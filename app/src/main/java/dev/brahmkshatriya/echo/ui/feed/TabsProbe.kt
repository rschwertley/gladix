package dev.brahmkshatriya.echo.ui.feed

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.firebase.crashlytics.FirebaseCrashlytics
import dev.brahmkshatriya.echo.BuildConfig
import dev.brahmkshatriya.echo.utils.ui.UiUtils.isTv
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicInteger

/**
 * ⚠⚠ TEMPORARY PROBE (2026-10-31, approved): the TV Library sub-tab bounce. OK on Playlists/Albums/Tracks returns the
 * selection to All and the content never changes (1156, 1158, 1159), and every fix made from reading the code -
 * the Jul 29 key listener, 1159's chosenTabId, tabsFlow skipping the empty pair, the payload rebind, the TV refocus -
 * has failed. This records what actually happens, through Crashlytics because adb does not work on the TV:
 * breadcrumbs tagged GladixTabs, uploaded by a marker non-fatal (GladixTabsProbe) ~3 s after OK on a tab, at most
 * [MAX_MARKERS] per process.
 * [CORRECTED 2026-11-01] "~3 s after OK on a tab" was the defect: the marker was scheduled from the tab button's own
 * key listener, so a press that never reached the button - the very thing under suspicion - produced no report at
 * all, and none arrived from 1160. The marker now comes from MainActivity.dispatchKeyEvent (onActivityKey), before
 * any view sees the key, on app-level scope so no rebind or bounce can cancel it; and from leaving the Library
 * (onLibraryLeft) when no marker was recorded during that visit, so a visit with no OK seen at all still reports.
 * The button's listener keeps its breadcrumbs (markAfterOk is now a breadcrumb, not a marker).
 * ⚠️ A NON-FATAL UPLOADS ON THE NEXT APP LAUNCH (Crashlytics SDK behaviour, not read from source here), so a report
 * can only appear after the TV app has been restarted once. That alone could explain the silence from 1160.
 * TV ONLY: [enabled] is set by LibraryFragment from Context.isTv(); on the phone every call returns at once.
 * Candidate (3) - the selection written to a different FeedViewModel/FeedData than the one displayed - is ruled out
 * by reading (LibraryFragment and FeedClickListener resolve viewModel<FeedViewModel>() on the same fragment); the
 * identity lines are logged anyway, so the report confirms it.
 * Caller stacks and CLASS NAMES in breadcrumbs are R8-obfuscated in release (breadcrumb text is not retraced);
 * retrace them with the build's mapping.txt. Resource id names and a button's text are not obfuscated, and are what
 * identify a view without the mapping.
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

    /**
     * Was the marker; now a breadcrumb ~2 s after OK reached a tab button (before the activity-level marker at ~3 s,
     * so it is attached to it). [state] is read when it fires.
     */
    fun markAfterOk(tabIndex: Int, state: () -> String) {
        if (!enabled || !BuildConfig.HAS_FIREBASE) return
        Handler(Looper.getMainLooper()).postDelayed({
            log("tab listener saw OK on tab $tabIndex, 2 s later: ${runCatching(state).getOrDefault("?")}")
        }, 2000)
    }

    // ---- Activity-level trigger (2026-11-01). Main thread only, except the scheduled record. ----

    private var scope: CoroutineScope? = null
    private var library: WeakReference<View>? = null
    private var visitOpen = false
    private var markerThisVisit = false
    private var okThisVisit = 0

    private val OK_KEYS = setOf(
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A
    )

    /** MainActivity.onCreate, TV only: the app-level scope the marker is recorded from. */
    fun attach(appScope: CoroutineScope) {
        scope = appScope
    }

    /** LibraryFragment on resume / un-hide: a visit starts. */
    fun onLibraryShown(root: View) {
        if (!enabled) return
        library = WeakReference(root)
        visitOpen = true
        markerThisVisit = false
        okThisVisit = 0
        log("visit start")
    }

    /** LibraryFragment on pause / hide / view destroyed, or the first key after its view stopped being shown. */
    fun onLibraryLeft(how: String) {
        if (!enabled || !visitOpen) return
        visitOpen = false
        log("visit end how=$how okPresses=$okThisVisit")
        if (!markerThisVisit) record("left Library ($how) with okPresses=$okThisVisit", null, 0)
        markerThisVisit = true
    }

    /**
     * MainActivity.dispatchKeyEvent, TV only, BEFORE any view sees the key. Every OK-type DOWN and UP while the
     * Library is shown: a breadcrumb with the focused view's chain. The first one in a visit schedules the marker.
     */
    fun onActivityKey(activity: Activity, event: KeyEvent) {
        if (!enabled || !BuildConfig.HAS_FIREBASE) return
        val showing = library?.get()?.isShown == true
        if (visitOpen && !showing) {
            onLibraryLeft("not-shown-at-key")
            return
        }
        if (!showing || event.keyCode !in OK_KEYS) return
        val action = when (event.action) {
            KeyEvent.ACTION_DOWN -> "down"
            KeyEvent.ACTION_UP -> "up"
            else -> return
        }
        okThisVisit++
        log(
            "activityKey $action code=${event.keyCode} repeat=${event.repeatCount} " +
                "focus=${describeFocus(activity.currentFocus)}"
        )
        if (!markerThisVisit) {
            markerThisVisit = true
            record("first OK in visit ($action code=${event.keyCode})", WeakReference(activity), 3000)
        }
    }

    private fun record(reason: String, activity: WeakReference<Activity>?, delayMs: Long) {
        if (!BuildConfig.HAS_FIREBASE) return
        if (markers.incrementAndGet() > MAX_MARKERS) return
        val target = scope ?: return
        target.launch {
            delay(delayMs)
            val focus = activity?.let {
                withContext(Dispatchers.Main) {
                    runCatching { describeFocus(it.get()?.currentFocus) }.getOrDefault("?")
                }
            }
            runCatching {
                FirebaseCrashlytics.getInstance().recordException(
                    GladixTabsProbe(
                        "TV Library tabs probe: $reason" + (focus?.let { ", ${delayMs / 1000} s later focus=$it" } ?: "")
                    )
                )
            }
        }
    }

    /**
     * The focused view and its parents up to (and including) the nearest RecyclerView, else to the root: class
     * (obfuscated in release), resource id name, and for a TextView its text - a tab's title. A MaterialButton adds
     * checked=; a view inside a RecyclerView row adds that row's adapter position.
     */
    private fun describeFocus(focused: View?): String {
        focused ?: return "none"
        fun View.label(): String {
            val name = runCatching { if (id != View.NO_ID) resources.getResourceEntryName(id) else null }.getOrNull()
            val text = (this as? TextView)?.text?.toString()?.take(24)
            return buildString {
                append(javaClass.simpleName)
                name?.let { append('#').append(it) }
                text?.let { append("\"").append(it).append("\"") }
                (this@label as? MaterialButton)?.let { append(" checked=").append(it.isChecked) }
            }
        }
        val chain = mutableListOf(focused.label())
        var child: View = focused
        var parent = focused.parent
        while (parent is View) {
            if (parent is RecyclerView) {
                chain += "${parent.label()}[pos=${parent.getChildAdapterPosition(child)}]"
                break
            }
            chain += parent.label()
            child = parent
            parent = parent.parent
        }
        return chain.joinToString(" < ")
    }

    class GladixTabsProbe(message: String) : Exception(message)
}
