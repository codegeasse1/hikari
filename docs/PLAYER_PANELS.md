# Player dialog panels — the sizing rule

Every dialog in the player (server list, quality, audio, subtitles, caption
style, download destination, video enhance, speed) is one composable shell:
`PlayerActivity.presentGlass(dialog, title, content, hint, iconRes, cancelable,
rowHosts, headerActions)`. It builds the hint line, the round ✕, the glass panel
and the scroll views, and it is skin-aware (`PlayerSkins.isFlat`) so **all four
Player UI choices — Default, Neon, Cinema, Minimal — come through it**. A new
dialog that builds its own window is a dialog that will be cut off on one of
them.

## The rule: a panel's height is MEASURED, never estimated

1. The panel view is `WRAP_CONTENT`. Nothing sizes the panel itself.
2. `MaxHeightScrollView` caps the **scroll view**, not the panel. It treats
   `maxHeightPx == 0` as "no cap" — but nothing sets 0 any more (see 4).
3. `presentGlass` opens with a cap derived from the **window** (`windowSize()`)
   — that number can be too large, never too small, and `applyHeightCap` settles
   it before the first draw.
4. `applyHeightCap` computes the cap from the room the dialog **really has** —
   `visibleRoomPx(outer)`, the smallest of the frame's measured height, the
   display area actually visible to this window
   (`getWindowVisibleDisplayFrame`) and the rotation-corrected window size — and
   **the cap is always applied**. There is no "the rows fit, so lift the limit"
   state: a `WRAP_CONTENT` scroll view with a ceiling shrinks onto short rows by
   itself, so lifting it bought nothing and cost a whole class of bug — a list
   that was wrongly judged to fit grew the panel past the bottom of the video
   and then had **nothing to scroll**, which is the "the box content is
   unscrollable" report. The ceiling is a ceiling, never a size.
5. A scrollbar is shown only when the scroll view **can actually scroll**
   (`canScrollVertically`, read after the pass that applied the cap). A bar
   beside a list that cannot move is a lie; a missing bar beside one that can is
   the "how was I supposed to know" half of the same report.
6. The measurement also runs in the window's **pre-draw** pass (three passes,
   then it removes itself) so the first frame the user sees is already right, and
   again on every layout change of the frame, the panel and the scroll views.
7. A row's secondary line may use **two** lines. A one-line cap ellipsised the
   explanation of the option being chosen ("Nothing applied — the picture
   exactly as the server sent it"), which is the text that makes the choice
   possible.

`presentGlass` has no height parameter on purpose. If a new caller "knows" how
tall its content is, that knowledge belongs in the measurement, not in an
argument.

## Both axes scroll

The content is wrapped in a `HorizontalScrollView` (the *reach*) inside the
`MaxHeightScrollView`, so a row that is genuinely wider than the panel can be
dragged into view instead of being clipped with no way to reach it — the "the box
is cut and it will not scroll sideways" report.

* `isFillViewport = true` is what makes it free: when the rows fit (the normal
  case) the content is stretched to the viewport, so every row spans the panel
  exactly as before and there is nothing to scroll. The horizontal scrollbar
  appears only when the strip can really scroll (`canScrollHorizontally`).
* Its layout direction is pinned **LTR**. In a right-to-left language the
  platform mirrors a horizontal scroller's origin, so it opens scrolled to its
  far end and shows its first pill sliced in half — the bug that made an earlier
  version drop the horizontal scroller altogether.
* The content's parent is the reach, not the scroll view. Anything that needs the
  panel's list view (the server chooser keeps its scroll position across a
  rebuild) must find it with `verticalScrollerOf(view)`, never with `v.parent`.
* A sideways drag moves every row's edges in the panel's own coordinates, and
  `CurvedGlassPanel` bends rows by those coordinates; `setRowOffsetX` feeds it
  the strip's scroll so the stack does not visibly shrink while it is dragged.
