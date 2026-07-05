package dev.brahmkshatriya.echo.playback

import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder
import java.util.concurrent.CopyOnWriteArrayList

@Suppress("unused")
@OptIn(UnstableApi::class)
class ShufflePlayer(
    private val player: ExoPlayer,
    // Lever B: maps the raw player error to a friendly, categorized one for the media session
    // (Android Auto). Null = passthrough (identity), so existing callers/tests are unaffected.
    private val errorMapper: ((PlaybackException) -> PlaybackException)? = null,
) : ForwardingPlayer(player) {

    init {
        player.shuffleOrder = ShuffleOrder.UnshuffledShuffleOrder(0)
    }

    private fun getQueue() = (0 until mediaItemCount).map { player.getMediaItemAt(it) }

    private var isShuffled = false
    private var original = getQueue()
    private var isFreshShuffle = false
    internal var isRearranging = false

    private val extraListeners = CopyOnWriteArrayList<Player.Listener>()

    // The sliding-window position, kept STATEFUL (recentered only near an edge — see windowStart())
    // so a >50 AA queue no longer re-serializes on every track. storedWindowStart is the single
    // source of truth every windowStart() consumer reads; lastSerializedWindowStart tracks the
    // window AA currently holds, so notifyWindowedTimelineChanged() only re-fires on an actual shift.
    private var storedWindowStart = -1
    private var lastSerializedWindowStart = -1

    // A queue REPLACE (setMediaItem(s)/clearMediaItems) invalidates the window: the next
    // windowStart() read recenters on the new current and notifyWindowedTimelineChanged() re-fires.
    // Mutates (add/remove/replace/move/changeQueue) deliberately do NOT call this — the window must
    // persist across normal edits, or the per-edit churn we're removing comes back.
    private fun resetWindow() {
        storedWindowStart = -1
        lastSerializedWindowStart = -1
    }

    override fun addListener(listener: Player.Listener) {
        extraListeners.add(listener)
        super.addListener(listener)
    }

    override fun removeListener(listener: Player.Listener) {
        extraListeners.remove(listener)
        super.removeListener(listener)
    }

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
        resetWindow()
        original = listOf(mediaItem)
        player.setMediaItem(mediaItem)
    }

    override fun setMediaItem(mediaItem: MediaItem, resetPosition: Boolean) {
        resetWindow()
        original = listOf(mediaItem)
        player.setMediaItem(mediaItem, resetPosition)
    }

    override fun setMediaItem(mediaItem: MediaItem, startPositionMs: Long) {
        resetWindow()
        original = listOf(mediaItem)
        player.setMediaItem(mediaItem, startPositionMs)
    }

    override fun setMediaItems(mediaItems: MutableList<MediaItem>) {
        resetWindow()
        original = mediaItems
        player.setMediaItems(mediaItems)
    }

    override fun setMediaItems(mediaItems: MutableList<MediaItem>, resetPosition: Boolean) {
        resetWindow()
        original = mediaItems
        player.setMediaItems(mediaItems, resetPosition)
    }

    override fun setMediaItems(
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long
    ) {
        resetWindow()
        original = mediaItems
        player.setMediaItems(
            mediaItems,
            startIndex.coerceAtMost(mediaItems.size - 1),
            startPositionMs
        )
    }

    override fun clearMediaItems() {
        resetWindow()
        original = emptyList()
        player.clearMediaItems()
    }

    // Lever B: re-message the raw player error into a friendly, categorized PlaybackException for
    // the media session (Android Auto reads getPlayerError() via LegacyConversions to build its
    // STATE_ERROR tile). The phone snackbar path (throwableFlow / ExceptionUtils) is untouched and
    // still sees the raw exception. Identity-keyed cache: a stable error maps exactly once, and the
    // moment the inner error clears to null (e.g. prepare() on tap-to-retry) the cache resets and we
    // return null — which is what makes the AA error tile disappear cleanly instead of lingering.
    // Called on the session's application looper thread, same as the other state below; plain vars.
    private var cachedErrorOriginal: PlaybackException? = null
    private var cachedErrorMapped: PlaybackException? = null
    override fun getPlayerError(): PlaybackException? {
        val inner = super.getPlayerError()
        if (inner == null) {
            cachedErrorOriginal = null
            cachedErrorMapped = null
            return null
        }
        val mapper = errorMapper ?: return inner
        if (inner !== cachedErrorOriginal) {
            cachedErrorOriginal = inner
            cachedErrorMapped = mapper(inner)
        }
        return cachedErrorMapped
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

    // Single source of truth for the window position, read by ALL consumers (getCurrentTimeline,
    // getCurrentMediaItemIndex, getCurrentPeriodIndex, notifyWindowedTimelineChanged, and the
    // seekTo/seekToDefaultPosition overrides). Stateful hysteresis: keep the stored window until
    // the current comes within EDGE_MARGIN of a MOVABLE edge (or falls outside it / the queue
    // shrank), then recenter on the current. Recentering here, on read, guarantees the current is
    // always inside [return, return + QUEUE_WINDOW_SIZE), so every windowed index/period derived
    // from it stays in-window (both PlayerInfo assertions hold, with no stale-window transient).
    private fun windowStart(fullCount: Int, fullIndex: Int): Int {
        val maxStart = fullCount - QUEUE_WINDOW_SIZE
        val s = storedWindowStart
        val recenter = s < 0 || s > maxStart ||
            fullIndex < s || fullIndex >= s + QUEUE_WINDOW_SIZE ||
            (fullIndex < s + EDGE_MARGIN && s > 0) ||
            (fullIndex >= s + QUEUE_WINDOW_SIZE - EDGE_MARGIN && s < maxStart)
        if (recenter) storedWindowStart = (fullIndex - QUEUE_WINDOW_SIZE / 2).coerceIn(0, maxStart)
        return storedWindowStart
    }

    // Called from PlayerEventListener.onMediaItemTransition (SEEK and AUTO reasons) to notify
    // all session listeners that the windowed timeline has shifted. ExoPlayer does not fire
    // EVENT_TIMELINE_CHANGED on a seek, so MediaSessionLegacyStub.updateQueue() would never
    // run without this explicit notification — leaving Android Auto's queue stale.
    fun notifyWindowedTimelineChanged() {
        val full = super.getCurrentTimeline()
        val count = full.windowCount
        if (count <= QUEUE_WINDOW_SIZE) return
        val fullIndex = player.currentMediaItemIndex.coerceAtLeast(0)
        windowStart(count, fullIndex) // recenter-on-read; updates storedWindowStart if it shifted
        if (storedWindowStart == lastSerializedWindowStart) return
        lastSerializedWindowStart = storedWindowStart
        val timeline = getCurrentTimeline()
        for (l in extraListeners) {
            l.onTimelineChanged(timeline, Player.TIMELINE_CHANGE_REASON_SOURCE_UPDATE)
        }
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

    // The un-windowed current index into the FULL ExoPlayer timeline. getCurrentMediaItemIndex()
    // above is deliberately WINDOWED for the media session (Media3 asserts it against the windowed
    // timeline's windowCount), so persistence and any full-timeline logic must read THIS — never
    // getCurrentMediaItemIndex(). Reads the inner player directly (the same value changeQueue /
    // windowStart use internally); a pure read that touches no session/AA state.
    val fullCurrentIndex: Int get() = player.currentMediaItemIndex

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

    // Inverse of getCurrentMediaItemIndex(): callers (UI queue taps, error-retry logic in
    // PlayerEventListener) read currentMediaItemIndex, which is windowed once the queue exceeds
    // QUEUE_WINDOW_SIZE. Without this override, seekTo() would forward that windowed index
    // straight to the real player as if it were a full-timeline index, landing on the wrong
    // track for any queue position outside the window's first QUEUE_WINDOW_SIZE entries.
    override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
        val full = super.getCurrentTimeline()
        val count = full.windowCount
        if (count <= QUEUE_WINDOW_SIZE) {
            super.seekTo(mediaItemIndex, positionMs)
            return
        }
        val fullIndex = player.currentMediaItemIndex.coerceAtLeast(0)
        val start = windowStart(count, fullIndex)
        super.seekTo(mediaItemIndex + start, positionMs)
    }

    // Twin of seekTo() above, for Android Auto queue selection. Media3's onSkipToQueueItem routes
    // to PlayerWrapper.seekToDefaultPosition((int) queueId), where queueId is the index into the
    // WINDOWED timeline we serialize — bypassing the seekTo() override entirely. Without this, a
    // deep AA queue selection forwards the windowed index straight to the real player as a
    // full-timeline index, landing on the wrong track beyond the window's first QUEUE_WINDOW_SIZE
    // entries. AA-selection-only: the app never calls seekToDefaultPosition() itself.
    override fun seekToDefaultPosition(mediaItemIndex: Int) {
        val full = super.getCurrentTimeline()
        val count = full.windowCount
        if (count <= QUEUE_WINDOW_SIZE) {
            super.seekToDefaultPosition(mediaItemIndex)
            return
        }
        val fullIndex = player.currentMediaItemIndex.coerceAtLeast(0)
        val start = windowStart(count, fullIndex)
        super.seekToDefaultPosition(mediaItemIndex + start)
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

        // How near a movable window edge the current must come before the window recenters. Larger =
        // more upcoming-track lookahead but more frequent recenters; smaller = rarer recenters (fewer
        // AA scroll resets) but less edge lookahead. Recenter cadence ≈ QUEUE_WINDOW_SIZE/2 − EDGE_MARGIN tracks.
        private const val EDGE_MARGIN = 10
    }

}
