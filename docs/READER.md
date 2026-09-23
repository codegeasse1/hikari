# The reader — Nekoread's reader, ported whole

`ui/screens/MangaReaderScreen.kt` is the shell. The reading is done by Nekoread's
reader, copied into this repo file-for-file (only the package of each file was
changed, and this app's provider plumbing was put behind one small interface). Do
not "simplify" any of it back into a Compose `Image` per page: every rendering
shape this app tried before — one bitmap per page, a sliced bitmap, a chunked
image view — drew real webtoon strips in pieces or in black, which is exactly
what the user reported ("images breaking", "blank screen between images").

## 1. The files, and what each one is

| Path | What it is |
| --- | --- |
| `ui/screens/MangaReaderScreen.kt` | The shell: chapter list, page fetch, strip seeding, progress, the globals (brightness/orientation/system bars), the prewarm loop, and the two viewer calls. |
| `reader/ui/YomiWebtoonReader.kt` | Compose host for the continuous strip. Builds `WebtoonItem`s (a divider per streamed chapter, a page per page) and drives yomi's auto-scroll. |
| `reader/ui/ChimahonPagerReader.kt` | Compose host for the paged modes. Picks L2R / R2L / vertical, keyed on the direction so switching direction inside a chapter rebuilds the viewer. |
| `reader/ui/YomiReaderChrome.kt` | Every reader option. Top bar (bookmark, overflow, expandable auto-scroll), the chapter-navigator pill (prev/next chapter + one-tick-per-page slider), the 5-button bottom bar and the Reading-mode / General / Color sheets. Stateless: values in, intents out. |
| `reader/ReaderSettings.kt` | The modes/backdrops/fits/orientations and **one** data class holding all ~38 options, with `toJson`/`fromJson`/`fromLegacy`. |
| `reader/ReaderEnums.kt` | `WebtoonScaleType`, `TappingInvertMode`, `ReaderHideThreshold`, `ColorFilterMode`. |
| `reader/cache/WebtoonPageCache.kt` | The on-device page cache: `fileFor` (single-flighted download through the source), `prime`/`meta`/`cachedSize` (dims, animated, tall), `targetFile`/`keyFor` (the SHA-256 name a page's bytes live under), 400MB LRU eviction, `TALL_RATIO`. |
| `reader/source/MangaSource.kt` | The seam: `id`, `name`, `data class PageDescriptor(pageUrl, imageUrl)`, `downloadPageImage(page, target)`. Trimmed from Nekoread's interface to exactly the members the ported code calls. |
| `reader/source/HikariPageSource.kt` | This app's implementation: `url -> headers` for every page the reader has seen, downloaded with `Http.downloadTo`. |
| `reader/coil/TachiyomiReaderDecoder.kt`, `reader/coil/CropBorders.kt` | The Coil 2 decoder that can crop a page's blank borders, and the `cropBorders(true)` request extension (the flag also goes into `Options.parameters`, so cropped and uncropped decodes can never collide in the memory cache). Registered in `HikariApp.setupImageLoader`. |
| `eu/kanade/tachiyomi/ui/reader/viewer/ReaderPageImageView.kt` | The page renderer: a `SubsamplingScaleImageView` for tall pages, an ordinary image path for short ones. `budgetedShortWidth`, `readerPageDecodeDispatcher` and `WEBTOON_MAX_DECODE_PIXELS` live here and the shell's prewarm reuses them, so a warm and a bind can never ask for two different sizes. |
| `…/viewer/webtoon/*` | `WebtoonViewer`, `WebtoonRecyclerView`, `WebtoonAdapter`, `WebtoonPageHolder`, `WebtoonChunkedImageView`, `WebtoonLayoutManager`, `WebtoonConfig`, `WebtoonBorderDetector`, `WebtoonFrame`, `WebtoonSubsamplingImageView`. |
| `…/viewer/pager/*` | `PagerViewer` + L2R/R2L/vertical subclasses, `Pager`, `PagerAdapter`, `PagerPageHolder`, `PagerConfig`. |
| `…/viewer/ViewerNavigation.kt`, `GestureDetectorWithLongTap.kt`, `ReaderDiagnostics.kt` | Tap-zone schemes, long-tap, and the log buffer the native code writes to. |

Deleted with the port: `manga/PageBitmaps.kt`, `manga/MangaPageLoader.kt`,
`manga/MangaEnhance.kt`. They are what drew the page.

## 2. Why a page comes out right now

A page is a **file** in `WebtoonPageCache`, fetched by the SOURCE (so the CDN's
Referer/Origin requirements are met) and handed to a `SubsamplingScaleImageView`
that region-decodes it from disk. Both viewers use the same file and the same
renderer, so there is one drawing path, and it is the one the reference reader
uses on these phones.

* **Wrong pixels / 403 / scrambled pages** were the app's own HTTP client
  fetching the bare image URL. A page is now downloaded **through the
  extension** — `HttpSource.getImage(Page(0, url = pageUrl, imageUrl = url))` in
  `HikariPageSource`, which is Nekoread's `TachiyomiHttpSourceAdapter.
  downloadPageImage` copied verbatim. `getImage` builds its request from the
  source's own `imageRequest(page)` (Referer/Origin/custom headers, and where a
  source with scrambled pages installs its descrambler) and runs it through the
  extension's own client (per-host limits, cookies, 404 fallback). `pageUrl` is
  part of that: an extension builds its Referer from `page.url`, so a page
  fetched with an empty one was refused. `MangaProvider.getStreams` therefore
  carries the page's own URL on the `StreamSource` (see `StreamSource.pageUrl`)
  and writes the resolved image URL back onto the page, exactly as the reference
  adapter does. `HikariPageSource.setHeaders` remains only as the fallback for a
  source with no `HttpSource` behind it.
* **Torn / sliced / tiled pages** were the old whole-page bitmap path (the user's
  screenshot shows one page drawn as a grid of fragments with grey seams). That
  path is deleted; a strip is now region-decoded by the subsampling view, which is
  the only shape that stays correct at any zoom.
* **A page that fails is a page with a Retry**, drawn by its own holder
  (`WebtoonPageHolder` / `PagerPageHolder`), not a blank cell in a strip.
* **A blank gap between pages** was the reader applying a PAGED fit to the
  continuous strip. The strip is always drawn at fill-width (Nekoread's rule — a
  strip fitted to the height would leave two thirds of a phone screen empty beside
  every page); what changes its look is the Webtoon section's scale type / smart
  scale / side padding. **The Page fit setting is a paged-mode setting and always
  has been** — say so, do not "fix" it by making the strip honour it.

## 3. The seam: what the shell does

1. **Chapter list** — `MangaStore.chaptersFor(key)`, fetched on demand
   (`MangaProvider.getEpisodes`) when the reader was opened straight from a
   "continue reading" card. `navChapters = dedupeChapters(chapters, scanlator)`
   is one entry per chapter NUMBER (an aggregator lists the same chapter once per
   group); the ◀ ▶ buttons and the auto-continue walk that list, and
   `neighbourOf`/`navIndexOf`/`chapterNo` are the whole rule.
2. **Pages** — `fetchPages(url)` calls `MangaProvider.getStreams`, hands the
   extension's own source to `HikariPageSource` (`httpSource`, the object every
   page is fetched through) and puts every page's `headers` into it as well
   (`knownHeaders`, a `ConcurrentHashMap` — the fallback path only). The
   `StreamSource`s become `PageDescriptor`s (`pageUrl = it.pageUrl`,
   `imageUrl = it.url`) — the image URL is the identity every cache key, diff and
   warm uses, and the page URL is half of what the extension's `imageRequest`
   needs.
3. **Where to open** — a chapter that was saved opens on its saved page;
   any other chapter opens at its top. `openPage` and `pages` are assigned in the
   same breath so the viewer can never be created against a stale page.
4. **The strip** — `streamQueue` (chapters) + `streamSegments` (their pages). The
   seed effect puts the current chapter in and reports `viewerPos` for it, and
   prepends the previous one when the chapter was reached by an in-reader jump
   (`jumpSeedChapter`) so scrolling up returns to it. `segmentDescriptors` is the
   descriptor view of `streamSegments`, remembered, and it is what the viewer and
   the prewarm both take.
5. **The strip is continuous in BOTH directions.** The viewer reports
   `nearEnd` (the last few pages of the last streamed chapter) and the shell
   appends the next chapter; it also reports `nearStart` (the first few pages of
   the FIRST streamed chapter) and the shell prepends the previous one
   (`prependIntoStream`). So the reader can be scrolled from the chapter it was
   opened on all the way back to chapter 1 and forward to the last chapter
   without ever leaving the strip. A prepend shifts every adapter position, so
   the viewer remembers the page under the reader and puts the scroll back on it
   (`WebtoonViewer.setItems(..., prependedItems)`); `YomiWebtoonReader` works out
   how many items were added at the head by comparing the new chapter-id list
   with the old (a suffix match = a prepend, a prefix match = an append). The
   shell consumes `nearStart` (sets it false) before fetching and records the
   chapter in `prependTried`, so a source that refuses a chapter cannot make it
   fetch the whole back-catalogue at once.
6. **Auto-continue** — the viewer reports `nearEnd`; the shell fetches the next
   chapter into the stream and the trailer shows loading / error+retry / idle /
   end-of-manga.
7. **What the chrome says the current chapter is** — `activeChapterUrl` is
   `streamQueue[viewerPos.first]`, i.e. the chapter the viewer last reported a
   page in, NOT the chapter the screen was seeded with. `prevChapter`/
   `nextChapter` (the ◀ ▶ buttons) are the neighbours of THAT, and `openChapter`
   compares its target against THAT. `streamPosition`, `activeChapterUrl`,
   `currentPage` and `pageTotal` are `remember`ed **keyed on `chapter` as well as
   `isWebtoon`**, which matters: they read the `remember(chapter)` position
   states by closure, so a `remember(isWebtoon)`-only derived state keeps reading
   the first chapter's state objects for the rest of the session. That was the
   "it says chapter 4 while I am in chapter 6, and the arrow loads the same
   chapter again" report — the title, the page counter, the saved progress and
   the ◀ ▶ neighbours were all frozen on the chapter the reader was opened on.
   Seeding `viewerPos` in the seed effect is what keeps the first frame right
   when a chapter is prepended above the reader.
8. **Where a chapter change lands** — `openChapter` moves to the chapter's first
   page: to the segment's own start when the strip already holds it
   (`moveToPage`), and to the top on reload (`openPage` is 0 for any chapter
   reached from inside the reader; the saved page is restored only for the
   chapter the screen was opened on).
9. **Progress** — `MangaStore.setProgress(MangaProgress(...))`, from
   `snapshotFlow { currentPage }` debounced 700 ms, and once on dispose through a
   `rememberUpdatedState` lambda (the position states are `remember`ed per
   chapter — a closure captured once would still be holding the chapter the
   reader was opened on).
10. **Prewarm** — one `LaunchedEffect` keyed on the stream + decode settings and
   NOT on the page. It walks outward from the current page (nearest first, 12
   behind / 60 ahead), downloads in a small concurrent batch (`WEBTOON_BATCH`) via
   `WebtoonPageCache.fileFor`, and — short pages only — keeps a rolling
   memory-cache warm going (one warm in flight, only while the reader is at rest,
   re-warming a page only after the position has moved on `WEBTOON_MEM_WARM_STALE`
   pages). Tall pages are left to the subsampling view. Do not key this effect on
   the page: an effect that restarts on every scroll step cancels its own
   downloads, which is what makes a slow source stutter.

## 4. Settings

All options live in **one JSON blob** (`AppStore.MANGA_READER_SETTINGS` ↔
`reader/ReaderSettings`). They travel together — a reset, a backup and the
chrome's "Reset settings" row all mean that object — and a new option needs no new
key. `ReaderSettings.fromLegacy` reads the old per-key preferences ONCE, when no
blob exists yet, so an existing install keeps the mode/fit/backdrop/page-number it
had. The per-series override is a second blob
(`MANGA_SERIES_MODES`, `mangaKey -> ReaderMode name`): the key being present IS
the override being on, and `onSelectReaderMode` writes to the map instead of the
global mode while it is.

The DEFAULTS are the reference reader's own out-of-the-box states, which is what
the user's settings screenshots asked for: webtoon + pure-black backdrop, page
transitions and smooth auto-scroll ON, pinch-to-zoom / double-tap-zoom /
tap-to-turn-the-page OFF (on a strip those turn a scroll into a jump), tap zones
`Edge`, webtoon scale `Fit`, menu-hide `Normal`, image quality `High (sharp)`,
everything else off. Changing a default does NOT rewrite a stored blob: an install
that has already saved its settings keeps them, and "Reset to defaults" in the
chrome is how it picks the new ones up.

A settings change is applied **live**: `WebtoonConfig`/`PagerConfig` are rebuilt
as `remember`ed snapshots keyed on the options that feed them, and the viewers'
`config` setters re-bind the pages already on screen when a RENDER-affecting value
changed (`renderKey()`), which is why picking a fit or toggling crop redraws the
page you are on rather than waiting for a page turn.

## 5. If you touch the reader

* A page is downloaded through the SOURCE and rendered from the cache file. Do not
  pass a page URL to `AsyncImage`, do not decode a page file yourself, and do not
  add a second drawing path.
* Do not key the prewarm (or any download effect) on the current page.
* Keep the fit setting a PAGED-mode setting. In the strip, the Webtoon section's
  scale controls are the equivalent.
* Anything that reads `currentPage`/`pageTotal` must read it **where it is used**
  (through the provider lambdas the chrome takes, or inside a `snapshotFlow`).
  Reading it at the `MangaReaderScreen` call site recomposes the whole reader once
  per page turn.
* Position state that is `remember`ed per chapter must not be captured by a
  `DisposableEffect(Unit)`; pass what you need through `rememberUpdatedState`.
* A screen that can show an empty list because of a verification wall should carry
  `VerificationNudge` and a globe that opens the engine's site — the reader, the
  manga detail page and every catalog header all do.
* The ported chrome's labels are Nekoread's own English strings (the port is a
  full copy). Localising them means editing `YomiReaderChrome.kt` and wrapping
  each label in `tr(...)`; do not re-word them.
