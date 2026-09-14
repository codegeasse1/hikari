package com.hikari.app.player

import android.graphics.Path
import kotlin.math.abs
import kotlin.math.pow

/**
 * The silhouette shared by every curved surface of the player's UI: the glass
 * panel itself ([CurvedGlassPanel]) and the neon ring behind it
 * ([GlassArcView]) both trace THIS shape, so the glow always hugs the panel's
 * real outline and cannot drift away from it.
 *
 * The shape is a superellipse — a rounded rectangle whose four edges bow
 * outward, the left/right edges far more than the top/bottom. That is what the
 * reference UI does: the panel is not a rectangle, its sides sweep out to their
 * widest point at mid-height and taper away toward the top and bottom, and the
 * rows inside it follow that same bend. A plain rounded rectangle (what this
 * screen used to draw) reads as a stock Android dialog no matter how bright the
 * glow behind it is.
 *
 * [halfWidth] is the single source of truth for the geometry: the path is built
 * from it, and the per-row insets are read off it, so a row can never be laid
 * out wider than the shape that clips it. It is closed-form in y (no iteration,
 * no PathMeasure), which is what makes querying it per row cheap enough to do on
 * every layout pass.
 */
object GlassShape {

    /**
     * How "barrel" the shape is. The superellipse exponent: 2 is an ellipse
     * (very pronounced taper), 4-5 a soft barrel, 8+ nearly a rectangle.
     *
     * 4.2 was picked against the reference: it gives a visible sweep (rows near
     * the middle of a menu come out ~13dp wider per side than the first/last
     * row) while keeping those first/last rows comfortably wide — a lower
     * exponent pinched the top row into something that no longer looked like a
     * row. Exponent is a per-panel field, so a panel that wants a gentler bend
     * can pass its own.
     */
    const val EXPONENT = 4.2f

    /**
     * Half of the shape's width at [y] (y measured from the top of the [h]-tall
     * bounding box), i.e. the distance from its centre line to the silhouette
     * edge. At mid-height this is exactly `w / 2` (the shape is widest there),
     * and it tapers to 0 at the very top and bottom.
     */
    fun halfWidth(w: Float, h: Float, y: Float, exponent: Float = EXPONENT): Float {
        if (w <= 0f || h <= 0f) return 0f
        val a = w / 2f
        val b = h / 2f
        val u = abs((y - b) / b).coerceIn(0f, 1f)
        val t = 1f - u.pow(exponent)
        return a * t.coerceAtLeast(0f).pow(1f / exponent)
    }

    /** How far the silhouette's edge is pulled in from the bounding box at [y]. */
    fun inset(w: Float, h: Float, y: Float, exponent: Float = EXPONENT): Float =
        (w / 2f) - halfWidth(w, h, y, exponent)

    /**
     * Traces the silhouette into [path], fitted to the given box. Walked as a
     * fine polyline: the curve is smooth, the segments land well under a pixel
     * of deviation at these sizes, and it keeps this class free of any
     * PathMeasure/arc maths that would have to stay in sync with [halfWidth].
     */
    fun build(
        path: Path,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        exponent: Float = EXPONENT,
        steps: Int = 120,
    ) {
        path.reset()
        val w = right - left
        val h = bottom - top
        if (w <= 0f || h <= 0f) return
        val cx = left + w / 2f
        for (i in 0..steps) {
            val y = h * i / steps
            val x = cx - halfWidth(w, h, y, exponent)
            if (i == 0) path.moveTo(x, top + y) else path.lineTo(x, top + y)
        }
        for (i in steps downTo 0) {
            val y = h * i / steps
            path.lineTo(cx + halfWidth(w, h, y, exponent), top + y)
        }
        path.close()
    }
}
