package dev.brahmkshatriya.echo.cast

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

/**
 * The session player while, and only while, a Cast session is live. Transport and device volume go to
 * the cast leg; the queue stays on the local leg.
 *
 * ⚠⚠ ITS LIFETIME IS THE CAST SESSION'S, AND THAT IS WHY THERE IS NO "NOT CASTING" BRANCH IN
 * HERE. CastSessionController installs it with MediaSession.setPlayer on session start and puts the
 * local player back on session end, so [remote] is non-null for the whole life of the object. The
 * alternative - one permanent wrapper with an `if (casting)` on every member - would put this class
 * in the path of ordinary playback for every user, which is exactly the blast radius the swap exists
 * to avoid. Do not "simplify" it into a permanent wrapper.
 *
 * ⚠⚠ WHY THE SWAP IS SAFE FOR CONTROLLERS, read from media3 1.11.0 rather than assumed.
 * MediaSessionImpl.setPlayerInternal (:290-312) does NOT diff against the old state: it detaches its
 * listener from the old player, attaches a fresh one, then rebuilds from scratch with
 * `playerInfo = newPlayerWrapper.createInitialPlayerInfo()` (:310) and re-publishes the command set
 * (:311). So there is no event-flag fidelity for this class to get wrong - the session re-reads
 * everything through the getters below. For AA and the notification, MediaSessionLegacyStub
 * .onPlayerChanged (:1423-1472) re-pushes timeline, playlist metadata, media metadata, shuffle,
 * repeat, device info, flags and the playback state, in that order.
 * ⚠️ THE DEVICE-INFO LINE IS THE ONE THAT MAKES THE TV VOLUME WORK: ":1453 Forcefully update
 * device info to update VolumeProviderCompat attached to the old player". RemoteCastPlayer reports
 * PLAYBACK_TYPE_REMOTE, and MediaSessionLegacyStub.createVolumeProviderCompat only builds a provider
 * for a non-LOCAL playback type - so the car's and the notification's volume rocker retarget to the
 * receiver at the swap, and back to STREAM_MUSIC at the swap home. Neither needs code from us.
 *
 * ⚠⚠ THE SPLIT, AND THE RULE FOR DECIDING WHICH SIDE A NEW MEMBER GOES ON: anything that is a
 * property of THE THING BEING HEARD goes remote (is it playing, where is it, how long is it, how loud
 * is the device, did it fail); anything that is a property of THE QUEUE goes local (what is in it,
 * which index, shuffle, repeat, next/previous, edits). The local leg is still the only queue there is
 * - nothing is transferred to the receiver, by design - so `getCurrentMediaItem` and the metadata
 * getters are deliberately NOT overridden: the notification must keep showing our canonical track,
 * not the proxy item we hand the receiver.
 *
 * ⚠️ seekTo(positionMs) IS REMOTE BUT seekTo(index, positionMs) IS NOT. The first is "move
 * within what is playing"; the second changes track, which is a queue operation that must advance the
 * local player and let CastSessionController load the new item on the receiver. Splitting them is the
 * whole reason both overrides exist rather than one.
 */
