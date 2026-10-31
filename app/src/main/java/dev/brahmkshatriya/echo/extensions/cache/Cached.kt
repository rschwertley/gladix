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
import dev.brahmkshatriya.echo.common.helpers.MediaUnavailableException
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

/**
 * ⚠⚠ NEW FIELDS MUST BE DEFAULTED, BECAUSE OLD ENTRIES ON DISK MUST STILL DECODE.
 * These files live in filesDir and survive an upgrade, a force-close and Android's Clear cache
 * (see CacheUtils.cacheDir), so there is always a population written by the previous build.
 * A default means an old entry decodes with writtenByUsAtMs = null, which reads as "not ours" and
 * simply takes the normal revalidation path - no migration, no version field, nothing to fail.
 * ⚠⚠ [AMENDED 2026-10-15] THE LAST CLAUSE NO LONGER HOLDS - THERE IS NOW A VERSION
 * FIELD, and it exists because a default could not express the change that needed expressing. A
 * default handles a NEW field whose absence is meaningful ("not ours"); it cannot handle an entry
 * whose EXISTING fields were written wrong. [CachedPlaylistTracks.schema] is for the second case.
 * ⚠️ writtenByUsAtMs IS THE POST-SAVE TRUST WINDOW. Set only by
 * [Cached.writePlaylistTracksCache], i.e. only when WE committed an edit and know the resulting
 * order. For TRUST_WINDOW_MS afterwards a revalidation may not replace the list, so a read endpoint
 * that lags the write it just acknowledged cannot put the user's pre-edit order back on screen.
 */
@Serializable
data class CachedPlaylistTracks(
    val savedAtMs: Long,
    val tracks: List<Track>,
    val writtenByUsAtMs: Long? = null,
    /**
     * Entry format version, compared against [Cached.TRACKS_SCHEMA] on every read.
     *
     * ⚠⚠ 0 MEANS "WRITTEN BEFORE 2026-10-15" AND MUST BE REJECTED. Schema-0 entries
     * were written through an extras ALLOWLIST that kept only "NEXT" and "playlist_id"
     * ("album_id" for albums), so every track in them lost UnifiedExtension's `extension_id` - and
     * a Unified track without it cannot be routed at all: UnifiedExtension.loadTrack throws
     * ExtensionNotFoundException(null), "Media item has no extension stamp". These files live in
     * filesDir and survive Clear cache, so they cannot be waited out - the FIRST cold start after
     * the upgrade has to refuse them.
     * ⚠️ REJECTED, NOT DELETED. An entry nobody reads never refreshes its lastModified,
     * so CacheUtils.saveToCache's oldest-first eviction reclaims schema-0 files as new ones arrive.
     * No migration pass, and nothing to get wrong on a path that runs before playback.
     */
    val schema: Int = 0,
)

object Cached {
    class NotFound(id: String) : Exception("Cache not found for $id")

    private const val DURABLE_PLAYLIST_FOLDER = "playlist-tracks"
    // The memo and the durable store are keyed DIFFERENTLY on purpose (canonical vs raw id, see the
    // note at the coalescedTracks call below), so the prefixes are named once here rather than
    // spelled out at each of the five sites that touch them.
    private const val MEMO_PLAYLIST_PREFIX = "playlist:"
    // SEPARATE FOLDER, not a shared one with a prefixed key. Both stores key on "<id>-tracks", and an album
    // id and a playlist id are different namespaces that can collide on the same string - Deezer numbers
    // both. A shared folder would therefore need the key itself to carry the type, which would invalidate
    // every existing playlist entry for no gain. Two folders cost nothing and cannot collide.
    private const val DURABLE_ALBUM_FOLDER = "album-tracks"
    private const val MEMO_ALBUM_PREFIX = "album:"
    // Shared by both stores: the reasoning is identical (people reopen the same lists across days, and the
    // things that change underneath - a playlist edited elsewhere, an album's catalog metadata - change far
    // more slowly than that). Every in-app mutation busts its own entry, so this bounds only EXTERNAL edits.
    // ⚠⚠ 24h STILL APPLIES TO ALBUMS, AND NO LONGER TO PLAYLISTS. The Aug 3 decision
    // was one TTL for both; it was revisited on 2026-10-15 because a playlist is MUTABLE and an album
    // is not. A user who reorders a playlist and sees the old order for the rest of the day is a bug
    // report (and was one); an album's track list cannot change from inside this app at all, so the
    // short-circuit there is pure win and stays. Playlists now paint from the store and revalidate -
    // see [loadPlaylistTracksCached].
    private const val TRACKS_TTL_MS = 24L * 60 * 60 * 1000  // 24h, ALBUMS ONLY

    /** How long after our own save a revalidation may refresh nothing. See [CachedPlaylistTracks]. */
    private const val TRUST_WINDOW_MS = 90L * 1000

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

