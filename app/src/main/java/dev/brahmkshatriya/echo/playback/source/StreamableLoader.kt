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
import dev.brahmkshatriya.echo.utils.QualityProbe
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
            // preferCache = true: the ONLY caller that passes it. See Cached.loadMedia — this keeps a
            // restored queue resolving from cache instantly, which is what makes offline playback work,
            // and which was previously happening only as a side effect of UnifiedExtension.loadTrack
            // throwing on a missing extension_id.
            Cached.loadMedia(app, it, item.state, preferCache = true)
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
                // serverIndex was chosen at build time against the stub's servers; if the freshly-loaded track
                // exposes fewer servers, that index is out of range. This is a DETERMINISTIC local mismatch —
                // re-resolving can never fix it (the generic-tail retry sets loaded=false and re-hits the
                // network each pass, which is what produced the back-to-back "Server not found" burst and can
                // feed a sub-extension rate-limit spiral). Throw the NON-retryable TrackUnavailableException so
                // onPlayerError skips this track ONCE (the "not available" skip family), with no re-resolution,
                // bounded by consecutiveUnavailableSkips — consistent with the 0-sources case just below.
                // ⚠⚠ TWO DIFFERENT FAULTS, SPLIT 2026-09-16 - THEY SHARED ONE STRING AND ONE OF
                // THEM WAS BEING MISREAD BECAUSE OF IT. The old text was "Server not found", which is
                // both WRONG for local playback (the Offline extension plays files; there is no server)
                // and jargon for everyone else. The word is gone from both branches.
                //   EMPTY         the track arrived carrying no playable sources at all. On a RESTORED
                //                 queue this is the toSlim contract being violated by that extension's
                //                 loadTrack - see the notes at HistoryEntity.toSlim and
                //                 OfflineExtension.loadTrack. Investigate THAT, not this line.
                //   OUT OF RANGE  the deterministic build-time index mismatch described above: the
                //                 freshly loaded track exposes fewer servers than the index chosen at
                //                 build time. Re-resolving can never fix it.
                // Both stay TrackUnavailableException (non-retryable) - the split is the MESSAGE and the
                // diagnosis, NOT the behaviour. Do not make either retryable without re-reading the
                // burst/rate-limit reasoning above.
                // ⚠️ [PARKED 2026-10-15] "RE-RESOLVING CAN NEVER FIX IT" ABOVE IS TRUE
                // ONLY BECAUSE THE RE-RESOLVE IS CACHE-SERVED, and nothing in that sentence says so.
                // The generic-tail retry does set loaded=false and does re-enter load() from the top -
                // but loadTrack above passes preferCache = true, so Cached.loadMedia returns the SAME
                // cached Track and the index mismatch is identical on every pass. With a FRESH resolve
                // the mismatch WOULD be fixable, because MediaItemUtils.buildLoaded recomputes
                // serverIndex from the newly loaded servers. The fix (option C) is parked, with its
                // risks and its build trigger, at MediaItemUtils.withRetry.
                // IF IT IS EVER BUILT, CORRECT THE SENTENCE ABOVE rather than leaving it to be read as
                // unconditional: the non-retryable throw is right today and would stop being right.
                val streamable = servers.getOrNull(index) ?: throw TrackUnavailableException(
                    if (servers.isEmpty()) "No playable source for this track"
                    else "Playable source no longer available"
                )
                // QUALITY-PROBE [emits p=4-resolve] RESOLVE ENTRY. The exact streamable.id handed to the extension -
                // its suffix is what becomes `quality` there - plus which extension instance resolves
                // it (Deezer direct vs Combine vs Unified), which no other line can show.
                QualityProbe.log(
                    "4-resolve", QualityProbe.nextSeq(), mediaItem.mediaId, null,
                    "serverIndex=" + index + " streamableId=" + streamable.id +
                        " suffix=" + streamable.id.substringAfterLast(':') +
                        " title=" + streamable.title + " q=" + streamable.quality +
                        " ext=" + it.id +
                        " extInstance=#" + Integer.toHexString(System.identityHashCode(it)) +
                        " serversSize=" + servers.size
                )
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