package com.hikari.app.manga

/**
 * The reader's "Enhance" look, as a colour matrix.
 *
 * This is the mild, honest version of "enhance": a little more saturation (1.16
 * — scan sites' JPEGs are noticeably washed out), a little more contrast (1.10,
 * with the 50% grey pivot kept where it is so dark line art does not blow out),
 * and a hair of lift so pure blacks on a black reader backdrop stop looking like
 * holes. Nothing is sharpened: an unsharp mask would need a second pass over
 * every page and would ring on line art.
 *
 * It lives here, next to the reader's drawing code, rather than in the screen
 * that turns it into a filter, because the matrix is the *look*: the reader
 * draws every page through it (see `mangaEnhanceFilter` in the reader, which is
 * the only filter used now that the page is one ordinary bitmap like any other
 * image in the app), and anything that ever needs the same numbers again should
 * get them from this one place rather than inventing a second, slightly
 * different "enhance".
 */
internal fun mangaEnhanceMatrix(): FloatArray {
    val sat = 1.16f
    val contrast = 1.10f
    val lift = 0.02f * 255f
    val inv = 1f - sat
    val ir = 0.213f * inv
    val ig = 0.715f * inv
    val ib = 0.072f * inv
    val saturation = floatArrayOf(
        ir + sat, ig, ib, 0f, 0f,
        ir, ig + sat, ib, 0f, 0f,
        ir, ig, ib + sat, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )
    val t = (1f - contrast) / 2f * 255f + lift
    val levels = floatArrayOf(
        contrast, 0f, 0f, 0f, t,
        0f, contrast, 0f, 0f, t,
        0f, 0f, contrast, 0f, t,
        0f, 0f, 0f, 1f, 0f,
    )
    // levels ∘ saturation: the page is desaturated-onto-saturated first, then
    // levelled — the order that keeps the luma weights meaningful.
    return multiplyColorMatrix(levels, saturation)
}

/** `a ∘ b` in Android/Compose's 4×5 row-major colour-matrix layout: apply [b]
 *  first, then [a]. */
private fun multiplyColorMatrix(a: FloatArray, b: FloatArray): FloatArray {
    val out = FloatArray(20)
    for (row in 0 until 4) {
        for (col in 0 until 5) {
            var sum = if (col == 4) a[row * 5 + 4] else 0f
            for (k in 0 until 4) sum += a[row * 5 + k] * b[k * 5 + col]
            out[row * 5 + col] = sum
        }
    }
    return out
}
