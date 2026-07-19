package dev.brahmkshatriya.echo

import android.app.UiModeManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color.TRANSPARENT
import android.os.Bundle
import android.widget.ImageView
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.navigationrail.NavigationRailView
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.Lifecycle
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.add
import androidx.fragment.app.commit
import com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_COLLAPSED
import com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
import com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_HIDDEN
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.DynamicColorsOptions
import com.google.android.material.navigation.NavigationBarView
import dev.brahmkshatriya.echo.databinding.ActivityMainBinding
import dev.brahmkshatriya.echo.extensions.ExtensionLoader
import dev.brahmkshatriya.echo.playback.PlayerState
import dev.brahmkshatriya.echo.playback.ResumptionUtils.recoverTracks
import dev.brahmkshatriya.echo.ui.common.ExceptionUtils.setupExceptionHandler
import dev.brahmkshatriya.echo.ui.common.FragmentUtils.setupIntents
import dev.brahmkshatriya.echo.ui.common.SnackBarHandler.Companion.setupSnackBar
import dev.brahmkshatriya.echo.ui.common.TvAwareRecyclerView
import dev.brahmkshatriya.echo.ui.common.UiViewModel
import dev.brahmkshatriya.echo.ui.common.UiViewModel.Companion.setupNavBarAndInsets
import dev.brahmkshatriya.echo.ui.common.UiViewModel.Companion.setupPlayerBehavior
import dev.brahmkshatriya.echo.ui.extensions.ExtensionsViewModel.Companion.configureExtensionsUpdater
import dev.brahmkshatriya.echo.ui.main.MainFragment
import dev.brahmkshatriya.echo.ui.player.PlayerFragment
import dev.brahmkshatriya.echo.ui.player.PlayerFragment.Companion.PLAYER_COLOR
import dev.brahmkshatriya.echo.ui.player.PlayerTvFragment
import dev.brahmkshatriya.echo.ui.player.PlayerViewModel
import dev.brahmkshatriya.echo.utils.ui.CheckBoxListener
import dev.brahmkshatriya.echo.utils.ContextUtils.getSettings
import dev.brahmkshatriya.echo.utils.ContextUtils.observe
import dev.brahmkshatriya.echo.utils.PermsUtils.checkAppPermissions
import dev.brahmkshatriya.echo.utils.PermsUtils.checkBatteryOptimization
import dev.brahmkshatriya.echo.utils.image.ImageUtils.loadInto
import dev.brahmkshatriya.echo.utils.ui.UiUtils.isNightMode
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

open class MainActivity : AppCompatActivity() {

    class Back : MainActivity()

    val binding by lazy { ActivityMainBinding.inflate(layoutInflater) }
    private val uiViewModel by viewModel<UiViewModel>()
    private val extensionLoader by inject<ExtensionLoader>()
    private val playerState by inject<PlayerState>()

    private val playerViewModel by viewModel<PlayerViewModel>()

