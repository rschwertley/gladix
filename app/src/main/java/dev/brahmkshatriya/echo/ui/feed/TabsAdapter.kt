package dev.brahmkshatriya.echo.ui.feed

import android.util.TypedValue
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.children
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonGroup
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

    // The tab that received the last OK/ENTER DOWN, selected on the UP whichever button gets it; -1 = none
    // (2026-11-01, see the key listener in apply()). On the adapter, not the button, so every tab's listener sees it.
    private var okDownTab = -1

    fun apply(parent: MaterialButtonGroup) {
        val tabs = data
        parent.isVisible = tabs.isNotEmpty()
        if (tabs.isEmpty()) return
        val toKeep = tabs.size - parent.childCount
        val inflater = LayoutInflater.from(parent.context)
        if (toKeep > 0) repeat(toKeep) {
            val tab = ItemTabBinding.inflate(inflater, parent, false).root
            // TV only (phone: isTv() == false → no-op, layout unchanged): bigger 10-foot tab text.
            // [CORRECTED 2026-11-02] The focus frame is no longer a tv_focus_pill foreground here: the checked tab is
            // a rounded rectangle (Expressive state-list shape), so the fixed pill did not fit it. TvFocusRing now
            // draws focus as the tab's own stroke, which follows its shape in every state.
            // [CORRECTED 2026-11-02, later] On TV the checked tab is no longer a rounded rectangle: values-television's
            // TabButton keeps a full pill in every state (1164: the selected fill and the focus ring did not match).
            if (parent.context.isTv()) {
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
                // [CORRECTED 2026-11-01] "not expected to be the fix" held, and the 1160 GladixTabs report (Onn 4K
                // Pro) showed what was happening instead: OK DOWN reached the focused tab (3), ~30 ms later focus
                // moved to tab 0 (All, also the checked tab), and the UP was delivered to tab 0 - so the click
                // landed on All ("already selected") and the chosen tab was never selected. What moved focus is NOT
                // identified by reading: Material 1.14.0's press path (MaterialButton.setPressed -> the group's
                // PressedStateTracker, which only invalidates, and maybeAnimateSize, the buttonSizeChange width
                // animation that Widget.Material3.MaterialButtonGroup sets) makes no focus call; recyclerview
                // 1.4.0's recoverFocusFromState acts only when the focused row is hidden; refocusSelected needs a
                // tabsFlow emission, which logs a breadcrumb. So now, both halves:
                // (1) DOWN no longer sets the pressed state, which removes the one thing a press does here (that
                //     width animation and its relayouts). If the jump persists, (2) still selects the right tab.
                // (2) DOWN records which tab took it ([okDownTab]); UP - on whichever button it is delivered to -
                //     focuses and clicks THAT tab, so the selection no longer depends on where focus is at release.
                //     An UP with no DOWN in this row (the press started elsewhere) is consumed and does nothing.
                // Phone: touch never reaches a key listener; a hardware keyboard's Enter loses only the pressed look.
                // [CONFIRMED 2026-11-02] On the device (TV): OK on Playlists opens Playlists. Which of the two halves
                // did it was not measured - both stay. The temporary TabsProbe that reported the 1160 jump is removed.
                setOnKeyListener { _, keyCode, event ->
                    val confirm = keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                    if (confirm && event.action == KeyEvent.ACTION_DOWN) {
                        if (event.repeatCount == 0) okDownTab = i
                        true
                    } else if (confirm && event.action == KeyEvent.ACTION_UP) {
                        val target = okDownTab
                        okDownTab = -1
                        val chosen = parent.getChildAt(target)?.takeIf { target < data.size && it.isVisible }
                        if (chosen != null) {
                            if (!chosen.hasFocus()) chosen.requestFocus()
                            chosen.performClick()
                        }
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

    private companion object {
        // Any non-empty payload makes DefaultItemAnimator reuse the holder (canReuseUpdatedViewHolder).
        val REBIND = Any()
    }
}