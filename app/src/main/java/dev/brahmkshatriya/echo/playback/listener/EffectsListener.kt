package dev.brahmkshatriya.echo.playback.listener

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.media.audiofx.AudioEffect
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import androidx.annotation.OptIn
import androidx.core.content.edit
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.PlayerMessage
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.copyTo
import dev.brahmkshatriya.echo.playback.MediaItemUtils.context
import dev.brahmkshatriya.echo.playback.MediaItemUtils.track
import dev.brahmkshatriya.echo.playback.PlayerService.Companion.SKIP_FADE_ON_ALBUMS
import dev.brahmkshatriya.echo.playback.renderer.AudioEffectsProcessor
import dev.brahmkshatriya.echo.utils.ContextUtils.getSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlin.math.pow
import kotlin.math.roundToInt

@OptIn(UnstableApi::class)
class EffectsListener(
    private val exoPlayer: ExoPlayer,
    private val context: Context,
    private val audioSessionFlow: MutableStateFlow<Int>,
    private val audioEffectsProcessor: AudioEffectsProcessor,
    // Service-lifetime scope (PlayerService.scope: SupervisorJob + IO, cancelled in onDestroy) — valid at
    // init/onCreate and never fires after release, so the deferred broadcast can't leak or run post-teardown.
    private val scope: CoroutineScope,
) : Player.Listener {

    // Serial (parallelism=1) view of IO: the two ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION announces — the init
    // one and the onAudioSessionIdChanged one — dispatch here in FIFO submission order, so the init announce can
    // never land AFTER a later session-change announce and strand an external equalizer on a stale session.
    // Declared before init so it's initialized before the init block's broadcast uses it.
    private val broadcastDispatcher = Dispatchers.IO.limitedParallelism(1)

    init {
        audioSessionFlow.value = exoPlayer.audioSessionId
        context.broadcastAudioSession()
    }

    private val settings: SharedPreferences = context.globalFx()
    private var oldSettings = settings
    private fun applyCustomEffects() {
        oldSettings.unregisterOnSharedPreferenceChangeListener(listener)
        val current = context.getFxPrefs(settings, exoPlayer.currentMediaItem?.mediaId?.hashCode())
            ?: settings
        oldSettings = current
        current.registerOnSharedPreferenceChangeListener(listener)
        applyPlayback(current)
        effects.applySettings(current)
    }

    private fun createEffects() = Effects(exoPlayer.audioSessionId)

    private fun applyPlayback(settings: SharedPreferences) {
        val index = settings.getInt(PLAYBACK_SPEED, speedRange.indexOf(1f))
        val speed = speedRange.getOrNull(index) ?: 1f
        val pitch = if (settings.getBoolean(CHANGE_PITCH, true)) speed else 1f
        exoPlayer.playbackParameters =
            PlaybackParameters(speed, pitch)
    }

    private var effects: Effects = createEffects()
    private val listener = OnSharedPreferenceChangeListener { _, _ -> applyCustomEffects() }

    private var pendingFadeOutMessage: PlayerMessage? = null

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        applyCustomEffects()
        applyGain(mediaItem)
        if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_AUTO &&
            reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
        ) {
            audioEffectsProcessor.cancelFades()
        }
        val skipForAlbum = mediaItem?.context is Album &&
            context.getSettings().getBoolean(SKIP_FADE_ON_ALBUMS, true)
        audioEffectsProcessor.skipFade = skipForAlbum
        if (!skipForAlbum) scheduleFadeOut()
    }

    private fun applyGain(mediaItem: MediaItem?) {
        val gainDb = runCatching {
            mediaItem?.track?.extras?.get("GAIN")?.toFloatOrNull()
        }.getOrNull()
        audioEffectsProcessor.setTrackGain(gainDb, mediaItem?.mediaId)
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        if (reason == Player.TIMELINE_CHANGE_REASON_SOURCE_UPDATE) scheduleFadeOut()
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK) {
            audioEffectsProcessor.cancelFades()
            scheduleFadeOut()
        }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_IDLE) {
            pendingFadeOutMessage?.cancel()
            pendingFadeOutMessage = null
        }
    }

    fun updateNormalizationSettings() {
        if (!audioEffectsProcessor.normalizationEnabled) {
            audioEffectsProcessor.resetGain()
        } else {
            applyGain(exoPlayer.currentMediaItem)
        }
    }

    fun updateCrossfadeSettings() {
        if (!audioEffectsProcessor.crossfadeEnabled) {
            pendingFadeOutMessage?.cancel()
            pendingFadeOutMessage = null
            audioEffectsProcessor.cancelFades()
        } else {
            scheduleFadeOut()
        }
    }

    private fun scheduleFadeOut() {
        pendingFadeOutMessage?.cancel()
        pendingFadeOutMessage = null
        if (!audioEffectsProcessor.crossfadeEnabled) return
        val duration = exoPlayer.duration
        val crossfadeMs = audioEffectsProcessor.crossfadeDurationMs.toLong()
        if (duration == C.TIME_UNSET || duration <= crossfadeMs) return
        val speed = exoPlayer.playbackParameters.speed.coerceAtLeast(0.1f)
        val triggerPosition = duration - (crossfadeMs * speed).toLong()
        if (triggerPosition <= exoPlayer.currentPosition) {
            audioEffectsProcessor.onFadeOutStart()
            return
        }
        pendingFadeOutMessage = exoPlayer.createMessage(PlayerMessage.Target { _, _ ->
            audioEffectsProcessor.onFadeOutStart()
        }).setPosition(triggerPosition).send()
    }
    override fun onAudioSessionIdChanged(audioSessionId: Int) {
        release()
        context.broadcastAudioSession()
        audioSessionFlow.value = audioSessionId
        effects = createEffects()
        effects.applySettings(oldSettings)
    }

    class Effects(sessionId: Int) {
        private val equalizer = runCatching { Equalizer(0, sessionId) }.getOrNull()
        private val gain = runCatching { LoudnessEnhancer(sessionId) }.getOrNull()
        private fun applyBassBoost(strength: Int) = runCatching {
            if (strength == 0) {
                equalizer?.setEnabled(false)
                gain?.setEnabled(false)
                return@runCatching
            }
            gain?.setEnabled(true)
            equalizer?.setEnabled(true)
            equalizer?.apply {
                val value =
                    (strength * bandLevelRange.last().toDouble() / 10).roundToInt().toShort()
                val zero = numberOfBands.toDouble() * 2 / 3
                for (it in 0 until numberOfBands) {
                    val v = (-(it - zero).pow(3) * value / zero.pow(3)).roundToInt()
                    setBandLevel(it.toShort(), v.toShort())
                }
            }
            val g = (strength.toDouble().pow(1.toDouble() / 3) * 1600).roundToInt()
            gain?.setTargetGain(g)
        }

        fun release() {
            runCatching {
                equalizer?.release()
                gain?.release()
            }
        }

        fun applySettings(settings: SharedPreferences) {
            applyBassBoost(settings.getInt(BASS_BOOST, 0))
        }
    }

    companion object {
        const val GLOBAL_FX = "global_fx"
        const val BASS_BOOST = "bass_boost"
        const val PLAYBACK_SPEED = "playback_speed"
        val speedRange = listOf(
            0.1f, 0.175f, 0.25f, 0.33f, 0.5f, 0.66f, 0.75f, 0.85f, 0.9f, 0.95f,
            1f, 1.05f, 1.1f, 1.15f, 1.25f, 1.33f, 1.5f, 1.66f, 1.75f, 1.88f, 2f,
            2.33f, 2.5f, 3f, 4f, 8f, 16f, 32f, 64f
        )

        const val CHANGE_PITCH = "change_pitch"
        const val CUSTOM_EFFECTS = "custom_effects"

        fun Context.globalFx() = getSharedPreferences(GLOBAL_FX, Context.MODE_PRIVATE)!!
        fun Context.deleteGlobalFx() = deleteSharedPreferences(GLOBAL_FX)
        fun Context.getFxPrefs(settings: SharedPreferences, id: Int? = null): SharedPreferences? {
            if (id == null) return null
            val string = id.toString()
            val hasCustom = settings.getStringSet(CUSTOM_EFFECTS, emptySet())?.contains(string)
                ?: false
            return if (!hasCustom) null
            else getSharedPreferences("fx_$string", Context.MODE_PRIVATE)!!.apply {
                if (getBoolean("init", false)) return@apply
                settings.copyTo(this)
                edit { putBoolean("init", true) }
            }
        }

        fun Context.deleteFxPrefs(id: Int) =
            deleteSharedPreferences("fx_$id")
    }

    private fun Context.broadcastAudioSession() {
        // Capture the session id SYNCHRONOUSLY (cheap getter, no binder) so the deferred announce uses the
        // session that was live at call time. Only the sendBroadcast — a blocking binder round-trip to
        // ActivityManagerService that ANR'd service onCreate on slow devices — moves off the main thread,
        // serialized via broadcastDispatcher to keep announce order. The audioSessionFlow write and effects
        // setup at the call sites stay synchronous; nothing in our code observes this broadcast (it's the
        // external-equalizer announce — no app-side receiver), so deferring it changes no internal state.
        val ctx = this
        val id = exoPlayer.audioSessionId
        scope.launch(broadcastDispatcher) {
            ctx.sendBroadcast(Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION).apply {
                putExtra(AudioEffect.EXTRA_PACKAGE_NAME, ctx.packageName)
                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, id)
                putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
            })
        }
    }

    private fun Context.broadcastAudioSessionClose(id: Int) {
        sendBroadcast(Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION).apply {
            putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
            putExtra(AudioEffect.EXTRA_AUDIO_SESSION, id)
        })
    }

    // Runtime session-change CLOSE — deferred off-main on the SAME serial broadcastDispatcher as OPEN, so
    // it can't block the ExoPlayer callback (main) thread on the AMS Binder round-trip (the ANR), and FIFO
    // keeps CLOSE(old) — submitted from release() BEFORE broadcastAudioSession()'s OPEN(new) in
    // onAudioSessionIdChanged — ordered ahead of that OPEN. `id` is passed by the caller (captured
    // synchronously) so the coroutine closes the OLD session, not whatever is live when it runs.
    private fun Context.broadcastAudioSessionCloseDeferred(id: Int) {
        val ctx = this
        scope.launch(broadcastDispatcher) { ctx.broadcastAudioSessionClose(id) }
    }

    private fun release() {
        effects.release()
        // Defer the CLOSE off-main — this runs from onAudioSessionIdChanged on the callback/main thread.
        // audioSessionFlow.value is still the OLD session here (updated AFTER this call), so we close it.
        context.broadcastAudioSessionCloseDeferred(audioSessionFlow.value)
    }

    // Teardown CLOSE — SYNCHRONOUS by design. Called from PlayerService.onDestroy BEFORE scope.cancel();
    // a deferred close would be dropped when the scope is cancelled → the final CLOSE never fires → the
    // external equalizer keeps a stale, leaked effect-session. The cold-start ANR that motivated deferring
    // the runtime OPEN/CLOSE does not apply at teardown (onDestroy, not a contended cold-start onCreate).
    fun releaseBlocking() {
        effects.release()
        context.broadcastAudioSessionClose(audioSessionFlow.value)
    }
}