package dev.brahmkshatriya.echo.extensions.cache

import com.mayakapps.kache.FileKache
import dev.brahmkshatriya.echo.common.Extension
import dev.brahmkshatriya.echo.common.clients.AlbumClient
import dev.brahmkshatriya.echo.common.clients.ArtistClient
import dev.brahmkshatriya.echo.common.clients.FollowClient
import dev.brahmkshatriya.echo.common.clients.HideClient
import dev.brahmkshatriya.echo.common.clients.LikeClient
import dev.brahmkshatriya.echo.common.clients.LyricsClient
import dev.brahmkshatriya.echo.common.clients.LyricsSearchClient
import dev.brahmkshatriya.echo.common.clients.PlaylistClient
import dev.brahmkshatriya.echo.common.clients.RadioClient
import dev.brahmkshatriya.echo.common.clients.SaveClient
import dev.brahmkshatriya.echo.common.clients.ShareClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.helpers.Page
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.loadAll
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeedData
import dev.brahmkshatriya.echo.common.models.ImageHolder
import dev.brahmkshatriya.echo.common.models.Lyrics
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Radio
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Tab
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.di.App
import dev.brahmkshatriya.echo.extensions.exceptions.MediaUnavailableException
import dev.brahmkshatriya.echo.extensions.exceptions.WrongItemException
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.getAs
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.getIf
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.isClient
import dev.brahmkshatriya.echo.extensions.MediaState
import dev.brahmkshatriya.echo.extensions.exceptions.AppException.Companion.toAppException
import dev.brahmkshatriya.echo.history.db.toSlim
import dev.brahmkshatriya.echo.utils.CacheUtils
import dev.brahmkshatriya.echo.utils.CacheUtils.getFromCache
import dev.brahmkshatriya.echo.utils.CacheUtils.saveToCache
import dev.brahmkshatriya.echo.utils.Serializer.toData
import dev.brahmkshatriya.echo.utils.Serializer.toJson
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class CachedPlaylistTracks(val savedAtMs: Long, val tracks: List<Track>)

object Cached {
    class NotFound(id: String) : Exception("Cache not found for $id")

    private const val DURABLE_PLAYLIST_FOLDER = "playlist-tracks"
    // SEPARATE FOLDER, not a shared one with a prefixed key. Both stores key on "<id>-tracks", and an album
    // id and a playlist id are different namespaces that can collide on the same string - Deezer numbers
    // both. A shared folder would therefore need the key itself to carry the type, which would invalidate
    // every existing playlist entry for no gain. Two folders cost nothing and cannot collide.
    private const val DURABLE_ALBUM_FOLDER = "album-tracks"
    // Shared by both stores: the reasoning is identical (people reopen the same lists across days, and the
    // things that change underneath - a playlist edited elsewhere, an album's catalog metadata - change far
    // more slowly than that). Every in-app mutation busts its own entry, so this bounds only EXTERNAL edits.
    private const val TRACKS_TTL_MS = 24L * 60 * 60 * 1000  // 24h

