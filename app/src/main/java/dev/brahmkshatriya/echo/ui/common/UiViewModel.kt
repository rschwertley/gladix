package dev.brahmkshatriya.echo.ui.common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.MarginLayoutParams
import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedCallback
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.core.view.forEach
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.core.view.updatePaddingRelative
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_COLLAPSED
import com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_DRAGGING
import com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
import com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_HIDDEN
import com.google.android.material.color.MaterialColors
import com.google.android.material.navigation.NavigationBarView
import com.google.android.material.navigationrail.NavigationRailView
import dev.brahmkshatriya.echo.MainActivity
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.extensions.ExtensionLoader
import dev.brahmkshatriya.echo.playback.PlayerState
import dev.brahmkshatriya.echo.ui.main.MainFragment
import dev.brahmkshatriya.echo.ui.player.PlayerColors
import dev.brahmkshatriya.echo.utils.CacheUtils.getFromCache
import dev.brahmkshatriya.echo.utils.CacheUtils.saveToCache
import dev.brahmkshatriya.echo.utils.ContextUtils.emit
import dev.brahmkshatriya.echo.utils.ContextUtils.getSettings
import dev.brahmkshatriya.echo.utils.ContextUtils.observe
import dev.brahmkshatriya.echo.utils.image.ImageUtils.loadDrawable
import dev.brahmkshatriya.echo.utils.ui.AnimationUtils.animateTranslation
import dev.brahmkshatriya.echo.utils.ui.GradientDrawable
import dev.brahmkshatriya.echo.utils.ui.UiUtils.dpToPx
import dev.brahmkshatriya.echo.utils.ui.UiUtils.isRTL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted.Companion.Lazily
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.androidx.viewmodel.ext.android.activityViewModel
import org.koin.androidx.viewmodel.ext.android.viewModel
import java.lang.ref.WeakReference
import kotlin.math.max
import kotlin.math.min

