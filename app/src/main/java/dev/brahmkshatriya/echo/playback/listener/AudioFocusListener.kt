package dev.brahmkshatriya.echo.playback.listener

import android.content.Context
import android.util.Log
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import androidx.media3.common.Player

@Suppress("DEPRECATION")
class AudioFocusListener(
    val context: Context,
    val player: Player
) : Player.Listener {

    private val handler = Handler(context.mainLooper)
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // ⚠⚠ READ BY CrossfadeController, AND IT IS THE ONLY RELIABLE DISCRIMINATOR.
    // ShufflePlayer forces handleAudioFocus=false, so every pause this class makes goes THROUGH the
    // player and surfaces to other listeners as PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST - a real
    // user pause and a focus pause are indistinguishable by reason code. This flag is set BEFORE
    // every auto-resumable pause below (AUDIOFOCUS_LOSS, AUDIOFOCUS_LOSS_TRANSIENT,
    // AUDIOFOCUS_REQUEST_DELAYED) and pause() flushes its listeners inline on this thread, so a
    // reader in that callback sees it. AUDIOFOCUS_REQUEST_FAILED deliberately does NOT set it:
    // focus was denied with no auto-resume, so a crossfade SHOULD abort there.
    // Read-only outside this class; this class is the only writer.
    internal var pausedForFocus = false
        private set
    private var loweringVolume = false

    // Mirrors whether commitPauseRunnable is actually queued. cancelCommit() reads it to skip
    // handler.removeCallbacks — the framework's full MAIN-looper MessageQueue scan (removeMessagesLegacy),
    // which ANR'd on budget devices during onDestroy — when nothing is pending (the usual state at teardown).
    // Only ever touched on the main thread (postDelayed, the runnable, and every abandon path run on the main
    // looper), so no synchronization is needed.
    private var pendingCommit = false

    // Fires after the grace window expires — abandons focus after immediate pause on AUDIOFOCUS_LOSS
    private val commitPauseRunnable = Runnable {
        pendingCommit = false   // executing now → no longer queued; clear before abandonFocus so its cancelCommit() no-ops
        abandonFocus()
    }

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                Log.d("GladixAudio", "AUDIOFOCUS_GAIN: playbackState=${player.playbackState} pausedForFocus=$pausedForFocus playWhenReady=${player.playWhenReady}")
                cancelCommit()
                if (loweringVolume) {
                    player.volume = 1f
                    loweringVolume = false
                }
                if (pausedForFocus) {
                    pausedForFocus = false
                    player.play()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                Log.d("GladixAudio", "AUDIOFOCUS_LOSS: playWhenReady=${player.playWhenReady}")
                if (player.playWhenReady) {
                    cancelCommit()
                    pausedForFocus = true
                    player.pause()
                    postCommit()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                cancelCommit()
                if (player.playWhenReady) {
                    pausedForFocus = true
                    player.pause()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                player.volume = DUCK_VOLUME
                loweringVolume = true
            }
        }
    }

    private val focusRequest: AudioFocusRequest? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAcceptsDelayedFocusGain(true)
            .setOnAudioFocusChangeListener(focusChangeListener, handler)
            .build()
    } else null

    internal fun requestFocus() {
        val result = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                audioManager.requestAudioFocus(focusRequest!!)
            else audioManager.requestAudioFocus(
                focusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        } catch (e: RuntimeException) {
            // The system can REJECT focus by THROWING instead of returning a code — SecurityException
            // "not allowed to perform TAKE_AUDIO_FOCUS" on some OEM / managed / restricted-background
            // contexts — which the return-value `when` below can't catch, so it was propagating out of
            // onPlayWhenReadyChanged and crashing (build 985). Treat a thrown denial EXACTLY like a
            // returned AUDIOFOCUS_REQUEST_FAILED (pause, no auto-resume) so it degrades gracefully.
            // RuntimeException (SecurityException's supertype) also covers any other binder-thrown
            // RuntimeException (DeadObject/IllegalState) from the same call.
            Log.w("GladixAudio", "requestAudioFocus threw — treating as REQUEST_FAILED", e)
            AudioManager.AUDIOFOCUS_REQUEST_FAILED
        }
        when (result) {
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> { /* normal, proceed */ }
            AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> {
                // Focus queued — pause now, AUDIOFOCUS_GAIN resumes when it arrives.
                // pausedForFocus=true prevents onPlayWhenReadyChanged from calling
                // abandonFocus(), which would cancel the queued request.
                pausedForFocus = true
                player.pause()
            }
            AudioManager.AUDIOFOCUS_REQUEST_FAILED -> {
                // Focus denied — pause without auto-resume.
                player.pause()
            }
        }
    }

    // Posting and cancelling the grace-window commit go through these two so pendingCommit stays in lockstep
    // with the real queue state — postDelayed/removeCallbacks for commitPauseRunnable exist nowhere else.
    private fun postCommit() {
        handler.postDelayed(commitPauseRunnable, GRACE_WINDOW_MS)
        pendingCommit = true
    }

    // Skips the removeCallbacks MAIN-queue scan when nothing is queued. When a commit IS pending it removes
    // it exactly as before, then clears the flag.
    private fun cancelCommit() {
        if (!pendingCommit) return
        handler.removeCallbacks(commitPauseRunnable)
        pendingCommit = false
    }

    private fun abandonFocus() {
        cancelCommit()
        // Same binder-throw risk as requestFocus (SecurityException / other RuntimeException from the
        // system): a throw here would crash from the many teardown paths that call this (release, STATE_IDLE,
        // becoming-noisy, the grace-window commit). Best-effort — abandoning focus has no fallback behavior.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                audioManager.abandonAudioFocusRequest(focusRequest!!)
            else audioManager.abandonAudioFocus(focusChangeListener)
        } catch (e: RuntimeException) {
            Log.w("GladixAudio", "abandonAudioFocus threw — ignoring", e)
        }
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (playWhenReady) {
            requestFocus()
            if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST && loweringVolume) {
                player.volume = 1f
                loweringVolume = false
            }
        } else if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY) {
            // BT/headphone disconnect — cancel any pending focus-driven pause and abandon focus
            // so AUDIOFOCUS_GAIN cannot re-enable playback on the now-disconnected device
            cancelCommit()
            pausedForFocus = false
            abandonFocus()
        } else if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST && !pausedForFocus) {
            // User explicitly paused (not focus-driven) — release focus so other apps can play
            cancelCommit()
            abandonFocus()
        }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_IDLE) abandonFocus()
        else if (playbackState == Player.STATE_BUFFERING && player.playWhenReady) {
            Log.d("GladixAudio", "STATE_BUFFERING+playWhenReady: re-requesting focus defensively")
            requestFocus()
        }
    }

    fun release() {
        abandonFocus()
    }

    companion object {
        private const val GRACE_WINDOW_MS = 750L
        private const val DUCK_VOLUME = 0.4f
    }
}
