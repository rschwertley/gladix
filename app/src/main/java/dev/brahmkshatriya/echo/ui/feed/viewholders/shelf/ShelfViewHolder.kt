package dev.brahmkshatriya.echo.ui.feed.viewholders.shelf

import android.graphics.drawable.Animatable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.databinding.ItemShelfCategoryBinding
import dev.brahmkshatriya.echo.databinding.ItemShelfListsMediaBinding
import dev.brahmkshatriya.echo.databinding.ItemShelfListsThreeTracksBinding
import dev.brahmkshatriya.echo.playback.PlayerState
import dev.brahmkshatriya.echo.playback.PlayerState.Current.Companion.isPlaying
import dev.brahmkshatriya.echo.ui.feed.FeedClickListener
import dev.brahmkshatriya.echo.ui.feed.viewholders.CategoryViewHolder.Companion.bind
import dev.brahmkshatriya.echo.ui.feed.viewholders.MediaViewHolder.Companion.applyCover
import dev.brahmkshatriya.echo.ui.feed.viewholders.MediaViewHolder.Companion.bind
import dev.brahmkshatriya.echo.ui.feed.viewholders.MediaViewHolder.Companion.subtitle
import dev.brahmkshatriya.echo.utils.ui.UiUtils.dimIfUnplayable
import dev.brahmkshatriya.echo.utils.ui.scrolling.ScrollAnimViewHolder

sealed class ShelfViewHolder<T : ShelfType>(view: View) : ScrollAnimViewHolder(view) {
    var scrollX = 0
    abstract fun bind(index: Int, list: List<T>)
    open fun onCurrentChanged(current: PlayerState.Current?) {}

