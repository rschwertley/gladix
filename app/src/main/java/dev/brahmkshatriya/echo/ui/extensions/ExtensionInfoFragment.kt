package dev.brahmkshatriya.echo.ui.extensions

import androidx.fragment.app.FragmentActivity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import androidx.preference.SwitchPreferenceCompat
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.common.Extension
import dev.brahmkshatriya.echo.common.models.ExtensionType
import dev.brahmkshatriya.echo.common.models.ImageHolder
import dev.brahmkshatriya.echo.common.models.ImportType
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.common.settings.SettingCategory
import dev.brahmkshatriya.echo.common.settings.SettingItem
import dev.brahmkshatriya.echo.common.settings.SettingList
import dev.brahmkshatriya.echo.common.settings.SettingMultipleChoice
import dev.brahmkshatriya.echo.common.settings.SettingOnClick
import dev.brahmkshatriya.echo.common.settings.SettingSlider
import dev.brahmkshatriya.echo.common.settings.SettingSwitch
import dev.brahmkshatriya.echo.common.settings.SettingTextInput
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.extensionPrefId
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.toSettings
import dev.brahmkshatriya.echo.playback.PlayerService.Companion.STREAM_QUALITY
import dev.brahmkshatriya.echo.playback.PlayerService.Companion.streamQualities
import dev.brahmkshatriya.echo.ui.common.SnackBarHandler.Companion.createSnack
import dev.brahmkshatriya.echo.ui.settings.BaseSettingsFragment
import dev.brahmkshatriya.echo.utils.ContextUtils.observe
import dev.brahmkshatriya.echo.utils.PermsUtils.registerActivityResultLauncher
import dev.brahmkshatriya.echo.utils.Serializer.getSerialized
import dev.brahmkshatriya.echo.utils.Serializer.putSerialized
import dev.brahmkshatriya.echo.utils.exportExtensionSettings
import dev.brahmkshatriya.echo.utils.importExtensionSettings
import dev.brahmkshatriya.echo.utils.ui.UiUtils.hasCreateDocument
import dev.brahmkshatriya.echo.utils.ui.UiUtils.hasOpenDocument
import dev.brahmkshatriya.echo.utils.ui.prefs.LoadingPreference
import dev.brahmkshatriya.echo.utils.ui.prefs.MaterialListPreference
import dev.brahmkshatriya.echo.utils.ui.prefs.MaterialMultipleChoicePreference
import dev.brahmkshatriya.echo.utils.ui.prefs.MaterialSliderPreference
import dev.brahmkshatriya.echo.utils.ui.prefs.MaterialTextInputPreference
import dev.brahmkshatriya.echo.utils.ui.prefs.TransitionPreference
import org.koin.androidx.viewmodel.ext.android.activityViewModel
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.koin.core.parameter.parametersOf

class ExtensionInfoFragment : BaseSettingsFragment() {
    private val args by lazy { requireArguments() }
    private val name by lazy { args.getString("name")!! }
    private val extensionId by lazy { args.getString("id")!! }
    private val extensionType by lazy { args.getString("type")!! }
    private val extIcon by lazy { args.getSerialized<ImageHolder?>("icon")?.getOrThrow() }

    private val viewModel by viewModel<ExtensionInfoViewModel> {
        parametersOf(ExtensionType.valueOf(extensionType), extensionId)
    }
    private val extensionsViewModel by activityViewModel<ExtensionsViewModel>()

    override val title get() = name
    override val icon get() = extIcon
    override val creator = { ExtensionPreference().apply { arguments = args } }

