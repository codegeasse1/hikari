# The reader — requests, the webtoon run, and page images

`ui/screens/MangaReaderScreen.kt` is the biggest screen in the app, and four of
its rules are load-bearing enough that they are written down here: **nothing moves
the pages except a request**, **a webtoon is a run of chapters**, **a page is
fetched by `manga/MangaPageLoader`, then drawn one of exactly two ways — a short
page as one budgeted bitmap from `manga/PageBitmaps`, a strip by region-decoding
`manga/ChunkedPageView` — and never a third way**,
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

## 4. Page images: `manga/MangaPageLoader` (bytes → a file) → `manga/PageBitmaps` (a file → ONE bitmap)

Two objects own a page, and the split between them is the point:

* **`MangaPageLoader` owns a page from bytes to a FILE.** It fetches the page with
  the extension's own headers, checks it, and writes it to
  `cacheDir/manga-pages/`. It does not decode the picture.
* **`PageBitmaps` owns a page from a FILE to pixels — one bitmap, the whole page.**
  It decodes the file ONCE, as SOFTWARE memory, at the width the screen can show and
  within the budget the page's own shape allows (`TALL_PAGE_BYTES` = 48MB for a
  strip, `MAX_PAGE_PIXELS` = `1_000_000` for a short page), and keeps the result in a
  byte-budgeted LRU. There is NO height ceiling any more: a height ceiling is
  precisely what used to force a page into pieces (see 4a). That is also where the
  page AHEAD of the reader is decoded (`prefetch`, called from the reader's
  `report`), which is the difference between a page that is on screen when the thumb
  arrives and a spinner for the few hundred milliseconds a decode costs.

`PageContent` is the only place a page is drawn: one bitmap inside an ordinary
Compose `Image`, scaled with the fit the MODE asked for, with a spinner while it
is being decoded (a page that is already decoded is drawn on the frame it is
composed on, with no spinner at all). A file that cannot be DECODED is reported
back through `MangaPageLoader.markUndecodable`, which drops the file and puts the
page on the `Failed` row — where the Retry button already is. The reader never
hands a page URL to Coil, and there is no second drawing path for it to fall back
to — one page, one bitmap (see 4a).

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

### 4a. ONE drawing shape: the whole page as a single SOFTWARE bitmap — this is the whole reader

Every version of "the image in the reader is breaking" came from a page being drawn
in a shape the platform cannot draw. The bytes were always fine, and the fetch side
could never fix it.

