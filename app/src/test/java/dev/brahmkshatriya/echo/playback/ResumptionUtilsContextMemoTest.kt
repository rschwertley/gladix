package dev.brahmkshatriya.echo.playback

import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Radio
import dev.brahmkshatriya.echo.history.db.toSlimContext
import dev.brahmkshatriya.echo.utils.Serializer.toData
import dev.brahmkshatriya.echo.utils.Serializer.toJson
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The CONTEXTS file written through ResumptionUtils.slimContextsOf (the per-save memo, ANR 54bbb97d) must be
 * byte-identical to the per-item form it replaced, `list.map { it.context?.toSlimContext() }`, where `it.context` is
 * Bundle.context: getSerialized<EchoMediaItem?>("context")?.getOrNull().
 */
class ResumptionUtilsContextMemoTest {

    // The replaced form, item by item: decode, then slim.
    private fun perItem(raw: List<String?>): List<EchoMediaItem?> =
        raw.map { it?.toData<EchoMediaItem?>()?.getOrNull()?.toSlimContext() }

    // Each item's string is encoded separately, as MediaItemUtils.toMetaData's putSerialized("context", context) does,
    // so the copies are equal but distinct instances.
    private fun encode(item: EchoMediaItem?): String = item.toJson()

    private fun assertSameFile(raw: List<String?>) {
        val expected = perItem(raw).toJson()
        val actual = ResumptionUtils.slimContextsOf(raw).toJson()
        assertEquals(expected, actual)
    }

    private val playlist = Playlist(
        id = "pl1", title = "Big playlist", isEditable = false,
        description = "long description", subtitle = "by someone",
        extras = mapOf("k" to "v"),
    )

    @Test
    fun onePlaylistQueue() {
        assertSameFile(List(2001) { encode(playlist) })
    }

    @Test
    fun mixedQueue() {
        val album = Album(id = "al1", title = "Album", description = "d", extras = mapOf("a" to "b"))
        val radio = Radio(id = "r1", title = "Radio", subtitle = "s", extras = mapOf("radio" to "keep"))
        val raw = List(300) { i ->
            when (i % 6) {
                0 -> encode(playlist)
                1 -> encode(album)
                2 -> encode(radio)
                3 -> encode(null)          // a context-less item: the string "null"
                4 -> null                  // no "context" key at all
                else -> "{not json"        // a context that fails to decode
            }
        }
        assertSameFile(raw)
    }
}
