package dev.brahmkshatriya.echo.extension.api

import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.extension.DeezerApi
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put

class DeezerShow(private val deezerApi: DeezerApi) {

    /**
     * ⚠⚠ `country` HERE IS PARSED OUT OF THE LANGUAGE TAG, NOT READ FROM DeezerApi.country,
     * AND THAT IS A SEPARATE DEFECT - SCOPED, NOT FIXED. DeezerApi.show passes `language`, and the line
     * below takes language.substringAfter("-"). So a user in Germany who picked "English (UK)" sends
     * country=GB for shows while their country SETTING says DE. The right source is
     * DeezerApi.country, whose own mismatch was fixed 2026-10-03.
     * ⚠️ DELIBERATELY NOT FOLDED INTO THAT FIX: it changes behaviour for users who already
     * work, since podcast availability and episode listings would move for anyone whose language
     * region differs from their country. That needs its own decision.
     * ⚠️ AND substringAfter("-") IS STILL WRONG EVEN AFTER THE LANGUAGE FIX - residue recorded
     * so the next reader does not assume resolveApiLanguageTag closed it. That resolver returns a
     * device tag VERBATIM when its prefix is supported, so a tag carrying a SCRIPT subtag survives:
     * `sr-Latn-RS`.substringAfter("-") is "Latn-RS", not "RS". (The zh-Hans-CN shape IS closed, but
     * only incidentally - `zh` is not a supported prefix, so it routes to "en-<region>".) The robust
     * form is Locale.forLanguageTag(language).country, not string surgery - a third reason this belongs
     * in its own change.
     */
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