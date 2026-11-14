package dev.brahmkshatriya.echo.ui.main

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.fragment.app.Fragment
import com.google.android.material.color.MaterialColors
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.ui.common.SnackBarHandler.Companion.createSnack
import dev.brahmkshatriya.echo.ui.extensions.login.AccountLabel
import dev.brahmkshatriya.echo.ui.extensions.login.AccountLabel.accountLabelInline
import dev.brahmkshatriya.echo.common.Extension
import dev.brahmkshatriya.echo.common.MusicExtension
import dev.brahmkshatriya.echo.common.models.ExtensionType
import dev.brahmkshatriya.echo.common.models.ImageHolder
import dev.brahmkshatriya.echo.common.models.Message
import dev.brahmkshatriya.echo.extensions.db.models.CurrentUser
import dev.brahmkshatriya.echo.ui.extensions.login.LoginUserListBottomSheet
import dev.brahmkshatriya.echo.common.models.User
import dev.brahmkshatriya.echo.databinding.ItemMainHeaderBinding
import dev.brahmkshatriya.echo.extensions.db.models.UserEntity.Companion.toEntity
import dev.brahmkshatriya.echo.ui.common.GridAdapter
import dev.brahmkshatriya.echo.ui.extensions.list.ExtensionsListBottomSheet
import dev.brahmkshatriya.echo.ui.extensions.login.LoginUserListViewModel
import dev.brahmkshatriya.echo.ui.settings.SettingsBottomSheet
import dev.brahmkshatriya.echo.utils.ContextUtils.observe
import dev.brahmkshatriya.echo.utils.image.ImageUtils.loadAsCircle
import dev.brahmkshatriya.echo.utils.ui.UiUtils.isTv
import dev.brahmkshatriya.echo.utils.ui.scrolling.ScrollAnimRecyclerAdapter
import dev.brahmkshatriya.echo.utils.ui.scrolling.ScrollAnimViewHolder
import org.koin.androidx.viewmodel.ext.android.activityViewModel

