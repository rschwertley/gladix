package dev.brahmkshatriya.echo.ui.extensions.login

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.RelativeSizeSpan
import android.text.style.UpdateAppearance
import dev.brahmkshatriya.echo.common.models.User
import dev.brahmkshatriya.echo.extensions.ExtensionLoader

/**
 * An account's label for the account screens: its name, and its subtitle on a second, dimmed and
 * slightly smaller line when there is one. For extensions with several servers (OpenGonic) the
 * name says which server and the subtitle which user, so accounts with the same username can be
 * told apart.
 * ⚠️ OPENGONIC ONLY (2026-11-11, [showsDetail]). Every other extension gets its name alone, exactly as before, and no
 * "Switched to" message. No built-in sets User.subtitle (Deezer's DeezerApi.makeUser and its renewal, the test extension's
 * onLogin and WebView user; Offline and Unified have no login), but a third-party extension's subtitle could be an
 * email, a plan or an id, and nothing here can know which.
 */
object AccountLabel {
    // The same dimming as the subtitle in Settings → Extensions (item_login_user.xml)
    private const val SUBTITLE_ALPHA = 0.66f
    private const val SUBTITLE_SIZE = 0.85f

    // Whether this extension's accounts show their subtitle and announce a switch.
    fun showsDetail(extensionId: String?) = extensionId == ExtensionLoader.OPENGONIC_ID

    fun User.accountLabel(extensionId: String?): CharSequence {
        if (!showsDetail(extensionId)) return name
        val sub = subtitle?.takeIf { it.isNotBlank() } ?: return name
        val text = SpannableStringBuilder(name).append('\n')
        val start = text.length
        text.append(sub)
        text.setSpan(DimSpan(), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.setSpan(RelativeSizeSpan(SUBTITLE_SIZE), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return text
    }

    /**
     * The same account on one line, as plain text, for the "Switched to" message: "Home Gonic (alice · Gonic)", or the
     * name alone when there is no subtitle. Callers show that message only when [showsDetail].
     */
    fun User.accountLabelInline(): String {
        val sub = subtitle?.takeIf { it.isNotBlank() } ?: return name
        return "$name ($sub)"
    }

    // Dims whatever colour the view draws with, so it follows the button's checked/unchecked colour
    private class DimSpan : CharacterStyle(), UpdateAppearance {
        override fun updateDrawState(tp: TextPaint) {
            tp.alpha = (tp.alpha * SUBTITLE_ALPHA).toInt()
        }
    }
}
