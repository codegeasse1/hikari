# Catalog pages, and searching INSIDE one

There are two catalog pages in the app, and both can search — in place, over what
the page is already about. Neither hands a name to the app's own Search tab: a
search started inside a studio's catalogue (or inside one manga engine) is a
question about THAT catalogue, and answering it by leaving the page is the bug that
was reported ("search inside a production sends me to Hikari search").

## `CatalogScreen` — an extension's own catalog (`ui/screens/CatalogScreen.kt`)

The route a provider's own `CatalogRef` opens. Its `CatalogViewModel` loads the
catalog page by page (infinite scroll) and can be re-pointed at another of the
engine's lists without leaving the page:

* **`catalog` / `switchCatalog(id)`** — which of the engine's lists is showing. A
  MANGA engine publishes exactly two (Popular, Latest — `MangaProvider.catalogs`),
  and `CatalogScreen` draws them as tabs for the catalog the user is already in,
  instead of sending them back to the Manga tab to press the other pill.
* **The in-engine search box** — drawn only for a MANGA catalog (`searchable`,
  `rawType == "manga"`). It asks THAT engine (`provider.search(query, page)`) rather
  than every installed one, and it pages exactly like the catalog does. A video
  extension does not get the box: Home's "search this extension" magnifier and the
  Search tab's own scope row already do that job, and a third way in would be a
  third way to be wrong.
* The box is debounced (400ms) and the text is local, so a half-typed word is one
  request and the grid is not cleared while the user is still typing.

## `TmdbGridScreen` — a studio, network, list, person or preset

The destination of "Show all" on a TMDB row and of every Production/Network/Person
tap. Its `TmdbGridViewModel` pages a `TmdbSpec` (or a built-in preset) 20 titles at
a time. This page cannot ASK for a search — a studio's catalogue is one paged
discover query and there is no text filter on it — so its search box does two
things instead:

* **It filters what is loaded** (title or original title, case-insensitive).
* **It walks further into the catalogue** while nothing matches: up to eight
  additional pages, bounded, with the counter reset whenever the query changes. A
  specific film can be dozens of pages into a studio's catalogue, so a filter that
  only ever saw page 1 would answer "not here" for a title the studio obviously has
  — and scanning a 500-page catalogue in the background to satisfy a keystroke is
  not a search either. Eight pages is the compromise, and "Looking further into this
  catalog…" says which of the two states the page is in (searching vs. nothing).

## Adding a catalog page

* If it can ask its source for a query, ask the SOURCE (page it, debounce it).
* If it cannot, filter what is loaded and say so while you look further — and never
  swap the user into another screen to answer their own search.