    // ══ DURABLE-CACHE ELIGIBILITY ══
    // GENERAL RULE: an item that carries its own expiry in `extras` is NOT written to, or read from, a
    // durable store. It still gets the in-flight dedup and the 5-minute memory layer, which are bounded by
    // the session; only the 24h on-disk entry is refused.
    //
    // The rule is about the EXPIRY, not about any particular kind of item. TRACKS_TTL_MS is our own 24h
    // guess at how fast a list changes underneath us, and it is a good guess for a user-built playlist. An
    // item that ships an expiry has told us the answer directly, and it is not our number: whatever the
    // source says, it supersedes the guess. Caching such an item durably would serve a list the SOURCE has
    // already declared dead, for up to a day, with pull-to-refresh as the only escape — the same failure
    // shape the prefetch asymmetry above exists to prevent.
    //
    // Written as a rule rather than a type check so any future item type that carries an expiry is covered
    // the day it lands, with no edit here. The first producer is Deezer's smarttracklist (the "Made for
    // you" daily mixes, which regenerate ~05:00 and set `expires` from EXPIRATION_DATE — see
    // DeezerParser.toSmartTracklist), but nothing below knows or cares about that.
    //
    // The VALUE is deliberately not parsed. Any format an extension chooses would have to be understood
    // here, and a parse failure would silently fall back to caching — the exact outcome the rule exists to
    // prevent. Presence of the key is the whole contract; "expires" is the reserved name.
    // ⚠⚠ CLOSED 2026-09-12: A SMARTTRACKLIST ID IS A SLOT, NOT A SNAPSHOT - CLOSED ON
    // EVIDENCE PLUS THIS MITIGATION, NOT ON CERTAINTY. The open question was whether `inspired-by-1`
    // names a STABLE DAILY SLOT that Deezer refills, or a SNAPSHOT of one day's generated mix. It decides
    // whether such an id can ever be a durable cache key.
    // THE EVIDENCE, from the STL-ID capture and GetMadeForMe:
    //   - `inspired-by-1` appears as BOTH the smarttracklist id AND the configuration id
    //     (stl=inspired-by-1 configId=inspired-by-1), alongside a SEPARATE compound dataId
    //     `6563868601.3949.1.1125.2179.20260829` that embeds a date. Slot plus instance, carried in two
    //     different fields - which is what a slot model looks like and not what a snapshot model does.
    //   - GetMadeForMe returned `inspired-by-1` through `inspired-by-5` plus `new-releases`: stable,
    //     enumerable, human-readable names. Snapshot ids do not come in a tidy numbered series.
    //   - AND THE LABEL ROTATES UNDER A FIXED ID, which is the same finding from the other side and was
    //     observed independently: module 46b377f1 read "Summer in slow-mo" and later "Hello, sunshine".
    //     A snapshot id would have changed WITH the content; this one outlived an editorial relabel.
    //     (Carried over from DeezerHomeFeedClient.probeSmartTracklist, now deleted - it asserted the slot
    //     model from this observation alone, and the captures above upgrade it from assertion to evidence.)
    // ⚠️ WHY THIS IS SAFE TO CLOSE WITHOUT BEING CERTAIN: THE CONSEQUENCE IS ALREADY MITIGATED
    // EITHER WAY, by the rule immediately below. These items ship `expires` and regenerate daily, so
    // carriesExpiry() already refuses to cache them durably - under BOTH readings. The id's form changes
    // no behaviour today, which is precisely why it does not need to be settled beyond this.
    // ⚠⚠ THIS REOPENS IF ANYTHING EVER CACHES SMARTTRACKLIST CONTENTS DURABLY. The moment the
    // expiry rule below stops covering them - a new cache path that does not consult carriesExpiry, an
    // extension that stops setting `expires`, or a deliberate exception - the slot-vs-snapshot question
    // becomes load-bearing again and the evidence above is suggestive rather than sufficient. Re-open it
    // BEFORE writing such a path, not after.
    private const val EXPIRES_EXTRA = "expires"
    private fun EchoMediaItem.carriesExpiry() = extras.containsKey(EXPIRES_EXTRA)

    // History's toSlim drops ALL extras; a playlist track's NEXT / playlist_id are read by DeezerUtil.log
    // (play-logging context), so preserve exactly those two while dropping streamables + everything heavy.
    private fun Track.toPlaylistSlim(): Track =
        toSlim().copy(extras = extras.filterKeys { it == "NEXT" || it == "playlist_id" })

    // Album tracks carry "album_id" where playlist tracks carry "playlist_id" (see DeezerAlbumClient's
    // loadTracks, which stamps it alongside "NEXT"). Keeping the wrong key would silently drop the context
    // a restored track needs, so the two slim forms cannot be merged.
    private fun Track.toAlbumSlim(): Track =
        toSlim().copy(extras = extras.filterKeys { it == "NEXT" || it == "album_id" })

    // Invalidate the durable playlist-tracks entry so the next loadTracks re-fetches (via the canonical
    // re-resolve path) instead of short-circuiting within TTL. Wired to pull-to-refresh + edit "reload".
    fun bustPlaylistTracksCache(app: App, playlistId: String) =
        bustTracksCache(app, playlistId, DURABLE_PLAYLIST_FOLDER)

    // Albums have no in-app mutation path (you cannot edit an album's track list), so unlike the playlist
    // one this exists for a single caller: pull-to-refresh. Without it, refreshTracks() would bust nothing
    // on an album page and the gesture would silently do nothing for 24h - the same failure the playlist
    // store had until the mutation sites were wired up.
    fun bustAlbumTracksCache(app: App, albumId: String) =
        bustTracksCache(app, albumId, DURABLE_ALBUM_FOLDER)

    private fun bustTracksCache(app: App, id: String, folder: String) {
        runCatching {
            val dir = CacheUtils.cacheDir(app.context, folder, durable = true)
            File(dir, "$id-tracks".hashCode().toString()).delete()
        }
    }

    // ⚠⚠ THE withContext IS THE FIX FOR A MAIN-THREAD ANR, NOT TIDINESS. This is a file read
    // PLUS a JSON decode, and being `suspend` imposes no dispatcher of its own - it ran on whatever
    // thread the caller was on. A cached Page<Shelf> decoded on main ANR'd a budget device (build
    // 1062). Wrapped HERE rather than at the callers because every cache read funnels through this one
    // function - getMedia, getFeed's three reads, and getFeedShelf's recursion through Category /
    // Categories - so a per-caller fix leaves the rest exposed, which is exactly how the 979 closure
    // came back on 1062. PagedSource.load carries the paging-path half; see its note.
    suspend inline fun <reified T> FileKache.getData(id: String) = runCatching {
        withContext(Dispatchers.IO) {
            val file = get(id) ?: throw NotFound(id)
            File(file).readText().toData<T>().getOrThrow()
        }
    }

