package dev.brahmkshatriya.echo.playback.renderer

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.StreamMetadata
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

@OptIn(UnstableApi::class)
class AudioEffectsProcessor : BaseAudioProcessor() {

    // ⚠⚠ THE CROSSFADE RAMP, AND IT IS NOT THE OLD TRACK FADE WITH A NEW NAME.
    // Track Fade was a SINGLE-leg envelope: it used gain = cos²(theta), which for one stream is a
    // pleasant steep curve and for an OVERLAP of two is a 3 dB hole in the middle
    // (cos⁴ + sin⁴ = 0.5 at the crossover). An overlap needs EQUAL POWER: gain_out = cos(theta),
    // gain_in = sin(theta), so gain_out² + gain_in² = 1 for uncorrelated signals. Dropping the
    // squaring is the whole change to the curve; the counters and their decrement are unchanged.
    // ⚠⚠ [CHECKED 2026-10-15] THE REFERENCE APP USES THE SAME EQUAL-POWER LAW, SO DO NOT
    // SWITCH THIS TO LINEAR. Echo Expressive 4.1.2's decompile applies cos/sin via setVolume in
    // 40 ms steps (PlayerService:863-868) - the same curve, a coarser applicator. This was checked
    // because a device report of "too much of the old song carries over" at fade=10 led to a
    // proposal to go linear, on the premise that the reference was linear. THE PREMISE WAS WRONG.
    // ⚠️ THE NUMBERS, SO THE ARGUMENT DOES NOT HAVE TO BE REDERIVED: equal power holds
    // the outgoing leg at 0.924 (-0.7 dB) a quarter of the way through and 0.707 (-3.0 dB) at the
    // midpoint; linear would be 0.750 (-2.5 dB) and 0.500 (-6.0 dB). So linear IS audibly milder -
    // but it is not what the reference does, and the reference is the thing that sounds right on
    // this device. The carry-over complaint therefore has to be explained by something else (the
    // tail starving on cache holes was the other candidate, and it was real); if it survives both
    // the upstream fix and the ENDED fix, revisit the curve as a deliberate DEPARTURE from the
    // reference rather than as a correction to it.
    // ⚠️ [CORRECTED 2026-10-28] NOT AN OPEN COMPLAINT - DO NOT READ THE LINE ABOVE AS A TASK. The
    // user's later recorded complaint (Oct 19) was that our 10 felt SHORTER than the reference's 10,
    // not that too much of the old song carried over, and crossfade at 10 was signed off by ear on
    // Oct 21-22 (speaker, headphones, an AA drive). That sign-off had the crossfade tail compressed
    // by Pass A - see OVERLAP_PEDESTAL.
    // ⚠️ LENGTH IS PER-RAMP, NOT A SETTING. Track Fade derived the frame count from a
    // prefs field (crossfadeDurationMs) on every buffer; a crossfade's length is decided once per
    // hand-off (min(fade, tail that actually exists), floored at 1s) and cannot come from a
    // global. [startFadeIn] / [startFadeOut] take MILLISECONDS of MEDIA time and convert here,
    // because only this class knows the sample rate; the speed factor is applied by the caller
    // (see CrossfadeController, which multiplies by playbackParameters.speed for the same reason
    // the old scheduleFadeOut did).
    private val fadeInFramesRemaining = AtomicLong(0)
    private val fadeOutFramesRemaining = AtomicLong(0)
    // Frames the CURRENT ramp was armed with. Both legs of one crossfade are armed with the same
    // number in the same main-thread task, which is what makes the equal-power identity hold.
    // [CORRECTED 2026-11-08] No longer the same number: the outgoing leg is armed with the tail's real remaining audio
    // (CrossfadeController.outgoingRampMs), the incoming with the full fade. Each processor keeps its own total, so each
    // leg's curve still runs 0 -> 1 over its own length; the sum dips about -0.6 to -1 dB mid-overlap instead of unity.
    private val rampTotalFrames = AtomicLong(0)

    // [2026-11-08] HOLD AT 0 ONCE A FADE-OUT HAS FINISHED. queueInput applies the fade only while a counter is above 0
    // and is back at full gain after it, so a fade-out shortened to the tail's remaining audio would play any audio left
    // past an early finish at FULL level. Set only by queueInput, in the buffer where a fade-out counter reaches 0, so
    // it can only affect a leg that was armed with startFadeOut: the incoming leg's fade-out counter is always 0
    // (startFadeIn and armIncoming zero it). Cleared by startFadeOut, startFadeIn, armIncoming, clearRamps, onFlush and
    // onReset. A stop of the sidecar (CrossfadeController.resetSidecar) disables its renderer, whose onDisabled flushes
    // the sink (MediaCodecAudioRenderer.java:798, media3 1.11.1) and so this processor (DefaultAudioSink.java:855), on
    // the same thread as queueInput, so no late write from a buffer in flight can survive into the next crossfade.
    // An atomic like the counters above, for the same reason: written from the app looper and the audio thread.
    private val fadeOutDone = AtomicBoolean(false)

    // [2026-11-10] THE START LOOK-AHEAD'S TWO ADDITIONS (CrossfadeController.startRamps), both set with the ramp and cleared
    // wherever the counters are (armIncoming, clearRamps, onFlush):
    // - The incoming leg holds at gain 0 until the stream reaches [fadeInStartFrame], the next song's measured onset, and only
    //   then starts its ramp. Counted against [emittedFrames], which restarts at the hand-off seek's flush, so it is the
    //   song's own frame position from 0 (the controller passes 0 unless main is near the start).
    // - THE OVERLAP TRIM, which replaced "no headroom guard". A loud start gets a fade-in shorter than the fade-out, and the two
    //   then sum above unity: +2.7 dB of power and up to 1.92 in amplitude (+5.7 dB) at a 2.5 s fade-in under a 10 s
    //   fade-out. Each leg works out the OTHER leg's curve on its own clock ([partnerTotalFrames], [partnerDelayFrames],
    //   [rampElapsedFrames]) and scales itself by g = min(1, 1/sqrt(in^2 + out^2)): the sum's power is held at 1 and its
    //   peak at 1.41, as before. It never boosts, and it is inert (g = 1) whenever the fade-in is at least as long as the
    //   fade-out, which was every crossfade before this: then in^2 + out^2 <= 1 on one clock. The legs are mixed by the
    //   platform (two AudioTracks), so no processor sees the sum and this per-leg trim is the only place a guard can sit.
    private val fadeInStartFrame = AtomicLong(0)
    private val partnerTotalFrames = AtomicLong(0)
    private val partnerDelayFrames = AtomicLong(0)
    private val rampElapsedFrames = AtomicLong(0)
    private val rampIsIncoming = AtomicBoolean(false)

    // ⚠⚠ MUTE AND TOKEN: THE INCOMING-LEG HANDSHAKE, AND onFlush MUST NOT CLEAR THEM.
    // At hand-off the MAIN player is the INCOMING leg: it is muted, then seeked to the next item,
    // and the seek FLUSHES this processor. A fade-in armed before the seek would be wiped by that
    // flush, and an unmuted main player would play the departing track twice (its own buffered
    // audio and the sidecar's tail) for the 1-2 s the seek takes. So:
    //   armIncoming(token)  ->  output silence, remember the token;
    //   onFlush             ->  clears the COUNTERS, keeps muted and pendingToken;
    //   first post-flush queueInput -> publishes the token (still silent);
    //   [CORRECTED 2026-11-03] "post-flush" was not enforced: a departing-track buffer rendered between
    //   armIncoming and the seek published it first, and onFlush kept it. onFlush now clears the
    //   PUBLISHED token (pendingToken and muted still stay) - see the note there.
    //   startFadeIn(ms) from the controller -> clears the mute and starts the ramp.
    // The controller owns every length decision on the main thread; the audio thread only reports
    // "my first real frame happened". That ordering is why no length arithmetic is needed here.
    // ⚠️ IDENTITY-COMPARED OBJECT, NOT A BOOLEAN, and the reason is on record: the
    // Jun 21-26 skipFade fix exists because onQueueEndOfStream runs on the AUDIO thread while the
    // flag was set from MAIN with no ordering guarantee between them. A fresh object per crossfade,
    // compared by identity, cannot let a stale previous crossfade arm anything.
    @Volatile private var muted = false

