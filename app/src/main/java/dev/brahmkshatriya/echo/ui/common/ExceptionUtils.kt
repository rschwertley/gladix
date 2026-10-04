package dev.brahmkshatriya.echo.ui.common

import android.content.Context
import android.net.ConnectivityManager
import android.view.View
import androidx.fragment.app.FragmentActivity
import dev.brahmkshatriya.echo.MainActivity
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.common.helpers.ContinuationCallback.Companion.await
import dev.brahmkshatriya.echo.common.models.Message
import dev.brahmkshatriya.echo.download.exceptions.DownloadException
import dev.brahmkshatriya.echo.download.exceptions.DownloaderExtensionNotFoundException
import dev.brahmkshatriya.echo.download.tasks.BaseTask.Companion.getTitle
import dev.brahmkshatriya.echo.extensions.db.models.UserEntity
import dev.brahmkshatriya.echo.extensions.exceptions.AppException
import dev.brahmkshatriya.echo.extensions.exceptions.ExtensionLoadException
import dev.brahmkshatriya.echo.extensions.exceptions.ExtensionLoaderException
import dev.brahmkshatriya.echo.extensions.exceptions.ExtensionNotFoundException
import dev.brahmkshatriya.echo.extensions.exceptions.InvalidExtensionListException
import dev.brahmkshatriya.echo.extensions.exceptions.RequiredExtensionsMissingException
import dev.brahmkshatriya.echo.extensions.exceptions.capMessage
import dev.brahmkshatriya.echo.playback.MediaItemUtils.extensionId
import dev.brahmkshatriya.echo.playback.MediaItemUtils.serverIndex
import dev.brahmkshatriya.echo.playback.MediaItemUtils.track
import dev.brahmkshatriya.echo.playback.exceptions.PlayerException
import dev.brahmkshatriya.echo.ui.common.FragmentUtils.openFragment
import dev.brahmkshatriya.echo.ui.extensions.login.LoginFragment
import dev.brahmkshatriya.echo.ui.extensions.login.LoginUserListViewModel
import dev.brahmkshatriya.echo.utils.AppUpdater
import dev.brahmkshatriya.echo.utils.ContextUtils.appVersion
import dev.brahmkshatriya.echo.utils.ContextUtils.observe
import dev.brahmkshatriya.echo.utils.Serializer
import dev.brahmkshatriya.echo.utils.Serializer.rootCause
import dev.brahmkshatriya.echo.utils.Serializer.toJson
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.koin.androidx.viewmodel.ext.android.viewModel

object ExceptionUtils {

    /**
     * Whether this device currently believes it has a network. Used only to word a timeout message -
     * see the timeout arm in [getTitle]. Defaults to TRUE on failure, so an unreadable
     * ConnectivityManager produces the stalling message rather than a false "No Internet".
     */
    private fun Context.hasActiveNetwork() = runCatching {
        getSystemService(ConnectivityManager::class.java)?.activeNetwork != null
    }.getOrDefault(true)

