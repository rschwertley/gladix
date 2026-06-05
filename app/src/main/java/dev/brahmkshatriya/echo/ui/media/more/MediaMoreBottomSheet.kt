package dev.brahmkshatriya.echo.ui.media.more

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.ViewModel
import androidx.paging.LoadState
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.FollowClient
import dev.brahmkshatriya.echo.common.clients.HideClient
import dev.brahmkshatriya.echo.common.clients.LikeClient
import dev.brahmkshatriya.echo.common.clients.PlaylistEditClient
import dev.brahmkshatriya.echo.common.clients.RadioClient
import dev.brahmkshatriya.echo.common.clients.SaveClient
import dev.brahmkshatriya.echo.common.clients.ShareClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Radio
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.history.HistoryRepository
import dev.brahmkshatriya.echo.databinding.DialogMediaMoreBinding
import dev.brahmkshatriya.echo.download.Downloader
import dev.brahmkshatriya.echo.extensions.MediaState
import dev.brahmkshatriya.echo.extensions.builtin.offline.OfflineExtension
import dev.brahmkshatriya.echo.extensions.builtin.unified.UnifiedExtension.Companion.EXTENSION_ID
import dev.brahmkshatriya.echo.ui.common.FragmentUtils.openFragment
import dev.brahmkshatriya.echo.ui.common.GridAdapter
import dev.brahmkshatriya.echo.ui.common.GridAdapter.Companion.configureGridLayout
import dev.brahmkshatriya.echo.ui.download.DownloadViewModel
import dev.brahmkshatriya.echo.ui.feed.FeedLoadingAdapter
import dev.brahmkshatriya.echo.ui.feed.FeedLoadingAdapter.Companion.createListener
import dev.brahmkshatriya.echo.ui.feed.viewholders.MediaViewHolder.Companion.icon
import dev.brahmkshatriya.echo.ui.media.MediaFragment
import dev.brahmkshatriya.echo.ui.media.MediaViewModel
import dev.brahmkshatriya.echo.ui.media.more.MoreButton.Companion.button
import dev.brahmkshatriya.echo.ui.player.PlayerViewModel
import dev.brahmkshatriya.echo.ui.player.audiofx.AudioEffectsBottomSheet
import dev.brahmkshatriya.echo.ui.player.more.lyrics.LyricsItemAdapter
import dev.brahmkshatriya.echo.ui.player.quality.QualitySelectionBottomSheet
import dev.brahmkshatriya.echo.ui.player.sleep.SleepTimerBottomSheet
import dev.brahmkshatriya.echo.ui.playlist.delete.DeletePlaylistBottomSheet
import dev.brahmkshatriya.echo.ui.playlist.edit.EditPlaylistBottomSheet
import dev.brahmkshatriya.echo.ui.playlist.edit.EditPlaylistFragment
import dev.brahmkshatriya.echo.ui.playlist.save.SaveToPlaylistBottomSheet
import dev.brahmkshatriya.echo.utils.ContextUtils.observe
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.activityViewModel
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.koin.core.parameter.parametersOf

class MediaMoreBottomSheet : BottomSheetDialogFragment(R.layout.dialog_media_more) {

    class Args : ViewModel() {
        var item: EchoMediaItem? = null
        var context: EchoMediaItem? = null
    }

    companion object {
        fun show(
            host: Fragment,
            manager: FragmentManager = host.parentFragmentManager,
            contId: Int,
            extensionId: String,
            item: EchoMediaItem,
            loaded: Boolean,
            fromPlayer: Boolean = false,
            context: EchoMediaItem? = null,
            tabId: String? = null,
            pos: Int? = null,
            fromHistory: Boolean = false,
        ) {
            val argsVm by host.activityViewModels<Args>()
            argsVm.item = item
            argsVm.context = context
            MediaMoreBottomSheet().apply {
                arguments = Bundle().apply {
                    putInt("contId", contId)
                    putString("extensionId", extensionId)
                    putBoolean("loaded", loaded)
                    putBoolean("fromPlayer", fromPlayer)
                    putString("tabId", tabId)
                    putInt("pos", pos ?: -1)
                    putBoolean("fromHistory", fromHistory)
                }
            }.show(manager, null)
        }
    }

