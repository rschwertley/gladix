package dev.brahmkshatriya.echo.ui.feed

import android.util.TypedValue
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
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
import dev.brahmkshatriya.echo.utils.ui.UiUtils.isTv
import dev.brahmkshatriya.echo.utils.ui.scrolling.ScrollAnimRecyclerAdapter
import dev.brahmkshatriya.echo.utils.ui.scrolling.ScrollAnimViewHolder

class ButtonsAdapter(
    private val viewModel: FeedData,
    private val listener: FeedClickListener,
    private val getAllLoaded: () -> List<Track>,
    private val onCreatePlaylistClick: (() -> Unit)? = null,
    private val onEditPlaylistClick: (() -> Unit)? = null,
    private val onMicClick: () -> Unit,
) : ScrollAnimRecyclerAdapter<ButtonsAdapter.ViewHolder>(), GridAdapter {
    override fun getItemCount() = 1
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(parent, viewModel, listener, getAllLoaded, onCreatePlaylistClick, onEditPlaylistClick, onMicClick)

    var buttons: FeedData.Buttons? = null
        set(value) {
            field = value
            notifyItemChanged(0)
        }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        holder.bind(buttons)
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
        private val onCreatePlaylistClick: (() -> Unit)? = null,
        private val onEditPlaylistClick: (() -> Unit)? = null,
        private val onMicClick: () -> Unit,
        private val binding: ItemFeedButtonsBinding = ItemFeedButtonsBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
    ) : ScrollAnimViewHolder(binding.root) {

        private var feed: FeedData.Buttons? = null

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
            binding.playButton.setOnClickListener {
                val list = if (feed?.item == null) {
                    feed?.buttons?.customTrackList ?: getAllLoaded()
                } else null
                listener.onPlayClicked(it, feed?.extensionId, feed?.item, list, false)
            }
            binding.shuffleButton.setOnClickListener {
                val feed = feed
                val list = if (feed?.item == null) {
                    feed?.buttons?.customTrackList ?: getAllLoaded()
                } else null
                listener.onPlayClicked(it, feed?.extensionId, feed?.item, list, true)
            }
            // TV only (phone: isTv() == false → no-op, layout unchanged): add the focus frame to each feed
            // action button so D-pad focus is visible. New Drawable instance per button (foregrounds must
            // not be shared across views).
            if (binding.root.context.isTv()) {
                val ctx = binding.root.context
                listOf(
                    binding.shuffleButton, binding.playButton, binding.searchToggleButton,
                    binding.sortToggleButton, binding.createPlaylistButton, binding.editPlaylistButton,
                    binding.micButton, binding.searchClose
                ).forEach { it.foreground = ContextCompat.getDrawable(ctx, R.drawable.tv_focus_pill) }
                // playButton is the only text button here — bump its 10-foot text size.
                binding.playButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            }
        }

        fun bind(feed: FeedData.Buttons?) {
            val buttons = feed?.buttons ?: Feed.Buttons.EMPTY
            this.feed = feed
            val showButtons = buttons.run { showPlayAndShuffle || showSort || showSearch }
            binding.searchToggleButton.isChecked = viewModel.searchToggled
            binding.searchBarText.setText(viewModel.searchQuery)
            binding.chipGroup.configure(feed?.sortState)
            binding.buttonGroup.isVisible = showButtons
            if (!showButtons) return
            binding.playButton.isVisible = buttons.showPlayAndShuffle
            binding.shuffleButton.isVisible = buttons.showPlayAndShuffle
            binding.searchToggleButton.isVisible = buttons.showSearch
            binding.sortToggleButton.isVisible = buttons.showSort
            binding.createPlaylistButton.isVisible = onCreatePlaylistClick != null
            binding.editPlaylistButton.isVisible = onEditPlaylistClick != null
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
                    viewModel.feedSortState.value = state.copy(feedSort = null)
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
                    viewModel.feedSortState.value = state.copy(reversed = false)
                }
            }
            isVisible = visible
        }
    }
}