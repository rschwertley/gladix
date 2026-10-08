package dev.brahmkshatriya.echo.ui.player.more.upnext

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.transition.MaterialSharedAxis
import dev.brahmkshatriya.echo.databinding.FragmentPlayerQueueBinding
import dev.brahmkshatriya.echo.ui.player.PlayerViewModel
import dev.brahmkshatriya.echo.utils.ContextUtils.observe
import dev.brahmkshatriya.echo.utils.ui.AnimationUtils.setupTransition
import dev.brahmkshatriya.echo.utils.ui.AutoClearedValue.Companion.autoClearedNullable
import org.koin.androidx.viewmodel.ext.android.activityViewModel

class QueueFragment : Fragment() {

    private var binding by autoClearedNullable<FragmentPlayerQueueBinding>()
    private val viewModel by activityViewModel<PlayerViewModel>()

    // ⚠⚠ A DRAG MUST NOT BE INTERRUPTED BY A RESUBMIT, AND THE VISIBLE JUMP IS THE LESSER HALF
    // OF WHY. onMove calls viewModel.moveQueueItems, which reorders the player's timeline, which emits
    // fullQueueFlow (after PlayerEventListener.emitFullQueue's 50ms debounce) -> queueFlow -> submit().
    // THE DRAG TRIGGERS ITS OWN RESUBMIT; no radio top-up or outside mutation is needed, which is why it
    // reproduced on every drag.
    // ⚠️ THE OBVIOUS FIX - SUPPRESS ONLY THE scrollToPosition - WOULD HAVE FIXED THE HALF YOU CAN
    // SEE AND LEFT THE DRAG STILL CANCELLABLE. Read from recyclerview 1.4.0:
    // ItemTouchHelper.onChildViewDetachedFromWindow (ItemTouchHelper.java:902-916) does
    // `if (mSelected != null && holder == mSelected) select(null, ACTION_STATE_IDLE);` - if a resubmit
    // recycles the row under the finger, THE DRAG IS TERMINATED. So the whole submit is deferred, not the
    // scroll. That is also what PlaylistTrackAdapter already does; this screen simply never got it.
    // ACCEPTED COST, STATED RATHER THAN WIDENED: tracks appended mid-drag (a radio top-up, say) do not
    // appear until the finger lifts. A few seconds of staleness beats a cancelled drag.
    private var isDragging = false

    // Captured at the same instant, and from the same expression, as ItemTouchHelper's own mSelectedStartY
    // (:687) - so the geom line below can report the gap between that frozen value and the tile's live
    // position. REMOVE WITH THE QUEUEDRAG SET.
    private var dragStartTop: Int? = null

    // ⚠⚠ TEMPORARY (2026-09-13) - REMOVE WITH THE QUEUEDRAG SET. THE FINGER, WHICH THE FIRST
    // THREE CAPTURES NEVER RECORDED. The probe logged the TILE (nowTop) while ItemTouchHelper's auto-scroll
    // trigger is computed from the FINGER: curY = mSelectedStartY + mDy, and mDy = e.y - e.y-at-DOWN
    // (ItemTouchHelper.updateDxDy :1185, raw viewport coordinates, nothing in the
    // onMove/onMoved/prepareForDrop chain offsets it). Those two bodies differ by `drift`, so three
    // captures measured a quantity the trigger does not use.
    // ⚠️ WHY NOT AN OnItemTouchListener, WHICH IS THE OBVIOUS HOOK: RecyclerView:3661 - once a
    // listener LATCHES (mInterceptingOnItemTouchListener), ONLY that one receives subsequent events. During
    // a drag ItemTouchHelper is latched, so an observer listener would log the DOWN and then go silent for
    // the whole gesture - the ambiguous-silence trap again. A View.OnTouchListener runs inside
    // View.dispatchTouchEvent BEFORE onTouchEvent, i.e. upstream of the item-touch dispatch entirely, so it
    // sees every event regardless of who latched. Returning false keeps it behaviour-neutral.
    private var fingerDownY = Float.NaN
    private var fingerY = Float.NaN

