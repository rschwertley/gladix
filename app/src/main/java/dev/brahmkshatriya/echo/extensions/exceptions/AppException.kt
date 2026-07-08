package dev.brahmkshatriya.echo.extensions.exceptions

import dev.brahmkshatriya.echo.common.Extension
import dev.brahmkshatriya.echo.common.helpers.ClientException
import dev.brahmkshatriya.echo.common.models.Metadata
import kotlinx.coroutines.TimeoutCancellationException
import kotlin.coroutines.cancellation.CancellationException

// First non-null message walking down the cause chain, falling back to the class name if every
// message is null. Keeps a wrapper exception (null message) from masking its cause's real reason.
private fun Throwable.deepestMessage(): String {
    var t: Throwable? = this
    while (t != null) {
        t.message?.let { return it }
        t = t.cause
    }
    return this::class.simpleName ?: "Unknown"
}

sealed class AppException : Exception() {

    abstract val extension: Metadata

    open class LoginRequired(
        override val extension: Metadata
    ) : AppException()

    class Unauthorized(
        override val extension: Metadata,
        val userId: String
    ) : LoginRequired(extension)

    class NotSupported(
        override val cause: Throwable,
        override val extension: Metadata,
        val operation: String
    ) : AppException() {
        override val message: String
            get() = "$operation is not supported in ${extension.name}"
    }

    class Other(
        override val cause: Throwable,
        override val extension: Metadata
    ) : AppException() {
        // Walk the cause chain for the first non-null message: a wrapper like ExceptionInInitializerError
        // has a null message while the real reason (e.g. "Expected URL scheme...") sits on its cause, so
        // the raw .message (used by crash logging) reads "null error in X" without this. The user-facing
        // snackbar already unwraps via ExceptionUtils.getFinalTitle; this aligns the logged message too.
        override val message: String
            get() = "${cause.deepestMessage()} error in ${extension.name}"
    }

    companion object {
        fun Throwable.toAppException(extension: Extension<*>) = toAppException(extension.metadata)
        fun Throwable.toAppException(extension: Metadata): AppException = when (this) {
            // A withTimeout() timeout is a real, reportable failure, so keep wrapping it. Must be
            // checked before CancellationException (its supertype) below.
            is TimeoutCancellationException -> Other(this, extension)
            // Cooperative cancellation must never be reported as an error — propagate it.
            is CancellationException -> throw this
            is ClientException.Unauthorized -> Unauthorized(extension, userId)
            is ClientException.LoginRequired -> LoginRequired(extension)
            is ClientException.NotSupported -> NotSupported(this, extension, operation)
            else -> Other(this, extension)
        }
    }
}