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
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.common.models.Message
import dev.brahmkshatriya.echo.databinding.DialogPlayerAudioFxBinding
import dev.brahmkshatriya.echo.databinding.FragmentAudioFxBinding
import dev.brahmkshatriya.echo.playback.PlayerService.Companion.CROSSFADE_DURATION
import dev.brahmkshatriya.echo.playback.PlayerService.Companion.CROSSFADE_ENABLED
import dev.brahmkshatriya.echo.playback.PlayerService.Companion.SKIP_FADE_ON_ALBUMS
import dev.brahmkshatriya.echo.playback.listener.EffectsListener.Companion.BASS_BOOST
import dev.brahmkshatriya.echo.playback.listener.EffectsListener.Companion.CHANGE_PITCH
import dev.brahmkshatriya.echo.playback.listener.EffectsListener.Companion.CUSTOM_EFFECTS
import dev.brahmkshatriya.echo.playback.listener.EffectsListener.Companion.PLAYBACK_SPEED
import dev.brahmkshatriya.echo.playback.listener.EffectsListener.Companion.deleteFxPrefs
import dev.brahmkshatriya.echo.playback.listener.EffectsListener.Companion.getFxPrefs
import dev.brahmkshatriya.echo.playback.listener.EffectsListener.Companion.globalFx
import dev.brahmkshatriya.echo.playback.listener.EffectsListener.Companion.speedRange
import dev.brahmkshatriya.echo.utils.ContextUtils.getSettings
import dev.brahmkshatriya.echo.ui.player.PlayerViewModel
import dev.brahmkshatriya.echo.utils.ContextUtils.observe
import dev.brahmkshatriya.echo.utils.PermsUtils.registerActivityResultLauncher
import dev.brahmkshatriya.echo.utils.ui.AutoClearedValue.Companion.autoCleared
import dev.brahmkshatriya.echo.utils.ui.RulerAdapter
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
        val viewModel by activityViewModel<PlayerViewModel>()
        var mediaId: String? = null

        fun bind() {
            val settings = requireContext().globalFx()
            settings.edit {
                val customEffects = settings.getStringSet(CUSTOM_EFFECTS, null) ?: emptySet()
                putStringSet(CUSTOM_EFFECTS, customEffects + mediaId?.hashCode()?.toString())
            }
            binding.audioFxDescription.isVisible = mediaId != null
            val mediaSettings =
                requireContext().getFxPrefs(settings, mediaId?.hashCode()) ?: settings
            binding.audioFxFragment.bind(
                mediaSettings, requireContext().getSettings()
            ) { onEqualizerClicked() }
        }
        observe(viewModel.playerState.current) {
            mediaId = it?.mediaItem?.mediaId
            bind()
        }
        binding.topAppBar.setNavigationOnClickListener { dismiss() }
        binding.topAppBar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.menu_refresh -> {
                    val context = requireContext()
                    val id = mediaId ?: return@setOnMenuItemClickListener false
                    context.deleteFxPrefs(id.hashCode())
                    bind()
                    true
                }

                else -> false
            }
        }
    }

    companion object {
        @SuppressLint("SetTextI18n")
        fun FragmentAudioFxBinding.bind(
            settings: SharedPreferences,
            appSettings: SharedPreferences,
            onEqualizerClicked: () -> Unit,
        ) {
            val speed = settings.getInt(PLAYBACK_SPEED, speedRange.indexOf(1f))
            val adapter = RulerAdapter(object : RulerAdapter.Listener<Int> {
                override fun intervalText(value: Int) = "${speedRange.getOrNull(value) ?: 1f}x"
                override fun onSelectItem(value: Int) {
                    speedValue.text = "${speedRange.getOrNull(value) ?: 1f}x"
                    settings.edit { putInt(PLAYBACK_SPEED, value) }
                }
            })

            speedRecycler.adapter = adapter
            adapter.submitList(List(speedRange.size) { index -> index to (index % 2 == 0) }, speed)

            pitchSwitch.isChecked = settings.getBoolean(CHANGE_PITCH, true)
            pitch.setOnClickListener {
                pitchSwitch.isChecked = !pitchSwitch.isChecked
            }
            pitchSwitch.setOnCheckedChangeListener { _, isChecked ->
                settings.edit { putBoolean(CHANGE_PITCH, isChecked) }
            }
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

            crossfadeSwitch.isChecked = appSettings.getBoolean(CROSSFADE_ENABLED, false)
            crossfadeDurationSlider.isEnabled = crossfadeSwitch.isChecked
            val durationValue = appSettings.getInt(CROSSFADE_DURATION, 5).coerceIn(1, 12)
            crossfadeDurationSlider.value = durationValue.toFloat()
            crossfadeDurationValue.text = "${durationValue}s"
            skipFadeOnAlbums.isVisible = crossfadeSwitch.isChecked
            skipFadeOnAlbumsSwitch.isChecked = appSettings.getBoolean(SKIP_FADE_ON_ALBUMS, true)
            crossfade.setOnClickListener {
                crossfadeSwitch.isChecked = !crossfadeSwitch.isChecked
            }
            crossfadeSwitch.setOnCheckedChangeListener { _, isChecked ->
                appSettings.edit { putBoolean(CROSSFADE_ENABLED, isChecked) }
                crossfadeDurationSlider.isEnabled = isChecked
                skipFadeOnAlbums.isVisible = isChecked
            }
            crossfadeDurationSlider.addOnChangeListener { _, value, _ ->
                appSettings.edit { putInt(CROSSFADE_DURATION, value.toInt()) }
                crossfadeDurationValue.text = "${value.toInt()}s"
            }
            skipFadeOnAlbums.setOnClickListener {
                skipFadeOnAlbumsSwitch.isChecked = !skipFadeOnAlbumsSwitch.isChecked
            }
            skipFadeOnAlbumsSwitch.setOnCheckedChangeListener { _, isChecked ->
                appSettings.edit { putBoolean(SKIP_FADE_ON_ALBUMS, isChecked) }
            }
        }

        // System-EQ presence is fixed for the process lifetime — only an app install/uninstall could
        // change it, which is rare and acceptable to ignore — so resolve the intent once and reuse the
        // cached Boolean for both the bind-time hide and the tap-time backstop. Companion-scoped, so it
        // survives sheet re-creation within a session and is only re-evaluated on a fresh process.
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
            val sessionId = viewModel.playerState.session.value
            runCatching { openEqualizer(activity, sessionId) }.getOrElse { showNoEqualizer() }
        }
    }
}
