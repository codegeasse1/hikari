## 0.6.6

The extension a title was opened from now gets to play it, an extension that stops responding can no longer freeze the server count, and the loading screen is never a plain black frame.

### Added

- **Your own extension plays first.** When you open a title inside an extension, that extension's server is placed at the top of the server list, and playback waits for it to answer before falling back to a server from anywhere else. If it turns out to have no link for that episode, playback starts on the first other server right away instead of waiting.
- **A loading screen with no artwork now has a background.** Titles whose source provides no poster or backdrop used to spend the entire server search on a black screen. The detail page and the player now show a dark gradient wash in its place, so a search in progress is clearly visible.

### Fixed

- **The wrong server playing.** The server list was ordered only by which extension answered fastest, so a title opened from one extension could start playing a same-named video from a different one, and the correct server did not even appear in the list. Servers are now ordered with your extension's first.
- **Playback only working on a second attempt.** An extension's first request for a title has to start its runtime and open the site's session, which is far slower than the requests that follow. If that first request timed out it was discarded and never repeated, so a title looked like it had no working server until you tried again. The extension is now asked with a short probe followed by a full retry, and if the search still ends without an answer from it, it is asked once more in the background and anything it finds is added to the playback already running.
- **The server count freezing on "still searching".** An extension that stopped responding kept its place in the running count forever, so the search appeared stuck and only recovered after leaving the page and starting again. A stalled extension is now marked as stopped responding and removed from the count.
- **Search pages being accepted as results.** Some sites return the search request itself as the page title, which means the text you searched for appears inside it and it can score as a valid match. Page titles that are really web addresses or query strings are no longer accepted.
- **Playback failing with no explanation of why.** The log now records which extension was asked, what it answered, and whether playback stopped waiting for it, so a server that never replied can be identified instead of guessed at.

## 0.6.5

Test build — the search stops claiming it has finished while it is still working, the repos a pass never got to (or was cut off from) are handed to the background sweep on **every** exit, a tap that lands during that sweep joins it instead of re-running 250 extensions, and two reported crashes are fixed: the page no longer re-plays itself after backing out of the player, and a personal catalog with a catalog listed twice no longer takes the screen down.

**"Search finished" is only said once the search really is finished.** Reported twice now: "see it again saying search finished on 5 servers, but on second click it shows all lots of server — why are you not fixing it". The count was never wrong; the **verdict** was. A lookup can come back with servers and no verdict at all — either it was cut short, or (the common case) it had joined the detail page's own still-running pass and came back before that pass did — and the flow announced "search finished" over it anyway, while that pass went on pushing servers into the very same list (5 → 16 → 30 → 43 in the log). The words now follow the state: with servers on the list and no verdict the cover reads **"Found N servers — still searching…"**, and it keeps reading that, re-stating the count every time it grows, until the list has been quiet for 12 seconds or a running sweep has ended — and only then does it say "Found N servers — search finished." A search that is still running can no longer be described as a finished one.

**Every pass now hands its unfinished repos over — including a pass that was cut off.** This is the mechanism behind the same report, and the reason the earlier fixes looked like they did nothing. The hand-off to the background sweep (the repos a pass never reached, timed out on, could not load, or matched but whose links were cut off when the pass ended) lived **inside the pass body** — so it only ever ran for a pass that reached its own end. In the log that was supplied, that is *no pass at all*: not one of the four passes ever reached its "done" line, because each was **cancelled** — the detail screen is re-created when you back out of the player, rotate, or re-open the title, and its viewModelScope dies with the pass it started. The hand-off now sits in the pass's `finally`, which runs on every exit — normal return, throw, or cancellation — and the sweep it starts lives on the application scope, so it outlives the screen that started it. The memory-answered repos ("no such title" from a single blank page earlier in the session) are merged into the **same** sweep, so there is one continuation per video and no target is ever silently dropped. Each pass now logs what it did with the leftovers: `pass over: 245 target(s), 20 with servers, 5 server(s) on the list, 168 repo(s) unfinished (pass was CUT OFF early) → sweeping them in the background`.

**A tap during that sweep joins it.** The second Play tap used to run all 250 extensions from scratch — the "on second click it shows all lots of server" half of the report — and it now registers itself on the sweep that is already running: it is handed the servers known so far and receives every new one as it lands, so the list grows in place instead of being rebuilt. That is also what makes backing out and coming straight back feel like the same search continuing rather than a new one starting.

**At most three sweeps run at once.** Handing over the tail on every pass is real work — hundreds of extensions to search and extract from — so browsing ten titles quickly cannot be allowed to leave ten of these running on a phone. Three may work at a time (enough for the title being watched and the one just left, in both directions); past that a new one is not started and the pass's own list stands, with the reason written in the log: `sweep "…" not started — 3 sweep(s) already running (168 repo(s) left unfinished)`.

**The page no longer re-plays itself when you back out of the player.** "Clicking back button starts loading the loading screen again with server search." Opening a title from History auto-resumes it (that is what makes Continue Watching work), and the once-only guard for that auto-resume lived in the composition — so every re-creation of the page (back out of the player, rotation, process rebuild, the same History row opened again seconds later) saw it as "not handled yet" and opened the player **and a fresh server search** again, from scratch. That is how two and three player instances ended up stacked on one episode. The guard now survives recreation *and* is held process-wide for a few minutes, so a re-created page shows its own Play/Resume button instead of starting a search by itself. A deliberate tap still plays, of course.

**Crash fixed: `Key "prov|cs3|…" was already used`.** From a crash log shared by a user on 0.5.22, thrown the moment he scrolled a personal catalog: `IllegalArgumentException: Key "prov|cs3|753064690|2|SERIES|row:8:0" was already used. If you are using LazyColumn/Row please make sure you provide a unique key for each item.` A catalog source is keyed by the catalog it points at, so a folder holding **the same catalog twice** (written by an older build, or restored from a backup) put two identical keys into the lazy list and took the whole screen down. This is now impossible three times over: duplicate sources are dropped when a collection is loaded, dropped again when its rows are built, and the list keys themselves carry the row's index as well as the catalog's key — so even a catalog that somehow arrives twice cannot crash the editor or the collection view.

**The log now says WHY a search asked fewer repos than are installed.** "Installed=Hikari=181" next to "families=Hikari=73" is a question the log could not answer on its own: the second pass of the same title asked 108 fewer Hikari repos than the first, and the only honest answer is *which filter* dropped them. Every pass now prints it — `skipped=cf-skip=104,hung=3,origin=1,…` — so "my repo was never asked" is answered in one line, and a repo sitting behind a Cloudflare wall is visibly different from one that was starved of a slot by the pass's own budget.

## 0.6.4

Test build — a stalled server no longer stops the search, a Play tap opens the player once, a thin early result keeps searching, personal-catalog covers can be cropped and wear the poster effect, the loading card has four looks to choose from, and the detail page's title has air under the artwork.

**A server that isn't responding no longer ends the search.** Reported as "it said the server is not responding, so why did it get stuck and stop all the other server searches". When the only server found so far never starts, the player used to put up the failure panel and call it done — even though the cross-extension pass and its background sweep were still finding servers, and would have streamed them in a minute later. Now a stalled server is marked (its URL tried, its host failed for the session, so no failover hands it back), and **the player waits for the search instead of failing**: whichever other server lands first starts automatically, and the cover says "X isn't responding — looking for another server…" while it waits (that line also survives the search's own progress text, which used to overwrite it). The honest failure panel appears only once the search really is finished and nothing new arrived. The same is true of a dead link after a failover: `refreshSources` used to report failure after its own fixed wait, regardless of whether the whole search was still running.

**A Play tap opens the player exactly once.** "When I press Play it loads 3 instances of the same page — I press back and the same server-loading page comes up again, every time, on every title." That was the player activity being launched more than once per tap and stacked: the immediate launch (before any server existed) did not arm the once-only guard, so when the user backed out of the player the guard was false again, and the first server that arrived afterwards launched a second copy — and a third, one per provider batch. The guard is now armed by the immediate launch itself, so every later server is appended to the live session the player is already listening on, never a new activity.

**A small early result keeps searching instead of saying "finished".** "It says 4 servers and search finished, then on the third tap it searched many servers; another title said 2, and the fourth tap found 36." The difference was which repos a pass answered **out of memory**: a session-wide "no such title" record is created by a single blank page, and repos answered from it are never really asked — so a pass can come back in seconds with two or three servers and look complete. Now a pass that returns **fewer than six** servers while repos were answered from that record is treated as unfinished: those repos are re-asked for real in the background and **every server they find streams into the same list** the player is already showing. "Search finished" is only said once that pass is over, and the cover reads "Found N servers — still searching the remaining extensions…" until then, so the count on screen is a running total rather than a result that later jumps to 36.

**Personal-catalog covers: crop, and the poster effect.** There was no crop button, so a long portrait photo in a 16:9 tile (or a wide shot in a poster tile) could not be adjusted — the image was simply cut wherever the tile's shape landed. A cover now has a **Crop & zoom** button beside "Choose from storage": the picture opens at exactly the shape of the tile it is going into, and dragging/pinching (or the zoom slider) frames it while a live preview shows precisely what the tile will draw. The crop is computed from the same scale and offset as the preview — it is the viewport — so what is on screen is what is kept, and the source's EXIF orientation is applied first, so a phone photo is not saved on its side. The cover also **wears the user's own poster styling** (Settings → App Layout → Poster styling): the rounding, the halo, and the chosen effect — aura ring, 3D tilt, sheen, glow, spotlight or gallery frame — now apply to a personal catalog's pictures just as they do to a TMDB poster, instead of the plain rectangle they used to be.

