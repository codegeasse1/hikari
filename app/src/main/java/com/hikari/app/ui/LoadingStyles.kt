package com.hikari.app.ui

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

object LoadingEffects {
    const val NONE = "none"
    const val SHEEN = "sheen"
    const val AURA = "aura"
    const val FRAME = "frame"
    const val GLOW = "glow"

    /** Every effect, in the order Settings lists them. */
    val ALL = listOf(NONE, SHEEN, AURA, FRAME, GLOW)

    fun normalize(key: String?): String = if (key != null && key in ALL) key else NONE

    fun normalizeSet(keys: Collection<String>?): Set<String> =
        if (keys.isNullOrEmpty()) emptySet()
        else ALL.filter { it != NONE && it in keys }.toSet()

    /** A single stored key from an older build — or a comma-joined list, which is
     *  how the set rides through PlayerActivity's intent extras — read back as
     *  the treatments it names. */
    fun parse(stored: String?): Set<String> {
        val s = stored?.trim().orEmpty()
        if (s.isEmpty()) return emptySet()
        return normalizeSet(s.split(',').map { it.trim() })
    }

    /** The chosen treatments as ONE preference value (see [parse]). */
    fun encode(keys: Collection<String>): String =
        ALL.filter { it in keys }.joinToString(",")

    fun label(key: String?): String = when (normalize(key)) {
        SHEEN -> "Sheen"
        AURA -> "Aura ring"
        FRAME -> "Gallery frame"
        GLOW -> "Accent glow"
        else -> "None"
    }

    /** The chosen treatments as one line: "Sheen + Aura ring". */
    fun label(keys: Collection<String>): String {
        val names = ALL.filter { it != NONE && it in keys }.map { label(it) }
        return if (names.isEmpty()) label(NONE) else names.joinToString(" + ")
    }

    fun description(key: String?): String = when (normalize(key)) {
        SHEEN -> "A band of light sweeps across the cover"
        AURA -> "A breathing ring of light around the screen's edge"
        FRAME -> "A hairline gallery frame around the cover"
        GLOW -> "An accent bloom that swells behind the title"
        else -> "No extra treatment"
    }

    /** The chosen treatments as one line — the description of the first, plus a
     *  count of the others, so the row stays one line tall. */
    fun description(keys: Collection<String>): String {
        val chosen = ALL.filter { it != NONE && it in keys }
        if (chosen.isEmpty()) return description(NONE)
        val first = description(chosen.first())
        return if (chosen.size == 1) first
        else first + " · +" + (chosen.size - 1).toString() + " more"
    }

    /** True when any chosen treatment animates (so the card runs a clock). */
    fun animated(keys: Collection<String>): Boolean =
        keys.any { it == SHEEN || it == AURA }

    /** Kept for a stored value from an older build. */
    fun animated(key: String?): Boolean = animated(normalizeSet(listOfNotNull(key)))

    /** True when any chosen treatment asks for the accent bloom view
     *  (`loading_glow` / the Compose glow layer) — see [LoadingStyles] and the
     *  player's own cover. */
    fun usesGlow(keys: Collection<String>): Boolean = GLOW in keys
}
