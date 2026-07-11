package dev.brahmkshatriya.echo.playback

import android.content.ContentResolver
import android.content.Context
import android.content.res.Resources
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.CallSuper
import androidx.annotation.OptIn
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.common.Extension
import dev.brahmkshatriya.echo.common.MusicExtension
import dev.brahmkshatriya.echo.common.clients.AlbumClient
import dev.brahmkshatriya.echo.common.clients.ArtistClient
import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.HomeFeedClient
import dev.brahmkshatriya.echo.common.clients.LibraryFeedClient
import dev.brahmkshatriya.echo.common.clients.PlaylistClient
import dev.brahmkshatriya.echo.common.clients.RadioClient
import dev.brahmkshatriya.echo.common.clients.SearchFeedClient
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.ImageHolder
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Radio
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.di.App
import dev.brahmkshatriya.echo.download.Downloader
import dev.brahmkshatriya.echo.history.HistoryRepository
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.isClient
import dev.brahmkshatriya.echo.playback.MediaItemUtils.extensionId
import dev.brahmkshatriya.echo.playback.MediaItemUtils.track
import dev.brahmkshatriya.echo.extensions.MediaState
import dev.brahmkshatriya.echo.extensions.builtin.offline.OfflineExtension
import dev.brahmkshatriya.echo.extensions.builtin.unified.UnifiedExtension
import dev.brahmkshatriya.echo.utils.CacheUtils.getFromCache
import dev.brahmkshatriya.echo.utils.CacheUtils.saveToCache
import dev.brahmkshatriya.echo.utils.CoroutineUtils.await
import dev.brahmkshatriya.echo.utils.CoroutineUtils.future
import dev.brahmkshatriya.echo.utils.CoroutineUtils.futureCatching
import dev.brahmkshatriya.echo.playback.ResumptionUtils.recoverCurrentId
import dev.brahmkshatriya.echo.playback.ResumptionUtils.recoverIndex
import dev.brahmkshatriya.echo.playback.ResumptionUtils.recoverTracks
import dev.brahmkshatriya.echo.playback.ResumptionUtils.resolveCurrentIndex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import java.io.ByteArrayOutputStream
import androidx.appcompat.content.res.AppCompatResources
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicBoolean
import dev.brahmkshatriya.echo.common.models.Message
import dev.brahmkshatriya.echo.utils.Serializer.toData
import dev.brahmkshatriya.echo.utils.Serializer.toJson
import kotlinx.serialization.Serializable
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

// ── Android Auto self-describing browse id (P4) ──────────────────────────────────────────────────
// A browsed track's play id round-trips through Android Auto as its mediaId. Legacy form is
// "auto/<trackId>" (extension + context live ONLY in the durable "auto/" file cache). The new form
// embeds the extension id + optional context (type/id/title) as base64url(JSON) after "auto/", so a
// cache miss can still re-resolve the item instead of silently dropping it. parseAutoId reads both.
@Serializable
private data class AutoId(
    val t: String,          // track id — also the durable "auto/" cache key
    val e: String? = null,  // extension id
    val ct: String? = null, // context type: album | playlist | radio | artist
    val ci: String? = null, // context id
    val cn: String? = null, // context title (labels the header without a re-fetch)
    val cst: String? = null, // album subtype (Album.Type name) — album-only; preserves Show/Book so a
                             // type-aware extension's loadAlbum branches correctly on a thin cache-miss item.
                             // New optional field; ignoreUnknownKeys makes it safe for old builds to drop.
)

@OptIn(ExperimentalEncodingApi::class)
private fun encodeAutoId(trackId: String, extId: String, con: EchoMediaItem?): String {
    val payload = AutoId(
        t = trackId, e = extId, ct = con?.autoContextType(), ci = con?.id, cn = con?.title,
        cst = (con as? Album)?.type?.name,
    )
    return "auto/" + Base64.UrlSafe.encode(payload.toJson().encodeToByteArray())
}

@OptIn(ExperimentalEncodingApi::class)
private fun parseAutoId(mediaId: String): AutoId? {
    if (!mediaId.startsWith("auto/")) return null
    val payload = mediaId.substringAfter("auto/")
    // New form: base64url(JSON). Legacy "auto/<trackId>": the payload IS the raw track id.
    return runCatching {
        Base64.UrlSafe.decode(payload).decodeToString().toData<AutoId>().getOrThrow()
    }.getOrNull() ?: AutoId(t = payload)
}

private fun EchoMediaItem.autoContextType(): String? = when (this) {
    is Album -> "album"
    is Playlist -> "playlist"
    is Radio -> "radio"
    is Artist -> "artist"
    else -> null // Track / others: no re-fetchable collection context
}

// Thin context rebuilt from a mediaId on a cache miss — enough for listTracks/loadAlbum to re-fetch
// fresh (with valid tokens), so #4 recovery matches the History fresh-resolve path.
private fun AutoId.toThinContext(): EchoMediaItem? {
    val id = ci ?: return null
    val title = cn.orEmpty()
    return when (ct) {
        // Carry the album subtype (Show/Book/…) back so a type-aware extension's loadAlbum branches
        // correctly — otherwise a podcast show is handed back as a plain album and re-fetched via the
        // album endpoint. Absent name (old-format id) or a future/unknown Type -> null, same as before.
        "album" -> Album(
            id = id, title = title,
            type = cst?.let { runCatching { Album.Type.valueOf(it) }.getOrNull() }
        )
        "playlist" -> Playlist(id = id, title = title, isEditable = false)
        "radio" -> Radio(id = id, title = title)
        "artist" -> Artist(id = id, name = title)
        else -> null
    }
}

