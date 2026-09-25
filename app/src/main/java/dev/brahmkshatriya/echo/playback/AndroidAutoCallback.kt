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
import dev.brahmkshatriya.echo.playback.MediaItemUtils.isReplayableContext
import dev.brahmkshatriya.echo.playback.MediaItemUtils.track
import dev.brahmkshatriya.echo.extensions.MediaState
import dev.brahmkshatriya.echo.extensions.builtin.offline.OfflineExtension
import dev.brahmkshatriya.echo.extensions.builtin.unified.UnifiedExtension
import dev.brahmkshatriya.echo.extensions.exceptions.AppException.Companion.toAppException
import dev.brahmkshatriya.echo.utils.CrashKeys
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

    // ⚠️ ONE OF FIVE QUEUE-REPLACEMENT MARKERS - the full inventory, what each answers, and why
    // none subsumes another is at ShufflePlayer.markQueueReplaced. Read it before adding a sixth.
    // THIS one is a ONE-WAY LATCH answering "has the user established a queue this session?", for
    // USER-vs-COLD-RESTORE arbitration only. It carries no queue identity and never goes back down, so it
    // cannot tell you whether the queue you started work against still exists - that is ShufflePlayer's
    // queueEpoch. It is also set at a DIFFERENT set of sites (radio/trackRadio/playItem/onSetMediaItems, but
    // not backfillQueue/addToQueue/addToNext), which is deliberate and documented there.
    internal val userQueueSet = AtomicBoolean(false)
    @Volatile private var lastSearchQuery = ""
    @Volatile protected var lastBrowsedExtId: String? = null
    private val searchResults = boundedMap<Pair<String, String>, List<MediaItem>>()
    private val searchJobs = boundedMap<String, Job>()
    private val searchMutex = Mutex()
    // ⚠️ THIS JOB'S LIFETIME IS THE WHOLE OF THE REMAINING PROTECTION AGAINST THE JUNE IPC LOOP.
    // START HERE if a notification loop ever reappears, rather than re-deriving it.
    //
    // The June loop ("extensionWatcherJob calling notifyChildrenChanged() to a System UI phantom binding
    // creates an unthrottled IPC loop") had TWO causes and got TWO fixes: this job is cancelled in
    // onDisconnected, AND isPlaybackOngoing() was rekeyed off bare playWhenReady — the latter mattered
    // because it made the 10-minute idle timeout a no-op, so the SERVICE NEVER WENT AWAY and the loop had
    // somewhere to live.
    // THE SECOND FIX IS GONE. The isPlaybackOngoing() override was removed when it became final in 1.10.1,
    // justified by onTaskRemoved() "already correctly handling equivalent behaviour: when CLOSE_PLAYER=false
    // the if block is skipped entirely, service stays alive when paused with a populated queue". That is
    // SERVICE LIFETIME ON TASK REMOVAL. It is a different property from idle-timeout-driven service teardown
    // and has nothing to do with phantom binding, so it does NOT carry the persistence half forward. Stated
    // as fact, not suspicion: the persistence half of the June protection no longer exists.
    //
    // WHAT ACTUALLY BOUNDS IT TODAY — watcher lifetime alone, in two places, both COUNTING watchers rather
    // than rate-limiting notifies:
    //   • `extensionWatcherJob?.cancel()` at the top of onGetLibraryRoot — a re-root REPLACES the watcher
    //     instead of accumulating one per root. This is the one that closes the feedback edge.
    //   • the cancel in onDisconnected — stops a watcher outliving its browser and firing into a dead binding.
    // The feedback edge is real and visible in the DHU capture of 2026-09-07: onGetLibraryRoot at 11:39:35.150
    // produced a notify at 11:39:35.151. One millisecond — that is the StateFlow replaying its current value
    // into the fresh collect. So EVERY RE-ROOT EMITS A NOTIFY IMMEDIATELY, and if a notify can induce a
    // re-bind and re-root through a phantom binding, the cycle closes.
    // With the two cancels the AMPLIFICATION FACTOR IS 1: each re-root yields one notify, never N. The
    // dangerous version was N surviving watchers multiplied by every emission. Bounded, NOT throttled — the
    // notify rate equals the extensionList emission rate, which is user-paced (install / enable / disable).
    private var extensionWatcherJob: Job? = null
    private var pendingSearchJob: Job? = null

    override fun onDisconnected(session: MediaSession, controller: MediaSession.ControllerInfo) {
        userQueueSet.set(false)
        lastBrowsedExtId = null
        pendingSearchJob?.cancel()
        // Load-bearing, not tidy-up: half of the June IPC-loop protection. See the note on the field — the
        // other half (isPlaybackOngoing keeping the service alive past the idle timeout) is GONE, so watcher
        // lifetime is now the entire bound. Note this fires per RENEGOTIATION, not per connection.
        extensionWatcherJob?.cancel()
        super.onDisconnected(session, controller)
    }

    @CallSuper
    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<MediaItem>> {
        if (params?.isRecent == true) return scope.futureCatching {
            // recoverTracks() decodes the (unbounded) saved queue. onGetLibraryRoot runs on the app looper, so
            // decode OFF it — a large queue was a synchronous ANR on connect. onGetLibraryRoot already returns
            // a ListenableFuture; AA awaits the resolved root exactly as before, only the decode location moved.
            val tracks = context.recoverTracks()
            val rawIndex = context.recoverIndex() ?: 0
            // Same repair as recoverPlaylist so the AA resume tile shows the true current, not a skewed one.
            val index = resolveCurrentIndex(tracks ?: emptyList(), rawIndex, context.recoverCurrentId()) {
                it.first.item.id
            }
            val track = (tracks?.getOrNull(index) ?: tracks?.firstOrNull())?.first?.item
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
        }
        if (browser.packageName != "com.google.android.projection.gearhead")
            return Futures.immediateFuture(
                LibraryResult.ofItem(browsableItem(ROOT, "", browsable = false), null)
            )
        Log.d("GladixAuto", "onGetLibraryRoot: extensionList.value.size=${extensionList.value.size}, clearing caches, starting watcher")
        // Load-bearing: this replace-don't-accumulate cancel is what keeps the June loop's amplification
        // factor at 1, because the collect below replays the current StateFlow value immediately (measured:
        // root 11:39:35.150 -> notify 11:39:35.151). See the note on extensionWatcherJob.
        extensionWatcherJob?.cancel()
        extensionWatcherJob = scope.launch {
            cacheMutex.withLock {
                clearCaches()
                searchResults.clear()
                // Pairs with heap_used_mb_conn, which is sampled in PlayerCallback.onConnect — i.e.
                // BEFORE this clear, and before onGetLibraryRoot even runs. Without a post-clear sample
                // every conn number in the OOM investigation measures the graph the PREVIOUS session
                // left behind, not what the connect allocates. conn → root drop implicates these caches;
                // no drop moves suspicion to root-build allocation (which force-instantiates every
                // enabled extension via isClient<…>). One sampleHeap, same style as the six existing.
                CrashKeys.onAutoCachesCleared()
            }
            extensionList.collect { extensions ->
                Log.d("GladixAuto", "extensionWatcher: collected ${extensions.size} extensions: ${extensions.map { it.id }}")
                if (extensions.isNotEmpty()) {
                    // ⚠️ THE ROOT NOTIFY BELOW IS A PROVEN NO-OP. MEASURED, NOT SUSPECTED. It is kept
                    // deliberately — see "why keep it" at the end — but DO NOT BUILD ANYTHING ON IT, and do
                    // not "fix" it by swapping overloads: both were tried.
                    //
                    // DHU capture, build 1085, 2026-09-07, verbatim:
                    //   12:14:41.340  ROOT subscribers=0 pkgs=[] includesRootBrowser=false   <- at connect
                    //   12:14:41.340  calling notifyChildrenChanged ROOT count=6 (broadcast)
                    //   12:14:42.399  onGetChildren ROOT: size=6 enabled=4   <- Gearhead's own browse, 1s AFTER
                    //   12:15:16.798  ROOT subscribers=0 pkgs=[] includesRootBrowser=false   <- toggle off
                    //   12:15:22.972  ROOT subscribers=0 pkgs=[] includesRootBrowser=false   <- toggle on
                    //   (no onGetChildren ROOT after either toggle)
                    // An earlier run on the TARGETED overload was identical in outcome: control ROOT query at
                    // connect, silence after both toggles, and there too the notify PRECEDED the connect-time
                    // query (11:39:35.151 vs 11:39:36.193), so even that query was Gearhead's own initial
                    // browse rather than a response.
                    //
                    // BOTH CANDIDATE CAUSES ARE NOW CLOSED:
                    //   (a) NOTHING IS SUBSCRIBED TO ROOT — CONFIRMED. subscribers=0 holds AT CONNECT as well
                    //       as after each toggle, so it is not a late-subscription race: no subscription is
                    //       ever established. Not Gearhead, and pkgs=[] means not System UI either.
                    //   (b) WRONG ControllerInfo TARGETED (the defect "recent" had) — REFUTED. With zero
                    //       subscribers, no overload can deliver: broadcast applies the same isSubscribed
                    //       gate per controller (MediaLibrarySessionImpl.java:302).
                    // So this call has never been deliverable, by either overload, and on current evidence
                    // has done nothing since it was written. Gearhead queries ROOT exactly once per connect
                    // and takes no notifications for it.
                    //
                    // WHAT WOULD MAKE IT WORK: nothing on our side. Subscription is the CLIENT's choice — the
                    // session cannot subscribe on a controller's behalf. Only a client that calls subscribe()
                    // on ROOT can make this deliver. DHU is one client on one harness; a real head unit may
                    // differ, which is the whole reason the probe stays.
                    //
                    // BROADCAST OVERLOAD KEPT (was targeted at `browser`), for two reasons: the targeted form
                    // was aimed at the browser that ISSUES onGetLibraryRoot, and that browser is provably NOT
                    // a subscriber, so targeting it is wrong in principle and not merely ineffective; and the
                    // "recent" call on the next line is already broadcast, so one idiom across the pair stops
                    // the next reader from "correcting" one to match the other in the wrong direction. It is
                    // behaviourally identical today (zero subscribers => both are no-ops) and would reach a
                    // future subscriber that the targeted form would miss. pkgs=[] confirms the swap opened
                    // no System UI dispatch.
                    //
                    // PROBE KEPT, TRIMMED. It answers a question now answered, which is the argument for
                    // deleting it — but we are deliberately keeping a call that does nothing, and this line is
                    // the only thing that will say so. Without it the next reader sees a notify, assumes
                    // delivery, and re-derives this whole investigation; with it, one glance at logcat settles
                    // it. Cost is one map read per extensionList emission, and emissions are user-paced
                    // (install / enable / disable). includesRootBrowser was dropped: it only discriminated
                    // cause (b), which is closed.
                    val rootSubs = session.getSubscribedControllers(ROOT)
                    Log.d(
                        "GladixAuto",
                        "extensionWatcher: ROOT subscribers=${rootSubs.size} pkgs=${rootSubs.map { it.packageName }}"
                    )
                    Log.d("GladixAuto", "extensionWatcher: calling notifyChildrenChanged ROOT count=${extensions.size} (broadcast, no-op while subscribers=0)")
                    session.notifyChildrenChanged(ROOT, extensions.size, null)
                    // NOT known to be a no-op: "recent" has a separate subscription set, and the record has
                    // AA responding to it immediately with onGetChildren("recent"). Leave it alone.
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
            // Raised from 10s, and no longer a hard cliff. Until the CombinedRepository sentinel fix
            // this predicate was satisfied within milliseconds by the built-ins, so the timeout never
            // realistically fired; it now genuinely waits for the ImportType.App package scan.
            // NOTE: degrading to a "built-ins only" list is NOT available here — during the scan
            // `music` is empty (ExtensionLoader.injected maps the null sentinel to emptyList), and the
            // built-ins live privately inside CombinedRepository rather than being published
            // separately. So the only safe degradations are to wait longer and to re-read the value
            // once on expiry (which catches the narrow race where it lands as the timeout fires).
            // The scan is one-time, bounded and off the main thread; the headroom exists for budget
            // eMMC hardware. Expiring still yields the explicit "timed out" tile rather than an empty
            // root, because an empty root reads to the user as "this app has nothing in it".
            withTimeoutOrNull(30_000L) { extensionList.first { it.isNotEmpty() } }
                ?: extensionList.value.takeIf { it.isNotEmpty() }
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
        // ⚠⚠ [DECISION 2026-09-23] AA SHOWED DEEZER WITH NO ITEMS WHILE YOUTUBE MUSIC WORKED -
        // SEEN ONCE, NOT PURSUED. Play updated the app mid-drive (1107 -> 1109) with Android Auto
        // connected; a full reconnect fixed it. The examined hypothesis was a credential-hydration
        // race: a cold process, AA reconnects and browses before ExtensionLoader's currentUsersFlow
        // delivers setLoginUser, so Deezer's handleArlExpiration throws LoginRequired on empty
        // credentials. IT IS PROBABLY NOT THE CAUSE, and the reason is worth keeping: AA's root build
        // calls Injectable.value() for EVERY enabled extension (Extension<*>.toMediaItem, for the
        // browsable flag), and that same value() drains the queued setLoginUser - so hydration
        // completes during ROOT BUILD, before any deep node is ever requested. For the race to bite,
        // the Room emission would have to land after a root build that itself waits up to 30s for the
        // package scan.
        // NOT REPRODUCIBLE HERE: the installed app is Play-signed and local builds are debug-signed,
        // so `adb install -r` cannot mimic the update restart, and force-stop is not equivalent (it
        // sets the stopped state, and the manual launch needed to clear it warms value() and destroys
        // the race). NOT PURSUING UNLESS IT RECURS.
        // ⚠️ IF IT RECURS, THE TILE TEXT IS THE DISCRIMINATOR AND IT COSTS NOTHING TO READ:
        //   "Failed to load content"                       auto_error_loading -> an extension
        //       threw; getList also prints a stack trace and emits to throwFlow, so System.err will
        //       name it (ClientException$LoginRequired for the hydration story).
        //   "Content took too long to load, please try again"  auto_timed_out -> the
        //       withTimeoutOrNull(15_000) below expired. Throws nothing, prints nothing, reports
        //       nothing - silence on System.err is what distinguishes it.
        // There is NO log line anywhere in this per-node path, so deep-node silence is not evidence
        // that the browse did not run.
        //
        // ⚠️ KNOWN GAP, RECORDED 2026-09-07, DELIBERATELY NOT FIXED HERE. This per-node dispatch
        // resolves extId against the RAW list: `extensions` is extensionList.value verbatim (the else-branch
        // at the top of onGetChildren), while the ROOT branch just above filters
        // `isEnabled && id != UNIFIED_ID`. This branch filters NEITHER. So a parentId of "<root>/unified/..."
        // browses Unified inside Android Auto, and "<root>/<disabled-ext>/..." browses an extension the user
        // has turned off. The next line compounds it: lastBrowsedExtId is written from this same unfiltered
        // parse — see the gap note on getCurrentExtension below and its twin in PlayerCallback.
        //
        // LINE DRIFT, FOR ANYONE MATCHING THIS AGAINST THE OLDER RECORD: the earlier session that found this
        // cited AndroidAutoCallback:371/:375/:377. Same three statements, moved; they are :392/:393/:394 at
        // HEAD 2026-09-07 and they live in AndroidAutoCallback.onGetChildren. Navigate by the symbol.
        //
        // ⚠️ REACHABLE ON A CURRENT BUILD, WITH NO VERSION HISTORY INVOLVED. A head unit caches the
        // browse tree it was served; the user then disables that extension. The cached node is still on
        // screen and still routes here. Worth stating explicitly, because the ORIGINAL INCOMPLETE FIX (May:
        // the guard that filtered the root listing and getCurrentExtension) was reasoned about purely in
        // terms of OLD builds' stale trees, which made the residue look like it would age out. It does not
        // age out. Disable-while-cached regenerates it on demand, on the newest build there is.
        //   ⚠️ AND THERE IS NO PARTIAL MITIGATION. An earlier revision of this note claimed the ROOT
        //   watcher softened this — that a disable re-emitting the flow while connected would invalidate the
        //   ROOT tile, leaving only deep nodes exposed. MEASURED AND REFUTED on DHU 2026-09-07: nothing is
        //   subscribed to ROOT, so that notify delivers to no one (see the proven-no-op record at the
        //   extension watcher in onGetLibraryRoot). NOTHING INVALIDATES ANYTHING.
        //   The gap is therefore WIDER than first recorded, and confirmed rather than inferred: Gearhead
        //   queries ROOT exactly once per connect, so a disabled extension's TILE STAYS ON THE ROOT SCREEN
        //   for the whole session and browsing into it works. No deep-node-retention assumption is needed.
        //   The one thing that does clear it is the NEXT CONNECT, when onGetLibraryRoot runs and Gearhead
        //   re-queries ROOT (measured: root 12:14:41.340 -> ROOT query 12:14:42.399). So the stale state is
        //   session-scoped, not permanent — and per the onDisconnected note, renegotiations are frequent
        //   mid-drive, which shortens it further.
        //
        // ⚠️ WHY THIS KEEPS RECURRING — THE FREQUENCY-DROP TRAP. THIS IS THE THIRD APPEARANCE OF
        // THE SHAPE. The May guard cut the crash rate far enough that Crashlytics AUTO-RESOLVED the issue,
        // the auto-resolution was read as a completed fix, and this dispatch was never touched. Both gaps
        // recorded now are QUIETER STILL, because neither one crashes at all: this line serves a perfectly
        // working browse of the wrong extension, and getCurrentExtension tier 1 serves a perfectly working
        // search against a disabled one. THERE IS NOTHING FOR CRASHLYTICS TO COUNT. Absence of reports is
        // therefore not evidence of absence here, and a falling graph must never be read as a fix landing.
        //
        // ⚠️ AND WHY A PRODUCER-SIDE FILTER NEEDS EVERY CONSUMER CHECKED. Both patterns are live
        // in this codebase and only one is safe by construction:
        //   SAFE     — UnifiedExtension.extensions() is filtered ONCE at its producer (setMusicExtensions,
        //              `id != UNIFIED_ID && metadata.isEnabled`). All ~25 `extensions().get(id)` consumers
        //              inherit that and CANNOT opt out; a new consumer is safe without knowing the rule.
        //   NOT SAFE — extensionList.value is the unfiltered flow, filtered at SOME call sites (the ROOT
        //              branch, aaEligible) and not at others (here). Every new consumer is a fresh chance to
        //              forget, and forgetting is silent.
        // A filter applied at a call site is a convention, not a guarantee. Enumerate the consumers.
        //
        // ⚠️ THE PREDICATE FIX IS NOT SHIPPED, AND AS OF 2026-09-07 IT IS NOT RECOMMENDED. The reasoning
        // is below; it changed when the refresh companion was refuted and when the tier-1 fix (change 2,
        // shipped) removed the sharp edge. Read the whole block before reviving it — "just add the predicate"
        // is the conclusion this note exists to argue against.
        //
        // WHY NOT: the harm that actually bit was the VOICE-SEARCH TARGET — lastBrowsedExtId written from
        // this unfiltered parse, then used unfiltered by getCurrentExtension. THAT IS ALREADY CLOSED, by
        // aaEligible on tier 1 in both getCurrentExtension implementations. What remains here is browse
        // COHERENCE: during the session in which the user disabled an extension, its cached tile still works.
        // Rejecting converts "works when it arguably should not" into "fails, with no way to clear it in
        // session" — a visible regression the user's own action caused, traded for coherence. Bad trade.
        // The Unified half of the predicate is available separately and nearly worthless: ROOT has filtered
        // Unified since May, so a "<root>/unified/…" node can only come from a pre-May cached tree, which
        // genuinely does age out. Costs nothing, gains nothing. Take it only for closure on that axis.
        // WHAT WOULD CHANGE THE ANSWER: a non-zero subscriber count at the probe, which would make the
        // failure self-clearing again. That is the only open one.
        // ⚠️ THE MEMORY ARGUMENT IS CLOSED, NOT OPEN — AND IT CLOSES AGAINST CHANGE 1. An earlier version
        // of this note wondered whether browsing a stale tile force-instantiates an extension the user
        // disabled, which would have been a real cost worth the error tile. IT DOES NOT. The pinning happens
        // at ROOT BUILD time, in Extension.toMediaItem, for every ENABLED extension — before the user taps
        // anything. A DISABLED extension is filtered out of that set and is therefore NOT instantiated by the
        // root build; and every enabled extension is already pinned regardless of what any tile does. So
        // browsing a stale tile costs no instantiation that has not already happened, and the disabled case
        // costs none at all. Do not reopen this as a reason for the predicate.
        // The force-instantiation is a REAL and separate problem with its own lever — see the note at
        // Extension.toMediaItem. It is parked there, not here.
        //
        // ── THE ROOT-REFRESH COMPANION: BUILT, TESTED, REFUTED, DELETED 2026-09-07 ──
        // Rejecting here returns auto_error_loading for a tile the head unit is STILL DISPLAYING, and the
        // tile does not go away by itself within the session. So a companion was scoped that would fire a
        // ROOT refresh on rejection to make the failure self-clearing — a one-shot flag, a captured
        // rootBrowser field with a ControllerInfo equality guard, a per-renegotiation ceiling.
        // ALL OF IT RESTED ON AN INVALIDATION THAT CANNOT ARRIVE, and the DHU capture below killed it. The
        // mechanism is deleted rather than parked: it is not "not yet built", it is refuted. Do not rebuild
        // it without first re-running the subscriber probe and getting a NON-ZERO answer.
        // Falling back to the ROOT LISTING instead was separately considered and rejected, and that still
        // stands on its own reasoning: returning ROOT children under a "<root>/<extId>/…" parentId leaves the
        // breadcrumb naming the dead extension while the list shows every other one, and the back stack then
        // holds a node whose contents contradict its title.
        //
        // THE SOURCE FINDINGS SURVIVE THE REFUTATION AND ARE WORTH KEEPING — they are what a future reader
        // would otherwise re-derive backwards from the "recent" precedent (media3-session 1.11.0):
        //   • A TARGETED NOTIFY TO AN UNSUBSCRIBED CONTROLLER IS A SILENT NO-OP.
        //     notifyChildrenChangedOnHandler ends in `if (!isSubscribed(callback, parentId)) return;`
        //     (MediaLibrarySessionImpl.java:302). No error, no log, nothing.
        //   • SUBSCRIPTIONS ARE KEYED BY ControllerCb, NOT BY PACKAGE OR ControllerInfo.equals.
        //     onSubscribeOnHandler stores `checkNotNull(browser.getControllerCb())` into
        //     controllerToSubscribedParentIds, and isSubscribed does containsEntry on it
        //     (MediaLibrarySessionImpl.java:224-227, :258-260). The controller that ISSUES a browse and the
        //     one that SUBSCRIBES are free to be different objects — which is exactly how the "recent" bug
        //     worked, and why "we targeted the browser we were handed" is not a safety argument.
        //   • BROADCAST IS THE OVERLOAD THAT CAN REACH SYSTEM UI; TARGETED CANNOT. The 3-arg form loops
        //     getConnectedControllers (MediaLibrarySessionImpl.java:271-281) and each delivery passes through
        //     the media-notification -> getSystemUiControllerInfo redirect at :293-298 before the same
        //     isSubscribed gate. So broadcast is NOT "spray at everyone" — but it is the one with a System UI
        //     path. The "recent" fix switched TO broadcast; that lesson INVERTS if the concern is System UI.
        //   • MediaLibrarySession.getSubscribedControllers(mediaId) is PUBLIC (MediaLibraryService.java:828)
        //     and reads parentIdToSubscribedControllers. Subscription state can be READ; never again infer it
        //     from silence, which is what cost this investigation two DHU runs.
        val extId = parentId.substringAfter("$ROOT/").substringBefore("/")
        // The isNotEmpty() condition is DELIBERATE, NOT INCIDENTAL: lastBrowsedExtId updates only on DEEPER
        // paths ("<root>/<extId>/home") and deliberately IGNORES tab-level clicks ("<root>/<extId>"). Reason
        // on record: the tab-level calls arrive as a BURST OF ALL-EXTENSION PREFETCHES, and letting them
        // write would corrupt the value to whichever prefetch landed last. Simplifying this to an
        // unconditional assignment reintroduces that corruption, and it would do so silently — the symptom
        // is a voice search running against an extension the user never browsed into.
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
                // ⚠️ isUserInitiated = false, AND THIS IS THE BROWSE-REFRESH PATH, NOT THE VOICE PATH.
                // The query here is parsed back out of a parentId that a PREVIOUS search produced, so this
                // re-serves an existing result page whenever Android Auto re-requests the node. Recording it
                // would write the same query again on every refresh.
                // ⚠️ DO NOT "FIX" onGetSearchResult/performSearch FOR CONSISTENCY — IT IS DELIBERATELY LEFT
                // AS A USER SEARCH. The user asked for that one OUT LOUD; a spoken search is as much a
                // gesture as a typed one, and it belongs in their history. The distinction is
                // gesture-vs-not, NOT typed-vs-not.
                val query = parentId.substringAfter("$ROOT/$extId/$SEARCH/", "")
                extension.getFeed<SearchFeedClient>(
                    context, parentId, page, throwableFlow
                ) { loadSearchFeed(query, isUserInitiated = false) }
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

    // TIER 1 WAS UNDER-FILTERED — FIXED 2026-09-07. It applied only `it != UNIFIED_ID` to the STRING, so a
    // DISABLED extension reached through a cached browse node became the voice-search target and STAYED it
    // (lastBrowsedExtId persists). Unified was caught; disabled was not. This base body was weaker still:
    // aaEligible itself omitted isEnabled, so NO tier checked it. Both are closed — aaEligible now carries
    // isEnabled and tier 1 runs the resolved extension through it, matching the live override
    // (PlayerCallback.getCurrentExtension, the only subclass). This body is the declared default and must
    // not drift from it again.
    // The id tier 1 reads is still written by AndroidAutoCallback.onGetChildren from an UNFILTERED parse of
    // parentId — THAT GAP IS STILL OPEN, see the note there. This fix closes the consumer, not the source.
    //
    // ⚠️ THE FIX DID NOT MAKE THIS FIELD TRUSTWORTHY — DO NOT READ IT AS MAKING getCurrentExtension
    // CORRECT. aaEligible closed the DISABLED/UNIFIED axis. TWO OTHER AXES SURVIVE IT, and they are
    // DIFFERENT PROBLEMS — keep them apart, because a fix for one does nothing for the other:
    //   (i)  RACINESS — "WHICH CONCURRENT WRITE WON". lastBrowsedExtId is @Volatile and shared across ALL
    //        in-flight browse futures, so under concurrent onGetChildren calls it is simply whichever future
    //        wrote last: which extension wins is NONDETERMINISTIC, disabled or not. An earlier session went
    //        looking for a per-request discriminator at this boundary and checked parentId,
    //        browser.packageName, params, page/pageSize, call ordering and time-since-connect; its conclusion
    //        was that NO RELIABLE DISCRIMINATOR EXISTS at the onGetChildren boundary. Needs a different
    //        mechanism, not a stronger predicate.
    //   (ii) STALENESS — "THE VALUE WAS NEVER UPDATED AT ALL". lastBrowsedExtId is written ONLY on an AA
    //        browse-into, and NEVER on a phone-side extension switch. So if the user changes extension on the
    //        phone while AA is connected, this field still names the one they last browsed in the car — a
    //        STALE-BUT-ENABLED id, which sails through aaEligible untouched. Not a race: nothing competed,
    //        the write simply never happened.
    //
    // Neither state crashes: the search runs and returns real results, just from the wrong extension. See
    // the frequency-drop trap recorded at onGetChildren's per-node dispatch — there is nothing to count.
    // ⚠⚠ `aaEligible` IS AA-SPECIFIC AND MUST NOT BE FACTORED OUT AS A SHARED "which extension"
    // PREDICATE. It vets lastBrowsedExtId, an untrusted id; a caller that reads `extensions.current`
    // DIRECTLY has a different question and can get a WRONG answer from this one. Worked example,
    // 2026-09-13: applying it in `SearchViewModel.resolve` was proposed and REJECTED, because search
    // history is written per-extension by SearchFragment.feedData's UNGATED
    // `music.getExtension(argId) ?: current.value!!` -> `saveInHistory`, so gating only the read would
    // aim the read and the write at different SharedPreferences files. Unified is a LEGITIMATE answer
    // there and an ineligible one here. Full reasoning at that symbol.
    protected open fun getCurrentExtension(): MusicExtension? {
        val aaEligible = { ext: MusicExtension ->
            ext.isEnabled && ext.id != UnifiedExtension.UNIFIED_ID
        }
        return lastBrowsedExtId
            ?.let { id -> extensionList.value.firstOrNull { it.id == id } }
            ?.takeIf(aaEligible)
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
                    // Wrap for ATTRIBUTION only: this path calls loadSearchFeed directly (:555-564) rather
                    // than through ExtensionUtils.get, so without this the throwable reaches App.throwFlow
                    // unwrapped and throwing_extension_id records "none" for every AA search failure.
                    // Emitting the wrapped form does not change what this function returns, nor the AA tile
                    // text. Safe re CancellationException: toAppException rethrows it (AppException.kt:65),
                    // but both cancellation cases are already handled above.
                    throwableFlow?.emit(it.toAppException(ext))
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
            // Build the UNSHUFFLED album order, then shuffle the LIST. Record the unshuffled order so the
            // ensuing setMediaItems keeps it as `original` and flips the shuffle flag ON (parity with the phone
            // Shuffle button: a later toggle-OFF restores album order). The flag flip is changeQueue-free — the
            // queue is applied already shuffled, so nothing re-shuffles.
            val unshuffled = tracks.loadAll().map {
                MediaItemUtils.build(app, downloadFlow.value, MediaState.Unloaded(extId, it), item)
            }
            val shuffled = unshuffled.shuffled()
            // Player access MUST be on the application/main thread — this future runs on a background
            // dispatcher (see CoroutineUtils.future default). notifyShuffleTileOriginal only sets a field,
            // but it's consumed on the main thread when Media3 applies the returned queue, so hop to Main
            // for ordering/visibility (and parity with the syncShuffleFlag fix below).
            if (unshuffled.isNotEmpty()) withContext(Dispatchers.Main) {
                (mediaSession.player as? ShufflePlayer)?.notifyShuffleTileOriginal(unshuffled)
            }
            return@future super.onSetMediaItems(
                mediaSession, controller, shuffled.toMutableList(), 0, startPositionMs
            ).await(context)
        }

        // Single-track tap from a playlist/album: expand to full queue at correct position
        if (mediaItems.size == 1 && mediaItems[0].mediaId.startsWith("auto/")) {
            // In-order tap (all branches below build current+upcoming): sync the shuffle flag/icon OFF WITHOUT
            // changeQueue. Set here (not after the framework applies the returned queue) because the ensuing
            // setMediaItems doesn't touch the flag, so this survives — and `original` becomes the in-order queue.
            // Main-thread hop: this future runs on a background dispatcher, but syncShuffleFlag mutates the
            // ExoPlayer (setShuffleModeEnabled), which asserts main-thread access — the build-999 "wrong
            // thread" crash. Only the player mutation moves to Main; resolution/build below stay on IO.
            withContext(Dispatchers.Main) {
                (mediaSession.player as? ShufflePlayer)?.syncShuffleFlag(false)
            }
            val auto = parseAutoId(mediaItems[0].mediaId)
            val cached = auto?.let {
                context.getFromCache<Triple<Track, String, EchoMediaItem?>>(it.t, "auto", durable = true)
            }
            if (cached != null) {
                val (track, extId, con) = cached
                if (con.isReplayableContext()) {
                    val tracksEntry = tracksMap.values.find { (item, _) ->
                        item is EchoMediaItem.Lists && item.id == con.id
                    }
                    if (tracksEntry != null) {
                        val (item, pagedData) = tracksEntry
                        val allTracks = pagedData.loadAll()
                        // A miss here means the context no longer contains the tapped track — it was
                        // removed from the playlist since it was played. Starting at the top is the
                        // defensible fallback for that, but it must not be SILENT: an identical silent
                        // fallback (this one and freshContextUpcoming's `?: 0`) hid a radio-routing bug for
                        // two months by making the wrong track look like a deliberate default. Radio can no
                        // longer reach here (isReplayableContext above), so a miss now genuinely means
                        // "removed since".
                        val rawIndex = allTracks.indexOfFirst { it.id == track.id }
                        if (rawIndex < 0) Log.w(
                            "GladixContext",
                            "tapped track not in freshly loaded context: ctx=${con.id} — starting at 0"
                        )
                        val tappedIndex = rawIndex.coerceAtLeast(0)
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
            // ⚠️ PARKED WORK — THIS LINE FORCE-INSTANTIATES EVERY ENABLED EXTENSION, PERMANENTLY.
            // Verified at HEAD 2026-09-07. The project record cites this as AndroidAutoCallback:922; it is
            // this statement in Extension.toMediaItem. NAVIGATE BY THE SYMBOL — a replacement line number is
            // not recorded here on purpose: the first version of this note gave one and it was stale by the
            // end of the same edit, which is the drift the house rule exists to stop.
            //
            // The ROOT branch of onGetChildren does `enabled.map { it.toMediaItem(context) }`, so opening the
            // AA browse root runs instance.value() for EVERY enabled non-Unified extension — purely to set a
            // browsable/playable flag on a tile. Injectable.data is `lazy { runCatching { getter() } }`
            // (common/…/helpers/Injectable.kt) with NO release and NO WeakReference, so the resulting object
            // graphs are PINNED FOR THE PROCESS LIFETIME: one AA browse permanently retains Spotify + Tidal +
            // SoundCloud + Deezer at once. Belongs with the Car/AA heap work (the per-item payload note at
            // MediaItemUtils.toMetaData is its sibling), not with any browse-routing fix.
            // ⚠️ THE TIMING IS LOAD-BEARING ELSEWHERE: because this fires at ROOT BUILD for ENABLED
            // extensions only, browsing a stale tile costs no instantiation that has not already happened,
            // and a DISABLED extension is never instantiated here at all. That is what closes the memory
            // argument against the browse-filter fix in onGetChildren's per-node dispatch note. If this
            // line ever moves to a lazier trigger, THAT NOTE'S CONCLUSION CHANGES TOO — revisit both.
            //
            // CHEAPEST LEVER ON RECORD: read `instance.data.isInitialized()` instead — a Lazy state read that
            // instantiates nothing.
            // ⚠️ BUT IT IS NOT A DROP-IN, AND THE ONE-LINE FORM OF THIS ADVICE HIDES THAT. The two express
            // different things: `success` means "this extension LOADS", isInitialized() means "this extension
            // is ALREADY LOADED". A never-instantiated extension would report false and its tile would be
            // marked unbrowsable, which breaks browsing into any extension on a fresh connect — the exact
            // opposite of the intent. Taking the lever means DECIDING WHAT THE FLAG SHOULD SAY when load
            // state is unknown; the defensible answer is optimistic (mark it browsable, discover failure on
            // tap, where an error is already handled) but that is a behaviour change and needs its own pass.
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
            // Wrap for ATTRIBUTION only: client.block() (:952) is a direct extension call, not routed
            // through ExtensionUtils.get, so unwrapped throwables make throwing_extension_id read "none"
            // for the whole AA browse path. Deliberately wraps ONLY the emit — the SessionError below
            // still reads the RAW `it.message`, so the AA tile text is byte-identical to before (the
            // wrapped form would differ for NotSupported/Other; that's a separate cosmetic call, not
            // this change). Safe re CancellationException: rethrown on the line above, before
            // toAppException's own rethrow (AppException.kt:65) could fire.
            throwableFlow?.emit(it.toAppException(this))
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