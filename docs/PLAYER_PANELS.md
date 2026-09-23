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
2. `MaxHeightScrollView` caps the **scroll view**, not the panel — and the cap is
   a CEILING OVER THE INCOMING MEASURE SPEC, never a replacement for it (an
   `AT_MOST(cap)` spec is clamped by the size the parent offered). Replacing the
   spec is how a box could come out cut off AND unscrollable: with a cap larger
   than the room the panel around it had, the scroll view was measured taller than
   the panel could be, a `LinearLayout` lays children out at their measured size
   even when its own height is clamped, so the bottom of the list was drawn past
   the panel's edge (hidden by the silhouette clip) inside a view that was itself
   too tall for its content to scroll into view. Honouring the spec makes that
   state impossible. `maxHeightPx == 0` still means "no cap".
3. `presentGlass` opens with a cap derived from the **SCREEN's own height in the
   current orientation** (`screenHeightPx()`) — a number that can be too large,
   never too small, and `applyHeightCap` settles it before the first draw.
4. `applyHeightCap` computes the cap from the room the dialog **really has** —
   `visibleRoomPx(outer)`, which is:

   * the dialog frame's own **measured** height (`outer` is `MATCH_PARENT` in both
     axes, so what it measures IS the window the manager gave the dialog), and
   * never more than **`screenHeightPx()` — the CONFIGURATION's screen height,
     which follows the rotation.**

   Nothing else is consulted, and that is the point. Two earlier versions derived
   the cap from `getWindowVisibleDisplayFrame()` and `windowSize()` as well, and
   both are figures the platform reports for a WINDOW: on this class of device a
   floating dialog's window is built from the display's NATURAL (portrait)
   metrics — the quirk `windowSize()` documents, where `currentWindowMetrics`
   answers 1080×2460 inside a 2460×1080 window. They could therefore say 2460
   about a screen that is 1080 tall (making the cap more than twice the room, so
   the panel grew past the bottom of the video and the rows below the fold could
   not be dragged into view), and a window that is still being placed can make the
   visible frame answer a band one row tall (the opposite failure: a box with a
   scrollbar beside it and nothing to scroll to). Both are gone. The cap is
   **always applied**; there is no "the rows fit, so lift the limit" state (a
   `WRAP_CONTENT` scroll view with a ceiling shrinks onto short rows by itself, so
   lifting it bought nothing and cost exactly this bug class). The ceiling is a
   ceiling, never a size.
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

