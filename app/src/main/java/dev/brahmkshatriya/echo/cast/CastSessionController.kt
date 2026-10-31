package dev.brahmkshatriya.echo.cast

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.content.edit
import androidx.media3.cast.RemoteCastPlayer
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import dev.brahmkshatriya.echo.common.models.ExtensionType
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.extensionPrefId
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.prefs
import androidx.media3.session.MediaSession
import com.google.android.gms.cast.MediaError
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.common.models.Message
import androidx.core.content.ContextCompat
import dev.brahmkshatriya.echo.di.App
import dev.brahmkshatriya.echo.di.PermissionRequest
import dev.brahmkshatriya.echo.di.PermissionResult
import dev.brahmkshatriya.echo.playback.MediaItemUtils
import dev.brahmkshatriya.echo.playback.MediaItemUtils.extensionId
import dev.brahmkshatriya.echo.playback.MediaItemUtils.serverIndex
import dev.brahmkshatriya.echo.playback.MediaItemUtils.track
import dev.brahmkshatriya.echo.playback.PlayerState
import dev.brahmkshatriya.echo.playback.listener.TrackingListener
import dev.brahmkshatriya.echo.playback.source.LocalMediaServer
import dev.brahmkshatriya.echo.utils.PermsUtils.openAppSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Owns a Cast session end to end: decides whether one may start, swaps the session player while it
 * lasts, keeps exactly one track loaded on the receiver, and puts everything back when it ends.
 *
 * ⚠⚠ THE QUEUE NEVER LEAVES THE PHONE. Nothing is transferred - no queue, no shuffle order, no
 * repeat mode. The receiver is handed ONE item at a time and the local player remains the only queue
 * there is: a cast-side end advances it, [CastSwitchPlayer] routes next/previous and every edit to it,
 * and the notification keeps showing its metadata. That is what makes the swap reversible at any
 * instant.
 *
 * ⚠⚠ MAIN THREAD FOR EVERYTHING THAT TOUCHES THE SESSION, BECAUSE setPlayer REQUIRES IT.
 * MediaSession.setPlayer asserts `player.getApplicationLooper() == Looper.myLooper()`
 * (MediaSession.java:988, 1.11.0) on top of requiring the new player to share the outgoing player's
 * looper. Cast's SessionManagerListener and RemoteMediaClient.Callback both arrive on the main thread
 * and the ExoPlayer was built there, so both hold - but materialisation runs on IO and posts back
 * before it loads anything.
 *
 * ⚠⚠ FAILURE DETECTION IS TWO MECHANISMS BECAUSE RemoteCastPlayer PROVIDES NEITHER, AND THE
 * FIRST BUILD SHIPPED WITH NO WORKING ONE. Its getPlayerError() returns unconditional null
 * (RemoteCastPlayer.java:623-627) and nothing in that file ever constructs a PlaybackException, so a
 * Player.Listener can never hear about a receiver failure; and its state mapping (:1840-1853) reads
 * only getPlayerState(), so an error-idle and a nothing-loaded idle are the same STATE_IDLE. So the
 * idleReason is read off [MediaStatus] through a [RemoteMediaClient.Callback], and a
 * [LOAD_TIMEOUT_MS] timer is armed per load. Without both, a load that never completes is silent
 * forever - an endless spinner with no message, which is exactly what was observed.
 *
 * ⚠️ WHAT HOLDS THE HEAP, given the 1032 aggregate-working-set history. CastContext and
 * RemoteCastPlayer both pin Play Services object graphs once touched. CastContext is reached only
 * through CastAvailability, which is lazy and user-triggered; the RemoteCastPlayer is built here on
 * session start and released on session end with [remote] nulled. [prepared] holds ids, tokens and one
 * MediaItem each; the materialised bytes live on disk, never in memory.
 */
