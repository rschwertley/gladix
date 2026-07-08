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

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentPlayerQueueBinding.inflate(inflater, container, false)
        return binding!!.root
    }

    private val queueAdapter by lazy {
        QueueAdapter(object : QueueAdapter.Listener() {
            override fun onDragHandleTouched(viewHolder: RecyclerView.ViewHolder) {
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

    private val touchHelper by lazy {
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
                val currentPos = queueAdapter.currentList.indexOfFirst { it.first != null }
                if (currentPos != -1 && toPos <= currentPos) return false
                viewModel.moveQueueItems(fromPos, toPos)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val pos = viewHolder.bindingAdapterPosition
                viewModel.removeQueueItem(pos)
            }

            override fun getMovementFlags(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ): Int {
                // Seam 2/G2: the current track (row carrying the non-null marker) is pinned — no drag.
                val pos = viewHolder.bindingAdapterPosition
                val isCurrent = pos != RecyclerView.NO_POSITION &&
                    queueAdapter.currentList.getOrNull(pos)?.first != null
                val dragFlags = if (isCurrent) 0 else ItemTouchHelper.UP or ItemTouchHelper.DOWN
                return makeMovementFlags(dragFlags, ItemTouchHelper.START)
            }
        })
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        setupTransition(view, false, axis = MaterialSharedAxis.Y)
        val recyclerView = binding!!.root
        recyclerView.adapter = queueAdapter
        touchHelper.attachToRecyclerView(recyclerView)
        val manager = recyclerView.layoutManager as LinearLayoutManager
        val screenHeight = view.resources.displayMetrics.heightPixels / 3

        fun submit() {
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
                binding?.root?.scrollToPosition(fullCurrentIndex)
            }
        }

        observe(viewModel.playerState.current) { submit() }
        observe(viewModel.queueFlow) { submit() }

        val currentForScroll = viewModel.playerState.current.value ?: return
        val index = viewModel.queue.indexOfFirst { it.mediaId == currentForScroll.mediaItem.mediaId }
        if (index < 0) return
        manager.scrollToPositionWithOffset(index + 1, screenHeight)
    }
}