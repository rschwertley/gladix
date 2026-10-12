package dev.brahmkshatriya.echo.playback

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.PlayerMessage
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.playback.MediaItemUtils.context
import dev.brahmkshatriya.echo.playback.MediaItemUtils.track
import dev.brahmkshatriya.echo.playback.source.CacheOnlyTailSource
import kotlin.math.max

/**
 * True crossfade, BUILD 1: everything except the audible part.
 *
 * ⚠⚠ THIS BUILD NEVER GOES ACTIVE. The sidecar is created, armed, pre-seeked and gated, and then
 * deliberately stopped - it never calls play(), the main player is never muted, and no ramp exists.
 * Build 1 proves the plumbing, the lifecycle, the gate and the abort paths while being INAUDIBLE, so
 * Build 2 can change exactly one thing: whether the overlap happens.
 * ⚠️ WHICH MEANS BUILD 1 CANNOT VALIDATE TWO THINGS, and they must not be assumed from a clean
 * Build 1: a sidecar that never renders never creates a second AudioTrack, so neither the shared
 * audio-session behaviour (effects applying to both legs; the OEM session-effect-drop risk) nor the
 * ramp arithmetic is exercised here. Both land in Build 2.
 *
 * ⚠⚠ INERT AT SLIDER 0 IS A HARD RULE, ENFORCED IN THREE PLACES, NOT ONE:
 *   1. PlayerService only CONSTRUCTS this class (and the sidecar) when the slider is > 0, and
 *      releases both when it returns to 0. With the slider at 0 the object does not exist.
 *   2. Every hook in PlayerEventListener is `crossfade?.…` against a null reference.
 *   3. [schedule] returns on `fadeSeconds == 0` anyway, so a stale instance mid-teardown is inert.
 * Guard 3 exists because 1 and 2 do not order against each other: the prefs listener and the player
 * callbacks are both on the app looper, but an already-queued PlayerMessage is not.
 *
 * ⚠⚠ A PREPARED SIDECAR HOLDS A POSITION FROM PREPARE TIME, WHICH MAKES BUILD 2's
 * RE-SYNC MANDATORY RATHER THAN DEFENSIVE. The pre-seek happens whenever the tail becomes
 * readable - the moment the main player reports the current item buffered to its end - and that is
 * an unpredictable number of seconds before the hand-off, now that prepare is deferred off ARM.
 * The sidecar then sits paused at that position while the main player keeps advancing, so by ACTIVE
 * the two are offset by however long the wait was. Build 2 MUST re-sync the sidecar to the main
 * player's position when they differ by more than 200 ms; treating that as an optional polish would
 * produce an overlap of the wrong part of the track, which is a worse artefact than no crossfade.
 *
 * ⚠️ NATURAL END ONLY. ARM is reached from a position-based PlayerMessage or from a seek
 * re-evaluation - NEVER from a media-item transition, which always aborts instead. That is what makes
 * a manual Next/Previous incapable of crossfading: it transitions, which aborts, and the next item
 * arms from scratch. Do not "simplify" this by arming in onTransition.
 */
