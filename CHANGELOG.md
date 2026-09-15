# Changelog

Notable changes to **Hikari**, newest first. Every version here is published on
the [releases page](https://github.com/codegeasse1/hikari/releases) with a signed
APK attached (`hikari.apk`).

---

## 0.3.68

The big one: a new look everywhere, a real download system, server sections, a
much smarter detail page, and a place to talk to us.

### Join us on Telegram

- **Telegram group:** <https://t.me/CodegeasseHikari> — for support, bug reports,
  feature requests, title requests, or just to say hi. It lives in
  *Settings → About → Telegram*, and the app invites you **once** on launch.
  Tick **"Don't show this again"** and it never comes back. Joining opens the
  Telegram app (or your browser) — never Hikari's built-in browser.

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
- **Trailers row** — real YouTube trailers/teasers ranked (official trailers
  first). Tapping one opens the **YouTube app**, not the in-app browser.
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

---

## 0.3.67 and earlier

- Stremio addons, universal scrapers (HTML selector rules + JSON-API mode), and
  CloudStream `.cs3` plugins.
- Media3 player with per-source headers, subtitles, HLS/DASH and seek controls.
- Search across every installed extension, with multi-page results.
- Library (favourites), History, and Continue Watching.
- Glass UI for Extensions and Settings, extension folder views, repo browsing.
