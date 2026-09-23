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
  "v": 1,
  "days":   { "2026-09-22": { "s": 720, "v": 1, "c": 0 } },
  "titles": { "<key>":       { "t": "Chainsaw Man", "p": "<poster>", "s": 600, "k": "manga" } }
}
```

- `days` is bucketed by the **device's local calendar day** ("days active", the
  streak and the heatmap are about the user's evenings, not about UTC midnight).
- `s` = seconds, `v` = videos started, `c` = chapters opened.
- `titles` totals the same seconds per title, which is what the favourite-title
  card reads; `k` is the kind (`movie` / `series` / `manga`).

**Who writes it**

- `PlayerActivity` (`startStatsTicker`): samples every 10 s, adds 10 s to
  `watchSecondsPending` while `player.isPlaying`, hands the total to the store
  every 60 s **and from `onStop()`**, and records one "item consumed" per session
  on the first tick that plays. Wall-clock seconds, deliberately: seeking and
  re-watching are still time spent, and the position would double-count a scrub
  and under-count a re-watch.
- `MangaReaderScreen`: one chapter per chapter opened (deduped per session — a
  webtoon strip walks the reader across chapters and back), and the wall-clock
  time the reader is on screen, counted only while the activity is RESUMED.

**How it is written** — `AppStore.recordWatchSeconds/recordVideoStarted/`
`recordChapterRead` do the read-modify-write **inside DataStore's atomic `edit`**,
so the player's minute-flush and the reader's tick can never lose each other's
write. Do not "optimise" this into a read-then-set in Kotlin.

Everything lives in the one preferences store, so `BackupManager`'s generic dump
carries it with no change (see docs/RELEASING.md's backup note).

## The page

- Three tiles: **Time spent**, **Items consumed** (videos + chapters), **Days
  active**.
- **Rank card**: the tier name, "Otaku Rank Level", an XP pill, and level
  progress. `WatchStats.rankFor` walks the ladder in `TIERS` (1h → 1000h) and
  `xp = totalSeconds / 360` — 1 XP per six minutes, so 12 minutes is exactly the
  reference app's "2 XP" with "0.2h / 1h" progress.
- Three tiles: **Avg episodes**, **Avg chapters** (per active day), **Streak
  (cur/long)**. A day with nothing logged yet does not break the current streak
  (at 09:00 nobody has watched anything today).
- **Favourite title**: the title with the most seconds, with its poster and kind.
- **Activity heatmap**: twelve Sunday-first weeks, darker = more time, tap a
  square for that day's total. Future days in the current week are blank rather
  than "no activity".
- **Reset** (in the header, only when there is something to reset) clears the
  document (`AppStore.clearWatchStats`).

No `java.time` anywhere: the app has no core-library desugaring (minSdk 24), so
the calendar work is `java.util.Calendar` + `java.text` formatting, exactly like
`DetailScreen.formatReleaseDate`.
