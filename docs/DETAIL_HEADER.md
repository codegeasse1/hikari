# The detail page's header, and the title drawn as art

The user asked for the detail page's header to work the way the reference client's
does (their screenshots are the spec):

* the title is drawn as a **logo** — TMDB's transparent wordmark art — ON the
  header art, not as text below it;
* as the page is scrolled the **art scrolls away and the wordmark does not move
  with it**: it stays on screen, shrinking a little, and ends up at the top of the
  page acting as the title;
* the title is never shown twice (no wordmark *and* a text title).

## Where each piece lives

* **`TmdbMeta.logo(item)`** — the wordmark URL. `/{movie|tv}/{id}/images` → `logos`,
  the best-voted English one with the language-neutral ones as the fallback, one
  request per title and cached in memory afterwards (a logo never changes). Null
  for anything TMDB has no art for (every extension-only row, most non-English
  titles), and `DetailScreen` then falls back to the plain text title — so this
  feature can only ever ADD to a page.
* **`DetailScreen.Hero`** — the header art itself, in the five shapes
  `DetailHeroStyles` offers (wide banner, art + poster, tall, poster, none). It is
  now the FIRST ITEM of the page's list instead of a fixed band above it, which is
  what makes the art scroll at all.
* **The overlay in `DetailScreen`** — the wordmark and a back button, drawn in the
  page's own `Box` (outside the list), positioned from the list's scroll:

  ```
  progress   = (art scrolled away) / (art height)        // 0 at the top, 1 when gone
  size       = userLogoPercent / 100                     // clamped to 0.5…1.6
  logo.width = screenWidth * lerp(0.62, 0.34, progress)  // shrinks as it rises
                          * size                         // the user's %, applied at BOTH ends
  logo.top   = lerp(artBottom - logoHeight - 10dp,       // on the art...
                    statusBar + 5dp,                     // ...then pinned at the top
                    progress)
  ```

  The art's height is read from the LAYOUT (`onSizeChanged`), never assumed from
  the style: one of the shapes wraps its content, and the wordmark's travel is
  measured against the real thing. The wordmark's own aspect ratio comes from the
  loaded image (`onSuccess`), so the 3:1 stand-in is used on the first frame only.
  The wordmark's height at the pinned end (`pinnedLogoDp`) is what the page's
  first content item reserves as top padding, multiplied by `progress` — drawn
  OVER the list, a big pinned wordmark would otherwise sit on the year, the
  genres and the Play button.

## Rules to keep

* **The overlay is outside the scroll.** If the wordmark lived inside the header
  art, it would scroll off with it — the whole point is that it does not.
* **The height is measured, the position is interpolated.** Positioning the
  wordmark from a hard-coded fraction of the screen instead of the measured art
  height is what breaks on a different header style or a different aspect ratio.
* **The user's logo size is a MULTIPLIER, applied at BOTH ends and clamped.**
  `AppStore.detailLogoSizeFlow()` (Settings → App Layout → Details header →
  "Title logo size", `DEFAULT_DETAIL_LOGO_SIZE` = 100%, 50…160%, one percent per
  step) multiplies the width fraction — the logo's height follows from the art's
  aspect ratio, so this is "bigger wordmark", never a stretch. **It multiplies
  the pinned end too, by the same factor**: 130% is 1.3x the usual size over the
  header image *and* 1.3x the usual size once the wordmark is pinned and acting
  as the page's title. Only the first half used to obey — the pinned fraction was
  a constant — which read as the setting doing nothing the moment you scrolled
  (`0.34f` was hard-coded into the pinned width). Do not put a curve here: it has
  to agree with the number on the slider. The result is clamped to `0.2f…1f`
  before it reaches `fillMaxWidth`, because a fraction above 1 would draw the art
  wider than the screen it is fitted into (the pinned one is clamped to
  `0.17f…0.72f`).
* **A missing logo is not a failure.** A blank logo leaves the page exactly as it
  was before this feature (text title, same spacing), and the text title's *layout*
  (the `if (heroLogo.isNullOrBlank())` guard) is the only place the two cases
  differ.
* **The text title is not drawn when the wordmark is.** The wordmark's
  `contentDescription` carries the title for a screen reader.
* **The back button never scrolls away.** The one inside the art is drawn by
  `Hero`; a second one in the page's own box takes over once the art is mostly
  gone (`progress > 0.6`), from the same corner.

## The mark strip under Play

The user asked for the reference client's check button next to Play — tap it and
the title can be marked **watched**, **watching** or **for later**. Where the
reference client has its own marks, this uses records the rest of the app already
reads, so a mark can never disagree with a play:

* **watched / watching are watch-history entries** (`AppStore.addHistory`). The
  "watched" test is the SAME one the Continue Watching shelf applies
  (`HomeScreen`: a known length, position within ten seconds of the end), and
  "watching" writes a just-started entry (`positionMs = 2000`, the runtime when
  TMDB knows it) — which is exactly what puts a title on that shelf. Marking
  unwatched is the entry going away (`removeHistory`), which also clears it from
  History.
* **for later is a Library filing** into a `Watch later` category, created on the
  first use and found by NAME afterwards (`AppStore.addLibraryCategory` +
  `addFavoriteCategories`), so the title appears in the Library, in My Stuff and in
  a backup, and can be re-filed or removed there like any other saved title.
* The target of "watched/watching" is the episode Play is pointing at (`resumeEp`
  else the first) — never a different one than the button names. A series also gets
  **Mark all episodes as watched**, the reference client's "mark season as
  watched", and any title with history gets **Remove from history**.
* "Mark as watching" **never resets a saved position**: it is a status, and
  clobbering someone's 40th minute with a fresh entry would be the opposite of
  helping. Nothing is written when the title is already being watched.

### Why the marks are a STRIP on the page, not a button in the Play row

The check button started in the Play row, mirroring the reference client. It cost
the Play button a quarter of its width — that row is `Play (weight 1f)` plus fixed
buttons, so "Resume S1 E5" wrapped and Play came out as a tall pill — and, more to
the point, it HID its own answer: the only way to learn whether a title is marked
was to tap it and read the sheet back.

So the marks are drawn instead:

* **Every mark the title carries is a chip**, in a row directly under Play
  (`MarkChip`, filled in the accent colour with its tick), so one glance answers
  "have I marked this, and how?" — which is what the user asked for.
* The strip also carries the way INTO the sheet (`Mark` when nothing is set,
  `Change` when something is), because the sheet owns the actions that are not a
  plain on/off: mark every episode, remove from history.
* An unset mark is a chip that is NOT THERE rather than a chip that says "no" — the
  strip is a readout of state, so its absence is the state.
* The Play row is therefore back to exactly what it was: Play + Download + Library,
  with Play wide enough for its own label.
