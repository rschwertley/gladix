package dev.brahmkshatriya.echo.extension.clients

import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Radio
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.extension.DeezerApi
import dev.brahmkshatriya.echo.extension.DeezerGatewayException
import dev.brahmkshatriya.echo.extension.DeezerParser
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

class DeezerRadioClient(private val api: DeezerApi, private val parser: DeezerParser) {

    fun loadTracks(radio: Radio): Feed<Track> = PagedData.Single {
        val kind = radio.kind()

        if (kind == RadioKind.PLAYLIST || kind == RadioKind.ALBUM) {
            val sourceId = radio.extras["source_id"]
            val freshSeeds: List<Track>? = if (sourceId != null) {
                runCatching {
                    when (radio.extras["source_type"]) {
                        "playlist" -> api.playlist(Playlist(id = sourceId, title = "", isEditable = false))
                            .randomTracksFromSongs(parser, 8)
                        else -> api.album(Album(id = sourceId, title = ""))
                            .randomTracksFromSongs(parser, 8)
                    }.takeIf { it.isNotEmpty() }
                }.getOrNull()
            } else null

            val seedIds = freshSeeds?.map { it.id }
                ?: radio.extras["seed_ids"]?.split(",")
                ?: listOf(radio.id)
            val seedArtists = freshSeeds?.map { it.artists.firstOrNull()?.id.orEmpty() }
                ?: radio.extras["seed_artists"]?.split(",")
                ?: listOf(radio.extras["artist"].orEmpty())
            val artistId = freshSeeds?.firstOrNull()?.artists?.firstOrNull()?.id.orEmpty()
                .ifBlank { radio.extras["artist"].orEmpty() }

            val includeSeeds = radio.extras["include_seeds"] == "true"
            val seedIdSet = seedIds.toSet()
            val (radioResults, fetchedSeedTracks) = coroutineScope {
                val radioJobs = seedIds.zip(seedArtists).map { (tId, aId) ->
                    async {
                        api.radio(tId, aId).resultsArray("data")
                            ?.mapNotNull { it.safeObj()?.toTrack(parser) }
                            ?: emptyList()
                    }
                }
                val seedJobs = if (includeSeeds) seedIds.map { tId ->
                    async {
                        runCatching {
                            api.track(tId)["results"]?.jsonObject?.toTrack(parser)
                        }.getOrNull()
                    }
                } else emptyList()
                val allRadio = radioJobs.awaitAll().flatten().distinctBy { it.id }
                    .filter { it.id !in seedIdSet }
                val seeds = seedJobs.awaitAll().filterNotNull()
                allRadio to seeds
            }
            val seedsWithCover = fetchedSeedTracks.map { t ->
                if (t.cover == null) t.copy(cover = radio.cover) else t
            }
            val merged = (seedsWithCover + radioResults).distinctBy { it.id }.shuffled()

            merged.mapIndexed { index, track ->
                val nextId = merged.getOrNull(index + 1)?.id.orEmpty()
                track.copy(extras = track.extras + mapOf("NEXT" to nextId, "artist_id" to artistId))
            }
        } else {
            // ⚠⚠ "EMPTY TRACKLIST" ARRIVES AS A THROW AND MUST LEAVE AS AN EMPTY LIST. The `?:`
            // fallbacks below cannot help: callApi throws DeezerGatewayException on a non-empty gateway
            // `error`, so resultsArray is never reached. smart.getSmartRadio answers DATA_ERROR
            // "empty tracklist for artist <id>" for artists with no radio, which is the same ANSWER as "no
            // tracks" delivered through the error channel - see DeezerGatewayException.isEmptyTracklist for
            // the measurement (48 events / 8 users on 1116) and why the match is code AND text.
            // ⚠⚠ WHAT THIS UNLOCKS, AND IT IS THE POINT RATHER THAN A SIDE EFFECT: an empty append
            // makes PlayerRadio.isThin true, and isThin opens with `!r.failed` - so while this threw, the
            // Last.fm bridge and the multi-seed escalation were BOTH unreachable. RadioFallback was built
            // and tested against an empty list and had never once seen this error. Returning empty hands
            // the station to it.
            // ⚠️ ACCEPTED, DELIBERATE BEHAVIOUR CHANGE: with failed=false the station counts as SPENT
            // rather than retryable. That is what stops the loop - a thrown load left the station Loaded so
            // every track transition retried it (artist 114420502, dozens of events, correlating with
            // aa_connected=true). Do not "restore" the throw to get retries back; the condition is
            // permanent and retrying it is what produced the report volume.
            // ⚠️ ONLY THIS PREDICATE IS SWALLOWED - everything else rethrows. A real refusal (auth,
            // region, a method that genuinely failed) must still reach the host, or this becomes the
            // silent-failure hole that the gateway-error reporting was added to close.
            val dataArray: JsonArray = try {
                when (kind) {
                    RadioKind.TRACK -> api.mix(radio.id).resultsArray("data") ?: JsonArray(emptyList())
                    RadioKind.ARTIST -> api.mixArtist(radio.id).resultsArray("data") ?: JsonArray(emptyList())
                    RadioKind.FLOW -> api.flow(radio.id).resultsArray("data") ?: JsonArray(emptyList())
                    RadioKind.PLAYLIST, RadioKind.ALBUM -> JsonArray(emptyList())
                }
            } catch (e: DeezerGatewayException) {
                if (!e.isEmptyTracklist) throw e
                JsonArray(emptyList())
            }

            dataArray.mapIndexed { index, song ->
                val track = song.safeObj()?.toTrack(parser) ?: return@mapIndexed null
                val next = dataArray.getOrNull(index + 1)?.safeObj()?.toTrack(parser)
                val nextId = next?.id.orEmpty()

                val addlExtras = when (kind) {
                    RadioKind.TRACK -> mapOf("artist_id" to track.artists.firstOrNull()?.id.orEmpty())
                    RadioKind.ARTIST -> mapOf("artist_id" to radio.id)
                    RadioKind.FLOW -> mapOf("user_id" to "0")
                    RadioKind.PLAYLIST, RadioKind.ALBUM -> emptyMap()
                }

                track.copy(extras = track.extras + mapOf("NEXT" to nextId) + addlExtras)
            }.filterNotNull().let { tracks ->
                // ⚠⚠ THIS STRIP ASSUMES THE CALLER ALREADY QUEUED THE SEED AT INDEX 0. THAT IS
                // HALF OF A CONTRACT AND THE OTHER HALF LIVES IN THE APP - READ BOTH BEFORE CHANGING EITHER.
                // Deezer's api.mix is called with start_with_input_track=false, so the seed is absent or
                // near-duplicated in the results; stripping it here is right ONLY because a track station
                // is supposed to be seed-first, with the tapped track played from index 0 and this mix
                // appended behind it. PlayerCallback.trackRadio is the app side that honours that, and its
                // comment states the same contract from the other direction.
                // ⚠️ PlayerCallback.radio HONOURS NEITHER HALF - it clears the queue and plays the
                // mix directly. Pointed at a Track it produced three defects on 2026-09-10 (wrong first
                // track, total silence, and a dead endless-queue fallback); the fix routes tracks away from
                // it at PlayerViewModel.radio. Anything that sends a Track to PlayerCallback.radio again
                // re-opens all three, and this filter is what makes them silent rather than noisy.
                // ⚠️ THE STRIP CAN REMOVE EVERYTHING. "Underwater" by The Frogmen is served under
                // two album ids, i.e. two track ids, and the title+artist test removes BOTH - a legitimately
                // empty result that is indistinguishable from an exhausted station. The app treats that case
                // at PlayerRadio.play's `thin` predicate; do not "fix" it here by weakening the test, because
                // the two-track loop it prevents is worse than the empty page it produces.
                if (kind == RadioKind.TRACK) {
                    val seedArtist = radio.extras["seed_artist_name"].orEmpty()
                    val seedTitle = radio.extras["seed_title"].orEmpty().stripVersionSuffix()
                    tracks.filter {
                        it.id != radio.id && !(
                            it.artists.firstOrNull()?.name.orEmpty().equals(seedArtist, ignoreCase = true) &&
                            it.title.stripVersionSuffix().equals(seedTitle, ignoreCase = true)
                        )
                    }
                } else tracks
            }
        }
    }.toFeed()

