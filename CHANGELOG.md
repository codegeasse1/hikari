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
