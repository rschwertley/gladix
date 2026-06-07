package dev.brahmkshatriya.echo.playback.renderer

import android.content.Context
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor


@androidx.annotation.OptIn(UnstableApi::class)
class RenderersFactory(
    context: Context,
    private val audioEffectsProcessor: AudioEffectsProcessor,
) : DefaultRenderersFactory(context) {

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean
    ) = run {
        val silenceSkippingAudioProcessor = SilenceSkippingAudioProcessor(
            2_000_000,
            20_000f / 2_000_000f,
            2_000_000,
            0,
            256,
        )

        DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioProcessorChain(
                DefaultAudioSink.DefaultAudioProcessorChain(
                    arrayOf(audioEffectsProcessor),
                    silenceSkippingAudioProcessor,
                    SonicAudioProcessor()
                )
            )
            .build()
    }
}