package com.hikari.app.reader.coil

import android.graphics.Bitmap
import coil.size.Size
import coil.transform.Transformation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A small unsharp-mask sharpen pass for reader pages.
 *
 * Short webtoon pages are decoded at (or below) the strip's display width and
 * then stretched to fill the screen, so a page served under the device's width
 * (the common ~800px source on a 1080px screen) reads soft even at the Ultra/4K
 * quality tiers — a bigger decode of the same pixels cannot fix that. This runs
 * a bounded 3x3 sharpen kernel over the decoded bitmap on an IO thread before
 * it reaches the memory cache, which restores edge crispness on text and line
 * art without changing the decode size or the cache footprint.
 *
 * Applied only at High quality and above (see the call sites): Low/Medium stay
 * on the fast unsharpened path. The [cacheKey] carries the strength, so a
 * strength change can never collide with older cached pages.
 */
class SharpenTransformation(
    private val strength: Float = 0.5f,
) : Transformation {

    override val cacheKey: String = "hikari_sharpen_$strength"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap = withContext(Dispatchers.IO) {
        // Hardware bitmaps cannot be read back — ask for a software copy first.
        // (Coil hands software bitmaps to transformations, so this is a no-op
        // safety net, never a per-page copy.)
        val src = if (input.config == Bitmap.Config.HARDWARE) {
            input.copy(Bitmap.Config.ARGB_8888, false) ?: input
        } else {
            input
        }
        val w = src.width
        val h = src.height
        if (w < 3 || h < 3) return@withContext input
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = IntArray(w * h)
        val s = strength.coerceIn(0f, 1f)
        // 3x3 kernel: center 1+4s, edges -s. s=0.5 keeps halos small on text.
        val center = 1f + 4f * s
        for (y in 0 until h) {
            val row = y * w
            val rowUp = ((y - 1).coerceAtLeast(0)) * w
            val rowDown = ((y + 1).coerceAtMost(h - 1)) * w
            for (x in 0 until w) {
                val xm = (x - 1).coerceAtLeast(0)
                val xp = (x + 1).coerceAtMost(w - 1)
                val c = pixels[row + x]
                val l = pixels[row + xm]
                val r = pixels[row + xp]
                val u = pixels[rowUp + x]
                val d = pixels[rowDown + x]
                out[row + x] = sharpenPixel(c, l, r, u, d, center, s)
            }
        }
        val result = if (input.isMutable) input else {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        }
        result.setPixels(out, 0, w, 0, 0, w, h)
        // When we borrowed the input's own mutable bitmap there is nothing to
        // recycle; when we allocated a fresh one Coil recycles the input
        // itself, so only the intermediate hardware-copy (if we made one) is
        // ours to drop.
        if (result !== input && src !== input) {
            runCatching { src.recycle() }
        }
        result
    }

    private fun sharpenPixel(c: Int, l: Int, r: Int, u: Int, d: Int, center: Float, s: Float): Int {
        val a = (c ushr 24) and 0xFF
        var red = (((c shr 16) and 0xFF) * center -
            (((l shr 16) and 0xFF) + ((r shr 16) and 0xFF) +
                ((u shr 16) and 0xFF) + ((d shr 16) and 0xFF)) * s)
        var green = (((c shr 8) and 0xFF) * center -
            (((l shr 8) and 0xFF) + ((r shr 8) and 0xFF) +
                ((u shr 8) and 0xFF) + ((d shr 8) and 0xFF)) * s)
        var blue = ((c and 0xFF) * center -
            ((l and 0xFF) + (r and 0xFF) + (u and 0xFF) + (d and 0xFF)) * s)
        red = red.coerceIn(0f, 255f)
        green = green.coerceIn(0f, 255f)
        blue = blue.coerceIn(0f, 255f)
        return (a shl 24) or (red.toInt() shl 16) or (green.toInt() shl 8) or blue.toInt()
    }
}
