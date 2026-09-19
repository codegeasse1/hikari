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
