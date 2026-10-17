package dev.brahmkshatriya.echo.ui.player.quality

import android.annotation.SuppressLint
import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.core.view.isVisible
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastStateListener
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.slider.Slider
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.cast.CastAvailability
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.databinding.DialogPlayerQualitySelectionBinding
import dev.brahmkshatriya.echo.playback.MediaItemUtils.backgroundIndex
import dev.brahmkshatriya.echo.playback.MediaItemUtils.serverIndex
import dev.brahmkshatriya.echo.playback.MediaItemUtils.serverWithDownloads
import dev.brahmkshatriya.echo.playback.MediaItemUtils.subtitleIndex
import dev.brahmkshatriya.echo.playback.MediaItemUtils.track
import dev.brahmkshatriya.echo.ui.common.FragmentUtils.openFragment
import dev.brahmkshatriya.echo.ui.player.PlayerViewModel
import dev.brahmkshatriya.echo.ui.player.quality.FormatUtils.getDetails
import dev.brahmkshatriya.echo.ui.player.quality.FormatUtils.getSelected
import dev.brahmkshatriya.echo.ui.player.quality.FormatUtils.toAudioDetails
import dev.brahmkshatriya.echo.ui.player.quality.FormatUtils.toSubtitleDetails
import dev.brahmkshatriya.echo.ui.player.quality.FormatUtils.toVideoDetails
import dev.brahmkshatriya.echo.ui.settings.SettingsPlayerFragment
import dev.brahmkshatriya.echo.utils.ContextUtils.observe
import dev.brahmkshatriya.echo.utils.ui.AutoClearedValue.Companion.autoCleared
import org.koin.androidx.viewmodel.ext.android.activityViewModel