class HeaderAdapter(
    private val fragment: Fragment,
    // TV only (2026-11-04): where Down goes from the extension icon. Search passes its bar; see SearchBarAdapter.
    private val tvNextFocusDown: Int = View.NO_ID,
) : ScrollAnimRecyclerAdapter<HeaderAdapter.ViewHolder>(), GridAdapter {
    private val viewModel by fragment.activityViewModel<LoginUserListViewModel>()

    override val adapter = this
    override fun getSpanSize(position: Int, width: Int, count: Int) = count
    override fun getItemCount() = 1
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val holder = ViewHolder(parent)
        val binding = holder.binding
        val parentFragmentManager = fragment.parentFragmentManager
        // Here, not in a bind: a rebuilt header row keeps it. FocusFinder takes a set id before comparing distances.
        if (parent.context.isTv()) binding.extensionsCont.nextFocusDownId = tvNextFocusDown
        binding.extensionsCont.setOnClickListener {
            ExtensionsListBottomSheet.newInstance(ExtensionType.MUSIC)
                .show(parentFragmentManager, null)
        }

        // [2026-11-13] On a phone, for an extension with login, the avatar opens the account sheet directly (audit item 3); the
        // sheet has a Settings entry, so Settings stays one tap away. Without login, and on TV, it opens Settings as before.
        binding.accountsCont.setOnClickListener {
            if (!parent.context.isTv() && triple.second) LoginUserListBottomSheet().show(parentFragmentManager, null)
            else SettingsBottomSheet().show(parentFragmentManager, null)
        }
        return holder
    }

    private var extension: MusicExtension? = null
        set(value) {
            field = value
            notifyItemChanged(0)
        }

    private var triple: Triple<Extension<*>?, Boolean, List<Pair<User, Boolean>>> =
        Triple(null, false, emptyList())
        set(value) {
            field = value
            notifyItemChanged(0)
        }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = with(holder.binding) {
        super.onBindViewHolder(holder, position)
        val context = root.context
        title.text = extension?.name ?: context.getString(R.string.app_name)
        extensions.loadBigIcon(extension?.metadata?.icon, R.drawable.ic_extension_32dp)
        extensionsCont.setLoopedLongClick(
            viewModel.extensionLoader.music.value.filter { it.isEnabled },
            { viewModel.extensionLoader.current.value }
        ) {
            viewModel.extensionLoader.setupMusicExtension(it, true)
        }

        val (ext, isLoginClient, all) = triple
        accounts.loadBigIcon(
            viewModel.currentUser.value?.cover,
            if (isLoginClient) R.drawable.ic_account_circle_32dp else R.drawable.ic_settings_outline_32dp
        )
        accountsCont.setAccountQuickSwitch(fragment, viewModel, ext, all)
    }

    class ViewHolder(
        parent: ViewGroup,
        val binding: ItemMainHeaderBinding = ItemMainHeaderBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        ),
    ) : ScrollAnimViewHolder(binding.root)

    init {
        with(fragment) {

            observe(viewModel.extensionLoader.current) {
                viewModel.currentExtension.value = it
                extension = it
            }
            observe(viewModel.allUsersWithClient) {
                triple = it
            }
        }
    }

    companion object {
        /**
         * [2026-11-13] THE ACCOUNT QUICK SWITCH: long-press moves to the next saved account (the header avatar, and the Settings
         * sheet's account name and avatar). On a phone (audit item 4): it always says "Switched to <account>" with Undo, which
         * puts back the account that was current (or no account), and with no account selected it switches to the first saved
         * one. On TV it is as before: no Undo, nothing from no account, and the message only when AccountLabel.showsDetail.
         */
        fun View.setAccountQuickSwitch(
            fragment: Fragment, viewModel: LoginUserListViewModel, ext: Extension<*>?, all: List<Pair<User, Boolean>>,
        ) {
            val phone = !context.isTv()
            setLoopedLongClick(all, { all.find { it.second } }, fromNoneToFirst = phone) { (user, _) ->
                ext ?: return@setLoopedLongClick
                val previous = all.find { it.second }?.first
                viewModel.setLoginUser(user.toEntity(ext.type, ext.id))
                val text = context.getString(R.string.switched_to_x, user.accountLabelInline())
                if (phone) fragment.createSnack(
                    Message(text, Message.Action(context.getString(R.string.undo)) {
                        if (previous != null) viewModel.setLoginUser(previous.toEntity(ext.type, ext.id))
                        else viewModel.setLoginUser(CurrentUser(ext.type, ext.id, null))
                    })
                )
                // The button shows only the avatar, so say which account this switched to
                else if (AccountLabel.showsDetail(ext.id)) fragment.createSnack(text)
            }
        }

        // [2026-11-13] [fromNoneToFirst]: with nothing current, the first item instead of nothing (the account quick switch on
        // a phone). The extension switcher passes false, as before.
        fun <T> View.setLoopedLongClick(
            list: List<T>,
            getCurrent: (View) -> T?,
            fromNoneToFirst: Boolean = false,
            onSelect: (T) -> Unit,
        ) {
            setOnLongClickListener {
                val current = getCurrent(this)
                val index = list.indexOf(current)
                if (index == -1) {
                    if (!fromNoneToFirst || current != null || list.isEmpty()) return@setOnLongClickListener false
                    onSelect(list.first())
                    return@setOnLongClickListener true
                }
                val next = list[(index + 1) % list.size]
                if (next == current) return@setOnLongClickListener false
                onSelect(next)
                true
            }
        }

        fun ImageView.loadBigIcon(image: ImageHolder?, placeholder: Int) {
            val color = ColorStateList.valueOf(
                MaterialColors.getColor(
                    this,
                    androidx.appcompat.R.attr.colorControlNormal
                )
            )
            image.loadAsCircle(this) {
                if (it == null) {
                    imageTintList = color
                    setImageResource(placeholder)
                } else {
                    imageTintList = null
                    setImageDrawable(it)
                }
            }
        }
    }
}