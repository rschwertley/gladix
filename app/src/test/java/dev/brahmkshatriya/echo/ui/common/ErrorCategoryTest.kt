package dev.brahmkshatriya.echo.ui.common

import dev.brahmkshatriya.echo.common.models.ExtensionType
import dev.brahmkshatriya.echo.common.models.ImportType
import dev.brahmkshatriya.echo.common.models.Metadata
import dev.brahmkshatriya.echo.extensions.exceptions.AppException
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException

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
