package dev.brahmkshatriya.echo.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.preference.PreferenceFragmentCompat
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.common.models.ImageHolder.Companion.toResourceImageHolder
import dev.brahmkshatriya.echo.ui.common.SnackBarHandler.Companion.createSnack
import dev.brahmkshatriya.echo.ui.extensions.ExtensionsViewModel
import dev.brahmkshatriya.echo.utils.ContextUtils.SETTINGS_NAME
import dev.brahmkshatriya.echo.utils.PermsUtils.registerActivityResultLauncher
import dev.brahmkshatriya.echo.utils.exportSettings
import dev.brahmkshatriya.echo.utils.importSettings
import dev.brahmkshatriya.echo.utils.ui.UiUtils.hasCreateDocument
import dev.brahmkshatriya.echo.utils.ui.UiUtils.hasOpenDocument
import dev.brahmkshatriya.echo.utils.ui.prefs.SwitchLongClickPreference
import dev.brahmkshatriya.echo.utils.ui.prefs.TransitionPreference
import org.koin.androidx.viewmodel.ext.android.activityViewModel

class SettingsOtherFragment : BaseSettingsFragment() {
    override val title get() = getString(R.string.other_settings)
    override val icon get() = R.drawable.ic_more_horiz.toResourceImageHolder()
    override val creator = { OtherPreference() }

    class OtherPreference : PreferenceFragmentCompat() {

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            configure()
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            val context = preferenceManager.context
            preferenceManager.sharedPreferencesName = SETTINGS_NAME
            preferenceManager.sharedPreferencesMode = Context.MODE_PRIVATE
            val screen = preferenceManager.createPreferenceScreen(context)
            preferenceScreen = screen

            SwitchLongClickPreference(context).apply {
                title = getString(R.string.check_for_updates)
                summary = getString(R.string.check_for_updates_summary)
                key = "check_for_updates"
                layoutResource = R.layout.preference_switch
                isIconSpaceReserved = false
                setDefaultValue(true)
                screen.addPreference(this)
                setOnLongClickListener {
                    val viewModel by activityViewModel<ExtensionsViewModel>()
                    viewModel.update(requireActivity(), true)
                }
            }

            TransitionPreference(context).apply {
                key = "export"
                title = getString(R.string.export_settings)
                summary = getString(R.string.export_settings_summary)
                layoutResource = R.layout.preference
                isIconSpaceReserved = false
                screen.addPreference(this)
                isVisible = context.hasCreateDocument()
                setOnPreferenceClickListener {
                    val contract = ActivityResultContracts.CreateDocument("application/json")
                    val launcher = requireActivity().registerActivityResultLauncher(contract) { uri ->
                        uri?.let { context.exportSettings(it) }
                    }
                    // See ExtensionInfoFragment's export preference for the full reasoning: catch
                    // ActivityNotFound ONLY, and unregister the per-tap launcher when launch throws.
                    try {
                        launcher.launch("echo-settings.json")
                    } catch (_: ActivityNotFoundException) {
                        launcher.unregister()
                        createSnack(R.string.no_file_picker)
                    }
                    true
                }
            }

            TransitionPreference(context).apply {
                key = "import"
                title = getString(R.string.import_settings)
                summary = getString(R.string.import_settings_summary)
                layoutResource = R.layout.preference
                isIconSpaceReserved = false
                screen.addPreference(this)
                isVisible = context.hasOpenDocument()
                setOnPreferenceClickListener {
                    val contract = ActivityResultContracts.OpenDocument()
                    val launcher = requireActivity().registerActivityResultLauncher(contract) {
                        it?.let {
                            if (context.importSettings(it)) requireActivity().recreate()
                            else Toast.makeText(
                                context,
                                getString(R.string.invalid_settings_file),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                    // As above: ActivityNotFound only, unregister on failure.
                    try {
                        launcher.launch(arrayOf("application/json"))
                    } catch (_: ActivityNotFoundException) {
                        launcher.unregister()
                        createSnack(R.string.no_file_picker)
                    }
                    true
                }
            }
        }
    }
}