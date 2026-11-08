package dev.brahmkshatriya.echo.ui.feed.viewholders

import dev.brahmkshatriya.echo.utils.ui.UiUtils.setTvRowButtons
import android.content.Context
import android.graphics.drawable.Animatable
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ImageView
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Radio
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.databinding.ItemShelfMediaBinding
import dev.brahmkshatriya.echo.playback.PlayerState
import dev.brahmkshatriya.echo.playback.PlayerState.Current.Companion.isPlaying
import dev.brahmkshatriya.echo.ui.feed.FeedClickListener
import dev.brahmkshatriya.echo.ui.feed.FeedType
import dev.brahmkshatriya.echo.ui.media.MediaHeaderAdapter.Companion.playableString
import dev.brahmkshatriya.echo.utils.image.ImageUtils.loadFittedInto
import dev.brahmkshatriya.echo.utils.image.ImageUtils.loadInto
import dev.brahmkshatriya.echo.utils.ui.UiUtils.dimIfUnplayable

class MediaViewHolder(
    parent: ViewGroup,
    listener: FeedClickListener,
    getAllTracks: (FeedType) -> Pair<List<Track>, Int>,
    private val binding: ItemShelfMediaBinding = ItemShelfMediaBinding.inflate(
        LayoutInflater.from(parent.context), parent, false
    ),
) : FeedViewHolder<FeedType.Media>(binding.root) {
    var feed: FeedType.Media? = null

    init {
        binding.coverContainer.cover.clipToOutline = true
        // TV: Right moves from the row into Play / more - see UiUtils.setTvRowButtons.
        binding.root.setTvRowButtons(listOf(binding.play, binding.more))
        binding.root.setOnClickListener {
            when (val item = feed?.item) {
                is Track -> {
                    if (item.isPlayable != Track.Playable.Yes) {
                        listener.onUnplayableClicked(item)
                        return@setOnClickListener
                    }
                    val (tracks, pos) = getAllTracks(feed!!)
                    listener.onTracksClicked(it, feed?.extensionId, feed?.context, tracks, pos)
                }

                else -> listener.onMediaClicked(it, feed?.extensionId, item, feed?.context)
            }
        }
        binding.root.setOnLongClickListener {
            listener.onMediaLongClicked(
                it, feed?.extensionId, feed?.item,
                feed?.context, feed?.tabId, bindingAdapterPosition
            )
            true
        }

        binding.more.setOnClickListener {
            listener.onMediaLongClicked(
                it, feed?.extensionId, feed?.item,
                feed?.context, feed?.tabId, bindingAdapterPosition
            )
        }
        binding.play.setOnClickListener {
            listener.onPlayClicked(
                it, feed?.extensionId, feed?.item, null, false
            )
        }
    }

    override fun bind(feed: FeedType.Media) {
        this.feed = feed
        binding.bind(feed.item, feed.number?.toInt(), showNotPlayable = false)
        // NOT inside ItemShelfMediaBinding.bind, even though that would cover this holder and
        // ThreeTracks in one line: ui/playlist/save/MediaItemAdapter calls that same extension, and
        // the save-to-playlist picker is not a surface this change was scoped to.
        dimIfUnplayable(feed.item, binding.coverContainer.root, binding.title, binding.subtitle)
    }

    override fun canBeSwiped() = feed?.item is Track
    override fun onSwipe() = feed

    override fun onCurrentChanged(current: PlayerState.Current?) {
        val isPlaying = current.isPlaying(feed?.item?.id)
        binding.coverContainer.isPlaying.isVisible = isPlaying
        (binding.coverContainer.isPlaying.icon as Animatable).start()
    }

    companion object {
        fun ItemShelfMediaBinding.bind(
            item: EchoMediaItem, index: Int? = null, showNotPlayable: Boolean = true
        ) {
            title.text = if (index == null) item.title
            else root.context.getString(R.string.n_dot_x, index + 1, item.title)
            val subtitleText = item.subtitle(root.context, showNotPlayable)
            subtitle.text = subtitleText
            subtitle.isVisible = !subtitleText.isNullOrBlank()
            coverContainer.run {
                applyCover(item, cover, listBg1, listBg2, icon)
                isPlaying.setBackgroundResource(
                    if (item is Artist) R.drawable.rounded_rectangle_cover_profile
                    else R.drawable.rounded_rectangle_cover
                )
            }
            play.isVisible = item !is Track
        }

        val EchoMediaItem.placeHolder
            get() = when (this) {
                is Track -> R.drawable.art_music
                is Artist -> R.drawable.art_artist
                is Album -> R.drawable.art_album
                is Playlist -> R.drawable.art_library_music
                is Radio -> R.drawable.art_sensors
            }

        val EchoMediaItem.icon
            get() = when (this) {
                is Track -> R.drawable.ic_music
                is Artist -> R.drawable.ic_artist
                is Album -> R.drawable.ic_album
                is Playlist -> R.drawable.ic_library_music
                is Radio -> R.drawable.ic_sensors
            }

        private val VIDEO_TYPES = setOf(Track.Type.Video, Track.Type.HorizontalVideo, Track.Type.VideoSong)

        // ?itemCorner (8dp phone, 12dp TV), the radius of the box's own background, rounded_rectangle_cover.
        private fun View.itemCornerPx(): Float {
            val typedValue = TypedValue()
            context.theme.resolveAttribute(R.attr.itemCorner, typedValue, true)
            return TypedValue.complexToDimension(typedValue.data, resources.displayMetrics)
        }

        fun applyCover(
            item: EchoMediaItem,
            cover: ImageView,
            listBg1: View,
            listBg2: View,
            icon: ImageView,
        ) {
            icon.isVisible = when (item) {
                is Track, is Artist, is Album -> false
                else -> true
            }
            icon.setImageResource(item.icon)
            cover.setBackgroundResource(
                if (item is Artist) R.drawable.rounded_rectangle_cover_profile
                else R.drawable.rounded_rectangle_cover
            )
            val bgVisible = item is EchoMediaItem.Lists
            listBg1.isVisible = bgVisible
            listBg2.isVisible = bgVisible
            // [2026-11-08] EVERY NON-VIDEO COVER IN A SQUARE BOX. Until now only artists were square and everything else took
            // the image's shape (adjustViewBounds in the cover layouts), so a tall cover pushed its text down past the
            // carousel row, whose height assumes a square cover (HorizontalListViewHolder.fixedRowHeightPx). Non-square
            // covers are fitted inside with rounded corners (FitSquareCoverTransformation); square ones look as before.
            // Videos keep their real shape and the plain load: in a carousel a video thumbnail comes through here too.
            // Artists keep the plain load and the round crop of their background.
            val isVideo = item is Track && item.type in VIDEO_TYPES
            cover.updateLayoutParams {
                height = if (isVideo) WRAP_CONTENT else width
            }
            if (item is Artist || isVideo) item.cover.loadInto(cover, item.placeHolder)
            else item.cover.loadFittedInto(cover, item.placeHolder, cover.itemCornerPx())
        }

        // showNotPlayable = false keeps the artist line on a Playable.No track (2026-10-30): the rows that
        // pass it are the UiUtils.dimIfUnplayable sites, where the dimming already marks the row and the tap
        // message (FeedClickListener.onUnplayableClicked) says why. Undimmed callers keep the default.
        // RegionLocked and Unreleased still replace it everywhere ("Releases on <date>" is information).
        fun EchoMediaItem.subtitle(context: Context, showNotPlayable: Boolean = true) = when (this) {
            is Track -> (if (!showNotPlayable && isPlayable is Track.Playable.No) null
            else playableString(context)) ?: subtitleWithE
            else -> subtitleWithE
        }
    }
}