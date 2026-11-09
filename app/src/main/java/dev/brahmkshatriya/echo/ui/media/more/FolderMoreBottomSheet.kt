package dev.brahmkshatriya.echo.ui.media.more

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.common.clients.LikeClient
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Message
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.databinding.DialogMediaMoreBinding
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.getExtension
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.getIf
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.isClient
import dev.brahmkshatriya.echo.extensions.builtin.offline.OfflineExtension
import dev.brahmkshatriya.echo.extensions.builtin.unified.UnifiedExtension
import dev.brahmkshatriya.echo.playback.MediaItemUtils
import dev.brahmkshatriya.echo.playback.MediaItemUtils.droppingUnplayable
import dev.brahmkshatriya.echo.ui.common.GridAdapter
import dev.brahmkshatriya.echo.ui.feed.FeedPageSlot
import dev.brahmkshatriya.echo.ui.common.GridAdapter.Companion.configureGridLayout
import dev.brahmkshatriya.echo.ui.media.MediaDetailsViewModel
import dev.brahmkshatriya.echo.ui.media.more.MoreButton.Companion.button
import dev.brahmkshatriya.echo.ui.player.PlayerViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.androidx.viewmodel.ext.android.activityViewModel

/**
 * [2026-11-08] The long-press menu of a marked folder row (FeedSort's isMarkedFolder): Play, Shuffle, Play next, Add to queue,
 * Like / Unlike.
 *
 * ⚠️ NOT MediaMoreBottomSheet. That sheet builds a MediaViewModel, whose init runs Cached.loadMedia on its item, so a folder
 * would be loaded as an album by the extension and a MediaState cached under the folder's id. Nothing here goes near
 * MediaViewModel, MediaDetailsViewModel's instance side or Cached.
 *
 * TRACKS: the folder's declared customTrackList when it has one (Offline's is recursive; OpenGonic declares one), otherwise
 * every page of its first tab. No cap. Every action plays under the folder's "Playing from" label
 * (MediaItemUtils.feedPageLabel), as Play on the folder's own page does.
 *
 * The actions run in PlayerViewModel's scope, not the sheet's, because the sheet closes on the tap and the folder may
 * still be loading.
 */
class FolderMoreBottomSheet : BottomSheetDialogFragment(R.layout.dialog_media_more) {

    // The Feed cannot be parcelled, so the row travels in an activity-scoped holder, as MediaMoreBottomSheet.Args does.
    class Args : ViewModel() {
        var category: Shelf.Category? = null
    }

    companion object {
        fun show(host: Fragment, manager: FragmentManager, extensionId: String, category: Shelf.Category) {
            val argsVm by host.activityViewModels<Args>()
            argsVm.category = category
            FolderMoreBottomSheet().apply {
                arguments = Bundle().apply { putString("extensionId", extensionId) }
            }.show(manager, null)
        }

        private suspend fun Shelf.Category.folderTracks(): List<Track> {
            val feed = feed ?: return listOf()
            val data = feed.getPagedData(feed.notSortTabs.firstOrNull())
            data.buttons?.customTrackList?.let { return it }
            return data.pagedData.loadAll().flatMap { shelf ->
                when (shelf) {
                    is Shelf.Item -> listOfNotNull(shelf.media as? Track)
                    is Shelf.Lists.Tracks -> shelf.list
                    else -> listOf()
                }
            }
        }
    }

    private val argsVm by activityViewModels<Args>()
    private lateinit var category: Shelf.Category
    private val extensionId by lazy { requireArguments().getString("extensionId")!! }
    private val playerViewModel by activityViewModel<PlayerViewModel>()

    private val label by lazy { MediaItemUtils.feedPageLabel(category.id, category.title) }

    // [2026-11-08] The label each action puts on the queue: it also makes this folder the live page a "Playing from" tap
    // reopens (FeedPageSlot), so it is built per action and never for the header, which opening the sheet alone shows.
    // extensionId is the row's, the same one CategoryViewHolder opens the folder's page with.
    private fun queueLabel() = FeedPageSlot.label(extensionId, category.id, category.title, category.feed)

    // What LikeClient is given: the folder as an Album, carrying the row's extras so the extension can tell it is a folder.
    private val folderAlbum by lazy {
        Album(
            id = category.id, title = category.title, cover = category.image,
            subtitle = category.subtitle, extras = category.extras, isLikeable = true,
        )
    }

    // null = no Like row (not supported, or the check failed); Pending while isItemLiked runs.
    private sealed interface LikeRow {
        data object Pending : LikeRow
        data class Known(val liked: Boolean) : LikeRow
    }

    private var likeRow: LikeRow? = null

