package dev.brahmkshatriya.echo.ui.media.more

import android.graphics.drawable.Animatable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.ui.feed.viewholders.MediaViewHolder.Companion.placeHolder
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Radio
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.databinding.ItemMoreHeaderBinding
import dev.brahmkshatriya.echo.playback.PlayerState
import dev.brahmkshatriya.echo.playback.PlayerState.Current.Companion.isPlaying
import dev.brahmkshatriya.echo.ui.common.GridAdapter
import dev.brahmkshatriya.echo.ui.media.MediaHeaderAdapter.Companion.typeInt
import dev.brahmkshatriya.echo.utils.image.ImageUtils.loadInto
import dev.brahmkshatriya.echo.utils.ui.scrolling.ScrollAnimViewHolder

class MoreHeaderAdapter(
    private val onItemClicked: () -> Unit
) : RecyclerView.Adapter<MoreHeaderAdapter.ViewHolder>(), GridAdapter {
    override val adapter = this
    override fun getSpanSize(position: Int, width: Int, count: Int) = count
    override fun getItemCount() = 1
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val holder = ViewHolder(parent)
        holder.binding.cover.setOnClickListener { onItemClicked() }
        return holder
    }

    var item: EchoMediaItem? = null
        set(value) {
            field = value
            notifyItemChanged(0)
        }

    // [2026-11-08] What shows with no cover, or when it fails to load. Null = the item's own placeholder, the feed's mapping
    // (MediaViewHolder.placeHolder: art_music, art_album, art_library_music, ...), all tinted ?attr/colorControlNormal. Until
    // now every sheet showed ic_music, an untinted black vector, dark on a dark sheet. FolderMoreBottomSheet sets its own,
    // because its item is a Radio label and the mapping's Radio arm is a radio icon.
    var placeholder: Int? = null

    // [2026-11-09] The second line, when the sheet knows better than the item's type. Null = the type line bind builds.
    // FolderMoreBottomSheet sets it, because its item is a Radio label, whose line is empty.
    var subtitle: String? = null

    private var viewHolder: ViewHolder? = null
    override fun onBindViewHolder(holder: ViewHolder, position: Int) = with(holder.binding) {
        viewHolder = holder
        val item = item
        holder.bind(item, placeholder, subtitle)
        holder.onCurrentChanged(item, current)
    }

    class ViewHolder(
        parent: ViewGroup,
        val binding: ItemMoreHeaderBinding = ItemMoreHeaderBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
    ) : ScrollAnimViewHolder(binding.root) {

        fun bind(item: EchoMediaItem?, placeholder: Int? = null, subtitle: String? = null) = with(binding) {
            if (item == null) return@with
            title.text = item.title
            val ctx = root.context
            type.text = subtitle ?: when (item) {
                is Artist -> "Artist"
                is Track -> {
                    val artist = item.artists.firstOrNull()?.name
                    val base = ctx.getString(R.string.track)
                    if (artist != null) "$base · $artist" else base
                }
                is Album -> {
                    val artist = item.artists.firstOrNull()?.name
                    val base = ctx.getString(R.string.album)
                    if (artist != null) "$base · $artist" else base
                }
                is Playlist -> {
                    val creator = item.artists.firstOrNull()?.name
                    val base = ctx.getString(R.string.playlist)
                    if (creator != null) "$base · $creator" else base
                }
                is Radio -> ""
            }
            // [2026-11-09] An empty line still takes a line's height, so a lone title sat above the cover's centre. Gone
            // instead, the text block is just the title and center_vertical centres it against the cover.
            type.isVisible = type.text.isNotEmpty()
            val fallback = placeholder ?: item.placeHolder
            item.cover.loadInto(cover, fallback, fallback)
        }

        fun onCurrentChanged(item: EchoMediaItem?, current: PlayerState.Current?) {
            binding.isPlaying.run {
                val isPlaying = current.isPlaying(item?.id)
                isVisible = isPlaying
                (icon as Animatable).start()
            }
        }
    }

    var current: PlayerState.Current? = null
    fun onCurrentChanged(current: PlayerState.Current?) {
        this.current = current
        viewHolder?.onCurrentChanged(item, current)
    }
}