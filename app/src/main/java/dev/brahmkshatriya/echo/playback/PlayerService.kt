package dev.brahmkshatriya.echo.playback

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.car.app.connection.CarConnection
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import androidx.core.content.getSystemService
import androidx.lifecycle.Observer
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaController
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionToken
import com.google.common.collect.ImmutableList
import dev.brahmkshatriya.echo.MainActivity.Companion.getMainActivity
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.ui.common.ErrorCategory
import dev.brahmkshatriya.echo.ui.common.classify
import dev.brahmkshatriya.echo.common.models.ExtensionType
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.di.App
import dev.brahmkshatriya.echo.download.Downloader
import dev.brahmkshatriya.echo.extensions.ExtensionLoader
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.extensionPrefId
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.prefs
import dev.brahmkshatriya.echo.history.HistoryRepository
import dev.brahmkshatriya.echo.playback.MediaItemUtils.extensionId
import dev.brahmkshatriya.echo.playback.ResumptionUtils
import dev.brahmkshatriya.echo.playback.ResumptionUtils.recoverPlaylist
import dev.brahmkshatriya.echo.playback.ResumptionUtils.recoverRepeat
import dev.brahmkshatriya.echo.playback.ResumptionUtils.recoverShuffle
import dev.brahmkshatriya.echo.playback.listener.AudioFocusListener
import dev.brahmkshatriya.echo.playback.listener.EffectsListener
import dev.brahmkshatriya.echo.playback.listener.MediaSessionServiceListener
import dev.brahmkshatriya.echo.playback.listener.PlayerEventListener
import dev.brahmkshatriya.echo.playback.listener.PlayerRadio
import dev.brahmkshatriya.echo.playback.listener.TrackingListener
import dev.brahmkshatriya.echo.playback.renderer.AudioEffectsProcessor
import dev.brahmkshatriya.echo.playback.renderer.PlayerBitmapLoader
import dev.brahmkshatriya.echo.playback.renderer.RenderersFactory
import dev.brahmkshatriya.echo.playback.source.StreamableMediaSource
import dev.brahmkshatriya.echo.ui.player.PlayerViewModel.Companion.KEEP_QUEUE
import kotlinx.coroutines.async
import dev.brahmkshatriya.echo.utils.ContextUtils.listenFuture
import dev.brahmkshatriya.echo.utils.CrashKeys
import dev.brahmkshatriya.echo.utils.HealthMonitor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import java.io.File

@OptIn(UnstableApi::class)
class PlayerService : MediaLibraryService() {

    private val musicAudioAttributes = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .build()

    private val extensionLoader by inject<ExtensionLoader>()
    private val extensions by lazy { extensionLoader }
    private val exoPlayer by lazy { createExoplayer(this.audioEffectsProcessor) }

    private var mediaSession: MediaLibrarySession? = null
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = mediaSession

