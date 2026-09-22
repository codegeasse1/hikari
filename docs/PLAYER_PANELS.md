# Player dialog panels — the sizing rule

Every dialog in the player (server list, quality, audio, subtitles, caption
style, download destination, video enhance, speed) is one composable shell:
`PlayerActivity.presentGlass(dialog, title, content, hint, iconRes, cancelable,
rowHosts, headerActions)`. It builds the hint line, the round ✕, the glass panel
and the scroll view, and it is skin-aware (`PlayerSkins.isFlat`) so **all four
Player UI choices — Default, Neon, Cinema, Minimal — come through it**. A new
dialog that builds its own window is a dialog that will be cut off on one of
them.

## The rule: a panel's height is MEASURED, never estimated

1. The panel view is `WRAP_CONTENT`. Nothing sizes the panel itself.
2. `MaxHeightScrollView` caps the **scroll view**, not the panel. It treats
   `maxHeightPx == 0` as "no cap".
3. `presentGlass` opens with a cap derived from the **window** (`windowSize()`,
   which follows the rotation) — that number can be too tall, never too short,
   and a frame that is too tall is corrected before the first draw.
4. `applyHeightCap` then recomputes the cap from the room the dialog frame
   **measured** (`outer.height`) and from what the content **measures**, and:
   * when the rows fit, it **lifts the cap entirely** (`maxHeightPx = 0`) so the
     panel is sized purely by its content — this is the part that guarantees
     "however many options it holds, none are cut off";
   * when they do not fit, it caps at the room and draws a scrollbar.
5. The fit test is exact: `content` is measured against the cap, so a full
   measurement means "fits" and a measurement equal to the cap means "does not".
   Do **not** reintroduce a per-row height estimate — that is what used to slice
   the second option off the download sheet (rows are taller than `34dp`, and
   the callback that was supposed to correct it need not fire for a short list).
6. The measurement also runs in the window's **pre-draw** pass (three passes,
   then it removes itself) so the first frame the user sees is already right.
7. A row's secondary line may use **two** lines. A one-line cap ellipsised the
   explanation of the option being chosen ("Nothing applied — the picture
   exactly as the server sent it"), which is the text that makes the choice
   possible.

`presentGlass` has no height parameter on purpose. If a new caller "knows" how
tall its content is, that knowledge belongs in the measurement, not in an
argument.
