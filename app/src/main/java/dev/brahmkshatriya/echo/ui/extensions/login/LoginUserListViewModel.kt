package dev.brahmkshatriya.echo.ui.extensions.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.brahmkshatriya.echo.common.Extension
import dev.brahmkshatriya.echo.common.clients.LoginClient
import dev.brahmkshatriya.echo.common.models.ExtensionType
import dev.brahmkshatriya.echo.extensions.ExtensionLoader
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.isClient
import dev.brahmkshatriya.echo.extensions.db.models.CurrentUser
import dev.brahmkshatriya.echo.extensions.db.models.UserEntity
import dev.brahmkshatriya.echo.extensions.db.models.UserEntity.Companion.toCurrentUser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LoginUserListViewModel(
    val extensionLoader: ExtensionLoader,
) : ViewModel() {

    private val userDao = extensionLoader.db.userDao()
    val currentExtension = MutableStateFlow<Extension<*>?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val allUsers = currentExtension
        .combine(extensionLoader.all) { ext, all ->
            if (ext == null) return@combine null
            all.find { it.type == ext.type && it.id == ext.id }
        }
        .combine(userDao.observeCurrentUser()) { a, b -> a to b }
        .flatMapLatest { (ext, current) ->
            val curr = current.find { it.extId == ext?.id && it.type == ext.type }
            val flow = if (ext != null) userDao.observeAllUsers(ext.type, ext.id)
            else null
            flow?.map { entities ->
                val users = entities.mapNotNull { ent ->
                    val selected = ent.id == curr?.userId
                    ent.user.getOrNull()?.let { it to selected }
                }
                ext to users
            } ?: emptyFlow()
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null to listOf())

    @OptIn(ExperimentalCoroutinesApi::class)
    val allUsersWithClient = allUsers.mapLatest {
        val (ext, users) = it
        val isLoginClient = ext?.isClient<LoginClient>() ?: false
        Triple(ext, isLoginClient, users)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, Triple(null, false, emptyList()))

    val currentUser = allUsers.map {
        it.second.find { user -> user.second }?.first
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // The session-expired "Logout and Login" action (ExceptionUtils.getMessage) only: deletes that account and selects nothing,
    // as upstream Echo did. [2026-11-13] The account sheet and the extension settings use removeCurrent / remove instead.
    fun logout(user: UserEntity?) {
        if (user == null) return
        viewModelScope.launch(Dispatchers.IO) {
            userDao.deleteUser(user)
        }
    }

    /**
     * [2026-11-13] "REMOVE ACCOUNT" REMOVES ONE SAVED ACCOUNT AND SELECTS ANOTHER, for every extension, Deezer included (the user's
     * decision, aligning them). Upstream Echo's Logout (f6f35c18) deleted the current account and then selected NO account, so
     * with several saved accounts it looked as if all were gone, while the others were still saved. Now, when the removed
     * account was the current one, the first remaining saved account for that extension becomes current; none left is no
     * account, as before. Removing an account that is not current changes nothing else.
     * The switch is a CurrentUser write, the same one a tap on an account makes (setLoginUser below): ExtensionLoader observes
     * CurrentUser and hydrates that account's credentials through the extension's setLoginUser, so a Deezer account selected
     * here gets its ARL loaded exactly as a normal switch does, and TvPairingFragment pairs whichever Deezer account is current.
     */
    fun remove(type: ExtensionType, extId: String, userId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val wasCurrent = userDao.getCurrentUser(type, extId)?.userId == userId
            userDao.getUser(type, extId, userId)?.let { userDao.deleteUser(it) }
            if (!wasCurrent) return@launch
            val next = userDao.observeAllUsers(type, extId).first()
                .firstOrNull { it.id != userId && it.user.getOrNull() != null }
            userDao.setCurrentUser(CurrentUser(type, extId, next?.id))
        }
    }

    /** [2026-11-13] [remove] for the extension's current account; nothing current, nothing to do. */
    fun removeCurrent(type: ExtensionType, extId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val userId = userDao.getCurrentUser(type, extId)?.userId ?: return@launch
            remove(type, extId, userId)
        }
    }

    fun setLoginUser(user: UserEntity?) {
        val currentUser = user?.toCurrentUser()
            ?: currentExtension.value?.let { CurrentUser(it.type, it.id, null) }
            ?: return
        setLoginUser(currentUser)
    }

    fun setLoginUser(currentUser: CurrentUser) {
        viewModelScope.launch(Dispatchers.IO) {
            userDao.setCurrentUser(currentUser)
        }
    }
}