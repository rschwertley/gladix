package dev.brahmkshatriya.echo.ui.extensions.login

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import android.widget.PopupMenu
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.databinding.DialogLoginUserListBinding
import dev.brahmkshatriya.echo.databinding.ItemAccountRowBinding
import dev.brahmkshatriya.echo.extensions.db.models.CurrentUser
import dev.brahmkshatriya.echo.extensions.db.models.UserEntity.Companion.toEntity
import dev.brahmkshatriya.echo.ui.common.FragmentUtils.openFragment
import dev.brahmkshatriya.echo.ui.extensions.login.AccountLabel.accountLabel
import dev.brahmkshatriya.echo.ui.extensions.login.AccountLabel.accountLabelInline
import dev.brahmkshatriya.echo.ui.settings.SettingsBottomSheet
import dev.brahmkshatriya.echo.utils.ContextUtils.observe
import dev.brahmkshatriya.echo.utils.ui.UiUtils.isTv
import dev.brahmkshatriya.echo.utils.image.ImageUtils.loadAsCircle
import dev.brahmkshatriya.echo.utils.ui.AutoClearedValue.Companion.autoCleared
import org.koin.androidx.viewmodel.ext.android.activityViewModel

class LoginUserListBottomSheet : BottomSheetDialogFragment() {

    var binding by autoCleared<DialogLoginUserListBinding>()
    val viewModel by activityViewModel<LoginUserListViewModel>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = DialogLoginUserListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.title.setNavigationOnClickListener { dismiss() }
        // [2026-11-13] PHONE ONLY, TV UNCHANGED (the user's decision, audit items 1-5): the hint, the labelled "Use without an
        // account" row in place of the toolbar icon, the Settings entry, subtitles for every extension and the confirmation
        // on Remove account. A TV keeps the toolbar icon and the sheet as it was.
        val phone = !requireContext().isTv()
        if (phone) binding.title.menu.clear()
        binding.openSettings.isVisible = phone
        binding.openSettings.setOnClickListener {
            dismiss()
            SettingsBottomSheet().show(parentFragmentManager, null)
        }

        observe(viewModel.allUsers) { (ext, list) ->
            binding.accountListLoading.root.isVisible = ext == null
            binding.accountList.isVisible = ext != null
            binding.title.title = getString(R.string.select_x_account, ext?.name ?: "")
            binding.accountList.removeAllViews()

            ext ?: return@observe

            binding.title.setOnMenuItemClickListener {
                dismiss()
                viewModel.setLoginUser(CurrentUser(ext.type, ext.id, null))
                true
            }

            binding.addAccount.setOnClickListener {
                dismiss()
                requireActivity().openFragment<LoginFragment>(
                    null,
                    LoginFragment.getBundle(ext.id, ext.name, ext.type)
                )
            }

            val selectedUser = viewModel.currentUser.value

            // [2026-11-13] "Remove account": removes the current account and selects the next saved one, or none
            // (LoginUserListViewModel.remove). The sheet stays open, so the switch shows in the list.
            binding.logout.isEnabled = selectedUser != null
            binding.logout.setOnClickListener {
                if (!phone) return@setOnClickListener viewModel.removeCurrent(ext.type, ext.id)
                val current = selectedUser ?: return@setOnClickListener
                confirmRemove(current.accountLabelInline()) { viewModel.removeCurrent(ext.type, ext.id) }
            }
            binding.switchHint.isVisible = phone && list.size > 1
            binding.withoutAccount.isVisible = phone
            binding.withoutAccount.isChecked = selectedUser == null
            binding.withoutAccount.setOnClickListener {
                binding.withoutAccount.isChecked = selectedUser == null
                if (selectedUser == null) return@setOnClickListener
                viewModel.setLoginUser(CurrentUser(ext.type, ext.id, null))
                dismiss()
            }

            // [2026-11-13] One row per saved account: tap selects it, as the toggle group did (a tap on the current one does
            // nothing, as selectionRequired did); the ⋮ removes it after a confirmation.
            list.forEach { (user, selected) ->
                val row = ItemAccountRowBinding.inflate(layoutInflater, binding.accountList, false)
                val button = row.account
                val label = user.accountLabel(ext.id, detail = phone || AccountLabel.showsDetail(ext.id))
                button.text = label
                button.isChecked = selected
                user.cover.loadAsCircle(button) {
                    if (it != null) {
                        button.icon = it
                        button.iconTint = null
                    } else button.setIconResource(R.drawable.ic_account_circle)
                }
                button.setOnClickListener {
                    button.isChecked = selected
                    if (selected) return@setOnClickListener
                    viewModel.setLoginUser(user.toEntity(ext.type, ext.id))
                    dismiss()
                }
                row.more.setOnClickListener { anchor ->
                    PopupMenu(requireContext(), anchor).apply {
                        menu.add(R.string.remove_account).setOnMenuItemClickListener {
                            confirmRemove(user.accountLabelInline()) { viewModel.remove(ext.type, ext.id, user.id) }
                            true
                        }
                    }.show()
                }
                binding.accountList.addView(row.root)
            }
        }
    }

    // [2026-11-13] "Remove (account)?" before an account is deleted: the ⋮ row action and, on a phone, the Remove account button.
    private fun confirmRemove(label: String, remove: () -> Unit) {
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(getString(R.string.remove_account_x, label))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.remove) { _, _ -> remove() }
            .show()
    }
}