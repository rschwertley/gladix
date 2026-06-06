package dev.brahmkshatriya.echo.ui.common

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
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

    class Concat(
        vararg adapters: GridAdapter
    ) : GridAdapter {
        override val adapter = ConcatAdapter(adapters.map { it.adapter })
        private val getSpanSizeMap = adapters.mapIndexed { index, gridAdapter ->
            gridAdapter.adapter to gridAdapter::getSpanSize
        }.toMap()

        override fun getSpanSize(position: Int, width: Int, count: Int): Int {
            val (adapter, pos) = adapter.getWrappedAdapterAndPosition(position).toKotlinPair()
            val getSpanSize = getSpanSizeMap[adapter]
                ?: throw IllegalStateException("No span size function found for adapter: ${adapter.javaClass.name}")
            return getSpanSize(pos, width, count)
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