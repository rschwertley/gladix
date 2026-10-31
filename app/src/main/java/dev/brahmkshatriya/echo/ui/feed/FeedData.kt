package dev.brahmkshatriya.echo.ui.feed

import android.os.Parcelable
import androidx.paging.cachedIn
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.utils.CrashKeys
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.ExtensionType
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Tab
import dev.brahmkshatriya.echo.di.App
import dev.brahmkshatriya.echo.extensions.ExtensionLoader
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Metadata
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.getExtensionOrThrow
import dev.brahmkshatriya.echo.extensions.cache.Cached
import dev.brahmkshatriya.echo.extensions.builtin.offline.MediaStoreUtils.searchBy
import dev.brahmkshatriya.echo.ui.common.PagedSource
import dev.brahmkshatriya.echo.ui.feed.FeedType.Companion.toFeedType
import dev.brahmkshatriya.echo.ui.feed.viewholders.HorizontalListViewHolder
import dev.brahmkshatriya.echo.utils.CacheUtils.cacheDir
import dev.brahmkshatriya.echo.utils.CacheUtils.getFromCache
import dev.brahmkshatriya.echo.utils.CacheUtils.saveToCache
import dev.brahmkshatriya.echo.utils.CoroutineUtils.combineTransformLatest
import dev.brahmkshatriya.echo.utils.image.ImageUtils.loadDrawable
import dev.brahmkshatriya.echo.utils.ui.UiUtils.isTv
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted.Companion.Lazily
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.withContext
import java.io.File
import java.lang.ref.WeakReference

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
data class FeedData(
    private val feedId: String,
    private val scope: CoroutineScope,
    private val app: App,
    private val extensionLoader: ExtensionLoader,
    private val cached: suspend ExtensionLoader.() -> State<Feed<Shelf>>?,
    private val load: suspend ExtensionLoader.() -> State<Feed<Shelf>>?,
    private val defaultButtons: Feed.Buttons,
    private val noVideos: Boolean,
    private val extraLoadFlow: Flow<*>
) {
    // ══ PREFETCH ══ Warm the track lists of playlists the user is looking at, so a tap that follows is a
    // cache hit rather than a fresh round trip. FeedFragment owns the trigger (scroll-settle + debounce and
    // which rows are visible); this half owns the gates, the extension lookup and the cap.
    //
    // PLAYLISTS ONLY. Every playlist loadTracks implementation read - Deezer, Offline, Unified, plus
    // Spotify and Tidal - uses only the id and routing extras, all set when the item is parsed into a
    // shelf. Albums are excluded because that is where both known failure shapes live: Tidal's album
    // loadTracks does album.extras["json"]!! on a field written by loadAlbum, so an unloaded item throws;
    // Spotify's passes the album object into toTrack, so an unloaded item silently degrades every track's
    // metadata. Albums get their speed from the durable cache and the Deezer results() handoff instead.
    //
    // NOT WHILE PLAYING is doing real work, not defensive boilerplate: the 2026-05-27 per-track
    // api.track() lookup was reverted because a speculative network call competing with playback caused
    // battery drain and cellular playback problems. This is the same shape, so it carries the same guard.
    // UNMETERED covers the cellular half.
    //
    // PROCESS-WIDE CAP, not per feed or per tab: a per-tab reset would let a user defeat it by cycling
    // tabs. The durable cache means an item is warmed at most once per TTL, so the cap is rarely
    // approached. When it is, prefetch silently stops and the user gets exactly today's behaviour - a
    // degradation to the status quo rather than a failure, which is the right trade for something
    // speculative, but silent, so it is written down here rather than left to be discovered in a profiler.
    fun warmTracks(items: List<FeedType>, isPlaying: Boolean) {
        if (isPlaying || !app.isUnmetered) return
        scope.launch {
            for (feedItem in items) {
                if (warmsIssued.get() >= MAX_WARMS_PER_PROCESS) return@launch
                val playlist = when (feedItem) {
                    is FeedType.Media -> feedItem.item
                    is FeedType.MediaGrid -> feedItem.item
                    else -> null
                } as? Playlist ?: continue
                warmsIssued.incrementAndGet()
                // getExtension throws for an unknown id; a warm must never surface anything.
                runCatching {
                    Cached.warmPlaylistTracks(app, getExtension(feedItem.extensionId), playlist)
                }
            }
        }
    }

    val current = extensionLoader.current
    val usersFlow = extensionLoader.db.currentUsersFlow
    suspend fun getExtension(id: String) =
        extensionLoader.getFlow(ExtensionType.MUSIC).getExtensionOrThrow(id)

    val layoutManagerStates = hashMapOf<Int, Parcelable?>()
    val visibleScrollableViews = hashMapOf<Int, WeakReference<HorizontalListViewHolder>>()

    // Surface flag for FeedType.toFeedType — drops the category preview's expand arrow on TV only.
    private val isTv = app.context.isTv()

    private val refreshFlow = MutableSharedFlow<Unit>(1)
    private val cachedState = MutableStateFlow<Result<State<Feed<Shelf>>?>?>(null)
    private val loadedState = MutableStateFlow<Result<State<Feed<Shelf>>?>?>(null)
    private val selectedTabFlow = MutableStateFlow<Tab?>(null)

    val loadedShelves = MutableStateFlow<List<Shelf>?>(null)
    var searchToggled: Boolean = false
    var searchQuery: String? = null
    val feedSortState = MutableStateFlow<FeedSort.State?>(null)

    /**
     * ⚠⚠ WHAT THE LAST RENDER APPLIED, WHICH IS NOT WHAT [feedSortState] AND [searchQuery] SAY NOW.
     * GitHub #4 follow-up (2026-10-29): with "Reversed" on, a tap in Library -> Tracks queued the rows
     * ABOVE it, because the tap sliced Feed.Buttons.customTrackList in the EXTENSION'S order while the
     * screen showed the sorted one. FeedAdapter.inDisplayOrder reads this to queue what is on screen.
     * ⚠️ WHY A RECORD AND NOT A READ OF THE STATE: searchQuery is written on every keystroke
     * (ButtonsAdapter's text listener) but the feed re-renders only on submit, so "a query is set" is
     * true while the rows are still unfiltered - and, with no sort, still PAGED, which would queue a
     * ~100-item window. Written by [getFeedSourceData] alongside the data it describes.
     *   [DisplayOrder.Rebuilt]  - a sort field or a search: the shelves were flattened, and the rendered
     *                             PagedData.Single IS the whole processed list (up to MAX_SORT_SEARCH_ITEMS).
     *   [DisplayOrder.Reversed] - "Reversed" alone: shelves NOT flattened, so a carousel (Library -> All)
     *                             keeps its own order and only the sections move.
     *   [DisplayOrder.Source]   - the extension's order; everything else.
     */
    enum class DisplayOrder { Source, Reversed, Rebuilt }

    @Volatile
    var displayOrder = DisplayOrder.Source
        private set

    // The (extensionId, tabId) the CURRENT feedSortState was loaded for. Captured at the disk read below,
    // in the same block and from the same source, so persistSortState can never pair a fresh tab with a
    // stale state — the defect this whole change exists to remove. feedId is a constructor property and
    // needs no capture.
    private var sortStateExtensionId: String? = null
    private var sortStateTabId: String? = null

    /**
     * Persist (or remove) the CURRENT [feedSortState] under the key it was loaded for.
     *
     * ⚠️ CALL THIS FROM USER ACTIONS ONLY. Saving a sort is something a person asks for; it is not a
     * property of having rendered the feed. Until 2026-09-06 the write lived inside getFeedSourceData and
     * fired on EVERY pass through the sort branch, pairing `selectedTabFlow.value` (already advanced by a
     * tab switch) with `feedSortState.value` (not yet re-read for the new tab, because that read lives in
     * a SEPARATE collector — see dataFlow). A saved sort on one tab was therefore written to a different
     * tab's key, silently and permanently.
     *
     * WHY THAT MATTERED MORE THAN IT LOOKS. The gate it arms is not cosmetic: getFeedSourceData's sort
     * branch runs data.loadTill(shelfLimit = 2000, itemLimit = MAX_SORT_SEARCH_ITEMS) BEFORE the flatMap,
     * materialising up to 2000 raw Shelf objects each still holding its full track list. That is the shape
     * that OOM'd on Combine's feed; MAX_SORT_SEARCH_ITEMS exists as a CAP added in response (015428a5,
     * 2026-07-19), sized on the assumption that the branch is user-requested. A spurious write breaks that
     * assumption — it arms an eager load on a feed nobody chose to sort.
     *
     * ⚠️ WHO WAS AFFECTED: everyone who has ever applied a saved sort on ANY tab of a MULTI-TAB feed.
     * Library and Home both qualify. The damaged tab is not the sorted one — it is whichever tab the user
     * switched TO while the previous tab's state was still loaded, and because the entry is on disk it
     * survived restarts. It went unnoticed on flat tabs (Library's Playlists/Albums/Tracks/Artists), where
     * every shelf is already a Shelf.Item and the flatMap is a no-op; it is glaring on a tab of carousels.
     *
     * ⚠️ save == false MEANS DELETE, NOT SKIP. Applying with the checkbox unticked, or resetting from the
     * sheet's toolbar, must REMOVE any existing entry. Skipping the write instead (what the old code did)
     * left the previous entry on disk to be restored on the next feed load — which is why the reset action
     * appeared to work and then silently un-did itself.
     *
     * The delete is a direct File.delete() HERE rather than a CacheUtils API, matching
     * Cached.bustTracksCache: CacheUtils states a deliberate no-delete policy in getFromCache's comment,
     * and this keeps that intact. Deliberately NOT saveToCache(key, null) — that writes the JSON literal
     * `null`, which getFromCache then fails to decode into a non-nullable State and logs as
     * "getFromCache unreadable: folder=sort" on EVERY feed load, forever.
     */
    fun persistSortState() = scope.launch(Dispatchers.IO) {
        val extensionId = sortStateExtensionId ?: return@launch
        val key = "$extensionId-$feedId-$sortStateTabId"
        val state = feedSortState.value
        if (state?.save == true) app.context.saveToCache(key, state, "sort")
        else runCatching { File(cacheDir(app.context, "sort"), key.hashCode().toString()).delete() }
    }
    val searchClickedFlow = MutableSharedFlow<Unit>()

    private val stateFlow = cachedState.combine(loadedState) { a, b -> a to b }
        .stateIn(scope, Lazily, null to null)

    private val cachedDataFlow = cachedState.combineTransformLatest(selectedTabFlow) { feed, tab ->
        emit(null)
        if (feed == null) return@combineTransformLatest
        emit(getData(feed, tab))
    }.stateIn(scope, Lazily, null)

    private val loadedDataFlow = loadedState.combineTransformLatest(selectedTabFlow) { feed, tab ->
        emit(null)
        if (feed == null) return@combineTransformLatest
        emit(getData(feed, tab))
    }.stateIn(scope, Lazily, null)

    private suspend fun getData(
        state: Result<State<Feed<Shelf>>?>, tab: Tab?
    ) = withContext(Dispatchers.IO) {
        runCatching {
            val (extensionId, item, feed) = state.getOrThrow() ?: return@runCatching null
            State(extensionId, item, feed.getPagedData(tab))
        }
    }

    // stateIn: the combine — and its side effects, including the "sort" disk read — runs ONCE per emission
    // instead of once per downstream collector (shouldShowEmpty + buttonsFlow + imageFlow = 3x before).
    // withContext(IO): the read is off the main thread. The read stays INSIDE the combine so feedSortState
    // is set before the emission propagates; moving it out would let buttonsFlow/feedTypeFlow render the
    // previous feed's sort for a frame (wrong-sort flash).
    val dataFlow = cachedDataFlow.combine(loadedDataFlow) { cached, loaded ->
        val extensionId = (loaded?.getOrNull() ?: cached?.getOrNull())?.extensionId
        val tabId = selectedTabFlow.value?.id
        searchQuery = null
        searchToggled = false
        val id = "$extensionId-$feedId-$tabId"
        // Captured HERE, with the value, from the same locals — see persistSortState. Any later write uses
        // these rather than re-reading selectedTabFlow, which is what makes a stale pairing impossible
        // rather than merely unlikely.
        sortStateExtensionId = extensionId
        sortStateTabId = tabId
        // ⚠️ NO persistSortState() HERE, and that is not an oversight — this assignment is the LOAD. It
        // writes what disk already holds, so persisting it would be a pure write-back, and it is not a
        // user action. Every OTHER writer of feedSortState either calls persistSortState (the sheet's
        // Apply and reset, the two header chip clears) or carries a comment saying why it must not
        // (FeedClickListener's arm-on-open). Keep that invariant: one writer, one explicit answer.
        feedSortState.value = extensionId?.let {
            withContext(Dispatchers.IO) { app.context.getFromCache(id, "sort") }
        }
        loadedShelves.value = null
        cached to loaded
    }.stateIn(scope, Lazily, null to null)

    val shouldShowEmpty = dataFlow.map { (cached, loaded) ->
        val data = loaded?.getOrNull() ?: cached?.getOrNull()
        data != null
    }.stateIn(scope, Lazily, false)

    // The (null, null) pair is a reload in progress - the load collector in init clears both states before
    // re-pulling - not a feed without tabs. Skipped (2026-10-31) so the tab row is not emptied, hidden and
    // rebuilt on every reload: on TV that dropped focus from the chosen tab (the Library bounce, see chosenTabId).
    // A feed that really has no tabs still arrives as a non-null state with an empty list.
    val tabsFlow = stateFlow.filter { (cached, loaded) -> cached != null || loaded != null }.map { (cached, loaded) ->
        val state = (loaded?.getOrNull() ?: cached?.getOrNull()) ?: return@map listOf()
        state.feed.tabs.map {
            FeedTab(feedId, state.extensionId, it)
        }
    }

    val selectedTabIndexFlow = tabsFlow.combine(selectedTabFlow) { tabs, tab ->
        tabs.indexOfFirst { it.tab.id == tab?.id }
    }

    data class FeedTab(
        val feedId: String,
        val extensionId: String,
        val tab: Tab
    )

    data class Buttons(
        val feedId: String,
        val extensionId: String,
        val buttons: Feed.Buttons,
        val item: EchoMediaItem? = null,
        val sortState: FeedSort.State? = null,
    )

    val buttonsFlow = dataFlow.combine(feedSortState) { data, state ->
        val feed = data.run { second?.getOrNull() ?: first?.getOrNull() } ?: return@combine null
        Buttons(
            feedId,
            feed.extensionId,
            feed.feed.buttons ?: defaultButtons,
            feed.item,
            state,
        )
    }

    private val imageFlow = dataFlow.map { (cached, loaded) ->
        (loaded?.getOrNull() ?: cached?.getOrNull())?.feed?.background
    }.stateIn(scope, Lazily, null)

    val backgroundImageFlow = imageFlow.mapLatest { image ->
        image?.loadDrawable(app.context)
    }.flowOn(Dispatchers.IO).stateIn(scope, Lazily, null)

    val cachedFeedTypeFlow =
        combineTransformLatest(cachedDataFlow, feedSortState, searchClickedFlow) { _ ->
            emit(null)
            val cached = cachedDataFlow.value ?: return@combineTransformLatest
            emit(getFeedSourceData(cached))
        }.stateIn(scope, Lazily, null)

    val loadedFeedTypeFlow =
        combineTransformLatest(loadedDataFlow, feedSortState, searchClickedFlow) { _ ->
            emit(null)
            val loaded = loadedDataFlow.value ?: return@combineTransformLatest
            emit(getFeedSourceData(loaded))
        }.stateIn(scope, Lazily, null)

    // ⚠️ KNOWN, SEPARATE DEFECT — the Library double-render flash. This rebuilds a PagedSource on every
    // upstream emission with no equality gate, so identical cache-then-network content renders twice. It
    // shares a CAUSE FAMILY with the sort-write race fixed on 2026-09-06 — combineTransformLatest is
    // combine(*flows).transformLatest(…), which re-runs its transform on every emission and does not
    // coordinate the independent collectors of the same upstreams — but it is NOT the same defect: the
    // flash is redundant work with CORRECT inputs, while that race was single work with MISMATCHED inputs
    // (a fresh tabId against a stale sortState, from two separate collectors).
    // Moving the sort write out of the render path does NOT fix the flash. What it does is make an extra
    // render pass HARMLESS: an extra pass is no longer an extra chance to persist a wrong sort. A previous
    // fix for the flash was reverted over stale-content risk and it remains open.
    val pagingFlow =
        cachedFeedTypeFlow.combineTransformLatest(loadedFeedTypeFlow) { cached, loaded ->
            // Taken from whichever Result actually carries data - loaded first, cached as fallback -
            // so it always describes the PagedData being wrapped. It travels WITH the data (see
            // getFeedSourceData); it is not looked up here.
            val metadata = (loaded?.getOrNull() ?: cached?.getOrNull())?.first
            emitAll(
                PagedSource(
                    loaded?.map { it.second }, cached?.map { it.second }, metadata
                ).flow
            )
        }.cachedIn(scope)

    /**
     * ⚠⚠ RETURNS THE Metadata PAIRED WITH THE PagedData IT DESCRIBES, AND THE PAIRING IS THE
     * POINT - DO NOT REPLACE IT WITH A READ FROM A SIBLING FLOW. PagedSource needs the extension's
     * Metadata to convert a load failure into an AppException (see the note at PagedSource.transform;
     * a wrong one names the wrong extension in the message AND opens the wrong login screen).
     * The obvious cheaper alternative is to read the id at the pagingFlow site from
     * loadedDataFlow/cachedDataFlow - a THIRD flow, not one of the two being combined there. That is
     * the shape the sort-write race was: "a fresh tabId against a stale sortState, FROM TWO SEPARATE
     * COLLECTORS", named in the comment above pagingFlow and fixed on 2026-09-06. Resolving it here,
     * from the same `result` the PagedData is built from, makes the mismatch unrepresentable.
     * ⚠️ NOT AN ARGUMENT FROM THE DOUBLE-RENDER FLASH. That defect is in the same comment and
     * does NOT support this: it is explicitly "redundant work with CORRECT INPUTS". It was cited for
     * this once, wrongly. The sort-write race is the half that applies.
     * (Same lesson as SearchViewModel.quickFeed pairing ids with items rather than holding them in a
     * separate field.)
     */
    private suspend fun getFeedSourceData(
        result: Result<State<Feed.Data<Shelf>>?>
    ): Result<Pair<Metadata?, PagedData<FeedType>>> = withContext(Dispatchers.IO) {
        val tabId = selectedTabFlow.value?.id
        val data = if (feedSortState.value != null || searchQuery != null) {
            result.mapCatching { state ->
                state ?: return@mapCatching PagedData.empty()
                val extensionId = state.extensionId
                val data = state.feed.pagedData

                val sortState = feedSortState.value
                val query = searchQuery
                var shelves = data.loadTill(
                    shelfLimit = 2000, itemLimit = MAX_SORT_SEARCH_ITEMS,
                ) { shelf -> if (shelf is Shelf.Lists<*>) shelf.list.size.coerceAtLeast(1) else 1 }
                // ⚠️ THIS flatMap DESTROYS SECTION STRUCTURE, AND THAT IS THE INTENDED BEHAVIOUR — but it
                // is invisible from the outside and cost several rounds of investigation on 2026-09-06.
                // Read this before chasing "my carousels turned into a flat list" again.
                //
                // WHAT IT DOES: every Shelf.Lists.* is exploded into its contents (Shelf.Lists.Items ->
                // shelf.list.map { it.toShelf() }), because sorting or searching only means anything over
                // individual items, not over container shelves. FeedType then maps each resulting
                // Shelf.Item to a full-width Media row and emits NO Header — headers come only from
                // Shelf.Lists. So a feed whose shelves ARE its organisation (Library -> All, Home) turns
                // into one flat, headerless, mixed list the moment a sort or a search is active.
                //
                // ⚠️ AND THE TRIGGER IS INVISIBLE AND STICKY. The outer gate above is
                // `feedSortState.value != null || searchQuery != null`; feedSortState is restored FROM
                // DISK on every dataFlow emission (see the read near the top of this file, key
                // "$extensionId-$feedId-$tabId") and written back below when sortState.save is set. So a
                // sort applied once — possibly on a DIFFERENT tab, since the key is per-tab but the state
                // flow is shared — survives process death and re-arms itself on the next feed load, with
                // nothing on screen at rest to say why the sections vanished except the sort chip in the
                // header.
                // [CORRECTED 2026-10-29] "written back below when sortState.save is set" is stale: that
                // write was removed by the 2026-09-06 fix (see the NO PERSISTENCE HERE note below). Saved
                // state is now written only by FeedData.persistSortState, from user actions — the sheet's
                // Apply and Reset (FeedSortBottomSheet) and the header chips (ButtonsAdapter.configure).
                // The restore half of this paragraph still holds.
                //
                // 2026-09-06 field case: Library -> All rendered as one flat mixed list of playlists,
                // albums, tracks and artists with no section titles, on Deezer, surviving force-stop.
                // DeezerLibraryClient.loadAll was correct throughout — it returns four
                // Shelf.Lists.Items(type = Linear) and nothing between it and FeedType alters them. The
                // whole effect was produced here.
                //
                // ⚠️ THE RESET MENU ITEM DOES NOT CLEAR THE SAVED ENTRY. FeedSortBottomSheet's toolbar
                // action sets FeedSort.State(), i.e. save = false, so the `if (sortState.save)` write
                // below is skipped and the OLD saved entry stays on disk — the next restore re-arms it.
                // Nothing in this file ever deletes a saved sort. The header chip path
                // (ButtonsAdapter.configure -> state.copy(feedSort = null)) does persist a cleared state,
                // because it keeps save = true and therefore rewrites the entry.
                // [CORRECTED 2026-10-29] Stale since the 2026-09-06 fix; both claims above are now false.
                // Reset DOES clear the saved entry: FeedSortBottomSheet's Reset sets FeedSort.State()
                // (save = false) and calls FeedData.persistSortState, which DELETES the entry when save is
                // false — as does Apply with Save unticked. The `if (sortState.save)` write it describes no
                // longer exists. The header-chip half is still right: the chips keep save = true and rewrite.
                shelves = if (sortState?.feedSort != null || query != null)
                    shelves.flatMap { shelf ->
                        when (shelf) {
                            is Shelf.Category -> listOf(shelf)
                            is Shelf.Item -> listOf(shelf)
                            is Shelf.Lists.Categories -> shelf.list
                            is Shelf.Lists.Items -> shelf.list.map { it.toShelf() }
                            is Shelf.Lists.Tracks -> shelf.list.map { it.toShelf() }
                        }
                    }
                else shelves
                // Post-explosion item cap: guards the single-huge-shelf case and the Combine aggregate.
                // `truncated` drives the leading "first N" indicator appended below.
                val truncated = shelves.size > MAX_SORT_SEARCH_ITEMS
                if (truncated) shelves = shelves.take(MAX_SORT_SEARCH_ITEMS)
                loadedShelves.value = shelves
                if (sortState != null) {
                    // With a sort field, "Reversed" is applied INSIDE the sorter (see
                    // FeedSort.sortedMissingLast) so rows with no value stay last in both directions.
                    // With no sort field there is nothing missing, and the whole list is reversed here.
                    val feedSort = sortState.feedSort
                    shelves = when {
                        feedSort != null -> feedSort.sorter.invoke(app.context, shelves, sortState.reversed)
                        sortState.reversed -> shelves.reversed()
                        else -> shelves
                    }
                    // ⚠️ NO PERSISTENCE HERE. Until 2026-09-06 this block ended with
                    //     if (sortState.save) app.context.saveToCache("$extensionId-$feedId-$tabId", …)
                    // which fired on every render pass and paired a fresh tabId with a stale sortState.
                    // Saving now happens only from user actions — see FeedData.persistSortState for the
                    // mechanism, the blast radius and why the branch above is worth protecting.
                    // ⚠️ `extensionId` and `tabId` remain in scope and are still correct for RENDERING;
                    // they are simply not a safe key to WRITE with from here, because the state they would
                    // be paired with belongs to whichever tab was selected when it was read.
                }
                if (query != null) {
                    // ⚠️ WITH A SORT OR "Reversed" ACTIVE, SEARCH ONLY FILTERS (2026-10-29). searchBy ends
                    // by re-sorting its hits by match score, which threw away the order the user had just
                    // chosen. The original index is carried through and restored, so the hits keep the
                    // sorted order. With no sort active, results stay in relevance order, as before.
                    // searchBy itself is untouched: the Offline extension's own search relies on its ranking.
                    val keepOrder = sortState?.feedSort != null || sortState?.reversed == true
                    val hits = shelves.withIndex().toList().searchBy(query) {
                        listOf(it.value.title)
                    }.map { it.second }
                    shelves = (if (keepOrder) hits.sortedBy { it.index } else hits).map { it.value }
                }
                // Truncated (would-OOM aggregate / huge playlist): tell the user up front that sort/search
                // only covered the first N. LEADING so it's seen on load, not after scrolling 15k items.
                // Added AFTER sort+search so it isn't reordered/filtered; null feed => inert header row.
                if (truncated) shelves = listOf(
                    Shelf.Category(
                        id = "feed-truncated-indicator",
                        title = app.context.getString(R.string.feed_truncated, MAX_SORT_SEARCH_ITEMS),
                        feed = null,
                    )
                ) + shelves
                displayOrder = when {
                    sortState?.feedSort != null || query != null -> DisplayOrder.Rebuilt
                    sortState?.reversed == true -> DisplayOrder.Reversed
                    else -> DisplayOrder.Source
                }
                PagedData.Single {
                    shelves.toFeedType(
                        feedId,
                        extensionId,
                        state.item,
                        tabId,
                        noVideos,
                        isTv = isTv
                    )
                }
            }
        } else result.mapCatching { state ->
            state ?: return@mapCatching PagedData.empty()
            displayOrder = DisplayOrder.Source
            val extId = state.extensionId
            val data = state.feed.pagedData
            data.loadPage(null)
            var start = 0L
            data.map { result ->
                result.map {
                    val list = it.toFeedType(feedId, extId, state.item, tabId, noVideos, start, isTv)
                    start += list.size
                    list
                }.getOrThrow()
            }
        }
        // Resolved from the SAME `result` the data above was built from. runCatching because
        // getExtension throws on an unknown id (getExtensionOrThrow) and is MUSIC-typed - a feed from
        // another extension type resolves to null, which simply leaves the error untransformed, i.e.
        // exactly today's behaviour. Never let this throw: it would turn a renderable feed into a
        // failed one over a diagnostic detail.
        val metadata = result.getOrNull()?.extensionId
            ?.let { id -> runCatching { getExtension(id).metadata }.getOrNull() }
        data.map { metadata to it }
    }

    private companion object {
        // See warmTracks. Process-wide by construction: a companion val outlives every FeedData instance.
        const val MAX_WARMS_PER_PROCESS = 20
        val warmsIssued = AtomicInteger(0)

        // Sort/search materialization bound — the non-paged analog of PagedSource maxSize=100. Bounds the
        // sources×shelves×items explosion (Combine can reach 100k–400k+ items → ~1 GB → OOM). Set well above
        // any legit feed (large playlists/libraries top out ~10–15k), so it only bites on would-OOM feeds;
        // when it does, the feed shows a "first N" indicator (never silent). Objects cost ~2–4 KB each; the
        // sort/search copies are shallow reference arrays, so ~15k ≈ 30–60 MB, safe under the 256 MB heap.
        const val MAX_SORT_SEARCH_ITEMS = 15_000
    }

    // Item-aware: stop once cumulative item weight hits itemLimit, not just element count — one raw
    // Shelf.Lists already holds its whole track list, so counting shelves alone doesn't bound memory.
    private suspend fun <T : Any> PagedData<T>.loadTill(
        shelfLimit: Int, itemLimit: Int, weight: (T) -> Int,
    ): List<T> {
        val list = mutableListOf<T>()
        var items = 0
        var page = loadPage(null)
        fun add(data: List<T>): Boolean {
            for (e in data) { list.add(e); items += weight(e); if (items >= itemLimit) return true }
            return false
        }
        if (add(page.data)) return list
        while (page.continuation != null && list.size < shelfLimit) {
            page = loadPage(page.continuation)
            if (add(page.data)) return list
        }
        return list
    }

    val isRefreshingFlow = loadedFeedTypeFlow.map {
        loadedFeedTypeFlow.value == null
    }.stateIn(scope, Lazily, true)

    private var saveTabJob: Job? = null

    /**
     * ⚠⚠ THE TAB THE USER CHOSE, HELD IN MEMORY AND AUTHORITATIVE OVER THE SAVED ONE (2026-10-31). Every feed reload
     * (stateFlow re-emitting) runs the selection collector in init, which used to re-read cache["selected_tab"]
     * - written ASYNCHRONOUSLY by [saveTabJob] - and so could put the selection back to the previous or first tab
     * while the user's choice was still on its way to disk. On TV that was the Library bounce: OK on Playlists
     * pulsed the tab, then selection and focus jumped back to All (1156 and 1158; first diagnosed in August,
     * when this fix was recommended and never landed). Set synchronously here; the cache is now only the
     * cold-start answer, read when nothing has been chosen in this FeedData's life.
     * ⚠️ THE RELOAD ITSELF IS NOT FIXED HERE, and its trigger on a tab select is still not found statically. One
     * route that would do it: ExtensionLoader.current re-emits whenever ExtensionLoader.music re-emits, because
     * mapped() wraps each client in a new Injectable, so the MusicExtension data class never compares equal;
     * this FeedData reloads on every `current` emission. This field makes any such reload harmless to the
     * selection.
     */
    @Volatile
    private var chosenTabId: String? = null

    fun selectTab(extensionId: String?, pos: Int) {
        val state = stateFlow.value.run { second?.getOrNull() ?: first?.getOrNull() }
        val tab = state?.feed?.tabs?.getOrNull(pos)
            ?.takeIf { state.extensionId == extensionId }
        if (tab != null) chosenTabId = tab.id
        selectedTabFlow.value = tab
        // Off Main + single-flight: cancel any pending save and persist the CURRENT selection (read at
        // write time), so a fast second tap can't let the first tap's write land last and store a stale tab.
        saveTabJob?.cancel()
        saveTabJob = scope.launch(Dispatchers.IO) {
            app.context.saveToCache(feedId, selectedTabFlow.value?.id, "selected_tab")
        }
    }

    fun refresh() = scope.launch { refreshFlow.emit(Unit) }

    init {
        scope.launch(Dispatchers.IO) {
            listOfNotNull(current, refreshFlow, usersFlow, extraLoadFlow)
                .merge().debounce(100L).collectLatest {
                    cachedState.value = null
                    loadedState.value = null
                    extensionLoader.current.value ?: return@collectLatest
                    CrashKeys.onFeedLoad()   // feed_load_count (debounced 100ms + collectLatest — not hot)
                    cachedState.value = runCatching { cached(extensionLoader) }
                    loadedState.value = runCatching { load(extensionLoader) }
                }
        }
        scope.launch {
            stateFlow.collect { result ->
                // A reload in progress (both states cleared) keeps the current selection rather than nulling it -
                // the same transient tabsFlow skips (2026-10-31).
                if (result.first == null && result.second == null) return@collect
                val feed = result.run { second?.getOrNull() ?: first?.getOrNull() }?.feed?.tabs
                selectedTabFlow.value = if (feed == null) null else {
                    // The user's choice first (see chosenTabId); the saved tab only when nothing was chosen yet.
                    val last = chosenTabId ?: withContext(Dispatchers.IO) {
                        app.context.getFromCache<String>(feedId, "selected_tab")
                    }
                    feed.find { it.id == last } ?: feed.firstOrNull()
                }
            }
        }
    }

    data class State<T>(
        val extensionId: String,
        val item: EchoMediaItem?,
        val feed: T,
    )

    fun onSearchClicked() = scope.launch { searchClickedFlow.emit(Unit) }
}