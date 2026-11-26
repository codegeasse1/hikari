# Hikari for Windows

This is a separate native desktop MVP. It does not wrap or require the Android APK, and it does not change the Android `:app` module.

The first milestone supports Stremio add-on catalogs, searching, title details, and handing direct stream URLs to VLC/MPV or the default browser. Other provider ecosystems and an embedded media player are outside this milestone.

Home shows up to eight catalog shelves as a preview. Open **Semua katalog** to browse every catalog declared by installed Stremio add-ons. Catalog pages use Stremio's paged responses. The APK's separate TMDB detail and stream-resolution paths are not ported yet, so this slice is not full APK parity.

Catalog browsing also supports a scoped query when that catalog's manifest explicitly advertises the `search` extra. A new query restarts at page one, and later pages keep the query with Stremio's skip offset. Catalogs without that declaration do not show the in-catalog search control. This is another incremental parity slice, not full APK parity.

Search keeps the 20 most recent submitted queries locally. A query can be run again from its chip, removed by itself, or cleared with the rest of the history. Loaded results can be filtered locally by media type (Semua, Film, Serial, Anime), genre, year, and minimum rating. Unrated titles remain visible when a rating threshold is active. Filter choices use metadata returned by the installed add-ons, so they only cover loaded search results. Search can target all installed add-ons or one selected add-on; changing the scope runs the current query against that selection. The source picker is single-select, not Android's full provider-filter UI. TMDB-backed discovery and its broader filters are not ported. Desktop is not at full APK parity.

Desktop **Pustaka** stores compact title snapshots in this Windows account's local preferences, newest first. It includes the four Android default categories, custom category creation, rename and delete, multi-category assignment, category filtering, and persistent ordering with keyboard-operable **Naik** and **Turun** actions. Trackers, manga, watch history, and continue-watching are still missing, so desktop is not at full APK parity.

## Build and run

Use Gradle 8.11.1 or newer with JDK 17 or newer:

```sh
gradle :desktop:test
gradle :desktop:run
gradle :desktop:packageMsi
gradle :desktop:packageExe
```

The MSI and EXE installers must be generated on a Windows runner. CI builds both from `windows-latest` and uploads them as workflow artifacts; no Android build or release is changed by that workflow.
