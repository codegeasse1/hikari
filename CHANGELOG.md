## 0.5.3

**Search can no longer get stuck on "finding server", the provider chips and list
rows are a comfortable size again, and a SkyStream extension behind Cloudflare
loads its catalog instead of reporting "no catalog".**

- **"Stuck on finding server" — and then it never searched again.** Two unbounded
  waits caused it. (1) The plugin loader took its process-wide slots with
  `acquireUninterruptibly()`: with only six slots and a few hundred installed
  extensions, one load that never returned parked every slot for good, so every
  later load — the whole cross-extension sweep and the Home warm-up — waited
  behind it forever and did nothing at all. A slot is now waited for at most 45
  seconds and then reported as *that one extension's* failure, so the sweep
  records it, moves on, and leaves the queue free for everyone else. (2) The
  search's wall-clock budgets ran through the slow-connection multiplier, so on a
  slow network "provider budget" became 150s and the sweep 450s — the sheet sat
  on "finding server" for minutes and appeared frozen. Every budget is now
  clamped to its intended ceiling (25s per page, 90s per provider, 100s per
  sweep, 55s per server scan), and a repo that already proved it has a show is
  remembered, so asking the same show again answers immediately instead of
  re-walking every repo.
- **The server list always stops saying "still searching…".** The final join onto
  the server search had no timeout, so one provider that never answered left the
  sheet in the searching state forever even after everything else had finished:
  it is now bounded (90s), the closing streams call has a hard 80s cap, and when
  a search ends with servers found the status line says so ("Found N server(s) —
  search finished.") instead of leaving the spinner text up.
- **SkyStream extension catalogs: the real cause of "no catalog" on 4khdhub &
  friends.** SkyStream's HTTP fetches were the only ones in the app without
  Cloudflare handling. A challenged site answered with its "Just a moment…"
  interstitial, the extension parsed *that page* as its catalog and reported a
  successful, empty result — so Home said "no catalog" while the site was simply
  waiting for a verification. SkyStream's client now runs CloudflareVerifier's
  interceptor like every other provider: it reuses the clearance your verify
  WebView earned, retries the challenge with the WebView's own user agent, and
  records the host so Home offers its globe button. The catalog budget is 75s
  (a SkyStream extension boots a whole JS engine plus a dozen-plus page fetches
  before it can answer), its fetch pool is 24 wide so a home page's parallel
  requests actually run in parallel, a plugin that parks on a promise nothing
  settles now says "the extension stopped responding before it could finish"
  instead of a misleading "timed out", and an empty catalog points at the globe
  button when the cause was really a Cloudflare wall.
- **Extensions are asked in engine-family order.** Home and search walked the
  install list, so with a few hundred installs the extensions installed *last* —
  every SkyStream one — sat at position ~240 and were never reached before the
  feed's own ceiling: their rows never appeared, which also read as "this
  extension has no catalog". Providers are now round-robined by family (first of
  each, then second of each, …), while install order is kept inside a family so
  the oldest install of a kind still sorts first among its own kind.
- **Smaller boxes.** The provider chips (All / Hikari / Nuvio / CloudStream)
  under the search bar, the extension picker on Home, its section headers, the
  option rows and the list rows are all one size down — medium instead of large —
  with tighter padding all round.

## 0.5.2

**A SkyStream extension's catalog loads now. The extension list stopped showing
grey puzzle pieces, and loading extensions no longer waits in a queue behind
each other.**

- **SkyStream catalogs load again — the real cause, not Cloudflare.** A SkyStream
  extension fetches all of its home rows with
  `Promise.all(categories.map(...))`, but the engine's HTTP bridge was
  *synchronous*: each of the 8 requests blocked the JavaScript thread until it
  finished, so one home page took the *sum* of eight round-trips (16-40s) instead
  of the time of its slowest one. That ran past the engine's 45s call budget, so
  `getHome` was killed before it ever answered and every installed SkyStream
  extension showed an empty catalog. There is now an asynchronous fetch bridge:
  the request is fired on the worker pool, the promise is resolved when the
  response lands, and the pump loop delivers each answer back into the engine
  between rounds — so `Promise.all` is genuinely parallel and a home page
  arrives in one round-trip. The per-extension catalog budget also went up
  (40s → 55s, and the outer Home cap 70s → 85s) so a slow-but-working site has
  room, and a timeout is now recorded per engine, so the empty state can say
  *which* extension timed out instead of showing the generic Cloudflare note.
  (The Cloudflare line on Home is unchanged — it is only shown when an extension
  really did report a Cloudflare wall.)
- **Extensions load side by side instead of one at a time.** Both plugin
  runtimes guarded every load with a *single* lock for the whole process, so on a
  device with a few hundred extensions a cold Home, a cross-extension search and
  an install all queued behind whichever archive happened to be loading — and one
  slow plugin could hold that queue for its whole 45s budget. Loads are now
  serialised per plugin file and capped at six at a time (with re-entrancy, so a
  plugin that loads another plugin cannot deadlock), and the load-error buffer is
  per thread instead of shared, so two plugins loading at once can no longer
  write over each other's error text.
- **A host that needs verification is skipped, not waited on.** When a page
  turned out to be a Cloudflare interstitial, the WebView resolver still waited
  out its full 60-second timeout on it — and every later attempt on the same host
  paid the same wait. The resolver now recognises a challenge by the page title
  the moment it appears and stops immediately, records the host, and every
  later attempt (resolver, stream probe, fetch bridge) skips that host in
  milliseconds instead of re-discovering the wall. The host's record is dropped
  the instant your own verify WebView earns the clearance. The resolver's own
  timeout is also down to 30s — a real player fires its stream request in a few
  seconds, and the only pages that need longer were ones that were never going
  to play.
- **Cross-extension search is quicker.** More extensions are asked at once
  (28 → 48) and each pass spends less time waiting for the stragglers before it
  shows what it has, while an extension that has already answered "no such
  title" for a query is remembered for a few minutes — so re-opening the same
  sources sheet no longer re-asks all 240 extensions to hear the same no.
