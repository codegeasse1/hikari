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