    // Same hazard in the WRITE direction, same reasoning as getData above: toJson() encodes a whole
    // page and writeText hits the disk, on whatever thread called it. Fixed together because they are
    // one mechanism, not two.
    suspend inline fun <reified T> FileKache.putData(id: String, data: T) = runCatching {
        withContext(Dispatchers.IO) {
            put(id) {
                runCatching {
                    File(it).writeText(data.toJson())
                }.isSuccess
            }
        }
    }

    // ⚠️ PUBLIC INLINE: this body is COPIED into every caller's class file, not called. Changing anything
    // it touches - a member's visibility, its signature, its very existence - silently orphans every
    // caller that is not recompiled in the same run, and neither the compiler nor R8 will say a word. The
    // caller keeps its stale copy, links against the old member, and fails at RUNTIME with
    // NoSuchMethodError. Build 1058 shipped exactly that: `fileCache` went private here, Cached.kt was
    // recompiled, StreamableLoader was not, and every track resolve died calling App.getFileCache().
    // Callers today — NOTE THE SCOPE: this list is every site that inlines ANY of Cached's inline
    // functions, not just the one this comment heads, because the stale-inline hazard is per-CALLER, not
    // per-function. That is why it mixes them: StreamableLoader (loadMedia), MediaViewModel (getMedia and
    // loadMedia), TrackInfoViewModel (loadMedia), UnifiedExtension (getMedia), and the four *Fragment
    // feedData lambdas (getFeedShelf). Adding an inline function here adds its callers to this list.
    // Verified against the tree 2026-09-01.
    // If you edit this body, do a CLEAN build before shipping - app/build.gradle.kts's
    // verifyCleanKotlinOutput enforces that for release/nightly/stable, but it cannot help a local
    // install. `inline` is required here only for `reified T`; if that need ever goes away, drop the
    // keyword and this whole hazard with it.
    suspend inline fun <reified T : EchoMediaItem> getMedia(
        app: App, extensionId: String, itemId: String,
    ) = runCatching {
        val fileCache = app.awaitFileCache()
        val id = "media-$extensionId-$itemId-state"
        fileCache.getData<MediaState.Loaded<T>>(id).getOrThrow()
    }

    // Normalize a media id before the round-trip equality check in loadMedia below. Some extensions return a
    // CANONICALIZED form of the id they were asked for; comparing normalized forms tolerates that without
    // weakening the wrong-item guard — two genuinely different ids still differ after normalizing, so
    // cache-poisoning protection is intact — and it is a NO-OP for ids that round-trip unchanged (e.g.
    // Deezer), so exact-match behaviour is preserved identically. Structured as "normalize then compare": add
    // the next known convention as another transform here, not as another guard.
    // Known conventions:
    //   - YouTube Music wraps a playlist id in a "VL" browse prefix: loadItem("VLPLaJ…") legitimately returns
    //     the same playlist with canonical id "PLaJ…". Strip a leading "VL".
    // @PublishedApi internal so the public inline loadMedia can reference it after inlining.
    @PublishedApi
    internal fun canonicalId(id: String) = id.removePrefix("VL")

    // Ids are EXTENSION-AUTHORED and unbounded, so cap them HERE rather than letting
    // PlayerEventListener's generic MAX_MSG_LEN absorb an arbitrary one — the same reasoning as
    // MAX_EXT_NAME_LEN capping extension names independently of the message budget. A Spotify local-file
    // URI is `spotify:local:{artist}:{album}:{title}:{duration}` fully percent-encoded, so Cyrillic runs
    // to six characters per letter and one id alone passes 300; two of them will not fit any budget worth
    // having.
    //
    // ELIDED FROM THE MIDDLE, NOT TRUNCATED FROM THE END, because the two failure shapes show up in
    // different places. A SUBSTITUTION (a different item entirely, e.g. a local file resolving to the
    // catalogue track it was matched against) is obvious from the head. An ENCODING or ROUND-TRIP
    // difference keeps the same head and differs only in the tail — end-truncation hides exactly that
    // case, which is the one worth catching. Keeping both ends distinguishes them at a glance.
    // 95 chars each, so two ids plus framing land near 240 and fit inside MAX_MSG_LEN.
    @PublishedApi
    internal fun idForMessage(id: String) =
        if (id.length <= 96) id else id.take(47) + "…" + id.takeLast(47)

