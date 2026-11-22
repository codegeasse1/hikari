package com.hikari.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

import com.hikari.app.manga.MangaOffline

/**
 * Pure-JVM regression guards (no Android, no network, no org.json — that class
 * stays off the classpath on purpose). They lock the two production-logo and
 * anime-identity fixes:
 *
 *  1. Both sides of the Production logo backfill must key companies the same
 *     way (the row lost every logo when the map was keyed with spaces and
 *     looked up without), and truncated display variants must still match
 *     via the guarded prefix rule without short names fuzzy-matching.
 *  2. The anime identity pipeline's pure pieces: exact titles pass the trust
 *     bar, unrelated ones do not, and dub/sub merges preserve release dates.
 */
class PipelineUnitTest {

    @Test
    fun companyKey_hasNoSpacesOrPunctuation() {
        assertEquals("nighttrainmedia", TmdbMeta.companyKey("Night Train Media"))
        assertEquals("universalpictures", TmdbMeta.companyKey("Universal Pictures"))
    }

    @Test
    fun companyKey_matchesAcrossPunctuationVariants() {
        // Addon side vs TMDB side punctuate the same studio differently —
        // the shared key form erases that (the old map/lookup pair used
        // different forms and missed every logo).
        assertEquals("nighttrainmedia", TmdbMeta.companyKey("night-train media!"))
        assertEquals(
            TmdbMeta.companyKey("Marvel Television"),
            TmdbMeta.companyKey("marvel-television!"),
        )
        assertTrue(TmdbMeta.companyKey("Syncopy").isNotEmpty())
        assertEquals("", TmdbMeta.companyKey("   "))
    }

    @Test
    fun companyNamesMatch_toleratesTruncationButNotShortNames() {
        // Truncated display variant still lends its logo (guarded prefix).
        assertTrue(
            TmdbMeta.companyNamesMatch(
                "International Media Funds Ma…",
                "International Media Funds",
            )
        )
        assertTrue(TmdbMeta.companyNamesMatch("Marvel Television", "marvel-television!"))
        // Short names must NOT fuzzy-match longer studios.
        assertFalse(TmdbMeta.companyNamesMatch("Pixar", "Pixar Animation Studios"))
        assertFalse(TmdbMeta.companyNamesMatch("Syncopy", "Universal Pictures"))
        assertFalse(TmdbMeta.companyNamesMatch("", "Pixar"))
        assertFalse(TmdbMeta.companyNamesMatch("   ", "Pixar"))
    }

    @Test
    fun companyLogoFor_exactThenTruncatedThenMiss() {
        val map = mapOf(
            "nighttrainmedia" to "https://img/ntm.png",
            "internationalmediafunds" to "https://img/imf.png",
        )
        assertEquals(
            "https://img/ntm.png",
            TmdbMeta.companyLogoFor(map, "Night Train Media"),
        )
        assertEquals(
            "https://img/imf.png",
            TmdbMeta.companyLogoFor(map, "International Media Funds Ma…"),
        )
        assertEquals(null, TmdbMeta.companyLogoFor(map, "Syncopy"))
        assertEquals(null, TmdbMeta.companyLogoFor(map, "   "))
        assertEquals(null, TmdbMeta.companyLogoFor(emptyMap(), "Pixar"))
    }

    @Test
    fun titleScore_exactMatchClearsTrustBar() {
        // isTmdbTrusted accepts >= 40; the bar must pass for exact titles and
        // fail for unrelated ones, or shelves blank on good data.
        assertEquals(60, TmdbMeta.titleScore("The Odyssey", "The Odyssey"))
        assertTrue(TmdbMeta.titleScore("The Odyssey", "The Odyssey") >= 40)
        assertEquals(0, TmdbMeta.titleScore("The Odyssey", " unrelated xyz"))
    }

    @Test
    fun titleScore_prefixAndNumerals() {
        assertEquals(40, TmdbMeta.titleScore("One Piece", "One Piece: Red"))
        assertEquals(
            TmdbMeta.normalizeTitle("Ramayana: Part Two"),
            TmdbMeta.normalizeTitle("Ramayana Part 2"),
        )
    }

