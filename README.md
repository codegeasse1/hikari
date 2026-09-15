# Hikari

**Hikari** (光) — a universal streaming app. One player, every ecosystem:

- **Stremio addons** — add any addon URL, browse catalogs, search, play.
- **Universal scrapers** — JSON-rule site scrapers, no code needed.
- **CloudStream .cs3 plugins** — Stage 2 ✅ — your existing `.cs3` extensions run unchanged.
- **SkyStream extensions** — Stage 3.

Modern Material 3 UI, HLS/DASH playback with per-source headers and subtitles, built in pure Kotlin + Compose.

## Support & community

Need help, found a bug, or want to request a feature? Join the **Hikari Telegram group** — the fastest way to reach me and get support:

- **Telegram:** <https://t.me/CodegeasseHikari>

Everyone is welcome — post your questions, bug reports, and feature requests there and I'll help you out.

## Screenshots

| Home | Extensions | Extension repo |
|---|---|---|
| <img src="https://user.uploads.dev/file/4c8cf9302ff9f189a7ae931b817c4e5e.jpg" width="240"/> | <img src="https://user.uploads.dev/file/63660b0117b875606e0bd26532f801cc.jpg" width="240"/> | <img src="https://user.uploads.dev/file/3abef8ccd845e097495b8d28e82190da.jpg" width="240"/> |

| Choose a source | Player |
|---|---|
| <img src="https://user.uploads.dev/file/61555fdf433dbe85a8960677e6279923.jpg" width="240"/> | <img src="https://user.uploads.dev/file/fb99309c5e1a41a6048cb602e211c774.jpg" width="240"/> |

## Installing the app

The latest signed APK is on the **latest release** page:

- **Latest release:** <https://github.com/codegeasse1/hikari/releases/latest>

Download it on the phone and open it (allow "install unknown apps").

## Add extensions

### Official repos — add all sources in one tap

The quickest way to load sources is to add our two official extension repos. Each is a single URL that installs a whole collection of extensions.

**Hikari extensions repo** (native .hiki extensions)

```
https://raw.githubusercontent.com/codegeasse1/hikari-extensions/builds/repo.json
```

That URL is a `repo.json` listing every official extension (trimmed here):

```json
{
  "name": "Hikari Extensions",
  "description": "Official .hiki extensions for Hikari.",
  "plugins": [
    { "name": "Anime4i",  "url": "https://github.com/codegeasse1/hikari-extensions/releases/download/continuous/anime4i.hiki",  "version": 2, "tvTypes": ["series", "movie"] },
    { "name": "Anime",     "url": "https://github.com/codegeasse1/hikari-extensions/releases/download/continuous/anime.hiki",      "version": 1, "tvTypes": ["movie", "tv"] },
    { "name": "Castle TV", "url": "https://github.com/codegeasse1/hikari-extensions/releases/download/continuous/castle.hiki",   "version": 3, "tvTypes": ["movie", "tv"] },
    { "name": "Anikoto",   "url": "https://github.com/codegeasse1/hikari-extensions/releases/download/continuous/anikoto.hiki", "version": 3, "tvTypes": ["movie", "tv"] }
  ]
}
```

**Codegeasse CloudStream repo** (.cs3 plugins)

```
https://raw.githubusercontent.com/codegeasse1/codegeasse-cloudstream-repos/builds/repo.json
```

Its `repo.json` points at the plugin list:

```json
{"name": "Codegeasse Repo", "description": "Anime4i CloudStream extensions", "manifestVersion": 1, "pluginLists": ["https://raw.githubusercontent.com/codegeasse1/codegeasse-cloudstream-repos/builds/plugins.json"]}
```

#### Add the Hikari repo, step by step

1. Open **Hikari** and go to the **Extensions** tab (puzzle-piece icon at the bottom).
2. Tap **Add Hikari repo** (the top-left button with the puzzle-piece icon).
3. Paste this URL and tap **Add**:

   `https://raw.githubusercontent.com/codegeasse1/hikari-extensions/builds/repo.json`

