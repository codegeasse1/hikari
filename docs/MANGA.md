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

**Read [READER.md](READER.md) before changing anything in it** — the request lane
(`ScrollRequest`/`ReaderMover`), the webtoon *run* of chapters and the page
loader are three rules that each map to a bug the user reported by name.

* Top bar: back, title, **chapter list** (`ChapterSheet` — reading order,
  scrolled to the current chapter, searchable, keyed by position because a
  source can repeat a chapter URL), reader settings.
* Bottom bar: chapter ◀ ▶ (walking `navChapters`, one entry per chapter NUMBER —
  see `dedupeChapters`), the chapter label, the page readout, and
  `ReaderScrubber` — **one dot per page**, drag or tap to land on an exact page
  (through a `ScrollRequest`, never by setting `page` and hoping). Not a
  `Slider`: a thumb has no relationship to a page number.
* Three read modes (`MangaReadMode`) and three fits (`MangaFit`), stored in
  `AppStore`; progress is written debounced on every page change and once more
  on dispose, against the chapter and page the surface REPORTED.
* Page images are fetched, validated and retried by `manga/MangaPageLoader`
  (ten attempts, then a per-page Retry button); the reader only ever decodes the
  file it produced.

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
* an installed engine's row in Extensions (`rememberVerifyAction` → each
  `ProviderCard`).
