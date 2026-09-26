package dev.brahmkshatriya.echo.ui.player

import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.doOnLayout
import androidx.core.view.updateLayoutParams
import androidx.media3.common.MediaItem
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.databinding.ItemClickPanelsBinding
import dev.brahmkshatriya.echo.databinding.ItemPlayerTrackBinding
import dev.brahmkshatriya.echo.playback.MediaItemUtils.track
import dev.brahmkshatriya.echo.playback.MediaItemUtils.unloadedCover
import dev.brahmkshatriya.echo.playback.PlayerState
import dev.brahmkshatriya.echo.ui.common.UiViewModel
import dev.brahmkshatriya.echo.ui.common.UiViewModel.Companion.applyHorizontalInsets
import dev.brahmkshatriya.echo.ui.common.UiViewModel.Companion.applyInsets
import dev.brahmkshatriya.echo.ui.player.PlayerColors.Companion.defaultPlayerColors
import dev.brahmkshatriya.echo.utils.image.ImageUtils.artKey
import dev.brahmkshatriya.echo.utils.image.ImageUtils.getCachedDrawable
import dev.brahmkshatriya.echo.utils.image.ImageUtils.loadWithThumb
import dev.brahmkshatriya.echo.utils.ui.GestureListener
import dev.brahmkshatriya.echo.utils.ui.GestureListener.Companion.handleGestures
import dev.brahmkshatriya.echo.utils.ui.UiUtils.dpToPx
import dev.brahmkshatriya.echo.utils.ui.UiUtils.isLandscape
import dev.brahmkshatriya.echo.utils.ui.UiUtils.isRTL
import dev.brahmkshatriya.echo.utils.ui.scrolling.ScrollAnimViewHolder
import kotlin.math.max
import kotlinx.coroutines.flow.MutableStateFlow

