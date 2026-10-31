package dev.brahmkshatriya.echo.ui.history

import dev.brahmkshatriya.echo.ui.common.TvAwareRecyclerView
import androidx.recyclerview.widget.RecyclerView
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
import dev.brahmkshatriya.echo.databinding.FragmentHistoryBinding
import dev.brahmkshatriya.echo.playback.MediaItemUtils.isReplayableContext
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
        if (context.isReplayableContext()) {
            // Load the context fresh and play current+upcoming from the tapped track's fresh version
            // (backfillQueue now sets+plays the whole queue). The stored track is NOT replayed — only
            // its id locates the fresh one — so a frozen/stale streamable token can't skip the tap.
            // respectShuffle: a History tap rebuilds the whole album/playlist, so it is a queue set
            // like any other and follows the same rule as a feed tap - shuffle ON keeps the tapped
            // track first and shuffles the rest. The seed path below sets a ONE-TRACK queue, where
            // there is nothing to shuffle, so it stays as it was.
            playerViewModel.backfillQueue(
                item.extensionId, context, false, track.id, respectShuffle = true
            )
        } else {
            // Seed path: force the STARTING TRACK, keep the station. The queue is set to the tapped track
            // alone; PlayerRadio then generates from it and APPENDS (PlayerRadio.play uses addMediaItems),
            // so the tapped track plays first and the station continues behind it.
            //
            // ⚠️ THE REAL CONTEXT PASSES THROUGH — the placeholder is for context == null ONLY. A stored
            // Radio keeps its own id/title/cover, so "Neil Young Radio / Heart of Gold" plays Heart of Gold
            // and then continues as Neil Young Radio. That works because loadPlaylist's marker strip is a
            // `takeUnless` aimed at LABEL_ONLY_RADIO alone: an unmarked Radio reaches radio(track, context)
            // untouched, and DeezerRadioClient's Track branch preserves station identity per RadioKind
            // (ARTIST rebuilds from context.id/title, PLAYLIST/ALBUM return the context itself).
            // Replacing it with trackRadioPlaceholder would discard the station's name AND regenerate from
            // the track instead — a different station wearing the wrong label.
            //
            // ⚠️ Radio REACHES THIS BRANCH ONLY BECAUSE OF isReplayableContext: Radio is an
            // EchoMediaItem.Lists, so a plain `is Lists` test above swallowed it and the old
            // `context is Radio` check here was dead code from July until 2026-09-04. Do not weaken that
            // predicate back to a type test.
            //
            // The placeholder still earns its keep for a bare track (context == null): there is no station
            // to name, so it stamps a display-only "<track> Radio" header from the first second, marked
            // LABEL_ONLY_RADIO so generation still runs off a null context exactly as before.
            val seedContext = context ?: trackRadioPlaceholder(track)
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
        // The TV layout's list is a TvAwareRecyclerView, so the binding types it as View (the same cast Home,
        // Library and Search make).
        val recyclerView = binding.recyclerView as RecyclerView
        (recyclerView as? TvAwareRecyclerView)?.navRailView =
            requireActivity().findViewById(R.id.navRailContainer)
        applyInsets(recyclerView, binding.appBarOutline) {
            binding.swipeRefresh.configure(it)
        }

        val titleAdapter = HistoryTitleAdapter(
            onClearClick = { viewModel.clearHistory() },
            onSortClick = { HistorySortBottomSheet().show(childFragmentManager, "history_sort") },
            onSearchChanged = { viewModel.searchQuery.value = it },
            onMicClick = { launchVoiceSearch() },
        )

        recyclerView.adapter =
            ConcatAdapter(HeaderAdapter(this), titleAdapter, adapter)
        observe(viewModel.history) { adapter.submitList(it) }
        binding.swipeRefresh.run {
            setOnRefreshListener { viewModel.refresh() }
            observe(viewModel.isRefreshingFlow) { isRefreshing = it }
        }
    }
}