    /**
     * ⚠⚠ ALL EXTRAS ARE KEPT EXCEPT THE TOKEN, AND THE ALLOWLIST THAT USED TO BE HERE
     * SHIPPED A TOTAL PLAYBACK FAILURE. It kept exactly "NEXT" and "playlist_id" ("album_id" for
     * albums) - the keys DeezerUtil.log reads - and dropped everything else, including
     * UnifiedExtension's `extension_id`. Every Unified track served from this store was therefore
     * unroutable: `extras={}` in the log, ExtensionNotFoundException(null) from
     * UnifiedExtension.loadTrack, a 25-track queue skipped in under half a second. Introduced
     * d221f4c2 (2026-08-02); the 2026-10-15 paint-at-any-age change turned it from "broken for 24h
     * after each open" into permanent, which is when it became reportable.
     * ⚠️ AN ALLOWLIST CANNOT BE MADE CORRECT HERE, which is why the fix is not "add two
     * more keys". `extras` is populated by extensions, including third-party ones this repo cannot
     * read - the YouTube Music and Spotify contracts are unknown here - so any allowlist is a bet
     * that nobody needs a key we did not think of. Keeping everything removes the bet. NOW KEPT and
     * previously destroyed: `extension_id` and `cached` (UnifiedExtension routing), GAIN (loudness),
     * FALLBACK_ID and FILESIZE_MP3_MISC (Deezer's fallback and quality-ladder choice), plus every
     * third-party key we cannot enumerate.
     *
     * ⚠⚠ TRACK_TOKEN IS THE ONE EXCLUSION, AND IT IS AN EXPIRY DECISION RATHER THAN A
     * SIZE ONE. Since paint-at-any-age these entries never expire, so a stored token can be WEEKS
     * old - and a stale token is a failure mode with history: it broke queue restore on 2026-05-11,
     * and since 1115 a token error can STEP DOWN THE QUALITY LADDER
     * (DeezerTrackClient.createStreamableForQuality's "Track token has no sufficient rights" branch)
     * instead of refreshing, so the user silently gets a lower bitrate. Omitting it instead routes
     * Deezer into the loadTrack self-heal it has taken for every cached track since 2026-08-02 -
     * proven in the field by ten weeks of working Deezer playback, which is exactly why this whole
     * defect stayed invisible.
     * ⚠️ THE SELF-HEAL'S COST IS ACCEPTED, NOT OVERLOOKED: one api.track per track per
     * cached open (the gate at DeezerTrackClient.loadTrack fires on an EMPTY token), and it
     * re-fetches by TOP-LEVEL id, which overwrites grafted art - see the note at MediaItemUtils'
     * size block. That is a known, bounded cost on a path that demonstrably works.
     * ⚠️ REVISIT KEEPING THE TOKEN ONLY AFTER TRACING A STALE ONE END TO END ON DEVICE -
     * i.e. establishing which branch a weeks-old token actually reaches at stream time, the
     * refresh branch or the step-down one. It cannot be settled by reading: both branches match on
     * server-supplied strings. Do not re-add it as an optimisation.
     *
     * ⚠️ WHAT KEEPING THE REST COSTS, MEASURED RATHER THAN GUESSED: the 2026-09 capture at
     * MediaItemUtils' size note puts extras at 0.9 KB of a 6.0 KB item (in-memory chars x2, so
     * roughly half that as UTF-8 JSON on disk), and TRACK_TOKEN dominated that figure - so dropping
     * it takes most of the increase back. The folder cap is 50 MB with oldest-first eviction
     * (CacheUtils.CACHE_FOLDER_SIZE), which at ~1.7-2 KB per track is well over a thousand
     * twenty-five-track entries either way. The bound was never the thing at risk; `streamables` is
     * the heavy field and toSlim still drops it.
     *
     * ONE FUNCTION, NOT TWO: the old pair differed only in which context key it allowlisted, so the
     * note claiming the two forms "cannot be merged" expired with the allowlist itself.
     */
    private fun Track.toTracksCacheSlim(): Track =
        toSlim().copy(extras = extras - "TRACK_TOKEN")

    /**
     * Bump when the stored shape changes meaning; see [CachedPlaylistTracks.schema].
     * internal rather than private so that field's KDoc link resolves.
     */
    internal const val TRACKS_SCHEMA = 1

    /**
     * The ONLY read of either tracks store. ⚠️ EVERY CALLER GOES THROUGH HERE so the
     * schema check cannot be forgotten at one of the four sites - which is the whole reason this is a
     * function and not an inline `?.takeIf` repeated four times.
     */
    private fun readTracksEntry(
        app: App, itemId: String, folder: String,
    ): CachedPlaylistTracks? =
        app.context.getFromCache<CachedPlaylistTracks>("$itemId-tracks", folder, durable = true)
            ?.takeIf { it.schema == TRACKS_SCHEMA }

    // Invalidate the durable playlist-tracks entry so the next loadTracks re-fetches (via the canonical
    // re-resolve path) instead of short-circuiting within TTL. Wired to pull-to-refresh + edit "reload".
    fun bustPlaylistTracksCache(app: App, playlistId: String) =
        bustTracksCache(app, playlistId, DURABLE_PLAYLIST_FOLDER, MEMO_PLAYLIST_PREFIX)

    // Albums have no in-app mutation path (you cannot edit an album's track list), so unlike the playlist
    // one this exists for a single caller: pull-to-refresh. Without it, refreshTracks() would bust nothing
    // on an album page and the gesture would silently do nothing for 24h - the same failure the playlist
    // store had until the mutation sites were wired up.
    fun bustAlbumTracksCache(app: App, albumId: String) =
        bustTracksCache(app, albumId, DURABLE_ALBUM_FOLDER, MEMO_ALBUM_PREFIX)

    // ⚠⚠ BOTH LAYERS, AND THE MEMO IS THE ONE THAT MADE THE BUST A NO-OP. Deleting
    // only the file left [coalescedTracks]' 5-minute entry holding the PRE-EDIT list, so the very
    // next load returned it with no fetch at all - and then [loadPlaylistTracksCached] wrote that
    // stale list back with a fresh timestamp, RENEWING the 24h lease instead of clearing it. A
    // reporter saw the old order survive a save, repeated refreshes, a force-close and a Clear cache
    // (the durable store is in filesDir by design - see CacheUtils.cacheDir - so Clear cache cannot
    // touch it), until the memo finally aged out. Clearing the memo here is what makes
    // pull-to-refresh and the editor's "reload" actually reach Deezer.
    private fun bustTracksCache(app: App, id: String, folder: String, memoPrefix: String) {
        runCatching {
            val dir = CacheUtils.cacheDir(app.context, folder, durable = true)
            File(dir, "$id-tracks".hashCode().toString()).delete()
        }
        // canonicalId, because that is what the memo is keyed on - see the note at the
        // coalescedTracks call in loadPlaylistTracksCached. A raw-id removal would silently miss for
        // any extension that canonicalizes (YouTube Music's "VL" prefix).
        inFlightTracks.remove(memoPrefix + canonicalId(id))
    }

