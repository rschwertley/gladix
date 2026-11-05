package dev.brahmkshatriya.echo.playback.renderer

import android.content.Context
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioOutput
import androidx.media3.exoplayer.audio.ForwardingAudioOutputProvider
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

        // ⚠⚠ FLOAT OUTPUT MUST STAY OFF, AND THIS IS NOT A TASTE QUESTION.
        // DefaultAudioSink.configure (media3 1.11.0, :754-761) adds
        // audioProcessorChain.getAudioProcessors() ONLY in the non-float arm; with float output on
        // and a high-resolution PCM decode (shouldUseFloatOutput = `enableFloatOutput &&
        // isEncodingHighResolutionPcm`, :1733-1735) the sink adds toFloatPcmAudioProcessor INSTEAD
        // and this chain never runs - so AudioEffectsProcessor, the crossfade's hand-off mute, both
        // ramps, the pre-roll discard, the EQ and the normalization LUT all disappear, on high-res
        // tracks only, with no error. Deezer's HiFi FLAC (24-bit / up to 192 kHz) is precisely such
        // a decode, which is why this is written here now.
        // ⚠️ THE VALUE IS DefaultRenderersFactory'S PARAMETER, WHICH IS FALSE
        // UNLESS setEnableAudioFloatOutput(true) IS CALLED - nothing in this app calls it. Passing
        // it through rather than hardcoding false keeps that single source of truth; do not pass
        // `true` here, and do not call that setter without reading the note at
        // AudioEffectsProcessor.onConfigure first.
        // ⚠️ AUDIO OFFLOAD IS THE OTHER WAY TO LOSE THIS CHAIN and it is
        // disabled unconditionally at PlayerService.offloadPreferences
        // (AUDIO_OFFLOAD_MODE_DISABLED, 7c6b37d1) - offload bypasses the processor chain
        // altogether, so the crossfade would not merely sound wrong, it would not happen.
        DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setAudioProcessorChain(
                DiscardReportingChain(
                    audioEffectsProcessor,
                    silenceSkippingAudioProcessor,
                    SonicAudioProcessor()
                )
            )
            // Builder(context).build() is what the sink builds by itself when no provider is set: same
            // buffer size and offload defaults, no AudioTrackProvider (DefaultAudioSink.Builder.build,
            // media3 1.11.1). The one thing the wrapper changes: getAudioCapabilities() returns null,
            // because the sink tests `instanceof AudioTrackAudioOutputProvider` and that class is final.
            // Its only reader is MediaCodecAudioRenderer's IAMF layout, which falls back to stereo.
            .setAudioOutputProvider(
                GatedOutputProvider(
                    AudioTrackAudioOutputProvider.Builder(context).build(),
                    audioEffectsProcessor.trackGate,
                )
            )
            .build()
    }

    private class GatedOutputProvider(
        provider: AudioOutputProvider,
        private val gate: TrackGate,
    ) : ForwardingAudioOutputProvider(provider) {
        override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput =
            gate.wrap(super.getAudioOutput(config))
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

/**
 * ⚠⚠ MUTES THE CURRENT AudioTrack WITHOUT TOUCHING player.volume, SO THE CROSSFADE HAND-OFF CAN SILENCE
 * AUDIO ALREADY QUEUED IN IT (see CrossfadeController.activate). Nothing else reaches queued audio:
 * AudioEffectsProcessor only sees frames not yet written.
 * ⚠️ PLAYBACK THREAD ONLY. AudioOutput states no thread contract, and the sink creates, drives and
 * releases its output there, so the controller reaches this through a PlayerMessage, which also keeps
 * it in order with the seek. No locks, and no volume write can follow a release.
 * ⚠️ THE MUTE BELONGS TO THE OUTPUT, NOT TO THIS CLASS, so a new AudioTrack always starts at unity: a
 * forgotten un-mute costs at most the one track the seek is about to release anyway.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class TrackGate {
    private var current: GatedOutput? = null

    fun setMuted(muted: Boolean) {
        current?.setScale(if (muted) 0f else 1f)
    }

    internal fun wrap(output: AudioOutput): AudioOutput = GatedOutput(output).also { current = it }

    private inner class GatedOutput(output: AudioOutput) : ForwardingAudioOutput(output) {
        // What the sink asked for, kept so a duck during the mute still applies when it lifts.
        private var requested = 1f
        private var scale = 1f

        override fun setVolume(volume: Float) {
            requested = volume
            super.setVolume(volume * scale)
        }

        fun setScale(newScale: Float) {
            scale = newScale
            super.setVolume(requested * newScale)
        }

        override fun release() {
            if (current === this) current = null
            super.release()
        }
    }
}