    suspend fun radio(item: EchoMediaItem, context: EchoMediaItem?): Radio = when (item) {
        is Artist -> Radio(
            id = item.id,
            title = item.name + " Radio",
            cover = item.cover,
            extras = mapOf("radio" to "artist")
        )

        is Album -> {
            val seeds = api.album(item).randomTracksFromSongs(parser, 8)
            val seed = seeds.firstOrNull() ?: error("No Radio")
            Radio(
                id = seed.id,
                title = item.title + " Radio",
                cover = item.cover ?: seed.cover,
                extras = mapOf(
                    "radio" to "album",
                    "source_id" to item.id,
                    "source_type" to "album",
                    "artist" to seed.artists.firstOrNull()?.id.orEmpty(),
                    "seed_ids" to seeds.joinToString(",") { it.id },
                    "seed_artists" to seeds.joinToString(",") { it.artists.firstOrNull()?.id.orEmpty() },
                    "include_seeds" to "true"
                )
            )
        }

        is Playlist -> {
            val seeds = api.playlist(item).randomTracksFromSongs(parser, 8)
            val seed = seeds.firstOrNull() ?: error("No Radio")
            Radio(
                id = seed.id,
                title = item.title + " Radio",
                cover = item.cover ?: seed.cover,
                extras = mapOf(
                    "radio" to "playlist",
                    "source_id" to item.id,
                    "source_type" to "playlist",
                    "artist" to seed.artists.firstOrNull()?.id.orEmpty(),
                    "seed_ids" to seeds.joinToString(",") { it.id },
                    "seed_artists" to seeds.joinToString(",") { it.artists.firstOrNull()?.id.orEmpty() },
                    "include_seeds" to "true"
                )
            )
        }

        is Track -> {
            when (context) {
                null -> item.asTrackRadio()
                is Radio -> when (context.kind()) {
                    RadioKind.TRACK -> item.asTrackRadio()
                    RadioKind.ARTIST -> Radio(
                        id = context.id,
                        title = context.title,
                        cover = context.cover,
                        extras = mapOf("radio" to "artist")
                    )
                    RadioKind.PLAYLIST -> context
                    RadioKind.ALBUM -> context
                    RadioKind.FLOW -> context.copy(title = "${context.title} Flow")
                }
                is Artist -> Radio(
                    id = context.id,
                    title = context.name + " Radio",
                    cover = context.cover,
                    extras = mapOf("radio" to "artist")
                )
                is Playlist -> {
                    val seeds = runCatching {
                        api.playlist(context).randomTracksFromSongs(parser, 8)
                    }.getOrDefault(emptyList())
                    val seed = seeds.firstOrNull()
                    if (seed == null) item.asCollectionTrackRadio("playlist")
                    else Radio(
                        id = seed.id,
                        title = context.title + " Radio",
                        cover = context.cover ?: seed.cover,
                        extras = mapOf(
                            "radio" to "playlist",
                            "source_id" to context.id,
                            "source_type" to "playlist",
                            "artist" to seed.artists.firstOrNull()?.id.orEmpty(),
                            "seed_ids" to seeds.joinToString(",") { it.id },
                            "seed_artists" to seeds.joinToString(",") { it.artists.firstOrNull()?.id.orEmpty() }
                        )
                    )
                }
                is Album -> {
                    val artist = item.artists.firstOrNull()?.takeIf { it.id.isNotBlank() }
                    if (artist != null) Radio(
                        id = artist.id,
                        title = artist.name + " Radio",
                        cover = artist.cover ?: item.cover,
                        extras = mapOf("radio" to "artist")
                    ) else item.asCollectionTrackRadio("album")
                }
                else -> error("No Radio")
            }
        }

        is Radio -> if (item.kind() == RadioKind.FLOW && !item.title.endsWith("Flow"))
            item.copy(title = "${item.title} Flow")
        else item
    }

