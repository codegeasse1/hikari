package com.hikari.app.music

import android.content.Context

/**
 * VxMusic quality options ported to Hikari music (login-free).
 * Same tier ids as VxMusic so behaviour matches:
 *  27 = 24-bit/192kHz FLAC, 7 = 24/96 FLAC, 6 = 16/44.1 FLAC, 5 = 320k MP3, -1 = YouTube Opus.
 */
object VxQuality {
    const val MAX_HI_RES = 27
    const val HI_RES_96 = 7
    const val CD_LOSSLESS = 6
    const val MP3_320 = 5
    const val YOUTUBE = -1

    private const val PREFS = "hikari_music_vx"
    private const val K_PREFER = "prefer_lossless"
    private const val K_STREAM = "lossless_quality"
    private const val K_DOWNLOAD = "download_quality"
    private const val K_BITPERFECT = "bit_perfect"
    private const val K_CLARITY = "studio_clarity"

    val STREAM_OPTIONS = listOf(MAX_HI_RES, HI_RES_96, CD_LOSSLESS, MP3_320, YOUTUBE)
    val DOWNLOAD_OPTIONS = listOf(MAX_HI_RES, HI_RES_96, CD_LOSSLESS, MP3_320, YOUTUBE)

    fun labelFor(q: Int): String = when (q) {
        MAX_HI_RES -> "FLAC 24-bit/192kHz"
        HI_RES_96 -> "FLAC 24-bit/96kHz"
        CD_LOSSLESS -> "FLAC 16-bit/44.1kHz"
        MP3_320 -> "MP3 320kbps"
        else -> "YouTube (Opus/AAC)"
    }

    fun shortLabelFor(q: Int): String = when (q) {
        MAX_HI_RES -> "24/192"
        HI_RES_96 -> "24/96"
        CD_LOSSLESS -> "16/44.1"
        MP3_320 -> "320k"
        else -> "YT"
    }

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun preferLossless(ctx: Context): Boolean =
        runCatching { prefs(ctx).getBoolean(K_PREFER, true) }.getOrDefault(true)

    fun setPreferLossless(ctx: Context, v: Boolean) {
        runCatching { prefs(ctx).edit().putBoolean(K_PREFER, v).apply() }
        runCatching { com.hikari.app.music.MusicApi.clearStreamCache() }
    }

    fun streamQuality(ctx: Context): Int =
        runCatching { prefs(ctx).getInt(K_STREAM, MAX_HI_RES) }.getOrDefault(MAX_HI_RES)
            .let { if (it in STREAM_OPTIONS) it else MAX_HI_RES }

    fun setStreamQuality(ctx: Context, q: Int) {
        val v = if (q in STREAM_OPTIONS) q else MAX_HI_RES
        runCatching { prefs(ctx).edit().putInt(K_STREAM, v).apply() }
        runCatching { com.hikari.app.music.MusicApi.clearStreamCache() }
    }

    fun downloadQuality(ctx: Context): Int =
        runCatching { prefs(ctx).getInt(K_DOWNLOAD, MAX_HI_RES) }.getOrDefault(MAX_HI_RES)
            .let { if (it in DOWNLOAD_OPTIONS) it else MAX_HI_RES }

    fun setDownloadQuality(ctx: Context, q: Int) {
        val v = if (q in DOWNLOAD_OPTIONS) q else MAX_HI_RES
        runCatching { prefs(ctx).edit().putInt(K_DOWNLOAD, v).apply() }
    }

    fun bitPerfect(ctx: Context): Boolean =
        runCatching { prefs(ctx).getBoolean(K_BITPERFECT, false) }.getOrDefault(false)

    fun setBitPerfect(ctx: Context, v: Boolean) {
        runCatching { prefs(ctx).edit().putBoolean(K_BITPERFECT, v).apply() }
    }

    fun studioClarity(ctx: Context): Boolean =
        runCatching { prefs(ctx).getBoolean(K_CLARITY, true) }.getOrDefault(true)

    fun setStudioClarity(ctx: Context, v: Boolean) {
        runCatching { prefs(ctx).edit().putBoolean(K_CLARITY, v).apply() }
    }

    /** Same fallback order as VxMusic: preferred, then above it, then below it, then YouTube. */
    fun attemptOrder(preferred: Int): List<Int> {
        if (preferred == YOUTUBE) return emptyList()
        val tiers = listOf(MP3_320, CD_LOSSLESS, HI_RES_96, MAX_HI_RES)
        val i = tiers.indexOf(preferred)
        if (i == -1) return listOf(MAX_HI_RES, HI_RES_96, CD_LOSSLESS, MP3_320)
        val above = tiers.subList(i + 1, tiers.size)
        val below = tiers.subList(0, i).reversed()
        return (listOf(tiers[i]) + above + below).distinct()
    }
}
