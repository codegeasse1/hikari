package com.hikari.app.player

import androidx.media3.common.Effect
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
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
 *  - Natural is the default and applies NOTHING visible: the armed pipeline
 *    carries a no-op so the picture stays pixel-identical, while sliders and
 *    preset picks apply live without ever re-opening the source.
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

/**
 * A user-built enhance preset: the five sliders of the player's enhance
 * editor (Brightness, Saturation, Contrast, Gamma, Hue — the set screenshot
 * reports show), stored as one JSON row in [com.hikari.app.data.AppStore]
 * under `playerEnhanceCustoms` and selected with the key `custom:<id>`.
 *
 * Mapping onto media3 (see the effect sources for the exact contracts):
 *  - saturation (-100..100) and hue (-180..180) go straight into
 *    [HslAdjustment], whose ranges are exactly those intervals;
 *  - contrast (-100..100) goes into [Contrast] as value/100 ([Contrast] takes
 *    -1..1 with 0 a no-op, and — unlike the matrix effects — asserts nothing
 *    about HDR, so it stays on for HDR video too);
 *  - brightness (-100..100) lifts [HslAdjustment] lightness a little (so it
 *    still does something on HDR) plus a real [Brightness] shift on SDR
 *    ([Brightness] throws on HDR input, so it is skipped there like the
 *    built-in tints);
 *  - gamma (-100..100) has no media3 effect behind it (there is no gamma
 *    primitive in media3-effect), so it is approximated the standard cheap
 *    way — a midtone lift through [HslAdjustment] lightness. It reads like
 *    gamma on ordinary footage; it is not a true power curve, and the slider
 *    does not pretend otherwise.
 */
data class CustomEnhancePreset(
    val id: String,
    val name: String,
    val brightness: Int = 0,
    val saturation: Int = 0,
    val contrast: Int = 0,
    val gamma: Int = 0,
    val hue: Int = 0,
) {
    fun key(): String = PREFIX + id

    fun displayName(): String = name.trim().ifBlank { "Custom" }

    fun isNeutral(): Boolean =
        brightness == 0 && saturation == 0 && contrast == 0 && gamma == 0 && hue == 0

    fun effects(hdr: Boolean): List<Effect> {
        val out = ArrayList<Effect>(3)
        val lightness = (brightness * 0.12f + gamma * 0.25f).coerceIn(-100f, 100f)
        if (saturation != 0 || hue != 0 || lightness != 0f) {
            out += HslAdjustment.Builder()
                .adjustHue(hue.toFloat())
                .adjustSaturation(saturation.toFloat())
                .adjustLightness(lightness)
                .build()
        }
        if (contrast != 0) out += Contrast((contrast / 100f).coerceIn(-1f, 1f))
        if (brightness != 0 && !hdr) {
            out += Brightness((brightness / 100f * 0.4f).coerceIn(-1f, 1f))
        }
        return out
    }

    fun summary(): String =
        "B $brightness · S $saturation · C $contrast · G $gamma · H $hue"

    fun toJson(): org.json.JSONObject = org.json.JSONObject()
        .put("id", id)
        .put("name", name)
        .put("b", brightness)
        .put("s", saturation)
        .put("c", contrast)
        .put("g", gamma)
        .put("h", hue)

    companion object {
        const val PREFIX = "custom:"
        private const val MAX_PRESETS = 12
        private const val MAX_NAME = 24

        fun isCustomKey(key: String?): Boolean =
            key != null && key.startsWith(PREFIX) && key.length > PREFIX.length

        fun idOf(key: String): String = key.removePrefix(PREFIX)

        fun newId(): String =
            "c" + System.currentTimeMillis().toString(36) +
                System.nanoTime().toString(36).takeLast(3)

        fun cleanName(name: String): String =
            name.trim().replace(Regex("\\s+"), " ").take(MAX_NAME)

        fun fromJson(o: org.json.JSONObject): CustomEnhancePreset? {
            val id = o.optString("id").trim().ifBlank { return null }
            return CustomEnhancePreset(
                id = id,
                name = cleanName(o.optString("name")).ifBlank { "Custom" },
                brightness = o.optInt("b").coerceIn(-100, 100),
                saturation = o.optInt("s").coerceIn(-100, 100),
                contrast = o.optInt("c").coerceIn(-100, 100),
                gamma = o.optInt("g").coerceIn(-100, 100),
                hue = o.optInt("h").coerceIn(-180, 180),
            )
        }

        fun listFromJson(raw: String?): List<CustomEnhancePreset> {
            if (raw.isNullOrBlank()) return emptyList()
            return runCatching {
                val arr = org.json.JSONArray(raw)
                (0 until minOf(arr.length(), MAX_PRESETS)).mapNotNull { i ->
                    arr.optJSONObject(i)?.let { fromJson(it) }
                }.distinctBy { it.id }
            }.getOrDefault(emptyList())
        }

        fun listToJson(list: List<CustomEnhancePreset>): String {
            val arr = org.json.JSONArray()
            list.take(MAX_PRESETS).forEach { arr.put(it.toJson()) }
            return arr.toString()
        }
    }
}
