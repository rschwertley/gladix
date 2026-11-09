package dev.brahmkshatriya.echo.playback

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaFormat
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.MediaFormatUtil
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.source.BundledExtractorsAdapter
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.DiscardingTrackOutput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.log10
import kotlin.math.max

/**
 * [2026-11-08] Where a song's music really ends, found before its crossfade from its own last [SCAN_MS], so the crossfade can end
 * there instead of at the end of the file (CrossfadeController.maybeStartEndAnalysis). A song with a quiet or silent ending
 * otherwise let the music stop seconds into the overlap: Wild Thing (Re-Recorded) went below -60 dBFS 3.8 s into a 10 s
 * crossfade on 1174. Same idea as foobar2000's Advanced Crossfader end cues ("by sound level in dB").
 *
 * READ THE SAME WAY THE TAIL IS, and with the player's own extractors, so the answer is in the player's timeline:
 * - The bytes come through CacheOnlyTailSource.dataSourceFactory, the tail's path (decrypted Deezer, downloads from their
 *   file, Http sources from the cache or their URL).
 * - BundledExtractorsAdapter with DefaultExtractorsFactory is what ProgressiveMediaPeriod uses for every track here (no
 *   extractor flags are set anywhere in the app), so the seek map, and with it "media position X", is the player's own.
 *   That matters most for an MP3 with no index (constant-bitrate estimate) and for FLAC.
 * - MediaCodec with the format MediaFormatUtil builds is how MediaCodecAudioRenderer decodes these same formats.
 *
 * DETECTION, the user's decision of 2026-11-08: RMS in [BLOCK_MS] blocks; the threshold is [RELATIVE_DB] below the 95th
 * percentile block of the scanned window, never below [FLOOR_DB]. Relative because what matters is when the old song stops
 * mattering under the new one, and songs differ in level by 20 dB or more; the floor keeps a quiet recording from being cut
 * at its music. The audible end is the end of the last block at or above the threshold. Only the last [SCAN_MS] is read, so a
 * hidden track after a longer gap is never reached. The minimum move and the cap are the caller's (CrossfadeController).
 *
 * Returns null, meaning "keep today's window", for anything undecided: no seek map, a format the platform cannot decode, a
 * read or decoder error, a decode that stops well short of the duration, or no block above the threshold at all.
 */
@OptIn(UnstableApi::class)
object AudibleEndAnalyzer {
    const val SCAN_MS = 15_000L
    private const val BLOCK_MS = 50L
    private const val RELATIVE_DB = 40.0
    private const val FLOOR_DB = -60.0

    // A decoder's first output after a seek can be pre-roll (Opus) or partial; not counted.
    private const val SKIP_HEAD_MS = 200L

    // The decode must reach this close to the duration, or the silence at the end could be missing data.
    private const val COVERAGE_SLACK_MS = 500L
    private const val DEQUEUE_TIMEOUT_US = 10_000L

    // Waits of DEQUEUE_TIMEOUT_US for the decoder's end of stream once it is queued (~5 s), then give up (null).
    private const val MAX_END_WAITS = 500

    /** The audible end in media ms, or null when undecided. Runs on IO; cancellable between reads. */
    suspend fun audibleEndMs(dataSourceFactory: DataSource.Factory, uri: Uri, durationMs: Long): Long? =
        withContext(Dispatchers.IO) {
            runCatching { measure(dataSourceFactory, uri, durationMs) }.getOrElse {
                if (it is CancellationException) throw it
                null
            }
        }

