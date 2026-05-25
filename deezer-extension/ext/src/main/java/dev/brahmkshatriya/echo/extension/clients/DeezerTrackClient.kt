package dev.brahmkshatriya.echo.extension.clients

import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Streamable.Media.Companion.toMedia
import dev.brahmkshatriya.echo.common.models.Streamable.Source.Companion.toSource
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.extension.AudioStreamProvider
import dev.brahmkshatriya.echo.extension.DeezerApi
import dev.brahmkshatriya.echo.extension.DeezerExtension
import dev.brahmkshatriya.echo.extension.DeezerParser
import dev.brahmkshatriya.echo.extension.Utils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient

class DeezerTrackClient(private val deezerExtension: DeezerExtension, private val api: DeezerApi, private val parser: DeezerParser) {

    private val client: OkHttpClient get() = api.clientNP

    private fun extractUrlFromJson(json: JsonObject): String? {
        val data = json["data"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
        val media = data["media"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
        val source = media["sources"]?.jsonArray?.getOrNull(1)?.jsonObject
            ?: media["sources"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: return null
        return source["url"]?.jsonPrimitive?.content
    }

    private suspend fun createStreamableForQuality(track: Track, quality: String, retry: Boolean = true): Streamable {
        return try {
            val currentTrackId = track.id
            val mediaJson =
                if (quality != "128" && quality != "mp3") api.getMediaUrl(track, quality)
                else api.getMP3MediaUrl(track, quality == "128")
            val mjString = mediaJson.toString()
            if (mjString.contains("License token has no sufficient rights on requested media")) return when (quality) {
                "flac" -> createStreamableForQuality(track, "320", retry)
                "320" -> createStreamableForQuality(track, "128", retry)
                else -> throw Exception("Track not available on server")
            }
            val trackJsonData = mediaJson["data"]?.jsonArray?.firstOrNull()?.jsonObject
            val mediaIsEmpty = trackJsonData?.get("media")?.jsonArray?.isEmpty() == true

            val (finalUrl, fallbackTrack) = when {
                mjString.contains("Track token has no sufficient rights on requested media") || mediaIsEmpty -> {
                    val t0 = System.currentTimeMillis()
                    println("GladixPlayback: TRACK_TOKEN stale for ${track.id} quality=$quality mediaIsEmpty=$mediaIsEmpty — fetching fallback")
                    val fallBackId = track.extras["FALLBACK_ID"].orEmpty()
                    if (quality == "128") {
                        val fallbackObject = api.track(fallBackId)
                        val resultOj = fallbackObject["results"]?.jsonObject!!
                        val fallBackTrack = parser.run { resultOj.toTrack() }
                        val fbMediaJson = api.getMP3MediaUrl(fallBackTrack, true)
                        val url = extractUrlFromJson(fbMediaJson)!!
                        println("GladixPlayback: TRACK_TOKEN fallback resolved for ${track.id} in ${System.currentTimeMillis() - t0}ms fallbackId=$fallBackId")
                        url to fallBackTrack
                    } else {
                        val fallbackParsed = track.copy(id = fallBackId)
                        val fallbackMediaJson = api.getMediaUrl(fallbackParsed, quality)
                        val url = extractUrlFromJson(fallbackMediaJson)!!
                        println("GladixPlayback: TRACK_TOKEN fallback resolved for ${track.id} in ${System.currentTimeMillis() - t0}ms fallbackId=$fallBackId")
                        url to fallbackParsed
                    }
                }

                mjString.contains("An error occurred while decoding track token") -> {
                    val fallbackObject = api.track(currentTrackId)
                    val resultOj = fallbackObject["results"]?.jsonObject!!
                    val fallBackTrack = parser.run { resultOj.toTrack() }
                    val fbMediaJson = api.getMP3MediaUrl(fallBackTrack, true)
                    val url = extractUrlFromJson(fbMediaJson)!!
                    url to fallBackTrack
                }

                else -> {
                    val url = extractUrlFromJson(mediaJson)!!
                    url to null
                }
            }

            val qualityValue = when (quality) {
                "flac" -> 9
                "320" -> 6
                "128" -> 3
                else -> 0
            }
            val qualityTitle = when (quality) {
                "flac" -> "FLAC"
                "320" -> "320kbps"
                "128" -> "128kbps"
                else -> "UNKNOWN"
            }
            val keySourceId = fallbackTrack?.id ?: currentTrackId

            Streamable.server(
                id = finalUrl,
                quality = qualityValue,
                title = qualityTitle,
                extras = mapOf("key" to Utils.createBlowfishKey(keySourceId))
            )
        } catch (e: Exception) {
            if (e.message?.contains("Song not available") == true) {
                if (retry) {
                    try {
                        deezerExtension.handleArlExpiration()
                        return createStreamableForQuality(track, quality, false)
                    } catch (retryEx: Exception) {
                        // ignore and proceed to quality fallback or final error
                    }
                }
            }

            when (quality) {
                "flac" -> createStreamableForQuality(track, "320", retry)
                "320" -> createStreamableForQuality(track, "128", retry)
                else -> throw Exception("Track not available on server")
            }
        }
    }

    suspend fun loadStreamableMedia(streamable: Streamable): Streamable.Media {
        deezerExtension.handleArlExpiration()
        val resolvedStreamable = if (streamable.id.startsWith(placeholderPrefix)) {
            val info = streamable.id.removePrefix(placeholderPrefix).split(":")
            val trackId = info[0]
            val quality = info.getOrNull(1) ?: "128"
            val newTrack = Track(
                id = trackId,
                title = quality,
                extras = mapOf(
                    "TRACK_TOKEN" to streamable.extras["TRACK_TOKEN"].orEmpty(),
                    "FALLBACK_ID" to streamable.extras["FALLBACK_ID"].orEmpty()
                )
            )
            var resolved: Streamable? = null
            for (attempt in 0..1) {
                if (attempt > 0) delay(2000L)
                try {
                    resolved = createStreamableForQuality(newTrack, quality)
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // retry on next attempt
                }
            }
            resolved ?: throw Exception("Track not available after retries: $trackId")
        } else {
            streamable
        }

        return if (resolvedStreamable.quality == 12) {
            resolvedStreamable.id.toSource().toMedia()
        } else {
            Streamable.InputProvider { start, _ ->
                val contentLength = Utils.getContentLength(resolvedStreamable.id, client)
                Pair(
                    AudioStreamProvider.openStream(resolvedStreamable, client, start),
                    contentLength - start
                )
            }.toSource(id = resolvedStreamable.id).toMedia()
        }
    }

    private val qualityOptions = listOf("flac", "320", "128")

    suspend fun loadTrack(track: Track): Track {
        deezerExtension.handleArlExpiration()

        if (track.type == Track.Type.Podcast) {
            return track
        }

        val isMp3Misc = track.extras["FILESIZE_MP3_MISC"]?.let { it != "0" } ?: false

        val streamables = if (isMp3Misc) {
            listOf(
                Streamable.server(
                    id = "$placeholderPrefix${track.id}:mp3",
                    quality = 0,
                    title = "MP3",
                    extras = mapOf(
                        "TRACK_TOKEN" to track.extras["TRACK_TOKEN"].orEmpty(),
                        "FALLBACK_ID" to track.extras["FALLBACK_ID"].orEmpty()
                    )
                )
            )
        } else {
            qualityOptions.map { quality ->
                val qualityValue = when (quality) {
                    "flac" -> 9
                    "320" -> 6
                    "128" -> 3
                    else -> 0
                }
                val qualityTitle = when (quality) {
                    "flac" -> "FLAC"
                    "320" -> "320kbps"
                    "128" -> "128kbps"
                    else -> "UNKNOWN"
                }
                Streamable.server(
                    id = "$placeholderPrefix${track.id}:$quality",
                    quality = qualityValue,
                    title = qualityTitle,
                    extras = mapOf(
                        "TRACK_TOKEN" to track.extras["TRACK_TOKEN"].orEmpty(),
                        "FALLBACK_ID" to track.extras["FALLBACK_ID"].orEmpty()
                    )
                )
            }
        }
        return track.copy(
            streamables = streamables
        )
    }

    private val placeholderPrefix = "dzp:"
}