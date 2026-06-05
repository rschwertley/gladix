package dev.brahmkshatriya.echo.playback.source

import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import dev.brahmkshatriya.echo.common.Extension
import dev.brahmkshatriya.echo.common.MusicExtension
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Streamable.Source.Companion.toSource
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.di.App
import dev.brahmkshatriya.echo.download.Downloader
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.getExtensionOrThrow
import dev.brahmkshatriya.echo.extensions.MediaState
import dev.brahmkshatriya.echo.extensions.cache.Cached
import dev.brahmkshatriya.echo.extensions.cache.Cached.loadStreamableMedia
import dev.brahmkshatriya.echo.playback.MediaItemUtils
import dev.brahmkshatriya.echo.playback.MediaItemUtils.backgroundIndex
import dev.brahmkshatriya.echo.playback.MediaItemUtils.downloaded
import dev.brahmkshatriya.echo.playback.MediaItemUtils.extensionId
import dev.brahmkshatriya.echo.playback.MediaItemUtils.isLoaded
import dev.brahmkshatriya.echo.playback.MediaItemUtils.serverIndex
import dev.brahmkshatriya.echo.playback.MediaItemUtils.state
import dev.brahmkshatriya.echo.playback.MediaItemUtils.subtitleIndex
import dev.brahmkshatriya.echo.playback.MediaItemUtils.track
import dev.brahmkshatriya.echo.playback.exceptions.TrackUnavailableException
import dev.brahmkshatriya.echo.ui.media.MediaHeaderAdapter.Companion.playableString
import dev.brahmkshatriya.echo.utils.HealthMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File

class StreamableLoader(
    private val app: App,
    private val extensionListFlow: StateFlow<List<MusicExtension>>,
    private val downloadFlow: StateFlow<List<Downloader.Info>>,
    private val healthMonitor: HealthMonitor,
) {
    suspend fun load(mediaItem: MediaItem) = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()
        try {
            withTimeout(30_000) {
                extensionListFlow.first { it.isNotEmpty() }
                val new = if (mediaItem.isLoaded) mediaItem
                else MediaItemUtils.buildLoaded(
                    app, downloadFlow.value, mediaItem, loadTrack(mediaItem)
                )

                val server = async { loadServer(new) }
                val background =
                    async { if (new.backgroundIndex < 0) null else loadBackground(new).getOrNull() }
                val subtitle = async { if (new.subtitleIndex < 0) null else loadSubtitle(new).getOrNull() }

                MediaItemUtils.buildWithBackgroundAndSubtitle(
                    new, background.await(), subtitle.await()
                ) to server.await()
            }
        } catch (e: TimeoutCancellationException) {
            healthMonitor.report(
                HealthMonitor.ExtensionResolutionTimeout(mediaItem.extensionId, System.currentTimeMillis() - startMs),
                HealthMonitor.Scope.PERSISTENT, 60 * 60 * 1000L
            )
            throw e
        }
    }

    private suspend fun <T> withClient(
        mediaItem: MediaItem,
        block: suspend (Extension<*>) -> Result<T>
    ): Result<T> {
        val extension = extensionListFlow.getExtensionOrThrow(mediaItem.extensionId)
        return block(extension)
    }

    private suspend fun loadTrack(item: MediaItem): MediaState.Loaded<Track> {
        val stub = item.state.item
        Log.d("GladixPlayback", "loadTrack stub: id=${stub.id} servers=${stub.servers.map { it.id }} extras=${stub.extras}")
        val track = withClient(item) {
            Cached.loadMedia(app, it, item.state)
        }
        val result = track.getOrThrow()
        Log.d("GladixPlayback", "loadTrack result: id=${result.item.id} servers=${result.item.servers.map { it.id }}")
        return result
    }

    private suspend fun loadServer(mediaItem: MediaItem): Result<Streamable.Media.Server> {
        val downloaded = mediaItem.downloaded
        val servers = mediaItem.track.servers
        val index = mediaItem.serverIndex
        if (!downloaded.isNullOrEmpty() && servers.size == index) {
            return runCatching {
                Streamable.Media.Server(
                    downloaded.map { Uri.fromFile(File(it)).toString().toSource() },
                    true
                )
            }
        }
        return withClient(mediaItem) {
            runCatching {
                val isPlayable = mediaItem.track.playableString(app.context)
                if (isPlayable != null) throw TrackUnavailableException(isPlayable)
                val streamable = servers.getOrNull(index) ?: throw Exception("Server not found")
                loadStreamableMedia(
                    app, it, mediaItem.track, streamable
                ).getOrThrow() as Streamable.Media.Server
            }
        }
    }

    private suspend fun loadBackground(mediaItem: MediaItem): Result<Streamable.Media.Background> {
        val streams = mediaItem.track.backgrounds
        val index = mediaItem.backgroundIndex
        val streamable = streams[index]
        return withClient(mediaItem) {
            runCatching {
                loadStreamableMedia(
                    app, it, mediaItem.track, streamable
                ).getOrThrow() as Streamable.Media.Background
            }
        }
    }

    private suspend fun loadSubtitle(mediaItem: MediaItem): Result<Streamable.Media.Subtitle> {
        val streams = mediaItem.track.subtitles
        val index = mediaItem.subtitleIndex
        val streamable = streams[index]
        return withClient(mediaItem) {
            runCatching {
                loadStreamableMedia(
                    app, it, mediaItem.track, streamable
                ).getOrThrow() as Streamable.Media.Subtitle
            }
        }
    }
}