package dev.brahmkshatriya.echo.utils.ui

import android.content.Context
import android.graphics.Rect
import android.view.View
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.AppBarLayout
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.ui.common.UiViewModel
import dev.brahmkshatriya.echo.utils.ContextUtils.getSettings
import dev.brahmkshatriya.echo.utils.ui.UiUtils.dpToPx
import dev.brahmkshatriya.echo.utils.ui.UiUtils.isRTL
import dev.brahmkshatriya.echo.utils.ui.UiUtils.isTv
import me.zhanghai.android.fastscroll.FastScroller
import me.zhanghai.android.fastscroll.FastScrollerBuilder


object FastScrollerHelper {
    const val SCROLL_BAR = "scroll_bar"
    // Default ON as of 2026-09-24, mirroring SettingsLookFragment's row. Sole read of the key; every
    // other mention is that row's `key =`.
    // ⚠️ [CORRECTED 2026-09-24] AN EARLIER VERSION OF THIS NOTE SAID "on TV it is the only
    // declaration too", IMPLYING THE NEW true DEFAULT REACHES TV. IT DOES NOT. This function has
    // exactly one caller, isFastScrollUsable below, which is `!isTv() && isScrollBarEnabled()` -
    // so isTv() short-circuits before the pref is read and no default here can put a scroller on
    // TV. The switch being absent from that screen is the SECOND gate, not the only one.
    // The wrong reading came from stopping at this line instead of following its single caller two
    // lines down; kept because the same shortcut is the obvious one to take again.
    fun View.isScrollBarEnabled() = context.getSettings().getBoolean(SCROLL_BAR, true)

    /**
     * The fast scroller is a DRAG-TO-SCROLL affordance, so it is never applied on TV regardless of the
     * setting: D-pad is the only input there and there is no pointer to grab the thumb with. Without this
     * a TV user who found the switch got a thumb that drew and auto-hid on scroll but could never be
     * touched - FastScroller drives everything from an OnItemTouchListener, and a D-pad generates no
     * MotionEvents.
     *
     * SettingsLookFragment hides the preference on TV too. Both are needed: the preference hide stops it
     * being turned on, this stops an already-set value (or one synced from a phone) taking effect.
     *
     * Focus safety was NOT the reason - it was checked and is a non-issue. FastScroller adds its thumb,
     * track and popup through ViewGroupOverlay.add(), not addView(), so they are never children of the
     * RecyclerView, never enter the focus tree, and cannot fight TvAwareRecyclerView's establishFeedFocus
     * / anchorFocusAt anchoring on the same recyclers.
     */
    private fun View.isFastScrollUsable() = !context.isTv() && isScrollBarEnabled()