    companion object {
        fun getBundle(
            name: String, id: String, type: ExtensionType, icon: ImageHolder?,
        ) = Bundle().apply {
            putString("name", name)
            putString("id", id)
            putString("type", type.name)
            putSerialized("icon", icon)
        }

        fun getBundle(extension: Extension<*>) =
            getBundle(extension.name, extension.id, extension.type, extension.metadata.icon)

        // ⚠️ SAYS SO WHEN NOTHING CAN OPEN THE LINK (2026-10-31). It used to swallow the failure, so on a TV with no
        // browser - the usual case - Guide, GitHub, the credits, an extension's repo and its author link did
        // nothing at all when pressed. Every caller passes requireActivity(), hence FragmentActivity for
        // createSnack. Unchanged wherever a browser exists.
        fun FragmentActivity.openLink(url: String) {
            val intent = Intent(Intent.ACTION_VIEW).apply { data = url.toUri() }
            runCatching { startActivity(intent) }
                .onFailure { createSnack(getString(R.string.not_available_on_device)) }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        observe(viewModel.stateFlow) { state ->
            val extension = state?.extension ?: return@observe
            val metadata = extension.metadata
            binding.toolBar.setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    R.id.menu_uninstall -> {
                        parentFragmentManager.popBackStack()
                        extensionsViewModel.uninstall(requireActivity(), extension)
                        true
                    }

                    R.id.menu_repo -> {
                        requireActivity().openLink(metadata.repoUrl!!)
                        true
                    }

                    R.id.menu_update -> {
                        extensionsViewModel.update(extension)
                        true
                    }

                    else -> false
                }
            }
            if (metadata.importType != ImportType.BuiltIn) {
                binding.toolBar.menu.clear()
                binding.toolBar.inflateMenu(R.menu.extensions_menu)
                if (metadata.repoUrl == null) binding.toolBar.menu.removeItem(R.id.menu_repo)
            }
        }
    }

    class ExtensionPreference : PreferenceFragmentCompat() {
        private val extensionId by lazy { arguments?.getString("id")!! }
        private val extensionType by lazy { arguments?.getString("type")!! }
        private val viewModel by lazy {
            requireParentFragment().viewModel<ExtensionInfoViewModel> {
                parametersOf(ExtensionType.valueOf(extensionType), extensionId)
            }.value
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            configure()
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            val context = preferenceManager.context
            preferenceManager.sharedPreferencesName = extensionPrefId(extensionType, extensionId)
            preferenceManager.sharedPreferencesMode = Context.MODE_PRIVATE
            val prefs = preferenceManager.sharedPreferences ?: return
            val settings = toSettings(prefs)
            prefs.registerOnSharedPreferenceChangeListener { _, key ->
                viewModel.onSettingsChanged(settings, key)
            }
            preferenceManager.setOnPreferenceTreeClickListener {
                viewModel.onSettingsChanged(settings, it.key)
                true
            }
            observe(viewModel.stateFlow) { state ->
                val extension = state?.extension
                val screen = preferenceManager.createPreferenceScreen(context)
                preferenceScreen = screen
                if (extension == null) {
                    screen.addPreference(LoadingPreference(context))
                    return@observe
                }
                val infoPreference = ExtensionInfoPreference(
                    this@ExtensionPreference, extension, state.isLoginClient
                )
                screen.addPreference(infoPreference)
                if (extension.type == ExtensionType.MUSIC && extension.id != "deezer") MaterialListPreference(context).apply {
                    key = STREAM_QUALITY
                    title = getString(R.string.stream_quality)
                    summary = getString(R.string.x_specific_quality_summary, extension.name)
                    entries =
                        context.resources.getStringArray(R.array.stream_qualities) + getString(R.string.off)
                    entryValues = streamQualities + "off"
                    layoutResource = R.layout.preference
                    isIconSpaceReserved = false
                    setDefaultValue("off")
                    screen.addPreference(this)
                }
                state.settings.forEach { it.addPreferenceTo(screen) }

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
                        val launcher =
                            requireActivity().registerActivityResultLauncher(contract) { uri ->
                                uri?.let {
                                    context.exportExtensionSettings(extensionType, extensionId, it)
                                }
                            }
                    // ⚠⚠ ActivityNotFoundException SPECIFICALLY, NOT A BLANKET runCatching.
                    // A missing picker is the ONE failure this knows how to explain; swallowing
                    // everything here would hide a real bug behind a "no file manager" message.
                    // ⚠️ AND THE LAUNCHER MUST BE UNREGISTERED ON FAILURE. registerActivity
                    // ResultLauncher registers a FRESH launcher per call under a random UUID key and
                    // unregisters it in its own callback - which never runs when launch() throws, so
                    // without this the registration leaks until the Activity is destroyed, once per tap.
                    // ⚠️ KEPT EVEN THOUGH THE PREFERENCE IS NOW GATED: resolveActivity can
                    // false-positive under package visibility, and a handler can be uninstalled between
                    // the gate and the tap. Same defense-in-depth as onEqualizerClicked.
                        try {
                            launcher.launch(
                                "echo-$extensionType-$extensionId-settings.json".lowercase()
                            )
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
                        val launcher =
                            requireActivity().registerActivityResultLauncher(contract) {
                                it?.let {
                                    if (context.importExtensionSettings(extensionType, extensionId, it))
                                        requireActivity().recreate()
                                    else Toast.makeText(
                                        context,
                                        getString(R.string.invalid_settings_file),
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        // See the export preference above for why this catches ActivityNotFound
                        // specifically and why the launcher is unregistered on failure.
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

        /**
         * Setting keys already reported as malformed, so a rebuild does not repeat the line -
         * this whole screen is recreated on every stateFlow emission, several times per visit.
         * Per fragment rather than per process, so a fresh visit to the page reports again.
         */
        private val loggedMismatches = mutableSetOf<String>()

        private fun Setting.addPreferenceTo(preferenceGroup: PreferenceGroup) {
            val context = preferenceGroup.context
            when (this) {
                is SettingCategory -> {
                    PreferenceCategory(context).also {
                        it.title = this.title
                        it.key = this.key

                        it.isIconSpaceReserved = false
                        it.layoutResource = R.layout.preference_category
                        preferenceGroup.addPreference(it)

                        this.items.forEach { item ->
                            item.addPreferenceTo(it)
                        }
                    }
                }

                is SettingItem -> {
                    Preference(context).also {
                        it.title = this.title
                        it.key = this.key
                        it.summary = this.summary

                        it.isIconSpaceReserved = false
                        it.layoutResource = R.layout.preference
                        preferenceGroup.addPreference(it)
                    }
                }

                is SettingSwitch -> {
                    SwitchPreferenceCompat(context).also {
                        it.title = this.title
                        it.key = this.key
                        it.summary = this.summary
                        it.setDefaultValue(this.defaultValue)

                        it.isIconSpaceReserved = false
                        it.layoutResource = R.layout.preference_switch
                        preferenceGroup.addPreference(it)
                    }
                }

                is SettingList -> {
                    // ⚠⚠ AN ENTRY-LESS LIST USED TO TAKE DOWN THE WHOLE PAGE, AND
                    // THE DATA IS THE EXTENSION'S, NOT OURS. `entryValues[index]` threw
                    // IndexOutOfBoundsException: Index 0 out of bounds for length 0 on build 1136
                    // (fatal, Combine + youtube_music): defaultEntryIndex is a plain Int that
                    // extensions overwhelmingly set to 0, while the entry lists are whatever the
                    // extension produced - and they are empty whenever a list is built from
                    // something that was not available at the time. One malformed setting then
                    // cost every OTHER setting on the page, including logout.
                    // ⚠️ DISABLED RATHER THAN DROPPED, WHICH IS WHAT THIS PAGE
                    // ALREADY DOES WITH BAD DATA: SettingMultipleChoice below discards an
                    // out-of-range default index and still shows its row. So a malformed setting
                    // degrades here instead of vanishing - the user sees that the extension
                    // offers it with nothing to choose - and a disabled row cannot open the empty
                    // dialog, which is the other dead end (MaterialListPreference.onClick indexes
                    // entryValues, reached only from a tap).
                    // ⚠⚠ AND CLAMPED TO THE COMMON LENGTH, BECAUSE ListPreference
                    // RESOLVES THE INDEX AGAINST ONE ARRAY AND THEN READS THE OTHER. Read from
                    // the androidx.preference 1.2.1 sources: getEntry() is
                    // `index >= 0 && mEntries != null ? mEntries[index] : null`
                    // (ListPreference.java:216-219) where the index came from
                    // findIndexOfValue(mValue) scanning mEntryValues (:227-235) - nothing checks
                    // it against mEntries. So entryValues LONGER than entryTitles, plus a stored
                    // value from the tail, throws inside the library at bind time, reached by
                    // MaterialListPreference.updateSummary reading `entry` on every
                    // onSetInitialValue. The mirror case is ours: onClick does
                    // `entryValues[index]` with an index bounded by `entries`. One clamp closes
                    // both, and size == 0 subsumes the empty-list case above.
                    val size = minOf(this.entryTitles.size, this.entryValues.size)
                    if (this.entryTitles.size != this.entryValues.size &&
                        loggedMismatches.add(this.key)
                    ) Log.w(
                        "ExtensionInfo", extensionId + " setting " + this.key + ": " +
                            this.entryTitles.size + " titles vs " + this.entryValues.size +
                            " values, using " + size
                    )
                    val titles = this.entryTitles.take(size)
                    val values = this.entryValues.take(size)
                    MaterialListPreference(context).also {
                        it.title = this.title
                        it.key = this.key
                        it.summary = this.summary
                        it.isEnabled = size > 0
                        // getOrNull, not [index]: a NON-empty list can still carry a default
                        // index past its end, which is the same crash one element further out.
                        // Against the CLAMPED list, so a default inside a discarded tail is
                        // dropped rather than set to a value no entry can display.
                        defaultEntryIndex?.let { index ->
                            values.getOrNull(index)?.let { default ->
                                it.setDefaultValue(default)
                            }
                        }
                        it.entries = titles.toTypedArray()
                        it.entryValues = values.toTypedArray()

                        it.isIconSpaceReserved = false
                        it.layoutResource = R.layout.preference
                        preferenceGroup.addPreference(it)
                    }
                }

                is SettingMultipleChoice -> {
                    // Same treatment as SettingList above, for the same reason. This branch never
                    // crashed - its default indices already go through getOrNull - but an entry-less
                    // multiple choice is still a row that can only open an empty dialog.
                    val hasEntries = this.entryTitles.isNotEmpty() && this.entryValues.isNotEmpty()
                    MaterialMultipleChoicePreference(context).also {
                        it.title = this.title
                        it.key = this.key
                        it.summary = this.summary
                        it.isEnabled = hasEntries
                        defaultEntryIndices?.let { indices ->
                            it.setDefaultValue(indices.mapNotNull { index ->
                                entryValues.getOrNull(index)
                            }.toSet())
                        }
                        it.entries = this.entryTitles.toTypedArray()
                        it.entryValues = this.entryValues.toTypedArray()

                        it.isIconSpaceReserved = false
                        it.layoutResource = R.layout.preference
                        preferenceGroup.addPreference(it)
                    }
                }

                is SettingTextInput -> {
                    MaterialTextInputPreference(context).also {
                        it.title = this.title
                        it.key = this.key
                        it.summary = this.summary
                        it.text = this.defaultValue

                        it.isIconSpaceReserved = false
                        it.layoutResource = R.layout.preference
                        preferenceGroup.addPreference(it)
                    }
                }

                is SettingSlider -> {
                    MaterialSliderPreference(context, from, to, steps, allowOverride).also {
                        it.title = this.title
                        it.key = this.key
                        it.summary = this.summary
                        it.setDefaultValue(this.defaultValue)

                        it.isIconSpaceReserved = false
                        preferenceGroup.addPreference(it)
                    }
                }

                is SettingOnClick -> {
                    TransitionPreference(context).also {
                        it.title = this.title
                        it.key = this.key
                        it.summary = this.summary
                        it.setOnPreferenceClickListener {
                            viewModel.onSettingsClick(onClick)
                            true
                        }
                        it.isIconSpaceReserved = false
                        it.layoutResource = R.layout.preference
                        preferenceGroup.addPreference(it)
                    }
                }
            }
        }
    }
}