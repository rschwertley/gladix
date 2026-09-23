package dev.brahmkshatriya.echo.playback.listener

import android.util.Log
import dev.brahmkshatriya.echo.BuildConfig
import dev.brahmkshatriya.echo.common.Extension
import dev.brahmkshatriya.echo.common.clients.SearchFeedClient
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.getIf
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * ENDLESS-QUEUE FALLBACK: when the playing extension's radio has nothing useful left, ask Last.fm for
 * similar tracks and map them back through the extension's own search.
 *
 * ⚠️ EXTENSION-AGNOSTIC BY CONSTRUCTION, DEEZER-MOTIVATED BY EVIDENCE. Nothing here is Deezer-specific —
 * the trigger reads the post-dedup append, the mapping goes through the common SearchFeedClient — but
 * Deezer is the extension with the reproducible failure and the only one this is known to help.
 * Reach as measured 2026-09-09: REACHED by four extensions — Deezer (motivating case), Offline
 * (deliberately excluded, see below), Unified (delegates to sub-extensions), YouTube Music (implements
 * RadioClient but currently throws upstream: ytmkt requires `musicQueueRenderer` at a watchNext tab index
 * YouTube no longer populates). HELPS one. Spotify untestable for unrelated reasons; Combine unknown.
 * Reach is not a design goal for this pass and should not be designed toward.
 *
 * THE FAILURE IT EXISTS FOR, verified on device: "Underwater" by The Frogmen (1961 surf instrumental).
 * Deezer's track radio returns only that same recording, twice, under two album ids; artist radio and
 * album radio return the same; "Similar artists" is populated for The Beatles and EMPTY for The Frogmen,
 * so it fails for exactly the artists that need it. Deezer's own app has the same failure, so there is no
 * Deezer behaviour to copy. Last.fm's track.getSimilar for that track returns Soul Surfer / Johnny
 * Fortune, Fiberglass Jungle / The Crossfires, Intoxica and Surfs Up / The Original Surfaris, Surfer's
 * Cry / The Torquays, Mr. Moto / The Belairs — all genuine 1960s surf instrumentals, five of six present
 * in Deezer's catalogue when searched by artist + title.
 *
 * ⚠️ DEFERRED, NOT REJECTED — AUTO-RESUME AFTER A LATE APPEND. The lookup runs while the last track is
 * still playing. If it outlives that track, STATE_ENDED fires, PlayerEventListener settles playWhenReady,
 * and the append then lands into a PAUSED queue — the user presses play once. Resuming automatically is
 * the obvious completion and the mechanism exists (tvDriveRadio's seekToNextMediaItem + play).
 * IT IS DEFERRED ON PURPOSE, PENDING ONE SPECIFIC OPEN BUG: cold-start autoplay, whose finding is that
 * "a real play request is arriving and its source is unidentified", with ShufflePlayer.play() /
 * setPlayWhenReady(true) named as the only app-reachable universal gate. Adding a NEW app-initiated play()
 * now would put another candidate into exactly the set that investigation is trying to narrow — and the
 * queue parked at ENDED is already what converts a phantom play into audible playback.
 * REVISIT WHEN THAT ITEM CLOSES. It is a small change and the cost of waiting is one button press in a
 * rare case; the cost of not waiting is a harder diagnosis on an open bug.
 *
 * ⚠⚠ [STATUS 2026-09-23] THAT ITEM DID NOT CLOSE, AND IT IS NOT WAITING ON ANYONE EITHER -
 * IT WAS SET DOWN DELIBERATELY AT THE TIME. The decision taken then was: move on unless it becomes
 * an issue again. THE REASONING IS RECORDED HERE BECAUSE THE RECORD KEPT THE INSTRUCTION AND LOST
 * THE DECISION - the symptom is intermittent and phone-only, and capturing it means deliberately
 * triggering cold start after cold start until it happens, which at the observed rate is near
 * impossible. It has not been seen in over a month (last ~August 2026).
 * SO THIS IS A CLOSED QUESTION, NOT A QUEUED TASK. Do not read "revisit when that item closes"
 * above as waiting on a capture somebody will take.
 * ⚠️ WHAT THE INVESTIGATION ESTABLISHED, kept because it is the part worth having if the
 * symptom returns:
 *   - both prior fixes verified intact - the SHOW_NOTIFICATION_FOR_IDLE_PLAYER constant at NEVER,
 *     and PlayerEventListener's wasPlaying gate on the buffering-watchdog retry;
 *   - NOTHING IN THE COLD-START PATH SHOULD PLAY - every app-side initiator was accounted for;
 *   - and yet A REAL PLAY REQUEST ARRIVES, from a source that was never identified.
 * That last line is the finding. If it comes back, start there rather than re-deriving it.
 * ⚠️ ONE SUSPECT WAS SUBTRACTED AFTERWARDS: PlayerCallback.onPlaybackResumption reads as
 * declining a play during a cold-start load and does not - media3 plays anyway on the failure arm
 * (see the note there). That removes a candidate; it does not name one.
 * ⚠️ WHAT THIS DOES TO THE DEFERRAL ABOVE, STATED RATHER THAN DECIDED: that reason is about
 * not adding a candidate to a set an ACTIVE investigation is narrowing, and there is no active
 * investigation. The reason is therefore weaker - but not void, because the play-request source
 * was never identified, so a new app-initiated play() would make a recurrence harder to diagnose.
 * THE DEFERRAL IS LEFT AS IS, deliberately. This note is what to re-read if it is ever picked up,
 * not a signal that it now can be.
 *
 * ⚠️ THIS IS A FALLBACK, NOT A REPLACEMENT. It does not run on the normal path, does not replace the
 * extension's radio, and does not guarantee a match. The final rung is still the queue ending cleanly —
 * which since 2026-09-09 is a settled state (PlayerEventListener pauses at STATE_ENDED) rather than a
 * stuck one.
 */
object RadioFallback {

    // ⚠️ NOT AN ARCHITECTURAL FIRST, AND I INITIALLY THOUGHT IT WAS. The app already makes direct
    // third-party calls outside any extension: ExceptionUtils.getPasteLink (paste.rs),
    // AddViewModel.getExtensionList, and AppUpdater (GitHub releases). All three construct a BARE
    // `OkHttpClient()` per call site with DEFAULT timeouts — connect/read/write 10s and, critically,
    // callTimeout = 0, i.e. NO OVERALL BOUND. That pattern is not safe to copy onto the playback path:
    // this project has already had a third-party extension's non-cancellable blocking I/O wedge a mutex
    // and hang every browse node. So this client sets an explicit callTimeout, and the call is ALSO
    // wrapped in withTimeout — two independent ceilings, the same belt-and-braces shape as
    // StreamableLoader's withTimeout(30_000) sitting outside DeezerApi's own socket timeouts.
    private val client by lazy {
        OkHttpClient.Builder()
            .callTimeout(CALL_TIMEOUT_S, TimeUnit.SECONDS)
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .build()
    }

    private val json = Json { ignoreUnknownKeys = true }

    private const val CALL_TIMEOUT_S = 5L
    private const val OUTER_TIMEOUT_MS = 6_000L

    // Last.fm's free tier is 5 req/sec and needs no authentication — only an api_key in the query string.
    // We issue at most one request per exhausted station, so the rate limit is not reachable by design.
    private const val ENDPOINT = "https://ws.audioscrobbler.com/2.0/"

    // How many similar tracks to ask for, and how many catalogue matches to append. Kept small: the point
    // is to keep the queue alive, not to build a station. Each candidate costs one extension search.
    // Candidates REQUESTED from Last.fm. 12 -> 25 on 2026-09-11, together with the shuffle below.
    //
    // WHY: a station that is dead stays dead, so the same exhausted track is re-bridged with the SAME
    // candidates every time. At 12 requested and ~6 matching Deezer, the loop consumed effectively all of
    // them, so the bridge was not merely similar between plays — it was IDENTICAL, same tracks in the same
    // order. A bigger pool plus a shuffle makes it vary.
    // ⚠️ THIS DOES NOT RAISE THE EXPECTED SEARCH COUNT, which is what makes it cheap: the loop still stops
    // at MAX_APPEND matches, so at a given hit-rate it issues the same number of searches whether it is
    // drawing from 12 candidates or 25. It raises the POOL, not the work. The worst case is unchanged too,
    // because MAX_SEARCHES (15) bounds attempts, not candidates.
    //
    // ⚠️ 25 IS NOT DOCUMENTED AS AVAILABLE, AND NEITHER IS ANY CEILING. Last.fm's track.getSimilar page
    // describes `limit` only as "Maximum number of similar tracks to return" — no stated default, no stated
    // maximum. So this is an empirical question, and the LASTFM log line answers it directly: `similar=`
    // reports what actually came back. If it still reads 12 for a given track, that is Last.fm's supply for
    // that track and the larger request is inert for it.
    // ⚠️ AND THAT IS THE CASE WHERE THE SHUFFLE BUYS NOTHING — worth knowing before reading a flat result
    // as a bug. If a track's supply is 12 and 6 of them exist in Deezer, every candidate is searched
    // regardless of order, so shuffling changes WHICH ORDER the same six arrive in, not WHICH six. The
    // shuffle only produces a different SET once the supply exceeds what the loop consumes. Obscure tracks
    // — exactly the ones this fallback exists for — are the likeliest to have a thin supply, so a
    // persisting `similar=12` means this pass helped least where it was aimed.
    private const val SIMILAR_LIMIT = 25
    private const val MAX_APPEND = 6

    // Hard ceiling on EXTENSION SEARCHES per exhausted station. Each search is a network round trip AND —
    // until the non-gesture search signal lands — a write into the user's server-side search history, so
    // this is the number that bounds the pollution, not MAX_APPEND. Since 2026-09-11 SIMILAR_LIMIT is 25,
    // so this DOES bite: at most 15 of the 25 shuffled candidates are ever searched, which is precisely
    // what keeps the bigger pool free — more to draw from, no more work done. It is also what bounds the
    // timeout worst case: 15 x ~300ms lands under SEARCH_BUDGET_MS with room for one slow outlier.
    private const val MAX_SEARCHES = 15

    // Wall clock for the SEARCH LOOP ONLY — deliberately not wrapping the Last.fm fetch, which carries its
    // own OUTER_TIMEOUT_MS. Two bounded phases rather than one bound over both: a slow fetch can no longer
    // eat the entire budget and leave nothing for the searches that actually produce the append.
    private const val SEARCH_BUDGET_MS = 6_000L

    // ⚠️ EXCLUDED BY IDENTITY, NOT BY CAPABILITY — AND THE CAPABILITY TEST WOULD NOT CATCH IT.
    // OfflineExtension implements BOTH RadioClient and SearchFeedClient, and its radio is a real local
    // feature (loadTracks(radio) pulls same-artist songs plus library.songList.shuffled().take(25)), so it
    // CAN produce a thin append and WOULD reach this code. Two independent reasons it must not:
    //   1. IT CANNOT HELP. A Last.fm match can only map to something already in the local library, which
    //      that shuffle already covers. There is nothing for the lookup to add.
    //   2. IT WOULD BE WRONG EVEN IF IT HELPED. A network request made on behalf of the offline extension
    //      is wrong regardless of outcome — the user may be deliberately offline, which the
    //      connectivity-veto work already treats as a first-class state rather than an error.
    // The exclusion is a correctness boundary, not an optimisation. Do not "generalise" it away.
    private const val OFFLINE_ID = "echo-offline"

    val isEnabled get() = BuildConfig.LASTFM_API_KEY.isNotBlank()

    /**
     * Returns catalogue tracks to append, or an empty list. NEVER throws except on cancellation.
     *
     * @param extension the extension that owns the PLAYING item — not whatever the UI is browsing.
     *   PlayerRadio resolves this from player.currentMediaItem, so this inherits the right one by
     *   sitting where it does; do not reach for ExtensionLoader's "current" extension, which tracks
     *   BROWSING and would search the wrong catalogue.
     */
    suspend fun similarTracks(extension: Extension<*>, seed: Track): List<Track> {
        val artist = seed.artists.firstOrNull()?.name?.trim().orEmpty()
        val title = seed.title.trim()
        val q = "$artist - $title"
        val started = System.currentTimeMillis()

        // ⚠️ `searched` IS THE FIELD THAT DOES THE WORK NOW THAT APPENDS CAN BE PARTIAL. Before the
        // partial change, reason=ok meant one thing: a full bridge. It no longer does — matched=3 and
        // matched=6 both succeed, and without `searched` they are indistinguishable across sessions, so a
        // steadily degrading catalogue hit-rate would look identical to a healthy one. The three numbers
        // read together tell the whole story:
        //   similar=12 searched=12 matched=6  — full bridge, candidates to spare
        //   similar=12 searched=15 matched=3  — partial: hit MAX_SEARCHES with a poor hit-rate
        //   similar=12 searched=7  matched=3  — partial: the search budget expired mid-loop
        //   similar=12 searched=12 matched=0  — searched everything, catalogue had none of it
        // reason carries the TERMINAL CAUSE and the counts carry the shape; neither alone is enough.
        fun log(reason: String, similar: Int = 0, searched: Int = 0, matched: Int = 0) = Log.d(
            "GladixRadio",
            // ⚠️ artist= IS BROKEN OUT OF q DELIBERATELY. q reads as one opaque string and a
            // placeholder artist hides inside it - "Unknown - Doug The Jitterbug" looked like a
            // Last.fm coverage problem for an hour when it was a metadata TIMING problem. A separate
            // field is skimmable; a composite is not.
            "LASTFM q=\"$q\" artist=\"$artist\" reason=$reason similar=$similar " +
                "searched=$searched matched=$matched " +
                "ms=${System.currentTimeMillis() - started}"
        )

        // ⚠️ "NO KEY" IS ITS OWN REASON, NOT SILENCE. local.properties is git-ignored, so a fresh clone,
        // a new machine or a CI run builds with LASTFM_API_KEY empty and the fallback disabled — and a
        // silently-disabled feature is indistinguishable from a broken one. This line is what separates
        // "this build has no key" from the six runtime outcomes below. It costs one log line per exhausted
        // station, which is rare by construction (single-slot latch, one lookup per station).
        if (!isEnabled) { log("no-api-key"); return emptyList() }
        if (extension.id == OFFLINE_ID) return emptyList()
        if (artist.isEmpty() || title.isEmpty()) { log("no-seed-fields"); return emptyList() }

        // ⚠️ SIX REASONS, NOT FOUR, AND THAT IS THE POINT. A permanently failing endpoint must be
        // DISTINGUISHABLE from a fallback that simply never fires — otherwise "the fallback never helps"
        // and "the fallback never ran" look identical. This project has already carried a dead endpoint
        // for weeks: channels/home-pipe was recorded as "TIMEOUT (confirmed dead)" in June and was still
        // being called on every load afterwards. A persisting reason=no-similar is that signal.
        // Fires ONLY on this path, so a healthy session that never exhausts a station prints nothing.
        val similar = runCatching { fetchSimilar(artist, title) }
            .getOrElse { e ->
                if (e is CancellationException) throw e
                log(if (e is IllegalStateException) "parse" else "fetch-timeout")
                return emptyList()
            }
        if (similar.isEmpty()) { log("no-similar"); return emptyList() }

        // ⚠⚠ THE PARTIAL APPEND IS THE FIX. THE CAP ALONE WOULD HAVE CHANGED NOTHING.
        // Before this, the whole mapping ran inside one all-or-nothing bound: if the budget expired after
        // five of six matches, withTimeout threw, the result was discarded and NOTHING WAS APPENDED — five
        // successful catalogue searches thrown away because the sixth was slow. The queue then died exactly
        // as if Last.fm had returned nothing. Capping the search count reduces how often the budget is hit;
        // it does not change what happens WHEN it is hit. Only collecting into an outer list and returning
        // whatever is in it makes a timeout degrade instead of erase.
        // withTimeoutOrNull, not withTimeout, for the same reason: the block's value is discarded and the
        // ACCUMULATED list is what is returned.
        //
        // ⚠️ TWO INDEPENDENT CEILINGS — an established pattern here, not invented for this:
        // "RESOLVE_GRACE_MS (25 s) must stay below StreamableLoader's withTimeout(30_000) — that is what
        // gives two independent ceilings." Same shape: MAX_SEARCHES x ~300ms lands around 4.5s under the
        // SEARCH_BUDGET_MS wall clock, so the count bound normally bites first and the clock is the backstop
        // for a single slow search rather than the primary limit. Raising MAX_SEARCHES without raising the
        // budget collapses the two into one and puts the partial path on the common route.
        // ⚠️ THE ~300ms IS MEASURED ONCE, NOT A DISTRIBUTION. One observation on one extension, one
        // network, one catalogue. It is the weakest number here; if partial appends become the norm rather
        // than the exception, this is the assumption that broke, not the budget.
        // ⚠⚠ THE BUDGET ASSUMES THE EXTENSION'S SEARCH IS CANCELLABLE, AND FOR THREE OF THE FOUR
        // EXTENSIONS THAT REACH HERE WE CANNOT KNOW THAT. Kotlin cancellation is COOPERATIVE: withTimeoutOrNull
        // marks the coroutine cancelled and throws at the next SUSPENSION POINT. A third-party searchFeed doing
        // non-interruptible blocking I/O has no suspension point, so the timeout DOES NOT RETURN EARLY — this
        // call sits until that I/O completes on its own. Precedent in this project: "a third-party extension
        // performing non-cancellable blocking I/O wouldn't be interrupted by withTimeoutOrNull, so withLock's
        // finally never runs and every browse node hangs."
        // ⚠️ BUT IT IS A LEAK, NOT A WEDGE, AND THE DIFFERENCE IS STRUCTURAL — checked rather than assumed:
        //   • NO LOCK IS HELD. That incident wedged because the hang was inside cacheMutex.withLock; nothing
        //     on this path takes a mutex, so a stuck search blocks no other caller.
        //   • PLAYBACK IS UNAFFECTED. This runs in a scope.launch off the player's critical path; the current
        //     track keeps playing and the append simply never arrives. The queue then ends as it did before
        //     the fallback existed, and PlayerEventListener's STATE_ENDED settle handles it.
        //   • IT CANNOT REPEAT PER STATION. fallbackTriedForRadioId is set BEFORE this call, so a hung search
        //     cannot be re-entered for the same station.
        //   • THE COST IS ONE PARKED COROUTINE AND ONE Dispatchers.IO THREAD (getIf wraps in withContext(IO))
        //     per exhausted station, until the extension returns. Bounded by stations, not unbounded.
        // Survivable and worth knowing. If a wedge is ever observed instead, the thing that changed is that
        // something on this path started holding a lock — look there first, not at the budget.
        val matched = mutableListOf<Track>()
        var searched = 0
        var noSearchClient = false
        val completed = withTimeoutOrNull(SEARCH_BUDGET_MS) {
            // shuffled(), so a repeatedly-exhausted station does not rebuild the identical bridge each
            // time. Applied BEFORE take(MAX_SEARCHES) so the cap selects a different 15 of the 25 on every
            // run rather than always the same prefix — that ordering is the whole point, do not swap them.
            //
            // ⚠️ THIS IS THE ESTABLISHED PATTERN IN THIS CODEBASE, NOT A FRESH PREFERENCE — AND THE
            // PRECEDENT IS A POOL, SAMPLED, WHICH IS EXACTLY THIS SHAPE. DeezerRadioClient.loadTracks does
            // it for PLAYLIST and ALBUM stations: randomTracksFromSongs(parser, 8) draws EIGHT random
            // tracks (its body is literally `songs.shuffled()`), stores them as extras seed_ids /
            // seed_artists, and then fires api.radio(tId, aId) PER SEED concurrently and merges the
            // results. Not "one random pick instead of a fixed one" — a sampled pool feeding parallel
            // queries, which is structurally what this loop does with Last.fm candidates.
            // ⚠️ AND IT RE-SAMPLES ON EVERY LOAD, not once at station creation: loadTracks re-fetches the
            // source and calls randomTracksFromSongs AGAIN, falling back to the stored seed_ids only if
            // that fetch fails. So re-opening the same album radio gives different seeds — the same
            // property this shuffle gives a re-bridged station. The symptom it fixed was that "the radio
            // always generated music similar to the same one track regardless of what was in the
            // playlist/album"; the singular randomTrackFromSongs it replaced has since been deleted as
            // dead code, so this pool-and-sample version IS the current pattern, not a step toward it.
            // If you are inclined to make either deterministic again for reproducibility, that is the bug
            // both changes fixed.
            //
            // Last.fm returns its candidates in similarity order, so shuffling deliberately DISCARDS that
            // ranking. Accepted: past the first few, Last.fm's ordering is not meaningfully better than
            // random for this purpose, and variety between plays is worth more than a ranking the user
            // cannot see. If a "best match first" property is ever wanted, this is the line that traded it.
            for (candidate in similar.shuffled().take(MAX_SEARCHES)) {
                if (matched.size >= MAX_APPEND) break
                searched++
                val found = extension.getIf<SearchFeedClient, List<Track>> {
                    searchOne(candidate)
                }.getOrNull()
                if (found == null && searched == 1) noSearchClient = true
                val first = found?.firstOrNull() ?: continue
                if (matches(first, candidate)) matched.add(first)
            }
            true
        }

        if (noSearchClient) { log("no-search-client", similar.size, searched); return emptyList() }
        if (matched.isEmpty()) {
            log(if (completed == null) "search-timeout" else "no-match", similar.size, searched)
            return emptyList()
        }
        val reason = when {
            completed == null -> "ok-timeout"          // budget expired, but we kept what we had
            matched.size >= MAX_APPEND -> "ok"         // full bridge
            else -> "ok-partial"                       // ran out of candidates or hit MAX_SEARCHES
        }
        log(reason, similar.size, searched, matched.size)
        return matched
    }

    private data class Similar(val artist: String, val title: String)

    private suspend fun fetchSimilar(artist: String, title: String): List<Similar> =
        withTimeout(OUTER_TIMEOUT_MS) {
            withContext(Dispatchers.IO) {
                val url = ENDPOINT.toHttpUrlLike(artist, title)
                val body = client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    r.body.string()
                }
                // ⚠️ DETECT HTML BEFORE DESERIALISING. Last.fm can serve an error page, a rate-limit page
                // or a maintenance page — with a 200 as easily as a 5xx — and a JSON decode on that throws
                // a PARSE exception, not a network one. Two precedents, one of them in this tree:
                //   • AddViewModel.getExtensionList crashed on HTML when the marketplace URL 404'd after a
                //     repo rename; the fix was exactly this, detect HTML before deserialisation.
                //   • a "Failed to parse JSON: no available server" report whose key read
                //     extension_id: echo-offline sent an investigation after an extension that had made no
                //     network call at all.
                // Raised as IllegalStateException so the caller logs reason=parse rather than reason=timeout.
                check(!body.trimStart().startsWith("<")) { "Last.fm returned HTML, not JSON" }
                val root = json.parseToJsonElement(body).jsonObject
                root["similartracks"]?.jsonObject?.get("track")?.jsonArray.orEmpty().mapNotNull { el ->
                    val o = el as? JsonObject ?: return@mapNotNull null
                    val t = o["name"]?.jsonPrimitive?.contentOrNullSafe() ?: return@mapNotNull null
                    val a = o["artist"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNullSafe()
                        ?: return@mapNotNull null
                    Similar(a, t)
                }
            }
        }

    /**
     * One candidate, one search. The loop, the caps and the budget live at the call site so that a timeout
     * can keep what it already has — see the partial-append note there.
     *
     * ⚠️ DELIBERATELY SIMPLE, NOT CLEVER. Wrong matches are acceptable here — live versions and
     * re-recordings already reach the queue today with older catalogue, and a wrong-but-plausible neighbour
     * beats silence. The failure mode of cleverness is worse than the failure mode of strictness: a
     * mis-scored fuzzy match is unexplainable, a dropped candidate is one fewer track. So: exact-ish compare
     * after the same version-suffix strip the append dedup uses, first result only, no scoring.
     *
     * ⚠️ THIS CALL WRITES THE USER'S SERVER-SIDE SEARCH HISTORY, AND THAT IS AN OPEN DEFECT.
     * DeezerSearchClient.loadSearchFeed calls api.setSearchHistory(query) unconditionally when the history
     * setting is on, so every candidate searched here — matched or not — becomes a "Recent" entry on the
     * user's Deezer account. Reported from device: twelve junk queries per exhausted station, the user's own
     * searches pushed out. The app-side local store (SearchViewModel.saveInHistory) is NOT involved; it has
     * one caller and that caller is the user-typed path.
     * THE FIX IS NOT HERE — loadSearchFeed is the only entry point SearchFeedClient exposes and the write is
     * inside the extension, so it needs a non-gesture signal on the common interface (a defaulted overload,
     * safe because the app supplies `common` at runtime). Two other callers share the defect:
     * AndroidAutoCallback's voice search and its browse SEARCH node. MAX_SEARCHES is what bounds the damage
     * until then.
     *
     * NOTE ON PRECEDENT: the smarttracklist work is NOT a precedent for this — it resolved by ID
     * (GraphQL edges[].node.id -> song.getListData), never by text. The template is
     * AndroidAutoCallback.performSearch, which this mirrors.
     */
    private suspend fun SearchFeedClient.searchOne(s: Similar): List<Track> {
        val feed = loadSearchFeed("${s.artist} ${s.title}", isUserInitiated = false)
        // Case-insensitive TRACK tab, with a firstOrNull fallback for extensions that name it differently.
        val tab = feed.notSortTabs.firstOrNull { it.id.equals("TRACK", ignoreCase = true) }
            ?: feed.notSortTabs.firstOrNull()
        val (shelves, _) = feed.getPagedData(tab).pagedData.loadPage(null)
        return shelves.toTracks()
    }

    private fun matches(track: Track, s: Similar): Boolean {
        val a = track.artists.firstOrNull()?.name?.strip() ?: return false
        return a == s.artist.strip() && track.title.strip() == s.title.strip()
    }

    // ⚠️ USES PlayerRadio's DEFINITION — THERE IS EXACTLY ONE APP-SIDE COPY, AND IT MUST STAY THAT WAY.
    // This file briefly carried its own identical regex, which made THREE in the tree. Two of those three
    // were both in the `app` module and had no justification at all: an internal reference is free.
    // The remaining duplication (app <-> DeezerRadioClient) is the only genuine one, and it stays — see
    // the note on PlayerRadio.VERSION_SUFFIX for why `common` is not the answer.
    // IF YOU NEED THIS NORMALISER IN A FOURTH APP-SIDE PLACE, REFERENCE IT; DO NOT RETYPE IT.
    private fun String.strip() = PlayerRadio.stripVersionSuffix(this).lowercase()

    private fun String.toHttpUrlLike(artist: String, title: String) =
        "$this?method=track.getsimilar&artist=${artist.enc()}&track=${title.enc()}" +
            "&api_key=${BuildConfig.LASTFM_API_KEY}&format=json&autocorrect=1&limit=$SIMILAR_LIMIT"

    private fun String.enc() = java.net.URLEncoder.encode(this, "UTF-8")

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe() =
        runCatching { content.takeIf { it.isNotBlank() } }.getOrNull()

    private suspend fun List<Shelf>.toTracks(): List<Track> = flatMap { shelf ->
        when (shelf) {
            is Shelf.Item -> listOfNotNull(shelf.media as? Track)
            is Shelf.Lists.Tracks -> shelf.list
            is Shelf.Lists.Items -> shelf.list.filterIsInstance<Track>()
            else -> emptyList()
        }
    }
}
