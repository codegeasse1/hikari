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

## The panel's width comes out of the LAYOUT — `PanelWidthHost`

The width is not computed anywhere any more. The panel is put inside
`PanelWidthHost`, a one-child `FrameLayout` that is `MATCH_PARENT` in the dialog's
root, so the width it is measured with IS the width the window really gave the
dialog — in whatever orientation, whatever the insets are, on every device. It
then measures the panel with that room, EXACTLY:

```
room  = the width the layout gave this host   // the one number that cannot be wrong
panel = min(room * 0.95|0.97,                 // never a wall: the pane keeps floating
            max(620dp, room * 0.72))          // ...and never that box again
panel.view.width = panel                      // the halo is painted INSIDE the panel
```

Both numbers are about the LOOK, not about the room, and the `0.72` term is the
one that decides on a phone. Every earlier version derived the width from a
NUMBER — `windowSize()`, the configuration, a fraction of the screen height — and
each of those can be right about the wrong axis: in the landscape player they
answer 1080×2460 inside a 2460×1080 window, which is how the panel came out ~915px
(330dp) wide with the rest of the video empty beside it and the right-hand column
of every row beyond its edge. A dp cap alone does not fix that either: 620dp is a
width whose pixel value depends entirely on the density the device reports, and
where that comes out small the panel is a narrow box on a wide screen. So the
panel takes the room it was given, up to the ceiling above.

Nothing re-measures it afterwards and nothing has to: a measure pass happens on
every layout change by itself — both orientations, a split-screen resize, a fold,
a television box — and the panel is sized by that pass. `windowSize()` is now used
only for the HEIGHT caps and for the opening height estimate.

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

It is a **best-effort opening estimate** — for the panel's opening HEIGHT only,
never its width (see `PanelWidthHost`), and it has three
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
window) and the WIDTH from the layout (`PanelWidthHost`) — neither of which can
answer in the wrong orientation.