    // ⚠⚠ A BOOLEAN, AND THE REASON IS THE DIRECTION OF STALENESS, NOT STYLE. submit() takes no
    // argument and reads viewModel.queue and playerState.current fresh at call time, so storing a
    // snapshot would replay a list that may be several mutations stale by the time the finger lifts.
    // Recording only THAT a submit was suppressed lets the catch-up read the latest state.
    //
    // ⚠⚠ [CORRECTED 2026-10-07] THIS NOTE USED TO SAY "A BOOLEAN, NOT PlaylistTrackAdapter'S
    // pendingList" AND THAT "pendingList is NECESSARY there". BOTH HALVES ARE NOW WRONG, AND THE
    // SECOND WAS WRONG WHEN WRITTEN. pendingList is GONE (removed 2026-10-07 fixing GitHub #3): the
    // playlist editor had the pre-fix shape of THIS screen - onMove called only the view model and
    // never reordered the adapter - and its pendingList replay at clearView was restoring the
    // accumulated order over the correct one. Storing the deferred list was not necessary there; it was
    // the bug. What that screen needed was this screen's conclusion, carried across.
    // ⚠️ SO READ THE REASONING, NOT THE COMPARISON. The durable half is: during a drag the
    // ADAPTER is authoritative, so an external submit should be DROPPED rather than deferred, whatever
    // the mechanism for dropping is. Whether you need a stored value depends only on whether the
    // source re-emits - see ManageExtensionsFragment, where it does NOT.

