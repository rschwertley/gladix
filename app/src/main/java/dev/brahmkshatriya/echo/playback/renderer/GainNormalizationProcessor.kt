package dev.brahmkshatriya.echo.playback.renderer

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.pow

@OptIn(UnstableApi::class)
class GainNormalizationProcessor : BaseAudioProcessor() {

    @Volatile var enabled = false
    private val gainMultiplier = AtomicReference(1f)
    private var configuredFormat = AudioProcessor.AudioFormat.NOT_SET

    fun setTrackGain(gainDb: Float) {
        gainMultiplier.set(10f.pow(gainDb / 20f).coerceIn(1f, 10f))
    }

    fun resetGain() {
        gainMultiplier.set(1f)
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        configuredFormat = inputAudioFormat
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val gain = if (enabled) gainMultiplier.get() else 1f
        val output = replaceOutputBuffer(inputBuffer.remaining())

        val fmt = configuredFormat
        if (gain == 1f || fmt.encoding != C.ENCODING_PCM_16BIT || fmt.sampleRate <= 0) {
            output.put(inputBuffer)
            output.flip()
            return
        }

        val channelCount = fmt.channelCount
        while (inputBuffer.remaining() >= channelCount * 2) {
            repeat(channelCount) {
                val sample = inputBuffer.short.toInt()
                output.putShort((sample * gain).toInt().coerceIn(-32768, 32767).toShort())
            }
        }
        output.flip()
    }
}
