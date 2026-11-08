package dev.brahmkshatriya.echo.extension.clients

import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeedData
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Tab
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.extension.DeezerApi
import dev.brahmkshatriya.echo.extension.objOrNull
import dev.brahmkshatriya.echo.extension.arrOrNull
import dev.brahmkshatriya.echo.extension.DeezerExtension
import dev.brahmkshatriya.echo.extension.DeezerParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

class DeezerLibraryClient(
    private val deezerExtension: DeezerExtension,
    private val api: DeezerApi,
    private val parser: DeezerParser,
    private val cpuDispatcher: CoroutineDispatcher = Dispatchers.Default
) {

    // [2026-11-07] All and Tracks both show the liked list, so both name the Tracks tab for the app's marks pass
    // (MARKS_TAB_EXTRA, see likedMarksPass).
    private val marksTab = mapOf(DeezerPlaylistClient.MARKS_TAB_EXTRA to TabId.TRACKS.id)
    private val tabs: List<Tab> = listOf(
        Tab(TabId.ALL.id, "All", extras = marksTab),
        Tab(TabId.PLAYLISTS.id, "Playlists"),
        Tab(TabId.ALBUMS.id, "Albums"),
        Tab(TabId.TRACKS.id, "Tracks", extras = marksTab),
        Tab(TabId.ARTISTS.id, "Artists"),
        // Restored 2026-11-02; removed by a401b5e1 (2026-05-08) when the tab came back empty. Its row on All and the
        // Podcasts folder in Android Auto's Library come back with it (loadAll iterates every config).
        Tab(TabId.SHOWS.id, "Podcasts"),
    )

    private data class TabConfig(
        val id: TabId,
        val title: String,
        val request: suspend DeezerApi.() -> JsonObject,
        val extractor: (JsonObject) -> JsonArray?
    )

    private enum class TabId(val id: String) {
        ALL("all"),
        PLAYLISTS("playlists"),
        ALBUMS("albums"),
        TRACKS("tracks"),
        ARTISTS("artists"),
        SHOWS("shows")
    }

    private val configs: Map<String, TabConfig> = listOf(
        TabConfig(TabId.PLAYLISTS, "Playlists", { getPlaylists() }) { it.tabDataArray("playlists") },
        TabConfig(TabId.ALBUMS, "Albums", { getAlbums() }) { it.tabDataArray("albums") },
        TabConfig(TabId.TRACKS, "Tracks", { getTracks() }) { it.resultsDataArray() },
        TabConfig(TabId.ARTISTS, "Artists", { getArtists() }) { it.tabDataArray("artists") },
        TabConfig(TabId.SHOWS, "Podcasts", { getShows() }) { it.tabDataArray("shows") },
    ).associateBy { it.id.id }


    /**
     * ⚠⚠ customTrackList IS THE FAVORITES LIST ITSELF, AND IT IS WHAT MAKES A
     * TAP QUEUE THE WHOLE COLLECTION. GitHub issue #4: tapping a liked track started that
     * track's radio instead of playing the liked list, because the app's queue-vs-radio test is
     * isOrderedCollection(context) and a library feed has no context item - Deezer's favorites
     * are a gateway method (favorite_song.getList, tab="loved"), not a playlist we can hand over.
     * Declaring the tracks here lets FeedClickListener.onTracksClicked recognise a tapped track
     * as part of a collection the EXTENSION says is one, with no tab-id string in app code.
     *
     * ⚠⚠ BOTH TABS, AND THE All TAB GETS IT WITHOUT THE BUTTONS. A liked track
     * is the same track from the same list whether it is tapped on the Tracks tab or in the All
     * tab's Tracks row, so both must queue it the same way. showPlayAndShuffle stays FALSE on
     * All - ButtonsAdapter gates the two buttons on that flag alone (binding.playButton.isVisible
     * = buttons.showPlayAndShuffle), so the list travels without adding a Play/Shuffle pair to a
     * tab that also holds playlists, albums and artists.
     * ⚠️ THE OTHER TABS PASS null AND ARE UNCHANGED: Playlists, Albums and
     * Artists hold no tracks, so there is nothing to declare and nothing in them can match.
     * ⚠️ NO SECOND REQUEST. Both loaders already materialise the grafted
     * favorites to build their shelves; this returns the same list rather than fetching it
     * again, which at nb=10000 would be the expensive mistake.
     */
    suspend fun loadLibraryFeed(): Feed<Shelf> {
        deezerExtension.handleArlExpiration()
        return Feed(tabs) { tab ->
            val id = tab?.id
            val (data, liked) = when {
                tab?.extras?.get(DeezerPlaylistClient.AVAILABILITY_AWAIT_EXTRA) == "1" ->
                    likedMarksPass(tab?.extras?.get(DeezerPlaylistClient.AVAILABILITY_RECHECK_EXTRA) == "1")
                        .let { marked -> marked.map { it.toShelf() } to marked }
                id == TabId.ALL.id -> loadAll()
                else -> loadSingle(id)
            }
            val buttons = Feed.Buttons(
                showPlayAndShuffle = id == TabId.TRACKS.id,
                customTrackList = liked,
            )
            data.toFeedData(buttons)
        }
    }

    /**
     * ⚠⚠ THE LIKED LIST'S MARKS PASS (2026-11-07), the playlist page's (DeezerPlaylistClient.loadTracks with
     * AVAILABILITY_AWAIT_EXTRA) for the library feed. The app's FeedData.runMarksPass asks the Tracks tab again with that
     * extra after a load and on every visit; this waits for the running check, asks once more for the ids still due
     * (markUnplayable) and returns the same rows marked - no second getTracks. The shape is the Tracks tab's own, so the
     * app's feed cache, which saves this answer under that tab, holds marked rows. [recheck] (a pull-to-refresh) marks
     * every row due first, so all of them are asked again whatever their age.
     * With no rows kept (no liked load yet in this process) it throws rather than answer empty: an empty answer would be
     * saved as the tab's rows. The app swallows it; the next load keeps rows.
     * Why it exists: on 1172/1173 dead liked songs showed as playable across visits and pulls, because verdicts were applied
     * only at load time and the screen never updated after the check, while the Favorite tracks tile (the playlist path)
     * greyed them.
     */
    private suspend fun likedMarksPass(recheck: Boolean): List<Track> {
        val rows = keptLiked ?: throw IllegalStateException("No liked rows to mark yet")
        if (recheck) api.markForRecheck(rows)
        deezerExtension.awaitAvailability(LIKED_AVAILABILITY_KEY)
        return api.markUnplayable(rows)
    }

    // The grafted liked rows of the last load (either tab), for likedMarksPass. Rows only; verdicts are applied on read.
    @Volatile
    private var keptLiked: List<Track>? = null

    /** Shelves for the All tab, plus the grafted favorites so [loadLibraryFeed] can declare them. */
    private suspend fun loadAll(): Pair<List<Shelf>, List<Track>?> = supervisorScope {
        deezerExtension.handleArlExpiration()
        // ⚠️ ONE FAILING TAB NO LONGER FAILS ALL (2026-11-02). awaitAll rethrows the first failure, so a refused
        // Podcasts request (restored today) or any other tab's error emptied the whole All page. Each tab's work is
        // now caught and that row is left out; the others load. Only when EVERY tab failed is the first failure
        // rethrown - a sign-in or network failure hits all of them, and must still show as the page's error, not as
        // an empty All. Cancellation is never caught. The tab pages themselves (loadSingle) are unchanged.
        // Android Auto's Library uses this same feed, so its All folder behaves the same way.
        val failures = mutableListOf<Throwable>()
        val results = configs.values.map { cfg ->
            async(cpuDispatcher) {
                try {
                    allRow(cfg)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    synchronized(failures) { failures.add(e) }
                    null to null
                }
            }
        }.awaitAll()
        if (failures.size == configs.size) throw failures.first()
        results.mapNotNull { it.first } to results.firstNotNullOfOrNull { it.second }
    }

    // One tab's row on All, and the liked list when it is the Tracks tab.
    private suspend fun allRow(cfg: TabConfig): Pair<Shelf?, List<Track>?> {
        val json = cfg.request(api)
        // Before the null return below, so the tile shows for an account with no playlists.
        if (cfg.id == TabId.PLAYLISTS) {
            val list = withFavoriteTracks(playlistItems(cfg.extractor(json)))
            return list.takeIf { it.isNotEmpty() }
                ?.let { Shelf.Lists.Items(id = cfg.title, title = cfg.title, list = it) } to null
        }
        val items = cfg.extractor(json) ?: return null to null
        if (cfg.id == TabId.TRACKS) {
            // One batched availability pass over the same rows - see DeezerApi.markUnplayable.
            // Both tab paths get it because the same list is declared to the app from both; see
            // the customTrackList note at loadLibraryFeed.
            // [CORRECTED 2026-11-02] No longer waited for: the known verdicts are applied now and the rest are
            // checked in the background (DeezerExtension.checkAvailabilityLater), so they mark the next load -
            // Library and the Android Auto declared list, which is rewritten on every browse.
            // ⚠️ NEXT LOAD BY DESIGN, NOT THE SAME VISIT (decided 2026-11-02). Same-visit dimming, as playlist pages
            // get, needs a channel the library feed lacks (loadLibraryFeed takes no item to carry the await flag, the
            // All tab's carousel would need marks too): ~100 lines across ~7 files, judged not worth it. Do not move
            // this back inline either: that blocked Library behind 100-id pipe requests, one after another, on large
            // libraries. A dead liked song played before it is dimmed still skips instantly once its verdict is known
            // (DeezerTrackClient.loadTrack's isKnownDead check). Library pull-to-refresh does not clear these
            // verdicts (no flag reaches here); they expire after DeezerApi's AVAIL_TTL_MS.
            // [CORRECTED 2026-11-07] "Mark the next load" held only when the background check succeeded. The liked path had no
            // second ask (a playlist's marks pass re-asks with markUnplayable), so a failed check left liked songs unmarked from 1166
            // (c0a8b5a1, shipped with e6592bf0) until now: device, 1171, the dead Elvis songs greyed in the Favorite tracks tile and
            // never on this tab until the tile had filled the shared verdicts. secondAsk is that re-ask, still off the load.
            // [CORRECTED 2026-11-07] The Nov 2 "next load by design" decision is reversed. On 1172/1173 dead liked songs showed as
            // playable across visits and pulls: a bottom-nav return does not reload, a pull shows the saved rows first and a
            // second pull cancels the first, and the 30-minute verdicts expired between loads - so the screen never updated
            // after the check, while the Favorite tracks tile, on the playlist path, marked in place. Now the same visit is
            // marked: the app asks again with the await flag after the load and on every visit (likedMarksPass, the channel
            // is MARKS_TAB_EXTRA on the tab), and applies the marks in place on Tracks and in All's carousel. Verdicts are
            // kept on disk and re-checked after 24 h (DeezerApi.dueIds), and a Library pull now re-checks every row
            // (likedMarksPass's recheck). Still never waited for here.
            val grafted = items.mapNotNull { el -> (el as? JsonObject)?.let { graftFavTrack(it) } }
                .also { keptLiked = it }
                .also { deezerExtension.checkAvailabilityLater(LIKED_AVAILABILITY_KEY, it, secondAsk = true) }
                .let(api::markKnown)
            // The artist page's liked index, refreshed from this list at no cost.
            deezerExtension.refreshLiked(grafted)
            val shelf = grafted.takeIf { it.isNotEmpty() }
                ?.let { Shelf.Lists.Items(id = cfg.title, title = cfg.title, list = it) }
            return shelf to grafted
        } else return parser.run { items.toShelfItemsList(cfg.title) } to null
    }

    /** As [loadAll], for one tab: shelves plus the favorites when this IS the Tracks tab. */
    private suspend fun loadSingle(id: String?): Pair<List<Shelf>, List<Track>?> {
        val cfg = configs[id] ?: return emptyList<Shelf>() to null
        deezerExtension.handleArlExpiration()
        val json = cfg.request(api)
        // Before the empty return below, so the tile shows for an account with no playlists.
        if (id == TabId.PLAYLISTS.id)
            return withFavoriteTracks(playlistItems(cfg.extractor(json))).map { it.toShelf() } to null
        val arr = cfg.extractor(json) ?: return emptyList<Shelf>() to null
        return if (id == TabId.TRACKS.id) {
            // ONE graft pass, used for the shelves and declared to the app - see loadLibraryFeed,
            // plus one batched availability pass - see DeezerApi.markUnplayable.
            // [CORRECTED 2026-11-02] Background check, as in allRow above; marks on the next load by design (see there).
            // [CORRECTED 2026-11-07] With a second ask; see allRow.
            // [CORRECTED 2026-11-07] Not "on the next load by design" any more: marked on the same visit; see allRow.
            val liked = arr.mapNotNull { el -> (el as? JsonObject)?.let { graftFavTrack(it) } }
                .also { keptLiked = it }
                .also { deezerExtension.checkAvailabilityLater(LIKED_AVAILABILITY_KEY, it, secondAsk = true) }
                .let(api::markKnown)
            // The artist page's liked index, refreshed from this list at no cost.
            deezerExtension.refreshLiked(liked)
            liked.map { it.toShelf() } to liked
        } else parser.run {
            arr.mapNotNull { it.jsonObject.toEchoMediaItem()?.toShelf() }
        } to null
    }

    // Same parse as the other non-Tracks tabs (toEchoMediaItem per object), lenient on a null array.
    private fun playlistItems(arr: JsonArray?): List<EchoMediaItem> = parser.run {
        arr.orEmpty().filterIsInstance<JsonObject>().mapNotNull { it.toEchoMediaItem() }
    }

    /**
     * ⚠️ THE "FAVORITE TRACKS" TILE (2026-10-30): the account's Loved Tracks playlist, FIRST in the
     * Playlists tab and in the All tab's Playlists row, like Deezer's own app. pageProfile's playlists tab
     * does not return it (user-confirmed: no such tile before this).
     * The id comes from DeezerExtension.lovedTracksIdOrNull (deezer.getUserData's USER.LOVEDTRACKS_ID,
     * once per session, best-effort, never for a guest) - no request per Library load after the first.
     * Coverless and count-less by design: opening it loads the real playlist (pagePlaylist + getSongs,
     * with the FALLBACK graft and markUnplayable) through DeezerPlaylistClient like any playlist, which
     * also forces isEditable = false for this id. Skipped if pageProfile ever returns the id itself.
     * Credit: idea from Evoe (github.com/Doggo785/evoe) eb765a67 / 4323cdaf / f1bb17f3; Evoe resolves the
     * id from Home "Recently played", which getUserData makes unnecessary.
     */
    private suspend fun withFavoriteTracks(items: List<EchoMediaItem>): List<EchoMediaItem> {
        // On 1173 (2026-11-07) the tile shows in Android Auto under both Library -> Playlists and the Playlists shortcut.
        val id = deezerExtension.lovedTracksIdOrNull() ?: return items
        if (items.any { it is Playlist && it.id == id }) return items
        // isSaveable = false as in loadPlaylist, so the long-press sheet's pre-load placeholder offers no Save.
        return listOf(Playlist(id = id, title = FAVORITE_TRACKS, isEditable = false, isSaveable = false)) + items
    }

    private companion object {
        // US English, matching Deezer's US app.
        const val FAVORITE_TRACKS = "Favorite tracks"
        // One background check for the liked list, whichever tab or Android Auto asked first.
        const val LIKED_AVAILABILITY_KEY = "liked"
    }

    // Lenient at every hop (DeezerJson): an empty library tab is a genuine state, and every
    // caller of these already treats null as "nothing to show".
    private fun JsonObject.results(): JsonObject? = this["results"].objOrNull()
    private fun JsonObject.resultsDataArray(): JsonArray? =
        results()?.get("data")?.arrOrNull()
    private fun JsonObject.tabDataArray(tabId: String): JsonArray? =
        results()?.get("TAB")?.objOrNull()?.get(tabId)?.objOrNull()
            ?.get("data")?.arrOrNull()

    // FALLBACK-graft for favorites/liked TRACKS — identical to DeezerPlaylistClient.loadTracks:
    // favorite_song.getList pre-substitutes an unavailable original with a playable-but-dead/mis-attributed
    // track at TOP-LEVEL and moves the CORRECT catalog data into a full FALLBACK object. When a FALLBACK
    // exists, take DISPLAY fields (artists/album/cover/background) from it while keeping the top-level SNG_ID
    // as the id for STREAMING. No FALLBACK → top-level as-is. (Confirmed on-device: karaoke → correct artist;
    // P.J. Proby → live/openable album; fb=no favorites unchanged.) Play-time art is preserved by
    // DeezerTrackClient.loadTrack's merge, so grafted favorites carry through to the player/fullscreen.
    // ⚠️ SHARED WITH DeezerExtension.fetchLiked ONLY (internal since 2026-10-30, for the artist page's liked
    // index). Keep it on the FAVORITES path: never fold this into DeezerParser.toTrack / toEchoMediaItem,
    // which search and album use and where the FALLBACK direction is the opposite (Aug 6).
    internal fun graftFavTrack(entry: JsonObject): Track = parser.run {
        val d = entry.unwrap()
        val top = d.toTrack()
        val fb = d["FALLBACK"] as? JsonObject
        if (fb == null) top
        else {
            val fbTrack = fb.toTrack()
            top.copy(
                artists = fbTrack.artists,
                album = fbTrack.album,
                cover = fbTrack.cover,
                background = fbTrack.background
            )
        }
    }
}