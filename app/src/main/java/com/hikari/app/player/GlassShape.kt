package com.hikari.app.player

import android.graphics.Path
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The silhouette of the player's glass surfaces. Every dialog panel is cut to
 * THIS outline ([CurvedGlassPanel]) and the neon light is painted along this
 * same edge, so the panel is one single curved shape — there is no rectangle
 * with a hoop drawn behind it.
 *
 * The shape is a curved PANE, not a pillow and not a rounded rectangle. Both
 * vertical edges bow the SAME way (toward +x):
 *
 *  - the RIGHT edge bulges outward, past the box, by [BULGE_X] of the
 *    half-width, and
 *  - the LEFT edge bows inward, into the panel, by [CONCAVE_X] of the
 *    half-width.
 *
 * Each edge is a genuine circular arc of constant curvature spanning the
 * panel's full height — measured off the reference, its right edge traces an
 * arc of radius ~427px over a ~490px-tall panel, reaching ~27% of a half-width
 * further out at mid-height than at its top and bottom ends. The top and bottom
 * are the short straight runs that close the shape and their four corners are
 * the only places the outline is not an arc, so [build] rounds them with a
 * couple of corner-cutting passes.
 *
 * [buildSides] traces ONLY the two arcs. The reference has no light along the
 * flat top and bottom: the glow runs up one bowed side and down the other and
 * fades out at the ends. Stroking the closed outline would draw exactly the two
 * horizontal "box" lines the reference does not have, and an earlier attempt
 * that swept the light around the corners instead left short horizontal stubs
 * pointing at the panel's middle — keeping the light on the bare arcs is what
 * removes both.
 */
object GlassShape {

    /**
     * How far the RIGHT edge bulges outward, as a fraction of the panel's
     * half-width. This is the value that decides whether the panel reads as
     * curved at all, so it is deliberately large — the reference's side arc
     * reaches ~27% of a half-width further out at mid-height than at its
     * corners.
     */
    const val BULGE_X = 0.27f

    /**
     * How far the LEFT edge bows inward, as a fraction of the panel's
     * half-width. Positive means it curves INTO the panel (the middle of the
     * left edge sits to the right of its ends) — the "curves inside" the
     * reference shows, and the opposite direction to the right edge. A
     * symmetric pillow (both sides bulging out) was rejected for exactly this
     * reason.
     */
    const val CONCAVE_X = 0.167f

    /** Samples along each full-height edge arc. */
    private const val ARC_SAMPLES = 60

    /** Samples along each straight top/bottom run, excluding its ends. */
    private const val FLAT_SAMPLES = 8

    /**
     * Corner-cutting passes over the finished outline (silk-smoothing only —
     * at this sampling density it just rounds the four corners where an arc
     * meets a flat run).
     */
    private const val SMOOTH_PASSES = 2

    /** Outward bulge of the right edge, in px, clamped so its arc stays a
     *  well-formed (sub-semicircular) curve for any panel aspect. */
    private fun sagOut(hw: Float, hh: Float, bulgeX: Float) =
        min(hw * bulgeX, hh * 0.95f).coerceAtLeast(1e-3f)

    /** Inward bow of the left edge, in px, clamped the same way. */
    private fun sagIn(hw: Float, hh: Float, concaveX: Float) =
        min(hw * concaveX, hh * 0.95f).coerceAtLeast(1e-3f)

    /**
     * The x of the LEFT boundary at [y] (measured from the box's top edge),
     * relative to the box's left edge. The left edge is a circular arc whose
     * ends sit on the box's left side and whose middle is pulled in by
     * [CONCAVE_X] of the half-width, so this is 0 at the very top and bottom and
     * largest at mid-height.
     */
    fun leftEdge(
        w: Float,
        h: Float,
        y: Float,
        bulgeX: Float = BULGE_X,
        concaveX: Float = CONCAVE_X,
    ): Float {
        if (w <= 0f || h <= 0f) return 0f
        val hh = h / 2f
        val sl = sagIn(w / 2f, hh, concaveX)
        val dy = y - hh
        if (abs(dy) >= hh) return 0f
        val rl = (sl * sl + hh * hh) / (2f * sl)
        return (sl - rl) + sqrt(max(rl * rl - dy * dy, 0f))
    }

    /**
     * The x of the RIGHT boundary at [y] (measured from the box's top edge),
     * relative to the box's left edge. The right edge is a circular arc that
     * touches the box's right side at mid-height and is pulled back in by
     * [BULGE_X] of the half-width at the top and bottom.
     */
    fun rightEdge(
        w: Float,
        h: Float,
        y: Float,
        bulgeX: Float = BULGE_X,
        concaveX: Float = CONCAVE_X,
    ): Float {
        if (w <= 0f || h <= 0f) return w
        val hh = h / 2f
        val sr = sagOut(w / 2f, hh, bulgeX)
        val dy = y - hh
        if (abs(dy) >= hh) return w - sr
        val rr = (sr * sr + hh * hh) / (2f * sr)
        return (w - rr) + sqrt(max(rr * rr - dy * dy, 0f))
    }