    // ⚠⚠ PERMANENT, KEEP WHEN THE PROBE BELOW IS DELETED: ItemTouchHelper CAPTURES
    // mSelectedStartY ONCE AND NEVER RE-CAPTURES IT. Read from recyclerview 1.4.0:
    //   :687  mSelectedStartY = selected.itemView.getTop();     <- only assignment, inside select()
    //   :774  int curY = (int) (mSelectedStartY + mDy);         <- the auto-scroll trigger's position term
    //   :801  mRecyclerView.scrollBy(scrollX, scrollY);         <- moves EVERY child, including this one
    // So the moment drag auto-scroll fires once, every child shifts while mSelectedStartY stays at the
    // value it had at pickup. curY then over-states how far out of bounds the tile is by the WHOLE
    // accumulated scroll, which feeds straight back into the next frame's scroll amount.
    // moveIfNecessary's row swaps move it too, with the same absence of a re-capture.
    // ⚠️ CONSEQUENCE, AND IT IS NOT SCREEN-SPECIFIC: any drag auto-scroll on ANY screen
    // accelerates FASTER than Callback.interpolateOutOfBoundsScroll's 2s ramp alone implies, because the
    // out-of-bounds magnitude it is fed is itself growing. Two compounding terms, only one of them
    // documented. Recorded because it reads as a per-screen quirk and is a library-wide one.
    // ⚠⚠ [2026-09-12] THE LOCAL-REORDER FIX ADDED A SECOND DIVERGENCE SOURCE, AND THIS NOTE IS
    // NOW OPERATIVE RATHER THAN BACKGROUND. Before the fix nothing reordered, so no swap ever completed and
    // the only thing moving children was scrollIfNecessary's own scrollBy. Now every successful onMove runs
    // Callback.onMoved -> LinearLayoutManager.prepareForDrop -> scrollToPositionWithOffset, so the list is
    // scrolled ON EVERY ROW CROSSED while mSelectedStartY still holds the pickup value. curY is therefore
    // "where the tile would be if the list had never moved", and the error accumulates with the drag's
    // history - how many rows were crossed and in which order.
    // ⚠️ THAT IS THE LEADING EXPLANATION FOR "DRAGGING UP SOMETIMES SCROLLS AND SOMETIMES DOES
    // NOT", REPORTED ON DEVICE AFTER THE FIX. It is UNMEASURED and it is the FOURTH mechanism proposed for
    // this defect, so treat it as a hypothesis. It is better supported than the first three, and the
    // difference is worth stating rather than asserting confidence: mSelectedStartY's single assignment is
    // READ (:687, no other write), and the second divergence source is not speculation about the library -
    // it is a consequence of a change WE made, whose call chain is read end to end.
    // ⚠️ WHAT IS NOT A DEFECT, so it is not re-investigated: up and down are asymmetric BY
    // DESIGN. scrollIfNecessary's down branch adds itemView.getHeight(), so it triggers as soon as the
    // tile's BOTTOM reaches the list's bottom; the up branch needs the tile's TOP to clear the padded top
    // edge, which a finger may never reach on a tall row. "Down works cleanly, up is reluctant" is those
    // two expressions, not a bug.
    // ⚠️ AND THE "DISAPPEARING TILE" MAY NOT BE A SCROLL AT ALL: if the dragged view leaves the
    // window, onChildViewDetachedFromWindow (:902-916) calls select(null, ACTION_STATE_IDLE) and the drag
    // ENDS. The surviving QUEUEDRAG lines separate the two at no cost - clearView mid-drag with no further
    // onMove is a dropped drag; clearView only at the end with onMove continuing is the scroll geometry.
    // It explains RUNAWAY, NOT ONSET - the first frame's curY is not yet corrupted, which is why any
    // rebuilt probe must log the FIRST frame of each scroll run, and startTop against live getTop().

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentPlayerQueueBinding.inflate(inflater, container, false)
        return binding!!.root
    }

    private val queueAdapter: QueueAdapter by lazy {
        QueueAdapter(object : QueueAdapter.Listener() {
            override fun onDragHandleTouched(viewHolder: RecyclerView.ViewHolder) {
                android.util.Log.d("GladixQueue", "QUEUEDRAG handleTouched -> startDrag")
                touchHelper.startDrag(viewHolder)
            }

            override fun onItemClicked(position: Int) {
                viewModel.play(position)
            }

            override fun onItemClosedClicked(position: Int) {
                viewModel.removeQueueItem(position)
            }
        })
    }

    private val touchHelper: ItemTouchHelper by lazy {
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            ItemTouchHelper.START
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val fromPos = viewHolder.bindingAdapterPosition
                val toPos = target.bindingAdapterPosition
                if (fromPos == RecyclerView.NO_POSITION || toPos == RecyclerView.NO_POSITION)
                    return false
                // Seam 2/G2: keep current at index 0 — an upcoming track can't be dropped at/above the
                // current row, so nothing gets stranded above current.
                // ⚠⚠ [CORRECTED 2026-09-12] READ FROM queueAdapter.currentList, NOT viewModel.queue.
                // The note here used to say the opposite: "Current index read from the viewModel (same
                // source as submit()), NOT queueAdapter — referencing the adapter here creates a
                // by-lazy <-> by-lazy type-inference cycle with its touchHelper-using listener." That cycle
                // was real (18066872 moved the read for it) and IS NOW GONE: queueAdapter and touchHelper
                // both carry EXPLICIT types, so neither needs the other's type inferred.
                // And the read MUST come back here, because `toPos` is an ADAPTER index and the adapter now
                // holds a drag-local order that diverges from viewModel.queue for the rest of the gesture.
                // Comparing an adapter index against a viewModel-derived one would check the pin against a
                // stale position the moment the first row is crossed.
                val currentPos = queueAdapter.currentList.indexOfFirst { it.first != null }
                if (currentPos != -1 && toPos <= currentPos) return false
                android.util.Log.d("GladixQueue", "QUEUEDRAG onMove $fromPos->$toPos")
                // ⚠⚠ TEMPORARY - REMOVE WITH THE QUEUEDRAG SET. WHY IT IS LOGGED HERE AND NOT IN
                // interpolateOutOfBoundsScroll, WHICH IS WHERE THE LIBRARY DOES THE ARITHMETIC: that method
                // is called ONLY when scrollIfNecessary already decided to scroll (:787-796), so on the
                // failing case it emits NOTHING - and nothing is what the earlier oob probe produced, which
                // was then misread as "auto-scroll is not the mechanism". onMove fires once per row crossed
                // regardless of the outcome, so this prints on the frames where the threshold is MISSED,
                // which are the ones in question.
                // WHAT THE NUMBERS ARE: startTop is mSelectedStartY's value (same expression, same instant);
                // nowTop is the tile's LIVE layout position; drift is how far the frozen capture has fallen
                // behind after prepareForDrop scrolled the list under it. topFresh/bottomFresh are the two
                // library thresholds recomputed from the LIVE position instead of the stale one.
                // ⚠️ NOT LITERALLY THE LIBRARY'S NUMBERS: it uses curY = mSelectedStartY + mDy,
                // and mDy (the finger delta) is not reachable from here. These are "what the threshold would
                // be if the capture were fresh", which is exactly the comparison at issue. mTmpRect is 0 on
                // this screen - it holds ItemDecoration offsets and QueueFragment adds none - so that term
                // drops out honestly rather than being ignored.
                run {
                    val v = viewHolder.itemView
                    val start = dragStartTop
                    val nowTop = v.top
                    // mDy and the two lib* values are the library's OWN expressions, evaluated on our
                    // side: curY = startTop + mDy, then topDiff / bottomDiff exactly as
                    // ItemTouchHelper.scrollIfNecessary :775-785 computes them. mTmpRect is 0 here (no
                    // ItemDecoration on this list), so it drops out honestly.
                    val dy = if (fingerDownY.isNaN()) Float.NaN else fingerY - fingerDownY
                    val curY = (start ?: 0) + dy
                    android.util.Log.d(
                        "GladixQueue",
                        "QUEUEDRAG geom dir=${if (toPos < fromPos) "up" else "down"} " +
                            "startTop=$start nowTop=$nowTop drift=${start?.let { nowTop - it }} " +
                            "mDy=${dy.toInt()} " +
                            "libTopDiff=${(curY - recyclerView.paddingTop).toInt()} " +
                            "libBottomDiff=${(curY + v.height - (recyclerView.height - recyclerView.paddingBottom)).toInt()} " +
                            "topFresh=${nowTop - recyclerView.paddingTop} " +
                            "bottomFresh=${nowTop + v.height - (recyclerView.height - recyclerView.paddingBottom)} " +
                            "tileH=${v.height} rvH=${recyclerView.height}"
                    )
                }
                // ⚠⚠ THE LOCAL REORDER - THIS IS THE FIX, AND IT MUST SIT AFTER BOTH REFUSALS.
                // ItemTouchHelper's contract is that `true` means "I moved the items in my data set". We
                // returned true while moving nothing: onMove only called the PLAYER and waited for
                // queueFlow to come back, so getAbsoluteAdapterPosition never changed, moveIfNecessary
                // re-fired on the same from-index every frame, and onMoved -> LinearLayoutManager
                // .prepareForDrop scrolled the list instead. Measured on 1102: `onMove 22->21` ten times.
                // SAME INSTANCES, REORDERED - not rebuilt. areContentsTheSame therefore compares a value to
                // itself for every row, so DiffUtil emits ONE move and ZERO rebinds; see the note at
                // DiffCallback for why that is what keeps the dragged ViewHolder attached.
                // ORDER: after the NO_POSITION guard and the pin above, so the UI can never show a move the
                // player refused. Before moveQueueItems only for readability - that call is async and
                // returns immediately, so the two cannot interleave meaningfully.
                queueAdapter.submitList(
                    queueAdapter.currentList.toMutableList().apply { add(toPos, removeAt(fromPos)) }
                )
                viewModel.moveQueueItems(fromPos, toPos)
                return true
            }

            // Armed before any onMove can fire. Only ACTION_STATE_DRAG arms it - a swipe removes a row
            // outright and has no in-progress state to protect.
            override fun onSelectedChanged(
                viewHolder: RecyclerView.ViewHolder?, actionState: Int
            ) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    isDragging = true
                    dragStartTop = viewHolder?.itemView?.top
                }
                android.util.Log.d(
                    "GladixQueue", "QUEUEDRAG selected state=$actionState armed=$isDragging"
                )
            }

            // ⚠⚠ REACHABILITY IS TOTAL; THE TIMING IS DELIBERATELY NOT IMMEDIATE - READ BOTH
            // HALVES BEFORE "FIXING" THE LAG. clearView is reached from all five call sites in
            // recyclerview 1.4.0 (ItemTouchHelper.java:336, 504, 652, 678, 913), and the cancellation path
            // is covered rather than special-cased: a detach at :908 calls select(null, ACTION_STATE_IDLE),
            // which lands on :652/:678 like any other end. So the flag cannot latch true.
            // BUT IT IS NOT CLEARED WHEN THE FINGER LIFTS. On the animated path clearView fires at
            // onAnimationEnd (:652) and can defer further into mPendingCleanup - the library's own comment
            // there reads "wait until remove animation is complete". THE FLAG THEREFORE STAYS SET THROUGH
            // THE SETTLE ANIMATION, AND THAT IS THE POINT: submitting while the row is still animating home
            // recycles it exactly as badly as submitting mid-drag. This is a choice, not lag.
            override fun clearView(
                recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder
            ) {
                super.clearView(recyclerView, viewHolder)
                android.util.Log.d("GladixQueue", "QUEUEDRAG clearView")
                isDragging = false
                dragStartTop = null
                // ⚠⚠ NO RESUBMIT HERE, AND THAT IS A FIX RATHER THAN AN OMISSION. A catch-up
                // `submit()` used to run here. It recomputed from viewModel.queue - which the 1102 capture
                // proved is STILL STALE at this moment: the drag's own moveMediaItem calls emit through
                // emitFullQueue's 50ms debounce and land AFTER clearView (every `scrolled` line read
                // dragging=false). So the catch-up submitted the pre-drag order and snapped the list
                // backwards, then the real emission snapped it forward again.
                // The adapter already holds the right order from onMove's local reorder, and the debounced
                // emission delivers the authoritative one a moment later. Nothing to do here but disarm.
                // ⚠️ DIVERGENCE WINDOW, NAMED RATHER THAN LEFT TO BE FOUND: if the player applies
                // NO move - ShufflePlayer.moveMediaItem has its own out-of-range no-op - then no timeline
                // change fires, no emission arrives, and the local order stands diverged from the player's.
                // It self-heals at the NEXT queue event (a track transition, a radio append), not
                // immediately. Bounded and visible-as-a-snap rather than silent.
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val pos = viewHolder.bindingAdapterPosition
                viewModel.removeQueueItem(pos)
            }

            override fun getMovementFlags(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ): Int {
                // Seam 2/G2: the current track is pinned — no drag. Current index read from the
                // viewModel (same source as submit()), NOT queueAdapter, to avoid the by-lazy cycle.
                val pos = viewHolder.bindingAdapterPosition
                val currentPos = viewModel.playerState.current.value?.let { c ->
                    viewModel.queue.indexOfFirst { it.mediaId == c.mediaItem.mediaId }
                } ?: -1
                val isCurrent = pos != RecyclerView.NO_POSITION && pos == currentPos
                val dragFlags = if (isCurrent) 0 else ItemTouchHelper.UP or ItemTouchHelper.DOWN
                return makeMovementFlags(dragFlags, ItemTouchHelper.START)
            }
        })
    }

    private fun submitOrDefer() {
        android.util.Log.d("GladixQueue", "QUEUEDRAG submitOrDefer dragging=$isDragging")
        // Dropped, not deferred - see the note at clearView. The adapter's drag-local order is already
        // correct, and re-submitting after the drag would only replay a stale snapshot.
        if (isDragging) return
        submit()
    }

    // Hoisted out of onViewCreated so clearView can reach it. The explicit types on queueAdapter and
    // touchHelper above are what make that safe: the by-lazy pair reference each other (the adapter's
    // listener calls touchHelper.startDrag), and routing a third reference through an INFERRED type is
    // what produces the cycle the onMove/getMovementFlags notes warn about. Declared types break it at the
    // root; those two notes stay accurate about why they read the index from the viewModel.
    private fun submit() {
        val current = viewModel.playerState.current.value
        val fullCurrentIndex = current?.let { c ->
            viewModel.queue.indexOfFirst { it.mediaId == c.mediaItem.mediaId }
        } ?: -1
        val it = viewModel.queue.mapIndexed { index, mediaItem ->
            if (fullCurrentIndex == index) current!!.isPlaying to current.mediaItem
            else null to mediaItem
        }
        queueAdapter.submitList(it) {
            if (fullCurrentIndex < 0) return@submitList
            android.util.Log.d(
                "GladixQueue", "QUEUEDRAG scrollToPosition idx=$fullCurrentIndex dragging=$isDragging"
            )
            binding?.root?.scrollToPosition(fullCurrentIndex)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        setupTransition(view, false, axis = MaterialSharedAxis.Y)
        val recyclerView = binding!!.root
        @Suppress("ClickableViewAccessibility")
        // TEMPORARY - REMOVE WITH THE QUEUEDRAG SET. Pure observer: always returns false, so the touch
        // continues to the item-touch dispatch untouched. Records on EVERY down and move.
        recyclerView.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { fingerDownY = e.y; fingerY = e.y }
                android.view.MotionEvent.ACTION_MOVE -> fingerY = e.y
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    fingerDownY = Float.NaN; fingerY = Float.NaN
                }
            }
            false
        }
        recyclerView.adapter = queueAdapter
        // ⚠️ NO FAST SCROLLER HERE, AND THAT IS THE WHOLE STORY - NOT A BROKEN ONE, A
        // NEVER-WIRED ONE. Closing a parked "QueueFragment fast scroller" item 2026-09-12: this screen has
        // never called FastScrollerHelper.applyTo, so the two fast-scroller explanations for the queue-drag
        // jump are STRUCTURALLY dead rather than merely unlikely.
        //   - "the scroller reacts to ItemTouchHelper's edge auto-scroll" needs a scroller to react.
        //   - "attach order is wrong" needs two things to order; the load-bearing ordering recorded at
        //     FeedFragment and MediaDetailsFragment (touch helper attached BEFORE the scroller) has no
        //     counterpart here because there is only one attach.
        // Do not "restore" an ordering that never existed, and do not add a scroller here to match the
        // other screens without a reason of its own - the queue is bounded by what is upcoming, which is
        // not the long-list case the scroller exists for.
        touchHelper.attachToRecyclerView(recyclerView)
        val manager = recyclerView.layoutManager as LinearLayoutManager
        val screenHeight = view.resources.displayMetrics.heightPixels / 3

        // BOTH observers defer: a track transition during a drag would scroll just as disruptively as a
        // queue mutation, and playerState.current fires on every play/pause too.
        // ⚠⚠ TEMPORARY DISCRIMINATOR - REMOVE THE WHOLE QUEUEDRAG SET ONCE ONE CAPTURE READS.
        // THE TWO MECHANISMS THIS SEPARATES, and they predict OPPOSITE log shapes:
        //  (A) A RESUBMIT SCROLLS THE LIST. Then exactly ONE `QUEUEDRAG scrollToPosition idx=<n>` line
        //      appears at the moment of the jump, with `dragging=false` next to it, followed by ONE large
        //      `scrolled dy=`. That is the mechanism the isDragging guard was built for, and a
        //      `dragging=false` there means the guard is INERT rather than wrong.
        //  (B) ItemTouchHelper's OWN AUTO-SCROLL. Then NO scrollToPosition line appears at all, and
        //      instead a STREAM of `scrolled dy=-N` lines runs for seconds with |dy| STARTING SMALL AND
        //      GROWING. That growth is the signature: Callback.interpolateOutOfBoundsScroll
        //      (ItemTouchHelper.java:2175-2190, recyclerview 1.4.0) multiplies speed by
        //      msSinceStartScroll / DRAG_SCROLL_ACCELERATION_LIMIT_TIME_MS (:1429, = 2000), i.e. a linear
        //      ramp from zero to full over the first two seconds. A 3-4 SECOND CREEP ENDING AT THE TOP IS
        //      THE SHAPE OF THAT RAMP, and nothing in our own code has a 3-4s cadence - the timers in this
        //      path are 50ms (emitFullQueue), 300ms (ResumptionUtils, PlayerEventListener) and a bare
        //      `post` (ShufflePlayer reconstitution). THE ABSENCE OF A MATCHING TIMER IS ITSELF EVIDENCE.
        //  (C) `QUEUEDRAG selected` never appears -> onSelectedChanged is not firing on the live callback
        //      and the guard never armed. Rules on question 3 outright.
        // Scroll logging is gated on isDragging so ordinary browsing does not flood the capture.
        binding!!.root.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                // ⚠⚠ UNGATED ON PURPOSE - THE FIRST VERSION OF THIS LINE GATED ON isDragging
                // AND THAT MADE IT BLIND TO THE LEADING HYPOTHESIS. If the flag never arms, a gated log
                // prints nothing, and "nothing scrolled" is then indistinguishable from "scrolled while
                // the guard was inert" - the two outcomes the capture exists to separate. `dragging=` on
                // every line carries the flag state instead.
                android.util.Log.d("GladixQueue", "QUEUEDRAG scrolled dy=$dy dragging=$isDragging")
            }
        })
        observe(viewModel.playerState.current) { submitOrDefer() }
        observe(viewModel.queueFlow) { submitOrDefer() }

        val currentForScroll = viewModel.playerState.current.value ?: return
        val index = viewModel.queue.indexOfFirst { it.mediaId == currentForScroll.mediaItem.mediaId }
        if (index < 0) return
        manager.scrollToPositionWithOffset(index + 1, screenHeight)
    }
}