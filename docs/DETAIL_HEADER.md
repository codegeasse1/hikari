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
  logo.width = screenWidth * lerp(0.62, 0.34, progress)  // shrinks as it rises
  logo.top   = lerp(artBottom - logoHeight - 10dp,       // on the art...
                    statusBar + 5dp,                     // ...then pinned at the top
                    progress)
  ```

  The art's height is read from the LAYOUT (`onSizeChanged`), never assumed from
  the style: one of the shapes wraps its content, and the wordmark's travel is
  measured against the real thing. The wordmark's own aspect ratio comes from the
  loaded image (`onSuccess`), so the 3:1 stand-in is used on the first frame only.

## Rules to keep

* **The overlay is outside the scroll.** If the wordmark lived inside the header
  art, it would scroll off with it — the whole point is that it does not.
* **The height is measured, the position is interpolated.** Positioning the
  wordmark from a hard-coded fraction of the screen instead of the measured art
  height is what breaks on a different header style or a different aspect ratio.
* **A missing logo is not a failure.** A blank logo leaves the page exactly as it
  was before this feature (text title, same spacing), and the text title's *layout*
  (the `if (heroLogo.isNullOrBlank())` guard) is the only place the two cases
  differ.
* **The text title is not drawn when the wordmark is.** The wordmark's
  `contentDescription` carries the title for a screen reader.
* **The back button never scrolls away.** The one inside the art is drawn by
  `Hero`; a second one in the page's own box takes over once the art is mostly
  gone (`progress > 0.6`), from the same corner.
