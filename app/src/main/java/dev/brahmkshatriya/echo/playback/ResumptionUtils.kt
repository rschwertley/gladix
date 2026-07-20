package dev.brahmkshatriya.echo.playback

import android.content.Context
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.di.App
import dev.brahmkshatriya.echo.download.Downloader
import dev.brahmkshatriya.echo.extensions.MediaState
import dev.brahmkshatriya.echo.playback.MediaItemUtils.context
import dev.brahmkshatriya.echo.playback.MediaItemUtils.extensionId
import dev.brahmkshatriya.echo.playback.MediaItemUtils.track
import dev.brahmkshatriya.echo.utils.CacheUtils.getFromCache
import dev.brahmkshatriya.echo.utils.HealthMonitor
import dev.brahmkshatriya.echo.utils.Serializer.toData
import dev.brahmkshatriya.echo.utils.Serializer.toJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File

object ResumptionUtils {

    private const val TRACKS = "queue_tracks"
    private const val CONTEXTS = "queue_contexts"
    private const val EXTENSIONS = "queue_extensions"
    private const val INDEX = "queue_index"
    private const val CURRENT_ID = "queue_current_id"
    private const val POSITION = "position"
    private const val SHUFFLE = "shuffle"
    private const val REPEAT = "repeat"

    // Bound on how much of the persisted queue is materialized/persisted: current + up to this many
    // upcoming. Shared by the restore window (recoverQueue) and the save cap (capForPersist) so disk,
    // restore, and player converge on the same size. 2000 clears every normal queue (a big playlist ≈
    // hundreds; 2000 ≈ 130+ hrs ahead) and trims only would-OOM "play all" queues. Tunable.
    private const val QUEUE_CAP_UPCOMING = 2000

    // Atomic composite of the ESSENTIAL per-track pair (track + extensionId). Bundling just these two
    // keeps them physically un-desyncable — a torn/interleaved save can't mispair a track with a
    // neighbour's extensionId — while Track being a concrete @Serializable means this file ALWAYS
    // round-trips. The context is deliberately NOT bundled here: it's a polymorphic EchoMediaItem that
    // can fail to round-trip and is only cosmetic ("playing from"), so it lives in a separate best-effort
    // CONTEXTS file (see writeQueueEntries/recoverTracks). A prior build bundled the context into this
    // entry; one bad context then failed the whole decode and wiped the queue on cold restore. The read
    // still recovers those older BUNDLED files — ignoreUnknownKeys skips the extra `context` key — so the
    // essential pair survives regardless of whether that context would parse.
    private const val QUEUE_ENTRIES = "queue_entries"

    @Serializable
    private data class QueueEntry(
        val track: Track,
        val extensionId: String,
    )

    private fun queueDir(context: Context) =
        File(context.filesDir, "context/queue").apply { mkdirs() }

    fun clearQueue(context: Context) {
        queueDir(context).listFiles()?.forEach { it.delete() }
        // Also wipe the pre-7b3ad34b cacheDir location, so a stale legacy queue there can't resurrect
        // via the cacheDir fallback in recoverTracks after the user clears the queue (or after the
        // orphan guard). Best-effort; the dir usually no longer exists.
        File(context.cacheDir, "context/queue").listFiles()?.forEach { it.delete() }
    }

    private inline fun <reified T> Context.saveToQueue(id: String, data: T?) = runCatching {
        val dir = queueDir(this)
        val target = File(dir, id.hashCode().toString())
        val tmp = File(dir, "${id.hashCode()}.tmp")
        tmp.writeText(data.toJson())
        check(tmp.renameTo(target)) { "Queue rename failed for $id" }
    }

    private inline fun <reified T> Context.getFromQueue(id: String): T? {
        val file = File(queueDir(this), id.hashCode().toString())
        return if (file.exists()) runCatching { file.readText().toData<T>().getOrThrow() }.getOrNull()
        else null
    }

