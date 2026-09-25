# How the source search works — and the invariants that must not be broken

This file is for whoever touches the search next. Every rule below exists because
its absence was reported as a bug by a user. The reports are quoted so nobody
"simplifies" these away.

Everything lives in `ContentRepository.kt` unless stated otherwise.

## The shape of one lookup

1. **Primary targets** — the provider the title was opened from, plus (for a
   Stremio origin) the other Stremio addons, plus Nuvio providers when the item
   resolves to a TMDB id. These search by the provider's own id and their servers
   stream into the list as they land.
   With **Server search: "Only this extension"** (Settings → Playback & Servers,
   `SearchScope.allExtensions == false`, the DEFAULT since 0.10.39) it is the
   origin ALONE: no sibling addons, no nuvio engines, no cross pass, no sweep,
   and no episode list borrowed from another site. The switch is read once at the
   top of `streamsForInner` into a local, so one lookup can never be half-scoped.
   Three things widen it, each with its own switch and its own rule:
   **exception extensions** (`SearchScope.exceptions`, invariant 6), and the
   **per-engine family set** (`SearchScope.engineFamilies` — invariant 17), which
   covers Nuvio and Stremio (on by default) and every other engine separately
   (off by default).
   **An item with no origin is the exception to all of them** — see invariant 10.
2. **Same-engine family** — the other repos of the origin's own engine, when that
   engine's family switch is ON (invariant 17). They are queued first in the cross
   pass. With the switch off there is no same-engine step at all: the title's own
   repo is the only one asked, which is what "only this extension" promises.
3. **Cross pass** — every other installed extension asked *by title*:
   search → pick the best matching entry (`confidentTitleMatch`) → resolve its
   meta + episode list → extract servers.
4. **Background sweep** — the providers the pass did not finish with are re-asked
   while the video plays, on the application scope. It carries BOTH kinds of
   work (`SweepUnit.byTitle`): repos to search by title, and the pass's primary
   targets to ask directly (a nuvio engine resolves from the TMDB id alone).

## Invariants

### 0. Nothing a lookup starts may be dropped — for ANY engine

The user's report, twice: *"the first time it searched everything except nuvio,
the second and third time nuvio was there"*, and *"you told me it was fixed, so
why does it still show all servers once and skip others"*. Both halves are this
invariant.

- **Every provider a lookup asks is either asked or handed over.** The pass's
  primary targets (the title's own extension, the nuvio engines, the Stremio
  addons) and the cross repos all end up in `PendingWork` unless they really
  ANSWERED. Answered means: the provider's own last call ended in an answer
  (`providerOutcome == "no servers"`, or it returned servers) — a timeout, a
  thrown failure, or a call cancelled while the pass was being torn down
  (`"✗ cut off after Ns (still searching)"`) is NOT an answer and IS re-asked.
  Never reintroduce a family-specific path here: nuvio is excluded from
  `crossExtensionTargets` (it is asked by id, not by title) and that exclusion
  once meant a nuvio engine that lost the race with the pass's clock was thrown
  away with nothing left to re-ask it.
- **A refused sweep is not a dropped one.** `MAX_PARALLEL_SWEEPS` may refuse to
  start a sweep; the work stays on the ledger and `drainSweeps()` (run whenever a
  sweep ends) starts it as soon as a slot frees. Automatic retries stop after
  `SWEEP_MAX_TRIES`, and a lookup the USER starts resets that count.
- **"No such title" is still not re-asked** (it is a real answer, and re-asking
  it is the work that made the pass crawl), and a provider sitting behind a
  Cloudflare wall (`crossCfSkip`) is dropped on purpose — but that drop is now
  *counted* in the pass's `skipped=` line, because it is invisible on screen and
  was previously invisible in the log too: 100+ Hikari repos disappeared between
  two passes of the same title and no line said why.
- **One provider list per pass.** `streamsForInner` reads `manager.providers`
  ONCE and hands that snapshot to `crossExtensionTargets`; `families=` and
  `installed=` in the pass's log line therefore always describe the same list.
  It used to re-read the manager, so a refresh landing in between made a pass ask
  ~100 fewer repos than the line next to it reported.
- **The skip reasons belong to the pass that prints them** (`CrossTally.
  filterReasons`, never a shared field): two passes run at once all the time, and
  the shared field let one print the other's reasons.

### 1. Every EXTENSION provider call is a plain blocking call

`withTimeoutOrNull` gives up *logically* while the thread stays parked forever.
So anything that WAITS on such a call can be wedged indefinitely. Consequences:

This is true of the extension families (hiki/cs3/universal/skystream/aniyomi):
their plugin runtimes are other people's code and nothing can interrupt them.
**A nuvio engine is the exception** — its fetch bridge is a real suspension
point, so cancelling a nuvio call really cancels its HTTP (see invariant 8).

- **Never wait unboundedly on provider work.** The sweep runs each repo through
  `detached(SWEEP_REPO_BUDGET_MS) { … }` and moves on when the budget expires.
  Never restore the old `coroutineScope { for (target in targets) launch { … } }`
  shape — one parked plugin then held the round (and every later round) open
  forever: the "stuck on 30 still searching and it never moved" report.
- **The pass has a wedge watchdog** (`CROSS_EXT_STALL_MS`): if nothing at all
  completes for 25 s while work is outstanding, the pass ends early and every
  repo it has no answer from goes to the background re-ask. That automatic retry
  is exactly what the user's own workaround ("back out and press Play again")
  did — a fresh call is what gets an answer out of a plugin whose runtime locked
  up while cold-starting.
- **Slots must be refundable per call.** `inFlight` is keyed by a unique call
  token (`gated`), not by provider id. Keying by provider let a later call
  overwrite a wedged one's entry, so its slot was never refunded and the gate
  shrank for the rest of the session until nothing could start.
- **A wedged extension is NOT blacklisted for the session.** `isHung` expires
  after `HUNG_TTL_MS` (2 min). Blacklisting for the session made the automatic
  retry skip exactly the repos that needed re-asking. (A repo skipped as hung is
  deliberately NOT marked `Sweep.answered`, so it stays on the ledger and is
  asked once its wedge expires.)

### 2. First server fast, on EVERY play — not just the second