    /**
     * ⚠⚠ PRE-ROLL DISCARD: PCM FRAMES TO DROP WHILE MUTED. This is how the two legs
     * are aligned WITHOUT A SEEK - a seek would flush the sink and repeat the AudioTrack
     * activation and the platform's own start ramp, which is the artefact the pre-roll exists to
     * move out of earshot. Dropping N frames of input advances the audio relative to the clock by
     * N frames, so the controller measures how far the tail is behind the main player once and
     * cancels it here.
     * ⚠⚠ [CORRECTED 2026-10-20] THE DROP IS NO LONGER INSIDE THE MUTE BRANCH,
     * AND THE NOTE THAT FORBADE MOVING IT WAS RIGHT ABOUT THE REASON AND WRONG ABOUT THE
     * MECHANISM. It read: "THE DROP HAPPENS INSIDE THE MUTE BRANCH, AND THAT IS WHAT MAKES IT
     * INAUDIBLE ... Dropping frames in the UNMUTED path would be a splice in live audio - never
     * move this." What makes a splice safe is that NOTHING IS AUDIBLE WHEN IT IS CONSUMED, and
     * the zero-fill in this branch was only one way to arrange that. Since 2026-10-20 the
     * sidecar is silenced by its PLAYER VOLUME instead (CrossfadeController.startPreRoll), so
     * the branch is never taken on the leg that discards and a drop left in here would have been
     * SCHEDULED AND NEVER APPLIED - the alignment correction silently absent, with the measured
     * gap still logged as if it had been cancelled.
     * ⚠⚠ WHAT STILL HAS TO HOLD, AND WHERE IT IS ENFORCED: the spliced frames
     * must reach the mixer while the volume is still 0. They are queued at the measurement,
     * ~300 ms into a ~2 s pre-roll, and the sink holds ~500 ms - so they are consumed around a
     * second before the volume comes back up. CrossfadeController.measureAlignment's `queue`
     * reject is what refuses the correction when that margin is not there; it is the guard this
     * note's invariant now lives behind.
     * ⚠️ SET BY THE APP LOOPER, READ AND WRITTEN BY THE AUDIO THREAD, hence atomic -
     * the same discipline the ramp counters carry for the Jul 1-3 fade-out bleed.
     */
    private val discardFramesRemaining = AtomicLong(0)

    /**
     * ⚠⚠ CUMULATIVE FRAMES DROPPED, REPORTED TO THE SINK, WHICH IS WHAT KEEPS
     * getCurrentPosition HONEST. DefaultAudioSink derives media time from frames the AudioTrack
     * has PLAYED and adds back whatever the chain says it skipped:
     * `positionUs + framesToDurationUs(audioProcessorChain.getSkippedOutputFrameCount())`
     * (DefaultAudioSink.java:1776-1778, media3 1.11.0). Its own chain reports only the
     * silence-skipping processor (:222-224), so frames dropped here would otherwise be invisible
     * and the sidecar's reported position would lag the audio permanently by the discarded
     * amount. RenderersFactory's chain adds this count, so nothing downstream - tail-remaining
     * arithmetic, logging, a future re-measurement - has to know about the correction.
     */
    private val discardedFrames = AtomicLong(0)

    /**
     * ⚠⚠ FRAMES THIS PROCESSOR HAS EMITTED TO THE CURRENT AUDIO OUTPUT, AND ITS
     * LIFETIME IS THE OUTPUT'S, NOT A CROSSFADE'S. Counted from the moment the output was
     * created or last flushed - which includes everything written while the player sat PAUSED
     * after prepare, because an ExoPlayer fills its AudioTrack then - and cleared ONLY in
     * [onFlush] and [onReset], the two points where the output itself restarts. Deliberately NOT
     * cleared by [clearRamps] or by anything in a crossfade's own lifecycle.
     *
     * ⚠️ WHAT IT IS FOR: the queued-ahead depth of the sink, which no public API
     * exposes. With this counter the controller computes, with no position counter involved,
     *     queuedMs = emittedMs / speed - (now - playoutStartAtMs)
     * i.e. the output we have handed down, minus the wall time that has elapsed since the mixer
     * started consuming it.
     * ⚠️ THE / speed IS NOT OPTIONAL, AND THE FIRST DRAFT OF THIS NOTE SAID IT
     * WAS. It claimed "both terms are output time, and Sonic sits AFTER this processor" - the
     * premise is right and the conclusion inverted. SonicAudioProcessor applying the rate change
     * after us is precisely why the frames counted here are PRE-speed, i.e. media time, while the
     * elapsed term is wall time. At 1x the two coincide, which is how a missing factor would have
     * shipped unnoticed. No discard term, though: dropped frames were never emitted.
     * ⚠️ A MID-STREAM FLUSH INVALIDATES THE PAIRING, not the counter: the counter
     * restarts at 0 while the controller's playoutStartAtMs still refers to the first playout.
     * The only flush that can reach the sidecar mid-pre-roll is a rebuffer, which demotes the
     * transition to a plain one, so the stale pairing is never read.
     */
    private val emittedFrames = AtomicLong(0)
    private val pendingToken = AtomicReference<Any?>(null)
    private val publishedToken = AtomicReference<Any?>(null)

    /**
     * ⚠⚠ [ADDED 2026-11-03] THE CROSSFADE BRIDGE'S "MAIN'S OLD AUDIO HAS STOPPED" SIGNAL. On the
     * hand-off seek, ExoPlayerImplInternal.seekToPeriodPosition (media3 1.11.1, :1838) pauses this
     * player's AudioTrack in stopRenderers() and then, in the same playback-thread message,
     * disableRenderers() reaches DefaultAudioSink.flush (:1588-1600), which flushes this processor
     * (resetSinkStateForFlush -> setupAudioProcessors -> pipeline flush, :855) BEFORE it releases
     * the output. So a flush observed after the switch means the departing track's queued audio is
     * already paused. Invoked on the PLAYBACK thread, on every flush (a hand-off produces two: the
     * seek's and the new stream's start), so the listener does its own filtering and must not block.
     * [CORRECTED 2026-11-04] "already paused" is true of the CALL, not of the mixer: the flush says
     * nothing about whether AudioFlinger has handled the pause, and when it had not, the release's flush
     * cut the track with no ramp (see CrossfadeController.activate). The signal now gates only the ramps.
     */
    @Volatile var onFlushed: (() -> Unit)? = null

    /** This player's current AudioTrack, for the crossfade hand-off; see [TrackGate]. */
    val trackGate = TrackGate()
    /**
     * ⚠⚠ VOLATILE, BECAUSE THE COEFFICIENT BUILD READS IT FROM THE OTHER THREAD.
     * [onConfigure] writes it on the PLAYBACK thread; [rebuildBands] reads it from there AND from
     * the app thread when the slider moves. AudioFormat is IMMUTABLE, so a single read into a
     * local - which is what every reader here does - is already a consistent snapshot of rate,
     * channel count and encoding together; there is no torn-field hazard. What was missing was
     * VISIBILITY: without volatile, a slider move racing a reconfigure could read a stale
     * reference and build a 44.1 kHz shelf for a 48 kHz stream, which would sit at the wrong
     * corner frequency until the next settings write. One keyword closes it.
     * ⚠️ SO THE RULE FOR READERS IS: READ IT ONCE INTO A LOCAL, NEVER TWICE.
     * [rebuildBands], [frameSizeBytes], [framesFor] and [emittedOutputMs] all do. Reading
     * `configuredFormat.sampleRate` and `configuredFormat.channelCount` as two separate
     * expressions would reintroduce exactly the inconsistency this note is about.
     */
    @Volatile private var configuredFormat = AudioProcessor.AudioFormat.NOT_SET

