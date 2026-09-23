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
* **A burst of installs rebuilds the provider list ONCE, not once per install.**
  Every install used to end with `manager.refresh(); reloadInstalled()` — and
  `ProviderManager.refresh()` re-instantiates every installed provider while
  `reloadInstalled()` re-reads and re-parses the whole stored list, so an
  "Install all" run over a 249-extension repo did that 249 times, each pass bigger
  than the last. `ExtensionsViewModel.requestRefresh()` now asks for the rebuild
  through a debounced tick (`REFRESH_QUIET_MS`), `installAllPlugins` settles it
  once at the end, and `ProviderManager.refresh()` coalesces overlapping requests
  itself (a request that arrives mid-build is served by one more build at the end,
  never a build of its own). If you add an install path, call `requestRefresh()` —
  never `manager.refresh()` directly.

## Preferences: deduplicate, and parse off the main thread

Every preference is read as a flow (`AppStore`), and the readers are composables —
which collect on the MAIN thread. `store.data` emits on every write ANYWHERE in
the app, so an undeduped flow re-ran its own `map` — including `parseProviders`,
`parseCollections`, `parseCategories` and the ad lists — on the main thread on
every unrelated write, while the UI waited for the frame. That is what made
installing an extension (whose very first act is a providers write) feel jittery,
and what hitched the UI while a settings slider was dragged.

So every one of those flows is `store.data.map { … }.distinctUntilChanged().flowOn(Dispatchers.Default)`,
and the rule for new ones is the same: deduplicate the value, and do any parsing
or list-building OFF the main thread. `distinctUntilChanged` also keeps Compose
from being handed a fresh-but-equal list, which is one recomposition per write
that nobody needs.

## The performance booster

`AppStore.perfModeFlow()` (Settings → **Performance**) is one switch that drops
the work which costs the most frames per second for the least information, for a
device that cannot keep up:

* `ui/PosterStyle.rememberPosterStyle` drops the blurred halo and the animated
  treatments — the halo is three artwork layers plus a gaussian blur *per poster
  card*, the most expensive thing the UI does while a row is scrolled;
* `ContentRepository.deviceFanOut()` has the fan-out (see below) — the same
  servers are still found, in more waves;
* `nuvio/NuvioRuntime` gates its engines with `PERF_CONCURRENT` instead of
  `MAX_CONCURRENT` (twelve QuickJS VMs at once is a lot to ask of a phone that is
  also drawing the screen).

The television's own performance mode (`K.TV_PERF`) drops the same work, and the
two are OR'd through `data/PerfMode` — a plain mirror of the two preferences that
hot, non-composable paths (the fan-out, the engine gate) can read synchronously,
the same way `NetTuning` and `SearchScope` mirror theirs. `PerfMode.tvOn` is set by
`HikariApp` alongside the layout sync, so a TV stick that turns the layout's
performance mode on is honoured everywhere at once. Nothing the booster turns off
changes what can be played.

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
* `PlayerActivity.notifySourcesChanged()` coalesces its rebuilds (one queued
  rebuild at a time, `REBUILD_COALESCE_MS`) and the server chooser's chip strip
  skips a rebuild that would produce exactly the same pills. A search that lands
  ten servers in one go used to re-create every row and every pill ten times back
  to back, which is what made the server list stutter while results were still
  arriving. The chooser's LIST is append-only for the same reason (see
  `builtSig`/`keep` in `showServerChooser`): a rebuild that re-created the row a
  finger was already pressing turned the tap into an `ACTION_CANCEL`.
* **Leaving the player holds the title's background sweep**
  (`StreamsLive.remove(id, holdSweep = true)` → `ContentRepository.pauseSweepFor`).
  A nuvio sweep cold-starts a JS engine per provider, and releasing it from
  `onDestroy` started exactly that while the player was being torn down and the
  previous screen rebuilt — the reported "it stays laggy for a few seconds after
  I come back from the player". The sweep's unasked providers stay on the
  `PendingWork` ledger and are re-asked when the title plays again.
* **`NuvioRuntime.MAX_CONCURRENT` is 12 on purpose — do not "tune it down".** Each
  nuvio engine is a native QuickJS VM plus its own context, so the count IS
  bounded, but the bound is set by how many engines a real install has, not by
  how many a device "feels like" running: a curated install is about a dozen, and
  a cap below that makes the last providers QUEUE for a slot rather than run, so
  their whole 60 s budget is spent waiting and their servers never reach the
  player (that was the "only 2-3 plugins answer" report). Two extra VMs is a
  rounding error next to the engines that then answer inside the same window.
  The pass's deadline is raised to match whenever nuvio targets are present (see
  docs/SEARCH.md §7) — a pass that gives up before an engine's own timeout
  cancels it mid-run and pays for a second boot plus a second round of network
  work in the sweep, which is strictly more expensive than waiting.
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

