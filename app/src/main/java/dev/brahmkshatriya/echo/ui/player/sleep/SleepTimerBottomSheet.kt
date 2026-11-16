package dev.brahmkshatriya.echo.ui.player.sleep

import android.os.SystemClock
import androidx.core.view.isVisible
import dev.brahmkshatriya.echo.utils.ContextUtils.observe
import kotlinx.coroutines.delay
import android.annotation.SuppressLint
import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.edit
import androidx.core.view.doOnLayout
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.cast.CastState
import dev.brahmkshatriya.echo.databinding.DialogPlayerSleepTimerBinding
import dev.brahmkshatriya.echo.ui.common.SnackBarHandler.Companion.createSnack
import dev.brahmkshatriya.echo.ui.player.PlayerViewModel
import dev.brahmkshatriya.echo.utils.ui.AutoClearedValue.Companion.autoCleared
import dev.brahmkshatriya.echo.utils.ui.RulerAdapter
import dev.brahmkshatriya.echo.utils.ui.UiUtils.isTv
import org.koin.androidx.viewmodel.ext.android.activityViewModel
import java.util.Calendar

class SleepTimerBottomSheet : BottomSheetDialogFragment() {
    var binding by autoCleared<DialogPlayerSleepTimerBinding>()
    private val viewModel by activityViewModel<PlayerViewModel>()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        binding = DialogPlayerSleepTimerBinding.inflate(inflater, container, false)
        return binding.root
    }

    private val adapter by lazy {
        RulerAdapter(object : RulerAdapter.Listener<Int> {
            override fun intervalText(value: Int) = value.toString()
            override fun onSelectItem(value: Int) {
                rulerTime = value
                binding.sleepTimerValue.text = requireContext().createString(value * 60L * 1000)
            }
        })
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.sleepTimerRecycler.adapter = adapter
        val last = viewModel.settings.getInt("sleep_timer", 5)
        adapter.submitList(timeRangeWithIntervals, last)
        // TV: open on the duration ruler (D-pad Left/Right steps it, see RulerAdapter), not on the toolbar's X,
        // the first focusable view. [CORRECTED 2026-10-31] The X no longer cancels (it only closes, below); the
        // ruler is still the sensible landing, with "Turn off timer" one Up above it while a timer runs. Laid
        // out, not posted, so it lands before the window picks its default focus. Phone: no focus is requested.
        if (requireContext().isTv()) binding.sleepTimerRecycler.doOnLayout { it.requestFocus() }

        binding.okay.setOnClickListener { saveAndDismiss(rulerTime) }
        binding.endOfTrack.setOnClickListener { setTimerAndDismiss(Long.MAX_VALUE) }
        // [2026-11-15] Not for a live item, which never ends, nor while casting, where the pause at the end of the item has no
        // effect on the receiver (PlayerCallback.startEndOfSong; both the user's decision).
        binding.endOfTrack.isVisible =
            viewModel.browser.value?.isCurrentMediaItemLive != true && !CastState.isCasting
        binding.min15.setOnClickListener { saveAndDismiss(15) }
        binding.min30.setOnClickListener { saveAndDismiss(30) }
        binding.min45.setOnClickListener { saveAndDismiss(45) }
        binding.hr1.setOnClickListener { saveAndDismiss(60) }
        binding.hr2.setOnClickListener { saveAndDismiss(120) }

        // ⚠️ THE X ONLY CLOSES (2026-10-31). It used to call setTimerAndDismiss(0), so closing the sheet the natural
        // way cancelled a running timer. Cancelling is now only "Turn off timer", shown while one runs.
        binding.topAppBar.setNavigationOnClickListener { dismiss() }
        binding.sleepTimerOff.setOnClickListener { setTimerAndDismiss(0) }
        // While a timer runs: show it, counting down. Driven by PlayerState.sleepTimerEndsAt, which the service
        // sets and clears; observe's collectLatest cancels the ticking loop whenever it changes.
        viewLifecycleOwner.observe(viewModel.playerState.sleepTimerEndsAt) { endsAt ->
            binding.sleepTimerRunning.isVisible = endsAt != null
            // [2026-11-15] An end-of-track timer shows no countdown (1181 report; see onDismiss).
            if (endsAt != null && viewModel.playerState.sleepAtEndOfSong) {
                binding.sleepTimerRemaining.text = getString(R.string.sleep_timer_stops_end_of_song)
                return@observe
            }
            while (endsAt != null) {
                val left = requireContext().timeLeft(endsAt) ?: break
                binding.sleepTimerRemaining.text = getString(R.string.sleep_timer_x_left, left)
                delay(1000)
            }
        }
        binding.topAppBar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.menu_schedule -> {
                    val is24Hour = DateFormat.is24HourFormat(context)
                    val picker = MaterialTimePicker.Builder()
                        .setTimeFormat(if (is24Hour) TimeFormat.CLOCK_24H else TimeFormat.CLOCK_12H)
                        .setTitleText(getString(R.string.sleep_on_specific_time))
                        .build()
                    picker.addOnCancelListener { picker.dismiss() }
                    picker.addOnPositiveButtonClickListener {
                        val now = Calendar.getInstance()
                        val nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
                        val pickerMin = picker.hour * 60 + picker.minute
                        val time = if (pickerMin > nowMin) pickerMin - nowMin
                        else 1440 - nowMin + pickerMin
                        setTimerAndDismiss(time * 60L * 1000)
                    }
                    picker.show(childFragmentManager, null)
                    true
                }

                else -> false
            }
        }
    }

    // ⚠️ null = NOTHING WAS CHOSEN (2026-10-31). onDismiss used to apply this unconditionally with a 0 default,
    // so closing the sheet without choosing - Back, or a swipe down on the phone - CANCELLED a running timer
    // and said so. Only the toolbar's X (setTimerAndDismiss(0)) cancels now. The always-apply came with the
    // original sleep timer upstream (6c5b68b0, 2024-12-29); no reason for it is recorded.
    private var timer: Long? = null
    private fun setTimerAndDismiss(ms: Long) {
        timer = ms
        dismiss()
    }

    private fun saveAndDismiss(mins: Int) {
        viewModel.settings.edit { putInt("sleep_timer", mins) }
        val ms = mins * 60L * 1000
        setTimerAndDismiss(ms)
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        val chosen = timer ?: return
        viewModel.setSleepTimer(chosen)
        val message = when (chosen) {
            0L -> getString(R.string.sleep_timer_cancelled)
            // [2026-11-15] NO NUMBER FOR END OF TRACK (1181 report: "Sleep timer set for X minutes" with X rounded, and
            // "Sleep timer set for  minutes", blank, under a minute). It used to format the song's remaining time with
            // createString, which floored to whole minutes and wrote nothing at all for 0. The stop is the end of the song,
            // so the message says that. The sheet's countdown and the player ⋮ entry say the same, through
            // PlayerState.sleepAtEndOfSong.
            Long.MAX_VALUE -> getString(R.string.sleep_timer_set_end_of_song)

            else -> getString(R.string.sleep_timer_set_for, requireContext().createString(chosen))
        }
        createSnack(message)
    }

    private var rulerTime = 5

    companion object {
        private const val MAX = 360
        private const val MIN = 5
        private const val STEPS = 5
        private const val INTERVAL = 15

        val timeRangeWithIntervals = 0.rangeTo((MAX - MIN) / STEPS).map {
            val value = MIN + it * STEPS
            value to (value == MIN || value % INTERVAL == 0)
        }

        /**
         * The time left before [endsAt] (SystemClock.elapsedRealtime ms) in the sheet's own format, rounded UP to
         * the minute so it never reads "0 min" while the timer still runs; null once it has passed. Also used for
         * the player ⋮ entry's label (MediaMoreBottomSheet.getPlayerButtons).
         */
        fun Context.timeLeft(endsAt: Long): String? {
            val left = endsAt - SystemClock.elapsedRealtime()
            if (left <= 0) return null
            return createString((left + 59_999) / 60_000 * 60_000)
        }

        // [2026-11-15] Rounds a part minute UP, as timeLeft does, so a positive time is never blank (1181 report). Whole
        // minutes, which is every ruler, preset and scheduled value, are unchanged.
        private fun Context.createString(ms: Long): String {
            val minutes = (ms + 59_999) / 60_000
            val hrs = minutes / 60
            val min = minutes % 60
            val str = StringBuilder()
            if (hrs > 0) str.append(
                runCatching { resources.getQuantityString(R.plurals.number_hour, hrs.toInt(), hrs) }
                    .getOrNull() ?: getString(R.string.n_hours, hrs)
            ).append(if (min > 0) " " else "")
            if (min > 0) str.append(
                runCatching { resources.getQuantityString(R.plurals.number_min, min.toInt(), min) }
                    .getOrNull() ?: getString(R.string.n_minutes, min)
            )
            return str.toString()
        }
    }
}