    // [REMOVED 2026-11-11] THE LUT AND LOUDNESS NORMALIZATION. A 65536-entry table used to map every input sample: the
    // track's Deezer GAIN + 4.5 dB, Pass A (2.5:1 compression above 0.4) and Pass B (the cubic limiter). Normalization was
    // forced off on Jun 9 (normalization_force_disabled_v1) with its row hidden, and from 2026-10-28 gain 0 with normalization
    // off returned the identity table (lut[i] = i - 32768), so the live table was always the identity. [queueInput] now reads
    // the sample directly, which gives the same float (see the note there). Pass B's curve survives only in [softLimit], for
    // the bass shelf. Not to be reintroduced by mistake: see "Known wrong history" in CLAUDE.md.
    /**
     * ⚠⚠ THE EQ STAGE, AND IT IS AN ARRAY OF BANDS RATHER THAN ONE BASS FILTER
     * BECAUSE THE NEXT BAND SHOULD BE A LINE OF CODE. Today it holds exactly one [LowShelf] -
     * bass boost - or nothing at all. Adding a second band later means: write another [EqBand]
     * (a peaking section is the same biquad with different coefficient formulas), append it in
     * [rebuildBands], give it a settings key, and add a slider. NOTHING IN [queueInput] CHANGES.
     * That is the whole reason the per-channel history lives inside each band and [EqBand.process]
     * takes a channel index, rather than this class owning one filter's state.
     *
     * ⚠⚠ SNAPSHOT ONCE PER BUFFER, NEVER PER FRAME. The audio thread reads this
     * AtomicReference at the top of [queueInput] into a local; the app thread publishes a whole
     * new array. So a settings change can never tear a coefficient set mid-buffer, and the hot
     * loop pays one array read per buffer plus an index loop with no iterator allocation.
     * ⚠️ EMPTY MEANS BIT-EXACT BYPASS, which is what slider 0 must be: the
     * inner loop skips the stage entirely, so a user with bass boost off gets byte-identical
     * output to a build without this code.
     */
    private val eqBands = AtomicReference<Array<EqBand>>(emptyArray())

    /**
     * Linear gain applied BEFORE the bands, to buy back part of what they add. See
     * [HEADROOM_FRACTION]; 1f whenever [eqBands] is empty.
     */
    private val eqPreGain = AtomicReference(1f)

    /**
     * Slider position the published bands were built for, so a no-op write rebuilds nothing.
     * Volatile because [rebuildBands] is reached from BOTH threads - the app thread on a
     * settings change and the playback thread from [onConfigure] - and a stale read there would
     * build the shelf for the wrong gain until the next write.
     */
    @Volatile private var bassBoostStrength = 0

    /**
     * ⚠⚠ BASS BOOST, 0-10, AND IT IS A SHELF IN OUR OWN CHAIN RATHER THAN THE
     * PLATFORM Equalizer. What it replaced, because the arithmetic is the whole argument: the old
     * implementation (upstream Echo's, unchanged by us) wrote a tilt into a session Equalizer AND
     * a makeup gain into a LoudnessEnhancer, `strength^(1/3) * 1600` millibels - so slider 1 gave
     * +1.5 dB of actual bass against +16.0 dB BROADBAND, and slider 10 gave +15 dB against
     * +34.5 dB. Measured on device 2026-10-21 with the system EQ off: both effects Enabled y, us
     * in sole control, and the audible result was "only louder". It was doing exactly what it
     * says; what it says is wrong.
     * ⚠⚠ AND IN OUR CHAIN, NOT THE SESSION, SO IT CANNOT FIGHT MusicFX. Two
     * clients on one session share ONE effect engine, control goes by priority, and the loser's
     * parameter writes fail silently - worse, a controlling client's setEnabled(false) disables
     * the engine for both. Ours ran at priority 0. Here the shelf is upstream of the AudioTrack
     * and the system EQ is downstream of it, so the two compose in series with nothing shared.
     * ⚠️ IT ALSO COVERS THE CROSSFADE TAIL, which the session version could not
     * be trusted to do: the sidecar had its own session for three builds (fixed 2026-10-21) and
     * the whole tail played with none of the session effects. A per-processor stage has no
     * session to get wrong - PlayerService mirrors the value to the sidecar's processor.
     *
     * Safe from any thread: all the arithmetic is closed form and the result is published
     * atomically. Called from the app thread on a settings change and once per sidecar creation.
     */
    fun setBassBoost(strength: Int) {
        val clamped = strength.coerceIn(0, BASS_BOOST_MAX)
        if (clamped == bassBoostStrength && eqBands.get().isNotEmpty() == (clamped > 0)) return
        bassBoostStrength = clamped
        rebuildBands()
    }

    /**
     * Builds the band array and the pre-gain for the current slider value and the configured
     * format. Called on a settings change and from [onConfigure], because the coefficients depend
     * on the sample rate - and on nothing else, which is why this is the only place they are
     * computed and why [queueInput] never does trigonometry.
     */
    private fun rebuildBands() {
        val fmt = configuredFormat
        val strength = bassBoostStrength
        val bands: Array<EqBand>
        val preGain: Float
        if (strength <= 0 || fmt.sampleRate <= 0 || fmt.channelCount <= 0) {
            bands = emptyArray()
            preGain = 1f
        } else {
            val shelfDb = strength * BASS_BOOST_DB_PER_STEP
            bands = arrayOf<EqBand>(
                LowShelf(
                    gainDb = shelfDb,
                    centreHz = BASS_SHELF_HZ,
                    slope = BASS_SHELF_SLOPE,
                    sampleRate = fmt.sampleRate,
                    channelCount = fmt.channelCount,
                )
            )
            preGain = 10.0.pow(-shelfDb * HEADROOM_FRACTION / 20.0).toFloat()
        }
        // ⚠⚠ PUBLISH ONLY IF THE FORMAT IS STILL THE ONE THESE WERE COMPUTED
        // FROM, AND IDENTITY IS THE RIGHT TEST. This runs on BOTH threads - the app thread when
        // the slider moves, the playback thread from [onConfigure] - and "onConfigure rebuilds
        // afterwards so it converges" misses one interleaving: the app thread reads the OLD
        // format and computes; onConfigure publishes the new format and its own coefficients;
        // then the app thread publishes LAST and the stream runs a 44.1 kHz shelf at 48 kHz,
        // until the next settings write. Discarding is correct rather than merely safe, because
        // onConfigure's rebuild has already published the right answer for the new format.
        // ⚠️ BOTH PATHS ARE GUARDED, INCLUDING THE EMPTY ONE. An app-thread pass
        // that read a NOT_SET format would otherwise publish `emptyArray()` over good bands and
        // silently turn the shelf off until the next write - the same race with the opposite
        // symptom, which is why the publish is one block at the end rather than an early return.
        if (configuredFormat !== fmt) return
        eqBands.set(bands)
        eqPreGain.set(preGain)
    }

    /** Media milliseconds -> frames at the configured rate. 0 when the format cannot be ramped. */
    private fun framesFor(mediaMs: Long): Long {
        val fmt = configuredFormat
        if (fmt.sampleRate <= 0 || fmt.encoding != C.ENCODING_PCM_16BIT) return 0L
        return fmt.sampleRate.toLong() * mediaMs / 1000L
    }