    private fun Context.getTitle(throwable: Throwable): String? = when (throwable) {
        // Class/library LOAD failure — the extension can't be loaded at all (missing/repackaged class,
        // bad dex, missing native lib). Often the APP's fault (e.g. an R8 ABI break repackaging :common),
        // never fixable by "updating". Name the cause + missing class so the real problem is self-evident.
        // MUST precede the LinkageError/ReflectiveOperationException catch-all below (these are subtypes).
        is NoClassDefFoundError, is ClassNotFoundException, is UnsatisfiedLinkError ->
            getString(R.string.extension_failed_to_load_x, "${throwable::class.simpleName}: ${throwable.message}")

        // Method/field-level ABI drift (built against a different :common signature). A compatibility
        // STATE, not an installable update — worded to NOT borrow the update system's "out of date /
        // update available" language (the update checker may correctly report no update exists).
        is LinkageError, is ReflectiveOperationException ->
            getString(R.string.extension_incompatible_version)

        // Kept in lockstep with ErrorCategory.classify (ErrorCategoryTest guards the drift). Connection-
        // level failures only — a mid-stream SocketException is not "no internet".
        is UnknownHostException, is UnresolvedAddressException,
        is ConnectException, is NoRouteToHostException -> getString(R.string.no_internet)

        // A stalled connection, not a missing one: the socket opened and then went quiet. Paired with
        // ErrorCategory.classify's timeout arm (ErrorCategoryTest guards the drift) and with
        // PlayerEventListener's isStreamStall hold branch, which is what the user is actually seeing when
        // this string appears - playback paused on the SAME track, nothing skipped.
        // ⚠⚠ THE CONNECTIVITY READ IS THE POINT, NOT A REFINEMENT. A network that drops
        // MID-SOCKET surfaces as a timeout rather than a ConnectException, so without this check a real
        // outage would be described as "stalling" while the phone showed no bars. The hold BEHAVIOUR is
        // identical either way (hold, no skip, no breaker) - only the wording differs, and the wording is
        // the whole value of the message.
        // ⚠️ IT LIVES HERE RATHER THAN AT THE THROW SITE so one decision covers every caller of
        // getTitle, and so PlayerEventListener does not have to fabricate an exception to steer a string.
        // ⚠️ activeNetwork, NOT a validated-capability check: the question is "does this device
        // think it has a network", which is the same read the buffering watchdog's net= probe makes.
        // runCatching because some OEM builds reject the ConnectivityManager binder call from the system
        // server (see App's network-callback guard) and a message must never throw.
        is SocketTimeoutException, is TimeoutCancellationException ->
            if (hasActiveNetwork()) getString(R.string.playback_stream_stalling)
            else getString(R.string.no_internet)

        // Deferred class-load/instantiate failure carrying extension identity (see ExtensionParser).
        // Root-cause unwrapped so a constructor failure wrapped in InvocationTargetException shows the
        // real error, not the reflection wrapper.
        is ExtensionLoadException -> getString(
            R.string.extension_failed_to_load_x,
            throwable.cause.rootCause.let { "${throwable.name} — ${it::class.simpleName}: ${it.message}" }
        )

        is ExtensionLoaderException ->
            getString(R.string.error_loading_extension_from_x, throwable.clazz)

        // A null id is a DIFFERENT CONDITION, not a missing argument - see the note on the
        // exception. "Extension null not found" read as a lookup failure when it is a
        // missing-input failure.
        is ExtensionNotFoundException -> throwable.id
            ?.let { getString(R.string.extension_x_not_found, it) }
            ?: getString(R.string.item_missing_extension)
        is RequiredExtensionsMissingException -> getString(
            R.string.required_extensions_missing_x,
            throwable.required.joinToString(", ")
        )

        is AppException -> when (throwable) {
            is AppException.Unauthorized ->
                getString(R.string.account_session_expired_in_x, throwable.extension.name)

            is AppException.LoginRequired ->
                getString(R.string.x_login_required, throwable.extension.name)

            is AppException.NotSupported -> getString(
                R.string.x_is_not_supported_in_x,
                throwable.operation,
                throwable.extension.name
            )

            is AppException.Other -> "${throwable.extension.name}: ${getFinalTitle(throwable.cause)}"
        }

        is InvalidExtensionListException -> getString(R.string.invalid_extension_list)
        // Named = an extension update; unnamed = the app's own. Falling back to the old generic
        // string keeps every un-tagged call site (AddViewModel, the app path) rendering as before.
        is AppUpdater.UpdateException -> throwable.name
            ?.let { getString(R.string.error_updating_x, it) }
            ?: getString(R.string.error_updating_extension)

        is PlayerException -> "${throwable.mediaItem?.track?.title}: ${getFinalTitle(throwable.cause)}"

        is DownloadException -> {
            val title = getTitle(throwable.type, throwable.downloadEntity.track.getOrNull()?.title ?: "???")
            "${title}: ${getFinalTitle(throwable.cause)}"
        }

        is DownloaderExtensionNotFoundException -> getString(R.string.no_download_extension)

        else -> null
    }

