package dev.brahmkshatriya.echo.ui.player

import android.content.res.ColorStateList
import android.graphics.drawable.Animatable
import android.graphics.drawable.AnimatedVectorDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import androidx.media3.common.Player.REPEAT_MODE_ALL
import androidx.media3.common.Player.REPEAT_MODE_OFF
import androidx.media3.common.Player.REPEAT_MODE_ONE
import com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
import com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_HIDDEN
import com.google.android.material.slider.Slider
import kotlinx.coroutines.flow.combine
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.databinding.FragmentPlayerTvBinding
import dev.brahmkshatriya.echo.playback.MediaItemUtils.context
import dev.brahmkshatriya.echo.playback.MediaItemUtils.extensionId
import dev.brahmkshatriya.echo.playback.MediaItemUtils.isLiked
import dev.brahmkshatriya.echo.playback.MediaItemUtils.track
import dev.brahmkshatriya.echo.ui.common.UiViewModel
import dev.brahmkshatriya.echo.ui.player.PlayerColors.Companion.defaultPlayerColors
import dev.brahmkshatriya.echo.ui.player.PlayerColors.Companion.getColorsFrom
import dev.brahmkshatriya.echo.ui.player.quality.FormatUtils.getDetails
import dev.brahmkshatriya.echo.utils.ContextUtils.getSettings
import dev.brahmkshatriya.echo.utils.ContextUtils.observe
import dev.brahmkshatriya.echo.common.models.ImageHolder
import dev.brahmkshatriya.echo.common.models.ImageHolder.Companion.toImageHolder
import dev.brahmkshatriya.echo.utils.image.ImageUtils.loadDrawable
import dev.brahmkshatriya.echo.utils.image.ImageUtils.loadInto
import dev.brahmkshatriya.echo.utils.ui.AutoClearedValue.Companion.autoClearedNullable
import dev.brahmkshatriya.echo.utils.ui.CheckBoxListener
import dev.brahmkshatriya.echo.utils.ui.UiUtils.marquee
import dev.brahmkshatriya.echo.utils.ui.UiUtils.toTimeString
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.activityViewModel
import kotlin.math.max
import kotlin.math.min

class PlayerTvFragment : Fragment() {

