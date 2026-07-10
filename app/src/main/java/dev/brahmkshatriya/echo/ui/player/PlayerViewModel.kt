package dev.brahmkshatriya.echo.ui.player

import android.content.SharedPreferences
import android.util.Log
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.ThumbRating
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.session.MediaController
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.common.clients.LikeClient
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Message
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.di.App
import dev.brahmkshatriya.echo.download.Downloader
import dev.brahmkshatriya.echo.extensions.ExtensionLoader
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.getExtension
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.isClient
import dev.brahmkshatriya.echo.extensions.MediaState
import dev.brahmkshatriya.echo.playback.MediaItemUtils
import dev.brahmkshatriya.echo.playback.MediaItemUtils.serverWithDownloads
import dev.brahmkshatriya.echo.playback.MediaItemUtils.sourceIndex
import dev.brahmkshatriya.echo.playback.MediaItemUtils.track
import dev.brahmkshatriya.echo.playback.PlayerCommands.addToNextCommand
import dev.brahmkshatriya.echo.playback.PlayerCommands.addToQueueCommand
import dev.brahmkshatriya.echo.playback.PlayerCommands.backfillCommand
import dev.brahmkshatriya.echo.playback.PlayerCommands.playCommand
import dev.brahmkshatriya.echo.playback.PlayerCommands.radioCommand
import dev.brahmkshatriya.echo.playback.PlayerCommands.seekToFullCommand
import dev.brahmkshatriya.echo.playback.PlayerCommands.sleepTimer
import dev.brahmkshatriya.echo.playback.PlayerService.Companion.getController
import dev.brahmkshatriya.echo.playback.PlayerState
import dev.brahmkshatriya.echo.utils.ContextUtils.listenFuture
import dev.brahmkshatriya.echo.utils.Serializer.putSerialized
import kotlinx.coroutines.Dispatchers
import dev.brahmkshatriya.echo.history.HistoryRepository
import dev.brahmkshatriya.echo.history.db.HistoryEntity
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

