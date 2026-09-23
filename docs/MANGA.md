# Manga — engines, kind detection, history and the reader

Manga is a first-class half of Hikari: a Mihon/Tachiyomi extension (keiyoushi
and its mirrors) is installed through the **same Extensions screen** as an
Aniyomi anime extension, because the two are the same file format listed by the
same index.

## Two managers, one file format

| Manager | Hosts | Provider id | Provider type |
| --- | --- | --- | --- |
| `manga/MangaExtensionManager` | `tachiyomi.extension*` APKs (CatalogueSource) | `manga\|<pkg>\|<i>` | `ProviderType.MANGA` |
| `aniyomi/AniyomiExtensionManager` | `tachiyomi.animeextension*` APKs (AnimeSource) | `aniyomi\|<pkg>\|<i>` | `ProviderType.ANIYOMI` |

`ExtensionsViewModel.installExtension` picks between them per download by asking
`MangaExtensionManager.isMangaApk(bytes)` (which reads the APK's own metadata).
Both write the repo URL the entry came from into `ProviderConfig.extra` — that
is the key uninstall and the update check match on.

## "Is this entry manga or anime?" (the repo listing's split)

An index can hold both kinds. A row's kind is decided by
`ExtensionsScreen.RepoPluginsView` in this order:

1. **What is installed** (`installedKinds`, keyed by the package name) — the
   authoritative answer, and free, because an installed provider's id carries
   its kind.
2. **The entry's own index format** (`AniyomiExtensionManager.contentKindOf`):
   the modern shape (`resources.apkUrl`, string `versionCode`, `extensionLib`)
   is Mihon/keiyoushi — **manga**; the legacy shape (`apk` file name, integer
   `code`) is Aniyomi — **anime**. This is a real signal, not a guess: the two
   branches of the ecosystem publish different shapes on purpose.

A repo that holds both kinds gets section headings and All/Manga/Anime chips. A
heading **sorts** a list and must never hide an entry — anything unclassified is
listed under its own heading.

## Reading history

Reading positions live in `MangaStore` (`filesDir/manga/progress.json`), are
surfaced as "Manga — continue reading" in **My Stuff → History** and on the
**Manga** tab, and are prunable from BOTH:

* the ✕ over a card's cover → `MangaStore.clearProgress(mangaKey)`;
* the heading's **Clear all** → `MangaStore.clearAllProgress()` (confirmed
  first; it clears positions only — the library and the chapter lists stay).

## The reader (`ui/screens/MangaReaderScreen.kt`)

The reader is **Nekoread's reader, ported whole** — its two native viewers, its
page cache, its Coil decoder and its chrome — with this app's provider plumbing
as the only seam. `MangaReaderScreen` is the shell: it loads the chapter list,
fetches a chapter's pages, seeds the strip, writes progress, and hands the page
surface to the viewers. **Read [READER.md](READER.md) before changing anything in
it.**

* The reading itself is done by `reader/ui/YomiWebtoonReader` (the continuous
  strip — a `RecyclerView` of subsampling page frames, chapter dividers between
  streamed chapters, a trailing item reporting the next chapter's
  loading/error/end state) and `reader/ui/ChimahonPagerReader` (the paged modes —
  a `DirectionalViewPager` of the same page frames, so it can page vertically
  too). Both render every page **from an on-device file** in
  `reader/cache/WebtoonPageCache`, downloaded once **through the extension's own
  `getImage(page)`** (`reader/source/HikariPageSource`, Nekoread's
  `downloadPageImage` verbatim — the extension's `imageRequest` headers, its
  descrambler, its per-host limits, its client) and region-decoded from disk by
  `SubsamplingScaleImageView` (or, for a strip too tall for one decode,
  `WebtoonChunkedImageView` — Nekoread's own chunked renderer, ported unchanged).
  The page is never held in the heap whole.
* Every option lives in `reader/ui/YomiReaderChrome` (yomi/chimahon's own
  settings sheets): reading mode, page fit, orientation, crop borders (per
  mode), tap zones and their inversion, side padding, page scale, double-tap and
  pinch zoom, page transitions, auto-scroll and its speed, the hide threshold,
  custom brightness, colour filter, grayscale / invert / enhance, image quality,
  the backdrop, the chapter list and the per-series mode override.
* Reader settings are stored as **one JSON blob** (`AppStore.MANGA_READER_SETTINGS`
  ↔ `reader/ReaderSettings`), because they travel together: a reset, a backup and
  the chrome's "Reset settings" row all mean that object. The older per-key
  preferences are read once, through `ReaderSettings.fromLegacy`, so an existing
  install keeps the mode/fit/backdrop/page-number it had picked. The per-series
  override is a second blob (`MANGA_SERIES_MODES`, `mangaKey -> ReaderMode name`)
  — the key being present IS the override being on.
* Progress is written into `MangaStore` from a `snapshotFlow` on the page (so a
  page turn does not recompose the screen), debounced 700 ms, and once more on
  dispose.
* The reader's own way past a Cloudflare check: the globe in the top bar opens
  the engine's site in the verification WebView (`WebViewActivity`, auto-closing
  once the clearance is in the cookie jar) and re-fetches the chapter on the way
  back; a chapter whose page list never arrives also shows `VerificationNudge`.

## Images that only an extension's client can fetch

