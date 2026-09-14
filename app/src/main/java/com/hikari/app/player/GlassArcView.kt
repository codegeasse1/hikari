package com.hikari.app.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.View

/**
 * The decorative glow that sweeps around the player's glass dialogs: a soft
 * cyan -> violet halo, drawn BEHIND the panel so only the part outside the
 * panel's silhouette is visible.
 *
 * The halo is an ellipse slightly wider than the panel and taller than the
 * stage, swept from -140 deg to +140 deg — i.e. open on the left — so the eye
 * reads one big curve wrapping the panel's right side rather than a closed
 * ring. Its top and bottom tips run off the view (harmless: the shader has
 * faded them to fully transparent before they get there), and the panel itself
 * hides the rest, so what shows is the flourish from the reference design.
 *
 * Glow is painted as a stack of strokes of decreasing width and increasing
 * alpha. A BlurMaskFilter would be one pass, but it is unsupported on a
 * hardware-accelerated canvas, and these dialogs are hardware accelerated.
 */
class GlassArcView(
    context: Context,
    private val startColor: Int,
    private val midColor: Int,
    private val endColor: Int,
) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val oval = RectF()

    /** Horizontal room between this view's edge and the panel it wraps. */
    var insetPx = 0

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val cx = w / 2f
        val cy = h / 2f
        // The right-hand extreme sits just outside the panel's right edge, so
        // the glow reads as a stroke painted along it.
        val rx = w / 2f - insetPx + w * BULGE
        val ry = h * VERTICAL_STRETCH
        oval.set(cx - rx, cy - ry, cx + rx, cy + ry)

        paint.shader = LinearGradient(
            0f, oval.top, 0f, oval.bottom,
            intArrayOf(
                withAlpha(startColor, 0f),
                withAlpha(startColor, 0.62f),
                withAlpha(midColor, 1f),
                withAlpha(endColor, 0.42f),
                withAlpha(endColor, 0f),
            ),
            floatArrayOf(0f, 0.11f, 0.5f, 0.88f, 1f),
            Shader.TileMode.CLAMP,
        )

        val density = resources.displayMetrics.density
        for (i in STROKE_DP.indices) {
            paint.strokeWidth = STROKE_DP[i] * density
            paint.alpha = (ALPHA[i] * 255f).toInt().coerceIn(0, 255)
            canvas.drawArc(oval, START_DEG, SWEEP_DEG, false, paint)
        }
    }

    private fun withAlpha(color: Int, fraction: Float): Int {
        val a = (Color.alpha(color) * fraction).toInt().coerceIn(0, 255)
        return (color and 0x00FFFFFF) or (a shl 24)
    }

    private companion object {
        const val BULGE = 0.03f
        const val VERTICAL_STRETCH = 0.59f
        const val START_DEG = -140f
        const val SWEEP_DEG = 280f
        val STROKE_DP = floatArrayOf(26f, 16f, 9f, 4.5f, 2f, 1f)
        val ALPHA = floatArrayOf(0.03f, 0.05f, 0.09f, 0.16f, 0.34f, 0.78f)
    }
}
