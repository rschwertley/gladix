package dev.brahmkshatriya.echo.ui.playlist.edit

import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.ConcatAdapter
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Tab
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.databinding.FragmentPlaylistEditBinding
import dev.brahmkshatriya.echo.ui.common.FragmentUtils.openFragment
import dev.brahmkshatriya.echo.ui.common.UiViewModel.Companion.applyBackPressCallback
import dev.brahmkshatriya.echo.ui.common.UiViewModel.Companion.applyInsets
import dev.brahmkshatriya.echo.ui.common.UiViewModel.Companion.applyInsetsWithChild
import dev.brahmkshatriya.echo.ui.feed.TabsAdapter
import dev.brahmkshatriya.echo.ui.playlist.edit.EditPlaylistBottomSheet.Companion.toText
import dev.brahmkshatriya.echo.ui.playlist.edit.search.EditPlaylistSearchFragment
import dev.brahmkshatriya.echo.utils.ContextUtils.observe
import dev.brahmkshatriya.echo.utils.Serializer.getSerialized
import dev.brahmkshatriya.echo.utils.Serializer.putSerialized
import dev.brahmkshatriya.echo.utils.ui.AnimationUtils.setupTransition
import dev.brahmkshatriya.echo.utils.ui.AutoClearedValue.Companion.autoCleared
import dev.brahmkshatriya.echo.utils.ui.FastScrollerHelper
import dev.brahmkshatriya.echo.utils.ui.FastScrollerHelper.applyInsets
import dev.brahmkshatriya.echo.utils.ui.UiUtils.configureAppBar
import kotlinx.coroutines.flow.combine
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.koin.core.parameter.parametersOf

class EditPlaylistFragment : Fragment() {

    companion object {
        fun getBundle(extension: String, playlist: Playlist, loaded: Boolean) = Bundle().apply {
            putString("extensionId", extension)
            putSerialized("playlist", playlist)
            putBoolean("loaded", loaded)
        }
    }

    private val args by lazy { requireArguments() }
    private val extensionId by lazy { args.getString("extensionId")!! }
    private val playlist by lazy { args.getSerialized<Playlist>("playlist")!!.getOrThrow() }
    private val loaded by lazy { args.getBoolean("loaded", false) }
    private val selectedTab by lazy { args.getString("selectedTabId").orEmpty() }

    private var binding: FragmentPlaylistEditBinding by autoCleared()
    private val vm by viewModel<EditPlaylistViewModel> {
        parametersOf(extensionId, playlist, loaded, selectedTab, -1)
    }

