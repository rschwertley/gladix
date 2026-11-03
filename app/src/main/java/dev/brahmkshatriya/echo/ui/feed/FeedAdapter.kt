package dev.brahmkshatriya.echo.ui.feed

import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognizerIntent
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.paging.LoadState
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.databinding.ItemLoadingBinding
import dev.brahmkshatriya.echo.playback.PlayerState
import dev.brahmkshatriya.echo.ui.common.GridAdapter
import dev.brahmkshatriya.echo.ui.common.PagedSource
import dev.brahmkshatriya.echo.ui.feed.FeedData.FeedTab
import dev.brahmkshatriya.echo.ui.feed.FeedLoadingAdapter.Companion.createListener
import dev.brahmkshatriya.echo.ui.feed.FeedType.Enum.Category
import dev.brahmkshatriya.echo.ui.feed.FeedType.Enum.CategoryGrid
import dev.brahmkshatriya.echo.ui.feed.FeedType.Enum.Header
import dev.brahmkshatriya.echo.ui.feed.FeedType.Enum.HorizontalList
import dev.brahmkshatriya.echo.ui.feed.FeedType.Enum.Media
import dev.brahmkshatriya.echo.ui.feed.FeedType.Enum.MediaGrid
import dev.brahmkshatriya.echo.ui.feed.FeedType.Enum.Video
import dev.brahmkshatriya.echo.ui.feed.FeedType.Enum.VideoHorizontal
import dev.brahmkshatriya.echo.ui.feed.viewholders.CategoryViewHolder
import dev.brahmkshatriya.echo.ui.feed.viewholders.FeedViewHolder
import dev.brahmkshatriya.echo.ui.feed.viewholders.HeaderViewHolder
import dev.brahmkshatriya.echo.ui.feed.viewholders.HorizontalListViewHolder
import dev.brahmkshatriya.echo.ui.feed.viewholders.MediaGridViewHolder
import dev.brahmkshatriya.echo.ui.feed.viewholders.MediaViewHolder
import dev.brahmkshatriya.echo.ui.feed.viewholders.VideoHorizontalViewHolder
import dev.brahmkshatriya.echo.ui.feed.viewholders.VideoViewHolder
import dev.brahmkshatriya.echo.ui.player.PlayerViewModel
import dev.brahmkshatriya.echo.utils.ContextUtils.observe
import dev.brahmkshatriya.echo.utils.ui.AnimationUtils.animatedWithAlpha
import dev.brahmkshatriya.echo.utils.ui.scrolling.ScrollAnimPagingAdapter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import org.koin.androidx.viewmodel.ext.android.activityViewModel
import java.lang.ref.WeakReference

