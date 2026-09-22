# The reader — requests, the webtoon run, and page images

`ui/screens/MangaReaderScreen.kt` is the biggest screen in the app, and three of
its rules are load-bearing enough that they are written down here: **nothing moves
the pages except a request**, **a webtoon is a run of chapters**, and **a page
image is fetched by `manga/MangaPageLoader`, never by a bare `AsyncImage`**.
Breaking any of them reintroduces a bug that was reported by name.

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

`MangaPageLoader` fetches a page's BYTES with the extension's own headers, checks
them, writes them to `cacheDir/manga-pages/`, and the reader decodes that file
with Coil. The reader never hands a network URL to Coil.

Why (this is the "some pages are broken" report): a decoder given a **truncated**
body does not fail — Skia fills the missing macroblocks with black slabs, so a
half-fetched page is drawn as artwork cut into rectangles with black gaps through
it, and nothing ever retried. The loader therefore checks

* the response's status and a non-empty body;
* `Content-Length` against what actually arrived (`bytes().size`);
* the magic bytes (JPEG/PNG/GIF/WebP/BMP/AVIF) — this is also what catches a
  hotlink refusal or a bot wall answering with a 200;
* the format's own end-of-image marker (`looksComplete`: JPEG `FF D9`, PNG
  `IEND`, GIF `0x3B`, WebP's declared RIFF length).

Anything that fails is fetched again — `MAX_ATTEMPTS` (10) tries with a capped
backoff — and after that the page reports `MangaPageState.Failed` so
`PageContent` draws a **Retry** row on that page alone. A page is fetched at most
once: `load` is single-flight per URL.

Two more things it owns:

* **the preload window** — `plan(pages, current)` asks for ten pages ahead, eight
  behind, then the rest of the chapter forward, four at a time;
* **the page's real size**, read from the file header (`bounds`), which is what
  lets the strip and the paged fit lay a page out at its true aspect ratio before
  it is drawn (a strip of unknown-height boxes jumps under the reader's thumb as
  pages land).

The page cache is capped (`CACHE_CAP_BYTES`, pruned least-recently-used at most
once a minute). Files are written under a FRESH name on every fetch, because a
same-path rewrite would keep serving Coil's cached decode of the old bytes.

## 5. If you touch the reader

* Do not add an effect that scrolls in response to `page`/`firstVisibleItemIndex`.
  Add a `ScrollRequest`.
* Do not key a `LazyColumn` item by index. `RunItem.key` is the identity.
* Do not pass a page URL to `AsyncImage`. Go through `MangaPageLoader`.
* `MangaReaderScreen`'s reader-mode/fit/background preferences are declared near
  the TOP of the composable (before the run): which mode is in force decides
  whether a run exists at all.
