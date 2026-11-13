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
import dev.brahmkshatriya.echo.extensions.ExtensionLoader
import dev.brahmkshatriya.echo.ui.feed.hasTracksOnTap
import dev.brahmkshatriya.echo.ui.feed.isMarkedFolder
import dev.brahmkshatriya.echo.ui.feed.loadTracksOnTap
import dev.brahmkshatriya.echo.ui.feed.QueueSampler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.androidx.viewmodel.ext.android.activityViewModel
import dev.brahmkshatriya.echo.utils.BoundedLoadAll.forEachPageBounded

/**
 * [2026-11-08] The long-press menu of a marked folder row (FeedSort's isMarkedFolder): Play, Shuffle, Play next, Add to queue,
 * Like / Unlike.
 * [2026-11-11] Also a row with only the tracks-on-tap marker (TracksOnTap, OpenGonic's "All songs"): the same menu without
 * Like, and without the header's "Folder" word, as it is not a folder.
 *
 * ⚠️ NOT MediaMoreBottomSheet. That sheet builds a MediaViewModel, whose init runs Cached.loadMedia on its item, so a folder
 * would be loaded as an album by the extension and a MediaState cached under the folder's id. Nothing here goes near
 * MediaViewModel, MediaDetailsViewModel's instance side or Cached.
 *
 * TRACKS: for a tracks-on-tap row, the extension's whole list for it (loadTracksOnTap: every song under the folder). Otherwise
 * the folder's declared customTrackList when it has one (Offline's is recursive), otherwise every page of its first tab. No
 * cap. Every action plays under the folder's "Playing from" label (MediaItemUtils.feedPageLabel), as Play on the folder's own
 * page does.
 *
 * The actions run in PlayerViewModel's scope, not the sheet's, because the sheet closes on the tap and the folder may
 * still be loading. [2026-11-11] Through PlayerViewModel.loadThenQueue: a newer play or queue action cancels the load, so a
 * slow folder can no longer replace a queue chosen since, and a load past a second shows "Loading…" with Cancel.
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

        // [2026-11-13] PAGE BY PAGE (Crashlytics 63ec9068): [onPage] gets each page's tracks and returns false to stop reading,
        // so a load-on-tap folder sampled to 2,000 is never held whole. A folder's own songs and a custom track list arrive
        // the same way and are collected whole by the caller, as before.
        private suspend fun Shelf.Category.folderTracks(
            extensions: ExtensionLoader, extensionId: String, onPage: suspend (List<Track>) -> Boolean,
        ) {
            if (hasTracksOnTap()) return loadTracksOnTap(extensions, extensionId, id, title, extras, onPage)
            val feed = feed ?: return
            val data = feed.getPagedData(feed.notSortTabs.firstOrNull())
            data.buttons?.customTrackList?.let { onPage(it); return }
            data.pagedData.forEachPageBounded { shelves ->
                onPage(shelves.flatMap { shelf ->
                    when (shelf) {
                        is Shelf.Item -> listOfNotNull(shelf.media as? Track)
                        is Shelf.Lists.Tracks -> shelf.list
                        else -> listOf()
                    }
                })
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
        // [2026-11-09] "Folder", plus the row's own subtitle when it has one (Offline's "N items"; OpenGonic's rows have
        // none). Nothing is fetched for a count: OpenGonic's songs exist only once its folder page loads.
        val folder = getString(R.string.folder)
        val rowSubtitle = category.subtitle?.takeIf { it.isNotBlank() }
        headerAdapter.subtitle = if (!category.isMarkedFolder()) rowSubtitle
        else rowSubtitle?.let { "$folder · $it" } ?: folder
        headerAdapter.item = label.copy(cover = category.image)
        configureGridLayout(binding.root, GridAdapter.Concat(headerAdapter, actionAdapter))
        submitButtons()
        viewLifecycleOwner.lifecycleScope.launch { checkLiked() }
    }

    // No Like row for Unified (its likeItem and isItemLiked accept songs only) or for Offline (its likeItem reads the
    // item's id as a MediaStore song id, so a folder named with digits would like an unrelated song). Otherwise the row
    // shows only when the extension is a LikeClient and isItemLiked answers; a failure hides it, with no error.
    private suspend fun checkLiked() {
        // [2026-11-11] No Like for a tracks-on-tap row that is not a folder ("All songs"): there is nothing to like.
        if (!category.isMarkedFolder()) return
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
        val message = app.context.getString(if (shuffle) R.string.shuffling_x else R.string.playing_x, title)
        // [2026-11-12] Through PlayerViewModel.playTracksOnTap: capped at 2,000 by a fair sample for a load-on-tap folder only
        // (TracksOnTap.QueueSampler); a folder's own songs are queued whole, as before.
        // ONE MESSAGE (the user's decision): a load-on-tap folder's "Shuffling <title>" moves to after the load and is replaced
        // by the sample message when the list was sampled; any other folder keeps it at the tap, as before.
        val extensions = playerViewModel.extensions
        val extensionId = extensionId
        val category = category
        val capped = category.hasTracksOnTap()
        if (!capped) playerViewModel.viewModelScope.launch { app.messageFlow.emit(Message(message)) }
        playerViewModel.playTracksOnTap(
            title, extensionId, queueLabel(), shuffle, capped = capped, unsampledMessage = message.takeIf { capped },
        ) { onPage ->
            category.folderTracks(extensions, extensionId, onPage)
        }
    }

    // [2026-11-12] Play next and Add to queue from a load-on-tap folder add at most the first 2,000 in folder order (the same cap);
    // a folder's own songs are added whole, as before.
    // [2026-11-13] Taken while reading (TracksOnTap.QueueSampler, Play's rule: stop at 2,000), not cut from a whole list, which
    // was capIfOnTap. Unplayable songs are not dropped here, as before.

    // Loads the folder's tracks off the main thread, then runs [block] on it (PlayerViewModel.loadThenQueue: a load error goes to
    // throwFlow, as any feed load error does, and a newer play or queue action cancels it).
    private fun withTracks(block: suspend (List<Track>) -> Unit) {
        val extensions = playerViewModel.extensions
        val extensionId = extensionId
        val category = category
        playerViewModel.loadThenQueue(category.title, {
            if (category.hasTracksOnTap()) {
                val sampler = QueueSampler(shuffle = false, dropUnplayable = false)
                category.folderTracks(extensions, extensionId) { sampler.offer(it) }
                sampler.result().tracks
            } else {
                val all = mutableListOf<Track>()
                category.folderTracks(extensions, extensionId) { all.addAll(it); true }
                all
            }
        }, block)
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
