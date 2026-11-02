package dev.brahmkshatriya.echo.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.common.models.ExtensionType
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.brahmkshatriya.echo.databinding.DialogTvPairingCredentialsBinding
import dev.brahmkshatriya.echo.databinding.FragmentTvPairingBinding
import dev.brahmkshatriya.echo.common.models.User
import dev.brahmkshatriya.echo.extension.DeezerExtension
import dev.brahmkshatriya.echo.extensions.ExtensionLoader
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.getAs
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.getExtension
import dev.brahmkshatriya.echo.ui.common.FragmentUtils.openFragment
import dev.brahmkshatriya.echo.ui.common.UiViewModel.Companion.applyInsetsWithChild
import dev.brahmkshatriya.echo.ui.extensions.login.LoginFragment
import dev.brahmkshatriya.echo.utils.ui.AnimationUtils.setupTransition
import dev.brahmkshatriya.echo.utils.ui.AutoClearedValue.Companion.autoCleared
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import org.koin.android.ext.android.inject

class TvPairingFragment : Fragment() {

    companion object {
        fun getBundle(code: String) = Bundle().apply { putString("code", code) }
    }

    private var binding by autoCleared<FragmentTvPairingBinding>()
    private val prefilledCode by lazy { arguments?.getString("code").orEmpty() }
    private val extensionLoader by inject<ExtensionLoader>()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        binding = FragmentTvPairingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        setupTransition(view)
        // [2026-11-01] The only full-screen page with no inset handling: its toolbar drew under the status bar on the
        // phone. App bar gets top (status bar) and start/end (rail); the scroll view gets start/end and bottom
        // (navigation bar, nav view, mini player - UiViewModel.combined). bottom = 0: the content keeps its own 24dp.
        applyInsetsWithChild(binding.appBarLayout, binding.nestedScrollView, bottom = 0)
        binding.toolBar.setNavigationOnClickListener {
            parentFragmentManager.popBackStack()
        }
        if (prefilledCode.isNotEmpty()) {
            binding.codeInput.setText(prefilledCode)
        }
        binding.cancelButton.setOnClickListener {
            parentFragmentManager.popBackStack()
        }
        binding.confirmButton.setOnClickListener {
            val entered = binding.codeInput.text.toString().trim().uppercase()
            if (entered.length != 6) {
                showStatus("Please enter the full 6-character code.")
                return@setOnClickListener
            }
            startLink(entered)
        }
    }

    // The code waiting on the Deezer sign-in screen opened by submitPairing; see onHiddenChanged.
    private var signInCode: String? = null
    // The email and password given for that code, if any, so the return from sign-in does not ask again.
    private var signInCredentials: Credentials? = null

    // storedArl: the stored user's ARL, set only when the credentials came from the stored user (storedCredentials);
    // typed ones carry null. See submitPairing for what it is used for.
    private data class Credentials(val email: String, val password: String, val storedArl: String? = null)

    /**
     * ⚠⚠ A LINKED TV SIGNS IN BY ITSELF (2026-11-02). Until now the TV got only a copy of this phone's ARL, so the two
     * shared one session: when the phone's was renewed or replaced the TV's copy died (Nov 1 and Nov 2), and an
     * ARL-only TV cannot sign in again. Now the Deezer email and password go with the ARL, and the TV signs in with
     * them (LoginViewModel.onSmartLoginComplete), getting its own ARL and session and renewing with its own password.
     * They come from this phone's stored user (the E-Mail and Password form, or a web login since 1159), or, when
     * none are stored (a pasted ARL), from [askForCredentials]. Typed ones are used for this link only: never saved,
     * and this phone's session is not touched. Skipped, or no stored password: the ARL alone, as before.
     * The ARL is always sent, so a TV on an older build still links with it.
     */
    private fun startLink(code: String) {
        binding.confirmButton.isEnabled = false
        binding.codeInputLayout.isEnabled = false
        lifecycleScope.launch {
            val credentials = storedCredentials() ?: when (val answer = askForCredentials()) {
                is CredentialsAnswer.Entered -> answer.credentials
                CredentialsAnswer.Skipped -> null
                CredentialsAnswer.Cancelled -> {
                    binding.confirmButton.isEnabled = true
                    binding.codeInputLayout.isEnabled = true
                    return@launch
                }
            }
            submitPairing(code, credentials)
        }
    }

    private suspend fun storedCredentials(): Credentials? = withContext(Dispatchers.IO) {
        val dao = extensionLoader.db.userDao()
        val current = dao.getCurrentUser(ExtensionType.MUSIC, "deezer") ?: return@withContext null
        val extras = dao.getUser(ExtensionType.MUSIC, "deezer", current.userId)?.user?.getOrNull()?.extras
            ?: return@withContext null
        val email = extras["email"].orEmpty()
        val password = extras["pass"].orEmpty()
        if (email.isBlank() || password.isEmpty()) null
        else Credentials(email.trim(), password, extras["arl"]?.takeIf { it.isNotEmpty() })
    }

    private sealed interface CredentialsAnswer {
        data class Entered(val credentials: Credentials) : CredentialsAnswer
        data object Skipped : CredentialsAnswer
        data object Cancelled : CredentialsAnswer
    }

    // Continue with both fields filled -> Entered; Skip, or Continue with a field empty -> Skipped (the ARL alone);
    // Back or a tap outside -> Cancelled (nothing is sent).
    private suspend fun askForCredentials(): CredentialsAnswer = suspendCancellableCoroutine { cont ->
        val dialogBinding = DialogTvPairingCredentialsBinding.inflate(layoutInflater)
        fun finish(answer: CredentialsAnswer) { if (cont.isActive) cont.resume(answer) }
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.tv_pairing_credentials_title)
            .setMessage(R.string.tv_pairing_credentials_message)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.tv_pairing_credentials_continue) { _, _ ->
                val email = dialogBinding.emailInput.text?.toString()?.trim().orEmpty()
                val password = dialogBinding.passwordInput.text?.toString().orEmpty()
                finish(
                    if (email.isEmpty() || password.isEmpty()) CredentialsAnswer.Skipped
                    else CredentialsAnswer.Entered(Credentials(email, password))
                )
            }
            .setNegativeButton(R.string.tv_pairing_credentials_skip) { _, _ -> finish(CredentialsAnswer.Skipped) }
            .setOnDismissListener { finish(CredentialsAnswer.Cancelled) }
            .show()
        cont.invokeOnCancellation { dialog.dismiss() }
    }

    /**
     * ⚠⚠ ONLY A VALIDATED ARL IS EVER POSTED (2026-10-31). This used to post the STORED user's ARL as is,
     * which goes stale after a silent re-login (see DeezerSession.onUserRenewed), and a dead ARL gives the TV
     * a guest session. DeezerExtension.userForPairing checks it with deezer.getUserData and renews it
     * silently when it can; when it cannot, the Deezer sign-in screen opens, and on returning this retries
     * ONCE with the same code ([afterSignIn]) and then stops, so backing out of sign-in cannot loop.
     * [CORRECTED 2026-11-02] "renews it silently when it can" no longer holds here: userForPairing is called with
     * allowRelogin = false, so linking never signs this phone in again (the phone's session is untouched by linking).
     * [CORRECTED 2026-11-02, same day] "A live ARL that fails the TV test now opens the sign-in screen even when a
     * password is stored" was true for one revision and is not any more. With a STORED email and password, a failed
     * validatedArl does not block linking: the stored user's ARL is sent as is (Credentials.storedArl), because the
     * TV signs in with the password and the ARL is only the fallback for TVs on older builds. The sign-in screen
     * opens only when there are no stored credentials and validatedArl fails (or the stored user has no ARL - the
     * pairing worker refuses a post without one). Typed credentials carry no storedArl, so they follow the ARL rules.
     */
    private fun submitPairing(code: String, credentials: Credentials?, afterSignIn: Boolean = false) {
        binding.confirmButton.isEnabled = false
        binding.codeInputLayout.isEnabled = false
        showStatus("Linking…")
        lifecycleScope.launch {
            val arl = validatedArl() ?: credentials?.storedArl
            if (arl == null) {
                if (afterSignIn) {
                    // Reached only when no ARL passed the TV's own test (DeezerExtension.userForPairing) even after
                    // a sign-in, so say what WILL work rather than "sign in" again (2026-10-31). Email and password
                    // can always be renewed silently; a Google/Apple/Facebook sign-in has no password to renew with.
                    // [CORRECTED 2026-11-02] Not renewed silently during linking any more (see the note above); a
                    // fresh sign-in on this phone is what produces an ARL that passes.
                    showStatus(
                        "Couldn't get a Deezer login that works on a TV. On this phone, sign in to Deezer with " +
                            "email and password (or paste an ARL from a browser), then enter the code again."
                    )
                    binding.confirmButton.isEnabled = true
                    binding.codeInputLayout.isEnabled = true
                } else {
                    signInCode = code
                    signInCredentials = credentials
                    showStatus("Sign in to Deezer to link your TV.")
                    openFragment<LoginFragment>(
                        null, LoginFragment.getBundle("deezer", "Deezer", ExtensionType.MUSIC)
                    )
                }
                return@launch
            }
            val ok = postPairing(code, arl, credentials)
            if (ok) {
                // ⚠️ NOT "linked successfully" (2026-10-31): the worker's {"ok": true} only means it STORED the ARL; the
                // TV fetches it separately and the phone never learns whether the TV accepted it. Only an ARL that
                // passed the TV's own test is ever sent (DeezerExtension.userForPairing).
                // [CORRECTED 2026-11-02] Except with stored email and password: then the stored ARL may go unchecked,
                // as the older-TV fallback beside them (see submitPairing's note).
                showStatus("Sent to TV. Finish on the TV.")
                binding.cancelButton.text = getString(R.string.done)
            } else {
                showStatus("Failed to link. Check the code and try again.")
                binding.confirmButton.isEnabled = true
                binding.codeInputLayout.isEnabled = true
            }
        }
    }

    // openFragment HIDES this fragment rather than pausing it, so the return from sign-in arrives here, not
    // in onResume.
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) return
        val code = signInCode ?: return
        signInCode = null
        val given = signInCredentials
        signInCredentials = null
        // A sign-in with email and password just now stores them, so they are read again rather than asked for.
        lifecycleScope.launch { submitPairing(code, given ?: storedCredentials(), afterSignIn = true) }
    }

    private suspend fun validatedArl(): String? = withContext(Dispatchers.IO) {
        val deezer = extensionLoader.music.getExtension("deezer") ?: return@withContext null
        deezer.getAs<DeezerExtension, User?> { userForPairing(allowRelogin = false) }.getOrNull()
            ?.extras?.get("arl")?.takeIf { it.isNotEmpty() }
    }

    // Body: {"code", "arl"} as before, plus "email" and "password" when there are credentials (pairing worker
    // 2026-11-02, which stores the whole payload). JSONObject so an email or password is escaped correctly.
    private suspend fun postPairing(code: String, arl: String, credentials: Credentials?): Boolean = withContext(Dispatchers.IO) {
        try {
            val conn = java.net.URL("https://gladix-pairing.schwertley.workers.dev/pair")
                .openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("Content-Type", "application/json")
            val body = org.json.JSONObject().apply {
                put("code", code)
                put("arl", arl)
                if (credentials != null) {
                    put("email", credentials.email)
                    put("password", credentials.password)
                }
            }.toString()
            conn.outputStream.use { it.write(body.toByteArray()) }
            if (conn.responseCode == 200) {
                val response = conn.inputStream.bufferedReader().readText()
                org.json.JSONObject(response).optBoolean("ok", false)
            } else false
        } catch (e: Exception) {
            android.util.Log.e("GladixTV", "Pairing POST failed", e)
            false
        }
    }

    private fun showStatus(message: String) {
        binding.statusText.text = message
        binding.statusText.isVisible = true
    }
}
