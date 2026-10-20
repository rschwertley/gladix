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
            .setAudioProcessorChain(
                DiscardReportingChain(
                    audioEffectsProcessor,
                    silenceSkippingAudioProcessor,
                    SonicAudioProcessor()
                )
            )
            .build()
    }

    /**
     * ⚠⚠ TELLS THE SINK ABOUT THE FRAMES AudioEffectsProcessor DROPS, WHICH IS WHAT
     * KEEPS getCurrentPosition HONEST ON THE CROSSFADE SIDECAR. DefaultAudioSink derives media
     * time from the frames the AudioTrack has PLAYED and then adds back whatever the chain
     * reports as skipped:
     * `positionUs + framesToDurationUs(audioProcessorChain.getSkippedOutputFrameCount())`
     * (DefaultAudioSink.java:1776-1778, media3 1.11.0). Its own chain reports ONLY the
     * silence-skipping processor (:222-224), so the crossfade pre-roll's discard would otherwise
     * be invisible and the sidecar's reported position would lag its audio permanently by the
     * discarded amount - which every later reader (tail-remaining arithmetic, the CROSSFADE-FEEL
     * lines, any second measurement) would then have to know about and subtract.
     *
     * ⚠️ SHARED WITH THE MAIN PLAYER, AND A NO-OP THERE. Both players are built by
     * this factory; nothing discards on the main leg, so the added count stays 0 and the
     * behaviour is identical to DefaultAudioProcessorChain.
     * ⚠️ SUBCLASSING IS SUPPORTED BY CONSTRUCTION, checked rather than assumed:
     * DefaultAudioProcessorChain is `public static class` with public, non-final members
     * (DefaultAudioSink.java:159) and the three-argument constructor is public at :177.
     */
    @androidx.annotation.OptIn(UnstableApi::class)
    private class DiscardReportingChain(
        private val effects: AudioEffectsProcessor,
        silenceSkipping: SilenceSkippingAudioProcessor,
        sonic: SonicAudioProcessor,
    ) : DefaultAudioSink.DefaultAudioProcessorChain(
        arrayOf(effects), silenceSkipping, sonic
    ) {
        override fun getSkippedOutputFrameCount(): Long =
            super.getSkippedOutputFrameCount() + effects.discardedFrameCount()
    }
}