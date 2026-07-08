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

    // Atomic composite of the three per-track lists (replaces the separate TRACKS/EXTENSIONS/CONTEXTS
    // files). Bundling track+extensionId+context per entry makes them physically un-desyncable, so a
    // torn/interleaved save can no longer mispair a track with a neighbour's extensionId, nor leave a
    // "tracks present, extensions absent" torn state that the orphan guard would wipe.
    private const val QUEUE_ENTRIES = "queue_entries"

    @Serializable
    private data class QueueEntry(
        val track: Track,
        val extensionId: String,
        val context: EchoMediaItem? = null,
    )

    private fun queueDir(context: Context) =
        File(context.filesDir, "context/queue").apply { mkdirs() }

    fun clearQueue(context: Context) {
        queueDir(context).listFiles()?.forEach { it.delete() }
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

    // Write the per-track lists as ONE atomic file (single temp-then-rename via saveToQueue), so track,
    // extensionId and context can never desync relative to each other. On a successful write, delete the
    // legacy split files so a stale copy can never be read later (single source of truth). Best-effort
    // delete: if it fails the legacy files linger harmlessly — recoverTracks/recoverQueue prefer the
    // composite and gate the orphan guard on its presence, so a stale split copy can't wipe or mispair.
    private fun Context.writeQueueEntries(list: List<MediaItem>) {
        val entries = list.map { QueueEntry(it.track, it.extensionId, it.context) }
        if (saveToQueue(QUEUE_ENTRIES, entries).isSuccess) {
            deleteQueueKey(TRACKS)
            deleteQueueKey(EXTENSIONS)
            deleteQueueKey(CONTEXTS)
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
        // New atomic composite: track+extensionId+context are bundled per entry, so they can never
        // desync. This is the source of truth for all saves written by this build.
        getFromQueue<List<QueueEntry>>(QUEUE_ENTRIES)?.let { entries ->
            return entries.map { MediaState.Unloaded(it.extensionId, it.track) to it.context }
        }
        // Legacy fallback (pre-composite installs, one migration restore): three parallel files.
        // Size-guard EXTENSIONS against TRACKS — a torn/desynced legacy state must NOT mispair a track
        // with a neighbour's extensionId (the harmful case that routes to the wrong extension), so bail
        // to no-restore instead of mispairing. CONTEXTS stays best-effort (getOrNull): a misaligned
        // context only mislabels "playing from" and never affects routing/resolution.
        val tracks = getFromQueue<List<Track>>(TRACKS) ?: return null
        val extensionIds = getFromQueue<List<String>>(EXTENSIONS)
        if (extensionIds == null || extensionIds.size != tracks.size) return null
        val contexts = getFromQueue<List<EchoMediaItem>>(CONTEXTS)
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
