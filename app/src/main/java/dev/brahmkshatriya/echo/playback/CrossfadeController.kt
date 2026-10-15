package dev.brahmkshatriya.echo.playback

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.playback.MediaItemUtils.context
import dev.brahmkshatriya.echo.playback.MediaItemUtils.track
import dev.brahmkshatriya.echo.playback.renderer.AudioEffectsProcessor
import dev.brahmkshatriya.echo.playback.source.CacheOnlyTailSource
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * True crossfade, BUILD 2: the overlap.
 *
 * ⚠⚠ ONE 200 ms TICK DRIVES EVERYTHING, AND IT REPLACED TWO PlayerMessages FOR A MEASURED
 * REASON. Build 1 scheduled ARM and the gate as position-based PlayerMessages. Two captures showed
 * the same failure: a message posted AT a media-item transition was delivered only at the NEXT
 * item's prepareSourceInternal (+20 s and +26 s on 2026-10-11; +26 s and +5 s on 2026-10-15), while
 * a message posted after a SOURCE_UPDATE was on time. media3's own code says an already-passed
 * position is SKIPPED rather than delivered late (ExoPlayerImplInternal.maybeTriggerPendingMessages,
 * 1.11.0, :2325-2332 skip vs :2336-2340 deliver), so the delivery we observed should not have been
 * possible and the mechanism was never identified. Re-posting after every timeline change (the
 * sibling pattern in EffectsListener.scheduleFadeOut, which has never been late) bounded it but
 * could not fix the case where the duration was already known at the transition. A tick removes the
 * dependency entirely: nothing is scheduled, every decision is re-made from the current position
 * 5 times a second, and the duration is re-read on every one of them.
 *
 * ⚠⚠ INERT AT SLIDER 0 IS A HARD RULE, ENFORCED IN THREE PLACES, NOT ONE:
 *   1. PlayerService only CONSTRUCTS this class (and the sidecar) when the slider is > 0, and
 *      releases both when it returns to 0. With the slider at 0 the object does not exist.
 *   2. Every hook in PlayerEventListener is `crossfade?.…` against a null reference.
 *   3. [startTick] refuses to post while `fadeSeconds == 0`, so a stale instance mid-teardown stops
 *      ticking even if a hook reaches it.
 *
 * ⚠⚠ THE RE-SYNC IS MANDATORY, NOT DEFENSIVE. The sidecar is pre-seeked as soon as the tail
 * becomes readable - the moment the main player reports the current item buffered to its end - which
 * is an unpredictable number of seconds before the hand-off. It then sits paused while the main
 * player keeps advancing, so by ACTIVE the two are offset by however long that wait was. Without the
 * re-sync the overlap would play the wrong part of the track, which is a worse artefact than no
 * crossfade at all.
 *
 * ⚠️ NATURAL END ONLY. ARM is reached only from the tick, and a media-item transition always
 * aborts (except our own hand-off, see [handoff]). That is what makes a manual Next/Previous
 * incapable of crossfading. Do not "simplify" this by arming in onTransition.
 *
 * ⚠️ THIRD-PARTY TRACKER THRESHOLDS ARE NOT UNDER OUR CONTROL. The hand-off ends the departing
 * track's play timers up to `fade` seconds early. Both surfaces we own are safe by a wide margin -
 * Gladix History is a 30 s dwell timer (TrackingListener's historyTimer) and Deezer's
 * getMarkAsPlayedDuration returns a flat 30 000 ms - and the shortest track that can crossfade at
 * fade=10 is >15 s, so both have long since fired. A tracker extension that returned something close
 * to the track duration would lose its mark on crossfaded tracks; that is its threshold, not ours.
 *
 * ⚠️ SKIP-SILENCE IS MIRRORED BUT CANNOT BE SYNCHRONISED. SilenceSkippingAudioProcessor sits
 * AFTER ours in the chain (RenderersFactory's DefaultAudioProcessorChain), so the frames our ramp
 * counts are pre-skip. Mirroring makes both legs skip by the same RULE, not by the same AMOUNT -
 * they are different audio - so with skip-silence on the two legs can drift during the overlap and
 * the single re-sync at the hand-off will not correct it. Deliberately left alone: a mid-overlap
 * re-seek would be more audible than the drift.
 */
@OptIn(UnstableApi::class)
class CrossfadeController(
    private val exoPlayer: ExoPlayer,
    private val sessionPlayer: Player,
    private val sidecar: ExoPlayer,
    private val tail: CacheOnlyTailSource,
    private val mainProcessor: AudioEffectsProcessor,
    private val sidecarProcessor: AudioEffectsProcessor,
    private val isRestoring: () -> Boolean,
    private val skipFadeOnAlbums: () -> Boolean,
    private val isPausedForFocus: () -> Boolean,
    private val isAndroidAutoConnected: () -> Boolean,
) {

    /** Slider value in seconds; 0 = off. Written on the app looper from PlayerService's prefs listener. */
    var fadeSeconds: Int = 0
        set(value) {
            field = value
            // ⚠⚠ EVERY ENTRY THAT CAN RUN MID-WINDOW RESETS TO IDLE FIRST. Moving the slider
            // inside an arm window used to leave the controller armed with a sidecar prepared against
            // the OLD tail start and nothing left to correct it until the next transition - the track
            // silently lost its crossfade, and lost it on the exact track the user was adjusting
            // during. [onTransition] and [onSeek] abort first for the same reason; the tick then
            // re-arms from scratch at the new length, immediately if the position is already inside
            // the window. Two labels, not one: SETTING_OFF should be followed by nothing at all,
            // SETTING_CHANGE by an immediate re-arm.
            if (value == 0) abort(Abort.SETTING_OFF) else abort(Abort.SETTING_CHANGE)
            startTick()
        }

    private enum class Phase { IDLE, ARMED, ACTIVE }

    /** Closed set, so an abort line can never read as free text. */
    private enum class Abort {
        SETTING_OFF, SETTING_CHANGE, TRACK_CHANGE, SEEK, USER_PAUSE, INTERRUPTION, STALL_HOLD,
        ERROR, QUEUE_EDIT,
        // NOT_READY: the gate point arrived with the tail or the sidecar not ready.
        // TAIL_DIED: the sidecar went IDLE or raised an error mid-overlap.
        // RAMP_TIMEOUT: the main player never rendered a post-hand-off frame within RAMP_TIMEOUT_MS,
        //   so its mute has to be lifted by force.
        // MAIN_RECOVERY: the main player was stopped, re-prepared or seeked under us (buffering
        //   watchdog, error retry) while ACTIVE.
        // ANDROID_AUTO: AA connected mid-crossfade; see [onAndroidAutoConnected].
        NOT_READY, TAIL_DIED, RAMP_TIMEOUT, MAIN_RECOVERY, ANDROID_AUTO,
        // The one non-abort member, so the success path prints a line too: a capture with `ramp` and
        // nothing after it would be undecidable between "it worked" and "the tick stopped".
        COMPLETED,
    }

    private var phase = Phase.IDLE
    private var armedMediaId: String? = null
    private var armedTailStartMs = 0L
    private var sidecarPrepared = false

    /** Media ms of overlap decided at ARM and re-derived each tick; the ramp length follows from it. */
    private var effectiveFadeMs = 0L
    private var handoffAtMs = 0L
    private var rampStartedAtMs = 0L
    private var rampMs = 0L

    /** Fresh per crossfade; the main processor republishes it from the audio thread. */
    private var overlapToken: Any? = null

    // ⚠⚠ THE HAND-OFF IS A SEEK, AND TWO OF OUR OWN HOOKS ABORT ON SEEKS. ShufflePlayer's
    // seekToNextMediaItem delivers DISCONTINUITY_REASON_SEEK (reaching PlayerEventListener's
    // `crossfade()?.onSeek()`) and a media-item transition (reaching `crossfade()?.onTransition()`),
    // so without this latch the controller tears down the overlap at the instant it starts. The
    // codebase already solves this twice for the same reason - internalSeekInFlight and
    // involuntarySkipInFlight in PlayerEventListener, both documented as "a user Next and our own
    // seekToNextMediaItem both surface as reason=SEEK, so only this marker distinguishes them". A
    // dedicated flag rather than reusing either, for the reason recorded at involuntarySkipInFlight.
    // Single-threaded on the app looper: seekToNextMediaItem delivers its callbacks synchronously
    // inline (ListenerSet.flushEvents inside seekTo), so the bracket reliably covers them.
    private var handoffInFlight = false
    private inline fun handoff(block: () -> Unit) {
        handoffInFlight = true
        try { block() } finally { handoffInFlight = false }
    }

    // ⚠⚠ THE TICK CONDITION IS `ACTIVE || (fadeSeconds > 0 && isPlaying)`, AND THE FIRST TERM
    // IS LOAD-BEARING. Between the hand-off seek and the main player's first frame the main is
    // BUFFERING (~1-2 s), so isPlaying is false - which is exactly the window where RAMP_TIMEOUT, the
    // TAIL_DIED watch and the volume mirror all have to keep running, and where the main processor is
    // MUTED. An isPlaying-only tick would stop there and leave permanent silence.
    private val handler = Handler(Looper.getMainLooper())
    private var tickPosted = false
    private val tick = object : Runnable {
        override fun run() {
            tickPosted = false
            onTick()
            startTick()
        }
    }

    private fun startTick() {
        if (tickPosted) return
        if (phase != Phase.ACTIVE && (fadeSeconds == 0 || !exoPlayer.isPlaying)) return
        tickPosted = true
        handler.postDelayed(tick, TICK_MS)
    }

    private fun stopTick() {
        handler.removeCallbacks(tick)
        tickPosted = false
    }

    // ── Hooks. All called on the app looper from PlayerEventListener. ────────────────────────────

    fun onTransition() {
        if (handoffInFlight) return   // our own hand-off; the overlap owns this transition
        abort(Abort.TRACK_CHANGE)
        startTick()
    }

    fun onSeek() {
        if (handoffInFlight) return
        abort(if (phase == Phase.ACTIVE) Abort.MAIN_RECOVERY else Abort.SEEK)
        startTick()
    }

    /**
     * ⚠⚠ A FOCUS PAUSE ARRIVES HERE, NOT AT [onInterruptionPause], AND THE REASON IS ON
     * RECORD. ShufflePlayer forces handleAudioFocus=false, so media3 never raises AUDIO_FOCUS_LOSS
     * itself; AudioFocusListener pauses THROUGH the player, which surfaces as
     * PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST. The reason code therefore cannot tell a user pause
     * from a duck or a transient loss - PlayerEventListener's own note says so and says to take the
     * signal from AudioFocusListener instead, which is what [isPausedForFocus] is.
     * ⚠️ AudioFocusListener SETS THE FLAG BEFORE IT PAUSES in all three auto-resumable paths
     * (AUDIOFOCUS_LOSS, AUDIOFOCUS_LOSS_TRANSIENT, AUDIOFOCUS_REQUEST_DELAYED), and pause() flushes
     * its listeners inline on this thread, so the flag is reliably visible here. The one path that
     * does NOT set it is AUDIOFOCUS_REQUEST_FAILED, where focus was denied with no auto-resume - so
     * aborting there is correct, not a gap.
     */
    fun onUserPause() {
        if (isPausedForFocus()) return onInterruptionPause()
        abort(Abort.USER_PAUSE)
    }

    /**
     * A transient focus loss / notification duck. Carries the sidecar with the main player instead of
     * aborting: an interruption must not cost the tail, and the overlap resumes where it left off.
     */
    fun onInterruptionPause() {
        if (phase != Phase.ACTIVE) return abort(Abort.INTERRUPTION)
        runCatching { sidecar.pause() }
    }

    /** playWhenReady went true again. Mirrored immediately rather than on the next tick. */
    fun onResumed() {
        if (phase == Phase.ACTIVE) runCatching { sidecar.play() }
        startTick()
    }

    /**
     * ⚠️ TICK LIFECYCLE ONLY - IT MUST NOT PAUSE THE SIDECAR. isPlaying goes false while the
     * main player buffers the new item after the hand-off, and the tail has to keep playing through
     * exactly that window. playWhenReady is the signal that means "the user stopped", and it arrives
     * at [onUserPause] / [onInterruptionPause] / [onResumed].
     */
    fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) startTick() else if (phase != Phase.ACTIVE) stopTick()
    }

    /**
     * The Oct 3 hold-instead-of-skip path. NOT a user pause: it is app-initiated, so media3 reports
     * it as a user request and the reason code cannot tell the two apart - which is why
     * PlayerEventListener tells us explicitly rather than us inferring from playWhenReady.
     */
    fun onStallHold() = abort(Abort.STALL_HOLD)

    fun onPlayerError() = abort(if (phase == Phase.ACTIVE) Abort.MAIN_RECOVERY else Abort.ERROR)

    /**
     * The main player reached STATE_IDLE - a stop() from the buffering watchdog's re-prepare, an
     * error retry, or end of queue. ⚠⚠ THE MAIN PROCESSOR MAY BE MUTED AT THIS POINT, so this
     * hook exists purely to guarantee the mute is lifted. Never leave the main muted.
     */
    fun onPlayerIdle() = abort(Abort.MAIN_RECOVERY)

    fun onQueueEdit() = abort(Abort.QUEUE_EDIT)

    /**
     * AA connected while something was armed or overlapping. [armRefusal] keeps a new one from
     * starting; this ends the one already in flight. Edge-triggered from PlayerService's
     * carConnectionObserver rather than polled in the tick, because that observer is the sole
     * writer of the flag it reads - there is no path to AA-connected that does not pass through it.
     */
    fun onAndroidAutoConnected() = abort(Abort.ANDROID_AUTO)

    fun onSkipSilenceChanged(enabled: Boolean) {
        sidecar.skipSilenceEnabled = enabled
    }

    fun release() {
        abort(Abort.SETTING_OFF)
        stopTick()
        runCatching { sidecar.release() }
    }

    // ── The tick ────────────────────────────────────────────────────────────────────────────────

    private fun onTick() {
        when (phase) {
            Phase.ACTIVE -> tickActive()
            Phase.ARMED -> tickArmed()
            Phase.IDLE -> tickIdle()
        }
    }

    private fun tickIdle() {
        if (fadeSeconds == 0) return
        val refusal = armRefusal()
        if (refusal != null) return probeRefusal(refusal)
        val duration = exoPlayer.duration
        val speed = exoPlayer.playbackParameters.speed.coerceAtLeast(0.1f)
        // Lead scaled by speed because a position threshold is MEDIA time while "25 seconds of
        // warning" is wall-clock intent - the same correction EffectsListener.scheduleFadeOut made.
        val fadeMs = fadeSeconds * 1000L
        val leadMs = (max(ARM_LEAD_BASE_MS + fadeMs, ARM_LEAD_FLOOR_MS) * speed).toLong()
        if (exoPlayer.currentPosition >= duration - leadMs) arm()
    }

    private fun tickArmed() {
        val item = exoPlayer.currentMediaItem ?: return abort(Abort.TRACK_CHANGE)
        if (item.mediaId != armedMediaId) return abort(Abort.TRACK_CHANGE)
        val duration = exoPlayer.duration
        if (duration == C.TIME_UNSET) return abort(Abort.TRACK_CHANGE)
        val position = exoPlayer.currentPosition
        if (!sidecarPrepared) maybePrepareSidecar()
        // Gate point, re-derived every tick from the CURRENT duration and speed, so a duration that
        // changes under us (ProgressiveMediaPeriod back-fills it at load completion) moves the gate
        // rather than stranding it. effectiveFade is wall-clock intent, hence the speed factor.
        val speed = exoPlayer.playbackParameters.speed.coerceAtLeast(0.1f)
        val remaining = duration - position
        effectiveFadeMs = min(fadeSeconds * 1000L * speed, remaining.toFloat()).toLong()
        if (position >= duration - effectiveFadeMs) {
            if (remaining <= LATE_ABORT_MS) return abort(Abort.NOT_READY)
            evaluateGate(item, position, duration)
        }
    }

    private fun tickActive() {
        // 1. Did the tail die under us? The sidecar deliberately has NO listeners (attaching ours
        //    would double-fire history, radio top-up and the skip breakers), so polling the state we
        //    already tick on is the right instrument, not a second listener.
        if (sidecar.playbackState == Player.STATE_IDLE || sidecar.playerError != null)
            return abort(Abort.TAIL_DIED)
        // 2. Mirror the duck. AudioFocusListener is the ONLY writer of the main player's volume
        //    (DUCK_VOLUME on TRANSIENT_CAN_DUCK, 1f on GAIN); we only ever write the sidecar's, so
        //    there is no second decider - this is a mirror, not a policy.
        runCatching { sidecar.volume = exoPlayer.volume }
        // 3. Waiting for the main player's first post-hand-off frame.
        if (rampStartedAtMs == 0L) {
            val token = overlapToken
            if (token != null && mainProcessor.overlapToken() === token) startRamps()
            else if (System.currentTimeMillis() - handoffAtMs > RAMP_TIMEOUT_MS)
                abort(Abort.RAMP_TIMEOUT)
            return
        }
        // 4. Ramp running.
        if (System.currentTimeMillis() - rampStartedAtMs >= rampMs) complete()
    }

    // ── Arm / prepare / gate ────────────────────────────────────────────────────────────────────

    /** Null when nothing refuses; otherwise the closed-set reason for the ARM probe line. */
    private fun armRefusal(): String? {
        if (isRestoring()) return "restore"
        // Product decision 7: no overlap on Android Auto. Read the note at [activate] before
        // touching this - the reason is a failure we can neither prevent nor detect, not a
        // preference. Checked early and cheaply, so an AA session never arms at all.
        if (isAndroidAutoConnected()) return "androidAuto"
        val item = exoPlayer.currentMediaItem ?: return "noItem"
        if (exoPlayer.repeatMode == Player.REPEAT_MODE_ONE) return "repeatOne"
        val duration = exoPlayer.duration
        if (duration == C.TIME_UNSET) return "noDuration"
        // The reference rule, and NOT the same number as MIN_REMAINING_MS: this one asks whether the
        // TRACK is long enough to be worth fading at all, the other asks whether enough of THIS
        // playthrough is left at ARM. A 20s interlude should never crossfade however it is reached.
        if (duration <= fadeSeconds * 1000L + MIN_TRACK_HEADROOM_MS) return "tooShortTrack"
        if (!sessionPlayer.hasNextMediaItem()) return "noNext"
        val next = runCatching {
            sessionPlayer.getMediaItemAt(sessionPlayer.nextMediaItemIndex)
        }.getOrNull() ?: return "noNext"
        if (isIntraAlbum(item, next)) return "album"
        return null
    }

    /**
     * Suppress only across a REAL intra-album boundary, which is not the same question as "did this
     * item come from an album".
     *
     * ⚠⚠ THE OLD TEST WAS `mediaItem.context is Album` ON THE ARRIVING ITEM ALONE, and it
     * suppressed three cases that are not album playback at all: tracks added to an arbitrary queue
     * from an album page (PlayerCallback.addToQueue's `addedContext = item.takeUnless { it is Track }`
     * stamps every one of them with that Album), the last album track crossing into radio or
     * continuous play, and any single inherited-context track played alone. Comparing BOTH sides is
     * what fixes all three.
     * ⚠⚠ SHUFFLE ON MEANS NO SUPPRESSION AT ALL, and this is the case the old test got most
     * wrong for anyone who listens shuffled: under shuffle, queue adjacency carries no album meaning,
     * so two album tracks landing next to each other are exactly the pair that SHOULD crossfade.
     * ⚠️ ORDER NUMBERS ARE ONLY SOMETIMES THERE, which is why there are two unshuffled branches.
     * Track.albumOrderNumber / albumDiscNumber are nullable and populated by Deezer (DeezerParser's
     * TRACK_NUMBER / DISK_NUMBER) and by anything Unified wraps from it; nothing in OfflineExtension
     * sets them, and third-party extensions are unknown. They DO survive slimming, because toSlim is
     * a data-class copy that only rewrites the fields it names. When both are present, demand the
     * same disc and the next track number. When either is absent, fall back to
     * same-album-and-queue-adjacent, which is what unshuffled album playback produces anyway.
     */
    private fun isIntraAlbum(departing: MediaItem, arriving: MediaItem): Boolean {
        if (!skipFadeOnAlbums()) return false
        val from = departing.context as? Album ?: return false
        val to = arriving.context as? Album ?: return false
        if (from.id != to.id) return false
        if (sessionPlayer.shuffleModeEnabled) return false
        val fromTrack = departing.track
        val toTrack = arriving.track
        val fromOrder = fromTrack.albumOrderNumber
        val toOrder = toTrack.albumOrderNumber
        return if (fromOrder != null && toOrder != null)
            fromTrack.albumDiscNumber == toTrack.albumDiscNumber && toOrder == fromOrder + 1
        else true
    }

    private fun arm() {
        val item = exoPlayer.currentMediaItem ?: return
        val duration = exoPlayer.duration
        if (duration == C.TIME_UNSET) return
        val position = exoPlayer.currentPosition
        val remaining = duration - position
        if (remaining < MIN_REMAINING_MS) return probeArm(false, "tooShort")
        val speed = exoPlayer.playbackParameters.speed.coerceAtLeast(0.1f)
        effectiveFadeMs = min(fadeSeconds * 1000L * speed, remaining.toFloat()).toLong()
        phase = Phase.ARMED
        armedMediaId = item.mediaId
        armedTailStartMs = duration - effectiveFadeMs
        probeArm(true, "armed")
        maybePrepareSidecar()
    }

    /**
     * Prepares the sidecar the first time the main player reports the current item fully loaded, and
     * is a no-op otherwise - so a slow link simply prepares later, and a link too slow to finish
     * before the gate fires prepares not at all and fails the gate on sidecarReady.
     *
     * ⚠⚠ THE PRECONDITION IS "THE MAIN PLAYER FINISHED LOADING", WHICH IS NOT THE SAME MOMENT
     * AS ARM. The cache-only source can only succeed once the tail is COMMITTED, and the final
     * fragment commits when the main player's loadable completes
     * (ProgressiveMediaPeriod.ExtractingLoadable.load's finally -> CacheDataSink.close -> commitFile).
     * Preparing too early means the cache-only chain opens a hole, gets PlaceholderDataSource's
     * IOException, burns media3's three load retries and lands in IDLE - with no listener to notice.
     * ⚠️ bufferedPosition >= duration IS THE TEST, AND IT IS TRUE FOR A SECOND REASON WORTH
     * KNOWING: ExoPlayerImpl.getContentBufferedPosition:1294-1297 short-circuits to the current
     * window's full duration as soon as the LOADING period moves to a different window - which is
     * exactly when MediaPeriodQueue.shouldLoadNextMediaPeriod:209-215 lets it move, i.e. when this
     * item is fully buffered. Same instant, different arithmetic than "this period's own buffer".
     * ⚠️ NO isLoading TERM, AND ITS ABSENCE IS LOAD-BEARING. isLoading tracks the LOADING
     * period, not the playing one, and that slot goes to the NEXT item the instant the current one is
     * fully buffered - so `!isLoading` would be false almost exactly when this needs to be true.
     */
    private fun maybePrepareSidecar() {
        if (phase != Phase.ARMED || sidecarPrepared) return
        val item = exoPlayer.currentMediaItem ?: return
        if (item.mediaId != armedMediaId) return
        val duration = exoPlayer.duration
        if (duration == C.TIME_UNSET) return
        if (exoPlayer.bufferedPosition < duration) return
        val ok = runCatching {
            sidecar.setMediaSource(tail.factory.createMediaSource(item))
            sidecar.prepare()
            sidecar.seekTo(armedTailStartMs)
        }.isSuccess
        if (ok) sidecarPrepared = true else probeArm(false, "sidecarSourceFailed")
    }

    private fun evaluateGate(item: MediaItem, position: Long, duration: Long) {
        val buffered = exoPlayer.bufferedPosition
        // Same reason as maybePrepareSidecar: no isLoading term. It is still PRINTED on the probe
        // line, because "was the player still loading something" is useful context - but it is an
        // observation, never a condition.
        val playerReady = buffered >= duration
        val gate = tail.gate(item)
        val sidecarReady = sidecar.playbackState == Player.STATE_READY
        probeGate(playerReady, sidecarReady, buffered, duration, gate)
        if (playerReady && gate.ready && sidecarReady) activate(position, duration)
        else abort(Abort.NOT_READY)
    }

    // ── The hand-off ────────────────────────────────────────────────────────────────────────────

    /**
     * ⚠⚠ TEN STEPS IN THIS ORDER, AND THE ORDER IS THE DESIGN. After the hand-off the MAIN
     * player is the INCOMING leg and the sidecar is the OUTGOING one, which is the opposite of the
     * intuition that the sidecar "is the crossfade".
     *   1-2. re-sync the sidecar to where the main player actually is (see the class note);
     *   3.   mirror speed / skip-silence / volume while it is still silent, so nothing is ever heard
     *        at the wrong rate or level for a frame;
     *   4.   NOT a fade-out yet. The tail plays at FULL gain until the token, because starting it
     *        here would have it descending through the 1-2 s the seek takes, which reintroduces the
     *        dip the equal-power curve exists to avoid;
     *   5.   mute the main processor and stage the token. From here the main is silent, which is what
     *        stops the departing track being audible TWICE (its own buffered audio plus the tail);
     *   6.   the tail becomes audible, at full gain;
     *   7.   the seek, inside [handoff] so our own onSeek/onTransition do not abort it;
     *   8.   media3 flushes the main processor; its onFlush clears the COUNTERS but keeps the mute
     *        and the token - that asymmetry is what lets step 5 survive the seek;
     *   9.   the main player's first post-flush frame publishes the token, still silent;
     *   10.  the next tick arms BOTH ramps with ONE length (see [startRamps]).
     *
     * ⚠⚠ ANDROID AUTO: CROSSFADE IS DISABLED WHILE AA IS CONNECTED, AND THAT IS THE
     * WHOLE GUARD. See [armRefusal]'s "androidAuto" arm and [onAndroidAutoConnected]. The reasoning,
     * because it went through two wrong answers first:
     *   Every other skip path takes `if (isAndroidAutoConnected()) { player.pause(); delay(50) }`
     *   before advancing (a14a8bc7, 2026-05-11, "Android Auto silent playback after skip fix"), and
     *   the hand-off IS a skip. But pause() is not available to us here: a USER_REQUEST pause with
     *   pausedForFocus=false makes AudioFocusListener ABANDON AUDIO FOCUS
     *   (AudioFocusListener.onPlayWhenReadyChanged), and the sidecar shares this audio session, so
     *   the guard would silence the tail mid-overlap and hand focus to another app.
     * ⚠️ AND RAMP_TIMEOUT DOES NOT COVER IT - THAT ARGUMENT WAS WRONG AND IS RECORDED
     * HERE SO IT IS NOT MADE AGAIN. The May symptom is "the next track appeared to play (progress
     * moving) but produced no audio on any output": progress moving means frames WERE reaching the
     * processor, so the overlap token would publish normally, the ramps would run, and nothing in
     * this class would notice. The silence was downstream of us, in the AA AudioTrack. A failure we
     * can neither prevent nor detect is a failure we must not risk, so the overlap simply does not
     * happen on AA.
     * ⚠️ RE-ENABLING THIS NEEDS A DELIBERATE CAR TEST, NOT A CODE REVIEW. It cannot be
     * reasoned to: the question is whether an un-paused seek still kills the AA AudioTrack on
     * current head units and current media3, and the only instrument for that is a car, a
     * crossfaded transition, and someone listening. Until then this arm stays.
     */
    private fun activate(position: Long, duration: Long) {
        val expected = armedTailStartMs + (position - (duration - effectiveFadeMs))
        val before = runCatching { sidecar.currentPosition }.getOrDefault(0L)
        val offset = before - expected
        val resync = abs(offset) > RESYNC_TOLERANCE_MS
        probeHandoff(position, before, offset, resync, duration - position)
        runCatching {
            if (resync) sidecar.seekTo(expected)
            sidecar.volume = exoPlayer.volume
            sidecar.playbackParameters = exoPlayer.playbackParameters
            sidecar.skipSilenceEnabled = exoPlayer.skipSilenceEnabled
        }
        val token = Any()
        overlapToken = token
        mainProcessor.armIncoming(token)
        runCatching { sidecar.play() }
        phase = Phase.ACTIVE
        handoffAtMs = System.currentTimeMillis()
        rampStartedAtMs = 0L
        handoff { sessionPlayer.seekToNextMediaItem() }
    }

    /**
     * ⚠⚠ ONE LENGTH, ONE START, BOTH LEGS - THAT IS WHAT EQUAL POWER REQUIRES. cos/sin only
     * sum to unity if the two legs share a clock, so the length is computed here, on the main thread,
     * once, and handed to both processors in the same task. The length is
     * min(effectiveFade, max(tailRemaining, MIN_RAMP_MS)) exactly as the reference does: never longer
     * than the fade the user asked for, never longer than the tail that exists, never shorter than
     * the floor.
     * ⚠️ A TAIL THAT ALREADY ENDED gets no fade-out - there is nothing left to fade - and the
     * main still fades in over the floor, so the new track never slams in at full gain.
     */
    private fun startRamps() {
        val ended = sidecar.playbackState == Player.STATE_ENDED
        val tailRemaining = runCatching {
            val d = sidecar.duration
            if (d == C.TIME_UNSET) 0L else (d - sidecar.currentPosition).coerceAtLeast(0L)
        }.getOrDefault(0L)
        rampMs = min(effectiveFadeMs, max(tailRemaining, MIN_RAMP_MS))
        rampStartedAtMs = System.currentTimeMillis()
        if (!ended) sidecarProcessor.startFadeOut(rampMs)
        mainProcessor.startFadeIn(rampMs)
        probeRamp("queueInput", rampStartedAtMs - handoffAtMs, rampMs, tailRemaining)
    }

    /** The overlap ran its course: tear the sidecar down and return to IDLE for the new item. */
    private fun complete() {
        val sidecarStateBefore = runCatching { sidecar.playbackState }.getOrDefault(-1)
        clearRampState()
        resetSidecar()
        phase = Phase.IDLE
        armedMediaId = null
        sidecarPrepared = false
        probeAbort(Abort.COMPLETED, sidecarStateBefore)
    }

    private fun clearRampState() {
        // Both, always, and the main one FIRST: it is the leg that can be muted, and an un-mute that
        // does not happen is silence the user cannot clear.
        runCatching { mainProcessor.clearRamps() }
        runCatching { sidecarProcessor.clearRamps() }
        overlapToken = null
        rampStartedAtMs = 0L
        rampMs = 0L
        handoffAtMs = 0L
    }

    private fun resetSidecar() {
        // Reused, never released - releasing per crossfade would re-enter the June "zombie ExoPlayer"
        // territory and, on a shared audio session, drop session effects on some OEM ROMs.
        runCatching {
            sidecar.stop()
            sidecar.clearMediaItems()
        }
    }

    /**
     * ⚠⚠ ALWAYS SAFE TO CALL, FROM ANY PHASE, AND IT ALWAYS UN-MUTES. Item 4 of the Build 2
     * spec in one function: whatever hits the main player - watchdog stop/prepare, an error skip, a
     * user seek, a queue edit - the main processor comes out of it unmuted with both ramps cleared.
     * The probe line is suppressed when there was nothing to abort, so an IDLE controller does not
     * print on every hook.
     */
    private fun abort(reason: Abort) {
        // Sampled BEFORE anything here touches the sidecar: the stop() below sets STATE_IDLE
        // synchronously, so reading playbackState at the probe call would report the outcome of our
        // own teardown. The useful number is what the sidecar had reached when the abort was decided -
        // the same quantity the gate line's sidecarReady reports, so the two read against each other.
        val sidecarStateBefore = runCatching { sidecar.playbackState }.getOrDefault(-1)
        val wasIdle = phase == Phase.IDLE
        clearRampState()
        phase = Phase.IDLE
        armedMediaId = null
        sidecarPrepared = false
        lastRefusal = null
        lastRefusalId = null
        resetSidecar()
        if (!wasIdle) probeAbort(reason, sidecarStateBefore)
    }

    // ── PROBE (2026-10-15), APPROVED FOR THE BUILD 2 TEST PERIOD ────────────────────────────────
    // ⚠️ REMOVE ALL FIVE LINES IN ONE COMMIT once a capture shows an ARM verdict on every
    // eligible track, a gate pass rate matching prediction, a re-sync offset distribution that says
    // whether the re-sync can ever be dropped, and trigger=queueInput on every hand-off. They are
    // grep-able as "CROSSFADE-PROBE".
    // Each prints UNCONDITIONALLY on its path. The one exception is the arm REFUSAL line, which is
    // edge-triggered per (item, reason): the tick evaluates it five times a second, so printing
    // every time would bury the capture. A refusal therefore prints when it STARTS applying, which
    // is the event; silence after it means "still the same reason", not "nothing ran".
    // Labels come from closed sets decided by measured facts, never by the hypothesis under test.
    private var lastRefusal: String? = null
    private var lastRefusalId: String? = null

    private fun probeRefusal(reason: String) {
        val id = exoPlayer.currentMediaItem?.mediaId
        if (reason == lastRefusal && id == lastRefusalId) return
        lastRefusal = reason
        lastRefusalId = id
        probeArm(false, reason)
    }

    // ⚠️ BLOCK BODY, REQUIRED: any probe called as `return probeX(...)` must be block-bodied, because
    // an expression body on Log.d infers Int and `return <Int>` from a Unit function does not compile.
    // An explicit `: Unit = Log.d(...)` does NOT fix it - Kotlin coerces to Unit in lambdas, not in
    // expression bodies - so the four probes below stay as they are only while they remain statements.
    private fun probeArm(armed: Boolean, reason: String) {
        Log.d(
            TAG,
            "CROSSFADE-PROBE arm armed=" + (if (armed) "yes" else "no") + " reason=" + reason +
                " fade=" + fadeSeconds + "s id=" + exoPlayer.currentMediaItem?.mediaId +
                " dur=" + exoPlayer.duration + " pos=" + exoPlayer.currentPosition +
                " shuffle=" + sessionPlayer.shuffleModeEnabled + " repeat=" + exoPlayer.repeatMode
        )
    }

    private fun probeGate(
        playerReady: Boolean,
        sidecarReady: Boolean,
        buffered: Long,
        duration: Long,
        gate: CacheOnlyTailSource.Gate,
    ) = Log.d(
        TAG,
        "CROSSFADE-PROBE gate gate=" + (if (playerReady && gate.ready && sidecarReady) "pass" else "fail") +
            " bufferedEqDuration=" + playerReady + " buffered=" + buffered + " dur=" + duration +
            " loading=" + exoPlayer.isLoading + " sidecarReady=" + sidecarReady +
            " cachedBytes=" + gate.cachedBytes + " contentLength=" + gate.contentLength +
            " cacheReason=" + gate.reason
    )

    private fun probeHandoff(
        position: Long, sidecarPos: Long, offset: Long, resync: Boolean, remaining: Long,
    ) = Log.d(
        TAG,
        "CROSSFADE-PROBE handoff pos=" + position + " sidecarPos=" + sidecarPos +
            " offset=" + offset + " resync=" + (if (resync) "yes" else "no") +
            " effFade=" + effectiveFadeMs + " remaining=" + remaining
    )

    private fun probeRamp(trigger: String, afterHandoffMs: Long, length: Long, tailRemaining: Long) =
        Log.d(
            TAG,
            "CROSSFADE-PROBE ramp trigger=" + trigger + " afterHandoff=" + afterHandoffMs +
                " rampMs=" + length + " tailRemaining=" + tailRemaining
        )

    private fun probeAbort(reason: Abort, sidecarStateBefore: Int) = Log.d(
        TAG, "CROSSFADE-PROBE abort reason=" + reason + " sidecarState=" + sidecarStateBefore
    )

    companion object {
        private const val TAG = "GladixPlayback"

        /** Thresholds carried from the reference implementation; see crossfade-status.md. */
        private const val ARM_LEAD_BASE_MS = 25_000L
        private const val ARM_LEAD_FLOOR_MS = 30_000L
        private const val MIN_REMAINING_MS = 1_500L
        private const val MIN_TRACK_HEADROOM_MS = 5_000L

        /** The reference polls at 200 ms; so do we, and it is the only clock this class has. */
        private const val TICK_MS = 200L

        /** Re-sync when the sidecar is further off than this. Mandatory, not polish. */
        private const val RESYNC_TOLERANCE_MS = 200L

        /**
         * The reference waits up to 4 s for its incoming leg to become READY. Ours is the deadline
         * for the main player's first post-hand-off frame; past it the mute is lifted by force,
         * because a muted main player with no tail left is silence the user cannot clear.
         */
        private const val RAMP_TIMEOUT_MS = 4_000L

        /** Shortest overlap worth running; also the length used when the tail already ended. */
        private const val MIN_RAMP_MS = 1_000L

        /** Closer to the end than this at the gate and there is nothing worth handing off. */
        private const val LATE_ABORT_MS = 800L

        const val FADE_SECONDS_MIN = 0
        const val FADE_SECONDS_MAX = 10
    }
}