    /**
     * Outgoing leg (the sidecar's tail). Armed while the sidecar is still silent, so there is no
     * gain step when it starts playing.
     */
    fun startFadeOut(mediaMs: Long, partnerInMs: Long = 0L, partnerDelayMs: Long = 0L) {
        val frames = framesFor(mediaMs)
        setPartner(incoming = false, partnerMs = partnerInMs, partnerDelayMs = partnerDelayMs)
        rampTotalFrames.set(frames)
        fadeInFramesRemaining.set(0)
        fadeOutDone.set(false)
        fadeOutFramesRemaining.set(frames)
    }

    /** [2026-11-10] The other leg's curve for the overlap trim (see [rampElapsedFrames]); 0 ms = no trim. */
    private fun setPartner(incoming: Boolean, partnerMs: Long, partnerDelayMs: Long) {
        rampIsIncoming.set(incoming)
        partnerTotalFrames.set(framesFor(partnerMs))
        partnerDelayFrames.set(framesFor(partnerDelayMs))
        rampElapsedFrames.set(0)
    }

    private fun clearLookAhead() {
        fadeInStartFrame.set(0)
        partnerTotalFrames.set(0)
        partnerDelayFrames.set(0)
        rampElapsedFrames.set(0)
    }

    /**
     * Incoming leg (the main player after the hand-off seek). Clears the mute set by
     * [armIncoming]: the first audible sample is therefore the first sample of the ramp, at gain 0.
     */
    fun startFadeIn(mediaMs: Long, onsetMs: Long = 0L, partnerOutMs: Long = 0L) {
        val frames = framesFor(mediaMs)
        setPartner(incoming = true, partnerMs = partnerOutMs, partnerDelayMs = 0L)
        fadeInStartFrame.set(framesFor(onsetMs))
        rampTotalFrames.set(frames)
        fadeOutFramesRemaining.set(0)
        fadeOutDone.set(false)
        fadeInFramesRemaining.set(frames)
        muted = false
    }

    /**
     * Silence this leg and stage [token]; survives the flush the hand-off seek causes.
     *
     * ⚠⚠ [MOVED HERE 2026-10-20] THIS IS WHERE THE OFFLOAD DEPENDENCY LIVES NOW. It
     * used to be stated on armPreRoll, which muted the SIDECAR by zero-filling here; the sidecar
     * is muted by its PLAYER VOLUME instead since 2026-10-20 (see CrossfadeController.startPreRoll
     * for why), so that function is gone and with it the claim that offload would make the
     * pre-roll audible. The dependency itself is unchanged and if anything broader: audio offload
     * bypasses the processor chain entirely, so under offload this mute, BOTH ramps and the
     * pre-roll discard would all be skipped - the crossfade would not merely sound wrong, it
     * would not happen. Offload is disabled unconditionally (7c6b37d1, 2026-07-13); that is a
     * precondition of this feature, not a coincidence.
     */
    fun armIncoming(token: Any) {
        pendingToken.set(token)
        publishedToken.set(null)
        fadeInFramesRemaining.set(0)
        fadeOutFramesRemaining.set(0)
        fadeOutDone.set(false)
        clearLookAhead()
        muted = true
    }

    /**
     * Non-null once this processor has rendered its first frame since [armIncoming]. The controller
     * polls it rather than being called back, so every decision that follows stays on the main
     * thread. Identity-compared against the controller's own token.
     */
    fun overlapToken(): Any? = publishedToken.get()

    /**
     * Drops [mediaMs] of the outgoing leg, in whole frames, while it is still INAUDIBLE - which
     * since 2026-10-20 means at player volume 0 rather than zero-filled here; see
     * [discardFramesRemaining]. Returns the frame count actually scheduled - 0 when the
     * configured format cannot be ramped, which is the same condition under which no fade would
     * be applied either.
     */
    fun discard(mediaMs: Long): Long {
        val frames = framesFor(mediaMs)
        discardFramesRemaining.set(frames)
        return frames
    }

    /** The second trim, to the frame (2026-11-04): CrossfadeController's timestamps can be read finer than a millisecond. */
    fun discardUs(mediaUs: Long) {
        val fmt = configuredFormat
        if (fmt.sampleRate <= 0 || fmt.encoding != C.ENCODING_PCM_16BIT) return
        discardFramesRemaining.set(fmt.sampleRate.toLong() * mediaUs / 1_000_000L)
    }

    /** Cumulative dropped frames, for RenderersFactory's chain. See [discardedFrames]. */
    fun discardedFrameCount(): Long = discardedFrames.get()

    // (frames emitted when a drop happened, cumulative dropped after it), for discardedFramesAt. A drop is one entry per
    // buffer it touches, so a handful per crossfade; cleared with the output.
    private val discardMarks = ArrayList<Pair<Long, Long>>()

    /**
     * Frames dropped BEFORE output frame [frame] (2026-11-04), which is not the same as dropped so far. A drop happens upstream of
     * ~500 ms of queue, so while it is still queued the frame now playing precedes it; counting it anyway would misread the tail by
     * the whole trim, and the second trim runs about when the first one reaches the mixer.
     * [CORRECTED 2026-11-05] Right for where the tail is NOW, so it stays for tsSwitchMs. It was wrong for what the second trim has left
     * to discard, which needs every drop already made: [discardedFrameCount]. "Misread" was this note's mistake, see
     * CrossfadeController.secondTrim.
     */
    fun discardedFramesAt(frame: Long): Long = synchronized(discardMarks) {
        discardMarks.lastOrNull { it.first <= frame }?.second ?: 0L
    }

    /**
     * The tail's level and where it went quiet, for the CROSSFADE-FEEL line (2026-11-05). Written once per buffer on the audio thread
     * from [queueInput], before fadeGain so it reads the song and not our ramp; read on the app looper. It starts at the switch, so
     * the audio already queued by then is not in it, and a frame here is heard about one queue-depth after the switch.
     */
    private class TailMeter {
        @Volatile var frames = 0L
        @Volatile var squares = 0.0
        @Volatile var loudFrames = 0L
        @Volatile var loudSquares = 0.0
    }

    @Volatile private var tailMeter: TailMeter? = null

    /** A new meter rather than a reset, so the audio thread never publishes old totals into it. Dropped by [clearRamps]. */
    fun startTailMeter() {
        tailMeter = TailMeter()
    }

    /** Media ms from the meter's start to the last frame above [TAIL_SILENCE]; 0 if none, null with no meter. */
    fun tailAudibleMs(): Long? {
        val meter = tailMeter ?: return null
        val rate = configuredFormat.sampleRate
        return if (rate <= 0) 0L else meter.loudFrames * 1000L / rate
    }

    /** Average level in dBFS up to that frame, or over all of it when nothing passed; `-` with nothing measured. */
    fun tailDb(): String {
        val meter = tailMeter ?: return "-"
        val loud = meter.loudFrames
        val frames = if (loud > 0L) loud else meter.frames
        val squares = if (loud > 0L) meter.loudSquares else meter.squares
        val channels = configuredFormat.channelCount
        if (frames == 0L || channels <= 0) return "-"
        if (squares <= 0.0) return "-inf"
        return (10.0 * log10(squares / (frames * channels))).roundToInt().toString()
    }

    /** Frames emitted to the current output, for TrackGate. See [emittedFrames]. */
    fun emittedFrameCount(): Long = emittedFrames.get()

    /**
     * Emitted output time in MEDIA milliseconds at the configured rate, or -1 when the format is
     * not one we count - the same condition under which no ramp and no discard would apply
     * either. See [emittedFrames] for the lifetime and for why a caller comparing this with wall
     * time has to divide by the playback speed first.
     */
    fun emittedOutputMs(): Long {
        val fmt = configuredFormat
        if (fmt.sampleRate <= 0 || fmt.encoding != C.ENCODING_PCM_16BIT) return -1L
        return emittedFrames.get() * 1000L / fmt.sampleRate
    }

