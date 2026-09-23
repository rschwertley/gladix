package dev.brahmkshatriya.echo.playback.listener

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.ParserException
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.StuckPlayerException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.StatsDataSource
import androidx.media3.datasource.TeeDataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoTimeoutException
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.common.clients.LikeClient
import dev.brahmkshatriya.echo.common.models.Message
import dev.brahmkshatriya.echo.di.App
import dev.brahmkshatriya.echo.extensions.ExtensionLoader
import dev.brahmkshatriya.echo.extensions.exceptions.AppException
import dev.brahmkshatriya.echo.extensions.exceptions.ExtensionNotFoundException
import dev.brahmkshatriya.echo.extensions.exceptions.MediaUnavailableException
import dev.brahmkshatriya.echo.extensions.exceptions.WrongItemException
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
import dev.brahmkshatriya.echo.playback.queueEpochOrZero
import dev.brahmkshatriya.echo.utils.CrashKeys
import dev.brahmkshatriya.echo.playback.source.StreamableDataSource
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.net.ConnectException
import java.net.NoRouteToHostException
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
    // Live PlayerState.loadEpisodeStartMs (epoch ms of the 0 -> 1 edge, 0 when nothing is
    // outstanding). Wired from PlayerService alongside activeLoadCount. PROBE - remove with
    // stuck_detail's loadAge field.
    private val loadEpisodeStartMs: () -> Long = { 0L },
    // Invoked when the timeline becomes non-empty (a queue was applied, from any source) — the success
    // clear for PlayerState.resumptionApplying. Fires on the app looper (Main), preserving that invariant.
    private val onQueueApplied: () -> Unit = {},
    // Returns-and-clears PlayerState.pendingRestoreSeek (the cold-start re-seek latch). Wired from
    // PlayerService like onQueueApplied; this listener is not given PlayerState directly. Returns null once
    // consumed, so it fires at most once per cold restore.
    private val consumeRestoreSeek: () -> PlayerState.RestoreSeek? = { null },
    // Non-consuming PEEK at PlayerState.pendingRestoreSeek — true iff the cold-start re-seek latch is armed.
    // Never clears it (unlike consumeRestoreSeek), so it can gate the saveCurrentPos 0-write below WITHOUT
    // stealing the latch the STATE_READY re-seek depends on. Wired from PlayerService like the others; fires
    // on the app looper (Main). A latch is armed only when the restored position was > 0, so "armed" means
    // "we restored to a known non-zero position that the placeholder timeline hasn't resolved yet" — exactly
    // the window in which a currentPosition of 0 is spurious and must never overwrite the good saved value.
    private val isRestoreSeekArmed: () -> Boolean = { false },
    private val healthMonitor: HealthMonitor? = null,
) : Player.Listener {

    // CACHED at construction, deliberately not `get() = session.player`.
    // MediaSession.getPlayer() -> MediaSessionImpl.getPlayerWrapper() calls verifyApplicationThread()
    // and throws IllegalStateException off the application looper (Media3 1.11.0; 1.10.1 had NO check,
    // so off-main reads silently returned torn state — that is what the AA metadata desync fixed in
    // 2ee949c6 looked like). Several uses below run inside scope.launch on Dispatchers.IO, so a
    // per-use accessor was already wrong today and would become fatal on 1.11.0.
    // This listener is constructed in PlayerService.onCreate on the MAIN thread, which is the player's
    // application looper, so the single read happens on the app thread and every use is then a plain
    // field access with no dispatch cost.
    // SAFE because MediaSession.setPlayer() is never called anywhere in the app (verified by grep) —
    // the reference cannot go stale. If that ever changes, this must become a withContext(Main) read.
    private val player = session.player

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

    // Durable-position ticker (the SAVE-side half of the mid-song-reboot fix). POSITION is otherwise written
    // only on discrete events (onPositionDiscontinuity / onIsPlayingChanged), so a song played straight
    // through never updates it after the AUTO_TRANSITION into it wrote 0 at song start — a reboot / OS memory-
    // kill / crash (none of which fire an event or reach onDestroy's flush) then resumes at 0. This snapshots
    // the live position periodically so the on-disk value stays fresh. Runs on the app looper (Main) so its
    // write SERIALIZES with the event saves — both go through the synchronous, atomic saveCurrentPosGated →
    // saveToQueue (tmp+rename), so there is never a concurrent POSITION-file write (do NOT move the write to
    // IO — that would break the serialization). Gated to actual playback via player.isPlaying (skips
    // buffering / paused / idle / released), and routed through saveCurrentPosGated so the isRestoreSeekArmed
    // gate still suppresses the placeholder 0 during the restore window. A child of `scope`, so it is
    // cancelled with the service in onDestroy (scope.cancel), and cannot fire between player.release() and
    // that cancel because both run synchronously on Main.
    // Launched from init{} (fire-and-forget, no stored Job handle): teardown is via scope.cancel() in
    // onDestroy, as the comment above describes. Same construction-phase launch and dependencies as the
    // former property-initializer form — only scope (a constructor param) is needed at launch-call time;
    // the body runs async (posted to Main + 5s delay) so player/saveCurrentPosGated are fully ready.
    init {
        scope.launch(Dispatchers.Main) {
            while (isActive) {
                delay(POSITION_SAVE_INTERVAL_MS)
                if (player.isPlaying) saveCurrentPosGated()
            }
        }
    }

    // True only while an INVOLUNTARY auto-skip's seekToNextMediaItem is in flight. onMediaItemTransition
    // reads it to SKIP persisting the resume pointer (saveIndex) for this advance, so CURRENT_ID stays on
    // the failed/stuck track: cold start then retries that track (recovering a transient token/network
    // failure) instead of over-advancing past it (the "resume one track ahead" bug). Same single-thread
    // bracket invariant as internalSeekInFlight — seekToNextMediaItem delivers onMediaItemTransition
    // SYNCHRONOUSLY on this (Main) thread inline (ListenerSet.flushEvents inside seekTo), so the flag is
    // reliably set when the callback observes it. Dedicated flag (NOT internalSeekInFlight, which gates the
    // restore-seek latch) and MARKER-gated, not reason-gated: an involuntary skip and a user's manual Next
    // both surface as reason=SEEK, so only this marker distinguishes them. finally-reset so it can't stick.
    private var involuntarySkipInFlight = false

    // Every skip in this listener is an INVOLUNTARY auto-skip (a failed/stuck current track). Route
    // them through here so ShufflePlayer removes the departing track WITHOUT pushing it to the play-
    // history back-stack (Seam 3) — Previous must never land back on a dead track that would re-fail.
    private fun skipInvoluntarily() {
        (player as? ShufflePlayer)?.suppressPushOnNextAdvance = true
        involuntarySkipInFlight = true
        try {
            player.seekToNextMediaItem()
        } finally {
            involuntarySkipInFlight = false
        }
    }

    // ⚠⚠ THIS DEBOUNCE STARVES UNDER A QUEUE DRAG, AND THAT BROKE REORDERING FOR THREE MONTHS.
    // THE ARITHMETIC, which reads as obvious once stated and is invisible otherwise:
    //   a drag fires QueueFragment's onMove roughly every 16ms (once per frame);
    //   each one calls moveMediaItem -> onTimelineChanged -> here;
    //   this CANCELS the pending job and restarts a 50ms timer;
    //   so the timer NEVER EXPIRES while the finger is moving.
    // No emission means no queueFlow, no submitList, and an adapter that never reorders - so
    // ItemTouchHelper re-asked for the same move every frame (`onMove 22->21` x10, measured 1102) while
    // LinearLayoutManager.prepareForDrop scrolled the list instead of moving the tile. THAT is why "barely
    // move it" works - a pause lets the 50ms elapse - and a continuous drag does not.
    // ⚠⚠ INTRODUCED BY 2e11248c (2026-06-24), WHICH ADDED fullQueueFlow AND THIS DEBOUNCE
    // TOGETHER. QueueFragment.onMove is UNCHANGED across all four of its commits and QueueAdapter has not
    // been touched since 2025 - SO THE BREAK CAME FROM A CHANGE MADE ON ANOTHER SCREEN'S BEHALF, and the
    // next person reading onMove will not find the cause there. That is the whole reason this note is here
    // rather than only at the drag site.
    // ⚠⚠ DO NOT FIX IT BY EXEMPTING THE DRAG. Two independent justifications, NEITHER about
    // drag, and a caller-dependent branch on a timer that fires every 16ms is the wrong place to get
    // timing right:
    //   RENDERING - changeQueue's incremental removeMediaItems/addMediaItems each fire a separate
    //     onTimelineChanged, and without coalescing the UI renders intermediate, partially-torn-down queue
    //     states.
    //   MEMORY - the Car/AA OOM audit lists "everything replace-latest (fullQueueFlow StateFlow) or
    //     debounce-cancel" as one of the reasons there are no per-switch leaks. Cancel-and-restart is doing
    //     work that investigation relied on.
    // The drag was fixed on its own side instead: QueueFragment.onMove now reorders the adapter locally, so
    // the gesture no longer depends on this round trip at all.
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
            if ((player as? ShufflePlayer)?.isRearranging == true) return@launch
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
        // Skip persisting the resume pointer for an INVOLUNTARY auto-skip (see involuntarySkipInFlight):
        // keep CURRENT_ID/INDEX on the failed track so cold start retries it instead of resuming one ahead.
        // Genuine advances and the user's manual Next (marker not set) persist as before; the debounced
        // saveQueue is untouched and still reconciles the persisted queue to the live post-skip state.
        if (!involuntarySkipInFlight)
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
        // Post-resumption custom-layout push (display-only). A restored/fresh queue applies via
        // PLAYLIST_CHANGED here — AFTER onConnect (connect → resume → queue applied), so AA is stably
        // connected. Re-push so the full layout (incl. the like button the synchronous onConnect seed
        // couldn't include) reaches the connected controller instead of being lost in the connect race.
        // updateCustomLayout no-ops when currentMediaItem is null, so an empty timeline here is safe.
        if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) updateCustomLayout()
        if ((player as? ShufflePlayer)?.isRearranging != true) {
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

    // THE BREAKER LEAVES THE PLAYER SOMEWHERE NOTHING CAN RE-ARM FROM. reportAndResetConsecutiveSkips
    // ends in player.pause(), which flips playWhenReady ONLY - playbackState stays STATE_BUFFERING, because
    // the load never completed. Every arm site needs an event that then cannot happen:
    //   onPlaybackStateChanged fires on a TRANSITION, and BUFFERING -> BUFFERING is not one;
    //   onTimelineChanged needs TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED;
    //   the cold-grace self re-arm lives inside a job that has already completed.
    // So a play press after a trip changed playWhenReady and nothing else, and the player span 12+ minutes
    // with no watchdog behind it. The field evidence for that is exact: the 10:22:09 play produced a SINGLE
    // audio-focus request, where a real state transition produces two (onPlayWhenReadyChanged plus
    // AudioFocusListener's STATE_BUFFERING branch). One request means no transition happened.
    //
    // Safe to fire before a breaker trip: arming during ordinary buffering is exactly the intended
    // behaviour, and armBufferingWatchdog cancels any existing job first, so it cannot double-arm.
    // This is a MITIGATION, not the fix - it bounds the hang, it does not stop periods failing to prepare.
    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (playWhenReady && player.playbackState == Player.STATE_BUFFERING) armBufferingWatchdog()
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        Log.d("GladixPlayback", "onPlaybackStateChanged: state=$playbackState")
        if (playbackState == Player.STATE_BUFFERING) {
            armBufferingWatchdog()
        } else {
            bufferingWatchdog?.cancel()
            bufferingWatchdog = null
        }
        // ⚠⚠ SETTLE playWhenReady AT THE END OF THE QUEUE. ExoPlayer does NOT clear it on STATE_ENDED,
        // and nothing else here did either, so a queue that simply ran out sat at ENDED with the player
        // still declaring INTENT TO PLAY — indefinitely. ONE FLAG, FIVE CONSUMERS, all wrong at once:
        //   1. PlayerFragment:832 trackPlayPause.isChecked      -> the transport shows PAUSE
        //   2. PlayerFragment:833 collapsedTrackPlayPause       -> the mini-bar shows PAUSE too
        //   3. PlayerFragment.updateWaveMotion (via :776)       -> the seek wave keeps animating
        //   4. PlayerFragment:838 playingIndicator.alpha        -> via `buffering && playWhenReady`
        //   5. MainActivity.keepScreenOn (the `if (isTV)` observer) -> screen held awake on a finished queue
        // Reported from device as "a PAUSE button offering to pause something that is not playing", which
        // is the one a user actually notices — the others read as cosmetic until you know the cause.
        //
        // ⚠️ pause(), NEVER stop(), AND THE DIFFERENCE IS NOT STATE HYGIENE:
        //   • stop() sends the INNER player to STATE_IDLE, and PlayerService sets
        //     SHOW_NOTIFICATION_FOR_IDLE_PLAYER_NEVER — MediaNotificationManager.shouldShowNotification
        //     (media3-session 1.11.0, :382) returns true for any non-IDLE state and false for IDLE under
        //     NEVER — so the notification would be removed the instant the last track ended.
        //   • The recorded sequencing for adopting stop() is "switch to AFTER_STOP_OR_ERROR first". THAT
        //     SEQUENCING IS NOT SAFE HERE AND READS AS IF IT WERE. That mode returns
        //     `!wasNotificationDismissed && hasBeenPrepared` for an IDLE player (:394-396), and
        //     hasBeenPrepared LATCHES TRUE the first time anything plays and is never reset — so every
        //     later IDLE posts a MediaStyle notification with a PLAY button. This app already shipped that
        //     once, via a hardcoded `2` with a comment claiming 2 == NEVER (the real mapping is
        //     0=ALWAYS, 1=NEVER, 2=AFTER_STOP_OR_ERROR), and it drove onPlaybackResumption ->
        //     unconditional player.play(). Do not re-enter it as a stepping stone.
        //     ⚠⚠ AND THE SYMPTOM IT PRODUCED WAS NEVER EXPLAINED. That accidental `2` caused
        //     COLD-START AUTOPLAY, which was investigated, never captured, and then SET DOWN
        //     DELIBERATELY - a decision, not a pending task; the reasoning and everything the
        //     investigation established are at RadioFallback's status note. So the recorded
        //     sequencing is not merely unsafe in theory: IT IS A KNOWN REGRESSION THAT THIS APP HAS
        //     ALREADY SHIPPED ONCE, and adopting it would reintroduce a mechanism producing the
        //     exact symptom nobody was able to explain. The June fix set the constant to NEVER and
        //     an August re-check verified it intact, so this is NOT the surviving cause - but it IS
        //     why the stepping stone is closed.
        //     ⚠️ REVISITING THIS BLOCKER MADE IT STRONGER, NOT WEAKER. Worth knowing as a
        //     counter-case to the house rule on stale blockers: re-reading one is how you find a free
        //     win, and equally how you avoid taking a recorded next step that has since been refuted.
        //   • stop() also leaves getPlayerError() non-null (stopInternal passes resetError=false) and only
        //     prepare() clears it. Not a problem AT ENDED — reaching ENDED requires a completed prepare(),
        //     so the error is null by construction — but stop() would move us to an IDLE that a later
        //     failure can populate.
        //
        // ⚠️ AND pause() DOES NOT ACTIVATE ShufflePlayer's FAKE STATE_READY. That fake needs
        // `inner == STATE_IDLE && mediaItemCount > 0 && !playWhenReady`. Here the inner player is ENDED,
        // not IDLE, so the condition fails on its first term. The trap to avoid is doing BOTH — stop() to
        // IDLE and clearing playWhenReady — which would satisfy it and blind all four prepare() guards
        // that test `playbackState == STATE_IDLE` (PlayerRadio's post-append prepare and PlayerCallback's
        // three). Checked against the Media3-internal readers too: shouldShowNotification and
        // MediaLibrarySessionImpl's recent-root branch both test against IDLE, and isAnySessionUserEngaged
        // (:288-300) requires READY or BUFFERING — so it is ALREADY false at ENDED regardless of this
        // pause, meaning the foreground timeout was already running before this change.
        //
        // WHAT THIS DELIBERATELY DOES NOT CHANGE: the frozen 02:10/02:10 readout (PlayerUiListener stops
        // the progress ticker for ENDED — correct, and only conspicuous because the wave next to it kept
        // moving), and the replay-on-press behaviour, since ShufflePlayer.play()/setPlayWhenReady() still
        // seekTo(0, 0) at ENDED. That branch carries its own never-monitored note and is the same family as
        // the cold-start autoplay bug (investigated, never explained, set down deliberately rather
        // than left pending - see RadioFallback's status note): a queue parked at ENDED is what
        // converts a phantom play request
        // into audible playback of a finished track. Pausing does not remove ENDED, so that interaction is
        // UNCHANGED — it is only the five playWhenReady consumers above that are fixed.
        //
        // hasNextMediaItem() rather than a bare ENDED test: ENDED with a next item is a state the radio
        // append path can transiently produce, and pausing there would fight the append.
        //
        // ⚠⚠ NOT GATED ON !isTv, AND THE FIRST VERSION OF THIS WAS — THAT WAS A BUG. The reasoning was
        // "PlayerRadio.onPlaybackStateChanged already owns end-of-queue on TV". It owns the RADIO
        // CONTINUATION there; it does not settle playWhenReady, and TV HAS THE SAME FIVE-CONSUMER PROBLEM:
        //   PlayerTvFragment.updateWaveMotion   reads viewModel.playWhenReady.value   (its own tvSeekWaveBar)
        //   PlayerTvFragment                    tvTrackPlayPause.isChecked = it
        //   PlayerTvFragment                    tvPlayingIndicator.alpha via buffering && it
        //   MainActivity                        `if (isTV) observe(playWhenReady) { keepScreenOn = it }`
        // THE LAST ONE IS TV-ONLY. keepScreenOn is only wired on TV, so gating this fix off on TV excluded
        // the single platform where that consumer exists — a finished queue would hold the screen awake
        // indefinitely, on the device most likely to be left unattended.
        // GENERAL LESSON, ALREADY PAID FOR ONCE: this project has shipped a fix that did nothing because
        // "the running fragment is PlayerTvFragment, not PlayerFragment — and the fix lives entirely in
        // PlayerFragment". MainActivity picks PlayerTvFragment on isTV else PlayerFragment. WHEN A FIX
        // TARGETS PLAYER UI STATE, CHECK BOTH FRAGMENTS BEFORE ASSUMING ONE OF THEM IS COVERED.
        // AND THE TV CASE IS THE WORSE ONE, not the safer one: tvDriveRadio(atEnd = true) calls
        // loadPlaylist() and only then seeks and plays, so on the empty-station data shape TV regenerates,
        // gets nothing, and sits at ENDED with no settle at all.
        // COST OF NOT GATING: when tvDriveRadio DOES append, this pause lands first and its `play()`
        // restores playWhenReady a moment later — a brief glyph/wave flicker for the length of the fetch.
        // That is honest state (nothing is playing during it) and it cannot break the append: pause()
        // leaves playbackState at ENDED, so tvDriveRadio's `if (STATE_ENDED && hasNextMediaItem())`
        // recovery still matches.
        // ⚠️ REMOVING THE GATE ALSO REMOVES A DEPENDENCE ON isTv BEING RIGHT, which is worth having:
        // isTv has been observed FALSE ON A GOOGLE TV before UiUtils.isTv was corrected to check
        // UiModeManager.currentModeType == UI_MODE_TYPE_TELEVISION BEFORE falling back to
        // FEATURE_LEANBACK (Google TV reports the former but not the latter). The current implementation is
        // the corrected one — but a fix that does not need to ask the question cannot be wrong about it.
        if (playbackState == Player.STATE_ENDED && !player.hasNextMediaItem()) {
            Log.d("GladixPlayback", "STATE_ENDED with no next item: settling playWhenReady")
            player.pause()
        }
        if (playbackState == Player.STATE_READY) {
            // PROBE (2026-08-29) - see the field declarations. This is the only moment the current item's
            // timeline is final and the shouldLoadNextMediaPeriod gate is being evaluated against it, which
            // is why a LAST-READY value is the right subject for `dur`: shouldLoadNextMediaPeriod gates
            // loading the NEXT period against the CURRENTLY PLAYING one, so the track that reached READY is
            // exactly the period whose duration the gate reads. Main thread here, so session.player is safe
            // to touch under 1.11's app-thread enforcement. player.duration is on the Player interface, so
            // it reads correctly through the ShufflePlayer wrapper - unlike the `mime` field that used to
            // sit here, which did not (see below).
            //
            // ⚠️ DO NOT REINSTATE A `mime` FIELD AS `(player as? ExoPlayer)?.audioFormat` - IT IS DEAD.
            // `player` is `session.player`, and PlayerService builds the session with
            // ShufflePlayer(exoPlayer), which is a ForwardingPlayer - NOT an ExoPlayer. The safe cast
            // therefore always yields null, so the probe reported mime=none in every report it ever
            // produced, on every path, and read as a finding about the stalled track when it was a constant.
            // Removed 2026-09-01. A working version would need the wrapper unwrapped AND a per-item capture
            // point - audioFormat is only populated once decoding begins, so at a stall it can only ever
            // describe the PREVIOUS track. That is new instrumentation, not a repair.
            lastReadyDurationKnown = player.duration != C.TIME_UNSET
            resetConsecutiveSkips()
            // A track resolved successfully — the queue is not all-dead (removed-extension tracks never reach
            // READY). Suppresses the removed-extension exhaustion message for any queue that played anything.
            resolvedSinceQueueReplace = true
            // A track resolved, so any prior run of 5xx server errors has ended — re-arm the one-per-run
            // server-error snackbar for the next run.
            serverErrorNotified = false
            retried404MediaId = null
            retriedSocketMediaId = null
            networkRetryCount = 0
            // Cold-start re-seek: the saved position was lost when prepare() resolved the deferred source's
            // placeholder->real timeline to the default (0). The real timeline now exists (STATE_READY), so a
            // seek sticks. Position-only on the current window (no index form — sidesteps ShufflePlayer's
            // windowed-index seeks). Guarded so it fires exactly once and loses to a user action: mediaId must
            // still be the restored track (not one the user tapped mid-buffer), and currentPosition must still
            // be at the start (a user seek before this READY moves it past the belt and we leave it alone).
            // ⚠⚠ EPOCH FIRST - THE TWO OLDER GUARDS CANNOT DECIDE THIS. See the mechanism note
            // at PlayerState.pendingRestoreSeek: mediaId and the belt are both SATISFIED by a fresh tap on
            // the restored track, which is exactly the case they were written to exclude. A null epoch means
            // the arm could not capture one (onPlaybackResumption) and keeps the old behaviour.
            // posBefore is read BEFORE the seek, because the belt is asking what the position was when the
            // fix judged it, not what the fix produced.
            val posBefore = player.currentPosition
            val seek = consumeRestoreSeek()
            if (seek != null
                && (seek.epoch == null || seek.epoch == player.queueEpochOrZero)
                && player.currentMediaItem?.mediaId == seek.mediaId
                && posBefore < RESTORE_SEEK_BELT_MS
            ) player.seekTo(seek.positionMs)
        }
    }

    private fun armBufferingWatchdog() {
        Log.d("GladixPlayback", "STATE_BUFFERING: ${player.currentMediaItem?.mediaId} \"${player.currentMediaItem?.mediaMetadata?.title}\"")
        // Start (or keep) the cold-resolution grace timer for the current item.
        val graceMediaId = player.currentMediaItem?.mediaId
        if (graceMediaId != resolveGraceMediaId) {
            resolveGraceMediaId = graceMediaId
            resolveGraceStart = System.currentTimeMillis()
            // PROBE (2026-08-29) - baseline for the `opens` delta, taken per ITEM rather than per arm so a
            // watchdog retry on the same track does not reset it. Anything the retries open still counts.
            // This guard is `graceMediaId != resolveGraceMediaId`, i.e. it fires when the current mediaId
            // CHANGES - it is not inside the STATE_READY branch above, so the baseline does not depend on a
            // track ever having reached READY.
            openCountAtItemStart = StreamableDataSource.openCount.get()
            // PROBE (2026-09-01) - same baseline, same reasoning, for the `bytes` delta.
            bytesReadAtItemStart = StreamableDataSource.bytesRead.get()
        }
        bufferingWatchdog?.cancel()
        bufferingWatchdog = scope.launch {
            delay(BUFFERING_WATCHDOG_MS)
            withContext(Dispatchers.Main) {
                if (player.playbackState != Player.STATE_BUFFERING) return@withContext
                // RESOLVE-IN-FLIGHT SUPPRESSION: a stream resolution is actively running and we are
                // still inside the grace window → the buffering is expected, not stuck. Re-arm and wait
                // WITHOUT touching the player: stop()+re-prepare() would cancel the running loadJob and
                // restart the resolution clock, skipping valid-but-slow tracks.
                //
                // ⚠️ COLD START IS ONE INSTANCE OF THIS, NOT THE WHOLE OF IT. This gate carried an
                // `isLoaded == false` conjunct until 2026-09-04, which confined it to first-time
                // resolutions and so never fired on an ADVANCE: by then the item has been through
                // buildLoaded, isLoaded is true, and a 2.4-3.8s resolve was killed and skipped by the 5s
                // watchdog with nothing wrong (measured — createPeriod 338ms before the trip).
                //
                // WHY WIDENING IS SAFE FOR THE JUNE 30 AA COLD-CONNECT FIX, which is the non-obvious part:
                // the old condition is a STRICT SUBSET of this one. `isLoaded == false && loads > 0`
                // implies `loads > 0`, so every input that took the cold branch still takes it. The fix is
                // preserved by construction, not by argument — there is no cold-path behaviour change to
                // re-test.
                //
                // AND THE TIMER WAS ALREADY PER-ITEM, not per-cold-start — see resolveGraceStart, which is
                // rekeyed whenever the current mediaId CHANGES. So on an advance the window measures from
                // the moment that item became current, which is exactly the semantics wanted here. June
                // 30's reason for keying it by mediaId rather than resetting from player callbacks (the
                // cold-restore path fires onMediaItemTransition / PLAYLIST_CHANGED as part of
                // setMediaItems, and callback-order resets were fragile there) is about NOT resetting from
                // callbacks and is untouched by widening.
                if (activeLoadCount() > 0
                    && System.currentTimeMillis() - resolveGraceStart < RESOLVE_GRACE_MS
                ) {
                    Log.d("GladixPlayback", "Buffering watchdog: resolve in flight, re-arming")
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
                    // Stall-mode discriminator for the otherwise bare "StuckBuffering" cause. This path
                    // passes recordSkip(null) -- there is no exception to describe -- so before this the
                    // report said only "it stalled", which is exactly what the class name already said.
                    // Build-1055/1056 produced four reports reading
                    // lastCauses=StuckBuffering,StuckBuffering,StuckBuffering and they were unactionable.
                    // The four modes these three fields separate:
                    //   loaded=false loads=1+ -> extension resolve still in flight, never returned a stream
                    //   loaded=false loads=0  -> resolve ended without producing a source
                    //   loaded=true  buf=0    -> source opened, zero bytes arrived (CDN / network stall)
                    //   loaded=true  buf=some -> bytes arriving, just too slowly for BUFFERING_WATCHDOG_MS
                    // and three context fields that say WHICH failure this is a case of:
                    //   item= 1st / same / next -- three trips on three tracks is a queue- or source-wide
                    //         fault; three on ONE track is ours, and reachable: skipInvoluntarily() is
                    //         seekToNextMediaItem(), which under REPEAT_MODE_ONE resolves to the SAME
                    //         index, so the breaker can trip without the queue ever advancing.
                    //   net=  up / down / ? -- separates "this device lost the network" from "the source
                    //         is unreachable while we are online", which no other field here can. Read
                    //         from ConnectivityManager rather than App.networkFlow because this listener
                    //         is not given App; runCatching because a diagnostic must never throw.
                    //   play= yes / no -- playWhenReady at the trip. The watchdog arms on ANY
                    //         STATE_BUFFERING, so a PAUSED restore that stalls can trip the breaker with
                    //         the user never having pressed play. Not inferable from the crash keys:
                    //         is_playing is false throughout STATE_BUFFERING regardless of intent.
                    //
                    // Deliberately NOT added, because they carry no information here:
                    //   time-in-buffering -- continuous (see the cardinality note below), and the grace
                    //     expiry it would show is already implied: reaching this branch with loaded=false
                    //     loads=1+ can ONLY happen after RESOLVE_GRACE_MS elapsed, or the cold branch above
                    //     would have re-armed instead.
                    //   retriedWatchdogCount -- always maxWatchdogRetries here; a constant.
                    //   mediaId / track title -- extension-authored, would need scrubbing, and item=
                    //     answers the only question they were wanted for. extensionId already attributes.
                    //
                    // (!) EVERY FIELD MUST STAY LOW-CARDINALITY. HealthMonitor.report() dedupes on
                    // simpleName + message, and lastCauses is PART of that message, so any continuous value
                    // here -- elapsed ms, a buffered-ms count, a track id -- gives every trip a unique
                    // signature, defeats the 10-minute cooldown and turns this back into spam. That is the
                    // same trap the deliberately-constant message on DataSourceTeardownRaceException exists
                    // to avoid. Hence the booleans, the "2+" clamp and the bucketed net/item: the ceiling
                    // is 2 x 3 x 2 x 3 x 3 x 2 = 216 signatures, but that is a CEILING and not a rate --
                    // the fields are strongly correlated (loaded=true forces loads=0; net=down forces
                    // buf=0) and every one of them is stable for the duration of an episode, so a
                    // repeating condition still collapses to one report per cooldown while a CHANGE of
                    // condition reports immediately. What would actually break the cooldown is a field
                    // that MOVES for uninteresting reasons; that is the test to apply to anything you add
                    // here, not the size of the product. Bucket anything new; never interpolate a raw
                    // number. Every field is ours - a player/system read, never an extension-authored
                    // string - so none of them need scrubbing, and the mediaId stays out for exactly that
                    // reason (extensionId already attributes, and item= answers what it was wanted for).
                    val loads = activeLoadCount()
                    val bufferedAhead = (player.bufferedPosition - player.currentPosition).coerceAtLeast(0L)
                    // currentMediaId, not a fresh read: it is captured at the top of this withContext and
                    // nothing has touched the player on this branch yet, so the two are identical here.
                    val item = when {
                        lastWatchdogSkipMediaId == null -> "1st"
                        lastWatchdogSkipMediaId == currentMediaId -> "same"
                        else -> "next"
                    }
                    lastWatchdogSkipMediaId = currentMediaId
                    val net = runCatching {
                        val cm = context.getSystemService(ConnectivityManager::class.java)
                        if (cm?.activeNetwork == null) "down" else "up"
                    }.getOrDefault("?")
                    // PROBE (2026-08-29) - probeDetail() carries dur/opens/mime; the fields before it are
                    // watchdog-local (item, play) or cheap player reads, so they stay here.
                    recordSkip(
                        null,
                        detail = "loaded=${player.currentMediaItem?.isLoaded == true} " +
                            "loads=${if (loads > 2) "2+" else "$loads"} " +
                            "buf=${if (bufferedAhead > 0L) "some" else "0"} " +
                            "item=$item net=$net play=${if (wasPlaying) "yes" else "no"} " +
                            probeDetail()
                    )
                    // ══ THE GIVE-UP BRANCHES, AND WHY THEIR SILENCE IS STRUCTURAL ══════════════════
                    // Both `pause(); return` paths below end the watchdog with the player still in
                    // STATE_BUFFERING — pause() moves playWhenReady only, because the load never
                    // completed. Nothing re-arms from there: onPlaybackStateChanged needs a STATE change
                    // and BUFFERING -> BUFFERING is not one, and onPlayWhenReadyChanged's arm requires
                    // playWhenReady TRUE, which pause() has just cleared. The player is parked.
                    //
                    // ⚠️ AND A STALL PARKED HERE IS SILENT BY CONSTRUCTION, NOT BY OVERSIGHT. Both
                    // 2026-09-05 field reports (build 1078, same device, 14 minutes apart) happened with
                    // NO UI ATTACHED: last_disconnected_pkg was our OWN package — the app's controller
                    // disconnecting from its own session, i.e. the Activity going away — and in the long
                    // session that disconnect landed ONE SECOND before the stuck window opened. The
                    // service went on running headless with the notification still showing a track that
                    // never played.
                    //
                    // ⚠️ SO ANY "REPORT IT ONCE" DESIGN ADDED HERE MUST NOT USE app.messageFlow. Its only
                    // subscriber is SnackBarHandler's lifecycle-gated observe (ContextUtils.observe ->
                    // flowWithLifecycle, STARTED), and a shared flow with no subscriber DISCARDS the
                    // emission — a buffer does not hold values for a subscriber that is not there yet.
                    // With no Activity there is nothing to render into and no record that anything was
                    // said. To be seen at all, a give-up notice has to reach somewhere that survives the
                    // UI: the media notification, the playback error state the session already exposes
                    // (stop() preserves getPlayerError(), which is how the phone shows "Login" on the
                    // login-required path), or Crashlytics for the after-the-fact case.
                    //
                    // The breaker branch has an out the end-of-queue branch does not: it calls
                    // reportAndResetConsecutiveSkips, which does reach Crashlytics. End-of-queue reports
                    // nothing anywhere.
                    if (consecutiveUnavailableSkips >= maxConsecutiveUnavailableSkips) {
                        reportAndResetConsecutiveSkips(player.currentMediaItem?.extensionId, "pause")
                        player.pause()
                        return@withContext
                    }
                    // hasNextMediaItem() compares the inner full index against the full count — a
                    // correct end-of-queue guard.
                    //
                    // ⚠️ DELIBERATE END-OF-QUEUE BEHAVIOUR — do not "fix" the pause away. Introduced with
                    // the watchdog itself in 22a3c064 (2026-05-10, "Fix infinite STATE_BUFFERING on
                    // session restore: … add 20s buffering watchdog"), whose diff shows this same
                    // `pause(); return` pair from the first version; 8256ca3b (2026-06-29) only replaced
                    // the predicate, because the old `currentMediaItemIndex < mediaItemCount - 1` mixed a
                    // WINDOWED index with a FULL count and was always true past 50 items.
                    // Not skipping when there is nothing to skip to is correct and stays. What is
                    // incidental to that intent — and is the part worth changing — is the state it leaves
                    // behind, described above.
                    //
                    // Note this branch is reachable BY CONSTRUCTION on a one-item timeline, which is what
                    // both 2026-09-05 reports had. It is NOT what produced them: media3's
                    // StuckBufferingDetector requires playWhenReady to count at all, so a stall parked by
                    // this pause() is invisible to it, and those reports each carry a full 600s window.
                    if (!player.hasNextMediaItem()) {
                        player.pause()
                        return@withContext
                    }
                    if (isAndroidAutoConnected()) {
                        player.pause()
                        delay(50)
                    }
                    // Cross-cancel the error-driven skip so the two skip triggers can't both advance this
                    // one stuck track (this watchdog IS the bufferingWatchdog job; launchInvoluntarySkip
                    // cancels it in the reverse direction).
                    involuntarySkipJob?.cancel()
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
        // RUNTIME class names of the media3 datasource close() cascade, resolved from the classes
        // themselves so each string carries whatever R8 renamed (or merged) it to in this build. Compared
        // against StackTraceElement.className, which is also a runtime name — see the long note at the
        // isDataSourceTeardownRace check for why literal source strings cannot work here.
        // The set spans the whole cascade because inlining moves the throwing frame: the 1052 report had
        // SimpleCache.commitFile inlined into CacheDataSink, so a SimpleCache-only match found nothing.
        // DataSourceUtil is deliberately EXCLUDED: R8 merged it into a class that also hosts Ac4Util,
        // HctSolver and SntpClient, so matching its runtime name could swallow unrelated ISEs.
        private val dataSourceRuntimeClassNames: Set<String> = setOf(
            CacheDataSource::class.java.name,
            CacheDataSink::class.java.name,
            TeeDataSource::class.java.name,
            ResolvingDataSource::class.java.name,
            StatsDataSource::class.java.name,
            SimpleCache::class.java.name,
        )

        private const val BUFFERING_WATCHDOG_MS = 5_000L
        // Cold-start re-seek belt: only re-apply the saved position if the current position is still at the
        // start. A user seek before the first STATE_READY moves it past this, and we leave their choice.
        private const val RESTORE_SEEK_BELT_MS = 1_000L
        // Durable-position snapshot interval. POSITION is otherwise written only on discrete events
        // (seek/pause/transition), so a straight-through song never refreshes it after the transition wrote 0
        // at song start — a reboot/OS-kill/crash then resumes at 0. 5s loses at most ~5s of position and
        // writes a tiny POSITION-only file at most 12×/min while playing (negligible).
        private const val POSITION_SAVE_INTERVAL_MS = 5_000L
        // How long the buffering watchdog defers while a stream resolution is IN FLIGHT (any item, cold or
        // mid-queue — see the gate in armBufferingWatchdog; cold start is one instance, not the whole of it).
        //
        // ≥ Deezer stream-resolution ceiling: DeezerApi clientNP connect 15s + read 10s;
        // getContentLength 10s. If clientNP ever gains a callTimeout, anchor to that instead.
        //
        // ⚠️ MUST STAY BELOW StreamableLoader's withTimeout(30_000). That ordering is what keeps the
        // widened gate bounded: a hung resolve loses the grace at 25s and the watchdog returns, and even if
        // it did not, the loader throws TimeoutCancellationException at 30s onto the isTimeout error path.
        // Two independent ceilings; raising this above 30s would remove the first and leave only the second.
        private const val RESOLVE_GRACE_MS = 25_000L
        // Bounds for enriched skip-cause reporting (safeCause) — keep the Crashlytics non-fatal small and
        // spiral-proof: per-cause detail is capped, and only maxConsecutiveUnavailableSkips (3) causes are
        // ever joined, so lastCauses stays ~a few hundred chars regardless of how nested a message is.
        // 140 -> 200 (2026-08-29). safeCause now also carries probeDetail() on the ERROR path, where it
        // competes with code/ext/cls/http/msg for the budget; .take() truncates from the END, so at 140 a
        // long message lost its tail — and the message is what identifies the fault.
        //
        // 80/200 -> 256/400 (2026-09-01). ⚠️ THE TWO MUST BE RAISED TOGETHER. The message is nested INSIDE
        // the cause: safeCause builds `listOfNotNull(code, ext, cls, detail, http, msg).joinToString(" ")`
        // and then `.take(MAX_CAUSE_LEN)`, so raising MAX_MSG_LEN alone changes nothing — MAX_CAUSE_LEN
        // clips the joined string regardless. That is what made Cached's wrong-item guard unreadable: it
        // reports BOTH ids ("expected X, got Y"), but the reports arrived cut at 80 chars, mid-percent-
        // escape, before ", got" was ever reached — so it read as though only the expected id was logged.
        // Sizing: the guard now elides each id to 95 chars (Cached.idForMessage), giving a message of
        // ~240; MAX_MSG_LEN 256 clears that, and MAX_CAUSE_LEN 400 leaves ~140 for code/ext/cls/detail/
        // http alongside it on the ERROR path. 3 x 400 = 1200 chars for lastCauses.
        // THIS IS A SELF-IMPOSED BUDGET, NOT AN EXTERNAL LIMIT. Verified 2026-08-29: nothing parses these
        // strings, and lastCauses reaches Crashlytics only inside the HealthException MESSAGE — no custom
        // key carries it (the keys are health_report_type / throwing_extension_id / extension_id /
        // player_state / is_playing, all short), so Crashlytics'''s 1024-char CUSTOM KEY limit is not the
        // binding constraint here. The one place the message is reused is report()'''s dedupe signature
        // (simpleName + message); for ConsecutiveSkipException that is Scope.MEMORY_ONLY, i.e. a HashMap
        // key with no length limit, and a longer string does not change the signature CARDINALITY because
        // identical content still produces an identical signature. Safe to move again if needed.
        // Bounds the cause-chain walk in skipFamilyOf. A self-referential or pathologically nested chain
        // must not spin on a per-skip path; 16 is far past anything real (the deepest observed is a
        // PlaybackException -> AppException -> IOException -> ErrnoException at 4).
        private const val MAX_CAUSE_DEPTH = 16
        private const val MAX_MSG_LEN = 256
        private const val MAX_CAUSE_LEN = 400
        // Extension display names are author-declared and unbounded; cap independently so a long one can't
        // eat the MAX_CAUSE_LEN budget the errorCodeName/type/message actually need.
        private const val MAX_EXT_NAME_LEN = 24
    }

    private val maxRetries = 3
    private val maxSingleItemRetries = 1
    private var currentRetries = 0
    private var last: KClass<*>? = null

    private val maxConsecutiveUnavailableSkips = 3
    private var consecutiveUnavailableSkips = 0
    // The safeCause() of each skip in the current run, oldest→newest, bounded to maxConsecutiveUnavailableSkips.
    // Moves in lockstep with consecutiveUnavailableSkips AND recentSkipFamily below: recordSkip()
    // advances all three, resetConsecutiveSkips() clears all three — they are never mutated apart, so a
    // reported run can never carry a cause, a count or a family from a previous run.
    private val recentSkipCauses = ArrayDeque<String>()

    /**
     * Highest-precedence [SkipFamily] seen in the current run, which picks the HealthException class at
     * the trip. Crashlytics groups on the class, so this is what lets a permanently-mutable family be
     * silenced without silencing the ones being watched.
     *
     * PRECEDENCE, not last-wins: a run of three skips can mix causes, and the enum is ordered so
     * `maxOf` keeps the most decision-relevant one. Internal beats Unavailable beats Network beats Error
     * beats Stall — an app-side guard always surfaces, an explicit refusal beats a transport symptom, and
     * only a genuinely unrecognised throwable keeps the residual name.
     *
     * A FAMILY, NOT A BOOLEAN, and not a scan of recentSkipCauses: deriving it by matching the joined
     * string would reintroduce exactly the message-parsing fragility HealthException's `val` fields exist
     * to remove. Third member of the reset trio — consecutiveUnavailableSkips, recentSkipCauses and this
     * are mutated together or a report carries one run's count with another run's family.
     */
    private var recentSkipFamily = SkipFamily.Stall

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
    // Serializes the involuntary auto-skip coroutine (error-driven skip-to-next). A 403 cascade fires an
    // auto-skip per failed track; without this guard those pause->delay->skip->prepare->play coroutines
    // could stack and over-skip. launchInvoluntarySkip() cancels any prior in-flight skip AND the
    // buffering watchdog (the other skip trigger) so at most one involuntary skip is pending. Cancel only
    // lands at the delay(50) suspension — everything after it is synchronous on Main — so a cancelled
    // coroutine never advanced, hence no over-skip and no dropped skip (the latest trigger always skips).
    private var involuntarySkipJob: Job? = null
    private fun launchInvoluntarySkip(body: suspend CoroutineScope.() -> Unit) {
        bufferingWatchdog?.cancel(); bufferingWatchdog = null
        involuntarySkipJob?.cancel()
        involuntarySkipJob = scope.launch(Dispatchers.Main, block = body)
    }
    // Resolve grace timer, keyed to the CURRENT ITEM: restarts when the current mediaId changes (a new
    // buffering episode) and persists across watchdog re-arms of the same item. Was named for cold start,
    // but it was never cold-specific — the rekey is on mediaId change, so it has always measured per item.
    // That is what lets the 2026-09-04 widening cover the advance path with no change to this timer.
    //
    // Keyed by mediaId rather than reset via player callbacks, so it survives the onMediaItemTransition /
    // PLAYLIST_CHANGED events that fire as part of the cold-restore setMediaItems. That June 30 reasoning
    // is about not resetting FROM CALLBACKS and is untouched by the widening.
    //
    // ⚠️ THE ONE REAL COST OF WIDENING, recorded so it is not rediscovered as a new fault:
    // activeLoadCount (PlayerState) is a GLOBAL counter incremented per prepareSourceInternal, not per
    // item. So an unrelated concurrent resolve can suppress the watchdog for a genuinely stuck CURRENT
    // item. Bounded at RESOLVE_GRACE_MS by the window, and the window restarts per item, so it cannot
    // compound across tracks. The counter's global nature was understood when the gate was built — the
    // guarantee has always been "activeLoadCount reaches zero, or the cap expires", which is the same
    // reasoning the report branch relies on ("loads=1+ can ONLY happen after RESOLVE_GRACE_MS elapsed").
    //
    // HOW NARROW THAT IS, in this app specifically: nothing prefetches. PreloadConfiguration was scoped on
    // Aug 1, found to be buffering-only, and never shipped — there is no call to it in the tree. The only
    // producer of a concurrent resolve is ExoPlayer preparing the NEXT period ahead of a transition, and it
    // does that off the current period's buffered position — so an item that has buffered nothing never
    // gets a neighbour prepared, and the `loaded=true buf=0` CDN stall still trips at 5s with loads=0.
    // The suppressible case therefore needs a coincidence: the current item buffers some, then stalls
    // mid-stream, WHILE the next item's resolve is slow. Narrow, but not theoretical — do not delete this
    // note on the grounds that it cannot happen.
    private var resolveGraceStart = 0L
    private var resolveGraceMediaId: String? = null
    private var retriedMediaId: String? = null
    private var retriedWatchdogCount = 0
    private val maxWatchdogRetries = 1
    // Item of the PREVIOUS watchdog skip in the current breaker run - the only thing that can tell three
    // trips on three tracks from three trips on ONE. Cleared in resetConsecutiveSkips() so it moves in
    // lockstep with recentSkipCauses and never compares across runs. NOT foldable into retriedMediaId:
    // that one is cleared at the top of this same branch (it scopes the per-track RETRY, not the run).
    private var lastWatchdogSkipMediaId: String? = null

    // PROBE (2026-08-29) - the two faults the 1059 captures separated, neither of which the existing
    // detail fields can see. REMOVE WITH THE PROBE.
    //
    // FAULT 1, the advance that was never queued. MediaPeriodQueue.shouldLoadNextMediaPeriod():209-215 is
    //   loading == null || (!loading.info.isFinal && loading.isFullyBuffered()
    //                       && loading.info.durationUs != C.TIME_UNSET && length < MAX_BUFFER_AHEAD)
    // There is NO LoadControl term and NO allocator term in it - which is what refuted the allocator
    // hypothesis before it cost a build. A track played for 131 seconds and emitted no prepareSourceInternal
    // for the next item at all, so that gate was shut for the whole track. Of its two candidate terms,
    // durationUs == C.TIME_UNSET is the one a stream property could explain. `dur` reads it.
    //
    // FAULT 2, the forced retry that never started. queue.clear() nulls `loading`, so after player.stop()
    // the gate reopens via the first branch and a holder IS enqueued and prepared - yet nine createPeriod
    // calls produced zero opens. `opens` is the observable that separates this from fault 1.
    //
    // `mime` tests whether fault 1 is per-stream: FLAC carries total samples in STREAMINFO so duration is
    // always known, while MP3 without a Xing/VBRI header needs a content length to divide. If failures are
    // MP3 and successes are FLAC, the duration hypothesis holds; if both containers appear on both sides it
    // is refuted, and `opens` still answers fault 2. media3-authored, never extension text - no scrubbing.
    //
    // Sampled at STATE_READY, NOT at the watchdog tick: by the tick the player has been stopped and
    // re-prepared repeatedly and the reading describes the wreckage, not the state that shut the gate.
    private var lastReadyDurationKnown: Boolean? = null
    private var bytesReadAtItemStart = 0L
    private var openCountAtItemStart = 0

    // The three probe fields rendered for a detail string. ONE helper, called from EVERY recordSkip site,
    // because the fields were originally built inline in the buffering watchdog and therefore reported on
    // the watchdog/breaker path ONLY. The error path has six recordSkip sites of its own (404, socket,
    // network, the missing-file/401/malformed/timeout family, maxRetries, per-item retries) and every one
    // of them passed detail = null, so a stall that surfaced through onPlayerError carried no probe data at
    // all - the instrument was blind on exactly the path a StuckPlayerDetector report takes.
    // Cheap and side-effect free: three field reads and an AtomicInteger get. REMOVE WITH THE PROBE.
    /**
     * What was true when media3's StuckPlayerDetector gave up. Read alongside probeDetail's fields, which
     * are appended verbatim.
     *
     * ⚠⚠ [2026-09-16] FIRST POPULATED REPORT. THE FINDING IS A CONTRADICTION INSIDE THE
     * FIELD SET, AND IT LEADS BECAUSE EVERYTHING ELSE BELOW IS NOW SETTLED CONTEXT FOR IT:
     *   type=buffering-no-progress to=60000 wd=live pwr=true state=1 items=3132 next=yes
     *   bufAhead=0 totalBuf=0 loads=1 graceAge=101900 wdRetries=0 errRetries=0
     *   dur=? opens=0 bytes=0
     * READ: RESOLVE_GRACE_MS is 25_000, so at graceAge=101900 the resolve-in-flight suppression
     * gate at armBufferingWatchdog (`activeLoadCount() > 0 && graceAge < RESOLVE_GRACE_MS`) was
     * FALSE. The body should have fallen through to the retry branch, which sets
     * retriedWatchdogCount = 1. With BUFFERING_WATCHDOG_MS at 5_000 and 102s of grace age that
     * should have happened about fifteen times over. wdRetries=0 says it never happened ONCE.
     * Two candidates, NEITHER SEPARABLE FROM THIS FIELD SET:
     *   (a) A PLAYLIST_CHANGED DURING THE STALL. READ: onTimelineChanged resets retriedMediaId and
     *       retriedWatchdogCount to 0 on TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED and cancels the
     *       watchdog, WHILE resolveGraceStart keeps running - it is rekeyed on the current mediaId
     *       CHANGING, not on the timeline. That asymmetry produces exactly wdRetries=0 beside a
     *       large graceAge. INFERRED: that it actually fired here. items=3132 makes a queue rebuild
     *       plausible and nothing in the field set records one.
     *   (b) THE BODY WAS STARVED - the watchdog's `withContext(Dispatchers.Main)` never ran, so the
     *       Job stayed active (wd=live) with its body unexecuted. WEAKENED BUT NOT KILLED: the
     *       detector itself fires on the main looper and did fire, so the thread was alive.
     * WHAT WOULD SEPARATE THEM: a PLAYLIST_CHANGED count, or the age of the last watchdog arm.
     * Neither exists yet - do not add both blind, pick after reading loadAge on the next report.
     *
     * ⚠️ AND state=1 (STATE_IDLE) IS NOT A CLUE - DO NOT SPEND A ROUND ON IT. Any playback
     * error transitions ExoPlayer to STATE_IDLE and StuckPlayerException arrives through the error
     * path, so the state has already moved by the time this samples. pwr=true also means
     * ShufflePlayer.getPlaybackState's fake-READY masking (IDLE && items>0 && !playWhenReady) did
     * not apply, so this is the real state. (The error -> IDLE transition is INFERRED from media3's
     * documented behaviour, not re-read from ExoPlayerImpl for this report.)
     *
     * ⚠️ THE 60s THRESHOLD AND graceAge=102s ARE NOT IN CONFLICT, and the reconciliation an
     * older record asked for is DONE, NOT OPEN: PlayerService sets
     * setStuckBufferingDetectionTimeoutMs(60_000) with the reasoning at that call site - 60s was
     * chosen to clear BOTH RESOLVE_GRACE_MS (25_000) and StreamableLoader's withTimeout(30_000), so
     * a 30s threshold would race two error paths onto one item. The two numbers measure DIFFERENT
     * THINGS FROM DIFFERENT ORIGINS: graceAge is time-since-this-item-became-current, while the
     * detector counts time-since-buffered-position-last-changed AND RESTARTS ITS CLOCK on any change
     * of period uid or buffered position. An item can be current for 102s and have last progressed
     * 60s ago. Anything that still reads "600s -> ~30s proposed, not built" is stale.
     *
     * ⚠️ STATE THE EXPECTED VALUES BEFORE READING THE FIRST REPORT, or the numbers are data rather than
     * evidence. The question this exists to settle is WHY OUR OWN 5s WATCHDOG DID NOT ACT — both
     * 2026-09-05 reports were 600s of unchanged buffered position, and BUFFERING_WATCHDOG_MS is 5_000,
     * so a live watchdog would have retried twice and skipped or paused inside ~15s. Three outcomes,
     * each falsifying the others:
     *   wd=null pwr=true   -> NO WATCHDOG WAS ARMED while the detector's own preconditions held. The
     *                         fault is in ARMING: an arm site never fired, or the job was cancelled with
     *                         no following state transition to re-arm it (the BUFFERING -> BUFFERING
     *                         non-event described at onPlayWhenReadyChanged). This is the predicted case.
     *   wd=live pwr=true   -> the watchdog WAS running and did not fix it. Arming is fine; the fault is
     *                         inside the watchdog body — most likely the resolve-in-flight re-arm at
     *                         armBufferingWatchdog, in which case loads>0 will corroborate.
     *                         ⚠⚠ THIS BRANCH WAS SELECTED [2026-09-16], WITH ITS OWN STATED
     *                         CORROBORATION: wd=live AND loads=1. The prediction above was written
     *                         before any report existed and picked both the branch and its secondary
     *                         signal, so read it as settled rather than as an open question.
     *                         WHAT IT RULES OUT: (i) ARMING - no arm site failed, and the
     *                         BUFFERING -> BUFFERING non-event at onPlayWhenReadyChanged is not the
     *                         mechanism; (ii) THE END-OF-QUEUE BRANCH, twice over - next=yes and
     *                         items=3132, where BOTH September reports had one-item timelines;
     *                         (iii) the model itself - pwr=true, so the "impossible" branch did not
     *                         appear. The live fault is in the BODY; see the contradiction at the
     *                         top of this block for where it now sits.
     *   pwr=false          -> IMPOSSIBLE as written, and its appearance falsifies the model rather than
     *                         the code: StuckBufferingDetector.update (media3 1.11.0) requires
     *                         playWhenReady before it will count at all. Seeing it means the flag changed
     *                         between the detector's last sample and this line.
     * `next` is the other half of the pair: both reports had a ONE-ITEM timeline, and next=no is the
     * input that makes the watchdog's end-of-queue branch reachable. next=yes with wd=null would rule
     * that branch out entirely.
     *
     * ⚠⚠ loadAge - ADDED 2026-09-16 BECAUSE `loads` ALONE CANNOT DECIDE ANYTHING. loads=1
     * says a resolve is outstanding and says NOTHING about how long, so it is equally consistent with
     * "the loader's withTimeout(30_000) has not had a chance yet" and "the timeout should have fired
     * and did not" - OPPOSITE CONCLUSIONS FROM THE SAME OUTPUT, which is what stopped the first
     * populated report from being decisive. loadAge is ms since PlayerState.loadEpisodeStartMs, the
     * 0 -> 1 edge of activeLoadCount, and -1 when nothing is outstanding.
     * STATED BEFORE THE FIRST READING, so the next report is evidence rather than data:
     *   loadAge=-1 with loads=0    -> nothing outstanding; the stall is NOT in resolution and the
     *                                 whole resolve line is the wrong place to look.
     *   loadAge < 30000            -> A LATE-STARTED LOAD. The timeout is FINE and simply has not
     *                                 expired; the fault is that a new resolve began this late into
     *                                 the stall, which points back at the watchdog contradiction
     *                                 above rather than at StreamableLoader.
     *   loadAge > 30000            -> THE TIMEOUT DID NOT FIRE, AND THAT IS A DEFECT IN OUR CODE.
     *                                 withTimeout is COOPERATIVE - it throws only at a suspension
     *                                 point - so a resolve blocking without one holds past 30s with
     *                                 the cancellation pending. Note the count itself already proves
     *                                 the timeout had not fired at sample time: StreamableMediaSource
     *                                 wraps the call in runCatching, which catches Throwable
     *                                 including TimeoutCancellationException, and its finally
     *                                 decrements - so loads could not still read 1 afterwards.
     *   loadAge > 60000            -> the same defect, and it alone explains the whole stall.
     * loadAge dates the EPISODE, not one load - see PlayerState.loadEpisodeStartMs for why, and for
     * the over-reporting direction that choice deliberately accepts.
     *
     * Raw numbers are fine here — this string feeds a Crashlytics key, not report()'s dedupe signature.
     */
    private fun stuckDetail(e: StuckPlayerException): String {
        val type = when (e.stuckType) {
            StuckPlayerException.STUCK_BUFFERING_NOT_LOADING -> "buffering-not-loading"
            StuckPlayerException.STUCK_BUFFERING_NO_PROGRESS -> "buffering-no-progress"
            StuckPlayerException.STUCK_PLAYING_NO_PROGRESS -> "playing-no-progress"
            StuckPlayerException.STUCK_PLAYING_NOT_ENDING -> "playing-not-ending"
            StuckPlayerException.STUCK_SUPPRESSED -> "suppressed"
            else -> "unknown(${e.stuckType})"
        }
        val graceAge = System.currentTimeMillis() - resolveGraceStart
        return "type=$type to=${e.timeoutMs} " +
            // wd is THE field. Everything else is context for whichever branch it selects.
            "wd=${if (bufferingWatchdog?.isActive == true) "live" else "null"} " +
            "pwr=${player.playWhenReady} state=${player.playbackState} " +
            "items=${player.mediaItemCount} next=${if (player.hasNextMediaItem()) "yes" else "no"} " +
            "bufAhead=${player.bufferedPosition - player.currentPosition} " +
            "totalBuf=${player.totalBufferedDuration} loads=${activeLoadCount()} " +
            "loadAge=${loadEpisodeStartMs().let { if (it == 0L) -1L else System.currentTimeMillis() - it }} " +
            "graceAge=$graceAge wdRetries=$retriedWatchdogCount errRetries=$currentRetries " +
            probeDetail()
    }

    private fun probeDetail(): String {
        val opens = (StreamableDataSource.openCount.get() - openCountAtItemStart).coerceAtLeast(0)
        val dur = when (lastReadyDurationKnown) {
            true -> "set"
            false -> "unset"
            null -> "?"
        }
        // Bucketed, not absolute: the question is "did bytes arrive", not how many, and a raw count would
        // be a continuous value in a string that feeds report()'s dedupe signature - the trap the whole
        // field set is built to avoid. Thresholds chosen against what the fault actually needs: an audio
        // extractor declares its tracks from a few KB of header, so 64k already exceeds anything
        // preparation could be waiting on, and 1M+ with nothing prepared is unambiguous.
        //   0     -> the source opened and delivered nothing: the connection is the fault
        //   <64k  -> trickling: too little to prepare, consistent with a dying connection
        //   <1M   -> substantial delivery; not a connection problem
        //   1M+   -> plenty arrived and the player still never prepared: the fault is downstream of
        //            delivery, in the extractor/prepare path (see StreamableDataSource.bytesRead)
        // Deliberately NOT expressed as a fraction of the source length: that would need the length
        // threaded out of open(), and the discrimination being asked for does not need it - a megabyte
        // with nothing prepared already settles it.
        val delta = (StreamableDataSource.bytesRead.get() - bytesReadAtItemStart).coerceAtLeast(0L)
        val bytes = when {
            delta == 0L -> "0"
            delta < 64 * 1024 -> "<64k"
            delta < 1024 * 1024 -> "<1M"
            else -> "1M+"
        }
        return "dur=$dur opens=${if (opens > 1) "2+" else "$opens"} bytes=$bytes"
    }
    private var retried404MediaId: String? = null
    private var retriedSocketMediaId: String? = null
    // Retry BUDGET for the network-down branch, not a per-track latch. It was a single mediaId compared
    // only against the current one, which cannot bound retries ACROSS tracks: alternating A -> B -> A the
    // id never matches the previous, so every track retried every time it came round and the hold was
    // never reached. STATE_READY is the only reset (see the block that clears retried404/retriedSocket),
    // and while the network is down STATE_READY never fires - so the old form was an unbounded retry
    // storm for exactly as long as the outage lasted. A counter bounds the outage, not the track.
    private var networkRetryCount = 0
    private val maxNetworkRetries = 2

    // Chain-walk, NOT rootCause. `rootCause` (Serializer.kt:35) is the DEEPEST node, and Android's real
    // network chains bottom out in PLATFORM types: UnknownHostException -> android.system.GaiException,
    // and ConnectException -> android.system.ErrnoException. So `rootCause is UnknownHostException` and
    // `rootCause is SocketException` were BOTH always false, and the DNS-hold and socket-retry branches
    // in onPlayerError have never fired since they were written. Build 1037 proved it: the recorded skip
    // causes read "GaiException android_getaddrinfo failed: EAI_NODATA" and "ErrnoException isConnected
    // failed: ECONNREFUSED" — exactly the nodes rootCause resolves to.
    // ⚠️ PATTERN: never type-check a wrapped exception against a non-chain-walking accessor.
    // Moved to file level (bottom of this file) 2026-09-11 so PlayerRadio's YTM throw-bridge can classify
    // an exception that toAppException has wrapped, instead of a second copy of the same three lines.

    // The ONLY way to advance the breaker: increments the counter and records this skip's cause together,
    // so they cannot drift. Called at every skip site; never at the exempt (5xx / removed-extension) sites,
    // which do not skip. cause == null for the buffering watchdog (a stuck resolve, no error object).
    private fun recordSkip(
        cause: Throwable?, playbackError: PlaybackException? = null, detail: String? = null
    ) {
        consecutiveUnavailableSkips++
        recentSkipCauses.addLast(safeCause(cause, playbackError, detail))
        // maxOf, so the most decision-relevant cause in a mixed run survives — see recentSkipFamily.
        recentSkipFamily = maxOf(recentSkipFamily, skipFamilyOf(cause, playbackError))
        if (recentSkipCauses.size > maxConsecutiveUnavailableSkips) recentSkipCauses.removeFirst()
    }

    // The ONLY way to clear the breaker: zeroes the counter and the causes together. Both reset points
    // (STATE_READY and the trip) go through here, so the two fields always reset atomically.
    private fun resetConsecutiveSkips() {
        consecutiveUnavailableSkips = 0
        recentSkipCauses.clear()
        recentSkipFamily = SkipFamily.Stall
        lastWatchdogSkipMediaId = null
    }

    // Enriched skip-cause detail (build-985 "lastCauses=Exception,Exception,Exception" was uselessly bare —
    // it stored only the class simpleName, hiding the real reason). Now carries, most-diagnostic first:
    //   • the Media3 PlaybackException errorCodeName (ERROR_CODE_IO_BAD_HTTP_STATUS / _PARSING_ / etc.) —
    //     the true IO-vs-parse-vs-source discriminator, previously not captured at all;
    //   • the OWNING extension's name (ext:<name>), walked off the AppException in the chain;
    //   • the exception type + HTTP responseCode (401/403/404 = token/auth vs missing);
    //   • the DEEPEST cause's message, URL-STRIPPED and length-capped.
    // Message handling is the security-sensitive part: Media3 embeds the signed CDN URL (token/hmac) in the
    // raw message, so any URL is replaced with <url> and the whole thing is hard-capped — never emitted raw.
    // Bounded by construction (MAX_CAUSE_LEN per cause × 3 causes) so a nested message can't spiral.
    //
    // ext:<name> closes the Unified attribution blind spot. ConsecutiveSkipException's extensionId comes from
    // the MediaItem, which for a Unified-browsed track is "unified" — while the actual failure came from a
    // SUB-extension. UnifiedExtension.client wraps with the sub-extension's Metadata and toAppException
    // returns an existing AppException as-is, so the sub-extension's identity IS in the chain; it was simply
    // never read. deepestSafeMessage() can't recover it either: it walks to the DEEPEST message (the raw
    // "Error 403"), skipping AppException.Other's "Error 403 error in Spotify". Placed early so it survives
    // the MAX_CAUSE_LEN truncation — attribution is the part that was missing entirely.
    // Same scrub/cap treatment as the message: the name is an author-declared third-party string, so it is
    // never emitted raw, and its own cap keeps it from eating the budget the real diagnosis needs.
    // `detail` is caller-supplied context for a skip that has NO exception to describe -- today only the
    // buffering watchdog, whose recordSkip(null) otherwise renders as the bare word "StuckBuffering". It is
    // placed immediately after the class name because it QUALIFIES it; the http/msg fields are always null
    // on that path, so nothing is displaced. Callers must keep it low-cardinality -- see the note at the
    // watchdog's call site for why (report()'s dedupe signature includes this string).
    /**
     * Skip families, ORDERED BY PRECEDENCE — `maxOf` over a run keeps the later entry. See
     * [recentSkipFamily] for why precedence and not last-wins.
     */
    private enum class SkipFamily { Stall, Error, Network, Unavailable, Internal }

    /**
     * Which family a single skip belongs to.
     *
     * ⚠️ EVERY TEST HERE IS AGAINST A TYPE WE OWN, THE JDK OWNS, OR MEDIA3 OWNS. Never a third-party
     * class name, never a message string. We know the built-in extensions; we do not know the
     * third-party universe, and there are dozens of extensions nobody here has read. A rule like
     * `cause::class.simpleName == "TrackUnavailableException"` classifies correctly for the extensions
     * that happen to have been seen and silently misfiles every other one — and misfiling INTO a muted
     * family is invisible, which is the worst direction for this to fail in.
     *
     * That is why PlaybackException.errorCode carries so much of the load: Media3 assigns it, so it means
     * the same thing whoever wrote the extension. An extension that throws its own type with no Media3
     * code and no recognised cause in the chain lands in [SkipFamily.Error], the residual family — which
     * is the correct answer, not a gap. An unanticipated cause SHOWING UP as unanticipated is the signal;
     * guessing it into Unavailable would mute a fault nobody has looked at yet.
     *
     * INTERNAL MATCHES NAMED TYPES ONLY. `is IllegalStateException` would route every unrelated app-side
     * throw into the one bucket that is actually read. A guard earns Internal by being given a name.
     */
    private fun skipFamilyOf(cause: Throwable?, playbackError: PlaybackException?): SkipFamily {
        // recordSkip(null) with no PlaybackException is the buffering watchdog — no throwable at all.
        if (cause == null && playbackError == null) return SkipFamily.Stall

        val chain = generateSequence(cause) { it.cause }.take(MAX_CAUSE_DEPTH).toList()

        // 1. OURS. Named guards only — see the note above.
        if (chain.any { it is WrongItemException }) return SkipFamily.Internal

        // 2. REFUSED OR GONE. Our own unavailable types, Media3's HTTP/permission/not-found codes, and a
        //    4xx from Media3's own datasource exception. A 4xx is the source declining, not a transport
        //    problem, so it must be tested before the network branch.
        if (chain.any { it is MediaUnavailableException || it is ExtensionNotFoundException })
            return SkipFamily.Unavailable
        when (playbackError?.errorCode) {
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE -> return SkipFamily.Unavailable
        }
        val http = chain.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()
        if (http != null && http.responseCode in 400..499) return SkipFamily.Unavailable

        // 3. TRANSPORT. JDK/kotlinx types plus Media3's connection codes. SocketException is deliberately
        //    absent for the same reason ErrorCategory.classify omits it: a mid-stream reset is a
        //    per-track transient, not a connectivity failure, and lumping it here would hide it.
        if (chain.any {
                it is SocketTimeoutException || it is TimeoutCancellationException ||
                    it is ConnectException || it is UnknownHostException || it is NoRouteToHostException
            }) return SkipFamily.Network
        when (playbackError?.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> return SkipFamily.Network
        }

        // 4. RESIDUAL. Recognised as a real throwable, not recognised as anything in particular.
        return SkipFamily.Error
    }

    private fun safeCause(
        cause: Throwable?, playbackError: PlaybackException? = null, detail: String? = null
    ): String {
        val code = playbackError?.errorCodeName
        val ext = playbackError?.appExtensionName()?.let { "ext:$it" }
        val cls = cause?.let { it::class.simpleName ?: "Unknown" } ?: "StuckBuffering"
        val http = (cause as? HttpDataSource.InvalidResponseCodeException)?.let { "HTTP ${it.responseCode}" }
        val msg = cause?.deepestSafeMessage()
        return listOfNotNull(code, ext, cls, detail, http, msg).joinToString(" ").take(MAX_CAUSE_LEN)
    }

    // Name of the extension that OWNS this failure: the first AppException in the chain (ExtensionUtils.get
    // wraps every extension call, so one is present for any extension-sourced error). Walked from the full
    // PlaybackException, not from rootCause — rootCause is the DEEPEST node and the AppException sits above
    // it. Null when no extension was involved (pure Media3 data-source failure, or the buffering watchdog's
    // recordSkip(null)), in which case the emitted string is byte-identical to before this field existed.
    private fun Throwable.appExtensionName(): String? {
        var t: Throwable? = this
        while (t != null) {
            (t as? AppException)?.let { return it.extension.name.scrubbed(MAX_EXT_NAME_LEN) }
            t = t.cause
        }
        return null
    }

    // Deepest non-null message in the cause chain, URL-stripped (signed-CDN-token guard) and capped. Returns
    // null if every message is null (the type alone then carries the cause).
    private fun Throwable.deepestSafeMessage(): String? {
        var t: Throwable? = this
        var last: String? = null
        while (t != null) {
            t.message?.let { last = it }
            t = t.cause
        }
        return last?.scrubbed(MAX_MSG_LEN)
    }

    // The shared guard for any third-party string that reaches Crashlytics: strip URLs (signed CDN tokens
    // live in them) and hard-cap. Extracted so the extension name gets exactly the same treatment as the
    // message rather than a second, drifting copy of the rule.
    private fun String.scrubbed(max: Int) =
        replace(Regex("https?://\\S+"), "<url>").trim().take(max)

    // Single convergence point for EVERY breaker trip, so one log line here covers all call sites. `outcome`
    // is the caller's intent ("stop" for the error paths, "pause" for the buffering watchdog) — the two end
    // in different player states and therefore different session/notification behaviour, and the log is the
    // only way to tell them apart after the fact. It cannot be derived here: player.playbackState at this
    // moment is the PRE-action state (already IDLE from the error), not the resulting one.
    // Logged BEFORE resetConsecutiveSkips(), which zeroes both the count and the causes.
    private fun reportAndResetConsecutiveSkips(extensionId: String?, outcome: String) {
        Log.d(
            "GladixPlayback",
            "Consecutive-skip breaker TRIPPED after $consecutiveUnavailableSkips skips " +
                "(ext=${extensionId ?: "unknown"}, outcome=$outcome): " +
                recentSkipCauses.joinToString(" | ")
        )
        // Family picks the CLASS, which is what Crashlytics groups on — a stall storm and a real error no
        // longer share an issue. Both carry the identical message, so lastCauses and its probe fields are
        // unchanged and the dedupe partitioning is the same as before the split.
        val causes = recentSkipCauses.joinToString(",")
        val skips = consecutiveUnavailableSkips
        val ext = extensionId ?: "unknown"
        healthMonitor?.report(
            when (recentSkipFamily) {
                SkipFamily.Stall -> HealthMonitor.ConsecutiveSkipStallException(skips, ext, causes)
                SkipFamily.Error -> HealthMonitor.ConsecutiveSkipErrorException(skips, ext, causes)
                SkipFamily.Network -> HealthMonitor.ConsecutiveSkipNetworkException(skips, ext, causes)
                SkipFamily.Unavailable ->
                    HealthMonitor.ConsecutiveSkipUnavailableException(skips, ext, causes)
                SkipFamily.Internal ->
                    HealthMonitor.ConsecutiveSkipInternalException(skips, ext, causes)
            },
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
                recordSkip(rootCause, error, probeDetail())
                if (consecutiveUnavailableSkips >= maxConsecutiveUnavailableSkips) {
                    reportAndResetConsecutiveSkips(mediaItem?.extensionId, "stop")
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

        // Computed HERE, above the socket branch, because ConnectException IS a SocketException: without
        // this precedence ECONNREFUSED would take the retry-then-SKIP path below and advance the breaker,
        // which is exactly the build-1037 outcome we are removing. A network-level failure is never a
        // per-track fault, so it must reach the hold branch instead. Consumed again at the hold branch.
        val isNetworkDown = error.anyCause {
            it is UnknownHostException || it is UnresolvedAddressException ||
                it is ConnectException || it is NoRouteToHostException
        }

        // A mid-stream SocketException (connection reset) stays here deliberately: it IS a per-track
        // transient, so retry-once-then-skip remains right. Only the connection-level subtypes above
        // are diverted.
        val isTransientServerError = !isNetworkDown && error.anyCause { it is SocketException }
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
                recordSkip(rootCause, error, probeDetail())
                if (consecutiveUnavailableSkips >= maxConsecutiveUnavailableSkips) {
                    reportAndResetConsecutiveSkips(mediaItem?.extensionId, "stop")
                    player.stop()
                    return
                }
                val hasMore = player.hasNextMediaItem()
                if (!hasMore) {
                    player.stop()
                    return
                }
                if (isAndroidAutoConnected()) {
                    launchInvoluntarySkip {
                        player.pause()
                        delay(50)
                        skipInvoluntarily()
                        player.prepare()
                        player.play()
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
        // re-prepares and retries). The budget is kept on the hold so a failed resume holds again;
        // it resets in the STATE_READY block on recovery, i.e. only a track that actually PLAYS
        // refills it. Scoped to these two exceptions only, so
        // genuinely-unavailable tracks still skip via the branches above/below.
        // Cache timeout is a WHOLE-APP condition, like a network outage - so it holds, exactly as the
        // network-down branch below does, and for the same reason: skipping cannot help. app.fileCache is a
        // single lazily-started Deferred shared process-wide, so the NEXT track awaits the identical object
        // and will time out identically. Skipping would walk the whole queue silently at 60s a track.
        // Placed ABOVE the generic tail on purpose: with no explicit branch this fell through to it and got
        // the retry-then-skip bookkeeping, i.e. a slow cache produced tracks that quietly skipped - the
        // symptom family this codebase has repeatedly had to chase. No recordSkip, so the consecutive-skip
        // breaker is untouched; a cache stall must not consume the budget that exists for dead tracks.
        // See App.FileCacheTimeoutException for why it carries no cause (attaching one would route it into
        // the silent-skip family via rootCause).
        if (rootCause is App.FileCacheTimeoutException) {
            Log.d("GladixPlayback", "onPlayerError: file cache timed out, holding")
            scope.launch { throwableFlow.emit(PlayerException(mediaItem, rootCause)) }
            player.pause()
            return
        }

        // isNetworkDown is computed above the socket branch (see there for why the ordering matters).
        if (isNetworkDown) {
            val currentMediaId = mediaItem?.mediaId
            if (networkRetryCount < maxNetworkRetries) {
                networkRetryCount++
                Log.d(
                    "GladixPlayback",
                    "onPlayerError: network down for $currentMediaId, " +
                        "retry $networkRetryCount/$maxNetworkRetries"
                )
                val savedIndex = player.currentMediaItemIndex
                val savedPosition = player.currentPosition
                player.stop()
                internalSeek { player.seekTo(savedIndex, savedPosition) }
                player.prepare()
                player.play()
                requestAudioFocus()
            } else {
                Log.d(
                    "GladixPlayback",
                    "onPlayerError: network down, retry budget spent for $currentMediaId, holding"
                )
                scope.launch { throwableFlow.emit(PlayerException(mediaItem, rootCause)) }
                player.pause()
            }
            return
        }

        if (rootCause is TrackUnavailableException || rootCause.message?.contains("not available", ignoreCase = true) == true) {
            recordSkip(rootCause, error, probeDetail())
            if (consecutiveUnavailableSkips >= maxConsecutiveUnavailableSkips) {
                reportAndResetConsecutiveSkips(mediaItem?.extensionId, "stop")
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
                launchInvoluntarySkip {
                    player.pause()
                    delay(50)
                    skipInvoluntarily()
                    player.prepare()
                    player.play()
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
        // ⚠️ TYPE-BASED, AND THAT IS WHY StuckPlayerException MISSES IT. media3's StuckPlayerDetector
        // surfaces through ExoPlayerImpl.onStuckPlayerDetected (:3652) as
        //   stopInternal(ExoPlaybackException.createForUnexpected(exception, ERROR_CODE_TIMEOUT))
        // so it CARRIES ERROR_CODE_TIMEOUT - but that is a PlaybackException CODE, not a Kotlin type, and
        // this check tests the rootCause's type. StuckPlayerException is neither of the two below, so it
        // falls past this branch, past the ExoTimeoutException release check, and into the generic tail.
        // Do not "fix" that by widening this line: matching a code here would pull genuine socket/parse
        // timeouts and the stuck detector into one bucket with one recovery, and they need different ones.
        //
        // WHAT THE GENERIC TAIL THEN DOES, measured on 1059 and contradicting the earlier scoping of a
        // StuckPlayerDetector threshold change: it does NOT consume the retry/skip budget on a first
        // occurrence. The tail emits the non-fatal, does last/currentRetries bookkeeping (currentRetries
        // resets to 0 because the root-cause class changed), falls past BOTH recordSkip gates, and takes
        // the else branch - replaceMediaItem(withRetry) then prepare() + play(). The budget is touched only
        // once the same class repeats to maxRetries, or the item's own retries reach maxSingleItemRetries.
        // It also DOES arm the watchdog: stopInternal drives STATE_IDLE (which cancels the watchdog and
        // abandons focus), and the tail's prepare() then drives STATE_BUFFERING - a real transition. The
        // 1059 timeline confirms it end to end: detector at 10:32:09, breaker trip at 10:32:39, exactly
        // 3 x (5s retry + 5s skip) later.
        // The one caveat that DOES survive: at a lowered threshold it can fire on a genuinely slow cold
        // resolve, so it still needs reconciling with RESOLVE_GRACE_MS = 25_000 before the threshold moves.
        val isTimeout = rootCause is TimeoutCancellationException || rootCause is SocketTimeoutException

        // Benign media3 datasource teardown race — suppressed, but counted. The player/cache is torn down
        // while a load is still closing (the stop()+prepare() churn from the watchdog and skip paths) and
        // one of media3's checkState() lifecycle assertions fires. The throwing frame MOVES around the
        // close() cascade — SimpleCache.commitFile inlined into CacheDataSink one report, TeeDataSource
        // .close the next — which is why this matches a family rather than a single class.
        //
        // ⚠️ MATCH ON RUNTIME NAMES, NEVER ON LITERAL SOURCE NAMES. This guard has been written three
        // times and twice shipped broken:
        //   3c438db6 (Jun 17)  className == SimpleCache::class.java.name   — correct but too narrow:
        //                      R8 INLINES SimpleCache.commitFile into CacheDataSink, so no SimpleCache
        //                      frame exists in the trace at all.
        //   f6464c00 (Jun 23)  className.contains("SimpleCache")           — BROKEN in release.
        //   3707e4c9 (Jul 1)   className.startsWith("androidx.media3.datasource.") — BROKEN in release,
        //                      then deleted as dead code by fe71e813 (Jul 3).
        // proguard-rules.pro has no media3 keep rules, so these classes ARE obfuscated: the frames read
        // xw / zs4 / zw / m04 / gm4 in a release build and no literal string can ever match them.
        // Comparing StackTraceElement.className against Class.getName() compares two RUNTIME names, so it
        // survives both renaming and horizontal merging (a merged class reports its host's name on both
        // sides). Only inlining defeats it, which is why the set spans the whole cascade.
        //
        // message == null is load-bearing twice over: media3's bare checkState(boolean) throws a
        // message-less ISE, and it keeps this disjoint from the is401 branch above, which matches an
        // IllegalStateException carrying "HTTP 401"/"HTTP 403" and must keep its retry.
        val isDataSourceTeardownRace = rootCause is IllegalStateException
            && rootCause.message == null
            && rootCause.stackTrace.any { it.className in dataSourceRuntimeClassNames }
        if (isDataSourceTeardownRace) {
            healthMonitor?.report(
                HealthMonitor.DataSourceTeardownRaceException(rootCause),
                HealthMonitor.Scope.MEMORY_ONLY, 10 * 60 * 1000L
            )
            return
        }

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
                recordSkip(rootCause, error, probeDetail())
                if (consecutiveUnavailableSkips >= maxConsecutiveUnavailableSkips) {
                    reportAndResetConsecutiveSkips(mediaItem?.extensionId, "stop")
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
                launchInvoluntarySkip {
                    player.pause()
                    delay(50)
                    skipInvoluntarily()
                    player.prepare()
                    player.play()
                }
            } else {
                skipInvoluntarily()
                player.prepare()
                player.play()
            }
            return
        }

        // Media3's own RELEASE diagnostic, not a playback failure. ExoPlayerImpl.release() emits this
        // through EVENT_PLAYER_ERROR when a renderer misses the releaseTimeoutMs budget, then finishes
        // teardown unconditionally and sets playerReleased = true — so the player IS released, there is
        // no playback left to recover, and the report names nothing we can act on. It reached the
        // generic tail below and was recorded as a PlayerException (build 1036, Pixel 10).
        // Scoped to TIMEOUT_OPERATION_RELEASE ONLY: Media3 raises two other operations
        // (SET_FOREGROUND_MODE, DETACH_SURFACE) on a LIVE player, and those are genuine faults that
        // must keep reporting. Returning here also skips the retry bookkeeping below, which is correct
        // — nothing should be retried on a player that is being torn down.
        val releaseTimeout = generateSequence(cause) { it.cause }
            .filterIsInstance<ExoTimeoutException>()
            .firstOrNull()
            ?.timeoutOperation == ExoTimeoutException.TIMEOUT_OPERATION_RELEASE
        if (releaseTimeout) return

        // STALL DETAIL, ATTACHED HERE BECAUSE NOTHING ELSE ON THIS PATH CAN CARRY IT. A first
        // StuckPlayerException reaches neither recordSkip gate below (see the isTimeout note above: the
        // tail does not consume the retry budget on a first occurrence), so probeDetail() — which only
        // ever travels as recordSkip's `detail` — is structurally unreachable for it. The two reports on
        // 2026-09-05 arrived with nothing attached for exactly that reason.
        // Written BEFORE the emit: throwableFlow.emit is launched asynchronously, so the key must already
        // be set when App.kt's collector reaches recordException.
        val stuck = generateSequence(cause) { it.cause }
            .take(MAX_CAUSE_DEPTH)
            .filterIsInstance<StuckPlayerException>()
            .firstOrNull()
        if (stuck != null) CrashKeys.onStuckDetail(stuckDetail(stuck))

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
            // Split from the old single "…, skipping" line: that fired BEFORE the breaker check below, so it
            // claimed a skip on the very run that stopped instead — which is why a trip was invisible in a
            // logcat capture. This line states only the fact (retries are done); the outcome is logged by
            // whichever branch actually runs (the breaker's own line inside reportAndResetConsecutiveSkips,
            // or the "skipping" line below).
            Log.d("GladixPlayback", "onPlayerError: maxRetries exhausted for ${mediaItem.mediaId}")
            recordSkip(rootCause, error, probeDetail())
            if (consecutiveUnavailableSkips >= maxConsecutiveUnavailableSkips) {
                reportAndResetConsecutiveSkips(mediaItem.extensionId, "stop")
                player.stop()
                return
            }
            val hasMore = player.hasNextMediaItem()
            if (!hasMore) {
                player.stop()
                return
            }
            Log.d("GladixPlayback", "onPlayerError: skipping ${mediaItem.mediaId}")
            if (isAndroidAutoConnected()) {
                launchInvoluntarySkip {
                    player.pause()
                    delay(50)
                    player.seekTo(player.currentMediaItemIndex, 0)
                    skipInvoluntarily()
                    player.prepare()
                    player.play()
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
            // Per-item retries are exhausted, so this IS a skip and must advance the breaker like every other
            // skip site — see recordSkip's contract above ("called at every skip site"), which this violated.
            // The omission is why a 3-strike breaker needed SIX tracks to trip: only the currentRetries >=
            // maxRetries path counted, and it zeroes currentRetries each time it fires, so the alternating
            // per-item skips were invisible to the breaker. Now 3 tracks, matching the documented intent.
            // Ordering mirrors every other counted site exactly (recordSkip -> breaker -> end-of-queue ->
            // skip). The breaker check belongs HERE rather than being left to the next site that happens to
            // check: incrementing without deciding is precisely the count/decision drift recordSkip guards
            // against, and it would defer the trip by an unbounded number of tracks.
            Log.d("GladixPlayback", "onPlayerError: item retries exhausted for ${mediaItem.mediaId}")
            recordSkip(rootCause, error, probeDetail())
            if (consecutiveUnavailableSkips >= maxConsecutiveUnavailableSkips) {
                reportAndResetConsecutiveSkips(mediaItem.extensionId, "stop")
                player.stop()
                return
            }
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

// ⚠️ THIS IS THE THIRD OF THREE ERROR CLASSIFIERS AND THE THREE ARE NOT INTERCHANGEABLE:
// ExceptionUtils.getTitle/getFinalTitle (phone snackbar, byte-for-byte frozen), ErrorCategory.classify()
// (AA head unit, enum {Network, LoginOrAuth, Generic}, kept in lockstep with getTitle by
// ErrorCategoryTest), and this raw predicate walk. This one exists BECAUSE classify()'s enum cannot
// express the distinctions its callers need - DNS/connection failures must hold and never skip while
// mid-stream socket resets retry once then skip, and PlayerRadio.throwBridge needs "parse-schema drift"
// where classify() can only say Generic.
// ⚠️ THAT IS AN ARGUMENT ABOUT AXIS AND COST, NOT ABOUT classify() BEING FROZEN - IT IS NOT.
// It was extended on 2026-08-19 (ConnectException / NoRouteToHostException, previously DNS-only), with
// getTitle changed in step and drift guards added to ErrorCategoryTest; that lockstep is the procedure for
// extending it. The reason not to extend it for these callers is that its enum is a USER-FACING
// presentation axis - Network / LoginOrAuth / Generic - and none of them want to tell the user anything
// different; they want a predicate at one call site. Promoting this walk to file level is consistent with
// that origin - one shared predicate walk, not a fourth classifier.
//
// ⚠⚠ THE ONE CHAIN WALKER. Used by onPlayerError's network classification above and by
// PlayerRadio's YTM throw-bridge. File-level and internal rather than private-to-the-class precisely so
// there is never a second copy: the whole point of the note at its former call site is that type-checking a
// WRAPPED exception against a non-chain-walking accessor silently never matches, and two walkers that could
// drift would reintroduce that risk by a different route.
internal fun Throwable.anyCause(predicate: (Throwable) -> Boolean): Boolean =
    generateSequence(this) { it.cause }.any(predicate)
