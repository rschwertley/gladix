package dev.brahmkshatriya.echo.ui.main.search

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePaddingRelative
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.Fragment
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
import com.google.android.material.search.SearchView
import com.google.android.material.transition.MaterialSharedAxis
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.common.clients.SearchFeedClient
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Buttons.Companion.EMPTY
import dev.brahmkshatriya.echo.common.models.QuickSearchItem
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.databinding.FragmentSearchBinding
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.getAs
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.getExtension
import dev.brahmkshatriya.echo.extensions.cache.Cached
import androidx.recyclerview.widget.RecyclerView
import dev.brahmkshatriya.echo.ui.common.GridAdapter.Companion.configureGridLayout
import dev.brahmkshatriya.echo.ui.common.TvAwareRecyclerView
import dev.brahmkshatriya.echo.ui.common.UiViewModel
import dev.brahmkshatriya.echo.ui.common.UiViewModel.Companion.applyBackPressCallback
import dev.brahmkshatriya.echo.ui.common.UiViewModel.Companion.configure
import dev.brahmkshatriya.echo.ui.common.UiViewModel.Companion.observeUserRefresh
import dev.brahmkshatriya.echo.ui.feed.FeedAdapter.Companion.getFeedAdapter
import dev.brahmkshatriya.echo.ui.feed.FeedAdapter.Companion.getTouchHelper
import dev.brahmkshatriya.echo.ui.feed.FeedClickListener
import dev.brahmkshatriya.echo.ui.feed.FeedData
import dev.brahmkshatriya.echo.ui.feed.FeedViewModel
import dev.brahmkshatriya.echo.ui.main.HeaderAdapter
import dev.brahmkshatriya.echo.ui.main.MainFragment.Companion.applyInsets
import dev.brahmkshatriya.echo.ui.main.search.SearchViewModel.Companion.saveInHistory
import dev.brahmkshatriya.echo.utils.ContextUtils.observe
import dev.brahmkshatriya.echo.utils.ui.AnimationUtils.setupTransition
import dev.brahmkshatriya.echo.utils.ui.UiUtils.isTv
import kotlinx.coroutines.flow.combine
import org.koin.androidx.viewmodel.ext.android.activityViewModel
import org.koin.androidx.viewmodel.ext.android.viewModel

class SearchFragment : Fragment(R.layout.fragment_search) {

    private val argId by lazy { arguments?.getString("extensionId") }
    private val searchViewModel by viewModel<SearchViewModel>()

