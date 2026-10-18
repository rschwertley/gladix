package dev.brahmkshatriya.echo.cast

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.cast.RemoteCastPlayer
import androidx.media3.session.MediaSession
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.common.models.Message
import dev.brahmkshatriya.echo.di.App
import dev.brahmkshatriya.echo.playback.MediaItemUtils.extensionId
import dev.brahmkshatriya.echo.playback.MediaItemUtils.serverIndex
import dev.brahmkshatriya.echo.playback.MediaItemUtils.track
import dev.brahmkshatriya.echo.playback.PlayerState
import dev.brahmkshatriya.echo.playback.listener.TrackingListener
import dev.brahmkshatriya.echo.playback.source.LocalMediaServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Owns a Cast session end to end: it decides whether one may start, swaps the session player while it
 * lasts, keeps exactly one track loaded on the receiver, and puts everything back when it ends.
 *
 * ⚠⚠ THE QUEUE NEVER LEAVES THE PHONE. Nothing is transferred to the receiver - no queue, no
 * shuffle order, no repeat mode. The receiver is handed ONE item at a time, and the local player
 * remains the only queue there is: [onCastEnded] advances it, [CastSwitchPlayer] routes next/previous
 * and every edit to it, and the notification keeps showing its metadata. That is the whole design and
 * it is what makes the swap reversible at any instant.
 *
 * ⚠⚠ MAIN THREAD ONLY, BECAUSE MediaSession.setPlayer REQUIRES IT. setPlayer asserts
 * `player.getApplicationLooper() == Looper.myLooper()` (MediaSession.java:988, 1.11.0) on top of
 * requiring the new player to share the outgoing player's looper. Cast's SessionManagerListener
 * callbacks arrive on the main thread and the ExoPlayer was built there, so both hold - but anything
 * added here that posts to another thread must post back before touching the session.
 *
 * ⚠️ WHAT HOLDS THE HEAP, given the 1032 aggregate-working-set history. CastContext and
 * RemoteCastPlayer both pin Play Services object graphs once touched. CastContext is reached through
 * CastAvailability, which is already lazy and user-triggered; the RemoteCastPlayer is built HERE on
 * session start and released on session end, and [remote] is nulled with it. Nothing in this class
 * retains a MediaItem, a Track or a bitmap - the cast item is built, handed over and dropped.
 */
