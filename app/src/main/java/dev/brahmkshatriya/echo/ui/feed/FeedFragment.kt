package dev.brahmkshatriya.echo.ui.feed

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.core.view.updatePaddingRelative
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.commit
import androidx.fragment.app.replace
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.appbar.AppBarLayout
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.databinding.FragmentGenericCollapsableBinding
import dev.brahmkshatriya.echo.databinding.FragmentRecyclerWithRefreshBinding
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.getExtensionOrThrow
import dev.brahmkshatriya.echo.extensions.cache.Cached
import dev.brahmkshatriya.echo.extensions.cache.Cached.savingFeed
import dev.brahmkshatriya.echo.ui.common.GridAdapter.Companion.configureGridLayout
import dev.brahmkshatriya.echo.ui.common.TvAwareRecyclerView
import androidx.recyclerview.widget.RecyclerView
import dev.brahmkshatriya.echo.ui.common.UiViewModel
import dev.brahmkshatriya.echo.ui.common.UiViewModel.Companion.applyContentInsets
import dev.brahmkshatriya.echo.ui.common.UiViewModel.Companion.applyInsets
import dev.brahmkshatriya.echo.ui.common.UiViewModel.Companion.configure
import dev.brahmkshatriya.echo.ui.extensions.login.LoginFragment.Companion.bind
import dev.brahmkshatriya.echo.ui.feed.FeedAdapter.Companion.getFeedAdapter
import dev.brahmkshatriya.echo.ui.feed.FeedAdapter.Companion.getTouchHelper
import dev.brahmkshatriya.echo.ui.feed.FeedClickListener.Companion.getFeedListener
import dev.brahmkshatriya.echo.ui.main.MainFragment.Companion.applyPlayerBg
import dev.brahmkshatriya.echo.utils.ContextUtils.observe
import dev.brahmkshatriya.echo.utils.ui.FastScrollerHelper
import dev.brahmkshatriya.echo.utils.ui.FastScrollerHelper.applyInsets
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import dev.brahmkshatriya.echo.ui.player.PlayerViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.activityViewModel
import org.koin.androidx.viewmodel.ext.android.viewModel

class FeedFragment : Fragment(R.layout.fragment_generic_collapsable) {
    companion object {
        // Prefetch debounce. Below ~400ms a fling that momentarily settles starts warming; above ~600ms a
        // genuine pause gets missed. 500 is the point where a fast scroll costs zero requests.
        const val WARM_DEBOUNCE_MS = 500L

        /**
         * ⚠⚠ THE IDS TRAVEL IN THE BUNDLE BECAUSE THE ACTIVITY VM DOES NOT
         * SURVIVE PROCESS DEATH. They used to live only in the activity-scoped [VM], so a kill
         * while locked left the restored fragment with nulls - getExtensionOrThrow(null) threw
         * ExtensionNotFoundException(null) and the cached path's own extensionId!! threw too,
         * i.e. a blank screen with nothing to retry. See [FeedArgs] for the precedence and for
         * what this does NOT restore (the Feed object itself, which cannot be parcelled).
         * ⚠️ DEFAULTED SO EXISTING CALLERS KEEP COMPILING, and there is one
         * (FeedClickListener.openFeed). A caller that omits them simply gets today's behaviour.
         */
        fun getBundle(
            title: String,
            subtitle: String?,
            extensionId: String? = null,
            feedId: String? = null,
        ) = Bundle().apply {
            putString("title", title)
            putString("subtitle", subtitle)
            putString("extensionId", extensionId)
            putString("feedId", feedId)
        }
    }

    class VM : ViewModel() {
        var initialized = false
        var extensionId: String? = null
        var feedId: String? = null
        var feed: Feed<Shelf>? = null
    }

    private val activityVm by activityViewModels<VM>()
    private val vm by viewModels<VM>()

