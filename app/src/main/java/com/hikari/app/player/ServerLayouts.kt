package com.hikari.app.player

/**
 * Sources sheet page layout (Settings → Playback & Servers → Server page
 * layout).
 *
 *  - HORIZONTAL — today's look: full-bleed rows floating over the title's
 *    landscape backdrop art.
 *  - VERTICAL — same glass rows floating over art, but the sheet is a
 *    narrow portrait column (capped at 560dp, centered) over the title's
 *    portrait poster art instead of full-bleed.
 *
 * Stored under AppStore.SERVER_LAYOUT and mirrored into the volatile
 * [current] below at startup (HikariApp), because the player is a View-based
 * Activity that has to know the value synchronously while its sheet is being
 * built — the same pattern as [PlayerSkins].
 */
object ServerLayouts {

    const val HORIZONTAL = "horizontal"
    const val VERTICAL = "vertical"

    @Volatile
    private var current: String = HORIZONTAL

    /** Stored value (or anything else) → one of the two known layouts. */
    fun namespace(raw: String?): String = when (raw?.trim()?.lowercase()) {
        VERTICAL -> VERTICAL
        else -> HORIZONTAL
    }

    fun setCurrent(raw: String?) {
        current = namespace(raw)
    }

    fun current(): String = current

    fun isVertical(): Boolean = current == VERTICAL
}