@OptIn(UnstableApi::class)
class PlayerViewModel(
    val app: App,
    val playerState: PlayerState,
    val settings: SharedPreferences,
    val cache: SimpleCache,
    val extensions: ExtensionLoader,
    val historyRepository: HistoryRepository,
    downloader: Downloader,
    fullQueueFlow: MutableStateFlow<List<MediaItem>>,
) : ViewModel() {
    private val downloadFlow = downloader.flow

    val browser = MutableStateFlow<MediaController?>(null)

    var queue: List<MediaItem> = emptyList()
    val queueFlow = MutableSharedFlow<Unit>()

    init {
        viewModelScope.launch {
            historyRepository.getLatest().collect { entity ->
                if (browser.value == null && entity != null) {
                    val track = entity.track ?: return@collect
                    val mediaItem = MediaItemUtils.build(
                        app,
                        downloadFlow.value,
                        MediaState.Unloaded(entity.extensionId, track),
                        null
                    )
                    if (playerState.current.value == null) {
                        playerState.current.value = PlayerState.Current(
                            index = 0,
                            mediaItem = mediaItem,
                            isLoaded = false,
                            isPlaying = false,
                            isPlaceholder = true
                        )
                        queue = listOf(mediaItem)
                        queueFlow.emit(Unit)
                    }
                }
                if (browser.value != null) return@collect
            }
        }
        viewModelScope.launch {
            fullQueueFlow.collect { items ->
                val showingPlaceholder = playerState.current.value?.isPlaceholder == true && items.isEmpty()
                if (items.isNotEmpty() || !showingPlaceholder) {
                    queue = items
                    queueFlow.emit(Unit)
                }
            }
        }
    }

    private fun withBrowser(block: suspend (MediaController) -> Unit) {
        viewModelScope.launch {
            val browser = browser.first { it != null }!!
            block(browser)
        }
    }

    private val context = app.context
    val controllerFutureRelease = getController(context) { player ->
        browser.value = player
        player.addListener(PlayerUiListener(player, this))
        // No cold-start resume() here: PlayerService.onCreate is the sole app-open restorer. Sending
        // resumeCommand on connect raced onCreate's in-flight recoverPlaylist — both compareAndSet
        // userQueueSet, but onCreate only AFTER its slow disk read, so resume() could claim first and run
        // a second restore (two recoverPlaylist calls), or claim-then-bail on activeLoadCount leaving
        // nobody to restore (the bar-flash). KEEP_QUEUE is now honored inside onCreate. (resumeCommand is
        // still used by the widget/notification resume actions, which are explicit user intents.)
    }

    override fun onCleared() {
        super.onCleared()
        controllerFutureRelease()
    }

    // `position` is a FULL-queue index (the tap indexes fullQueueFlow). Seek by full index via a
    // custom command handled service-side by ShufflePlayer.seekToFullIndex, which seeks the real
    // player directly — bypassing the windowed controller seekTo() whose range check crashed on
    // taps outside the serialized window. Do NOT reconstruct a windowed index here (that desync-prone
    // math was the crash source); the service owns the window.
    fun play(position: Int) {
        withBrowser {
            it.sendCustomCommand(seekToFullCommand, Bundle().apply {
                putInt("index", position)
                putBoolean("play", true)
            })
        }
    }

    fun seek(position: Int) {
        withBrowser {
            it.sendCustomCommand(seekToFullCommand, Bundle().apply {
                putInt("index", position)
                putBoolean("play", false)
            })
        }
    }

    fun removeQueueItem(position: Int) {
        withBrowser { it.removeMediaItem(position) }
    }

    fun moveQueueItems(fromPos: Int, toPos: Int) {
        withBrowser { it.moveMediaItem(fromPos, toPos) }
    }

    fun seekTo(pos: Long) {
        withBrowser { it.seekTo(pos) }
    }

    fun seekToAdd(position: Int) {
        withBrowser { it.seekTo(max(0, it.currentPosition + position)) }
    }

    fun setPlaying(isPlaying: Boolean) {
        withBrowser {
            Log.d("GladixAudio", "setPlaying: isPlaying=$isPlaying playbackState=${it.playbackState} playWhenReady=${it.playWhenReady}")
            it.playWhenReady = isPlaying
        }
    }

    fun next() {
        withBrowser { it.seekToNextMediaItem() }
    }

    fun previous() {
        withBrowser { it.seekToPrevious() }
    }

    fun setShuffle(isShuffled: Boolean, changeCurrent: Boolean = false) {
        withBrowser {
            it.shuffleModeEnabled = isShuffled
            if (changeCurrent) it.seekTo(0, 0)
        }
    }

    fun setRepeat(repeatMode: Int) {
        withBrowser { it.repeatMode = repeatMode }
    }

    suspend fun isLikeClient(extensionId: String): Boolean = withContext(Dispatchers.IO) {
        extensions.music.getExtension(extensionId)?.isClient<LikeClient>() ?: false
    }

    private fun createException(throwable: Throwable) {
        viewModelScope.launch { app.throwFlow.emit(throwable) }
    }

    fun likeCurrent(isLiked: Boolean) = withBrowser { controller ->
        val future = controller.setRating(ThumbRating(isLiked))
        app.context.listenFuture(future) { sessionResult ->
            sessionResult.getOrElse { createException(it) }
        }
    }

    fun setSleepTimer(timer: Long) {
        withBrowser { it.sendCustomCommand(sleepTimer, Bundle().apply { putLong("ms", timer) }) }
    }

    fun changeTrackSelection(trackGroup: TrackGroup, index: Int) {
        withBrowser {
            it.trackSelectionParameters = it.trackSelectionParameters
                .buildUpon()
                .clearOverride(trackGroup)
                .addOverride(TrackSelectionOverride(trackGroup, index))
                .build()
        }
    }

    private fun changeCurrent(newItem: MediaItem) {
        withBrowser { player ->
            // player is the MediaController: its currentMediaItemIndex is windowed, but the session
            // applies replaceMediaItem to the full inner timeline. Resolve the current item's FULL
            // index by mediaId so we replace the actual current track, not the wrong one, in queues > 50.
            val fullIndex =
                queue.indexOfFirst { it.mediaId == playerState.current.value?.mediaItem?.mediaId }
            if (fullIndex < 0) return@withBrowser
            val oldPosition = player.currentPosition
            player.replaceMediaItem(fullIndex, newItem)
            player.prepare()
            player.seekTo(oldPosition)
        }
    }

    fun changeServer(server: Streamable) {
        val item = playerState.current.value?.mediaItem ?: return
        val index = item.serverWithDownloads(app.context).indexOf(server).takeIf { it != -1 }
            ?: return
        changeCurrent(MediaItemUtils.buildServer(item, index))
    }

    fun changeBackground(background: Streamable?) {
        val item = playerState.current.value?.mediaItem ?: return
        val index = item.track.backgrounds.indexOf(background)
        changeCurrent(MediaItemUtils.buildBackground(item, index))
    }

    fun changeSubtitle(subtitle: Streamable?) {
        val item = playerState.current.value?.mediaItem ?: return
        val index = item.track.subtitles.indexOf(subtitle)
        changeCurrent(MediaItemUtils.buildSubtitle(item, index))
    }

    fun changeCurrentSource(index: Int) {
        val item = playerState.current.value?.mediaItem ?: return
        changeCurrent(MediaItemUtils.buildSource(item, index))
    }

    fun setQueue(id: String, list: List<Track>, index: Int, context: EchoMediaItem?) {
        withBrowser { controller ->
            if (list.isEmpty()) return@withBrowser
            // P2 — current+upcoming: start at the tapped track (index 0) and drop the tracks before it,
            // so it lands at index 0 with nothing stranded above and a zero persisted index — matching
            // playItem and freshContextUpcoming. `index` locates the tapped track within `list`.
            val start = index.coerceIn(0, list.size - 1)
            val upcoming = list.subList(start, list.size)
            val mediaItems = upcoming.map {
                MediaItemUtils.build(
                    app,
                    downloadFlow.value,
                    MediaState.Unloaded(id, it),
                    context
                )
            }
            controller.setMediaItems(mediaItems, 0, upcoming.first().playedDuration ?: 0)
            controller.prepare()
        }
    }

    fun backfillQueue(
        extensionId: String, item: EchoMediaItem, loaded: Boolean, startTrackId: String,
    ) = viewModelScope.launch {
        withBrowser {
            it.sendCustomCommand(backfillCommand, Bundle().apply {
                putString("extId", extensionId)
                putSerialized("item", item)
                putBoolean("loaded", loaded)
                putString("startTrackId", startTrackId)
            })
        }
    }

    fun radio(id: String, item: EchoMediaItem, loaded: Boolean) = viewModelScope.launch {
        app.messageFlow.emit(
            Message(app.context.getString(R.string.loading_radio_for_x, item.title))
        )
        withBrowser {
            it.sendCustomCommand(radioCommand, Bundle().apply {
                putString("extId", id)
                putSerialized("item", item)
                putBoolean("loaded", loaded)
            })
        }
    }

    fun play(id: String, item: EchoMediaItem, loaded: Boolean, startTrackId: String? = null) = viewModelScope.launch {
        if (item !is Track) app.messageFlow.emit(
            Message(app.context.getString(R.string.playing_x, item.title))
        )
        withBrowser {
            it.sendCustomCommand(playCommand, Bundle().apply {
                putString("extId", id)
                putSerialized("item", item)
                putBoolean("loaded", loaded)
                putBoolean("shuffle", false)
                if (startTrackId != null) putString("startTrackId", startTrackId)
            })
        }
    }

    fun shuffle(id: String, item: EchoMediaItem, loaded: Boolean) = viewModelScope.launch {
        if (item !is Track) app.messageFlow.emit(
            Message(app.context.getString(R.string.shuffling_x, item.title))
        )
        withBrowser {
            it.sendCustomCommand(playCommand, Bundle().apply {
                putString("extId", id)
                putSerialized("item", item)
                putBoolean("loaded", loaded)
                putBoolean("shuffle", true)
            })
        }
    }


    fun addToQueue(id: String, item: EchoMediaItem, loaded: Boolean) = viewModelScope.launch {
        if (item !is Track) app.messageFlow.emit(
            Message(app.context.getString(R.string.adding_x_to_queue, item.title))
        )
        withBrowser {
            it.sendCustomCommand(addToQueueCommand, Bundle().apply {
                putString("extId", id)
                putSerialized("item", item)
                putBoolean("loaded", loaded)
            })
        }
    }

    fun addToNext(id: String, item: EchoMediaItem, loaded: Boolean) = viewModelScope.launch {
        if (!(browser.value?.mediaItemCount == 0 && item is Track)) app.messageFlow.emit(
            Message(app.context.getString(R.string.adding_x_to_next, item.title))
        )
        withBrowser {
            it.sendCustomCommand(addToNextCommand, Bundle().apply {
                putString("extId", id)
                putSerialized("item", item)
                putBoolean("loaded", loaded)
            })
        }
    }

    val progress = MutableStateFlow(0L to 0L)
    val discontinuity = MutableStateFlow(0L)
    val totalDuration = MutableStateFlow<Long?>(null)

    val buffering = MutableStateFlow(false)
    val isPlaying = MutableStateFlow(false)
    val playWhenReady = MutableStateFlow(false)
    val nextEnabled = MutableStateFlow(false)
    val previousEnabled = MutableStateFlow(false)
    val repeatMode = MutableStateFlow(0)
    val shuffleMode = MutableStateFlow(false)

    val tracksFlow = MutableStateFlow<Tracks?>(null)
    val serverAndTracks = tracksFlow.combine(playerState.serverChanged) { tracks, _ -> tracks }
        .combine(playerState.current) { tracks, current ->
            val server = playerState.servers[current?.mediaItem?.mediaId]?.getOrNull()
            val index = current?.mediaItem?.sourceIndex
            Triple(tracks, server, index)
        }.stateIn(viewModelScope, SharingStarted.Lazily, Triple(null, null, null))

    companion object {
        const val KEEP_QUEUE = "keep_queue"
    }
}