package dev.brahmkshatriya.echo.playback

import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder

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
        // Auto-advance (track ended → next plays itself) has no public entry point to intercept,
        // so it is the ONE forward-advance source handled via a callback. Registered on the inner
        // player because that is where ForwardingPlayer routes all real Player.Listener callbacks.
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                onInnerMediaItemTransition(mediaItem, reason)
            }
        })
    }

    private fun getQueue() = (0 until mediaItemCount).map { player.getMediaItemAt(it) }

    private var isShuffled = false
    private var original = getQueue()
    private var isFreshShuffle = false
    internal var isRearranging = false

    // ── Session-only Previous prototype (in-memory play-history back-stack) ─────────────────
    // Ordered play-history of tracks advanced PAST (via Next or auto-advance) and removed from the
    // live timeline. Newest at the end (top). Session-only: NOT persisted — lost on process death,
    // after which Previous falls back to restart-current. Capped to bound memory.
    private val backStack = ArrayDeque<MediaItem>()
    // The item that was current before the latest transition. An auto-advance callback reports only
    // the NEW item, so this is how we know which item departed and must be pushed + removed.
    private var lastCurrentItem: MediaItem? = player.currentMediaItem
    // Re-entrancy guard: true while WE mutate the timeline for a Next/Previous navigation, so a
    // transition callback synchronously triggered by that mutation cannot recursively re-process it.
    // Push and pop are each done synchronously on the application looper; this bounds their atomicity.
    private var isNavigating = false

    private companion object {
        const val BACK_STACK_CAP = 100
        const val PREVIOUS_RESTART_THRESHOLD_MS = 3000L
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

    // CrossfadePlayer must override setAudioAttributes() to broadcast to both internal players.
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

    // Seek by FULL-timeline index — the play/seek path from the phone queue (PlayerViewModel routes
    // taps here via seekToFullCommand). Seeks the real player directly, and NO-OPs (not clamp) if the
    // index is out of range, so a stale/racing tap index can never crash or jump to the wrong track.
    // (This guard is why we keep the custom command instead of the raw controller seekTo, which would
    // throw on an out-of-range index.) `play` preserves the play()=true / seek()=false distinction.
    fun seekToFullIndex(fullIndex: Int, play: Boolean) {
        if (fullIndex < 0 || fullIndex >= super.getCurrentTimeline().windowCount) return
        super.seekTo(fullIndex, 0)
        if (play) playWhenReady = true
    }

    // ── Session-only Previous state machine ─────────────────────────────────────────────────
    // Handles ONLY the AUTO transition (a track ended and the next one started by itself). Every
    // user-driven Next flows through seekToNext()/seekToNextMediaItem() and is handled there,
    // synchronously. A SEEK transition (Next, Previous, or a queue tap) is deliberately ignored here
    // so each forward-advance source pushes in exactly one place and the two halves never double-fire.
    // A queue tap (SEEK, not routed through Next) correctly does NOT push — only sequential advance does.
    private fun onInnerMediaItemTransition(newItem: MediaItem?, reason: Int) {
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && !isNavigating && !isRearranging) {
            val departing = lastCurrentItem
            if (departing != null && departing.mediaId != newItem?.mediaId) {
                isNavigating = true
                try {
                    pushAndRemove(departing)
                } finally {
                    isNavigating = false
                }
            }
        }
        lastCurrentItem = newItem
    }

    // Genuine forward advance (Next button / COMMAND_SEEK_TO_NEXT[_MEDIA_ITEM]). Captures the
    // departing item BEFORE delegating, advances the inner player, then pushes it to the back-stack
    // and removes it from the live timeline — all synchronously, so rapid Next-mashing can neither
    // lose nor duplicate a departing item (each press owns exactly one). Guarded so a synchronously-
    // delivered transition callback from the removal cannot re-enter.
    override fun seekToNextMediaItem() = advanceForward { player.seekToNextMediaItem() }
    override fun seekToNext() = advanceForward { player.seekToNext() }

    private fun advanceForward(doSeek: () -> Unit) {
        if (isNavigating) {
            doSeek()
            return
        }
        val departing = player.currentMediaItem
        isNavigating = true
        try {
            doSeek()
            val nowCurrent = player.currentMediaItem
            if (departing != null && nowCurrent?.mediaId != departing.mediaId) {
                pushAndRemove(departing)
            }
            lastCurrentItem = player.currentMediaItem
        } finally {
            isNavigating = false
        }
    }

    // Apple-Music Previous: within the first 3s of the current track, restart it; otherwise pop the
    // most-recently-played track off the back-stack, re-insert it at the front of the live timeline
    // (the addMediaItem override also re-adds it to `original`), and make it current from the start.
    // Empty stack (nothing played yet, or lost to a restart) → restart current. Previous never pushes:
    // the track we leave stays in the timeline as the immediate up-next, so a following Next returns to it.
    override fun seekToPreviousMediaItem() = handlePrevious()
    override fun seekToPrevious() = handlePrevious()

    private fun handlePrevious() {
        if (player.currentPosition > PREVIOUS_RESTART_THRESHOLD_MS) {
            player.seekToDefaultPosition()
            return
        }
        val item = backStack.removeLastOrNull() ?: run {
            player.seekToDefaultPosition()
            return
        }
        isNavigating = true
        try {
            addMediaItem(0, item)          // override: re-adds to `original` + inserts at timeline index 0
            player.seekToDefaultPosition(0)
            lastCurrentItem = item
        } finally {
            isNavigating = false
        }
    }

    // Push the departed item to the back-stack (capped, dropping the oldest) and remove it from the
    // live timeline by mediaId. Removal goes through the removeMediaItem override so `original` stays
    // in sync. The departed item is never the current one, so its removal emits no media-item
    // transition — only a timeline change — which is why push↔remove cannot re-trigger a push.
    private fun pushAndRemove(item: MediaItem) {
        backStack.addLast(item)
        while (backStack.size > BACK_STACK_CAP) backStack.removeFirst()
        val idx = (0 until mediaItemCount)
            .firstOrNull { player.getMediaItemAt(it).mediaId == item.mediaId }
        if (idx != null) removeMediaItem(idx)
    }

}
