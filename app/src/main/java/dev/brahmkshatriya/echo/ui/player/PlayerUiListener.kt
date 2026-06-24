package dev.brahmkshatriya.echo.ui.player

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C.TIME_UNSET
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class PlayerUiListener(
    private val player: Player,
    private val viewModel: PlayerViewModel
) : Player.Listener {

    private var pendingQueueUpdate: Job? = null

    // MediaController reconnection (e.g. after rotation) can deliver the session's timeline
    // progressively, firing onTimelineChanged multiple times with a still-settling
    // mediaItemCount. Tracking the count across calls lets us skip applying a queue update
    // until two consecutive calls agree, avoiding queue[current.index] briefly pointing at
    // the wrong track while the timeline is mid-sync.
    private var lastSeenMediaItemCount: Int? = null

    init {
        updateList()
        with(viewModel) {
            tracksFlow.value = player.currentTracks
            isPlaying.value = player.isPlaying
            playWhenReady.value = player.playWhenReady
            buffering.value = player.playbackState == Player.STATE_BUFFERING
            shuffleMode.value = player.shuffleModeEnabled
            repeatMode.value = player.repeatMode
        }
        updateNavigation()
    }

    private fun updateList() = viewModel.run {
        updateNavigation()
        val mediaItemCount = player.mediaItemCount
        val previousCount = lastSeenMediaItemCount
        lastSeenMediaItemCount = mediaItemCount
        if (previousCount != null && previousCount != 0 && mediaItemCount != 0 &&
            previousCount != mediaItemCount
        ) return@run

        val newQueue = (0 until mediaItemCount).map { player.getMediaItemAt(it) }
        val showingPlaceholder = playerState.current.value?.isPlaceholder == true && mediaItemCount == 0

        if (mediaItemCount > 0 || !showingPlaceholder) {
            queue = newQueue
            pendingQueueUpdate?.cancel()
            pendingQueueUpdate = viewModelScope.launch {
                delay(50)
                queueFlow.emit(Unit)
            }
        }
    }

    private fun updateNavigation() {
        viewModel.nextEnabled.value = player.hasNextMediaItem()
        viewModel.previousEnabled.value = player.currentMediaItemIndex >= 0
    }

    private val delay = 500L
    private val threshold = 0.2f
    private val updateProgressRunnable = Runnable { updateProgress() }
    private val handler = Handler(Looper.getMainLooper()).also {
        it.post(updateProgressRunnable)
    }

    private fun updateProgress() {
        viewModel.progress.value =
            player.currentPosition to player.bufferedPosition
        viewModel.totalDuration.value = player.duration.takeIf { it != TIME_UNSET }

        handler.removeCallbacks(updateProgressRunnable)
        val playbackState = player.playbackState
        if (playbackState != ExoPlayer.STATE_IDLE && playbackState != ExoPlayer.STATE_ENDED) {
            var delayMs: Long
            if (player.playWhenReady && playbackState == ExoPlayer.STATE_READY) {
                delayMs = delay - player.currentPosition % delay
                if (delayMs < delay * threshold) {
                    delayMs += delay
                }
            } else {
                delayMs = delay
            }
            handler.postDelayed(updateProgressRunnable, delayMs)
        }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        when (playbackState) {
            Player.STATE_BUFFERING ->
                viewModel.buffering.value = true

            Player.STATE_READY -> {
                viewModel.buffering.value = false
            }

            else -> Unit
        }
        updateProgress()
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        viewModel.isPlaying.value = isPlaying
        viewModel.playWhenReady.value = player.playWhenReady
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        viewModel.playWhenReady.value = playWhenReady
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int
    ) {
        updateNavigation()
        updateProgress()
        viewModel.discontinuity.value = newPosition.positionMs
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        updateList()
    }

    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
        viewModel.shuffleMode.value = player.shuffleModeEnabled
        updateList()
    }

    override fun onRepeatModeChanged(repeatMode: Int) {
        viewModel.repeatMode.value = player.repeatMode
        updateList()
    }

    override fun onPlayerError(error: PlaybackException) {
        viewModel.isPlaying.value = false
        viewModel.buffering.value = false
    }

    override fun onTracksChanged(tracks: Tracks) {
        viewModel.tracksFlow.value = tracks
    }
}