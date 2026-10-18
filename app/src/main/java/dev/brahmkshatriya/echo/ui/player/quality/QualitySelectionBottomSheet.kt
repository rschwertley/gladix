package dev.brahmkshatriya.echo.ui.player.quality

import android.annotation.SuppressLint
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
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
                // ⚠⚠ THIS CHIP REFLECTS serverIndex, NOT THE AUDIO BEING DECODED, AND
                // IT IS NEVER EVIDENCE ABOUT BYTES. It shows which server was SELECTED; what the
                // decoder was handed is the Format on the selected track group, which is a different
                // thing whenever the extension steps a quality down on its own (DeezerTrackClient
                // .createStreamableForQuality does exactly that, at three rungs). Recorded here
                // because TWO investigations of "320 selected, 128 plays" were misled by reading
                // this chip as the answer - the 2026-10-16 capture settled it only once a decoder
                // Format was printed beside the chip and the two disagreed.
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
    private var audioDeviceCallback: AudioDeviceCallback? = null

    /**
     * The last step this sheet wrote, so a drag issues ONE write per integer step instead of one
     * per touch event. Null until the first write.
     *
     * ⚠⚠ RE-BASELINED BY THE OBSERVER ON EVERY EXTERNAL CHANGE, AND THAT IS NOT
     * TIDINESS - WITHOUT IT THE DEDUPE GOES WRONG. Drag to 7 (written, remembered), then press
     * hardware volume-down to 6, then drag back to 7: the step equals what we last wrote, the write
     * is skipped, and the slider sits at 7 while the stream is at 6. So the observer sets this to
     * whatever the system reports whenever it is not being dragged, which makes "what we wrote" and
     * "what the system has" the same number by construction.
     */
    private var lastWrittenVolume: Int? = null

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
    // ⚠️ EVERYTHING HERE HAPPENS BEFORE super.onDestroyView(), AND THE ORDER IS
    // LOAD-BEARING FOR THE AUDIO CALLBACK. AutoClearedValue clears `binding` from an onDestroy
    // observer that runs as the view lifecycle is destroyed; unregistering first is what keeps a
    // route change arriving during teardown from reaching a cleared binding. applyOutputIfAlive's
    // guard covers the already-posted case that unregistering cannot.
    override fun onDestroyView() {
        audioDeviceCallback?.let { callback ->
            AudioOutput.unregisterDeviceCallback(requireContext(), callback)
        }
        audioDeviceCallback = null
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
     * ⚠⚠ [CORRECTED 2026-10-17] THE OUTPUT LABEL IS LIVE. THIS SAID IT WAS READ
     * ONCE AT BIND AND THAT A ROUTE CHANGE WHILE THE SHEET IS OPEN WAS "RARE ENOUGH THAT A
     * STALE LABEL COSTS LESS THAN AN AudioDeviceCallback REGISTRATION AND ITS UNREGISTER".
     * That judgement was wrong on the frequency: connecting or disconnecting Bluetooth with the
     * sheet open left the previous device named on screen, which is worse than a missing label
     * because it reads as a fact. The registration is in [bindOutput] and the unregister in
     * [onDestroyView], which is what the old note was weighing the cost of.
     */
    private fun bindOutput() {
        applyOutput()
        binding.outputRow.setOnClickListener {
            AudioOutput.openSystemOutputSettings(requireContext())
        }

        // ⚠⚠ BOTH SIGNALS ARE REGISTERED BECAUSE NEITHER COVERS THE OTHER'S CASE,
        // AND THAT IS NOT BELT-AND-BRACES. AudioDeviceCallback fires when a device APPEARS OR
        // DISAPPEARS - plugging in headphones, a Bluetooth connect or drop. It does NOT fire when
        // the ACTIVE ROUTE moves between devices that are all already present, which is exactly
        // what the system output switcher does: nothing is added or removed, so the label would
        // stay wrong. That case arrives instead as a DeviceInfo change on the player, which the
        // deviceVolume observer below already collects - so the refresh there is the only cover
        // for it.
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) =
                applyOutputIfAlive()

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) =
                applyOutputIfAlive()
        }
        if (AudioOutput.registerDeviceCallback(requireContext(), callback)) {
            audioDeviceCallback = callback
        }

        // ⚠⚠ THE VOLUME IS WRITTEN LIVE WHILE DRAGGING, ONCE PER INTEGER STEP.
        // Every fromUser change reaches [writeVolumeStep], which writes only when the step actually
        // changes: stepSize=1 makes `value` integral already, but onValueChange fires far more often
        // than the step moves. A full-width drag of a STREAM_MUSIC range - commonly 15 or 25 steps -
        // is therefore tens of writes, which is what the system's own volume panel does.
        // ⚠⚠ ONE PER STEP IS A CORRECTNESS BOUND WHILE CASTING, NOT A TIDINESS ONE.
        // Locally a write is one binder call; with a Cast session live it is a CastSession.setVolume
        // out to the receiver over Wi-Fi. Per step that is simply what a remote volume slider has to
        // do; per touch EVENT it would be a packet storm. Do not relax this to every float change.
        // ⚠️ THE STEP MEMORY IS RE-BASELINED WHENEVER THE SYSTEM VOLUME MOVES ON ITS
        // OWN. The observer below adopts the reported value once the finger is off the slider, so a
        // hardware key press between two drags cannot leave the remembered step disagreeing with the
        // stream - see lastWrittenVolume for the sequence that needs it.
        // The stop write is a final flush through the same dedupe: free after a drag that already
        // wrote its last step, and it pins the value for a tap that settles in a single event.
        binding.volumeSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) = Unit
            override fun onStopTrackingTouch(slider: Slider) {
                writeVolumeStep(slider.value.toInt())
            }
        })
        // ⚠⚠ fromUser IS THE WHOLE FEEDBACK-LOOP GUARD, AND IT IS SUFFICIENT.
        // Material's Slider reports fromUser=false for a programmatic `value =` assignment, and the
        // ONLY programmatic assignment is the observer below - which is where a hardware key press
        // arrives (onDeviceVolumeChanged -> deviceVolume -> observer -> slider.value). So a key press
        // moves the thumb and writes nothing, and our own write's echo comes back through the same
        // fromUser=false path. There is no second mechanism needed and no flag to keep in step.
        binding.volumeSlider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            binding.outputVolumePercent.text = getString(
                R.string.percent_x, viewModel.deviceVolume.value.percentFor(value.toInt())
            )
            writeVolumeStep(value.toInt())
        }

        observe(viewModel.deviceVolume) { volume ->
            binding.volumeSlider.isEnabled = volume.available
            // The second route signal - see the note at the AudioDeviceCallback above. AHEAD OF
            // THE EARLY RETURN BELOW, DELIBERATELY: the route is not the slider's range, so a
            // drag in progress or a not-yet-available range must not stop the label updating.
            // This flow carries BOTH onDeviceVolumeChanged and onDeviceInfoChanged
            // (PlayerUiListener routes them to one place), and it is the DeviceInfo half that
            // means "the route moved".
            // They are indistinguishable here, so this also re-resolves on every volume key
            // press - one getAudioDevicesForAttributes binder call each. Cheap, and bounded by
            // the sheet being open; if it ever shows in a trace, gate it on min/max having
            // changed rather than dropping it, since that is the half that matters.
            applyOutput()
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
            // Past the drag guard, so this is the system's own value and not an echo of ours: adopt
            // it as the dedupe baseline. See lastWrittenVolume for the sequence that needs it.
            lastWrittenVolume = volume.volume
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

    /**
     * One write per integer step; see the note at the slider's listeners for why that bound matters.
     * [PlayerViewModel.setDeviceVolume] owns WHERE it goes - the phone's STREAM_MUSIC normally, the
     * receiver while a Cast session is live - and owns the Do Not Disturb failure message.
     */
    private fun writeVolumeStep(step: Int) {
        if (step == lastWrittenVolume) return
        lastWrittenVolume = step
        viewModel.setDeviceVolume(step)
    }

    private fun applyOutput() {
        val output = AudioOutput.current(requireContext())
        binding.outputIcon.setImageResource(output.icon)
        binding.outputLabel.text = output.name ?: getString(output.label)
    }

    /**
     * ⚠⚠ THE GUARD IS NOT DEFENSIVE - binding THROWS AFTER onDestroyView.
     * AutoClearedValue.getValue raises IllegalStateException once its onDestroy observer has
     * nulled the value, and unregisterAudioDeviceCallback does not drain callbacks ALREADY
     * POSTED to the main looper - so a Bluetooth event landing in the same frame as a dismiss
     * can reach this after the views are gone. `view == null` is the cheap test for that;
     * runCatching is the belt for anything that slips between the check and the read.
     */
    private fun applyOutputIfAlive() {
        if (view == null) return
        runCatching { applyOutput() }
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