package dev.brahmkshatriya.echo.ui.player

import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import androidx.annotation.OptIn
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.ThumbRating
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.session.MediaController
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.common.clients.LikeClient
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.ImageHolder
import dev.brahmkshatriya.echo.common.models.Message
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.di.App
import dev.brahmkshatriya.echo.download.Downloader
import dev.brahmkshatriya.echo.extensions.ExtensionLoader
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.getExtension
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.isClient
import dev.brahmkshatriya.echo.extensions.MediaState
import dev.brahmkshatriya.echo.history.HistoryRepository
import dev.brahmkshatriya.echo.history.db.HistoryEntity
import dev.brahmkshatriya.echo.playback.MediaItemUtils
import dev.brahmkshatriya.echo.playback.MediaItemUtils.serverWithDownloads
import dev.brahmkshatriya.echo.playback.MediaItemUtils.sourceIndex
import dev.brahmkshatriya.echo.playback.MediaItemUtils.track
import dev.brahmkshatriya.echo.playback.PlayerCommands.addToNextCommand
import dev.brahmkshatriya.echo.playback.PlayerCommands.addToQueueCommand
import dev.brahmkshatriya.echo.playback.PlayerCommands.backfillCommand
import dev.brahmkshatriya.echo.playback.PlayerCommands.playCommand
import dev.brahmkshatriya.echo.playback.PlayerCommands.previousTrackCommand
import dev.brahmkshatriya.echo.playback.PlayerCommands.radioCommand
import dev.brahmkshatriya.echo.playback.PlayerCommands.seekToFullCommand
import dev.brahmkshatriya.echo.playback.PlayerCommands.sleepTimer
import dev.brahmkshatriya.echo.playback.PlayerCommands.syncShuffleFlagCommand
import dev.brahmkshatriya.echo.playback.PlayerCommands.trackRadioCommand
import dev.brahmkshatriya.echo.playback.PlayerService.Companion.getController
import dev.brahmkshatriya.echo.playback.PlayerState
import dev.brahmkshatriya.echo.utils.ContextUtils.listenFuture
import dev.brahmkshatriya.echo.utils.Serializer.putSerialized
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(UnstableApi::class)
class PlayerViewModel(
    val app: App,
    val playerState: PlayerState,
    val settings: SharedPreferences,
    val cache: SimpleCache,
    val extensions: ExtensionLoader,
    val historyRepository: HistoryRepository,
    downloader: Downloader,
    fullQueueFlow: MutableStateFlow<List<MediaItem>>,
) : ViewModel() {
    private val downloadFlow = downloader.flow

    val browser = MutableStateFlow<MediaController?>(null)

    var queue: List<MediaItem> = emptyList()

    // ⚠️ replay = 1 WAS TRIED HERE AND REVERTED (2026-08-28). Do not re-apply it without reading this.
    // It was added to fix the dropped-emission problem described below — a problem found while tracing
    // something else, never observed by the user, and cosmetic when it does occur. The build carrying it
    // hung on a cold start: restored queue, session state=NONE, position 0, spinner, nothing logged for
    // 19 minutes. It could not be cheaply ruled out as the cause, so it went rather than being carried.
    // Why it is not obviously safe, despite both consumers being pure UI: replay changes whether an
    // emission made BEFORE PlayerFragment subscribes is delivered at subscribe time. Whether one exists is
    // a RACE between this ViewModel's restore block emitting (below) and onViewCreated subscribing, so the
    // change is intermittent by construction and shows up exactly when cold-start disk I/O is slow — the
    // same timing window every cold-start bug in this project has lived in. An earlier analysis called it
    // "deterministic on cold start, therefore ruled out"; that was wrong.
    // To clear it you need either a cold-start reproduction on replay=0 (proving it wasn't the cause) or
    // enough clean launches to bound an unknown failure rate. Neither is cheap. The dropped emission is
    // not worth that; drive new work from the `current` collector instead.
    //
    // The problem it was addressing, which is real and still present:
    // This flow does not deliver while the screen is off: it is a
    // zero-buffer MutableSharedFlow, and BOTH its collectors go through ContextUtils.observe ->
    // flowWithLifecycle(lifecycle), whose default minActiveState is STARTED. A stopped Activity has NO
    // subscriber, and a MutableSharedFlow with no subscriber and no replay drops the emission outright —
    // gone for good, not deferred. Measured 2026-08-24 across eight consecutive screen-off auto-advances:
    // subscriptionCount was 0 at every emit and neither collector ran.
    //
    // The two collectors, and they are NOT equivalent:
    //   PlayerFragment.configurePlayerControls -> submit(), the full-screen ViewPager pages
    //   QueueFragment.onViewCreated             -> submit(), the Up Next list and scroll-to-current
    // Both are backed by a sibling `playerState.current` collector that rebuilds from `queue`, but by
    // different mechanisms, and the asymmetry is the whole point:
    //   QueueFragment's sibling (:120) is ALSO gated, so flowWithLifecycle re-subscribes at ON_START and
    //     `current` — a StateFlow — replays its latest value. It self-heals on every wake.
    //   PlayerFragment's sibling (:534) is a raw lifecycleScope.launch that subscribes ONCE for the life
    //     of the fragment. It never re-subscribes, so it never gets a replay. The ungated collector that
    //     rescues the album art is precisely the one with NO resume-time recovery.
    //
    // The case that made this real rather than theoretical: a queue mutation with a STATIONARY current —
    // a radio top-up while the screen is off. PlayerState.Current is a data class and StateFlow conflates
    // equal values, so `current` does not emit; only this flow does. Before replay=1 the appended tracks
    // were missing as ViewPager pages until the next track change, while QueueFragment showed them
    // correctly. That divergence is the signature of this bug if it ever returns.
    //
    // Why replay is safe HERE specifically: the flow carries Unit, so a replayed emission can never
    // deliver stale data — it only says "re-read", and submit() reads `queue` and `current` together at
    // call time. A flow carrying the queue itself would not have that property.
    //
    // `queue` itself IS kept current while stopped (the collector that writes it lives in viewModelScope
    // and is not lifecycle-gated), so the damage was always confined to the SIGNAL, not the data.
    val queueFlow = MutableSharedFlow<Unit>()

    init {
        // The cold-start current is seeded ONCE, in the getController callback below, from the shared
        // restore read (restoreDeferred) with a history fallback — NOT here. The old eager history
        // placeholder that used to live in this init was the one current source the wrong-track repair never
        // reconciled (history-latest = last COMPLETED track, not the restored CURRENT_ID), which is what made
        // the mini bar render a stale/consumed track while the queue-validated full player showed the real
        // one. See the seed block in getController for the single-writer replacement.
        viewModelScope.launch {
            fullQueueFlow.collect { items ->
                val showingPlaceholder = playerState.current.value?.isPlaceholder == true && items.isEmpty()
                if (items.isNotEmpty() || !showingPlaceholder) {
                    queue = items
                    queueFlow.emit(Unit)
                }
            }
        }
    }

    // ⚠⚠ EVERY SessionResult FROM HERE IS DISCARDED - AND THE FIX IS NOT HERE. `block` returns
    // Unit and the ListenableFuture that sendCustomCommand hands back is never awaited or inspected, so a
    // handler that fails is INVISIBLE BY CONSTRUCTION - not unreported by oversight, but unreportable
    // through this call shape. Eleven sendCustomCommand sites dispatch through this function.
    //
    // ⚠️ [CORRECTED 2026-09-10] THIS DID NOT CAUSE THE "a message, then nothing" REPORT, THOUGH
    // THIS NOTE ASSERTED THAT IT DID. The claim was: PlayerViewModel.radio emits its snackbar before
    // dispatching, so the user gets the optimistic half and never the failure half. Re-derived against
    // source: on the Frogmen station start() SUCCEEDED (asTrackRadio is local construction off fields the
    // Track already carries, and cannot fail), play() appended zero after DeezerRadioClient's TRACK filter
    // stripped both copies of the recording, and radio() returned RESULT_SUCCESS. NO return@future error
    // EVER RAN. The silence was an empty append treated as success plus play() no-opping on an emptied
    // queue, fixed by the seed routing at PlayerViewModel.radio.
    // Kept rather than deleted because of HOW it got believed: a plausible, real, but UNEXERCISED mechanism
    // sitting next to a real symptom was written down as the cause while the actual bug was still being
    // traced, and was then quoted back as established before anyone re-derived it.
    //
    // ⚠️ [RETARGETED 2026-09-10] THE FIX IS IN THE HANDLERS, NOT HERE. This note previously
    // proposed having block return the SessionResult and emitting on a non-success code. Wrong layer:
    //   - SessionResult carries an ERROR CODE AND NO MESSAGE, so the most this side could ever say is
    //     "something failed" - strictly less than the handler already knows.
    //   - The service and the UI SHARE A PROCESS, and app.messageFlow already crosses that boundary, so
    //     the SessionResult round trip is redundant as a channel, not merely lossy.
    //   - The precedent is already in PlayerCallback: playItem emits app.messageFlow / throwableFlow FROM
    //     INSIDE THE HANDLER (its list_is_empty guard), and trackRadio's catch does the same for its
    //     generation phase. Neither touches SessionResult.
    // So withBrowser SHOULD NOT CHANGE. The work is one emit per silent guard, inside PlayerCallback.
    // ⚠️ SAME RULE, ONE SUBSYSTEM OVER: see CALLED vs LAUNCHED at App.exceptionHandler. THE CODE
    // THAT KNOWS ABOUT THE FAILURE REPORTS IT - there it is why a CALLED extension method is attributed and
    // a LAUNCHED coroutine is not; here it is why the handler reports and the dispatch helper cannot.
    // Cross-referenced deliberately, so it is a project-wide rule rather than the same judgement reinvented
    // at each site.
    //
    // ⚠⚠ DONE 2026-09-11, AS ONE PASS ACROSS EVERY HANDLER - not two of eight. It was parked on
    // 2026-09-10 with the reachability recorded (five theoretical guards, zero ever observed to fire) and
    // taken anyway, because "tap does nothing, no message, no log" is the worst failure shape in this app
    // and the guards being unreachable is a property of TODAY'S CALLERS, not of the code.
    // WHAT WAS DONE, at PlayerCallback - see the classification note above its bug()/notFound() helpers:
    //   13 PROGRAMMER-ERROR guards  -> throwableFlow, naming the command and the missing key, and
    //                                  carrying the deserialization CAUSE that ?.getOrNull() used to drop.
    //    8 USER-VISIBLE guards      -> messageFlow (4x no-extension, 2x empty list, backfill's rebuilt-
    //                                  empty, radio's no-station-possible half).
    //    2 inline dispatch branches -> an else on the silent `as? ShufflePlayer`, which was the cheapest
    //                                  dead tap in the app (a queue-row tap that does nothing).
    //    4 guards LEFT SILENT ON PURPOSE, with the reason at each: the three queue-epoch stale drops
    //                                  (explaining a decision the user just made) and trackRadio's
    //                                  missing-extension case (the seed is already playing - degraded
    //                                  success, not a dead tap). All four log under GladixQueue.
    //    7 guards were ALREADY correct and were not touched.
    // ⚠️ THE PART THAT IS NOT FIXED: the lifecycle gate. app.messageFlow's only subscriber is
    // SnackBarHandler's flowWithLifecycle(STARTED) observe(), and the flow has replay = 0, so a message
    // emitted while the Activity is stopped is DROPPED. Every notFound() site is PRE-SUSPEND and fires
    // microseconds after the tap, so those are reliable; the four POST-NETWORK user-visible guards can
    // land backgrounded and additionally Log.d for exactly that reason. That closes the "no log" half
    // without pretending the gap is gone, and it is a second independent motivation for the parked
    // held-state item.
    // ⚠️ withBrowser ITSELF STILL DOES NOT CHANGE, and must not: SessionResult carries a code with
    // no message, and the service and UI share a process, so the handler can always say more than this
    // side could learn. If a new command handler is added, give its guards a classification HERE-style
    // rather than returning a bare error - a partially-reporting command surface is harder to reason
    // about than a uniformly silent one, because "no message" stops being evidence of anything.
    private fun withBrowser(block: suspend (MediaController) -> Unit) {
        viewModelScope.launch {
            val browser = browser.first { it != null }!!
            block(browser)
        }
    }

    private val context = app.context
    val controllerFutureRelease = getController(app) { player ->
        browser.value = player
        player.addListener(PlayerUiListener(player, this))
        // At-rest position seed (display half of the cold-start position fix). The controller reports
        // currentPosition=0 before play — the queue isn't applied yet and the masked position is never
        // surfaced to the controller (proven by GladixProgress). Seed progress from the in-memory
        // RestoreData.pos (shared PlayerState.restoreDeferred, not disk) so the scrubber shows the saved
        // position at rest; updateProgress holds it until a real tick or a user seek. Skipped if playback has
        // already started (currentPosition > 0). Independent of the service re-seek: that fixes PLAYBACK,
        // this fixes the at-rest DISPLAY, and they share no state.
        viewModelScope.launch {
            val data = playerState.restoreDeferred?.await()
            // SOLE initial-current writer (reuses the ONE restore read above — no second recoverPlaylist).
            // A restorable queue seeds the CURRENT_ID-aligned current: data.items[data.index] is the SAME
            // element applyRestoreIfCold applies and updateCurrentFlow later reflects, so the mini bar and the
            // full player cannot show different tracks. No restorable queue falls back to the last-played
            // history track (there is no CURRENT_ID to align to, and no queue to diverge from). The
            // current == null guard yields to updateCurrentFlow if the service already applied the queue;
            // both orders are correct. current is only ever REPLACED here, never nulled, so the bar cannot
            // flicker hidden — and updateCurrentFlow's later replacement carries the same mediaId.
            if (playerState.current.value == null) {
                if (data != null) {
                    val item = data.items.getOrNull(data.index) ?: data.items.firstOrNull()
                    if (item != null) {
                        playerState.current.value = PlayerState.Current(
                            index = data.index,
                            mediaItem = item,
                            isLoaded = false,
                            isPlaying = false,
                            isPlaceholder = true
                        )
                        queue = data.items
                        queueFlow.emit(Unit)
                    }
                } else {
                    val entity = historyRepository.getLatest().first()
                    val track = entity?.track
                    if (track != null) {
                        val mediaItem = MediaItemUtils.build(
                            app,
                            downloadFlow.value,
                            MediaState.Unloaded(entity.extensionId, track),
                            null
                        )
                        playerState.current.value = PlayerState.Current(
                            index = 0,
                            mediaItem = mediaItem,
                            isLoaded = false,
                            isPlaying = false,
                            isPlaceholder = true
                        )
                        queue = listOf(mediaItem)
                        queueFlow.emit(Unit)
                    }
                }
            }
            // At-rest position seed (display half of the cold-start position fix): only meaningful with a
            // restore, so it stays gated on data != null.
            if (data != null && data.pos > 0 && player.currentPosition <= 0L) {
                restoreSeedMs = data.pos
                progress.value = data.pos to 0L
            }
        }
        // No cold-start resume() here: PlayerService.onCreate is the sole app-open restorer. Sending
        // resumeCommand on connect raced onCreate's in-flight recoverPlaylist — both compareAndSet
        // userQueueSet, but onCreate only AFTER its slow disk read, so resume() could claim first and run
        // a second restore (two recoverPlaylist calls), or claim-then-bail on activeLoadCount leaving
        // nobody to restore (the bar-flash). KEEP_QUEUE is now honored inside onCreate. (resumeCommand is
        // still used by the widget/notification resume actions, which are explicit user intents.)
    }

    override fun onCleared() {
        super.onCleared()
        controllerFutureRelease()
    }

    // `position` is a FULL-queue index (the tap indexes fullQueueFlow). Seek by full index via a
    // custom command handled service-side by ShufflePlayer.seekToFullIndex, which seeks the real
    // player directly — bypassing the windowed controller seekTo() whose range check crashed on
    // taps outside the serialized window. Do NOT reconstruct a windowed index here (that desync-prone
    // math was the crash source); the service owns the window.
    fun play(position: Int) {
        withBrowser {
            it.sendCustomCommand(seekToFullCommand, Bundle().apply {
                putInt("index", position)
                putBoolean("play", true)
            })
        }
    }

    fun seek(position: Int) {
        withBrowser {
            it.sendCustomCommand(seekToFullCommand, Bundle().apply {
                putInt("index", position)
                putBoolean("play", false)
            })
        }
    }

    fun removeQueueItem(position: Int) {
        withBrowser { it.removeMediaItem(position) }
    }

    fun moveQueueItems(fromPos: Int, toPos: Int) {
        withBrowser { it.moveMediaItem(fromPos, toPos) }
    }

    fun seekTo(pos: Long) {
        withBrowser { it.seekTo(pos) }
    }

    fun seekToAdd(position: Int) {
        withBrowser { it.seekTo(max(0, it.currentPosition + position)) }
    }

    fun setPlaying(isPlaying: Boolean) {
        withBrowser {
            Log.d("GladixAudio", "setPlaying: isPlaying=$isPlaying playbackState=${it.playbackState} playWhenReady=${it.playWhenReady}")
            it.playWhenReady = isPlaying
        }
    }

    fun next() {
        withBrowser { it.seekToNextMediaItem() }
    }

    fun previous() {
        withBrowser { it.seekToPrevious() }
    }

    // Backward edge swipe only. Deliberately NOT previous(): that one restarts the current track past
    // 3s, which is right for a button and wrong for a navigation gesture. See
    // ShufflePlayer.handlePrevious. A no-op service-side when the back-stack is empty.
    fun previousTrack() {
        withBrowser { it.sendCustomCommand(previousTrackCommand, Bundle.EMPTY) }
    }

    fun setShuffle(isShuffled: Boolean, changeCurrent: Boolean = false) {
        withBrowser {
            it.shuffleModeEnabled = isShuffled
            if (changeCurrent) it.seekTo(0, 0)
        }
    }

    fun setRepeat(repeatMode: Int) {
        withBrowser { it.repeatMode = repeatMode }
    }

    // Icon of the PLAYING extension, for the full-screen player's top-right slot. Takes an explicit id
    // (from the current MediaItem) rather than reading extensionLoader.current, which is the BROWSING
    // extension and would change while a track from a different extension kept playing.
    // Null when the id is unknown or the extension ships no icon; the view keeps its ic_extension_32dp.
    suspend fun getExtensionIcon(extensionId: String?): ImageHolder? = withContext(Dispatchers.IO) {
        extensionId ?: return@withContext null
        extensions.music.getExtension(extensionId)?.metadata?.icon
    }

    suspend fun isLikeClient(extensionId: String): Boolean = withContext(Dispatchers.IO) {
        extensions.music.getExtension(extensionId)?.isClient<LikeClient>() ?: false
    }

    private fun createException(throwable: Throwable) {
        viewModelScope.launch { app.throwFlow.emit(throwable) }
    }

    fun likeCurrent(isLiked: Boolean) = withBrowser { controller ->
        val future = controller.setRating(ThumbRating(isLiked))
        app.context.listenFuture(future) { sessionResult ->
            sessionResult.getOrElse { createException(it) }
        }
    }

    // mediaId-scoped like, for the player's overflow sheet. Same transport as likeCurrent above, but it
    // targets a NAMED track and surfaces the result CODE, because RESULT_ERROR_BAD_VALUE ("that id is not
    // in the timeline") is the caller's signal to fall back to the extension-only path. See the note on
    // PlayerCallback.onSetRating(mediaId) for why that code is unambiguous.
    fun likeById(mediaId: String, isLiked: Boolean, onResult: (Int) -> Unit) = withBrowser { controller ->
        val future = controller.setRating(mediaId, ThumbRating(isLiked))
        app.context.listenFuture(future) { sessionResult ->
            onResult(
                sessionResult.getOrElse {
                    createException(it)
                    SessionResult(SessionError.ERROR_UNKNOWN)
                }.resultCode
            )
        }
    }

    fun setSleepTimer(timer: Long) {
        withBrowser { it.sendCustomCommand(sleepTimer, Bundle().apply { putLong("ms", timer) }) }
    }

    fun changeTrackSelection(trackGroup: TrackGroup, index: Int) {
        withBrowser {
            it.trackSelectionParameters = it.trackSelectionParameters
                .buildUpon()
                .clearOverride(trackGroup)
                .addOverride(TrackSelectionOverride(trackGroup, index))
                .build()
        }
    }

    private fun changeCurrent(newItem: MediaItem) {
        withBrowser { player ->
            // player is the MediaController: its currentMediaItemIndex is windowed, but the session
            // applies replaceMediaItem to the full inner timeline. Resolve the current item's FULL
            // index by mediaId so we replace the actual current track, not the wrong one, in queues > 50.
            val fullIndex =
                queue.indexOfFirst { it.mediaId == playerState.current.value?.mediaItem?.mediaId }
            if (fullIndex < 0) return@withBrowser
            val oldPosition = player.currentPosition
            player.replaceMediaItem(fullIndex, newItem)
            player.prepare()
            player.seekTo(oldPosition)
        }
    }

    fun changeServer(server: Streamable) {
        val item = playerState.current.value?.mediaItem ?: return
        val index = item.serverWithDownloads(app.context).indexOf(server).takeIf { it != -1 }
            ?: return
        changeCurrent(MediaItemUtils.buildServer(item, index))
    }

    fun changeBackground(background: Streamable?) {
        val item = playerState.current.value?.mediaItem ?: return
        val index = item.track.backgrounds.indexOf(background)
        changeCurrent(MediaItemUtils.buildBackground(item, index))
    }

    fun changeSubtitle(subtitle: Streamable?) {
        val item = playerState.current.value?.mediaItem ?: return
        val index = item.track.subtitles.indexOf(subtitle)
        changeCurrent(MediaItemUtils.buildSubtitle(item, index))
    }

    fun changeCurrentSource(index: Int) {
        val item = playerState.current.value?.mediaItem ?: return
        changeCurrent(MediaItemUtils.buildSource(item, index))
    }

    // ⚠⚠ THE STRONGEST FINDING, AND IT REFRAMES EVERY OTHER LINE BELOW: NO prepare() EVER
    // EXECUTED ON THE SERVICE-SIDE PLAYER. Not "three prepares that failed to act" - NONE RAN.
    // PROOF, read from media3 1.11.0: ExoPlayerImpl.prepare() early-returns ONLY if the state is not
    // IDLE (:589 - ours WAS idle, so it did not), then MASKS TO STATE_BUFFERING SYNCHRONOUSLY (:596)
    // and calls updatePlaybackInfo (:603), which flushes listeners and REPUBLISHES THE SESSION STATE.
    // The two dumpsys captures were BYTE-IDENTICAL including `updated=`, so no such republish happened.
    // Whatever went wrong is UPSTREAM of ExoPlayerImpl.prepare() - not inside it, and not in the source
    // layer beneath it.
    //
    // ⚠⚠ OPEN UNEXPLAINED SYMPTOM - TAPPED A PLAYLIST, IT SPUN FOREVER AND NEVER STARTED.
    // A capture of ONE occurrence is enough to decide this. READ THE TABLE FIRST; the analysis below it is
    // only there to say why the other explanations are gone.
    //
    // ⚠⚠ WHAT TO CAPTURE NEXT TIME - `adb logcat -s GladixPlayback:D GladixQueue:D` - AND WHAT
    // EACH OUTCOME MEANS. The deciding lines already exist in StreamableMediaSource; nothing needs adding.
    //   NO `prepareSourceInternal:` LINE AT ALL
    //       -> ⚠⚠ [CORRECTED] THIS IS THE EXPECTED RESULT, NOT EVIDENCE OF ANYTHING. It was
    //          written as "the highest-value branch - the first hard evidence the fault is below our
    //          layer". WRONG: prepare() never executed at all (see the lead), so the source layer was
    //          never going to be reached. And a STALLED DEFERRED SOURCE WOULD SHOW STATE_BUFFERING,
    //          NOT STATE_IDLE - so the deferred-source line is closed twice over. This branch tells
    //          you nothing; the three below still discriminate if prepare() ever does run.
    //   `prepareSourceInternal:` then "handler.post: released, skipping prepareChildSource"
    //       -> the May-2026 released-flag mechanism, back by some route the reset does not cover.
    //   `prepareSourceInternal:` then "prepareChildSource threw" + stack
    //       -> Mechanism C, FIRST EVER OBSERVATION. Left instrumented in May and never seen since.
    //   `prepareSourceInternal:` then "prepareChildSource firing" and nothing after
    //       -> the child source accepted preparation and stalled. Below us again, different place.
    //
    // ⚠⚠ RECOVERY - PAUSE, THEN PLAY. IT CLEARS INSTANTLY. Twenty minutes of waiting did
    // nothing; one pause/play cycle started playback immediately.
    // ⚠⚠ ORDER MATTERS: TAKE THE DUMP FIRST, RECOVER SECOND. The wedge is the only state that
    // cannot be recreated, and pause/play PRESERVES THE PROCESS - so a future occurrence yields both the
    // capture and a working app. Do not kill the app to clear it.
    //
    // ⚠⚠ DO NOT CHASE AUDIO FOCUS. THIS IS THE SENTENCE THAT SAVES THE SESSION:
    // PAUSE/PLAY DOES TWO UNRELATED THINGS AT ONCE, AND ONE OF THEM IS IRRELEVANT TO THIS SYMPTOM.
    // It re-requests audio focus (AudioFocusListener.onPlayWhenReadyChanged requests on the
    // playWhenReady->true edge), AND it supplies a playWhenReady EDGE. Only the second matters here.
    // ⚠️ FOCUS CANNOT PRODUCE THIS STATE, BY CONSTRUCTION: focus acts on PLAY INTENT - it drives
    // playWhenReady=false or a playbackSuppressionReason - and NEVER touches playbackState. STATE_IDLE is a
    // PREPARATION state, reachable only from never-prepared, stop(), release(), or a fatal error; the dump
    // had error=null and a full 297-item timeline. So the shared recovery action is COINCIDENCE, and a
    // reader who starts from the recovery will spend a session in the wrong subsystem.
    // (Two focus items exist in the project record - a June "Audio Focus 26-Minute Stall" whose fix IS
    // intact, see ShufflePlayer's setAudioAttributes override forcing handleAudioFocus=false; and a May
    // note about "10 call sites missing requestFocus() after recovery". ⚠️ THAT COUNT IS
    // UNVERIFIED AND MAY DESCRIBE A DESIGN THAT NO LONGER EXISTS: requestFocus() is `internal` with EXACTLY
    // ONE caller today, AudioFocusListener:154, i.e. focus is re-acquired BY THE playWhenReady EDGE rather
    // than by recovery paths remembering to ask. Whether sites of that shape still exist is a SEPARATE
    // INVESTIGATION, and question 3 above has disconnected it from this wedge entirely.)
    //
    // ⚠⚠ [CORRECTED] THE "EDGE" DEDUCTION BELOW IS FALSE. KEPT BECAUSE IT IS PLAUSIBLE AND
    // WILL BE RE-DERIVED. It argued that once playWhenReady was true every play-intent call was INERT
    // via ExoPlayerImpl.setPlayWhenReady's unchanged-value early return, leaving prepare() the only
    // lever - so the recovery worked by supplying an EDGE. THE EARLY RETURN NEVER BLINDS US, because
    // our override fires the prepare BEFORE delegating to it:
    //     override fun setPlayWhenReady(playWhenReady: Boolean) {
    //         if (playWhenReady && player.playbackState == STATE_IDLE) player.prepare()  // runs first
    //         super.setPlayWhenReady(playWhenReady)                                      // early return
    //     }
    // So a no-op setPlayWhenReady(true) STILL FIRES A PREPARE. The recovery supplied a prepare, not an
    // edge.
    // ⚠️ AND THAT PREPARE CAME FROM ShufflePlayer:457, NOT FROM BasePlayer. An external analysis
    // quoted BasePlayer.play() as `if (getPlaybackState() == STATE_IDLE) { prepare(); }
    // setPlayWhenReady(true);`. THAT IS NOT IN MEDIA3 1.11.0, where BasePlayer:112 reads
    // `public final void play() { setPlayWhenReady(true); }` and nothing else. It reached the right
    // conclusion from a quote that does not exist - check quotes against the PINNED version rather
    // than accept a plausible one.
    //
    // THE SUPERSEDED REASONING FOLLOWS. The first
    // attempt was short of NEITHER prepares NOR play intent. Exclusion 5 proves playWhenReady was ALREADY
    // TRUE, and prepare() was called at least twice (setQueue's explicit call, plus setPlayWhenReady's
    // auto-prepare).
    // ⚠️ THE NON-OBVIOUS PART IS AN EARLY RETURN: ExoPlayerImpl.setPlayWhenReady compares against
    // the current value and RETURNS WITHOUT POSTING ANYTHING if unchanged, and play() routes through it. So
    // once playWhenReady was true, EVERY LATER PLAY-INTENT CALL WAS INERT and prepare() was the only live
    // lever - the one that was observed doing nothing. The pause broke that by forcing the flag to a
    // different value, so the following play() was a REAL TRANSITION that posted real work.
    // SO THE NARROWED CLAIM IS: a player at STATE_IDLE, with a full timeline, and playWhenReady ALREADY
    // TRUE, where prepare() alone does not start it and a playWhenReady EDGE does. That is sharper than
    // "prepare was ignored" and it is consistent with all six exclusions rather than competing with them.
    // Why prepare() is inert in that state remains below our layer - which is what the table's first branch
    // is for.
    //
    // WHAT WAS SEEN (build 1103, once, not reproducible): tapped a 297-track Deezer playlist; the UI spun
    // indefinitely and playback never began. Two `dumpsys media_session` captures MINUTES APART were
    // byte-identical, including `updated=` - published once, never touched again. state=NONE(0),
    // error=null, queue size 297, metadata correct for the TAPPED track (not a leftover), active=true,
    // 4 controllers. A thread dump showed the process COMPLETELY AT REST: main drawing frames,
    // ExoPlayer:Playback parked in nativePollOnce with an empty queue, every DefaultDispatcher worker
    // TimedWaiting, OkHttp threads idle. Not a hang mid-work - NO WORK AT ALL.
    //
    // ⚠️ WHY THE RUN-UP WAS UNRECOVERABLE, as a fact and not an instruction: logcat's default
    // ring is 256 KB per buffer and it had already wrapped. Enlarging it is NOT recommended - `logcat -G`
    // resets on reboot and this occurs about monthly, so it would mean re-running a command after every
    // restart against a small chance.
    //
    // EXCLUSIONS - A RECURRENCE STARTS FROM THESE:
    //   1. THE BLOCK CANNOT HALF-RUN. After the controller is acquired there is NO SUSPENSION POINT in
    //      withBrowser's body - setMediaItems / prepare / sendCustomCommand are all ordinary calls
    //      (`upcoming.first()` is List.first, not Flow.first). Kotlin cancellation is cooperative, so it
    //      cannot interleave them. Once started, the block runs to completion.
    //   2. viewModelScope IS ACTIVITY-SCOPED (18 activityViewModel sites, zero fragment-scoped), so it
    //      survives config changes and fragment destruction - and the UI was alive showing a spinner.
    //   3. THE CALLS CANNOT BE REORDERED OR LOST IN TRANSPORT. setMediaItems demonstrably arrived (the
    //      queue and metadata are the tapped playlist's). A later call on the same live connection
    //      cannot overtake it or vanish while the session stays active.
    //      ⚠️ [NARROWED] THAT RULES OUT TRANSPORT LOSS, NOT COMMAND MASKING - a different
    //      mechanism at a different layer: MediaController checks isCommandAvailable and RETURNS
    //      BEFORE SENDING, so nothing is ever in transit to be ordered. Binder reasoning says nothing
    //      about it.
    //      ⚠⚠ MASKING IS NEVERTHELESS CLOSED, CHECKED 2026-09-13, FOUR WAYS SO NOBODY RE-OPENS
    //      IT: (a) PlayerCallback:197 grants ConnectionResult.DEFAULT_PLAYER_COMMANDS EXPLICITLY, which
    //      contains COMMAND_PREPARE; (b) nothing narrows commands per-controller or per-trust-state -
    //      that grep returns only the one call; (c) NOTHING calls setAvailableCommands post-connect
    //      (only setCustomLayout, which is layout buttons, not player commands); (d) THE CONNECT-RACE
    //      WINDOW DOES NOT EXIST - browser.value is assigned inside listenFuture(playerFuture) in
    //      PlayerService.getController:928-936, i.e. ONLY after MediaController.Builder.buildAsync()'s
    //      future completes, and that future does not complete until the handshake has applied the
    //      ConnectionResult. A NON-NULL browser ALREADY IMPLIES commands are set.
    //      ⚠️ SO "make withBrowser await the command set rather than non-nullness" IS
    //      UNNECESSARY - non-nullness already means that. Do not build it.
    //   4. NOTHING OF OURS SWALLOWS A prepare(). ShufflePlayer does NOT override it - it passes through
    //      ForwardingPlayer to the inner player. Its three prepare() calls are all ADDITIVE. The four
    //      PlayerEventListener prepare guards are on error/retry paths, and error=null means none armed.
    //   5. state=NONE(0) PROVES THE FAKE STATE_READY DID NOT APPLY (getPlaybackState fakes READY on
    //      IDLE + items + !playWhenReady, which would have published PAUSED(2)). So playWhenReady was
    //      ALREADY TRUE - play intent existed.
    //   6. THREE INDEPENDENT PREPARES WERE ISSUED: setQueue's explicit prepare(), setPlayWhenReady's
    //      auto-prepare (which fires even when the value is UNCHANGED - see the correction above), and
    //      play()'s. ⚠⚠ [REFRAMED] NONE OF THEM EXECUTED - see the lead. The question is not
    //      why three prepares failed to act, but why none reached ExoPlayerImpl.prepare(), given that
    //      the block cannot half-run (1), the scope was alive (2), transport cannot lose them (3),
    //      masking is closed (3d), and nothing of ours swallows one (4).
    //      ⚠️ DISCRIMINATOR FOR A FUTURE OCCURRENCE, AND IT IS MEDIA3'S OWN, NOT OURS:
    //      MediaControllerImplBase logs a warning when it drops a command the controller lacks. An
    //      UNFILTERED logcat around the tap carries it with no instrumentation of ours - so if masking
    //      somehow is the mechanism despite (a)-(d), that line says so.
    //
    // ⚠️ FAMILY, NOT CAUSE - RECOGNISING THE SHAPE IS THE POINT. The May 2026 "mid-song resume
    // hang" was the same shape: a prepare() ACCEPTED THAT DID NOTHING, because StreamableMediaSource's
    // `released` meant "have I ever been released?" and was never reset, so every later handler.post
    // silently exited. THAT SPECIFIC ROUTE IS CLOSED at HEAD - prepareSourceInternal's first statement is
    // `released = false`, and setMediaItems MINTS FRESH source instances (only replaceMediaItem(s) can
    // reuse one via canUpdateMediaItem), so no instance crosses a queue replace. Kept because a future
    // reader meeting "prepare accepted, nothing happened" should recognise it immediately rather than
    // re-derive it.
    fun setQueue(id: String, list: List<Track>, index: Int, context: EchoMediaItem?) {
        withBrowser { controller ->
            if (list.isEmpty()) return@withBrowser
            // P2 — current+upcoming: start at the tapped track (index 0) and drop the tracks before it,
            // so it lands at index 0 with nothing stranded above and a zero persisted index — matching
            // playItem and freshContextUpcoming. `index` locates the tapped track within `list`.
            val start = index.coerceIn(0, list.size - 1)
            val upcoming = list.subList(start, list.size)
            val mediaItems = upcoming.map {
                MediaItemUtils.build(
                    app,
                    downloadFlow.value,
                    MediaState.Unloaded(id, it),
                    context
                )
            }
            controller.setMediaItems(mediaItems, 0, upcoming.first().playedDuration ?: 0)
            controller.prepare()
            // In-order queue set (track tap / History) — sync the shuffle flag/icon OFF on the service player
            // WITHOUT changeQueue (pure primitive), so the icon can't stay stale-ON from prior playback.
            // `original` is already the in-order queue from setMediaItems, so this is cosmetic-only. FIFO after
            // setMediaItems, and idempotent regardless of arrival order. (The feed Play/Shuffle buttons call
            // setShuffle(...) AFTER this, which correctly overrides the flag for the Shuffle-button case.)
            controller.sendCustomCommand(
                syncShuffleFlagCommand, Bundle().apply { putBoolean("enabled", false) }
            )
        }
    }

    fun backfillQueue(
        extensionId: String, item: EchoMediaItem, loaded: Boolean, startTrackId: String,
    ) = viewModelScope.launch {
        withBrowser {
            it.sendCustomCommand(backfillCommand, Bundle().apply {
                putString("extId", extensionId)
                putSerialized("item", item)
                putBoolean("loaded", loaded)
                putString("startTrackId", startTrackId)
            })
        }
    }

    // ⚠⚠ TRACKS DO NOT GO THROUGH radioCommand. THE SPLIT IS BY ITEM TYPE AND IT IS LOAD-BEARING.
    // A Track IS a radio seed by nature, so a track station must be SEED-FIRST: the tapped track queued at
    // index 0 and played, then the generated mix appended behind it. Everything else (Album, Artist,
    // Playlist, Radio) has no seed to preserve - PlayerCallback.radio clears the queue and plays the mix,
    // which is CORRECT for those and only for those.
    //
    // ⚠️ WHAT THIS FIXES, MEASURED ON DEVICE 2026-09-10. Long-press -> Radio on a search result reached
    // PlayerCallback.radio, whose clearMediaItems() runs with no seed queued. THREE SYMPTOMS, ONE CAUSE:
    //   1. "Worst That Could Happen" (The Brooklyn Bridge) - ~100 tracks queued, playback started on the
    //      wrong one, because the tapped track was never at index 0 and DeezerRadioClient's TRACK branch
    //      had stripped it from the mix (see the coupling note there).
    //   2. "Underwater" (The Frogmen) - Deezer serves that recording under two album ids, the TRACK branch
    //      stripped BOTH, the append was empty, and play() ran on an emptied queue. Total silence.
    //   3. The endless-queue fallback never fired: PlayerRadio.play reads its seed from
    //      player.currentMediaItem, which is null once the queue is cleared and nothing is appended.
    //      Confirmed by an EMPTY `adb logcat -s GladixRadio` across both attempts - RadioFallback logs on
    //      every outcome, so zero lines means it never ran.
    //
    // ⚠️ DO NOT "FIX" THIS BY QUEUEING THE SEED HERE AND THEN CALLING radioCommand. ATOMIC PACKAGING IS
    // WHY trackRadio EXISTS: as two separate async commands the append can read a STALE currentMediaItem
    // between them. trackRadio does both halves inside one command, on one thread, in order.
    //
    // THE CHOICE LIVES HERE, AT THE CALLER, DELIBERATELY. Both UI entry points - MediaMoreBottomSheet's
    // radio button and MediaHeaderAdapter's onRadioClicked - funnel through this one function, so one
    // branch covers both and PlayerCallback.radio stays untouched and correct for its remaining callers.
    // Branching INSIDE PlayerCallback.radio was rejected: it would make the service handler mean two
    // different things depending on payload type, and the service is the harder place to see it from.
    //
    // ⚠⚠ THIS REROUTE POINTS THE MENU AT A HANDLER BUILT FOR TILE TAPS, AND THAT EXACT SHAPE
    // HAS REGRESSED BEFORE. A previous reroute of the single-track branch was believed TV-only, was relayed
    // as "phone unchanged" when the truth was "phone reaches the same result through DIFFERENT CODE", and
    // phone single-track tiles and search results then played NOTHING. Different code is different failure
    // modes. So the inputs were compared rather than assumed - checked 2026-09-10:
    //   extId        - tile tap passes the feed item's extensionId, the menu passes the sheet's. Both
    //                  non-null String; trackRadio only needs it to stamp MediaState.Unloaded.
    //   item         - both go through putSerialized<EchoMediaItem>(...) on THIS function, so the
    //                  polymorphic discriminator trackRadio's getSerialized<EchoMediaItem> needs is written
    //                  by construction. (Serialising as the concrete Track omits it - that is the
    //                  "tapping does nothing" regression recorded at playTrackRadio.)
    //   context      - NOT a difference: trackRadio builds its own "<title> Radio" Radio from the seed and
    //                  ignores whatever the caller had. SearchFragment's override passes null for exactly
    //                  this reason; the menu never supplied one at all.
    //   loaded       - THE ONE REAL DIFFERENCE. Tile taps pass an UNLOADED feed track; the menu passes a
    //                  LOADED one. trackRadio wraps either in MediaState.Unloaded and lets the normal
    //                  pipeline resolve it, so both work - the loaded track just carries more extras
    //                  through the Binder. The old menu path called loadItem() first; dropping that is not
    //                  a loss, because the menu already passed loaded=true so it was a no-op there.
    // ⚠️ ONE BEHAVIOUR DIFFERENCE AT THE EXTENSION, VERIFIED FOR DEEZER AND ONLY DEEZER. The old
    // path called RadioClient.radio(item, null); trackRadio calls it with the Radio it just built. In
    // DeezerRadioClient the Track branch sends BOTH to the same place - `null -> item.asTrackRadio()` and
    // `is Radio -> RadioKind.TRACK -> item.asTrackRadio()` - so the generated station is IDENTICAL. That
    // equivalence is NOT guaranteed for other extensions: any RadioClient that treats a non-null Radio
    // context differently from null will now see a context where it used to see null. If a non-Deezer
    // track radio behaves oddly after this change, that is the first place to look.
    //
    // NOTE ON `loaded`: intentionally unused on the Track path. trackRadio builds the seed as
    // MediaState.Unloaded and lets the normal resolution pipeline load it, which is what the already-shipped
    // tile path (FeedClickListener -> playTrackRadio) does with unloaded feed tracks.
    fun radio(id: String, item: EchoMediaItem, loaded: Boolean) = viewModelScope.launch {
        if (item is Track) {
            // No snackbar on this path: the seed starts playing immediately, so "Loading radio for X"
            // followed by instant audio reads as a stutter. The message below exists because the non-seed
            // path genuinely has nothing to show until the whole mix resolves.
            playTrackRadio(id, item)
            return@launch
        }
        app.messageFlow.emit(
            Message(app.context.getString(R.string.loading_radio_for_x, item.title))
        )
        withBrowser {
            it.sendCustomCommand(radioCommand, Bundle().apply {
                putString("extId", id)
                putSerialized("item", item)
                putBoolean("loaded", loaded)
            })
        }
    }

    // Single-track "radio" tiles (Home "Mixes inspired by", search): play the seed first, then append the
    // generated radio — service-side (PlayerCallback.trackRadio) so it works on TV without relying on
    // auto-radio. No loading snackbar (the seed plays immediately), matching the old setQueue path.
    fun playTrackRadio(id: String, track: Track) = viewModelScope.launch {
        withBrowser {
            it.sendCustomCommand(trackRadioCommand, Bundle().apply {
                putString("extId", id)
                // Serialize as EchoMediaItem (not Track) so the polymorphic "mediaItemType" discriminator is
                // written and the handler's getSerialized<EchoMediaItem> can round-trip it. Serializing as
                // the concrete Track omits the discriminator, decoding fails, and the seed guard silently
                // bails before any playback — the "tapping does nothing" regression.
                putSerialized<EchoMediaItem>("item", track)
            })
        }
    }

    fun play(id: String, item: EchoMediaItem, loaded: Boolean, startTrackId: String? = null) = viewModelScope.launch {
        if (item !is Track) app.messageFlow.emit(
            Message(app.context.getString(R.string.playing_x, item.title))
        )
        withBrowser {
            it.sendCustomCommand(playCommand, Bundle().apply {
                putString("extId", id)
                putSerialized("item", item)
                putBoolean("loaded", loaded)
                putBoolean("shuffle", false)
                if (startTrackId != null) putString("startTrackId", startTrackId)
            })
        }
    }

    fun shuffle(id: String, item: EchoMediaItem, loaded: Boolean) = viewModelScope.launch {
        if (item !is Track) app.messageFlow.emit(
            Message(app.context.getString(R.string.shuffling_x, item.title))
        )
        withBrowser {
            it.sendCustomCommand(playCommand, Bundle().apply {
                putString("extId", id)
                putSerialized("item", item)
                putBoolean("loaded", loaded)
                putBoolean("shuffle", true)
            })
        }
    }


    fun addToQueue(id: String, item: EchoMediaItem, loaded: Boolean) = viewModelScope.launch {
        if (item !is Track) app.messageFlow.emit(
            Message(app.context.getString(R.string.adding_x_to_queue, item.title))
        )
        withBrowser {
            it.sendCustomCommand(addToQueueCommand, Bundle().apply {
                putString("extId", id)
                putSerialized("item", item)
                putBoolean("loaded", loaded)
            })
        }
    }

    fun addToNext(id: String, item: EchoMediaItem, loaded: Boolean) = viewModelScope.launch {
        if (!(browser.value?.mediaItemCount == 0 && item is Track)) app.messageFlow.emit(
            Message(app.context.getString(R.string.adding_x_to_next, item.title))
        )
        withBrowser {
            it.sendCustomCommand(addToNextCommand, Bundle().apply {
                putString("extId", id)
                putSerialized("item", item)
                putBoolean("loaded", loaded)
            })
        }
    }

    val progress = MutableStateFlow(0L to 0L)

    // At-rest position seed hold (display half of the cold-start position fix). Non-null = the saved restore
    // position is being shown while the controller still reports currentPosition=0. PlayerUiListener.updateProgress
    // emits it in place of 0 and releases it (nulls) on the first real tick; a user seek nulls it too. Main-only.
    var restoreSeedMs: Long? = null
    val discontinuity = MutableStateFlow(0L)
    val totalDuration = MutableStateFlow<Long?>(null)

    val buffering = MutableStateFlow(false)
    val isPlaying = MutableStateFlow(false)
    val playWhenReady = MutableStateFlow(false)
    val nextEnabled = MutableStateFlow(false)
    val previousEnabled = MutableStateFlow(false)
    val repeatMode = MutableStateFlow(0)
    val shuffleMode = MutableStateFlow(false)

    // Tracks STAMPED with the mediaId they belong to. Written only by PlayerUiListener, which reads both
    // halves in one expression. The stamp turns "are these tracks for the item on screen?" from a timing
    // assumption into a checkable one, which is the point: the previous shape emitted the PREVIOUS track's
    // formats for the whole gap between an item transition and its onTracksChanged, because `current`
    // emits immediately while tracksFlow still holds the old value. On an all-320 queue that is invisible;
    // the moment quality varies it silently mislabels the new track for 2.4-3.8s (measured resolve window).
    val tracksFlow = MutableStateFlow<Pair<String?, Tracks?>>(null to null)
    val serverAndTracks = tracksFlow.combine(playerState.serverChanged) { stamped, _ -> stamped }
        .combine(playerState.current) { (stampedId, tracks), current ->
            val currentId = current?.mediaItem?.mediaId
            val server = playerState.servers[currentId]?.getOrNull()
            val index = current?.mediaItem?.sourceIndex
            // null unless the stamp matches. Consumers already null-guard `tracks`, so a mismatch renders
            // as "not known yet" rather than as the wrong track's format. `server` and `index` are NOT
            // gated: they come from `current` itself and are always about the right item, which is what
            // keeps the quality sheet's source chips populated during the gap.
            //
            // FAILURE MODE, AND HOW TO RECOGNISE IT. If a stamp never matches - a transition race, or a
            // reconnect that re-seeds with an id the UI has moved off - the pill hides PERMANENTLY rather
            // than showing something wrong. That is the safe direction, but it is silent, so know the
            // signature: a track that genuinely has no readable format hides the pill for THAT TRACK ONLY,
            // while a stamp mismatch hides it for EVERY track. The quality sheet is the discriminator -
            // its source chips come from `server`, which is never gated, so "chips present, details line
            // absent, on every track" means the stamp is not matching and this gate is the place to look.
            // "Chips present, details absent, on one track" is the legitimate case.
            Triple(if (stampedId != null && stampedId == currentId) tracks else null, server, index)
        }.stateIn(viewModelScope, SharingStarted.Lazily, Triple(null, null, null))

    companion object {
        const val KEEP_QUEUE = "keep_queue"
    }
}