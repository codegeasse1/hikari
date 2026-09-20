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

/**
 * The treatment drawn OVER the loading card — Settings → App Layout → Loading
 * screen → Effect.
 *
 * The two quiet styles were reported as too plain ("minimal and spotlight is so
 * simple, it just shows the title zooming in and out"), so the loading cover now
 * has the same kind of signature details a poster card does: a band of light
 * sweeping across it, a breathing accent ring, a gallery mat around the frame,
 * or an accent bloom behind the name.
 *
 * Deliberately independent of the style: an effect is drawn over whichever card
 * the user chose, so "Minimal + gallery frame" and "Spotlight + sheen" are both
 * one setting each. Every effect is pure drawing on top of the existing cover —
 * no extra network request, no extra copy of the artwork — and they compose with
 * the styles' own motion rather than replacing it.
 *
 * The choice is a SET: more than one treatment can be on at once ("sheen and
 * aura ring", "gallery frame and sheen"), and each is drawn in its own layer so
 * the combination reads as one cover rather than two. An empty set is [NONE].
 *
 *  - [NONE]  nothing over the card (the styles as they were).
 *  - [SHEEN] a band of light sweeps across the cover, like a glossy print
 *            catching the light.
 *  - [AURA]  a breathing accent ring behind the name.
 *  - [FRAME] a hairline gallery frame inset around the whole cover.
 *  - [GLOW]  an accent bloom that swells and fades behind the title.
 */
object LoadingEffects {
    const val NONE = "none"
    const val SHEEN = "sheen"
    const val AURA = "aura"
    const val FRAME = "frame"
    const val GLOW = "glow"

    /** Every effect, in the order Settings lists them. */
    val ALL = listOf(NONE, SHEEN, AURA, FRAME, GLOW)

    fun normalize(key: String?): String = if (key != null && key in ALL) key else NONE

    /** The treatments named by [keys], in [ALL] order and without [NONE].
     *
     *  Several effects at once is the point (Settings → App Layout → Loading
     *  screen → Effect): "sheen and aura ring" or "gallery frame and sheen" are
     *  each one choice, and every one of them is drawn over the same card. */
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
        AURA -> "A breathing accent ring around the card"
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
