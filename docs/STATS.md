# Stats — what the user watched and read, and for how long

The Stats page (`ui/screens/StatsScreen.kt`) is one screen with two doors:

- **Settings → Stats** — a row on the settings index that opens it as a full
  page (`showStats` in `SettingsScreen.kt`, like the Logs page).
- **The Stats tab** — `Routes.STATS` in `AppNav.kt`, an entry in `BottomTabs`
  that is **OFF by default** and switched on in **Settings → Taskbar buttons**
  (the `statsTabFlow` preference, exactly the IPTV/Manga pattern). A TV rail
  draws every tab, so on a television it is always there.

The same composable draws both, so they can never disagree; `onBack` is the only
difference (a page header with a back button when Settings opened it).

## Where the numbers come from

Nothing on the page is derived from the watch history. History holds ONE row per
video — a re-watch overwrites the resume position — so "time spent" cannot be
recovered from it at all, and a manga chapter is not in it either.

Instead, `data/WatchStats.kt` keeps one small JSON document under the single
preference key `watchStats`:

```json
{
  "v": 2,
  "days": {
    "2026-09-22": {
      "s": 720, "v": 1, "c": 0,
      "tt": { "<key>": { "t": "Chainsaw Man", "p": "<poster>", "s": 720, "k": "manga", "v": 0, "c": 1 } }
    }
  },
  "titles": { "<key>": { "t": "Chainsaw Man", "p": "<poster>", "s": 600, "k": "manga", "v": 0, "c": 1 } }
}
```

- `days` is bucketed by the **device's local calendar day** ("days active", the
  streak and the heatmap are about the user's evenings, not about UTC midnight).
- `s` = seconds, `v` = videos started, `c` = chapters opened.
- `tt` is that DAY's own per-title breakdown — the same row shape as the
  all-time `titles` map, written by the same `bump()` merge. It exists so the
  heatmap can answer "what was that 2m?" for the day that was tapped: without it
  a day's bucket held a time and nothing else, so every square read out the same
  figures. Decoded into `WatchStats.Day.titles`, read back through
  `Snapshot.titlesOn(day)`. A document from a build that predates the field
  simply has no `tt` and reads as a plain time, never as wrong numbers.
- `titles` totals the same seconds per title, which is what the favourite-title
  card reads; `k` is the kind (`movie` / `series` / `manga`).
- **A row is recorded for every event, not only the ones with time on them.**
  `withTotals` used to write a title row only when `seconds > 0`, so
  `addVideo`/`addChapter` (which carry no seconds) left no row at all — which is
  how "Items consumed: 9" could sit next to a list that accounted for none of
  them. Opening a chapter and closing it again is an item consumed, and it is now
  listed as one.

**Who writes it**

- `PlayerActivity` (`startStatsTicker`): samples every 10 s, adds 10 s to
  `watchSecondsPending` while `player.isPlaying`, hands the total to the store
  every 60 s **and from `onStop()`**, and records one "item consumed" per session
  on the first tick that plays. Wall-clock seconds, deliberately: seeking and
  re-watching are still time spent, and the position would double-count a scrub
  and under-count a re-watch.
  **Both writes go through `app.appScope`, never `lifecycleScope`** — see the
  trap below.
- `MangaReaderScreen`: one chapter per chapter opened (deduped per session — a
  webtoon strip walks the reader across chapters and back), and the wall-clock
  time the reader is on screen, counted only while the activity is RESUMED. The
  clock is ONE clock for the whole reader, keyed on the manga (`LaunchedEffect(key)`)
  and **not** on the chapter: keying it on the chapter restarted it on every
  chapter change, so a reader who moved faster than one tick — or whose webtoon
  strip walked across chapters — was credited no time at all.

**The trap that kept "time spent" at 0m.** Both of these are written from a
place that is about to die (the player's `onStop`/`onDestroy`, the reader's
screen leaving), and a write launched in an `Activity`/composable scope is
cancelled the moment that scope dies:

- the player's final flush was `lifecycleScope.launch { … }`, so leaving the
  player cancelled the write of everything since the last 60 s flush — i.e. the
  tail of every watch, and ALL of a watch shorter than a minute;
- "items consumed" kept being written (a chapter opened, a video started), which
  is why the page could honestly show 7 items and 0m spent at the same time.

If you add another counter, launch the write in `app.appScope` (the process-wide
scope) and keep counting on a single clock that outlives the chapter/screen
change.

**How it is written** — `AppStore.recordWatchSeconds/recordVideoStarted/`
`recordChapterRead` do the read-modify-write **inside DataStore's atomic `edit`**,
so the player's minute-flush and the reader's tick can never lose each other's
write. Do not "optimise" this into a read-then-set in Kotlin.

Everything lives in the one preferences store, so `BackupManager`'s generic dump
carries it with no change (see docs/RELEASING.md's backup note).

## The page

- Three tiles: **Time spent**, **Items consumed** (videos + chapters), **Days
  active**. **Each one is a door**: tapping it opens the list behind the number
  in place, under the tiles ([`PanelCard`]):
  - **Time spent** / **Items consumed** → every title in scope, longest first
    (Items sorts by episodes + chapters), each row showing its poster, its kind,
    what was taken from it and its time. Only the titles that actually
    contributed are listed, so the rows always add up to the figure.
  - **Days active** → every day with something on it, newest first, each row
    showing that day's own summary; tapping a row picks that day AND turns the
    sheet into that day's own item list — the question the day list implies.
  - Tapping the open tile again closes the sheet; the sheet's own header has a
    close button and, when a day is scoping it, an **All time** action.
- Which day scopes the first two figures — and the row lists inside those sheets
  — is whichever day the heatmap has PICKED (a square, or a row in the "Days
  active" sheet). The page starts UNscoped: today is only the *default*
  selection, and while it is the default, "today so far" and "all time" are the
  same question for a log this young. `dayChosen` is what tells those two apart,
  so once a day is picked on purpose it scopes the figures — today included,
  because "what did I watch today" is a real question. The sheet's own **All
  time** action clears the pick (`pickedDay` returns to today, so the heatmap
  caption is never blank). That is the whole "tap the 19th, then tap Time spent"
  flow.
- **Rank card**: the tier name, "Otaku Rank Level", an XP pill, and level
  progress. `WatchStats.rankFor` walks the ladder in `TIERS` (1h → 1000h) and
  `xp = totalSeconds / 360` — 1 XP per six minutes, so 12 minutes is exactly the
  reference app's "2 XP" with "0.2h / 1h" progress.
- Three tiles: **Avg episodes**, **Avg chapters** (per active day), **Streak
  (cur/long)**. A day with nothing logged yet does not break the current streak
  (at 09:00 nobody has watched anything today).
- **Favourite title**: the title with the most seconds, with its poster and kind.
- **Activity heatmap**: twelve Sunday-first weeks, darker = more time, tap a
  square to pick that day. Future days in the current week are blank rather
  than "no activity". Under the grid, the picked day's own block: its date, its
  own time/episodes/chapters (`daySummary`), its top three titles
  (`StatsTitleRow`) and a "See all N items" shortcut into the Items sheet — so a
  square answers for itself instead of repeating the page totals.
- **Reset** (in the header, only when there is something to reset) clears the
  document (`AppStore.clearWatchStats`).

No `java.time` anywhere: the app has no core-library desugaring (minSdk 24), so
the calendar work is `java.util.Calendar` + `java.text` formatting, exactly like
`DetailScreen.formatReleaseDate`.
