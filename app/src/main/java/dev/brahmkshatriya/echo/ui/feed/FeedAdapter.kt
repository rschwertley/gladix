package dev.brahmkshatriya.echo.ui.feed

import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Canvas
import android.speech.RecognizerIntent
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
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
        val tracks = feeds.mapNotNull { it.toTrack() }
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

    override fun onBindViewHolder(holder: FeedViewHolder<*>, position: Int) {
        super.onBindViewHolder(holder, position)
        val feed = runCatching { getItem(position) }.getOrNull() ?: return
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
    }.flatten()

    fun withLoading(fragment: Fragment, vararg before: GridAdapter, initialButtons: Boolean = false, onCreatePlaylistClick: (() -> Unit)? = null, onEditPlaylistClick: (() -> Unit)? = null, editPlaylistVisible: Flow<Boolean>? = null): GridAdapter.Concat {
        val tabs = TabsAdapter<FeedTab>({ tab.title }) { view, index, tab ->
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
        val buttons = ButtonsAdapter(viewModel, listener, ::getAllTracks, onCreatePlaylistClick, onEditPlaylistClick) {
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
        fragment.observe(viewModel.buttonsFlow) {
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
        var hasEverLoaded = false
        addLoadStateListener { loadStates ->
            if (loadStates.refresh is LoadState.NotLoading) hasEverLoaded = true
            header.loadState = if (hasEverLoaded && loadStates.refresh is LoadState.Loading)
                LoadState.NotLoading(false)
            else loadStates.refresh
            footer.loadState = loadStates.append
        }
        return GridAdapter.Concat(*before, tabs, buttons, header, empty, this, footer)
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
        }
    }

    private var current: PlayerState.Current? = null
    fun onCurrentChanged(current: PlayerState.Current?) {
        this.current = current
        onEachViewHolder { onCurrentChanged(current) }
    }

    companion object {
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