    // ⚠️ PUBLIC INLINE: this body is COPIED into every caller's class file, not called. Changing anything
    // it touches - a member's visibility, its signature, its very existence - silently orphans every
    // caller that is not recompiled in the same run, and neither the compiler nor R8 will say a word. The
    // caller keeps its stale copy, links against the old member, and fails at RUNTIME with
    // NoSuchMethodError. Build 1058 shipped exactly that: `fileCache` went private here, Cached.kt was
    // recompiled, StreamableLoader was not, and every track resolve died calling App.getFileCache().
    // Callers today — NOTE THE SCOPE: this list is every site that inlines ANY of Cached's inline
    // functions, not just the one this comment heads, because the stale-inline hazard is per-CALLER, not
    // per-function. That is why it mixes them: StreamableLoader (loadMedia), MediaViewModel (getMedia and
    // loadMedia), TrackInfoViewModel (loadMedia), UnifiedExtension (getMedia), and the four *Fragment
    // feedData lambdas (getFeedShelf). Adding an inline function here adds its callers to this list.
    // Verified against the tree 2026-09-01.
    // If you edit this body, do a CLEAN build before shipping - app/build.gradle.kts's
    // verifyCleanKotlinOutput enforces that for release/nightly/stable, but it cannot help a local
    // install. `inline` is required here only for `reified T`; if that need ever goes away, drop the
    // keyword and this whole hazard with it.
    /**
     * @param preferCache serve a previously-cached [MediaState.Loaded] WITHOUT contacting the extension.
     *   Defaults to false. Passed true from StreamableLoader ONLY — the playback path — and deliberately
     *   NOT from MediaViewModel or TrackInfoViewModel, which open the media page where isSaved / isLiked /
     *   followers are exactly the values that must be fresh.
     *
     * ⚠️ THIS MAKES AN EXISTING BEHAVIOUR EXPLICIT RATHER THAN INTRODUCING ONE. Restored Unified tracks
     * already resolve from cache today — but only because UnifiedExtension.loadTrack throws on their
     * missing extension_id and the fallback below catches it. Once that stamp is restored
     * (ResumptionUtils.restamped) the throw stops, and without this flag every restored track would
     * attempt a network call first. Offline that means waiting out the sub-extension's timeouts (Deezer:
     * connect 15s + read 10s) INSIDE THE BUFFERING PATH, where it would meet the 5s buffering watchdog and
     * the 60s stuck detector, on a queue that is the reliable offline case today.
     *
     * ⚠️ STALENESS IS UNBOUNDED, AND THAT IS A CHOSEN POLICY, NOT AN OVERSIGHT. Do not add a TTL here
     * "as an obvious improvement": an age check reintroduces the network attempt for precisely the offline
     * case this exists to protect, which is the one thing it must never do.
     */
    suspend inline fun <reified T : EchoMediaItem> loadMedia(
        app: App, extension: Extension<*>, state: MediaState<T>, preferCache: Boolean = false,
    ) = coroutineScope {
        runCatching {
            // ⚠️ SAFE TO PLAY FROM, AND THIS IS THE REASON — it is not obvious and it is what makes the
            // whole flag sound: a cached MediaState.Loaded carries NO STREAM URL. Streams are resolved
            // separately at play time by loadStreamableMedia, so a stale cached state cannot produce an
            // expired or wrong playback URL. What it can hold stale is display/library metadata, which is
            // why this is confined to the playback path and kept away from the media-page callers.
            //
            // The five capability probes below (isSaved / isFollowed / followers / isLiked / isHidden) are
            // SKIPPED ENTIRELY on this path, not half-refreshed. None of them is displayed for a queue
            // item being resolved for playback, and skipping them is exactly what happens today via the
            // throw — half-refreshing would be new behaviour nobody asked for.
            if (preferCache) getMedia<T>(app, extension.id, state.item.id).getOrNull()
                ?.let { return@runCatching it }
            val result = runCatching {
                val new = loadItem(extension, state.item).getOrThrow()
                // Deleted/unavailable content: some extensions (e.g. Deezer) return a BLANK item (empty id)
                // instead of raising an error when the content no longer exists — a NORMAL condition, not a
                // bug. Surface a friendly, localized "no longer available" message (the header Error holder
                // renders it via getFinalTitle's `?: throwable.message` fallback) rather than the raw
                // wrong-item ISE below. Checked FIRST: a blank id can never legitimately match a real id, so
                // it would otherwise always fall into the mismatch branch. (Still routes through the cache
                // fallback below, so a previously-cached copy is preferred when one exists.)
                if (new.id.isBlank()) throw MediaUnavailableException(
                    app.context.getString(R.string.x_no_longer_available, state.item.title)
                )
                // Compare CANONICAL ids, not raw strings: an extension may legitimately return a canonicalized
                // form of the requested id (see canonicalId — YTM drops its "VL" playlist prefix), and raw
                // equality would false-positive this guard. A genuinely different item still mismatches. The
                // message keeps the RAW ids so a real mismatch stays diagnosable.
                // WrongItemException, not error(): an anonymous IllegalStateException cannot be told
                // apart from any other app-side throw, and the skip reports group on the class. See the
                // note on that type.
                if (canonicalId(new.id) != canonicalId(state.item.id)) throw WrongItemException(
                    "loadItem returned wrong item: expected ${idForMessage(state.item.id)}, " +
                        "got ${idForMessage(new.id)}"
                )
                val isSaved = async {
                    if (new.isSaveable) extension.getIf<SaveClient, Boolean> {
                        isItemSaved(new)
                    } else null
                }
                val isFollowed = async {
                    if (new.isFollowable) extension.getIf<FollowClient, Boolean> {
                        isFollowing(new)
                    } else null
                }
                val followers = async {
                    if (new.isFollowable) extension.getIf<FollowClient, Long?> {
                        getFollowersCount(new)
                    } else null
                }
                val isLiked = async {
                    if (new.isLikeable) extension.getIf<LikeClient, Boolean> {
                        isItemLiked(new)
                    } else null
                }
                val isHidden = async {
                    if (new.isHideable) extension.getIf<HideClient, Boolean> {
                        isItemHidden(new)
                    } else null
                }
                val newState = MediaState.Loaded(
                    item = new,
                    extensionId = extension.id,
                    isSaved = isSaved.await()?.getOrThrow(),
                    isFollowed = isFollowed.await()?.getOrThrow(),
                    followers = followers.await()?.getOrThrow(),
                    isLiked = isLiked.await()?.getOrThrow(),
                    isHidden = isHidden.await()?.getOrThrow(),
                    showRadio = new.isRadioSupported && extension.isClient<RadioClient>(),
                    showShare = new.isShareable && extension.isClient<ShareClient>(),
                )
                val fileCache = app.awaitFileCache()
                val id = "media-${extension.id}-${newState.item.id}-state"
                fileCache.putData(id, newState)
                newState
            }
            // ⚠️ THIS FALLBACK IS CURRENTLY HIDING A REAL DEFECT — see UnifiedExtension.loadTrack.
            // Every RESTORED Unified track throws there (its `extension_id` extra is destroyed on save by
            // toSlim) and lands here, where a previously-cached MediaState.Loaded is served instead. The
            // queue plays, nothing is reported, and restored tracks are silently never re-resolved.
            // That accident is also load-bearing: because the throw precedes any I/O, the cached copy is
            // served INSTANTLY, which is what makes an offline restore work. Correcting the stamp without
            // making this ordering explicit for the playback call site would turn each restored track into
            // a network attempt that must time out first. Read that note before changing either.
            result.getOrElse {
                getMedia<T>(app, extension.id, state.item.id).getOrNull()
                    ?: throw it
            }
        }
    }

