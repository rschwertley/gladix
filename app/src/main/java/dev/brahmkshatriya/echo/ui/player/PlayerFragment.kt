@file:Suppress("ASSIGNED_BUT_NEVER_ACCESSED_VARIABLE")

package dev.brahmkshatriya.echo.ui.player

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.view.KeyEvent
import android.graphics.Outline
import android.graphics.drawable.Animatable
import android.graphics.drawable.AnimatedVectorDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ProgressBar
import androidx.activity.OnBackPressedCallback
import androidx.annotation.OptIn
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.withResumed
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.core.net.toUri
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
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
import dev.brahmkshatriya.echo.ui.player.quality.FormatUtils.getDetails
import dev.brahmkshatriya.echo.ui.player.quality.QualitySelectionBottomSheet
import dev.brahmkshatriya.echo.utils.ContextUtils.emit
import dev.brahmkshatriya.echo.utils.ContextUtils.getSettings
import dev.brahmkshatriya.echo.utils.ContextUtils.observe
import dev.brahmkshatriya.echo.utils.image.ImageUtils.getCachedDrawable
import dev.brahmkshatriya.echo.utils.image.ImageUtils.loadBlurred
import dev.brahmkshatriya.echo.utils.image.ImageUtils.loadWithThumb
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
import dev.brahmkshatriya.echo.utils.ui.ViewPager2Utils.registerOnUserPageChangeCallback
import dev.brahmkshatriya.echo.utils.ui.ViewPager2Utils.supportBottomSheetBehavior
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.activityViewModel
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class PlayerFragment : Fragment() {
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

    override fun onPause() {
        super.onPause()
        binding?.bgImage?.pause()
    }

    override fun onResume() {
        super.onResume()
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
            currLeft = (leftPadding * inv).toInt()
            currRight = view.width - (rightPadding * inv).toInt()
            currRound = max(padding * inv, padding * uiViewModel.playerBackProgress.value * 2)
            view.invalidateOutline()
        }
        observe(uiViewModel.combined) {
            leftPadding = (if (view.context.isRTL()) it.end else it.start) + padding
            rightPadding = (if (view.context.isRTL()) it.start else it.end) + padding
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
            val (collapsedY, offset, collapsedOffset) = uiViewModel.run {
                if (playerSheetState.value == STATE_EXPANDED) {
                    val offset = moreSheetOffset.value
                    Triple(systemInsets.value.top, offset, if (isLandscape) 0f else offset)
                } else {
                    val offset = 1 - max(0f, playerSheetOffset.value)
                    Triple(-collapsedTopPadding, offset, offset)
                }
            }
            val collapsedInv = 1 - collapsedOffset
            binding.playerCollapsedContainer.root.run {
                translationY = collapsedY - collapseHeight * collapsedInv * 2
                alpha = collapsedOffset * 2
                translationZ = -1f * collapsedInv
            }
            binding.bgCollapsed.run {
                translationY = collapsedY - collapseHeight * collapsedInv * 2
                alpha = min(1f, collapsedOffset * 2) - 0.5f
            }
            val alphaInv = 1 - min(1f, offset * 3)
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
                val top = if (playerSheetState.value != STATE_EXPANDED) 0
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
            binding.playerCollapsedContainer.root.applyHorizontalInsets(insets)
            binding.playerControls.root.applyHorizontalInsets(
                insets,
                requireActivity().isLandscape()
            )
            val left = if (requireContext().isRTL()) system.end + extraEndPadding else system.start
            leftPadding = collapsedTopPadding + left
            val right = if (requireContext().isRTL()) system.start else system.end + extraEndPadding
            rightPadding = collapsedTopPadding + right
            updateCollapsed()
            adapter.insetsUpdated()
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

        var seenNonHidden = false
        observe(uiViewModel.playerSheetState) {
            updateCollapsed()
            if (isFinalState(it)) adapter.playerSheetStateUpdated()
            if (it == STATE_HIDDEN) {
                if (seenNonHidden) {
                    viewModel.clearQueue()
                    binding.bgImage.stop()
                }
            } else {
                seenNonHidden = true
                if (it == STATE_COLLAPSED) emit(uiViewModel.playerBgVisible, false)
            }
            when (it) {
                STATE_EXPANDED -> binding.bgImage.resume()
                else -> binding.bgImage.pause()
            }
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
        binding.playerCollapsedContainer.playerClose.setOnClickListener {
            uiViewModel.changePlayerState(STATE_HIDDEN)
        }
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
        viewPager.registerOnUserPageChangeCallback { pos, isUser ->
            val index = viewModel.playerState.current.value?.index
            if (index != pos && isUser) viewModel.seek(pos)
        }

        fun submit() {
            val capturedIndex = (viewModel.playerState.current.value?.index ?: -1).takeIf { it != -1 }
            adapter.submitList(viewModel.queue) {
                val index = capturedIndex ?: return@submitList
                val viewPager = binding?.viewPager ?: return@submitList
                val current = viewPager.currentItem
                val smooth = !isInitialLoad && abs(index - current) <= 1
                adapter.onCurrentChanged(index)
                isInitialLoad = false
                if (!viewPager.isLaidOut) viewPager.setCurrentItem(index, smooth)
                else {
                    pendingPageScroll?.let { viewPager.removeCallbacks(it) }
                    val runnable = Runnable {
                        val liveIndex = viewModel.playerState.current.value?.index ?: index
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
        lifecycleScope.launch {
            viewModel.playerState.current.collectLatest {
                uiViewModel.run {
                    if (it == null) return@run changePlayerState(STATE_HIDDEN)
                    if (!isFinalState(playerSheetState.value)) return@run
                    changePlayerState(
                        if (playerSheetState.value != STATE_EXPANDED) STATE_COLLAPSED
                        else STATE_EXPANDED
                    )
                }
                submit()
                it?.mediaItem ?: return@collectLatest
                binding.applyCurrent(it.mediaItem)
            }
        }

        observe(viewModel.queueFlow) { submit() }
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
                    seekBar.value = max(0f, min(curr.toFloat(), seekBar.valueTo))
                    trackCurrentTime.text = curr.toTimeString()
                }
            }
        }

        observe(viewModel.totalDuration) {
            val duration = it ?: viewModel.playerState.current.value?.track?.duration ?: 0
            binding.playerCollapsedContainer.run {
                collapsedSeekbar.max = duration.toInt()
                collapsedBuffer.max = duration.toInt()
            }
            binding.playerControls.run {
                bufferBar.max = duration.toInt()
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
                    if (fromUser) trackCurrentTime.text = value.toLong().toTimeString()
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
                trackSubtitle.text = tracks?.getDetails(requireContext(), server, index)
                    ?.joinToString(" ⦿ ")?.takeIf { it.isNotBlank() }
            }
        }
    }

    private val likeListener = CheckBoxListener { viewModel.likeCurrent(it) }

    private fun configureColors() {
        observe(viewModel.playerState.current) { adapter.onCurrentUpdated() }
        var last: Drawable? = null
        var lastBlurredItemId: String? = null
        adapter.currentDrawableListener = { drawable ->
            if (last != drawable) {
                last = drawable
                val context = requireContext()
                uiViewModel.playerDrawable.value = drawable
                val colors =
                    if (context.isDynamic()) context.getColorsFrom(drawable?.toBitmap()) else null
                uiViewModel.playerColors.value = colors
                val currentItemId = viewModel.playerState.current.value?.mediaItem?.mediaId
                if (context.showBackground()) {
                    if (drawable == null) {
                        lastBlurredItemId = null
                    } else if (currentItemId != lastBlurredItemId) {
                        lastBlurredItemId = currentItemId
                        binding?.bgImage?.loadBlurred(drawable, 8f)
                    }
                } else {
                    binding?.bgImage?.setImageDrawable(null)
                }
            }
        }
        val bufferView =
            binding?.playerView?.findViewById<ProgressBar>(androidx.media3.ui.R.id.exo_buffering)
        observe(uiViewModel.playerColors) {
            val context = requireContext()
            if (context.isPlayerColor() && context.isDynamic()) {
                val newAccent = it?.accent
                if (uiViewModel.lastPlayerAccentColor != newAccent) {
                    uiViewModel.lastPlayerAccentColor = newAccent
                    if (requireActivity().lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                        requireActivity().recreate()
                    } else {
                        lifecycleScope.launch {
                            lifecycle.withResumed { requireActivity().recreate() }
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
                collapsedBuffer.setIndicatorColor(colors.accent)
                collapsedBuffer.trackColor = colors.onBackground
                collapsedTrackTitle.setTextColor(colors.onBackground)
                collapsedTrackArtist.setTextColor(colors.onBackground)
            }

            binding.playerControls.run {
                seekBar.trackActiveTintList = ColorStateList.valueOf(colors.accent)
                seekBar.thumbTintList = ColorStateList.valueOf(colors.accent)
                playingIndicator.setIndicatorColor(colors.accent)
                bufferBar.setIndicatorColor(colors.accent)
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
            setOnMenuItemClickListener {
                if (it.itemId != R.id.menu_more) return@setOnMenuItemClickListener false
                onMoreClicked(item)
                true
            }
        }
        playerCollapsedContainer.run {
            collapsedTrackTitle.text = track.title
            collapsedTrackArtist.text = track.artists.joinToString(", ") { it.name }
            val cachedCover = lastLoadedCollapsedCovers[item.mediaId]
            if (cachedCover != null) {
                collapsedTrackCover.setImageDrawable(cachedCover)
            } else {
                val thumb = collapsedTrackCover.drawable
                    ?: item.unloadedCover?.getCachedDrawable(requireContext())
                track.cover.loadWithThumb(collapsedTrackCover, thumb) {
                    val image = it
                        ?: ResourcesCompat.getDrawable(resources, R.drawable.ic_music, context.theme)
                    setImageDrawable(image)
                    if (it != null) cacheCollapsedCover(item.mediaId, it)
                }
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
        // Process-lifetime cache of the last successfully resolved mini-player cover per
        // mediaId, mirroring PlayerTrackAdapter's lastLoadedCovers. Survives Fragment
        // recreation (rotation), letting applyCurrent() apply an already-loaded cover
        // synchronously instead of re-issuing a Coil request for art that's already known.
        private const val MAX_CACHED_COLLAPSED_COVERS = 20
        private val lastLoadedCollapsedCovers = LinkedHashMap<String, Drawable>()

        private fun cacheCollapsedCover(mediaId: String, drawable: Drawable) {
            lastLoadedCollapsedCovers.remove(mediaId)
            lastLoadedCollapsedCovers[mediaId] = drawable
            while (lastLoadedCollapsedCovers.size > MAX_CACHED_COLLAPSED_COVERS) {
                val oldest = lastLoadedCollapsedCovers.keys.firstOrNull() ?: break
                lastLoadedCollapsedCovers.remove(oldest)
            }
        }

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