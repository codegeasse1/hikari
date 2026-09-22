# The reader — requests, the webtoon run, and page images

`ui/screens/MangaReaderScreen.kt` is the biggest screen in the app, and four of
its rules are load-bearing enough that they are written down here: **nothing moves
the pages except a request**, **a webtoon is a run of chapters**, **a page image
is fetched AND decoded by `manga/MangaPageLoader`, never by a bare `AsyncImage`**,
and **webtoon is the default reading mode** (an aggregator's chapters are
vertical strips; the paged modes are one tap away in the settings, and a stored
choice wins). Breaking any of them reintroduces a bug that was reported by name.

## 1. One lane for every move: `ScrollRequest` + `ReaderMover`

The reader publishes a `ScrollRequest(seq, chapterUrl, page)` when something
decides the surface should show a different page — the page bar, the ◀ ▶ page
buttons, a D-pad press, a restored position, a chapter jump. The body that draws
the pages turns it into a scroll through its own `ReaderMover`, whose channel is
CONFLATED and whose single collector is started once per body with
`LaunchedEffect(Unit)`.

* A body **reports** its position (`onReport(chapterUrl, page)`), and a report may
  only update the readout (`page`, `visibleChapter`, the preload window, saved
  progress). A report must NEVER cause a move.
* This used to be the other way round — the surface followed the reported page in
  an effect keyed on that page — and the result was reported twice: tapping the
  page bar flashed to the target and snapped back (the effect cancelled its own
  scroll the moment the pager reported the move), and dragging it fought itself
  page by page.
* The conflated channel is what makes a drag safe: forty requests while the finger
  moves leave one move to carry out — the one the finger stopped on.
* `seq` exists so "ask for the page you are already on" is expressible. Never
  compare pages to decide whether a request is new.

## 2. A webtoon is a RUN of chapters, not a chapter

In `MangaReadMode.WEBTOON` the drawn list is `List<RunItem>` — for each
`RunBlock` (a chapter) one `RunItem.Head` title card followed by one
`RunItem.Page` per page. `flattenRun` builds it; `MangaReaderScreen` keeps the
run (`run`), the flattened list (`runItems`), and the watcher that grows it:

* **the last few items in view** → fetch the next chapter in `navChapters` and
  append it (and `MangaPageLoader.plan(pages, 0)` so it arrives loaded);
* **the first few items in view** → fetch the previous chapter and PREPEND it,
  then `listState.scrollToItem(anchor + added, anchorOffset)` to hold the page
  that was under the eye still. The anchor is read after the fetch, right before
  the strip grows, and only one prepend is ever in flight (`prepending`);
* items are keyed by `RunItem.key` (`<chapterUrl>#<page>`) — never by position —
  which is what keeps the viewport on the same page across an insert at either
  end;
* `tried` remembers neighbours that turned out to have no pages, so a broken
  chapter is not re-fetched on every scroll step.

`page`/`visibleChapter` always mean "the chapter on screen and the page inside
it", so progress saving, the page bar and the chapter label follow the reader
across chapter boundaries without any extra bookkeeping.

## 3. `navChapters` — the arrows walk chapter NUMBERS

`dedupeChapters` + `chapterNo` (in the reader file) collapse the source's list to
one entry per chapter number, preferring the scanlator group being read; an
aggregator lists the same chapter once per group, and walking the raw list made
"next chapter" open the SAME chapter from another group. The chapter sheet
(`ChapterSheet`) still lists every release — `chapters`, not `navChapters`.

## 4. Page images: `manga/MangaPageLoader`

`MangaPageLoader` owns a page from bytes to PIXELS. It fetches the page with the
extension's own headers, checks it, writes it to `cacheDir/manga-pages/`, decodes
it, and hands the reader a `Bitmap` — the reader never hands a network URL to
Coil, and never decodes a page itself.

Why (this is the "some pages are broken" report, twice): a decoder given a
**truncated** body does not fail — Skia fills the missing macroblocks with black
slabs, so a half-fetched page is drawn as artwork cut into rectangles with black
gaps through it, and nothing ever retried. And a page can pass every structural
check and still be garbage: a complete, correctly-terminated JPEG whose scan data
is damaged decodes to displaced blocks. So the loader checks, in this order:

* the response's status, a 404/410 answered as "do not retry this one", and a
  non-empty body;
* `Content-Length` against what actually arrived (`bytes().size`);
* the magic bytes (JPEG/PNG/GIF/WebP/BMP/AVIF) — this is also what catches a
  hotlink refusal or a bot wall answering with a 200;
* the format's own end-of-image marker (`looksComplete`: JPEG `FF D9`, PNG
  `IEND`, GIF `0x3B`, WebP's declared RIFF length);
* and finally **a real decode at the width the reader will draw** (`decodeFor`),
  which is the only test that can catch damaged-but-complete bytes.

Anything that fails is fetched again — `MAX_ATTEMPTS` (10) tries with a capped
backoff — and after that the page reports `MangaPageState.Failed` so
`PageContent` draws a **Retry** row on that page alone. A page is fetched at most
once: `load` is single-flight per URL.

**The decode is cached, and that is what makes scrolling smooth.** Decoded pages
live in `bitmaps`, an `LruCache` budgeted by BYTES (a sixth of the process heap,
48MB–320MB floor/ceiling). A page is decoded at most `MAX_DECODE_W` wide and
`MAX_DECODE_H` tall — powers of two, so `BitmapFactory` does the skipping while it
decodes — and as `RGB_565`, because a page is opaque: that is half the memory and
half the upload for every frame it is on screen. `bitmap(url, maxWidthPx)` is what
the UI calls (off the main thread, once per page per composition); it returns
cached pixels or decodes and caches them. **Never recycle** those bitmaps: the
cache hands them straight to a drawing frame, so a recycled one is a crash rather
than a saved allocation. Eviction just drops the reference.

Things the loader also owns:

* **the preload window** — `plan(pages, current)` asks for ten pages ahead, eight
  behind, then the rest of the chapter forward, four at a time. The ahead/behind
  window is fetched EAGERLY (`load(..., eager = true)`: bytes decoded into the
  cache as they land, so the next swipe is a blit); the long tail is fetched to
  disk only. `plan` skips itself when the reader has moved less than two pages
  since the last call, because it runs on every scroll step.
* **the page's real size**, read from the file header (`bounds`), which is what
  lets the strip and the paged fit lay a page out at its true aspect ratio before
  it is drawn (a strip of unknown-height boxes jumps under the reader's thumb as
  pages land).
* **`decodeWidth`** — the reader sets it from its own window width (one
  `LaunchedEffect`), because the loader cannot know it and a page wider than the
  screen is decoded down to the screen, not up to the site's original.
* **What the sheet's switches mean.** `Enhance images` is a `ColorFilter`
  (a colour matrix, `mangaEnhanceFilter`) applied when the page is DRAWN, not a
  second copy of the artwork: it costs no decode and no memory, which is why it
  can stay on while the strip scrolls.

The page cache is capped (`CACHE_CAP_BYTES`, pruned least-recently-used at most
once a minute; a pruned page's file, bitmap and `Ready` state all go together).
Files are written under a FRESH name on every fetch, because a same-path rewrite
would keep a stale decode alive.

## 5. If you touch the reader

* Do not add an effect that scrolls in response to `page`/`firstVisibleItemIndex`.
  Add a `ScrollRequest`.
* Do not key a `LazyColumn` item by index. `RunItem.key` is the identity.
* Do not pass a page URL to `AsyncImage` and do not decode a page file yourself.
  Go through `MangaPageLoader.bitmap`.
* `MangaReaderScreen`'s reader-mode/fit/background preferences are declared near
  the TOP of the composable (before the run): which mode is in force decides
  whether a run exists at all.
* The reader's settings sheet must keep scrolling (`verticalScroll`) — a Column
  in a `ModalBottomSheet` that overflows is CLIPPED, not scrolled, and a clipped
  row reads as a duplicated control.
* A screen that can show an empty list because of a verification wall should
  carry `VerificationNudge` (see `ui/components/Components.kt`) and a globe that
  opens the engine's site — the reader, the manga detail page and every catalog
  header all do.
