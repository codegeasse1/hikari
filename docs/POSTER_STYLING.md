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

## The two corner tags

The poster's top-**left** corner carries up to two small chips, drawn by
`rememberPosterBadges(item, style)` and stacked downwards:

| key | chip | default |
| --- | --- | --- |
| `POSTER_SHOW_TYPE` | `Movie` / `Series` — which one this title is | **on** |
| `POSTER_SHOW_QUALITY` | the best quality Hikari has SEEN (4K, 1080p, Blu-ray…) | **off** |

Both are drawn by `PosterTag`: plain white text on a dark translucent pill, which
is the shape the reference clients draw their own quality chips in (their "4K" /
"Web" / "Blu-ray" labels are white on a dark rounded chip in the poster's
top-left corner). They are deliberately NOT `RatingBadge` — that one is
IMDb-yellow because a score IS an IMDb number, and a yellow "Movie" would read as
one more rating on a cell that carries both.

Why they live there and not on the top-right: that corner is the score badge's
(`ratingAlignment`), on every grid, so the two can never land on each other. A
cell that has already spent the top-LEFT on something of its own (the
Related/Similar shelves put the score there, because the kebab owns the
top-right) passes a different `badgeAlignment` instead of stacking them.

**The stack order is fixed, and both ends matter.** `PosterArt` draws the badges
as a `Column` anchored at `badgeAlignment` with a 3dp gap, so:

- with BOTH switched on, the quality chip is first and the type chip sits
  directly under it — and it is the same order on every poster, never swapped
  between two cells;
- with only ONE switched on, that chip is simply the top of a one-item column,
  i.e. it sits at the top of the corner, never "down below" where the second one
  would have been. That was an explicit request when these were added.

`badges` is empty when there is nothing to draw, when both settings are off, or
when the caller passed no `item` — a cast portrait, a collection's cover tile and
the detail page's own hero art all draw art for something that is not a catalogue
item, so they pass nothing and get no chips.

**The quality chip is never a guess.** `data/TitleQuality.kt` only prints a label
it has actually seen, cheapest source first:

1. the item's own text — extension rows routinely name a title
   "Movie (2024) 1080p WEB-DL", which is the site's own answer (`fromText`, over
   a RANKED list of patterns: 4K/2160p, 1080p, 720p, 480p, 360p, HDR, Blu-ray,
   Web, HD, CAM — a resolution always beats a release tag);
2. the SERVERS found the last time the title was opened (`bestOf` over the
   stream names: "HdHub 4K", "NetMirror 720p").

A label is filed under `searchTitle|year`, so the same film seen from two
extensions shares one badge and two different titles that share a name do not. A
weaker label never overwrites a stronger one (a bad server day must not demote a
title), and the small map is persisted in `filesDir/known-quality.json` alongside
`Ratings`. `TitleQuality.revision` is a `StateFlow<Int>` that `rememberPosterBadges`
collects, so a grid that is already on screen picks up a new badge the moment a
search teaches the app the answer.

**No disk I/O on the caller's thread.** `forItem` is called from COMPOSITION (a
poster cell reading its own badge) and `remember` from the player's main-scoped
coroutines, so neither may touch the file: `warm()` kicks the one-time read off
on the store's own `Dispatchers.IO` scope (it is an `AtomicBoolean` check, so
calling it from every poster cell is free), and `remember` queues load → mutate →
write on the same scope, guarded by one lock — `save()` writes the WHOLE map, so
two unguarded writers could interleave inside the file. Until the load lands,
`forItem` answers from the item's own title text; the `revision` bump when it
does is what repaints the cells that a previous session's server list can now
label.

## Where the pieces are read

* `PosterArt` — the card. Draws the halo, the treatments, the score badge, the
  type/quality chips and the glass hairline in a fixed order (halo/treatment
  layers BEHIND the art, the light and the scrims OVER it, the ring and the frame
  last), so two treatments never fight about which is on top. It takes an
  optional `item` — that is what the corner chips are derived from, so a grid
  that forgets to pass it draws no chips.
* `rememberPosterScore` — warms and prints the score badge (`Ratings`).
* `rememberPosterBadges` — the movie/series + quality chips (see above).
* `TitleQuality` (`data/TitleQuality.kt`) — the quality labels the chips print,
  and the `revision` they repaint on.
* `RatingBadge` — the score: IMDb-yellow text on its dark pill (deliberately
  small, and yellow because a score IS an IMDb number).
* `PosterTag` — the corner tags: plain WHITE text on the same dark pill. Do not
  swap these two: a yellow "Movie" reads as one more rating, and the reference
  clients draw their own quality chips white-on-dark.
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

## Where the quality label comes from (and what used to stop it appearing)

`TitleQuality` (`data/TitleQuality.kt`) is the only source of the quality tag.
`forItem` answers a poster cell, and it never touches the disk: it reads the
title's own text first ("… 1080p WEB-DL"), then the map filed by completed
searches.

What fills that map is the point — a search only counts if **something records
it**, and 0.10.23 fixed the paths that did not:

- the pass's own non-empty result (always did);
- **the cache hit in `prefetchFirstStreams`** — every re-entry into a detail page
  takes this path, and it used to return without recording anything;
- **the never-downgrade fallback** in `resolveStreams` (a cut-short pass handed
  the cached/live list instead of a verdict);
- **the player**: `notifySourcesChanged()` files the best quality of every
  server list the player holds, so a title that was simply *played* gets its
  badge. It used to record only when the user switched episode.

Lookups also stop depending on the year matching: `byTitle` holds the same labels
keyed by title alone, and `forItem` tries the exact `title|year` key first. Two
catalogue rows for one film disagree about the year often enough that the badge
was invisible to the poster cell that drew it.
