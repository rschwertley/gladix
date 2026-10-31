package dev.brahmkshatriya.echo.ui.common

import androidx.core.view.updatePaddingRelative
import dev.brahmkshatriya.echo.utils.ui.UiUtils.dpToPx
import dev.brahmkshatriya.echo.ui.common.UiViewModel.Companion.applyAppBarRailInset
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.databinding.FragmentExceptionBinding
import dev.brahmkshatriya.echo.ui.common.ExceptionUtils.getPasteLink
import dev.brahmkshatriya.echo.ui.common.SnackBarHandler.Companion.createSnack
import dev.brahmkshatriya.echo.ui.common.UiViewModel.Companion.applyBackPressCallback
import dev.brahmkshatriya.echo.ui.common.UiViewModel.Companion.applyContentInsets
import dev.brahmkshatriya.echo.ui.common.UiViewModel.Companion.applyFabInsets
import dev.brahmkshatriya.echo.ui.common.UiViewModel.Companion.applyInsets
import dev.brahmkshatriya.echo.utils.ContextUtils.copyToClipboard
import dev.brahmkshatriya.echo.utils.Serializer.getSerialized
import dev.brahmkshatriya.echo.utils.Serializer.putSerialized
import dev.brahmkshatriya.echo.utils.ui.AnimationUtils.setupTransition
import dev.brahmkshatriya.echo.utils.ui.AutoClearedValue.Companion.autoCleared
import dev.brahmkshatriya.echo.utils.ui.UiUtils.configureAppBar
import kotlinx.coroutines.launch

class ExceptionFragment : Fragment() {
    companion object {
        fun getBundle(data: ExceptionUtils.Data) = Bundle().apply {
            putSerialized("data", data)
        }
    }

    private val data by lazy {
        requireArguments().getSerialized<ExceptionUtils.Data>("data")!!.getOrThrow()
    }

    private var binding by autoCleared<FragmentExceptionBinding>()
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        binding = FragmentExceptionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        setupTransition(view)
        applyInsets {
            binding.nestedScrollView.applyContentInsets(it)
            binding.fabContainer.applyFabInsets(it, systemInsets.value)
            // TV-layout summary (null on the phone layout): clear the rail like the trace below it. 24dp is the
            // layout's own horizontal padding.
            val pad = 24.dpToPx(requireContext())
            binding.exceptionSummary?.updatePaddingRelative(start = pad + it.start, end = pad + it.end)
        }
        // The header (toolbar: back + title) cleared nothing on the left, so on TV it sat under the rail. Same
        // helper as the other configureAppBar-only headers; isRail-gated, so phone portrait is untouched and
        // phone landscape gets the same parity its other headers have.
        applyAppBarRailInset(binding.appBarLayout)
        applyBackPressCallback()
        binding.appBarLayout.configureAppBar { offset ->
            binding.toolbarOutline.alpha = offset
            binding.exceptionIconContainer.alpha = 1 - offset
        }

        binding.exceptionMessage.setNavigationOnClickListener {
            parentFragmentManager.popBackStack()
        }

        binding.exceptionMessage.title = data.title
        binding.exceptionDetails.text = data.trace
        // TV ONLY: exceptionSummary exists only in layout-land-television/fragment_exception.xml, so it is
        // null on the phone. Shows the top exception's line and every "Caused by:" line (for a Deezer
        // refusal, DeezerGatewayException's toString with method, lang and error=...) ahead of the frames.
        // Keyed on the section markers ExceptionUtils.getStackTrace writes; if those change, this goes blank
        // rather than wrong.
        binding.exceptionSummary?.text = data.trace.lineSequence()
            .dropWhile { it != "---Stack Trace---" }.drop(1)
            .takeWhile { it != "---Details---" }
            .filterIndexed { index, line -> index == 0 || line.startsWith("Caused by: ") }
            .joinToString("\n")
        binding.fabCopy.setOnClickListener {
            copyException()
        }

        requireActivity().window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
    }

    private fun copyException() {
        createSnack(R.string.copying_the_error)
        lifecycleScope.launch {
            val toCopy = getPasteLink(data).getOrElse { data.trace }
            requireContext().copyToClipboard("Error", toCopy)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}