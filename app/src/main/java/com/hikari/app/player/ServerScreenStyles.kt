package com.hikari.app.player

/**
 * Which look the server list page uses (Settings -> Customize -> Server screen
 * style). CLASSIC is the page the app always had and stays the default;
 * STREAMS is the hero banner + extension chips + live per-extension list.
 */
object ServerScreenStyles {

    const val CLASSIC = "classic"
    const val STREAMS = "streams"

    @Volatile
    private var current: String = CLASSIC

    fun namespace(raw: String?): String = when (raw?.trim()?.lowercase()) {
        STREAMS -> STREAMS
        else -> CLASSIC
    }

    fun setCurrent(raw: String?) {
        current = namespace(raw)
    }

    fun current(): String = current

    fun isStreams(): Boolean = current == STREAMS
}
