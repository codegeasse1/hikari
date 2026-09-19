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
2. **Same-engine family** — the other repos of the origin's own engine, queued
   first in the cross pass.
3. **Cross pass** — every other installed extension asked *by title*:
   search → pick the best matching entry (`confidentTitleMatch`) → resolve its
   meta + episode list → extract servers.
4. **Background sweep** — the repos the pass did not finish with are re-asked
   while the video plays, on the application scope.

## Invariants

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
  retry skip exactly the repos that needed re-asking.

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

### 3. Every state on screen must be able to resolve

- The sweep is bounded: `SWEEP_BUDGET_MS` per round, `SWEEP_MAX_ROUNDS` rounds
  (two of each). It used to run 10 min × 6 rounds, so the Sources panel could
  honestly say "still searching" for ~an hour.
- `anySweepBusy` / `sweepBusyFor` are **staleness-aware** (`SWEEP_STALE_MS`): a
  sweep that has reported nothing for 90 s stops counting as an active search.
- The player's Sources line counts `running` only while the tally has changed
  within `SEARCH_QUIET_MS` (PlayerActivity), so a frozen count cannot be shown
  as a running search.
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
