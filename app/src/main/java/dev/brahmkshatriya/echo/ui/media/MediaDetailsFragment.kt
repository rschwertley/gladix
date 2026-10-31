package dev.brahmkshatriya.echo.ui.media

import dev.brahmkshatriya.echo.ui.common.FragmentUtils.focusFirstRowOnTv
import android.os.Bundle
import android.view.View
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.paging.LoadState
import kotlinx.coroutines.launch
import com.google.android.material.appbar.AppBarLayout
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.common.clients.PlaylistEditClient
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.databinding.FragmentMediaDetailsBinding
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.isClient
import dev.brahmkshatriya.echo.ui.common.FragmentUtils.openFragment
import dev.brahmkshatriya.echo.ui.common.GridAdapter
import dev.brahmkshatriya.echo.ui.common.GridAdapter.Companion.configureGridLayout
import dev.brahmkshatriya.echo.ui.common.TvAwareRecyclerView
import dev.brahmkshatriya.echo.ui.common.UiViewModel
import dev.brahmkshatriya.echo.ui.playlist.edit.EditPlaylistFragment
import dev.brahmkshatriya.echo.ui.common.UiViewModel.Companion.applyContentInsets
import dev.brahmkshatriya.echo.ui.common.UiViewModel.Companion.applyInsets
import dev.brahmkshatriya.echo.ui.common.UiViewModel.Companion.configure
import dev.brahmkshatriya.echo.ui.feed.FeedAdapter.Companion.getFeedAdapter
import dev.brahmkshatriya.echo.ui.feed.FeedAdapter.Companion.getTouchHelper
import dev.brahmkshatriya.echo.ui.feed.FeedClickListener
import dev.brahmkshatriya.echo.ui.feed.FeedClickListener.Companion.getFeedListener
import dev.brahmkshatriya.echo.ui.feed.FeedViewModel
import dev.brahmkshatriya.echo.ui.media.MediaHeaderAdapter.Companion.getMediaHeaderListener
import dev.brahmkshatriya.echo.utils.ContextUtils.observe
import dev.brahmkshatriya.echo.utils.ui.FastScrollerHelper
import dev.brahmkshatriya.echo.utils.ui.FastScrollerHelper.applyInsets
import kotlinx.coroutines.flow.combine
import org.koin.androidx.viewmodel.ext.android.activityViewModel
import org.koin.androidx.viewmodel.ext.android.viewModel

class MediaDetailsFragment : Fragment(R.layout.fragment_media_details) {

    interface Parent {
        val feedId: String
        val viewModel: MediaDetailsViewModel
        val fromPlayer: Boolean
        val showInitialButtons: Boolean get() = false
    }

    val parent by lazy { requireParentFragment() as Parent }
    val viewModel by lazy { parent.viewModel }
    private val feedViewModel by lazy {
        requireParentFragment().viewModel<FeedViewModel>().value
    }

    private val trackFeedData by lazy {
        feedViewModel.getFeedData(
            "${parent.feedId}_tracks",
            Feed.Buttons(showPlayAndShuffle = true),
            true,
            viewModel.tracksLoadedFlow, viewModel.trackCachedFlow,
            cached = { viewModel.trackCachedFlow.value?.getOrThrow() },
            loader = { viewModel.tracksLoadedFlow.value?.getOrThrow() }
        )
    }

    private val feedData by lazy {
        feedViewModel.getFeedData(
            "${parent.feedId}_feed",
            Feed.Buttons.EMPTY,
            false,
            viewModel.feedCachedFlow, viewModel.feedLoadedFlow,
            cached = { viewModel.feedCachedFlow.value?.getOrThrow() },
            loader = { viewModel.feedLoadedFlow.value?.getOrThrow() }
        )
    }

    private val mediaHeaderAdapter by lazy {
        MediaHeaderAdapter(
            requireParentFragment().getMediaHeaderListener(viewModel),
            parent.fromPlayer
        )
    }

