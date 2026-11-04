package dev.brahmkshatriya.echo.playback

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.NetworkConnection
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.di.App
import dev.brahmkshatriya.echo.playback.MediaItemUtils.track
import dev.brahmkshatriya.echo.playback.PlayerService.Companion.STREAM_QUALITY
import dev.brahmkshatriya.echo.playback.PlayerService.Companion.UNMETERED_STREAM_QUALITY

/**
 * ⚠⚠ TEMPORARY PROBE (2026-11-02, user-approved) - REMOVE AFTER ONE CAPTURE. Time from a play tap to the first
 * song's real playout, in stages, after the fast-start change (page rows reused, dead-song check off the list load).
 * ONE line per armed start, tag GladixStart, on EVERY outcome (end=played / error / replaced / timeout), so silence
 * means only "nothing armed it". Times are ms since the tap, wall clock (playoutStartSystemTimeMs is wall clock):
 *   queue   first media-item transition after the tap (the queue applied); n = its window count
 *   track   StreamableLoader.loadTrack start..end; cache=hit|miss (Cached.loadMedia's preferCache arm),
 *           token=refetched|kept (the loaded TRACK_TOKEN differs from the stub's - compared in memory, only the
 *           yes/no is logged)
 *   stream  StreamableLoader.loadServer start..end; sub=1 when the source is Deezer's substitute ("Alt version")
 *   ready   first STATE_READY of the song that played - stream end to ready is CDN first byte + initial buffer
 *   audio   AnalyticsListener.onAudioPositionAdvancing's playoutStartSystemTimeMs, the method of the Oct 17-20
 *           crossfade sidecar probe (play to playout was ~170-190 ms there)
 *   skips   first songs passed over before audio; err = the last PlaybackException code seen
 * The Deezer extension prints a second line per stream lookup ("GladixStart ext id=... rungs=..."), under logcat
 * tag System.out because it is a JVM module with no android.util.Log; `adb logcat | grep GladixStart` reads both,
 * matched on id. Ids: the Deezer track id only. No tokens, ARL or URLs. No Crashlytics.
 * Zero cost when idle: every hook returns at `!armed`. Arm sites: FeedClickListener (onPlayClicked, onMediaClicked,
 * onTracksClicked), HistoryFragment, MediaHeaderAdapter's Play, AndroidAutoCallback.onSetMediaItems, and (added
 * 2026-11-03) MediaMoreBottomSheet's Play and Shuffle. Still unarmed: a shared-link start (FragmentUtils), a Radio
 * card (FeedClickListener.onMediaClicked) and the player's own controls; none of them prints a line.
 * q is the APP-level quality setting (an extension-level override is not reflected); net is App.networkFlow's
 * metered flag, logged as wifi (Unmetered) / cell (Metered) / none.
 */
object StartProbe {
    private const val TAG = "GladixStart"
    private const val TIMEOUT_MS = 60_000L

    private class Stage {
        var trackStart = -1L
        var trackEnd = -1L
        var cache = "miss"
        var token = "-"
        var streamStart = -1L
        var streamEnd = -1L
        var stream = "-"
        var ready = -1L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable { synchronized(this) { emit(null, "timeout") } }
    private var app: App? = null
    private var armed = false
    private var tap = 0L
    private var src = ""
    private var rows = ""
    private var shuffle = false
    private var queue = -1L
    private var n = -1
    private var currentId: String? = null
    private var skips = 0
    private var err = "-"
    // Per track id while armed: the first song's lookup can start before the queue transition is delivered.
    private val stages = HashMap<String, Stage>()

    private fun now() = System.currentTimeMillis()
    private fun rel(t: Long) = if (t < 0) "-" else "+${t - tap}"

    @Synchronized
    fun arm(src: String, rows: String, shuffle: Boolean = false) {
        if (armed) emit(null, "replaced")
        this.src = src
        this.rows = rows
        this.shuffle = shuffle
        tap = now()
        queue = -1L
        n = -1
        currentId = null
        skips = 0
        err = "-"
        stages.clear()
        armed = true
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, TIMEOUT_MS)
    }

    // src label from a queue's context, for the arm sites.
    fun srcOf(context: EchoMediaItem?, fallback: String) = when (context) {
        is Playlist -> "playlist"
        is Album -> "album"
        else -> fallback
    }