    /**
     * Keeps the scroller's TRACK inside the real window insets. Call from the same inset block that pads
     * the RecyclerView, so the two move together.
     *
     * `applyTo` sets a flat 8dp on all four sides once, and that is all a call site gets if it throws the
     * returned [FastScroller] away — which every direct caller did, so on those screens the track ran under
     * the mini-player and the nav bar. `MainFragment.applyInsets` was the only place that kept the handle
     * and re-padded it, which is exactly why the four tab screens were correct and the other seven were
     * not. This is that block, extracted so both routes share one implementation.
     *
     * ⚠️ THIS MOVES WHERE THE TRACK IS DRAWN. IT DOES NOT CHANGE THE SCROLL RANGE. If a screen scrolls
     * past its last row into blank space, that is a metrics problem and this will not fix it - see
     * PixelFastScrollViewHelper.
     *
     * Null-receiver tolerant: `applyTo` returns null whenever the scroller is not applied (setting off, or
     * on TV), so call sites can wire this unconditionally instead of guarding.
     */
    /**
     * Pads the scroll TRACK so it clears the system bars and the mini-player.
     *
     * ⚠️ [top] IS A PARAMETER BECAUSE THE ANSWER DIFFERS BY LAYOUT, and getting it wrong is invisible
     * until someone looks closely at where the thumb sits. It defaults to [UiViewModel.Insets.top], which
     * is right for a FULL-BLEED list — Home, Search, Library, History put the RecyclerView under the
     * status bar, so the track must start below it.
     *
     * It is WRONG for a list inside a CoordinatorLayout + AppBarLayout (MediaDetailsFragment,
     * FeedFragment). There, HeaderScrollingViewBehavior sizes the RecyclerView to roughly
     * `viewport - collapsedToolbarHeight` and CoordinatorLayout positions it BELOW the header, so the
     * status bar belongs to the AppBarLayout and not to this view. Adding it anyway inset the track by a
     * status bar that is not there: the track started too low and was too short, so the thumb sat below
     * the top of its own track at scroll zero (looking "parked mid-track") and the thumb-to-content
     * mapping was compressed by that amount. Those sites pass `top = 0`.
     *
     * THE TEST TO APPLY at any new call site: does the RecyclerView itself extend under the status bar?
     * The same question `View.applyContentInsets` answers with its `vertical` argument — and the two must
     * agree about the same view, since they are padding the content and the track of one list. Where
     * applyContentInsets is given a top of 0, this must be too.
     *
     * ⚠️ Fixes the thumb's POSITION only. The collapsing-layout screens ALSO had a dead drag, and that was
     * an unrelated OnItemTouchListener registration-order problem — see the note at those call sites.
     */
    fun FastScroller?.applyInsets(
        context: Context,
        insets: UiViewModel.Insets,
        extraBottom: Int = 0,
        top: Int = insets.top,
    ) {
        this ?: return
        val pad = 8.dpToPx(context)
        val isRtl = context.isRTL()
        // FLUSH ON THE THUMB SIDE. FastScroller lays the thumb at
        // `isLayoutRtl ? padding.left : viewWidth - padding.right - mThumbWidth`, so the thumb rides the
        // END padding in both directions — the swap below is what keeps that true in RTL. It gets the
        // window inset only, with NO 8dp: the inset is real (nav rail, cutout) but the 8dp was a cosmetic
        // gap, and at 40dp wide against a carousel it read as the thumb floating off the edge.
        //
        // TOP AND BOTTOM ARE UNCHANGED and must stay so — they are not cosmetic. insets.top clears the
        // status bar / app bar, and insets.bottom + extraBottom carries the nav bar and the mini-player,
        // which is what stops the track running underneath it.
        val edge = insets.end
        val far = insets.start + pad
        val left = if (!isRtl) far else edge
        val right = if (!isRtl) edge else far
        setPadding(Rect(left, top + pad, right, insets.bottom + extraBottom + pad))
    }