## The nuvio provider bridge is asynchronous, and it is the reason to care

Everything above is about Hikari's own code. A nuvio provider is other people's
code running in a native QuickJS engine, and the bridge it fetches through is the
single biggest lever on how fast a search feels — the reference client lists the
same engines' servers in a couple of seconds.

- **Async, not blocking.** `NuvioRuntime.bridgeFetchAsync` is registered with
  `QuickJs.asyncFunction`, so JS gets a real promise and `await fetch(...)`
  suspends the engine's thread instead of parking it: a provider's own
  `Promise.all` really overlaps. `assets/nuvio/harness.js` awaits it
  (`__nuvioFetch`), and its response interceptors are `async` — but the
  `Promise.resolve(...)` shape keeps working with a synchronous host.
- **Bounded by the network, not by a pool.** The old code submitted every fetch
  to `Executors.newFixedThreadPool(4)` and blocked on it — four nuvio requests in
  flight, app-wide, no matter how many engines were running. It is now OkHttp's
  own dispatcher (`maxRequests = 64`, `maxRequestsPerHost = 12`) with
  `enqueue` + `suspendCancellableCoroutine`.
- **Cancellable.** `invokeOnCancellation { call.cancel() }` — closing the engine,
  the pass's deadline, or the user leaving the player really aborts the socket
  instead of leaving a 90-second request running behind the next screen.
- **Classified honestly.** A provider that crashes reports `✗ provider failed: …`
  and is re-asked; a provider whose site has nothing reports `no sources for this
  title` and is not (see docs/SEARCH.md §9).

**How to check a change here:** the harness can be exercised without a phone — run
`assets/nuvio/{boot,cheerio,crypto-js,harness}.js` in a JS sandbox with a stub
`__hikariFetch` that returns canned payloads, and assert (a) three concurrent
300ms fetches finish in ~300ms, not ~900ms, and (b) a TMDB call with a dead
`api_key` comes back with Hikari's key and an injected `imdb_id`.

## The search fan-out is sized to the device, and every engine is bounded

Three rules came out of the "it is slow AND it freezes while the servers load"
report (a 390-target pass on a 4-core phone):

- **The fan-out scales with the device.** `ContentRepository.deviceFanOut()`
  (`cores × 6`, clamped to 24..96) sizes both `CROSS_EXT_SEARCH_CONCURRENCY`
  and the wave ceiling `CROSS_EXT_WAVE_MAX`. The old fixed 96 was chosen on a
  desktop-class assumption: searches, the HTML/JSON parsing they do on return,
  ~12 QuickJS engines booting beside them and the UI's own drawing all compete
  for the same cores, and on a phone that is the freeze. Nothing is skipped — the
  queue still drains in waves (`launchWave`) — and the tail lands SOONER,
  because the head is not thrashing.
- **A nuvio engine is never cancelled while it is working.** The pass ceiling
  exists so the other extensions cannot eat the whole budget, but cancelling an
  in-flight nuvio engine produces no answer: the teardown hands it to the
  background sweep, which boots a SECOND VM and repeats every fetch. So the pass
  waits up to `NUVIO_TAIL_MS` (25s) past its ceiling while one is still running;
  `NuvioRuntime.CALL_TIMEOUT_MS` (60s) is the real bound. This is the reported
  "some extensions show servers in the nuvio app and are just cut off here".
- **Every engine has a memory budget** (`NuvioRuntime.ENGINE_MEMORY_LIMIT`,
  256MB). QuickJS allocates NATIVE memory, which the Java heap cap does not
  cover — before this, one runaway provider could allocate until the low-memory
  killer took the process ("it almost crashes while the sources load").
- **Cheerio is loaded only when the provider asks for it.** `cheerio.js` is
  ~440KB of JavaScript that every fresh engine used to execute before the
  provider ran a line. `NuvioRuntime.needsCheerio(source)` is a permissive look
  at the provider's TEXT (any mention of cheerio, or `$(`), and a provider that
  fails without the bundle is retried once WITH it (`getStreams`), so the
  optimization can only ever turn a silent breakage into a working engine.

**How to check a change here:** the pass's own log line now ends with
`MemoryReport.short()` (`java used/maxMB native …MB`), the app logs the device's
heap classes at startup, `onTrimMemory` logs the level Android chose, and every
nuvio provider that comes back empty logs the exact TMDB id/media type/season it
was asked with plus the HTTP trail from its own call (`no HTTP request at all`
means the arguments were wrong for it, not that its site was empty). One search
with the log is enough to see both halves of the problem.