- **Extension icons, for real this time.** Every row in a repository listing
  showed the same grey puzzle-piece glyph because the icon was read from a
  single field name and SkyStream listings mostly don't use it: their icon lives
  in the extension's *first addon manifest* (`logo`), and their `baseUrl` is
  often a placeholder (`stremio-hub.local`). Icons are now resolved in order —
  the listing's own `iconUrl`/`icon`/`logo` (relative and protocol-relative
  paths are made absolute), else the addon manifest's logo (saved at install
  time, so installed extensions keep it), else the site's favicon (placeholder
  hosts skipped) — and **SVG icons decode at last** (Coil ships no SVG support;
  many logos, e.g. dramayo's, are `.svg`, which decoded to a failure and landed
  on the placeholder). The `%exact_size%` URL template used by some CloudStream
  repos is substituted too.
- **Smoother.** Every log line used to append to the log file *and* stat it for
  the roll-over check **on the calling thread** — i.e. on the main thread, for
  every tracked event. File writes now happen on one daemon writer thread, and
  the screens that read the logs flush first, so nothing about logging can cost
  a frame any more.
- **The extension picker matches the rest of the app.** Its search box is the
  translucent pill used elsewhere, and the rows and category chips are the same
  roundy glass cards as the settings panels instead of flat charcoal strips; the
  icon tile on each row is a glass tile as well.

## 0.5.1

**One Play tap searches everywhere, and keeps filling the server list while the
video runs. The ten alternative icons are cut correctly at last, `.sky`
extensions install, and extensions can no longer open their own Cloudflare page
over your video.**

- **Every engine's servers, on Play, in the background.** Tapping Play used to
  show the servers of the extension you opened the title from and then stop —
  the rest arrived on a live session the player had already stopped listening
  to, so the list looked like it had "loaded halfway". The play tap now holds
  *one* session id for the whole search, so Hikari / Nuvio / SkyStream /
  Stremio / CloudStream / universal-scraper servers all keep landing on the
  player's own source sheet as each engine answers, exactly like the download
  chooser does.
- **A search that ends empty no longer wipes the servers you already have.**
  When a late or cached re-read of the providers came back empty (every one of
  them failing or timing out on the retry) it used to overwrite the list the
  player was already holding, which closed the source sheet and left the next
  Play tap saying "no servers" even though the links were still good.
- **`.sky` extensions install again.** Installing a SkyStream extension failed
  with *"Not a valid SkyStream extension: TypeError: circular reference"* — the
  engine's bootstrap returns the global object, and writing it back into the
  script's own scope made QuickJS reject the value. Bootstrapping now discards
  the result, so the extension loads.
- **Extensions cannot open a Cloudflare page on their own.** Some extensions
  (Cinemacity is the one that was caught doing it) ship their own "Bypass
  Cloudflare" WebView and open it by themselves mid-search, and print the raw
  *"Cloudflare blocked. Go to Settings…"* line over the video when they can't.
  The guard is now three layers instead of one, so it holds whether the
  extension reads the toggle through Hikari's key store or straight out of its
  own preferences file: the read answers `false`, the stored value is forced to
  `false`, and the `Context` extensions are handed answers `false` too. Turning
  Hikari's own switch **on** no longer writes `true` into the extension's
  switch either — it only stops the forcing (writing it *enabled* the very page
  the switch was meant to prevent). Hikari's own tap-only verification (the
  globe button) is unchanged.
- **Every alternative app icon is cut correctly.** The ten alternatives were
  cropped from the art sheet by hand and several were clipped or half-offset —
  one lost a third of its artwork inside the launcher's icon mask. All ten are
  re-cut to the same rule (complete artwork, equally inset, sampled per icon),
  so nothing is cut off on any launcher shape, and the chooser tiles now show
  the same thing the launcher will.
- **Collections moved where you'd look for them.** Settings → Appearance no
  longer carries *Collections*; it lives in a new *Personal Catalog creator*
  folder of its own in the settings list.

**Fixes on top of 0.5.0:**

- **The old crash is gone for good.** The `ToastBinding`
  `NoClassDefFoundError` some installs hit on launch (a generated ViewBinding
  class the desktop CloudStream jar expects) is still excluded from the jar and
  replaced by Hikari's own class — see the older entry below; nothing in this
  build touches it.

## 0.5.0

**Hikari speaks SkyStream. `.sky` scriptable extensions run next to every other
source — install, browse, search, play, download — and the "no playable source"
regression is gone.**

- **SkyStream `.sky` extensions.** A new provider family beside Stremio addons,
  universal scrapers, Nuvio and CloudStream. A `.sky` file is a JavaScript
  streaming extension written against a small helper library (`http_get` /
  `http_post` / `http_parallel`, `parseHtml`, `getAndUnpack`, AES helpers,
  `solveCaptcha`, `loadExtractor`, …). Hikari runs it in an embedded QuickJS
  engine with a cheerio-backed DOM facade, so an unmodified plugin believes it
  is running under Node — `getHome`, `search`, `load`, `loadStreams` and the
  `getSettings`-driven preference store all work through a bridged `fetch`.
