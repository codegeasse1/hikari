package com.hikari.app.player

import androidx.media3.common.Effect
import androidx.media3.effect.Brightness
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.RgbAdjustment

/**
 * Video enhance presets (Settings → Player → Video enhance, and the Enhance
 * button in the player).
 *
 * These are REAL GPU colour grades applied to the decoded video by media3's
 * video-effects pipeline ([androidx.media3.effect]) — not a fake overlay on
 * the player UI — so they affect the picture itself and work with any server.
 *
 * Two things are deliberate here:
 *
 *  - Natural is the default and applies NOTHING. The enhancement pipeline costs
 *    a GL pass per frame, so it stays off until the user asks for it (and
 *    turning it off removes the pipeline again).
 *
 *  - Colour grading is built from [HslAdjustment] wherever possible, because
 *    the matrix-based effects ([Brightness], [RgbAdjustment]) assert that the
 *    input is NOT HDR and would throw on an HDR stream. Those are only added
 *    for SDR video (see [effects]), so a 4K HDR movie can never be broken by
 *    picking a preset.
 */
enum class EnhancePreset(
    val key: String,
    val label: String,
    val desc: String,
    /** The effects to hand to `ExoPlayer.setVideoEffects`. [hdr] is true when
     *  the video being played is HDR: HDR-unsafe matrix effects are skipped. */
    private val build: (hdr: Boolean) -> List<Effect>,
) {
    NATURAL(
        "natural",
        "Natural",
        "Nothing applied — the picture exactly as the server sent it",
        { emptyList() },
    ),
    VIBRANT(
        "vibrant",
        "Vibrant",
        "Richer colour and a touch more brightness",
        { hsl(saturation = 22f, lightness = 4f) },
    ),
    MOVIE(
        "movie",
        "Movie",
        "Deeper, calmer colours for films",
        { hsl(saturation = -6f, lightness = -6f) },
    ),
    CINEMATIC(
        "cinematic",
        "Cinematic",
        "A cooler film-grade look with a warm tint",
        { hdr -> hsl(hue = -8f, saturation = 6f, lightness = -7f) + warmTint(hdr) },
    ),
    WARM(
        "warm",
        "Warm",
        "Warmer skin tones, softer blues",
        { hdr -> hsl(hue = 6f, saturation = 6f, lightness = 4f) + warmTint(hdr) },
    ),
    COOL(
        "cool",
        "Cool",
        "Cooler, crisper tones",
        { hdr -> hsl(hue = -6f, saturation = 4f, lightness = 2f) + coolTint(hdr) },
    ),
    ANIME(
        "anime",
        "Anime",
        "Bright, punchy, high-saturation animation",
        { hsl(saturation = 32f, lightness = 8f) },
    ),
    BRIGHT(
        "bright",
        "Bright",
        "Lifts the picture when watching in a bright room",
        { hdr -> hsl(saturation = 4f, lightness = 14f) + brightLift(hdr) },
    );

    /** The effects to hand to `ExoPlayer.setVideoEffects`. */
    fun effects(hdr: Boolean): List<Effect> = build(hdr)

    companion object {
        val DEFAULT = NATURAL

        fun fromKey(key: String?): EnhancePreset =
            entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

private fun hsl(
    hue: Float = 0f,
    saturation: Float = 0f,
    lightness: Float = 0f,
): List<Effect> = listOf(
    HslAdjustment.Builder()
        .adjustHue(hue)
        .adjustSaturation(saturation)
        .adjustLightness(lightness)
        .build()
)

/** Warm tint. [RgbAdjustment] asserts non-HDR input, so it is skipped on HDR
 *  video (the HSL part of the preset still applies there). */
private fun warmTint(hdr: Boolean): List<Effect> =
    if (hdr) emptyList() else listOf(
        RgbAdjustment.Builder()
            .setRedScale(1.08f)
            .setGreenScale(1f)
            .setBlueScale(0.92f)
            .build()
    )

private fun coolTint(hdr: Boolean): List<Effect> =
    if (hdr) emptyList() else listOf(
        RgbAdjustment.Builder()
            .setRedScale(0.92f)
            .setGreenScale(1f)
            .setBlueScale(1.08f)
            .build()
    )

private fun brightLift(hdr: Boolean): List<Effect> =
    if (hdr) emptyList() else listOf(Brightness(0.05f))