@OptIn(UnstableApi::class)
class CastSessionController(
    private val app: App,
    private val scope: CoroutineScope,
    private val state: PlayerState,
    private val localServer: LocalMediaServer,
    private val store: CastStreamStore,
    private val localPlayer: Player,
    private val session: () -> MediaSession?,
    private val tracking: () -> TrackingListener?,
    private val isAndroidAutoConnected: () -> Boolean,
    private val abortCrossfade: () -> Unit,
) {

    /**
     * ⚠⚠ TELLS THE DEEZER EXTENSION TO ASK FOR PLAIN FLAC WHILE CASTING,
     * BECAUSE IT CANNOT SEE CastState ITSELF. Google's Cast media documentation lists FLAC only
     * up to 96 kHz / 24-bit, and a Deezer HiFi master can be 192 kHz - so the receiver would be
     * handed something it is not documented to decode, AFTER we had materialised the whole file
     * (about 145 MB for a four-minute 24/192 track, against CastStreamStore's 256 MB cap).
     *
     * ⚠⚠ A SECOND KEY, NEVER THE USER'S. The switch lives at `flac_24` in the
     * extension's own prefs; this writes `flac_24_cast_off` beside it and DeezerMedia.hiFiWanted
     * ANDs the two. Writing the user's key instead would need a save/restore pair around every
     * session - and any crash in between would silently change a setting they chose.
     * ⚠️ WHY THE APP REACHES INTO AN EXTENSION'S PREFS AT ALL: the decision
     * needs app-side knowledge (a live Cast session) and the formats list is extension-side, and
     * there is no channel between them - the extension module has no Android Context and no
     * access to app classes. Streamable carries no per-request flag either. The precedent for
     * app-side Deezer knowledge is TvPairingFragment.getArl, which reads that extension's user
     * row by the same literal id.
     * [CORRECTED 2026-10-31] getArl no longer exists: pairing now validates through
     * TvPairingFragment.validatedArl (DeezerExtension.userForPairing), still keyed by the literal "deezer".
     * ⚠️ CLEARED AT CONSTRUCTION AS WELL AS AT SESSION END, so a process
     * death mid-session cannot latch it: the only cost of a stale true is plain FLAC on the
     * phone, but it would persist until the next cast session ended, which is worse than one
     * write at service create.
     * ⚠️ BEST-EFFORT. A prefs write cannot be allowed to fail a cast start, so
     * the failure mode is the status quo (HiFi requested) rather than no session.
     *
     * ⚠⚠ [TRIGGER FIRED 2026-10-25 - NOW HANDLED BY reresolveIfHighRes]
     * Pink Floyd, "Money", pill showing 96 kHz locally: STUTTERED on an Amazon Fire TV, where
     * plain-FLAC casts never have. So the exposure below is real, and the revisit condition at
     * the end of this note was too narrow - it asked for the FIRST CAST TRACK FAILING TO LOAD,
     * and the device instead played it badly. That is the worse outcome of the two: failAndStop
     * reports itself, a stutter does not.
     * ⚠️ AND THE TEST IS NOT "WAS IT FLAC_24" - that cannot be asked,
     * because nothing records the format the relay chose. It is the DECODED sample rate on the
     * local player, > 48 kHz. See [reresolveIfHighRes].
     * ⚠⚠ IT DOES NOT COVER THE TRACK THAT WAS ALREADY RESOLVED - EXPOSURE IS
     * THE FIRST TRACK OF A SESSION, AND THAT IS ACCEPTED RATHER THAN MISSED. [planFor] reads
     * state.servers[track.id], i.e. the stream the LOCAL resolve already produced, so a track
     * resolved before the session started keeps whatever format it was resolved at - this flag
     * only reaches resolves that happen after it. It is exactly one track: per the note at
     * [advanceLocalQueue], the next item is NOT resolved while the local player is parked, so
     * every later track in the session is resolved after this write and gets plain FLAC. And if
     * casting starts from a state where nothing is resolved yet, planFor returns null and the
     * resolution re-enters later - already after the write.
     * ⚠️ [DONE 2026-10-25] WHY NOT FORCE A RE-RESOLVE: dropping
     * state.servers[id] would not cause one - nothing re-prepares a parked player - so planFor
     * would find no plan and the session would stall. Making it real means replaceMediaItem on
     * the local player at cast start, which spends a fresh resolve (and a token) on every
     * session and touches the parked-player path at the moment CastSwitchPlayer is being
     * installed. THAT IS NOW BUILT - [reresolveIfHighRes], gated on the decoded sample rate so
     * it costs nothing for the 44.1/48 kHz streams that are almost every track. The analysis
     * above stands as written; only the verdict changed, and the two halves it names (drop the
     * entry AND force the resolve) are exactly what that function does.
     */
    /**
     * Throw away a high-res stream the phone already resolved, so the first cast track is
     * re-resolved under [setHiFiSuppressed] and the receiver is handed plain FLAC.
     *
     * ⚠⚠ THE TEST IS THE DECODED SAMPLE RATE, NOT "WAS IT FLAC_24",
     * BECAUSE NOTHING RECORDS THE FORMAT WE ASKED FOR. DeezerMedia sends
     * arrayOf("FLAC_24", "FLAC") and keeps only the URL the relay answers with; the resulting
     * Streamable is identical either way (quality 9, "FLAC"). What IS available is the format
     * the local decoder is actually using - the same value the quality pill prints via
     * FormatUtils.toAudioDetails - and that is the better test anyway: the receiver cares what
     * it is being handed, not what we requested, so this also covers any other extension that
     * ever serves high-res.
     * ⚠️ > 48 kHz, WHICH IS "ABOVE WHAT EVERY CAST RECEIVER TAKES"
     * RATHER THAN A MEASURED CLIFF. 44.1 and 48 are the universal rates; 88.2/96/176.4/192 are
     * the HiFi tier and the one measured failure sits at 96. Format.NO_VALUE (-1) and an
     * un-started player both fall below it, so nothing happens when nothing is decoding - which
     * is correct: with no stream resolved there is nothing to throw away and the resolve that
     * follows is already after the suppression write.
     * ⚠⚠ BOTH HALVES ARE NEEDED AND NEITHER WORKS ALONE. Dropping
     * state.servers[id] alone leaves nothing to re-resolve - a parked player never re-prepares -
     * so planFor would find no plan and the session would stall, which is what the note at
     * [setHiFiSuppressed] predicted. replaceMediaItem alone races: the stale entry is still in
     * state.servers when loadCurrentOnCast runs, so the 24-bit plan goes out anyway. Dropping it
     * makes planFor return null, which is already defined as "not an error; its resolution
     * re-enters here", and the re-entry is the existing state.current observer.
     * ⚠️ withReresolve, NOT withRetry: the bump has to be a field
     * canUpdateMediaItem compares, and `retries` is an error budget PlayerEventListener spends
     * on skip decisions. See MediaItemUtils.withReresolve.
     * ⚠️ THE COST, STATED: one extra resolve and one extra Deezer token
     * per session, only when the current stream is high-res. If it fails, planFor keeps
     * returning null and the existing loadTimeout/failAndStop path handles it - the same handled
     * failure as a track that never resolves, not a new one.
     * ⚠️ BEFORE pause() AND BEFORE CastSwitchPlayer IS INSTALLED, so
     * the item is replaced on a player that is still prepared and still owns its own session.
     */
    private fun reresolveIfHighRes() {
        val item = localPlayer.currentMediaItem ?: return
        val index = localPlayer.currentMediaItemIndex
        val sampleRate = selectedAudioSampleRate()
        if (sampleRate <= HIGH_RES_SAMPLE_RATE_HZ) return
        state.servers.remove(item.track.id)
        runCatching { localPlayer.replaceMediaItem(index, MediaItemUtils.withReresolve(item)) }
    }

    // The format the local decoder selected, or Format.NO_VALUE when nothing is decoding. Same
    // selection rule as FormatUtils' own (first selected track of the first audio group), spelled
    // out here rather than widening that file's private helper for one caller.
    private fun selectedAudioSampleRate(): Int = runCatching {
        localPlayer.currentTracks.groups
            .filter { it.type == C.TRACK_TYPE_AUDIO }
            .firstNotNullOfOrNull { group ->
                (0 until group.length).firstOrNull { group.isTrackSelected(it) }
                    ?.let { group.getTrackFormat(it).sampleRate }
            }
    }.getOrNull() ?: Format.NO_VALUE

    private fun setHiFiSuppressed(suppressed: Boolean) {
        runCatching {
            extensionPrefId(ExtensionType.MUSIC.name, DEEZER_EXTENSION_ID)
                .prefs(app.context)
                .edit { putBoolean(HIFI_CAST_OFF_KEY, suppressed) }
        }
    }

    init {
        setHiFiSuppressed(false)
    }

    /**
     * The phone's position at cast start, used by the FIRST LOAD of the session whenever it is
     * sent.
     *
     * ⚠⚠ WITHOUT THIS THE FIRST TRACK WOULD RESTART FROM ZERO ON THE TV
     * WHENEVER THE LOAD IS DEFERRED. The live position is read ONCE, at the
     * loadCurrentOnCast call in onCastStarted. If planFor finds no resolved server the LOAD does
     * not happen there; it happens on the next state.current emission, which arrives through
     * PlayerService with `positionMs = 0L` - correct for a track CHANGE and wrong for the one
     * track that was already playing.
     * ⚠️ THE RE-RESOLVE BELOW MAKES THAT THE NORMAL CASE, which is why
     * this is here. It was reachable before - casting while the current track had not resolved
     * yet - but harmless then, because an unresolved track is at position ~0 anyway.
     * ⚠️ CONSUMED IN [sendLoad], NOT WHERE IT IS READ, so every path to
     * the first LOAD gets it and the second LOAD cannot.
     */
    private var castStartPositionMs: Long? = null

    private var remote: RemoteCastPlayer? = null
    private var wrapper: CastSwitchPlayer? = null
    private var mediaClient: RemoteMediaClient? = null

    private class PendingPermission(
        val sessionId: String?,
        val result: CompletableDeferred<PermissionResult>,
    )

    /**
     * ⚠⚠ THE LOCAL-NETWORK PROMPT THAT IS STILL ON SCREEN, HELD FOR TWO JOBS, AND
     * ONE OF THEM IS A RE-ENTRY GUARD. The wait can last [PERMISSION_TIMEOUT_MS] with the Cast
     * session connected and nothing of ours started, so:
     *   1. if the user disconnects or the receiver drops in that window, [onCastEnded] completes
     *      it with `sessionEnded` rather than leaving the coroutine parked until the timeout;
     *   2. non-null means "a prompt is already up", which is the ONLY thing standing between a
     *      second [onCastStarted] and a second dialog - see the guard there.
     * Main thread only, like every other field here.
     */
    private var pendingPermission: PendingPermission? = null

    /** What is on the receiver now, so the state.current observer cannot restart the same track. */
    private var castedMediaId: String? = null

    /**
     * ⚠⚠ THE mediaId OF A LOAD THAT HAS STARTED BUT NOT YET BEEN SENT, AND WITHOUT
     * IT A TRACK LOADS TWICE. [castedMediaId] is set in [sendLoad], which for a Materialise plan
     * is a file copy away - a window of a couple of hundred milliseconds in which PlayerService's
     * state.current observer calls [loadCurrentOnCast] again, finds castedMediaId still null and
     * starts the entire thing a second time.
     * ⚠️ MEASURED ON DEVICE 2026-10-18: two plans, two copies and two LOADs 150 ms
     * apart, and the SECOND won. It came from the observer, which passes pos=0, so the receiver
     * was told to start at the beginning of a track the phone was 63 s into - and the 63 s was
     * then also what onCastEnded could not restore. Claiming the id when the load STARTS closes
     * the window; it is cleared when that load lands, so a genuine retry is still possible.
     */
    private var pendingMediaId: String? = null

    /**
     * ⚠⚠ ORDERS LOADS THAT FINISH OUT OF ORDER, AND WITHOUT IT A STALE COPY CAN
     * OVERWRITE A NEWER ONE. A Materialise load completes a file copy some time after the event
     * that started it, so Next pressed mid-copy leaves TWO in flight - and nothing makes the
     * first one asked for the first one to finish, because they are different files of different
     * sizes on different threads. A callback whose generation is no longer the current one is
     * abandoned rather than sent.
     * ⚠️ [pendingMediaId] CANNOT DO THIS JOB. It answers "is this track already
     * being loaded", which is a different question from "is this load still the one we want" -
     * and the second is the one that decides whether a late callback may touch the receiver.
     * Bumped at both commit points: here, and in [sendLoad] for the synchronous path.
     */
    private var loadGeneration = 0

    /** A materialisation in flight, so a newer load can STOP it rather than race it. */
    private class Copy(
        val job: Job,
        val token: String,
        val mediaId: String,
        val generation: Int,
    )

    private var copy: Copy? = null

    /** Cancelled on the first STATE_READY after a load; fires if the receiver never gets there. */
    private var loadTimeout: Job? = null

    /** See [armInboundWatch]. Cancelled by the next LOAD and by session end. */
    private var inboundWatch: Job? = null

    /**
     * ⚠️ THE TRANSIENT-ENDED GUARD, KEPT UNTIL DEVICE TESTING SAYS IT IS REDUNDANT.
     * RemoteCastPlayer reports STATE_ENDED between items - the previous one finished, the new one has
     * not started - so an unguarded "ENDED means advance" skips a track on every transition. The
     * idleReason path below is the precise signal (IDLE_REASON_FINISHED) and should make this
     * unnecessary, but both stay live until a drive confirms it.
     */
    private var lastLoadAtMs = 0L

    /**
     * mediaId -> what was prepared for it. Holds only what is on the receiver; see
     * [retainPrepared].
     */
    private val prepared = HashMap<String, Prepared>()

    private data class Prepared(
        val mediaId: String,
        val token: String,
        val plan: CastPlan,
        val item: MediaItem,
    )

    // ── Receiver signals ────────────────────────────────────────────────────────────────────────

    private val remoteListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            Log.d(TAG, "remote state=" + stateName(playbackState))
            when (playbackState) {
                Player.STATE_READY -> {
                    loadTimeout?.cancel()
                    loadTimeout = null
                }

                Player.STATE_ENDED -> {
                    val sinceLoad = System.currentTimeMillis() - lastLoadAtMs
                    if (sinceLoad >= TRANSIENT_ENDED_MS) advanceLocalQueue()
                    else Log.d(TAG, "ignoring transient ENDED " + sinceLoad + "ms after load")
                }

                else -> Unit
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // One timer, one record: TrackingListener keeps its own 30 s PauseTimer and its
            // currentFlow-driven track identity. While casting the only thing it is missing is a
            // truthful "am I playing", because the local player it listens to is parked.
            tracking()?.onIsPlayingChanged(isPlaying)
        }
    }

    /**
     * ⚠⚠ THE ONLY PLACE A RECEIVER FAILURE IS VISIBLE. media3 drops idleReason entirely, so this
     * reads it off MediaStatus directly. IDLE_REASON_ERROR is a failed load or a decode the receiver
     * gave up on; IDLE_REASON_FINISHED is a clean end of item and advances the queue, which is the
     * exact signal the 3 s window above only approximates.
     */
    private val clientCallback = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() {
            val status = mediaClient?.mediaStatus ?: return
            val playerState = status.playerState
            val idleReason = status.idleReason
            Log.d(TAG, "mediaStatus playerState=" + playerState + " idleReason=" + idleReason)
            if (playerState != MediaStatus.PLAYER_STATE_IDLE) return
            when (idleReason) {
                MediaStatus.IDLE_REASON_ERROR -> {
                    Log.w(TAG, "receiver idle with ERROR - stopping")
                    failAndStop(R.string.cast_stopped)
                }

                MediaStatus.IDLE_REASON_FINISHED -> advanceLocalQueue()
                else -> Unit
            }
        }

        /**
         * ⚠⚠ THE RECEIVER'S OWN VERDICT ON OUR LOAD, AND THE ONLY PLACE IT ARRIVES.
         * The LOAD goes out through RemoteCastPlayer.setMediaItem, which issues
         * remoteMediaClient.load internally and consumes the PendingResult<MediaChannelResult>
         * itself - nothing in media3 1.11.0 surfaces that result, and its getPlayerError() is
         * unconditional null (RemoteCastPlayer.java:623-627). So the request's own status code and
         * message are NOT reachable without issuing the load ourselves, which would mean two load
         * paths for one item. This callback is the free half: whatever the receiver reports about
         * the media is delivered here, type and reason as strings plus the numeric
         * detailedErrorCode that distinguishes, for example, a fetch failure from a decode one.
         * Verified present in play-services-cast-framework 22.3.1 (RemoteMediaClient$Callback
         * .onMediaError) with the getters on MediaError in play-services-cast 22.3.1.
         *
         * ⚠️ LOG ONLY, DELIBERATELY. [onStatusUpdated] above already owns the failure
         * decision through IDLE_REASON_ERROR, and a second actor on the same event would race it
         * into a double stop. customData is also deliberately not logged: it is receiver-authored
         * and can echo the request, token and all.
         */
        override fun onMediaError(mediaError: MediaError) {
            Log.w(
                TAG, "receiver mediaError type=" + mediaError.type +
                    " reason=" + mediaError.reason +
                    " detailedCode=" + mediaError.detailedErrorCode +
                    " requestId=" + mediaError.requestId
            )
        }
    }

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(castSession: CastSession, sessionId: String) =
            onCastStarted(sessionId)

        // A resume carries no sessionId parameter, so it is read off the session. Verified present
        // on the base type: Session.getSessionId() / Session.isConnected(), decoded from
        // play-services-cast-framework 22.3.1 (CastSession extends Session).
        override fun onSessionResumed(castSession: CastSession, wasSuspended: Boolean) =
            onCastStarted(runCatching { castSession.sessionId }.getOrNull())

        override fun onSessionEnded(castSession: CastSession, error: Int) = onCastEnded()
        override fun onSessionSuspended(castSession: CastSession, reason: Int) = onCastEnded()
        override fun onSessionStarting(castSession: CastSession) = Unit
        override fun onSessionEnding(castSession: CastSession) = Unit
        override fun onSessionResuming(castSession: CastSession, sessionId: String) = Unit
        override fun onSessionStartFailed(castSession: CastSession, error: Int) = onCastEnded()
        override fun onSessionResumeFailed(castSession: CastSession, error: Int) = onCastEnded()
    }

    // ── Registration ────────────────────────────────────────────────────────────────────────────

    /**
     * ⚠️ A NO-OP WHEN THE DEVICE CANNOT CAST, AND IT COSTS NO CastContext BEYOND THE CHECK.
     * CastAvailability returns null when Play Services is missing or stale, in which case no Cast code
     * runs again - requirement 7's "no crash, no button" in one branch.
     */
    fun register() {
        val context = CastAvailability.castContext(app.context) ?: return
        runCatching {
            context.sessionManager.addSessionManagerListener(sessionListener, CastSession::class.java)
        }
        Log.d(TAG, "session listener registered")
    }

    fun unregister() {
        val context = CastAvailability.castContext(app.context) ?: return
        runCatching {
            context.sessionManager
                .removeSessionManagerListener(sessionListener, CastSession::class.java)
        }
        if (CastState.isCasting) onCastEnded()
    }

    // ── Session start ───────────────────────────────────────────────────────────────────────────

    /**
     * ⚠⚠ THE PERMISSION GATE, AND IT RUNS BEFORE THE SERVER BINDS RATHER THAN
     * BEFORE THE LOAD. On Android 17 (API 37) with targetSdk 37, the receiver cannot open a TCP
     * connection IN to [LocalMediaServer] without ACCESS_LOCAL_NETWORK, and a missing grant does
     * not error - the connection TIMES OUT. Build 1141 is exactly that shape: a registered plan, a
     * materialised 6.4 MB file, contentId=sameAsUrl on the wire, playerState=5 on the receiver,
     * NOT ONE "accept from" line, and our own 15 s load timeout. Metadata and artwork arrived
     * because neither comes from us - control goes through Play Services and the cover is a CDN
     * URL the receiver fetches itself.
     * ⚠️ ASKED HERE AND NOWHERE ELSE, WHICH IS THE PRODUCT DECISION: the user has
     * just picked a Cast device, so the prompt has a reason the user can see. At app start it
     * would be an unexplained network-permission dialog on every first launch, and most users
     * never cast. The cost is that the first cast of a fresh install shows a dialog mid-connect.
     * ⚠️ AND BEFORE [LocalMediaServer.start] SPECIFICALLY. Binding first would
     * leave a listening socket nothing can reach, and the purge it dispatches would run for a
     * session that is about to be refused.
     */
    private fun onCastStarted(sessionId: String?) {
        if (CastState.isCasting) return
        // AA and Cast are mutually exclusive. Refused here rather than by hiding the button, because
        // a session can be started from the TV or another phone - the button is not the only entry.
        if (isAndroidAutoConnected()) {
            Log.w(TAG, "refusing session: Android Auto connected")
            message(R.string.cast_refused_android_auto)
            endSession()
            return
        }
        // ⚠⚠ A PROMPT ALREADY UP MEANS DROP THIS CALL, BECAUSE isCasting IS FALSE
        // FOR THE WHOLE WAIT AND THE GUARD AT THE TOP OF THIS FUNCTION THEREFORE DOES NOT HOLD.
        // Two routes reach here twice for one connection: onSessionStarted and onSessionResumed
        // both call this, and a disconnect/reconnect inside the wait produces a second start. Two
        // prompts would be bad on its own; worse is that the second would OVERWRITE this field,
        // orphaning the first deferred - which nothing then completes, so it waits out the full
        // two minutes and ends with `timeout` -> endSession(), killing the session the SECOND
        // prompt had just been granted for.
        // ⚠️ THE DROPPED CALL IS NOT RE-QUEUED, AND THAT IS THE ACCEPTED TRADE.
        // If the pending prompt belongs to a session that has since been replaced, its own
        // stale-session guard below refuses to start and the user re-taps - one lost tap in a
        // double fault, against a guaranteed-correct state. `same=` in the line says which case
        // it was, so a capture can show if the same-session half ever actually happens.
        val inFlight = pendingPermission
        if (inFlight != null) {
            Log.w(
                TAG, "ignoring session start: local-network prompt in flight (same=" +
                    (inFlight.sessionId == sessionId) + ")"
            )
            return
        }
        if (localNetworkState() != LOCAL_NET_DENIED) return startCastSession()
        // ⚠⚠ NO ACTIVITY MEANS NO PROMPT, AND subscriptionCount IS HOW WE KNOW
        // RATHER THAN GUESS. A session can start from the TV or the notification with our UI
        // backgrounded, and the broker is PermsUtils.setupPermissionRequests behind
        // ContextUtils.observe - lifecycle-gated to STARTED, so the emission would simply be
        // dropped (the same gap App.messageFlow's note describes). Waiting on a deferred nobody
        // will complete is the 15 s spinner again with two extra minutes on it, so the absence of
        // a collector is answered immediately. See App.permissionRequestFlow, where this check is
        // part of the contract rather than an optimisation.
        if (app.permissionRequestFlow.subscriptionCount.value == 0) {
            Log.w(TAG, "refusing session: localNetwork=noUi")
            failAndStop(R.string.cast_needs_local_network_foreground)
            return
        }
        val request = PermissionRequest(
            Manifest.permission.ACCESS_LOCAL_NETWORK, CompletableDeferred()
        )
        pendingPermission = PendingPermission(sessionId, request.result)
        Log.d(TAG, "requesting local-network permission")
        scope.launch {
            app.permissionRequestFlow.emit(request)
            // The broker completes this on every path, including a cancelled dialog, and
            // [onCastEnded] completes it if the session goes away - so the timeout is a backstop
            // for a collector that dies mid-dialog, not the normal exit.
            val result = withTimeoutOrNull(PERMISSION_TIMEOUT_MS) { request.result.await() }
            withContext(Dispatchers.Main) {
                if (pendingPermission?.result === request.result) pendingPermission = null
                val outcome = result?.outcome ?: "timeout"
                Log.d(TAG, "local-network permission -> " + outcome)
                if (outcome == PERMISSION_SESSION_ENDED) return@withContext
                if (result?.granted != true) {
                    // ⚠️ A PERMANENT DENIAL NEEDS A WAY OUT, because the system
                    // will not show the dialog again - the same answer checkAppPermissions gives a
                    // refusal, routed through the snackbar's action instead of a dialog.
                    // ⚠️ AND activityDestroyed IS NOT A REFUSAL. The prompt was
                    // torn down by a rotation or a theme change (see
                    // PermsUtils.setupPermissionRequests), so the user decided nothing and the
                    // honest message is "try again" rather than "you denied this".
                    when (outcome) {
                        "deniedPermanently" -> messageWithSettings()
                        "activityDestroyed" -> message(R.string.cast_needs_local_network_retry)
                        else -> message(R.string.cast_needs_local_network)
                    }
                    endSession()
                    return@withContext
                }
                // ⚠⚠ THE SESSION MAY HAVE ENDED WHILE THE DIALOG WAS UP, AND
                // STARTING ON A DEAD SESSION IS WORSE THAN NOT STARTING. Up to two minutes can
                // pass here with the receiver connected and nothing of ours attached, so the
                // grant is re-validated against the framework rather than assumed: the current
                // session must exist, be connected, and - when we were given an id - be the same
                // one. CastState.isCasting is checked too, for the disconnect/reconnect case where
                // a SECOND onCastStarted already got through on a later grant.
                val current = runCatching {
                    CastAvailability.castContext(app.context)?.sessionManager?.currentCastSession
                }.getOrNull()
                val connected = current != null &&
                    runCatching { current.isConnected }.getOrDefault(false) &&
                    (sessionId == null ||
                        runCatching { current.sessionId }.getOrNull() == sessionId)
                if (!connected || CastState.isCasting) {
                    Log.w(
                        TAG, "permission result for ended session (connected=" + connected +
                            " casting=" + CastState.isCasting + ")"
                    )
                    return@withContext
                }
                startCastSession()
            }
        }
    }

    /** Everything the session needs once the local-network question is settled. */
    private fun startCastSession() {
        if (!localServer.start()) {
            Log.w(TAG, "refusing session: no Wi-Fi address to bind")
            message(R.string.cast_needs_wifi)
            endSession()
            return
        }
        val castPlayer = runCatching { RemoteCastPlayer.Builder(app.context).build() }.getOrNull()
        if (castPlayer == null) {
            Log.w(TAG, "refusing session: RemoteCastPlayer could not be built")
            message(R.string.cast_stopped)
            endSession()
            return
        }
        Log.d(TAG, "session starting localNetwork=" + localNetworkState())
        remote = castPlayer
        castPlayer.addListener(remoteListener)
        mediaClient = CastAvailability.castContext(app.context)
            ?.sessionManager?.currentCastSession?.remoteMediaClient
        runCatching { mediaClient?.registerCallback(clientCallback) }

        // Flag first, so every gate - ShufflePlayer.play, the crossfade arm, the watchdog - is closed
        // before anything else moves.
        CastState.setCasting(true)
        // BEFORE loadCurrentOnCast below, which is what resolves the stream: set after it, the
        // first cast track would already have been requested in HiFi. See [setHiFiSuppressed].
        setHiFiSuppressed(true)
        // Read BEFORE the re-resolve and before pause(), and used by whichever round of
        // loadCurrentOnCast ends up sending the first LOAD - see [castStartPositionMs].
        castStartPositionMs =
            runCatching { localPlayer.currentPosition }.getOrNull()?.takeIf { it != C.TIME_UNSET }
        reresolveIfHighRes()
        abortCrossfade()
        runCatching { localPlayer.pause() }
        tracking()?.castSource = castPlayer

        val switch = CastSwitchPlayer(localPlayer, castPlayer)
        wrapper = switch
        val mediaSession = session()
        if (mediaSession == null) {
            Log.w(TAG, "no media session; unwinding")
            onCastEnded()
            return
        }
        runCatching { mediaSession.player = switch }
        loadCurrentOnCast(localPlayer.currentPosition, play = true, force = true)
    }

    // ── One track at a time ─────────────────────────────────────────────────────────────────────

    /**
     * Called by PlayerService on every state.current emission while casting, and once at session
     * start with the live position.
     *
     * ⚠️ EVERY EMISSION IS PASSED IN, INCLUDING THE UNLOADED ONE, and [castedMediaId]
     * plus [pendingMediaId] decide - the first is what the receiver has, the second what it is
     * about to be given.
     * state.current emits at least twice per track - once unloaded, once after the stream resolves -
     * and it is the LOADED one that matters, because before it state.servers has no entry. Keying the
     * observer on mediaId alone would miss it; guarding here instead means an unresolved track simply
     * retries when its resolution lands.
     */
    fun loadCurrentOnCast(positionMs: Long, play: Boolean, force: Boolean = false) {
        if (!CastState.isCasting) return
        val current = state.current.value?.mediaItem ?: return
        if (!force && (current.mediaId == castedMediaId || current.mediaId == pendingMediaId))
            return

        // See [castStartPositionMs]: the session's first LOAD uses the position the phone was at
        // when casting started, whichever round of this function ends up sending it.
        val startPos = castStartPositionMs ?: positionMs
        prepared[current.mediaId]?.let { ready ->
            sendLoad(ready, startPos, play)
            return
        }
        val plan = planFor(current) ?: return
        if (plan is CastPlan.Refused) {
            Log.w(TAG, "refusing track: " + plan.reason)
            message(
                when (plan.reason) {
                    CastPlan.Reason.DRM -> R.string.cast_unsupported_drm
                    CastPlan.Reason.MANIFEST_WITH_HEADERS,
                    CastPlan.Reason.UNKNOWN_SOURCE -> R.string.cast_unsupported_stream
                }
            )
            endSession()
            return
        }
        // Before anything is claimed: a newer load supersedes whatever copy is running, and
        // cancelCopy releases that copy's pendingMediaId - so it runs first and this load's own
        // claims cannot be cleared by it.
        cancelCopy("superseded")
        pendingMediaId = current.mediaId
        val generation = ++loadGeneration
        prepare(current, plan, generation) { ready ->
            // Cleared BEFORE the staleness check, and only when it still names this load: a
            // discarded load must not leave its own id parked in pendingMediaId, or the guard
            // above would refuse to ever load that track again.
            if (pendingMediaId == current.mediaId) pendingMediaId = null
            if (generation != loadGeneration) {
                Log.d(
                    TAG, "discarding stale load gen=" + generation + " now=" + loadGeneration +
                        " mediaId=" + hash(current.mediaId)
                )
                // The finished file is unreferenced the moment this load is abandoned - it was
                // never put in [prepared] - so retainPrepared is what deletes it. A copy still
                // running is untouched: CastStreamStore.retain only sees COMPLETED files.
                if (ready != null) retainPrepared()
                return@prepare
            }
            if (ready == null) failAndStop(R.string.cast_stopped)
            else sendLoad(ready, startPos, play)
        }
    }

    /** Null when the item has no resolved server yet - not an error; its resolution re-enters here. */
    private fun planFor(item: MediaItem): CastPlan? {
        val server = state.servers[item.track.id]?.getOrNull() ?: return null
        val source = server.sources.firstOrNull()
            ?: return CastPlan.Refused(CastPlan.Reason.UNKNOWN_SOURCE)
        // ⚠️ serverIndex CAN BE THE DOWNLOAD PSEUDO-INDEX, WHICH IS EXACTLY servers.size.
        // selectServerIndex returns it to mean "play the downloaded file", so indexing track.servers
        // with it yields null - and that is correct here rather than a bug to route around, because
        // the Streamable is only the MIME ladder's LAST-RESORT label and the route is already known
        // from the source itself (a file:// url). selectServerIndex is deliberately untouched.
        val streamable = item.track.servers.getOrNull(item.serverIndex)
        val key = CastMediaItemBuilder.keyFor(item.track, item.extensionId)
        return CastPlan.planFor(source, streamable, key)
    }

    /**
     * Registers the plan, materialises if the plan needs it, and hands back a [Prepared] on the main
     * thread. [onReady] gets null when anything failed, having logged why.
     */
    private fun prepare(
        item: MediaItem,
        plan: CastPlan,
        generation: Int,
        onReady: (Prepared?) -> Unit,
    ) {
        val url = if (plan is CastPlan.Direct) plan.url else localServer.urlForPlan(plan)
        if (url == null) {
            Log.w(TAG, "no url for plan=" + plan.javaClass.simpleName)
            onReady(null)
            return
        }
        // ⚠⚠ NOT THE LAST PATH SEGMENT - THAT IS NOW track.<ext>. The server owns the
        // shape and reads its own token back; deriving it here by hand is what would silently
        // materialise under "track.flac" and then 404 when the real token was served. Direct
        // plans carry a foreign URL, where this value is only a map key and a log hash.
        val token = LocalMediaServer.tokenOf(url)
        if (plan !is CastPlan.Materialise) {
            val built = CastMediaItemBuilder.build(item.track, plan, url)
            onReady(built?.let { Prepared(item.mediaId, token, plan, it) })
            return
        }
        val source = state.servers[item.track.id]?.getOrNull()?.sources?.firstOrNull()
        val job = scope.launch(Dispatchers.IO) {
            // ⚠️ THE COPY POLLS THIS ONCE PER BUFFER - see the cancellation point in
            // CastStreamStore.materialise. Cancelling a coroutine cannot interrupt a blocking
            // read, so handing the flag in is the only thing that makes a cancel stop a download.
            val file = store.materialise(token, plan.key) { !isActive }
            withContext(Dispatchers.Main) {
                // Finished, so no longer cancellable: release the slot unless a newer copy has
                // already taken it.
                if (copy?.generation == generation) copy = null
                if (file == null) {
                    onReady(null)
                    return@withContext
                }
                // Now that bytes exist the container can be sniffed, which is the only REAL mime
                // signal a Raw source has - it carries no uri, no type and no extension. This
                // replaces the label guess with a fact before the receiver is told anything.
                val header = store.headerBytes(file)
                val finalPlan =
                    if (source == null) plan
                    else plan.copy(mime = CastPlan.mimeFor(source, null, header))
                val built = CastMediaItemBuilder.build(item.track, finalPlan, url)
                onReady(built?.let { Prepared(item.mediaId, token, finalPlan, it) })
            }
        }
        // After launch, deliberately: the body cannot reach its Main section until this function
        // returns, because that section is queued on the looper this is running on.
        copy = Copy(job, token, item.mediaId, generation)
    }

    private fun sendLoad(ready: Prepared, positionMs: Long, play: Boolean) {
        val castPlayer = remote ?: return
        // The start-position override belongs to the FIRST load only - see [castStartPositionMs].
        castStartPositionMs = null
        // Every send opens a new generation, which is what makes a copy that was started before
        // this - including by the synchronous [prepared] path, which has no generation of its
        // own - stale when it lands. See [loadGeneration].
        loadGeneration++
        // Reached from the synchronous [prepared] path too, which starts no copy of its own and
        // would otherwise leave an older one running. A no-op when this send came FROM a copy:
        // that one cleared the slot before calling back.
        cancelCopy("load sent")
        castedMediaId = ready.mediaId
        prepared[ready.mediaId] = ready
        lastLoadAtMs = System.currentTimeMillis()
        retainPrepared()
        // ⚠️ THESE ARE THE FIELDS DefaultMediaItemConverter ACTUALLY READS, so the
        // line says what the RECEIVER is told rather than what we intended: contentUrl comes from
        // localConfiguration.uri (:171) and contentType from localConfiguration.mimeType (:180),
        // media3-cast 1.11.0. Read off the built item for that reason, not off the plan.
        val config = ready.item.localConfiguration
        Log.d(
            TAG, "LOAD plan=" + ready.plan.javaClass.simpleName +
                " contentUrl=" + (config?.uri?.toString()?.let { loggableUrl(ready.plan, it) }
                    ?: "none") +
                " contentType=" + (config?.mimeType ?: "none") +
                " contentId=" + (
                    // What the converter will actually send, by the decoded branch quoted in
                    // CastMediaItemBuilder: the URL when the mediaId is the default. Printing the
                    // LOCAL id here (as this did until 2026-10-20) hid the whole defect - the line
                    // said contentId=<track> while the wire carried <track> for a different reason
                    // than anyone reading it assumed.
                    if (ready.item.mediaId == MediaItem.DEFAULT_MEDIA_ID) "sameAsUrl"
                    else hash(ready.item.mediaId)
                    ) + " localId=" + hash(ready.mediaId) +
                " pos=" + positionMs + " play=" + play
        )
        runCatching {
            castPlayer.setMediaItem(ready.item, positionMs.coerceAtLeast(0L))
            castPlayer.prepare()
            castPlayer.playWhenReady = play
        }
        armLoadTimeout()
        armInboundWatch(ready.plan)
    }

    /**
     * ⚠⚠ MAKES A MISSING INBOUND CONNECTION SAY SO, WHICH IS THE ONE THING THE
     * 1141 CAPTURE COULD ONLY SHOW BY ITS ABSENCE. Reading that capture meant noticing that an
     * "accept from" line was NOT there - and an absence is indistinguishable from a log that was
     * filtered, a server that never started, or a build without the line at all. This states it
     * positively a few seconds after each LOAD, with the three facts needed to act on it: whether
     * the permission is held, where we told the receiver to connect, and which plan it was.
     * ⚠️ NOT ARMED FOR [CastPlan.Direct], which is not a failure: the receiver
     * fetches the origin itself and is SUPPOSED to make no connection to us. Arming it there
     * would print a warning on every healthy Direct cast, which is how a probe becomes noise.
     * ⚠️ A COUNTER, NOT A FLAG, because a session serves many requests - the
     * question is whether anything arrived SINCE THIS LOAD, so two readings are compared. See
     * LocalMediaServer.accepted for why it resets per server rather than per process.
     * ⚠️ SHORTER THAN [LOAD_TIMEOUT_MS] ON PURPOSE: the point is to have the
     * diagnosis in the log BEFORE the timeout fires, so the capture reads cause then symptom.
     */
    private fun armInboundWatch(plan: CastPlan) {
        inboundWatch?.cancel()
        inboundWatch = null
        if (plan is CastPlan.Direct) return
        val before = localServer.acceptedCount()
        inboundWatch = scope.launch {
            delay(INBOUND_WATCH_MS)
            withContext(Dispatchers.Main) {
                if (localServer.acceptedCount() != before) return@withContext
                Log.w(
                    TAG, "no inbound connection " + INBOUND_WATCH_MS + "ms after LOAD" +
                        " (localNetwork=" + localNetworkState() +
                        " base=" + (localServer.baseUrl() ?: "none") +
                        " plan=" + plan.javaClass.simpleName + ")"
                )
            }
        }
    }

    /**
     * ⚠⚠ THE ONLY THING THAT CATCHES A LOAD THAT NEVER COMPLETES. A receiver can sit in
     * LOADING/BUFFERING indefinitely - media3 maps both to STATE_BUFFERING and reports nothing more -
     * which on the first build was an endless spinner with no message and no way to recover but to
     * disconnect by hand. Cancelled on the first STATE_READY; anything else means it never got there.
     */
    private fun armLoadTimeout() {
        loadTimeout?.cancel()
        loadTimeout = scope.launch {
            delay(LOAD_TIMEOUT_MS)
            withContext(Dispatchers.Main) {
                if (!CastState.isCasting) return@withContext
                Log.w(TAG, "load timeout after " + LOAD_TIMEOUT_MS + "ms - stopping")
                failAndStop(R.string.cast_stopped)
            }
        }
    }

    /**
     * Keeps what is on the receiver and drops everything else, on disk and in the map. One track at
     * a time is the whole design, and with no prefetch (see [advanceLocalQueue]) there is never a
     * second entry worth keeping.
     */
    private fun retainPrepared() {
        val keep = castedMediaId
        prepared.keys.toList().forEach { if (it != keep) prepared.remove(it) }
        store.retain(prepared.values.map { it.token }.toSet())
    }

    /**
     * Moves the LOCAL queue on, which is what makes state.current emit the next item and brings
     * [loadCurrentOnCast] round again to send the next LOAD.
     *
     * ⚠⚠ THERE IS DELIBERATELY NO PREFETCH HERE, AND THE GAP BETWEEN TRACKS IS
     * ACCEPTED. One was built and removed on 2026-10-18, because it could not work where it stood:
     * the next item is NOT RESOLVED while the local player is parked - a parked player never
     * advances, so it never preloads - so state.servers has no entry for it and the prefetch found
     * nothing to do on every normal transition.
     * ⚠️ MAKING IT REAL MEANS RESOLVING AHEAD OF THE QUEUE, WHICH IS NOT A LOCAL
     * CHANGE. A resolve writes state.servers and emits PlayerState.serverChanged, and that flow
     * feeds PlayerViewModel's quality sheet and PlayerEventListener's server-change path - both of
     * which assume the emission is about the CURRENT item. Emitting for a non-current one is
     * cross-talk, not a detail.
     * DECIDED: match the reference player (design reference only, nothing copied), which also
     * advances first and resolves after, and accept the gap. REVISIT ONLY IF THE GAP IS BAD ON
     * DEVICE - and then as a resolve-ahead design with the serverChanged question answered, not by
     * restoring the old call.
     */
    private fun advanceLocalQueue() {
        loadTimeout?.cancel()
        loadTimeout = null
        runCatching {
            if (localPlayer.hasNextMediaItem()) {
                Log.d(TAG, "advancing local queue")
                localPlayer.seekToNextMediaItem()
            } else {
                Log.d(TAG, "end of queue")
                remote?.playWhenReady = false
            }
        }
    }

    // ── Session end ─────────────────────────────────────────────────────────────────────────────

    /**
     * ⚠⚠ RETURNS TO LOCAL AT THE CAST POSITION, PAUSED, and the pause is the part worth stating.
     * Resuming on the phone the instant a TV session ends would start audio out of a device the user
     * has just stopped using, often in another room. So the position follows and the play state does
     * not.
     *
     * ⚠️ IDEMPOTENT, BECAUSE IT IS REACHED FROM SIX PLACES: session ended, suspended, start
     * failed, resume failed, our own failure paths, and unregister() at teardown.
     */
    private fun onCastEnded() {
        // ⚠⚠ BEFORE THE EARLY RETURN, DELIBERATELY, AND THAT ORDER IS THE WHOLE
        // FIX. While a local-network prompt is on screen nothing of ours has started - isCasting is
        // false and remote is null - so the guard below returns immediately and would leave the
        // waiting coroutine parked for its full timeout, ending a session that had already ended.
        // Completing the deferred here makes the wait exit at once; a second complete() on an
        // already-completed deferred is a no-op returning false, so racing the broker is safe.
        pendingPermission?.result?.complete(PermissionResult(false, PERMISSION_SESSION_ENDED))
        pendingPermission = null
        inboundWatch?.cancel()
        inboundWatch = null
        if (!CastState.isCasting && remote == null) return
        Log.d(TAG, "session ending")
        loadTimeout?.cancel()
        loadTimeout = null
        cancelCopy("session ended")
        val castPlayer = remote
        val position = castPlayer?.let {
            runCatching { it.currentPosition }.getOrDefault(C.TIME_UNSET)
        } ?: C.TIME_UNSET

        CastState.setCasting(false)
        setHiFiSuppressed(false)
        tracking()?.castSource = null
        castedMediaId = null
        pendingMediaId = null

        runCatching { mediaClient?.unregisterCallback(clientCallback) }
        mediaClient = null

        runCatching { session()?.player = localPlayer }
        wrapper?.detach()
        wrapper = null

        runCatching { castPlayer?.removeListener(remoteListener) }
        runCatching { castPlayer?.release() }
        remote = null

        prepared.clear()
        store.retain(emptySet())

        // ⚠⚠ AN UNUSABLE CAST POSITION MEANS KEEP THE LOCAL ONE, NOT SEEK TO ZERO.
        // RemoteCastPlayer reports 0 for an item that never left LOADING, which is exactly the
        // failed-load case this path is most often reached from. Nothing in a cast session moves
        // the parked local player, so its own position is still the one the user reached - seeking
        // to a cast 0 would be the only thing that threw it away.
        val localPosition = runCatching { localPlayer.currentPosition }.getOrDefault(C.TIME_UNSET)
        val resumeAt = if (position != C.TIME_UNSET && position > 0L) {
            runCatching { localPlayer.seekTo(position) }
            position
        } else localPosition
        // Explicitly NOT play(): see the note above. ShufflePlayer's refusal is already lifted by the
        // flag, so this is a choice rather than a consequence.
        runCatching { localPlayer.pause() }
        localServer.stop()
        Log.d(
            TAG, "session ended, local paused at " + resumeAt +
                " (cast=" + position + " local=" + localPosition + ")"
        )
    }

    /**
     * The other half of the mutual exclusion. AA cannot be refused - the system decides when
     * projection connects - so the Cast session gives way, which is also right physically: the user
     * has just got into a car.
     */
    fun onAndroidAutoConnected() {
        if (!CastState.isCasting) return
        Log.w(TAG, "Android Auto connected - stopping cast")
        message(R.string.cast_stopped_android_auto)
        endSession()
    }

    private fun failAndStop(resId: Int) {
        message(resId)
        endSession()
    }

    private fun endSession() {
        val context = CastAvailability.castContext(app.context)
        runCatching { context?.sessionManager?.endCurrentSession(true) }
        // endCurrentSession may not call back when no session was fully established, so unwind
        // whatever was set up - the flag must never stay stuck on.
        onCastEnded()
    }

    private fun message(resId: Int) {
        scope.launch { app.messageFlow.emit(Message(app.context.getString(resId))) }
    }

    /**
     * The denial message with a Settings action, for a permission the system will no longer
     * prompt for. Separate from [message] because a snackbar ACTION is not free: SnackBarHandler
     * dedupes by value and Message.action holds a lambda, which has no structural equality, so
     * every actionable message escapes the dedupe. One per refused session is the whole point
     * here, so that is acceptable - do not reach for this shape for anything repeating.
     */
    private fun messageWithSettings() {
        scope.launch {
            app.messageFlow.emit(
                Message(
                    app.context.getString(R.string.cast_needs_local_network),
                    Message.Action(app.context.getString(R.string.settings)) {
                        app.context.openAppSettings()
                    }
                )
            )
        }
    }

    /**
     * `notRequired` below Android 17, otherwise `granted` or `denied`. A string rather than a
     * Boolean because it is read straight into the "session starting" line, where "this device
     * never needed it" and "this device granted it" are different findings.
     *
     * ⚠️ THE CONSTANT AND THE GATE WERE BOTH VERIFIED AGAINST THE COMPILE SDK
     * (android-37.0): Manifest.permission.ACCESS_LOCAL_NETWORK is
     * "android.permission.ACCESS_LOCAL_NETWORK" and Build.VERSION_CODES.CINNAMON_BUN is 37, so
     * neither is a literal here.
     */
    private fun localNetworkState(): String = when {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.CINNAMON_BUN -> "notRequired"
        ContextCompat.checkSelfPermission(
            app.context, Manifest.permission.ACCESS_LOCAL_NETWORK
        ) == PackageManager.PERMISSION_GRANTED -> "granted"
        else -> LOCAL_NET_DENIED
    }

    /**
     * ⚠⚠ CANCELS A SUPERSEDED COPY, AND THE REASON IS CONCURRENCY RATHER THAN
     * TIDINESS. Abandoning the result is not enough: a copy is a full stream read, so three quick
     * Nexts would leave three complete downloads running at once - and Deezer's rate limiting and
     * its 403s under concurrent requests are an open concern here, not a theoretical one. The copy
     * deletes its own partial file on the way out and never reaches CastStreamStore's `ready`.
     *
     * ⚠️ IT ALSO RELEASES [pendingMediaId]. A cancelled job's callback never runs, so
     * the id parked there would otherwise stay and make that track unloadable for the rest of the
     * session.
     */
    private fun cancelCopy(reason: String) {
        val running = copy ?: return
        copy = null
        if (running.mediaId == pendingMediaId) pendingMediaId = null
        if (!running.job.isActive) return
        Log.d(TAG, "cancelling copy token=" + hash(running.token) + " (" + reason + ")")
        running.job.cancel()
    }

    private fun hash(value: String) = Integer.toHexString(value.hashCode())

    /**
     * The served URL as the receiver will see it, with the TOKEN replaced by its hash - the token
     * is the credential and is never printed. A [CastPlan.Direct] URL is an origin URL that can
     * carry a signature, so only its scheme and host survive.
     */
    private fun loggableUrl(plan: CastPlan, uri: String): String {
        if (plan is CastPlan.Direct) {
            val scheme = uri.substringBefore("://", "")
            val host = uri.substringAfter("://", "").substringBefore('/')
            val origin = if (scheme.isEmpty() || host.isEmpty()) "" else scheme + "://" + host
            return origin + "/#" + hash(uri)
        }
        val marker = "/stream/"
        val prefix = uri.substringBeforeLast(marker, "")
        if (prefix.isEmpty()) return "#" + hash(uri)
        val rest = uri.substringAfterLast(marker)
        val token = rest.substringBefore('/')
        return prefix + marker + "#" + hash(token) + rest.removePrefix(token)
    }

    private fun stateName(value: Int) = when (value) {
        Player.STATE_IDLE -> "idle"
        Player.STATE_BUFFERING -> "buffering"
        Player.STATE_READY -> "ready"
        Player.STATE_ENDED -> "ended"
        else -> "unknown(" + value + ")"
    }

    companion object {
        private const val TAG = CastAvailability.TAG

        /** The extension whose HiFi request is suppressed while casting. See setHiFiSuppressed. */
        private const val DEEZER_EXTENSION_ID = "deezer"

        /**
         * Above this, the stream is re-resolved before the first LOAD - see [reresolveIfHighRes].
         *
         * ⚠️ "ABOVE WHAT EVERY RECEIVER TAKES", NOT A MEASURED CLIFF. 44.1
         * and 48 kHz are universal; 88.2/96/176.4/192 are the HiFi tier, and the one measured
         * failure (Pink Floyd "Money", Amazon Fire TV, 2026-10-25) sits at 96. If a receiver ever
         * turns out to handle 96 cleanly this is the number to raise - do NOT make it per-device,
         * which would need a capability probe the Cast SDK does not offer.
         */
        private const val HIGH_RES_SAMPLE_RATE_HZ = 48_000

        /**
         * App-written key in that extension's own prefs, ANDed with the user's `flac_24` switch by
         * DeezerMedia.hiFiWanted. Deliberately NOT the user's key - see setHiFiSuppressed.
         */
        private const val HIFI_CAST_OFF_KEY = "flac_24_cast_off"

        /**
         * Matches CastStreamStore's stall window, so a stalled copy and a stalled receiver surface in
         * the same interval rather than one masking the other.
         */
        private const val LOAD_TIMEOUT_MS = 15_000L

        /**
         * How long after a LOAD we report that nothing dialled in - see [armInboundWatch]. Short
         * enough to land BEFORE [LOAD_TIMEOUT_MS] so the capture reads cause then symptom, long
         * enough that a receiver doing DNS, TLS to its own CDN and a first range request is not
         * reported as broken. The 1140/1141 captures show a healthy receiver connecting well inside
         * a second of the LOAD.
         */
        private const val INBOUND_WATCH_MS = 5_000L

        /**
         * ⚠⚠ GENEROUS BECAUSE NOTHING IS SPINNING YET, which is the opposite of
         * [LOAD_TIMEOUT_MS]'s problem. At this point no socket is bound and no LOAD has been sent;
         * the user is looking at a system permission dialog and may read it, switch apps, or check
         * their network. Cutting that short would refuse a session the user was in the middle of
         * allowing. The cases that would otherwise hang are handled by their own exits instead -
         * no collector (subscriptionCount), a cancelled dialog (the broker), and the session ending
         * ([onCastEnded]) - so this fires only if the collector dies mid-dialog.
         */
        private const val PERMISSION_TIMEOUT_MS = 120_000L

        /** [localNetworkState]'s refusing value, and the one the gate compares against. */
        private const val LOCAL_NET_DENIED = "denied"

        /**
         * The outcome [onCastEnded] completes a pending prompt with. Distinct from `denied` because
         * the user did not refuse anything - they disconnected - so it must NOT produce a message.
         */
        private const val PERMISSION_SESSION_ENDED = "sessionEnded"

        private const val TRANSIENT_ENDED_MS = 3_000L
    }
}