- **Install them wherever you install anything else.** A SkyStream repo
  (`repo.json` with a `pluginLists` chain, an inline `plugins` array, or nested
  repos), a single `.sky` from a URL, or a `.sky` file from your phone —
  Extensions → *Install .sky extension*. Each installed plugin shows its own
  icon (from its manifest, falling back to the repo's favicon), and can be
  toggled, uninstalled and updated like any other extension.
- **Wired everywhere.** SkyStream providers feed Home catalogs, Home's
  collection shelves, Search, the detail page, episodes, the server sheet,
  cross-extension lookup (Hikari / Nuvio / CloudStream ↔ SkyStream) and
  Downloads. When an extension answers, its result is in.
- **`loadExtractor` is real.** When a plugin hands back an embed URL instead of
  a direct link, Hikari now resolves it through its own extractor stack
  (CloudStream-style resolvers and the WebView resolver) rather than failing —
  which is what most SkyStream plugins depend on for the final video URL.
- **Instant play covers SkyStream too.** The first server a plugin's
  `loadStreams` answers with starts playing at once; the rest of the pool keeps
  resolving in the background.
- **Pick the icon your launcher shows.** Settings → Appearance → *App icon* has
  eleven tiles — the official Hikari icon plus ten alternatives — each drawn
  from the launcher icon itself, masked like a home-screen icon so the tile is
  what you get. Switching flips the enabled `activity-alias` with the app
  running: no restart, no reinstall, and your choice survives updates and
  restores (the icon is re-asserted at startup if it ever drifts).
- **Hide the bottom-bar buttons you never use.** Settings → Appearance →
  *Taskbar buttons* switches off Home, Discover, Search, Library, Downloads,
  Settings — individually. Nothing becomes unreachable: the top bar keeps a gear
  (Settings), a magnifier (Search) and the verify globe whenever their button is
  off.

**Fixes on top of 0.4.1:**

- **A nuvio repo your phone's DNS refuses now loads.** Some devices/ISPs answer
  specific hosts with *"No address associated with hostname"* — the host itself
  is fine and answers 200 from any public resolver (the Eclipsia nuvio repo is
  the one that hit this) — so Hikari now falls back to DNS-over-HTTPS: when the
  platform resolver throws, the host is re-resolved over HTTPS against a
  hard-coded resolver IP, which needs no working DNS of its own. Wired into
  every HTTP client Hikari uses — repo manifests, extension installs, both
  plugin runtimes, the CloudStream plugin client and the player/download client
  — so one broken resolver no longer takes out just one feature.

- **"No playable source found" is fixed.** A Cloudflare / withholding-looking
  page is no longer read as proof that an extension had nothing to play — only
  an extension that genuinely came back empty counts as empty. A title that
  plays in one extension plays again.
- **Play is instant again.** The origin-play grace window was cut from 8 seconds
  to 1.2, so the first direct stream starts immediately instead of waiting for
  the whole provider pool to finish before it is allowed on screen.
- **The settings gear opens.** Opening a plugin's own settings screen retries
  when the host is stale, and names the exception when it still cannot — instead
  of dropping you back on the Extensions list with a bare error.
- **A collection's shelves match what you built.** A collection with a *single*
  folder now shows one shelf per catalog inside it — your Netflix, Hulu and
  Disney+ lists side by side — because a one-folder collection is a grouping of
  catalogs, not a merged jumble. Two or more folders keep the folder-per-shelf
  layout, named after each folder. (0.4.0 merged a single folder's catalogs into
  one row and hid what you had picked.)

## 0.4.1

**An extension you installed stays installed, a repo can only be added once, and
the whole app now speaks your language.**

- **An installed extension stays installed.** A repo's list is matched against
  what is on your device by the *identity* of the file, not by the exact URL
  string the repo happened to publish the day you installed it. Repos rewrite
  those URLs as they rebuild — a new branch, `refs/heads/x` against plain `x`,
  or the jsDelivr mirror Hikari falls back to when GitHub rate-limits — and a
  literal string comparison is why a repo you had already added greeted you with
  an *Install* button for an extension that was installed and still working on
  Home, and why its *Install all* offered to install it a second time. The
  Uninstall button, uninstall itself, the update check and the gear button on a
  repo row all key on that identity now.
- **One repo, one entry.** Adding a repo you already have (pasting the CNC link
  a second time, say) no longer files a second copy of it in the list under a
  different URL spelling — that copy showed *every* extension of the repo as
  uninstalled all over again. Re-adding now refreshes the entry you already
  have and says *Repo already added*, and a repo list that already has such
  duplicates is healed the first time anything is added or removed.
- **The gear on an extension now says why it failed.** When a plugin's own
  settings screen refuses to open, Hikari names the exception, the plugin class
  and the line in a dialog you can read and copy, instead of an ellipsized toast
  that only had room for *"…threw: Il…"*. The full stack trace also goes to the
  app log (Settings → Logs), and the plugin is re-loaded and tried once more,
  because several of them fail only on a transient condition.
- **The rest of the app is translated.** Home's row titles and genre names, the
  search-scope and translate dialogs, install/update/remove statuses, the
  extension update row and its buttons, the toasts — all of it goes through the
  translation table now: about 490 strings in each of 20 languages plus the
  pseudo-locale, on top of the sections that were already covered.
- **A crash is explained, not just logged.** A crash on the previous run is
  reported on Home as a glass dialog — *Hikari crashed last time* — with a line
  on what to do about it and a pointer to Settings → Logs, instead of a banner
  that sat on the page until it was dismissed.
- **The language picker is a glass dialog.** *Settings → Language* opens the
  20 languages in the app's own dialog, each written in its own script, with the
  current one marked.

## 0.4.0

**A rating row that fills itself in, a colour-coded age rating, a download that
no longer closes the player, a one-file migration path for anyone coming from
CloudStream, and collections that turn your extensions into the shelves you
actually watch.**

- **The score row now fills itself in.** Every badge comes from a different
  site (IMDb via OMDb/Wikidata/Cinemeta, Rotten Tomatoes, Metacritic,
  Letterboxd, TMDB) and each one is independently optional, so a title that was
  looked up while one of those sites was rate-limited, slow, or simply had no
  page for it yet was cached *without* that badge for a whole day — which is why
  one film showed IMDb and the next did not, no matter how often it was
  re-opened. A missing source is now re-asked in the background on its own
  schedule (every 15 minutes for the IMDb mirrors, every 3 hours for the review
  sites) and the row grows the moment it answers, without the page waiting. Each
  source also has its own 9-second ceiling, so one hanging site can never hold
  up the others, and the attempt times are persisted with the cache, so the
  schedule survives a restart. Titles that TMDB gives no IMDb id for are also
  resolved one step harder: after IMDb's own suggestion endpoint, Cinemeta's
  keyless search index is asked for the `tt` id, so a brand-new release gets an
  IMDb badge (and a Letterboxd one) the moment IMDb has a page for it.
- **The age rating is colour-coded.** `PG`, `R`, `TV-MA`, `G`, … used to be
  plain white text. It is now the same tinted-glass pill as the score badges:
  green for all-ages certificates (`G`, `TV-Y`, `U`, `TP`, `ALL`), amber for the
  guidance bands (`PG`, `PG-13`, `M`, `12`) and red for the adult ones (`R`,
  `NC-17`, `TV-MA`, `18`, `R18`), with any age number the certificate carries
  used as a fallback when the label is one we do not know.
- **Downloading from outside the app no longer crashes.** In download mode
  nothing ever plays, so the player's "I finished playing, close myself"
  cleanup saw the server chooser being dismissed (which is what the row tap
  does) as the user backing out and finished the Activity while the download
  sheet was still opening — which is what threw you back to the home tab. The
  chooser now distinguishes "a server was picked, the next sheet is coming" from
  "the user backed out", the glass dialogs refuse to show on a finishing
  Activity, and a failed queue attempt reports itself instead of taking the
  player down with it. This covers both the server list and the quality list.
- **Import your CloudStream repositories.** Backup & Restore has a third row —
  *Coming from CloudStream?* — that reads a CloudStream backup file
  (`.txt`/`.json`) and adds every repository in it here, so a migration is one
  tap instead of re-typing forty repo URLs. CloudStream's backup holds its
  *repo list* (a JSON array inside `datastore._String.REPOSITORIES_KEY`); its
  installed extensions live in its own database and are not in the file at all,
  so the import brings the repos over and the Extensions screen then lists each
  repo's plugins, with its per-repo *Install all* button. Watch positions, home
  preferences and cookies are deliberately not imported — this app has its own.
  URLs already in your list are skipped, and the reader is layered (known key →
  any repos-looking preference → the largest repo-shaped array anywhere in the
  file) so a fork's differently-shaped backup still works.
- **Collections.** *Settings → Appearance → Collections* lets you build your own
  shelves out of the catalogs you already have. Make a collection, name it, then
  add a folder for each kind of content you want inside it — *Movies*, *Anime*,
  *Kids*, whatever you like — and fill each folder with either a ready-made TMDB
  list (Marvel Studios, Pixar, A24, Warner Bros., DC and friends for films; the
  Netflix, HBO, Prime Video, Disney+ and other networks for series) or any
  catalog from any installed extension. A folder holds as many catalogs as you
  want: its row merges them all and drops the duplicates, and its *Show All*
  opens that single catalog full-screen with paging. Pick a collection in Home's
  provider picker and Home loads only its folders — nothing else — which is the
  fast path to the handful of shelves you actually watch. Everything inside
  behaves like any other row: tapping a title opens the normal detail page and
  plays through the normal sources, TMDB lists included, because their titles
  are matched back onto your installed extensions. Collections are stored in the
  app's own settings, so editing or deleting one never touches an extension.
- **The provider picker can filter by engine.** Home's provider picker now
  carries a row of chips — *All*, *CloudStream*, *Hikari*, *Nuvio*, *Stremio* —
  and picking one narrows the list to extensions of that kind, which is the
  difference between scanning a hundred rows and three. *All* stays the default
  and the provider you picked is still remembered across restarts.
- **The new strings are translated.** The CloudStream import row, collections,
  the provider filter chips and the rest of this release's English have entries
  in all 20 languages plus the pseudo-locale.

## 0.3.89

**Black text on dark themes is gone, the age rating now sits on every film and
series, each rating badge explains itself when tapped, and the Download button
asks which server to use instead of quietly starting playback.**

- **No more unreadable black headings.** On dark, AMOLED and glass themes a lot
  of text — catalog titles like *Trending* and *Movies*, the settings rows,
  sheet and dialog text — rendered in flat black on a near-black background. The
  cause was the navigation shell: it drew a *transparent* content surface, and a
  transparent surface resolves to Material's fallback (black) content colour, so
  every piece of text that didn't set its own colour fell through to it. The
  theme now provides its own content colour (`onBackground`) for the whole app,
  so unstyled text inherits the correct colour on every theme, including the
  accent-tinted and transparent glass ones.
- **Age ratings on all movies and series (PG-13, R, TV-MA, …).** The
  certification is shown as a hairline-bordered chip right next to
  *year · runtime* on the detail page — the way IMDb and the store listings
  print it. It comes from TMDB's own per-region certification list
  (`release_dates` for films, `content_ratings` for series), preferring the US
  rating and then falling back to GB/AU/CA/IE/NZ and finally to whichever region
  has one, so a title that was only rated outside the US still shows its rating.
- **Tap a score and it tells you what it means.** Every rating badge is now
  clickable and opens an explanation: where the number comes from, how that
  site's scale works (out of 10, out of 100, a percentage), the word for the
  band it's in (*Rotten*, *Certified Fresh*, *Acclaim*, *Mixed*), how many
  votes or reviews it is based on, the site's own average when it publishes one,
  and a button that opens the source page. IMDb is now always shown with one
  decimal (`8.7`, never `8.7` read as `8`), and its lookup falls back through
  OMDb, Wikidata and Cinemeta, so the badge appears far more often than before.
- **The Rotten Tomatoes tomato is red again.** It was rendering as a green disc
  at every score, which read as a bug. The mark is now always the red tomato
  with its green leaf, and the fresh/rotten split is carried by the colour of
  the number in front of it (green when fresh, red when rotten) — plus the
  Metascore square, which keeps its own green/yellow/red banding.
- **Download doesn't start playback any more.** The download button next to
  **Play** used to begin streaming and only then offer a download. It now lists
  the available servers first with the title **Download from** and a note that
  nothing will start playing, and once you pick one it goes straight to the
  quality list. When nothing has played yet (the usual case) there are no parsed
  tracks to read qualities from, so the app fetches the source's own HLS master
  playlist and asks it — so a multi-quality source no longer silently downloads
  at whatever the engine happened to default to. After the download is queued
  the player closes itself and hands you back to the screen you came from.
- **Less stutter.** Decoding a poster used to bump one global "revision" value
  that every image on every screen was watching, so one image finishing forced
  the whole feed to recompose while you were scrolling. Each image now tracks
  only itself; the glass theme's tokens are computed once per palette instead of
  on every read; and the download flow no longer probes sources in the
  background while it waits for you to choose.
- **Settings header is just *Settings*.** The paragraph explaining the folder
  layout is gone — the screen opens with a bold heading and the folders.
- **The new strings are translated.** The rating explanations, the age-rating
  chip's neighbours, the download chooser's labels and the rest of this
  release's English now have entries in all 20 languages (plus the pseudo-locale
  used to test layout with long strings).

