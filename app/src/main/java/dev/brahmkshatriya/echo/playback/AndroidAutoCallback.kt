package dev.brahmkshatriya.echo.playback

import android.content.ContentResolver
import android.content.Context
import android.content.res.Resources
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.CallSuper
import androidx.annotation.OptIn
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
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.isClient
import dev.brahmkshatriya.echo.extensions.MediaState
import dev.brahmkshatriya.echo.extensions.builtin.offline.OfflineExtension
import dev.brahmkshatriya.echo.utils.CacheUtils.getFromCache
import dev.brahmkshatriya.echo.utils.CacheUtils.saveToCache
import dev.brahmkshatriya.echo.utils.CoroutineUtils.await
import dev.brahmkshatriya.echo.utils.CoroutineUtils.future
import dev.brahmkshatriya.echo.utils.CoroutineUtils.futureCatching
import dev.brahmkshatriya.echo.playback.ResumptionUtils.recoverIndex
import dev.brahmkshatriya.echo.playback.ResumptionUtils.recoverTracks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Collections
import java.util.LinkedHashMap

@UnstableApi
abstract class AndroidAutoCallback(
    open val app: App,
    open val scope: CoroutineScope,
    open val extensionList: StateFlow<List<MusicExtension>>,
    open val downloadFlow: StateFlow<List<Downloader.Info>>
) : MediaLibrarySession.Callback {

    val context get() = app.context

    @Volatile private var lastSearchQuery = ""
    private val searchResults = boundedMap<String, List<MediaItem>>()
    private val searchMutex = Mutex()
    private var extensionWatcherJob: Job? = null

    @CallSuper
    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<MediaItem>> {
        if (params?.isRecent == true)
            return Futures.immediateFuture(
                LibraryResult.ofItem(browsableItem("recent", "", browsable = false), null)
            )
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
    ) = Futures.immediateFuture(LibraryResult.ofVoid()).also {
        val extras = params?.extras
        val effectiveQuery = listOfNotNull(
            extras?.getString(MediaStore.EXTRA_MEDIA_TITLE),
            extras?.getString(MediaStore.EXTRA_MEDIA_ARTIST),
            extras?.getString(MediaStore.EXTRA_MEDIA_ALBUM),
            query.takeIf { it.isNotEmpty() }
        ).firstOrNull() ?: query
        Log.d("GladixAuto", "onSearch: rawQuery='$query' effectiveQuery='$effectiveQuery'")
        lastSearchQuery = effectiveQuery
        scope.launch {
            runCatching {
                val tracks = performSearch(effectiveQuery)
                searchResults[query] = tracks
                Log.d("GladixAuto", "onSearch: notifySearchResultChanged query='$query' count=${tracks.size}")
                session.notifySearchResultChanged(browser, query, tracks.size, params)
            }.onFailure { if (it is CancellationException) throw it else it.printStackTrace() }
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
            val enabled = extensions.filter { it.isEnabled }
            Log.d("GladixAuto", "onGetChildren ROOT: extensionList.first size=${extensions.size} enabled=${enabled.size}, ids=${enabled.map { it.id }}")
            return@futureCatching LibraryResult.ofItemList(
                enabled.map { it.toMediaItem(context) },
                null
            )
        }
        if (parentId == "recent") {
            val tracks = context.recoverTracks()
            val index = context.recoverIndex() ?: 0
            val (state, _) = tracks?.getOrNull(index) ?: tracks?.firstOrNull()
                ?: return@futureCatching LibraryResult.ofItemList(ImmutableList.of(), null)
            return@futureCatching LibraryResult.ofItemList(
                ImmutableList.of(state.item.toItem(context, state.extensionId)),
                null
            )
        }
        val extId = parentId.substringAfter("$ROOT/").substringBefore("/")
        val extension = extensions.firstOrNull { it.id == extId }
            ?: return@futureCatching LibraryResult.ofError(
                SessionError(SessionError.ERROR_IO, context.getString(R.string.auto_error_loading))
            )
        val type = parentId.substringAfter("$extId/").substringBefore("/")
        cacheMutex.withLock { withTimeoutOrNull(15_000L) { when (type) {
            ALBUM -> extension.getList<AlbumClient>(context) {
                val id = parentId.substringAfter("$ALBUM/").substringBefore("/")
                val unloaded = itemMap[id] as Album
                val tracks = getTracks(context, id, extId, page) {
                    val album = loadAlbum(unloaded)
                    album to loadTracks(album)
                }
                if (page == 0) listOf(shuffleItem(id, extId, context)) + tracks else tracks
            }

            PLAYLIST -> extension.getList<PlaylistClient>(context) {
                val id = parentId.substringAfter("$PLAYLIST/").substringBefore("/")
                val unloaded = itemMap[id] as Playlist
                val tracks = getTracks(context, id, extId, page) {
                    val playlist = loadPlaylist(unloaded)
                    playlist to loadTracks(playlist)
                }
                if (page == 0) listOf(shuffleItem(id, extId, context)) + tracks else tracks
            }

            RADIO -> extension.getList<RadioClient>(context) {
                val id = parentId.substringAfter("$RADIO/").substringBefore("/")
                val radio = itemMap[id] as Radio
                getTracks(context, id, extId, page) {
                    radio to loadTracks(radio)
                }
            }

            USER -> extension.getList<ArtistClient>(context) {
                val id = parentId.substringAfter("$USER/").substringBefore("/")
                val artist = loadArtist(itemMap[id] as Artist)
                loadFeed(artist).toMediaItems(id, context, extId, page)
            }

            LIST -> extension.getList<ExtensionClient>(context) {
                val id = parentId.substringAfter("$LIST/").substringBefore("/")
                getListsItems(context, id, extId)
            }

            SHELF -> extension.getList<ExtensionClient>(context) {
                val id = parentId.substringAfter("$SHELF/").substringBefore("/")
                getShelfItems(context, id, extId, page)
            }

            HOME -> extension.getFeed<HomeFeedClient>(
                context, parentId, HOME, page
            ) { loadHomeFeed() }

            LIBRARY -> extension.getFeed<LibraryFeedClient>(
                context, parentId, LIBRARY, page
            ) { loadLibraryFeed() }

            FEED -> extension.getList<ExtensionClient>(context) {
                val id = parentId.substringAfter("$ROOT/$extId/$FEED/")
                val feed = feedMap[id] ?: return@getList emptyList()
                feed.toMediaItems(id, context, extId, page)
            }

            SEARCH -> {
                val query = parentId.substringAfter("$ROOT/$extId/$SEARCH/", "")
                extension.getFeed<SearchFeedClient>(
                    context, parentId, SEARCH, page
                ) { loadSearchFeed(query) }
            }

            PLAYLISTS -> extension.getList<LibraryFeedClient>(context) {
                val libFeed = loadLibraryFeed()
                val playlistTab = libFeed.notSortTabs.firstOrNull {
                    it.id.contains("playlist", ignoreCase = true) ||
                    it.title.contains("playlist", ignoreCase = true)
                } ?: libFeed.notSortTabs.firstOrNull()
                Feed<Shelf>(listOf()) { libFeed.getPagedData(playlistTab) }
                    .toMediaItems(parentId, context, extId, page)
            }

            else -> LibraryResult.ofItemList(
                listOfNotNull(
                    if (extension.isClient<HomeFeedClient>())
                        browsableItem("$ROOT/$extId/$HOME", context.getString(R.string.home))
                    else null,
                    if (extension.isClient<SearchFeedClient>())
                        browsableItem("$ROOT/$extId/$SEARCH", context.getString(R.string.search))
                    else null,
                    if (extension.isClient<LibraryFeedClient>())
                        browsableItem("$ROOT/$extId/$LIBRARY", context.getString(R.string.library))
                    else null,
                    if (extension.isClient<LibraryFeedClient>())
                        browsableItem("$ROOT/$extId/$PLAYLISTS", context.getString(R.string.playlists))
                    else null,
                ),
                null
            )
        } } ?: LibraryResult.ofError(
            SessionError(SessionError.ERROR_IO, context.getString(R.string.auto_timed_out))
        ) }
    }

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
        Log.d("GladixAuto", "onGetSearchResult: query='$query' page=$page pageSize=$pageSize lastSearchQuery='$lastSearchQuery' cachedResults=${searchResults[query]?.size ?: "none"}")
        return scope.future {
            val effectiveQuery = lastSearchQuery.ifEmpty { query }
            val allTracks = searchMutex.withLock {
                searchResults[query] ?: performSearch(effectiveQuery).also {
                    searchResults[query] = it
                }
            }
            val from = page * pageSize
            Log.d("GladixAuto", "onGetSearchResult: returning ${allTracks.drop(from).take(pageSize).size} items (total=${allTracks.size}) for query='$query'")
            LibraryResult.ofItemList(allTracks.drop(from).take(pageSize), null)
        }
    }

    private suspend fun performSearch(query: String): List<MediaItem> {
        val extensions = extensionList.value
        Log.d("GladixAuto", "performSearch: query='$query' searching ${extensions.size} extensions")
        return extensions.flatMap { ext ->
            runCatching {
                withTimeout(10_000) {
                    Log.d("GladixAuto", "performSearch: trying ext='${ext.id}'")
                    val client = ext.instance.value().getOrNull() as? SearchFeedClient
                    if (client == null) {
                        Log.d("GladixAuto", "performSearch: ext='${ext.id}' not a SearchFeedClient, skipping")
                        return@withTimeout emptyList<MediaItem>()
                    }
                    val feed = client.loadSearchFeed(query)
                    val tab = feed.notSortTabs.firstOrNull()
                    Log.d("GladixAuto", "performSearch: ext='${ext.id}' feed tabs=${feed.notSortTabs.map { it.title }} using tab=${tab?.title}")
                    val pagedData = feed.getPagedData(tab).pagedData
                    val (shelves, _) = pagedData.loadPage(null)
                    Log.d("GladixAuto", "performSearch: ext='${ext.id}' got ${shelves.size} shelves: ${shelves.map { it::class.simpleName }}")
                    val tracks = shelves.toTracks()
                    Log.d("GladixAuto", "performSearch: ext='${ext.id}' extracted ${tracks.size} tracks")
                    tracks.map { it.toItem(context, ext.id) }
                }
            }.getOrElse {
                if (it is CancellationException) throw it
                Log.d("GladixAuto", "performSearch: ext='${ext.id}' threw ${it::class.simpleName}: ${it.message}")
                emptyList()
            }
        }.also { results ->
            Log.d("GladixAuto", "performSearch: query='$query' total results=${results.size}")
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
            val autoId = mediaItems[0].mediaId.substringAfter("auto/")
            val cached = context.getFromCache<Triple<Track, String, EchoMediaItem?>>(autoId, "auto")
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
                        val allItems = allTracks.map {
                            MediaItemUtils.build(app, downloadFlow.value, MediaState.Unloaded(extId, it), item)
                        }
                        return@future super.onSetMediaItems(
                            mediaSession, controller, allItems.toMutableList(), tappedIndex, startPositionMs
                        ).await(context)
                    }
                }
            }
        }

        val new = mediaItems.mapNotNull {
            if (it.mediaId.startsWith("auto/")) {
                val id = it.mediaId.substringAfter("auto/")
                val (track, extId, con) =
                    context.getFromCache<Triple<Track, String, EchoMediaItem?>>(id, "auto")
                        ?: return@mapNotNull null
                MediaItemUtils.build(
                    app,
                    downloadFlow.value,
                    MediaState.Unloaded(extId, track),
                    con
                )
            } else it
        }
        val future = super.onSetMediaItems(
            mediaSession, controller, new, startIndex, startPositionMs
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

        private fun browsableItem(
            id: String,
            title: String,
            subtitle: String? = null,
            browsable: Boolean = true,
            artWorkUri: Uri? = null,
            type: Int = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
        ) = MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setIsPlayable(false)
                    .setIsBrowsable(browsable)
                    .setMediaType(type)
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setArtworkUri(artWorkUri)
                    .build()
            )
            .build()

        private fun Track.toItem(
            context: Context, extensionId: String, con: EchoMediaItem? = null
        ): MediaItem {
            context.saveToCache(id, Triple(this, extensionId, con), "auto")
            return MediaItem.Builder()
                .setMediaId("auto/$id")
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

        private suspend fun Extension<*>.toMediaItem(context: Context) = browsableItem(
            "$ROOT/$id", name, context.getString(R.string.extension),
            instance.value().isSuccess,
            metadata.icon?.toUri(context)
        )

        @OptIn(UnstableApi::class)
        val notSupported =
            LibraryResult.ofError<ImmutableList<MediaItem>>(SessionError.ERROR_NOT_SUPPORTED)

        @OptIn(UnstableApi::class)
        val errorIo = LibraryResult.ofError<ImmutableList<MediaItem>>(SessionError.ERROR_IO)

        suspend inline fun <reified C> Extension<*>.getList(
            context: Context,
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
            it.printStackTrace()
            LibraryResult.ofError(
                SessionError(SessionError.ERROR_IO, it.message ?: context.getString(R.string.auto_error_loading))
            )
        }


        private val itemMap = boundedMap<String, EchoMediaItem>()
        private fun EchoMediaItem.toMediaItem(
            context: Context, extId: String
        ): MediaItem = when (this) {
            is Track -> toItem(context, extId)
            else -> {
                val id = hashCode().toString()
                itemMap[id] = this
                val (page, type) = when (this) {
                    is Artist, is Radio -> USER to MediaMetadata.MEDIA_TYPE_MIXED
                    is Album -> ALBUM to MediaMetadata.MEDIA_TYPE_ALBUM
                    is Playlist -> PLAYLIST to MediaMetadata.MEDIA_TYPE_PLAYLIST
                    else -> throw IllegalStateException("Invalid type")
                }
                browsableItem(
                    "$ROOT/$extId/$page/$id",
                    title,
                    subtitleWithE,
                    true,
                    cover?.toUri(context),
                    type
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
            page: String,
            pageNumber: Int,
            getFeed: T.() -> Feed<Shelf>
        ): LibraryResult<ImmutableList<MediaItem>> = getList<T>(context) {
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
            return list.map { it.toItem(context, extId, item) }
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