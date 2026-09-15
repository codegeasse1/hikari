## 0.3.77

**With "Don't play directly" on, the server sheet now opens the instant you tap
Play — and your own extension is searched first.**

- **The chooser no longer waits 20-25s for the first server.** It used to open
  only once a server (or the "wait for N servers" count) had arrived, so a tap
  on Play showed the title card for up to half a minute before the list
  appeared. It now opens the moment the player does, with a live
  "Searching <your provider>…" state, and fills in as servers land — every
  server is appended the second it is found.
- **Backing out of an empty chooser no longer kills the play.** If you dismiss
  the sheet before anything has been found, the search keeps running and the
  chooser re-opens when the first server actually arrives, instead of showing a
  "no servers" error.
- **The in-player "wait for more servers" setting no longer delays the
  chooser** — one server is already enough to put the list on screen. An
  episode switch made from inside the player honours the setting too, so a new
  episode gets its own chooser instead of auto-starting.
- **Your provider is searched first.** All the other providers used to fire at
  t=0 alongside the one you opened the title from (~60 searches at once on a
  phone), which buried its own servers behind the pack. The origin's job now
  starts immediately; the other primary targets (the Nuvio engines) wait a
  1.2s head start, and the other repos of the origin's own engine (the
  CloudStream family) wait 2s. Their servers still stream into the same sheet
  right after — the origin's land first, which is what "if I'm on MovieBox,
  play MovieBox" should look like.
- **Searching keeps running in the background.** Covers, searches, catalog
  loads, source scans and extension installs are now carried by a foreground
  service (a quiet ongoing notification), so backing out of the app no longer
  abandons a search or a download mid-way. Android 13+ asks for notification
  permission on first launch.

## 0.3.76

**The chooser's hint no longer looks "stuck on one repo".** It used to be a
`" · "`-joined list of every extension that came back empty, and the two-line
hint cut that off after the first entry — so a repo saying "no matching title"
sat there looking like the search had died, while the rest were still running.
The line now leads with numbers and keeps updating:

`Asked 48 other repos (CloudStream 48 of 48, Hikari 64) — 12 still searching, 4 with servers`

and, once everything is finished and nothing was found,

`Asked 96 other repos (CloudStream 48, Hikari 48) — all done, none with servers · e.g. Prmovies — no matching title`

- It reports how many repos were asked **out of how many are installed**, per
  engine, so "32 of 48 CloudStream" immediately shows when an engine's repos are
  being skipped rather than answering empty.
- How many found servers, and how many are still searching, so progress is
  always visible.
- One example reason is appended only when nothing at all was found, so the
  "why" is still on screen without the line being truncated mid-list.

Verified from the shared 0.3.74 log: the CloudStream repo that carries the
title (`MovieBox [CS3]`, which found servers in the earlier 0.3.71 run) was
**not among the 32 CloudStream repos that 0.3.74 asked** — the 64-slot cap gave
it no turn. 0.3.75's uncapped list asks all of them.

## 0.3.75

**The real cause of "no CloudStream server shows up".** A shared log settled
it: in the report the app asked **exactly 64 providers and all 64 of them were
native `.hiki` repos — zero CloudStream**. The CloudStream repos were installed
the whole time; they were never *asked*.

- **The cross pass no longer stops at 64.** The pass builds one list of every
  installed extension that gets asked for the same title, and that list was
  capped at 64. It is ordered origin-engine-first, and the native `.hiki` family
  alone is 64+ repos — so the cap filled the list ENTIRELY with `.hiki` repos and
  an installed CloudStream repo was never reached. The cap is now 1024, i.e.
  every installed repo is in the pass; the concurrency semaphore (18) and the
  time budget — not a silent cap — bound the work. The proof from the log:
  `cross=64 same=48 late=16` with a CloudStream origin (48 CloudStream + 16
  `.hiki`, a straight prefix of the ranked list) and 64 distinct `.hiki` repos
  with ZERO CloudStream when the title was opened from a Nuvio provider.
