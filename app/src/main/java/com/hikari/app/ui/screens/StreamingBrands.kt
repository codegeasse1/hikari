package com.hikari.app.ui.screens

import androidx.compose.ui.graphics.Color

/** Instant local brand tiles for the streaming-services row: full wordmark
 *  painted on the first frame, zero network. The background id resolve
 *  (for the tap action) runs in parallel and never blocks paint. */
object StreamingBrands {
    class Brand(val label: String, val bg: Color, val fg: Color)

    private val MAP: Map<String, Brand> = mapOf(
        "netflix" to Brand("NETFLIX", Color(0xFFE50914), Color.White),
        "disney+" to Brand("Disney+", Color(0xFF113CCF), Color.White),
        "disney plus" to Brand("Disney+", Color(0xFF113CCF), Color.White),
        "apple tv+" to Brand("Apple TV+", Color.Black, Color.White),
        "apple tv plus" to Brand("Apple TV+", Color.Black, Color.White),
        "prime video" to Brand("prime video", Color(0xFF00A8E1), Color.White),
        "amazon" to Brand("prime video", Color(0xFF00A8E1), Color.White),
        "hbo" to Brand("HBO", Color.Black, Color.White),
        "hulu" to Brand("hulu", Color(0xFF1CE783), Color.Black),
        "paramount+" to Brand("Paramount+", Color(0xFF0064FF), Color.White),
        "paramount plus" to Brand("Paramount+", Color(0xFF0064FF), Color.White),
        "peacock" to Brand("Peacock", Color.Black, Color.White),
    )

    fun of(name: String): Brand {
        MAP[name.trim().lowercase()]?.let { return it }
        return Brand(name, Color(0xFF2A2A2E), Color.White)
    }
}
