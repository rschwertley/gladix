package dev.brahmkshatriya.echo.ui.extensions.login

import android.app.UiModeManager
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.text.InputType.TYPE_CLASS_NUMBER
import android.text.InputType.TYPE_CLASS_TEXT
import android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
import android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
import android.text.InputType.TYPE_TEXT_VARIATION_URI
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.getSystemService
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.fragment.app.replace
import androidx.lifecycle.lifecycleScope
import com.google.android.material.transition.MaterialSharedAxis
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.common.clients.LoginClient
import dev.brahmkshatriya.echo.common.clients.LoginClient.InputField.Type
import dev.brahmkshatriya.echo.common.models.ExtensionType
import dev.brahmkshatriya.echo.common.models.Message
import dev.brahmkshatriya.echo.databinding.FragmentExtensionLoginCustomInputBinding
import dev.brahmkshatriya.echo.databinding.FragmentExtensionLoginSelectorBinding
import dev.brahmkshatriya.echo.databinding.FragmentExtensionLoginSmartBinding
import dev.brahmkshatriya.echo.databinding.FragmentGenericCollapsableBinding
import dev.brahmkshatriya.echo.databinding.FragmentWebviewBinding
import dev.brahmkshatriya.echo.databinding.ItemExtensionButtonBinding
import dev.brahmkshatriya.echo.databinding.ItemInputBinding
import dev.brahmkshatriya.echo.extension.DeezerExtension
import dev.brahmkshatriya.echo.extensions.exceptions.AppException
import dev.brahmkshatriya.echo.ui.common.UiViewModel.Companion.applyAppBarRailInset
import dev.brahmkshatriya.echo.ui.common.UiViewModel.Companion.applyBackPressCallback
import dev.brahmkshatriya.echo.ui.common.UiViewModel.Companion.applyContentInsets
import dev.brahmkshatriya.echo.ui.common.UiViewModel.Companion.applyInsets
import dev.brahmkshatriya.echo.ui.extensions.WebViewUtils.configure
import dev.brahmkshatriya.echo.utils.ContextUtils.observe
import dev.brahmkshatriya.echo.utils.image.ImageUtils.loadAsCircle
import dev.brahmkshatriya.echo.utils.ui.AnimationUtils.setupTransition
import dev.brahmkshatriya.echo.utils.ui.AutoClearedValue.Companion.autoCleared
import dev.brahmkshatriya.echo.utils.ui.UiUtils.configureAppBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.koin.core.parameter.parametersOf

class LoginFragment : Fragment() {
    companion object {
        fun getBundle(extId: String, extName: String, extensionType: ExtensionType) = Bundle().apply {
            putString("extId", extId)
            putString("extName", extName)
            putString("extensionType", extensionType.name)
        }

        fun getBundle(error: AppException.LoginRequired) =
            getBundle(error.extension.id, error.extension.name, error.extension.type)


        fun FragmentGenericCollapsableBinding.bind(
            fragment: Fragment, applyInsets: Boolean = true
        ) = with(fragment) {
            setupTransition(root)
            if (applyInsets) applyInsets {
                genericFragmentContainer.applyContentInsets(it)
            }
            applyBackPressCallback()
            appBarLayout.configureAppBar { offset ->
                toolbarOutline.alpha = offset
                iconContainer.alpha = 1 - offset
            }
            applyAppBarRailInset(appBarLayout)
            toolBar.setNavigationOnClickListener {
                parentFragmentManager.popBackStack()
            }
        }

        private fun getIcon(type: Type) = when (type) {
            Type.Email -> R.drawable.ic_email
            Type.Password -> R.drawable.ic_password
            Type.Number -> R.drawable.ic_numbers
            Type.Url -> R.drawable.ic_language
            Type.Username -> R.drawable.ic_account_circle
            Type.Misc -> R.drawable.ic_input
        }
    }

