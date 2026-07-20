package dev.brahmkshatriya.echo.ui.common

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import androidx.core.view.doOnLayout
import androidx.core.view.doOnNextLayout
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

    override fun focusSearch(focused: View, direction: Int): View? {
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
                        // Different span (card grid -> non-focusable header / next shelf). Delegate to
                        // RecyclerView's native focusSearch: its onFocusSearchFailed + the
                        // !result.hasFocusable() path scroll a non-focusable header on-screen and keep
                        // focus so the next press continues past it. Our custom advance suppressed that
                        // recovery and stranded focus at the block ("can't scroll past first card block").
                        // Fall back to advanceFocusDown ONLY when super finds nothing AND the target is
                        // actually focusable — never advance across a non-focusable boundary.
                        super.focusSearch(focused, direction) ?: run {
                            if (glm.findViewByPosition(nextRowFirstPos)?.hasFocusable() == true)
                                advanceFocusDown(glm, nextRowFirstPos, focused)
                            else focused
                        }
                    } else {
                        // Same span (card grid row -> row): let the platform beam-search handle it
                        // column-aligned while the next row is laid out; only when it's offscreen
                        // (super returns null) do we scroll in the column-aligned target and follow.
                        super.focusSearch(focused, direction)
                            ?: advanceFocusDown(
                                glm,
                                columnAlignedTarget(lookup, currentPos, nextRowFirstPos, spanCount),
                                focused,
                            )
                    }
                } else focused
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

    // Column-aligned target in the next row for a same-span DOWN: the item whose span index matches
    // the current column, clamped to the last item of that row. The walk stops at the next row's span
    // group boundary, so a short final row falls back to its last card instead of overshooting into a
    // later span group. Matches on span index (not position offset) so it is correct for span>1 too.
    // Bounded by spanCount iterations; span index/group lookups are cached.
    private fun columnAlignedTarget(
        lookup: GridLayoutManager.SpanSizeLookup,
        currentPos: Int,
        nextRowFirstPos: Int,
        spanCount: Int,
    ): Int {
        val col = lookup.getSpanIndex(currentPos, spanCount)
        val nextRowGroup = lookup.getSpanGroupIndex(nextRowFirstPos, spanCount)
        val count = adapter?.itemCount ?: 0
        var target = nextRowFirstPos
        var p = nextRowFirstPos
        while (p < count && lookup.getSpanGroupIndex(p, spanCount) == nextRowGroup) {
            if (lookup.getSpanIndex(p, spanCount) <= col) target = p else break
            p++
        }
        return target
    }

    // Focus-follow DOWN advance shared by both boundary branches in focusSearch. The old code either
    // scrolled without moving focus (different-span) or had no advance at all (same-span fell to bare
    // super); both stranded focus on the current card when the next row was offscreen. Unified here:
    // if the target is laid out, return it so the framework moves focus this frame; if it's offscreen,
    // scroll it in and request focus once the scroll's single re-anchored layout pass binds it.
    // TV-only: only reachable from the isTv-gated FOCUS_DOWN path, so phone never calls it.
    private var pendingFocusPos = NO_POSITION

    private fun advanceFocusDown(glm: GridLayoutManager, targetPos: Int, focused: View): View {
        if (targetPos < 0 || targetPos >= (adapter?.itemCount ?: 0)) return focused
        glm.findViewByPosition(targetPos)?.let { return it }        // laid out -> framework focuses it
        // Offscreen: scrollToPosition re-anchors layout on targetPos, so ONE next layout pass binds
        // it regardless of distance (unlike smoothScroll's many frames -> no scroll listener needed).
        pendingFocusPos = targetPos
        glm.scrollToPosition(targetPos)
        doOnNextLayout {
            if (pendingFocusPos != targetPos) return@doOnNextLayout  // superseded by a later DOWN press
            pendingFocusPos = NO_POSITION
            glm.findViewByPosition(targetPos)?.requestFocus()        // fresh lookup; no-op if unbound
        }
        return focused
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

    // ---- Generalized TV focus anchoring (#6 cold start, #7 player-collapse, #8 warm resume) ----
    // Target is ALWAYS saved-if-valid-else-first, so first-item is a fallback within one resolver,
    // never a path that can override a valid save (see maybeInitialAnchor). Layout-aware and
    // idempotent via anchorGen. Separate from the Group-1 DOWN token (pendingFocusPos).
    // NOTE: #8 (warm resume) is NOT triggered here — it is routed through the single MainActivity
    // window-focus arbiter, which calls establishFeedFocus below. This view has no onWindowFocusChanged.
    private var anchorGen = 0
    private var didInitialAnchor = false

    // Public entry for external triggers (#7 collapse, #8 arbiter). [allowClaim] is the caller's
    // no-steal decision. Target = saved position if valid, else first shelf.
    fun establishFeedFocus(allowClaim: Boolean) {
        if (!isTv || !allowClaim) return
        val count = adapter?.itemCount ?: 0
        if (count == 0) return
        anchorFocusAt(if (savedOuterPos in 0 until count) savedOuterPos else 0)
    }

    private fun anchorFocusAt(target: Int) {
        if (target >= (adapter?.itemCount ?: 0)) { requestFocus(); return }
        val gen = ++anchorGen
        scrollToPosition(target)
        doOnLayout {
            if (gen != anchorGen) return@doOnLayout              // superseded by a later anchor
            val itemView = layoutManager?.findViewByPosition(target)
                ?: run { requestFocus(); return@doOnLayout }
            val inner = if (target == savedOuterPos) savedInnerPos else NO_POSITION
            val innerRv = if (inner != NO_POSITION) itemView.firstRecyclerOrSelf() else null
            if (innerRv != null) {
                innerRv.scrollToPosition(inner)
                innerRv.doOnLayout {
                    if (gen != anchorGen) return@doOnLayout
                    val card = innerRv.layoutManager?.findViewByPosition(inner)
                    if (card == null || !card.requestFocus()) itemView.requestFocus()
                }
            } else itemView.requestFocus()
        }
    }

    // #6 cold start: land initial focus once the feed first has content. Uses the same saved-or-first
    // resolver, so if a save is already present it RESTORES it rather than defaulting to first.
    private val initialAnchorObserver = object : RecyclerView.AdapterDataObserver() {
        override fun onChanged() = maybeInitialAnchor()
        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = maybeInitialAnchor()
    }

    private fun maybeInitialAnchor() {
        if (!isTv || didInitialAnchor || (adapter?.itemCount ?: 0) == 0) return
        didInitialAnchor = true
        establishFeedFocus(allowClaim = rootView?.findFocus() == null)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (isTv) runCatching { adapter?.registerAdapterDataObserver(initialAnchorObserver) }
    }

    override fun onDetachedFromWindow() {
        if (isTv) runCatching { adapter?.unregisterAdapterDataObserver(initialAnchorObserver) }
        super.onDetachedFromWindow()
    }

    private fun restoreSavedFocus() = anchorFocusAt(savedOuterPos)

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
