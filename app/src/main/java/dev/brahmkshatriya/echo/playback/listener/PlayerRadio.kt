package dev.brahmkshatriya.echo.playback.listener

import android.content.SharedPreferences
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Player.REPEAT_MODE_OFF
import androidx.media3.common.Timeline
import dev.brahmkshatriya.echo.common.Extension
import dev.brahmkshatriya.echo.common.MusicExtension
import dev.brahmkshatriya.echo.common.clients.RadioClient
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Feed.Companion.pagedDataOfFirst
import dev.brahmkshatriya.echo.common.models.Radio
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.di.App
import dev.brahmkshatriya.echo.download.Downloader
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.get
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.getExtension
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.getIf
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.getOrThrow
import dev.brahmkshatriya.echo.extensions.MediaState
import dev.brahmkshatriya.echo.playback.MediaItemUtils
import dev.brahmkshatriya.echo.playback.MediaItemUtils.context
import dev.brahmkshatriya.echo.playback.MediaItemUtils.extensionId
import dev.brahmkshatriya.echo.playback.MediaItemUtils.isLoaded
import dev.brahmkshatriya.echo.playback.MediaItemUtils.track
import dev.brahmkshatriya.echo.playback.PlayerState
import dev.brahmkshatriya.echo.playback.queueEpochOrZero
import dev.brahmkshatriya.echo.utils.ui.UiUtils.isTv
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.MissingFieldException

