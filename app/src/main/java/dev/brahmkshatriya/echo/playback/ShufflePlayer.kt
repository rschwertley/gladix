package dev.brahmkshatriya.echo.playback

import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder

@Suppress("unused")
@OptIn(UnstableApi::class)
class ShufflePlayer(
    private val player: ExoPlayer,
) : ForwardingPlayer(player) {

    init {
        player.setShuffleOrder(ShuffleOrder.UnshuffledShuffleOrder(0))
    }

    private fun getQueue() = (0 until mediaItemCount).map { player.getMediaItemAt(it) }

    private var isShuffled = false
    private var original = getQueue()

    override fun setShuffleModeEnabled(enabled: Boolean) {
        if (enabled) original = getQueue()
        isShuffled = enabled
        changeQueue(if (enabled) original.shuffled() else original)
        player.shuffleModeEnabled = enabled
    }

    override fun hasNextMediaItem(): Boolean {
        return player.currentMediaItemIndex < mediaItemCount - 1
    }

    // Use player.currentMediaItemIndex directly — NOT getCurrentMediaItemIndex() — to avoid
    // WindowedTimeline offset. CrossfadePlayer must override setAudioAttributes() to broadcast
    // to both internal players.
    fun peekNextItem(): MediaItem? {
        val nextIndex = player.currentMediaItemIndex + 1
        return if (nextIndex < player.mediaItemCount) player.getMediaItemAt(nextIndex) else null
    }

    @Suppress("UNUSED_PARAMETER")
    private fun log(name: String) {
//        println(name)
//        println("$isShuffled list ${original.size}: ${original.map { it.mediaMetadata.title }}")
//        println("player ${mediaItemCount}: ${getQueue().map { it.mediaMetadata.title }}")
    }

    private fun changeQueue(list: List<MediaItem>) {
        log("Change queue")
        if (list.size <= 1) return
        val currentMediaItem = list.first { it.mediaId == currentMediaItem?.mediaId }
        val index = list.indexOf(currentMediaItem)
        val before = list.take(index) - currentMediaItem
        val after = list.takeLast(list.size - index) - currentMediaItem
        // Use player.currentMediaItemIndex directly — this is the raw ExoPlayer index and must
        // not go through getCurrentMediaItemIndex(), which returns a windowed offset.
        if (player.currentMediaItemIndex > 0)
            player.removeMediaItems(0, player.currentMediaItemIndex)
        player.addMediaItems(0, before)
        player.removeMediaItems(player.currentMediaItemIndex + 1, mediaItemCount)
        player.addMediaItems(player.currentMediaItemIndex + 1, after)
    }

    fun onMediaItemChanged(old: MediaItem, new: MediaItem) {
        original = original.toMutableList().apply {
            val index = indexOf(old).takeIf { it != -1 } ?: return
            set(index, new)
        }
        log("Change media item")
    }

    override fun addMediaItem(mediaItem: MediaItem) {
        original = original + mediaItem
        player.addMediaItem(mediaItem)
        log("Add media item")
    }

    override fun addMediaItems(mediaItems: MutableList<MediaItem>) {
        original = original + mediaItems
        player.addMediaItems(mediaItems)
        log("Add media items")
    }

    override fun addMediaItem(index: Int, mediaItem: MediaItem) {
        original = original + mediaItem
        player.addMediaItem(index, mediaItem)
        log("Add media item at $index")
    }

    override fun addMediaItems(index: Int, mediaItems: MutableList<MediaItem>) {
        original = original + mediaItems
        player.addMediaItems(index, mediaItems)
        log("Add media items at $index")
    }

    private fun getItemAt(index: Int) = player.getMediaItemAt(index).let {
        original.first { item -> item.mediaId == it.mediaId }
    }

    override fun removeMediaItem(index: Int) {
        original = original - getItemAt(index)
        player.removeMediaItem(index)
        log("Remove media item at $index")
    }

    override fun removeMediaItems(fromIndex: Int, toIndex: Int) {
        original =
            original - (fromIndex until toIndex).map { getItemAt(it) }.toSet()
        player.removeMediaItems(fromIndex, toIndex)
        log("Remove media items from $fromIndex to $toIndex")
    }

    override fun replaceMediaItem(index: Int, mediaItem: MediaItem) {
        original = original.toMutableList().apply {
            val originalIndex = indexOf(getItemAt(index)).takeIf { it != -1 }!!
            set(originalIndex, mediaItem)
        }
        player.replaceMediaItem(index, mediaItem)
        log("Replace media item at $index")
    }

    override fun replaceMediaItems(
        fromIndex: Int, toIndex: Int, mediaItems: MutableList<MediaItem>
    ) {
        original = original.toMutableList().apply {
            val originalIndexes = (fromIndex until toIndex).map { i ->
                indexOf(getItemAt(i)).takeIf { it != -1 }!!
            }
            originalIndexes.forEachIndexed { i, originalIndex ->
                set(originalIndex, mediaItems[i])
            }
        }
        player.replaceMediaItems(fromIndex, toIndex, mediaItems)
        log("Replace media items from $fromIndex to $toIndex")
    }

    override fun setMediaItem(mediaItem: MediaItem) {
        original = listOf(mediaItem)
        player.setMediaItem(mediaItem)
        log("Set media item")
    }

    override fun setMediaItem(mediaItem: MediaItem, resetPosition: Boolean) {
        original = listOf(mediaItem)
        player.setMediaItem(mediaItem, resetPosition)
        log("Set media item")
    }

    override fun setMediaItem(mediaItem: MediaItem, startPositionMs: Long) {
        original = listOf(mediaItem)
        player.setMediaItem(mediaItem, startPositionMs)
        log("Set media item")
    }

    override fun setMediaItems(mediaItems: MutableList<MediaItem>) {
        original = mediaItems
        player.setMediaItems(mediaItems)
        log("Set media items")
    }

    override fun setMediaItems(mediaItems: MutableList<MediaItem>, resetPosition: Boolean) {
        original = mediaItems
        player.setMediaItems(mediaItems, resetPosition)
        log("Set media items")
    }

    override fun setMediaItems(
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long
    ) {
        original = mediaItems
        player.setMediaItems(
            mediaItems,
            startIndex.coerceAtMost(mediaItems.size - 1),
            startPositionMs
        )
        log("Set media items")
    }

    override fun clearMediaItems() {
        original = emptyList()
        player.clearMediaItems()
        log("Clear media items")
    }

    // Reports STATE_READY when the inner player is STATE_IDLE with a queued but unstarted
    // playlist and playWhenReady=false. This gives Media3's PlaybackStateCompat builder
    // STATE_PAUSED(2) so AA shows the thumbnail play button on cold start, without calling
    // prepare() in onCreate() which caused STATE_ENDED at ~88ms before stream load completed.
    // Purely presentational: all Player.Listener callbacks come from real ExoPlayer (listeners
    // are registered on the inner player by ForwardingPlayer.addListener), so no synthetic
    // STATE_READY callback is delivered to PlayerEventListener or AudioFocusListener.
    override fun getPlaybackState(): Int {
        return if (player.playbackState == Player.STATE_IDLE && mediaItemCount > 0 && !playWhenReady)
            Player.STATE_READY
        else
            player.playbackState
    }

    // Auto-prepares before play when inner player is STATE_IDLE (e.g. cold start restore with
    // no prepare() call). Needed because the PlayerViewModel STATE_IDLE guard sees faked
    // STATE_READY and never fires. Both play() and setPlayWhenReady() are overridden because
    // setPlaying() in PlayerViewModel uses the playWhenReady property setter directly, not play().
    override fun play() {
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0, 0)
        super.play()
    }

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (playWhenReady && player.playbackState == Player.STATE_IDLE) player.prepare()
        if (playWhenReady && player.playbackState == Player.STATE_ENDED) player.seekTo(0, 0)
        super.setPlayWhenReady(playWhenReady)
    }

    // Prevent Media3 session initialization from re-enabling AudioFocusManager.
    // ForwardingPlayer.setAudioAttributes() would forward handleAudioFocus=true to ExoPlayer,
    // causing Media3's internal AudioFocusManager to run in parallel with AudioFocusListener.
    override fun setAudioAttributes(audioAttributes: AudioAttributes, handleAudioFocus: Boolean) {
        super.setAudioAttributes(audioAttributes, false)
    }

    // Returns the start index into the full ExoPlayer timeline for a QUEUE_WINDOW_SIZE window
    // centred on fullIndex. Both getCurrentTimeline() and getCurrentMediaItemIndex() must use
    // this same calculation so that the windowed index is always < windowedTimeline.windowCount.
    private fun windowStart(fullCount: Int, fullIndex: Int): Int {
        val half = QUEUE_WINDOW_SIZE / 2
        return (fullIndex - half).coerceIn(0, fullCount - QUEUE_WINDOW_SIZE)
    }

    // Limit the timeline exposed to the media session (and thus Bluetooth/AVRCP) to a sliding
    // window around the current item. ExoPlayer's internal timeline is unchanged; only the view
    // the session serializes over Binder is trimmed, preventing BadParcelableException when the
    // queue is large (tested failure point: 199 items over com.google.android.bluetooth).
    override fun getCurrentTimeline(): Timeline {
        val full = super.getCurrentTimeline()
        val count = full.windowCount
        if (count <= QUEUE_WINDOW_SIZE) return full
        // Use player.currentMediaItemIndex directly — NOT getCurrentMediaItemIndex() — to avoid
        // a circularity: getCurrentMediaItemIndex() calls windowStart(), which needs the full
        // index that getCurrentTimeline() is computing here.
        val fullIndex = player.currentMediaItemIndex.coerceAtLeast(0)
        return WindowedTimeline(full, windowStart(count, fullIndex), QUEUE_WINDOW_SIZE)
    }

    // Returns the current item index relative to the windowed timeline so that Media3's
    // PlayerInfo.Builder.build() assertion (mediaItemIndex < timeline.windowCount) holds.
    // All internal logic that operates on the real ExoPlayer queue (changeQueue, hasNextMediaItem,
    // getCurrentTimeline's window-start calculation) reads player.currentMediaItemIndex directly.
    override fun getCurrentMediaItemIndex(): Int {
        val full = super.getCurrentTimeline()
        val count = full.windowCount
        if (count <= QUEUE_WINDOW_SIZE) return player.currentMediaItemIndex
        val fullIndex = player.currentMediaItemIndex.coerceAtLeast(0)
        return fullIndex - windowStart(count, fullIndex)
    }

    private class WindowedTimeline(
        private val delegate: Timeline,
        private val windowStart: Int,
        private val windowSize: Int,
    ) : Timeline() {

        private val periodStart: Int
        private val periodCount: Int

        init {
            val w1 = Window()
            val w2 = Window()
            periodStart = delegate.getWindow(windowStart, w1).firstPeriodIndex
            delegate.getWindow(windowStart + windowSize - 1, w2)
            periodCount = w2.lastPeriodIndex - periodStart + 1
        }

        override fun getWindowCount() = windowSize

        override fun getWindow(windowIndex: Int, window: Window, defaultPositionProjectionUs: Long): Window {
            delegate.getWindow(windowStart + windowIndex, window, defaultPositionProjectionUs)
            window.firstPeriodIndex -= periodStart
            window.lastPeriodIndex -= periodStart
            return window
        }

        override fun getPeriodCount() = periodCount

        override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period {
            delegate.getPeriod(periodStart + periodIndex, period, setIds)
            period.windowIndex -= windowStart
            return period
        }

        override fun getIndexOfPeriod(uid: Any): Int {
            val idx = delegate.getIndexOfPeriod(uid)
            if (idx == C.INDEX_UNSET) return C.INDEX_UNSET
            val relative = idx - periodStart
            return if (relative in 0 until periodCount) relative else C.INDEX_UNSET
        }

        override fun getUidOfPeriod(periodIndex: Int): Any =
            delegate.getUidOfPeriod(periodStart + periodIndex)
    }

    companion object {
        private const val QUEUE_WINDOW_SIZE = 50
    }

}