    private enum class RadioKind { TRACK, ARTIST, PLAYLIST, ALBUM, FLOW }

    private fun Radio.kind(): RadioKind = when (extras["radio"]) {
        "track" -> RadioKind.TRACK
        "artist" -> RadioKind.ARTIST
        "playlist" -> RadioKind.PLAYLIST
        "album" -> RadioKind.ALBUM
        else -> RadioKind.FLOW
    }

    private fun Track.asTrackRadio() = Radio(
        id = id,
        title = title + " Radio",
        cover = cover,
        extras = mapOf(
            "radio" to "track",
            "seed_artist_name" to artists.firstOrNull()?.name.orEmpty(),
            "seed_title" to title
        )
    )

    private fun Track.asCollectionTrackRadio(tag: String) = Radio(
        id = id,
        title = title,
        cover = cover,
        extras = mapOf(
            "radio" to tag,
            "artist" to artists.firstOrNull()?.id.orEmpty()
        )
    )

    // ⚠️ MIRRORED APP-SIDE — EDIT BOTH OR NEITHER. PlayerRadio's companion carries VERSION_SUFFIX with
    // the identical pattern, used by its append dedup (dedupKey's title+artist fallback when a Track has no
    // ISRC). It is a COPY on purpose: the app cannot depend on this extension module, and the app-side rule
    // has to hold for every extension, not just Deezer. The two are therefore free to drift, and drift is
    // the whole risk — if you widen this one (e.g. to strip " - Live at X", which neither currently does),
    // widen the app's too or TRACK stations will filter differently from every other kind.
    // The two filters do NOT overlap fully and both are deliberate: this one compares against the SEED
    // (radio.extras seed_title / seed_artist_name), which the app-side filter cannot see — it only knows
    // what is in the queue — so this still removes a seed that was never queued or has scrolled out of the
    // app's dedup window. Keeping both was decided 2026-09-09, not left by omission.
    private val versionSuffixRegex = Regex(
        """\s*\(.*\)\s*$""",
        RegexOption.IGNORE_CASE
    )

    private fun String.stripVersionSuffix() = replace(versionSuffixRegex, "").trim()

    private fun JsonObject.resultsArray(key: String): JsonArray? =
        this["results"]?.jsonObject?.get(key)?.jsonArray

    private fun JsonElement.safeObj(): JsonObject? =
        runCatching { this.jsonObject }.getOrNull()

    private fun JsonObject.toTrack(parser: DeezerParser): Track =
        parser.run { this@toTrack.toTrack() }

    private fun JsonObject.randomTracksFromSongs(parser: DeezerParser, count: Int): List<Track> =
        runCatching {
            val songs = this["results"]?.jsonObject
                ?.get("SONGS")?.jsonObject
                ?.get("data")?.jsonArray
                ?: return emptyList()
            val allTracks = songs.shuffled().mapNotNull { it.safeObj()?.toTrack(parser) }
            val liked = allTracks.filter { it.extras["loved"] == "1" }
            val nonLiked = allTracks.filter { it.extras["loved"] != "1" }
            when {
                liked.size >= count -> liked.take(count)
                liked.isNotEmpty() -> liked + nonLiked.take(count - liked.size)
                else -> allTracks.take(count)
            }
        }.getOrDefault(emptyList())
}