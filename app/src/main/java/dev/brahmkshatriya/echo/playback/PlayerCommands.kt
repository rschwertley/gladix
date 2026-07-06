package dev.brahmkshatriya.echo.playback

import android.content.Context
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.CommandButton
import androidx.media3.session.SessionCommand
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.playback.MediaItemUtils.isLiked

object PlayerCommands {
    val likeCommand = SessionCommand("liked", Bundle.EMPTY)
    val unlikeCommand = SessionCommand("unliked", Bundle.EMPTY)
    val repeatCommand = SessionCommand("repeat", Bundle.EMPTY)
    val repeatOffCommand = SessionCommand("repeat_off", Bundle.EMPTY)
    val repeatOneCommand = SessionCommand("repeat_one", Bundle.EMPTY)
    val shuffleCommand = SessionCommand("shuffle_on", Bundle.EMPTY)
    val shuffleOffCommand = SessionCommand("shuffle_off", Bundle.EMPTY)
    val playCommand = SessionCommand("play", Bundle.EMPTY)
    val addToQueueCommand = SessionCommand("add_to_queue", Bundle.EMPTY)
    val addToNextCommand = SessionCommand("add_to_next", Bundle.EMPTY)
    val radioCommand = SessionCommand("radio", Bundle.EMPTY)
    val sleepTimer = SessionCommand("sleep_timer", Bundle.EMPTY)
    val resumeCommand = SessionCommand("resume", Bundle.EMPTY)
    val imageCommand = SessionCommand("image", Bundle.EMPTY)
    val backfillCommand = SessionCommand("backfill", Bundle.EMPTY)
    val seekToFullCommand = SessionCommand("seek_to_full", Bundle.EMPTY)

    fun getLikeButton(context: Context, item: MediaItem) =
        if (!item.isLiked)
            CommandButton.Builder(CommandButton.ICON_HEART_UNFILLED)
                .setDisplayName(context.getString(R.string.like))
                .setCustomIconResId(R.drawable.ic_favorite_20dp)
                .setSessionCommand(likeCommand)
                .build()
        else
            CommandButton.Builder(CommandButton.ICON_HEART_FILLED)
                .setDisplayName(context.getString(R.string.unlike))
                .setCustomIconResId(R.drawable.ic_favorite_filled_20dp)
                .setSessionCommand(unlikeCommand)
                .build()

    fun getShuffleButton(context: Context, shuffleEnabled: Boolean): CommandButton =
        if (shuffleEnabled)
            CommandButton.Builder(CommandButton.ICON_SHUFFLE_ON)
                .setDisplayName(context.getString(R.string.shuffle))
                .setCustomIconResId(R.drawable.ic_shuffle_on_40dp)
                .setSessionCommand(shuffleOffCommand)
                .build()
        else
            CommandButton.Builder(CommandButton.ICON_SHUFFLE_OFF)
                .setDisplayName(context.getString(R.string.shuffle))
                .setCustomIconResId(R.drawable.ic_shuffle_40dp)
                .setSessionCommand(shuffleCommand)
                .build()

    fun getRepeatButton(context: Context, repeat: Int) = when (repeat) {
        Player.REPEAT_MODE_ONE ->
            CommandButton.Builder(CommandButton.ICON_REPEAT_ONE)
                .setDisplayName(context.getString(R.string.repeat_one))
                .setCustomIconResId(R.drawable.ic_repeat_one_20dp)
                .setSessionCommand(repeatOffCommand)
                .build()

        Player.REPEAT_MODE_OFF ->
            CommandButton.Builder(CommandButton.ICON_REPEAT_OFF)
                .setDisplayName(context.getString(R.string.repeat_off))
                .setCustomIconResId(R.drawable.ic_repeat_20dp)
                .setSessionCommand(repeatCommand)
                .build()

        else ->
            CommandButton.Builder(CommandButton.ICON_REPEAT_ALL)
                .setDisplayName(context.getString(R.string.repeat_all))
                .setCustomIconResId(R.drawable.ic_repeat_on_20dp)
                .setSessionCommand(repeatOneCommand)
                .build()
    }
}