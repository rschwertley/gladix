package dev.brahmkshatriya.echo.playback

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.Log
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
import dev.brahmkshatriya.echo.extensions.MediaState
import dev.brahmkshatriya.echo.playback.MediaItemUtils
import dev.brahmkshatriya.echo.playback.MediaItemUtils.extensionId
import dev.brahmkshatriya.echo.playback.MediaItemUtils.track
import dev.brahmkshatriya.echo.playback.ResumptionUtils.recoverCurrentId
import dev.brahmkshatriya.echo.playback.ResumptionUtils.recoverIndex
import dev.brahmkshatriya.echo.playback.ResumptionUtils.recoverPlaylist
import dev.brahmkshatriya.echo.playback.ResumptionUtils.resolveCurrentIndex
import dev.brahmkshatriya.echo.playback.ResumptionUtils.recoverRepeat
import dev.brahmkshatriya.echo.playback.ResumptionUtils.recoverShuffle
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

    override fun getCurrentExtension(): MusicExtension? {
        val aaEligible = { ext: MusicExtension ->
            ext.isEnabled && ext.id != UnifiedExtension.UNIFIED_ID
        }
        return lastBrowsedExtId
            ?.takeIf { it != UnifiedExtension.UNIFIED_ID }
            ?.let { id -> extensionList.value.firstOrNull { it.id == id } }
            ?: extensions.current.value?.takeIf { aaEligible(it) }
            ?: extensionList.value.firstOrNull(aaEligible)
    }

    private val radioFlow get() = state.radio

    override fun onConnect(
        session: MediaSession, controller: MediaSession.ControllerInfo,
    ): MediaSession.ConnectionResult {
        val sessionCommands = with(PlayerCommands) {
            MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                .add(likeCommand).add(unlikeCommand).add(repeatCommand).add(repeatOffCommand)
                .add(repeatOneCommand).add(shuffleCommand).add(shuffleOffCommand)
                .add(radioCommand).add(sleepTimer)
                .add(playCommand).add(addToQueueCommand).add(addToNextCommand)
                .add(resumeCommand).add(imageCommand).add(backfillCommand)
                .add(seekToFullCommand)
                .build()
        }
        return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
            .setAvailableSessionCommands(sessionCommands).build()
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
            sleepTimer -> onSleepTimer(player, args.getLong("ms"))
            resumeCommand -> resume(player)
            imageCommand -> getImage(player)
            backfillCommand -> backfillQueue(player, args)
            seekToFullCommand -> run {
                // Phone queue tap: seek by FULL index on the real player. seekToFullIndex no-ops on an
                // out-of-range (stale/racing) index, which the raw controller seekTo can't — that guard
                // is why this stays a custom command. `play` preserves play()=true / seek()=false. AA's
                // onSkipToQueueItem path (seekToDefaultPosition) never enters here.
                (player as? ShufflePlayer)?.seekToFullIndex(args.getInt("index"), args.getBoolean("play"))
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
        if (!app.settings.getBoolean(KEEP_QUEUE, true)) return
        val data = state.restoreDeferred?.await() ?: return
        withContext(Dispatchers.Main) {
            if (player.mediaItemCount != 0 || state.resumptionApplying) return@withContext
            if (!userQueueSet.compareAndSet(false, true)) return@withContext
            player.shuffleModeEnabled = data.shuffle
            player.repeatMode = data.repeat
            player.setMediaItems(data.items.toMutableList(), data.index, data.pos)
            // Arm the cold-start re-seek: the startPositionMs above is lost at prepare() (see PlayerState).
            if (data.pos > 0)
                state.pendingRestoreSeek = data.items.getOrNull(data.index)?.mediaId?.let { it to data.pos }
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
        val extId = args.getString("extId") ?: return@future error
        val item = args.getSerialized<EchoMediaItem>("item")?.getOrNull() ?: return@future error
        val itemLoaded = args.getBoolean("loaded", false)
        val extension = extensions.music.getExtension(extId) ?: return@future error
        val newItem = if (itemLoaded) item else loadItem(extension, item)
        radioFlow.value = PlayerState.Radio.Loading
        val loaded = PlayerRadio.start(
            throwableFlow, extension, newItem, null
        )
        if (loaded == null) return@future error
        player.with {
            clearMediaItems()
            shuffleModeEnabled = false
        }
        PlayerRadio.play(player, downloadFlow, app, radioFlow, loaded)
        player.with { play() }
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
        val correctIndex = list.indexOfFirst { it.id == tappedTrackId }.takeIf { it >= 0 } ?: 0
        return list.subList(correctIndex, list.size).map {
            MediaItemUtils.build(app, downloadFlow.value, MediaState.Unloaded(extId, it), context)
        }
    }


    private fun playItem(player: Player, args: Bundle) = scope.future {
        userQueueSet.set(true)
        val error = SessionResult(SessionError.ERROR_UNKNOWN)
        val extId = args.getString("extId") ?: return@future error
        val item = args.getSerialized<EchoMediaItem>("item")?.getOrNull() ?: return@future error
        val loaded = args.getBoolean("loaded", false)
        val shuffle = args.getBoolean("shuffle", false)
        val startTrackId = args.getString("startTrackId")
        val extension = extensions.music.getExtension(extId) ?: return@future error
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
                    else runCatching {
                        val (list, continuation) = extension.get { tracks.loadPage(null) }.getOrThrow()
                        if (continuation != null) scope.launch {
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
                            // subList-trimmed to the tapped track, and to mid-load advances).
                            player.with { addMediaItems(all) }
                        }
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
                    }
                    if (playbackState == Player.STATE_IDLE) prepare()
                    play()
                }
            }
        }
        SessionResult(RESULT_SUCCESS)
    }

    private suspend fun <T> Player.with(block: suspend Player.() -> T): T =
        withContext(Dispatchers.Main) { block() }

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
        val extId = args.getString("extId") ?: return@future error
        val item = args.getSerialized<EchoMediaItem>("item")?.getOrNull() ?: return@future error
        val startTrackId = args.getString("startTrackId") ?: return@future error
        val upcoming = freshContextUpcoming(extId, item, startTrackId)
        if (upcoming.isEmpty()) return@future error
        withContext(Dispatchers.Main) {
            player.setMediaItems(upcoming, 0, 0)
            player.prepare()
            player.playWhenReady = true
        }
        SessionResult(RESULT_SUCCESS)
    }

    private fun addToQueue(player: Player, args: Bundle) = scope.future {
        val error = SessionResult(SessionError.ERROR_UNKNOWN)
        val extId = args.getString("extId") ?: return@future error
        val item = args.getSerialized<EchoMediaItem>("item")?.getOrNull() ?: return@future error
        val loaded = args.getBoolean("loaded", false)
        val extension = extensions.music.getExtension(extId) ?: return@future error
        val tracks = listTracks(extension, item, loaded).getOrElse {
            if (it is CancellationException) throw it
            throwableFlow.emit(it)
            return@future error
        }.load().getOrElse {
            if (it is CancellationException) throw it
            throwableFlow.emit(it)
            return@future error
        }
        if (tracks.isEmpty()) return@future error
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
            addMediaItems(mediaItems)
            prepare()
        }
        SessionResult(RESULT_SUCCESS)
    }

    private var next = 0
    private var nextJob: Job? = null
    private fun addToNext(player: Player, args: Bundle) = scope.future {
        val error = SessionResult(SessionError.ERROR_UNKNOWN)
        val extId = args.getString("extId") ?: return@future error
        val item = args.getSerialized<EchoMediaItem>("item")?.getOrNull() ?: return@future error
        val loaded = args.getBoolean("loaded", false)
        val extension = extensions.music.getExtension(extId) ?: return@future error
        nextJob?.cancel()
        val tracks = listTracks(extension, item, loaded).getOrElse {
            if (it is CancellationException) throw it
            throwableFlow.emit(it)
            return@future error
        }.load().getOrElse {
            if (it is CancellationException) throw it
            throwableFlow.emit(it)
            return@future error
        }
        if (tracks.isEmpty()) return@future error
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
        player.with {
            if (mediaItemCount == 0) playWhenReady = true
            // Current index so "play next" inserts right after the CURRENT track.
            val fullIndex = currentMediaItemIndex
            addMediaItems(fullIndex + 1 + next, mediaItems)
            prepare()
        }
        next += mediaItems.size
        nextJob = scope.launch {
            delay(5000)
            next = 0
        }
        SessionResult(RESULT_SUCCESS)
    }

    override fun onSetRating(
        session: MediaSession, controller: MediaSession.ControllerInfo, rating: Rating,
    ): ListenableFuture<SessionResult> {
        return if (rating !is ThumbRating) super.onSetRating(session, controller, rating)
        else scope.future {
            val item = session.player.with { currentMediaItem }
                ?: return@future SessionResult(SessionError.ERROR_UNKNOWN)
            val track = item.track
            runCatching {
                val extension = extensions.music.getExtensionOrThrow(item.extensionId)
                // Any? (not Unit): the result is discarded below; an extension whose likeItem drifted to
                // return a value would otherwise crash with "String cannot be cast to Unit".
                extension.getAs<LikeClient, Any?> {
                    likeItem(track, rating.isThumbsUp)
                }
            }.getOrElse {
                if (it is CancellationException) throw it
                throwableFlow.emit(PlayerException(item, it))
                return@future SessionResult(SessionError.ERROR_UNKNOWN)
            }
            val liked = rating.isThumbsUp
            val newItem = item.run {
                buildUpon().setMediaMetadata(
                    mediaMetadata.buildUpon().setUserRating(ThumbRating(liked)).build()
                )
            }.build()
            session.player.with {
                // Current index so the like replaces the CURRENT track. replaceMediaItem keys the
                // original-list update off this index too.
                val fullIndex = currentMediaItemIndex
                replaceMediaItem(fullIndex, newItem)
            }
            SessionResult(RESULT_SUCCESS, Bundle().apply { putBoolean("liked", liked) })
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
                if (state.activeLoadCount.get() > 0) {
                    Log.d("GladixPlayback", "onPlaybackResumption: skipping, activeLoadCount=${state.activeLoadCount.get()}")
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
                    if (data.pos > 0)
                        state.pendingRestoreSeek =
                            data.items.getOrNull(data.index)?.mediaId?.let { it to data.pos }
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
            val isEmpty = context.recoverTracks().isNullOrEmpty()
            if (isEmpty) Toast.makeText(
                context,
                context.getString(R.string.no_last_played_track_found),
                Toast.LENGTH_SHORT
            ).show()
            return !isEmpty
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