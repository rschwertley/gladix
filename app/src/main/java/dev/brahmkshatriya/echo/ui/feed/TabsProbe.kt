package dev.brahmkshatriya.echo.ui.feed

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.firebase.crashlytics.FirebaseCrashlytics
import dev.brahmkshatriya.echo.BuildConfig
import dev.brahmkshatriya.echo.utils.ui.UiUtils.isTv
import java.util.concurrent.atomic.AtomicInteger

/**
 * ⚠⚠ TEMPORARY PROBE (2026-10-31, approved): the TV Library sub-tab bounce. OK on Playlists/Albums/Tracks returns the
 * selection to All and the content never changes (1156, 1158, 1159), and every fix made from reading the code -
 * the Jul 29 key listener, 1159's chosenTabId, tabsFlow skipping the empty pair, the payload rebind, the TV refocus -
 * has failed. This records what actually happens, through Crashlytics because adb does not work on the TV:
 * breadcrumbs tagged GladixTabs, uploaded by a marker non-fatal (GladixTabsProbe) ~3 s after OK on a tab, at most
 * [MAX_MARKERS] per process.
 * TV ONLY: [enabled] is set by LibraryFragment from Context.isTv(); on the phone every call returns at once.
 * Candidate (3) - the selection written to a different FeedViewModel/FeedData than the one displayed - is ruled out
 * by reading (LibraryFragment and FeedClickListener resolve viewModel<FeedViewModel>() on the same fragment); the
 * identity lines are logged anyway, so the report confirms it.
 * Caller stacks in breadcrumbs are R8-obfuscated in release (breadcrumb text is not retraced); retrace them with the
 * build's mapping.txt. Each write also carries an explicit site label, which is not obfuscated.
 * REMOVE (this file and every TabsProbe call) once ONE report has been read and the cause named.
 */
object TabsProbe {
    @Volatile
    var enabled = false
        private set

    fun enableIfTv(context: Context) {
        enabled = context.isTv()
    }

    fun log(message: String) {
        if (!enabled || !BuildConfig.HAS_FIREBASE) return
        runCatching { FirebaseCrashlytics.getInstance().log("GladixTabs $message") }
    }

    fun id(any: Any?) = any?.let { "${it.javaClass.simpleName}@${Integer.toHexString(System.identityHashCode(it))}" }

    // The four frames above the caller of this function, newest first.
    fun caller(): String = Throwable().stackTrace.drop(2).take(4)
        .joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }

    private const val MAX_MARKERS = 3
    private val markers = AtomicInteger(0)

    /** Schedules the upload marker ~3 s after OK on a tab; [state] is read when it fires. */
    fun markAfterOk(tabIndex: Int, state: () -> String) {
        if (!enabled || !BuildConfig.HAS_FIREBASE) return
        if (markers.incrementAndGet() > MAX_MARKERS) return
        Handler(Looper.getMainLooper()).postDelayed({
            runCatching {
                FirebaseCrashlytics.getInstance().recordException(
                    GladixTabsProbe("TV Library tabs probe: OK on tab $tabIndex, 3 s later: ${state()}")
                )
            }
        }, 3000)
    }

    class GladixTabsProbe(message: String) : Exception(message)
}
