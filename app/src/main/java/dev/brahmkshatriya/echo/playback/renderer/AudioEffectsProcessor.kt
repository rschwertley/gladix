package dev.brahmkshatriya.echo.playback.renderer

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.StreamMetadata
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.min
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
class AudioEffectsProcessor : BaseAudioProcessor() {

    @Volatile var normalizationEnabled = false

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
    private val rampTotalFrames = AtomicLong(0)

    // ⚠⚠ MUTE AND TOKEN: THE INCOMING-LEG HANDSHAKE, AND onFlush MUST NOT CLEAR THEM.
    // At hand-off the MAIN player is the INCOMING leg: it is muted, then seeked to the next item,
    // and the seek FLUSHES this processor. A fade-in armed before the seek would be wiped by that
    // flush, and an unmuted main player would play the departing track twice (its own buffered
    // audio and the sidecar's tail) for the 1-2 s the seek takes. So:
    //   armIncoming(token)  ->  output silence, remember the token;
    //   onFlush             ->  clears the COUNTERS, keeps muted and pendingToken;
    //   first post-flush queueInput -> publishes the token (still silent);
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
    private var configuredFormat = AudioProcessor.AudioFormat.NOT_SET

    // LUT infrastructure
    // Placeholder is a CLEAN PASSTHROUGH table (see passthroughLut) set synchronously in the ctor, so
    // activeLut is always a valid 65536-entry array before the audio thread reads it. The real unity-gain
    // table (~65k pow() — the onCreate ANR when built in the ctor) is generated OFF-MAIN in init{} below
    // and swapped in by the audio thread via pendingLut, exactly like setTrackGain/resetGain.
    private var activeLut: ShortArray = passthroughLut()
    private val pendingLut = AtomicReference<Pair<String, ShortArray>?>(null)
    private val currentTrackToken = AtomicReference("")
    // No CoroutineExceptionHandler, and that is CORRECT here — CHECKED 2026-09-10, DO NOT RE-FLAG.
    // A handler-less scope is the build-974 crash shape (an uncaught throw goes to the default uncaught
    // handler and kills the app), so this was audited alongside MainApplication's. It differs in the one
    // way that matters: EXTENSION CODE CANNOT REACH IT. All three scope.launch bodies (the init LUT, and
    // setTrackGain/resetGain below) do pure local work — generateDualStageLUT arithmetic, AtomicReference
    // sets and token comparisons. No ExtensionLoader, no network, no third-party call, nothing that can
    // throw an unknown type from someone else's APK.
    // ⚠️ AND THERE IS A SECOND REASON IT STAYS AS IT IS, from the earlier examination: this class is
    // constructed with NO App REFERENCE, so routing to throwFlow would need constructor plumbing through
    // the renderer. That cost is not worth paying for closed-form CPU work that cannot throw an unknown
    // type. Deferred deliberately, twice; this note exists so a third sweep does not re-surface it.
    // If anything extension-facing is ever launched here, this needs the handler — see App.exceptionHandler.
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    init {
        // Defer the unity-gain LUT off the main thread. Stage with the initial token so the audio thread's
        // swap gate applies it only while no track has set its own gain yet; a later setTrackGain
        // (unconditional pendingLut.set) always wins, and compareAndSet(null,…) here never disturbs it.
        scope.launch {
            val lut = generateDualStageLUT(0f)
            pendingLut.compareAndSet(null, currentTrackToken.get() to lut)
        }
    }

    // Clean unprocessed passthrough for the pre-swap window. The read path is index = rawSample + 32768,
    // output = activeLut[index], so lut[i] = i - 32768 maps every input sample back to ITSELF exactly.
    // (lut[i] = i would add a +32768 DC offset and clip — distortion.) No pow(), so it's cheap on main; it
    // only omits the soft-limiter on >half-scale peaks until the real table swaps in — inaudible, and no
    // audio flows at onCreate anyway.
    private fun passthroughLut() = ShortArray(65536) { (it - 32768).toShort() }

    private fun generateDualStageLUT(gainDb: Float, isNormalizationEnabled: Boolean = true): ShortArray {
        val gainMultiplier = 10.0.pow(gainDb / 20.0)
        val lut = ShortArray(65536)
        for (i in 0..65535) {
            val rawSample = (i - 32768) / 32768.0
            val signedInput = rawSample * gainMultiplier
            val sign = if (signedInput >= 0.0) 1.0 else -1.0
            var x = abs(signedInput)

            // Pass A — 2.5:1 downward compression above threshold 0.4
            if (isNormalizationEnabled && x > 0.4) x = 0.4 * (x / 0.4).pow(PASS_A_EXPONENT)

            // Pass B — cubic soft limiter, C0-continuous at x=0.5 and x=1.25
            val out = when {
                x <= 0.5  -> x
                x <= 1.25 -> (-16.0 / 27.0) * x.pow(3) + (8.0 / 9.0) * x.pow(2) + (5.0 / 9.0) * x + (2.0 / 27.0)
                else      -> 1.0
            }

            lut[i] = (out * sign * 32767.0).toInt().coerceIn(-32768, 32767).toShort()
        }
        return lut
    }

