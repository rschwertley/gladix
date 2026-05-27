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
import dev.brahmkshatriya.echo.utils.Serializer.toData
import dev.brahmkshatriya.echo.utils.Serializer.toJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object ResumptionUtils {

    private const val TRACKS = "queue_tracks"
    private const val CONTEXTS = "queue_contexts"
    private const val EXTENSIONS = "queue_extensions"
    private const val INDEX = "queue_index"
    private const val POSITION = "position"
    private const val SHUFFLE = "shuffle"
    private const val REPEAT = "repeat"

    private fun queueDir(context: Context) =
        File(context.filesDir, "context/queue").apply { mkdirs() }

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

    private fun Player.mediaItems() = (0 until mediaItemCount).map { getMediaItemAt(it) }

    fun saveIndex(context: Context, index: Int) {
        context.saveToQueue(INDEX, index)
    }

    suspend fun saveQueue(context: Context, player: Player) = withContext(Dispatchers.Main) {
        val list = player.mediaItems()
        Log.d("GladixPlayback", "saveQueue: itemCount=${list.size}")
        if (list.isEmpty()) {
            Log.d("GladixPlayback", "saveQueue: empty — stack: ${Thread.currentThread().stackTrace.take(10).joinToString(" < ") { it.methodName }}")
            return@withContext
        }
        val currentIndex = player.currentMediaItemIndex
        withContext(Dispatchers.IO) {
            val extensionIds = list.map { it.extensionId }
            val tracks = list.map { it.track }
            val contexts = list.map { it.context }
            context.saveToQueue(INDEX, currentIndex)
            context.saveToQueue(EXTENSIONS, extensionIds)
            context.saveToQueue(TRACKS, tracks)
            context.saveToQueue(CONTEXTS, contexts)
        }
    }

    fun saveCurrentPos(context: Context, position: Long) {
        context.saveToQueue(POSITION, position)
    }

    fun Context.recoverTracks(): List<Pair<MediaState.Unloaded<Track>, EchoMediaItem?>>? {
        val tracks = getFromQueue<List<Track>>(TRACKS)
        val extensionIds = getFromQueue<List<String>>(EXTENSIONS)
        val contexts = getFromQueue<List<EchoMediaItem>>(CONTEXTS)
        return tracks?.mapIndexedNotNull { index, track ->
            val extensionId = extensionIds?.getOrNull(index) ?: return@mapIndexedNotNull null
            val item = contexts?.getOrNull(index)
            MediaState.Unloaded(extensionId, track) to item
        }
    }

    private fun Context.recoverQueue(
        app: App,
        downloads: List<Downloader.Info>,
    ): List<MediaItem>? {
        val tracks = recoverTracks() ?: return null
        return tracks.map { (state, item) ->
            MediaItemUtils.build(app, downloads, state, item)
        }
    }

    fun Context.recoverIndex() = getFromQueue<Int>(INDEX)
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
    ): Triple<List<MediaItem>, Int, Long> {
        val items = recoverQueue(app, downloads) ?: emptyList()
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
        val rawPos = recoverPosition() ?: 0L
        val trackDuration = items.getOrNull(index)?.track?.duration
        val safePos = when {
            trackDuration != null && trackDuration > 0 && rawPos >= trackDuration + 2_000 -> 0L
            trackDuration == null && rawPos > 90 * 60_000L -> 0L
            else -> rawPos
        }
        Log.d("GladixPlayback", "recoverPlaylist: returning ${items.size} items index=$index pos=$rawPos safePos=$safePos duration=$trackDuration")
        return Triple(items, index, safePos)
    }
}
