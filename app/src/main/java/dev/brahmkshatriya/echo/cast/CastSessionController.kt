package dev.brahmkshatriya.echo.cast

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.cast.RemoteCastPlayer
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.common.models.Message
import dev.brahmkshatriya.echo.di.App
import dev.brahmkshatriya.echo.playback.MediaItemUtils.extensionId
import dev.brahmkshatriya.echo.playback.MediaItemUtils.serverIndex
import dev.brahmkshatriya.echo.playback.MediaItemUtils.track
import dev.brahmkshatriya.echo.playback.PlayerState
import dev.brahmkshatriya.echo.playback.listener.TrackingListener
import dev.brahmkshatriya.echo.playback.source.LocalMediaServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    private var remote: RemoteCastPlayer? = null
    private var wrapper: CastSwitchPlayer? = null
    private var mediaClient: RemoteMediaClient? = null

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
    }

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(castSession: CastSession, sessionId: String) = onCastStarted()
        override fun onSessionResumed(castSession: CastSession, wasSuspended: Boolean) =
            onCastStarted()

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

    private fun onCastStarted() {
        if (CastState.isCasting) return
        // AA and Cast are mutually exclusive. Refused here rather than by hiding the button, because
        // a session can be started from the TV or another phone - the button is not the only entry.
        if (isAndroidAutoConnected()) {
            Log.w(TAG, "refusing session: Android Auto connected")
            message(R.string.cast_refused_android_auto)
            endSession()
            return
        }
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
        Log.d(TAG, "session starting")
        remote = castPlayer
        castPlayer.addListener(remoteListener)
        mediaClient = CastAvailability.castContext(app.context)
            ?.sessionManager?.currentCastSession?.remoteMediaClient
        runCatching { mediaClient?.registerCallback(clientCallback) }

        // Flag first, so every gate - ShufflePlayer.play, the crossfade arm, the watchdog - is closed
        // before anything else moves.
        CastState.setCasting(true)
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

        prepared[current.mediaId]?.let { ready ->
            sendLoad(ready, positionMs, play)
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
            else sendLoad(ready, positionMs, play)
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
                " contentId=" + hash(ready.mediaId) + " pos=" + positionMs + " play=" + play
        )
        runCatching {
            castPlayer.setMediaItem(ready.item, positionMs.coerceAtLeast(0L))
            castPlayer.prepare()
            castPlayer.playWhenReady = play
        }
        armLoadTimeout()
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

        /**
         * Matches CastStreamStore's stall window, so a stalled copy and a stalled receiver surface in
         * the same interval rather than one masking the other.
         */
        private const val LOAD_TIMEOUT_MS = 15_000L

        private const val TRANSIENT_ENDED_MS = 3_000L
    }
}
