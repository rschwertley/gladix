package dev.brahmkshatriya.echo.ui.media

import android.content.Context
import android.content.Intent
import androidx.core.app.ShareCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.common.Extension
import dev.brahmkshatriya.echo.common.MusicExtension
import dev.brahmkshatriya.echo.common.clients.FollowClient
import dev.brahmkshatriya.echo.common.clients.HideClient
import dev.brahmkshatriya.echo.common.clients.LikeClient
import dev.brahmkshatriya.echo.common.clients.SaveClient
import dev.brahmkshatriya.echo.common.clients.ShareClient
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.Message
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.di.App
import dev.brahmkshatriya.echo.download.Downloader
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.getIf
import dev.brahmkshatriya.echo.extensions.MediaState
import dev.brahmkshatriya.echo.extensions.cache.Cached.bustAlbumTracksCache
import dev.brahmkshatriya.echo.extensions.cache.Cached.bustPlaylistTracksCache
import dev.brahmkshatriya.echo.extensions.cache.Cached.getFeed
import dev.brahmkshatriya.echo.extensions.cache.Cached.getTracks
import dev.brahmkshatriya.echo.extensions.cache.Cached.loadFeed
import dev.brahmkshatriya.echo.extensions.cache.Cached.loadItem
import dev.brahmkshatriya.echo.extensions.cache.Cached.loadTracks
import dev.brahmkshatriya.echo.ui.common.FragmentUtils.gladixLinkFor
import dev.brahmkshatriya.echo.ui.feed.FeedData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted.Companion.Eagerly
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
abstract class MediaDetailsViewModel(
    downloader: Downloader,
    private val app: App,
    private val loadFeeds: Boolean,
    extension: Flow<MusicExtension?>,
) : ViewModel() {
    val extensionFlow = extension.stateIn(viewModelScope, Eagerly, null)
    val downloadsFlow = downloader.flow

    val refreshFlow = MutableSharedFlow<Unit>()
    val cacheResultFlow = MutableStateFlow<Result<MediaState.Loaded<*>>?>(null)
    val itemResultFlow = MutableStateFlow<Result<MediaState.Loaded<*>>?>(null)

    val uiResultFlow = itemResultFlow.combine(cacheResultFlow) { item, cache ->
        item ?: cache
    }.stateIn(viewModelScope, Eagerly, null)

    val cacheExtensionItemFlow = uiResultFlow.map {
        extensionFlow.value to it?.getOrNull()?.item
    }.stateIn(viewModelScope, Eagerly, null to null)

    val extensionItemFlow = itemResultFlow.map { result ->
        extensionFlow.value to result?.getOrNull()?.item
    }.stateIn(viewModelScope, Eagerly, null to null)

    private fun trackFeed(item: EchoMediaItem, extension: Extension<*>) : Result<Feed<Shelf>>? =
        if (item is Track) runCatching {
            PagedData.Concat<Shelf>(
                PagedData.Single {
                    val album = item.album?.let { loadItem(extension, it).getOrNull() ?: it }
                    listOfNotNull(album?.toShelf())
                },
                PagedData.Single {
                    if (item.artists.isEmpty()) return@Single emptyList()
                    item.artists.map { artist ->
                        (loadItem(extension, artist).getOrNull() ?: artist).toShelf()
                    }
                },
            ).toFeed(Feed.Buttons.EMPTY)
        } else null

    val trackCachedFlow = cacheExtensionItemFlow.transformLatest { (extension, item) ->
        emit(null)
        if (!loadFeeds) return@transformLatest
        extension ?: return@transformLatest
        val item = item ?: cacheResultFlow.value?.getOrNull()?.item ?: return@transformLatest
        val feed : Result<Feed<Shelf>?> = trackFeed(item, extension)
            ?: getTracks(app, extension.id, item).map { it?.map { t -> t.toShelf() } }
        emit(feed.map {
            it ?: return@map null
            FeedData.State(extension.id, item, it)
        })
    }.stateIn(viewModelScope, Eagerly, null)

    val tracksLoadedFlow = extensionItemFlow.transformLatest { (extension, item) ->
        emit(null)
        if (!loadFeeds) return@transformLatest
        extension ?: return@transformLatest
        item ?: return@transformLatest
        val feed : Result<Feed<Shelf>?> = trackFeed(item, extension)
            ?: loadTracks(app, extension, item).map { it?.map { t -> t.toShelf() } }
        emit(feed.map {
            it ?: return@map null
            FeedData.State(extension.id, item, it)
        })
    }.stateIn(viewModelScope, Eagerly, null)

    val feedCachedFlow = cacheExtensionItemFlow.transformLatest { (extension, item) ->
        emit(null)
        if (!loadFeeds) return@transformLatest
        extension ?: return@transformLatest
        val item = item ?: cacheResultFlow.value?.getOrNull()?.item ?: return@transformLatest
        val feed = getFeed(app, extension.id, item) ?: return@transformLatest
        emit(feed.map {
            FeedData.State(extension.id, item, it)
        })
    }.stateIn(viewModelScope, Eagerly, null)

    val feedLoadedFlow = extensionItemFlow.transformLatest { (extension, item) ->
        emit(null)
        if (!loadFeeds) return@transformLatest
        extension ?: return@transformLatest
        item ?: return@transformLatest
        val feed = loadFeed(app, extension, item)
        emit(feed.map {
            it ?: return@map null
            FeedData.State(extension.id, item, it)
        })
    }.stateIn(viewModelScope, Eagerly, null)

    fun refresh() = viewModelScope.launch {
        refreshFlow.emit(Unit)
    }

    // Force a fresh canonical re-fetch: bust the durable playlist-tracks entry (so loadTracks skips its
    // 24h short-circuit) then refresh. Gated to pull-to-refresh + edit "reload" — NOT like/save/follow/hide,
    // which call refresh() and must keep serving the cached tracks. No-op for non-playlist items.
    fun refreshTracks() = viewModelScope.launch {
        when (val i = getItem()?.second) {
            is Playlist -> bustPlaylistTracksCache(app, i.id)
            // Albums are cached durably too as of this change, so pull-to-refresh has to bust theirs or the
            // gesture does nothing for 24h on an album page.
            is Album -> bustAlbumTracksCache(app, i.id)
            else -> {}
        }
        refreshFlow.emit(Unit)
    }

    abstract fun getItem(): Triple<String, EchoMediaItem, Boolean>?

    fun likeItem(liked: Boolean) = app.scope.launch {
        val item = itemResultFlow.value?.getOrNull()?.item ?: return@launch
        val extension = extensionFlow.value
        like(app, extension, item, liked)
        refresh()
    }

    fun hideItem(hidden: Boolean) = app.scope.launch {
        val item = itemResultFlow.value?.getOrNull()?.item ?: return@launch
        val extension = extensionFlow.value
        hide(app, extension, item, hidden)
        refresh()
    }

    fun followItem(followed: Boolean) = app.scope.launch {
        val item = itemResultFlow.value?.getOrNull()?.item ?: return@launch
        val extension = extensionFlow.value
        follow(app, extension, item, followed)
        refresh()
    }

    fun saveToLibrary(saved: Boolean) = app.scope.launch {
        val item = itemResultFlow.value?.getOrNull()?.item ?: return@launch
        val extension = extensionFlow.value
        save(app, extension, item, saved)
        refresh()
    }

    fun onShare() = app.scope.launch(Dispatchers.IO) {
        val item = itemResultFlow.value?.getOrNull()?.item ?: return@launch
        val extension = extensionFlow.value
        share(app, extension, item)
    }

    // ⚠⚠ READS THE LOADED ITEM, NOT THE PAGE'S STUB, AND THAT IS WHAT MAKES THE STAMP
    // RELIABLE. UnifiedExtension's loadTrack/loadAlbum/loadArtist/loadPlaylist all end in
    // .withExtensionId(id, this), so a successfully loaded Unified item carries its sub-extension
    // id by construction. Building this from the unloaded item would reintroduce exactly the
    // missing-stamp case gladixLinkFor exists to refuse.
    fun onShareGladixLink() = app.scope.launch(Dispatchers.IO) {
        val item = itemResultFlow.value?.getOrNull()?.item ?: return@launch
        val extensionId = extensionFlow.value?.id ?: return@launch
        val link = gladixLinkFor(extensionId, item) ?: return@launch
        shareGladixLink(app, item, link)
    }

    val isRefreshing get() = itemResultFlow.value == null
    val isRefreshingFlow = itemResultFlow.map {
        isRefreshing
    }

    companion object {

        suspend fun notFound(app: App, id: Int) {
            val notFound = app.context.run { getString(R.string.no_x_found, getString(id)) }
            app.messageFlow.emit(Message(notFound))
        }

        suspend fun createMessage(app: App, message: Context.() -> String) {
            app.messageFlow.emit(Message(app.context.message()))
        }

        suspend fun like(
            app: App, extension: Extension<*>?, item: EchoMediaItem, like: Boolean,
        ) {
            val extension = extension ?: return notFound(app, R.string.extension)
            createMessage(app) {
                getString(
                    if (like) R.string.liking_x else R.string.unliking_x,
                    item.title
                )
            }
            // R = Any? (not Unit): the return value is unused (only null-checked for success below), and
            // an extension whose likeItem was built against a divergent signature can return a non-Unit
            // value (e.g. a String) at runtime. Constraining R to Unit forced a `checkcast kotlin/Unit`
            // on that value → fatal "String cannot be cast to Unit". Any? accepts whatever it returns.
            val result = extension.getIf<LikeClient, Any?>(app.throwFlow) {
                likeItem(item, like)
            }
            if (result != null) createMessage(app) {
                getString(
                    if (like) R.string.liked_x else R.string.unliked_x, item.title
                )
            }
        }

        suspend fun hide(
            app: App, extension: Extension<*>?, item: EchoMediaItem, hide: Boolean,
        ) {
            val extension = extension ?: return notFound(app, R.string.extension)
            createMessage(app) {
                getString(
                    if (hide) R.string.hiding_x else R.string.unhiding_x,
                    item.title
                )
            }
            // R = Any?, same crash-safety as like(): the result is only null-checked, never used.
            val result = extension.getIf<HideClient, Any?>(app.throwFlow) {
                hideItem(item, hide)
            }
            if (result != null) createMessage(app) {
                getString(
                    if (hide) R.string.hidden_x else R.string.unhidden_x,
                    item.title
                )
            }
        }

        suspend fun follow(
            app: App, extension: Extension<*>?, item: EchoMediaItem, follow: Boolean,
        ) {
            val extension = extension ?: return notFound(app, R.string.extension)
            createMessage(app) {
                getString(
                    if (follow) R.string.following_x else R.string.unfollowing_x,
                    item.title
                )
            }
            // R = Any?, same crash-safety as like(): the result is only null-checked, never used.
            val result = extension.getIf<FollowClient, Any?>(app.throwFlow) {
                followItem(item, follow)
            }
            if (result != null) createMessage(app) {
                getString(
                    if (follow) R.string.followed_x else R.string.unfollowed_x,
                    item.title
                )
            }
        }

        suspend fun save(
            app: App, extension: Extension<*>?, item: EchoMediaItem, save: Boolean,
        ) {
            val extension = extension ?: return notFound(app, R.string.extension)
            createMessage(app) {
                getString(
                    if (save) R.string.saving_x else R.string.removing_x,
                    item.title
                )
            }
            // R = Any?, same crash-safety as like(): the result is only null-checked, never used.
            val result = extension.getIf<SaveClient, Any?>(app.throwFlow) {
                saveToLibrary(item, save)
            }
            if (result != null) createMessage(app) {
                getString(
                    if (save) R.string.saved_x_to_library else R.string.removed_x_from_library,
                    item.title
                )
            }
        }

        /**
         * ⚠⚠ PREREQUISITE FOR THE PLANNED "SHARE GLADIX LINK" SIBLING OF THIS FUNCTION:
         * IT MUST PREFER item.extras[EXTENSION_ID] OVER extension.id WHEN BUILDING THE LINK.
         * A Gladix link is `.../gladix/o/?e=<ext>&t=<type>&i=<id>&n=<title>&s=<name>` and the
         * recipient's app rebuilds a STUB - Track(id, name) - with NO extras. UnifiedExtension
         * routes by extras[EXTENSION_ID] (loadStreamableMedia proxies through it), so a link that
         * says `e=unified` is DEAD ON ARRIVAL: Map.extensionId throws
         * ExtensionNotFoundException(null) before anything can resolve.
         * ⚠️ NOT HYPOTHETICAL - THE SHAPE WAS INVESTIGATED IN JULY 2026 as
         * "getExtensionId 'Extension id not found' during backfillQueue -> loadRadio", diagnosed
         * as an item reaching Unified without its EXTENSION_ID stamp. A share link constructs
         * exactly such an item.
         * The loaded item DOES carry the stamp (UnifiedExtension.withExtensionId sets it), so
         * reading it costs nothing - and it is also what the recipient wants, since they may not
         * use Unified at all.
         *
         * ⚠⚠ THE OTHER REQUIREMENT: CONDITION IT THE SAME AS showShare - i.e. REQUIRE
         * ShareClient - AND THE OBVIOUS SHORTCUT IS WRONG. An earlier version of this note said to
         * give it its OWN condition (item.isShareable alone), so extensions with no ShareClient could
         * be shared at all. That is right for a NETWORK extension with no share support and WRONG for
         * OfflineExtension, which is local files on one device and resolvable by nobody - and whose
         * items leave isShareable at its `true` default, because there is no chokepoint to set it at
         * (see the note on that class). isShareable alone would silently admit every Offline item.
         * ⚠️ SO THE GAP IS DELIBERATELY LEFT OPEN: an extension with no ShareClient still
         * cannot be shared, even by a Gladix link that needs no URL from it. CLOSING IT NEEDS A
         * CAPABILITY THAT DOES NOT EXIST - one meaning "my items are resolvable by id", distinct
         * from "I can mint a URL"; see the note on ShareClient for why those are different claims.
         * That is a common-module ABI change plus per-extension adoption, declined. Do not close the
         * gap with the nearest available proxy; it is the proxy that is wrong, not the gap.
         * Add the entry to MediaMoreBottomSheet's button list rather than as a second header icon.
         */
        suspend fun share(
            app: App, extension: Extension<*>?, item: EchoMediaItem,
        ) {
            val extension = extension ?: return notFound(app, R.string.extension)
            createMessage(app) { getString(R.string.sharing_x, item.title) }
            val url = extension.getIf<ShareClient, String>(app.throwFlow) {
                onShare(item)
            } ?: return notFound(app, R.string.extension)
            val intent = ShareCompat.IntentBuilder(app.context)
                .setType("text/plain")
                .setChooserTitle("${extension.name} - ${item.title}")
                .setText(url)
                .createChooserIntent()
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            app.context.startActivity(intent)
        }

        // No createMessage("Sharing X") here, unlike share() above: that message exists because
        // onShare makes a NETWORK call (ShareClient.onShare) and the chooser can lag behind the tap.
        // This mints the URL locally, so the chooser is immediate and a toast would arrive with it.
        fun shareGladixLink(app: App, item: EchoMediaItem, link: String) {
            val intent = ShareCompat.IntentBuilder(app.context)
                .setType("text/plain")
                .setChooserTitle(item.title)
                .setText(link)
                .createChooserIntent()
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            app.context.startActivity(intent)
        }

        private fun <T : Any> Feed<T>.map(transform: (T) -> Shelf) = Feed(tabs) { tab ->
            val data = getPagedData.invoke(tab)
            Feed.Data(
                data.pagedData.map {
                    val list = it.getOrThrow()
                    list.map(transform)
                },
                data.buttons,
                data.background
            )
        }
    }
}