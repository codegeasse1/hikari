package com.hikari.desktop

import com.hikari.desktop.data.AddonCatalog
import com.hikari.desktop.data.AddonManifest
import com.hikari.desktop.data.AddonResource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class HomeCatalogSelectionTest {
    @Test
    fun showsCatalogsFromEveryProviderWhenAllProvidersAreSelected() {
        val sources = listOf(
            source(
                url = "https://catalog-a.example/manifest.json",
                name = "Catalog A",
                catalogs = listOf(
                    AddonCatalog("movie", "popular", "Popular Movies"),
                    AddonCatalog("series", "popular", "Popular Series"),
                ),
            ),
            source(
                url = "https://pengu.example/manifest.json",
                name = "PenguPlay",
                catalogs = emptyList(),
                resources = listOf(AddonResource("stream")),
            ),
            source(
                url = "https://catalog-b.example/manifest.json",
                name = "Catalog B",
                catalogs = listOf(AddonCatalog("movie", "indonesian", "Indonesian Movies")),
            ),
        )

        val catalogs = selectHomeCatalogs(sources, selectedProviderUrl = null, mediaFilter = HomeMediaFilter.ALL)

        assertEquals(
            listOf(
                "Catalog A" to "Popular Movies",
                "Catalog A" to "Popular Series",
                "Catalog B" to "Indonesian Movies",
            ),
            catalogs.map { it.addonName to it.catalog.name },
        )
    }

    @Test
    fun filmFilterShowsOnlyMovieAndFilmCatalogsAcrossProviders() {
        val sources = listOf(
            source(
                url = "https://catalog.example/manifest.json",
                name = "Catalog",
                catalogs = listOf(
                    AddonCatalog("movie", "popular", "Popular"),
                    AddonCatalog("film", "indonesian", "Indonesian"),
                    AddonCatalog("series", "shows", "Shows"),
                ),
            ),
        )

        val catalogs = selectHomeCatalogs(sources, selectedProviderUrl = null, mediaFilter = HomeMediaFilter.MOVIES)

        assertEquals(listOf("Popular", "Indonesian"), catalogs.map { it.catalog.name })
    }

    @Test
    fun seriesFilterShowsOnlySeriesCatalogs() {
        val sources = listOf(
            source(
                url = "https://catalog.example/manifest.json",
                name = "Catalog",
                catalogs = listOf(
                    AddonCatalog("movie", "popular", "Popular"),
                    AddonCatalog("series", "shows", "Shows"),
                ),
            ),
        )

        val catalogs = selectHomeCatalogs(sources, selectedProviderUrl = null, mediaFilter = HomeMediaFilter.SERIES)

        assertEquals(listOf("Shows"), catalogs.map { it.catalog.name })
    }

    @Test
    fun selectedProviderLimitsCatalogsToThatProvider() {
        val sources = listOf(
            source(
                url = "https://catalog-a.example/manifest.json",
                name = "Catalog A",
                catalogs = listOf(AddonCatalog("movie", "popular", "Popular A")),
            ),
            source(
                url = "https://catalog-b.example/manifest.json",
                name = "Catalog B",
                catalogs = listOf(AddonCatalog("movie", "popular", "Popular B")),
            ),
        )

        val catalogs = selectHomeCatalogs(
            sources,
            selectedProviderUrl = "https://catalog-b.example/manifest.json",
            mediaFilter = HomeMediaFilter.ALL,
        )

        assertEquals(listOf("Popular B"), catalogs.map { it.catalog.name })
    }

    @Test
    fun allCatalogsRemainAvailableWhenTheHomeShowsOnlyItsEightShelfPreview() {
        val sources = (1..11).map { index ->
            val url = "https://catalog-$index.example/manifest.json"
            source(
                url = url,
                name = "Catalog $index",
                catalogs = listOf(AddonCatalog("movie", "popular", "Popular $index")),
            )
        }

        val inventory = homeCatalogInventory(sources, selectedProviderUrl = null, mediaFilter = HomeMediaFilter.ALL)

        assertEquals(11, inventory.allCatalogs.size)
        assertEquals(8, inventory.homeShelves.size)
        assertEquals(
            listOf("Catalog 9", "Catalog 10", "Catalog 11"),
            inventory.allCatalogs.drop(inventory.homeShelves.size).map { it.addonName },
        )
    }

    @Test
    fun homeSelectionMapsToAStableProviderNeutralCatalogReference() {
        val first = HomeCatalogSelection(
            addonUrl = "https://catalog-a.example/manifest.json",
            addonName = "Catalog A",
            catalog = AddonCatalog("movie", "popular", "Popular"),
        )
        val second = first.copy(addonUrl = "https://catalog-b.example/manifest.json", addonName = "Catalog B")

        val firstRef = first.toCatalogReference()
        val secondRef = second.toCatalogReference()

        assertEquals(CatalogProviderKind.STREMIO, firstRef.provider)
        assertEquals(first.addonUrl, firstRef.sourceId)
        assertEquals(first.catalog.type, firstRef.mediaType)
        assertEquals(first.catalog.id, firstRef.catalogId)
        assertNotEquals(firstRef.stableIdentity, secondRef.stableIdentity)
    }

    private fun source(
        url: String,
        name: String,
        catalogs: List<AddonCatalog>,
        resources: List<AddonResource> = emptyList(),
    ) = SourceState(
        url = url,
        manifest = AddonManifest(
            addonUrl = url,
            name = name,
            description = null,
            catalogs = catalogs,
            resources = resources,
        ),
    )
}