- **The origin's own engine is asked in full, first.** Open a title from a
  CloudStream repo and every installed CloudStream repo is asked before a single
  slot is spent on the 64+ native repos; then the remaining engines round-robin
  one repo per engine per round, so no engine is starved.
- **The log now identifies its build and the installed counts.** `Search: start`
  begins with `v=<app version>` and ends with `installed=CloudStream=48,Hikari=64,…`
  — so a log can no longer be ambiguous about which build produced it, or about
  whether the CloudStream repos existed to be asked.

## 0.3.74

Three fixes: the last of the flat-cut text along the glass, the heading over
the origin provider's servers, and a much faster cross-extension pass so the
one repo that carries a title doesn't land after playback has already started.

### Nothing is cut by the curve (headers, and the download sheet's line)

- **The overhang allowance is now capped by the row's own padding.** The bend
  lets a row ride the bow as far as its *content padding* reaches — that is the
  dark space inside a pill's rounded end, which is what makes a row look like
  it curves with the glass. But the allowance was being credited to rows that
  have no padding and no rounded background at all: the section headers
  ("HIKARI · 5", "ANIME4I · 2"), the "All/Hikari/Nuvio" chip strip, and a
  dialog's message line. Those are laid out flush at their own left edge, so
  the allowance put their first letter straight onto the bowed edge with the
  bright rim cutting through the glyph ("H", "A"). A child with no padding on a
  side now gets no overhang there; its margin lands at the true shape boundary
  plus the ~13dp air gap (widened from 10dp, because text a couple of dp off a
  bright rim reads as sliced even when it is not).
- **Loose content is bent too.** Only containers handed over as row *hosts*
  were bent; anything else inside the panel — notably the message line a
  `showGlassMenu` dialog puts above its list — was laid out at the panel's full
  inner width and then sliced by the bowed edge. The download sheet's
  "Episode 683 · …" line lost its first four letters to the left curve and
  wrapped the rest of the way round; it is now bent like everything else, and
  read whole. A registered host (or a container holding one) is recognised and
  skipped, so no row is bent twice.

### The heading over your own provider's servers

- The section the user opened the title from is now named after the **engine**
  (`CLOUDSTREAM`, `HIKARI`, `NUVIO`…), not the one repo — so it reads as a
  category, like every other section, and the repo a server came from stays
  visible on the row itself ("MovieBoxIN (Hindi Audio) 1080p"). When the origin
  and other installed repos share the engine (a CloudStream title plus the
  other CloudStream repos), they now merge into **one** `CLOUDSTREAM` section
  instead of producing a duplicate heading and a duplicate chip.

### Cross-extension: the repo that carries the title answers sooner

- Searching a title is cheap and extracting a link is not, so they keep
  separate caps — but both were tight enough that, with ~50 installed repos,
  the searches alone took the better part of a minute. The one repo that
  actually carries the title (MovieBox, for the title in the report) only
  landed its servers about two seconds after playback had already started on
  the origin's own sources. Search concurrency is now 18 and extraction 12, so
  every installed repo gets its turn sooner and the servers that do exist
  arrive while the chooser is still open.
- The `Search: start` log line now reports which engines the cross pass is
  about to ask and how many repos of each (`families=CloudStream=48,Hikari=…`),
  so "the CloudStream servers never show up" can be answered from the log: it
  is visible whether that family was searched at all.

## 0.3.73

Build fix. **0.3.72 failed to compile** in CI (the Kotlin compiler rejected
the CloudStream episode-map rework, which is reverted here), so this release
ships the same fixes as intended for 0.3.72:

- The server list (pills, group headers, rows) no longer has its rounded ends
  sliced flat by the glass curve — the boundary is measured from the shape,
  halo included, with a uniform ~10dp air gap.
- Installed CloudStream extensions are now actually queried by the
  cross-extension pass (the 64-repo list used to be filled entirely by the
  200+ native Hikari repos), and the chooser's hint line reports why a repo
  came back empty.

See the 0.3.72 notes below for the full detail.

## 0.3.72

Fixes the server list's rounded rows and headers being sliced flat by the glass
curve, and makes a missing CloudStream extension explain itself instead of just
not appearing.

