@file:Suppress("ASSIGNED_BUT_NEVER_ACCESSED_VARIABLE")

package dev.brahmkshatriya.echo.ui.player

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.Animatable
import android.graphics.drawable.AnimatedVectorDrawable
import android.graphics.drawable.Drawable
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.util.Log
import android.view.Display
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ProgressBar
import androidx.activity.OnBackPressedCallback
import androidx.annotation.OptIn
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.toBitmap
import androidx.core.net.toUri
import androidx.core.view.doOnLayout
import androidx.core.view.doOnNextLayout
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Player.REPEAT_MODE_ALL
import androidx.media3.common.Player.REPEAT_MODE_OFF
import androidx.media3.common.Player.REPEAT_MODE_ONE
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
import androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_COLLAPSED
import com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
import com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_HIDDEN
import com.google.android.material.slider.Slider
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.databinding.FragmentPlayerBinding
import dev.brahmkshatriya.echo.playback.MediaItemUtils.background
import dev.brahmkshatriya.echo.playback.MediaItemUtils.context
import dev.brahmkshatriya.echo.playback.MediaItemUtils.extensionId
import dev.brahmkshatriya.echo.playback.MediaItemUtils.isLiked
import dev.brahmkshatriya.echo.playback.MediaItemUtils.isLoaded
import dev.brahmkshatriya.echo.playback.MediaItemUtils.showBackground
import dev.brahmkshatriya.echo.playback.MediaItemUtils.track
import dev.brahmkshatriya.echo.playback.MediaItemUtils.unloadedCover
import dev.brahmkshatriya.echo.ui.common.FragmentUtils.openFragment
import dev.brahmkshatriya.echo.ui.common.UiViewModel
import dev.brahmkshatriya.echo.ui.common.UiViewModel.Companion.applyHorizontalInsets
import dev.brahmkshatriya.echo.ui.common.UiViewModel.Companion.applyInsets
import dev.brahmkshatriya.echo.ui.common.UiViewModel.Companion.isFinalState
import dev.brahmkshatriya.echo.ui.common.UiViewModel.Companion.setupPlayerMoreBehavior
import dev.brahmkshatriya.echo.ui.media.MediaFragment
import dev.brahmkshatriya.echo.ui.media.more.MediaMoreBottomSheet
import dev.brahmkshatriya.echo.ui.player.PlayerColors.Companion.defaultPlayerColors
import dev.brahmkshatriya.echo.ui.player.PlayerColors.Companion.getColorsFrom
import dev.brahmkshatriya.echo.ui.player.PlayerTrackAdapter.Companion.configureClicking
import dev.brahmkshatriya.echo.ui.player.quality.FormatUtils.getDetailsFormatFirst
import dev.brahmkshatriya.echo.ui.player.quality.QualitySelectionBottomSheet
import dev.brahmkshatriya.echo.utils.ContextUtils.emit
import dev.brahmkshatriya.echo.utils.ContextUtils.getSettings
import dev.brahmkshatriya.echo.utils.ContextUtils.observe
import dev.brahmkshatriya.echo.utils.image.ImageUtils
import dev.brahmkshatriya.echo.utils.image.ImageUtils.getCachedDrawable
import dev.brahmkshatriya.echo.utils.image.ImageUtils.loadAsCircle
import dev.brahmkshatriya.echo.utils.image.ImageUtils.loadBlurred
import dev.brahmkshatriya.echo.utils.image.ImageUtils.loadWithThumb
import dev.brahmkshatriya.echo.utils.image.ImageUtils.warmMemoryCache
import dev.brahmkshatriya.echo.utils.ui.AnimationUtils.animateVisibility
import dev.brahmkshatriya.echo.utils.ui.AutoClearedValue.Companion.autoClearedNullable
import dev.brahmkshatriya.echo.utils.ui.CheckBoxListener
import dev.brahmkshatriya.echo.utils.ui.SimpleItemSpan
import dev.brahmkshatriya.echo.utils.ui.UiUtils.dpToPx
import dev.brahmkshatriya.echo.utils.ui.UiUtils.hideSystemUi
import dev.brahmkshatriya.echo.utils.ui.UiUtils.isLandscape
import dev.brahmkshatriya.echo.utils.ui.UiUtils.isRTL
import dev.brahmkshatriya.echo.utils.ui.UiUtils.marquee
import dev.brahmkshatriya.echo.utils.ui.UiUtils.toTimeString
import dev.brahmkshatriya.echo.utils.ui.ViewPager2Utils.onFirstPageBackSwipe
import dev.brahmkshatriya.echo.utils.ui.ViewPager2Utils.registerOnUserPageChangeCallback
import dev.brahmkshatriya.echo.utils.ui.ViewPager2Utils.supportBottomSheetBehavior
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.activityViewModel

// The fraction of the player-sheet drag that each chrome fade occupies. The collapsed mini-bar fades
// OUT over the first CHROME_FADE_FRACTION of the drag; the expanded toolbar and controls fade IN over
// the last CHROME_FADE_FRACTION. At 1/3 that leaves a chrome-free middle third carried by the cover
// morph alone (PlayerTrackAdapter.updateCollapsed scales the cover linearly across the whole drag) —
// the shared element owns the handoff and the chrome clears at both ends.
//
// RETUNE HERE AND ONLY HERE. Both curves in updateCollapsed derive from this one value:
//   collapsed bar   : min(1, max(0, (collapsedOffset - (1 - F)) / F))   -> 1 at p=0,   0 from p=F
//   expanded chrome : 1 - min(1, collapsedOffset / F)                   -> 0 until p=1-F, 1 at p=1
// where p = playerSheetOffset (0 collapsed .. 1 expanded) and collapsedOffset = 1 - p in the drag arm.
// Larger F = longer fades and a smaller gap; F = 1/2 closes the gap and the curves cross at p = 0.5.
// Avoid that: the two layouts put their text in different places, so blending both at 50% is
// double-exposure — the same artefact this replaced.
//
// WHAT THIS FIXED: the outgoing curve was `collapsedOffset * 2`, which fades over the WRONG HALF. The
// bar held FULL opacity for p in [0, 0.5] while the sheet and cover travelled half their distance, then
// overlapped the incoming toolbar over p in [2/3, 1]. Both directions ran it — the drag-down fix in
// e0a3e5df/f2b661b0 put collapse onto the same arm expand already used — which is why the morph read as
// the collapsed bar OVERLAYING the expanded player rather than morphing into it, in BOTH directions.
//
// ⚠️ DO NOT "fix" the `* 2` translation parallax that accompanies these fades. It is deliberate and
// consistent across all four animated groups (bar, bgCollapsed, toolbar, controls): the outgoing element
// clears its slot faster than the container moves. It looks like a retuning candidate now that the alpha
// has changed, and it is not — the old artefact (an opaque bar sitting ~64dp above its slot at mid-drag)
// was an ALPHA problem wearing a translation costume. With the fade ending at p = F the bar is never
// seen further than 2 * collapseHeight * F from its slot and reads as a normal slide-and-fade exit.
// Retune F, not the 2.
private const val CHROME_FADE_FRACTION = 1f / 3f

class PlayerFragment : Fragment() {
    // ⚠⚠ `binding != null` IS NOT AN ATTACHMENT PROXY. READ THIS BEFORE WRITING ANOTHER
    // `binding?.foo ?: return` GUARD — the shape is used widely in this file and it is weaker than it looks.
    // PROVEN BY A CRASH, not argued: the build-1084 fatal in configurePlayerControls' submitList callback
    // reached Fragment.requireContext() and threw "Fragment not attached to a context" — which it could
    // only do AFTER passing `val viewPager = binding?.viewPager ?: return@submitList` on the line above.
    // So binding was NON-NULL while the fragment was DETACHED.
    // WHAT THE GUARD ACTUALLY BUYS: it proves the VIEW still exists (autoClearedNullable clears it at
    // onDestroyView), which is enough to touch views and to read View.getContext(). IT PROVES NOTHING ABOUT
    // THE FRAGMENT being attached, so it does not make requireContext(), requireActivity(),
    // getViewLifecycleOwner() or any other require*/Fragment-host call safe. If you need a Context in a
    // callback that may run late, take it from a VIEW you have already null-checked.
    private var binding by autoClearedNullable<FragmentPlayerBinding>()
    private val viewModel by activityViewModel<PlayerViewModel>()
    private val uiViewModel by activityViewModel<UiViewModel>()
    private val adapter by lazy {
        PlayerTrackAdapter(uiViewModel, viewModel.playerState.current, adapterListener)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
    ): View {
        binding = FragmentPlayerBinding.inflate(inflater, container, false)
        return binding!!.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = binding!!
        binding.viewPager.supportBottomSheetBehavior()
        setupPlayerMoreBehavior(uiViewModel, binding.playerMoreContainer)
        configureOutline(binding.root)
        configureCollapsing(binding)
        configureColors()
        configurePlayerControls()
        configureBackgroundPlayerView()
    }

    // Wave motion follows the SAME discipline as the Ken Burns background below: a lifecycle pair plus the
    // playerSheetState observer, with a third condition (isPlaying) that Ken Burns does not have.
    // The "on" values are captured from the inflated view rather than duplicated as constants here, so the
    // style stays the single source of truth for amplitude and speed.
    private var waveSpeedPx = -1
    private var waveAmplitudePx = -1
    // NOT Fragment.isResumed: that reports mState and its value during onPause is an ordering detail
    // of FragmentStateManager. This is our own flag, set explicitly either side.
    private var waveResumed = false

