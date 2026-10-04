package dev.brahmkshatriya.echo.playback.source

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.playback.source.StreamableResolver.Companion.copy
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@UnstableApi
class StreamableDataSource(
    private val defaultDataSourceFactory: Lazy<DefaultDataSource.Factory>,
    private val defaultHttpDataSourceFactory: Lazy<DefaultHttpDataSource.Factory>,
    private val rawDataSourceFactory: Lazy<RawDataSource.Factory>,
) : BaseDataSource(true) {

    class Factory(
        context: Context,
    ) : DataSource.Factory {
        private val defaultDataSourceFactory = lazy {
            DefaultDataSource.Factory(context, defaultHttpDataSourceFactory.value)
        }
        private val defaultHttpDataSourceFactory = lazy {
            DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
        }
        private val rawDataSourceFactory = lazy { RawDataSource.Factory() }
        override fun createDataSource() = StreamableDataSource(
            defaultDataSourceFactory, defaultHttpDataSourceFactory, rawDataSourceFactory
        )
    }

    private var source: DataSource? = null

    override fun getUri() = source?.uri

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val read = source?.read(buffer, offset, length) ?: throw Exception("Source not opened")
        // `> 0` because C.RESULT_END_OF_INPUT is -1 and a 0-length read is not progress; only real bytes
        // count, so the delta means "bytes actually delivered".
        if (read > 0) bytesRead.addAndGet(read.toLong())
        return read
    }

    override fun close() {
        source?.close()
        source = null
    }

    override fun open(dataSpec: DataSpec): Long {
        openCount.incrementAndGet()
        Log.d("GladixPlayback", "StreamableDataSource.open: ${dataSpec.uri}")
        val result = dataSpec.customData as? Result<*>
        val (factory, spec) = when (result) {
            null -> defaultDataSourceFactory to dataSpec
            else -> when (val streamable = result.getOrThrow() as Streamable.Source) {
                is Streamable.Source.Raw -> rawDataSourceFactory to
                        dataSpec.copy(uri = streamable.uri, customData = streamable)

                is Streamable.Source.Http -> {
                    val spec = streamable.request.run {
                        defaultHttpDataSourceFactory.value.setDefaultRequestProperties(headers)
                        dataSpec.copy(uri = url.toUri(), httpRequestHeaders = headers)
                    }
                    defaultDataSourceFactory to spec
                }
            }
        }
        val source = factory.value.createDataSource()
        this.source = source
        // See openInFlight below. Wrapped around the DELEGATE call, not around this whole function: the
        // factory selection above is synchronous and allocation-only, while everything that can BLOCK -
        // the Deezer HEAD, the ranged GET, an HTTP connect - is inside source.open.
        openInFlight.incrementAndGet()
        return try {
            source.open(spec)
        } finally {
            openInFlight.decrementAndGet()
            lastOpenEndMs.set(System.currentTimeMillis())
        }
    }

    companion object {
        // PROBE (2026-08-29). Counts every entry into open() above, process wide.
        // WHY A COUNTER AND NOT A LOG: ProgressiveMediaPeriod.prepare() reaches startLoading() with no
        // LoadControl, allocator or state gate in between (ProgressiveMediaPeriod:286-304 -> :1079-1102),
        // so a load starting is a faithful proxy for "prepare() was invoked on the period". Nine periods
        // were created and never opened in the 1059 captures; logcat showed it and Crashlytics could not,
        // because the main buffer rolls in minutes. PlayerEventListener diffs this against a per-item
        // snapshot and puts the delta in the consecutive-skip report, so the answer survives the buffer.
        // Process wide and static ON PURPOSE: only deltas are read, there is exactly one player process,
        // and a static needs no constructor change on either Factory. It survives service recreation,
        // which is correct - the ExoPlayer instance does too.
        // LIVES IN THIS COMPANION, not its own: Kotlin allows exactly ONE companion per class, and this
        // one already existed for Streamable.Source.uri below. Adding a second made BOTH invalid, which
        // also unresolved the uri reference in open() - the failure reads as two unrelated errors.
        // ⚠⚠ PROMOTED TO PERMANENT 2026-09-12 - THESE OUTLIVED THE PROBE THEY WERE BUILT FOR
        // AND NOW BELONG TO THE BUFFERING WATCHDOG. The note here used to read "REMOVE WITH THE PROBE. This
        // is diagnostic scaffolding, not a feature." That is no longer true of either counter:
        //   PlayerEventListener.armBufferingWatchdog snapshots both per item (openCountAtItemStart,
        //     bytesReadAtItemStart) and reports the deltas, so they are wired into a live mechanism rather
        //     than into a one-off capture;
        //   and the `opens=0 bytes=0` pair has come back POPULATED in two Crashlytics reports in one week -
        //     it is what established that a stall never made a connection at all, which no other field says.
        // A counter whose consumer is a shipped watchdog is instrumentation, not scaffolding. Do not strip
        // these with the temporary trace lines elsewhere in the tree.
        val openCount = AtomicInteger(0)

        // PROBE (2026-09-01). Total bytes DELIVERED through read(), process wide, same rationale and
        // lifetime as openCount above — diffed against a per-item snapshot by PlayerEventListener.
        // WHY: `opens` alone cannot separate the two stalls that look identical in a report. A connection
        // that opens and then delivers nothing, and a stream that opens and delivers fine while the player
        // never prepares, both produce loaded=true loads=0 buf=0 opens=2+. Bytes is the field that splits
        // them: ProgressiveMediaPeriod cannot set `prepared` until the extractor calls endTracks(), emits a
        // seekMap and gives every SampleQueue a Format (ProgressiveMediaPeriod.maybeFinishPrepare), all of
        // which are driven from inside the read loop — so bytes arriving in quantity with nothing prepared
        // puts the fault downstream of delivery, and near-zero bytes puts it at the connection.
        // Long, not Int: a single track is megabytes and this is process-wide across a session.
        // PERMANENT - see the note at openCount above. The "REMOVE WITH THE PROBE" that stood here was
        // retired 2026-09-12: the field's own rationale above (bytes is what splits the two stalls that
        // look identical in a report) is a description of a permanent diagnostic, not a temporary one.
        val bytesRead = AtomicLong(0)

        /**
         * Number of open() calls currently blocked in the delegate, process wide. NOT a probe - the
         * buffering watchdog's second suppression arm reads it (PlayerEventListener.armBufferingWatchdog,
         * OPEN_GRACE_MS). Do not remove with the diagnostic counters above it.
         *
         * ⚠⚠ IT EXISTS BECAUSE activeLoadCount CANNOT SEE A DATASOURCE-LEVEL RETRY, AND THAT
         * BLINDNESS WAS DEFEATING THE A1 FIX. activeLoadCount tracks StreamableMediaSource's RESOLVE job,
         * which has long completed by the time media3's Loader starts retrying open() - so the watchdog's
         * resolve-in-flight gate read 0, fired at BUFFERING_WATCHDOG_MS (5s), and did stop() + prepare(),
         * which tears the Loader down and resets its errorCount to 0. media3 would never have reached its
         * second attempt. This counter is the only signal that an open is pending.
         * ⚠️ A COUNTER RATHER THAN A FLAG, because concurrent opens are normal (ExoPlayer prepares
         * the next period while the current one plays), and a flag would be cleared by whichever finished
         * first while another was still blocked.
         * ⚠️ INCREMENT-THEN-try/finally, NOT try/finally-around-both: the increment has no
         * suspension or throw between it and the try, so the finally cannot be skipped.
         */
        val openInFlight = AtomicInteger(0)

        /**
         * Epoch ms at which the most recent open() call RETURNED OR THREW, 0 if none has yet. Read with
         * [openInFlight] by the buffering watchdog's second suppression arm.
         *
         * ⚠⚠ IT EXISTS FOR THE GAPS BETWEEN MEDIA3's RETRIES, WHICH ARE INVISIBLE TO
         * openInFlight. DefaultLoadErrorHandlingPolicy.getRetryDelayMsFor is
         * min((errorCount - 1) * 1000, 5000), so between attempts the Loader sleeps with NO open
         * outstanding and openInFlight reads 0. The watchdog ticks every BUFFERING_WATCHDOG_MS (5s), so
         * a tick landing in one of those gaps would see an idle counter, fall through, and tear down the
         * very ladder the arm exists to protect - roughly 3s of gap in a ~43s ladder, i.e. it would
         * misfire a small fraction of the time, silently, reproducing the exact defect being fixed.
         * ⚠️ A TIMESTAMP RATHER THAN A SECOND COUNTER, because the question is "was an open
         * active RECENTLY", which a monotonic count cannot answer without the reader keeping its own
         * previous sample - and the watchdog Job is recreated on every arm, so it has nowhere to keep
         * one.
         * Stamped in the finally beside the decrement, so it covers a throw as well as a return.
         */
        val lastOpenEndMs = AtomicLong(0L)

        val Streamable.Source.uri
            get() = when (this) {
                is Streamable.Source.Http -> request.url.toUri()
                is Streamable.Source.Raw -> "raw://${id.hashCode()}".toUri()
            }
    }
}
