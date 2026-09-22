# Content filters — the adult-content switch

Settings → **Content & Filters** → *Adult content* (ON by default). It is one
switch, and this file is the whole rule: where it is stored, what it changes, what
it deliberately does not, and why each decision went the way it did. Read it before
touching `data/NsfwGate.kt`, `data/ExtensionNsfw.kt`, `ui/ContentGate.kt` or the
provider list.

## What the switch is

| | ON (the default) | OFF |
|---|---|---|
| Adult/R-rated TITLES | shown, exactly as the extension published them | filtered out of every list the app draws |
| 18+ EXTENSIONS (installed) | listed and used | not listed, **not instantiated at all** |
| 18+ EXTENSIONS (installable) | offered in the store lists | hidden from the store lists |
| The user's stored data | untouched | untouched |

Nothing is ever deleted, rewritten or migrated. The user's library, history,
downloads, collections and installed configs stay exactly as they are; with the
switch back on, everything returns on the next frame.

## Where it lives

* **Stored**: `AppStore.K.NSFW_ENABLED` (`booleanPreferencesKey("nsfwEnabled")`),
  read through `nsfwEnabledFlow()` which defaults to **true** — an install that has
  never touched the setting is the app's normal behaviour. Written by
  `setNsfwEnabled(on)` from the Settings card.
* **Mirrored**: `NsfwGate.on`, a `@Volatile Boolean` kept in step by a single
  collect started in `HikariApp.onCreate` (next to the player-skin and
  redirect-allow mirrors, the same shape the other app-wide caches use). It is a
  plain field on purpose: the gate is read on the DRAW path, and a list being
  composed cannot await DataStore.
* **Reactive**: `ui/ContentGate.kt` — `rememberNsfwEnabled()` collects the flow so a
  screen re-filters the moment the switch is flipped, and `rememberVisibleItems` /
  `rememberVisibleRows` remember their result against that value. This is the
  missing half of a synchronous flag: nothing would recompose just because a field
  changed.
* **Re-listened**: the same collect re-runs `ProviderManager.refresh()` when the
  value actually changes, which is what adds or removes an 18+ extension's
  providers for the rest of the session (see "Extensions", below).

## The rule: what counts as adult

Two layers, because the two questions are different.

### 1. An extension is 18+ (hides a whole source)

`ExtensionNsfw.isNsfw(context, config)` asks the manager that loads the extension,
because that metadata is the only place the tag exists:

* a **manga** extension: `tachiyomi.extension.nsfw == 1` (`MangaExtensionManager`);
* an **Aniyomi** extension: `tachiyomi.animeextension.nsfw == 1` or Aniyomi's
  `aniyomix.contentWarning > 0` (`AniyomiExtensionManager`).

The answer is cached per `.ext` file, exactly once per extension per session, and a
lookup that FAILED is not cached (a failed load is not a verdict — the loader has
its own retry window and a permanent `false` here would outlive it). The call is
BLOCKING (it instantiates extension classes) and only ever happens when the switch
is OFF; the Aniyomi loader returns null outright if it is called on the UI thread.

The flag is deliberately NOT persisted on `ProviderConfig`: every extension the user
already has would need a backfill, and one installed before the flag existed would
silently stay visible with the switch off — the one failure this feature must not
have.

### 2. A TITLE is adult (hides one item)

`NsfwGate` — the single rule, used by every screen:

* an explicit marker from the source: TMDB's `adult` flag, or a provider whose own
  type says so (`MediaItem.nsfw`; CloudStream's `TvType.NSFW` sets it, and it is
  carried on the item so a catalogue page, a search hit and a cached shelf each
  carry their own answer);
* an 18+/adult **genre or tag** (`ADULT_WORDS`): `adult`, `18+`, `r-18`, `r18`,
  `hentai`, `porn`, `pornography`, `xxx`, `erotica`, `erotic`, `jav`, `smut`,
  `nsfw`, `18 plus`;
* the **name**, checked as a WHOLE WORD only — extension catalogues for this
  material usually say so in the title ("… Hentai", "JAV …"), and a substring test
  would eat innocent titles ("Adaptation" contains "adult");