    /**
     * Adds [bytes] of emitted output to [emittedFrames]. Called on every path that writes to the
     * output buffer - muted, passthrough and the PCM ramp loop - so the count is the SINK's view
     * of what we produced, which is what the queue depth is measured against. A format with no
     * frame size contributes nothing rather than a wrong number.
     */
    private fun countEmitted(bytes: Int) {
        val frameSize = frameSizeBytes()
        if (frameSize > 0 && bytes > 0) emittedFrames.addAndGet((bytes / frameSize).toLong())
    }

    /** Bytes per PCM frame for the configured format; 0 when it is not a format we ramp. */
    fun frameSizeBytes(): Int {
        val fmt = configuredFormat
        if (fmt.encoding != C.ENCODING_PCM_16BIT || fmt.sampleRate <= 0 || fmt.channelCount <= 0)
            return 0
        return Util.getPcmFrameSize(fmt.encoding, fmt.channelCount)
    }

    /**
     * Full reset: both counters, the mute and the token. Called on every abort and completion, so
     * a leg can never be left silent or mid-ramp. MUST be reachable without a flush — a hand-off
     * that is aborted before the seek never produces one.
     */
    fun clearRamps() {
        fadeInFramesRemaining.set(0)
        fadeOutFramesRemaining.set(0)
        fadeOutDone.set(false)
        rampTotalFrames.set(0)
        clearLookAhead()
        // The PENDING discard goes, because it is gain-side state that must not survive an
        // abort. The CUMULATIVE counts deliberately do NOT: they are what the sink has
        // been told about the stream it may still be draining, so its lifetime is the stream's -
        // bounded by onFlush and onReset - and zeroing it here would step the reported position.
        discardFramesRemaining.set(0)
        pendingToken.set(null)
        publishedToken.set(null)
        muted = false
        tailMeter = null
    }

