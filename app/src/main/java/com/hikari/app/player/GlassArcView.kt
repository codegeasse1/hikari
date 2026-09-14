package com.hikari.app.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.view.View

/**
 * The neon sweep that wraps the player's glass dialogs: a closed cyan -> violet
 * ring drawn BEHIND the panel, tracing [GlassShape] — the SAME barrel outline
 * the panel itself is cut to (see [CurvedGlassPanel]) — so the light hugs the
 * panel's real edge: out along the bowed sides, round the soft corners, and
 * closed across the top and bottom.
 *
 * It has to be a CLOSED ring. An earlier version swept ~280 deg (open on the
 * left) and the left-hand curve never showed up at all: with every dialog
 * centred on screen there is no single "right side" to decorate — an asymmetric
 * arc just reads as a rendering artefact, and the user reported "where are the
 * curves". Symmetry also survives every panel width, whereas an open sweep only
 * looked right at one aspect ratio.
 *
 * The shape it traces used to be an ellipse, which is only correct for a
 * rectangular panel: against a curved panel an ellipse drifts off the edge (too
 * far out at the corners, cutting the corners at the top and bottom). Following
 * the panel's own outline keeps the gap even all the way round.
 *
 * Glow is painted as a stack of strokes of decreasing width and increasing
 * alpha (a BlurMaskFilter would do it in one pass, but it is unsupported on a
 * hardware-accelerated canvas and these dialogs are hardware accelerated),
 * finished with a near-white core so the ring reads as a light source rather
 * than a pastel outline. The stack was tuned against the reference: a bolder one
 * bloomed into a haze that flattened the panel instead of framing it. The outer
 * strokes need room to fade, which is what [padHPx]/[padVPx] reserve in the
 * stage and [gapHPx]/[gapVPx] define as the breathing space between the panel
 * edge and the ring.
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
        // The barrel turns sharply at its top/bottom tips; a miter join there
        // would shoot spikes out of the ring.
        strokeJoin = Paint.Join.ROUND
    }
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val shapePath = Path()

    /** Horizontal room between this view's edge and the panel it wraps. */
    var padHPx = 0f

    /** Vertical room between this view's edge and the panel it wraps. */
    var padVPx = 0f

    /** Gap between the panel's left/right edges and the ring. */
    var gapHPx = 0f

    /** Gap between the panel's top/bottom edges and the ring. */
    var gapVPx = 0f

    /** Exponent of the panel's silhouette — kept in step with the panel. */
    var exponent = GlassShape.EXPONENT

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // The panel occupies [padHPx, w-padHPx] x [padVPx, h-padVPx]; the ring
        // sits `gap` OUTSIDE that, following the identical curve, which is what
        // makes it read as light off the panel's edge rather than a floating
        // hoop.
        val left = (padHPx - gapHPx).coerceAtLeast(0f)
        val top = (padVPx - gapVPx).coerceAtLeast(0f)
        val right = (w - padHPx + gapHPx).coerceAtLeast(left + 4f)
        val bottom = (h - padVPx + gapVPx).coerceAtLeast(top + 4f)
        GlassShape.build(shapePath, left, top, right, bottom, exponent)

        val density = resources.displayMetrics.density
        paint.shader = LinearGradient(
            0f, top, 0f, bottom,
            intArrayOf(
                withAlpha(startColor, 0f),
                withAlpha(startColor, 0.5f),
                withAlpha(midColor, 1f),
                withAlpha(endColor, 0.5f),
                withAlpha(endColor, 0f),
            ),
            floatArrayOf(0f, 0.05f, 0.42f, 0.86f, 1f),
            Shader.TileMode.CLAMP,
        )

        for (i in STROKE_DP.indices) {
            paint.strokeWidth = STROKE_DP[i] * density
            paint.alpha = (ALPHA[i] * 255f).toInt().coerceIn(0, 255)
            canvas.drawPath(shapePath, paint)
        }

        // Hot core: the thin near-white line on top of the bloom. Without it
        // the stack reads as a soft haze with no edge and the eye skips over it.
        corePaint.shader = LinearGradient(
            0f, top, 0f, bottom,
            intArrayOf(
                withAlpha(CORE_TOP, 0f),
                withAlpha(CORE_TOP, 0.5f),
                withAlpha(CORE_MID, 1f),
                withAlpha(CORE_BOTTOM, 0.5f),
                withAlpha(CORE_BOTTOM, 0f),
            ),
            floatArrayOf(0f, 0.05f, 0.42f, 0.86f, 1f),
            Shader.TileMode.CLAMP,
        )
        corePaint.strokeWidth = CORE_STROKE_DP * density
        corePaint.alpha = (CORE_ALPHA * 255f).toInt().coerceIn(0, 255)
        canvas.drawPath(shapePath, corePaint)
    }

    private fun withAlpha(color: Int, fraction: Float): Int {
        val a = (Color.alpha(color) * fraction).toInt().coerceIn(0, 255)
        return (color and 0x00FFFFFF) or (a shl 24)
    }

    private companion object {
        // Widest stroke is 56dp: half of it (28dp) must fit between the ring and
        // this view's edge, i.e. padH - gapH >= 28dp (see haloStage).
        val STROKE_DP = floatArrayOf(56f, 40f, 28f, 20f, 14f, 8f, 3.5f)
        val ALPHA = floatArrayOf(0.10f, 0.16f, 0.26f, 0.42f, 0.60f, 0.78f, 0.95f)
        val CORE_STROKE_DP = 3.0f
        const val CORE_ALPHA = 0.92f
        val CORE_TOP = Color.rgb(215, 248, 255)
        val CORE_MID = Color.rgb(205, 195, 255)
        val CORE_BOTTOM = Color.rgb(245, 205, 255)
    }
}
