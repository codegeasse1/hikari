# The reader — requests, the webtoon run, and page images

`ui/screens/MangaReaderScreen.kt` is the biggest screen in the app, and four of
its rules are load-bearing enough that they are written down here: **nothing moves
the pages except a request**, **a webtoon is a run of chapters**, **a page is
fetched by `manga/MangaPageLoader`, decoded by `manga/PageBitmaps` and drawn as
ONE bitmap — never in slices, never region-decoded, never by a view of our own**,
and **webtoon is the default reading mode** (an
aggregator's chapters are vertical strips; the paged modes are one tap away in the
settings, and a stored choice wins). Breaking any of them reintroduces a bug that
was reported by name.

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

## 4. Page images: `manga/MangaPageLoader` → `manga/PageBitmaps` → an `Image`

Two objects own a page, and the split between them is the point:

* **`MangaPageLoader` owns a page from bytes to a FILE.** It fetches the page with
  the extension's own headers, checks it, and writes it to
  `cacheDir/manga-pages/`. It does not decode the picture.
* **`PageBitmaps` owns a page from a FILE to pixels.** It decodes the file ONCE —
  at most at the width the screen can show, and at most `MAX_PAGE_PIXELS` — and
  keeps the result in a byte-budgeted LRU. That is also where the page AHEAD of
  the reader is decoded (`prefetch`, called from the reader's `report`), which is
  the difference between a page that is on screen when the thumb arrives and a
  spinner for the few hundred milliseconds a decode costs.

`PageContent` is the only place a page is drawn: one bitmap inside an ordinary
Compose `Image`, scaled with the fit the MODE asked for, with a spinner while it
is being decoded (a page that is already decoded is drawn on the frame it is
composed on, with no spinner at all). A file that cannot be DECODED is reported
back through `MangaPageLoader.markUndecodable`, which drops the file and puts the
page on the `Failed` row — where the Retry button already is. The reader never
hands a page URL to Coil, and never region-decodes anything.

Why the loader checks as hard as it does (this is the "some pages are broken"
report, twice): a decoder given a **truncated** body does not fail — Skia fills the
missing macroblocks with black slabs, so a half-fetched page is drawn as artwork
cut into rectangles with black gaps through it, and nothing ever retried. So the
loader checks, in this order:

* the response's status, a 404/410 answered as "do not retry this one", and a
  non-empty body;
* `Content-Length` against what actually arrived (`bytes().size`);
* the magic bytes (JPEG/PNG/GIF/WebP/BMP/AVIF) — this is also what catches a
  hotlink refusal or a bot wall answering with a 200;
* the format's own end-of-image marker (`looksComplete`: JPEG `FF D9`, PNG
  `IEND`, GIF `0x3B`, WebP's declared RIFF length);
* and the file's own header, through `bounds()` — the page's true pixel size (what
  the strip lays a page out at before it is drawn) and, since a file whose header
  will not parse is a file that will not decode, the last cheap gate there is.

Anything that fails is fetched again — `MAX_ATTEMPTS` (10) tries with a capped
backoff — and after that the page reports `MangaPageState.Failed` so
`PageContent` draws a **Retry** row on that page alone. A page is fetched at most
once: `load` is single-flight per URL. From the second attempt on, the request
carries `Cache-Control: no-cache`: a retry means the last body was unusable, and
the likeliest reason a later attempt gets the same one is a CDN edge holding it
(a header rather than a URL change, so a signed link stays valid). Files are
written under a FRESH name on every fetch, because a decoder keeps what it read
keyed by the file — the same path with new bytes would keep serving the old page.

### 4a. A page is ONE bitmap — this is the whole reader

Every version of "the image in the reader is breaking" came from a page being
drawn in PIECES, and none of them could be fixed from the fetch side: the bytes
were always fine. All three shapes were tried, in this order:

* **A decoded page drawn by the platform** (0.9x). A bitmap handed to the
  compositor becomes a GPU texture, and a page taller than the device's
  `GL_MAX_TEXTURE_SIZE` is then drawn by Skia's tile fallback — the one whose
  tiles' source rectangles are mis-placed on the drivers these phones have. What
  the reader showed was bands of the page displaced sideways, artwork repeated at
  a fixed offset and a white seam at every tile boundary.
* **A decoded page cut into slices by us** (0.10.9): decode the page, cut it into
  2048px slices, stack them. It did not hold, because "smaller than the limit" is
  not a property of the page — a webtoon page is still 1600×8000, and the phones
  that report a 2048 limit are exactly the ones the slices have to satisfy.
* **A page region-decoded by a subsampling view** (0.10.10): hand the file to
  `SubsamplingScaleImageView` (the Tachiyomi/yomi reader's own view) and let it
  decode the tiles the viewport needs. This is the shape the reference readers
  use, and on the pages this reader opens it came out as the same scattered
  blocks on a white field — the reader's sibling Nekoread says as much in its own
  comments, where the chunked and region-decoded paths are the ones that "came
  out black", and where the fix was to decode the whole strip into ONE bitmap.

The lesson is not "the tile size was wrong". It is that a page drawn in pieces
depends on the platform — tile placement, texture limits, region rectangles —
being right, and that is not something this app can verify or promise on someone
else's phone. **A whole bitmap depends on none of it: there is nothing to
mis-place, because the pixels arrive in one piece.**

So `PageBitmaps` decodes the page once, at a size the process can hold, and the
compositor scales that bitmap into whatever box the mode gives it:

* at most the width the screen can show (a ~800px source is never upscaled to a
  1080px screen — that adds bytes, not detail), and at most one halving more when
  the source is wider than the screen;
* at most `MAX_PAGE_PIXELS` (~44MB): a page too big for that is decoded at the
  next power-of-two reduction down. A 1080×20000 manhwa strip becomes 540×10000 —
  the sharpest copy of it a phone can hold, and, more to the point, a COMPLETE one
  instead of a scrambled one. That trade is the same one Nekoread's reader makes
  (48MB per strip); softness on a pathological strip costs the reader nothing
  permanent, a broken page costs it the chapter.

Consequences to respect:

* **Never** add a second drawing path: no tiles, no slices, no chunked view, no
  region decoding, no "draw only the visible part" optimisation. Every one of them
  has been here, and each one is a page that can come out as blocks. The whole
  bitmap is the fix, not a stepping stone.
* The file is the unit. A page's pixels come from `MangaPageState.Ready.file` and
  nowhere else, and a FILE is never mutated in place (the loader writes a fresh
  name per fetch) — which is what makes the decoded-bitmap cache safe to key on
  the file's path.
* `fitWidth` is a `ContentScale` on an ordinary `Image`: full width for the
  webtoon strip and `MangaFit.WIDTH` (inside a box of the page's own aspect
  ratio), fitted inside the viewport for the paged fit modes. It is a property of
  the MODE, decided by the container, which is the thing that knows how much room
  the page was given.
* The page's real size still comes from the loader's header read, which is what
  sizes each strip item BEFORE it is drawn — a strip of unknown-height boxes jumps
  under the reader's thumb as pages land.
* Nothing in the drawing path takes touch events. The reader's own container owns
  every gesture (a tap toggles the chrome, a drag scrolls the strip or swipes the
  pager), and an `Image` never competes for them.

Things the loader also owns:

* **the preload window** — `plan(pages, current)` asks for ten pages ahead, eight
  behind, then the rest of the chapter forward, four at a time, and skips itself
  when the reader has moved less than two pages since the last call (it runs on
  every scroll step). It warms BYTES, which is the slow half (seconds). The
  pixels of the single page the reader is about to reach are warmed separately
  (`PageBitmaps.prefetch`, from `report`): a decoded page is worth a few hundred
  milliseconds on an ordinary page and megabytes of heap on a big one, so warming
  ten of them ahead would evict the page on screen to make room for pages nobody
  is looking at. `prefetch` gives up rather than queue in front of a decode that
  is already running.
* **the page's real size**, read from the file header (`bounds`), which is what
  lets the strip and the paged fit lay a page out at its true aspect ratio before
  it is drawn.
* **What the sheet's switches mean.** `Enhance images` is a colour matrix
  (`manga/MangaEnhance.kt`) applied when the page is DRAWN, as the Compose
  `ColorFilter` on the page's `Image` (and on the few chrome images), so it needs
  no second decode and no extra bitmap — which is why it can stay on while the
  strip scrolls.

The page cache is capped (`CACHE_CAP_BYTES`, pruned least-recently-used at most
once a minute; a pruned page's file and `Ready` state go together, so the page is
fetched again rather than drawn blank).

## 5. If you touch the reader

* Do not add an effect that scrolls in response to `page`/`firstVisibleItemIndex`.
  Add a `ScrollRequest`.
* Do not key a `LazyColumn` item by index. `RunItem.key` is the identity.
* Do not pass a page URL to `AsyncImage` and do not decode a page file yourself
  (see 4a). A page is one bitmap from `PageBitmaps`, drawn by an ordinary `Image`.
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
