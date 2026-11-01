package dev.brahmkshatriya.echo.utils.ui

import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.View
import android.view.ViewTreeObserver
import android.view.Window
import android.widget.AbsListView
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.slider.Slider
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.utils.ui.UiUtils.dpToPx

/**
 * ⚠⚠ THE TV FOCUS RING FOR EVERY VIEW THAT DOES NOT DRAW ITS OWN (2026-11-01, 1158 TV sweep item #19).
 * Cards, rows and a list of buttons get the pink ring from an android:foreground selector (tv_focused_list_item,
 * tv_focus_pill, tv_focus_indicator) set in TV layouts or in code. Everything else - the main tabs' extension and
 * account icons, toolbar navigation and menu buttons, sheet headers, DialogFragment rows and buttons, chips,
 * Settings rows, nav rail items - fell back to the theme's ripple focus state, a faint white wash. So this draws the
 * same ring (tv_focus_ring_pill / tv_focus_ring_rect) in the FOCUSED view's ViewOverlay, from one global focus
 * listener per window, instead of a foreground on each view.
 * WHY AN OVERLAY, NOT A STYLE: the two toolbar icons are MaterialCardViews, whose helper replaces any
 * android:foreground with its own ripple, and toolbar menu items are framework-built views no layout reaches.
 * SKIPPED, so nothing gets two rings: a view with its own foreground (every explicit android:foreground in the app
 * is one of the three TV rings - checked 2026-11-01 - except MaterialCardView's internal one), scroll containers
 * (a focused list is not a target), text fields (TextInputLayout's focused stroke) and sliders (their focus halo,
 * tv_slider_focus_selector on the player).
 * Shape: the pill for anything up to 64dp tall (buttons, icons, chips, toolbar items - all pill or round in M3),
 * the card/row ring above that.
 * WINDOWS: MainActivity's, and every DialogFragment's (bottom sheets included) via a recursive fragment lifecycle
 * callback. A plain AlertDialog shown without a fragment has no hook; its buttons get the same pill ring from the
 * TV dialog button style (values-television/styles.xml).
 * TV ONLY: installed from MainActivity behind isTv(). Phone - touch or hardware keyboard - is not touched.
 * ⚠️ IT ONLY RINGS WHAT CAN TAKE FOCUS. Below API 26 a clickable view is NOT focusable by default (focusable="auto"
 * arrived in 26), so on an Android 7 TV the rows and cards clicked through code were unreachable with the D-pad.
 * The layout views with a click listener and no focusable attribute now say android:focusable="true" (2026-11-01:
 * the main header's extension/account cards, dialog_settings' two cards, dialog_sort's two rows, the Audio effects
 * rows, the add-colour card, item_selectable_header); on 26+ that is what "auto" already resolved to, so nothing
 * changes there. Rows styled @style/Selectable already set it. Plain dialog LIST rows are drawn by the ListView's
 * selector, not focused individually - see tv_focus_list_selector.
 */
object TvFocusRing {

    fun install(activity: FragmentActivity) {
        install(activity.window)
        activity.supportFragmentManager.registerFragmentLifecycleCallbacks(
            object : FragmentManager.FragmentLifecycleCallbacks() {
                override fun onFragmentViewCreated(
                    fm: FragmentManager, f: Fragment, v: View, savedInstanceState: Bundle?
                ) {
                    (f as? DialogFragment)?.dialog?.window?.let(::install)
                }
            }, true
        )
    }

    private fun install(window: Window) {
        val root = window.decorView
        // The window's own context (the Activity, or the dialog's themed wrapper), so ?attr/colorPrimary resolves
        // in the theme that window is drawn with.
        val context = window.context
        val pill = AppCompatResources.getDrawable(context, R.drawable.tv_focus_ring_pill) ?: return
        val rect = AppCompatResources.getDrawable(context, R.drawable.tv_focus_ring_rect) ?: return
        val pillMaxHeight = 64.dpToPx(context)
        var current: View? = null
        var ring: Drawable? = null
        val follow = View.OnLayoutChangeListener { v, l, t, r, b, _, _, _, _ ->
            ring?.setBounds(0, 0, r - l, b - t)
            v.invalidate()
        }
        root.viewTreeObserver.addOnGlobalFocusChangeListener(ViewTreeObserver.OnGlobalFocusChangeListener { _, new ->
            current?.let { old ->
                ring?.let { old.overlay.remove(it) }
                old.removeOnLayoutChangeListener(follow)
            }
            current = null
            ring = null
            if (new == null || !needsRing(new)) return@OnGlobalFocusChangeListener
            val drawable = if (new.height <= pillMaxHeight) pill else rect
            drawable.setBounds(0, 0, new.width, new.height)
            new.overlay.add(drawable)
            new.addOnLayoutChangeListener(follow)
            current = new
            ring = drawable
        })
    }

    private fun needsRing(view: View) = when {
        view is MaterialCardView -> true
        view.foreground != null -> false
        // AbsListView too (2026-11-01): a focused ListView draws its row ring through its selector
        // (tv_focus_list_selector in dialogs), so a ring round the whole list would be a second one.
        view is RecyclerView || view is AbsListView || view is ScrollView || view is NestedScrollView ||
            view is HorizontalScrollView -> false
        view is EditText || view is Slider -> false
        else -> true
    }
}
