package dev.brahmkshatriya.echo.ui.common

import dev.brahmkshatriya.echo.common.models.ExtensionType
import dev.brahmkshatriya.echo.common.models.ImportType
import dev.brahmkshatriya.echo.common.models.Metadata
import dev.brahmkshatriya.echo.extensions.exceptions.AppException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Drift guard for [classify] vs the phone-snackbar classifier ([ExceptionUtils]'s getTitle/
 * getFinalTitle). Each case documents the getTitle line it must agree with. If getTitle's network or
 * login conditions change, this test and [classify] must be updated together.
 *
 * SCOPE NOTE (honest limitation): these assertions pin [classify]'s output directly. They do NOT
 * invoke getFinalTitle, because getFinalTitle needs an Android Context (getString) and this module has
 * no Robolectric/instrumented setup. So this catches accidental changes to classify() itself, and the
 * per-case comments assert the intended correspondence; it does not *automatically* detect a change to
 * getTitle. Promote to a Robolectric test that calls getFinalTitle and maps the string back to a
 * category if you want that stronger, fully-automated guarantee.
 */
class ErrorCategoryTest {

    private val meta = Metadata(
        className = "Test",
        path = "test",
        importType = ImportType.BuiltIn,
        type = ExtensionType.MUSIC,
        id = "test",
        name = "Test",
        version = "1.0",
        description = "",
        author = "",
    )

    // getTitle line 46: `is UnknownHostException, is UnresolvedAddressException -> no_internet`.
    @Test
    fun `bare UnknownHost is Network`() {
        assertEquals(ErrorCategory.Network, classify(UnknownHostException("www.deezer.com")))
    }

    // getTitle: ConnectException joins the no_internet line (added 2026-08-19). The REAL Android shape
    // bottoms out in android.system.ErrnoException, which a rootCause check would see instead — the
    // build-1037 defect. Modelled here with a plain cause since ErrnoException needs an Android runtime.
    @Test
    fun `wrapped ConnectException is Network`() {
        val chain = IOException(ConnectException("failed to connect to /1.2.3.4:443"))
        assertEquals(ErrorCategory.Network, classify(chain))
    }

    // Deliberate NON-match: a mid-stream SocketException is a per-track transient, not "no internet".
    // It must stay Generic so PlayerEventListener's retry-then-skip branch keeps its meaning.
    // getTitle's timeout arm: `is SocketTimeoutException, is TimeoutCancellationException ->`
    // playback_stream_stalling, or no_internet when the device reports no active network. classify has
    // no Context and both of getTitle's answers are Network, so it resolves unconditionally.
    @Test
    fun `bare SocketTimeoutException is Network`() {
        assertEquals(ErrorCategory.Network, classify(SocketTimeoutException("timeout")))
    }

    // The real A1 chain, reproduced rather than mocked: RawDataSource.open rewraps the HEAD's
    // TimeoutCancellationException as a SocketTimeoutException carrying it as the CAUSE, and media3's
    // Loader wraps whatever escapes DataSource.open. Every node must resolve to Network at any depth.
    // ⚠️ THE TIMEOUT IS CAUGHT FROM A REAL withTimeout, NOT CONSTRUCTED:
    // TimeoutCancellationException's constructor is INTERNAL to kotlinx-coroutines, so the type can be
    // caught and type-checked but never instantiated from here. Do not "simplify" this to a constructor
    // call - it will not compile.
    @Test
    fun `wrapped stream timeout is Network at every depth`() {
        val timeout = try {
            runBlocking { withTimeout(1) { delay(10_000) } }
            throw AssertionError("withTimeout did not time out")
        } catch (e: TimeoutCancellationException) {
            e
        }
        assertEquals(ErrorCategory.Network, classify(timeout))
        val rewrapped = SocketTimeoutException("Stream open timed out").apply { initCause(timeout) }
        assertEquals(ErrorCategory.Network, classify(rewrapped))
        assertEquals(ErrorCategory.Network, classify(IOException(rewrapped)))
    }

    // DRIFT GUARD, AND THE ONE MOST LIKELY TO BE BROKEN BY A WELL-MEANING WIDENING. The timeout arm
    // added 2026-10-03 must NOT pull plain SocketException with it: SocketTimeoutException extends
    // InterruptedIOException, not SocketException, which is exactly why the two can be separated. A
    // mid-stream connection reset stays Generic and keeps its retry-then-skip meaning.
    @Test
    fun `plain SocketException stays Generic`() {
        assertEquals(ErrorCategory.Generic, classify(SocketException("Connection reset")))
    }

    // getTitle lines 57/61: `is AppException -> is AppException.LoginRequired -> x_login_required`.
    // LoginRequired sets no cause, so a single-rootCause check would miss it — the chain-walk matches
    // it at its own depth.
    @Test
    fun `bare AppException_LoginRequired is LoginOrAuth`() {
        assertEquals(ErrorCategory.LoginOrAuth, classify(AppException.LoginRequired(meta)))
    }

    // The real wrapped-DNS shape: getTitle line 70 `AppException.Other -> "…: ${getFinalTitle(cause)}"`
    // recurses into the cause (UnknownHost -> no_internet). classify walks the same cause -> Network.
    @Test
    fun `AppException_Other wrapping UnknownHost is Network`() {
        val chain = IOException(AppException.Other(UnknownHostException("www.deezer.com"), meta))
        assertEquals(ErrorCategory.Network, classify(chain))
    }

    // getTitle `else -> null` -> getFinalTitle falls through to the message: not network/login.
    @Test
    fun `generic exception is Generic`() {
        assertEquals(ErrorCategory.Generic, classify(IllegalStateException("boom")))
    }
}