    /**
     * Traces the closed silhouette into [path], fitted to the given box: the
     * right arc from top to bottom, the bottom run right to left, the left arc
     * from bottom to top, and the top run left to right.
     */
    fun build(
        path: Path,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        bulgeX: Float = BULGE_X,
        concaveX: Float = CONCAVE_X,
    ) {
        path.reset()
        val w = right - left
        val h = bottom - top
        if (w <= 0f || h <= 0f) return
        val pts = ArrayList<Float>(ARC_SAMPLES * 8 + FLAT_SAMPLES * 4)
        // Right edge, top -> bottom.
        for (i in 0..ARC_SAMPLES) {
            val f = i.toFloat() / ARC_SAMPLES
            add(pts, left + rightEdge(w, h, h * f, bulgeX, concaveX), top + h * f)
        }
        // Bottom run, right -> left.
        val xBR = left + rightEdge(w, h, h, bulgeX, concaveX)
        val xBL = left + leftEdge(w, h, h, bulgeX, concaveX)
        for (i in 1 until FLAT_SAMPLES) {
            val f = i.toFloat() / FLAT_SAMPLES
            add(pts, xBR + (xBL - xBR) * f, bottom)
        }
        // Left edge, bottom -> top.
        for (i in 0..ARC_SAMPLES) {
            val f = i.toFloat() / ARC_SAMPLES
            add(pts, left + leftEdge(w, h, h * (1f - f), bulgeX, concaveX), bottom - h * f)
        }
        // Top run, left -> right.
        val xTL = left + leftEdge(w, h, 0f, bulgeX, concaveX)
        val xTR = left + rightEdge(w, h, 0f, bulgeX, concaveX)
        for (i in 1 until FLAT_SAMPLES) {
            val f = i.toFloat() / FLAT_SAMPLES
            add(pts, xTL + (xTR - xTL) * f, top)
        }
        var cur = pts
        repeat(SMOOTH_PASSES) { cur = chaikin(cur) }
        if (cur.size < 6) return
        path.moveTo(cur[0], cur[1])
        var i = 2
        while (i < cur.size) {
            path.lineTo(cur[i], cur[i + 1])
            i += 2
        }
        path.close()
    }

    /**
     * Traces ONLY the two bowed sides into [path], as two open subpaths that
     * each span the panel's full height.
     *
     * This is what the neon light follows. There is no glow along the flat top
     * and bottom runs — no horizontal "box" lines and no short horizontal stubs
     * at the corners — because the light never leaves the arcs.
     */
    fun buildSides(
        path: Path,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        bulgeX: Float = BULGE_X,
        concaveX: Float = CONCAVE_X,
    ) {
        path.reset()
        val w = right - left
        val h = bottom - top
        if (w <= 0f || h <= 0f) return
        path.moveTo(left + rightEdge(w, h, 0f, bulgeX, concaveX), top)
        for (i in 1..ARC_SAMPLES) {
            val f = i.toFloat() / ARC_SAMPLES
            path.lineTo(left + rightEdge(w, h, h * f, bulgeX, concaveX), top + h * f)
        }
        path.moveTo(left + leftEdge(w, h, h, bulgeX, concaveX), bottom)
        for (i in 1..ARC_SAMPLES) {
            val f = i.toFloat() / ARC_SAMPLES
            path.lineTo(left + leftEdge(w, h, h * (1f - f), bulgeX, concaveX), bottom - h * f)
        }
    }

    private fun add(dst: ArrayList<Float>, x: Float, y: Float) {
        val n = dst.size
        if (n >= 2 && abs(dst[n - 2] - x) < 1e-4f && abs(dst[n - 1] - y) < 1e-4f) return
        dst.add(x)
        dst.add(y)
    }

    /** One Chaikin corner-cutting pass over a closed polygon packed as
     *  `[x0, y0, x1, y1, ...]`. */
    private fun chaikin(src: ArrayList<Float>): ArrayList<Float> {
        val n = src.size / 2
        val out = ArrayList<Float>(n * 4)
        for (i in 0 until n) {
            val j = (i + 1) % n
            val x0 = src[i * 2]
            val y0 = src[i * 2 + 1]
            val x1 = src[j * 2]
            val y1 = src[j * 2 + 1]
            out.add(0.75f * x0 + 0.25f * x1)
            out.add(0.75f * y0 + 0.25f * y1)
            out.add(0.25f * x0 + 0.75f * x1)
            out.add(0.25f * y0 + 0.75f * y1)
        }
        return out
    }
}