    suspend inline fun <reified T : EchoMediaItem> loadItem(
        extension: Extension<*>, item: T,
    ) = runCatching {
        when (item) {
            is Artist -> extension.getAs<ArtistClient, Artist> { loadArtist(item) }
            is Album -> extension.getAs<AlbumClient, Album> { loadAlbum(item) }
            is Playlist -> extension.getAs<PlaylistClient, Playlist> { loadPlaylist(item) }
            is Track -> extension.getAs<TrackClient, Track> { loadTrack(item, false) }
            is Radio -> extension.getAs<RadioClient, Radio> { loadRadio(item) }
        }.getOrThrow() as T
    }

    suspend fun loadStreamableMedia(
        app: App, extension: Extension<*>, track: Track, streamable: Streamable,
    ) = extension.getAs<TrackClient, Streamable.Media> {
        loadStreamableMedia(streamable, false)
    }

    suspend fun getTracks(
        app: App, extensionId: String, item: EchoMediaItem,
    ) = runCatching {
        if (item !is EchoMediaItem.Lists) return@runCatching null
        val itemId = item.id
        // Playlist tracks live in an isolated durable store (canonical re-resolve is expensive). Instant
        // cached read, age-agnostic; loadTracks owns the TTL/revalidate decision.
        val durableFolder = if (item.carriesExpiry()) null else when (item) {
            is Playlist -> DURABLE_PLAYLIST_FOLDER
            is Album -> DURABLE_ALBUM_FOLDER
            else -> null
        }
        if (durableFolder != null) {
            val cached = app.context.getFromCache<CachedPlaylistTracks>(
                "$itemId-tracks", durableFolder, durable = true
            )
            if (cached != null) return@runCatching cached.tracks.toFeed()
        }
        getFeed<Track>(app, extensionId, "$itemId-tracks") { it }
    }

    suspend fun loadTracks(app: App, extension: Extension<*>, item: EchoMediaItem) = runCatching {
        if (item is Playlist) return@runCatching loadPlaylistTracksCached(app, extension, item)
        if (item is Album) return@runCatching loadAlbumTracksCached(app, extension, item)
        val feed = when (item) {
            is Album -> null // handled above
            is Radio -> extension.getAs<RadioClient, Feed<Track>> { loadTracks(item) }
            is Artist -> null
            is Track -> null
            is Playlist -> null // handled above
        }?.getOrThrow() ?: return@runCatching null
        savingFeed(app, extension, "${item.id}-tracks", feed)
    }

