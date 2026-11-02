package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.helpers.ContinuationCallback.Companion.await
import dev.brahmkshatriya.echo.common.models.Streamable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.internal.closeQuietly
import java.io.IOException
import java.io.InputStream
import java.util.ArrayDeque

/**
 * ⚠⚠ PORTED FROM UPSTREAM 43998ca (LuftVerbot/echo-deezer-extension, 2026-10-27), ADAPTED (2026-11-02). Three parts
 * taken, the rest deliberately not:
 * - THE BUFFER POOL IS ATOMIC. Ours was a Collections.synchronizedList read with removeFirstOrNull(), which is
 *   isEmpty() then removeAt(0) - two separately locked calls - so two streams opening at once could race for the
 *   last pooled buffer and throw IndexOutOfBoundsException. Opens do overlap: the main player's load, preload, the
 *   crossfade sidecar's hole fetches (CacheOnlyTailSource is cache-FIRST, its holes go upstream through
 *   RawDataSource to here) and cast materialising.
 * - THE RANGE RESPONSE IS VALIDATED ([validateRangeResponse]). A server that ignored Range after a seek used to be
 *   decrypted from the wrong stripe and played as noise with no error; it is now an IOException, which every
 *   consumer already treats as a network failure: the main player retries it, the crossfade sidecar aborts the
 *   overlap (CrossfadeController's TAIL_DIED) and the main track plays on.
 * - DECRYPTION IS PER STRIPE, NOT PER READ ([StripeDecryptingInputStream]). The old FilterInputStream decrypted
 *   correctly only for reads of at least one whole 2048-byte stripe, and its skip() and single-byte read() went to
 *   the still-encrypted bytes. Neither could happen through RawDataSource (it calls read(buf, off, len) only, and
 *   the BufferedInputStream around it always asked for a full buffer), so this removes a trap, not a live bug; it
 *   also lets the BufferedInputStream go, one copy fewer.
 * NOT TAKEN: upstream's blocking execute() - our await() (ce917c18) stays; rounding endByte to a stripe end (no
 * caller passes endByte; the InputProvider in DeezerTrackClient.loadStreamableMedia passes only the start); a
 * mandatory Blowfish key (every encrypted streamable carries one, DeezerTrackClient's Streamable.server extras) and
 * the Cache-Control header rename (the extension has no OkHttp cache). Episodes (quality 12) never come here.
 */
object AudioStreamProvider {

    private const val BLOCK_SIZE = 2048
    private const val BUFFER_POOL_LIMIT = 10

    private val bufferPool = ArrayDeque<ByteArray>()
    private val poolLock = Any()

    private fun getBuffer(): ByteArray =
        synchronized(poolLock) { bufferPool.pollFirst() } ?: ByteArray(BLOCK_SIZE)

    private fun returnBuffer(buffer: ByteArray) {
        if (buffer.size != BLOCK_SIZE) return
        synchronized(poolLock) {
            if (bufferPool.size < BUFFER_POOL_LIMIT) bufferPool.addLast(buffer)
        }
    }

    suspend fun openStream(
        streamable: Streamable,
        client: OkHttpClient,
        startByte: Long = 0,
        endByte: Long = -1
    ): InputStream = withContext(Dispatchers.IO) {
        val alignedStart = startByte - (startByte % BLOCK_SIZE)

        val rangeHeader = if (endByte < 0) {
            "bytes=$alignedStart-"
        } else {
            "bytes=$alignedStart-$endByte"
        }

        val url = streamable.id
        val key = streamable.extras["key"] ?: ""

        val request = Request.Builder()
            .url(url)
            .header("Range", rangeHeader)
            .header("cache", "no-store")
            .build()

        val response = client.newCall(request).await()
        try {
            if (!response.isSuccessful) {
                throw IllegalStateException("Failed to fetch audio: HTTP ${response.code}")
            }
            validateRangeResponse(response, alignedStart)
            val decrypted = StripeDecryptingInputStream(
                source = response.body.byteStream(),
                key = key,
                initialChunkIndex = alignedStart / BLOCK_SIZE,
                onClose = { response.close() },
            )
            RangeSlicingInputStream(decrypted, skipBytes = startByte - alignedStart)
        } catch (e: Exception) {
            response.closeQuietly()
            throw e
        }
    }