4. Tap the **Hikari Extensions** card that appears, then tap **Install** next to any extension you want (Anime4i, Anime, Castle TV, …).

#### Add the CloudStream repo, step by step

1. Open **Hikari** → **Extensions** tab.
2. Tap **Add CloudStream repo** (the top-left **+** button).
3. Paste this URL and tap **Add**:

   `https://raw.githubusercontent.com/codegeasse1/codegeasse-cloudstream-repos/builds/repo.json`

4. Tap the **Codegeasse Repo** card, then **Install** the providers you want (Anime4i, Anikoto, Anineko, …).

> You can even paste a plain `github.com/owner/repo` link instead of the full raw URL — Hikari automatically finds the correct `repo.json`. Installed providers appear in **Home** and **Search** right away.

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
- **Stage 3 (in progress):** ✅ downloads (offline + export), ✅ favourites /
  continue-watching, ✅ new UI everywhere. Still to come: SkyStream shims,
  torrent engine (Stremio infoHash streams), Trakt.

## Changelog

Every release's notes live in [`CHANGELOG.md`](CHANGELOG.md) and on the
[releases page](https://github.com/codegeasse1/hikari/releases).

## Build

```bash
# CI builds app-debug.apk on every push to main (see .github/workflows/build.yml)
# locally:
gradle assembleDebug
```

## Developer notes

- **Never do blocking work on the main thread.** `viewModelScope` is
  `Dispatchers.Main`, and OkHttp's `execute()` (via `Http.get/getString`) throws
  `NetworkOnMainThreadException` there — an exception that any surrounding
  `runCatching` swallows, so the feature looks like it "did nothing" instead of
  crashing. This once silently killed every detail-page TMDB lookup:
  `TmdbMeta.extras/related/similar` were launched straight from
  `viewModelScope`, so the "Show Details" block and the Cast / Trailers /
  Related / Similar rows never rendered on any title. `TmdbResolver.apiGet` now
  hops to `Dispatchers.IO` itself, so all present and future callers are safe by
  construction — keep that property when adding new TMDB/HTTP helpers.
- **Title matching across sources needs normalisation.** IMDb spells sequels
  "Ramayana Part 2" where catalogs say "Ramayana: Part Two"; the artwork
  fallback's exact/prefix test rejected those and left the cell on its
  placeholder forever. `TmdbMeta.normalizeTitle()` folds case, punctuation and
  numerals before comparing (see `Artwork` for the cache-miss TTL that makes a
  bad match stick for days).
- **Episode lists: TMDB first, Bangumi to top up, extensions as a last resort.**
  `NuvioScraper.getEpisodes` drops rows whose `air_date` is in the future
  (Renegade Immortal lists 200 episodes but only 158 have aired), and when TMDB
  knows a single season it merges Bangumi (`BangumiMeta.episodes()` —
  `api.bgm.tv`, no key needed) to append episodes TMDB is missing (Battle
  Through the Heavens: TMDB stops at 45, Bangumi has 150+). Bangumi numbers
  episodes absolutely and continuously across seasons, matching TMDB's
  numbering, but its air dates run a day later, so the merged list allows one
  day of grace. Search Bangumi with TMDB's `original_name` (native title) —
  English queries return garbage. If both TMDB and Bangumi come up empty,
  `ContentRepository.episodesFromExtensions` borrows the episode list from an
  installed site-scraping extension for the same title.
- **Episode NAMES are always English, or the source's own name.** The UI is in
  English, so a row is upgraded ONLY when TMDB has an English title for that
  episode (asked for with `language=en-US`, since TMDB otherwise answers in the
  show's original language): `EpisodeTitles.lookup()` returns just those, and
  `ContentRepository.withRealEpisodeNames` swaps them in while leaving the
  extension's list — count, order and numbering — exactly as it is. Everything
  else keeps what its source gave it: the site's own label for an extension
  item, TMDB's own name for a Nuvio item (there is no site behind it). Bangumi
  is deliberately NOT consulted for names any more — its titles are Chinese, so
  letting them substitute for a real site title (or for TMDB's generic
  "Episode 128") would put Chinese rows in an English list. The one exception is
  a source label that is pure noise ("Swallowed Star Episode 33 English Sub"):
  it carries no title, so TMDB's plain "Episode 33" is used in its place. Note
  TMDB's English coverage of a donghua is often partial (Sword of Coming
  season 2 has English for 4 of 27 episodes) — for a fully English list the
  user turns on the per-extension "Always translate" toggle (the `Aあ` button
  on Home), which runs the remaining names through `Translator`. A title that
  names a season ("Sword of Coming Season 2") is resolved season-locally,
  because the site numbers that season from 1 while TMDB numbers the franchise
  continuously. A show's native title can also be indexed twice on TMDB (剑来 is
  both the show and a one-episode short), so candidate scores break ties on
  popularity — otherwise a 1-episode entry can win and title the wrong list.
