package dev.brahmkshatriya.echo.utils.ui

import android.graphics.Canvas
import android.view.MotionEvent
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.AppBarLayout
import me.zhanghai.android.fastscroll.FastScroller
import me.zhanghai.android.fastscroll.Predicate
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Scroll metrics for the fast scroller.
 *
 * [2026-11-13] THEY COME FROM A HEIGHT MODEL NOW, NOT FROM RecyclerView's SCROLLBAR ESTIMATE (the user's approved design of
 * 2026-11-13). The thumb's position ([getScrollOffset], [getScrollRange]) and the drag's target ([scrollTo]) read ONE model,
 * so they cannot disagree: see [HEIGHT MODEL] below for what it is, and [scrollTo] for how a target is reached.
 *
 * ## The contract a ViewHelper must satisfy
 *
 * Two implementations ship with AndroidFastScroll (RecyclerViewHelper, and SimpleViewHelper for ScrollView /
 * NestedScrollView / WebView), and comparing them is what defines the interface's real contract, since none of it is
 * documented:
 * 1. `getScrollRange()`, `getScrollOffset()` and `scrollTo()`'s argument are ONE unit space.
 * 2. `getScrollRange() - view.getHeight()` must be the reachable maximum of `getScrollOffset()`.
 *    FastScroller.getScrollOffsetRange() hardcodes that subtraction, so the space has to be one in
 *    which the viewport extent IS the view height — i.e. PIXELS.
 * 3. `scrollTo(x)` then `getScrollOffset()` must return ~x. FastScroller keeps no memory of the
 *    finger: updateScrollbarState recomputes
 *    `mThumbOffset = thumbOffsetRange * getScrollOffset() / scrollOffsetRange` on every frame, and
 *    onPreDraw lays the thumb at `padding.top + mThumbOffset`. So the thumb tracks the finger only
 *    if the round trip is faithful. (Read again 2026-11-13 from the v1.3.0 source, FastScroller.java: ACTION_UP and
 *    ACTION_CANCEL only call setDragging(false), so what the thumb does at release is entirely this class's arithmetic.)
 *
 * ## Why the previous position-based version could not work
 *
 * It expressed range and offset in adapter positions. Requirement 2 then needs "how many items fit"
 * to stand in for the view height, which is unknowable without measuring every item — that was the
 * `extent` term, and it caused every round of this: read live it moved the denominator mid-drag
 * (jumping); cached it went stale and clamped the offset (thumb pinning); the shrink guard was a
 * patch on the patch. Requirement 2 is not satisfiable in position space. Do not go back to it.
 * [2026-11-13] The height model is PIXEL space with positions only as its index, so this still holds.
 *
 * ## Why not the library's own RecyclerViewHelper
 *
 * It estimates every item's height from `getChildAt(0)` alone:
 * ```java
 * getScrollRange()  = paddingTop + itemCount*itemHeight + paddingBottom
 * getScrollOffset() = paddingTop + firstItemPosition*itemHeight - firstItemOffset
 * ```
 * so it is exact only when every item has the top child's height. On mixed-height lists it is not, which is upstream
 * issues #57 ("scrollbar jumps around") and #47 ("thumb is wrong position in expandable RecyclerView").
 * [CORRECTED 2026-11-13] Those two issues are CLOSED, not open (both "question", closed the day they were filed): the
 * maintainer's answer is that items of different heights are not supported by default and the README explains why. The
 * only open item is #38, the maintainer's own enhancement "Add a RecyclerView ViewHelper for dynamic item height", which
 * nobody has built. So the fix for mixed heights is ours, and this class is it.
 *
 * ## ⚠️ The `+ paddingTop + paddingBottom` is required, not cosmetic
 *
 * FastScroller subtracts the FULL view height from the range, while the list's content area is the height minus the
 * vertical padding. At the true bottom the list's own offset is `content - (height - padTop - padBottom)`, so the range
 * must carry the padding for `offset / (range - height)` to reach exactly 1.0. Established 2026-09-04 by reading
 * AndroidFastScroll and RecyclerView together (ScrollbarHelper.computeScrollExtent / OrientationHelper.getTotalSpace,
 * recyclerview 1.4.0), WRONGLY CALLED A DEFECT on 2026-09-05 and reinstated the same day. Drop it and the thumb is
 * positioned past the end of its own track. [2026-11-13] Unchanged by the height model: [getScrollRange] still adds it.
 *
 * ## ⚠️ smoothScrollbarEnabled MUST stay on, and the span-estimate flag off
 *
 * The model no longer reads computeVerticalScrollRange/Offset, but other code does: MainFragment.applyInsets reads
 * `computeVerticalScrollOffset()` in pixels for the app-bar outline fade, and canScrollVertically (used here for the two
 * anchors) goes through the same path. Turning `LinearLayoutManager.mSmoothScrollbarEnabled` off switches those to item
 * counts. GridLayoutManager's setUsingSpansToEstimateScrollbarDimensions must stay off for the fatal reason at
 * GridAdapter.configureGridLayout ("Cannot find wrapper for N", build 1081).
 *
 * ## The short list
 *
 * If the content plus padding fits, `getScrollRange()` is at most the view height, `getScrollOffsetRange()` is at most 0,
 * and FastScroller's `mScrollbarEnabled = scrollOffsetRange > 0` hides the thumb. No explicit gate needed.
 *
 * ## [HEIGHT MODEL] (2026-11-13)
 *
 * - Every item's height is recorded by adapter position as it is laid out (decorated height plus margins, i.e. the space
 *   it takes in the list), with its view type. An AdapterDataObserver keeps the positions right: inserts and removes
 *   shift them, a range change forgets it, a full change or a move forgets everything.
 * - An item not measured yet counts as the AVERAGE MEASURED HEIGHT OF ITS OWN VIEW TYPE, so a carousel below the fold is
 *   estimated from carousels and a tile from tiles. That is what the extrapolated estimate could not do: it averaged
 *   the attached window, so a window of cards priced the carousels below as cards (the Search measurements in
 *   [scrollTo]'s history note). With no measurement of that type, the average of everything measured.
 * - In a grid an item counts `height * spanSize / spanCount`, so a full row sums to one row height without walking
 *   span groups (the ANR cost, see [scrollTo]); getSpanSize is O(1) through GridAdapter. A section's last row that is
 *   not full is undercounted by the missing spans (inference: up to half a row for 2-up tiles).
 * - A prefix sum over those weighted heights is the model's whole content height and every item's start. Types and span
 *   sizes are read once per data change; heights per new measurement; a rebuild is then arithmetic only.
 * - FROZEN DURING A THUMB GESTURE, and until the next scroll that is not the scroller's (a finger drag, a fling, a
 *   programmatic smooth scroll): heights measured meanwhile wait in [pending]. So the thumb and the drag use one
 *   unchanging scale for the whole gesture, and the thumb does not move at release because nothing changes at release.
 *   A data change still rebuilds (a page loaded mid-drag adds its estimated rows), which moves the thumb once: the cost
 *   of a list without placeholders, the same as before.
 * - Two anchors make the ends exact whatever the model's error: a list that cannot scroll up reports 0 for its part, and
 *   one that cannot scroll down (with the header collapsed) reports the maximum.
 */
