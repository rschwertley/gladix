package dev.brahmkshatriya.echo.ui.settings

import dev.brahmkshatriya.echo.ui.common.FragmentUtils.focusFirstRowOnTv
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.databinding.FragmentAudioFxBinding
import dev.brahmkshatriya.echo.databinding.FragmentGenericCollapsableBinding
import dev.brahmkshatriya.echo.playback.listener.EffectsListener.Companion.deleteGlobalFx
import dev.brahmkshatriya.echo.playback.listener.EffectsListener.Companion.globalFx
import dev.brahmkshatriya.echo.utils.ContextUtils.getSettings
import dev.brahmkshatriya.echo.ui.common.UiViewModel.Companion.applyContentInsets
import dev.brahmkshatriya.echo.ui.common.UiViewModel.Companion.applyInsets
import dev.brahmkshatriya.echo.ui.extensions.login.LoginFragment.Companion.bind
import dev.brahmkshatriya.echo.ui.player.audiofx.AudioEffectsBottomSheet.Companion.bind
import dev.brahmkshatriya.echo.ui.player.audiofx.AudioEffectsBottomSheet.Companion.onEqualizerClicked
import dev.brahmkshatriya.echo.utils.ui.AutoClearedValue.Companion.autoCleared
import dev.brahmkshatriya.echo.utils.ui.FastScrollerHelper
import dev.brahmkshatriya.echo.utils.ui.FastScrollerHelper.applyInsets

class AudioEffectsFragment : Fragment() {

    private var binding: FragmentGenericCollapsableBinding by autoCleared()
    private val fragment = AudioFxFragment()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentGenericCollapsableBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // applyInsets = false (2026-10-31): AudioFxFragment pads its own root from the same combined insets (with
        // the fast-scroller handle), so padding this container too applied the rail, nav and mini-player insets
        // TWICE. Same split as BaseSettingsFragment, whose container is unpadded and whose list pads itself.
        binding.bind(this, applyInsets = false)
        binding.extensionIcon.isVisible = false
        binding.toolBar.title = getString(R.string.audio_fx)
        childFragmentManager.beginTransaction().replace(R.id.genericFragmentContainer, fragment)
            .commit()
        // TV: focus the first row on open, restore the row left on return - see FragmentUtils.focusFirstRowOnTv.
        focusFirstRowOnTv(binding.genericFragmentContainer)

        binding.toolBar.inflateMenu(R.menu.refresh_menu)
        binding.toolBar.setOnMenuItemClickListener {
            val context = requireContext()
            context.deleteGlobalFx()
            fragment.bind()
            true
        }
    }

    class AudioFxFragment : Fragment() {
        var binding by autoCleared<FragmentAudioFxBinding>()

        override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
        ): View {
            binding = FragmentAudioFxBinding.inflate(inflater, container, false)
            return binding.root
        }

        fun bind() = binding.bind(
            requireContext().globalFx(), requireContext().getSettings()
        ) { onEqualizerClicked() }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            bind()
            binding.root.apply {
                clipToPadding = false
                // Handle kept and re-padded in the same inset block that pads the list, rather than left
                // on applyTo's flat 8dp.
                val scroller = FastScrollerHelper.applyTo(this)
                applyInsets {
                    applyContentInsets(it)
                    scroller.applyInsets(context, it)
                }
                isVerticalScrollBarEnabled = false
            }
        }
    }

    companion object {
        const val AUDIO_FX = "audio_fx"
    }
}