Two images in this app are not ordinary URLs, and both belong to the manga
feature: a reader **page** and a manga **cover**. These are the CDNs that refuse a
bare request (no Referer, the wrong User-Agent) while serving the same bytes to the
extension's own request, so the extension's client is the one that has to ask.
`reader/source/ExtensionPageImageFetcher.kt` is Nekoread's own fetcher layer for
exactly that, ported whole and registered on the app's single Coil loader
(`HikariApp`):

* `ExtensionPageImage(pageUrl, imageUrl, source)` — a page, fetched through
  `HttpSource.getImage(Page(...))`, i.e. the extension's `imageRequest(page)`
  headers and its client with its interceptors. `Fetcher` + `Keyer`, as Nekoread
  has them.
* `ExtensionCoverImage(imageUrl, source)` — a cover, fetched with
  `HttpSource.headers` plus a Referer fallback, through a short-timeout clone of
  the extension's client so a cover can never queue behind a burst of page
  requests on the same host. This is what Nekoread's own card uses for every cover
  it draws.
* `ExtensionCoverRef(imageUrl, providerId)` — **the one addition**, and it is about
  this app rather than a preference: Nekoread keeps every installed extension in a
  live registry built at start-up, so its cells can hand Coil a model that already
  holds the source. Here extension classes are loaded on demand (a dex load), and
  that must never happen while a grid composes on the main thread — so the model
  carries only the provider id and the Fetcher resolves the source on Coil's own
  dispatcher. If it cannot resolve or fetch, it falls back to the plain URL through
  the app's own client, so a cover is never WORSE than it was before this existed.
* `MangaSource` (`reader/source/MangaSource.kt`) therefore carries Nekoread's own
  members — `userAgent`, `getPageImageModels` and `coverImageModel` beside the page
  descriptors and `downloadPageImage` — and `PosterLoader.coverModel(url, providerId)` is
  how a cell asks for the extension-aware cover model: `Artwork.model(item)` for an
  item, `MangaPosterCard`/`ContinueCard` and the manga detail header for a raw URL.

## Browse: finding an engine, and keeping the two you read

`MangaScreen`'s Browse section is a flat list of every installed manga engine
(one row per source an extension publishes), and with a hundred extensions installed
finding one in it is half the work the tab does. Two things answer that:

* **A search box over the installed engines**, always drawn (it used to appear only
  past six engines, which left the reader with four and a name to find unable to
  filter at all). It matches the engine's NAME, and it filters the list in place.
* **A HOLD on a row pins it.** The gesture is `holdOrTap` (`HOLD_MS` = 500ms, the
  same helper and the same duration as the Home picker's multi-select hold, shared
  because a second hand-tuned hold in the same app would be a second thing to learn).
  Holding reveals the pin (and a Done button — a control whose only exit is another
  gesture is a trap); a pinned engine is drawn ABOVE every other one, wears the accent
  so the top of the list explains itself, and is remembered by engine ID in
  `AppStore.pinnedMangaEnginesFlow`. A tap on a row while its pin control is showing
  puts the control away instead of opening the engine, which is what every other
  context menu does. The ordering is applied where the list is drawn, from the stored
  set — a pin that outlives its engine simply stops matching.

**A tap opens the engine's Popular list**, and each row also carries Popular/Latest
pills. Once INSIDE one of the two, the catalog page offers the same pair as tabs
(`CatalogScreen` + `CatalogViewModel.catalog`/`switchCatalog`), so switching between
the two lists happens where the reader already is instead of by going back a screen
and pressing the other pill. The tabs are offered only where the pair is the whole
story: a MANGA catalog (`rawType == "manga"`) currently showing one of the two.

The catalog page also carries the engine's own search box (see `docs/SEARCH.md`),
which asks THAT engine rather than every installed one — with a hundred extensions,
the one a reader wants may not be the one the tab searches by default.

## Cloudflare and manga sites

Several manga sites answer every request with a bot check until a *browser* has
passed one, and no extension can open a browser for itself. The user-facing fix
is the **globe** button, which loads the extension's own site in
`WebViewActivity` (`autoCloseWhenCloudflarePassed = true`) and needs the site
URL — derived from the extension's source `baseUrl` by
`MangaExtensionManager.siteUrlOf` / `AniyomiExtensionManager.siteUrlOf`, i.e.
**on IO only** (it loads the extension) and never on the main thread.

**The clearance only works if the User-Agent matches.** A `cf_clearance` is bound
to the UA the WebView presented, so the extension client's default UA is
`HikariApp.effectiveWebViewUa()` (see `NetworkHelper.defaultUserAgentProvider`)
and any request that carries a clearance is presented under that same UA
(`ExtensionCloudflareInterceptor.withJarCookies`), as is the solver's offscreen
WebView. Two different fingerprints fighting over one cookie slot is what made a
verified site lapse back into challenging. The verify view and the solver both
`CookieManager.flush()` so the clearance reaches the disk.


The globe is offered in three places so it is always reachable from wherever the
user noticed the problem:

* the Manga tab's engine card (`MangaScreen.EngineRow`),
* a manga engine's Popular/Latest page (`CatalogScreen`'s header and its empty
  state — that page also carries the engine's own **search box**),
* a title's own page (`MangaDetailScreen`'s header — the chapter list itself can
  be the thing that is gated) and the reader's top bar,
* an installed engine's row in Extensions (`rememberVerifyAction` → each
  `ProviderCard`).

Any of those screens can also go ten seconds with nothing arriving, which is the
signature of exactly this wall: they carry `VerificationNudge`
(`ui/components/Components.kt`), a three-second chip that says so and opens the
verification view when tapped.