    private var binding by autoCleared<FragmentGenericCollapsableBinding>()
    private val clientType by lazy {
        val type = requireArguments().getString("extensionType")!!
        ExtensionType.valueOf(type)
    }
    private val extId by lazy { requireArguments().getString("extId")!! }
    private val extName by lazy { requireArguments().getString("extName")!! }
    private val loginViewModel by viewModel<LoginViewModel> {
        parametersOf(clientType, extId)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        binding = FragmentGenericCollapsableBinding.inflate(inflater, container, false)
        return binding.root
    }

    private inline fun <reified T : Fragment> add(args: Bundle? = null) {
        if (!isAdded) return
        childFragmentManager.run {
            loginViewModel.loading.value = false
            commit {
                setReorderingAllowed(true)
                if (fragments.isNotEmpty()) addToBackStack(null)
                replace<T>(R.id.genericFragmentContainer, null, args)
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.bind(this)
        binding.toolBar.setNavigationOnClickListener {
            parentFragmentManager.popBackStack()
        }
        binding.toolBar.title = getString(R.string.x_login, extName)

        observe(loginViewModel.extension) { ext ->
            ext?.metadata?.icon.loadAsCircle(binding.extensionIcon, R.drawable.ic_extension_32dp) {
                binding.extensionIcon.setImageDrawable(it)
                lifecycleScope.launch {
                    delay(2000)
                    binding.appBarLayout.setExpanded(false)
                }
            }
        }

        observe(loginViewModel.loading) {
            binding.genericFragmentContainer.isVisible = !it
            binding.loading.root.isVisible = it
        }

        observe(loginViewModel.loadingOver) {
            repeat(childFragmentManager.backStackEntryCount) {
                parentFragmentManager.popBackStack()
            }
            parentFragmentManager.popBackStack()
        }

        observe(loginViewModel.addFragmentFlow) {
            when (it) {
                LoginViewModel.FragmentType.Selector -> add<Selector>(arguments)
                LoginViewModel.FragmentType.WebView -> add<WebView>(arguments)
                is LoginViewModel.FragmentType.CustomInput -> add<CustomInput>(Bundle().apply {
                    putAll(arguments)
                    putInt("formIndex", it.index ?: 0)
                })
                LoginViewModel.FragmentType.SmartLogin -> add<SmartLogin>(arguments)
            }
        }
    }

    class Selector : Fragment(R.layout.fragment_extension_login_selector) {
        private val loginViewModel by lazy {
            requireParentFragment().viewModel<LoginViewModel>().value
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            setupTransition(view)
            val binding = FragmentExtensionLoginSelectorBinding.bind(view)
            val client = loginViewModel.extension.value?.instance?.value
            val uiModeManager = requireContext().getSystemService<UiModeManager>()!!
            val isTV = requireContext().packageManager
                .hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
                uiModeManager.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
            val clients = listOfNotNull(
                if (client is LoginClient.WebView && !isTV) {
                    val button = ItemExtensionButtonBinding.inflate(
                        layoutInflater, binding.loginToggleGroup, false
                    ).root
                    button.text = getString(R.string.webview)
                    button.setIconResource(R.drawable.ic_language)
                    button to { loginViewModel.changeFragment(LoginViewModel.FragmentType.WebView) }
                } else null,
                *(if (client is LoginClient.CustomInput) {
                    val forms = runCatching { client.forms }.getOrNull().orEmpty()
                    forms.mapIndexed { index, it ->
                        val button = ItemExtensionButtonBinding.inflate(
                            layoutInflater, binding.loginToggleGroup, false
                        ).root
                        button.text = it.label
                        button.setIconResource(getIcon(it.icon))
                        button to {
                            loginViewModel.changeFragment(
                                LoginViewModel.FragmentType.CustomInput(index)
                            )
                        }
                    }
                } else listOf()).toTypedArray(),
                *(if (isTV && client is DeezerExtension) {
                    val button = ItemExtensionButtonBinding.inflate(
                        layoutInflater, binding.loginToggleGroup, false
                    ).root
                    button.text = getString(R.string.tv_pairing_link_your_device)
                    button.setIconResource(R.drawable.ic_login)
                    listOf(button to {
                        loginViewModel.changeFragment(LoginViewModel.FragmentType.SmartLogin)
                    })
                } else emptyList()).toTypedArray(),
            )
            clients.forEachIndexed { index, pair ->
                val button = pair.first
                button.setOnClickListener { pair.second() }
                binding.loginToggleGroup.addView(button)
                button.id = index
            }
            val count = binding.loginToggleGroup.childCount
            repeat(count) { i ->
                val button = binding.loginToggleGroup.getChildAt(i)
                button.isFocusable = true
                button.nextFocusDownId = if (i < count - 1) i + 1 else i
                button.nextFocusUpId = if (i > 0) i - 1 else 0
            }
            if (count > 0) view.post { binding.loginToggleGroup.getChildAt(0).requestFocus() }
        }
    }

    class WebView : Fragment(R.layout.fragment_webview) {
        private val loginViewModel by lazy {
            requireParentFragment().viewModel<LoginViewModel>().value
        }
        private val extension by lazy {
            loginViewModel.extension.value
        }
        private val webViewRequest by lazy {
            (extension?.instance?.value as? LoginClient.WebView)?.webViewRequest
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            setupTransition(view, axis = MaterialSharedAxis.X)
            val binding = FragmentWebviewBinding.bind(view)
            val req = webViewRequest ?: return
            val callback = requireActivity().configure(
                binding.webview, binding.progress, req, true
            ) {
                if (it == null) loginViewModel.loading.value = true
                else loginViewModel.onWebViewStop(it)
            }
            requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, callback)
        }
    }

    class CustomInput : Fragment(R.layout.fragment_extension_login_custom_input) {
        private val loginViewModel by lazy {
            requireParentFragment().viewModel<LoginViewModel>().value
        }
        private val extension by lazy { loginViewModel.extension.value }
        private val formIndex by lazy { requireArguments().getInt("formIndex", 0) }
        private val form by lazy {
            (extension?.instance?.value as? LoginClient.CustomInput)?.forms?.getOrNull(formIndex)
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            setupTransition(view, axis = MaterialSharedAxis.X)
            val form = form ?: run {
                message(Message("No form found for extension ${extension?.id}"))
                parentFragmentManager.popBackStack()
                return
            }
            val binding = FragmentExtensionLoginCustomInputBinding.bind(view)
            binding.run {
                form.inputFields.forEachIndexed { index, field ->
                    val input = ItemInputBinding.inflate(
                        layoutInflater, customInput, false
                    )
                    input.root.id = field.key.hashCode()
                    input.editText.id = "${field.key}_input".hashCode()
                    input.root.hint = field.label
                    input.root.setStartIconDrawable(getIcon(field.type))
                    @Suppress("DEPRECATION")
                    input.root.isPasswordVisibilityToggleEnabled = field.type == Type.Password
                    input.editText.inputType = when (field.type) {
                        Type.Email -> TYPE_CLASS_TEXT or TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                        Type.Password -> TYPE_CLASS_TEXT or TYPE_TEXT_VARIATION_PASSWORD
                        Type.Number -> TYPE_CLASS_TEXT or TYPE_CLASS_NUMBER
                        Type.Url -> TYPE_CLASS_TEXT or TYPE_TEXT_VARIATION_URI
                        else -> TYPE_CLASS_TEXT
                    }
                    input.editText.setText(loginViewModel.inputs[field.key])
                    input.editText.doAfterTextChanged { editable ->
                        loginViewModel.inputs[field.key] =
                            editable.toString().takeIf { it.isNotBlank() }
                    }
                    input.editText.setOnEditorActionListener { _, _, _ ->
                        if (index < form.inputFields.size - 1) {
                            customInput.getChildAt(index + 1).requestFocus()
                        } else loginCustomSubmit.performClick()
                        true
                    }

                    customInput.addView(input.root)
                }
                if (form.inputFields.isNotEmpty()) {
                    view.post {
                        val firstLayout = customInput.getChildAt(0)
                            as? com.google.android.material.textfield.TextInputLayout
                        (firstLayout?.editText ?: customInput.getChildAt(0))?.requestFocus()
                    }
                }
                loginCustomSubmit.setOnClickListener {
                    form.inputFields.forEach {
                        if (it.isRequired && loginViewModel.inputs[it.key].isNullOrEmpty()) {
                            message(Message(getString(R.string.x_is_required, it.label)))
                            return@setOnClickListener
                        }
                        val regex = it.regex
                        if (regex != null && !loginViewModel.inputs[it.key].isNullOrEmpty()) {
                            if (!loginViewModel.inputs[it.key]!!.matches(regex)) {
                                message(
                                    Message(
                                        getString(R.string.regex_invalid, it.label, regex.pattern)
                                    )
                                )
                                return@setOnClickListener
                            }
                        }
                    }
                    loginViewModel.onCustomTextInputSubmit(form)
                }
            }
        }

        private fun message(m: Message) {
            lifecycleScope.launch {
                loginViewModel.messageFlow.emit(m)
            }
        }
    }

    class SmartLogin : Fragment(R.layout.fragment_extension_login_smart) {
        private val loginViewModel by lazy {
            requireParentFragment().viewModel<LoginViewModel>().value
        }
        private var pollingJob: Job? = null
        private var countdownJob: Job? = null

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            setupTransition(view, axis = MaterialSharedAxis.X)
            val binding = FragmentExtensionLoginSmartBinding.bind(view)
            loadCode(binding)
            binding.regenerateButton.setOnClickListener { loadCode(binding) }
        }

        private fun loadCode(binding: FragmentExtensionLoginSmartBinding) {
            pollingJob?.cancel()
            countdownJob?.cancel()
            binding.qrCode.visibility = View.INVISIBLE
            binding.smartCode.visibility = View.INVISIBLE
            binding.qrLoading.visibility = View.VISIBLE
            binding.ttlCountdown.isVisible = false
            binding.pollingStatus.isVisible = false
            binding.errorText.isVisible = false
            binding.regenerateButton.isVisible = false

            val charset = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
            val code = (1..6).map { charset[kotlin.random.Random.nextInt(charset.length)] }.joinToString("")
            val qrUrl = "gladix://pair?code=$code"

            lifecycleScope.launch {
                runCatching {
                    val writer = QRCodeWriter()
                    val matrix = writer.encode(qrUrl, BarcodeFormat.QR_CODE, 512, 512)
                    val bmp = Bitmap.createBitmap(512, 512, Bitmap.Config.RGB_565)
                    for (x in 0 until 512) {
                        for (y in 0 until 512) {
                            bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
                        }
                    }
                    binding.qrCode.setImageBitmap(bmp)
                }
                binding.qrLoading.visibility = View.GONE
                binding.qrCode.visibility = View.VISIBLE
                binding.smartCode.text = code
                binding.smartCode.visibility = View.VISIBLE
                binding.pollingStatus.isVisible = true

                countdownJob = launch {
                    for (remaining in 600 downTo 0) {
                        binding.ttlCountdown.text = getString(R.string.tv_pairing_expires_in, remaining)
                        binding.ttlCountdown.isVisible = true
                        delay(1000)
                    }
                    pollingJob?.cancel()
                    binding.pollingStatus.isVisible = false
                    binding.ttlCountdown.text = getString(R.string.tv_pairing_expired)
                    binding.regenerateButton.isVisible = true
                }

                pollingJob = launch {
                    while (isActive) {
                        delay(3000L)
                        val arl = pollWorker(code) ?: continue
                        countdownJob?.cancel()
                        loginViewModel.onSmartLoginComplete(arl)
                        return@launch
                    }
                }
            }
        }

        private suspend fun pollWorker(code: String): String? = withContext(Dispatchers.IO) {
            try {
                val conn = java.net.URL("https://gladix-pairing.schwertley.workers.dev/pair/$code")
                    .openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().readText()
                    org.json.JSONObject(body).optString("arl", "").takeIf { it.isNotEmpty() }
                } else null
            } catch (e: Exception) { null }
        }

        override fun onDestroyView() {
            super.onDestroyView()
            pollingJob?.cancel()
            countdownJob?.cancel()
        }
    }
}