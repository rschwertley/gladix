package dev.brahmkshatriya.echo.playback

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.scale
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Rating
import androidx.media3.common.ThumbRating
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaButtonReceiver
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionResult.RESULT_SUCCESS
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.common.Extension
import dev.brahmkshatriya.echo.common.MusicExtension
import dev.brahmkshatriya.echo.extensions.builtin.unified.UnifiedExtension
import dev.brahmkshatriya.echo.common.clients.AlbumClient
import dev.brahmkshatriya.echo.common.clients.ArtistClient
import dev.brahmkshatriya.echo.common.clients.LikeClient
import dev.brahmkshatriya.echo.common.clients.PlaylistClient
import dev.brahmkshatriya.echo.common.clients.RadioClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Feed.Companion.pagedDataOfFirst
import dev.brahmkshatriya.echo.common.models.Message
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Radio
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.di.App
import dev.brahmkshatriya.echo.download.Downloader
import dev.brahmkshatriya.echo.extensions.ExtensionLoader
import dev.brahmkshatriya.echo.history.HistoryRepository
import dev.brahmkshatriya.echo.ui.player.PlayerViewModel.Companion.KEEP_QUEUE
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.get
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.getAs
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.getExtension
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.getExtensionOrThrow
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.isClient
import dev.brahmkshatriya.echo.extensions.MediaState
import dev.brahmkshatriya.echo.playback.MediaItemUtils
import dev.brahmkshatriya.echo.playback.MediaItemUtils.extensionId
import dev.brahmkshatriya.echo.utils.CrashKeys
import dev.brahmkshatriya.echo.playback.MediaItemUtils.track
import dev.brahmkshatriya.echo.playback.ResumptionUtils.recoverCurrentId
import dev.brahmkshatriya.echo.playback.ResumptionUtils.recoverIndex
import dev.brahmkshatriya.echo.playback.ResumptionUtils.recoverPlaylist
import dev.brahmkshatriya.echo.playback.ResumptionUtils.resolveCurrentIndex
import dev.brahmkshatriya.echo.playback.ResumptionUtils.recoverRepeat
import dev.brahmkshatriya.echo.playback.ResumptionUtils.recoverShuffle
import dev.brahmkshatriya.echo.playback.ResumptionUtils.hasSavedQueue
import dev.brahmkshatriya.echo.playback.ResumptionUtils.recoverTracks
import dev.brahmkshatriya.echo.playback.exceptions.PlayerException
import dev.brahmkshatriya.echo.playback.listener.PlayerRadio
import dev.brahmkshatriya.echo.utils.CoroutineUtils.future
import dev.brahmkshatriya.echo.utils.CoroutineUtils.futureCatching
import dev.brahmkshatriya.echo.utils.Serializer.getSerialized
import dev.brahmkshatriya.echo.utils.image.ImageUtils.loadDrawable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


