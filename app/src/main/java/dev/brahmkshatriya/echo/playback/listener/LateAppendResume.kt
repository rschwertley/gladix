package dev.brahmkshatriya.echo.playback.listener

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import androidx.media3.common.Player
import dev.brahmkshatriya.echo.cast.CastState
import dev.brahmkshatriya.echo.playback.queueEpochOrZero

/**
 * ⚠⚠ THE PHONE'S AUTO-RESUME WHEN RADIO SONGS LAND AFTER THE QUEUE ENDED (2026-11-06, the deferral at RadioFallback's class note).
 * TV has its own at PlayerRadio.tvDriveRadio; this is never armed there. In memory only, so a restart cannot trigger it.
 * All on the app looper: the listener callbacks, the session callback, and appendDeduped's withContext(Main).
 *
 * ARMED by PlayerEventListener's STATE_ENDED settle, from playWhenReady read BEFORE its own pause: still true means nobody paused
 * and the song ran out. A stop or a dismissal ends at IDLE and an error stops the player, so neither reaches ENDED.
 * CLEARED by anything else that happens: every controller command (PlayerCallback.onPlayerCommandRequest), setting a sleep timer, a
 * seek or skip, a song change, IDLE, a noisy event, an output device going away, and a new queue (the epoch, checked at the resume).
 * [CORRECTED 2026-11-06] "Anything else" and "every controller command" were too broad: onPlayerCommandRequest sees Player commands
 * only, not custom session commands. Add to queue, play next, like, repeat and shuffle do NOT clear this, and need not: none says
 * the music should stop, and the first two only add songs, so a resume plays what the user queued. Custom commands that do count:
 * the sleep timer clears it itself (PlayerCallback.onSleepTimer), and playing an item or starting a radio replaces the queue, which
 * the epoch and the song-change clear cover (inferred, not traced per command).
 * ⚠️ A SLEEP TIMER RUNNING WHEN THE SONG ENDS REFUSES THE ARM, because "End of track" fires at about that moment and can land on
 * either side of it (PlayerCallback.onSleepTimer).
 * ⚠️ THERE IS NO FOCUS LOSS TO CLEAR ON. The settle's own pause makes AudioFocusListener abandon focus, so nothing reports a loss
 * while this waits. Hence the check at the resume: another app playing, or a call, refuses it.
 */
internal object LateAppendResume {
    // [2026-11-12] Internal, not private: RadioFallback.TOTAL_BUDGET_MS is derived from it, so its songs land inside it.
    const val WINDOW_MS = 20_000L

    // SystemClock.elapsedRealtime when the queue ended; 0 when not armed.
    private var endedAtMs = 0L
    private var endedEpoch = 0L

    fun arm(player: Player) {
        endedAtMs = SystemClock.elapsedRealtime()
        endedEpoch = player.queueEpochOrZero
    }

    fun clear() {
        endedAtMs = 0L
    }

    /** One shot: the first append that adds songs decides. seekToNextMediaItem then play is tvDriveRadio's shape. */
    fun onAppended(player: Player, context: Context) {
        val endedAt = endedAtMs
        if (endedAt == 0L) return
        clear()
        if (SystemClock.elapsedRealtime() - endedAt > WINDOW_MS) return
        if (player.queueEpochOrZero != endedEpoch) return
        if (player.playbackState != Player.STATE_ENDED || !player.hasNextMediaItem()) return
        if (CastState.isCasting) return
        val audio = context.getSystemService(AudioManager::class.java)
        if (audio.mode != AudioManager.MODE_NORMAL || audio.isMusicActive) return
        player.seekToNextMediaItem()
        player.play()
    }
}