class UiViewModel(
    context: Context,
    extensionLoader: ExtensionLoader,
    private val playerState: PlayerState
) : ViewModel() {

    data class Insets(
        val top: Int = 0,
        val bottom: Int = 0,
        val start: Int = 0,
        val end: Int = 0
    ) {
        fun add(vararg insets: Insets) = insets.fold(this) { acc, it ->
            Insets(
                acc.top + it.top,
                acc.bottom + it.bottom,
                acc.start + it.start,
                acc.end + it.end
            )
        }
    }

    var lastPlayerAccentColor: Int? = null
    val navigation = MutableStateFlow(context.getFromCache("main_nav") ?: 0).also { flow ->
        viewModelScope.launch { flow.collect { context.saveToCache("main_nav", it) } }
    }
    val selectedSettingsTab = MutableStateFlow(0)
    val navigationReselected = MutableSharedFlow<Int>()
    val navIds = listOf(
        R.id.homeFragment,
        R.id.searchFragment,
        R.id.libraryFragment,
        R.id.historyFragment
    )

    val currentNavBackground = MutableStateFlow<Drawable?>(null)
    private val extensionColor = extensionLoader.current.transform { extension ->
        emit(null)
        val drawable = extension?.metadata?.icon?.loadDrawable(context) ?: return@transform
        emit(PlayerColors.getDominantColor(drawable.toBitmap()).toDrawable())
    }

    private val navViewInsets = MutableStateFlow(Insets())
    private val playerNavViewInsets = MutableStateFlow(Insets())
    private val playerInsets = MutableStateFlow(Insets())
    val systemInsets = MutableStateFlow(Insets())
    val isMainFragment = MutableStateFlow(true)
    var isRail = false

    val combined = systemInsets.combine(navViewInsets) { system, nav ->
        if (isMainFragment.value || isRail) system.add(nav) else system
    }.combine(playerInsets) { system, player ->
        system.add(player)
    }.stateIn(viewModelScope, Lazily, Insets())

    fun getCombined() = (if (isMainFragment.value || isRail) systemInsets.value.add(navViewInsets.value)
    else systemInsets.value).add(playerInsets.value)

    fun getSnackbarInsets(): Insets {
        if (playerSheetState.value == STATE_EXPANDED) return Insets()
        if (isMainFragment.value || isRail) return navViewInsets.value.add(playerInsets.value)
        return playerInsets.value
    }

    fun setPlayerNavViewInsets(context: Context, isNavVisible: Boolean, isRail: Boolean): Insets {
        val insets = context.resources.run {
            if (isRail) {
                val width = getDimensionPixelSize(R.dimen.nav_width)
                if (context.isRTL()) Insets(end = width) else Insets(start = width)
            } else {
                if (!isNavVisible) return@run Insets()
                Insets(bottom = getDimensionPixelSize(R.dimen.nav_height))
            }
        }
        playerNavViewInsets.value = insets
        return insets
    }

    fun setNavInsets(insets: Insets) {
        navViewInsets.value = insets
    }

    fun setSystemInsets(context: Context, insets: WindowInsetsCompat) {
        val system = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars())
        val display = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
        val inset = system.run {
            val top = if (top > 0) top else display.top
            val bottom = if (bottom > 0) bottom else display.bottom
            val left = if (left > 0) left else display.left
            val right = if (right > 0) right else display.right
            if (context.isRTL()) Insets(top, bottom, right, left)
            else Insets(top, bottom, left, right)
        }
        systemInsets.value = inset
    }

    fun setPlayerInsets(context: Context, isVisible: Boolean) {
        val insets = if (isVisible) {
            val height = context.resources.getDimensionPixelSize(R.dimen.collapsed_cover_size)
            Insets(bottom = height + 8.dpToPx(context))
        } else Insets()
        playerInsets.value = insets
    }

    val playerBgVisible = MutableStateFlow(false)

    private fun getState() =
        if (playerState.current.value != null) STATE_COLLAPSED else STATE_HIDDEN

    val playerSheetState = MutableStateFlow(getState())
    val tvMiniPlayerVisible = MutableStateFlow(false)
    val playerSheetOffset = MutableStateFlow(0f)
    val moreSheetState = MutableStateFlow(STATE_COLLAPSED)
    val moreSheetOffset = MutableStateFlow(0f)
    val playerBackProgress = MutableStateFlow(0f)
    private var playerBackPressCallback: OnBackPressedCallback? = null
    private var moreBackPressCallback: OnBackPressedCallback? = null
    fun backPressCallback() = object : OnBackPressedCallback(false) {
        val backPress
            get() = moreBackPressCallback ?: playerBackPressCallback

        override fun handleOnBackStarted(backEvent: BackEventCompat) {
            if (playerBgVisible.value) return
            backPress?.handleOnBackStarted(backEvent)
        }

        override fun handleOnBackProgressed(backEvent: BackEventCompat) {
            if (playerBgVisible.value) return
            backPress?.handleOnBackProgressed(backEvent)
        }

        override fun handleOnBackPressed() {
            if (playerBgVisible.value) {
                changeBgVisible(false)
                return
            }
            backPress?.handleOnBackPressed()
        }

        override fun handleOnBackCancelled() {
            if (playerBgVisible.value) return
            backPress?.handleOnBackCancelled()
        }
    }

    fun collapsePlayer() {
        changePlayerState(getState())
        changeMoreState(STATE_COLLAPSED)
    }

    private var playerBehaviour = WeakReference<BottomSheetBehavior<View>>(null)
    // Sheet view, stashed in setupPlayerBehavior, so changePlayerState can check isLaidOut / defer via
    // doOnLayout. Needed because the WeakReference<BottomSheetBehavior> alone exposes no view handle.
    private var playerSheetViewRef = WeakReference<View>(null)
    // Latest requested state awaiting a layout-safe apply. Single field → coalesces multiple pre-layout
    // calls to the last one; both the immediate-apply path and the deferred runnable clear/guard on it
    // so a stale earlier deferral can never land after a newer request.
    private var pendingPlayerState: Int? = null

    fun changePlayerState(state: Int) {
        val behavior = playerBehaviour.get() ?: return
        // (iii) Flow write is synchronous on EVERY call, so playerSheetState is the honest coordination
        // variable: it suppresses/orders later callers whose guards read it (the notification and the
        // MainActivity current-observer both branch on STATE_HIDDEN), and a same-state physical no-op
        // can never leave the flow stale (which was what pinned updateCollapsed to top-origin geometry).
        playerSheetState.value = state
        // (ii) The physical apply must never race first measurement: setting behavior.state before the
        // sheet is laid out computes the collapsed offset against parentHeight≈0 and lands it at screen
        // top. Once laid out, parentHeight is known and the offset is correct regardless of peekHeight
        // (a still-pending inset-corrected peekHeight only shifts it by ~systemInsets.bottom, which
        // BottomSheetBehavior.setPeekHeight re-settles on its own) — so layout is the ONLY gate needed.
        val view = playerSheetViewRef.get()
        // STATE_HIDDEN parks the sheet fully off-screen; unlike COLLAPSED it has no peek offset to compute
        // against a not-yet-measured parent, so it is safe to apply BEFORE layout — and must be, so a no-track
        // cold start parks hidden from the first onLayoutChild instead of drawing a frame of the XML-default
        // COLLAPSED bar and then sliding down. (COLLAPSED/EXPANDED still defer until laid out.)
        if (view == null || view.isLaidOut || state == STATE_HIDDEN) {
            pendingPlayerState = null
            applyPlayerBehaviorState(behavior, state)
            return
        }
        val alreadyPending = pendingPlayerState != null
        pendingPlayerState = state
        if (alreadyPending) return
        view.doOnLayout {
            val pending = pendingPlayerState ?: return@doOnLayout
            pendingPlayerState = null
            playerBehaviour.get()?.let { applyPlayerBehaviorState(it, pending) }
        }
    }

    // SOLE owner of behavior.isHideable. Sets it to match the target state SYNCHRONOUSLY with behavior.state,
    // within this one Main-thread task — never left lagging for the async onStateChanged to correct (that lag
    // was the drag-dismiss race). A sheet must be hideable to reach HIDDEN, and must NOT be hideable while
    // shown, or a drag could dismiss it — which this app no longer supports. Going to HIDDEN: enable, then
    // hide. Going to a shown state: set it (COLLAPSED/EXPANDED are reachable regardless), then disable — so
    // the instant the bar is draggable, hideable is already false. The only time isHideable stays true is
    // while genuinely HIDDEN (empty queue), when there is no bar to drag.
    private fun applyPlayerBehaviorState(behavior: BottomSheetBehavior<View>, state: Int) {
        if (state == STATE_HIDDEN) {
            behavior.isHideable = true
            behavior.state = STATE_HIDDEN
        } else {
            behavior.state = state
            behavior.isHideable = false
        }
    }

    private var moreBehaviour = WeakReference<BottomSheetBehavior<View>>(null)
    fun changeMoreState(state: Int) {
        val behavior = moreBehaviour.get() ?: return
        behavior.state = state
    }

    var lastMoreTab = R.id.queue
    var playerControlsHeight = MutableStateFlow(0)
    val playerDrawable = MutableStateFlow<Drawable?>(null)
    val playerColors = MutableStateFlow<PlayerColors?>(null)

    val mainBgDrawable = playerDrawable.combine(extensionColor) { a, b -> a ?: b }

    fun changeBgVisible(show: Boolean) {
        playerBgVisible.value = show
        if (!show && moreSheetState.value == STATE_EXPANDED)
            changeMoreState(STATE_COLLAPSED)
    }

    companion object {
        const val BACKGROUND_GRADIENT = "bg_gradient"
        suspend fun Fragment.applyGradient(view: View, drawable: Drawable?) {
            val settings = requireContext().getSettings()
            val isGradient = settings.getBoolean(BACKGROUND_GRADIENT, true)
            val source = if (isGradient) {
                drawable ?: MaterialColors.getColor(view, androidx.appcompat.R.attr.colorPrimary)
                    .toDrawable()
            } else null
            val context = view.context
            val bitmap: Bitmap? = source?.toBitmap(
                source.intrinsicWidth.coerceAtLeast(1),
                source.intrinsicHeight.coerceAtLeast(1)
            )
            val bg = withContext(Dispatchers.Default) {
                GradientDrawable.createBlurred(context, bitmap)
            }
            view.background = bg
        }

        fun Fragment.applyInsets(vararg flows: Flow<*>, block: UiViewModel.(Insets) -> Unit) {
            val uiViewModel by activityViewModel<UiViewModel>()
            val flows = listOf(uiViewModel.combined) + flows
            observe(flows.merge()) { uiViewModel.block(uiViewModel.combined.value) }
        }

        fun Fragment.applyInsetsWithChild(
            appBar: View,
            child: View?,
            bottom: Int = 12,
            block: UiViewModel.(Insets) -> Unit = {}
        ) {
            val uiViewModel by activityViewModel<UiViewModel>()
            observe(uiViewModel.combined) { insets ->
                child?.updatePadding(
                    bottom = insets.bottom + bottom.dpToPx(child.context),
                )
                appBar.updatePaddingRelative(
                    top = insets.top,
                    start = insets.start,
                    end = insets.end
                )
                uiViewModel.block(insets)
            }
        }

        fun View.applyContentInsets(
            insets: Insets, horizontal: Int = 0, vertical: Int = 0, bottom: Int = 0
        ) {
            val horizontalPadding = horizontal.dpToPx(context)
            val verticalPadding = vertical.dpToPx(context)
            updatePaddingRelative(
                top = verticalPadding,
                bottom = insets.bottom + verticalPadding + bottom.dpToPx(context),
                start = insets.start + horizontalPadding,
                end = insets.end + horizontalPadding
            )
        }

        fun View.applyInsets(it: Insets, vertical: Int, horizontal: Int, bottom: Int = 0) {
            val verticalPadding = vertical.dpToPx(context)
            val horizontalPadding = horizontal.dpToPx(context)
            val bottomPadding = bottom.dpToPx(context)
            updatePaddingRelative(
                top = verticalPadding + it.top,
                bottom = bottomPadding + verticalPadding + it.bottom,
                start = horizontalPadding + it.start,
                end = horizontalPadding + it.end,
            )
        }

        fun View.applyInsets(it: Insets, paddingDp: Int = 0) {
            val padding = paddingDp.dpToPx(context)
            updatePaddingRelative(
                top = it.top + padding,
                bottom = it.bottom + padding,
                start = it.start + padding,
                end = it.end + padding,
            )
        }

        fun View.applyHorizontalInsets(it: Insets, isLandScape: Boolean = false) {
            updatePaddingRelative(
                start = if (!isLandScape) it.start else 0,
                end = it.end
            )
        }

        fun View.applyFabInsets(it: Insets, system: Insets, paddingDp: Int = 0) {
            val padding = paddingDp.dpToPx(context)
            updatePaddingRelative(
                bottom = it.bottom - system.bottom + padding,
                start = it.start + padding,
                end = it.end + padding,
            )
        }

        fun Fragment.applyBackPressCallback(callback: ((Int) -> Unit)? = null) {
            val activity = requireActivity()
            val viewModel by activity.viewModel<UiViewModel>()
            val backPress = viewModel.backPressCallback()
            observe(viewModel.playerSheetState) {
                backPress.isEnabled = it == STATE_EXPANDED
                callback?.invoke(it)
            }
            activity.onBackPressedDispatcher.addCallback(viewLifecycleOwner, backPress)
        }

        private fun BottomSheetBehavior<View>.backPressCallback(
            onProgress: (Float) -> Unit = {},
        ) = object : OnBackPressedCallback(true) {
            override fun handleOnBackStarted(backEvent: BackEventCompat) {
                startBackProgress(backEvent)
                onProgress(0f)
            }

            override fun handleOnBackProgressed(backEvent: BackEventCompat) {
                updateBackProgress(backEvent)
                onProgress(min(1f, backEvent.progress * 2))
            }

            override fun handleOnBackPressed() {
                handleBackInvoked()
                onProgress(0f)
            }

            override fun handleOnBackCancelled() {
                cancelBackProgress()
                onProgress(0f)
            }
        }

        const val NAVBAR_GRADIENT = "navbar_gradient"
        fun MainActivity.setupNavBarAndInsets(
            uiViewModel: UiViewModel,
            root: View,
            navView: NavigationBarView
        ) {
            val isRail = navView is NavigationRailView
            uiViewModel.isRail = isRail
            ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
                uiViewModel.setSystemInsets(this, insets)
                val navBarSize = uiViewModel.systemInsets.value.bottom
                val full = getSettings().getBoolean(NAVBAR_GRADIENT, true)
                binding.navGradientOverlay?.let { GradientDrawable.applyNav(it, isRail, navBarSize, !full) }
                binding.navGradientOverlay?.isVisible = full
                insets
            }

            navView.setOnItemSelectedListener {
                uiViewModel.navigation.value = uiViewModel.navIds.indexOf(it.itemId)
                true
            }
            var lastTappedItemId: Int? = null
            navView.menu.forEach {
                val itemIndex = uiViewModel.navIds.indexOf(it.itemId)
                val itemId = it.itemId
                findViewById<View>(it.itemId).setOnClickListener { _ ->
                    if (!uiViewModel.isMainFragment.value) {
                        supportFragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
                    }
                    val isCurrentTab = navView.selectedItemId == itemId
                    if (isCurrentTab && lastTappedItemId == itemId)
                        uiViewModel.run { emit(navigationReselected, itemIndex) }
                    lastTappedItemId = if (isCurrentTab) itemId else null
                    navView.selectedItemId = itemId
                }
            }

            fun animateNav(animate: Boolean) {
                val isMainFragment = uiViewModel.isMainFragment.value
                val insets =
                    uiViewModel.setPlayerNavViewInsets(this, true, isRail)
                val isPlayerCollapsed = uiViewModel.playerSheetState.value != STATE_EXPANDED
                navView.animateTranslation(isRail, isMainFragment, isPlayerCollapsed, animate) {
                    uiViewModel.setNavInsets(insets)
                    if (isPlayerCollapsed) navView.updateLayoutParams<MarginLayoutParams> {
                        bottomMargin = -it.toInt()
                    }
                }
            }

            animateNav(false)
            supportFragmentManager.addOnBackStackChangedListener {
                val current = supportFragmentManager.findFragmentById(R.id.navHostFragment)
                val isMain = current is MainFragment
                uiViewModel.isMainFragment.value = isMain
                animateNav(true)
            }
            observe(uiViewModel.navigation) { navView.selectedItemId = uiViewModel.navIds[it] }
            observe(uiViewModel.playerSheetOffset) {
                if (!uiViewModel.isMainFragment.value) return@observe
                val offset = max(0f, it)
                if (isRail) navView.translationX = -navView.width * offset
                else navView.translationY = navView.height * offset
                navView.menu.forEach { item ->
                    findViewById<View>(item.itemId).apply {
                        translationX = 0f
                        translationY = 0f
                    }
                }
            }
            observe(uiViewModel.playerSheetState) { animateNav(true) }
        }

        fun isFinalState(state: Int): Boolean {
            return state == STATE_HIDDEN || state == STATE_COLLAPSED || state == STATE_EXPANDED
        }

        fun LifecycleOwner.setupPlayerBehavior(viewModel: UiViewModel, view: View, isTV: Boolean = false, navRailContainer: ViewGroup? = null) {
            val behavior = BottomSheetBehavior.from(view)
            viewModel.playerBehaviour = WeakReference(behavior)
            viewModel.playerSheetViewRef = WeakReference(view)
            observe(viewModel.moreSheetState) { behavior.isDraggable = it == STATE_COLLAPSED }
            viewModel.playerBackPressCallback = behavior.backPressCallback {
                viewModel.playerBackProgress.value = it
            }

            val combined =
                viewModel.run { playerNavViewInsets.combine(systemInsets) { nav, _ -> nav } }
            observe(combined) {
                // Landscape (rail) sits the mini-player flush to the screen bottom: peek is just the
                // bar height (values-land 64dp), WITHOUT the bottom system inset — the gesture bar is
                // at the bottom in landscape, and adding it floated the pill up by that inset.
                // Portrait keeps values/ (136dp) + systemInsets.bottom to clear the bottom nav.
                val height =
                    view.resources.getDimensionPixelSize(R.dimen.bottom_player_peek_height)
                val newHeight = height +
                    if (viewModel.isRail) 0 else viewModel.systemInsets.value.bottom
                behavior.peekHeight = newHeight
                if (viewModel.playerSheetState.value != STATE_HIDDEN)
                    animateTranslation(view, behavior.peekHeight, newHeight)
            }
            val callback = object : BottomSheetBehavior.BottomSheetCallback() {
                override fun onStateChanged(bottomSheet: View, newState: Int) {
                    // TV force-route: TV has no collapsed bar, so a COLLAPSED settle means "minimize" → hide.
                    // Owns its own isHideable (TV-only, atomic with the hide in this same task); the phone owner
                    // is applyPlayerBehaviorState. Phone (isTV=false) never enters this branch.
                    if (isTV && newState == STATE_COLLAPSED) {
                        behavior.isHideable = true
                        behavior.state = STATE_HIDDEN
                        viewModel.playerSheetState.value = STATE_HIDDEN
                        return
                    }
                    // Track the physical state into the flow. isHideable is NOT touched here — the phone has no
                    // dismiss gesture; hideable is owned end-to-end by applyPlayerBehaviorState.
                    viewModel.playerSheetState.value = newState
                    if (!isFinalState(newState)) return
                    if (isTV) {
                        val hidden = newState == STATE_HIDDEN
                        (bottomSheet as? ViewGroup)?.apply {
                            descendantFocusability = if (hidden) ViewGroup.FOCUS_BLOCK_DESCENDANTS
                                                     else ViewGroup.FOCUS_BEFORE_DESCENDANTS
                            importantForAccessibility = if (hidden)
                                View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                            else
                                View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                        }
                        navRailContainer?.apply {
                            descendantFocusability = if (hidden) ViewGroup.FOCUS_AFTER_DESCENDANTS
                                                     else ViewGroup.FOCUS_BLOCK_DESCENDANTS
                            importantForAccessibility = if (hidden)
                                View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                            else
                                View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                        }
                    }
                    viewModel.setPlayerInsets(view.context, newState != STATE_HIDDEN)
                    onSlide(view, if (newState == STATE_EXPANDED) 1f else 0f)
                }

                override fun onSlide(bottomSheet: View, slideOffset: Float) {
                    viewModel.playerSheetOffset.value = slideOffset
                }
            }
            val state = viewModel.playerSheetState.value
            callback.onStateChanged(view, state)
            callback.onSlide(view, if (state == STATE_EXPANDED) 1f else 0f)
            behavior.addBottomSheetCallback(callback)
            // The seeding above only updates bookkeeping (flow, insets); it does NOT move the sheet, which
            // still sits at its XML-default COLLAPSED. On phone with no track that is a blank peek-height bar
            // on screen until the current observer's first (null) emission lands a changePlayerState(HIDDEN) —
            // a coroutine dispatch, not a frame, away. Drive the physical sheet to the initial state now so it
            // settles on the first layout pass (HIDDEN applies pre-layout via changePlayerState). TV is excluded
            // because its isTV branch in onStateChanged already moved the sheet during the seeding above.
            if (!isTV) viewModel.changePlayerState(state)
        }

        fun setupPlayerMoreBehavior(viewModel: UiViewModel, view: View) {
            val behavior = BottomSheetBehavior.from(view)
            viewModel.moreBehaviour = WeakReference(behavior)
            val backPress = behavior.backPressCallback()
            val callback = object : BottomSheetBehavior.BottomSheetCallback() {
                override fun onStateChanged(bottomSheet: View, newState: Int) {
                    viewModel.moreSheetState.value = newState
                    viewModel.moreBackPressCallback =
                        backPress.takeIf { newState != STATE_COLLAPSED }
                }

                override fun onSlide(bottomSheet: View, slideOffset: Float) {
                    val offset = max(0f, slideOffset)
                    viewModel.moreSheetOffset.value = offset
                }
            }
            val state = viewModel.moreSheetState.value
            callback.onStateChanged(view, state)
            callback.onSlide(view, if (state == STATE_EXPANDED) 1f else 0f)
            behavior.addBottomSheetCallback(callback)
        }

        fun SwipeRefreshLayout.configure(it: Insets = Insets()) {
            setProgressViewOffset(true, it.top, 72.dpToPx(context) + it.top)
        }
    }
}