## 0.3.88

**Every score for a film in one coloured row — IMDb, Rotten Tomatoes,
Metacritic, Letterboxd and TMDB — plus save-without-watching, one-file backup,
and a player that plays your own extension's server first and never slides onto
a dead one behind your back.**

- **The lone white `★ 6.9` is gone — the detail page now shows every review
  score, in each site's own colour.** One badge per site: a yellow **IMDb**
  number, the **Rotten Tomatoes** tomatometer (red when fresh, green when rotten)
  with its **popcornmeter** audience score, the **Metacritic** square that turns
  green/yellow/red with the Metascore, the green **Letterboxd** average, and
  TMDB's own score in TMDB's blue. Each badge is the site's mark next to its
  number on a tinted pill, and the row scrolls sideways so six of them still fit
  a phone. This is why a film that IMDb scores 8.7 and Rotten Tomatoes scores
  73% no longer reads as a flat "6.9".
- **Where the numbers come from — no API keys, nothing to set up.** IMDb's own
  site answers a plain app with an empty page, so the IMDb rating comes from
  Wikidata's record of it (matched by the title's IMDb id, which the TMDB lookup
  already returns); Rotten Tomatoes, Metacritic and Letterboxd are read from
  their own pages and then checked against the title and year before they are
  trusted — a bad guess shows no badge rather than a different film's score.
  Every source is independent: whatever answers shows up, whatever is blocked,
  renamed or simply has no entry is quietly absent, and a review site can never
  delay or break the page it decorates. Scores are cached for a day.
