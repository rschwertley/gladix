package dev.brahmkshatriya.echo.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.common.models.ExtensionType
import dev.brahmkshatriya.echo.databinding.FragmentTvPairingBinding
import dev.brahmkshatriya.echo.extensions.ExtensionLoader
import dev.brahmkshatriya.echo.utils.ui.AnimationUtils.setupTransition
import dev.brahmkshatriya.echo.utils.ui.AutoClearedValue.Companion.autoCleared
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

class TvPairingFragment : Fragment() {

    companion object {
        fun getBundle(code: String) = bundleOf("code" to code)
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
            submitPairing(entered)
        }
    }

    private fun submitPairing(code: String) {
        binding.confirmButton.isEnabled = false
        binding.codeInputLayout.isEnabled = false
        showStatus("Linking…")
        lifecycleScope.launch {
            val arl = getArl()
            if (arl.isNullOrEmpty()) {
                showStatus("Not logged in to Deezer. Please log in first.")
                binding.confirmButton.isEnabled = true
                binding.codeInputLayout.isEnabled = true
                return@launch
            }
            val ok = postPairing(code, arl)
            if (ok) {
                showStatus("TV linked successfully!")
                binding.cancelButton.text = getString(R.string.done)
            } else {
                showStatus("Failed to link. Check the code and try again.")
                binding.confirmButton.isEnabled = true
                binding.codeInputLayout.isEnabled = true
            }
        }
    }

    private suspend fun getArl(): String? = withContext(Dispatchers.IO) {
        val dao = extensionLoader.db.userDao()
        val current = dao.getCurrentUser(ExtensionType.MUSIC, "deezer") ?: return@withContext null
        dao.getUser(ExtensionType.MUSIC, "deezer", current.userId)
            ?.user?.getOrNull()?.extras?.get("arl")
    }

    private suspend fun postPairing(code: String, arl: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val conn = java.net.URL("https://gladix-pairing.schwertley.workers.dev/pair")
                .openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("Content-Type", "application/json")
            val body = """{"code":"$code","arl":"$arl"}"""
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
