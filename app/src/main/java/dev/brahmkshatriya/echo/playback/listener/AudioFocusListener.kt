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

    private var pausedForFocus = false
    private var loweringVolume = false

    // Fires after the grace window expires — commits the pause caused by AUDIOFOCUS_LOSS
    private val commitPauseRunnable = Runnable {
        pausedForFocus = true
        player.pause()
    }

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                Log.d("GladixAudio", "AUDIOFOCUS_GAIN: playbackState=${player.playbackState} pausedForFocus=$pausedForFocus playWhenReady=${player.playWhenReady}")
                handler.removeCallbacks(commitPauseRunnable)
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
                if (player.playWhenReady) {
                    handler.removeCallbacks(commitPauseRunnable)
                    handler.postDelayed(commitPauseRunnable, GRACE_WINDOW_MS)
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                handler.removeCallbacks(commitPauseRunnable)
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

    private fun requestFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            audioManager.requestAudioFocus(focusRequest!!)
        else audioManager.requestAudioFocus(
            focusChangeListener,
            AudioManager.STREAM_MUSIC,
            AudioManager.AUDIOFOCUS_GAIN
        )
    }

    private fun abandonFocus() {
        handler.removeCallbacks(commitPauseRunnable)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            audioManager.abandonAudioFocusRequest(focusRequest!!)
        else audioManager.abandonAudioFocus(focusChangeListener)
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (playWhenReady) {
            requestFocus()
        } else if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY) {
            // BT/headphone disconnect — cancel any pending focus-driven pause and abandon focus
            // so AUDIOFOCUS_GAIN cannot re-enable playback on the now-disconnected device
            handler.removeCallbacks(commitPauseRunnable)
            pausedForFocus = false
            abandonFocus()
        } else if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST && !pausedForFocus) {
            // User explicitly paused (not focus-driven) — release focus so other apps can play
            handler.removeCallbacks(commitPauseRunnable)
            abandonFocus()
        }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_IDLE) abandonFocus()
    }

    fun release() {
        abandonFocus()
    }

    companion object {
        private const val GRACE_WINDOW_MS = 1500L
        private const val DUCK_VOLUME = 0.2f
    }
}