    private fun Context.deleteQueueKey(id: String) {
        File(queueDir(this), id.hashCode().toString()).delete()
    }

    private fun Context.hasQueueKey(id: String) =
        File(queueDir(this), id.hashCode().toString()).exists()

    // Cheap "is there a restorable queue?" check — a stat() per key, NO deserialization. For the
    // ButtonReceiver.shouldStartForegroundService main-thread gate, which only needs existence (not the
    // decoded tracks) and must return a Boolean synchronously, so it can't await the IO restoreDeferred.
    // QUEUE_ENTRIES is written only for a non-empty queue and clearQueue() deletes it, so its presence
    // faithfully means "non-empty queue saved and not cleared"; TRACKS covers the pre-composite legacy state
    // until the first save migrates it to QUEUE_ENTRIES. (The ancient cacheDir-only legacy path is
    // intentionally not stat'd — a vanishing population, worst case one missed media-button resume.)
    fun hasSavedQueue(context: Context) =
        context.hasQueueKey(QUEUE_ENTRIES) || context.hasQueueKey(TRACKS)

    // Write the ESSENTIAL pair (track + extensionId) as ONE atomic file — Track is concrete, so this
    // never fails to serialize, and it alone decides whether the queue survives a cold restore. Only on
    // its success do we touch anything else: write the context list SEPARATELY and BEST-EFFORT (a context
    // that won't serialize must never cost us the essential queue — that was the bundled-composite
    // regression); on a context-write failure drop the key so a stale, mispaired context can't be read
    // next time. Then retire the legacy essential files (QUEUE_ENTRIES supersedes them); CONTEXTS is
    // shared with this format, so it is NOT deleted.
    private fun Context.writeQueueEntries(list: List<MediaItem>) {
        val entries = list.map { QueueEntry(it.track, it.extensionId) }
        if (saveToQueue(QUEUE_ENTRIES, entries).isSuccess) {
            if (saveToQueue(CONTEXTS, list.map { it.context }).isFailure) deleteQueueKey(CONTEXTS)
            deleteQueueKey(TRACKS)
            deleteQueueKey(EXTENSIONS)
        }
    }

    private fun Player.mediaItems() = (0 until mediaItemCount).map { getMediaItemAt(it) }

    // Bound the persisted UPCOMING tail — the "play all" OOM vector. Keep [0 .. currentIndex + W] so the
    // saved INDEX (= currentMediaItemIndex, written synchronously by saveIndex) stays valid against the
    // list — NO re-base, so no index/list desync. before-current ≈ 0 in current+upcoming, so this is
    // ~current + W upcoming. Never empties a non-empty list: an in-range current is always retained.
    private fun List<MediaItem>.capForPersist(currentIndex: Int): List<MediaItem> {
        val safeCurrent = currentIndex.coerceIn(0, size - 1)
        val end = (safeCurrent + 1 + QUEUE_CAP_UPCOMING).coerceAtMost(size)
        return if (end >= size) this else subList(0, end).toList()
    }

    fun saveIndex(context: Context, index: Int, currentId: String?) {
        context.saveToQueue(INDEX, index)
        context.saveToQueue(CURRENT_ID, currentId)
    }

    suspend fun saveQueue(context: Context, player: Player) = withContext(Dispatchers.Main) {
        val list = player.mediaItems()
        Log.d("GladixPlayback", "saveQueue: itemCount=${list.size}")
        if (list.isEmpty()) {
            Log.d("GladixPlayback", "saveQueue: empty — stack: ${Thread.currentThread().stackTrace.take(10).joinToString(" < ") { it.methodName }}")
            return@withContext
        }
        // Persist the current index. currentId is the ground-truth current track id for the restore
        // tripwire; both are read here on the main thread before the IO writes.
        val currentIndex = player.currentMediaItemIndex
        val currentId = player.currentMediaItem?.mediaId
        val capped = list.capForPersist(currentIndex)   // currentIndex stays valid within capped
        withContext(Dispatchers.IO) {
            context.saveToQueue(INDEX, currentIndex)
            context.saveToQueue(CURRENT_ID, currentId)
            context.writeQueueEntries(capped)
        }
    }