    private val headerAdapter by lazy { MoreHeaderAdapter {} }
    private val actionAdapter by lazy { MoreButtonAdapter() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Null only on process-death restoration, as in MediaMoreBottomSheet.
        category = argsVm.category ?: run { dismissAllowingStateLoss(); return }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        if (!::category.isInitialized) return
        val binding = DialogMediaMoreBinding.bind(view)
        // The label as the header: a Radio shows no type line ("Album" would be wrong for a folder).
        // [2026-11-08] With no cover (or one that fails), the feed's own placeholder for a list (MediaViewHolder.placeHolder's
        // Playlist arm), tinted from the theme. Set here because the header's default follows the item, and this item is a Radio
        // label, whose arm is a radio icon. A folder with a cover still shows it. Phone and TV alike, as the default now is.
        headerAdapter.placeholder = R.drawable.art_library_music
        headerAdapter.item = label.copy(cover = category.image)
        configureGridLayout(binding.root, GridAdapter.Concat(headerAdapter, actionAdapter))
        submitButtons()
        viewLifecycleOwner.lifecycleScope.launch { checkLiked() }
    }

    // No Like row for Unified (its likeItem and isItemLiked accept songs only) or for Offline (its likeItem reads the
    // item's id as a MediaStore song id, so a folder named with digits would like an unrelated song). Otherwise the row
    // shows only when the extension is a LikeClient and isItemLiked answers; a failure hides it, with no error.
    private suspend fun checkLiked() {
        if (extensionId == UnifiedExtension.UNIFIED_ID || extensionId == OfflineExtension.metadata.id) return
        val extension = playerViewModel.extensions.music.getExtension(extensionId) ?: return
        if (!extension.isClient<LikeClient>()) return
        likeRow = LikeRow.Pending
        submitButtons()
        val liked = withContext(Dispatchers.IO) {
            extension.getIf<LikeClient, Boolean> { isItemLiked(folderAlbum) }
        }.getOrNull()
        likeRow = liked?.let { LikeRow.Known(it) }
        submitButtons()
    }

    private fun submitButtons() = actionAdapter.submitList(listOfNotNull(
        button("play", R.string.play, R.drawable.ic_play) { play(false) },
        button("shuffle_play", R.string.shuffle, R.drawable.ic_shuffle) { play(true) },
        if (playerViewModel.queue.isNotEmpty())
            button("next", R.string.add_to_next, R.drawable.ic_playlist_play) {
                withTracks { playerViewModel.addToNext(extensionId, queueLabel(), true, tracks = it) }
            }
        else null,
        if (playerViewModel.queue.size > 1)
            button("queue", R.string.add_to_queue, R.drawable.ic_playlist_add) {
                withTracks { playerViewModel.addToQueue(extensionId, queueLabel(), true, tracks = it) }
            }
        else null,
        when (val row = likeRow) {
            null -> null
            LikeRow.Pending -> MoreButton("like", getString(R.string.like), R.drawable.ic_heart_outline_40dp, enabled = false)
            is LikeRow.Known -> button(
                "like", if (row.liked) R.string.unlike else R.string.like,
                if (row.liked) R.drawable.ic_heart_filled_40dp else R.drawable.ic_heart_outline_40dp
            ) { like(!row.liked) }
        },
    ))

    // The list path of FeedClickListener.onPlayClicked, with the folder's label as the context.
    private fun play(shuffle: Boolean) {
        val app = playerViewModel.app
        val title = category.title
        playerViewModel.viewModelScope.launch {
            app.messageFlow.emit(Message(
                app.context.getString(if (shuffle) R.string.shuffling_x else R.string.playing_x, title)
            ))
        }
        withTracks { tracks ->
            val playable = tracks.droppingUnplayable()
            if (playable.isEmpty()) {
                app.messageFlow.emit(Message(app.context.getString(R.string.list_is_empty)))
                return@withTracks
            }
            playerViewModel.setQueue(extensionId, playable, 0, queueLabel(), shuffleList = shuffle)
            if (!shuffle) playerViewModel.setShuffle(false, true)
            playerViewModel.setPlaying(true)
        }
    }

    // Loads the folder's tracks off the main thread, then runs [block] on it. A load error goes to throwFlow, as any
    // feed load error does.
    private fun withTracks(block: suspend (List<Track>) -> Unit) {
        val app = playerViewModel.app
        val category = category
        playerViewModel.viewModelScope.launch {
            val tracks = runCatching { withContext(Dispatchers.IO) { category.folderTracks() } }.getOrElse {
                if (it is CancellationException) throw it
                app.throwFlow.emit(it)
                return@launch
            }
            block(tracks)
        }
    }

    private fun like(like: Boolean) {
        val app = playerViewModel.app
        val extensions = playerViewModel.extensions
        val extensionId = extensionId
        val item = folderAlbum
        playerViewModel.viewModelScope.launch {
            MediaDetailsViewModel.like(app, extensions.music.getExtension(extensionId), item, like)
        }
    }
}
