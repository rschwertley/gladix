package dev.brahmkshatriya.echo.ui.history

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.ConcatAdapter
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Radio
import dev.brahmkshatriya.echo.databinding.FragmentHistoryBinding
import dev.brahmkshatriya.echo.ui.main.HeaderAdapter
import dev.brahmkshatriya.echo.ui.main.MainFragment.Companion.applyInsets
import dev.brahmkshatriya.echo.ui.player.PlayerViewModel
import dev.brahmkshatriya.echo.ui.common.UiViewModel.Companion.applyBackPressCallback
import dev.brahmkshatriya.echo.ui.common.UiViewModel.Companion.configure
import dev.brahmkshatriya.echo.utils.ContextUtils.observe
import com.google.android.material.transition.MaterialSharedAxis
import dev.brahmkshatriya.echo.utils.ui.AnimationUtils.setupTransition
import org.koin.androidx.viewmodel.ext.android.viewModel

class HistoryFragment : Fragment(R.layout.fragment_history) {

    private val viewModel by viewModel<HistoryViewModel>()
    private val playerViewModel by activityViewModels<PlayerViewModel>()
    private val adapter = HistoryAdapter(this) { item ->
        val track = item.track ?: return@HistoryAdapter
        val context = item.context
        if (context is EchoMediaItem.Lists) {
            playerViewModel.play(item.extensionId, context, false, track.id)
        } else {
            playerViewModel.setQueue(item.extensionId, listOf(track), 0, if (context is Radio) null else context)
            playerViewModel.setPlaying(true)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentHistoryBinding.bind(view)
        setupTransition(view, false, MaterialSharedAxis.Y)

        applyBackPressCallback()
        applyInsets(binding.recyclerView, binding.appBarOutline) {
            binding.swipeRefresh.configure(it)
        }
        binding.recyclerView.adapter =
            ConcatAdapter(HeaderAdapter(this), HistoryTitleAdapter { viewModel.clearHistory() }, adapter)
        observe(viewModel.history) { adapter.submitList(it) }
        binding.swipeRefresh.run {
            setOnRefreshListener { viewModel.refresh() }
            observe(viewModel.isRefreshingFlow) { isRefreshing = it }
        }
    }
}