    private val feedListener by lazy {
        if (!parent.fromPlayer) requireParentFragment().getFeedListener()
        else FeedClickListener(
            requireParentFragment(),
            requireActivity().supportFragmentManager,
            R.id.navHostFragment
        ) {
            val uiViewModel by activityViewModel<UiViewModel>()
            uiViewModel.collapsePlayer()
        }
    }

    private val trackAdapter by lazy {
        getFeedAdapter(trackFeedData, feedListener, true)
    }
    private val feedAdapter by lazy {
        getFeedAdapter(feedData, feedListener)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentMediaDetailsBinding.bind(view)
        // ⚠⚠ [CORRECTED 2026-09-13] THE "matching HomeFragment/LibraryFragment/SearchFragment"
        // CLAIM WAS FALSE - AND IT WAS FALSE FOR ALL THREE, NOT JUST ONE. Verified by reading them:
        //     HomeFragment     applyInsets(recyclerView, ...) :68  ->  getTouchHelper ... :84
        //     LibraryFragment  applyInsets(recyclerView, ...) :86  ->  getTouchHelper ... :90
        //     SearchFragment   applyInsets(recyclerView, ...) :132 ->  getTouchHelper ... :199
        // applyInsets is MainFragment's companion extension and calls FastScrollerHelper.applyTo inside
        // (MainFragment:105), so ALL THREE register the SCROLLER FIRST - the order this comment calls broken.
        // ⚠⚠ WHAT THAT COST: Search has a live fast-scroller grab fault, and it was never in the
        // fix's list BECAUSE THIS COMMENT CERTIFIED IT AS ALREADY CORRECT. A comment that certifies a screen
        // it never checked is worse than no comment - a month of the investigation went elsewhere on its word.
        // It is also a SINGLE ASSERTION with no second record: the claim appears in no session summary, only
        // here and in its twin, so nothing could contradict it.
        // ⚠️ WHAT SURVIVES: the REORDER ON THESE TWO FILES IS STILL RIGHT - it demonstrably fixed a
        // thumb that rendered and refused to drag. What does NOT survive is the explanation, because the three
        // screens cited as working exemplars use the opposite order. So the mechanism below is UNCONFIRMED:
        // either those three have the same latent fault and nobody has tried to grab their thumbs, or
        // registration order is not the whole cause here. Do not cite this comment as evidence about any screen
        // other than this one.
        // ORDER IS LOAD-BEARING ON THIS FILE: the ItemTouchHelper is attached BEFORE the fast scroller.
        // Both are OnItemTouchListeners and
        // RecyclerView.dispatchOnItemTouch walks mOnItemTouchListeners IN REGISTRATION ORDER, latching
        // the first that intercepts - upstream AndroidFastScroll issue #53. This file and its twin
        // had the opposite order and were the only two screens where the fast-scroll thumb rendered but
        // refused to drag. Do not reorder these two lines.
        getTouchHelper(feedListener).attachToRecyclerView(binding.recyclerView)
        // Handle KEPT and re-padded below. Discarding it left the track on applyTo's flat 8dp forever,
        // running under the mini-player and nav bar.
        // COLLAPSING SCREEN: hand the scroller the AppBarLayout so its metrics account for the scroll
        // the header consumes. It lives in the PARENT fragment's layout (fragment_media.xml), not this
        // one, and is a sibling of this fragment's container under the CoordinatorLayout — hence resolved
        // from the parent rather than from binding.
        val appBar = requireParentFragment().view?.findViewById<AppBarLayout>(R.id.appBarLayout)
        val scroller = FastScrollerHelper.applyTo(binding.recyclerView, appBar, traceTag = "media")
        applyInsets(viewModel.uiResultFlow) {
            // The TV mini-player reserve is in combined itself now (UiViewModel.combined, 2026-10-31); do not add it here.
            binding.recyclerView.applyContentInsets(it, 20, 0, 16)
            // top = 0, disagreeing with the full-bleed screens ON PURPOSE — see applyInsets' `top` param.
            // ⚠️ THE SAME VALUE HAS NOW CARRIED THREE DIFFERENT JUSTIFICATIONS. Do not quote an older one
            // back; they are not interchangeable, and two of them are false under the current layout:
            //   1. PRE-MIGRATION (this, and true again): the RecyclerView sits BELOW the AppBarLayout in a
            //      CoordinatorLayout — HeaderScrollingViewBehavior positions it there — so it never extends
            //      under the status bar; the collapsing header does. Adding insets.top here would inset the
            //      scroll TRACK by a status bar this view does not contain, parking the thumb below the top
            //      of its own track (the "mid-track at rest" symptom).
            //   2. OVERLAP BUILD (1085, reverted): the view DID span the full viewport and DID contain the
            //      status bar, and 0 was kept for the OPPOSITE reason — so the rail spanned the screen with
            //      the thumb grabbable at the very top. That wording is now false and is gone.
            //   3. POST-OVERLAP, PRE-RESTORE: the list was positioned below a non-collapsing AppBarLayout,
            //      so (1)'s reasoning held but the header consumed no scroll.
            // Reading (1) applies as of the 2026-09-07 CollapsingToolbarLayout restore.
            // Matches the `0` passed to applyContentInsets on the line above: one view, one answer about
            // the top inset. There is NO paddingTop written here and there must not be — the toolbar
            // clearance is paid once, by layout, and writing it again would inset the content twice.
            // ⚠️ This fixes the thumb's POSITION, not the dead drag — that was the listener order above.
            scroller.applyInsets(binding.recyclerView.context, it, top = 0)
        }
        val lineAdapter = LineAdapter()
        observe(trackFeedData.shouldShowEmpty) {
            lineAdapter.loadState = if (it) LoadState.Loading else LoadState.NotLoading(false)
        }
        observe(viewModel.uiResultFlow) { result ->
            mediaHeaderAdapter.result = result
        }
        binding.recyclerView.itemAnimator = null
        configureGridLayout(
            binding.recyclerView,
            GridAdapter.Concat(
                mediaHeaderAdapter,
                trackAdapter.withLoading(this, initialButtons = parent.showInitialButtons, onEditPlaylistClick = {
                    lifecycleScope.launch {
                        val state = viewModel.uiResultFlow.value?.getOrNull() ?: return@launch
                        val playlist = state.item as? Playlist ?: return@launch
                        if (!playlist.isEditable) return@launch
                        if (viewModel.extensionFlow.value?.isClient<PlaylistEditClient>() != true) return@launch
                        requireParentFragment().openFragment<EditPlaylistFragment>(
                            null, EditPlaylistFragment.getBundle(state.extensionId, playlist, state.loaded)
                        )
                    }
                }, editPlaylistVisible = viewModel.uiResultFlow.combine(viewModel.extensionFlow) { result, extension ->
                    // The tap's own two checks above, so the pencil shows exactly when it works.
                    val playlist = result?.getOrNull()?.item as? Playlist
                    playlist?.isEditable == true && extension?.isClient<PlaylistEditClient>() == true
                }),
                lineAdapter,
                feedAdapter.withLoading(this)
            ),
        )
        (binding.recyclerView as? TvAwareRecyclerView)?.navRailView =
            requireActivity().findViewById(R.id.navRailContainer)
        // The cast above never succeeds - fragment_media_details has no TV variant - so this page had no TV focus
        // handling at all; this is it. // TV: focus the first row on open, restore the row left on return - see FragmentUtils.focusFirstRowOnTv.
        focusFirstRowOnTv(binding.recyclerView)
        val loadingFlow = viewModel.isRefreshingFlow
            .combine(trackFeedData.isRefreshingFlow) { a, b -> a || b }
                .combine(feedData.isRefreshingFlow) { a, b -> a || b }
        binding.swipeRefresh.run {
            configure()
            setOnRefreshListener { viewModel.refreshTracks() }
            var hasEverLoaded = false
            observe(loadingFlow) { isLoading ->
                if (!isLoading) hasEverLoaded = true
                isRefreshing = hasEverLoaded && isLoading
            }
        }
        if (parent.fromPlayer) {
            binding.swipeRefresh.isEnabled = false
            ViewCompat.setNestedScrollingEnabled(binding.recyclerView, true)
        }
    }
}