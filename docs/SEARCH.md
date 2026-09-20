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
   `SearchScope.allExtensions == false`) it is the origin ALONE: no sibling
   addons, no nuvio engines, no cross pass, no sweep, and no episode list
   borrowed from another site. The switch is read once at the top of
   `streamsForInner` into a local, so one lookup can never be half-scoped.
   **Exception extensions** (`SearchScope.exceptions`) are the one thing that
   widens that, and they carry a rule of their own — see invariant 6.
2. **Same-engine family** — the other repos of the origin's own engine, queued
   first in the cross pass.
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

### 1. Every provider call is a plain blocking call

`withTimeoutOrNull` gives up *logically* while the thread stays parked forever.
So anything that WAITS on such a call can be wedged indefinitely. Consequences:

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
- **The origin's hold is a HEAD START, not a wait.** With "play as soon as the
  first server is found" (the default), `originReady` in PlayerActivity stops
  holding the moment `ORIGIN_HEAD_START_MS` (3 s) has passed since the FIRST
  server arrived — `originHeadStartMs` in the launch intent, and
  `firstServersAt` in the live collector. Only "wait for more servers first"
  uses the full `ORIGIN_PLAY_GRACE_MS` (45 s) window. The report: *"it found
  70-80 servers but it still searching on loading screen instead of playing"* —
  a full server list was held behind one repo's answer. The origin's own
  servers still arrive and are still placed at the top of the list.

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
  its own catalogue to itself.
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
