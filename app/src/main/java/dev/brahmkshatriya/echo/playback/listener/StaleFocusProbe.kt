package dev.brahmkshatriya.echo.playback.listener

import com.google.firebase.crashlytics.FirebaseCrashlytics
import dev.brahmkshatriya.echo.BuildConfig
import dev.brahmkshatriya.echo.utils.CrashKeys

/**
 * ⚠⚠ REPORTS A FOCUS RESUME THAT WAS REFUSED FOR BEING TOO OLD, AND IT EXISTS TO NAME AN
 * ARMING PATH WE COULD NOT IDENTIFY FROM A CAPTURE. Build 1141: a player the user had paused the
 * previous night resumed itself when an alarm handed audio focus back at 06:15. The end state is
 * understood - [AudioFocusListener.pausedForFocus] was still true, so AUDIOFOCUS_GAIN called
 * play() - but nothing in the dumpsys says what armed it. No app requested focus between 22:40 and
 * the alarm, which rules out the transient-loss reading; AUDIOFOCUS_REQUEST_DELAYED is possible and
 * unproven. So the 30-minute window in AudioFocusListener is a bound on an unknown mechanism, and
 * this is the instrument that turns the next occurrence into a named one.
 *
 * ⚠️ NOT A TEMPORARY PROBE, unlike ColdStartPlayProbe. It reports a refusal, not a
 * suspicion: every report means a real resume was blocked, which stays worth knowing even once the
 * arming path is identified. There is no removal condition - if `armedBy` turns out to be a path
 * worth fixing at source, FIX IT and leave this reporting the residue.
 *
 * ⚠️ WHAT EACH OUTPUT WOULD SETTLE, written before the build so the next report is
 * readable without re-deriving the model:
 *   armedBy=transientLoss  the 1141 reading after all, and the dumpsys window simply missed the
 *                          loss - look for a focus event older than the capture's first line.
 *   armedBy=delayedGrant   requestFocus returned AUDIOFOCUS_REQUEST_DELAYED and the grant never
 *                          arrived. That branch deliberately holds focus; it needs its own expiry.
 *   armedBy=loss           a full AUDIOFOCUS_LOSS whose 750 ms commit was cancelled by a GAIN that
 *                          then did not resume - i.e. cancelCommit ran and the resume did not.
 *   armedBy=unknown        the latch was set by code that does not go through armPausedForFocus,
 *                          which would mean a new writer has appeared. Grep for pausedForFocus.
 * A LOW ageMs (near the window) means the window is too tight for a legitimate interruption and
 * should be raised; hours mean exactly the 1141 shape.
 *
 * ⚠️ ITS KEYS ARE SAFE UNDER R8, CHECKED 2026-10-21 - do not "fix" them the way
 * IdleStateProbe's had to be. Crashlytics deobfuscates STACK TRACES ONLY, so a custom key
 * holding a class or method name arrives minified and stays that way. `armedBy` here is one of
 * three LITERAL strings chosen at the arming sites, not a symbol name, and every other key is a
 * number or a boolean. The trace is captured inside the focus callback, which is exactly where
 * the interesting frame is.
 *
 * ⚠️ recordException DIRECTLY, WHICH IS A THIRD SITE, AND THE REASON IS THE SAME ONE
 * ColdStartPlayProbe GIVES: App.kt and HealthMonitor are the two permanent sites, and the
 * alternative here - app.throwFlow - SHOWS THE USER A SNACKBAR. A diagnostic must not become a
 * behaviour change, least of all one that fires while the user is asleep.
 */
object StaleFocusProbe {

    /** Process age at the moment the latch was armed, for the report's `armedAtProcessMs` key. */
    fun processAge(): Long = runCatching { CrashKeys.processAgeMs() }.getOrDefault(-1L)

    fun onBlocked(armedBy: String, ageMs: Long, armedAtProcessMs: Long, playWhenReady: Boolean) {
        if (!BuildConfig.HAS_FIREBASE) return
        runCatching {
            val crashlytics = FirebaseCrashlytics.getInstance()
            crashlytics.setCustomKey("focus_armed_by", armedBy)
            crashlytics.setCustomKey("focus_age_ms", ageMs)
            crashlytics.setCustomKey("focus_armed_at_process_ms", armedAtProcessMs)
            crashlytics.setCustomKey("focus_process_ms", processAge())
            crashlytics.setCustomKey("focus_play_when_ready", playWhenReady)
            val error = StaleFocusResumeBlocked(
                "blocked a focus resume armed by " + armedBy + " " + ageMs + "ms ago"
            )
            // Trimmed the same way ColdStartPlayProbe trims: the frames inside this object say
            // nothing, and the interesting one - the focus callback - is just above them.
            val all = Throwable().stackTrace
            val own = StaleFocusProbe::class.java.name
            val trimmed = all.dropWhile { it.className == own }.take(TRACE_FRAMES)
            error.stackTrace = (trimmed.ifEmpty { all.toList() }).toTypedArray()
            crashlytics.recordException(error)
        }
    }

    private const val TRACE_FRAMES = 12
}

/**
 * Filed by [StaleFocusProbe] when AUDIOFOCUS_GAIN found a resume authorisation older than
 * AudioFocusListener's window and refused it. The user kept their silence; this says so.
 */
class StaleFocusResumeBlocked(message: String) : Exception(message)
