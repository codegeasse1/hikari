# Hikari

**Hikari** (光) — a universal streaming app. One player, every ecosystem:

- **Stremio addons** — add any addon URL, browse catalogs, search, play.
- **Universal scrapers** — JSON-rule site scrapers, no code needed.
- **CloudStream .cs3 plugins** — Stage 2 ✅ — your existing `.cs3` extensions run unchanged.
- **SkyStream extensions** — Stage 3.

Modern Material 3 UI, HLS/DASH playback with per-source headers and subtitles, built in pure Kotlin + Compose.

## Installing the app

Every push to `main` auto-builds `app-debug.apk` and publishes it to:

- **Release:** <https://github.com/codegeasse1/hikari/releases/download/continuous/hikari-debug.apk>
- **build branch:** <https://github.com/codegeasse1/hikari/raw/build/hikari-debug.apk>

Download it on the phone and open it (allow "install unknown apps").

## Add extensions

**Stremio addon:** Extensions → *Add Stremio addon* → paste the addon URL (it must serve `manifest.json`).

**Universal scraper:** Extensions → *Add scraper* → paste a JSON config. Format:

```json
{
  "name": "MySite",
  "baseUrl": "https://example.com",
  "homeUrl": "/",
  "catalogs": [ { "id": "home", "name": "Home", "type": "movie" } ],
  "search": {
    "url": "/search?q={query}&page={page}",
    "item": ".result-item",
    "title": ".title",
    "href": "a@href",
    "poster": "img@src"
  },
  "detail": {
    "title": "h1",
    "poster": ".poster img@src",
    "overview": ".desc",
    "type": "series"
  },
  "episodes": {
    "url": "{href}",
    "item": ".episode",
    "number": "data-ep",
    "href": "a@href"
  },
  "streams": {
    "video": "video@src",
    "m3u8": "source[type*=m3u8]@src",
    "iframe": "iframe@src"
  }
}
```

Rules use CSS selectors; `element@attr` means read an attribute instead of text. `{query}`, `{page}`, `{href}` are substituted at runtime. Streams can be direct `video`/m3u8 or hidden inside an `iframe` (followed recursively).

**CloudStream .cs3 plugin:** Extensions → *Install .cs3 from URL* (or *Pick .cs3 file*). Paste a direct link to a compiled `.cs3`, e.g.:

```
https://github.com/codegeasse1/codegeasse-cloudstream-repos/raw/builds/JustAnimeProvider.cs3
```

The plugin's providers appear in Home/Search and play like any other source. One `.cs3` can register several providers (each gets its own card, toggle and delete). The plugin files are stored in the app's private `filesDir/cs3/`, so they survive app restarts.

## Roadmap

- **Stage 1 (done):** app core, Material 3 UI, Stremio addons, universal scrapers, Media3 player with headers + subtitles.
- **Stage 2 (done):** CloudStream `.cs3` plugin loader (native cloudstream3 API compatibility layer: MainAPI, models, factories, nicehttp `app`, M3u8Helper, loadExtractor, WebViewResolver, CloudflareKiller).
- **Stage 3:** SkyStream shims, torrent engine (Stremio infoHash streams), downloads, favorites/continue-watching, Trakt.

## Build

```bash
# CI builds app-debug.apk on every push to main (see .github/workflows/build.yml)
# locally:
gradle assembleDebug
```
