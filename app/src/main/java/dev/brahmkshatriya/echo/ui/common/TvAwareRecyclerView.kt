package dev.brahmkshatriya.echo.ui.common

import android.content.Context
import android.content.pm.PackageManager
import android.util.AttributeSet
import android.view.View
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

class TvAwareRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : RecyclerView(context, attrs, defStyleAttr) {

    val isTv = context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

    var navRailView: View? = null

    override fun focusSearch(focused: View, direction: Int): View {
        if (!isTv) return super.focusSearch(focused, direction)
        val glm = layoutManager as? GridLayoutManager
            ?: return super.focusSearch(focused, direction)
        val itemView = findContainingItemView(focused)
            ?: return super.focusSearch(focused, direction)
        val currentPos = getChildAdapterPosition(itemView)
        if (currentPos == NO_POSITION) return super.focusSearch(focused, direction)

        return when (direction) {
            FOCUS_DOWN -> {
                val lookup = glm.spanSizeLookup
                val spanCount = glm.spanCount
                val currentRowIndex = lookup.getSpanGroupIndex(currentPos, spanCount)
                var nextRowFirstPos = currentPos + 1
                while (nextRowFirstPos < (adapter?.itemCount ?: 0)) {
                    if (lookup.getSpanGroupIndex(nextRowFirstPos, spanCount) > currentRowIndex) break
                    nextRowFirstPos++
                }
                if (nextRowFirstPos < (adapter?.itemCount ?: 0)) {
                    val currentSpanSize = lookup.getSpanSize(currentPos)
                    val nextSpanSize = lookup.getSpanSize(nextRowFirstPos)
                    if (currentSpanSize != nextSpanSize) {
                        val targetView = glm.findViewByPosition(nextRowFirstPos)
                        if (targetView != null) targetView
                        else {
                            glm.scrollToPosition(nextRowFirstPos)
                            focused
                        }
                    } else super.focusSearch(focused, direction)
                } else super.focusSearch(focused, direction)
            }
            FOCUS_UP -> {
                if (currentPos == 0) focused
                else super.focusSearch(focused, direction)
            }
            FOCUS_LEFT -> {
                val spanIndex = glm.spanSizeLookup.getSpanIndex(currentPos, glm.spanCount)
                if (spanIndex == 0) navRailView ?: super.focusSearch(focused, direction)
                else super.focusSearch(focused, direction)
            }
            else -> super.focusSearch(focused, direction)
        }
    }
}
