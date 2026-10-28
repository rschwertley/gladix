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
import kotlin.math.PI
import kotlin.math.sin

@Suppress("DEPRECATION")
class AudioFocusListener(
    val context: Context,
    val player: Player,
    // ⚠⚠ A NETWORK HOLD KEEPS FOCUS, AS A TRANSIENT INTERRUPTION WOULD (PlayerEventListener's
    // network hold; wired by PlayerService). Its pause arrives here as USER_REQUEST with
    // pausedForFocus false and an error hold also drives STATE_IDLE - both abandon focus below, so
    // both stand aside while held. A PERMANENT loss while held cancels the resume
    // (onFocusLostWhileHeld); a transient one keeps it and defers it to AUDIOFOCUS_GAIN - see
    // [resumeAfterNetworkHold]. The hold never sets pausedForFocus except in that deferred case, so a
    // hold pause is never read as a focus pause, and the 1141 latch rules (transport clears it,
    // RESUME_WINDOW_MS bounds it) apply unchanged.
    private val isNetworkHeld: () -> Boolean = { false },
    private val onFocusLostWhileHeld: () -> Unit = {},
) : Player.Listener {

    // A transient loss arrived while held and no AUDIOFOCUS_GAIN has followed it yet.
    private var interruptedWhileHeld = false

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

    /**
     * ⚠⚠ WE RESUME BEFORE THE SYSTEM'S OUTPUT HAS SETTLED, AND THIS MAKES
     * THAT INAUDIBLE RATHER THAN TRYING TO WAIT IT OUT. Measured from dumpsys audio on a
     * Pixel 10 (build 1144, two alarms, identical shape): the alarm's player is released, our
     * AudioTrack starts 5-11 ms later on this listener's AUDIOFOCUS_GAIN, and 200-270 ms after
     * that the system MUTES our track ("muted updated source:streamVolume") while it flips the
     * shared output back off the interrupter's route - unmuting 260-390 ms later again. A plain
     * pause -> play shows neither the mute nor the flip, which is what identifies the route
     * switch rather than anything of ours. So the first ~0.6 s of a focus resume is cut.
     * With a 1 s sin² ramp the gain is about -32 dB at the first step, -20 dB where the
     * mute lands and -3 dB around the unmute edge: the system cuts near-silence, and the
     * audio is most of the way up by the time it hands the route back.
     *
     * ⚠⚠ player.volume, NOT AudioEffectsProcessor's FADE - AND THE PROCESSOR
     * FADE WOULD ARRIVE TOO LATE TO HELP. A pause does not flush the sink, so the already
     * processed, full-level PCM queued in the AudioTrack is what plays FIRST on resume - which
     * is exactly the window before the mute. startFadeIn only attenuates frames processed
     * after the call, so it could not reach that audio; a player volume write is a per-track
     * gain at the sink and attenuates what is already queued. Two further reasons it had to
     * be this side: the processor's rampTotalFrames/fadeIn/fadeOut registers belong to the
     * crossfade and a focus resume mid-overlap would stomp a live hand-off ramp, and they are
     * per-processor, so the tail (its own instance) would have kept playing at full level.
     *
     * ⚠️ sin², NOT sin: THIS IS ONE LEG, NOT A CROSSOVER. Equal power
     * (sin/cos, unsquared) is what two summed legs need - see the note at
     * AudioEffectsProcessor.OVERLAP_PEDESTAL. A single leg fading up from silence wants the
     * squared curve, which is also what the retired Track Fade used.
     * ⚠️ 10 STEPS OF 100 ms, NOT 20 OF 50: every write reaches every
     * Player.Listener AND every connected MediaController (Android Auto, the notification) as
     * an onVolumeChanged, so the step count is traffic. USER DECISION 2026-10-23: halve it.
     * ⚠️ EVERY FOCUS-DRIVEN RESUME GETS IT, which is all three things
     * [armPausedForFocus] can be armed by, so there is deliberately no label test here:
     * AUDIOFOCUS_LOSS and AUDIOFOCUS_LOSS_TRANSIENT are the alarm/ringtone/call-pause shapes
     * measured above, and AUDIOFOCUS_REQUEST_DELAYED is NOT a track start - per the May 24
     * record it is set when we request focus DURING AN ACTIVE CALL, so its resume lands on the
     * call ending and crosses a call-mode and route change, i.e. the worst case of this same
     * glitch. What is excluded is excluded structurally, not by a test: a user play never sets
     * the latch (and [onTransportIntent] clears it), and a duck restore never pauses, so it
     * never reaches the play() below.
     */
    private var fadeStepsDone = 0
    private var pendingFade = false

    private val fadeRunnable = object : Runnable {
        override fun run() {
            fadeStepsDone++
            if (fadeStepsDone >= FOCUS_FADE_STEPS) {
                // Written as exactly 1f rather than the curve's endpoint, so float error can
                // never leave the player a hair under unity for the rest of the session.
                pendingFade = false
                player.volume = 1f
                return
            }
            val p = fadeStepsDone.toFloat() / FOCUS_FADE_STEPS
            val s = sin(p * (PI / 2).toFloat())
            player.volume = s * s
            handler.postDelayed(this, FOCUS_FADE_STEP_MS)
        }
    }

    /**
     * Silences the player and starts the ramp. MUST be called BEFORE play(): the point is to
     * attenuate the PCM the sink already holds, and that plays from the first instant.
     *
     * ⚠️ SELF-COMPLETING, SO A play() THAT NEVER STARTS CANNOT STRAND THE
     * PLAYER AT 0f. The ramp is driven by [handler] and reads no player state, so it reaches
     * its last step - an exact 1f - whatever happened to the play request. The case that
     * raises the question is real: ShufflePlayer.play REFUSES while a Cast session is live and
     * returns without touching playWhenReady, so no callback follows and nothing else would
     * restore the level. It ends at 1f 1 s later regardless, on a local leg that is silent
     * during a Cast session anyway. Called only from the branch that actually calls play()
     * (inside the RESUME_WINDOW_MS test); the stale-blocked branch never touches volume.
     *
     * ⚠️ [CORRECTED 2026-10-23] THIS CLASS IS NOT THE ONLY WRITER OF THE
     * MAIN PLAYER'S VOLUME, whatever the note at ShufflePlayer.play says. PlayerFragment's
     * playerSheetOffset observer writes `browser.value?.volume = 1 + min(0f, offset)` - a
     * MediaController write, which reaches this same player - so a sheet drag during the ramp
     * overwrites a step with 1f and ends the fade early. It fires only while that fragment is
     * STARTED and only when the offset CHANGES, so a background focus resume (the measured
     * case) cannot hit it; the worst outcome is the glitch returning for one resume, never a
     * stranded level, since the competing write is itself 1f.
     */
    private fun startFocusFadeIn() {
        cancelFocusFade()
        fadeStepsDone = 0
        pendingFade = true
        player.volume = 0f
        handler.postDelayed(fadeRunnable, FOCUS_FADE_STEP_MS)
    }

    /**
     * ⚠️ A CANCEL ALWAYS LEAVES A SANE LEVEL, NEVER A PARTIAL ONE: 1f, or
     * DUCK_VOLUME while a duck is in force. Anything else would strand the player quiet with
     * no event left to fix it - the failure the dual-manager note warns about at
     * ShufflePlayer.play. Cheap when idle: the [pendingFade] guard skips both the
     * removeCallbacks main-queue scan and the volume write, as [cancelCommit] does.
     */
    private fun cancelFocusFade() {
        if (!pendingFade) return
        handler.removeCallbacks(fadeRunnable)
        pendingFade = false
        player.volume = if (loweringVolume) DUCK_VOLUME else 1f
    }

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
                interruptedWhileHeld = false
                if (loweringVolume) {
                    cancelFocusFade()
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
                    if (ageMs <= RESUME_WINDOW_MS) {
                        // Before play(), and on this same thread - see [pendingFade].
                        startFocusFadeIn()
                        player.play()
                    }
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
                    cancelFocusFade()
                    armPausedForFocus(ARMED_BY_LOSS)
                    player.pause()
                    postCommit()
                } else if (isNetworkHeld()) {
                    // Another app took over audio for good: the held song is no longer ours to resume.
                    onFocusLostWhileHeld()
                    abandonFocus()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                cancelCommit()
                // ⚠️ THE playWhenReady GUARD IS THE REASON THIS BRANCH IS NOT
                // THE 1141 BUG, and it was checked before being left alone: at 06:15:00 the
                // player was already paused, so nothing was armed here. The latch that fired
                // was older. Do not "fix" the auto-resume by touching this guard.
                if (player.playWhenReady) {
                    cancelFocusFade()
                    armPausedForFocus(ARMED_BY_TRANSIENT)
                    player.pause()
                } else if (isNetworkHeld()) interruptedWhileHeld = true
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // First: the duck is the newer decision, and a surviving ramp step would
                // overwrite DUCK_VOLUME 100 ms later.
                cancelFocusFade()
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
        // The user has taken over; a ramp we started for a focus resume is no longer ours to
        // finish, and leaving it running would walk the volume up under a user pause.
        cancelFocusFade()
        pausedForFocus = false
        pausedForFocusArmedBy = null
        handler.postDelayed({
            if (!player.playWhenReady && !pausedForFocus) abandonFocus()
        }, INTENT_RESOLVE_MS)
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        // ⚠️ UNCONDITIONAL ON THE PAUSE SIDE, AND IT HAS TO BE: our own
        // focus pauses arrive here as USER_REQUEST with pausedForFocus set, which falls past
        // every reason test below. Cancelling on the state rather than the reason is the only
        // form that covers them.
        if (!playWhenReady) cancelFocusFade()
        if (playWhenReady) {
            requestFocus()
            if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST && loweringVolume) {
                cancelFocusFade()
                player.volume = 1f
                loweringVolume = false
            }
        } else if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY) {
            // BT/headphone disconnect — cancel any pending focus-driven pause and abandon focus
            // so AUDIOFOCUS_GAIN cannot re-enable playback on the now-disconnected device
            cancelCommit()
            pausedForFocus = false
            abandonFocus()
        } else if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST && !pausedForFocus &&
            !isNetworkHeld()
        ) {
            // User explicitly paused (not focus-driven) — release focus so other apps can play
            cancelCommit()
            abandonFocus()
        }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_IDLE) {
            if (!isNetworkHeld()) abandonFocus()
        } else if (playbackState == Player.STATE_BUFFERING && player.playWhenReady) {
            Log.d("GladixAudio", "STATE_BUFFERING+playWhenReady: re-requesting focus defensively")
            requestFocus()
        }
    }

    /**
     * The network hold's resume asks here first. True = play now. False = a transient loss (a call,
     * an assistant) is still outstanding, so the song is reloaded paused and the existing focus resume
     * plays it on AUDIOFOCUS_GAIN - with its fade and inside RESUME_WINDOW_MS - armed as
     * [ARMED_BY_NETWORK_HOLD] so StaleFocusProbe can name it.
     */
    internal fun resumeAfterNetworkHold(): Boolean {
        if (!interruptedWhileHeld) return true
        interruptedWhileHeld = false
        armPausedForFocus(ARMED_BY_NETWORK_HOLD)
        return false
    }

    /** The hold ended without a resume (cap, song change): release focus if nothing will play. */
    internal fun onNetworkHoldEnded() {
        interruptedWhileHeld = false
        if (!player.playWhenReady && !pausedForFocus) abandonFocus()
    }

    fun release() {
        cancelFocusFade()
        abandonFocus()
    }

    companion object {
        private const val GRACE_WINDOW_MS = 750L
        private const val DUCK_VOLUME = 0.4f

        /** 10 x 100 ms = a 1 s fade-in on a focus resume. See [fadeRunnable]. */
        private const val FOCUS_FADE_STEPS = 10
        private const val FOCUS_FADE_STEP_MS = 100L

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
        private const val ARMED_BY_NETWORK_HOLD = "networkHold"
    }
}