@OptIn(UnstableApi::class)
class PlayerCallback(
    override val app: App,
    override val scope: CoroutineScope,
    override val throwableFlow: MutableSharedFlow<Throwable>,
    private val extensions: ExtensionLoader,
    private val state: PlayerState,
    override val downloadFlow: StateFlow<List<Downloader.Info>>,
    private val histRepo: HistoryRepository,
) : AndroidAutoCallback(app, scope, extensions.music, downloadFlow) {

    override val historyRepository: HistoryRepository get() = histRepo

    // TIER 1 WAS UNDER-FILTERED — FIXED 2026-09-07. This is the LIVE implementation (PlayerCallback is
    // AndroidAutoCallback's only subclass). Tiers 2 and 3 already applied the full aaEligible; TIER 1 APPLIED
    // ONLY `it != UNIFIED_ID` to the STRING and never checked isEnabled. The id it reads is written by
    // AndroidAutoCallback.onGetChildren from an unfiltered parse of parentId, so a DISABLED extension reached
    // through a browse node the head unit still had cached became the voice-search target and STAYED it,
    // because lastBrowsedExtId persists. Reachable with no version history at all: cache a browse tree, then
    // disable the extension. Tier 1 now resolves first and runs the extension through aaEligible, so all
    // three tiers share one predicate.
    // ⚠️ THE SOURCE OF THE ID IS STILL UNFILTERED — AndroidAutoCallback.onGetChildren's per-node dispatch
    // resolves extId against the raw extensionList and writes it here. That gap is open and scoped in the
    // note at that dispatch. This fix hardens the CONSUMER only; it does not make the field trustworthy.
    //
    // ⚠️ THE TIER-1 FIX DID NOT MAKE THIS FUNCTION CORRECT, ONLY LESS WRONG. It closed the
    // DISABLED/UNIFIED axis. TWO OTHERS SURVIVE IT, and they are DISTINCT — do not conflate them:
    //   (i)  RACINESS — "WHICH CONCURRENT WRITE WON". lastBrowsedExtId is @Volatile and shared across all
    //        in-flight browse futures; under concurrency it holds whichever future wrote last, so which
    //        extension wins is nondeterministic independently of enablement. An earlier session searched for
    //        a per-request discriminator at the onGetChildren boundary (parentId, browser.packageName,
    //        params, page/pageSize, ordering, time-since-connect) and concluded none exists.
    //   (ii) STALENESS — "THE VALUE WAS NEVER UPDATED AT ALL". It is set only on an AA browse-into and NEVER
    //        on a phone-side extension switch, so switching extension on the phone while AA is connected
    //        leaves this naming the last one browsed in the car. That id is stale but ENABLED, so aaEligible
    //        passes it through. Nothing raced here; the write never happened.
    // Both are unsolved and neither is addressed by a stronger predicate.
    //
    // It does not crash — the search runs and returns real results from an extension the user turned off.
    // See the frequency-drop trap at AndroidAutoCallback.onGetChildren's per-node dispatch: the May guard was
    // read as complete because Crashlytics auto-resolved a falling count, and these two gaps produce no
    // count at all. Do not read quiet as fixed.
    override fun getCurrentExtension(): MusicExtension? {
        val aaEligible = { ext: MusicExtension ->
            ext.isEnabled && ext.id != UnifiedExtension.UNIFIED_ID
        }
        return lastBrowsedExtId
            ?.let { id -> extensionList.value.firstOrNull { it.id == id } }
            ?.takeIf(aaEligible)
            ?: extensions.current.value?.takeIf { aaEligible(it) }
            ?: extensionList.value.firstOrNull(aaEligible)
    }

    private val radioFlow get() = state.radio

    // Swallow the phantom hardware KEYCODE_MEDIA_PLAY that a head unit emits around a BT/car/AA
    // disconnect, which would otherwise reach applyMediaButtonKeyEvent -> play() -> onPlaybackResumption
    // and auto-start playback the user never asked for (traced against Media3 1.10.1 MediaSessionImpl:
    // this callback is invoked at line 1460, strictly BEFORE the keycode->play() dispatch, and returning
    // true stops propagation with no player/notification side effects). This path is reached ONLY for
    // system-routed ACTION_MEDIA_BUTTON key events (e.g. Bluetooth); a MediaController.play() from our
    // own UI travels the AIDL path and never enters here, so in-app playback is never suppressed. Gated
    // purely on route-STATE (PlayerState.isPostDisconnect); no timing window. Both ACTION_DOWN and
    // ACTION_UP of MEDIA_PLAY are swallowed while post-disconnect.
    override fun onMediaButtonEvent(
        session: MediaSession,
        controllerInfo: MediaSession.ControllerInfo,
        intent: Intent,
    ): Boolean {
        if (state.isPostDisconnect) {
            val keyEvent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
            if (keyEvent?.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY) {
                Log.d(
                    "GladixPlayback",
                    "Swallowing post-disconnect phantom KEYCODE_MEDIA_PLAY (action=${keyEvent.action})"
                )
                return true
            }
        }
        return super.onMediaButtonEvent(session, controllerInfo, intent)
    }

    override fun onConnect(
        session: MediaSession, controller: MediaSession.ControllerInfo,
    ): MediaSession.ConnectionResult {
        CrashKeys.onControllerConnected(controller.packageName)
        val sessionCommands = with(PlayerCommands) {
            MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                .add(likeCommand).add(unlikeCommand).add(repeatCommand).add(repeatOffCommand)
                .add(repeatOneCommand).add(shuffleCommand).add(shuffleOffCommand)
                .add(radioCommand).add(trackRadioCommand).add(sleepTimer)
                .add(playCommand).add(addToQueueCommand).add(addToNextCommand)
                .add(resumeCommand).add(imageCommand).add(backfillCommand)
                .add(seekToFullCommand).add(syncShuffleFlagCommand)
                .build()
        }
        return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
            .setAvailableSessionCommands(sessionCommands)
            // Preserves pre-1.11.0 behaviour DELIBERATELY. The old single-arg builder always seeded
            // DEFAULT_PLAYER_COMMANDS; the two-arg one seeds them only for a trusted controller and gives
            // an untrusted one DEFAULT_UNTRUSTED_PLAYER_COMMANDS (read-only). This is the line to delete
            // if the tighter default is ever wanted — deleting it costs untrusted controllers all 28
            // write commands, including play/pause, prepare, stop, seek, skip, shuffle and repeat, i.e.
            // a head unit that can display but not control. Untrusted is a REAL population here, not
            // theoretical: legacy browsers get their trust from the platform's
            // MediaSessionManager.isTrustedForMediaControl (MediaSessionServiceLegacyStub:130), whose
            // answer varies by device and by whether a notification listener is granted.
            .setAvailablePlayerCommands(MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS)
            // Seed the AA custom-action layout at connect (display-only). Shuffle + repeat read
            // synchronously from player state, so they show on the FIRST auto-played track instead of only
            // after track 2. No current item is needed, so this is safe before the queue is restored
            // (currentMediaItem may be null here). The like button needs async extension IO → deferred to
            // the post-resumption push in PlayerEventListener.onTimelineChanged.
            .setCustomLayout(
                with(PlayerCommands) {
                    listOf(
                        getShuffleButton(context, session.player.shuffleModeEnabled),
                        getRepeatButton(context, session.player.repeatMode),
                    )
                }
            )
            .build()
    }

    override fun onDisconnected(session: MediaSession, controller: MediaSession.ControllerInfo) {
        CrashKeys.onControllerDisconnected(controller.packageName)
        super.onDisconnected(session, controller)
    }

    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle,
    ): ListenableFuture<SessionResult> = with(PlayerCommands) {
        val player = session.player
        when (customCommand) {
            likeCommand -> onSetRating(session, controller, ThumbRating(true))
            unlikeCommand -> onSetRating(session, controller, ThumbRating())
            repeatOffCommand -> setRepeat(player, Player.REPEAT_MODE_OFF)
            repeatOneCommand -> setRepeat(player, Player.REPEAT_MODE_ONE)
            repeatCommand -> setRepeat(player, Player.REPEAT_MODE_ALL)
            shuffleCommand -> setShuffle(player, true)
            shuffleOffCommand -> setShuffle(player, false)
            playCommand -> playItem(player, args)
            addToQueueCommand -> addToQueue(player, args)
            addToNextCommand -> addToNext(player, args)
            radioCommand -> radio(player, args)
            trackRadioCommand -> trackRadio(player, args)
            sleepTimer -> onSleepTimer(player, args.getLong("ms"))
            resumeCommand -> resume(player)
            imageCommand -> getImage(player)
            backfillCommand -> backfillQueue(player, args)
            // ⚠⚠ THE `as?` BELOW SWALLOWS A MISMATCH SILENTLY, AND THESE TWO ARE THE CHEAPEST
            // "TAP DOES NOTHING" IN THE APP: seekToFull is a queue-row tap, so a null cast means the row
            // does not play and nothing anywhere records why. The session player IS always a ShufflePlayer,
            // which is exactly why a failure here is a PROGRAMMER ERROR worth a non-fatal rather than a user
            // message - there is nothing the user could do about it.
            seekToFullCommand -> run {
                // Phone queue tap: seek by FULL index on the real player. seekToFullIndex no-ops on an
                // out-of-range (stale/racing) index, which the raw controller seekTo can't — that guard
                // is why this stays a custom command. `play` preserves play()=true / seek()=false. AA's
                // onSkipToQueueItem path (seekToDefaultPosition) never enters here.
                val shuffled = player as? ShufflePlayer
                if (shuffled == null) bugAsync("seek_to_full", "player is not a ShufflePlayer")
                else shuffled.seekToFullIndex(args.getInt("index"), args.getBoolean("play"))
                Futures.immediateFuture(SessionResult(RESULT_SUCCESS))
            }
            syncShuffleFlagCommand -> run {
                // Pure icon sync from a CLIENT-side in-order start-playback path (track tap / History setQueue):
                // set the flag WITHOUT changeQueue. `original` is already correct from the preceding setMediaItems,
                // so this is cosmetic-only and cannot reorder.
                val shuffled = player as? ShufflePlayer
                if (shuffled == null) bugAsync("sync_shuffle_flag", "player is not a ShufflePlayer")
                else shuffled.syncShuffleFlag(args.getBoolean("enabled"))
                Futures.immediateFuture(SessionResult(RESULT_SUCCESS))
            }
            else -> super.onCustomCommand(session, controller, customCommand, args)
        }
    }

    private fun getImage(player: Player) = scope.future {
        val item = player.with { currentMediaItem } ?: run {
            // Read-only fallback: widget image fetch should never trigger clearQueue().
            val tracks = context.recoverTracks()
                ?: return@future SessionResult(SessionError.ERROR_UNKNOWN)
            val rawIndex = context.recoverIndex() ?: 0
            val (state, ctx) = tracks.getOrNull(rawIndex) ?: tracks.firstOrNull()
                ?: return@future SessionResult(SessionError.ERROR_UNKNOWN)
            MediaItemUtils.build(app, downloadFlow.value, state, ctx)
        }
        val image = item.track.cover.loadDrawable(context)?.toScaledBitmap(720)
        SessionResult(RESULT_SUCCESS, Bundle().apply { putParcelable("image", image) })
    }

    private fun Drawable.toScaledBitmap(width: Int) = toBitmap().let { bmp ->
        val ratio = width.toFloat() / bmp.width
        val height = (bmp.height * ratio).toInt()
        bmp.scale(width, height)
    }

    // Consumer of the shared cold-start restore (PlayerState.restoreDeferred). Applies the snapshot ONLY
    // when the player is cold — empty AND no resumption in flight — and loses cleanly to a concurrent user
    // play: playItem sets userQueueSet first (its synchronous first line, before its suspend points), so
    // our compareAndSet then fails and we skip. Main-atomic: the whole gate+apply is one withContext(Main)
    // block, and resumptionApplying / mediaItemCount are read on the looper they're written on. No
    // prepare() — same lazy STATE_READY reason as the old onCreate restore.
    //
    // KNOWN ORDERING (accepted, not a bug): if this CAS wins the sub-second race — the disk read finished
    // and applied BEFORE the user's tap registered playItem's set(true) — we apply the full restore and
    // playItem then replaces it, so BOTH applies fire onTimelineChanged(PLAYLIST_CHANGED),
    // onMediaItemTransition and scheduleSaveQueue (the exact observer-churn class that cost us a week).
    // Tolerated deliberately: we do NOT prepare(), so no source prepare / network fetch is started; the two
    // scheduleSaveQueue calls are debounced last-wins and BOTH non-empty, so disk ends on the user's track
    // with no empty-save wipe; and the current-observer is hadTrack-gated, so the bar shows once. It is the
    // "restore finished, then user played new" sequence, not a spurious double-restore.
    suspend fun applyRestoreIfCold(player: Player) {
        // ⚠️ THE KEEP_QUEUE GATE BELONGS HERE AND ONLY HERE. Its absence elsewhere is not a missed case.
        //
        // The setting's own label is the only statement of intent that exists — nothing about KEEP_QUEUE
        // is recorded anywhere in this project's history; it is inherited from upstream. The label reads:
        //     "Keep Player Queue — Recover the player queue, when you reopen the app"
        // "when you reopen the app" is precisely this function: the app-open cold-start restore. A
        // Bluetooth or headset media button is NOT reopening the app, so onPlaybackResumption being
        // ungated matches what the setting promises rather than contradicting it — a media button is an
        // explicit play request, and Media3's resumption contract is "give me something to play".
        // PlayerViewModel's at-rest seed (the mini-bar's current track) is display, not queue recovery, and
        // is ungated for the same reason.
        //
        // ⚠️ SO ADDING A GATE TO onPlaybackResumption WOULD CHANGE WHAT THE SETTING MEANS, not fix a gap.
        // It would make "don't recover my queue when I reopen the app" also mean "don't resume when I
        // press play on my headphones", which the label does not say and which no record supports.
        //
        // WHAT IS *NOT* SETTLED BY THIS, kept separate so the two are not conflated: with the setting off
        // the snapshot is still BUILT — PlayerService's producer runs recoverPlaylist unconditionally and
        // constructs up to QUEUE_CAP_UPCOMING + 1 MediaItems. That is justified semantically (the two
        // ungated consumers above legitimately want the data) but wasteful in degree: consumer 3 needs ONE
        // item and consumer 2 needs the list only if a media button ever arrives. That is the parked
        // lazy-RestoreData-construction work, and it is a performance question, not a semantics one.
        if (!app.settings.getBoolean(KEEP_QUEUE, true)) return
        val data = state.restoreDeferred?.await() ?: return
        withContext(Dispatchers.Main) {
            if (player.mediaItemCount != 0 || state.resumptionApplying) return@withContext
            if (!userQueueSet.compareAndSet(false, true)) return@withContext
            player.shuffleModeEnabled = data.shuffle
            player.repeatMode = data.repeat
            player.setMediaItems(data.items.toMutableList(), data.index, data.pos)
            // Arm the cold-start re-seek: the startPositionMs above is lost at prepare() (see PlayerState).
            // ⚠⚠ THE EPOCH IS READ HERE, AFTER setMediaItems, AND THAT ORDER IS THE WHOLE POINT.
            // setMediaItems bumps queueEpoch via markQueueReplaced, so reading it on the line BEFORE would
            // capture the outgoing queue's value and every consume would then look stale - a gate present
            // but inverted, which is the playItem defect recorded at ShufflePlayer.markQueueReplaced in a
            // different costume. This site can read it validly; the resumption site below cannot.
            if (data.pos > 0)
                state.pendingRestoreSeek = data.items.getOrNull(data.index)?.mediaId?.let {
                    PlayerState.RestoreSeek(it, data.pos, player.queueEpochOrZero)
                }
        }
    }

    // Widget/notification resume — a pure consumer now: ensure the cold-start queue is applied (no
    // independent recoverPlaylist, so it can't race onCreate's restore), then honor the resume intent by
    // preparing an idle player so the caller's playWhenReady=true can start it. Warm player:
    // applyRestoreIfCold no-ops and we prepare/play whatever is already there.
    private fun resume(player: Player) = scope.future {
        applyRestoreIfCold(player)
        withContext(Dispatchers.Main) {
            if (player.mediaItemCount != 0 && player.playbackState == Player.STATE_IDLE) player.prepare()
        }
        SessionResult(RESULT_SUCCESS)
    }

    private var timerJob: Job? = null
    private fun onSleepTimer(player: Player, ms: Long): ListenableFuture<SessionResult> {
        timerJob?.cancel()
        val time = when (ms) {
            0L -> return Futures.immediateFuture(SessionResult(RESULT_SUCCESS))
            Long.MAX_VALUE -> player.run { duration - currentPosition }
            else -> ms
        }

        timerJob = scope.launch {
            delay(time)
            player.with { pause() }
        }
        return Futures.immediateFuture(SessionResult(RESULT_SUCCESS))
    }

    private fun setRepeat(player: Player, repeat: Int) = run {
        player.repeatMode = repeat
        Futures.immediateFuture(SessionResult(RESULT_SUCCESS))
    }

    private fun setShuffle(player: Player, enabled: Boolean) = run {
        player.shuffleModeEnabled = enabled
        Futures.immediateFuture(SessionResult(RESULT_SUCCESS))
    }


    @OptIn(UnstableApi::class)
    private fun radio(player: Player, args: Bundle) = scope.future {
        userQueueSet.set(true)
        val error = SessionResult(SessionError.ERROR_UNKNOWN)
        val extId = args.getString("extId") ?: return@future bug("radio", "missing extId")
        val itemArg = args.getSerialized<EchoMediaItem>("item")
        val item = itemArg?.getOrNull() ?: return@future bug(
            "radio", "item missing or undeserializable", itemArg?.exceptionOrNull()
        )
        val itemLoaded = args.getBoolean("loaded", false)
        val extension = extensions.music.getExtension(extId)
            ?: return@future notFound(R.string.extension)
        val newItem = if (itemLoaded) item else loadItem(extension, item)
        // ⚠⚠ NOT FOR TRACKS. This handler clears the queue and plays the generated mix, which
        // is correct for Album / Artist / Playlist / Radio and WRONG for a Track, whose station must be
        // seed-first. Tracks are routed to trackRadio at PlayerViewModel.radio - see the note there for the
        // three device-confirmed defects this produced, and DeezerRadioClient's TRACK branch for why the
        // seed vanishes silently rather than loudly.
        //
        // ⚠⚠ `prior` EXISTS BECAUSE A PINNED Loading STRANDS THE WHOLE RADIO SUBSYSTEM, NOT
        // JUST THIS REQUEST. Loading has to be set BEFORE start() - it is what stops topUpQueue racing the
        // load - but until 2026-09-10 the `loaded == null` return below left it set forever. Per the note
        // in PlayerRadio.play, a pinned Loading makes topUpQueue() AND startRadio() no-op for the REST OF
        // THE CONTEXT, so auto-radio and every queue top-up stop for the session. Restoring `prior` rather
        // than Empty is the correct undo: clearMediaItems() has not run yet at this point, so the previous
        // station is still the queue's station and nothing has changed.
        // THIS IS THE ONLY RETURN THAT NEEDS IT. The extId / item / extension returns and loadItem() all sit
        // ABOVE the Loading assignment, so they cannot strand it. No try/finally is needed either:
        // ExtensionUtils.getOrThrow(throwableFlow) rethrows ONLY CancellationException and converts every
        // other throwable into a reported null, so start() cannot throw past this point except when the
        // scope is already dying.
        //
        // ⚠️ [CORRECTED 2026-09-10] HOW THIS IS REACHED, because the first record of it named the
        // wrong route. It originally read: "reachable whenever start() returns null, INCLUDING
        // !item.isRadioSupported, which the smarttracklist work sets false on purpose". THAT ROUTE IS NOW
        // GONE - tracks route to trackRadio (see PlayerViewModel.radio), and isRadioSupported defaults to
        // true on Album/Artist/Playlist, so it will not fire on what still arrives here.
        // The hole did NOT become rare, it became INCIDENTAL rather than deterministic. start() also returns
        // null for any throwable, and DeezerRadioClient THROWS BY DESIGN on this path: its Album and
        // Playlist branches do `seeds.firstOrNull() ?: error("No Radio")`, and the context `when` ends in
        // `else -> error("No Radio")`. An album whose tracklist fetch fails or returns empty - an ordinary
        // network failure - lands on exactly the return below. Keeping the wrong route recorded because the
        // reasoning that produced it is the instructive part: a deliberate, easily-named cause was found
        // first and the far more common accidental one was nearly missed behind it.
        val prior = radioFlow.value
        radioFlow.value = PlayerState.Radio.Loading
        val loaded = PlayerRadio.start(
            throwableFlow, extension, newItem, null
        )
        if (loaded == null) {
            radioFlow.value = prior
            // ⚠⚠ A SPLIT, AND ONLY ONE HALF IS SILENT TODAY - AN UNCONDITIONAL EMIT HERE WOULD
            // DOUBLE-REPORT. PlayerRadio.start ends in getOrThrow(throwableFlow), which converts any
            // THROWN failure into a report plus null - and DeezerRadioClient throws "No Radio" by design
            // on its Album/Playlist branches, reachable on an ordinary network failure. Those are already
            // on the user's screen. The genuinely silent half is "this thing cannot have a radio at all":
            // !isRadioSupported, or an extension that is not a RadioClient. Only that half gets a message.
            if (!newItem.isRadioSupported || !extension.isClient<RadioClient>()) {
                app.messageFlow.emit(
                    Message(
                        app.context.getString(
                            R.string.no_x_found, app.context.getString(R.string.radio)
                        )
                    )
                )
            }
            Log.d(
                "GladixQueue",
                "radio: no station ext=$extId supported=${newItem.isRadioSupported}"
            )
            return@future error
        }
        player.with {
            clearMediaItems()
            shuffleModeEnabled = false
        }
        PlayerRadio.play(player, downloadFlow, app, radioFlow, loaded, extension, source = "radioCmd")
        player.with { play() }
        SessionResult(RESULT_SUCCESS)
    }

    // Seed-first radio for feed/search single-track "radio" tiles (Home "Mixes inspired by", search).
    // Mirrors PHONE exactly: queue + play the SEED first (like setQueue's single-track path), THEN APPEND
    // the generated radio (mirroring PlayerRadio.loadPlaylist's start+play) — instead of relying on
    // auto-radio to append, which doesn't fire on TV (so the seed looped and loadTracks never ran).
    // ⚠⚠ THE SEED IS NOT FILTERED HERE, AND THAT IS ONE HALF OF A TWO-SIDED CONTRACT.
    // This side queues the seed at index 0 unfiltered; the EXTENSION side strips the seed out of the
    // generated mix - see DeezerRadioClient's `if (kind == RadioKind.TRACK)` branch, which carries the
    // matching note. Neither half is correct alone: strip without queueing and the tapped track never
    // plays; queue without stripping and it plays twice back to back.
    // ⚠️ radio() ABOVE HONOURS NEITHER HALF. It clears the queue and plays the mix, so a Track
    // sent there loses its seed entirely. That is what long-press -> Radio did until 2026-09-10; tracks are
    // now routed here at PlayerViewModel.radio. THIS IS THE PATH A TRACK STATION MUST TAKE.
    // ⚠️ AND IT MUST STAY ONE COMMAND. Queueing the seed from the UI and then sending
    // radioCommand is NOT equivalent: as two separate async commands the append can read a STALE
    // currentMediaItem between them. That is why trackRadioCommand exists at all rather than being
    // composed at the call site.
    // ⚠⚠ THAT CLAUSE IS ABOUT COMMAND SPLITTING, NOT ABOUT DURATION - READ IT THAT WAY BEFORE
    // CONCLUDING THE BRIDGE BELOW CONTRADICTS IT. The bridge runs a ~12s Last.fm lookup INSIDE this same
    // coroutine and this same command, so nothing is split; the hazard the clause names cannot occur.
    // It is in fact SAFER than play()'s bridge on the clause's own terms: PlayerRadio.throwBridge takes
    // the seed as a PARAMETER and never reads player.currentMediaItem, so the stale read is structurally
    // impossible rather than merely unlikely.
    // ⚠️ AND THE MITIGATION IT IMPLIED IS SUPERSEDED, TWICE OVER. "Keep the window short" was
    // the original defence; the queue epoch replaced it with an actual test - appendDeduped drops and logs
    // any append whose queue was replaced mid-flight, which is the real guarantee the clause wanted.
    // Beyond that, the design this clause describes is itself the FIRST version of that fix, which was
    // then reworked twice before shipping: once "TV-scoped behind isTv" after the first reroute turned out
    // not to be TV-only and regressed phone, and finally to "reuse the proven generate-and-append
    // (startRadio/topUpQueue bodies untouched)". So it is a historically superseded note that is still
    // worth keeping for the command-splitting rule, not a live constraint on how long this command runs.
    @OptIn(UnstableApi::class)
    private fun trackRadio(player: Player, args: Bundle) = scope.future {
        userQueueSet.set(true)
        val error = SessionResult(SessionError.ERROR_UNKNOWN)
        // The only guards permitted BEFORE playback are true playback prerequisites: without extId we can't
        // build the seed's MediaItem, without a seed there is nothing to play. NO generation concern (the
        // extension lookup, radio start/play) may sit in front of playback — that shape caused the "tapping
        // does nothing" regression, so generation lives in the guarded block below, after the seed is playing.
        val extId = args.getString("extId") ?: return@future bug("track_radio", "missing extId")
        val seedArg = args.getSerialized<EchoMediaItem>("item")
        val seed = seedArg?.getOrNull() as? Track ?: return@future bug(
            // ⚠️ THE HIGHEST-VALUE GUARD IN THIS PASS. It sits BEFORE any playback, so firing it
            // means a tap that does nothing at all - the exact "tapping does nothing" regression recorded
            // at PlayerViewModel.playTrackRadio, whose cause was a serialization discriminator. One
            // message covers all three ways to get here (absent / undeserializable / not a Track); the
            // cause distinguishes them when there is one.
            "track_radio", "item missing, undeserializable, or not a Track", seedArg?.exceptionOrNull()
        )
        // Track-radio context: drives the "<title> Radio" header and is what generation runs from — the
        // same Radio the setQueue single-track path built on phone.
        val context = Radio(
            id = seed.id, title = "${seed.title} Radio", cover = seed.cover,
            extras = mapOf("radio" to "track")
        )
        // 1) Seed first — replace the queue with the single seed and play it (mirrors setQueue single).
        //    Committed here, before any generation step, so a missing extension or a generation failure can
        //    never leave nothing playing: worst case is "seed plays, no radio", never silence.
        val seedItem = MediaItemUtils.build(
            app, downloadFlow.value, MediaState.Unloaded(extId, seed), context
        )
        // ⚠⚠ CLAIMED BEFORE setMediaItems, AND THE ORDER IS FORCED - Media3 publishes the timeline
        // event SYNCHRONOUSLY INSIDE setMediaItems, so onTimelineChanged has already scheduled its
        // startRadio coroutine before the call returns. See the note at PlayerRadio.trackRadioGenerating
        // for the source citation, for why the queue epoch cannot serve here, and for why stateFlow is
        // not usable as the marker.
        // ⚠️ THIS REMOVAL IS SAFE ONLY BECAUSE trackRadio NOW BRIDGES ITS OWN start() FAILURE. Until
        // that landed, the incidental generation was the ONLY thing rescuing a YTM track radio, and
        // removing it would have silently un-fixed it.
        // ⚠️ AND THAT BRIDGE ALREADY CLOSED A LIVE GAP NOBODY HAD REPORTED: with autoStartRadio OFF,
        // startRadio returns on its first line, so TODAY a YTM track radio gets NO rescue at all in that
        // configuration. The accident only ever worked with the setting on. So this removal rests on a
        // fix that was doing more than unblocking it.
        // ⚠⚠ TAGGED GladixQueue, NOT GladixRadio, DELIBERATELY - THIS IS A DIAGNOSTIC AND THE
        // CAPTURE FILTER IS PART OF WHAT IT HAS TO SURVIVE. Two device captures on 2026-09-11 showed a
        // single GladixQueue line and no GladixRadio lines at all, and with a healthy station the ONLY
        // GladixRadio line either run should have produced was the suppression itself - so "trackRadio
        // never ran" and "GladixRadio was not in the filter" are indistinguishable from those captures.
        // Logging entry under the tag that is demonstrably being captured removes that ambiguity whichever
        // way it turns out.
        PlayerRadio.markTrackRadioGenerating(true)
        try {
            player.with {
                // ⚠⚠ [FIXED 2026-09-11] THIS ONE-ITEM QUEUE USED TO MAKE EVERY SINGLE-TRACK PLAY FETCH ITS
                // RADIO TWICE, ON EVERY EXTENSION INCLUDING DEEZER. A one-item queue has no next item, so the
                // onTimelineChanged this setMediaItems fires reached PlayerRadio.startRadio, whose
                // hasNextMediaItem() guard passed, which called loadPlaylist -> PlayerRadio.start - while this
                // handler was already calling PlayerRadio.start a few lines below. Two independent generations
                // from one tap, and one wasted network round trip per play.
                // It was the SECOND confirmed instance of the double-generation first seen in the 2026-09-11
                // artist-radio log, where a RESEED line appeared at a timestamp neither Last.fm lookup could
                // have produced - reconstructed from timing there, read straight off the call graph here.
                // Now suppressed by the markTrackRadioGenerating claim above, checked in loadPlaylist (NOT in
                // startRadio - topUpQueue reaches it too; see the note there).
                // ⚠️ IT WAS ALSO THE ONLY FINDING OF THAT STRETCH THAT COST THE PRIMARY EXTENSION ANYTHING:
                // everything else was YTM-only. Kept recorded rather than deleted because the ORDERING was the
                // hard part - it could not be removed until trackRadio bridged its own start() failure, since
                // until then the duplicate was the only thing making the YTM rescue reachable.
                // ⚠⚠ SALVAGED FROM A DELETED PROBE 2026-09-12 - THIS DOES NOT RESUME MUSIC, AND
                // THE REASON IS NOT VISIBLE FROM HERE. `playedDuration` is set in EXACTLY ONE PLACE,
                // DeezerParser's EPISODE branch, from Deezer's own bookmark - so it is POPULATED FOR
                // PODCASTS ONLY and a music seed always passes 0. Four call sites read it as a start
                // position (this one, PlayerViewModel's setQueue, and two in playItem) and every one of them
                // looks like a resume until you know that.
                // Recorded here because the probe note that carried it was removed with the restore-seek
                // proof, and the fact outlived the question that found it: it is what ruled out "the seed
                // carries a resume point" when a cold-start tap started mid-song.
                setMediaItems(listOf(seedItem), 0, seed.playedDuration ?: 0)
                (this as? ShufflePlayer)?.syncShuffleFlag(false)
                if (playbackState == Player.STATE_IDLE) prepare()
                playWhenReady = true
            }
            // 2) Generate the radio and append it after the seed (mirrors PlayerRadio.loadPlaylist: start +
            //    play). Fully guarded: a missing extension or any generation error is reported but cannot abort
            //    the seed already playing. Cancellation still propagates.
            val extension = extensions.music.getExtension(extId)
            // LEGITIMATELY SILENT, logged only. The seed is ALREADY PLAYING by the time we get here, so
            // this is a degraded success ("seed plays, no radio"), not a dead tap - see the guard note
            // above. A snackbar for a station the user never explicitly asked for would be noise.
            if (extension == null) Log.d("GladixQueue", "track_radio: no extension ext=$extId")
            if (extension != null) {
                var startFailure: Throwable? = null
                val loaded = PlayerRadio.start(
                    throwableFlow, extension, seed, context, onFailure = { startFailure = it }
                )
                if (loaded != null) PlayerRadio.play(
                    player, downloadFlow, app, radioFlow, loaded, extension, source = "trackRadio"
                )
                // ⚠⚠ THE SAME NULL-STATION FAILURE loadPlaylist BRIDGES, AND UNTIL 2026-09-11
                // THIS PATH HAD NO BRIDGE AT ALL. On YTM the rescue still happened, but only BY ACCIDENT:
                // the one-item queue set above has no next item, so onTimelineChanged reaches
                // PlayerRadio.startRadio, which calls loadPlaylist, which IS bridged. Turn autoStartRadio
                // off, or ever queue more than one item here, and the rescue silently disappeared.
                // This removes that dependency - and it is what has to land BEFORE the duplicate
                // getSongRadio call is removed, because that duplicate is currently the only thing making
                // the accident work. See the parked note at setMediaItems above.
                // ⚠️ THE CONTEXT STAMP IS `context`, THE REAL Radio BUILT ABOVE - not a
                // placeholder. Third distinct case for the same expression in throwBridge
                // (`(itemContext as? Radio) ?: trackRadioPlaceholder`): no station, live station, and now
                // an app-built track station. It passes the September rule unchanged - a real Radio
                // context passes through - and keeps the seed and its bridge tracks under one header.
                else startFailure?.let {
                    PlayerRadio.throwBridge(player, downloadFlow, app, extension, seed, it, context)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throwableFlow.emit(e)
        } finally {
            // Covers setMediaItems AND the generation, so neither a throw nor a cancellation can leave the
            // marker stuck and stop this queue ever generating.
            PlayerRadio.markTrackRadioGenerating(false)
        }
        SessionResult(RESULT_SUCCESS)
    }

    private suspend fun loadItem(
        extension: Extension<*>, item: EchoMediaItem,
    ) = when (item) {
        is Track -> extension.getAs<TrackClient, EchoMediaItem> { loadTrack(item, false) }
        is Album -> extension.getAs<AlbumClient, EchoMediaItem> { loadAlbum(item) }
        is Playlist -> extension.getAs<PlaylistClient, EchoMediaItem> { loadPlaylist(item) }
        is Artist -> extension.getAs<ArtistClient, EchoMediaItem> { loadArtist(item) }
        is Radio -> throw IllegalStateException()
    }.getOrThrow()

    private suspend fun listTracks(
        extension: Extension<*>, item: EchoMediaItem, loaded: Boolean,
    ) = when (item) {
        is Album -> extension.getAs<AlbumClient, PagedData<Track>> {
            val album = if (!loaded) loadAlbum(item) else item
            loadTracks(album)?.pagedDataOfFirst()
                ?: PagedData.empty()
        }

        is Playlist -> extension.getAs<PlaylistClient, PagedData<Track>> {
            val playlist = if (!loaded) loadPlaylist(item) else item
            loadTracks(playlist).pagedDataOfFirst()
        }

        is Radio -> extension.getAs<RadioClient, PagedData<Track>> {
            val radio = if (!loaded) loadRadio(item) else item
            loadTracks(radio).pagedDataOfFirst()
        }

        is Artist -> extension.getAs<ArtistClient, PagedData<Track>> {
            val artist = if (!loaded) loadArtist(item) else item
            loadFeed(artist).pagedDataOfFirst().toTracks()
        }

        is Track -> Result.success(PagedData.Single { listOf(item) })
    }

    // Fresh-resolve for a History/cache-miss tap: load the context's tracks live and return the
    // current+upcoming media items (fresh tapped track first, then the tracks after it; `before`
    // dropped for the current+upcoming model). Nothing from a stored track is replayed — only its id
    // is used to locate the fresh version, so a frozen/stale streamable token can never fail the tap.
    override suspend fun freshContextUpcoming(
        extId: String, context: EchoMediaItem, tappedTrackId: String
    ): List<MediaItem> {
        val extension = extensions.music.getExtension(extId) ?: return emptyList()
        val tracks = listTracks(extension, context, false).getOrElse {
            if (it is CancellationException) throw it
            throwableFlow.emit(it)
            return emptyList()
        }
        val (list, _) = extension.get { tracks.loadPage(null) }.getOrElse {
            if (it is CancellationException) throw it
            throwableFlow.emit(it)
            return emptyList()
        }
        // A miss means the freshly loaded context no longer contains the tapped track. Starting at the top
        // is the defensible fallback for the case that actually causes it — a track deleted from the
        // playlist since it was played — but it MUST NOT BE SILENT. Until 2026-09-04 a Radio context
        // reached here (Radio is an EchoMediaItem.Lists), where a miss was GUARANTEED because a radio
        // regenerates, and this fallback quietly played the new station's first track instead of the one
        // tapped. Callers now gate on MediaItemUtils.isReplayableContext, so a miss here is genuinely
        // unexpected. Log it rather than absorbing it.
        val foundIndex = list.indexOfFirst { it.id == tappedTrackId }
        if (foundIndex < 0) Log.w(
            "GladixContext",
            "tapped track not in freshly loaded context: ext=$extId ctx=${context.id} — starting at 0"
        )
        val correctIndex = foundIndex.coerceAtLeast(0)
        return list.subList(correctIndex, list.size).map {
            MediaItemUtils.build(app, downloadFlow.value, MediaState.Unloaded(extId, it), context)
        }
    }


    private fun playItem(player: Player, args: Bundle) = scope.future {
        userQueueSet.set(true)
        val error = SessionResult(SessionError.ERROR_UNKNOWN)
        val extId = args.getString("extId") ?: return@future bug("play", "missing extId")
        val itemArg = args.getSerialized<EchoMediaItem>("item")
        val item = itemArg?.getOrNull() ?: return@future bug(
            // The cause matters: getSerialized returns a Result and ?.getOrNull() THREW IT AWAY, so a
            // deserialization failure was indistinguishable from an absent key. Reporting the actual
            // exception is the difference between a useful non-fatal and another dead end.
            "play", "item missing or undeserializable", itemArg?.exceptionOrNull()
        )
        val loaded = args.getBoolean("loaded", false)
        val shuffle = args.getBoolean("shuffle", false)
        val startTrackId = args.getString("startTrackId")
        val extension = extensions.music.getExtension(extId)
            ?: return@future notFound(R.string.extension)
        when (item) {
            is Track -> {
                // P1: stamp a display-only "<track> Radio" context (LABEL_ONLY_RADIO) so the header
                // reads it from the first second instead of staying blank until the auto-radio's 2nd
                // track — same fix shipped for History bare/Radio. Stripped in PlayerRadio before real
                // radio generation, so the auto-radio is unchanged.
                val mediaItem = MediaItemUtils.build(
                    app, downloadFlow.value, MediaState.Unloaded(extId, item),
                    MediaItemUtils.trackRadioPlaceholder(item)
                )
                player.with {
                    setMediaItem(mediaItem)
                    prepare()
                    seekTo(item.playedDuration ?: 0)
                    play()
                }
            }

            else -> {
                val tracks = listTracks(extension, item, loaded).getOrElse {
                    if (it is CancellationException) throw it
                    throwableFlow.emit(it)
                    return@future error
                }

                // Carry `continuation` alongside the list so the empty check below can tell a genuinely
                // empty collection from an empty FIRST page that has more pages. Shuffle loadAll()s the
                // whole thing, so its continuation is always null (an empty result there IS genuinely empty).
                val result: Result<Pair<List<Track>, String?>> =
                    if (shuffle) extension.get { tracks.loadAll() }.map { it to null as String? }
                    // loadPage returns Page<Track>; `result` is a Pair because the shuffle branch above
                    // produces one from loadAll(). The conversion is NOT incidental - it is the only thing
                    // reconciling the two branches, and it was lost on 2026-09-10 when the loadAll
                    // continuation was lifted out of this block (it had been the block's trailing
                    // expression, one line below the launch that was being moved).
                    else runCatching {
                        val (list, continuation) = extension.get { tracks.loadPage(null) }.getOrThrow()
                        list to continuation
                    }
                val (list, continuation) = result.getOrElse {
                    if (it is CancellationException) throw it
                    throwableFlow.emit(it)
                    return@future error
                }
                if (list.isEmpty()) {
                    if (continuation == null) {
                        // Genuinely empty (shuffle's loadAll exhausted, or an empty first page with no more
                        // pages). Expected user input, NOT a bug — route to messageFlow (user snackbar), NOT
                        // throwableFlow, which records a Crashlytics non-fatal via App.throwFlow.
                        app.messageFlow.emit(Message(app.context.getString(R.string.list_is_empty)))
                    } else {
                        // Empty FIRST page but a continuation exists — a non-empty collection that paginates
                        // oddly (page 1 fully filtered/region-locked). Rare anomaly, so KEEP the signal: a
                        // distinct, extension-tagged report (rare → no Crashlytics flood, and not confusable
                        // with a genuine empty tap).
                        throwableFlow.emit(
                            Exception("Collection first page empty with continuation (ext=$extId)")
                        )
                    }
                    return@future error
                }
                val startIndex = when {
                    startTrackId != null -> list.indexOfFirst { it.id == startTrackId }.takeIf { it >= 0 } ?: 0
                    shuffle -> list.indices.random()
                    else -> 0
                }
                val startPos = list.getOrNull(startIndex)?.playedDuration ?: 0
                if (shuffle) (player as? ShufflePlayer)?.notifyFreshShuffle()
                player.with {
                    if (shuffle) {
                        // Shuffle keeps the whole list; enabling shuffle triggers changeQueue, which
                        // pulls the current track to index 0 and drops the rest above it.
                        val mediaItems = list.map {
                            MediaItemUtils.build(app, downloadFlow.value, MediaState.Unloaded(extId, it), item)
                        }
                        setMediaItems(mediaItems, startIndex, startPos)
                        shuffleModeEnabled = true
                    } else {
                        // P2 — current+upcoming: drop the tracks BEFORE the tapped one so it lands at
                        // index 0 (the subList-to-0 pattern freshContextUpcoming/History uses). Prevents
                        // stranded-above tracks and a non-zero persisted index (which would resume restore
                        // mid-queue).
                        val upcoming = list.subList(startIndex, list.size).map {
                            MediaItemUtils.build(app, downloadFlow.value, MediaState.Unloaded(extId, it), item)
                        }
                        setMediaItems(upcoming, 0, startPos)
                        // In-order Play: sync the shuffle flag/icon OFF without changeQueue (order-safe,
                        // cosmetic). `original` is already the in-order queue just set above.
                        (this as? ShufflePlayer)?.syncShuffleFlag(false)
                    }
                    if (playbackState == Player.STATE_IDLE) prepare()
                    play()
                }
                // ⚠⚠ THE REMAINING-PAGES LOAD IS STARTED HERE, AFTER setMediaItems - IT USED TO
                // BE STARTED ABOVE, INSIDE THE loadPage runCatching, AND THAT ORDER WAS WRONG TWICE OVER.
                // This is a detached scope.launch running an UNBOUNDED loadAll() (PagedData.loadAll has no
                // cap and no timeout), so it is the LONGEST-LIVED async append in the app and the only
                // fire-and-forget one: its `future` returns long before it does.
                //   1. Started above, it raced its OWN setMediaItems. A fast loadAll appended to the
                //      PREVIOUS queue, which setMediaItems then wiped - the extra pages silently vanished.
                //   2. It could not be epoch-gated from up there either: capturing the epoch before this
                //      call's own setMediaItems would make every append look stale and NO long collection
                //      would ever load past page one. Moving it below is what makes the gate correct rather
                //      than merely present.
                // Cost of the move: loadAll now starts a few ms later (after setMediaItems + prepare)
                // instead of racing it. Nothing waits on it either way.
                //
                // ⚠️ [CORRECTED 2026-09-10] A PROJECT RECORD DESCRIBES THIS LAUNCH AS CANCELLABLE
                // AND IT IS NOT, WHICH MATTERS BECAUSE IT WOULD ARGUE AGAINST THE GATE BELOW. It reads:
                // "If user's playItem(albumTrack) had already called setMediaItems(albumTracks) and launched
                // loadJob_album, that job is cancelled by resume() overwriting the queue."
                // THE TREE: there is no loadJob_album and never was - PlayerCallback holds exactly two Job
                // fields, timerJob and nextJob, neither queue-related; resume() cancels nothing; and
                // `git log -S loadJob_album --all` returns ZERO commits across full history (which reaches
                // 2023-12-31, so May is well inside it). The identifier never existed. The only loadJob in
                // the app is StreamableMediaSource's, per-source streamable loading, unrelated to queue
                // lifetime.
                // BUT THE SHARPEST FRAMING IS NOT "it was removed" OR "it never existed" - IT IS THAT THE
                // JOB EXISTS AND THE HANDLE DOES NOT. The launch below IS the job that record was naming;
                // it simply has no name and no one keeps its Job, so there is nothing to call cancel() on.
                // That is precisely the machinery option (a) would have had to add, and precisely why (b)
                // was cheaper.
                // THE CHARITABLE READING IS ALMOST CERTAINLY WHAT WAS MEANT: "cancelled" as in OVERWRITTEN -
                // the launch's append landing in a queue that setMediaItems then wiped. That is a real
                // effect, and it is the second latent bug described above. But overwriting is NOT
                // cancelling: the work still runs, still spends the network, and protects nothing unless
                // loadAll happens to finish FIRST. When it finishes second - the normal case, since
                // loadAll is unbounded - the append lands in the new queue and stays. Either way there is
                // no cancellation mechanism here to lean on.
                if (continuation != null) {
                    val epoch = player.queueEpochOrZero
                    scope.launch {
                        val all = extension.get { tracks.loadAll() }.getOrElse {
                            if (it is CancellationException) throw it
                            throwableFlow.emit(it)
                            return@launch
                        }.drop(list.size).map {
                            MediaItemUtils.build(
                                app, downloadFlow.value, MediaState.Unloaded(extId, it), item
                            )
                        }
                        // Append remaining pages at the END (robust to the first page having been
                        // subList-trimmed to the tapped track, and to mid-load advances) - but only if
                        // this is still the queue they were loaded for.
                        player.with {
                            if (queueEpochOrZero != epoch) {
                                Log.d(
                                    "GladixQueue",
                                    "stale_drop site=playItem_loadAll n=${all.size} " +
                                        "started=$epoch now=$queueEpochOrZero"
                                )
                                return@with
                            }
                            addMediaItems(all)
                        }
                    }
                }
            }
        }
        SessionResult(RESULT_SUCCESS)
    }

    // Player receiver: for a reference ALREADY resolved on the application thread. Every caller of this
    // overload receives one as a `player: Player` parameter (getImage, resume, radio, playItem, …), so
    // the accessor has already run safely. Kept as-is.
    // ⚠⚠ THREE DESTINATIONS, CHOSEN PER GUARD - NOT ONE BLANKET RULE. Added 2026-09-11 after a
    // pass over every handler reached through PlayerViewModel.withBrowser, because "tap does nothing, no
    // message, no log" is the worst failure shape in this app and these guards were unreportable BY
    // CONSTRUCTION (withBrowser discards every SessionResult - see the note there).
    //   bug()      PROGRAMMER ERROR. The ViewModel/handler contract is broken - a missing Bundle key, an
    //              item that would not deserialize. No user message: there is nothing actionable to say
    //              and "something went wrong" is silence plus a toast. Goes to throwableFlow, i.e. a
    //              Crashlytics non-fatal naming the command and the key.
    //   notFound() USER-VISIBLE and actionable. Mirrors FeedClickListener.notFoundSnack's wording so the
    //              phrasing matches what the UI already says elsewhere.
    //   (silent)   Expected, routine, or already-reported. Left alone DELIBERATELY, with the reason at the
    //              site - the same way PlayerBitmapLoader was excluded from the futureCatching pass.
    //
    // THE PRECEDENT THIS FOLLOWS, verified in history rather than assumed: f8f1e0a7 (2026-07-09) split
    // playItem's one empty-list guard into TWO destinations - messageFlow for the genuinely empty case,
    // throwableFlow for the anomalous empty-first-page-with-continuation case - restructured the code to
    // carry `continuation` so the two could be told apart, and wrote the rationale in the same hunk
    // ("Expected user input, NOT a bug"). Deliberate classification, not a drive-by move.
    //
    // ⚠️ DELIVERY CAVEAT FOR notFound(): app.messageFlow's only subscriber is SnackBarHandler's
    // lifecycle-gated observe() (flowWithLifecycle, STARTED) and the flow has replay = 0, so a message
    // emitted while the Activity is stopped is DROPPED, not buffered. Every notFound() call site below is
    // PRE-SUSPEND - it fires microseconds after the tap, while the Activity is still STARTED - so this is
    // reliable for them. The guards that sit BEHIND a network call additionally Log.d, because those can
    // land after the user has backgrounded the app and the snackbar would be lost. That is the parked
    // held-state item; the logs close its "no log" half without pretending the gap is gone.
    private suspend fun bug(command: String, detail: String, cause: Throwable? = null) =
        SessionResult(SessionError.ERROR_UNKNOWN).also {
            throwableFlow.emit(IllegalStateException("custom command '$command': $detail", cause))
        }

    // Non-suspend variant for the inline dispatch branches, which are not in a coroutine.
    private fun bugAsync(command: String, detail: String) {
        scope.launch {
            throwableFlow.emit(IllegalStateException("custom command '$command': $detail"))
        }
    }

    private suspend fun notFound(nameRes: Int) =
        SessionResult(SessionError.ERROR_UNKNOWN).also {
            app.messageFlow.emit(
                Message(app.context.getString(R.string.no_x_found, app.context.getString(nameRes)))
            )
        }

    private suspend fun <T> Player.with(block: suspend Player.() -> T): T =
        withContext(Dispatchers.Main) { block() }

    // Session receiver: resolves session.player INSIDE the main dispatch. Use this whenever you hold
    // only the session. The Player overload above cannot be used for that, because a receiver is
    // evaluated at the CALL SITE — so `session.player.with { … }` from a scope.future on Dispatchers.IO
    // reads the accessor on IO and, from Media3 1.11.0, throws IllegalStateException before the block is
    // ever posted to Main. It looks protected and is not; that trap is why this overload exists.
    private suspend fun <T> MediaSession.with(block: suspend Player.() -> T): T =
        withContext(Dispatchers.Main) { player.block() }

    private suspend fun <T : Any> PagedData<T>.load(
        pages: Int = 5,
    ) = runCatching {
        val list = mutableListOf<T>()
        var page = loadPage(null)
        list.addAll(page.data)
        var count = 0
        while (page.continuation != null && count < pages) {
            page = loadPage(page.continuation)
            list.addAll(page.data)
            count++
        }
        list
    }

    // History-tap enqueue (phone). Loads the context FRESH and sets it as the current+upcoming queue,
    // starting at the tapped track's fresh version — replacing the old setQueue([storedTrack]) fast-start
    // + insert-after, which replayed the stored track's stale resolution state (dead token → skip). The
    // set (via the ShufflePlayer override) wipes the previous queue and back-stack. Loses the instant
    // fast-start (which was broken for stale-token entries anyway) for a correct, always-resolving tap.
    private fun backfillQueue(player: Player, args: Bundle) = scope.future {
        val error = SessionResult(SessionError.ERROR_UNKNOWN)
        val extId = args.getString("extId") ?: return@future bug("backfill", "missing extId")
        val itemArg = args.getSerialized<EchoMediaItem>("item")
        val item = itemArg?.getOrNull() ?: return@future bug(
            "backfill", "item missing or undeserializable", itemArg?.exceptionOrNull()
        )
        val startTrackId = args.getString("startTrackId")
            ?: return@future bug("backfill", "missing startTrackId")
        // ⚠⚠ THE ONLY ASYNC PATH THAT *REPLACES* THE QUEUE RATHER THAN APPENDING TO IT, WHICH
        // MAKES A STALE ONE DESTRUCTIVE, NOT MERELY UNTIDY: freshContextUpcoming is a network fetch, and if
        // the user starts something else while it runs, this setMediaItems WIPES the queue they are now
        // listening to and replaces it with the History item they tapped earlier. Every other site here
        // pollutes; this one destroys.
        val epoch = player.queueEpochOrZero
        val upcoming = freshContextUpcoming(extId, item, startTrackId)
        if (upcoming.isEmpty()) {
            // POST-NETWORK, so the snackbar can be lost if the user backgrounded the app while
            // freshContextUpcoming was running - hence the log as well. A History tap that rebuilds to
            // nothing is otherwise a completely dead tap, which is the shape this whole pass is about.
            Log.d("GladixQueue", "backfill: rebuilt context empty ext=$extId")
            app.messageFlow.emit(
                Message(app.context.getString(R.string.could_not_load_x, item.title))
            )
            return@future error
        }
        withContext(Dispatchers.Main) {
            if (player.queueEpochOrZero != epoch) {
                Log.d(
                    "GladixQueue",
                    "stale_drop site=backfill n=${upcoming.size} " +
                        "started=$epoch now=${player.queueEpochOrZero}"
                )
                return@withContext
            }
            player.setMediaItems(upcoming, 0, 0)
            // History tap: in-order current+upcoming — sync the shuffle flag/icon OFF without changeQueue.
            (player as? ShufflePlayer)?.syncShuffleFlag(false)
            player.prepare()
            player.playWhenReady = true
        }
        SessionResult(RESULT_SUCCESS)
    }

    private fun addToQueue(player: Player, args: Bundle) = scope.future {
        val error = SessionResult(SessionError.ERROR_UNKNOWN)
        val extId = args.getString("extId") ?: return@future bug("add_to_queue", "missing extId")
        val itemArg = args.getSerialized<EchoMediaItem>("item")
        val item = itemArg?.getOrNull() ?: return@future bug(
            "add_to_queue", "item missing or undeserializable", itemArg?.exceptionOrNull()
        )
        val loaded = args.getBoolean("loaded", false)
        val extension = extensions.music.getExtension(extId)
            ?: return@future notFound(R.string.extension)
        // ⚠️ USER-INITIATED, AND DROPPING IS STILL RIGHT - the one site where that is a judgement
        // rather than obvious. "Add to queue" means add to THE QUEUE THAT EXISTED WHEN IT WAS TAPPED; if the
        // user has since replaced that queue outright, the later action supersedes this one. An ordinary
        // track advance does NOT bump the epoch, so this can only drop after a real replacement.
        val epoch = player.queueEpochOrZero
        val tracks = listTracks(extension, item, loaded).getOrElse {
            if (it is CancellationException) throw it
            throwableFlow.emit(it)
            return@future error
        }.load().getOrElse {
            if (it is CancellationException) throw it
            throwableFlow.emit(it)
            return@future error
        }
        if (tracks.isEmpty()) {
            // POST-NETWORK: the snackbar is lost if the user backgrounded the app while listTracks ran,
            // so this logs too. Same string and same meaning as playItem's genuinely-empty case - the
            // collection really has nothing in it, which is expected user input rather than a fault.
            Log.d("GladixQueue", "add_to_queue: nothing to add ext=$extId")
            app.messageFlow.emit(Message(app.context.getString(R.string.list_is_empty)))
            return@future error
        }
        // P5: give added tracks a source label so they don't show a blank header when reached. A
        // collection (Album/Playlist/Artist/Radio) is its own source; a lone track gets the display-only
        // "<track> Radio" placeholder (stripped in PlayerRadio), consistent with a bare-track play.
        val addedContext = item.takeUnless { it is Track }
        val mediaItems = tracks.map { track ->
            MediaItemUtils.build(
                app,
                downloadFlow.value,
                MediaState.Unloaded(extId, track),
                addedContext ?: MediaItemUtils.trackRadioPlaceholder(track)
            )
        }
        player.with {
            if (queueEpochOrZero != epoch) {
                Log.d(
                    "GladixQueue",
                    "stale_drop site=addToQueue n=${mediaItems.size} started=$epoch now=$queueEpochOrZero"
                )
                return@with
            }
            addMediaItems(mediaItems)
            prepare()
        }
        SessionResult(RESULT_SUCCESS)
    }

    private var next = 0
    private var nextJob: Job? = null
    private fun addToNext(player: Player, args: Bundle) = scope.future {
        val error = SessionResult(SessionError.ERROR_UNKNOWN)
        val extId = args.getString("extId") ?: return@future bug("add_to_next", "missing extId")
        val itemArg = args.getSerialized<EchoMediaItem>("item")
        val item = itemArg?.getOrNull() ?: return@future bug(
            "add_to_next", "item missing or undeserializable", itemArg?.exceptionOrNull()
        )
        val loaded = args.getBoolean("loaded", false)
        val extension = extensions.music.getExtension(extId)
            ?: return@future notFound(R.string.extension)
        nextJob?.cancel()
        // ⚠️ WORSE THAN A PLAIN APPEND IF STALE: the insert position below is computed from the
        // LIVE queue (currentMediaItemIndex + 1 + next), with `next` a running offset across recent adds, so
        // a stale insert lands at an index that means nothing in the queue it arrives in.
        val epoch = player.queueEpochOrZero
        val tracks = listTracks(extension, item, loaded).getOrElse {
            if (it is CancellationException) throw it
            throwableFlow.emit(it)
            return@future error
        }.load().getOrElse {
            if (it is CancellationException) throw it
            throwableFlow.emit(it)
            return@future error
        }
        if (tracks.isEmpty()) {
            // POST-NETWORK: the snackbar is lost if the user backgrounded the app while listTracks ran,
            // so this logs too. Same string and same meaning as playItem's genuinely-empty case - the
            // collection really has nothing in it, which is expected user input rather than a fault.
            Log.d("GladixQueue", "add_to_next: nothing to add ext=$extId")
            app.messageFlow.emit(Message(app.context.getString(R.string.list_is_empty)))
            return@future error
        }
        // P5: same source-label treatment as addToQueue — collection context, else track-radio placeholder.
        val addedContext = item.takeUnless { it is Track }
        val mediaItems = tracks.map { track ->
            MediaItemUtils.build(
                app,
                downloadFlow.value,
                MediaState.Unloaded(extId, track),
                addedContext ?: MediaItemUtils.trackRadioPlaceholder(track)
            )
        }
        var inserted = false
        player.with {
            if (queueEpochOrZero != epoch) {
                Log.d(
                    "GladixQueue",
                    "stale_drop site=addToNext n=${mediaItems.size} started=$epoch now=$queueEpochOrZero"
                )
                return@with
            }
            if (mediaItemCount == 0) playWhenReady = true
            // Current index so "play next" inserts right after the CURRENT track.
            val fullIndex = currentMediaItemIndex
            addMediaItems(fullIndex + 1 + next, mediaItems)
            prepare()
            inserted = true
        }
        // Only advance the running offset if the insert actually happened - otherwise `next` drifts past a
        // drop and the FOLLOWING add lands too far out. (It self-heals after nextJob's 5s reset, but a
        // silently wrong position in that window is the kind of thing that gets reported as "play next put
        // it in the wrong place".)
        if (!inserted) return@future error
        next += mediaItems.size
        nextJob = scope.launch {
            delay(5000)
            next = 0
        }
        SessionResult(RESULT_SUCCESS)
    }

    // THE SINGLE LIKE WRITE PATH. Body of BOTH onSetRating overloads below. It takes the target item and
    // ITS index rather than reading currentMediaItem / currentMediaItemIndex, which is the whole reason the
    // mediaId overload can be correct for a track that is not the current one.
    // `index` is a Player-level index into the CURRENT timeline - the same space replaceMediaItem uses - so
    // ShufflePlayer's original-list update still keys off it exactly as it did when this read
    // currentMediaItemIndex.
    // The Track comes from item.track, i.e. mediaMetadata.extras (MediaItemUtils:260). That is why this
    // cannot be called without a MediaItem, and why the mediaId overload must return rather than "like
    // anyway" when the id is not in the timeline - there would be no Track to hand to likeItem.
    private suspend fun applyRating(
        session: MediaSession, item: MediaItem, index: Int, liked: Boolean,
    ): SessionResult {
        val track = item.track
        runCatching {
            val extension = extensions.music.getExtensionOrThrow(item.extensionId)
            // Any? (not Unit): the result is discarded below; an extension whose likeItem drifted to
            // return a value would otherwise crash with "String cannot be cast to Unit".
            extension.getAs<LikeClient, Any?> {
                likeItem(track, liked)
            }
        }.getOrElse {
            if (it is CancellationException) throw it
            throwableFlow.emit(PlayerException(item, it))
            // ERROR_UNKNOWN, deliberately NOT ERROR_BAD_VALUE: the sheet treats BAD_VALUE as "not in the
            // timeline, retry via the extension path", so returning it here would like the track twice.
            return SessionResult(SessionError.ERROR_UNKNOWN)
        }
        val newItem = item.run {
            buildUpon().setMediaMetadata(
                mediaMetadata.buildUpon().setUserRating(ThumbRating(liked)).build()
            )
        }.build()
        session.with { replaceMediaItem(index, newItem) }
        return SessionResult(RESULT_SUCCESS, Bundle().apply { putBoolean("liked", liked) })
    }

    override fun onSetRating(
        session: MediaSession, controller: MediaSession.ControllerInfo, rating: Rating,
    ): ListenableFuture<SessionResult> {
        return if (rating !is ThumbRating) super.onSetRating(session, controller, rating)
        else scope.future {
            val target = session.with { currentMediaItem?.let { it to currentMediaItemIndex } }
                ?: return@future SessionResult(SessionError.ERROR_UNKNOWN)
            applyRating(session, target.first, target.second, rating.isThumbsUp)
        }
    }

    // mediaId-scoped rating. Exists so the player's overflow sheet can like the track it is showing without
    // duplicating the buildUpon/replaceMediaItem write: before this, the sheet called LikeClient directly
    // and never touched the player, so trackHeart (driven by MediaItem.isLiked, i.e. userRating metadata)
    // stayed stale until something else rebuilt the item.
    // Media3 declares this overload (MediaSession.Callback:1787) and MediaController.setRating(mediaId,
    // rating) (:1134); the default returns ERROR_NOT_SUPPORTED, which is why it did nothing before.
    //
    // ⚠️ RESULT_ERROR_BAD_VALUE IS LOAD-BEARING CONTROL FLOW, not just a status. The caller falls back to
    // the extension-only path on it, so it must be unambiguous. Verified against media3 1.11.0:
    // MediaSessionStub.sendSessionResult (:174) hands our SessionResult to the controller UNMODIFIED, and
    // the only codes the transport substitutes are RESULT_INFO_SKIPPED (cancellation), ERROR_NOT_SUPPORTED
    // (an UnsupportedOperationException cause) and ERROR_UNKNOWN (any other execution failure), plus
    // ERROR_PERMISSION_DENIED rejected before the callback runs. BAD_VALUE can therefore only originate
    // here. Do not reuse it for any other outcome in this file.
    override fun onSetRating(
        session: MediaSession, controller: MediaSession.ControllerInfo, mediaId: String, rating: Rating,
    ): ListenableFuture<SessionResult> {
        return if (rating !is ThumbRating) super.onSetRating(session, controller, mediaId, rating)
        else scope.future {
            val target = session.with {
                (0 until mediaItemCount).firstNotNullOfOrNull { i ->
                    val mediaItem = getMediaItemAt(i)
                    if (mediaItem.mediaId == mediaId) mediaItem to i else null
                }
            } ?: return@future SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE)
            applyRating(session, target.first, target.second, rating.isThumbsUp)
        }
    }

    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        isForPlayback: Boolean,
    ): ListenableFuture<MediaItemsWithStartPosition> {
        // Claim SYNCHRONOUSLY on the application looper (Media3 invokes this callback there) so the
        // app-open applyRestoreIfCold, on Main, sees the marker and defers — the framework is about to
        // apply the same queue, and a second setMediaItems would tear down and re-prepare. Only the
        // isForPlayback path applies; the metadata-only stub below never sets it.
        if (isForPlayback) state.resumptionApplying = true
        return scope.futureCatching {
            if (!isForPlayback) {
                // System UI metadata-only request (e.g. lock-screen notification after reboot).
                // Media3 will not call play() — return a single stub item, no queue restore needed.
                // Read-only: recoverTracks() skips the orphaned-session clearQueue() side effect that
                // would destroy queue files before the isForPlayback=true full restore fires.
                val tracks = context.recoverTracks()
                    ?: throw UnsupportedOperationException("No saved queue")
                val rawIndex = context.recoverIndex() ?: 0
                // Same repair as recoverPlaylist so the lock-screen metadata tile shows the true current.
                val index = resolveCurrentIndex(tracks, rawIndex, context.recoverCurrentId()) { it.first.item.id }
                val (s, ctx) = tracks.getOrNull(index) ?: tracks.firstOrNull()
                    ?: throw UnsupportedOperationException("No saved queue")
                val item = MediaItemUtils.build(app, downloadFlow.value, s, ctx)
                return@futureCatching MediaItemsWithStartPosition(listOf(item), 0, 0L)
            }
            try {
                // ⚠⚠ THIS THROW DOES NOT PREVENT PLAYBACK, AND THE LOG LINE OVERSTATES WHAT
                // IT DOES. Read from media3-session 1.11.0, MediaSessionImpl's resumption
                // FutureCallback: its onFailure arm logs the UnsupportedOperationException and then
                // calls Util.handlePlayButtonAction(playerWrapper) anyway, under the comment
                // "Play as requested even if playback resumption fails." BOTH of the throws in
                // this function hit that arm - this one and the "No saved queue" ones above.
                // SO WHAT IS SKIPPED IS THE QUEUE RESTORE, NOT THE PLAY. If a queue is already on the
                // player (which is exactly what activeLoadCount > 0 implies - something is resolving),
                // the play lands on it and is audible.
                // ⚠️ THERE IS A REAL GATE AND IT IS NOT OURS TO USE. MediaSessionImpl consults
                // onPlayRequested() BEFORE any of this and drops the request entirely if it resolves
                // false - but that lives on MediaSession.Listener, which MediaSessionService
                // implements internally; it is not an app-facing Callback override. Do not go looking
                // for it as a hook.
                // ⚠️ RELEVANCE TO THE OPEN COLD-START AUTOPLAY ITEM, STATED CAREFULLY: this is
                // NOT a new source of a play request. Both routes into that method require an
                // incoming play - MediaSessionLegacyStub (media button, system UI, legacy AA) and
                // MediaSessionStub (an AIDL MediaController.play()). What it DOES mean is that a play
                // arriving DURING a cold-start load is not declined even though this code reads as
                // declining it. That is worth knowing for an investigation whose finding is "a real
                // play request is arriving and its source is unidentified" - it does not name the
                // source, but it removes this function from the list of things that would have
                // stopped it.
                if (state.activeLoadCount.get() > 0) {
                    Log.d("GladixPlayback", "onPlaybackResumption: skipping RESTORE (play still proceeds), activeLoadCount=${state.activeLoadCount.get()}")
                    withContext(Dispatchers.Main) { state.resumptionApplying = false }
                    throw UnsupportedOperationException("Load in progress")
                }
                // Consumer of the shared restore — no independent recoverPlaylist. Return the snapshot to
                // Media3, which sets it on the player and plays; the timeline listener then clears the
                // marker. We do NOT claim userQueueSet: the marker + mediaItemCount gate coordinate with
                // applyRestoreIfCold, and Media3 already gates us on getCurrentMediaItem()==null.
                val data = state.restoreDeferred?.await()
                if (data == null) {
                    withContext(Dispatchers.Main) { state.resumptionApplying = false }
                    throw UnsupportedOperationException("No saved queue")
                }
                withContext(Dispatchers.Main) {
                    mediaSession.player.shuffleModeEnabled = data.shuffle
                    mediaSession.player.repeatMode = data.repeat
                    // Arm the cold-start re-seek: Media3 applies startPositionMs below via the same 3-arg
                    // setMediaItems and loses it at prepare() identically (see PlayerState).
                    // ⚠⚠ ARMED WITH epoch = null DELIBERATELY - THIS SITE CANNOT READ A VALID
                    // EPOCH AND MUST NOT PRETEND TO. We return MediaItemsWithStartPosition and MEDIA3
                    // applies the queue afterwards, so the bump happens outside this function: a read here
                    // captures the PREVIOUS queue's value, and keying on it would make every consume look
                    // stale and disable the re-seek this latch exists for. There is no later point inside
                    // this function to move the arm to - the application is the caller's, not ours.
                    // ⚠️ "+1 FROM HERE" WAS CONSIDERED AND REJECTED: it would be arithmetic about
                    // a library's internal sequencing, correct only while nothing else replaces the queue
                    // in between, and it would fail SILENTLY when that stopped holding.
                    // ⚠️ RESIDUAL, STATED RATHER THAN PAPERED OVER: this path keeps TODAY'S
                    // behaviour - mediaId + belt only - so the defect above remains reachable HERE, through
                    // a much narrower window. onPlaybackResumption runs because the system asked to resume,
                    // and Media3 applies-and-plays immediately, so the arm-to-consume gap is short and has
                    // no user tap in it by construction. Not zero: a tap on the SAME track inside that
                    // buffering window would still be seeked. Closing it needs an arm that fires after the
                    // application - a Player.Listener-side re-arm - which is a structural change, not this one.
                    if (data.pos > 0)
                        state.pendingRestoreSeek =
                            data.items.getOrNull(data.index)?.mediaId?.let {
                                PlayerState.RestoreSeek(it, data.pos, null)
                            }
                }
                Log.d("GladixPlayback", "onPlaybackResumption: items=${data.items.size}")
                MediaItemsWithStartPosition(data.items.map { withUnloaded(it) }, data.index, data.pos)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { state.resumptionApplying = false }
                if (e !is UnsupportedOperationException && e !is CancellationException) throwableFlow.emit(e)
                throw e
            }
        }
    }

    private fun withUnloaded(item: MediaItem): MediaItem {
        val bundle = Bundle().apply {
            putAll(item.mediaMetadata.extras!!)
            putBoolean("loaded", false)
        }
        return item.buildUpon()
            .setMediaMetadata(item.mediaMetadata.buildUpon().setExtras(bundle).build())
            .build()
    }

    @OptIn(UnstableApi::class)
    override fun onPlayerInteractionFinished(
        session: MediaSession,
        controllerInfo: MediaSession.ControllerInfo,
        playerCommands: Player.Commands,
    ) {
        if (playerCommands.contains(Player.COMMAND_PLAY_PAUSE))
            Log.d("GladixAuto", "onPlayerInteractionFinished: PLAY_PAUSE from ${controllerInfo.packageName}")
        super.onPlayerInteractionFinished(session, controllerInfo, playerCommands)
    }

    class ButtonReceiver : MediaButtonReceiver() {
        override fun shouldStartForegroundService(context: Context, intent: Intent): Boolean {
            // Existence-only gate: cheap stat() via hasSavedQueue, NOT a full recoverTracks() decode of the
            // saved queue. shouldStartForegroundService runs synchronously on the main thread (BroadcastReceiver),
            // so decoding a large queue here ANRs; we only need to know whether a queue exists.
            val hasQueue = hasSavedQueue(context)
            if (!hasQueue) Toast.makeText(
                context,
                context.getString(R.string.no_last_played_track_found),
                Toast.LENGTH_SHORT
            ).show()
            return hasQueue
        }
    }

    companion object {
        fun PagedData<Shelf>.toTracks() = map {
            it.getOrThrow().mapNotNull { shelf ->
                when (shelf) {
                    is Shelf.Category -> null
                    is Shelf.Item -> listOfNotNull(shelf.media as? Track)
                    is Shelf.Lists.Categories -> null
                    is Shelf.Lists.Items -> shelf.list.filterIsInstance<Track>()
                    is Shelf.Lists.Tracks -> shelf.list
                }
            }.flatten()
        }
    }
}