* an adults-only **age rating** (`ADULT_RATINGS`, for the detail screen, which is
  the only screen that knows a title's real certificate): `R`, `NC-17`, `X`, `XXX`,
  `18`, `R18`, `X18`, `A18`, `MA18`, `ADULTS_ONLY` — normalized (uppercase, no
  spaces, no `+`/`-`) before lookup.

**TV-MA is deliberately NOT in the rating set.** It is the television equivalent of
an R and covers most prestige drama; hiding every TV-MA show would empty the
shelves of someone who only meant to turn off adult material. Likewise **"Ecchi" is
not an `ADULT_WORD`**: it is fanservice in a mainstream shonen title as often as it
is adult material, and hiding a whole genre on an ambiguous word is how a filter
turns into a bug report.

### The two predicates, and why they are split

* `NsfwGate.isAdultText(title, genres)` — **pure**: "does this text look adult?" It
  ignores the switch, so a caller that already knows the switch's state can combine
  the two.
* `NsfwGate.isAdult(item)` / `isAdultRating(cert)` — **switch-aware**: "would the
  gate HIDE this?", i.e. always false while the switch is on.

Getting this backwards is a silent no-op — a filter that asks the switch-aware
predicate while the switch is off is told "not hidden" for everything — so
`filter()` spells the per-item rule out rather than routing through `isAdult`.

## Where the rule is applied

* **Provider list** (`providers/ProviderManager.refresh`) — an 18+ extension is not
  instantiated at all while the switch is off. This is the important one: a
  provider that is not in the list is not asked for catalogues, not searched, not
  listed, and its titles cannot reach Home, a collection, the search queue or
  "Continue watching" by any route. Filtering the DRAWN lists instead would leave
  every one of those paths intact behind the UI.
* **The TMDB requests themselves** (`nuvio/TmdbResolver.apiGet`, through
  `NsfwGate.capDiscoverCertification`) — every `/discover/movie` query carries
  `certification_country=US&certification.lte=PG-13` while the switch is off, so an
  R-rated film cannot reach a row in the first place. The drawn-list filter cannot
  do this job: a `/discover/movie` answer is a title, a poster and a year, and
  carries NO certificate — there is nothing on the item to test. Every shelf, browse
  grid, preset and Production/Network/Person catalogue in the app IS a discover query
  (`data/TmdbSources.kt`, `data/TmdbBrowse.kt`, `data/TmdbPresets.kt`), so one line
  in the one place every TMDB request passes through covers them all. TV is
  deliberately not capped — TV-MA is not in the adult set (see section 2), and a cap
  that removed it would empty the shelves of someone who only meant to switch off
  adult material. A query that names its own `certification`/`certification.lte` is
  left alone: that is the user stating what they want, and the switch is not a licence
  to override it. `HomeViewModel` drops its cached feed and rebuilds the moment the
  switch moves, so the change lands on the feed the user is already looking at — the
  one case a request-level rule cannot fix on its own.
* **Every drawn list** through `rememberVisibleItems` / `rememberVisibleRows`:
  `MediaRow` (every shelf: Home, collections, the detail page), `CatalogScreen`'s
  grid, `SearchScreen`'s grid, `LibraryScreen` (the grid AND its empty state, so a
  hidden title cannot leave a blank grid under a heading), `CollectionScreens`'
  TMDB catalog grid, `MangaScreen`'s search results, and `ContinueWatchingRow`
  (which is fed from the same history as the History tab, so something watched
  before the switch was flipped would otherwise come straight back onto Home).
* **Store listings** (`Cs3RepoPlugin.nsfw`, from `ExtensionNsfw.repoEntryNsfw`):
  CloudStream's `tvTypes` containing `NSFW`, a Mihon/Aniyomi index entry's
  `nsfw: 1` or `contentWarning: CONTENT_WARNING_NSFW`. `ExtensionsScreen` filters
  the whole `pluginsByRepo` map once, at the one place the screen reads it from, so
  no tab — or its "Install all" — can bypass it. Repos keep their keys, so a repo
  whose entire listing is hidden still reports as loaded rather than refetching.

## What is deliberately NOT filtered

* **IPTV** playlists, groups and channels. That is the user's own list, from their
  own provider; hiding entries from a playlist the user typed in would be
  surprising and slow (the list is parsed, not fetched per title).
* **The user's own manga library and reading progress.** Their own follows, not a
  catalogue's output.
* **A page the user explicitly opened.** `NsfwGate.isAdultRating` is the rating half
  of the rule, and it is NOT wired into the detail screen: blocking a page reached
  from a link, a collection or a title the user typed fights the user rather than
  helping them. What keeps this material from being DISCOVERED is the catalogue half
  — the drawn-list filter and the TMDB certification ceiling above — and a saved or
  shared link is the user's own decision to open it.

## The two accepted consequences

* With the switch OFF, an installed 18+ extension is not listed on the **Extensions**
  screen either — so it cannot be uninstalled from there until the switch is turned
  back on. That is "hide", as asked, and the way back is one tap in Settings; the
  alternative (a locked row that cannot be used but says it exists) was judged worse
  than a clean list.
* With the switch OFF, a film TMDB has no US certificate for disappears from the
  catalogues too. `certification.lte` is a request-level filter and TMDB will not
  match a title it holds no certificate for — so a niche row (a small country's
  cinema, a straight-to-streaming release) comes back shorter than it does with the
  switch on. That is a shelf with fewer cards on it, which is precisely the trade the
  switch was asking for; the alternative is a filter that cannot see the rating it is
  supposed to be filtering on, which is how "I turned 18+ off and it still shows
  R-rated films" happened.

## Adding a new surface

Anything that draws titles or offers extensions must go through one of:

* `rememberVisibleItems(items)` / `rememberVisibleRows(rows)` — for a list, in a
  **composable** scope. A lazy grid/list BUILDER is not a composable scope, so hoist
  the call above the builder (`val visible = rememberVisibleItems(items)` then
  `items(visible, …)`) — calling it inside `items(...)` does not compile.
* the `pluginsByRepo` filter in `ExtensionsScreen` — for store listings.
* `ExtensionNsfw.filter` — for anything holding `ProviderConfig` rows.

If a new list is added and it is not one of those, that is the bug: the switch now
has a hole, and the only way to find it is by flipping the switch and looking.
