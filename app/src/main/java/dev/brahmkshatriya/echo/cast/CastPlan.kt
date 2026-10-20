package dev.brahmkshatriya.echo.cast

import dev.brahmkshatriya.echo.common.models.Streamable

/**
 * How one source reaches the receiver, and with what content type. Decided once per load; everything
 * downstream - [CastMediaItemBuilder], LocalMediaServer, the prefetch - reads the decision rather than
 * re-deriving it.
 *
 * ⚠⚠ FOUR ROUTES, AND WHICH ONE A SOURCE TAKES IS A PROPERTY OF THE SOURCE, NOT A PREFERENCE.
 *   DIRECT        plain Http with no headers: the receiver fetches the origin itself. No proxy at all,
 *                 so no phone bandwidth, no battery, and nothing to go wrong in our server.
 *   RELAY         Http WITH headers: the receiver cannot send them, so the phone fetches upstream with
 *                 the headers and relays status/headers/bytes. Upstream gives the real Content-Length,
 *                 so playback starts immediately - no copy.
 *   FILE          an already-local file (a download): served straight off disk with a real length.
 *   MATERIALISE   Raw only: no length until the whole stream is read, so it is copied first. See
 *                 CastStreamStore for why this is the one case that must wait.
 *   REFUSED       the source cannot be cast at all; the reason is shown to the user and logged.
 *
 * ⚠⚠ WIDEVINE IS REFUSED, NOT MATERIALISED, AND THAT CORRECTS AN EARLIER PLAN. Copying a
 * Widevine-protected stream to a file copies the CIPHERTEXT - our chain only applies DRM at the
 * decoder, not in the DataSource - so the receiver would be handed bytes it cannot decode and would
 * fail exactly as if the URL were broken. There is no local-proxy shape that fixes it: the receiver
 * would need the licence, which is the extension's to hold. So it is refused up front with a message
 * instead of failing opaquely on the TV.
 * ⚠️ HLS AND DASH WITH HEADERS ARE REFUSED FOR A DIFFERENT REASON: a manifest names its own
 * segment URLs, which the receiver resolves against the manifest's host. Relaying the manifest without
 * rewriting every segment URL to point back at us gives the receiver URLs it will fetch WITHOUT the
 * headers, so the segments 403. Manifest rewriting is the real fix and is not built; without headers
 * they go DIRECT and work, which is the common case.
 */
sealed class CastPlan {

    abstract val mime: String

    data class Direct(val url: String, override val mime: String) : CastPlan()
    data class Relay(
        val url: String, val headers: Map<String, String>, override val mime: String,
    ) : CastPlan()

    data class File(val path: String, override val mime: String) : CastPlan()
    data class Materialise(val key: String, override val mime: String) : CastPlan()
    data class Refused(val reason: Reason) : CastPlan() {
        override val mime get() = ""
    }

    enum class Reason { DRM, MANIFEST_WITH_HEADERS, UNKNOWN_SOURCE }

