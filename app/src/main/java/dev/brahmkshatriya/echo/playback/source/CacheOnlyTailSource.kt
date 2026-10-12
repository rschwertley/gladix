package dev.brahmkshatriya.echo.playback.source

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import android.content.Context as AndroidContext
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.playback.MediaItemUtils.serverIndex
import dev.brahmkshatriya.echo.playback.source.StreamableDataSource.Companion.uri

/**
 * The crossfade sidecar's media source and its readiness gate.
 *
 * ⚠⚠ CACHE-ONLY ON PURPOSE, AND "NO UPSTREAM" IS THE WHOLE DESIGN. The sidecar replays the
 * TAIL of a track the main player is already streaming, so every byte it needs has already been
 * fetched and decrypted once. Giving it an upstream would mean a SECOND stream open per crossfade -
 * for Deezer that is RawDataSource.open's runBlocking { HEAD getContentLength + ranged GET +
 * Blowfish prime } on the loader thread, against a shared-account CDN. Reading committed cache
 * bytes costs none of that.
 * ⚠️ AND IT FAILS IN THE RECOVERABLE SHAPE. media3 substitutes PlaceholderDataSource when no
 * upstream factory is set (CacheDataSource.java:558, 1.11.0), whose open() throws
 * IOException("PlaceholderDataSource cannot be opened") - a retriable load error to media3's Loader,
 * NOT the non-IOException class that escapes uncaught (see the note at RawDataSource.open's generic
 * catch). A cache miss therefore degrades; it does not crash.
 * ⚠️ READ-ONLY TOO (no write sink), so the sidecar can never become a second writer on a key the
 * main player's CacheDataSink is already writing. SimpleCache is single-writer per key.
 *
 * ⚠⚠ THE RESOLVER IS SHARED WITH THE MAIN PLAYER'S CHAIN BY CONSTRUCTION - it is handed the
 * SAME `state.servers` map. That is what makes the sidecar resolve to the identical URL, which is
 * what makes the cache key identical: the default key is the DataSpec uri (CacheKeyFactory.DEFAULT),
 * and [StreamableDataSource.uri] derives it from the resolved source - the signed CDN URL for Http,
 * "raw://<id.hashCode()>" for Raw. A SECOND resolve would mint a different signed URL, hence a
 * different key, hence a guaranteed cache miss. Never resolve for the sidecar.
 */
@OptIn(UnstableApi::class)
class CacheOnlyTailSource(
    context: AndroidContext,
    private val cache: SimpleCache,
    private val servers: MutableMap<String, Result<Streamable.Media.Server>>,
) {

    private val resolver = StreamableResolver(context, servers)

    /**
     * Progressive only, deliberately: the tail is a byte range of an already-open container.
     * Dash/Hls factories are not wired because a sidecar never plays a manifest-based stream - those
     * arrive as Http sources with their own chunk sources, and the gate below rejects them anyway
     * (no single whole-resource length to confirm).
     */
    val factory: MediaSource.Factory by lazy {
        DefaultMediaSourceFactory(
            ResolvingDataSource.Factory(
                CacheDataSource.Factory()
                    .setCache(cache)
                    // No upstream and no write sink. Both omissions are load-bearing - see the note above.
                    .setCacheWriteDataSinkFactory(null),
                resolver
            )
        )
    }

    /** What the gate decided, with the two numbers it decided on. */
    data class Gate(
        val ready: Boolean,
        val reason: String,
        val cachedBytes: Long,
        val contentLength: Long,
    )

    /**
     * Is the WHOLE resource committed to the cache and readable right now?
     *
     * ⚠⚠ WHOLE RESOURCE, NOT THE TAIL RANGE, AND THAT IS NOT LAZINESS. getCachedBytes takes BYTE
     * positions while the tail is known in TIME, and a bytes<->time mapping is only exact for CBR -
     * wrong for VBR and for FLAC, which is most of the catalogue. Confirming the whole resource needs
     * no mapping at all. It is stricter than necessary and cannot produce a false READY.
     * ⚠️ IT READS ONLY COMMITTED SPANS, which is exactly the property wanted. SimpleCache
     * .getCachedBytes goes through the content index (SimpleCache.java:477 -> :467), and a span enters
     * that index in commitFile - so bytes still sitting in an open CacheDataSink fragment are
     * correctly invisible here. A fragment commits at 5 MB (CacheDataSink.DEFAULT_FRAGMENT_SIZE) or
     * when the DataSource closes, and the close happens at LOAD completion
     * (ProgressiveMediaPeriod.ExtractingLoadable.load's finally, :1356) - which for a track with less
     * than 50 s left is well before playback reaches the end.
     * ⚠️ A `file://` SOURCE ALWAYS FAILS HERE and that is correct for this build: downloads bypass
     * the cache entirely (getFactory(source) sends them to the non-cache factories), so they need a
     * direct-file tail path instead. Deliberately not built yet.
     */
    fun gate(item: MediaItem): Gate {
        val id = item.mediaId
        val index = item.serverIndex
        if (index < 0) return Gate(false, "noServerIndex", 0, C.LENGTH_UNSET.toLong())
        val source = runCatching {
            servers[id]?.getOrNull()?.sources?.getOrNull(index)
        }.getOrNull() ?: return Gate(false, "noResolvedSource", 0, C.LENGTH_UNSET.toLong())
        val key = source.uri.toString()
        val length = ContentMetadata.getContentLength(cache.getContentMetadata(key))
        if (length == C.LENGTH_UNSET.toLong()) return Gate(false, "noContentLength", 0, length)
        val cached = cache.getCachedBytes(key, 0, length)
        return if (cached == length) Gate(true, "committed", cached, length)
        else Gate(false, "incomplete", cached, length)
    }
}