    private val argsVm by activityViewModels<Args>()
    private lateinit var item: EchoMediaItem
    private var itemContext: EchoMediaItem? = null

    private val args by lazy { requireArguments() }
    private val contId by lazy { args.getInt("contId", -1).takeIf { it != -1 }!! }
    private val extensionId by lazy { args.getString("extensionId")!! }
    private val loaded by lazy { args.getBoolean("loaded") }
    private val tabId by lazy { args.getString("tabId") }
    private val pos by lazy { args.getInt("pos") }
    private val fromPlayer by lazy { args.getBoolean("fromPlayer") }
    private val fromHistory by lazy { args.getBoolean("fromHistory") }
    private val delete by lazy { args.getBoolean("delete", false) }
    private val historyRepository by inject<HistoryRepository>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // argsVm.item is null only on process-death restoration; the sheet can't recover gracefully.
        item = argsVm.item ?: run { dismissAllowingStateLoss(); return }
        itemContext = argsVm.context
    }

    private val vm by viewModel<MediaViewModel> {
        parametersOf(false, extensionId, item, loaded, delete)
    }
    private val playerViewModel by activityViewModel<PlayerViewModel>()

    private val actionAdapter by lazy { MoreButtonAdapter() }
    private val headerAdapter by lazy {
        MoreHeaderAdapter {
            openItemFragment(extensionId, item, loaded)
            dismiss()
        }
    }

    private val loadingAdapter by lazy {
        FeedLoadingAdapter(createListener { vm.refresh() }) {
            val holder = LyricsItemAdapter.Loading(it)
            holder
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = DialogMediaMoreBinding.bind(view)
        observe(playerViewModel.playerState.current) {
            headerAdapter.onCurrentChanged(it)
        }
        val actionFlow =
            combine(vm.downloadsFlow, vm.uiResultFlow, vm.extensionFlow) { _, _, _ -> }
        observe(actionFlow) {
            val client = vm.extensionFlow.value?.instance?.value()?.getOrNull()
            val result = vm.uiResultFlow.value?.getOrNull()
            val downloads = vm.downloadsFlow.value.filter { it.download.finalFile != null }
            val loaded = if (result != null) true else loaded
            if (vm.uiResultFlow.value == null) {
                actionAdapter.submitList(getButtons(client, null, loaded, downloads))
                headerAdapter.item = item
                return@observe
            }
            val list = getButtons(client, result, loaded, downloads)
            actionAdapter.submitList(list)
            headerAdapter.item = result?.item ?: item
        }
        observe(vm.itemResultFlow) { result ->
            loadingAdapter.loadState = result?.map { LoadState.NotLoading(false) }?.getOrElse {
                LoadState.Error(it)
            } ?: LoadState.Loading
        }
        configureGridLayout(
            binding.root,
            GridAdapter.Concat(
                headerAdapter,
                actionAdapter,
                loadingAdapter
            )
        )
    }

    private fun placeholderButton(id: String, title: Int, icon: Int) =
        MoreButton(id, getString(title), icon, enabled = false)

    private fun getButtons(
        client: ExtensionClient?,
        state: MediaState.Loaded<*>?,
        loaded: Boolean,
        downloads: List<Downloader.Info>
    ) = getPlayButtons(client, state?.item ?: item, loaded) +
            getActionButtons(client, state) +
            getPlaylistEditButtons(client, state, loaded) +
            getDownloadButtons(client, state, downloads) +
            getItemButtons(state?.item ?: item) +
            getRadioButton(client, state) +
            getShareButton(client, state) +
            getPlayerButtons() +
            getHistoryButtons()

    private fun getHistoryButtons(): List<MoreButton> {
        if (!fromHistory || item !is Track) return listOf()
        val buttons = mutableListOf<MoreButton>()
        buttons.add(button("remove_from_history", R.string.remove_from_history, R.drawable.ic_cancel) {
            lifecycleScope.launch { historyRepository.delete((item as Track).id, extensionId) }
        })
        val ctx = itemContext
        if (ctx != null && ctx !is Radio && ctx !is Track) {
            buttons.add(button("go_to_context", "Go to ${ctx.title}", ctx.icon) {
                openItemFragment(extensionId, ctx, false)
            })
        }
        return buttons
    }

    private fun getPlayerButtons() = if (fromPlayer) listOf(
        button("audio_fx", R.string.audio_fx, R.drawable.ic_equalizer) {
            AudioEffectsBottomSheet().show(parentFragmentManager, null)
        },
        button("sleep_timer", R.string.sleep_timer, R.drawable.ic_snooze) {
            SleepTimerBottomSheet().show(parentFragmentManager, null)
        },
        button("quality_selection", R.string.quality_selection, R.drawable.ic_high_quality) {
            QualitySelectionBottomSheet().show(parentFragmentManager, null)
        }
    ) else listOf()

    private fun getPlayButtons(
        client: ExtensionClient?, item: EchoMediaItem, loaded: Boolean
    ) = if (client is TrackClient) listOfNotNull(
        button("play", R.string.play, R.drawable.ic_play) {
            playerViewModel.play(extensionId, item, loaded)
        },
        if (item is EchoMediaItem.Lists) button(
            "shuffle_play", R.string.shuffle, R.drawable.ic_shuffle
        ) {
            playerViewModel.shuffle(extensionId, item, loaded)
        } else null,
        if (playerViewModel.queue.isNotEmpty())
            button("next", R.string.add_to_next, R.drawable.ic_playlist_play) {
                playerViewModel.addToNext(extensionId, item, loaded)
            }
        else null,
        if (playerViewModel.queue.size > 1)
            button("queue", R.string.add_to_queue, R.drawable.ic_playlist_add) {
                playerViewModel.addToQueue(extensionId, item, loaded)
            }
        else null
    ) else listOf()

    fun getPlaylistEditButtons(
        client: ExtensionClient?, state: MediaState<*>?, loaded: Boolean
    ) = run {
        if (client !is PlaylistEditClient) return@run listOf()
        val item = state?.item ?: item
        val isEditable = item is Playlist && item.isEditable
        listOfNotNull(
            when {
                loaded -> button(
                    "save_to_playlist", R.string.save_to_playlist, R.drawable.ic_library_music
                ) {
                    SaveToPlaylistBottomSheet.newInstance(extensionId, item)
                        .show(parentFragmentManager, null)
                }
                state == null -> placeholderButton(
                    "save_to_playlist", R.string.save_to_playlist, R.drawable.ic_library_music
                )
                else -> null
            },
            if (isEditable) button(
                "edit_playlist", R.string.edit_playlist, R.drawable.ic_edit_note
            ) {
                openFragment<EditPlaylistFragment>(
                    EditPlaylistFragment.getBundle(extensionId, item, loaded)
                )
            } else null,
            if (isEditable) button(
                "delete_playlist", R.string.delete_playlist, R.drawable.ic_delete
            ) {
                DeletePlaylistBottomSheet.show(requireActivity(), extensionId, item, loaded)
            } else null,
            if ((itemContext as? Playlist)?.isEditable == true && item is Track) button(
                "remove_from_playlist", R.string.remove, R.drawable.ic_cancel
            ) {
                EditPlaylistBottomSheet.newInstance(
                    extensionId, itemContext as Playlist, tabId, pos
                ).show(parentFragmentManager, null)
            } else null
        )
    }

    fun getDownloadButtons(
        client: ExtensionClient?, state: MediaState<*>?, downloads: List<Downloader.Info>
    ) = run {
        val item = state?.item ?: item
        val shouldShowDelete = when (item) {
            is Track -> downloads.any { it.download.trackId == item.id }
            else -> downloads.any { it.context?.itemId == item.id }
        }
        val downloadable =
            state != null && client is TrackClient && state.item.extras[EXTENSION_ID] != OfflineExtension.metadata.id

        listOfNotNull(
            when {
                downloadable -> button(
                    "download", R.string.download, R.drawable.ic_download_for_offline
                ) {
                    val downloadViewModel by activityViewModel<DownloadViewModel>()
                    downloadViewModel.addToDownload(requireActivity(), extensionId, item, itemContext)
                }
                state == null && client is TrackClient -> placeholderButton(
                    "download", R.string.download, R.drawable.ic_download_for_offline
                )
                else -> null
            },
            if (shouldShowDelete) button(
                "delete_download", R.string.delete_download, R.drawable.ic_scan_delete
            ) {
                val downloadViewModel by activityViewModel<DownloadViewModel>()
                downloadViewModel.deleteDownload(item)
            } else null
        )

    }

    private fun getRadioButton(client: ExtensionClient?, state: MediaState.Loaded<*>?) = listOfNotNull(
        when {
            state?.showRadio == true -> button(
                "radio", R.string.radio, R.drawable.ic_sensors
            ) { playerViewModel.radio(extensionId, state.item, true) }
            state == null && client is RadioClient && item.isRadioSupported -> placeholderButton(
                "radio", R.string.radio, R.drawable.ic_sensors
            )
            else -> null
        }
    )

    private fun getShareButton(client: ExtensionClient?, state: MediaState.Loaded<*>?) = listOfNotNull(
        when {
            state?.showShare == true -> button(
                "share", R.string.share, R.drawable.ic_share
            ) { vm.onShare() }
            state == null && client is ShareClient && item.isShareable -> placeholderButton(
                "share", R.string.share, R.drawable.ic_share
            )
            else -> null
        }
    )

    fun getActionButtons(
        client: ExtensionClient?,
        state: MediaState.Loaded<*>?,
    ) = listOfNotNull(
        when {
            state?.isFollowed != null -> button(
                "follow", if (state.isFollowed) R.string.unfollow else R.string.follow,
                if (state.isFollowed) R.drawable.ic_check_circle_filled else R.drawable.ic_check_circle
            ) { vm.followItem(!state.isFollowed) }
            state == null && client is FollowClient && item.isFollowable -> placeholderButton(
                "follow", R.string.follow, R.drawable.ic_check_circle
            )
            else -> null
        },
        when {
            state?.isSaved != null && !(state.item is Playlist && (state.item as Playlist).isEditable) -> button(
                "save_to_library",
                if (state.isSaved) R.string.remove_from_library else R.string.save_to_library,
                if (state.isSaved) R.drawable.ic_bookmark_filled else R.drawable.ic_bookmark_outline,
                dismissOnClick = !(state.isSaved && state.item is Playlist)
            ) {
                if (state.isSaved && state.item is Playlist) {
                    MaterialAlertDialogBuilder(requireContext())
                        .setMessage(getString(R.string.remove_from_library_confirm, (state.item as Playlist).title))
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(R.string.remove) { _, _ -> vm.saveToLibrary(false) }
                        .show()
                } else {
                    vm.saveToLibrary(!state.isSaved)
                }
            }
            state == null && client is SaveClient && run {
                val i = item; i.isSaveable && !(i is Playlist && i.isEditable)
            } -> placeholderButton(
                "save_to_library", R.string.save_to_library, R.drawable.ic_bookmark_outline
            )
            else -> null
        },
        when {
            state?.isLiked != null -> button(
                "like", if (state.isLiked) R.string.unlike else R.string.like,
                if (state.isLiked) R.drawable.ic_heart_filled_40dp else R.drawable.ic_heart_outline_40dp
            ) { vm.likeItem(!state.isLiked) }
            state == null && client is LikeClient && item.isLikeable -> placeholderButton(
                "like", R.string.like, R.drawable.ic_heart_outline_40dp
            )
            else -> null
        },
        when {
            state?.isHidden != null -> button(
                "hide", if (state.isHidden) R.string.unhide else R.string.hide,
                if (state.isHidden) R.drawable.ic_unhide else R.drawable.ic_hide
            ) { vm.hideItem(!state.isHidden) }
            state == null && client is HideClient && item.isHideable -> placeholderButton(
                "hide", R.string.hide, R.drawable.ic_hide
            )
            else -> null
        }
    )

    private fun getItemButtons(item: EchoMediaItem) = when (item) {
        is Track -> item.artists + listOfNotNull(item.album)
        is EchoMediaItem.Lists -> item.artists
        is Artist -> listOf()
    }.map {
        button(it.id, it.title, it.icon) {
            openItemFragment(extensionId, it)
        }
    }

    private inline fun <reified T : Fragment> openFragment(bundle: Bundle) {
        requireActivity().openFragment<T>(null, bundle, contId)
    }

    private fun openItemFragment(
        extensionId: String?, item: EchoMediaItem?, loaded: Boolean = false
    ) {
        extensionId ?: return
        item ?: return
        openFragment<MediaFragment>(MediaFragment.getBundle(extensionId, item, loaded))
        dismiss()
    }
}