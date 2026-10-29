package dev.brahmkshatriya.echo.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import dev.brahmkshatriya.echo.BuildConfig
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.common.models.ImageHolder.Companion.toResourceImageHolder
import dev.brahmkshatriya.echo.di.App
import dev.brahmkshatriya.echo.ui.common.SnackBarHandler.Companion.createSnack
import dev.brahmkshatriya.echo.ui.extensions.ExtensionsViewModel
import dev.brahmkshatriya.echo.utils.AppUpdater
import dev.brahmkshatriya.echo.utils.AppUpdater.InstallChannel
import dev.brahmkshatriya.echo.utils.AppUpdater.installChannel
import dev.brahmkshatriya.echo.utils.ContextUtils.SETTINGS_NAME
import dev.brahmkshatriya.echo.utils.ContextUtils.copyToClipboard
import dev.brahmkshatriya.echo.utils.ContextUtils.observe
import dev.brahmkshatriya.echo.utils.PermsUtils.registerActivityResultLauncher
import dev.brahmkshatriya.echo.utils.exportSettings
import dev.brahmkshatriya.echo.utils.importSettings
import dev.brahmkshatriya.echo.utils.ui.UiUtils.hasCreateDocument
import dev.brahmkshatriya.echo.utils.ui.UiUtils.hasOpenDocument
import dev.brahmkshatriya.echo.utils.ui.prefs.SwitchLongClickPreference
import dev.brahmkshatriya.echo.utils.ui.prefs.TransitionPreference
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.activityViewModel

class SettingsOtherFragment : BaseSettingsFragment() {
    override val title get() = getString(R.string.other_settings)
    override val icon get() = R.drawable.ic_more_horiz.toResourceImageHolder()
    override val creator = { OtherPreference() }

    class OtherPreference : PreferenceFragmentCompat() {

        private val app by inject<App>()
        private val extVM by activityViewModel<ExtensionsViewModel>()
        private val channel by lazy { app.installChannel() }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            configure()
            // Re-rendered on every state change AND on every return to this screen (flowWithLifecycle
            // restarts on STARTED), so "Last checked" also picks up an automatic check made meanwhile.
            viewLifecycleOwner.observe(extVM.appUpdateUi) { renderAppUpdates(it) }
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            val context = preferenceManager.context
            preferenceManager.sharedPreferencesName = SETTINGS_NAME
            preferenceManager.sharedPreferencesMode = Context.MODE_PRIVATE
            val screen = preferenceManager.createPreferenceScreen(context)
            preferenceScreen = screen

