package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.helpers.ContinuationCallback.Companion.await
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object Utils {
    // Fuck GitHub
    private const val SECRET = "g4el58wc0zvf9na1"
    private val secretIvSpec = IvParameterSpec(ByteArray(8) { it.toByte() })
    private val keySpecCache = ConcurrentHashMap<String, SecretKeySpec>()

    private val md5Digest = ThreadLocal.withInitial { MessageDigest.getInstance("MD5") }

    private val charset = Charsets.ISO_8859_1

    private fun bitwiseXor(vararg values: Char): Char {
        return values.fold(0) { acc, char -> acc xor char.code }.toChar()
    }

    fun createBlowfishKey(trackId: String): String {
        val trackMd5Hex = trackId.toMD5()
        return buildString {
            for (i in 0 until 16) {
                append(bitwiseXor(trackMd5Hex[i], trackMd5Hex[i + 16], SECRET[i]))
            }
        }
    }

    private fun getSecretKeySpec(blowfishKey: String): SecretKeySpec {
        return keySpecCache.computeIfAbsent(blowfishKey) {
            SecretKeySpec(blowfishKey.toByteArray(charset), "Blowfish")
        }
    }

    private fun String.toMD5(): String {
        val digest = md5Digest.get()
        digest.reset()
        return digest.digest(toByteArray(charset)).joinToString("") { "%02x".format(it) }
    }

    fun decryptBlowfish(chunk: ByteArray, blowfishKey: String): ByteArray {
        val secretKeySpec = getSecretKeySpec(blowfishKey)
        val cipher = Cipher.getInstance("BLOWFISH/CBC/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, secretKeySpec, secretIvSpec)
        }
        return cipher.doFinal(chunk)
    }

    /**
     * Content-Length for a resolved stream URL, via a HEAD. Called from the InputProvider lambda in
     * DeezerTrackClient.loadStreamableMedia, i.e. inside RawDataSource.open's runBlocking - so this runs
     * ON MEDIA3's LOADER THREAD, once per upstream open.
     *
     * ⚠⚠ 30s IS A BACKSTOP, NOT A FUSE, AND IT WAS 10_000 UNTIL 2026-10-03. THE WHOLE POINT IS
     * THAT OKHTTP's OWN readTimeout MUST FIRE FIRST. Lowering this back to ~10s re-breaks dead-connection
     * recovery, silently, in a way no log line will show.
     *
     * WHY, read from okhttp 5.5.0 sources:
     *   clientNP is built with readTimeout(10s) and NO pingInterval, and okhttp's DEFAULT_PROTOCOLS
     *     (OkHttpClient.kt:1389) is [HTTP_2, HTTP_1_1] - so CDN connections are usually MULTIPLEXED.
     *   On a multiplexed connection a SocketTimeoutException does NOT poison the connection:
     *     RealConnection.trackFailure:398 only sets noNewExchanges under `!isMultiplexed ||
     *     e is ConnectionShutdownException`.
     *   What DOES evict it is okhttp's degraded ping, and its own comment at Http2Connection.kt:541-553
     *     describes exactly this case - "when a stream times out we don't know whether the problem
     *     impacts just one stream or the entire connection ... we ping the server". It is armed in ONE
     *     place: Http2Stream.StreamTimeout.timedOut() (:724-728) does closeLater(CANCEL) AND
     *     connection.sendDegradedPingLater(). The pong deadline is 1s (DEGRADED_PONG_TIMEOUT_NS), after
     *     which Http2Connection.isHealthy (:530-539) returns false and the pool drops the connection.
     *   A COROUTINE TIMEOUT REACHES NONE OF THAT. withTimeout cancels the continuation,
     *     ContinuationCallback.invoke calls Call.cancel(), and RealCall.cancel -> Exchange.cancel ->
     *     Http2ExchangeCodec.cancel (:124-127) does `stream?.closeLater(ErrorCode.CANCEL)` and NOTHING
     *     ELSE - no degraded ping. trackFailure:387-389 then explicitly permits it ("Permit any number
     *     of CANCEL errors on locally-canceled calls"). To okhttp we cancelled a HEALTHY stream, so the
     *     half-open connection stays pooled and isHealthy.
     *
     * ⚠⚠ AND AT 10_000 THIS TIMEOUT WON THE RACE EVERY TIME, STRUCTURALLY RATHER THAN BY LUCK -
     * which is why the bug was reliable. Both ceilings were 10s, but withTimeout starts when the
     * coroutine starts, BEFORE connection acquire and request write, while okhttp's readTimeout starts
     * only when the response read begins. Ours therefore expired first by however long acquire+write
     * took, every single time, suppressing the one check that would have evicted the connection.
     *
     * ⚠️ WHAT IT COST, and the evidence it rests on - NOT the Aug 24-29 "15 minutes" incident,
     * which that record attributes to the watchdog trap and which notes that switching extensions did
     * not help (a different extension is a different OkHttpClient, hence a different pool):
     *   the May 24 observation that "stalls are always total: bytes either flow immediately or not at
     *     all", which is what a dead connection serving every request looks like; and
     *   the 1112-1113 double-trip data (2 users, 4 reports, identical): breaker trips, the user presses
     *     play, and the next three tracks fail the same way within ~23-26s. A connection reused every
     *     few seconds is never IDLE, so the pool's 5-minute keepalive never evicts it.
     *
     * ⚠️ THE 10s FUSE AND THE RETRY LADDER ARE UNCHANGED BY THIS. On a dead pooled connection
     * okhttp's readTimeout still fires at 10s - it just fires instead of us, so the degraded ping goes
     * out and media3's next attempt gets a FRESH connection. The resulting SocketTimeoutException is an
     * IOException, so it takes RawDataSource.open's existing `catch (e: IOException) { throw e }` arm
     * untouched; the TimeoutCancellationException rewrap there is now the belt, not the mechanism.
     * PlayerEventListener.OPEN_GRACE_MS (50s) is derived from that same 4 x 10s + 0/1/2s ladder and
     * therefore needs no change.
     * ⚠️ 30s RATHER THAN 20s: clientNP has NO callTimeout (deliberately - the audio body read
     * takes minutes), so okhttp's own worst case for this HEAD is connect 15s + read 10s = ~25s. A
     * backstop below that would start cutting legitimate slow connects, which is the job it is not for.
     * ⚠️ IT IS STILL LOAD-BEARING: clientNP's missing callTimeout means a call that outlives
     * okhttp's per-operation timeouts has no other ceiling, and this runs on a media3 loader thread
     * inside runBlocking. Do not delete it in favour of "okhttp handles it".
     */
    suspend fun getContentLength(url: String, client: OkHttpClient): Long {
        return withTimeout(30_000) {
            val request = Request.Builder().url(url).head().build()
            client.newCall(request).await().use { response ->
                response.header("Content-Length")?.toLong() ?: 0L
            }
        }
    }
}