### The server list (rows, headers and pills cut by the glow)

- **Nothing is cut by the curve any more.** The glass silhouette includes its
  soft halo, but the rows/headers/pills were bent against the *view's* edge
  rather than the visible curve — so on a portrait panel the first pill's cap,
  the section header's leading letter, and every row's rounded ends were sliced
  flat by the glow. The boundary is now measured from the shape itself (halo
  included), so the header, the pills and every row sit **inside** the curve
  with a uniform ~10dp air gap on both sides.
- Applies to both portrait and landscape, and to the "All/Hikari/Nuvio" chip
  strip, the group headers and the server rows equally.

### CloudStream extensions that never show up

- **The real fix: every installed extension is now actually asked.** The
  cross-extension pass picks at most 64 repos, and it sorted them
  "origin's engine, then 200+ native Hikari repos, then CloudStream". With the
  native Hikari family alone numbering two hundred, that list was filled
  ENTIRELY by Hikari repos — a CloudStream (`.cs3`) repo the user had installed
  was never in it, so its servers could not appear no matter how long you
  waited for the other engines to finish. The pass now cycles one repo per
  engine family per round, so every family keeps its seat (your CloudStream
  repos are asked right after the origin's own family) while each family still
  keeps its install order.
- **A cross-extension that could not be searched now says so.** If a CloudStream
  repo's plugin fails to load, or its search call throws, Hikari used to report
  it as "no matching title" — indistinguishable from "this repo simply doesn't
  have the show". It now reports the real reason (plugin failed to load /
  search failed: …), logs it, and surfaces it live in the chooser.
- **The chooser's hint line is now live status.** While the other engines are
  still searching it reads "Searching <repo>, <repo>"; once they are done it
  reads "No servers from: <repo> — <reason>", so it's obvious at a glance
  whether a repo is still working or came back empty and why.
- Longer cold-start budgets (search/episodes 15s, extraction 45s) so a slow
  repo has time to answer on a phone network.

## 0.3.71

Fixes for the "don't play directly" server list, the video's missing quality
badge / wrong orientation, the server order, and the cut-off pill row.

### The server list ("Don't play directly — show all servers to choose")

- **Tapping a server now always plays it.** With the chooser up, the first row
  was drawn as the current server (the player's index starts at 0 before
  anything has played) and a tap on that row was treated as "already on this
  one" — so the list closed and nothing ever started. No row is marked as
  current until playback has actually been committed to one, and a tap on the
  first row plays it like any other.
- **The list no longer eats taps while it is still growing.** Each batch of new
  servers rebuilt every row from scratch, which destroyed the row a finger was
  pressing: the tap arrived as a cancel and was dropped. Rows are now appended
  in place while the list only grows.
- **The list no longer re-opens by itself.** The server chooser is shown at most
  once per play, so a late batch of servers can't bring it back after the user
  has already picked (this was the "the server list shows again, then it starts
  on the fastest server" report).
- Backing out of the list (Back, ✕, a tap outside) still falls back to the
  remembered/best server instead of leaving the player on the loading card —
  but a tap on a row never also triggers that fallback.

### Player

- **Quality badge comes back, and landscape video now rotates.** With the video
  enhancer active media3 never reports the video's size (its effects pipeline
  swallows the callback), so the badge never appeared and the screen stayed
  portrait with a letterboxed video. The size is now read from the video track
  itself as a fallback, and it also sets the render aspect ratio.
- **The rotate button wins.** Auto-rotate happens once per source and never
  overrides a rotation the user chose with the button.
- **The pill row is no longer cut off in portrait.** The row is a scrolling
  strip, and a focused pill could pull it to one end and leave the first pill
  half cut off for the rest of the session. It now starts at its left edge
  every time the controls appear, and the centring spacer only reserves room
  while the pills actually fit.
- **Glass panels are measured onto their rows.** A panel taller than the rows it
  held left a bare band of glass below them; the panel is now shrunk to fit its
  content (still capped, still scrollable when longer), and the row-bending
  maths is idempotent so a row can never be squeezed to nothing.

