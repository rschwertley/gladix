package dev.brahmkshatriya.echo.playback.renderer

import android.content.Context
import android.media.AudioTimestamp
import androidx.media3.common.C
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.AudioTrackAudioOutput
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioOutput
import androidx.media3.exoplayer.audio.ForwardingAudioOutputProvider
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import java.nio.ByteBuffer


@androidx.annotation.OptIn(UnstableApi::class)
class RenderersFactory(
    context: Context,
    private val audioEffectsProcessor: AudioEffectsProcessor,
) : DefaultRenderersFactory(context) {

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean
    ): AudioSink = run {
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
        // ramps, the pre-roll discard and the EQ all disappear, on high-res
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
        TimestampedSink(DefaultAudioSink.Builder(context)
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
                    silenceSkippingAudioProcessor,
                )
            )
            .build(), audioEffectsProcessor)
    }

    // Tells the gate each input buffer's media time and how many frames came before it (2026-11-04); see
    // TrackGate.mediaTimestamp. Here because the AudioOutput sees only what the track accepted, and a full track leaves
    // an earlier buffer's output to be written under a later buffer's time.
    private class TimestampedSink(
        sink: AudioSink,
        private val effects: AudioEffectsProcessor,
    ) : ForwardingAudioSink(sink) {
        private var lastPresentationTimeUs = C.TIME_UNSET

        override fun setOutputStreamOffsetUs(outputStreamOffsetUs: Long) {
            effects.trackGate.onStreamOffset(outputStreamOffsetUs)
            super.setOutputStreamOffsetUs(outputStreamOffsetUs)
        }

        override fun handleBuffer(
            buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int,
        ): Boolean {
            if (presentationTimeUs != lastPresentationTimeUs) {
                lastPresentationTimeUs = presentationTimeUs
                effects.trackGate.onInputBuffer(
                    presentationTimeUs, effects.emittedFrameCount() + effects.discardedFrameCount()
                )
            }
            return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        }
    }

    private class GatedOutputProvider(
        provider: AudioOutputProvider,
        private val gate: TrackGate,
        private val skipper: SilenceSkippingAudioProcessor,
    ) : ForwardingAudioOutputProvider(provider) {
        override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput =
            gate.wrap(super.getAudioOutput(config)) { skipper.skippedFrames }
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
 * [CORRECTED 2026-11-04] Playback thread only for the VOLUME. [mediaTimestamp] is read from the app looper: AudioTrack's own
 * calls are thread-safe, a released track only throws, which it catches, and the fields it reads are volatile.
 *
 * ⚠⚠ WHERE A TRACK REALLY IS, IN MEDIA TIME (2026-11-04), which is what CrossfadeController.timestampOffsetUs compares across
 * the two legs. AudioTrack.getTimestamp gives a frame and when it played, with none of the position tracker's smoothing or
 * 10 s polling. [baseUs] turns that frame into media time:
 *     media(frame) = baseUs + (frame + framesDiscarded) / rate
 * baseUs is the same for every buffer of a stream, including across a discard, so one value does. It is the MINIMUM seen,
 * because every error here is one way: when the track is full an earlier buffer is still inside the pipeline, the frame count
 * reads low, and the buffer's base reads high. Any buffer that met an empty pipeline gives the true value. The first buffer of
 * a stream is skipped, the one exception: its head may be trimmed as encoder delay, which reads low.
 * ⚠️ VALID ONLY AT 1x WITH SKIP-SILENCE OFF, because Sonic and the silence skipper change the frame count after AudioEffectsProcessor
 * counts it; the caller checks. Trailing encoder padding held back by the trimmer reads high by a constant on both legs of
 * the same file, so it cancels in the comparison. READ, NOT MEASURED: how accurate this is on a device.
 * [CORRECTED 2026-11-04] VALID AT 1x, skip-silence on or off. "Off" would have disabled this for a default install, because
 * SKIP_SILENCE defaults on, the same trap as the 2026-10-20 note at CrossfadeController.startPreRoll. Only main can have it on.
 *     media(frame) = baseUs + (frame + framesDiscarded + framesSkipped) / rate
 * baseUs needs no change: AudioEffectsProcessor sits before the skipper, so its count is already pre-skip. framesSkipped comes
 * from GatedOutput's marks, exact once the frame playing is past the silence, so a song with no silence over 2 s (the skipper's
 * minimum as built in buildAudioSink) reads as if skip-silence were off. While a skip is still queued ahead of the frame playing
 * there is no answer: main's media time is about to jump, so [mediaTimestamp] returns null.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class TrackGate {
    class MediaTimestamp(val mediaUs: Long, val nanoTime: Long)

    @Volatile private var current: GatedOutput? = null
    @Volatile private var baseUs = C.TIME_UNSET
    private var streamOffsetUs = 0L
    private var buffersSeen = 0

    fun setMuted(muted: Boolean) {
        current?.setScale(if (muted) 0f else 1f)
    }

    internal fun wrap(output: AudioOutput, skipped: () -> Long): AudioOutput = GatedOutput(output, skipped).also {
        current = it
        resetBase()
    }

    private fun resetBase() {
        baseUs = C.TIME_UNSET
        buffersSeen = 0
    }

    // Playback thread. A new stream on the same output (a gapless item) restarts media time.
    internal fun onStreamOffset(offsetUs: Long) {
        streamOffsetUs = offsetUs
        resetBase()
    }

    // Playback thread. [framesBefore] includes the frames discarded, which is what keeps the base constant across a discard.
    internal fun onInputBuffer(presentationTimeUs: Long, framesBefore: Long) {
        val rate = current?.sampleRate ?: return
        if (rate <= 0 || buffersSeen++ == 0) return
        val base = presentationTimeUs - streamOffsetUs - framesBefore * 1_000_000L / rate
        val held = baseUs
        if (held == C.TIME_UNSET || base < held) baseUs = base
    }

    /** Null when the track has no timestamp yet, no buffer has set the base, or a silence skip is queued. See the class note. */
    fun mediaTimestamp(discardedAt: (Long) -> Long): MediaTimestamp? {
        val base = baseUs
        if (base == C.TIME_UNSET) return null
        val output = current ?: return null
        val timestamp = output.timestamp() ?: return null
        val skipped = output.skippedAt(timestamp.framePosition) ?: return null
        val frames = timestamp.framePosition + discardedAt(timestamp.framePosition) + skipped
        return MediaTimestamp(base + frames * 1_000_000L / output.sampleRate, timestamp.nanoTime)
    }

    /** Time skipped up to the frame now playing, whether or not more is queued; null with no timestamp. For the log's skipMs. */
    fun skippedUs(): Long? {
        val output = current ?: return null
        val timestamp = output.timestamp() ?: return null
        return output.skippedPlayed(timestamp.framePosition) * 1_000_000L / output.sampleRate
    }

    private inner class GatedOutput(
        output: AudioOutput,
        private val skipped: () -> Long,
    ) : ForwardingAudioOutput(output) {
        private val track = (output as? AudioTrackAudioOutput)?.audioTrack
            ?.takeIf { Util.isEncodingLinearPcm(it.audioFormat) }

        fun timestamp(): AudioTimestamp? {
            val timestamp = AudioTimestamp()
            val read = runCatching { track?.getTimestamp(timestamp) == true }.getOrDefault(false)
            return timestamp.takeIf { read && sampleRate > 0 }
        }

        private val frameSize = track?.let { Util.getPcmFrameSize(it.audioFormat, it.channelCount) } ?: 0
        private var framesWritten = 0L

        // (frames written when a skip reached this output, cumulative skipped by then). One per skipper chunk, so a few per
        // silence. A chunk's output is written after its skip is counted, so a frame inside that output, ~10 ms of silence per
        // second skipped, reads high by the chunk.
        private val skipMarks = ArrayList<Pair<Long, Long>>()
        private var skipRaw = 0L
        private var skipCarried = 0L
        private var skipTotal = 0L

        // Playback thread, the skipper's own. Its count restarts on a pipeline flush, and this output can outlive one
        // (DefaultAudioSink.applyAudioProcessorPlaybackParametersAndSkipSilence, media3 1.11.1), while the track's frames do not restart.
        private fun markSkips() {
            val raw = skipped()
            if (raw < skipRaw) skipCarried += skipRaw
            skipRaw = raw
            val total = skipCarried + raw
            if (total == skipTotal) return
            skipTotal = total
            synchronized(skipMarks) { skipMarks += framesWritten to total }
        }

        override fun write(buffer: ByteBuffer, encodedAccessUnitCount: Int, presentationTimeUs: Long): Boolean {
            if (frameSize == 0) return super.write(buffer, encodedAccessUnitCount, presentationTimeUs)
            markSkips()
            val before = buffer.remaining()
            val handled = super.write(buffer, encodedAccessUnitCount, presentationTimeUs)
            framesWritten += (before - buffer.remaining()) / frameSize
            return handled
        }

        /** Frames skipped before [frame], or null while a later skip is still queued. See the class note. */
        fun skippedAt(frame: Long): Long? = synchronized(skipMarks) {
            skippedPlayed(frame).takeIf { it == (skipMarks.lastOrNull()?.second ?: 0L) }
        }

        fun skippedPlayed(frame: Long): Long = synchronized(skipMarks) {
            skipMarks.lastOrNull { it.first <= frame }?.second ?: 0L
        }

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