package com.hikari.app.reader

import androidx.compose.ui.graphics.BlendMode

/**
 * Reader settings ported from chimahon/yomi. These are plain enums (no string resources) so the
 * Compose chrome can label them and the native viewer can consume their values.
 */

/** Target aspect ratio for the webtoon "long strip with gaps" smart scaling. */
enum class WebtoonScaleType(val ratio: Float) {
    FIT(0f),
    R4_3(3f / 4f),
    R3_2(2f / 3f),
    R16_9(9f / 16f),
    R20_9(9f / 20f),
}

/** How the reader's tap-zone regions are flipped. */
enum class TappingInvertMode(
    val shouldInvertHorizontal: Boolean = false,
    val shouldInvertVertical: Boolean = false,
) {
    NONE,
    HORIZONTAL(shouldInvertHorizontal = true),
    VERTICAL(shouldInvertVertical = true),
    BOTH(shouldInvertHorizontal = true, shouldInvertVertical = true),
}

/** Scroll distance (px) above which the reader menu auto-hides. */
enum class ReaderHideThreshold(val threshold: Int) {
    HIGHEST(5),
    HIGH(13),
    LOW(31),
    LOWEST(47),
}

/** Blend mode used by the color-filter overlay, mirroring chimahon's ColorFilterMode list. */
enum class ColorFilterMode(val blendMode: BlendMode) {
    DEFAULT(BlendMode.SrcOver),
    MULTIPLY(BlendMode.Modulate),
    SCREEN(BlendMode.Screen),
    OVERLAY(BlendMode.Overlay),
    LIGHTEN(BlendMode.Lighten),
    DARKEN(BlendMode.Darken),
}
