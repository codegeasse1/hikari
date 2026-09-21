# Personal catalogs (collections), in full

A **personal catalog** ("collection") is the user's own tree: a collection holds
**folders**, a folder holds **catalog sources**. Everything about it lives in
`data/Models.kt` (`Collection`, `CollectionFolder`, `CatalogSource`), is stored by
`data/AppStore.kt` (`COLLECTIONS`, one JSON array), is built by
`ui/screens/CollectionScreens.kt`, is loaded by `data/CollectionsRepository.kt`,
and is drawn on Home by `HomeScreen.kt`.

This file is the reference for what each field means, where it is edited, and how
it maps to and from the reference client's export format
(`data/NuvioCollectionsImport.kt` / `NuvioCollectionsExport`).

---

## 1. The model

### `Collection`

| field | meaning | edited in |
| --- | --- | --- |
| `id` | `col…`, minted by `AppStore.newId` | — |
| `name` | the collection's name (also its Home row title) | collection editor |
| `folders` | ordered list — **position is the order**, there is no order field | collection editor (up/down + drag) |
| `coverKind` / `coverValue` | `none` / `emoji` / `url` / `gif` (`CoverKinds`) | editor's cover card |
| `tileShape` | `poster` / `square` / `wide` (`TileShapes`) | editor's cover card |
| `pinToTop` | draw this catalog's folders on Home even when nothing is picked | editor's "Home & layout" card |
| `viewMode` | `rows` (one shelf per folder) or `tabs` (`CollectionViewModes`) | editor's "Home & layout" card |
| `showAllTab` | in Tabs mode, add an "All" tab with every folder's catalogs | editor's "Home & layout" card |
| `backdropUrl` | wide hero image for the collection's own pages | editor's "Home & layout" card |

### `CollectionFolder`

