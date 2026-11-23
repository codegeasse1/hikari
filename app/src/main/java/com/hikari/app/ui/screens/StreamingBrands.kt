package com.hikari.app.ui.screens

import androidx.compose.ui.graphics.Color

/** Instant local badges for the streaming-services row: paint on the first
 *  frame with brand colours, no network wait. The TMDB logo (when resolved)
 *  still replaces the badge; the id resolve continues in the background for
 *  the tap action. */
object StreamingBrands {
    class Brand(val short: String, val bg: Color, val fg: Color)

    private val MAP: Map<String, Brand> = mapOf(
        "netflix" to Brand("N", Color(0xFFE50914), Color.White),
        "disney+" to Brand("D+", Color(0xFF113CCF), Color.White),
        "disney plus" to Brand("D+", Color(0xFF113CCF), Color.White),
        "apple tv+" to Brand("AT", Color.Black, Color.White),
        "apple tv plus" to Brand("AT", Color.Black, Color.White),
        "prime video" to Brand("PV", Color(0xFF00A8E1), Color.White),
        "amazon" to Brand("PV", Color(0xFF00A8E1), Color.White),
        "hbo" to Brand("HBO", Color.Black, Color.White),
        "hulu" to Brand("H", Color(0xFF1CE783), Color.Black),
        "paramount+" to Brand("P+", Color(0xFF0064FF), Color.White),
        "paramount plus" to Brand("P+", Color(0xFF0064FF), Color.White),
        "peacock" to Brand("P", Color.Black, Color.White),
    )

    fun of(name: String): Brand {
        MAP[name.trim().lowercase()]?.let { return it }
        // Fallback: initials on a neutral tile, same as before but instant.
        val initials = name.split(" ").mapNotNull { it.firstOrNull()?.uppercase() }.take(2).joinToString("")
        return Brand(initials.ifBlank { "•" }, Color(0xFF2A2A2E), Color.White)
    }
}
