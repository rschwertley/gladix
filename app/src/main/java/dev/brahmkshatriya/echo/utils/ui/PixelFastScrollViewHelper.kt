package dev.brahmkshatriya.echo.utils.ui

import android.graphics.Canvas
import android.util.Log
import android.view.MotionEvent
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.AppBarLayout
import me.zhanghai.android.fastscroll.FastScroller
import me.zhanghai.android.fastscroll.Predicate

/**
 * Scroll metrics for the fast scroller, taken from RecyclerView's own scrollbar estimates.
 *
 * This is deliberately the shape of AndroidFastScroll's OTHER shipped ViewHelper, `SimpleViewHelper`
 * (used for ScrollView / NestedScrollView / WebView):
 * ```java
 * getScrollRange()  = computeVerticalScrollRange();
 * getScrollOffset() = computeVerticalScrollOffset();
 * scrollTo(offset)  = scrollTo(getScrollX(), offset);
 * ```
 * Two implementations ship, and comparing them is what defines the interface's real contract, since
 * none of it is documented.
 *
 * ## The contract a ViewHelper must satisfy
 *
 * 1. `getScrollRange()`, `getScrollOffset()` and `scrollTo()`'s argument are ONE unit space.
 * 2. `getScrollRange() - view.getHeight()` must be the reachable maximum of `getScrollOffset()`.
 *    FastScroller.getScrollOffsetRange() hardcodes that subtraction, so the space has to be one in
 *    which the viewport extent IS the view height — i.e. PIXELS.
 * 3. `scrollTo(x)` then `getScrollOffset()` must return ~x. FastScroller keeps no memory of the
 *    finger: updateScrollbarState recomputes
 *    `mThumbOffset = thumbOffsetRange * getScrollOffset() / scrollOffsetRange` on every frame, and
 *    onPreDraw lays the thumb at `padding.top + mThumbOffset`. So the thumb tracks the finger only
 *    if the round trip is faithful.
 *
 * ## Why the previous position-based version could not work
 *
 * It expressed range and offset in adapter positions. Requirement 2 then needs "how many items fit"
 * to stand in for the view height, which is unknowable without measuring every item — that was the
 * `extent` term, and it caused every round of this: read live it moved the denominator mid-drag
 * (jumping); cached it went stale and clamped the offset (thumb pinning); the shrink guard was a
 * patch on the patch. Requirement 2 is not satisfiable in position space. Do not go back to it.
 *
 * ## Why not the library's own RecyclerViewHelper
 *
 * It estimates every item's height from `getChildAt(0)` alone:
 * ```java
 * getScrollRange()  = paddingTop + itemCount*itemHeight + paddingBottom
 * getScrollOffset() = paddingTop + firstItemPosition*itemHeight - firstItemOffset
 * scrollTo(x)       { x -= paddingTop; h = getItemHeight();
 *                     position = max(0, x/h); offsetInItem = position*h - x; ... }
 * ```
 * Working the round trip through gives `getScrollOffset() = x + position*(h_after - h_before)`, so it
 * is exact only when the scroll does not change the top child's height. On mixed-height lists it is
 * not exact, which is upstream issues #57 ("scrollbar jumps around") and #47 ("thumb is wrong
 * position in expandable RecyclerView") — the jumping on Home/Search/Settings was partly the
 * library's own defect, not something introduced here.
 *
 * RecyclerView's estimate is strictly better: ScrollbarHelper averages over the WHOLE laid-out window
 * (`avgSizePerRow = laidOutArea / itemRange`), not one child, and range and offset are computed from
 * the same window in the same call, so the average largely cancels out of the ratio.
 *
 * ## ⚠️ The `+ paddingTop + paddingBottom` is required, not cosmetic
 *
 * `computeVerticalScrollExtent` is `min(getTotalSpace(), laidOutArea)` and OrientationHelper's
 * vertical `getTotalSpace()` is `height - paddingTop - paddingBottom` — so the extent is NOT the view
 * height, it is short by the vertical padding. FastScroller subtracts the view height regardless, so
 * without adding the padding back the denominator is too small by exactly that amount and the thumb
 * runs past the bottom of the track (badly so on a barely-scrollable list with a large mini-player
 * inset, where `range - height` is small). Adding it makes the two agree exactly:
 * ```
 * getScrollRange() - view.height  ==  computeVerticalScrollRange() - computeVerticalScrollExtent()
 * ```
 * This is the same reason RecyclerViewHelper carries paddingTop/paddingBottom in its own range.
 *
 * ## Requirement 2 then holds exactly, by construction
 *
 * At maximum scroll the laid-out window is positions P..itemCount-1, and ScrollbarHelper computes
 * `avgSizePerRow = laidOutArea / itemRange` over that same window, so `avg * (itemCount - P)` IS
 * `laidOutArea` identically. Substituting into
 * `offset = itemsBefore*avg + (startAfterPadding - decoratedStart(first))` and
 * `range = avg*itemCount` reduces to `offset == range - extent`. So the end is reached by the SHAPE.
 * No end anchor, no special case, and nothing to keep in sync.
 *
 * The short-list case falls out the same way: if the content plus padding fits, `getScrollRange()` is
 * <= the view height, `getScrollOffsetRange()` is <= 0, and FastScroller's
 * `mScrollbarEnabled = scrollOffsetRange > 0` disables the thumb. No explicit gate needed.
 *
 * ## ⚠️ smoothScrollbarEnabled MUST stay on
 *
 * `LinearLayoutManager.mSmoothScrollbarEnabled` defaults to true and nothing here changes it. Turning
 * it off switches ScrollbarHelper to a completely different unit space — range becomes
 * `state.getItemCount()`, offset becomes `itemsBefore`, extent becomes a count of items — which is
 * position space again, i.e. requirement 2 breaks in exactly the way described above. It is also what
 * makes this better than the library's helper: the whole-window averaging only happens on the
 * `smoothScrollbarEnabled` branch. And MainFragment.applyInsets reads
 * `computeVerticalScrollOffset()` in pixels for the app-bar outline fade, which would break too.
 *
 * GridLayoutManager overrides computeVerticalScrollRange/Offset, but only when
 * `mUsingSpansToEstimateScrollBarDimensions` is set; it defaults false and nothing in this app sets
 * it, so both fall through to the LinearLayoutManager path together. Do not enable it without
 * revisiting the padding correction above — it changes range and offset but NOT extent, which is what
 * that correction is calibrated against.
 *
 * ## Accepted residual
 *
 * The average still varies with item heights, so thumb travel is non-linear — faster through tall
 * sections. It does not pin or stop short: range and offset come from one window in one call, and a
 * repeated target is never re-applied (see [scrollTo]). Whether the thumb still visibly leaves the
 * finger mid-drag is the open question — see [getScrollOffset] for the one remedy, and why it is not
 * carried pre-emptively.
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
     * When null every composite term below is zero and the arithmetic reduces EXACTLY to what the six
     * full-bleed screens use today. That is the containment guarantee: those screens are not on a
     * different branch, they are on the same expression with an additive identity.
     */
    private val appBar: AppBarLayout? = null,
    /** Screen tag for the temporary trace only. REMOVE WITH THE TRACE. */
    private val traceTag: String = "?",
) : FastScroller.ViewHelper {

    // ── COMPOSITE SCROLL METRICS (2026-09-05) ────────────────────────────────────────────────────────
    // A collapsing screen has TWO stacked scroll axes: the header collapsing, then the list travelling.
    // FastScroller's contract is one-dimensional, and computeVerticalScrollOffset/Range describe only the
    // SECOND — so on those screens the pixels the AppBarLayout consumes are invisible to the thumb.
    // RecyclerView.nestedScrollBy returns VOID (RecyclerView.java:2074) and subtracts whatever
    // dispatchNestedPreScroll consumed before scrolling, so scrollTo cannot see the loss either: the drag
    // spends finger pixels the list never travelled.
    //
    // The AppBarLayout's contribution is a pure ADDITIVE term available through public Material API, so it
    // can be represented rather than worked around. verticalOffset runs 0 (expanded) to -totalScrollRange
    // (collapsed), hence the negation.
    private var appBarConsumed = 0

    /** Trace throttle in px. REMOVE WITH THE TRACE. */
    private val DRAG_LOG_PX = 24

    private val appBarRange get() = appBar?.totalScrollRange ?: 0

    init {
        appBar?.addOnOffsetChangedListener(
            AppBarLayout.OnOffsetChangedListener { _, verticalOffset -> appBarConsumed = -verticalOffset }
        )
    }

    /**
     * True while FastScroller is driving the list from a touch it consumed. NOT `view.scrollState`: that
     * stays IDLE for the whole of a thumb drag, because FastScroller moves the list itself rather than
     * through RecyclerView's touch handling. Driven off whether the touch predicate consumed the event,
     * which is true exactly when FastScroller has the thumb or track — see [trackGesture].
     */
    private var gestureActive = false

    /**
     * The list's scrollable span in content pixels, captured ONCE at the start of each gesture from
     * [liveRange]. Read [scrollTo].
     */
    private var gestureSpan = 1

    /** Previous thumb fraction in this gesture, or NaN before the first. */
    private var lastFraction = Double.NaN

    /** Sub-pixel remainder carried between frames so a long slow drag cannot drift. */
    private var pendingPixels = 0.0

    /**
     * True once this gesture has landed on the last adapter position - see [scrollTo]'s
     * end-of-rail branch. Cleared on the first call of a gesture and on the first call that is
     * not at the rail's end, so a finger that leaves the end and returns lands again.
     *
     * ⚠️ NOT AN OPTIMISATION. FastScroller re-issues the same target on every move
     * event while the finger is held, so without this the landing would schedule a layout pass
     * per event - and [addOnPreDrawListener] installs an ItemDecoration, which is why no layout
     * pass here is free (the same reason [scrollTo]'s zero-delta return is load-bearing).
     */
    private var landedAtEnd = false

    // ── TRACE (2026-09-05, temporary, GladixScroll). REMOVE WITH THE INVESTIGATION. ──────────────────
    // Two lines, because the open questions live at two different moments and one cannot answer the other.
    //   rest:  fires on IDLE. Decides whether artist's mid-rail rest position comes from a non-zero
    //          offset (composite layer can reach it) or not (it cannot, and the fallback is to gate the
    //          scroller off on collapsing screens). `extent` is printed so offset+extent==range — the
    //          identity in liveRange's note above — is checkable directly rather than inferred.
    //   drag:  fires during scrollTo. This is where read 4 lives: `req` is what scrollTo ASKED
    //          nestedScrollBy for, and nestedScrollBy reports nothing back, so req vs the change in
    //          `offset` is the only way to observe the AppBarLayout eating the delta.
    //
    // Throttled on a PIXEL DELTA, not every Nth event: a drag is frame-driven, so an event counter samples
    // at a rate that varies with finger speed, while a pixel threshold samples uniformly in the quantity
    // under investigation and still emits during a slow drag at the stall point.
    /** One-shot guard for the first rest: line. REMOVE WITH THE TRACE. */
    private var loggedFirstRest = false
    private var lastDragLogAt = 0
    private var dragReqAccum = 0

    /**
     * The appBar field for both trace lines. REMOVE WITH THE TRACE.
     *
     * ⚠️ "none" AND "0/N" MEAN DIFFERENT THINGS AND MUST NOT BE CONFLATED. A null [appBar] makes every
     * composite term zero, which produces today's arithmetic with new logging — indistinguishable from
     * "the composite ran and the header happened to be expanded" if this printed a bare 0. On a `media` or
     * `seeall` line, "none" means requireParentFragment().view?.findViewById(R.id.appBarLayout) returned
     * null and THE COMPOSITE NEVER RAN: that capture cannot answer anything about the fix. On a `main`
     * line "none" is correct and expected — those screens have no collapsing header by design.
     */
    private fun appBarField() =
        if (appBar == null) "none" else "$appBarConsumed/$appBarRange"

    private fun thumbFrac(): Double {
        val span = (getScrollRange() - view.height).coerceAtLeast(1)
        return getScrollOffset().toDouble() / span
    }

    /** REMOVE WITH THE TRACE. Called from the scroll listener when the list settles. */
    private fun logRest() {
        Log.d(
            "GladixScroll",
            ("rest: screen=%s atTop=%b atBottom=%b offset=%d extent=%d range=%d height=%d " +
                "padTop=%d padBottom=%d appBar=%s thumbFrac=%.3f").format(
                traceTag,
                !view.canScrollVertically(-1),
                !view.canScrollVertically(1),
                getScrollOffset(),
                view.computeVerticalScrollExtent(),
                getScrollRange(),
                view.height,
                view.paddingTop,
                view.paddingBottom,
                appBarField(),
                thumbFrac(),
            )
        )
    }

    /** REMOVE WITH THE TRACE. [req] is the delta handed to nestedScrollBy on this call. */
    private fun logDrag(req: Int) {
        dragReqAccum += req
        if (kotlin.math.abs(dragReqAccum - lastDragLogAt) < DRAG_LOG_PX) return
        lastDragLogAt = dragReqAccum
        Log.d(
            "GladixScroll",
            "drag: screen=%s req=%d offset=%d range=%d appBar=%s thumbFrac=%.3f".format(
                traceTag, req, getScrollOffset(), getScrollRange(), appBarField(), thumbFrac()
            )
        )
    }

    /**
     * ⚠️ THE PADDING TERM IS LOAD-BEARING. IT IS WHAT LETS THE THUMB REACH THE BOTTOM. Do not remove it.
     *
     * Established 2026-09-04 by reading AndroidFastScroll and RecyclerView together, WRONGLY CALLED A
     * DEFECT on 2026-09-05, and reinstated the same day. Recording the algebra so the next reader does not
     * repeat the retraction:
     *
     *   computeVerticalScrollExtent() = min(getTotalSpace(), laidOutExtent)
     *                                 = height - padTop - padBottom   (list longer than the viewport)
     *   at the true bottom:  offset = range - extent = range - height + padTop + padBottom
     *   FastScroller uses:   scrollOffsetRange = getScrollRange() - mView.getHeight()
     *                                          = range + padTop + padBottom - height
     *   -> the two are EQUAL, so offset/scrollOffsetRange reaches exactly 1.0 and the thumb lands flush.
     *
     * Drop the padding and offset_at_bottom EXCEEDS scrollOffsetRange, so mThumbOffset overshoots
     * getThumbOffsetRange() and the thumb is positioned past the end of its own track. The asymmetry is
     * REQUIRED because extent subtracts the padding and getScrollOffsetRange subtracts the full height.
     *
     * Source: ScrollbarHelper.computeScrollExtent / OrientationHelper.getTotalSpace (recyclerview 1.4.0);
     * FastScroller.getScrollOffsetRange (AndroidFastScroll 1.3.0, decoded from the AAR — no sources jar).
     */
    private fun liveRange() =
        view.computeVerticalScrollRange() + view.paddingTop + view.paddingBottom

    /**
     * LIVE, deliberately — an earlier revision froze this for the duration of a gesture and it was the
     * wrong lever. Live, the estimate's average appears in both numerator and denominator of
     * `mThumbOffset = thumbOffsetRange * getScrollOffset() / scrollOffsetRange` and largely CANCELS,
     * leaving roughly `itemsBefore / itemCount` — so the drawn thumb is stable. Freezing the denominator
     * while the numerator stayed live broke that cancellation and made the thumb wander mid-drag, which
     * is exactly what was observed. [scrollTo] no longer needs it frozen; see there.
     */
    override fun getScrollRange() = appBarRange + liveRange()

    /**
     * Plain live measurement, and with [scrollTo] no longer subtracting it there is nothing it can feed
     * back into. It is read exactly once per gesture now, on the first (absolute) call.
     *
     * An earlier revision replayed the requested offset here so the drawn thumb would exactly follow the
     * finger. Not needed: with [getScrollRange] live, the estimate's average cancels between this and the
     * range, so the thumb already sits at roughly `itemsBefore / itemCount` on its own.
     */
    override fun getScrollOffset() = appBarConsumed + view.computeVerticalScrollOffset()

    /**
     * A DELTA, not an absolute seek — RecyclerView has no "set pixel scroll" to mirror `View.scrollTo`.
     *
     * ⚠️ THIS APPLIES A DELTA RECOVERED FROM THE FINGER, NOT THE ABSOLUTE TARGET IT IS HANDED. That is
     * the whole point, and it is what makes a thumb drag behave like a finger scroll.
     *
     * A FINGER scroll is perfect on these same screens with the same unstable estimate, because
     * RecyclerView.onTouchEvent computes `dy` from finger movement and hands it to scrollByInternal —
     * there is not a single compute*Scroll* call anywhere in that method. The estimate is never in the
     * loop. A THUMB drag was different only because of what we did with FastScroller's argument:
     * `scrollToThumbOffset` builds `scrollOffset = getScrollOffsetRange() * thumbOffset /
     * thumbOffsetRange` where `thumbOffset = mDragStartThumbOffset + (eventY - mDragStartY)` is pure
     * finger pixels and `thumbOffsetRange` is pure geometry — so the ONLY estimate dependence is
     * getScrollOffsetRange(), i.e. ours. Subtracting `computeVerticalScrollOffset()` from that target put
     * the estimate back in the loop on both sides: the target scaled with `avg * itemCount`, the
     * subtrahend with `avg * itemsBefore`, so `delta ∝ avg * (itemCount*thumbFrac - itemsBefore)` and the
     * average MULTIPLIED the error. Content lurched, the window changed, the average moved, repeat — two
     * images at different offsets, alternating frames.
     *
     * So invert FastScroller's own multiplication with the SAME span it just used to recover the finger
     * fraction, and convert fraction deltas to pixels with a span read ONCE per gesture:
     *   fraction = offset / (getScrollRange() - view.height)      // undoes the library's multiply
     *   pixels   = (fraction - lastFraction) * gestureSpan        // gestureSpan captured at gesture start
     * `computeVerticalScrollOffset()` is never consulted after the first call, so there is no feedback
     * path at all. An error in gestureSpan makes the whole drag proportionally fast or slow — a bounded,
     * monotonic wrongness — instead of oscillating. That is the same trade a finger scroll makes.
     *
     * The first call of a gesture still lands ABSOLUTELY, which is required rather than incidental: the
     * track-TAP path sets mDragStartThumbOffset from the tap position and jumps, so the first target is
     * genuinely somewhere else. On a thumb GRAB the first target is where the content already is, so the
     * same absolute step is ~0.
     *
     * Sub-pixel remainders accumulate in [pendingPixels] rather than being rounded away each frame, so a
     * long slow drag cannot drift.
     *
     * A zero delta returns WITHOUT calling scrollBy, and that is load-bearing, not an optimisation.
     * RecyclerView.scrollBy has no no-op guard, and scrollByInternal (RecyclerView.java:2266-2268) runs
     * `if (!mItemDecorations.isEmpty()) { invalidate(); }` UNCONDITIONALLY before any consumed-check —
     * while dispatchOnScrolled at :2296 IS guarded. addOnPreDrawListener below installs an
     * ItemDecoration, so that list is never empty and every scrollBy repaints the whole list, including
     * scrollBy(0, 0). A held finger produces an unchanged fraction, so this returns and the screen stops
     * flashing.
     *
     * It also clamps at both ends by itself, which is the other half of why no end anchor is needed.
     * stopScroll() first, matching RecyclerViewHelper.
     *
     * ⚠⚠ [2026-09-22] A LATER ANR RUNS THROUGH THIS CALL AND THIS CHANGE IS NOT ITS CAUSE - IT
     * IS WHY THE CAUSE IS VISIBLE. Build 1107 ANR'd on a thumb drag with nestedScrollBy in the stack,
     * which reads as a regression from the switch below. IT IS THE OPPOSITE.
     * The ANR's cost is GridLayoutManager.SpanSizeLookup.getSpanGroupIndex, reached from
     * GridAdapter's item decoration inside layoutChunk -> getItemDecorInsetsForChild. THAT IS IN THE
     * LAYOUT PASS, WHICH BOTH SCROLL PATHS PERFORM IDENTICALLY - scrollBy and nestedScrollBy lay out
     * the same children the same way. GapWorker's prefetch does create+bind only
     * (flushTaskWithDeadline -> prefetchPositionWithDeadline -> tryGetViewHolderForPositionByDeadline,
     * plus a nested pass for carousels) and NEVER reaches getItemDecorInsetsForChild, so it adds no
     * span-group walks at all. It is also deadline-bounded: when the main thread is saturated,
     * prefetch degrades rather than compounding.
     * So the span walk was ALWAYS on this path. What this change removed was the carousel-binding
     * term sitting ON TOP of it - and removing the larger term is what left the smaller one exposed
     * as the dominant cost. Before this change the same drag would have ANR'd with carousel binding
     * masking the real cause. DO NOT REVERT THIS IN RESPONSE TO THAT ANR.
     *
     * ⚠️ nestedScrollBy, NOT scrollBy — THIS IS THE NESTED-PREFETCH PATH, NOT A NESTED-SCROLLING WISH.
     * `RecyclerView.scrollBy` (RecyclerView.java:2051) calls scrollByInternal DIRECTLY and is the one
     * public scroll entry point that never posts to GapWorker. All three paths that do post go through
     * it: nestedScrollByInternal (:2130), onTouchEvent's ACTION_MOVE (:3964) and ViewFlinger (:6006).
     * GapWorker is what prefetches NESTED RecyclerViews — prefetchInnerRecyclerViewWithDeadline
     * (GapWorker.java:317), collectPrefetchPositionsFromView(innerView, true) (:332), driven off
     * `holder.mNestedRecyclerView` (:357).
     *
     * So with plain scrollBy, a thumb drag scrolled the outer list while every horizontal carousel it
     * pulled into view had to create and bind its children synchronously in that layout pass, where a
     * finger scroll over the same content gets them prefetched a frame ahead. That is why the stutter
     * appeared only on Home and Search (many nested carousels), never on History or Settings (none), and
     * why it stopped the instant the finger lifted — lifting stops using this path.
     *
     * `nestedScrollBy` is the only public API that reaches nestedScrollByInternal, so it is how a
     * non-touch scroll source opts into prefetch. The cost is that it now dispatches through the whole
     * nested-scroll chain with TYPE_NON_TOUCH.
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
     *
     *  3. AppBarLayout.Behavior (fragment_manage_extensions, layout_scrollFlags
     *     "scroll|enterAlwaysCollapsed|snap"; fragment_playlist_edit, AppBarLayout$ScrollingViewBehavior)
     *     — ACCEPTS NON-TOUCH AND CONSUMES IT. KNOWN BEHAVIOUR CHANGE: those two toolbars now collapse
     *     during a thumb drag, where before they sat still. Taken deliberately, because it is what a
     *     finger scroll on the same screens already does. fragment_search's CoordinatorLayout declares
     *     no AppBarLayout and no layout_behavior, so it is unaffected.
     */
    override fun scrollTo(offset: Int) {
        view.stopScroll()
        // ⚠️ THIS MUST BE THE EXACT SPAN FastScroller MULTIPLIED BY. It computes the `offset` handed to
        // this method as
        //     scrollOffset = getScrollOffsetRange() * thumbOffset / getThumbOffsetRange()
        //     getScrollOffsetRange() = getScrollRange() - mView.getHeight()
        // so recovering the thumb fraction REQUIRES dividing by getScrollRange() - height. Anything else
        // and the fraction does not cancel.
        //
        // RESTORING AN INVARIANT, NOT INTRODUCING ONE. The 2026-09-04 resolution was that the thumb never
        // needed an accurate content height — it needed THE DRAG AND THE THUMB TO SHARE ONE SCALE, and
        // gestureSpan was the only quantity taken from somewhere else. The composite change (2026-09-05)
        // broke exactly that: it grew getScrollRange() by appBarRange and left this divisor on the old
        // liveRange(), 633px smaller on the artist page.
        //
        // WHAT THAT COST, measured before the fix: with a STATIONARY finger thumbOffset is constant, so
        // matched spans reduce `fraction` to thumbOffset/thumbOffsetRange — invariant, delta 0, no scroll.
        // Mismatched, `fraction` moves whenever range moves, emits a delta, the scroll changes which
        // children are attached, range moves again, and the sign flips. The capture shows the closed loop:
        //     req= 237 offset= 988 range=3743 thumbFrac=0.595
        //     req=-237 offset=1855 range=4767 thumbFrac=0.691
        // repeating at 16ms for 600ms+ with the finger still — an 867px alternation at 60Hz, which is the
        // on-device "flashing two screens".
        //
        // ⚠️ ANY FUTURE CHANGE TO getScrollRange() MUST MOVE THIS SPAN WITH IT. That coupling is what
        // failed here and will fail the same way again — silently, as a frame-rate oscillation rather than
        // a wrong number. The two expressions are load-bearing as a PAIR.
        //
        // NOT FROZEN AT GESTURE START, deliberately: the library keeps recomputing mThumbOffset from a LIVE
        // getScrollRange() every pre-draw, so a frozen divisor here would put the drawn thumb and the drag
        // back on different scales — the 2026-09-04 round-3 failure, where the thumb wandered away from the
        // finger mid-drag. That is a LESS VISIBLE failure than flashing and therefore the more dangerous
        // one to ship.
        val librarySpan = (getScrollRange() - view.height).coerceAtLeast(1)
        val fraction = offset.toDouble() / librarySpan
        // Computed once and reused by the end-of-rail branch below, so the two cannot drift apart.
        val firstCall = !gestureActive || lastFraction.isNaN()
        if (firstCall) landedAtEnd = false
        // ⚠⚠ THE END OF THE RAIL LANDS ON THE LAST ITEM, BECAUSE THE PIXEL
        // CEILING RECEDES AS THE DRAG DESCENDS AND THE DELTA PATH CANNOT REACH IT. The ceiling
        // is getScrollRange() - view.height, and getScrollRange() GROWS as taller children attach
        // (ScrollbarHelper extrapolates laidOutArea / laidOutRange * itemCount from the attached
        // window - see [getScrollOffset]'s note and the Search capture below, 4082 at the top and
        // 5918 further down on 33 items). So the bottom moves away from the finger: ON DEVICE, one
        // full-rail drag down a large playlist stops just short and a second drag arrives. The TOP
        // needs nothing - a target below 0 is clamped by scrollBy, which is exactly why song 1 was
        // always reachable and only the bottom was not.
        // ⚠️ THE CONDITION IS GEOMETRY, NOT THE ESTIMATE, WHICH IS WHY IT CANNOT
        // FIRE MID-LIST. FastScroller hands us scrollOffset = scrollOffsetRange *
        // clamp(thumbOffset, 0, thumbOffsetRange) / thumbOffsetRange, with thumbOffset =
        // mDragStartThumbOffset + (eventY - mDragStartY) - pure finger pixels - and
        // thumbOffsetRange = height - padding - thumbHeight, pure view geometry (AndroidFastScroll
        // 1.3.0, decoded from the AAR). scrollOffsetRange IS our librarySpan, read in the same
        // synchronous call, so `offset >= librarySpan` holds if and only if the library's own clamp
        // fired: the finger is at or past the bottom of the track. For any clamped value below
        // thumbOffsetRange the integer division gives strictly less than librarySpan, so there is
        // no fuzzy band to choose and no tolerance to tune.
        // ⚠️ A TRACK TAP AT THE VERY BOTTOM TAKES THIS BRANCH TOO, which is
        // intended: FastScroller builds mDragStartThumbOffset from the tap position and clamps it
        // the same way, so a tap on the last pixels of the rail now means "the end".
        // ⚠️ THE SEEDING ASSIGNMENTS BELOW MUST NOT BE SKIPPED. If a gesture's
        // FIRST call is already at the end (that track tap), returning without setting gestureSpan
        // would leave the next move computing a delta against the PREVIOUS gesture's span - the
        // mismatched-span failure of 2026-09-05, which on device was a 60Hz alternation between two
        // scroll positions.
        // ⚠️ scrollToPositionWithOffset DISPATCHES NO NESTED SCROLL, so it cannot
        // collapse a header on its own - hence the setExpanded; and it is also why nothing else in
        // this file's parent analysis (SwipeRefreshLayout, BottomSheetBehavior, AppBarLayout)
        // changes: the whole drag still travels through nestedScrollBy, only the landing does not.
        // The snap is a no-op when the header is already collapsed, which it is after any drag
        // that crossed the first appBarRange pixels of the composite range.
        // ⚠️ THE RAIL'S BOTTOM IS THE LAST LOADED ITEM, NOT THE LAST TRACK.
        // PagedSource sets enablePlaceholders = false, so adapter.itemCount counts loaded items
        // only; landing there trips Paging's prefetch and the list grows underneath. That is what
        // the delta path already did at the loaded edge - the landing does not change which items
        // exist, only that the edge is reached in one drag instead of approached.
        if (offset >= librarySpan) {
            val last = (view.adapter?.itemCount ?: 0) - 1
            if (last >= 0 && !landedAtEnd) {
                landedAtEnd = true
                if (appBarRange > 0 && appBarConsumed < appBarRange)
                    appBar?.setExpanded(false, false)
                (view.layoutManager as? LinearLayoutManager)
                    ?.scrollToPositionWithOffset(last, 0)
            }
            gestureSpan = librarySpan
            lastFraction = fraction
            pendingPixels = 0.0
            return
        }
        landedAtEnd = false
        if (firstCall) {
            // ⚠️ FOURTH ATTEMPT AT gestureSpan, AND IT WORKS BY DELETING WHAT THE OTHER THREE WERE TUNING.
            // DO NOT REINTRODUCE AN ESTIMATE HERE. Three revisions calibrated a running per-item mean —
            // live sample, then asymmetric EMA, then symmetric EMA — and each was wrong by a different
            // amount because they were all estimating a quantity that does not exist.
            //
            // RecyclerView's computeVerticalScrollRange is not a measurement of the content. Read
            // ScrollbarHelper.computeScrollRange (recyclerview 1.4.0): it returns
            // `laidOutArea / laidOutRange * itemCount` — an extrapolation from whichever children are
            // attached RIGHT NOW. On a mixed feed (full-span shelves beside 2-up tiles) that is
            // position-dependent by construction, so there is no true content height to converge on. A
            // capture on Search, 33 items throughout, measured 4082 at the top and 5918 further down and
            // then exactly 4082 again on returning — the repeat is determinism, not oscillation: same
            // viewport, same children, same arithmetic. No mean can smooth that into a fixed number.
            //
            // AND ACCURACY WAS NEVER THE REQUIREMENT. The thumb does not need to know how tall the content
            // is; it needs the DRAG and the THUMB to share one scale. FastScroller draws the thumb from
            // getScrollOffset()/getScrollRange(), both of which come from that same extrapolation, so the
            // thumb is self-consistent however the scale moves. gestureSpan was the only quantity taken
            // from somewhere else — and being derived from an average across every region scrolled, it sat
            // ABOVE the live value at almost any grab point (est/live measured 1.19-1.69), so each finger
            // pixel bought too much scroll and the thumb ran ahead. Setting it to librarySpan makes the two
            // the same number, and the thumb stays under the finger by construction rather than by
            // calibration.
            //
            // RESIDUAL, stated so it is not mistaken for a new fault: if the scale shifts DURING a long
            // drag (new children attach with different heights), the fraction-to-pixels mapping drifts.
            // But it drifts IDENTICALLY for the thumb and for the content, because both now read the same
            // extrapolation — which is the property that matters. The estimate broke exactly that.
            //
            // ⚠⚠ [2026-09-13, REVISED] TWO REPORTED SEARCH FAULTS ARE ONE FAULT - THIS ONE -
            // IN OPPOSITE DIRECTIONS. They were investigated separately and ruled INDEPENDENT; that was
            // wrong, and it was wrong because fault 1 had been described as a GRAB failure when it is a
            // VISIBILITY failure. One quantity, position-dependent on a mixed feed, two symptoms:
            //   ESTIMATE TOO LARGE where the drag starts -> each finger pixel buys too much scroll ->
            //     travel exhausts before the content does -> THE DRAG STOPS ~TWO-THIRDS DOWN.
            //     (est/live 1.69, 1.19, 1.60, measured on this screen.)
            //   ESTIMATE TOO SMALL over the card/tile sections -> getScrollRange() - view.height <= 0 ->
            //     FastScroller's `mScrollbarEnabled = scrollOffsetRange > 0` goes FALSE -> NO THUMB IS
            //     RENDERED THERE AT ALL. Crossed and uncrossed as you scroll, which is what is seen.
            // ⚠⚠ MEASURED ON DEVICE 2026-09-13, AND IT IS NOT "CARD SECTIONS BREAK IT" - IT IS
            // "A WINDOW DOMINATED BY CARD ROWS BREAKS IT". Anyone fixing this by special-casing card
            // layouts would be treating the wrong thing.
            // THE SCREEN (Deezer Search landing, top to bottom):
            //     Genres                        cards - 3 rows x 2 columns (6 items)
            //     Categories                    cards - 3 rows x 2 columns (6 items)
            //     Discover something new        carousel
            //     Albums of the week            carousel
            //     This week's freshest releases carousel
            //     Today's hits                  carousel
            //     Explore all                   cards - 3 rows x 2 columns (6 items)
            // CARDS ARE GRIDS OF TILES; A CAROUSEL IS ONE OUTER-LIST ITEM HOLDING A ROW OF MANY CARDS.
            // Those two were conflated in earlier analysis and the distinction is the whole mechanism:
            // over cards a row height covers TWO items; over a carousel a row height covers ONE item and
            // the row is much taller. PIXELS-PER-ITEM DIFFERS BY ROUGHLY 4-5x BETWEEN THEM - and
            // pixels-per-item is exactly what ScrollbarHelper.computeScrollRange averages
            // (laidOutArea / laidOutRange * itemCount).
            // ⚠⚠ THE DECIDING OBSERVATION - SAME SECTION TYPE, OPPOSITE BEHAVIOUR: the thumb is
            // MISSING over Genres and Categories at the top, and PRESENT AND GRABBABLE over "Explore
            // all" at the bottom. Identical layout, so the section type cannot be the cause.
            // What differs is the ATTACHED WINDOW. At the bottom the window still holds tall carousel
            // rows above, pulling the average up and keeping the estimate over the threshold. At the top
            // there is nothing tall above, the window is almost entirely two-up card rows, the average
            // collapses, and scrollOffsetRange drops below zero.
            // ⚠️ THIS IS BETTER EVIDENCE THAN THE est/live RATIOS ALONE: a measured
            // position-dependence on a real screen, with a control (the same layout behaving both ways)
            // rather than three numbers from one direction.
            //
            // ⚠️ SO VISIBILITY IS DRIVEN BY THIS ESTIMATE, NOT BY TOUCH AND NOT BY ALPHA. The
            // threshold is documented in this file's header (the short-list paragraph) and needs no
            // explicit gate. The library's auto-hide animates a thumb that EXISTS; this is the thumb not
            // existing. Cross-reference the header's padding note - computeVerticalScrollExtent is
            // getTotalSpace (height minus vertical padding) while getScrollOffsetRange subtracts the FULL
            // height, so the range adds the padding back. mScrollbarEnabled sits on that same quantity.
            //
            // ⚠⚠ AND THE SEPTEMBER CHANGE IS ORTHOGONAL TO BOTH, NOT A PARTIAL FIX OF EITHER.
            // Setting gestureSpan = librarySpan made the drag and the thumb share ONE SCALE - a
            // CONSISTENCY property between two consumers of the estimate. Neither surviving symptom is a
            // consistency problem: reachability needs the estimate STABLE DURING A DRAG, visibility needs
            // it to EXCEED THE VIEWPORT AT EVERY POSITION, and sharing a scale supplies neither (both
            // consumers simply agree there is no thumb). BOTH REMAINING SYMPTOMS ARE THE SAME UNTOUCHED
            // RESIDUAL.
            //
            // ⚠️ THIS IS A KNOWN UPSTREAM DEFECT, NOT OUR MISTAKE - WHICH CHANGES WHAT A FIX WOULD
            // MEAN. The September session found the library's own RecyclerViewHelper has the SAME defect,
            // with TWO OPEN UPSTREAM ISSUES against it. So some of the original symptoms were never
            // introduced by this app, and any fix here is a WORKAROUND for something the maintainers have
            // not solved either - judge it by that standard, not as correcting our own error.
            // ⚠️ THOSE TWO ISSUES ARE UNCHARACTERISED IN THE RECORD - nobody has written down
            // whether they describe REACHABILITY, VISIBILITY, or something else. Reading them is the
            // closest thing to a lead that exists, and it costs one look at the tracker. Do that before
            // designing anything.
            //
            // ⚠⚠ [2026-09-13] THAT DISMISSAL DOES NOT COVER REACHABILITY, AND A REPORTED FAULT
            // SITS IN THE GAP. SYMPTOM: on the Search landing, grabbing the thumb at the very top and
            // dragging down STOPS ABOUT TWO-THIRDS OF THE WAY AND WILL NOT REACH THE BOTTOM.
            // ⚠⚠ THE NUMBERS ARE ALREADY IN THIS NOTE, WHICH IS WHY THIS IS NEAR-PROOF RATHER
            // THAN A HYPOTHESIS: the Search capture recorded above measured 4082 at the top and 5918
            // further down, 33 items throughout. 4082 / 5918 = 0.69. "ABOUT TWO-THIRDS" IS NOT AN
            // APPROXIMATION OF THE SYMPTOM - IT IS THE NUMBER THIS CODE PRODUCES.
            // MECHANISM: getScrollRange() grows as the drag descends, so getScrollOffsetRange() =
            // getScrollRange() - view.height grows with it, and the bottom RECEDES as the finger
            // approaches it.
            // ⚠️ WHY THE PARAGRAPH ABOVE MISSED IT: "drifts identically for the thumb and for
            // the content" is sound for KEEPING THE THUMB UNDER THE FINGER, and says nothing about whether
            // the END IS REACHABLE. Those are different properties of the same drift. So this is a known
            // residual whose dismissal rested on a property that does not apply to this symptom - not a
            // new fault, and not one the September work refuted.
            // ⚠️ NOT FIXED, AND THE OBVIOUS FIX IS NEITHER DEAD NOR CLEAR. Freezing
            // getScrollRange() for the duration of a drag is NOT the same operation as the freeze already
            // refuted here - that one froze childCount INSIDE the extrapolation's denominator, leaving the
            // other terms live, which made the ceiling internally inconsistent and pinned the thumb.
            // Freezing the whole composite at DOWN keeps every term consistent. Its own untested risk is
            // the mirror image: frozen at the top means frozen at 4082, which could make the bottom
            // unreachable a different way. Left unproposed - the Search screen has a SECOND, unrelated
            // fault (an initial thumb grab over the card sections is refused), and one fault at a time.
            gestureSpan = librarySpan
            lastFraction = fraction
            pendingPixels = 0.0
            // ⚠⚠ THIS LINE SETS THE COST OF THE WHOLE GESTURE, AND IT IS O(DRAG DISTANCE).
            // An absolute positioning gesture is expressed here as a RELATIVE scroll:
            // LinearLayoutManager.scrollBy -> fill() lays out and recycles EVERY child across the
            // delta, so dragging the thumb across a 3514-item feed lays out every item in between.
            // THREE DISTINCT ANR/JANK COSTS HAVE NOW BEEN FOUND ON THIS ONE GESTURE, AND ALL THREE
            // ARE PER CHILD LAID OUT:
            //   1. nested carousel binding      - fixed 2026-09 by moving to nestedScrollBy (GapWorker)
            //   2. the span-group walk          - mitigated 2026-09-22 by FeedAdapter using peek
            //   3. Coil request restart on view attach (ViewTargetRequestManager
            //      .onViewAttachedToWindow -> restart -> enqueue) - open
            // Each fix reduces a CONSTANT. None of them changes the ORDER, because the multiplier is
            // "how many children get laid out" and that is decided here. Expect a fourth.
            //
            // ⚠⚠ COALESCING TOUCH MOVES TO ONE PER FRAME DOES NOT HELP - AND IT IS THE FIRST
            // THING ANYONE WILL REACH FOR. fill() is O(distance), so THREE 1000px scrolls and ONE
            // 3000px scroll lay out the SAME CHILDREN and do the same per-child work. Coalescing
            // saves per-pass overhead only. Do not spend a build on it.
            //
            // ⚠️ THE ORDER-OF-GROWTH FIX IS ABSOLUTE POSITIONING - scrollToPositionWithOffset,
            // which discards the layout and builds ONE SCREEN regardless of distance. It divides all
            // three costs at once, including a fourth nobody has found. TWO OBSTACLES, BOTH REAL:
            //   (a) IT SUPERSEDES THE SEPTEMBER DECISION RATHER THAN EXTENDING IT. nestedScrollBy was
            //       chosen because it is the only public API reaching nestedScrollByInternal, i.e. the
            //       only one that posts to GapWorker - see the note above. scrollToPositionWithOffset
            //       gets no GapWorker, so carousel binding returns to the synchronous pass, for ONE
            //       screen instead of N. Probably a large net win; that is a MEASUREMENT, not an
            //       argument, and it must be measured before the switch.
            //   (b) ⚠⚠ IT NEEDS A PIXEL->POSITION MAPPING, WHICH MEANS CONFRONTING
            //       getScrollRange() RATHER THAN WORKING AROUND IT - AND THAT IS THE SAME UNSTABLE
            //       MODEL BEHIND THE "STOPS TWO-THIRDS OF THE WAY DOWN" SEARCH FAULT recorded above
            //       (4082/5918 = 0.69). SO THE STRUCTURAL FIX FOR THE DRAG ANRs AND THE FIX FOR THE
            //       SEARCH SCROLLER FAULT ARE THE SAME PIECE OF WORK. Anyone scoping either one
            //       separately is scoping half of it and will meet the other half mid-build. This is
            //       the single most useful thing on this line: PLAN THEM TOGETHER.
            val jump = offset - view.computeVerticalScrollOffset()
            if (jump != 0) {
                logDrag(jump)
                view.nestedScrollBy(0, jump)
            }
            return
        }
        pendingPixels += (fraction - lastFraction) * gestureSpan
        lastFraction = fraction
        val delta = pendingPixels.toInt()
        if (delta == 0) return
        pendingPixels -= delta
        logDrag(delta)
        view.nestedScrollBy(0, delta)
    }

    // The three registrations mirror RecyclerViewHelper's own shapes (verified against the 1.3.0 AAR):
    // an ItemDecoration.onDraw for pre-draw, an OnScrollListener, and an OnItemTouchListener.
    override fun addOnPreDrawListener(onPreDraw: Runnable) {
        view.addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
                onPreDraw.run()
                // ── TRACE (2026-09-05). REMOVE WITH THE REST OF THE GladixScroll LINES. ──
                // The FIRST rest: line must come from here, not from onScrollStateChanged. That callback
                // fires only on a STATE TRANSITION, and a freshly-opened page is already IDLE — so "at the
                // top, never touched" produces no callback at all, which is precisely the state the
                // mid-rail-rest question needs. A whole capture came back with zero rest: lines for that
                // reason.
                //
                // This hook is also the RIGHT moment rather than merely an available one: it is what
                // FastScroller itself uses to recompute mThumbOffset, so the metrics logged here are the
                // ones that position the thumb on this frame. A post-layout one-shot could read before the
                // adapter has content.
                //
                // Guarded on a laid-out view with items so an empty first frame does not consume the
                // one-shot. After it fires the flag short-circuits, so the steady-state cost is one field
                // read per draw. onScrollStateChanged keeps its emission — it is the only one that can
                // report atBottom.
                if (!loggedFirstRest &&
                    parent.height > 0 &&
                    (parent.adapter?.itemCount ?: 0) > 0
                ) {
                    loggedFirstRest = true
                    logRest()
                }
            }
        })
    }

    override fun addOnScrollChangedListener(onScrollChanged: Runnable) {
        view.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                onScrollChanged.run()
            }

            // REMOVE WITH THE TRACE.
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) logRest()
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
     * Marks the start and end of a FastScroller-driven gesture, which is what lets [scrollTo] treat the
     * first call as an absolute landing and every later one as a finger delta.
     *
     * ⚠️ DRIVEN OFF WHETHER FASTSCROLLER CONSUMED THE EVENT, NOT `view.scrollState`. FastScroller moves
     * the list itself, so RecyclerView's scrollState stays IDLE for the entire thumb drag and would never
     * open the gesture. FastScroller.onTouchEvent returns `mDragging`, so a consumed event means exactly
     * "it has the thumb or the track and is about to drive scrollTo".
     *
     * Ordering is deliberate and the track-TAP path depends on it: this runs AFTER the predicate, so on
     * the ACTION_MOVE where FastScroller decides a track tap has happened it calls scrollToThumbOffset
     * before [gestureActive] is set — which routes that first jump down [scrollTo]'s absolute branch,
     * where it belongs. A thumb GRAB sets the flag on ACTION_DOWN, and FastScroller issues no scrollTo
     * until the following ACTION_MOVE, so it is seeded by the NaN check instead.
     */
    private fun trackGesture(event: MotionEvent, consumed: Boolean) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> if (consumed) gestureActive = true
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                gestureActive = false
                // Cleared together: a fraction or remainder left behind would be applied against the NEXT
                // gesture's span, which is a different scale.
                lastFraction = Double.NaN
                pendingPixels = 0.0
            }
        }
    }
}
