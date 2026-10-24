package dev.brahmkshatriya.echo.extension.clients

import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeedData
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


    suspend fun loadLibraryFeed(): Feed<Shelf> {
        deezerExtension.handleArlExpiration()
        return Feed(tabs) { tab ->
            val id = tab?.id
            val data = when (id) {
                TabId.ALL.id -> loadAll()
                else -> loadSingle(id)
            }
            val buttons = if (id == TabId.TRACKS.id) Feed.Buttons(showPlayAndShuffle = true)
            else Feed.Buttons()
            data.toFeedData(buttons)
        }
    }

    private suspend fun loadAll(): List<Shelf> = supervisorScope {
        deezerExtension.handleArlExpiration()
        configs.values.map { cfg ->
            async(cpuDispatcher) {
                val json = cfg.request(api)
                val items = cfg.extractor(json) ?: return@async null
                if (cfg.id == TabId.TRACKS) {
                    val grafted = items.mapNotNull { el -> (el as? JsonObject)?.let { graftFavTrack(it) } }
                    grafted.takeIf { it.isNotEmpty() }
                        ?.let { Shelf.Lists.Items(id = cfg.title, title = cfg.title, list = it) }
                } else parser.run { items.toShelfItemsList(cfg.title) }
            }
        }.awaitAll().filterNotNull()
    }

    private suspend fun loadSingle(id: String?): List<Shelf> {
        val cfg = configs[id] ?: return emptyList()
        deezerExtension.handleArlExpiration()
        val json = cfg.request(api)
        val arr = cfg.extractor(json) ?: return emptyList()
        return if (id == TabId.TRACKS.id)
            arr.mapNotNull { el -> (el as? JsonObject)?.let { graftFavTrack(it).toShelf() } }
        else parser.run { arr.mapNotNull { it.jsonObject.toEchoMediaItem()?.toShelf() } }
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
    private fun graftFavTrack(entry: JsonObject): Track = parser.run {
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