package dev.brahmkshatriya.echo.ui.feed

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.paging.LoadState
import dev.brahmkshatriya.echo.databinding.ItemShelfEmptyBinding
import dev.brahmkshatriya.echo.ui.common.GridAdapter
import dev.brahmkshatriya.echo.utils.ui.scrolling.ScrollAnimLoadStateAdapter
import dev.brahmkshatriya.echo.utils.ui.scrolling.ScrollAnimViewHolder

class EmptyAdapter : ScrollAnimLoadStateAdapter<EmptyAdapter.ViewHolder>(), GridAdapter {
    class ViewHolder(val binding: ItemShelfEmptyBinding) : ScrollAnimViewHolder(binding.root) {
        // Use View.postDelayed instead of a view-tree lifecycle scope: bind() runs during
        // onBindViewHolder, before the view is attached, so findViewTreeLifecycleOwner() is
        // often null and the launch silently no-ops — leaving the spinner up forever.
        private val showEmptyRunnable = Runnable {
            if (binding.loadingIndicator.isVisible) {
                binding.loadingIndicator.isVisible = false
                binding.emptyLayout.isVisible = true
            }
        }

        fun bind(loadState: LoadState) {
            itemView.removeCallbacks(showEmptyRunnable)
            if (loadState is LoadState.Loading) {
                binding.emptyLayout.isVisible = false
                binding.loadingIndicator.isVisible = true
                itemView.postDelayed(showEmptyRunnable, 3000)
            } else {
                binding.emptyLayout.isVisible = false
                binding.loadingIndicator.isVisible = false
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, loadState: LoadState): ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val binding = ItemShelfEmptyBinding.inflate(inflater, parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, loadState: LoadState) {
        super.onBindViewHolder(holder, loadState)
        holder.bind(loadState)
    }

    override val adapter = this
    override fun getSpanSize(position: Int, width: Int, count: Int) = count
}