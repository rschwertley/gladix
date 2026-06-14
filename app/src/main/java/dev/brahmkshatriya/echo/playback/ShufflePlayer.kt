package dev.brahmkshatriya.echo.playback

import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
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
        player.shuffleOrder = ShuffleOrder.UnshuffledShuffleOrder(0)
    }

    private fun getQueue() = (0 until mediaItemCount).map { player.getMediaItemAt(it) }

    private var isShuffled = false
    private var original = getQueue()
    private var isFreshShuffle = false
    internal var isRearranging = false

    // Called by playItem() before setting shuffleModeEnabled=true so changeQueue() knows to
    // place the starting track at physical index 0 with no "before" predecessors. Must be called
    // explicitly rather than inferred from setMediaItems() to avoid false-positives from the
    // cold-start restore paths (resume/onPlaybackResumption/PlayerService.onCreate), which all
    // call setMediaItems AFTER shuffleModeEnabled and must never trigger this behavior.
    internal fun notifyFreshShuffle() {
        isFreshShuffle = true
    }

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

    private fun changeQueue(list: List<MediaItem>) {
        if (list.size <= 1) return
        val freshPlay = isFreshShuffle
        isFreshShuffle = false
        val currentMediaItem = list.first { it.mediaId == currentMediaItem?.mediaId }
        val index = list.indexOf(currentMediaItem)
        val before = if (freshPlay) emptyList() else list.take(index) - currentMediaItem
        val after = if (freshPlay) list - currentMediaItem else list.takeLast(list.size - index) - currentMediaItem
        isRearranging = true
        try {
            // Use player.currentMediaItemIndex directly — NOT getCurrentMediaItemIndex() — to avoid
            // WindowedTimeline offset.
            if (player.currentMediaItemIndex > 0)
                player.removeMediaItems(0, player.currentMediaItemIndex)
            player.addMediaItems(0, before)
            player.removeMediaItems(player.currentMediaItemIndex + 1, mediaItemCount)
            player.addMediaItems(player.currentMediaItemIndex + 1, after)
        } finally {
            isRearranging = false
        }
    }

    fun onMediaItemChanged(old: MediaItem, new: MediaItem) {
        original = original.toMutableList().apply {
            val index = indexOf(old).takeIf { it != -1 } ?: return
            set(index, new)
        }
    }

    override fun addMediaItem(mediaItem: MediaItem) {
        original = original + mediaItem
        player.addMediaItem(mediaItem)
    }

    override fun addMediaItems(mediaItems: MutableList<MediaItem>) {
        original = original + mediaItems
        player.addMediaItems(mediaItems)
    }

    override fun addMediaItem(index: Int, mediaItem: MediaItem) {
        original = original + mediaItem
        player.addMediaItem(index, mediaItem)
    }

    override fun addMediaItems(index: Int, mediaItems: MutableList<MediaItem>) {
        original = original + mediaItems
        player.addMediaItems(index, mediaItems)
    }

    private fun getItemAt(index: Int) = player.getMediaItemAt(index).let {
        original.first { item -> item.mediaId == it.mediaId }
    }

    override fun removeMediaItem(index: Int) {
        original = original - getItemAt(index)
        player.removeMediaItem(index)
    }

    override fun removeMediaItems(fromIndex: Int, toIndex: Int) {
        original =
            original - (fromIndex until toIndex).map { getItemAt(it) }.toSet()
        player.removeMediaItems(fromIndex, toIndex)
    }

    override fun replaceMediaItem(index: Int, mediaItem: MediaItem) {
        original = original.toMutableList().apply {
            val originalIndex = indexOf(getItemAt(index)).takeIf { it != -1 }!!
            set(originalIndex, mediaItem)
        }
        player.replaceMediaItem(index, mediaItem)
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
    }

    override fun setMediaItem(mediaItem: MediaItem) {
        original = listOf(mediaItem)
        player.setMediaItem(mediaItem)
    }

    override fun setMediaItem(mediaItem: MediaItem, resetPosition: Boolean) {
        original = listOf(mediaItem)
        player.setMediaItem(mediaItem, resetPosition)
    }

    override fun setMediaItem(mediaItem: MediaItem, startPositionMs: Long) {
        original = listOf(mediaItem)
        player.setMediaItem(mediaItem, startPositionMs)
    }

    override fun setMediaItems(mediaItems: MutableList<MediaItem>) {
        original = mediaItems
        player.setMediaItems(mediaItems)
    }

    override fun setMediaItems(mediaItems: MutableList<MediaItem>, resetPosition: Boolean) {
        original = mediaItems
        player.setMediaItems(mediaItems, resetPosition)
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
    }

    override fun clearMediaItems() {
        original = emptyList()
        player.clearMediaItems()
    }

    // Reports STATE_READY when the inner player is STATE_IDLE with a queued but unstarted
    // playlist and playWhenReady=false. This gives Media3's PlaybackStateCompat builder
    // STATE_PAUSED(2) so AA shows the thumbnail play button on cold start, without calling
    // prepare() in onCreate() which caused STATE_ENDED at ~88ms before stream load completed.
    // Purely presentational: all Player.Listener callbacks come from real ExoPlayer (listeners
    // are registered on the inner player by ForwardingPlayer.addListener), so no synthetic
    // STATE_READY callback is delivered to PlayerEventListener or AudioFocusListener.
    override fun getPlaybackState(): Int {
        return if (player.playbackState == STATE_IDLE && mediaItemCount > 0 && !playWhenReady)
            STATE_READY
        else
            player.playbackState
    }

    // Auto-prepares before play when inner player is STATE_IDLE (e.g. cold start restore with
    // no prepare() call). Needed because the PlayerViewModel STATE_IDLE guard sees faked
    // STATE_READY and never fires. Both play() and setPlayWhenReady() are overridden because
    // setPlaying() in PlayerViewModel uses the playWhenReady property setter directly, not play().
    override fun play() {
        if (player.playbackState == STATE_IDLE) player.prepare()
        if (player.playbackState == STATE_ENDED) player.seekTo(0, 0)
        super.play()
    }

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (playWhenReady && player.playbackState == STATE_IDLE) player.prepare()
        if (playWhenReady && player.playbackState == STATE_ENDED) player.seekTo(0, 0)
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

    // Returns the current period index relative to the windowed timeline so that Media3's
    // PlayerWrapper.createPositionInfo() assertion (periodIndex < timeline.getPeriodCount()) holds.
    // WindowedTimeline.init computes periodStart as the firstPeriodIndex of the window's first
    // window; we apply the same offset here so the two values are always consistent.
    override fun getCurrentPeriodIndex(): Int {
        val full = super.getCurrentTimeline()
        val count = full.windowCount
        if (count <= QUEUE_WINDOW_SIZE) return player.currentPeriodIndex
        val fullIndex = player.currentMediaItemIndex.coerceAtLeast(0)
        val start = windowStart(count, fullIndex)
        val periodStart = full.getWindow(start, Timeline.Window()).firstPeriodIndex
        return player.currentPeriodIndex - periodStart
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
