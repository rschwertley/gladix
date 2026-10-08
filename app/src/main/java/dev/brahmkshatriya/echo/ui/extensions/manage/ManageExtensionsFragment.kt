package dev.brahmkshatriya.echo.ui.extensions.manage

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import dev.brahmkshatriya.echo.common.Extension
import dev.brahmkshatriya.echo.common.MusicExtension
import dev.brahmkshatriya.echo.common.models.ExtensionType
import dev.brahmkshatriya.echo.databinding.FragmentManageExtensionsBinding
import dev.brahmkshatriya.echo.ui.common.FragmentUtils.openFragment
import dev.brahmkshatriya.echo.ui.common.UiViewModel.Companion.applyBackPressCallback
import dev.brahmkshatriya.echo.ui.common.UiViewModel.Companion.applyInsets
import dev.brahmkshatriya.echo.ui.common.UiViewModel.Companion.applyInsetsWithChild
import dev.brahmkshatriya.echo.ui.extensions.ExtensionInfoFragment
import dev.brahmkshatriya.echo.ui.extensions.ExtensionInfoPreference.Companion.getType
import dev.brahmkshatriya.echo.ui.extensions.ExtensionsViewModel
import dev.brahmkshatriya.echo.ui.extensions.add.ExtensionsAddBottomSheet
import dev.brahmkshatriya.echo.utils.ContextUtils.observe
import dev.brahmkshatriya.echo.utils.ui.AnimationUtils.setupTransition
import dev.brahmkshatriya.echo.utils.ui.AutoClearedValue.Companion.autoCleared
import dev.brahmkshatriya.echo.utils.ui.FastScrollerHelper
import dev.brahmkshatriya.echo.utils.ui.FastScrollerHelper.applyInsets
import dev.brahmkshatriya.echo.utils.ui.UiUtils.configureAppBar
import org.koin.androidx.viewmodel.ext.android.activityViewModel

class ManageExtensionsFragment : Fragment() {
    private var binding by autoCleared<FragmentManageExtensionsBinding>()
    private val viewModel by activityViewModel<ExtensionsViewModel>()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        binding = FragmentManageExtensionsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        setupTransition(view)
        // Hoisted above the inset block so the handle exists when it first runs; kept and re-padded there
        // rather than left on applyTo's flat 8dp.
        val scroller = FastScrollerHelper.applyTo(binding.recyclerView)
        applyInsetsWithChild(binding.appBarLayout, binding.recyclerView, 104) {
            binding.fabContainer.applyInsets(it)
            scroller.applyInsets(binding.recyclerView.context, it)
        }
        applyBackPressCallback()
        binding.appBarLayout.configureAppBar { offset ->
            binding.appBarOutline.alpha = offset
            binding.appBarOutline.isVisible = offset > 0
            binding.toolBar.alpha = 1 - offset
        }
        binding.toolBar.setNavigationOnClickListener {
            parentFragmentManager.popBackStack()
        }
        binding.toolBar.setOnMenuItemClickListener {
            viewModel.update(requireActivity(), true)
            true
        }

        binding.fabAddExtensions.setOnClickListener {
            ExtensionsAddBottomSheet().show(parentFragmentManager, null)
        }

        val tabs = ExtensionType.entries.map {
            binding.tabLayout.newTab().apply {
                setText(getType(it))
            }
        }
        binding.tabLayout.run {
            tabs.forEach { addTab(it) }
        }