    class Category(
        parent: ViewGroup,
        listener: FeedClickListener,
        private val binding: ItemShelfCategoryBinding = ItemShelfCategoryBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        ),
    ) : ShelfViewHolder<ShelfType.Category>(binding.root) {

        private var item: ShelfType.Category? = null

        init {
            binding.root.setOnClickListener {
                listener.openFeed(
                    it,
                    item?.extensionId,
                    item?.id,
                    item?.category?.title,
                    item?.category?.subtitle,
                    item?.category?.feed
                )
            }
            binding.root.updateLayoutParams { width = WRAP_CONTENT }
            binding.icon.clipToOutline = true
        }

        override fun bind(index: Int, list: List<ShelfType.Category>) {
            val item = list[index]
            this.item = item
            binding.bind(item.category)
        }
    }

    class Media(
        parent: ViewGroup,
        listener: FeedClickListener,
        private val binding: ItemShelfListsMediaBinding = ItemShelfListsMediaBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        ),
    ) : ShelfViewHolder<ShelfType.Media>(binding.root) {
        var shelf: ShelfType.Media? = null

        init {
            binding.coverContainer.cover.clipToOutline = true
            binding.root.setOnClickListener {
                when (val item = shelf?.media) {
                    is Track -> {
                        if (item.isPlayable != Track.Playable.Yes) {
                            listener.onUnplayableClicked(item)
                        } else listener.onTracksClicked(
                            it, shelf?.extensionId, shelf?.context, listOf(item), 0
                        )
                    }

                    else -> listener.onMediaClicked(it, shelf?.extensionId, item, shelf?.context)
                }
            }
            binding.root.setOnLongClickListener {
                listener.onMediaLongClicked(
                    it, shelf?.extensionId, shelf?.media,
                    shelf?.context, shelf?.tabId, bindingAdapterPosition
                )
                true
            }
        }

        override fun bind(index: Int, list: List<ShelfType.Media>) {
            val shelf = list[index]
            this.shelf = shelf
            binding.bind(shelf.media)
            dimIfUnplayable(shelf.media, binding.coverContainer.root, binding.title, binding.subtitle)
        }

        override fun onCurrentChanged(current: PlayerState.Current?) {
            val isPlaying = current.isPlaying(shelf?.media?.id)
            binding.coverContainer.isPlaying.isVisible = isPlaying
            (binding.coverContainer.isPlaying.icon as Animatable).start()
        }
    }

    class ThreeTracks(
        parent: ViewGroup,
        listener: FeedClickListener,
        getAllTracks: () -> List<Track>,
        binding: ItemShelfListsThreeTracksBinding = ItemShelfListsThreeTracksBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        ),
    ) : ShelfViewHolder<ShelfType.ThreeTracks>(binding.root) {
        private val bindings = listOf(binding.track1, binding.track2, binding.track3)

        private var shelf: ShelfType.ThreeTracks? = null

        init {
            bindings.forEachIndexed { index, binding ->
                binding.coverContainer.cover.clipToOutline = true
                // ⚠⚠ [CORRECTED 2026-10-25] THIS WAS A `val` COMPUTED RIGHT
                // HERE, IN init, WHERE `shelf` IS STILL NULL - so `shelf?.number` was always null,
                // the whole expression collapsed to the elvis branch `index` (0..2 within this row
                // of three), and every shelf after the first reported positions 0, 1, 2 to
                // onMediaLongClicked instead of its real ones. A FUNCTION, NOT A val: it has to be
                // evaluated at EVENT time, once bind has assigned `shelf`. Same expression the tap
                // path's `pos` uses - the two were out of step, and this is the long-press half of
                // the fault fixed there.
                fun actualIndex() = shelf?.number?.let { it * 3 + index } ?: index
                binding.root.setOnClickListener { view ->
                    val tracks = getAllTracks()
                    val pos = shelf?.number?.let { it * 3 + index } ?: index
                    // ⚠⚠ [CORRECTED 2026-10-25] THIS READ `getOrNull(index)`, AND
                    // index IS 0..2 WITHIN THIS PAGE OF THREE while `tracks` is the FLAT list this
                    // carousel was chunked FROM - HorizontalListViewHolder sets
                    // `adapter.tracks = feed.shelf.list.filterIsInstance<Track>()` and pages it with
                    // `list.chunked(3)`. So for every page after the first (number > 0) the
                    // playability gate tested PAGE ONE's tracks and handed the wrong track to the
                    // unplayable branch. `pos` is the index the sibling onTracksClicked call and
                    // bind's display number both already use - the two were simply out of step.
                    // [A first draft of this note said `tracks` was the WHOLE FEED's list. It is one
                    // shelf's. The fix is unchanged either way - pos indexes whatever list was
                    // chunked - but the reason is this, not that.]
                    // Visible only once something sets isPlayable, which is why it surfaced now
                    // rather than when it was written.
                    val track = tracks.getOrNull(pos)
                    if (track?.isPlayable != Track.Playable.Yes) listener.onUnplayableClicked(track)
                    else listener.onTracksClicked(
                        view, shelf?.extensionId, shelf?.context, tracks, pos
                    )
                }
                binding.root.setOnLongClickListener {
                    listener.onMediaLongClicked(
                        it, shelf?.extensionId, shelf?.tracks?.toList()?.getOrNull(index),
                        shelf?.context, shelf?.tabId, actualIndex()
                    )
                    true
                }
                binding.more.setOnClickListener {
                    listener.onMediaLongClicked(
                        it, shelf?.extensionId, shelf?.tracks?.toList()?.getOrNull(index),
                        shelf?.context, shelf?.tabId, actualIndex()
                    )
                }
            }
        }

        override fun bind(index: Int, list: List<ShelfType.ThreeTracks>) {
            val shelf = list[index]
            this.shelf = shelf
            val number = shelf.number
            val tracks = shelf.tracks.toList()
            bindings.forEachIndexed { index, view ->
                val track = tracks.getOrNull(index)
                view.root.isVisible = track != null
                if (track == null) return@forEachIndexed
                view.bind(track, number?.let { it * 3 + index })
                dimIfUnplayable(track, view.coverContainer.root, view.title, view.subtitle)
            }
        }

        override fun onCurrentChanged(current: PlayerState.Current?) {
            val tracks = shelf?.tracks?.toList() ?: return
            bindings.forEachIndexed { index, binding ->
                val track = tracks.getOrNull(index) ?: return@forEachIndexed
                val isPlaying = current.isPlaying(track.id)
                binding.coverContainer.isPlaying.isVisible = isPlaying
                (binding.coverContainer.isPlaying.icon as Animatable).start()
            }
        }
    }

    companion object {
        fun ItemShelfListsMediaBinding.bind(item: EchoMediaItem) {
            val gravity = if (item is Artist) Gravity.CENTER else Gravity.NO_GRAVITY
            title.text = item.title
            title.gravity = gravity
            val sub = item.subtitle(root.context)
            subtitle.text = sub
            subtitle.gravity = gravity
            subtitle.isVisible = !sub.isNullOrBlank()
            coverContainer.run { applyCover(item, cover, listBg1, listBg2, icon) }
        }
    }
}