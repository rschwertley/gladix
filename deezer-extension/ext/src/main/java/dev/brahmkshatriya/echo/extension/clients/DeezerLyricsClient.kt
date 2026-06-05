package dev.brahmkshatriya.echo.extension.clients

import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.Lyrics
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.extension.DeezerApi
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class DeezerLyricsClient(private val api: DeezerApi) {

    fun searchTrackLyrics(track: Track): Feed<Lyrics> = PagedData.Single {
        try {
            val jsonObject = api.lyrics(track.id)
            val dataObject = jsonObject["data"]!!.jsonObject
            val trackObject = dataObject["track"]!!.jsonObject
            val lyricsObject = trackObject["lyrics"]!!.jsonObject
            val lyricsId = lyricsObject["id"]?.jsonPrimitive?.content ?: ""
            val lyrics = if (lyricsObject["synchronizedLines"] != JsonNull) {
                val linesArray = lyricsObject["synchronizedLines"]!!.jsonArray
                val lyrics = linesArray.map { lineObj ->
                    val line = lineObj.jsonObject["line"]?.jsonPrimitive?.content ?: ""
                    val start = lineObj.jsonObject["milliseconds"]?.jsonPrimitive?.int ?: 0
                    val end = lineObj.jsonObject["duration"]?.jsonPrimitive?.int ?: 0
                    Lyrics.Item(line, start.toLong(), start.toLong() + end.toLong())
                }
                Lyrics.Timed(lyrics)
            } else {
                val lyricsText = lyricsObject["text"]!!.jsonPrimitive.content
                Lyrics.Simple(lyricsText)
            }
            val writers = lyricsObject["writers"]
                ?.takeIf { it != JsonNull }
                ?.jsonPrimitive?.content
                ?.takeIf { it.isNotEmpty() }
            val extras = if (writers != null) mapOf("writers" to writers) else emptyMap()
            listOf(Lyrics(lyricsId, track.title, lyrics = lyrics, extras = extras))
        } catch (e: Exception) {
            emptyList()
        }
    }.toFeed()
}