    // ══ SHARED IN-FLIGHT / SHORT-TTL LAYER ══
    // One map serving two jobs that are the same object at different points in its life:
    //   a RUNNING Deferred  -> in-flight dedup. A second caller for the same key joins it instead of
    //                          issuing an identical second request.
    //   a COMPLETED Deferred -> a short-lived memory cache. await() on a finished Deferred returns its
    //                          value immediately.
    // Before this, Cached had no dedup at all: two concurrent callers for one playlist both fetched. That
    // is a real sequence, not a theoretical one - it is exactly what a prefetch warm followed by the user
    // tapping the item produces, and without this the tap would race a second identical request and make
    // the warmed case SLOWER than the unwarmed one.
    //
    // Owned by app.scope, NOT the caller's scope. The whole point is that the work outlives whichever
    // caller happened to start it: a caller cancelled mid-flight (collectLatest restarting, a fragment
    // going away) must not take the shared result down with it. app.scope carries a SupervisorJob and an
    // exception handler, so a failed child cannot escalate.
    //
    // ⚠️ EVICTION ON FAILURE IS DELIBERATE, AND IT IS THE ONE PLACE THE TWO JOBS DISAGREE. Read before
    // "simplifying" it away:
    //   a CONCURRENT caller SHOULD share a failure - it is awaiting the same Deferred, it asked at the
    //     same time, and issuing a second request for something that just failed helps nobody;
    //   a LATER caller MUST NOT inherit it - a five-minute-old failure is not evidence about now, and
    //     caching it would turn one transient network error into five minutes of a dead playlist with no
    //     way for the user to retry except pull-to-refresh.
    // So the entry is removed the moment it completes exceptionally, via invokeOnCompletion. Anyone already
    // awaiting still gets the throw (dedup half); anyone arriving afterwards finds nothing and starts fresh
    // (cache half). Removing by VALUE (`remove(key, fresh)`) so a newer entry that replaced this one is
    // never clobbered.
    // This reads like a bug - "why does the cache drop failures immediately?" - which is why it is spelled
    // out here rather than left to be inferred from a one-line remove().
    private const val MEMORY_TTL_MS = 5L * 60 * 1000

    private val inFlightTracks = ConcurrentHashMap<String, Pair<Long, Deferred<List<Track>>>>()

    private suspend fun coalescedTracks(
        app: App, key: String, block: suspend () -> List<Track>,
    ): List<Track> {
        val now = System.currentTimeMillis()
        // Opportunistic sweep. No timer, no eviction thread: the map only ever holds keys somebody asked
        // for recently, and it is swept on every call, so it cannot grow unbounded.
        inFlightTracks.entries.removeIf { now - it.value.first > MEMORY_TTL_MS }
        var created: Pair<Long, Deferred<List<Track>>>? = null
        val entry = inFlightTracks.compute(key) { _, existing ->
            if (existing != null && now - existing.first <= MEMORY_TTL_MS) existing
            else (now to app.scope.async { block() }).also { created = it }
        }!!
        // Registered OUTSIDE compute: compute holds the bin lock, and a completion handler that mutates the
        // same map from inside it is a deadlock waiting for a fast-failing block.
        created?.let { fresh ->
            fresh.second.invokeOnCompletion { cause ->
                if (cause != null) inFlightTracks.remove(key, fresh)
            }
        }
        return entry.second.await()
    }

    // Durable SWR on top of the canonical re-resolve. Within TTL: pure short-circuit (no fetch, no
    // revalidate). Otherwise fetch via the extension (the song.getListData re-resolve path), materialize
    // (Deezer = PagedData.Single → one page), store slim {now, tracks}, and return the fresh tracks.
    private suspend fun loadPlaylistTracksCached(
        app: App, extension: Extension<*>, item: Playlist,
    ): Feed<Track> {
        // See carriesExpiry: an item with its own expiry skips BOTH the read and the write below, so it
        // falls through to the coalesced fetch every open and is never served from disk.
        val durable = !item.carriesExpiry()
        val cached = if (!durable) null else app.context.getFromCache<CachedPlaylistTracks>(
            "${item.id}-tracks", DURABLE_PLAYLIST_FOLDER, durable = true
        )
        if (cached != null && System.currentTimeMillis() - cached.savedAtMs < TRACKS_TTL_MS)
            return cached.tracks.toFeed()

        // canonicalId for the MEMORY key, raw item.id for the DURABLE key, and the difference is
        // load-bearing. Some extensions return a canonicalized id from loadItem - YouTube Music strips a
        // "VL" playlist prefix - so the same playlist reaches us as "VLPLaJ..." from a feed row and
        // "PLaJ..." after loading. The durable entry is only ever written from a real open, which always
        // carries the loaded id, so it is self-consistent on the raw value. The memory entry is the one a
        // prefetch warm (unloaded id) and a user tap (loaded id) must BOTH hit, so it has to normalize or
        // every warm would silently miss and the feature would do nothing at all.
        val tracks = coalescedTracks(app, "playlist:${canonicalId(item.id)}") {
            val feed = extension.getAs<PlaylistClient, Feed<Track>> { loadTracks(item) }.getOrThrow()
            feed.loadAll()
        }
        if (durable) app.context.saveToCache(
            "${item.id}-tracks",
            CachedPlaylistTracks(System.currentTimeMillis(), tracks.map { it.toPlaylistSlim() }),
            DURABLE_PLAYLIST_FOLDER, durable = true
        )
        return tracks.toFeed()
    }

