package dev.brahmkshatriya.echo.ui.main.search

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.doOnLayout
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.utils.ui.UiUtils.setTvRowButtons
import com.google.android.material.search.SearchView
import dev.brahmkshatriya.echo.databinding.ItemSearchBarBinding
import dev.brahmkshatriya.echo.ui.common.GridAdapter
import dev.brahmkshatriya.echo.utils.ui.scrolling.ScrollAnimRecyclerAdapter
import dev.brahmkshatriya.echo.utils.ui.scrolling.ScrollAnimViewHolder

class SearchBarAdapter(
    val viewModel: SearchViewModel,
    val searchView: SearchView,
    val onMicClick: () -> Unit,
) : ScrollAnimRecyclerAdapter<SearchBarAdapter.ViewHolder>(), GridAdapter {
    override val adapter = this
    override fun getSpanSize(position: Int, width: Int, count: Int) = count
    override fun getItemCount() = 1
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(parent, viewModel, searchView, onMicClick)

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        holder.bind()
    }

    class ViewHolder(
        parent: ViewGroup,
        val viewModel: SearchViewModel,
        val searchView: SearchView,
        val onMicClick: () -> Unit,
        private val binding: ItemSearchBarBinding = ItemSearchBarBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
    ) : ScrollAnimViewHolder(binding.root) {
        fun bind() {
            searchView.setupWithSearchBar(binding.root)
            binding.root.setText(viewModel.queryFlow.value.takeIf { it.isNotBlank() })
            binding.root.setOnMenuItemClickListener { item ->
                if (item.itemId == R.id.menu_voice_search) {
                    onMicClick()
                    true
                } else false
            }
            // TV (2026-11-02): Right from the bar to the mic, Left from the mic BACK TO THE BAR. Left used to reach the
            // rail: TvAwareRecyclerView's in-item Left search starts at the row's root, which IS this full-width bar,
            // and Android never counts a view as left of a button it contains. The mic view carries the menu item's id
            // (appcompat ActionMenuItemView.initialize sets it), and exists once the bar is laid out. Phone: no-op.
            binding.root.doOnLayout { bar ->
                bar.findViewById<View>(R.id.menu_voice_search)?.let { bar.setTvRowButtons(listOf(it)) }
            }
        }
    }
}