    companion object {

        /**
         * ⚠⚠ THE MIME LADDER, IN THIS ORDER, AND THE ORDER IS THE POINT. The receiver decides
         * whether it can play an item from the declared type alone, so a guess that is merely plausible
         * fails the load with no diagnostic. Each rung below is a FACT about the source; only the last
         * is a guess:
         *   1. SourceType - Http carries `type`, so HLS and DASH are KNOWN, never inferred. This is the
         *      rung that makes casting work for extensions that stream manifests.
         *   2. the URI's extension - Http.id IS request.url, so this is available before opening
         *      anything, and it is what identifies a downloaded file://....mp3.
         *   3. the container's magic bytes - only available once something has been read, so it is
         *      passed in by the caller when a file exists (CastStreamStore.headerBytes). This is the
         *      ONLY real signal a Raw source has: Raw has no uri, no type and no extension.
         *   4. the extension's own label - what the old implementation used as its ONLY input, which is
         *      why it worked for Deezer ("320kbps", ":flac") and nothing else. Last resort, and audio/mpeg
         *      below it, because an unrecognised tier is overwhelmingly MP3.
         */
        fun mimeFor(
            source: Streamable.Source,
            streamable: Streamable?,
            header: ByteArray? = null,
        ): String {
            if (source is Streamable.Source.Http) when (source.type) {
                Streamable.SourceType.HLS -> return "application/x-mpegURL"
                Streamable.SourceType.DASH -> return "application/dash+xml"
                Streamable.SourceType.Progressive -> Unit
            }
            extensionMime(source.id)?.let { return it }
            header?.let { sniffMime(it) }?.let { return it }
            return labelMime(streamable, source)
        }

        /**
         * The file extension for a MIME type, for the `track.<ext>` suffix LocalMediaServer.urlForPlan
         * puts on a served URL. The near-inverse of [extensionMime], which is the authority on the
         * pairings; this direction has to pick ONE extension per type, so m4a loses to mp4 and oga
         * to ogg.
         *
         * ⚠️ "bin" FOR ANYTHING UNRECOGNISED, RATHER THAN A PLAUSIBLE GUESS. The
         * extension is a hint; the Content-Type we send and the contentType in the LOAD are the
         * real answer, and naming a container we are not sure of would contradict them.
         */
        fun extFor(mime: String): String = when (mime.substringBefore(';').trim().lowercase()) {
            "audio/mpeg", "audio/mp3" -> "mp3"
            "audio/flac", "audio/x-flac" -> "flac"
            "audio/mp4", "audio/aac", "audio/m4a", "audio/x-m4a" -> "mp4"
            "audio/ogg", "application/ogg" -> "ogg"
            "audio/opus" -> "opus"
            "audio/webm", "video/webm" -> "webm"
            "audio/wav", "audio/x-wav", "audio/wave" -> "wav"
            "application/x-mpegurl", "application/vnd.apple.mpegurl" -> "m3u8"
            "application/dash+xml" -> "mpd"
            else -> "bin"
        }

        /** Strips query and fragment first: a CDN URL's real extension is before the `?`. */
        private fun extensionMime(url: String): String? {
            val path = url.substringBefore('?').substringBefore('#').lowercase()
            return when {
                path.endsWith(".mp3") -> "audio/mpeg"
                path.endsWith(".flac") -> "audio/flac"
                path.endsWith(".m4a") || path.endsWith(".mp4") || path.endsWith(".aac") ->
                    "audio/mp4"
                path.endsWith(".opus") || path.endsWith(".ogg") || path.endsWith(".oga") ->
                    "audio/ogg"
                path.endsWith(".wav") -> "audio/wav"
                path.endsWith(".m3u8") -> "application/x-mpegURL"
                path.endsWith(".mpd") -> "application/dash+xml"
                else -> null
            }
        }

        /**
         * Container magic, which is the only thing that can identify a Raw stream.
         * ID3 is checked before MPEG sync because a tagged MP3 starts with the tag, not the frame.
         */
        private fun sniffMime(head: ByteArray): String? {
            fun at(i: Int, vararg bytes: Int) =
                head.size > i + bytes.size - 1 &&
                    bytes.withIndex().all { (k, b) -> head[i + k].toInt() and 0xFF == b }
            return when {
                at(0, 0x49, 0x44, 0x33) -> "audio/mpeg"                        // "ID3"
                at(0, 0x66, 0x4C, 0x61, 0x43) -> "audio/flac"                  // "fLaC"
                at(0, 0x4F, 0x67, 0x67, 0x53) -> "audio/ogg"                   // "OggS"
                at(4, 0x66, 0x74, 0x79, 0x70) -> "audio/mp4"                   // "....ftyp"
                at(0, 0x52, 0x49, 0x46, 0x46) -> "audio/wav"                   // "RIFF"
                // MPEG audio frame sync: 11 set bits. Covers an MP3 with no ID3 tag.
                head.size > 1 && head[0].toInt() and 0xFF == 0xFF &&
                    head[1].toInt() and 0xE0 == 0xE0 -> "audio/mpeg"
                else -> null
            }
        }

        /** The last resort: the extension's own tier text, which only Deezer's happens to encode. */
        private fun labelMime(streamable: Streamable?, source: Streamable.Source): String {
            val haystack = (
                streamable?.title.orEmpty() + " " + streamable?.id.orEmpty() + " " +
                    source.title.orEmpty() + " " + source.id
                ).lowercase()
            return when {
                LOSSLESS_WORDS.any { haystack.contains(it) } -> "audio/flac"
                haystack.contains("opus") || haystack.contains("ogg") -> "audio/ogg"
                haystack.contains("aac") || haystack.contains("m4a") -> "audio/mp4"
                else -> "audio/mpeg"
            }
        }

        private val LOSSLESS_WORDS =
            listOf("flac", "alac", "lossless", "hi-res", "hires", "wav", "aiff")

        /**
         * Chooses the route for a source. [key] is the Base64 player key, needed only by MATERIALISE.
         *
         * ⚠️ A `file://` Http SOURCE IS A DOWNLOAD, and that is how a download is recognised here
         * rather than by the serverIndex pseudo-index: StreamableLoader.loadServer turns a download into
         * `Uri.fromFile(...).toString().toSource()`, so the ROUTE is visible in the source itself and no
         * index arithmetic is involved. The pseudo-index still has to be handled where track.servers is
         * indexed - see CastSessionController - but not here.
         */
        fun planFor(
            source: Streamable.Source,
            streamable: Streamable?,
            key: String,
        ): CastPlan {
            if (source is Streamable.Source.Http && source.decryption != null) {
                return Refused(Reason.DRM)
            }
            return when (source) {
                is Streamable.Source.Raw -> Materialise(key, mimeFor(source, streamable))
                is Streamable.Source.Http -> {
                    val url = source.request.url
                    val headers = source.request.headers
                    val mime = mimeFor(source, streamable)
                    val isManifest = source.type != Streamable.SourceType.Progressive
                    when {
                        url.startsWith("file://") -> File(url.removePrefix("file://"), mime)
                        headers.isEmpty() -> Direct(url, mime)
                        isManifest -> Refused(Reason.MANIFEST_WITH_HEADERS)
                        else -> Relay(url, headers, mime)
                    }
                }
            }
        }
    }
}
