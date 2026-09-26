# Vega providers

[Vega](https://github.com/Zenda-Cross/vega-providers) (the app is
[vega-app](https://github.com/Zenda-Cross/vega-app)) is a scraper ecosystem
whose providers are **plain CommonJS modules** rather than an extension archive.
Hikari runs them in an embedded QuickJS engine (the same one nuvio providers
use) and adapts them to its own `ContentProvider` contract, so a Vega provider
is a source like any other: a Home row, a catalogue, a search target, a detail
page, an episode list and a set of servers.

## The repository

A Vega repository is **one file**: `manifest.json` at the repo root, a **bare
JSON array** (no wrapper object, unlike every other kind):

```json
[
  {
    "display_name": "AniKoto",
    "value": "AniKoto",
    "version": "1.0.10",
    "icon": "https://cdn.jsdelivr.net/gh/Zenda-Cross/vega-providers@main/dist/icons/AniKoto.png",
    "type": "global",
    "disabled": false,
    "hasSettings": false
  }
]
```

- `value` is the provider's identity **and** the name of the folder its code
  lives in: `dist/<value>/{catalog,posts,meta,stream,episodes,settings}.js`.
- `disabled: true` is the repo owner's tombstone for a provider that stopped
  working. Those entries are skipped, not offered.
- There is no `repositories.json`; the official repo is the only one, and it is
  seeded on first run (`VegaPluginManager.DEFAULT_REPOS`).

## The modules

One file per function, each a **self-contained bundle** — nothing in the
published `dist/` calls `require` across files, which is what lets the runtime
load exactly one file per call:

| File | Export | Contract |
| --- | --- | --- |
| `catalog.js` | `catalog`, `genres` | Arrays of `{title, filter}` — pure data, no network. |
| `posts.js` | `getPosts({filter,page,(signal),providerContext,providerValue})` | A page of the catalogue. |
| `posts.js` | `getSearchPosts({searchQuery,page,…})` | Search results. |
| `meta.js` | `getMeta({link,…})` | An `Info`. |
| `episodes.js` | `getEpisodes({url,…})` | `EpisodeLink[]` for one seasons link. |
| `stream.js` | `getStream({link,type,(signal),isDownload,…})` | `Stream[]`. |
| `settings.js` | `getSettingsSchema({…})` | Not used yet (no per-provider settings UI). |

Shapes the adapter relies on:

- `Post` — `{title, link, image}`. `link` is the provider's own opaque handle (a
  page URL, or a JSON blob its own `meta` produced) and is carried through
  Hikari verbatim as the item's `id`.
- `Info` — `{title, synopsis, image, imdbId, type, linkList, webUrl, tags,
  rating, quickDownload, cast}`.
- `linkList[]` — `{title, episodesLink?, directLinks[{link,title,type,quality}]?,
  link?, quality?}`. A series entry carries `episodesLink` (a second request
  through `episodes.js`) or its own `directLinks`; a movie carries a direct link
  or just the page URL. Skip-timing entries inside an episode list have no
  `link` and drop out.
- `Stream` — `{server, link, type, quality?, headers?, subtitles?}` with `type`
  one of `mkv`, `m3u8`, `mp4`, `dash` (plus `external` in a few providers), and
  `subtitles: [{language|label|title, uri|file|url}]`.

## The runtime

`com.hikari.app.providers.vega.VegaRuntime` boots, per call:

1. `nuvio/boot.js` — polyfills (`console`, `URL`, `TextEncoder/Decoder`, `Blob`,
   `AbortController`, `atob`/`btoa`, `crypto`).
2. `nuvio/cheerio.js` — the real cheerio bundle (index 238 of the 259 published
   files use it), captured as a module like nuvio does.
3. `nuvio/harness.js` — global `fetch` over Hikari's OkHttp bridge (async, so a
   provider's `Promise.all([...])` is genuinely parallel), `Buffer`, `process`,
   `util`, `events`, the `__nuvioRequire` registry.
4. A per-call register script — registers cheerio, wires `__nuvioFetchImpl` to
   the async bridge, defines `__vegaLoadIsolated` (a `new Function` scope, so
   the shared `headers.js` cannot leak its helper names into the engine),
   computes the common-headers JSON from `assets/vega/commonHeaders.js`, and
   hands over the provider's `value` and its persisted KV JSON.
5. `assets/vega/harness.js` — the Vega `providerContext` (`axios`, `cheerio`,
   `commonHeaders`, `kvStore`, `openWebView`, `providerGlobal`), timers, and the
   call machinery (`__vegaCall`, `__vegaCallMany`, `__vegaCatalogJson`).

Then **one** provider file is loaded: `VegaRuntime.loadModule` wraps its body in
a CommonJS function (so its top-level `var`s are private), assigns the result to
`globalThis.__vegaExports`, and the whole wrapper is compiled to QuickJS
bytecode once per `provider/file/content-hash` and reused. `__vegaCall` runs the
named export with the call's args object plus `providerContext` and
`providerValue`, and answers `{"ok":true,"data":…}` or `{"ok":false,"error":…}`.

The engine is driven by a **pump loop**, exactly as nuvio's is: timer callbacks
never fire early (providers use `setTimeout` for `Promise.race` guards and retry
backoff), a parked timer is waited for, and "nothing parked + nothing in flight"
for six consecutive rounds is reported as *the provider stopped responding*
rather than hanging until the timeout.

Concurrency is bounded (8 engines, 4 with the performance booster on), a call
gets 60s (75s for a catalogue read), and each HTTP request inside it gets 30s.
`Accept-Encoding` is stripped from provider headers so OkHttp's own transparent
decompression does its job (forwarding it hands JS raw gzip bytes decoded as
UTF-8 — the same fix the nuvio bridge needed).

### What is deliberately not emulated

- `openWebView` resolves `{success:false}`: there is no interactive WebView to
  hand a captcha to from the engine, and answering honestly lets a provider fall
  through to its own error path instead of hanging. Providers that need it
  report their own failure.
- `settings.js` is stored but not driven; Vega's per-provider settings UI is not
  part of Hikari's yet.

## The adapter

`com.hikari.app.providers.vega.VegaProvider` maps the contract:

| Hikari | Vega |
| --- | --- |
| `catalogs()` | `catalog.js` `catalog` → `cat:<filter>`, `genres` → `genre:<filter>` |
| `homeCatalogs()` | `catalog` entries only — a home page is not twenty genre rows |
| `getCatalog(ref,page)` | `posts.getPosts({filter, page})` |
| `search(q,page)` | `posts.getSearchPosts({searchQuery, page})` |
| `getMeta(item)` | `meta.getMeta({link})` → `Info` |
| `getEpisodes(item)` | `Info.linkList` → `Episode` list (`episodes.js` per seasons link, in ONE engine via `callMany`) |
| `getStreams(item,ep)` | `stream.getStream({link, type, isDownload:false})` → `StreamSource` |

`streamErrors` / `catalogErrors` / `lastOutcome` are the per-provider maps the
Detail screen, the catalogue's empty state and the sources log read, exactly as
the other engines expose them.

## Files

- `app/src/main/java/com/hikari/app/providers/vega/VegaRuntime.kt` — engine,
  bridge, bytecode cache, pump loop.
- `…/vega/VegaProvider.kt` — the `ContentProvider` adapter.
- `…/vega/VegaPluginManager.kt` — `manifest.json` parsing, install/uninstall,
  first-run seeding, `dirOf`/`fileMissing`.
- `app/src/main/assets/vega/harness.js` — the `providerContext` + call glue (a
  vendored, editable asset, not a download).
- `app/src/main/assets/vega/commonHeaders.js` — byte-exact copy of upstream
  `dist/headers.js` (the shared desktop-Chrome headers).
- `data/RepoKind.VEGA`, `ProviderType.VEGA`, `data/RepoProvenance.kt`,
  `ui/screens/ExtensionsScreen.kt` — the wiring that makes it a source kind.

## Adding a Vega provider by hand

Add repository → a Vega manifest URL (or the short name `vega`), then open the
"Vega repos" folder, open the repo and install the provider you want. The
install downloads whichever of the six files exist (a movie-only provider has no
`episodes.js`; `moviesApi` publishes only `stream.js`), validates the folder by
actually loading its `stream`/`posts`/`meta` module, and refuses anything that
exports none of them.
