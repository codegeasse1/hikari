# Hikari

**Hikari** (光) — a universal streaming app. One player, every ecosystem:

- **Stremio addons** — add any addon URL, browse catalogs, search, play.
- **Universal scrapers** — JSON-rule site scrapers, no code needed.
- **CloudStream .cs3 plugins** — coming in Stage 2 (your existing `.cs3` extensions will run unchanged).
- **SkyStream extensions** — Stage 3.

Modern Material 3 UI, HLS/DASH playback with per-source headers and subtitles, built in pure Kotlin + Compose.

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

## Roadmap

- **Stage 1 (done):** app core, Material 3 UI, Stremio addons, universal scrapers, Media3 player with headers + subtitles.
- **Stage 2:** CloudStream `.cs3` plugin loader, torrent engine (Stremio infoHash streams), downloads, favorites/continue-watching, Trakt.
- **Stage 3:** SkyStream shims, scriptable scrapers, casting, widgets.

## Build

```bash
# CI builds app-debug.apk on every push to main (see .github/workflows/build.yml)
# locally:
gradle assembleDebug
```
