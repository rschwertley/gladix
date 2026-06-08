package dev.brahmkshatriya.echo.ui.history

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import dev.brahmkshatriya.echo.databinding.ItemHistoryTitleBinding
import dev.brahmkshatriya.echo.ui.common.GridAdapter
import dev.brahmkshatriya.echo.utils.ui.scrolling.ScrollAnimRecyclerAdapter
import dev.brahmkshatriya.echo.utils.ui.scrolling.ScrollAnimViewHolder

class HistoryTitleAdapter(
    private val onClearClick: () -> Unit,
    private val onSortClick: () -> Unit,
    private val onSearchChanged: (String?) -> Unit,
) : ScrollAnimRecyclerAdapter<HistoryTitleAdapter.ViewHolder>(), GridAdapter {
    override val adapter = this
    override fun getSpanSize(position: Int, width: Int, count: Int) = count
    override fun getItemCount() = 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val holder = ViewHolder(parent)
        holder.binding.clearButton.setOnClickListener { onClearClick() }
        holder.binding.sortButton.setOnClickListener { onSortClick() }
        holder.binding.searchButton.setOnClickListener {
            val isVisible = holder.binding.searchLayout.isVisible
            holder.binding.searchLayout.isVisible = !isVisible
            if (isVisible) {
                holder.binding.searchInput.text?.clear()
                onSearchChanged(null)
            } else {
                holder.binding.searchInput.requestFocus()
            }
        }
        holder.binding.searchInput.addTextChangedListener { text ->
            val query = text?.toString()?.takeIf { it.isNotBlank() }
            onSearchChanged(query)
        }
        return holder
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        super.onBindViewHolder(holder, position)
    }

    class ViewHolder(
        parent: ViewGroup,
        val binding: ItemHistoryTitleBinding = ItemHistoryTitleBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
    ) : ScrollAnimViewHolder(binding.root)
}