// Thin track rebuilt from the mediaId + the metadata Android Auto round-trips, so a cache-miss item is
// KEPT in the queue (never silently dropped). It loads on play where loadTrack works by id, and error-
// skips visibly where the original token is required (e.g. Deezer) instead of vanishing.
private fun MediaItem.toThinTrack(id: String): Track {
    val md = mediaMetadata
    return Track(
        id = id,
        title = md.title?.toString() ?: id,
        artists = md.artist?.toString()?.let { listOf(Artist(id = "", name = it)) } ?: listOf(),
    )
}

@UnstableApi
abstract class AndroidAutoCallback(
    open val app: App,
    open val scope: CoroutineScope,
    open val extensionList: StateFlow<List<MusicExtension>>,
    open val downloadFlow: StateFlow<List<Downloader.Info>>
) : MediaLibrarySession.Callback {

    val context get() = app.context

    open val throwableFlow: MutableSharedFlow<Throwable>? get() = null
    open val historyRepository: HistoryRepository? = null

    internal val userQueueSet = AtomicBoolean(false)
    @Volatile private var lastSearchQuery = ""
    @Volatile protected var lastBrowsedExtId: String? = null
    private val searchResults = boundedMap<Pair<String, String>, List<MediaItem>>()
    private val searchJobs = boundedMap<String, Job>()
    private val searchMutex = Mutex()
    private var extensionWatcherJob: Job? = null
    private var pendingSearchJob: Job? = null

    override fun onDisconnected(session: MediaSession, controller: MediaSession.ControllerInfo) {
        userQueueSet.set(false)
        lastBrowsedExtId = null
        pendingSearchJob?.cancel()
        extensionWatcherJob?.cancel()
        super.onDisconnected(session, controller)
    }

    @CallSuper
    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<MediaItem>> {
        if (params?.isRecent == true) {
            val tracks = context.recoverTracks()
            val rawIndex = context.recoverIndex() ?: 0
            // Same repair as recoverPlaylist so the AA resume tile shows the true current, not a skewed one.
            val index = resolveCurrentIndex(tracks ?: emptyList(), rawIndex, context.recoverCurrentId()) {
                it.first.item.id
            }
            val track = (tracks?.getOrNull(index) ?: tracks?.firstOrNull())?.first?.item
            return Futures.immediateFuture(
                LibraryResult.ofItem(
                    if (track != null)
                        browsableItem(
                            "recent",
                            track.title,
                            track.subtitleWithE,
                            browsable = true,
                            artWorkUri = track.cover?.toUri(context)
                        )
                    else
                        browsableItem("recent", "", browsable = false),
                    null
                )
            )
        }
        if (browser.packageName != "com.google.android.projection.gearhead")
            return Futures.immediateFuture(
                LibraryResult.ofItem(browsableItem(ROOT, "", browsable = false), null)
            )
        Log.d("GladixAuto", "onGetLibraryRoot: extensionList.value.size=${extensionList.value.size}, clearing caches, starting watcher")
        extensionWatcherJob?.cancel()
        extensionWatcherJob = scope.launch {
            cacheMutex.withLock {
                clearCaches()
                searchResults.clear()
            }
            extensionList.collect { extensions ->
                Log.d("GladixAuto", "extensionWatcher: collected ${extensions.size} extensions: ${extensions.map { it.id }}")
                if (extensions.isNotEmpty()) {
                    Log.d("GladixAuto", "extensionWatcher: calling notifyChildrenChanged ROOT count=${extensions.size}")
                    session.notifyChildrenChanged(browser, ROOT, extensions.size, null)
                    session.notifyChildrenChanged("recent", 1, null)
                }
            }
        }
        return Futures.immediateFuture(
            LibraryResult.ofItem(
                browsableItem(ROOT, "", browsable = false),
                null
            )
        )
    }

    @OptIn(UnstableApi::class)
    override fun onSearch(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<Void>> = Futures.immediateFuture(LibraryResult.ofVoid()).also {
        val extras = params?.extras
        val effectiveQuery = listOfNotNull(
            extras?.getString(MediaStore.EXTRA_MEDIA_TITLE),
            extras?.getString(MediaStore.EXTRA_MEDIA_ARTIST),
            extras?.getString(MediaStore.EXTRA_MEDIA_ALBUM),
            query.takeIf { it.isNotEmpty() }
        ).firstOrNull() ?: query
        Log.d("GladixAuto", "onSearch: rawQuery='$query' effectiveQuery='$effectiveQuery'")
        lastSearchQuery = effectiveQuery
        val extId = getCurrentExtension()?.id ?: ""
        val cacheKey = query to extId
        val cached = searchResults[cacheKey]
        if (cached != null) {
            session.notifySearchResultChanged(browser, query, cached.size, params)
        }
        val existing = searchJobs[query]
        if (existing != null && existing.isActive) {
            Log.d("GladixAuto", "onSearch: joining existing in-flight search for query='$query'")
            scope.launch {
                runCatching {
                    existing.join()
                    val tracks = searchResults[cacheKey]
                    if (tracks != null) {
                        Log.d("GladixAuto", "onSearch: notifySearchResultChanged (joined) query='$query' count=${tracks.size}")
                        session.notifySearchResultChanged(browser, query, tracks.size, params)
                    }
                }.onFailure {
                    if (it is CancellationException) throw it
                    throwableFlow?.emit(it)
                    it.printStackTrace()
                }
            }
            return@also
        }
        pendingSearchJob?.cancel()
        pendingSearchJob = scope.launch {
            delay(300)
            searchJobs[query] = coroutineContext[Job]!!
            runCatching { performSearch(effectiveQuery) }
                .onSuccess { tracks ->
                    searchResults[cacheKey] = tracks
                    searchJobs.remove(query)
                    Log.d("GladixAuto", "onSearch: notifySearchResultChanged query='$query' count=${tracks.size}")
                    session.notifySearchResultChanged(browser, query, tracks.size, params)
                }
                .onFailure {
                    searchJobs.remove(query)
                    if (it is CancellationException) throw it
                    throwableFlow?.emit(it)
                    it.printStackTrace()
                }
        }
    }

    @OptIn(UnstableApi::class)
    @CallSuper
    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.futureCatching {
        val extensions = if (parentId == ROOT) {
            withTimeoutOrNull(10_000L) { extensionList.first { it.isNotEmpty() } }
                ?: return@futureCatching LibraryResult.ofError(
                    SessionError(SessionError.ERROR_IO, context.getString(R.string.auto_timed_out))
                )
        } else extensionList.value
        if (parentId == ROOT) {
            val enabled = extensions.filter { it.isEnabled && it.id != UnifiedExtension.UNIFIED_ID }
            Log.d("GladixAuto", "onGetChildren ROOT: extensionList.first size=${extensions.size} enabled=${enabled.size}, ids=${enabled.map { it.id }}")
            return@futureCatching LibraryResult.ofItemList(
                enabled.map { it.toMediaItem(context) },
                null
            )
        }
        if (parentId == "recent") {
            // Read from live player state first to avoid the race between saveQueue() (async
            // in onTimelineChanged) and notifyChildrenChanged("recent") (sync in
            // onMediaItemTransition) — recoverTracks() may still hold the previous extension's
            // queue when AA calls onGetChildren("recent") after a cross-extension switch.
            val liveItem = withContext(Dispatchers.Main) { session.player.currentMediaItem }
            val liveTrack = runCatching { liveItem?.track }.getOrNull()
            val liveExtId = runCatching { liveItem?.extensionId }.getOrNull()
            if (liveTrack != null && liveExtId != null) {
                return@futureCatching LibraryResult.ofItemList(
                    ImmutableList.of(liveTrack.toItem(context, liveExtId)),
                    null
                )
            }
            val tracks = context.recoverTracks()
            val rawIndex = context.recoverIndex() ?: 0
            val index = resolveCurrentIndex(tracks ?: emptyList(), rawIndex, context.recoverCurrentId()) {
                it.first.item.id
            }
            val (state, _) = tracks?.getOrNull(index) ?: tracks?.firstOrNull()
                ?: return@futureCatching LibraryResult.ofItemList(ImmutableList.of(), null)
            return@futureCatching LibraryResult.ofItemList(
                ImmutableList.of(state.item.toItem(context, state.extensionId)),
                null
            )
        }
        val extId = parentId.substringAfter("$ROOT/").substringBefore("/")
        if (parentId.substringAfter("$ROOT/$extId").isNotEmpty()) lastBrowsedExtId = extId
        val extension = extensions.firstOrNull { it.id == extId }
            ?: return@futureCatching LibraryResult.ofError(
                SessionError(SessionError.ERROR_IO, context.getString(R.string.auto_error_loading))
            )
        val type = parentId.substringAfter("$extId/").substringBefore("/")
        cacheMutex.withLock { withTimeoutOrNull(15_000L) { when (type) {
            ALBUM -> extension.getList<AlbumClient>(context, throwableFlow) {
                val id = parentId.substringAfter("$ALBUM/").substringBefore("/")
                // Soft-fail the node (empty) on a cache miss (eviction) or a wrong-typed item mis-filed under
                // this mediaId, rather than NPE/CCE the browse future. Deliberately NOT a thin re-fetch: that
                // would route a mismatched id back into loadAlbum — the mistyped-item bug this guards against.
                val unloaded = itemMap[id] as? Album ?: return@getList emptyList()
                val tracks = getTracks(context, id, extId, page) {
                    val album = loadAlbum(unloaded)
                    album to loadTracks(album)
                }
                if (page == 0) listOf(shuffleItem(id, extId, context)) + tracks else tracks
            }

            PLAYLIST -> extension.getList<PlaylistClient>(context, throwableFlow) {
                val id = parentId.substringAfter("$PLAYLIST/").substringBefore("/")
                val unloaded = itemMap[id] as? Playlist ?: return@getList emptyList()
                val tracks = getTracks(context, id, extId, page) {
                    val playlist = loadPlaylist(unloaded)
                    playlist to loadTracks(playlist)
                }
                if (page == 0) listOf(shuffleItem(id, extId, context)) + tracks else tracks
            }

            RADIO -> extension.getList<RadioClient>(context, throwableFlow) {
                val id = parentId.substringAfter("$RADIO/").substringBefore("/")
                val radio = itemMap[id] as? Radio ?: return@getList emptyList()
                getTracks(context, id, extId, page) {
                    radio to loadTracks(radio)
                }
            }

            USER -> extension.getList<ArtistClient>(context, throwableFlow) {
                val id = parentId.substringAfter("$USER/").substringBefore("/")
                val unloaded = itemMap[id] as? Artist ?: return@getList emptyList()
                val artist = loadArtist(unloaded)
                loadFeed(artist).toMediaItems(id, context, extId, page)
            }

            LIST -> extension.getList<ExtensionClient>(context, throwableFlow) {
                val id = parentId.substringAfter("$LIST/").substringBefore("/")
                getListsItems(context, id, extId)
            }

            SHELF -> extension.getList<ExtensionClient>(context, throwableFlow) {
                val id = parentId.substringAfter("$SHELF/").substringBefore("/")
                getShelfItems(context, id, extId, page)
            }

            HOME -> extension.getFeed<HomeFeedClient>(
                context, parentId, page, throwableFlow
            ) { loadHomeFeed() }

            LIBRARY -> extension.getFeed<LibraryFeedClient>(
                context, parentId, page, throwableFlow
            ) { loadLibraryFeed() }

            FEED -> extension.getList<ExtensionClient>(context, throwableFlow) {
                val id = parentId.substringAfter("$ROOT/$extId/$FEED/")
                val feed = feedMap[id] ?: return@getList emptyList()
                feed.toMediaItems(id, context, extId, page)
            }

            SEARCH -> {
                val query = parentId.substringAfter("$ROOT/$extId/$SEARCH/", "")
                extension.getFeed<SearchFeedClient>(
                    context, parentId, page, throwableFlow
                ) { loadSearchFeed(query) }
            }

            PLAYLISTS -> extension.getList<LibraryFeedClient>(context, throwableFlow) {
                val libFeed = loadLibraryFeed()
                val playlistTab = libFeed.notSortTabs.firstOrNull {
                    it.id.contains("playlist", ignoreCase = true) ||
                    it.title.contains("playlist", ignoreCase = true)
                } ?: libFeed.notSortTabs.firstOrNull()
                Feed(listOf()) { libFeed.getPagedData(playlistTab) }
                    .toMediaItems(parentId, context, extId, page)
            }

            HISTORY -> {
                val items = historyRepository?.getByExtension(extId) ?: emptyList()
                LibraryResult.ofItemList(
                    ImmutableList.copyOf(items.mapNotNull { entity ->
                        val con = if (entity.context is Radio) null else entity.context
                        entity.track?.toItem(context, extId, con)
                    }),
                    null
                )
            }

            else -> LibraryResult.ofItemList(
                listOfNotNull(
                    if (extension.isClient<HomeFeedClient>())
                        browsableItem("$ROOT/$extId/$HOME", "${extension.name} • ${context.getString(R.string.home)}")
                    else null,
                    if (extension.isClient<SearchFeedClient>())
                        browsableItem("$ROOT/$extId/$SEARCH", "${extension.name} • ${context.getString(R.string.aa_browse)}")
                    else null,
                    if (extension.isClient<LibraryFeedClient>())
                        browsableItem("$ROOT/$extId/$LIBRARY", "${extension.name} • ${context.getString(R.string.library)}")
                    else null,
                    if (extension.isClient<LibraryFeedClient>())
                        browsableItem("$ROOT/$extId/$PLAYLISTS", "${extension.name} • ${context.getString(R.string.playlists)}")
                    else null,
                    if (historyRepository != null)
                        browsableItem("$ROOT/$extId/$HISTORY", "${extension.name} • ${context.getString(R.string.history)}")
                    else null,
                ),
                null
            )
        } } ?: LibraryResult.ofError(
            SessionError(SessionError.ERROR_IO, context.getString(R.string.auto_timed_out))
        ) }
    }

    @OptIn(UnstableApi::class)
    override fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String
    ): ListenableFuture<LibraryResult<MediaItem>> {
        return super.onGetItem(session, browser, mediaId)
    }

    // Load the given context's tracks FRESH (via the extension) and return the current+upcoming media
    // items — the tapped track (fresh, live token) first, then the tracks after it. Overridden by
    // PlayerCallback (which owns the track-loading); base default is empty. Used to play History taps
    // and browse-cache-miss taps WITHOUT replaying a stored track's stale resolution state.
    protected open suspend fun freshContextUpcoming(
        extId: String, context: EchoMediaItem, tappedTrackId: String
    ): List<MediaItem> = emptyList()

    @OptIn(UnstableApi::class)
    @CallSuper
    override fun onGetSearchResult(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        val extId = getCurrentExtension()?.id ?: ""
        val cacheKey = query to extId
        Log.d("GladixAuto", "onGetSearchResult: query='$query' page=$page pageSize=$pageSize lastSearchQuery='$lastSearchQuery' cachedResults=${searchResults[cacheKey]?.size ?: "none"}")
        return scope.future {
            val effectiveQuery = lastSearchQuery.ifEmpty { query }
            val allTracks = searchResults[cacheKey]
                ?: run { runCatching { searchJobs[query]?.join() }.getOrNull(); searchResults[cacheKey] }
                ?: searchMutex.withLock {
                    searchResults[cacheKey] ?: performSearch(effectiveQuery).also {
                        searchResults[cacheKey] = it
                    }
                }
            val from = page * pageSize
            Log.d("GladixAuto", "onGetSearchResult: returning ${allTracks.drop(from).take(pageSize).size} items (total=${allTracks.size}) for query='$query'")
            LibraryResult.ofItemList(allTracks.drop(from).take(pageSize), null)
        }
    }

    protected open fun getCurrentExtension(): MusicExtension? {
        val aaEligible = { ext: MusicExtension -> ext.id != UnifiedExtension.UNIFIED_ID }
        return lastBrowsedExtId
            ?.takeIf { it != UnifiedExtension.UNIFIED_ID }
            ?.let { id -> extensionList.value.firstOrNull { it.id == id } }
            ?: extensionList.value.firstOrNull(aaEligible)
    }

    private suspend fun performSearch(query: String): List<MediaItem> {
        val ext = getCurrentExtension()
        if (ext == null) {
            Log.d("GladixAuto", "performSearch: no extension available for query='$query'")
            return emptyList()
        }
        Log.d("GladixAuto", "performSearch: query='$query' ext=${ext.id}")
        return runCatching {
            withTimeout(10_000) {
                val client = ext.instance.value().getOrNull() as? SearchFeedClient
                    ?: run {
                        Log.d("GladixAuto", "performSearch: ${ext.id} has no SearchFeedClient")
                        return@withTimeout emptyList<MediaItem>()
                    }
                Log.d("GladixAuto", "performSearch: calling loadSearchFeed query='$query' ext=${ext.id}")
                val feed = client.loadSearchFeed(query)
                val tab = feed.notSortTabs.firstOrNull { it.id.equals("TRACK", ignoreCase = true) }
                val pagedData = feed.getPagedData(tab).pagedData
                val (shelves, _) = pagedData.loadPage(null)
                val tracks = shelves.toTracks()
                Log.d("GladixAuto", "performSearch: ${tracks.size} results for query='$query' ext=${ext.id}")
                tracks.take(25).map { track ->
                    val item = track.toItem(context, ext.id)
                    val artist = item.mediaMetadata.artist
                    item.buildUpon().setMediaMetadata(
                        item.mediaMetadata.buildUpon()
                            .setArtist(if (artist.isNullOrEmpty()) ext.name else "$artist • ${ext.name}")
                            .build()
                    ).build()
                }
            }
        }.getOrElse {
            when (it) {
                is TimeoutCancellationException -> {
                    Log.d("GladixAuto", "performSearch: timeout for query='$query' ext=${ext.id}")
                    emptyList()
                }
                is CancellationException -> throw it
                else -> {
                    throwableFlow?.emit(it)
                    Log.d("GladixAuto", "performSearch: error for query='$query' ext=${ext.id}: ${it::class.simpleName}: ${it.message}")
                    emptyList()
                }
            }
        }
    }

    @CallSuper
    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long
    ) = scope.future {
        userQueueSet.set(true)
        val shuffleItem = mediaItems.singleOrNull()
            ?.takeIf { it.mediaId.startsWith("$SHUFFLE_PREFIX/") }
        if (shuffleItem != null) {
            val rest = shuffleItem.mediaId.substringAfter("$SHUFFLE_PREFIX/")
            val extId = rest.substringBefore("/")
            val id = rest.substringAfter("/")
            val (item, tracks) = tracksMap[id]
                ?: return@future super.onSetMediaItems(
                    mediaSession, controller, mutableListOf(), 0, startPositionMs
                ).await(context)
            val all = tracks.loadAll().shuffled().map {
                MediaItemUtils.build(app, downloadFlow.value, MediaState.Unloaded(extId, it), item)
            }
            return@future super.onSetMediaItems(
                mediaSession, controller, all.toMutableList(), 0, startPositionMs
            ).await(context)
        }

        // Single-track tap from a playlist/album: expand to full queue at correct position
        if (mediaItems.size == 1 && mediaItems[0].mediaId.startsWith("auto/")) {
            val auto = parseAutoId(mediaItems[0].mediaId)
            val cached = auto?.let {
                context.getFromCache<Triple<Track, String, EchoMediaItem?>>(it.t, "auto", durable = true)
            }
            if (cached != null) {
                val (track, extId, con) = cached
                if (con is EchoMediaItem.Lists) {
                    val tracksEntry = tracksMap.values.find { (item, _) ->
                        item is EchoMediaItem.Lists && item.id == con.id
                    }
                    if (tracksEntry != null) {
                        val (item, pagedData) = tracksEntry
                        val allTracks = pagedData.loadAll()
                        val tappedIndex = allTracks.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
                        // P2 — current+upcoming: drop the tracks before the tapped one so it lands at
                        // index 0 (matches phone playItem + freshContextUpcoming) — no stranded-above,
                        // zero persisted index.
                        val upcomingItems = allTracks.subList(tappedIndex, allTracks.size).map {
                            MediaItemUtils.build(app, downloadFlow.value, MediaState.Unloaded(extId, it), item)
                        }
                        return@future super.onSetMediaItems(
                            mediaSession, controller, upcomingItems.toMutableList(), 0, startPositionMs
                        ).await(context)
                    } else {
                        // Context not in the browse cache — a HISTORY tap (its context was never browsed)
                        // or an evicted album. Load it FRESH and play current+upcoming from the tapped
                        // track, so the cached STALE track is never replayed. Fixes the History-tap skip;
                        // empty result (context load failed) falls through to the stored-track path below.
                        val upcoming = freshContextUpcoming(extId, con, track.id)
                        if (upcoming.isNotEmpty()) {
                            return@future super.onSetMediaItems(
                                mediaSession, controller, upcoming.toMutableList(), 0, startPositionMs
                            ).await(context)
                        }
                    }
                }
            } else if (auto?.e != null) {
                // #4 — durable cache missed, but the mediaId is self-describing: rebuild a thin context
                // and re-resolve FRESH (loadAlbum/listTracks → valid tokens), exactly like a History tap.
                // Full current+upcoming recovery, extension-agnostic. If the context can't be rebuilt or
                // the load returns nothing, fall through to the generic branch's thin-keep (#3).
                val thinContext = auto.toThinContext()
                if (thinContext != null) {
                    val upcoming = freshContextUpcoming(auto.e, thinContext, auto.t)
                    if (upcoming.isNotEmpty()) {
                        return@future super.onSetMediaItems(
                            mediaSession, controller, upcoming.toMutableList(), 0, startPositionMs
                        ).await(context)
                    }
                }
            }
        }

        // Generic rebuild. On a durable-cache HIT, rebuild from the full cached Triple (best: real
        // streamables/tokens). On a MISS, #3 keeps the item as a thin track (never silently drop) when
        // the mediaId carries an extId; only a legacy "auto/<id>" with no recoverable extId is dropped,
        // and #2 messages the user rather than silently shrinking the queue.
        var dropped = 0
        val new = mediaItems.mapNotNull {
            if (it.mediaId.startsWith("auto/")) {
                val auto = parseAutoId(it.mediaId)
                if (auto == null) { dropped++; return@mapNotNull null }
                val cached =
                    context.getFromCache<Triple<Track, String, EchoMediaItem?>>(auto.t, "auto", durable = true)
                when {
                    cached != null -> {
                        val (track, extId, con) = cached
                        // No context (bare-track / Radio-History seed): stamp a display-only "<track>
                        // Radio" label. Marked LABEL_ONLY_RADIO, so PlayerRadio still generates off a null
                        // context — radio generation is unchanged, this is only the label.
                        val seedContext = con ?: MediaItemUtils.trackRadioPlaceholder(track)
                        MediaItemUtils.build(
                            app, downloadFlow.value, MediaState.Unloaded(extId, track), seedContext
                        )
                    }

                    auto.e != null -> {
                        // #3 thin-keep: never silently drop. Rebuild a display track from the mediaId +
                        // the metadata AA round-trips; it loads on play where loadTrack works by id and
                        // error-skips visibly (e.g. Deezer, token-less) instead of vanishing.
                        val thinTrack = it.toThinTrack(auto.t)
                        val seedContext =
                            auto.toThinContext() ?: MediaItemUtils.trackRadioPlaceholder(thinTrack)
                        MediaItemUtils.build(
                            app, downloadFlow.value, MediaState.Unloaded(auto.e, thinTrack), seedContext
                        )
                    }

                    else -> {
                        // Legacy "auto/<id>" with no recoverable extId — genuinely unresolvable.
                        dropped++
                        null
                    }
                }
            } else it
        }
        if (dropped > 0) scope.launch {
            app.messageFlow.emit(Message(context.getString(R.string.some_tracks_couldnt_be_restored)))
        }
        // Enforce super's invariant: the base onAddMediaItems throws UnsupportedOperationException on any
        // item with localConfiguration == null (Media3 1.10.1). The auto/ items above are built with a URI
        // (localConfiguration set) and non-auto items that already carry a URI pass through; only genuinely
        // unresolvable ones — an AA voice "play X" query or a bare mediaId with no URI — are filtered out.
        // Silent by design (a phone snackbar wouldn't show in the car). An empty result hits super's safe
        // empty path (same as the shuffle branch above), clearing the queue rather than re-prepping a fine
        // track. Resolving the query to real results (+ a spoken "couldn't find that" on none) is a separate
        // feature, not this crash guard.
        val playable = new.filter { it.localConfiguration != null }
        // Dropping items can leave startIndex past the end → IllegalSeekPositionException when Media3 applies
        // it. Clamp to the filtered list (0 when empty).
        val safeIndex = startIndex.coerceIn(0, (playable.size - 1).coerceAtLeast(0))
        val future = super.onSetMediaItems(
            mediaSession, controller, playable.toMutableList(), safeIndex, startPositionMs
        )
        future.await(context)
    }

    companion object {
        private const val ROOT = "root"
        private const val LIBRARY = "library"
        private const val PLAYLISTS = "playlists"
        private const val HOME = "home"
        private const val SEARCH = "search"
        private const val FEED = "feed"
        private const val SHELF = "shelf"
        private const val LIST = "list"

        private const val USER = "user"
        private const val ALBUM = "album"
        private const val PLAYLIST = "playlist"
        private const val RADIO = "radio"
        private const val HISTORY = "history"

        private const val SHUFFLE_PREFIX = "auto-shuffle"

        private const val MAX_MAP_SIZE = 500

        private fun <K, V> boundedMap(): MutableMap<K, V> =
            Collections.synchronizedMap(object : LinkedHashMap<K, V>(16, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?) = size > MAX_MAP_SIZE
            })

        private val cacheMutex = Mutex()

        private fun clearCaches() {
            itemMap.clear()
            listsMap.clear()
            shelvesMap.clear()
            feedMap.clear()
            tracksMap.clear()
            continuations.clear()
            extensionIconCache.clear()
        }

        private fun shuffleItem(id: String, extId: String, context: Context) = MediaItem.Builder()
            .setMediaId("$SHUFFLE_PREFIX/$extId/$id")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .setTitle(context.getString(R.string.shuffle))
                    .setArtworkUri(context.resources.getUri(R.drawable.ic_shuffle))
                    .build()
            ).build()

        private fun Resources.getUri(int: Int): Uri {
            val scheme = ContentResolver.SCHEME_ANDROID_RESOURCE
            val pkg = getResourcePackageName(int)
            val type = getResourceTypeName(int)
            val name = getResourceEntryName(int)
            val uri = "$scheme://$pkg/$type/$name"
            return uri.toUri()
        }

        private fun ImageHolder.toUri(context: Context) = when (this) {
            is ImageHolder.ResourceUriImageHolder -> uri.toUri()
            is ImageHolder.NetworkRequestImageHolder -> request.url.toUri()
            is ImageHolder.ResourceIdImageHolder -> context.resources.getUri(resId)
            is ImageHolder.HexColorImageHolder -> "".toUri()
        }

        private suspend fun ImageHolder.loadBitmapBytes(context: Context, maxPx: Int): ByteArray? =
            withContext(Dispatchers.IO) {
                runCatching {
                    val src: Bitmap = when (this@loadBitmapBytes) {
                        is ImageHolder.ResourceIdImageHolder ->
                            BitmapFactory.decodeResource(context.resources, resId)
                        is ImageHolder.NetworkRequestImageHolder ->
                            (java.net.URL(request.url).openConnection() as java.net.HttpURLConnection)
                                .apply { connectTimeout = 3000; readTimeout = 3000 }
                                .inputStream.use { BitmapFactory.decodeStream(it) }
                        is ImageHolder.ResourceUriImageHolder ->
                            context.contentResolver.openInputStream(uri.toUri())
                                ?.use { BitmapFactory.decodeStream(it) }
                        is ImageHolder.HexColorImageHolder -> null
                    } ?: return@runCatching null
                    val scale = minOf(1f, maxPx.toFloat() / maxOf(src.width, src.height))
                    val scaled = if (scale < 1f) {
                        src.scale(
                            (src.width * scale).toInt().coerceAtLeast(1),
                            (src.height * scale).toInt().coerceAtLeast(1)
                        ).also { src.recycle() }
                    } else src
                    ByteArrayOutputStream().use { out ->
                        scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
                        scaled.recycle()
                        out.toByteArray()
                    }
                }.getOrNull()
            }

        private suspend fun getTabIconBytes(context: Context, resId: Int): ByteArray? =
            withContext(Dispatchers.IO) {
                runCatching {
                    val size = 96
                    val padding = 8
                    val bitmap = createBitmap(size, size)
                    val canvas = Canvas(bitmap)
                    val drawable = AppCompatResources.getDrawable(context, resId) ?: return@runCatching null
                    drawable.setBounds(padding, padding, size - padding, size - padding)
                    drawable.draw(canvas)
                    ByteArrayOutputStream().use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                        bitmap.recycle()
                        out.toByteArray()
                    }
                }.getOrNull()
            }

        private fun browsableItem(
            id: String,
            title: String,
            subtitle: String? = null,
            browsable: Boolean = true,
            artWorkUri: Uri? = null,
            artworkData: ByteArray? = null,
            type: Int = MediaMetadata.MEDIA_TYPE_MIXED
        ) = MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setIsPlayable(false)
                    .setIsBrowsable(browsable)
                    .setMediaType(type)
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .apply {
                        if (artworkData != null)
                            setArtworkData(artworkData, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                        else
                            setArtworkUri(artWorkUri)
                    }
                    .build()
            )
            .build()

        private fun Track.toItem(
            context: Context, extensionId: String, con: EchoMediaItem? = null
        ): MediaItem {
            // #1 durable = true → filesDir, so an OS cacheDir wipe can't evict it. #3/#4 encodeAutoId →
            // self-describing mediaId carrying extId + context for cache-miss re-resolution.
            context.saveToCache(id, Triple(this, extensionId, con), "auto", durable = true)
            return MediaItem.Builder()
                .setMediaId(encodeAutoId(id, extensionId, con))
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setIsPlayable(true)
                        .setIsBrowsable(false)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                        .setTitle(title)
                        .setArtist(subtitleWithE)
                        .setAlbumTitle(album?.title)
                        .setArtworkUri(cover?.toUri(context))
                        .build()
                ).build()
        }

        private suspend fun Extension<*>.toMediaItem(context: Context): MediaItem {
            val success = instance.value().isSuccess
            val artworkData = if (extensionIconCache.containsKey(id)) {
                extensionIconCache[id]
            } else {
                val localResId = extensionIconResId[id]
                val bytes = if (localResId != null)
                    getTabIconBytes(context, localResId)
                else
                    metadata.icon?.loadBitmapBytes(context, 96)
                bytes.also { extensionIconCache[id] = it }
            }
            return browsableItem(
                "$ROOT/$id", name, context.getString(R.string.extension),
                success, artworkData = artworkData
            )
        }

        @OptIn(UnstableApi::class)
        val notSupported =
            LibraryResult.ofError<ImmutableList<MediaItem>>(SessionError.ERROR_NOT_SUPPORTED)

        @OptIn(UnstableApi::class)

        suspend inline fun <reified C> Extension<*>.getList(
            context: Context,
            throwableFlow: MutableSharedFlow<Throwable>? = null,
            block: C.() -> List<MediaItem>
        ): LibraryResult<ImmutableList<MediaItem>> = runCatching {
            val client = instance.value().getOrThrow() as? C ?: return@runCatching notSupported
            LibraryResult.ofItemList(
                client.block(),
                MediaLibraryService.LibraryParams.Builder()
                    .setOffline(client is OfflineExtension)
                    .build()
            )
        }.getOrElse {
            if (it is CancellationException) throw it
            throwableFlow?.emit(it)
            it.printStackTrace()
            LibraryResult.ofError(
                SessionError(SessionError.ERROR_IO, it.message ?: context.getString(R.string.auto_error_loading))
            )
        }


        private val extensionIconResId = mapOf(
            "deezer" to R.drawable.ic_aa_deezer,
            "spotify" to R.drawable.ic_aa_spotify,
            "Youtube_music" to R.drawable.ic_aa_youtube_music,
            "GoogleDrive_extension" to R.drawable.ic_aa_google_drive,
            "jellyfin" to R.drawable.ic_aa_jellyfin,
            "saavn_music" to R.drawable.ic_aa_saavn,
            "Groove_music" to R.drawable.ic_aa_groove
        )
        private val extensionIconCache = boundedMap<String, ByteArray?>()
        private val itemMap = boundedMap<String, EchoMediaItem>()
        private fun EchoMediaItem.toMediaItem(
            context: Context, extId: String
        ): MediaItem = when (this) {
            is Track -> toItem(context, extId)
            else -> {
                val id = hashCode().toString()
                itemMap[id] = this
                val (page, type) = when (this) {
                    is Artist -> USER to MediaMetadata.MEDIA_TYPE_MIXED
                    is Radio -> RADIO to MediaMetadata.MEDIA_TYPE_MIXED
                    is Album -> ALBUM to MediaMetadata.MEDIA_TYPE_ALBUM
                    is Playlist -> PLAYLIST to MediaMetadata.MEDIA_TYPE_PLAYLIST
                }
                browsableItem(
                    "$ROOT/$extId/$page/$id",
                    title,
                    subtitleWithE,
                    true,
                    cover?.toUri(context),
                    type = type
                )
            }
        }

        private val listsMap = boundedMap<String, Shelf.Lists<*>>()
        private fun getListsItems(
            context: Context, id: String, extId: String
        ) = run {
            val shelf = listsMap[id] ?: return@run emptyList()
            when (shelf) {
                is Shelf.Lists.Categories -> shelf.list.map { it.toMediaItem(context, extId) }
                is Shelf.Lists.Items -> shelf.list.map { it.toMediaItem(context, extId) }
                is Shelf.Lists.Tracks -> shelf.list.map { it.toItem(context, extId) }
            } + listOfNotNull(
                shelf.more?.let { more ->
                    val moreId = shelf.id
                    feedMap[moreId] = more
                    browsableItem(
                        "$ROOT/$extId/$FEED/$moreId",
                        context.getString(R.string.more)
                    )
                }
            )
        }

        private fun Shelf.toMediaItem(
            context: Context, extId: String
        ): MediaItem = when (this) {
            is Shelf.Category -> {
                val items = feed
                if (items != null) feedMap[id] = items
                browsableItem("$ROOT/$extId/$FEED/$id", title, subtitle, items != null)
            }

            is Shelf.Item -> media.toMediaItem(context, extId)
            is Shelf.Lists<*> -> {
                val id = "${id.hashCode()}"
                listsMap[id] = this
                browsableItem("$ROOT/$extId/$LIST/$id", title, subtitle)
            }
        }


        // THIS PROBABLY BREAKS GOING BACK TBH, NEED TO TEST
        private val shelvesMap = boundedMap<String, PagedData<Shelf>>()
        private val continuations = boundedMap<Pair<String, Int>, String?>()
        private suspend fun getShelfItems(
            context: Context, id: String, extId: String, page: Int
        ): List<MediaItem> {
            val shelf = shelvesMap[id] ?: return emptyList()
            val (list, next) = shelf.loadPage(continuations[id to page])
            continuations[id to page + 1] = next
            return listOfNotNull(
                *list.map { it.toMediaItem(context, extId) }.toTypedArray()
            )
        }

        private val feedMap = boundedMap<String, Feed<Shelf>>()
        private suspend fun Feed<Shelf>.toMediaItems(
            id: String, context: Context, extId: String, page: Int
        ): List<MediaItem> {
            val feedKey = id.hashCode().toString()
            if (notSortTabs.isNotEmpty()) {
                return notSortTabs.map { tab ->
                    val shelfKey = "${feedKey}_${tab.id.hashCode()}"
                    shelvesMap[shelfKey] = PagedData.Suspend { getPagedData(tab).pagedData }
                    browsableItem("$ROOT/$extId/$SHELF/$shelfKey", tab.title)
                }
            }
            if (shelvesMap[feedKey] == null) {
                shelvesMap[feedKey] = getPagedData(null).pagedData
            }
            return getShelfItems(context, feedKey, extId, page)
        }

        private suspend inline fun <reified T> Extension<*>.getFeed(
            context: Context,
            parentId: String,
            pageNumber: Int,
            throwableFlow: MutableSharedFlow<Throwable>? = null,
            getFeed: T.() -> Feed<Shelf>
        ): LibraryResult<ImmutableList<MediaItem>> = getList<T>(context, throwableFlow) {
            val extId = parentId.substringAfter("$ROOT/").substringBefore("/")
            getFeed().toMediaItems(parentId, context, extId, pageNumber)
        }

        private val tracksMap = boundedMap<String, Pair<EchoMediaItem, PagedData<Track>>>()
        private suspend fun getTracks(
            context: Context,
            id: String,
            extId: String,
            page: Int,
            getTracks: suspend () -> Pair<EchoMediaItem, Feed<Track>?>
        ): List<MediaItem> {
            val (item, tracks) = tracksMap[id] ?: run {
                val newPair = getTracks().run {
                    first to (second?.run { getPagedData(tabs.firstOrNull()) }?.pagedData
                        ?: PagedData.empty())
                }
                tracksMap[id] = newPair
                newPair
            }
            val (list, next) = tracks.loadPage(continuations[id to page])
            continuations[id to page + 1] = next
            return list.take(150).map { it.toItem(context, extId, item) }
        }

        private suspend fun List<Shelf>.toTracks(): List<Track> = flatMap { shelf ->
            when (shelf) {
                is Shelf.Item -> listOfNotNull(shelf.media as? Track)
                is Shelf.Lists.Tracks -> shelf.list
                is Shelf.Lists.Items -> shelf.list.filterIsInstance<Track>()
                is Shelf.Category -> shelf.feed?.let { feed ->
                    val pagedData = feed.getPagedData(feed.notSortTabs.firstOrNull()).pagedData
                    val (innerShelves, _) = pagedData.loadPage(null)
                    innerShelves.flatMap { inner ->
                        when (inner) {
                            is Shelf.Item -> listOfNotNull(inner.media as? Track)
                            is Shelf.Lists.Tracks -> inner.list
                            is Shelf.Lists.Items -> inner.list.filterIsInstance<Track>()
                            else -> emptyList()
                        }
                    }
                } ?: emptyList()
                else -> emptyList()
            }
        }
    }

}