- **Your own subtitle files: "Add external subtitle".** When no extension has
  subtitles for a film — or only bad ones — the Subtitles sheet (the CC pill in
  the player) now has **Add external subtitle**, which opens your device's file
  picker so you can choose an `.srt`, `.vtt`, `.ass` or `.ttml` you downloaded
  yourself. The file is checked for real captions before it is accepted (so a
  wrong pick answers with a message instead of nothing), shows up in the
  track list labelled with its own file name, and is **selected automatically**
  — you don't have to go and find it. Subtitles you add stay attached when the
  player moves to another server, and the existing **Sync** buttons re-time them
  exactly like a provider's subtitle. **Remove added subtitles** takes them back
  out again.
- **Download straight from a detail page.** Every episode row has a download
  icon, and the big button row under the cover has a second button next to
  Library. Tapping either one opens the player on that episode and puts its
  download chooser up as soon as a server is ready — you never have to watch the
  first seconds just to reach the download button. It is the same download flow
  as the player's own button (one code path, so quality/server choice, progress
  and the queue all behave exactly the same), and it covers movies, whose only
  row is the main one.
- **New: Settings → Backup & Restore.** *Back up Hikari data* writes one JSON
  file — your installed extensions, sources, repos, per-provider settings,
  history, favourites and app settings — into your phone's Downloads folder.
  *Restore from a backup* picks such a file back up and puts it on this device,
  then reloads the sources. The file contains **no videos and no passwords** (the
  offline copies, caches and the download queue are deliberately left out, so a
  backup stays small enough to e-mail to yourself), and a restore is only ever
  allowed to write inside the app's own extension folders, so a backup you were
  sent cannot touch anything else on your phone.
- **The Backup & Restore page is a folder in Settings**, like the others:
  Settings now reads Player · Sources & Extensions · Downloads · Appearance ·
  Privacy & Browsing · Logs & Diagnostics · Backup & Restore · About & Updates.
- **Your own extension's server goes first.** Tapping a film inside an extension
  used to play whichever installed provider happened to answer first — often
  another repo entirely. Now the extension the title was opened from is asked
  before the others, and playback holds for a bounded moment (8 seconds, only
  when that extension is installed and enabled) so its own link is the one that
  plays. It ends the instant that extension answers, and the search keeps running
  behind the player either way, so nothing is delayed by an extension that has
  nothing to say.
- **A dead server is now your decision, not a surprise.** New setting
  **Settings → Player → Playback start → *Ask me when a chosen server fails***
  (on by default). When a server *you* tapped in the list dies, Hikari no longer
  slides onto another one behind your back: it offers *Try next server*, *Choose
  another server*, or *Always switch automatically*, and switches on its own
  after 8 seconds if you don't answer, so playback is never stranded. A server
  Hikari picked by itself still fails over silently, exactly as before.
- **"Choose another server" continues the list, it does not restart it.** It
  re-opens the same grouped list you picked from — every server found so far,
  with the ones still arriving appended live — so the list picks up where it left
  off instead of running the whole search again. If the play started from a
  Download tap, the download chooser is re-armed too, so whichever server you
  land on offers to download it.
- **New copy is translated in all 21 languages** (including the new Download
  button's label and the whole server-failure prompt), and the backup page's own
  strings fall back to English only where a language has no word for it yet.

## 0.3.87

**The verification page can no longer open on its own — the cause was found in
the extension's own code this time — and the whole app has been restyled to the
soft, rounded glass look.**

- **The verification WebView that kept appearing by itself is closed at the
  source.** This was not Hikari opening it: CinemaCity's own code calls
  `showCinemacityCFBypassDialogAndWait()` the instant a request comes back
  Cloudflare-blocked, and that page is the one you were seeing (it even tagged
  itself `cinemacity_cf_bypass_auto`). It was gated on one of the extension's own
  settings — a `CINEMACITY_CF_WEBVIEW_ENABLED` switch. Two things were wrong:
  Hikari had no way to turn that switch off, and the plugin setting store it
  reads from was one Hikari was not writing to, so the extension never saw the
  value Hikari had. Both are fixed: a new **Extension verification pages** switch
  (Settings → Privacy & Browsing, **off by default**) forces every known
  verification-page switch off, and plugin settings now round-trip through the
  store the extensions actually read.
- **New setting: Extension verification pages.** Off — an extension cannot open
  its own verification page, no matter what it decides during a source load.
  On — extensions may open their own page as they were designed to. Hikari's own
  WebView (globe) button is unaffected either way: it still opens a verification
  page when, and only when, you tap it.
- **New theme: AMOLED Black.** True-black backgrounds with the same soft cards,
  for panels that look best with the backlight fully off.
- **The rounded glass look, everywhere.** Cards are 24–26dp with a 1px hairline
  border and a frosted fill, the app has a soft accent glow behind the top of
  the screen, the bottom bar is a floating rounded bar whose active tab is a
  filled pill, and list/setting icons sit in circular badges. The light theme
  keeps a real drop shadow so the same shapes still read as raised.
- **Lock now also stops the brightness and volume swipes.** While the player is
  locked, dragging up or down no longer changes brightness or volume — only the
  small lock icon reacts.

## 0.3.86

**The crash is fixed at its source, a verification page can never open on its own
again, the extension's own Cloudflare wording is gone from the app, and a locked
player stops covering the film.**

- **Crash fixed — `NoSuchMethodError: getWebViewUserAgent1()`, on every
  Cloudflare-fronted extension.** CloudStream's `CloudflareKiller` (built into
  the jar) is now shadowed by Hikari's own implementation with the exact same
  public method table, so plugin bytecode still links. The old class was
  compiled against an Android API the jar's own `WebViewResolver` stub never
  declared — every request that reached it died on an OkHttp dispatcher thread
  (`CloudflareKiller.proceed`), which is what kept putting "app crashed on a
  previous launch" on Home. As a second line of defence,
  `WebViewResolver.getWebViewUserAgent1()` now exists.
