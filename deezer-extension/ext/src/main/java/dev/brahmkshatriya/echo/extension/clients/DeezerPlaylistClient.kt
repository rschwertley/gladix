package dev.brahmkshatriya.echo.extension.clients

import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.extension.DeezerApi
import dev.brahmkshatriya.echo.extension.arrOrNull
import dev.brahmkshatriya.echo.extension.objOrNull
import dev.brahmkshatriya.echo.extension.requireObj
import dev.brahmkshatriya.echo.extension.DeezerExtension
import dev.brahmkshatriya.echo.extension.DeezerParser
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class DeezerPlaylistClient(private val deezerExtension: DeezerExtension, private val api: DeezerApi, private val parser: DeezerParser) {

    // Deezer has no related-content shelves for playlists. Return null (not an empty Feed) so
    // the feed pipeline treats it as "no section" and renders nothing. An empty but non-null
    // Feed is interpreted as "a section that loaded empty" and engages the empty-state
    // placeholder (EmptyAdapter), which shows a spinner/illustration we don't want here.
    // `playlist` is unused BY DESIGN — the answer is null regardless of which playlist is asked about.
    // Kept rather than removed: this is the sole delegate for DeezerExtension.loadFeed(playlist), which
    // overrides PlaylistClient.loadFeed(playlist) in :common, and it mirrors the sibling
    // DeezerArtistClient.getShelves(artist) which DOES use its argument. Dropping it would desync both.
    @Suppress("UNUSED_PARAMETER")
    fun getShelves(playlist: Playlist): Feed<Shelf>? = null

    suspend fun loadPlaylist(playlist: Playlist): Playlist {
        // A smarttracklist ("Made for you" daily mix) is NOT a playlist to Deezer: its id resolves in
        // neither deezer.pagePlaylist nor playlist.getSongs, so api.playlist() below would return an error
        // body and `results!!` would NPE. The feed row already carries everything the detail header shows
        // (title, subtitle, cover) because DeezerParser.toSmartTracklist builds it from the Home payload,
        // so returning the item unchanged loses nothing — there is no richer form to fetch.
        // Keyed on the extra rather than on the id's shape: ids are opaque strings and "inspired-by-1"
        // happens to be non-numeric today, which is an observation about one id, not a contract.
        if (playlist.extras.containsKey(SMART_TRACKLIST_EXTRA)) return playlist
        deezerExtension.handleArlExpiration()
        val jsonObject = api.playlist(playlist)
        // STRICT ON THE TYPE ONLY: a playlist whose envelope is a literal is a failed load, not
        // an empty playlist - the same argument loadTracks below already makes.
        // ⚠️ THE `!!` STAYS FOR THE ABSENT CASE, for the reason recorded at
        // DeezerAlbumClient.results: absent threw a NullPointerException before this sweep and
        // did not reach any "no longer available" handling, so re-typing it would change a
        // user-visible message on a path nobody has reported. Only the literal is upgraded.
        val resultsObject = jsonObject["results"]?.requireObj("results")!!
        return parser.run { resultsObject.toPlaylist() }
    }

    fun loadTracks(playlist: Playlist): Feed<Track> = PagedData.Single {
        deezerExtension.handleArlExpiration()
        playlist.extras[SMART_TRACKLIST_EXTRA]?.let { return@Single smartTracklistTracks(it) }
        // Tracks come from the dedicated playlist.getSongs (the app/deezer-py authoritative path), NOT from
        // deezer.pagePlaylist's inline SONGS (a summary that can carry a wrong same-named-artist twin).
        // pagePlaylist is still used for playlist METADATA in loadPlaylist above.
        val jsonObject = api.playlistSongs(playlist)
        // `results` is present for ANY valid response (empty playlists included), so a missing `results`
        // is a genuine Deezer error response (callApi returns non-CSRF errors un-thrown). Surface it with
        // Deezer's error message if available — do NOT degrade to empty, which would mask the failure as an
        // empty playlist. (message extraction is runCatching-guarded so it can never throw over the real error.)
        // objOrNull so a PRESENT literal takes this same path rather than throwing an opaque
        // IllegalArgumentException - the error below is the readable one. See DeezerJson.
        val results = jsonObject["results"].objOrNull() ?: run {
            val message = runCatching {
                jsonObject["error"]?.jsonObject?.entries
                    ?.joinToString { (key, value) -> value.jsonPrimitive.contentOrNull ?: key }
                    ?.takeIf { it.isNotBlank() }
            }.getOrNull()
            throw Exception(message ?: "Failed to load playlist tracks")
        }
        // playlist.getSongs returns tracks at results.data[] (flat — NOT results.SONGS.data). Absent/empty
        // (empty or edge playlist) degrades to an empty list rather than NPE. PagedData.Single (non-paginated),
        // so an empty result can never mask a mid-pagination gap.
        val dataArray = results["data"]?.jsonArray ?: JsonArray(emptyList())
        // playlist.getSongs pre-substitutes an unavailable original with a playable-but-dead/mis-attributed
        // track at TOP-LEVEL and moves the CORRECT catalog data into a full FALLBACK object. So when a
        // FALLBACK exists, take the DISPLAY fields (artists/album/cover) from it while keeping the top-level
        // SNG_ID as the track id for STREAMING (top-level is the confirmed-playable one). No FALLBACK →
        // top-level is already correct, use as-is. (Verified on Piper/Baby Beluga/Dora; fb=no unchanged.)
        val baseTracks = dataArray.filterIsInstance<JsonObject>().map { entry ->
            parser.run {
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
        // One batched availability pass before the NEXT/playlist_id stamping - see
        // DeezerApi.markUnplayable. It changes no ids and drops no rows, so the NEXT chain below is
        // the same chain; it is read off `marked` only so the two cannot drift apart later.
        val marked = api.markUnplayable(baseTracks)
        marked.mapIndexed { index, track ->
            track.copy(
                extras = track.extras + mapOf(
                    "NEXT" to marked.getOrNull(index + 1)?.id.orEmpty(),
                    "playlist_id" to playlist.id
                )
            )
        }
    }.toFeed()

    /**
     * Tracks for a smarttracklist, via page.get on "smarttracklist/<id>" — the same gateway method Home
     * itself uses, with the id substituted into PAGE. That is the only endpoint reachable from what we
     * hold: the playlist methods take a playlist id, and this is not one.
     *
     * ⚠️ THE RESPONSE SHAPE IS INFERRED, NOT OBSERVED. Only the Home payload has been captured; the page
     * for an individual smarttracklist has not. The traversal below therefore assumes only the shape every
     * page.get response has shared so far — results.sections[].items[] — and finds tracks by asking each
     * item whether it parses as a song, rather than by indexing a section position or a module id.
     *
     * ON-DEVICE FAILURE MODE IF THAT ASSUMPTION IS WRONG, so the first build's symptom is legible:
     *  - Unexpected but well-formed JSON (sections elsewhere, or none) -> zero tracks -> throw below ->
     *    the track list shows an ERROR, not an endless spinner. IT DOES NOT STRAND, and that chain was
     *    read rather than assumed: Cached.loadPlaylistTracksCached materialises this PagedData.Single
     *    eagerly (feed.loadAll() inside coalescedTracks), so the throw lands in Cached.loadTracks's
     *    runCatching -> MediaDetailsViewModel.tracksLoadedFlow carries a failed Result -> the fragment's
     *    `loader` rethrows it into FeedData's `loadedState = runCatching { load(...) }`, which is the
     *    feed's error surface. Deliberately a throw and not an empty list — an empty list is
     *    indistinguishable from a mix Deezer legitimately emptied, and would look like a working feature
     *    quietly returning nothing.
     *  - An error body (bad/expired id) -> also zero tracks -> same error. The GladixDeezer line below
     *    names the id and the section count, which separates "we asked wrong" from "the page is empty".
     * Either way the failure is bounded to this one row: Home still renders, and nothing is cached (see
     * Cached.carriesExpiry — an item with an `expires` extra is never written to the durable store, so a
     * bad first load cannot stick for 24h).
     */
    /**
     * ⚠⚠ DO NOT WRITE THE OBVIOUS GRAPHQL MAPPER HERE. IT WOULD LOOK FIXED AND BE UNPLAYABLE. ⚠⚠
     *
     * Deezer serves smarttracklist tracks over GRAPHQL (pipe.deezer.com, query GetSmartTracklist,
     * response at data.smartTracklist.tracks.edges[].node) — verified from
     * music-assistant/deezer-python-gql, queries/get_smart_tracklist.graphql. The tempting move once that
     * lands is to map those nodes straight to our Track. DO NOT.
     *
     * The node comes from the repo's TrackFields fragment: id, title, ISRC, diskInfo, duration,
     * isExplicit, isFavorite, popularity, album, contributors. THERE IS NO MD5_ORIGIN, NO FILESIZE_*, AND
     * NO TRACK_TOKEN — which are exactly the fields DeezerParser.toTrack builds `streamables` from. A
     * mapped Track therefore renders perfectly: right title, right artist, right cover, right duration —
     * and cannot play. THAT IS WORSE THAN TODAY'S FAILURE, which at least announces itself: a mix that
     * opens to a full track list and then fails at playback looks like a streaming bug, not a parsing
     * gap, and it costs a build plus a device test to discover. Today's version throws immediately.
     * A second mapper would also fork Deezer track parsing in two, so every future field change to
     * toTrack silently misses one path.
     *
     * THE SHAPE THAT WORKS: use GraphQL for the ID LIST ONLY (edges[].node.id), then fetch full gateway
     * records for those ids and hand them to the existing toTrack unchanged. Zero new parsing, real
     * streamables, one code path. That needs a chunked song.getListData wrapper in api/DeezerTrack.kt
     * (which has none today — `track(id)` posts a single SNG_ID, and N single calls for a 40-track mix is
     * not acceptable). MATCH THE SHIPPED CALL SHAPE FROM THE PIPER WORK: chunked, parameter SNG_IDS,
     * UPPERCASE, response read at results.data. gw-light params are effectively case-insensitive, so this
     * is consistency with a known-working call site rather than a requirement — take it anyway, it is free.
     *
     * GATED ON THE ID QUESTION, WHICH IS NOT YET ANSWERED. See the STL-ID probe in
     * DeezerHomeFeedClient.probeSmartTracklist: our Home item carries a SLOT id (SMARTTRACKLIST_ID,
     * "inspired-by-1", identical to CONFIGURATION_ID) and an INSTANCE id (data.ID, embedding a user id and
     * a date). Which one GraphQL accepts is unknown, the repo's fixtures are hand-written and prove
     * nothing, and open-source verification establishes that an endpoint EXISTS — not that it accepts the
     * ids we hold. playlist.getSongs was verified exactly this way, shipped, and did not answer the
     * question being asked of it. Do not build past the probe.
     */
    private suspend fun smartTracklistTracks(stlId: String): List<Track> {
        // TWO STEPS, AND THE SPLIT IS THE WHOLE DESIGN (see the block above): GraphQL supplies IDS ONLY,
        // the gateway supplies the RECORDS, and DeezerParser.toTrack — unchanged, shared with every other
        // track path in this extension — does the parsing.
        val ids = api.smartTracklistTrackIds(stlId)
        if (ids.isEmpty()) {
            println("GladixDeezer DROP smarttracklist=$stlId reason=no-ids-from-graphql")
            throw Exception("Failed to load tracks for this mix")
        }
        // song.getListData returns results.data[] per chunk — the SAME shape playlist.getSongs returns, so
        // the parse below mirrors loadTracks' rather than inventing a second one.
        val entries = api.getListData(ids).flatMap { page ->
            (page["results"] as? JsonObject)?.get("data")?.arrOrNull()
                ?.filterIsInstance<JsonObject>().orEmpty()
        }
        val baseTracks = entries.mapNotNull { entry ->
            parser.run {
                val d = entry.unwrap()
                // Song-only, same reason as the old traversal: a mix can carry non-song entries and
                // toTrack on one would yield a track with no SNG_ID rather than fail loudly.
                if (d.str("__TYPE__")?.contains("song") != true) null
                else runCatching { d.toTrack() }.getOrNull()
            }
        }.distinctBy { it.id }
        if (baseTracks.isEmpty()) {
            // Separated from the no-ids case on purpose: "GraphQL gave us nothing" and "GraphQL gave us
            // ids the gateway would not resolve" are different failures with different next steps.
            println(
                "GladixDeezer DROP smarttracklist=$stlId reason=no-tracks-parsed " +
                    "ids=${ids.size} entries=${entries.size}"
            )
            throw Exception("Failed to load tracks for this mix")
        }
        // A partial resolve is worth seeing but NOT worth failing: the mix plays with what came back.
        if (baseTracks.size < ids.size) println(
            "GladixDeezer PARTIAL smarttracklist=$stlId ids=${ids.size} resolved=${baseTracks.size}"
        )
        return baseTracks.mapIndexed { index, track ->
            // NEXT only — no "playlist_id". DeezerUtil.log maps that key to ctxtT="playlist_page" plus the
            // id, and this id is not a playlist, so it would report a context Deezer cannot resolve. The
            // else-branch's empty context is the honest value for a context we have no logging name for.
            track.copy(
                extras = track.extras + mapOf("NEXT" to baseTracks.getOrNull(index + 1)?.id.orEmpty())
            )
        }
    }

    companion object {
        // Set by DeezerParser.toSmartTracklist; the sole routing signal for the two branches above.
        const val SMART_TRACKLIST_EXTRA = "smarttracklist"
    }
}