    private val feedData by lazy {
        val feedViewModel by viewModel<FeedViewModel>()
        if (!vm.initialized) {
            vm.initialized = true
            // ⚠⚠ ARGUMENTS WIN OVER THE ACTIVITY VM - see [FeedArgs]. The VM is
            // RAM-only, so after a process kill it holds nulls while the arguments still carry
            // the ids. The fallback covers back-stack entries created before the ids were put
            // in the bundle. Adapted from Evoe (github.com/Doggo785/evoe), 27eac710, UPL.
            val (extId, fId) = FeedArgs.resolveFeedIds(
                arguments?.getString("extensionId"),
                arguments?.getString("feedId"),
                activityVm.extensionId,
                activityVm.feedId,
            )
            vm.extensionId = extId
            vm.feedId = fId
            // ⚠️ STILL FROM THE VM, AND STILL NULL AFTER A KILL. Feed<Shelf>
            // carries lambdas and cannot be parcelled, so the loader below fails on a restore
            // and the CACHED lambda - which needs only the two ids above - is what renders.
            vm.feed = activityVm.feed
        }
        feedViewModel.getFeedData(
            vm.feedId ?: "",
            cached = {
                val extId = vm.extensionId!!
                val feed = Cached.getFeedShelf(app, extId, vm.feedId!!)
                FeedData.State(extId, null, feed.getOrThrow())
            }
        ) {
            // ⚠⚠ THROWN, NOT RETURNED AS null, AND THE DIFFERENCE IS THE WHOLE
            // FIX. This used to be `vm.feed!!`, which threw a NullPointerException on every
            // restored see-all (the VM is RAM-only - see the note above and [FeedArgs]).
            // Returning null instead LOOKS like the gentler option and blanks the screen: a null
            // loader state becomes a SUCCESSFUL PagedData.empty() in FeedData.getFeedSourceData,
            // and PagedSource.load serves that empty page from `loaded` without ever reaching its
            // cached fallback. Only a FAILED `loaded` reaches that fallback, so the cached feed -
            // the thing the bundle ids were added for - renders only if this throws.
            // ⚠️ Cached.NotFound SPECIFICALLY, because it is already routed:
            // FeedLoadingAdapter.getStateViewType maps it to the NotLoading row, while any other
            // exception falls to the Error row and renders its message. It is also inert - the
            // only other reference to the type in the app is that `when` arm (nothing catches it,
            // re-invokes a loader on it, or reports it to throwFlow/Crashlytics).
            // ⚠️ RESIDUAL, AND THE DECISION ON IT - NOT A TO-DO. With NO cached
            // copy this shows the empty row, whose Retry cannot succeed: a retry re-enters this
            // lambda and vm.feed is still null. Feed<Shelf> carries lambdas and cannot be
            // parcelled, so nothing here can rebuild it; BACK, then re-opening the shelf, is the
            // working recovery. USER DECISION 2026-10-23: left as is, and a pop-back-to-parent on
            // this condition was WEIGHED AND DECLINED, because the case is rare - the feed the
            // user just opened is the MOST RECENTLY USED entry in App.getCache's kache, so the
            // 50 MB LRU evicts it LAST, and an OS cache clear would have to land in the window
            // between the process kill and the reopen. It comes back if that proves wrong on a
            // device: an empty row on a see-all the user had just been looking at.
            // ⚠️ AND THE cacheDir ARGUMENT FOR THE OPPOSITE IS THE TRAP: an
            // earlier draft of this note read "cacheDir + LRU" as making the no-cache case
            // common. Evictable does not mean evicted-soon under LRU, and MRU ordering is the
            // half that reading left out. (Routing read from PagedSource.load and
            // FeedLoadingAdapter; the no-cache outcome is INFERRED from that reading, never
            // observed on a device.)
            val feedId = vm.feedId ?: throw Cached.NotFound("feed")
            val restored = vm.feed ?: throw Cached.NotFound("feed-" + vm.extensionId + "-" + feedId)
            val extension = music.getExtensionOrThrow(vm.extensionId)
            val feed = savingFeed(app, extension, feedId, restored)
            FeedData.State(extension.id, null, feed)
        }
    }

