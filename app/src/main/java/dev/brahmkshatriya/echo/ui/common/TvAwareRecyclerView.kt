package dev.brahmkshatriya.echo.ui.common

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import androidx.core.view.doOnLayout
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.brahmkshatriya.echo.utils.ui.UiUtils.isTv

class TvAwareRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : RecyclerView(context, attrs, defStyleAttr) {

    // Shared TV check (UI_MODE_TYPE_TELEVISION || FEATURE_LEANBACK). FEATURE_LEANBACK alone was false on
    // Google TV (which reports TELEVISION, not LEANBACK), silently disabling the cross-span DOWN focus logic
    // below — so on Google TV boxes the feed couldn't move focus past the first (span-1) shelf.
    val isTv = context.isTv()

    var navRailView: View? = null

    override fun focusSearch(focused: View, direction: Int): View {
        if (!isTv) return super.focusSearch(focused, direction)
        val glm = layoutManager as? GridLayoutManager
            ?: return super.focusSearch(focused, direction)
        val itemView = findContainingItemView(focused)
            ?: return super.focusSearch(focused, direction)
        val currentPos = getChildAdapterPosition(itemView)
        if (currentPos == NO_POSITION) return super.focusSearch(focused, direction)

        return when (direction) {
            FOCUS_DOWN -> {
                val lookup = glm.spanSizeLookup
                val spanCount = glm.spanCount
                val currentRowIndex = lookup.getSpanGroupIndex(currentPos, spanCount)
                var nextRowFirstPos = currentPos + 1
                while (nextRowFirstPos < (adapter?.itemCount ?: 0)) {
                    if (lookup.getSpanGroupIndex(nextRowFirstPos, spanCount) > currentRowIndex) break
                    nextRowFirstPos++
                }
                if (nextRowFirstPos < (adapter?.itemCount ?: 0)) {
                    val currentSpanSize = lookup.getSpanSize(currentPos)
                    val nextSpanSize = lookup.getSpanSize(nextRowFirstPos)
                    if (currentSpanSize != nextSpanSize) {
                        val targetView = glm.findViewByPosition(nextRowFirstPos)
                        if (targetView != null) targetView
                        else {
                            glm.scrollToPosition(nextRowFirstPos)
                            focused
                        }
                    } else super.focusSearch(focused, direction)
                } else super.focusSearch(focused, direction)
            }
            FOCUS_UP -> {
                if (currentPos == 0) focused
                else super.focusSearch(focused, direction)
            }
            FOCUS_LEFT -> {
                val spanIndex = glm.spanSizeLookup.getSpanIndex(currentPos, glm.spanCount)
                if (spanIndex == 0) navRailView ?: super.focusSearch(focused, direction)
                else super.focusSearch(focused, direction)
            }
            else -> super.focusSearch(focused, direction)
        }
    }

    // --- TV D-pad focus restoration across hide()/show() (lateral tab switch AND drill-down/back) ---
    // The nav (MainFragment tab hide/show, and FragmentUtils.openFragment which hide()s the caller on
    // drill-down) toggles the feed root's visibility. The platform restores RecyclerView SCROLL on
    // show(), but not which item held D-pad focus — so on return focus fell to the platform default and
    // the ring vanished. We remember the focused item's position chain (outer shelf + inner card) as
    // focus moves, and re-request it once the feed is visible and laid out again. TV-only: on phone
    // (isTv == false) both overrides just call super, so behavior is byte-identical.
    private var savedOuterPos = NO_POSITION
    private var savedInnerPos = NO_POSITION

    // requestChildFocus bubbles up here on EVERY focus change within the RV (inner rows call their
    // parent chain), so this records the live position without needing to read focus at hide() time
    // (by then GONE has already cleared it).
    override fun requestChildFocus(child: View?, focused: View?) {
        super.requestChildFocus(child, focused)
        if (!isTv || child == null || focused == null) return
        val outer = getChildAdapterPosition(child)
        if (outer == NO_POSITION) return
        savedOuterPos = outer
        // If this shelf hosts a horizontal card row, remember which card too; else NO_POSITION.
        val innerRv = focused.nearestRecyclerBelow()
        savedInnerPos = innerRv
            ?.let { rv -> rv.findContainingItemView(focused)?.let(rv::getChildAdapterPosition) }
            ?: NO_POSITION
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (!isVisible || !isTv || savedOuterPos == NO_POSITION) return
        // Don't steal focus if it currently sits OUTSIDE this feed — e.g. on the nav rail during a
        // lateral tab switch. Only restore when nothing is focused (returning from a popped fragment)
        // or focus is already inside us (framework dumped it on the default item).
        val current = rootView?.findFocus()
        if (current != null && current !== this && !current.isDescendantOfThis()) return
        restoreSavedFocus()
    }

    private fun restoreSavedFocus() {
        val outer = savedOuterPos
        if (outer >= (adapter?.itemCount ?: 0)) { requestFocus(); return }
        scrollToPosition(outer)
        doOnLayout {
            val itemView = layoutManager?.findViewByPosition(outer)
                ?: run { requestFocus(); return@doOnLayout }
            val inner = savedInnerPos
            val innerRv = if (inner != NO_POSITION) itemView.firstRecyclerOrSelf() else null
            if (innerRv != null) {
                innerRv.scrollToPosition(inner)
                innerRv.doOnLayout {
                    val card = innerRv.layoutManager?.findViewByPosition(inner)
                    if (card == null || !card.requestFocus()) itemView.requestFocus()
                }
            } else itemView.requestFocus()
        }
    }

    private fun View.isDescendantOfThis(): Boolean {
        var p: ViewParent? = parent
        while (p != null) {
            if (p === this@TvAwareRecyclerView) return true
            p = p.parent
        }
        return false
    }

    // Nearest RecyclerView ancestor of the focused view that sits below this outer RV (the shelf's
    // horizontal card row), or null if the focused item is a direct shelf (header/single item).
    private fun View.nearestRecyclerBelow(): RecyclerView? {
        var p: ViewParent? = parent
        while (p != null && p !== this@TvAwareRecyclerView) {
            if (p is RecyclerView) return p
            p = p.parent
        }
        return null
    }

    private fun View.firstRecyclerOrSelf(): RecyclerView? {
        if (this is RecyclerView) return this
        if (this is ViewGroup) for (i in 0 until childCount) {
            getChildAt(i).firstRecyclerOrSelf()?.let { return it }
        }
        return null
    }
}
