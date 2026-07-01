package dev.brahmkshatriya.echo.playback.renderer

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
class AudioEffectsProcessor : BaseAudioProcessor() {

    @Volatile var crossfadeEnabled = false
    @Volatile var normalizationEnabled = false
    @Volatile var crossfadeDurationMs = 5000
    @Volatile var skipFade = false

    private val fadeInFramesRemaining = AtomicLong(0)
    private val fadeOutFramesRemaining = AtomicLong(0)
    // audio thread only — no atomic needed
    private var isPendingFadeIn = false
    private var configuredFormat = AudioProcessor.AudioFormat.NOT_SET

    // LUT infrastructure
    private var activeLut: ShortArray = generateDualStageLUT(0f)
    private val pendingLut = AtomicReference<Pair<String, ShortArray>?>(null)
    private val currentTrackToken = AtomicReference("")
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

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

    fun onFadeOutStart() {
        FadeTrace.rec(FadeTrace.FOS, crossfadeFrames()) // FADEDBG
        if (crossfadeEnabled) fadeOutFramesRemaining.set(crossfadeFrames())
    }

    fun cancelFades() {
        FadeTrace.rec(FadeTrace.CANCEL, fadeInFramesRemaining.get(), fadeOutFramesRemaining.get()) // FADEDBG
        fadeInFramesRemaining.set(0)
        fadeOutFramesRemaining.set(0)
    }

    private fun crossfadeFrames(): Long {
        val fmt = configuredFormat
        if (fmt.sampleRate <= 0 || fmt.encoding != C.ENCODING_PCM_16BIT) return 0L
        return fmt.sampleRate.toLong() * crossfadeDurationMs / 1000L
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        configuredFormat = inputAudioFormat
        FadeTrace.firstBufferPending = true // FADEDBG
        FadeTrace.rec(FadeTrace.CFG, inputAudioFormat.encoding.toLong(), inputAudioFormat.sampleRate.toLong(), crossfadeFrames()) // FADEDBG
        return inputAudioFormat
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION") // FADEDBG: purely observational — base onFlush() is a no-op
    override fun onFlush() {
        FadeTrace.rec(FadeTrace.FLUSH)
    }

    override fun onQueueEndOfStream() {
        FadeTrace.rec(FadeTrace.QES, if (isPendingFadeIn) 1L else 0L, fadeInFramesRemaining.get(), fadeOutFramesRemaining.get(), if (crossfadeEnabled) 1L else 0L, if (skipFade) 1L else 0L) // FADEDBG
        if (crossfadeEnabled && !skipFade) isPendingFadeIn = true
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return

        // FADEDBG: pre-arm snapshot of this buffer; first=1 marks the first buffer after a reconfigure
        FadeTrace.rec(
            FadeTrace.QIN,
            if (FadeTrace.firstBufferPending) 1L else 0L,
            fadeInFramesRemaining.get(),
            fadeOutFramesRemaining.get(),
            (if (isPendingFadeIn) 1L else 0L) or (if (skipFade) 2L else 0L),
            crossfadeFrames()
        )
        FadeTrace.firstBufferPending = false // FADEDBG

        if (skipFade) {
            FadeTrace.rec(FadeTrace.SKIPBLK) // FADEDBG
            // Force full volume for album transitions, regardless of whether isPendingFadeIn
            // raced ahead of the skipFade flag being set (onQueueEndOfStream runs on the audio
            // thread, skipFade is set from the main thread — no ordering guarantee between them).
            isPendingFadeIn = false
            fadeInFramesRemaining.set(0)
            fadeOutFramesRemaining.set(0)
        } else if (isPendingFadeIn) {
            FadeTrace.rec(FadeTrace.ARM, crossfadeFrames()) // FADEDBG
            fadeInFramesRemaining.set(crossfadeFrames())
            isPendingFadeIn = false
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
            output.put(inputBuffer)
            output.flip()
            return
        }

        var currentFi = fadeInFramesRemaining.get()
        var currentFo = fadeOutFramesRemaining.get()
        val total = crossfadeFrames()
        val channelCount = fmt.channelCount
        val applyFade = crossfadeEnabled && total > 0

        while (inputBuffer.remaining() >= channelCount * 2) {
            // Cosine² fade envelope — computed once per frame, applied to all channels
            val fadeGain: Float
            if (applyFade && (currentFi > 0 || currentFo > 0)) {
                val fadeInGain = if (currentFi > 0) {
                    val c = cos(currentFi.toFloat() / total * (PI / 2).toFloat())
                    c * c
                } else 1f
                val fadeOutGain = if (currentFo > 0) {
                    val c = cos((1f - currentFo.toFloat() / total) * (PI / 2).toFloat())
                    c * c
                } else 1f
                if (currentFi > 0) currentFi--
                if (currentFo > 0) currentFo--
                fadeGain = fadeInGain * fadeOutGain
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
        output.flip()
    }

    private companion object {
        const val PASS_A_EXPONENT = 1.0 / 2.5  // 2.5:1 compression ratio, threshold 0.4
    }
}
