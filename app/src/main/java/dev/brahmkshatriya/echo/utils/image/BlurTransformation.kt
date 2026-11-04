@file:Suppress("DEPRECATION")

package dev.brahmkshatriya.echo.utils.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicBlur
import androidx.core.graphics.applyCanvas
import androidx.core.graphics.createBitmap
import coil3.size.Size
import coil3.transform.Transformation

class BlurTransformation(
    private val context: Context,
    private val radius: Float = DEFAULT_RADIUS
) : Transformation() {

    override val cacheKey = "${BlurTransformation::class.simpleName}-$radius"

    override suspend fun transform(input: Bitmap, size: Size) =
        input.blurred(context, radius)

    companion object {
        private const val DEFAULT_RADIUS = 20f
        private const val MAX_WIDTH = 512
        fun Bitmap.blurred(
            context: Context,
            radius: Float = DEFAULT_RADIUS
        ): Bitmap {
            require(radius in 0.0..25.0) { "radius must be in [0, 25]." }

            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            val scale = width.toFloat() / MAX_WIDTH
            val scaledHeight = (height / scale).toInt().coerceAtLeast(1)
            // ⚠️ ALWAYS ARGB_8888, NOT THE SOURCE'S CONFIG (2026-11-04). Crashlytics, 1166, browsing iptv_org:
            // RSInvalidStateException "Bad bitmap type: RGBA_F16" at Allocation.createFromBitmap. RenderScript has no
            // F16 element, and this copied the source's config. Forcing 8-bit at decode cannot prevent it: Android
            // decodes a high-precision image to F16 even when ARGB_8888 is requested (SkAndroidCodec
            // .computeOutputColorType, android12-release) and Coil 3.6.3's BitmapFactoryDecoder.configureConfig forces
            // F16 too. Drawing into this bitmap converts it, and the result is a blur, so the lost precision cannot show.
            val output = createBitmap(MAX_WIDTH, scaledHeight, Bitmap.Config.ARGB_8888)
            output.applyCanvas {
                scale(1 / scale, 1 / scale)
                drawBitmap(this@blurred, 0f, 0f, paint)
            }

            var script: RenderScript? = null
            var tmpInt: Allocation? = null
            var tmpOut: Allocation? = null
            var blur: ScriptIntrinsicBlur? = null
            try {
                script = RenderScript.create(context)
                tmpInt = Allocation.createFromBitmap(
                    script,
                    output,
                    Allocation.MipmapControl.MIPMAP_NONE,
                    Allocation.USAGE_SCRIPT
                )
                tmpOut = Allocation.createTyped(script, tmpInt.type)
                blur = ScriptIntrinsicBlur.create(script, Element.U8_4(script))
                blur.setRadius(radius)
                blur.setInput(tmpInt)
                blur.forEach(tmpOut)
                tmpOut.copyTo(output)
            } finally {
                script?.destroy()
                tmpInt?.destroy()
                tmpOut?.destroy()
                blur?.destroy()
            }

            return output
        }
    }
}