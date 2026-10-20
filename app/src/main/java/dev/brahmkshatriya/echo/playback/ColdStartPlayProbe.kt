package dev.brahmkshatriya.echo.playback

import android.os.SystemClock
import com.google.firebase.crashlytics.FirebaseCrashlytics
import dev.brahmkshatriya.echo.BuildConfig
import dev.brahmkshatriya.echo.utils.CrashKeys

/**
 * ⚠⚠ [TEMPORARY 2026-10-19] DIAGNOSTIC ONLY, FOR THE COLD-START AUTOPLAY ITEM. It changes no
 * behaviour: it records who asked for play, and on the first play of a young process it either leaves
 * a breadcrumb naming the asker or files one non-fatal saying nobody asked.
 *
 * WHAT IT IS FOR. The item (Aug 11) closed with three durable results and one open question: both
 * prior fixes verified intact, nothing in the cold-start path should play, and "a real play request IS
 * arriving and its source is unidentified". Phone only, intermittent. Reading has already been done -
 * every app-side path was bounded - so the only thing left is to name the caller at the moment it
 * happens, which is what this does.
 *
 * ⚠⚠ REMOVAL IS ONE GREP: `ColdStartPlayProbe`. Delete this file, then delete the call lines
 * it names - PlayerCallback.onPlayerCommandRequest (the whole override), four recordUi() lines in
 * PlayerViewModel, two onPlay() lines in ShufflePlayer, one attach() line in PlayerService - plus
 * CrashKeys.processAgeMs, which was added for this and is marked the same way. Nothing else depends on
 * it and nothing reads its state.
 *
 * ⚠️ PACKAGE NAMES AND NUMBERS ONLY. The only identity recorded is `ControllerInfo.packageName`
 * for an external controller and the literal "ui" for our own surfaces. No track, no queue, no
 * session id, no uid, no metadata.
 *
 * ⚠️ IT REPORTS AT MOST ONCE PER PROCESS, AND DECIDES ON THE FIRST PLAY. [onPlay] latches on its
 * first call whatever it concludes, including "too old to be a cold start" - so a session that plays
 * normally for an hour costs one comparison and nothing else.
 *
 * ⚠⚠ IT CALLS recordException DIRECTLY, WHICH IS A THIRD SITE, DELIBERATELY. App.kt and
 * HealthMonitor are the only two permanent ones and that is still the rule. The alternative here would
 * be app.throwFlow, and that collector SHOWS THE USER A SNACKBAR - which would make a diagnostic into
 * a behaviour change. A temporary probe that must stay invisible has to bypass it.
 */
object ColdStartPlayProbe {

    /** The source name for our own UI. Reserved: an external controller is recorded by package. */
    private const val UI = "ui"

    /**
     * Within this much of process birth, the first play is interesting. The reported cases were all at
     * or near launch; 30 s is wide enough to cover a slow cold start and narrow enough that a user who
     * opens the app and presses play deliberately usually falls OUTSIDE it - and when they do not,
     * their press is recorded as a source anyway, so the report still does not fire.
     */
    private const val COLD_WINDOW_MS = 30_000L

    /**
     * How recently a request must have been recorded to count as the explanation for this play. The
     * session dispatches onPlayerCommandRequest synchronously before the player call, and our UI
     * records before sending, so the real gap is milliseconds; 2 s is slack, not a guess.
     */
    private const val SOURCE_WINDOW_MS = 2_000L

    /** Frames kept on the filed non-fatal. Enough to show the caller and its dispatcher. */
    private const val TRACE_FRAMES = 12

    @Volatile private var lastSource: String? = null
    @Volatile private var lastSourceAtMs = 0L

    /** Latched by the first [onPlay] of the process, whatever it decided. */
    @Volatile private var decided = false

    @Volatile private var androidAutoConnected: () -> Boolean = { false }
    @Volatile private var resumptionApplying: () -> Boolean = { false }

    /**
     * Supplies the two pieces of state the probe cannot see from here. Called once by PlayerService,
     * which owns both. Everything here runs on the application looper (the session callback, our UI,
     * and ShufflePlayer's overrides all do), so the volatiles are insurance rather than a mechanism.
     */
    fun attach(androidAuto: () -> Boolean, restoring: () -> Boolean) {
        androidAutoConnected = androidAuto
        resumptionApplying = restoring
    }

    /** An external controller asked for play. [packageName] is the only identity recorded. */
    fun recordController(packageName: String) = record(packageName)

    /** One of our own UI surfaces asked for play. */
    fun recordUi() = record(UI)

    private fun record(source: String) {
        lastSource = source
        lastSourceAtMs = SystemClock.elapsedRealtime()
    }

    /**
     * The player is about to start. [path] names which override saw it, so a breadcrumb can say
     * whether the request arrived as play() or as playWhenReady = true.
     */
    fun onPlay(path: String) {
        if (decided) return
        decided = true
        val ageMs = CrashKeys.processAgeMs()
        if (ageMs < 0L || ageMs > COLD_WINDOW_MS) return
        val source = lastSource
        val sinceSourceMs =
            if (source == null) -1L else SystemClock.elapsedRealtime() - lastSourceAtMs
        val restoring = runCatching { resumptionApplying() }.getOrDefault(false)
        val suffix = " path=" + path + " ageMs=" + ageMs + " restoring=" + restoring
        if (source != null && sinceSourceMs in 0..SOURCE_WINDOW_MS) {
            log("coldstart play source=" + source + " sinceSourceMs=" + sinceSourceMs + suffix)
            return
        }
        // Android Auto starts playback through paths that are not a controller play command, and the
        // item is phone-only, so a connected head unit is an explanation rather than a finding.
        if (runCatching { androidAutoConnected() }.getOrDefault(false)) {
            log("coldstart play source=androidauto" + suffix)
            return
        }
        report("no source within " + SOURCE_WINDOW_MS + "ms:" + suffix + " lastSource=" +
            (source ?: "none") + " sinceSourceMs=" + sinceSourceMs)
    }

    /**
     * ⚠️ THE TRIMMED TRACE IS THE WHOLE POINT OF FILING AN EXCEPTION RATHER THAN LOGGING. The
     * breadcrumb paths say WHICH override ran; only the stack says who called it, which is the
     * question the item has been open on. Frames are dropped from the top until this class is out of
     * the way, so the first frame is the caller and Crashlytics groups on it.
     *
     * The name survives R8 under proguard-rules rule 7 (`-keepnames class * extends
     * java.lang.Throwable`), so every occurrence groups as ColdStartAutoplayUnexplained rather than
     * one issue per build.
     */
    private fun report(message: String) {
        if (!BuildConfig.HAS_FIREBASE) return
        runCatching {
            val error = ColdStartAutoplayUnexplained(message)
            val all = Throwable().stackTrace
            val own = ColdStartPlayProbe::class.java.name
            val trimmed = all.dropWhile { it.className == own }.take(TRACE_FRAMES)
            error.stackTrace = (trimmed.ifEmpty { all.toList() }).toTypedArray()
            FirebaseCrashlytics.getInstance().recordException(error)
        }
    }

    private fun log(line: String) {
        if (!BuildConfig.HAS_FIREBASE) return
        runCatching { FirebaseCrashlytics.getInstance().log(line) }
    }
}

/**
 * ⚠️ [TEMPORARY 2026-10-19] Filed by [ColdStartPlayProbe] when a young process starts playing and
 * nothing recorded a request for it. Delete with the probe.
 */
class ColdStartAutoplayUnexplained(message: String) : Exception(message)