    // PREFETCH ENTRY POINT. Fills the shared in-flight / memory layer for a playlist the user has not
    // opened, so a subsequent tap joins a running request or reads a completed one instead of starting its
    // own.
    //
    // (!) IT DELIBERATELY DOES NOT WRITE THE DURABLE STORE, and that asymmetry is the whole safety
    // argument. A warm is handed the UNLOADED feed-row item, because that is all a feed row has. Every
    // PLAYLIST loadTracks implementation checked reads only the id and routing extras - Deezer, Offline,
    // Unified, Spotify, Tidal - so a warm is safe for them. But third-party extensions are sideloaded and
    // unbounded, and Spotify's ALBUM loadTracks is a live example of the failure shape: handed an unloaded
    // item it SUCCEEDS and returns plausible-but-thinner data rather than throwing. If a warm could write
    // the durable store, one such extension would poison a 24h entry and the user would see a wrong or
    // empty track list for a day, with pull-to-refresh as the only escape.
    // Confining a warm to the 5-minute memory layer bounds that to one session while still delivering the
    // whole benefit, because the sequence this exists to serve - pause, then tap - happens in seconds. Only
    // a REAL open, which always passes the loaded item, writes the durable entry.
    //
    // Errors are swallowed: a warm that fails is a wasted request and nothing more, and must never surface
    // to a user who did not ask for it. coalescedTracks evicts the failed entry, so the tap that follows
    // starts clean rather than inheriting the failure.
    suspend fun warmPlaylistTracks(app: App, extension: Extension<*>, item: Playlist) {
        // Skip if the durable entry already covers it - warming something cached for 24h is pure waste.
        // Checked on the RAW id, because that is what the durable store is keyed on. For an extension that
        // canonicalizes (YouTube Music's "VL" prefix) this check can miss and warm unnecessarily, costing
        // one request; the result still lands correctly in the memory layer, which IS canonical-keyed.
        val cached = app.context.getFromCache<CachedPlaylistTracks>(
            "${item.id}-tracks", DURABLE_PLAYLIST_FOLDER, durable = true
        )
        if (cached != null && System.currentTimeMillis() - cached.savedAtMs < TRACKS_TTL_MS) return
        runCatching {
            coalescedTracks(app, "playlist:${canonicalId(item.id)}") {
                extension.getAs<PlaylistClient, Feed<Track>> { loadTracks(item) }.getOrThrow().loadAll()
            }
        }
    }

    // Album equivalent of loadPlaylistTracksCached. Same shape deliberately, with three differences that
    // are real rather than cosmetic:
    //   AlbumClient.loadTracks returns a NULLABLE Feed - an album may legitimately have no track list at
    //     all. That is a structural property, not an empty result, so a null is returned WITHOUT caching:
    //     writing it as an empty list would make "this client does not expose tracks" indistinguishable
    //     from "this album has none", and the 24h TTL would make the confusion sticky.
    //   toAlbumSlim, not toPlaylistSlim - different context extra (see there).
    //   a separate durable folder - album and playlist ids share a string namespace (see the constants).
    private suspend fun loadAlbumTracksCached(
        app: App, extension: Extension<*>, item: Album,
    ): Feed<Track>? {
        // Same eligibility rule as the playlist path — see carriesExpiry. No album ships an expiry today;
        // it is here so the rule is genuinely general and does not have to be rediscovered when one does.
        val durable = !item.carriesExpiry()
        val cached = if (!durable) null else app.context.getFromCache<CachedPlaylistTracks>(
            "${item.id}-tracks", DURABLE_ALBUM_FOLDER, durable = true
        )
        if (cached != null && System.currentTimeMillis() - cached.savedAtMs < TRACKS_TTL_MS)
            return cached.tracks.toFeed()

        // "album:" prefix for the same reason the folders are separate: coalescedTracks is ONE map, and an
        // album and a playlist sharing an id string would otherwise share an in-flight entry and serve each
        // other's tracks. canonicalId for the same reason as the playlist path.
        val tracks = coalescedTracks(app, "album:${canonicalId(item.id)}") {
            val feed = extension.getAs<AlbumClient, Feed<Track>?> { loadTracks(item) }.getOrThrow()
                ?: return@coalescedTracks emptyList()
            feed.loadAll()
        }
        if (tracks.isEmpty()) return null
        if (durable) app.context.saveToCache(
            "${item.id}-tracks",
            CachedPlaylistTracks(System.currentTimeMillis(), tracks.map { it.toAlbumSlim() }),
            DURABLE_ALBUM_FOLDER, durable = true
        )
        return tracks.toFeed()
    }

    suspend fun getFeed(
        app: App, extensionId: String, item: EchoMediaItem,
    ) = run {
        if (item !is EchoMediaItem.Lists) return@run null
        val itemId = item.id
        getFeedShelf(app, extensionId, itemId)
    }