@OptIn(UnstableApi::class)
class CastSwitchPlayer(
    private val local: Player,
    private val remote: Player,
) : ForwardingPlayer(local) {

    /**
     * ⚠⚠ LISTENERS MUST BE ADDED TO BOTH LEGS, AND super.addListener COVERS ONLY ONE.
     * ForwardingPlayer.addListener (1.11.0, :85-94) wraps the listener in a private ForwardingListener
     * that re-targets callbacks to `this` and registers it on the DELEGATE - so a listener added here
     * hears the local player and nothing else. While casting, the events that matter (is it playing,
     * did it end, did it fail) only happen on the remote leg, so each listener also gets a [RemoteRelay]
     * on the cast player.
     * ⚠️ THE RELAY DOES NOT NEED PERFECT EVENT FIDELITY, and that is worth knowing before
     * anyone tries to make it exhaustive. media3's session reacts to a callback by RE-READING the
     * player's getters (see the class note on createInitialPlayerInfo), so the relay's job is only to
     * say "something changed, look again" with `this` as the player. Reporting the wrong FIELD in an
     * event set costs a redundant read; reporting NOTHING is what would freeze a controller.
     */
    private val relays = HashMap<Player.Listener, RemoteRelay>()

    override fun addListener(listener: Player.Listener) {
        super.addListener(listener)
        val relay = synchronized(relays) { relays.getOrPut(listener) { RemoteRelay(listener) } }
        remote.addListener(relay)
    }

    override fun removeListener(listener: Player.Listener) {
        super.removeListener(listener)
        val relay = synchronized(relays) { relays.remove(listener) }
        if (relay != null) remote.removeListener(relay)
    }

    private inner class RemoteRelay(private val target: Player.Listener) : Player.Listener {
        // `this@CastSwitchPlayer` in every callback that carries a Player: a listener that was handed
        // the raw cast player would read position and state straight off it, bypassing the split above.
        override fun onEvents(player: Player, events: Player.Events) =
            target.onEvents(this@CastSwitchPlayer, events)

        override fun onIsPlayingChanged(isPlaying: Boolean) = target.onIsPlayingChanged(isPlaying)

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) =
            target.onPlayWhenReadyChanged(playWhenReady, reason)

        override fun onPlaybackStateChanged(playbackState: Int) =
            target.onPlaybackStateChanged(playbackState)

        override fun onPlayerError(error: PlaybackException) = target.onPlayerError(error)

        override fun onPlayerErrorChanged(error: PlaybackException?) =
            target.onPlayerErrorChanged(error)

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int,
        ) = target.onPositionDiscontinuity(oldPosition, newPosition, reason)

        override fun onDeviceInfoChanged(deviceInfo: DeviceInfo) =
            target.onDeviceInfoChanged(deviceInfo)

        override fun onDeviceVolumeChanged(volume: Int, muted: Boolean) =
            target.onDeviceVolumeChanged(volume, muted)

        override fun onIsLoadingChanged(isLoading: Boolean) = target.onIsLoadingChanged(isLoading)
    }

    // ── What is being heard: the cast leg ────────────────────────────────────────────────────────

    override fun play() = remote.play()
    override fun pause() = remote.pause()
    override fun setPlayWhenReady(playWhenReady: Boolean) {
        remote.playWhenReady = playWhenReady
    }

    override fun getPlayWhenReady() = remote.playWhenReady
    override fun prepare() = remote.prepare()
    override fun stop() = remote.stop()
    override fun seekTo(positionMs: Long) = remote.seekTo(positionMs)

    override fun getPlaybackState() = remote.playbackState
    override fun getPlaybackSuppressionReason() = remote.playbackSuppressionReason
    override fun isPlaying() = remote.isPlaying
    override fun isLoading() = remote.isLoading
    override fun getPlayerError(): PlaybackException? = remote.playerError

    override fun getCurrentPosition() = remote.currentPosition
    override fun getContentPosition() = remote.contentPosition
    override fun getBufferedPosition() = remote.bufferedPosition
    override fun getBufferedPercentage() = remote.bufferedPercentage
    override fun getTotalBufferedDuration() = remote.totalBufferedDuration

    /**
     * ⚠️ FALLS BACK TO THE LOCAL DURATION WHILE THE RECEIVER IS STILL LOADING. RemoteCastPlayer
     * reports TIME_UNSET until the receiver has the item's metadata, and a TIME_UNSET duration makes
     * every progress bar - ours, the notification's and the car's - render as indeterminate for the
     * first second or two of every track. The local leg already knows the real duration from the
     * extension, and it is the same recording, so the fallback is correct rather than cosmetic.
     */
    override fun getDuration(): Long {
        val remoteDuration = remote.duration
        return if (remoteDuration != C.TIME_UNSET) remoteDuration else local.duration
    }

    override fun getContentDuration(): Long {
        val remoteDuration = remote.contentDuration
        return if (remoteDuration != C.TIME_UNSET) remoteDuration else local.contentDuration
    }

    // ── How loud the device is: the cast leg, i.e. the receiver ──────────────────────────────────

    override fun getDeviceInfo(): DeviceInfo = remote.deviceInfo
    override fun getDeviceVolume() = remote.deviceVolume
    override fun isDeviceMuted() = remote.isDeviceMuted

    override fun setDeviceVolume(volume: Int, flags: Int) = remote.setDeviceVolume(volume, flags)
    override fun increaseDeviceVolume(flags: Int) = remote.increaseDeviceVolume(flags)
    override fun decreaseDeviceVolume(flags: Int) = remote.decreaseDeviceVolume(flags)
    override fun setDeviceMuted(muted: Boolean, flags: Int) = remote.setDeviceMuted(muted, flags)

    // The no-flag forms are what the legacy VolumeProviderCompat path calls, so they cannot be left to
    // ForwardingPlayer or the car's volume rocker would move the PHONE while the TV plays.
    @Deprecated("Deprecated in Player", ReplaceWith("setDeviceVolume(volume, 0)"))
    override fun setDeviceVolume(volume: Int) = remote.setDeviceVolume(volume, 0)

    @Deprecated("Deprecated in Player", ReplaceWith("increaseDeviceVolume(0)"))
    override fun increaseDeviceVolume() = remote.increaseDeviceVolume(0)

    @Deprecated("Deprecated in Player", ReplaceWith("decreaseDeviceVolume(0)"))
    override fun decreaseDeviceVolume() = remote.decreaseDeviceVolume(0)

    @Deprecated("Deprecated in Player", ReplaceWith("setDeviceMuted(muted, 0)"))
    override fun setDeviceMuted(muted: Boolean) = remote.setDeviceMuted(muted, 0)

    // ── Commands: the union, because the wrapper really can do both halves ───────────────────────

    /**
     * ⚠⚠ A UNION, NOT THE REMOTE'S SET. Controllers gate their UI on this: handing them the cast
     * player's commands alone would grey out next/previous and the queue, which still work because the
     * queue never left the phone. Handing them the local set alone would hide the device-volume
     * commands that only the remote has, and the TV volume would stop being reachable.
     */
    override fun getAvailableCommands(): Player.Commands = Player.Commands.Builder()
        .addAll(local.availableCommands)
        .addAll(remote.availableCommands)
        .build()

    override fun isCommandAvailable(command: Int) = availableCommands.contains(command)

    /**
     * Drops the relays off the cast leg. Called by CastSessionController AFTER MediaSession.setPlayer
     * has put the local player back, so nothing is listening through this object by then.
     *
     * ⚠️ DOES NOT RELEASE EITHER LEG. The local player outlives every cast session, and the cast
     * player is the controller's to release - it is the thing holding the Play Services object graph
     * the 1032 heap work cares about, and releasing it here would double-release it.
     */
    fun detach() {
        val snapshot = synchronized(relays) { relays.values.toList().also { relays.clear() } }
        snapshot.forEach { runCatching { remote.removeListener(it) } }
    }
}
