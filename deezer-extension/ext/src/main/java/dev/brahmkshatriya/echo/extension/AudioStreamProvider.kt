package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.models.Streamable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.internal.closeQuietly
import java.io.IOException
import java.io.InputStream
import java.util.ArrayDeque
import kotlin.coroutines.resumeWithException

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
 * [CORRECTED 2026-11-06] "our await() stays": the opener's is now [awaitHeaders], still asynchronous, so it can close a lost response.
 */
object AudioStreamProvider {

    private const val BLOCK_SIZE = 2048
    private const val BUFFER_POOL_LIMIT = 10
    private const val OPEN_CEILING_MS = 30_000L

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

    /**
     * The stream and the file's whole size, from one GET (2026-11-06). The size was a HEAD before it, a second request on every open:
     * 221 and 348 ms for the pair on 1170. Utils.getContentLength is now the fallback, for a response that does not state the total.
     * ⚠️ THE TOTAL FROM THE CDN IS NOT VERIFIED AGAINST ITS HEAD: no capture has compared them. RawDataSource's "stream ready total="
     * line shows it per open.
     */
    suspend fun openStreamWithTotal(
        streamable: Streamable,
        client: OkHttpClient,
        startByte: Long = 0,
        endByte: Long = -1
    ): Pair<InputStream, Long> = withContext(Dispatchers.IO) {
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

        val response = awaitHeaders(client.newCall(request))
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
            val stream = RangeSlicingInputStream(decrypted, skipBytes = startByte - alignedStart)
            try {
                stream to (response.totalBytes() ?: Utils.getContentLength(url, client))
            } catch (e: Exception) {
                // The whole stream, not just the response: this also returns its pooled buffer.
                stream.closeQuietly()
                throw e
            }
        } catch (e: Exception) {
            response.closeQuietly()
            throw e
        }
    }

    // A 206's Content-Length is only the requested part. Null for "*" or a missing header: the HEAD answers instead.
    private fun Response.totalBytes(): Long? =
        if (code == 206) header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
        else header("Content-Length")?.toLongOrNull()

    /**
     * Up to the response headers, under the ceiling that was Utils.getContentLength's. The body read is outside it and takes minutes.
     * ⚠⚠ 30 s MUST STAY ABOVE OKHTTP'S connect 15 s + read 10 s, FOR THE REASON AT Utils.getContentLength: okhttp's own timeout has to
     * fire first, because its degraded ping is what evicts a dead HTTP/2 connection. With the HEAD gone from a normal open, this GET
     * is the request that does that job (dbe877a9, 2026-10-03).
     * ⚠️ A RESPONSE CAN BE LOST IN TWO PLACES, AND EACH HAS ITS OWN CLOSE:
     * - resuming a continuation that was cancelled meanwhile drops the value (prompt cancellation, suspendCancellableCoroutine's
     *   docs, read in 1.9.0's sources; the project is on 1.11.0, whose jar has the same resume overload). Hence resume's onCancellation,
     *   and hence not :common's Call.await, which has none.
     * - withTimeout can throw after its block returned ("Asynchronous timeout and resources" in its docs). Hence the var outside it.
     */
    private suspend fun awaitHeaders(call: Call): Response {
        var response: Response? = null
        try {
            withTimeout(OPEN_CEILING_MS) {
                response = suspendCancellableCoroutine { continuation ->
                    call.enqueue(object : Callback {
                        override fun onResponse(call: Call, response: Response) {
                            continuation.resume(response) { _, lost, _ -> lost.closeQuietly() }
                        }

                        override fun onFailure(call: Call, e: IOException) {
                            if (!call.isCanceled()) continuation.resumeWithException(e)
                        }
                    })
                    continuation.invokeOnCancellation { call.cancel() }
                }
            }
            return response!!
        } catch (e: Exception) {
            response?.closeQuietly()
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
