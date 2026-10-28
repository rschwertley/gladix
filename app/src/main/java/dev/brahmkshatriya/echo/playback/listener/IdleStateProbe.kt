package dev.brahmkshatriya.echo.playback.listener

import com.google.firebase.crashlytics.FirebaseCrashlytics
import dev.brahmkshatriya.echo.BuildConfig
import dev.brahmkshatriya.echo.utils.CrashKeys

/**
 * ⚠⚠ REPORTS THE ONE PLAYER STATE THAT IS A STUCK UI BY CONSTRUCTION: STATE_IDLE with
 * playWhenReady TRUE and no error. Build 1141, 06:15:18: a player that had just been resumed went
 * IDLE within ~90 ms and stayed there. dumpsys media_session showed state=NONE(0), position 56600,
 * error=null, 72 queued items, metadata present - and the transport showed PAUSE, so the user could
 * not even press play. ShufflePlayer.getPlaybackState only fakes READY when playWhenReady is FALSE,
 * so with it true the session reports the real IDLE and the button is wrong.
 *
 * ⚠⚠ THE CALLER IS THE WHOLE POINT, AND IT IS ONLY CAPTURABLE AT THE TRANSITION.
 * ExoPlayerImpl.updatePlaybackInfo calls listeners.flushEvents() INLINE (1.11.0), so
 * onPlaybackStateChanged runs on the stack of whatever called stop() - the caller is right there.
 * 250 ms later, when the guard decides, that stack is gone. Hence [onIdleEntered] records the
 * FRAMES and [onGuardFired] reports them.
 *
 * ⚠⚠ THE FRAMES TRAVEL AS THE REPORTED THROWABLE'S STACK TRACE, NOT AS A CUSTOM
 * KEY, AND THAT IS A RELEASE-BUILD REQUIREMENT RATHER THAN A STYLE CHOICE. R8 obfuscates our
 * classes - only Throwable subclasses keep their names, by the -keepnames rule added 2026-10-18 -
 * and Crashlytics DEOBFUSCATES STACK TRACES ONLY. A class name carried in a custom key arrives as
 * `a.b.c` and stays that way, because nothing maps keys back. So the captured frames are attached
 * with setStackTrace, exactly as ColdStartPlayProbe does, and the `by` key is kept only as a
 * convenience label for the issue list - it will read as minified, and that is expected.
 * [CORRECTED 2026-10-28] THE `by` KEY IS GONE, AND IT WAS NOT A HARMLESS LABEL. It named the
 * first frame [ourFrames] kept, and that was a MEDIA3 frame: on 1148, idle_by = hq1.s =
 * ForwardingPlayer$ForwardingListener.onPlaybackStateChanged - the listener dispatch, not the cause.
 * No in-process rule can name the cause reliably: R8 renames our classes AND the bundled androidx /
 * media3 ones, and Crashlytics deobfuscates only the stack. So the cause is read from the
 * deobfuscated stack - which is why it is now kept whole (see [TRACE_FRAMES]).
 * ⚠️ ONE LIMITATION, STATED SO A REPORT IS NOT OVER-READ: ListenerSet.flushEvents defers
 * when a flush is already in progress, so if stop() is called from inside another listener callback
 * the frame names the OUTER main-thread entry point rather than stop()'s direct caller. Still the
 * useful answer - it names which of our subsystems was running - but it is not proof of the exact
 * call site.
 *
 * ⚠️ REPORTS ONLY WHEN THE GUARD FIRES, WHICH IS WHAT KEEPS IT SILENT IN NORMAL USE.
 * Every recovery in the app passes through IDLE with playWhenReady true - the buffering
 * watchdog's stop/seek/prepare, the error paths' stop/prepare - but all of them complete inside
 * ONE main-thread task, so the 250 ms re-check never sees them. A report therefore means
 * something reached this state and nothing recovered it.
 *
 * ⚠️ WHAT EACH OUTPUT WOULD SETTLE, before the build. [CORRECTED 2026-10-28] "by=" below now
 * means "the deepest app or media3 frame that started the call in the deobfuscated stack"; the key
 * itself is gone. A notification swipe no longer reports at all - see armIdleSettle's third
 * stand-aside.
 *   by=<a watchdog or onPlayerError frame>  a recovery cleared the error with prepare() and then
 *       failed back to IDLE. The branch is named and the fix belongs there, not here.
 *   by=<no in-package frame>  media3 drove IDLE itself (renderer or release path), which is a
 *       different investigation and would not have been findable from a logcat line either.
 *   items=0  a queue clear, which the 1141 invariants had already excluded - would mean the guard's
 *       own precondition is wrong.
 *   NO REPORT EVER  the state does not occur in the field and both the guard and this probe should
 *       be deleted. That is a real possible outcome and the removal condition.
 *
 * ⚠️ recordException DIRECTLY - a third site, same reasoning StaleFocusProbe and
 * ColdStartPlayProbe give: the alternative, app.throwFlow, shows the user a snackbar, and a
 * diagnostic must not become a behaviour change.
 */
