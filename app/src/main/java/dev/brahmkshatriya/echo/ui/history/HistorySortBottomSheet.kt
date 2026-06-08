package dev.brahmkshatriya.echo.ui.history

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

class HistorySortBottomSheet : BottomSheetDialogFragment() {

    private val historyViewModel by lazy {
        val vm by requireParentFragment().viewModel<HistoryViewModel>()
        vm
    }

    private var binding by autoCleared<DialogSortBinding>()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        binding = DialogSortBinding.inflate(inflater, container, false)
        return binding.root
    }

    private var selectedSort: HistoryViewModel.SortOption? = null
    private var selectedExtFilter: String? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.progressIndicator.isVisible = false

        val sortOptions = HistoryViewModel.SortOption.entries
        observe(historyViewModel.sortState) { state ->
            selectedSort = state.sortOption
            binding.sortChipGroup.run {
                removeAllViews()
                sortOptions.forEachIndexed { index, option ->
                    val chip = Chip(context)
                    chip.id = index
                    chip.text = getString(option.title)
                    chip.ellipsize = TextUtils.TruncateAt.MIDDLE
                    chip.isCheckable = true
                    addView(chip)
                    if (option == state.sortOption) check(chip.id)
                }
                setOnCheckedStateChangeListener { _, checkedIds ->
                    selectedSort = sortOptions.getOrNull(checkedIds.firstOrNull() ?: -1)
                }
            }
            binding.reversedSwitch.isChecked = state.reversed
            binding.saveCheckbox.isChecked = state.save
        }

        selectedExtFilter = historyViewModel.extensionFilter.value
        observe(historyViewModel.extensionLoader.music) { extensions ->
            binding.filter.isVisible = extensions.isNotEmpty()
            binding.filterGroup.isVisible = extensions.isNotEmpty()
            binding.filterGroup.run {
                removeAllViews()
                isSingleSelection = true
                extensions.forEachIndexed { index, ext ->
                    val chip = Chip(context)
                    chip.id = index
                    chip.text = ext.metadata.name
                    chip.isCheckable = true
                    addView(chip)
                    if (ext.id == selectedExtFilter) check(chip.id)
                }
                setOnCheckedStateChangeListener { _, checkedIds ->
                    val selectedId = checkedIds.firstOrNull()
                    selectedExtFilter =
                        if (selectedId != null) extensions.getOrNull(selectedId)?.id else null
                }
            }
        }

        binding.reversedContainer.setOnClickListener {
            binding.reversedSwitch.isChecked = !binding.reversedSwitch.isChecked
        }
        binding.saveContainer.setOnClickListener {
            binding.saveCheckbox.isChecked = !binding.saveCheckbox.isChecked
        }
        binding.apply.setOnClickListener {
            historyViewModel.applySortState(
                HistoryViewModel.SortState(
                    sortOption = selectedSort ?: HistoryViewModel.SortOption.ByDate,
                    reversed = binding.reversedSwitch.isChecked,
                    save = binding.saveCheckbox.isChecked,
                )
            )
            historyViewModel.extensionFilter.value = selectedExtFilter
            dismiss()
        }
        binding.topAppBar.setNavigationOnClickListener { dismiss() }
        binding.topAppBar.setOnMenuItemClickListener {
            historyViewModel.applySortState(HistoryViewModel.SortState())
            historyViewModel.extensionFilter.value = null
            dismiss()
            true
        }
    }
}
