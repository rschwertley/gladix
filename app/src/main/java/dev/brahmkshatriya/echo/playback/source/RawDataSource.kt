package dev.brahmkshatriya.echo.playback.source

import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import dev.brahmkshatriya.echo.common.models.Streamable
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking

@OptIn(UnstableApi::class)
class RawDataSource : BaseDataSource(true) {

    class Factory : DataSource.Factory {
        override fun createDataSource() = RawDataSource()
    }

    private var stream: InputStream? = null
    private var uri: Uri? = null

    override fun open(dataSpec: DataSpec): Long {
        val streamable = dataSpec.customData as Streamable.Source.Raw
        Log.d("GladixPlayback", "RawDataSource.open: invoking InputProvider pos=${dataSpec.position} uri=${dataSpec.uri}")
        val (source, total) = try {
            runBlocking {
                streamable.streamProvider!!.provide(dataSpec.position, dataSpec.length)
            }
        } catch (e: IOException) {
            throw e                     // already the recoverable type — pass through unchanged
        } catch (e: TimeoutCancellationException) {
            // ⚠⚠ A TIMEOUT IS A FAILURE WEARING CANCELLATION'S CLOTHES, AND THIS ARM IS THE WHOLE
            // DIFFERENCE BETWEEN ONE RETRY AND NONE. Without it, Deezer's getContentLength HEAD
            // (Utils.getContentLength) escaped open() as a CancellationException via the arm below,
            // and then:
            //   Loader.LoadTask.run (media3 1.11.0, Loader.java:470-475) catches any non-IOException and
            //     re-sends it as `new UnexpectedLoaderException(e)`;
            //   DefaultLoadErrorHandlingPolicy.isNonRetriableException (:174-181) LISTS
            //     UnexpectedLoaderException, and isAnyCauseNonRetriable (:163-171) walks the whole chain;
            //   so getRetryDelayMsFor returns C.TIME_UNSET, ProgressiveMediaPeriod.onLoadError (:889-890)
            //     returns Loader.DONT_RETRY_FATAL, and Loader.maybeThrowError (:347-348) throws it with no
            //     retry-count gate at all.
            // ONE 10-SECOND STALL THEREFORE COST A WHOLE TRACK, on the first occurrence, at every layer.
            // Three of those tripped the consecutive-skip breaker and stopped playback - measured, 2 users
            // x 4 reports, identical shape every time (timeout -> StuckBuffering -> StuckBuffering).
            //
            // ⚠⚠ WHY THE TYPE SPLIT IS RELIABLE AND NOT A HEURISTIC. media3 cancels a load by
            // THREAD INTERRUPT - Loader.java:409-428 does `loadable.cancelLoad(); executorThread
            // .interrupt()` - and runBlocking turns an interrupt into an InterruptedException, not a
            // cancellation: kotlinx-coroutines-core-jvm 1.11.0, jvmMain/Builders.kt:58, is
            // `if (Thread.interrupted()) cancelCoroutine(InterruptedException())`. This runBlocking also
            // has no parent Job, so nothing outside can cancel its coroutine. TimeoutCancellationException
            // is constructed ONLY by kotlinx's timeout machinery, so it can only have come from a
            // withTimeout INSIDE the provider. Real loader cancellation takes the InterruptedException arm
            // below, untouched, exactly as before.
            //
            // ⚠️ IT MUST PRECEDE THE CancellationException ARM: TimeoutCancellationException is a
            // SUBCLASS of it, so placed after, this arm is unreachable.
            // ⚠️ SocketTimeoutException RATHER THAN A BARE IOException, for two reasons: it is
            // honest about what happened, and PlayerEventListener's isTimeout / skipFamily both match that
            // type directly, so our own classification survives even if the cause chain is flattened. The
            // TimeoutCancellationException is kept as the CAUSE - verified safe, because the policy's
            // chain walk above tests for ParserException / FileNotFoundException /
            // CleartextNotPermittedException / UnexpectedLoaderException / position-out-of-range
            // DataSourceException, and a TimeoutCancellationException is none of those.
            // ⚠️ A TIMEOUT RACING A REAL CANCELLATION IS DISCARDED, NOT MISHANDLED: run()'s
            // IOException arm is gated on `if (!released)`, and handleMessage (:512-517) checks
            // `if (canceled)` and calls onLoadCanceled BEFORE the MSG_IO_EXCEPTION switch.
            //
            // ⚠⚠ [AMENDED 2026-10-03] THIS ARM IS NOW THE BELT, NOT THE MECHANISM, AND THAT
            // CHANGE IS WHY getContentLength's BACKSTOP MOVED TO 30s. At 10s our coroutine timeout
            // always beat okhttp's own readTimeout(10s) - it starts earlier, before connection acquire
            // and request write - and a coroutine cancel arms NO degraded ping, so a half-open HTTP/2
            // connection stayed pooled and every retry reused it. Raising the backstop above okhttp's
            // ceiling lets okhttp's stream timeout fire instead, which evicts the connection AND
            // arrives as a SocketTimeoutException - an IOException, so it takes the FIRST arm of this
            // when, not this one. Full derivation at Utils.getContentLength.
            // ⚠️ SO EXPECT THIS ARM TO FIRE RARELY OR NEVER NOW, and do not read that as dead
            // code: it still covers any extension whose provider runs its own withTimeout, and it is
            // the only thing standing between such a timeout and a non-retriable
            // UnexpectedLoaderException.
            throw SocketTimeoutException("Stream open timed out").apply { initCause(e) }
        } catch (e: CancellationException) {
            throw e                     // loader cancellation must propagate untouched (not an error)
        } catch (e: InterruptedException) {
            throw e                     // loader-thread interrupt must propagate untouched
        } catch (e: Exception) {
            // A failed extension stream-resolve throws AppException (wrapping the real UnknownHostException),
            // a NON-IOException. DataSource.open() is contracted to throw IOException, and Media3's Loader only
            // treats an IOException as a RECOVERABLE load error (→ onLoadError → onPlayerError → the existing
            // retry/error-skip). A non-IOException escapes uncaught and crashes (build-1013 fatal DNS crash).
            // Rewrap as IOException, preserving the real cause, so a Raw-stream network/DNS failure degrades
            // exactly like the HTTP DataSource path instead of crashing. (Error, e.g. OOM, still propagates.)
            throw IOException(e)
        }
        Log.d("GladixPlayback", "RawDataSource.open: stream ready total=$total pos=${dataSpec.position}")
        uri = dataSpec.uri
        stream = source
        return total
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        return stream!!.read(buffer, offset, length)
    }

    override fun getUri() = uri

    override fun close() {
        stream?.close()
        stream = null
        uri = null
    }
}