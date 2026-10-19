package dev.brahmkshatriya.echo.cast

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import android.net.Uri
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.playback.source.StreamableDataSource
import dev.brahmkshatriya.echo.playback.source.StreamableResolver
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Materialises a [Streamable.Source.Raw] to a local file so the receiver can be given a real
 * Content-Length and satisfiable byte ranges.
 *
 * ⚠⚠ ONLY Raw NEEDS THIS, AND THE REASON IS THE ONE THING A CHROMECAST CANNOT TOLERATE.
 * A Raw source's length comes from InputProvider.provide, documented as "-1 if unknown"
 * (Streamable.kt:260-269), and when it is unknown LocalMediaServer could only answer a ranged GET -
 * which is a media element's FIRST request - with 416. Copying the decrypted bytes to a file replaces
 * an unknown length with a measured one, which makes every range satisfiable by construction. Http
 * sources are never copied: upstream supplies the real length, so they relay live and start instantly.
 *
 * ⚠⚠ THE COPY GOES THROUGH THE PLAYER'S OWN CHAIN, WHICH IS WHAT DECRYPTS IT. Same shape as
 * CacheOnlyTailSource and for the same reasons: the SHARED resolver (so the resolved URL and therefore
 * the SimpleCache key match the local player's by construction) and a NULL WRITE SINK (so this is a
 * third reader and never a second writer while SimpleCache's commitFile/isFullyLocked failure is
 * unexplained). StreamableDataSource routes Raw to RawDataSource, where the Blowfish decrypt happens,
 * so the bytes that land in the file are plain audio and the receiver never sees the encryption.
 *
 * ⚠️ ONE FILE AT A TIME, AND EVICTION IS BY TOKEN RATHER THAN BY AGE. [retain] names
 * the tokens still wanted and everything else is deleted, so the CALLER'S INTENT decides what
 * survives rather than a timestamp. Only the track on the receiver is ever here - the session
 * prepares one item at a time and materialises nothing ahead of it (see
 * CastSessionController.advanceLocalQueue) - so the set handed to [retain] normally holds exactly
 * one token. The rule is written this way rather than as a count because an age cap would have to
 * GUESS which file still matters, and the caller always knows.
 */