**Four looks for the "finding your server" card.** Settings → Loading screen now offers **Cinematic** (the backdrop drifting behind the breathing title, the way it has always looked), **Poster card** (the title's own poster on a glass pane with a progress bar), **Spotlight** (no artwork — the title in a pool of accent light) and **Minimal** (flat and quiet: a spinner and the status line). One choice covers both screens that show the card — the detail page's full-screen cover and the player's own — so the hand-off between them never changes the design under the user. Every style keeps the live status line, so nothing is lost by picking the quiet one.

**The detail page's title has air under the artwork.** Marked on a screenshot: the title, its year line and the genre chips began on the artwork's very last row of pixels, so the two read as one block and the title looked pushed up into the header. The first line of the page now starts 16dp below the art, and the genre chips scroll sideways instead of running off the right edge when a title has four long ones.

## 0.6.3

Test build — Default is the curved glass again, the server box has room around its rows, the search report says what it means, and a personal catalog can be searched from its own header and picked in the Search tab.

**"Default" is Hikari's look again — the curved one.** 0.6.2 added a Default that was a plain, flat box, which is not what was asked for: the look asked to be the default was the **curved glass pane** — the bent sheet the server list loads inside, with the neon running down its two bowed sides. Default now *is* that (the frosted bars, the round glass pills and the accent-ringed play button it always had, on the curved pane), and **Glass is gone from the picker** because it was the same look under a second name — listing one look twice is what made the picker read as broken. The list is Default / Minimal / Cinema / Neon; an install that has "glass" stored resolves to Default and looks byte-for-byte the same as before.

**The server box has room around its rows.** Reported from a screenshot: the row pills were flush against the box's left and right edges ("the server roundy UI is fully attached to the box") and the **All** chip's own rounded box was cut by the box's rounded corner. A flat panel has no curve for its rows to follow, so its padding is the only inset there is — and it had none: a row's cap was laid out at the panel's edge and sliced by the silhouette. The panel now keeps 8dp of air on both sides, and the top of the content clears the corner each skin actually has (the needed inset is solved from the corner radius, so an 18dp corner and NEON's 26dp one both come out right). The box is also **longer** — 0.97 of the window (0.95 for the curved pane) against the old 0.95/0.93, cap raised to 560dp — so a long engine name, its quality and its host fit on one row. The height fit measures the content at the width it will really get, so the last row is not clipped off the bottom any more.

