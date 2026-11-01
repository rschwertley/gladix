package dev.brahmkshatriya.echo.ui.player.audiofx

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.audiofx.AudioEffect
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.edit
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.viewModelScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.brahmkshatriya.echo.utils.ContextUtils.observe
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.common.models.Message
import dev.brahmkshatriya.echo.cast.CastState
import dev.brahmkshatriya.echo.databinding.DialogPlayerAudioFxBinding
import dev.brahmkshatriya.echo.databinding.FragmentAudioFxBinding
import dev.brahmkshatriya.echo.playback.CrossfadeController
import dev.brahmkshatriya.echo.playback.PlayerService.Companion.SKIP_FADE_ON_ALBUMS
import dev.brahmkshatriya.echo.playback.PlayerService.Companion.TRUE_CROSSFADE_SECONDS
import dev.brahmkshatriya.echo.playback.listener.EffectsListener.Companion.BASS_BOOST
import dev.brahmkshatriya.echo.playback.listener.EffectsListener.Companion.CHANGE_PITCH
import dev.brahmkshatriya.echo.playback.listener.EffectsListener.Companion.PLAYBACK_SPEED
import dev.brahmkshatriya.echo.playback.listener.EffectsListener.Companion.globalFx
import dev.brahmkshatriya.echo.playback.listener.EffectsListener.Companion.resetGlobalFx
import dev.brahmkshatriya.echo.playback.listener.EffectsListener.Companion.speedRange
import dev.brahmkshatriya.echo.utils.ContextUtils.getSettings
import dev.brahmkshatriya.echo.ui.player.PlayerViewModel
import dev.brahmkshatriya.echo.utils.PermsUtils.registerActivityResultLauncher
import dev.brahmkshatriya.echo.utils.ui.AutoClearedValue.Companion.autoCleared
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.activityViewModel

class AudioEffectsBottomSheet : BottomSheetDialogFragment() {