- **Nothing opens a WebView by itself.** The invisible Cloudflare solver is
  gone, and so is the jar's `CloudflareKiller`, which used to load the
  challenged site — a real page load — straight from the networking stack on
  any 403/503. A challenge is now simply recorded, and the verification happens
  only when you tap the WebView (globe) button. The "Solve Cloudflare checks
  automatically" setting is gone with it: there is nothing left to toggle.
- **Your verification now sticks.** The jar's class also called
  `CookieManager.removeAllCookies()` from its constructor, wiping the
  `cf_clearance` you had just earned — which is why verifying never seemed to
  change anything. Hikari's replacement only ever *reads* the cookie jar and
  reuses the clearance once it exists.
- **The extension's Cloudflare text is gone from the app.** *"Cloudflare
  blocked. Go to Settings 'n Bypass Cloudflare."* is a message about
  CloudStream's settings screen; it no longer appears in the server list, the
  per-extension diagnostics, or the player's "other repos" hint. A Cloudflare
  answer also no longer hides a whole provider from the source list.
- **On Home, a verification wall is explained in Hikari's own words.** Pick
  that extension as your provider and it says its site needs a verification and
  gives you an **Open WebView** button that opens it — the only place the
  message appears, because it is the only place you can act on it. The
  extension list says the same, in one line.
- **Locking the player no longer ruins the film.** The big padlock that used to
  sit in the middle of the picture all through the movie is gone; in its place
  is a small icon in the top-right corner, exactly where the Lock button lives
  in the controls, so tapping the same spot unlocks it again.
- **Brightness and volume keep working while locked** — swipe up/down on the
  left or right half as usual. What no longer works while locked is the
  accidental stuff: a double-tap can't seek, and a finger held down can't jump
  to 2x speed. A tap while locked says "Locked — tap the small lock icon to
  unlock" instead of looking like a dead screen.

## 0.3.85

**The extension crash is fixed, one broken mirror can no longer eat the whole
failover, the source search survives the player opening, Cloudflare-gated
servers stay hidden until they are verified, and the language you pick applies
on the first tap.**

- **Crash fixed — `ArrayIndexOutOfBoundsException` thrown out of an
  extension.** The report (`length=49; index=49` at `java.util.ArrayList.add`,
  raised inside a `Cs3BridgeProvider` callback while its coroutine was already
  cancelling) was a plugin mutating a plain `ArrayList` from more than one
  thread at once. Hikari can no longer be part of that race: the stream
  callbacks it hands an extension are now `CopyOnWriteArrayList`s, and calls
  into any one extension are serialised (a lock per extension id, so a wedged
  extension cannot block the others) — Hikari never has two calls inside the
  same extension at the same time.
- **"Loading server… then it fails", with only one dead mirror in the list.**
  A host that answers HTTP 500/404/410 (or refuses the connection) is now
  remembered for the session: its sibling rows — MovieBlast's 1080p/720p/360p
  all live on the same `mbfiles.mbaccess.site` — are skipped instead of being
  walked one 13-second error at a time, and a 5xx no longer spends the three
  header-variant retries first (no header set can fix a server error). The
  probe remembers the same answers, so a URL a probe already proved dead is
  skipped outright, and playback starts on the first *healthy* server rather
  than blindly on row 1.
- **The source search no longer stops early when the player opens.** The
  multi-extension search ran on the composition's scope, so tearing the detail
  screen down behind the player (low-memory devices) cancelled it mid-flight —
  the reported *"The search stopped early (LeftCompositionCancellationException)"*
  with an empty server list. It now runs on the application scope, keeps its own
  list of what it found, and only touches the screen's state while the screen is
  still alive.
