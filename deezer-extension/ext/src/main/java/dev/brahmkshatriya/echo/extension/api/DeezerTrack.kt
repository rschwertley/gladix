package dev.brahmkshatriya.echo.extension.api

import dev.brahmkshatriya.echo.extension.DeezerApi
import dev.brahmkshatriya.echo.extension.DeezerGatewayException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add

class DeezerTrack(private val deezerApi: DeezerApi) {

    suspend fun track(id: String): JsonObject {
        return deezerApi.callApi(
            method = "deezer.pageTrack",
            paramsBuilder = {
                put("sng_id", id)
            },
            np = true
        )
    }

    /**
     * Full gateway records for a list of track ids, chunked. Response is `results.data[]` per call, the
     * SAME shape playlist.getSongs returns, so DeezerParser.toTrack consumes it unchanged.
     *
     * ⚠️ PARAMETER IS `SNG_IDS`, UPPERCASE, AND THE PROJECT RECORD CONTRADICTS ITSELF ON THIS.
     * One summary records the open-source verification as lowercase `{"sng_ids":[...]}`; a later one
     * records the shipped call as uppercase SNG_IDS. NEITHER could be checked against this tree, because
     * NO song.getListData CALL EXISTED HERE AT ALL before 2026-09-07 — the "shipped re-resolve" both
     * summaries describe is not in the code. Settled by reading the source again (deezer-py, gw.py):
     *     def get_tracks(self, sng_ids):
     *         body = self.api_call('song.getListData', {'SNG_IDS': sng_ids})
     * UPPERCASE, ids passed as a list, response read at data[] under results. Use that.
     *
     * ⚠️ AND DO NOT LEAN ON "gw-light PARAMS ARE CASE-INSENSITIVE" — NOBODY VERIFIED IT. This file is
     * itself mixed (deezer.pageTrack takes lowercase `sng_id`; favorite_song.add/remove take uppercase
     * `SNG_ID`) and both work — but that is equally consistent with each METHOD having its own fixed
     * casing, which is the likelier reading. Match the verified casing per method; do not generalise.
     *
     * ⚠️ THE CHUNK SIZE IS STILL UNVERIFIED — BUT IT IS NOT ARBITRARY. "Safe batch limit to be
     * verified" was left open by an earlier session and is still open: deezer-py sends the WHOLE list in
     * one call with no slicing, so there is no known upper bound and no evidence one exists. Nothing here
     * probed the ceiling either, and this chunking is a NO-OP for the case it was built for (a
     * smarttracklist is ~40 tracks — one call), so the multi-chunk path ships untested in anger. If a large
     * list ever fails, this number is the first suspect and the answer is a capture, not a guess.
     * WHAT IT DOES HAVE IS FIELD HISTORY: chunked(100) SHIPPED in d221f4c2 and ran until 04c67817. This is
     * the same number re-landing, not a fresh guess.
     *
     * ⚠️ AND THE HISTORY IS WHY THIS FUNCTION LOOKS NEW BUT IS NOT. A song.getListData wrapper existed
     * before: added by d221f4c2 (canonical re-resolve for the playlist wrong-artist bug) and DELETED by
     * 04c67817 when the FALLBACK graft superseded it. Two project summaries describe it as shipped and
     * NEITHER records the deletion — which is why "match the shipped call shape" had nothing in the tree to
     * match when this was rebuilt. If a summary describes code you cannot find, check whether it was
     * removed rather than assuming it never landed.
     *
     * ⚠️ THE STRONGEST EVIDENCE FOR THE GITHUB-VERIFICATION RULE IS THIS FUNCTION. Two people, a month
     * apart, with no access to each other's working notes, went to deezer-py's gw.py and came back with
     * the SAME two answers: SNG_IDS uppercase, and chunked at 100. The deleted d221f4c2 version carried
     * the comment "SNG_IDS is UPPERCASE (confirmed vs deezer-py)"; this one was derived the same way after
     * the summaries were found to contradict each other. Same source, same answer, twice — while four
     * traffic-capture attempts on the official app have produced nothing. Read the source.
     */
    suspend fun getListData(ids: List<String>): List<JsonObject> =
        ids.chunked(GET_LIST_DATA_CHUNK).map { chunk ->
            deezerApi.callApi(
                method = "song.getListData",
                paramsBuilder = {
                    putJsonArray("SNG_IDS") { chunk.forEach { add(it) } }
                },
                np = true
            )
        }

