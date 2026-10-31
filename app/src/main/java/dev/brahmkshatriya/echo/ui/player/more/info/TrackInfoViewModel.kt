package dev.brahmkshatriya.echo.ui.player.more.info

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import dev.brahmkshatriya.echo.extensions.cache.Cached.loadItem
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
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
    extensionLoader,
    loadTrackSection = false,
) {
    val currentFlow = playerState.current

    /**
     * ⚠️ THE ALBUM/ARTISTS SECTION AT ITS FINAL LENGTH FROM THE FIRST FRAME (2026-10-31, step (c)). It used to be a
     * paged feed that appeared only once its loadItem calls returned, which on 1158 was AFTER the artist feed's
     * Top and Favorite tracks sections had drawn - so it inserted above them and pushed the page down.
     * The rows are the track's OWN album and artist objects (real titles and covers), emitted at once; each
     * loadItem result then replaces its row (same position, same id, so FeedAdapter.DiffCallback rebinds it in
     * place), and a failed load keeps the unloaded row - what MediaDetailsViewModel.trackFeed did with `?: it`.
     * Keyed on the track id, so the loaded state replacing the player's copy of the same track neither
     * restarts the loads nor changes the row count. Same Cached.loadItem calls as trackFeed, in the same
     * order, so no new requests.
     */
    override val fixedTrackRows: StateFlow<List<EchoMediaItem>?> = uiResultFlow
        .map { it?.getOrNull()?.item as? Track }
        .distinctUntilChanged { old, new -> old?.id == new?.id }
        .transformLatest { track ->
            if (track == null) {
                emit(null)
                return@transformLatest
            }
            val rows = (listOfNotNull(track.album) + track.artists).toMutableList<EchoMediaItem>()
            emit(rows.toList())
            val extension = extensionFlow.value ?: return@transformLatest
            rows.indices.forEach { index ->
                val loaded = loadItem(extension, rows[index]).getOrNull() ?: return@forEach
                rows[index] = loaded
                emit(rows.toList())
            }
        }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    override fun getItem(): Triple<String, EchoMediaItem, Boolean>? {
        return currentFlow.value?.let { (_, item) ->
            Triple(item.extensionId, item.track, item.isLoaded)
        }
    }

    init {
        // ⚠️ KEYED ON (extension id, track id, isLoaded), NOT ON EVERY PlayerState.Current (2026-10-30).
        // Current also carries isPlaying, which PlayerEventListener.updateCurrentFlow rewrites on every
        // play/pause and buffering->ready change, and each of those restarted this whole load from null:
        // header back to its invisible placeholder, every section blanked and refetched.
        // HISTORY (Jun 7): the tab's jumpiness was recorded then as happening ONLY when Info is opened in
        // the first few seconds after a track starts, which is when isPlaying flips; a skeleton fix was
        // declined then. Nothing relied on the reload: the like heart is not shown on this tab (Jun 6),
        // and the stale-heart fix (Oct 23, Cached.setTrackLiked) writes the cached state directly.
        // The trigger itself is upstream (0a3bce3f), with no reason recorded.
        val trackKey = playerState.current.map { current ->
            current?.mediaItem?.let { Triple(it.extensionId, it.track.id, it.isLoaded) }
        }.distinctUntilChanged()
        viewModelScope.launch(Dispatchers.IO) {
            listOf(trackKey, extensionFlow, refreshFlow).merge().collectLatest {
                val extension = extensionFlow.value
                val track = currentFlow.value?.mediaItem?.takeIf { it.isLoaded }?.track
                // The full header from the first frame, built from the track the player already holds,
                // so MediaHeaderAdapter binds its Success holder at once instead of the invisible Loading
                // line, and the loaded state below rebinds that same holder in place. The capability
                // fields are unknown here, so they are null; the details the load adds (Deezer's
                // withInfoDetails fill included) make the header grow once, downward.
                // Set BEFORE itemResultFlow is cleared, so uiResultFlow never passes through null.
                cacheResultFlow.value = if (extension != null && track != null) Result.success(
                    MediaState.Loaded(
                        extensionId = extension.id, item = track, isFollowed = null, followers = null,
                        isSaved = null, isLiked = null, isHidden = null,
                        showRadio = false, showShare = false,
                    )
                ) else null
                itemResultFlow.value = null
                extension ?: return@collectLatest
                track ?: return@collectLatest
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