    /**
     * Echo's fast-scroller look: a small circular thumb and no visible track, in place of
     * AndroidFastScroll's md2 style (8dp x 52dp rounded-rect thumb over a full-height 8dp bar).
     *
     * ⚠️ MUST be called AFTER useMd2Style(). That call sets BOTH drawables, so anything applied
     * before it is overwritten.
     *
     * Only the drawables change. Auto-hide-on-idle, drag, and tap-to-jump are all untouched - do NOT
     * call disableScrollbarAutoHide(), the fade when idle is the behaviour we want. There is no popup
     * to style: AndroidFastScroll only shows one when the adapter implements PopupTextProvider, and
     * none of ours do.
     *
     * See the two drawable files for why the track is transparent rather than removed, and why it
     * cannot be a ColorDrawable.
     *
     * KNOWN COST, carried here because this is where someone will look. FastScroller hit-tests the
     * track for tap-to-jump and expands every hit area to `afs_min_touch_target_size` (48dp), and
     * onTouchEvent is gated only on "is this list scrollable" - NOT on whether the thumb is currently
     * faded in. So a ~48dp strip along the edge is live on every scrollable list whenever the setting
     * is on. That predates this styling and is not caused by hiding the track.
     *
     * It matters because `more` (the ⋯ overflow) is the rightmost control on every media row -
     * item_shelf_media, item_history, item_shelf_video - so the strip overlaps it.
     *
     * ✅ TESTED ON DEVICE 2026-09-04 — ACCEPTABLE, AND THIS GATE IS CLOSED. Do not re-run it, and do not
     * reach for the dimen override below. The reasoning above OVERSTATED the reach: the collision is
     * THUMB-POSITION-ONLY, not track-wide. Only the ⋯ on the single row the thumb is physically sitting
     * in front of is unreachable; rows at the same right edge above and below it open their menus
     * normally. So the cost is one row briefly blocked by a VISIBLE control, cleared by scrolling - not
     * an invisible strip eating taps down the whole edge, which is what the paragraph above predicted and
     * what would genuinely have argued against default-on.
     *
     * ➤ THE FIX, NOT APPLIED AND NOT NEEDED: `afs_min_touch_target_size` is read with
     *   Resources.getDimensionPixelSize, so an app-side <dimen> of that name overrides the library's 48dp
     *   by normal resource merging - no fork required. It shrinks the THUMB's grab area too and takes it
     *   under the 48dp accessibility floor, which is why it stays unapplied: the measured cost does not
     *   justify it. Kept here only so the option is documented if the behaviour ever changes upstream.
     */
    private fun FastScrollerBuilder.applyEchoStyle(context: Context) {
        setTrackDrawable(AppCompatResources.getDrawable(context, R.drawable.fast_scroll_track)!!)
        // ⚠⚠ THE GRAB REGION IS A NARROW RIGHT-EDGE STRIP, AND ITS NUMBERS ARE DERIVABLE BUT
        // WERE NEVER WRITTEN DOWN. Salvaged 2026-09-13 from a deleted touch probe, because a capture
        // full of x-coordinates is unreadable without it.
        // FastScroller lays the thumb out at `viewWidth - padding.right - mThumbWidth` (see the RTL note
        // above), the drawable declares android:width="40dp", and applyTo sets 8dp padding on all four
        // sides. So on a 1080px-wide screen at density 3.0 the thumb occupies roughly x 936..1056, and a
        // press at x=1077 is OUTSIDE IT - in the 8dp end padding, not on the thumb.
        // ⚠️ SO AN x-SPREAD IN A TOUCH CAPTURE HAS TWO CAUSES, AND THEY LOOK ALIKE: presses
        // outside ~936..1056 miss the thumb GEOMETRICALLY, while presses inside it can still miss
        // because the thumb IS NOT RENDERED at that scroll position (see PixelFastScrollViewHelper's
        // extrapolation note - mScrollbarEnabled rides the same estimate). Same failed grab, two
        // mechanisms; separate them by x before reading anything else into a capture.
        setThumbDrawable(AppCompatResources.getDrawable(context, R.drawable.fast_scroll_thumb)!!)
    }

