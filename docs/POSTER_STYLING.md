# Poster styling

Everything in this document lives in two files and is read by every grid in the
app:

* `data/AppStore.kt` — the preferences (blur, corner, titles, scores, glass,
  the effect set, the aura colour, and the edge light's point + strength).
* `ui/PosterStyle.kt` — `PosterStyle` (the value a grid reads),
  `rememberPosterStyle()` (the one place it is collected from the store),
  `PosterEffects` (the treatments) and `PosterArt` (the one composable that draws
  a card).

**One drawing path.** Every poster in the app — Home's shelves, Show All, Search,
Library, a collection's grid, a TMDB catalog grid, the loading card — is a
`PosterArt`, and a grid that drew its own would be the bug. That is why a setting
changed in Settings → App Layout → Poster & icon styling is visible on the next
frame everywhere at once, with no restart and nothing to refetch.

**`PosterArt` is called with a `PosterStyle` that the ROW remembered**, not with a
fresh `rememberPosterStyle()` per cell: one collection of the store's flows per row
is the difference between one DataStore subscription per screen and one per card.

## The treatments (`PosterEffects`)

A card wears a SET of treatments, not one — `PosterEffects.normalizeSet` keeps them
in `ALL` order and drops `NONE`, and each treatment draws its own layer inside
`PosterArt`. The set is stored as one comma-joined string (`encode`/`parse`), with
the older single-key value still readable so an update does not lose a look.

| key | what it draws | costs |
| --- | --- | --- |
| `glow` | the artwork itself, enlarged and blurred, behind the card | one extra draw of the poster's own halo decode (`PosterLoader.haloModel`) |
| `tilt` | a real 3D lean (`rotationX/Y` + a close camera) with a cast shadow and an edge highlight | one `graphicsLayer` |
| `sheen` | a band of light sweeping the art | one shared animation clock |
| `aura` | a breathing gradient ring in the card's own colour | the same clock |
| `spotlight` | an accent pool behind the card plus a bottom scrim | a `radialGradient` |
| `frame` | a gallery mat: an inset hairline frame and a soft accent tint | two borders |
| `lit` | the edge light — see below | two `radialGradient` draws |

`PosterEffects.animated` (sheen/aura) is what decides whether a card creates an
animation clock at all, and `PosterEffects.haloed` (glow/spotlight) is what makes
the blur slider's value irrelevant — those two want a halo whatever the slider
says. `TvMode` performance mode drops the whole set and the halo, so a TV stick
draws plain posters and keeps its frame rate.

### The edge light (`lit`)

The Nuvio-style look: the card is lit from ONE point and falls into shadow away
from it. `Modifier.edgeLight(centerX, centerY, strength)` is the whole
implementation — two radial gradients over the artwork:

* a soft white pool centred on the point (the light itself), and
* a shadow that grows with distance from the SAME point.

Both halves are needed. A bright spot on an otherwise uniformly bright card reads
as a blob; real light leaves the far side darker than it found it, which is what
makes a flat poster look like an object standing in a room. Both are `drawRect`
calls over a gradient, so nothing is decoded, blurred or cached, and the treatment
works over artwork that has not finished loading.

The point is the user's: `POSTER_GLOW_X`/`POSTER_GLOW_Y` are stored as FRACTIONS of
the card (0,0 = top-left, 1,1 = bottom-right) rather than in pixels, so the same
pair means the same place on a 90dp grid cell and on a 400dp hero. Settings draws
the real modifier over a stand-in card with a draggable dot, plus a strength
slider; the drag is held locally and written when the gesture ends, because a
DataStore write per frame would fight the finger it is following.

## Where the pieces are read

* `PosterArt` — the card. Draws the halo, the treatments, the score badge and the
  glass hairline in a fixed order (halo/treatment layers BEHIND the art, the
  light and the scrims OVER it, the ring and the frame last), so two treatments
  never fight about which is on top.
* `rememberPosterScore` — warms and prints the score badge (`Ratings`).
* `RatingBadge` — the IMDb-yellow number on its dark pill (deliberately small).
* `AuraColors` — the aura ring's colour, `THEME` ("Accent") meaning "follow the
  app accent", which is what the ring always drew.

## Adding a treatment

1. A key + label + description in `PosterEffects`, listed in `ALL` (Settings builds
   its picker from `ALL`, so the row appears by itself).
2. Draw it in `PosterArt`, in the layer order that makes sense, and read it from
   `style.effects` — never from the store directly.
3. Any setting it needs is a key in `AppStore` with a flow, a getter and a setter,
   collected in `rememberPosterStyle`, or a field with a default on `PosterStyle`.
4. If it animates, teach `PosterEffects.animated` about it so the clock is created;
   if it wants the halo, teach `haloed`.
