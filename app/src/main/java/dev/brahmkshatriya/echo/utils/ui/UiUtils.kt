package dev.brahmkshatriya.echo.utils.ui

import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration.ORIENTATION_LANDSCAPE
import android.content.res.Configuration.UI_MODE_NIGHT_MASK
import android.content.res.Configuration.UI_MODE_NIGHT_NO
import android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
import android.text.TextUtils
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import dev.brahmkshatriya.echo.ui.common.UiViewModel.Companion.BACKGROUND_GRADIENT
import dev.brahmkshatriya.echo.utils.ContextUtils.getSettings
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToLong

object UiUtils {

    fun Activity.hideSystemUi(hide: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        if (hide) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    fun AppBarLayout.configureAppBar(block: (offset: Float) -> Unit) {
        val settings = context.getSettings()
        val isGradient = settings.getBoolean(BACKGROUND_GRADIENT, true)
        val extra = if (isGradient) -191 else 0
        addOnOffsetChangedListener { _, verticalOffset ->
            val offset = -verticalOffset / totalScrollRange.toFloat()
            background?.mutate()?.alpha = max(0, extra + (offset * 255).toInt())
            runCatching { block(offset) }
        }
    }

    fun Context.isRTL() =
        resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL

    fun Context.isLandscape() =
        resources.configuration.orientation == ORIENTATION_LANDSCAPE

    fun Context.isNightMode() =
        resources.configuration.uiMode and UI_MODE_NIGHT_MASK != UI_MODE_NIGHT_NO

    /**
     * Is there anything on this device that can handle a document picker?
     *
     * ⚠⚠ CREATE AND OPEN ARE CHECKED INDEPENDENTLY AND MUST STAY THAT WAY. They are separate
     * framework actions, and a device can resolve one without the other; collapsing them into one flag
     * would hide Import on a device that can only Open, or offer Export on one that cannot Create.
     *
     * ⚠⚠ THESE READS DEPEND ON THE <queries> ENTRIES IN AndroidManifest.xml AND ARE WORSE
     * THAN NOTHING WITHOUT THEM. Under API 30+ package visibility, resolveActivity returns null for an
     * unqueried action even when a handler exists - so gating a preference on this WITHOUT the manifest
     * entries hides Export/Import on every modern device. Same coupling the equalizer check has, and
     * the same reason its <intent> entry exists. If you ever remove those entries, remove these gates.
     *
     * ⚠️ CAPABILITY-GATED, NOT isTv()-GATED, DELIBERATELY. Android TV usually ships no
     * DocumentsUI, which is why this matters there most - but a TV box that DOES have a file manager
     * keeps the feature, and a phone that somehow lacks one loses it. The device answers the question;
     * the form factor only correlates with the answer.
     *
     * ⚠️ CACHED PER PROCESS, like hasSystemEqualizer. A picker cannot appear or vanish without
     * a package install, and the tap-time catch is the backstop for the window where it does.
     */
    private var canCreateDocument: Boolean? = null
    private var canOpenDocument: Boolean? = null

    fun Context.hasCreateDocument() = canCreateDocument ?: resolves(
        Intent(Intent.ACTION_CREATE_DOCUMENT)
    ).also { canCreateDocument = it }

    fun Context.hasOpenDocument() = canOpenDocument ?: resolves(
        Intent(Intent.ACTION_OPEN_DOCUMENT)
    ).also { canOpenDocument = it }

    // CATEGORY_OPENABLE + a concrete type, because that is what the contracts actually launch -
    // resolving a bare action would answer a question nobody asks.
    private fun Context.resolves(intent: Intent) = intent
        .addCategory(Intent.CATEGORY_OPENABLE)
        .setType("application/json")
        .resolveActivity(packageManager) != null

    // Google TV reports UI_MODE_TYPE_TELEVISION but NOT FEATURE_LEANBACK, so the UiModeManager check must
    // come first (and stay) — FEATURE_LEANBACK alone would miss Google TV boxes.
    fun Context.isTv() =
        (getSystemService(Context.UI_MODE_SERVICE) as UiModeManager).currentModeType ==
            UI_MODE_TYPE_TELEVISION ||
            packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

    fun Int.dpToPx(context: Context) = (this * context.resources.displayMetrics.density).toInt()

    fun Context.resolveStyledDimension(attr: Int): Int {
        val typed = theme.obtainStyledAttributes(intArrayOf(attr))
        val itemWidth = typed.getDimensionPixelSize(typed.getIndex(0), 0)
        return itemWidth
    }

    fun Long.toTimeString(): String {
        val seconds = (this.toFloat() / 1000).roundToLong()
        val minutes = seconds / 60
        val hours = minutes / 60
        return if (hours > 0) {
            String.format(Locale.ENGLISH, "%02d:%02d:%02d", hours, minutes % 60, seconds % 60)
        } else {
            String.format(Locale.ENGLISH, "%02d:%02d", minutes, seconds % 60)
        }
    }

    fun Long.toCompactDurationString(): String {
        val totalSeconds = (this.toFloat() / 1000).roundToLong()
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        return when {
            hours > 0 && minutes > 0 -> "${hours}h ${minutes}min"
            hours > 0 -> "${hours}h"
            else -> "${minutes}min"
        }
    }

    fun TextView.marquee() {
        isSelected = true
        ellipsize = TextUtils.TruncateAt.MARQUEE
        maxLines = 1
        marqueeRepeatLimit = -1
        setHorizontallyScrolling(true)
    }

    fun BottomSheetDialogFragment.configureBottomBar(bar: View) {
        val view = requireView()
        val dialog = requireDialog() as BottomSheetDialog
        val behavior = dialog.behavior
        val barHeight = 72.dpToPx(requireContext())
        var peek = 0
        var toScroll = 0
        var offset = 0f

        val callback = object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(p0: View, p1: Int) {}
            override fun onSlide(p0: View, p1: Float) {
                offset = p1.coerceAtLeast(0f)
                bar.y = peek + toScroll * offset
            }
        }
        view.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val screen = v.height - barHeight
            peek = (behavior.peekHeight - barHeight).coerceAtMost(screen)
            toScroll = screen - peek
            bar.y = peek + toScroll * offset
        }
        behavior.addBottomSheetCallback(callback)
    }
}