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

## 4. Page images: `manga/MangaPageLoader` → `manga/PageBitmaps` (short) / `manga/ChunkedPageView` (strip)

Two objects own a page, and the split between them is the point:

* **`MangaPageLoader` owns a page from bytes to a FILE.** It fetches the page with
  the extension's own headers, checks it, and writes it to
  `cacheDir/manga-pages/`. It does not decode the picture.
* **`PageBitmaps` owns a SHORT page from a FILE to pixels.** It decodes the file
  ONCE — at most at the width the screen can show, and at most `MAX_PAGE_PIXELS`
  (`1_000_000`) and `MAX_DECODE_HEIGHT` (`2048`) on either axis — and keeps the
  result in a byte-budgeted LRU. That is also where the page AHEAD of the reader is
  decoded (`prefetch`, called from the reader's `report`), which is the difference
  between a page that is on screen when the thumb arrives and a spinner for the few
  hundred milliseconds a decode costs.
* **`ChunkedPageView` owns a STRIP from a FILE to pixels**, and it never builds a
  bitmap the height of the page (see 4a). It decodes the page's rows in bounded
  chunks, nearest to the viewport first, and draws only the chunks that overlap it.

`PageContent` is the only place a page is drawn: one bitmap inside an ordinary
Compose `Image`, scaled with the fit the MODE asked for, with a spinner while it
is being decoded (a page that is already decoded is drawn on the frame it is
composed on, with no spinner at all). A file that cannot be DECODED is reported
back through `MangaPageLoader.markUndecodable`, which drops the file and puts the
page on the `Failed` row — where the Retry button already is. The reader never
hands a page URL to Coil; the only thing that region-decodes anything is
`ChunkedPageView`, and it is the ported chunked renderer of section 4a, not a
setting of ours.

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

### 4a. Two drawing shapes, decided by the page's own proportions — this is the whole reader

Every version of "the image in the reader is breaking" came from a page being
drawn in a shape the platform cannot draw. The bytes were always fine, and the
fetch side could never fix it. The five shapes that were tried, in order:

* **A decoded page drawn by the platform, whole** (0.9x). A bitmap handed to the
  compositor becomes a GPU texture, and a page taller than the device's
  `GL_MAX_TEXTURE_SIZE` **cannot be one texture** — the fallback the compositor
  uses instead splits it, and on these phones the split comes out as artwork
  scattered into displaced blocks with black tiles where art should be. That is
  what the screenshots show.
* **A decoded page cut into slices by us** (0.10.9): decode the page, cut it into
  2048px slices, stack them. It did not hold, because "smaller than the limit" is
  not a property of the page — a webtoon page is still 1600×8000, and the phones
  that report a small limit are exactly the ones the slices have to satisfy.
* **A page region-decoded by a subsampling view, configured by us** (0.10.10):
  the right library, our own view, our own settings — and the page still drawn as
  a bitmap in Compose-land. It changed nothing.
* **A page decoded whole at a big budget** (0.10.11): the same failure as the
  first one, because a "budget" of 11M pixels permits a 1080×10000 page, which is
  still not a texture.
* **A subsampling view for strips only** (0.10.12): the routing rule
  (`h > 3w → NekoPageView`, `SubsamplingScaleImageView`) was right, but the file it
  routed to (`WebtoonSubsamplingItem`) turned out to be **dead code in Nekoread**
  (nothing in that app calls it), and an ordinary comic page (~1.8–2× its width)
  never matched the rule in the first place — so the user saw literally no change,
  which is exactly what they reported.

The fix is not another size, and the fifth time it is the REAL port: the reader of
this app's manga sibling **Nekoread** (Tachiyomi/Mihon's reader, `WebtoonPageHolder`
→ `WebtoonChunkedImageView`), whose reader loads these exact pages correctly on the
phones where this one did not, and which the user asked to have ported. Nekoread
draws a page one of exactly two ways, and the page's own proportions decide which:

* **h ≤ 3w — the ordinary page, and the ordinary manhwa page.** One bitmap,
  decoded at the screen's own width and drawn by an ordinary Compose `Image`
  (`PageBitmaps`). The budget is Nekoread's own for short pages —
  `WEBTOON_MAX_DECODE_PIXELS = 1_000_000` and a `2048` ceiling on either axis —
  and it is a budget about the COMPOSITOR, not about the heap: every bitmap drawn
  here becomes a GPU texture, a page-sized one has to be split into a grid of
  tiles, and on the phones that reported the broken pages that split is what came
  out as artwork scattered across a rigid lattice. At 1M px and ≤2048 on an axis
  the bitmap is always one texture, and the page is still the whole picture at the
  width the screen shows it at.