| field | meaning |
| --- | --- |
| `id`, `name` | identity + display name |
| `sources` | ordered `CatalogSource` list — **position is the order** |
| `coverKind` / `coverValue` | as the collection (its own cover wins; otherwise it borrows the collection's) |
| `tileShape` | as the collection |
| `hideTitle` | draw the tile as artwork only (the name still shows in the editor and on the folder's page) |
| `gifAlways` | an animated cover keeps animating on every device, whatever that device's setting says |
| `heroBackdropUrl` | wide image drawn above the folder page's header |
| `titleLogoUrl` | transparent wordmark drawn in place of the folder's title text |

### `CatalogSource` — three kinds

* `PROVIDER` — one catalog of one installed extension (`providerId`,
  `catalogId`, `type`, `rawType`).
* `TMDB` — a TMDB query: either a built-in preset (`tmdbPreset`, e.g. `marvel`)
  or a hand-built `TmdbSpec` (`tmdbSpec`, JSON).
* `ITEMS` — a list of titles the user imported, held as `itemsJson`. Needs no
  network and no extension; its items are stored as `providerId = "tmdb"` so
  they open and resolve like any other title.

---

## 2. `TmdbSpec` and the discover filters

`TmdbSpec.type` is one of `PRESET`, `TITLE`, `LIST`, `COMPANY`, `NETWORK`,
`COLLECTION`, `PERSON`, `DIRECTOR`, `DISCOVER` (`TmdbSourceType`). All of them
are implemented in `data/TmdbSources.kt`; `LIST` uses `/list/{id}`,
`DIRECTOR` walks `/person/{id}/combined_credits` filtering `job == "Director"`,
`COMPANY`/`NETWORK` are `/discover` with `with_companies`/`with_networks`.

For `DISCOVER` (the editor's **Custom** chip), the full filter set is:

| `TmdbSpec` field | TMDB parameter | notes |
| --- | --- | --- |
| `genre` | `with_genres` | the single-genre chip; ignored when `genresText` is set |
| `genresText` | `with_genres` | id list; `,` = AND, `|` = OR |
| `genresExclude` | `without_genres` | |
| `year` | `primary_release_year` / `first_air_date_year` | |
| `dateFrom` / `dateTo` | `primary_release_date.gte/.lte` (film) or `first_air_date.gte/.lte` (tv) | `YYYY-MM-DD` |
| `ratingMin` / `ratingMax` | `vote_average.gte/.lte` | |
| `votesMin` | `vote_count.gte` | also suppresses the built-in 200-vote floor when set |
| `language` | `with_original_language` | lowercased |
| `country` | `with_origin_country` | uppercased |
| `keywords` / `keywordsExclude` | `with_keywords` / `without_keywords` | |
| `companies` / `companiesExclude` | `with_companies` / `without_companies` | ignored for a `COMPANY` source's own `filterKey` |
| `networks` | `with_networks` | ignored for a `NETWORK` source's own `filterKey` |
| `providers` / `providersExclude` | `with_watch_providers` / `without_watch_providers` | needs `region` |
| `region` | `watch_region` | `US`, `GB`, `IN`… |

Every field is a **string that is empty when unset**, and an empty field sends no
parameter at all — which is why adding them changed no existing row. They are all
part of `TmdbSpec.identity`, so two sources differing only in filters are two
sources. They are edited in the source sheet's **Advanced filters** section
(`TMDB_ADV_FILTERS` in `CollectionScreens.kt`, a list of
`(state key, label, hint)`), folded away by default.

---

## 3. How Home draws it

1. `HomeViewModel.rowsFlowFor` returns **extension** catalogs only. A pick made
   solely of collections yields an empty feed — it must not fall through to
   `homeRowsStreamingFor(emptySet())`, which would stack every installed
   extension's home page under the user's catalogs.
2. The screen draws one `CollectionFoldersOnHome` row per collection in
   `collectionFolderRows`:
   * every collection in the Home pick (`collection:<id>` keys) — so a multi
     pick shows each collection's folders under its own name, and
   * every `pinToTop` collection while Home is on "All" (which is how a pinned
     catalog appears on Home without being picked).
   A folder-less collection is skipped here (a pick still gets the empty state).
3. Tapping a folder opens `Routes.collectionView(collectionId, folderId)` →
   `CollectionFolderContent`, whose shelves are `CollectionsRepository.folderRowsOnce`.
4. `Routes.collectionView(id)` with no folder: `viewMode == TABS` opens
   `CollectionTabsContent` (tab strip + `FolderRowList`), otherwise
   `CollectionFoldersPage` (the folder tile grid).

`Routes.collectionGrid(id)` is "Show all": every folder's every catalog as one
flat grid (`CollectionsRepository.allRows`).

---

## 4. Ordering

There is no order field anywhere. `Collection.folders`, `CollectionFolder.sources`
and the order of the `collections` list itself **are** the order, because
`AppStore.encodeCollections` writes the list it is given and
`CollectionsRepository` publishes rows in list order.

* the collection editor's `MoveButtons` pair reorders folders;
* the folder editor's `MoveButtons` pair (and its long-press drag) reorders
  sources;
* the creator list's `MoveButtons` pair reorders collections — which is also
  their order on Home.

---

## 5. Import / export

`NuvioCollectionsImport` reads the reference client's export:

```json
[ { "title": "Networks",
    "backdropImageUrl": "…", "pinToTop": true, "viewMode": "TABBED_GRID",
    "showAllTab": true,
    "folders": [
      { "title": "Netflix", "tileShape": "LANDSCAPE",
        "coverImageUrl": "…", "focusGifUrl": "…", "coverEmoji": "🎬",
        "hideTitle": false, "gifAlways": false,
        "heroBackdropUrl": "…", "titleLogoUrl": "…",
        "sources": [
          { "provider": "tmdb", "tmdbSourceType": "NETWORK", "tmdbId": 213,
            "mediaType": "TV", "sortBy": "popularity.desc",
            "filters": { "withGenres": "18", "releaseDateGte": "1990-01-01" } },
          { "provider": "addon", "addonId": "com.linvo.cinemeta",
            "catalogId": "top", "type": "movie" },
          { "provider": "trakt", "traktListId": 123 } ] } ] } ]
```

* `provider: "tmdb"` → a `TmdbSpec`; `tmdbSourceType` maps to
  `DISCOVER`/`COMPANY`/`NETWORK`/`COLLECTION`/`PERSON`/`LIST`/`DIRECTOR`/`TITLE`.
* `provider: "addon"`/`"stremio"` → matched against **installed** extensions
  (see `matchInstalledCatalog`); a miss is dropped and counted.
* `provider: "trakt"` → dropped and counted: Hikari has no Trakt engine.
* A folder whose `items`/`titles` array is present carries titles directly and
  becomes a `CatalogSourceKind.ITEMS` source, needing nothing installed.
* `viewMode`: `TABBED_GRID` → `CollectionViewModes.TABS`, `ROWS` → `ROWS`.
* `tileShape`: `LANDSCAPE` → `wide`, `square`/`SQUARE` → `square`, else `poster`.

`NuvioCollectionsExport.encode` writes the same shape back (collection →
`pinToTop`/`viewMode`/`showAllTab`/`backdropImageUrl`; folder → covers,
`hideTitle`, `gifAlways`, hero URLs; source → `provider` + its own fields, with
TMDB filters under `filters`). That is what the creator's clipboard button copies
to the clipboard, so an export round-trips through the importer.

Anything a `TmdbSpec` cannot express is not silently faked: it is dropped and
COUNTED, and the import summary names it (`catalogs are from an addon that is not
installed`, `Trakt lists (not supported)`). The import preview also warns, before
anything is imported, when a collection's sources are all catalogs this install
does not have.

---

## 6. Animated covers

`CoverKinds.GIF` is a URL that animates. Whether it *does* animate is decided in
`FolderTile`:

```
animateGif = folder.gifAlways || AppStore.gifAnimFlow()   // device setting, default true
```

so the folder's own switch ("Always animate", shown only when the cover is a
GIF) wins over the device's, and the device's (`Settings → App Layout → Animate
covers`) is what a TV stick can turn off without editing what it imported.

Held still, the frame comes from `PosterLoader.stillModel(url)`, which fetches the
URL and decodes the **first frame** with `BitmapFactory.decodeByteArray` (Coil
would return an animating drawable). It follows the same waiting-counter pattern
as a data-URI poster, so the tile is recomposed when its frame lands. `CoverArt`
passes the still model only for `GIF` covers with `animateGif == false`.

---

## 7. Not implemented, on purpose

* **Trakt** (`provider: "trakt"`): no engine, so import drops the source and
  counts it. See `SettingsScreen`'s roadmap line.
* **Account / cloud sync**: Hikari has no accounts; a catalog moves between
  installs through the copy-out JSON and the import.
* **Per-catalog genre filtering of extension catalogs**: an arbitrary extension
  catalog has no genre field in the protocol (`CatalogRef` carries none); only
  Stremio-addon catalogs accept a genre extra.
* **A hero VIDEO on a folder** (the reference app's `heroVideoUrl`): artwork
  only.
