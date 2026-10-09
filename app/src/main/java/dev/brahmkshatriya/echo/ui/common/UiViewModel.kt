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

    /**
     * Every inset a content view must reserve: system bars + the nav bar + the collapsed player.
     *
     * ⚠⚠ THE NAV TERM IS UNCONDITIONAL, AND IT USED TO BE GATED ON isMainFragment. That gate
     * was correct when written and WRONG FOR FOUR MONTHS AFTERWARDS. Two commits on 2026-05-29 split
     * the nav bar's VISIBILITY from its INSET and neither updated this line:
     *   280e68d0 "nav bar visibility" changed AnimationUtils.animateTranslation from
     *     `visible = if (isRail) true else (isMainFragment && isPlayerCollapsed)` to
     *     `visible = if (isRail) true else isPlayerCollapsed` - so the phone nav bar stopped hiding
     *     on sub-pages and became visible on EVERY page unless the player sheet is expanded;
     *   3e6f301e "player bar overlap" changed setPlayerNavViewInsets(this, isMainFragment, isRail)
     *     to (this, true, isRail) - so the WRITTEN inset stopped varying too.
     * Before those, isMainFragment==false meant the bar was hidden AND navViewInsets was already
     * Insets(), so this gate was redundant-but-correct. After them it dropped 64dp of inset for a bar
     * that was still on screen.
     *
     * ⚠⚠ WHY NOTHING NOTICED FOR FOUR MONTHS, AND WHY ANDROID AUTO EXPOSED IT. isMainFragment
     * was read with .value INSIDE this combine while not being one of the combined flows, so `combined`
     * never recomputed when it changed. Drilling into an artist/album page set it false and left this
     * flow STALE - still carrying the nav bar's 64dp - so the padding was accidentally right. The bug
     * appeared only when something else made an upstream flow emit and forced a recompute, and in
     * practice that was AA: playerInsets is Insets() or Insets(bottom=72dp) and Insets is a data class,
     * so MutableStateFlow conflates equal writes and ONLY a HIDDEN<->shown sheet transition emits.
     * "First track of the session arrives while you are already standing on a detail page" is the AA
     * case; starting playback on the phone happens before you drill in, so the stale value survives.
     * Measured on device: the last row of an artist page clears with no AA and is covered by ~one row
     * after AA connects. MediaDetailsFragment pads by combined.bottom + 16dp against a 136dp peek, so
     * the shortfall was 48dp there.
     *
     * ⚠️ IT IS SAFE TO ADD THE NAV TERM UNCONDITIONALLY RATHER THAN GATING IT ON
     * "sheet not expanded", because every consumer that cares about the expanded state branches away
     * BEFORE the nav term can reach it - checked, not assumed:
     *   getSnackbarInsets returns Insets() for STATE_EXPANDED in an earlier return;
     *   PlayerFragment's combined observer uses `system`, not getCombined(), when STATE_EXPANDED;
     *   PlayerFragment's playerCollapsedContainer uses getCombined() unconditionally but through
     *     applyHorizontalInsets, which writes start/end only - the phone nav inset is bottom-only;
     *   PlayerTrackAdapter reads getCombined() only when isLandscape, where the nav is a RAIL and its
     *     inset is start/end.
     * A VERSION GATED ON playerSheetState WAS SCOPED AND REJECTED: the only input it treated
     * differently was "non-main page while expanded", which none of the above can observe, and it cost
     * two extra flows in the combine and therefore a new emission on every navigation and every sheet
     * state change. Do not reintroduce it without a consumer that needs the distinction.
     *
     * ⚠️ AND THE LAMBDA NOW READS NO MUTABLE STATE OUTSIDE ITS FLOWS, which is the structural
     * point rather than a tidy-up: isRail is a `var` assigned once in setupNavBarAndInsets before any
     * layout, so there is nothing left here that can go stale the way isMainFragment did. If you ever
     * add a term to this lambda, it must be a COMBINED FLOW, not a .value read.
     */
    val combined = systemInsets.combine(navViewInsets) { system, nav ->
        system.add(nav)
    }.combine(playerInsets) { system, player ->
        system.add(player)
    }.stateIn(viewModelScope, Lazily, Insets())

    /** Imperative twin of [combined]. Same terms, same reasoning - see that doc before changing either. */
    fun getCombined() =
        systemInsets.value.add(navViewInsets.value).add(playerInsets.value)

    /**
     * What a snackbar must clear. No systemInsets term, deliberately - a Snackbar's own parent already
     * applies those.
     *
     * ⚠️ THE STATE_EXPANDED EARLY RETURN IS WHY THE NAV TERM BELOW NEEDS NO GATE: an expanded
     * sheet covers the screen, and this returns before any nav/player term is reached. The second line
     * was `if (isMainFragment.value || isRail)` with a bare `playerInsets.value` fallback until
     * 2026-10-03; see [combined] for the four-month-old split that made that gate wrong.
     */
    fun getSnackbarInsets(): Insets {
        if (playerSheetState.value == STATE_EXPANDED) return Insets()
        return navViewInsets.value.add(playerInsets.value)
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

    /**
     * ⚠⚠ THE VALUE WRITTEN HERE IS EFFECTIVELY FROZEN, AND UN-FREEZING IT IS A TRAP. Its only
     * caller is animateNav, which passes setPlayerNavViewInsets(this, true, isRail) - `isNavVisible`
     * hardcoded true since 3e6f301e (2026-05-29). So on a phone this always writes
     * Insets(bottom = nav_height), Insets is a data class, and MutableStateFlow conflates equal values:
     * navViewInsets has not actually changed since setup.
     *
     * ⚠️ THAT ACCIDENT KILLED A REAL RACE, which is why restoring a varying write is not the
     * "more correct" fix it looks like. AnimationUtils.animateTranslation fires its action at the
     * animation START when the bar appears and at the END when it leaves - deliberately asymmetric, so
     * space is reserved before the bar arrives and released after it goes. A varying write from inside
     * those callbacks is the "animation window race" the 2026-05-26/27 work recorded and deliberately
     * left alone on phone: back then animateNav ran on EVERY drill-down and pop (the bar hid per page),
     * so two navigations could land their callbacks out of order and strand the wrong inset.
     * ⚠️ AND IT WOULD OPEN A NEW HOLE TODAY: animateNav is called from exactly two places
     * (setup, and the back-stack listener) and NEVER on a sheet-state change. So if the player were
     * expanded during a navigation, a varying write would set this to Insets() and nothing would
     * restore it when the user collapsed the player - the same missing-64dp bug with a new trigger.
     * The 2026-10-03 fix therefore changed the READERS ([combined] and friends) and left this frozen.
     */
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

    // Resting sheet state. Phone: a loaded track rests at COLLAPSED (the peek/mini bar). TV: there is NO
    // COLLAPSED resting state — the sheet rests HIDDEN behind a SEPARATE mini bar, and the TV force-route
    // in onStateChanged bounces any COLLAPSED settle straight back to HIDDEN. So on TV, returning COLLAPSED
    // here made collapsePlayer() (called on every drill-down via openFragment) drive the sheet
    // HIDDEN→COLLAPSED→HIDDEN — a ~1–2s double settle during which updateVisibility() hid the mini bar
    // (its "sheetHidden" gate went false then true). Rest HIDDEN on TV so drill-down is a no-op for the sheet.
    private fun getState() =
        if (!isTv && playerState.current.value != null) STATE_COLLAPSED else STATE_HIDDEN

    val playerSheetState = MutableStateFlow(getState())

    // ⚠⚠ THE SINGLE WRITER OF playerInsets, AND IT IS A FLOW COLLECTOR BECAUSE THE OLD ONE
    // WAS A TRANSITION CALLBACK. setPlayerInsets used to be called from exactly one place - inside
    // onStateChanged, after its `if (!isFinalState(newState)) return` gate. BottomSheetBehavior calls
    // onStateChanged on a TRANSITION, so if the sheet is laid out already at its resting state the
    // callback never fires and playerInsets stays Insets() for the WHOLE PROCESS while the mini player
    // is visibly on screen. changePlayerState writes this flow and NOT the inset, so even a corrected
    // state left the inset at zero unless the sheet actually moved.
    // ⚠️ WHAT THAT LOOKED LIKE, AND WHY IT READ AS TWO UNRELATED BUGS: playerInsets is a
    // term of BOTH [combined] and [getSnackbarInsets], so a zero there puts the playlist editor's
    // Add-song/Save card AND every error snackbar behind the mini player, app-wide. A rotation cured it
    // because the sheet is recreated and settles - a real transition - while this ViewModel is
    // activity-scoped and SURVIVES the config change, so the corrected value persisted for the rest of
    // the process. That combination (survives restarts, cured by rotation) looked like persistent state
    // and is not: nothing here is written to disk.
    // ⚠️ SAME PREDICATE AS THE CALLBACK IT REPLACES (!= STATE_HIDDEN), so this is not a
    // behaviour change where the callback did fire - it only removes the dependence on whether it
    // fired at all. Insets is a data class and MutableStateFlow conflates equal values, so a redundant
    // write costs nothing.
    // ⚠⚠ WHY THE SEED IS EXACTLY WHAT THIS CLOSES, AND IT IS THE WHOLE POINT. getState()
    // reads playerState.current.value at CONSTRUCTION. playerState is a Koin process singleton while
    // this ViewModel is activity-scoped, so an Activity created fresh WHILE A TRACK IS ALREADY LOADED
    // seeds STATE_COLLAPSED. The phone's sole track-driven sheet driver
    // (PlayerFragment's playerState.current collector - read its note) then does
    // `else if (playerSheetState.value == STATE_HIDDEN) changePlayerState(STATE_COLLAPSED)`, which is
    // FALSE against a COLLAPSED seed - so no state write, and before this collector existed, no inset
    // write either, because the sheet was laid out at its resting state and fired no transition.
    // Collecting a StateFlow delivers its CURRENT value on subscribe, so this fires on the seed itself
    // and the inset is correct with no transition needed.
    // ⚠️ [CORRECTED 2026-10-08] AN EARLIER VERSION OF THIS NOTE SAID THE RESIDUAL WAS "the
    // seed says HIDDEN while the sheet peeks", blamed on MainActivity's observe(playerState.current)
    // being RESUMED-gated. BOTH HALVES WERE WRONG. That observer lives in setupTvMiniPlayer, which
    // early-returns on `R.id.tvMiniPlayer ?: return` - an id only in layout-land-television - so it has
    // never run on a phone; PlayerFragment's note records that the same mistake cost debugging time on
    // 2026-08-23 and says in terms not to reason about phone sheet state from that guard. And the
    // residual it described is unreachable anyway: a HIDDEN seed means current was null at
    // construction, i.e. no track and therefore no peek, and the first non-null emission then drives
    // COLLAPSED through the phone driver, which this collector follows.
    // ⚠️ context IS A PLAIN CONSTRUCTOR PARAMETER, captured in a coroutine that outlives the
    // constructor - the same shape `navigation` above already uses for saveToCache. Not a new pattern.
    init {
        viewModelScope.launch {
            playerSheetState.collect { setPlayerInsets(context, it != STATE_HIDDEN) }
        }
    }
    // True only on the TV surface (set in setupPlayerBehavior). TV rests at STATE_HIDDEN with a separate
    // mini bar and has no drag gesture, so it must stay isHideable=true (needed to reach HIDDEN, and there
    // is no drag to dismiss). applyPlayerBehaviorState reads this to keep isHideable=false phone-only —
    // otherwise setHideable(false) while Material still reads HIDDEN force-collapses a from-HIDDEN expand.
    var isTv = false
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
    private fun applyPlayerBehaviorState(behavior: BottomSheetBehavior<View>, requested: Int) {
        // Chokepoint guard: setState only accepts STABLE states. If a transient (SETTLING/DRAGGING) ever reaches
        // here — e.g. a mid-drag activity recreation re-applying a stored state — coerce it to the resting default
        // getState() (COLLAPSED if a track is loaded, else HIDDEN), since a recreated activity must place the sheet
        // AT REST, not at a mid-drag position. Final states pass through unchanged, so normal expand/collapse/hide
        // restore is untouched. Makes the "STATE_SETTLING should not be set externally" crash structurally impossible.
        val state = if (isFinalState(requested)) requested else getState()
        if (state == STATE_HIDDEN) {
            behavior.isHideable = true
            behavior.state = STATE_HIDDEN
        } else {
            behavior.state = state
            // Phone-only: isHideable=false prevents a drag-dismiss of the always-visible mini (COLLAPSED).
            // On TV there is no drag gesture AND the sheet rests HIDDEN with isHideable=true; setting it false
            // here — while Material still reads HIDDEN because the expand settle was posted (dirty layout) —
            // makes setHideable(false) force setState(COLLAPSED), which the TV force-hide branch then turns into
            // HIDDEN (the "expand collapses to mini" bug). Leaving TV hideable=true expands cleanly and keeps
            // its HIDDEN-rest/minimize model. Phone still expands from COLLAPSED where isHideable is already false.
            if (!isTv) behavior.isHideable = false
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

        /**
         * ⚠⚠ DO NOT "TIDY" THE combined.value READ BELOW INTO getCombined() - IT LOOKS
         * EQUIVALENT AND WAS A NEAR-MISS. While the nav term was gated on isMainFragment (see
         * [combined]), the two differed in exactly the way that mattered: `combined` did not observe
         * isMainFragment, so on a detail page it served a STALE nav-inclusive value and the bottom
         * padding came out right by accident, whereas getCombined() evaluates the gate FRESH at call
         * time with nothing to mask it. Swapping it in would have dropped the nav bar's 64dp on EVERY
         * detail page immediately, turning an Android-Auto-only symptom into a permanent one.
         * The gate is gone as of 2026-10-03, so the two are equivalent again TODAY - but the next
         * divergence between the flow and the imperative reader will have the same shape, and this
         * helper is where it would land.
         * ⚠️ The block always reads combined.value rather than the merged flow's emission
         * because `flows` may carry unrelated triggers (a result flow, a visibility flag); the inset is
         * read fresh on every tick regardless of which flow woke it.
         */
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
                child?.updatePaddingRelative(
                    start = insets.start,
                    end = insets.end,
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

        // TV / phone-landscape rail parity for headers that only use configureAppBar (which applies NO
        // inset): pad the AppBar start by the rail inset so its content (nav icon, title) clears the left
        // rail, matching the FeedFragment/MediaFragment header fix. isRail-gated, so phone portrait never
        // registers it (header untouched); start-only, so fitsSystemWindows still owns the top status-bar
        // inset (no duplication). Do NOT use on pages whose AppBar already gets start via applyInsetsWithChild.
        fun Fragment.applyAppBarRailInset(appBar: View) {
            val uiViewModel by activityViewModel<UiViewModel>()
            if (uiViewModel.isRail) observe(uiViewModel.combined) {
                appBar.updatePaddingRelative(start = it.start)
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
                val insets =
                    uiViewModel.setPlayerNavViewInsets(this, true, isRail)
                val isPlayerCollapsed = uiViewModel.playerSheetState.value != STATE_EXPANDED
                navView.animateTranslation(isRail, isPlayerCollapsed, animate) {
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
            // Set before any expand can occur (setup runs before user interaction) so applyPlayerBehaviorState
            // keeps isHideable=false phone-only. Uses the real isTV signal, NOT isRail (phone landscape is rail too).
            viewModel.isTv = isTV
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
                    // Track the physical state into the flow — but ONLY final states. SETTLING/DRAGGING are
                    // transient and Material forbids passing them to setState; storing one let a mid-drag activity
                    // recreation re-apply it (setupPlayerBehavior -> changePlayerState -> behavior.state = SETTLING)
                    // and crash. Gating the write keeps playerSheetState's observers (which branch on
                    // EXPANDED/HIDDEN) off transient values too; the smooth drag is tracked separately via onSlide
                    // -> playerSheetOffset. isHideable is NOT touched here — the phone has no dismiss gesture;
                    // hideable is owned end-to-end by applyPlayerBehaviorState.
                    if (!isFinalState(newState)) return
                    viewModel.playerSheetState.value = newState
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
                        // #5: land on play/pause now that the sheet has physically settled and its
                        // descendants are unblocked (above). Driving off the settle removes the race
                        // where the old flow-driven landing ran while still BLOCK_DESCENDANTS. The
                        // window-focus case (settle while window unfocused) is covered by the
                        // MainActivity arbiter, not here.
                        if (newState == STATE_EXPANDED)
                            bottomSheet.findViewById<View>(R.id.tv_track_play_pause)?.requestFocus()
                    }
                    // ⚠️ setPlayerInsets USED TO BE CALLED HERE AND MUST NOT COME BACK. The
                    // write now lives in the init collector beside playerSheetState, which this line
                    // already updates a few lines above - so the inset follows from that write instead
                    // of from this callback having fired. Re-adding it would restore two writers of one
                    // value whose whole defect was that this one did not always run.
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