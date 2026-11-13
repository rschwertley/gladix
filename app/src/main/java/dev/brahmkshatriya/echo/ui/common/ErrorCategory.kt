package dev.brahmkshatriya.echo.ui.common

import dev.brahmkshatriya.echo.extensions.exceptions.AppException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlinx.coroutines.TimeoutCancellationException

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
    // ConnectException / NoRouteToHostException added 2026-08-19: build 1037 showed ECONNREFUSED
    // (ConnectException -> ErrnoException) classifying as Generic, so roughly half of real network
    // failures produced a generic AA tile and a generic snackbar. Deliberately NOT all SocketException:
    // a mid-stream connection reset is a per-track transient, not "no internet", and must stay Generic
    // so PlayerEventListener's retry-then-skip branch keeps its meaning. getTitle carries the same two
    // additions — these must move together (see ErrorCategoryTest).
    is UnknownHostException, is UnresolvedAddressException,
    is ConnectException, is NoRouteToHostException -> ErrorCategory.Network
    // Timeouts added 2026-10-03, and they bring this function into line with a classifier that already
    // said so: PlayerEventListener.skipFamily has grouped SocketTimeoutException and
    // TimeoutCancellationException under Network since it was written, while THIS function resolved them
    // to Generic - so an Android Auto stall showed "Can't play this track" for a connection problem.
    // classify() and skipFamily were the two that disagreed; getTitle carries the matching arm.
    // ⚠⚠ THIS IS BROADER THAN THE DEFECT THAT PROMPTED IT, DELIBERATELY AND WITH SIGN-OFF. The
    // trigger was Deezer's getContentLength HEAD timing out at 10s (okhttp's readTimeout owns that 10s
    // as of 2026-10-03; see Utils.getContentLength), but the arm covers EVERY socket and
    // coroutine timeout on every extension and every path. A timeout is a connectivity symptom to a user
    // whatever produced it, and splitting it per-source would mean a fourth ErrorCategory member - which
    // the note above makes expensive (enum + mapAaError + getTitle + this test, in lockstep).
    // ⚠️ STILL NOT all SocketException: the exclusion above survives untouched.
    // SocketTimeoutException extends InterruptedIOException, NOT SocketException, so adding it here does
    // not widen that line - a mid-stream connection RESET stays Generic and keeps its retry-then-skip
    // meaning in PlayerEventListener.
    // ⚠️ getTitle's ARM IS CONDITIONAL AND THIS ONE IS NOT, and that asymmetry is intended
    // rather than drift: getTitle has a Context, so it can read live connectivity and say "No Internet"
    // when the network actually dropped mid-socket. classify has no Context - and both of its possible
    // answers are Network anyway, so there is nothing to condition on.
    is SocketTimeoutException, is TimeoutCancellationException -> ErrorCategory.Network
    is AppException.LoginRequired -> ErrorCategory.LoginOrAuth // Unauthorized is a LoginRequired subclass
    // [2026-11-13] A TLS refusal the NETWORK caused (Crashlytics 37e6ec83; see [isRedirectedTls]): Network, so the AA tile and the
    // snackbar both speak of the connection. getTitle carries the matching arm (network_redirecting), ErrorCategoryTest the case.
    else -> if (isRedirectedTls(throwable)) ErrorCategory.Network else classify(throwable.cause)
}

/**
 * [2026-11-13] THE CONNECTION WAS REFUSED BECAUSE THE NETWORK SENT IT SOMEWHERE ELSE (Crashlytics 37e6ec83, 1178: deezer.com
 * answered with a certificate for CN=instaposts.halahala.ru, a DNS block or hijack, a VPN or proxy, or a captive portal; the
 * user's decision). The TLS check did its job and is not changed: this only names what it refused.
 * - SSLPeerUnverifiedException: a valid certificate for a DIFFERENT host (OkHttp's hostname check).
 * - SSLHandshakeException only with a CertificateException or CertPathValidatorException in its chain: a certificate nothing
 *   on the device trusts (a portal's or an interceptor's). A handshake that failed for another reason (a protocol mismatch, a
 *   reset mid-handshake) is not this and stays as it was.
 * One node at a time, so classify's walk keeps its first-match order. Read by classify, ExceptionUtils.getTitle and App's
 * report filter ([hasRedirectedTls]), which must agree.
 */
fun isRedirectedTls(node: Throwable): Boolean = node is SSLPeerUnverifiedException ||
    (node is SSLHandshakeException && generateSequence(node.cause) { it.cause }.take(MAX_TLS_CHAIN)
        .any { it is CertificateException || it is CertPathValidatorException })

/** [2026-11-13] [isRedirectedTls] anywhere in this chain: App's throwFlow collector does not report these. */
fun Throwable.hasRedirectedTls(): Boolean = generateSequence(this) { it.cause }.take(MAX_TLS_CHAIN).any(::isRedirectedTls)

private const val MAX_TLS_CHAIN = 8