* A **nested** horizontal scroller (the source chooser's engine chip strip) is
  sized to the panel's inner width by `boundNestedHScrollers`, from
  `applyHeightCap` (the width only exists after the first layout). Under the
  reach's unbounded width measure such a scroller answers with the width of
  everything it holds — the sum of all its pills — and every `MATCH_PARENT` row
  beside it is then stretched to that width, so the cards grow past the glass
  and their right-hand controls sit outside the silhouette until the whole panel
  is dragged. Bounded, the strip scrolls **inside** the panel, which is what it
  is for, and the rows stay the panel's width.

### …and the content ITSELF is pinned to the panel's inner width

`applyHeightCap` hands the content to `fitContentToPanel(content, innerW)` on every
pass, and that is the fix for the report that the pills under the server/subtitle
lines "are not fit" and that there is no way to scroll to them:

* A row is built as `[marker][label column, weight 1][pill][chevron]` (`glassRow`,
  `serverOption`). A weighted child only SHRINKS when it is measured against a
  BOUNDED width; under an unbounded measure it is handed its full intrinsic width
  instead — whatever the label needs.
* The reach measures its child with an UNSPECIFIED width (that is what makes a
  sideways drag possible at all), so every row was measured at its label's full
  intrinsic width. A server row ("Provider (Repo) · Plugin · 1080p") came out
  wider than the panel, the `HLS`/`DASH`/`SUB` pill and the chevron landed past the
  panel's right edge, and the only way to see them was a sideways drag inside a
  vertically scrolling list.
* `fitContentToPanel` therefore sets the content's own `layoutParams.width` to the
  panel's inner width **and** runs `boundNestedHScrollers` (which is now called
  only from there, so the two rules cannot drift apart). Labels ellipsize at the
  end — every row text already asks for that — and the trailing pills are inside
  the glass on every row with no drag. The chip strip keeps its own sideways
  scroll, because that is a strip that really can be longer than the panel.
* It is idempotent (the width is only rewritten when it differs), so calling it on
  every layout pass converges instead of looping.

## The panel's width — the restored geometry

The width is the size of the box the dialog floats as, so it is a number this file
COMPUTES from the window, and the smallest of three ceilings wins:

```
room   = win.x - 2*halo - 8dp                  // the window, less the glow's margins
panelW = min(win.x * 0.95|0.97,                // never a wall: the pane keeps floating
             win.y * 0.93|0.97,                // landscape: this is the term that binds
             560dp)                            // a tablet does not become a sheet
           .coerceAtMost(room)                 // the floor never wins over the room
           .coerceAtLeast(min(140dp, room))    // ...and never over the screen
```

`win.y * 0.93` is the term that decides on a phone held sideways: a landscape
window is three times wider than it is tall, so the window's HEIGHT is the binding
constraint and the panel comes out a ~41% box in the middle of the screen — which
is what a dialog is supposed to look like.

That is the geometry of the build the user screenshotted as correct. Three later
versions (0.10.6-0.10.11) tried to MEASURE the width out of the layout instead of
computing it — a `PanelWidthHost` that measured the panel against its own room,
620dp caps, a 0.72-of-the-room floor — and every one of them produced the same
wrong box: a panel at ~72% of a 2460px screen, sitting left of centre, with the
right-hand half of the video empty beside it. The width is not a thing the layout
can be asked for here, because the WINDOW is sized from the panel, not the other
way round. The host is deleted.

The halo is painted INSIDE the panel's own bounds, so the panel view IS the
silhouette plus a halo on each side, and `panelW + 2*halo` is the width the WINDOW
is given.

## The WINDOW is as wide as the panel and as TALL as the screen

`dialog.window` is asked for `panelW + 2*halo` wide and `MATCH_PARENT` tall,
centred. Only the WIDTH is ever explicit; the height is `MATCH_PARENT` on purpose.
An explicit height taken from `windowSize()` — which can answer in the display's
natural orientation — asks for a window TALLER than the screen, and the platform
keeps a window's top on the display: the frame is then taller than the screen and
the panel, centred inside it, is drawn from the middle downwards with its last rows
below the display. That is the "the boxes in the player go out of the player"
report, and `MATCH_PARENT` height is what makes it impossible.

`sizeDialogWindow()` re-reads the window and re-asserts that width on every layout
pass (cheap, and a no-op when the width has not changed), because a rotation, a
split-screen resize or a window that has not settled yet changes the number after
the dialog was already shown.

## `windowSize()`

It is a **best-effort opening estimate** — for the panel's opening HEIGHT only,
and only its opening HEIGHT is taken from it (the width is the arithmetic above; the caps are measured), and it has three
sources (the decor, `currentWindowMetrics`, `getRealSize`) that can each answer in
the display's NATURAL orientation. Before any axis is compared with its
configuration twin, they are **swapped when the orientation disagrees with the
configuration's** — the configuration always follows the current rotation.

That swap was the bug behind the whole "the boxes are cut off and unscrollable"
report: in the landscape player the decor was not laid out yet, so the metrics
answered 1080×2460 inside a 2460×1080 window, and the old cross-check (which only
ever SHRANK an axis) could not put them back the right way round. The height caps
were measured against the wrong axis, so they never bit and the panel grew past
the bottom of the video.

Nothing depends on it being right any more, which is the point: the HEIGHT caps
come from `visibleRoomPx` (the frame and the display area really visible to this
window) and the WIDTH from the window arithmetic above — neither of which can
answer in the wrong orientation.
