package dev.brahmkshatriya.echo.playback.listener

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.ParserException
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.common.clients.LikeClient
import dev.brahmkshatriya.echo.common.models.Message
import dev.brahmkshatriya.echo.extensions.ExtensionLoader
import dev.brahmkshatriya.echo.extensions.exceptions.ExtensionNotFoundException
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.getExtension
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.isClient
import dev.brahmkshatriya.echo.playback.MediaItemUtils
import dev.brahmkshatriya.echo.playback.MediaItemUtils.extensionId
import dev.brahmkshatriya.echo.playback.MediaItemUtils.isLoaded
import dev.brahmkshatriya.echo.playback.MediaItemUtils.retries
import dev.brahmkshatriya.echo.playback.PlayerCommands.getLikeButton
import dev.brahmkshatriya.echo.playback.PlayerCommands.getRepeatButton
import dev.brahmkshatriya.echo.playback.PlayerCommands.getShuffleButton
import dev.brahmkshatriya.echo.playback.PlayerState
import dev.brahmkshatriya.echo.playback.ResumptionUtils
import dev.brahmkshatriya.echo.playback.ShufflePlayer
import dev.brahmkshatriya.echo.playback.exceptions.PlayerException
import dev.brahmkshatriya.echo.playback.exceptions.TrackUnavailableException
import dev.brahmkshatriya.echo.ui.common.ErrorCategory
import dev.brahmkshatriya.echo.ui.common.classify
import dev.brahmkshatriya.echo.utils.HealthMonitor
import dev.brahmkshatriya.echo.utils.Serializer.rootCause
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException
import kotlin.reflect.KClass

