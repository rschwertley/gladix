package dev.brahmkshatriya.echo.playback.listener

import android.content.Context
import android.util.Log
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import androidx.media3.common.Player

@Suppress("DEPRECATION")
class AudioFocusListener(
    val context: Context,
    val player: Player
) : Player.Listener {

    private val handler = Handler(context.mainLooper)
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // ⚠⚠ READ BY CrossfadeController, AND IT IS THE ONLY RELIABLE DISCRIMINATOR.
    // ShufflePlayer forces handleAudioFocus=false, so every pause this class makes goes THROUGH the
    // player and surfaces to other listeners as PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST - a real
    // user pause and a focus pause are indistinguishable by reason code. This flag is set BEFORE
    // every auto-resumable pause below (AUDIOFOCUS_LOSS, AUDIOFOCUS_LOSS_TRANSIENT,
    // AUDIOFOCUS_REQUEST_DELAYED) and pause() flushes its listeners inline on this thread, so a
    // reader in that callback sees it. AUDIOFOCUS_REQUEST_FAILED deliberately does NOT set it:
    // focus was denied with no auto-resume, so a crossfade SHOULD abort there.
    // Read-only outside this class; this class is the only writer.
    internal var pausedForFocus = false
        private set

    /**
     * ⚠⚠ WHEN AND WHY THE LATCH WAS ARMED, BECAUSE AN AUTHORISATION TO RESUME
     * HAS TO EXPIRE AND WE DO NOT KNOW WHAT ARMS IT. Build 1141 auto-resumed a player the user
     * had paused the previous night: an alarm took focus at 06:15, gave it back 16 s later, and
     * [pausedForFocus] was still true from some earlier event, so AUDIOFOCUS_GAIN called play().
     * The capture could not say what armed it - no app requested focus between 22:40 and the
     * alarm, which rules out the transient-loss reading - so the window below is deliberately
     * not a fix for a known path; it is a bound on an unknown one. StaleFocusProbe reports the
     * provenance when it fires, which is how that path gets named.
     * ⚠️ elapsedRealtime, NOT currentTimeMillis: the gap being measured is hours
     * long and spans a sleeping device, so a clock or timezone change must not be able to turn a
     * 7-hour-old authorisation into a fresh one (or vice versa).
     */
    private var pausedForFocusAtMs = 0L
    private var pausedForFocusArmedBy: String? = null
    private var pausedForFocusProcessAgeMs = 0L

    private fun armPausedForFocus(by: String) {
        pausedForFocus = true
        pausedForFocusArmedBy = by
        pausedForFocusAtMs = SystemClock.elapsedRealtime()
        pausedForFocusProcessAgeMs = StaleFocusProbe.processAge()
    }

    private var loweringVolume = false

    // Mirrors whether commitPauseRunnable is actually queued. cancelCommit() reads it to skip
    // handler.removeCallbacks — the framework's full MAIN-looper MessageQueue scan (removeMessagesLegacy),
    // which ANR'd on budget devices during onDestroy — when nothing is pending (the usual state at teardown).
    // Only ever touched on the main thread (postDelayed, the runnable, and every abandon path run on the main
    // looper), so no synchronization is needed.
    private var pendingCommit = false

    // Fires after the grace window expires — abandons focus after immediate pause on AUDIOFOCUS_LOSS
    private val commitPauseRunnable = Runnable {
        pendingCommit = false   // executing now → no longer queued; clear before abandonFocus so its cancelCommit() no-ops
        abandonFocus()
    }

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                Log.d("GladixAudio", "AUDIOFOCUS_GAIN: playbackState=${player.playbackState} pausedForFocus=$pausedForFocus playWhenReady=${player.playWhenReady}")
                cancelCommit()
                if (loweringVolume) {
                    player.volume = 1f
                    loweringVolume = false
                }
                // ⚠⚠ AN AUTHORISATION TO RESUME EXPIRES, AND THIS IS THE BELT
                // FOR AN ARMING PATH WE HAVE NOT IDENTIFIED. See [pausedForFocusAtMs] for the
                // 1141 capture. The primary fix is [onTransportIntent] - a user who touches
                // transport revokes the authorisation outright - but that only helps if the
                // user touches transport, and the 1141 user's pause press was a no-op
                // (ExoPlayerImpl.updatePlayWhenReady, 1.11.0, :3074-3078, returns early when
                // playWhenReady, the suppression reason AND the change reason all match, so an
                // already-paused player fires no callback at all).
                // ⚠️ 30 MINUTES IS A USER DECISION, NOT A DERIVED NUMBER. No
                // platform guidance gives a value; the official pattern is the transport one
                // above. The window has to outlast the longest interruption a user would still
                // want resumed - a phone call - while killing an overnight resume.
                if (pausedForFocus) {
                    val ageMs = SystemClock.elapsedRealtime() - pausedForFocusAtMs
                    val armedBy = pausedForFocusArmedBy ?: "unknown"
                    val armedAt = pausedForFocusProcessAgeMs
                    pausedForFocus = false
                    pausedForFocusArmedBy = null
                    if (ageMs <= RESUME_WINDOW_MS) player.play()
                    else {
                        Log.w(
                            "GladixAudio", "stale focus resume blocked: armedBy=" + armedBy +
                                " ageMs=" + ageMs
                        )
                        StaleFocusProbe.onBlocked(armedBy, ageMs, armedAt, player.playWhenReady)
                        // Nothing of ours is going to play, so stop holding what another app
                        // could use. STATE_IDLE would abandon anyway; a paused player would not.
                        abandonFocus()
                    }
                }
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                Log.d("GladixAudio", "AUDIOFOCUS_LOSS: playWhenReady=${player.playWhenReady}")
                if (player.playWhenReady) {
                    cancelCommit()
                    armPausedForFocus(ARMED_BY_LOSS)
                    player.pause()
                    postCommit()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                cancelCommit()
                // ⚠️ THE playWhenReady GUARD IS THE REASON THIS BRANCH IS NOT
                // THE 1141 BUG, and it was checked before being left alone: at 06:15:00 the
                // player was already paused, so nothing was armed here. The latch that fired
                // was older. Do not "fix" the auto-resume by touching this guard.
                if (player.playWhenReady) {
                    armPausedForFocus(ARMED_BY_TRANSIENT)
                    player.pause()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                player.volume = DUCK_VOLUME
                loweringVolume = true
            }
        }
    }

    private val focusRequest: AudioFocusRequest? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAcceptsDelayedFocusGain(true)
            .setOnAudioFocusChangeListener(focusChangeListener, handler)
            .build()
    } else null

    internal fun requestFocus() {
        val result = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                audioManager.requestAudioFocus(focusRequest!!)
            else audioManager.requestAudioFocus(
                focusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        } catch (e: RuntimeException) {
            // The system can REJECT focus by THROWING instead of returning a code — SecurityException
            // "not allowed to perform TAKE_AUDIO_FOCUS" on some OEM / managed / restricted-background
            // contexts — which the return-value `when` below can't catch, so it was propagating out of
            // onPlayWhenReadyChanged and crashing (build 985). Treat a thrown denial EXACTLY like a
            // returned AUDIOFOCUS_REQUEST_FAILED (pause, no auto-resume) so it degrades gracefully.
            // RuntimeException (SecurityException's supertype) also covers any other binder-thrown
            // RuntimeException (DeadObject/IllegalState) from the same call.
            Log.w("GladixAudio", "requestAudioFocus threw — treating as REQUEST_FAILED", e)
            AudioManager.AUDIOFOCUS_REQUEST_FAILED
        }
        when (result) {
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> { /* normal, proceed */ }
            AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> {
                // Focus queued — pause now, AUDIOFOCUS_GAIN resumes when it arrives.
                // pausedForFocus=true prevents onPlayWhenReadyChanged from calling
                // abandonFocus(), which would cancel the queued request.
                armPausedForFocus(ARMED_BY_DELAYED)
                player.pause()
            }
            AudioManager.AUDIOFOCUS_REQUEST_FAILED -> {
                // Focus denied — pause without auto-resume.
                player.pause()
            }
        }
    }

    // Posting and cancelling the grace-window commit go through these two so pendingCommit stays in lockstep
    // with the real queue state — postDelayed/removeCallbacks for commitPauseRunnable exist nowhere else.
    private fun postCommit() {
        handler.postDelayed(commitPauseRunnable, GRACE_WINDOW_MS)
        pendingCommit = true
    }

    // Skips the removeCallbacks MAIN-queue scan when nothing is queued. When a commit IS pending it removes
    // it exactly as before, then clears the flag.
    private fun cancelCommit() {
        if (!pendingCommit) return
        handler.removeCallbacks(commitPauseRunnable)
        pendingCommit = false
    }

    private fun abandonFocus() {
        cancelCommit()
        // Same binder-throw risk as requestFocus (SecurityException / other RuntimeException from the
        // system): a throw here would crash from the many teardown paths that call this (release, STATE_IDLE,
        // becoming-noisy, the grace-window commit). Best-effort — abandoning focus has no fallback behavior.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                audioManager.abandonAudioFocusRequest(focusRequest!!)
            else audioManager.abandonAudioFocus(focusChangeListener)
        } catch (e: RuntimeException) {
            Log.w("GladixAudio", "abandonAudioFocus threw — ignoring", e)
        }
    }

    /**
     * ⚠⚠ A USER TOUCHING TRANSPORT REVOKES THE FOCUS-RESUME AUTHORISATION, AND
     * THIS IS THE PRIMARY FIX FOR THE 1141 OVERNIGHT AUTO-RESUME. [pausedForFocus] means "we
     * paused this and will resume it"; once the user presses play or pause, that is no longer
     * true whatever the player's current state. Clearing it here is unconditional and
     * synchronous, so it holds even on the one path that produces no player callback at all: a
     * pause press on an ALREADY-PAUSED player, which ExoPlayerImpl.updatePlayWhenReady
     * (1.11.0, :3074-3078) drops because playWhenReady, the suppression reason and the change
     * reason all already match. That silence is why the latch survived the night.
     *
     * ⚠⚠ THE INTENT IS READ FROM THE RESULT, ONE TASK LATER, BECAUSE IT CANNOT
     * BE READ AT THE CALL. media3 invokes PlayerCallback.onPlayerCommandRequest BEFORE running
     * the command - MediaSessionStub:353 calls the callback, then :369-372 only QUEUES the work
     * (ConnectedControllersManager.addToCommandQueue:344-353) for a later flush - and
     * COMMAND_PLAY_PAUSE is shared by play and pause, so the command carries no direction.
     * ⚠️ AND READING player.playWhenReady AT THE CALL IS NOT A SUBSTITUTE, which
     * is the trap worth naming: "playing now, so this must be a pause" is wrong for an explicit
     * play() on a playing player and wrong for a pause() on a paused one - and the paused one is
     * exactly the 1141 case. So the decision waits for the outcome.
     * ⚠️ ABANDON ON PAUSE ONLY, NEVER ON PLAY. An abandon-then-request blip
     * around a play would hand AUDIOFOCUS_GAIN to whatever sits below us in the focus stack -
     * another paused-for-focus media app - and resume IT. That is this same bug pointed the
     * other way, so the posted check refuses unless the outcome really is a paused player.
     * ⚠️ SAFE IF THE POSTED READ IS EARLY ANYWAY: the latch clear has already
     * happened, and an abandon followed by [onPlayWhenReadyChanged]'s own requestFocus() for the
     * play costs a re-request, not a behaviour change.
     */
    internal fun onTransportIntent() {
        cancelCommit()
        pausedForFocus = false
        pausedForFocusArmedBy = null
        handler.postDelayed({
            if (!player.playWhenReady && !pausedForFocus) abandonFocus()
        }, INTENT_RESOLVE_MS)
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (playWhenReady) {
            requestFocus()
            if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST && loweringVolume) {
                player.volume = 1f
                loweringVolume = false
            }
        } else if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY) {
            // BT/headphone disconnect — cancel any pending focus-driven pause and abandon focus
            // so AUDIOFOCUS_GAIN cannot re-enable playback on the now-disconnected device
            cancelCommit()
            pausedForFocus = false
            abandonFocus()
        } else if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST && !pausedForFocus) {
            // User explicitly paused (not focus-driven) — release focus so other apps can play
            cancelCommit()
            abandonFocus()
        }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_IDLE) abandonFocus()
        else if (playbackState == Player.STATE_BUFFERING && player.playWhenReady) {
            Log.d("GladixAudio", "STATE_BUFFERING+playWhenReady: re-requesting focus defensively")
            requestFocus()
        }
    }

    fun release() {
        abandonFocus()
    }

    companion object {
        private const val GRACE_WINDOW_MS = 750L
        private const val DUCK_VOLUME = 0.4f

        /** How long a focus-driven pause stays resumable. See [pausedForFocusAtMs]. */
        private const val RESUME_WINDOW_MS = 30 * 60 * 1000L

        /**
         * How long [onTransportIntent] waits before reading the command's OUTCOME. One queued
         * task on this same looper; see that function for why the intent is unreadable at the
         * call. Short enough to be invisible, long enough that the queue has drained.
         */
        private const val INTENT_RESOLVE_MS = 50L

        // Provenance strings for StaleFocusProbe. Closed set, so a report can never read as free
        // text, and named rather than inlined because the probe's keys are the whole point.
        private const val ARMED_BY_LOSS = "loss"
        private const val ARMED_BY_TRANSIENT = "transientLoss"
        private const val ARMED_BY_DELAYED = "delayedGrant"
    }
}
