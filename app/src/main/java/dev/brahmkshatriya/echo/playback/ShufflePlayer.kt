package dev.brahmkshatriya.echo.playback

import android.os.Handler
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
    // Seam 3 involuntary-exclusion: set true by an error/watchdog auto-skip immediately before its
    // seekToNextMediaItem(). advanceForward consumes it and removes the departing track WITHOUT
    // pushing it to the back-stack, so Previous never replays a dead/skipped-past track.
    internal var suppressPushOnNextAdvance = false

    // Auto-advance trim runs SYNCHRONOUSLY in onInnerMediaItemTransition (mirroring advanceForward). The
    // departed track is BEFORE current, so its removal only shifts indices — current-item identity is
    // invariant — which makes it safe to do inside the transition dispatch AND keeps the queue in lockstep
    // with current (the old deferred trim lagged the full-screen cover by a frame). The "gapless corruption"
    // that once justified deferring was the Tensor G5 offload HAL, now disabled globally; software gapless
    // tolerates a mid-transition edit, so the deferral was protecting a non-problem while causing the cover lag.
    //
    // RECONSTITUTION stays deferred: maybeReconstituteForRepeatAll re-adds the whole back-stack, and that
    // heavy add on a repeat-all wrap is the one mutation we keep OFF the transition flush. It runs one looper
    // cycle later from a quiescent context — timing unchanged from before; only the trim moved to synchronous.
    private var reconstitutionScheduled = false
    private val looperHandler = Handler(player.applicationLooper)
    private val reconstituteRunnable = Runnable { reconstitutionScheduled = false; runReconstitution() }

    private companion object {
        const val BACK_STACK_CAP = 1000
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

    // Set by a start-playback entry point right before the framework applies its returned queue; consumed by
    // the next 3-arg setMediaItems. Non-null = "the queue about to be applied is the AA shuffle tile's
    // PRE-shuffled list; record THIS (the unshuffled album order) as `original` and flip the flag ON." Lets
    // the tile match the phone Shuffle button (toggle-OFF restores album order) race-free — no post-apply
    // looper hop, and no other setMediaItems intervenes between the notify and the framework's apply.
    private var pendingShuffleTileOriginal: List<MediaItem>? = null

    // AA shuffle tile (E): record the unshuffled album order to become `original`, consumed by the ensuing
    // setMediaItems. See pendingShuffleTileOriginal.
    fun notifyShuffleTileOriginal(unshuffled: List<MediaItem>) {
        pendingShuffleTileOriginal = unshuffled
    }

    // Sync the shuffle flag/icon to what a start-playback entry point actually did, WITHOUT physically
    // reordering (no changeQueue). The inner ExoPlayer's shuffleOrder is UnshuffledShuffleOrder (identity), so
    // setting the flag alone never changes advance order — it only fires onShuffleModeEnabledChanged (the icon).
    // isShuffled is kept in lockstep so the next real toggle behaves, and so a queue drag still syncs `original`
    // (moveMediaItem syncs original only when !isShuffled — a stale isShuffled=true would silently skip it).
    fun syncShuffleFlag(enabled: Boolean) {
        isShuffled = enabled
        player.shuffleModeEnabled = enabled
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
        isFreshShuffle = false
        // Degrade to a no-op reorder rather than crash if current isn't in `list` (transient divergence);
        // the same NoSuchElementException family as the getItemAt tap-to-jump crash.
        val currentItem = list.firstOrNull { it.mediaId == currentMediaItem?.mediaId } ?: return
        // Current+upcoming model: current stays at index 0 with NOTHING before it; everything else
        // follows as upcoming. (The old before/after split placed ~half the shuffled tracks above
        // current, stranding them — G3.) The removeMediaItems(0, currentIndex) below also heals any
        // pre-existing stranded-above state by pulling current back to index 0. Uses the inner
        // player.* calls (not the overrides), so `original` and `backStack` are left untouched.
        val after = list - currentItem
        isRearranging = true
        try {
            if (player.currentMediaItemIndex > 0)
                player.removeMediaItems(0, player.currentMediaItemIndex)
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

    // Maps a timeline index to its `original` entry by mediaId. Returns null (not throwing) when the
    // item isn't in `original`: a transient timeline↔original divergence must degrade, never crash the
    // caller — jumpForwardTo's span removal walks this across many upcoming items on a common tap.
    private fun getItemAt(index: Int) = player.getMediaItemAt(index).let {
        original.firstOrNull { item -> item.mediaId == it.mediaId }
    }

    override fun removeMediaItem(index: Int) {
        getItemAt(index)?.let { original = original - it }
        player.removeMediaItem(index)
    }

    override fun removeMediaItems(fromIndex: Int, toIndex: Int) {
        // Remove the departing items from `original` by mediaId with correct MULTIPLICITY, and tolerant
        // of a missing entry (skipped, never throws). The old `original - set.toSet()` collapsed a
        // duplicate track (radio top-up / re-queue → same track.id) to one set element and deleted BOTH
        // `original` copies when only one left the timeline, leaving `original` short → the next
        // getItemAt on the surviving dupe threw NoSuchElementException (the tap-to-jump crash).
        val removedCounts = (fromIndex until toIndex)
            .mapNotNull { runCatching { player.getMediaItemAt(it) }.getOrNull()?.mediaId }
            .groupingBy { it }.eachCount().toMutableMap()
        if (removedCounts.isNotEmpty()) {
            original = original.filter { item ->
                val remaining = removedCounts[item.mediaId] ?: 0
                if (remaining > 0) { removedCounts[item.mediaId] = remaining - 1; false } else true
            }
        }
        player.removeMediaItems(fromIndex, toIndex)
    }

    // Reorder (Seam 2/G2). Previously un-overridden, so a drag desynced `original` from the timeline.
    // Sync `original` ONLY when unshuffled (then original == display order); when shuffled, `original`
    // is the fixed unshuffle reference and a display-order reorder must not rewrite it. The cross-
    // current guard that keeps current at index 0 lives in QueueFragment (movement flags / onMove).
    override fun moveMediaItem(currentIndex: Int, newIndex: Int) {
        val item = if (!isShuffled) getItemAt(currentIndex) else null
        if (item != null) {
            original = original.toMutableList().apply {
                val from = indexOf(item)
                if (from != -1) {
                    removeAt(from)
                    add(newIndex.coerceIn(0, size), item)
                }
            }
        }
        player.moveMediaItem(currentIndex, newIndex)
    }

    override fun replaceMediaItem(index: Int, mediaItem: MediaItem) {
        getItemAt(index)?.let { existing ->
            original = original.toMutableList().apply {
                val originalIndex = indexOf(existing)
                if (originalIndex != -1) set(originalIndex, mediaItem)
            }
        }
        player.replaceMediaItem(index, mediaItem)
    }

    override fun replaceMediaItems(
        fromIndex: Int, toIndex: Int, mediaItems: MutableList<MediaItem>
    ) {
        original = original.toMutableList().apply {
            (fromIndex until toIndex).forEachIndexed { offset, i ->
                val existing = getItemAt(i) ?: return@forEachIndexed
                val originalIndex = indexOf(existing)
                if (originalIndex != -1) set(originalIndex, mediaItems[offset])
            }
        }
        player.replaceMediaItems(fromIndex, toIndex, mediaItems)
    }

    // Seam 3: setMediaItem(s)/clearMediaItems establish a NEW context (new play, cold-start restore,
    // clear-queue), so the play-history back-stack must be wiped — otherwise Previous could pop a
    // track from a prior session's queue. Any pending REPEAT_ALL reconstitution is CANCELLED here for the
    // same reason: it re-adds back-stack items that belong to the OLD queue. These are the only new-context
    // entry points; advance, jump, changeQueue, and reconstitution all use add/remove/move, never set/clear.
    override fun setMediaItem(mediaItem: MediaItem) {
        original = listOf(mediaItem)
        backStack.clear()
        cancelPendingReconstitution()
        player.setMediaItem(mediaItem)
    }

    override fun setMediaItem(mediaItem: MediaItem, resetPosition: Boolean) {
        original = listOf(mediaItem)
        backStack.clear()
        cancelPendingReconstitution()
        player.setMediaItem(mediaItem, resetPosition)
    }

    override fun setMediaItem(mediaItem: MediaItem, startPositionMs: Long) {
        original = listOf(mediaItem)
        backStack.clear()
        cancelPendingReconstitution()
        player.setMediaItem(mediaItem, startPositionMs)
    }

    override fun setMediaItems(mediaItems: MutableList<MediaItem>) {
        original = mediaItems
        backStack.clear()
        cancelPendingReconstitution()
        player.setMediaItems(mediaItems)
    }

    override fun setMediaItems(mediaItems: MutableList<MediaItem>, resetPosition: Boolean) {
        original = mediaItems
        backStack.clear()
        cancelPendingReconstitution()
        player.setMediaItems(mediaItems, resetPosition)
    }

    override fun setMediaItems(
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long
    ) {
        val tileOriginal = pendingShuffleTileOriginal
        pendingShuffleTileOriginal = null
        original = tileOriginal ?: mediaItems
        backStack.clear()
        cancelPendingReconstitution()
        player.setMediaItems(
            mediaItems,
            startIndex.coerceAtMost(mediaItems.size - 1),
            startPositionMs
        )
        if (tileOriginal != null) {
            // AA shuffle tile: the queue was just applied ALREADY shuffled; keep the unshuffled album order as
            // `original` and flip the flag ON without changeQueue (identity shuffleOrder → no reorder). A later
            // toggle-OFF then changeQueue(original) restores album order, matching the phone Shuffle button.
            isShuffled = true
            player.shuffleModeEnabled = true
        }
    }

    override fun clearMediaItems() {
        original = emptyList()
        backStack.clear()
        cancelPendingReconstitution()
        player.clearMediaItems()
    }

    // Teardown: drop any pending reconstitution so it can never run on a released player or bleed into a
    // cold-start restore (the deferred reconstitution is the one thing outliving this transition dispatch).
    override fun release() {
        cancelPendingReconstitution()
        super.release()
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
    // taps here via seekToFullCommand). `play` preserves the play()=true / seek()=false distinction.
    // Delegates to jumpForwardTo, which NO-OPs on an out-of-range index (stale/racing tap can't crash).
    fun seekToFullIndex(fullIndex: Int, play: Boolean) {
        jumpForwardTo(fullIndex, play)
    }

    // Android Auto queue-item tap (onSkipToQueueItem → seekToDefaultPosition(index)) — the AA analogue
    // of the phone's seekToFullIndex, trimmed the same way. seekTo(int, long) is deliberately NOT
    // overridden: resume and shuffle-changeCurrent use it and must never trim. The no-arg
    // seekToDefaultPosition() (used by handlePrevious via the inner player) is also untouched.
    override fun seekToDefaultPosition(mediaItemIndex: Int) {
        jumpForwardTo(mediaItemIndex, play = true)
    }

    // Forward-jump trim (Seam 1): push the departing current to the back-stack, then remove the whole
    // span [current, target) so the tapped track becomes index 0 and onTimelineChanged re-publishes the
    // AA queue at current. The skipped span is DISCARDED (removed, not pushed) — jumped-over tracks were
    // never played, consistent with the involuntary-skip exclusion. Command-level only (phone seekToFull
    // + AA seekToDefaultPosition), never the transition SEEK branch, so resume/radio/scrub don't misfire.
    private fun jumpForwardTo(targetIndex: Int, play: Boolean) {
        if (targetIndex < 0 || targetIndex >= mediaItemCount) return   // out of range → no-op (stale tap)
        val current = player.currentMediaItemIndex
        if (targetIndex <= current) {                                  // not a forward jump → plain seek
            super.seekTo(targetIndex, 0)
            if (play) playWhenReady = true
            return
        }
        val departing = player.currentMediaItem
        isNavigating = true
        try {
            if (departing != null) pushToBackStack(departing)
            removeMediaItems(current, targetIndex)   // removes current + skipped span; target → index `current`
            lastCurrentItem = player.currentMediaItem
            maybeReconstituteForRepeatAll()
        } finally {
            isNavigating = false
        }
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
                // Trim the departed track SYNCHRONOUSLY, mirroring advanceForward. It is BEFORE current, so
                // the removal only shifts indices — current-item identity is invariant — so no listener later
                // in this flush can read a wrong current (the UI binds art from the emitted current identity,
                // not a mid-flush re-query). This keeps the queue in lockstep with current, fixing the
                // one-behind cover on auto-advance. isNavigating-guarded so the removal's own timeline
                // callback (behind-current: no media-item transition) cannot re-enter this handler.
                isNavigating = true
                try {
                    pushToBackStack(departing)
                    removeByMediaId(departing.mediaId)
                } finally {
                    isNavigating = false
                }
                // Reconstitution (heavy back-stack re-add) stays deferred off this dispatch.
                scheduleReconstitution()
            }
        }
        lastCurrentItem = newItem
    }

    // Runs the deferred REPEAT_ALL reconstitution from a quiescent context. Guarded so a re-entrant or
    // in-navigation call is a no-op — this is what lets advanceForward/handlePrevious settle it synchronously
    // without clobbering an in-progress navigation's isNavigating.
    private fun runReconstitution() {
        if (isNavigating) return
        isNavigating = true
        try {
            maybeReconstituteForRepeatAll()   // Seam 4: refill the loop as we reach the last track
        } finally {
            isNavigating = false
        }
    }

    private fun scheduleReconstitution() {
        if (reconstitutionScheduled) return
        reconstitutionScheduled = true
        looperHandler.post(reconstituteRunnable)
    }

    // Settle a still-pending reconstitution NOW (before a manual Next/Previous acts) so navigation sees a
    // settled queue, then cancel the posted runnable so it cannot double-fire.
    private fun settlePendingReconstitution() {
        if (!reconstitutionScheduled) return
        reconstitutionScheduled = false
        looperHandler.removeCallbacks(reconstituteRunnable)
        runReconstitution()
    }

    // New-context reset (setMediaItems/clear/release): cancel any pending reconstitution so it can't run
    // against the new queue or a released player. The back-stack it reads is cleared alongside each call.
    private fun cancelPendingReconstitution() {
        reconstitutionScheduled = false
        looperHandler.removeCallbacks(reconstituteRunnable)
    }

    // Genuine forward advance (Next button / COMMAND_SEEK_TO_NEXT[_MEDIA_ITEM]). Captures the
    // departing item BEFORE delegating, advances the inner player, then pushes it to the back-stack
    // and removes it from the live timeline — all synchronously, so rapid Next-mashing can neither
    // lose nor duplicate a departing item (each press owns exactly one). Guarded so a synchronously-
    // delivered transition callback from the removal cannot re-enter.
    override fun seekToNextMediaItem() = advanceForward { player.seekToNextMediaItem() }
    override fun seekToNext() = advanceForward { player.seekToNext() }

    private fun advanceForward(doSeek: () -> Unit) {
        // Auto-advance trims are synchronous now, so the back-stack is already settled here. Settle any
        // still-pending REPEAT_ALL reconstitution FIRST so we act on a fully settled queue — including the
        // synchronous involuntary-skip path (onPlayerError → skipInvoluntarily) that can reach this mid-defer.
        settlePendingReconstitution()
        if (isNavigating) {
            doSeek()
            return
        }
        val skipHistory = suppressPushOnNextAdvance   // Seam 3: consume the involuntary-skip flag
        suppressPushOnNextAdvance = false
        val departing = player.currentMediaItem
        isNavigating = true
        try {
            doSeek()
            val nowCurrent = player.currentMediaItem
            if (departing != null && nowCurrent?.mediaId != departing.mediaId) {
                if (skipHistory) removeByMediaId(departing.mediaId)   // involuntary: remove, don't push
                else pushAndRemove(departing)
            }
            lastCurrentItem = player.currentMediaItem
            maybeReconstituteForRepeatAll()   // Seam 4
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
        // Auto-advance trims are synchronous, so the just-departed track is already in the backStack. Settle
        // any pending REPEAT_ALL reconstitution first so we pop against a settled queue.
        settlePendingReconstitution()
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

    // Shared primitives. pushToBackStack: capped play-history push (Seam 1 jump, advance).
    // removeByMediaId: remove-without-push from the timeline (Seam 1 skipped span, Seam 3 involuntary).
    // Removal goes through the removeMediaItem override so `original` stays in sync. The removed item
    // is never the current one, so its removal emits no media-item transition — only a timeline
    // change — which is why push↔remove cannot re-trigger a push.
    private fun pushToBackStack(item: MediaItem) {
        backStack.addLast(item)
        while (backStack.size > BACK_STACK_CAP) backStack.removeFirst()
    }

    private fun removeByMediaId(mediaId: String) {
        val idx = (0 until mediaItemCount)
            .firstOrNull { player.getMediaItemAt(it).mediaId == mediaId }
        if (idx != null) removeMediaItem(idx)
    }

    private fun pushAndRemove(item: MediaItem) {
        pushToBackStack(item)
        removeByMediaId(item.mediaId)
    }

    // Seam 4 — REPEAT_ALL reconstitution. maybeReconstituteForRepeatAll fires when we've reached the
    // last track (no upcoming) under REPEAT_ALL with history to loop; called after every advance/jump
    // and on setRepeatMode. reconstituteFromBackStack drains the play-history back into the timeline
    // as upcoming (via the addMediaItems override, which re-syncs `original`), restoring the full set
    // so it loops. Cap-100 means queues >100 lose their earliest tracks from the loop.
    private fun maybeReconstituteForRepeatAll() {
        if (repeatMode == Player.REPEAT_MODE_ALL &&
            currentMediaItemIndex >= mediaItemCount - 1 &&
            backStack.isNotEmpty()
        ) reconstituteFromBackStack()
    }

    private fun reconstituteFromBackStack() {
        if (backStack.isEmpty()) return
        val items = backStack.toMutableList()
        backStack.clear()
        addMediaItems(mediaItemCount, items)
    }

    override fun setRepeatMode(repeatMode: Int) {
        super.setRepeatMode(repeatMode)
        // Handles toggling INTO all while already on the last track (loop instead of repeat-one);
        // mid-queue toggles reconstitute later via the advance-time check.
        maybeReconstituteForRepeatAll()
    }

}
