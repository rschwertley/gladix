package dev.brahmkshatriya.echo.ui.common

import dev.brahmkshatriya.echo.extensions.exceptions.AppException
import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException

/**
 * Android-Auto-facing error classification, consumed only by the AA error mapper (Lever B, wired in
 * PlayerService -> ShufflePlayer.getPlayerError()).
 *
 * This is deliberately a SEPARATE function from [ExceptionUtils]'s `getTitle`/`getFinalTitle`, which
 * feed the phone snackbar and must stay byte-for-byte unchanged. It mirrors ONLY the network and
 * login/auth type-matches of `getTitle` (ExceptionUtils.kt: line 46 `UnknownHostException`/
 * `UnresolvedAddressException`, and lines 58/61 the `AppException.LoginRequired` family) so the AA
 * head-unit message and the phone snackbar always classify the same error into the same category and
 * never disagree.
 *
 * It CHAIN-WALKS the cause chain (like `getFinalTitle` does), NOT a single `rootCause`, so it resolves
 * both real wrapping shapes correctly:
 *   - `AppException.Other(cause = UnknownHostException)` -> not network/login itself, walks into
 *     `.cause` -> [ErrorCategory.Network]  (the wrapped DNS chain:
 *      ExoPlaybackException -> IOException -> AppException.Other -> UnknownHostException).
 *   - a bare `AppException.LoginRequired` (which sets no cause, so `rootCause` would BE it, not a
 *     `ClientException`) -> matched at its own depth -> [ErrorCategory.LoginOrAuth].
 * A single-`rootCause` check would misclassify the login case; the walk is what makes both work.
 *
 * Known, intentional divergence from `getFinalTitle`: `getFinalTitle` STOPS at the first node
 * `getTitle` recognizes, so a getTitle-terminal type (e.g. `LinkageError`) wrapping a network cause
 * would resolve "generic" there, whereas this walk would continue and report Network. That shape does
 * not occur in practice (those types never wrap a network/login cause) and is not in the drift test's
 * corpus; keeping the walk minimal (only the two AA-relevant matches) is worth that theoretical edge.
 *
 * Kept in sync with `getTitle` by `ErrorCategoryTest`. If `getTitle`'s network/login conditions ever
 * change, update this function and that test together.
 */
enum class ErrorCategory { Network, LoginOrAuth, Generic }

tailrec fun classify(throwable: Throwable?): ErrorCategory = when (throwable) {
    null -> ErrorCategory.Generic
    is UnknownHostException, is UnresolvedAddressException -> ErrorCategory.Network
    is AppException.LoginRequired -> ErrorCategory.LoginOrAuth // Unauthorized is a LoginRequired subclass
    else -> classify(throwable.cause)
}