    fun setTrackGain(gainDb: Float?, trackId: String?) {
        val token = trackId ?: ""
        currentTrackToken.set(token)
        scope.launch {
            val effectiveGain = if (normalizationEnabled) {
            if (gainDb != null) (gainDb + 4.5f).coerceIn(-15f, 15f)
            else 0f
        } else 0f
            val lut = generateDualStageLUT(effectiveGain, normalizationEnabled)
            if (currentTrackToken.get() == token) {
                pendingLut.set(Pair(token, lut))
            }
        }
    }

    fun resetGain() {
        val token = currentTrackToken.get()
        scope.launch {
            val lut = generateDualStageLUT(0f, normalizationEnabled)
            if (currentTrackToken.get() == token) {
                pendingLut.set(Pair(token, lut))
            }
        }
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
    fun startFadeOut(mediaMs: Long) {
        val frames = framesFor(mediaMs)
        rampTotalFrames.set(frames)
        fadeInFramesRemaining.set(0)
        fadeOutFramesRemaining.set(frames)
    }

    /**
     * Incoming leg (the main player after the hand-off seek). Clears the mute set by
     * [armIncoming]: the first audible sample is therefore the first sample of the ramp, at gain 0.
     */
    fun startFadeIn(mediaMs: Long) {
        val frames = framesFor(mediaMs)
        rampTotalFrames.set(frames)
        fadeOutFramesRemaining.set(0)
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

    /** Cumulative dropped frames, for RenderersFactory's chain. See [discardedFrames]. */
    fun discardedFrameCount(): Long = discardedFrames.get()

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
        rampTotalFrames.set(0)
        // The PENDING discard goes, because it is gain-side state that must not survive an
        // abort. The CUMULATIVE counts deliberately do NOT: they are what the sink has
        // been told about the stream it may still be draining, so its lifetime is the stream's -
        // bounded by onFlush and onReset - and zeroing it here would step the reported position.
        discardFramesRemaining.set(0)
        pendingToken.set(null)
        publishedToken.set(null)
        muted = false
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        configuredFormat = inputAudioFormat
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
        rampTotalFrames.set(0)
        // ⚠️ THE DISCARD STATE GOES, INCLUDING THE CUMULATIVE COUNT, AND THE MUTE
        // STILL DOES NOT. A flush restarts the stream, so frames dropped from the old one are no
        // longer part of what the sink is measuring - leaving the count behind would offset the
        // new stream's position by an amount that belongs to the previous one.
        clearPreRollState()
    }

    override fun onReset() {
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
        discardedFrames.addAndGet(dropped)
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

        // Swap in pending LUT if available and generation token still matches
        pendingLut.get()?.let { (lutToken, lutArray) ->
            if (lutToken == currentTrackToken.get()) {
                activeLut = lutArray
                pendingLut.set(null)
            }
        }

        val fmt = configuredFormat
        val output = replaceOutputBuffer(inputBuffer.remaining())

        if (fmt.encoding != C.ENCODING_PCM_16BIT || fmt.sampleRate <= 0) {
            countEmitted(inputBuffer.remaining())
            output.put(inputBuffer)
            output.flip()
            return
        }

        var currentFi = fadeInFramesRemaining.get()
        var currentFo = fadeOutFramesRemaining.get()
        val total = rampTotalFrames.get()
        val channelCount = fmt.channelCount
        val applyFade = total > 0

        while (inputBuffer.remaining() >= channelCount * 2) {
            // Cosine² fade envelope — computed once per frame, applied to all channels
            var fadeGain: Float
            if (applyFade && (currentFi > 0 || currentFo > 0)) {
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
                if (currentFo > 0) currentFo--
                fadeGain = fadeInGain * fadeOutGain * OVERLAP_PEDESTAL
            } else {
                fadeGain = 1f
            }
            repeat(channelCount) {
                val raw = inputBuffer.short.toInt()
                val index = (raw + 32768).coerceIn(0, 65535)
                val processed = activeLut[index] / 32768f
                output.putShort(
                    (processed * fadeGain * 32768f).toInt()
                        .coerceIn(-32768, 32767).toShort()
                )
            }
        }

        fadeInFramesRemaining.set(currentFi)
        fadeOutFramesRemaining.set(currentFo)
        countEmitted(output.position())
        output.flip()
    }

    private companion object {
        const val PASS_A_EXPONENT = 1.0 / 2.5  // 2.5:1 compression ratio, threshold 0.4

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
        const val OVERLAP_PEDESTAL = 1.0f
    }
}
