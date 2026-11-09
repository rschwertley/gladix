package dev.brahmkshatriya.echo.ui.main

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kotlinx.coroutines.launch
import com.google.android.material.transition.MaterialSharedAxis
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.common.clients.LibraryFeedClient
import dev.brahmkshatriya.echo.common.clients.PlaylistEditClient
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Message
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Radio
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.databinding.FragmentLibraryBinding
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.getAs
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.isClient
import dev.brahmkshatriya.echo.extensions.cache.Cached
import dev.brahmkshatriya.echo.playback.MediaItemUtils
import androidx.recyclerview.widget.RecyclerView
import dev.brahmkshatriya.echo.ui.common.GridAdapter.Companion.configureGridLayout
import dev.brahmkshatriya.echo.ui.common.TvAwareRecyclerView
import dev.brahmkshatriya.echo.ui.common.SnackBarHandler.Companion.createSnack
import dev.brahmkshatriya.echo.ui.common.UiViewModel
import dev.brahmkshatriya.echo.ui.common.UiViewModel.Companion.applyBackPressCallback
import dev.brahmkshatriya.echo.ui.common.UiViewModel.Companion.configure
import dev.brahmkshatriya.echo.ui.common.UiViewModel.Companion.observeUserRefresh
import dev.brahmkshatriya.echo.ui.feed.FeedAdapter.Companion.getFeedAdapter
import dev.brahmkshatriya.echo.ui.feed.FeedAdapter.Companion.getTouchHelper
import dev.brahmkshatriya.echo.ui.feed.FeedClickListener.Companion.getFeedListener
import dev.brahmkshatriya.echo.ui.feed.FeedData
import dev.brahmkshatriya.echo.ui.feed.FeedViewModel
import dev.brahmkshatriya.echo.ui.main.MainFragment.Companion.applyInsets
import dev.brahmkshatriya.echo.ui.playlist.create.CreatePlaylistBottomSheet
import dev.brahmkshatriya.echo.ui.settings.SettingsBottomSheet
import dev.brahmkshatriya.echo.utils.ContextUtils.observe
import dev.brahmkshatriya.echo.utils.Serializer.getSerialized
import dev.brahmkshatriya.echo.utils.ui.AnimationUtils.setupTransition
import kotlinx.coroutines.flow.combine
import org.koin.androidx.viewmodel.ext.android.activityViewModel
import org.koin.androidx.viewmodel.ext.android.viewModel

class LibraryFragment : Fragment(R.layout.fragment_library) {
    companion object {
        const val LIBRARY_TAB_RESULT = "libraryTab"
    }

    private val feedData by lazy {
        val vm by viewModel<FeedViewModel>()
        val id = "library"
        vm.getFeedData(id, cached = {
            val curr = current.value!!
            val feed = Cached.getFeedShelf(app, curr.id, id).getOrThrow()
            FeedData.State(curr.id, null, feed)
        }) {
            val curr = current.value!!
            val feed = Cached.savingFeed(
                app, curr, id,
                curr.getAs<LibraryFeedClient, Feed<Shelf>> { loadLibraryFeed() }.getOrThrow()
            )
            FeedData.State(curr.id, null, feed)
        }
    }

    /**
     * [2026-11-08] "Playing from Library - <tab>" for a queue set on this screen (FeedClickListener.feedPageLabel): Play,
     * Shuffle and declared-list row taps here had no context, so a blank bar. The tab's own title, so another extension's
     * library list reads as what it is. Phone only. A tap on it shows this tab again (PlayerFragment.openLibraryTab, via the
     * LIBRARY_TAB_RESULT listener below), never the folder path.
     */
    // [2026-11-08] A song tapped in a row (rowId / rowTitle, e.g. the All tab's "Tracks" row) is labelled after that row and
    // reopens the one tab whose title or id matches the row's, ignoring case, so All's Tracks row reads and behaves like the
    // Tracks tab (GitHub #4, "the All tab must behave the same"). No match, or several: the tab on screen. A matching rule,
    // not a declared link, since Shelf carries no tab; no extension is named here.
    fun libraryLabel(rowId: String? = null, rowTitle: String? = null): Radio? {
        val extensionId = feedData.shownExtensionId ?: return null
        val rowKeys = listOfNotNull(rowId, rowTitle).filter { it.isNotBlank() }
        val rowTab = if (rowKeys.isEmpty()) null else feedData.shownTabs.filter { tab ->
            rowKeys.any { it.equals(tab.title, ignoreCase = true) || it.equals(tab.id, ignoreCase = true) }
        }.singleOrNull()
        val tab = rowTab ?: feedData.selectedTab
        val name = rowTitle?.takeIf { it.isNotBlank() } ?: tab?.title?.takeIf { it.isNotBlank() }
        val title = name?.let { getString(R.string.library_tab_label, it) } ?: getString(R.string.library)
        return MediaItemUtils.libraryTabLabel(extensionId, tab?.id, title)
    }