    suspend fun loadFeed(app: App, extension: Extension<*>, item: EchoMediaItem) = runCatching {
        val feed = when (item) {
            is Artist -> extension.getAs<ArtistClient, Feed<Shelf>> { loadFeed(item) }
            is Album -> extension.getAs<AlbumClient, Feed<Shelf>?> { loadFeed(item) }
            is Playlist -> extension.getAs<PlaylistClient, Feed<Shelf>?> { loadFeed(item) }
            is Track -> extension.getAs<TrackClient, Feed<Shelf>?> { loadFeed(item) }
            is Radio -> null
        }?.getOrThrow() ?: return@runCatching null
        savingFeed(app, extension, item.id, feed)
    }

    suspend fun loadLyrics(app: App, extension: Extension<*>, lyrics: Lyrics) = runCatching {
        val fileCache = app.awaitFileCache()
        val id = "lyrics-${extension.id}-${lyrics.id}"
        val loaded = extension.getAs<LyricsClient, Lyrics> {
            loadLyrics(lyrics)
        }.getOrElse { throwable ->
            fileCache.getData<Lyrics>(id).getOrNull() ?: throw throwable
        }
        fileCache.putData(id, loaded)
        loaded
    }

    suspend fun getLyricsFeed(
        app: App, extensionId: String, clientId: String, track: Track, query: String,
    ) = runCatching {
        val id = if (query.isEmpty()) "lyrics-$clientId-${track.id}" else "lyrics-search-$query"
        getFeed<Lyrics>(app, extensionId, id) { it }
    }

    suspend fun loadLyricsFeed(
        app: App, extension: Extension<*>, clientId: String, track: Track, query: String,
    ) = runCatching {
        val feed = if (query.isEmpty()) extension.getAs<LyricsClient, Feed<Lyrics>> {
            searchTrackLyrics(clientId, track)
        }.getOrThrow() else extension.getAs<LyricsSearchClient, Feed<Lyrics>> {
            searchLyrics(query)
        }.getOrThrow()
        val id = if (query.isEmpty()) "lyrics-$clientId-${track.id}" else "lyrics-search-$query"
        savingFeed(app, extension, id, feed)
    }

    suspend fun getFeedShelf(
        app: App, extensionId: String, feedId: String,
    ): Result<Feed<Shelf>> = runCatching {
        getFeed<Shelf>(app, extensionId, feedId) { shelf ->
            when (shelf) {
                is Shelf.Item -> shelf
                is Shelf.Category -> shelf.copy(
                    feed = getFeedShelf(app, extensionId, shelf.id).getOrNull()
                )

                is Shelf.Lists.Categories -> shelf.copy(
                    list = shelf.list.map {
                        it.copy(feed = getFeedShelf(app, extensionId, it.id).getOrNull())
                    },
                    more = getFeedShelf(app, extensionId, shelf.id).getOrNull()
                )

                is Shelf.Lists.Items -> shelf.copy(
                    more = getFeedShelf(app, extensionId, shelf.id).getOrNull()
                )

                is Shelf.Lists.Tracks -> shelf.copy(
                    more = getFeedShelf(app, extensionId, shelf.id).getOrNull()
                )
            }
        }
    }


    // FEED STUFF

    suspend inline fun <reified T : Any> getFeed(
        app: App, extensionId: String, feedId: String, crossinline transform: suspend (T) -> T,
    ): Feed<T> {
        val fileCache = app.awaitFileCache()
        val tabId = "feed-$extensionId-$feedId"
        val tabs = fileCache.getData<List<Tab>>(tabId).getOrThrow()
        return Feed(tabs) { tab ->
            val id = "$tabId-${tab?.id}"
            val (buttons, bg) = fileCache.getData<Pair<Feed.Buttons?, ImageHolder?>>(id)
                .getOrThrow()
            PagedData.Continuous { token ->
                val id = "$id-$token"
                val page = fileCache.getData<Page<T>>(id).getOrThrow()
                page.copy(page.data.map { transform(it) })
            }.toFeedData(buttons, bg)
        }
    }

    suspend inline fun <reified T : Any> savingFeed(
        app: App, extension: Extension<*>, feedId: String, feed: Feed<T>,
    ): Feed<T> {
        val fileCache = app.awaitFileCache()
        val tabId = "feed-${extension.id}-$feedId"
        fileCache.putData(tabId, feed.tabs)
        return Feed(feed.tabs) { tab ->
            val data = runCatching {
                feed.getPagedData(tab)
            }.getOrElse {
                throw it.toAppException(extension)
            }
            val (pagedData, buttons, bg) = data
            val id = "$tabId-${tab?.id}"
            fileCache.putData(id, Pair(buttons, bg))
            PagedData.Continuous { token ->
                val page = runCatching {
                    pagedData.loadPage(token)
                }.getOrElse {
                    throw it.toAppException(extension)
                }
                val id = "$id-$token"
                fileCache.putData(id, page)
                page
            }.toFeedData(buttons, bg)
        }
    }

}