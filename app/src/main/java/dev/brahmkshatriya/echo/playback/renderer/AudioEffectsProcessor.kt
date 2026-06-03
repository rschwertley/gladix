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
import kotlin.math.sign

@OptIn(UnstableApi::class)
class AudioEffectsProcessor : BaseAudioProcessor() {

    @Volatile var crossfadeEnabled = false
    @Volatile var normalizationEnabled = false
    @Volatile var crossfadeDurationMs = 5000

    private val gainMultiplier = AtomicReference(1f)
    private val fadeInFramesRemaining = AtomicLong(0)
    private val fadeOutFramesRemaining = AtomicLong(0)
    // audio thread only — no atomic needed
    private var isPendingFadeIn = false
    private var configuredFormat = AudioProcessor.AudioFormat.NOT_SET

    // null = no gain data available, apply passthrough
    fun setTrackGain(gainDb: Float?) {
        gainMultiplier.set(if (gainDb != null) 10f.pow(gainDb / 20f) else 1f)
    }

    fun resetGain() {
        gainMultiplier.set(1f)
    }

    fun onFadeOutStart() {
        if (crossfadeEnabled) fadeOutFramesRemaining.set(crossfadeFrames())
    }

    fun cancelFades() {
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
        return inputAudioFormat
    }

    override fun queueEndOfStream() {
        if (crossfadeEnabled) isPendingFadeIn = true
        super.queueEndOfStream()
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        if (isPendingFadeIn) {
            fadeInFramesRemaining.set(crossfadeFrames())
            isPendingFadeIn = false
        }

        val fmt = configuredFormat
        val output = replaceOutputBuffer(inputBuffer.remaining())

        if (fmt.encoding != C.ENCODING_PCM_16BIT || fmt.sampleRate <= 0) {
            output.put(inputBuffer)
            output.flip()
            return
        }

        val gain = if (normalizationEnabled) gainMultiplier.get() else 1f
        var currentFi = fadeInFramesRemaining.get()
        var currentFo = fadeOutFramesRemaining.get()
        val total = crossfadeFrames()
        val channelCount = fmt.channelCount
        val applyFade = crossfadeEnabled && total > 0

        while (inputBuffer.remaining() >= channelCount * 2) {
            // Cosine² fade envelope — computed once per frame, applied to all channels
            val fadeGain: Float
            if (applyFade && (currentFi > 0 || currentFo > 0)) {
                // fade-in: cos²(currentFi/total * π/2) — 0 at track start, 1 when complete
                val fadeInGain = if (currentFi > 0) {
                    val c = cos(currentFi.toFloat() / total * (PI / 2).toFloat())
                    c * c
                } else 1f
                // fade-out: cos²((1 - currentFo/total) * π/2) — 1 at fade start, 0 at track end
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

            val combinedGain = gain * fadeGain
            repeat(channelCount) {
                val normalized = inputBuffer.short.toInt() / 32768f
                output.putShort(
                    (softLimit(normalized * combinedGain) * 32768f).toInt()
                        .coerceIn(-32768, 32767).toShort()
                )
            }
        }

        fadeInFramesRemaining.set(currentFi)
        fadeOutFramesRemaining.set(currentFo)
        output.flip()
    }

    // Polynomial soft limiter on normalized float samples [-1f, 1f].
    // Linear below 0.5, cubic knee 0.5–1.25, hard clamp at ±1 above 1.25.
    // Always active — catches gain-normalization overshoot without hard PCM clipping.
    private fun softLimit(x: Float): Float {
        val a = abs(x)
        return when {
            a <= 0.5f -> x
            a < 1.25f -> sign(x) * (3f - (2f * a - 2f) * (2f * a - 2f)) / 4f
            else -> sign(x)
        }
    }
}