    private val isTV by lazy {
        val uiModeManager = getSystemService(UI_MODE_SERVICE) as UiModeManager
        packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            uiModeManager.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(getAppTheme())
        DynamicColors.applyToActivityIfAvailable(
            this, applyUiChanges(this, uiViewModel)
        )

        setContentView(binding.root)

        enableEdgeToEdge(
            SystemBarStyle.auto(TRANSPARENT, TRANSPARENT),
            if (isNightMode()) SystemBarStyle.dark(TRANSPARENT)
            else SystemBarStyle.light(TRANSPARENT, TRANSPARENT)
        )

        setupNavBarAndInsets(uiViewModel, binding.root, binding.navView as NavigationBarView)
        val gradientEnabled = getSettings().getBoolean(UiViewModel.NAVBAR_GRADIENT, true)
        observe(uiViewModel.playerSheetOffset) { offset ->
            binding.navGradientOverlay?.alpha = (1f - offset).coerceIn(0f, 1f)
            binding.navGradientOverlay?.isVisible = gradientEnabled
        }
        setupTvNavRail()
        setupTvMiniPlayer()
        setupTvPlayerCollapseFocus()
        setupPlayerBehavior(
            uiViewModel, binding.playerFragmentContainer, isTV,
            binding.root.findViewById(R.id.navRailContainer)
        )
        setupExceptionHandler(setupSnackBar(uiViewModel, binding.root))
        checkAppPermissions { extensionLoader.setPermGranted() }
        checkBatteryOptimization()
        configureExtensionsUpdater()
        supportFragmentManager.commit {
            if (savedInstanceState != null) return@commit
            add<MainFragment>(R.id.navHostFragment, "main")
            if (isTV) add<PlayerTvFragment>(R.id.playerFragmentContainer, "player")
            else add<PlayerFragment>(R.id.playerFragmentContainer, "player")
        }
        setupIntents(uiViewModel)
        val isFromGearhead = try {
            referrer?.host == "com.google.android.projection.gearhead"
        } catch (_: Exception) {
            false
        }
        if (savedInstanceState == null && isFromGearhead) {
            lifecycleScope.launch {
                val hasTracks = withContext(Dispatchers.IO) { !recoverTracks().isNullOrEmpty() }
                if (hasTracks && playerViewModel.playWhenReady.value) {
                    uiViewModel.changePlayerState(STATE_EXPANDED)
                    uiViewModel.changeMoreState(STATE_COLLAPSED)
                }
            }
        }
    }

    // #7: player leaves the screen (collapse/hide on TV) -> hand focus to the visible feed. Reclaim
    // only from the dismissed player or from nothing; never steal the nav rail / mini bar / drill-in.
    // This is a playerSheetState event, distinct from the window-focus arbiter below.
    private fun setupTvPlayerCollapseFocus() {
        if (!isTV) return
        observe(uiViewModel.playerSheetState) { state ->
            if (state != STATE_HIDDEN) return@observe
            val feed = binding.navHostFragment.findVisibleTvFeed() ?: return@observe
            val focus = currentFocus
            val inPlayer = focus != null &&
                generateSequence(focus.parent) { it.parent }.any { it === binding.playerFragmentContainer }
            feed.establishFeedFocus(allowClaim = focus == null || inPlayer)
        }
    }

