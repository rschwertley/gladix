@file:Suppress("ASSIGNED_BUT_NEVER_ACCESSED_VARIABLE")

package dev.brahmkshatriya.echo.utils.ui

import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager.widget.ViewPager
import androidx.viewpager2.widget.ViewPager2
import androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback
import kotlin.math.abs

object ViewPager2Utils {

    fun ViewPager2.supportBottomSheetBehavior() {
        val recycler = getChildAt(0) as RecyclerView
        recycler.run {
            isNestedScrollingEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
    }

    /**
     * Calls [onSwipe] when the user drags backwards while already on the FIRST page.
     *
     * ⚠⚠ THIS IS THE ONLY WAY TO SEE THAT GESTURE - ViewPager2 REPORTS NOTHING. At the start
     * edge there is no page to scroll to, supportBottomSheetBehavior has already set
     * OVER_SCROLL_NEVER and isNestedScrollingEnabled = false, so there is no scroll, no overscroll, no
     * onPageScrolled fraction and no onPageSelected. registerOnUserPageChangeCallback below cannot
     * observe it, which is also why the two cannot collide: the forward-swipe `index != pos` guard in
     * PlayerFragment lives in that callback and is never reached from here.
     *
     * ⚠⚠ OBSERVE-ONLY: onInterceptTouchEvent ALWAYS RETURNS false, AND THAT IS LOAD-BEARING.
     * RecyclerView.dispatchOnItemTouch latches the FIRST listener that intercepts and routes
     * everything to it (upstream AndroidFastScroll issue #53, the dead-thumb family). Never
     * intercepting means this listener cannot latch, cannot starve ViewPager2's own drag handling, and
     * cannot take vertical drags away from the BottomSheet - and it keeps receiving the full event
     * stream, because RecyclerView keeps offering events to non-intercepting listeners.
     * ⚠️ REGISTRATION ORDER IS THEREFORE IRRELEVANT HERE, and that claim was checked rather
     * than assumed: order only decides which INTERCEPTING listener wins, and this one never does. It
     * is moot twice over - the pager's RecyclerView currently has NO other OnItemTouchListener (the
     * app's only addOnItemTouchListener is PixelFastScrollViewHelper's, on list screens, not here), and
     * FixOnItemTouchListenerRecyclerView - named in the record as the gate for ordering - IS NOT IN
     * THIS TREE; it is a library class mentioned only in that helper's comment.
     *
     * ⚠️ HORIZONTAL-DOMINANT WITH A THRESHOLD, so a vertical drag still belongs to the sheet
     * and a tap still reaches clickPanel.
     * ⚠⚠ AND IT FIRES ON ACTION_UP, NOT ON THE QUALIFYING MOVE. Acting mid-gesture would
     * insert the previous track into the timeline while the finger is still down, so the list and the
     * page position would churn underneath the drag - the June tearing shape, where an incremental
     * queue edit fires onTimelineChanged -> emitFullQueue -> submitList -> setCurrentItem and renders
     * intermediate states. Waiting for the lift means the edit lands on a settled gesture.
     * ⚠️ ACTION_CANCEL CLEARS WITHOUT FIRING. A cancel means the gesture was taken over by an
     * ancestor - in practice the BottomSheet claiming a drag that started sideways - and acting on it
     * would change tracks because the user dragged the SHEET.
     * [CORRECTED 2026-11-20] "A drag that started sideways" was not an edge case, it was most back swipes: 2 of 10
     * worked on device (1184). At page 0 nothing ever locked the sheet out. RecyclerView asks its parent not to
     * intercept only once it has consumed scroll (RecyclerView 1.4.0 RecyclerView.java:3952, 3962), and here there
     * is nothing to scroll. So the player sheet's ViewDragHelper took any drag with more than touch slop of downward
     * drift (customview 1.2.0 ViewDragHelper.java:1426-1442; Material 1.14.0 BottomSheetBehavior.java:737), at any
     * point before the lift, and this listener got the cancel. Forward swipes were safe because RecyclerView locks
     * at its first consumed scroll. The lock below gives page 0 the same.
     * ⚠⚠ [2026-11-20] THE ONE THING THIS DOES BESIDES OBSERVE: once a drag at page 0 is backwards past
     * scaledPagingTouchSlop (the slop ViewPager2's RecyclerView starts dragging at, TOUCH_SLOP_PAGING) and more
     * sideways than vertical, it calls parent.requestDisallowInterceptTouchEvent(true), once per gesture. This is
     * the same call and the same distance a forward swipe gets from RecyclerView, and the pattern of Google's
     * ViewPager2 NestedScrollableHost sample. It still never intercepts, fires or writes the page. The framework clears
     * the flag on the next DOWN. A drag that goes more than touch slop downward first still goes to the sheet. Once
     * locked, a gesture that turns vertical lifts to nothing, as a forward swipe does. On the mini bar
     * (item_player_collapsed_controls has no clickable root, so touches reach the pager) the same holds:
     * a drag up is vertical-first and still expands the player.
     * ⚠️ qualified IS RE-EVALUATED ON EVERY MOVE, not latched. A drag that goes back the other
     * way, or turns vertical, un-qualifies itself and lifts to nothing - which is what a user who
     * changed their mind mid-drag expects.
     * Does NOT touch currentItem or setCurrentItem - the page position keeps its existing single
     * writer, which is what the "one behind" regression was caused by adding a second one.
     */
    fun ViewPager2.onFirstPageBackSwipe(onSwipe: () -> Unit) {
        val recycler = getChildAt(0) as? RecyclerView ?: return
        val pagingSlop = ViewConfiguration.get(context).scaledPagingTouchSlop
        val threshold = pagingSlop * 2
        recycler.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            private var downX = 0f
            private var downY = 0f
            private var qualified = false
            private var locked = false

            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.x
                        downY = e.y
                        qualified = false
                        locked = false
                    }

                    MotionEvent.ACTION_MOVE -> {
                        if (currentItem != 0) {
                            qualified = false
                            return false
                        }
                        val dx = e.x - downX
                        val dy = e.y - downY
                        // dx > 0 is a drag to the RIGHT, i.e. reaching for the page BEFORE this one.
                        // RTL is handled by ViewPager2's own layout direction, so compare against the
                        // layout, not the raw sign.
                        val backwards = if (rv.layoutDirection == View.LAYOUT_DIRECTION_RTL) -dx else dx
                        qualified = backwards > threshold && backwards > abs(dy)
                        // [2026-11-20] The sheet lock; see the CORRECTED note above.
                        if (!locked && backwards > pagingSlop && backwards > abs(dy)) {
                            locked = true
                            rv.parent?.requestDisallowInterceptTouchEvent(true)
                        }
                    }

                    MotionEvent.ACTION_UP -> {
                        val fire = qualified && currentItem == 0
                        qualified = false
                        if (fire) onSwipe()
                    }

                    MotionEvent.ACTION_CANCEL -> qualified = false
                }
                return false
            }
        })
    }

    fun ViewPager2.registerOnUserPageChangeCallback(
        listener: (position: Int, userInitiated: Boolean) -> Unit
    ) {
        var previousState: Int = -1
        var userScrollChange = false
        registerOnPageChangeCallback(object : OnPageChangeCallback() {

            override fun onPageSelected(position: Int) {
                listener(position, userScrollChange)
            }

            override fun onPageScrollStateChanged(state: Int) {
                if (previousState == ViewPager.SCROLL_STATE_DRAGGING &&
                    state == ViewPager.SCROLL_STATE_SETTLING
                ) {
                    userScrollChange = true
                } else if (previousState == ViewPager.SCROLL_STATE_SETTLING &&
                    state == ViewPager.SCROLL_STATE_IDLE
                ) {
                    userScrollChange = false
                }
                previousState = state
            }
        })
    }
}