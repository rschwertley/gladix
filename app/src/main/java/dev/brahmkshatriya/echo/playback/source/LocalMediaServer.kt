package dev.brahmkshatriya.echo.playback.source

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import dev.brahmkshatriya.echo.common.models.Streamable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A LAN HTTP endpoint that serves one extension-resolved stream per request, by reading through the
 * SAME ResolvingDataSource(CacheDataSource(StreamableDataSource)) chain the local player uses. Built
 * for Cast (the receiver cannot speak Deezer, Spotify or Blowfish, so something on this side has to
 * hand it plain bytes over http), and usable before any Cast code exists through
 * [PLAY_THROUGH_LOCAL_SERVER].
 *
 * ⚠⚠ ONLY REGISTERED TOKENS ARE SERVED, AND THAT IS NOT BELT-AND-BRACES - THE KEY IS GUESSABLE.
 * MediaItemUtils.buildForSource's key is Base64 of (trackId, sourceIndex, extensionId) with nothing
 * secret in it, so a URL carrying the key would let anything on the same Wi-Fi fetch DECRYPTED audio
 * for any track id it cared to try - Deezer ids are small integers. So the key never travels: [urlFor]
 * mints a 122-bit random token (UUID.randomUUID, which is specified to draw from a cryptographically
 * strong source), maps token -> key in [tokens], and the URL carries only the token. An unregistered
 * token is a 404 with no distinction from a malformed one, and [stop] clears the map, so a token
 * cannot outlive the server that minted it.
 * ⚠️ [CORRECTED 2026-10-17] AN EARLIER VERSION PUT THE KEY IN THE URL AS A QUERY PARAMETER
 * (`?k=<encoded>`) AND THE NOTE HERE ARGUED ONLY ABOUT ENCODING. That reasoning was sound and
 * answered the wrong question: the real objection is that the key is PREDICTABLE, not that it is
 * awkward to encode. The encoding fact is kept because it is still true and still a trap - the key
 * uses `kotlin.io.encoding.Base64` on its DEFAULT alphabet, which contains `+`, `/` and `=`, so it
 * can never go in a path segment. If anyone is ever tempted to put the key back in the URL, it fails
 * on BOTH counts.
 *
 * ⚠⚠ THE RESOLVER IS THE MAIN PLAYER'S, BY CONSTRUCTION - SAME `state.servers` MAP, SAME REASON
 * AS CacheOnlyTailSource. The cache key is the resolved DataSpec uri (CacheKeyFactory.DEFAULT), and
 * [StreamableDataSource.uri] derives it from the resolved source: the signed CDN URL for Http,
 * "raw://<id.hashCode()>" for Raw. A SECOND resolve mints a different signed URL, hence a different
 * key, hence a guaranteed cache miss - so a track the local player has already cached would be
 * re-downloaded in full for the Cast device. Never resolve here.
 *
 * ⚠⚠ NO WRITE SINK. READER NUMBER THREE, WRITER STILL NUMBER ONE.
 * There are now three readers of these keys - the main player, the crossfade sidecar
 * (CacheOnlyTailSource) and this - and exactly one writer, the main player's CacheDataSink.
 * SimpleCache is single-writer per key, and the open `commitFile` / `isFullyLocked` failure has no
 * explanation yet, so adding a writer while that is unexplained is the one change that could turn an
 * unexplained failure into a reproducible one. The null sink also keeps this out of the hole-lock
 * hazard the sidecar's note documents: with no write sink CacheDataSource.openNextSource takes its
 * :767-771 arm (1.11.0), calling cache.releaseHoleSpan(nextSpan) IMMEDIATELY, so this never holds a
 * hole-span lock across any I/O.
 *
 * ⚠️ WHAT IT COSTS THE CROSSFADE TAIL, WHICH IS THE QUESTION WORTH ASKING AND IS NOT "NOTHING".
 * Not contention - see above - but POPULATION. While [PLAY_THROUGH_LOCAL_SERVER] is on, the local
 * player's chain is replaced by a plain http factory pointed at this server
 * (StreamableMediaSource.Factories.create), so for the duration of that test build NOBODY WRITES THE
 * CACHE: this reads without writing, and the main player is no longer on the caching chain at all. The
 * sidecar then finds 0 cached bytes and fetches the whole tail through its own upstream. That is
 * correct behaviour rather than a break - CrossfadeController.evaluateGate no longer requires
 * Gate.ready - but it means a crossfade in a proxy test build costs a second full fetch, and a
 * cachedBytes=0 reading in that build is the flag, not a regression. With the flag off, nothing here
 * changes what the player writes.
 *
 * ⚠️ WI-FI ONLY IS ENFORCED BY THE BIND ADDRESS, NOT BY A CHECK. The socket is bound to the
 * Wi-Fi interface's own IPv4 address, so there is no listener on the cellular interface to find -
 * which is stronger than binding 0.0.0.0 and refusing non-LAN peers, and is why [start] returns false
 * rather than falling back when Wi-Fi is down.
 *
 * ⚠️ CORS ON EVERY RESPONSE, BECAUSE THE CAST RECEIVER IS A WEB PAGE. The Default Media
 * Receiver is an HTML app on a cast.google.com origin fetching a media URL from this host, so without
 * `Access-Control-Allow-Origin` the browser blocks it - and a ranged media element also needs
 * `Content-Range` / `Content-Length` / `Accept-Ranges` named in `Access-Control-Expose-Headers` or it
 * cannot see them even on an allowed response, which costs seeking. The headers are added inside
 * [writeHead] rather than at each call site so that NO response can miss them, 404 and 416 included -
 * a CORS-less error response reaches the page as an opaque network failure rather than as its status.
 */