### Server order

- **The origin's own engine now comes first.** A title opened from a CloudStream
  repo asks the user's OTHER CloudStream repos in the first pass — they used to
  wait out the grace window and then sort behind the whole Hikari pool, which is
  why they showed up below dozens of Hikari servers.

## 0.3.70

Bug reports without screenshots, plus fixes for the "it found no sources", "the
first play is slow" and "Provider not found" reports.

### Logs you can share — Settings → Logs & diagnostics

- Hikari now keeps its own logs on the device: **two rolling app logs**
  (`app.log`, and `app.previous.log` once the first rolls over at 512 KB) and
  **one crash log** — the most recent uncaught exception with its full stack
  trace and the last 300 log lines leading up to it.
- **Settings → Logs & diagnostics** lists all three, each with **Share** and
  **Save to Downloads**, plus **Share all** / **Save all** to hand over
  everything at once. The share sheet carries the real text files, so a report no
  longer needs a photo of the screen.
- The "The app crashed on a previous launch" banner gained a **Share log**
  button next to Dismiss.
- Logs never leave the device by themselves; the page also has **Clear all
  logs**.

### "Provider not found"

- A detail page opened from History/Library/Home could dead-end on "Provider not
  found". Extension ids are **not stable** — a CloudStream plugin that
  re-registers its providers reindexes their ids, and some ids are derived from a
  file name. Hikari now looks the same title up again (your History first, then
  the installed providers, same engine first) and carries on, instead of
  refusing to open a title that plays fine.