@OptIn(UnstableApi::class)
class CastStreamStore(
    private val context: Context,
    cache: SimpleCache,
    servers: MutableMap<String, Result<Streamable.Media.Server>>,
) {

    private val resolver = StreamableResolver(context, servers)

    private val dataSourceFactory: DataSource.Factory = ResolvingDataSource.Factory(
        CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(StreamableDataSource.Factory(context))
            .setCacheWriteDataSinkFactory(null),
        resolver
    )

    /** token -> the finished file. Only complete files are ever in here. */
    private val ready = ConcurrentHashMap<String, File>()

    /**
     * ⚠⚠ ORDERS [purgeAll] AGAINST [materialise] WITHOUT A LOCK, AND WITHOUT IT THE
     * PURGE COULD DELETE A FILE BEING WRITTEN. purgeAll is DISPATCHED by LocalMediaServer.start
     * rather than called there, because it must not do file I/O on the main thread - so it can now
     * land after a load has minted a token and begun copying. Whichever arrives first wins: a
     * purge that claims the folder sweeps it, and a copy that claims it first turns the purge into
     * a no-op.
     * ⚠️ BOTH ORDERINGS ARE SAFE, WHICH IS WHY A FLAG IS ENOUGH. The only cost of the
     * purge losing is that files left by a PREVIOUS process survive one process longer - [retain]
     * cannot see them (it only knows tokens from this process) but the next purge will take them.
     * Losing requires a copy to start within milliseconds of the bind, so it should be rare; it is
     * logged either way.
     */
    private val purgeClaim = AtomicBoolean(false)

    private val dir: File
        get() = File(context.cacheDir, DIR).also { if (!it.exists()) it.mkdirs() }

    fun fileFor(token: String): File? = ready[token]

    /**
     * Copies the whole stream for [key] into a file named by [token]. Blocking; call off the main
     * thread. Returns null on failure, having logged why and cleaned up the partial file.
     *
     * ⚠⚠ THE STALL WATCHDOG IS A PROGRESS CHECK, NOT A DEADLINE, AND THAT DISTINCTION IS THE
     * WHOLE POINT. A total time limit would fail a large track on a slow link that was working fine.
     * What actually needs catching is a copy that has STOPPED moving - an upstream that accepted the
     * connection and then went quiet, which is what a Wi-Fi drop mid-copy looks like. So the limit is
     * on the gap between two successful reads, not on the total.
     */
    fun materialise(token: String, key: String): File? {
        // Claim the folder so an in-flight purge cannot delete what is about to be written. See
        // the note on purgeClaim; this is the cheap half of that ordering.
        purgeClaim.set(true)
        ready[token]?.let { return it }
        val target = File(dir, token + EXT)
        val startedAt = System.currentTimeMillis()
        val source = dataSourceFactory.createDataSource()
        var written = 0L
        val opened = runCatching {
            source.open(DataSpec.Builder().setUri(Uri.parse(key)).setPosition(0L).build())
        }
        val expected = opened.getOrNull()
        if (expected == null) {
            Log.w(
                TAG, "materialise open failed token=" + token + " " +
                    (opened.exceptionOrNull()?.javaClass?.simpleName ?: "?")
            )
            runCatching { source.close() }
            return null
        }
        Log.d(TAG, "materialise start token=" + token + " expected=" + expected)
        val ok = runCatching {
            FileOutputStream(target).use { out ->
                val buffer = ByteArray(BUFFER)
                var lastProgressAt = System.currentTimeMillis()
                while (true) {
                    val read = source.read(buffer, 0, buffer.size)
                    if (read == C.RESULT_END_OF_INPUT) break
                    if (read > 0) {
                        out.write(buffer, 0, read)
                        written += read
                        lastProgressAt = System.currentTimeMillis()
                    } else if (System.currentTimeMillis() - lastProgressAt > STALL_MS) {
                        error("no progress for " + STALL_MS + "ms at " + written + " bytes")
                    }
                }
                out.flush()
            }
            true
        }.getOrElse {
            Log.w(
                TAG, "materialise failed token=" + token + " at=" + written +
                    " " + it.javaClass.simpleName + ": " + it.message
            )
            false
        }
        runCatching { source.close() }
        if (!ok) {
            runCatching { target.delete() }
            return null
        }
        // An empty or truncated file would be served with a Content-Length the receiver then cannot
        // fill - a stall with no error, which is the exact failure mode this whole store exists to
        // remove. Treat it as a failure here instead.
        if (written <= 0L || (expected != LENGTH_UNSET && written < expected)) {
            Log.w(TAG, "materialise short token=" + token + " got=" + written + " expected=" + expected)
            runCatching { target.delete() }
            return null
        }
        Log.d(
            TAG, "materialise done token=" + token + " bytes=" + written +
                " ms=" + (System.currentTimeMillis() - startedAt)
        )
        ready[token] = target
        return target
    }

    /**
     * Empties the folder completely. Called once per process, from LocalMediaServer's first
     * successful start - on a WORKER THREAD, not the caller's: listFiles plus one delete per file
     * is unbounded disk I/O and that start can happen on the main thread. [purgeClaim] is what
     * makes the asynchrony safe.
     *
     * ⚠⚠ THIS EXISTS FOR THE CRASH CASE, WHICH [retain] CANNOT REACH. retain runs
     * after a load and at session end, so it only ever tidies up a session that got to tidy up. A
     * process killed mid-copy - low memory, a crash, force-stop, the service being killed during a
     * long cast - leaves a partial file and no in-memory entry naming it, and nothing would remove
     * it until some later session happened to sweep. Clearing on start means the folder's lifetime
     * is bounded by the process rather than by a session ending cleanly.
     * ⚠️ SAFE BECAUSE NOTHING CAN STILL BE WANTED AT THAT POINT. Tokens are random
     * per load and live only in memory, so a file from a previous process is unreachable by
     * definition - there is no token left that could name it.
     */
    fun purgeAll() {
        if (!purgeClaim.compareAndSet(false, true)) {
            Log.d(TAG, "skipping purge: a copy claimed the folder first")
            return
        }
        // A no-op by construction when the purge wins the claim - nothing can have materialised
        // yet - but left in so the function means what its name says if it is ever called again.
        ready.clear()
        val deleted = runCatching {
            dir.listFiles()?.count { it.delete() } ?: 0
        }.getOrDefault(0)
        Log.d(TAG, "purged cast cache on start: " + deleted + " file(s)")
    }

    /**
     * Deletes every file whose token is not in [keep]. Called after each load and at session end -
     * passing an empty set is how the session clears the folder.
     */
    fun retain(keep: Set<String>) {
        ready.keys.toList().forEach { token ->
            if (token !in keep) {
                val file = ready.remove(token)
                runCatching { file?.delete() }
            }
        }
        // Also sweep files with no entry at all: a process death mid-copy leaves a partial behind, and
        // nothing else would ever remove it.
        runCatching {
            dir.listFiles()?.forEach { file ->
                val token = file.name.removeSuffix(EXT)
                if (token !in keep) file.delete()
            }
        }
        val remaining = runCatching { dir.listFiles()?.sumOf { it.length() } ?: 0L }.getOrDefault(0L)
        if (remaining > MAX_BYTES) Log.w(TAG, "cast cache over cap: " + remaining + " bytes")
    }

    /** Sniffs the container from the first bytes, for [CastMime]. Null when the file is unreadable. */
    fun headerBytes(file: File): ByteArray? = runCatching {
        file.inputStream().use { input ->
            val head = ByteArray(SNIFF)
            val read = input.read(head)
            if (read <= 0) null else head.copyOf(read)
        }
    }.getOrNull()

    companion object {
        private const val TAG = "GladixCastServer"

        /**
         * C.LENGTH_UNSET is declared `int` (C.java:79, media3 1.11.0) while every length here is
         * Long, so comparing one against it directly does not compile. Widened once, the same way
         * LocalMediaServer does it - that constant is private to that class.
         *
         * A `val` and not a `const val`: a const initialiser must be a compile-time constant and
         * `.toLong()` is not one.
         */
        private val LENGTH_UNSET: Long = C.LENGTH_UNSET.toLong()

        private const val DIR = "cast"
        private const val EXT = ".bin"
        private const val BUFFER = 128 * 1024
        private const val SNIFF = 16

        /**
         * A gap between two successful reads, not a total time limit. 15 s matches the load timeout in
         * CastSessionController so a stalled copy and a stalled receiver report in the same window.
         */
        private const val STALL_MS = 15_000L

        /** current + next of lossless audio comfortably fits; the cap only catches a leak. */
        private const val MAX_BYTES = 256L * 1024 * 1024
    }
}
