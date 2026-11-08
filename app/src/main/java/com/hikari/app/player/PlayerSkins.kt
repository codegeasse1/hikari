package com.hikari.app.player

object PlayerSkins {

    const val DEFAULT = "default"
    /** The key DEFAULT took before the picker existed — kept so an install that
     *  has "glass" stored (and any preference or log line written under that
     *  name) keeps resolving to the same look, which is now [DEFAULT]. */
    const val GLASS = "glass"
    const val MINIMAL = "minimal"
    const val CINEMA = "cinema"
    const val NEON = "neon"
    const val TV = "tv"

    val ALL = listOf(DEFAULT, MINIMAL, CINEMA, NEON, TV)

    const val FALLBACK = MINIMAL

    fun normalize(key: String?): String = when {
        key == GLASS -> DEFAULT
        key != null && ALL.contains(key) -> key
        else -> FALLBACK
    }

    fun isFlat(key: String?): Boolean = normalize(key) != DEFAULT

    fun label(key: String?): String = when (normalize(key)) {
        MINIMAL -> "Minimal"
        CINEMA -> "Cinema"
        NEON -> "Neon"
        TV -> "TV Player"
        else -> "Default"
    }

    fun description(key: String?): String = when (normalize(key)) {
        MINIMAL -> "No panels — just the controls floating on the video"
        CINEMA -> "A solid deck under the picture, square control plates"
        NEON -> "Floating rounded decks with glowing accent edges"
        TV -> "Netflix-style dark controls made for TV remotes and D-pad navigation"
        else -> "Hikari's curved glass — frosted bars, neon-edged panels"
    }

    // ---- Synchronous mirror --------------------------------------------------

    @Volatile
    private var currentKey: String = FALLBACK

    fun setCurrent(key: String?) {
        currentKey = normalize(key)
    }

    fun current(): String = currentKey

    // ---- Presentation spec ---------------------------------------------------

    class SkinSpec(
        /** Background for the top bar, or 0 for none. */
        val topBarBackground: Int,
        /** Background for the bottom bar, or 0 for none. */
        val bottomBarBackground: Int,
        /** Background for the ordinary (non-accent) pills. */
        val pillBackground: Int,
        /** Pill text size in dp. */
        val pillTextDp: Float,
        /** Pill horizontal / vertical inner padding in dp. */
        val pillPadH: Int,
        val pillPadV: Int,
        /** Gap between pills, in dp. */
        val pillMargin: Int,
        /** Corner radius for the accent pills, in dp. */
        val accentPillRadius: Float,
        /** How the centre play button is drawn: see PlayTreatment. */
        val playTreatment: PlayTreatment,
        /** Play button diameter in dp. Never larger than 52 (see below). */
        val playSizeDp: Int,
        /** Margin for the floating decks, in dp (0 = edge to edge). */
        val deckMarginDp: Int,
        /** Extra vertical padding under the top bar's title, in dp. */
        val topBarPadBottom: Int,
    )

    enum class PlayTreatment { RING, PLAIN, SOLID, GLOW }

    fun spec(key: String?): SkinSpec = when (normalize(key)) {
        MINIMAL -> SkinSpec(
            topBarBackground = 0,
            bottomBarBackground = 0,
            pillBackground = com.hikari.app.R.drawable.pill_flat_ripple,
            pillTextDp = 10f,
            pillPadH = 6,
            pillPadV = 3,
            pillMargin = 1,
            accentPillRadius = 8f,
            playTreatment = PlayTreatment.PLAIN,
            playSizeDp = 44,
            deckMarginDp = 0,
            topBarPadBottom = 4,
        )

        CINEMA -> SkinSpec(
            topBarBackground = com.hikari.app.R.drawable.top_bar_bg,
            bottomBarBackground = com.hikari.app.R.drawable.player_deck_bg,
            pillBackground = com.hikari.app.R.drawable.pill_cinema_ripple,
            pillTextDp = 11f,
            pillPadH = 10,
            pillPadV = 5,
            pillMargin = 3,
            accentPillRadius = 5f,
            playTreatment = PlayTreatment.SOLID,
            playSizeDp = 52,
            deckMarginDp = 0,
            topBarPadBottom = 8,
        )

        TV -> SkinSpec(
            // TV uses the player's own gradient scrims rather than rectangular
            // decks. The controls remain visible, but the picture stays dominant.
            topBarBackground = 0,
            bottomBarBackground = 0,
            pillBackground = com.hikari.app.R.drawable.pill_flat_ripple,
            pillTextDp = 13f,
            pillPadH = 10,
            pillPadV = 6,
            pillMargin = 5,
            accentPillRadius = 12f,
            playTreatment = PlayTreatment.SOLID,
            playSizeDp = 64,
            deckMarginDp = 0,
            topBarPadBottom = 12,
        )

        NEON -> SkinSpec(
            topBarBackground = com.hikari.app.R.drawable.player_top_chip,
            bottomBarBackground = com.hikari.app.R.drawable.player_neon_deck,
            pillBackground = com.hikari.app.R.drawable.pill_neon_ripple,
            pillTextDp = 11f,
            pillPadH = 9,
            pillPadV = 5,
            pillMargin = 3,
            accentPillRadius = 14f,
            playTreatment = PlayTreatment.GLOW,
            playSizeDp = 52,
            deckMarginDp = 10,
            topBarPadBottom = 6,
        )

        else -> SkinSpec(
            topBarBackground = com.hikari.app.R.drawable.top_bar_bg,
            bottomBarBackground = com.hikari.app.R.drawable.bottom_bar_bg,
            pillBackground = com.hikari.app.R.drawable.pill_glass_ripple,
            pillTextDp = 11f,
            pillPadH = 8,
            pillPadV = 4,
            pillMargin = 2,
            accentPillRadius = 9f,
            playTreatment = PlayTreatment.RING,
            playSizeDp = 52,
            deckMarginDp = 0,
            topBarPadBottom = 8,
        )
    }
}