- **Extraction is progressive**: `launchPendingExtractions()` runs on every
  iteration of the pass loop, in trust order (origin first, then proven repos,
  then the origin's family, then the rest). It used to wait until every one of
  ~250 extensions had been searched, or 45 s had passed, while the whole pass was
  capped at 55 s — so a cold first play could not produce a server for ~45 s and
  the second play (warm caches) was instant.
- **Searches are fanned out in WAVES** (`CROSS_EXT_WAVE_*`): a few repos first,
  widening only while answers come back. Firing ~96 cold plugin runtimes at the
  same instant is what wedges them on a phone. Waves only stagger the start —
  every target is still asked, in trust order (`crossExtensionTargets`).
- **There is NO origin hold at all** (removed in 0.10.39). This used to be a
  window in which playback waited for the extension the title was opened FROM —
  45 s as a backstop in "wait for more servers first" mode, and (after 0.10.38) 0
  in the instant mode. The instant-mode zero was not enough, because the user's
  setting was not always the instant one and because a *third* report arrived
  anyway: *"see still not playing when server found but see 56 servers have found
  and its still not playing"*, with the cover reading "Found N servers — still
  searching…" and a 45 s window running out underneath it. The wait is gone in
  every mode: `originGraceMs` and `originHeadStartMs` are 0 on the launch intent,
  `tryStart` in PlayerActivity starts on the first server that arrives, and a
  **failsafe** (`START_FAILSAFE_MS`) starts playback anyway if servers sit on the
  list for four seconds with nothing committed — which makes "the first server
  starts the video" structural rather than a property of the current conditions.
  The origin is not punished: it is still asked first, still gets the first
  engine slot, its servers still sort to the top of the list and to the front of
  the player's own list, and any that land later stream into "Select server"
  while the video plays. What the hold used to protect — a replay continuing on
  the server that worked last time — is preserved as ORDER instead of waiting
  (the remembered server is placed directly after the origin's rows).
  "Wait for more servers first" is still honoured: it waits for a COUNT of
  servers, never for one provider.

### 3. Every state on screen must be able to resolve

- The sweep is bounded: `SWEEP_BUDGET_MS` per round, `SWEEP_MAX_ROUNDS` rounds
  (two of each). It used to run 10 min × 6 rounds, so the Sources panel could
  honestly say "still searching" for ~an hour.
- `anySweepBusy` / `sweepBusyFor` are **staleness-aware** (`SWEEP_STALE_MS`): a
  sweep that has reported nothing for 90 s stops counting as an active search.
- The player's Sources line counts `running` only while the tally has changed
  within `SEARCH_QUIET_MS` (PlayerActivity), so a frozen count cannot be shown
  as a running search.
- **The Sources line counts EVERY engine** (`CrossTally.running` +
  `primaryRunning`, and likewise for `asked`/`found`). It used to count only the
  cross repos, so a nuvio engine still cold-booting was in none of its numbers:
  the line said "done" — honestly, by its own books — over a list with no nuvio
  tab at all. Anything whose answer the player is waiting for must move these
  maps, and `crossStatusQuietForMs` (which decides whether a count is still
  moving) is bumped on every primary arrival for the same reason.
- `sweepBusyFor` also reports TRUE while this video has unfinished work waiting
  for a free sweep slot, so the detail screen does not announce "no playable
  server found" over a repo that has not been asked yet.
- The detail screen watches the sweep for at most `SWEEP_WATCH_CAP_MS` (5 min)
  and then commits to a verdict and calls `StreamsLive.markDone`.

### 4. Language: read in the chosen language, search in the original

- **Display**: the page title is the name the page was OPENED with (the provider
  meta never renames it — see the "THE PAGE NEVER RENAMES ITSELF" block), the
  description prefers TMDB's localized `TitleExtras.overview`, and episode names
  come from `EpisodeTitles.lookup(..., language)` with
  `TmdbResolver.contentLanguage`. Auto-translate (`Translator`) runs BEFORE the
  TMDB lookup so a real localized name is never replaced by an English one.
- **Search**: every provider query uses `MediaItem.searchTitle` = the original
  (English) name whenever TMDB knows one. Never search with the display title.
- **An EXTENSION item has no TMDB original name, so one is derived**: the page
  translates the item's own name to English once (`englishSearchName` in
  `DetailScreen.kt`, 2.5 s, skipped for a name that is already Latin, cached in
  memory and on disk by `Translator`), carries it as `originalTitle`, and strips
  the words the translator adds ("The movie Moana" → "Moana"). Without this an
  Arabic-named item hands the Arabic name to every other repo, none of which
  indexes it, and only the engines that resolve by TMDB id answer — the reported
  "in Arabic it plays from nuvio only, in English it finds all the servers".
- **TMDB's own `videos` list must be asked for MORE than one language**
  (`TmdbMeta.extras`): the list only contains videos tagged with the requested
  language, so a non-English `language` returns one trailer (or none). The query
  sends `include_video_language=<app>,en,null`.

### 5. A server must belong to the same show AND the same episode

`confidentTitleMatch` (the only filter the cross pass applies — the origin's own
results are never filtered):

- reject page titles that are really URLs / query echoes (`looksLikeUrlEcho`);
- reject media-kind mismatches (a MOVIE entry for an episode of a SERIES);
- one title's significant words must contain the other's, and the head word must
  survive;
- a repo entry that is a SHORTENED form of what was asked for must still carry ≥2
  significant words — a single word is how an unrelated video gets in ("an adult
  video titled Renegade"), while a repo naming the show MORE fully is accepted at
  any length;
- when both years are known and differ by more than one, only an exact match is
  accepted;
- a candidate whose own name states an explicit episode number must state the one
  being played (`statedEpisodeNumber` — explicit markers only, so "Show Season 2"
  is not misread as episode 2).

`matchCrossEpisode` then maps the played episode onto the repo's own numbering
(same season/number, single-season repos, season-by-position, flat numbering, and
finally the number written in the row's own name).

### 6. An exception extension is one-directional

The user's report: *"if i select mrds and 51cg server, so now if i am on 1show
server and play video it search all server also mrds and 51cg, but if i open mrds
or cg51 extension and trying to play video from these extension it wont search any
other extension for server and only play with its own server."*

- **In force only when the switch is on.** `SearchScope.exceptions` is the CHOSEN
  ids while "Exception extensions" is on and the EMPTY SET while it is off
  (`AppStore.activeSearchExceptionsFlow` is the one place that combines the two,
  and HikariApp mirrors it), so the switch and the list can never disagree
  mid-lookup.
- **Origin is the exception's own repo ⇒ the pass collapses to "only this
  extension".** `originIsException` in `streamsForInner` forces `scopeAll = false`
  and clears `exceptions` for that lookups, whatever the two switches say. This is
  the second half of the report and it is deliberate: a repo the user marked keeps
  its own catalogue to itself. (The nuvio family switch is deliberately stronger
  than this rule for a NUVIO origin — see invariant 15 — because the user asked
  for the nuvio engines to be gathered together in as many words, and that switch
  is the one that turns it off.)
- **Everywhere else they are ADDED, never a replacement.** With `scopeAll` false
  but exceptions present, the pass asks the origin (as always) plus the exception
  repos: nuvio exception ids go through the nuvio (by-TMDB-id) path, and every
  other engine's exception id goes through `crossExtensionTargets(…, onlyIds =
  exceptions)` — so the same title-search, trust order, filters and tally apply,
  over the marked ids instead of over everything.
- **The episode-list fallback follows the same rule**: `episodesFromExtensions`
  may borrow from exception repos when the switch is off, and returns null (as
  before) when there are none. A lookup started inside an exception repo still
  never reaches it.
- **Anything that says how wide the search is must count them** — the detail
  screen's status line ("Searching your extension + N more…") and the "no
  playable server" note (which may only claim a single-repo verdict when there are
  no exceptions in play).

### 7. A nuvio engine must get its turn — and its own budget

The user's report: *"in our hikari it's not extracting all nuvio servers, while
in nuvio app the extractor shows servers from all plugin"*, with 20+ nuvio
providers installed and only 2-3 servers on the list. Three separate limits were
stopping the tail of the provider queue from ever answering:

- **The runtime is compiled once, not once per provider.** Every nuvio call boots
  a fresh QuickJS engine and evaluates `assets/nuvio/{boot,cheerio,harness}.js`
  into it — ~550KB of JavaScript, cheerio alone being 450KB. Compiling that from
  source per provider meant a 20-provider search spent most of its budget
  re-parsing the same bundle 20 times. `NuvioRuntime.bytecodeCache` (with
  `evaluateCached`) compiles each script to QuickJS bytecode once and evaluates
  the bytecode from then on, exactly as nuvio does (`JsRuntime`'s cached
  polyfill/call bytecode). Bytecode is portable across engines of the same
  QuickJS build, and a compile/run failure falls back to evaluating the source,
  so a compiler hiccup can only cost speed, never a provider.
- **The caps are nuvio's own numbers**: `CALL_TIMEOUT_MS = 60_000`
  (`PLUGIN_TIMEOUT_MS`), and `MAX_CONCURRENT = 12` rather than nuvio's 10 — a
  typical curated install is a dozen engines (the sources sheet reports it as
  "Nuvio 12"), and with only 10 slots the last two QUEUE for a slot instead of
  running, so on a phone already running the 400-repo cross-extension sweep their
  whole budget can be spent waiting. nuvio never has this problem because it runs
  nothing but the engines; two extra native VMs is a rounding error next to the
  engines that then answer in the same window. (At 6 engines and 45 s a provider
  it was far worse — most of a 20+ provider install was still queued when the
  budget expired, and a timeout is not an answer, so those providers were "cut
  off", re-asked by the background sweep, and cut off again.)
  **Since 0.10.23 the slots are also the least of it**: the bridge is
  asynchronous (invariant 8), so a queue of engines now actually drains at
  network speed instead of at four-threads-at-a-time, and background sweeps take
  their own small pool (`NuvioRuntime.withBackgroundSlot`) rather than competing
  for these slots at all.
- **The PASS must outlast the engines.** `ContentRepository`'s primary pass ends
  at a deadline, and it used to be 55 s — shorter than the 60 s a nuvio engine is
  allowed. So the tail of the engine set was cancelled mid-run, and because the
  pass's cancellation kills the engine outright, the background sweep had to
  re-ask it from scratch: a second VM boot plus a second round of network work for
  the same answer. When the pass has nuvio targets the ceiling is now the longer
  of `NetTuning.timeout(70_000L)` and 75 s; otherwise it stays at 55 s. The
  deadline only binds while engines are still working, and results stream in as
  they land, so waiting is strictly cheaper than redoing.
- **An anime title must be asked for as a SERIES.** Every nuvio provider we ship
  branches on `mediaType === "tv" ? "tv" : "movie"` (or builds
  `${mediaType}/${tmdbId}` URL segments with it) — **no provider understands
  "anime"**. So an anime resolved to the hint `anime` was looked up as a MOVIE:
  `/movie/<tvId>` coming back 404 an empty list, i.e. a whole category of titles
  with no servers, and the sweep re-asking the same wrong question. `getStreams`
  now normalises the resolver's hint before any engine sees it —
  `if (resolved.mediaType.equals("movie", true)) "movie" else "tv"` — so
  series (including anime and every other `tv`-ish kind) are asked for as "tv"
  and only real films as "movie". Do not add a third value here without
  checking all 29 bundled providers; the contract is two-valued.
- **The engines that produced nothing are now visible.** `nuvioReportLines` in
  `DetailScreen.kt` prints one line per installed nuvio engine in the sources
  sheet (both under the list and in the "no playable sources" state): what it
  answered, or "never ran — no engine slot before the search ended". The maps it
  reads (`NuvioScraper.lastOutcome`/`streamErrors`) are cleared at the start of
  a search, so a provider with no entry genuinely was not asked. Do not remove
  this: without it "why is this plugin's servers missing" is unanswerable from
  the app itself.

**Leaving the player holds the sweep** (`StreamsLive.remove(id, holdSweep =
true)` → `ContentRepository.pauseSweepFor`). A nuvio sweep cold-starts an engine
per provider, and releasing it at `onDestroy` started exactly that while the
player was being torn down and the previous screen rebuilt — the *"it stays laggy
for a few seconds after I come back from the player"* report. The cancelled
sweep's unasked providers stay on the `PendingWork` ledger, and the hold is
released by the next play of the same title (or by `SWEEP_HOLD_MAX_MS`), so
nothing is lost.

### 8. A nuvio engine is asked asynchronously, and never behind a pass

The report that produced this: *"see in nuvio i try playing something and it
shows all this server in less than 5 second, so you know the real issue is not
server time like 75second or 60second cap or anything, the real issue is
something else"*. It was not the cap. Three things were true at once:

- **The fetch bridge was SYNCHRONOUS.** `__hikariFetch` was registered with
  `QuickJs.function` (a plain native call), so JS got its answer only once the
  request was over: a provider doing `Promise.all([fetch(a), fetch(b)])` ran `a`
  and then `b`, and — because every request was submitted to a fixed pool of
  **four** threads and blocked on — at most four nuvio requests could be in
  flight in the whole app, however many engines were running. The reference
  client's bridge is `asyncFunction` over its own HTTP client
  (`await __native_fetch(...)`), which is why the same providers answer there in
  a couple of seconds. **`NuvioRuntime.bridgeFetchAsync`** now does the same:
  `asyncFunction` + OkHttp's `enqueue` through `suspendCancellableCoroutine`,
  with `Dispatcher(maxRequests = 64, maxRequestsPerHost = 12)`. The JS side had
  to follow: `harness.js`'s `__nuvioFetch` awaits the bridge and its response
  interceptors (`__nuvioIntercept`, `__nuvioFixTmdb`, `__nuvioJikanFallback`,
  `__nuvioGraphQL`) are `async` — a *synchronous* fetch wrapped in
  `Promise.resolve()` still works, so the same harness would run over a WebView
  host.
- **Cancellation now works.** The old bridge could not be interrupted
  (`withTimeoutOrNull` gave up logically while the socket stayed open), so one
  hung site — NetMirror in the user's log: three concurrent attempts, "no answer
  in 92s" each — held a VM, a slot and a thread for a minute and a half *after*
  the search that asked for it was over. `call.cancel()` on cancellation fixes
  both the tail and the "it stays laggy for a few seconds after I come back from
  the player".
- **A sweep must never be the reason a pass is slow.** `ContentRepository`'s
  companion keeps a foreground/background gate: `enterForegroundPass()` /
  `exitForegroundPass()` wrap the pass body (the exit is in its `finally`, so a
  cancelled pass still releases it), `quietFor(POST_PLAYER_QUIET_MS)` opens an
  8-second window when the player closes, and `awaitBackgroundClearance()` is
  what the sweep's worker loop calls before every repo. The wait is taken off the
  sweep's round budget (`pausedMs`) and refreshes `sweep.lastProgressAt`, so a
  parked sweep is neither starved nor mistaken for a wedged one.

### 9. "No servers" is an ANSWER; a crash is not

`isNoAnswer()` decides whether a provider is re-asked in the background. A
nuvio engine that threw inside its own JS used to report `"✗ <js error>"`, which
matched none of its keywords, so it was recorded as the answer **"no servers"** —
never asked again, and its line in the sources sheet said the engine had looked
and found nothing. `NuvioScraper` now says what happened:

- `"✗ provider failed: …"` — a crash, a bridge failure, an unreadable payload, a
  budget that ran out, or a TMDB id we could not resolve. Recognised by
  `isNoAnswer()` (which matches `provider failed`), so it is re-asked.
- `"no sources for this title"` — a real answer from a real run. Not re-asked.

The per-provider log line prints the provider's own message instead of a blanket
`no servers`, so a search that comes back thin says which of the two it was.

### 10. An item with no origin is searched by its ID-RESOLVING engines

The report that produced this: *"the same PenguPlay addon shows dozens of servers
in Stremio and in Hikari it says no playable source found"*.

A title browsed from Home / Search / Collections / a nuvio catalogue import
carries `providerId = "tmdb"` (`TmdbMeta`), and `manager.byId("tmdb")` names no
provider. `origin` was therefore null, and every target list was built from "the
origin" — i.e. from nothing. With "Server search: only this extension" on there
was not even a cross pass to fall back on, so the lookup asked **nobody** and
reported "no playable server found" over a title Stremio plays. An origin that has
since been uninstalled or switched off is the same case.

- **`originless` is `origin == null || !origin.config.enabled`**, and for such an
  item the **id-resolving engines are the pass**: every Stremio addon (asked by
  the item's own `tmdb:`/`tt` id) plus the nuvio engines (asked by TMDB id). They
  need no title search, which is why they are the only engines that can answer
  for a catalogue item at all — and it is what the real client does with a
  catalogue id. This holds whatever the "only this extension" switch says, since
  that switch has nothing to restrict to. IPTV items are excluded (a channel
  belongs to one playlist — invariant 6's rule).
- **They are not also searched by title** (`crossExtensionTargets`'s `alsoSkip`):
  the same provider asked twice would put its servers on the list twice, and the
  title route cannot answer for a catalogue-less addon anyway.
- **The verdict says so.** The detail screen's note may not claim "the extension
  this title came from" when there is none, and the advice for this case is "mark
  the addon under Exception extensions" (see the 0.10.35 CHANGELOG note).

### 11. A row on the screen must be a row the player can play

The report: *"it shows so many servers and then says no playable source found"*.
`DetailScreen.playableEvery` (the only thing the player is handed) filters out
`ytId`/`externalUrl` rows and blank-URL non-torrents, while the source sheet is
rendered from the RAW list — so any row class the filter rejects showed as a
server and could never play.

- **Link rows are resolved the moment they are seen**, not six of them at the end
  of a pass: `PlayableResolver.warmLinks` (from the view model's live feed) walks
  every `ytId`/`externalUrl` row in the background, capped in parallel but not in
  count, and pushes the servers it finds back through the same feed so they join
  the list and reach an already-open player. A row whose resolution found nothing
  is released to be retried on the next emission.
- **A Stremio `url` that is a `magnet:`/`torrent:` URL is a torrent** (the
  protocol allows it, and addons use it), parsed into `infoHash`/`fileIdx`/
  `trackers` and played by the torrent engine. It used to be handed to ExoPlayer as
  a direct link.
- **The verdict may not deny rows that are on screen.** When every found row is an
  unresolvable link, the note says exactly that ("Found N links … none could be
  turned into a video"), and the player fails fast on that wording too
  (`PlayerActivity.isNoResultVerdict`) instead of reporting a cut-short search.
- **The cross summary is scoped to the title it describes** (`CrossTally.title`):
  a pass with nothing to search does not publish, so the newest tally can belong
  to a different title — which is how "asked 3 · 2 don't carry it" came to be
  printed under a search that had asked nobody.

### 12. A series on a Stremio addon is asked as a VIDEO, in the namespaces the addon DECLARED

The report: *"movies play, but every series says no playable source — the same
addon in Stremio shows dozens of servers"*. Three separate protocol mistakes in
`StremioAddon.getStreams`, all of which only bite a series:

- **The `type` segment of `/stream/{type}/{id}` must be one the addon serves for
  THIS id.** `streamTypeOrder` used to lead with the item's own `rawType` — which
  for a TMDB-browsed title is TMDB's `tv`, a spelling an addon like PenguPlay
  (which declares `movie` and `series`, and whose `tv` resource is IPTV only)
  answers with nothing. The declared types for the id in hand now come first,
  ordered by the item's kind, and the canonical spellings follow as a fallback.
  `declaredStreamTypes` matches the resource's `idPrefixes` too, so the IPTV-only
  `tv` resource is not counted as a `tv` the addon serves for a `tt…` id.
- **The walk may not stop on a row that is only a LINK.** A row with an
  `externalUrl` or a `ytId` parses as a stream and used to end the search at the
  first spelling that produced one — so the spelling carrying the addon's real
  servers was never asked, and the verdict was "Found 1 link … none could be
  turned into a video". The walk now continues past a link-only answer (the rows
  are kept and returned if nothing better is found), and only a row that can
  actually play ends it.
- **The id must be translated into a namespace the addon knows.** A `tmdb:…` id
  (what an addon's own catalogue rows carry) is offered as `tmdb:` when the addon
  declares that prefix, and the numeric part is resolved to an IMDb id (via TMDB)
  when it declares `tt` — a `tmdb:`-prefixed id used to be read with
  `takeWhile { isDigit }` on the RAW id, which saw no digits at all, so an addon
  declaring only `tt` (Torrentio, Cinemeta) was asked about a namespace it does
  not know. Both spellings are handed over when the addon declares both, in a
  round-robin across the type segments rather than one spelling to exhaustion.
- **A series with no episode list is still playable**: the episode id is
  `"{show}:1:1"` for the addon's stream call, and the `/meta` fallback for a
  `tmdb:` id asks TMDB (`TmdbBrowse.episodes`) for the list the addon does not
  publish. Addons that are catalogue-less (PenguPlay) never had an episode list
  from their own manifest.

### 13. Playback starts on the first server a probe has PROVED — the rest keep loading

The report: *"it found 79 servers and the video still did not start"*. A source a
probe has verified is enough on its own to start (see `PlayerSource.probeVerified`
and `healthyStartIndex`), so playback commits on the first server that is known to
work while the remaining servers continue to arrive in the background and are
still offered in the player's server sheet.

**Since 0.10.39 there is no hold at all to end** — see invariant 2: this was
originally written as "a verified server ends the ORIGIN's head start early", and
the head start itself has now been removed, so a verified server is simply the one
`healthyStartIndex` prefers. The four-second `START_FAILSAFE_MS` in the player
guarantees the start even if every preference fails.

### 14. An empty catalogue page is not an answer

The report: *"a row on Home is full of posters, tapping Show all says nothing
here right now"*. Catalogue hosts answer HTTP 200 with an empty list while their
upstream is rate-limited (StremioLabAR's Trakt/MDBList lists do exactly this,
and say so in an `error` field), and the page read happened a second after the
Home row's read of the SAME catalogue succeeded.

- **One funnel for every engine** (`ContentRepository.loadCatalogPage`): the
  page that opens on a tap re-asks once after a short pause when the answer is
  empty (`retryEmpty`), and page 1 falls back to the last NON-EMPTY answer this
  catalogue gave — from a process-wide cache, because the two reads come from
  different repository instances (Home's row and the catalogue screen).
- **The addon's own words are shown when it has any** (`StremioAddon.catalogErrors`):
  an `error` field, `totalItems == 0`, a non-JSON body or a timeout each get
  their own sentence instead of the generic "returned no items".
- **A catalogue that can ONLY be answered with a query is not a Home row**
  (`StremioAddon.homeCatalogs`): a catalogue whose `extra` marks `search` as
  required answers an empty list to a plain page request by construction, so it
  is held back from the feed (it stays in `catalogs()`, where `search()` asks it
  with the query it needs) and `search()` skips catalogues that declare extras
  and no `search` (see `supportsSearch`).

### 15. A Nuvio origin searches the whole Nuvio family — unless the user says not to

The request: *"even with the search-all-extensions toggle off, for a nuvio
provider it should still search all my installed nuvio providers — and add a
toggle there to turn that off too"*.

- **Why it is its own rule rather than a consequence of the scope switch.** The
  nuvio engines all resolve the same `(tmdbId, mediaType, season, episode)` tuple
  from the item itself, so the family is ONE source of servers, not a
  cross-search of unrelated sites. A reader browsing a nuvio catalogue wants
  their nuvio servers gathered for the title, which is what the real nuvio app
  does — and that is true whether or not the user wants CloudStream/Aniyomi repos
  in the mix.
- **`SearchScope.engineFamilies`** is the single value behind it since 0.10.39
  (mirrored from `AppStore.engineFamiliesFlow` by HikariApp, read into a local at
  the top of `streamsForInner` like `allExtensions`): a SET of engine names whose
  family is asked. `SearchScope.family(type)` answers for one engine, and
  `nuvioFamily` / `stremioFamily` are computed from it — see invariant 17.
  It adds every installed nuvio provider to the pass's targets when the title was
  opened FROM one, with the origin first and the rest in `NUVIO_PRIORITY` order
  (`nuvioOrder`). Default ON; the switch that turns it off is the "Search every
  Nuvio provider" row on the Server search card.
- **Off means off.** With it off, a nuvio origin in "Only this extension" mode
  asks only the exception nuvio ids, or nobody — exactly the previous behaviour.
- **It outranks the exception-collapse rule (invariant 6) for nuvio origins,**
  and that is deliberate: the user asked for the family in as many words, and
  this switch is the thing that turns it off. A NON-nuvio origin with nuvio
  exception engines marked behaves exactly as before.
- **The Stremio family worked differently until 0.10.38**, and now has the same
  switch — see invariant 16.

### 16. A Stremio origin searches the whole Stremio family — unless the user says not to

The request: *"make the same as our nuvio provider toggle for stremio — when the
search-all-extensions toggle is off, playing anything from one stremio extension
should search servers from all installed ONLY stremio extensions, not any other
extension — and add the same toggle button in settings"*.

- **Why it is the same rule as invariant 15.** Every Stremio addon is handed the
  item's own imdb/tmdb id, so one addon's answer is not evidence about another's
  — but they all resolve the SAME video, which is exactly what makes the family
  one source of servers rather than a cross-search. The real Stremio client asks
  every installed addon for a catalogue id, and Hikari already did that on Home
  and with the scope switch ON (`primaryTargets`).
- **`SearchScope.engineFamilies`** is the one value behind it since 0.10.39
  (mirrored from `AppStore.engineFamiliesFlow` by HikariApp, read into a local at
  the top of `streamsForInner`); `stremioFamily` is computed from it. It adds
  every installed Stremio addon to the pass's targets when the title was opened
  FROM one, with the origin first (`stremioOrder`). Default ON; the row that turns
  it off is "Search every Stremio addon" on the Server search card, right under
  the Nuvio one. See invariant 17.
- **Only Stremio addons are added.** A Stremio origin never drags a CloudStream,
  Aniyomi, SkyStream or Hikari repo into the pass — the addons are gathered, and
  nothing else. (A non-Stremio origin is unaffected: it still asks only itself,
  plus the nuvio family when it IS a nuvio provider, plus the exception repos.)
- **`stremioPrimaryIds` covers the family too**, so an addon that is already a
  primary target is not asked a second time by TITLE in the cross pass — the two
  asks would put the same servers on the list twice and cost the addon a
  pointless search.
- **Off means off**, exactly like the nuvio switch: a Stremio origin in "Only this
  extension" mode asks the addon it came from (and any addon marked as an
  exception), and nobody else.

### 17. Every engine has its own "search the whole family" switch

The request (0.10.39): *"do the same toggle thing you did for nuvio and stremio
for all of them — hikari, cloudstream, skystream and all the others. By default
make search-all-extensions off, and keep only the nuvio and stremio toggles on;
hikari, cloudstream etc stay off. Turning the cloudstream toggle on will search
servers from all installed CloudStream extensions, same for hikari, same for the
others."*

- **One value, one switch per engine.** `SearchScope.engineFamilies` is the set
  of `ProviderType` names whose whole installed family is searched for a title
  opened FROM one of its repos. `AppStore.engineFamiliesFlow()` builds it from
  three stored things — the two dedicated switches that already existed for Nuvio
  (`NUVIO_SEARCH_ALL`) and Stremio (`STREMIO_SEARCH_ALL`) and a general string set
  (`searchFamilyTypes`) for every other engine — so an existing choice of theirs
  survives unchanged, while the UI can be one uniform row per engine
  (`AppStore.setEngineFamily(type, on)`).
- **Defaults: STREMIO and NUVIO on, every other engine OFF.** Nuvio and Stremio
  are the two engines that resolve a title from an id the item already carries,
  which is what makes their family one source of servers (invariants 15 and 16).
  For a site-scraper engine the family is a genuine widening: it means asking
  sibling repos by TITLE, which is a cross-search, and the user asked for it to be
  off until they turn it on.
- **Server search: "search all installed extensions" now defaults to OFF.** That
  is the CloudStream model — a title plays from the repo it was opened from — and
  it is what makes the per-engine switches the only thing that widens a lookup.
  An install that had never touched the switch used to get every extension asked
  for every title; `AppStore.searchAllExtensionsFlow()` returns false when the
  key is unset. (A stored `true`/`false` is still honoured, so nothing changes for
  anyone who has made the choice explicitly.)
- **Where it applies, exactly.** The gate is `sameEngine` in `streamsForInner`:
  with the origin's engine NOT in the set, no sibling repo of that engine is a
  same-engine target, so in "Only this extension" mode the origin is the only repo
  asked. With the scope switch ON the family set changes nothing — every repo is
  asked by design — and with exception repos in force a repo the user marked by
  hand is still asked (an explicit instruction outranks a family default).
- **The family applies to one engine only.** A CloudStream title with the
  CloudStream switch on never drags in a Hikari or Aniyomi repo: only repos of
  the origin's own `ProviderType` are added.
- **A log line records both directions.** With the switch off and siblings
  installed, the pass logs `family(<Engine>) switch is off — N sibling repo(s) of
  the origin's engine are not asked for this title`, so "my other CloudStream repo
  never showed servers" is answerable from the log.
