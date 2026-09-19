package com.hikari.app.ui

/**
 * How the "finding your server" card looks (Settings → App Layout → Loading
 * screen).
 *
 * The card is shown twice per play — the detail page puts it up on the tap and
 * the player continues with the same look until real video is on screen — so a
 * single choice covers both and the hand-off between them stays seamless.
 *
 *  - CINEMATIC — the original: the title's backdrop drifting slowly under a
 *                heavy scrim, the title breathing, a spinner at the bottom.
 *  - POSTER    — a glass card with the title's POSTER in it, so a portrait
 *                poster is never cropped; a hairline progress bar underneath.
 *  - SPOTLIGHT — no artwork at all: the accent colour blooms behind the title
 *                and a light sweeps across it. The most "designed" of the set.
 *  - MINIMAL   — flat background, small title, plain spinner, status line. For
 *                someone who wants to see that it is working and nothing else.
 *
 * The style is a pure presentation choice: every one of them carries the same
 * title, episode line and live status line (which the search keeps updating — a
 * server landing, "still searching the remaining extensions", a failure), so
 * nobody loses information by picking the quiet one.
 */
object LoadingStyles {
    const val CINEMATIC = "cinematic"
    const val POSTER = "poster"
    const val SPOTLIGHT = "spotlight"
    const val MINIMAL = "minimal"

    val ALL = listOf(CINEMATIC, POSTER, SPOTLIGHT, MINIMAL)

    fun normalize(key: String?): String = if (key != null && key in ALL) key else CINEMATIC

    fun label(key: String?): String = when (normalize(key)) {
        POSTER -> "Poster card"
        SPOTLIGHT -> "Spotlight"
        MINIMAL -> "Minimal"
        else -> "Cinematic"
    }

    fun description(key: String?): String = when (normalize(key)) {
        POSTER -> "The poster on a glass card, with a progress bar"
        SPOTLIGHT -> "No artwork — the title in a pool of accent light"
        MINIMAL -> "Flat and quiet: a spinner and the status line"
        else -> "Backdrop drifting behind the zooming title"
    }

    /** True when this style draws the backdrop artwork at all. [MINIMAL] and
     *  [SPOTLIGHT] are deliberate "no artwork" looks, and the player's own cover
     *  honours this by not loading a backdrop it will not show. */
    fun usesArtwork(key: String?): Boolean =
        normalize(key) == CINEMATIC || normalize(key) == POSTER
}
