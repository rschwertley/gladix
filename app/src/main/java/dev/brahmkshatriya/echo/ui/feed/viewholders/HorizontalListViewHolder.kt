package dev.brahmkshatriya.echo.ui.feed.viewholders

import android.annotation.SuppressLint
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePaddingRelative
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Radio
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.databinding.ItemShelfListsBinding
import dev.brahmkshatriya.echo.databinding.ItemShelfListsMediaBinding
import dev.brahmkshatriya.echo.playback.PlayerState
import dev.brahmkshatriya.echo.ui.feed.FeedClickListener
import dev.brahmkshatriya.echo.ui.feed.FeedType
import dev.brahmkshatriya.echo.ui.feed.viewholders.shelf.ShelfType
import dev.brahmkshatriya.echo.ui.feed.viewholders.shelf.ShelfViewHolder
import dev.brahmkshatriya.echo.utils.ui.AnimationUtils.applyTranslationAndScaleAnimation
import dev.brahmkshatriya.echo.utils.ui.UiUtils.dpToPx

class HorizontalListViewHolder(
    parent: ViewGroup,
    listener: FeedClickListener,
    pool: RecyclerView.RecycledViewPool,
    private val binding: ItemShelfListsBinding = ItemShelfListsBinding.inflate(
        LayoutInflater.from(parent.context), parent, false
    )
) : FeedViewHolder<FeedType.HorizontalList>(binding.root) {
    val adapter = Adapter(listener)
    val layoutManager = LinearLayoutManager(parent.context, RecyclerView.HORIZONTAL, false)

    // Scratch copy of the media card layout, inflated once and never attached to a parent.
    // Used only to measure label text height when sizing the radio row (see contentRowHeightPx).
    // Inflating per measure would reintroduce per-bind work on these carousels — keep it cached.
    private val measureBinding = ItemShelfListsMediaBinding.inflate(
        LayoutInflater.from(parent.context)
    )

    init {
        binding.root.setRecycledViewPool(pool)
        binding.root.layoutManager = layoutManager
    }

    private fun resolvedItemCoverSizePx(): Int {
        val typedValue = TypedValue()
        binding.root.context.theme.resolveAttribute(R.attr.itemCoverSize, typedValue, true)
        return TypedValue.complexToDimensionPixelSize(
            typedValue.data, binding.root.context.resources.displayMetrics
        )
    }

    // Natural height of a view at a fixed width with height unconstrained. Respects the XML's
    // maxLines/padding/textSize at the current density and font scale — so a label that wraps
    // to two lines on a dense or large-font device reports its real height here.
    private fun View.naturalHeightPx(widthPx: Int): Int {
        measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        return measuredHeight
    }

    // Generic row height = deterministic cover/card geometry (baseHeightPx) + the tallest measured
    // label block across the shelf's items. Only the labels are measured (they bind synchronously);
    // the cover is async-loaded and would under-measure, so its height stays analytic in baseHeightPx.
    private fun <T> contentRowHeightPx(
        items: List<T>,
        baseHeightPx: Int,
        textWidthPx: Int,
        bindLabels: (T) -> List<TextView>,
    ): Int = baseHeightPx + (items.maxOfOrNull { item ->
        bindLabels(item).sumOf { it.naturalHeightPx(textWidthPx) }
    } ?: 0)

    private fun fixedRowHeightPx(shelf: Shelf.Lists<*>): Int = binding.root.context.run {
        when {
            shelf is Shelf.Lists.Items && shelf.list.all { it is Radio } -> {
                val coverSize = resolvedItemCoverSizePx()
                // Vertical chrome around the cover in item_shelf_lists_media + item_shelf_media_cover_big:
                // root paddingVertical 8dp + cover container paddingVertical 8dp - cover container
                // topMargin 4dp = 12dp. Density-scaled, font-scale-independent (it holds no text).
                val coverChrome = 12.dpToPx(this)
                contentRowHeightPx(
                    items = shelf.list,
                    baseHeightPx = coverSize + coverChrome,
                    textWidthPx = coverSize,
                ) { media ->
                    measureBinding.title.text = media.title
                    listOf(measureBinding.title)
                }
            }
            shelf is Shelf.Lists.Items ->
                resolvedItemCoverSizePx() +
                    resources.getDimensionPixelSize(R.dimen.shelf_media_text_block_height)
            shelf is Shelf.Lists.Categories ->
                resources.getDimensionPixelSize(R.dimen.shelf_category_row_height)
            else ->
                resources.getDimensionPixelSize(R.dimen.shelf_three_tracks_row_height)
        }
    }

    override fun bind(feed: FeedType.HorizontalList) {
        val endPadding = if (feed.shelf is Shelf.Lists.Tracks) 8 else 20
        binding.root.updatePaddingRelative(end = endPadding.dpToPx(binding.root.context))
        binding.root.updateLayoutParams { height = fixedRowHeightPx(feed.shelf) }
        adapter.resetScroll()
        adapter.tracks = feed.shelf.list.filterIsInstance<Track>()
        binding.root.adapter = adapter
        adapter.setItems(feed.shelf.toShelfType(feed.extensionId, feed.context, feed.tabId))
    }

    override fun onCurrentChanged(current: PlayerState.Current?) {
        adapter.onCurrentChanged(current)
    }

    fun Shelf.Lists<*>.toShelfType(
        extensionId: String, context: EchoMediaItem?, tabId: String?
    ) = when (this) {
        is Shelf.Lists.Items -> list.map { ShelfType.Media(extensionId, context, tabId, it) }
        is Shelf.Lists.Categories -> list.map {
            ShelfType.Category(extensionId, context, tabId, it)
        }

        is Shelf.Lists.Tracks -> list.chunked(3).mapIndexed { index, it ->
            ShelfType.ThreeTracks(
                extensionId, context, tabId, index,
                Triple(
                    it[0],
                    it.getOrNull(1),
                    it.getOrNull(2)
                )
            )
        }
    }

    class Adapter(
        private val listener: FeedClickListener
    ) : RecyclerView.Adapter<ShelfViewHolder<*>>() {
        var tracks: List<Track> = emptyList()
        private var items: List<ShelfType> = emptyList()

        // Synchronous list swap. The carousel is rebound wholesale on every section bind;
        // notifyDataSetChanged updates content within the same layout pass (no AsyncListDiffer
        // window) and, since this adapter has no stable IDs, flushes the item-view cache too —
        // so a recycled carousel can't keep showing the previously-bound section's leading items.
        @SuppressLint("NotifyDataSetChanged")
        fun setItems(list: List<ShelfType>) {
            items = list
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size
        override fun getItemViewType(position: Int) = items[position].type.ordinal
        override fun onCreateViewHolder(
            parent: ViewGroup, viewType: Int
        ) = when (ShelfType.Enum.entries[viewType]) {
            ShelfType.Enum.Category -> ShelfViewHolder.Category(parent, listener)
            ShelfType.Enum.Media -> ShelfViewHolder.Media(parent, listener)
            ShelfType.Enum.ThreeTracks -> ShelfViewHolder.ThreeTracks(
                parent, listener, { tracks }
            )
        }

        override fun onBindViewHolder(
            holder: ShelfViewHolder<*>, position: Int
        ) {
            holder.itemView.applyTranslationAndScaleAnimation(scrollAmountX)
            holder.scrollX = scrollAmountX
            when (holder) {
                is ShelfViewHolder.Category -> holder.bind(
                    position,
                    items.map { it as ShelfType.Category })

                is ShelfViewHolder.Media -> holder.bind(
                    position,
                    items.map { it as ShelfType.Media })

                is ShelfViewHolder.ThreeTracks -> holder.bind(
                    position,
                    items.map { it as ShelfType.ThreeTracks })
            }
            holder.onCurrentChanged(current)
        }

        var current: PlayerState.Current? = null

        override fun onViewDetachedFromWindow(holder: ShelfViewHolder<*>) {
            holder.onCurrentChanged(current)
        }

        override fun onViewAttachedToWindow(holder: ShelfViewHolder<*>) {
            holder.onCurrentChanged(current)
        }

        private var scrollAmountX: Int = 0
        private val scrollListener = object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                scrollAmountX = dx
            }
        }

        // A recycled HorizontalListViewHolder's Adapter instance retains scrollAmountX from
        // whatever row it previously displayed. Without resetting it on rebind, cards in a
        // freshly bound row that was never actually scrolled inherit a stale nonzero value,
        // triggering applyTranslationAndScaleAnimation's entrance shrink-from-50% animation
        // incorrectly.
        fun resetScroll() {
            scrollAmountX = 0
        }

        var recyclerView: RecyclerView? = null
        override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
            this.recyclerView = recyclerView
            recyclerView.addOnScrollListener(scrollListener)
        }

        override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
            recyclerView.removeOnScrollListener(scrollListener)
            this.recyclerView = null
        }

        private fun onEachViewHolder(action: ShelfViewHolder<*>.() -> Unit) {
            recyclerView?.let { rv ->
                for (i in 0 until rv.childCount) {
                    val holder = rv.getChildViewHolder(rv.getChildAt(i)) as? ShelfViewHolder<*>
                    holder?.action()
                }
            }
        }

        fun onCurrentChanged(current: PlayerState.Current?) {
            this.current = current
            onEachViewHolder { onCurrentChanged(current) }
        }
    }
}