            // ⚠⚠ APP UPDATES (2026-10-28). ~90% of users install from GitHub and had no visible way to
            // check by hand - only two long-presses (this screen's switch, the settings sheet's version
            // line), both running the full app + extensions pass. One section per install channel
            // (AppUpdater.installChannel).
            // ⚠️ TV: EVERY ACTION IS A WHOLE Preference ROW, NEVER A BUTTON INSIDE ONE. The framework
            // makes each row a single focusable, clickable item the D-pad centre activates; a nested
            // button needs hand-wired nextFocus and is where TV focus usually gets lost. The Version row
            // is selectable for the same reason (an unbroken focus chain from the toolbar down), and
            // the Update row is inserted BELOW Check now, so focus stays on the pressed row.
            PreferenceCategory(context).apply {
                title = getString(R.string.app_updates)
                key = "app_updates"
                isIconSpaceReserved = false
                layoutResource = R.layout.preference_category
                screen.addPreference(this)

                Preference(context).apply {
                    key = KEY_APP_VERSION
                    title = getString(R.string.version)
                    layoutResource = R.layout.preference
                    isIconSpaceReserved = false
                    setOnPreferenceClickListener {
                        context.copyToClipboard(getString(R.string.version), BuildConfig.VERSION_NAME)
                        true
                    }
                    addPreference(this)
                }

                when (channel) {
                    InstallChannel.Sideload -> {
                        Preference(context).apply {
                            key = KEY_APP_CHECK_NOW
                            title = getString(R.string.app_update_check_now)
                            layoutResource = R.layout.preference
                            isIconSpaceReserved = false
                            setOnPreferenceClickListener { extVM.checkAppNow(); true }
                            addPreference(this)
                        }
                        Preference(context).apply {
                            key = KEY_APP_UPDATE
                            summary = getString(R.string.app_update_available_summary)
                            layoutResource = R.layout.preference
                            isIconSpaceReserved = false
                            isVisible = false
                            setOnPreferenceClickListener { extVM.installAppNow(requireActivity()); true }
                            addPreference(this)
                        }
                    }

                    InstallChannel.Play -> Preference(context).apply {
                        key = "app_open_play"
                        title = getString(R.string.open_in_play_store)
                        summary = getString(R.string.open_in_play_store_summary)
                        layoutResource = R.layout.preference
                        isIconSpaceReserved = false
                        setOnPreferenceClickListener { openPlayListing(); true }
                        addPreference(this)
                    }

                    InstallChannel.OtherStore -> Preference(context).apply {
                        key = "app_other_store"
                        summary = getString(R.string.app_update_other_store)
                        layoutResource = R.layout.preference
                        isIconSpaceReserved = false
                        isSelectable = false
                        addPreference(this)
                    }

                    // Fail-closed like AppUpdater.isStoreInstall: no in-app install, only the releases
                    // page, so the user - not the app - decides what goes over this copy.
                    InstallChannel.Unknown -> Preference(context).apply {
                        key = "app_open_releases"
                        title = getString(R.string.open_github_releases)
                        summary = getString(R.string.app_update_unknown_source, getString(R.string.app_name))
                        layoutResource = R.layout.preference
                        isIconSpaceReserved = false
                        setOnPreferenceClickListener {
                            // Not openLink, for the reason at openPlayListing: it fails silently
                            // without a browser, which is the usual TV case.
                            val repo = getString(R.string.app_github_repo)
                            val releases = Intent(Intent.ACTION_VIEW, "https://github.com/$repo/releases".toUri())
                            try {
                                startActivity(releases)
                            } catch (_: ActivityNotFoundException) {
                                createSnack(R.string.could_not_open_github)
                            }
                            true
                        }
                        addPreference(this)
                    }
                }
            }

            // ⚠️ THIS SWITCH GOVERNS THE WHOLE AUTOMATIC PASS, NOT ONE HALF OF IT. Its only reader is
            // ExtensionsViewModel.shouldCheckForExtensionUpdates, which gates update(force = false):
            // the app check, then every extension. Outside Sideload the app half returns at
            // AppUpdater.updateApp's isStoreInstall gate, so there it governs extensions only - hence
            // the label, and why it is NOT hidden on Play: hiding it would remove the only way to
            // stop daily extension updates there.
            // Its own category since 2026-10-28, directly after App updates, so on a store install an
            // extensions-only switch does not sit under the "App updates" header. The header row
            // takes no D-pad focus (PreferenceCategory.isEnabled is false, and Preference
            // .onBindViewHolder disables the row's views from it; androidx.preference 1.2.1), so the
            // focus order on this screen is unchanged.
            PreferenceCategory(context).apply {
                title = getString(R.string.extensions)
                key = "extension_updates"
                isIconSpaceReserved = false
                layoutResource = R.layout.preference_category
                screen.addPreference(this)

                SwitchLongClickPreference(context).apply {
                    val checksApp = channel == InstallChannel.Sideload
                    title = getString(
                        if (checksApp) R.string.check_for_updates else R.string.check_for_extension_updates
                    )
                    summary = getString(
                        if (checksApp) R.string.check_for_updates_summary
                        else R.string.check_for_extension_updates_summary
                    )
                    key = "check_for_updates"
                    layoutResource = R.layout.preference_switch
                    isIconSpaceReserved = false
                    setDefaultValue(true)
                    addPreference(this)
                    setOnLongClickListener {
                        val viewModel by activityViewModel<ExtensionsViewModel>()
                        viewModel.update(requireActivity(), true)
                    }
                }
            }

            // Own category (2026-10-28) so Export/Import do not render under the Extensions header above.
            // Hidden when NEITHER row can show: PreferenceGroupAdapter.createVisiblePreferencesList adds
            // a visible group's header whatever its children's visibility (androidx.preference 1.2.1),
            // so a category of two hidden rows would be an empty header.
            val settingsFile = PreferenceCategory(context).apply {
                title = getString(R.string.settings)
                key = "settings_file"
                isIconSpaceReserved = false
                layoutResource = R.layout.preference_category
                screen.addPreference(this)
                isVisible = context.hasCreateDocument() || context.hasOpenDocument()
            }

