package dev.brahmkshatriya.echo.utils.image

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import androidx.core.graphics.applyCanvas
import androidx.core.graphics.createBitmap
import coil3.size.Dimension
import coil3.size.Size
import coil3.transform.Transformation
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * [2026-11-08] The feed's cover in a square box (MediaViewHolder.applyCover): every cover sits in a fixed square, the image
 * fitted inside it without stretching, the rule being round for artists and a rounded square for everything else.
 *
 * A SQUARE SOURCE PASSES THROUGH UNTOUCHED, so a square cover (Deezer, or anything cropped by SquareCropTransformation, which
 * runs first for crop = true) looks exactly as before: it fills the box and the box's outline rounds it.
 *
 * A non-square source is fitted and its own corners rounded, as FitRoundedCornersTransformation does for the TV player's
 * cover, which is left as it is. One difference: that class never enlarges, and when the ImageView then enlarges a small
 * output to fit the box its corners grow with it. Here the radius is drawn smaller by the same factor, so it reads as
 * [radiusPx] on screen at any source size. The leftover space shows the cover view's own background
 * (rounded_rectangle_cover).
 */
class FitSquareCoverTransformation(private val radiusPx: Float) : Transformation() {
    override val cacheKey = "${FitSquareCoverTransformation::class.simpleName}-$radiusPx"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        if (abs(input.width - input.height) <= 1) return input
        // Target = the ImageView's resolved bounds; fall back to the source if a dimension is undefined.
        val targetW = (size.width as? Dimension.Pixels)?.px ?: input.width
        val targetH = (size.height as? Dimension.Pixels)?.px ?: input.height
        // fit: the scale the image is shown at. scale: the one drawn here, never above the source.
        val fit = minOf(targetW / input.width.toFloat(), targetH / input.height.toFloat())
        val scale = minOf(fit, 1f)
        val outW = (input.width * scale).roundToInt().coerceAtLeast(1)
        val outH = (input.height * scale).roundToInt().coerceAtLeast(1)
        // The view enlarges the output by fit / scale, so the radius is drawn smaller by that factor.
        val radius = (radiusPx * scale / fit).coerceAtMost(minOf(outW, outH) / 2f)
        // ARGB_8888 (createBitmap default) so the corners outside the round-rect stay transparent.
        return createBitmap(outW, outH).applyCanvas {
            val shader = BitmapShader(input, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                if (scale != 1f) setLocalMatrix(Matrix().apply { setScale(scale, scale) })
            }
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader }
            drawRoundRect(0f, 0f, outW.toFloat(), outH.toFloat(), radius, radius, paint)
        }
    }
}
