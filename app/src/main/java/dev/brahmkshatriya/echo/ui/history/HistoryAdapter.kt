package dev.brahmkshatriya.echo.ui.history

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.databinding.ItemHistoryBinding
import dev.brahmkshatriya.echo.databinding.ItemHistoryHeaderBinding
import dev.brahmkshatriya.echo.history.db.HistoryEntity
import dev.brahmkshatriya.echo.ui.media.more.MediaMoreBottomSheet
import dev.brahmkshatriya.echo.utils.image.ImageUtils.loadInto
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

sealed class HistoryListItem {
    data class Header(val label: String) : HistoryListItem()
    data class Item(val entity: HistoryEntity, val extensionName: String) : HistoryListItem()
}

class HistoryAdapter(
    private val host: Fragment,
    private val onTrackClick: (HistoryEntity) -> Unit
) : ListAdapter<HistoryListItem, RecyclerView.ViewHolder>(DIFF) {

    class ItemViewHolder(val binding: ItemHistoryBinding) : RecyclerView.ViewHolder(binding.root)
    class HeaderViewHolder(val binding: ItemHistoryHeaderBinding) : RecyclerView.ViewHolder(binding.root)

    override fun getItemViewType(position: Int) = when (getItem(position)) {
        is HistoryListItem.Header -> VIEW_TYPE_HEADER
        is HistoryListItem.Item -> VIEW_TYPE_ITEM
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_HEADER)
            HeaderViewHolder(ItemHistoryHeaderBinding.inflate(inflater, parent, false))
        else
            ItemViewHolder(ItemHistoryBinding.inflate(inflater, parent, false))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val listItem = getItem(position)) {
            is HistoryListItem.Header ->
                (holder as HeaderViewHolder).binding.headerLabel.text = listItem.label
            is HistoryListItem.Item -> with((holder as ItemViewHolder).binding) {
                val track = listItem.entity.track ?: return
                root.setOnClickListener { onTrackClick(listItem.entity) }
                root.setOnLongClickListener {
                    MediaMoreBottomSheet.show(
                        host = host,
                        contId = R.id.navHostFragment,
                        extensionId = listItem.entity.extensionId,
                        item = track,
                        loaded = true,
                        context = listItem.entity.context,
                        fromHistory = true
                    )
                    true
                }
                track.cover.loadInto(cover)
                title.text = track.title
                artist.text = track.artists.joinToString(", ") { it.name }
                playedAt.text = root.context.getString(R.string.history_item_subtitle, listItem.entity.playedAt.toRelativeTime(), listItem.extensionName)
                moreButton.setOnClickListener {
                    MediaMoreBottomSheet.show(
                        host = host,
                        contId = R.id.navHostFragment,
                        extensionId = listItem.entity.extensionId,
                        item = track,
                        loaded = true,
                        context = listItem.entity.context,
                        fromHistory = true
                    )
                }
            }
        }
    }

    private fun Long.toRelativeTime(): String {
        val diff = System.currentTimeMillis() - this
        return when {
            diff < TimeUnit.MINUTES.toMillis(1) -> "Just now"
            diff < TimeUnit.HOURS.toMillis(1) -> "${diff / TimeUnit.MINUTES.toMillis(1)}m ago"
            diff < TimeUnit.DAYS.toMillis(1) -> "${diff / TimeUnit.HOURS.toMillis(1)}h ago"
            diff < TimeUnit.DAYS.toMillis(7) -> "${diff / TimeUnit.DAYS.toMillis(1)}d ago"
            else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(this))
        }
    }

    companion object {
        private const val VIEW_TYPE_HEADER = 0
        private const val VIEW_TYPE_ITEM = 1

        private val DIFF = object : DiffUtil.ItemCallback<HistoryListItem>() {
            override fun areItemsTheSame(a: HistoryListItem, b: HistoryListItem) = when {
                a is HistoryListItem.Header && b is HistoryListItem.Header -> a.label == b.label
                a is HistoryListItem.Item && b is HistoryListItem.Item ->
                    a.entity.trackId == b.entity.trackId && a.entity.extensionId == b.entity.extensionId
                else -> false
            }

            override fun areContentsTheSame(a: HistoryListItem, b: HistoryListItem) = a == b
        }
    }
}