class PlayerTrackAdapter(
    private val uiViewModel: UiViewModel,
    private val current: MutableStateFlow<PlayerState.Current?>,
    private val listener: Listener
) : ListAdapter<MediaItem, PlayerTrackAdapter.ViewHolder>(DiffCallback) {

    interface Listener {
        fun onClick()
        fun onLongClick() {}
        fun onStartDoubleClick() {}
        fun onEndDoubleClick() {}
    }

    object DiffCallback : DiffUtil.ItemCallback<MediaItem>() {
        override fun areItemsTheSame(oldItem: MediaItem, newItem: MediaItem) =
            oldItem.mediaId == newItem.mediaId

        override fun areContentsTheSame(oldItem: MediaItem, newItem: MediaItem): Boolean {
            return oldItem == newItem
        }
    }

    inner class ViewHolder(
        private val binding: ItemPlayerTrackBinding
    ) : ScrollAnimViewHolder(binding.root) {

        private val context = binding.root.context

        private val collapsedPadding = 8.dpToPx(context)
        private val targetZ = collapsedPadding.toFloat()
        private val size = binding.root.resources.getDimension(R.dimen.collapsed_cover_size).toInt()
        private var targetScale = 0f
        private var targetX = 0
        private var targetY = 0

        private val cover = binding.playerTrackCoverContainer
        private var currentCoverHeight = size
        private var currCoverRound = 0f
        private val isLandscape = context.isLandscape()
        fun updateCollapsed() = uiViewModel.run {
            // Hoisted above targetPosY so that line and the branch further down test the SAME value.
            // Reading playerSheetOffset separately at each site would let them disagree: `observe` collects
            // a CONFLATED StateFlow off a coroutine resumption, not synchronously inside onSlide's frame.
            val playerFullyExpanded = playerSheetOffset.value >= 1f
            val insets = if (!isLandscape) systemInsets.value else getCombined()
            // Full-width mini-bar: land the morphed cover flush at insets.start (matching the overlay
            // collapsedTrackCover), NOT collapsedPadding+insets.start — otherwise the two covers sit
            // 8dp apart and the duplicate shows. Both now stack at the same flush position.
            val targetPosX = if (context.isRTL()) insets.end else insets.start
            // Same conjunct as the branch below, and for the same reason: on a collapse drag
            // playerSheetState still reads EXPANDED for the whole gesture, so keying on state alone aimed
            // the cover at the EXPANDED landing point (collapsedPadding + status bar) for every frame and
            // then SNAPPED it to 0 by roughly that distance once the flow settled to COLLAPSED. Drag-up,
            // starting from COLLAPSED, always aimed at 0 - the jump was collapse-only, and it survived the
            // e0a3e5df/f2b661b0 fix because that only reached the offset branch, not this line.
            //
            // ⚠️ NOT DEAD CODE. Keying reachability on the player sheet alone concludes the EXPANDED arm is
            // unused, because targetY is multiplied by `offset` below and offset is 0 at the expanded rest.
            // It is reachable via UP NEXT: dragging that sheet open over a fully expanded player leaves
            // playerSheetOffset at 1f (so the conjunct holds) while offset becomes moreSheetOffset > 0, and
            // this value multiplies through. The conjunct IS the discrimination between "Up Next moving over
            // a fully expanded player" (keep this arm) and "the player sheet itself being dragged" (take the
            // else arm). Do not collapse it to a constant 0.
            val targetPosY = if (playerSheetState.value != STATE_EXPANDED || !playerFullyExpanded) 0
            else collapsedPadding + systemInsets.value.top
            targetX = targetPosX - cover.left
            targetY = targetPosY - cover.top
            currentCoverHeight = cover.height.takeIf { it > 0 } ?: currentCoverHeight
            targetScale = size.toFloat() / currentCoverHeight

            // The `playerSheetOffset >= 1f` conjunct is LOAD-BEARING, not defensive. playerSheetState is a
            // SETTLED-STATES-ONLY signal: UiViewModel gates its write with `if (!isFinalState(newState))
            // return`, so DRAGGING/SETTLING never reach it and the value still reads EXPANDED for the whole
            // of a collapse drag. Keying the branch on state alone therefore sent every frame of that drag
            // into the EXPANDED arm, where `offset` comes from moreSheetOffset (0, Up Next closed) and
            // playerSheetOffset is not read at all — a constant. updateCollapsed() still ran per frame
            // (observe(playerSheetOffset) fires) but recomputed the SAME transform, so the cover did not
            // morph while BottomSheetBehavior kept translating the sheet: the whole page slid instead.
            // Expanding was unaffected because that drag starts from COLLAPSED and was already on the else
            // arm. That directional break arrived with the state-gate in 1bff9b02 (2026-07-16); the geometry
            // below is unchanged since 4be12e0d (2026-07-01) and was never at fault.
            //
            // Reading playerSheetOffset makes the test "is the player sheet ACTUALLY fully expanded" rather
            // than "did it last settle there". Exactness is guaranteed, not hoped for: onStateChanged forces
            // onSlide(view, if (EXPANDED) 1f else 0f) on every settle, so the resting value is exactly 1f.
            // `>=` covers fling overshoot; max(0f, …) below still covers the negative HIDDEN direction.
            //
            // Up Next is NOT affected. It is a separate BottomSheetBehavior (setupPlayerMoreBehavior) whose
            // callback writes moreSheetOffset alone. With it open the player rests expanded (offset exactly
            // 1f, state EXPANDED), so this arm stays selected; dragging its top bar down to dismiss moves
            // moreSheetOffset only, and that gesture tracks frame by frame exactly as before. There is no
            // reachable case of the player sheet being dragged below expanded while Up Next is open, which
            // is why this needs no max(moreOffset, 1 - slide) blend.
            //
            // Deliberately NOT fixed by un-gating playerSheetState: republishing DRAGGING/SETTLING would
            // reach every other observer of that flow — PlayerFragment's bgImage.pause(), the
            // emit(playerBgVisible, false) on COLLAPSED and the applyPlayer() re-run are all unguarded —
            // and would undo 1bff9b02's setState crash fix. One consumer was wrong; one consumer is fixed.
            // (playerFullyExpanded is hoisted to the top of this function - targetPosY needs it too.)
            val (collapsedY, offset) =
                if (playerSheetState.value == STATE_EXPANDED && playerFullyExpanded)
                    systemInsets.value.top to if (isLandscape) 0f else moreSheetOffset.value
                else -collapsedPadding to 1 - max(0f, playerSheetOffset.value)

            val inv = 1 - offset
            // ☠️ INERT - see the note on item_player_collapsed.xml's root. That subtree's only content
            // (collapsedPlayerInfo) is hardcoded android:visibility="invisible" and NOTHING un-hides it:
            // `collapsedPlayerInfo` appears exactly once in the whole tree, as its own id declaration. So
            // this alpha paints nothing at any value. The LIVE mini-bar is item_player_collapsed_controls
            // in the fragment overlay, animated by PlayerFragment.updateCollapsed. Changing these two
            // lines has no visible effect; do not chase a morph bug through here.
            binding.playerCollapsed.root.run {
                translationY = collapsedY - size * inv * 2
                alpha = offset
            }
            if (isLandscape) binding.clickPanel.root.scaleX = 0.5f + 0.5f * inv
            val extraY = if (!isPlayerVisible) 0f else {
                val toMoveY = binding.playerControlsPlaceholder.top - cover.top
                toMoveY * inv
            }
            val extraX = if (!isPlayerVisible) 0f else {
                val toMoveX = binding.playerControlsPlaceholder.left - cover.left
                toMoveX * inv
            }
            cover.run {
                scaleX = if (!isPlayerVisible) 1 + (targetScale - 1) * offset else targetScale
                scaleY = scaleX
                translationX = targetX * offset + extraX
                translationY = targetY * offset + extraY
                translationZ = targetZ * (1 - offset)
                currCoverRound = collapsedPadding / scaleX
                invalidateOutline()
            }
        }

        fun updateInsets() = uiViewModel.run {
            val (v, h) = if (!isLandscape) 64 to 0 else 0 to 24
            binding.constraintLayout.applyInsets(systemInsets.value, v, h)
            val insets = if (isLandscape) getCombined() else systemInsets.value
            // ☠️ INERT - permanently invisible subtree, see item_player_collapsed.xml's root note.
            binding.playerCollapsed.root.applyHorizontalInsets(insets)
            binding.playerControlsPlaceholder.run {
                updateLayoutParams {
                    height = playerControlsHeight.value
                }
                doOnLayout {
                    updateCollapsed()
                    cover.doOnLayout { updateCollapsed() }
                }
            }

            updateCollapsed()
        }

        fun updateColors() {
            // ☠️ INERT - these TextViews are inside the permanently invisible collapsedPlayerInfo. The
            // colours that reach the screen are set on item_player_collapsed_controls in PlayerFragment.
            // See item_player_collapsed.xml's root note.
            binding.playerCollapsed.run {
                val colors = uiViewModel.playerColors.value ?: context.defaultPlayerColors()
                collapsedTrackTitle.setTextColor(colors.onBackground)
                collapsedTrackArtist.setTextColor(colors.onBackground)
            }
        }

        // Set ONLY by a real Coil delivery (loadWithThumb's onDelivered). This is the retry guard, so it
        // must never be satisfied by a cached thumbnail or a placeholder: it previously took its value
        // from the callback that also fires for the synchronous pre-set, so a disk-cached thumb marked
        // the cover "loaded" and disarmed retryLoad for the life of the ViewHolder — defeating the one
        // path written to recover a load that never arrived.
        private var coverDrawable: Drawable? = null

        // What is currently PAINTED on the ImageView, thumbnail included. Split out from coverDrawable
        // so the dynamic-colour listener keeps seeing every paint (its previous behaviour) while the
        // retry guard above sees only real deliveries. Feeding it coverDrawable instead would blank the
        // player colours on every thumb-only paint.
        private var paintedDrawable: Drawable? = null

        // Set ONLY on a terminal outcome, for the same reason. Assigning it before the async load meant a
        // load that never delivered still marked the track bound, so no later rebind of the same track
        // could re-enter the cover block and re-run the synchronous pre-set.
        private var lastBoundMediaId: String? = null

        // ═══ WHAT THE 2026-08-24 FIX DID AND DID NOT DO - read before trusting the guards ═══
        // It fixed the SEALING, not the failure. Two bookkeeping defects made a failed cover load
        // permanent: coverDrawable was set from the SYNCHRONOUS thumbnail (disarming retryLoad's guard
        // with a placeholder) and lastBoundMediaId was recorded BEFORE completion (so no rebind could
        // re-issue). Fixing both made the recovery paths reachable again.
        // It never established WHY the load fails, and it never guarded the PAINT. onDelivered below is
        // guarded; the paint callbacks were not, until 2026-08-29. Do not read the guarded onDelivered and
        // assume the pixels are covered - they are separate callbacks with separate guards.
        // The failure mode that survived: a load that DELIVERS CORRECTLY, then a stale delivery for a
        // previous track repaints the same recycled ImageView afterwards. The bookkeeping is then
        // CORRECT - coverDrawable and lastBoundMediaId both refer to the right track - so every recovery
        // path is correctly disarmed while the pixels are wrong, permanently, until something rebinds.
        // That is why guarding the paint matters and why the sealing fix alone could not have caught it.
        //
        // ═══ WHAT BUILD 1059 ESTABLISHED - the overwrite family is REFUTED ═══
        // The paint guard below and a resume-time belt (forceReloadCover / refreshCovers, re-issuing the
        // cover load unconditionally from PlayerFragment.onResume) were written as the two competing
        // remedies for the two candidate mechanisms, and were meant to ship one at a time so the result
        // would attribute. They did not: BOTH landed in f2b661b0 (build 1057) and both were live in 1058
        // and 1059. Screen-off natural timeout, auto-advance, wake on 1059 still produced a cover exactly
        // one track behind, with title, artist and the mini bar all correct.
        // So the pair is refuted together, and each half separately:
        //   * suppressing a stale paint does not cure it -> the failure is NOT a superseded delivery
        //     overwriting a correct one, which was the whole overwrite hypothesis;
        //   * re-issuing the load at ON_START does not cure it either, and the belt ran at FULL WIDTH
        //     (every attached holder, three decodes per wake) for two generations before removal.
        // The live family is therefore: the CORRECT load does not deliver while the screen is dark.
        // The strongest evidence for it predates both and still stands - the GONE test, where an
        // art_probe ImageView that was never visible still issued its Coil request and the art came back
        // correct after five dark advances. The cure was the REQUEST, not the pixels. Why that request
        // worked where forceReloadCover's did not is the open question, and it is a difference between
        // the two call sites, not between the two images.
        //
        // The belt was removed in full rather than narrowed: a change with a failed experiment behind it
        // and no mechanism in front of it is not worth a full size decode per wake on a tree that OOM'd
        // at builds 1032 and 1039. NOTHING SPECULATIVE REMAINS IN THE TREE for this bug, so the next
        // experiment on it starts clean and its result will attribute. Do not reintroduce an unconditional
        // re-issue without a mechanism; that one has been run.
        //
        // The guard below STAYS regardless. A superseded load must not paint, whether or not that is the
        // bug - it is correct on its own terms and it costs nothing.

        // The mediaId this holder is currently trying to show. A late delivery from a superseded load
        // must not write bookkeeping for a track the holder has already moved off, or the next bind for
        // the real track would see a mismatch and re-issue. Compared by value inside onDelivered.
        private var pendingMediaId: String? = null

        // TRACE (2026-08-29, temporary, GladixArt). What the LAST cover decision on this holder was, so a
        // screen-off wake can be read after the fact instead of guessed. Three outcomes:
        //   declined:<guard>  - no request issued at all, and which early return stopped it. THIS IS THE
        //                       EXPECTED failure: a stale load has already satisfied coverDrawable and
        //                       lastBoundMediaId, so both entry points decline and a warm cache is never
        //                       consulted. Without this line a negative result is unreadable.
        //   issued:<site>     - a request went out, source appended when it lands (MEMORY_CACHE = the warm
        //                       worked; NETWORK = the warm missed or the key did not match).
        // REMOVE WITH THE TRACE.
        var lastCoverDecision: String = "none"
            private set
        // The mediaId the decision above was ABOUT. Sealed with it, so the wake line can be compared
        // against the current track instead of inferring identity from a page number. If they differ, the
        // pager is showing the wrong page and every image-layer hypothesis is aimed at the wrong layer;
        // if they match and the load still went to network, it is a cache problem. REMOVE WITH THE TRACE.
        var lastCoverItemId: String? = null
            private set
        private var coverDecisionSealed = false

        // FIRST WRITE WINS within a generation. bind (onBindViewHolder) and retryLoad
        // (onViewAttachedToWindow) can BOTH run on the same holder in one wake traversal, in either order,
        // and last-write-wins made a wake where bind issued and retryLoad then declined log the DECLINE -
        // precisely the confusion this trace exists to remove. The generation is opened by
        // beginCoverTrace() from PlayerFragment.onResume, which runs before the wake traversal, so what
        // gets reported is the first decision taken during that traversal.
        fun resetCoverDecision() {
            lastCoverDecision = "none"
            lastCoverItemId = null
            coverDecisionSealed = false
        }

        private fun recordCoverDecision(decision: String, itemId: String?) {
            if (coverDecisionSealed) return
            lastCoverDecision = decision
            lastCoverItemId = itemId
            coverDecisionSealed = true
        }

        // The one write allowed THROUGH the seal, because a source is not a competing decision - it is the
        // completion of the one already recorded. Guarded on an exact match so a load from the site that
        // LOST the race cannot relabel the winner: if the sealed decision is a decline, or an issue from
        // the other site, this no-ops.
        private fun upgradeCoverDecision(site: String, src: String) {
            if (lastCoverDecision == "issued:$site") lastCoverDecision = "issued:$site/$src"
        }

        fun applyDrawable() {
            val index = bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION } ?: return
            val item = getItem(index) ?: return
            val curr = current.value?.mediaItem
            if (curr != item) return
            val drawable = paintedDrawable
            currentDrawableListener?.invoke(drawable)
        }

        fun retryLoad(item: MediaItem?) {
            if (coverDrawable != null) {
                recordCoverDecision("declined:coverDrawable@retryLoad", item?.mediaId)
                return
            }
            val old = item?.unloadedCover?.getCachedDrawable(binding.root.context)
            val boundId = item?.mediaId
            pendingMediaId = boundId
            recordCoverDecision("issued:retryLoad", boundId)
            item?.track?.cover.loadWithThumb(
                binding.playerTrackCover, old,
                onSource = { src -> upgradeCoverDecision("retryLoad", src) },
                onDelivered = { drawable ->
                    if (pendingMediaId == boundId) {
                        coverDrawable = drawable
                        lastBoundMediaId = boundId
                    }
                }
            ) {
                if (pendingMediaId != boundId) return@loadWithThumb
                val image = it
                    ?: ResourcesCompat.getDrawable(resources, R.drawable.art_music, context.theme)
                setImageDrawable(image)
                paintedDrawable = it
                applyDrawable()
            }
        }

        fun bind(item: MediaItem?) {
            // ⚠⚠ TEMPORARY DIAGNOSTIC (2026-09-26), TAG GladixArt. REMOVE AS SOON AS ONE
            // ROW BELOW IS OBSERVED - it prints on EVERY bind and every mini-art load, working cases
            // included, so silence can only mean this code did not run.
            // THE QUESTION: a shared-link track shows correct art on the mini player and lock screen
            // and a BLANK cover on the full player's page. Both surfaces provably read the same
            // objects - PlayerEventListener is the only writer of currentFlow, and ShufflePlayer
            // overrides neither getCurrentMediaItem nor getMediaItemAt - so the split must be in what
            // each does with them.
            //   currentCoverNull=f listCoverNull=t  -> they DO read different items after all; the
            //       queue flow is stale independently of the timeline. Fix at emitFullQueue's triggers.
            //   currentCoverNull=f listCoverNull=f  -> the page HAS the cover and the image load is
            //       suppressed. Fix at the pendingMediaId/boundId guard or the cache key.
            //   both t, yet the mini player shows art -> the mini art is not coming from track.cover
            //       at all (cache or placeholder). Fix at the ImageUtils cache key.
            Log.d(
                "GladixArt",
                "ARTSRC where=page id=${item?.mediaId}" +
                    " currentCoverNull=${current.value?.track?.cover == null}" +
                    " listCoverNull=${item?.track?.cover == null}" +
                    " listKey=${item?.track?.cover.artKey()}" +
                    " currentKey=${current.value?.track?.cover.artKey()}"
            )
            // ☠️ INERT - these TextViews are inside the permanently invisible collapsedPlayerInfo, so this
            // text is never seen. The mini-bar title/artist the user reads are written in PlayerFragment
            // onto item_player_collapsed_controls. See item_player_collapsed.xml's root note.
            binding.playerCollapsed.run {
                collapsedTrackTitle.text = item?.track?.title
                collapsedTrackArtist.text = item?.track?.artists?.joinToString(", ") { it.name }
            }
            if (item?.mediaId == lastBoundMediaId)
                recordCoverDecision("declined:lastBoundMediaId@bind", item?.mediaId)
            if (item?.mediaId != lastBoundMediaId) {
                // lastBoundMediaId is deliberately NOT assigned here — see its declaration. Until a
                // terminal outcome arrives this holder stays rebindable, so a rebind of the same track
                // re-enters and re-runs the synchronous pre-set below, which repaints the ImageView from
                // the disk cache with no delivery required.
                val boundId = item?.mediaId
                pendingMediaId = boundId
                recordCoverDecision("issued:bind", boundId)
                coverDrawable = null
                val old = item?.unloadedCover?.getCachedDrawable(binding.root.context)
                item?.track?.cover.loadWithThumb(
                    binding.playerTrackCover, old,
                    onSource = { src -> upgradeCoverDecision("bind", src) },
                    onDelivered = { drawable ->
                        if (pendingMediaId == boundId) {
                            coverDrawable = drawable
                            lastBoundMediaId = boundId
                        }
                    }
                ) {
                    if (pendingMediaId != boundId) return@loadWithThumb
                    val image = it
                        ?: ResourcesCompat.getDrawable(resources, R.drawable.art_music, context.theme)
                    setImageDrawable(image)
                    paintedDrawable = it
                    // ⚠⚠ TEMPORARY (2026-09-26, GladixArt). The bind-time line cannot answer
                    // "did it paint" - nothing has been delivered yet there. This fires in the EXISTING
                    // paint lambda, so it adds no request and no callback: painted=f with a non-null
                    // cover is row 2 (the load was issued and produced nothing), painted=f with a null
                    // cover is row 3 (there was nothing to load). decision distinguishes which guard
                    // ran - see recordCoverDecision above.
                    Log.d(
                        "GladixArt",
                        "ARTSRC where=page-done id=$boundId" +
                            " painted=${it != null}" +
                            " decision=$lastCoverDecision" +
                            " key=${item?.track?.cover.artKey()}"
                    )
                    applyDrawable()
                }
            }
            updateInsets()
            updateColors()
        }

        init {
            cover.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(
                        0, 0, currentCoverHeight, currentCoverHeight, currCoverRound
                    )
                }
            }
            cover.clipToOutline = true
            cover.doOnLayout { updateInsets() }
            binding.clickPanel.configureClicking(listener, uiViewModel)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val binding = ItemPlayerTrackBinding.inflate(inflater, parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    var recyclerView: RecyclerView? = null
    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        this.recyclerView = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        this.recyclerView = null
    }

    override fun onViewAttachedToWindow(holder: ViewHolder) {
        holder.updateInsets()
        holder.updateColors()
        holder.applyDrawable()
        val pos = holder.bindingAdapterPosition
        if (pos != RecyclerView.NO_POSITION) holder.retryLoad(getItem(pos))
    }

    override fun onViewDetachedFromWindow(holder: ViewHolder) {
        holder.updateInsets()
        holder.updateColors()
        holder.applyDrawable()
    }

    private fun onEachViewHolder(block: ViewHolder.() -> Unit) {
        val recyclerView = recyclerView ?: return
        recyclerView.run {
            for (it in 0 until childCount) {
                val viewHolder = getChildViewHolder(getChildAt(it)) as? ViewHolder
                viewHolder?.block()
            }
        }
    }

    fun moreOffsetUpdated() = onEachViewHolder { updateCollapsed() }
    fun playerOffsetUpdated() = onEachViewHolder { updateCollapsed() }
    fun playerSheetStateUpdated() = onEachViewHolder { updateInsets() }
    fun insetsUpdated() = onEachViewHolder { updateInsets() }
    fun playerControlsHeightUpdated() = onEachViewHolder { updateInsets() }
    fun onColorsUpdated() = onEachViewHolder { updateColors() }
    // TRACE (2026-08-29, temporary). Reads the visible page's last cover decision. No request, no paint,
    // no state written - purely a read, so it cannot repeat the resume-time re-commit mistakes.
    // REMOVE WITH THE TRACE.
    // Opens a trace generation on every ATTACHED holder, not just the visible one: which holder ends up
    // visible is decided by the traversal that has not run yet. REMOVE WITH THE TRACE.
    fun beginCoverTrace() = onEachViewHolder { resetCoverDecision() }

    // Decision and the mediaId it was about, in one lookup so the two cannot come from different holders.
    // REMOVE WITH THE TRACE.
    fun coverTrace(position: Int): Pair<String, String?>? =
        (recyclerView?.findViewHolderForAdapterPosition(position) as? ViewHolder)
            ?.let { it.lastCoverDecision to it.lastCoverItemId }

    fun onCurrentUpdated() {
        onEachViewHolder { applyDrawable() }
        if (current.value == null) currentDrawableListener?.invoke(null)
    }

    private var isPlayerVisible = false
    fun updatePlayerVisibility(visible: Boolean) {
        isPlayerVisible = visible
        onEachViewHolder { updateInsets() }
    }

    var currentDrawableListener: ((Drawable?) -> Unit)? = null

    companion object {
        fun ItemClickPanelsBinding.configureClicking(listener: Listener, uiViewModel: UiViewModel) {
            start.handleGestures(object : GestureListener {
                override val onClick = listener::onClick
                override val onLongClick = listener::onLongClick
                override val onDoubleClick: (() -> Unit)?
                    get() = if (uiViewModel.playerSheetState.value != STATE_EXPANDED) null
                    else listener::onStartDoubleClick
            })
            end.handleGestures(object : GestureListener {
                override val onClick = listener::onClick
                override val onLongClick = listener::onLongClick
                override val onDoubleClick: (() -> Unit)?
                    get() = if (uiViewModel.playerSheetState.value != STATE_EXPANDED) null
                    else listener::onEndDoubleClick
            })
        }
    }
}