package dev.brahmkshatriya.echo.ui.feed

import android.util.TypedValue
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.children
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonGroup
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.databinding.ItemTabBinding
import dev.brahmkshatriya.echo.utils.ui.UiUtils.isTv
import dev.brahmkshatriya.echo.databinding.ItemTabContainerBinding
import dev.brahmkshatriya.echo.ui.common.GridAdapter
import dev.brahmkshatriya.echo.utils.ui.scrolling.ScrollAnimRecyclerAdapter
import dev.brahmkshatriya.echo.utils.ui.scrolling.ScrollAnimViewHolder

class TabsAdapter<T>(
    private val getTitle: T.() -> String,
    private val onTabSelected: (View, Int, T) -> Unit
) : ScrollAnimRecyclerAdapter<TabsAdapter.ViewHolder>(), GridAdapter {
    override val adapter = this
    override fun getSpanSize(position: Int, width: Int, count: Int) = count
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(parent)
    override fun getItemCount() = 1

    var data: List<T> = emptyList()
        set(value) {
            field = value
            notifyItemChanged(0)
        }

    var selected = -1
        set(value) {
            field = value
            parent?.let { apply(it) }
        }

    var parent: MaterialButtonGroup? = null
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        val parent = holder.binding.buttonGroup
        this.parent = parent
        apply(parent)
        parent.doOnLayout {
            if (selected < 0) return@doOnLayout
            val scrollX = parent.children.filter { it.isVisible }
                .take(selected).sumOf { it.width }
            holder.binding.root.scrollTo(scrollX, 0)
        }
    }

    fun apply(parent: MaterialButtonGroup) {
        val tabs = data
        parent.isVisible = tabs.isNotEmpty()
        if (tabs.isEmpty()) return
        val toKeep = tabs.size - parent.childCount
        val inflater = LayoutInflater.from(parent.context)
        if (toKeep > 0) repeat(toKeep) {
            val tab = ItemTabBinding.inflate(inflater, parent, false).root
            // TV only (phone: isTv() == false → no-op, layout unchanged): focus frame (distinguish D-pad
            // focus from the checked tab) + bigger 10-foot tab text. New foreground instance per tab
            // (Drawables must not be shared across views).
            if (parent.context.isTv()) {
                tab.foreground = ContextCompat.getDrawable(parent.context, R.drawable.tv_focus_pill)
                tab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            }
            parent.addView(tab)
        } else if (toKeep < 0) repeat(-toKeep) {
            parent.getChildAt(tabs.size + it).isVisible = false
        }
        tabs.indices.forEach { i ->
            val tab = tabs[i]
            val button = parent.getChildAt(i) as MaterialButton
            button.apply {
                isVisible = true
                val title = getTitle(tab)
                if (text.toString() != title) text = title
                isChecked = i == selected
                // Single selection action shared by touch (OnClickListener) and D-pad OK (OnKeyListener).
                val select = View.OnClickListener { v ->
                    if (i == selected) isChecked = true
                    else onTabSelected(v, i, tab)
                }
                setOnClickListener(select)
                // Mirror the feed cards' D-pad handling (GestureListener.handleGestures): on TV the default
                // performClick path doesn't reach the listener for these grouped checkable buttons, so handle
                // OK/ENTER on key-UP explicitly and run the SAME select action. Returning true consumes the UP
                // so performClick can't also fire it (no double-invoke on TV). Inert on phone — touch never
                // delivers a key event here. Re-applied on every apply(), so it survives tab rebuilds.
                setOnKeyListener { v, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_UP &&
                        (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
                    ) {
                        select.onClick(v)
                        true
                    } else false
                }
            }
        }
    }

    class ViewHolder(
        parent: ViewGroup,
        val binding: ItemTabContainerBinding = ItemTabContainerBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
    ) : ScrollAnimViewHolder(binding.root) {
        init {
            binding.buttonGroup.removeAllViews()
        }
    }
}