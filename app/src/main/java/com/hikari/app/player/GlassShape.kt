package com.hikari.app.player

import android.graphics.Path
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

object GlassShape {

    const val BULGE_X = 0.27f

    const val CONCAVE_X = 0.167f

    /** Samples along each full-height edge arc. */
    private const val ARC_SAMPLES = 60

    /** Samples along each straight top/bottom run, excluding its ends. */
    private const val FLAT_SAMPLES = 8

    private const val SMOOTH_PASSES = 2

    /** Outward bulge of the right edge, in px, clamped so its arc stays a
     *  well-formed (sub-semicircular) curve for any panel aspect. */
    private fun sagOut(hw: Float, hh: Float, bulgeX: Float) =
        min(hw * bulgeX, hh * 0.95f).coerceAtLeast(1e-3f)

    /** Inward bow of the left edge, in px, clamped the same way. */
    private fun sagIn(hw: Float, hh: Float, concaveX: Float) =
        min(hw * concaveX, hh * 0.95f).coerceAtLeast(1e-3f)

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
