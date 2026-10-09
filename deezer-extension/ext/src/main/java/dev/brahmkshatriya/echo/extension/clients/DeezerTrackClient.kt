package dev.brahmkshatriya.echo.extension.clients

import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Streamable.Media.Companion.toMedia
import dev.brahmkshatriya.echo.common.models.Streamable.Source.Companion.toSource
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.extension.AudioStreamProvider
import dev.brahmkshatriya.echo.extension.DeezerApi
import dev.brahmkshatriya.echo.extension.DeezerExtension
import dev.brahmkshatriya.echo.extension.DeezerParser
import dev.brahmkshatriya.echo.extension.Utils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient

class DeezerTrackClient(private val deezerExtension: DeezerExtension, private val api: DeezerApi, private val parser: DeezerParser) {

    private val client: OkHttpClient get() = api.clientNP

    private fun extractUrlFromJson(json: JsonObject): String? {
        val data = json["data"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
        val media = data["media"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
        val source = media["sources"]?.jsonArray?.getOrNull(1)?.jsonObject
            ?: media["sources"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: return null
        return source["url"]?.jsonPrimitive?.content
    }

    private suspend fun createStreamableForQuality(track: Track, quality: String, retry: Boolean = true): Streamable {
        return try {
            val currentTrackId = track.id
            val mediaJson =
                if (quality != "128" && quality != "mp3") api.getMediaUrl(track, quality)
                else api.getMP3MediaUrl(track, quality == "128")
            val mjString = mediaJson.toString()
            if (mjString.contains("License token has no sufficient rights on requested media")) return when (quality) {
                "flac" -> createStreamableForQuality(track, "320", retry)
                "320" -> createStreamableForQuality(track, "128", retry)
                else -> throw Exception("Track not available on server")
            }
            val trackJsonData = mediaJson["data"]?.jsonArray?.firstOrNull()?.jsonObject
            val mediaIsEmpty = trackJsonData?.get("media")?.jsonArray?.isEmpty() == true

            val (finalUrl, fallbackTrack) = when {
                mjString.contains("Track token has no sufficient rights on requested media") || mediaIsEmpty -> {
                    // ⚠⚠ STEP DOWN ON THE *SAME TRACK* FIRST. THIS BRANCH USED TO JUMP STRAIGHT
                    // TO FALLBACK_ID, WHICH STREAMS A DIFFERENT RECORDING UNDER THE ORIGINAL'S TITLE.
                    // FIELD REPORT (GitHub issue, 2026-10): "Only Love Can Save Me Now - Acoustic" and
                    // "Death by Rock and Roll - Acoustic" (The Pretty Reckless), from Deezer Liked
                    // Songs, played the STANDARD versions while the player showed the acoustic titles.
                    // Region-dependent, and the two halves were measured separately:
                    //   on a phone where the acoustic has no 320 the relay THROWS "Song not available",
                    //     the outer catch below steps 320 -> 128, and the acoustic plays correctly;
                    //   in the reporter's region the same unavailability arrives as an HTTP 200 with an
                    //     EMPTY `media` array instead - nothing throws, no licence string - so it landed
                    //     here and substituted FALLBACK_ID's audio.
                    // ⚠️ SO THE BUG WAS A HOLE BETWEEN TWO EXISTING LADDERS, not a missing one.
                    // The licence branch above handles "License token has no sufficient rights"; the
                    // outer catch handles anything that THROWS. mediaIsEmpty is neither: it is a
                    // SUCCESSFUL response that simply has no media for the requested format. Treating
                    // "not available in THIS format" as "not available at all" is what skipped the
                    // step-down.
                    // ⚠️ THE TRACK-TOKEN STRING IS STEPPED DOWN TOO, DELIBERATELY. getMediaUrl
                    // (flac/320) posts only {formats, ids} and NO track_tokens, so that sentence can
                    // only reach us there as Deezer's error about the RELAY's token passed through -
                    // and whether the relay passes bodies through is NOT KNOWN. getMP3MediaUrl (128)
                    // does send track_tokens, so there it is about ours. Either way the sentence says
                    // "on requested media", i.e. it is about the FORMAT, and stepping down first is
                    // safe under both readings because the fallback is still reached at the bottom.
                    // ⚠️ BOUNDED AT ONE REQUEST PER QUALITY: the recursion strictly descends
                    // flac -> 320 -> 128 and the bottom rung has no lower quality, so a track cannot
                    // revisit a rung. Worst case is +3 requests versus the old immediate substitution,
                    // and only on tracks that were already failing. In the COMMON case the extra call
                    // goes to media.deezer.com and REPLACES the fallback's relay call, so the shared
                    // unauthenticated relay sees no more traffic than before.
                    val lower = when (quality) {
                        "flac" -> "320"
                        "320" -> "128"
                        // "128" and "mp3" (MP3_MISC) are bottom rungs - nothing lower exists, so the
                        // substitution below is the only remaining option.
                        else -> null
                    }
                    if (lower != null) return createStreamableForQuality(track, lower, retry)

                    // ⚠️ BOTTOM RUNG ONLY. The `else` arm that used to live here - a
                    // getMediaUrl(track.copy(id = fallBackId), quality) for flac/320 - IS GONE RATHER
                    // THAN KEPT AS A BELT, because the step-down above makes it unreachable and leaving
                    // it would imply the substitution can still happen at a high quality. It cannot:
                    // by here `quality` is "128" or "mp3".
                    val fallBackId = track.extras["FALLBACK_ID"].orEmpty()
                    val fallbackObject = api.track(fallBackId)
                    val resultOj = fallbackObject["results"]?.jsonObject!!
                    val fallBackTrack = parser.run { resultOj.toTrack() }
                    val fbMediaJson = api.getMP3MediaUrl(fallBackTrack, true)
                    val url = extractUrlFromJson(fbMediaJson)!!
                    url to fallBackTrack
                }

                mjString.contains("An error occurred while decoding track token") -> {
                    val fallbackObject = api.track(currentTrackId)
                    val resultOj = fallbackObject["results"]?.jsonObject!!
                    val fallBackTrack = parser.run { resultOj.toTrack() }
                    val fbMediaJson = api.getMP3MediaUrl(fallBackTrack, true)
                    val url = extractUrlFromJson(fbMediaJson)!!
                    url to fallBackTrack
                }

                else -> {
                    val url = extractUrlFromJson(mediaJson)!!
                    url to null
                }
            }

            val qualityValue = when (quality) {
                "flac" -> 9
                "320" -> 6
                "128" -> 3
                else -> 0
            }
            val qualityTitle = when (quality) {
                "flac" -> "FLAC"
                "320" -> "320kbps"
                "128" -> "128kbps"
                else -> "UNKNOWN"
            }
            val keySourceId = fallbackTrack?.id ?: currentTrackId

            // ⚠⚠ WHEN A SUBSTITUTION HAPPENED, SAY SO - A SILENT SWAP IS THE ACTUAL DEFECT.
            // fallbackTrack is non-null ONLY on the two substituting branches above, and until now it
            // was consumed solely for the Blowfish key, so the user had no way to tell that the audio
            // was a different recording from the title on screen.
            // ⚠️ IT TRAVELS IN extras BECAUSE THIS FUNCTION CANNOT REACH THE DISPLAY. The Track
            // the player titles from was built by loadTrack, long before this call, and the Streamable
            // returned here carries no song metadata - `title` below is the QUALITY label. So the
            // signal is handed to loadStreamableMedia, which builds the Source that the player's
            // subtitle pill and the quality sheet read. See the Raw(title = ...) note there.
            // ⚠️ AND NOT BY CHANGING THE TRACK'S OWN TITLE, which would be the obvious idea and
            // is the risky one: the knowledge arrives AFTER loadTrack has built the item, and a
            // mid-play metadata change runs through MediaMetadata.equals (which EXCLUDES extras),
            // StreamableMediaSource.canUpdateMediaItem's field gate, and PlayerTrackAdapter's
            // lastBoundMediaId - the three paths that cost several builds of album-art bugs.
            // ⚠⚠ A PRESENCE MARKER, NOT THE SUBSTITUTE'S TITLE - AND IT CARRIED THE TITLE
            // UNTIL 2026-10-07. The label can only be read in the quality sheet: the player's pill
            // uses FormatUtils.getDetailsFormatFirst, which appends source titles LAST by design
            // (f2b661b0, 2026-08-29: "when the line is cut the codec and bitrate are the part worth
            // keeping"), and the pill is one line of 12sp inside a 210dp maxWidth - about 30
            // characters, which toAudioDetails' own "MPEG 128 kbps • 44100 Hz • 2ch" already
            // fills. So anything appended after it is past the ellipsis NO MATTER HOW SHORT, and a
            // long label bought nothing the sheet did not already give. Hoisting it in FormatUtils
            // was scoped and declined - that line is fenced off by the note above it.
            //
            // ⚠⚠ GATED ON A DIFFERENT TRACK ID, NOT ON fallbackTrack != null - AND THAT FIXES
            // A BUG IN THE FIRST CUT OF THIS CODE. fallbackTrack is non-null on TWO branches, and only
            // one of them is a substitution: the "An error occurred while decoding track token" branch
            // re-fetches api.track(currentTrackId), i.e. the SAME recording with a fresh token. The
            // earlier `fallbackTrack?.title` form labelled that as an alternate version, which is a
            // false claim about the audio - exactly the kind of mislabelling this whole change exists
            // to remove. A differing id is the honest test, and it is structurally right too: the
            // FALLBACK branch fetches FALLBACK_ID, the token branch fetches currentTrackId.
            val isAlternateRecording = fallbackTrack != null && fallbackTrack.id != currentTrackId

            Streamable.server(
                id = finalUrl,
                quality = qualityValue,
                title = qualityTitle,
                extras = buildMap {
                    put("key", Utils.createBlowfishKey(keySourceId))
                    if (isAlternateRecording) put(ALT_VERSION_EXTRA, "1")
                }
            )
        } catch (e: Exception) {
            if (e.message?.contains("Song not available") == true) {
                if (retry) {
                    // Deliberate best-effort fallback: the cause is logged above; rethrowing would change
                    // the intended quality-fallback control flow. Suppressing Detekt SwallowedException here.
                    @Suppress("SwallowedException")
                    try {
                        deezerExtension.handleArlExpiration()
                        return createStreamableForQuality(track, quality, false)
                    } catch (retryEx: Exception) {
                        // Best-effort: proceed to quality fallback. Log so a failing ARL-refresh retry
                        // (a token issue) isn't invisible — control flow unchanged.
                        println("GladixDeezer createStreamableForQuality ARL-refresh retry failed id=${track.id} q=$quality: ${retryEx.message}")
                    }
                }
            }

            when (quality) {
                "flac" -> createStreamableForQuality(track, "320", retry)
                "320" -> createStreamableForQuality(track, "128", retry)
                else -> throw Exception("Track not available on server")
            }
        }
    }

    suspend fun loadStreamableMedia(streamable: Streamable): Streamable.Media {
        deezerExtension.handleArlExpiration()
        val resolvedStreamable = if (streamable.id.startsWith(placeholderPrefix)) {
            val info = streamable.id.removePrefix(placeholderPrefix).split(":")
            val trackId = info[0]
            val quality = info.getOrNull(1) ?: "128"
            val newTrack = Track(
                id = trackId,
                title = quality,
                extras = mapOf(
                    "TRACK_TOKEN" to streamable.extras["TRACK_TOKEN"].orEmpty(),
                    "FALLBACK_ID" to streamable.extras["FALLBACK_ID"].orEmpty()
                )
            )
            var resolved: Streamable? = null
            // Last attempt's cause, CHAINED into the throw below. Control flow is unchanged — the loop still
            // swallows per-attempt failures and still retries — this only stops the reason being discarded.
            // Without it every failure mode collapses to one string: the 2026-08-19 breaker trip showed three
            // distinct track ids all reporting "Track not available after retries" with no transport error,
            // and token/session staleness, an account-level limit and a server outage were indistinguishable.
            var lastError: Throwable? = null
            for (attempt in 0..1) {
                if (attempt > 0) delay(2000L)
                // Best-effort retry: the loop continues past a failed attempt and ends in the throw below.
                // The caught exception IS used now (captured as lastError and chained), but the annotation
                // stays because the catch still does not rethrow on the non-final attempt.
                @Suppress("SwallowedException")
                try {
                    resolved = createStreamableForQuality(newTrack, quality)
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastError = e
                    // Kept alongside the chaining, not made redundant by it: only the LAST attempt's cause is
                    // chained, so attempt 0's reason exists nowhere else. The two are complementary.
                    println("GladixDeezer loadStreamableMedia attempt $attempt failed id=$trackId q=$quality: ${e.message}")
                }
            }
            resolved ?: throw Exception("Track not available after retries: $trackId", lastError)
        } else {
            streamable
        }

        // ⚠⚠ Raw(...) IS CONSTRUCTED DIRECTLY INSTEAD OF VIA toSource(), AND ONLY TO CARRY A
        // TITLE. InputProvider.toSource(id, isVideo, isLive) does not forward one, so every Deezer
        // Source has title == null - which is why FormatUtils.sourceTitles' own comment says Deezer
        // contributes "a single short title or nothing". Setting it here is what makes a substituted
        // recording visible, in two surfaces that already exist and that YouTube Music already uses:
        //   PlayerFragment's subtitle pill, via FormatUtils.getDetailsFormatFirst; and
        //   QualitySelectionBottomSheet's source chip, `it.title ?: getString(quality_x, it.quality)`.
        // ⚠️ DO NOT "TIDY" THIS BY ADDING A title PARAMETER TO toSource(). That function is in
        // :common, and an optional parameter with a default is BINARY-INCOMPATIBLE there - Kotlin
        // compiles the old signature away, so every already-built extension breaks. Constructing Raw
        // directly keeps the whole change inside this module, with no ABI surface at all.
        // ⚠️ null TITLE WHEN NOTHING WAS SUBSTITUTED, so the normal case is byte-identical to
        // the old toSource() result and the pill shows exactly what it showed before.
        val isAlternateRecording = resolvedStreamable.extras.containsKey(ALT_VERSION_EXTRA)
        return if (resolvedStreamable.quality == 12) {
            resolvedStreamable.id.toSource().toMedia()
        } else {
            Streamable.Source.Raw(
                streamProvider = Streamable.InputProvider { start, _ ->
                    val contentLength = Utils.getContentLength(resolvedStreamable.id, client)
                    Pair(
                        AudioStreamProvider.openStream(resolvedStreamable, client, start),
                        contentLength - start
                    )
                },
                id = resolvedStreamable.id,
                title = if (isAlternateRecording) ALT_VERSION_LABEL else null
            ).toMedia()
        }
    }

    private val qualityOptions = listOf("flac", "320", "128")

    suspend fun loadTrack(original: Track): Track {
        deezerExtension.handleArlExpiration()

        if (original.type == Track.Type.Podcast) {
            return original
        }

        // Self-heal a missing/empty TRACK_TOKEN (e.g. a context-less bare track recovered from an
        // Android Auto cache-miss, or a stale persisted seed): re-fetch the track fresh by id so its
        // streamables carry a valid token — and, for a thin recovered track, full metadata too. Gated
        // on an EMPTY token, so the normal path (token already present) adds no network round-trip. On
        // any failure we fall back to the original track unchanged — the stream-time token-error
        // fallback in createStreamableForQuality still applies — never crashing. CancellationException
        // is rethrown so coroutine cancellation is honoured.
        // ⚠⚠ THIS runCatching ABSORBS THE NEW GATEWAY THROW, AND THAT MAKES THE FLIP A NO-OP
        // ON THE PATH THAT TRIGGERS IT MOST. Measured 2026-09-12: one playlist and the album behind it,
        // containing tracks that will not play, produced TWELVE `GATEWAY-ERROR method=deezer.pageTrack
        // error=REQUEST_ERROR=Wrong parameters` on one screen - every one from the api.track call below.
        // ⚠️ THE CAUSE OF THE UNPLAYABILITY IS NOT KNOWN AND IS NOT ASSERTED. What IS observed:
        // those tracks reach here with an EMPTY TRACK_TOKEN, because that is the gate on the `if` below and
        // it opened for each of them. So each asks the gateway for a record the gateway then refuses.
        // Do not upgrade "empty TRACK_TOKEN" into a catalogue-state explanation - it is a correlation
        // measured once, at 12 of 12, and nothing here establishes why.
        // BEFORE THE FLIP: callApi returned {error, results:{}}; `results` parsed to nothing, this
        // runCatching caught whatever that produced, fresh = null, fall back to `original`.
        // AFTER: callApi throws DeezerGatewayException; the SAME runCatching catches it, the SAME
        // getOrElse yields null, the SAME fallback to `original` runs. Identical screen, identical
        // behaviour - twelve exceptions constructed and immediately absorbed by a handler that already
        // existed for exactly this outcome. ⚠️ NOT A FLOOD AND NOT AN IMPROVEMENT HERE: it is
        // ABSORBED, bounded by construction at one per token-less track. What it does buy is determinism -
        // `fresh` is now reliably null on a refusal instead of depending on how an empty `results` object
        // happens to parse.
        // WHERE THE FLIP ACTUALLY PAYS is createStreamableForQuality's two api.track calls on the FALLBACK
        // branches: those are NOT wrapped, so they surface - and they already failed today, opaquely, on a
        // `!!` or a parse of empty results. They now fail with Deezer's own sentence attached. Same
        // failure, readable cause.
        // The fourth call site, DeezerRadioClient's seed fetch, is runCatching{}.getOrNull() - absorbed
        // like this one.
        // ⚠⚠ THIS GATE FIRES ON PROVENANCE, NOT ON AVAILABILITY - AND IT READS LIKE THE
        // OPPOSITE, WHICH IS WHY IT IS WRITTEN DOWN. An empty TRACK_TOKEN means the track LOST ITS EXTRAS
        // somewhere - restored from a saved queue, recovered from history, or slimmed - because
        // HistoryEntity.toSlim does `extras = emptyMap()` wholesale. It says NOTHING about whether the
        // track can play. Re-fetching in that case is correct and this gate is right; what is wrong is
        // reading the emptiness as a property of the track.
        // ⚠️ MEASURED THE WRONG WAY ONCE, COST TWO CAPTURES: on 2026-09-12 an unplayable-track
        // investigation observed this gate opening 12 times out of 12 on tracks that would not play, and
        // took empty-token as an availability signal. The next capture refuted it outright - the record it
        // caught was an unplayable track WITH a token and every FILESIZE variant at zero, and the
        // no-token slot never fired at all. The correlation was real and the causation was backwards:
        // both the refusals and the empty tokens follow from how those tracks REACHED the player.
        // Do not reuse this condition as an availability test. See the pattern note at HistoryEntity.toSlim.
        val track = if (original.extras["TRACK_TOKEN"].isNullOrEmpty()) {
            val fresh = runCatching {
                api.track(original.id)["results"]?.jsonObject?.let { results ->
                    parser.run { results.toTrack() }
                }
            }.getOrElse { if (it is CancellationException) throw it else null }
            when {
                fresh == null -> original
                // Seed already carries display metadata (e.g. a FALLBACK-grafted playlist track whose
                // top-level TRACK_TOKEN was empty): KEEP the seed's artists/album/cover/background and take
                // ONLY the token/streamable extras from the fresh fetch. Re-fetching by the top-level id
                // returns the substitute's OWN (un-grafted) record, so replacing wholesale would discard the
                // graft and show the wrong/old cover in the player (the fullscreen ViewHolder reads the loaded
                // track). Streaming is unaffected: track.id stays the top-level id and we take fresh's TOKEN.
                // ⚠⚠ THE COVER IS FILLED FIELD-BY-FIELD, NOT ALL-OR-NOTHING, AND THAT IS A FIX FOR
                // PIPE-SOURCED TRACKS. The gate above is an OR, so a track with artists and an album but NO
                // cover takes this branch and used to keep its null cover forever - the fresh fetch's cover
                // was discarded along with everything else. Pipe search results are exactly that shape, and
                // the symptom was no art in the player for every Pipe-sourced track.
                // ⚠️ IT CANNOT DISTURB THE Aug 6 GRAFT CASE THIS BRANCH WAS WRITTEN FOR, and the
                // reason is the elvis: a FALLBACK-grafted track HAS a cover (the graft copies
                // artists/album/cover/background from FALLBACK), so `original.cover ?: fresh.cover` keeps
                // the graft's cover and never reaches fresh's. The fill only engages where there was
                // nothing to preserve - which is the one case the OR gate let through wrongly.
                // ⚠️ COVER ONLY, DELIBERATELY. artists and album are NOT filled the same way: the
                // gate passes when EITHER is non-empty, so filling them would let a grafted track with
                // artists-but-no-album take the substitute's album - the exact wrong-album bug the graft
                // exists to prevent. Widen this list only with a case that proves it safe.
                original.cover != null || original.artists.isNotEmpty() || original.album != null ->
                    original.copy(
                        cover = original.cover ?: fresh.cover,
                        extras = original.extras + fresh.extras
                    )
                // Thin recovered track (context-less/bare, e.g. an Android Auto cache-miss): no display
                // metadata to preserve → use the full fresh fetch.
                else -> fresh
            }
        } else original

        val isMp3Misc = track.extras["FILESIZE_MP3_MISC"]?.let { it != "0" } ?: false

        val streamables = if (isMp3Misc) {
            listOf(
                Streamable.server(
                    id = "$placeholderPrefix${track.id}:mp3",
                    quality = 0,
                    title = "MP3",
                    extras = mapOf(
                        "TRACK_TOKEN" to track.extras["TRACK_TOKEN"].orEmpty(),
                        "FALLBACK_ID" to track.extras["FALLBACK_ID"].orEmpty()
                    )
                )
            )
        } else {
            qualityOptions.map { quality ->
                val qualityValue = when (quality) {
                    "flac" -> 9
                    "320" -> 6
                    "128" -> 3
                    else -> 0
                }
                val qualityTitle = when (quality) {
                    "flac" -> "FLAC"
                    "320" -> "320kbps"
                    "128" -> "128kbps"
                    else -> "UNKNOWN"
                }
                Streamable.server(
                    id = "$placeholderPrefix${track.id}:$quality",
                    quality = qualityValue,
                    title = qualityTitle,
                    extras = mapOf(
                        "TRACK_TOKEN" to track.extras["TRACK_TOKEN"].orEmpty(),
                        "FALLBACK_ID" to track.extras["FALLBACK_ID"].orEmpty()
                    )
                )
            }
        }
        return track.copy(
            streamables = streamables
        )
    }

    private val placeholderPrefix = "dzp:"

    companion object {
        // Hand-off key for a substituted recording's title, written by createStreamableForQuality and
        // read by loadStreamableMedia. Internal to this file - it never reaches a Track's extras, so it
        // cannot collide with the TRACK_TOKEN / FALLBACK_ID keys that do.
        private const val ALT_VERSION_EXTRA = "ALT_VERSION"

        // ⚠️ HARDCODED ENGLISH, CONSISTENT WITH THIS MODULE. The extension has no resources
        // and no locale plumbing for display strings; its sibling labels ("FLAC", "320kbps",
        // "128kbps", "MP3") are hardcoded in createStreamableForQuality the same way. If this module
        // ever gains localisation, these four move together.
        private const val ALT_VERSION_LABEL = "Alt version"
    }
}