    fun saveCurrentPos(context: Context, position: Long) {
        context.saveToQueue(POSITION, position)
    }

    // Synchronous teardown flush — called on the main thread from PlayerService.onDestroy BEFORE the
    // player is released and the service scope is cancelled. scope.cancel() drops any pending debounced
    // saveQueue (PlayerEventListener.scheduleSaveQueue), so without this a final advance inside the
    // debounce window would leave a stale TRACKS against a fresh INDEX/CURRENT_ID (saveIndex fires
    // synchronously on each transition) → wrong-track on restore. Reads the player inline (already on
    // the main thread) and writes files synchronously; NOT a coroutine, so no Main-dispatch deadlock.
    fun saveQueueBlocking(context: Context, player: Player) {
        val list = player.mediaItems()
        if (list.isEmpty()) return
        val currentIndex = player.currentMediaItemIndex
        context.saveToQueue(INDEX, currentIndex)
        context.saveToQueue(CURRENT_ID, player.currentMediaItem?.mediaId)
        context.writeQueueEntries(list.capForPersist(currentIndex))
    }

    fun Context.recoverTracks(): List<Pair<MediaState.Unloaded<Track>, EchoMediaItem?>>? {
        // Essential composite (track + extensionId). This ONE decode handles BOTH the current de-bundled
        // format AND the older BUNDLED composite: the bundled file also carries a `context` key, which
        // ignoreUnknownKeys skips, so the essential pair is recovered whether or not that context would
        // round-trip (this is what un-wipes users migrating off the bundled build). Success is decided by
        // the essential pair alone. Context is read SEPARATELY and BEST-EFFORT — a context list that won't
        // parse (or is simply absent, as on a bundled-era file) yields null labels, never an empty queue.
        getFromQueue<List<QueueEntry>>(QUEUE_ENTRIES)?.let { entries ->
            val contexts = getFromQueue<List<EchoMediaItem?>>(CONTEXTS)
            return entries.mapIndexed { index, entry ->
                MediaState.Unloaded(entry.extensionId, entry.track) to contexts?.getOrNull(index)
            }
        }
        // Legacy three-file fallback (pre-composite installs, one migration restore). Try the current
        // filesDir location first; if nothing valid is there, try the pre-7b3ad34b cacheDir location —
        // queue storage moved cacheDir→filesDir at 7b3ad34b with no data copy, so a queue saved by an
        // older build lives in cacheDir/context/queue. Recovers it if the cache survived the update.
        // The composite check above short-circuits first, so this only runs when no composite exists
        // (genuine pre-composite state) — a stale cacheDir queue can never override a real composite one.
        return assembleLegacy(
            getFromQueue<List<Track>>(TRACKS),
            getFromQueue<List<String>>(EXTENSIONS),
            getFromQueue<List<EchoMediaItem?>>(CONTEXTS),
        ) ?: assembleLegacy(
            getFromCache<List<Track>>(TRACKS, "queue"),
            getFromCache<List<String>>(EXTENSIONS, "queue"),
            getFromCache<List<EchoMediaItem?>>(CONTEXTS, "queue"),
        )
    }

    // Pairs the three parallel legacy lists into (track+extensionId, context) entries. Size-guard
    // EXTENSIONS against TRACKS — a torn/desynced legacy state must NOT mispair a track with a
    // neighbour's extensionId (the harmful case that routes to the wrong extension), so bail to null
    // instead of mispairing. CONTEXTS stays best-effort (getOrNull): a misaligned context only mislabels
    // "playing from" and never affects routing/resolution. Returns null when there's nothing usable, so
    // recoverTracks can fall through to the next source.
    private fun assembleLegacy(
        tracks: List<Track>?,
        extensionIds: List<String>?,
        contexts: List<EchoMediaItem?>?,
    ): List<Pair<MediaState.Unloaded<Track>, EchoMediaItem?>>? {
        if (tracks == null) return null
        if (extensionIds == null || extensionIds.size != tracks.size) return null
        return tracks.mapIndexed { index, track ->
            MediaState.Unloaded(extensionIds[index], track) to contexts?.getOrNull(index)
        }
    }

