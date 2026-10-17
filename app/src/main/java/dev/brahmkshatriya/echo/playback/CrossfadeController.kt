package dev.brahmkshatriya.echo.playback

import android.os.Handler
import android.os.Looper
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
        NOT_READY, TAIL_DIED, RAMP_TIMEOUT, MAIN_RECOVERY,
    }

    // ⚠️ `reason` IS NOW WRITE-ONLY, AND THE ENUM IS KEPT ANYWAY. Every value above
    // used to be read by a probe line that printed it; the probes are gone (2026-10-17) and nothing
    // consumes the label any more. It stays because it is the only record of WHY each of the twelve
    // [abort] call sites aborts - `abort(Abort.STALL_HOLD)` says at the call site what a bare
    // `abort()` would not - and because a future diagnostic wants exactly this set back. Expect an
    // "unused parameter" warning on [abort]; that is this decision, not rot.

    private var phase = Phase.IDLE
    private var armedMediaId: String? = null
    private var armedTailStartMs = 0L
    private var sidecarPrepared = false

    /**
     * Per-playthrough gate budget. ⚠️ DELIBERATELY NOT CLEARED BY [abort] - abort IS the
     * failing path, so clearing it there would make the budget unenforceable. Cleared only on a
     * genuine track change ([onTransition]) and on a completed overlap ([complete]).
     */
    private var gateAttempts = 0
    private var gateAttemptsForMediaId: String? = null

    /** Bounded re-prepare budget for an ENDED sidecar; see [recoverEndedSidecar]. */
    private var sidecarRePrepares = 0

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
        gateAttempts = 0
        gateAttemptsForMediaId = null
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

    /**
     * A queue edit. ⚠⚠ THREE DIFFERENT ANSWERS BY PHASE, AND AN UNCONDITIONAL ABORT
     * HERE IS WHAT BROKE BUILD 2's FIRST DEVICE TEST. Our own hand-off edits the queue:
     * ShufflePlayer.advanceForward seeks and then calls pushAndRemove(departing), whose
     * removeByMediaId is a removeMediaItem - a PLAYLIST_CHANGED timeline change delivered
     * SYNCHRONOUSLY inside seekToNextMediaItem, i.e. inside [handoff]. [onTransition] and
     * [onSeek] consult the latch; this hook did not, so every overlap tore itself down ~35 ms
     * after it started (device test 2026-10-15: two transitions, both reason=QUEUE_EDIT
     * sidecarState=3, audible as the old track cutting off with no overlap).
     * ⚠️ THE ~35 ms WAS NOT ASYNCHRONY - DO NOT GO LOOKING FOR A POST. The number
     * came from a probe line (removed 2026-10-17) that printed BEFORE the seek in [activate]: the
     * gap is the cost of the seek machinery itself, and the removal, its onTimelineChanged and the
     * abort all happened INSIDE the bracket.
     */
    fun onQueueEdit() {
        if (handoffInFlight) return
        when (phase) {
            Phase.IDLE -> return
            // Nothing left to re-decide - the incoming item is already current. A removal or
            // replacement OF it arrives as onMediaItemTransition, which PlayerEventListener routes
            // to [onTransition] for EVERY reason, so that case aborts there as TRACK_CHANGE.
            // ⚠️ BUT A CLEARED QUEUE REACHES NEITHER HOOK, which is why this is not a
            // bare return: PlayerEventListener.onMediaItemTransition returns early on a null item,
            // and an emptied timeline reports STATE_ENDED rather than STATE_IDLE
            // (ExoPlayerImplInternal.java:1069, media3 1.11.0 -
            // `setState(timeline.isEmpty() ? STATE_ENDED : STATE_BUFFERING)`), while our
            // onPlayerIdle hook fires only on STATE_IDLE. Without this arm the main processor
            // would stay MUTED once the tail ran out: silence the user cannot clear.
            Phase.ACTIVE -> if (exoPlayer.currentMediaItem == null) abort(Abort.QUEUE_EDIT)
            // ⚠⚠ RE-DECIDE WITH THE SAME PREDICATE THE ARM USED, rather than abort.
            // That is what PlayerEventListener's call site has always asked for ("A queue edit can
            // change the NEXT item without changing the current one ... Re-decide"); it simply had
            // no way to say it. An edit matters only if it changed something [armRefusal] reads:
            // the queue emptied ("noItem"), the next item went away ("noNext"), or the new
            // neighbour is an intra-album one ("album"). An append PAST the next item (radio
            // top-up), a 50-item window shift after a seek, a REPEAT_ALL reconstitution and the
            // removal of some OTHER copy of a duplicated track all leave those three unchanged.
            // ⚠️ DUPLICATE-SAFE BY CONSTRUCTION, which is why there is no mediaId
            // bookkeeping here: armRefusal reads nextMediaItemIndex POSITIONALLY and then compares
            // CONTENT (album id, disc number, order number via [isIntraAlbum]). Nothing on this
            // path searches the queue for a mediaId, so the Jul 7-8 multiset problem - two
            // identical items being indistinguishable by id - cannot reach it.
            Phase.ARMED -> if (armRefusal() != null) abort(Abort.QUEUE_EDIT)
        }
    }

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
        // The string is now documentation only - see [armRefusal]. Non-null means "do not arm".
        if (armRefusal() != null) return
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
        if (sidecarPrepared &&
            runCatching { sidecar.playbackState }.getOrNull() == Player.STATE_ENDED
        ) recoverEndedSidecar()
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

    /**
     * Null when nothing refuses; otherwise a reason, which since 2026-10-17 is READ ONLY AS
     * NON-NULL - the strings used to label a probe line and are kept because they name the
     * conditions at the point that tests them, which a bare `false` would not.
     *
     * ⚠️ THERE IS NO ANDROID AUTO ARM HERE ANY MORE. One stood second in this list
     * from 2026-05 to 2026-10-17; see [activate]'s note for the decision and the drive that
     * settled it. If AA ever needs excluding again this is where it goes, and it must be checked
     * early and cheaply as that one was.
     */
    private fun armRefusal(): String? {
        if (isRestoring()) return "restore"
        val item = exoPlayer.currentMediaItem ?: return "noItem"
        // ⚠⚠ THE GATE BUDGET, AND WITHOUT IT THE RETRY IS UNBOUNDED RATHER THAN ABSENT.
        // [abort] sets phase=IDLE, and tickIdle re-arms the moment position >= duration - leadMs -
        // which after a gate failure at dur-10 s is still true, so the controller re-armed on the
        // very next tick. Measured 2026-10-15: a NOT_READY at 16:36:16.885 re-armed at .087 and
        // gated again at .290, with effFade 9542 instead of ~10000.
        // ⚠️ MAX_GATE_ATTEMPTS IS 2, NOT 1, AND THE CAPTURE IS THE REASON. The Build 2
        // spec said a failed gate means no crossfade for that transition - but that second attempt
        // PASSED, so a budget of 1 would have converted a working crossfade into none, for a 458 ms
        // shorter fade nobody can hear. It also matches the reference app, which keeps polling and
        // only gives up ~0.8 s before the end (our LATE_ABORT_MS). What this bounds is the
        // PATHOLOGICAL case - a gate that can never pass re-arming five times a second for the rest
        // of the track - not the transient one.
        if (gateAttempts >= MAX_GATE_ATTEMPTS && item.mediaId == gateAttemptsForMediaId)
            return "gateAttemptsUsed"
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
        if (remaining < MIN_REMAINING_MS) return
        val speed = exoPlayer.playbackParameters.speed.coerceAtLeast(0.1f)
        effectiveFadeMs = min(fadeSeconds * 1000L * speed, remaining.toFloat()).toLong()
        phase = Phase.ARMED
        armedMediaId = item.mediaId
        sidecarRePrepares = 0
        armedTailStartMs = duration - effectiveFadeMs
        maybePrepareSidecar()
    }

    /**
     * Prepares the sidecar the first time the main player reports the current item fully loaded, and
     * is a no-op otherwise - so a slow link simply prepares later, and a link too slow to finish
     * before the gate fires prepares not at all and fails the gate on sidecarReady.
     *
     * ⚠⚠ THE PRECONDITION IS "THE MAIN PLAYER FINISHED LOADING", WHICH IS NOT THE SAME MOMENT
     * AS ARM. It was originally required because the tail had no upstream, so preparing before the
     * final fragment committed (ProgressiveMediaPeriod.ExtractingLoadable.load's finally ->
     * CacheDataSink.close -> commitFile) opened a hole, got PlaceholderDataSource's IOException,
     * burned media3's three load retries and landed in IDLE with no listener to notice.
     * ⚠️ [AMENDED 2026-10-15] THAT IS NO LONGER WHY IT IS KEPT - the tail now has an
     * upstream and a hole is fetched rather than fatal. It is kept because it is also what keeps
     * the sidecar OFF the same cache key while the main player is still writing it, which is the
     * hole-lock hazard documented at CacheOnlyTailSource's read-only note. Do not relax it to
     * "prepare at ARM" on the grounds that holes are now survivable: the two players would then
     * contend on one key and could punch holes into it.
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
        // ⚠️ TOLERANCE, BECAUSE AN EXACT COMPARISON LOST A CROSSFADE BY 12 ms. Measured
        // 2026-10-15: buffered=176281 against dur=176293, so this returned and the sidecar never
        // prepared at all. The two numbers cannot be expected to meet exactly - duration is derived
        // by ConstantBitrateSeekMap from inputLength/bitrate, while bufferedPosition advances in
        // WHOLE MP3 FRAMES of 1152 samples (~26.1 ms at 44.1 kHz), so a sub-frame residual is
        // structural, and encoder padding plus the last partial frame can add another.
        // BUFFERED_EPSILON_MS is ~5 frames: far above that noise, far below both the fade length and
        // LATE_ABORT_MS, so it cannot let the gate run on a track that has not genuinely finished
        // loading. A CONSTANT, not a fraction of duration - the error is per-frame, not proportional.
        if (exoPlayer.bufferedPosition < duration - BUFFERED_EPSILON_MS) return
        // ⚠⚠ THE START POSITION TRAVELS WITH THE SOURCE - NEVER seekTo() AFTER prepare().
        // This used to be setMediaSource(); prepare(); seekTo(armedTailStartMs), three calls on the
        // app looper with nothing between them, so the seek was PROCESSED AGAINST THE PLACEHOLDER
        // TIMELINE - ProgressiveMediaSource has not published a duration yet at that instant. That
        // routes it through ExoPlayerImplInternal.seekTo, which has two ways to end playback
        // outright: resolvedSeekPosition == null -> setState(STATE_ENDED) (1.11.0, :1746-1749), and
        // ProgressiveMediaPeriod.startLoading's pendingResetPositionUs > maxLoadPositionUs ->
        // loadingFinished = true WITH NO LOAD ISSUED (:1085-1089). Either one leaves the sidecar in
        // STATE_ENDED having read nothing, which is exactly what the device showed: three
        // sidecarEnded lines on 2026-10-15 with seekTarget TEN SECONDS INSIDE tailDur.
        // ⚠️ setMediaSource(source, startPositionMs) AVOIDS BOTH BY CONSTRUCTION, and the
        // reason is in ExoPlayerImpl: it routes to setMediaSourcesInternal, which hands the position
        // to internalPlayer.setMediaSources(holders, startWindowIndex, Util.msToUs(startPositionMs),
        // shuffleOrder) (:2658-2659). The position therefore arrives as part of the PLAYLIST UPDATE
        // and is resolved against the REAL timeline when it first appears - it never passes through
        // seekTo's resolver, and it never sets isPendingReset(), which is the precondition for
        // startLoading's guard. Not a race narrowed; a path removed.
        // ⚠️ AND IT IS WHY THERE IS NO NEW PROBE HERE. The old probe sampled
        // sidecar.duration when the TICK NOTICED the ENDED - up to 200 ms late and after prepare()
        // had published the real seek map - so it could never tell "duration unknown at the seek"
        // from "known 200 ms later". The fix removes the branch instead of measuring it.
        val ok = runCatching {
            // ⚠⚠ BELT FOR THE playWhenReady LEAK - see [resetSidecar], which is the
            // primary fix. Set here too because this is the ONE line that decides whether a prepare
            // starts playing, and it must hold however the sidecar got into its current state
            // (a reset we did not run, a future path that stops without resetting, an OEM quirk).
            // Cheap and idempotent; never remove it as redundant.
            sidecar.playWhenReady = false
            sidecar.setMediaSource(tail.factory.createMediaSource(item), armedTailStartMs)
            sidecar.prepare()
        }.isSuccess
        if (ok) sidecarPrepared = true
    }

    private fun evaluateGate(item: MediaItem, position: Long, duration: Long) {
        val buffered = exoPlayer.bufferedPosition
        // Same reason as maybePrepareSidecar: no isLoading term, and no tolerance of its own -
        // same tolerance as [maybePrepareSidecar], or the sidecar prepares and then the gate
        // refuses it for the identical handful of milliseconds.
        val playerReady = buffered >= duration - BUFFERED_EPSILON_MS
        val sidecarReady = sidecar.playbackState == Player.STATE_READY
        // ⚠⚠ [CORRECTED 2026-10-17] THIS NO LONGER CALLS tail.gate(item) AT ALL, AND
        // THE PARAGRAPH BELOW IS WHY. The call's only remaining consumer was the removed probe
        // line, so it had become per-tick content-index I/O feeding nothing. CacheOnlyTailSource
        // .gate and its Gate type now have NO CALLERS - left in place rather than deleted here
        // because deciding that is a separate call, and because the next cache question wants it.
        // ⚠⚠ gate.ready WAS NO LONGER A CONDITION EVEN BEFORE THAT. It asked "is every
        // byte committed to the cache", which was the right proxy for "can the tail be read" only
        // while the tail had no upstream. It now refuses crossfades that would work: two
        // consecutive tracks on 2026-10-15 failed on cacheReason=incomplete alone (73% and 93%
        // committed) with the main player reporting the whole track buffered and not loading.
        // ⚠️ sidecarReady IS THE HONEST SIGNAL, AND IS NOW THE ONLY ONE: STATE_READY
        // means the sidecar has actually buffered the tail, whichever side of the cache the bytes
        // came from. The old note asked to keep printing gate.reason to see whether the upstream
        // fallback was being used; that question is answered - the 2026-10-17 drive crossfaded five
        // times with the upstream in place - so the reading is no longer needed.
        if (playerReady && sidecarReady) {
            activate(position, duration)
            return
        }
        // Increment against the OLD id, THEN adopt the new one - the reverse order makes the
        // comparison self-referential and the counter never passes 1.
        gateAttempts = if (item.mediaId == gateAttemptsForMediaId) gateAttempts + 1 else 1
        gateAttemptsForMediaId = item.mediaId
        abort(Abort.NOT_READY)
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
     * ⚠⚠ ANDROID AUTO: CROSSFADE RUNS ON AA. DECIDED 2026-10-17, ON A DRIVE.
     * There is no AA arm in [armRefusal], no AA abort hook, and no flag. From 2026-05-11 to
     * 2026-10-17 the overlap was refused outright whenever AA was connected; it is not, now, and
     * this paragraph is the record of why the change was allowed to be permanent.
     *
     * WHAT THE GUARD WAS FOR, because it was not a preference and the reason still has teeth.
     * The May symptom (a14a8bc7, 2026-05-11, "Android Auto silent playback after skip fix", on
     * media3 1.8.0) was "the next track appeared to play - progress moving - but produced no audio
     * on any output". Every other skip path answers it with
     * `if (isAndroidAutoConnected()) { player.pause(); delay(50) }` before advancing, and the
     * hand-off IS a skip - but that pause was never available here: a USER_REQUEST pause with
     * pausedForFocus=false makes AudioFocusListener ABANDON AUDIO FOCUS
     * (AudioFocusListener.onPlayWhenReadyChanged), and the sidecar shares this audio session, so
     * the guard would have silenced the tail mid-overlap and handed focus to another app. So the
     * overlap was refused instead.
     *
     * ⚠️ AND RAMP_TIMEOUT NEVER COVERED IT - THAT ARGUMENT WAS WRONG AND IS KEPT HERE
     * SO IT IS NOT MADE AGAIN. Progress moving means frames WERE reaching the processor, so the
     * overlap token publishes normally, the ramps run, and nothing in this class notices. The
     * silence was downstream of us, in the AA AudioTrack. That is why the question could not be
     * closed by reading code: a failure we can neither prevent nor detect is only ever settled by
     * someone listening to it.
     *
     * ⚠⚠ THE DRIVE, 2026-10-17, DESKTOP HEAD UNIT - what was actually observed, since
     * this is the whole basis for the decision. FIVE crossfades with AA connected, every one
     * COMPLETED; re-sync offset 0 on all five; no audio-focus loss; the audio indistinguishable
     * from the same transitions on the phone. Next, Previous and Pause pressed MID-CROSSFADE each
     * aborted cleanly and playback recovered. So the hand-off's un-paused seek does not kill the
     * AA AudioTrack on a current head unit and current media3 - which is the one thing the guard
     * existed to avoid and the one thing no amount of reading could establish.
     *
     * ⚠️ WHAT WOULD REOPEN IT, stated so a report is not mistaken for something else.
     * A user describing "the next track plays with no sound" ON AA, with progress moving. That is
     * the May symptom exactly, and it would mean the 1.8.0 failure is back or the head unit above
     * is not representative - one drive on one unit is what this rests on. The fix is then the arm
     * refusal, back in [armRefusal] as the second check.
     * ⚠️ TWO THINGS THAT WERE NOT THE VARIABLE, so a re-test does not credit them: audio
     * offload went from user-toggleable to unconditionally DISABLED (7c6b37d1, 2026-07-13,
     * "Disable audio offload to fix silent playback on gapless auto-advance") and media3 went
     * 1.8.0 -> 1.11.0 between the symptom and the drive. Either could be why it now works, and
     * nothing distinguishes them - the May cause was NEVER IDENTIFIED, so this is a behavioural
     * result, not an explained one.
     * ⚠⚠ THE FIVE ERROR-PATH AA GUARDS IN PlayerEventListener ARE UNTOUCHED, AND THAT
     * IS LOAD-BEARING FOR THE RESULT ABOVE. They are the May workaround itself (pause ->
     * delay(50) -> skip) and they still run on every error-driven skip. The drive tested the
     * hand-off WITH those guards in place; removing them would invalidate it.
     */
    private fun activate(position: Long, duration: Long) {
        val expected = armedTailStartMs + (position - (duration - effectiveFadeMs))
        val before = runCatching { sidecar.currentPosition }.getOrDefault(0L)
        val offset = before - expected
        val resync = abs(offset) > RESYNC_TOLERANCE_MS
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
    }

    /** The overlap ran its course: tear the sidecar down and return to IDLE for the new item. */
    private fun complete() {
        clearRampState()
        resetSidecar()
        phase = Phase.IDLE
        armedMediaId = null
        sidecarPrepared = false
        gateAttempts = 0
        gateAttemptsForMediaId = null
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

    /**
     * The sidecar reached STATE_ENDED while merely ARMED.
     *
     * ⚠⚠ [CORRECTED 2026-10-15, AGAIN] THE CAUSE WAS FOUND AND IT WAS NONE OF THE FOUR
     * HYPOTHESES BELOW - IT WAS A LEAKED playWhenReady, and this KDoc previously asserted that very
     * thing was impossible. The sentence here read "It cannot have got there by PLAYING - [activate]
     * is the only caller of sidecar.play()". BOTH HALVES WERE TRUE AND THE CONCLUSION WAS WRONG:
     * activate() is indeed the only caller, but play() sets a FLAG THAT OUTLIVES EVERY RESET - stop()
     * and clearMediaItems() leave playWhenReady alone (ExoPlayerImpl.stopInternal, 1.11.0,
     * :2161-2180). So from the second hand-off onwards each prepare() started the tail playing, and it
     * reached ENDED by doing exactly what the note said it could not do. Fixed at [resetSidecar];
     * read that note for the three symptoms it explains.
     * ⚠️ THE LESSON, NOT JUST THE FACT: "X is the only caller of Y" bounds WHO CALLS Y,
     * never how long Y's EFFECT lasts. A one-shot call that sets persistent state is reachable
     * forever afterwards. Do not write an impossibility claim about state from a claim about calls.
     * ⚠⚠ THE FOUR EARLIER HYPOTHESES ARE KEPT BELOW, ALL REFUTED OR FIXED, because the
     * next reader of an ENDED sidecar will reach for them and all four are dead ends.
     *   1. REFUTED TWICE - "the seek overshot the TAIL's own duration". The capture has
     *      seekTarget=208410 against tailDur=mainDur=218410, in range by ten seconds; and a CBR
     *      byte<->time snap is bounded by ONE MP3 FRAME, ~26 ms at 44.1 kHz
     *      (ConstantBitrateSeekMap.getSeekPoints returns the SNAPPED frame time, always <= the
     *      request). It cannot produce the 6671 ms and 3665 ms offsets that were observed, so the
     *      byte<->time mapping was never the fault here.
     *   2. FIXED - "the seek was issued before the extractor published a duration".
     *      [maybePrepareSidecar] no longer seeks after prepare(); the start position travels with
     *      the source. Read the note there before changing it back.
     * ⚠️ SO IF THIS EVER FIRES AGAIN, NEITHER RECORDED CAUSE EXPLAINS IT - treat it as a
     * new finding rather than reaching for these two, and note that the offsets above are still
     * unexplained: 6.7 s is far too large for any seek-map error and was never accounted for.
     * ⚠️ A THIRD HYPOTHESIS IS ALREADY REFUTED, recorded so it is not re-proposed: "the
     * duration was back-filled at load completion AFTER arm, so armedTailStartMs went stale". The
     * capture shows dur=168053 on BOTH the arm line and the gate line, so the duration did not
     * move between them.
     * ⚠️ AND THE LEFTOVER-SIDECAR HYPOTHESIS IS REFUTED BY THIS FILE: [abort] already
     * sets sidecarPrepared=false and calls [resetSidecar], whose stop() drives STATE_IDLE - so a
     * sidecar left over from a previous abort would read state 1, not 4.
     *
     * BOUNDED, because if hypothesis 1 is the live one a re-prepare lands in ENDED again: an
     * unbounded retry would re-prepare five times a second for the rest of the track. Past the
     * budget the gate simply keeps failing on sidecarReady, which is the correct outcome.
     */
    private fun recoverEndedSidecar() {
        if (sidecarRePrepares >= MAX_SIDECAR_REPREPARES) return
        sidecarRePrepares++
        resetSidecar()
        sidecarPrepared = false
    }

    /**
     * ⚠⚠ CLEARS playWhenReady FIRST, AND THAT LINE IS THE FIX FOR THE WHOLE "BAD
     * CROSSFADE" FAMILY. stop() does NOT reset playWhenReady - ExoPlayerImpl.stopInternal (1.11.0,
     * :2161-2180) masks the state to IDLE and touches nothing else - and neither does
     * clearMediaItems(). So after the first hand-off, where [activate] calls sidecar.play(), the flag
     * stayed TRUE for the life of the sidecar, and EVERY LATER prepare() started it PLAYING THE TAIL
     * THE INSTANT IT WAS READY - while the phase was still ARMED and no ramp was armed, i.e. at FULL
     * GAIN on top of the main player.
     * ⚠️ WHAT THAT PRODUCED ON DEVICE, 2026-10-15, and it explains three separate
     * complaints that were being chased as three bugs:
     *   - "too much of the old song carries over" / "the old song seems to loop or play a wrong
     *     section": a SECOND COPY of the departing track, audible from ARM, seconds before any fade.
     *   - STATE_ENDED while merely ARMED, exactly one fade-length after each prepare
     *     (21:23:33 arm -> 21:23:43 and 21:23:53 ended): it played the ~10 s tail out and finished.
     *   - every non-zero re-sync offset in the captures (6671 ms, 3665 ms): the sidecar had been
     *     playing since prepare, so by the hand-off its position had run past armedTailStartMs.
     * ⚠️ pause() AND the explicit flag write, not one or the other: pause() is the
     * documented way to drop playWhenReady, and setting the field makes the invariant greppable and
     * survives a future refactor that reorders these calls.
     * Reused, never released - releasing per crossfade would re-enter the June "zombie ExoPlayer"
     * territory and, on a shared audio session, drop session effects on some OEM ROMs.
     */
    private fun resetSidecar() {
        runCatching {
            sidecar.pause()
            sidecar.playWhenReady = false
            sidecar.stop()
            sidecar.clearMediaItems()
        }
    }

    /**
     * ⚠⚠ ALWAYS SAFE TO CALL, FROM ANY PHASE, AND IT ALWAYS UN-MUTES. Item 4 of the Build 2
     * spec in one function: whatever hits the main player - watchdog stop/prepare, an error skip, a
     * user seek, a queue edit - the main processor comes out of it unmuted with both ramps cleared.
     *
     * ⚠️ [reason] IS NOT READ - see the note on [Abort]. It documents the call site.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun abort(reason: Abort) {
        clearRampState()
        phase = Phase.IDLE
        armedMediaId = null
        sidecarPrepared = false
        resetSidecar()
    }

    companion object {
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

        /**
         * Slack on "the main player has buffered the whole track". ~5 MP3 frames; see the note at
         * [maybePrepareSidecar] for why an exact comparison cannot hold.
         */
        private const val BUFFERED_EPSILON_MS = 150L

        /** Gate evaluations allowed per playthrough; see the budget note in [armRefusal]. */
        private const val MAX_GATE_ATTEMPTS = 2

        /** Re-prepares allowed for an ENDED sidecar; see [recoverEndedSidecar]. */
        private const val MAX_SIDECAR_REPREPARES = 2

        const val FADE_SECONDS_MIN = 0
        const val FADE_SECONDS_MAX = 10
    }
}
