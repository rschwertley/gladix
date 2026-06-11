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
            val dataArray: JsonArray = when (kind) {
                RadioKind.TRACK -> api.mix(radio.id).resultsArray("data")
                RadioKind.ARTIST -> api.mixArtist(radio.id).resultsArray("data")
                RadioKind.FLOW -> api.flow(radio.id).resultsArray("data")
                else -> null
            } ?: JsonArray(emptyList())

            dataArray.mapIndexed { index, song ->
                val track = song.safeObj()?.toTrack(parser) ?: return@mapIndexed null
                val next = dataArray.getOrNull(index + 1)?.safeObj()?.toTrack(parser)
                val nextId = next?.id.orEmpty()

                val addlExtras = when (kind) {
                    RadioKind.TRACK -> mapOf("artist_id" to track.artists.firstOrNull()?.id.orEmpty())
                    RadioKind.ARTIST -> mapOf("artist_id" to radio.id)
                    RadioKind.FLOW -> mapOf("user_id" to "0")
                    else -> emptyMap()
                }

                track.copy(extras = track.extras + mapOf("NEXT" to nextId) + addlExtras)
            }.filterNotNull().let { tracks ->
                if (kind == RadioKind.TRACK) tracks.filter { it.id != radio.id } else tracks
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
        extras = mapOf("radio" to "track")
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