    private val speechLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.let { searchViewModel.queryFlow.value = it }
        }
    }

    private fun launchVoiceSearch() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.voice_search))
        }
        runCatching { speechLauncher.launch(intent) }
    }

    // ⚠⚠ `private var extensionId = ""` WAS HERE AND IS GONE - DO NOT BRING IT BACK. It was
    // assigned in ONE place, `feedData`'s loaded lambda below, i.e. only after a network search feed
    // returned, so every quick search before that ran with `""` and silently found no extension. Quick
    // search now passes `argId` and lets `SearchViewModel.resolve` do what these feed lambdas already
    // do; the full mechanism is recorded there. Anything on this screen needing the extension should
    // ASK FOR IT, not read a field somebody else was supposed to have filled in.
    private val feedData by lazy {
        val vm by viewModel<FeedViewModel>()
        val id = "search"
        vm.getFeedData(
            id,
            EMPTY,
            false,
            searchViewModel.queryFlow,
            cached = {
                val curr = music.getExtension(argId) ?: current.value!!
                val query = searchViewModel.queryFlow.value
                val feed = Cached.getFeedShelf(app, curr.id, "$id-$query")
                FeedData.State(curr.id, null, feed.getOrThrow())
            }
        ) {
            val curr = music.getExtension(argId) ?: current.value!!
            val query = searchViewModel.queryFlow.value
            curr.saveInHistory(vm.app.context, query)
            val feed = Cached.savingFeed(
                app, curr, "$id-$query",
                curr.getAs<SearchFeedClient, Feed<Shelf>> { loadSearchFeed(query) }.getOrThrow()
            )
            FeedData.State(curr.id, null, feed)
        }
    }

    private val listener by lazy {
        val nav = if (argId == null) requireParentFragment() else this
        // ⚠⚠ THE onTracksClicked OVERRIDE THAT USED TO LIVE HERE IS GONE, AND ITS ABSENCE IS
        // THE POINT. It forced context = null and flattened tracks to a 1-element list so the base
        // single-track branch would route to playTrackRadio. That made the rule TRUE ON THIS SCREEN
        // ONLY, by manufacturing the inputs the branch tested for.
        // The branch now keys on FeedClickListener.isOrderedCollection(context) instead. Search's
        // FeedData.State carries item = null, so its track rows are not an ordered collection and
        // still radio - SAME BEHAVIOUR, DIFFERENT MECHANISM. Do not reintroduce this override: it
        // would discard the surrounding run, which is exactly what the rule exists to preserve.
        FeedClickListener(this@SearchFragment, nav.parentFragmentManager, nav.id)
    }

    private val feedAdapter by lazy {
        getFeedAdapter(feedData, listener)
    }
    private var swipeRefresh: SwipeRefreshLayout? = null

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) swipeRefresh?.isRefreshing = false
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentSearchBinding.bind(view)
        val recyclerView = binding.recyclerView as RecyclerView
        setupTransition(view, false, MaterialSharedAxis.Y)
        applyInsets(recyclerView, binding.appBarOutline) {
            binding.swipeRefresh.configure(it)
        }
        val uiViewModel by activityViewModel<UiViewModel>()
        observe(uiViewModel.navigationReselected) {
            if (it != 1) return@observe
            binding.quickSearchView.show()
        }
        observe(uiViewModel.navigation) {
            binding.quickSearchView.hide()
        }
        observe(
            uiViewModel.navigation.combine(feedData.backgroundImageFlow) { a, b -> a to b }
        ) { (curr, bg) ->
            if (curr != 1) return@observe
            uiViewModel.currentNavBackground.value = bg
        }
        val backCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                binding.quickSearchView.hide()
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)
        binding.quickSearchView.addTransitionListener { v, _, newState ->
            backCallback.isEnabled = v.isShowing
            // TV: when the overlay finishes collapsing, if Material's own restore-to-bar didn't land focus
            // (focus lost), put it on the search bar so the feed stays navigable — DOWN then enters the
            // freshly loaded, top-attached results. Only fires on lost focus, so it complements Material's
            // restore rather than fighting it. Phone never enters (isTv false).
            if (view.context.isTv() && newState == SearchView.TransitionState.HIDDEN &&
                view.findFocus() == null
            ) binding.recyclerView.findViewById<View>(R.id.searchBar)?.requestFocus()
        }
        applyBackPressCallback {
            if (it == STATE_EXPANDED) binding.quickSearchView.hide()
        }
        binding.quickSearchView.inflateMenu(R.menu.search_mic_menu_white)
        binding.quickSearchView.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_voice_search -> { launchVoiceSearch(); true }
                // Confirmed, matching the app's shape for irreversible single-gesture destruction
                // (MaterialAlertDialogBuilder + remove_from_library_confirm in MediaHeaderAdapter, and
                // DeletePlaylistBottomSheet). The dialog is HONEST here rather than corrective: it guards a
                // genuinely bulk action instead of apologising for a mislabelled per-item one. Read the note
                // in search_mic_menu_white.xml before moving this back onto the rows.
                R.id.menu_clear_search_history -> {
                    MaterialAlertDialogBuilder(requireContext())
                        .setMessage(R.string.clear_search_history_confirm)
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(R.string.clear) { _, _ ->
                            searchViewModel.clearSearchHistory(
                                argId, binding.quickSearchView.editText.text.toString()
                            )
                        }
                        .show()
                    true
                }
                else -> false
            }
        }
        val searchAdapter = SearchBarAdapter(searchViewModel, binding.quickSearchView) {
            launchVoiceSearch()
        }
        observe(searchViewModel.queryFlow) {
            searchAdapter.notifyItemChanged(0)
            binding.quickSearchView.setText(it)
        }
        // ⚠⚠ [CORRECTED 2026-09-13] THIS IS A VISIBILITY FAULT, NOT A GRAB FAULT, AND IT IS THE
        // SAME FAULT AS THE DRAG STOPPING TWO-THIRDS DOWN. THE THUMB IS NOT RENDERED over the card/tile
        // sections - there is nothing to grab, and the scroller declining a press on a thumb that does
        // not exist is CORRECT BEHAVIOUR, not the bug. Full mechanism at PixelFastScrollViewHelper's
        // extrapolation note: `mScrollbarEnabled = scrollOffsetRange > 0` rides the same
        // position-dependent computeVerticalScrollRange estimate, so a section that estimates the
        // content as short renders no thumb.
        // ⚠️ THE SCROLLTOUCH PROBE THAT ESTABLISHED THIS IS GONE (removed 2026-09-13), AND IT WAS
        // NOT A DEAD END - worth saying, because a deleted probe reads like one. It eliminated gesture
        // ARBITRATION and the nested CAROUSELS, and eliminating those is what left visibility as the
        // only place to look. Its answer - the scroller is reached and declines - was CORRECT, and
        // turned out to be DOWNSTREAM of the real question. What it cost was being built against a
        // symptom description nobody had checked. Its geometry finding was salvaged to
        // FastScrollerHelper's setThumbDrawable note before deletion.
        // The original note follows, superseded in its framing but accurate on what was measured.
        // ⚠⚠ OPEN FAULT - CANNOT START A THUMB GRAB OVER THE CARD/TILE SECTIONS ON THIS SCREEN.
        // WHAT WAS SEEN: on the Search landing, an INITIAL TOUCH on the fast-scroll thumb over a card/tile
        // section does not take. The thumb IS visible there and drags through those sections fine if the
        // grab BEGINS above or below them. It is the start of the gesture that fails, not visibility, and
        // not the drag. (A second, UNRELATED fault on this screen - the drag stopping two-thirds down - is
        // recorded at PixelFastScrollViewHelper's extrapolation note. Different half of the system.)
        //
        // REGISTRATION ORDER HERE IS SCROLLER-FIRST: applyInsets(recyclerView, ...) at :132 calls
        // FastScrollerHelper.applyTo inside (MainFragment:105), and the ItemTouchHelper attaches below.
        // That is the order FeedFragment's comment calls broken - but read that comment's [CORRECTED]
        // block before drawing from it: HomeFragment and LibraryFragment are scroller-first too, so the
        // three screens it cited as working exemplars all use this order. Order alone therefore explains
        // neither their working state nor this fault, and it CANNOT explain a POSITIONAL symptom in any
        // case - registration order does not vary with where the finger lands.
        //
        // ⚠️ THE CAROUSEL HYPOTHESIS IS WEAKER THAN IT LOOKS, WHICH IS WORTH RECORDING BECAUSE
        // IT IS THE OBVIOUS CANDIDATE. The card sections DO host nested horizontal RecyclerViews
        // (HorizontalListViewHolder: binding.root is the inner RecyclerView, LinearLayoutManager.HORIZONTAL)
        // - established, not in doubt. But they are BARE: grepping the whole feed package finds NO
        // onInterceptTouchEvent override, NO requestDisallowInterceptTouchEvent, NO setOnTouchListener.
        // And the OUTER RecyclerView's OnItemTouchListeners run in ITS onInterceptTouchEvent, which
        // ViewGroup dispatch runs BEFORE any child sees the DOWN - so a bare nested RecyclerView should not
        // be able to take a gesture the scroller's listener claimed. Not refuted, but it does not follow
        // from what is in the tree; something else is doing the positional part.
        //
        // ⚠️ ALPHA CONSIDERED AND RANKED BELOW IT, NOT UNTRIED: a thumb at alpha 0 (the library's
        // auto-hide) would refuse a grab while still being visible once re-shown. Dropped because AUTO-HIDE
        // IS TEMPORAL AND THIS SYMPTOM IS POSITIONAL - it would refuse a grab anywhere, not specifically
        // over cards. Note also that FixOnItemTouchListenerRecyclerView, named in the record as the gate
        // that would close this question, IS NOT IN THIS TREE - it exists only as a mention in
        // PixelFastScrollViewHelper's addOnTouchEventListener note, describing something the library ships.
        //
        // ⚠️ AND ONE WRONG TURN, RECORDED SO IT IS NOT REPEATED: applyTo is called with
        // traceTag = "main" here, and that was read as evidence the thumb belonged to a DIFFERENT
        // RecyclerView (MainFragment's). It does not - applyInsets is a SHARED companion extension and the
        // tag is a label on the helper, not on a view. This screen has ONE RecyclerView with TWO listeners.
        // Second time in this codebase a NAME has been read as describing a function it does not perform;
        // the other is bufferBar, whose only surviving job is to draw the static unplayed rail.
        getTouchHelper(listener).attachToRecyclerView(recyclerView)
        configureGridLayout(
            recyclerView,
            feedAdapter.withLoading(this, HeaderAdapter(this), searchAdapter),
        )
        (recyclerView as? TvAwareRecyclerView)?.navRailView =
            requireActivity().findViewById(R.id.navRailContainer)
        swipeRefresh = binding.swipeRefresh
        binding.swipeRefresh.run {
            setOnRefreshListener { feedData.refresh() }
        }
        observeUserRefresh(binding.swipeRefresh, feedData.isRefreshingFlow)
        binding.quickSearchView.editText.setText(searchViewModel.queryFlow.value)
        // TV: give the on-screen keyboard a concrete submit action so its "done"/OK reliably fires the
        // editor-action listener below (hide + run the search). Phone keeps the SearchView's default action.
        if (view.context.isTv())
            binding.quickSearchView.editText.imeOptions = EditorInfo.IME_ACTION_SEARCH
        binding.quickSearchView.editText.doOnTextChanged { text, _, _, _ ->
            searchViewModel.quickSearch(argId, text.toString())
        }
        binding.quickSearchView.editText.setOnEditorActionListener { textView, _, _ ->
            val query = textView.text.toString()
            binding.quickSearchView.hide()
            searchViewModel.queryFlow.value = query
            false
        }
        val quickSearchAdapter = QuickSearchAdapter(object : QuickSearchAdapter.Listener {
            override fun onClick(item: QuickSearchAdapter.Item, transitionView: View) {
                when (val actualItem = item.actual) {
                    is QuickSearchItem.Query -> {
                        binding.quickSearchView.editText.run {
                            setText(actualItem.query)
                            onEditorAction(imeOptions)
                        }
                    }

                    is QuickSearchItem.Media -> {
                        val extensionId = item.extensionId
                        listener.onMediaClicked(transitionView, extensionId, actualItem.media, null)
                    }
                }
            }

            override fun onLongClick(item: QuickSearchAdapter.Item, transitionView: View) =
                when (val actualItem = item.actual) {
                    // Was `onDeleteClick(item); true` — removed 2026-09-10 with the per-row ✕. Long-press
                    // promising per-item removal was the same lie in a different gesture: it reached the
                    // same account-wide wipe. Returns false (unhandled) so a Query row simply has no
                    // long-press action; clearing is on the overlay's overflow menu.
                    is QuickSearchItem.Query -> false

                    is QuickSearchItem.Media -> {
                        val extensionId = item.extensionId
                        listener.onMediaLongClicked(
                            transitionView, extensionId, actualItem.media,
                            null, null, -1
                        )
                        true
                    }
                }

            override fun onInsert(item: QuickSearchAdapter.Item) {
                binding.quickSearchView.editText.run {
                    setText(item.actual.title)
                    setSelection(length())
                }
            }
        }, onFirstRowUp = if (view.context.isTv())
            ({ binding.quickSearchView.editText.requestFocus() }) else null)

        binding.quickSearchRecyclerView.adapter = quickSearchAdapter
        observe(uiViewModel.combined) { insets ->
            binding.quickSearchRecyclerView.updatePaddingRelative(start = insets.start)
            // Rail parity for the SearchView's OWN header (the toolbar holding the back/hint icon + text
            // field), which is otherwise full-width and sits under the left nav rail. Uses marginStart, NOT
            // padding: Material's setUpToolbarInsetListener calls setPadding(l,t,r,b) on this toolbar on every
            // window-insets dispatch (from system-bar/cutout insets, which exclude our rail) and would clobber
            // a start padding — it never touches margins. Phone-safe and consistent with the suggestions inset
            // above: insets.start is 0 on phone portrait (bottom nav), = rail width on phone-landscape / TV.
            binding.quickSearchView.toolbar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                marginStart = insets.start
            }
        }
        observe(searchViewModel.quickFeed) { list ->
            // The id arrives WITH each item (see `SearchViewModel.quickFeed`) rather than from a field
            // here - see the note at the removed `extensionId` field above.
            quickSearchAdapter.submitList(list.map { (extId, item) ->
                QuickSearchAdapter.Item(extId, item)
            })
        }
    }
}