package dev.brahmkshatriya.echo.ui.player

import android.os.Handler
import android.os.Looper
import androidx.media3.common.C.TIME_UNSET
import androidx.media3.common.DeviceInfo
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
            // STAMPED. Both halves are read in ONE expression, in one main-thread turn with no suspension
            // between them, so the id and the tracks describe the same instant. Reading them separately is
            // the bug this pair exists to prevent: at a transition the two reads can straddle the item
            // change and stamp the new id onto the old track's formats.
            tracksFlow.value = player.currentMediaItem?.mediaId to player.currentTracks
            isPlaying.value = player.isPlaying
            playWhenReady.value = player.playWhenReady
            buffering.value = player.playbackState == Player.STATE_BUFFERING
            shuffleMode.value = player.shuffleModeEnabled
            repeatMode.value = player.repeatMode
        }
        updateDeviceVolume()
    }

    /**
     * System media volume for the quality sheet's output row. BOTH callbacks route here because
     * they change different halves of the same reading: onDeviceVolumeChanged moves the value
     * (including from a hardware key - media3 owns that broadcast), onDeviceInfoChanged moves the
     * RANGE, which happens when the output route changes. Reading one without the other leaves a
     * value outside its own min..max, which is what makes a Slider throw.
     */
    override fun onDeviceVolumeChanged(volume: Int, muted: Boolean) = updateDeviceVolume()

    override fun onDeviceInfoChanged(deviceInfo: DeviceInfo) = updateDeviceVolume()

    // runCatching around the reads, not around the write: a MediaController whose commands are
    // not yet negotiated logs and returns defaults rather than throwing, but deviceInfo is read
    // through the session and a disconnect mid-read is the one case that can. Falling back to the
    // default DeviceVolume() reports available=false, which every consumer already gates on.
    private fun updateDeviceVolume() {
        viewModel.deviceVolume.value = runCatching {
            val info = player.deviceInfo
            PlayerViewModel.DeviceVolume(
                volume = player.deviceVolume,
                min = info.minVolume,
                max = info.maxVolume,
                muted = player.isDeviceMuted,
            )
        }.getOrDefault(PlayerViewModel.DeviceVolume())
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
                // FIRST-TRACK RE-SEED. onTracksChanged for track 1 can fire BEFORE this listener is
                // attached: it is added in getController's callback (PlayerViewModel:155), i.e. on
                // controller connect, which on a cold start with a resumed queue happens after the first
                // item has already reported its tracks. The init seed above then captures a freshly
                // connected controller's empty Tracks, and nothing re-fires until the NEXT item change -
                // which is exactly why the pill read "Unknown quality" for the whole first track and was
                // correct from the second one on.
                // STATE_READY fires per track, so this also covers a reconnect after process death.
                // CHEAP IN FACT, not just in principle: MediaControllerImplBase.getCurrentTracks() (:2143)
                // is `return playerInfo.currentTracks` - a field read of the controller's locally cached
                // PlayerInfo, no binder transaction. And it is idempotent: tracksFlow is a StateFlow and
                // Tracks has value equality, so re-seeding the same pair on later tracks emits nothing.
                // Same one-expression read as the init seed, for the same reason.
                viewModel.tracksFlow.value =
                    player.currentMediaItem?.mediaId to player.currentTracks
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
        // ⚠️ A CROSSFADE HAND-OFF ALSO LANDS HERE AS A SEEK (2026-10-15). The overlap
        // advances the queue with seekToNextMediaItem, so this nulls the seed hold once per crossfaded
        // transition. Audited and left alone: by the time a track is within one fade length of its end the
        // at-rest seed has long been released, and the branch is idempotent. If a cold-start restore
        // ever appears to lose its seed on a crossfaded first track, this line is where to look.
        if (reason == Player.DISCONTINUITY_REASON_SEEK) viewModel.restoreSeedMs = null
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
        // Stamped with the item these tracks belong to. media3 delivers this with the player already at
        // the new item, so currentMediaItem is the right owner, and both reads are in one turn.
        viewModel.tracksFlow.value = player.currentMediaItem?.mediaId to tracks
    }
}