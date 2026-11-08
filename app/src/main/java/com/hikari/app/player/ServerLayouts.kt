package com.hikari.app.player

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
