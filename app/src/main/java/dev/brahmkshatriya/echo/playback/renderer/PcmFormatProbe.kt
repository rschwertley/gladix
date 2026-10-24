package dev.brahmkshatriya.echo.playback.renderer

import com.google.firebase.crashlytics.FirebaseCrashlytics
import dev.brahmkshatriya.echo.BuildConfig
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ⚠⚠ THE CANARY FOR AudioEffectsProcessor'S ONE PREMISE: that the format it is
 * configured with is PCM 16-bit. Read the note at AudioEffectsProcessor.onConfigure for why that
 * holds (DefaultAudioSink inserts toInt16PcmAudioProcessor ahead of our chain) and for the two
 * settings that would break it. This fires only if it stops holding.
 *
 * ⚠️ WHY A REPORT RATHER THAN A LOG: the failure is SILENT AND TOTAL on the
 * tracks it affects - queueInput's pass-through branch copies the buffer through, so the
 * crossfade's hand-off mute, both ramps, the discard, the EQ and the LUT are all skipped with
 * nothing on screen and nothing in the log to say so. A user would report "the crossfade stopped
 * working on some tracks", which is the hardest shape to diagnose from a description.
 *
 * ⚠️ ONCE PER PROCESS. onConfigure runs per format change, so an unexpected
 * format would otherwise report on every track and on every seek that reconfigures the sink. The
 * first one carries everything needed; the rest are the same finding.
 * ⚠️ KEYS ARE LITERAL STRINGS, R8-SAFE, as StaleFocusProbe's note records:
 * Crashlytics deobfuscates stack traces only, so a key built from a class or field name would
 * arrive renamed.
 *
 * REMOVAL: this is not a temporary probe. It has no removal condition - it is the standing check
 * that the pipeline order media3 gives us has not changed under a version bump. Delete it only if
 * the processor stops assuming 16-bit.
 */
object PcmFormatProbe {

    private val reported = AtomicBoolean(false)

    fun onUnexpectedFormat(encoding: Int, sampleRate: Int, channelCount: Int) {
        if (!reported.compareAndSet(false, true)) return
        if (!BuildConfig.HAS_FIREBASE) return
        runCatching {
            val crashlytics = FirebaseCrashlytics.getInstance()
            crashlytics.setCustomKey("pcm_encoding", encoding)
            crashlytics.setCustomKey("pcm_sample_rate", sampleRate)
            crashlytics.setCustomKey("pcm_channel_count", channelCount)
            val error = UnexpectedPcmEncoding(
                "AudioEffectsProcessor configured with encoding " + encoding +
                    " at " + sampleRate + " Hz - the chain is bypassing its gain and ramps"
            )
            // Trimmed as StaleFocusProbe trims: the frames inside this object say nothing and the
            // interesting one - whoever configured the sink - is just above them.
            val all = Throwable().stackTrace
            val own = PcmFormatProbe::class.java.name
            val trimmed = all.dropWhile { it.className == own }.take(TRACE_FRAMES)
            error.stackTrace = (trimmed.ifEmpty { all.toList() }).toTypedArray()
            crashlytics.recordException(error)
        }
    }

    private const val TRACE_FRAMES = 12

    class UnexpectedPcmEncoding(message: String) : Exception(message)
}