    /**
     * ⚠⚠ WHY A 16-BIT-ONLY PROCESSOR IS CORRECT, AND WHAT WOULD MAKE IT
     * WRONG. Everything here assumes PCM 16-bit: the 65,536-entry LUT is indexed by the sample
     * itself, and [framesFor], [frameSizeBytes] and [emittedOutputMs] all refuse any other
     * encoding. That holds because the SINK CONVERTS BEFORE US. DefaultAudioSink.configure
     * (media3 1.11.0, :754-761) builds the pipeline as
     *     pipelineProcessors.addAll(availableAudioProcessors)
     *     if (shouldUseFloatOutput(encoding)) { add(toFloatPcmAudioProcessor) }
     *     else { add(toInt16PcmAudioProcessor); add(audioProcessorChain.getAudioProcessors()) }
     * - so with float output off a 24-bit or 32-bit decode reaches us already converted to
     * 16-bit. Deezer's HiFi FLAC (24-bit, up to 192 kHz) is the format that makes this worth
     * writing down: MediaCodecAudioRenderer takes the decoder's own KEY_PCM_ENCODING when it
     * declares one (:650-660), so the SINK may well be handed 24-bit packed - but this chain is
     * not.
     * ⚠️ THE RATE IS NOT CONVERTED, ONLY THE DEPTH. Nothing in that list
     * resamples, so a 96/176.4/192 kHz stream arrives at its own rate and every per-frame cost
     * here runs up to ~4.35x more often than at 44.1 kHz. That is a CPU fact, not a correctness
     * one: the LUT is indexed by sample value, and every ms<->frame conversion in this file goes
     * through the configured rate.
     * ⚠⚠ TWO SETTINGS WOULD REMOVE THIS PROCESSOR FROM THE PIPELINE
     * ENTIRELY, SILENTLY, AND ONLY ON HIGH-RES TRACKS. Float output is one (the branch above
     * adds the chain's processors ONLY in the else arm, and shouldUseFloatOutput is
     * `enableFloatOutput && isEncodingHighResolutionPcm`, :1733-1735); audio offload is the
     * other, since it bypasses the processor chain altogether. Both are off - see the note at
     * RenderersFactory.buildAudioSink - and both must stay off, because losing this processor
     * loses the crossfade's hand-off mute and both ramps with no error anywhere.
     * ⚠️ [PcmFormatProbe] IS THE CANARY FOR EXACTLY THAT. If the premise
     * ever fails, queueInput's pass-through would hand the audio through ungained and unramped
     * and nothing would say so; the probe reports it once per process instead.
     * [CORRECTED 2026-11-13] Not to be read as "24-bit or hi-res tracks pass through unramped", which is how it was taken: the
     * pass-through is a branch for a premise failure, not a live path. While float output and offload are both off
     * (RenderersFactory, unchanged), the sink converts every decode to 16-bit before this chain, so this processor ALWAYS receives
     * 16-bit and the crossfade's mute, ramps and trim apply to 24-bit FLAC as to any other track (read from media3 1.11.0's
     * DefaultAudioSink.configure, above). What is lost is the depth: hi-res plays as 16-bit.
     */
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        configuredFormat = inputAudioFormat
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) PcmFormatProbe.onUnexpectedFormat(
            inputAudioFormat.encoding, inputAudioFormat.sampleRate, inputAudioFormat.channelCount
        )
        // The biquad coefficients are a function of the SAMPLE RATE, so a format change
        // invalidates them - a 44.1k shelf run at 48k sits at the wrong frequency. This is the
        // only other place [rebuildBands] is called, and between the two of them the audio thread
        // never computes a coefficient.
        rebuildBands()
        return inputAudioFormat
    }

    /**
     * ⚠⚠ THE ONE PLACE THAT CLEARS A RAMP WITHOUT CLEARING THE MUTE, AND THE
     * ASYMMETRY IS THE POINT. A seek flushes the sink, so this is where the Jul 1-3 fade-out bleed
     * would come back: the departing track's half-finished fade-out counter would otherwise survive
     * into the next track and multiply its opening. onQueueEndOfStream used to be the guard, but a
     * crossfade hand-off is a SEEK, not an end of stream — it never fires.
     * ⚠️ muted / pendingToken ARE DELIBERATELY KEPT: they were set BEFORE the seek that
     * caused this flush, and clearing them here would unmute the incoming leg mid-seek (the
     * departing track audible twice) and lose the handshake. See the field note.
     * Overrides the StreamMetadata form, not the deprecated no-arg one.
     */
    override fun onFlush(streamMetadata: StreamMetadata) {
        fadeInFramesRemaining.set(0)
        fadeOutFramesRemaining.set(0)
        fadeOutDone.set(false)
        rampTotalFrames.set(0)
        clearLookAhead()
        // ⚠️ THE DISCARD STATE GOES, INCLUDING THE CUMULATIVE COUNT, AND THE MUTE
        // STILL DOES NOT. A flush restarts the stream, so frames dropped from the old one are no
        // longer part of what the sink is measuring - leaving the count behind would offset the
        // new stream's position by an amount that belongs to the previous one.
        clearPreRollState()
        // ⚠⚠ [ADDED 2026-11-03] THE PUBLISHED TOKEN GOES; muted AND pendingToken STILL STAY. The field
        // note's design is "first post-flush queueInput publishes the token", but armIncoming runs on
        // the main thread BEFORE the seek, so a departing-track buffer rendered in between published
        // it early, and the flush kept it. The controller then armed the ramps on its next tick; when
        // that tick landed before this flush, the counters cleared above zeroed the fade-in and the
        // incoming track entered at FULL gain (CrossfadeController.tickActive, rampsEarly). Clearing
        // only the published copy lets the next muted frame - necessarily post-flush - republish it,
        // and the stream-start flush (DefaultAudioSink.handleBuffer, before the first new buffer)
        // clears it again before any new-track frame is processed.
        publishedToken.set(null)
        onFlushed?.invoke()
    }

    override fun onReset() {
        fadeOutDone.set(false)
        clearPreRollState()
    }

    /**
     * Everything whose lifetime is the AUDIO OUTPUT's rather than a crossfade's. Called from
     * [onFlush] and [onReset] only - see [emittedFrames] and [discardedFrames] for why each of
     * these must survive a crossfade ending but not an output restart.
     */
    private fun clearPreRollState() {
        discardFramesRemaining.set(0)
        discardedFrames.set(0)
        emittedFrames.set(0)
        synchronized(discardMarks) { discardMarks.clear() }
        // ⚠️ THE FILTER HISTORY GOES WITH THE STREAM, NOT WITH THE CROSSFADE. A
        // biquad carries two samples of state per channel; after a flush the next buffer is
        // discontinuous with it, and feeding stale history into a new stream is a transient -
        // audible as a click or a thump on the first frames. The COEFFICIENTS stay (they depend
        // only on rate and gain), which is why this resets the bands rather than rebuilding them.
        val bands = eqBands.get()
        for (i in bands.indices) bands[i].reset()
    }

    /**
     * Consumes up to [discardFramesRemaining] whole frames from the front of [inputBuffer] and
     * reports them to [discardedFrames]. Nothing happens for a format with no frame size, which
     * is the same condition under which no ramp would apply either - see [frameSizeBytes].
     * Audio thread only.
     */
    private fun applyPendingDiscard(inputBuffer: ByteBuffer) {
        val wanted = discardFramesRemaining.get()
        if (wanted <= 0L) return
        val frameSize = frameSizeBytes()
        if (frameSize <= 0) return
        val dropped = min(wanted, (inputBuffer.remaining() / frameSize).toLong())
        if (dropped <= 0L) return
        discardFramesRemaining.addAndGet(-dropped)
        val total = discardedFrames.addAndGet(dropped)
        synchronized(discardMarks) { discardMarks += emittedFrames.get() to total }
        inputBuffer.position(inputBuffer.position() + (dropped * frameSize).toInt())
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return

        // ⚠⚠ MUTED MEANS SILENCE AND A PUBLISHED TOKEN, IN THAT ORDER. This is the
        // incoming leg between the hand-off and the controller arming its ramp: the buffer is
        // consumed and replaced with zeros, and the FIRST such buffer publishes the token so the
        // controller knows a real frame has been rendered. It keeps outputting silence until
        // startFadeIn clears the mute, which is what lets the controller decide the ramp length on
        // the main thread (it needs the sidecar position; the audio thread cannot read it). The
        // silence lasts the seek plus at most one 200 ms tick, and the tail covers all of it.
        // ⚠⚠ THE DISCARD IS TAKEN BEFORE ANY OUTPUT IS SIZED, AND THAT IS WHY IT
        // IS HERE RATHER THAN IN EITHER BRANCH BELOW. It consumes frames from the INPUT, so
        // every path after it - mute, passthrough, the ramp loop - sizes its output from the
        // shortened buffer and reports exactly what it emitted, with no per-path arithmetic. An
        // empty remainder is fine on all three: replaceOutputBuffer(0) and flip() are valid.
        applyPendingDiscard(inputBuffer)

        if (muted) {
            pendingToken.get()?.let { publishedToken.compareAndSet(null, it) }
            val keep = inputBuffer.remaining()
            countEmitted(keep)
            val silence = replaceOutputBuffer(keep)
            while (silence.hasRemaining()) silence.put(0)
            inputBuffer.position(inputBuffer.limit())
            silence.flip()
            return
        }

        val fmt = configuredFormat
        val output = replaceOutputBuffer(inputBuffer.remaining())

        if (fmt.encoding != C.ENCODING_PCM_16BIT || fmt.sampleRate <= 0) {
            countEmitted(inputBuffer.remaining())
            output.put(inputBuffer)
            output.flip()
            return
        }

        // Snapshotted once per buffer - see [eqBands]. A settings change between buffers takes
        // effect on the next one; it can never tear a coefficient set inside this loop.
        val bands = eqBands.get()
        val preGain = eqPreGain.get()
        var currentFi = fadeInFramesRemaining.get()
        var currentFo = fadeOutFramesRemaining.get()
        val total = rampTotalFrames.get()
        val channelCount = fmt.channelCount
        val applyFade = total > 0
        val wasDone = fadeOutDone.get()
        var holdSilent = wasDone
        val meter = tailMeter
        var meterFrames = meter?.frames ?: 0L
        var meterSquares = meter?.squares ?: 0.0
        var meterLoudFrames = meter?.loudFrames ?: 0L
        var meterLoudSquares = meter?.loudSquares ?: 0.0
        // [2026-11-10] The start look-ahead's hold and trim; see the field note at [fadeInStartFrame].
        var elapsed = rampElapsedFrames.get()
        var streamFrame = emittedFrames.get()
        val startFrame = fadeInStartFrame.get()
        val partnerTotal = partnerTotalFrames.get()
        val partnerDelay = partnerDelayFrames.get()
        val incoming = rampIsIncoming.get()

        while (inputBuffer.remaining() >= channelCount * 2) {
            // Cosine² fade envelope — computed once per frame, applied to all channels
            var fadeGain: Float
            if (holdSilent) {
                fadeGain = 0f
            } else if (applyFade && currentFi > 0 && streamFrame < startFrame) {
                // [2026-11-10] Before the next song's onset: held at 0, and its ramp has not started.
                fadeGain = 0f
            } else if (applyFade && (currentFi > 0 || currentFo > 0 || (incoming && elapsed < partnerTotal))) {
                // Equal power: progress p runs 0 -> 1 across the ramp, in = sin(p*pi/2),
                // out = cos(p*pi/2), so in² + out² = 1. NOT squared — squaring is the single-leg
                // Track Fade curve and puts a 3 dB hole in the middle of an overlap.
                val fadeInGain = if (currentFi > 0) {
                    val p = 1f - currentFi.toFloat() / total
                    sin(p * (PI / 2).toFloat())
                } else 1f
                val fadeOutGain = if (currentFo > 0) {
                    val p = 1f - currentFo.toFloat() / total
                    cos(p * (PI / 2).toFloat())
                } else 1f
                if (currentFi > 0) currentFi--
                if (currentFo > 0) {
                    currentFo--
                    if (currentFo == 0L) holdSilent = true
                }
                val trim = overlapTrim(
                    if (incoming) fadeInGain else fadeOutGain, incoming, elapsed, partnerTotal, partnerDelay
                )
                fadeGain = fadeInGain * fadeOutGain * OVERLAP_PEDESTAL * trim
            } else {
                fadeGain = 1f
            }
            if (applyFade) elapsed++
            streamFrame++
            var frameLoud = false
            for (ch in 0 until channelCount) {
                // [2026-11-11] The sample itself, where the LUT lookup was. Bit-identical: the live table was the identity
                // (lut[raw + 32768] = raw as a Short), and Short / Float and Int / Float are both toFloat() / 32768f, exact for
                // every 16-bit value. See the removal note above [eqBands].
                var processed = inputBuffer.short / 32768f
                // ⚠⚠ THE EQ STAGE: PRE-GAIN, BANDS, THEN THE LIMITER - AND THE
                // RAMP STAYS OUTERMOST. Order is load-bearing in three directions:
                //   - AFTER the LUT, so the shelf works on a normalised sample and the per-track
                //     gain is not shelved along with the music;
                //   - BEFORE [softLimit], so the peaks a +12 dB shelf adds are limited rather
                //     than clipped at the int conversion below;
                //   - BEFORE fadeGain, so cos/sin remain the last thing applied and the overlap
                //     still sums to unity. Ramping before the bands would feed them an
                //     attenuated signal and make each leg come out relatively hotter - the same
                //     argument the note on [OVERLAP_PEDESTAL] makes about the LUT.
                // ⚠️ [CORRECTED 2026-10-21] THE CHAIN WAS RECORDED AS
                // "normalize -> fade -> limit" AND THE LIMITER IS NOT WHERE THAT SAYS. Both the
                // normalisation and the original limiter are INSIDE the LUT (one table lookup,
                // Pass A and Pass B of generateDualStageLUT), i.e. UPSTREAM of the fade, not
                // downstream of it. So the existing limiter could not have caught the shelf's
                // peaks - it never sees them - and [softLimit] below is what gives the EQ stage
                // the limiter the design assumed it already had.
                // [CORRECTED 2026-11-11] There is no LUT any more (removed with normalization), so "AFTER the LUT" and the
                // per-track gain above describe the old chain. The order that remains is sample, pre-gain, bands, [softLimit],
                // then fadeGain, and the reasons for the last two still hold.
                if (bands.isNotEmpty()) {
                    processed *= preGain
                    for (b in bands.indices) processed = bands[b].process(processed, ch)
                    processed = softLimit(processed)
                }
                if (meter != null) {
                    meterSquares += (processed * processed).toDouble()
                    if (abs(processed) >= TAIL_SILENCE) frameLoud = true
                }
                output.putShort(
                    (processed * fadeGain * 32768f).toInt()
                        .coerceIn(-32768, 32767).toShort()
                )
            }
            if (meter != null) {
                meterFrames++
                if (frameLoud) {
                    meterLoudFrames = meterFrames
                    meterLoudSquares = meterSquares
                }
            }
        }

        if (meter != null) {
            meter.frames = meterFrames
            meter.squares = meterSquares
            meter.loudFrames = meterLoudFrames
            meter.loudSquares = meterLoudSquares
        }
        fadeInFramesRemaining.set(currentFi)
        fadeOutFramesRemaining.set(currentFo)
        rampElapsedFrames.set(elapsed)
        if (holdSilent && !wasDone) fadeOutDone.set(true)
        countEmitted(output.position())
        output.flip()
    }

    /**
     * [2026-11-10] The overlap trim g = min(1, 1/sqrt(own^2 + partner^2)), where partner is the other leg's gain [elapsed]
     * frames into the shared ramp: for the incoming leg the outgoing's cos over [partnerTotal], then 0; for the outgoing leg the
     * incoming's 0 for [partnerDelay], sin over [partnerTotal], then 1. 1 when there is no partner. See [fadeInStartFrame].
     */
    private fun overlapTrim(own: Float, incoming: Boolean, elapsed: Long, partnerTotal: Long, partnerDelay: Long): Float {
        if (partnerTotal <= 0L) return 1f
        val partner = if (incoming) {
            if (elapsed >= partnerTotal) 0f else cos(elapsed.toFloat() / partnerTotal * (PI / 2).toFloat())
        } else {
            val t = elapsed - partnerDelay
            when {
                t <= 0L -> 0f
                t >= partnerTotal -> 1f
                else -> sin(t.toFloat() / partnerTotal * (PI / 2).toFloat())
            }
        }
        val power = own * own + partner * partner
        return if (power <= 1f) 1f else 1f / sqrt(power)
    }

    /**
     * ⚠⚠ ONE SECTION OF THE EQ STAGE. The contract is deliberately tiny: take a
     * sample and its channel, return the filtered sample. State lives in the BAND, keyed by
     * channel, because that is what lets [eqBands] grow without [queueInput] changing - and what
     * stops two bands sharing one history by accident.
     * ⚠️ [process] IS ON THE AUDIO THREAD, PER FRAME PER CHANNEL. No allocation,
     * no trigonometry, no branching beyond what the maths needs. Everything expensive happens in
     * the constructor, which runs on the app thread from [rebuildBands].
     */
    private interface EqBand {
        fun process(sample: Float, channel: Int): Float

        /** Drops the filter history; coefficients survive. Called on a flush, never per buffer. */
        fun reset()
    }

    /**
     * ⚠⚠ RBJ LOW SHELF, ONE BIQUAD SECTION, AND IT IS BAND 0 OF THE EQ STAGE
     * RATHER THAN A BASS-BOOST SPECIAL CASE. Coefficients are the Audio EQ Cookbook's lowShelf,
     * normalised by a0 so [process] is four multiplies and four adds. A SECOND BAND IS THE SAME
     * CLASS WITH DIFFERENT COEFFICIENT FORMULAS - a peaking section needs only b0/b1/b2/a1/a2 to
     * be computed from gain, Q and centre instead; nothing else about this file changes.
     *
     * ⚠️ ONE SECTION, S = 0.9, SO THERE IS NO RIPPLE AND NO RESONANT PEAK. A
     * shelf slope above 1 needs cascaded sections to stay monotonic; 0.9 keeps a single section
     * well-behaved, which matters because the thing being boosted is the part of the spectrum a
     * phone speaker and a cheap pair of earbuds already struggle with.
     * ⚠️ DOUBLE ACCUMULATORS, FLOAT INTERFACE. A biquad at 120 Hz against a
     * 44.1 kHz rate has coefficients close to 1 and is numerically delicate in float; the state
     * and the accumulate are Double, and only the result is narrowed. The cost is one conversion
     * per sample, which is far cheaper than a filter that drifts.
     */
    private class LowShelf(
        gainDb: Float,
        centreHz: Float,
        slope: Float,
        sampleRate: Int,
        channelCount: Int,
    ) : EqBand {
        private val b0: Double
        private val b1: Double
        private val b2: Double
        private val a1: Double
        private val a2: Double

        // Flat per-channel history: [x1, x2, y1, y2] per channel, so one allocation and no
        // per-sample indirection. Audio thread only, after the constructor publishes the array.
        private val state = DoubleArray(channelCount.coerceAtLeast(1) * 4)

        init {
            val a = 10.0.pow(gainDb / 40.0)
            val w0 = 2.0 * PI * centreHz / sampleRate
            val cosW0 = cos(w0)
            val alpha = sin(w0) / 2.0 * sqrt((a + 1 / a) * (1.0 / slope - 1.0) + 2.0)
            val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha
            val a0 = (a + 1) + (a - 1) * cosW0 + twoSqrtAAlpha
            b0 = a * ((a + 1) - (a - 1) * cosW0 + twoSqrtAAlpha) / a0
            b1 = 2 * a * ((a - 1) - (a + 1) * cosW0) / a0
            b2 = a * ((a + 1) - (a - 1) * cosW0 - twoSqrtAAlpha) / a0
            a1 = -2 * ((a - 1) + (a + 1) * cosW0) / a0
            a2 = ((a + 1) + (a - 1) * cosW0 - twoSqrtAAlpha) / a0
        }

        override fun process(sample: Float, channel: Int): Float {
            val i = channel * 4
            if (i + 3 >= state.size) return sample
            val x0 = sample.toDouble()
            val y0 = b0 * x0 + b1 * state[i] + b2 * state[i + 1] -
                a1 * state[i + 2] - a2 * state[i + 3]
            state[i + 1] = state[i]
            state[i] = x0
            state[i + 3] = state[i + 2]
            state[i + 2] = y0
            return y0.toFloat()
        }

        override fun reset() {
            state.fill(0.0)
        }
    }

    /**
     * ⚠⚠ THE SAME CUBIC generateDualStageLUT's PASS B BAKES INTO THE TABLE,
     * EVALUATED PER SAMPLE FOR THE EQ STAGE. It exists because the table's limiter is UPSTREAM of
     * the bands - the LUT is one lookup that normalises and limits together - so it cannot see
     * what a +12 dB shelf adds. Without this the shelf's peaks would hit the int conversion and
     * hard-clip, which is the one artefact a bass boost must not produce.
     * ⚠️ KEEP THE TWO IN STEP. Written out with multiplies rather than pow()
     * because this runs per frame per channel, but it is the SAME curve: identity to 0.5, cubic
     * to 1.25, flat at 1.0 after. If the table's curve changes, change this.
     * ⚠️ ONLY REACHED WHEN BANDS ARE ACTIVE, and the magnitude test means a
     * sample under half scale costs one comparison. Slider 0 never reaches it at all.
     * ⚠️ SINCE 2026-10-28 THE TABLE CARRIES PASS B ONLY WHEN A GAIN IS APPLIED (or
     * normalization is on); at 0 dB it is the passthrough table, so with bass boost on this is
     * the only limiter in the chain. "Keep the two in step" still holds for the curve.
     * [CORRECTED 2026-11-11] The table and generateDualStageLUT are gone (see the removal note above [eqBands]), so this
     * is the only copy of the Pass B curve and there is nothing left to keep in step. The curve is unchanged.
     */
    private fun softLimit(sample: Float): Float {
        val x = abs(sample)
        if (x <= 0.5f) return sample
        val sign = if (sample >= 0f) 1f else -1f
        if (x >= 1.25f) return sign
        val out = (-16f / 27f) * x * x * x + (8f / 9f) * x * x + (5f / 9f) * x + (2f / 27f)
        return sign * out
    }

    private companion object {
        /** Slider range, mirroring the Audio FX sheet's valueTo. */
        const val BASS_BOOST_MAX = 10

        /**
         * Shelf gain per slider step, so 10 is +12 dB of bass. Linear in dB because the ear is:
         * every step is the same perceived increment, which the old cube-root makeup curve was
         * not - it spent 46% of its range on step 1.
         */
        const val BASS_BOOST_DB_PER_STEP = 1.2f

        /**
         * ⚠⚠ HOW MUCH OF THE SHELF'S GAIN IS BOUGHT BACK BEFORE IT, AND THE
         * ONE NUMBER TO TUNE BY EAR. 0.5 means -0.6 dB of pre-attenuation per step: at slider 10,
         * +12 dB of shelf against -6 dB in, so the bass rises 6 dB net and everything else drops
         * 6 dB. DECIDED by the user, 2026-10-21, over full compensation (-12 dB, no residual
         * peaks but audibly quieter) and none (the old behaviour, which clipped).
         * ⚠️ THE RESIDUAL IS WHAT [softLimit] IS FOR. Half compensation leaves
         * up to 6 dB of peak over full scale on bass-heavy material; the limiter rounds those off
         * instead of the int conversion clipping them. If a listening pass finds the shelf grainy
         * at 10, RAISE THIS toward 1.0 - do not touch the curve or the slope.
         */
        const val HEADROOM_FRACTION = 0.5f

        /**
         * Shelf corner and slope. 120 Hz sits below almost all vocal fundamentals and above the
         * rumble a phone speaker cannot reproduce anyway, so the boost is felt rather than heard
         * as muddiness. S below 1 keeps the single section monotonic - see [LowShelf].
         */
        const val BASS_SHELF_HZ = 120f
        const val BASS_SHELF_SLOPE = 0.9f

        // ⚠⚠ HEADROOM FOR THE SUMMED OVERLAP. 1.0 = none, which is what the
        // reference ships and what we ship. The numbers behind the choice, measured from
        // generateDualStageLUT above rather than estimated:
        //   Pass B (the cubic) is UNCONDITIONAL and maps a full-scale input to f(1.0) = 25/27 =
        //     0.926. Pass A (2.5:1 above 0.4) runs only when normalization is on, and would take
        //     it to 0.577 — but normalization is force-disabled (PlayerService writes
        //     normalization_force_disabled_v1) and its row is hidden in the FX sheet, so 0.926 is
        //     the live per-leg cap.
        //   Equal-power legs peak together at 1/sqrt(2) each, so the worst-case sum is
        //     0.926 * sqrt(2) = 1.31 — 2.34 dB over full scale. That requires the two legs to be
        //     CORRELATED and both at full scale at the same instant; for uncorrelated music the
        //     summed RMS is flat at 0.926 and only occasional peaks exceed 1.0.
        //   0.764 (-2.34 dB) is the value that makes the sum <= 1.0 everywhere. If a listening
        //     pass finds grit at the crossover, change this one constant — do not touch the curve.
        // ⚠️ THE RAMP IS APPLIED AFTER THE LUT (see the output stage below) AND MUST
        // STAY THERE. Ramping before the limiter would feed it an attenuated signal, it would
        // compress less, and each leg would come out relatively HOTTER — the opposite of the
        // intent. The limiter is also per-leg and pre-mix, so it cannot see the sum at all; that is
        // precisely why this constant exists rather than relying on it.
        // ⚠⚠ [CORRECTED 2026-10-28] THE PER-LEG CAP IS 1.0, NOT 0.926, AND THE TAIL WAS NOT 0.926
        // EITHER. Two things were wrong above:
        //   - From 2026-10-15 to 2026-10-28 the crossfade TAIL ran PASS A: the sidecar processor
        //     never receives setTrackGain, so it kept the init{} table, built with
        //     generateDualStageLUT's isNormalizationEnabled default of TRUE. Its full-scale peak was
        //     0.577 (-4.8 dB) against the main leg's 0.926 - "runs only when normalization is on" was
        //     true of the main leg only. The Oct 21-22 by-ear sign-off at slider 10 (speaker,
        //     headphones, an AA drive) was made with that compressed tail.
        //   - Since 2026-10-28 gain 0 with normalization off is the passthrough table on BOTH legs,
        //     so the cap per leg is full scale: the correlated worst-case sum is sqrt(2) = 1.41,
        //     3.0 dB over, and 0.707 is the value that keeps it <= 1.0. Uncorrelated RMS is still
        //     flat. Stays 1.0 (user-settled 2026-10-15) unless a listening pass hears grit.
        // ⚠️ STILL OPEN IF NORMALIZATION IS EVER RE-ENABLED: the sidecar still gets no setTrackGain,
        // so the tail would play ungained against a gained main leg.
        // [CORRECTED 2026-11-11] Closed by removal: normalization, setTrackGain and the LUT were deleted, so both legs have
        // no per-track gain and the cap per leg is full scale, as the correction above says.
        // [CORRECTED 2026-11-10] "No guard" now holds only for overlaps whose fade-in is at least as long as the fade-out. A
        // loud start's shorter fade-in (CrossfadeController.incomingFadeMs) would sum to 1.92 (+5.7 dB) correlated, so each
        // leg now applies the overlap trim (overlapTrim, see the note at fadeInStartFrame), which keeps the 1.41 bound above;
        // it is inert on every other overlap. This constant is unchanged and still 1.0.
        // [CORRECTED 2026-11-13] incomingFadeMs is removed with the pair rule (the user's decision): the fade-in is now the whole
        // overlap. The trim stays, by the same decision, as a guard. Derived, not measured: it acts only when cos^2 + sin^2 > 1,
        // which needs a fade-in shorter than the fade-out. A fade-out shortened to the tail's remaining audio
        // (CrossfadeController.outgoingRampMs) or a fade-in delayed to the onset only lowers the sum, so it should now be inert.
        const val OVERLAP_PEDESTAL = 1.0f

        /** -60 dBFS: below this a sample counts as silence for [TailMeter]. Chosen, not measured. */
        const val TAIL_SILENCE = 0.001f
    }
}
