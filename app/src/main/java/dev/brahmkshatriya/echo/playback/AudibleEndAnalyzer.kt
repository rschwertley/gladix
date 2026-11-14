package dev.brahmkshatriya.echo.playback

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
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
 * [CORRECTED 2026-11-13] "Only the last [SCAN_MS] is read" holds only while that window has audio in it. An all-silent one is
 * now followed by longer windows, up to [MAX_TAIL_SCAN_MS] (the user's decision), so a song ending in 15-60 s of silence gets
 * the end of its music. A hidden track whose audio is inside the last [SCAN_MS] is still the end, as before.
 *
 * Returns null, meaning "keep today's window", for anything undecided: no seek map, a format the platform cannot decode, a
 * read or decoder error, a decode that stops well short of the duration, or no block above the threshold at all.
 *
 * [2026-11-10] ALSO THE START LOOK-AHEAD ([startShape]): the same decode from byte 0 over the first [HEAD_SCAN_MS] of the
 * NEXT song, to find where its music starts and how soon it is near its level (CrossfadeController.startRamps). Read only
 * from what is already committed to the cache (CacheOnlyTailSource.head), never the network. Same blocks and threshold
 * rule; no seek, so no seek map is needed.
 */
@OptIn(UnstableApi::class)
object AudibleEndAnalyzer {
    const val SCAN_MS = 15_000L

    /** [2026-11-10] The start look-ahead's window (the user's decision): fewer than this decodable gives null. */
    const val HEAD_SCAN_MS = 5_000L

    /**
     * [2026-11-13] THE LONGER LOOK-AHEADS, the user's decisions of 2026-11-13. A tail whose last [SCAN_MS] is all silent is
     * scanned again further back, [SCAN_MS] more at a time, up to [MAX_TAIL_SCAN_MS] before the end of the file
     * ([End.noAudio] when nothing is found). A head with no onset in [HEAD_SCAN_MS] is scanned again over [MAX_HEAD_SCAN_MS]
     * (a [Start] with no onset then means none in 15 s). Each longer scan is a new decode of its whole window, so a song
     * whose answer is in the first window costs exactly what it did before.
     */
    const val MAX_TAIL_SCAN_MS = 60_000L
    const val MAX_HEAD_SCAN_MS = 15_000L
    private const val BLOCK_MS = 50L
    private const val RELATIVE_DB = 40.0
    private const val FLOOR_DB = -60.0

    // [2026-11-11] The pair rule's levels (CrossfadeController.classifyPair, the user's decision): the outgoing over the last
    // 3 s before its audible end, the incoming from its onset over 3 s, at least 1 s of it inside the head window.
    // [CORRECTED 2026-11-13] The pair rule is removed (the user's decision; see the note after CrossfadeController.shapeFor).
    // These levels are still measured, for the CROSSFADE-FEEL line's outDb and inDb only. End.bodyDb and End.fadeMs (the long
    // fade-out, 12 dB below the body, for the LONG_FADE row) are removed with their computation (the user's decision).
    private const val LEVEL_WINDOW_MS = 3_000L
    private const val MIN_LEVEL_MS = 1_000L

    // A decoder's first output after a seek can be pre-roll (Opus) or partial; not counted.
    private const val SKIP_HEAD_MS = 200L

    // The decode must reach this close to the duration, or the silence at the end could be missing data.
    private const val COVERAGE_SLACK_MS = 500L
    private const val DEQUEUE_TIMEOUT_US = 10_000L

    // Waits of DEQUEUE_TIMEOUT_US for the decoder's end of stream once it is queued (~5 s), then give up (null).
    private const val MAX_END_WAITS = 500

    /**
     * [2026-11-11] The end look-ahead's result. [audibleEndMs] as before; [endDb] the average power (RMS dBFS, raw PCM) over
     * the last [LEVEL_WINDOW_MS] before it, null with nothing measured there; bodyDb the 15 s window's 95th-percentile block.
     * [2026-11-13] fadeMs: from the end of the last block within FADE_BELOW_BODY_DB of the body to the audible end, the
     * length of a fade-out (0 when the music stays near its body to the end).
     * [CORRECTED 2026-11-13] bodyDb and fadeMs are removed: only the pair rule read them, and it is removed. The 95th-percentile
     * block is still computed in [Blocks.end], for the audible-end threshold. [scannedMs]: the window that found the end,
     * [SCAN_MS] unless the last [SCAN_MS] was all silent. [noAudio]: nothing audible within [MAX_TAIL_SCAN_MS] of the end (or
     * in the whole song, if shorter); the other fields then mean nothing.
     */
    class End(
        val audibleEndMs: Long, val endDb: Double?, val scannedMs: Long = SCAN_MS, val noAudio: Boolean = false,
    )

    /** The audible end, or null when undecided. Runs on IO; cancellable between reads. */
    suspend fun audibleEnd(dataSourceFactory: DataSource.Factory, uri: Uri, durationMs: Long): End? =
        withContext(Dispatchers.IO) {
            runCatching { measure(dataSourceFactory, uri, durationMs) }.getOrElse {
                if (it is CancellationException) throw it
                null
            }
        }

    /**
     * [2026-11-10] Where the music starts in the first [HEAD_SCAN_MS] of a song, and how soon after that it is near its level.
     * [onsetMs] null = nothing above the threshold in the window; riseMs null = never near its level after the onset.
     * referenceDb is the window's 95th-percentile block, RMS dBFS.
     * [2026-11-11] [levelDb] is the average power from the onset over [LEVEL_WINDOW_MS], clipped to the window; null with no
     * onset or less than [MIN_LEVEL_MS] of it inside the window. Raw PCM, like every level here.
     * [CORRECTED 2026-11-13] riseMs and referenceDb are removed, with the rise's computation and NEAR_LEVEL_DB: only
     * CrossfadeController.incomingFadeMs read them, and it is removed with the pair rule (the user's decision). The
     * 95th-percentile block is still computed in [Blocks.start], for the onset threshold. [onsetMs] (the onset, and the
     * silent-intro decline when null) and [levelDb] (the CROSSFADE-FEEL line's inDb) remain.
     */
    class Start(val onsetMs: Long?, val levelDb: Double? = null)

    /**
     * The start of the song at [uri], or null when undecided: fewer than [HEAD_SCAN_MS] decodable from the first
     * [boundLength] bytes ([C.LENGTH_UNSET] = to the end), a format the platform cannot decode, or no audio at all. Runs on
     * IO; cancellable between reads.
     */
    suspend fun startShape(dataSourceFactory: DataSource.Factory, uri: Uri, boundLength: Long): Start? =
        withContext(Dispatchers.IO) {
            runCatching { measureStart(dataSourceFactory, uri, boundLength) }.getOrElse {
                if (it is CancellationException) throw it
                null
            }
        }

    // [2026-11-13] The last SCAN_MS as before; only when all of it is silent, windows of 30, 45 and 60 s (MAX_TAIL_SCAN_MS),
    // each decoded from its start to the end of the file. Audio inside the last SCAN_MS is the end, as before, so a hidden
    // track after a gap is still the end. A window reaching the start of the song is the last one tried.
    private suspend fun measure(dataSourceFactory: DataSource.Factory, uri: Uri, durationMs: Long): End? {
        if (durationMs <= SCAN_MS) return null
        var windowMs = SCAN_MS
        while (true) {
            val blocks = Blocks(durationMs - windowMs, windowMs, SKIP_HEAD_MS)
            decode(dataSourceFactory, uri, blocks, head = false, boundLength = C.LENGTH_UNSET.toLong()) ?: return null
            if (blocks.lastFrameEndMs < durationMs - COVERAGE_SLACK_MS) return null
            if (!blocks.allSilent()) return blocks.end()?.let {
                End(it.audibleEndMs, it.endDb, scannedMs = windowMs)
            }
            if (windowMs >= MAX_TAIL_SCAN_MS || windowMs >= durationMs) {
                return End(durationMs - windowMs, null, scannedMs = windowMs, noAudio = true)
            }
            windowMs = minOf(windowMs + SCAN_MS, MAX_TAIL_SCAN_MS, durationMs)
        }
    }

    // [2026-11-13] HEAD_SCAN_MS as before; only with no onset there, once more over MAX_HEAD_SCAN_MS. Undecided (null) when that
    // much is not decodable yet, so the caller tries again once more of the head is committed.
    private suspend fun measureStart(dataSourceFactory: DataSource.Factory, uri: Uri, boundLength: Long): Start? {
        val first = measureStart(dataSourceFactory, uri, boundLength, HEAD_SCAN_MS) ?: return null
        if (first.onsetMs != null) return first
        return measureStart(dataSourceFactory, uri, boundLength, MAX_HEAD_SCAN_MS)
    }

    private suspend fun measureStart(
        dataSourceFactory: DataSource.Factory, uri: Uri, boundLength: Long, windowMs: Long,
    ): Start? {
        val blocks = Blocks(0L, windowMs, 0L)
        val format = decode(dataSourceFactory, uri, blocks, head = true, boundLength = boundLength) ?: return null
        if (blocks.lastFrameEndMs < windowMs) return null
        // The decoder's output starts with the encoder's priming frames, which the player's sink trims; ours does not.
        val delayMs = if (format.encoderDelay > 0 && format.sampleRate > 0)
            format.encoderDelay * 1000L / format.sampleRate else 0L
        return blocks.start(delayMs)
    }

    /**
     * The shared decode loop, filling [blocks]. Returns the format the decoder was configured with, or null when undecided.
     * Tail (head = false): seeks to [Blocks.startMs] with the seek map and decodes to the end of the file. Head: decodes from
     * byte 0 with no seek and stops once [Blocks.endMs] is decoded; the extractor is not told the length, so one that looks
     * for the end of the file first (Ogg) does not; a read past [boundLength], or an error, ends the input there (the end
     * of what is committed can cut a frame). A head whose extractor must seek past the bound (an MP4 with its index at the
     * end) gets nothing and is undecided.
     */
    private suspend fun decode(
        dataSourceFactory: DataSource.Factory, uri: Uri, blocks: Blocks, head: Boolean, boundLength: Long,
    ): Format? {
        val output = Output()
        if (head) output.startCollecting()
        val adapter = BundledExtractorsAdapter(DefaultExtractorsFactory())
        val source = dataSourceFactory.createDataSource()
        val bounded = boundLength != C.LENGTH_UNSET.toLong()
        var codec: MediaCodec? = null
        var codecFormat: Format? = null
        try {
            fun openAt(position: Long): Boolean {
                runCatching { source.close() }
                if (bounded && position >= boundLength) return false
                val spec = DataSpec.Builder().setUri(uri).setPosition(position)
                if (bounded) spec.setLength(boundLength - position)
                val length = source.open(spec.build())
                val end = if (head || length == C.LENGTH_UNSET.toLong()) C.LENGTH_UNSET.toLong() else position + length
                adapter.init(source, uri, source.responseHeaders, position, end, output)
                return true
            }
            if (!openAt(0)) return null
            val holder = PositionHolder()
            var inputDone = false
            val info = MediaCodec.BufferInfo()
            var pcm = Pcm()
            var endWaits = 0
            while (true) {
                coroutineContext.ensureActive()
                if (codec == null) {
                    val seekMap = output.seekMap
                    val format = output.format
                    if (format != null && (head || seekMap != null)) {
                        if (!head && seekMap?.isSeekable != true) return null
                        val mime = format.sampleMimeType ?: return null
                        if (!MimeTypes.isAudio(mime)) return null
                        codec = openSoftwareDecoder(format, mime) ?: return null
                        codecFormat = format
                        if (!head && seekMap != null) {
                            val startUs = blocks.startMs * 1000
                            val point = seekMap.getSeekPoints(startUs).first
                            openAt(point.position)
                            adapter.seek(point.position, startUs)
                            output.startCollecting()
                        }
                    }
                }
                if (!inputDone) {
                    val result = try {
                        adapter.read(holder)
                    } catch (e: Exception) {
                        if (!head) throw e
                        Extractor.RESULT_END_OF_INPUT
                    }
                    when (result) {
                        Extractor.RESULT_SEEK -> if (!openAt(holder.position)) inputDone = true
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
                if (head && blocks.lastFrameEndMs >= blocks.endMs) break
            }
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { adapter.release() }
            runCatching { source.close() }
        }
        return codecFormat
    }

    /**
     * [2026-11-11] THE ANALYSIS DECODER IS SOFTWARE ONLY, the user's decision, for both look-aheads. Many phones decode MP3
     * and AAC in hardware with a small instance limit, and when it is reached the newest instance fails to start
     * (CodecException, ERROR_INSUFFICIENT_RESOURCE). The start look-ahead can run beside main's decoder and the sidecar's,
     * so a hardware analysis decoder could be the one that takes the last slot, or stop the sidecar from getting one. So:
     * the first decoder for [mime] that is software only (MediaCodecInfo.isSoftwareOnly from API 29; by name, c2.android.*
     * or OMX.google.*, below it) and accepts this rate and channel count, created by name. None, or any failure to create,
     * configure or start it, gives null, so the caller keeps today's fade or window. Never a hardware fallback. Released
     * here on failure, and by [decode]'s finally otherwise.
     */
    private fun openSoftwareDecoder(format: Format, mime: String): MediaCodec? {
        val probe = MediaFormat.createAudioFormat(mime, format.sampleRate, format.channelCount)
        val info = runCatching {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.firstOrNull { info ->
                !info.isEncoder && isSoftwareOnly(info) &&
                    info.supportedTypes.any { it.equals(mime, ignoreCase = true) } &&
                    (format.sampleRate <= 0 || format.channelCount <= 0 ||
                        info.getCapabilitiesForType(mime).isFormatSupported(probe))
            }
        }.getOrNull() ?: return null
        var codec: MediaCodec? = null
        return try {
            codec = MediaCodec.createByCodecName(info.name)
            codec.configure(MediaFormatUtil.createMediaFormatFromFormat(format), null, null, 0)
            codec.start()
            codec
        } catch (e: Exception) {
            // CodecException (an IllegalStateException), IllegalArgumentException, IllegalStateException, IOException.
            runCatching { codec?.release() }
            null
        }
    }

    private fun isSoftwareOnly(info: MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.isSoftwareOnly
        else info.name.startsWith("OMX.google.") || info.name.startsWith("c2.android.")

    private class Pcm(format: MediaFormat? = null) {
        val rate = format?.getInteger(MediaFormat.KEY_SAMPLE_RATE) ?: 0
        val channels = format?.getInteger(MediaFormat.KEY_CHANNEL_COUNT) ?: 0
        val float = format?.takeIf { it.containsKey(MediaFormat.KEY_PCM_ENCODING) }
            ?.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
    }

    /**
     * Mean square per [BLOCK_MS] block of the scanned window ([startMs] for [windowMs]), from the decoder's output timestamps.
     * Frames before [skipHeadMs] into the window are not counted (the tail's post-seek pre-roll; 0 for the head).
     */
    private class Blocks(val startMs: Long, windowMs: Long, private val skipHeadMs: Long) {
        val endMs = startMs + windowMs
        private val count = ((windowMs + BLOCK_MS - 1) / BLOCK_MS).toInt() + 1
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
                val use = tMs >= startMs + skipHeadMs && block in 0 until count
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

        private fun levels() = (0 until count).filter { samples[it] > 0 }.map {
            it to 10 * log10(max(squares[it] / samples[it], 1e-12))
        }

        private fun referenceOf(levels: List<Pair<Int, Double>>): Double {
            val sorted = levels.map { it.second }.sorted()
            return sorted[((sorted.size - 1) * 0.95).toInt()]
        }

        fun end(): End? {
            val levels = levels()
            if (levels.isEmpty()) return null
            val reference = referenceOf(levels)
            val threshold = max(reference - RELATIVE_DB, FLOOR_DB)
            val last = levels.lastOrNull { it.second >= threshold }?.first ?: return null
            val window = (LEVEL_WINDOW_MS / BLOCK_MS).toInt()
            return End(
                audibleEndMs = startMs + (last + 1) * BLOCK_MS,
                endDb = meanDb(last - window + 1, last),
            )
        }

        /** [2026-11-13] Blocks were measured and none is above the threshold: the whole window is silent. */
        fun allSilent(): Boolean {
            val levels = levels()
            if (levels.isEmpty()) return false
            val threshold = max(referenceOf(levels) - RELATIVE_DB, FLOOR_DB)
            return levels.none { it.second >= threshold }
        }

        /**
         * [2026-11-11] Average power in dB over blocks [from]..[to] (clipped to the window): total squares over total samples,
         * so loud blocks weigh as they sound, not an average of dB values. Null with no samples in the range.
         */
        fun meanDb(from: Int, to: Int): Double? {
            var squareSum = 0.0
            var sampleSum = 0L
            for (block in max(from, 0)..minOf(to, count - 1)) {
                squareSum += squares[block]
                sampleSum += samples[block]
            }
            if (sampleSum == 0L) return null
            return 10 * log10(max(squareSum / sampleSum, 1e-12))
        }

        /** [2026-11-11] Blocks from [from] that hold samples, up to [to] (clipped). */
        fun filledBlocks(from: Int, to: Int): Int =
            (max(from, 0)..minOf(to, count - 1)).count { samples[it] > 0 }

        /**
         * [2026-11-10] The onset: the first block at or above the same threshold as the end's whose next block is too (so a
         * click alone is not one). The rise: from the onset to the first block within NEAR_LEVEL_DB of the reference.
         * [CORRECTED 2026-11-13] The rise is no longer computed; see the note at [Start].
         */
        fun start(encoderDelayMs: Long): Start? {
            val levels = levels()
            if (levels.isEmpty()) return null
            val reference = referenceOf(levels)
            val threshold = max(reference - RELATIVE_DB, FLOOR_DB)
            val byBlock = levels.toMap()
            val onset = levels.firstOrNull { (block, db) ->
                db >= threshold && (byBlock[block + 1] ?: Double.NEGATIVE_INFINITY) >= threshold
            }?.first ?: return Start(null)
            val window = (LEVEL_WINDOW_MS / BLOCK_MS).toInt()
            val enough = filledBlocks(onset, onset + window - 1) * BLOCK_MS >= MIN_LEVEL_MS
            return Start(
                onsetMs = (startMs + onset * BLOCK_MS - encoderDelayMs).coerceAtLeast(0L),
                levelDb = if (enough) meanDb(onset, onset + window - 1) else null,
            )
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
