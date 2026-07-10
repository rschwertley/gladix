package dev.brahmkshatriya.echo.ui.player

import android.os.Handler
import android.os.Looper
import androidx.media3.common.C.TIME_UNSET
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer

class PlayerUiListener(
    private val player: Player,
    private val viewModel: PlayerViewModel
) : Player.Listener {

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
    }

    private fun updateList() {
        updateNavigation()
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
        // Cold-start position lands here. The MediaController connects and snapshots PlayerInfo BEFORE the
        // awaited restore continuation runs (applyRestoreIfCold's setMediaItems is gated behind the restore
        // Deferred's disk read), so the snapshot carries position 0. The real position arrives milliseconds
        // later as a PlayerInfo delta whose only signal is this onTimelineChanged. Nothing else re-reads it:
        // the delta doesn't change playbackState (the controller's stays IDLE — ShufflePlayer's faked READY
        // is getter-only and never delivered as a callback), so onPlaybackStateChanged never fires, and the
        // progress poll already halted on IDLE at init. This line is what picks the position up.
        // The connect-time 0 is written by the init updateProgress() and corrected here, so the collapsed
        // mini-bar's progress line may show 0 for the sub-second disk-read window on cold start — known,
        // cosmetic, not worth gating.
        updateProgress()
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