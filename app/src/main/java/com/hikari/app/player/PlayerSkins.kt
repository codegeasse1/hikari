package com.hikari.app.player

/**
 * Player interface skins (Settings → Player → Player UI).
 *
 * Four looks for the playback controls, so the overlay can match the taste of
 * whatever app the user came from:
 *
 *  - DEFAULT  — Hikari's own look, and what the app shipped with before the
 *               picker existed: frosted top bar, gradient bottom bar, round
 *               glass pills, accent-ringed play button, and the CURVED glass
 *               pane (the bent sheet with the neon running down its two bowed
 *               sides) behind every dialog — the server list loads inside the
 *               curving lines. This is the one the user means by "the old UI".
 *  - MINIMAL  — no bar backgrounds at all: the title and the controls read as
 *               text and icons floating directly on the picture, like VLC's
 *               compact overlay. Best for people who want the video, not the UI.
 *  - CINEMA   — MX Player / desktop-player feel: a solid rounded "deck" panel
 *               under the picture, square-ish control plates, a bigger play
 *               button. Reads as equipment rather than as chrome.
 *  - NEON     — floating rounded decks clear of the screen edges, with accent
 *               hairlines and a glowing play button. The "late night" skin.
 *
 * HOW IT IS APPLIED. [PlayerActivity] inflates one controller layout
 * (@layout/player_controller) and then calls its applyPlayerSkin(), which reads
 * [spec] and restyles the views it already has: bar backgrounds, pill drawables
 * and metrics, the play-button ring and the floating margins. The skins are
 * therefore a pure presentation layer — they cannot change what the controls DO
 * (every button, pill and media3 id is identical in all four skins), which also
 * means media3's own layout assumptions (the last child of exo_basic_controls,
 * the minimal-mode size thresholds) are unaffected by a skin change.
 *
 * The skin is stored under AppStore.PLAYER_SKIN and mirrored into the volatile
 * [current] below at startup (HikariApp), because the player is a View-based
 * Activity that has to know the value synchronously while it is being built —
 * the same pattern as [NetTuning] and TmdbResolver.contentLanguage.
 */
object PlayerSkins {

    const val DEFAULT = "default"
    /** The key DEFAULT took before the picker existed — kept so an install that
     *  has "glass" stored (and any preference or log line written under that
     *  name) keeps resolving to the same look, which is now [DEFAULT]. */
    const val GLASS = "glass"
    const val MINIMAL = "minimal"
    const val CINEMA = "cinema"
    const val NEON = "neon"

    /**
     * Every skin, in the order the picker lists them. [DEFAULT] leads because it
     * IS Hikari's look — the curved glass everything was built around — and the
     * others are deliberate departures from it. "Glass" is NOT a second entry
     * any more: it and Default are the same thing, and listing one look twice
     * made the picker read as broken (the user's report: "why did you in
     * default also added that box one" — Default had grown a flat box while the
     * curved pane they asked to be the default sat under the other name).
     */
    val ALL = listOf(DEFAULT, MINIMAL, CINEMA, NEON)

    /** What an unset/unknown value resolves to — Hikari's own look. */
    const val FALLBACK = DEFAULT

    fun normalize(key: String?): String =
        if (key != null && ALL.contains(key)) key else FALLBACK

    /** True when this skin's dialogs are the FLAT slab rather than the curved
     *  pane (see [CurvedGlassPanel.applySkin]). Default is the curve; the three
     *  looks that name a corner style of their own are flat. Callers that have
     *  to reserve room for the neon — or decide how much air to keep around a
     *  panel — ask this instead of comparing against [GLASS], which is no longer
     *  a value [normalize] can ever return. */
    fun isFlat(key: String?): Boolean = normalize(key) != DEFAULT

    fun label(key: String?): String = when (normalize(key)) {
        MINIMAL -> "Minimal"
        CINEMA -> "Cinema"
        NEON -> "Neon"
        else -> "Default"
    }

    fun description(key: String?): String = when (normalize(key)) {
        MINIMAL -> "No panels — just the controls floating on the video"
        CINEMA -> "A solid deck under the picture, square control plates"
        NEON -> "Floating rounded decks with glowing accent edges"
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

    /**
     * Everything [PlayerActivity] needs to restyle its controller. A `0` for a
     * drawable means "draw nothing" (no background / no ring), and the dp values
     * are applied to the inflated views as-is.
     */
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

        // DEFAULT — Hikari's own look, kept exactly as it always was: the
        // frosted bars, the round glass pills and the accent-ringed play
        // button, with the curved glass pane (see [CurvedGlassPanel]) behind
        // every dialog. This is the skin the app shipped with.
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