    private fun Context.recoverQueue(
        app: App,
        downloads: List<Downloader.Info>,
        healthMonitor: HealthMonitor? = null,
    ): List<MediaItem>? {
        // Orphan guard is LEGACY-ONLY: it inspects the old split files, so it must not run when the
        // composite exists (the composite is authoritative, and a stale legacy file that survived
        // cleanup must never trigger clearQueue() on a valid composite queue).
        if (!hasQueueKey(QUEUE_ENTRIES)) {
            val rawTracks = getFromQueue<List<Track>>(TRACKS)
            val rawExtensions = getFromQueue<List<String>>(EXTENSIONS)
            if (rawTracks != null && (rawExtensions == null || rawExtensions.isEmpty())) {
                clearQueue(this)
                healthMonitor?.report(
                    HealthMonitor.OrphanedSessionException(rawTracks.size, rawTracks.firstOrNull()?.id ?: "unknown"),
                    HealthMonitor.Scope.PERSISTENT, 24 * 60 * 60 * 1000L
                )
                return emptyList()
            }
        }
        val tracks = recoverTracks() ?: return null
        if (tracks.isEmpty()) return emptyList()

        // ── Slice BEFORE the heavy build (the OOM fix) ──────────────────────────────────────────────
        // Locate current on the LIGHTWEIGHT entries — CURRENT_ID == track.id (MediaItemUtils.build does
        // setMediaId(state.item.id)), so idOf = { it.first.item.id }, identical to the AA resume tiles.
        // We then build ONLY current + W upcoming, so the full ×N heavy build (each item embeds serialized
        // state/context/cover) never happens. This slice ALSO subsumes the old P2 subList: it starts the
        // window AT current, dropping any stranded before-current tracks, so recoverPlaylist must NOT
        // re-base again — exactly one re-base, here.
        val rawIndex = recoverIndex() ?: C.INDEX_UNSET
        val coercedIndex = when {
            rawIndex == C.INDEX_UNSET -> 0
            rawIndex < tracks.size -> rawIndex
            else -> tracks.size - 1
        }
        val savedCurrentId = recoverCurrentId()
        val current = resolveCurrentIndex(tracks, coercedIndex, savedCurrentId) { it.first.item.id }
            .coerceIn(0, tracks.size - 1)   // valid, non-empty window: current is always in range
        // ResumeIndexMismatch telemetry (relocated from recoverPlaylist; track.id in place of mediaId).
        val actualId = tracks.getOrNull(coercedIndex)?.first?.item?.id
        if (savedCurrentId != null && actualId != null && savedCurrentId != actualId) {
            healthMonitor?.report(
                HealthMonitor.ResumeIndexMismatchException(savedCurrentId, actualId, coercedIndex, tracks.size),
                HealthMonitor.Scope.PERSISTENT, 24 * 60 * 60 * 1000L
            )
        }
        val end = (current + 1 + QUEUE_CAP_UPCOMING).coerceAtMost(tracks.size)
        val window = tracks.subList(current, end)   // current at window index 0; before-current dropped

        // Skip-and-continue: build each saved item independently so ONE unbuildable entry (a partial/older-
        // format save, a mistyped item, a null field on a Track) can't throw out of the whole restore and
        // brick cold start. resolveCurrentIndex already relocated current on the full entries above.
        val built = window.mapNotNull { (state, item) ->
            runCatching { MediaItemUtils.build(app, downloads, state, item) }.getOrNull()
        }
        val dropped = window.size - built.size
        if (dropped > 0)
            Log.w("GladixPlayback", "recoverQueue: windowed [$current,$end) of ${tracks.size}; skipped $dropped/${window.size} unbuildable")
        // Empty-window fallback — bounding must NEVER wipe a non-empty queue. Only reachable if every item
        // in a ~2000-wide window is unbuildable (effectively impossible); if so, DON'T cap — build the full
        // list rather than emit empty. Correctness (never lose the queue) outranks the theoretical heap risk.
        if (built.isEmpty()) return tracks.mapNotNull { (state, item) ->
            runCatching { MediaItemUtils.build(app, downloads, state, item) }.getOrNull()
        }
        return built
    }