class FeedAdapter(
    private val viewModel: FeedData,
    private val listener: FeedClickListener,
    private val takeFullScreen: Boolean = false,
    // Resolved once at construction (see getFeedAdapter), never per getSpanSize call.
    private val isTV: Boolean = false,
    private val phoneSingleColumn: Boolean = false,
) : ScrollAnimPagingAdapter<FeedType, FeedViewHolder<*>>(DiffCallback), GridAdapter {

    object DiffCallback : DiffUtil.ItemCallback<FeedType>() {
        override fun areContentsTheSame(oldItem: FeedType, newItem: FeedType) = oldItem == newItem
        override fun areItemsTheSame(oldItem: FeedType, newItem: FeedType): Boolean {
            if (oldItem.extensionId != newItem.extensionId) return false
            if (newItem.type != oldItem.type) return false
            if (oldItem.id != newItem.id) return false
            return true
        }
    }

    private val viewPool = RecyclerView.RecycledViewPool()
    // ⚠⚠ peek, NOT getItem, AND THE DIFFERENCE IS AN ANR. Build 1107 ANR'd on the fast-scroller
    // drag path with this call at the bottom of the stack. THE CHAIN: a thumb drag runs
    // PixelFastScrollViewHelper.scrollTo -> nestedScrollBy -> fill -> layoutChunk ->
    // getItemDecorInsetsForChild -> GridAdapter.VerticalSpacingItemDecoration.getItemOffsets, which
    // calls GridLayoutManager.SpanSizeLookup.getSpanGroupIndex. THAT METHOD WALKS: its loop is
    // `for (i = start; i < adapterPosition; i++) getSpanSize(i)`, and `start` is 0 whenever the span
    // cache is cold. So ONE child's decoration cost is O(position) getSpanSize calls - each routed
    // through TWO GridAdapter.Concat levels on a media-details page - and each landing HERE.
    // ⚠️ getItem IS NOT A LOOKUP. AsyncPagingDataDiffer.getItem does two StateFlow CAS updates
    // and writes lastAccessedIndex, then PagingDataPresenter.get does two MORE and calls
    // `hintReceiver.processHint(pageStore.createAccessHintForIndex(index))` - a PREFETCH SIGNAL. At
    // O(position) calls per child that is thousands of spurious access hints per layout, which also
    // CLOSES A LOOP: a hint triggers a page load, the insert fires onItemsAdded, GridLayoutManager
    // clears the span-group cache, and the next walk starts from 0 again.
    // peek returns the identical value with none of it (paging 3.5.1, AsyncPagingDataDiffer:484).
    //
    // ⚠⚠ IT IS A DROP-IN ONLY BECAUSE enablePlaceholders = false (PagedSource.kt). THIS IS THE
    // PART THAT WILL BE REDISCOVERED THE HARD WAY. With placeholders OFF, every index in
    // [0, itemCount) is a loaded item, so peek and getItem return the same non-null object. TURN
    // PLACEHOLDERS ON AND THEY DIVERGE: peek returns null for an unloaded slot where getItem would
    // have triggered the load - and the `?: 0` fallback below IS NOT NEUTRAL. Ordinal 0 is
    // FeedType.Enum.Header, and getSpanSize maps Header -> count, i.e. FULL WIDTH. So enabling
    // placeholders would silently render every unloaded row as a full-width header, with no crash
    // and no log. If placeholders are ever enabled, this fallback must be revisited FIRST.
    //
    // ⚠️ onBindViewHolder MUST KEEP USING getItem - do not "finish the job" there. That call
    // fires a hint for an item actually being displayed, which is the signal Paging is designed
    // around. Removing the walk's hints makes that signal MORE accurate, not less: the walk was
    // rewriting lastAccessedIndex thousands of times per layout and leaving it at position-1.
    override fun getItemViewType(position: Int) =
        runCatching { peek(position)!! }.getOrNull()?.type?.ordinal ?: 0

    /**
     * ⚠️ THE NAME IS NARROWER THAN THE JOB. Its only reader is
     * [getAllTracks], where it means "this feed groups its track rows into one playable run" -
     * false makes a tap queue THAT ROW ALONE. The visible Play/Shuffle buttons are a separate
     * path (ButtonsAdapter reads Feed.Buttons directly), so this being true while they are
     * momentarily not drawn is correct, not a desync.
     */
    private var isPlayButtonShown = false
    private fun FeedType.toTrack(): Track? = when (this) {
        is FeedType.Media -> item as? Track
        is FeedType.MediaGrid -> item as? Track
        is FeedType.Video -> item
        else -> null
    }

    fun getAllTracks(feed: FeedType): Pair<List<Track>, Int> {
        if (!isPlayButtonShown) return listOfNotNull(feed.toTrack()) to 0
        val list = snapshot().mapNotNull { it }
        // ⚠⚠ THE TAPPED ROW BY IDENTITY FIRST, ITS ID ONLY AS A FALLBACK (2026-10-29). FeedType.Media's
        // id is the ITEM's id, so a song listed twice - a playlist duplicate, or a two-artist song under
        // the Artists sort, which lists it once per artist - always resolved to its FIRST copy, and a tap
        // on the second started the queue at the first. The bound row is the snapshot's own instance in
        // the normal case; if it is not (a re-render between bind and tap), the id lookup is exactly the
        // old behaviour.
        val index = list.indexOfFirst { it === feed }.takeIf { it >= 0 }
            ?: list.indexOfFirst { it.id == feed.id }
        if (index == -1) return listOf<Track>() to -1
        val from = list.take(index).indexOfLast { it.type != feed.type }
        val to = list.drop(index + 1).indexOfFirst { it.type != feed.type }
        val feeds = list.subList(from + 1, if (to == -1) list.size else index + to + 1)
        val tracks = feeds.mapNotNull { it.toTrack()?.marked() }
        // The row's place in the run, counted in tracks - not an id lookup, for the same reason.
        val inRun = index - (from + 1)
        val newIndex = if (feeds[inRun].toTrack() == null) -1
        else feeds.subList(0, inRun).count { it.toTrack() != null }
        return tracks to newIndex
    }

    /**
     * ⚠⚠ THE DECLARED LIST IN THE ORDER THE SCREEN SHOWS IT, WHICH IS WHAT A TAP AND PLAY/SHUFFLE
     * QUEUE. GitHub #4 follow-up (2026-10-29): Feed.Buttons.customTrackList is in the EXTENSION'S
     * order, so with "Reversed" on a tap in Library -> Tracks queued the rows above it. See
     * FeedData.displayOrder for what each case means and why it is a record of the render.
     * ⚠️ Rebuilt RETURNS THE SCREEN AND NOTHING ELSE: a search or a date sort hides tracks, and
     * queuing them would play songs that are not in the list. NOT de-duplicated - the Artists sort
     * lists a two-artist song twice, and FeedClickListener.onTracksClicked maps the tapped COPY by
     * occurrence, so the queue starts at the row that was tapped.
     * ⚠️ Reversed APPENDS what the screen does not hold: on Library -> All the tracks are a
     * carousel holding only the start of the list, so the rest follows in the extension's order. On
     * Library -> Tracks every song is its own row and nothing is appended.
     */
    private fun inDisplayOrder(declared: List<Track>): List<Track> = when (viewModel.displayOrder) {
        FeedData.DisplayOrder.Source -> declared
        FeedData.DisplayOrder.Rebuilt -> getAllTracks()
        FeedData.DisplayOrder.Reversed -> getAllTracks().let { shown ->
            val ids = shown.mapTo(HashSet()) { it.id }
            shown + declared.filter { it.id !in ids }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FeedViewHolder<*> {
        val type = FeedType.Enum.entries[viewType]
        return when (type) {
            Header -> HeaderViewHolder(parent, listener)
            HorizontalList -> HorizontalListViewHolder(parent, listener, viewPool)
            Category -> CategoryViewHolder(parent, listener)
            CategoryGrid -> CategoryViewHolder(parent, listener)
            Media -> MediaViewHolder(parent, listener, ::getAllTracks)
            MediaGrid -> MediaGridViewHolder(parent, listener, ::getAllTracks)
            Video -> VideoViewHolder(parent, listener, ::getAllTracks)
            VideoHorizontal -> VideoHorizontalViewHolder(parent, listener, ::getAllTracks)
        }
    }

    // The previous tab's rows while a switched-to tab is still loading past the grace (see withLoading's tab-switch
    // note). INVISIBLE keeps the rows laid out but untappable and unfocusable; set through a payload rebind so the
    // RecyclerView's cached holders are covered too, not only the attached ones.
    private var rowsHidden = false
        set(value) {
            if (field == value) return
            field = value
            notifyItemRangeChanged(0, itemCount, ROWS_HIDDEN)
        }

    override fun onBindViewHolder(holder: FeedViewHolder<*>, position: Int, payloads: MutableList<Any>) {
        holder.itemView.isInvisible = rowsHidden
        if (payloads.isNotEmpty() && payloads.all { it == ROWS_HIDDEN }) return
        // A marks rebind binds the row's content only: ScrollAnimPagingAdapter's bind starts a translation
        // animation from the last scroll delta, which on rows already on screen would read as a jolt. And only the
        // row types a mark can reach - a HorizontalList rebind would reset its carousel (scrollToPosition(0)).
        if (payloads.isNotEmpty() && payloads.all { it == ROWS_HIDDEN || it == PLAYABLE_MARKS }) {
            if (holder is MediaViewHolder || holder is MediaGridViewHolder ||
                holder is VideoViewHolder || holder is VideoHorizontalViewHolder
            ) bindRow(holder, position)
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    /**
     * ⚠⚠ DEAD-SONG MARKS APPLIED IN PLACE (2026-11-02), by track id, from MediaDetailsViewModel.playableMarks. The
     * Deezer check now finishes after the page shows, and reloading the feed to show its result would blank it for
     * a frame and lose the scroll position (Cached.tracksRevalidated's note) - the flash just removed from Library
     * tab switches. Instead the rows already bound are re-bound with the mark: a payload notify, so cached holders
     * are covered too and the RecyclerView keeps its position. Only a Yes row takes a mark; a row's own non-Yes
     * verdict stands. Read by the bind below, by both getAllTracks (Play, Shuffle and row taps queue what is
     * dimmed) and so by every viewholder's unplayable-tap gate, which reads the bound item.
     */
    var playableMarks: Map<String, Track.Playable> = emptyMap()
        set(value) {
            if (field == value) return
            field = value
            notifyItemRangeChanged(0, itemCount, PLAYABLE_MARKS)
        }

    private fun Track.marked(): Track =
        if (isPlayable != Track.Playable.Yes) this
        else playableMarks[id]?.let { copy(isPlayable = it) } ?: this

    private fun FeedType.marked(): FeedType = if (playableMarks.isEmpty()) this else when (this) {
        is FeedType.Media -> (item as? Track)?.marked()?.takeIf { it !== item }?.let { copy(item = it) } ?: this
        is FeedType.MediaGrid -> (item as? Track)?.marked()?.takeIf { it !== item }?.let { copy(item = it) } ?: this
        is FeedType.Video -> item.marked().takeIf { it !== item }?.let { copy(item = it) } ?: this
        else -> this
    }

    // Whether the rows hold the WHOLE list: the last page has loaded and there is no next one. ButtonsAdapter queues
    // the rows only then (2026-11-02); a partial window (Jun 1 - Oct 24 played only what was loaded) keeps the item
    // path, which loads the rest.
    private var isFullyLoaded = false

    override fun onBindViewHolder(holder: FeedViewHolder<*>, position: Int) {
        super.onBindViewHolder(holder, position)
        bindRow(holder, position)
    }

    private fun bindRow(holder: FeedViewHolder<*>, position: Int) {
        val feed = runCatching { getItem(position) }.getOrNull()?.marked() ?: return
        when (holder) {
            is HeaderViewHolder -> holder.bind(feed as FeedType.Header)
            is CategoryViewHolder -> holder.bind(feed as FeedType.Category)
            is MediaViewHolder -> holder.bind(feed as FeedType.Media)
            is MediaGridViewHolder -> holder.bind(feed as FeedType.MediaGrid)
            is VideoViewHolder -> holder.bind(feed as FeedType.Video)
            is VideoHorizontalViewHolder -> holder.bind(feed as FeedType.Video)
            is HorizontalListViewHolder -> {
                holder.bind(feed as FeedType.HorizontalList)
                viewModel.visibleScrollableViews[position] = WeakReference(holder)
                holder.layoutManager.apply {
                    val state = viewModel.layoutManagerStates[position]
                    if (state != null) onRestoreInstanceState(state)
                    else scrollToPosition(0)
                }
            }
        }
        holder.onCurrentChanged(current)
    }

    override fun onViewRecycled(holder: FeedViewHolder<*>) {
        if (holder is HorizontalListViewHolder) saveScrollState(holder) {
            viewModel.visibleScrollableViews.remove(holder.bindingAdapterPosition)
        }
    }

    override fun onViewAttachedToWindow(holder: FeedViewHolder<*>) {
        holder.onCurrentChanged(current)
    }

    class LoadingViewHolder(
        parent: ViewGroup,
        val binding: ItemLoadingBinding = ItemLoadingBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        ),
    ) : FeedLoadingAdapter.ViewHolder(binding.root) {
        init {
            binding.textView.isVisible = false
        }

        override fun bind(loadState: LoadState) {
            binding.root.alpha = 0f
            binding.root.animatedWithAlpha(500)
        }
    }

    fun getAllTracks() = snapshot().mapNotNull {
        when (it) {
            is FeedType.Media -> listOfNotNull(it.item as? Track)
            is FeedType.MediaGrid -> listOfNotNull(it.item as? Track)
            is FeedType.Video -> listOf(it.item)
            is FeedType.HorizontalList -> it.shelf.list.filterIsInstance<Track>()
            else -> null
        }
    }.flatten().map { it.marked() }

    // showTabsAndButtons = false (2026-10-31, the player's Info tab only): the tab and buttons rows are left out of the
    // returned Concat. Their observers still run - the buttons observer also sets isPlayButtonShown and declaredTracks,
    // which taps rely on - only the two (empty there) rows and the grid spacing each one took are gone.
    // [CORRECTED 2026-11-01] Not "only": the buttons row's first bind was also what started the feed (see the
    // searchPrimed note below). 1160 shipped without it and the Info tab showed "So empty" on every song.
    // [CORRECTED 2026-11-01] showTabsAndButtons is now showButtons, and the tab row is back on the Info tab whenever
    // the feed HAS tabs (any extension - "never shows tabs" was checked for Deezer only). It is left out only while
    // there are none (TabsAdapter.collapseWhenEmpty), so the 1160 spacing applies to tab-less feeds. The buttons row
    // stays out there (sort/filter does not belong on the Info tab, Jun 7).
    fun withLoading(fragment: Fragment, vararg before: GridAdapter, initialButtons: Boolean = false, onCreatePlaylistClick: (() -> Unit)? = null, onEditPlaylistClick: (() -> Unit)? = null, editPlaylistVisible: Flow<Boolean>? = null, showButtons: Boolean = true): GridAdapter.Concat {
        // Starts the tab-switch grace; assigned at the load-state listener below. TabsAdapter calls this only for a tap on a DIFFERENT tab.
        var onTabSwitch: () -> Unit = {}
        val tabs = TabsAdapter<FeedTab>({ tab.title }, collapseWhenEmpty = !showButtons) { view, index, tab ->
            onTabSwitch()
            listener.onTabSelected(view, tab.feedId, tab.extensionId, index)
        }
        fragment.observe(viewModel.tabsFlow) { tabs.data = it }
        var buttonsAdapter: ButtonsAdapter? = null
        // Last declared track list for this feed, read by the tap path AT TAP TIME rather than
        // captured when it arrives - see FeedClickListener.declaredTracks.
        var declaredTracks: List<Track>? = null
        listener.declaredTracks = { declaredTracks?.let(::inDisplayOrder) }
        /**
         * ⚠⚠ THE LAST NON-NULL Buttons, KEPT ACROSS A NULL EMISSION, BECAUSE A
         * NULL ONE USED TO QUEUE A SINGLE SONG. FeedData.buttonsFlow ends in
         * `?: return@combine null` and BOTH halves of its dataFlow `emit(null)` on every restart,
         * so a reload hands this observer a null while the RecyclerView still shows the rows the
         * PREVIOUS emission produced - the flag clear is a synchronous assignment, the row removal
         * goes through PagedSource and submitData's async diff.
         * ⚠⚠ AND IT TOOK BOTH FIELDS TO DO IT, WHICH IS WHY ONE ASSIGNMENT
         * CAUSED IT. With [isPlayButtonShown] false, getAllTracks returns `listOfNotNull(track) to
         * 0`; with declaredTracks null, FeedClickListener's declared branch cannot rescue it; so the
         * fallback setQueue got a ONE-ELEMENT list. Either field alone is harmless - a declared list
         * survives a false flag, and a true flag queues the whole visible run without a declared
         * list. They were cleared together because they come from the same `it?.buttons`.
         * ⚠️ THE WINDOW IS SECONDS, NOT FRAMES, ON THE LIKED LIST.
         * DeezerApi.tracksAvailability runs one pipe request per 100 ids SEQUENTIALLY, so a
         * 1000-track favourites list is ~10 round trips before the loaded feed exists. Measured at
         * 0.3-0.5 s each, that is 3-5 s of reload with rows already on screen and scrollable.
         * ⚠️ NOT CARRIED ACROSS TABS: the retention is dropped whenever the
         * selected tab index changes, below. A different tab is a different list, and answering for
         * content it never described is the one thing worse than answering with nothing. A feed with
         * no tabs emits index -1 once and keeps its retention, which is what playlist and album
         * pages need.
         * ⚠️ THE ButtonsAdapter IS DELIBERATELY NOT GIVEN THE RETAINED
         * VALUE. FeedData.Buttons also carries sortState, and showing a retained sort chip through a
         * reload would trade a flicker for a wrong label. The visible row keeps its existing
         * behaviour; only the tap path reads through the retention.
         */
        var retainedButtons: FeedData.Buttons? = null
        var retainedTabIndex: Int? = null
        fragment.observe(viewModel.selectedTabIndexFlow) { index ->
            tabs.selected = index
            if (retainedTabIndex != index) {
                retainedTabIndex = index
                retainedButtons = null
                isPlayButtonShown = false
                declaredTracks = null
            }
        }
        val speechLauncher = fragment.registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                    ?.firstOrNull()?.let { text ->
                        viewModel.searchToggled = true
                        viewModel.searchQuery = text
                        buttonsAdapter?.notifyItemChanged(0)
                        viewModel.onSearchClicked()
                    }
            }
        }
        val buttons = ButtonsAdapter(viewModel, listener, ::getAllTracks, { isFullyLoaded }, onCreatePlaylistClick, onEditPlaylistClick) {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            }
            runCatching { speechLauncher.launch(intent) }
        }
        buttonsAdapter = buttons
        // ⚠️ THE PENCIL SHOWS ONLY WHEN TAPPING IT CAN OPEN THE EDITOR (2026-10-30). Since 14f8fd81 (Jun 1) it
        // showed whenever onEditPlaylistClick was passed - on every media page, albums and other users'
        // playlists included - and isEditable / PlaylistEditClient were checked only inside the tap, so it
        // was a visible no-op there. Seen on the Deezer Favorite tracks page, which loadPlaylist marks
        // non-editable. The caller supplies the same test the tap runs; without a flow it stays as before.
        if (editPlaylistVisible != null) {
            buttons.showEditPlaylist = false
            fragment.observe(editPlaylistVisible) { buttons.showEditPlaylist = it }
        }
        if (initialButtons) buttons.buttons = FeedData.Buttons("", "", Feed.Buttons(showPlayAndShuffle = true))
        // ⚠⚠ WITHOUT THE BUTTONS ROW, NOTHING STARTS THE FEED (2026-11-01, 1160 regression: the Info tab showed
        // "So empty" for every song). FeedData's cached/loadedFeedTypeFlow combine searchClickedFlow, a
        // MutableSharedFlow with no initial value, so pagingFlow emits nothing until it has emitted once. The only
        // thing that did that on open is ButtonsAdapter's FIRST BIND: item_feed_buttons inflates searchToggleButton
        // with android:checked="true", bind sets isChecked = searchToggled (false), and the checked listener calls
        // onSearchClicked (MaterialButton.setCheckedInternal notifies only on a change - Material 1.14.0, decoded).
        // A Concat without the row never binds it. So that one emission is made here instead, on the first loaded
        // buttons value - after getFeedAdapter's pagingFlow observer, registered earlier, has subscribed.
        // Media pages keep the row and are unchanged.
        var searchPrimed = showButtons
        fragment.observe(viewModel.buttonsFlow) {
            if (!searchPrimed && it != null) {
                searchPrimed = true
                viewModel.onSearchClicked()
            }
            buttons.buttons = it
            // See the retention note above: a null emission keeps the last description of the rows
            // that are still drawn; a non-null one always replaces it.
            if (it != null) retainedButtons = it
            val effective = it ?: retainedButtons
            isPlayButtonShown = effective?.buttons?.showPlayAndShuffle == true
            // The same Feed.Buttons the Play/Shuffle buttons read, handed to the tap path: a
            // track the extension declared as part of a collection queues the whole collection
            // instead of starting a radio. See FeedClickListener.declaredTracks. Set here and not
            // only where isPlayButtonShown is, because a feed may declare the list WITHOUT
            // showing the buttons - Library -> All does exactly that.
            declaredTracks = effective?.buttons?.customTrackList
        }
        val loadStateListener = fragment.createListener { retry() }
        val header = FeedLoadingAdapter(loadStateListener) { LoadingViewHolder(it) }
        val footer = FeedLoadingAdapter(loadStateListener) { LoadingViewHolder(it) }
        val empty = EmptyAdapter()
        fragment.observe(
            loadStateFlow.combine(viewModel.shouldShowEmpty) { a, b -> a to b }
        ) { (loadStates, shouldShowEmpty) ->
            val isEmpty =
                shouldShowEmpty && itemCount == 0 && loadStates.append is LoadState.NotLoading
            empty.loadState = if (isEmpty) LoadState.Loading else LoadState.NotLoading(false)
        }
        // Inline loading row hidden after the first load on purpose (Jun 13, "Detail Page Second-Load Spinner Suppression": stops the cache-then-network flash); not shown for tab changes either (decided 2026-11-02).
        // [CORRECTED 2026-11-02] "Not shown for tab changes" was not what the code did: a tab change first builds a PagedSource with neither side ready, whose refresh is Error(LoadingException), which FeedLoadingAdapter draws as the same spinner, and only Loading is masked here. Now handled by the tab-switch grace below; every other reload is unchanged.
        var hasEverLoaded = false
        /**
         * ⚠️ TAB-SWITCH GRACE (2026-11-02, Library tab-switch flash). Only a tap on a different tab arms it
         * ([onTabSwitch]); reloads, searches, extension/account switches and pull-to-refresh never do, and keep the
         * rule above exactly. For [TAB_SWITCH_GRACE_MS] after the tap, Error(LoadingException) is masked too, so a tab
         * whose first page comes from the disk cache shows no spinner row. Still waiting when the grace ends (a tab
         * never opened, so the network) - the spinner row shows as before, AND the previous tab's rows are hidden
         * ([rowsHidden]: invisible, so neither tappable nor focusable) until the new tab's first page arrives, so the
         * spinner never sits above the old rows. A real error after a tap hides them the same way.
         * [switchSawPending]: the switch ends only on a NotLoading that FOLLOWS a pending state, so a late update from
         * the previous tab's list cannot end it before the new one starts.
         */
        var switchedAt = 0L
        var switchSawPending = false
        var lastRefresh: LoadState = LoadState.NotLoading(false)
        val graceHandler = Handler(Looper.getMainLooper())
        fun isWaiting(state: LoadState) = state is LoadState.Error && state.error is PagedSource.LoadingException
        // ⚠️ TV: a row going INVISIBLE while it holds focus would CLEAR focus (View.setFlags, API 34), and lost or
        // jumping focus on the Library tabs took many builds to fix (TabsAdapter's OK DOWN/UP note). So focus in one
        // of these rows (D-pad Down right after OK) is handed to the selected tab button first. Phone: no row holds
        // focus in touch mode, so this does nothing.
        fun hideOldRows() {
            if (rowsHidden) return
            val rv = recyclerView
            val focused = rv?.focusedChild
            if (rv != null && focused != null && rv.getChildViewHolder(focused)?.bindingAdapter === this) {
                tabs.parent?.getChildAt(tabs.selected)?.takeIf { tabs.selected >= 0 }?.requestFocus()
            }
            rowsHidden = true
        }
        fun applyRefresh() {
            val refresh = lastRefresh
            val switching = switchedAt != 0L
            val inGrace = switching && SystemClock.uptimeMillis() - switchedAt < TAB_SWITCH_GRACE_MS
            header.loadState = when {
                hasEverLoaded && refresh is LoadState.Loading -> LoadState.NotLoading(false)
                inGrace && isWaiting(refresh) -> LoadState.NotLoading(false)
                else -> refresh
            }
            if (switching && !inGrace && refresh is LoadState.Error) hideOldRows()
        }
        val graceEnd = Runnable { applyRefresh() }
        onTabSwitch = {
            if (hasEverLoaded) {
                switchedAt = SystemClock.uptimeMillis()
                switchSawPending = false
                graceHandler.removeCallbacks(graceEnd)
                graceHandler.postDelayed(graceEnd, TAB_SWITCH_GRACE_MS)
            }
        }
        addLoadStateListener { loadStates ->
            val refresh = loadStates.refresh
            lastRefresh = refresh
            if (switchedAt != 0L) when {
                refresh is LoadState.Loading || isWaiting(refresh) -> switchSawPending = true
                refresh is LoadState.Error -> if (SystemClock.uptimeMillis() - switchedAt < TAB_SWITCH_GRACE_MS) {
                    // A real error inside the grace: shown at once, and the old rows go with it.
                    switchedAt = 0L
                    graceHandler.removeCallbacks(graceEnd)
                    hideOldRows()
                }
                switchSawPending -> {
                    switchedAt = 0L
                    graceHandler.removeCallbacks(graceEnd)
                }
            }
            if (refresh is LoadState.NotLoading) {
                hasEverLoaded = true
                if (switchedAt == 0L) rowsHidden = false
            }
            applyRefresh()
            footer.loadState = loadStates.append
        }
        return if (showButtons) GridAdapter.Concat(*before, tabs, buttons, header, empty, this, footer)
        else GridAdapter.Concat(*before, tabs, header, empty, this, footer)
    }

    override val adapter = this
    override fun getSpanSize(position: Int, width: Int, count: Int) =
        when (FeedType.Enum.entries[getItemViewType(position)]) {
            Header, HorizontalList -> count
            Category, Media, Video -> when {
                takeFullScreen -> count                  // checked first: full width on phone AND TV
                // TV: pin to 2 columns regardless of feed width. span = count/2 yields exactly 2 tiles per
                // row for any even count (configureGridLayout forces even), same idiom as CategoryGrid below.
                // Was 2.coerceAtMost(count) = count/2-ish columns, which drifted 1<->2 as the width-derived
                // spanCount crossed the floor/even boundary. count==1 (feed too narrow) degrades to 1 column.
                isTV -> (count / 2).coerceAtLeast(1)
                phoneSingleColumn -> count               // sw<600dp: full width -> 1 column
                else -> 2.coerceAtMost(count)            // sw>=600dp tablet: today's behavior verbatim
            }
            // Pin category-grid previews to 2 columns regardless of device width: span = count/2
            // yields exactly 2 tiles per row for any even count (Home/Search use even=true), so the
            // 6-card preview is always 3 rows of 2 on every device. coerceAtLeast(1) guards count==1.
            CategoryGrid -> (count / 2).coerceAtLeast(1)
            MediaGrid, VideoHorizontal -> 1
        }

    // peek for the same reason as getItemViewType - see the note there. THIS IS THE SECOND ROUTE
    // FROM THE SAME getItemOffsets, and changing only the other one would have left it firing
    // access hints on the hot path. Once per child rather than O(position), so it was never the
    // ANR, but it is the same call on the same drag and they move together.
    override fun isSectionHeader(position: Int) =
        runCatching { peek(position) }.getOrNull()?.type == Header


    private fun clearState() {
        viewModel.layoutManagerStates.clear()
        viewModel.visibleScrollableViews.clear()
    }

    private fun saveScrollState(
        holder: HorizontalListViewHolder, block: ((HorizontalListViewHolder) -> Unit)? = null,
    ) = runCatching {
        val layoutManagerStates = viewModel.layoutManagerStates
        layoutManagerStates[holder.bindingAdapterPosition] =
            holder.layoutManager.onSaveInstanceState()
        block?.invoke(holder)
    }

    fun saveState() {
        viewModel.visibleScrollableViews.values.forEach { item ->
            item.get()?.let { saveScrollState(it) }
        }
        viewModel.visibleScrollableViews.clear()
    }

    init {
        addLoadStateListener {
            if (it.refresh == LoadState.Loading) clearState()
            isFullyLoaded = it.refresh is LoadState.NotLoading && it.append.endOfPaginationReached
        }
    }

    private var current: PlayerState.Current? = null
    fun onCurrentChanged(current: PlayerState.Current?) {
        this.current = current
        onEachViewHolder { onCurrentChanged(current) }
    }

    companion object {
        // See withLoading's tab-switch note: long enough for a disk-cached first page, short enough to read as instant.
        private const val TAB_SWITCH_GRACE_MS = 300L
        private val ROWS_HIDDEN = Any()
        private val PLAYABLE_MARKS = Any()

        fun Fragment.getFeedAdapter(
            viewModel: FeedData,
            listener: FeedClickListener,
            takeFullScreen: Boolean = false,
        ): FeedAdapter {
            val adapter = getUnboundFeedAdapter(viewModel, listener, takeFullScreen)
            observe(viewModel.pagingFlow) {
                adapter.saveState()
                adapter.submitData(it)
            }
            return adapter
        }

        /**
         * The same adapter as [getFeedAdapter] - rows, clicks, span sizes, now-playing state - WITHOUT the
         * subscription to [viewModel]'s paging flow, for a caller that submits its own rows (2026-10-31, the
         * player Info tab's fixed album/artists section, see MediaDetailsViewModel.fixedTrackRows). [viewModel]
         * still backs the scroll-state maps the adapter keeps. getFeedAdapter is this plus the subscription,
         * so it is unchanged.
         */
        fun Fragment.getUnboundFeedAdapter(
            viewModel: FeedData,
            listener: FeedClickListener,
            takeFullScreen: Boolean = false,
        ): FeedAdapter {
            val playerViewModel by activityViewModel<PlayerViewModel>()
            val context = requireContext()
            val isTV = (context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager)
                .currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
                context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
            val phoneSingleColumn = context.resources.getBoolean(R.bool.feed_phone_single_column)
            val adapter = FeedAdapter(
                viewModel, listener, takeFullScreen, isTV, phoneSingleColumn
            )
            observe(playerViewModel.playerState.current) { adapter.onCurrentChanged(it) }
            return adapter
        }

        // ⚠⚠ THE SWIPE NEVER "COMPLETES" AS FAR AS ItemTouchHelper IS CONCERNED, AND THAT IS THE FIX
        // FOR ROWS LEFT BLANK (2026-10-30). Swipe toward END ("Add to next", Spotify's direction; RIGHT in
        // LTR, LEFT in RTL via getAbsoluteMovementFlags); there is no START action.
        // THE DEFECT, DEVICE-CONFIRMED ON 1154: on an album page a swipe added the song but the row stayed
        // BLANK until it was scrolled away and back. Read from recyclerview 1.4.0: a COMPLETED swipe animates
        // the row off-screen, then parks it in mPendingCleanup with its RecoverAnimation flagged
        // mIsPendingCleanup (ItemTouchHelper.select). Callback.onDrawOver only drops ended animations that
        // are NOT pending cleanup, and Callback.onDraw re-applies translationX = anim.mX (the full width)
        // every frame, so the row stays off-screen until the view DETACHES (onChildViewDetachedFromWindow
        // -> clearView). The old onSwiped's notifyItemChanged detaches it only where a change animation
        // swaps holders; MediaDetailsFragment and DownloadFragment set itemAnimator = null, so the holder
        // was rebound in place and never detached. Nothing in that path depends on direction.
        // THE SHAPE NOW: threshold and escape velocity are Float.MAX_VALUE, so every release takes the
        // CANCELLED-swipe path, which animates the row back to 0 and calls clearView at once - with any
        // item animator. onChildDraw notes whether the finger is past 25% of the width (the old
        // threshold), and onSelectedChanged(null, IDLE) - called by select() when the finger lifts - fires
        // the action. onSwiped is never called. A fling below 25% no longer counts; an ACTION_CANCEL past
        // it also fires (select(null) again), which is rare because the helper blocks parent intercepts
        // while a row is selected.
        fun getTouchHelper(listener: FeedClickListener) = ItemTouchHelper(
            object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.END) {
                private var armed: MediaViewHolder? = null

                override fun getMovementFlags(
                    recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder,
                ): Int {
                    if (viewHolder !is MediaViewHolder) return 0
                    if (viewHolder.feed?.item !is Track) return 0
                    return makeMovementFlags(0, ItemTouchHelper.END)
                }

                override fun onChildDraw(
                    c: Canvas, recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder,
                    dX: Float, dY: Float, actionState: Int, isCurrentlyActive: Boolean,
                ) {
                    if (isCurrentlyActive && actionState == ItemTouchHelper.ACTION_STATE_SWIPE)
                        armed = (viewHolder as? MediaViewHolder)
                            ?.takeIf { kotlin.math.abs(dX) > recyclerView.width * 0.25f }
                    super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
                }

                override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                    super.onSelectedChanged(viewHolder, actionState)
                    if (actionState != ItemTouchHelper.ACTION_STATE_IDLE) {
                        armed = null
                        return
                    }
                    val holder = armed ?: return
                    armed = null
                    val feed = holder.feed ?: return
                    val track = feed.item as? Track ?: return
                    listener.onTrackSwiped(holder.itemView, feed.extensionId, track)
                }

                override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

                override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder) = Float.MAX_VALUE
                override fun getSwipeEscapeVelocity(defaultValue: Float) = Float.MAX_VALUE
                override fun onMove(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                    target: RecyclerView.ViewHolder,
                ) = false
            }
        )
    }
}