    /**
     * Write a KNOWN-GOOD track list straight into both layers, bypassing any fetch.
     *
     * ⚠⚠ THE ONLY CALLER IS A SUCCESSFUL PLAYLIST SAVE, AND THAT IS THE POINT. The
     * editor already knows the exact new order (it maintains the list across the action loop and it
     * is what it sent to the extension), so after the server has accepted the edit there is no reason
     * to ask anyone what the playlist now looks like - least of all a read endpoint that may lag the
     * write it just acknowledged. Painting from this is both faster and more correct than a refetch.
     * ⚠️ BOTH LAYERS, OR IT DOES NOTHING. The durable entry is what the page paints from
     * on open ([getTracks] reads it age-agnostically); the memo is what [loadPlaylistTracksCached]
     * consults before fetching. Writing one and not the other just moves which layer serves the
     * stale copy.
     * ⚠️ SLIM ON DISK, FULL IN MEMORY, deliberately: the durable store has only ever
     * held [toTracksCacheSlim] forms (the 50 MB cap and the build-1008/1013 fat-Track OOMs are why),
     * while the memo feeds the live feed and playback and therefore needs the real tracks - which
     * includes the TRACK_TOKEN that the disk form deliberately drops.
     * ⚠️ RESPECTS carriesExpiry FOR THE DISK WRITE, same rule as every other writer
     * here: an item carrying its own expiry is never stored durably. The memo is still seeded, since
     * it is bounded by MEMORY_TTL_MS anyway.
     */
    fun writePlaylistTracksCache(app: App, playlist: Playlist, tracks: List<Track>) {
        runCatching {
            if (!playlist.carriesExpiry()) app.context.saveToCache(
                "${playlist.id}-tracks",
                CachedPlaylistTracks(
                    savedAtMs = System.currentTimeMillis(),
                    tracks = tracks.map { it.toTracksCacheSlim() },
                    // The trust window: for TRUST_WINDOW_MS a revalidation may not replace this.
                    writtenByUsAtMs = System.currentTimeMillis(),
                    schema = TRACKS_SCHEMA,
                ),
                DURABLE_PLAYLIST_FOLDER, durable = true
            )
            inFlightTracks[MEMO_PLAYLIST_PREFIX + canonicalId(playlist.id)] =
                System.currentTimeMillis() to CompletableDeferred(tracks)
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
    /**
     * ⚠⚠ THE ONE DEFINITION OF THE MEDIA-STATE CACHE KEY. It used to be
     * written out at both ends - the read in [getMedia] and the write in [loadMedia] - and a third
     * copy was about to appear in [setTrackLiked]. A key that is spelled in three places is a key
     * that can be spelled differently in one of them, and the failure would be silent: a write
     * nobody reads back.
     * ⚠️ extensionId IS THE ONE THE ITEM CARRIES, NOT A SUB-EXTENSION'S.
     * MediaItemUtils defines `MediaItem.extensionId` AS `state.extensionId`, and the playback read
     * resolves its Extension with getExtensionOrThrow(mediaItem.extensionId) - so the two sides are
     * literally the same string for a Unified/Combine item too (the container's id), and there is
     * no sub-extension variant to reconcile.
     */
    fun mediaStateId(extensionId: String, itemId: String) = "media-$extensionId-$itemId-state"

    suspend inline fun <reified T : EchoMediaItem> getMedia(
        app: App, extensionId: String, itemId: String,
    ) = runCatching {
        val fileCache = app.awaitFileCache()
        fileCache.getData<MediaState.Loaded<T>>(mediaStateId(extensionId, itemId)).getOrThrow()
    }

    /**
     * ⚠⚠ MAKES A LIKE SURVIVE THE NEXT REBUILD OF THE SAME TRACK. The player's
     * heart reads MediaItem.isLiked, i.e. the userRating on the timeline item's metadata, and
     * PlayerCallback.applyRating writes that - but the timeline item is REPLACED by
     * StreamableMediaSource with the one StreamableLoader built from a cached MediaState.Loaded
     * (MediaItemUtils.toMetaData derives userRating from its isLiked). So without this the next
     * separately-built MediaItem for a liked track - re-tapping it in a playlist, a second copy
     * further down the queue, a cold restore - came back with the PRE-LIKE heart, while the
     * overflow sheet beside it correctly offered "Unlike" from a fresh probe.
     * REPRODUCED ON DEVICE 2026-10-23 (Pixel 10, Deezer): liked from the player heart, re-tapped
     * the same track in the playlist, heart empty.
     *
     * ⚠️ READ-MODIFY-WRITE OF A STATE THAT IS ALREADY THERE, WHICH IS WHY IT
     * CANNOT POISON THE CACHE. It never calls loadItem and never invents an item, so the
     * wrong-item guard in [loadMedia] has nothing to guard here: the only thing written is the
     * state this just read back, under the key it was read from.
     * ⚠️ A MISS IS A NO-OP, DELIBERATELY. With no cached state there is nothing
     * stale to correct, and the next full load runs the isItemLiked probe for real - fabricating a
     * state here would also have to invent the other four capability fields.
     * ⚠️ MediaState.Loaded IS NOT A data class, so this spells out all nine
     * fields rather than using copy(). A field added there must be mirrored here, AND THE COMPILER
     * WILL NOT CATCH IT: these are named arguments, so a new field carrying a DEFAULT compiles
     * cleanly and is silently dropped from every state this rewrites. There is a matching note at
     * that constructor.
     * ⚠️ THE EXTENSION IS NOT CONSULTED. The caller has already told the
     * extension (LikeClient.likeItem succeeded); this only aligns our cache with it.
     */
    suspend fun setTrackLiked(
        app: App, extensionId: String, trackId: String, liked: Boolean,
    ) {
        val cached = getMedia<Track>(app, extensionId, trackId).getOrNull() ?: return
        if (cached.isLiked == liked) return
        runCatching {
            app.awaitFileCache().putData(
                mediaStateId(extensionId, trackId),
                MediaState.Loaded(
                    extensionId = cached.extensionId,
                    item = cached.item,
                    isFollowed = cached.isFollowed,
                    followers = cached.followers,
                    isSaved = cached.isSaved,
                    isLiked = liked,
                    isHidden = cached.isHidden,
                    showRadio = cached.showRadio,
                    showShare = cached.showShare,
                    // PRESERVED, NOT CLEARED. This learned ONE field from a successful like; the
                    // flag says some OTHER probe never answered, and that is still true. Clearing
                    // it here would stop the re-probe that would have filled the rest in.
                    probesDegraded = cached.probesDegraded,
                )
            )
        }
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

    /**
     * A capability probe's result, degraded to null ("unknown") on failure and reported once.
     *
     * ⚠⚠ null ARRIVES TWO WAYS AND THEY MEAN THE SAME THING TO EVERY
     * CONSUMER: the item did not declare the capability (the Result itself is null, from
     * [loadMedia]'s `else null`), or the probe failed. Both are "we do not know", which is what
     * the nullable field on MediaState.Loaded is for.
     * ⚠️ CancellationException IS RETHROWN, NEVER DEGRADED.
     * ExtensionUtils.get wraps the call in runCatching, so a cancelled probe arrives here as a
     * Result.failure rather than unwinding - and swallowing it would detach this coroutine from
     * its parent's cancellation. Same guard, same reason, as the one in
     * ExtensionUtils.getOrThrow(throwableFlow).
     * ⚠️ @PublishedApi internal BECAUSE [loadMedia] IS PUBLIC INLINE. Read the
     * stale-inline hazard note above that function before changing this signature: a caller that
     * is not recompiled in the same run keeps its inlined copy and links against whatever was
     * here before.
     */
    @PublishedApi
    internal fun <T> Result<T?>?.probeOrNull(
        extensionId: String, probe: String, failed: MutableList<String>
    ): T? {
        val result = this ?: return null
        return result.getOrElse { throwable ->
            if (throwable is CancellationException) throw throwable
            failed.add(probe)
            CapabilityProbeFailure.record(extensionId, probe, throwable)
            null
        }
    }

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
            // ⚠️ [CORRECTED 2026-10-23] "SKIPPED ENTIRELY ON THIS PATH" IS TRUE ONLY OF A
            // CACHE HIT. preferCache short-circuits only when getMedia RETURNS something; on a miss the
            // full load below runs with all five probes and writes the state, so a track's FIRST play
            // does probe isLiked and does populate the cache. That is what makes a stale like possible
            // at all - the first play stores isLiked, a later like updates the player and the extension
            // but not that entry - and why [setTrackLiked] exists.
            // ⚠⚠ A CACHED TRACK WITH NO SERVERS IS TREATED AS A MISS, BECAUSE SUCH A STATE WAS
            // WRITTEN AS A LEGITIMATE SUCCESS AND THEN SERVED FOREVER. An extension can return a track
            // with zero streamables WITHOUT THROWING - Spotify did exactly that while it was answering
            // 403s - and Combine passes that straight through (its loadTrack returns
            // `metaTrack.copy(streamables = allStreamables)`, and when the metadata extension is also the
            // preferred stream extension that list is metaTrack.streamables verbatim). So the write below
            // stored streamables=[] under media-echo_combine-<id>-state as a normal success, and the
            // no-TTL policy means it outlives the 403s, the Spotify fix, and the extension update.
            // ⚠️ WHAT IT LOOKED LIKE, MEASURED ON DEVICE 2026-10-10 (GladixPlayback capture): a 4 ms
            // "loadTrack result servers=[]" - too fast for any extension call, i.e. this line - then
            // selectServerIndex -1, then StreamableLoader.loadServer throwing TrackUnavailableException
            // ("No playable source for this track"), then StreamableMediaSource's null/empty-sources arm,
            // then a SILENT skip, because PlayerEventListener only emits once the breaker trips. The next
            // track in the same queue, on the same Combine -> Spotify route, got 2 servers and played -
            // which is what proves the route was healthy and only the remembered state was not.
            // ⚠⚠ THIS IS NOT THE TTL THE NOTE ABOVE FORBIDS, AND THE DIFFERENCE IS THE WHOLE POINT.
            // That note protects the OFFLINE restored queue, whose cached states carry REAL servers; this
            // test is `servers.isEmpty()`, so it fires only on states that are structurally incapable of
            // producing playback and never on the ones being protected. It asks "can this state possibly
            // play", never "how old is it" - no age check, no freshness, nothing to re-attempt for a state
            // that would have worked.
            // ⚠️ OFFLINE WITH A POISONED ENTRY IS UNCHANGED: loadItem fails fast (offline is a
            // DNS/connect failure, not a read timeout), and the getOrElse fallback below re-serves the same
            // empty state, ending in the same skip as before. That fallback is deliberately NOT given this
            // guard - doing so would surface the extension's error offline instead, which is a separate
            // decision about what an offline user sees.
            // ⚠️ IT SELF-HEALS AND NEEDS NO CACHE WIPE: the entry is bypassed on the next play and
            // then overwritten by the write below when the reload succeeds.
            // ⚠️ `as?` PLUS `== true` IS DELIBERATE: a non-Track state (album, artist, playlist) has no
            // servers to test, so the predicate must be FALSE for it and the cache still preferred. A
            // direct `!servers.isEmpty()` would make every non-Track cache read a miss.
            // ⚠⚠ A DEGRADED ENTRY IS ALSO A MISS, AND IT IS THE SAME KIND OF
            // TEST AS THE EMPTY-SERVERS ONE ABOVE - NOT THE TTL THE NOTE FORBIDS. It asks "did
            // this state ever find out?", which is a STRUCTURAL property written into the entry at
            // build time (MediaState.Loaded.probesDegraded), never "how old is it". A state whose
            // probes all answered is preferred forever, exactly as before.
            // ⚠️ WHY IT MATTERS ON THIS PATH SPECIFICALLY: a cache hit here
            // skips all five probes, so a single failed probe would otherwise be remembered as the
            // answer for the life of the entry - an unfilled heart on every later play of that
            // track, which is the stale-heart class [setTrackLiked] exists to prevent.
            // ⚠️ OFFLINE STILL PLAYS, AND THAT IS WHY THE ENTRY IS STILL
            // WRITTEN RATHER THAN SKIPPED. Bypassing the cache means loadItem runs and fails fast
            // offline (DNS/connect, not a read timeout), and the getOrElse fallback below re-serves
            // this very state - so the cost is one doomed attempt, not a track that cannot play.
            // NOT persisting a degraded load would have left nothing to fall back to at all.
            if (preferCache) getMedia<T>(app, extension.id, state.item.id).getOrNull()
                ?.takeUnless {
                    (it.item as? Track)?.servers?.isEmpty() == true || it.probesDegraded
                }
                ?.let { return@runCatching withCallerVerdict(state.item, it) }
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
                // ⚠⚠ A FAILED CAPABILITY PROBE IS "UNKNOWN", NOT A FAILED LOAD.
                // These five were `.getOrThrow()`, so ANY of them failing failed the whole
                // MediaState.Loaded - and on a cache miss that failure reached the caller. On the
                // playback path the caller is StreamableLoader.loadTrack, so a like/save lookup could
                // stop a track from playing: 1146 (ru, SM-G781B, fresh Obtainium install) died in
                // prepare because Deezer refused favorite_song.getList for an anonymous session, which
                // isItemSaved was calling per track.
                // ⚠⚠ THE TYPE ALREADY SAID THIS WAS ALLOWED. All five fields are
                // NULLABLE on MediaState.Loaded precisely because "we could not find out" is a real
                // answer, and every consumer already renders it (the heart falls back to unfilled, the
                // library row to not-saved). `.getOrThrow()` was converting a representable unknown
                // into a fatal.
                // ⚠️ THE FAILURE IS STILL REPORTED, JUST NOT TO THE USER: see
                // [probeOrNull]. A snackbar per track on a failing queue is noise, and throwableFlow
                // would deliver exactly that.
                // ⚠⚠ AND THE STATE REMEMBERS THAT IT IS INCOMPLETE, WHICH IS THE
                // OTHER HALF OF DEGRADING AT ALL. This state is written to the cache below like any
                // other, and on the playback path a cache hit SKIPS all five probes - so without the
                // marker one transient refusal would leave that track's heart unfilled permanently.
                // See MediaState.Loaded.probesDegraded and the preferCache arm above.
                // ⚠️ A PLAIN MutableList IS SAFE HERE: the five awaits are
                // evaluated in order, on this one coroutine, as the constructor's arguments - the
                // async blocks run concurrently but nothing adds to this list except these five
                // sequential calls.
                val failedProbes = mutableListOf<String>()
                val newState = MediaState.Loaded(
                    item = new,
                    extensionId = extension.id,
                    isSaved = isSaved.await()
                        .probeOrNull(extension.id, "isItemSaved", failedProbes),
                    isFollowed = isFollowed.await()
                        .probeOrNull(extension.id, "isFollowing", failedProbes),
                    followers = followers.await()
                        .probeOrNull(extension.id, "getFollowersCount", failedProbes),
                    isLiked = isLiked.await()
                        .probeOrNull(extension.id, "isItemLiked", failedProbes),
                    isHidden = isHidden.await()
                        .probeOrNull(extension.id, "isItemHidden", failedProbes),
                    showRadio = new.isRadioSupported && extension.isClient<RadioClient>(),
                    showShare = new.isShareable && extension.isClient<ShareClient>(),
                    probesDegraded = failedProbes.isNotEmpty(),
                )
                val fileCache = app.awaitFileCache()
                val id = mediaStateId(extension.id, newState.item.id)
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

    /**
     * ⚠⚠ THE Oct 25 RULE, APPLIED TO THIS STORE: A PERSISTED VERDICT MUST NEVER OUTLIVE A LIST RELOAD.
     * It was applied to the saved queue and History (HistoryEntity.resetPlayable) and missed here. An
     * entry's isPlayable is whatever its STUB carried when [loadMedia] wrote it -
     * DeezerTrackClient.loadTrack returns the stub or a copy of it - and a stub carries Playable.No
     * when it came from a marked row: the long-press sheet or track page (MediaViewModel) on a dimmed
     * row, or an Android Auto marked head (droppingUnplayable(keepFirst = true); the car shows no
     * dimming). A hit then served that No for ever, and StreamableLoader.loadServer throws on it before
     * any request: an instant silent skip of a row the list shows as playable.
     * ⚠⚠ A CALLER'S Yes IS ALSO "UNKNOWN", SO IT OVERRIDES ONLY A STORED No. resetPlayable writes Yes
     * (the type's default, read everywhere as "no opinion"), so History and restored-queue stubs say
     * Yes without knowing. A stored RegionLocked / Unreleased therefore survives a caller Yes; the
     * safety net in StreamableLoader.load re-resolves that case if it fails. Any non-Yes caller verdict
     * comes from a list and wins outright.
     * ⚠️ ON A HIT ONLY. On a miss the verdict comes from the extension's loadTrack just now, which is at
     * least as fresh as the caller's, and some extensions may report availability only there.
     * ⚠️ READ-TIME, so entries already on disk are covered without a migration; nothing is written back.
     * ⚠️ A THIRD HAND-REBUILD OF MediaState.Loaded (with setTrackLiked) - mirror new fields here too.
     * [2026-10-30] And a fourth: TrackInfoViewModel.withInfoDetails.
     */
    @PublishedApi
    internal fun <T : EchoMediaItem> withCallerVerdict(
        stub: T, hit: MediaState.Loaded<T>,
    ): MediaState.Loaded<T> {
        val stored = hit.item as? Track ?: return hit
        val caller = (stub as? Track)?.isPlayable ?: return hit
        val keepStored = caller == Track.Playable.Yes && stored.isPlayable !is Track.Playable.No
        if (keepStored || stored.isPlayable == caller) return hit
        @Suppress("UNCHECKED_CAST")
        return MediaState.Loaded(
            extensionId = hit.extensionId,
            item = stored.copy(isPlayable = caller) as T,
            isFollowed = hit.isFollowed,
            followers = hit.followers,
            isSaved = hit.isSaved,
            isLiked = hit.isLiked,
            isHidden = hit.isHidden,
            showRadio = hit.showRadio,
            showShare = hit.showShare,
            probesDegraded = hit.probesDegraded,
        )
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

    /**
     * ⚠⚠ THERE IS NO CACHE HERE, AND ITS ABSENCE IS LOAD-BEARING - DO NOT RE-ADD ONE.
     * Upstream cached every resolved [Streamable.Media] under "media-<extId>-<trackId>-<streamableId>"
     * and read it back as a failure fallback; that code was present at the fork point (f2108206) and
     * a401b5e1 (2026-05-08) deleted both halves.
     * ⚠️ NO REASON IS RECORDED ANYWHERE - not in the commit message, not in any code
     * comment. The substance of a401b5e1 is the AudioFocusListener rewrite plus
     * setAudioAttributes(audioAttributes, false); the cache deletion rode along. So treat the cost of
     * re-adding it as UNKNOWN, not zero.
     *
     * RECONSTRUCTION, AND IT IS INFERENCE FROM TODAY'S SOURCE RATHER THAN THE RECORDED REASON. Two
     * concrete hazards a cached Streamable.Media would carry:
     *   - [Streamable.Raw] declares its streamProvider @Transient, so a DESERIALIZED Raw media has a
     *     null provider and NPEs on RawDataSource.open's `streamProvider!!`. Per the note there, a
     *     non-IOException escaping open() becomes UnexpectedLoaderException - NON-RETRIABLE - and the
     *     whole track dies.
     *   - Streamable.Source.Http serializes its request, so a hit replays a possibly-expired signed
     *     URL into PlayerEventListener.onPlayerError's 401/403 re-resolve.
     *
     * ⚠⚠ AND preferCache'S WHOLE SOUNDNESS ARGUMENT DEPENDS ON THIS STAYING EMPTY.
     * [loadMedia] is allowed to serve an arbitrarily old MediaState.Loaded on the playback path
     * precisely because "a cached MediaState.Loaded carries NO STREAM URL" - streams are resolved
     * HERE, fresh, on every play. A cache at this function makes that sentence false and hands an
     * expired URL to a restored queue. If a stream-media cache is ever wanted again, the preferCache
     * note has to be re-argued FIRST, not amended afterwards.
     */
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
            val cached = readTracksEntry(app, itemId, durableFolder)
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

    /**
     * Emits a playlist id when a background revalidation found a DIFFERENT track list and wrote it.
     *
     * ⚠⚠ ONLY ON AN ACTUAL DIFFERENCE, WHICH IS WHAT KEEPS SCROLL POSITION. The page
     * re-reads the store on each emission, so emitting for an unchanged playlist would rebuild the
     * feed - and blank it for a frame - on every open. The id-sequence comparison in
     * [loadPlaylistTracksCached] is therefore load-bearing, not an optimisation.
     * Replay 0 and a small buffer: a page that is not open has nothing to update, and a missed
     * emission costs one stale paint that the next open corrects.
     */
    private val tracksRevalidated = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val tracksRevalidatedFlow = tracksRevalidated.asSharedFlow()


    /**
     * [fetched] is false when the list came from the 5-minute memo rather than from a real request.
     *
     * ⚠⚠ IT GATES THE DURABLE WRITE, AND THAT IS WHY IT EXISTS. Writing a memo hit to
     * disk re-stamps savedAtMs, which RENEWS the 24h lease on data we did not just verify - so a
     * bust followed by a load inside the memo window used to cement the stale list for another day
     * instead of clearing it. Only a real fetch may (re)start the lease.
     */
    private class CoalescedTracks(val tracks: List<Track>, val fetched: Boolean)

    private suspend fun coalescedTracks(
        app: App, key: String, block: suspend () -> List<Track>,
    ): CoalescedTracks {
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
        return CoalescedTracks(entry.second.await(), created != null)
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
        val cached = if (!durable) null
        else readTracksEntry(app, item.id, DURABLE_PLAYLIST_FOLDER)
        // ⚠⚠ PAINT FROM THE STORE AT ANY AGE, THEN REVALIDATE IN THE BACKGROUND.
        // This replaced the 24h short-circuit on 2026-10-15. The short-circuit meant no fetch at
        // all for a day, so a playlist edited elsewhere - or edited HERE through a path that did not
        // bust the entry - stayed wrong for up to 24h, which is what a reporter saw after a reorder.
        // The cost of the replacement is bounded by the throttle in [revalidatePlaylistTracks]: at
        // most one playlist.getSongs per playlist per MEMORY_TTL_MS, and none at all on a library
        // scroll. ALBUMS KEEP THE 24h RULE - see the note at TRACKS_TTL_MS.
        // ⚠️ THE PAINT IS STILL INSTANT AND STILL OFFLINE-SAFE. The cached list is
        // returned synchronously here; the revalidation is a detached job on app.scope whose failure
        // is swallowed, so "offline = paint and skip" needs no code of its own.
        // ⚠️ A CHANGE MARKER WOULD MAKE THIS CHEAPER AND IS DELIBERATELY NOT BUILT
        // YET. Deezer has no snapshot id - verified 2026-10-15 against
        // music-assistant/deezer-python-gql's schema.graphql, where type Playlist carries
        // creationDate and lastModificationDate and the only checksum in the whole schema is
        // TrackMediaChecksums (per track encoding). The gateway equivalent is DATE_MOD, which
        // deezer.pagePlaylist already returns on every open, so comparing it against a stored copy
        // could skip the getSongs entirely. TWO THINGS BLOCK IT, both unverified rather than hard:
        //   1. whether playlist.updateOrder bumps DATE_MOD AT ALL is not established. No
        //      open-source client documents which mutations touch it, and a reorder is precisely
        //      the case that matters. One measurement settles it: reorder, then diff the raw
        //      DATE_MOD from pagePlaylist before and after.
        //   2. the value is not reachable from here today. DeezerParser.toDate parses
        //      "yyyy-MM-dd" or "yyyy-MM-dd HH:mm:ss" and DISCARDS the time, so
        //      Playlist.creationDate is day-granularity and cannot separate two edits on one day.
        //      A usable marker needs the raw string in Playlist.extras - an extension change.
        if (cached != null) {
            revalidatePlaylistTracks(app, extension, item, cached)
            return cached.tracks.toFeed()
        }

        // canonicalId for the MEMORY key, raw item.id for the DURABLE key, and the difference is
        // load-bearing. Some extensions return a canonicalized id from loadItem - YouTube Music strips a
        // "VL" playlist prefix - so the same playlist reaches us as "VLPLaJ..." from a feed row and
        // "PLaJ..." after loading. The durable entry is only ever written from a real open, which always
        // carries the loaded id, so it is self-consistent on the raw value. The memory entry is the one a
        // prefetch warm (unloaded id) and a user tap (loaded id) must BOTH hit, so it has to normalize or
        // every warm would silently miss and the feature would do nothing at all.
        val result = coalescedTracks(app, MEMO_PLAYLIST_PREFIX + canonicalId(item.id)) {
            val feed = extension.getAs<PlaylistClient, Feed<Track>> { loadTracks(item) }.getOrThrow()
            feed.loadAll()
        }
        // result.fetched: only a REAL request may write the store or renew the 24h lease. See the
        // note on CoalescedTracks - a memo hit written here is what turned a bust into a renewal.
        if (durable && result.fetched) app.context.saveToCache(
            "${item.id}-tracks",
            CachedPlaylistTracks(
                savedAtMs = System.currentTimeMillis(),
                tracks = result.tracks.map { it.toTracksCacheSlim() },
                schema = TRACKS_SCHEMA,
            ),
            DURABLE_PLAYLIST_FOLDER, durable = true
        )
        return result.tracks.toFeed()
    }

    /**
     * Background re-read of a playlist's tracks, launched from an OPEN of its page and nowhere else.
     *
     * ⚠⚠ NEVER FROM [warmPlaylistTracks], AND THAT IS STRUCTURAL RATHER THAN A FLAG.
     * The Aug 30 library-scroll warm calls [coalescedTracks] directly and never reaches
     * [loadPlaylistTracksCached], so scrolling past fifty playlists cannot fire fifty getSongs
     * calls. Do not "tidy" the warm by routing it through the cached loader - that one change
     * would turn a scroll into a request storm.
     * ⚠️ THROTTLED BY [coalescedTracks] ITSELF, WHICH IS WHY THERE IS NO SEPARATE
     * GUARD HERE. A live memo entry (fetched, or in flight, within MEMORY_TTL_MS) is JOINED rather
     * than replaced, so this cannot issue a second request for the same playlist inside that
     * window - the worst case stays one getSongs per playlist per 5 minutes however often the page
     * is opened, which is the whole point of the throttle.
     * ⚠️ JOINING RATHER THAN SKIPPING IS DELIBERATE, and it closes a case an early
     * return left open: the Aug 30 library-scroll warm fills the memo WITHOUT writing the durable
     * store, so a playlist whose stored entry was older than the warm would have painted the older
     * list while a fresher one sat unused in the memo until it aged out. Joining compares that
     * fresher list and swaps it in, at no extra request.
     * ⚠️ THE TRUST WINDOW WINS OVER A FETCH. Inside TRUST_WINDOW_MS of our own save
     * the list we wrote is authoritative and a fetched one is discarded - see
     * [CachedPlaylistTracks]. The fetch is still allowed to happen (it warms the memo and costs
     * nothing extra); only the replacement is suppressed.
     * ⚠️ SWAPS IN ONLY ON AN ID-SEQUENCE DIFFERENCE, which is deliberate twice over:
     * it catches a reorder (same set, different order), and it ignores the field-level churn a
     * re-resolve produces (covers, fallbacks, availability). The second half is what keeps this
     * cheap: ANY emission reaching FeedData makes it null both of its states and re-pull (see its
     * init, which collects extraLoadFlow), so an emission for an unchanged list is a visible rebuild
     * of the feed on every open. The filter belongs here, where the comparison is possible.
     */
    private fun revalidatePlaylistTracks(
        app: App, extension: Extension<*>, item: Playlist, cached: CachedPlaylistTracks,
    ) {
        val key = MEMO_PLAYLIST_PREFIX + canonicalId(item.id)
        app.scope.launch {
            runCatching {
                val result = coalescedTracks(app, key) {
                    extension.getAs<PlaylistClient, Feed<Track>> { loadTracks(item) }
                        .getOrThrow().loadAll()
                }
                // ⚠⚠ THIS PATH WRITES WHETHER OR NOT IT FETCHED, UNLIKE THE ONE IN
                // [loadPlaylistTracksCached], AND THE ASYMMETRY IS INTENDED. There, a write from a
                // memo hit would renew a 24h READ lease on unverified data - the defect that turned
                // a bust into a renewal. Here there is no read lease left to renew (playlists no
                // longer short-circuit on age), and a joined memo list is by construction at least as
                // fresh as the stored one, so propagating it to disk is the correct outcome. The only
                // thing savedAtMs still gates for a playlist is [warmPlaylistTracks]' skip, where
                // suppressing a warm after a real list arrived is also correct.
                val fresh = result.tracks
                // An empty result is not evidence of an empty playlist. DeezerPlaylistClient already
                // refuses to degrade a gateway error into an empty list, but a third-party extension
                // may not - so an empty fetch never replaces a non-empty cached list.
                if (fresh.isEmpty() && cached.tracks.isNotEmpty()) return@runCatching
                if (fresh.map { it.id } == cached.tracks.map { it.id }) return@runCatching
                val written = cached.writtenByUsAtMs
                if (written != null && System.currentTimeMillis() - written < TRUST_WINDOW_MS)
                    return@runCatching
                app.context.saveToCache(
                    "${item.id}-tracks",
                    CachedPlaylistTracks(
                        savedAtMs = System.currentTimeMillis(),
                        tracks = fresh.map { it.toTracksCacheSlim() },
                        schema = TRACKS_SCHEMA,
                    ),
                    DURABLE_PLAYLIST_FOLDER, durable = true
                )
                tracksRevalidated.emit(item.id)
            }
        }
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
        val cached = readTracksEntry(app, item.id, DURABLE_PLAYLIST_FOLDER)
        if (cached != null && System.currentTimeMillis() - cached.savedAtMs < TRACKS_TTL_MS) return
        runCatching {
            coalescedTracks(app, MEMO_PLAYLIST_PREFIX + canonicalId(item.id)) {
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
    //   [AMENDED 2026-10-15] this line used to read "toAlbumSlim, not toPlaylistSlim - different
    //     context extra". Both are gone: the two slim forms differed only in which context key their
    //     extras allowlist kept, and that allowlist is what made every Unified track unplayable. One
    //     toTracksCacheSlim now serves both, so there is no album/playlist distinction left here.
    //   a separate durable folder - album and playlist ids share a string namespace (see the constants).
    private suspend fun loadAlbumTracksCached(
        app: App, extension: Extension<*>, item: Album,
    ): Feed<Track>? {
        // Same eligibility rule as the playlist path — see carriesExpiry. No album ships an expiry today;
        // it is here so the rule is genuinely general and does not have to be rediscovered when one does.
        val durable = !item.carriesExpiry()
        val cached = if (!durable) null
        else readTracksEntry(app, item.id, DURABLE_ALBUM_FOLDER)
        if (cached != null && System.currentTimeMillis() - cached.savedAtMs < TRACKS_TTL_MS)
            return cached.tracks.toFeed()

        // "album:" prefix for the same reason the folders are separate: coalescedTracks is ONE map, and an
        // album and a playlist sharing an id string would otherwise share an in-flight entry and serve each
        // other's tracks. canonicalId for the same reason as the playlist path.
        val result = coalescedTracks(app, MEMO_ALBUM_PREFIX + canonicalId(item.id)) {
            val feed = extension.getAs<AlbumClient, Feed<Track>?> { loadTracks(item) }.getOrThrow()
                ?: return@coalescedTracks emptyList()
            feed.loadAll()
        }
        if (result.tracks.isEmpty()) return null
        // Same rule as the playlist path above: a memo hit never renews the lease.
        if (durable && result.fetched) app.context.saveToCache(
            "${item.id}-tracks",
            CachedPlaylistTracks(
                savedAtMs = System.currentTimeMillis(),
                tracks = result.tracks.map { it.toTracksCacheSlim() },
                schema = TRACKS_SCHEMA,
            ),
            DURABLE_ALBUM_FOLDER, durable = true
        )
        return result.tracks.toFeed()
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

    /**
     * ⚠⚠ THE FEED CACHE MUST NOT PERSIST A STREAM TOKEN, AND THAT IS THE
     * WHOLE OF A BUG THAT LOOKED LIKE FOUR. TRACK_TOKEN is perishable; this cache is on disk and
     * served first on every open of Home, Library, Search and any expanded feed (HomeFragment,
     * LibraryFragment, SearchFragment, FeedFragment all call [getFeedShelf]). A track queued from a
     * cached feed therefore arrived with an HOURS-OLD token, and nothing pre-empts that:
     * DeezerTrackClient.loadTrack re-fetches only on an EMPTY token, so a stale one is
     * indistinguishable from a good one before the request (the note at createStreamableForQuality).
     * The track then had to FAIL first and be rescued by the ladder, which is where it went wrong.
     * ⚠️ THE TRACKS CACHE ALREADY DOES THIS - see [toTracksCacheSlim],
     * `toSlim().copy(extras = extras - "TRACK_TOKEN")`. That is why album and playlist pages never
     * showed this: their cached tracks have no token at all, so the empty-token self-heal runs and
     * they always play on a fresh one. This makes the feed cache behave the same way.
     * ⚠⚠ TOKEN ONLY, NEVER toSlim(). toSlim also drops streamables,
     * description and - critically - FALLBACK_ID, which a grafted favourite needs to play AT ALL
     * (DeezerLibraryClient.graftFavTrack). Removing one key is the fix; slimming here would trade
     * this bug for the Baby Beluga one.
     * ⚠️ STRIPPED ON READ, NOT ON WRITE, so caches already on disk are
     * covered without a migration. Costs one extra gateway fetch per track at play time, which is
     * the price a restored queue already pays by design.
     * ⚠⚠ [CORRECTED 2026-10-27] TWO CLAIMS ABOVE WERE WRONG, AND A THIRD IS UNCONFIRMED.
     *   "THIS MAKES THE FEED CACHE BEHAVE THE SAME WAY" - it covered the PAGE items only. The feed
     *   cache also stores each tab's Feed.Buttons (savingFeed's `Pair(buttons, bg)`), and
     *   Buttons.customTrackList is the DECLARED LIST that a Library -> Tracks tap actually queues
     *   (FeedClickListener's declaredTracks branch) - not the row. That list kept its token (READ
     *   from source). Buttons are now stripped too, in getFeedShelf's `buttons` argument to [getFeed].
     *   "ONE EXTRA GATEWAY FETCH PER TRACK AT PLAY TIME" - only on a media-state MISS. A hit
     *   (loadMedia's preferCache arm) never reaches loadTrack, so the token baked into that entry's
     *   streamables is used however old it is (READ).
     *   "A STALE TOKEN IS THE CAUSE" - the opening of this note, and of the 1151 work - IS UNCONFIRMED.
     *   Measured on 1151 2026-10-27: Maria and Baby Beluga skipped from Library -> Tracks across
     *   force-stops and played for good once the long-press sheet rewrote their media-state entries.
     *   That pins the fault to the saved entry, not to which field in it. And on Deezer a 320 rung
     *   sends NO token (DeezerMedia.getMediaUrl posts the track id alone to the relay); only the 128
     *   and MP3_MISC rungs send it, so a 320 user meets a stale token only after a step-down or on an
     *   MP3_MISC-only entry. A stored Playable verdict is an equal candidate. The temporary
     *   GladixPlayback lines (removed after the 1153 capture) settle which; until
     *   they do, do not cite this block as proof that the token was the cause.
     *   [UPDATED 2026-10-27, 1152 capture] THEY DID, FOR FALLBACK-GRAFTED SONGS: a hit's stale token
     *   at the 128 rung got an unrecognised answer and the ladder skipped without substituting; the
     *   safety net's fresh load substituted. The fix is extension-side - DeezerTrackClient checks a
     *   token's age where it is sent (createStreamableForQuality) - so this strip stays, and the hit
     *   still serves the entry's token. The answer body is unobserved, and Maria was not in that capture.
     */
    private fun Track.withoutStreamToken(): Track =
        if (extras["TRACK_TOKEN"].isNullOrEmpty()) this
        else copy(extras = extras - "TRACK_TOKEN")

    private fun EchoMediaItem.itemWithoutStreamToken(): EchoMediaItem =
        (this as? Track)?.withoutStreamToken() ?: this

    suspend fun getFeedShelf(
        app: App, extensionId: String, feedId: String,
    ): Result<Feed<Shelf>> = runCatching {
        getFeed<Shelf>(
            app, extensionId, feedId,
            buttons = { b ->
                b?.customTrackList?.let { list -> b.copy(customTrackList = list.map { it.withoutStreamToken() }) } ?: b
            }
        ) { shelf ->
            when (shelf) {
                is Shelf.Item -> shelf.copy(media = shelf.media.itemWithoutStreamToken())
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
                    list = shelf.list.map { it.itemWithoutStreamToken() },
                    more = getFeedShelf(app, extensionId, shelf.id).getOrNull()
                )

                is Shelf.Lists.Tracks -> shelf.copy(
                    list = shelf.list.map { it.withoutStreamToken() },
                    more = getFeedShelf(app, extensionId, shelf.id).getOrNull()
                )
            }
        }
    }


    // FEED STUFF

    // `buttons` is applied to the stored Feed.Buttons on READ, alongside `transform` for page items -
    // see the [CORRECTED 2026-10-27] note at withoutStreamToken for why page items alone were not enough.
    suspend inline fun <reified T : Any> getFeed(
        app: App, extensionId: String, feedId: String,
        crossinline buttons: (Feed.Buttons?) -> Feed.Buttons? = { it },
        crossinline transform: suspend (T) -> T,
    ): Feed<T> {
        val fileCache = app.awaitFileCache()
        val tabId = "feed-$extensionId-$feedId"
        val tabs = fileCache.getData<List<Tab>>(tabId).getOrThrow()
        return Feed(tabs) { tab ->
            val id = "$tabId-${tab?.id}"
            val (storedButtons, bg) = fileCache.getData<Pair<Feed.Buttons?, ImageHolder?>>(id)
                .getOrThrow()
            PagedData.Continuous { token ->
                val id = "$id-$token"
                val page = fileCache.getData<Page<T>>(id).getOrThrow()
                page.copy(page.data.map { transform(it) })
            }.toFeedData(buttons(storedButtons), bg)
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