    // 206 must start exactly at the aligned stripe; any other success code is only right from byte 0.
    private fun validateRangeResponse(response: Response, alignedStart: Long) {
        if (response.code == 206) {
            val contentRange = response.header("Content-Range")
                ?: throw IOException("HTTP 206 response is missing Content-Range")
            if (!contentRange.startsWith("bytes $alignedStart-")) {
                throw IOException("Unexpected Content-Range: $contentRange; expected start at $alignedStart")
            }
        } else if (alignedStart != 0L) {
            throw IOException("Server ignored the byte-range request: HTTP ${response.code}, start=$alignedStart")
        }
    }

    // Reads one whole stripe from the network at a time and hands it out in any read size. Every third full
    // stripe, counted from the start of the FILE, is Blowfish-encrypted; a short final stripe never is.
    private class StripeDecryptingInputStream(
        private val source: InputStream,
        private val key: String,
        initialChunkIndex: Long,
        private val onClose: () -> Unit,
    ) : InputStream() {
        private var chunkIndex = initialChunkIndex
        private val inputBuffer = getBuffer()
        private var outputBuffer: ByteArray? = null
        private var outputPosition = 0
        private var outputLimit = 0
        private var eof = false
        private var closed = false

        private fun loadNextStripe(): Boolean {
            var total = 0
            while (total < BLOCK_SIZE) {
                val count = source.read(inputBuffer, total, BLOCK_SIZE - total)
                if (count < 0) break
                if (count == 0) {
                    val single = source.read()
                    if (single < 0) break
                    inputBuffer[total++] = single.toByte()
                } else total += count
            }
            if (total == 0) {
                eof = true
                return false
            }
            outputPosition = 0
            outputLimit = total
            if (total == BLOCK_SIZE) {
                outputBuffer = if (chunkIndex % 3L == 0L) Utils.decryptBlowfish(inputBuffer, key) else inputBuffer
                chunkIndex++
            } else {
                outputBuffer = inputBuffer
                eof = true
            }
            return true
        }

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (closed) throw IOException("Stream is closed")
            if (offset < 0 || length < 0 || offset > buffer.size - length) throw IndexOutOfBoundsException()
            if (length == 0) return 0
            if (outputPosition >= outputLimit && (eof || !loadNextStripe())) return -1
            val current = outputBuffer ?: throw IOException("Missing stripe output")
            val count = minOf(length, outputLimit - outputPosition)
            System.arraycopy(current, outputPosition, buffer, offset, count)
            outputPosition += count
            return count
        }

        // Closed once: the pooled buffer must never be returned twice, or two streams would share it.
        override fun close() {
            if (closed) return
            closed = true
            try {
                source.close()
            } finally {
                try {
                    onClose()
                } finally {
                    returnBuffer(inputBuffer)
                }
            }
        }
    }

    // Drops the bytes between the aligned stripe start and the requested start, then passes the rest through.
    private class RangeSlicingInputStream(
        private val source: InputStream,
        private var skipBytes: Long,
    ) : InputStream() {
        private var closed = false
        private val discardBuffer = ByteArray(BLOCK_SIZE)

        private fun discardPrefix() {
            while (skipBytes > 0) {
                val count = source.read(discardBuffer, 0, minOf(skipBytes, discardBuffer.size.toLong()).toInt())
                if (count < 0) throw IOException("Unexpected EOF while seeking to requested byte")
                skipBytes -= count
            }
        }

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (closed) throw IOException("Stream is closed")
            if (offset < 0 || length < 0 || offset > buffer.size - length) throw IndexOutOfBoundsException()
            if (length == 0) return 0
            discardPrefix()
            return source.read(buffer, offset, length)
        }

        override fun close() {
            if (closed) return
            closed = true
            source.close()
        }
    }
}