    // SINGLE TV window-focus arbiter (warm resume, and cold-start window gain). Exactly one
    // destination per window-focus event: expanded player -> play/pause; otherwise -> visible feed.
    // Replaces both the feed RV's former onWindowFocusChanged and the former B3 player fallback, so
    // no two handlers can request focus on one window-focus event. Resume-into-expanded and
    // resume-into-feed are both legitimate and are chosen here, not designed away.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || !isTV) return
        if (uiViewModel.playerSheetState.value == STATE_EXPANDED) {
            val container = binding.playerFragmentContainer
            if (!container.hasFocus())
                container.findViewById<View>(R.id.tv_track_play_pause)?.requestFocus()
        } else {
            val feed = binding.navHostFragment.findVisibleTvFeed() ?: return
            val focus = currentFocus
            val inFeed = focus != null &&
                generateSequence(focus.parent) { it.parent }.any { it === feed }
            feed.establishFeedFocus(allowClaim = focus == null || inFeed)
        }
    }

    private fun View.findVisibleTvFeed(): TvAwareRecyclerView? {
        if (this is TvAwareRecyclerView && isShown) return this
        if (this is ViewGroup) for (i in 0 until childCount) {
            getChildAt(i).findVisibleTvFeed()?.let { return it }
        }
        return null
    }

    private fun setupTvNavRail() {
        if (!isTV) return
        val navView = binding.navView as? NavigationRailView ?: return
        val nowPlayingItem = navView.menu.findItem(R.id.navNowPlaying) ?: return

        // setupNavBarAndInsets installed a listener that does navIds.indexOf(itemId) — navNowPlaying
        // is not in navIds so indexOf returns -1, corrupting navigation.value. Replace it.
        navView.setOnItemSelectedListener { item ->
            if (item.itemId == R.id.navNowPlaying) {
                uiViewModel.changePlayerState(STATE_EXPANDED)
                false
            } else {
                uiViewModel.navigation.value = uiViewModel.navIds.indexOf(item.itemId)
                true
            }
        }

        // setupNavBarAndInsets also installed a click listener on every menu item view.
        // Override it for navNowPlaying so it expands the player instead of navigating.
        findViewById<View>(R.id.navNowPlaying)?.setOnClickListener {
            uiViewModel.changePlayerState(STATE_EXPANDED)
        }

        nowPlayingItem.isVisible = playerState.current.value != null
        observe(playerState.current) { nowPlayingItem.isVisible = it != null }
    }

    // PHONE / TV SWITCH — and the map of who owns the mini player per surface. R.id.tvMiniPlayer exists ONLY
    // in layout-land-television, so the `?: return` below makes this ENTIRE method TV-only: the
    // playerState.current observer here (which drives the TV mini bar AND its sheet show/hide, including the
    // RESUMED gate) never runs on a phone. Three surfaces, three independent mini players / sheet models:
    //   phone: BottomSheet + PlayerFragment  — dismissable (see PlayerFragment's phone-only sheet-state block)
    //   TV:    this tvMiniPlayer + PlayerTvFragment — no dismiss
    //   AA:    Android Auto / gearhead's own UI — NO Fragment at all (nothing PlayerFragment does reaches it)
    // Nothing else in the code says this; assuming this observer was "general" cost real debugging time.
    private fun setupTvMiniPlayer() {
        val miniPlayer = binding.root.findViewById<LinearLayout>(R.id.tvMiniPlayer) ?: return
        val miniArt = binding.root.findViewById<ImageView>(R.id.miniArt)
        val miniTitle = binding.root.findViewById<TextView>(R.id.miniTitle)
        val miniArtist = binding.root.findViewById<TextView>(R.id.miniArtist)
        val miniPlayPause = binding.root.findViewById<MaterialCheckBox>(R.id.miniPlayPause)
        val miniProgress = binding.root.findViewById<ProgressBar>(R.id.miniProgress)

        miniPlayer.setOnClickListener { uiViewModel.changePlayerState(STATE_EXPANDED) }

        val playPauseListener = CheckBoxListener { playerViewModel.setPlaying(it) }
        miniPlayPause.addOnCheckedStateChangedListener(playPauseListener)

        fun updateVisibility() {
            val hasTrack = playerState.current.value != null
            val isHidden = uiViewModel.playerSheetState.value == STATE_HIDDEN
            val showMini = hasTrack && isHidden
            miniPlayer.isVisible = showMini
            uiViewModel.tvMiniPlayerVisible.value = showMini
            binding.navHostFragment.nextFocusDownId =
                if (showMini) R.id.tvMiniPlayer else View.NO_ID
        }

        var hadTrack = false
        var lastMiniArtId: String? = null
        observe(playerState.current) { current ->
            val hasTrack = current != null
            if (hasTrack && !hadTrack && uiViewModel.playerSheetState.value == STATE_HIDDEN) {
                if (playerViewModel.playWhenReady.value) {
                    uiViewModel.changePlayerState(STATE_EXPANDED)
                } else if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    uiViewModel.changePlayerState(STATE_COLLAPSED)
                }
            }
            hadTrack = hasTrack
            updateVisibility()
            if (current == null) return@observe
            val track = current.track
            miniTitle.text = track.title
            miniArtist.text = track.artists.joinToString(", ") { it.name }
            if (current.mediaItem.mediaId != lastMiniArtId) {
                lastMiniArtId = current.mediaItem.mediaId
                track.cover.loadInto(miniArt, R.drawable.ic_music)
            }
        }

        observe(uiViewModel.playerSheetState) { state ->
            updateVisibility()
            if (state == STATE_HIDDEN) {
                binding.root.post {
                    if (!miniPlayPause.requestFocus()) binding.navHostFragment.requestFocus()
                }
            }
        }

        observe(playerViewModel.playWhenReady) {
            playPauseListener.enabled = false
            miniPlayPause.isChecked = it
            playPauseListener.enabled = true
        }

        observe(playerViewModel.progress) { (curr, _) ->
            val duration = playerViewModel.totalDuration.value
                ?: playerState.current.value?.track?.duration ?: 0
            miniProgress.progress =
                if (duration > 0) ((curr.toFloat() / duration) * 1000).toInt() else 0
        }
    }

    companion object {
        const val THEME_KEY = "theme"
        const val AMOLED_KEY = "amoled"
        const val BIG_COVER = "big_cover"
        const val CUSTOM_THEME_KEY = "custom_theme"
        const val COLOR_KEY = "color"

        fun Context.getAppTheme(): Int {
            val settings = getSettings()
            val bigCover = settings.getBoolean(BIG_COVER, false)
            val amoled = settings.getBoolean(AMOLED_KEY, false)
            return when {
                amoled && bigCover -> R.style.AmoledBigCover
                amoled -> R.style.Amoled
                bigCover -> R.style.BigCover
                else -> R.style.Default
            }
        }

        fun Context.defaultColor() =
            ContextCompat.getColor(this, R.color.app_color)

        fun Context.isAmoled() = getSettings().getBoolean(AMOLED_KEY, false)

        fun applyUiChanges(context: Context, uiViewModel: UiViewModel): DynamicColorsOptions {
            val settings = context.getSettings()
            val mode = when (settings.getString(THEME_KEY, "system")) {
                "light" -> AppCompatDelegate.MODE_NIGHT_NO
                "dark" -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
            AppCompatDelegate.setDefaultNightMode(mode)

            val custom = settings.getBoolean(CUSTOM_THEME_KEY, true)
            val color = if (custom) settings.getInt(COLOR_KEY, context.defaultColor()) else null
            val playerColor = settings.getBoolean(PLAYER_COLOR, false)
            val customColor = uiViewModel.playerColors.value?.accent?.takeIf { playerColor }

            val builder = DynamicColorsOptions.Builder()
            (customColor ?: color)?.let { builder.setContentBasedSource(it) }
            return builder.build()
        }

        const val BACK_ANIM = "back_anim"

        // Picks the Back-animation launcher variant when the BACK_ANIM pref is on — but Back's <activity> is
        // android:enabled="false" by default and only ENABLED at runtime by the settings toggle
        // (changeEnabledComponent). The pref (SharedPreferences) and the component-enabled state can DIVERGE — a
        // reinstall/update reverts Back to the manifest default, or a settings restore turns the pref on without
        // enabling the component — and startActivity() on a DISABLED component throws a fatal
        // ActivityNotFoundException (it hit all four getMainActivity() callers: opener, player/download
        // notifications, webview login). Guard at this single chokepoint: return Back ONLY when it is actually
        // ENABLED, else fall back to MainActivity. getComponentEnabledSetting returns ENABLED only for the explicit
        // runtime-enable; DEFAULT (=manifest false) and DISABLED both mean "not usable". The fallback is always
        // safe: the toggle only disables MainActivity in the SAME call that ENABLES Back, so whenever Back is not
        // enabled, MainActivity is. runCatching guards the rare case of the query itself failing.
        fun Context.getMainActivity(): Class<out MainActivity> {
            if (!getSettings().getBoolean(BACK_ANIM, false)) return MainActivity::class.java
            val backEnabled = runCatching {
                packageManager.getComponentEnabledSetting(ComponentName(this, Back::class.java))
            }.getOrNull() == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            return if (backEnabled) Back::class.java else MainActivity::class.java
        }
    }
}