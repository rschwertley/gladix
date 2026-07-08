package dev.brahmkshatriya.echo.playback.listener

import android.content.SharedPreferences
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Player.REPEAT_MODE_OFF
import androidx.media3.common.Timeline
import dev.brahmkshatriya.echo.common.Extension
import dev.brahmkshatriya.echo.common.MusicExtension
import dev.brahmkshatriya.echo.common.clients.RadioClient
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Feed.Companion.pagedDataOfFirst
import dev.brahmkshatriya.echo.common.models.Radio
import dev.brahmkshatriya.echo.di.App
import dev.brahmkshatriya.echo.download.Downloader
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.get
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.getExtension
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.getIf
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.getOrThrow
import dev.brahmkshatriya.echo.extensions.MediaState
import dev.brahmkshatriya.echo.playback.MediaItemUtils
import dev.brahmkshatriya.echo.playback.MediaItemUtils.context
import dev.brahmkshatriya.echo.playback.MediaItemUtils.extensionId
import dev.brahmkshatriya.echo.playback.MediaItemUtils.track
import dev.brahmkshatriya.echo.playback.PlayerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlayerRadio(
    private val app: App,
    private val scope: CoroutineScope,
    private val player: Player,
    private val throwFlow: MutableSharedFlow<Throwable>,
    private val stateFlow: MutableStateFlow<PlayerState.Radio>,
    private val extensionList: StateFlow<List<MusicExtension>>,
    private val downloadFlow: StateFlow<List<Downloader.Info>>
) : Player.Listener {

    companion object {
        const val AUTO_START_RADIO = "auto_start_radio"
        private const val RADIO_PREFETCH_THRESHOLD = 3
        suspend fun start(
            throwableFlow: MutableSharedFlow<Throwable>,
            extension: Extension<*>,
            item: EchoMediaItem,
            itemContext: EchoMediaItem?
        ): PlayerState.Radio.Loaded? {
            if (!item.isRadioSupported) return null
            return extension.getIf<RadioClient, PlayerState.Radio.Loaded?> {
                val radio = radio(item, itemContext)
                val tracks = loadTracks(radio).pagedDataOfFirst()
                PlayerState.Radio.Loaded(extension.id, radio, null) {
                    extension.get { tracks.loadPage(it) }.getOrThrow(throwableFlow)
                }
            }.getOrThrow(throwableFlow)
        }

        suspend fun play(
            player: Player,
            downloadFlow: StateFlow<List<Downloader.Info>>,
            app: App,
            stateFlow: MutableStateFlow<PlayerState.Radio>,
            loaded: PlayerState.Radio.Loaded
        ) {
            stateFlow.value = PlayerState.Radio.Loading
            val tracks = loaded.tracks(loaded.cont) ?: return

            stateFlow.value = if (tracks.continuation == null) PlayerState.Radio.Empty
            else loaded.copy(cont = tracks.continuation)

            val item = tracks.data.map {
                MediaItemUtils.build(
                    app,
                    downloadFlow.value,
                    MediaState.Unloaded(loaded.clientId, it),
                    loaded.context
                )
            }

            withContext(Dispatchers.Main) {
                player.addMediaItems(item)
                if (player.playbackState == Player.STATE_IDLE) player.prepare()
            }
        }
    }

    private var radioQueueActive = false

    private suspend fun loadPlaylist() {
        val mediaItem = withContext(Dispatchers.Main) { player.currentMediaItem } ?: return
        val extensionId = mediaItem.extensionId
        val item = mediaItem.track
        // A LABEL_ONLY_RADIO context is a display-only header stamp (bare-track / Radio-History seed), not
        // a real radio to generate — strip it so radio() receives null exactly as before, keeping the real
        // auto-radio identical and extension-agnostic. The MediaItem's context is untouched, so the header
        // still reads "Playing from <track> Radio".
        val itemContext = mediaItem.context?.takeUnless {
            it is Radio && it.extras[MediaItemUtils.LABEL_ONLY_RADIO] == "true"
        }
        stateFlow.value = PlayerState.Radio.Loading
        val extension = extensionList.getExtension(extensionId) ?: return
        val loaded = start(throwFlow, extension, item, itemContext)
        stateFlow.value = loaded ?: PlayerState.Radio.Empty
        if (loaded != null) {
            radioQueueActive = true
            play(player, downloadFlow, app, stateFlow, loaded)
        }
    }

    private suspend fun topUpQueue() {
        if (!radioQueueActive) return
        if (stateFlow.value is PlayerState.Radio.Loading) return
        val remaining = withContext(Dispatchers.Main) {
            // Remaining upcoming tracks = full count minus the current index; drives radio prefetch.
            val fullIndex = player.currentMediaItemIndex
            player.mediaItemCount - fullIndex - 1
        }
        if (remaining > RADIO_PREFETCH_THRESHOLD) return
        when (val state = stateFlow.value) {
            is PlayerState.Radio.Loaded -> play(player, downloadFlow, app, stateFlow, state)
            is PlayerState.Radio.Empty -> loadPlaylist()
            else -> {}
        }
    }

    private var autoStartRadio = app.settings.getBoolean(AUTO_START_RADIO, true)

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { pref, key ->
        if (key != AUTO_START_RADIO) return@OnSharedPreferenceChangeListener
        autoStartRadio = pref.getBoolean(AUTO_START_RADIO, true)
    }

    init {
        app.settings.registerOnSharedPreferenceChangeListener(listener)
        scope.coroutineContext[Job]?.invokeOnCompletion {
            app.settings.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    private suspend fun startRadio() {
        if (!autoStartRadio) return
        val shouldNotStart = withContext(Dispatchers.Main) {
            player.run {
                currentMediaItem == null || repeatMode != REPEAT_MODE_OFF || hasNextMediaItem()
            }
        }
        if (shouldNotStart) return
        when (val state = stateFlow.value) {
            is PlayerState.Radio.Loading -> {}
            is PlayerState.Radio.Empty -> loadPlaylist()
            is PlayerState.Radio.Loaded -> play(player, downloadFlow, app, stateFlow, state)
        }
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        scope.launch { startRadio() }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (player.mediaItemCount == 0) {
            stateFlow.value = PlayerState.Radio.Empty
            radioQueueActive = false
        }
        scope.launch { startRadio() }
        scope.launch { topUpQueue() }
    }
}

