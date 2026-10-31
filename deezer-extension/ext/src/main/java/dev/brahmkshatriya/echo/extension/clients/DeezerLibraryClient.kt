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

    private val tabs: List<Tab> = listOf(
        Tab(TabId.ALL.id, "All"),
        Tab(TabId.PLAYLISTS.id, "Playlists"),
        Tab(TabId.ALBUMS.id, "Albums"),
        Tab(TabId.TRACKS.id, "Tracks"),
        Tab(TabId.ARTISTS.id, "Artists"),
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
        ARTISTS("artists")
    }

    private val configs: Map<String, TabConfig> = listOf(
        TabConfig(TabId.PLAYLISTS, "Playlists", { getPlaylists() }) { it.tabDataArray("playlists") },
        TabConfig(TabId.ALBUMS, "Albums", { getAlbums() }) { it.tabDataArray("albums") },
        TabConfig(TabId.TRACKS, "Tracks", { getTracks() }) { it.resultsDataArray() },
        TabConfig(TabId.ARTISTS, "Artists", { getArtists() }) { it.tabDataArray("artists") },
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
            val (data, liked) = when (id) {
                TabId.ALL.id -> loadAll()
                else -> loadSingle(id)
            }
            val buttons = Feed.Buttons(
                showPlayAndShuffle = id == TabId.TRACKS.id,
                customTrackList = liked,
            )
            data.toFeedData(buttons)
        }
    }

    /** Shelves for the All tab, plus the grafted favorites so [loadLibraryFeed] can declare them. */
    private suspend fun loadAll(): Pair<List<Shelf>, List<Track>?> = supervisorScope {
        deezerExtension.handleArlExpiration()
        val results = configs.values.map { cfg ->
            async(cpuDispatcher) {
                val json = cfg.request(api)
                // Before the null return below, so the tile shows for an account with no playlists.
                if (cfg.id == TabId.PLAYLISTS) {
                    val list = withFavoriteTracks(playlistItems(cfg.extractor(json)))
                    return@async list.takeIf { it.isNotEmpty() }
                        ?.let { Shelf.Lists.Items(id = cfg.title, title = cfg.title, list = it) } to null
                }
                val items = cfg.extractor(json) ?: return@async null to null
                if (cfg.id == TabId.TRACKS) {
                    // One batched availability pass over the same rows - see DeezerApi.markUnplayable.
                    // Both tab paths get it because the same list is declared to the app from both; see
                    // the customTrackList note at loadLibraryFeed.
                    val grafted = api.markUnplayable(
                        items.mapNotNull { el -> (el as? JsonObject)?.let { graftFavTrack(it) } }
                    )
                    // The artist page's liked index, refreshed from this list at no cost.
                    deezerExtension.refreshLiked(grafted)
                    val shelf = grafted.takeIf { it.isNotEmpty() }
                        ?.let { Shelf.Lists.Items(id = cfg.title, title = cfg.title, list = it) }
                    shelf to grafted
                } else parser.run { items.toShelfItemsList(cfg.title) } to null
            }
        }.awaitAll()
        results.mapNotNull { it.first } to results.firstNotNullOfOrNull { it.second }
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
            val liked = api.markUnplayable(
                arr.mapNotNull { el -> (el as? JsonObject)?.let { graftFavTrack(it) } }
            )
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
        // ⚠⚠ TEMPORARY PROBE (2026-10-30) - REMOVE BOTH GladixFav LINES (this one and AndroidAutoCallback's
        // probeFav) AFTER ONE CAPTURE of the car's Library -> Playlists and Playlists shortcut. Prints on
        // every path. memo is the state BEFORE the lookup: unset = first lookup this session, empty = a
        // remembered failure or "none", id = cached. added=no-id / present / yes is which return ran.
        val memo = deezerExtension.lovedTracksMemoState
        fun probe(added: String) = println(
            "GladixFav DEEZER hasUserId=${api.hasUserId} memo=$memo added=$added items=${items.size}"
        )
        val id = deezerExtension.lovedTracksIdOrNull() ?: return items.also { probe("no-id") }
        if (items.any { it is Playlist && it.id == id }) return items.also { probe("present") }
        probe("yes")
        // isSaveable = false as in loadPlaylist, so the long-press sheet's pre-load placeholder offers no Save.
        return listOf(Playlist(id = id, title = FAVORITE_TRACKS, isEditable = false, isSaveable = false)) + items
    }

    private companion object {
        // US English, matching Deezer's US app.
        const val FAVORITE_TRACKS = "Favorite tracks"
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