@OptIn(UnstableApi::class)
class LocalMediaServer(
    private val context: Context,
    cache: SimpleCache,
    servers: MutableMap<String, Result<Streamable.Media.Server>>,
) {

    private val resolver = StreamableResolver(context, servers)

    /**
     * ⚠️ A FRESH StreamableDataSource.Factory, exactly as CacheOnlyTailSource does and for the
     * same reason: the factory is stateless, createDataSource() mints a source per request, and what
     * must not be duplicated is the RESOLVER - which is not, see the class note.
     */
    private val upstream = StreamableDataSource.Factory(context)

    private val dataSourceFactory: DataSource.Factory = ResolvingDataSource.Factory(
        CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstream)
            .setCacheWriteDataSinkFactory(null),
        resolver
    )

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var workers: ExecutorService? = null

    /**
     * Read from loader threads through [urlFor] and written by [start] / [stop] on whichever thread
     * owns the service lifecycle, so it is volatile rather than guarded - a stale null only makes the
     * hook fall through to the ordinary chain.
     */
    @Volatile
    private var base: String? = null

    /**
     * token -> key, insertion-ordered oldest-first, capped at [MAX_TOKENS] and guarded by `this`.
     *
     * ⚠⚠ EVICTION IS LRU, NOT FIFO, AND THAT IS WHAT MAKES THE CAP SAFE. A cap is pointless if
     * it can drop the token of the track being PLAYED - the next seek would 404 and playback would
     * stop. [keyFor] re-inserts on every lookup ([touch]), so the token currently being read is always
     * the newest entry and eviction can only ever reach a source nobody is reading. The intended
     * population is "current + next"; the cap is larger than two because a quality or server change
     * re-prepares the current item while the next one is already registered, and over-evicting is the
     * failure that breaks playback while over-retaining merely keeps a few dead tokens alive until
     * [stop].
     */
    private val tokens = LinkedHashMap<String, String>()

    /**
     * Whole-resource lengths, learned from the first open of each key and reused for the
     * `Content-Range` denominator of every later ranged request. Memoised rather than re-derived
     * because deriving it costs an open, and for an uncached Deezer Raw source an open is a HEAD plus
     * a ranged GET plus a Blowfish prime. Keyed by the KEY, not the token, so it survives re-minting.
     */
    private val lengths = ConcurrentHashMap<String, Long>()

    val isRunning: Boolean get() = base != null

    /**
     * Registers [key] and returns the URL that serves it, or null if the server could not be brought
     * up - callers treat null as "use the ordinary chain" rather than as an error.
     *
     * ⚠⚠ THIS IS WHERE THE SOCKET IS BOUND, AND THAT IS THE WHOLE LIFETIME DESIGN. Nothing
     * starts the server at app launch or at service creation; the first request for a stream URL does,
     * which means a normal run - and a run of a test build where nothing is ever played - opens no
     * listening socket at all. Idempotent, because [start] returns early when already running.
     */
    @Synchronized
    fun urlFor(key: String): String? {
        if (!start()) return null
        val prefix = base ?: return null
        return prefix + PATH_PREFIX + register(key)
    }

    /**
     * ⚠️ CALLED BY [urlFor] ON DEMAND, AND DIRECTLY BY A CAST SESSION IN STAGE 3. The second
     * caller is why this is public and why it does not assume the test flag: a Cast session needs the
     * server up before the receiver is handed any URL, whereas the local test hook can bind lazily on
     * the first stream.
     */
    @Synchronized
    fun start(): Boolean {
        if (running.get()) return true
        val address = wifiAddress()
        if (address == null) {
            if (VERBOSE) Log.w(TAG, "not starting: no Wi-Fi IPv4 address")
            return false
        }
        val host = address.hostAddress
        if (host == null) {
            if (VERBOSE) Log.w(TAG, "not starting: Wi-Fi address has no literal form")
            return false
        }
        val opened = runCatching { ServerSocket(0, BACKLOG, address) }
        val socket = opened.getOrNull()
        if (socket == null) {
            if (VERBOSE) Log.w(
                TAG, "bind failed: " + (opened.exceptionOrNull()?.javaClass?.simpleName ?: "?")
            )
            return false
        }
        serverSocket = socket
        workers = Executors.newCachedThreadPool()
        base = "http://" + host + ":" + socket.localPort
        running.set(true)
        val thread = Thread({ acceptLoop(socket) }, THREAD_NAME)
        thread.isDaemon = true
        thread.start()
        if (VERBOSE) Log.d(TAG, "listening on " + base)
        return true
    }

    /**
     * ⚠️ CLOSING THE ServerSocket IS WHAT ENDS THE ACCEPT LOOP. accept() blocks with no timeout,
     * so the `running` flag alone would leave that thread parked until the next connection. close()
     * makes accept() throw, which the loop reads as "stop" - the flag is there so a stop that races a
     * connection does not start serving it.
     * ⚠⚠ CLEARING [tokens] IS PART OF STOPPING, NOT TIDYING. A token must not survive the
     * server that minted it, or a restart on a new port would leave previously-handed-out URLs
     * semantically valid; and any MediaSource still holding an old URL must fail loudly (404) rather
     * than resolve to whatever key happens to re-register later.
     */
    @Synchronized
    fun stop() {
        if (!running.getAndSet(false) && serverSocket == null) return
        base = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        workers?.shutdownNow()
        workers = null
        tokens.clear()
        lengths.clear()
        if (VERBOSE) Log.d(TAG, "stopped")
    }

    /** Reuses this key's existing token when it has one, so a re-prepare does not grow the map. */
    @Synchronized
    private fun register(key: String): String {
        val existing = tokens.entries.firstOrNull { it.value == key }?.key
        if (existing != null) {
            touch(existing)
            return existing
        }
        val token = UUID.randomUUID().toString().replace("-", "")
        tokens[token] = key
        while (tokens.size > MAX_TOKENS) {
            val oldest = tokens.keys.firstOrNull() ?: break
            tokens.remove(oldest)
        }
        if (VERBOSE) Log.d(TAG, "registered key=" + keyHash(key) + " tokens=" + tokens.size)
        return token
    }

    @Synchronized
    private fun keyFor(token: String): String? {
        val key = tokens[token] ?: return null
        touch(token)
        return key
    }

    // LinkedHashMap is insertion-ordered, so removing and re-inserting is how an entry becomes the
    // newest. Callers already hold the monitor; see the eviction note on `tokens` for why this exists.
    private fun touch(token: String) {
        val key = tokens.remove(token) ?: return
        tokens[token] = key
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running.get()) {
            val client = runCatching { socket.accept() }.getOrNull()
            if (client == null) break
            val pool = workers
            if (pool == null) {
                runCatching { client.close() }
                break
            }
            val submitted = runCatching {
                pool.execute {
                    try {
                        serve(client)
                    } catch (e: Throwable) {
                        // A worker must not die with a stack trace on the console for a stream the
                        // extension refused or a client that vanished; the request has already been
                        // answered or the socket is already gone.
                        if (VERBOSE) Log.w(TAG, "serve failed: " + e.javaClass.simpleName)
                    } finally {
                        runCatching { client.close() }
                    }
                }
            }.isSuccess
            if (!submitted) runCatching { client.close() }
        }
    }

    private fun serve(client: Socket) {
        client.soTimeout = READ_TIMEOUT_MS
        client.tcpNoDelay = true
        val input = client.getInputStream().buffered()
        val output = client.getOutputStream().buffered()

        val requestLine = readHeaderLine(input) ?: return
        val parts = requestLine.split(' ')
        if (parts.size < 2) {
            respondStatus(output, 400, "Bad Request")
            return
        }
        val method = parts[0].uppercase()
        val target = parts[1]

        var rangeHeader: String? = null
        while (true) {
            val header = readHeaderLine(input) ?: return
            if (header.isEmpty()) break
            val colon = header.indexOf(':')
            if (colon <= 0) continue
            if (header.substring(0, colon).trim().equals("Range", true)) {
                rangeHeader = header.substring(colon + 1).trim()
            }
        }

        // The CORS preflight, answered before anything else looks at the target: a browser sends it
        // for the media fetch and the body is irrelevant to it. 204 rather than 200 because there is
        // no body and a preflight response must not have one.
        if (method == "OPTIONS") {
            if (VERBOSE) Log.d(TAG, "OPTIONS -> 204")
            runCatching {
                writeHead(output, 204, "No Content", listOf("Connection: close"))
                output.flush()
            }
            return
        }
        if (method != "GET" && method != "HEAD") {
            respondStatus(output, 405, "Method Not Allowed", "Allow: GET, HEAD, OPTIONS")
            return
        }

        val path = runCatching { Uri.parse(PATH_BASE + target).path }.getOrNull()
        val token = if (path == null || !path.startsWith(PATH_PREFIX)) null
        else path.substring(PATH_PREFIX.length).trim('/')
        // ⚠️ ONE 404 FOR EVERY REJECTION, AND THE SAMENESS IS THE POINT. A wrong path, an
        // expired token and a never-registered token are indistinguishable from outside, so probing
        // learns nothing about what this device is playing.
        val key = if (token.isNullOrEmpty()) null else keyFor(token)
        if (key == null) {
            if (VERBOSE) Log.d(TAG, method + " unknown token -> 404")
            respondStatus(output, 404, "Not Found")
            return
        }

        val range = parseRange(rangeHeader)
        if (range == null) {
            respondRangeError(output, lengths[key])
            return
        }

        var known = lengths[key]
        if (range.suffix) {
            // The only shape that needs the length BEFORE the real open, and the only one that pays an
            // extra one. Neither ExoPlayer nor the Cast receiver sends a suffix range; it is handled so
            // that a curl check does not look like a server fault.
            if (known == null) known = measureLength(key)
            if (known == null) {
                respondRangeError(output, null)
                return
            }
        }
        val start = if (range.suffix) (known!! - range.suffixLength).coerceAtLeast(0L)
        else range.start
        if (known != null && known > 0L && start >= known) {
            respondRangeError(output, known)
            return
        }

        val source = dataSourceFactory.createDataSource()
        val opened = runCatching { source.open(specFor(key, start)) }
        val remaining = opened.getOrNull()
        if (remaining == null) {
            runCatching { source.close() }
            if (VERBOSE) Log.w(
                TAG, "open failed key=" + keyHash(key) + " start=" + start + " " +
                    (opened.exceptionOrNull()?.javaClass?.simpleName ?: "?")
            )
            respondStatus(output, 500, "Internal Server Error")
            return
        }
        if (remaining != LENGTH_UNSET) {
            val measured = start + remaining
            lengths[key] = measured
            known = measured
        }

        // ⚠️ A 206 WITHOUT A USABLE Content-Range IS INVALID, SO IT IS REFUSED RATHER THAN FAKED.
        // This needs both an end and, ideally, a total; with neither available the resource has no
        // whole-resource length at all, which for this chain means a live stream. Answering 200 from a
        // non-zero offset instead would make DefaultHttpDataSource skip forward AGAIN over bytes it
        // already asked to skip, i.e. silently serve the wrong audio.
        if (range.isPartial && known == null && range.endInclusive < 0L) {
            runCatching { source.close() }
            respondRangeError(output, null)
            return
        }

        val total = known
        val end = when {
            range.suffix -> (total ?: 0L) - 1L
            range.endInclusive >= 0L -> range.endInclusive
            total != null -> total - 1L
            else -> -1L
        }
        val bodyLength = if (end >= 0L) (end - start + 1L).coerceAtLeast(0L) else -1L

        val headers = ArrayList<String>()
        headers.add("Accept-Ranges: bytes")
        headers.add("Content-Type: " + MIME)
        if (bodyLength >= 0L) headers.add("Content-Length: " + bodyLength)
        if (range.isPartial) headers.add(
            "Content-Range: bytes " + start + "-" + end + "/" + (total?.toString() ?: "*")
        )
        // Always close: one request per connection, because the worker closes the socket as soon
        // as serve() returns. Advertising keep-alive would be a lie the next request pays for.
        headers.add("Connection: close")

        val status = if (range.isPartial) 206 else 200
        if (VERBOSE) Log.d(
            TAG, method + " key=" + keyHash(key) + " range=" + (rangeHeader ?: "-") +
                " -> " + status + " start=" + start + " len=" + bodyLength + " total=" + total
        )
        runCatching {
            writeHead(output, status, if (range.isPartial) "Partial Content" else "OK", headers)
            output.flush()
        }
        if (method == "HEAD") {
            runCatching { source.close() }
            return
        }

        val buffer = ByteArray(BUFFER)
        var written = 0L
        try {
            while (bodyLength < 0L || written < bodyLength) {
                val want = if (bodyLength < 0L) buffer.size
                else minOf(buffer.size.toLong(), bodyLength - written).toInt()
                if (want <= 0) break
                val read = source.read(buffer, 0, want)
                if (read == C.RESULT_END_OF_INPUT) break
                output.write(buffer, 0, read)
                written += read
            }
            output.flush()
        } catch (e: IOException) {
            // Routine, not a fault: every seek, scrub and stop abandons an in-flight response, and the
            // client closing its half is how that arrives here. Logged at debug only because a build
            // with the flag on is a build where the pattern of these IS the measurement.
            if (VERBOSE) Log.d(
                TAG, "aborted key=" + keyHash(key) + " written=" + written + " of " + bodyLength +
                    " " + e.javaClass.simpleName
            )
        } finally {
            runCatching { source.close() }
        }
    }

    private fun specFor(key: String, position: Long) = DataSpec.Builder()
        .setUri(Uri.parse(key))
        .setPosition(position)
        // ⚠️ LENGTH_UNSET EVEN WHEN THE CLIENT GAVE AN END, AND THAT IS HOW THE TOTAL IS LEARNT.
        // open() returns the bytes readable from `position`, so an UNBOUNDED open yields
        // position + returned = the whole-resource length. Passing the client's bounded length instead
        // would make open() return just that window and the Content-Range denominator unknowable. The
        // response is still truncated to what was asked - see the write loop.
        .setLength(LENGTH_UNSET)
        .build()

    private fun measureLength(key: String): Long? {
        val source = dataSourceFactory.createDataSource()
        val length = runCatching { source.open(specFor(key, 0L)) }.getOrNull()
        runCatching { source.close() }
        if (length == null || length == LENGTH_UNSET) return null
        lengths[key] = length
        return length
    }

    private data class Range(
        val start: Long,
        val endInclusive: Long,
        val suffix: Boolean,
        val suffixLength: Long,
        val isPartial: Boolean,
    )

    /**
     * Null means "cannot be served": a malformed header, or a multi-range request. Multi-range is
     * refused rather than implemented - it would need multipart/byteranges, no media client asks for
     * it, and a 416 is a correct answer to a range this server will not satisfy.
     */
    private fun parseRange(header: String?): Range? {
        if (header == null) return Range(0L, -1L, false, 0L, false)
        val value = header.trim()
        if (!value.startsWith("bytes=", true)) return null
        val spec = value.substring(6).trim()
        if (spec.contains(',')) return null
        val dash = spec.indexOf('-')
        if (dash < 0) return null
        val first = spec.substring(0, dash).trim()
        val second = spec.substring(dash + 1).trim()
        if (first.isEmpty()) {
            val suffixLength = second.toLongOrNull() ?: return null
            if (suffixLength <= 0L) return null
            return Range(0L, -1L, true, suffixLength, true)
        }
        val from = first.toLongOrNull() ?: return null
        if (from < 0L) return null
        if (second.isEmpty()) return Range(from, -1L, false, 0L, true)
        val to = second.toLongOrNull() ?: return null
        if (to < from) return null
        return Range(from, to, false, 0L, true)
    }

    /** The CORS block is added HERE so that no response can omit it - see the class note. */
    private fun writeHead(out: OutputStream, status: Int, reason: String, headers: List<String>) {
        val head = StringBuilder()
        head.append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
        for (header in CORS) head.append(header).append("\r\n")
        for (header in headers) head.append(header).append("\r\n")
        head.append("\r\n")
        out.write(head.toString().toByteArray(Charsets.US_ASCII))
    }

    private fun respondStatus(
        out: OutputStream, status: Int, reason: String, vararg headers: String,
    ) {
        runCatching {
            writeHead(
                out, status, reason,
                listOf("Content-Length: 0", "Connection: close", *headers)
            )
            out.flush()
        }
    }

    private fun respondRangeError(out: OutputStream, total: Long?) {
        respondStatus(
            out, 416, "Range Not Satisfiable",
            "Content-Range: bytes */" + (total?.toString() ?: "*")
        )
    }

    private fun readHeaderLine(input: InputStream): String? {
        val line = StringBuilder()
        while (true) {
            val c = input.read()
            if (c == -1) return if (line.isEmpty()) null else line.toString()
            if (c == NEWLINE) return line.toString().removeSuffix("\r")
            if (line.length >= MAX_LINE) return null
            line.append(c.toChar())
        }
    }

    /**
     * ⚠️ allNetworks RATHER THAN activeNetwork, AND IT IS DEPRECATED ON PURPOSE. The ACTIVE
     * network is frequently cellular while Wi-Fi is also up and is the one a Cast device is on, so
     * activeNetwork would refuse to start in exactly the situation this exists for. There is no
     * non-deprecated API that enumerates networks; the replacement (a NetworkCallback with a Wi-Fi
     * request) is a listener, not a query, and would make start() asynchronous.
     */
    @Suppress("DEPRECATION")
    private fun wifiAddress(): Inet4Address? {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
        return runCatching {
            manager.allNetworks.firstNotNullOfOrNull { network ->
                val capabilities = manager.getNetworkCapabilities(network)
                if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) null
                else manager.getLinkProperties(network)?.linkAddresses
                    ?.map { it.address }
                    ?.filterIsInstance<Inet4Address>()
                    ?.firstOrNull { !it.isLoopbackAddress && !it.isAnyLocalAddress }
            }
        }.getOrNull()
    }

    // The key carries trackId/sourceIndex/extensionId and no secret, but it is still the identifier of
    // what someone is listening to, so the log carries a hash of it rather than the thing itself. The
    // TOKEN is never logged at all - it is the credential.
    private fun keyHash(key: String) = Integer.toHexString(key.hashCode())

    companion object {

        /**
         * ⚠⚠ THE TEST HOOK. With this true, StreamableMediaSource.Factories.create points the
         * LOCAL player at this server instead of at the resolving/caching chain, so playback, seek and
         * scrub can be verified on the phone with no Cast device in the room. It also turns on this
         * class's logging, which is the only way to see what a seek actually requested.
         *
         * ⚠️ A `const val`, NOT BuildConfig.DEBUG, BECAUSE ONLY RELEASE BUILDS ARE EVER
         * INSTALLED HERE - a DEBUG-gated hook would be dead code in every build that reaches a device.
         * The same pattern was used for CrossfadeController's AA gate, which is gone now that AA
         * crossfade is permanent (2026-10-17) - so this is the only remaining instance, and the
         * reason is recorded here rather than by pointing at a sibling that may also go.
         *
         * FLIP BACK TO false BEFORE SHIPPING: while it is true nothing writes the SimpleCache (see the
         * class note on the crossfade tail), and playback depends on the phone's own Wi-Fi being up.
         */
        const val PLAY_THROUGH_LOCAL_SERVER = false

        private const val VERBOSE = PLAY_THROUGH_LOCAL_SERVER

        private const val TAG = "GladixCastServer"
        private const val THREAD_NAME = "GladixLocalMediaServer"
        private const val PATH_PREFIX = "/stream/"

        // Only ever parsed, never dialled: the request target is relative, and Uri.parse needs an
        // authority before it will treat the target as a path.
        private const val PATH_BASE = "http://localhost"

        /**
         * "current + next", with headroom for a quality change re-preparing the current item. Eviction
         * cannot reach a token being read - see the note on `tokens`.
         */
        private const val MAX_TOKENS = 4

        private val CORS = listOf(
            "Access-Control-Allow-Origin: *",
            "Access-Control-Allow-Methods: GET, HEAD, OPTIONS",
            "Access-Control-Allow-Headers: *",
            "Access-Control-Expose-Headers: Content-Length, Content-Range, Accept-Ranges",
        )

        // Nominal. The chain hands over whatever the extension resolved - MP3 for Deezer 128/320, FLAC
        // for lossless, Opus/AAC elsewhere - and no source carries a MIME we could forward. ExoPlayer
        // sniffs the container and ignores this entirely, which is why a test hook does not need it to
        // be right; the Cast receiver DOES care, so stage 3 has to carry a real type per source rather
        // than inherit this.
        private const val MIME = "audio/mpeg"

        /**
         * ⚠️ C.LENGTH_UNSET IS DECLARED `int`, AND EVERY LENGTH IN THIS FILE IS Long.
         * C.java:79 (media3 1.11.0) is `public static final int LENGTH_UNSET = -1` - while
         * C.TIME_UNSET seventeen lines above it is a `long`. So `someLong != C.LENGTH_UNSET` does
         * not compile, and neither does passing it to DataSpec.Builder.setLength(long). Three
         * sites here were written that way and failed the release build (2026-10-17).
         * Widened ONCE here instead of `.toLong()` at each use, so a fourth use cannot repeat it.
         * NOT a `const val`: a const initializer must be a compile-time constant and
         * `.toLong()` is a call, so this is a companion `val` - which also keeps the tie to
         * media3's own constant rather than hardcoding -1L here.
         */
        private val LENGTH_UNSET: Long = C.LENGTH_UNSET.toLong()

        private const val BUFFER = 64 * 1024
        private const val BACKLOG = 8
        private const val READ_TIMEOUT_MS = 15_000
        private const val MAX_LINE = 8192
        private const val NEWLINE = 10
    }
}