- Everything that records state per provider (Library, "the server you used
  last", search-in-provider) now uses the provider the page actually ended up
  on, so the rescue sticks.

### Faster second play — no more "Finding the best server…"

- The extracted server list is now cached for the whole app instead of for each
  screen. Backing out of the player and reopening the same title used to re-run
  the entire multi-provider search; now it is instant. A Play tap made while the
  page is still searching **joins** that search instead of starting a second one.
- Provider links are signed and expire, so a stale list is never used for
  instant play: it is shown while the fresh one is fetched, so playback can never
  start on a dead link.
- **Select server** now shows your provider's own servers as their own section at
  the top, then every engine that found something.

### More servers found

- The cross-extension pass now runs 10 extractions in parallel (was 6) with an
  80 s ceiling (was 50 s), so extensions further down the list really do get
  searched.
- If the full title finds no match, the search retries with a shortened title
  ("Foo: Bar (2023)" → "Foo").
- Every provider's outcome is written to the log, so "this extension returned
  nothing" can be read from a shared log instead of guessed at.

### Video enhance actually applies now

- The GPU colour grade is installed **before** the video is prepared. Media3 only
  creates the video pipeline when an effect list is already present — setting a
  preset after playback began was silently ignored, which is why Enhance looked
  like it did nothing.
- On devices whose video pipeline cannot be built at all, Hikari now turns the
  feature off and says so, instead of leaving playback on a black screen.

### Player controls

- The preview schematic can be switched between **Words** and **Icons**, so you
  can see the actual glyphs the player shows, and every control's row now shows
  its icon.

### Fixes

- The player's glass menu panels (Servers, Options, …) no longer jitter or twitch
  while scrolling on Android 12+.

## 0.3.69

Customisation, and a crash fix.

### Player controls — you decide where the buttons go

- New **Settings → Player → Player controls** screen. Every player button —
  favourite, download, PiP, gear, lock, speed, episodes, servers, quality,
  audio, subtitles, rotate, skip intro, resize, enhance — can be moved to the
  **top bar**, the **left** or **right** end of the bottom row, or **hidden**
  completely.
- A schematic preview at the top of the screen shows the resulting layout, so
  you can see where things land before leaving the page.
- Buttons moved to the top bar turn into compact round icon buttons, so the bar
  can never overflow.
- The back button, the play/pause circle and the title are fixed: without them
  there is no way to leave, pause or tell what is playing.
- **Reset** puts everything back the way it shipped.

### Video enhance

- A real GPU colour grade applied to the **video itself** (not an overlay on the
  UI), so it works with every server and every title. Presets: **Natural**
  (default — applies nothing), **Vibrant**, **Movie**, **Cinematic**, **Warm**,
  **Cool**, **Anime** and **Bright**.
- Pick it in **Settings → Player → Video enhance**, or from the new **Enhance**
  button in the player's bottom-right row.
- HDR video is handled carefully: the tint part of a preset is skipped on HDR
  streams, so a 4K HDR film can never be broken by picking one. Enhancement
  only runs while a preset is picked — "Natural" costs nothing.

### Accent colours

- **Settings → Appearance → Accent color**: eleven accents (Amber, Violet,
  Blue, Cyan, Teal, Green, Red, Orange, Pink, Purple, Mono) for the whole app —
  buttons, selected tabs, sliders, highlights — with a live preview swatch
  drawn from the accent's real gradient.
- The **player has its own accent** (the glow behind its pills, badges, play
  ring, progress bar and gesture HUD), so you can keep the app gold and the
  player violet, or make them match.
- **Match app & player theme** ties the two together, plus one-tap
  **App → player** / **Player → app** buttons to copy either direction when
  they're not linked.

### Crash fix

- Fixed the launch crash that could show the "The app crashed on a previous
  launch" banner: a native WebView failure is no longer rethrown into the app,
  WebView renderer deaths are handled instead of taking the process down, and a
  background-thread crash no longer kills a healthy running app.

## 0.3.68

The big one: a new look everywhere, a real download system, server sections, a
much smarter detail page, and a place to talk to us.

Here's what's new — and, for the two new things that *open other apps*, exactly
where they open, so nothing surprises you.

### Trailers open in the real YouTube app

- The new **Trailers** row on a title shows the actual YouTube trailers and
  teasers for it, official ones first.
- Tap one and it opens the **YouTube app** — the real one, with your account,
  full quality, and the controls and gestures you already know.
- If YouTube isn't installed, it goes to your browser instead. Hikari's own
  in-app browser is the very last fallback and, in practice, never gets used —
  so you always get a real trailer, not a stripped-down web page.

### Telegram opens in Telegram

- **Telegram group:** <https://t.me/CodegeasseHikari> — for support, bug
  reports, feature requests, title requests, or just to say hi.
- Two ways in: **Settings → About → Telegram**, and a one-time invitation the
  first time you open the app.
- Join opens the **Telegram app** directly — and if Telegram isn't installed,
  your browser. It deliberately never opens inside Hikari's own in-app browser:
  Telegram is an app, so we hand you straight to it.
- The invitation shows **once**. Tick **"Don't show this again"** and it never
  comes back.

### New Home screen

- **Wide 16:9 hero carousel** — swipeable, with the title's backdrop, logo-free
  art, rating and a one-tap **Play** / **Library** action.
- **Continue Watching** shelf that remembers where you were, plus a setting to
  hide it if you don't want it.
- **Progressive feed** — rows appear as each extension answers instead of the
  whole page waiting for the slowest source.
- Posters load through a bounded, tokenised image pipeline, so a Home with
  thousands of tiles no longer runs out of memory.

### New detail page

- **Show Details block** — status, runtime, certification, rating, country,
  language, director and writers, from TMDB.
- **Cast row** — tap any actor to search for them.
- **Trailers row** — see *Trailers open in the real YouTube app* above.
- **Related** and **Similar** shelves.
- **Add to Library** (the heart) right on the page, mirroring the player's heart.
- **Season picker** and 30-episode pages, so a 200-episode show is navigable.
- **Episode names in English** — pulled from TMDB (`en-US`) when it has them;
  otherwise the episode keeps the source's own name (never a Chinese title
  substituted for an English one). Decorated/franchise titles ("Sword of Coming
  Season 2", "Battle Through The Heavens: Origin") now resolve properly, which
  is what brings Cast / Trailers / Details / Related / Similar back for them.
- Tap a genre/tag chip to search for it.

### New player UI

- Complete redesign: floating **glass panels** for every menu — quality, audio
  track, subtitles, servers, episodes, playback speed and progress — with the
  panel's rows following its curved edge.
- Compact top bar (heart / download / PiP / gear / lock), centred pill row, and
  a "Tap to play" title card while the stream starts.
- **Gesture HUD**: double-tap to seek (5s/10s), vertical swipe for brightness
  (left) and volume (right), long-press speed, lock and rotate pills.
- **Subtitles**: size, sync offset and vertical position controls; side-loaded
  subtitle files render correctly now; audio-track switching fixed.
- **Picture-in-picture**, YouTube-style control auto-hide, resume prompt inside
  the video, and deep buffering so a weak connection stalls instead of dying.
- Side-loaded and extension-provided subtitles, per-source headers, HLS/DASH,
  ClearKey/Widevine DRM, and no more replaying an expired provider link (servers
  are re-fetched when they go stale). The server list keeps filling in live
  while you watch.

### Pick your server, grouped by engine

- **Don't auto-play the first source** — a new setting lets Hikari wait (for the
  first server, or until N servers are found) and show the chooser instead, with
  an optional title-card loading screen.