**The cap is corrected against what the box actually came out as — and the cap is
re-derived from scratch whenever the room changes.** The panel's vertical padding is
derived from its own size (`CurvedGlassPanel`), so a FIXED prediction of it drifts as
the panel grows — and every pixel it drifts by is a pixel of the panel below the
bottom of the video, where no drag can reach it (the "the subtitle box is
unscrollable" report). `applyHeightCap` therefore:

* reads that padding straight off the panel (`panel.paddingTop + paddingBottom` —
  the padding actually applied; the old `panel.height - scroll.height` is only the
  fallback, because while the panel is clamped that difference is a leftover sliver
  rather than the padding, and believing it kept the cap too big),
* compares the height the box ACTUALLY came out as — the hint line, the list the
  scroll view was really given (`scroll.height`) and that padding — with the room it
  was given, and takes the excess off the list, which lays out again and calls this
  again, and
* **resets that correction whenever `avail` changes** (`lastAvail`), so the cap is
  always `ceiling − correction-at-THIS-room`. The correction used to be monotonic
  across the dialog's whole life — a single early pass with a wrong (tiny) room
  fixed the list at that height forever, which is the one-row box with a scrollbar
  beside it and no way to see the other rows. Re-derivation makes that state
  unreachable: a cap that is a little too LARGE is corrected a pass later, while
  one that is too SMALL is the only one that strands the rows.

It deliberately does not read `root.height` for this: a `LinearLayout`'s own
measured height is CLAMPED to the spec its parent offers, so a root hanging off the
bottom of the display reports exactly the room it was given and the correction
never fired — which is why it could not close this report on its own. The list's
height is the figure that stays truthful in that state.

The correction stops at its floor (24dp), so a room genuinely too small for the
hint line plus one row cannot make it accumulate without changing the layout, and
the wrapper `applyHeightCap()` around `applyHeightCapBody()` refuses to re-enter: a
layout pass this pass itself scheduled must not run it a second time inside the same
frame. It re-runs on every layout change of the window outer frame, the panel, the
scroll view and the root, plus three pre-draw passes.

`presentGlass` has no height parameter on purpose. If a new caller "knows" how
tall its content is, that knowledge belongs in the measurement, not in an
argument.

## Both axes scroll, and only where there is something to scroll

The panel has **one** scroller: the vertical `MaxHeightScrollView`. Its content is
added `MATCH_PARENT`, so it is measured against a BOUNDED width and a row built as
`[marker][text column, weight 1][pill][chevron]` has its weighted column shrink,
its label ellipsize and its trailing pills inside the glass — with nothing pinned
or measured by hand.

A row that genuinely needs to move sideways owns its own `SidewaysScrollView`.

* **The old outer "reach" is gone.** A `HorizontalScrollView` around the whole
  content claims EVERY sideways drag anywhere in the panel the moment the finger
  moves — that is what it does on touch slop; it does not first ask whether it has
  anything to scroll to. Its own range was zero, so the gesture went nowhere: the
  engine chip strip (All / CloudStream / Hikari / Nuvio / Stremio / SkyStream) and
  every other sideways row never saw a single drag. That is the "I cannot scroll
  the chips to reach Nuvio" report, and it is why the reach was removed rather
  than patched. `PanelReach` and `fitContentToPanel` went with it.
* **`SidewaysScrollView` is what a nested strip is built from** (`sidewaysStrip`).
  It is a plain `HorizontalScrollView` except for `dispatchTouchEvent`: once a
  motion is clearly horizontal (`|dx| > slop` and `|dx| > |dy|`) it calls
  `parent.requestDisallowInterceptTouchEvent(true)`, so the vertical scroller above
  can no longer take the gesture away — and it gives it back (`false`) the moment
  the motion is not. That one line is the whole difference between "the chips never
  scroll" and "the chips always scroll".
* `isFillViewport = true` does the other half in the strip: a child narrower than
  the viewport is stretched to it, so a short strip looks exactly as it did before
  and there is nothing to scroll. Its layout direction is pinned **LTR** — in a
  right-to-left language the platform mirrors a horizontal scroller's origin, so it
  would open scrolled to its far end showing its first pill sliced in half.
* A sideways drag of a strip moves that strip's edges in the panel's own
  coordinates, and `CurvedGlassPanel` bends rows by those coordinates;
  `setRowOffsetX` feeds it the strip's scroll so the stack does not visibly shrink
  while it is dragged.
* The content's parent is now the scroll view, so `v.parent` is a truthful answer
  for the panel's list; `verticalScrollerOf(view)` still finds it by walking up.

## The panel's width — the restored geometry

The width is the size of the box the dialog floats as, so it is a number this file
COMPUTES from the SCREEN's own width and height in the current orientation
(`screenWidthPx` / `screenHeightPx` — the configuration, which follows the
rotation), and the smallest of three ceilings wins:

```
room   = scrW - 2*halo - 8dp                   // the screen, less the glow's margins
panelW = min(scrW * 0.95|0.97,                 // never a wall: the pane keeps floating
             scrH * 0.93|0.97,                 // landscape: this is the term that binds
             560dp)                            // a tablet does not become a sheet
           .coerceAtMost(room)                 // the floor never wins over the room
           .coerceAtLeast(min(140dp, room))    // ...and never over the screen
```

`scrH * 0.93` is the term that decides on a phone held sideways: a landscape screen
is more than twice as wide as it is tall, so the screen's HEIGHT is the binding
constraint and the panel comes out a ~41% box in the middle of the screen — which
is what a dialog is supposed to look like. It used to be the WINDOW's height here,
and a window can answer in the display's natural orientation (see `windowSize()`),
which is how a landscape player could be handed a portrait-shaped wall of a panel.

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

`sizeDialogWindow()` re-reads the **screen** and re-asserts that width on every
layout pass (cheap, and a no-op when the width has not changed), because a rotation
changes the configuration's figures after the dialog was already shown — and a
window that has not settled yet is exactly the figure it no longer trusts.

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
come from `visibleRoomPx` — the dialog frame's MEASURED height, bounded by the
configuration's own screen height — and the WIDTH from the screen's own width and
height (`screenWidthPx` / `screenHeightPx`, see the arithmetic above).

`windowSize()` is now only the last-resort fallback for the opening cap, used when
the configuration reports nothing usable. Two earlier versions fed it into the
height caps and the panel width, and both failed for the same reason: the frame and
the visible-display frame are also figures the platform reports for a WINDOW, and a
floating dialog's window is built from the display's NATURAL metrics (the very quirk
this function exists to work around), so "neither of which can answer in the wrong
orientation" was simply not true. The configuration is the only source that follows
the rotation, and it is what the panel's geometry uses now. If you add a fourth
source of vertical space to this file, bound it by `screenHeightPx()` too.