    private val title by lazy { arguments?.getString("title")!! }
    private val subtitle by lazy { arguments?.getString("subtitle") }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentGenericCollapsableBinding.bind(view)
        binding.bind(this, false)
        binding.extensionIcon.isVisible = false
        binding.toolBar.title = title
        binding.toolBar.subtitle = subtitle
        // TV rail parity (matches MediaFragment): the collapsable HEADER (appBarLayout) is otherwise
        // rail-unaware and stretches full-width across the nav rail, while the list already insets via
        // `combined`. Pad the header start by the rail inset so it lines up with the list. isRail-gated,
        // so on phone (isRail == false) this observer never registers and the header is untouched.
        val uiViewModel by activityViewModel<UiViewModel>()
        if (uiViewModel.isRail) observe(uiViewModel.combined) {
            binding.appBarLayout.updatePaddingRelative(start = it.start)
        }
        applyPlayerBg(view) {
            mainBgDrawable.combine(feedData.backgroundImageFlow) { a, b -> b ?: a }
        }
        if (savedInstanceState == null) childFragmentManager.commit {
            replace<Actual>(R.id.genericFragmentContainer, null, arguments)
        }
    }

    class Actual : Fragment(R.layout.fragment_recycler_with_refresh) {
        private val feedData by lazy {
            val vm by requireParentFragment().viewModel<FeedViewModel>()
            vm.feedDataMap.values.first()
        }

        private val listener by lazy { requireParentFragment().getFeedListener() }
        private val feedAdapter by lazy {
            getFeedAdapter(feedData, listener)
        }
        private var swipeRefresh: SwipeRefreshLayout? = null

        override fun onHiddenChanged(hidden: Boolean) {
            super.onHiddenChanged(hidden)
            if (hidden) swipeRefresh?.isRefreshing = false
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            val binding = FragmentRecyclerWithRefreshBinding.bind(view)
            val recyclerView = binding.recyclerView as RecyclerView
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
            getTouchHelper(listener).attachToRecyclerView(recyclerView)
            // Hoisted above the inset block so the handle exists when the block first runs; it is kept and
            // re-padded there rather than left on applyTo's flat 8dp.
            // COLLAPSING SCREEN — see MediaDetailsFragment. The AppBarLayout is in the OUTER
            // FeedFragment's layout (fragment_generic_collapsable.xml); this is FeedFragment.Actual.
            val appBar = requireParentFragment().view?.findViewById<AppBarLayout>(R.id.appBarLayout)
            val scroller = FastScrollerHelper.applyTo(recyclerView, appBar, traceTag = "seeall")
            applyInsets {
                // The TV mini-player reserve is in combined itself now (UiViewModel.combined, 2026-10-31); do not add it here.
                recyclerView.applyContentInsets(it, 20, 8, 16)
                // top = 0 — same reason as MediaDetailsFragment: fragment_generic_collapsable puts this
                // RecyclerView below an AppBarLayout in a CoordinatorLayout, so the status bar is the
                // header's, not this view's. applyContentInsets above likewise adds no insets.top.
                scroller.applyInsets(recyclerView.context, it, top = 0)
            }
            configureGridLayout(
                recyclerView,
                feedAdapter.withLoading(this),
            )
            (recyclerView as? TvAwareRecyclerView)?.navRailView =
                requireActivity().findViewById(R.id.navRailContainer)

            // ══ PREFETCH TRIGGER ══ See FeedData.warmTracks for the gates and the reasoning.
            // Scroll-settle, not long-press: what predicts opening an item is looking at it. A long-press
            // opens the more-menu and is used to queue, to check the artist, or by accident - it does not
            // predict an open, and it was only ever considered because the listener already existed.
            //
            // The debounce is what makes a fling cost ZERO: every state change cancels the pending job, so
            // a scroll that never settles for the full delay warms nothing. It also cancels a settle that
            // is immediately scrolled away from.
            //
            // Cancelling the JOB does not cancel a warm already in flight, and that is deliberate: once
            // started, the request is owned by app.scope inside coalescedTracks, so it completes and fills
            // the memory layer. Cancelling mid-response would waste the bytes already transferred AND cache
            // nothing, turning a partially-useful request into a wholly wasted one.
            //
            // CENTRE-WEIGHTED MIDDLE THREE rather than every visible row: the middle of the viewport is
            // what someone who stopped scrolling is looking at, and three keeps a deliberate pause at about
            // three requests instead of six. The visible width is the tuning knob if that needs adjusting.
            val playerViewModel by activityViewModel<PlayerViewModel>()
            var warmJob: Job? = null
            recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                    warmJob?.cancel()
                    if (newState != RecyclerView.SCROLL_STATE_IDLE) return
                    warmJob = viewLifecycleOwner.lifecycleScope.launch {
                        delay(WARM_DEBOUNCE_MS)
                        // GridLayoutManager extends LinearLayoutManager, so this covers both feed layouts.
                        val lm = rv.layoutManager as? LinearLayoutManager ?: return@launch
                        val first = lm.findFirstVisibleItemPosition()
                        val last = lm.findLastVisibleItemPosition()
                        if (first < 0 || last < first) return@launch
                        val centre = (first + last) / 2
                        val visible = (centre - 1..centre + 1)
                            .filter { it in first..last }
                            // peek, never get: reading an item must not trigger a Paging load.
                            .mapNotNull { runCatching { feedAdapter.peek(it) }.getOrNull() }
                        feedData.warmTracks(visible, playerViewModel.isPlaying.value)
                    }
                }
            })
            swipeRefresh = binding.swipeRefresh
            binding.swipeRefresh.run {
                configure()
                setOnRefreshListener { feedData.refresh() }
                var hasEverLoaded = false
                observe(feedData.isRefreshingFlow) {
                    if (!it) hasEverLoaded = true
                    isRefreshing = hasEverLoaded && it
                }
            }
        }
    }
}