        // ⚠️ PARKED 2026-09-12, SAME FAMILY AS THE QUEUE-DRAG FIX, DEVICE-UNVERIFIED HERE.
        // This screen has the resubmit-during-drag half WITHOUT the scroll half: onMove calls
        // viewModel.moveExtensionItem, which feeds manageExtListFlow, whose observer below calls
        // extensionAdapter.submit -> submitData(PagingData.from(list)) on EVERY emission. There is no
        // scrollToPosition anywhere here, so there is no visible jump - which is exactly why it would go
        // unnoticed if it is real.
        // THE EXPOSURE THAT REMAINS: recyclerview 1.4.0's ItemTouchHelper.java:902-916 terminates a drag
        // outright if the dragged row is detached - `if (mSelected != null && holder == mSelected)
        // select(null, ACTION_STATE_IDLE);`. Whether Paging's differ actually detaches it for a pure
        // REORDER of the same items is a device question, not a source one, and it is the reason this is
        // parked rather than fixed: QueueFragment had a reproduced symptom, this has a mechanism and no
        // report. Do not fix it blind - drag an extension and see whether the drag survives first.
        // THE FIX, IF IT IS REAL, IS WRITTEN ON BOTH OTHER DRAG SCREENS, AND BOTH NOW AGREE ON THE
        // SHAPE: reorder the adapter locally in onMove, and DROP external submits while dragging rather
        // than deferring them. See QueueFragment.onMove / submitOrDefer and
        // PlaylistTrackAdapter.onMove / EditPlaylistFragment's currentTracks observer.
        // ⚠️ [CORRECTED 2026-10-07] THIS USED TO SAY "Prefer pendingList's shape here, because
        // this observer CARRIES the list". pendingList NO LONGER EXISTS - it was removed from
        // PlaylistTrackAdapter on 2026-10-07 because the deferred replay was itself the defect in
        // GitHub #3, restoring an accumulated order over the correct one.
        // ⚠⚠ BUT THE CARRIES-THE-LIST OBSERVATION WAS RIGHT AND STILL MATTERS HERE, SO DO NOT
        // COPY THE DROP BLINDLY. The editor can drop safely because currentTracks is a
        // MutableStateFlow - the next emission after the drag carries the latest value, so an emission
        // is late, never lost. manageExtListFlow (ExtensionsViewModel:79) is a COLD combine of
        // extensionLoader.all and lastSelectedManageExt with no stateIn, so a dropped emission is gone
        // until an upstream happens to emit again. On this screen the local reorder is the part that
        // transfers; the submit side needs either a stateIn upstream or a re-read at drag end.
        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val fromPos = viewHolder.bindingAdapterPosition
                val toPos = target.bindingAdapterPosition
                viewModel.moveExtensionItem(toPos, fromPos)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun getMovementFlags(
                recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder
            ) = makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)

        }

        val touchHelper = ItemTouchHelper(callback)
        val extensionAdapter = ExtensionAdapter(object : ExtensionAdapter.Listener {
            override fun onClick(extension: Extension<*>, view: View) {
                openFragment<ExtensionInfoFragment>(
                    view, ExtensionInfoFragment.getBundle(extension)
                )
            }

            override fun onDragHandleTouched(viewHolder: ExtensionAdapter.ViewHolder) {
                touchHelper.startDrag(viewHolder)
            }

            override fun onOpenClick(extension: Extension<*>) {
                viewModel.onExtensionSelected(extension as MusicExtension)
                parentFragmentManager.popBackStack()
                parentFragmentManager.popBackStack()
            }
        })

        observe(viewModel.manageExtListFlow) { list ->
            extensionAdapter.submit(list, viewModel.lastSelectedManageExt.value, viewModel.app.settings)
        }

        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            fun select(tab: TabLayout.Tab) {
                viewModel.lastSelectedManageExt.value = tab.position
            }

            override fun onTabSelected(tab: TabLayout.Tab) = select(tab)
            override fun onTabReselected(tab: TabLayout.Tab) = select(tab)
            override fun onTabUnselected(tab: TabLayout.Tab) {}
        })

        binding.tabLayout.selectTab(tabs[viewModel.lastSelectedManageExt.value])
        binding.recyclerView.adapter = extensionAdapter.withEmptyAdapter()
        touchHelper.attachToRecyclerView(binding.recyclerView)
    }
}