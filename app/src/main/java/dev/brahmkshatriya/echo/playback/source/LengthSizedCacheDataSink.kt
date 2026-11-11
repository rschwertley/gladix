package dev.brahmkshatriya.echo.playback.source

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSink
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSink

/**
 * [2026-11-10] The main player's cache sink, writing each stream in pieces of a thirtysecond of its length, between
 * [MIN_FRAGMENT_BYTES] and [MAX_FRAGMENT_BYTES] ([MIN_FRAGMENT_BYTES] when the length is unknown). Was media3's default,
 * one 5 MB piece (CacheDataSink.DEFAULT_FRAGMENT_SIZE, media3 1.11.0).
 *
 * WHY: a piece is readable only once it is committed, when it fills or the loader closes (CacheDataSink.write and
 * closeCurrentOutputStream -> SimpleCache.commitFile), and the next song loads at playback speed once the 50 s buffer is
 * full (PlayerService.createExoplayer's LoadControl), so by the crossfade it has about 40 s loaded: ~640 KB at MP3 128,
 * never a whole 5 MB piece. The crossfade's start look-ahead (CacheOnlyTailSource.head) reads that head from the cache
 * only, never the network, so it needs the first pieces committed before the switch. 512 KB is 32 s at MP3 128; the
 * length rule keeps a 24/192 file to about 32 pieces instead of hundreds.
 *
 * Splits only where the request allows it: ProgressiveMediaPeriod sets FLAG_ALLOW_CACHE_FRAGMENTATION on every load
 * (ProgressiveMediaPeriod.buildDataSpec, 1.11.0), and CacheDataSink ignores the size without it. The length is the one
 * TeeDataSource gives the sink from the upstream's open (TeeDataSource.open), so the content length on a load from 0.
 * Existing 5 MB pieces stay valid: pieces are indexed by position, and nothing that reads the cache depends on their size.
 * Below 2 MB, every new CacheDataSink logs media3's "below the minimum recommended value" warning (accepted 2026-11-10).
 */
@OptIn(UnstableApi::class)
class LengthSizedCacheDataSink(private val cache: Cache) : DataSink {

    private var sink: CacheDataSink? = null

    override fun open(dataSpec: DataSpec) {
        sink = CacheDataSink(cache, fragmentSizeFor(dataSpec.length)).also { it.open(dataSpec) }
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        sink?.write(buffer, offset, length)
    }

    override fun close() {
        val current = sink ?: return
        sink = null
        current.close()
    }

    class Factory(private val cache: Cache) : DataSink.Factory {
        override fun createDataSink(): DataSink = LengthSizedCacheDataSink(cache)
    }

    companion object {
        const val MIN_FRAGMENT_BYTES = 512L * 1024
        const val MAX_FRAGMENT_BYTES = 4L * 1024 * 1024
        private const val PIECES = 32

        fun fragmentSizeFor(length: Long): Long =
            if (length == C.LENGTH_UNSET.toLong() || length <= 0) MIN_FRAGMENT_BYTES
            else (length / PIECES).coerceIn(MIN_FRAGMENT_BYTES, MAX_FRAGMENT_BYTES)
    }
}
