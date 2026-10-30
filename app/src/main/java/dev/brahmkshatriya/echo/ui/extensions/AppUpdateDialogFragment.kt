package dev.brahmkshatriya.echo.ui.extensions

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.utils.AppUpdater
import dev.brahmkshatriya.echo.utils.Serializer.toData
import dev.brahmkshatriya.echo.utils.Serializer.toJson
import org.koin.androidx.viewmodel.ext.android.activityViewModel

/**
 * "Update available" for a Sideload app update (2026-10-30): the version, the release notes, Update /
 * Later. Shown by configureExtensionsUpdater from ExtensionsViewModel.appUpdatePrompt (`prompt = true`)
 * and by Settings -> App updates -> What's new (`prompt = false`: Update / Close, and Close answers
 * nothing).
 *   Update  ExtensionsViewModel.installAppNow - the same install path as Settings' Update row, which
 *           answers the tag only once the download starts.
 *   Later   ExtensionsViewModel.skipAppUpdate - answered at once.
 *   Back / outside tap  neither; an automatic offer returns on the next app open.
 * A DialogFragment so it survives rotation. AlertDialog keeps the message in its own scroll view, so long
 * notes scroll; focus starts on Update so a TV remote lands on a button.
 */
class AppUpdateDialogFragment : DialogFragment() {

    private val viewModel by activityViewModel<ExtensionsViewModel>()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val args = requireArguments()
        val found = args.getString(ARG_FOUND)!!.toData<AppUpdater.AppLookup.Available>().getOrThrow()
        val prompt = args.getBoolean(ARG_PROMPT)
        val notes = AppUpdater.releaseNotesText(found.notes)
            .ifBlank { getString(R.string.no_release_notes) }
        val version = getString(R.string.app_update_version_x, getString(R.string.app_name), found.version)
        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.app_update_available_title)
            .setMessage("$version\n\n$notes")
            .setPositiveButton(R.string.update) { _, _ ->
                viewModel.installAppNow(requireActivity(), found)
            }
            .setNegativeButton(if (prompt) R.string.later else R.string.close) { _, _ ->
                if (prompt) viewModel.skipAppUpdate(found)
            }
            .create().apply {
                setOnShowListener { getButton(AlertDialog.BUTTON_POSITIVE)?.requestFocus() }
            }
    }

    companion object {
        const val TAG = "app_update_dialog"
        private const val ARG_FOUND = "found"
        private const val ARG_PROMPT = "prompt"

        fun newInstance(found: AppUpdater.AppLookup.Available, prompt: Boolean) =
            AppUpdateDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_FOUND, found.toJson())
                    putBoolean(ARG_PROMPT, prompt)
                }
            }
    }
}
