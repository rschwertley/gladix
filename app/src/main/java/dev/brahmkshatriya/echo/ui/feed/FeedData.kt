package dev.brahmkshatriya.echo.ui.feed

import android.os.Parcelable
import androidx.paging.cachedIn
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.ExtensionType
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Tab
import dev.brahmkshatriya.echo.di.App
import dev.brahmkshatriya.echo.extensions.ExtensionLoader
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.getExtensionOrThrow
import dev.brahmkshatriya.echo.extensions.builtin.offline.MediaStoreUtils.searchBy
import dev.brahmkshatriya.echo.ui.common.PagedSource
import dev.brahmkshatriya.echo.ui.feed.FeedType.Companion.toFeedType
import dev.brahmkshatriya.echo.ui.feed.viewholders.HorizontalListViewHolder
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
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    val tabsFlow = stateFlow.map { (cached, loaded) ->
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

    val pagingFlow =
        cachedFeedTypeFlow.combineTransformLatest(loadedFeedTypeFlow) { cached, loaded ->
            emitAll(PagedSource(loaded, cached).flow)
        }.cachedIn(scope)

    private suspend fun getFeedSourceData(
        result: Result<State<Feed.Data<Shelf>>?>
    ): Result<PagedData<FeedType>> = withContext(Dispatchers.IO) {
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
                    shelves = sortState.feedSort?.sorter?.invoke(app.context, shelves) ?: shelves
                    if (sortState.reversed) shelves = shelves.reversed()
                    if (sortState.save)
                        app.context.saveToCache("$extensionId-$feedId-$tabId", sortState, "sort")
                }
                if (query != null) {
                    shelves = shelves.searchBy(query) {
                        listOf(it.title)
                    }.map { it.second }
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
        data
    }

    private companion object {
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
    fun selectTab(extensionId: String?, pos: Int) {
        val state = stateFlow.value.run { second?.getOrNull() ?: first?.getOrNull() }
        val tab = state?.feed?.tabs?.getOrNull(pos)
            ?.takeIf { state.extensionId == extensionId }
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
                    cachedState.value = runCatching { cached(extensionLoader) }
                    loadedState.value = runCatching { load(extensionLoader) }
                }
        }
        scope.launch {
            stateFlow.collect { result ->
                val feed = result.run { second?.getOrNull() ?: first?.getOrNull() }?.feed?.tabs
                selectedTabFlow.value = if (feed == null) null else {
                    val last = withContext(Dispatchers.IO) {
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