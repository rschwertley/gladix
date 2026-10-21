package dev.brahmkshatriya.echo.playback.listener

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.media.audiofx.AudioEffect
import androidx.annotation.OptIn
import androidx.core.content.edit
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.copyTo
import dev.brahmkshatriya.echo.playback.MediaItemUtils.track
import dev.brahmkshatriya.echo.playback.renderer.AudioEffectsProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
class EffectsListener(
    private val exoPlayer: ExoPlayer,
    private val context: Context,
    private val audioSessionFlow: MutableStateFlow<Int>,
    private val audioEffectsProcessor: AudioEffectsProcessor,
    /**
     * ⚠⚠ APPLIES BASS BOOST TO EVERY PROCESSOR, WHICH IS MORE THAN THIS CLASS
     * CAN REACH. [audioEffectsProcessor] is the MAIN player's; the crossfade tail has its own
     * instance, created and owned by PlayerService, and a shelf on one leg only would make the
     * tail audibly different from the head - the exact defect the session-sharing rule existed to
     * prevent. So the fan-out lives at the one place that can see both.
     * Defaults to the main processor alone, which keeps this class usable in isolation.
     */
    private val applyBassBoost: (Int) -> Unit = { audioEffectsProcessor.setBassBoost(it) },
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
        // ⚠⚠ BASS BOOST IS OURS NOW, NOT THE SESSION'S. See
        // AudioEffectsProcessor.setBassBoost for the arithmetic that retired the old pair and
        // why a session effect could not be kept: two clients on one engine, control by
        // priority, and a setEnabled(false) of ours switched the system EQ off with it.
        // PlayerService's lambda applies it to the main processor AND the crossfade tail's.
        applyBassBoost(current.getInt(BASS_BOOST, 0))
    }

    private fun applyPlayback(settings: SharedPreferences) {
        val index = settings.getInt(PLAYBACK_SPEED, speedRange.indexOf(1f))
        val speed = speedRange.getOrNull(index) ?: 1f
        val pitch = if (settings.getBoolean(CHANGE_PITCH, true)) speed else 1f
        exoPlayer.playbackParameters =
            PlaybackParameters(speed, pitch)
    }

    private val listener = OnSharedPreferenceChangeListener { _, _ -> applyCustomEffects() }

    /**
     * ⚠⚠ A SECOND init BLOCK, AND ITS POSITION IN THE FILE IS THE WHOLE REASON
     * IT IS SEPARATE. Kotlin runs property initialisers and init blocks in DECLARATION ORDER, and
     * [applyCustomEffects] touches [settings], [oldSettings] and [listener] - all declared above
     * this line and BELOW the first init block. Calling it from there would read an
     * uninitialised [oldSettings] and NPE on the unregister. So it runs here, after everything it
     * needs exists. Do not merge the two init blocks.
     *
     * ⚠⚠ WHY IT IS CALLED AT ALL: UNTIL 2026-10-21 EFFECTS WAITED FOR THE FIRST
     * MEDIA-ITEM TRANSITION. [onMediaItemTransition] was the only caller, so a freshly started
     * service played its first track with no bass boost and no saved playback speed - and, worse,
     * with NO PREFS LISTENER REGISTERED, since registering it is something applyCustomEffects
     * does. Moving the slider before the first transition therefore changed nothing at all. The
     * old platform-effect implementation had the same gap and it went unnoticed because the
     * effects it configured were disabled by default anyway.
     * ⚠️ SAFE TO RUN BEFORE ANY ITEM EXISTS: currentMediaItem is null, so
     * getFxPrefs falls through to the global file, which is the right answer for "no track yet".
     * The first transition re-resolves and re-points the listener at the track's own file if it
     * has one.
     */
    init {
        applyCustomEffects()
    }

    // ⚠⚠ TRACK FADE LIVED HERE AND IS GONE AS OF BUILD 2 (2026-10-15). What this hook
    // used to own: a PlayerMessage scheduled at `duration - crossfadeMs*speed` that called
    // AudioEffectsProcessor.onFadeOutStart, re-posted on transition / SOURCE_UPDATE / seek, plus the
    // skipFade flag that suppressed it on albums. All of it is replaced by CrossfadeController, which
    // overlaps two players instead of fading one out into silence - so there is nothing to schedule
    // here any more, and the album rule moved to its armRefusal (same key, SKIP_FADE_ON_ALBUMS,
    // same meaning).
    // ⚠️ DO NOT RE-ADD A FADE HOOK ON THE TRANSITION REASON. The crossfade hand-off is
    // a SEEK, so a reason-keyed fade here would fire in the middle of an overlap. The ramps are
    // driven by the controller through the two processors; this class owns gain and session effects.
    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        applyCustomEffects()
        applyGain(mediaItem)
    }

    private fun applyGain(mediaItem: MediaItem?) {
        val gainDb = runCatching {
            mediaItem?.track?.extras?.get("GAIN")?.toFloatOrNull()
        }.getOrNull()
        audioEffectsProcessor.setTrackGain(gainDb, mediaItem?.mediaId)
    }

    fun updateNormalizationSettings() {
        if (!audioEffectsProcessor.normalizationEnabled) {
            audioEffectsProcessor.resetGain()
        } else {
            applyGain(exoPlayer.currentMediaItem)
        }
    }

    /**
     * ⚠⚠ ANNOUNCE-ONLY SINCE 2026-10-21, AND THAT IS THE POINT OF THE CHANGE.
     * This used to release and rebuild our own Equalizer + LoudnessEnhancer on the new session,
     * which is the only reason they ever ended up on the right one: the id arrives
     * ASYNCHRONOUSLY (ExoPlayerImpl generates it on the playback thread from its constructor,
     * 1.11.0 :427-443), so the pair built in [init] was built on AUDIO_SESSION_ID_UNSET. Now that
     * bass boost lives in AudioEffectsProcessor there is nothing of ours on the session to
     * rebuild, and the release/re-create - a chain reconfiguration on a live session, i.e. an
     * audible click - goes with it.
     * ⚠️ THE BROADCASTS STAY. The OPEN/CLOSE pair is how a system or
     * third-party equalizer attaches to our session at all, which is still wanted; we have simply
     * stopped competing with it for the same effect engine.
     */
    override fun onAudioSessionIdChanged(audioSessionId: Int) {
        context.broadcastAudioSessionCloseDeferred(audioSessionFlow.value)
        context.broadcastAudioSession()
        audioSessionFlow.value = audioSessionId
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

    // Teardown CLOSE — SYNCHRONOUS by design. Called from PlayerService.onDestroy BEFORE scope.cancel();
    // a deferred close would be dropped when the scope is cancelled → the final CLOSE never fires → the
    // external equalizer keeps a stale, leaked effect-session. The cold-start ANR that motivated deferring
    // the runtime OPEN/CLOSE does not apply at teardown (onDestroy, not a contended cold-start onCreate).
    fun releaseBlocking() {
        // ⚠️ NOTHING OF OURS IS ATTACHED TO THE SESSION ANY MORE, so there is no
        // effect to release here - the pair this used to free lived on it until 2026-10-21 and
        // bass boost is now an AudioProcessor stage, released with the player. The CLOSE is still
        // owed: an external equalizer attached through our OPEN has to be told the session is
        // going, or it keeps a stale handle.
        context.broadcastAudioSessionClose(audioSessionFlow.value)
    }
}