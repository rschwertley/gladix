package dev.brahmkshatriya.echo.ui.player.more.info

import androidx.lifecycle.viewModelScope
import dev.brahmkshatriya.echo.common.Extension
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.di.App
import dev.brahmkshatriya.echo.download.Downloader
import dev.brahmkshatriya.echo.extensions.ExtensionLoader
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.getAs
import dev.brahmkshatriya.echo.extensions.MediaState
import dev.brahmkshatriya.echo.extensions.cache.Cached.loadMedia
import dev.brahmkshatriya.echo.playback.MediaItemUtils.extensionId
import dev.brahmkshatriya.echo.playback.MediaItemUtils.isLoaded
import dev.brahmkshatriya.echo.playback.MediaItemUtils.track
import dev.brahmkshatriya.echo.playback.PlayerState
import dev.brahmkshatriya.echo.ui.media.MediaDetailsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

class TrackInfoViewModel(
    val app: App,
    extensionLoader: ExtensionLoader,
    playerState: PlayerState,
    downloader: Downloader
) : MediaDetailsViewModel(
    downloader, app, true,
    playerState.current.combine(extensionLoader.music) { current, music ->
        current?.mediaItem?.extensionId?.let { id ->
            music.find { it.id == id }
        }
    },
    extensionLoader
) {
    val currentFlow = playerState.current
    override fun getItem(): Triple<String, EchoMediaItem, Boolean>? {
        return currentFlow.value?.let { (_, item) ->
            Triple(item.extensionId, item.track, item.isLoaded)
        }
    }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            listOf(playerState.current, extensionFlow, refreshFlow).merge().collectLatest {
                itemResultFlow.value = null
                val extension = extensionFlow.value ?: return@collectLatest
                val track = currentFlow.value?.mediaItem?.takeIf { it.isLoaded }?.track
                    ?: return@collectLatest
                itemResultFlow.value = loadMedia(
                    app, extension, MediaState.Unloaded(extension.id, track)
                ).map { withInfoDetails(extension, it) }
            }
        }
    }

    /**
     * ⚠️ DISPLAY ONLY (2026-10-30): fills a Deezer track's MISSING contributors, ISRC, release date and
     * disc/track for this tab, from DeezerTrackClient.withInfoDetails (one deezer.pageTrack per song per
     * session, remembered there). Runs AFTER loadMedia, so the media-state entry it wrote is the unfilled
     * one; the queued track, its token, playback and every cache are untouched.
     * Covers every liked path at once because it works on whatever the player is holding.
     * ⚠️ FILL-ONLY HERE TOO, so a field the track already has is never replaced whatever comes back.
     * Best-effort: getAs reports nothing, and any failure shows the track as loaded.
     * The filled item does reach this page's like/save actions; the like path's LikedEntry.of keeps none
     * of these fields, so the liked list is unchanged.
     */
    private suspend fun withInfoDetails(
        extension: Extension<*>, state: MediaState.Loaded<Track>,
    ): MediaState.Loaded<Track> {
        if (extension.id != DEEZER_ID) return state
        val track = state.item
        val details = extension.getAs<TrackClient, Track> {
            loadTrack(track.copy(extras = track.extras + (INFO_DETAILS_EXTRA to "1")), false)
        }.getOrNull()
        // getAs's runCatching also catches a cancellation; without this a superseded load could
        // write the previous track's details over the next one.
        currentCoroutineContext().ensureActive()
        details ?: return state
        val contribs = details.extras.filter { (key, value) ->
            key.startsWith("CONTRIB_") && value.isNotEmpty() && track.extras[key].isNullOrEmpty()
        }
        val filled = track.copy(
            isrc = track.isrc?.takeIf { it.isNotBlank() } ?: details.isrc,
            releaseDate = track.releaseDate ?: details.releaseDate,
            albumOrderNumber = track.albumOrderNumber ?: details.albumOrderNumber,
            albumDiscNumber = track.albumDiscNumber ?: details.albumDiscNumber,
            extras = track.extras + contribs,
        )
        if (filled == track) return state
        return MediaState.Loaded(
            extensionId = state.extensionId,
            item = filled,
            isFollowed = state.isFollowed,
            followers = state.followers,
            isSaved = state.isSaved,
            isLiked = state.isLiked,
            isHidden = state.isHidden,
            showRadio = state.showRadio,
            showShare = state.showShare,
            probesDegraded = state.probesDegraded,
        )
    }

    private companion object {
        const val DEEZER_ID = "deezer"
        // Same literal as DeezerTrackClient's INFO_DETAILS_EXTRA; there is no shared module for it.
        const val INFO_DETAILS_EXTRA = "gladix_info_details"
    }
}