    // Read the gating note in styles.xml (EchoLinearProgressIndicator.Wavy) before changing this.
    // Short version: the phase animator is never cancelled and does not need to be. setWaveSpeed(0)
    // stops every invalidation, and setWaveAmplitude(0) trips the hasWavyEffect gate so flattening on
    // pause IS the stop. Do not "fix" this by reaching for the animator.
    private fun updateWaveMotion() {
        val wave = binding?.playerControls?.seekWaveBar ?: return
        if (waveSpeedPx < 0) {
            waveSpeedPx = wave.waveSpeed
            waveAmplitudePx = wave.waveAmplitude
        }
        // playWhenReady, NOT Current.isPlaying. isPlaying is `player.isPlaying && state == READY`, and
        // Media3's own isPlaying additionally requires playbackSuppressionReason == NONE — so it goes
        // FALSE on buffering, on any seek that rebuffers, and on every track transition. Gating on it
        // flattened the wave on all of those and left it flat until an unrelated event happened to run
        // this again. playWhenReady tracks the player's INTENT and only changes on a real pause.
        // MainActivity:109 picked the same signal for keepScreenOn, with the same reasoning.
        val playing = viewModel.playWhenReady.value
        val expanded = uiViewModel.playerSheetState.value == STATE_EXPANDED
        // Amplitude tracks PLAYING only: a paused player shows a flat line, which is what the system
        // media notification does. Speed additionally requires the wave to be on screen and the fragment
        // resumed — collapsed is the one state the library does not handle for us, because the sheet's
        // views stay attached and window-visible when it slides down.
        wave.waveAmplitude = if (playing) waveAmplitudePx else 0
        wave.waveSpeed = if (playing && expanded && waveResumed) waveSpeedPx else 0
    }

    override fun onPause() {
        super.onPause()
        waveResumed = false
        updateWaveMotion()
        binding?.bgImage?.pause()
    }

    override fun onResume() {
        super.onResume()
        waveResumed = true
        updateWaveMotion()
        // TRACE (2026-08-29, temporary, GladixArt). One line per wake for the VISIBLE page only, recording
        // which of the three outcomes the cover took: no request and which guard declined, or a request
        // and where its bytes came from. Posted so it runs AFTER the wake traversal, i.e. after bind and
        // retryLoad have already decided - this REPORTS, it does not act. It issues nothing and writes no
        // page position, so it is not a re-run of the resume-time re-commits (aecc6700, reverted 3 Aug).
        // Needed because "the holder declined to issue" is the EXPECTED failure mode for the memory-cache
        // warm, not an edge case, so a negative result is otherwise unreadable. REMOVE WITH THE TRACE.
        binding?.let { b ->
            // Synchronous, and BEFORE the post: onResume runs ahead of the wake traversal, so this opens
            // the generation that bind/retryLoad then decide within, and the posted read below reports it.
            adapter.beginCoverTrace()
            b.viewPager.post {
                val pos = b.viewPager.currentItem
                // `pos` is ViewPager2's LOGICAL page (mCurrentItem, written at ViewPager2.java:649 the
                // instant setCurrentItem is called, frames or no frames). `rendered` is what is actually
                // laid out. They are different questions and the whole point of this pair is that a
                // stalled smooth scroll separates them: mCurrentItem advances, the pixels do not.
                // ViewPager2's own reconciler (updateCurrentItem) CANNOT mask this — it is gated on
                // SCROLL_STATE_IDLE at ViewPager2.java:555, so while SETTLING it never snaps pos to
                // rendered. Read off the inner RecyclerView because ViewPager2 exposes no such accessor.
                val rendered = (b.viewPager.getChildAt(0) as? RecyclerView)
                    ?.let { it.layoutManager as? LinearLayoutManager }
                    ?.findFirstCompletelyVisibleItemPosition() ?: RecyclerView.NO_POSITION
                // The stall itself, directly. notifyProgrammaticScroll dispatches SCROLL_STATE_SETTLING
                // and only resetState() clears it, which is reached solely from onScrolled /
                // onScrollStateChanged — so SETTLING at a wake means the smooth scroll never progressed.
                // 0=IDLE 1=DRAGGING 2=SETTLING.
                val scrollState = b.viewPager.scrollState
                val trace = adapter.coverTrace(pos)
                val boundId = trace?.second
                val curId = viewModel.playerState.current.value?.mediaItem?.mediaId
                // bound vs cur is the question this line exists to settle, so BOTH are printed and the
                // comparison is made here rather than left to be inferred from the page number. A mismatch
                // means the pager is a page behind and the stale cover is a symptom of that, not of any
                // image load; a match with a NETWORK decision means the cache was not consulted or the key
                // did not agree. warm=issued/done separates never-ran / parked / completed - see the
                // counters in ImageUtils.
                //
                // ⚠️ READ page/rendered/scrollState BEFORE bound/cur. bound and cur are ADAPTER-SPACE and
                // can both be correct about a page that is not the one on screen. If page != rendered, or
                // scrollState is SETTLING(2), this is a PAGE-POSITION fault and every image-layer reading
                // on this line is aimed at the wrong layer.
                //
                // ⚠️ decision=none AND bound=null ARE THE RESET VALUES, not findings. beginCoverTrace()
                // writes exactly that pair onto every attached holder moments earlier, so together they
                // mean only "no cover decision was recorded during this wake" - NOT that the holder is
                // unbound, and NOT that no request was ever issued. A holder bound before the trace opened
                // reads identically. `decision=no-holder` is the only value that means no holder was
                // found; the durable binding field (ViewHolder.lastBoundMediaId) is never printed here.
                Log.d(
                    "GladixArt",
                    "wake: page=$pos rendered=$rendered scrollState=$scrollState " +
                        "bound=$boundId cur=$curId match=${boundId == curId} " +
                        "decision=${trace?.first ?: "no-holder"} " +
                        "warm=${ImageUtils.warmIssued.get()}/${ImageUtils.warmDone.get()}"
                )
            }
        }
        if (uiViewModel.playerSheetState.value == STATE_EXPANDED)
            binding?.bgImage?.resume()
    }

    private val collapseHeight by lazy {
        resources.getDimension(R.dimen.collapsed_cover_size).toInt()
    }

