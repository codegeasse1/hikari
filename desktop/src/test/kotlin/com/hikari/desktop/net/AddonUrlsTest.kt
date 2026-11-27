package com.hikari.desktop.net

import org.junit.Assert.assertEquals
import org.junit.Test

class AddonUrlsTest {
    @Test
    fun addsManifestPathToAddonRoot() {
        assertEquals(
            "https://addons.example/manifest.json",
            AddonUrls.manifestUrl("https://addons.example/"),
        )
    }

    @Test
    fun addsHttpsSchemeToBareHost() {
        assertEquals(
            "https://addons.example/manifest.json",
            AddonUrls.manifestUrl("addons.example"),
        )
    }

    @Test
    fun normalizesBareStremioAddonLinkToHttpsManifest() {
        assertEquals(
            "https://addons.example/stremio/abc/manifest.json",
            AddonUrls.manifestUrl("stremio://addons.example/stremio/abc"),
        )
    }

    @Test
    fun decodesStremioInstallLinkAndDropsItsQuery() {
        assertEquals(
            "https://addons.example/stremio/abc/manifest.json",
            AddonUrls.manifestUrl(
                "stremio://https%3A%2F%2Faddons.example%2Fstremio%2Fabc%2Fmanifest.json%3Fkey%3Dsecret",
            ),
        )
    }

    @Test
    fun decodesStremioPathAndAcceptsCaseInsensitiveWrapperScheme() {
        assertEquals(
            "https://addons.example/stremio/abc/manifest.json",
            AddonUrls.manifestUrl("StReMiO://addons.example/stremio%2Fabc%2Fmanifest.json?install=1"),
        )
    }

    @Test
    fun preservesExplicitHttpSchemeInStremioLink() {
        assertEquals(
            "http://addons.example/manifest.json",
            AddonUrls.manifestUrl("stremio://http%3A%2F%2Faddons.example%2Fmanifest.json"),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsStremioLinksWithUnsupportedEmbeddedSchemes() {
        AddonUrls.manifestUrl("stremio://file%3A%2F%2Faddons.example%2Fmanifest.json")
    }

    @Test
    fun keepsManifestPathAndAddonConfigurationQuery() {
        assertEquals(
            "https://addons.example/stremio/abc/manifest.json?key=secret",
            AddonUrls.manifestUrl("https://addons.example/stremio/abc/manifest.json?key=secret"),
        )
    }

    @Test
    fun buildsSearchCatalogUrlWithEncodedSearchAndOriginalAddonQuery() {
        assertEquals(
            "https://addons.example/stremio/abc/catalog/series/top/search=one%20piece.json?key=secret",
            AddonUrls.catalogUrl(
                manifestUrl = "https://addons.example/stremio/abc/manifest.json?key=secret",
                type = "series",
                catalogId = "top",
                search = "one piece",
            ),
        )
    }

    @Test
    fun appendsSkipForLaterCatalogPages() {
        assertEquals(
            "https://addons.example/catalog/movie/trending/skip=100.json",
            AddonUrls.catalogUrl(
                manifestUrl = "https://addons.example/manifest.json",
                type = "movie",
                catalogId = "trending",
                skip = 100,
            ),
        )
    }

    @Test
    fun combinesSearchAndPaginationInTheExtraPath() {
        assertEquals(
            "https://addons.example/catalog/movie/top/search=one%20piece&skip=200.json",
            AddonUrls.catalogUrl(
                manifestUrl = "https://addons.example/manifest.json",
                type = "movie",
                catalogId = "top",
                search = "one piece",
                skip = 200,
            ),
        )
    }

    @Test
    fun buildsMetadataUrlForAnEpisodeAndKeepsAddonQuery() {
        assertEquals(
            "https://addons.example/stremio/abc/meta/series/tt123:1:2.json?key=secret",
            AddonUrls.metaUrl(
                manifestUrl = "https://addons.example/stremio/abc/manifest.json?key=secret",
                type = "series",
                id = "tt123:1:2",
            ),
        )
    }

    @Test
    fun buildsStreamUrlFromAddonRoot() {
        assertEquals(
            "https://addons.example/stream/movie/tt123.json",
            AddonUrls.streamUrl(
                manifestUrl = "https://addons.example/",
                type = "movie",
                id = "tt123",
            ),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonHttpAddonUrls() {
        AddonUrls.manifestUrl("file:///etc/passwd")
    }
}