    private suspend fun measure(dataSourceFactory: DataSource.Factory, uri: Uri, durationMs: Long): Long? {
        if (durationMs <= SCAN_MS) return null
        val startMs = durationMs - SCAN_MS
        val blocks = Blocks(startMs)
        val output = Output()
        val adapter = BundledExtractorsAdapter(DefaultExtractorsFactory())
        val source = dataSourceFactory.createDataSource()
        var codec: MediaCodec? = null
        try {
            fun openAt(position: Long) {
                runCatching { source.close() }
                val length = source.open(DataSpec.Builder().setUri(uri).setPosition(position).build())
                val end = if (length == C.LENGTH_UNSET.toLong()) C.LENGTH_UNSET.toLong() else position + length
                adapter.init(source, uri, source.responseHeaders, position, end, output)
            }
            openAt(0)
            val holder = PositionHolder()
            var seeked = false
            var inputDone = false
            val info = MediaCodec.BufferInfo()
            var pcm = Pcm()
            var endWaits = 0
            while (true) {
                coroutineContext.ensureActive()
                if (!seeked) {
                    val seekMap = output.seekMap
                    val format = output.format
                    if (seekMap != null && format != null) {
                        if (!seekMap.isSeekable) return null
                        val mime = format.sampleMimeType ?: return null
                        if (!MimeTypes.isAudio(mime)) return null
                        codec = MediaCodec.createDecoderByType(mime).also {
                            it.configure(MediaFormatUtil.createMediaFormatFromFormat(format), null, null, 0)
                            it.start()
                        }
                        val point = seekMap.getSeekPoints(startMs * 1000).first
                        openAt(point.position)
                        adapter.seek(point.position, startMs * 1000)
                        output.startCollecting()
                        seeked = true
                    }
                }
                if (!inputDone) {
                    when (adapter.read(holder)) {
                        Extractor.RESULT_SEEK -> openAt(holder.position)
                        Extractor.RESULT_END_OF_INPUT -> inputDone = true
                    }
                }
                val decoder = codec
                if (decoder == null) {
                    if (inputDone) return null
                    continue
                }
                // Feed every finished sample, then the end of stream once the extractor is done.
                while (true) {
                    val sample = output.samples.firstOrNull()
                    val endNow = sample == null && inputDone && !output.endQueued
                    if (sample == null && !endNow) break
                    val index = decoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (index < 0) break
                    if (sample != null) {
                        decoder.getInputBuffer(index)!!.apply { clear(); put(sample.second) }
                        decoder.queueInputBuffer(index, 0, sample.second.size, sample.first, 0)
                        output.samples.removeFirst()
                    } else {
                        decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        output.endQueued = true
                    }
                }
                // Drain whatever is decoded.
                var ended = false
                while (true) {
                    val index = decoder.dequeueOutputBuffer(info, if (output.endQueued) DEQUEUE_TIMEOUT_US else 0)
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        pcm = Pcm(decoder.outputFormat)
                        continue
                    }
                    if (index < 0) {
                        if (output.endQueued && ++endWaits > MAX_END_WAITS) return null
                        break
                    }
                    if (pcm.rate <= 0) pcm = Pcm(decoder.outputFormat)
                    val buffer = decoder.getOutputBuffer(index)
                    if (buffer != null && info.size > 0) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        blocks.add(buffer.slice().order(ByteOrder.nativeOrder()), info.presentationTimeUs, pcm)
                    }
                    decoder.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        ended = true
                        break
                    }
                }
                if (ended) break
            }
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { adapter.release() }
            runCatching { source.close() }
        }
        if (blocks.lastFrameEndMs < durationMs - COVERAGE_SLACK_MS) return null
        return blocks.audibleEndMs()
    }

    private class Pcm(format: MediaFormat? = null) {
        val rate = format?.getInteger(MediaFormat.KEY_SAMPLE_RATE) ?: 0
        val channels = format?.getInteger(MediaFormat.KEY_CHANNEL_COUNT) ?: 0
        val float = format?.takeIf { it.containsKey(MediaFormat.KEY_PCM_ENCODING) }
            ?.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
    }

    /** Mean square per [BLOCK_MS] block of the scanned window, from the decoder's output timestamps. */
    private class Blocks(private val startMs: Long) {
        private val count = ((SCAN_MS + BLOCK_MS - 1) / BLOCK_MS).toInt() + 1
        private val squares = DoubleArray(count)
        private val samples = LongArray(count)
        var lastFrameEndMs = 0L
            private set

        fun add(buffer: ByteBuffer, timeUs: Long, pcm: Pcm) {
            if (pcm.rate <= 0 || pcm.channels <= 0) return
            val bytesPerSample = if (pcm.float) 4 else 2
            val frames = buffer.remaining() / (bytesPerSample * pcm.channels)
            for (frame in 0 until frames) {
                val tMs = timeUs / 1000 + frame * 1000L / pcm.rate
                val block = ((tMs - startMs) / BLOCK_MS).toInt()
                val use = tMs >= startMs + SKIP_HEAD_MS && block in 0 until count
                for (ch in 0 until pcm.channels) {
                    val v = if (pcm.float) buffer.float.toDouble() else buffer.short / 32768.0
                    if (use) {
                        squares[block] += v * v
                        samples[block]++
                    }
                }
            }
            lastFrameEndMs = max(lastFrameEndMs, timeUs / 1000 + frames * 1000L / pcm.rate)
        }

        fun audibleEndMs(): Long? {
            val levels = (0 until count).filter { samples[it] > 0 }.map {
                it to 10 * log10(max(squares[it] / samples[it], 1e-12))
            }
            if (levels.isEmpty()) return null
            val sorted = levels.map { it.second }.sorted()
            val reference = sorted[((sorted.size - 1) * 0.95).toInt()]
            val threshold = max(reference - RELATIVE_DB, FLOOR_DB)
            val last = levels.lastOrNull { it.second >= threshold }?.first ?: return null
            return startMs + (last + 1) * BLOCK_MS
        }
    }

    /** The first audio track's samples, whole, once collecting starts (after the seek). Other tracks are discarded. */
    private class Output : ExtractorOutput, TrackOutput {
        var seekMap: SeekMap? = null
        var format: Format? = null
        val samples = ArrayDeque<Pair<Long, ByteArray>>()
        var endQueued = false
        private var collecting = false
        private var audioTrackId: Int? = null
        private var data = ByteArray(64 * 1024)
        private var size = 0

        fun startCollecting() {
            collecting = true
            size = 0
        }

        override fun track(id: Int, type: Int): TrackOutput {
            if (type != C.TRACK_TYPE_AUDIO) return DiscardingTrackOutput()
            val audio = audioTrackId ?: id.also { audioTrackId = it }
            return if (audio == id) this else DiscardingTrackOutput()
        }

        override fun endTracks() {}

        override fun seekMap(seekMap: SeekMap) {
            this.seekMap = seekMap
        }

        override fun format(format: Format) {
            this.format = format
        }

        private fun ensure(extra: Int) {
            if (size + extra > data.size) data = data.copyOf(max(data.size * 2, size + extra))
        }

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            ensure(length)
            val read = input.read(data, size, length)
            if (read == C.RESULT_END_OF_INPUT) {
                if (allowEndOfInput) return C.RESULT_END_OF_INPUT
                throw EOFException()
            }
            size += read
            return read
        }

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            ensure(length)
            data.readBytes(this.data, size, length)
            size += length
        }

        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            val end = this.size - offset
            val start = end - size
            if (collecting && start >= 0) samples.addLast(timeUs to data.copyOfRange(start, end))
            // Keep only the bytes after this sample (the next sample's, already written).
            if (end in 0..this.size) {
                System.arraycopy(data, end, data, 0, this.size - end)
                this.size -= end
            }
        }
    }
}
