package dev.brahmkshatriya.echo.playback.listener

import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaSession
import dev.brahmkshatriya.echo.common.clients.LikeClient
import dev.brahmkshatriya.echo.common.helpers.ClientException
import dev.brahmkshatriya.echo.extensions.ExtensionLoader
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.getExtension
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.isClient
import dev.brahmkshatriya.echo.playback.MediaItemUtils
import dev.brahmkshatriya.echo.playback.MediaItemUtils.extensionId
import dev.brahmkshatriya.echo.playback.MediaItemUtils.isLoaded
import dev.brahmkshatriya.echo.playback.MediaItemUtils.retries
import dev.brahmkshatriya.echo.playback.PlayerCommands.getLikeButton
import dev.brahmkshatriya.echo.playback.PlayerCommands.getRepeatButton
import dev.brahmkshatriya.echo.playback.PlayerCommands.getShuffleButton
import dev.brahmkshatriya.echo.playback.PlayerState
import dev.brahmkshatriya.echo.playback.ResumptionUtils
import dev.brahmkshatriya.echo.playback.exceptions.PlayerException
import dev.brahmkshatriya.echo.playback.exceptions.TrackUnavailableException
import dev.brahmkshatriya.echo.utils.Serializer.rootCause
import dev.brahmkshatriya.echo.R
import androidx.media3.datasource.HttpDataSource
import java.net.SocketException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.reflect.KClass

