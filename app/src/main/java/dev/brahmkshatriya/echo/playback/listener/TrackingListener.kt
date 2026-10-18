package dev.brahmkshatriya.echo.playback.listener

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import dev.brahmkshatriya.echo.common.Extension
import dev.brahmkshatriya.echo.common.clients.TrackerClient
import dev.brahmkshatriya.echo.common.clients.TrackerMarkClient
import dev.brahmkshatriya.echo.common.models.TrackDetails
import dev.brahmkshatriya.echo.extensions.ExtensionLoader
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.getExtension
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.runIf
import dev.brahmkshatriya.echo.history.HistoryRepository
import dev.brahmkshatriya.echo.playback.MediaItemUtils.context
import dev.brahmkshatriya.echo.playback.MediaItemUtils.extensionId
import dev.brahmkshatriya.echo.playback.MediaItemUtils.track
import dev.brahmkshatriya.echo.playback.PlayerState
import dev.brahmkshatriya.echo.utils.PauseTimer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@UnstableApi
class TrackingListener(
    private val player: Player,
    private val scope: CoroutineScope,
    extensions: ExtensionLoader,
    private val currentFlow: MutableStateFlow<PlayerState.Current?>,
    private val throwableFlow: MutableSharedFlow<Throwable>,
    private val historyRepository: HistoryRepository,
) : Player.Listener {

    private val musicList = extensions.music
    private val trackerList = extensions.tracker

    private var current: MediaItem? = null
    private var previousId: String? = null

    /**
     * ⚠⚠ THE PLAYER TO ASK "AM I PLAYING, AND WHERE" - THE LOCAL ONE UNLESS A CAST
     * SESSION IS LIVE. Null when not casting, and then every read below is the local player exactly
     * as before; CastSessionController sets it to the RemoteCastPlayer on session start and back to
     * null on session end.
     *
     * ⚠⚠ WHY THE INPUTS MOVE AND NOT THE LISTENER. This class is registered on the
     * LOCAL player and stays there, because its track IDENTITY comes from `currentFlow` - the local
     * queue - which is still correct while casting: nothing is transferred, the local queue still
     * advances, so onTrackChanged already fires for the right track with no cast code at all. The
     * only things that go stale are position/duration and isPlaying, because the local player is
     * parked. Feeding those two is strictly smaller than a second listener and, decisively, it keeps
     * ONE 30 s PauseTimer and ONE history record per track: two independent timers would have to
     * agree about who had already recorded a track across a mid-track swap, which is exactly the
     * double-count this avoids by construction.
     *
     * ⚠️ THE SWAP ITSELF PRODUCES NO TRACK CHANGE, in either direction, which is what
     * makes a mid-track swap safe: `currentFlow` does not emit, so the timer is neither reset nor
     * restarted and simply keeps accumulating against the same track.
     */
    @Volatile
    var castSource: Player? = null

    private val source: Player get() = castSource ?: player

    private suspend fun getDetails(): TrackDetails? {
        val item = current ?: return null
        val (pos, total) = withContext(Dispatchers.Main) {
            val from = source
            from.currentPosition to from.duration.takeIf { it != C.TIME_UNSET }
        }
        return TrackDetails(item.extensionId, item.track, item.context, pos, total)
    }

    private fun trackMedia(
        block: suspend TrackerClient.(extension: Extension<*>, details: TrackDetails?) -> Unit
    ) {
        scope.launch {
            val details = getDetails()
            val prevExtension = previousId?.takeIf { details?.extensionId != it }
                ?.let { musicList.getExtension(it) }
            val extension = musicList.getExtension(details?.extensionId)
            val trackers = trackerList.value.filter { it.isEnabled }
            prevExtension?.runIf<TrackerClient>(throwableFlow) { block(prevExtension, null) }
            extension?.runIf<TrackerClient>(throwableFlow) { block(extension, details) }
            trackers.forEach {
                launch { it.runIf<TrackerClient>(throwableFlow) { block(it, details) } }
            }
        }
    }

    private val mutex = Mutex()
    private val timers = mutableMapOf<String, PauseTimer>()
    private var historyTimer: PauseTimer? = null
    private fun onTrackChanged(mediaItem: MediaItem?) {
        previousId = current?.extensionId
        current = mediaItem
        scope.launch {
            mutex.withLock {
                timers.forEach { (_, timer) -> timer.pause() }
                timers.clear()
                historyTimer?.pause()
                historyTimer = null
            }
            trackMedia { extension, details ->
                onTrackChanged(details)
                details ?: return@trackMedia
                val duration = (this as? TrackerMarkClient)?.getMarkAsPlayedDuration(details)
                    ?: return@trackMedia
                mutex.withLock {
                    timers[extension.id] = PauseTimer(scope, duration) {
                        scope.launch {
                            extension.runIf<TrackerMarkClient>(throwableFlow) {
                                onMarkAsPlayed(details)
                            }
                        }
                    }
                }
            }
            if (mediaItem != null) {
                val capturedItem = mediaItem
                mutex.withLock {
                    historyTimer = PauseTimer(scope, 30_000L) {
                        scope.launch {
                            runCatching {
                                historyRepository.recordTrack(
                                    capturedItem.extensionId,
                                    capturedItem.track,
                                    capturedItem.context
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        scope.launch {
            playState.value = getDetails() to isPlaying
        }
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int
    ) {
        scope.launch {
            val isPlaying = withContext(Dispatchers.Main) { source.isPlaying }
            playState.value = getDetails() to isPlaying
        }
        if (reason == 2 || current == null) return
        val mediaItem = newPosition.mediaItem ?: return
        if (oldPosition.mediaItem != mediaItem) return
        if (newPosition.positionMs != 0L) return

        onTrackChanged(current)
    }

    override fun onPlayerError(error: PlaybackException) {
        onTrackChanged(null)
    }

    private val playState = MutableStateFlow<Pair<TrackDetails?, Boolean>>(null to false)

    init {
        scope.launch {
            currentFlow.map { it?.let { curr -> curr.mediaItem.takeIf { curr.isLoaded } } }
                .distinctUntilChanged().collectLatest {
                    onTrackChanged(it)
                }
        }
        scope.launch {
            @OptIn(FlowPreview::class)
            playState.debounce(500).collectLatest { (_, isPlaying) ->
                mutex.withLock {
                    timers.forEach { (_, timer) ->
                        if (isPlaying) timer.resume()
                        else timer.pause()
                    }
                    if (isPlaying) historyTimer?.resume() else historyTimer?.pause()
                }
                trackMedia { _, details ->
                    onPlayingStateChanged(details, isPlaying)
                }
            }
        }
    }
}