- **A decorated title must not defeat TMDB resolution.** Site metas carry
  decorations and translations TMDB's search index does not match: "Sword of
  Coming Season 2" and "Battle Through The Heavens: Origin" both return ZERO
  results for the literal query, and a franchise's English name on a site is
  often a different translation than TMDB's ("Battle Through The Heavens" is
  "Fights Break Sphere" there). An unresolved item silently loses the Cast,
  Trailers, Details, Related and Similar sections. `TmdbResolver.searchByTitle`
  therefore searches `TmdbMeta.queryVariants()` (full title, then brackets /
  season markers / subtitle stripped) and, failing any name match, checks the
  alternate titles of the top results. Only a NAME match is accepted — the year
  merely breaks ties — so a franchise guess can never swap in a different
  show's data.
- **Trailers open in the YouTube app, never in Hikari's WebView.** A TMDB
  trailer's `vnd.youtube:<key>` intent is tried first, then the
  `https://www.youtube.com/watch?v=<key>` link (the YouTube app again when its
  App Links are verified, otherwise a browser), and Hikari's own
  `WebViewActivity` only as a last resort — `ui/ExternalLinks.openYouTubeVideo`.
  Keep it that way: Hikari's WebView is for pages Hikari renders itself, whereas
  a trailer belongs in the user's own YouTube app, with their account, full
  quality and their familiar player gestures. The
  resolver never probes with `resolveActivity`: on API 30+ package visibility
  would report YouTube as missing unless it is declared in `<queries>`, while
  starting the implicit intent works regardless. YouTube-hosted STREAM sources
  (`StreamSource.ytId`) still use the WebView — that is deliberate, it is the
  ad-free playback path.
- **A one-time Telegram invitation runs on launch.** `MainActivity` shows
  `ui/components/TelegramDialog` (Join / Close + "Don't show this again")
  after the update check has finished, so the two dialogs never stack;
  `AppStore.telegramDontShow` remembers the tick. Join goes through
  `ui/ExternalLinks.openTelegram`, whose single source of truth for the link is
  the `TELEGRAM_CHANNEL_URL` constant: `tg://resolve?domain=<handle>` first,
  then the plain `https://t.me/...` link (the Telegram app again when its App
  Links are verified, otherwise the user's browser). It never falls back to
  Hikari's WebView — Telegram is a native messaging app, so handing the user
  straight to it is both nicer and the intended path. The Settings
  Telegram row uses the same helper.
- **System back must unwind in-screen sub-views.** The Extensions and Settings
  screens hold their sub-pages in local state, not nav destinations, so without
  a `BackHandler` the system back button popped the whole destination and
  dropped the user on the Home tab from any folder. Both screens now register a
  `BackHandler` that first walks back one level (repo plugin list → folder →
  list, settings folder → index), matching the on-screen back arrow.