    // Media3's onStartCommand() always returns START_STICKY, which causes the OS to restart the
    // service after memory pressure kills it — even with no user intent to play. This produces a
    // blank "Gladix" notification on cold restart with no track loaded. START_NOT_STICKY means
    // the service only restarts when something explicitly starts it (ButtonReceiver on BT PLAY,
    // or the app binding via PlayerViewModel). super.onStartCommand() must still be called: it
    // dispatches the media button intent to MediaSessionImpl.handleMediaButtonEvent() via Handler,
    // which is load-bearing for BT AVRCP PLAY triggering onPlaybackResumption().
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_NOT_STICKY
    }

    private lateinit var audioFocusListener: AudioFocusListener
    private lateinit var carConnection: CarConnection
    private var isAndroidAutoConnected = false
    private val carConnectionObserver = Observer<Int> { connectionType ->
        val isConnected = connectionType == CarConnection.CONNECTION_TYPE_PROJECTION
            || connectionType == CarConnection.CONNECTION_TYPE_NATIVE
        val wasConnected = isAndroidAutoConnected
        isAndroidAutoConnected = isConnected
        CrashKeys.onAndroidAutoState(isConnected)   // per AA route change (not hot)
        // AA is the authoritative connect/disconnect signal for the phantom-PLAY route-state, because AA
        // projection does NOT reliably present as an audio-output device to AudioDeviceCallback — so an
        // AA disconnect may never fire onAudioDevicesRemoved. Recompute on every AA transition (connect
        // clears the flag so a head-unit resume-on-connect passes through; disconnect sets it when no
        // other external route remains). recompute reads isAndroidAutoConnected, updated just above.
        recomputeRouteState()
        if (wasConnected && !isConnected) {
            mediaSession?.player?.let { if (it.playWhenReady) it.pause() }
        }
    }

    // Phantom-PLAY route tracking (see PlayerState.isPostDisconnect). AudioDeviceCallback covers the
    // BT-A2DP / wired / USB-audio disconnect; the CarConnection observer covers AA/Automotive; the
    // onCreate seed covers cold-open after the service was killed during a long disconnect.
    private val audioManager by lazy { getSystemService<AudioManager>()!! }
    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = recomputeRouteState()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = recomputeRouteState()
    }

    // True if any current OUTPUT device is not a built-in speaker/earpiece — i.e. a real external audio
    // route (BT/wired/USB/dock/etc.) is present. getDevices(GET_DEVICES_OUTPUTS) is never empty (the
    // built-in speaker is always listed), so we test for a NON-built-in type rather than an empty list.
    private fun hasExternalAudioOutput(): Boolean =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { device ->
            when (device.type) {
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> false
                else -> !(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                    && device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE)
            }
        }

    // We are "post-disconnect" only when there is neither an external audio route NOR an AA connection.
    // Composing both inputs here (rather than writing the flag separately per signal) prevents the
    // AudioDeviceCallback from falsely flagging post-disconnect while AA is connected but presenting no
    // audio-output device. Runs on the application looper (all three callers do).
    private fun recomputeRouteState() {
        state.isPostDisconnect = !(isAndroidAutoConnected || hasExternalAudioOutput())
    }

    private val app by inject<App>()
    private val healthMonitor by inject<HealthMonitor>()
    private val state by inject<PlayerState>()
    private val fullQueueFlow by inject<MutableStateFlow<List<MediaItem>>>()
    // Safety net for UNCAUGHT exceptions on this scope — the same pattern App.scope and
    // ExtensionLoader.scope already carry, missing here until now. This is the reason an
    // off-application-thread session.player read was FATAL rather than a reported non-fatal: an uncaught
    // throw in a scope.launch child reaches the default uncaught handler and crashes the process, because
    // SupervisorJob only stops siblings being cancelled — it does not absorb the exception.
    // Routes to app.throwFlow so it degrades to the same non-fatal path as every other reported error
    // (printStackTrace + Crashlytics recordException, plus the snackbar collector).
    // CancellationException is never delivered to a CoroutineExceptionHandler (normal cancellation) — the
    // guard is defensive so it can never be reported. runCatching so a failure to record cannot re-crash
    // or loop; SupervisorJob keeps the scope alive after a child fails, so the launch is safe.
    // The `: CoroutineExceptionHandler` annotation is LOAD-BEARING, not style. Without it the property's
    // type must be inferred from the initializer, whose lambda body references `scope`, whose type is
    // inferred from an expression containing this handler — a cycle the compiler reports as "Type checking
    // has run into a recursive problem". Declaring the type lets it resolve `scope` without analysing this
    // body first. App.exceptionHandler and ExtensionLoader.exceptionHandler carry it for the same reason.
    private val exceptionHandler: CoroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        if (throwable is CancellationException) return@CoroutineExceptionHandler
        runCatching { scope.launch { app.throwFlow.emit(throwable) } }
    }
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineName("PlayerService") + exceptionHandler
    )

    private val audioEffectsProcessor by lazy {
        AudioEffectsProcessor().apply {
            // One-time migration: force normalization off for all existing users.
            // Safe to re-enable by clearing normalization_force_disabled_v1 from prefs.
            if (!app.settings.getBoolean("normalization_force_disabled_v1", false)) {
                app.settings.edit {
                    putBoolean(LOUDNESS_NORMALIZATION, false)
                    putBoolean("normalization_force_disabled_v1", true)
                }
            }
            crossfadeEnabled = app.settings.getBoolean(CROSSFADE_ENABLED, true)
            // Clamp on READ. The sliders only clamp what they DISPLAY — neither persists the
            // corrected value (the programmatic `value =` assignment happens before the change
            // listener is attached), so anyone who stored 6-12 while that was the allowed range
            // still has it, and without this they get a 12-second fade while both UIs show 5.
            crossfadeDurationMs = app.settings.getInt(CROSSFADE_DURATION, 4)
                .coerceIn(CROSSFADE_DURATION_MIN, CROSSFADE_DURATION_MAX) * 1000
            normalizationEnabled = app.settings.getBoolean(LOUDNESS_NORMALIZATION, false)
        }
    }

    @OptIn(UnstableApi::class)
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        when (key) {
            SKIP_SILENCE -> exoPlayer.skipSilenceEnabled = prefs.getBoolean(key, true)
            LOUDNESS_NORMALIZATION -> {
                audioEffectsProcessor.normalizationEnabled = prefs.getBoolean(key, false)
                effects.updateNormalizationSettings()
            }
            CROSSFADE_ENABLED -> {
                audioEffectsProcessor.crossfadeEnabled = prefs.getBoolean(key, false)
                effects.updateCrossfadeSettings()
            }
            CROSSFADE_DURATION -> {
                audioEffectsProcessor.crossfadeDurationMs = prefs.getInt(key, 2)
                    .coerceIn(CROSSFADE_DURATION_MIN, CROSSFADE_DURATION_MAX) * 1000
                effects.updateCrossfadeSettings()
            }
        }
    }
    private val effects by lazy { EffectsListener(exoPlayer, this, state.session, audioEffectsProcessor, scope) }

    private val historyRepository by inject<HistoryRepository>()
    private val downloader by inject<Downloader>()
    private val downloadFlow by lazy { downloader.flow }

    @Volatile private var foregroundStartSuppressed = false

    // Lever B: turn the raw player error into a friendly, categorized PlaybackException for the media
    // session (Android Auto). Category comes from the shared classify() chain-walk so the AA message
    // and the phone snackbar (ExceptionUtils) never disagree; the message/code are AA-tailored with a
    // "check your phone" recovery cue. Never returns null (the null case is handled upstream in
    // ShufflePlayer.getPlayerError before this is called).
    @OptIn(UnstableApi::class)
    private fun mapAaError(raw: PlaybackException): PlaybackException {
        val (code, message) = when (classify(raw)) {
            ErrorCategory.Network -> PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED to
                getString(R.string.playback_error_no_connection)
            ErrorCategory.LoginOrAuth -> PlaybackException.ERROR_CODE_AUTHENTICATION_EXPIRED to
                getString(R.string.playback_error_login_required)
            ErrorCategory.Generic -> PlaybackException.ERROR_CODE_UNSPECIFIED to
                getString(R.string.playback_error_generic)
        }
        return PlaybackException(message, raw, code)
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        // age_s_svc + service_create_count + heap sample. service_create_count > 1 means this process has
        // destroyed and recreated the service — the kill/rebind loop that makes heap_used_mb_svc a LATE sample.
        CrashKeys.onServiceCreate()
        startForegroundCompat()
        setListener(MediaSessionServiceListener(this, getPendingIntent(this)))

        val player = ShufflePlayer(exoPlayer, ::mapAaError)
        scope.launch(Dispatchers.Main) {
            mediaChangeFlow.collect { (o, n) -> player.onMediaItemChanged(o, n) }
        }

        val callback = PlayerCallback(
            app, scope, app.throwFlow, extensions, state, downloadFlow, historyRepository
        )

        val session = MediaLibrarySession.Builder(this, player, callback)
            .setBitmapLoader(PlayerBitmapLoader(this, scope))
            .setSessionActivity(getPendingIntent(this))
            // Disable Media3's ~1/sec periodic PlaybackState position pushes: recent Android Auto
            // versions reset the browse/queue scroll on every playback-state change, so the periodic
            // updates make the AA queue jump to the top every few seconds during playback (androidx/
            // media #2192, b/400923507). Discrete PlaybackState updates (play/pause/seek/track change)
            // still fire, and all standard controllers (AA, notification, lockscreen) extrapolate
            // position between them, so the seek bar is unaffected. Our own UI reads player position
            // directly on a 500ms ticker (PlayerUiListener), so it's independent of this flag.
            .setPeriodicPositionUpdateEnabled(false)
            // Behaviourally a no-op — NON_FATAL is already Media3's default (MediaLibraryService:465).
            // Stated explicitly because the default is load-bearing and its loss would be SILENT: under
            // FATAL, a library error replicated from ANY onGetChildren node takes the isFatal branch in
            // MediaSessionLegacyStub.createPlaybackStateCompat (:1843-1861) and publishes STATE_ERROR with
            // setActions(0) — over live playback of a DIFFERENT extension, since the platform session is
            // shared. That is the stuck-error-over-playback class we spent Aug 9-11 diagnosing. NON_FATAL
            // instead attaches only setErrorMessage + extras and leaves state/position/actions intact.
            // No compile error and no test would catch a change here, so do not "simplify" this line away.
            .setLibraryErrorReplicationMode(
                MediaLibrarySession.LIBRARY_ERROR_REPLICATION_MODE_NON_FATAL
            )
            .build()

        player.addListener(
            PlayerEventListener(this, scope, session, state.current, extensions, app.throwFlow,
                fullQueueFlow = fullQueueFlow,
                isAndroidAutoConnected = { isAndroidAutoConnected },
                requestAudioFocus = { audioFocusListener.requestFocus() },
                activeLoadCount = { state.activeLoadCount.get() },
                // Epoch ms of the current load episode's start, 0 when idle - stuck_detail's loadAge.
                loadEpisodeStartMs = { state.loadEpisodeStartMs.get() },
                // Clears the resumption marker once the queue lands (timeline non-empty) — the success
                // clear for onPlaybackResumption; on Main, since Player.Listener fires on the app looper.
                onQueueApplied = { state.resumptionApplying = false },
                // Returns-and-clears the cold-start re-seek latch (Main; Player.Listener fires on the app looper).
                consumeRestoreSeek = { state.pendingRestoreSeek.also { state.pendingRestoreSeek = null } },
                // Non-consuming peek at the same latch — gates the saveCurrentPos 0-write without clearing it
                // (Main; Player.Listener fires on the app looper).
                isRestoreSeekArmed = { state.pendingRestoreSeek != null },
                healthMonitor = healthMonitor,
            )
        )
        player.addListener(
            PlayerRadio(
                app, scope, player, app.throwFlow, state.radio, extensions.music, downloadFlow
            )
        )
        player.addListener(
            TrackingListener(player, scope, extensions, state.current, app.throwFlow, historyRepository)
        )
        player.addListener(effects)
        audioFocusListener = AudioFocusListener(this, player)
        carConnection = CarConnection(this)
        carConnection.type.observeForever(carConnectionObserver)
        // Phantom-PLAY route tracking: observe live BT/wired/USB audio-route changes, and SEED the flag
        // now from the current output set. The seed is the cold-open fix — after a long disconnect the
        // service is killed (START_NOT_STICKY) and this in-memory flag resets, so on recreation we must
        // re-derive it: no external output present => start post-disconnect, which is exactly the state
        // after a BT/car/AA disconnect. The CarConnection observer's initial emission recomputes again
        // once AA state is known.
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, Handler(Looper.getMainLooper()))
        recomputeRouteState()
        player.addListener(audioFocusListener)
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                app.crashPlayerState = playbackState
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                app.crashIsPlaying = isPlaying
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                app.crashExtensionId = mediaItem?.extensionId ?: "none"
                // Reuse the already-decoded id (no second state round-trip); once per track transition.
                CrashKeys.onPlayingExtension(app.crashExtensionId)
            }
        })
        app.settings.registerOnSharedPreferenceChangeListener(listener)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(clearQueueReceiver, IntentFilter(ACTION_CLEAR_QUEUE),
                RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(clearQueueReceiver, IntentFilter(ACTION_CLEAR_QUEUE))
        }

        val notificationProvider = DefaultMediaNotificationProvider.Builder(this)
            .setChannelName(R.string.app_name)
            .build()
        notificationProvider.setSmallIcon(R.drawable.ic_gladix_mono)
        setMediaNotificationProvider(SafeNotificationProvider(notificationProvider))
        // Suppress the notification entirely when the timeline is empty (no track loaded).
        // NEVER mode causes MediaNotificationManager.shouldShowNotification() to return false
        // and call removeNotification() instead of our provider when the player is idle.
        // startForegroundCompat()'s initial placeholder remains visible until the first real
        // track notification replaces it — this is acceptable and avoids the "Loading…" flash.
        setShowNotificationForIdlePlayer(SHOW_NOTIFICATION_FOR_IDLE_PLAYER_NEVER)

        mediaSession = session
        // Belt-and-suspenders: ensure ExoPlayer's internal AudioFocusManager has
        // handleAudioFocus=false after all Media3 session initialization completes.
        // Session init calls player.setAudioAttributes(DEFAULT, true) through ShufflePlayer
        // (caught by the override), but calling directly on exoPlayer guarantees the
        // AudioFocusManager's internal state is locked off regardless of any timing race.
        exoPlayer.setAudioAttributes(musicAudioAttributes, false)

        // Producer: read the saved queue from disk ONCE into the shared Deferred (see PlayerState). Every
        // consumer awaits THIS — no path runs its own recoverPlaylist, so the cold-start double-restore
        // race is gone at the root. with(this@PlayerService) supplies the Context receiver the recover*
        // extensions need inside the coroutine.
        state.restoreDeferred = scope.async(Dispatchers.IO) {
            // Reuse the previous service instance's built items when the queue on disk has not changed
            // (see PlayerState.restoreCache for why this is a generation cache and not "once per
            // process"). The apply below is unaffected — only the disk read and the MediaItem
            // construction are skipped, so a recreated service still gets its queue.
            val generation = ResumptionUtils.queueGeneration
            state.restoreCache?.takeIf { it.first == generation }?.let {
                Log.d("GladixPlayback", "restore read: reused cache gen=$generation")
                return@async it.second
            }
            with(this@PlayerService) {
                val (items, index, pos) = recoverPlaylist(app, downloadFlow.value, healthMonitor)
                Log.d("GladixPlayback", "restore read: items=${items.size}")
                val data = if (items.isEmpty()) null
                else RestoreData(
                    items, index, pos,
                    recoverShuffle() ?: false,
                    recoverRepeat() ?: Player.REPEAT_MODE_OFF
                )
                // Key on the generation captured BEFORE the read: a write that lands mid-read bumps it,
                // so the next instance misses this entry and re-reads rather than serving a torn build.
                state.restoreCache = generation to data
                data
            }
        }
        // App-open apply: the sole restorer for a controller-driven cold start. Main-atomic, gated on an
        // empty player + the resumption marker; KEEP_QUEUE is honored inside applyRestoreIfCold. It does
        // NOT prepare() — same lazy-STATE_READY reason as before; prepare() waits for play().
        scope.launch { callback.applyRestoreIfCold(player) }
        scheduleRestoreSnapshotRelease(player)
    }

    /**
     * Frees the cold-start restore snapshot once it can no longer be needed.
     *
     * ⚠️ WHY: PlayerState.restoreCache and restoreDeferred were set once and NEVER cleared — a grep for
     * assignments in the whole tree returned only the two writes. Both hold the SAME RestoreData, whose
     * `items` is the full list of built MediaItems, and PlayerState is a Koin singleton. Measured on the
     * 2026-09-07 OOM reports: heap_used_mb_build is 45-63 MB at restore_build_count = 2001, i.e. ~20-30 KB
     * per MediaItem, so the snapshot retained 40-60 MB for the whole session — held TWICE, beside a live
     * timeline of 5,432 items that is a separate copy of the same class of data, on a device whose growth
     * limit is 384 MB.
     *
     * ⚠️ WHY A TTL AND NOT A SIZE CAP. The cache exists for the build-1039 service-creation storm: ~1050
     * creations in 42-73 s, each of which re-read the queue and rebuilt every MediaItem (~85,000 builds in
     * a minute, 20 MB -> 255 MB, fatal). That storm is bounded in TIME and unbounded in QUEUE SIZE, and
     * the cache's BENEFIT scales with queue size exactly as its cost does — a storm over 2,001 items is
     * 25x worse than the 81-item queue it was justified against. Capping what the cache retains would
     * therefore disable it precisely where it matters most. Time is the correct knob; size is not.
     *
     * ⚠️ EXPIRY COSTS A DISK READ, NOT CORRECTNESS. A service re-created after release simply re-reads and
     * rebuilds — the pre-cache behaviour, which PlayerState.restoreCache's own note describes as "merely
     * wasteful" rather than fatal. The failure mode of releasing too early is the state the cache was
     * added to improve, never the state it was added to prevent.
     *
     * ⚠️ THE RELEASE CONDITION IS THE PART THAT COULD BREAK RESTORE, so it is a conjunction, not a timer.
     *
     * THERE ARE THREE AWAITERS OF restoreDeferred AT HEAD, not two — grep it before changing this gate:
     *   1. PlayerCallback.applyRestoreIfCold  — service create. Done once the timeline is non-empty; its
     *      own gate is `mediaItemCount != 0 || resumptionApplying` anyway, the same layer we release on.
     *   2. PlayerCallback.onPlaybackResumption — media button. Media3 invokes it ONLY when
     *      getCurrentMediaItem() == null, so a non-empty timeline means it cannot be called again.
     *   3. ⚠️ PlayerViewModel:164 — THE BINDING CONSTRAINT, and the reason the timeline alone is not a
     *      sufficient gate. It awaits inside getController { … }, i.e. when the UI's MediaController
     *      connects, which happens AFTER the service has applied the queue BY DESIGN — and again on every
     *      later Activity creation. Releasing on a non-empty timeline alone would null the Deferred out
     *      from under a UI that opens minutes later (e.g. after a Bluetooth resume).
     *
     * (An older record counts FOUR consumers. That predates this cache: PlayerViewModel is listed among
     * the files the restoreCache change itself touched, so consumer 3 was added or reworked in the same
     * commit and the older count describes a different set — the same drift as its cited PlayerService.kt
     * :293, which is :391 here. THREE is the count at HEAD. Other disk-restore paths — AndroidAutoCallback
     * x2, the widget's ControllerHelper, PlayerCallback:235 and onPlaybackResumption's isForPlayback=false
     * branch — call recoverTracks() DIRECTLY and never touch the Deferred, so this release cannot affect
     * them.)
     *
     * SO THE GATE IS `mediaItemCount != 0 && current.value != null`. A non-null `current` means either the
     * service's own listener or consumer 3's seed has already run, which is exactly the write consumer 3
     * would otherwise be making — so nothing is still owed the snapshot.
     *
     * ⚠️ BOTH TERMS ARE LOAD-BEARING. NEITHER SUBSUMES THE OTHER. DO NOT DELETE EITHER.
     * There is a recorded claim that "playerState.current is safe to observe — it only goes null when
     * mediaItemCount == 0", and reading it as "current != null implies mediaItemCount != 0" makes term 1
     * look redundant. IT IS NOT: that claim is about when current goes NULL, and the converse is false.
     * PlayerEventListener.updateCurrentFlow has a third branch —
     *     } else if (player.mediaItemCount == 0 && currentFlow.value?.isPlaceholder == true) {
     *         // Keep the placeholder until we have real items or decide to clear
     * — and consumer 3 writes exactly such a placeholder (PlayerState.Current(..., isPlaceholder = true)).
     * So `current != null` WITH `mediaItemCount == 0` is a real, deliberately-preserved state: the UI has
     * seeded from this snapshot and the service has not applied the queue yet. Releasing there would
     * destroy THE ONLY COPY of data the UI is still displaying from.
     * The two terms do NOT cover symmetric halves, and term 1's scope is the narrower one:
     *   current != null      is the ROUTINE guard — queue applied, consumer 3 not yet seeded.
     *   mediaItemCount != 0  covers the states where the queue is NEVER APPLIED, so a placeholder is all
     *                        there is. Transiently that is a millisecond race on any cold start (both
     *                        awaiters resume from the same Deferred onto Main in unspecified order), which
     *                        would never survive to a 90s deadline. DURABLY it is at least two real
     *                        configurations: the BT-reconnect-cold-start-before-restore path, and
     *                        KEEP_QUEUE disabled — applyRestoreIfCold returns on its first line when that
     *                        setting is off, while consumer 3 has no such gate and seeds anyway, leaving a
     *                        placeholder against an empty timeline for the whole session.
     * VERIFIED 2026-09-07, not inferred: isPlaceholder is written in exactly two places, both consumer 3
     * (PlayerViewModel:182 restore branch, :202 history fallback). updateCurrentFlow never sets it — it
     * constructs Current(index, item, isLoaded, isPlaying, false) positionally.
     * And there is no flicker to worry about in the other direction: updateCurrentFlow nulls `current`
     * only when currentMediaItem is null AND that placeholder branch does not apply, i.e. when the player
     * genuinely holds nothing — so a track playing at the deadline always satisfies term 2.
     *
     * ⚠️ AND YES, THIS GATE DOES OPEN ON AN AT-REST COLD RESTORE — the case this whole change exists for,
     * where the queue is restored and nothing is ever played. Recorded as a MECHANISM so it is not
     * re-derived: `current` is PlayerState.current (PlayerService passes state.current into
     * PlayerEventListener as currentFlow), written by updateCurrentFlow, which onEvents calls on
     * EVENT_TIMELINE_CHANGED / EVENT_MEDIA_ITEM_TRANSITION (among others). setMediaItems fires BOTH of
     * those on its own, and updateCurrentFlow then reads a non-null player.currentMediaItem and writes
     * Current. NO prepare() IS INVOLVED. An earlier observation of `current` appearing after a restore
     * that did setMediaItems AND prepare is consistent with this but does not make prepare necessary — it
     * was incidental to that path. applyRestoreIfCold deliberately does not prepare(), and the gate opens
     * anyway. (Belt: consumer 3 also writes `current` itself while consuming the snapshot, so even absent
     * the service listener the gate would open precisely when consumer 3 is finished with the data.)
     *
     * ⚠️ WHAT A LOOSENED GATE LOOKS LIKE, since the failure is silent and does NOT resemble a crash: the
     * QUEUE STAYS INTACT and only the UI degrades. Consumer 3 is the sole initial writer of `current`; with
     * a null snapshot it falls to the last-played history track, which is the documented path for "no
     * restorable queue". So a restorable queue looks UNRESTORABLE TO THE UI ONLY — the mini-bar seeds from
     * the wrong track, AND the scrubber loses its position, because history tracks always start from the
     * beginning and there is no equivalent of RestoreData.pos in that fallback. Two wrong things, both
     * silent, neither of which points at this release.
     *
     * ⚠️ THIS GATE READS current.value; IT NEVER WRITES IT. That distinction is deliberate. The position/
     * current area has form — an earlier session found three independent writers racing (current.value,
     * viewModel.queue, adapter.currentList) and fixed it by REMOVING a writer, so adding one here would be
     * the exact shape that went wrong. A read introduces one ordering dependency and only one: this
     * release must not run before whoever sets `current` first, which the gate enforces by construction —
     * if it is still null we return and, being a one-shot delay, never retry.
     *
     * If the player is STILL EMPTY at the deadline we keep the snapshot: that is the case where a later
     * media button genuinely still needs it, and it is also the case where the snapshot is the ONLY copy
     * rather than a duplicate, so holding it costs nothing this change was meant to save. Same for a null
     * `current`. Because the delay is one-shot, either miss keeps the snapshot for the SESSION — accepted
     * for the same reason: in both states it is not the duplicate this change exists to remove.
     */
    private fun scheduleRestoreSnapshotRelease(player: Player) {
        scope.launch {
            delay(RESTORE_SNAPSHOT_TTL_MS)
            withContext(Dispatchers.Main) {
                if (player.mediaItemCount == 0 || state.current.value == null) return@withContext
                state.restoreCache = null
                state.restoreDeferred = null
                Log.d("GladixPlayback", "restore snapshot released after ${RESTORE_SNAPSHOT_TTL_MS}ms")
            }
        }
    }

    // Called at the very top of onCreate() to satisfy Android's 5-second startForeground()
    // requirement before any heavyweight initialization (ExoPlayer, MediaLibrarySession, etc.).
    // Uses DefaultMediaNotificationProvider's channel/notification IDs so that Media3's own
    // startForeground() call (which fires once the session has an active player state) cleanly
    // replaces this placeholder notification with real media controls.
    @OptIn(UnstableApi::class)
    private fun startForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService<NotificationManager>()!!
            if (nm.getNotificationChannel(DefaultMediaNotificationProvider.DEFAULT_CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        DefaultMediaNotificationProvider.DEFAULT_CHANNEL_ID,
                        getString(R.string.app_name),
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
            }
        }
        val notification = NotificationCompat.Builder(
            this, DefaultMediaNotificationProvider.DEFAULT_CHANNEL_ID
        )
            .setSmallIcon(R.drawable.ic_gladix_mono)
            .setContentTitle(getString(R.string.app_name))
            .setContentIntent(getPendingIntent(this))
            .setSilent(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException (API 31+): service was started via
            // bindService() from a background caller (e.g. widget MediaController connection)
            // rather than startForegroundService(), so mAllowStartForeground=false and
            // startForeground() is rejected. No 5-second obligation exists for bind-started
            // services; the service runs as bound until startForegroundService() elevates it.
            // foregroundStartSuppressed is set so onUpdateNotification() can retry once Media3
            // posts a real notification and the AA binding chain has had time to allow it.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                e.javaClass.name == "android.app.ForegroundServiceStartNotAllowedException"
            ) {
                foregroundStartSuppressed = true
                return
            }
            throw e
        }
    }

    // Media3's MediaNotificationManager.startForeground() (called when album art loads
    // asynchronously and the notification is rebuilt) throws ForegroundServiceStartNotAllowedException
    // on API 31+ when the service was started via bindService() rather than startForegroundService().
    // We can't patch Media3's internal code, but onUpdateNotification() is the last public override
    // point before execution enters MediaNotificationManager, so we catch the exception here.
    //
    // If the initial startForegroundCompat() was suppressed (foregroundStartSuppressed == true),
    // we attempt to promote to foreground here before delegating to Media3, closing the vulnerable
    // window between the AA bind and the first real media notification.
    @OptIn(UnstableApi::class)
    override fun onUpdateNotification(session: MediaSession, startInForeground: Boolean) {
        // Playback resumed — swap back to the media controls notification
        if (foregroundStartSuppressed) {
            try {
                val placeholder = NotificationCompat.Builder(
                    this, DefaultMediaNotificationProvider.DEFAULT_CHANNEL_ID
                )
                    .setSmallIcon(R.drawable.ic_gladix_mono)
                    .setSilent(true)
                    .build()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID,
                        placeholder,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                    )
                } else {
                    startForeground(
                        DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID,
                        placeholder
                    )
                }
                foregroundStartSuppressed = false
            } catch (e: Exception) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                    e.javaClass.name != "android.app.ForegroundServiceStartNotAllowedException"
                ) throw e
                // Still not allowed — remain suppressed, try again on the next update
            }
        }
        try {
            super.onUpdateNotification(session, startInForeground)
        } catch (e: Exception) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                e.javaClass.name == "android.app.ForegroundServiceStartNotAllowedException"
            ) return
            throw e
        }
    }

    // Wraps DefaultMediaNotificationProvider to catch ForegroundServiceStartNotAllowedException
    // thrown from the async bitmap callback (OnBitmapLoadedFutureCallback.onSuccess). That path
    // fires after onUpdateNotification() has already returned, so the catch in onUpdateNotification()
    // cannot intercept it. Wrapping the Provider.Callback here catches it at the last public point
    // before the exception propagates to an uncaught crash.
    @OptIn(UnstableApi::class)
    private class SafeNotificationProvider(
        private val delegate: DefaultMediaNotificationProvider
    ) : MediaNotification.Provider {
        override fun createNotification(
            session: MediaSession,
            customLayout: ImmutableList<CommandButton>,
            actionFactory: MediaNotification.ActionFactory,
            callback: MediaNotification.Provider.Callback
        ): MediaNotification {
            val safeCallback = MediaNotification.Provider.Callback { notification ->
                try {
                    callback.onNotificationChanged(notification)
                } catch (e: Exception) {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                        e.javaClass.name != "android.app.ForegroundServiceStartNotAllowedException"
                    ) throw e
                    // Service was demoted while album art was loading — safe to swallow
                }
            }
            return delegate.createNotification(session, customLayout, actionFactory, safeCallback)
        }

        override fun handleCustomCommand(
            session: MediaSession, action: String, extras: Bundle
        ): Boolean = delegate.handleCustomCommand(session, action, extras)

        override fun getNotificationChannelInfo() = delegate.notificationChannelInfo
    }

    override fun onDestroy() {
        if (::carConnection.isInitialized) carConnection.type.removeObserver(carConnectionObserver)
        audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
        unregisterReceiver(clearQueueReceiver)
        mediaSession?.run {
            // Flush the debounced queue save synchronously BEFORE releasing the player and cancelling
            // the scope (which would drop a pending scheduleSaveQueue). Must run before player.release()
            // — a released player reports an empty timeline, which saveQueueBlocking no-ops on.
            ResumptionUtils.saveQueueBlocking(applicationContext, player)
            audioFocusListener.release()
            // Synchronous final effect-session CLOSE. MUST precede scope.cancel() below: the runtime CLOSE
            // (broadcastAudioSessionCloseDeferred) posts to this scope, which cancel() would drop — so the
            // teardown close is done synchronously here, while the session is still live and before the
            // scope dies. effects is always initialized (addListener in onCreate); teardown isn't a
            // contended cold-start, so a synchronous sendBroadcast is fine.
            effects.releaseBlocking()
            release()                  // mediaSession first — Media3 requirement
            player.release()           // player second — main thread, synchronous
            mediaSession = null
        }
        scope.cancel()
        super.onDestroy()
    }

    private val cache by inject<SimpleCache>()

    private val mediaChangeFlow = MutableSharedFlow<Pair<MediaItem, MediaItem>>()

    @OptIn(UnstableApi::class)
    private fun offloadPreferences() =
        TrackSelectionParameters.AudioOffloadPreferences.Builder()
            // Offload is disabled unconditionally. The Pixel 10 (Tensor G5) gapless-offload
            // HAL drops audio silently across a natural track boundary; forcing ExoPlayer to
            // decode on the CPU gives device-agnostic software gapless.
            .setAudioOffloadMode(AUDIO_OFFLOAD_MODE_DISABLED)
            .setIsGaplessSupportRequired(true)
            .setIsSpeedChangeSupportRequired(true)
            .build()

    @OptIn(UnstableApi::class)
    private fun createExoplayer(
        audioEffectsProcessor: AudioEffectsProcessor,
        handleAudioBecomingNoisy: Boolean = true
    ) = run {
        val audioOffloadPreferences = offloadPreferences()

        val factory = StreamableMediaSource.Factory(
            app, scope, state, extensions, cache, downloadFlow, mediaChangeFlow, healthMonitor
        )

        ExoPlayer.Builder(this, factory)
            .setRenderersFactory(RenderersFactory(this, audioEffectsProcessor))
            .setHandleAudioBecomingNoisy(handleAudioBecomingNoisy)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setAudioAttributes(musicAudioAttributes, false)
            // 500 ms = Media3's own default, restored deliberately. The 150 ms here came from 464d4d3f
            // (2026-06-06), the same commit that made onDestroy call mediaSession.release() then
            // player.release() — the latter is SYNCHRONOUS ON THE MAIN THREAD, and the tight budget was
            // an ANR guard. That intent is preserved: 500 ms is still two orders of magnitude below any
            // service-destroy ANR threshold, so the guard costs at most 350 ms more in the worst case.
            // 150 ms was demonstrably too tight — it fired on a Pixel 10 / Android 17 (build 1036),
            // where the budget bounds renderer/codec teardown, not media-source teardown, so it does NOT
            // scale with queue size. Do not re-tighten without a concrete ANR attributable to this line.
            .setReleaseTimeoutMs(500)
            // Media3's stuck-buffering backstop, lowered from its 600_000 default (ExoPlayer
            // .DEFAULT_STUCK_BUFFERING_DETECTION_TIMEOUT_MS). It fires a StuckPlayerException with
            // ERROR_CODE_TIMEOUT into onPlayerError, where PlayerEventListener's generic tail re-prepares
            // and plays — which also produces the state transition that re-arms our own watchdog.
            //
            // WHAT THE 600s DEFAULT COST: two field reports on 2026-09-05 (same device, same build 1078,
            // 14 minutes apart) each show TEN MINUTES of unchanged buffered position on a one-item
            // timeline. BUFFERING_WATCHDOG_MS is 5_000 with one retry, so a live watchdog resolves or
            // gives up inside ~15s — meaning in both cases OUR WATCHDOG WAS NOT RUNNING AT ALL and this
            // detector was the only thing that ever noticed. It noticed after ten minutes of silence.
            //
            // ⚠️ 60s, NOT 30s, AND THE TWO NUMBERS IT MUST CLEAR ARE REAL: PlayerEventListener's
            // RESOLVE_GRACE_MS is 25_000 and StreamableLoader wraps a resolve in withTimeout(30_000).
            // media3's detector knows nothing about either — it counts any window with no change in
            // buffered position, and a stream that has not resolved yet has none — so a 30s threshold
            // would fire at the same instant the loader times out, racing two error paths onto one item.
            // 60s clears both with margin while still leaving this an order of magnitude below the
            // default. It is a BACKSTOP, not a competitor: at 60s it can only fire when the 5s watchdog
            // has already failed to act, which is exactly the condition worth reporting.
            //
            // Also note the detector's own precondition (StuckBufferingDetector.update, media3 1.11.0):
            // it counts only while STATE_BUFFERING && playWhenReady && no playback suppression, and any
            // change of period uid or buffered position restarts its clock. A paused stall is invisible
            // to it, so this does not bound every hang — only the ones the user is waiting on.
            .setStuckBufferingDetectionTimeoutMs(60_000)
            .build()
            .also {
                it.trackSelectionParameters = it.trackSelectionParameters
                    .buildUpon()
                    .setAudioOffloadPreferences(audioOffloadPreferences)
                    .build()
                it.skipSilenceEnabled = app.settings.getBoolean(SKIP_SILENCE, true)
            }
    }


    private val clearQueueReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_CLEAR_QUEUE) return
            mediaSession?.player?.run {
                clearMediaItems()
                stop()
            }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (app.settings.getBoolean(CLOSE_PLAYER, false)) {
            mediaSession?.player?.run { stop(); clearMediaItems() }
            stopSelf()
        }
    }

    companion object {
        // 90s, chosen against the OBSERVED storm rather than a round number: the build-1039 service
        // creation storms ran 42-73 s, so this clears the worst measured case with ~25% margin while
        // holding 40-60 MB for a quarter less time than a 120 s window would. Expiring mid-storm is not a
        // correctness risk (see scheduleRestoreSnapshotRelease) — it costs one disk read and rebuild.
        private const val RESTORE_SNAPSHOT_TTL_MS = 90_000L

        const val CLOSE_PLAYER = "close_player"
        private const val ACTION_CLEAR_QUEUE = "dev.rschwertley.gladix.auto.CLEAR_QUEUE"
        const val SKIP_SILENCE = "skip_silence"
        const val LOUDNESS_NORMALIZATION = "loudness_normalization"
        const val CROSSFADE_ENABLED = "crossfade_enabled"
        const val CROSSFADE_DURATION = "crossfade_duration"

        // Single source of truth for the fade range. It was previously written out separately in the
        // settings slider, the audio-fx sheet's coerceIn, the audio-fx layout XML and the summary
        // string; when the range was retuned from 1-12 to 1-5 (530681cf, 2026-08-02) the string was
        // missed, so the label advertised a range the widgets had stopped allowing. The summary is
        // now formatted from these, so it cannot drift again. The layout XML still hardcodes 1..5 —
        // resource attributes can't reference these, so that one file remains a manual match.
        const val CROSSFADE_DURATION_MIN = 1
        const val CROSSFADE_DURATION_MAX = 5

        const val SKIP_FADE_ON_ALBUMS = "skip_fade_on_albums"

        const val CACHE_SIZE = "cache_size"

        // Function-level suppression: getCache has exactly ONE try/catch (the corrupt-cache wipe+rebuild
        // recovery below), so this scopes to precisely that one finding with no collateral. The swallow is
        // deliberate best-effort recovery and the cause is now logged (Log.w) — rethrowing would change the
        // wipe-and-retry control flow. Not try-scoped only because it's a return-position try.
        // ⚠️ MUST REMAIN A SINGLETON. Registered as singleOf(PlayerService::getCache) in DI.kt, and that
        // registration is the ONLY thing making the recovery below safe.
        // SimpleCache's constructor takes an exclusive lock on its directory. If getCache ever ran a second
        // time while an instance was live, the first SimpleCache(...) would throw ("Another SimpleCache
        // instance uses the folder"), the catch would deleteRecursively() the LIVE cache's files out from
        // under the running instance, and the retry would hand back a second cache over a directory the
        // first one still holds in-memory spans for. Those spans then point at deleted files, and the next
        // commitFile()/read fails a checkState — which is exactly the bare IllegalStateException that
        // PlayerEventListener's isDataSourceTeardownRace guard exists to absorb. It would be absorbed
        // silently, so this would not announce itself.
        // If you ever change this to a factory, scope it per-directory, or add a second caller, the
        // wipe-and-retry must move behind a check that no live instance holds the lock.
        @Suppress("SwallowedException")
        @OptIn(UnstableApi::class)
        fun getCache(
            app: Application,
            settings: SharedPreferences,
        ): SimpleCache {
            val cacheDir = File(app.cacheDir, "exo-player")
            val cacheSize = settings.getInt(CACHE_SIZE, 250)
            val evictor = LeastRecentlyUsedCacheEvictor(cacheSize * 1024 * 1024L)
            return try {
                SimpleCache(cacheDir, evictor, StandaloneDatabaseProvider(app))
            } catch (e: Exception) {
                // Corrupt/locked ExoPlayer cache → wipe and rebuild (deliberate recovery). Log the cause so
                // a recurring rebuild (disk full / corruption / lock) isn't invisible — control flow unchanged.
                Log.w("GladixPlayback", "ExoPlayer cache init failed, recreating: ${e.message}")
                cacheDir.deleteRecursively()
                SimpleCache(cacheDir, evictor, StandaloneDatabaseProvider(app))
            }
        }

        const val STREAM_QUALITY = "stream_quality"
        const val UNMETERED_STREAM_QUALITY = "unmetered_stream_quality"
        val streamQualities = arrayOf("highest", "medium", "lowest")

        fun selectServerIndex(
            app: App,
            extensionId: String,
            streamables: List<Streamable>,
            downloaded: List<String>,
        ) = if (downloaded.isNotEmpty()) streamables.size
        else if (streamables.isNotEmpty()) {
            val streamable = streamables.select(app, extensionId) { it.quality }
            streamables.indexOf(streamable)
        } else -1

        private fun <E> List<E>.select(
            app: App,
            settings: SharedPreferences,
            quality: (E) -> Int,
            default: String = streamQualities[1],
        ): E? {
            if (app.isUnmetered) {
                val unmeteredQuality = settings.getString(UNMETERED_STREAM_QUALITY, "off")
                if (unmeteredQuality != "off") return selectQuality(unmeteredQuality, quality)
                if (default == "off") return null  // extension level — stop here
                // app level — fall through to stream_quality
                return selectQuality(settings.getString(STREAM_QUALITY, default), quality)
            }
            return selectQuality(settings.getString(STREAM_QUALITY, default), quality)
        }

        private fun <E> List<E>.selectQuality(final: String?, quality: (E) -> Int): E? {
            return when (final) {
                streamQualities[0] -> maxBy { quality(it) }
                streamQualities[1] -> sortedBy { quality(it) }[size / 2]
                streamQualities[2] -> minBy { quality(it) }
                else -> null
            }
        }


        fun <T> List<T>.select(
            app: App, extensionId: String, quality: (T) -> Int,
        ): T {
            val extSettings =
                extensionPrefId(ExtensionType.MUSIC.name, extensionId).prefs(app.context)
            return select(app, extSettings, quality, "off")
                ?: select(app, app.settings, quality)
                ?: first()
        }

        // Takes App rather than Application so a FAILED connection has somewhere to go. It previously
        // did `printStackTrace()` and nothing else: a controller that never connected left no trace in
        // Crashlytics, no snackbar, and no state change — which is why a service/controller churn loop
        // was invisible until it OOM'd the process (build 1039, 2026-08-23). Routing to app.throwFlow
        // gives it the same non-fatal path as every other reported error.
        fun getController(
            app: App,
            block: (MediaController) -> Unit,
        ): () -> Unit {
            val context = app.context
            val sessionToken =
                SessionToken(context, ComponentName(context, PlayerService::class.java))
            val playerFuture = MediaController.Builder(context, sessionToken).buildAsync()
            context.listenFuture(playerFuture) { result ->
                val controller = result.getOrElse {
                    it.printStackTrace()
                    // scope.launch bridges the non-suspend callback to the suspending emit; runCatching so
                    // a failure to record can never re-crash the caller (often a BroadcastReceiver).
                    runCatching { app.scope.launch { app.throwFlow.emit(it) } }
                    return@listenFuture
                }
                block(controller)
            }
            return { MediaController.releaseFuture(playerFuture) }
        }

        fun getPendingIntent(context: Context): PendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, context.getMainActivity()).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra("fromNotification", true)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}