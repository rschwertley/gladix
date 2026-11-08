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
import dev.brahmkshatriya.echo.ui.feed.viewholders.MediaViewHolder.Companion.subtitle
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

    // Vertical chrome around the cover, READ FROM THE ACTUAL inflated card (measureBinding) so it tracks
    // whichever layout variant is in use instead of assuming phone's: the card root's vertical padding plus
    // the cover container's net vertical chrome (its vertical padding + its SIGNED topMargin, which
    // item_shelf_media_cover_big pulls up by -4dp, so this subtracts).
    //
    // Shared by BOTH measured branches. Density-scaled and font-scale-independent (it holds no text), so a
    // padding change in either layout variant flows through here automatically and cannot reintroduce the
    // under-budgeting that once clipped TV titles off the bottom of a fixed row height.
    private fun measuredCoverChromePx(): Int {
        val coverRoot = measureBinding.coverContainer.root
        val coverTopMargin =
            (coverRoot.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin ?: 0
        return measureBinding.root.paddingTop + measureBinding.root.paddingBottom +
            coverRoot.paddingTop + coverRoot.paddingBottom + coverTopMargin
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
                // Radio cards carry a title and no subtitle; chrome via measuredCoverChromePx().
                contentRowHeightPx(
                    items = shelf.list,
                    baseHeightPx = coverSize + measuredCoverChromePx(),
                    textWidthPx = coverSize,
                ) { media ->
                    measureBinding.title.text = media.title
                    listOf(measureBinding.title)
                }
            }
            // STANDARD MEDIA CAROUSEL — MEASURED, NOT RESERVED. Was
            // `coverSize + R.dimen.shelf_media_text_block_height` (80dp phone / 100dp TV), a worst-case
            // reservation for two-line title + two-line subtitle. That dimen now has no consumers and is
            // deleted. The reservation was not merely generous, it was VARIABLE: how much of it a row
            // actually consumed depended on that row's longest label and on whether its items had subtitles
            // at all, so two carousels on one screen ended with different amounts of dead space and no
            // constant could equalise them. Measuring sizes each row to its own tallest card, which is what
            // the radio branch above has always done — same mechanism, same cached measureBinding, no new
            // machinery.
            //
            // ⚠️ THE SUBTITLE IS INCLUDED ONLY WHEN NON-BLANK, mirroring ShelfViewHolder's
            // `ItemShelfListsMediaBinding.bind` (`subtitle.isVisible = !sub.isNullOrBlank()`). Measuring it
            // unconditionally would budget a full line for shelves whose items carry no subtitle — artist
            // shelves especially — reinstating exactly the slack this replaces. The three fields read here
            // (title, subtitle text, subtitle visibility) are the whole of the coupling to that binder; if
            // it ever grows a fourth that affects HEIGHT, mirror it here.
            //
            // Font scale is handled by construction: measured heights are real pixels at the current scale,
            // where the dp reservation could be overflowed by a large font setting and clip.
            shelf is Shelf.Lists.Items -> {
                val coverSize = resolvedItemCoverSizePx()
                contentRowHeightPx(
                    items = shelf.list,
                    baseHeightPx = coverSize + measuredCoverChromePx(),
                    textWidthPx = coverSize,
                ) { media ->
                    measureBinding.title.text = media.title
                    val sub = media.subtitle(binding.root.context)
                    measureBinding.subtitle.text = sub
                    if (sub.isNullOrBlank()) listOf(measureBinding.title)
                    else listOf(measureBinding.title, measureBinding.subtitle)
                }
            }
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
        /**
         * [2026-11-07] Dead-song marks by track id, from FeedAdapter.playableMarks - its in-place marks for a carousel's
         * cards (Library -> All's Tracks row). Only a Yes track takes a mark; a track's own verdict stands. Applied at bind
         * and to [tracks] for the ThreeTracks tap gate. A change rebinds the cards with [PLAYABLE_MARKS], which skips the
         * scroll animation: a full bind would start one from the last scroll delta on cards already on screen.
         */
        var playableMarks: Map<String, Track.Playable> = emptyMap()
            set(value) {
                if (field == value) return
                field = value
                notifyItemRangeChanged(0, itemCount, PLAYABLE_MARKS)
            }

        private fun Track.marked(): Track =
            if (isPlayable != Track.Playable.Yes) this
            else playableMarks[id]?.let { copy(isPlayable = it) } ?: this

        private fun ShelfType.marked(): ShelfType = when (this) {
            is ShelfType.Media -> (this.media as? Track)?.marked()?.takeIf { it !== this.media }
                ?.let { ShelfType.Media(extensionId, context, tabId, it) } ?: this

            is ShelfType.ThreeTracks -> {
                val (a, b, c) = this.tracks
                val marked = Triple(a.marked(), b?.marked(), c?.marked())
                if (marked.first === a && marked.second === b && marked.third === c) this
                else ShelfType.ThreeTracks(extensionId, context, tabId, number, marked)
            }

            else -> this
        }

        private fun shownItems() = if (playableMarks.isEmpty()) items else items.map { it.marked() }

        override fun onCreateViewHolder(
            parent: ViewGroup, viewType: Int
        ) = when (ShelfType.Enum.entries[viewType]) {
            ShelfType.Enum.Category -> ShelfViewHolder.Category(parent, listener)
            ShelfType.Enum.Media -> ShelfViewHolder.Media(parent, listener)
            ShelfType.Enum.ThreeTracks -> ShelfViewHolder.ThreeTracks(
                parent, listener, { if (playableMarks.isEmpty()) tracks else tracks.map { it.marked() } }
            )
        }

        override fun onBindViewHolder(
            holder: ShelfViewHolder<*>, position: Int, payloads: MutableList<Any>
        ) {
            if (payloads.isNotEmpty() && payloads.all { it === PLAYABLE_MARKS }) bindContent(holder, position)
            else super.onBindViewHolder(holder, position, payloads)
        }

        override fun onBindViewHolder(
            holder: ShelfViewHolder<*>, position: Int
        ) {
            holder.itemView.applyTranslationAndScaleAnimation(scrollAmountX)
            holder.scrollX = scrollAmountX
            bindContent(holder, position)
        }

        private fun bindContent(holder: ShelfViewHolder<*>, position: Int) {
            val items = shownItems()
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

        private companion object {
            val PLAYABLE_MARKS = Any()
        }
    }
}