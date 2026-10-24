package com.hikari.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Line-style source glyphs drawn in the app's own icon language (white strokes
 * on the glass tile, tinted accent like every material glyph there) for the
 * two rows no material icon covers: Hikari's torii gate and Stremio's
 * diamond-play. Motifs follow the supplied artwork; the rendering is strokes,
 * never pictures.
 */
private fun strokeNodes(data: String) = PathParser().parsePathString(data).toNodes()

private fun strokeBuilder(name: String): ImageVector.Builder =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    )

private fun ImageVector.Builder.stroke(
    data: String,
    width: Float,
): ImageVector.Builder = addPath(
    pathData = strokeNodes(data),
    fill = null,
    stroke = SolidColor(Color.White),
    strokeLineWidth = width,
    strokeLineCap = StrokeCap.Round,
    strokeLineJoin = StrokeJoin.Round,
)

/** Hikari's torii gate: curved lintel, tie beam, twin pillars, centre plaque. */
val HikariTorii: ImageVector by lazy {
    strokeBuilder("HikariTorii")
        .stroke("M2.5,7.5 C7,6.4 17,6.4 21.5,7.5", 2.2f)
        .stroke("M4.5,11 L19.5,11", 2f)
        .stroke("M7.2,11 L6.6,20", 2.2f)
        .stroke("M16.8,11 L17.4,20", 2.2f)
        .stroke("M12,11 L12,8.2", 1.8f)
        .stroke("M4.5,20 L19.5,20", 2f)
        .build()
}

/** Stremio's diamond-play: round-joined diamond with a filled play triangle. */
val StremioDiamond: ImageVector by lazy {
    strokeBuilder("StremioDiamond")
        .stroke("M12,2.8 L21.2,12 L12,21.2 L2.8,12 Z", 2f)
        .addPath(
            pathData = strokeNodes("M10,8.2 L15.8,12 L10,15.8 Z"),
            fill = SolidColor(Color.White),
        )
        .build()
}

/** Nuvio's play triangle: solid rounded triangle, the reference mark. */
val NuvioTriangle: ImageVector by lazy {
    strokeBuilder("NuvioTriangle")
        .addPath(
            pathData = strokeNodes("M8,5.2 L19,12 L8,18.8 Z"),
            fill = SolidColor(Color.White),
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
        .build()
}

/** Anymex's A: twin legs, crossbar and the reference's sweeping cut. */
val AnymexA: ImageVector by lazy {
    strokeBuilder("AnymexA")
        .stroke("M5.5,20 L12,4", 2.4f)
        .stroke("M12,4 L18.5,20", 2.4f)
        .stroke("M8.3,14 L15.7,14", 2f)
        .stroke("M3,16.5 C8,15.5 14,12.5 21,7", 1.4f)
        .build()
}