* **h > 3w — a webtoon strip.** `NekoPageView` → `ChunkedPageView`, a direct port
  of Nekoread/Tachiyomi's `WebtoonChunkedImageView`, given the page FILE. It
  decodes the page into chunks of at most 2048 decoded px with a FRESH
  `BitmapRegionDecoder` per chunk, decodes nearest-to-the-viewport first (two
  workers per page, three decodes at once across all pages), keeps decoded chunks
  for the page's lifetime, recycles only the chunks farthest from the viewport past
  a 64MB budget, and draws only the chunk range overlapping the viewport plus one
  chunk of margin — **so nothing it ever hands the compositor is bigger than one
  chunk**, however tall the page is. Chunk i covers source rows `[i·2048·sample, …)`
  and is drawn exactly there, so chunks tile with no gaps and an undecoded chunk
  skips only its own rows. `MAX_DECODE_DIM = 4096` bounds either axis of a chunk and
  the decode width follows the width the page is DRAWN at (`fitInside` = the paged
  fit modes; the strip fills the view's width). A chunk that fails is skipped and
  gets one more chance when it enters the window; a chunk the reader is LOOKING at
  that fails reports the page broken, so a damaged file becomes the reader's
  `Failed` row instead of a silent black band. Touch is ignored, so the reader's
  list keeps every gesture.

Consequences to respect:

* **Never draw a strip as a bitmap.** `PageBitmaps.page` refuses a tall page (and
  logs it) rather than decode one; `prefetch` skips strips entirely — the file on
  disk IS the warm-up for them (chunks are region-decoded out of it when they come
  on screen), and a decoded strip would be exactly the page-sized bitmap the strip
  path exists to avoid.
* **Never widen a decoded page.** The decoded width is the screen's width and no
  more, and a chunk's decode width follows the width the page is DRAWN at. Both
  paths log what they produced ("page 800x1600 → bitmap 800x1600 (sample 1)",
  "page 800x20000 → 800px, 10 chunks of 2048"), which is the fastest way to see
  which shape a page took and what it cost.
* The file is the unit. A page's pixels come from `MangaPageState.Ready.file` and
  nowhere else, and a FILE is never mutated in place (the loader writes a fresh
  name per fetch) — which is what makes the decoded-bitmap cache safe to key on
  the file's path, and what makes a retry visible to the view (`setPage` is
  idempotent for the file it already holds, and picks up a new one).
* `fitWidth` is a `ContentScale` on an ordinary `Image`: full width for the
  webtoon strip and `MangaFit.WIDTH` (inside a box of the page's own aspect
  ratio), fitted inside the viewport for the paged fit modes. It is a property of
  the MODE, decided by the container, which is the thing that knows how much room
  the page was given. For a strip the same flag becomes `ChunkedPageView.fitInside`
  (`!fitWidth`): the strip fills the view's width, the paged modes scale the whole
  page inside the viewport.
* The page's real size still comes from the loader's header read, which is what
  sizes each strip item BEFORE it is drawn — a strip of unknown-height boxes jumps
  under the reader's thumb as pages land.
* Nothing in the drawing path takes touch events. The reader's own container owns
  every gesture (a tap toggles the chrome, a drag scrolls the strip or swipes the
  pager), and neither an `Image` nor `ChunkedPageView` competes for them.
* **`Enhance images` applies to short pages only.** It is a Compose `ColorFilter`
  on the page's `Image`, and a `View` that draws its own bitmaps has no equivalent
  — a strip is drawn by the view, unfiltered.

## 5. If you touch the reader

* Do not add an effect that scrolls in response to `page`/`firstVisibleItemIndex`.
  Add a `ScrollRequest`.
* Do not key a `LazyColumn` item by index. `RunItem.key` is the identity.
* Do not pass a page URL to `AsyncImage` and do not decode a page file yourself
  (see 4a). A page's drawing shape is decided by its proportions and by nothing
  else: h ≤ 3w is one bitmap from `PageBitmaps` drawn by an ordinary `Image`,
  h > 3w is `NekoPageView` (which is `ChunkedPageView`, ported — see 4a). Never a
  third path, and never our own `BitmapRegionDecoder`/subsampling settings.
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
