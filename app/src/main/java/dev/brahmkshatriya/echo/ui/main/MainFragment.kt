package dev.brahmkshatriya.echo.ui.main

import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.recyclerview.widget.RecyclerView
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.databinding.FragmentMainBinding
import dev.brahmkshatriya.echo.databinding.ItemMainHeaderBinding
import dev.brahmkshatriya.echo.ui.common.FragmentUtils.addIfNull
import dev.brahmkshatriya.echo.ui.common.UiViewModel
import dev.brahmkshatriya.echo.ui.common.UiViewModel.Companion.BACKGROUND_GRADIENT
import dev.brahmkshatriya.echo.ui.common.UiViewModel.Companion.applyGradient
import dev.brahmkshatriya.echo.ui.common.UiViewModel.Companion.applyInsets
import dev.brahmkshatriya.echo.ui.history.HistoryFragment
import dev.brahmkshatriya.echo.ui.main.search.SearchFragment
import dev.brahmkshatriya.echo.utils.ContextUtils.getSettings
import dev.brahmkshatriya.echo.utils.ContextUtils.observe
import dev.brahmkshatriya.echo.utils.ui.AnimationUtils.setupTransition
import dev.brahmkshatriya.echo.utils.ui.AutoClearedValue.Companion.autoCleared
import dev.brahmkshatriya.echo.utils.ui.FastScrollerHelper
import dev.brahmkshatriya.echo.utils.ui.FastScrollerHelper.applyInsets
import dev.brahmkshatriya.echo.utils.ui.UiUtils.dpToPx
import dev.brahmkshatriya.echo.utils.ui.UiUtils.isTv
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import org.koin.androidx.viewmodel.ext.android.activityViewModel
import kotlin.math.max

class MainFragment : Fragment() {

    private var binding by autoCleared<FragmentMainBinding>()
    private val viewModel by activityViewModel<UiViewModel>()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        binding = FragmentMainBinding.inflate(inflater, container, false)
        return binding.root
    }

    private inline fun <reified F : Fragment> Fragment.addIfNull(tag: String): String {
        addIfNull<F>(R.id.main_fragment_container_view, tag)
        return tag
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        setupTransition(view)
        applyPlayerBg(view) {
            mainBgDrawable(requireContext()).combine(currentNavBackground) { a, b -> b ?: a }
        }
        observe(viewModel.navigation) {
            val toShow = when (it) {
                1 -> addIfNull<SearchFragment>("search")
                2 -> addIfNull<LibraryFragment>("library")
                3 -> addIfNull<HistoryFragment>("history")
                else -> addIfNull<HomeFragment>("home")
            }

            childFragmentManager.commit(true) {
                childFragmentManager.fragments.forEach { fragment ->
                    if (fragment.tag != toShow) hide(fragment)
                    else show(fragment)
                }
                setReorderingAllowed(true)
            }
        }
    }

    companion object {
        fun Fragment.applyPlayerBg(
            view: View,
            imageFlow: UiViewModel.() -> Flow<Drawable?>
        ): UiViewModel {
            val uiViewModel by activityViewModel<UiViewModel>()
            val combined = uiViewModel.imageFlow()
            observe(combined) { applyGradient(view, it) }
            return uiViewModel
        }

        fun Fragment.applyInsets(
            recyclerView: RecyclerView,
            outline: View,
            bottom: Int = 0,
            block: UiViewModel.(UiViewModel.Insets) -> Unit = {}
        ) {
            recyclerView.run {
                val height = 48.dpToPx(context)
                val settings = context.getSettings()
                val isGradient = settings.getBoolean(BACKGROUND_GRADIENT, true)
                val extra = if (isGradient) 0.5f else 0f
                setOnScrollChangeListener { _, _, _, _, _ ->
                    val offset =
                        computeVerticalScrollOffset().coerceAtMost(height) / height.toFloat()
                    outline.alpha = max(0f, offset - extra)
                }
            }
            // Full-bleed: no appBar, so the header terms of the scroll metrics are zero. [2026-11-13] traceTag is retired
            // with the GladixScroll trace (FastScrollerHelper.applyTo).
            val scroller = FastScrollerHelper.applyTo(recyclerView)
            // [2026-11-25] The track starts BELOW the header row (HeaderAdapter, item_main_header: extensions button,
            // title, account icon at top right), which is row 0 of the list on all four callers - Home, Library, Search,
            // History. It started 8dp under the status bar, so at the top of the list the thumb sat on that row and covered
            // the account icon until it auto-hid. The header is MEASURED, not hard-coded (48dp today: 32dp icon + 2 x 4dp
            // card padding + 2 x 4dp margin, and the 48dp title); measured once, as its height is fixed dp and does not
            // follow its content. Only the track's drawing rect moves: the scroll range reads the LIST's own padding
            // (PixelFastScrollViewHelper's "+ paddingTop + paddingBottom" note), which is unchanged. Null on TV and with
            // the setting off, where there is no scroller to inset.
            val headerHeight = if (scroller != null) recyclerView.measureMainHeader() else 0
            applyInsets {
                // The TV mini-player reserve is in combined itself now (UiViewModel.combined, 2026-10-31); do not add it here.
                // TV overscan safe-area (phone: isTv() == false → 20/20, unchanged). `it.start` already carries
                // the nav-rail width inset, so 48 is added on top of it (left = rail + 48, right = 48).
                val tv = recyclerView.context.isTv()
                val vertical = if (tv) 27 else 20
                recyclerView.applyInsets(it, vertical, if (tv) 48 else 20, bottom + 4)
                outline.updatePadding(top = it.top)
                // Was an inline Rect here; extracted to FastScrollerHelper so the seven direct-call sites
                // get the same treatment instead of keeping applyTo's flat 8dp forever.
                // [2026-11-25] top = where the header row ends: status bar + the list's top padding + the header. The
                // helper's own 8dp then sits between the header and the track.
                scroller.applyInsets(
                    recyclerView.context, it, bottom,
                    top = it.top + vertical.dpToPx(recyclerView.context) + headerHeight
                )
                block(it)
            }
        }

        // [2026-11-25] Height of the header row the four main tabs put first in the list, from the same layout
        // HeaderAdapter.ViewHolder inflates. Detached and measured unconstrained: its height is fixed dp.
        private fun RecyclerView.measureMainHeader(): Int {
            val header = ItemMainHeaderBinding.inflate(LayoutInflater.from(context), this, false).root
            header.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            return header.measuredHeight
        }
    }
}