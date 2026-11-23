package com.hikari.app.ui.screens

import com.hikari.app.R

/** Real bundled streamer logos: first-frame instant, zero network.
 *  Artwork is the genuine TMDB brand mark for each service, shipped in
 *  res/drawable-nodpi. [providerId] is the TMDB watch-provider id used by
 *  the "Everything" grid (0 = HBO, which uses its TMDB company instead). */
object StreamingBrands {
    class Brand(
        val label: String,
        val resId: Int,
        val providerId: Int,
    )

    val ORDER: List<String> = listOf(
        "Netflix",
        "Disney+",
        "Apple TV+",
        "Prime Video",
        "HBO",
        "Hulu",
        "Paramount+",
        "Peacock",
        "Crunchyroll",
    )

    private val MAP: Map<String, Brand> = mapOf(
        "netflix" to Brand("Netflix", R.drawable.stream_logo_netflix, 8),
        "disney+" to Brand("Disney+", R.drawable.stream_logo_disney, 337),
        "disney plus" to Brand("Disney+", R.drawable.stream_logo_disney, 337),
        "apple tv+" to Brand("Apple TV+", R.drawable.stream_logo_apple, 350),
        "apple tv plus" to Brand("Apple TV+", R.drawable.stream_logo_apple, 350),
        "prime video" to Brand("Prime Video", R.drawable.stream_logo_prime, 9),
        "amazon" to Brand("Prime Video", R.drawable.stream_logo_prime, 9),
        "hbo" to Brand("HBO", R.drawable.stream_logo_hbo, 0),
        "hulu" to Brand("Hulu", R.drawable.stream_logo_hulu, 15),
        "paramount+" to Brand("Paramount+", R.drawable.stream_logo_paramount, 531),
        "paramount plus" to Brand("Paramount+", R.drawable.stream_logo_paramount, 531),
        "peacock" to Brand("Peacock", R.drawable.stream_logo_peacock, 386),
        "crunchyroll" to Brand("Crunchyroll", R.drawable.stream_logo_crunchyroll, 283),
    )

    fun of(name: String): Brand =
        MAP[name.trim().lowercase()] ?: Brand(name, 0, 0)
}