    private val listener by lazy { getFeedListener(requireParentFragment()) }
    private val feedAdapter by lazy { getFeedAdapter(feedData, listener) }
    private var swipeRefresh: SwipeRefreshLayout? = null

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) swipeRefresh?.isRefreshing = false
        // [2026-11-07] Every visit re-marks the liked list (FeedData.runMarksPass): a bottom-nav return does not reload,
        // and before this a list shown unmarked stayed unmarked until something did (1172/1173).
        else feedData.runMarksPass()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentLibraryBinding.bind(view)
        val recyclerView = binding.recyclerView as RecyclerView
        // No add/remove/change fades (2026-11-02): a tab switch replaces every row, which the default animator played as a flash. Same as MediaDetailsFragment (Jun 13).
        recyclerView.itemAnimator = null
        setupTransition(view, false, MaterialSharedAxis.Y)
        val headerAdapter = HeaderAdapter(this)
        val uiViewModel by activityViewModel<UiViewModel>()
        observe(uiViewModel.navigationReselected) {
            if (it != 2) return@observe
            SettingsBottomSheet().show(parentFragmentManager, null)
        }
        observe(
            uiViewModel.navigation.combine(feedData.backgroundImageFlow) { a, b -> a to b }
        ) { (curr, bg) ->
            if (curr != 2) return@observe
            uiViewModel.currentNavBackground.value = bg
        }
        applyInsets(recyclerView, binding.appBarOutline) {
            binding.swipeRefresh.configure(it)
        }
        applyBackPressCallback()
        getTouchHelper(listener).attachToRecyclerView(recyclerView)
        val parent = requireParentFragment()
        configureGridLayout(
            recyclerView,
            feedAdapter.withLoading(this, headerAdapter, onCreatePlaylistClick = {
                lifecycleScope.launch {
                    if (feedData.current.value?.isClient<PlaylistEditClient>() == true)
                        CreatePlaylistBottomSheet().show(parent.parentFragmentManager, null)
                }
            }),
        )
        (recyclerView as? TvAwareRecyclerView)?.navRailView =
            requireActivity().findViewById(R.id.navRailContainer)
        swipeRefresh = binding.swipeRefresh
        binding.swipeRefresh.run {
            // [2026-11-07] A pull also re-checks the liked list's songs, whatever their verdicts' age (FeedData.requestRecheck).
            setOnRefreshListener { feedData.requestRecheck(); feedData.refresh() }
        }
        observeUserRefresh(binding.swipeRefresh, feedData.isRefreshingFlow)
        // [2026-11-07] The liked list's dead-song marks, applied in place (FeedData.playableMarks). A recreated view is a
        // visit too, so the pass runs here as well; it does nothing until a load has finished.
        observe(feedData.playableMarks) { feedAdapter.playableMarks = it }
        feedData.runMarksPass()

        parent.parentFragmentManager.setFragmentResultListener("createPlaylist", this) { _, data ->
            val extensionId = data.getString("extensionId")
            val playlist = data.getSerialized<Playlist>("playlist")?.getOrNull()
            if (extensionId != null && playlist != null) createSnack(
                Message(
                    getString(R.string.x_created, playlist.title),
                    Message.Action(getString(R.string.view)) {
                        listener.onMediaClicked(null, extensionId, playlist, null)
                    }
                )
            )
            feedData.refresh()
        }

        parent.parentFragmentManager.setFragmentResultListener("deleted", this) { _, _ ->
            feedData.refresh()
        }

        parent.parentFragmentManager.setFragmentResultListener("reloadLibrary", this) { _, _ ->
            feedData.refresh()
        }

        // [2026-11-08] From a "Playing from Library - <tab>" tap. A result set before this listener exists (Library not yet
        // opened) is kept by the FragmentManager and delivered once this view is started.
        parent.parentFragmentManager.setFragmentResultListener(LIBRARY_TAB_RESULT, this) { _, data ->
            val tabId = data.getString("tabId") ?: return@setFragmentResultListener
            feedData.selectTabById(data.getString("extensionId"), tabId)
        }
    }
}