    /**
     * [appBar] is the collapsing header ABOVE this list, or null on a full-bleed screen. Passing it makes
     * the scroll metrics COMPOSITE — see PixelFastScrollViewHelper's note. Null keeps today's arithmetic
     * exactly, so the six full-bleed call sites are unchanged by construction rather than by testing.
     * [traceTag] labels the GladixScroll trace lines.
     * ⚠⚠ RETAINED DELIBERATELY - NOT PENDING A REMOVAL CONDITION (2026-09-12). The GladixScroll
     * trace is kept at the user's explicit request for an OPEN Search investigation (the 2/3 stall and the
     * short-of-bottom case); it is the instrument that investigation needs. It was reviewed in the
     * 2026-09-12 temporary-logging inventory and deliberately left in place.
     * So "REMOVE WITH THE TRACE" markers in this file and PixelFastScrollViewHelper describe what goes
     * TOGETHER when the trace eventually goes - they are NOT a signal that it is ready to go now.
     */
    /**
     * ⚠️ THE COMPOSITE PATH WENT LIVE AGAIN ON 2026-09-07 AND HAS NOT RUN SINCE 4c4fb267. READ THIS
     * BEFORE FILING A THUMB DEFECT ON ARTIST/ALBUM/PLAYLIST DETAIL.
     *
     * [appBar] makes PixelFastScrollViewHelper's arithmetic composite: appBarRange
     * (appBar.totalScrollRange) and appBarConsumed (-verticalOffset, from an OnOffsetChangedListener) are
     * added to the list's own extent and offset. BOTH TERMS ARE ZERO WHENEVER NO CHILD OF THE AppBarLayout
     * CARRIES A `scroll` FLAG — AppBarLayout.getTotalScrollRange() breaks its loop at i=0 and returns
     * Math.max(0, 0) = 0 (decoded from the 1.14.0 AAR) — so on a non-collapsing screen the whole thing
     * reduces to the flat-screen path exactly.
     *
     * That is what artist/album/playlist detail did between the 2026-09-05 header migration (5e4b9413) and
     * the 2026-09-07 CollapsingToolbarLayout restore: no scroll flags, both terms 0, reduced path. The
     * restore puts a CTL back, so TOTALSCROLLRANGE IS NON-ZERO AGAIN AND THE FULL ARITHMETIC RETURNS IN ONE
     * STEP, WITH NO CODE CHANGE HERE. Nothing in this file or in PixelFastScrollViewHelper was edited for
     * it; the terms simply stopped being 0.
     * SO: IF THE THUMB MISBEHAVES ON THOSE PAGES AFTER 2026-09-07, READ IT AS THE COMPOSITE PATH RETURNING,
     * NOT AS A NEW DEFECT. The suspect is this arithmetic, which last ran in anger under 4c4fb267.
     *
     * ✅ CONFIRMED CLEAN ON DEVICE, BUILD 1088 (2026-09-08) — THIS IS NO LONGER AN OPEN RISK. Artist and
     * album collapse correctly (cover at top, name below it, name shrinking into the toolbar on scroll, one
     * cover), the thumb rests below the header exactly as the trade predicted, and HOLDING A STILL FINGER
     * MID-DRAG PRODUCES NO FLASHING. That last one is the specific thing that had never been tested with the
     * composite live: the matched-span fix holds with appBarRange/appBarConsumed non-zero. Keep the
     * paragraphs above — they are still the right reading if a NEW symptom appears — but do not treat the
     * reactivation itself as unverified.
     *
     * IT SHOULD BEHAVE BETTER THAN IT DID THEN, NOT WORSE — three fixes landed after 4c4fb267 and all are
     * still in place: the matched-span fix, the pre-draw rest hook, and the span-aware revert. None of them
     * was touched by the migration or by the restore, so the composite path returns onto a repaired base
     * rather than the one that produced the original symptoms.
     *
     * KNOWN AND ACCEPTED, NOT A DEFECT: with a collapsing header the rail can only span the RecyclerView,
     * which HeaderScrollingViewBehavior positions BELOW the header — so the thumb rests below the header
     * and is not grabbable until the header scrolls away. That trade was made deliberately at the restore;
     * see the note in fragment_media.xml before trying to "fix" it.
     */
    fun applyTo(
        view: RecyclerView,
        appBar: AppBarLayout? = null,
        traceTag: String = "?",
    ): FastScroller? {
        view.isVerticalScrollBarEnabled = false
        if (!view.isFastScrollUsable()) return null
        return FastScrollerBuilder(view).apply {
            useMd2Style()
            applyEchoStyle(view.context)
            // Replaces the library's RecyclerViewHelper, which estimates every item's height from
            // getChildAt(0) alone and so makes the thumb race, snap back and vanish on any screen with
            // mixed row heights. See PixelFastScrollViewHelper for the ViewHelper contract, for why
            // RecyclerView's own averaged estimates satisfy it, and for why the earlier position-based
            // attempt could not. RecyclerView ONLY — the NestedScrollView overload below keeps the
            // library's default helper, which is already this shape there.
            setViewHelper(PixelFastScrollViewHelper(view, appBar, traceTag))
            // Pre-inset default; applyInsets overwrites it on the first inset pass. Flush on the thumb
            // side here too so the first frame does not show the gap and then close it.
            val pad = 8.dpToPx(view.context)
            val isRtl = view.context.isRTL()
            setPadding(if (isRtl) 0 else pad, pad, if (isRtl) pad else 0, pad)
        }.build()
    }

    fun applyTo(view: NestedScrollView): FastScroller? {
        view.isVerticalScrollBarEnabled = false
        if (!view.isFastScrollUsable()) return null
        return FastScrollerBuilder(view).apply {
            useMd2Style()
            applyEchoStyle(view.context)
            val pad = 8.dpToPx(view.context)
            setPadding(pad, pad, pad, pad)
        }.build()
    }

}
