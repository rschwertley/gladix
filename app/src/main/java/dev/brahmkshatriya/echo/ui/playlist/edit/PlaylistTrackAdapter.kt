package dev.brahmkshatriya.echo.ui.playlist.edit

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.core.view.updatePaddingRelative
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.databinding.ItemPlaylistTrackBinding
import dev.brahmkshatriya.echo.ui.player.more.upnext.QueueAdapter.Companion.bind
import dev.brahmkshatriya.echo.utils.ui.UiUtils.dpToPx
import dev.brahmkshatriya.echo.utils.ui.scrolling.ScrollAnimListAdapter
import dev.brahmkshatriya.echo.utils.ui.scrolling.ScrollAnimViewHolder

class PlaylistTrackAdapter(
    private val listener: Listener,
) : ScrollAnimListAdapter<Track, PlaylistTrackAdapter.ViewHolder>(DiffCallback) {
    // Set for the whole drag gesture. EditPlaylistFragment's currentTracks observer DROPS submits
    // while it is true - see the note there for why dropping rather than deferring.
    // ⚠️ pendingList WAS REMOVED 2026-10-07. It stored the dropped list and replayed it in
    // clearView, which turned out to be the defect rather than the safeguard: with onMove now
    // reordering locally, the ADAPTER holds the authoritative order during a drag and currentTracks is
    // the side catching up, so replaying currentTracks at drag end overwrote the correct order with the
    // accumulated one. QueueFragment.submitSuppressed and ManageExtensionsFragment both cite this
    // field by name; both notes were updated in the same change.
    var isDragging = false

    object DiffCallback : DiffUtil.ItemCallback<Track>() {
        // ⚠⚠ THE DRAG DEPENDS ON areItemsTheSame COMPARING A STABLE KEY, AND IT IS SAFE ONLY
        // BECAUSE THIS LINE USES id. onMove reorders this adapter's list in place and submits it, and
        // DiffUtil can resolve that to a MOVE only if items are identified by a key. Compare positions
        // or whole objects instead and the diff becomes remove+insert, the dragged row is recycled, and
        // ItemTouchHelper.onChildViewDetachedFromWindow (ItemTouchHelper.java:902-909, recyclerview
        // 1.4.0) runs select(null, ACTION_STATE_IDLE) - the drag ends mid-gesture with nothing logged.
        // Same dependency, same reasoning, same library lines as QueueAdapter.DiffCallback; read that
        // note too before touching either.
        override fun areItemsTheSame(oldItem: Track, newItem: Track) = oldItem.id == newItem.id
        // Moot for the drag, which is the stronger point: onMove reorders the SAME INSTANCES rather
        // than rebuilding them, so every call here compares a value to itself. One move, zero rebinds.
        // ⚠️ UNVERIFIED, AND MORE LIKELY HERE THAN IN THE QUEUE: a playlist may legitimately
        // contain the same track twice, giving DiffUtil two genuinely interchangeable rows. For a
        // single-element move the diff should still resolve to one move, since the rest of the list is
        // unchanged - but that is reasoning, not a measurement. If a drag misbehaves specifically on a
        // playlist with duplicate tracks, start here.
        override fun areContentsTheSame(oldItem: Track, newItem: Track) = oldItem == newItem
    }

    interface Listener {
        fun onTrackClicked(viewHolder: ViewHolder)
        fun onTrackClosedClicked(viewHolder: ViewHolder)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(parent, listener)
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        val track = getItem(position)
        holder.bind(track)
    }

    class ViewHolder(
        parent: ViewGroup,
        listener: Listener,
        val binding: ItemPlaylistTrackBinding = ItemPlaylistTrackBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
    ) : ScrollAnimViewHolder(binding.root) {
        var track: Track? = null

        init {
            binding.playlistItemClose.setOnClickListener {
                listener.onTrackClosedClicked(this)
            }
            binding.playlistItemDrag.setOnTouchListener { v, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    v.performClick()
                    listener.onTrackClicked(this)
                }
                true
            }
            val color = MaterialColors.getColor(binding.root, R.attr.echoBackground)
            binding.root.backgroundTintList = ColorStateList.valueOf(color)
            binding.playlistItemNowPlaying.isVisible = false
            binding.playlistItem.updatePaddingRelative(start = 24.dpToPx(binding.root.context))
        }

        fun bind(track: Track) {
            this.track = track
            binding.bind(track)
        }
    }

    companion object {
        fun getTouchHelperAndListener(
            viewModel: EditPlaylistViewModel,
            adapterProvider: () -> PlaylistTrackAdapter
        ): Pair<Listener, ItemTouchHelper> {
            val callback = object : ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP or ItemTouchHelper.DOWN, ItemTouchHelper.START
            ) {
                override fun onSelectedChanged(
                    viewHolder: RecyclerView.ViewHolder?, actionState: Int
                ) {
                    super.onSelectedChanged(viewHolder, actionState)
                    if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                        adapterProvider().isDragging = true
                    }
                }

                override fun clearView(
                    recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder
                ) {
                    super.clearView(recyclerView, viewHolder)
                    // ⚠⚠ NO RESUBMIT HERE, AND THAT IS THE FIX RATHER THAN AN OMISSION. A
                    // pendingList replay used to run at this point. It restored the list that
                    // currentTracks had accumulated DURING the gesture - which, now that onMove reorders
                    // locally, is the stale side: the adapter already holds the order the user dragged
                    // to, and currentTracks converges on it as the Move actions land. Replaying it
                    // snapped the rows to a different arrangement the moment the finger lifted, which is
                    // the "tracks end up in random positions" half of GitHub #3.
                    // Same decision and same reasoning as QueueFragment.clearView - read that note
                    // before adding a catch-up submit here.
                    adapterProvider().isDragging = false
                }

                override fun getMovementFlags(
                    recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder
                ): Int {
                    if (viewHolder !is ViewHolder) return 0
                    return makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, ItemTouchHelper.START)
                }
                override fun onMove(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                    target: RecyclerView.ViewHolder
                ): Boolean {
                    if (viewHolder !is ViewHolder) return false
                    if (target !is ViewHolder) return false

                    val fromPos = viewHolder.bindingAdapterPosition
                    val toPos = target.bindingAdapterPosition
                    // Jun 19's guard - KEEP IT. NO_POSITION reaches edit() as an index, and
                    // EditPlaylistViewModel.edit does removeAt/add on a MutableList, so a -1 is an
                    // IndexOutOfBounds inside that function's runCatching: a toast on throwFlow instead
                    // of an edit. It now also guards the local reorder immediately below.
                    if (fromPos == RecyclerView.NO_POSITION || toPos == RecyclerView.NO_POSITION) return false

                    // ⚠⚠ THE LOCAL REORDER - THIS IS THE FIX FOR GitHub #3, AND IT MUST COME
                    // BEFORE edit(). ItemTouchHelper's contract is that a true return means "I moved the
                    // items in my data set". This returned true while moving nothing: it only called
                    // edit(), which mutates EditPlaylistViewModel.currentTracks, whose observer was
                    // DEFERRED for the whole gesture - so the adapter's list never changed,
                    // bindingAdapterPosition never advanced, and ItemTouchHelper re-asked for the SAME
                    // from->to every frame. Measured on the queue, which had this exact shape:
                    // onMove 22->21 ten times in one gesture. Each re-ask applied ANOTHER Move to
                    // currentTracks, so one gesture produced N moves of one row and the list walked far
                    // past the finger.
                    // ⚠️ IT ALSO FIXES BOTH SCROLL SYMPTOMS, which read as separate bugs and are
                    // not. moveIfNecessary (ItemTouchHelper.java:890-893) calls onMoved on a true
                    // return, which reaches LinearLayoutManager.prepareForDrop and SCROLLS the list -
                    // while mSelectedStartY, captured once at :687 and never re-captured, stays frozen.
                    // scrollIfNecessary (:774-785) computes BOTH thresholds from
                    // curY = mSelectedStartY + mDy, so after the first scroll the capture lags the row's
                    // real position: up-drag works to a point, down-drag stops crossing bottomDiff at
                    // all, and once the row leaves the viewport the detach at :902-909 terminates the
                    // gesture outright - the "page seems to refresh". With a real reorder,
                    // prepareForDrop works against live geometry instead.
                    // ⚠️ ADAPTER-LOCAL INDICES, WHICH IS NOT OBVIOUS ON THIS SCREEN: the
                    // RecyclerView runs a ConcatAdapter(headerAdapter, tabAdapter, this), and
                    // bindingAdapterPosition is relative to THIS adapter - so the same index is correct
                    // for both currentList and edit(). Do not switch either to absoluteAdapterPosition.
                    // QueueFragment is a single adapter, so this is the one structural difference
                    // between the two screens, and it happens to be benign.
                    val adapter = adapterProvider()
                    adapter.submitList(
                        adapter.currentList.toMutableList().apply { add(toPos, removeAt(fromPos)) }
                    )
                    viewModel.edit(EditPlaylistViewModel.Action.Move(fromPos, toPos))
                    return true
                }

                override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                    val pos = viewHolder.bindingAdapterPosition
                    if (pos == RecyclerView.NO_POSITION) return
                    viewModel.edit(EditPlaylistViewModel.Action.Remove(listOf(pos)))
                }
            }
            val itemTouchHelper = ItemTouchHelper(callback)
            val listener = object : Listener {
                override fun onTrackClicked(viewHolder: ViewHolder) {
                    itemTouchHelper.startDrag(viewHolder)
                }

                override fun onTrackClosedClicked(viewHolder: ViewHolder) {
                    viewModel.edit(EditPlaylistViewModel.Action.Remove(listOf(viewHolder.bindingAdapterPosition)))
                }
            }
            return listener to itemTouchHelper
        }
    }
}