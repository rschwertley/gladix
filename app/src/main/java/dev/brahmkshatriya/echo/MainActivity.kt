package dev.brahmkshatriya.echo

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color.TRANSPARENT
import android.os.Bundle
import android.widget.ImageView
import android.view.View
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
        fun Context.getMainActivity() = if (getSettings().getBoolean(BACK_ANIM, false))
            Back::class.java else MainActivity::class.java
    }
}