package dev.brahmkshatriya.echo.ui.history

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.ConcatAdapter
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Radio
import dev.brahmkshatriya.echo.databinding.FragmentHistoryBinding
import dev.brahmkshatriya.echo.playback.MediaItemUtils.trackRadioPlaceholder
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
            // Load the context fresh and play current+upcoming from the tapped track's fresh version
            // (backfillQueue now sets+plays the whole queue). The stored track is NOT replayed — only
            // its id locates the fresh one — so a frozen/stale streamable token can't skip the tap.
            playerViewModel.backfillQueue(item.extensionId, context, false, track.id)
        } else {
            // Bare-track / Radio seed: stamp a display-only "<track> Radio" context so the header reads
            // "Playing from <track> Radio" from the first second. It's marked LABEL_ONLY_RADIO, so radio
            // generation still runs off a null context (unchanged) — this is purely the label.
            val seedContext =
                if (context is Radio || context == null) trackRadioPlaceholder(track) else context
            playerViewModel.setQueue(item.extensionId, listOf(track), 0, seedContext)
            playerViewModel.setPlaying(true)
        }
    }

    private val speechLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.let { viewModel.searchQuery.value = it }
        }
    }

    private fun launchVoiceSearch() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.voice_search))
        }
        runCatching { speechLauncher.launch(intent) }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentHistoryBinding.bind(view)
        setupTransition(view, false, MaterialSharedAxis.Y)

        applyBackPressCallback()
        applyInsets(binding.recyclerView, binding.appBarOutline) {
            binding.swipeRefresh.configure(it)
        }

        val titleAdapter = HistoryTitleAdapter(
            onClearClick = { viewModel.clearHistory() },
            onSortClick = { HistorySortBottomSheet().show(childFragmentManager, "history_sort") },
            onSearchChanged = { viewModel.searchQuery.value = it },
            onMicClick = { launchVoiceSearch() },
        )

        binding.recyclerView.adapter =
            ConcatAdapter(HeaderAdapter(this), titleAdapter, adapter)
        observe(viewModel.history) { adapter.submitList(it) }
        binding.swipeRefresh.run {
            setOnRefreshListener { viewModel.refresh() }
            observe(viewModel.isRefreshingFlow) { isRefreshing = it }
        }
    }
}
