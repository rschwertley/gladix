package dev.brahmkshatriya.echo.ui.main.search

import dev.brahmkshatriya.echo.utils.ui.UiUtils.setTvRowButtons
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.common.models.QuickSearchItem
import dev.brahmkshatriya.echo.databinding.ItemQuickSearchClearBinding
import dev.brahmkshatriya.echo.utils.ui.UiUtils.isTv
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
    }.also { holder ->
        // TV: the pink row ring (2026-11-02). The rows' Selectable style sets a plain ripple as foreground, and
        // TvFocusRing skips any view with one, so a focused row showed only a faint wash. tv_focused_list_item is the
        // TV list-row ring; unfocused it falls back to the same ripple. Phone keeps the style's foreground.
        if (parent.context.isTv()) holder.itemView.foreground =
            AppCompatResources.getDrawable(parent.context, R.drawable.tv_focused_list_item)
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
        // [2026-11-02] DOWN on the LAST row of the whole list (this adapter, or the Clear row after it) stays put, from
        // the row and from its insert button: on TV nothing keeps focus inside the overlay (FocusFinder confines it to
        // the overlay's cluster only on touchscreen devices), so Down from the last row used to land on whatever lay
        // below it behind the overlay. Read at key time, so a list that grew or shrank since the bind is still right.
        if (onFirstRowUp != null) holder.itemView.setTvRowButtons(
            listOf(holder.insertView),
            buttonKeys = { keyCode, _ -> holder.isLastDown(keyCode) },
        ) { keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP && holder.bindingAdapterPosition == 0) {
                if (event.action == KeyEvent.ACTION_DOWN) onFirstRowUp.invoke()
                true
            } else holder.isLastDown(keyCode)
        }
    }

    private fun RecyclerView.ViewHolder.isLastDown(keyCode: Int): Boolean {
        if (keyCode != KeyEvent.KEYCODE_DPAD_DOWN) return false
        val count = (itemView.parent as? RecyclerView)?.adapter?.itemCount ?: return false
        return absoluteAdapterPosition == count - 1
    }

    /**
     * ⚠️ TV ONLY: "Clear search history" as the LAST ROW of the overlay list (2026-11-02). On the phone it is the
     * overlay's ⋮ menu item, which a D-pad reaches only through the toolbar (the open TV item recorded in
     * search_mic_menu_white.xml). Shown while the list holds a searched entry; OK runs the same confirmed clear as
     * the menu (SearchFragment.confirmClearSearchHistory). Down stays put, as on the last quick-search row.
     */
    class ClearHistoryAdapter(
        private val onClick: () -> Unit,
    ) : RecyclerView.Adapter<ClearHistoryAdapter.ViewHolder>() {
        class ViewHolder(val binding: ItemQuickSearchClearBinding) : RecyclerView.ViewHolder(binding.root)

        var shown = false
            set(value) {
                if (field == value) return
                field = value
                if (value) notifyItemInserted(0) else notifyItemRemoved(0)
            }

        override fun getItemCount() = if (shown) 1 else 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
            ItemQuickSearchClearBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        ).apply {
            itemView.setOnClickListener { onClick() }
            itemView.setOnKeyListener { _, keyCode, _ -> keyCode == KeyEvent.KEYCODE_DPAD_DOWN }
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) = Unit
    }
}