class PixelFastScrollViewHelper(
    private val view: RecyclerView,
    /**
     * The collapsing header ABOVE this list, when there is one — null on every full-bleed screen.
     *
     * ⚠️ NOT DISCOVERED, PASSED IN. The AppBarLayout is a SIBLING under the CoordinatorLayout (this
     * RecyclerView lives inside a FragmentContainerView), never an ancestor, so walking up the view tree
     * would have to scan the CoordinatorLayout's children and guess. Call sites resolve it explicitly.
     *
     * When null every composite term below is zero, so full-bleed screens are on the same expression with an additive
     * identity, not a different branch.
     */
    private val appBar: AppBarLayout? = null,
) : FastScroller.ViewHelper {

    // ── COMPOSITE SCROLL METRICS (2026-09-05) ────────────────────────────────────────────────────────
    // A collapsing screen has TWO stacked scroll axes: the header collapsing, then the list travelling.
    // FastScroller's contract is one-dimensional, so the header's pixels come first in the one space.
    // RecyclerView.nestedScrollBy returns VOID (RecyclerView.java:2074) and subtracts whatever
    // dispatchNestedPreScroll consumed before scrolling, so scrollTo cannot see the header's share either: it is read
    // back here instead. verticalOffset runs 0 (expanded) to -totalScrollRange (collapsed), hence the negation.
    private var appBarConsumed = 0

    private val appBarRange get() = appBar?.totalScrollRange ?: 0

    init {
        appBar?.addOnOffsetChangedListener(
            AppBarLayout.OnOffsetChangedListener { _, verticalOffset -> appBarConsumed = -verticalOffset }
        )
    }

    /**
     * True while FastScroller is driving the list from a touch it consumed. NOT `view.scrollState`: that
     * stays IDLE for the whole of a thumb drag, because FastScroller moves the list itself rather than
     * through RecyclerView's touch handling. See [trackGesture].
     */
    private var gestureActive = false

    /** The last target FastScroller handed [scrollTo] in this gesture; see the still-finger note there. */
    private var lastRequested = Int.MIN_VALUE

    // ── HEIGHT MODEL (2026-11-13), see the class note ────────────────────────────────────────────────
    private class Measured(val type: Int, val height: Int)

    private val measured = HashMap<Int, Measured>()
    private val pending = HashMap<Int, Measured>()
    private var frozen = false
    private var heightsDirty = true
    private var dataDirty = true
    private var observed: RecyclerView.Adapter<*>? = null

    private var count = 0
    private var builtSpanCount = 0
    private var types = IntArray(0)
    private var spans = IntArray(0)
    private var rowStart = IntArray(0)
    private var heights = IntArray(0)
    private var prefix = DoubleArray(1)

    private val observer = object : RecyclerView.AdapterDataObserver() {
        override fun onChanged() = forgetAll()
        override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) = forgetAll()

        override fun onItemRangeChanged(positionStart: Int, itemCount: Int) {
            for (p in positionStart until positionStart + itemCount) {
                measured.remove(p)
                pending.remove(p)
            }
            dataDirty = true
        }

        override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) =
            onItemRangeChanged(positionStart, itemCount)

        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
            shift(measured, positionStart, itemCount)
            shift(pending, positionStart, itemCount)
            dataDirty = true
        }

        override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) {
            for (map in listOf(measured, pending)) {
                for (p in positionStart until positionStart + itemCount) map.remove(p)
                shift(map, positionStart + itemCount, -itemCount)
            }
            dataDirty = true
        }
    }

    private fun forgetAll() {
        measured.clear()
        pending.clear()
        dataDirty = true
    }

    // Moves every key at or after [from] by [by].
    private fun shift(map: HashMap<Int, Measured>, from: Int, by: Int) {
        if (map.keys.none { it >= from }) return
        val moved = map.filterKeys { it >= from }
        moved.keys.forEach { map.remove(it) }
        moved.forEach { (p, m) -> map[p + by] = m }
    }

    private fun ensureAdapter() {
        val adapter = view.adapter
        if (adapter === observed) return
        observed?.unregisterAdapterDataObserver(observer)
        adapter?.registerAdapterDataObserver(observer)
        observed = adapter
        forgetAll()
    }

    private fun freeze() {
        frozen = true
    }

    // The model takes what was measured while it was frozen. Only between gestures (see the class note).
    private fun unfreeze() {
        if (!frozen) return
        frozen = false
        if (pending.isNotEmpty()) {
            measured.putAll(pending)
            pending.clear()
            heightsDirty = true
        }
    }

    // Records the attached children's heights: into the model, or into [pending] while frozen.
    private fun measureAttached() {
        val lm = view.layoutManager ?: return
        val into = if (frozen) pending else measured
        for (i in 0 until view.childCount) {
            val child = view.getChildAt(i)
            val position = view.getChildAdapterPosition(child)
            if (position == RecyclerView.NO_POSITION) continue
            val lp = child.layoutParams as RecyclerView.LayoutParams
            val height = lm.getDecoratedMeasuredHeight(child) + lp.topMargin + lp.bottomMargin
            val type = view.getChildViewHolder(child).itemViewType
            val known = into[position]
            if (known != null && known.height == height && known.type == type) continue
            into[position] = Measured(type, height)
            if (!frozen) heightsDirty = true
        }
    }

    /**
     * Brings the model up to date. ⚠️ GridAdapter's note (configureGridLayout, "Cannot find wrapper for N") is why every
     * position read here is below the adapter's LIVE itemCount, read in the same call: the ConcatAdapter resolves those.
     * If one still throws, the model keeps its last build and tries again next frame - a stale thumb for a frame, rather
     * than a fatal crash out of a draw. This is not the guard that note forbids (inside getSpanSize itself, to buy back
     * the span estimator); getSpanSize still throws for every other caller.
     */
    private fun ensureModel() {
        ensureAdapter()
        measureAttached()
        val adapter = view.adapter ?: return
        val grid = view.layoutManager as? GridLayoutManager
        val spanCount = grid?.spanCount ?: 1
        if (adapter.itemCount != count || spanCount != builtSpanCount) dataDirty = true
        if (!dataDirty && !heightsDirty) return
        runCatching {
            if (dataDirty) readData(adapter, grid, spanCount)
            rebuildHeights(spanCount)
        }.onSuccess {
            dataDirty = false
            heightsDirty = false
        }
    }

    // Types, span sizes and row starts, once per data change. Rows are GridLayoutManager's default greedy grouping.
    private fun readData(adapter: RecyclerView.Adapter<*>, grid: GridLayoutManager?, spanCount: Int) {
        val n = adapter.itemCount
        val newTypes = IntArray(n)
        val newSpans = IntArray(n)
        val newRows = IntArray(n)
        val lookup = grid?.spanSizeLookup
        var used = 0
        var start = 0
        for (p in 0 until n) {
            newTypes[p] = measured[p]?.type ?: adapter.getItemViewType(p)
            val span = (lookup?.getSpanSize(p) ?: spanCount).coerceIn(1, spanCount)
            if (used + span > spanCount) {
                used = 0
                start = p
            }
            used += span
            newSpans[p] = span
            newRows[p] = start
        }
        types = newTypes
        spans = newSpans
        rowStart = newRows
        count = n
        builtSpanCount = spanCount
    }

    private fun rebuildHeights(spanCount: Int) {
        val sums = HashMap<Int, Long>()
        val counts = HashMap<Int, Int>()
        var allSum = 0L
        for (m in measured.values) {
            sums[m.type] = (sums[m.type] ?: 0L) + m.height
            counts[m.type] = (counts[m.type] ?: 0) + 1
            allSum += m.height
        }
        val fallback = if (measured.isEmpty()) (view.height / 3).coerceAtLeast(1)
        else (allSum / measured.size).toInt().coerceAtLeast(1)
        val n = count
        val newHeights = IntArray(n)
        val newPrefix = DoubleArray(n + 1)
        for (p in 0 until n) {
            val type = types[p]
            val h = measured[p]?.height
                ?: counts[type]?.let { (sums[type]!! / it).toInt() }
                ?: fallback
            newHeights[p] = h
            newPrefix[p + 1] = newPrefix[p] + h.toDouble() * spans[p] / spanCount
        }
        heights = newHeights
        prefix = newPrefix
    }

    private fun modelRange(): Int =
        appBarRange + prefix[count].roundToInt() + view.paddingTop + view.paddingBottom

    /**
     * The list's own offset in the model: the start of the row at the top edge, plus how far into that row the list is,
     * as a fraction of the row's actual height scaled to its model height. Measured against the top padding, as
     * computeVerticalScrollOffset is (startAfterPadding - decoratedStart).
     */
    private fun listOffset(): Double {
        if (!view.canScrollVertically(-1)) return 0.0
        val lm = view.layoutManager ?: return 0.0
        var first = Int.MAX_VALUE
        var top = 0
        var actual = 0
        for (i in 0 until view.childCount) {
            val child = view.getChildAt(i)
            val position = view.getChildAdapterPosition(child)
            if (position == RecyclerView.NO_POSITION || position >= first) continue
            val lp = child.layoutParams as RecyclerView.LayoutParams
            val childTop = lm.getDecoratedTop(child) - lp.topMargin
            val childHeight = lm.getDecoratedMeasuredHeight(child) + lp.topMargin + lp.bottomMargin
            if (childTop + childHeight <= view.paddingTop) continue
            first = position
            top = childTop
            actual = childHeight
        }
        if (first == Int.MAX_VALUE) return 0.0
        if (first >= count) return prefix[count]
        val row = rowStart[first]
        val into = (view.paddingTop - top).coerceIn(0, actual)
        val fraction = if (actual > 0) into.toDouble() / actual else 0.0
        return prefix[row] + fraction * heights[row]
    }

    private fun modelOffset(range: Int): Int {
        val max = (range - view.height).coerceAtLeast(0)
        if (appBarConsumed >= appBarRange && !view.canScrollVertically(1)) return max
        return (appBarConsumed + listOffset()).roundToInt().coerceIn(0, max)
    }

    override fun getScrollRange(): Int {
        ensureModel()
        return modelRange()
    }

    override fun getScrollOffset(): Int {
        ensureModel()
        return modelOffset(modelRange())
    }

    /**
     * [2026-11-13] AN ABSOLUTE TARGET IN THE MODEL, reached by the cheapest move that gets there (the user's approved
     * design): FastScroller hands `offset = (range - height) * thumbFraction`, both from this class, so it is the model
     * position under the finger. The list moves by `target - current`, both in the model:
     * - up to one view height: nestedScrollBy, which keeps GapWorker's prefetch of nested carousels (the note below);
     * - further: scrollToPositionWithOffset to the target's row, which lays out ONE screen however far the jump - the
     *   order-of-growth fix for the drag ANRs recorded below;
     * - the end of the rail: the last item, as before.
     * A header in the way is collapsed (a jump into the list) or expanded (a jump into the header's range) first, since
     * scrollToPositionWithOffset dispatches no nested scroll; the rest of a header-range target is then covered by the
     * next small move, which collapses it the way a finger does.
     *
     * ⚠️ THE STILL-FINGER GUARD IS LOAD-BEARING: a held finger keeps sending ACTION_MOVE with the same y, so the same
     * target. Without the guard the residual between target and model (one row's estimate error) would be applied again
     * every event; with it, a still finger moves nothing. This is the 2026-09-05 lesson (two images alternating at 60 Hz
     * while the finger was still) in its general form: the thumb and the drag share one frozen scale, and a repeated
     * target is never re-applied.
     *
     * [CORRECTED 2026-11-13] RECONCILED WITH THE RECORD BEFORE THIS CHANGE, because the 2026-09-04 note said the drag
     * scale had become a "live span" and the trace was deleted, while the code read on 2026-11-13 had a span fixed at the
     * grab and the trace present. Read from git: the span was ALWAYS captured once per gesture. 2026-09-04 (f03492fc)
     * replaced an averaged per-item estimate with `gestureSpan = liveRange() - view.height`, taken at the gesture's first
     * call; "live" meant live AT THE GRAB, not live each move. 2026-09-05 (e7f2a0a6) renamed it to librarySpan
     * (getScrollRange() - height) when the header term went into getScrollRange, because the two had come apart and a
     * still finger alternated between two positions. The GladixScroll trace was deleted on 2026-09-04 (f03492fc), put back
     * the next morning (4c4fb267) for the artist/album header work, and kept on 2026-09-12 at the user's request for this
     * Search investigation; it is retired with this change. Neither was a regression. What stopped Search at two-thirds
     * was that once-per-gesture span itself: fixed at 4082 at the top while the extrapolated range reached 5918 lower down.
     * The model is also frozen per gesture, but it does not have that fault: a frozen model is the WHOLE scale (thumb,
     * target and current offset), not one multiplier against a range that keeps growing, so the full rail maps to the
     * full model and the end anchor makes the bottom exact.
     *
     * ⚠⚠ [2026-09-22] A THUMB-DRAG ANR RAN THROUGH nestedScrollBy AND THAT CALL WAS NOT ITS CAUSE. Its cost was
     * GridLayoutManager.SpanSizeLookup.getSpanGroupIndex, reached from GridAdapter's item decoration inside layoutChunk ->
     * getItemDecorInsetsForChild: the LAYOUT PASS, which scrollBy and nestedScrollBy perform identically. THREE COSTS
     * HAVE BEEN FOUND ON THIS ONE GESTURE, ALL PER CHILD LAID OUT: nested carousel binding (fixed 2026-09 by nestedScrollBy
     * and GapWorker), the span-group walk (mitigated 2026-09-22 by FeedAdapter using peek), and Coil's request restart on
     * attach (ViewTargetRequestManager.onViewAttachedToWindow). As a relative scroll, a drag across a long feed laid out
     * every item in between, so each fix only reduced a constant. Coalescing moves does not help: three 1000px scrolls
     * and one 3000px scroll lay out the same children. [2026-11-13] The jump path above is the order-of-growth fix that
     * note called for: one screen per jump. Its cost is that a jump binds that screen's carousels synchronously
     * (scrollToPositionWithOffset gets no GapWorker); the 2026-09 note asked for that to be MEASURED, and the device test
     * of a long feed is that measurement.
     *
     * ⚠⚠ [2026-09-13] THE SEARCH MEASUREMENTS, kept as the evidence the model answers. Deezer's Search landing, top to
     * bottom: Genres and Categories (cards, 3 rows x 2), four carousels, Explore all (cards, 3 rows x 2). Cards are grids
     * of tiles; a carousel is one outer-list item holding a row of many cards, so pixels-per-item differs about 4-5x
     * between them, and pixels-per-item is exactly what ScrollbarHelper.computeScrollRange averaged
     * (laidOutArea / laidOutRange * itemCount, over the attached window). 33 items throughout, the extrapolated range read
     * 4082 at the top and 5918 further down, and exactly 4082 again on returning. 4082/5918 = 0.69, the "about
     * two-thirds" the drag stopped at. The thumb was also MISSING over Genres and Categories (a window of cards priced the
     * whole list below the viewport, so scrollOffsetRange went under 0 and FastScroller's mScrollbarEnabled went false)
     * and PRESENT over Explore all, the same layout, because carousels above kept the average up there. That control
     * showed the cause was the attached window, not the section type, which is why the model prices each item by its
     * own type rather than special-casing cards. The 2026-09-06 GridAdapter note that "row density was never the driver"
     * was measured with the span estimator, which still averaged the window; it does not cover per-type pricing.
     *
     * ⚠️ nestedScrollBy, NOT scrollBy — THIS IS THE NESTED-PREFETCH PATH, NOT A NESTED-SCROLLING WISH.
     * `RecyclerView.scrollBy` (RecyclerView.java:2051) calls scrollByInternal DIRECTLY and is the one
     * public scroll entry point that never posts to GapWorker. All three paths that do post go through
     * it: nestedScrollByInternal (:2130), onTouchEvent's ACTION_MOVE (:3964) and ViewFlinger (:6006).
     * GapWorker is what prefetches NESTED RecyclerViews — prefetchInnerRecyclerViewWithDeadline
     * (GapWorker.java:317), collectPrefetchPositionsFromView(innerView, true) (:332), driven off
     * `holder.mNestedRecyclerView` (:357). `nestedScrollBy` is the only public API that reaches
     * nestedScrollByInternal, so it is how a non-touch scroll source opts into prefetch. The cost is that it
     * dispatches through the whole nested-scroll chain with TYPE_NON_TOUCH.
     *
     * ⚠️ THE THREE PARENTS THIS REACHES ANSWER DIFFERENTLY. Do not check one and assume the rest.
     * Verified individually against the pinned versions:
     *
     *  1. SwipeRefreshLayout (every feed screen) — REFUSES NON-TOUCH OUTRIGHT.
     *     onStartNestedScroll returns false for any `type != TYPE_TOUCH`
     *     (SwipeRefreshLayout.java:918-921, and onNestedScroll bails at :872), so the chain never even
     *     accepts. It cannot arm the refresh spinner.
     *
     *  2. BottomSheetBehavior (MediaDetailsFragment opened from the player, which sits five levels deep:
     *     BottomSheetBehavior -> LinearLayout -> MaterialCardView -> TrackInfoFragment ->
     *     MediaDetailsFragment -> SwipeRefreshLayout -> RecyclerView, with nested scrolling
     *     DELIBERATELY enabled on that recycler) — ACCEPTS NON-TOUCH, THEN CONSUMES NOTHING.
     *     Its onStartNestedScroll does NOT check type at all — it is just
     *     `lastNestedScrollDy = 0; nestedScrolled = false; return (axes & SCROLL_AXIS_VERTICAL) != 0`
     *     — so unlike (1) it does accept. Nothing comes of it:
     *       - onNestedPreScroll opens with `if (type == TYPE_NON_TOUCH) return;`, so it consumes none of
     *         the delta, never moves the sheet, and never reaches the line that sets nestedScrolled.
     *       - onNestedScroll (the 9-arg overload) has a body of exactly `return`, so lastNestedScrollDy
     *         stays 0.
     *       - onStopNestedScroll therefore hits
     *         `if (isNestedScrollingCheckEnabled() && isViewScrollingChild(target) && !nestedScrolled)
     *         return;` — the first is hardcoded true, the second is true for this recycler, and
     *         nestedScrolled is still false — and returns before any settle logic.
     *     So a thumb drag on the player's track list cannot move or dismiss the sheet, and none of the
     *     delta is eaten before the list sees it.
     *
     *     ⚠️ AND THE ONE THING THAT IS NOT INFERABLE FROM READING onStopNestedScroll ALONE: its FIRST
     *     branch is `if (child.getTop() == getExpandedOffset()) { setStateInternal(STATE_EXPANDED);
     *     return; }`, which is reached on every release, because the sheet IS at its expanded offset
     *     whenever that list is visible. That looks like it would fire the BottomSheetCallback — and
     *     this app's callback drives playerSheetState, whose observers run bgImage.resume(),
     *     emit(playerBgVisible, false) and applyPlayer(), the last of which detaches and reattaches the
     *     video surface. Firing that on every drag release would be a real regression. It does not,
     *     because setStateInternal begins `if (this.state == state) return;` and the sheet is already
     *     STATE_EXPANDED, so nothing is dispatched. If that early return ever goes away, this becomes a
     *     bug and the symptom will look nothing like the scroller.
     *     [2026-11-13] A jump (scrollToPositionWithOffset) dispatches no nested scroll at all, so it reaches none of this.
     *
     *  3. AppBarLayout.Behavior (fragment_manage_extensions, layout_scrollFlags
     *     "scroll|enterAlwaysCollapsed|snap"; fragment_playlist_edit, AppBarLayout$ScrollingViewBehavior)
     *     — ACCEPTS NON-TOUCH AND CONSUMES IT. KNOWN BEHAVIOUR CHANGE: those two toolbars collapse
     *     during a thumb drag, as a finger scroll on the same screens already does. fragment_search's
     *     CoordinatorLayout declares no AppBarLayout and no layout_behavior, so it is unaffected.
     *
     * A zero delta returns WITHOUT calling scrollBy, and that is load-bearing, not an optimisation.
     * RecyclerView.scrollBy has no no-op guard, and scrollByInternal (RecyclerView.java:2266-2268) runs
     * `if (!mItemDecorations.isEmpty()) { invalidate(); }` UNCONDITIONALLY before any consumed-check.
     * addOnPreDrawListener below installs an ItemDecoration, so every scrollBy repaints the whole list,
     * including scrollBy(0, 0).
     */
    override fun scrollTo(offset: Int) {
        view.stopScroll()
        // A track tap's first jump arrives before trackGesture opens the gesture (see there); it is a gesture too.
        if (!frozen) freeze()
        if (gestureActive && offset == lastRequested) return
        lastRequested = offset
        ensureModel()
        val range = modelRange()
        val max = (range - view.height).coerceAtLeast(0)
        val target = offset.coerceIn(0, max)
        val delta = target - modelOffset(range)
        if (delta == 0) return
        if (abs(delta) <= view.height) {
            view.nestedScrollBy(0, delta)
            return
        }
        val lm = view.layoutManager as? LinearLayoutManager
        if (lm == null || count == 0) return view.nestedScrollBy(0, delta)
        when {
            // The end of the rail: the last loaded item. PagedSource sets enablePlaceholders = false, so this is the last
            // LOADED item and landing there lets Paging load the next page, as reaching it by finger does.
            target >= max -> {
                if (appBarRange > 0 && appBarConsumed < appBarRange) appBar?.setExpanded(false, false)
                lm.scrollToPositionWithOffset(count - 1, 0)
            }
            // Into the header's own range: the list's top with the header expanded; the next small move collapses it.
            target < appBarRange -> {
                appBar?.setExpanded(true, false)
                lm.scrollToPositionWithOffset(0, 0)
            }

            else -> {
                if (appBarRange > 0 && appBarConsumed < appBarRange) appBar?.setExpanded(false, false)
                val listTarget = (target - appBarRange).toDouble()
                val row = rowStart[itemAt(listTarget)]
                val into = (listTarget - prefix[row]).coerceIn(0.0, heights[row].toDouble())
                lm.scrollToPositionWithOffset(row, -into.roundToInt())
            }
        }
    }

    // The last item starting at or before [listOffset] in the model.
    private fun itemAt(listOffset: Double): Int {
        var low = 0
        var high = count - 1
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (prefix[mid] <= listOffset) low = mid else high = mid - 1
        }
        return low
    }

    // The three registrations mirror RecyclerViewHelper's own shapes (verified against the 1.3.0 AAR):
    // an ItemDecoration.onDraw for pre-draw, an OnScrollListener, and an OnItemTouchListener.
    override fun addOnPreDrawListener(onPreDraw: Runnable) {
        view.addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
                onPreDraw.run()
            }
        })
    }

    override fun addOnScrollChangedListener(onScrollChanged: Runnable) {
        view.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                onScrollChanged.run()
            }

            // [2026-11-13] A scroll that is not the scroller's (a finger, a fling, a smooth scroll) ends the frozen model.
            // A thumb drag never gets here: it leaves scrollState IDLE (see [gestureActive]).
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE && !gestureActive) unfreeze()
            }
        })
    }

    // Where the fast scroller and ItemTouchHelper meet: both are OnItemTouchListeners and whichever
    // intercepts first wins. That is upstream issue #53, and the library ships
    // FixOnItemTouchListenerRecyclerView for it. This is no worse than the library's own helper, but
    // it is the line to look at if that swap ever happens.
    override fun addOnTouchEventListener(onTouchEvent: Predicate<MotionEvent>) {
        view.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent) =
                onTouchEvent.test(e).also { trackGesture(e, it) }

            override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
                trackGesture(e, onTouchEvent.test(e))
            }
        })
    }

    /**
     * Marks the start and end of a FastScroller-driven gesture.
     *
     * ⚠️ DRIVEN OFF WHETHER FASTSCROLLER CONSUMED THE EVENT, NOT `view.scrollState`. FastScroller moves
     * the list itself, so RecyclerView's scrollState stays IDLE for the entire thumb drag and would never
     * open the gesture. FastScroller.onTouchEvent returns `mDragging`, so a consumed event means exactly
     * "it has the thumb or the track and is about to drive scrollTo".
     *
     * Ordering: this runs AFTER the predicate, so on the ACTION_MOVE where FastScroller decides a track tap has happened
     * it calls scrollToThumbOffset before [gestureActive] is set. [scrollTo] freezes the model for that call itself.
     * [2026-11-13] The model is frozen when a gesture opens and stays frozen after it ends; see the class note.
     */
    private fun trackGesture(event: MotionEvent, consumed: Boolean) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> if (consumed && !gestureActive) {
                gestureActive = true
                freeze()
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                gestureActive = false
                lastRequested = Int.MIN_VALUE
            }
        }
    }
}