class PlayerRadio(
    private val app: App,
    private val scope: CoroutineScope,
    private val player: Player,
    private val throwFlow: MutableSharedFlow<Throwable>,
    private val stateFlow: MutableStateFlow<PlayerState.Radio>,
    private val extensionList: StateFlow<List<MusicExtension>>,
    private val downloadFlow: StateFlow<List<Downloader.Info>>
) : Player.Listener {

    companion object {
        const val AUTO_START_RADIO = "auto_start_radio"
        private const val RADIO_PREFETCH_THRESHOLD = 3

        // How far back down the queue the append dedup looks. Big enough to survive a rotation of the same
        // recording across several album ids; small enough that a long restored queue is not rescanned and
        // that a station revisiting a track much later is not treated as a loop. Not tuned against data —
        // if a rotation ever exceeds this, the symptom is the loop returning and this is the first suspect.
        private const val DEDUP_WINDOW = 24

        // ⚠⚠ MULTI-SEED RE-SEED. N=4, NOT the 8 that DeezerRadioClient uses - SAME PATTERN,
        // DIFFERENT COST CURVE, and copying the constant would have been cargo-culting it.
        // randomTracksFromSongs(parser, 8) draws 8 rows out of an album/playlist tracklist the extension is
        // ALREADY HOLDING, inside ONE extension call: the 8 costs essentially nothing. Here each seed is a
        // separate radio() + loadTracks() pair, i.e. its own NETWORK ROUND TRIP from the app.
        // (That sentence originally read "TWO NETWORK ROUND TRIPS PER SEED" - see the [CORRECTED] block
        // below: for a TRACK seed radio() turns out to be local, so it is one. The contrast that motivates
        // N=4 is unchanged - rows the extension already holds versus a network call per seed.)
        // WHY 4 (inference, no measured per-seed success rate): if each independent seed yields a live
        // station with probability p, N seeds give 1-(1-p)^N. At p~=0.5 that is 87% at N=3, 94% at N=4, 97%
        // at N=5 - the gain flattens while the request cost stays linear.
        // ⚠️ 4 IS ALSO WHERE THE TRANSPORT STOPS BEING FREE, WHICH IS THE HARD CEILING.
        // Traced end to end 2026-09-10; every link was checked because the CONCLUSION SURVIVED A WRONG
        // FIRST REASON and the corrected chain is what makes it checkable:
        //   1. DeezerApi has no Mutex, no Semaphore and no single-thread dispatcher - calls are plain
        //      withContext(Dispatchers.IO). Nothing serialises them at the app layer.
        //   2. ⚠️ [CORRECTED] IT BUILDS THREE OkHttpClients, NOT ONE (client / clientLog /
        //      clientNP), each with its own Dispatcher and therefore its own independent cap. An earlier
        //      version of this note assumed a single client, which would have made the cap below wrong.
        //      IT IS NOT WRONG, BUT ONLY BECAUSE THE THREE ARE CHOSEN BY PURPOSE, NOT ROUND-ROBINED:
        //      callApi does `if (np) clientNP else client` and DeezerRadio.mix/mixArtist/flow never pass
        //      np, so EVERY radio call lands on `client`. The seeds cannot be spread across the three.
        //   3. None of them sets a custom Dispatcher, so OkHttp's defaults hold: maxRequests=64 (never the
        //      binding constraint here) and maxRequestsPerHost=5.
        //   4. Those limits only apply to ASYNC calls, so this turns on enqueue-vs-execute:
        //      ContinuationCallback.await uses enqueue(), so the Dispatcher DOES gate these. Had it
        //      wrapped execute(), the per-host cap would not apply at all and this ceiling would be void.
        //   5. All radio calls go to one host (callApi builds www.deezer.com), so they contend.
        // CONCLUSION: N=4 concurrent seeds sit under the per-host cap, so latency is MAX of the seeds and
        // not SUM. AT N>=6 THE SIXTH QUEUES BEHIND THE FIRST FIVE.
        // ⚠️ AND THE 5 IS SHARED, NOT RESERVED FOR US. Any other gateway call in flight on
        // `client` (metadata loads for the queue, a feed refresh) spends from the same 5, so a fan-out of 4
        // takes most of the budget. The effect of exceeding it is QUEUEING, not failure, and the ~9 minutes
        // of prefetch headroom absorbs it - but "4 of 5" is the honest figure, not "4, comfortably under".
        // Anyone raising this past 5 must set an explicit OkHttp Dispatcher or accept partial serialisation.
        // ⚠️ [CORRECTED] ONE ROUND TRIP PER SEED, NOT TWO. An earlier version of this note said
        // each seed costs a radio() plus a loadTracks() round trip. For a TRACK seed radio() is PURELY
        // LOCAL - DeezerRadioClient.asTrackRadio just builds a Radio out of fields the Track already
        // carries - so the only network call is the page load (song.getSearchTrackMix). The cost estimate
        // that justified N=4 was therefore CONSERVATIVE, not optimistic; the ceiling is unaffected because
        // it is set by concurrency, not by the number of calls.
        private const val RESEED_SEEDS = 4

        // How far back down the queue to walk looking for artist-distinct seeds. Bounded so a long restored
        // queue is not scanned; generous enough to clear a post-fallback bridge (6 tracks) plus a little.
        private const val SEED_SCAN = 12

        // Cap on what one fan-out appends. A single healthy station page is ~100 tracks (measured), so three
        // extra stations uncapped would add ~300 at once - queue bloat for no benefit, since the RETAINED
        // station keeps supplying afterwards. 60 is under one normal page and still far more than the
        // RADIO_PREFETCH_THRESHOLD needs to stop re-triggering.
        private const val RESEED_MAX_APPEND = 60

        // The extras key and value that mark a station as SEED-DETERMINED. Deezer's convention, and the app
        // is not merely a reader of it - PlayerCallback.trackRadio WRITES exactly this pair when it builds
        // an explicit track station, so for that path the app owns both ends. Named constants rather than
        // inline literals because the gate and the writer must not drift apart silently; see the gate note
        // at reseedFanOut for what a missing key costs and why the miss is logged.
        private const val RADIO_KIND_EXTRA = "radio"
        private const val RADIO_KIND_TRACK = "track"

        // ⚠⚠ THE SINGLE APP-SIDE DEFINITION. RadioFallback calls stripVersionSuffix below rather than
        // holding a copy; anything else app-side that needs it must do the same.
        //
        // WHY THIS IS NOT IN `common`, WHICH IS THE OBVIOUS HOME SINCE BOTH THE APP AND EVERY EXTENSION
        // DEPEND ON IT — judged 2026-09-09 and worth stating because "the app cannot depend on an extension
        // module" is TRUE BUT INCOMPLETE as a reason:
        //   • `common` IS THE EXTENSION ABI. Every symbol there is a permanent public contract kept by
        //     `-keep class dev.brahmkshatriya.echo.common.** { *; }` and anchored in verifyExtensionAbi.
        //     ADDING is much safer than changing — the R8 break was repackaging, not surface size — but it
        //     creates a NEW VERSION-SKEW HAZARD: an extension compiled against a `common` that has this
        //     function, running against an app whose `common` does not, throws the
        //     NoSuchMethodError/AbstractMethodError family. ExtensionUtils.getOrThrow already degrades that
        //     family SILENTLY as "outdated extension", so the failure would be invisible.
        //   • That is a real, if small, permanent cost. The thing being shared is a TWO-LINE REGEX. The
        //     trade does not clear the bar this project sets for that surface.
        // SO: ONE COPY PER MODULE BOUNDARY IS THE MAXIMUM DEFENSIBLE, and that is now the state — one here,
        // one in DeezerRadioClient. Three was one too many and the extra one was pure duplication.
        //
        // ⚠️ WHAT MAKES THE REMAINING PAIR DRIFT-RESISTANT, BEYOND THE MIRROR NOTES — because notes alone
        // have demonstrably not been enough in this repo: STRUCTURE, not prose. There is now exactly ONE
        // definition reachable from app code, so a future app-side consumer references it instead of
        // retyping (retyping is what produced the third copy in the first place). The cross-module pair is
        // only reconcilable by the notes, so it is kept to the SMALLEST possible surface — one regex, one
        // function, no options and no call-site variation — which is what makes a divergence obvious on
        // sight rather than something to reason about.
        private val VERSION_SUFFIX = Regex("""\s*\(.*\)\s*$""", RegexOption.IGNORE_CASE)

        fun stripVersionSuffix(s: String) = s.replace(VERSION_SUFFIX, "").trim()

        // Null when the track carries nothing comparable — caller keeps it. See the note at the call site.
        // ⚠⚠ THE `ta:` FORM IS NOT A FALLBACK. IT IS A SECOND NAMESPACE, AND CALLING IT A
        // FALLBACK IS WHAT HID THIS FOR THREE CAPTURES.
        // This returned ONE key and preferred ISRC: `isrc:X` when present, `ta:<title>\0<artist>` otherwise.
        // The word "fallback" implies the second form answers the same question less precisely. It does
        // not - it answers it in a DIFFERENT ALPHABET. `isrc:…` can never equal `ta:…`, so a track WITH an
        // ISRC and the same recording WITHOUT one were permanently unmatchable, and every reader (me
        // included) read "fallback" and assumed degraded-but-comparable.
        //
        // ⚠️ MEASURED, ONE RECORDING, THREE NON-MATCHING STRINGS (The Frogmen artist radio,
        // Deezer, clean force-stopped run 2026-09-11):
        //     seed as currentMediaItem, PRE-RESOLUTION (no ISRC yet)  ta:underwater\0the frogmen
        //     station copy A                                          isrc:...4082
        //     station copy B                                          isrc:...4053
        // The log read: offered=2 added=2, then 2/1, then 2/0 - the queue-tail window slowly catching up
        // to copies it should have rejected on arrival. All three are "Underwater" by The Frogmen.
        //
        // ⚠️ AND THIS IS THE SAME DEFECT AS THE PRE-RESOLUTION SEED, NOT A SEPARATE ONE - the two
        // threads only looked separate. resolveSeed fixed the QUERY by preferring the player's resolved
        // copy; it did nothing for the FILTER, which still reads `currentMediaItem?.track` directly and so
        // still keys the seed off whatever copy is in the timeline. Same root - an unresolved track has no
        // ISRC - surfacing once in RadioFallback's query and once here. See the note at resolveSeed.
        //
        // SO: EVERY FORM A TRACK CAN OFFER, AND A MATCH ON ANY ONE OF THEM IS A DUPLICATE. That makes the
        // coarse key a genuine fallback at last: it is present for every track regardless of ISRC or
        // resolution state, so two copies of one recording always share at least one key.
        // An EMPTY set means "nothing comparable" and the caller KEEPS the track - never dropped.
        private fun Track.dedupKeys(): Set<String> {
            val keys = HashSet<String>(2)
            isrc?.trim()?.takeIf { it.isNotEmpty() }?.let { keys.add("isrc:${it.uppercase()}") }
            if (stripVersionSuffix(title).isNotEmpty()) keys.add("ta:${recordingKey()}")
            return keys
        }
        // What play() appended and whether the station is spent. Returned rather than logged so the
        // re-seed escalation in loadPlaylist can gate on the SAME predicate play() uses internally -
        // see isThin.
        data class PlayResult(
            val appended: Int,
            val exhausted: Boolean,
            // The page load itself failed (extension threw, already reported). Distinct from "returned
            // nothing": a transient failure must NOT escalate, because play() has restored the prior
            // Loaded state for the next transition to retry and a fan-out would overwrite that.
            val failed: Boolean = false,
        )

        // ⚠⚠ THE SINGLE DEFINITION OF "THIS STATION GAVE US NOTHING USEFUL". Used by play() for the
        // Last.fm bridge and by loadPlaylist for the multi-seed escalation. Two call sites, one predicate,
        // deliberately - they must fire on the same condition or the two rescues disagree about whether a
        // station is dead.
        // Deliberately NOT "fewer than RADIO_PREFETCH_THRESHOLD": a healthy station legitimately appends
        // small pages WITH a continuation, and treating that as failure would fire both rescues constantly.
        fun isThin(r: PlayResult) =
            !r.failed && (r.appended == 0 || (r.appended < 2 && r.exhausted))

        // ⚠⚠ IN THE COMPANION, NOT ON THE INSTANCE, BECAUSE TWO CALLERS NEED IT AND ONE OF THEM
        // HAS NO PlayerRadio. PlayerCallback.trackRadio hits the same null-station failure and reaches this
        // class only through the companion (it already calls start() and play() that way). Taking
        // player/downloadFlow/app as parameters is the shape play() and appendDeduped already use, so this
        // is consistent rather than a new convention - and it is what stops trackRadio from growing a
        // second copy of the bridge.
        // ⚠⚠ THE THROW BRIDGE - FOR A STATION THAT NEVER EXISTS, NOT ONE THAT RUNS DRY.
        // Every other rescue in this file triggers on "the append was empty or thin after filtering". YouTube
        // Music never reaches that: ytmkt THROWS on every track, because YouTube changed the watchNext shape
        // and the library still requires a field tab 2 no longer has -
        //   MissingFieldException: Field 'musicQueueRenderer' is required for
        //   ...YoutubeiNextResponse.Content, missing at $...watchNextTabbedResultsRenderer.tabs[2]...
        // start() therefore returns null, loadPlaylist parks at Empty, and the queue simply ends.
        //
        // ⚠⚠ REACH, STATED PLAINLY SO NOBODY READS THIS AS GENERAL - IT HAS TWO HALVES AND THEY
        // POINT OPPOSITE WAYS.
        // ZERO FOR DEEZER, STRUCTURALLY. Deezer has NO @Serializable classes and decodes to a raw JsonObject,
        // so MissingFieldException cannot arise there at all. This buys nothing on the primary extension and
        // is dead weight if YTM goes away.
        // BUT ON YTM IT IS NOT LIMITED TO AN EXPLICIT RADIO TAP - it fires on ORDINARY LISTENING. Every
        // end-of-queue reaches here, which includes every one-track play from search, i.e. the common case.
        // An earlier version of this note implied it only helped someone who asked for a radio; that was wrong
        // and materially understated the value.
        // ⚠️ WITH ONE HONEST QUALIFIER: "every end-of-queue WHEN NO STALE STATION IS LOADED". If
        // radioFlow still holds a Loaded from a previous station, startRadio continues THAT station instead of
        // calling loadPlaylist, and this bridge is never reached. See the parked note at startRadio's Loaded
        // branch. So: not an unconditional every.
        //
        // ⚠⚠ WHY IT COVERS A ONE-TRACK PLAY IS FRAGILE, AND MUST BE READ AS FRAGILE. Playing a
        // single track from search calls getSongRadio TWICE:
        //   CALLER 1  PlayerCallback.trackRadio's own PlayerRadio.start(). NOT covered - it is in
        //             PlayerCallback and has no bridge.
        //   CALLER 2  startRadio -> loadPlaylist -> start(), because onTimelineChanged fires on the ONE-ITEM
        //             queue trackRadio just created and hasNextMediaItem() is false. COVERED - this is it.
        // (That is also why the two crash reports show different JSON paths for the same endpoint: two
        // invocations, not two endpoints.)
        // So the bridge covers the case NOT because trackRadio is covered, but because startRadio independently
        // fires on the queue trackRadio created. THAT IS AN ACCIDENT OF QUEUE SHAPE, NOT A DESIGNED PATH:
        //   - if trackRadio ever queued MORE THAN ONE item, hasNextMediaItem() becomes true and caller 2
        //     never fires;
        //   - if autoStartRadio is off, startRadio returns immediately and caller 2 never fires.
        // In either case the bridge is silently not reached. Covering PlayerCallback.trackRadio directly is
        // what would make this robust, and is the parked follow-up below.
        //
        // ⚠⚠ MissingFieldException ONLY - NOT THE SerializationException FAMILY. This was scoped as
        // "parse-family" and that would have been ACTIVELY HARMFUL. SerializationException IS reachable from
        // Deezer: decodeJson/decodeJsonStream pre-empt the empty-body case with an explicit retryable
        // IOException, so what gets past that guard and still fails to parse is a NON-EMPTY body that is not
        // JSON - a WAF challenge, an HTML error page, a truncated response - which throws JsonDecodingException,
        // a SerializationException. Bridging there would silently route around a LIVE TRANSPORT OR AUTH PROBLEM
        // and convert a surfaced, retryable condition into a quiet substitution.
        // MissingFieldException is safe STRUCTURALLY rather than heuristically: it can only be thrown by a
        // @Serializable decode whose declared schema disagrees with the payload. That IS "stale library against
        // a moving API", and it is not something a raw-tree parser can produce.
        // ⚠️ THE CONDITION THAT INVALIDATES THAT ARGUMENT, recorded because it cannot be reasoned
        // about now: IF A @Serializable DECODE IS EVER ADDED INSIDE THE RADIO PATH IN APP CODE, this classifier
        // silently starts routing around OUR OWN BUG instead of the extension's. Today there is none - common's
        // models are decoded in Serializer, not inside RadioClient.radio - so a MissingFieldException arriving
        // through an extension call is by construction the extension's. Re-check that before widening this.
        //
        // anyCause, NEVER rootCause, and the ONE walker in PlayerEventListener.kt rather than a second copy:
        // getIf -> get -> toAppException WRAPS the original, so the MissingFieldException is never the top node.
        // See that walker's own note - "never type-check a wrapped exception against a non-chain-walking
        // accessor" - which is the same mistake in a different subsystem.
        //
        // ⚠️ COUPLED TO ExtensionUtils.getOrThrow, WHICH ALREADY SILENTLY DEGRADES ONE FAMILY
        // (IncompatibleClassChangeError returns null with NO emit). Muting MissingFieldException there would be
        // acceptable ONLY AFTER this rescue exists; doing it before would have left no report AND no rescue -
        // strictly worse than doing nothing. The matching note is at getOrThrow.
        //
        // NOT COVERED, DELIBERATELY, PENDING THIS BEING OBSERVED TO WORK: PlayerCallback.trackRadio and
        // PlayerCallback.radio have the same null-station outcome and no bridge. For YTM a long-press -> Radio
        // routes to trackRadio, which plays the seed and stops. Whether they get the same treatment is a
        // separate decision once this one is seen working on device.
        // ⚠️ WHY NOT classify() - THERE ARE THREE ERROR CLASSIFIERS IN THIS APP AND THEY ARE NOT
        // INTERCHANGEABLE. Someone unifying "the two" will find three and pick the wrong one, so:
        //   ExceptionUtils.getTitle/getFinalTitle  phone snackbar text. Must stay byte-for-byte unchanged.
        //   ErrorCategory.classify()               AA head-unit mapping. Enum is {Network, LoginOrAuth,
        //                                          Generic} and it MIRRORS ONLY getTitle's network and
        //                                          login/auth matches, kept in lockstep by ErrorCategoryTest.
        //   Throwable.anyCause(predicate)          this, and PlayerEventListener's network handling.
        // As it stands, classify() cannot express what this site needs: a MissingFieldException lands in
        // Generic, which is also where every unrelated failure lands.
        // ⚠️ [CORRECTED] BUT classify() IS NOT FROZEN, AND AN EARLIER VERSION OF THIS NOTE IMPLIED IT
        // WAS. It said a new member "would either fail ErrorCategoryTest or force a change to the phone
        // snackbar path", which reads as a prohibition. IT IS NOT ONE - EXTENDING IT HAS PRECEDENT. On
        // 2026-08-19 ConnectException and NoRouteToHostException were added (it had been DNS-only), getTitle
        // was changed in step, and ErrorCategoryTest gained drift guards including a deliberate NON-match for
        // plain SocketException. The lockstep is the PROCEDURE for extending it, not a barrier to it.
        // SO THE REAL REASON IS COST AND AXIS, NOT PERMISSION:
        //   - THE AXIS IS WRONG. That enum answers "what should the user be told / how should AA present
        //     this" - Network, LoginOrAuth, Generic. "The extension's parser is stale against a moving API"
        //     has no user-facing category; the honest answer for the user REMAINS Generic. Extending it would
        //     not give this site a distinction, only a new name for one it cannot act on.
        //   - THE COST IS REAL. The procedure means changing getTitle in lockstep, i.e. touching the phone
        //     snackbar path another session deliberately left byte-for-byte untouched, and the only new string
        //     it could justify is one the user can do nothing about - the same argument that made the
        //     programmer-error guards in PlayerCallback report to throwableFlow and say nothing on screen.
        //   - AND THIS SITE DOES NOT WANT A CATEGORY AT ALL. It wants one boolean predicate at one call site,
        //     which is exactly what anyCause is.
        // That is also why anyCause was introduced originally rather than extending classify(): its enum could
        // not express DNS-hold-never-skip vs socket-reset-retry-once. Same shape of answer, same reasoning.
        suspend fun throwBridge(
            player: Player,
            downloadFlow: StateFlow<List<Downloader.Info>>,
            app: App,
            extension: Extension<*>,
            seed: Track,
            failure: Throwable,
            // The context loadPlaylist was generating FROM. Needed for the stamp - see the note at `context`
            // below; passing it is what keeps a live station's identity from being overwritten.
            itemContext: EchoMediaItem?,
        ) {
            // ⚠⚠ MissingFieldException IS EXPERIMENTAL API, AND THE OPT-IN IS ON THIS LOCAL RATHER
            // THAN THE FUNCTION SO IT COVERS EXACTLY ONE EXPRESSION - a later experimental usage elsewhere in
            // throwBridge will raise its own warning instead of being silently absorbed by a wider annotation.
            // Per-declaration @OptIn is this project's existing convention (nine sites across :app, :common and
            // the Deezer extension - e.g. ResumptionUtils, CacheUtils, DeezerApi.decodeJsonStream); there is no
            // module-wide compiler arg, and this deliberately does not introduce one.
            //
            // ⚠️ IF A kotlinx-serialization BUMP CHANGES THIS TYPE'S IDENTITY, THE FAILURE IS SILENT
            // AND TOTAL: the predicate stops matching, throwBridge never fires, and YTM radio quietly goes back
            // to not working with nothing in the log to say why. Joins the other "this could stop matching"
            // notes in this file - the anyCause-not-rootCause walk above, and the extras["radio"] gate.
            // WHAT THE OPT-IN BUYS, which is the reason to prefer it over a "stable" alternative: a COMPILE-TIME
            // signal. If the type is renamed or moved, this stops compiling. Every stable alternative fails
            // silently instead:
            //   - `is SerializationException` alone is WRONG, not merely broad - Deezer's JsonDecodingException
            //     is one, and it means a WAF page or truncated body, i.e. a live transport problem that must
            //     surface rather than be bridged around. See the classifier rationale above.
            //   - excluding JsonDecodingException explicitly is impossible: it is `internal` to
            //     kotlinx-serialization-json and cannot be referenced.
            //   - matching the message ("is required for") or the class name string would compile forever and
            //     break quietly on any reword or rename, with no warning at any point. Strictly worse.
            // So the experimental annotation is a FEATURE here: it is the only mechanism that will tell us.
            @OptIn(ExperimentalSerializationApi::class)
            val schemaDrift = failure.anyCause { it is MissingFieldException }
            if (!schemaDrift) return
            // Cheap reversible check FIRST, permanent claim second - the ordering trap hit on 2026-09-11 in
            // play(): claiming the (extension, seed) latch before the rescue marker would spend that seed's one
            // attempt on a bridge that never ran, and lock it out for good.
            if (!rescueInFlight.compareAndSet(false, true)) {
                Log.d("GladixRadio", "THROWBRIDGE reason=rescue_in_flight ext=${extension.id}")
                return
            }
            try {
                // Synthetic key: there is no station at all here, so no radio id exists. Keying on the
                // extension preserves the one-attempt-per-seed property the real latch has.
                // Epoch captured BEFORE the claim, because it is now part of the key - see claimFallback.
                // Same synthetic station component as before ("throw:<ext>", since no station exists here),
                // but keyed on the RECORDING rather than the track id: two catalogue copies of one song have
                // different track ids AND different ISRCs, so an id-shaped key cannot merge them.
                val epoch = player.queueEpochOrZero
                // ⚠️ RE-RESOLVE BEFORE CLAIMING. `seed` here is trackRadio's SERIALIZED
                // object, which on the long-press path is the bottom sheet's pre-resolution copy -
                // the one whose artist reads "Unknown". Preferring the player's resolved copy of
                // the same track is the whole fix for that path. Checked BEFORE claimFallback so an
                // unusable seed does not spend the one attempt for this (queue, station, recording)
                // - the same ordering rule as everywhere else in this file.
                val check = resolveSeed(player, seed)
                val resolved = check.seed ?: run {
                    Log.d("GladixRadio", "THROWBRIDGE reason=${check.reason} ext=${extension.id}")
                    return
                }
                if (!claimFallback(epoch, "throw:${extension.id}", resolved.recordingKey())) {
                    Log.d("GladixRadio", "THROWBRIDGE reason=already_claimed ext=${extension.id}")
                    return
                }
                val extra = RadioFallback.similarTracks(extension, resolved)
                if (extra.isEmpty()) {
                    Log.d("GladixRadio", "THROWBRIDGE reason=no_matches ext=${extension.id}")
                    return
                }
                // ⚠⚠ A REAL Radio CONTEXT PASSES THROUGH; THE PLACEHOLDER IS ONLY FOR WHEN
                // THERE IS GENUINELY NO STATION. Replacing a real context with trackRadioPlaceholder is a
                // defect that was fixed in September - "the real Radio context PASSES THROUGH rather than
                // being replaced by trackRadioPlaceholder", because Deezer's Track branch preserves
                // identity per kind (ARTIST keeps id/title/cover, PLAYLIST/ALBUM return the context as-is,
                // FLOW copies with a "... Flow" title, TRACK re-seeds on the newly tapped track).
                // ⚠️ AND IT IS NOT UNREACHABLE HERE, WHICH IS WHY THIS IS A CHECK AND NOT A
                // COMMENT. An unconditional placeholder was the first version of this bridge and it was
                // wrong: loadPlaylist reaches `loaded == null` with itemContext STILL SET whenever a live
                // station's own generation throws - a YTM artist radio going Empty, say - and stamping a
                // placeholder there would overwrite the station the user is actually in.
                // `as? Radio` rather than a bare elvis, deliberately: a Radio context means "you are in a
                // station" and these tracks continue it, but an Album/Playlist/Artist context means the
                // bridge tracks would be claiming membership of a collection they are not part of. For
                // those, the placeholder's "<seed> Radio" is the HONEST label - it is what the auto-radio
                // would have been called had it not thrown.
                val context = (itemContext as? Radio) ?: MediaItemUtils.trackRadioPlaceholder(seed)
                // Routed through appendDeduped for the same reason as the Last.fm bridge in play() - this
                // append had the identical filter gap, and inherits the epoch check along with it.
                val added = appendDeduped(
                    player, downloadFlow, app,
                    extra.map { extension.id to it }, context, epoch,
                    source = "throwbridge", seedKeys = seedKeysFor(context.id)
                )
                Log.d(
                    "GladixRadio",
                    "THROWBRIDGE reason=ok added=$added offered=${extra.size} ext=${extension.id}"
                )
            } finally {
                rescueInFlight.set(false)
            }
        }

        suspend fun start(
            throwableFlow: MutableSharedFlow<Throwable>,
            extension: Extension<*>,
            item: EchoMediaItem,
            itemContext: EchoMediaItem?,
            // ⚠⚠ THE TRACK THAT WAS PLAYING WHEN THIS STATION WAS BUILT, OR NULL - SUPPLIED BY THE
            // CALLER AND NOT READ FROM THE PLAYER HERE, BECAUSE ONLY THE CALLER KNOWS IF IT MEANS ANYTHING.
            // An earlier version of this took `player: Player` and read currentMediaItem inside. That is
            // wrong for the path it was written for: PlayerCallback.radio calls start() and only THEN does
            // `player.with { clearMediaItems() }`, so currentMediaItem is the OUTGOING queue's track - an
            // unrelated song, recorded as this station's seed, suppressing a legitimate track from the new
            // station. Silent, and in the direction that produces no report.
            // ⚠️ THE TWO CALLER KINDS NEED OPPOSITE ANSWERS, WHICH IS WHY THIS IS A PARAMETER:
            //   loadPlaylist (auto-radio)  APPENDS to the live queue, so the playing track stays, plays,
            //                              advances to the backStack and CAN be re-appended. That is the
            //                              duplicate this exists to stop - it passes the track.
            //   PlayerCallback.radio       REPLACES the queue, so the old track is not in the new station
            //                              at all and cannot duplicate - it passes null.
            //   .trackRadio / reseedFanOut pass a Track as `item`, already covered by the line below -
            //                              null.
            // REQUIRED rather than defaulted, deliberately: a future fifth path must state its intent
            // instead of silently inheriting one, which is the same reason the recording lives in here at
            // all (see the note at recordStationSeed below).
            playingSeed: Track?,
            // Handed the failure BEFORE getOrThrow consumes it, for callers that need to CLASSIFY it
            // rather than merely know it happened. Optional so the four existing call sites are unchanged,
            // and a local `var` at the caller rather than shared state - reseedFanOut runs three starts
            // CONCURRENTLY, so a companion-scoped "last failure" would misattribute.
            onFailure: ((Throwable) -> Unit)? = null,
        ): PlayerState.Radio.Loaded? {
            if (!item.isRadioSupported) return null
            val result = extension.getIf<RadioClient, PlayerState.Radio.Loaded?> {
                val radio = radio(item, itemContext)
                // Recorded HERE rather than at the callers so every station-creating path is covered by
                // construction - loadPlaylist, PlayerCallback.radio, PlayerCallback.trackRadio and
                // reseedFanOut all funnel through start(), and a future fifth path would too.
                recordStationSeed(radio.id, item)
                // ⚠⚠ AND AGAIN FOR THE PLAYING TRACK WHEN THE STATION WAS NOT BUILT FROM ONE,
                // WHICH IS THE WHOLE OF FIX 3 FOR ARTIST STATIONS. recordStationSeed starts with
                // `(item as? Track) ?: return`, so for an ARTIST, ALBUM or PLAYLIST station it records
                // NOTHING - and the 2026-09 "stash the seed's key for the life of the station" fix was
                // built and verified on an ARTIST station, so it never covered the case it was written for.
                // ⚠️ THE FAILURE IS AFTER THE SEED PLAYS, NOT BEFORE, which is why captures looked
                // clean. appendDeduped builds `existing` from seedKeys + currentMediaItem + a
                // DEDUP_WINDOW tail of the LIVE queue, so while the seed is still queued it is excluded by
                // the window. Once it plays, remove-on-advance moves it to the backStack - out of
                // currentMediaItem AND out of mediaItemCount - and seedKeys is the only thing left that
                // could exclude it. Empty for ARTIST, so it came back. The 1099 Frogmen capture
                // (offered=2 added=1, then added=0) measured the live-queue window working and never
                // reached the post-advance condition.
                // ⚠️ WHY NOT JUST ADD THE CURRENT TRACK TO `existing` AT APPEND TIME: that is
                // ALREADY THERE (appendDeduped's currentMediaItem line) and is exactly what does not help -
                // it expires the moment the track advances. The key has to be PERSISTED for the station's
                // life, which is what stationSeeds is.
                // ⚠️ recordStationSeed IS REUSED RATHER THAN OPEN-CODED so the "ta:" prefixing, the
                // per-station deque, STATION_SEED_CAP and the LRU touch all come along. Its own note
                // records that writing a raw recordingKey into that prefixed set silently never matches.
                // ⚠️ NO PLAYER READ HERE - the main-thread rule for currentMediaItem lives with the
                // caller that actually has a seed (loadPlaylist wraps it in withContext(Main), the way
                // resolveSeed does). reseedFanOut's three concurrent starts are fine either way:
                // recordStationSeed is synchronized on stationSeeds and the deque is capped, so three
                // seeds just coexist.
                playingSeed?.let { recordStationSeed(radio.id, it) }
                val tracks = loadTracks(radio).pagedDataOfFirst()
                PlayerState.Radio.Loaded(extension.id, radio, null) {
                    extension.get { tracks.loadPage(it) }.getOrThrow(throwableFlow)
                }
            }
            result.exceptionOrNull()?.let { onFailure?.invoke(it) }
            return result.getOrThrow(throwableFlow)
        }

        // ⚠⚠ ONE BRIDGE PER (STATION, SEED) - NOT PER STATION. THE KEY IS THE WHOLE FIX.
        //
        // ⚠️ [CORRECTED 2026-09-11] WHAT THIS USED TO BE AND WHY IT WAS WRONG. It was a single
        // `@Volatile var fallbackTriedForRadioId: String?`, and its note read: "at most ONE Last.fm lookup
        // per station, ever ... reset implicitly WHEN A DIFFERENT STATION BECOMES CURRENT."
        // THAT LAST CLAUSE IS TRUE FOR TRACK STATIONS AND FALSE FOR ARTIST STATIONS, and the note was
        // written without noticing it reasoned about only one kind:
        //   TRACK  - loadPlaylist re-seeds from the current track, DeezerRadioClient's TRACK branch does
        //            asTrackRadio(), and `id = item.id` - so every re-seed MINTS A NEW STATION ID and the
        //            latch renews itself. "A different station becomes current" really does happen.
        //   ARTIST - the ARTIST branch returns Radio(id = context.id, ...), i.e. THE ARTIST ID, ignoring
        //            the seed track entirely. Every re-seed produces THE SAME ID FOREVER, so the latch
        //            never renews. ONE BRIDGE PER ARTIST STATION, PERMANENTLY.
        // DEVICE, 2026-09-11 (The Frogmen ARTIST radio): the station ran dry, got exactly one bridge, and
        // then nothing. Eight further top-ups each regenerated the station and each declined both rescues -
        // the fan-out because an artist station is context-determined (four seeds collapse to one), the
        // bridge because the latch was spent. BOTH DECLINES WERE INDIVIDUALLY CORRECT. The composition was
        // the defect, and it lived in this key.
        //
        // WHY (radioId, seedTrackId) IS THE RIGHT KEY - the bridge IS seeded from a track, so "have I
        // already bridged FROM THIS SEED?" is the honest question, and it behaves correctly on both kinds:
        //   - on an ARTIST station the seed changes as playback advances, so each dry stretch gets one
        //     bridge per track: NATURALLY RATE-LIMITED BY PLAYBACK rather than by a counter;
        //   - on a TRACK station it changes nothing in practice, because radioId already moves;
        //   - it PRESERVES THE ORIGINAL PURPOSE, which was that "a thin station would otherwise re-trigger
        //     on every topUpQueue" - it still cannot, because the seed only changes on a transition.
        //
        // A SET, NOT A SLOT, because one slot is defeated by an A->B->A rotation: B's claim would evict A's
        // and A would bridge again immediately. FIFO with a cap of 8 - a rotation has to run through eight
        // distinct keys before the oldest ages out, which is far past any loop.
        //
        // ⚠⚠ [CORRECTED 2026-09-11, SECOND TIME] THE KEY NOW CARRIES THE QUEUE EPOCH, BECAUSE
        // (radioId, seed) MADE RE-ENTERING A STATION A PER-PROCESS DEAD END. Observed: an artist radio
        // bridged correctly, was left, and was RE-ENTERED in the same process - and produced NOTHING. Both
        // components are constant for an artist station (the artist id, and whichever track Deezer seeds
        // from), the deque is never cleared, and the latch counts ATTEMPTS not successes, so the second
        // visit was silently refused. Only a force-stop cleared it.
        // Same defect the first re-key fixed, one level up: (radioId, seed) renews as playback moves
        // BETWEEN TRACKS within a station, and does nothing for RE-ENTERING one.
        // ⚠️ WHY THE EPOCH AND NOT "CLEAR ON start()", WHICH IS THE INSTINCTIVE FIX: start() runs
        // on every RE-SEED too, and on an artist station every re-seed rebuilds the SAME radioId. Clearing
        // there would clear the claim on every top-up and reopen the unbounded retry the latch exists to
        // prevent - the bridge would re-fire on every transition of a thin station.
        // The epoch separates the two cases exactly, and is already proven to: it bumps ONLY on whole-queue
        // replacement (ShufflePlayer's eight seam sites, verified not to fire on advance). Entering a
        // station replaces the queue -> new epoch -> fresh claims. Re-seeding within one does not -> same
        // epoch -> the latch still holds. One attempt per (queue, station, recording): renewed per track by
        // the recording component, per visit by the epoch.
        // It also makes "never cleared" harmless - a key from a dead queue can never be regenerated.
        // ⚠️ AND THE CLAIM IS NOW ATOMIC, which the old `var` never was: check-then-set on a
        // plain field let two concurrent thin play() calls both pass. They can, and on an artist station
        // they collide on the SAME key, so the race was reachable exactly where it hurt.
        // ⚠⚠ ONE RESCUE AT A TIME. Held across the Last.fm bridge in play() and the whole body
        // of reseedFanOut - the two expensive, slow, network-bound rescues - and NOTHING ELSE.
        //
        // ⚠⚠ RESCUE-SCOPED ON PURPOSE: IT MUST NOT GUARD ORDINARY TOP-UPS. The obvious fix for
        // the re-entrancy this closes was to widen stateFlow = Loading over the fallback, and that is
        // WRONG, because topUpQueue and startRadio are EDGE-TRIGGERED - they run only from
        // onMediaItemTransition and onTimelineChanged, and there is no poll. A Loading that spans a ~12s
        // bridge does not DEFER those transitions, it DISCARDS them. If the queue drains inside the window
        // playback reaches STATE_ENDED, and on phone nothing re-fires (onPlaybackStateChanged routes to
        // tvDriveRadio only under isTv), so play() finishes and writes a perfectly correct Empty/Loaded
        // that no one will ever read again.
        // ⚠️ THAT LOST WAKEUP IS NOT A PREDICTION - THIS APP HAS ALREADY BEEN BITTEN BY IT. The AA
        // metadata freeze: AA rides Media3's DIFFED specific-callback dispatch (updateMetadataIfChanged off
        // onMediaItemTransition), the advance-past-hung-track SUPPRESSED that callback, AA never received
        // the good track's metadata and stayed frozen on lastMediaId. Edge-triggered callback suppressed,
        // nothing re-fires, state stuck. Same shape, different subsystem.
        // The release position of stateFlow in play() is therefore LOAD-BEARING and deliberate: it keeps
        // the queue serviceable during a long rescue. See the cannot-stall-or-spin proof at that release.
        //
        // ⚠️ SEPARATE MARKER, NOT A REUSED ONE - the same insistence recorded when
        // involuntarySkipInFlight was added as a DEDICATED flag, explicitly NOT to be conflated with
        // internalSeekInFlight (which gates the restore-seek latch), using this exact
        // `try { flag = true; ... } finally { flag = false }` shape. This is that established pattern, and
        // tvInFlight in this same file is the third instance: claim BEFORE the try, release in finally, so
        // every exit - early return, throw, coroutine cancellation - unwinds through the same release.
        // That is also why a marker is safe where stateFlow is not: stateFlow is released at a COMPUTED
        // POINT IN THE MIDDLE of the work, so its correctness depends on call order (three separate
        // defects this week turned on exactly that); a claim/finally pair has no computed release point.
        //
        // SIZE OF FIX, STATED SO IT IS NOT LATER MISTAKEN FOR A CORRECTNESS GUARD: post-queue-epoch this is
        // WASTE, NOT CORRUPTION. Two concurrent play() calls on the SAME queue both pass the epoch check
        // and both append, and appendDeduped dedups them. The eight RESEED lines observed on 2026-09-11
        // were eight redundant station regenerations - cost, not damage. One small marker is the right size.
        // ⚠⚠ THE FAMILY THIS CLOSES: "Loading HELD ACROSS NETWORK WORK WITH NO finally", FOUR SITES, TWO
        // DIFFERENT RESOLUTIONS. Read this before copying either one, because they are not interchangeable
        // and the wrong choice fails in a way that looks fine locally.
        // 
        //   play()'s Last.fm bridge   ~12s rescue      -> rescueInFlight (this flag); Loading NOT widened
        //   reseedFanOut              3 fetches, ~25s  -> rescueInFlight; Loading DROPPED entirely
        //   play()'s page fetch       one page load    -> try/finally; Loading KEPT
        //   loadPlaylist's start()    one generation   -> try/finally; Loading KEPT
        // 
        // THE DISCRIMINATOR IS NOT DURATION, THOUGH IT CORRELATES. It is whether blocking topUpQueue and
        // startRadio for the window is CORRECT or HARMFUL:
        //   - A short GENERATION is exactly what Loading is for. A second generation starting inside that
        //     window would be the duplicate this file has fought all week, so suppressing it is the point.
        //     Those sites keep Loading and only needed the missing finally.
        //   - A long RESCUE must NOT block ordinary top-ups. Those consumers are EDGE-TRIGGERED - they run
        //     only from onMediaItemTransition / onTimelineChanged, with no poll - so a Loading spanning the
        //     window DISCARDS the transitions it blocks rather than deferring them, and the queue can drain
        //     to STATE_ENDED with nothing left to re-fire it. Those sites needed a separate marker so the
        //     state machine keeps running while the rescue is in flight.
        // 
        // ⚠️ THE FOURTH SITE WAS FOUND WHILE FIXING THE THIRD, and only because the third was scoped as
        // "the last instance". play()'s page fetch has the identical shape - Loading, a network call, then
        // a settle - and was invisible in a comment-stripped read because a long comment sits inside its
        // elvis branch. Worth remembering that "last instance" claims in this file have been wrong twice.
        // 
        // Common to all four: the flag is set BEFORE the work and released in a finally that covers every
        // exit including cancellation. Cancellation is the exit every explicit restore missed, because it
        // unwinds past the restore line rather than through it.
        private val rescueInFlight = AtomicBoolean(false)

        // ⚠⚠ SET WHILE PlayerCallback.trackRadio IS QUEUEING ITS SEED AND GENERATING FOR IT, SO
        // THE INCIDENTAL GENERATION THAT ITS OWN QUEUE SHAPE TRIGGERS IS SUPPRESSED. A one-item queue has
        // no next item, so trackRadio's setMediaItems reaches startRadio (and topUpQueue) and a SECOND
        // radio request fires for the track the user tapped once - one wasted network round trip per
        // single-track play, on EVERY extension including Deezer.
        //
        // ⚠️ CLAIMED BEFORE setMediaItems, AND THAT ORDER IS FORCED, NOT PREFERRED. Media3
        // publishes the timeline event SYNCHRONOUSLY INSIDE setMediaItems - "updatePlaybackInfo ends with
        // listeners.flushEvents(), the legacy publishes complete synchronously inside setMediaItems()",
        // read off Media3's source in an earlier session and the reason a prepare() afterwards could not
        // fix a related problem. So onTimelineChanged has already scheduled its startRadio coroutine before
        // setMediaItems returns; anything that must be true first has to be set BEFORE the call.
        // ⚠️ THAT IS ALSO WHY THE QUEUE EPOCH CANNOT SERVE HERE, though it is the shape that
        // fixed the Last.fm latch tonight. The epoch is only readable AFTER setMediaItems has bumped it,
        // which is after the event has been scheduled - a window the boolean does not have.
        // ⚠️ AND NOT stateFlow = Loading, which is tempting because startRadio already has an
        // `is Loading -> {}` branch. See the note at reseedFanOut: stateFlow is not a mutex. Its consumers
        // are EDGE-TRIGGERED, so a Loading that spans this window DISCARDS the transitions it blocks rather
        // than deferring them, and a throw between set and clear pins it and strands the whole radio
        // subsystem. Using it here would contradict a note written three turns earlier.
        // RELEASED IN A finally that covers setMediaItems AND the generation, so a throw or a cancellation
        // cannot leave it stuck. Held across the ~12s Last.fm bridge deliberately: the only thing suppressed
        // in that window is generation FOR THE VERY QUEUE trackRadio is generating for. After its first
        // append, hasNextMediaItem() is true and startRadio self-suppresses anyway - this only covers the
        // gap before that.
        private val trackRadioGenerating = AtomicBoolean(false)

        /** Claimed by PlayerCallback.trackRadio BEFORE its setMediaItems; released in its finally. */
        fun markTrackRadioGenerating(active: Boolean) = trackRadioGenerating.set(active)

        private const val FALLBACK_LATCH_CAP = 8
        private val fallbackTried = ArrayDeque<String>()

        // ⚠⚠ COARSE RECORDING IDENTITY - TITLE+ARTIST ONLY, DELIBERATELY NEVER ISRC.
        // Separate from dedupKey() and NOT a duplicate of it. dedupKey answers "is this the same audio
        // file" and prefers ISRC because that is the precise answer. This answers "is this the same SONG BY
        // THE SAME ARTIST", which is what the bridge latch needs, and for that ISRC is actively wrong.
        // ⚠️ MEASURED ON DEVICE 2026-09-11: Deezer served the SAME recording of "Underwater" by
        // The Frogmen under ISRCs ending 4082 and 4053, with identical title and artist on screen. ISRC
        // DOES NOT IDENTIFY A RECORDING the way dedupKey's note assumed. So a latch keyed on anything
        // id-shaped - track id OR ISRC - cannot merge two catalogue copies of one song, which is precisely
        // what it must do: both bridges that night ran on seed "The Frogmen - Underwater" because the two
        // copies differ in BOTH track id and ISRC.
        // ⚠⚠ STAMP AT PUBLICATION, NEVER AT CONSTRUCTION - AND radio() IS THE COUNTER-EXAMPLE
        // THAT DECIDES IT, FOR THE SIXTH TIME TONIGHT. PlayerCallback.radio calls start() BEFORE its
        // clearMediaItems(), so a station stamped when it is BUILT would carry the pre-bump epoch and be
        // instantly stale - the queue would regenerate the station it had just created. Every path that
        // publishes a Loaded does so AFTER its own bump (trackRadio and radio() via play(); loadPlaylist
        // and reseedFanOut with no bump at all), so publication is the one moment that is correct on all
        // of them. This is the same shape that made clearing stationSeeds on the epoch bump unsafe, and
        // radio()'s ordering was the counter-example there too.
        private fun PlayerState.Radio.Loaded.forQueue(player: Player) =
            copy(epoch = player.queueEpochOrZero)

        /** The seed to query with, or null plus the reason it is unusable. */
        private data class SeedCheck(val seed: Track?, val reason: String?)

        // ⚠⚠ [CORRECTED 2026-09-12] THE HEADLINE BELOW IS WRONG AND IS KEPT BECAUSE IT READS
        // AS OBVIOUSLY RIGHT. It said: "THE BRIDGE MUST QUERY THE RESOLVED COPY OF THE SEED, NOT THE QUEUED
        // ONE." The Deezer measurement under it is real and still holds; the RULE generalised from it is
        // not. TWO INVERSIONS, MEASURED ON TWO EXTENSIONS, SAME DEFECT, OPPOSITE POLARITY:
        //     Deezer, long-press -> Radio   queued/passed copy "Unknown"   player's resolved copy CORRECT
        //     YTM,    long-press -> Radio   passed copy CORRECT            player's resolved copy "Unknown"
        // (YTM capture 2026-09-12: TRACKRADIO ext=Youtube_music seed=Kokomo, then
        //  LASTFM q="Unknown - Kokomo" artist="Unknown" reason=no-similar similar=0 matched=0.)
        // Preferring RESOLVED fixes Deezer and breaks YTM. Preferring PRE-RESOLUTION does the reverse.
        // ⚠⚠ SO NEITHER RESOLUTION STAGE NOR PROVENANCE IS THE AXIS - ONLY CONTENT IS. Note that
        // in the Deezer case BOTH copies were resolved, just by different loaders (MediaMoreBottomSheet
        // passes state.item from a MediaState.Loaded that MediaDetailsViewModel resolved; the player's copy
        // comes from StreamableLoader.loadTrack), so "which producer supplied it" does not predict quality
        // either. The rule that survives both captures is the only one that never looks at where a copy came
        // from: TAKE THE FIRST CANDIDATE THAT ACTUALLY HAS AN ARTIST. Loaded-ness is now a TIEBREAK among
        // usable copies, not a gate in front of them.
        //
        // ⚠️ WHY THE COPIES CAN DISAGREE AT ALL - ESTABLISHED BY A PRIOR AUDIT, NOT RE-DERIVED
        // HERE, WHICH IS WHAT MAKES IT INDEPENDENT EVIDENCE. The extensionId-promotion audit grep-confirmed
        // that `state` is written at EXACTLY ONE SITE inside MediaItemUtils.toMetaData - "every other path
        // is putAll-copy-then-overwrite-unrelated-keys (never rewriting state or extensionId) -> identical
        // at birth, copied together forever". That audit was done for a different purpose and found the same
        // property. (It cites MediaItemUtils.kt:207; the site is now :335 - the line drifted, the property
        // did not. See "In comments, reference symbols rather than line numbers".)
        // Consequence: resolution REPLACES the Track wholesale - there is no field-level merge - so an
        // extension whose loadTrack returns thinner metadata than it supplied silently overwrites the good
        // copy everywhere MediaItem.track is read. YTM is one instance of that class, not the class itself.
        //
        // ⚠⚠ THE GENERAL FIX IS HELD, AND THE REASON IS SPECIFIC RATHER THAN CAUTION. Preserving
        // the pre-resolution artist in the MediaItem bundle - the shape unloadedCover already uses one line
        // above the `state` overwrite - would cover play()'s bridge too, and would close the recordingKey
        // observation below as a side effect. It is not blocked on size: one artist name is negligible
        // against that comment block's own 20-30 KB-per-item measurement.
        // IT IS BLOCKED ON WHERE IT LANDS. The parked extensionId promotion targets "the budget-device
        // main-thread full-Track-JSON busy-decode ANR in MediaItemUtils.toMetaData" - so this would add a
        // second change to the exact hot path whose decode cost is already the subject of an open ANR item,
        // and the parked item exists PRECISELY BECAUSE that bundle is too expensive to decode. Two changes
        // to one hot path, one of them parked for the cost of the thing the other would add to. Scope them
        // together or not at all; note that unloadedCover is a precedent for the MECHANISM only - its own
        // note records it as a hot-path decode AVOIDANCE, which is the opposite concern.
        // FOUND BY A DISCRIMINATOR WORTH KEEPING, because it would otherwise be re-derived from scratch:
        // on the SAME track, a single-track play from search ALWAYS produced a working bridge and
        // long-press -> Radio NEVER did -
        //     search:     q="The Beach Boys - Kokomo"      similar=25 matched=6
        //     long-press: q="Unknown - Doug The Jitterbug" similar=0  matched=0
        // Two callers into trackRadio, passing different objects: FeedClickListener hands it the SEARCH
        // RESULT Track, while MediaMoreBottomSheet hands it state.item from MediaState.Loaded. Same track
        // id, different copies, and the query is built from whichever arrives.
        // The artist was never lost - the player's Info tab showed it correctly the whole time, because
        // TrackInfoViewModel reads `currentFlow.value?.mediaItem?.takeIf { it.isLoaded }?.track`, i.e. the
        // copy AFTER resolution, while the player header renders mediaMetadata written at build() time
        // from the pre-resolution seed. We were reading the right object at the wrong TIME.
        //
        // ⚠️ isLoaded IS THE ESTABLISHED SIGNAL FOR EXACTLY THIS, not a discriminator invented
        // here: it is already used as "the proven hung-track signal - only build() ran, loaded=false; a
        // cleanly-resolved track = true". Resolved-vs-unresolved is precisely what it means.
        // ⚠️ BUT isLoaded ALONE IS NOT ENOUGH, AND THE SAME INVESTIGATION THAT PROVED IT PROVED
        // WHY. There, "a 403 track resolves its metadata (isLoaded==true) before its stream fails, so
        // isLoaded==false alone would miss it" - resolution having RUN says nothing about the result being
        // USABLE. The inverse bites here: an extension can resolve a track and still supply a placeholder
        // artist, which passes isLoaded and still yields "Unknown - <title>". Hence two conditions with two
        // distinct reasons, because they mean different things:
        //     seed-not-loaded  resolution has not run yet - MAY become usable on a later attempt
        //     no-seed-fields   resolved, artist still blank - will NEVER become usable
        //
        // ⚠⚠ A PLACEHOLDER ARTIST IS NOT DETECTABLE GENERALLY, AND IS DELIBERATELY NOT FILTERED.
        // Blank or missing is checkable for any extension. The literal "Unknown" is not: it is a string an
        // extension happened to choose, indistinguishable from a band actually called Unknown without
        // hardcoding a per-extension, per-locale list. So a non-blank placeholder is allowed through and
        // made VISIBLE in the log instead - the fix for "Unknown" surfacing as similar=0 is that the query
        // is now readable at a glance, not that it is guessed at. Do not add a string blacklist here.
        //
        // ⚠️ SAFE TO PREFER THE PLAYER'S COPY ONLY WHEN IT IS THE SAME TRACK. throwBridge takes
        // its seed as a PARAMETER (trackRadio's serialized, unresolved object - the path the YTM failure
        // actually used), so it needs this more than play() does. But the user can skip between queueing
        // and the bridge firing, so the id check is what keeps "a later copy of the same track" from
        // quietly becoming "a different track".
        private suspend fun resolveSeed(player: Player, candidate: Track?): SeedCheck =
            withContext(Dispatchers.Main) {
                val current = player.currentMediaItem
                val sameTrack = current != null &&
                    (candidate == null || current.track.id == candidate.id)
                // ⚠️ `current` IS NON-NULL IN THE sameTrack BRANCHES BY CONSTRUCTION, NOT BY
                // LUCK: sameTrack is `current != null && ...`, and `current` is a local val captured ONCE
                // from player.currentMediaItem. Both !! here were unnecessary from the moment they were
                // written (Kotlin 2.4 carries the smart cast through a local val boolean); they were
                // defensive-by-assumption, not load-bearing, and nothing upstream changed to retire them.
                // ⚠️ THE CAPTURE IS THE PART THAT MATTERS. Re-reading player.currentMediaItem in
                // each branch would be the dangerous shape - a property that can return a different value
                // on every read, so no check on one read guards another. Reading it once into a local is
                // both why the smart cast is possible and why it is correct.
                val playerSeed = if (sameTrack) current.track else null
                // The TIEBREAK, and it only decides when BOTH copies are usable: prefer the loaded one,
                // since that is the copy actually playing and the one carrying ISRC and servers. When they
                // disagree on usability - which is both measured cases above - the content filter below
                // decides and this ordering never gets a say.
                val ordered = if (sameTrack && current.isLoaded) listOfNotNull(playerSeed, candidate)
                else listOfNotNull(candidate, playerSeed)
                val seed = ordered.firstOrNull { it.hasUsableArtist() }
                if (seed != null) return@withContext SeedCheck(seed, null)
                // ⚠️ THE THREE REASONS STILL MEAN DIFFERENT THINGS, AND THE ORDER OF THESE TESTS
                // IS WHAT KEEPS THEM HONEST. seed-not-loaded now means "no candidate we HAVE is usable AND
                // resolution is still pending", which is its true meaning - it used to fire while a
                // perfectly good candidate was in hand, which is exactly the YTM THROWBRIDGE line above.
                // Non-burning is unchanged and load-bearing: every caller claims via
                // `seed != null && claimFallback(...)`, so a null seed short-circuits before the claim and
                // spends nothing. That is what makes "MAY become usable later" a true statement.
                return@withContext when {
                    ordered.isEmpty() -> SeedCheck(null, "no-seed")
                    sameTrack && !current.isLoaded -> SeedCheck(null, "seed-not-loaded")
                    else -> SeedCheck(null, "no-seed-fields")
                }
            }

        // Blank or missing is checkable for ANY extension; a non-blank placeholder like "Unknown" is not,
        // and is deliberately allowed through - see the note at resolveSeed's placeholder paragraph.
        private fun Track.hasUsableArtist() =
            !artists.firstOrNull()?.name?.trim().isNullOrEmpty()

        // ⚠️ UNMEASURED OBSERVATION, RECORDED BEFORE THE BUNDLE WORK IS SCOPED BECAUSE THAT
        // WORK WOULD ALSO CLOSE IT. If an extension stamps a placeholder artist catalogue-wide - YTM
        // resolves every track to "Unknown", measured 2026-09-12 - then this returns `<title>\0unknown` for
        // EVERY track on it, and dedupKeys()'s `ta:` namespace loses its artist discriminator there. Under
        // match-on-either, two genuinely different recordings that share a title would then collide and one
        // would be DROPPED. It fails quiet - a missing track, not a duplicate - which is the direction that
        // does not generate a report. Not looked for in any capture yet; do not treat it as observed.
        private fun Track.recordingKey(): String {
            val t = stripVersionSuffix(title)
            val a = artists.firstOrNull()?.name?.trim().orEmpty()
            return "${t.lowercase()}\u0000${a.lowercase()}"
        }

        /**
         * Atomically claims the one bridge attempt for this (queue, station, recording).
         * False if already claimed.
         */
        private fun claimFallback(epoch: Long, radioId: String, recording: String): Boolean =
            synchronized(fallbackTried) {
                val key = "$epoch\u0000$radioId\u0000$recording"
                if (fallbackTried.contains(key)) return@synchronized false
                fallbackTried.addLast(key)
                while (fallbackTried.size > FALLBACK_LATCH_CAP) fallbackTried.removeFirst()
                true
            }

        // ⚠⚠ EVERY SEED A STATION HAS BEEN GENERATED FROM - A STATION MUST NEVER APPEND ITS OWN
        // SEED. (Read the bound below before relying on "never" - the INVARIANT is unconditional, the
        // IMPLEMENTATION is a bounded approximation of it, and the two are not the same claim.)
        // Deliberately SEPARATE from the queue-tail rotation dedup below, which
        // answers a different question and is allowed a different answer: that window intentionally
        // tolerates "a station legitimately revisiting a recording an hour later". Re-appending the track
        // the station was BUILT FROM is wrong however long ago it played.
        //
        // ⚠️ WHY THE APP HAS TO HOLD THIS. DeezerRadioClient filters its seed for RadioKind.TRACK
        // only, and for ARTIST it structurally CANNOT: it compares `it.id != radio.id`, where radio.id is
        // the ARTIST id, so it never matches a track id. Recorded as a prediction weeks ago; observed on
        // device 2026-09-11 when "Underwater" was re-appended to The Frogmen's artist radio.
        //
        // ⚠⚠ AND THE APP'S OWN DEDUP COULD NOT CATCH IT EITHER - THIS IS THE SECOND DEFECT FROM
        // ONE DURABLE WRONG PREMISE. The window below scans player.getMediaItemAt over THE LIVE TIMELINE,
        // and its note claimed "the queue itself is the record of what was appended". IT IS NOT.
        // ShufflePlayer.pushAndRemove REMOVES each departing track from the timeline into backStack, so the
        // timeline is [current, upcoming...] and the window sees only UPCOMING items. Anything already
        // played is invisible to it. The queue is FORWARD-LOOKING; the history lives in backStack and the
        // history file.
        // That same premise - reasoning about the live timeline as though it held history - has now
        // produced two separate defects in this file and was corrected from the device both times. It is
        // durable precisely because the code reads as if it were true. If a third dedup question comes up,
        // ASK WHERE THE HISTORY ACTUALLY IS FIRST.
        //
        // Keyed by radioId and ACCUMULATING PER STATION, because an artist station keeps ONE id across many
        // re-seeds and each re-seed uses a different track: "the seed" is a growing set for that kind, not
        // one value. A single-valued store would remember only the most recent seed and let every earlier
        // one back in, which is the defect this exists to stop.
        //
        // ⚠⚠ BOUNDED ON BOTH AXES, AND IT HAS TO BE BOTH - ONE CAP IS NOT ENOUGH HERE.
        //   SEEDS PER STATION  - a dry artist station re-seeds once per track, forever, so the per-station
        //                        set grows with listening time and nothing else would evict it.
        //   STATIONS REMEMBERED- radioId is the map key, so a session that visits many stations accumulates
        //                        ENTRIES indefinitely even if each is small.
        // ⚠️ [CORRECTED 2026-09-11] THE FIRST VERSION HAD ONE GLOBAL FIFO OF 16 PAIRS. That did
        // bound total memory, so it was not unbounded as first reported - but a GLOBAL cap is the wrong
        // shape: two stations in rotation evict EACH OTHER'S seeds, so the protection a station gets
        // depends on unrelated traffic. Per-station caps make each station's memory its own.
        // Ceiling is STATION_CAP x STATION_SEED_CAP = 40 short strings, comparable to DEDUP_WINDOW's 24 and
        // far under backStack's 1000. Bounded by construction, in the same spirit as those and as the
        // SnackBarHandler queue that was capped at 16 for exactly this reason.
        //
        // ⚠️ NOTHING CLEARS THIS, AND THAT IS DELIBERATE - THE OBVIOUS PLACE TO CLEAR IT IS UNSAFE.
        // ShufflePlayer's queue-replacement seam looks right (it marks the moment the old station stops
        // mattering) and it is NOT, because of call ORDER: PlayerCallback.radio calls PlayerRadio.start -
        // which records the seed - BEFORE its clearMediaItems(), so clearing on the epoch bump would wipe
        // the seed it had just recorded, microseconds before play() reads it back. Fix 3 would silently
        // do nothing on that path. (trackRadio has the opposite order and would be fine; radio() is the
        // counter-example, and one is enough.) Reordering radio() to suit this is not worth it.
        // Instead, stale stations AGE OUT BY RECENCY: recordStationSeed re-inserts the station at the end
        // on every touch, so the map is recency-ordered and the eldest UNUSED station is evicted first. A
        // station the user has left stops being touched and falls off on its own.
        //
        // ⚠️ WHAT THE BOUND COSTS, STATED RATHER THAN GLOSSED: a seed from more than
        // STATION_SEED_CAP re-seeds ago CAN be re-appended. At one seed per track that is roughly the last
        // eight tracks of that station. That is the same territory the rotation window below already
        // tolerates on purpose ("a station legitimately revisiting a recording an hour later is not a
        // loop"), so the two degrade consistently rather than one silently undercutting the other.
        private const val STATION_SEED_CAP = 8

        // ⚠⚠ DERIVED, NOT PICKED: ONE FULL FAN-OUT OF STATION CREATIONS, PLUS THE ONE BEING
        // PLAYED. reseedFanOut is the only thing that creates stations WITHOUT replacing the queue, and it
        // creates RESEED_SEEDS of them in a burst (the primary re-seed plus its extra seeds). A cap below
        // that lets a single fan-out evict the seeds of the station the user is actually listening to. The
        // +1 is the station being played, so it survives a whole burst even if it were never read.
        private const val STATION_CAP = RESEED_SEEDS + 1
        private val stationSeeds = LinkedHashMap<String, ArrayDeque<String>>()

        private fun recordStationSeed(radioId: String, item: EchoMediaItem) {
            // ⚠️ THE COARSE KEY ALONE, NOT dedupKeys() AND NOT BOTH FORMS - the open question
            // from 2026-09-11, resolved. Under match-on-either the coarse key matches whenever the precise
            // one does, PLUS the cases the precise one misses (no ISRC, or a different ISRC for the same
            // recording). A stored `isrc:X` would therefore be redundant on one axis and useless on the
            // other. It also makes this consistent with claimFallback, which already keys on recordingKey
            // for exactly the same reason.
            // ⚠️ STORED IN THE SAME NAMESPACE THE FILTER COMPARES IN - "ta:" PREFIXED. seedKeys
            // go straight into appendDeduped's `existing`, which is tested against dedupKeys() output;
            // storing the raw recordingKey here would put an unprefixed string in a prefixed set and
            // silently never match. (Caught immediately after writing it - the prefix is exactly the
            // namespace distinction this whole change is about, and it is easy to drop when moving
            // from dedupKey(), which carried its own prefix, to recordingKey(), which does not.)
            val key = (item as? Track)
                ?.takeIf { stripVersionSuffix(it.title).isNotEmpty() }
                ?.let { "ta:${it.recordingKey()}" } ?: return
            synchronized(stationSeeds) {
                val seeds = stationSeeds.remove(radioId) ?: ArrayDeque()
                if (!seeds.contains(key)) {
                    seeds.addLast(key)
                    while (seeds.size > STATION_SEED_CAP) seeds.removeFirst()
                }
                // Re-inserted at the END on every touch, so LinkedHashMap's insertion order IS recency
                // order and the eviction below drops the least recently used station, not the oldest one.
                stationSeeds[radioId] = seeds
                while (stationSeeds.size > STATION_CAP) {
                    stationSeeds.remove(stationSeeds.keys.first())
                }
            }
        }

        // ⚠⚠ READING REFRESHES RECENCY, AND THAT IS WHAT MAKES THE LRU EVICTION SAFE RATHER
        // THAN MERELY PLAUSIBLE. The tempting argument for a small STATION_CAP is "a station you have not
        // touched recently is not appending, so its seeds are not being consulted". That is TRUE ONLY IF
        // CONSULTING COUNTS AS TOUCHING - and with a write-only refresh it did not, because
        // recordStationSeed runs in start() (station CREATION) while this runs in play() (every append).
        // A healthy long-running station calls start() ONCE and then tops up through its continuation
        // forever, so its recency would have been frozen at creation while it was still actively
        // appending. One reseedFanOut - which creates RESEED_SEEDS stations in a burst - could then evict
        // the seeds of the station currently playing, and its next append would go UNFILTERED and could
        // re-append its own seed. That is the exact defect this store exists to prevent, reintroduced
        // through the eviction policy.
        // Re-inserting on read closes it: a station being consulted is by definition still in use, so it
        // stays hot, and only genuinely idle stations age out. Idle stations are not appending, so losing
        // them costs nothing - which is the premise above, now actually true instead of assumed.
        // A miss does NOT create an entry: absent stays absent, so a read cannot grow the map.
        private fun seedKeysFor(radioId: String): Set<String> = synchronized(stationSeeds) {
            val seeds = stationSeeds.remove(radioId) ?: return@synchronized emptySet()
            stationSeeds[radioId] = seeds
            seeds.toSet()
        }

        // ⚠⚠ THE ONE PLACE THE QUEUE-TAIL DEDUP LIVES. Extracted from play() on 2026-09-10 when the
        // multi-seed re-seed became a SECOND caller that appends station tracks. Two copies of a
        // windowed dedup that must agree is precisely the drift this file has already been bitten by
        // once (see the [CORRECTED] note below on 'radio already has its own dedup'), so the second
        // caller got an extraction rather than a paste.
        // `entries` is clientId-to-track because the multi-seed caller merges tracks from SEVERAL
        // stations that may belong to DIFFERENT extensions under Unified - each track must keep the
        // id that will resolve it. play() passes the same clientId for every entry, which is the
        // degenerate case of the same rule.
        suspend fun appendDeduped(
            player: Player,
            downloadFlow: StateFlow<List<Downloader.Info>>,
            app: App,
            entries: List<Pair<String, Track>>,
            context: EchoMediaItem,
            // The queue this work was started against - see the ownership check below.
            epoch: Long,
            // Which append site this is, for the log line at the bottom. Required, not defaulted: a new
            // caller must name itself, because an untagged append is exactly what made the 2026-09-11
            // duplicate hunt undecidable.
            // ⚠⚠ THE LAST TWO ARE PASSED BY NAME AT ALL FOUR CALL SITES, DELIBERATELY. This
            // function takes six leading positional parameters, and on 2026-09-11 adding `source`
            // between epoch and seedKeys silently slid every caller's seedKeys argument into the
            // source slot - four identical "Set<String> where String expected" errors that said
            // nothing about the actual cause. Naming the trailing arguments makes a future insertion
            // here a compile error at the DECLARATION rather than a shifted meaning at the callers.
            source: String,
            // Every seed this station was generated from. Excluded unconditionally, at any distance -
            // see the note at stationSeeds for why this is NOT the same question as the window below.
            seedKeys: Set<String> = emptySet(),
        ): Int = withContext(Dispatchers.Main) {
            // ⚠⚠ OWNERSHIP CHECK: NEVER APPEND INTO A QUEUE THIS WORK WAS NOT STARTED AGAINST.
            // Every caller fetches from the network and appends on return, so the queue can be REPLACED in
            // between - switch stations mid-fetch and the tracks land in the new queue, stamped with the OLD
            // station's context and clientId. Observed 2026-09-10: an orphaned Last.fm bridge from a wiped
            // queue appended three tracks into the station that replaced it. Benign that time (surf into
            // surf); the ordinary case is Sinatra-adjacent tracks arriving in a Lil Wayne queue, with no
            // error and no indication.
            // ⚠️ COMPARED BY EPOCH, NOT BY CONTEXT. Context identity is NOT stable across
            // regeneration: loadPlaylist mints a new Radio on every re-seed, while asTrackRadio sets
            // id = seed.id, so two independent generations of one seed share an id. Comparing contexts gives
            // false matches AND false mismatches. A monotonic counter has no identity semantics.
            // No lock needed: ShufflePlayer's seam runs on the application looper (Media3 enforces the app
            // thread) and this block is on Main, so check-then-append cannot interleave with a bump.
            if (player.queueEpochOrZero != epoch) {
                Log.d(
                    "GladixQueue",
                    "stale_drop site=$source n=${entries.size} " +
                        "started=$epoch now=${player.queueEpochOrZero}"
                )
                return@withContext 0
            }
            // ⚠️ NEVER APPEND THE TRACK THAT IS PLAYING RIGHT NOW.
            //
            // WHY HERE AND NOT IN THE EXTENSION, which is the obvious instinct since that is where the
            // duplicate comes from: the invariant is a property of THE PLAYER forcing a first track, not
            // of any extension's radio algorithm. DeezerRadioClient does filter its seed out of results
            // — but only for RadioKind.TRACK (see its `if (kind == RadioKind.TRACK)` branch), because
            // TRACK used to be the only kind that ever had a track forced ahead of it. ARTIST and FLOW
            // are unfiltered, and ARTIST could not filter correctly anyway: its Radio carries
            // `id = <artist id>`, so an `it.id != radio.id` test compares track ids against an artist id
            // and never matches. PLAYLIST/ALBUM deliberately re-include their own seeds. Fixing it
            // per-kind in Deezer would need a new seed_id extra on two branches AND would leave every
            // other extension carrying the same bug — the filter belongs on the side that created the
            // situation.
            //
            // WHAT MADE THIS REACHABLE: History taps on a Radio context now force the tapped track as
            // the queue and let the stored station generate behind it (HistoryFragment's seed branch),
            // so a non-TRACK station is generated behind a forced first track for the first time. It
            // also covers the pre-existing case of an album auto-radio returning the album's last track.
            //
            // Applied to EVERY append, not just the first: a station appending the currently playing
            // track is wrong whenever it happens, and topUpQueue reaches this same function.
            //
            // ⚠️ THIS CAN EMPTY THE APPEND. If a page returns only the current track, addMediaItems gets
            // an empty list and the queue does not grow. That is a NO-OP, not a stall: stateFlow was
            // already advanced above, so a continuation leaves Loaded and the next topUpQueue loads the
            // NEXT page, while a null continuation leaves Empty and startRadio/topUpQueue call
            // loadPlaylist() to regenerate. The one behaviour change is that a station whose entire
            // remaining content is the current track now ENDS instead of replaying that track — which is
            // the correct outcome and the point of the filter.
            // ⚠⚠ DEDUP AGAINST THE QUEUE TAIL — THIS REPLACED AN id-ONLY CHECK AGAINST THE CURRENT
            // ITEM ON 2026-09-09, AND THE GAP IT CLOSES WAS IDENTIFIED TWO WEEKS BEFORE IT WAS FELT.
            //
            // THE DEFECT, STATED PLAINLY: THERE WAS NO DEDUP ON NON-TRACK STATION APPENDS, ON EITHER
            // SIDE. DeezerRadioClient dedups its seed for RadioKind.TRACK ONLY (title + artist, with a
            // version-suffix strip); ARTIST, PLAYLIST, ALBUM and FLOW hit its `else tracks` branch and
            // are dedup'd by nothing at all except the app's old id-only test against the SINGLE
            // current item. That is not enough for the data shape Deezer actually serves.
            //
            // MEASURED, BOTH DIRECTIONS, SAME RECORDING, SAME DAY (2026-09-09, "Underwater" by The
            // Frogmen — Deezer returns that one recording under TWO album ids, i.e. two track ids):
            //   RadioKind.TRACK      — the extension's title+artist filter removed BOTH copies, the
            //                          append was empty, and the queue ended in silence.
            //   any other kind       — nothing filtered but `it.id != currentId`, so the copy that was
            //                          not currently playing survived, was appended, became current,
            //                          and next round the OTHER copy survived. AN INDEFINITE
            //                          TWO-TRACK LOOP: same recording, forever, invisible to both the
            //                          buffering watchdog and StuckPlayerDetector because the player is
            //                          READY and genuinely progressing.
            // One data shape, opposite outcomes, decided entirely by which RadioKind was in play.
            //
            // ⚠️ [CORRECTED 2026-09-09] THE DECISION THAT LEFT THIS OPEN. A July pass considered exactly
            // this and declined it: "P3 — no dedup on appends — LEFT AS-IS (deliberate)… radio already
            // has its own dedup." THAT PREMISE IS TRUE FOR RadioKind.TRACK AND FALSE FOR EVERY OTHER
            // KIND. It is also disproved by this project's own later finding, recorded in August at the
            // note below: forcing a first track "created the FIRST CASE WHERE A NON-TRACK STATION IS
            // GENERATED BEHIND A FORCED SEED. Deezer's own seed filter is RadioKind.TRACK-only, and for
            // ARTIST it could not work anyway — it compares it.id != radio.id, where radio.id is the
            // ARTIST id" and so never matches a track id. The gap was identified, written down, and left
            // for two weeks; the loop above is that finding arriving as a symptom. Recorded here rather
            // than deleted because the reasoning is the instructive part: "the extension handles it" was
            // checked against ONE code path and generalised to five.
            //
            // WHY APP-SIDE IS THE RIGHT LAYER — unchanged from the note below, which already said it:
            // the invariant is a property of THE PLAYER forcing a first track, not of any extension's
            // radio algorithm. This is not a competing mechanism; it is the one that should have been
            // here.
            //
            // ⚠️ THE KEY IS ISRC FIRST, NORMALISED TITLE+ARTIST AS FALLBACK. An ISRC identifies a
            // RECORDING, which is exactly the equivalence wanted: the same master on two albums shares
            // one ISRC, while a remaster, a live take and a cover each get their own. Track.isrc exists
            // on the common model and DeezerParser populates it from data.ISRC. Where it is null or
            // blank the fallback mirrors the extension's own rule (strip a trailing parenthesised
            // suffix, compare case-insensitively) — weaker, because it WILL merge a re-recording or a
            // same-artist cover, and will NOT catch " - Live at X" since only "(…)" is stripped. That
            // asymmetry is deliberate: a false merge costs one skipped track, a false split costs an
            // infinite loop. A NULL KEY MEANS "CANNOT COMPARE" AND THE TRACK IS KEPT — never dropped.
            //
            // ⚠️ COMPARED AGAINST A WINDOW OF THE QUEUE, NOT AGAINST THE CURRENT ITEM. A pairwise check
            // against `current` cannot see a THREE-WAY rotation (A,B,C where each append is merely not
            // the one now playing), and this is the same recording served under N album ids — nothing
            // bounds N at two. The queue itself is the record of what was appended, so the window needs
            // no new state, survives process death, and is correct for every caller of play(), which
            // matters because this is a companion function with three call sites and no instance to
            // hold a set on. Bounded rather than whole-queue: a 5,000-item restored queue must not be
            // rescanned per append, and a station legitimately revisiting a recording an hour later is
            // not a loop.
            val existing = HashSet<String>()
            existing.addAll(seedKeys)
            player.currentMediaItem?.track?.let { existing.addAll(it.dedupKeys()) }
            val windowStart = (player.mediaItemCount - DEDUP_WINDOW).coerceAtLeast(0)
            for (i in windowStart until player.mediaItemCount) {
                runCatching { player.getMediaItemAt(i).track }.getOrNull()
                    ?.let { existing.addAll(it.dedupKeys()) }
            }
            val items = entries
                // add() returns false when the key is already present, so this also removes duplicates
                // WITHIN a single batch, not just against what is already queued.
                // ⚠⚠ MATCH ON EITHER FORM, AND ACCUMULATE AS WE WALK. The accumulation was
                // always here - `existing.add` returning false already deduped WITHIN a batch as well as
                // against the queue - so the two copies that both got through were COMPARED and judged
                // different. The gap was never the mechanism, only the key.
                // ⚠️ THE ACCEPTED COST: a station serving a studio take and a "(Live at ...)"
                // take of the same song by the same artist in ONE append will now drop one, because the
                // suffix strip only removes a trailing "(...)". Confined to radio appends - playItem,
                // addToQueue, addToNext and backfillQueue never call this function - and it needs two
                // versions of one song by one artist inside a single station's page. Rare on radio, cost is
                // one track, and the offered/added gap in the log makes it visible if it ever bites.
                .filter { (_, t) ->
                    val keys = t.dedupKeys()
                    if (keys.isEmpty()) true
                    else if (keys.any { it in existing }) false
                    else { existing.addAll(keys); true }
                }
                .map { (clientId, t) ->
                    MediaItemUtils.build(
                        app, downloadFlow.value, MediaState.Unloaded(clientId, t), context
                    )
                }
            player.addMediaItems(items)
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            // ⚠⚠ PROMOTED TO PERMANENT 2026-09-12. THE MOST USEFUL DIAGNOSTIC IN THE APP - IT
            // HAS ANSWERED FOUR QUESTIONS IT WAS NOT BUILT FOR. Built for the three-copies duplicate hunt,
            // it then settled: whether an append was landing against a stale queue (the epoch work), how
            // many times an exhausted station regenerated (eight before generationInFlight, two after), and
            // WHICH caller produced a given append (the `source=` tag). Do not strip it with the temporary
            // lines; it is not one.
            // ⚠️ WHY IT GENERALISES, so the next diagnostic can be built the same way: it prints
            // on EVERY append rather than only on failures, so silence means "no append happened" and
            // nothing else; and it carries the CALLER, which is what let one line answer questions about
            // four different code paths. Those two properties are the whole reason it outlived its purpose.
            // ⚠⚠ OFFERED vs ADDED, TAGGED BY SOURCE - THE LINE THAT MAKES A DUPLICATE HUNT
            // DECIDABLE. On 2026-09-11 three copies of one recording reached the queue and NOTHING in the
            // log could say which append produced them, so two rounds were spent reasoning about which
            // filter "failed" before noticing that two append sites did not call this function at all.
            // offered > added means this filter removed something; offered == added means it removed
            // nothing and the duplicates were already distinct by dedupKey.
            Log.d("GladixQueue", "append site=$source offered=${entries.size} added=${items.size}")
            items.size
        }
        suspend fun play(
            player: Player,
            downloadFlow: StateFlow<List<Downloader.Info>>,
            app: App,
            stateFlow: MutableStateFlow<PlayerState.Radio>,
            loaded: PlayerState.Radio.Loaded,
            // The extension that owns the PLAYING item, for the endless-queue fallback's catalogue search.
            // Nullable with a default so a call site that cannot cheaply resolve it still compiles and
            // simply does not get the fallback — rather than forcing a resolution that could be wrong.
            extension: Extension<*>? = null,
            // ⚠⚠ THE CALLER'S NAME, NOT THIS FUNCTION'S. Tagged "station_page" by function until
            // 2026-09-11, which was a level too coarse to be useful: play() is called by loadPlaylist,
            // topUpQueue, startRadio, PlayerCallback.radio AND PlayerCallback.trackRadio, so the append
            // line was identical whichever generated the station - and telling those apart was exactly the
            // question two device captures could not answer. A log that cannot distinguish its own callers
            // is not instrumentation, it is decoration.
            source: String = "station_page",
        ): PlayResult {
            // Captured BEFORE the page fetch: this is the queue this call is for.
            val epoch = player.queueEpochOrZero
            stateFlow.value = PlayerState.Radio.Loading
            // ⚠⚠ THE finally COVERS THE ONE EXIT THE ELVIS BELOW CANNOT: CANCELLATION. A failed page
            // load returns null and IS restored explicitly; a CANCELLED one unwinds straight past that
            // line and would leave stateFlow pinned at Loading forever - which per the note at
            // currentRadio makes topUpQueue AND startRadio no-op for the rest of the context.
            var settled = false
            try {
                val tracks = loaded.tracks(loaded.cont) ?: run {
                    // Page load failed: extension.get caught the throwable and getOrThrow already reported it to
                    // throwFlow, returning null. Restore the prior Loaded state instead of leaving stateFlow pinned
                    // at Loading — a stuck Loading makes topUpQueue() and startRadio() no-op for the rest of the
                    // context, stranding the WHOLE radio subsystem (not just this fetch). Restoring Loaded lets the
                    // next track transition retry (correct for a transient failure); a genuinely exhausted radio
                    // takes the continuation==null path below and becomes Empty instead. Generic by construction:
                    // null is the universal failure signal here, so this covers any extension whose loadPage throws.
                    stateFlow.value = loaded.forQueue(player)
                    return PlayResult(0, exhausted = false, failed = true)
                }

                // ⚠⚠ PARKED, WITH EVIDENCE: THE EXHAUSTED-STATION MEMO. Empty here means
                // "no station", so it is indistinguishable from "never had one" - and the next qualifying
                // transition therefore takes topUpQueue's `Empty -> loadPlaylist()` branch and REBUILDS this
                // station from scratch. For a deterministic station id that is start() plus a page-1 fetch,
                // two network round-trips, to re-derive a page we already proved deduped to nothing.
                // ⚠️ MEASURED 2026-09-12 (Frogmen artist radio, build 1099): EIGHT regenerations
                // of radio=273559 in one context, every one `site=loadPlaylist offered=2 added=0`, every one
                // followed by a LASTFM reason=seed-not-loaded and a RESEED reason=not_seed_determined. The
                // site tag is the proof it is this line: a station still Loaded would have logged
                // site=topUpQueue, so Empty was written here every time.
                // THE FIX WOULD BE a memo of (queue epoch, station id) proven continuation-less and
                // zero-yield, consulted before regenerating - the same key shape claimFallback already uses,
                // for the same reason (per-queue, so a new queue retries). It collapses the eight to one and
                // takes the seed-not-loaded refusals with them as a side effect.
                // ⚠⚠ [CLOSED 2026-09-12 - NOT WORTH BUILDING. MEASURED, NOT ARGUED.]
                // The blocker recorded here was that the memo needs a LIFETIME: "exhausted" is not
                // permanent, so a memo keyed on (epoch, id) would suppress a legitimate later retry.
                // THAT PROBLEM NEVER HAS TO BE SOLVED, because generationInFlight removed almost all of the
                // waste the memo was aimed at:
                //     before generationInFlight   EIGHT site=loadPlaylist regenerations in one context
                //     after                       TWO, spread 34 seconds apart
                // Two spread over 34s is ordinary top-up cadence, not a loop. And the suppression line
                // (LOADPLAYLIST reason=generation_in_flight) fires exactly once, right where the 19ms burst
                // used to be.
                // ⚠️ SO THE DIAGNOSIS RECORDED ABOVE WAS RIGHT ABOUT THE COUNT AND WRONG ABOUT
                // THE CAUSE, WHICH IS THE PART WORTH KEEPING. The eight regenerations were read as
                // REPETITION - one per qualifying transition, inherent to Empty erasing the station - and
                // the memo was designed against that. They were almost entirely CONCURRENCY: several
                // coroutines entering loadPlaylist from one callback. Fixing the concurrency left a residual
                // that needs no fix, and the expensive change was never required.
                // Two counts, one mechanism each, and only measurement separated them. Do not rebuild the
                // memo without first showing that the regeneration count is high again.
                stateFlow.value = if (tracks.continuation == null) PlayerState.Radio.Empty
                // Stamped here and not carried through copy(): play() receives an UNSTAMPED Loaded straight
                // from start() on the radio()/trackRadio paths, so relying on copy() alone would publish -1L
                // and make every explicitly-started station read as stale.
                else loaded.copy(cont = tracks.continuation).forQueue(player)
                settled = true

                val appended = appendDeduped(
                    player, downloadFlow, app,
                    tracks.data.map { loaded.clientId to it }, loaded.context, epoch,
                    source = source, seedKeys = seedKeysFor(loaded.context.id)
                )
                // Post-fallback total, which is what loadPlaylist gates the multi-seed escalation on. The
                // `appended` val above stays the PRE-fallback page count the Last.fm gate below asks about,
                // so a successful bridge raises the total and suppresses the fan-out without changing whether
                // the bridge itself fires.
                var totalAppended = appended
                run {
                    // ── ENDLESS-QUEUE FALLBACK ── see RadioFallback for the whole rationale.
                    //
                    // TRIGGER: EMPTY OR THIN, MEASURED AFTER FILTERING. Both are "the extension has nothing
                    // useful left" and both were observed on the same track on the same day:
                    //   empty  — every candidate was a duplicate of what is already queued (the silence case)
                    //   thin   — one track and NO CONTINUATION, i.e. a station that cannot sustain a queue
                    // Deliberately NOT "fewer than RADIO_PREFETCH_THRESHOLD": a healthy station legitimately
                    // appends small pages WITH a continuation, and treating that as failure would fire the
                    // fallback constantly on working stations.
                    // Measured post-dedup on purpose — the raw page in the loop case had two entries and looked
                    // perfectly healthy; it was only after filtering that it was revealed as nothing.
                    // ⚠️ [MEASURED 2026-09-10] `thin` FIRES FROM FILTERING, NOT FROM A STINGY API -
                    // AND THAT CLOSES A PARKED PROPOSAL. It had been suggested that api.mix might return too few
                    // tracks for some seeds, which would have argued for a multi-seed RadioKind.TRACK inside
                    // DeezerRadioClient. Hand-counted on device: Link Wray "Fire and Brimstone" and The Brooklyn
                    // Bridge "Worst That Could Happen" BOTH returned ~100 tracks, with no LASTFM line logged.
                    // THERE IS NO THIN CASE AT THE API. That proposal is dropped, not parked.
                    // What remains true is that the seed strip can empty a HEALTHY page - see DeezerRadioClient's
                    // TRACK branch on the two-album-id case - which is why this is measured post-dedup.
                    // Pre-fallback counts on purpose: this asks whether the STATION is spent, which is what
                    // the Last.fm bridge exists to answer. The value play() RETURNS is post-fallback, so a
                    // successful bridge stops the multi-seed escalation from also firing - see loadPlaylist.
                    val thin = isThin(PlayResult(appended, tracks.continuation == null))
                    val radioId = loaded.context.id
                    // ⚠⚠ THE CAS COMES BEFORE claimFallback, AND THAT ORDER IS A FIX, NOT A STYLE
                    // CHOICE. claimFallback SPENDS the one attempt for this (station, seed) permanently. If the
                    // rescue marker were claimed second, a concurrent rescue would fail the CAS AFTER the seed
                    // had already been claimed - burning that seed's only attempt on a lookup that never ran,
                    // and locking it out for good. That is precisely the defect fixed on 2026-09-10 when the
                    // latch was moved below the seed read; claiming in the wrong order here would reintroduce
                    // it through a different door. Cheap, reversible check first; permanent claim second.
                    if (thin && extension != null && rescueInFlight.compareAndSet(false, true)) try {
                        // ⚠⚠ `null` CANDIDATE - THIS PATH HAS NO SECOND COPY, SO THE
                        // CONTENT-FIRST RULE CANNOT RESCUE IT THE WAY IT RESCUES throwBridge. Read
                        // resolveSeed's note first; this is the half that is NOT fixed.
                        // throwBridge receives trackRadio's own object as a candidate and can therefore
                        // prefer whichever copy has an artist. Here there is only player.currentMediaItem,
                        // so once resolution has OVERWRITTEN a good artist with a placeholder the good one
                        // is gone - `state` is replaced wholesale at MediaItemUtils.toMetaData, no merge.
                        // ⚠️ CONCRETELY: A YTM ARTIST STATION GOING THIN STILL PRODUCES
                        // q="Unknown - <title>" AND STILL FAILS. Do not read the content-first change as
                        // having fixed YTM generally - it fixed long-press -> Radio, which is throwBridge.
                        // ⚠️ WHAT IT DOES FIX HERE, WHICH IS NARROWER THAN IT LOOKS AND WAS
                        // FOUND WHILE BUILDING RATHER THAN PREDICTED: when the current item has NOT
                        // resolved yet, `current.track` is still the QUEUED copy, and this used to refuse on
                        // loaded-ness alone without ever looking at it. The Frogmen capture shows that copy
                        // was perfectly usable - the dedupKeys note records the pre-resolution seed as
                        // `ta:underwater\0the frogmen`, artist present - so those EIGHT seed-not-loaded
                        // refusals should now succeed on the first attempt instead of the third.
                        // ⚠⚠ [CONFIRMED 2026-09-12 - PREDICTION MET, ZERO OBSERVED.] The same
                        // Frogmen artist station that produced the eight refusals now reaches the bridge on
                        // its FIRST attempt: `LASTFM q="The Frogmen - Underwater" artist="The Frogmen"
                        // reason=ok similar=25 searched=10 matched=6`, and NO seed-not-loaded line anywhere
                        // in the capture. So the queued copy WAS usable all along, exactly as the dedupKeys
                        // note recorded, and loaded-ness was the wrong gate. The content-first rule in
                        // resolveSeed is what changed it. The prediction below stands as written and is kept
                        // because the reasoning is what makes the result checkable, not the outcome.
                        // ⚠⚠ THE PREDICTION IS ZERO, NOT "FEWER", AND ZERO IS WHAT MAKES IT A
                        // TEST. On this path candidate is null, so `ordered` holds exactly one entry - the
                        // queued copy - and seed-not-loaded can now fire ONLY if that copy has a blank
                        // artist. Every track in a Deezer station queue comes from a radio page, a
                        // catalogue search or the tapped seed, and all three carry artists. ANY residual
                        // falsifies "the queued copy is always usable" and is independently worth chasing:
                        // an artist-less queued track also makes recordingKey() `<title>\0`, degrading the
                        // ta: namespace for that track exactly as described at recordingKey.
                        //
                        // ⚠⚠ AND THIS COUNT CANNOT BE READ ALONE, BECAUSE generationInFlight
                        // SHIPPED IN THE SAME BUILD AND MOVES THE DENOMINATOR. Fewer regenerations means
                        // fewer thin appends means fewer bridge attempts, so a DROP in the seed-not-loaded
                        // total is ambiguous between the two fixes. The discriminator is to stop counting
                        // and read ONE event:
                        //   THE FIRST thin `site=loadPlaylist` APPEND IN THE CAPTURE decides this fix, and
                        //   only this fix. Its LASTFM line reads `q="The Frogmen - Underwater"` if
                        //   content-first works, or `reason=seed-not-loaded` if it does not.
                        // generationInFlight cannot touch that line: it only suppresses LATER overlapping
                        // entries, never the first. Conversely `LOADPLAYLIST reason=generation_in_flight` is
                        // emitted by the marker and by nothing else, so the two fixes are read from two
                        // disjoint log lines and neither can borrow the other's evidence.
                        val check = resolveSeed(player, null)
                        // ⚠⚠ PROMOTED TO PERMANENT 2026-09-12 - reason= AND artist= BOTH.
                        // `reason=` separates SIX outcomes that would otherwise be one silence
                        // (seed-not-loaded / no-seed / no-seed-fields / already_claimed / ok / no-similar),
                        // and every one of those has been read off a real capture at least once.
                        // `artist=` earned its place independently: it is what exposed the metadata-timing
                        // bug - q="Unknown - Kokomo" on YTM, q="The Frogmen - Underwater" after the
                        // content-first fix - and confirmed the zero-refusal prediction. The query being
                        // readable at a glance IS the fix for "Unknown", not a step toward one.
                        // ⚠️ A SKIP HERE IS A SILENT NO-RESCUE - THE QUEUE ENDS. That is correct
                        // (a lookup on a blank or absent artist cannot match anything), but it means this log
                        // line is LOAD-BEARING rather than decorative: without it, an unresolved seed is
                        // indistinguishable from Last.fm having no data.
                        if (check.reason != null) Log.d(
                            "GladixRadio", "LASTFM reason=${check.reason} epoch=$epoch radio=$radioId"
                        )
                        val seed = check.seed
                        // ONE claim call, captured - calling claimFallback twice would claim on the first and
                        // refuse on the second, which is the shape of bug this whole latch keeps producing.
                        val claimed = seed != null && claimFallback(epoch, radioId, seed.recordingKey())
                        // ⚠️ THE REFUSAL LOGS NOW. It used to fall through in silence, which is how
                        // the per-process dead end above stayed invisible: a station that simply stopped, with
                        // an empty capture. Every other drop in this file logs; this was the exception.
                        if (seed != null && !claimed) Log.d(
                            "GladixRadio",
                            "LASTFM reason=already_claimed epoch=$epoch radio=$radioId " +
                                "seed=${seed.artists.firstOrNull()?.name} - ${seed.title}"
                        )
                        if (seed != null && claimed) {
                            // ⚠⚠ THE LATCH BURNS HERE - AFTER THE SEED READ, BEFORE THE LOOKUP. BOTH HALVES
                            // OF THAT POSITION ARE A FIX FOR A DIFFERENT FAILURE; DO NOT MOVE IT EITHER WAY.
                            //
                            // IT USED TO BE SET ABOVE, BEFORE the seed read, AND THAT WAS A REAL BUG (found
                            // 2026-09-10). When PlayerCallback.radio served a track station it cleared the
                            // queue without queueing a seed, so currentMediaItem was null, `seed != null`
                            // failed - and the latch had ALREADY been set for a lookup that never happened.
                            // Every later topUpQueue on that station was then locked out. The station was
                            // silent AND permanently un-retryable, and because the skip has no branch and no
                            // log, nothing recorded it. (The null-seed cause is fixed at
                            // PlayerViewModel.radio, but the latch was independently wrong and stays fixed
                            // here: any future path that reaches this with no current item must not burn it.)
                            //
                            // AND NOT AFTER similarTracks EITHER. The point of the claim is ONE ATTEMPT PER
                            // (STATION, SEED) - counting attempts, not successes. Claiming on the far side
                            // would let a lookup that throws, times out, or returns empty re-fire on every
                            // single topUpQueue for the rest of the context: an unbounded network retry loop
                            // driven by track transitions. Claimed before the call, so a thrown lookup is spent.
                            // (claimFallback is now the test AND the set, atomically - see its note.)
                            val extra = RadioFallback.similarTracks(extension, seed)
                            // ⚠️ THE LONGEST BOUNDED WINDOW ON THIS PATH (~12s: OUTER_TIMEOUT_MS for
                            // the Last.fm fetch plus SEARCH_BUDGET_MS for the catalogue searches) and the one
                            // actually observed appending into a replaced queue. Same epoch as the page append
                            // above: both belong to the queue play() started against.
                            // ⚠⚠ ROUTED THROUGH appendDeduped 2026-09-11. IT USED TO CALL
                            // player.addMediaItems DIRECTLY, SO THERE WAS NO DEDUP AT ALL ON THIS PATH - no
                            // seedKeys, no currentMediaItem check, no DEDUP_WINDOW scan. RadioFallback does
                            // not exclude the seed from its own results either, and Last.fm's neighbours for
                            // a track can include that same artist, so the catalogue search could return the
                            // SEED RECORDING ITSELF and it would land unfiltered.
                            // ⚠️ THE SCOPING DECISION THAT CAUSED IT WAS EXPLICIT, WHICH IS WHY THIS IS
                            // RECORDED RATHER THAN QUIETLY FIXED. When the queue epoch was added, rerouting
                            // this append through appendDeduped was considered and declined with "Keep scope
                            // tight: don't reroute the fallback." That reasoning was ordinary and will look
                            // equally sensible next time. The lesson is not "be braver" - it is that an
                            // append site outside the shared filter IS a filter gap, and the cost of one
                            // surfaces as an undecidable bug report weeks later, not as a visible omission.
                            // It also inherits the epoch check, replacing a hand-rolled copy of it.
                            // ⚠️ STAMP FROM THE SEARCH RESULT'S EXTENSION, NEVER THE STATION'S. This is the
                            // FOURTH extension_id failure of this family in one week — after the radio
                            // non-fatal (fixed by ResumptionUtils.restamped), loadTrack's missing stamp
                            // masked by the cache fallback, and the four UnifiedExtension tracker
                            // callbacks. See also Track.toSlim's extras stripping, which is the mechanism
                            // that keeps producing them.
                            // It matters MORE here than it looks: under Unified the station belongs to one
                            // sub-extension while the search fans out across all of them, so the matched
                            // track can legitimately come from a DIFFERENT sub-extension. Inheriting the
                            // station's id would then fail at loadStreamableMedia — one layer below and
                            // several seconds after the mistake, with no obvious link back to here.
                            // MediaState.Unloaded carries the clientId that resolution will use, so
                            // extension.id (the searched extension) is the correct value.
                            totalAppended += appendDeduped(
                                player, downloadFlow, app,
                                extra.map { extension.id to it }, loaded.context, epoch,
                                source = "lastfm:$source", seedKeys = seedKeysFor(loaded.context.id)
                            )
                        }
                    } finally {
                        rescueInFlight.set(false)
                    }
                }
                return PlayResult(totalAppended, tracks.continuation == null)
            } finally {
                // Restoring `loaded` rather than a captured prior: play() is only ever entered WITH a
                // station, so the correct undo is that station, and the explicit restore below writes the
                // same value - the double write on that path is idempotent.
                if (!settled) stateFlow.value = loaded.forQueue(player)
            }
        }
    }

    private var radioQueueActive = false

    // TV drives radio continuation/start from the explicit hooks below (tvDriveRadio) instead of
    // startRadio()/topUpQueue(), which don't reliably fire on TV. Phone is untouched: isTv is false there,
    // so onTimelineChanged / onMediaItemTransition fall through to the exact same startRadio()/topUpQueue()
    // calls and onPlaybackStateChanged is a no-op. Detection matches the rest of the app (UiModeManager
    // UI_MODE_TYPE_TELEVISION || FEATURE_LEANBACK); the mode is fixed at runtime, so lazy eval is safe.
    private val isTv by lazy { app.context.isTv() }

    // Reset-safe idempotency guard for the TV driver — deliberately NOT the radioFlow==Loading state (which
    // can strand). compareAndSet is checked BEFORE the try, so every exit inside the try (early returns,
    // exceptions, coroutine cancellation) unwinds through finally and always releases it.
    private val tvInFlight = AtomicBoolean(false)

    // ⚠⚠ THE PHONE ANALOGUE OF tvInFlight ABOVE, AND ITS ABSENCE WAS A REAL RACE, MEASURED.
    // tvDriveRadio has serialised overlapping transition callbacks since it was written - "tvInFlight (not
    // the radioFlow state) serializes overlapping transition / STATE_ENDED calls so a boundary never
    // double-appends". Phone had NO equivalent, and phone reaches loadPlaylist from TWO coroutines launched
    // by ONE callback: onMediaItemTransition does `scope.launch { startRadio() }` and
    // `scope.launch { topUpQueue() }` back to back, and on the last track of a batch BOTH proceed -
    // hasNextMediaItem() is false so startRadio does not return, remaining == 0 so topUpQueue does not
    // either - and both land on their `Empty -> loadPlaylist()` branch.
    //
    // ⚠⚠ MEASURED 2026-09-12, The Frogmen artist radio, build 1099. This is the line that makes
    // it a RACE rather than a cadence, and it is why the marker exists:
    //     08:05:43.307  append site=loadPlaylist offered=2 added=0
    //     08:05:43.316  append site=loadPlaylist offered=2 added=0   <- +9ms
    //     08:05:43.326  append site=loadPlaylist offered=2 added=0   <- +10ms
    // THREE FULL STATION REGENERATIONS IN 19 MILLISECONDS - each one a start() plus a page-1 fetch, i.e.
    // six network round-trips, all for the same station against the same queue. The other five
    // regenerations in that capture were spread across genuine transitions (08:04:50.686, .937, 51.313,
    // then a 33s gap, then 08:05:24.238, 25.809, 27.521), which is the designed per-transition cadence and
    // is NOT what this closes. Only the burst is.
    //
    // ⚠⚠ AN AtomicBoolean AND NOT "MOVE THE Loading WRITE UP", WHICH WAS THE OTHER CANDIDATE
    // AND IS THE ONE THIS FILE HAS ALREADY GOT WRONG ONCE. Both entry points test
    // `stateFlow.value is Loading` before calling in, so moving loadPlaylist's Loading write above the
    // currentMediaItem read would narrow the window - and leave it open, because a check-then-act on a
    // plain field is non-atomic wherever the write lands. That is the correction recorded at
    // reseedFanOutLocked verbatim: "stateFlow is not a mutex, and using it as one discards the transitions
    // it blocks instead of deferring them." A CAS is atomic; a state read is not. It would also push
    // Loading above an early `?: return` that no finally covers yet, reintroducing the pinning shape that
    // the same correction removed.
    //
    // ⚠️ WHERE IT SITS: CLAIMED AFTER THE trackRadioGenerating GATE, RELEASED IN A finally
    // AROUND EVERYTHING ELSE. Following reseedFanOut's ordering exactly - "claimed AFTER the
    // seed-determined gate above, which is pure logic plus a log and costs nothing, so that gate still
    // reports on every attempt. Only the expensive half is serialised." Everything past the claim (the
    // Main-thread hop, start(), play(), reseedFanOut) is the work that must not run twice; the gate above
    // is a field read and a log, and keeping it outside preserves it as an honest per-attempt counter.
    //
    // ⚠️ THE LOSER IS DROPPED, NOT DEFERRED, AND THAT IS ACCEPTABLE HERE FOR A REASON THAT DOES
    // NOT GENERALISE. The lost-wakeup objection to Loading-as-mutex applies to any marker, so it has to be
    // answered rather than assumed: what gets dropped here is a DUPLICATE of work already running against
    // the same queue and the same station, not a distinct piece of work. Transitions keep arriving and
    // `remaining` is still under the threshold, so a genuinely-needed generation is retried at the next
    // one. ⚠️ The one bounded exposure: if the queue is REPLACED mid-flight, the winner's append
    // is discarded by appendDeduped's epoch check and the loser was already dropped - but the replacement
    // itself fires onTimelineChanged -> startRadio, which runs after the marker clears. Bounded by one
    // transition, not stranded.
    //
    // No interaction with the other markers: play() and reseedFanOut both claim rescueInFlight, a
    // different flag, and nothing nests this one inside itself. On TV this always succeeds, because
    // tvInFlight has already serialised tvDriveRadio before it calls in - so TV behaviour is unchanged.
    private val generationInFlight = AtomicBoolean(false)

    private suspend fun loadPlaylist() {
        // ⚠⚠ CHECKED HERE AND NOT IN startRadio, BECAUSE topUpQueue IS A REAL THIRD CONTENDER
        // AND THAT IS NOT VISIBLE FROM READING EITHER FUNCTION ALONE. topUpQueue returns early unless
        // radioQueueActive - which is set true HERE and reset ONLY when mediaItemCount hits 0. So a fresh
        // trackRadio queue INHERITS true from whatever station played before it, `remaining` is 0 on a
        // one-item queue, stateFlow is Empty, and topUpQueue calls this function too. A guard in startRadio
        // would have left that path still duplicating. Both incidental entry points funnel here; one check
        // covers both.
        //
        // ⚠️ [CORRECTED] CONTENDER COUNT: FOUR DOWN TO ONE FOR THIS PATH, NOT "roughly two".
        // An earlier estimate of mine said four to two; it was made before I had established that
        // topUpQueue also routes through here. For one single-track play the contenders were trackRadio
        // itself, onTimelineChanged -> startRadio, onMediaItemTransition -> startRadio, and
        // onMediaItemTransition -> topUpQueue. Three of the four are incidental and all three stop here,
        // so the race on this path is ELIMINATED rather than reduced. Elsewhere - album end, artist
        // station - contention is unchanged, and the shared rescueInFlight plus the epoch-keyed latch
        // still guarantee one lookup with losers logged.
        //
        // AA IS UNAFFECTED: it never sends trackRadioCommand (the only senders are FeedClickListener and
        // PlayerViewModel.radio's Track branch, both phone UI). AA plays through onSetMediaItems ->
        // startRadio -> here, i.e. ONE generation, so it has no duplicate to remove and never claims the
        // marker. ⚠️ PARKED, RECORDED NOT FIXED: an AA single-track queue with autoStartRadio OFF
        // therefore gets no radio at all, because AA has no trackRadio equivalent to generate one.
        // ⚠⚠ BOTH LOADPLAYLIST reason= LINES ARE PERMANENT AS OF 2026-09-12, AND THE PAIRING IS
        // WHY. Each fires only on a refusal, which alone would be ambiguous - silence could mean "no
        // refusal" or "loadPlaylist never ran". The always-printing half is the adjacent
        // `append site=loadPlaylist` line: refusal line present = suppressed, append present = proceeded,
        // neither = never entered. DO NOT STRIP ONE WITHOUT THE OTHER; they are one instrument.
        // generation_in_flight is what measured the concurrency fix (one line exactly where the 19ms burst
        // used to be), and track_radio_generating is what proves the duplicate suppression end to end.
        if (trackRadioGenerating.get()) {
            Log.d("GladixRadio", "LOADPLAYLIST reason=track_radio_generating")
            return
        }
        // ⚠️ THE REFUSAL LOGS, matching every other drop in this file - the silent fall-through
        // is what kept the Last.fm latch's dead end invisible for a week. It is also the only way to tell a
        // closed race from a race that never fired: this line appearing twice in 19ms is the burst above,
        // now suppressed rather than served.
        if (!generationInFlight.compareAndSet(false, true)) {
            Log.d("GladixRadio", "LOADPLAYLIST reason=generation_in_flight")
            return
        }
        try {
            loadPlaylistLocked()
        } finally {
            generationInFlight.set(false)
        }
    }

    // Split out purely so the claim above can wrap the whole body in try/finally without reindenting it -
    // the same shape, and for the same reason, as reseedFanOut / reseedFanOutLocked below.
    // Everything here runs under generationInFlight.
    private suspend fun loadPlaylistLocked() {
        val mediaItem = withContext(Dispatchers.Main) { player.currentMediaItem } ?: return
        val extensionId = mediaItem.extensionId
        val item = mediaItem.track
        // A LABEL_ONLY_RADIO context is a display-only header stamp (bare-track / Radio-History seed), not
        // a real radio to generate — strip it so radio() receives null exactly as before, keeping the real
        // auto-radio identical and extension-agnostic. The MediaItem's context is untouched, so the header
        // still reads "Playing from <track> Radio".
        val itemContext = mediaItem.context?.takeUnless {
            it is Radio && it.extras[MediaItemUtils.LABEL_ONLY_RADIO] == "true"
        }
        val prior = stateFlow.value
        stateFlow.value = PlayerState.Radio.Loading
        // ⚠⚠ settled FLIPS THE INSTANT stateFlow STOPS HOLDING Loading, which is the only moment
        // that matters. Set it earlier and a cancellation in between restores OVER a correct value;
        // set it later and the same cancellation leaves Loading pinned. Seventh time this week that
        // where a flag sits relative to its work decided the design.
        var settled = false
        try {
            val extension = extensionList.getExtension(extensionId) ?: run {
                // Extension gone after Loading was set — restore the prior state rather than strand at Loading.
                // Same principle as play() restoring its `loaded`: reset to whatever we were before Loading. Here
                // prior is always Empty (loadPlaylist is only reached from the Empty branches of topUpQueue/
                // startRadio), so this is Empty today, but capturing it keeps the intent explicit and robust.
                stateFlow.value = prior
                return
            }
            // startFailure is a LOCAL, not shared state: see the onFailure note at start().
            var startFailure: Throwable? = null
            val loaded = start(
                throwFlow, extension, item, itemContext,
                // The auto-radio APPENDS, so the playing track stays in the queue and can come back
                // after it advances - see the playingSeed note at start(). Read on Main per this
                // file's currentMediaItem rule.
                playingSeed = withContext(Dispatchers.Main) { player.currentMediaItem?.track },
                onFailure = { startFailure = it }
            )
            stateFlow.value = loaded?.forQueue(player) ?: PlayerState.Radio.Empty
            settled = true
            if (loaded == null) {
                // Attached AFTER getOrThrow has already emitted, so the extension's own report is never
                // suppressed and no second one is added - one report before this change, one after.
                startFailure?.let {
                    throwBridge(player, downloadFlow, app, extension, item, it, itemContext)
                }
            }
            if (loaded != null) {
                radioQueueActive = true
                // ⚠⚠ ESCALATION GATE - ONE SEED FIRST, THEN THE OTHER THREE ONLY IF THAT FAILED.
                // The common case is unchanged and costs nothing extra: one seed, one station, no fan-out.
                // A re-seed that comes back thin is the ONLY thing that triggers the remaining seeds, which is
                // why this needs no mode flag and no new state - the condition is measured, not remembered.
                // It also catches a case neither "always multi-seed" nor "only after a Last.fm bridge" would:
                // an ORDINARY station whose re-seed happens to land on a dead track.
                // ⚠️ A KNOWN ORDERING, NOT A DECISION - recorded because it was DISCOVERED while
                // building this, not chosen. The Last.fm bridge
                // lives INSIDE play(), so it runs BEFORE this gate is even evaluated, and its appends count
                // toward the returned total - so a successful bridge suppresses the fan-out. That ordering is a
                // consequence of where the two rescues sit, not a judgement that Last.fm should go first;
                // multi-seed is the cheaper and more native of the two (3 radio calls vs up to 15 catalogue
                // searches) and would ideally be tried first. Reversing them means lifting the bridge out of
                // play(), which is a larger change than this one and was deliberately not taken.
                val result = play(
                    player, downloadFlow, app, stateFlow, loaded, extension, source = "loadPlaylist"
                )
                if (isThin(result)) reseedFanOut(itemContext)
            }
        } finally {
            // ⚠⚠ COVERS EVERY EXIT INCLUDING CANCELLATION - the exposure the explicit restore above
            // never did. start() only propagates CancellationException (getOrThrow converts everything
            // else into a reported null), and extensionList.getExtension suspends on a Flow, so either
            // in-window call can unwind without ever reaching the settle line.
            if (!settled) stateFlow.value = prior
        }
    }

    // Seeds for the fan-out: walk BACKWARDS from the playhead taking one track per distinct artist.
    // ⚠⚠ ARTIST-DISTINCTNESS IS THE WHOLE POINT, NOT A TIDINESS RULE. Four tracks by the same
    // artist are four seeds that return the same neighbourhood, which collapses N back to 1 and buys
    // nothing for 3x the requests. What makes a seed set worth fanning out over is INDEPENDENCE.
    // That is also why this reads the queue TAIL rather than the station: after a Last.fm bridge the tail
    // holds six tracks by six different artists that Last.fm proposed and the catalogue confirmed it
    // carries - independently sourced, not drawn from the station that just died.
    // An empty artist name means "cannot compare" and the track is KEPT, matching dedupKey's rule: a false
    // merge silently costs a seed, a false split costs nothing.
    private suspend fun collectSeeds(max: Int): List<Pair<String, Track>> =
        withContext(Dispatchers.Main) {
            val out = ArrayList<Pair<String, Track>>()
            val artists = HashSet<String>()
            val from = player.currentMediaItemIndex
            val floor = (from - SEED_SCAN + 1).coerceAtLeast(0)
            var i = from
            while (i >= floor && out.size < max) {
                val mediaItem = runCatching { player.getMediaItemAt(i) }.getOrNull()
                i--
                val track = mediaItem?.track ?: continue
                val artist = track.artists.firstOrNull()?.name?.trim()?.lowercase().orEmpty()
                if (artist.isNotEmpty() && !artists.add(artist)) continue
                out.add(mediaItem.extensionId to track)
            }
            out
        }

    // ⚠⚠ FAN OUT ONCE, MERGE, RETAIN ONE STATION. Reached only from loadPlaylist's escalation
    // gate, i.e. only after a single-seed re-seed already came back thin.
    //
    // ⚠️ WHAT Radio.Loaded MEANS AFTERWARDS IS A DELIBERATE SIMPLIFICATION - READ THIS BEFORE
    // TRUSTING IT. The user is hearing tracks merged from up to four stations, and Loaded names exactly
    // ONE of them: the highest-yielding. The other stations' continuations are DISCARDED after their first
    // page. So the NEXT re-seed is single-seed roulette again - which is fine, because the escalation gate
    // catches it again if it comes back thin. The alternative (Loaded carrying a list, topUpQueue
    // round-robining continuations) changes PlayerState's shape and every consumer of it, for a benefit
    // that only shows up on a station that was already rescued once.
    // The merged tracks are stamped with the RETAINED station's context, so the "Playing from ..." header
    // follows the station that will actually continue. It will change once, at the fan-out.
    // ⚠⚠ THE FAN-OUT ONLY RUNS FOR SEED-DETERMINED STATIONS. THE GATE IS NOT A PREFERENCE,
    // IT IS A CORRECTNESS CONDITION - fanning out over a context-determined station is definitionally a
    // no-op that costs three extra round trips.
    //     gate:  itemContext == null || itemContext.extras["radio"] == "track"
    //
    // THE STRUCTURAL REASON, AND IT IS DESIGN RATHER THAN ACCIDENT. In DeezerRadioClient.radio's Track
    // branch the non-TRACK kinds IGNORE THE SEED TRACK ENTIRELY:
    //     RadioKind.ARTIST   -> Radio(id = context.id, ...)
    //     RadioKind.PLAYLIST -> context
    //     RadioKind.ALBUM    -> context
    // Four different seeds under an artist context therefore resolve to FOUR COPIES OF ONE STATION.
    // ⚠️ AND THE FORWARDING IS DELIBERATE, NOT AN OVERSIGHT TO BE FIXED: PLAYLIST and ALBUM were
    // CHANGED to forward the existing Radio object directly, specifically to preserve source_id,
    // include_seeds and the rest of the seed state across the whole radio session. So the collapse is that
    // branch working AS DESIGNED. Anyone tempted to "fix" the collapse by making those kinds seed-sensitive
    // would be undoing that, and would break seed-state continuity to gain a fan-out nobody asked for.
    //
    // THIRD INSTANCE OF TRACK BEING THE ODD KIND OUT, which is what makes this gate consistent with the
    // codebase rather than a new distinction invented here:
    //   1. The seed filter in loadTracks is RadioKind.TRACK-ONLY - and for ARTIST it could not work anyway,
    //      because it compares `it.id != radio.id` where radio.id is the ARTIST id, so it never matches a
    //      track id. (See the dedup note in play(), which was written when that gap surfaced as a loop.)
    //   2. Generation itself: TRACK and a null context consult the seed via asTrackRadio; every other kind
    //      forwards or rebuilds from the context.
    //   3. This gate.
    //
    // ⚠️ A REAL NARROWING, BY OMISSION NOT BY DECISION - RECORDED SO IT IS VISIBLE RATHER THAN
    // DISCOVERED. No extension other than Deezer writes extras["radio"], so a NON-DEEZER EXPLICIT TRACK
    // STATION NEVER FANS OUT. Those extensions get the fan-out only through the null-context clause, which
    // covers the bare-track and History seeds - the common path - but not a station started deliberately
    // from a track. Accepted knowingly: Deezer is the primary extension, the null clause covers the common
    // path for everyone, and the portable alternative (fan out, then discard stations whose Radio.id
    // matches each other's) pays three requests to learn what this test knows for free.
    //
    // ⚠⚠ THE LOG LINE BELOW IS MANDATORY, NOT DIAGNOSTIC GARNISH. extras["radio"] ALREADY HAS
    // ONE SILENT FAILURE ON RECORD: Radio.kind() defaults to FLOW when the key is missing or altered - it
    // does not error, it falls through to the user's personal Flow (getUserRadio), returning a different,
    // wrong-seeded, often tiny set. This gate makes a missing key ALSO disable the fan-out, i.e. a second
    // silent consequence for one missing key, in a second subsystem.
    // The POLARITY is opposite and that is the whole safety argument: kind()'s failure ACTS WRONGLY
    // (commission), this one DOES NOTHING EXTRA (omission - you simply get the single-seed behaviour that
    // predates the fan-out). But severity was never what made the first bug expensive - DETECTABILITY was,
    // and that argument does not transfer for free. The log line is what buys it: a missing key becomes
    // OBSERVABLE at the moment it changes behaviour here, even though it stays silent at kind().
    // DO NOT REMOVE IT AS NOISE. It is the only thing standing between this key and a second slow hunt.
    //
    // ⚠️ LABEL_ONLY_RADIO IS NOT THIS TEST, THOUGH IT WILL LOOK LIKE THE ANSWER TO THE NEXT
    // READER. It marks a placeholder context standing in for NO context
    // (MediaItemUtils.trackRadioPlaceholder, which by its own comment "only ever labels the header and
    // never alters which radio is generated"). That is PLACEHOLDER-VS-REAL; this gate needs
    // SEED-DETERMINED-VS-CONTEXT-DETERMINED, and the two do not partition the same way:
    //     bare-track / History seed      marked   -> stripped to null   -> seed-determined
    //     explicit station from trackRadio  UNMARKED (a real track-kind Radio) -> seed-determined
    //     album completed -> artist radio   unmarked                    -> context-determined
    // It is TRUE for one seed-determined case and FALSE for the other, so gating on it would exclude the
    // explicit track station - THE EXACT CASE THIS FAN-OUT WAS BUILT FOR. Wrong in the direction that
    // matters. What the marker does contribute is the null half for free: loadPlaylist strips it, so
    // `itemContext == null` is already a sound sufficient test. It cannot supply the track-kind half.
    //
    // WHY THE SEEDS THEMSELVES STILL PASS A NULL CONTEXT (below), given all of the above: that is what
    // makes them produce DISTINCT stations. Forwarding itemContext into each seed would preserve the
    // station's label and destroy the fan-out in the same stroke - see the collapse above. The gate is what
    // makes the null safe, by ensuring we only reach it when the context was not carrying the station's
    // identity in the first place.
    private suspend fun reseedFanOut(itemContext: EchoMediaItem?) {
        if (itemContext != null) {
            val kind = (itemContext as? Radio)?.extras?.get(RADIO_KIND_EXTRA)
            if (kind != RADIO_KIND_TRACK) {
                // PERMANENT (2026-09-12): already "mandatory" here, now said in the word used everywhere
                // else so a strip pass cannot misread it as temporary.
                // Mandatory - see the gate note above. `kind` is reported verbatim so an ABSENT key is
                // distinguishable from a present-but-different one, which is the whole point: absent means
                // the key was dropped somewhere upstream and Radio.kind() is simultaneously treating this
                // station as FLOW, silently.
                val observed = when {
                    itemContext !is Radio -> "not-a-radio:${itemContext::class.simpleName}"
                    kind == null -> "<absent>"
                    else -> kind
                }
                Log.d("GladixRadio", "RESEED reason=not_seed_determined kind=$observed")
                return
            }
        }
        // Claimed AFTER the seed-determined gate above, which is pure logic plus a log and costs nothing,
        // so that gate still reports on every attempt. Only the expensive half is serialised.
        if (!rescueInFlight.compareAndSet(false, true)) {
            Log.d("GladixRadio", "RESEED reason=rescue_in_flight")
            return
        }
        try {
            reseedFanOutLocked()
        } finally {
            rescueInFlight.set(false)
        }
    }

    // Split out purely so the claim above can wrap the whole body in try/finally without reindenting it.
    // Everything here runs under rescueInFlight.
    private suspend fun reseedFanOutLocked() {
        // Seed 1 is the currently playing track - loadPlaylist already tried it, that is what came back
        // thin. Only the remainder are new work.
        val seeds = collectSeeds(RESEED_SEEDS)
        val extra = seeds.drop(1)
        val started = System.currentTimeMillis()
        if (extra.isEmpty()) {
            Log.d("GladixRadio", "RESEED reason=no_distinct_seeds scanned=$SEED_SCAN")
            return
        }

        // ⚠️ [CORRECTED 2026-09-11] THE CLAIM THAT USED TO STAND HERE, kept because it reads as obviously
        // right and is the trap: "Loading BEFORE THE FAN-OUT, NOT AFTER THE FIRST RESULT. play() has
        // already left stateFlow at Empty or Loaded by now, and topUpQueue's only re-entrancy guard is
        // `stateFlow.value is Loading`. Without this line a track transition during the fan-out re-enters
        // loadPlaylist and starts a SECOND concurrent fan-out against the same queue."
        // The DIAGNOSIS was right - a second concurrent fan-out is exactly the hazard. The REMEDY was
        // wrong: stateFlow is not a mutex, and using it as one discards the transitions it blocks instead
        // of deferring them. rescueInFlight provides the same exclusion without touching the state machine.
        // ⚠️ THIS USED TO SET stateFlow = Loading HERE AND HOLD IT ACROSS
        // THE FAN-OUT. The build report that shipped it called that "correct" and said it "handles risk 3"
        // (stateFlow re-entrancy). IT WAS NOT CORRECT, and it was the WORSE of the two instances of this
        // mistake in the file:
        //   - it held Loading across THREE CONCURRENT STATION FETCHES bounded by the extension's 25s
        //     callTimeout - a LONGER window than the ~12s Last.fm bridge it was written alongside;
        //   - with NO finally, so any throw - coroutineScope rethrows CancellationException, appendDeduped
        //     can throw - pinned Loading permanently and stranded the whole radio subsystem. Only the
        //     stations.isEmpty() path restored it.
        //   - and it carried the same lost-wakeup exposure as widening Loading in play(): edge-triggered
        //     transitions inside the window are discarded, not deferred.
        // Loading is now DROPPED here entirely rather than moved into the try/finally. Dropping REMOVES the
        // lost-wakeup exposure; moving it would only have bounded the pinning half. rescueInFlight already
        // provides the mutual exclusion Loading was being misused for, and one mechanism doing one job
        // beats two with overlapping ones.
        // Four concurrent station fetches then one merged append - the same replace-in-between window.
        val epoch = player.queueEpochOrZero

        val stations = coroutineScope {
            extra.map { (clientId, seed) ->
                async {
                    // ⚠️ PER-SEED ISOLATION: THREE OF FOUR SUCCEEDING MUST STILL APPEND. One seed
                    // whose extension is gone, or whose radio() throws, cannot take the fan-out down with
                    // it - that would make multi-seed LESS reliable than the single seed it replaces.
                    // CancellationException is rethrown, never swallowed: the scope dying must propagate.
                    // start() already routes extension errors through getOrThrow(throwFlow) and returns
                    // null, so this catch is a backstop, and it logs rather than emitting to throwFlow -
                    // a supplementary seed failing is not something to put a snackbar in front of the user
                    // for, but it must not be invisible either.
                    try {
                        val ext = extensionList.getExtension(clientId) ?: return@async null
                        // seed is a Track, so start()'s own recordStationSeed covers it.
                        val station = start(throwFlow, ext, seed, null, playingSeed = null)
                            ?: return@async null
                        val page = station.tracks(null) ?: return@async null
                        station to page
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        Log.d(
                            "GladixRadio",
                            "RESEED seed_failed client=$clientId err=${e.javaClass.simpleName}"
                        )
                        null
                    }
                }
            }.awaitAll()
        }.filterNotNull()

        if (stations.isEmpty()) {
            // No restore needed now that Loading is not set: stateFlow still holds whatever play() left,
            // which is Empty on the thin path - exactly "the next transition regenerates".
            Log.d(
                "GladixRadio",
                "RESEED reason=all_seeds_failed seeds=${extra.size} " +
                    "ms=${System.currentTimeMillis() - started}"
            )
            return
        }

        // Retain the station that can actually CONTINUE, then the biggest page. A station with no
        // continuation is spent the moment its page is consumed, so continuation outranks any page size -
        // hence the constant, which is far above any real page (~100).
        val retained = stations.maxByOrNull { (_, page) ->
            (if (page.continuation != null) 1_000_000 else 0) + page.data.size
        }!!

        // ⚠⚠ CROSS-STATION DEDUP HAPPENS HERE, BEFORE appendDeduped's QUEUE-TAIL WINDOW.
        // Four seeds picked for artist-distinctness still land in overlapping neighbourhoods - six surf
        // instrumentals share plenty of neighbours - so the stations' pages duplicate EACH OTHER, not just
        // the queue. appendDeduped's window cannot see that: it compares against what is already queued.
        // Without this pass the merged pool is mostly repeats, the append count collapses, and the thin
        // predicate then misreads a fan-out that actually SUCCEEDED as another failure.
        // Round-robin rather than concatenation so the result is a blend, not four contiguous blocks of
        // one station each.
        val seen = HashSet<String>()
        val merged = ArrayList<Pair<String, Track>>()
        var depth = 0
        outer@ while (merged.size < RESEED_MAX_APPEND) {
            var any = false
            for ((station, page) in stations) {
                val track = page.data.getOrNull(depth) ?: continue
                any = true
                val keys = track.dedupKeys()
                if (keys.isEmpty() || keys.none { it in seen }) {
                    seen.addAll(keys)
                    merged.add(station.clientId to track)
                    if (merged.size >= RESEED_MAX_APPEND) break@outer
                }
            }
            if (!any) break
            depth++
        }

        val appended = appendDeduped(
            player, downloadFlow, app, merged, retained.first.context, epoch,
            source = "fanout", seedKeys = seedKeysFor(retained.first.context.id)
        )
        stateFlow.value =
            if (retained.second.continuation != null)
                retained.first.copy(cont = retained.second.continuation).forQueue(player)
            else PlayerState.Radio.Empty

        // ⚠️ STATE THE NUMBERS, NOT THE SYMPTOM. stations<extra means seeds failed;
        // merged<<sum(pages) means the stations overlapped heavily; appended<merged means the queue tail
        // already held them. Each gap points at a different one of the risks this was built against.
        Log.d(
            "GladixRadio",
            "RESEED seeds=${extra.size} stations=${stations.size} merged=${merged.size} " +
                "appended=$appended cont=${retained.second.continuation != null} " +
                "ms=${System.currentTimeMillis() - started}"
        )
    }

    // ⚠⚠ THE ONLY WAY startRadio AND topUpQueue SHOULD READ stateFlow. A Loaded stamped for a
    // queue that has since been replaced belongs to a station the user has left, and continuing it appends
    // the PREVIOUS extension's tracks into the current queue - see PlayerState.Radio.Loaded.epoch.
    // Downgrades to Empty AND WRITES THAT BACK, so the state self-heals: the next read is clean, this logs
    // once rather than on every transition, and the Empty is exactly what makes loadPlaylist regenerate.
    // ⚠️ FAILS TOWARD REGENERATING, deliberately. If this is ever wrong, the cost is one extra
    // radio request and a lost continuation - and NOTHING RENDERS FROM THIS FLOW (no collect() on
    // state.radio anywhere), so it is invisible. The opposite failure is the defect this closes.
    //
    // ⚠⚠ WHEN THE RADIO SUBSYSTEM ACTUALLY LOOKS AT ITS STATE - worth reading past this function,
    // because it is not obvious from startRadio or topUpQueue alone and it is what makes the drop rare
    // enough to be tolerable.
    // THE DROP IS GUARANTEED, NOT A RACE. It is tempting to assume a stale Loaded is usually beaten by a
    // fresh publication; it is not. Of the five paths that replace a queue, only radio() reliably wins -
    // it sets Loading BEFORE clearMediaItems(), so this function sees Loading and returns early. trackRadio
    // bumps, then does a NETWORK CALL, then publishes, so anything reading inside that window sees the old
    // station. And playItem, backfillQueue and AA's onSetMediaItems PUBLISH NOTHING AT ALL - there is no
    // publication to win, so the stale Loaded simply persists until something drops it.
    // WHAT BOUNDS IT IS THE GUARDS ABOVE THIS CALL, not the publications:
    //   startRadio returns on hasNextMediaItem(), so with a collection queued it is not reached until the
    //     queue nears its end;
    //   topUpQueue returns unless remaining <= RADIO_PREFETCH_THRESHOLD;
    //   and the downgrade WRITES Empty BACK, so it cannot repeat for one queue.
    // Net: an album or playlist drops ONCE, near its end - exactly when the old station would otherwise
    // have been extended. A single-track play drops IMMEDIATELY, because a one-item queue has no next item.
    //
    // ⚠⚠ THE LOG LINE BELOW IS NORMAL OPERATION, NOT A SIGNAL, AND IS TIME-BOXED. Every firing is
    // legitimate and expected, which breaks the convention the rest of this file's logging follows - DROP,
    // FALLBACK and GATEWAY-ERROR fire only on paths that would otherwise be silent, so a healthy session
    // prints nothing. This one prints on ordinary queue switches, and on back-to-back single-track plays
    // it prints on nearly every one.
    // IT IS KEPT ONLY BECAUSE IT IS CURRENTLY THE SOLE EVIDENCE THAT Loaded.epoch WORKS - a silent-correct
    // mechanism with no observable signal cannot be confirmed, only assumed.
    // ⚠️ REMOVE IT ONCE BOTH HALVES HAVE BEEN SEEN ON DEVICE: it FIRES on a queue switch, AND it
    // does NOT fire mid-station. Either alone proves less - firing shows the check runs, not firing
    // mid-station shows the stamp is right, and only the pair shows it is right for the right reason.
    // ⚠️ AND IT CANNOT BE FILTERED DOWN TO THE INTERESTING SUBSET, so do not propose that instead.
    // The notable case is cross-extension contamination - dropping a station from extension A as a queue
    // from extension B starts - but deciding that needs player.currentMediaItem.extensionId, and
    // currentMediaItem IS NOT SAFE TO READ OFF THE MAIN THREAD. stateFlow.value and queueEpochOrZero both
    // are (@Volatile), which is why this function can run wherever it is called from. All-or-nothing is
    // forced by thread confinement, not chosen.
    private fun currentRadio(): PlayerState.Radio {
        val state = stateFlow.value
        if (state !is PlayerState.Radio.Loaded) return state
        val now = player.queueEpochOrZero
        if (state.epoch == now) return state
        Log.d(
            "GladixRadio",
            "STALE_STATION dropped=${state.context.title} builtFor=${state.epoch} now=$now"
        )
        stateFlow.value = PlayerState.Radio.Empty
        return PlayerState.Radio.Empty
    }

    private suspend fun topUpQueue() {
        if (!radioQueueActive) return
        if (stateFlow.value is PlayerState.Radio.Loading) return
        val remaining = withContext(Dispatchers.Main) {
            // Remaining upcoming tracks = full count minus the current index; drives radio prefetch.
            val fullIndex = player.currentMediaItemIndex
            player.mediaItemCount - fullIndex - 1
        }
        if (remaining > RADIO_PREFETCH_THRESHOLD) return
        when (val state = currentRadio()) {
            is PlayerState.Radio.Loaded ->
                play(
                    player, downloadFlow, app, stateFlow, state,
                    extensionList.getExtension(state.clientId), source = "topUpQueue"
                )
            is PlayerState.Radio.Empty -> loadPlaylist()
            else -> {}
        }
    }

    private var autoStartRadio = app.settings.getBoolean(AUTO_START_RADIO, true)

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { pref, key ->
        if (key != AUTO_START_RADIO) return@OnSharedPreferenceChangeListener
        autoStartRadio = pref.getBoolean(AUTO_START_RADIO, true)
    }

    init {
        app.settings.registerOnSharedPreferenceChangeListener(listener)
        scope.coroutineContext[Job]?.invokeOnCompletion {
            app.settings.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    private suspend fun startRadio() {
        if (!autoStartRadio) return
        val shouldNotStart = withContext(Dispatchers.Main) {
            player.run {
                currentMediaItem == null || repeatMode != REPEAT_MODE_OFF || hasNextMediaItem()
            }
        }
        if (shouldNotStart) return
        when (val state = currentRadio()) {
            is PlayerState.Radio.Loading -> {}
            is PlayerState.Radio.Empty -> loadPlaylist()
            // ⚠⚠ [FIXED 2026-09-11] A STALE Loaded USED TO MAKE THIS CONTINUE THE WRONG STATION.
            // Nothing reset radioFlow when a new queue was set - trackRadio and playItem never touched it, and
            // the only reset to Empty was onMediaItemTransition's mediaItemCount == 0 branch - so a Loaded left
            // over from a PREVIOUS station survived into a new queue and this branch extended THAT station,
            // appending the previous extension's tracks. The queue epoch did not catch it either: the
            // replacement bumps the epoch BEFORE play() captures it, so the append was "for" the new queue by
            // that test and landed.
            // Now closed by PlayerState.Radio.Loaded.epoch, read through currentRadio() above. The fix is
            // STRUCTURAL rather than a reset call: a station carries the queue it was built for, so every
            // replacement path - including AA, which never touches this code - is covered by the seam rather
            // than by caller-specific work. Resetting at ShufflePlayer's seam was the obvious alternative and
            // was rejected: ShufflePlayer holds no PlayerState, so it would have meant threading the model into
            // the most bug-fixed file in the app to do something it has no other reason to know about.
            // ⚠️ IT WAS OBSERVED, NOT THEORETICAL, AND IT COMPOSED. On device it paired with the Last.fm
            // latch's per-process dead end to produce a station that stopped with a COMPLETELY EMPTY capture:
            // this half meant loadPlaylist never ran (so no RESEED line, since reseedFanOut is reachable only
            // from there), and the latch half refused the bridge without logging (so no LASTFM line). Each was
            // survivable alone - this one keeps playing, wrongly but visibly. The pair produced total silence.
            // Both halves are now fixed; the record is kept because the SHAPE recurs: two silent defects under
            // one symptom, where finding the first explains only part of the evidence.
            // No `extension` argument, so the endless-queue fallback does NOT run on TV. Deliberate for
            // this pass: TV owns its own end-of-queue path (tvDriveRadio) and reach beyond the motivating
            // extension is explicitly not a goal here. Wiring it is one argument, the same as topUpQueue's Loaded branch.
            is PlayerState.Radio.Loaded ->
                play(player, downloadFlow, app, stateFlow, state, source = "startRadio")
        }
    }

    // TV-only radio driver (see isTv). Mirrors what auto-radio should do, driven from the listener callbacks
    // that DO fire on TV: (1) extend an active radio when running low — like topUpQueue, but gated on the
    // current item being a Radio rather than radioQueueActive, so explicitly-started radios (context menu,
    // card/search trackRadio) extend too, and NOT gated on autoStartRadio (matching topUpQueue); (2) start a
    // radio at the end of ANY queue — album/playlist/track — like startRadio, honoring autoStartRadio.
    // Reuses the unmodified loadPlaylist(), which derives itemContext from the current track exactly as on
    // phone, so the generated radio is identical. tvInFlight (not the radioFlow state) serializes overlapping
    // transition / STATE_ENDED calls so a boundary never double-appends.
    private suspend fun tvDriveRadio(atEnd: Boolean) {
        if (!tvInFlight.compareAndSet(false, true)) return
        try {
            val info = withContext(Dispatchers.Main) {
                val current = player.currentMediaItem ?: return@withContext null
                Triple(
                    current.context,
                    player.mediaItemCount - player.currentMediaItemIndex - 1,
                    player.repeatMode != REPEAT_MODE_OFF
                )
            } ?: return
            val (ctx, remaining, repeating) = info
            if (repeating) return
            val runningLow = ctx is Radio && remaining <= RADIO_PREFETCH_THRESHOLD
            val startAtEnd = (atEnd || remaining <= 0) && autoStartRadio
            if (!runningLow && !startAtEnd) return
            loadPlaylist()
            // If we appended because the queue had fully ENDED (the STATE_ENDED belt fired before a
            // running-low transition could pre-append), playback is parked at the end — advance into the
            // freshly appended radio and resume. In the normal case the append happened while the last track
            // was still playing, so the player isn't ENDED here and this is a no-op.
            if (atEnd) withContext(Dispatchers.Main) {
                if (player.playbackState == Player.STATE_ENDED && player.hasNextMediaItem()) {
                    player.seekToNextMediaItem()
                    player.play()
                }
            }
        } finally {
            tvInFlight.set(false)
        }
    }

    // ⚠⚠ A ZERO-TRACK APPEND DOES NOT FIRE THIS, SO THERE IS NO APPEND -> REGENERATE -> APPEND
    // FEEDBACK LOOP. Recorded because the hypothesis is a good one and WILL be re-derived: appendDeduped
    // calls player.addMediaItems(items) UNCONDITIONALLY - there is no isNotEmpty guard - so a 2/0 append
    // really does call into the player, and `reason` being ignored below really does mean every timeline
    // change reaches startRadio. Both halves are true; the conclusion still does not follow.
    // READ FROM media3 1.11.0 SOURCE, NOT INFERRED:
    //   ExoPlayerImpl.java:709 addMediaSources has no empty short-circuit - it calls
    //     addMediaSourcesInternal then updatePlaybackInfo(..., TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED).
    //   ExoPlayerImpl.java:2312 `boolean timelineChanged = !previousPlaybackInfo.timeline.equals(...)`.
    //   ExoPlayerImpl.java:2356 `if (timelineChanged)` GUARDS the EVENT_TIMELINE_CHANGED dispatch.
    //   Timeline.java:1369 Timeline.equals is VALUE-BASED - window count, period count, every Window and
    //     Period, plus the shuffled-order walk.
    // Adding zero holders leaves all of those identical, so timelineChanged is false and no event is
    // queued. THE GUARD THAT SAVES US IS IN THE LIBRARY, NOT IN OUR CODE - which is exactly why it must be
    // written down here rather than trusted to be re-noticed.
    // And the non-empty case is self-limiting by construction: an append that DOES mutate the timeline is
    // the same append that makes hasNextMediaItem() true, so startRadio returns at its own guard.
    //
    // ⚠️ WHAT IS STILL UNEXPLAINED, AND IS NOT THIS: a cluster of three regenerations 251ms and
    // 376ms apart in the 2026-09-12 capture (08:04:50.686 / .937 / 51.313) - too fast for track
    // transitions, too slow for the 19ms concurrent burst that generationInFlight closes, and each gap wide
    // enough for a full start() + page fetch to have completed in between.
    // ⚠️ HYPOTHESIS, NOT A FINDING, AND IT IS NOT BUILT ON: `reason` is ignored below, so
    // TIMELINE_CHANGE_REASON_SOURCE_UPDATE reaches startRadio too. A track's window changes by VALUE as it
    // resolves - Timeline.Window.equals (Timeline.java:378) compares durationUs and isPlaceholder - and
    // this app's sources are deferred, so resolution publishes real timelines after playback starts. With
    // remaining == 0 the hasNextMediaItem guard is open, so each such update would reach loadPlaylist.
    // THE DISCRIMINATOR IS ALREADY IN THE NEXT CAPTURE, no new logging needed: sequential entries 250ms
    // apart do NOT overlap, so generationInFlight neither blocks nor masks them - a refusal ALWAYS logs.
    //   cluster replaced by `LOADPLAYLIST reason=generation_in_flight`  -> it was concurrent after all
    //   cluster still present, no refusal lines                         -> a third, SEQUENTIAL trigger
    // Do not add a `reason` filter here on the strength of the hypothesis alone: this callback predates
    // that reading and its breadth has never been shown to be accidental.
    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        if (isTv) return // TV: radio is driven by onMediaItemTransition + onPlaybackStateChanged instead
        scope.launch { startRadio() }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (player.mediaItemCount == 0) {
            stateFlow.value = PlayerState.Radio.Empty
            radioQueueActive = false
        }
        if (isTv) {
            scope.launch { tvDriveRadio(atEnd = false) }
            return
        }
        scope.launch { startRadio() }
        scope.launch { topUpQueue() }
    }

    // TV-only end-of-queue hook (phone never overrode this; default Player.Listener impl is empty). When the
    // queue fully ends without a running-low transition having pre-appended, start/continue the radio.
    override fun onPlaybackStateChanged(playbackState: Int) {
        if (isTv && playbackState == Player.STATE_ENDED) scope.launch { tvDriveRadio(atEnd = true) }
    }
}

