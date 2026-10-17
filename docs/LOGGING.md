# Logging rule (standing — applies to every feature, present and future)

A bug report must be answerable from **Settings → Logs**, never from a
screenshot. Every user-visible flow logs through `com.hikari.app.data.Logs`:

1. **Start** — what started, with its key ids (`tap`, `start`, chapter tap,
   search start, sync start).
2. **End with wall-clock duration** — `end in Nms`, plus the outcome
   (counts, sizes, decisions). Use `Logs.timed(tag, label) { … }` so new
   code gets start/end/duration from day one.
3. **Every inner stage** — each network/JS call logs its own duration,
   result size, and timeout/cancel flag. A stage that can time out MUST log
   whether it timed out (`timeout=true/false`).
4. **Every failure** — short reason via the file's `fail()` helper (which
   logs) or `Logs.logError`. Never fail silently; never spin forever —
   bounded calls land on an error state with retry, and the retry tap logs
   its attempt number.

## Tag registry (one tag per area — grep the tag to get the whole story)

- `Tracker` — tracker sync searches, queries, candidates, decisions, pushes
- `Search` — server search passes, cross tallies, sweeps, rescues, fallbacks
- `Provider` — provider answers, stalls, wedges, breakers
- `Player` — playback opens, switches, errors, reconnects
- `Manga` — manga/novel plain-source calls (page lists, page fetches)
- `LnReader` — LNReader plugin calls (catalogues, chapters, chapter text)
- `Novel` — novel reader screen (taps, loads, retries, navigation)
- `Backup` — backup/restore/pairing
- `App` / `Home` / `Memory` / `Work` — lifecycle, feed, resources, tasks

## Checklist for a new feature

- [ ] First line of the flow logs `start` with its ids.
- [ ] Last line logs `end in Nms` with counts/outcome.
- [ ] Each bounded call logs duration + `timeout=` flag.
- [ ] Each failure logs a one-line reason; UI shows error + retry.
- [ ] Retry/repeat actions log their attempt number.
- [ ] No `catch {}` that swallows without a log line.
