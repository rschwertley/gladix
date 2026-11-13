package dev.brahmkshatriya.echo.ui.player.more.lyrics

import androidx.core.content.edit
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.paging.cachedIn
import dev.brahmkshatriya.echo.common.Extension
import dev.brahmkshatriya.echo.common.clients.LyricsClient
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Lyrics
import dev.brahmkshatriya.echo.common.models.Metadata
import dev.brahmkshatriya.echo.di.App
import dev.brahmkshatriya.echo.extensions.ExtensionLoader
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.getExtension
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.isClient
import dev.brahmkshatriya.echo.extensions.cache.Cached
import dev.brahmkshatriya.echo.playback.MediaItemUtils.extensionId
import dev.brahmkshatriya.echo.playback.MediaItemUtils.isLoaded
import dev.brahmkshatriya.echo.playback.MediaItemUtils.track
import dev.brahmkshatriya.echo.playback.PlayerState
import dev.brahmkshatriya.echo.ui.common.PagedSource
import dev.brahmkshatriya.echo.ui.extensions.list.ExtensionListViewModel
import dev.brahmkshatriya.echo.utils.CacheUtils.getFromCache
import dev.brahmkshatriya.echo.utils.BoundedLoadAll.loadAllBounded
import dev.brahmkshatriya.echo.utils.CacheUtils.saveToCache
import dev.brahmkshatriya.echo.utils.CoroutineUtils.combineTransformLatest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted.Companion.Eagerly
import kotlinx.coroutines.flow.SharingStarted.Companion.Lazily
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LyricsViewModel(
    private val app: App,
    extensionLoader: ExtensionLoader,
    playerState: PlayerState,
) : ExtensionListViewModel<Extension<*>>() {

    private val refreshFlow = MutableSharedFlow<Unit>()
    override val currentSelectionFlow = MutableStateFlow<Extension<*>?>(null)

    val queryFlow = MutableStateFlow("")
    val selectedTabIndexFlow = MutableStateFlow(-1)
    val lyricsState = MutableStateFlow<State>(State.Initial)

    private val mediaFlow = playerState.current.map { current ->
        current?.mediaItem?.takeIf { it.isLoaded }
    }.distinctUntilChanged().stateIn(viewModelScope, Eagerly, null)

    override val extensionsFlow = extensionLoader.lyrics.combine(mediaFlow) { lyrics, mediaItem ->
        val trackExtension = mediaItem?.extensionId?.let { id ->
            extensionLoader.music.getExtension(id)?.takeIf { it.isClient<LyricsClient>() }
        }
        listOfNotNull(trackExtension) + lyrics
    }.onEach { extensions ->
        currentSelectionFlow.value = null
        lyricsState.value = State.Initial
        queryFlow.value = ""
        val media = mediaFlow.value?.track?.id
        currentSelectionFlow.value = media?.let {
            val id = app.context.getFromCache<String>(media, "lyrics_ext")
                ?: app.settings.getString(LAST_LYRICS_KEY, null)
            extensions.find { it.id == id } ?: extensions.firstOrNull()
        }
        refreshFlow.emit(Unit)
    }.stateIn(viewModelScope, Eagerly, emptyList())

    override fun onExtensionSelected(extension: Extension<*>) {
        app.settings.edit { putString(LAST_LYRICS_KEY, extension.id) }
        val media = mediaFlow.value?.track?.id ?: return
        app.context.saveToCache<String>(media, extension.id, "lyrics_ext")
    }

    fun reloadCurrent() = viewModelScope.launch { refreshFlow.emit(Unit) }

    private val cachedFeed = combineTransformLatest(
        currentSelectionFlow, mediaFlow, queryFlow, refreshFlow
    ) {
        emit(null)
        val extension = it[0] as Extension<*>? ?: return@combineTransformLatest
        val item = it[1] as MediaItem? ?: return@combineTransformLatest
        val query = it[2] as String
        val result = Cached.getLyricsFeed(app, extension.id, item.extensionId, item.track, query)
        // ⚠⚠ THE Metadata IS PAIRED WITH THE Feed HERE, AT THE ONLY POINT IN THE CHAIN WHERE
        // BOTH EXIST. PagedSource needs it to convert a load failure into an AppException (a raw
        // ClientException.LoginRequired from a lyrics paging lambda otherwise renders a RETRY button
        // that cannot work - see the note at PagedSource.transform). Downstream, `getData` receives a
        // Feed<Lyrics>, WHICH CARRIES NO EXTENSION IDENTITY OF ITS OWN, so anything below this line
        // could only recover it by reading currentSelectionFlow - a SEPARATE COLLECTOR of the same
        // upstream that the data was derived from.
        // ⚠⚠ THAT IS NOT A THEORETICAL HAZARD - IT SHIPPED ONCE AND CORRUPTED DISK STATE.
        // Verified against the sort-write race recorded at FeedData.persistSortState, which is
        // explicit about the mechanism: it paired "selectedTabFlow.value (ALREADY ADVANCED by a tab
        // switch)" with "feedSortState.value (NOT YET RE-READ for the new tab, because that read
        // lives in a SEPARATE collector)", and "a saved sort on one tab was therefore written to a
        // different tab's key, silently and permanently". It survived restarts and affected everyone
        // who had applied a saved sort on any tab of a multi-tab feed. Fixed 2026-09-06 by capturing
        // the key IN THE SAME BLOCK, FROM THE SAME SOURCE - which is what this pairing does too.
        // ⚠️ PAIR OF DISTINCTLY-TYPED COMPONENTS, DELIBERATELY - NOT A DATA CLASS WITH TWO
        // SAME-TYPED FIELDS. Metadata and Feed<Lyrics> are unrelated types, so a .first/.second
        // mix-up anywhere downstream is a COMPILE ERROR rather than a silent wrong-extension bug.
        // That property is load-bearing here: this file is on the Inspect Code exclusion list
        // (reified-generic/@OptIn inference complexity), so the compiler and the device are the only
        // checks this change gets. Do not "tidy" this into a holder whose fields could be swapped.
        emit(result.map { extension.metadata to it })
    }.stateIn(viewModelScope, Eagerly, null)

    private val loadedFeed = combineTransformLatest(
        currentSelectionFlow, mediaFlow, queryFlow, refreshFlow
    ) {
        emit(null)
        val extension = it[0] as Extension<*>? ?: return@combineTransformLatest
        val item = it[1] as MediaItem? ?: return@combineTransformLatest
        val query = it[2] as String
        val result = Cached.loadLyricsFeed(app, extension, item.extensionId, item.track, query)
        // Paired for the same reason as cachedFeed above - see that note.
        emit(result.map { extension.metadata to it })
    }.stateIn(viewModelScope, Eagerly, null)

    private val feedFlow = loadedFeed.combine(cachedFeed) { loaded, cache ->
        cache to loaded
    }.stateIn(viewModelScope, Eagerly, null to null)

    val tabsFlow = feedFlow.map { (cached, loaded) ->
        val state = (loaded?.getOrNull() ?: cached?.getOrNull()) ?: return@map listOf()
        state.second.tabs
    }

    /**
     * ⚠️ CARRIES THE Metadata STRAIGHT THROUGH. It is not used here - it exists so that
     * pagingFlow can hand it to PagedSource without re-deriving it from a sibling flow. The FeedData
     * equivalent (getFeedSourceData) could resolve identity from its OWN input because State carries
     * extensionId; this one cannot, because Feed<Lyrics> carries none - which is why the pairing has
     * to start two hops earlier, at cachedFeed/loadedFeed.
     */
    // ⚠️ `feedValue`, NOT `loadedFeed` - THE OBVIOUS NAME SHADOWS THE PROPERTY DECLARED ABOVE.
    // Caught in review before it shipped, and worth a line because SHADOWING HAS ALREADY PRODUCED A
    // SILENT NEVER-EXECUTES BUG IN THIS CODEBASE: a type-hierarchy shadow meant code written for the
    // radio path had never run at all, and MediaItemUtils.toMetaData carries a nearest-receiver
    // shadowing note with a sibling at buildForSource deliberately left alone. Kotlin compiles a
    // shadow happily; nothing warns at the call site. Prefer a distinct name over a tidy one.
    private suspend fun getData(
        feed: Result<Pair<Metadata, Feed<Lyrics>>>?, index: Int,
    ) = withContext(Dispatchers.IO) {
        feed?.mapCatching { (metadata, feedValue) ->
            val paged = feedValue.getPagedData(
                feedValue.tabs.run { getOrNull(index) ?: firstOrNull() }
            ).pagedData
            metadata to paged
        }
    }

    private val cachedDataFlow =
        cachedFeed.combineTransformLatest(selectedTabIndexFlow) { feed, tab ->
            emit(null)
            if (feed == null) return@combineTransformLatest
            emit(getData(feed, tab))
        }.stateIn(viewModelScope, Lazily, null)

    private val loadedDataFlow =
        loadedFeed.combineTransformLatest(selectedTabIndexFlow) { feed, tab ->
            emit(null)
            if (feed == null) return@combineTransformLatest
            emit(getData(feed, tab))
        }.stateIn(viewModelScope, Lazily, null)

    private val dataFlow = loadedDataFlow.combine(cachedDataFlow) { loaded, cache ->
        cache to loaded
    }.stateIn(viewModelScope, Lazily, null to null)

    val shouldShowEmpty = dataFlow.map { (cached, loaded) ->
        val data = loaded?.getOrNull() ?: cached?.getOrNull()
        data != null
    }.stateIn(viewModelScope, Lazily, false)

    @OptIn(ExperimentalCoroutinesApi::class)
    val pagingFlow = dataFlow.transformLatest { (cached, loaded) ->
        // From whichever Result actually carries data - loaded first, cached as fallback - so it
        // always describes the PagedData being wrapped. It TRAVELLED here with the data; it is not
        // looked up. See the pairing note at cachedFeed.
        val metadata = (loaded?.getOrNull() ?: cached?.getOrNull())?.first
        emitAll(
            PagedSource(
                loaded?.map { it.second }, cached?.map { it.second }, metadata
            ).flow
        )
    }.flowOn(Dispatchers.IO).cachedIn(viewModelScope)

    sealed interface State {
        data object Initial : State
        data object Loading : State
        data object Empty : State
        data class Loaded(val result: Result<Lyrics>) : State
    }

    fun onLyricsSelected(lyricsItem: Lyrics?) = viewModelScope.launch(Dispatchers.IO) {
        lyricsState.value = State.Loading
        if (lyricsItem == null) lyricsState.value = State.Empty else {
            val extension = currentSelectionFlow.value ?: return@launch
            lyricsState.value =
                State.Loaded(Cached.loadLyrics(app, extension, lyricsItem).map { it.fillGaps() })
        }
    }

    private fun Lyrics.fillGaps(): Lyrics {
        val lyrics = this.lyrics as? Lyrics.Timed
        return if (lyrics != null && lyrics.fillTimeGaps) {
            val new = mutableListOf<Lyrics.Item>()
            var last = 0L
            lyrics.list.forEach {
                if (it.startTime > last) {
                    new.add(Lyrics.Item("", last, it.startTime))
                }
                new.add(it)
                last = it.endTime
            }
            this.copy(lyrics = Lyrics.Timed(new))
        } else this
    }

    init {
        reloadCurrent()
        viewModelScope.launch(Dispatchers.IO) {
            dataFlow.collectLatest { (cached, loaded) ->
                if (lyricsState.value != State.Initial) return@collectLatest
                runCatching {
                    // [2026-11-13] Bounded (BoundedLoadAll): no unbounded loadAll left in the app (Crashlytics 700db7c5).
                    val cachedLyrics = cached?.getOrNull()?.second?.loadAllBounded()?.firstOrNull()
                    val loaded = loaded?.getOrNull()?.second
                    if (loaded != null) {
                        lyricsState.value = State.Loading
                        onLyricsSelected(loaded.loadPage(null).data.firstOrNull())
                    } else if (cachedLyrics != null) {
                        onLyricsSelected(cachedLyrics)
                    }
                }
            }
        }
    }

    companion object {
        const val LAST_LYRICS_KEY = "last_lyrics_client"
    }
}