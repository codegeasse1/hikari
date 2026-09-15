package com.hikari.app.player

import android.graphics.Path
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
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
 * The top and bottom edges are the short straight-ish runs that close the
 * shape, and they carry no light (see [buildSides]) — measured off the
 * reference, the glow runs up one bowed side, round the corners and down the
 * other, and fades out completely along the flats, which is what stops the
 * panel reading as a box.
 *
 * Every number here was measured off the reference's glowing edges: its right
 * edge traces a circular arc of radius ~427px over a ~490px-tall panel, and its
 * left edge bows inward ~50px over the same height and spans the middle ~55% of
 * it before the corners take over. Both edges are genuine circular arcs of
 * constant curvature, and each pair of neighbouring edges is joined by a
 * long, tangent-continuous corner blend, so there is no kink anywhere on the
 * outline.
 */
object GlassShape {

    /**
     * How far the RIGHT edge bulges outward, as a fraction of the panel's
     * half-width. This is the value that decides whether the panel reads as
     * curved at all, so it is deliberately large — the reference's side arc
     * reaches ~27% of a half-width further out at mid-height than at its
     * corners. The earlier superellipse (exponent 4.2) was effectively a rounded
     * rectangle, which is the complaint this answers.
     */
    const val BULGE_X = 0.27f

    /**
     * How far the LEFT edge bows inward, as a fraction of the panel's
     * half-width. Positive means it curves INTO the panel (the middle of the
     * left edge sits to the right of its corners) — the "curves inside" the
     * reference shows, and the opposite of the right edge. A symmetric pillow
     * (both sides bulging out) was rejected for exactly this reason.
     */
    const val CONCAVE_X = 0.167f

    /**
     * How much of each edge is handed over to the corner blend, as a fraction of
     * that edge's length. At 0.26 the blend covers the last quarter of the side
     * AND the first quarter of the top, so the corner is a long continuous
     * sweep; measured against the reference, the left edge's clean arc runs from
     * ~21% to ~76% of the panel's height before the corners take over, which is
     * this fraction. Lower values close the corner up into a tight radius.
     */
    private const val CORNER_FRAC = 0.26f

    /** Samples along the straight part of each edge. Odd, so t = 0.5 (the
     *  edge's widest point) is always one of them. */
    private const val MID_SAMPLES = 13

    /** Samples taken across each corner blend. */
    private const val CORNER_SAMPLES = 12

    /** Corner-cutting passes over the finished outline (silk-smoothing only). */
    private const val SMOOTH_PASSES = 1

    private const val TWO_PI = 6.2831855f

    /** Outward bulge of the right edge, in px, clamped so its arc stays a
     *  well-formed (sub-semicircular) curve for any panel aspect. */
    private fun sagOut(hw: Float, hh: Float, bulgeX: Float) =
        min(hw * bulgeX, hh * 0.95f).coerceAtLeast(1e-3f)

    /** Inward bow of the left edge, in px, clamped the same way. */
    private fun sagIn(hw: Float, hh: Float, concaveX: Float) =
        min(hw * concaveX, hh * 0.95f).coerceAtLeast(1e-3f)

    /**
     * The x of the LEFT boundary at [y], measured from the box's left edge. The
     * left edge is a circular arc whose ends sit on the box's left side and
     * whose middle is pulled in by [CONCAVE_X] of the half-width, so this is 0
     * at the very top and bottom and largest at mid-height.
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
     * The x of the RIGHT boundary at [y], measured from the box's left edge. The
     * right edge is a circular arc that touches the box's right side at
     * mid-height and is pulled back in by [BULGE_X] of the half-width at the top
     * and bottom.
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
     * Traces the closed silhouette into [path], fitted to the given box.
     *
     * Built as four edges (the two bowed sides are circular arcs, the top and
     * bottom are straight runs) whose ends are pulled back by [CORNER_FRAC], with
     * each pair of ends bridged by a cubic that leaves and arrives along the two
     * edges' own tangents. That construction is the whole trick: the blend
     * cannot form a corner because it is tangent to both edges where it meets
     * them, so the outline sweeps continuously round the shape.
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
        val runs = runs(left, top, right, bottom, bulgeX, concaveX)
        val pts = ArrayList<Float>(runs.sumOf { it.size })
        for (r in runs) pts.addAll(r)
        if (pts.size < 6) return
        var cur = pts
        repeat(SMOOTH_PASSES) { cur = chaikin(cur) }
        path.moveTo(cur[0], cur[1])
        var i = 2
        while (i < cur.size) {
            path.lineTo(cur[i], cur[i + 1])
            i += 2
        }
        path.close()
    }

    /**
     * Traces ONLY the two bowed sides (with the corner sweeps that lead into the
     * top and bottom) into [path], as two open subpaths.
     *
     * This is what the neon light follows. The reference has no glow along the
     * flat top and bottom runs — the light comes up one side, rounds the
     * corners and goes down the other and fades out — so stroking the closed
     * outline instead would draw exactly the two horizontal "box" lines the
     * reference does not have.
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
        val r = runs(left, top, right, bottom, bulgeX, concaveX)
        // Order inside [runs]: [0]=right arc, [1]=blend right->bottom,
        // [2]=bottom run, [3]=blend bottom->left, [4]=left arc,
        // [5]=blend left->top, [6]=top run, [7]=blend top->right.
        // The right side is the blend down from the top corner, then the arc,
        // then the blend into the bottom corner; the left side is the mirror.
        appendSide(path, r[7], r[0], r[1])
        appendSide(path, r[3], r[4], r[5])
    }

    /** Appends [pieces] as ONE continuous subpath of [path], so the stroked
     *  side reads as a single sweep rather than blobs at the joins. */
    private fun appendSide(path: Path, vararg pieces: ArrayList<Float>) {
        var started = false
        for (piece in pieces) {
            var i = 0
            while (i < piece.size) {
                if (started) path.lineTo(piece[i], piece[i + 1])
                else {
                    path.moveTo(piece[i], piece[i + 1])
                    started = true
                }
                i += 2
            }
        }
    }