    private fun configureOutline(view: View) {
        val padding = 8.dpToPx(requireContext())
        var currHeight = collapseHeight
        var currRound = padding.toFloat()
        var currRight = 0
        var currLeft = 0
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(
                    currLeft, 0, currRight, currHeight, currRound
                )
            }
        }
        view.clipToOutline = true

        var leftPadding = 0
        var rightPadding = 0

        val maxElevation = 4.dpToPx(requireContext()).toFloat()
        fun updateOutline() {
            val offset = max(0f, uiViewModel.playerSheetOffset.value)
            val inv = 1 - offset
            view.elevation = maxElevation * inv
            currHeight = collapseHeight + ((view.height - collapseHeight) * offset).toInt()
            // Full-width collapsed mini-bar minus the 8dp card inset, but still respecting the
            // start/end insets — flush to the screen edge in portrait, and flush to the nav-rail's
            // right edge in landscape (combined.start carries the rail width). Corner radius
            // (currRound) is intentionally left untouched.
            currLeft = (leftPadding * inv).toInt()
            currRight = view.width - (rightPadding * inv).toInt()
            currRound = max(padding * inv, padding * uiViewModel.playerBackProgress.value * 2)
            view.invalidateOutline()
        }
        observe(uiViewModel.combined) {
            leftPadding = if (view.context.isRTL()) it.end else it.start
            rightPadding = if (view.context.isRTL()) it.start else it.end
            updateOutline()
        }
        observe(uiViewModel.playerBackProgress) { updateOutline() }
        observe(uiViewModel.playerSheetOffset) { updateOutline() }
        view.doOnLayout { updateOutline() }
    }

    private fun configureCollapsing(binding: FragmentPlayerBinding) {
        binding.playerCollapsedContainer.root.clipToOutline = true

        val collapsedTopPadding = 8.dpToPx(requireContext())
        var currRound = collapsedTopPadding.toFloat()
        var currTop = 0
        var currBottom = collapseHeight
        var currRight = 0
        var currLeft = 0

        val view = binding.viewPager
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(
                    currLeft, currTop, currRight, currBottom, currRound
                )
            }
        }
        view.clipToOutline = true

        val extraEndPadding = 108.dpToPx(requireContext())
        var leftPadding = 0
        var rightPadding = 0
        val isLandscape = requireContext().isLandscape()
        fun updateCollapsed() {
            // The `playerSheetOffset >= 1f` conjunct is the TWIN of the one in
            // PlayerTrackAdapter.updateCollapsed - see the long note there for the full reasoning.
            // Short version: playerSheetState is a SETTLED-STATES-ONLY signal (UiViewModel gates its write
            // with `if (!isFinalState(newState)) return`), so it still reads EXPANDED for the whole of a
            // collapse drag. Keying on state alone sent every frame into the EXPANDED arm, where `offset`
            // comes from moreSheetOffset - 0 with Up Next closed - so alphaInv stayed 1 and the expanded
            // header ("Playing From", the context title, the extension icon) slid down with the sheet at
            // FULL OPACITY instead of fading out, while the cover morphed correctly.
            // ⚠️ THERE ARE TWO updateCollapsed() FUNCTIONS. The adapter's animates the per-page cover; this
            // one animates the real collapsed bar, bgCollapsed, the toolbar and playerControls. Fixing one
            // fixes half the morph - that is exactly what happened on 2026-08-26, and why the cover
            // morphed while the header did not. Change them together.
            // Hoisted so the branch below and currTop at the bottom of this function test the SAME value.
            // Reading playerSheetOffset separately at each site would let them disagree: `observe` collects
            // a CONFLATED StateFlow off a coroutine resumption, not synchronously inside onSlide's frame.
            val playerFullyExpanded = uiViewModel.playerSheetOffset.value >= 1f
            val (collapsedY, offset, collapsedOffset) = uiViewModel.run {
                if (playerSheetState.value == STATE_EXPANDED && playerFullyExpanded) {
                    val offset = moreSheetOffset.value
                    Triple(systemInsets.value.top, offset, if (isLandscape) 0f else offset)
                } else {
                    val offset = 1 - max(0f, playerSheetOffset.value)
                    Triple(-collapsedTopPadding, offset, offset)
                }
            }
            val collapsedInv = 1 - collapsedOffset
            // Outgoing chrome. collapsedOffset counts DOWN from 1 as the sheet expands, so the drag-space
            // window [0, F] maps to [1, 1 - F] here. Derived from CHROME_FADE_FRACTION - retune there.
            val collapsedAlpha = min(
                1f, max(0f, (collapsedOffset - (1f - CHROME_FADE_FRACTION)) / CHROME_FADE_FRACTION)
            )
            binding.playerCollapsedContainer.root.run {
                // The `* 2` parallax is deliberate and is NOT a retuning candidate now that the alpha
                // curve has moved - see the warning on CHROME_FADE_FRACTION before touching it.
                translationY = collapsedY - collapseHeight * collapsedInv * 2
                alpha = collapsedAlpha
                // Mirrors the `isVisible = offset < 1` on the expanded chrome below. Without it the bar
                // keeps translating (invisibly) for the remaining 1 - F of the drag after its fade ends.
                isVisible = collapsedAlpha > 0f
                translationZ = -1f * collapsedInv
            }
            binding.bgCollapsed.run {
                translationY = collapsedY - collapseHeight * collapsedInv * 2
                // Scrim ceiling stays 0.5 (was `min(1f, collapsedOffset * 2) - 0.5f`, which clamped its
                // negative tail to 0). Only the window moves, so the scrim cannot outlive the bar above it.
                alpha = 0.5f * collapsedAlpha
            }
            // Incoming chrome - the mirror of collapsedAlpha, fading in over the LAST CHROME_FADE_FRACTION.
            val alphaInv = 1 - min(1f, offset / CHROME_FADE_FRACTION)
            binding.expandedToolbar.run {
                translationY = collapseHeight * offset * 2
                alpha = alphaInv
                isVisible = offset < 1
                translationZ = -1f * offset
            }
            binding.playerControls.root.run {
                translationY = collapseHeight * offset * 2
                alpha = alphaInv
                isVisible = offset < 1
            }
            currTop = uiViewModel.run {
                // Same conjunct as the branch above, same reason: on a collapse drag playerSheetState still
                // reads EXPANDED for the whole gesture, so keying on state alone fed the ramp below the
                // EXPANDED inset for the last quarter of the drag and then SNAPPED it to 0 when the flow
                // finally settled to COLLAPSED. Drag-up, starting from COLLAPSED, always read 0 - the jump
                // was collapse-only.
                //
                // ⚠️ NOT DEAD CODE. Keying reachability on the player sheet alone concludes this arm is
                // unused, because the ramp is non-zero only for collapsedOffset > 0.75 while the conjunct
                // needs playerSheetOffset >= 1f (collapsedOffset -> 0). It is reachable via UP NEXT: in the
                // EXPANDED arm collapsedOffset is moreSheetOffset, so dragging Up Next past 75% open over a
                // fully expanded player lands here. Portrait only - the EXPANDED arm pins collapsedOffset to
                // 0f in landscape. Do not collapse this to a constant 0.
                val top = if (playerSheetState.value != STATE_EXPANDED || !playerFullyExpanded) 0
                else collapsedTopPadding + systemInsets.value.top
                (top * max(0f, (collapsedOffset - 0.75f) * 4)).toInt()
            }
            val bot = currTop + collapseHeight
            currBottom = bot + ((view.height - bot) * collapsedInv).toInt()
            currLeft = (leftPadding * collapsedOffset).toInt()
            currRight = view.width - (rightPadding * collapsedOffset).toInt()
            currRound = collapsedTopPadding * collapsedOffset
            view.invalidateOutline()
        }

        view.doOnLayout { updateCollapsed() }
        observe(uiViewModel.combined) {
            val system = uiViewModel.systemInsets.value
            binding.constraintLayout.applyInsets(system, 64, 0)
            binding.expandedToolbar.applyInsets(system)
            val insets = uiViewModel.run {
                if (playerSheetState.value == STATE_EXPANDED) system
                else getCombined()
            }
            // Collapsed mini-player always uses getCombined() (rail included), NOT the STATE_EXPANDED-
            // gated `insets`: on rotate-while-expanded → collapse, `combined` last emits while EXPANDED
            // (gate picks rail-less `system`) and collapsing never re-emits it, so the bar kept a zero
            // rail inset and overlapped the rail. The container is alpha=0 whenever landscape+expanded
            // (configureCollapsing's updateCollapsed pins collapsedOffset to 0f in landscape, so
            // collapsedAlpha is 0), so carrying the rail inset while expanded is inert. The gate stays for
            // the playerControls.root.applyHorizontalInsets call just below, which needs `system` for its
            // expanded end-inset.
            binding.playerCollapsedContainer.root.applyHorizontalInsets(uiViewModel.getCombined())
            binding.playerControls.root.applyHorizontalInsets(
                insets,
                requireActivity().isLandscape()
            )
            val left = if (requireContext().isRTL()) system.end + extraEndPadding else system.start
            leftPadding = collapsedTopPadding + left
            val right = if (requireContext().isRTL()) system.start else system.end + extraEndPadding
            rightPadding = collapsedTopPadding + right
            // Landscape/rail: after rotation the viewPager cover isn't settled to its landscape
            // geometry when this fires, so a synchronous updateCollapsed() would read stale
            // cover.left/height and land the morph wrong (art/title overlap). Defer to the next
            // layout so it reads settled geometry. (Gate is isLandscape — NOT it.bottom, because
            // here `it` is uiViewModel.combined, whose bottom carries playerInsets and is never 0
            // in landscape.) Portrait keeps the synchronous path unchanged.
            if (isLandscape) {
                binding.viewPager.doOnNextLayout { updateCollapsed(); adapter.insetsUpdated() }
            } else {
                updateCollapsed()
                adapter.insetsUpdated()
            }
        }

        observe(uiViewModel.moreSheetOffset) {
            updateCollapsed()
            adapter.moreOffsetUpdated()
        }
        observe(uiViewModel.playerSheetOffset) {
            updateCollapsed()
            adapter.playerOffsetUpdated()

            viewModel.browser.value?.volume = 1 + min(0f, it)
            if (it < 1)
                requireActivity().hideSystemUi(false)
            else if (uiViewModel.playerBgVisible.value)
                requireActivity().hideSystemUi(true)
        }

        observe(uiViewModel.playerSheetState) {
            updateCollapsed()
            if (isFinalState(it)) adapter.playerSheetStateUpdated()
            if (it == STATE_COLLAPSED) emit(uiViewModel.playerBgVisible, false)
            when (it) {
                STATE_EXPANDED -> binding.bgImage.resume()
                else -> binding.bgImage.pause()
            }
            updateWaveMotion()
            // Canvas/video is fullscreen-only — re-run applyPlayer() for the new sheet state: on collapse it
            // DETACHES the video surface (playerView.player = null) so the Canvas/video stops rendering in the
            // mini-bar (surface-only — audio keeps playing via the service player); on expand it re-attaches
            // and shows it. Same transition as the KenBurns pause above. playerSheetState only emits final
            // states (HIDDEN/COLLAPSED/EXPANDED), so this fires once per settle — no mid-drag churn.
            applyPlayer()
        }
        binding.playerControls.root.doOnLayout {
            uiViewModel.playerControlsHeight.value = it.height
            adapter.playerControlsHeightUpdated()
        }
        var bgBackCallback: OnBackPressedCallback? = null
        observe(uiViewModel.playerBgVisible) { visible ->
            binding.viewPager.isUserInputEnabled = !visible
            binding.fgContainer.animateVisibility(!visible)
            binding.playerMoreContainer.animateVisibility(!visible)
            bgBackCallback?.remove()
            bgBackCallback = null
            if (visible) {
                bgBackCallback = object : OnBackPressedCallback(true) {
                    override fun handleOnBackPressed() {
                        uiViewModel.changeBgVisible(false)
                    }
                }.also {
                    requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, it)
                }
            }
        }
        binding.bgPanel.configureClicking(adapterListener, uiViewModel)
        binding.expandedToolbar.setNavigationOnClickListener {
            uiViewModel.collapsePlayer()
        }
    }

    private val adapterListener = object : PlayerTrackAdapter.Listener {
        override fun onClick(): Unit = uiViewModel.run {
            if (playerSheetState.value != STATE_EXPANDED) changePlayerState(STATE_EXPANDED)
            else {
                if (moreSheetState.value == STATE_EXPANDED) {
                    changeMoreState(STATE_COLLAPSED)
                    return
                }
                val shouldBeVisible = !playerBgVisible.value
                if (shouldBeVisible) {
                    val binding = binding ?: return@run
                    if (binding.bgImage.drawable == null && !binding.playerView.player.hasVideo())
                        return
                    changeMoreState(STATE_COLLAPSED)
                }
                changeBgVisible(shouldBeVisible)
            }
        }

        override fun onStartDoubleClick() {
            viewModel.seekToAdd(-10000)
        }

        override fun onEndDoubleClick() {
            viewModel.seekToAdd(10000)
        }
    }

    private var isInitialLoad = true
    private var pendingPageScroll: Runnable? = null
    private fun configurePlayerControls() {
        val viewPager = binding!!.viewPager
        viewPager.adapter = adapter
        (viewPager.getChildAt(0) as? RecyclerView)?.itemAnimator = null
        // Backward swipe on page 0 - the one gesture ViewPager2 cannot report. Routed to the
        // always-previous command rather than to viewModel.previous(), and it never touches the page
        // position. See ViewPager2Utils.onFirstPageBackSwipe.
        viewPager.onFirstPageBackSwipe { viewModel.previousTrack() }
        viewPager.registerOnUserPageChangeCallback { pos, isUser ->
            val curr = viewModel.playerState.current.value
            val index = curr?.let { c -> viewModel.queue.indexOfFirst { it.mediaId == c.mediaItem.mediaId } } ?: -1
            if (index != pos && isUser) viewModel.seek(pos)
        }

        fun submit() {
            val capturedCurrent = viewModel.playerState.current.value
            // `submitted` is the EXACT instance handed to submitList, so the index computed below and
            // the list the adapter receives provably come from one generation. Do not re-read
            // viewModel.queue inside the callback: it can advance a generation (emitFullQueue writes 50ms
            // after a timeline change) while the adapter still holds this one, and an index from one
            // generation applied to another is off by however many tracks were removed in between.
            val submitted = viewModel.queue
            val capturedIndex = capturedCurrent?.let { c ->
                submitted.indexOfFirst { it.mediaId == c.mediaItem.mediaId }.takeIf { it != -1 }
            }
            adapter.submitList(submitted) {
                val index = capturedIndex ?: return@submitList
                val viewPager = binding?.viewPager ?: return@submitList
                val current = viewPager.currentItem
                // Only smooth-scroll when frames will actually be delivered to THIS window. A smooth
                // scroll is driven by Choreographer frames; a NON-smooth setCurrentItem commits via the
                // LayoutManager's pending scroll (scrollToPosition when laid out, mPendingCurrentItem when
                // not), applied on the next layout pass — no frames needed — so the correct page renders
                // with no stale frame. Visible advances keep the animated ±1 behavior.
                //
                // TWO CONDITIONS, AND THE SECOND WAS MISSING UNTIL 2026-09-03.
                //   displayOn  — the screen is producing frames at all. DOZE / DOZE_SUSPEND / OFF all
                //                correctly read as "no frames". DisplayManager rather than
                //                Context.getDisplay, which is API 30+ and we ship 24.
                //   isResumed  — frames are being delivered to US. A BACKGROUNDED app with the display
                //                fully on is the case displayOn alone gets wrong: the display is on and
                //                frames flow — to the foreground app. This window gets none.
                //
                // ⚠️ THE DISPLAY-ONLY GATE WAS VALIDATED FOR SCREEN-OFF ONLY. The 2026-08-24 measurement
                // cited below ran over eight consecutive SCREEN-OFF auto-advances. It never exercised
                // backgrounded-with-display-on, and that is the door it left open. Do not cite it as
                // general validation of this gate. (It is also recorded ONLY in comments — both Aug 24
                // commits, b33c4207 and 172f1edb, have empty message bodies. Treat accordingly.)
                //
                // What goes wrong without isResumed, READ FROM ViewPager2 1.1.0-beta02 SOURCE (identical
                // in 1.0.0 — setCurrentItemInternal and updateCurrentItem are byte-identical across both,
                // so the transitive version does not matter):
                //   1. ViewPager2.java:649 sets `mCurrentItem = item` IMMEDIATELY, before any scrolling.
                //      getCurrentItem() returns the target whether or not a frame is ever drawn.
                //   2. ScrollEventAdapter.notifyProgrammaticScroll sets STATE_IN_PROGRESS_SMOOTH_SCROLL and
                //      dispatches SCROLL_STATE_SETTLING. Only resetState() clears that, and it is reached
                //      solely from onScrolled/onScrollStateChanged — i.e. only if the scroll progresses.
                //   3. The ONLY layout-time reconciler is ViewPager2.java:538 `if (mCurrentItemDirty)
                //      updateCurrentItem()`. It runs the WRONG WAY (:543-561 pushes the RENDERED snap
                //      position into mCurrentItem; it never scrolls toward mCurrentItem), and it is gated
                //      on `getScrollState() == SCROLL_STATE_IDLE` at :555 — false while SETTLING. It then
                //      clears mCurrentItemDirty at :560 REGARDLESS, consuming its own trigger.
                //   4. It is self-latching: ViewPager2.java:640 returns early on
                //      `item == mCurrentItem && smoothScroll`, so no later smooth setCurrentItem for the
                //      same page can re-drive it.
                // Net: logical page desyncs from the rendered page, permanently, with nothing to fix it —
                // no bind, no cover request, and the old holder keeps its old drawable.
                //
                // ⚠️ THE ESCAPE IS setCurrentItem(index, FALSE), NOT A currentItem COMPARISON. With state
                // SETTLING, smooth=false passes BOTH guards (:636 needs isIdle(), false; :640 needs
                // smoothScroll, false) and reaches :682 mRecyclerView.scrollToPosition(item), a pending
                // position applied on the next layout with no frames required. But `if (vp.currentItem !=
                // index)` is FALSE in the stalled state — mCurrentItem was set at step 1 — so a guarded
                // re-commit is a NO-OP in exactly the scenario it looks like it fixes. That is what the
                // 2026-07-28 onResume pre-draw block (cf60b564, reverted 2026-07-30 by 60ab8d0c) did; it
                // could never have fixed this bug. Do not reinstate it in that shape.
                //
                // Asymmetry that justifies erring conservative: smooth=false is ALWAYS correct — the
                // non-smooth path commits the same page through the LayoutManager — so a false negative
                // costs only an animation, while a false positive leaves the page stale until the user
                // interacts. NO PATH REQUIRES the animated variant: the user-drag path never comes through
                // here (it is ViewPager2's own touch handling), and every caller of submit() wants the
                // page committed, not animated. This flag is the ONLY thing changed; the writers of the
                // page position are untouched, which is what the reverted pre-draw re-commit got wrong (it
                // added a third writer and produced a permanent one-behind).
                // ⚠⚠ viewPager.context, NEVER requireContext() — THIS LINE WAS A FATAL CRASH ON BUILD 1084.
                // "IllegalStateException: Fragment not attached to a context", Fragment.requireContext at
                // configurePlayerControls$submit$lambda, reached from AsyncListDiffer.onCurrentListChanged
                // -> latchList -> Handler. AsyncListDiffer diffs OFF-THREAD and posts the commit callback
                // back to the main looper; the fragment detached inside that window and the callback then
                // asked the Fragment for a Context. View.getContext() never throws and is the same Activity
                // context, and `viewPager` is already non-null here — so this reads the display exactly as
                // before with no attachment dependency.
                // ⚠️ THE READ STAYS INSIDE THE CALLBACK. DO NOT HOIST IT INTO submit() FOR TIDINESS — that
                // would sample the screen BEFORE the off-thread diff, and a stale displayOn=true is exactly
                // the false positive that produces a stale page.
                // AND displayOn EXISTS BECAUSE AN ACTIVITY-STATE PROXY WAS ALREADY TRIED AND FOUND WRONG.
                // The gate used to read `started && !isInitialLoad && abs(index - current) <= 1`. A POWER
                // BUTTON drives the Activity to STOPPED promptly, so `started` went false and the page
                // committed instantly — fine. A NATURAL TIMEOUT dims the display FIRST and the Activity may
                // still report STARTED while frames have stopped: `started` stayed true, the smooth scroll
                // waited on Choreographer frames that never came, and ViewPager2's logical mCurrentItem
                // desynced from the rendered page with nothing pending to reconcile it. Reading the DISPLAY
                // is the fix for that, and reading it LATE is what keeps it true. A hoist reintroduces the
                // same class of staleness the read was added to remove.
                //
                // ⚠️ CONFIRMED SAFE ON A DETACHED-ACTIVITY CONTEXT, which is the one link this fix depends
                // on. View.getContext() on a detached fragment returns the Context the view was inflated
                // with — possibly a destroyed Activity or a ContextThemeWrapper around one. That is fine:
                // AOSP's DisplayManager constructor is `mContext = context; mGlobal =
                // DisplayManagerGlobal.getInstance();` and getDisplay(int) delegates to that PROCESS-WIDE
                // singleton, not to per-Context state — the Context is used for resources/user-id, not for
                // display enumeration. So the state read is real regardless of the Activity's condition.
                // Belt and braces anyway: the chain below is null-safe and a null service yields
                // displayOn=false, which is the CONSERVATIVE value (smooth=false is always correct — see
                // the asymmetry note). The worst case degrades to an un-animated commit, never a stale page.
                //
                // ⚠️ THIS CHANGES *WHERE THE CONTEXT COMES FROM*, NOT *WHEN THE WRITE HAPPENS* — stated
                // explicitly because "we changed the submit callback" is alarming on this surface. The page
                // position has THREE independent writers racing the async adapter: current.value (async),
                // viewModel.queue (synchronous), and adapter.currentList (async DiffUtil commit, lagging
                // both), and this file has a history of one-behind bugs from exactly that. Nothing here
                // adds, removes or reorders a writer: the same callback fires at the same moment, computes
                // the same boolean from the same display, and calls the same setCurrentItem with the same
                // index. Only the source of the Context object changed.
                //
                // ⚠️ WHY NOT THE TWO OBVIOUS GUARDS — both were considered and both are WORSE THAN THE CRASH:
                //   `context ?: return@submitList` and an `isAdded` check stop the throw by SKIPPING THE
                //   PAGE COMMIT, which is the entire purpose of this callback. A fragment that detaches and
                //   reattaches then sits on the WRONG PAGE, silently — a worse bug than a crash, because
                //   nothing reports it. The commit must still happen; only the Fragment dependency goes.
                //   `viewLifecycleOwner` is not merely insufficient here, it is INAPPLICABLE: this is a
                //   Handler-posted diff callback, not a coroutine, and no lifecycle scope governs it. (That
                //   distinction matters because the ~25 observe() calls in this file bound to the Fragment
                //   rather than viewLifecycleOwner ARE a real and separate family — same symptom, different
                //   mechanism. Do not fix this one by reaching for that one's tool.)
                //
                // ⚠️ WHY THE SIBLING CRASH'S REJECTION DOES NOT TRANSFER — THE MECHANISM IS ABSENT HERE, NOT
                // MERELY UNLIKELY. The same exception at PlayerTvFragment.configureColors (:290) was fixed
                // WITHOUT hoisting the context, because that callback wrote uiViewModel.playerColors —
                // ACTIVITY-SCOPED, survives recreate — so a silent guard let it write null, MainActivity
                // themed without the accent, and the next fragment's observer saw null != last accent and
                // called recreate() again: a loop. THIS callback writes only isInitialLoad (:565),
                // pendingPageScroll (:566) and ViewPager state — every one of which dies with the fragment
                // or the view. Nothing here outlives a recreate, so there is nothing to feed a loop.
                // Inherit the caution only where the write is; do not inherit it as a rule.
                //
                // WHY STARTUP — A PROBABILITY AMPLIFIER, NOT A PRECONDITION. Keys: process_age_s 0, all ages
                // 0-1, restore_build_count 80, heap 26MB. Both factors peak in the same second: the FIRST
                // submit after a cold restore diffs an empty list against the WHOLE restored queue (the
                // longest off-thread window there is), and the accent path calls requireActivity().recreate()
                // (:1062, :1067) as the first track's colours arrive. INFERENCE, NOT PROOF: the keys
                // establish the WINDOW, not the CAUSE — a config change or process teardown would look
                // identical. The bug is reachable whenever a detach coincides with a commit; startup just
                // makes both likely at once.
                // Not a stale diff: AsyncListDiffer's mMaxScheduledGeneration means a SUPERSEDED diff
                // produces no callback at all, so this was the CURRENT diff. The fix is about attachment.
                //
                // POSITIVE EXAMPLE, ALREADY CORRECT: QueueFragment's commit callback (:114) uses only
                // `binding?.root?.scrollToPosition(...)` — null-safe binding read, no fragment API. That is
                // the shape a commit callback should have.
                val displayOn = viewPager.context.getSystemService(DisplayManager::class.java)
                    ?.getDisplay(Display.DEFAULT_DISPLAY)?.state == Display.STATE_ON
                val smooth = displayOn && isResumed && !isInitialLoad && abs(index - current) <= 1
                isInitialLoad = false
                if (!viewPager.isLaidOut) viewPager.setCurrentItem(index, smooth)
                else {
                    pendingPageScroll?.let { viewPager.removeCallbacks(it) }
                    val runnable = Runnable {
                        val liveCurrent = viewModel.playerState.current.value
                        val liveIndex = liveCurrent?.let { c ->
                            viewModel.queue.indexOfFirst { it.mediaId == c.mediaItem.mediaId }.takeIf { it != -1 }
                        } ?: index
                        // ⚠️ KNOWN HAZARD, left in place deliberately (measured 2026-08-24, never observed
                        // to fire). `index` is consistent with `submitted`; `liveIndex` is re-derived here
                        // from viewModel.queue at POST time — a potentially LATER generation — and then
                        // applied to the adapter's EARLIER list. viewModel.queue advances 50ms after a
                        // timeline change (emitFullQueue) while the adapter is only re-submitted by
                        // submit(), so the two can disagree by however many tracks were removed in between.
                        // Instrumented over eight consecutive screen-off advances: the two always agreed,
                        // because submit() runs from the ungated `current` collector before the 50ms write
                        // lands. This is the SAME false step as the reverted 2026-07-30 pre-draw re-commit
                        // (60ab8d0c), which derived an index from the live queue and applied it to a stale
                        // adapter list and produced a permanent one-behind. Do not add a third derivation
                        // here; if this ever needs touching, take `index` and delete the re-derivation.
                        binding?.viewPager?.setCurrentItem(liveIndex, smooth)
                    }
                    pendingPageScroll = runnable
                    viewPager.post(runnable)
                }
            }
        }

        val binding = binding!!
        binding.playerControls.trackHeart.addOnCheckedStateChangedListener(likeListener)
        // Deliberately not using observe()/flowWithLifecycle here: that restarts collection
        // (and redelivers the StateFlow's current value) on every STARTED re-entry, which can
        // fire multiple times in quick succession during Activity recreation. This must collect
        // exactly once per Fragment instance since it drives non-idempotent side effects
        // (image load/dispose, page scroll).
        // PHONE-ONLY sheet-state driver. This is the sole place the sheet is shown or hidden IN RESPONSE TO
        // THE CURRENT TRACK on phone. It is NOT the only caller of changePlayerState — FragmentUtils drives
        // STATE_EXPANDED from notification and intent entry points, and this file does too on user actions —
        // so if you are chasing an unexpected expand, look there first; what this block owns is the
        // track-driven show/hide. MainActivity's current-observer does NOT participate here — it is TV-only, gated by
        // R.id.tvMiniPlayer (see MainActivity.setupTvMiniPlayer). TV uses PlayerTvFragment + tvMiniPlayer;
        // Android Auto has no Fragment at all. So changes in this block affect phone only.
        // Verified 2026-08-23, with commit references so this is checkable rather than folklore:
        // setupTvMiniPlayer() early-returns on `R.id.tvMiniPlayer ?: return` (that id exists only in
        // layout-land-television), and it has done so since the method was created in 75501299
        // (2026-05-26). The RESUMED-gated STATE_COLLAPSED transition inside it was added LATER, in
        // d4c3b9bb (2026-06-08), whose session notes describe it as a PHONE cold-start fix ("blank Now
        // Playing bar") — but it went into the already-TV-gated method, so it has never executed on a
        // phone. Do not reason about phone sheet state from that guard. Approaching from the phone side
        // and assuming otherwise cost real debugging time on 2026-08-23; the mirror of this note, written
        // after the same mistake from the TV side, is at MainActivity:242-249.
        lifecycleScope.launch {
            viewModel.playerState.current.collectLatest {
                uiViewModel.run {
                    // Persistent transport bar (Spotify / YouTube Music / Apple Music model): the mini bar is
                    // shown whenever there is a current track and hidden ONLY when the queue empties. There is
                    // no dismiss gesture — the sheet is non-hideable while shown (applyPlayerBehaviorState).
                    // This is a pure current-STATE rule, not an edge: the first non-null emission shows
                    // COLLAPSED, so a cold-start restore (current is set before this Fragment subscribes) needs
                    // no prior null and no dependence on when the sheet settles. playerSheetState is read only
                    // to preserve a user's EXPANDED and to avoid churn when the bar is already shown.
                    if (it == null) changePlayerState(STATE_HIDDEN)
                    else if (playerSheetState.value == STATE_HIDDEN) changePlayerState(STATE_COLLAPSED)
                }
                submit()
                it?.mediaItem ?: return@collectLatest
                binding.applyCurrent(it.mediaItem)
                loadCurrentBackground(it.mediaItem)
                // ═══ THIRD ATTEMPT ON THE SCREEN-OFF STALE COVER BUG. Read before changing. ═══
                // The two before it are REFUTED, both shipped in f2b661b0 (build 1057) and both live in
                // 1058 and 1059 while the bug survived:
                //   the paint guard (a superseded load must not paint) - so it is NOT a stale delivery
                //     overwriting a correct one. The guard stays because it is correct anyway;
                //   a resume-time unconditional re-issue - so re-issuing at ON_START does not cure it.
                //     Removed entirely rather than narrowed; it had a failed experiment behind it.
                //
                // WHAT THIS TESTS, and it is ONE property: a request that is ALREADY AT THE LIFECYCLE GATE
                // when the gate opens. Nothing executes while the screen is off - every enqueued load
                // awaitStarted()s on the Activity lifecycle (ImageUtils:140), the art_probe included, so
                // "issued in the dark" was never the distinguishing trait and an earlier reading of mine
                // that said so was wrong. What the probe had was a request ENQUEUED per dark advance, so at
                // ON_START one already existed for the current track; the recorded consequence was bind
                // resolving src=MEMORY_CACHE in ~2ms at a healthy wake. The ViewHolder enqueues NOTHING
                // while dark: its only two entry points are bind (onBindViewHolder) and retryLoad
                // (onViewAttachedToWindow), both driven by a layout traversal that a STOPPED Activity never
                // performs - and at wake both are guarded and may decline to issue at all.
                // NOTE the corollary, which the smooth-scroll gate in submit() got wrong until 2026-09-03:
                // "stopped" here means THIS WINDOW gets no traversals and no frames, and that is true of a
                // BACKGROUNDED app whose display is fully on. A display-state check alone reads that case
                // as "frames are flowing" and is wrong about it; the gate now also requires isResumed.
                //
                // TWO EXISTENCE PROOFS, and what they share. The mini bar's cover (loaded into
                // collapsedTrackCover by FragmentPlayerBinding.applyCurrent) has
                // never had this bug, and neither did art_probe. Both load from THIS collector by identity
                // into a non-recycled view; both use loadWithThumb's lambda target, so NEITHER gets
                // requestManager coalescing - which rules coalescing out as the discriminator. The property
                // they share with each other and not with the ViewHolder is issuance from `current`.
                //
                // CLEAR OF THE RESUME-TIME TRAP. A prior resume-time re-commit made things worse, a
                // permanent one-behind rather than an intermittent one: the onResume doOnPreDraw block
                // added by cf60b564 (2026-07-28) and reverted by 60ab8d0c (2026-07-30), whose subject is
                // "revert album-art onResume re-commit". (An earlier comment here cited "aecc6700,
                // reverted 3 Aug by 25fae92a" — VERIFIED WRONG 2026-09-03: aecc6700 is 2026-07-13 and
                // 25fae92a is 2026-07-14, and `git log -S "doOnPreDraw"` lists neither. Commit hashes in
                // comments are checkable; check them.) This is not one: it writes no page position, touches no
                // ViewPager state, and is not a resume-time action - it runs when `current` changes.
                //
                // COST is one decode per advance into a ~38MB memory cache. Deliberately not engineered
                // around: the 1032/1039 OOMs were the service-restart loop, not bitmaps, and toMaxRes's
                // 1920x1920 rewrite is TV only.
                //
                // ⚠️ IF THIS FAILS, the next suspect is NOT the cache. It is that the holder declines to
                // issue at wake - retryLoad returns early on `coverDrawable != null`, bind on
                // `item?.mediaId == lastBoundMediaId` - in which case a warm cache is never consulted and
                // this refutes for a reason unrelated to warming.
                context?.let { ctx -> it.mediaItem.track.cover.warmMemoryCache(ctx) }
            }
        }

        // The wave's primary driver, and deliberately a GATED observer rather than a raw launch:
        // ContextUtils.observe is flowWithLifecycle(STARTED), so it re-subscribes on every ON_START and a
        // StateFlow replays its current value. That makes this LEVEL-driven — a missed edge self-corrects
        // at the next wake instead of leaving the wave wrong indefinitely, which is what the previous
        // edge-only wiring off the `current` collector did.
        observe(viewModel.playWhenReady) { updateWaveMotion() }

        // ⚠️ DELIBERATELY UNGATED — DO NOT REPLACE WITH observe(). 2026-09-04.
        //
        // WHAT THE GATE WAS FOR, AND HOW I KNOW: nothing specific. `observe` is ContextUtils.observe =
        // flowWithLifecycle(STARTED), the house default for collecting in a Fragment; both queueFlow
        // collectors used it because that is what everything here uses. PlayerViewModel's note on queueFlow
        // treats the gating as the PROBLEM, not as a decision, and recommends driving new work off the
        // `current` collector instead. There is no commit adding the gate to fix anything — it was never
        // not there.
        //
        // WHAT IT COSTS: queueFlow is a MutableSharedFlow<Unit> with replay = 0. A stopped Activity has no
        // subscriber, and such a flow DROPS an emission outright rather than deferring it. So the queue
        // correction published 50ms after a backgrounded advance is destroyed, the adapter keeps the
        // departed track as a real extra page, and nothing regenerates the lost signal: `viewModel.queue`
        // is already correct by then, so no further fullQueueFlow write occurs until the next timeline
        // change. Confirmed on device 2026-09-04: after ANY backgrounded advance — natural timeout or a
        // power-button press alike — the previous track's page is still swipeable at wake.
        //
        // WHY THIS IS NOT THE replay = 1 THAT WAS REVERTED (2026-08-28, cold-start hang): replay changes
        // what a LATE subscriber sees at subscribe time, which is what raced the restore block. Ungating
        // adds no replay and no buffer; it only keeps a subscriber alive while stopped, so emit() has
        // somewhere to go. Nothing about cold-start subscribe timing changes.
        //
        // WHY IT DOES NOT REINTRODUCE THE JUNE 11 TEARING: that defect was changeQueue()'s incremental
        // removeMediaItems/addMediaItems each firing a separate onTimelineChanged and rendering
        // intermediate torn-down queue states. The 50ms debounce that fixed it lives UPSTREAM, in
        // PlayerEventListener.emitFullQueue, which cancels its pending write on every new timeline change.
        // Only the coalesced result ever reaches queueFlow, gated or not. The debounce is untouched.
        //
        // WHY IT DOES NOT REINTRODUCE THE JUNE 21-26 RE-DELIVERY: that was observe() on `current`, a
        // StateFlow, which REPLAYS its latest value to each re-subscription, so applyCurrent()/submit()
        // re-fired on every ON_START during Activity recreation. A replay-0 SharedFlow replays nothing on
        // re-subscribe — which is precisely why this one loses emissions instead of duplicating them. The
        // two flows fail in opposite directions under the same operator.
        //
        // viewLifecycleOwner.lifecycleScope, NOT lifecycleScope: this is a view-bound collector and must die
        // with the view. (The sibling `current` collector above uses the FRAGMENT scope while being started
        // from onViewCreated — if the view is ever recreated without the fragment being destroyed, that one
        // stacks. Pre-existing; not changed here, but do not copy it.)
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.queueFlow.collect { submit() }
        }
        observe(viewModel.browser) { controller ->
            if (controller != null && viewModel.queue.isNotEmpty() && adapter.currentList.isEmpty()) {
                submit()
            }
        }

        val playPauseListener = CheckBoxListener { viewModel.setPlaying(it) }
        binding.playerControls.trackPlayPause
            .addOnCheckedStateChangedListener(playPauseListener)
        binding.playerCollapsedContainer.collapsedTrackPlayPause
            .addOnCheckedStateChangedListener(playPauseListener)
        observe(viewModel.playWhenReady) {
            binding.run {
                playPauseListener.enabled = false
                playerControls.trackPlayPause.isChecked = it
                playerCollapsedContainer.collapsedTrackPlayPause.isChecked = it
                playPauseListener.enabled = true

                val isBuffering = viewModel.buffering.value && it
                playerControls.playingIndicator.alpha = if (isBuffering) 1f else 0f
                playerCollapsedContainer.collapsedPlayingIndicator.alpha = if (isBuffering) 1f else 0f
            }
        }
        observe(viewModel.buffering) {
            val playWhenReady = viewModel.playWhenReady.value
            val isBuffering = it && playWhenReady
            binding.playerControls.playingIndicator.alpha = if (isBuffering) 1f else 0f
            binding.playerCollapsedContainer.collapsedPlayingIndicator.alpha = if (isBuffering) 1f else 0f
        }

        observe(viewModel.progress) { (curr, buff) ->
            binding.playerCollapsedContainer.run {
                collapsedBuffer.progress = buff.toInt()
                collapsedSeekbar.progress = curr.toInt()
            }
            binding.playerControls.run {
                if (!seekBar.isPressed) {
                    bufferBar.progress = buff.toInt()
                    seekWaveBar.progress = curr.toInt()
                    seekBar.value = max(0f, min(curr.toFloat(), seekBar.valueTo))
                    trackCurrentTime.text = curr.toTimeString()
                }
            }
        }

        // Duration comes from a COMBINE of totalDuration + current, not totalDuration alone. On cold start
        // player.duration is TIME_UNSET (unprepared) so totalDuration stays null, and its null->null is
        // conflated to no emission — but the restored track carries a known duration. combine re-fires when
        // current arrives, so the `?: current.track.duration` fallback actually evaluates instead of being
        // stranded behind a totalDuration emission that never comes. Precedence stays totalDuration-first.
        // DELIBERATE MIRROR of PlayerTvFragment's duration observer — keep the two in sync; each writes its
        // own views (phone: playerControls + collapsed bar; TV: tvSeekBar/tvTotalTime/tvBufferBar).
        observe(combine(viewModel.totalDuration, viewModel.playerState.current) { total, current ->
            total ?: current?.track?.duration ?: 0L
        }) { duration ->
            binding.playerCollapsedContainer.run {
                collapsedSeekbar.max = duration.toInt()
                collapsedBuffer.max = duration.toInt()
            }
            binding.playerControls.run {
                bufferBar.max = duration.toInt()
                seekWaveBar.max = duration.toInt()
                seekBar.apply {
                    value = max(0f, min(value, duration.toFloat()))
                    valueTo = 1f + duration
                }
                trackTotalTime.text = duration.toTimeString()
            }
        }


        val repeatModes = listOf(REPEAT_MODE_OFF, REPEAT_MODE_ALL, REPEAT_MODE_ONE)
        val animatedVectorDrawables = requireContext().run {
            fun asAnimated(id: Int) =
                AppCompatResources.getDrawable(this, id) as AnimatedVectorDrawable
            listOf(
                asAnimated(R.drawable.ic_repeat_one_to_repeat_off_40dp),
                asAnimated(R.drawable.ic_repeat_off_to_repeat_40dp),
                asAnimated(R.drawable.ic_repeat_to_repeat_one_40dp)
            )
        }
        val drawables = requireContext().run {
            fun asDrawable(id: Int) = AppCompatResources.getDrawable(this, id)!!
            listOf(
                asDrawable(R.drawable.ic_repeat_off_40dp),
                asDrawable(R.drawable.ic_repeat_40dp),
                asDrawable(R.drawable.ic_repeat_one_40dp),
            )
        }

        binding.playerControls.trackRepeat.icon =
            drawables[repeatModes.indexOf(viewModel.repeatMode.value)]

        fun changeRepeatDrawable(repeatMode: Int) = binding.playerControls.trackRepeat.run {
            val index = repeatModes.indexOf(repeatMode)
            icon = animatedVectorDrawables[index]
            (icon as Animatable).start()
        }

        binding.playerControls.run {
            seekBar.apply {
                addOnChangeListener { _, value, fromUser ->
                    if (fromUser) {
                        trackCurrentTime.text = value.toLong().toTimeString()
                        // The wave is a separate view from the Slider, so it is NOT carried along by the
                        // drag. The progress observer above is gated on !seekBar.isPressed, so during a
                        // gesture nothing else updates it and the wave would visibly lag the thumb for the
                        // whole drag. Drive it from here so the two stay together; fromUser keeps this off
                        // the programmatic path, which the observer already owns.
                        seekWaveBar.progress = value.toInt()
                    }
                }
                addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                    override fun onStartTrackingTouch(slider: Slider) = Unit
                    override fun onStopTrackingTouch(slider: Slider) =
                        viewModel.seekTo(slider.value.toLong())
                })
                val uiModeManager =
                    requireContext().getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
                val isTV = requireContext().packageManager
                    .hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
                    uiModeManager.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
                if (isTV) {
                    setOnKeyListener { _, keyCode, event ->
                        if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                        when (keyCode) {
                            KeyEvent.KEYCODE_DPAD_LEFT -> { viewModel.seekToAdd(-10_000); true }
                            KeyEvent.KEYCODE_DPAD_RIGHT -> { viewModel.seekToAdd(10_000); true }
                            else -> false
                        }
                    }
                }
            }

            trackNext.setOnClickListener {
                viewModel.next()
                (trackNext.icon as Animatable).start()
            }
            observe(viewModel.nextEnabled) { trackNext.isEnabled = it }

            trackPrevious.setOnClickListener {
                viewModel.previous()
                (trackPrevious.icon as Animatable).start()
            }
            observe(viewModel.previousEnabled) { trackPrevious.isEnabled = it }

            val shuffleListener = CheckBoxListener { viewModel.setShuffle(it) }
            trackShuffle.addOnCheckedStateChangedListener(shuffleListener)
            observe(viewModel.shuffleMode) {
                shuffleListener.enabled = false
                trackShuffle.isChecked = it
                shuffleListener.enabled = true
            }

            trackRepeat.setOnClickListener {
                val mode = when (viewModel.repeatMode.value) {
                    REPEAT_MODE_OFF -> REPEAT_MODE_ALL
                    REPEAT_MODE_ALL -> REPEAT_MODE_ONE
                    else -> REPEAT_MODE_OFF
                }
                changeRepeatDrawable(mode)
                viewModel.setRepeat(mode)
            }
            observe(viewModel.repeatMode) { changeRepeatDrawable(it) }

            trackSubtitle.setOnClickListener {
                QualitySelectionBottomSheet().show(parentFragmentManager, null)
            }
            observe(viewModel.serverAndTracks) { (tracks, server, index) ->
                // HIDE, DO NOT BLANK, and do not clear the text. Between an item transition and its
                // onTracksChanged `tracks` is null (the stamp does not match yet), and this window is the
                // full resolve time - 2.4-3.8s measured. Writing "Unknown quality" there would flash it on
                // every advance, which is worse than the stale value it replaced. Blanking is no better:
                // this view has a 40dp minHeight and a shape_pill background, so an empty string leaves an
                // empty capsule that reads as a failure. Absent reads as "not known yet", which is true.
                // The text is deliberately left in place while hidden so a re-show does not repaint.
                val details = tracks?.getDetailsFormatFirst(requireContext(), server, index)
                    ?.joinToString(" ⦿ ")?.takeIf { it.isNotBlank() }
                if (details != null) trackSubtitle.text = details
                trackSubtitle.isVisible = details != null
            }
        }
    }

    private val likeListener = CheckBoxListener { viewModel.likeCurrent(it) }

    // Ken Burns background is driven by CURRENT TRACK IDENTITY (loadCurrentBackground), like the mini bar —
    // NOT by the attached page's coverDrawable, which is null/detached after a screen-off auto-advance and
    // left it stale + downstream of the pager. Guarded by lastBlurredItemId so re-applying on every resume is
    // a no-op when the track is unchanged.
    private var lastBlurredItemId: String? = null
    private fun loadCurrentBackground(item: MediaItem?) {
        val bg = binding?.bgImage ?: return
        val context = context ?: return
        if (!context.showBackground()) {
            bg.setImageDrawable(null)
            lastBlurredItemId = null
            return
        }
        val itemId = item?.mediaId
        if (itemId == lastBlurredItemId) return
        lastBlurredItemId = itemId
        bg.loadBlurred(item?.track?.cover, 8f)
    }

    private fun configureColors() {
        observe(viewModel.playerState.current) { adapter.onCurrentUpdated() }
        var last: Drawable? = null
        // Colors/dynamic-theming still derive from the attached page drawable; only the Ken Burns background
        // was moved to identity-based loading (loadCurrentBackground).
        // Captured once at setup (onViewCreated, provably attached) rather than per-invocation. This
        // listener is NOT lifecycle-gated: PlayerTrackAdapter.applyDrawable() invokes it from the
        // ViewHolder's async cover-load callback, which can land after onDestroyView (activity recreation),
        // and requireContext() would throw there. isDynamic()/getColorsFrom() only need a Context, not an
        // attached one. Same fix as PlayerTvFragment.configureColors — the phone site has the identical
        // shape and simply hasn't been the one to crash yet.
        val listenerContext = requireContext()
        adapter.currentDrawableListener = { drawable ->
            if (last != drawable) {
                uiViewModel.playerDrawable.value = drawable
                val colors = if (listenerContext.isDynamic())
                    listenerContext.getColorsFrom(drawable?.toBitmap()) else null
                uiViewModel.playerColors.value = colors
                // After the work, for the same reason as PlayerTvFragment's lastDrawable: an early exit
                // must not leave the cache claiming this drawable was applied. Wholly synchronous here, so
                // moving it is free.
                last = drawable
            }
        }
        val bufferView =
            binding?.playerView?.findViewById<ProgressBar>(androidx.media3.ui.R.id.exo_buffering)
        observe(uiViewModel.playerColors) {
            val context = requireContext()
            if (context.isPlayerColor() && context.isDynamic()) {
                val newAccent = it?.accent
                if (uiViewModel.lastPlayerAccentColor != newAccent) {
                    // See PlayerTvFragment.configureColors for the full reasoning: written only where the
                    // recreate actually runs, and inside the withResumed block so no cancellation point
                    // separates the recreate from the flag recording it. Pre-assigning left the deferred
                    // branch able to drop the recreate while claiming it happened — and since the accent is
                    // seeded into the theme only by MainActivity.applyUiChanges at activity creation, that
                    // stales the theme until an unrelated recreate, with no retry (the guard blocks it).
                    if (requireActivity().lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                        requireActivity().recreate()
                        uiViewModel.lastPlayerAccentColor = newAccent
                    } else {
                        lifecycleScope.launch {
                            lifecycle.withResumed {
                                requireActivity().recreate()
                                uiViewModel.lastPlayerAccentColor = newAccent
                            }
                        }
                    }
                    return@observe
                }
            }
            val colors = it ?: context.defaultPlayerColors()
            val binding = binding!!
            adapter.onColorsUpdated()

            binding.run {
                val color = if (requireContext().isDynamic()) colors.accent
                else colors.background
                root.setBackgroundColor(color)
                val backgroundState = ColorStateList.valueOf(colors.background)
                bgGradient.imageTintList = backgroundState
                bgCollapsed.backgroundTintList = backgroundState
                bufferView?.indeterminateDrawable?.setTint(colors.accent)
                expandedToolbar.run {
                    setTitleTextColor(colors.onBackground)
                    setSubtitleTextColor(colors.onBackground)
                }
            }

            binding.playerCollapsedContainer.run {
                collapsedPlayingIndicator.setIndicatorColor(colors.accent)
                collapsedSeekbar.setIndicatorColor(colors.accent)
                // collapsedBuffer.setIndicatorColor is DELIBERATELY ABSENT - do not restore it without
                // also changing the layout. collapsedBuffer's indicator is transparent in XML, and a
                // runtime setIndicatorColor here would override that and paint the buffer line straight
                // back. Only the rail is tinted now. Same treatment as bufferBar below and tvBufferBar in
                // PlayerTvFragment; keep all three in step.
                // BUFFERING IS NOW SHOWN NOWHERE IN THE APP - the full screen and TV players lost it in
                // 1055 for the wavy seek bar, and this was the last surface still drawing one. See the
                // note on collapsed_buffer in item_player_collapsed_controls.xml for the full reasoning
                // and for why this view still exists (it carries the rail, its 0.5 alpha and its zero
                // gap size, none of which can move to collapsed_seekbar).
                collapsedBuffer.trackColor = colors.onBackground
                collapsedTrackTitle.setTextColor(colors.onBackground)
                collapsedTrackArtist.setTextColor(colors.onBackground)
            }

            binding.playerControls.run {
                // The accent goes on the WAVE, not the Slider's active track. seekBar.trackColorActive is
                // transparent in XML so the wave is the only thing drawing the position line — but a
                // runtime tint would override that XML and paint a straight line back under the wave.
                // ⚠⚠ NEUTRAL BY DESIGN - DO NOT RE-TINT THIS FROM PlayerColors.accent.
                // It will look plain next to the rest of the player and that is the trade, made knowingly.
                //
                // ⚠⚠ 1. THE COUPLING WAS STRUCTURAL, NOT A TUNING PROBLEM. The background here is
                // a BLURRED VERSION OF THE SAME ARTWORK the accent is extracted from (bgImage via
                // ImageUtils.loadBlurred; accent via Palette in PlayerColors.getColorsFrom). On a monochrome
                // cover both land in the same region of colour space BY CONSTRUCTION, and no swatch choice
                // escapes it - lightVibrant, darkVibrant and the muted fallbacks are all drawn from the same
                // pixels. OBSERVED: a warm red cover (Ray Lamontagne, "Supernova") rendered the played wave
                // as dark red on red - a smudge, not a progress indicator - while the theme-derived rail
                // beside it stayed perfectly legible. The two halves of one control, opposite problems,
                // same track.
                //
                // ⚠️ 2. THE PAUSED STATE IS THE ACCESSIBILITY BASELINE, NOT THE PLAYING ONE.
                // updateWaveMotion sets waveAmplitude = 0 on pause, so the wave flattens to a plain 4dp
                // line and loses the shape cue that helps it read at low contrast. Motion cannot be the
                // sole means of making a control perceivable (WCAG 1.4.11, which also sets the 3:1 non-text
                // contrast threshold). Judge any future tint against the PAUSED render.
                //
                // ⚠️ 3. FOUR SHIPPING PLAYERS BREAK THE COUPLING THE SAME WAY - Spotify, Apple
                // Music, YouTube Music, Tidal: the background carries the artwork, the progress control is a
                // fixed neutral. None of them tints the bar.
                //
                // ⚠️ 4. AND GLADIX ALREADY AGREED WITH THEM EVERYWHERE ELSE: the Android Auto and
                // lock-screen renderings use a fixed-colour bar. The in-app player was the odd one out
                // within its own app.
                //
                // ⚠⚠ WHY amoled_fg AND NOT ?attr/colorOnSurface, WHICH THE RAIL USED TO USE:
                // colorOnSurface is WALLPAPER-DERIVED under DynamicColors on Android 12+, so it carries a
                // tint unrelated to the artwork and DIFFERS PER DEVICE. amoled_fg is a pure neutral -
                // @color/black in light, @color/white in night - and is already this player's foreground
                // token (transport buttons, artist name, trackSubtitle, icon tints). ONE SOURCE FOR BOTH
                // HALVES means they cannot drift apart on someone else's phone.
                // Verified on device in LIGHT mode: the player renders light, the transport buttons are
                // black, and they read clearly against the dimmed blurred cover - so the buttons were
                // already the experiment for this colour in both themes.
                //
                // ⚠️ PARKED, NOT BUILT: an AMBIENT-GLOW alternative - a soft blurred
                // artwork-tinted shadow BEHIND a neutral wave. It would keep artwork presence on the control
                // without putting colour where contrast has to be. More work than this and unverified; it is
                // the answer if the tinting is ever missed.
                //
                // ⚠️ SCOPE: THIS ROW ONLY. PlayerColors.accent is untouched and still tints the
                // blurred background, the heart when checked, the playing indicator, the collapsed
                // mini-player, MainActivity's system chrome and TV's root background.
                // [CORRECTED 2026-09-17] THIS LIST USED TO INCLUDE "the codec pill". IT NEVER TOOK
                // ACCENT - it took PlayerColors.background, as the note at trackSubtitle below has
                // always said. The two comments contradicted each other; this one was wrong.
                val seekNeutral = ContextCompat.getColor(requireContext(), R.color.amoled_fg)
                seekWaveBar.setIndicatorColor(seekNeutral)
                // The heart is the one control here that shows a persistent CHOICE, so it gets the
                // accent when checked and stays amoled_fg otherwise (see color/button_player_heart.xml
                // for why accent's weak-palette fallback is acceptable on a glyph but not on a fill).
                trackHeart.buttonTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(colors.accent, colors.onBackground)
                )
                // ⚠⚠ THE PILL IS NEUTRAL, NOT ARTWORK-TINTED, AND BOTH HALVES COME FROM THE SAME
                // TOKEN AS THE WAVE AND THUMB. It used to take PlayerColors.background with
                // onBackground text - correct while the wave and thumb were artwork-tinted too, and
                // wrong the moment they went neutral, because it left the pill as the ONLY coloured
                // element in the seek row. It is also the least important thing in that row (a
                // secondary affordance opening QualitySelectionBottomSheet), so it was pulling the
                // most attention for the least reason. Softening the artwork colour would have left
                // it the odd one out, only quieter; making it neutral answers the actual complaint.
                //
                // ⚠⚠ 0x1F IS 12%, AND 0x33 (20%) IS THE CEILING. THE REASON IS NOT TASTE -
                // THE FILL AND THE TEXT ARE NOW THE SAME TOKEN, so raising the alpha moves the
                // backdrop TOWARD the text colour and CONTRAST FALLS AS ALPHA RISES. That is the
                // opposite of the usual intuition ("more opaque = more readable"), which is exactly
                // how someone would discover it by making it worse. Written down so nobody has to.
                // ⚠️ AND IT IS THE ONE NUMBER HERE THAT NEEDS A LOOK ON DEVICE RATHER THAN A
                // DERIVATION: this is a scrim, so its apparent strength depends on the dimmed blurred
                // cover behind it - 12% over a busy bright cover reads differently than over a flat
                // dark one. If it reads too faint, 0x33 and stop.
                // ⚠️ WHAT THIS BUYS BESIDES THE LOOK: the Palette coupling is gone. The old
                // pairing was onBackground = bgSwatch.bodyTextColor, a contrast Palette computes
                // AGAINST bgSwatch.rgb - so any change to the fill silently invalidated the
                // guarantee, and how badly depended on the artwork. Two fixed tokens cannot drift,
                // per device or per track.
                val pillScrim = ColorUtils.setAlphaComponent(seekNeutral, 0x1F)
                trackSubtitle.backgroundTintList = ColorStateList.valueOf(pillScrim)
                trackSubtitle.setTextColor(seekNeutral)
                // The thumb MUST match the wave: it reads as the LEADING EDGE of the played portion, and
                // a coloured thumb over a neutral track is not a pattern any shipping player uses.
                seekBar.thumbTintList = ColorStateList.valueOf(seekNeutral)
                playingIndicator.setIndicatorColor(colors.accent)
                // bufferBar.setIndicatorColor is DELIBERATELY ABSENT — do not restore it without also
                // changing the layout. bufferBar's indicator is transparent in XML because
                // DeterminateDrawable never assigns startFraction (it stays 0f), so the indicator can only
                // fill from the left edge and drew a solid accent line under the whole played portion,
                // visible through the wave. A runtime setIndicatorColor here would override that XML and
                // paint it straight back. Only the rail is tinted now. See the note on bufferBar in
                // item_player_controls.xml. PlayerTvFragment carries the same omission for tvBufferBar —
                // keep the two in step.
                bufferBar.trackColor = colors.onBackground
                trackCurrentTime.setTextColor(colors.onBackground)
                trackTotalTime.setTextColor(colors.onBackground)
                trackTitle.setTextColor(colors.onBackground)
                trackArtist.setTextColor(colors.onBackground)
            }
        }
    }

    private fun FragmentPlayerBinding.applyCurrent(item: MediaItem) {
        val track = item.track
        val extId = item.extensionId
        expandedToolbar.run {
            val itemContext = item.context
            title = if (itemContext != null) context.getString(R.string.playing_from) else null
            subtitle = itemContext?.title
            val navigableContext = when (itemContext) {
                is EchoMediaItem.Lists, is Artist -> itemContext
                else -> null
            }
            setOnClickListener(if (navigableContext != null) View.OnClickListener {
                openItem(extId, navigableContext)
            } else null)
        }
        // Overflow moved out of the toolbar menu (PlayerToolbarStyle no longer sets `menu`) and down
        // beside the heart, so it is a plain click listener now rather than setOnMenuItemClickListener.
        playerControls.trackMore.setOnClickListener { onMoreClicked(item) }
        // Playing extension's icon, top right. Resolved from the item's extensionId - the PLAYING
        // extension - not from extensionLoader.current, which is the BROWSING one and would swap the
        // icon mid-track when the user changes tabs. loadAsCircle keeps ic_extension_32dp when the
        // extension has none, so the slot never goes empty.
        lifecycleScope.launch {
            val icon = viewModel.getExtensionIcon(extId)
            icon.loadAsCircle(extensionIcon, R.drawable.ic_extension_32dp) {
                // Only overwrite on a real image: a null delivery must leave the XML fallback in place
                // rather than blanking the slot.
                if (it != null) extensionIcon.setImageDrawable(it)
            }
        }
        playerCollapsedContainer.run {
            collapsedTrackTitle.text = track.title
            collapsedTrackArtist.text = track.artists.joinToString(", ") { it.name }
            val thumb = collapsedTrackCover.drawable
                ?: item.unloadedCover?.getCachedDrawable(requireContext())
            track.cover.loadWithThumb(collapsedTrackCover, thumb) {
                val image = it
                    ?: ResourcesCompat.getDrawable(resources, R.drawable.ic_music, context.theme)
                setImageDrawable(image)
            }
        }
        playerControls.run {
            trackTitle.text = track.title
            trackTitle.marquee()
            val artists = track.artists
            val artistNames = artists.joinToString(", ") { it.name }
            val span = SpannableString(artistNames)

            artists.forEach { artist ->
                val start = artistNames.indexOf(artist.name)
                val end = start + artist.name.length
                val clickableSpan = SimpleItemSpan(trackArtist.context) {
                    openItem(extId, artist)
                }
                runCatching {
                    span.setSpan(
                        clickableSpan, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }

            trackArtist.text = span
            trackArtist.movementMethod = LinkMovementMethod.getInstance()
            likeListener.enabled = false
            trackHeart.isChecked = item.isLiked
            likeListener.enabled = true
            lifecycleScope.launch {
                val isTrackClient = viewModel.isLikeClient(item.extensionId)
                trackHeart.isVisible = isTrackClient
            }
        }
    }

    private fun openItem(extension: String, item: EchoMediaItem) {
        requireActivity().openFragment<MediaFragment>(
            null, MediaFragment.getBundle(extension, item, false)
        )
    }

    private fun onMoreClicked(item: MediaItem) {
        MediaMoreBottomSheet.show(
            this, requireActivity().supportFragmentManager,
            R.id.navHostFragment, item.extensionId, item.track, item.isLoaded, true
        )
    }

    private fun Player?.hasVideo() =
        this?.currentTracks?.groups.orEmpty().any { it.type == C.TRACK_TYPE_VIDEO }

    private fun applyVideoVisibility(visible: Boolean) {
        binding?.playerView?.isVisible = visible
        binding?.bgImage?.isVisible = !visible
        if (requireContext().isLandscape()) return
        binding?.playerControls?.trackCoverPlaceHolder?.isVisible = visible
        adapter.updatePlayerVisibility(visible)
    }

    private var oldBg: Streamable.Media.Background? = null
    private var backgroundPlayer: Player? = null

    @OptIn(UnstableApi::class)
    private fun applyPlayer() {
        // Canvas/video is fullscreen-only. When NOT expanded (mini-bar / hidden), DETACH the video surface so
        // the Canvas/video stops rendering in the collapsed bar. `playerView.player = null` clears ONLY the
        // view's video surface (clearVideoSurface); the actual playback lives in the service player behind
        // the MediaController (mainPlayer), so AUDIO IS UNAFFECTED — it keeps playing while collapsed. The
        // static bg_image (blurred art) shows instead. Video/Canvas re-attaches on expand (below), rendering
        // at the live position. Setting isVisible alone did NOT stop the SurfaceView-backed PlayerView —
        // detaching the surface is the reliable stop (the true analog of KenBurns' animator pause).
        if (uiViewModel.playerSheetState.value != STATE_EXPANDED) {
            binding?.playerView?.player = null
            backgroundPlayer?.playWhenReady = false   // no-op for the main-player video path (null); pauses
                                                      // the silent Canvas loop in the background-streamable case
            binding?.playerView?.isVisible = false
            binding?.bgImage?.isVisible = true
            return
        }
        val mainPlayer = viewModel.browser.value
        val background = viewModel.playerState.current.value?.mediaItem?.background
        val visible = if (mainPlayer.hasVideo()) {
            binding?.playerView?.player = mainPlayer
            binding?.playerView?.resizeMode = RESIZE_MODE_FIT
            backgroundPlayer?.release()
            backgroundPlayer = null
            true
        } else if (background != null) {
            if (oldBg != background || backgroundPlayer == null) {
                oldBg = background
                backgroundPlayer?.release()
                backgroundPlayer = getPlayer(requireContext(), viewModel.cache, background)
            }
            binding?.playerView?.player = backgroundPlayer
            binding?.playerView?.resizeMode = RESIZE_MODE_ZOOM
            backgroundPlayer?.playWhenReady = true   // resume a Canvas that was paused on collapse
            true
        } else {
            backgroundPlayer?.release()
            backgroundPlayer = null
            binding?.playerView?.player = null
            false
        }
        applyVideoVisibility(visible)
    }

    @OptIn(UnstableApi::class)
    private fun configureBackgroundPlayerView() {
        binding?.playerView?.subtitleView?.setStyle(
            CaptionStyleCompat(
                Color.WHITE, Color.TRANSPARENT, Color.TRANSPARENT,
                EDGE_TYPE_OUTLINE, Color.BLACK, null
            )
        )
        observe(viewModel.serverAndTracks) { applyPlayer() }
    }

    companion object {
        private fun Context.showBackground() = getSettings().showBackground()
        const val DYNAMIC_PLAYER = "dynamic_player"
        const val PLAYER_COLOR = "player_app_color"
        fun Context.isDynamic(): Boolean =
            getSettings().getBoolean(DYNAMIC_PLAYER, true)

        private fun Context.isPlayerColor() =
            getSettings().getBoolean(PLAYER_COLOR, false)

        @OptIn(UnstableApi::class)
        fun getPlayer(
            context: Context, cache: SimpleCache, video: Streamable.Media.Background,
        ): ExoPlayer {
            val cacheFactory = CacheDataSource
                .Factory().setCache(cache)
                .setUpstreamDataSourceFactory(
                    DefaultHttpDataSource.Factory()
                        .setDefaultRequestProperties(video.request.headers)
                )
            val factory = DefaultMediaSourceFactory(context)
                .setDataSourceFactory(cacheFactory)
            val player = ExoPlayer.Builder(context).setMediaSourceFactory(factory).build()
            player.setMediaItem(MediaItem.fromUri(video.request.url.toUri()))
            player.repeatMode = REPEAT_MODE_ONE
            player.volume = 0f
            player.prepare()
            player.play()
            return player
        }
    }
}