class QualitySelectionBottomSheet : BottomSheetDialogFragment() {
    var binding by autoCleared<DialogPlayerQualitySelectionBinding>()
    private val viewModel by activityViewModel<PlayerViewModel>()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
    ): View {
        binding = DialogPlayerQualitySelectionBinding.inflate(inflater, container, false)
        return binding.root
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.topAppBar.setNavigationOnClickListener { dismiss() }
        binding.topAppBar.setOnMenuItemClickListener {
            dismiss()
            requireActivity().openFragment<SettingsPlayerFragment>()
            true
        }

        bindOutput()
        bindCast()

        observe(viewModel.buffering) {
            binding.progressIndicator.isVisible = it
        }
        observe(viewModel.playerState.current) { current ->
            val item = current?.mediaItem ?: return@observe
            val track = item.track
            binding.run {
                applyChips(
                    item.serverWithDownloads(requireContext()),
                    streamableServer, streamableServerGroup, item.serverIndex
                ) {
                    it ?: return@applyChips
                    viewModel.changeServer(it)
                }
                streamableServer.isVisible = true
                streamableServerGroup.isVisible = true

                applyChips(
                    listOf(null, *track.backgrounds.toTypedArray()), streamableBackgrounds,
                    streamableBackgroundGroup, item.backgroundIndex + 1
                ) {
                    viewModel.changeBackground(it)
                }

                applyChips(
                    listOf(null, *track.subtitles.toTypedArray()), streamableSubtitles,
                    streamableSubtitleGroup, item.subtitleIndex + 1
                ) {
                    viewModel.changeSubtitle(it)
                }
            }
        }

        observe(viewModel.serverAndTracks) { (tracks, server, index) ->
            val list = if (server != null && !server.merged) server.sources else listOf()
            binding.run {
                applyChips(
                    list, streamableSource, streamableSourceGroup, index,
                    { viewModel.changeCurrentSource(list.indexOf(it)) }) {
                    it.title ?: getString(R.string.quality_x, it.quality)
                }
            }
            val details = tracks?.getDetails(requireContext(), server, index)?.joinToString("\n")
            binding.streamableInfo.text = details
            binding.streamableInfo.isVisible = !details.isNullOrBlank()

            val audios = tracks?.groups?.filter { it.type == C.TRACK_TYPE_AUDIO }
            val videos = tracks?.groups?.filter { it.type == C.TRACK_TYPE_VIDEO }
            val subtitles = tracks?.groups?.filter { it.type == C.TRACK_TYPE_TEXT }

            val onClick: Chip.(Pair<Tracks.Group, Int>?) -> Unit = {
                val trackGroup = it!!.first.mediaTrackGroup
                viewModel.changeTrackSelection(trackGroup, it.second)
            }

            binding.run {
                applyChips(audios, trackAudios, trackAudiosGroup, onClick) {
                    it.toAudioDetails()
                }
                applyChips(videos, trackVideos, trackVideosGroup, onClick) {
                    it.toVideoDetails()
                }
                applyChips(subtitles, trackSubtitles, trackSubtitlesGroup, onClick) {
                    it.toSubtitleDetails()
                }
            }
        }
    }

    private var castContext: CastContext? = null
    private var castStateListener: CastStateListener? = null

    /**
     * BUILD 1: DISCOVERY AND LOGGING ONLY. Nothing here plays, queues or resolves - the button
     * opens the system route chooser and a connect produces a log line, nothing more. Playback
     * still goes to the local player whatever this says.
     *
     * ⚠⚠ THIS IS THE ONLY PLACE THE CAST FRAMEWORK IS EVER INITIALISED, AND THAT IS
     * THE POINT OF SHIPPING IT FIRST. CastContext.getSharedInstance starts the framework and its
     * discovery and can throw (Play Services missing or stale, OptionsProvider unloadable after
     * R8); behind a sheet-open it costs one tap and a hidden button, where on an every-launch path
     * it would cost the app. See the note at CastAvailability.
     *
     * ⚠️ WHAT THE CAPTURE SHOULD SAY, stated before the build so the log is readable:
     * opening the sheet with no Chromecast on the network prints castState=noDevices and the
     * button stays hidden (MediaRouteButton hides itself); with one present, castState=notConnected
     * and the button appears; tapping through to a connect prints connecting then connected. A
     * `cast init failed:` line instead means the framework never came up, and its throwable name
     * says whether that is Play Services or the R8 keep rule.
     */
    private fun bindCast() {
        val context = CastAvailability.castContext(requireContext()) ?: return
        castContext = context
        // Wrapped separately from the init above: setUpMediaRouteButton touches the framework's
        // route selector, and a failure there must still leave the rest of the sheet working.
        val wired = runCatching {
            CastButtonFactory.setUpMediaRouteButton(requireContext(), binding.castSlot)
        }.isSuccess
        if (wired) binding.castSlot.isVisible = true
        Log.d(
            CastAvailability.TAG,
            "sheet opened: button=" + (if (wired) "wired" else "failed") +
                " castState=" + CastAvailability.stateName(context.castState)
        )
        val listener = CastStateListener { state ->
            Log.d(CastAvailability.TAG, "castState -> " + CastAvailability.stateName(state))
        }
        runCatching { context.addCastStateListener(listener) }
            .onSuccess { castStateListener = listener }
    }

    // Paired with bindCast. The CastContext is a process singleton, so the listener outlives this
    // sheet unless it is removed here - and the sheet is opened repeatedly, so leaking one per
    // open would multiply the log lines and hold a reference to a destroyed fragment.
    override fun onDestroyView() {
        castStateListener?.let { listener ->
            runCatching { castContext?.removeCastStateListener(listener) }
        }
        castStateListener = null
        castContext = null
        super.onDestroyView()
    }

    /**
     * The output row and the system-volume slider. READ-ONLY toward playback: the row is a label
     * plus a hand-off to system settings, and the slider writes media3's deviceVolume (the
     * STREAM_MUSIC system volume), never player.volume - see the note at
     * PlayerViewModel.deviceVolume.
     *
     * ⚠️ THE OUTPUT LABEL IS READ ONCE, AT BIND. It is not observed, because the
     * sheet is modal and short-lived - a route change while it is open is rare enough that a
     * stale label costs less than an AudioDeviceCallback registration and its unregister. The
     * VOLUME half IS live, because hardware keys are pressed while the sheet is open all the
     * time. If route changes ever need to be live, that is an AudioDeviceCallback here, not a
     * change to AudioOutput.
     */
    private fun bindOutput() {
        val output = AudioOutput.current(requireContext())
        binding.outputIcon.setImageResource(output.icon)
        binding.outputLabel.text = output.name ?: getString(output.label)
        binding.outputRow.setOnClickListener {
            AudioOutput.openSystemOutputSettings(requireContext())
        }

        // Write on STOP only. Each write is a cross-process setStreamVolume, so a per-pixel
        // listener would fire dozens of them per drag; and the system volume moving under the
        // thumb mid-drag is what makes a slider feel like it is fighting the user.
        binding.volumeSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) = Unit
            override fun onStopTrackingTouch(slider: Slider) {
                viewModel.setDeviceVolume(slider.value.toInt())
            }
        })
        // The percentage follows the thumb during the drag even though the write does not, so the
        // number is never stale while the finger is down. fromUser keeps this off the
        // programmatic path, which the observer below owns.
        binding.volumeSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) binding.outputVolumePercent.text = getString(
                R.string.percent_x, viewModel.deviceVolume.value.percentFor(value.toInt())
            )
        }

        observe(viewModel.deviceVolume) { volume ->
            binding.volumeSlider.isEnabled = volume.available
            binding.outputVolumePercent.text =
                getString(R.string.percent_x, volume.percent)
            // ⚠⚠ WIDEN, SET, THEN NARROW - AND THAT ORDER IS THE WHOLE POINT.
            // Slider.validateValues() THROWS IllegalStateException whenever value falls outside
            // valueFrom..valueTo, and it runs on EVERY one of these three setters - so the
            // obvious range-then-value order crashes whenever the new range is NARROWER than the
            // old one and the current value sits above the new valueTo. That is not a corner
            // case: BT and the built-in speaker have different STREAM_MUSIC maxima (commonly 15
            // vs 25), so it is one headphone unplug with the sheet open.
            // Lowering valueFrom and raising valueTo can never fail, so the window is expanded to
            // cover both ranges first, the value is assigned inside it, and only then is the
            // window narrowed to the real range - by which point the value is already inside it.
            // stepSize=1 stays satisfied throughout because every bound here is an integer.
            // isPressed keeps our own drag from being overwritten by the echo of its own write.
            if (!volume.available || binding.volumeSlider.isPressed) return@observe
            val low = volume.min.toFloat()
            val high = volume.max.toFloat()
            runCatching {
                binding.volumeSlider.valueFrom = minOf(binding.volumeSlider.valueFrom, low)
                binding.volumeSlider.valueTo = maxOf(binding.volumeSlider.valueTo, high)
                binding.volumeSlider.value =
                    volume.volume.coerceIn(volume.min, volume.max).toFloat()
                binding.volumeSlider.valueFrom = low
                binding.volumeSlider.valueTo = high
            }
        }
    }

    @OptIn(UnstableApi::class)
    private fun applyChips(
        groups: List<Tracks.Group>?,
        textView: TextView,
        chipGroup: ChipGroup,
        onClick: Chip.(Pair<Tracks.Group, Int>?) -> Unit,
        text: (Format) -> String,
    ) {
        val (trackGroups, select) = groups.orEmpty().getSelected()
        applyChips(trackGroups, textView, chipGroup, select, onClick) {
            val format = it.first.getTrackFormat(it.second)
            text(format)
        }
    }

    private fun applyChips(
        streamables: List<Streamable?>,
        textView: TextView,
        chipGroup: ChipGroup,
        selected: Int?,
        onClick: Chip.(Streamable?) -> Unit,
    ) {
        val context = chipGroup.context
        applyChips(streamables, textView, chipGroup, selected, onClick) {
            it?.let {
                it.title ?: when (it.type) {
                    Streamable.MediaType.Subtitle -> context.getString(R.string.unknown)
                    else -> context.getString(R.string.quality_x, it.quality)
                }
            } ?: context.getString(R.string.off)
        }
    }

    private fun <T> applyChips(
        list: List<T>,
        textView: TextView,
        chipGroup: ChipGroup,
        selected: Int?,
        onClick: Chip.(T) -> Unit,
        text: (T) -> String,
    ) {
        val visible = list.size > 1
        textView.isVisible = visible
        chipGroup.apply {
            isVisible = visible
            removeAllViews()
            list.forEachIndexed { index, t ->
                val chip = Chip(context)
                chip.text = text(t)
                chip.ellipsize = TextUtils.TruncateAt.MIDDLE
                chip.isCheckable = true
                addView(chip)
                if (index == selected) check(chip.id)
                chip.setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) onClick(chip, t)
                }
            }
        }
    }
}