**What works, and what the reference reader actually does.** Nekoread (Mihon's
reader, and yomi's before it) draws a page ONE way: it decodes the ENTIRE page,
once, at the width it will be shown at, as SOFTWARE memory (Coil's
`allowHardware(false)`; `inPreferredConfig = ARGB_8888` and never `HARDWARE` here),
and hands that one bitmap to an ordinary `ImageView`. The platform's render thread
splits a bitmap larger than the largest texture into as many tiles as it needs —
silently, correctly, per frame — and because the bitmap is stable for the page's
whole life there is nothing to re-decode while scrolling. That is the shape that has
always worked, including for strips 13,700-17,000px tall, and it is what
`PageBitmaps` now does for every page. `TALL_PAGE_BYTES` = 48MB is the reference
reader's own per-bitmap cap for exactly this job; the width is halved only when a
monster strip needs it, and never below `MIN_DECODE_WIDTH` (256px).

**Why "it must fit one texture" was the wrong worry, and what it cost.** A bitmap
is only one texture when the DECODER made it a hardware bitmap. A SOFTWARE bitmap is
uploaded and tiled by the compositor, which is what the reference reader relies on.
So the budget that matters is the HEAP (48MB a strip, ~4MB a short page), not
`GL_MAX_TEXTURE_SIZE` — and a rule built on the texture limit is what produced years
of workarounds: a height ceiling, then slices, then a subsampling view, then
region-decoded chunks. Every one of them drew the page in PIECES, and every one of
them came out on the user's phone as artwork scattered in displaced blocks or as a
black field.

The six shapes that were tried, in order, and the one that works:

* **A decoded page drawn by the platform, whole** (0.9x). Correct in principle —
  and broken by a HARDWARE bitmap: a hardware decode cannot be tiled, so a page
  taller than the texture limit came out as artwork scattered into displaced blocks
  with black tiles where art should be. That is what the screenshots show.
* **A decoded page cut into slices by us** (0.10.9): decode the page, cut it into
  2048px slices, stack them. Even a flawless slice stack is drawn by the platform as
  slices, piece by piece, and the compositor already does this job better.
* **A page region-decoded by a subsampling view, configured by us** (0.10.10): the
  right library, our own view, our own settings — and the page still drawn as a
  bitmap in Compose-land. It changed nothing.
* **A page decoded whole at a big budget** (0.10.11): the same failure as the first
  one, because a "budget" of 11M pixels permits a 1080x10000 page, and a
  1080x10000 HARDWARE bitmap is still not one texture.
* **A subsampling view for strips only** (0.10.12): the routing rule
  (`h > 3w → NekoPageView`, `SubsamplingScaleImageView`) was right, but the file it
  routed to (`WebtoonSubsamplingItem`) turned out to be **dead code in Nekoread**
  (nothing in that app calls it), and an ordinary comic page (~1.8-2x its width)
  never matched the rule in the first place — so the user saw literally no change.
* **The ported chunked renderer** (0.10.13): `WebtoonChunkedImageView`, ported
  faithfully — region-decoded chunks of at most 2048px, nearest-to-the-viewport
  first, two workers a page. It is a real renderer, and it came out as black fields
  and displaced blocks on the user's phone; the reference app's own log says the same
  thing about it (`path=TALL_CHUNKED` … "one solid black field with no error and no
  spinner"). That is exactly why the reference app reaches for it only as a LAST
  RESORT, for a strip whose single decode cannot fit its 48MB budget, and never for a
  normal webtoon page. It is deleted here — together with `NekoPageView`, the Compose
  host it needed.

So the rule is one line: **a page is one bitmap, or it is a failed page with a Retry
row.** `page` decodes it, `prefetch` warms the ones ahead, and `isTallPage` /
`TALL_RATIO` survive only to pick which byte budget a page gets and to say so in the
log. There is no renderer to route to and no route to decide.

Consequences to respect:

* **Never hand the decoder a hardware bitmap.** `ARGB_8888`, never `HARDWARE`, and
  never `ImageDecoder`'s default: the entire correctness of this shape rests on the
  bitmap being software memory that the compositor is free to tile.
* **Never cut a page up.** No slices, no chunks, no `BitmapRegionDecoder`: every one
  of those has been tried here and every one of them drew the page in pieces.
* **Never widen a decoded page.** The decoded width is the screen's width or the
  source's, whichever is smaller, and the only reduction is `inSampleSize`, applied
  while the file is read so the full-size pixels are never allocated even for a
  moment. The log line ("page 1280x5000 -> bitmap 1080x4219 (sample 1, 18MB,
  strip)") is the fastest way to see which budget a page took and what it cost.
* The file is the unit. A page's pixels come from `MangaPageState.Ready.file` and
  nowhere else, and a FILE is never mutated in place (the loader writes a fresh name
  per fetch) — which is what makes the decoded-bitmap cache safe to key on the file's
  path, and what makes a retry decode the new file instead of serving the old one.
* `fitWidth` is a `ContentScale` on an ordinary `Image`: full width for
  `MangaFit.WIDTH` and for the continuous webtoon mode (inside a box of the page's
  own aspect ratio), fitted inside the viewport for the other fit modes. It is a
  property of the MODE, decided by the container, which is the thing that knows how
  much room the page was given — and it applies in the continuous mode too, where the
  fit modes give every page one viewport of its own (see `WebtoonRunBody`).
* The page's real size still comes from the loader's header read, which is what sizes
  an item BEFORE it is drawn — a box of unknown height jumps under the reader's thumb
  as pages land.
* Nothing in the drawing path takes touch events: the reader's own container owns
  every gesture, and an `Image` does not compete for them.
* `Enhance images` applies to every page, strips included: it is a Compose
  `ColorFilter` on the page's `Image`, and there is only one `Image` now.
## 5. If you touch the reader

* Do not add an effect that scrolls in response to `page`/`firstVisibleItemIndex`.
  Add a `ScrollRequest`.
* Do not key a `LazyColumn` item by index. `RunItem.key` is the identity.
* Do not pass a page URL to `AsyncImage` and do not decode a page file yourself
  (see 4a). A page is ONE bitmap from `PageBitmaps`, drawn by an ordinary `Image`,
  and that is the only drawing path there is. Do not add a second one — no slices,
  no chunks, no `BitmapRegionDecoder`, no subsampling view, and never a `HARDWARE`
  decode.
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