- **Cloudflare-gated servers are only listed once the host is verified.**
  A source whose host answered a Cloudflare challenge and has no `cf_clearance`
  cookie yet is withheld from the list — so the servers shown are the ones that
  actually play. If you have already done the verification for that host
  (manually in the extension's WebView, or via the automatic solver), its
  servers appear normally again.
- **Language applies on the first tap.** Choosing a language recorded the
  choice *after* recreating the activity, on the dying composition's scope, so
  the write was cancelled and the app came back in the old language until the
  same option was picked again. The choice is now held in memory and persisted
  on the application scope *before* the locale is applied, so the first tap
  works, and the override is forgotten as soon as the store agrees.
- **Settings is fully translated.** The Settings index folders (Player, Sources
  & Extensions, Downloads, Appearance, Privacy & Browsing, Logs & Diagnostics,
  About & Updates, with their subtitles and blurbs) were never passed through
  the translator, and five strings whose keys had been stored with escaped
  quotes could never match — that is why whole cards stayed English. Both are
  fixed, and the 28 missing strings are translated in all 21 languages.
- **"System default (English)"** is now how the language picker names the
  follow-the-phone option, instead of an empty bracket, and it stays in English
  on purpose so it reads the same in every language.

## 0.3.84

**"Play as soon as the first server is found" now really plays on the first
server — and the loading cover says what it is waiting for.**

- **The remembered last-used server no longer overrides that choice.** When a
  title had been played before, the detail screen held playback back until
  *that* server appeared (a 10-second head start) even with "play as soon as the
  first server is found" selected — and if the head start expired before any
  server had arrived, every later arrival was still filtered out, so playback
  only started when the entire search had finished or not at all. The hold now
  applies only to the "wait for more servers first" choice, and the head start
  is a hard deadline: after it, the next server to arrive starts playback at
  once. The cover also says *"Waiting for your last used server (up to 10s)…"*
  instead of a generic sentence.
- **A "nothing found" status can no longer fail a session that did get
  servers.** The player's fail-fast check now also requires its own source list
  to be empty.
- **A dead first server no longer parks the whole list.** Server 1 answering
  HTTP 500 used to send the player into a same-server re-resolution that held
  playback on that one server (cover back to "Found N servers — still
  searching…") while the other servers already in hand sat untried — the
  "it found 8 servers but nothing played" report. That re-resolution now only
  runs when there is nothing else left to try; otherwise the player fails over
  to the next server immediately, and the cover says *"Reconnecting — <server>"*
  while it does.
- **Hikari no longer loads a Cloudflare check on its own.** Automatic
  (invisible) Cloudflare solving is now opt-in — Settings → *Solve Cloudflare
  checks automatically*, **off by default**. A challenged host is simply
  recorded so the UI can offer the deliberate globe-button verification instead.
  Three loop causes are fixed alongside it: Cloudflare's own challenge hosts and
  `/cdn-cgi/` endpoints are never treated as ad traffic (blocking them made the
  page re-request itself forever, in both the browsing view and the solver),
  the element blocker is never injected into a verification page (hiding the
  wrong iframe made the challenge re-render), and a renderer crash can no longer
  relaunch the web view over and over — one recovery at most, and never for a
  verification view.
- **The 20 app languages are real now.** The translation table the UI reads
  (`assets/i18n/…`) was never shipped, so every `tr("…")` lookup fell back to
  English and picking a language changed nothing. The UI is now translated into
  es, pt-BR, fr, de, it, ru, uk, tr, ar, hi, id, vi, th, ko, ja, zh-CN, pl, nl,
  el and he (plus the joke "mmmm… monke"). One file per language, loaded lazily,
  so a language switch costs a ~20 KB asset read instead of a 450 KB one.

## 0.3.83

**Build fix — 0.3.82 never produced an APK.** Two Kotlin compile errors in the
extensions screen stopped the release build; both are inside the new machine-
readable "bundle" repo importer and the i18n pass, so no shipped behaviour
changes.

- `for (((url, _), name) in fresh.zip(names))` — Kotlin's `for` destructuring
  grammar has no nested patterns (only lambda parameters and `val` allow them),
  so the parser rejected the line and every reference below it cascaded into
  "unresolved reference 'url'". It is now a plain indexed loop.
- `tr("CloudStream")` / `tr("Hikari")` / `tr("Nuvio")` were called inside the
  `LazyColumn` (a `LazyListScope`, not a composable scope). The three group
  titles are now resolved above the list and passed in.

## 0.3.82

**Three crashes/never-ends are gone: plugin toasts no longer kill the app, the
WebView's barcode-scan check no longer kills the app, and "Finding the best
server…" now tells you what is happening instead of spinning forever.**

- **Fixed the crash when a plugin shows a toast or a login/notice message.**
  `CommonActivity.showToast` (CloudStream's own toast — every plugin login
  dialog, "login succeeded", "stream not found" runs through it) inflates a
  generated ViewBinding class, `databinding/ToastBinding`. Hikari ships the
  CloudStream jar but not the `androidx.viewbinding` runtime, so the class could
  not be *linked* and ART reported it as
  `NoClassDefFoundError: Failed resolution of: …databinding/ToastBinding;` —
  killing the app on the main thread (seen on a Xiaomi 2109119DI). The jar's
  class is now excluded and replaced by Hikari's own `ToastBinding.java` (same
  name, same three members `CommonActivity` uses) which inflates a Hikari
  layout, and `androidx.databinding:viewbinding` + `androidx.cardview` are on
  the classpath so the class links. Startup also pre-warms it, so a future
  break shows up as a logged cause chain instead of a mystery crash.
- **Fixed the WebView GMS crash (repeated process death on a realme 5i).** The
  System WebView's bundled Play-Services shim reads
  `com.google.android.gms.version` from the manifest the moment a page touches
  the Shape Detection APIs (BarcodeDetector/FaceDetector — several
  streaming/Cloudflare pages do). Hikari never declared it, so its availability
  check threw `GooglePlayServicesMissingManifestValueException` on Chromium's
  in-proc GPU thread, rethrew through JNI and killed the process 5–40s after
  launch. The standard meta-data tag is now declared; the same check then answers
  normally and WebView simply skips the feature.
- **"Finding the best server…" no longer spins forever.** Two things were wrong.
  First, the search coroutine only signalled completion on its happy path — a
  throw or a cancelled collector skipped `markDone`, so the player sat on its
  cover until its 90-second safety timeout, with no text ever explaining
  anything. The signal is now in a `finally`, and the player fails fast the
  moment it receives a "nothing found" result *with the reason attached*.
  Second, the cover showed one frozen sentence for the whole search. It now
  shows live progress from the detail screen ("Searching 4 extensions…", "Found
  2 servers — starting playback…", "Re-extracting expired links…") plus a
  ticking elapsed-seconds counter, and when the search ends empty it says why:
  *"No playable server found after searching 4 extensions — only 4 of your 227
  installed extensions are enabled"*, followed by the provider/network note when
  there is one. The same reason is repeated on the player's error card.
  Note that this also explains the other half of the report: with only a handful
  of extensions installed, a title like "Gandhari" genuinely has no servers —
  the search log showed `Search: done "Gandhari" → 0 servers`, so there was
  nothing to play. The toggle only decides *when* playback starts, not whether
  any server exists.

## 0.3.81

**The downloads/build branch is now separate, WebView popups are tamed, the app
speaks 21 languages, the server chooser's repo short names work again, and the
StreamPlay crash now explains itself.**

- **Popups are blocked, not just redirected.** Hikari's built-in browser already
  stopped cross-site redirects, but a site could still open an ad window as a new
  tab (`window.open` / a target=_blank link) and the popup opened full-screen over
  the video. Popups that are NOT triggered by a real tap are now dropped, and the
  ad-cleanup script hides the usual popup/interstitial/overlay-ad containers and
  the common pop/ad iframes (popads, popcash, propellerads, adnxs, exoclick,
  juicyads, trafficjunky).
- **The Cloudflare verification WebView no longer throws itself open.** With the
  hidden (off-screen) solve doing the work, the visible verify view only appears
  when you ask for it (the globe button), never automatically mid-browse.
- **App language.** Settings → Appearance → App language offers 21 languages plus
  "System default". Choosing one switches the app's resource-backed text — the
  player overlay's Episodes/Source/Quality/Audio/Subtitles/Rotate/Skip
  Intro/Enhance pills and the "Tap to play" hint, every content description, and
  the language card itself — and it is remembered across launches. There is also a
  fun extra: `mmmm... monke` (🙈). The choice is applied through Android's per-app
  locale API and Android 13+ also lists it in the system settings screen.
- **Repo short names work again.** "Add repository" now normalises a URL that
  points at a branch head (`…/refs/heads/builds/repo.json`) to the canonical raw
  form and tries both, so repos whose URL is stored the long way (phisher98's
  builds branch, the mega-repo bundles) import instead of failing with "an HTML
  page instead of repo.json". `phisher` and a batch of new CS3 short names
  (csOfficial, csx, cnc, aniyomi, german, italian, turkish, indo, skillshare,
  luna, redowan, dogior, storm, cinephile, saimuelRepo, fstream, …) resolve to
  working repo.json URLs; the nuvio manifest for phisher is under
  `phishernuvio`.
- **StreamPlay's NoClassDefFoundError now shows its cause.** The plugin load error
  log recorded only "NoClassDefFoundError:
  com.lagradost.cloudstream3.syncproviders.AccountManager" with no reason. The
  recorder now unwraps the cause chain, and the sync-providers class the plugin
  touches is pre-warmed at startup, so the log line names the real missing
  class/initializer instead of the symptom. CloudStreamApp also gains the boxed
  `removeKeys` return type and the `openBrowser` default-argument bridge some
  plugins call.

## 0.3.80

**Every row in the server chooser is now the same small capsule.**

- **Fixed the oversized, extra-round pill on rows with a long server name.** A
  row's roundness comes from its height (the corner radius is clamped to half of
  it), and the label was allowed to wrap onto a second line. So a long
  "Provider (Repo) · Plugin" name — the first Hikari servers, right under the
  CloudStream ones — made a two-line label plus the host line: a visibly fatter,
  rounder capsule than its neighbours. The label is now a single line with the
  tail ellipsised, and the host line is single-line too, so every row on screen
  is exactly one label line plus one host line.

## 0.3.79

**The crash on opening servers is fixed, "my other repo's servers are missing" is
fixed for real, and a Cloudflare-blocked site now says so and offers the
verification that was missing.**

- **Fixed the crash `ForegroundServiceDidNotStartInTimeException`** (the app dying
  right after you tap Play / open a title). The background-work service stopped
  itself without ever calling `startForeground()` when its work registry happened
  to be empty by the time `onStartCommand` ran — which Android punishes by killing
  the whole process. It now always steps into the foreground first and only then
  decides there is nothing to do. (A search that finished almost instantly, e.g. a
  cache hit, was the usual trigger.) The service also logs a `startForeground`
  failure instead of swallowing it, so this can never again fail silently.
- **Fixed the real reason another repo's servers were missing: the tail of the
  list was never even asked.** The pass ran search AND extraction per repo through
  the same 18 slots, so with 182 `.hiki` + 49 CloudStream repos installed the
  repos at the back of the queue never got a turn — and the hint still said
  **"Asked 231 other repos … all done, none with servers"**, because a repo was
  counted as "asked" the moment it was *queued*. Now:
  - **Two phases.** Phase 1 searches EVERY installed extension (28 at a time);
    phase 2 extracts only the ones that matched, through its own slots. A repo
    that matched can no longer hold a search slot while it fetches its episode
    list.
  - **"Asked" means asked.** The hint now reports `Asked N of M`, and repos the
    pass did not reach are named as **"never reached (the pass ended before
    asking it)"** instead of being silently counted as searched.
  - **The pass is longer (150s) and reports "stopped early (N never finished)"**
    rather than "all done" when it runs out of time.
