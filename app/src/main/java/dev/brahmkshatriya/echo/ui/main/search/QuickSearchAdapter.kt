package dev.brahmkshatriya.echo.ui.main.search

import dev.brahmkshatriya.echo.utils.ui.UiUtils.setTvRowButtons
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import dev.brahmkshatriya.echo.common.models.QuickSearchItem
import dev.brahmkshatriya.echo.utils.ui.scrolling.ScrollAnimListAdapter

class QuickSearchAdapter(
    val listener: Listener,
    // O6 (TV-only): invoked when D-pad UP is pressed on the FIRST suggestion/history row, to return focus to
    // the search field. Null on phone — the key listener is then never installed, so touch is byte-identical.
    private val onFirstRowUp: (() -> Unit)? = null,
) : ScrollAnimListAdapter<QuickSearchAdapter.Item, QuickSearchViewHolder>(DiffCallback) {
    data class Item(
        val extensionId: String,
        val actual: QuickSearchItem,
    )

    object DiffCallback : DiffUtil.ItemCallback<Item>() {
        override fun areItemsTheSame(oldItem: Item, newItem: Item): Boolean {
            if (oldItem.extensionId != newItem.extensionId) return false
            return oldItem.actual.sameAs(newItem.actual)
        }

        override fun areContentsTheSame(oldItem: Item, newItem: Item) = oldItem == newItem
    }

    interface Listener {
        fun onClick(item: Item, transitionView: View)
        fun onLongClick(item: Item, transitionView: View): Boolean
        fun onInsert(item: Item)
    }

    override fun getItemViewType(position: Int) = when (getItem(position).actual) {
        is QuickSearchItem.Query -> 0
        is QuickSearchItem.Media -> 1
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = when (viewType) {
        0 -> QuickSearchViewHolder.Query.create(parent)
        1 -> QuickSearchViewHolder.Media.create(parent)
        else -> throw IllegalArgumentException("Unknown viewType: $viewType")
    }

    override fun onBindViewHolder(holder: QuickSearchViewHolder, position: Int) {
        val item = getItem(position) ?: return
        holder.bind(item)
        holder.itemView.setOnClickListener {
            listener.onClick(item, holder.transitionView)
        }
        holder.itemView.setOnLongClickListener {
            listener.onLongClick(item, holder.transitionView)
        }
        holder.insertView.setOnClickListener {
            listener.onInsert(item)
        }

        // O6 (TV): UP on the FIRST row returns focus to the search field (the plain overlay RecyclerView has
        // no TvAwareRecyclerView focus handling, and geometric UP doesn't cross into the toolbar's edit text).
        // Non-first rows keep normal list movement (return false). Re-applied every bind, so it survives list
        // rebuilds. Consumes both DOWN and UP of the key at row 0 so the press is fully handled; acts once on
        // DOWN. Installed only when onFirstRowUp != null (TV) — phone rows get no key listener.
        // The row also holds the insert button, so the same listener carries the TV row-buttons rule (Right
        // into insert) - see UiUtils.setTvRowButtons; the first-row UP runs first via rowKeys.
        if (onFirstRowUp != null) holder.itemView.setTvRowButtons(listOf(holder.insertView)) { keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP && holder.bindingAdapterPosition == 0) {
                if (event.action == KeyEvent.ACTION_DOWN) onFirstRowUp.invoke()
                true
            } else false
        }
    }
}