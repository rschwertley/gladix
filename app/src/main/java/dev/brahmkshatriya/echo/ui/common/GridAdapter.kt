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
            // Find the rightmost adapter position in the same visual row. With caching
            // enabled this is O(1) per step; for spanCount=2 it checks at most 1 extra item.
            // [CORRECTED 2026-11-12] NOT O(1): IT WAS O(position) PER CALL, and it caused ANR 911f7347 (1178, a fast-scroll
            // jump on echo-offline). The public SpanSizeLookup.getSpanGroupIndex only READS the group cache enabled in
            // configureGridLayout; only the package-private getCachedSpanGroupIndex writes it, and GridLayoutManager calls that
            // for accessibility, focus search and the span-info estimator (off here), never in layout (RecyclerView 1.4.0,
            // GridLayoutManager.java:1618 and :1723-1758, read from the sources jar). So every call walked from position 0,
            // at least twice per item, and a drag that lays out N items deep in a long list cost about N x position.
            // Now the row is found from the child's own LayoutParams: GridLayoutManager.layoutChunk sets spanIndex and
            // spanSize (assignSpans) before calculateItemDecorationsForChild calls this, and the walk takes the next item
            // while it still fits in the row, exactly GridLayoutManager's own grouping, so at most spanCount steps. The old
            // walk stays for a child with no span yet (INVALID_SPAN_ID). A child laid out before an insert can carry a span
            // one layout stale: at worst one row's gap for one frame.
            val lp = view.layoutParams as? GridLayoutManager.LayoutParams
            val lastInRow = runCatching {
                if (lookup != null && lp != null && lp.spanIndex != GridLayoutManager.LayoutParams.INVALID_SPAN_ID) {
                    var used = lp.spanIndex + lp.spanSize
                    var last = position
                    while (last + 1 < state.itemCount) {
                        val next = lookup.getSpanSize(last + 1)
                        if (used + next > spanCount) break
                        used += next
                        last++
                    }
                    last
                } else if (lookup != null) {
                    val itemGroup = lookup.getSpanGroupIndex(position, spanCount)
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
            // THE PRE-HEADER GAP, FOR EVERY SECTION TYPE. This REPLACES the row spacing rather than
            // adding to it (note the `return` above), so it is the WHOLE decoration contribution before a
            // header — a value of 0 means the gap is the header's own 8dp marginVertical and nothing else.
            //
            // ⚠️ ONE VALUE, DELIBERATELY. A per-type hook (extraSpacingBeforeHeaderDp) lived here until
            // 2026-09-04 and was deleted rather than zeroed: it COMPENSATED for section types ending
            // differently instead of making them end the same, so every new section type needed a new
            // number and the numbers could only ever be tuned against each other. The differences it was
            // compensating for were 4dp of vertical padding in three layouts (the carousel card, video,
            // video-horizontal), all now normalised to zero, plus a variable amount of unconsumed row
            // reservation in carousels, now gone because HorizontalListViewHolder MEASURES the row instead
            // of reserving a dimen. With every type contributing nothing of its own, this constant is the
            // only lever and it moves all of them together.
            //
            // If a section type ever ends with the wrong gap again, the fix is to find what padding that
            // type carries and remove it — not to reintroduce a per-type exception here.
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
            // ══ DO NOT RE-ENABLE setUsingSpansToEstimateScrollbarDimensions(true) ═══════════════════
            // Added 2026-09-05, REVERTED 2026-09-06 after it shipped in build 1081 and produced a FATAL
            // crash: 10 events, 7 users, including Play installs (install_source_installer =
            // com.android.vending).
            //
            //   java.lang.IllegalArgumentException: Cannot find wrapper for 4
            //     ConcatAdapter.getWrappedAdapterAndPosition
            //     GridAdapter$Concat.getSpanSize          (this file, the getSpanSize above)
            //     GridLayoutManager$SpanSizeLookup.getSpanSize
            //     GridLayoutManager.getCachedSpanGroupIndex
            //     GridLayoutManager.computeScrollOffsetWithSpanInfo
            //     GridLayoutManager.computeVerticalScrollOffset
            //     RecyclerView.canScrollVertically
            //     SwipeRefreshLayout.canChildScrollUp / onInterceptTouchEvent
            //
            // WHY IT IS FATAL RATHER THAN COSMETIC: the entry point is a TOUCH, not a scroll.
            // SwipeRefreshLayout asks canChildScrollUp on every intercepted touch, so with the flag on,
            // ANY tap on a feed screen runs the span-info estimator, which walks positions through our
            // ConcatAdapter-backed getSpanSize. If the adapter set has changed since the positions the
            // estimator is working from, getWrappedAdapterAndPosition cannot resolve one and throws
            // IllegalArgumentException — out of a touch handler, so it takes the process down.
            //
            // ⚠️ THE SAFETY ARGUMENT THAT WAS ACCEPTED FOR THIS WAS EXACTLY BACKWARDS. The scoping
            // question was "does enabling it globally matter", and the answer taken was that the flag
            // moves RANGE and OFFSET together — computeScrollRangeWithSpanInfo and
            // computeScrollOffsetWithSpanInfo both switch on this one flag — so the offset+extent==range
            // identity could not break. That pairing is real, and it is the thing that crashes: the
            // OFFSET half is what reaches getCachedSpanGroupIndex, and the default offset path never
            // calls it. The argument for why it was safe was a description of the defect.
            //
            // AND IT BOUGHT NOTHING. Measured on device before the crash arrived: Search still stalled at
            // the same row, Home and History unchanged. So row density was never the driver of the range
            // swing — which also weakens the row-height-cache proposal that attacks the same quantity
            // from the same premise.
            //
            // DO NOT "FIX" THIS BY GUARDING getSpanSize (returning 1 for an unresolvable position, or
            // catching IllegalArgumentException). That would hide a real staleness signal to buy back an
            // estimator that has already been refuted on device, on the path where a wrong span size
            // silently corrupts layout instead of crashing. The flag stays off.
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