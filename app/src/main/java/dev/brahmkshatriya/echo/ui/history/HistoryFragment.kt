package dev.brahmkshatriya.echo.ui.history

import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.databinding.FragmentHistoryBinding
import dev.brahmkshatriya.echo.ui.player.PlayerViewModel
import dev.brahmkshatriya.echo.utils.ContextUtils.observe
import dev.brahmkshatriya.echo.utils.ui.AnimationUtils.setupTransition
import org.koin.androidx.viewmodel.ext.android.viewModel

class HistoryFragment : Fragment(R.layout.fragment_history) {

    private val viewModel by viewModel<HistoryViewModel>()
    private val playerViewModel by activityViewModels<PlayerViewModel>()
    private val adapter = HistoryAdapter { item ->
        val track = item.track ?: return@HistoryAdapter
        val context = item.context
        if (context is EchoMediaItem.Lists) {
            playerViewModel.play(item.extensionId, context, false, track.id)
        } else {
            playerViewModel.setQueue(item.extensionId, listOf(track), 0, context)
            playerViewModel.setPlaying(true)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentHistoryBinding.bind(view)
        setupTransition(view)

        val backCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                parentFragmentManager.popBackStack()
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)

        binding.toolBar.setNavigationOnClickListener {
            backCallback.handleOnBackPressed()
        }
        binding.toolBar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.menu_clear_history) {
                viewModel.clearHistory()
                true
            } else false
        }
        binding.recyclerView.adapter = adapter
        observe(viewModel.history) { adapter.submitList(it) }
    }
}
