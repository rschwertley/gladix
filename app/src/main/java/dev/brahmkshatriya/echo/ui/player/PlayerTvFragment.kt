package dev.brahmkshatriya.echo.ui.player

import android.graphics.Color
import androidx.fragment.app.FragmentManager
import android.content.res.ColorStateList
import android.graphics.drawable.Animatable
import android.graphics.drawable.AnimatedVectorDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.doOnLayout
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
import androidx.core.content.ContextCompat
import androidx.core.view.MenuItemCompat
import android.view.MenuItem
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.databinding.FragmentPlayerTvBinding
import dev.brahmkshatriya.echo.ui.media.more.MediaMoreBottomSheet
import dev.brahmkshatriya.echo.playback.MediaItemUtils.context
import dev.brahmkshatriya.echo.playback.MediaItemUtils.extensionId
import dev.brahmkshatriya.echo.playback.MediaItemUtils.isLiked
import dev.brahmkshatriya.echo.playback.MediaItemUtils.track
import dev.brahmkshatriya.echo.ui.common.UiViewModel
import dev.brahmkshatriya.echo.ui.player.PlayerColors.Companion.defaultPlayerColors
import dev.brahmkshatriya.echo.ui.player.PlayerColors.Companion.getColorsFrom
import dev.brahmkshatriya.echo.ui.player.quality.FormatUtils.getDetailsFormatFirst
import dev.brahmkshatriya.echo.utils.ContextUtils.getSettings
import dev.brahmkshatriya.echo.utils.ContextUtils.observe
import dev.brahmkshatriya.echo.common.models.ImageHolder
import dev.brahmkshatriya.echo.common.models.ImageHolder.Companion.toImageHolder
import dev.brahmkshatriya.echo.utils.image.ImageUtils.loadDrawable
import dev.brahmkshatriya.echo.utils.image.ImageUtils.loadRoundedInto
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
        // ⚠⚠ THE ⋮ IS INFLATED HERE, BECAUSE THE SHARED STYLE NO LONGER DOES IT (2026-10-31). Until bc25f358
        // (2026-08-28, "Rework the full-screen player's lower region") PlayerToolbarStyle carried
        // `menu = @menu/more_menu`; that commit moved the PHONE's overflow down beside the heart and removed the
        // item from the shared style, which silently took the TV player's ⋮ with it. Everything below - the
        // focus routing, the click handler opening MediaMoreBottomSheet - kept pointing at a menu_more view that
        // no longer existed, so on TV there was nothing to see or focus (1158). Tinted amoled_fg, like the rest
        // of the TV player's chrome. [CORRECTED 2026-10-31] Now white - it sits on a dark scrim; see the tint below.
        binding!!.tvToolbar.inflateMenu(R.menu.more_menu)
        binding!!.tvToolbar.menu.findItem(R.id.menu_more)?.let {
            // ALWAYS, not the menu's ifRoom: collapsed into an overflow button it would lose the menu_more id
            // that the focus routing below targets.
            it.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            // White, not amoled_fg (2026-10-31): the ⋮ always sits on the dark tv_minimize_scrim (wireMoreButtonFocus),
            // and amoled_fg follows night mode - black in light mode, i.e. black-on-dark. Same for the minimize arrow
            // (fragment_player_tv.xml).
            MenuItemCompat.setIconTintList(it, ColorStateList.valueOf(Color.WHITE))
        }
        // Let the toolbar's overflow action (menu_more) receive D-pad focus. The old FOCUS_BLOCK_DESCENDANTS
        // blocked EVERY descendant to hide the removed nav-icon slot — but navigationIcon=null above already
        // removes that view from the tree, and title/subtitle aren't focusable, so AFTER_DESCENDANTS exposes
        // ONLY the overflow button. (TO the overflow is wired by-id in XML: tv_minimize_button.nextFocusRight
        // = @id/menu_more, resolved to the current instance at focus-search time.)
        binding!!.tvToolbar.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        // Scheme A routing FROM the overflow: Left -> minimize (top-left), Down -> heart (top of the right
        // panel); Up/Right trap to self (top-right corner). ActionMenuView (re)builds its item view on
        // layout, so RE-APPLY on every toolbar layout — a rebuilt view would otherwise lose these ids.
        fun wireMoreButtonFocus() {
            binding?.tvToolbar?.findViewById<View>(R.id.menu_more)?.apply {
                // The same 4dp ring as the minimize button; the action view's own highlight is too faint at
                // 10 feet. Set once per built view (ActionMenuView may rebuild it, hence the null check).
                if (foreground == null) foreground = ContextCompat.getDrawable(context, R.drawable.tv_focus_indicator)
                // Visible at all times, not only when focused (2026-10-31): the same 40%-black circular scrim as
                // the minimize button, under the same amoled_fg icon [CORRECTED 2026-10-31: both icons are now white,
                // since amoled_fg is black in light mode and the scrim is always dark]. amoled_fg follows night mode, not the
                // player background (which is the album accent when Player Color is on), so on its own it is not
                // guaranteed to read; the scrim gives it the minimize button's contrast everywhere.
                // Once per built view: setting a background re-lays out the toolbar, which re-runs this function.
                if (getTag(R.id.tv_more_scrim) != true) {
                    background = ContextCompat.getDrawable(context, R.drawable.tv_minimize_scrim)
                    setTag(R.id.tv_more_scrim, true)
                }
                nextFocusLeftId = R.id.tv_minimize_button
                nextFocusUpId = R.id.menu_more
                nextFocusRightId = R.id.menu_more
            }
            routeAroundHeart()
        }
        binding!!.tvToolbar.doOnLayout { wireMoreButtonFocus() }
        binding!!.tvToolbar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> wireMoreButtonFocus() }
        binding!!.tvMinimizeButton.setOnClickListener { minimize() }
        val backCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() = minimize()
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)
        backCallback.isEnabled = uiViewModel.playerSheetState.value == STATE_EXPANDED
        viewLifecycleOwner.observe(uiViewModel.playerSheetState) { state ->
            backCallback.isEnabled = state == STATE_EXPANDED
            updateWaveMotion()
            // Landing is driven by the physical settle in UiViewModel.onStateChanged plus the single
            // MainActivity window-focus arbiter — the old flow-driven doOnLayout here raced the unlock.
        }
    }

    // Wave motion — TV twin of PlayerFragment.updateWaveMotion(); the gating note lives in styles.xml
    // (EchoLinearProgressIndicator.Wavy) and applies verbatim here. Two TV-specific differences:
    //   - TV rests at STATE_HIDDEN, not STATE_COLLAPSED (it has a separate mini bar), so the on-screen
    //     test is "== STATE_EXPANDED" rather than "!= STATE_COLLAPSED".
    //   - PlayerTvFragment had no onPause/onResume at all, so unlike the phone there was no Ken Burns
    //     discipline to mirror; the pair below exists solely for this.
    private var waveSpeedPx = -1
    private var waveAmplitudePx = -1
    private var waveResumed = false

    private fun updateWaveMotion() {
        val wave = binding?.tvSeekWaveBar ?: return
        if (waveSpeedPx < 0) {
            waveSpeedPx = wave.waveSpeed
            waveAmplitudePx = wave.waveAmplitude
        }
        // playWhenReady, not Current.isPlaying — see the note on PlayerFragment's twin of this line.
        val playing = viewModel.playWhenReady.value
        val expanded = uiViewModel.playerSheetState.value == STATE_EXPANDED
        wave.waveAmplitude = if (playing) waveAmplitudePx else 0
        wave.waveSpeed = if (playing && expanded && waveResumed) waveSpeedPx else 0
    }

    override fun onPause() {
        super.onPause()
        waveResumed = false
        updateWaveMotion()
    }

    override fun onResume() {
        super.onResume()
        waveResumed = true
        updateWaveMotion()
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
                val showHeart = viewModel.isLikeClient(item.extensionId)
                // Hiding the FOCUSED heart (next track from an extension without likes) dropped focus out of the
                // player entirely (2026-10-31). Hand it to the heart's own Down neighbour first.
                if (!showHeart && binding.tvTrackHeart.hasFocus()) binding.tvSeekBar.requestFocus()
                binding.tvTrackHeart.isVisible = showHeart
                routeAroundHeart()
            }
        }

        // Play / pause
        val playPauseListener = CheckBoxListener { viewModel.setPlaying(it) }
        binding.tvTrackPlayPause.addOnCheckedStateChangedListener(playPauseListener)
        observe(viewModel.playWhenReady) {
            // Also the wave's primary driver — observe() is flowWithLifecycle(STARTED) over a StateFlow,
            // so this re-asserts on every ON_START and a missed edge self-corrects. Folded into the
            // existing observer rather than adding a second one over the same flow.
            updateWaveMotion()
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
                binding.tvSeekWaveBar.progress = curr.toInt()
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
            binding.tvSeekWaveBar.max = duration.toInt()
            binding.tvSeekBar.apply {
                value = max(0f, min(value, duration.toFloat()))
                valueTo = 1f + duration
            }
            binding.tvTotalTime.text = duration.toTimeString()
        }

        // Seek bar interactions
        binding.tvSeekBar.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                binding.tvCurrentTime.text = value.toLong().toTimeString()
                // Mirrors PlayerFragment: the wave is a separate view, the progress observer is gated on
                // !isPressed, so without this the wave lags the thumb for the whole drag. On TV the drag
                // also arrives from DPAD_LEFT/RIGHT via seekToAdd, which comes back through the observer.
                binding.tvSeekWaveBar.progress = value.toInt()
            }
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
        observe(viewModel.nextEnabled) {
            // Disabling the FOCUSED Next (the last track, repeat off) must not strand focus (2026-10-31): hand it
            // to Next's Left neighbour, play/pause. Previous needs nothing - it is enabled whenever an item is
            // current (PlayerUiListener's previousEnabled).
            if (!it && binding.tvTrackNext.hasFocus()) binding.tvTrackPlayPause.requestFocus()
            binding.tvTrackNext.isEnabled = it
        }

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
            // Same hide-don't-blank rule as the phone pill - see the note there. TV matters slightly
            // more: these observers are flowWithLifecycle(STARTED) and serverAndTracks is a stateIn
            // StateFlow, so every ON_START replays the latest value. Under the old always-write shape a
            // stale value re-appeared on each return to the player; under this one an ungated (null)
            // value replays as hidden.
            val details = tracks?.getDetailsFormatFirst(requireContext(), server, index)
                ?.joinToString(" ⦿ ")?.takeIf { it.isNotBlank() }
            if (details != null) binding.tvTrackSubtitle.text = details
            binding.tvTrackSubtitle.isVisible = details != null
        }

        // Overflow → the same media "more" sheet the phone player opens for the current track
        // (PlayerFragment.onMoreClicked): MediaMoreBottomSheet for the current item, fromPlayer=true.
        // The sheet is already used on TV from the feed/history/media pages, so it's reachable and
        // D-pad navigable as-is. No-op when nothing is playing.
        binding.tvToolbar.setOnMenuItemClickListener {
            if (it.itemId == R.id.menu_more) {
                viewModel.playerState.current.value?.let { current ->
                    returnFocusToMoreWhenSheetCloses()
                    MediaMoreBottomSheet.show(
                        this, requireActivity().supportFragmentManager,
                        R.id.navHostFragment, current.mediaItem.extensionId, current.track,
                        current.isLoaded, true
                    )
                }
                true
            } else false
        }
    }

    /**
     * ⚠️ THE HEART'S NEIGHBOURS, SET EXPLICITLY (2026-10-31). The heart sits between the seek bar and the top row
     * (minimize, ⋮): seek bar Up -> heart, minimize and ⋮ Down -> heart. When the heart is hidden (an extension
     * without likes) or disabled, those ids point at a view that cannot take focus and Android falls back to a
     * geometric guess. Route straight across it instead, and back when it returns. Called whenever the heart's
     * visibility is set and whenever the ⋮ view is (re)built.
     */
    private fun routeAroundHeart() {
        val b = binding ?: return
        val heartUsable = b.tvTrackHeart.isVisible && b.tvTrackHeart.isEnabled
        b.tvSeekBar.nextFocusUpId = if (heartUsable) R.id.tv_track_heart else R.id.tv_minimize_button
        val belowTop = if (heartUsable) R.id.tv_track_heart else R.id.tv_seek_bar
        b.tvMinimizeButton.nextFocusDownId = belowTop
        b.tvToolbar.findViewById<View>(R.id.menu_more)?.nextFocusDownId = belowTop
    }

    /**
     * Back (or any close) of the player's ⋮ sheet puts focus back on the ⋮ (2026-10-31), rather than leaving it to
     * the window's default. One-shot: unregisters on the first MediaMoreBottomSheet view destroyed. Skipped when
     * the sheet's action left the player (a page opened, so the player is no longer expanded); when an action
     * opened another sheet (Audio effects, Sleep timer, Quality) the ⋮ becomes the activity's focused view
     * underneath it, so closing that sheet lands there too.
     */
    private fun returnFocusToMoreWhenSheetCloses() {
        val manager = requireActivity().supportFragmentManager
        manager.registerFragmentLifecycleCallbacks(object : FragmentManager.FragmentLifecycleCallbacks() {
            override fun onFragmentViewDestroyed(fm: FragmentManager, f: Fragment) {
                if (f !is MediaMoreBottomSheet) return
                fm.unregisterFragmentLifecycleCallbacks(this)
                val toolbar = binding?.tvToolbar ?: return
                toolbar.post {
                    if (uiViewModel.playerSheetState.value != STATE_EXPANDED) return@post
                    toolbar.findViewById<View>(R.id.menu_more)?.requestFocus()
                }
            }
        }, false)
    }

    private var lastDrawable: Drawable? = null

    private fun configureColors() {
        observe(viewModel.playerState.current) { current ->
            if (current == null) return@observe
            val cover = current.track.cover
            val albumArt = binding?.tvAlbumArt ?: return@observe
            cover.toMaxRes().loadRoundedInto(albumArt, 24f, R.drawable.ic_music)
            // Context captured HERE, synchronously in the observe body while the fragment is provably
            // attached — not re-fetched after the suspend below. The old code called requireContext() again
            // on resumption, which threw "not attached to a context" whenever an activity recreation (the
            // accent path below calls recreate() itself; night-mode/settings changes do too) detached the
            // fragment mid-load. Nothing in the tail needs a *fresh* context: getSettings() is
            // SharedPreferences and getColorsFrom() is Palette + a config read, both fine on the context the
            // load started with — and semantically that IS the right one, since these colours belong to the
            // load that was kicked off then.
            val ctx = requireContext()
            lifecycleScope.launch {
                val drawable = cover.loadDrawable(ctx)
                if (lastDrawable !== drawable) {
                    uiViewModel.playerDrawable.value = drawable
                    val dynamic = ctx.getSettings().getBoolean(DYNAMIC_PLAYER, true)
                    val colors = if (dynamic) ctx.getColorsFrom(drawable?.toBitmap()) else null
                    uiViewModel.playerColors.value = colors
                    // Assigned AFTER the work, not before. Pre-assigning meant any early exit from the tail
                    // left the cache claiming this drawable had been applied when its colours never were —
                    // and since the guard is identity-based, the same drawable would then short-circuit
                    // forever and the player would keep stale colours. Safe to move: loadDrawable above is
                    // the only suspension point, so everything from the check to here is synchronous on
                    // Main and no other coroutine can interleave between them.
                    lastDrawable = drawable
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
                    // The bookkeeping is written only where the recreate actually happens. It used to be
                    // assigned up front, which broke the DEFERRED branch: withResumed resumes its caller
                    // through a dispatch, and that dispatch is a cancellation point — so the coroutine could
                    // be cancelled after recreate() was requested but before an assignment placed *after*
                    // withResumed, leaving the flag stale and the accent retrying on every emission forever.
                    // lastPlayerAccentColor lives on UiViewModel, which SURVIVES recreate(), so the write
                    // persists across the teardown it just triggered. Nothing else re-applies the accent —
                    // MainActivity.applyUiChanges seeds the theme from playerColors at activity creation
                    // only — so a dropped recreate does not self-heal, which is why this ordering matters.
                    if (requireActivity().lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                        requireActivity().recreate()
                        uiViewModel.lastPlayerAccentColor = newAccent
                    } else {
                        lifecycleScope.launch {
                            lifecycle.withResumed {
                                // No suspension point between these two, so cancellation cannot land
                                // between the recreate and the flag that records it.
                                requireActivity().recreate()
                                uiViewModel.lastPlayerAccentColor = newAccent
                            }
                        }
                    }
                    return@observe
                }
            }

            val colors = it ?: ctx.defaultPlayerColors()
            val b = binding ?: return@observe

            b.root.setBackgroundColor(if (dynamic) colors.accent else colors.background)
            b.tvBgGradient.imageTintList = ColorStateList.valueOf(colors.background)
            // Accent goes on the WAVE, not the Slider's active track — see PlayerFragment's twin of this
            // line. A runtime tint here would override the transparent trackColorActive in XML and draw a
            // straight line back under the wave.
            // ⚠⚠ NEUTRAL, MIRRORING PlayerFragment - AND THE CASE IS STRONGER HERE, NOT WEAKER.
            // Read the full rationale at PlayerFragment's twin of this line. On phone the wave and its
            // background are two colours drawn from ONE image; on TV, `b.root.setBackgroundColor(...)` a few
            // lines above sets the root to `colors.accent` ITSELF when dynamic - so the wave and the surface
            // behind it were the SAME VALUE, not merely the same region of colour space. Invisible by
            // arithmetic rather than by coincidence.
            // TV already uses amoled_fg for its own chrome (icon tints, text in fragment_player_tv.xml), so
            // this matches what is beside it. tvPlayingIndicator below KEEPS the accent: it is not on the
            // seek row and does not sit on the progress track.
            val seekNeutral = ContextCompat.getColor(ctx, R.color.amoled_fg)
            b.tvSeekWaveBar.setIndicatorColor(seekNeutral)
            b.tvSeekBar.thumbTintList = ColorStateList.valueOf(seekNeutral)
            b.tvPlayingIndicator.setIndicatorColor(colors.accent)
            // tvBufferBar.setIndicatorColor is DELIBERATELY ABSENT — mirrors PlayerFragment. The buffer
            // indicator is transparent in XML because DeterminateDrawable never assigns startFraction, so
            // it could only draw from the left edge and put a solid line under the whole played portion,
            // visible through the wave. A runtime setIndicatorColor here would override that XML. Only the
            // rail is tinted. See the note on tv_buffer_bar in fragment_player_tv.xml.
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