    @Synchronized
    fun rows(value: String) {
        if (armed) rows = value
    }

    private fun stage(id: String) = stages.getOrPut(id) { Stage() }

    @Synchronized
    fun trackStart(id: String) {
        if (armed) stage(id).let { if (it.trackStart < 0) it.trackStart = now() }
    }

    @Synchronized
    fun cacheHit(id: String) {
        if (armed) stage(id).cache = "hit"
    }

    @Synchronized
    fun trackEnd(id: String, tokenRefetched: Boolean) {
        if (!armed) return
        stage(id).let {
            if (it.trackEnd >= 0) return
            it.trackEnd = now()
            it.token = if (it.cache == "hit") "n/a" else if (tokenRefetched) "refetched" else "kept"
        }
    }

    @Synchronized
    fun streamStart(id: String) {
        if (armed) stage(id).let { if (it.streamStart < 0) it.streamStart = now() }
    }

    @Synchronized
    fun streamEnd(id: String, ok: Boolean, substitute: Boolean) {
        if (!armed) return
        stage(id).let {
            if (it.streamEnd >= 0) return
            it.streamEnd = now()
            it.stream = "${if (ok) "ok" else "err"},sub=${if (substitute) 1 else 0}"
        }
    }

    private fun emit(id: String?, end: String, audio: Long = -1L) {
        if (!armed) return
        armed = false
        handler.removeCallbacks(timeout)
        val shown = id ?: currentId
        val s = shown?.let { stages[it] } ?: Stage()
        val app = app
        val settings = app?.settings
        val q = settings?.let {
            // Mirrors PlayerService.select's app-level defaults (restored 2026-11-03): Wi-Fi "medium", Mobile "lowest".
            val unmetered = it.getString(UNMETERED_STREAM_QUALITY, "medium")
            if (app?.isUnmetered == true && unmetered != "off") unmetered else it.getString(STREAM_QUALITY, "lowest")
        } ?: "-"
        val net = when (app?.networkFlow?.value) {
            NetworkConnection.Unmetered -> "wifi"
            NetworkConnection.Metered -> "cell"
            NetworkConnection.NotConnected -> "none"
            null -> "-"
        }
        Log.d(
            TAG,
            "src=$src rows=$rows shuffle=${if (shuffle) 1 else 0} n=$n q=$q net=$net id=${shown ?: "-"} " +
                "queue=${rel(queue)} track=${rel(s.trackStart)}..${rel(s.trackEnd)}(cache=${s.cache},token=${s.token}) " +
                "stream=${rel(s.streamStart)}..${rel(s.streamEnd)}(${s.stream}) ready=${rel(s.ready)} " +
                "audio=${rel(audio)} skips=$skips err=$err end=$end"
        )
        stages.clear()
    }

    private val window = Timeline.Window()
    private fun AnalyticsListener.EventTime.trackId(): String? = runCatching {
        if (timeline.isEmpty) null
        else timeline.getWindow(windowIndex, window).mediaItem.track.id
    }.getOrNull()

    // Added to every ExoPlayer PlayerService.createExoplayer builds.
    @OptIn(UnstableApi::class)
    fun listener(app: App): AnalyticsListener {
        this.app = app
        return object : AnalyticsListener {
            override fun onMediaItemTransition(
                eventTime: AnalyticsListener.EventTime, mediaItem: MediaItem?, reason: Int,
            ) = synchronized(this@StartProbe) {
                if (!armed) return@synchronized
                val id = mediaItem?.track?.id
                if (queue < 0) {
                    queue = now()
                    n = eventTime.timeline.windowCount
                } else if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) skips++
                currentId = id
            }

            override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) =
                synchronized(this@StartProbe) {
                    if (!armed || state != Player.STATE_READY) return@synchronized
                    val id = eventTime.trackId() ?: return@synchronized
                    stage(id).let { if (it.ready < 0) it.ready = now() }
                }

            override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: PlaybackException) =
                synchronized(this@StartProbe) {
                    if (armed) err = error.errorCodeName
                }

            override fun onAudioPositionAdvancing(
                eventTime: AnalyticsListener.EventTime, playoutStartSystemTimeMs: Long,
            ) = synchronized(this@StartProbe) {
                if (armed) emit(eventTime.trackId(), "played", playoutStartSystemTimeMs)
            }
        }
    }
}
