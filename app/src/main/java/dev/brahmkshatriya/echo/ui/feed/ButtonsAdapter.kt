package dev.brahmkshatriya.echo.ui.feed

import android.graphics.drawable.InsetDrawable
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.databinding.ItemFeedButtonsBinding
import dev.brahmkshatriya.echo.ui.common.GridAdapter
import dev.brahmkshatriya.echo.utils.ui.AnimationUtils.animateVisibility
import dev.brahmkshatriya.echo.utils.ui.UiUtils.dpToPx
import dev.brahmkshatriya.echo.utils.ui.UiUtils.isTv
import dev.brahmkshatriya.echo.utils.ui.scrolling.ScrollAnimRecyclerAdapter
import dev.brahmkshatriya.echo.utils.ui.scrolling.ScrollAnimViewHolder

class ButtonsAdapter(
    private val viewModel: FeedData,
    private val listener: FeedClickListener,
    private val getAllLoaded: () -> List<Track>,
    private val isComplete: () -> Boolean,
    private val onCreatePlaylistClick: (() -> Unit)? = null,
    private val onEditPlaylistClick: (() -> Unit)? = null,
    private val onMicClick: () -> Unit,
) : ScrollAnimRecyclerAdapter<ButtonsAdapter.ViewHolder>(), GridAdapter {
    override fun getItemCount() = 1
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(parent, viewModel, listener, getAllLoaded, isComplete, onCreatePlaylistClick, onEditPlaylistClick, onMicClick)

    var buttons: FeedData.Buttons? = null
        set(value) {
            field = value
            notifyItemChanged(0)
        }

    // See FeedAdapter.withLoading's editPlaylistVisible.
    var showEditPlaylist = true
        set(value) {
            if (field == value) return
            field = value
            notifyItemChanged(0)
        }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        holder.bind(buttons, showEditPlaylist)
    }

    override fun onViewDetachedFromWindow(holder: ViewHolder) {
        holder.onViewDetached()
    }

    override val adapter = this
    override fun getSpanSize(position: Int, width: Int, count: Int) = count

    class ViewHolder(
        parent: ViewGroup,
        private val viewModel: FeedData,
        private val listener: FeedClickListener,
        private val getAllLoaded: () -> List<Track>,
        private val isComplete: () -> Boolean,
        private val onCreatePlaylistClick: (() -> Unit)? = null,
        private val onEditPlaylistClick: (() -> Unit)? = null,
        private val onMicClick: () -> Unit,
        private val binding: ItemFeedButtonsBinding = ItemFeedButtonsBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
    ) : ScrollAnimViewHolder(binding.root) {

        private var feed: FeedData.Buttons? = null
        private var wasShowingButtons = false

        init {
            binding.micButton.setOnClickListener { onMicClick() }
            binding.createPlaylistButton.setOnClickListener { onCreatePlaylistClick?.invoke() }
            binding.editPlaylistButton.setOnClickListener { onEditPlaylistClick?.invoke() }
            binding.searchToggleButton.addOnCheckedChangeListener { _, isChecked ->
                viewModel.searchToggled = isChecked
                if (!isChecked) {
                    binding.searchLayout.clearFocus()
                    viewModel.searchQuery = null
                    viewModel.onSearchClicked()
                }
                binding.searchBarContainer.animateVisibility(isChecked)
            }
            binding.searchBarText.addTextChangedListener { text ->
                viewModel.searchQuery = text?.toString()?.takeIf { it.isNotBlank() }
                binding.searchClose.isEnabled = !text.isNullOrBlank()
            }
            binding.searchClose.setOnClickListener {
                binding.searchBarText.setText("")
                viewModel.searchQuery = null
                viewModel.onSearchClicked()
            }
            binding.searchBarText.setOnEditorActionListener { v, _, _ ->
                viewModel.onSearchClicked()
                true
            }
            binding.sortToggleButton.setOnClickListener {
                listener.onSortClicked(it, feed?.feedId)
            }
            // ⚠️ THE SAME LIST A TAP QUEUES, IN SCREEN ORDER (GitHub #4 follow-up, 2026-10-29).
            // listener.declaredTracks is customTrackList passed through FeedAdapter.inDisplayOrder, so a
            // sort, "Reversed" or a search is honoured here as well; it is the RETAINED list, which only
            // differs from feed?.buttons during a reload (see FeedAdapter's retainedButtons note).
            // An album or playlist page (feed.item != null) used to ALWAYS hand the item to vm.play,
            // which reloads in the source's order - so with a sort applied it now queues the rows as
            // shown, still with the item as the queue's context. Unsorted pages keep the item path.
            // ⚠⚠ [2026-11-02] AN UNSORTED PAGE NOW QUEUES ITS ROWS TOO, ONCE THEY ARE THE WHOLE LIST (isComplete:
            // the last page loaded, no next one). It handed the item to vm.play instead, which re-fetched the list
            // in the service - on Deezer a second getSongs plus, until this build, the full dead-song check before
            // the first song (a ~1,500-song playlist waited ~10 s or more). The item path stays for a partial window
            // (rows still paging in), where it loads the rest. Same context: feed?.item goes to vm.setQueue as the
            // queue's context exactly as it went to vm.play (Playing from, History's Go to, isOrderedCollection).
            // Shuffle goes through the 1151 fresh-list path (setQueue shuffleList -> ShufflePlayer.setMediaItems),
            // as Library and sorted pages already did. Rows carry no TRACK_TOKEN when painted from the store; the
            // first song's DeezerTrackClient.loadTrack re-fetches one (hasUsableToken), one extra lookup.
            binding.playButton.setOnClickListener {
                val list = if (rowsAreTheList()) listener.declaredTracks() ?: getAllLoaded() else null
                listener.onPlayClicked(it, feed?.extensionId, feed?.item, list, false)
            }
            binding.shuffleButton.setOnClickListener {
                val feed = feed
                val list = if (rowsAreTheList()) listener.declaredTracks() ?: getAllLoaded() else null
                listener.onPlayClicked(it, feed?.extensionId, feed?.item, list, true)
            }
            // TV only (phone: isTv() == false → no-op, layout unchanged).
            // [CORRECTED 2026-11-02] These eight buttons no longer get a tv_focus_pill foreground: all are Expressive
            // buttons whose shape changes with state (pressed, and checked for the search toggle), which the fixed
            // pill did not follow. TvFocusRing now draws their focus as each button's own stroke.
            // [CORRECTED 2026-11-02, later] The search toggle no longer changes shape when checked on TV: it uses
            // ToggleIconButton, a full pill in every state on TV, like the Library tabs. The others still go CornerSmall
            // while OK is held.
            // [CORRECTED 2026-11-04] Two more claims here were wrong or are gone. "All are Expressive buttons whose shape
            // changes" was never true of Shuffle: its replaced background makes getShapeAppearance throw, so it always
            // took TvFocusRing's overlay. And the rest now take that overlay too, as a two-tone ring, with no corner
            // change while OK is held (FilledButton and the icon button styles in values-television).
            if (binding.root.context.isTv()) {
                // Shuffle: the cookie's points reach ~2dp from the view's edge, inside the ring's 6dp, so it is inset to
                // sit clear of the circular ring. Its tint carries over (AppCompat re-applies it to a new background).
                // Padding restored because an InsetDrawable's insets otherwise replace the view's padding (View.setBackground).
                binding.shuffleButton.run {
                    val start = paddingStart
                    val top = paddingTop
                    val end = paddingEnd
                    val bottom = paddingBottom
                    background = InsetDrawable(background, 4.dpToPx(context))
                    setPaddingRelative(start, top, end, bottom)
                }
                // playButton is the only text button here — bump its 10-foot text size.
                binding.playButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            }
        }

        // Sorted or item-less pages as before (1155), plus an unsorted page whose rows are the whole, non-empty list.
        // An empty one keeps the item path and its "list is empty" message.
        private fun rowsAreTheList() = feed?.item == null ||
            viewModel.displayOrder != FeedData.DisplayOrder.Source ||
            (isComplete() && getAllLoaded().isNotEmpty())

        fun bind(feed: FeedData.Buttons?, showEditPlaylist: Boolean) {
            val buttons = feed?.buttons ?: Feed.Buttons.EMPTY
            this.feed = feed
            val showButtons = buttons.run { showPlayAndShuffle || showSort || showSearch }
            binding.searchToggleButton.isChecked = viewModel.searchToggled
            binding.searchBarText.setText(viewModel.searchQuery)
            binding.chipGroup.configure(feed?.sortState)
            // A null feed is a reload or tab switch in progress (FeedData's dataFlow emits null first). The row keeps
            // its height then, INVISIBLE rather than GONE (2026-11-02, Library tab-switch flash; Jun 13 precedent:
            // initialButtons). Invisible also means not tappable: with a null feed, Play would queue the PREVIOUS
            // tab's rows (getAllLoaded) and Sort would open with no feed id. Only a row that was showing keeps its
            // height, so a feed without buttons still starts and stays GONE.
            binding.buttonGroup.visibility = when {
                showButtons -> View.VISIBLE
                feed == null && wasShowingButtons -> View.INVISIBLE
                else -> View.GONE
            }
            if (feed != null) wasShowingButtons = showButtons
            if (!showButtons) return
            binding.playButton.isVisible = buttons.showPlayAndShuffle
            binding.shuffleButton.isVisible = buttons.showPlayAndShuffle
            binding.searchToggleButton.isVisible = buttons.showSearch
            binding.sortToggleButton.isVisible = buttons.showSort
            binding.createPlaylistButton.isVisible = onCreatePlaylistClick != null
            binding.editPlaylistButton.isVisible = onEditPlaylistClick != null && showEditPlaylist
        }

        fun onViewDetached() {
            binding.searchLayout.clearFocus()
        }

        private fun ChipGroup.configure(state: FeedSort.State?) {
            removeAllViews()
            var visible = false
            if (state?.feedSort != null) {
                visible = true
                val chip = Chip(context)
                chip.text = context.getString(state.feedSort.title)
                chip.isCheckable = true
                chip.isChecked = true
                addView(chip)
                chip.setOnClickListener {
                    // ⚠️ persistSortState() IS LOAD-BEARING HERE, NOT TIDINESS. This clear used to persist
                    // only as a SIDE EFFECT of the feed re-rendering afterwards (the removed write in
                    // FeedData.getFeedSourceData). With that gone, omitting this call would clear the sort
                    // on screen and leave it on disk — it would come back on the next feed load, which is
                    // exactly the failure the 2026-09-06 change exists to remove. `state.copy` keeps
                    // save = true, so this writes a cleared state rather than deleting the entry.
                    viewModel.feedSortState.value = state.copy(feedSort = null)
                    viewModel.persistSortState()
                }
            }
            if (state?.reversed == true) {
                visible = true
                val chip = Chip(context)
                chip.text = context.getString(R.string.reversed)
                chip.isCheckable = true
                chip.isChecked = true
                addView(chip)
                chip.setOnClickListener {
                    // Same as the sort chip above — see that comment. Without this call the "reversed"
                    // clear survives only until the next feed load.
                    viewModel.feedSortState.value = state.copy(reversed = false)
                    viewModel.persistSortState()
                }
            }
            isVisible = visible
        }
    }
}