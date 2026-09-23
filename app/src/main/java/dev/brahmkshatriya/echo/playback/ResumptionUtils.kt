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
import dev.brahmkshatriya.echo.extensions.builtin.unified.UnifiedExtension
import dev.brahmkshatriya.echo.history.db.toSlim
import dev.brahmkshatriya.echo.history.db.toSlimContext
import dev.brahmkshatriya.echo.playback.MediaItemUtils.context
import dev.brahmkshatriya.echo.playback.MediaItemUtils.state
import dev.brahmkshatriya.echo.playback.MediaItemUtils.track
import dev.brahmkshatriya.echo.utils.CacheUtils.getFromCache
import dev.brahmkshatriya.echo.utils.CrashKeys
import dev.brahmkshatriya.echo.utils.HealthMonitor
import dev.brahmkshatriya.echo.utils.Serializer.json
import dev.brahmkshatriya.echo.utils.Serializer.toJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.decodeFromStream
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
    // hundreds; 2000 ≈ 130+ hrs ahead) and trims only would-OOM "play all" queues. Tunable. The build-1008
    // OOM fix is slim-on-write (writeQueueEntries), NOT this cap — 2000 slim entries is only ~1.5–2.4 MB.
    private const val QUEUE_CAP_UPCOMING = 2000

    // Size-gate for the queue JSON files (build 1008 OOM defense). Any queue file larger than this is
    // discarded UNREAD (see getFromQueue / the cacheDir legacy read) rather than pulled into a giant
    // String/decode — the exact readText/decode that OOM'd the 256 MB heap on fat, cap-2000 "play all"
    // files. Chosen to sit in the gap between slim files and OOM-class fat files: with encodeDefaults=false
    // and toSlim dropping streamables/extras/nested (but KEEPING track/artist/album covers, which dominate),
    // a slim entry is ~0.74–1.2 KB, so a FULL 2000-entry slim QUEUE_ENTRIES tops out ≈ 2.4 MB for a
    // collab-heavy queue (CONTEXTS far less). 4 MB leaves ≈1.7× margin so a full slim file is NEVER falsely
    // dropped; it also clears a normal EXISTING fat queue (a few hundred fat tracks) so those survive the
    // upgrade read and get slimmed on next save, and sits BELOW the pathological fat files (cap-2000 fat
    // ≈ 5–15 MB) that OOM. Tunable; do NOT drop toward the ~2.4 MB full-2000 slim ceiling.
    private const val QUEUE_FILE_MAX_BYTES = 4L * 1024 * 1024

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

    /**
     * Puts the extension stamp back on a restored track.
     *
     * ⚠️ WHY THIS IS NEEDED AT ALL. saveQueue persists `QueueEntry(s.item.toSlim(), s.extensionId)`, and
     * History's Track.toSlim() sets `extras = emptyMap()` — so the track's own `extension_id` is destroyed
     * on every save. The id survives BESIDE the track and is restored into MediaState.Unloaded, which is
     * why `mediaItem.extensionId` has always been correct; the TRACK's copy is what goes missing.
     * Introduced by 92af04f5 (2026-07-26), whose own comment says "toSlim dropping streamables/extras/
     * nested" — the loss was stated and never traced. de6d344b (2026-08-10) only re-routed the surviving
     * copy and is innocent.
     * ⚠️ [CLARIFIED 2026-09-16] READ "THE LOSS" ABOVE AS THE `extras` HALF ONLY. That sentence is
     * about the extension stamp and is correct for it. It has ALREADY misled one reading into treating the
     * dropped `streamables` as an untraced loss too, and they are not: dropping them is DELIBERATE (a
     * CursorWindow crash at row 53, plus a cold-start crash for three users - see the note at
     * HistoryEntity.toSlim), and the contract is that each extension repopulates them in loadTrack. A
     * `streamables` problem is therefore a loadTrack problem in ONE extension, and never a reason to
     * change toSlim. OfflineExtension was the violator; fixed 2026-09-16.
     *
     * WHAT THE MISSING STAMP BROKE: UnifiedExtension resolves a sub-extension from `track.extras`, so a
     * restored track threw ExtensionNotFoundException(null) in radio() (the reported non-fatal, via
     * PlayerRadio.loadPlaylist on a cold start restoring a one-item queue with auto-radio on), in
     * loadFeed(track), and — on EVERY restored track — in loadTrack(), where Cached.loadMedia's fallback
     * caught it and served the cached copy instead. See the note on UnifiedExtension.loadTrack.
     *
     * ⚠️ RESTORE SIDE, NOT SAVE SIDE, DELIBERATELY. Preserving the key through the slim would only help
     * queues saved AFTER the change; re-stamping here repairs the queues already on disk, using data that
     * is already in the entry. The systematic follow-up — a queue-local slimmer that keeps routing keys,
     * the way Cached.toPlaylistSlim already keeps NEXT/playlist_id — is parked; it only affects
     * DeezerUtil.log's play-context telemetry, which is the one other thing toSlim drops that nothing
     * repopulates.
     *
     * Only ADDS the key, never overwrites: a track that somehow kept its own stamp keeps it.
     */
    /**
     * ⚠⚠ A QUEUE-LOCAL SLIMMER THAT KEEPS THE ONE ROUTING KEY. This is the fix for the
     * defect recorded at UnifiedExtension.loadTrack: toSlim() sets `extras = emptyMap()`, which
     * destroys a track's own `extension_id` on save, and for a UNIFIED queue that id is the only
     * record of WHICH SUB-EXTENSION the track came from.
     * ⚠⚠ IT DOES NOT RE-FATTEN ANYTHING, AND THAT DISTINCTION IS LOAD-BEARING. toSlim's
     * stripping is deliberate twice over - a History CursorWindow OOM at row 53 of a 2MB window
     * (~38KB/row), and the queue analogue where 2000 fat entries OOM'd. What made those fat was
     * TRACK_TOKEN and the nested object graphs; this keeps ONE SHORT STRING, an extension id.
     * ⚠⚠ A SLIMMER THAT KEEPS EXACTLY WHAT ONE DOWNSTREAM CONSUMER NEEDS IS THE ESTABLISHED
     * PATTERN HERE, WITH TWO PRIOR INSTANCES - this is not a special case pleaded for itself:
     *   toSlimContext        keeps a Radio's extras, deliberately, because radio re-resolution
     *                        reads kind + seeds from them - it is the single source of truth that
     *                        work depends on.
     *   Cached.toPlaylistSlim keeps album.id/title/cover so "Go to Album" still works from a
     *                        cached track.
     * Each drops the bulk and keeps one routing or navigation key. So does this.
     * DO NOT widen it to keep extras generally - that IS the re-fatten the two OOMs forbid, and the
     * difference between the two is the whole point: keep a key, never keep a payload.
     */
    private fun MediaState<Track>.queueSlim() = item.toSlim().let { slim ->
        val routing = item.extras.filterKeys { it == UnifiedExtension.EXTENSION_ID }
        if (routing.isEmpty()) slim else slim.copy(extras = routing)
    }

    /**
     * Back-fills the stamp for queues saved BEFORE queueSlim existed. New saves carry it already, so
     * the `containsKey` guard makes this a no-op for them.
     *
     * ⚠⚠ IT MUST NOT STAMP "unified" - THAT IS THE TRAP THIS FUNCTION USED TO SET.
     * `extensionId` here is the extension the QUEUE played under, which for a Unified queue is
     * UNIFIED_ID. Stamping it satisfies the null check and then fails one layer down, because
     * UnifiedExtension.extensions() filters `id != UNIFIED_ID` - turning
     * ExtensionNotFoundException(null) into a bare Exception("Extension unified not found") and
     * fixing nothing. For an old Unified queue there IS no recoverable sub-extension id, so leave it
     * unstamped: the accessor then throws, Cached.loadMedia's cache fallback serves the previously
     * cached state, and playback continues exactly as it does today.
     */
    private fun Track.restamped(extensionId: String) = when {
        extras.containsKey(UnifiedExtension.EXTENSION_ID) -> this
        extensionId == UnifiedExtension.UNIFIED_ID -> this
        else -> copy(extras = extras + (UnifiedExtension.EXTENSION_ID to extensionId))
    }

    private fun queueDir(context: Context) =
        File(context.filesDir, "context/queue").apply { mkdirs() }

    fun clearQueue(context: Context) {
        queueGeneration++
        queueDir(context).listFiles()?.forEach { it.delete() }
        // Also wipe the pre-7b3ad34b cacheDir location, so a stale legacy queue there can't resurrect
        // via the cacheDir fallback in recoverTracks after the user clears the queue (or after the
        // orphan guard). Best-effort; the dir usually no longer exists.
        File(context.cacheDir, "context/queue").listFiles()?.forEach { it.delete() }
    }

    // Bumped by EVERY queue write and by clearQueue, so PlayerState.restoreCache can tell "the queue on
    // disk is the one I already built items for" from "it changed". In-memory and exact: the cache is only
    // ever read by a SECOND-or-later PlayerService instance in the SAME process, and this app is
    // single-process, so nothing can change these files without passing through here first. Deliberately
    // NOT File.lastModified(): that is second-granular on some filesystems, and scheduleSaveQueue is
    // debounced at 300ms, so two saves inside one second would collide and serve a stale cache.
    // ⚠⚠ ONE OF TWO MONOTONIC QUEUE COUNTERS, AND THEY ANSWER DIFFERENT QUESTIONS. DO NOT
    // UNIFY THEM - checked 2026-09-11, they diverge in BOTH directions:
    //   queueGeneration (here)      "HAS THE PERSISTED QUEUE CHANGED?" Bumped in saveToQueue and
    //                               clearQueue, i.e. on WRITES TO DISK. Consumer: PlayerState.restoreCache,
    //                               which is keyed on it so a cached restore is discarded once the saved
    //                               queue moves on.
    //   ShufflePlayer.queueEpoch    "HAS THE LIVE QUEUE BEEN REPLACED?" Bumped at the eight replacement
    //                               seams (setMediaItem x3, setMediaItems x3, clearMediaItems, release),
    //                               verified NOT to fire on ordinary advance. Consumers: appendDeduped's
    //                               ownership check, the Last.fm latch key, and PlayerState.Radio.Loaded's
    //                               epoch.
    // THEY DIVERGE BOTH WAYS, so neither can serve for the other:
    //   - an ordinary debounced save during playback bumps THIS and not the epoch (no replacement);
    //   - a setMediaItems bumps the EPOCH immediately while the save is still 300ms away.
    // Related but not identical, which is the same trap as the five queue-replacement markers inventoried
    // at ShufflePlayer.markQueueReplaced: names that sound interchangeable, semantics that are not.
    @Volatile
    var queueGeneration: Long = 0L
        private set

    private inline fun <reified T> Context.saveToQueue(id: String, data: T?) = runCatching {
        queueGeneration++
        val dir = queueDir(this)
        val target = File(dir, id.hashCode().toString())
        val tmp = File(dir, "${id.hashCode()}.tmp")
        tmp.writeText(data.toJson())
        check(tmp.renameTo(target)) { "Queue rename failed for $id" }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private inline fun <reified T> Context.getFromQueue(id: String): T? {
        val file = File(queueDir(this), id.hashCode().toString())
        if (!file.exists()) return null
        // Size-gate BEFORE any readText, on EVERY key that goes through here (QUEUE_ENTRIES, CONTEXTS, and
        // the legacy TRACKS/EXTENSIONS reads; the tiny scalar keys never approach the limit). An oversized
        // file — a pre-slim fat queue big enough to OOM readText/decode — is deleted UNREAD and treated as
        // absent, so cold start proceeds with no queue and the next save writes a slim file. This is the
        // build-1008 upgrade trap: the first post-upgrade read would otherwise pull the fat file into memory
        // before slim-on-write could ever help. Mirrors the History migration discarding an oversized row.
        if (file.length() > QUEUE_FILE_MAX_BYTES) {
            file.delete()
            return null
        }
        // Stream-decode instead of readText()+decodeFromString (build-1013 OOM). readText built the ENTIRE
        // file into one String (StringWriter.toString) — that transient whole-file String, ~3–5× the file
        // size, is what OOM'd the 256 MB heap when recoverTracks fires concurrently at cold start (service
        // restore + AA tiles), even for sub-gate files. decodeFromStream parses straight from a buffered
        // stream, allocating only the (slim) decoded objects, never a whole-file String. Same Json instance
        // → byte-for-byte identical parse/result. Gate above still cheaply rejects genuinely huge files.
        return runCatching {
            file.inputStream().buffered().use { json.decodeFromStream<T>(it) }
        }.getOrNull()
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
        // Slim every entry before persisting (build-1008 OOM fix): drop streamables/extras/description/
        // nested-artist-album heaviness via the SAME History slimmers (toSlim/toSlimContext), which keep
        // exactly what the queue needs — id (playback re-resolves by id), extensionId, and display fields —
        // and, crucially, PRESERVE Radio context extras (toSlimContext keeps them for a Radio), so the
        // recent radio work's re-resolution + "<title> Radio" header survive restore. This shrinks the file
        // ~10× (fixing the readText/decode OOM) and shrinks the build-time re-serialization in toMetaData.
        // Decode the serialized state ONCE per item, then read both fields off the object. `it.track` and
        // `it.extensionId` each route through the MediaItem accessor → getSerialized("state"), so the prior
        // form deserialized the whole MediaState<Track> graph TWICE per entry — N-fold on a large queue, on
        // every debounced save. Hoisting to one `it.state` decode halves it. (Zero decodes would require
        // carrying the MediaState as an in-process object on the item rather than serialized in extras — a
        // separate, larger change.)
        val entries = list.map { it.state.let { s -> QueueEntry(s.queueSlim(), s.extensionId) } }
        if (saveToQueue(QUEUE_ENTRIES, entries).isSuccess) {
            if (saveToQueue(CONTEXTS, list.map { it.context?.toSlimContext() }).isFailure)
                deleteQueueKey(CONTEXTS)
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
        CrashKeys.onQueueSize(list.size)   // player_media_item_count (debounced 300ms save — not hot)
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
                MediaState.Unloaded(entry.extensionId, entry.track.restamped(entry.extensionId)) to
                    contexts?.getOrNull(index)
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
            // Same size-gate as getFromQueue (via maxBytes): the ancient cacheDir fat-Track queue is the
            // last unguarded fat-read path, so an oversized legacy file here is skipped UNREAD too.
            getFromCache<List<Track>>(TRACKS, "queue", maxBytes = QUEUE_FILE_MAX_BYTES),
            getFromCache<List<String>>(EXTENSIONS, "queue", maxBytes = QUEUE_FILE_MAX_BYTES),
            getFromCache<List<EchoMediaItem?>>(CONTEXTS, "queue", maxBytes = QUEUE_FILE_MAX_BYTES),
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
        //
        // ⚠️ THIS FIRES PRE-HEAL, BY DESIGN — it compares against coercedIndex (the SAVED index), not
        // against `current` (the index resolveCurrentIndex just resolved by id). That is deliberate: the
        // question being reported is "was the persisted INDEX stale", and comparing post-heal would answer
        // a different question and report nothing whenever the heal worked.
        //
        // ⚠️ WHICH IS WHY THE TWO OUTCOMES GET DIFFERENT SCOPES. DO NOT "SIMPLIFY" THIS BACK TO ONE.
        // Until 2026-09-07 both were reported identically at PERSISTENT and were indistinguishable in
        // triage, which made the signal far weaker than it looked — most of its volume is the harmless
        // case:
        //   found = true  -> the saved CURRENT_ID IS somewhere in the restored list; resolveCurrentIndex
        //                    starts the window there and THE RESTORE IS CORRECT. A stale INDEX corrected
        //                    by id is cosmetic, so MEMORY_ONLY: visible in a session, not competing for
        //                    attention with real breakage.
        //   found = false -> the saved current track is ABSENT from the restored list. resolveCurrentIndex
        //                    falls back to the stale index and THE USER RESUMES ON A DIFFERENT TRACK.
        //                    That is a real defect surfacing, so PERSISTENT with the 24h window.
        //
        // `found` is computed DIRECTLY — tracks.any { it.first.item.id == savedCurrentId } — rather than
        // derived from (current != coercedIndex). The derived form is equivalent today but says the wrong
        // thing: it asks "did the index move", and it would answer `false` for a saved id that happens to
        // sit at the stale index for some other reason. The direct form asks the question the severity
        // actually turns on.
        val actualId = tracks.getOrNull(coercedIndex)?.first?.item?.id
        if (savedCurrentId != null && actualId != null && savedCurrentId != actualId) {
            val found = tracks.any { it.first.item.id == savedCurrentId }
            healthMonitor?.report(
                HealthMonitor.ResumeIndexMismatchException(
                    savedCurrentId, actualId, coercedIndex, tracks.size, current, found
                ),
                if (found) HealthMonitor.Scope.MEMORY_ONLY else HealthMonitor.Scope.PERSISTENT,
                24 * 60 * 60 * 1000L
            )
        }
        // ⚠️ CONSIDERED AND DROPPED 2026-09-07: making this build LAZY — deferring MediaItemUtils.build
        // out of here and into each restoreDeferred consumer, so a consumer that needs one item builds one
        // item. Do not revive it on the memory argument; the reason it was dropped is structural and holds
        // even if every number changes.
        //
        // 1. IT DOWNGRADES A STRUCTURAL BOUND TO A CONVENTION. The cap below is applied to the WINDOW
        //    BEFORE the build, at the ONLY site that builds over persisted state (19 MediaItemUtils.build
        //    call sites exist at HEAD; exactly two are over persisted state and both are in this function).
        //    That is why "bounding this one site bounds all of them" is true. Deferring the build moves the
        //    cap into RestoreData and makes THREE consumer sites each responsible for honouring W — a
        //    property that currently cannot be violated becomes one that three future call sites must
        //    remember. That trade is bad at any queue size.
        // 2. IT SAVES NOTHING ON THE DEFAULT PATH. With KEEP_QUEUE on, applyRestoreIfCold calls
        //    setMediaItems(data.items, …) with the WHOLE list on every cold start, so the UI's one-item
        //    consumer reads work already caused, not work it causes. An earlier scoping claimed otherwise
        //    ("consumer 3 pays for 2,001 builds to display one track") and that was simply wrong. The only
        //    default-path saving is a sub-second race where applyRestoreIfCold loses its CAS to a user
        //    play. The real benefit is confined to KEEP_QUEUE-OFF users with large queues, and someone who
        //    turned queue restore off is the least likely person to be carrying a 5,000-track queue.
        // 3. IT IS THE SAME SIDE OF A DISTINCTION ALREADY DRAWN. A previous audit refuted withUnloaded()
        //    as a bound ("a per-item flag flip, if anything an extra full-N pass"). Lazy construction is a
        //    different mechanism — it prevents builds rather than transforming built items — but it lands
        //    in the same place: per-item work is not where the bound lives. The bound lives in HOW MANY
        //    ITEMS ENTER THE PIPELINE, which is QUEUE_CAP_UPCOMING, right here.
        //
        // The work that DOES help is orthogonal and parked at MediaItemUtils.toMetaData: shrink the
        // PER-ITEM PAYLOAD. That changes the size of each item rather than how many are built or when, so
        // it relocates no bound, and it compounds into the retained timeline, every remote controller
        // connect, every save decode and the restore snapshot at once.
        val end = (current + 1 + QUEUE_CAP_UPCOMING).coerceAtMost(tracks.size)
        val window = tracks.subList(current, end)   // current at window index 0; before-current dropped
        CrashKeys.onQueueBuild(window.size)   // restore_build_count + heap sample BEFORE the OOM-prone build

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