object IdleStateProbe {

    private var pendingFrames: Array<StackTraceElement>? = null
    private var pendingAtMs = 0L

    /**
     * Called from the IDLE transition itself, on the app thread. Cheap: one Throwable allocation
     * with no printing, only on a state change.
     */
    fun onIdleEntered(nowMs: Long) {
        pendingAtMs = nowMs
        val frames = runCatching { ourFrames() }.getOrNull()
        pendingFrames = frames?.takeIf { it.isNotEmpty() }
    }

    fun onGuardFired(
        nowMs: Long,
        position: Long,
        buffered: Long,
        items: Int,
        extensionId: String?,
    ) {
        val frames = pendingFrames
        val sinceMs = if (pendingAtMs == 0L) -1L else nowMs - pendingAtMs
        pendingFrames = null
        pendingAtMs = 0L
        if (!BuildConfig.HAS_FIREBASE) return
        runCatching {
            val crashlytics = FirebaseCrashlytics.getInstance()
            crashlytics.setCustomKey("idle_since_state_change_ms", sinceMs)
            crashlytics.setCustomKey("idle_position", position)
            crashlytics.setCustomKey("idle_buffered", buffered)
            crashlytics.setCustomKey("idle_items", items)
            crashlytics.setCustomKey("idle_extension", extensionId ?: "none")
            crashlytics.setCustomKey(
                "idle_process_ms", runCatching { CrashKeys.processAgeMs() }.getOrDefault(-1L)
            )
            val error = IdleWithPlayWhenReady("idle with playWhenReady, settled")
            // The frames from the TRANSITION, so the report is grouped and deobfuscated by the
            // code that drove IDLE rather than by this coroutine. Falls back to the current
            // stack only if the capture failed, which would at least identify the guard.
            val here = Throwable().stackTrace
            val own = IdleStateProbe::class.java.name
            val fallback = here.dropWhile { it.className == own }.take(TRACE_FRAMES)
            error.stackTrace = frames ?: fallback.ifEmpty { here.toList() }.toTypedArray()
            crashlytics.recordException(error)
        }
    }

    /** Dropped when the guard stands aside, so a later report cannot carry a stale frame. */
    fun clear() {
        pendingFrames = null
        pendingAtMs = 0L
    }

    /**
     * The nearest frame that is OURS, by EXCLUSION rather than by package prefix, and that choice
     * is the release-build one. R8 repackages this app, so matching
     * `startsWith("dev.brahmkshatriya.echo")` would match NOTHING in the only builds that ship -
     * the exact shape of the dead `mime` probe this file's sibling records. Platform and stdlib
     * namespaces are NOT renamed, so excluding them leaves our frames whatever they were renamed
     * to, and the result is triaged against mapping.txt like any other report.
     * [CORRECTED 2026-10-28] "PLATFORM AND STDLIB NAMESPACES ARE NOT RENAMED" WAS HALF WRONG. Only
     * what the device supplies keeps its name (android.*, java.*, dalvik.*). Libraries BUNDLED in the
     * APK - androidx, media3, kotlinx, com.google - are renamed like our own code: 1148's mapping
     * (decoded by the user) has ForwardingPlayer$ForwardingListener as hq1, and 1152's has androidx.work's
     * ForceStopRunnable as hq1. So the prefix exclusion kept media3 frames as "ours". It now drops only the two classes
     * whose RUNTIME names are known here (this probe and PlayerEventListener, the transition frames)
     * and keeps everything below them.
     */
    private fun ourFrames(): Array<StackTraceElement> {
        val own = IdleStateProbe::class.java.name
        val caller = PlayerEventListener::class.java.name
        return Throwable().stackTrace
            .dropWhile { it.className.startsWith(own) || it.className.startsWith(caller) }
            .take(TRACE_FRAMES)
            .toTypedArray()
    }

    /**
     * ⚠⚠ 64, WAS 12 - THE WHOLE MAIN-THREAD STACK, BECAUSE THE BOTTOM IS THE ANSWER. 1148's 12 frames
     * ran from the listener dispatch to MediaSessionStub and stopped there, hiding the one frame that
     * mattered: below stopForControllerInfo, applyMediaButtonKeyEvent / MediaSessionService.onStartCommand
     * means a media-button Intent (the swipe), while dispatchPlayerCommand / a Binder or Handler frame
     * means IPC. Counted for that path: ~20 frames from the dispatch to stopForControllerInfo, 2-4 for
     * the discriminating frames, ~10 more to ActivityThread.main - about 35. 64 is that with room for a
     * nested or deferred flush, and still a bound on a pathological stack.
     */
    private const val TRACE_FRAMES = 64
}

/**
 * Filed by [IdleStateProbe] when the idle-settle guard found the player in STATE_IDLE with
 * playWhenReady still true, no error and a non-empty queue, 250 ms after the transition - i.e. a
 * transport stuck showing Pause with nothing playing, which the guard then settled to Pause so one
 * tap can recover it.
 */
class IdleWithPlayWhenReady(message: String) : Exception(message)
