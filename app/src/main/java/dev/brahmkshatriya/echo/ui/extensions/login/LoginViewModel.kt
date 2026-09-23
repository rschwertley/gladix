package dev.brahmkshatriya.echo.ui.extensions.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.common.clients.LoginClient
import dev.brahmkshatriya.echo.common.models.ExtensionType
import dev.brahmkshatriya.echo.common.models.Message
import dev.brahmkshatriya.echo.common.models.User
import dev.brahmkshatriya.echo.extensions.ExtensionLoader
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.get
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.getAs
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.getExtensionFlow
import dev.brahmkshatriya.echo.extensions.db.models.UserEntity.Companion.toCurrentUser
import dev.brahmkshatriya.echo.extensions.db.models.UserEntity.Companion.toEntity
import dev.brahmkshatriya.echo.extensions.exceptions.AppException.Companion.toAppException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted.Companion.Eagerly
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LoginViewModel(
    val extensionLoader: ExtensionLoader,
    val extensionType: ExtensionType,
    val extensionId: String? = null,
) : ViewModel() {
    val extension = extensionLoader.getFlow(extensionType).getExtensionFlow(extensionId)
        .stateIn(viewModelScope, Eagerly, null)

    private val app = extensionLoader.app
    val messageFlow = app.messageFlow
    private val userDao = extensionLoader.db.userDao()
    val loading = MutableStateFlow(true)
    val loadingOver = MutableSharedFlow<Unit>()
    val addFragmentFlow = MutableSharedFlow<FragmentType>()

    sealed class FragmentType {
        data object Selector : FragmentType()
        data object WebView : FragmentType()
        data class CustomInput(val index: Int?) : FragmentType()
        data object SmartLogin : FragmentType()
    }

    private suspend fun loginNotSupported(extName: String?) {
        val login = app.context.getString(R.string.login)
        val message =
            app.context.getString(R.string.x_is_not_supported_in_x, login, extName.toString())
        messageFlow.emit(Message(message))
        loadingOver.emit(Unit)
    }

    fun changeFragment(type: FragmentType) = viewModelScope.launch {
        addFragmentFlow.emit(type)
    }

    private suspend fun afterLogin(
        result: Result<List<User>>
    ) {
        val users = result.getOrElse {
            if (it is CancellationException) throw it
            app.throwFlow.emit(it)
            loading.value = false
            loadingOver.emit(Unit)
            return@afterLogin
        }
        if (users.isEmpty()) {
            app.messageFlow.emit(Message(app.context.getString(R.string.no_user_found)))
        } else {
            val entities = users.map { it.toEntity(extensionType, extensionId!!) }
            userDao.insertUsers(entities)
            val user = entities.first()
            userDao.setCurrentUser(user.toCurrentUser())
        }
        loading.value = false
        loadingOver.emit(Unit)
    }

    fun onSmartLoginComplete(arl: String) = viewModelScope.launch {
        loading.value = true
        val extension = extension.first { it != null }!!
        val users = extension.getAs<LoginClient.CustomInput, List<User>> {
            onLogin("manual", mapOf("arl" to arl))
        }
        afterLogin(users)
    }

    fun onWebViewStop(
        result: Result<List<User>>,
    ) = viewModelScope.launch {
        val extension = extension.first { it != null }!!
        val users = runCatching { result.getOrElse { throw it.toAppException(extension) } }
        afterLogin(users)
    }

    val inputs = mutableMapOf<String, String?>()
    fun onCustomTextInputSubmit(form: LoginClient.Form) = viewModelScope.launch {
        loading.value = true
        val extension = extension.first { it != null }!!
        val users = extension.getAs<LoginClient.CustomInput, List<User>> {
            onLogin(form.key, inputs.toMap())
        }
        afterLogin(users)
    }

    init {
        // ⚠⚠ THE LOGIN UI IS A CAPABILITY CHECK, SO AN EXTENSION THAT CANNOT BE INJECTED
        // CANNOT BE LOGGED INTO. Both reads below go through Injectable.value(), which runs the
        // extension's injection block and returns Result.failure if ANY injection throws. When that
        // happens `extension.get { }` yields null, totalClients falls to 0, and the user is told
        // "Login is not supported in <ext>" - the one message that is certainly false, on the one
        // screen that could have fixed the state.
        // FIELD INSTANCE, build 1108 (2026-09-23): Deezer's onExtensionSelected rethrew a
        // LoginRequired that fires on every cold start before credentials hydrate, so value() failed
        // permanently and this screen refused to offer a login. Fixed at the throwing site.
        // ⚠️ THE STRUCTURAL SMELL, NAMED SO IT IS RECOGNISED NEXT TIME: a state that can
        // only be cleared by an action whose AVAILABILITY DEPENDS ON THAT STATE. Deezer's refusal
        // latch is cleared only by setLoginUser, reachable only by logging in, gated by this check.
        // ⚠️ [CORRECTED 2026-09-23] NOT HARDENED IN THE INCIDENT PASS - RIGHT CALL, WRONG
        // REASON, AND THE WRONG REASON IS KEPT BECAUSE IT IS THE ONE A READER WILL RE-DERIVE. It
        // was argued that bypassing injections risks a login that cannot PERSIST, because
        // setSettings would be skipped. THAT IS FALSE: a login persists HOST-SIDE. afterLogin below
        // writes the returned User - extras and all - through UserEntity.toEntity, which serialises
        // the WHOLE object to JSON into Room, and DeezerExtension.setLoginUser re-hydrates from
        // there on every process. Extension settings are not in that path at all.
        // CORROBORATED INDEPENDENTLY by the project record, which states it from the other end:
        // "ARL lives in Room DB as UserEntity.user.extras['arl'] - queried via
        // userDao.getCurrentUser(MUSIC, 'deezer'). NO SharedPreferences involved." Recorded because
        // the WRONG reason is the quotable one: left uncorrected, "do not bypass, settings would be
        // skipped" would be cited later as an established constraint. It is not one.
        //
        // ⚠⚠ THE REAL REASON TO SPLIT RATHER THAN BYPASS: THE INJECTION BLOCK IS NOT
        // HOMOGENEOUS, AND TODAY IT IS ONE LAMBDA SO ONE FAILURE DISCARDS ALL OF IT. Read
        // ExtensionLoader.injected - seven statements in a single entry:
        //   CONFIGURE  setMetadata, setMessageFlow, setGlobalSettings, setSettings,
        //              setWebViewClient - pure field assignment. No I/O, no auth, cannot
        //              meaningfully fail. A WebView login literally cannot run without the last one.
        //   ACTIVATE   onInitialize(), onExtensionSelected() - the only two that do work, and the
        //              only two that can throw. (onInitialize is an empty default on ExtensionClient.)
        // LOGIN NEEDS A CONFIGURED CLIENT, NOT AN ACTIVATED ONE. So the shape is a second accessor
        // that runs the CONFIGURE prefix and returns the client whatever ACTIVATE did - not a
        // bypass of injection, which would skip the setters a third-party login may genuinely need.
        //
        // ⚠⚠ DO NOT CLOSE IT BY CHANGING isClient<T>() GLOBALLY. It backs EVERY capability
        // check in the app and today it FAILS CLOSED - an extension whose activation failed reports
        // no capabilities, so nothing runs against it. Reporting capabilities on an unactivated
        // client would let playback paths call an extension whose onExtensionSelected never ran.
        // The surface to switch is exactly three sites: here, LoginUserListViewModel's
        // isLoginClient, and ExtensionInfoViewModel's LoginClient check. Nothing else.
        //
        // ⚠️ AND THE CHEAPER HALF IS HOST-SIDE, NOT HERE: Injectable.value() drains
        // injectionsMap only after injections, so a failed injection strands queued work forever -
        // see the note there. Draining the map regardless WOULD ALONE HAVE PREVENTED THE 1108
        // LOCKOUT (setLoginUser runs, credentials hydrate, the next value() succeeds), while this
        // split is what stops an injection failure REVOKING THE LOGIN AFFORDANCE. Complementary,
        // and the drain is the smaller one.
        //
        // ⚠⚠ THE GUARANTEE, WRITTEN OUT BECAUSE THE SPLIT WILL LATER BE READ AS PROMISING
        // A STRONGER ONE. What the split buys, exactly:
        //   GUARANTEED      an injection failure can never REMOVE THE LOGIN AFFORDANCE. The login
        //                   is offered, entered and attempted.
        //   NOT GUARANTEED  that the login SUCCEEDS. No design can promise that when an
        //                   extension's own initialisation is broken - if its onLogin depends on
        //                   state that onInitialize never set, it still fails.
        // THE DIFFERENCE IS WHERE THE FAILURE LANDS, and that is the whole value: at the point of
        // login, with that extension's own error, which a user can read and act on - instead of as
        // a capability that silently is not there, which a user can only read as "not supported".
        // ⚠️ SO DO NOT LATER RECORD THIS AS "LOGIN ALWAYS WORKS" OR TREAT A STILL-FAILING
        // LOGIN AS THE SPLIT NOT WORKING. A refused login that reaches the user IS the success
        // condition; only a missing or refusing-to-open login screen is a regression of it.
        viewModelScope.launch {
            val extension = extension.first { it != null }!!
            val totalClients = extension.get {
                listOfNotNull(
                    if (this is LoginClient.WebView) 1 else 0,
                    if (this is LoginClient.CustomInput) forms.size
                    else 0,
                ).sum()
            }.getOrNull() ?: 0
            val client = extension.instance.value().getOrNull()
            when (totalClients) {
                0 -> loginNotSupported(extension.name)
                1 -> when (client) {
                    is LoginClient.WebView -> addFragmentFlow.emit(FragmentType.WebView)
                    is LoginClient.CustomInput -> addFragmentFlow.emit(FragmentType.CustomInput(null))
                    null -> loginNotSupported(extension.name)
                }

                else -> addFragmentFlow.emit(FragmentType.Selector)
            }
        }
    }
}