@OptIn(UnstableApi::class)
class PlayerEventListener(
    private val context: Context,
    private val scope: CoroutineScope,
    private val session: MediaLibrarySession,
    private val currentFlow: MutableStateFlow<PlayerState.Current?>,
    private val extensions: ExtensionLoader,
    private val throwableFlow: MutableSharedFlow<Throwable>,
    private val fullQueueFlow: MutableStateFlow<List<MediaItem>>,
    private val isAndroidAutoConnected: () -> Boolean = { false },
    private val requestAudioFocus: () -> Unit = {},
    // Live PlayerState.activeLoadCount (>0 ⇒ a stream resolution is in flight). Wired from
    // PlayerService where PlayerState is in scope; this listener is not given PlayerState directly.
    private val activeLoadCount: () -> Int = { 0 },
    // Invoked when the timeline becomes non-empty (a queue was applied, from any source) — the success
    // clear for PlayerState.resumptionApplying. Fires on the app looper (Main), preserving that invariant.
    private val onQueueApplied: () -> Unit = {},
    // Returns-and-clears PlayerState.pendingRestoreSeek (the cold-start re-seek latch). Wired from
    // PlayerService like onQueueApplied; this listener is not given PlayerState directly. Returns null once
    // consumed, so it fires at most once per cold restore.
    private val consumeRestoreSeek: () -> Pair<String, Long>? = { null },
    // Non-consuming PEEK at PlayerState.pendingRestoreSeek — true iff the cold-start re-seek latch is armed.
    // Never clears it (unlike consumeRestoreSeek), so it can gate the saveCurrentPos 0-write below WITHOUT
    // stealing the latch the STATE_READY re-seek depends on. Wired from PlayerService like the others; fires
    // on the app looper (Main). A latch is armed only when the restored position was > 0, so "armed" means
    // "we restored to a known non-zero position that the placeholder timeline hasn't resolved yet" — exactly
    // the window in which a currentPosition of 0 is spurious and must never overwrite the good saved value.
    private val isRestoreSeekArmed: () -> Boolean = { false },
    private val healthMonitor: HealthMonitor? = null,
) : Player.Listener {

    private val player get() = session.player

    // True only while an INTERNAL seek is in flight — a buffering-watchdog re-prepare OR an onPlayerError
    // retry (both stop→seek→prepare the current track). onPositionDiscontinuity's
    // latch-disarm reads it to tell such a seek — which Media3 delivers as
    // DISCONTINUITY_REASON_SEEK, indistinguishable by reason from a user seek (ExoPlayerImpl.seekTo sets
    // it unconditionally) — from a real user seek, so the watchdog does NOT steal the cold-start re-seek
    // latch. Plain var (not Atomic): every touch is on the app looper (Main). The watchdog body runs in
    // withContext(Dispatchers.Main), and the seek's discontinuity is delivered SYNCHRONOUSLY on that same
    // thread inside player.seekTo (updatePlaybackInfo → ListenerSet.flushEvents runs events inline), so the
    // set/clear reliably brackets the callback — the same single-thread invariant as resumptionApplying.
    private var internalSeekInFlight = false

    // Brackets an internal seek (buffering-watchdog re-prepare or onPlayerError retry) with
    // internalSeekInFlight so the SEEK discontinuity it triggers is not mistaken for a user seek. try/finally
    // so an unexpected throw can never strand the flag set.
    private inline fun internalSeek(block: () -> Unit) {
        internalSeekInFlight = true
        try { block() } finally { internalSeekInFlight = false }
    }

    // Every skip in this listener is an INVOLUNTARY auto-skip (a failed/stuck current track). Route
    // them through here so ShufflePlayer removes the departing track WITHOUT pushing it to the play-
    // history back-stack (Seam 3) — Previous must never land back on a dead track that would re-fail.
    private fun skipInvoluntarily() {
        (player as? ShufflePlayer)?.suppressPushOnNextAdvance = true
        player.seekToNextMediaItem()
    }

    private var pendingFullQueueUpdate: Job? = null
    private fun emitFullQueue() {
        pendingFullQueueUpdate?.cancel()
        pendingFullQueueUpdate = scope.launch(Dispatchers.Main) {
            delay(50)
            fullQueueFlow.value = (0 until player.mediaItemCount).map { player.getMediaItemAt(it) }
        }
    }

    // remove-on-advance fires onTimelineChanged (→ this listener) on EVERY advance, so an un-debounced
    // saveQueue would launch a fresh IO coroutine per track change — under rapid Next-mashing that's an
    // IO storm plus a read-snapshot-then-write race that can persist a stale index/queue (the cold-
    // start-wrong-track class). Debounce so a burst of advances coalesces into one save after it
    // settles. saveIndex (fired synchronously on each transition) keeps the index fresh meanwhile, and
    // recoverPlaylist's index coerce bounds any crash-in-window gap. isRearranging re-checked at fire.
    private var pendingSaveQueue: Job? = null
    private fun scheduleSaveQueue() {
        pendingSaveQueue?.cancel()
        pendingSaveQueue = scope.launch {
            delay(300)
            if ((session.player as? ShufflePlayer)?.isRearranging == true) return@launch
            ResumptionUtils.saveQueue(context, player)
        }
    }

    private fun updateCustomLayout() = scope.launch(Dispatchers.Main) {
        val item = player.currentMediaItem ?: return@launch
        val supportsLike = withContext(Dispatchers.IO) {
            extensions.music.getExtension(item.extensionId)?.isClient<LikeClient>() ?: false
        }
        val commandButtons = listOfNotNull(
            getShuffleButton(context, player.shuffleModeEnabled),
            getRepeatButton(context, player.repeatMode),
            getLikeButton(context, item).takeIf { supportsLike }
        )
        session.setCustomLayout(commandButtons)
    }

    private fun updateCurrentFlow() {
        val item = player.currentMediaItem
        if (item != null) {
            val isPlaying = player.isPlaying && player.playbackState == Player.STATE_READY
            currentFlow.value = PlayerState.Current(
                player.currentMediaItemIndex, item, item.isLoaded, isPlaying, false
            )
        } else if (player.mediaItemCount == 0 && currentFlow.value?.isPlaceholder == true) {
            // Keep the placeholder until we have real items or decide to clear
        } else {
            currentFlow.value = null
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (mediaItem == null) return  // fired on player.release() with index=0; don't overwrite saved position
        updateCustomLayout()
        // Persist the current index so cold-start restore seeks to the correct track. mediaItem is the
        // new current item.
        val fullIndex = player.currentMediaItemIndex
        ResumptionUtils.saveIndex(context, fullIndex, mediaItem.mediaId)
        session.notifyChildrenChanged("recent", 1, null)
        retriedMediaId = null
        retriedWatchdogCount = 0
        // A fresh queue (replace / cold-restore) moves the current item with this reason; queue EDITS that
        // leave the current item in place (radio top-up append, etc.) and our own skips (SEEK) do not. So
        // this marks "a new queue that hasn't played anything yet", arming the removed-extension exhaustion
        // message for the next all-dead run.
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
            resolvedSinceQueueReplace = false
            // Re-arm the once-per-episode 5xx snackbar too: a new queue is a fresh context, so if the user
            // swaps queues mid-CDN-outage the new queue's server errors should notify again. (serverErrorNotified
            // otherwise only re-arms on a successful STATE_READY, i.e. the CDN recovering.)
            serverErrorNotified = false
        }
    }

    override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
        updateCustomLayout()
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        emitFullQueue()
        if (timeline.windowCount > 0) onQueueApplied()
        if ((session.player as? ShufflePlayer)?.isRearranging != true) {
            scheduleSaveQueue()
            if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {
                bufferingWatchdog?.cancel()
                bufferingWatchdog = null
                if (player.playbackState == Player.STATE_BUFFERING && player.playWhenReady) {
                    armBufferingWatchdog()
                }
            }
        }
        if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {
            retriedMediaId = null
            retriedWatchdogCount = 0
        }
        if (!timeline.isEmpty() && reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED
            && player.playlistMetadata.title.isNullOrEmpty()
        ) {
            player.setPlaylistMetadata(
                MediaMetadata.Builder().setTitle(context.getString(R.string.queue)).build()
            )
        }
    }

    override fun onRepeatModeChanged(repeatMode: Int) {
        updateCustomLayout()
        ResumptionUtils.saveRepeat(context, repeatMode)
        emitFullQueue()
    }

    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
        updateCustomLayout()
        ResumptionUtils.saveShuffle(context, shuffleModeEnabled)
        scope.launch { ResumptionUtils.saveQueue(context, player) }
        emitFullQueue()
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        Log.d("GladixPlayback", "onPlaybackStateChanged: state=$playbackState")
        if (playbackState == Player.STATE_BUFFERING) {
            armBufferingWatchdog()
        } else {
            bufferingWatchdog?.cancel()
            bufferingWatchdog = null
        }
        if (playbackState == Player.STATE_READY) {
            resetConsecutiveSkips()
            // A track resolved successfully — the queue is not all-dead (removed-extension tracks never reach
            // READY). Suppresses the removed-extension exhaustion message for any queue that played anything.
            resolvedSinceQueueReplace = true
            // A track resolved, so any prior run of 5xx server errors has ended — re-arm the one-per-run
            // server-error snackbar for the next run.
            serverErrorNotified = false
            retried404MediaId = null
            retriedSocketMediaId = null
            retriedNetworkMediaId = null
            // Cold-start re-seek: the saved position was lost when prepare() resolved the deferred source's
            // placeholder->real timeline to the default (0). The real timeline now exists (STATE_READY), so a
            // seek sticks. Position-only on the current window (no index form — sidesteps ShufflePlayer's
            // windowed-index seeks). Guarded so it fires exactly once and loses to a user action: mediaId must
            // still be the restored track (not one the user tapped mid-buffer), and currentPosition must still
            // be at the start (a user seek before this READY moves it past the belt and we leave it alone).
            consumeRestoreSeek()?.let { (id, pos) ->
                if (player.currentMediaItem?.mediaId == id
                    && player.currentPosition < RESTORE_SEEK_BELT_MS
                ) player.seekTo(pos)
            }
        }
    }

    private fun armBufferingWatchdog() {
        Log.d("GladixPlayback", "STATE_BUFFERING: ${player.currentMediaItem?.mediaId} \"${player.currentMediaItem?.mediaMetadata?.title}\"")
        // Start (or keep) the cold-resolution grace timer for the current item.
        val coldMediaId = player.currentMediaItem?.mediaId
        if (coldMediaId != coldBufferingMediaId) {
            coldBufferingMediaId = coldMediaId
            coldBufferingStart = System.currentTimeMillis()
        }
        bufferingWatchdog?.cancel()
        bufferingWatchdog = scope.launch {
            delay(BUFFERING_WATCHDOG_MS)
            withContext(Dispatchers.Main) {
                if (player.playbackState != Player.STATE_BUFFERING) return@withContext
                // COLD-START SUPPRESSION: a first-time stream resolution is actively in flight
                // (current item not loaded AND a load running) and we're still inside the grace
                // window → the buffering is expected, not stuck. Re-arm and wait WITHOUT touching the
                // player: stop()+re-prepare() would cancel the running loadJob and restart the
                // resolution clock, skipping valid-but-slow cold tracks (the AA cold-connect bug).
                if (player.currentMediaItem?.isLoaded == false
                    && activeLoadCount() > 0
                    && System.currentTimeMillis() - coldBufferingStart < COLD_GRACE_MS
                ) {
                    Log.d("GladixPlayback", "Buffering watchdog: cold resolution in flight, re-arming")
                    armBufferingWatchdog()
                    return@withContext
                }
                // Preserve the pre-retry intent: a paused, still-loading restore (playWhenReady=
                // false) must re-prepare WITHOUT resuming, else the watchdog converts a paused
                // cold-start restore into active playback. Captured before stop()/pause() below.
                val wasPlaying = player.playWhenReady
                val currentMediaId = player.currentMediaItem?.mediaId
                if (retriedMediaId != currentMediaId) {
                    retriedMediaId = currentMediaId
                    retriedWatchdogCount = 1
                    Log.d("GladixPlayback", "Buffering watchdog: retrying $currentMediaId (attempt 1/$maxWatchdogRetries)")
                    // Position-only seek: stop() keeps the current item, so re-selecting it by index
                    // isn't needed.
                    val savedPosition = player.currentPosition
                    player.stop()
                    internalSeek { player.seekTo(savedPosition) }
                    player.prepare()
                    if (wasPlaying) {
                        player.play()
                        requestAudioFocus()
                    }
                } else if (retriedWatchdogCount < maxWatchdogRetries) {
                    retriedWatchdogCount++
                    Log.d("GladixPlayback", "Buffering watchdog: retrying $currentMediaId (attempt $retriedWatchdogCount/$maxWatchdogRetries)")
                    val savedPosition = player.currentPosition
                    player.stop()
                    internalSeek { player.seekTo(savedPosition) }
                    player.prepare()
                    if (wasPlaying) {
                        player.play()
                        requestAudioFocus()
                    }
                } else {
                    retriedMediaId = null
                    retriedWatchdogCount = 0
                    Log.d("GladixPlayback", "Buffering watchdog fired: skipping ${player.currentMediaItem?.mediaId}")
                    recordSkip(null)
                    if (consecutiveUnavailableSkips >= maxConsecutiveUnavailableSkips) {
                        reportAndResetConsecutiveSkips(player.currentMediaItem?.extensionId)
                        player.pause()
                        return@withContext
                    }
                    // hasNextMediaItem() compares the inner full index against the full count — a
                    // correct end-of-queue guard.
                    if (!player.hasNextMediaItem()) {
                        player.pause()
                        return@withContext
                    }
                    if (isAndroidAutoConnected()) {
                        player.pause()
                        delay(50)
                    }
                    internalSeek { player.seekTo(0) }
                    skipInvoluntarily()
                    player.prepare()
                    if (wasPlaying) player.play()
                }
            }
        }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (player.mediaItemCount == 0) return  // fired during/after player.release(); position is 0
        saveCurrentPosGated()
    }

    // The saveCurrentPos gate (fix 1). NEVER persist a 0 over the good saved position while the cold-start
    // re-seek latch is armed: a currentPosition of 0 during that window is the unresolved placeholder
    // timeline, never a real user position (the latch is armed only when the restored position was > 0).
    // Trigger-independent — it drops BOTH the watchdog's seek-to-0 write and any timeline-resolution
    // 0-discontinuity, because both land in the same pre-first-STATE_READY window. Legit saves are untouched:
    // a real pause carries its real P (> 0, ungated); a genuinely-at-0 queue never armed the latch, so its 0
    // persists normally. Peeks the latch NON-destructively (isRestoreSeekArmed) — reading it via
    // consumeRestoreSeek would clear it and re-open the very latch theft fix 2 closes.
    private fun saveCurrentPosGated() {
        val position = player.currentPosition
        if (position == 0L && isRestoreSeekArmed()) return
        ResumptionUtils.saveCurrentPos(context, position)
    }

    override fun onEvents(player: Player, events: Player.Events) {
        if (events.containsAny(
                Player.EVENT_MEDIA_ITEM_TRANSITION,
                Player.EVENT_MEDIA_METADATA_CHANGED,
                Player.EVENT_TIMELINE_CHANGED,
                Player.EVENT_PLAYBACK_STATE_CHANGED,
                Player.EVENT_IS_PLAYING_CHANGED
            )
        ) {
            updateCurrentFlow()
        }
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int
    ) {
        if (player.mediaItemCount == 0) return  // fired during player.release(); position is 0
        // A user seek before the cold-start re-seek fires must win — disarm the latch. Our own re-seek also
        // lands here, but it consumed the latch first, so this is a no-op for it. EXCLUDE internal
        // (buffering-watchdog) seeks: Media3 delivers them as DISCONTINUITY_REASON_SEEK too (indistinguishable
        // by reason), so without the flag the watchdog would steal the latch and the corrective re-seek would
        // never fire (fix 2).
        if (reason == Player.DISCONTINUITY_REASON_SEEK && !internalSeekInFlight) consumeRestoreSeek()
        saveCurrentPosGated()
    }

    companion object {
        private const val BUFFERING_WATCHDOG_MS = 5_000L
        // Cold-start re-seek belt: only re-apply the saved position if the current position is still at the
        // start. A user seek before the first STATE_READY moves it past this, and we leave their choice.
        private const val RESTORE_SEEK_BELT_MS = 1_000L
        // ≥ Deezer stream-resolution ceiling: DeezerApi clientNP connect 15s + read 10s;
        // getContentLength 10s. If clientNP ever gains a callTimeout, anchor to that instead.
        private const val COLD_GRACE_MS = 25_000L
    }

    private val maxRetries = 3
    private val maxSingleItemRetries = 1
    private var currentRetries = 0
    private var last: KClass<*>? = null

    private val maxConsecutiveUnavailableSkips = 3
    private var consecutiveUnavailableSkips = 0
    // The safeCause() of each skip in the current run, oldest→newest, bounded to maxConsecutiveUnavailableSkips.
    // Moves in lockstep with consecutiveUnavailableSkips: recordSkip() appends as it increments and
    // resetConsecutiveSkips() clears as it zeroes — the two are never mutated apart, so a reported run can
    // never carry a cause from a previous run.
    private val recentSkipCauses = ArrayDeque<String>()

    // True once a track has resolved to STATE_READY since the queue was last set fresh (reset below on a
    // PLAYLIST_CHANGED media-item transition). Removed-extension tracks fail during resolution and never
    // reach READY, so this stays false only when the WHOLE queue was unplayable — the sole case where the
    // removed-extension exhaustion message should fire (so a normal session that merely ends on a couple of
    // removed tracks stays silent).
    private var resolvedSinceQueueReplace = false

    // Gates the 5xx "server error" snackbar to once per run of server errors — set on the first skip caused
    // by a 5xx, reset on the next STATE_READY (a track resolved, so the run ended). Keeps a burst of CDN 5xx
    // to a single message instead of one per skipped track.
    private var serverErrorNotified = false

    private var bufferingWatchdog: Job? = null
    // Cold-resolution grace timer, keyed to the current item: restarts when the current mediaId
    // changes (a new buffering episode) and persists across watchdog re-arms of the same item.
    // Keyed by mediaId rather than reset via player callbacks, so it survives the
    // onMediaItemTransition / PLAYLIST_CHANGED events that fire as part of the cold-restore setMediaItems.
    private var coldBufferingStart = 0L
    private var coldBufferingMediaId: String? = null
    private var retriedMediaId: String? = null
    private var retriedWatchdogCount = 0
    private val maxWatchdogRetries = 1
    private var retried404MediaId: String? = null
    private var retriedSocketMediaId: String? = null
    private var retriedNetworkMediaId: String? = null

    // The ONLY way to advance the breaker: increments the counter and records this skip's cause together,
    // so they cannot drift. Called at every skip site; never at the exempt (5xx / removed-extension) sites,
    // which do not skip. cause == null for the buffering watchdog (a stuck resolve, no error object).
    private fun recordSkip(cause: Throwable?) {
        consecutiveUnavailableSkips++
        recentSkipCauses.addLast(safeCause(cause))
        if (recentSkipCauses.size > maxConsecutiveUnavailableSkips) recentSkipCauses.removeFirst()
    }

    // The ONLY way to clear the breaker: zeroes the counter and the causes together. Both reset points
    // (STATE_READY and the trip) go through here, so the two fields always reset atomically.
    private fun resetConsecutiveSkips() {
        consecutiveUnavailableSkips = 0
        recentSkipCauses.clear()
    }

    // Class + a whitelisted safe detail only — never the raw message (Media3 embeds the signed CDN URL with
    // token/hmac in it). HTTP responseCode is the 401-vs-404-vs-timeout discriminator and carries no secret;
    // responseMessage/headers are deliberately excluded.
    private fun safeCause(cause: Throwable?): String {
        if (cause == null) return "StuckBuffering"
        val cls = cause::class.simpleName ?: "Unknown"
        return if (cause is HttpDataSource.InvalidResponseCodeException) "$cls HTTP ${cause.responseCode}" else cls
    }

    private fun reportAndResetConsecutiveSkips(extensionId: String?) {
        healthMonitor?.report(
            HealthMonitor.ConsecutiveSkipException(
                consecutiveUnavailableSkips, extensionId ?: "unknown", recentSkipCauses.joinToString(",")
            ),
            HealthMonitor.Scope.MEMORY_ONLY, 10 * 60 * 1000L
        )
        resetConsecutiveSkips()
    }

    override fun onPlayerError(error: PlaybackException) {
        val cause = error.cause ?: error
        val rootCause = cause.rootCause
        val mediaItem = player.currentMediaItem

        if (rootCause is CancellationException && rootCause !is TimeoutCancellationException) {
            Log.d("GladixPlayback", "onPlayerError: ignoring CancellationException for ${mediaItem?.mediaId}")
            return
        }

        // Login-required is non-transient: every queued track fails identically, so letting it fall
        // through to the generic tail cascades retries/skips across the whole queue. classify() chain-
        // walks the wrapped form the extension actually produces (ExoPlaybackException -> IOException ->
        // AppException.LoginRequired, which has no cause so a rootCause type-check misses it). Emit once
        // and stop cleanly on the failing track: stop() preserves getPlayerError() -> the phone "Login"
        // snackbar (getMessage.rootCause) and the Lever B "Sign in" AA tile both show once, and the queue
        // is kept so play() after logging in re-resolves this same track.
        if (classify(error) == ErrorCategory.LoginOrAuth) {
            scope.launch { throwableFlow.emit(PlayerException(mediaItem, rootCause)) }
            player.stop()
            return
        }

        if (rootCause is HttpDataSource.InvalidResponseCodeException && rootCause.responseCode == 404) {
            val currentMediaId = mediaItem?.mediaId
            if (retried404MediaId != currentMediaId) {
                retried404MediaId = currentMediaId
                Log.d("GladixPlayback", "onPlayerError: 404 for $currentMediaId, retrying with stop/prepare")
                val savedIndex = player.currentMediaItemIndex
                val savedPosition = player.currentPosition
                player.stop()
                internalSeek { player.seekTo(savedIndex, savedPosition) }
                player.prepare()
                player.play()
                requestAudioFocus()
            } else {
                retried404MediaId = null
                Log.d("GladixPlayback", "onPlayerError: 404 retry failed for $currentMediaId, skipping")
                recordSkip(rootCause)
                if (consecutiveUnavailableSkips >= maxConsecutiveUnavailableSkips) {
                    reportAndResetConsecutiveSkips(mediaItem?.extensionId)
                    player.stop()
                    return
                }
                val hasMore = player.hasNextMediaItem()
                if (!hasMore) {
                    player.stop()
                    return
                }
                skipInvoluntarily()
                player.prepare()
                player.play()
            }
            return
        }

        // HTTP 5xx (500/502/503/504) = a transient REMOTE server/CDN error — not our bug. Moved off the
        // generic tail: report to messageFlow (user snackbar, NO Crashlytics non-fatal, same category as the
        // removed-extension fix) and EXEMPT from consecutiveUnavailableSkips so a CDN wobble can't trip the
        // circuit breaker and halt an otherwise-good queue. Otherwise this is the generic tail's per-item path
        // unchanged: ONE immediate retry (replaceMediaItem/withRetry — no backoff; backoff-retry is parked as
        // its own task), then skip. Bounded by end-of-queue (hasNextMediaItem): a fully-500ing CDN skips
        // monotonically to the end and stops — no loop, no spin.
        if (rootCause is HttpDataSource.InvalidResponseCodeException
            && rootCause.responseCode in 500..599
        ) {
            if (mediaItem == null) return
            val index = player.currentMediaItemIndex
            if (mediaItem.retries >= maxSingleItemRetries) {
                // Retry exhausted for this track — skip. Report ONCE per run of server errors (serverErrorNotified,
                // reset on the next STATE_READY) so a burst of 5xx shows a single snackbar, not one per track.
                if (!serverErrorNotified) {
                    serverErrorNotified = true
                    scope.launch {
                        extensions.app.messageFlow.emit(
                            Message(context.getString(R.string.server_error_skipping))
                        )
                    }
                }
                if (!player.hasNextMediaItem()) {
                    player.stop()
                    return
                }
                skipInvoluntarily()
            } else {
                player.replaceMediaItem(index, MediaItemUtils.withRetry(mediaItem))
            }
            player.prepare()
            player.play()
            return
        }

        val isTransientServerError = rootCause is SocketException
        if (isTransientServerError) {
            val currentMediaId = mediaItem?.mediaId
            if (retriedSocketMediaId == null || retriedSocketMediaId != currentMediaId) {
                retriedSocketMediaId = currentMediaId
                Log.d("GladixPlayback", "onPlayerError: SocketException for $currentMediaId, retrying")
                val savedIndex = player.currentMediaItemIndex
                val savedPosition = player.currentPosition
                player.stop()
                internalSeek { player.seekTo(savedIndex, savedPosition) }
                player.prepare()
                player.play()
                requestAudioFocus()
            } else {
                retriedSocketMediaId = null
                Log.d("GladixPlayback", "onPlayerError: SocketException retry failed for $currentMediaId, skipping")
                recordSkip(rootCause)
                if (consecutiveUnavailableSkips >= maxConsecutiveUnavailableSkips) {
                    reportAndResetConsecutiveSkips(mediaItem?.extensionId)
                    player.stop()
                    return
                }
                val hasMore = player.hasNextMediaItem()
                if (!hasMore) {
                    player.stop()
                    return
                }
                if (isAndroidAutoConnected()) {
                    scope.launch {
                        withContext(Dispatchers.Main) {
                            player.pause()
                            delay(50)
                            skipInvoluntarily()
                            player.prepare()
                            player.play()
                        }
                    }
                } else {
                    skipInvoluntarily()
                    player.prepare()
                    player.play()
                }
            }
            return
        }

        // Network-resolution failure (DNS down / host unresolved) is whole-connection, NOT a
        // per-track problem — so hold position, never skip. Mirrors the SocketException branch
        // (retry the SAME track once) but ends in pause() instead of seekToNextMediaItem(). First
        // occurrence: silent re-prepare (clears the player error, so a transient blip recovers to
        // clean playback with no message). Second occurrence: the retry also failed → pause and
        // hold, surface the no_internet message, and let the user / AA-BT resume via play (which
        // re-prepares and retries). retriedNetworkMediaId is kept on the hold so a failed resume
        // holds again (one attempt per tap); it resets in the STATE_READY block on recovery and
        // auto-invalidates when the mediaId changes. Scoped to these two exceptions only, so
        // genuinely-unavailable tracks still skip via the branches above/below.
        val isNetworkDown = rootCause is UnknownHostException || rootCause is UnresolvedAddressException
        if (isNetworkDown) {
            val currentMediaId = mediaItem?.mediaId
            if (retriedNetworkMediaId == null || retriedNetworkMediaId != currentMediaId) {
                retriedNetworkMediaId = currentMediaId
                Log.d("GladixPlayback", "onPlayerError: network down for $currentMediaId, retrying once")
                val savedIndex = player.currentMediaItemIndex
                val savedPosition = player.currentPosition
                player.stop()
                internalSeek { player.seekTo(savedIndex, savedPosition) }
                player.prepare()
                player.play()
                requestAudioFocus()
            } else {
                Log.d("GladixPlayback", "onPlayerError: network down retry failed for $currentMediaId, holding")
                scope.launch { throwableFlow.emit(PlayerException(mediaItem, rootCause)) }
                player.pause()
            }
            return
        }

        if (rootCause is TrackUnavailableException || rootCause.message?.contains("not available", ignoreCase = true) == true) {
            recordSkip(rootCause)
            if (consecutiveUnavailableSkips >= maxConsecutiveUnavailableSkips) {
                reportAndResetConsecutiveSkips(mediaItem?.extensionId)
                player.stop()
                val isRetryExhausted = rootCause.message?.contains("not available after retries", ignoreCase = true) == true
                if (!isRetryExhausted) scope.launch { throwableFlow.emit(PlayerException(mediaItem, rootCause)) }
                return
            }
            val hasMore = player.hasNextMediaItem()
            if (!hasMore) {
                player.stop()
                return
            }
            if (isAndroidAutoConnected()) {
                scope.launch {
                    withContext(Dispatchers.Main) {
                        player.pause()
                        delay(50)
                        skipInvoluntarily()
                        player.prepare()
                        player.play()
                    }
                }
            } else {
                skipInvoluntarily()
                player.prepare()
                player.play()
            }
            return
        }

        val isMissingFile = rootCause is FileDataSource.FileDataSourceException
                || rootCause is FileNotFoundException
                || rootCause.message?.contains("ENOENT", ignoreCase = true) == true
        val is401 = (rootCause is HttpDataSource.InvalidResponseCodeException
                && rootCause.responseCode in listOf(401, 403))
                || (rootCause is IllegalStateException
                && (rootCause.message?.contains("HTTP 401") == true
                    || rootCause.message?.contains("HTTP 403") == true))
        val isMalformedContent = rootCause is ParserException && rootCause.contentIsMalformed
        val isTimeout = rootCause is TimeoutCancellationException || rootCause is SocketTimeoutException
        if (is401) {
            val currentMediaId = mediaItem?.mediaId
            if (retriedMediaId != currentMediaId) {
                retriedMediaId = currentMediaId
                retriedWatchdogCount = 1
                Log.d("GladixPlayback", "onPlayerError: 401 for $currentMediaId, retrying with stop/prepare (fresh TRACK_TOKEN)")
                val savedIndex = player.currentMediaItemIndex
                val savedPosition = player.currentPosition
                player.stop()
                internalSeek { player.seekTo(savedIndex, savedPosition) }
                player.prepare()
                player.play()
                requestAudioFocus()
                return
            }
            retriedMediaId = null
            retriedWatchdogCount = 0
            Log.d("GladixPlayback", "onPlayerError: 401 retry exhausted for $currentMediaId, skipping")
            // fall through to silent skip below
        }

        // ExtensionNotFoundException = the track's extension was UNINSTALLED (removed) while queued. Disabled
        // extensions stay in the music flow and getExtensionOrThrow returns them (they don't throw this), so
        // this is removed-only. It's a synchronous list.find miss — instant, no network, and no retry can
        // ever succeed — so it joins the silent-skip family but is EXEMPT from the consecutiveUnavailableSkips
        // circuit breaker: that cap throttles retry-loop storms (CDN/token), whereas skipping a dead-extension
        // track is free. Only the end-of-queue bound below applies, so we skip past e.g. 30 removed-Spotify
        // tracks straight to the live-extension track at 31.
        val isExtensionRemoved = rootCause is ExtensionNotFoundException
        if (isMissingFile || is401 || isMalformedContent || isTimeout || isExtensionRemoved) {
            if (!isExtensionRemoved) {
                recordSkip(rootCause)
                if (consecutiveUnavailableSkips >= maxConsecutiveUnavailableSkips) {
                    reportAndResetConsecutiveSkips(mediaItem?.extensionId)
                    player.stop()
                    return
                }
            }
            val hasMore = player.hasNextMediaItem()
            if (!hasMore) {
                // Queue exhausted. For a removed-extension run, surface ONE message iff NOTHING resolved to
                // READY since the queue was last set (resolvedSinceQueueReplace) — i.e. the whole queue was
                // unplayable — so a normal session that merely ends on a couple of removed tracks stays
                // silent. messageFlow = user snackbar, no Crashlytics (expected input, not a bug).
                if (isExtensionRemoved && !resolvedSinceQueueReplace) {
                    scope.launch {
                        extensions.app.messageFlow.emit(
                            Message(context.getString(R.string.removed_extension_playback_stopped))
                        )
                    }
                }
                player.stop()
                return
            }
            if (isAndroidAutoConnected()) {
                scope.launch {
                    withContext(Dispatchers.Main) {
                        player.pause()
                        delay(50)
                        skipInvoluntarily()
                        player.prepare()
                        player.play()
                    }
                }
            } else {
                skipInvoluntarily()
                player.prepare()
                player.play()
            }
            return
        }

        scope.launch { throwableFlow.emit(PlayerException(mediaItem, cause)) }

        val old = last
        last = rootCause::class
        if (old != null && old == last) currentRetries++
        else currentRetries = 0

        if (mediaItem == null) return
        // Current index: replaceMediaItem below applies to the full timeline, so this must be the
        // current track's real index or the retry would swap the wrong track.
        val index = player.currentMediaItemIndex
        val retries = mediaItem.retries

        if (currentRetries >= maxRetries) {
            currentRetries = 0
            last = null
            Log.d("GladixPlayback", "onPlayerError: maxRetries exhausted for ${mediaItem.mediaId}, skipping")
            recordSkip(rootCause)
            if (consecutiveUnavailableSkips >= maxConsecutiveUnavailableSkips) {
                reportAndResetConsecutiveSkips(mediaItem.extensionId)
                player.stop()
                return
            }
            val hasMore = player.hasNextMediaItem()
            if (!hasMore) {
                player.stop()
                return
            }
            if (isAndroidAutoConnected()) {
                scope.launch {
                    withContext(Dispatchers.Main) {
                        player.pause()
                        delay(50)
                        player.seekTo(player.currentMediaItemIndex, 0)
                        skipInvoluntarily()
                        player.prepare()
                        player.play()
                    }
                }
            } else {
                player.seekTo(player.currentMediaItemIndex, 0)
                skipInvoluntarily()
                player.prepare()
                player.play()
            }
            return
        }
        if (retries >= maxSingleItemRetries) {
            val hasMore = player.hasNextMediaItem()
            if (!hasMore) {
                player.stop()
                return
            }
            skipInvoluntarily()
        } else {
            val newItem = MediaItemUtils.withRetry(mediaItem)
            player.replaceMediaItem(index, newItem)
        }
        player.prepare()
        player.play()
    }
}