    private fun getDetails(throwable: Throwable): String? = when (throwable) {
        is ExtensionLoaderException -> """
            Class: ${throwable.clazz}
            Source: ${throwable.source}
        """.trimIndent()

        is ExtensionLoadException -> """
            Extension: ${throwable.name}
            ID: ${throwable.id}
            Class: ${throwable.className}
        """.trimIndent()

        is ExtensionNotFoundException -> "Extension ID: ${throwable.id}"
        is RequiredExtensionsMissingException ->
            "Required Extension: ${throwable.required.joinToString(", ")}"

        is AppException -> """
            Type: ${throwable.extension.type}
            ID: ${throwable.extension.id}
            Extension: ${throwable.extension.name}(${throwable.extension.version})
            ${if (throwable is AppException.NotSupported) "Operation: ${throwable.operation}" else ""}
        """.trimIndent()

        is InvalidExtensionListException -> "Link: ${throwable.link}"

        is PlayerException -> throwable.mediaItem?.let {
            """
            Extension ID: ${it.extensionId}
            Track: ${it.track.toJson()}
            Stream: ${it.run { track.servers.getOrNull(serverIndex)?.toJson() }}
        """.trimIndent()
        }

        is DownloadException -> """
            Type: ${throwable.type}
            Track: ${throwable.downloadEntity.toJson()}
        """.trimIndent()

        is Serializer.DecodingException -> "JSON: ${throwable.json}"

        else -> null
    }

    fun Context.getFinalTitle(throwable: Throwable): String? =
        getTitle(throwable) ?: throwable.cause?.let { getFinalTitle(it) } ?: throwable.message


    private fun getFinalDetails(throwable: Throwable): String = buildString {
        getDetails(throwable)?.let { appendLine(it) }
        throwable.cause?.let { append(getFinalDetails(it)) }
    }

    // ~16K chars. A String parcels at ~2 bytes/char (UTF-16), so this is ~32KB in the instance-state Bundle —
    // far under the ~1MB TransactionTooLarge budget the un-capped trace was blowing on onSaveInstanceState
    // (a DecodingException inlines the entire failed-to-decode response via getDetails).
    private const val TRACE_CHAR_CAP = 16_384

    private fun getStackTrace(throwable: Throwable): String {
        // Order matters for the head-cap below: FRAMES FIRST (bounded, and their "Caused by: …: Response
        // code: 401" lines carry the exception types + HTTP responseCode — the key discriminators), then the
        // UNBOUNDED raw payload LAST (getFinalDetails inlines full toJson() / DecodingException.json, which is
        // what reaches hundreds of KB). So a head-cap keeps the diagnostics and truncates only the raw tail.
        val full = buildString {
            appendLine("Version: ${appVersion()}")
            appendLine("---Stack Trace---")
            appendLine(throwable.stackTraceToString())
            appendLine("---Details---")
            append(getFinalDetails(throwable))
        }
        if (full.length <= TRACE_CHAR_CAP) return full
        // Cap counts CHARS, and the marker states what was cut so a truncated trace is never mistaken for a
        // full one (~2 bytes/char → the cut size in KB).
        val cutKb = (full.length - TRACE_CHAR_CAP) * 2 / 1024
        return full.take(TRACE_CHAR_CAP) +
            "\n…[trace truncated: showing first $TRACE_CHAR_CAP of ${full.length} chars, ~${cutKb}KB cut]"
    }

    @Serializable
    data class Data(val title: String, val trace: String)

    fun Throwable.toData(context: Context) = run {
        // Same cap, same reasoning as getMessage above. Safe here because the FULL text is still
        // reachable: Data carries the trace alongside the title, and getStackTrace appends
        // getFinalDetails, which inlines the raw payload (bounded separately by TRACE_CHAR_CAP).
        val title = (context.getFinalTitle(this) ?: context.getString(
            R.string.error_x,
            message ?: this::class.run { simpleName ?: java.name }
        )).capMessage()
        Data(title, getStackTrace(this))
    }