    fun Context.recoverIndex() = getFromQueue<Int>(INDEX)
    fun Context.recoverCurrentId() = getFromQueue<String>(CURRENT_ID)
    private fun Context.recoverPosition() = getFromQueue<Long>(POSITION)

    fun Context.recoverShuffle() = getFromQueue<Boolean>(SHUFFLE)
    fun saveShuffle(context: Context, shuffle: Boolean) {
        context.saveToQueue(SHUFFLE, shuffle)
    }

    fun Context.recoverRepeat() = getFromQueue<Int>(REPEAT)
    fun saveRepeat(context: Context, repeat: Int) {
        context.saveToQueue(REPEAT, repeat)
    }

    // Single resolver for "which item is current on restore", shared by recoverPlaylist and the AA resume
    // tiles. CURRENT_ID (written synchronously by saveIndex) is ground truth; the saved index can lead the
    // debounced TRACKS on disk after a hard kill (the auto-advance skew), leaving the PREVIOUS track at
    // coercedIndex. Relocate to CURRENT_ID via indexOfFirst — the earliest occurrence is at-or-before the
    // true position, so a subList by the caller always keeps the true current and never trims past it (a
    // directional at-or-after search could drop it on a backward/Previous skew). Falls back to coercedIndex
    // (today's behavior) when CURRENT_ID is unsaved or absent from the list (edit/append race). Returns an
    // INDEX so no caller re-looks-up a mediaId; idOf is passed because the two surfaces hold different
    // element types (MediaItem here vs recovered Unloaded pairs on the AA tiles).
    fun <T> resolveCurrentIndex(
        items: List<T>,
        coercedIndex: Int,
        savedCurrentId: String?,
        idOf: (T) -> String?,
    ): Int {
        if (savedCurrentId == null) return coercedIndex
        if (items.getOrNull(coercedIndex)?.let(idOf) == savedCurrentId) return coercedIndex
        val found = items.indexOfFirst { idOf(it) == savedCurrentId }
        return if (found >= 0) found else coercedIndex
    }

    fun Context.recoverPlaylist(
        app: App,
        downloads: List<Downloader.Info>,
        healthMonitor: HealthMonitor? = null,
    ): Triple<List<MediaItem>, Int, Long> {
        val items = recoverQueue(app, downloads, healthMonitor) ?: emptyList()
        // recoverQueue already sliced-before-build AND re-based current to index 0 (it subsumed the old P2
        // subList and moved the index-repair + ResumeIndexMismatch telemetry upstream). Do NOT re-resolve or
        // re-slice here — a second re-base is exactly the device-confirmed wrong-track bug. Exactly one
        // re-base, in recoverQueue. items[0] is current (or the list is empty).
        val rawPos = recoverPosition() ?: 0L
        if (items.isEmpty()) return Triple(items, C.INDEX_UNSET, rawPos)
        val trackDuration = items.first().track.duration
        val safePos = when {
            trackDuration != null && trackDuration > 0 && rawPos >= trackDuration + 2_000 -> 0L
            trackDuration == null && rawPos > 90 * 60_000L -> 0L
            else -> rawPos
        }
        Log.d("GladixPlayback", "recoverPlaylist: ${items.size} items index=0 (windowed) pos=$rawPos safePos=$safePos duration=$trackDuration")
        return Triple(items, 0, safePos)
    }
}