    var binding by autoCleared<DialogPlayerAudioFxBinding>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = DialogPlayerAudioFxBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // ⚠️ ONE APP-WIDE SET OF EFFECTS (2026-10-31). This sheet used to add the playing song to
        // CUSTOM_EFFECTS on every bind and edit that song's private copy, rebinding on every track change; it
        // now edits the same global file as Settings -> Audio effects, binds once, and its refresh resets the
        // global values as Settings' does. See EffectsListener.dropPerSongFx.
        // [CORRECTED 2026-11-01] Content, reset and casting state now come from setupAudioFx, shared with Settings.
        setupAudioFx(binding.audioFxFragment)
        binding.topAppBar.setNavigationOnClickListener { dismiss() }
    }

    companion object {
        /**
         * ⚠⚠ ONE AUDIO EFFECTS SCREEN (2026-11-01, design decision). The player's ⋮ -> Audio effects (this sheet) and
         * Settings -> Audio effects (AudioEffectsFragment.AudioFxFragment) both call THIS with the same
         * fragment_audio_fx content, so rows, order, wording, reset and the casting state cannot differ. Only the
         * container does: the sheet's close button and centred title, the page's collapsing toolbar and back arrow,
         * and the page's insets, fast scroller and TV focus handling.
         * Reset is the labelled last row, behind a confirmation, and restores EVERY control here - see resetAudioFx.
         * Casting is followed live (CastState.castingFlow): Settings can stay on screen behind the player while a
         * session starts, which the read-once state assumed could not happen.
         */
        fun Fragment.setupAudioFx(binding: FragmentAudioFxBinding) {
            val context = requireContext()
            fun bind(casting: Boolean) = binding.bind(
                context.globalFx(), context.getSettings(), casting
            ) { onEqualizerClicked() }
            bind(CastState.isCasting)
            viewLifecycleOwner.observe(CastState.castingFlow) { bind(it) }
            binding.resetAudioFx.setOnClickListener {
                MaterialAlertDialogBuilder(context)
                    .setMessage(R.string.reset_audio_fx_confirm)
                    .setPositiveButton(R.string.reset) { _, _ ->
                        context.resetAudioFx()
                        bind(CastState.isCasting)
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }

        /**
         * Every control on the Audio effects screen back to its default (2026-11-01): the global effects file
         * (speed, pitch, bass boost) and the two app settings the screen shares with Settings -> Player - Crossfade
         * to off and "skip fade on albums" to on, the same defaults SettingsPlayerFragment and PlayerService use.
         * Written explicitly rather than removed, so both screens read the default from a stored value. PlayerService's
         * listener on the same file picks up the Crossfade write (see the ONE PREFERENCE note in bind).
         */
        fun Context.resetAudioFx() {
            resetGlobalFx()
            getSettings().edit {
                putInt(TRUE_CROSSFADE_SECONDS, CrossfadeController.FADE_SECONDS_MIN)
                putBoolean(SKIP_FADE_ON_ALBUMS, true)
            }
        }

        @SuppressLint("SetTextI18n")
        fun FragmentAudioFxBinding.bind(
            settings: SharedPreferences,
            appSettings: SharedPreferences,
            casting: Boolean,
            onEqualizerClicked: () -> Unit,
        ) {
            // ⚠️ bind() RUNS AGAIN ON EVERY REBIND (2026-10-31) - the player sheet on each playerState.current
            // emission, with a DIFFERENT per-track `settings`, and AudioEffectsFragment on reset. Each run used to
            // ADD a change listener to every slider and never remove one. Worse than the pile-up: setting
            // slider.value dispatches to every attached listener (BaseSlider.setValuesInternal, Material 1.14.0),
            // so the previous track's listener wrote the NEW track's value into the OLD track's settings. So every
            // listener is cleared BEFORE the value is set, and the one added after it writes to this bind's
            // `settings`. Same fix as MaterialSliderPreference.onBindViewHolder. pitchSwitch has the same shape
            // with a single listener: it is unset before isChecked is written.
            // [CORRECTED 2026-10-31, same day] The player sheet no longer rebinds per track and there are no per-track
            // settings (one global file, see EffectsListener.dropPerSongFx); bind() now re-runs only on a reset, in
            // either screen. Clear-first is kept so a rebind can never stack listeners or replay a stale writer.
            // Playback speed (2026-10-31): a stepped Slider over INDICES into speedRange - the same int the ruler
            // stored under PLAYBACK_SPEED, so saved values and EffectsListener are unchanged. The steps are
            // equal on the track while the speeds are not (0.1 to 64, closer together near 1x), as on the ruler.
            // A saved index outside speedRange falls back to 1x, as the ruler (and EffectsListener.applyPlayback) did;
            // it must not reach the Slider, which throws on a value outside its range.
            // D-pad: Material's own Left/Right stepping, like the sliders below. Label shows the speed, not the index.
            speedSlider.clearOnChangeListeners()
            speedSlider.valueTo = speedRange.lastIndex.toFloat()
            val speed = settings.getInt(PLAYBACK_SPEED, speedRange.indexOf(1f))
                .takeIf { it in speedRange.indices } ?: speedRange.indexOf(1f)
            speedSlider.value = speed.toFloat()
            speedSlider.setLabelFormatter { "${speedRange.getOrNull(it.toInt()) ?: 1f}x" }
            speedValue.text = "${speedRange[speed]}x"
            speedSlider.addOnChangeListener { _, value, _ ->
                settings.edit { putInt(PLAYBACK_SPEED, value.toInt()) }
                speedValue.text = "${speedRange.getOrNull(value.toInt()) ?: 1f}x"
            }

            pitchSwitch.setOnCheckedChangeListener(null)
            pitchSwitch.isChecked = settings.getBoolean(CHANGE_PITCH, true)
            pitch.setOnClickListener {
                pitchSwitch.isChecked = !pitchSwitch.isChecked
            }
            pitchSwitch.setOnCheckedChangeListener { _, isChecked ->
                settings.edit { putBoolean(CHANGE_PITCH, isChecked) }
            }
            bassBoostSlider.clearOnChangeListeners()
            bassBoostSlider.value = settings.getInt(BASS_BOOST, 0).toFloat()
            bassBoostSlider.addOnChangeListener { _, value, _ ->
                settings.edit { putInt(BASS_BOOST, value.toInt()) }
            }
            // Hide the equalizer row entirely on devices with no system EQ app — the
            // DISPLAY_AUDIO_EFFECT_CONTROL_PANEL intent resolves to nothing (e.g. some Huawei/Honor
            // tablets like the PGT-N19) — so the user never taps a dead button. Reads the cached
            // resolveActivity result (hasSystemEqualizer); the manifest <queries> entry keeps it
            // reliable under API 30+ package visibility.
            val hasEqualizer = hasSystemEqualizer(equalizer.context)
            equalizer.isVisible = hasEqualizer
            if (hasEqualizer) equalizer.setOnClickListener { onEqualizerClicked() }

            loudnessNormalization.isVisible = false

            // ⚠⚠ ONE PREFERENCE, TWO SCREENS: appSettings IS THE SAME FILE
            // SettingsPlayerFragment WRITES. appSettings is Context.getSettings(), i.e.
            // getSharedPreferences(SETTINGS_NAME, MODE_PRIVATE), and the settings screen sets
            // preferenceManager.sharedPreferencesName = SETTINGS_NAME with the same mode, so both land
            // on the one SharedPreferencesImpl the framework caches per (file, mode) per process.
            // PlayerService registers its OnSharedPreferenceChangeListener on that same instance
            // (app.settings, the Koin single over getSettings()) and routes TRUE_CROSSFADE_SECONDS to
            // syncCrossfade, which re-reads the key. So a write HERE reaches
            // CrossfadeController.fadeSeconds with no wiring of its own, and the settings screen shows
            // this value next time it binds. Do NOT introduce a second key or a local mirror.
            // ⚠️ THE RANGE AND DEFAULT ARE READ FROM CrossfadeController, not retyped: the
            // layout's valueFrom/valueTo and this coerceIn have to agree with syncCrossfade's own
            // coerceIn, and FADE_SECONDS_MIN is also the off value and the default in both screens.
            // Writes on every change tick, exactly like the Track Fade slider above and like
            // MaterialSliderPreference on the settings screen (it persists in addOnChangeListener too,
            // ignoring byUser) - so dragging produces a run of syncCrossfade calls on both screens
            // alike, and crossing 0 is what creates or releases the sidecar.
            trueCrossfadeSummary.text = trueCrossfadeSummary.context.getString(
                R.string.true_crossfade_seconds_summary, CrossfadeController.FADE_SECONDS_MAX
            )
            val trueCrossfadeSeconds = appSettings
                .getInt(TRUE_CROSSFADE_SECONDS, CrossfadeController.FADE_SECONDS_MIN)
                .coerceIn(
                    CrossfadeController.FADE_SECONDS_MIN,
                    CrossfadeController.FADE_SECONDS_MAX
                )
            trueCrossfadeSlider.clearOnChangeListeners()
            trueCrossfadeSlider.value = trueCrossfadeSeconds.toFloat()
            trueCrossfadeValue.text = "${trueCrossfadeSeconds}s"
            trueCrossfadeSlider.addOnChangeListener { _, value, _ ->
                appSettings.edit { putInt(TRUE_CROSSFADE_SECONDS, value.toInt()) }
                trueCrossfadeValue.text = "${value.toInt()}s"
                // The album switch follows the overlap being on, which is what the deleted Track
                // Fade switch used to decide. Same key, same meaning, new owner.
                skipFadeOnAlbums.isVisible = value.toInt() > 0
            }
            skipFadeOnAlbums.isVisible = trueCrossfadeSeconds > 0
            // Cleared first like pitchSwitch (2026-11-01): bind now re-runs on every cast change and reset.
            skipFadeOnAlbumsSwitch.setOnCheckedChangeListener(null)
            skipFadeOnAlbumsSwitch.isChecked = appSettings.getBoolean(SKIP_FADE_ON_ALBUMS, true)
            skipFadeOnAlbums.setOnClickListener {
                skipFadeOnAlbumsSwitch.isChecked = !skipFadeOnAlbumsSwitch.isChecked
            }
            skipFadeOnAlbumsSwitch.setOnCheckedChangeListener { _, isChecked ->
                appSettings.edit { putBoolean(SKIP_FADE_ON_ALBUMS, isChecked) }
            }

            // ⚠⚠ EVERY EFFECT HERE ACTS ON THE PHONE'S OWN AudioTrack, SO NONE OF IT DOES
            // ANYTHING WHILE CASTING - AND SAYING SO IS THE POINT. Bass boost and the speed/pitch
            // pair run in AudioEffectsProcessor, the equalizer row opens the system panel for OUR
            // audio session, and crossfade is refused outright by CrossfadeController.armRefusal
            // while a session is live. Left live, a user could move a slider and hear no change,
            // which reads as a broken effect rather than an inapplicable one.
            // ⚠️ DISABLED AND DIMMED, NOT HIDDEN. Hiding changes the sheet's shape when
            // a session starts and leaves the user hunting for controls; a greyed row stays where
            // it was. No stored preference is touched - this is presentation only, and every value
            // returns when the session ends.
            // ⚠️ THE ROWS ARE DISABLED WITHOUT A REASON LABEL, which is the one thing
            // this does less well than the quality sheet's output row. There is no text view in
            // fragment_audio_fx.xml to carry one and inventing a layout element was not worth it
            // blind; R.string.cast_unavailable_while_casting exists for whoever adds one.
            // ⚠️ READ ONCE AT BIND, and that is sound HERE only because this sheet
            // cannot be open when a session starts - the route picker lives in the quality sheet,
            // not this one. If a second entry point ever appears, this needs the live treatment
            // the quality sheet's AudioDeviceCallback got.
            // [CORRECTED 2026-11-01] A second entry point existed: Settings -> Audio effects can stay on screen while a
            // session starts. Both now rebind on CastState.castingFlow (setupAudioFx), so this sets the state BOTH
            // ways. A disabled row gets no click from touch or D-pad (View.onTouchEvent / onKeyUp check enabled), so
            // the click listeners are left in place rather than nulled - re-enabling then needs nothing else.
            val enabled = !casting
            val alpha = if (enabled) 1f else DISABLED_ALPHA
            listOf(equalizer, pitch, trueCrossfade, skipFadeOnAlbums).forEach { row ->
                row.isEnabled = enabled
                row.alpha = alpha
            }
            listOf(speedSlider, bassBoostSlider, trueCrossfadeSlider).forEach { it.isEnabled = enabled }
            pitchSwitch.isEnabled = enabled
            skipFadeOnAlbumsSwitch.isEnabled = enabled
            // Speed joins them (2026-10-31): the ruler could not be disabled, the Slider can.
            listOf(speedSlider, bassBoostSlider).forEach { it.alpha = alpha }
        }

        // System-EQ presence is fixed for the process lifetime — only an app install/uninstall could
        // change it, which is rare and acceptable to ignore — so resolve the intent once and reuse the
        // cached Boolean for both the bind-time hide and the tap-time backstop. Companion-scoped, so it
        // survives sheet re-creation within a session and is only re-evaluated on a fresh process.
        /** The dimming used for an inactive row elsewhere in the app. */
        private const val DISABLED_ALPHA = 0.38f

        private var systemEqualizerAvailable: Boolean? = null
        private fun hasSystemEqualizer(context: Context) =
            systemEqualizerAvailable ?: (Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL)
                .resolveActivity(context.packageManager) != null).also { systemEqualizerAvailable = it }

        private fun openEqualizer(activity: ComponentActivity, sessionId: Int) {
            val intent = Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL).apply {
                putExtra(AudioEffect.EXTRA_PACKAGE_NAME, activity.packageName)
                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
                putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
            }
            val contract = ActivityResultContracts.StartActivityForResult()
            activity.registerActivityResultLauncher(contract) {}.launch(intent)
        }

        fun Fragment.onEqualizerClicked() {
            val viewModel by activityViewModel<PlayerViewModel>()
            val activity = requireActivity()
            val message = getString(R.string.no_system_equalizer)
            // Defense-in-depth backstop: the button is already hidden at bind time when no EQ resolves,
            // but resolveActivity can false-positive under package visibility, or the handler can vanish
            // between check and launch. Show a clear message, never the raw ActivityNotFoundException,
            // and never let it throw.
            fun showNoEqualizer() {
                viewModel.run { viewModelScope.launch { app.messageFlow.emit(Message(message)) } }
            }
            if (!hasSystemEqualizer(activity)) {
                showNoEqualizer()
                return
            }
            // ⚠⚠ NEVER HAND OUT SESSION 0, WHICH IS WHAT THIS FLOW HOLDS BEFORE
            // THE PLAYER HAS ONE. PlayerState.session starts at 0 and is written only when
            // EffectsListener sees onAudioSessionIdChanged - and the id is generated on the
            // playback thread and published back asynchronously (ExoPlayerImpl, 1.11.0,
            // :427-443), so there is a real window where it is still 0. Session 0 is
            // AUDIO_SESSION_ID_GENERATE / the global output mix: handing it to MusicFX would
            // point the system equalizer at everything the device plays, or at nothing, rather
            // than at us. Milliseconds wide in practice, and one comparison to close.
            val sessionId = viewModel.playerState.session.value
            if (sessionId == 0) {
                showNoEqualizer()
                return
            }
            runCatching { openEqualizer(activity, sessionId) }.getOrElse { showNoEqualizer() }
        }
    }
}