    // ⚠⚠ THE CAP IS APPLIED HERE, AND DELIBERATELY NOT IN getTitle/getFinalTitle. Those two
    // are under a standing constraint to stay BYTE-FOR-BYTE UNTOUCHED (the phone snackbar path), which
    // the classify() work has respected since. Capping the RESULT at the construction site honours it
    // and is also strictly better placed: getFinalTitle WALKS THE CAUSE CHAIN to find the real reason,
    // and a cap inside that walk would risk changing which cause is chosen rather than only how much
    // of it is shown.
    // ⚠️ AND CAPPING getFinalTitle WOULD NOT HAVE FIXED THE WORSE HALF ANYWAY, which is the
    // reason this placement is not a compromise. The Crashlytics issue TITLE comes from the exception's
    // own `message` - AppException.Other.message, built by deepestMessage() - and never passes through
    // this file at all. That path is capped at its own source; see AppException.capMessage.
    // Truncating from the END keeps the attribution: getTitle renders Other as
    // "<extension name>: <cause>", so the name survives and only the payload is cut.
    fun FragmentActivity.getMessage(throwable: Throwable, view: View?): Message {
        val title = (getFinalTitle(throwable) ?: getString(
            R.string.error_x,
            throwable.message ?: throwable::class.run { simpleName ?: java.name }
        )).capMessage()
        val root = throwable.rootCause
        return Message(
            message = title,
            when (root) {
                is AppException.Unauthorized ->
                    Message.Action(getString(R.string.logout_and_login)) {
                        runCatching { openLoginException(root, view) }
                    }

                is AppException.LoginRequired -> Message.Action(getString(R.string.login)) {
                    runCatching { openLoginException(root, view) }
                }

                else -> Message.Action(getString(R.string.view)) {
                    runCatching { openException(Data(title, getStackTrace(throwable)), view) }
                }
            }
        )
    }

    private fun FragmentActivity.openException(data: Data, view: View? = null) {
        openFragment<ExceptionFragment>(view, ExceptionFragment.getBundle(data))
    }

    fun FragmentActivity.openLoginException(
        it: AppException.LoginRequired, view: View? = null
    ) {
        if (it is AppException.Unauthorized) {
            val model by viewModel<LoginUserListViewModel>()
            model.logout(UserEntity(it.extension.type, it.extension.id, it.userId, ""))
        }
        openFragment<LoginFragment>(view, LoginFragment.getBundle(it))
    }


    fun MainActivity.setupExceptionHandler(handler: SnackBarHandler) {
        observe(handler.app.throwFlow) { throwable ->
            val message = getMessage(throwable, null)
            handler.create(message)
        }
    }

    private val client = OkHttpClient()

    /**
     * Uploads [data].trace to paste.rs and returns the paste URL, or a FAILURE the caller can fall
     * back from. ExceptionFragment.copyException does `getPasteLink(data).getOrElse { data.trace }`,
     * so a failure here means the user copies the plain error text instead - which is the point.
     *
     * ⚠⚠ THE STATUS CHECK IS THE WHOLE FIX. OkHttp DOES NOT THROW ON 4xx/5xx, so without it
     * runCatching SUCCEEDED on an error response and body.string() returned paste.rs's HTML ERROR
     * PAGE, which was then handed back as if it were a paste URL and copied to the clipboard.
     * FIELD REPORT (2026-10-02): a user copying a playback error got an HTML "400: Bad Request" page
     * footed "Rocket" - paste.rs is a Rocket app - pasted where a link should have been. The 400 had
     * nothing to do with the error being reported; it arrived later, from this call.
     * ⚠️ SAME DEFECT CLASS AS THE AppUpdater GITHUB PATH, which checks response.code before
     * deserialising for exactly this reason. If a response can be an error page, the code must be
     * read before the body is believed.
     * ⚠️ isSuccessful RATHER THAN A CODE LIST, DELIBERATELY: there is nothing to say about
     * WHICH failure, because every outcome is the same fall back to the raw trace. The code goes in
     * the message only so a report can name it.
     */
    suspend fun getPasteLink(data: Data) = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://paste.rs")
            .post(data.trace.toRequestBody())
            .build()
        runCatching {
            client.newCall(request).await().use { response ->
                if (!response.isSuccessful)
                    throw IOException("paste.rs returned HTTP ${response.code}")
                response.body.string()
            }
        }
    }
}