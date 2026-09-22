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

## The panel's width is MEASURED, never assumed — `applyPanelWidth`

The width comes from the **frame** (`outer`, the dialog's content view) as soon as
it has been laid out: its width IS the width the window really got, in whatever
orientation the device is in, insets and all. `applyPanelWidth` re-derives the
panel from that measurement on every layout change, so the wrong answer cannot
survive:

```
room   = outer.width                       // the real width, or windowSize() before layout
panel  = min(room * 0.95|0.97, 620dp)      // never wider than the room, never a wall
         .coerceAtMost(room - 2*halo - 8dp) // the glow needs somewhere to fade
         .coerceAtLeast(min(140dp, room))   // the floor yields to the room
panel.view.width = panel + 2*halo           // the halo is painted INSIDE the panel
```

`windowSize()` is only the **opening estimate** now, and that is deliberate: it can
answer in the display's natural orientation (see there), and the width used to be
taken from it ONCE and never re-checked, so a wrong answer was wrong for the life
of the panel. That is the box that came out a third of the screen wide (915px of
2460px) with the right-hand column of every row beyond its edge, and nothing to
drag because the panel believed it was already as wide as it should be. Measured
from the frame instead, the same panel comes out at 620dp — the width the rows
were designed for — on every device and in both orientations.

## The WINDOW is left to the window manager

`dialog.window` is asked for `MATCH_PARENT` in both axes, centred. The window
manager is the only thing that knows the screen's real rect in the current
orientation minus the insets, and it is what a dialog is supposed to ask for.

Sizing the window by hand was actively harmful. An explicit height taken from
`windowSize()` — which can answer in the display's natural orientation — asks for a
window TALLER than the screen; the platform keeps the window's top on the display,
the frame is then taller than the screen, and the panel, centred inside that
frame, is drawn from the middle of the screen downwards: half of it, and its last
rows, end up below the display. That is the "the boxes in the player go out of the
player" report. `MATCH_PARENT` is the whole fix — the window can then never be
bigger than the display — and every number the panel needs comes from the frame
inside it.

## `windowSize()`

It is a **best-effort opening estimate** (see `applyPanelWidth`), and it has three
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

Nothing depends on it being right any more, which is the point: the caps come from
`visibleRoomPx` (the frame and the display area really visible to this window) and
the width from the frame.