- **A Cloudflare-blocked site is no longer disguised as "this repo has no such
  title".** When a challenge could not be passed automatically, the host is
  recorded and the chooser's hint / the "no sources" panel now say
  **"Cloudflare check needed on <host> — open the globe (verify) on Home, then
  search again"**.
- **The verification dialog appears when it is actually needed.** A challenge
  that needs a human click now opens the verify WebView for that host *when you
  are waiting on that one site* (play/extract). During a bulk search it is
  deliberately not popped — the dialog would land over whatever you are doing —
  the host is reported instead.
- **Cloudflare can no longer starve a search.** While the pass runs, each hidden
  solve gets a short budget and at most two run at once (a blocking solve used to
  hold a search slot for its full 20s and the search timeout could not preempt
  it). A duplicated repo entry can also no longer launch two lookups against the
  same provider and report one as "couldn't be searched".

## 0.3.78

**"No servers from my other repos" now says WHY — and a search that never really
ran is retried instead of being written off.**

- **Every repo's answer is now truthful.** Until now the cross pass wrote
  "no matching title in this repo" for three completely different situations:
  the repo really does not carry the show, the repo's search **threw**, and the
  repo's search **timed out** (a cold `.hiki`/`.cs3` load — the first call has to
  spin up the plugin). So a repo that DOES carry the show looked identical to one
  that does not, and there was no way to tell from the log which had happened.
  Each case now reports itself: "search timed out after 20s", "search failed:
  <reason>", "N search result(s), none of them "<title>" (best match 12/40)", or
  a plain empty page.
- **The chooser's hint now counts the reasons across the whole pass** — e.g.
  `Asked 231 other repos (CloudStream 49, Hikari 182) — all done, none with
  servers · 200 no such title, 28 could not load`. A pass where most repos
  answered "no such title" is a catalog/matching story; a pass where most
  "could not load" is a broken-extension story. You can see which it is without
  sending a log.
- **A failed or timed-out search is retried once**, and the search/episode
  timeouts went 15s → 20s (the cold plugin load of a native extension has to fit
  inside them). A repo whose first search died is asked again rather than losing
  its servers for the whole play.
- **A broken extension can no longer masquerade as "this repo hasn't got the
  show".** A `.hiki` that will not load now reports "Extension failed to load —
  <reason>"; a CloudStream plugin that exists only to extract links says "This
  extension is extractor-only (no search)"; a missing/unregistered plugin says
  so too. All of it lands in the log and in the hint's `e.g.` example.
- **The end-of-pass log line is now a full summary**: asked / with servers /
  empty, the reason breakdown, and one real example per kind of failure.

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
