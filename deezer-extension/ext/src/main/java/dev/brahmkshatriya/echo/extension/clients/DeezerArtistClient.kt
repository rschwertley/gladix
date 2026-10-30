package dev.brahmkshatriya.echo.extension.clients

import dev.brahmkshatriya.echo.common.helpers.Page
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeedData
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.extension.DeezerApi
import dev.brahmkshatriya.echo.extension.objOrNull
import dev.brahmkshatriya.echo.extension.arrOrNull
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
        val resultsObject = jsonObject["results"].objOrNull() ?: return@Single emptyList()

        listOfNotNull(likedSongsShelf(artist)) + orderedKeys.flatMap { key ->
            val payload = resultsObject[key] ?: return@flatMap emptyList()
            when (key) {
                "ALBUMS" -> listOfNotNull(buildAlbumsShelf(artist, payload.jsonObject))
                "TOP" -> buildTopTracksShelf(artist, payload.jsonObject)
                "RELATED_ARTISTS" -> listOfNotNull(buildRelatedArtistsShelf(artist, payload.jsonObject))
                else -> listOfNotNull(shelfFactories[key]?.invoke(parser, payload.jsonObject))
            }
        }
    }.toFeed()

    /**
     * ⚠️ "LIKED SONGS" - ONE ENTRY, FIRST ON THE PAGE (2026-10-30), like Spotify's: the user's liked songs by
     * this artist (DeezerExtension.likedByArtist - by artist id, or credited as a guest after "feat."). null
     * for a guest session or no match, so no entry shows.
     * ONE ENTRY, NOT ROWS: a song tapped ON the artist page has the artist as its context, which
     * FeedClickListener.isOrderedCollection does not treat as a collection, so it would start a radio; and
     * declaring the liked list for the whole artist feed would also catch a liked song in Top. The opened page
     * is its own feed with showPlayAndShuffle + customTrackList, so a tap queues the list from that song and
     * Play/Shuffle queue all of it, in the order shown - the 1147 declared-list path, as Library -> Tracks.
     * REQUESTS: none for the artist page (the liked list is the session's one fetch, usually already made by
     * the first heart check). Opening the page makes NO song fetch - LikedEntry.toTrack rebuilds the grafted
     * Tracks with FALLBACK_ID intact - and one markUnplayable batch, as every list open does (1148).
     */
    private suspend fun likedSongsShelf(artist: Artist): Shelf? {
        val liked = deezerExtension.likedByArtist(artist)
        if (liked.isEmpty()) return null
        return Shelf.Category(
            id = "liked",
            title = "Liked songs",
            subtitle = if (liked.size == 1) "1 song" else "${liked.size} songs",
            feed = Feed(listOf()) {
                val tracks = api.markUnplayable(liked.map { it.toTrack() })
                tracks.map<Track, Shelf> { it.toShelf() }.toFeedData(
                    Feed.Buttons(showPlayAndShuffle = true, customTrackList = tracks)
                )
            },
        )
    }

    private fun buildTopTracksShelf(artist: Artist, jObject: JsonObject): List<Shelf> {
        val shelf = parser.run {
            jObject["data"]?.jsonArray?.toShelfItemsList("Top") as? Shelf.Lists.Items
        }
        val list = shelf?.list?.filterIsInstance<Track>().orEmpty()
        if (list.isEmpty()) return emptyList()
        return listOf(Shelf.Category("top", "Top", feed = null)) +
            list.take(5).map { Shelf.Item(it) }
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
        val resultsObject = jsonObject["results"].objOrNull() ?: return artist
        return parser.run { resultsObject.toArtist() }
    }

    suspend fun isFollowing(item: EchoMediaItem): Boolean {
        // Lenient at every hop: "not following" is the honest answer when the shape is not what
        // we expect, and it is also what an absent key already produced. See DeezerJson.
        val dataArray = api.getArtists()["results"].objOrNull()
            ?.get("TAB")?.objOrNull()
            ?.get("artists")?.objOrNull()
            ?.get("data")?.arrOrNull() ?: return false

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
                jObject["ITEM"]?.jsonObject?.toShelfItemsList("Highlight")
                    ?.takeIf { it !is Shelf.Lists.Items || it.list.size > 1 }
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