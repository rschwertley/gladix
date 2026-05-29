package dev.brahmkshatriya.echo.utils.ui

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import androidx.recyclerview.widget.RecyclerView

class TvFocusRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : RecyclerView(context, attrs, defStyleAttr) {

    private val focusedRect = Rect()
    private val nextRect = Rect()

    override fun focusSearch(focused: View?, direction: Int): View? {
        val next = super.focusSearch(focused, direction) ?: return null
        if (focused == null) return next
        focused.getGlobalVisibleRect(focusedRect)
        next.getGlobalVisibleRect(nextRect)
        val valid = when (direction) {
            FOCUS_DOWN -> nextRect.top >= focusedRect.bottom - 2
            FOCUS_UP   -> nextRect.bottom <= focusedRect.top + 2
            FOCUS_RIGHT -> nextRect.left >= focusedRect.right - 2
            FOCUS_LEFT  -> nextRect.right <= focusedRect.left + 2
            else -> true
        }
        return if (valid) next else null
    }
}
