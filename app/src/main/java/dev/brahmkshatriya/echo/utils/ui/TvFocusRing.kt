package dev.brahmkshatriya.echo.utils.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
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
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.RelativeCornerSize
import com.google.android.material.shape.ShapeAppearanceModel
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
 * [2026-11-02] TWO KINDS OF VIEW NO LONGER GET THE OVERLAY RING, because a fixed pill or rectangle cannot match them:
 * - A MaterialButton whose shape changes with state (every Widget.Material3Expressive.Button style: its
 *   shapeAppearance is m3expressive_button_shape_state_list - pressed CornerSmall, checked CornerMedium, else Full,
 *   material-1.14.0 values.xml), e.g. the selected Library tab, a rounded rectangle inside a pill ring. Its focus is
 *   drawn as the button's OWN stroke (4dp, colorPrimary), restored on blur. MaterialButtonHelper.updateStroke sets
 *   the stroke on the same MaterialShapeDrawable that updateButtonShape gives the state-list shape and corner spring
 *   (decoded from 1.14.0), so the ring follows every state and the morph between them. Picked by
 *   ShapeAppearance.isStateful (false for a fixed ShapeAppearanceModel); getShapeAppearance throws when the button's
 *   background was replaced, which counts as fixed. A button with its own foreground still keeps that instead.
 * - A nav rail item: one ring around its active-indicator pill (same shape, 2dp outside it, so it shows on the
 *   selected item's pink fill too) instead of a rectangle round icon and label. The indicator keeps its full layout
 *   bounds on every item - selection only animates its scale and alpha (NavigationBarItemView
 *   .ActiveIndicatorTransform.updateForProgress, 1.14.0) - so the ring sits where the pill is, selected or not.
 *   The rail's own focus wash is gone with it (tv_nav_item_ripple's focused state is now transparent).
 * [CORRECTED 2026-11-02] "nav rail items" in the first paragraph's list: they get the indicator ring above, not
 * this overlay's ring.
 * [CORRECTED 2026-11-02, later] "so the ring sits where the pill is" was wrong on the TV rail: it was centred on the
 * indicator's LAYOUT box, while the pill draws with the icon container's bounds (taller here), so the ring sat ~2dp
 * high - seen on the 1164 device (bottom touching, gap on top). It now uses the drawn bounds; see drawnPillBounds.
 * [CORRECTED 2026-11-02, later] "e.g. the selected Library tab, a rounded rectangle" no longer holds on TV: the tabs
 * and the feed's search toggle use values-television's TabButton / ToggleIconButton, a full pill in every state. Their
 * shape is still a state LIST (xml/tv_button_shape_pill), so they still take this stroke path.
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
        // The same width and colour as tv_focus_ring_pill (4dp, ?attr/colorPrimary in this window's theme).
        val ringWidth = 4.dpToPx(context)
        val ringColor = MaterialColors.getColor(context, androidx.appcompat.R.attr.colorPrimary, Color.TRANSPARENT)
        val railGap = 2.dpToPx(context)
        var current: View? = null
        var ring: Drawable? = null
        // Where the ring goes in `current`; re-run when it, or a rail item's indicator, is laid out again.
        var place: (() -> Unit)? = null
        var followed: List<View> = emptyList()
        // The stroke a focused shape-changing button had before, put back when focus leaves it.
        var stroked: MaterialButton? = null
        var savedStrokeWidth = 0
        var savedStrokeColor: ColorStateList? = null
        val follow = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            place?.invoke()
            current?.invalidate()
        }
        // Rail ring only: re-placed just before every frame, because the pill's drawn bounds can change with no layout
        // change at all (see drawnPillBounds). Invalidates only when the ring actually moved.
        var preDraw: ViewTreeObserver.OnPreDrawListener? = null
        root.viewTreeObserver.addOnGlobalFocusChangeListener(ViewTreeObserver.OnGlobalFocusChangeListener { _, new ->
            current?.let { old -> ring?.let { old.overlay.remove(it) } }
            followed.forEach { it.removeOnLayoutChangeListener(follow) }
            preDraw?.let { listener -> root.viewTreeObserver.takeIf { it.isAlive }?.removeOnPreDrawListener(listener) }
            preDraw = null
            stroked?.run {
                strokeWidth = savedStrokeWidth
                strokeColor = savedStrokeColor
            }
            current = null
            ring = null
            place = null
            followed = emptyList()
            stroked = null
            if (new == null) return@OnGlobalFocusChangeListener
            val button = new.shapeChangingButton()
            if (button != null) {
                savedStrokeWidth = button.strokeWidth
                savedStrokeColor = button.strokeColor
                button.strokeWidth = ringWidth
                button.strokeColor = ColorStateList.valueOf(ringColor)
                stroked = button
                return@OnGlobalFocusChangeListener
            }
            val indicator = new.railIndicator()
            val drawable: Drawable
            if (indicator != null) {
                drawable = indicatorRing(indicator, ringWidth, ringColor)
                val outset = railGap + ringWidth
                place = {
                    val box = indicator.boundsIn(new)
                    val drawn = indicator.drawnPillBounds()
                    drawable.bounds = Rect(box.left + drawn.left, box.top + drawn.top, box.left + drawn.right, box.top + drawn.bottom)
                        .apply { inset(-outset, -outset) }
                }
                followed = listOf(new, indicator)
                val placed = Rect()
                preDraw = ViewTreeObserver.OnPreDrawListener {
                    placed.set(drawable.bounds)
                    place?.invoke()
                    if (placed != drawable.bounds) new.invalidate()
                    true
                }.also { root.viewTreeObserver.addOnPreDrawListener(it) }
            } else {
                if (!needsRing(new)) return@OnGlobalFocusChangeListener
                drawable = if (new.height <= pillMaxHeight) pill else rect
                place = { drawable.setBounds(0, 0, new.width, new.height) }
                followed = listOf(new)
            }
            place?.invoke()
            new.overlay.add(drawable)
            followed.forEach { it.addOnLayoutChangeListener(follow) }
            current = new
            ring = drawable
        })
    }

    private fun View.shapeChangingButton(): MaterialButton? = (this as? MaterialButton)?.takeIf {
        it.foreground == null && runCatching { it.shapeAppearance.isStateful }.getOrDefault(false)
    }

    // A nav rail (NavigationBarItemView) item's active-indicator view, found by Material's own id.
    private fun View.railIndicator(): View? =
        findViewById<View>(com.google.android.material.R.id.navigation_bar_item_active_indicator_view)
            ?.takeIf { it !== this }

    // The indicator's own shape (its background, a MaterialShapeDrawable built from itemActiveIndicatorStyle),
    // stroked only; a full pill if the background is ever something else.
    private fun indicatorRing(indicator: View, width: Int, color: Int): Drawable {
        val shape = (indicator.background as? MaterialShapeDrawable)?.shapeAppearanceModel
            ?: ShapeAppearanceModel.builder().setAllCornerSizes(RelativeCornerSize(0.5f)).build()
        return MaterialShapeDrawable(shape).apply {
            fillColor = ColorStateList.valueOf(Color.TRANSPARENT)
            setStroke(width.toFloat(), color)
        }
    }

    /**
     * ⚠⚠ WHERE THE PILL IS ACTUALLY DRAWN, in the indicator's own coordinates - NOT its layout box (2026-11-02, the
     * 1164 ring sat ~2dp high: bottom touching the pill, a gap on top). Material 1.14.0's
     * NavigationBarItemView.refreshItemBackground (decoded) builds the icon container's FOREGROUND as
     * RippleDrawable(itemRippleColor, null, mask = getActiveIndicatorDrawable()), and that getter returns the
     * indicator view's own background - the SAME MaterialShapeDrawable instance. FrameLayout sizes its foreground
     * to the icon container, and RippleDrawable (a LayerDrawable) gives its mask layer those bounds; the indicator
     * view resets its background's bounds only when its own size changes (View.setBackgroundBounds, API 34). So
     * after the first frame the pill draws at the indicator's origin with the ICON CONTAINER's size. On the TV rail
     * the 36dp icon (NavViewTv itemIconSize) makes that container 36dp tall around a 32dp indicator
     * (BottomNavbarActiveIndicator), centred on it: the drawn pill starts at the indicator's top and runs 4dp
     * further down, centre 2dp lower than the box this ring was centred on. Widths are equal, hence no horizontal
     * error. Reading the drawable's current bounds follows whatever Material does; the layout box is the fallback
     * before anything has been drawn. Unselected items draw the same bounds (only scaled and faded), so the ring
     * sits where their pill appears.
     */
    private fun View.drawnPillBounds(): Rect =
        background?.bounds?.takeUnless { it.isEmpty }?.let(::Rect) ?: Rect(0, 0, width, height)

    // This view's layout rect in [ancestor]'s coordinates. Layout positions only: the indicator's scale and alpha
    // animation is deliberately left out (see the class note).
    private fun View.boundsIn(ancestor: View): Rect {
        var x = 0
        var y = 0
        var v: View = this
        while (v !== ancestor) {
            val parent = v.parent as? View ?: break
            x += v.left - parent.scrollX
            y += v.top - parent.scrollY
            v = parent
        }
        return Rect(x, y, x + width, y + height)
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