            TransitionPreference(context).apply {
                key = "export"
                title = getString(R.string.export_settings)
                summary = getString(R.string.export_settings_summary)
                layoutResource = R.layout.preference
                isIconSpaceReserved = false
                settingsFile.addPreference(this)
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
                settingsFile.addPreference(this)
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

        private fun renderAppUpdates(ui: ExtensionsViewModel.AppUpdateUi) {
            if (!isAdded) return
            findPreference<Preference>(KEY_APP_VERSION)?.summary = run {
                // The version NAME only: it already carries the build number (v3.1.NNNNN).
                val name = BuildConfig.VERSION_NAME.substringBefore('_')
                val last = app.settings.getLong(AppUpdater.KEY_LAST_APP_UPDATE_CHECK, 0L)
                val checked = if (last <= 0L) getString(R.string.app_update_never_checked)
                else getString(
                    R.string.app_update_last_checked_x,
                    DateUtils.getRelativeTimeSpanString(last, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
                )
                // "Last checked" only where it can move: KEY_LAST_APP_UPDATE_CHECK is written by
                // AppUpdater.lookUpAppUpdate, which runs only past updateApp's isStoreInstall gate - so
                // on every other channel it would read "Not checked yet" for ever.
                val tap = getString(R.string.tap_to_copy)
                if (channel == InstallChannel.Sideload) "$name\n$checked\n$tap" else "$name\n$tap"
            }
            val appName = getString(R.string.app_name)
            findPreference<Preference>(KEY_APP_CHECK_NOW)?.summary = when (ui) {
                ExtensionsViewModel.AppUpdateUi.Idle ->
                    getString(R.string.app_update_check_now_summary, appName)
                ExtensionsViewModel.AppUpdateUi.Checking -> getString(R.string.app_update_checking)
                ExtensionsViewModel.AppUpdateUi.Busy -> getString(R.string.app_update_busy)
                ExtensionsViewModel.AppUpdateUi.UpToDate -> getString(R.string.app_update_up_to_date, appName)
                is ExtensionsViewModel.AppUpdateUi.Available,
                is ExtensionsViewModel.AppUpdateUi.Downloading ->
                    getString(R.string.app_update_check_now_summary, appName)
                is ExtensionsViewModel.AppUpdateUi.Failed -> ui.text
            }
            findPreference<Preference>(KEY_APP_UPDATE)?.run {
                val found = when (ui) {
                    is ExtensionsViewModel.AppUpdateUi.Available -> ui.found
                    is ExtensionsViewModel.AppUpdateUi.Downloading -> ui.found
                    else -> null
                }
                // Stays ENABLED while downloading, and taps are ignored by installAppNow instead:
                // disabling a focused row on TV makes focus jump.
                isVisible = found != null
                if (found != null) {
                    title = getString(R.string.app_update_available_x, found.version)
                    summary = getString(
                        if (ui is ExtensionsViewModel.AppUpdateUi.Downloading) R.string.app_update_downloading
                        else R.string.app_update_available_summary
                    )
                }
            }
        }

        // Gladix's own listing: packageName at runtime IS the listed package for a Play install
        // (build.gradle.kts applicationId), so nothing is hard-coded. Not ExtensionInfoFragment.openLink,
        // which swallows a failure silently - a TV without a browser would then do nothing at all.
        private fun openPlayListing() {
            val id = requireContext().packageName
            val market = Intent(Intent.ACTION_VIEW, "market://details?id=$id".toUri())
                .setPackage("com.android.vending")
            val web = Intent(Intent.ACTION_VIEW, "https://play.google.com/store/apps/details?id=$id".toUri())
            try {
                startActivity(market)
            } catch (_: ActivityNotFoundException) {
                try {
                    startActivity(web)
                } catch (_: ActivityNotFoundException) {
                    createSnack(R.string.could_not_open_play_store)
                }
            }
        }

        private companion object {
            const val KEY_APP_VERSION = "app_version"
            const val KEY_APP_CHECK_NOW = "app_check_now"
            const val KEY_APP_UPDATE = "app_update_install"
        }
    }
}