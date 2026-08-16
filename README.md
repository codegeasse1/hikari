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

**Universal scraper:** Extensions → *Add scraper* → paste a JSON config. Two modes — **HTML mode** for classic sites with server-rendered pages, and **JSON-API mode** for single-page apps that only serve data through a JSON API.

### HTML mode — selector rules

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

### JSON-API mode — for SPA / API-only sites

Many modern streaming sites (JustAnime, etc.) are React/Vue single-page apps: their pages return an empty `<div id="root">`, so there is no HTML to scrape. Everything comes from a JSON API. For those, add an `"api"` block — the scraper talks to the API instead of parsing HTML. It sends the configured `headers` on every call (many APIs reject requests without the right `Origin`/`Referer`/`User-Agent`), and can route through the site's own proxy when the API is Cloudflare-gated.

```json
{
  "name": "JustAnime",
  "baseUrl": "https://justanime.to",
  "api": {
    "base": "https://core.justanime.to/api",
    "proxy": "https://neko.justanime.to/m3u8-proxy",
    "headers": {
      "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
      "Accept": "application/json, text/plain, */*",
      "Referer": "https://justanime.to/",
      "Origin": "https://justanime.to"
    },
    "catalogs": [
      { "id": "trending", "name": "Trending", "type": "series" },
      { "id": "popular", "name": "Popular", "type": "series" },
      { "id": "latestEpisode", "name": "Latest Episodes", "type": "series" },
      { "id": "airing", "name": "Airing Now", "type": "series" },
      { "id": "upcoming", "name": "Upcoming", "type": "series" },
      { "id": "favourite", "name": "Favourites", "type": "series" }
    ],
    "homePath": "/home",
    "searchPath": "/search",
    "searchResults": "results",
    "detailPath": "/anime/{id}",
    "detailData": "data",
    "episodesPath": "/anime/{id}/episodes",
    "episodesPageParam": "page",
    "episodesItems": "episodes",
    "episodesHasNext": "hasNextPage",
    "episodesMaxPages": 30,
    "episodeNumber": "number",
    "episodeName": "title",
    "streamsPath": "/watch/{id}/episode/{ep}/anineko/{lang}/hd1",
    "streamsLangs": "sub,dub",
    "streamsSources": "sources",
    "streamsUrl": "url",
    "streamsQuality": "quality",
    "streamsIsM3u8": "isM3U8",
    "streamsSubtitles": "subtitles",
    "streamsSubUrl": "url",
    "streamsSubLang": "lang",
    "streamsHeaders": "headers",
    "proxyStreams": true
  }
}
```

This is a working config — paste it into *Add scraper* and JustAnime appears in Home/Search.

#### `api` field reference

| field | default | meaning |
|---|---|---|
| `base` | — (required) | API root URL, e.g. `https://core.justanime.to/api`. |
| `proxy` | — | Optional URL that fetches the API on the site's behalf. The scraper tries `base` directly first, then falls back to `proxy?url=…&headers=…` (twice). Also used to wrap stream URLs when `proxyStreams` is on. |
| `headers` | — | HTTP headers sent with every API call — include `Origin`, `Referer`, `User-Agent`, `Accept`. |
| `catalogs` | — | Array of `{ id, name, type }` (`type`: `movie` or `series`) shown as Home rows. |
| `homePath` | `/home` | Endpoint that returns each home section as a keyed array; the catalog `id` is used as the array key. |
| `searchPath` | `/search` | Search endpoint. |
| `searchResults` | `results` | Response key holding the results array. |
| `searchQueryParam` / `searchPageParam` | `query` / `page` | Query and page parameter names. |
| `detailPath` | `/anime/{id}` | Detail endpoint (`{id}` = the item's numeric/string id). |
| `detailData` | — | Optional wrapper key the detail object sits under (e.g. `data`). |
| `episodesPath` | `/anime/{id}/episodes` | Paginated episode endpoint. |
| `episodesPageParam` | `page` | Page parameter name. |
| `episodesItems` | `episodes` | Response key holding the episode array. |
| `episodesHasNext` | `hasNextPage` | Boolean key that says "there's another page". |
| `episodesMaxPages` | `30` | Maximum pages to walk before giving up. |
| `episodeNumber` / `episodeName` | `number` / `title` | Episode fields for the number and name. |
| `streamsPath` | `/watch/{id}/episode/{ep}/anineko/{lang}/hd1` | Watch endpoint; placeholders `{id}`, `{ep}`, `{lang}`. |
| `streamsLangs` | `sub,dub` | Comma-separated language keys to try (each becomes a watch request). |
| `streamsSources` | `sources` | Response key holding the sources array. |
| `streamsUrl` / `streamsQuality` / `streamsIsM3u8` | `url` / `quality` / `isM3U8` | Fields on each source object. |
| `streamsSubtitles` | `subtitles` | Response key holding subtitles. |
| `streamsSubUrl` / `streamsSubLang` | `url` / `lang` | Subtitle fields. |
| `streamsHeaders` | `headers` | Key of an object of per-stream headers (e.g. `Referer`/`Origin`) the player must send to the CDN. |
| `proxyStreams` | `true` | Also emit each stream wrapped through `proxy` — for CDNs that only serve the site's own player. |

#### API-mode conventions

- List items carry `id`, `title` (either a string or `{ "english": …, "romaji": … }`), and a poster found from `cover` → `coverImage.extraLarge` → `bannerImage` (first hit wins).
- Detail responses use `format` (or `type`): `MOVIE` → movie, anything else → series. `description`, `genres`, `seasonYear`/`year` and `bannerImage` are picked up automatically.
- Episode ids are built as `{animeId}|{episodeNumber}` — used to fetch streams.
- Streams get the per-source `headers` attached (plus `User-Agent`), so hotlink-protected CDNs still play. Subtitles ride along on every source.

**CloudStream .cs3 plugins (repos):** Extensions → *Add plugin repo* → paste a CloudStream-style `repo.json` URL, e.g.:

```
https://raw.githubusercontent.com/codegeasse1/codegeasse-cloudstream-repos/builds/repo.json
```

Hikari fetches the repo's `pluginLists`, shows every plugin (icon, description, author, version, checksum-verified install), and each gets a one-tap *Install* / *Uninstall*. Your saved repos persist across restarts.

You can also install a single `.cs3` directly: Extensions → *Install .cs3 from URL* (or *Pick .cs3 file*), e.g.:

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
