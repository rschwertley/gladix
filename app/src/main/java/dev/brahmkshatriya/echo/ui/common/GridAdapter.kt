package dev.brahmkshatriya.echo.ui.common

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Rect
import android.view.View
import androidx.core.util.toKotlinPair
import androidx.core.view.doOnLayout
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.utils.ui.UiUtils.dpToPx
import dev.brahmkshatriya.echo.utils.ui.UiUtils.resolveStyledDimension
import kotlin.math.floor

interface GridAdapter {
    val adapter: RecyclerView.Adapter<*>
    fun getSpanSize(position: Int, width: Int, count: Int): Int

    // Lets VerticalSpacingItemDecoration skip adding a gap before items that already provide
    // their own visual separation (e.g. section headers), avoiding doubled spacing.
    fun isSectionHeader(position: Int): Boolean = false

    class Concat(
        vararg adapters: GridAdapter
    ) : GridAdapter {
        override val adapter = ConcatAdapter(adapters.map { it.adapter })
        private val getSpanSizeMap = adapters.mapIndexed { index, gridAdapter ->
            gridAdapter.adapter to gridAdapter::getSpanSize
        }.toMap()
        private val isSectionHeaderMap = adapters.mapIndexed { index, gridAdapter ->
            gridAdapter.adapter to gridAdapter::isSectionHeader
        }.toMap()

        override fun getSpanSize(position: Int, width: Int, count: Int): Int {
            val (adapter, pos) = adapter.getWrappedAdapterAndPosition(position).toKotlinPair()
            val getSpanSize = getSpanSizeMap[adapter]
                ?: throw IllegalStateException("No span size function found for adapter: ${adapter.javaClass.name}")
            return getSpanSize(pos, width, count)
        }

        override fun isSectionHeader(position: Int): Boolean {
            val (adapter, pos) = adapter.getWrappedAdapterAndPosition(position).toKotlinPair()
            val isSectionHeader = isSectionHeaderMap[adapter] ?: return false
            return isSectionHeader(pos)
        }
    }

    class VerticalSpacingItemDecoration(
        private val spacingPx: Int, private val gridAdapter: GridAdapter
    ) : RecyclerView.ItemDecoration() {
        override fun getItemOffsets(
            outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State
        ) {
            val position = parent.getChildAdapterPosition(view)
            if (position == RecyclerView.NO_POSITION || state.itemCount == 0) return
            val lm = parent.layoutManager as? GridLayoutManager
            val spanCount = lm?.spanCount ?: 1
            val lookup = lm?.spanSizeLookup
            val itemGroup = lookup?.getSpanGroupIndex(position, spanCount) ?: position
            // Find the rightmost adapter position in the same visual row. With caching
            // enabled this is O(1) per step; for spanCount=2 it checks at most 1 extra item.
            val lastInRow = runCatching {
                if (lookup != null) {
                    var last = position
                    while (last + 1 < state.itemCount &&
                        lookup.getSpanGroupIndex(last + 1, spanCount) == itemGroup) last++
                    last
                } else position
            }.getOrDefault(position)
            if (lastInRow >= state.itemCount - 1) return
            if (runCatching { gridAdapter.isSectionHeader(lastInRow + 1) }.getOrDefault(false)) {
                outRect.bottom = HEADER_PRE_SPACING_DP.dpToPx(parent.context)
                return
            }
            outRect.bottom = spacingPx
        }

        companion object {
            // Extra space before a section header, on top of the header's own internal
            // padding. 0 by default since the header's own padding was deemed sufficient;
            // bump this if Home/Search read as under-spaced after removing the doubled gap.
            private const val HEADER_PRE_SPACING_DP = 0
        }
    }

    companion object {
        fun configureGridLayout(
            recycler: RecyclerView, gridAdapter: GridAdapter, even: Boolean = true
        ) {
            val context = recycler.context
            val isTV = (context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager)
                .currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
                context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
            val layoutManager = GridLayoutManager(context, 1)
            recycler.adapter = gridAdapter.adapter
            recycler.layoutManager = layoutManager
            recycler.addItemDecoration(VerticalSpacingItemDecoration(8.dpToPx(context), gridAdapter))
            recycler.doOnLayout { view ->
                val itemWidth = if (isTV) {
                    val screenHeight = context.resources.displayMetrics.heightPixels
                    val miniPlayerHeight = 84.dpToPx(context)
                    val usableHeight = screenHeight - miniPlayerHeight
                    (usableHeight / 2.5f).toInt()
                } else context.resolveStyledDimension(R.attr.itemCoverSize)
                val width = view.width - view.paddingLeft - view.paddingRight
                val calc = floor(width.toFloat() / (itemWidth + 8.dpToPx(context))).toInt()
                val count = if (calc > 1) calc - if (even) calc % 2 else 0 else 1
                recycler.post {
                    layoutManager.spanCount = count
                    layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                        override fun getSpanSize(position: Int): Int {
                            return gridAdapter.getSpanSize(position, width, count)
                        }
                    }
                    layoutManager.spanSizeLookup.setSpanGroupIndexCacheEnabled(true)
                    layoutManager.spanSizeLookup.setSpanIndexCacheEnabled(true)
                    recycler.requestLayout()
                }
            }
        }
    }
}