@OptIn(UnstableApi::class)
class CrossfadeController(
    private val exoPlayer: ExoPlayer,
    private val sessionPlayer: Player,
    private val sidecar: ExoPlayer,
    private val tail: CacheOnlyTailSource,
    private val isRestoring: () -> Boolean,
    private val skipFadeOnAlbums: () -> Boolean,
) {

    /** Slider value in seconds; 0 = off. Written on the app looper from PlayerService's prefs listener. */
    var fadeSeconds: Int = 0
        set(value) {
            field = value
            if (value == 0) abort(Abort.SETTING_OFF) else reschedule()
        }

    private enum class Phase { IDLE, ARMED }

    /** Closed set, so an abort line can never read as free text. */
    private enum class Abort {
        SETTING_OFF, TRACK_CHANGE, SEEK, USER_PAUSE, INTERRUPTION, STALL_HOLD, ERROR,
        QUEUE_EDIT, BUILD1_NO_ACTIVE,
    }

    private var phase = Phase.IDLE
    private var armMessage: PlayerMessage? = null
    private var gateMessage: PlayerMessage? = null
    private var armedMediaId: String? = null
    private var armedTailStartMs = 0L
    private var sidecarPrepared = false

    // ⚠⚠ A POLL, BECAUSE THERE IS NO CALLBACK FOR WHAT THIS WAITS ON. The condition is
    // "the CURRENT item is buffered to its end", and media3 raises no event for that: onIsLoadingChanged
    // fires for the LOADING period, which by then is the next item (see maybePrepareSidecar). The
    // reference implementation polls its whole trigger at 200ms; this polls only the prepare
    // precondition, only while ARMED and not yet prepared, and stops itself the moment either changes.
    // Worst case it runs for the ~30s arm window of one track and then never again.
    private val handler = Handler(Looper.getMainLooper())
    private val prepareTick = object : Runnable {
        override fun run() {
            maybePrepareSidecar()
            if (phase == Phase.ARMED && !sidecarPrepared) handler.postDelayed(this, PREPARE_POLL_MS)
        }
    }

    // ── Hooks. All called on the app looper from PlayerEventListener. ────────────────────────────

    fun onTransition() {
        abort(Abort.TRACK_CHANGE)
        reschedule()
    }

    /**
     * A seek re-evaluates from scratch, and that is the whole fix for the case a position message
     * cannot see: a seek that lands INSIDE or PAST the arm point never crosses the message's
     * position, so the message never fires. [schedule] handles it by arming immediately when the
     * current position is already at or past armAt.
     */
    fun onSeek() {
        abort(Abort.SEEK)
        reschedule()
    }

    fun onUserPause() = abort(Abort.USER_PAUSE)

    /**
     * A transient focus loss / notification duck. Build 1 aborts; Build 2 will pause and resume the
     * sidecar alongside the main player instead, because an interruption must not cost the tail.
     */
    fun onInterruptionPause() = abort(Abort.INTERRUPTION)

    /**
     * The Oct 3 hold-instead-of-skip path. NOT a user pause: it is app-initiated, so media3 reports
     * it as a user request and the reason code cannot tell the two apart - which is why
     * PlayerEventListener tells us explicitly rather than us inferring from playWhenReady.
     */
    fun onStallHold() = abort(Abort.STALL_HOLD)

    fun onPlayerError() = abort(Abort.ERROR)

    fun onQueueEdit() = abort(Abort.QUEUE_EDIT)

    /**
     * Kept as a SECONDARY trigger only. It fires for the loading period, which after the current
     * item finishes is the NEXT item - so on a queue it arrives too late to be the primary signal,
     * and [prepareTick] is what actually catches the moment. It is still worth having: on a
     * single-item queue (no next period to take the loading slot) this is the exact event.
     */
    fun onLoadingChanged() = maybePrepareSidecar()

    fun onSkipSilenceChanged(enabled: Boolean) {
        sidecar.skipSilenceEnabled = enabled
    }

    fun release() {
        abort(Abort.SETTING_OFF)
        runCatching { sidecar.release() }
    }

    // ── Scheduling ──────────────────────────────────────────────────────────────────────────────

    private fun reschedule() {
        cancelMessages()
        schedule()
    }

    private fun schedule() {
        if (fadeSeconds == 0) return                                   // inert guard 3
        val refusal = armRefusal()
        if (refusal != null) {
            probeArm(false, refusal)
            return
        }
        val fadeMs = fadeSeconds * 1000L
        val duration = exoPlayer.duration
        // Lead scaled by speed for the same reason the old fade trigger scaled its own offset:
        // setPosition takes MEDIA time, while "25 seconds of warning" is wall-clock intent.
        val speed = exoPlayer.playbackParameters.speed.coerceAtLeast(0.1f)
        val leadMs = (max(ARM_LEAD_BASE_MS + fadeMs, ARM_LEAD_FLOOR_MS) * speed).toLong()
        val armAt = duration - leadMs
        if (exoPlayer.currentPosition >= armAt) arm()                  // seek-into-window
        else {
            armMessage = exoPlayer
                .createMessage { _, _ -> arm() }
                // ⚠⚠ setLooper IS NOT OPTIONAL, AND IT IS A DELIBERATE DIVERGENCE FROM
                // EffectsListener.scheduleFadeOut, WHICH CORRECTLY OMITS IT. A PlayerMessage defaults
                // to the PLAYBACK looper, and ExoPlayerImplInternal.sendMessageToTarget
                // (media3 1.11.0, ExoPlayerImplInternal.java:2227-2238) delivers in-line on that
                // thread when message.getLooper() == playbackLooper. Track Fade can live with that
                // because its target only writes AudioEffectsProcessor.onFadeOutStart's two atomics;
                // ours calls arm(), whose first statement is exoPlayer.currentMediaItem. Every getter
                // on ExoPlayerImpl runs verifyApplicationThread, which THROWS (throwsWhenUsingWrongThread
                // is set true in its constructor and only a package-private setter can clear it), the
                // IllegalStateException escapes deliverMessage into
                // ExoPlayerImplInternal.handleMessage's catch(RuntimeException) and becomes
                // ERROR_CODE_FAILED_RUNTIME_CHECK plus stopInternal(forceResetRenderers = true). Observed
                // twice on the 2026-10-11 build, each time as a playback error that restarted the track.
                // ⚠️ AND NOT A handler.post { arm() } INSTEAD: media3's own hop
                // (sendMessageToTargetThread, ExoPlayerImplInternal.java:2240-2258) posts a runnable that
                // calls deliverMessage, which re-checks message.isCanceled() at :2261 INSIDE the post. So
                // an abort() landing between the hop and the run is honoured. Our own post would not be,
                // and cancelMessages() would silently fail to stop an arm already in flight.
                .setLooper(exoPlayer.applicationLooper)
                .setPosition(armAt)
                .send()
            probeArm(true, "posted", armAt)
        }
    }

    /**
     * The duration-became-known re-entry, and the ONLY trigger that may run while something is
     * already scheduled - so it is the one that must neither cancel nor double-post.
     *
     * ⚠⚠ WHY IT IS NOT reschedule(): that cancels first, and [armRefusal] then returns
     * "alreadyArmed" for the same item - which would leave phase ARMED with both messages gone and
     * [prepareTick] re-posting itself forever on a window that can no longer fire. The IDLE check
     * below also stops a second armMessage being posted over a live one, which would orphan the
     * first (the field is overwritten, the pending message still fires) and arm twice.
     *
     * ⚠️ WHY THIS HOOK HAS TO EXIST AT ALL. [armRefusal] reads exoPlayer.duration, which
     * is the current window of playbackInfo.timeline; for a progressive source whose extractor has
     * no duration and whose length is unknown (MP3 with no Xing/VBRI header is the recorded case -
     * see PlayerEventListener's lastReadyDurationKnown note), it is C.TIME_UNSET until
     * ProgressiveMediaPeriod back-fills it from the largest queued timestamp in onLoadCompleted
     * (media3 1.11.0, ProgressiveMediaPeriod.java:799-806) and refreshes the timeline. Before this
     * hook, schedule() ran only on transition, seek and slider, and a 2026-10-11 capture caught the
     * consequence exactly: 15 arm lines, 15 x reason=noDuration, dur=-9223372036854775807, two of
     * them three minutes into a track.
     * ⚠️ STATE_READY IS DELIBERATELY NOT ALSO HOOKED. Both ways the duration can arrive
     * end in a timeline refresh - setSeekMap at prepare goes through maybeFinishPrepare's
     * onSourceInfoRefreshed, the late case through onLoadCompleted's - so SOURCE_UPDATE carries
     * every one of them. READY would add buffering-cycle noise and no new information.
     */
    fun onSourceInfoRefreshed() {
        if (phase != Phase.IDLE || armMessage != null) return
        schedule()
    }

    /** Null when nothing refuses; otherwise the closed-set reason for the ARM probe line. */
    private fun armRefusal(): String? {
        if (isRestoring()) return "restore"
        val item = exoPlayer.currentMediaItem ?: return "noItem"
        if (armedMediaId == item.mediaId && phase == Phase.ARMED) return "alreadyArmed"
        if (exoPlayer.repeatMode == Player.REPEAT_MODE_ONE) return "repeatOne"
        val duration = exoPlayer.duration
        if (duration == C.TIME_UNSET) return "noDuration"
        // The reference rule, and NOT the same number as MIN_REMAINING_MS: this one asks whether the
        // TRACK is long enough to be worth fading at all, the other asks whether enough of THIS
        // playthrough is left at ARM. A 20s interlude should never crossfade however it is reached.
        if (duration <= fadeSeconds * 1000L + MIN_TRACK_HEADROOM_MS) return "tooShortTrack"
        if (!sessionPlayer.hasNextMediaItem()) return "noNext"
        val next = runCatching {
            sessionPlayer.getMediaItemAt(sessionPlayer.nextMediaItemIndex)
        }.getOrNull() ?: return "noNext"
        if (isIntraAlbum(item, next)) return "album"
        return null
    }

    /**
     * Suppress only across a REAL intra-album boundary, which is not the same question as "did this
     * item come from an album".
     *
     * ⚠⚠ THE OLD TEST WAS `mediaItem.context is Album` ON THE ARRIVING ITEM ALONE, and it
     * suppressed three cases that are not album playback at all: tracks added to an arbitrary queue
     * from an album page (PlayerCallback.addToQueue's `addedContext = item.takeUnless { it is Track }`
     * stamps every one of them with that Album), the last album track crossing into radio or
     * continuous play, and any single inherited-context track played alone. Comparing BOTH sides is
     * what fixes all three.
     * ⚠⚠ SHUFFLE ON MEANS NO SUPPRESSION AT ALL, and this is the case the old test got most
     * wrong for anyone who listens shuffled: under shuffle, queue adjacency carries no album meaning,
     * so two album tracks landing next to each other are exactly the pair that SHOULD crossfade.
     * ⚠️ ORDER NUMBERS ARE ONLY SOMETIMES THERE, which is why there are two unshuffled branches.
     * Track.albumOrderNumber / albumDiscNumber are nullable and populated by Deezer (DeezerParser's
     * TRACK_NUMBER / DISK_NUMBER) and by anything Unified wraps from it; nothing in OfflineExtension
     * sets them, and third-party extensions are unknown. They DO survive slimming, because toSlim is
     * a data-class copy that only rewrites the fields it names. When both are present, demand the
     * same disc and the next track number. When either is absent, fall back to
     * same-album-and-queue-adjacent, which is what unshuffled album playback produces anyway.
     */
    private fun isIntraAlbum(departing: MediaItem, arriving: MediaItem): Boolean {
        if (!skipFadeOnAlbums()) return false
        val from = departing.context as? Album ?: return false
        val to = arriving.context as? Album ?: return false
        if (from.id != to.id) return false
        if (sessionPlayer.shuffleModeEnabled) return false
        val fromTrack = departing.track
        val toTrack = arriving.track
        val fromOrder = fromTrack.albumOrderNumber
        val toOrder = toTrack.albumOrderNumber
        return if (fromOrder != null && toOrder != null)
            fromTrack.albumDiscNumber == toTrack.albumDiscNumber && toOrder == fromOrder + 1
        else true
    }

    private fun arm() {
        val item = exoPlayer.currentMediaItem ?: return
        val duration = exoPlayer.duration
        if (duration == C.TIME_UNSET) return
        val remaining = duration - exoPlayer.currentPosition
        if (remaining < MIN_REMAINING_MS) {
            probeArm(false, "tooShort")
            return
        }
        // effectiveFade: a seek into the last seconds must not ask for more tail than exists.
        val fadeMs = (fadeSeconds * 1000L).coerceAtMost(remaining)
        phase = Phase.ARMED
        armedMediaId = item.mediaId
        armedTailStartMs = duration - fadeMs
        probeArm(true, "armed")
        // ⚠⚠ PREPARED ONLY ONCE THE MAIN PLAYER HAS FINISHED LOADING, WHICH IS NOT THE SAME
        // MOMENT AS ARM. ARM is ~30s from the end; the cache-only source can only succeed once the
        // tail is COMMITTED, and the final fragment commits when the main player's loadable completes
        // (ProgressiveMediaPeriod.ExtractingLoadable.load's finally -> CacheDataSink.close ->
        // commitFile), which on a slow link is later than ARM. Preparing too early means the
        // cache-only chain opens a hole, gets PlaceholderDataSource's IOException, burns media3's
        // three load retries and lands in IDLE - with no listener to notice and no later retry.
        // ⚠️ SO THE TRIGGER IS bufferedPosition == duration, CHECKED HERE AND AGAIN ON EVERY
        // onIsLoadingChanged. The reference implementation never needed this because its sidecar
        // streams from the network; ours reads committed cache bytes, so it has a precondition the
        // reference does not.
        maybePrepareSidecar()
        if (!sidecarPrepared) handler.postDelayed(prepareTick, PREPARE_POLL_MS)
        // Where ACTIVE would be. Build 1 evaluates the gate here, logs it, and stops.
        gateMessage = exoPlayer
            .createMessage { _, _ -> evaluateGate(item) }
            // Same reason as the arm message in schedule() - read its note. evaluateGate touches
            // exoPlayer.duration, bufferedPosition, sidecar.playbackState and then abort(), so it is
            // the same wrong-thread defect one message later.
            .setLooper(exoPlayer.applicationLooper)
            .setPosition(duration - fadeMs)
            .send()
    }

    /**
     * Called at ARM and from [onLoadingChanged]. Prepares the sidecar the first time the main
     * player reports the current item fully loaded, and is a no-op otherwise - so a slow link simply
     * prepares later, and a link too slow to finish before the gate fires prepares not at all and
     * fails the gate on sidecarReady. No retry loop and no timer: the only thing that can change the
     * answer is the loader finishing, and that has its own callback.
     */
    private fun maybePrepareSidecar() {
        if (phase != Phase.ARMED || sidecarPrepared) return
        val item = exoPlayer.currentMediaItem ?: return
        if (item.mediaId != armedMediaId) return
        val duration = exoPlayer.duration
        if (duration == C.TIME_UNSET) return
        // ⚠⚠ NO isLoading TERM, AND ITS ABSENCE IS LOAD-BEARING. isLoading tracks the
        // LOADING period, not the playing one (ExoPlayerImplInternal.updateIsLoading:3574-3581 reads
        // queue.getLoadingPeriod()), and MediaPeriodQueue.shouldLoadNextMediaPeriod:209-215 hands that
        // slot to the NEXT item the instant the current one is fully buffered. Since ARM requires a
        // next item, `!isLoading` is false almost exactly when this needs to be true - it would have
        // made this method a permanent no-op, and the symptom (every gate failing on sidecarReady)
        // would have pointed at the cache instead of at this line.
        // bufferedPosition >= duration is sufficient on its own: the current item can only report
        // buffered-to-the-end after its extractor hit end-of-input, which is what closes the
        // DataSource and commits the final cache fragment. getCachedBytes remains the authority.
        if (exoPlayer.bufferedPosition < duration) return
        val ok = runCatching {
            sidecar.setMediaSource(tail.factory.createMediaSource(item))
            sidecar.prepare()
            sidecar.seekTo(armedTailStartMs)
        }.isSuccess
        sidecarPrepared = ok
        if (ok) handler.removeCallbacks(prepareTick)
        else probeArm(false, "sidecarSourceFailed")
    }

    private fun evaluateGate(item: MediaItem) {
        val duration = exoPlayer.duration
        val buffered = exoPlayer.bufferedPosition
        // Same reason as maybePrepareSidecar: no isLoading term. It is still PRINTED on the probe
        // line, because "was the player still loading something" is useful context - but it is an
        // observation, never a condition.
        val playerReady = duration != C.TIME_UNSET && buffered >= duration
        val gate = tail.gate(item)
        val sidecarReady = sidecar.playbackState == Player.STATE_READY
        probeGate(playerReady, sidecarReady, buffered, duration, gate)
        // BUILD 1: never ACTIVE, whatever the verdict.
        abort(Abort.BUILD1_NO_ACTIVE)
    }

    private fun abort(reason: Abort) {
        cancelMessages()
        if (phase == Phase.IDLE) return
        phase = Phase.IDLE
        armedMediaId = null
        sidecarPrepared = false
        // Reused, never released - releasing per crossfade would re-enter the June "zombie ExoPlayer"
        // territory and, on a shared audio session, drop session effects on some OEM ROMs.
        runCatching {
            sidecar.stop()
            sidecar.clearMediaItems()
        }
        probeAbort(reason)
    }

    private fun cancelMessages() {
        handler.removeCallbacks(prepareTick)
        runCatching { armMessage?.cancel() }
        runCatching { gateMessage?.cancel() }
        armMessage = null
        gateMessage = null
    }

    // ── PROBE (2026-10-11), APPROVED FOR ONE TEST SESSION ───────────────────────────────────────
    // ⚠️ REMOVE ALL THREE LINES IN ONE COMMIT once a capture shows an ARM verdict on every
    // eligible track and a gate pass rate matching prediction on both Wi-Fi and mobile. They are
    // grep-able as "CROSSFADE-PROBE" and are the only logging this build adds.
    // Each prints UNCONDITIONALLY on its path, so silence can only mean the path never ran - a
    // refusal-only line would make an empty capture undecidable between "nothing refused" and
    // "nothing ran".
    // ⚠️ [CORRECTED 2026-10-11] THAT WAS FALSE OF THE ARM LINE ON THE DAY IT WAS
    // WRITTEN, AND IT IS THE EXACT FAILURE IT CLAIMS TO PREVENT. schedule() printed only on
    // REFUSAL; its success path printed nothing, and the confirming armed=yes lived one hop and up
    // to 30s later inside arm() - which, being delivered on the playback thread, died on its first
    // statement before reaching it. So the first capture read as "arm never ran" when schedule()
    // had in fact succeeded twice, and those two successes were the two wrong-thread crashes. Fixed
    // by printing reason=posted (with armAt) AT the post site; arm() keeps reason=armed for actually
    // entering the window. A posted line with no armed line after it is now a readable finding
    // rather than silence.
    // Labels come from closed sets decided by measured facts, never by the
    // hypothesis under test, and the gate line carries BOTH of its numbers so its two failure
    // modes (player not finished loading vs bytes not committed) can never be confused.
    private fun probeArm(armed: Boolean, reason: String, armAt: Long? = null) = Log.d(
        TAG,
        "CROSSFADE-PROBE arm armed=" + (if (armed) "yes" else "no") + " reason=" + reason +
            (if (armAt == null) "" else " armAt=" + armAt) +
            " fade=" + fadeSeconds + "s id=" + exoPlayer.currentMediaItem?.mediaId +
            " dur=" + exoPlayer.duration + " pos=" + exoPlayer.currentPosition +
            " shuffle=" + sessionPlayer.shuffleModeEnabled + " repeat=" + exoPlayer.repeatMode
    )

    private fun probeGate(
        playerReady: Boolean,
        sidecarReady: Boolean,
        buffered: Long,
        duration: Long,
        gate: CacheOnlyTailSource.Gate,
    ) = Log.d(
        TAG,
        "CROSSFADE-PROBE gate gate=" + (if (playerReady && gate.ready && sidecarReady) "pass" else "fail") +
            " bufferedEqDuration=" + playerReady + " buffered=" + buffered + " dur=" + duration +
            " loading=" + exoPlayer.isLoading + " sidecarReady=" + sidecarReady +
            " cachedBytes=" + gate.cachedBytes + " contentLength=" + gate.contentLength +
            " cacheReason=" + gate.reason
    )

    private fun probeAbort(reason: Abort) = Log.d(
        TAG, "CROSSFADE-PROBE abort reason=" + reason + " sidecarState=" + sidecar.playbackState
    )

    companion object {
        private const val TAG = "GladixPlayback"

        /** Thresholds carried from the reference implementation; see crossfade-status.md. */
        private const val ARM_LEAD_BASE_MS = 25_000L
        private const val ARM_LEAD_FLOOR_MS = 30_000L
        private const val MIN_REMAINING_MS = 1_500L
        private const val MIN_TRACK_HEADROOM_MS = 5_000L
        private const val PREPARE_POLL_MS = 300L

        /** Slider bounds. 0 = off, and 0 is the default. */
        const val FADE_SECONDS_MIN = 0
        const val FADE_SECONDS_MAX = 10
    }
}
