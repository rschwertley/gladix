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
        withContext(Dispatchers.IO) {
            context.saveToQueue(INDEX, currentIndex)
            context.saveToQueue(CURRENT_ID, currentId)
            context.writeQueueEntries(list)
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
        context.saveToQueue(INDEX, player.currentMediaItemIndex)
        context.saveToQueue(CURRENT_ID, player.currentMediaItem?.mediaId)
        context.writeQueueEntries(list)
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
        return tracks.map { (state, item) ->
            MediaItemUtils.build(app, downloads, state, item)
        }
    }

    fun Context.recoverIndex() = getFromQueue<Int>(INDEX)
    private fun Context.recoverCurrentId() = getFromQueue<String>(CURRENT_ID)
    private fun Context.recoverPosition() = getFromQueue<Long>(POSITION)

    fun Context.recoverShuffle() = getFromQueue<Boolean>(SHUFFLE)
    fun saveShuffle(context: Context, shuffle: Boolean) {
        context.saveToQueue(SHUFFLE, shuffle)
    }

    fun Context.recoverRepeat() = getFromQueue<Int>(REPEAT)
    fun saveRepeat(context: Context, repeat: Int) {
        context.saveToQueue(REPEAT, repeat)
    }

    fun Context.recoverPlaylist(
        app: App,
        downloads: List<Downloader.Info>,
        healthMonitor: HealthMonitor? = null,
    ): Triple<List<MediaItem>, Int, Long> {
        val items = recoverQueue(app, downloads, healthMonitor) ?: emptyList()
        // INDEX and TRACKS are saved independently; a crash or system kill between the two
        // writes can leave INDEX > items.size, which causes PlayerInfo.Builder.build() to
        // throw an IllegalStateException when Media3 checks mediaItemIndex < windowCount.
        val rawIndex = recoverIndex() ?: C.INDEX_UNSET
        val index = when {
            items.isEmpty() -> C.INDEX_UNSET
            rawIndex == C.INDEX_UNSET -> 0
            rawIndex < items.size -> rawIndex
            else -> items.size - 1
        }
        // Tripwire: the track at the restored index must be the one that was current at save time.
        // A mismatch means the persisted index and queue disagree (e.g. a future regression saving a
        // windowed index against the full order). Diagnostic only — restore still proceeds.
        val savedCurrentId = recoverCurrentId()
        val actualId = items.getOrNull(index)?.mediaId
        if (savedCurrentId != null && actualId != null && savedCurrentId != actualId) {
            healthMonitor?.report(
                HealthMonitor.ResumeIndexMismatchException(savedCurrentId, actualId, index, items.size),
                HealthMonitor.Scope.PERSISTENT, 24 * 60 * 60 * 1000L
            )
        }
        val rawPos = recoverPosition() ?: 0L
        val trackDuration = items.getOrNull(index)?.track?.duration
        val safePos = when {
            trackDuration != null && trackDuration > 0 && rawPos >= trackDuration + 2_000 -> 0L
            trackDuration == null && rawPos > 90 * 60_000L -> 0L
            else -> rawPos
        }
        Log.d("GladixPlayback", "recoverPlaylist: returning ${items.size} items index=$index pos=$rawPos safePos=$safePos duration=$trackDuration")
        // P2 — current+upcoming: the current track must restore at index 0. A queue persisted by an
        // older build can carry a non-zero index with "before" tracks stranded above current (which,
        // unlike a fresh play, never clear by advancing); drop them so restore comes back at the saved
        // track as index 0. Post-fix saves are always index 0, so this is a no-op for queues written by
        // this build, and it heals both the phone (PlayerService) and AA (resume/onPlaybackResumption)
        // paths at their single shared source. Naively coercing index to 0 without slicing would resume
        // the WRONG, earlier track — hence the subList.
        if (index != C.INDEX_UNSET && index > 0)
            return Triple(items.subList(index, items.size).toList(), 0, safePos)
        return Triple(items, index, safePos)
    }
}