    private var binding by autoClearedNullable<FragmentPlayerTvBinding>()
    private val viewModel by activityViewModel<PlayerViewModel>()
    private val uiViewModel by activityViewModel<UiViewModel>()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        binding = FragmentPlayerTvBinding.inflate(inflater, container, false)
        return binding!!.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        configureBack()
        configureControls()
        configureColors()
    }

    private fun configureBack() {
        val minimize = { uiViewModel.changePlayerState(STATE_HIDDEN) }
        binding!!.tvToolbar.setNavigationOnClickListener { minimize() }
        binding!!.tvToolbar.navigationIcon = null
        binding!!.tvToolbar.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        binding!!.tvMinimizeButton.setOnClickListener { minimize() }
        val backCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() = minimize()
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)
        backCallback.isEnabled = uiViewModel.playerSheetState.value == STATE_EXPANDED
        viewLifecycleOwner.observe(uiViewModel.playerSheetState) { state ->
            backCallback.isEnabled = state == STATE_EXPANDED
            // Landing is driven by the physical settle in UiViewModel.onStateChanged plus the single
            // MainActivity window-focus arbiter — the old flow-driven doOnLayout here raced the unlock.
        }
    }

    private val likeListener = CheckBoxListener { viewModel.likeCurrent(it) }

    private fun configureControls() {
        val binding = binding!!
        binding.tvTrackHeart.addOnCheckedStateChangedListener(likeListener)

        // Track metadata
        observe(viewModel.playerState.current) { current ->
            if (current == null) {
                uiViewModel.changePlayerState(STATE_HIDDEN)
                return@observe
            }
            val item = current.mediaItem
            val track = item.track
            binding.tvTrackTitle.text = track.title
            binding.tvTrackTitle.marquee()
            binding.tvTrackArtist.text = track.artists.joinToString(", ") { it.name }

            val itemContext = item.context
            binding.tvToolbar.title =
                if (itemContext != null) getString(R.string.playing_from) else null
            binding.tvToolbar.subtitle = itemContext?.title

            likeListener.enabled = false
            binding.tvTrackHeart.isChecked = item.isLiked
            likeListener.enabled = true
            lifecycleScope.launch {
                binding.tvTrackHeart.isVisible = viewModel.isLikeClient(item.extensionId)
            }
        }

        // Play / pause
        val playPauseListener = CheckBoxListener { viewModel.setPlaying(it) }
        binding.tvTrackPlayPause.addOnCheckedStateChangedListener(playPauseListener)
        observe(viewModel.playWhenReady) {
            playPauseListener.enabled = false
            binding.tvTrackPlayPause.isChecked = it
            playPauseListener.enabled = true
            binding.tvPlayingIndicator.alpha = if (viewModel.buffering.value && it) 1f else 0f
        }
        observe(viewModel.buffering) {
            binding.tvPlayingIndicator.alpha = if (it && viewModel.playWhenReady.value) 1f else 0f
        }

        // Progress
        observe(viewModel.progress) { (curr, buff) ->
            binding.tvBufferBar.progress = buff.toInt()
            if (!binding.tvSeekBar.isPressed) {
                binding.tvSeekBar.value =
                    max(0f, min(curr.toFloat(), binding.tvSeekBar.valueTo))
                binding.tvCurrentTime.text = curr.toTimeString()
            }
        }
        // DELIBERATE MIRROR of PlayerFragment's duration observer (see the rationale there): combine
        // totalDuration + current so the track-duration fallback re-fires when current arrives, instead of
        // being stranded behind a totalDuration null->null that never emits. Keep the two in sync; this one
        // writes the TV views (tvBufferBar / tvSeekBar / tvTotalTime).
        observe(combine(viewModel.totalDuration, viewModel.playerState.current) { total, current ->
            total ?: current?.track?.duration ?: 0L
        }) { duration ->
            binding.tvBufferBar.max = duration.toInt()
            binding.tvSeekBar.apply {
                value = max(0f, min(value, duration.toFloat()))
                valueTo = 1f + duration
            }
            binding.tvTotalTime.text = duration.toTimeString()
        }

        // Seek bar interactions
        binding.tvSeekBar.addOnChangeListener { _, value, fromUser ->
            if (fromUser) binding.tvCurrentTime.text = value.toLong().toTimeString()
        }
        binding.tvSeekBar.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) = Unit
            override fun onStopTrackingTouch(slider: Slider) =
                viewModel.seekTo(slider.value.toLong())
        })
        binding.tvSeekBar.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { viewModel.seekToAdd(-10_000); true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { viewModel.seekToAdd(10_000); true }
                else -> false
            }
        }

        // Skip buttons
        binding.tvTrackNext.setOnClickListener {
            viewModel.next()
            (binding.tvTrackNext.icon as Animatable).start()
        }
        observe(viewModel.nextEnabled) { binding.tvTrackNext.isEnabled = it }

        binding.tvTrackPrevious.setOnClickListener {
            viewModel.previous()
            (binding.tvTrackPrevious.icon as Animatable).start()
        }
        observe(viewModel.previousEnabled) { binding.tvTrackPrevious.isEnabled = it }

        // Shuffle
        val shuffleListener = CheckBoxListener { viewModel.setShuffle(it) }
        binding.tvTrackShuffle.addOnCheckedStateChangedListener(shuffleListener)
        observe(viewModel.shuffleMode) {
            shuffleListener.enabled = false
            binding.tvTrackShuffle.isChecked = it
            shuffleListener.enabled = true
        }

        // Repeat
        val repeatModes = listOf(REPEAT_MODE_OFF, REPEAT_MODE_ALL, REPEAT_MODE_ONE)
        val animatedDrawables = requireContext().run {
            fun anim(id: Int) = AppCompatResources.getDrawable(this, id) as AnimatedVectorDrawable
            listOf(
                anim(R.drawable.ic_repeat_one_to_repeat_off_40dp),
                anim(R.drawable.ic_repeat_off_to_repeat_40dp),
                anim(R.drawable.ic_repeat_to_repeat_one_40dp)
            )
        }
        val staticDrawables = requireContext().run {
            fun d(id: Int) = AppCompatResources.getDrawable(this, id)!!
            listOf(
                d(R.drawable.ic_repeat_off_40dp),
                d(R.drawable.ic_repeat_40dp),
                d(R.drawable.ic_repeat_one_40dp)
            )
        }
        binding.tvTrackRepeat.icon =
            staticDrawables[repeatModes.indexOf(viewModel.repeatMode.value)]

        fun changeRepeatDrawable(mode: Int) = binding.tvTrackRepeat.run {
            icon = animatedDrawables[repeatModes.indexOf(mode)]
            (icon as Animatable).start()
        }
        binding.tvTrackRepeat.setOnClickListener {
            val mode = when (viewModel.repeatMode.value) {
                REPEAT_MODE_OFF -> REPEAT_MODE_ALL
                REPEAT_MODE_ALL -> REPEAT_MODE_ONE
                else -> REPEAT_MODE_OFF
            }
            changeRepeatDrawable(mode)
            viewModel.setRepeat(mode)
        }
        observe(viewModel.repeatMode) { changeRepeatDrawable(it) }

        // Quality subtitle
        observe(viewModel.serverAndTracks) { (tracks, server, index) ->
            binding.tvTrackSubtitle.text =
                tracks?.getDetails(requireContext(), server, index)
                    ?.joinToString(" ⦿ ")?.takeIf { it.isNotBlank() }
        }

        // More button stub
        binding.tvToolbar.setOnMenuItemClickListener {
            if (it.itemId == R.id.menu_more) {
                Toast.makeText(requireContext(), R.string.coming_soon, Toast.LENGTH_SHORT).show()
                true
            } else false
        }
    }

    private var lastDrawable: Drawable? = null

    private fun configureColors() {
        observe(viewModel.playerState.current) { current ->
            if (current == null) return@observe
            val cover = current.track.cover
            val albumArt = binding?.tvAlbumArt ?: return@observe
            cover.toMaxRes().loadInto(albumArt, R.drawable.ic_music)
            lifecycleScope.launch {
                val drawable = cover.loadDrawable(requireContext())
                if (lastDrawable !== drawable) {
                    lastDrawable = drawable
                    val ctx = requireContext()
                    uiViewModel.playerDrawable.value = drawable
                    val dynamic = ctx.getSettings().getBoolean(DYNAMIC_PLAYER, true)
                    val colors = if (dynamic) ctx.getColorsFrom(drawable?.toBitmap()) else null
                    uiViewModel.playerColors.value = colors
                }
            }
        }

        observe(uiViewModel.playerColors) {
            val ctx = requireContext()
            val dynamic = ctx.getSettings().getBoolean(DYNAMIC_PLAYER, true)
            val playerColor = ctx.getSettings().getBoolean(PLAYER_COLOR, false)

            if (playerColor && dynamic) {
                val newAccent = it?.accent
                if (uiViewModel.lastPlayerAccentColor != newAccent) {
                    uiViewModel.lastPlayerAccentColor = newAccent
                    if (requireActivity().lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                        requireActivity().recreate()
                    } else {
                        lifecycleScope.launch { lifecycle.withResumed { requireActivity().recreate() } }
                    }
                    return@observe
                }
            }

            val colors = it ?: ctx.defaultPlayerColors()
            val b = binding ?: return@observe

            b.root.setBackgroundColor(if (dynamic) colors.accent else colors.background)
            b.tvBgGradient.imageTintList = ColorStateList.valueOf(colors.background)
            b.tvSeekBar.trackActiveTintList = ColorStateList.valueOf(colors.accent)
            b.tvSeekBar.thumbTintList = ColorStateList.valueOf(colors.accent)
            b.tvPlayingIndicator.setIndicatorColor(colors.accent)
            b.tvBufferBar.setIndicatorColor(colors.accent)
            b.tvBufferBar.trackColor = colors.onBackground
            b.tvCurrentTime.setTextColor(colors.onBackground)
            b.tvTotalTime.setTextColor(colors.onBackground)
            b.tvTrackTitle.setTextColor(colors.onBackground)
            b.tvTrackArtist.setTextColor(colors.onBackground)
            b.tvToolbar.setTitleTextColor(colors.onBackground)
            b.tvToolbar.setSubtitleTextColor(colors.onBackground)
        }
    }

    private fun ImageHolder?.toMaxRes(): ImageHolder? {
        val holder = (this as? ImageHolder.NetworkRequestImageHolder) ?: return this
        val url = holder.request.url
        if (!url.contains("cdn-images.dzcdn.net")) return this
        val newUrl = url.replace(Regex("""\d+x\d+(?=-000000-)"""), "1920x1920")
        return newUrl.toImageHolder(holder.request.headers, holder.crop)
    }

    companion object {
        private const val DYNAMIC_PLAYER = PlayerFragment.DYNAMIC_PLAYER
        private const val PLAYER_COLOR = PlayerFragment.PLAYER_COLOR
    }
}
