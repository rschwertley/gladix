package dev.brahmkshatriya.echo.ui.player

import android.os.Handler
import android.os.Looper
import android.util.Log
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

    // TEMP cold-start diagnostic — remove after capture.
    private val logStartMs = System.currentTimeMillis()
    private var logFirstReadyMs: Long? = null
    // EVENT gate, not a clock gate: log until the first STATE_READY (the cold-restore track resolving) + 2s,
    // so the decisive re-seek discontinuity (reason=1) is captured no matter how long the stream takes to
    // load. Before READY (at rest) it logs unbounded, so the seed/display poll lines are always visible.
    private fun shouldLog() =
        logFirstReadyMs.let { it == null || System.currentTimeMillis() - it <= 2_000L }

    private fun updateProgress(caller: String = "poll") {
        val position = player.currentPosition
        val buffered = player.bufferedPosition
        val playerDuration = player.duration
        val durationSet = playerDuration != TIME_UNSET
        val totalDuration = playerDuration.takeIf { durationSet }
        val trackDuration = viewModel.playerState.current.value?.track?.duration
        val state = player.playbackState

        // At-rest seed hold: while the seed is armed and the controller still reports 0 (queue not applied /
        // masked position not surfaced), emit the saved restore position instead of 0. Release on the first
        // real (non-zero) tick — the service re-seek at STATE_READY produces it — so play tracks normally.
        val seed = viewModel.restoreSeedMs
        val displayPosition = if (seed != null && position <= 0L) seed else position
        if (position > 0L && seed != null) viewModel.restoreSeedMs = null

        // TEMP cold-start diagnostic — remove after capture. Logs which source wins for position and duration
        // on each call, tagged by caller, until the first STATE_READY + 2s (see shouldLog). pos = raw
        // player.currentPosition; seed/display = the at-rest hold; the re-seek shows up on the controller as
        // onPositionDiscontinuity reason=1 (SEEK) to the saved position — the falsifiable test.
        val elapsed = System.currentTimeMillis() - logStartMs
        if (shouldLog()) {
            Log.d(
                "GladixProgress",
                "t=${elapsed}ms [$caller] state=$state pwr=${player.playWhenReady} " +
                    "pos(player.currentPosition)=$position seed=$seed display=$displayPosition buf=$buffered " +
                    "player.duration=$playerDuration set=$durationSet -> totalDuration=$totalDuration " +
                    "track.duration=$trackDuration"
            )
        }

        viewModel.progress.value = displayPosition to buffered
        viewModel.totalDuration.value = totalDuration

        handler.removeCallbacks(updateProgressRunnable)
        if (state != ExoPlayer.STATE_IDLE && state != ExoPlayer.STATE_ENDED) {
            var delayMs: Long
            if (player.playWhenReady && state == ExoPlayer.STATE_READY) {
                delayMs = delay - position % delay
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
                // TEMP: mark the first READY so shouldLog() keeps logging 2s past the re-seek.
                if (logFirstReadyMs == null) logFirstReadyMs = System.currentTimeMillis()
            }

            else -> Unit
        }
        updateProgress("stateChanged")
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
        // A user seek before the at-rest seed releases must win — null the seed hold. The service re-seek also
        // lands here as a SEEK, but it carries a real (non-zero) position that releases the hold in
        // updateProgress anyway, so nulling here is consistent for both.
        if (reason == Player.DISCONTINUITY_REASON_SEEK) viewModel.restoreSeedMs = null
        // TEMP diagnostic — remove after capture. reason=1 (SEEK) to ~the saved position at first STATE_READY
        // is the proof the re-seek fired; reason=6 from 0 is the pre-fix failure. Gated on the event (first
        // READY + 2s), not the clock, so a slow stream load can't push the re-seek past the window.
        val elapsed = System.currentTimeMillis() - logStartMs
        if (shouldLog()) {
            Log.d(
                "GladixProgress",
                "t=${elapsed}ms DISCONTINUITY reason=$reason old=${oldPosition.positionMs} new=${newPosition.positionMs}"
            )
        }
        updateProgress("discontinuity")
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
        updateProgress("timeline")
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