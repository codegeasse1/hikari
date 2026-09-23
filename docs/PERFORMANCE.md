# Performance — the rules this app is built to, and where they are enforced

Smoothness here is not a matter of taste: every rule below exists because breaking
it produced a specific, reportable symptom (a stutter while scrolling, a janky
extension install, an OutOfMemoryError on a low-memory device). If you add code to
one of these paths, keep the rule.

## The main thread does no I/O and no decoding

* **Extension loading is a dex load** (`manga/MangaExtensionManager`,
  `aniyomi/AniyomiExtensionManager`) and instantiates the extension's classes. It is
  blocking by nature and is only ever called from `Dispatchers.IO` — the readers
  fetch it that way, and `reader/source/ExtensionPageImageFetcher.kt` resolves it
  inside a Coil `Fetcher`, which Coil always calls off the main thread. A model
  carries the extension's id, never the extension itself, precisely so that a
  composable can name it without loading it (`ExtensionCoverRef`).
* **Posters and covers are decoded off the main thread, at the size they are
  drawn.** `ui/PosterLoader` decodes a stored poster payload on a two-thread
  executor (`hikari-poster-prep`, daemon, `MIN_PRIORITY`) and hands composition a
  null model with a per-poster state that recomposes exactly the cell that asked
  for it — the previous design used one shared revision, so every finished decode
  recomposed every cell on screen while the user scrolled.
* **A still of an animated cover is decoded SAMPLED** (`decodeStill`): a bounds
  pass, then `inSampleSize` to `STILL_MAX_PX` (512) in `RGB_565`. It used to be a
  full-size `decodeByteArray` per animated cover — several MB each, held for the
  session, which is a low-memory device being killed while a grid scrolls.
* **The small blocking reads at start-up are deliberate and bounded**
  (`MainActivity`: the TV-mode and fullscreen flags, `withTimeoutOrNull(3s)`),
  because the first frame has to already be the right shape and Compose cannot
  await a DataStore read. Do not add a third one; everything else is async.

## The extension install path

* Download → byte-check (`PK` magic) → write → load → provider rows, all on
  `Dispatchers.IO` (`MangaExtensionManager.install`). The package name is read from
  a temp file that is deleted immediately, and the final move is a rename inside
  the extension directory.
* The one path that copies bytes is streamed (`inputStream().copyTo(outputStream())`)
  rather than `temp.readBytes()`: an extension is tens of megabytes and a second
  full copy of it in RAM is the spike that gets a low-memory device killed
  mid-install.
* `ExtensionNsfw.isNsfw` loads an extension's metadata, so it is only ever asked
  when the adult-content switch is OFF (with the default ON nothing is looked up)
  and its answer is cached per `.ext` file.

## Layout passes are bounded and never nested

* `player/PlayerActivity.presentGlass` re-measures a panel's height cap a bounded
  number of times, and `applyHeightCap()` refuses to re-enter
  (`inCapPass`): the pass ends by requesting layout on the views whose layout
  listeners call it back, and a synchronous re-entry inside that layout is how a
  panel starts to shudder while its list is scrolled. See
  [PLAYER_PANELS.md](PLAYER_PANELS.md).
* `CurvedGlassPanel.rebend()` is coalesced onto the next frame's animation phase:
  a scroll emits one change event per pixel, and bending rows on each of them laid
  the whole panel out several times per frame.
* `PosterLoader.pending` holds one state per poster, capped at `PENDING_MAX`; the
  still cache is capped at `STILLS_MAX`.

## Coil

* One loader for the whole app (`HikariApp`), memory cache sized to the real heap
  (`1/8`, floored at 24 MB, capped at 96 MB) instead of Coil's 25% default, which
  on a poster grid can fill a small heap by itself.
* `respectCacheHeaders(false)`, so a poster whose CDN sends no cache headers still
  lands in the disk cache instead of being re-fetched on every recomposition.
* Stored posters are requested with `diskCachePolicy(DISABLED)`: their bytes are
  already persisted by `PosterLoader`, and a second copy in Coil's disk cache is
  pure waste.
* The image interceptor retries a request with header variants instead of letting a
  hotlink-protected CDN hand back a 403, and it CLOSES the previous attempt before
  proceeding (`OkHttp` throws from a dispatcher thread if it is still open, which
  takes the process down).

## How to check a change here

There is no profiler in this repository, so the honest check is:

1. `grep` the path you touched for `runBlocking`, `Thread.sleep`, `BitmapFactory`,
   `Http.get`/`getString`/`downloadBytes` and `PackageManager` — each of those on a
   composable or a main-thread `LaunchedEffect` path is a bug.
2. Read the `Logs.log(...)` lines after a change: cold start, first grid, reader
   open, extension install. They are what a bug report can be reconstructed from.
3. On a low-end target, watch `Runtime.getRuntime().totalMemory()` while scrolling
   a poster grid and while reading a long strip: it must plateau, not climb. The
   reader's own memory is bounded by Nekoread's renderer (region-decoded pages from
   disk, chunked only for a strip too tall for one decode — see
   [READER.md](READER.md)); do not add a code path that holds a whole page in the
   heap.
