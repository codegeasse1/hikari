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
(`ScrollRequest`/`ReaderMover`), the webtoon *run* of chapters, the page loader
and "webtoon is the default mode" are four rules that each map to a bug the user
reported by name.

* Top bar: back, title, **chapter list** (`ChapterSheet` — reading order,
  scrolled to the current chapter, searchable, keyed by position because a
  source can repeat a chapter URL), **the globe** (this engine's site in the
  verification WebView — a site can gate the CHAPTER list, not just the catalog)
  and reader settings.
* Bottom bar: chapter ◀ ▶ (walking `navChapters`, one entry per chapter NUMBER —
  see `dedupeChapters`), the chapter label, the page readout, and
  `ReaderScrubber` — **one dot per page**, drag or tap to land on an exact page
  (through a `ScrollRequest`, never by setting `page` and hoping). Not a
  `Slider`: a thumb has no relationship to a page number.
* Three read modes (`MangaReadMode`) and three fits (`MangaFit`), stored in
  `AppStore` (**webtoon is the default** — see `MangaReadMode.normalize`);
  progress is written debounced on every page change and once more on dispose,
  against the chapter and page the surface REPORTED. The settings sheet SCROLLS:
  a `Column` in a `ModalBottomSheet` that overflows is clipped, not scrolled, and
  a sliced row reads to the user as a duplicated control.
* Page images are fetched, validated and retried by `manga/MangaPageLoader` (ten
  attempts, then a per-page Retry button), and every page is drawn the same way:
  ONE whole-page SOFTWARE bitmap from `manga/PageBitmaps`, drawn by an ordinary
  `Image`. There is no second path — no chunked renderer, no slices, no
  `BitmapRegionDecoder` (all four were tried and all four drew the page in
  pieces). The budget depends on the page's own shape: `MAX_PAGE_PIXELS` =
  `1_000_000` for an ordinary page, `TALL_PAGE_BYTES` = 48MB for a webtoon strip,
  with the width halved only when a monster strip needs it and never below 256px.
  `docs/READER.md` section 4a is the whole story, and it is required reading
  before touching the image path.

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
