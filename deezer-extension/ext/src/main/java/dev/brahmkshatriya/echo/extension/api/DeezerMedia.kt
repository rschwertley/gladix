package dev.brahmkshatriya.echo.extension.api

import dev.brahmkshatriya.echo.common.helpers.ContinuationCallback.Companion.await
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.extension.DeezerApi
import dev.brahmkshatriya.echo.extension.DeezerSession
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class DeezerMedia(
    private val deezerApi: DeezerApi,
    private val session: DeezerSession,
    private val clientNP: OkHttpClient,
) {

    suspend fun getMP3MediaUrl(track: Track, arl: String, sid: String, licenseToken: String, is128: Boolean): JsonObject {
        val headers = Headers.Builder().apply {
            //add("Accept-Encoding", "gzip")
            add("Accept-Language", deezerApi.langCode)
            add("Cache-Control", "max-age=0")
            add("Connection", "Keep-alive")
            add("Content-Type", "application/json; charset=utf-8")
            add("Cookie", "arl=$arl&sid=$sid")
            add("Host", "media.deezer.com")
            add(
                "User-Agent",
                "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/79.0.3945.130 Safari/537.36"
            )
        }.build()

        val requestBody = deezerApi.encodeJson {
            put("license_token", licenseToken)
            putJsonArray("media") {
                add(buildJsonObject {
                    put("type", "FULL")
                    putJsonArray("formats") {
                        add(buildJsonObject {
                            put("cipher", "BF_CBC_STRIPE")
                            put("format", if (!is128) "MP3_MISC" else "MP3_128")
                        })
                    }
                })
            }
            putJsonArray("track_tokens") { add(track.extras["TRACK_TOKEN"]) }
        }.toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url("https://media.deezer.com/v1/get_url")
            .post(requestBody)
            .headers(headers)
            .build()

        val response = clientNP.newCall(request).await()
        val responseBody = response.body.string()

        if (responseBody.contains("Song not available")) {
            throw Exception("Song not available")
        }
        return deezerApi.decodeJson(responseBody)
    }

    /**
     * ⚠⚠ HIFI IS ASKED FOR INSIDE THE flac RUNG, NOT AS A RUNG OF ITS OWN,
     * AND THAT IS WHAT KEEPS THE QUALITY LADDER UNTOUCHED. One request carries both formats and
     * Deezer answers with the best it has for that song, so DeezerTrackClient's ladder still sees
     * flac -> 320 -> 128, topQuality still has three values, and the substitute ladder's
     * re-entry at topQuality needs no new case. Adapted from upstream
     * (LuftVerbot/echo-deezer-extension, 865463c); the rest of that commit is deliberately not
     * taken.
     * ⚠️ WHAT THE RELAY WAS MEASURED TO DO, 2026-10-24, AND WHAT WAS NOT
     * SHOWN. The formats list is the RELAY's contract, not Deezer's, so it was probed directly:
     * it ACCEPTS ["FLAC_24","FLAC"] and DEGRADES TO FLAC - a normal FLAC source came back - and
     * ["FLAC_24"] alone answers {"data":[{"media":[]}]}, so an unavailable format is an EMPTY
     * MEDIA ARRAY rather than a refusal or an error. NOT SHOWN: a FLAC_24 source. None was
     * served across 8 ids (2 known + the 6 current chart tracks), which is consistent with
     * upstream's own "not all songs have it" and with the relay account possibly not holding
     * HiFi rights. So this may be inert until Deezer serves one - the cost of asking is one
     * extra token in a list and the same single request.
     * ⚠️ THE EMPTY SHAPE NEEDS NO GUARD: DeezerTrackClient's mediaIsEmpty
     * branch already treats it as "step down on the same track first" (the Oct 2026
     * acoustic-substitution report). Unreachable through this path anyway, since FLAC is always
     * asked for alongside.
     * ⚠️ `!= false` RATHER THAN `== true`, AND UPSTREAM HAS THIS WRONG. A
     * declared SettingSwitch default is NOT written to prefs until the user touches the switch -
     * ExtensionUtils.toSettings returns null for an absent key - so `== true` would leave the
     * feature OFF despite the switch reading ON, which is the opposite of the declared default.
     */
    private fun hiFiWanted(): Boolean {
        val settings = session.settings ?: return false
        // ⚠️ TWO KEYS, AND THE SECOND ONE IS NOT THE USER'S. `flac_24` is the
        // switch; `flac_24_cast_off` is written by the APP while a Cast session is live (see
        // CastSessionController.setHiFiSuppressed) because the Default Media Receiver is
        // documented only to 24-bit/96 kHz while a HiFi master can be 192 kHz, and this module
        // cannot see CastState - it has no Android Context at all. Suppression is a SEPARATE key
        // so the user's own setting is never written to, and so turning the switch on mid-cast
        // still does the right thing.
        if (settings.getBoolean("flac_24_cast_off") == true) return false
        return settings.getBoolean("flac_24") != false
    }

    /**
     * [TEMPORARY 2026-10-27] perishable-cache probe - removal condition at the app's
     * Cached.logPreferCache. The relay's HTTP status for the LAST getMediaUrl call per track id, read by
     * DeezerTrackClient's `deezer rung` line for via=relay rungs (http=). Recorded here rather than
     * returned, so getMediaUrl's return type and exceptions - which the ladder branches on - are
     * unchanged. Cleared before each call, so a call that never got a response (connect failure, timeout)
     * reads as absent rather than as the previous call's status. Keyed by id only: two concurrent relay
     * calls for the SAME id could cross, which a capture can tell from the line order. Unbounded but tiny
     * (one Int per distinct id) and it leaves with the probe.
     * ⚠️ THE THEORY THIS SERVES, AND THE OBSERVATION AGAINST IT. Theory (2026-10-27): this shared relay
     * refuses intermittently (third-party, shared account, ~1,000/day threshold), the ladder steps down to
     * 128 on the user's own account, and a failure there skips silently - timing alone would then explain
     * Maria failing on tap but playing via the header Shuffle minutes later (Oct 26), Baby Beluga playing
     * mid-capture (Oct 26), and the long-press Radio "cure" (1151). It needs the 128 step to fail for a
     * NON-TOKEN reason, because a stale track token there is re-fetched by refreshTokenAndRetry: i.e. the
     * licence-token refusal or "Song not available" from media.deezer.com.
     * AGAINST IT, user-observed 2026-10-27: both songs play in Deezer's own app on the same FREE account,
     * so the account itself holds rights to them. That rules out "the account cannot serve them at 128".
     * It does NOT rule out OUR SESSION's licence token being stale or refused (a session credential,
     * which Deezer's app refreshes itself and api.track does not), and it says nothing about the relay
     * step. Read this line together with the q=128 line that follows it: a relay refusal followed by
     * `refusal=licence` keeps the theory alive as a session-licence problem; a relay refusal followed
     * by `refusal=none` means the relay alone never skips a song.
     */
    val lastRelayStatus = java.util.concurrent.ConcurrentHashMap<String, Int>()

    suspend fun getMediaUrl(track: Track, quality: String): JsonObject {
        lastRelayStatus.remove(track.id)
        val formats = when (quality) {
            "128" -> arrayOf("MP3_128")
            "flac" -> if (hiFiWanted()) arrayOf("FLAC_24", "FLAC") else arrayOf("FLAC")
            else -> arrayOf("MP3_320")
        }

        val requestBody = deezerApi.encodeJson {
            put("formats", buildJsonArray { formats.forEach { add(it) } })
            put("ids", buildJsonArray { add(track.id.toLong()) })
        }.toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url("https://lufts-dzmedia.fly.dev/get_url")
            .post(requestBody)
            .build()

        val response = clientNP.newCall(request).await()
        lastRelayStatus[track.id] = response.code
        val responseBody = response.body.string()

        if (responseBody.contains("Song not available")) {
            throw Exception("Song not available")
        }
        return deezerApi.decodeJson(responseBody)
    }
}