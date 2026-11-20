package dev.brahmkshatriya.echo.ui.feed

import android.content.DialogInterface
import android.os.Bundle
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.Chip
import dev.brahmkshatriya.echo.databinding.DialogSortBinding
import dev.brahmkshatriya.echo.utils.ContextUtils.observe
import dev.brahmkshatriya.echo.utils.ui.AutoClearedValue.Companion.autoCleared
import org.koin.androidx.viewmodel.ext.android.viewModel

class FeedSortBottomSheet : BottomSheetDialogFragment() {

    private val feedData by lazy {
        val vm by requireParentFragment().viewModel<FeedViewModel>()
        val id = requireArguments().getString("id")!!
        vm.feedDataMap[id]!!
    }
    private val loadedShelves by lazy { feedData.loadedShelves }
    private val sortState by lazy { feedData.feedSortState }

    private var binding by autoCleared<DialogSortBinding>()
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        binding = DialogSortBinding.inflate(inflater, container, false)
        return binding.root
    }

    private var selectedFeedSort: FeedSort? = null
    private fun applySorts() {
        val list = loadedShelves.value ?: listOf()
        val state = sortState.value
        selectedFeedSort = state?.feedSort
        val available = getSorts(list)
        val checked = available.indexOf(state?.feedSort)
        binding.sortChipGroup.run {
            removeAllViews()
            available.forEachIndexed { index, t ->
                val chip = Chip(context)
                chip.id = index
                chip.text = getString(t.title)
                chip.ellipsize = TextUtils.TruncateAt.MIDDLE
                chip.isCheckable = true
                addView(chip)
                if (index == checked) check(chip.id)
            }
            setOnCheckedStateChangeListener { _, checkedIds ->
                selectedFeedSort = available.getOrNull(checkedIds.firstOrNull() ?: -1)
            }
        }
        binding.filter.isVisible = false
        binding.filterGroup.isVisible = false
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        observe(loadedShelves) {
            binding.progressIndicator.isVisible = it == null
            applySorts()
        }
        observe(sortState) {
            applySorts()
            binding.saveCheckbox.isChecked = it?.save ?: false
            binding.reversedSwitch.isChecked = it?.reversed ?: false
        }
        binding.saveContainer.setOnClickListener {
            binding.saveCheckbox.isChecked = !binding.saveCheckbox.isChecked
        }
        binding.reversedContainer.setOnClickListener {
            binding.reversedSwitch.isChecked = !binding.reversedSwitch.isChecked
        }
        binding.apply.setOnClickListener {
            sortState.value = FeedSort.State(
                feedSort = selectedFeedSort,
                reversed = binding.reversedSwitch.isChecked,
                save = binding.saveCheckbox.isChecked
            )
            // The ONLY moment a sort is deliberately saved. Persisting from here rather than from the feed
            // render path is the 2026-09-06 fix — see FeedData.persistSortState for why. Note this is also
            // strictly MORE reliable than the old behaviour: applying with the checkbox ticked and then
            // navigating away used to lose the save if no further render pass happened to run.
            // An UNTICKED checkbox reaches the same call and REMOVES any existing entry; it does not
            // simply skip the write, or unticking would leave the previous sort armed on disk.
            // [2026-11-19] Except on a folder page (FeedData.sortScope), where unticked sorts this page only; see persistSortState.
            feedData.persistSortState(unsavedApply = !binding.saveCheckbox.isChecked)
            dismiss()
        }
        binding.topAppBar.setNavigationOnClickListener {
            dismiss()
        }
        binding.topAppBar.setOnMenuItemClickListener {
            // RESET. FeedSort.State() is save = false, which persistSortState treats as DELETE — the whole
            // point. Before 2026-09-06 this cleared only the in-memory state: the `if (sortState.save)`
            // write in the render path was skipped, the old entry stayed on disk, and the next feed load
            // restored it. That is why a saved sort looked impossible to clear from the sheet.
            sortState.value = FeedSort.State()
            feedData.persistSortState()
            dismiss()
            true
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        parentFragmentManager.setFragmentResult("shelfSort", Bundle().apply {
            putBoolean("changed", true)
        })
    }

    companion object {
        fun newInstance(id: String) = FeedSortBottomSheet().apply {
            arguments = Bundle().apply { putString("id", id) }
        }
    }
}