@OptIn(UnstableApi::class)
class CastSessionController(
    private val app: App,
    private val scope: CoroutineScope,
    private val state: PlayerState,
    private val localServer: LocalMediaServer,
    private val localPlayer: Player,
    private val session: () -> MediaSession?,
    private val tracking: () -> TrackingListener?,
    private val isAndroidAutoConnected: () -> Boolean,
    private val abortCrossfade: () -> Unit,
) {

    private var remote: RemoteCastPlayer? = null
    private var wrapper: CastSwitchPlayer? = null

    /**
     * ⚠⚠ THE TRANSIENT-ENDED GUARD, AND IT IS NOT A WORKAROUND FOR A RACE WE COULD FIX.
     * RemoteCastPlayer reports STATE_ENDED while the receiver is between items - the previous item has
     * finished and the newly loaded one has not started - so an unguarded "ENDED means advance" would
     * skip a track on every single transition. The guard is a time window because the only thing that
     * distinguishes the two cases is how long ago we handed the receiver a new item: a real end comes
     * a track's length after the load, a transient one comes within a moment of it.
     */
    private var lastLoadAtMs = 0L

    /**
     * ⚠⚠ WHAT IS ALREADY ON THE RECEIVER, SO THE state.current OBSERVER CANNOT RESTART
     * THE CURRENT TRACK. PlayerService observes state.current and that flow emits more than once per
     * track - at minimum an UNLOADED emission and then a LOADED one, because isLoaded flips after the
     * stream resolves. Keying the observer on mediaId alone would miss the loaded emission, which is
     * the one that matters (before it, state.servers has no entry and [loadCurrentOnCast] can do
     * nothing); keying it on both and not guarding here would re-load the same track from 0 the
     * moment it finished resolving. So the observer passes every change and this decides.
     * ⚠️ SET ONLY ON A LOAD THAT ACTUALLY HAPPENED. The early returns below leave it
     * alone, which is what lets the loaded emission retry a track whose server was not resolved yet.
     */
    private var castedMediaId: String? = null

    private val remoteListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState != Player.STATE_ENDED) return
            val sinceLoad = System.currentTimeMillis() - lastLoadAtMs
            if (sinceLoad < TRANSIENT_ENDED_MS) return
            onCastTrackEnded()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // (b): one timer, one record. TrackingListener keeps its own 30 s PauseTimer and its own
            // currentFlow-driven track identity; all it is missing while casting is a truthful
            // "am I playing", because the local player it listens to is parked. Feeding its existing
            // callback is what keeps the rule single-sourced instead of reimplemented here.
            tracking()?.onIsPlayingChanged(isPlaying)
        }

        override fun onPlayerError(error: PlaybackException) {
            // The Wi-Fi-drop detector, and it needs no network callback of its own: the server is
            // bound to the Wi-Fi address, so losing Wi-Fi kills the socket, the receiver's next range
            // request fails, and the failure arrives here. Any other receiver-side failure lands in
            // the same place and wants the same handling - say so, and come home.
            message(R.string.cast_stopped)
            endSessionFromError()
        }
    }

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(castSession: CastSession, sessionId: String) =
            onCastStarted()

        override fun onSessionResumed(castSession: CastSession, wasSuspended: Boolean) =
            onCastStarted()

        override fun onSessionEnded(castSession: CastSession, error: Int) = onCastEnded()
        override fun onSessionSuspended(castSession: CastSession, reason: Int) = onCastEnded()
        override fun onSessionStarting(castSession: CastSession) = Unit
        override fun onSessionEnding(castSession: CastSession) = Unit
        override fun onSessionResuming(castSession: CastSession, sessionId: String) = Unit
        override fun onSessionStartFailed(castSession: CastSession, error: Int) = onCastEnded()
        override fun onSessionResumeFailed(castSession: CastSession, error: Int) = onCastEnded()
    }

    /**
     * ⚠️ REGISTRATION IS THE ONLY THING DONE EAGERLY, AND IT STILL COSTS NO CastContext.
     * CastAvailability.castContext returns null when Play Services is missing or stale, in which case
     * this is a no-op and no Cast code ever runs again - which is requirement 7's "no crash, no
     * button" in one branch. The listener itself allocates nothing that matters.
     */
    fun register() {
        val context = CastAvailability.castContext(app.context) ?: return
        runCatching {
            context.sessionManager.addSessionManagerListener(sessionListener, CastSession::class.java)
        }
    }

    fun unregister() {
        val context = CastAvailability.castContext(app.context) ?: return
        runCatching {
            context.sessionManager
                .removeSessionManagerListener(sessionListener, CastSession::class.java)
        }
        if (CastState.isCasting) onCastEnded()
    }

    // ── Session start ────────────────────────────────────────────────────────────────────────────

    private fun onCastStarted() {
        if (CastState.isCasting) return
        // Requirement 6, one half: AA and Cast are mutually exclusive. Refusing here rather than
        // hiding the button is deliberate - the session can be started from the TV or from another
        // phone, so the button is not the only entry point.
        if (isAndroidAutoConnected()) {
            message(R.string.cast_refused_android_auto)
            endSessionFromError()
            return
        }
        if (!localServer.start()) {
            // Wi-Fi only: no bindable Wi-Fi address means no URL the receiver could fetch.
            message(R.string.cast_needs_wifi)
            endSessionFromError()
            return
        }
        val castPlayer = runCatching { RemoteCastPlayer.Builder(app.context).build() }.getOrNull()
        if (castPlayer == null) {
            message(R.string.cast_stopped)
            endSessionFromError()
            return
        }
        remote = castPlayer
        castPlayer.addListener(remoteListener)

        // Order matters: the flag first, so the gates (ShufflePlayer.play, the crossfade arm,
        // PlayerEventListener's watchdog) are already closed before anything else moves.
        CastState.setCasting(true)
        abortCrossfade()
        runCatching { localPlayer.pause() }
        tracking()?.castSource = castPlayer

        val switch = CastSwitchPlayer(localPlayer, castPlayer)
        wrapper = switch
        val mediaSession = session()
        if (mediaSession == null) {
            // No session means no playback to move; unwind rather than leaving the flag set.
            onCastEnded()
            return
        }
        runCatching { mediaSession.player = switch }

        // Requirement 6: start from the current track and position.
        loadCurrentOnCast(localPlayer.currentPosition, play = true, force = true)
    }

    // ── One track at a time ──────────────────────────────────────────────────────────────────────

    /**
     * ⚠⚠ CALLED ON EVERY LOCAL CURRENT-ITEM CHANGE WHILE CASTING, which is what makes the
     * receiver follow the queue without the queue ever being sent. PlayerService observes
     * state.current and calls this; [onCastTrackEnded] advances the local player, the observer fires,
     * and the next item loads. The cycle has exactly one driver - the local queue - in both
     * directions.
     */
    fun loadCurrentOnCast(positionMs: Long, play: Boolean, force: Boolean = false) {
        if (!CastState.isCasting) return
        val castPlayer = remote ?: return
        val current = state.current.value?.mediaItem ?: return
        if (!force && current.mediaId == castedMediaId) return
        val serverResult = state.servers[current.track.id]
        val server = serverResult?.getOrNull()
        if (server == null) {
            // The local loader has not resolved this track yet. Not an error: its resolution will
            // publish a current change and bring us straight back here.
            return
        }
        val index = current.serverIndex.takeIf { it >= 0 } ?: 0
        val streamable = current.track.servers.getOrNull(index)
        if (streamable == null) {
            message(R.string.cast_stopped)
            return
        }
        val item = CastMediaItemBuilder.build(
            track = current.track,
            extensionId = current.extensionId,
            // Single-source servers are the norm; the resolver indexes sources within the server, and
            // 0 is the only index the local chain uses for them.
            sourceIndex = 0,
            streamable = streamable,
            urlFor = { key -> localServer.urlFor(key) },
        )
        if (item == null) {
            message(R.string.cast_needs_wifi)
            return
        }
        lastLoadAtMs = System.currentTimeMillis()
        castedMediaId = current.mediaId
        runCatching {
            castPlayer.setMediaItem(item, positionMs.coerceAtLeast(0L))
            castPlayer.prepare()
            castPlayer.playWhenReady = play
        }
    }

    private fun onCastTrackEnded() {
        // Advance the LOCAL queue; its current-item change loads the next item on the receiver. Uses
        // the session player so ShufflePlayer's own advance bookkeeping runs exactly as it would for
        // a local auto-advance - the queue must not diverge just because the audio was elsewhere.
        runCatching {
            if (localPlayer.hasNextMediaItem()) {
                localPlayer.seekToNextMediaItem()
            } else {
                // End of queue: stop the receiver rather than leaving it on a finished item.
                // Braces because an assignment is not an expression in Kotlin and cannot be an
                // `else` branch.
                remote?.playWhenReady = false
            }
        }
    }

    // ── Session end ──────────────────────────────────────────────────────────────────────────────

    /**
     * ⚠⚠ RETURNS TO LOCAL AT THE CAST POSITION, PAUSED - requirement 6, and the pause is the
     * part that needs stating. Resuming on the phone the instant a TV session ends would start audio
     * out of a device the user has just stopped using, often in a different room. So the position
     * follows and the play state does not.
     *
     * ⚠️ IDEMPOTENT, BECAUSE IT IS REACHED FROM FIVE PLACES: session ended, suspended, start
     * failed, resume failed, and our own error path - plus unregister() at service teardown. The
     * `isCasting` early return is what makes a double call free.
     */
    private fun onCastEnded() {
        if (!CastState.isCasting && remote == null) return
        val castPlayer = remote
        val position = castPlayer?.let {
            runCatching { it.currentPosition }.getOrDefault(C.TIME_UNSET)
        } ?: C.TIME_UNSET

        // Flag down first: every gate reopens before the local player is handed anything to do.
        CastState.setCasting(false)
        tracking()?.castSource = null
        castedMediaId = null

        val mediaSession = session()
        runCatching { mediaSession?.player = localPlayer }
        wrapper?.detach()
        wrapper = null

        runCatching { castPlayer?.removeListener(remoteListener) }
        runCatching { castPlayer?.release() }
        remote = null

        if (position != C.TIME_UNSET && position > 0L) {
            runCatching { localPlayer.seekTo(position) }
        }
        // Explicitly NOT play(): see the note above. ShufflePlayer's refusal is already lifted by the
        // flag, so this is a choice rather than a consequence.
        runCatching { localPlayer.pause() }

        localServer.stop()
    }

    /**
     * The other half of requirement 6's mutual exclusion. AA cannot be refused - the system decides
     * when projection connects - so the Cast session is what gives way, with a message, which is
     * also the right answer physically: the user has just got into a car.
     */
    fun onAndroidAutoConnected() {
        if (!CastState.isCasting) return
        message(R.string.cast_stopped_android_auto)
        endSessionFromError()
    }

    /** Ends the Cast session itself, for the refusal paths. onSessionEnded then unwinds the rest. */
    private fun endSessionFromError() {
        val context = CastAvailability.castContext(app.context)
        runCatching { context?.sessionManager?.endCurrentSession(true) }
        // endCurrentSession may not call back if no session was ever fully established; unwind
        // whatever we did set up so the flag can never stay stuck on.
        onCastEnded()
    }

    private fun message(resId: Int) {
        scope.launch { app.messageFlow.emit(Message(app.context.getString(resId))) }
    }

    companion object {
        /**
         * Measured against nothing yet - it is the value the design was agreed with, and the two
         * cases it separates are a track's length apart, so the window is not delicate. If a real
         * end is ever missed because a track was shorter than this, that is the number to look at.
         */
        private const val TRANSIENT_ENDED_MS = 3_000L
    }
}