    private val adapter: PlaylistTrackAdapter by lazy {
        lateinit var adapterRef: PlaylistTrackAdapter
        val (listener, itemCallback) = PlaylistTrackAdapter.getTouchHelperAndListener(vm) { adapterRef }
        itemCallback.attachToRecyclerView(binding.recyclerView)
        adapterRef = PlaylistTrackAdapter(listener)
        adapterRef
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentPlaylistEditBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        setupTransition(view)
        // Hoisted above the inset block so the handle exists when it first runs; kept and re-padded there
        // rather than left on applyTo's flat 8dp.
        val scroller = FastScrollerHelper.applyTo(binding.recyclerView)
        // ⚠⚠ TEMPORARY PROBE - EditPlaylistInsets, 2026-10-08. REMOVE together with the
        // two widened reads in UiViewModel. REMOVAL CONDITION, POSITIVE AND NAMED: one capture
        // containing an `insets cfg=port` line from the FIRST editor open on a FRESH INSTALL, plus a
        // `cfg=land` line after rotating. Silence therefore means THIS BLOCK NEVER RAN - it can never
        // mean "nothing was wrong", which is the trap an absence-based condition would set.
        // ⚠️ IT MAY ALREADY BE UNCATCHABLE, AND THAT IS NOT A REASON TO WEAKEN IT. The
        // symptom stopped after one rotation and survived leave/reopen, swipe-away and force-close, so
        // whatever ordering produced it is not recurring in this install. If the fresh-install capture
        // comes back entirely healthy, the finding is "first launch after update only" - record that
        // and stop, rather than re-reading the healthy log for a mechanism.
        //
        // WHAT EACH OUTPUT WOULD LET US CONCLUDE - written BEFORE the build so the log cannot be read
        // to confirm whatever we already believe. navBottom and playerBottom are the two terms of
        // combined that are not systemInsets:
        //   navBottom=0 on open, non-zero after rotation    -> setNavInsets had NOT run yet. Its only
        //     caller is animateNav -> animateTranslation, which is doOnLayout-gated and calls action()
        //     at AnimationUtils:102, so navViewInsets is Insets() from process start until the nav
        //     view's FIRST LAYOUT. The card was then padded short and sat behind the bar. Fix belongs
        //     at the ordering, not here.
        //   playerBottom=0 on open  -> THE DIAGNOSED CAUSE, now fixed app-wide. playerInsets had only
        //     one writer and it was inside onStateChanged, which BottomSheetBehavior calls on a
        //     TRANSITION - so a sheet laid out already at its resting state never wrote it. It is now
        //     written by the collector beside UiViewModel.playerSheetState. Seeing playerBottom=0 here
        //     on a build that HAS that collector means the remaining half is live: playerSheetState
        //     itself is wrong (seed says HIDDEN while the sheet peeks), which the collector cannot fix.
        //   both non-zero and EQUAL port vs land, but cardY off-screen on open -> padding was always
        //     right; the fault is measure/position, and cardY vs rootH is what decides it.
        //   combinedBottom != systemBottom + navBottom + playerBottom -> a term is being dropped in
        //     the combine itself, which would be a defect in [combined] rather than in its inputs.
        //   insets line present, layout line absent         -> the container never laid out; look at
        //     child order and visibility, not at insets.
        //   no lines at all on open                         -> applyInsetsWithChild's observe did not
        //     fire before first layout, which is itself the answer.
        // ⚠️ cfg AND density ARE CAPTURED OUT HERE, NOT INSIDE THE LAMBDA, BECAUSE THE
        // BLOCK'S RECEIVER IS UiViewModel - which has no `resources`. The inset terms below come from
        // that receiver; `binding` and these two locals come from the fragment.
        val cfg = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
            "land" else "port"
        val density = resources.displayMetrics.density
        applyInsetsWithChild(binding.appBarLayout, binding.recyclerView, 96) {
            binding.fabContainer.applyInsets(it)
            scroller.applyInsets(binding.recyclerView.context, it)
            Log.d(
                "EditPlaylistInsets",
                "insets cfg=$cfg combinedBottom=${it.bottom} systemBottom=${systemInsets.value.bottom}" +
                    " navBottom=${navViewInsets.value.bottom}" +
                    " playerBottom=${playerInsets.value.bottom}" +
                    " top=${it.top} density=$density"
            )
            // Captured locally: `binding` is autoCleared(), so touching it from a posted runnable
            // after the view is gone would throw. The view reference is safe on its own.
            val fab = binding.fabContainer
            fab.post {
                val fabLoc = IntArray(2)
                fab.getLocationOnScreen(fabLoc)
                val card = fab.getChildAt(0)
                val cardLoc = IntArray(2)
                card?.getLocationOnScreen(cardLoc)
                // fab is wrap_content, so a CORRECTLY laid-out container always satisfies
                // height >= paddingTop + paddingBottom + cardHeight. If it does not, the laid-out
                // geometry PREDATES the current padding - the measurement the fix below turns on.
                // ⚠⚠ THE CARD'S OWN HEIGHT IS PART OF `needed`, AND LEAVING IT OUT MAKES THE
                // TEST SILENTLY TOO WEAK. A container laid out as card + OLD padding can still clear
                // the NEW padding sum on its own - e.g. card 64dp + old padding 48dp = 112dp, which is
                // >= a new padding sum of 200dp only sometimes, but >= a new sum of 96dp always. So
                // stale would read false while the card was still sitting behind the mini player. The
                // padding sum alone is not a height any correct layout has to reach; padding + child is.
                // ⚠️ TWO WAYS THIS STAYS CONSERVATIVE, BOTH DELIBERATE - IT CAN MISS, NEVER
                // FALSE-POSITIVE:
                //   card?.height is read from the SAME stale layout, so it can be short (or 0 before the
                //     card is ever measured, which degrades this to the padding-only test rather than
                //     breaking it - and the next combined emission re-checks).
                //   FrameLayout's measured height includes the child's margins, which `needed` does not
                //     count - the card carries layout_marginBottom=12dp - so `needed` under-counts by up
                //     to that margin.
                // Both err toward not firing. That is the right direction: a missed repair leaves the
                // pre-existing bug, while a spurious one would requestLayout on every inset emission.
                val needed = fab.paddingTop + fab.paddingBottom + (card?.height ?: 0)
                val stale = fab.height < needed
                Log.d(
                    "EditPlaylistInsets",
                    "layout cfg=$cfg visible=${fab.isVisible} padTop=${fab.paddingTop}" +
                        " padBottom=${fab.paddingBottom} needed=$needed fabY=${fabLoc[1]}" +
                        " fabH=${fab.height} stale=$stale" +
                        " cardY=${if (card == null) -1 else cardLoc[1]} cardH=${card?.height ?: -1}" +
                        " rootH=${fab.rootView.height}"
                )
                // ⚠⚠ REPAIR REMOVED 2026-10-08 - SUPERSEDED BY THE APP-WIDE FIX AT
                // UiViewModel's playerInsets collector, which addresses the CAUSE (playerInsets never
                // written when the sheet fires no transition) rather than this screen's symptom. Two
                // fixes in one build would have blurred the capture: a correct card would not have said
                // WHICH one did it. The probe lines STAY - `playerBottom` in the insets line is the
                // direct confirmation, and `stale` still reports whether the laid-out geometry trails
                // the padding.
                // Kept below for the record: why the observed flow did not self-correct, which is the
                // part that looks impossible until the dispatcher is read.
                // ORIGINAL NOTE FOLLOWS.
                // ⚠⚠ WHY THE OBSERVED FLOW DID NOT SELF-CORRECT:
                // THAT LOOKS IMPOSSIBLE UNTIL THE DISPATCHER IS READ:
                //   UiViewModel.Insets.add SUMS its terms, so a late nav or player inset DOES change
                //     [combined] and DOES emit - the flow side is not at fault and was checked first.
                //   ContextUtils.observe is lifecycleScope.launch { flowWithLifecycle.collectLatest },
                //     i.e. Dispatchers.Main.IMMEDIATE. Already on the main thread, the collector can
                //     resume INLINE at the point of the `.value =` assignment.
                //   navViewInsets' only writer is setNavInsets, called from animateNav ->
                //     NavigationBarView.animateTranslation, which is `= doOnLayout { ... }` and calls
                //     action(value) at AnimationUtils:102 - i.e. INSIDE A LAYOUT TRAVERSAL.
                // Chain: nav view lays out -> setNavInsets -> combined emits -> this collector resumes
                // inline, still inside that traversal -> applyInsets -> updatePaddingRelative ->
                // requestLayout() issued DURING layout, where the framework suppresses it. The padding
                // is then CORRECT in the view while the laid-out geometry is stale, so the card keeps
                // its pre-inset position until any unrelated re-layout - which is exactly what a
                // rotation provided, permanently, with no state change anywhere.
                // ⚠️ THE SWALLOWED requestLayout IS INFERENCE - the first three steps are
                // read from source, the suppression is not (ViewRootImpl is not readable from here).
                // THAT IS WHY THE FIX IS WRITTEN AS AN INVARIANT REPAIR RATHER THAN AS A COUNTER TO
                // THAT MECHANISM: it asserts "the laid-out height accounts for the current padding"
                // and repairs it when false, so it holds whichever term arrived late, and holds even
                // if the mechanism above is wrong. Do not "simplify" it into something that only
                // handles a late nav inset.
                // ⚠️ SCOPED TO THIS SCREEN DELIBERATELY. Every applyInsets consumer shares
                // the hazard, and the general fix belongs in applyInsetsWithChild - but that would
                // change the layout timing of every screen in the app to fix one card. Local first;
                // generalise only with a reason to.
            }
        }

        applyBackPressCallback()
        binding.appBarLayout.configureAppBar { offset ->
            binding.toolbarOutline.alpha = offset
            binding.toolbarIconContainer.alpha = 1 - offset
        }

        binding.toolbar.setNavigationOnClickListener {
            parentFragmentManager.popBackStack()
        }

        binding.toolbar.setOnMenuItemClickListener {
            parentFragmentManager.setFragmentResult("delete", Bundle().apply {
                putSerialized("playlist", playlist)
            })
            parentFragmentManager.popBackStack()
            true
        }

        binding.save.setOnClickListener {
            vm.save()
        }
        observe(vm.isSaveable) {
            binding.save.isEnabled = it
        }

        binding.add.setOnClickListener {
            openFragment<EditPlaylistSearchFragment>(
                it, EditPlaylistSearchFragment.getBundle(extensionId)
            )
        }
        parentFragmentManager.setFragmentResultListener("searchedTracks", this) { _, bundle ->
            val tracks = bundle.getSerialized<List<Track>>("tracks")!!.getOrNull().orEmpty().toMutableList()
            vm.edit(
                EditPlaylistViewModel.Action.Add(
                    vm.currentTracks.value?.size ?: 0, tracks
                )
            )
        }

        val headerAdapter = EditPlaylistHeaderAdapter(this, vm)
        val tabAdapter = TabsAdapter<Tab>({ title }) { v, index, tab ->
            vm.selectedTabFlow.value = tab
        }

        binding.recyclerView.adapter = ConcatAdapter(headerAdapter, tabAdapter, adapter)
        observe(vm.dataFlow) { headerAdapter.data = it }
        observe(vm.tabsFlow) { tabAdapter.data = it }
        observe(vm.selectedTabFlow) { tabAdapter.selected = vm.tabsFlow.value.indexOf(it) }
        // ⚠⚠ DROPPED DURING A DRAG, NOT DEFERRED - AND THE DIRECTION OF STALENESS IS WHY.
        // This used to park the list in adapter.pendingList and replay it from clearView. Now that
        // onMove reorders the adapter itself, the ADAPTER holds the order the user is dragging to and
        // currentTracks is the side catching up, so a replay at drag end overwrites the correct order
        // with whatever currentTracks accumulated. Dropping is what QueueFragment.submitOrDefer settled
        // on, for the reason stated there: the adapter's drag-local order is already correct, and
        // re-submitting after the drag would only replay a stale snapshot.
        // ⚠️ ACCEPTED COST, STATED RATHER THAN WIDENED: an edit from another source mid-drag -
        // a swipe-to-remove, say - does not reach the list until the finger lifts and the next emission
        // arrives. currentTracks is a StateFlow, so that emission carries the latest value; nothing is
        // lost, it is only late.
        observe(vm.currentTracks) {
            if (adapter.isDragging) return@observe
            adapter.submitList(it)
        }

        val combined = vm.originalList.combine(vm.saveState) { a, b -> a to b }
        observe(combined) { (tracks, save) ->
            val trackLoading = tracks == null
            val saving = save != EditPlaylistViewModel.SaveState.Initial
            val loading = trackLoading || saving
            binding.recyclerView.isVisible = !loading
            binding.fabContainer.isVisible = !loading
            binding.loading.root.isVisible = loading
            binding.loading.textView.text = save.toText(playlist, requireContext())

            val save = save as? EditPlaylistViewModel.SaveState.Saved ?: return@observe
            if (save.result.isSuccess) parentFragmentManager.setFragmentResult(
                "reload", Bundle().apply { putString("id", playlist.id) }
            )
            parentFragmentManager.popBackStack()
        }
    }
}