**The cross-extension report says what it means.** "82 no answer in time" read as a verdict about the *title*; it is a statement about the *repos* (they were still working when the pass's budget ran out, or their turn never came), and they are re-asked by the background sweep while the video plays. The line now reads in plain words — "didn't answer in time", "don't carry it", "couldn't load", "not searchable", "have it, no links", "stopped responding" — and the tallies are shown **whether or not servers came back**, because (in the user's words) that line is how you can tell the app really is asking every installed extension. It also no longer says "done" while a sweep is still running: the state is "N still searching" or "pass over, still searching" until the search really is over.

**A search inside a catalog.** The header of a catalog page — the folder/collection page titled with the catalog's name, the screen the marked screenshot shows — now carries a magnifier beside the title. It opens the Search tab scoped to that catalog, so a title can be looked for across exactly the sources that catalog holds (Amazon, HBO, whatever the folders were built from).

**And your catalogs are in the Search tab's provider row.** The row listed only installed extensions, so the catalog the user had just built in Settings → Personal Catalog creator could not be picked there to search inside it. There is now a chip per saved catalog (named from the collection, tinted with the tertiary accent so it cannot be mistaken for an extension of the same name), selectable alone or beside extension chips. Picking a catalog searches **that catalog** — the extension sweep is skipped rather than quietly searching everywhere — and the search box and the status line name the scope ("Search in abc…"). Searching a catalog with nothing else selected shows what is inside it; tap a title and the detail page finds its streams as usual.

**Home's header no longer sits on the hero banner.** The header (Hikari, its tagline, the search / translate / web-view buttons) was drawn *inside* the banner's box, over the artwork; with the short "Compact strip" banner the two text blocks ended up jammed together and the buttons sat on the picture. It is now its own strip above the banner, so the banner starts below it and no hero style can collide with it. While a **collection** is picked, the magnifier also offers "In \<collection\>" beside "Global search".

**Checked while here: the search key is the original title.** Every provider query in the app — the cross-extension pass, the per-extension chips, the episode-list fallback, the yt-dlp last resort, the collection rows and the collection search — asks for `MediaItem.searchTitle`, which is the title's ORIGINAL (English) name whenever TMDB knows one, and the displayed name otherwise. Changing the TMDB language therefore changes what is *shown* and never what is *searched for*, which is why a Spanish-language install finds the servers that index the original name.

## 0.6.2

Test build — the search asks the right name, the panels take the shape of the skin you chose, and a personal catalog gets a search of its own.

**The localised title was breaking the search, everywhere.** Change the TMDB language and the app translates the names it shows — correctly. But that translated name was then used as the *search key*: the whole cross-extension pass (CloudStream, Hikari, Nuvio, SkyStream, Aniyomi — two hundred and fifty repos) asked every one of them for a title no site, no catalogue and no extension has ever heard of. What that looked like from the outside was "I switched the language to Spanish, the film is renamed, and now it says there is no extension for this movie", plus a large share of the "no playable sources" reports where the same title had played fine before.

- A TMDB item now carries both names: the **display** title (what you read, in your language) and the **original** one (what the extensions index). Every provider lookup — the cross pass, the match-confidence check, the episode-list fallback, the yt-dlp last resort, the provider remap on a detail page, the shelf cell that searches the extension it was opened from — searches the original, and falls back to the display name when there is no original.
- The name pair is resolved once, from TMDB, whatever route the title came in by: a preset row, a collection row, a hand-built one-title source, an imported list, the Search tab, the Library, or a detail page opened straight from a click. A detail page whose origin extension is gone now tries **both** names before it gives up, so it stops dead-ending on a title every other language finds.
- The page keeps showing your language: the origin extension's own `/meta` answers with its own (English) title, which used to flip the page back out of the language you chose the moment it loaded — the row's name is kept, and the original name rides along on the item for the lookups.
- One-title sources added through the collection editor carry it too, and a collection search matches either name, so "Avengers: Endgame" finds "Vengadores: Endgame".

**A "Default" player UI.** The picker offered Glass, Minimal, Cinema and Neon — all of them a look of Hikari's. There is now a **Default**: plain scrim bars, ordinary rounded buttons, a solid play button, no glass and no glow. It sits first in the list, and the stored fallback for an install that has never chosen stays Glass, so nobody's player is restyled behind their back.

**Each skin now shapes the dialogs its own way.** The Source / Quality / Audio / Subtitles / Speed sheets were already skin-aware, but three of the four skins were the same rounded slab with a different corner radius. They are now distinct shapes: **Default** an ordinary opaque card with a plain hairline; **Minimal** a flat slab with no edge line at all; **Cinema** a squarer deck with a harder edge; **Neon** a fully-rounded card edged in the accent; **Glass** unchanged.

**The panels fit their contents.** The hint line above the panel is three lines instead of two — the cross-extension status ("Asked 254 other repos (Aniyomi 13, CloudStream 57, Hikari 181, SkyStream 3) — done, 5 with servers") is exactly three lines on a phone and was being cut mid-fact. The panel itself is wider (0.95 of the window for a flat skin, 0.93 for the glass pane, and no longer capped against the window's height on the short axis, which is what kept the five engine chips wider than the panel in landscape) and taller, so the engine chips and the longer server names fit on one line.

**A personal catalog can be searched.** The folder editor's header now carries a search button — the same idea Home's header has — opening a search over TMDB's movies and series: type a name, see the posters, tap to add that one title to the folder (tap again to take it out), several in a row. Nothing else about the folder changes; each pick is an ordinary one-title source.

**And your own catalogs show up in Search.** The Search tab's "From your collections" row matched imported lists and hand-built TMDB sources, but skipped the third kind of source a folder can hold — an **extension catalog** you filed into your own catalog — which is the usual way a personal catalog is built, and therefore the reason the row never appeared. Those are now probed through the very code path the folder's own rows use, under the same eight-source network budget and the same gate, and the row fills in as each source answers (an imported list is instant; a catalog behind a server is not) instead of waiting for the slowest one.

**The search stops writing off repos it never really asked.** When a lookup comes back with nothing at all, the extensions it answered out of the session's own "no such title" record — the least trustworthy answer in the search, since one blank page creates one — are asked for real in the background instead of the app declaring there is nothing to play. The record's lifetime is also cut from five minutes to three, so a wrong one heals while you are still watching.

## 0.6.1

Test build — the search stops getting stuck, the player's panels learn the player's skin, and a catalog can hold one title. Plus: no extension gets to sell you anything.

**A search can no longer wedge itself.** The report was specific and infuriating: the same episode sometimes came back with a dozen servers, sometimes with two, sometimes only Nuvio's; sometimes the sweep froze at "87 of 159" and never loaded another source. The cause was one bad extension. A third-party plugin that blocks inside its own `synchronized` code can never be cancelled — nothing inside the call ever runs again — and until now such a call held its search slot for the rest of the session: a handful of them shrank the gates until the sweep had no room left to ask anyone, which is exactly "it found everything the first time and almost nothing the second". Now:


- Every gated cross-extension call is registered as in-flight with the provider it belongs to, and a watchdog (every 15s) refunds the slot of any call older than 200 seconds and marks that provider as hung — for the rest of the session it is skipped before its slot is ever taken. A wedged extension can no longer spend the budget of the ones that work.
- The sweep no longer silently drops repos it ran out of budget for: the leftovers are collected and asked in a **second round**, up to six rounds, skipping anything hung. The episodes that used to reach "146 other repos asked" and stop now finish the list.
- A hung provider is named on the player's report line ("stopped responding earlier") instead of just quietly missing from the results, and the stream ledger that remembers what a server once returned now lasts the whole session (an hour) rather than 15 minutes, so a server found once stops flickering in and out of the list.

**The player's panels follow the player's skin.** Source, Quality, Audio, Subtitles, the subtitle-style editor and the loading card were always the curved neon glass pane, whatever skin was chosen — a minimal player that opened a neon bubble the moment you tapped anything. Each skin now has its own panel: **Minimal** gets a flat, quiet, hairline-bordered sheet; **Cinema** a flat, near-black deck; **Neon** the accent-tinted glass; only **Glass** keeps the curved pane with its glow. The halo, the width and the row bending follow the same decision.

**The subtitle-style editor fits and scrolls.** Its rows (presets, text colour, weight, outline, shadow) were laid out in a fixed row, so "Custom" pushed the swatches off past the rounded edge — invisible and unreachable, since the panel could not scroll sideways. Each row's controls now live in a horizontally scrollable strip with the label kept to one ellipsized line, so every option is reachable at any text size.

**Personal catalogs can hold a single title.** The TMDB source picker could build a studio, a network, a list, a person, a director or a custom query — but not simply "this one film" or "this one show", which is the most obvious thing a personal catalog is for. There is now a **Movie or series** source: type a name (or paste a TMDB id or link), pick the title from the results, and the row is that title. One query matches both shapes, so each result says which it is, and the source remembers it.

**Search finds your own catalogs, and says which one.** A title you imported or built into a personal catalog was invisible to Search — Search only ever asked extensions. Results now open with a **"From your collections"** row: the titles your own collections carry, matched by name, each labelled with the collection (and folder) it came from, in front of the provider grid. Imported lists are matched with no network at all; a hand-built TMDB source answers from its first page under a short timeout, so the local half of a search can never hold the network half up.

**Extensions no longer get to sell you anything.** A repo or an extension that hangs a "goal achieved — send extra love — watch an ad to support" card, a ko-fi or Patreon button, or a donation link off its content had it rendered inside Hikari. Not any more:

- A navigation to a donation host (ko-fi, Buy Me a Coffee, Patreon, PayPal, GitHub Sponsors, Open Collective, Liberapay, Traketeer, Saweria, Sociabuzz, GoFundMe, Kickstarter and the rest) or to a donate/sponsor path on any host is **refused before it loads**, in the in-app browser and in every link that opens one — with "Blocked a donation page" instead of the pitch.
- A page that DOES load has its promo blocks hidden by an injected cleaner: the usual money classes and ids, and — so a card with no telltale class still goes — any plausible box whose text is only a funding ask and which contains a link or button. Real prose stays (a sentence that merely mentions support is not a donation card), and a video is never touched.
- Extension-supplied text is cleaned before it is drawn: repo and plugin descriptions in the Extensions screen have markup and donation links stripped and go blank when that is all they were, and a promo-only header or info row an extension pushes into its own settings screen is dropped.

**Kept smooth while doing it.** The promo cleaner runs at most once every 400ms (an ad-heavy page mutates constantly, and walking its links on every mutation is work the page cannot afford), a search probes at most eight network-backed catalog sources — imported lists cost nothing and are always all read — and a hung extension no longer spends the search slots of the ones that work, which is what made a sweep start fast and end up crawling.

## 0.6.0

Test build — the app gets a wardrobe. The big surfaces are now yours to shape: the Featured banner on Home, the header a title page opens with, the decoration on every poster, and the player's own control shell. Plus one new way to fill a folder: **import a list of titles** from JSON.

**The Featured banner, in four shapes** (Settings → App Layout → Featured banner)

- **Carousel** — the 16:9 cards that peek in from the sides, unchanged as the default.
- **Spotlight** — a full-width cinematic banner: the title set large over the artwork, the plot line under it, a score chip, and a scrim that lifts the type off the picture.
- **Compact strip** — a short 148dp band with the title and a round play button, so the rest of the feed starts sooner.
- **Showcase** — the poster beside the details in a glass card instead of behind them, so the artwork is never cropped. This is the one for the people who care what the poster looks like.
- Three independent switches for what the banner says: the **plot line**, the **score badge** and the **metadata** (year, runtime, seasons, genres). A style with no room for something simply ignores its switch, and each style's description in the picker says which.

**The details header, in five shapes** (Settings → App Layout → Details header)

The picture a title page opens with: **Wide** (the original 16:9 band), **Side by side** (a shorter band with the poster on the left and the back button moved to the top-right), **Tall** (portrait 3:4 art for series), **Poster** (the poster standing in front of a dimmed copy of itself), and **Plain** (no header art at all, just the back button — the fastest and the lightest on data). Every shape keeps the same back button, the same title block and the same play button beneath it; only the art above them changes.

**Seven poster effects** (Settings → App Layout → Poster styling → Poster effect)

- **Glow** — a coloured halo bleeding around the artwork (the blur slider, raised past it).
- **3D tilt** — the cards lean back towards the viewer, as if held at an angle.
- **Sheen** — a band of light sweeping across the art.
- **Aura ring** — a breathing accent ring around the card.
- **Spotlight** — an accent light behind the card with a scrim over the bottom of the art.
- **Gallery frame** — the art inset behind a hairline mount, the way a print is framed.
- **None** — the plain artwork, and still the default.

The two animated effects run off a single animation clock per card, and every animated value is read inside the drawing layer rather than in composition, so a moving sheen repaints a card's layer and nothing else — no recomposition per frame, no cost to scroll. Effect, blur, corners, titles, score badges and glass trim compose together rather than replacing each other.

**Four Player UI skins** (Settings → Player → Player UI)

- **Glass** — Hikari's existing player, untouched: frosted bars, round glass pills, the accent-ringed play button.
- **Minimal** — no panels at all; the title and the controls float on the picture over nothing but a whisper of a scrim (VLC-like).
- **Cinema** — a solid rounded deck under the picture with square control plates and a solid accent play button, in the spirit of a desktop player.
- **Neon** — the decks become floating rounded cards, clear of the screen edges, with accent hairlines and a glowing play button.

A skin changes presentation only — backgrounds, metrics, the play button's decoration — so no button can be lost whatever you pick, and media3's own layout rules (its overflow handling, its minimal-mode thresholds) are untouched. It applies to the next video you open, since the controller is built once when the player starts rather than rebuilt mid-playback.

**Import a list of titles from JSON** (Collections → a folder → Import a list)

A list that lives outside an extension — a Nuvio/Stremio export, a `catalog` response copied out of a browser, a JSON file someone shared — now becomes a normal Home shelf. Paste it or open a `.json` file; the parser accepts a bare array of titles, an object with `items`/`metas`/`titles` inside, a Stremio addon catalog, or a manifest/export with several named `catalogs`, and it reads the common spelling of every field (id, imdb_id, tmdb_id, name, title, poster/image/cover/poster_path, background/backdrop, year/releaseInfo/first_air_date, description/overview/plot, genres, imdbRating/vote_average). Bare `/poster.jpg` paths are expanded against TMDB's image host, and `tt…` ids are kept as they are.

- You choose what comes in: the sheet lists every list it found, with its name and how many titles it actually yielded, and adds nothing until you tap.
- Imported titles are stored **inside the collection** (there is no server behind them), each list with its own stable id so renaming or extending one never remounts its row.
- They resolve through TMDB like any other title, so they get real posters, real details and the same server search — and a list that carries no poster still opens and plays.

**An anime's detail page shows its characters, not its voice actors**

TMDB's credit list for an anime is the voice cast — a wall of faces and names that mean nothing unless you follow the Japanese dubbing industry. The cast row already knows the character each actor plays, but it leads with the actor. So for an animated title whose original language is Japanese (or which TMDB marks as from Japan), the characters are fetched from AniList instead: every character with its portrait on top and the Japanese actor who voices it underneath, sorted by relevance. The row's heading switches from "Cast" to **Characters** so it is obvious which list is on screen, and tapping a character searches their **actor** (or their name when no actor is listed), which is the thing that actually searches well.

- **Only a confident match is used.** The title and the year both have to agree with the search hit (the year within two, since a season can start either side of its show). A hit that cannot be confirmed — including a title AniList simply spells differently — returns nothing, and the row quietly keeps TMDB's voice cast. A wrong show's characters would be worse than the right cast.
- **Decoration only, like the rest of the background metadata:** it runs off the main thread, goes out on the quiet HTTP client so AniList can never raise the "verification needed" banner, and a failure contributes nothing.
- **Cached** in memory and on disk — a hit for a month, a miss retried after six hours, the file bounded at 200 entries — so a show's characters are fetched once and then instant from any extension that opens it.
- Western animation is left alone: TMDB already lists its voice cast with the character each actor plays, so those rows are correct as they are.

## 0.5.26

Test build — full subtitle styling. The captions are no longer just a size and a nudge: text colour, outline or drop shadow with its own colour, a background you can remove entirely, bold/italic, and any font — including a .ttf/.otf from your own device. All of it is picked in a new **Subtitles → Caption style** panel, and every change lands on the video immediately.

- **Text colour** — a real HSV picker (saturation/value square, hue strip, one-tap presets) rather than a colour-wheel stub, with a live caption preview drawn exactly the way the renderer will draw it. The preview shows the outline too, so a colour is judged against video, not against a swatch.
- **Outline / shadow / plain** — the edge type plus its colour. (media3 strokes an outline at a fixed width, so there is deliberately no width slider that would do nothing.)
- **Background** — a colour picker *with* transparency and a one-tap **Remove**, so the black caption box can be dropped entirely.
- **Bold / Italic** and **Font**: the system families (sans, serif, monospace, casual, cursive) or a font file from your phone. A picked file is copied into the app's own storage and validated, so it survives a restart and a file that gets moved afterwards degrades to the default font instead of breaking playback.
- **Presets** — Classic (white on a black outline), Cinema (yellow with a shadow), Boxed (white on a translucent block), Mono (monospaced and bold) — one tap each.
- **Stream default** keeps the old behaviour exactly: the subtitle file's own styling is honoured until you switch to Custom. Custom styling off also means every caption that ships its own fonts/colours still looks the way its author intended.

The **Background** row's picker is the app's own drawn panel, matching the player's dark glass dialogs. Every setting is stored per device, next to the existing size, sync and position rows, and they all still apply to any subtitle from any server.



Test build — the search now finishes the job: it keeps asking every installed extension in the background while you watch, it only plays what you actually asked for, an Aniyomi extension's episodes and servers finally show up, and the player stops repeating itself.

**The search no longer stops at "13 of 181"**

A pass has a time budget, and a big install does not fit inside it — with 181 Hikari, 57 CloudStream and a dozen Aniyomi extensions against 96 search slots, one pass could only reach a handful of each before the clock ran out, and everything it never got to was cancelled and written off as "still searching when the pass ended". So the search looked like it had given up at 13 of 181 while 168 repos were never asked at all.

Now, when a pass runs out of time, the extensions it never reached are handed to a sweep that keeps working **in the background**: after the pass returns, after the player opens, while the video plays. Every server it finds is pushed straight to the player's "Select server" list, exactly as if it had arrived in time — so the list keeps growing during playback instead of freezing at whatever answered first.

- Repos that could not be *asked* in the pass — a search that timed out, a plugin whose load had to wait behind the loader's slots — are retried in the sweep. Their plugin is loaded and cached by now, so the retry is both cheap and likely to succeed. A repo that genuinely answered "no such title" is not re-asked.
- The sweep is per title+episode: tapping Play again joins the sweep already running instead of starting a second one against the same 250 repos.
- Its own ceiling is ten minutes, and it takes slots from the same concurrency caps as the pass, so it can never starve the pass still running for another title or hammer the phone.
- **The player is never told the search is over while the sweep is still working.** The one verdict that must not be given early — "No playable server found after searching N extensions" — is now withheld for as long as a sweep is alive; the player's cover says the search is still running instead, and switches to the honest verdict the moment the sweep really finishes (with every server it found already in the list). This is the other half of "it stopped at 13 of 181": before, the app announced a verdict the search had not reached and the player quit on it.

**Only the title you asked for plays**

Asking for *Renegade Immortal* episode 148 sometimes started a completely different film from another repo. The old match accepted a repo entry when a similarity score cleared a threshold, and two unrelated shows that share one word — "Renegade **Immortal**" and "**Immortal** Samsara" — could clear it. The score is still used to *order* a repo's search page, but a repo's entry now has to pass a strict, structural test before it is trusted:

- a **movie** entry can never be the answer for an **episode** of a series (a film has no episode 148), nor a series entry for a movie;
- one title's significant words must **contain** the other's — so "renegade immortal" matches "Renegade Immortal (Xian Ni)" and "Renegade Immortal Season 1", but never "Immortal Samsara";
- the **first** significant word must survive, so "One Piece" cannot match "Piece of Cake";
- when both years are known and differ by more than one, only an exact match is accepted.

Deliberately strict: wrongly rejecting a repo only costs one missing server, while wrongly accepting one plays the wrong video. This applies to the cross-extension pass only — the extension you opened the title from is never filtered.

**An extension that names its episodes its own way is still found**

Some extensions carry the exact episode but label the row by their own counter, or call it "Ep 148" while their episode number is something else. The episode matcher now reads the number out of the row's own name as a last resort — "Ep 148", "Episode 148", "E148", "第148集", or a row that ends in a bare number — so an extension that plainly has episode 148 is no longer reported as "has the title, but not S1E148".

**Aniyomi: no episode list, no servers — both fixed**

Two separate bugs. An Aniyomi source that needs its details fetched before it can list episodes returned an empty list, and an empty list means no server lookup is even attempted — so the extension contributed nothing. The episode list is now fetched through every call shape the source API offers (the combined call, the plain `getEpisodeList`, and the combined call with details), and a source whose list only works after its details are loaded gets that second chance too.

The second bug was in the app's own fallback: when an item's own extension could not list episodes, the app borrowed the list from an installed extension — but only for TMDB/Nuvio items, never for an item opened from a site-scraper. That is why some Aniyomi titles showed "Episodes (0) — No episode list available" while a dozen installed extensions carried the show. **Every** series whose own list comes back empty now gets that fallback, and it is much better at it: candidates run in parallel, ordered by the origin's engine family and by extensions that have already produced servers this session; each gets the real per-provider budgets (an Aniyomi APK is allowed the same 75s the cross pass gives it) instead of a flat 12s a cold class load could never meet; and the match is the same strict title test, so an extension carrying a different show can never donate its episode list to this one.

**One extension is one row — again**

Installing an extension and seeing the same provider name six times is the same problem as before, one layer further in: several sources inside one extension report the same *language* as well as the same name, so the previous disambiguation had nothing left to tell them apart. A repeated name now falls back to the source's site host, and only then to a ` (n)` counter — and a suffix the name already contains is never added twice.

Installing the same extension *again* was a second, separate cause: the new rows were added one at a time and the old ones were left behind, so a version that publishes fewer sources (or a plain second Install tap) grew the provider list instead of replacing it. An install now replaces every row that package owned in a single write, and it keeps your own on/off choice for each row it replaces.

**The player stops repeating itself**

- The episode line read "Episode 158 · Episode 158" whenever an extension names its rows after their number ("Episode 158", "Ep 158", "第158集"). A name that is only an episode tag is dropped from the label; a real title ("Freedom Day") still shows. The same fix covers the loading card and the episode picker.
- Two different links from the same engine often arrive with the same label ("DahmerMovies 1080p"), so the server list looked like one server listed twice. A repeated name now gets its host appended — or, when even the hosts match, its position among them — so every row is distinguishable.

## 0.5.24

Test build — Aniyomi fixes, a server list that no longer changes its mind, and drag-to-reorder in the Personal Catalog creator.

**Aniyomi: one extension is one row again (unless it really is several)**

Installing a single Aniyomi extension put several identical rows in the extension picker — one extension whose sources all report the same name read exactly like the same extension installed half a dozen times. Two things were wrong. A source list that repeats the same source (an extension that appends to a shared list, or a class named twice in its metadata) was kept in full instead of collapsing to the one source it is. And an extension that legitimately bundles several sources under one name had no way to tell them apart, so every row read the same. Repeated sources are now dropped, and sources that share a name get Aniyomi's own ` (n)` suffix — the same shape the official repo already uses for "Jellyfin (1)…(3)". Existing installs are rebuilt to match on the next app start.

**Aniyomi: its episodes and servers actually show up**

Selecting a donghua from an Aniyomi extension and pressing play listed no Aniyomi server, even though the extension plainly had it. The extension was being cut off before it answered: an Aniyomi extension is an APK, so the first call into one pays a cold class load on top of the site's own latency, and the shared 12–45s budgets — sized for a plugin manifest — expired mid-answer. A second, worse bug hid the episode list entirely: every item out of an Aniyomi catalogue was typed "unknown", and the app returns NO episodes for an unknown item, so the episode grid stayed empty and the title played as though it were a film. Aniyomi sources are now correctly typed as series (a film is an anime with one episode), and their search / meta / episode / stream budgets are their own, wider ones — the extension the user is playing FROM is searched first, as it should be.

**The server list no longer changes every attempt**

Playing the same episode repeatedly gave a different server list each time — nuvio only, then two hikari plus nuvio, then fewer nuvio and no hikari, then cloudstream plus everything. The cross-extension pass is a fresh, time-bounded sweep of 250+ repos, so whichever extensions happened to answer inside the budget decided the list, and the report could even say the search was "done" when a fifth of the repos were never reached. Four things fixed:

- Servers a title produced in a recent lookup are now MERGED into the next one instead of only being used when the new pass comes back empty, so a repeat lookup can never show fewer servers than the one before it.
- A repo that answered with an empty page is only remembered as "no such title" when it had nothing else to say. A page that parsed to zero items because the site answered with a challenge or an error page was being cached as "this repo does not carry the show" for five minutes — which is exactly how a repo that does carry it dropped out of the next attempt.
- Repos answered from that session's own record now count as asked, so the progress line stops reading "asked 1 of 11 … done" while the remaining ten WERE consulted.
- Searching and extraction run wider (96 searches and 20 extractors at once instead of 64 and 12, 32 episode fetches instead of 16) and the whole pass has a longer ceiling, so the repos at the back of the queue actually get their turn instead of being cut off by the clock.

**Personal Catalog creator: hold a catalog and drag it where you want**

Moving one catalog from the bottom of a thirty- or fifty-catalog folder to the top was thirty taps of the up chevron. Now you can hold any catalog for a moment instead: it wobbles, the phone ticks and the row lifts, so it is obvious it is in your hand — then drag it up or down and the rows move out of the way as you pass them. Let go and it drops there. Holding it near the top or bottom edge of the page scrolls the list to follow you, so a long folder can be reordered in one go. The up/down chevrons stay for a precise one-step nudge.

**Fixed**

- A CloudStream plugin that asks for CloudStream's own `MainActivity` (the CineStream plugin does it from its settings dialog) crashed the app on the spot: the class does not exist in Hikari, and the failure escaped through the plugin's own callback on the main thread. It is now shadowed like the other CloudStream classes Hikari stands in for — the request opens Hikari's own main screen instead of killing the process.

## 0.5.23

Test build — Aniyomi `.apk` extensions, and adding several catalogs at once in the Personal Catalog creator.

**New — Aniyomi `.apk` extensions**

Hikari can now install and run Aniyomi extensions: the `.apk` extension format of the Aniyomi / Mihon anime ecosystem, which is to anime roughly what `.cs3` is to CloudStream. One extension is a small APK holding one or more anime sources, and Hikari loads it in-process through the Aniyomi source API it was built against.

- **Install** three ways, from the Extensions tab: **Add Aniyomi repo** (paste a repo's `index.min.json`), **Install Aniyomi extension (.apk)** from a URL, or from a `.apk` you already downloaded on the phone. The official Aniyomi extensions repo is offered out of the box, so a working anime source is one tap away.
- **Browse:** every anime source becomes a provider with its own Home catalogs (Popular and Latest), its own icon, and its own entry in the extension picker — exactly like a `.cs3` or `.sky` provider. Installed extensions can be enabled, disabled, updated and removed like any other.
- **Search, detail and episodes:** a source answers search, and a title's detail page lists its episodes. An episode's video servers are resolved through the extension's own hoster list and then played by the same player as everything else — headers, subtitles, source switching and downloads included.
- **Cross-extension lookup** works for Aniyomi sources as it does for every other kind, so a title found on an `.hiki` or `.cs3` catalog can still be played through an Aniyomi source and vice versa.
- Hikari ships the small piece of the Aniyomi API an extension needs (`eu.kanade.tachiyomi.*`) instead of loading Aniyomi itself, so an extension stays a self-contained file. An extension built for a much newer Aniyomi API version may refuse to load — when that happens its row says so rather than failing silently.

**Fixed — Personal Catalog creator: add several catalogs in one go**

Adding a catalog to a folder used to close the picker after the first tap, so building a folder out of Netflix, HBO and Prime Video meant re-opening the TMDB sheet for each one. The picker now stays open: tap Netflix, then HBO, then Prime Video — each lands in the folder and shows a tick, and tapping a ticked row takes it back out. The same goes for a folder's extension catalogs: choose an extension once and tick every catalog you want from it in a single visit. A **Done** row closes the sheet when you are finished.

## 0.5.22

Hikari 0.5.22 — the first main release since 0.5.17. This build gathers everything the test builds 0.5.18 to 0.5.22 changed: it is mostly one big UI tweak pass over the whole app, plus the catalog mover, plus a few known bugs fixed. Nothing from those builds is left out.

**UI tweaks**
- The app has one corner radius now. Every box inside a card — buttons, chips, picker rows, form fields, cover previews, catalog rows, sub-folder rows, the new up/down chevrons — is rounded at the card's own 26.dp, so nothing sits half-rounded beside something fully rounded any more. Before this the inner boxes were a mix of 4, 10, 12, 13, 14, 16, 18, 20 and 24.dp, which is exactly what made a card look half-rounded: the same card read "fully rounded" or "barely rounded" depending on what happened to be in it, and a sub-folder row never matched the card it sat in. A short box therefore comes out a capsule and a tall one keeps the card's curve.
- Every "pick one of several" setting works the same way: one row showing what is in use, and tapping it opens a scrollable panel of choices with the current one ticked — the app language, App font, Title language (TMDB), DNS mode, Video enhance preset, navigation-bar layout and Theme. It used to be a wall of radio buttons inside the card for the long ones (DNS mode was eleven rows, the TMDB language list over thirty), which pushed everything else off the page.
- Settings folders open folded: a card holding more than one control shows its name and what it is set to ("Accent colour · Violet", "Taskbar buttons · 6 / 7 buttons · labels on") and unfolds when tapped, so a folder page is a short list of headings instead of a wall of switches. Leaving a folder folds it again, so a page never reopens half-open.
- Page headings are one line: the back button, the page's icon, then its name with the one-line summary under it. No settings page looks louder than another's, and a name is never wrapped — it is drawn at one size for every page and ellipsised.
- The sentence that used to sit under every heading is gone. Each folder — Appearance & Theme, Player, Network, Sources, Downloads, Personal Catalog creator, Privacy, Logs, Backup, About, and the sub-folders like Accent colour and App font — carried a second line repeating what its name already said. The heading is now just the name with its one-line summary.
- The explanations were cut right down — one line at most, and none at all for options whose own name says it. DNS mode lost its paragraph, and the toggles lost the captions that only repeated their own label.
- Appearance is now Appearance & Theme, and the folders were re-sorted: App font, Title language (TMDB) and the ratings strip moved to the folders they belong to, and the accent page is now just "Accent colour".
- Player controls: the preview opens showing the real button icons (it used to open as words), and the schematic sits on a stand-in video frame instead of a flat black panel, so the layout reads at a glance.

**New — the catalog mover**
- Catalogs and folders can be put in any order you want. In the Personal Catalog creator, every catalog row inside a folder ("Netflix", "HBO", "Pixar") and every folder row inside a collection now carries a small up/down pair: move HBO above Netflix, or move an Amazon Prime you just added up to where you want it instead of leaving it at the bottom. The order you leave the rows in is the order Home shows — the catalogs inside a folder, and the folders inside a collection. A new catalog still lands at the end of the list, and the chevron at either end fades out when there is nowhere further to go.

**Fixed**
- Fixed some known bugs found by actually using the test builds. Nothing sits trapped behind the floating bottom bar any more: "Add repo" / "Add Stremio addon" on the extensions screen were under the bar and could not be tapped, and so were the last rows of the Logs & diagnostics and Player controls pages. Every page that shows the bar now keeps its last row clear of it, and the choice panels inside them scroll clear too.
- The same catalog added to a folder twice now counts once, both in the editor's list and in the row it builds — it used to be able to list the same catalog twice and fetch it twice over.
- The player-control editor's last rows and the Logs page's last rows are reachable again, and no page's header wraps onto a second line with its icon beside it ("Personal Catalog creator" was the one that did).

## 0.5.21

Test build — one fix on the settings pages.

- The sentence under every settings heading is gone. Each folder — Appearance & Theme, Player, Network, Sources, Downloads, Personal Catalog creator, Privacy, Logs, Backup, About, and the sub-folders like Accent colour and App font — carried a second line under its title ("How Hikari looks and speaks on this phone."), which only repeated what the name and its summary already said. A page's heading is now just the name with its one-line summary under it. The Logs & diagnostics page and the player-control editor lost the same line, since they share the header.

## 0.5.20

Test build — two fixes on the settings pages.

- Folder headers are one line again. The badge sat above the name; now the back button, the folder's icon, the name and its summary share a single row, so a page starts with the same "icon, then name" shape as the row that opened it. Every settings page's name is drawn at the same size — folder pages, Logs & diagnostics and the player-control editor — so no page reads louder than another, and a name is never allowed to wrap: it gets the whole line after the icon and uses the size that fits them all.
- The boxes inside a folder all have the same rounding. Outlined buttons ("Edit control layout", "Import a font from storage", the ad-block presets, the accent copy buttons) were drawn as full capsules next to the softly-rounded cards; they now use the same corner radius family as everything else, and a sub-folder's row matches too. The outer setting box is unchanged.

## 0.5.19

Test build — another polish pass, this time over the Settings pages and the player-control editor.

- Settings folders now open folded. A card that holds more than one control shows its name and what it is set to — "Accent colour · Violet", "Taskbar buttons · 6 / 7 buttons · labels on" — and unfolds when you tap it, so a folder page is a short list of headings instead of a wall of switches. Leaving a folder folds everything again, so a page never reopens half-open.
- Every folder page's header now puts the folder name on its own line, across the full width. "Personal Catalog creator" used to wrap onto a second line with its icon beside it; no folder name wraps now.
- Appearance is now "Appearance & Theme", and the folders were re-sorted: App font, Title language (TMDB) and the ratings strip moved to the folders they belong to, and the accent page is now just "Accent colour".
- The explanations were cut right down — one line at most, and none at all for options whose name already says it. DNS mode lost its paragraph, and the toggles lost the captions that were only repeating their own label.
- Player controls: the preview opens showing the real button icons (it used to open as words), and the schematic sits on a stand-in video frame instead of a flat black panel, so the layout reads at a glance.

## 0.5.18

Test build — everything here is a polish pass on what 0.5.17 shipped.

- Every "pick one of several" setting in the app now works the same way: one row showing what is in use, and tapping it opens a scrollable panel of choices with the current one ticked. That is the app language, App font, Title language (TMDB), DNS mode, Video enhance preset, Navigation bar layout and Theme. It used to be a wall of radio buttons inside the card for the long ones (DNS mode was eleven rows, the TMDB language list over thirty), which pushed everything else off the page.
- Nothing is trapped behind the floating bottom bar any more. "Add repo" / "Add Stremio addon" on the extensions screen sat under the bar and could not be tapped; the same for the last rows on the Logs & diagnostics and Player controls pages. Every page that shows the bar now keeps its last row clear of it, and the choice panels inside them scroll clear too.

## 0.5.17

The last release you got was 0.5.6 — everything below has landed since then. The builds in between were test builds, so every fix here comes out of actually using them.

New:
- TMDB titles in your language. Movie and series titles and overviews are fetched from TMDB in the app's language, or in any single language you pick (Settings → App Layout → Title language), with a switch to keep TMDB's original titles. Changing it applies straight away, no restart.
- The personal catalog creator builds a row from anywhere on TMDB: presets, public lists, studios, networks, collections, people, directors, or a custom genre/year/sort query — by name, by TMDB id, or by pasting a themoviedb.org link — each with your own display name.
- Covers for collections and folders: an emoji, an image link, an animated GIF (it plays on the tile), or an image from your own storage, in a poster, square or wide tile.
- Library categories. Movies, Series, Action and Romance are built in, and you can add, rename and delete your own. "Add to library" asks which category a title goes in, saved titles can be moved between them, and the categories show as filter pills above the grid.
- App font: system default, sans, light/medium/black, condensed, serif, monospace, casual, cursive or small caps — or import a .ttf/.otf from your storage. It applies to the whole app, the player and the browser.
- Poster & icon styling: an iOS-style blur that lifts posters off the page, corner rounding from square to very round, and switches for titles and score badges on artwork.
- Settings → Network and Internet, with DNS mode: Automatic, Cloudflare, Google, AdGuard, Quad9, DNS.SB, Mullvad, Canadian Shield, CleanBrowsing, DNS.WATCH or your own DNS-over-HTTPS address, with a Test button. It covers searches, repo and extension downloads, stream probes and playback.
- The bottom bar is yours to shape (Settings → App Layout → Taskbar & navigation): Floating animation (the default — it draws itself in as you scroll up), Floating, or Classic; button names on or off; and which buttons it shows.
- "Show ratings" switch, for the review strip on a detail page and the score badge on posters.
- "Turn off full screen app mode", keeping the phone's status bar and its buttons visible everywhere.
- Settings is two levels deep — Appearance and App Layout — each page with a breadcrumb.

Fixed:
- The crash that could take the app down while browsing when an extension repeated itself: the same title twice, two catalogs sharing an id, the same repo added twice. Repeats are handled everywhere now, and a repo installed twice is only loaded once.
- Play no longer gives up while the search is still running. "No playable server found" is only said once a search has really finished, Retry rejoins the search that is already running instead of starting over, and tapping Play twice no longer sets two searches going against each other. The card now also names the extensions that never answered in time.
- A title you just played starts instantly on the server that worked, while fresh servers are looked for behind it — and Play searches every time instead of reusing a remembered "no source".
- Scores on posters: the badge shows on every poster now (it falls back to TMDB's average when no review score resolves), on search results and catalog "Show all" grids, and on the Related and Similar rows of a detail page — and far fewer posters come up blank.
- The taskbar is a proper capsule: it rests at the height its length asks for, with real room above and below the icons, and its names are never clipped at any app font or text size.
- The black band under the bottom bar is gone in all three layouts. The bar floats over the page, is glass rather than a slab, and stays readable over a bright poster.
- The Home provider pill can be tapped again (it sat underneath the taskbar), and it is readable on the Dark Glass UI theme — the same fix covers the extension catalog button and Library's chips.
- Poster titles are whole again: the rounded corners no longer shave the first and last letter off.
- A folder with no cover of its own shows its collection's cover.
- "Add to library" no longer cuts its own buttons off below the fold, and the extension picker is a clean minimal list.

## 0.5.16

Fixed:
- The crash some people hit while browsing ("Key \"…\" was already used"). Compose refuses to draw two items in the same list that share a key — it throws, and the screen goes down with it. Every key in Hikari is built out of data an extension handed us, and extensions repeat themselves: the same title twice in a scraped page, two catalogs sharing an id, the same repo installed twice, the same source added to a folder twice. Those repeats are now handled in Home, a catalog's "Show all" grid, search results, a detail page's shelf, the Library grid, a folder's catalogs and the extension and picker lists — a repeat in the data costs the item its own slot at worst, and can no longer take a screen (or the app) down with it.
- The same repo installed twice is only loaded once. The provider list could hold duplicates, so every catalog of that repo was fetched twice and produced two rows with the same key — which was one of the ways the crash above was reached.

## 0.5.15

Fixed:
- The Home provider pill can be tapped again. It sits in the bottom-right corner — exactly where the taskbar floats — so once the bar started drawing over the page instead of in a strip of its own, the pill ended up underneath the bar's own buttons and a tap on it landed on Home or Settings instead. It lifts itself clear of the bar now, and stays put when the gesture bar is showing.
- The taskbar can be read over artwork. The floating bar was a white whisper of a panel, so a bright poster decided whether the icons and their names were legible — over a pale poster they all but vanished. It is the theme's own surface at nearly full strength now (a dark frosted panel on the dark themes, a light one on the light theme) with the hairline edge and soft top-light every other glass panel in the app carries, so it is still glass with the artwork faintly showing through it, and the buttons are readable whatever they are floating over.

Changed:
- The floating bars are small pills, not near-full-width bands: shorter, round-ended, with real room around them on every side, so they float over the artwork instead of sitting in a band across the page.
- The drawn-in size of "Floating animation" is a real step down again — and this time it is not a near-copy of the bar it grew out of. It pulls its ends in hard, drops a size and lays the button names aside, leaving a compact icon capsule with air either side of it: still the same round-ended pill with the same icons, so it is plainly the same bar, but the scroll animation is something you can see.

## 0.5.14

Fixed:
- The score badge now shows on the Related and Similar rows of a detail page. Those two rows were the only posters in Hikari that hand-drew their own artwork and therefore ignored "Show ratings" entirely — they draw through the same poster component as every other grid now (the search icon still sits in their top-right corner, with the score in the top-left).
- Fewer posters come up without a score. Four causes: the TMDB average a poster's badge falls back to was only published after all five review sites had answered, so the slowest site decided when a whole row's scores appeared (it is published the moment it lands now, and the IMDb/tomato numbers join it as they arrive); a title whose first lookup answered nothing was remembered as scoreless for the rest of the session and never asked again (it is re-asked on the same 15-minute / 3-hour schedule the detail page's refresh uses); a race in the repaint-on-arrival path could hand a poster a state object nobody ever updated, leaving the corner blank; and a crowded screen dropped the titles it could not look up at once — only the first hundred-odd a screen asked about were ever looked up and the rest were forgotten for the rest of the session, which is what left some posters in a row badged and their neighbours bare (a title's place in the queue is released as soon as its badge is up now, before the review sites are asked, and a poster that is turned away asks again instead of staying blank). Posters that genuinely have no score yet — an unreleased title, or one with fewer than 10 votes on TMDB — still show no badge, as TMDB itself does.
- "Show ratings" now draws on the Search results grid and a catalog's "Show all" grid as well. They were missing the badge too, though Settings → Appearance describes the switch as covering posters.

## 0.5.13

Fixed:
- The black band at the bottom of the screen is gone, in all three taskbar layouts. The bar used to be given a strip of the screen to live in, so the page stopped short of it and that strip was painted in the page's own background colour — on the dark and AMOLED themes a black band with the bar sitting inside it. The bar floats over the page now: content scrolls behind it, and the glass finally has something to be glass over.
- The floating bar's shrunk pill is a little wider, so it reads as the same bar drawn in rather than a narrow stub.

New:
- Settings → Network and Internet, a folder of its own for everything about how the app reaches the internet. "Mobile data / slow internet" moved there from Player.
- DNS mode, in that folder. Pick the resolver Hikari uses — Automatic (this phone), Cloudflare, Google, AdGuard, Quad9, DNS.SB, Mullvad, Canadian Shield, CleanBrowsing (family, adult-content filter) or DNS.WATCH — or type any DNS-over-HTTPS address of your own. The choice applies to the whole app: source searches, extension and repo downloads, stream probes and playback all look names up through it. The public resolvers that offer an encrypted service are asked over DNS-over-HTTPS and carry their own server addresses, so picking one still works on a connection whose own DNS is refusing to answer; DNS.WATCH, which never added an encrypted service, is asked over plain port-53 DNS and says so on its row. A "Test" button asks the chosen resolver for example.com and tells you what came back, and "Automatic" leaves the old behaviour untouched — the phone's resolver first, with Hikari's encrypted fallback behind it.

## 0.5.12

Fixed:
- Poster titles are whole again. The poster's rounded corners were wrapping the title as well as the artwork, so with a big corner rounding the first and last letter of every title were shaved off ("Coyote vs. Acme" printed as "oyote vs. Acme"). The rounded shape clips the artwork only now — titles are drawn whole, and the same mistake on the Library grid, the catalog grid and a collection's poster grid is gone with it.
- Every taskbar name fits, at any app font and any text size. The labels are measured with the app's own font and its letter spacing before their size is chosen ("Downloads" and "Extensions" were still running into each other on wider fonts). A taskbar button's icon and name also always get the bar's full inner height, so nothing is squeezed inside the bar.
- The floating bar is glass, not a slab. It was painted from a nearly-opaque panel colour, which on the dark themes made the bar and the black page around it the same colour — the bar's area read as a black box with the buttons floating in it. It is a translucent panel with a hairline edge now, and it hugs its buttons instead of carrying empty space around them.

Changed:
- The floating bar's two sizes are much closer together: a medium labelled pill that grows a little as you scroll down and settles back as you scroll up, instead of swelling to the full bar and collapsing to an icon-only sliver. It keeps its labels in both states.

## 0.5.11

New:
- "Show text on taskbar buttons" in Settings → App Layout → Taskbar & navigation. Turn it off and the bottom bar is icons only — no Home, Library, Downloads written under them.
- Floating animation is the bar layout Hikari starts with now. Its resting size is the full labelled bar, and it shrinks into a smaller icon-only pill as you scroll back up towards the top of a page; scrolling down brings the full bar back. Switching tabs always brings it back.

Fixed:
- Changing the language TMDB titles are fetched in takes effect straight away. It used to need a restart: the Home feed, a saved TMDB source's row title and the search grid were all remembered from the old language and nothing re-fetched them. Picking a new language now throws those away and rebuilds them on the spot.
- The last word of the taskbar labels is no longer cut off. "Downloads" and "Extensions" were wider than the slot they sit in and were clipped on both sides — the size of each label is now measured against the width it actually has, so every label fits with a gap either side.
- The score badge shows on every poster, not just some. The badge only had the IMDb number to print and gave up when nothing could resolve one for a title; it now falls back to TMDB's own average, and posters wait their turn instead of being skipped when a lot of them come on screen at once.
- The "All providers" button on Home is readable on the Dark Glass UI theme again. Its background was built from a nearly-transparent white overlay, which made it a bright slab under white text; it is a proper glass panel now, and the same mistake was behind the washed-out Extension catalog button and Library's chips on that theme, so those are fixed too.
- A folder shows the collection's cover when it has no cover of its own. Picking an image for a collection used to leave every folder inside it drawn as the default folder icon.

## 0.5.10

New:
- Settings is two levels deep now. Appearance keeps what the app *is* — app language, theme, interface size — and App icon and App color theme became their own pages under it; everything about how a page is laid out (TMDB title language, poster styling, app font, taskbar & navigation) moved into a new App Layout folder, each on its own page. Both open pages show a breadcrumb, so you always know where you are and Back always goes up one level.
- "Show ratings" in Settings → Appearance: one switch for the IMDb / Rotten Tomatoes strip on the detail page and the small score badge on posters. Turning it off also stops the lookups.
- "Turn off full screen app mode" in Settings → App Layout: keeps the phone's status bar and its three buttons visible everywhere, instead of Hikari hiding them. The player's own fullscreen video is left alone.
- Collections and folders take a cover now: an emoji, an image link, an animated GIF link, or an image picked from your own storage — plus the shape of the tile, Poster, Square or Wide. The New collection / New folder screens ask for it while you create them, both editors let you change it later, and there is a live preview. Covers picked from storage are copied into the app so they survive restarts, and a GIF cover actually plays while its tile is on screen.
- The bottom bar's "Borderless" layout is now "Floating animation": a full bar with labels at the top of a page that shrinks into a small icon-only pill as you scroll down and comes back when you scroll up.

Fixed:
- The poster rating badge shows up now. It needed a score that had not been looked up yet and nothing redrew the poster once the score arrived, so the switch looked like it did nothing. Scores are fetched quietly in the background as posters come on screen, and each poster repaints the moment its own score lands.
- "Add to library" no longer hides its buttons below the fold. The sheet opened at half height, which cut off "Create a new category" and the Add to library button; it opens at full height now, and the category list no longer pushes them down.
- The extension picker on Home is the minimal list: a plain search field, chips with the selected one filled in, COLLECTIONS and PROVIDERS headings, and plain rows with thin dividers instead of a card per extension. The selected extension is tinted and carries a filled tick. The extension picker inside a folder looks the same now.
- Nav style CLASSIC is no longer identical to Borderless: Classic is a solid plate with a hairline along its top edge.

## 0.5.9

New:
- Build your own catalog sources the way Nuvio does. Settings → Personal Catalog creator → a folder → "+ TMDB" now opens the full source picker: Presets, Public list, Production, Network, Collection, Person, Director and Custom. Type a name, a TMDB id or paste a themoviedb.org link and Hikari finds it, so Marvel Studios, Netflix, a Star Wars collection, Tom Hanks or "everything directed by Christopher Nolan" is a row in your folder. Custom sources also take genre, year and sort order, and every source can be given your own display name.
- App language now changes the titles themselves. TMDB titles and overviews are fetched in the language the app is set to, so switching to Spanish gives you Spanish movie and series names everywhere — search, Home, your catalogs, the detail page. Settings → Appearance → Title language (TMDB) has a switch to keep TMDB's original titles, or pick any single language regardless of the app's.
- App font. Settings → Appearance → App font picks from System default, Sans, Sans Light/Medium/Black, Condensed, Serif, Monospace, Casual, Cursive and Small Caps, and it applies to the whole app — every screen, the player and the built-in browser. You can also import a font file from your own storage (.ttf/.otf); it is copied into the app, so it keeps working after the file picker has signed off.
- Library categories. Your Library now has Movies, Series, Action and Romance wired in, plus as many of your own as you like. "Add to library" asks which categories a title belongs in (the right one is already ticked), an already-saved title has a "Move to" that shows every category, and a new category can be invented straight from either sheet. Categories appear as filter pills above the grid, and you can rename or delete them from the header. A title can sit in as many categories as you want.
- Poster & icon styling. Settings → Appearance → Poster & icon styling: a dynamic iOS-style blur that lifts posters off the page, corner rounding from square to very round, and switches for showing titles and rating badges on artwork. It applies live everywhere — Home, Search, Library, collections.
- Flexible bottom bar. Settings → Appearance → Navigation bar: Floating (the rounded glass pill), Classic (edge-to-edge and seamless) or Borderless (no plate at all, just the icons over the page). Which buttons it shows is still set above it.

## 0.5.8

Fixed:
- Play no longer gives up while the search is still running. Hikari used to call a search that had not finished "no playable server found" about ten seconds in — the servers it was still finding a moment later were thrown away and you had to tap Play again, sometimes several times. Now only a search that really finished can report that, and one that got cut off is simply tried again.
- A Retry now rejoins the search that is already running for that title instead of starting all over again, so retrying is quick instead of another full sweep.
- Tapping Play twice no longer makes two searches fight each other. Each search keeps its own count, so the "asked N · M no such title" line under the sheet is that search's own numbers instead of two searches mixed together.
- The "no playable server found" card now includes the extensions that never answered in time, so it no longer reads as if every extension was asked and said no.

## 0.5.7

Fixed:
- Tapping Play again no longer answers "no playable source" without searching. A search that found nothing used to be remembered for a few minutes, so the next taps skipped the search and failed instantly. Every tap searches now.
- A video you just played starts immediately the next time you open it — on the server that worked — while the search for fresh servers runs behind it.

## 0.5.6

First release since 0.3.71, so a lot of this is new to you — the builds in between were test builds and never went out.

New:
- SkyStream `.sky` extensions. Install them from a repo, a link or a file, and use them like any other source: Home rows, search, episodes, servers, downloads. If one hands back an embed link, Hikari resolves it itself.
- A new look: rounded frosted glass cards, a floating bottom bar, round icon badges, a soft glow behind the top of the screen.
- AMOLED Black theme, true black with the same soft cards.
- Accent colours — eleven of them, and the player can keep its own accent separate from the app's.
- App icon change: Settings → Appearance → App icon, eleven icons, changed while the app runs, and your choice survives updates.
- Taskbar button hide option: Settings → Appearance → Taskbar buttons, turn off Home, Discover, Search, Library, Downloads or Settings one by one. The top bar keeps a gear, a magnifier and the verify globe.
- 20 languages, translated properly through the whole app — menus, Home, search, downloads, toasts, the player. Picked in Settings → Language.
- Personal Catalog creator. Build your own Home shelves out of TMDB lists (Marvel, Pixar, A24, Netflix, HBO, Disney+…) and any catalog from your extensions, grouped into your own folders.
- Ratings on every title: IMDb, Rotten Tomatoes with its popcornmeter, Metacritic, Letterboxd and TMDB, each badge in its own colour. Tap a badge and it explains the number.
- Age rating on every movie and series — PG-13, R, TV-MA — colour-coded: green for all ages, amber for guidance, red for adult.
- Download without playing: a download button on every episode row and one next to Library, and the player's download button now asks which server to use instead of starting the video.
- Add external subtitle: pick your own `.srt`, `.vtt`, `.ass` or `.ttml` from the CC sheet in the player, and it is used straight away.
- Server list grouped by engine, with engine filter chips in Home's provider picker.
- "Ask me when a chosen server fails" — Hikari offers you the next server instead of switching on its own while you watch.
- One Play tap searches every installed extension and keeps adding servers to the player's own list while the video runs.
- Backup & Restore, and restore from CloudStream: back up Hikari into one file (extensions, sources, repos, settings, history, favourites) and load it back on any device. Settings → Backup & Restore → "Coming from CloudStream?" reads a CloudStream backup file and adds every repository in it in one tap.
- A crash from the previous run is shown on Home, with a pointer to the logs.
- Extension verification pages switch (off by default), so an extension cannot open its own verification page over your video. Hikari's globe button still opens one when you tap it.

Fixed:
- The app no longer crashes when an extension shows a message or a login box, or when a WebView page dies.
- Picking a language changes the app right away — before, it stayed English.
- A dead server asks you what to do instead of switching behind your back, and playback starts on the first server that really works.

## 0.5.4

Fixed:
- SkyStream extension catalogs load. The provider treated page numbers as 0-based, so every row asked for page 1 and got an empty list, and every search answered "no matching title" in ~5ms.
- Cloudflare reason only compares against the extension's own site instead of the last challenged host in the whole app.
- Cloudflare detection no longer fires on healthy pages (dropped `turnstile`, `hcaptcha`, `cf-chl`, `access denied`, `request blocked`).
- The globe button opens the selected extension's site; SkyStream sites are read from its plugin.json.
- A missing plugin file or an unanswered search reports that reason instead of "no matching title".
- Cross-extension sweep: search 25s → 45s, sweep 70s → 110s, budget 100s → 140s, concurrency 48 → 64.

## 0.5.3

Fixed:
- Search could get stuck on "finding server": the plugin loader took its slots with `acquireUninterruptibly()` and one stuck load parked all of them. A slot now waits at most 45s and is reported as that extension's failure.
- Slow-connection mode multiplied the search budgets, so "provider budget" became 150s and the sweep 450s. Budgets are clamped now (25s page, 90s provider, 100s sweep, 55s scan).
- The server list no longer stops at "still searching…": the final join is bounded at 90s, the closing call at 80s, and a finished search says "Found N server(s) — search finished."
- SkyStream fetches now go through the Cloudflare interceptor (reuse the clearance, retry with the WebView user-agent). Catalog budget 75s, fetch pool 24.
- Extensions are asked in engine-family order, so SkyStream extensions are not stuck at position ~240 behind 200+ Hikari repos.
- Smaller provider chips, extension picker rows and list rows.

## 0.5.2

Fixed:
- SkyStream catalogs load. The engine's HTTP bridge was synchronous, so a plugin's `Promise.all` of 8 rows took the sum of every round-trip and ran past the 45s call budget. There is an async fetch bridge now; catalog budget 40s → 55s, Home cap 70s → 85s.
- Extensions load side by side instead of one process-wide lock: serialised per plugin file, six at a time.
- A host that needs verification is skipped the moment the challenge title appears instead of waiting out a 60s timeout; the resolver timeout is 30s now.
- Cross-extension search asks 28 → 48 at once and remembers "no such title" for a few minutes.
- Extension icons: listing fields, then the addon manifest logo, then the site favicon; relative and `%exact_size%` URLs handled; SVG icons decode.
- Log writes happen on a background thread instead of the calling (main) thread.
- The extension picker matches the rest of the app.

## 0.5.1

Added:
- One Play tap searches every engine and keeps filling the player's own source sheet while the video runs.

Fixed:
- An empty re-read of the providers no longer wipes the servers the player already has.
- `.sky` extensions install again — the engine's bootstrap returned the global object and QuickJS rejected it. The result is discarded now.
- Extensions can no longer open their own Cloudflare WebView (Cinemacity did it mid-search): the setting reads `false`, is forced to `false`, and the `Context` wrappers answer `false`.
- The ten alternative app icons are re-cut, so none is clipped by the launcher mask.
- Collections moved out of Settings → Appearance into its own folder.

## 0.5.0

Added:
- SkyStream `.sky` extensions: install from a repo, a URL or a file. They run in an embedded QuickJS engine with a bridged `fetch`, so `getHome`, `search`, `load`, `loadStreams` and the plugin's own settings work unmodified.
- SkyStream in Home catalogs, collections, Search, detail page, episodes, the server sheet, cross-extension lookup and Downloads.
- `loadExtractor` resolves embed URLs through Hikari's extractor stack.
- Instant play covers SkyStream.
- App icon picker: 11 icons, switched live, survives updates.
- Taskbar buttons can be turned off one by one.

Fixed:
- A host your DNS refuses loads through a DNS-over-HTTPS fallback, wired into every HTTP client.
- "No playable source found" is no longer triggered by a Cloudflare/withholding page.
- Origin-play grace window 8s → 1.2s.
- A plugin's own settings screen opens: it retries and names the exception when it still fails.
- A one-folder collection shows one shelf per catalog instead of a merged row.

## 0.4.1

Added:
- About 490 strings translated into 20 languages plus the pseudo-locale: Home rows and genres, the search-scope and translate dialogs, install/update/remove statuses, toasts.
- A crash on the previous run is reported on Home as a dialog with a pointer to Settings → Logs.
- The language picker is an in-app dialog, each language in its own script.

Fixed:
- An installed extension stays installed: repos are matched by file identity, not by the URL string they published the day you installed.
- Adding a repo you already have refreshes the entry instead of filing a duplicate under a different URL spelling.
- A plugin settings failure names the exception, plugin class and line, and writes the stack trace to the log.

## 0.4.0

Added:
- Backup & Restore → "Coming from CloudStream?": reads a CloudStream backup file and adds every repository in it.
- Collections: build your own Home shelves from TMDB lists and extension catalogs, one folder per shelf.
- Engine filter chips in Home's provider picker (All / CloudStream / Hikari / Nuvio / Stremio).
- Translations for the new strings.

Fixed:
- The score row refills missing badges in the background on its own schedule instead of caching a title without them for a day. Each source has a 9s ceiling.
- The age rating is a colour-coded pill: green for all-ages, amber for guidance, red for adult.
- Downloading from outside the app no longer closes the player: the chooser tells "a server was picked" apart from "the user backed out".

## 0.3.89

Fixed:
- Black text on dark, AMOLED and glass themes — the navigation shell drew a transparent content surface, so unstyled text fell through to Material's black fallback. The theme sets `onBackground` now.
- Age rating shown for every movie and series, from TMDB's per-region certifications (US first, then GB/AU/CA/IE/NZ).
- Every rating badge is tappable and explains where the number comes from. IMDb always shows one decimal.
- The Rotten Tomatoes tomato is red again; the freshness is carried by the number's colour.
- The Download button asks which server to use first instead of starting playback, and reads quality from the source's HLS master playlist.
- Less stutter: images track only themselves instead of one global revision, and glass tokens are computed once per palette.
- The Settings header is just "Settings".

## 0.3.88

Added:
- Every review score in one coloured row: IMDb, Rotten Tomatoes (+ popcornmeter), Metacritic, Letterboxd and TMDB. No API keys needed.
- "Add external subtitle" in the Subtitles sheet: pick an `.srt`/`.vtt`/`.ass`/`.ttml`, it is checked, listed and selected automatically.
- Download from the detail page: an icon on every episode row and a button next to Library.
- Settings → Backup & Restore: one JSON file of extensions, sources, repos, settings, history and favourites.
- "Ask me when a chosen server fails" (on by default): when a server you tapped dies, Hikari offers Try next / Choose another / Always switch.
- Your own extension's server is searched first, with a bounded 8s head start.

Fixed:
- "Choose another server" re-opens the same list with what is still arriving, instead of running the whole search again.
- New copy translated in all 21 languages.

## 0.3.87

Added:
- "Extension verification pages" switch (Settings → Privacy & Browsing, off by default).
- AMOLED Black theme.
- The rounded glass look everywhere: frosted cards, floating bottom bar, circular icon badges.

Fixed:
- The verification WebView that kept opening by itself: CinemaCity's own code opens it and was gated on a switch Hikari could not write. Plugin settings round-trip through the store the extensions actually read now.
- While the player is locked, brightness and volume swipes are ignored too.

## 0.3.86

Fixed:
- Crash `NoSuchMethodError: getWebViewUserAgent1()` on Cloudflare-fronted extensions — Hikari ships its own `CloudflareKiller` with the same method table, and `WebViewResolver.getWebViewUserAgent1()` exists.
- Nothing opens a WebView by itself; the jar's CloudflareKiller and the "Solve Cloudflare checks automatically" setting are gone. Verification only happens when you tap the globe button.
- Your `cf_clearance` cookie is no longer wiped by `removeAllCookies()`, so verifying now sticks.
- The extension's own Cloudflare text is gone from the server list, diagnostics and the player's hint. A Cloudflare answer no longer hides a whole provider.
- Home explains a verification wall in Hikari's own words, with an Open WebView button.
- The locked player shows a small lock icon in the top-right instead of a padlock over the film; brightness and volume keep working while locked.

## 0.3.85

Fixed:
- Crash `ArrayIndexOutOfBoundsException` thrown out of an extension: callbacks are `CopyOnWriteArrayList`s and calls into one extension are serialised.
- One dead mirror no longer eats the failover: a host answering 500/404/410 is remembered for the session, 5xx no longer spends the three header-variant retries, and playback starts on the first healthy server.
- The source search survives the player opening (it runs on the application scope, not the screen's).
- Cloudflare-gated servers are withheld until the host is verified.
- The language you pick applies on the first tap.
- Settings is fully translated, including five strings whose keys had escaped quotes.
- "System default (English)" instead of an empty bracket.

## 0.3.84

Fixed:
- "Play as soon as the first server is found" really plays on the first server; the remembered last-used server only holds playback for the "wait for more servers" choice, and the head start is a hard deadline.
- A "nothing found" status can no longer fail a session that did get servers.
- A dead first server no longer parks the whole list; the player fails over to the next one and the cover says "Reconnecting — <server>".
- Automatic Cloudflare solving is opt-in and off by default. Cloudflare's challenge hosts are no longer blocked as ads, the element blocker is not injected into a verification page, and a renderer crash recovers at most once.
- The language files actually ship, so picking a language changes the UI. 20 languages, one file each, loaded lazily.

## 0.3.83

Fixed:
- Build only: 0.3.82 never produced an APK. Nested `for` destructuring (rejected by Kotlin) is now an indexed loop, and the `tr()` calls were moved out of the `LazyColumn`.

## 0.3.82

Fixed:
- Crash when a plugin shows a toast or login message: the jar's `databinding/ToastBinding` could not link. Hikari ships its own class and adds the viewbinding runtime.
- WebView GMS crash: the `com.google.android.gms.version` meta-data tag is declared, so the Shape Detection check answers instead of killing the process.
- "Finding the best server…" no longer spins forever: the completion signal is in a `finally`, and the cover shows live progress plus the reason when the search ends empty.

## 0.3.81

Added:
- Popups that are not from a real tap are dropped, and the common popup/interstitial/ad containers and ad iframes are hidden.
- App language in Settings → Appearance: 21 languages plus "System default" (and `mmmm... monke`).

Fixed:
- Repo short names and branch URLs (`…/refs/heads/builds/repo.json`) normalise to the raw form, so those repos import.
- StreamPlay's `NoClassDefFoundError` shows its cause: the recorder unwraps the cause chain and the sync-providers class is pre-warmed.
- The Cloudflare verification WebView no longer opens by itself.

## 0.3.80

Fixed:
- Every row in the server chooser is the same small capsule. A long "Provider (Repo) · Plugin" name wrapped to two lines and made a fatter, rounder pill. The label is one line, ellipsised.

## 0.3.79

Fixed:
- Crash `ForegroundServiceDidNotStartInTimeException`: the background service always steps into the foreground before deciding there is nothing to do.
- The tail of the server list was never asked: the pass ran search and extraction through the same 18 slots. There are two phases now, "Asked N of M" is truthful, and the pass reports what it did not reach.
- A Cloudflare-blocked site says so ("Cloudflare check needed on <host>") instead of "this repo has no such title".
- The verification dialog opens only when you are waiting on that one site, never during a bulk search.
- Cloudflare can no longer starve a search: short budgets, at most two solves at once.

## 0.3.78

Fixed:
- Every repo's answer is truthful: "search timed out after 20s", "search failed: <reason>", "N results, none of them <title>", or an empty page — instead of one "no matching title" for all of them.
- The chooser hint counts the reasons across the pass.
- A failed or timed-out search is retried once; search/episode timeouts 15s → 20s.
- A broken extension can no longer look like "this repo hasn't got the show": it reports "Extension failed to load", "extractor-only (no search)" or a missing plugin.

## 0.3.77

Fixed:
- With "Don't play directly" on, the server sheet opens the instant you tap Play and fills in as servers land, instead of waiting 20-25s for the first one.
- Backing out of an empty chooser no longer kills the play — the sheet re-opens when the first server arrives.
- Your provider is searched first; the other engines wait a 1.2s head start and its own engine family 2s.
- Searches, catalog loads, source scans and installs run on a foreground service, so leaving the app does not abandon them.

## 0.3.76

Fixed:
- The chooser hint leads with numbers ("Asked 48 other repos (CloudStream 48 of 48, Hikari 64) — 12 still searching, 4 with servers") instead of a joined list that got cut off after the first entry.

## 0.3.75

Fixed:
- The cross pass no longer stops at 64 repos. The cap filled the list entirely with native `.hiki` repos, so an installed CloudStream repo was never asked. The cap is 1024 now; concurrency and time bound the work.
- The origin's own engine is asked in full first, then the other engines round-robin one repo per engine per round.
- The log line carries the build version and the installed counts per engine.

## 0.3.74

Fixed:
- The glass curve no longer cuts the first letter of section headers, the chip strip or a dialog's message line (the overhang allowance is capped by the row's own padding; the air gap is 13dp).
- Loose content inside a panel is bent with the panel, so the download sheet's "Episode …" line reads whole.
- The section you opened the title from is named after the engine (CLOUDSTREAM, HIKARI, NUVIO…), and same-engine sections merge into one.
- Cross-extension search concurrency 18, extraction 12, so the repo that carries the title answers while the chooser is still open.

## 0.3.73

Fixed:
- Build only: 0.3.72 failed to compile (the episode-map rework is reverted). This release ships the 0.3.72 fixes.

## 0.3.72

Fixed:
- The server list's pills, group headers and rows are no longer sliced flat by the glass curve — the boundary is measured from the shape itself, halo included, with a uniform ~10dp gap.
- Every installed extension is actually asked: the 64-repo list was filled entirely by the 200+ native Hikari repos, so an installed CloudStream repo was never in it. One repo per engine family per round now.
- A cross-extension that could not be searched says so instead of "no matching title".
- Cold-start budgets: search/episodes 15s, extraction 45s.

## 0.3.71

Fixed:
- Tapping a server always plays it (no row is drawn as current before playback is committed).
- The list no longer eats a tap while it is still growing, and no longer re-opens by itself after you picked.
- The quality badge comes back and landscape video rotates: the size is read from the video track when the enhancer swallows it.
- The rotate button wins over auto-rotate.
- The pill row is not cut off in portrait; glass panels shrink to fit their rows.
- The origin's own engine comes first in the server order.

## 0.3.70

Added:
- Settings → Logs & diagnostics: two rolling app logs and a crash log, each with Share and Save to Downloads, plus Share all / Save all and Clear all.
- The player preview schematic can show Words or Icons.

Fixed:
- "Provider not found" on a title opened from History/Library/Home: the title is looked up again (History, then installed providers, same engine first).
- The extracted server list is cached app-wide, and a Play tap joins a search that is already running.
- Video enhance applies: the GPU colour grade is installed before the video is prepared.
- The player's glass panels no longer jitter on Android 12+.

## 0.3.69

Added:
- Settings → Player → Player controls: every player button can go to the top bar, the left or right end of the bottom row, or be hidden, with a preview and a Reset.
- Video enhance: real GPU presets (Natural, Vibrant, Movie, Cinematic, Warm, Cool, Anime, Bright), applied to the video itself.
- Accent colours: eleven accents for the app, a separate accent for the player, and one-tap copy between them.

Fixed:
- The launch crash that showed the "app crashed on a previous launch" banner: native WebView failures and renderer deaths no longer take the process down.

## 0.3.68

Added:
- Trailers row on a title, opening the real YouTube app.
- Telegram group (`t.me/CodegeasseHikari`) via Settings → About, with a one-time invitation.
- New Home: 16:9 hero carousel with Play/Library, Continue Watching shelf, progressive feed, bounded poster pipeline.
- New detail page: Show Details, Cast row, Trailers, Related and Similar, Add to Library, season picker with 30-episode pages, English episode names, tappable genre chips.
- New player UI: glass panels for every menu, compact top bar, gesture HUD, subtitle size/sync/position controls, PiP, resume prompt, deep buffering.
- Server sections by engine, "Don't auto-play the first source", slow connection / mobile data mode.
- Downloads: save offline or export to Downloads, quality choice, HLS video+audio muxed into one MP4, parallel segments, 1-10 concurrent downloads with pause/cancel/delete.
- New Settings folders, in-app UI scale, GitHub update checker that installs the APK.
- Extensions: `.cs3` CloudStream plugins, `.hiki`, Nuvio providers, Stremio addons and universal scrapers side by side, each repo's folder with install-all, and the official repos seeded on first run.

Fixed:
- Detail-page metadata (Show Details / Cast / Trailers / Related / Similar) never loaded — a `NetworkOnMainThreadException` swallowed by `runCatching`.
- System back steps out of Extensions/Settings folders.
- Out-of-memory crashes on image-heavy screens.
- Search paging, catalog categories, plugin settings scrolling, mojibake in titles, hero banner swipe, continuous numbering on single-season shows, a Kotlin interpolation crash on CJK season markers (`第2季`).
- A blank band under the status bar after returning from the background in fullscreen.
