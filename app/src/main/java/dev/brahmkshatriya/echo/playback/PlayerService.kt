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
import android.os.Build
import android.os.Bundle
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
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED
import androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED
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
import dev.brahmkshatriya.echo.common.models.ExtensionType
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.di.App
import dev.brahmkshatriya.echo.download.Downloader
import dev.brahmkshatriya.echo.extensions.ExtensionLoader
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.extensionPrefId
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.prefs
import dev.brahmkshatriya.echo.history.HistoryRepository
import dev.brahmkshatriya.echo.playback.MediaItemUtils.extensionId
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
import dev.brahmkshatriya.echo.utils.ContextUtils.listenFuture
import dev.brahmkshatriya.echo.utils.HealthMonitor
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
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
        if (wasConnected && !isConnected) {
            mediaSession?.player?.let { if (it.playWhenReady) it.pause() }
        }
    }

    private val app by inject<App>()
    private val healthMonitor by inject<HealthMonitor>()
    private val state by inject<PlayerState>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("PlayerService"))

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
            crossfadeEnabled = app.settings.getBoolean(CROSSFADE_ENABLED, false)
            crossfadeDurationMs = app.settings.getInt(CROSSFADE_DURATION, 5) * 1000
            normalizationEnabled = app.settings.getBoolean(LOUDNESS_NORMALIZATION, false)
        }
    }

    @OptIn(UnstableApi::class)
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        when (key) {
            SKIP_SILENCE -> exoPlayer.skipSilenceEnabled = prefs.getBoolean(key, true)
            MORE_BRAIN_CAPACITY -> exoPlayer.trackSelectionParameters =
                exoPlayer.trackSelectionParameters
                    .buildUpon()
                    .setAudioOffloadPreferences(offloadPreferences(prefs.getBoolean(key, false)))
                    .build()
            LOUDNESS_NORMALIZATION -> {
                audioEffectsProcessor.normalizationEnabled = prefs.getBoolean(key, false)
                effects.updateNormalizationSettings()
            }
            CROSSFADE_ENABLED -> {
                audioEffectsProcessor.crossfadeEnabled = prefs.getBoolean(key, false)
                effects.updateCrossfadeSettings()
            }
            CROSSFADE_DURATION -> {
                audioEffectsProcessor.crossfadeDurationMs = prefs.getInt(key, 5) * 1000
                effects.updateCrossfadeSettings()
            }
        }
    }
    private val effects by lazy { EffectsListener(exoPlayer, this, state.session, audioEffectsProcessor) }

    private val historyRepository by inject<HistoryRepository>()
    private val downloader by inject<Downloader>()
    private val downloadFlow by lazy { downloader.flow }

    @Volatile private var foregroundStartSuppressed = false

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        startForegroundCompat()
        setListener(MediaSessionServiceListener(this, getPendingIntent(this)))

        val player = ShufflePlayer(exoPlayer)
        scope.launch(Dispatchers.Main) {
            mediaChangeFlow.collect { (o, n) -> player.onMediaItemChanged(o, n) }
        }

        val callback = PlayerCallback(
            app, scope, app.throwFlow, extensions, state, downloadFlow, historyRepository
        )

        val session = MediaLibrarySession.Builder(this, player, callback)
            .setBitmapLoader(PlayerBitmapLoader(this, scope))
            .setSessionActivity(getPendingIntent(this))
            .build()

        player.addListener(
            PlayerEventListener(this, scope, session, state.current, extensions, app.throwFlow,
                isAndroidAutoConnected = { isAndroidAutoConnected },
                requestAudioFocus = { audioFocusListener.requestFocus() },
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

        scope.launch {
            val (items, index, pos) = recoverPlaylist(app, downloadFlow.value, healthMonitor)
            Log.d("GladixPlayback", "onCreate restore: items=${items.size} userQueueSet=${callback.userQueueSet.get()}")
            if (items.isEmpty()) return@launch
            if (!callback.userQueueSet.compareAndSet(false, true)) {
                Log.d("GladixPlayback", "onCreate restore: skipping, userQueueSet already claimed")
                return@launch
            }
            withContext(Dispatchers.Main) {
                player.shuffleModeEnabled = recoverShuffle() ?: false
                player.repeatMode = recoverRepeat() ?: Player.REPEAT_MODE_OFF
                player.setMediaItems(items.toMutableList(), index, pos)
                // No prepare() here — ShufflePlayer.getPlaybackState() fakes STATE_READY when
                // STATE_IDLE + items queued + !playWhenReady, giving AA STATE_PAUSED(2) for the
                // thumbnail. prepare() is deferred to ShufflePlayer.play()/setPlayWhenReady()
                // when the user actually initiates playback, eliminating the STATE_ENDED at ~88ms.
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
        unregisterReceiver(clearQueueReceiver)
        mediaSession?.run {
            audioFocusListener.release()
            release()                  // mediaSession first — Media3 requirement
            player.release()           // player second — main thread, synchronous
            mediaSession = null
        }
        cache.release()
        scope.cancel()
        super.onDestroy()
    }

    private val cache by inject<SimpleCache>()

    private val mediaChangeFlow = MutableSharedFlow<Pair<MediaItem, MediaItem>>()

    @OptIn(UnstableApi::class)
    private fun offloadPreferences(moreBrainCapacity: Boolean) =
        TrackSelectionParameters.AudioOffloadPreferences.Builder()
            .setAudioOffloadMode(
                if (moreBrainCapacity) AUDIO_OFFLOAD_MODE_DISABLED else AUDIO_OFFLOAD_MODE_ENABLED
            ).setIsGaplessSupportRequired(true)
            .setIsSpeedChangeSupportRequired(true)
            .build()

    @OptIn(UnstableApi::class)
    private fun createExoplayer(
        audioEffectsProcessor: AudioEffectsProcessor,
        handleAudioBecomingNoisy: Boolean = true
    ) = run {
        val audioOffloadPreferences =
            offloadPreferences(app.settings.getBoolean(MORE_BRAIN_CAPACITY, false))

        val factory = StreamableMediaSource.Factory(
            app, scope, state, extensions, cache, downloadFlow, mediaChangeFlow, healthMonitor
        )

        ExoPlayer.Builder(this, factory)
            .setRenderersFactory(RenderersFactory(this, audioEffectsProcessor))
            .setHandleAudioBecomingNoisy(handleAudioBecomingNoisy)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setAudioAttributes(musicAudioAttributes, false)
            .setReleaseTimeoutMs(150)
            .build()
            .also {
                it.trackSelectionParameters = it.trackSelectionParameters
                    .buildUpon()
                    .setAudioOffloadPreferences(audioOffloadPreferences)
                    .build()
                it.preloadConfiguration = ExoPlayer.PreloadConfiguration(C.TIME_UNSET)
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
        const val MORE_BRAIN_CAPACITY = "offload"
        const val CLOSE_PLAYER = "close_player"
        private const val ACTION_CLEAR_QUEUE = "dev.rschwertley.gladix.auto.CLEAR_QUEUE"
        const val SKIP_SILENCE = "skip_silence"
        const val LOUDNESS_NORMALIZATION = "loudness_normalization"
        const val CROSSFADE_ENABLED = "crossfade_enabled"
        const val CROSSFADE_DURATION = "crossfade_duration"
        const val SKIP_FADE_ON_ALBUMS = "skip_fade_on_albums"

        const val CACHE_SIZE = "cache_size"

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

        fun getController(
            context: Application,
            block: (MediaController) -> Unit,
        ): () -> Unit {
            val sessionToken =
                SessionToken(context, ComponentName(context, PlayerService::class.java))
            val playerFuture = MediaController.Builder(context, sessionToken).buildAsync()
            context.listenFuture(playerFuture) { result ->
                val controller = result.getOrElse {
                    return@listenFuture it.printStackTrace()
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