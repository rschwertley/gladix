package dev.brahmkshatriya.echo.extension.clients

import dev.brahmkshatriya.echo.common.helpers.MediaUnavailableException
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Streamable.Media.Companion.toMedia
import dev.brahmkshatriya.echo.common.models.Streamable.Source.Companion.toSource
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.extension.AudioStreamProvider
import dev.brahmkshatriya.echo.extension.DeezerApi
import dev.brahmkshatriya.echo.extension.objOrNull
import dev.brahmkshatriya.echo.extension.requireObj
import dev.brahmkshatriya.echo.extension.DeezerExtension
import dev.brahmkshatriya.echo.extension.DeezerParser
import dev.brahmkshatriya.echo.extension.Utils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    /**
     * One rung of the quality ladder. `quality` is the rung being ATTEMPTED; [topQuality] is the
     * rung the ladder STARTED at - what the user's selected placeholder asked for - carried
     * unchanged through every descent so a SUBSTITUTE can be fetched at that quality instead of at
     * whatever rung the original happened to run out on.
     *
     * ⚠⚠ TWO TERMINATORS, NOT ONE, AND SPLITTING THEM IS A BUG FIX.
     * [allowSubstitute] bounds the FALLBACK_ID path (no substitute OF a substitute);
     * [allowRetoken] bounds the decode-error self-heal (no endless re-fetch of a token that
     * keeps failing). They were ONE flag until 2026-10-25, which made the two recoveries
     * mutually exclusive: whichever fired first disabled the other.
     * ⚠⚠ WHAT THAT COST, MEASURED ON DEVICE: a grafted favourite needs
     * the substitution to play AT ALL - its top-level SNG_ID is the unavailable original and
     * FALLBACK_ID is the playable recording (see DeezerLibraryClient.graftFavTrack). "Baby
     * Beluga" - the Aug 6 graft example - skipped as unplayable from Library -> Tracks while
     * playing fine from search, intermittently and self-healing: the library feed is served from
     * DISK CACHE (LibraryFragment's Cached.getFeedShelf arm), so its TRACK_TOKEN can be hours
     * old, the stale token produced "An error occurred while decoding track token" FIRST, the
     * self-heal burned the shared flag, and the re-entered ladder then hit the substitute
     * terminator with nothing left to try. Fresh token, no decode error, substitution
     * reachable, plays.
     * ⚠️ NO EXPIRY IS PARSED, DELIBERATELY. TRACK_TOKEN_EXPIRE exists in
     * the gateway record (see the key list at DeezerParser.toTrack) and is NOT read: loadTrack
     * re-fetches only on an EMPTY token, so a stale one is indistinguishable from a good one
     * before the request. Reacting to the refusal is what this function does; pre-empting it is
     * a separate change with a request cost per stale track.
     * ⚠⚠ [CORRECTED 2026-10-27] THAT DECISION (Oct 26) IS REVERSED - THE EXPIRY IS PARSED NOW, and
     * loadTrack pre-empts a stale token through [hasUsableToken]. The reason is coverage, not a
     * proven cause: Maria still skipped silently from Library -> Tracks on 1151 with this function's
     * three recovery sites in place, and the refusal it met was never observed.
     * ⚠️ THAT A STALE TOKEN CAUSED IT IS UNCONFIRMED, for Maria and for the "Maria" paragraph above.
     * A 320 rung sends NO token - getMediaUrl posts the track id alone to the relay - so the token
     * only matters at the 128 and MP3_MISC rungs (getMP3MediaUrl), after a step-down. The saved
     * media-state entry is the confirmed location of the fault; the field in it is not. The
     * temporary `deezer rung` lines (removed after the 1153 capture) named the rung and the refusal,
     * and the app's preferCache line the entry's verdict and streams - those settled it.
     * [UPDATED 2026-10-27, 1152 capture] SETTLED FOR THE SAVED ENTRY: its stale token at 128 got an
     * answer no branch recognised, the `!!` that stood at the no_url branch threw, and the catch
     * skipped without substituting; a fresh token for the same id got token_rights and substituted.
     * The answer body is still unobserved. Fixed by the token-age check and the no_url branch in
     * createStreamableForQuality. Maria herself was not in that capture.
     * What stays true is the request cost, now paid only when the token has expired or its expiry is
     * unknown rather than on every stale track's FAILURE.
     */
    /**
     * ONE token refresh: re-fetch this track's own gateway record and re-enter the ladder at
     * [topQuality] with the refresh spent.
     *
     * ⚠⚠ allowRetoken NOW MEANS "A TOKEN REFRESH IS UNSPENT IN THIS
     * RESOLVE", NOT "THE DECODE-ERROR SELF-HEAL IS UNSPENT". Same flag, same threading, wider
     * meaning - which is why this needs no new parameter and inherits the existing termination
     * argument unchanged: every caller re-enters with allowRetoken = false, so at most ONE refresh
     * happens per resolve attempt no matter which branch spends it.
     * ⚠⚠ WHY IT IS CALLED FROM THREE PLACES NOW. A stale TRACK_TOKEN does
     * not always announce itself as a decode error. Deezer can refuse it as "Track token has no
     * sufficient rights on requested media", or simply answer with an EMPTY `media` array - both of
     * which land in the substitute branch instead. For a track WITHOUT FALLBACK_ID that branch used
     * to throw on the spot, so an ordinary track whose token had gone stale was skipped silently
     * with no recovery attempted at all. The decode branch's own note still says a refetch there was
     * "rejected... the token self-heal below already recovers that way" - true only for the one
     * wording it matches.
     * ⚠️ WHICH REFUSAL THE 2026-10-26 CASE ACTUALLY HIT WAS NEVER
     * OBSERVED. "Maria" (P.J. Proby, id 3019874391 - readable, 214 countries, no public
     * `alternative`) skipped silently and repeatably from Library -> Tracks on 1149 and played from
     * the same list seconds later via the header Shuffle, which used a list with a fresh token. The
     * substitute-branch attribution is INFERRED from that and from the code paths, not from a
     * captured response: no log line or capture ever showed the string Deezer returned. If this is
     * ever revisited, that capture is the missing evidence.
     * [2026-10-27] The 1152 capture observed the REFUSAL CLASS for a stale saved token (unrecognised,
     * no URL - see the no_url branch), still not the string.
     * ⚠️ THE LICENCE BRANCH IS DELIBERATELY NOT A CALLER. Its string is
     * "License token has no sufficient rights" - a DIFFERENT credential from TRACK_TOKEN, which
     * api.track() does not refresh. Calling this from there would spend a request and change
     * nothing. Covering it needs a licence-token refresh, which is a separate decision.
     * ⚠️ STRICT ON SHAPE, like the decode branch it replaces: a bad
     * response must surface rather than build a track from nothing.
     *
     * @param atQuality the rung to resume at. topQuality (the default) for the decode branch and the
     * refusedAtRung terminals, which re-enter the whole ladder as before; the rung itself for the
     * token-age check and the no_url branch (2026-10-27), which refresh BEFORE a token rung's request
     * or after its unrecognised answer, where re-entering higher would only repeat relay calls.
     */
    private suspend fun refreshTokenAndRetry(
        track: Track, topQuality: String, retry: Boolean, allowSubstitute: Boolean,
        atQuality: String = topQuality,
    ): Streamable {
        val refetched = api.track(track.id)
        val results = refetched["results"].requireObj("results")
        val rebuilt = parser.run { results.toTrack() }
        // FALLBACK_ID carried when the re-fetch omits it - see the [CORRECTED 2026-10-26] note at
        // the decode branch. deezer.pageTrack wins when it supplies one.
        val carried = track.extras["FALLBACK_ID"]
        val fresh =
            if (rebuilt.extras["FALLBACK_ID"].isNullOrBlank() && !carried.isNullOrBlank())
                rebuilt.copy(extras = rebuilt.extras + ("FALLBACK_ID" to carried))
            else rebuilt
        return descend(fresh, atQuality, retry, topQuality, allowSubstitute, allowRetoken = false)
    }

    private suspend fun createStreamableForQuality(
        track: Track,
        quality: String,
        retry: Boolean = true,
        topQuality: String = quality,
        allowSubstitute: Boolean = true,
        allowRetoken: Boolean = true,
    ): Streamable {
        return try {
            // ⚠⚠ THE TOKEN'S AGE IS CHECKED WHERE THE TOKEN IS SENT. Only the 128 and mp3 rungs send
            // TRACK_TOKEN (getMP3MediaUrl); the relay rungs send the id alone, so checking there would
            // spend an api.track on every cached play that 320 serves. loadTrack's gate cannot cover
            // this: a media-state cache hit (Cached.loadMedia's preferCache arm) never calls loadTrack,
            // and hands loadStreamableMedia the token the entry was written with, however old - the
            // 1152 capture's mechanism, recorded at the no_url branch below. TRACK_TOKEN_EXPIRE now rides
            // in each streamable's extras so this can judge it; an entry written before that has no
            // expiry, reads no_expiry, and refreshes here each time it reaches this rung (a hit never
            // rewrites the entry), which is one api.track - what a cache miss pays in loadTrack anyway.
            // Spends the one-refresh budget, and resumes AT THIS RUNG (atQuality) rather than at
            // topQuality, so a 320 user pays no second relay call for a refusal already received.
            if ((quality == "128" || quality == "mp3") && allowRetoken && !track.hasUsableToken())
                return refreshTokenAndRetry(track, topQuality, retry, allowSubstitute, atQuality = quality)
            val currentTrackId = track.id
            val mediaJson =
                if (quality != "128" && quality != "mp3") api.getMediaUrl(track, quality)
                else api.getMP3MediaUrl(track, quality == "128")
            val mjString = mediaJson.toString()
            if (mjString.contains("License token has no sufficient rights on requested media")) return when (quality) {
                "flac" -> descend(track, "320", retry, topQuality, allowSubstitute, allowRetoken)
                "320" -> descend(track, "128", retry, topQuality, allowSubstitute, allowRetoken)
                else -> throw Exception("Track not available on server")
            }
            val trackJsonData = mediaJson["data"]?.jsonArray?.firstOrNull()?.jsonObject
            val mediaIsEmpty = trackJsonData?.get("media")?.jsonArray?.isEmpty() == true

            // ⚠⚠ ONE VALUE, NOT A Pair, SINCE 2026-10-16. This used to be
            // `val (finalUrl, fallbackTrack) = when {`, because both substituting branches fell
            // THROUGH to the Streamable.server call at the end of this function carrying the
            // substitute's Track. They now `return` a Streamable built by their own recursive
            // ladder, so nothing below this point can be looking at a substitute - which is what
            // lets keySourceId and the ALT marker both collapse to one obvious form each.
            val finalUrl: String = when {
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
                    // ⚠️ BOUNDED AT ONE REQUEST PER QUALITY PER LADDER, AND AT TWO LADDERS.
                    // [CORRECTED 2026-10-16, when the substitute gained a ladder of its own] Three
                    // things bound this, and all three are needed:
                    //   1. each ladder strictly descends flac -> 320 -> 128 and the bottom rung has no
                    //      lower quality, so a rung is never revisited WITHIN a ladder;
                    //   2. allowSubstitute is false inside the substitute's ladder, so there is no
                    //      third ladder - a substitute cannot substitute;
                    //   3. a failure from a nested rung is rethrown rather than re-laddered by the
                    //      outer catch (see the LadderDescentException note there), which is what
                    //      stops each level walking the remaining rungs a SECOND time.
                    // WORST CASE, every rung of both ladders refused by an error-shaped HTTP 200 and
                    // the user on FLAC: 3 original + 1 api.track(FALLBACK_ID) + 3 substitute = 7
                    // requests, then one MediaUnavailableException. loadStreamableMedia tries the
                    // whole thing twice with a 2 s delay, so 14 per resolve, and the substitute ladder
                    // runs exactly ONCE per attempt. Before point 3 the same case cost 15 per attempt
                    // with FALLBACK_ID fetched four times, so this is FEWER requests than before the
                    // substitute ladder existed, not more.
                    // ⚠️ [AMENDED 2026-10-25, FLAG SPLIT] A DECODE ERROR CAN NOW ADD
                    // A RE-TOKEN AND A SECOND PASS OF THE ORIGINAL'S LADDER, so the absolute worst
                    // case is 3 original + 1 api.track(currentTrackId) + 3 re-entered + 1
                    // api.track(FALLBACK_ID) + 3 substitute = 11 per attempt, 22 per resolve. Point 1
                    // still bounds each ladder and allowRetoken bounds the re-token at exactly one, so
                    // 11 is a ceiling and not a new typical cost: reaching it needs every rung of
                    // three ladders refused AND a stale token. The path this spends it on is the one
                    // that previously dead-ended at the terminator below with no substitution tried.
                    // ⚠️ [RECOMPUTED 2026-10-26, BRANCH (b) REFRESH] THE CEILING IS
                    // UNCHANGED AT 11 PER ATTEMPT / 22 PER RESOLVE, AND THAT IS DERIVED, NOT
                    // ESTIMATED: the BUDGET bounds it and the budget did not change. At most one
                    // substitution (allowSubstitute) and at most one refresh (allowRetoken) allows at
                    // most THREE ladder passes - the entry one, the substitute's, and the refreshed
                    // one - at 3 media calls each, plus the two single fetches that start the latter
                    // two: 3 + 1 + 3 + 1 + 3 = 11. The new call sites let the refresh be spent from
                    // the substitute branch as well as the decode branch; they do not add a third
                    // chance to spend it.
                    // WHAT DOES CHANGE IS THE COST OF A FAILING TRACK WITH NO FALLBACK_ID, which is
                    // the unmarked-dead population: it used to leave at the blank terminal after one
                    // ladder (3 per attempt, 6 per resolve) and now pays a refresh and a second
                    // ladder first (7 per attempt, 14 per resolve) before the same throw. See the
                    // note at PlayerEventListener's maxConsecutiveCatalogueDeadSkips for what that
                    // does to a dead run.
                    // ⚠️ [RECHECKED 2026-10-27, TOKEN-AGE CHECK + no_url] STILL 11 / 22, by the same
                    // budget argument: both new sites SPEND the one refresh rather than adding one, and
                    // both resume at the token rung (atQuality), so their refreshed pass is ONE call, not
                    // a ladder - they can only lower a path's cost. The ceiling is still reached through
                    // the decode branch or a terminal in refusedAtRung, which re-enter at topQuality.
                    // In the COMMON case (the substitute is available at the requested quality) the
                    // substitute's first call goes to the relay and SUCCEEDS, replacing the single
                    // media.deezer.com 128 call that used to happen here - one request either way.
                    return refusedAtRung(track, quality, retry, topQuality, allowSubstitute, allowRetoken)
                }

                mjString.contains("An error occurred while decoding track token") -> {
                    // ⚠⚠ SAME RECORDING, FRESH TOKEN - NOT A SUBSTITUTION. api.track is
                    // called with currentTrackId, not FALLBACK_ID, so no ALT_VERSION stamp belongs on
                    // the result - and the absence of a stamp here is now the ONLY thing that says so,
                    // since the id comparison that used to make the distinction is gone. Do not add
                    // one: see the last paragraph of the ALT_VERSION_EXTRA note in the companion
                    // object for the mislabelling this prevents.
                    // ⚠️ AND IT WAS HARD-WIRED TO 128 TOO. getMP3MediaUrl(fallBackTrack,
                    // true) stood here, so a successfully re-tokenised track dropped to 128 even when
                    // the user had asked for FLAC - the same defect as the FALLBACK_ID block above, one
                    // branch over. It now re-enters the ladder at topQuality.
                    // ⚠⚠ allowRetoken = false FOR TERMINATION, and here it stops an
                    // INFINITE LOOP rather than merely an unbounded chain: a re-fetch whose fresh token
                    // also fails to decode lands on THIS branch again, with the same id, forever.
                    // ⚠⚠ ITS OWN FLAG SINCE 2026-10-25, AND THAT IS THE WHOLE FIX.
                    // Sharing one flag with the substitution above meant this recovery CONSUMED that
                    // one: a stale disk-cached TRACK_TOKEN re-tokenised here, re-entered with the
                    // substitution already disabled, and the grafted favourite that needed it was
                    // skipped as unplayable. The two bound different recursions - one id re-fetched
                    // repeatedly here, a chain of different ids there - and neither bounds the other.
                    if (!allowRetoken)
                        throw MediaUnavailableException("Track not available on server")
                    // [CORRECTED 2026-10-26] THIS BRANCH USED TO REBUILD THE TRACK INLINE AND
                    // CLAIM "ITS FALLBACK_ID IS STILL THE RIGHT LAST RESORT" WHILE THE REBUILT
                    // TRACK DID NOT NECESSARILY HAVE ONE: it comes from deezer.pageTrack, while
                    // FALLBACK is something favorite_song.getList PRE-SUBSTITUTES
                    // (DeezerLibraryClient.graftFavTrack), so toTrack could write FALLBACK_ID = ""
                    // and the substitute branch above would then hit its "NO FALLBACK_ID MEANS NO
                    // REQUEST" throw with allowSubstitute still true. The 2026-10-25 flag split
                    // preserved the FLAG while this recovery destroyed the DATA the flag protects.
                    // The carry now lives in refreshTokenAndRetry, shared with the two
                    // substitute-branch terminals that need the same recovery.
                    return refreshTokenAndRetry(track, topQuality, retry, allowSubstitute)
                }

                // ⚠⚠ no_url: AN ANSWER NONE OF THE BRANCHES ABOVE RECOGNISES, AND NO URL IN IT. This
                // was `extractUrlFromJson(mediaJson)!!`, and the NPE it threw reached the catch below,
                // whose bottom rung throws MediaUnavailableException - so a FALLBACK-grafted track was
                // skipped WITHOUT EVER REACHING ITS SUBSTITUTE, which only the token_rights branch runs.
                // MEASURED, 1152 capture 2026-10-27 (e.g. id 67239924, a substitute song): a saved
                // media-state entry's stale TRACK_TOKEN met this at 128 on both loadStreamableMedia
                // attempts (`refusal=thrown:NullPointerException`); StreamableLoader.safetyNet's fresh
                // load, same id and session minutes later, got `token_rights` at 128 and substituted.
                // Same tokenLen on both - a token's length says it is present, not that it is current.
                // ⚠️ THE ANSWER BODY ITSELF IS STILL UNOBSERVED; "a stale token gets an unrecognised
                // answer" is the capture's pattern, not Deezer's documented behaviour.
                // ⚠⚠ REFRESH FIRST, NEVER SUBSTITUTE FIRST, at a token rung: the acoustic-versions
                // report (see the step-down note on the token_rights branch) had FALLBACK_IDs, so a
                // stale token sent straight to refusedAtRung could play the standard recording under
                // the acoustic title. Only once the refresh is spent is this handled like token_rights.
                // ⚠️ NOT AT A RELAY RUNG (flac/320): getMediaUrl sends the id alone, so a refresh would
                // repeat a byte-identical relay request against its shared daily ceiling. There
                // refusedAtRung only steps down, and the 128 rung below it applies this rule.
                else -> extractUrlFromJson(mediaJson) ?: run {
                    if (allowRetoken && (quality == "128" || quality == "mp3"))
                        return refreshTokenAndRetry(track, topQuality, retry, allowSubstitute, atQuality = quality)
                    return refusedAtRung(track, quality, retry, topQuality, allowSubstitute, allowRetoken)
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
            // ⚠⚠ ALWAYS THIS RUNG'S OWN TRACK ID NOW - A SIMPLIFICATION, NOT A LOSS.
            // [CORRECTED 2026-10-16] `fallbackTrack?.id ?: currentTrackId` stood here, for the
            // fall-through shape described above. With both substituting branches returning their
            // own recursive result, by here no substitution has happened and the key can only be
            // this track's. The substitute's key is derived the same way ONE FRAME DOWN, from ITS
            // currentTrackId, which is exactly the id Blowfish needs - which is why the recursion
            // needed no special handling for the key at all.
            // ⚠⚠ AND `isAlternateRecording` IS GONE FROM HERE FOR THE SAME REASON. It read
            // `fallbackTrack != null && fallbackTrack.id != currentTrackId` and would now be
            // permanently false, which is worse than wrong: it would silently stop marking
            // substitutions. The marker is written at ONE site instead - the FALLBACK_ID block
            // above, the only branch that substitutes - and the whole mechanism (why extras, why
            // not the Track's title, why a presence marker rather than the substitute's title, and
            // why the decode-token branch must NOT be marked) is recorded at ALT_VERSION_EXTRA in
            // the companion object, where those notes moved rather than being deleted.
            val keySourceId = currentTrackId

            Streamable.server(
                id = finalUrl,
                quality = qualityValue,
                title = qualityTitle,
                // ALT_VERSION is NOT put here any more: see the keySourceId note above. It is
                // added by the FALLBACK_ID block, to the Streamable its own ladder returned.
                extras = mapOf("key" to Utils.createBlowfishKey(keySourceId))
            )
        } catch (e: Exception) {
            // ⚠⚠ A FAILURE RAISED BY A *NESTED* RUNG IS RETHROWN, NOT RE-LADDERED.
            // Every descent made from inside the try goes through [descend], which tags its failure;
            // this line unwraps the tag and rethrows the real cause.
            // WHAT THE `when (quality)` BELOW IS FOR IS UNCHANGED, and that is why this is an unwrap
            // and not a removal: getMediaUrl THROWS "Song not available" on a relay refusal, so on
            // that path `mjString` is never reached and this catch is the ONLY place a step-down can
            // happen. See the p=5-stepdown thrown-branch note below. What the tag removes is the OTHER
            // case, where the throw arrives from a nested call that has ALREADY walked every lower
            // rung - indistinguishable here from this rung's own request failing.
            // ⚠️ MEASURED COST OF THAT DUPLICATION, which is why it is closed in the same
            // change rather than later: for one flac resolve where every rung is refused by an
            // error-shaped HTTP 200, the old shape ran T(flac) = 1 + 2*T(320) = 15 requests per
            // attempt and fetched FALLBACK_ID four times; with the substitute ladder added below it
            // would have been 39. Unwrapped it is 7, with exactly one substitute ladder. The
            // duplicate pass was also an UNDECLARED retry - the declared one is loadStreamableMedia's
            // two attempts with a 2 s delay, which this does not touch.
            if (e is LadderDescentException) throw e.cause ?: e
            if (e.message?.contains("Song not available") == true) {
                if (retry) {
                    // Deliberate best-effort fallback: the cause is logged above; rethrowing would change
                    // the intended quality-fallback control flow. Suppressing Detekt SwallowedException here.
                    @Suppress("SwallowedException")
                    try {
                        deezerExtension.handleArlExpiration()
                        return createStreamableForQuality(
                            track, quality, false, topQuality, allowSubstitute, allowRetoken
                        )
                    } catch (retryEx: Exception) {
                        // Best-effort: proceed to quality fallback. Log so a failing ARL-refresh retry
                        // (a token issue) isn't invisible — control flow unchanged.
                        println("GladixDeezer createStreamableForQuality ARL-refresh retry failed id=${track.id} q=$quality: ${retryEx.message}")
                    }
                }
            }

            when (quality) {
                // DELIBERATELY NOT [descend]: a throw from here already propagates out of this catch,
                // so there is no second ladder to suppress - and tagging it would only hide the cause
                // from the frame above.
                "flac" -> createStreamableForQuality(
                    track, "320", retry, topQuality, allowSubstitute, allowRetoken
                )
                "320" -> createStreamableForQuality(
                    track, "128", retry, topQuality, allowSubstitute, allowRetoken
                )
                // TYPED, NOT A BARE Exception (2026-10-10). This is the throw that ESCAPES to the
                // app: the bottom rung has no lower quality, so the ladder ends here. It arrives at
                // PlayerEventListener.onPlayerError as the rootCause (deepest node), and a bare
                // Exception gave skipFamilyOf nothing to classify - so an exhausted track was filed
                // in the RESIDUAL Error family rather than Unavailable (Crashlytics 1fc0baac, 1119).
                // The MESSAGE IS UNCHANGED on purpose: onPlayerError still falls back to matching
                // "not available" for extensions that cannot name this type.
                else -> throw MediaUnavailableException("Track not available on server")
            }
        }
    }

    /**
     * What a rung does once Deezer has refused THIS track at THIS quality without throwing: step down
     * on the same track, then - at the bottom rung only - refresh or substitute. Shared by the
     * token_rights / media_empty branch and the no_url branch of [createStreamableForQuality], so the
     * two cannot drift. Called from inside that function's try, so everything it throws reaches the
     * same catch it did when this body was inline. Its notes were written inline in that function's
     * `when`: "above", "below", "one branch down" and "this catch" in them refer to that function.
     */
    private suspend fun refusedAtRung(
        track: Track,
        quality: String,
        retry: Boolean,
        topQuality: String,
        allowSubstitute: Boolean,
        allowRetoken: Boolean,
    ): Streamable {
        val lower = when (quality) {
            "flac" -> "320"
            "320" -> "128"
            // "128" and "mp3" (MP3_MISC) are bottom rungs - nothing lower exists, so the
            // substitution below is the only remaining option.
            else -> null
        }
        if (lower != null)
            return descend(track, lower, retry, topQuality, allowSubstitute, allowRetoken)

        // ⚠️ BOTTOM RUNG ONLY - by here `quality` is "128" or "mp3". That is
        // the right answer to WHEN to substitute and it was the wrong answer to AT WHAT
        // QUALITY.
        // [CORRECTED 2026-10-16] The note that stood here said the flac/320 `else` arm,
        // removed by 2419867c, "IS GONE RATHER THAN KEPT AS A BELT, because the step-down
        // above makes it unreachable and leaving it would imply the substitution can still
        // happen at a high quality. It cannot". The reachability claim was correct; the
        // consequence was missed. With no arm above 128 the substitute could only ever be
        // FETCHED at 128, so a user who selected 320 got a 128 stream OF A DIFFERENT
        // RECORDING - and no other part of the chain was wrong.
        // MEASURED, QUALITY-PROBE capture 2026-10-16: for the failing tracks Deezer answers
        // code 2002 "Track token has no sufficient rights on requested media" at flac, 320
        // AND 128 - from the relay and from the user's own account - so every manual pick in
        // the quality sheet reached this block, and p=5-final reported quality=128
        // substituted=true every time. Selection, the sheet and the resolve path were all
        // verified correct in that capture.
        // ⚠⚠ SO THE SUBSTITUTE NOW RUNS ITS OWN LADDER FROM topQuality, AND
        // 2419867c's RULE IS UNTOUCHED: the original recording is still tried at EVERY rung
        // before any substitution, because this block is only reachable once it has been.
        // ⚠⚠ allowSubstitute = false IS WHAT TERMINATES THIS, AND IT IS
        // NOT DEFENSIVE. Every descent propagates it unchanged, so inside a substitute's
        // ladder the throw below fires instead of fetching a substitute OF a substitute -
        // FALLBACK_ID chains otherwise have no termination condition at all.
        // ⚠⚠ IT TERMINATES THE SUBSTITUTION AND NOTHING ELSE. Until
        // 2026-10-25 this test read `!allowFallback`, one flag shared with the decode-error
        // self-heal below - so a stale TRACK_TOKEN that triggered a re-token BEFORE the
        // ladder got here arrived with the flag already spent, and this threw instead of
        // substituting. For a FALLBACK-grafted favourite that is the difference between
        // playing and being skipped as unplayable; see the note on this function.
        if (!allowSubstitute) {
            // Last step before giving up - see refreshTokenAndRetry. The substitution is
            // already spent here, so a stale token is the only recovery left.
            if (allowRetoken)
                return refreshTokenAndRetry(track, topQuality, retry, allowSubstitute)
            throw MediaUnavailableException("Track not available on server")
        }
        // ⚠⚠ NOTHING HERE VERIFIES THAT THE SUBSTITUTE IS THE SAME RECORDING.
        // ISRC / DURATION VERIFICATION: DECLINED OCT 16 2026. Recorded because the
        // absence looks like an oversight and is a choice, and because "declined" is a
        // closed question where "parked" would read as a queued task forever.
        // FALLBACK_ID is Deezer's own pointer and it demonstrably points at a DIFFERENT
        // recording sometimes - the 2026-10 field report quoted in the step-down note
        // above is exactly that (two Pretty Reckless "- Acoustic" tracks played the
        // standard versions). The step-down made that rarer; it did not make the
        // substitute correct when it does happen.
        // THE CHECK THAT WOULD WORK, so nobody has to re-derive it: ISRC identifies a
        // RECORDING, so an alternate release of the same master shares it while a
        // re-recording does not - precisely the distinction that failed. Both halves are
        // already parsed: DeezerParser.toTrack sets `isrc = data.str("ISRC")` and ISRC is
        // in the gateway song-record key set documented there, so fallBackTrack.isrc is
        // FREE - api.track(fallBackId) is already being called below. Duration is free the
        // same way and is the weaker variant: a remaster differs by seconds and an
        // acoustic can coincidentally match.
        // THE ONE ASYMMETRY, and it is the whole cost: the ORIGINAL's isrc is not here.
        // `track` is the placeholder loadStreamableMedia synthesises, which carries only
        // id + TRACK_TOKEN + FALLBACK_ID. Closing that is a no-request change - loadTrack
        // holds the real parsed Track when it builds the streamables, so threading
        // "ISRC" through the same extras map that already carries those two keys is all
        // it takes. Re-fetching the original instead would add a gateway call to a track
        // that has already failed three times; prefer the threading.
        // WHY IT WAS DECLINED, which is the part that is not a mechanism question:
        // a mismatch has to DO something, and both answers are bad in a way source
        // reading cannot rank. Refusing turns tracks that play today - wrongly, but
        // audibly - into MediaUnavailableException, which feeds the Unavailable skip
        // family and the consecutiveUnavailableSkips breaker. And ISRC is nullable here
        // (`data.str("ISRC")`), so the null policy decides a population size nobody has
        // measured: fail open and incomplete records keep today's behaviour, fail closed
        // and they all lose substitution. Silent-wrong versus silent-skip is a product
        // call, not a correctness one, and it was not worth a build on its own.
        // WHAT WOULD BRING IT BACK: a field report of a substituted recording that is
        // audibly the wrong song, now that the step-down has made substitution rare - at
        // which point the mismatch policy is being chosen against a real case instead of
        // in the abstract. The implementation above is the whole design; it is ~6 lines
        // and no new requests.
        // [CHECKED 2026-10-25, STILL DECLINED] The one named graft in the liked list was
        // compared record to record and it is NOT a wrong-song case: 67239974 "Whistling
        // Tune" and its FALLBACK 3427782381 "Worst Pies in London" share an IDENTICAL
        // MD5_ORIGIN, duration and filesize - the SAME AUDIO FILE under two titles, so what
        // plays is right and only the metadata is wrong. Across all 117 liked ids gw
        // FALLBACK.SNG_ID and pipe media.id agreed 16/16 with equal durations, with no
        // pipe-only substitutes. So the trigger above - AUDIBLY the wrong song - has NOT
        // fired, and this stays declined on evidence rather than on absence.
        val fallBackId = track.extras["FALLBACK_ID"].orEmpty()
        // ⚠⚠ NO FALLBACK_ID MEANS NO REQUEST, AND api.track("") WAS
        // WORSE THAN USELESS. The gateway cannot return anything playable for an
        // empty id, so the call was spent to produce a response with no `results` -
        // and the `!!` below then threw an NPE into this function's own catch, which
        // read it as "this rung failed" and stepped down. The cost was the refused
        // request AND a doomed api.track("") at flac, 320 and 128 before the ladder
        // ran out. Throwing here instead reaches the same place by the same route
        // with two fewer requests.
        // ⚠️ THE TYPE IS DELIBERATELY THE LADDER'S OWN, NOT A NEW ONE.
        // MediaUnavailableException is what the !allowSubstitute / !allowRetoken
        // terminators above and the bottom rung below already throw, and this catch
        // treats it exactly like
        // the NPE it replaces - descend, then surface as Unavailable. So nothing that
        // works today changes type: PlayerEventListener.skipFamilyOf still files it as
        // SkipFamily.Unavailable and the unavailable-skip path still handles it.
        // ⚠️ A REFETCH OF THE CURRENT TRACK WAS REJECTED HERE, and the
        // reason is one branch down: the token self-heal below already recovers that
        // way, and doing it from THIS branch would re-introduce the art overwrite the
        // Aug 6 merge-instead-of-replace change fixed. Blank means give up, not retry.
        // Adapted from Evoe (github.com/Doggo785/evoe), 409c4c5e, UPL - their version
        // refetches the current track; ours declines to, for the reason above.
        if (fallBackId.isBlank()) {
            // The ordinary-track case that used to dead-end here with nothing tried -
            // see refreshTokenAndRetry. Still no api.track(""): the refresh fetches THIS
            // track's id, which is a real id, not the blank fallback one.
            if (allowRetoken)
                return refreshTokenAndRetry(track, topQuality, retry, allowSubstitute)
            throw MediaUnavailableException("Track not available on server")
        }
        val fallbackObject = api.track(fallBackId)
        // Same throw rather than `!!`: a FALLBACK_ID that resolves to nothing is the
        // same verdict as no FALLBACK_ID, and an NPE here only reached the player as
        // this type anyway - via the catch below, two wasted rungs later.
        val resultOj = fallbackObject["results"].objOrNull()
            ?: throw MediaUnavailableException("Track not available on server")
        val fallBackTrack = parser.run { resultOj.toTrack() }
        // allowRetoken is passed UNCHANGED, not forced off: the substitute's record was
        // just fetched, so its token is fresh, and if it still fails to decode the same
        // one-shot recovery that works for any other track should be available to it. It
        // stays bounded either way - at most one re-token per resolve attempt, wherever
        // in the chain it is spent.
        val fbStreamable = descend(
            fallBackTrack, topQuality, retry, topQuality,
            allowSubstitute = false, allowRetoken = allowRetoken
        )
        // ⚠⚠ STAMPED HERE BECAUSE THE INNER CALL CANNOT KNOW IT IS A
        // SUBSTITUTE. From inside its own ladder the fallback is just a track being
        // resolved; it substitutes nothing and so stamps nothing. Without this line the
        // marker would never be written at all and the quality sheet's "Alt version"
        // label would silently vanish - reintroducing exactly the mislabelling the
        // 2026-10 field report was about. THIS IS THE ONLY WRITE SITE for the marker;
        // the mechanism is documented at ALT_VERSION_EXTRA in the companion object.
        // The Blowfish key needs no equivalent fix: the inner call derives keySourceId
        // from its own track id, which IS the fallback's.
        return fbStreamable.copy(
            extras = fbStreamable.extras + (ALT_VERSION_EXTRA to "1")
        )
    }

    /**
     * A ladder descent: calls [createStreamableForQuality] for a LOWER rung, or for a substitute,
     * and tags any failure so the CALLER's catch rethrows it instead of walking the same rungs a
     * second time. Used for every recursive call made from INSIDE the try block; the catch block's
     * own step-downs call [createStreamableForQuality] directly.
     *
     * ⚠️ CancellationException is rethrown UNWRAPPED, which keeps today's behaviour
     * exactly: the caller's `catch (e: Exception)` still sees it and still steps down. Wrapping it
     * would make cancellation propagate properly instead - a better behaviour, and a separate
     * change.
     */
    private suspend fun descend(
        track: Track,
        quality: String,
        retry: Boolean,
        topQuality: String,
        allowSubstitute: Boolean,
        allowRetoken: Boolean,
    ): Streamable = try {
        createStreamableForQuality(track, quality, retry, topQuality, allowSubstitute, allowRetoken)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw LadderDescentException(e)
    }

    /**
     * Marks "a rung BELOW this one already failed", so a catch can tell that apart from "this
     * rung's own request failed". The two are otherwise identical at the catch and need opposite
     * handling. Private, and it never crosses more than one frame: the catch that recognises it
     * rethrows its cause, so [descend] one level up wraps that cause afresh rather than nesting.
     */
    private class LadderDescentException(cause: Throwable) : Exception(cause)

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
                    "FALLBACK_ID" to streamable.extras["FALLBACK_ID"].orEmpty(),
                    "TRACK_TOKEN_EXPIRE" to streamable.extras["TRACK_TOKEN_EXPIRE"].orEmpty()
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
            // Typed for the same reason as the throw above; `lastError` still rides along as the
            // cause, which is what MediaUnavailableException's optional second parameter exists for.
            // NOTE the rootCause of this is lastError's OWN deepest node, not this message - which is
            // why onPlayerError's isRetryExhausted check, which reads rootCause.message, is left alone
            // here rather than quietly re-pointed.
            resolved ?: throw MediaUnavailableException(
                "Track not available after retries: $trackId", lastError
            )
        } else {
            streamable
        }

        // ⚠⚠ Raw(...) IS CONSTRUCTED DIRECTLY INSTEAD OF VIA toSource(), AND ONLY TO CARRY A
        // TITLE. InputProvider.toSource(id, isVideo, isLive) does not forward one, so every Deezer
        // Source has title == null - which is why FormatUtils.sourceTitles' own comment says Deezer
        // contributes "a single short title or nothing".
        //
        // ⚠⚠ [CORRECTED 2026-10-16] THE LABEL IS UNREACHABLE ON DEEZER AND ALWAYS HAS
        // BEEN. THIS NOTE CLAIMED: "Setting it here is what makes a substituted recording visible, in
        // two surfaces that already exist and that YouTube Music already uses: PlayerFragment's
        // subtitle pill, via FormatUtils.getDetailsFormatFirst; and QualitySelectionBottomSheet's
        // source chip, `it.title ?: getString(quality_x, it.quality)`." BOTH EXPRESSIONS ARE QUOTED
        // CORRECTLY AND NEITHER ONE RUNS FOR US. Traced end to end on 2026-10-16 against a capture
        // where the substitute ladder provably worked (Sleep Walk, original refused at 320 and 128,
        // fallback played at 320, p=5-final role=fallback substituteLadder=true) and no label
        // appeared. Source.toMedia() is `Server(listOf(this), false)` - ONE source, merged = false -
        // and that single fact defeats both readers independently:
        //   THE CHIP: QualitySelectionBottomSheet's generic applyChips opens with
        //     `val visible = list.size > 1` and applies it to the label AND the ChipGroup, so with one
        //     source the whole Sources row is hidden and the text lambda is never called. (The SERVER
        //     row just above it overrides this with an explicit isVisible = true; the source row does
        //     not.)
        //   THE PILL AND THE SHEET'S INFO LINE: FormatUtils.sourceTitles takes the non-merged branch,
        //     `sources.getOrNull(index ?: -1)?.title`, where index is MediaItem.sourceIndex. That key is
        //     written ONLY by MediaItemUtils.buildSource, i.e. only when the user explicitly picks a
        //     source in the sheet, and its accessor defaults to -1 - so on every normal resolve
        //     getOrNull(-1) returns null and the title is dropped. The `merged` branch, which ignores
        //     the index, is the one we never take.
        // ⚠️ NOT A REGRESSION - THE GATES PREDATE THE MARKER BY A YEAR. git log -S puts
        // `val visible = list.size > 1` in 1cc44e6e (2025-03-02, "player ui") and
        // `sources.getOrNull(index ?: -1)?.title` in c88e0349 (2025-08-22, upstream). The marker was
        // built in 2026-10 against two upstream gates nobody read, which is why it has never been
        // seen on a device. THE SIGNATURE TO RECOGNISE: the extension side was VERIFIED CORRECT in
        // the 2026-10-16 QUALITY-PROBE capture (now removed) - substituteLadder=true with a differing
        // id, and the marker written - so a missing "Alt version" label is this, not a resolve bug.
        // Do not re-investigate the extension, and note that the capture that proved it is gone: a
        // re-check means re-instrumenting, which is a reason to trust this note rather than redo it.
        // WHAT IS STILL TRUE AND WHY THIS CODE STAYS: the title costs nothing, it is correct, and it
        // is what a fix consumes. Making it visible is an APP-SIDE change (the size>1 gate, or
        // sourceTitles' index handling, or a surface that does not go through either) and that is
        // where the work belongs - not here. YouTube Music is unaffected: it MERGES, so it has
        // several sources and takes the index-free branch.
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
        if (original.extras[INFO_DETAILS_EXTRA] == "1") return withInfoDetails(original)

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
        // ⚠⚠ [2026-10-27] THE GATE IS NOW "NOT USABLE", NOT "EMPTY" - see [hasUsableToken]. Everything
        // said above about provenance still holds for an EMPTY token; a non-empty one is now also
        // re-fetched when it has expired or carries no expiry, which is every token stored before this
        // build. That is what stops a token kept in a cache (a feed's buttons, an AA or Unified store,
        // a media-state entry) being used however old it is.
        // [CORRECTED 2026-10-27, 1152 capture] NOT A MEDIA-STATE ENTRY: a cache hit never calls this
        // function, so this gate never sees that entry's token. The 1152 capture showed exactly that
        // token failing at 128. The check for it is at the top of createStreamableForQuality's try,
        // fed by TRACK_TOKEN_EXPIRE in the streamables built below.
        val track = if (!original.hasUsableToken()) {
            val fresh = runCatching {
                api.track(original.id)["results"].objOrNull()?.let { results ->
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
                        // ⚠⚠ BLANKS FILTERED, AND THIS IS WHAT MAKES A RESTORED
                        // GRAFT PLAYABLE. `fresh` wins on a key collision, and the parser writes
                        // FALLBACK_ID unconditionally with `.orEmpty()` - so re-fetching by the
                        // TOP-LEVEL id (the substitute's own un-grafted record, FALLBACK absent)
                        // used to overwrite the seed's FALLBACK_ID with "". For a restored grafted
                        // favourite that is the one key the ladder needs, and losing it here made
                        // ResumptionUtils' decision to persist it pointless.
                        // ⚠️ NOTHING NEEDS CLEARING BY THE FRESH FETCH, which is
                        // why dropping its blanks is safe rather than merely convenient: TRACK_TOKEN
                        // is non-blank on this path by definition (a blank one means the re-fetch
                        // achieved nothing), FILESIZE_MP3_MISC is `?: "0"`, TYPE/__TYPE__ are
                        // literals and `loved` is "0"/"1". A fresh blank is always absence, never a
                        // correction.
                        extras = original.extras + fresh.extras.filterValues { it.isNotEmpty() }
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
                        "FALLBACK_ID" to track.extras["FALLBACK_ID"].orEmpty(),
                        // Read by createStreamableForQuality's token-age check, which a media-state
                        // cache hit reaches without passing through this function - see the note there.
                        "TRACK_TOKEN_EXPIRE" to track.extras["TRACK_TOKEN_EXPIRE"].orEmpty()
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
                        "FALLBACK_ID" to track.extras["FALLBACK_ID"].orEmpty(),
                        "TRACK_TOKEN_EXPIRE" to track.extras["TRACK_TOKEN_EXPIRE"].orEmpty()
                    )
                )
            }
        }
        return track.copy(
            streamables = streamables
        )
    }

    private val placeholderPrefix = "dzp:"

    /**
     * ⚠️ THE PLAYER INFO TAB'S DETAILS, AND NOTHING ELSE (2026-10-30). The app's TrackInfoViewModel asks
     * through loadTrack with [INFO_DETAILS_EXTRA] set, and this returns the track it was handed with ONLY its
     * missing contributors (CONTRIB_*), ISRC, release date and disc/track numbers filled - never a field the
     * track already has, no token, no streamables. The app displays the result and keeps it out of every
     * cache, the queue and playback; see TrackInfoViewModel.withInfoDetails.
     * WHY IT IS NEEDED: those fields are missing on the liked paths. The artist page's LikedEntry and Android
     * Auto's stored list (toSlimKeepingRoutingKeys) drop them, a disk-painted album or playlist page loses
     * ISRC and release date (Cached.toTracksCacheSlim), and loadTrack's re-fetch merge takes only the cover
     * and extras. Library -> Tracks shows none of them either, and nothing on our side drops them there
     * (graftFavTrack keeps the top-level record's fields, the feed cache removes only TRACK_TOKEN). INFERRED,
     * NOT VERIFIED: favorite_song.getList's records do not carry them. deemix and deezer-py do not call that
     * method at all - their liked list is song.getFavoriteIds + song.getListData - so its shape is unknown
     * from open source.
     * THE REQUEST: deezer.pageTrack, the one loadTrack's re-fetch already uses. deemix reads all five from its
     * DATA (bambanah/deemix packages/deezer-sdk/src/utils.ts map_track: `isrc: track.ISRC`,
     * `track_position: track.TRACK_NUMBER`, `disk_number: track.DISK_NUMBER`,
     * `release_date: track.PHYSICAL_RELEASE_DATE`, `song_contributors: track.SNG_CONTRIBUTORS`, applied to
     * gw.get_track_with_fallback's deezer.pageTrack body.DATA).
     * ⚠️ WHICH ID: FALLBACK_ID when the track has one, else its own id, and the answer is used only if its
     * album matches the album shown. A FALLBACK-grafted favourite (graftFavTrack) shows the FALLBACK record's
     * album, so its own id would fetch the substitute's ISRC and credits for a different recording. A track
     * whose FALLBACK is not the record shown (an album-page row) fails the album check and gets no fill,
     * which is the safe miss.
     * ONE REQUEST PER SONG PER SESSION, failures included (remembered as null), keyed by the track's id.
     * Nothing missing, no request. Best-effort: any failure returns the track as handed.
     */
    private suspend fun withInfoDetails(track: Track): Track {
        val clean = track.copy(extras = track.extras - INFO_DETAILS_EXTRA)
        if (!clean.lacksInfoDetails()) return clean
        val full = infoDetailsMutex.withLock {
            if (infoDetails.containsKey(clean.id)) infoDetails[clean.id]
            else {
                val fallbackId = clean.extras["FALLBACK_ID"].orEmpty()
                val fetched = runCatching {
                    api.track(fallbackId.ifEmpty { clean.id })["results"].objOrNull()
                        ?.let { parser.run { it.toTrack() } }
                }.getOrElse { if (it is CancellationException) throw it else null }
                    ?.takeIf { clean.album?.id.isNullOrEmpty() || it.album?.id == clean.album?.id }
                infoDetails[clean.id] = fetched
                fetched
            }
        } ?: return clean
        val missingContribs = full.extras.filter { (key, value) ->
            key.startsWith("CONTRIB_") && value.isNotEmpty() && clean.extras[key].isNullOrEmpty()
        }
        return clean.copy(
            isrc = clean.isrc?.takeIf { it.isNotBlank() } ?: full.isrc,
            releaseDate = clean.releaseDate ?: full.releaseDate,
            albumOrderNumber = clean.albumOrderNumber ?: full.albumOrderNumber,
            albumDiscNumber = clean.albumDiscNumber ?: full.albumDiscNumber,
            extras = clean.extras + missingContribs,
        )
    }

    private fun Track.lacksInfoDetails() =
        extras.keys.none { it.startsWith("CONTRIB_") } || isrc.isNullOrBlank() || releaseDate == null ||
            albumOrderNumber == null || albumDiscNumber == null

    // Session-only, bounded; a value of null records a failed or rejected fetch so it is not repeated.
    private val infoDetailsMutex = Mutex()
    private val infoDetails = object : LinkedHashMap<String, Track?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Track?>) = size > 500
    }

    /**
     * ⚠⚠ THIS EXTENSION AGES ITS OWN CREDENTIAL; THE APP CANNOT. TRACK_TOKEN is ours, kept in
     * Track.extras, and it outlives the request that issued it in every store that keeps extras - so
     * loadTrack must not take a non-empty one as current.
     * UNIT: EPOCH SECONDS. Verified 2026-10-27 against pleezer (github.com/roderickvd/pleezer, main),
     * src/protocol/gateway/list_data/mod.rs, both the song and the episode variant:
     * `#[serde(rename = "TRACK_TOKEN_EXPIRE")] #[serde_as(as = "TimestampSeconds<i64, Flexible>")]
     * expiry: SystemTime`. `Flexible` means Deezer may send it as a number or a string; DeezerParser's
     * `str` returns either as its content, and anything toLongOrNull rejects counts as unknown.
     * UNKNOWN EXPIRY COUNTS AS UNUSABLE, so every token stored before this build (none carries the key)
     * re-fetches once and is then stored with its expiry - migration-safe by construction.
     * [CORRECTED 2026-10-27] "ONCE" holds for stores that loadTrack rewrites. A media-state entry is
     * not rewritten on a hit, so an old entry (no expiry in its streamables) refreshes at the 128/mp3
     * rung on EVERY play that reaches that rung, until the entry is replaced - one api.track each.
     * The 5-minute margin covers device-clock skew and the gap between this check and the media
     * request. Uncalibrated.
     */
    private fun Track.tokenState(): String {
        if (extras["TRACK_TOKEN"].isNullOrEmpty()) return TOKEN_MISSING
        val expireS = extras["TRACK_TOKEN_EXPIRE"]?.toLongOrNull() ?: return TOKEN_NO_EXPIRY
        return if (expireS * 1000 - TOKEN_EXPIRY_MARGIN_MS > System.currentTimeMillis()) TOKEN_FRESH
        else TOKEN_EXPIRED
    }

    private fun Track.hasUsableToken(): Boolean = tokenState() == TOKEN_FRESH

    companion object {
        private const val TOKEN_FRESH = "fresh"
        private const val TOKEN_EXPIRED = "expired"
        private const val TOKEN_MISSING = "missing"
        private const val TOKEN_NO_EXPIRY = "no_expiry"
        private const val TOKEN_EXPIRY_MARGIN_MS = 5 * 60 * 1000L

        // Hand-off key for a substituted recording, written by createStreamableForQuality's
        // FALLBACK_ID block and read by loadStreamableMedia. Internal to this file - it never
        // reaches a Track's extras, so it cannot collide with the TRACK_TOKEN / FALLBACK_ID keys
        // that do.
        //
        // ⚠⚠ WHEN A SUBSTITUTION HAPPENED, SAY SO - A SILENT SWAP IS THE ACTUAL DEFECT.
        // The substitute's Track used to be consumed solely for the Blowfish key, so the user had
        // no way to tell that the audio was a different recording from the title on screen.
        // (These notes MOVED HERE on 2026-10-16 from createStreamableForQuality's
        // `isAlternateRecording`, which no longer exists: the substituting branch now returns the
        // result of its own recursive ladder and stamps this key on it directly, so the marker has
        // exactly one write site and this is the only place the mechanism is described.)
        // ⚠️ IT TRAVELS IN extras BECAUSE createStreamableForQuality CANNOT REACH THE
        // DISPLAY. The Track the player titles from was built by loadTrack, long before the stream
        // is resolved, and the Streamable returned carries no song metadata - its `title` is the
        // QUALITY label. So the signal is handed to loadStreamableMedia, which builds the Source
        // that the player's subtitle pill and the quality sheet read. See the Raw(title = ...) note
        // there.
        // ⚠️ AND NOT BY CHANGING THE TRACK'S OWN TITLE, which would be the obvious idea
        // and is the risky one: the knowledge arrives AFTER loadTrack has built the item, and a
        // mid-play metadata change runs through MediaMetadata.equals (which EXCLUDES extras),
        // StreamableMediaSource.canUpdateMediaItem's field gate, and PlayerTrackAdapter's
        // lastBoundMediaId - the three paths that cost several builds of album-art bugs.
        // ⚠⚠ A PRESENCE MARKER, NOT THE SUBSTITUTE'S TITLE - AND IT CARRIED THE TITLE
        // UNTIL 2026-10-07. The label can only be read in the quality sheet: the player's pill uses
        // FormatUtils.getDetailsFormatFirst, which appends source titles LAST by design (f2b661b0,
        // 2026-08-29: "when the line is cut the codec and bitrate are the part worth keeping"), and
        // the pill is one line of 12sp inside a 210dp maxWidth - about 30 characters, which
        // toAudioDetails' own "MPEG 128 kbps • 44100 Hz • 2ch" already fills. So anything
        // appended after it is past the ellipsis NO MATTER HOW SHORT, and a long label bought
        // nothing the sheet did not already give. Hoisting it in FormatUtils was scoped and
        // declined - that line is fenced off by the note above it.
        // ⚠️ [CORRECTED 2026-10-16] "THE LABEL CAN ONLY BE READ IN THE QUALITY SHEET"
        // OVERSTATES IT: ON DEEZER IT CANNOT BE READ ANYWHERE. The pill reasoning above is sound as
        // far as it goes and is kept, but it assumes the sheet shows the title - and the sheet hides
        // the whole Sources row when there is one source, which is always, for us. The full trace is
        // at loadStreamableMedia's Raw(...) note; read it before acting on anything in this
        // paragraph.
        // ⚠⚠ ONLY THE FALLBACK_ID BRANCH MARKS - AND THAT FIXES A BUG IN THE FIRST CUT
        // OF THIS CODE. TWO branches re-fetch a Track, and only one is a substitution: the "An
        // error occurred while decoding track token" branch re-fetches api.track(currentTrackId),
        // i.e. the SAME recording with a fresh token. An earlier `fallbackTrack?.title` form
        // labelled that as an alternate version, a false claim about the audio - exactly the kind
        // of mislabelling this marker exists to remove. The test used to be "the re-fetched id
        // differs"; it is now structural, since only the FALLBACK_ID block stamps the key.
        private const val ALT_VERSION_EXTRA = "ALT_VERSION"

        // Set ONLY by the app's TrackInfoViewModel (same literal there, no shared module): routes
        // loadTrack to withInfoDetails instead of a playback load.
        private const val INFO_DETAILS_EXTRA = "gladix_info_details"

        // ⚠️ HARDCODED ENGLISH, CONSISTENT WITH THIS MODULE. The extension has no resources
        // and no locale plumbing for display strings; its sibling labels ("FLAC", "320kbps",
        // "128kbps", "MP3") are hardcoded in createStreamableForQuality the same way. If this module
        // ever gains localisation, these four move together.
        private const val ALT_VERSION_LABEL = "Alt version"
    }
}