    /**
     * The eight point runs of the outline (see [buildSides] for their order).
     * Edge 0 is the right arc, traversed top -> bottom; edge 1 the bottom run,
     * right -> left; edge 2 the left arc, bottom -> top; edge 3 the top run,
     * left -> right. `runs[2*e]` is edge `e`'s middle samples, `runs[2*e+1]` the
     * corner blend from edge `e` into edge `e+1`.
     */
    private fun runs(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        bulgeX: Float,
        concaveX: Float,
    ): Array<ArrayList<Float>> {
        val out = Array(8) { ArrayList<Float>() }
        val w = right - left
        val h = bottom - top
        if (w <= 0f || h <= 0f) return out
        val hw = w / 2f
        val hh = h / 2f
        val cy = top + hh
        val sr = sagOut(hw, hh, bulgeX)
        val sl = sagIn(hw, hh, concaveX)

        // Right edge: circular arc through (right-sr, top), (right, cy),
        // (right-sr, bottom). Its centre sits a radius to the left of the apex.
        val rr = (sr * sr + hh * hh) / (2f * sr)
        val rcx = right - rr
        val aRtop = atan2(top - cy, (right - sr) - rcx)
        val aRbot = atan2(bottom - cy, (right - sr) - rcx)

        // Left edge: circular arc through (left, top), (left+sl, cy),
        // (left, bottom). Its centre also sits to the left, so the middle of the
        // edge is pulled in toward the panel.
        val rl = (sl * sl + hh * hh) / (2f * sl)
        val lcx = left + (sl * sl - hh * hh) / (2f * sl)
        val aLbot = atan2(bottom - cy, left - lcx)
        val aLtop = atan2(top - cy, left - lcx)

        fun point(e: Int, t: Float): FloatArray = when (e) {
            0 -> {
                val th = aRtop + (aRbot - aRtop) * t
                floatArrayOf(rcx + rr * cos(th), cy + rr * sin(th))
            }
            1 -> floatArrayOf((right - sr) + (left - (right - sr)) * t, bottom)
            2 -> {
                val th = aLbot + (aLtop - aLbot) * t
                floatArrayOf(lcx + rl * cos(th), cy + rl * sin(th))
            }
            else -> floatArrayOf(left + ((right - sr) - left) * t, top)
        }

        fun tangent(e: Int, t: Float): FloatArray {
            val d = 0.002f
            val p1 = point(e, min(1f, t + d))
            val p0 = point(e, max(0f, t - d))
            val dx = p1[0] - p0[0]
            val dy = p1[1] - p0[1]
            val m = hypot(dx, dy).coerceAtLeast(1e-6f)
            return floatArrayOf(dx / m, dy / m)
        }

        fun push(dst: ArrayList<Float>, x: Float, y: Float) {
            val n = dst.size
            if (n >= 2 && abs(dst[n - 2] - x) < 1e-4f && abs(dst[n - 1] - y) < 1e-4f) return
            dst.add(x)
            dst.add(y)
        }

        for (e in 0 until 4) {
            val next = (e + 1) % 4
            val mid = out[2 * e]
            val blend = out[2 * e + 1]
            for (k in 0 until MID_SAMPLES) {
                val t = CORNER_FRAC + (1f - 2f * CORNER_FRAC) * k / (MID_SAMPLES - 1)
                val p = point(e, t)
                push(mid, p[0], p[1])
            }
            // Blend from this edge's corner zone into the next edge's, with the
            // control points laid along each edge's own tangent by the distance
            // to their shared junction, so the blend cannot kink.
            val a = point(e, 1f - CORNER_FRAC)
            val ta = tangent(e, 1f - CORNER_FRAC)
            val b = point(next, CORNER_FRAC)
            val tb = tangent(next, CORNER_FRAC)
            val k = point(e, 1f)
            val la = hypot(k[0] - a[0], k[1] - a[1]).coerceAtLeast(0.001f)
            val lb = hypot(k[0] - b[0], k[1] - b[1]).coerceAtLeast(0.001f)
            val c1x = a[0] + ta[0] * la
            val c1y = a[1] + ta[1] * la
            val c2x = b[0] - tb[0] * lb
            val c2y = b[1] - tb[1] * lb
            for (i in 1 until CORNER_SAMPLES) {
                val t = i.toFloat() / CORNER_SAMPLES
                val u = 1f - t
                push(
                    blend,
                    u * u * u * a[0] + 3f * u * u * t * c1x + 3f * u * t * t * c2x + t * t * t * b[0],
                    u * u * u * a[1] + 3f * u * u * t * c1y + 3f * u * t * t * c2y + t * t * t * b[1],
                )
            }
        }
        return out
    }

    /** One Chaikin corner-cutting pass over a closed polygon packed as
     *  `[x0, y0, x1, y1, ...]`. At this sampling density it only polishes the
     *  polyline — the corner blends above are already tangent-continuous. */
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
