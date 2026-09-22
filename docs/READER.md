# The reader — requests, the webtoon run, and page images

`ui/screens/MangaReaderScreen.kt` is the biggest screen in the app, and four of
its rules are load-bearing enough that they are written down here: **nothing moves
the pages except a request**, **a webtoon is a run of chapters**, **a page is
fetched by `manga/MangaPageLoader` and drawn by `manga/SubsamplingPageView`, and
is NEVER decoded whole**, and **webtoon is the default reading mode** (an
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

## 4. Page images: `manga/MangaPageLoader` → `manga/SubsamplingPageView`

Two objects own a page, and the split between them is the point:

* **`MangaPageLoader` owns a page from bytes to a FILE.** It fetches the page with
  the extension's own headers, checks it, and writes it to
  `cacheDir/manga-pages/`. It does not decode the picture.
* **`SubsamplingPageView` owns a page from a FILE to pixels.** It is a
  `SubsamplingScaleImageView` (the Tachiyomi fork,
  `com.github.tachiyomiorg:subsampling-scale-image-view`, pinned in
  `gradle/libs.versions.toml` — the same artifact and commit the manga sibling
  Nekoread draws with, so the drawing path here and there is the same code) that
  opens the file, reads its bytes once, and decodes only the REGION the viewport
  is showing, as tiles.

`PageContent` is the only place a page is drawn: a `SubsamplingPageView` inside
an `AndroidView`, handed the file and the mode's shape, with a spinner over it
until its first layer is up. A file the view cannot OPEN at all is reported back
through `MangaPageLoader.markUndecodable`, which drops the file and puts the page
on the `Failed` row — where the Retry button already is. The reader never hands a
page URL to Coil, and never decodes a page itself.

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

### 4a. A page is NEVER decoded whole — this is the whole reader

Both versions of "the image in the reader is breaking" came from decoding a page
into one bitmap and drawing it, and neither could be fixed from the fetch side:
the bytes were always fine. The second one is worth writing down, because it looks
like it should have worked.

A bitmap handed to the compositor becomes a GPU texture, and a texture cannot be
larger than the device's `GL_MAX_TEXTURE_SIZE`. An image bigger than that is not
drawn by Skia — it is drawn as a GRID OF TILES, and that fallback mis-places the
tiles' source rectangles on the drivers these phones have. What the reader shows
is bands of the page displaced sideways, artwork repeated at a fixed offset, a
white seam at every tile boundary and the whole thing running off both screen
edges. 0.10.9 tried to fix that by cutting the decoded page into slices no taller
than 2048px and stacking them — and it did not hold, because "smaller than the
limit" is not a property of the page: a webtoon page is still 1600×8000, the
phones that report a 2048 limit are exactly the ones the slices have to satisfy,
and any page that still ends up over the limit goes back to the tiled draw. A
recipe that has to guess a device's texture limit will be wrong on some device.

So the page is not drawn from a bitmap at all. `SubsamplingPageView` region-decodes
the file: it decodes TILES — a downsampled base layer at about the resolution the
screen can show, plus the higher-resolution tiles the viewport actually needs — and
it does that again as the reader scrolls. A 1080×20000 strip is never decoded at
its own size: what a page costs is the file's bytes plus a handful of viewports of
pixels, whatever its height, and every tile is a legal texture on every device
because nothing here depends on the device's texture limit. (The tile ceiling is
pinned with `setMaxTileSize(2048)` in `SubsamplingPageView` for exactly that
reason: a viewport wider than 2048px — a tablet, a desktop window — must not be
allowed to produce a tile the GPU cannot take.)

Consequences to respect:

* **Never** hand a page's pixels to a Compose `Image`/`AsyncImage`, however
  "small" the page looks. That is the path that breaks.
* The file is the unit. A page's pixels come from `MangaPageState.Ready.file` and
  nowhere else, and `showPage` is a no-op for the file already open (the reader
  recomposes on every scroll frame).
* The view refuses every touch (`onTouchEvent` returns `false`), and zoom is off,
  because the container owns the gestures: a tap toggles the chrome and a drag
  scrolls the strip or swipes the pager. If a zoom gesture is ever added, enable
  it in `SubsamplingPageView` and disambiguate in the container — do not take
  touches away from the container silently.
* `fitWidth` is the `SubsamplingScaleImageView` scale type, and it is a property
  of the MODE: full width for the webtoon strip and `MangaFit.WIDTH`, fitted
  inside the viewport for the paged fit modes.
* The page's real size still comes from the loader's header read, which is what
  sizes each strip item BEFORE it is drawn — a strip of unknown-height boxes jumps
  under the reader's thumb as pages land.

Things the loader also owns:

* **the preload window** — `plan(pages, current)` asks for ten pages ahead, eight
  behind, then the rest of the chapter forward, four at a time, and skips itself
  when the reader has moved less than two pages since the last call (it runs on
  every scroll step). There is nothing else to warm up: a page whose bytes are on
  disk is a page that appears when the thumb reaches it.
* **the page's real size**, read from the file header (`bounds`), which is what
  lets the strip and the paged fit lay a page out at its true aspect ratio before
  it is drawn.
* **What the sheet's switches mean.** `Enhance images` is a colour matrix
  (`manga/MangaEnhance.kt`) applied when the page is DRAWN — as a Compose
  `ColorFilter` on the few `Image`s in the reader's own chrome, and as a platform
  `ColorFilter` on the page view (which paints its tiles itself and cannot hold
  one, so `SubsamplingPageView.onDraw` draws the page through a saved layer that
  carries it). No second decode and no extra bitmap, which is why it can stay on
  while the strip scrolls.

The page cache is capped (`CACHE_CAP_BYTES`, pruned least-recently-used at most
once a minute; a pruned page's file and `Ready` state go together, so the page is
fetched again rather than drawn blank).

## 5. If you touch the reader

* Do not add an effect that scrolls in response to `page`/`firstVisibleItemIndex`.
  Add a `ScrollRequest`.
* Do not key a `LazyColumn` item by index. `RunItem.key` is the identity.
* Do not pass a page URL to `AsyncImage` and do not decode a page file yourself
  (see 4a). A page is drawn by `SubsamplingPageView`, from the loader's file.
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
