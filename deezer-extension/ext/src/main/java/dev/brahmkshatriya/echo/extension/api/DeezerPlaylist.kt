package dev.brahmkshatriya.echo.extension.api

import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.extension.DeezerApi
import dev.brahmkshatriya.echo.extension.DeezerGatewayException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put

class DeezerPlaylist(private val deezerApi: DeezerApi) {

    suspend fun playlist(playlist: Playlist): JsonObject {
        return deezerApi.callApi(
            method = "deezer.pagePlaylist",
            paramsBuilder = {
                put("playlist_id", playlist.id)
                put("lang", deezerApi.langCode)
                put("nb", playlist.trackCount)
                put("tags", true)
                put("start", 0)
            }
        )
    }

    // Dedicated, authoritative track-list method — the path Deezer's own app / deezer-py use for playlist
    // tracks (deezer.pagePlaylist's inline SONGS is a summary and can carry mis-attributed entries).
    // Returns results.data[] of canonical SONG objects. nb=-1 fetches all tracks.
    suspend fun getSongs(playlist: Playlist): JsonObject {
        return deezerApi.callApi(
            method = "playlist.getSongs",
            paramsBuilder = {
                put("PLAYLIST_ID", playlist.id)
                put("nb", -1)
            }
        )
    }

    suspend fun getPlaylists(userId: String): JsonObject {
        return deezerApi.callApi(
            method = "deezer.pageProfile",
            paramsBuilder = {
                put("user_id", userId)
                put ("tab", "playlists")
                put("nb", 10000)
            }
        )
    }

    suspend fun addFavoritePlaylist(id: String) {
        deezerApi.callApi(
            method = "playlist.addFavorite",
            paramsBuilder = {
                put("PARENT_PLAYLIST_ID", id)
            }
        )
    }

    suspend fun removeFavoritePlaylist(id: String) {
        deezerApi.callApi(
            method = "playlist.deleteFavorite",
            paramsBuilder = {
                put("PLAYLIST_ID", id)
            }
        )
    }

    // ⚠⚠ ERROR_DATA_EXISTS IS SWALLOWED AS SUCCESS, AND THAT IS NOT LENIENCY - THE OPERATION IS
    // IDEMPOTENT. The gateway answers ERROR_DATA_EXISTS "This song already exists in this playlist", which
    // describes a playlist already in the state the caller asked for. Returning normally is the honest
    // result; throwing produced the fixed "Deezer refused this request." string for a successful outcome.
    // See DeezerGatewayException.isAlreadyInPlaylist for the measurement and for why no message is shown.
    // ⚠️ THE AMPLIFIER WAS THE MULTI-PLAYLIST SAVE: SaveToPlaylistViewModel loops per playlist and
    // emits each failure to throwFlow, so one duplicate track saved into N playlists produced N snackbars
    // and N Crashlytics reports, counted as neither saved nor skipped. With this catch the loop's own
    // `true` outcome stands, so it now counts as SAVED with no host-side change needed.
    // ⚠️ THE CATCH IS ON THE PREDICATE ONLY - every other gateway error rethrows. A full playlist,
    // a revoked token or a playlist that is not editable must still surface.
    // ⚠️ NOT NARROWED TO SINGLE-TRACK ADDS, AND THE LIMIT IS WORTH KNOWING: `songs` is a batch, so
    // if ANY track in the batch already exists the gateway may refuse the whole call and the others would
    // be silently dropped rather than added. Not observed - every 1116 report was a single-track save from
    // the more-sheet - but if a "saved to playlist but only some tracks appeared" report ever arrives, this
    // is the first place to look.
    suspend fun addToPlaylist(playlist: Playlist, tracks: List<Track>) {
        try {
            deezerApi.callApi(
                method = "playlist.addSongs",
                paramsBuilder = {
                    put("playlist_id", playlist.id)
                    put("songs", buildJsonArray {
                        tracks.forEach { track ->
                            add(buildJsonArray { add(track.id); add(0) })
                        }
                    })
                }
            )
        } catch (e: DeezerGatewayException) {
            if (!e.isAlreadyInPlaylist) throw e
        }
    }

    suspend fun removeFromPlaylist(playlist: Playlist, tracks: List<Track>, indexes: List<Int>) {
        val trackIds = tracks.map { it.id }
        val ids = indexes.map { index -> trackIds[index] }

        deezerApi.callApi(
            method = "playlist.deleteSongs",
            paramsBuilder = {
                put("playlist_id", playlist.id)
                put("songs", buildJsonArray {
                    ids.forEach { id ->
                        add(buildJsonArray { add(id); add(0) })
                    }
                })
            }
        )
    }

    suspend fun createPlaylist(title: String, description: String? = ""): JsonObject {
        return deezerApi.callApi(
            method = "playlist.create",
            paramsBuilder = {
                put("title", title)
                put("description", description)
                put("songs", buildJsonArray {})
                put("status", 0)
            }
        )
    }

    suspend fun deletePlaylist(id: String) {
        deezerApi.callApi(
            method = "playlist.delete",
            paramsBuilder = {
                put("playlist_id", id)
            }
        )
    }

    suspend fun updatePlaylist(id: String, title: String, description: String? = "") {
        deezerApi.callApi(
            method = "playlist.update",
            paramsBuilder = {
                put("description", description)
                put ("playlist_id", id)
                put("status", 0)
                put("title", title)
            }
        )
    }

    suspend fun updatePlaylistOrder(playlistId: String, ids: MutableList<String>) {
        deezerApi.callApi(
            method = "playlist.updateOrder",
            paramsBuilder = {
                put("order", buildJsonArray { ids.forEach { add(it) } })
                put ("playlist_id", playlistId)
                put("position", 0)
            }
        )
    }
}