- Servers are now **grouped into sections**: **CloudStream**, **Hikari**,
  **Nuvio**, **Stremio** (and Other), so a merged list of 40 servers is readable
  at a glance.
- **Slow connection / mobile data mode** — raises the search and probe timeouts
  and retries providers that time out, so a weak connection doesn't end in
  "No playable sources found".
- Play never dead-ends any more: the player opens immediately and the sources
  sheet reveals itself as soon as anything is found.

### Downloads (new)

- Save titles **offline inside the app** and/or **export to your phone's
  storage** (Downloads folder via MediaStore).
- Choose the quality to download.
- HLS downloads keep video + audio in sync and **mux them into one MP4**
  (separate audio tracks are merged, not dropped).
- Parallel segment fetching — video and audio download at the same time.
- **1–10 concurrent downloads** slider, live speed, progress, a "Converting"
  phase and the ability to pause/cancel/delete from the Downloads tab.

### New Settings screen

- Reorganised into folders instead of one endless list: **Player**, **Sources**,
  **Downloads**, **Appearance**, **Privacy**, **About**.
- **In-app UI scale** — ignore the phone's font/display size and pick your own,
  so the app looks identical on every device.
- Themes, playback start rule, loading banner, slow-connection mode, ad
  blocking, userscripts, element blocker, WebView safety (redirect + popup
  protection with an allow-list), WebView user-agent override, download
  concurrency, continue-watching visibility, and a GitHub update checker that
  downloads and installs the new APK for you.

### Extensions

- **CloudStream `.cs3` plugins** (Stage 2): Hikari's own cloudstream3
  compatibility layer — install from a repo (checksum-verified) or a single
  `.cs3` URL/file, and the plugin's own settings screen opens from the gear.
- **Hikari extensions** (`.hiki`), **Nuvio providers**, **Stremio addons** and
  **universal JSON/HTML scrapers** all live side by side.
- Extension browser with **folders per repo, per source kind**, install-all,
  search, and a **globe button** to run an extension's Cloudflare verification
  in a real WebView.
- The official Hikari and CloudStream repos are seeded on first run, so you have
  sources without typing a single URL.

### Fixes

- Detail-page metadata (Show Details / Cast / Trailers / Related / Similar) used
  to silently never load — a `NetworkOnMainThreadException` swallowed by a
  `runCatching`. All TMDB calls now hop to a background thread by construction.
- System back now steps out of Extensions/Settings folders instead of dropping
  you on Home.
- Out-of-memory crashes on image-heavy screens (bounded caches, `largeHeap`,
  poster tokenisation).
- Search paging, catalog categories, plugin settings sheet scrolling, mojibake
  in some titles, hero banner swipe, single-season shows with continuous
  numbering, and a Kotlin string-interpolation crash on CJK season markers
  (`第2季`).
- In-app fullscreen no longer leaves a blank band under the status bar after
  coming back from the background.