class PlayerEventListener(
    private val context: Context,
    private val scope: CoroutineScope,
    private val session: MediaSession,
    private val currentFlow: MutableStateFlow<PlayerState.Current?>,
    private val extensions: ExtensionLoader,
    private val throwableFlow: MutableSharedFlow<Throwable>,
    private val isAndroidAutoConnected: () -> Boolean = { false }
) : Player.Listener {

    private val player get() = session.player

    private fun updateCustomLayout() = scope.launch(Dispatchers.Main) {
        val item = player.currentMediaItem ?: return@launch
        val supportsLike = withContext(Dispatchers.IO) {
            extensions.music.getExtension(item.extensionId)?.isClient<LikeClient>() ?: false
        }
        val commandButtons = listOfNotNull(
            getShuffleButton(context, player.shuffleModeEnabled),
            getRepeatButton(context, player.repeatMode),
            getLikeButton(context, item).takeIf { supportsLike }
        )
        session.setCustomLayout(commandButtons)
    }

    private fun updateCurrentFlow() {
        val item = player.currentMediaItem
        if (item != null) {
            val isPlaying = player.isPlaying && player.playbackState == Player.STATE_READY
            currentFlow.value = PlayerState.Current(
                player.currentMediaItemIndex, item, item.isLoaded, isPlaying, false
            )
        } else if (player.mediaItemCount == 0 && currentFlow.value?.isPlaceholder == true) {
            // Keep the placeholder until we have real items or decide to clear
        } else {
            currentFlow.value = null
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        updateCurrentFlow()
        updateCustomLayout()
        ResumptionUtils.saveIndex(context, player.currentMediaItemIndex)
    }

    override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
        updateCurrentFlow()
        updateCustomLayout()
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        updateCurrentFlow()
        scope.launch { ResumptionUtils.saveQueue(context, player) }
        if (!timeline.isEmpty() && reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED
            && player.playlistMetadata.title.isNullOrEmpty()
        ) {
            player.setPlaylistMetadata(
                MediaMetadata.Builder().setTitle(context.getString(R.string.queue)).build()
            )
        }
    }

    override fun onRepeatModeChanged(repeatMode: Int) {
        updateCustomLayout()
        ResumptionUtils.saveRepeat(context, repeatMode)
    }

    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
        updateCustomLayout()
        ResumptionUtils.saveShuffle(context, shuffleModeEnabled)
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        updateCurrentFlow()
        if (playbackState == Player.STATE_BUFFERING) {
            Log.d("GladixPlayback", "STATE_BUFFERING: ${player.currentMediaItem?.mediaId} \"${player.currentMediaItem?.mediaMetadata?.title}\"")
            bufferingWatchdog?.cancel()
            bufferingWatchdog = scope.launch {
                delay(BUFFERING_WATCHDOG_MS)
                withContext(Dispatchers.Main) {
                    if (player.playbackState != Player.STATE_BUFFERING) return@withContext
                    val currentMediaId = player.currentMediaItem?.mediaId
                    if (retriedMediaId == null || retriedMediaId != currentMediaId) {
                        retriedMediaId = currentMediaId
                        Log.d("GladixPlayback", "Buffering watchdog: retrying $currentMediaId")
                        val savedIndex = player.currentMediaItemIndex
                        val savedPosition = player.currentPosition
                        player.stop()
                        player.seekTo(savedIndex, savedPosition)
                        player.prepare()
                        player.play()
                    } else {
                        retriedMediaId = null
                        Log.d("GladixPlayback", "Buffering watchdog fired: skipping ${player.currentMediaItem?.mediaId}")
                        consecutiveUnavailableSkips++
                        if (consecutiveUnavailableSkips >= maxConsecutiveUnavailableSkips) {
                            consecutiveUnavailableSkips = 0
                            player.pause()
                            return@withContext
                        }
                        val hasMore = player.currentMediaItemIndex < player.mediaItemCount - 1
                        if (!hasMore) {
                            player.pause()
                            return@withContext
                        }
                        if (isAndroidAutoConnected()) {
                            player.pause()
                            delay(50)
                        }
                        player.seekToNextMediaItem()
                        player.prepare()
                        player.play()
                    }
                }
            }
        } else {
            bufferingWatchdog?.cancel()
            bufferingWatchdog = null
        }
        if (playbackState == Player.STATE_READY) {
            consecutiveUnavailableSkips = 0
            retried404MediaId = null
            retriedSocketMediaId = null
        }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        updateCurrentFlow()
        ResumptionUtils.saveCurrentPos(context, player.currentPosition)
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int
    ) {
        ResumptionUtils.saveCurrentPos(context, player.currentPosition)
    }

    companion object {
        private const val BUFFERING_WATCHDOG_MS = 8_000L
    }

    private val maxRetries = 3
    private val maxSingleItemRetries = 1
    private var currentRetries = 0
    private var last: KClass<*>? = null

    private val maxConsecutiveUnavailableSkips = 3
    private var consecutiveUnavailableSkips = 0

    private var bufferingWatchdog: Job? = null
    private var retriedMediaId: String? = null
    private var retried404MediaId: String? = null
    private var retriedSocketMediaId: String? = null

    override fun onPlayerError(error: PlaybackException) {
        val cause = error.cause ?: error
        val rootCause = cause.rootCause
        val mediaItem = player.currentMediaItem

        if (rootCause is CancellationException) {
            Log.d("GladixPlayback", "onPlayerError: ignoring CancellationException for ${mediaItem?.mediaId}")
            return
        }

        if (rootCause is ClientException.LoginRequired) {
            scope.launch { throwableFlow.emit(PlayerException(mediaItem, rootCause)) }
            return
        }

        if (rootCause is HttpDataSource.InvalidResponseCodeException && rootCause.responseCode == 404) {
            val currentMediaId = mediaItem?.mediaId
            if (retried404MediaId != currentMediaId) {
                retried404MediaId = currentMediaId
                Log.d("GladixPlayback", "onPlayerError: 404 for $currentMediaId, retrying with stop/prepare")
                val savedIndex = player.currentMediaItemIndex
                val savedPosition = player.currentPosition
                player.stop()
                player.seekTo(savedIndex, savedPosition)
                player.prepare()
                player.play()
            } else {
                retried404MediaId = null
                Log.d("GladixPlayback", "onPlayerError: 404 retry failed for $currentMediaId, skipping")
                consecutiveUnavailableSkips++
                if (consecutiveUnavailableSkips >= maxConsecutiveUnavailableSkips) {
                    consecutiveUnavailableSkips = 0
                    player.pause()
                    return
                }
                val hasMore = player.currentMediaItemIndex < player.mediaItemCount - 1
                if (!hasMore) {
                    player.pause()
                    return
                }
                player.seekToNextMediaItem()
                player.prepare()
                player.play()
            }
            return
        }

        val isTransientServerError = rootCause is SocketException
        if (isTransientServerError) {
            val currentMediaId = mediaItem?.mediaId
            if (retriedSocketMediaId == null || retriedSocketMediaId != currentMediaId) {
                retriedSocketMediaId = currentMediaId
                Log.d("GladixPlayback", "onPlayerError: SocketException for $currentMediaId, retrying")
                val savedIndex = player.currentMediaItemIndex
                val savedPosition = player.currentPosition
                player.stop()
                player.seekTo(savedIndex, savedPosition)
                player.prepare()
                player.play()
            } else {
                retriedSocketMediaId = null
                Log.d("GladixPlayback", "onPlayerError: SocketException retry failed for $currentMediaId, skipping")
                consecutiveUnavailableSkips++
                if (consecutiveUnavailableSkips >= maxConsecutiveUnavailableSkips) {
                    consecutiveUnavailableSkips = 0
                    player.pause()
                    return
                }
                val hasMore = player.currentMediaItemIndex < player.mediaItemCount - 1
                if (!hasMore) {
                    player.pause()
                    return
                }
                if (isAndroidAutoConnected()) {
                    scope.launch {
                        withContext(Dispatchers.Main) {
                            player.pause()
                            delay(50)
                            player.seekToNextMediaItem()
                            player.prepare()
                            player.play()
                        }
                    }
                } else {
                    player.seekToNextMediaItem()
                    player.prepare()
                    player.play()
                }
            }
            return
        }

        if (rootCause is TrackUnavailableException || rootCause.message?.contains("not available", ignoreCase = true) == true) {
            consecutiveUnavailableSkips++
            if (consecutiveUnavailableSkips >= maxConsecutiveUnavailableSkips) {
                consecutiveUnavailableSkips = 0
                player.pause()
                val isRetryExhausted = rootCause.message?.contains("not available after retries", ignoreCase = true) == true
                if (!isRetryExhausted) scope.launch { throwableFlow.emit(PlayerException(mediaItem, rootCause)) }
                return
            }
            val hasMore = player.currentMediaItemIndex < player.mediaItemCount - 1
            if (!hasMore) {
                player.pause()
                return
            }
            if (isAndroidAutoConnected()) {
                scope.launch {
                    withContext(Dispatchers.Main) {
                        player.pause()
                        delay(50)
                        player.seekToNextMediaItem()
                        player.prepare()
                        player.play()
                    }
                }
            } else {
                player.seekToNextMediaItem()
                player.prepare()
                player.play()
            }
            return
        }

        scope.launch { throwableFlow.emit(PlayerException(mediaItem, cause)) }

        val old = last
        last = rootCause::class
        if (old != null && old == last) currentRetries++
        else currentRetries = 0

        if (mediaItem == null) return
        val index = player.currentMediaItemIndex
        val retries = mediaItem.retries

        if (currentRetries >= maxRetries) return
        if (retries >= maxSingleItemRetries) {
            val hasMore = index < player.mediaItemCount - 1
            if (!hasMore) {
                player.pause()
                return
            }
            player.seekToNextMediaItem()
        } else {
            val newItem = MediaItemUtils.withRetry(mediaItem)
            player.replaceMediaItem(index, newItem)
        }
        player.prepare()
        player.play()
    }
}