    @Test
    fun dubSubMerge_preservesDubReleaseDate() {
        // Acceptance: English-dub release dates remain unchanged. The merge
        // must pick the first non-blank date (the dub row's), not synthesize.
        val dub = Episode(number = 1, id = "x#s1e1-d", name = "Episode 1 (English Dub)", released = "2024-01-01", season = 1)
        val sub = Episode(number = 1, id = "x#s1e1-s", name = "Episode 1 [Sub]", released = null, season = 1)
        val out = EpisodeDubSub.mergedFor("k", listOf(dub, sub))
        assertEquals(1, out.size)
        assertEquals("2024-01-01", out[0].released)
        assertFalse(out[0].name.orEmpty().contains("dub", ignoreCase = true))
        assertFalse(out[0].name.orEmpty().contains("sub", ignoreCase = true))
    }

    @Test
    fun dubSubMerge_keepsFoldedRowsForStreamExpansion() {
        val dub = Episode(number = 2, id = "x#s1e2-d", name = "Episode 2 (Dub)", season = 1)
        val sub = Episode(number = 2, id = "x#s1e2-s", name = "Episode 2 (Sub)", season = 1)
        val out = EpisodeDubSub.mergedFor("k2", listOf(dub, sub))
        assertEquals(1, out.size)
        // The folded-away sibling must still be reachable for audio expansion.
        assertEquals(2, EpisodeDubSub.variantsFor("k2", 1, 2).size)
    }

    @Test
    fun dubSubMerge_leavesUnrelatedRowsAlone() {
        val a = Episode(number = 1, id = "a", name = "The Beginning", season = 1)
        val b = Episode(number = 1, id = "b", name = "Something Else Entirely", season = 1)
        assertEquals(2, EpisodeDubSub.mergedFor("k3", listOf(a, b)).size)
    }

    @Test
    fun liveActionVeto_savesIronManFromItsAnimeNamesake() {
        // "Iron Man" (2008, Action/Sci-Fi) vs Marvel Anime's exact namesake:
        // TMDB is confident and non-Animation, so the anime pick is vetoed and
        // the live-action page keeps its cast/trailers/shelves.
        assertTrue(
            DonghuaDetails.liveActionVeto(
                tmdbNames = listOf("Iron Man"),
                titleVariants = listOf("Iron Man"),
                itemYear = 2008,
                tmdbYear = 2008,
                genreIds = listOf(28, 878, 12),
                genreNames = listOf("Action", "Science Fiction", "Adventure"),
            )
        )
    }

    @Test
    fun liveActionVeto_keepsTrueAnimeAndFailsClosed() {        // A real anime film carries the Animation genre: no veto, anime wins.
        assertFalse(
            DonghuaDetails.liveActionVeto(
                tmdbNames = listOf("Your Name."),
                titleVariants = listOf("Your Name."),
                itemYear = 2016,
                tmdbYear = 2016,
                genreIds = listOf(16, 18, 14),
                genreNames = listOf("Animation", "Drama", "Fantasy"),
            )
        )
        // Weak title match: no veto (old behaviour — anime may still win).
        assertFalse(
            DonghuaDetails.liveActionVeto(
                tmdbNames = listOf("Totally Different Film"),
                titleVariants = listOf("Iron Man"),
                itemYear = 2008,
                tmdbYear = 2008,
                genreIds = listOf(28),
                genreNames = listOf("Action"),
            )
        )
        // Year clash: no veto.
        assertFalse(
            DonghuaDetails.liveActionVeto(
                tmdbNames = listOf("Iron Man"),
                titleVariants = listOf("Iron Man"),
                itemYear = 2008,
                tmdbYear = 2020,
                genreIds = listOf(28),
                genreNames = listOf("Action"),
            )
        )
        // Unknown genres: fail closed, no veto.
        assertFalse(
            DonghuaDetails.liveActionVeto(
                tmdbNames = listOf("Iron Man"),
                titleVariants = listOf("Iron Man"),
                itemYear = 2008,
                tmdbYear = 2008,
                genreIds = emptyList(),
                genreNames = emptyList(),
            )
        )
    }

    @Test
    fun offlineExt_prefersContentTypeThenUrlThenMagic() {
        // Offline page files need the right extension: reply type wins, then
        // the URL, then the bytes' magic, then jpg.
        assertEquals("png", MangaOffline.extForContentType("image/png", "https://x/y", null))
        assertEquals("jpg", MangaOffline.extForContentType("image/jpeg", "https://x/y.webp", null))
        assertEquals("webp", MangaOffline.extForContentType(null, "https://x/y.webp?sig=1", null))
        assertEquals(
            "png",
            MangaOffline.extForContentType(
                null, "https://x/y",
                byteArrayOf(0x89.toByte(), 0x50.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
            )
        )
        assertEquals("jpg", MangaOffline.extForContentType(null, "https://x/y", null))
    }
}
