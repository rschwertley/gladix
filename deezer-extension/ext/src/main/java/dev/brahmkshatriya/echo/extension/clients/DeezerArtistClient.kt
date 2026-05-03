package dev.brahmkshatriya.echo.extension.clients

import dev.brahmkshatriya.echo.common.helpers.Page
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.extension.DeezerApi
import dev.brahmkshatriya.echo.extension.DeezerExtension
import dev.brahmkshatriya.echo.extension.DeezerParser
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class DeezerArtistClient(private val deezerExtension: DeezerExtension, private val api: DeezerApi, private val parser: DeezerParser) {

    fun getShelves(artist: Artist): Feed<Shelf> = PagedData.Single {
        deezerExtension.handleArlExpiration()
        val jsonObject = api.artist(artist.id)
        val resultsObject = jsonObject["results"]?.jsonObject ?: return@Single emptyList()

        orderedKeys.mapNotNull { key ->
            val payload = resultsObject[key] ?: return@mapNotNull null
            when (key) {
                "ALBUMS" -> buildAlbumsShelf(artist, payload.jsonObject)
                "TOP" -> buildTopTracksShelf(artist, payload.jsonObject)
                "RELATED_ARTISTS" -> buildRelatedArtistsShelf(artist, payload.jsonObject)
                else -> shelfFactories[key]?.invoke(parser, payload.jsonObject)
            }
        }
    }.toFeed()

    private fun buildTopTracksShelf(artist: Artist, jObject: JsonObject): Shelf? {
        val shelf = parser.run {
            jObject["data"]?.jsonArray?.toShelfItemsList("Top") as? Shelf.Lists.Items
        }
        val list = (shelf?.list as? List<Track>).orEmpty()
        if (list.isEmpty()) return null
        return Shelf.Lists.Tracks(
            id = shelf!!.id,
            title = shelf.title,
            subtitle = shelf.subtitle,
            type = Shelf.Lists.Type.Linear,
            more = PagedData.Continuous<Shelf> { continuation ->
                deezerExtension.handleArlExpiration()
                val index = continuation?.toIntOrNull() ?: 0
                val response = api.artistTop(artist.id, index)
                val total = response["total"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                val tracks = response["data"]?.jsonArray?.mapNotNull { element ->
                    runCatching {
                        parser.run { element.jsonObject.toTrackFromRestApi() }.toShelf()
                    }.getOrNull()
                } ?: emptyList()
                val nextIndex = index + PAGE_SIZE
                Page(tracks, if (nextIndex < total) nextIndex.toString() else null)
            }.toFeed(),
            list = list.take(5)
        )
    }

    private fun buildRelatedArtistsShelf(artist: Artist, jObject: JsonObject): Shelf? {
        val shelf = parser.run {
            jObject["data"]?.jsonArray?.toShelfItemsList("Related Artists") as? Shelf.Lists.Items
        }
        val list = shelf?.list.orEmpty()
        if (list.isEmpty()) return null
        return Shelf.Lists.Items(
            id = shelf!!.id,
            title = shelf.title,
            subtitle = shelf.subtitle,
            type = Shelf.Lists.Type.Linear,
            more = PagedData.Continuous<Shelf> { continuation ->
                deezerExtension.handleArlExpiration()
                val index = continuation?.toIntOrNull() ?: 0
                val response = api.artistRelated(artist.id, index)
                val total = response["total"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                val artists = response["data"]?.jsonArray?.mapNotNull { element ->
                    runCatching {
                        parser.run { element.jsonObject.toArtistFromRestApi() }.toShelf()
                    }.getOrNull()
                } ?: emptyList()
                val nextIndex = index + PAGE_SIZE
                Page(artists, if (nextIndex < total) nextIndex.toString() else null)
            }.toFeed(),
            list = list
        )
    }

    private fun buildAlbumsShelf(artist: Artist, jObject: JsonObject): Shelf? {
        val shelf = parser.run {
            jObject["data"]?.jsonArray?.toShelfItemsList("Albums") as? Shelf.Lists.Items
        }
        val list = shelf?.list.orEmpty()
        if (list.isEmpty()) return null
        return Shelf.Lists.Items(
            id = shelf!!.id,
            title = shelf.title,
            subtitle = shelf.subtitle,
            type = Shelf.Lists.Type.Linear,
            more = PagedData.Continuous<Shelf> { continuation ->
                deezerExtension.handleArlExpiration()
                val index = continuation?.toIntOrNull() ?: 0
                val response = api.artistAlbums(artist.id, index)
                val total = response["total"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                val albums = response["data"]?.jsonArray?.mapNotNull { element ->
                    runCatching {
                        parser.run { element.jsonObject.toAlbumFromRestApi(artist) }.toShelf()
                    }.getOrNull()
                } ?: emptyList()
                val nextIndex = index + PAGE_SIZE
                Page(albums, if (nextIndex < total) nextIndex.toString() else null)
            }.toFeed(),
            list = list
        )
    }

    suspend fun loadArtist(artist: Artist): Artist {
        deezerExtension.handleArlExpiration()
        val jsonObject = api.artist(artist.id)
        val resultsObject = jsonObject["results"]?.jsonObject ?: return artist
        return parser.run { resultsObject.toArtist() }
    }

    suspend fun isFollowing(item: EchoMediaItem): Boolean {
        val dataArray = api.getArtists()["results"]?.jsonObject
            ?.get("TAB")?.jsonObject
            ?.get("artists")?.jsonObject
            ?.get("data")?.jsonArray ?: return false

        return dataArray.any { artistItem ->
            val artistId = artistItem.jsonObject["ART_ID"]?.jsonPrimitive?.content
            artistId == item.id
        }
    }

    fun getFollowersCount(item: EchoMediaItem): Long? = item.extras["followers"]?.toLongOrNull()

    private companion object {
        private fun Shelf.isEffectivelyEmpty(): Boolean = when (this) {
            is Shelf.Lists.Items -> list.isEmpty()
            is Shelf.Lists.Tracks -> list.isEmpty()
            else -> false
        }
        private fun Shelf?.nullIfEmpty(): Shelf? = this?.takeIf { !it.isEffectivelyEmpty() }

        private val shelfFactories: Map<String, DeezerParser.(JsonObject) -> Shelf?> = mapOf(
            "HIGHLIGHT" to filterEmpty { jObject ->
                jObject["ITEM"]?.jsonObject?.toShelfItemsList("Highlight").nullIfEmpty()
            },
            "SELECTED_PLAYLIST" to filterEmpty { jObject ->
                jObject["data"]?.jsonArray?.toShelfItemsList("Selected Playlists").nullIfEmpty()
            },
            "RELATED_PLAYLIST" to filterEmpty { jObject ->
                jObject["data"]?.jsonArray?.toShelfItemsList("Related Playlists").nullIfEmpty()
            },
        )

        private const val PAGE_SIZE = 50

        private fun filterEmpty(
            block: DeezerParser.(JsonObject) -> Shelf?
        ): DeezerParser.(JsonObject) -> Shelf? = { json ->
            block(this, json)?.takeIf { !it.isEffectivelyEmpty() }
        }

        private val orderedKeys = listOf(
            "TOP",
            "HIGHLIGHT",
            "SELECTED_PLAYLIST",
            "ALBUMS",
            "RELATED_PLAYLIST",
            "RELATED_ARTISTS"
        )
    }
}