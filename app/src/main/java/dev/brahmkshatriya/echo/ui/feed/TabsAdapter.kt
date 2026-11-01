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
    // true = NO ROW AT ALL while there are no tabs (2026-11-01, the player's Info tab only), so a tab-less feed
    // gets no empty row and no grid spacing; the row is inserted when tabs arrive. false (every other caller) =
    // always one row, hidden by apply() when empty - unchanged.
    // ⚠️ KEEP IT BEFORE onTabSelected: callers pass that as a TRAILING LAMBDA, which binds to the LAST parameter.
    // Added after it, it broke LyricsItemAdapter and EditPlaylistFragment (release compile, 2026-11-01).
    private val collapseWhenEmpty: Boolean = false,
    private val onTabSelected: (View, Int, T) -> Unit,
) : ScrollAnimRecyclerAdapter<TabsAdapter.ViewHolder>(), GridAdapter {
    override val adapter = this
    override fun getSpanSize(position: Int, width: Int, count: Int) = count
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(parent)
    override fun getItemCount() = if (collapseWhenEmpty && data.isEmpty()) 0 else 1

    // ⚠️ REBOUND IN PLACE, WITH A PAYLOAD (2026-10-31). A plain notifyItemChanged lets the item animator swap in a
    // different holder for the change animation, so a FOCUSED tab button was replaced by another view and focus
    // fell elsewhere - on TV, back to All (the Library bounce, see FeedData.chosenTabId). With a payload the same
    // holder is rebound and apply() keeps its buttons. On TV, if a tab had focus and the rebuild still lost it,
    // focus goes back to the checked tab. Phone: the tab row just no longer cross-fades on a data change.
    var data: List<T> = emptyList()
        set(value) {
            val before = itemCount
            field = value
            refocusSelected = parent?.hasFocus() == true
            // Without collapseWhenEmpty both counts are 1, so this is the same notifyItemChanged as before.
            when (itemCount) {
                before -> if (before == 1) notifyItemChanged(0, REBIND)
                1 -> notifyItemInserted(0)
                else -> notifyItemRemoved(0)
            }
        }

    private var refocusSelected = false

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
        if (refocusSelected) {
            refocusSelected = false
            if (parent.context.isTv()) parent.post {
                if (!parent.hasFocus() && selected >= 0) parent.getChildAt(selected)?.requestFocus()
            }
        }
        parent.doOnLayout {
            if (selected < 0) return@doOnLayout
            val scrollX = parent.children.filter { it.isVisible }
                .take(selected).sumOf { it.width }
            holder.binding.root.scrollTo(scrollX, 0)
        }
    }

    // TabsProbe (TEMPORARY): true while apply() sets the checked states, so a checked change can be attributed.
    private var applying = false

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
            // TabsProbe (TEMPORARY, TV only - enabled is false on the phone): every checked change with its source,
            // and focus moves in the tab row.
            if (TabsProbe.enabled) {
                tab.addOnCheckedChangeListener { b, checked ->
                    TabsProbe.log(
                        "checked tab=${parent.indexOfChild(b)} -> $checked source=${if (applying) "apply" else "other"} " +
                            "selected=$selected"
                    )
                }
                tab.setOnFocusChangeListener { v, hasFocus ->
                    TabsProbe.log("focus tab=${parent.indexOfChild(v)} has=$hasFocus")
                }
            }
        } else if (toKeep < 0) repeat(-toKeep) {
            parent.getChildAt(tabs.size + it).isVisible = false
        }
        applying = true
        try { tabs.indices.forEach { i ->
            val tab = tabs[i]
            val button = parent.getChildAt(i) as MaterialButton
            button.apply {
                isVisible = true
                val title = getTitle(tab)
                if (text.toString() != title) text = title
                isChecked = i == selected
                // Single selection action shared by touch (OnClickListener) and D-pad OK (OnKeyListener).
                val select = View.OnClickListener { v ->
                    TabsProbe.log("select tab=$i selected=$selected -> ${if (i == selected) "already selected" else "onTabSelected"}")
                    if (i == selected) isChecked = true
                    else onTabSelected(v, i, tab)
                }
                setOnClickListener(select)
                // Mirror the feed cards' D-pad handling (GestureListener.handleGestures): on TV the default
                // performClick path doesn't reach the listener for these grouped checkable buttons, so handle
                // OK/ENTER on key-UP explicitly and run the SAME select action. Returning true consumes the UP
                // so performClick can't also fire it (no double-invoke on TV). Inert on phone — touch never
                // delivers a key event here. Re-applied on every apply(), so it survives tab rebuilds.
                // ⚠️ [CORRECTED 2026-10-31] OK NOW TAKES THE SAME PATH AS A TAP. The listener above called
                // select.onClick(v) on ACTION_UP only, which (1) skipped MaterialButton.performClick - so the
                // toggleCheckedStateOnClick toggle a tap does never ran on TV - and (2) left ACTION_DOWN to
                // View.onKeyDown, which presses the view and arms a long-press that the consumed UP never
                // cleared (onKeyUp is what does). The Library tab bounce (1156-1159) is TV-only while the phone,
                // through the same write path, works, so the difference has to be here or after it; this is the
                // one TV-only difference in the path itself. Now DOWN is consumed (pressed state only, nothing
                // armed) and UP clears it and calls performClick(): toggle, then the click listener, once, as on
                // the phone. TabsProbe reports whether that was it.
                // [CORRECTED 2026-10-31, same day] "the one TV-only difference in the path itself" overstated it
                // as the cause. Git since this listener landed (aeb6edf5, 2026-07-23): item_tab.xml and
                // toggleCheckedStateOnClick unchanged (attribute since 2025-07-25), Material 1.14.0 since
                // 2026-06-15, onTabSelected unchanged, selectTab changed only by 1159's chosenTabId - so nothing
                // could have turned a toggle-skipping key-up from working to broken. And the skipped toggle only
                // affects the checked LOOK until the rebind: select -> onTabSelected -> FeedData.selectTab writes
                // the selection either way, while the symptom (content never changes) is after the write. Kept
                // because it matches the phone's tap path and cannot do harm; not expected to be the fix. Whether
                // OK ever switched tabs on the device after 2026-07-23 is unrecorded (marked fixed with no device check).
                setOnKeyListener { v, keyCode, event ->
                    TabsProbe.log("key tab=$i code=$keyCode action=${event.action} selected=$selected")
                    val confirm = keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                    if (confirm && event.action == KeyEvent.ACTION_DOWN) {
                        v.isPressed = true
                        true
                    } else if (confirm && event.action == KeyEvent.ACTION_UP) {
                        v.isPressed = false
                        v.performClick()
                        TabsProbe.markAfterOk(i) {
                            val checked = (0 until parent.childCount)
                                .filter { (parent.getChildAt(it) as? MaterialButton)?.isChecked == true }
                            "selected=$selected checked=$checked focused=${(0 until parent.childCount)
                                .firstOrNull { parent.getChildAt(it).hasFocus() }}"
                        }
                        true
                    } else false
                }
            }
        } } finally { applying = false }
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

    private companion object {
        // Any non-empty payload makes DefaultItemAnimator reuse the holder (canReuseUpdatedViewHolder).
        val REBIND = Any()
    }
}