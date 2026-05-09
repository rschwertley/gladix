package dev.brahmkshatriya.echo.extension.api

import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.extension.DeezerApi
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put

class DeezerShow(private val deezerApi: DeezerApi) {

    suspend fun show(album: Album, language: String, userId: String): JsonObject {
        return deezerApi.callApi(
            method = "deezer.pageShow",
            paramsBuilder = {
                put("country", language.substringAfter("-"))
                put("lang", deezerApi.langCode)
                put("nb", album.trackCount)
                put("show_id", album.id)
                put("start", 0)
                put("user_id", userId)
            }
        )
    }

    suspend fun getShows(userId: String): JsonObject {
        return deezerApi.callApi(
            method = "deezer.pageProfile",
            paramsBuilder = {
                put("user_id", userId)
                put("tab", "shows")
                put("nb", 2000)
            }
        )
    }

    suspend fun addFavoriteShow(id: String) {
        deezerApi.callApi(
            method = "show.addFavorite",
            paramsBuilder = {
                put("SHOW_ID", id)
            }
        )
    }

    suspend fun removeFavoriteShow(id: String) {
        deezerApi.callApi(
            method = "show.deleteFavorite",
            paramsBuilder = {
                put("SHOW_ID", id)
            }
        )
    }

    suspend fun getBookmarkedEpisodes(userId: String): JsonObject {
        return deezerApi.callAppApi(
            method = "episode.bookmarkGetList",
            paramsBuilder = {
                put("USER_ID", userId)
            }
        )
    }

    suspend fun bookmarkEpisode(id: String, offset: Long, duration: Double) {
        deezerApi.callApi(
            method = "episode.bookmarkSet",
            paramsBuilder = {
                put("EPISODE_ID", id)
                put("OFFSET", offset)
                put("DURATION", duration)
            }
        )
    }
}