    suspend fun getTracks(userId: String): JsonObject {
        return deezerApi.callApi(
            method = "favorite_song.getList",
            paramsBuilder = {
                put("user_id", userId)
                put("tab", "loved")
                put("nb", 10000)
                put("start", 0)
            },
            np = true
        )
    }

    /**
     * ⚠⚠ ALREADY LIKED IS SUCCESS, AND THE CALLER MUST SEE IT THAT WAY. The
     * gateway answers ERROR_DATA_EXISTS when the song is already in Loved Tracks, which is the
     * state the caller asked for - so swallowing it here lets likeItem return normally, and the
     * HOST then does what it does on success: PlayerCallback.applyRating writes the metadata
     * rating AND Cached.setTrackLiked, so the heart ends up correct instead of a snackbar plus a
     * report plus a heart still showing the stale value. See
     * DeezerGatewayException.isAlreadyFavorite for the 1144 report this comes from.
     * ⚠️ THE SESSION SET NEEDS NOTHING, AND THAT IS WORTH CHECKING RATHER
     * THAN ASSUMING. DeezerExtension.likeItem adds to likedTrackIds BEFORE this call, so on the
     * swallowed path the set already says liked - which is exactly right. (The inverse is a
     * pre-existing gap and is not touched here: on a GENUINE failure the set keeps the
     * optimistic value. isItemLiked would then lie until the next fetchLikedTrackIds - renamed fetchLiked 2026-10-30.)
     * ⚠️ HERE RATHER THAN IN likeItem, so saveToLibrary's Track branch - the
     * other caller of this method, with the same meaning - gets it too. Only the SONG add is
     * wrapped; album/playlist/show favourites are left exactly as they are, because none of them
     * has produced this report and the narrow-match rule applies to call sites as well as codes.
     */
    suspend fun addFavoriteTrack(id: String) {
        try {
            deezerApi.callApi(
                method = "favorite_song.add",
                paramsBuilder = {
                    put("SNG_ID", id)
                }
            )
        } catch (e: DeezerGatewayException) {
            if (!e.isAlreadyFavorite) throw e
        }
    }

    /**
     * ⚠⚠ THE MIRROR CASE IS UNHANDLED BECAUSE THE CODE IS UNKNOWN, NOT
     * BECAUSE IT CANNOT HAPPEN. Unliking a song Deezer does not have liked is the same shape as
     * the add above (a song unliked in the Deezer app after our media-state was cached), and the
     * honest answer would be the same: the state the caller asked for already holds.
     * ⚠️ WHAT WAS SEARCHED, 2026-10-24, SO IT IS NOT REPEATED: d-fi
     * (d-fi-core/src/lib/request.ts) handles only NEED_API_AUTH_REQUIRED, code 4, GATEWAY_ERROR
     * and VALID_TOKEN_REQUIRED; refreezer (lib/api/deezer.dart:331) calls favorite_song.remove
     * with NO error handling at all; deezer-py's gw.py was not reachable at the paths tried.
     * No open-source client handles a failed favourite REMOVE, which is consistent with clients
     * only removing what they just listed. GUESSING A CODE HERE WOULD BE THE EXACT THING
     * [DeezerGatewayException.isEmptyTracklist]'s note forbids: widen on an observed string,
     * never pre-emptively.
     * ⚠️ THE INSTRUMENT ALREADY EXISTS, WHICH IS WHY THIS IS A WAIT AND NOT
     * A TODO. App.kt's deezer_gateway key writes method AND errorText for every
     * DeezerGatewayException - it is what produced the 1144 report - so the first real occurrence
     * arrives as `gw=favorite_song.remove err=<the code>`. Add the mirror test then, against that
     * string.
     */
    suspend fun removeFavoriteTrack(id: String) {
        deezerApi.callApi(
            method = "favorite_song.remove",
            paramsBuilder = {
                put("SNG_ID", id)
            }
        )
    }

    companion object {
        // See getListData's note: self-imposed, not a discovered limit.
        private const val GET_LIST_DATA_CHUNK = 100
    }
}