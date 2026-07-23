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
import kotlin.math.roundToInt

/**
 * Rounds the cover's corners AFTER scaling it DOWN to FIT within the target (like fitCenter) — unlike
 * Coil's [coil3.transform.RoundedCornersTransformation], which scales to FILL and center-crops the
 * overflow before rounding (cropping album text / faces on non-square covers). The output bitmap is the
 * FITTED image size (no letterbox), so the rounding lands on the real image edges and the whole cover
 * stays visible for any aspect ratio. The radius is applied at the fitted display size, so it reads as
 * the intended dp regardless of source resolution.
 */
class FitRoundedCornersTransformation(private val radiusPx: Float) : Transformation() {
    override val cacheKey = "${FitRoundedCornersTransformation::class.simpleName}-$radiusPx"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        // Target = the ImageView's resolved bounds; fall back to the source if a dimension is undefined.
        val targetW = (size.width as? Dimension.Pixels)?.px ?: input.width
        val targetH = (size.height as? Dimension.Pixels)?.px ?: input.height
        // FIT: scale down to fit within the target, never upscale beyond the source.
        val scale = minOf(
            targetW / input.width.toFloat(),
            targetH / input.height.toFloat(),
            1f
        )
        val outW = (input.width * scale).roundToInt().coerceAtLeast(1)
        val outH = (input.height * scale).roundToInt().coerceAtLeast(1)
        // Never let the radius exceed half the shorter edge (degenerate on tiny thumbnails).
        val radius = radiusPx.coerceAtMost(minOf(outW, outH) / 2f)
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
