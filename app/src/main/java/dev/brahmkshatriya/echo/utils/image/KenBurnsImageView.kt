package dev.brahmkshatriya.echo.utils.image

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Matrix
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.animation.LinearInterpolator
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.view.doOnLayout
import kotlin.math.max
import kotlin.random.Random

class KenBurnsImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private data class Crop(val zoom: Float, val panX: Float, val panY: Float)

    init {
        scaleType = ScaleType.MATRIX
    }

    private var animator: ValueAnimator? = null
    private var startCrop = Crop(1.1f, 0f, 0f)
    private var endCrop = Crop(1.1f, 0f, 0f)
    private var currentCrop: Crop? = null
    private var pendingStart = false

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        // Re-apply current matrix so the new drawable appears at the correct position.
        // Falls back to center-crop when animation hasn't started yet.
        currentCrop?.let { applyMatrix(it) } ?: applyCenterCrop()
        if (pendingStart && drawable != null) {
            pendingStart = false
            start()
        }
    }

    fun start() {
        if (drawable == null) {
            pendingStart = true
            return
        }
        if (width == 0 || height == 0) {
            doOnLayout { start() }
            return
        }
        pendingStart = false
        stop()
        startCrop = randomCrop()
        endCrop = randomCrop()
        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 10_000L
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            addUpdateListener { va ->
                val crop = lerp(startCrop, endCrop, va.animatedFraction)
                currentCrop = crop
                applyMatrix(crop)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationRepeat(animation: Animator) {
                    // Seamless loop: next start = current end, pick new random end.
                    startCrop = endCrop
                    endCrop = randomCrop()
                }
            })
        }
        animator = anim
        anim.start()
    }

    fun pause() {
        animator?.pause()
    }

    fun resume() {
        val anim = animator
        if (anim == null) start() else anim.resume()
    }

    fun stop() {
        pendingStart = false
        animator?.cancel()
        animator = null
        currentCrop = null
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stop()
    }

    private fun randomCrop(): Crop {
        val dw = (drawable?.intrinsicWidth?.toFloat())?.takeIf { it > 0 } ?: width.toFloat()
        val dh = (drawable?.intrinsicHeight?.toFloat())?.takeIf { it > 0 } ?: height.toFloat()
        val vw = width.toFloat()
        val vh = height.toFloat()
        val zoom = 1.1f + Random.nextFloat() * 0.1f  // 10–20% zoom
        val baseScale = max(vw / dw.coerceAtLeast(1f), vh / dh.coerceAtLeast(1f))
        val totalScale = baseScale * zoom
        val maxPanX = (dw * totalScale - vw).coerceAtLeast(0f)
        val maxPanY = (dh * totalScale - vh).coerceAtLeast(0f)
        return Crop(
            zoom = zoom,
            panX = Random.nextFloat() * maxPanX,
            panY = Random.nextFloat() * maxPanY
        )
    }

    private fun lerp(a: Crop, b: Crop, frac: Float) = Crop(
        zoom = a.zoom + (b.zoom - a.zoom) * frac,
        panX = a.panX + (b.panX - a.panX) * frac,
        panY = a.panY + (b.panY - a.panY) * frac
    )

    private fun applyCenterCrop() {
        val d = drawable ?: return
        val dw = d.intrinsicWidth.toFloat()
        val dh = d.intrinsicHeight.toFloat()
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (dw <= 0 || dh <= 0 || vw <= 0 || vh <= 0) return
        val s = max(vw / dw, vh / dh)
        val m = Matrix()
        m.setScale(s, s)
        m.postTranslate((vw - dw * s) / 2f, (vh - dh * s) / 2f)
        imageMatrix = m
    }

    private fun applyMatrix(crop: Crop) {
        val d = drawable ?: return
        val dw = d.intrinsicWidth.toFloat()
        val dh = d.intrinsicHeight.toFloat()
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (dw <= 0 || dh <= 0 || vw <= 0 || vh <= 0) return
        val baseScale = max(vw / dw, vh / dh)
        val totalScale = baseScale * crop.zoom
        val m = Matrix()
        m.setScale(totalScale, totalScale)
        m.postTranslate(-crop.panX, -crop.panY)
        imageMatrix = m
    }
}