/**
 * Seems Deezer ditched this way of getting songs.
 * Will leave it for now.
 */
/*
@Suppress("NewApi", "GetInstance")
fun generateTrackUrl(trackId: String, md5Origin: String, mediaVersion: String, quality: Int): String {
    val magicByte = 164
    val aesKey = "jo6aey6haid2Teih".toByteArray()
    val keySpec = SecretKeySpec(aesKey, "AES")

    val step1 = ByteArrayOutputStream().apply {
        write(md5Origin.toByteArray())
        write(magicByte)
        write(quality.toString().toByteArray())
        write(magicByte)
        write(trackId.toByteArray())
        write(magicByte)
        write(mediaVersion.toByteArray())
    }

    val md5Digest = MessageDigest.getInstance("MD5").digest(step1.toByteArray())
    val md5hex = md5Digest.joinToString("") { "%02x".format(it) }

    val step2 = ByteArrayOutputStream().apply {
        write(md5hex.toByteArray())
        write(magicByte)
        write(step1.toByteArray())
        write(magicByte)
    }

    while (step2.size() % 16 != 0) {
        step2.write(46)
    }

    val cipher = Cipher.getInstance("AES/ECB/NoPadding").apply {
        init(Cipher.ENCRYPT_MODE, keySpec)
    }

    val encryptedHex = StringBuilder()
    val step2Bytes = step2.toByteArray()
    for (i in step2Bytes.indices step 16) {
        val block = step2Bytes.copyOfRange(i, i + 16)
        encryptedHex.append(cipher.doFinal(block).joinToString("") { "%02x".format(it) })
    }

    return "https://e-cdns-proxy-${md5Origin[0]}.dzcdn.net/mobile/1/$encryptedHex"
}*/