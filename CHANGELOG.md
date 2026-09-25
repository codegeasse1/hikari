## 0.10.36

### Fixed

- **A series now plays on a Stremio addon that lists dozens of servers for it in Stremio itself.**
  The report was exact: *"movies play, but any series episode says no playable source — the same
  PenguPlay addon in Stremio shows 79 servers"*. The addon was not at fault; three protocol
  mistakes in `StremioAddon.getStreams` were, and every one of them only bites a series.
  The `type` segment of `/stream/{type}/{id}` was tried with the item's own `rawType` first — which
  for a title browsed from Hikari's own Home is TMDB's `tv`, a spelling PenguPlay does not serve
  videos from (its `tv` resource is IPTV-only), so a series' whole fan-out was spent on a request the
  addon answers with nothing while a movie's spelling happened to work. The segments the addon
  actually declares *for the id in hand* now come first (matching each resource's own `idPrefixes`,
  not just the manifest's), ordered by the item's kind. The walk also used to STOP at the first
  non-empty answer, and a Stremio "link" row (`externalUrl`/`ytId` — "watch it on the site") parses
  as a stream: a spelling that answered only with a link ended the search before the spelling that
  carries the servers was ever asked, which is where *"Found 1 link in your extensions, but none
  could be turned into a video"* came from. The walk now goes past a link-only answer and keeps
  only a row that can actually play. Last, the id the addon is asked about was not translated into a
  namespace it knows: the numeric part of a `tmdb:…` id (what an addon's own catalogue rows carry)
  was read with `takeWhile { it.isDigit() }` on the RAW id, which sees no digits at all, so an addon
  declaring only `tt` (Torrentio, Cinemeta) was asked about a namespace it does not know; both
  spellings are now built and offered, round-robin across the type segments. A series with no
  episode list at all (normal for a stream-only addon) also plays now: the addon is asked for season
  1 episode 1, and the `/meta` fallback for a `tmdb:` id asks TMDB for the list the addon never
  published.
- **Playback starts the instant ONE working server is found.** The player deliberately held for the
  title's own extension for a couple of seconds before starting (so "if I'm on MovieBox, play
  MovieBox's server first"), and that hold did not know the difference between "waiting for the
  right server" and "a server has already been probed and answered with a real video stream". It
  does now: a source whose URL a probe has already resolved to a video/HLS/DASH manifest ends the
  hold, so the fastest working server starts the video immediately while every other server keeps
  arriving in the background and remains in the player's server list to switch to. Row choice also
  prefers a probe-verified server over one that merely looks most promising.
- **Installing an extension no longer leaves the app heavy for a while afterwards.** The download
  behind an install was a blocking OkHttp call inside the install's own 20-second budget: when the
  budget ran out (or the install was cancelled, e.g. by starting the next one), the timeout
  abandoned the WAIT but the read kept running on its IO thread to its own 30-second timeout, so a
  cancelled or overrunning install left a leaked download competing with the install that replaced
  it. Extension downloads are now enqueued and actually CANCELLED when the coroutine that owns them
  ends (`Http.fetchBytesCancellable` / `downloadBytesCancellable`), and the between-attempt pause is
  a cancellable `delay` instead of a `Thread.sleep`. The Nuvio first-run provider seeding uses the
  same path.
- **"Nothing here right now" on a catalogue that plainly has items.** A catalogue host answers HTTP
  200 with an empty list while its upstream is rate-limited or cold-starting (StremioLabAR's
  Trakt/MDBList lists do exactly this, and say so in an `error` field), so a row full of posters on
  Home could be followed by "Show all" showing nothing a second later — the two reads were of the
  same catalogue and the second one believed a transient empty answer.
  `ContentRepository.loadCatalogPage` is now the one funnel every catalogue read goes through, for
  every engine: the page the user is LOOKING at re-asks once after a short pause before believing an
  empty answer, and page 1 falls back to the last non-empty answer that catalogue gave (from a
  process-wide cache, because Home's row and the catalogue's own page are different repository
  instances). The addon's own words are shown when it has any (`error`, `totalItems: 0`, a non-JSON
  body, a timeout — each its own sentence instead of "returned no items"), and a catalogue that can
  only be answered WITH a query is no longer offered as a Home row, since such a row is empty by
  construction (`StremioAddon.homeCatalogs`; it is still searched globally, and `search()` now skips
  catalogues that declare extras and no `search`).
- **The whole TV layout answers the remote.** The TV layer was in place, but a bare
  `Switch`/`Slider`/`Checkbox` at the far end of a full-width row is skipped by Compose's focus
  search, so a handful of settings could not be reached or flipped from a D-pad at all. Every
  remaining one is now wrapped in the app's own `tvToggle`/`tvAdjust` (Settings, Collections,
  Extensions, History, Player controls, the library-category picker and the reader's settings sheet),
  which makes the control focusable, flips a toggle on centre/left/right and steps a slider with the
  same commit a drag-release runs.

### Notes

- The series fix is Stremio-protocol-specific by nature: the other engines (nuvio, CloudStream,
  Aniyomi, SkyStream, universal scrapers) address their own site's API and have no `type` segment or
  id-namespace handshake to get wrong, so there is nothing analogous to change in them. What is
  shared — the catalogue funnel above, the id-resolving engines being asked for a title that came
  from Hikari's own rows (0.10.35, `docs/SEARCH.md` invariants 10-11) and the episode-list fallback
  through TMDB — applies to all of them.
- The reader's live chrome keeps its own remote handling on purpose: left/right on the page scrubber
  and the auto-scroll panel is page navigation, so those two sliders were deliberately left alone
  (the reader's settings sheet, which is where settings belong, is covered).
## 0.10.35

### Fixed

- **A title opened from Hikari's own Home/Search is now searched by the playback addons — PenguPlay,
  Torrentio, the nuvio engines — instead of by nobody.** Titles browsed from Home, Search,
  Collections and the nuvio catalogue imports carry `providerId = "tmdb"` (`TmdbMeta`), and
  `manager.byId("tmdb")` names no provider at all. Every target list in `streamsForInner` was built
  from "the origin", so for such a title `origin` was null and the pass asked **nobody**: with
  "Server search: only this extension" on there was not even a cross pass to fall back on, and the
  app reported "no playable server found" over a Stremio addon that lists dozens of servers for the
  same title in Stremio itself. (An origin the user has since uninstalled or switched off is the
  same case.) The id-resolving engines — Stremio addons and nuvio engines, the ones that take the
  item's own `tmdb:`/`tt` id and need no title search — are now asked for such a title, whatever the
  scope switch says: exactly what the real client asks for a catalogue id. A title opened from an
  extension is unaffected (its own repo is still the only thing "only this extension" asks).
- **An addon's "link" rows are turned into servers as they arrive, not six of them at the end of a
  pass.** Stremio's protocol lets a stream be a `ytId` (a YouTube video) or an `externalUrl` (the
  addon's "watch it here" page), and `DetailScreen.playableEvery` filters both out of playback while
  the source sheet lists them — so an addon answering in those shapes showed a full server list and
  then reported "no playable server found". The resolution pass ran once, at the end of a lookup, on
  the first six rows and inside a twenty-second budget; anything past that stayed unplayable forever,
  and nothing arriving through the live feed was resolved at all. Link rows are now resolved in the
  background the moment they are seen (any number of them, six at a time, per-row timeout), and the
  servers they resolve to are pushed back through the same live feed, so they join the list and
  reach an already-open player like any provider's own links. A row whose resolution genuinely finds
  nothing is retried on the next lookup instead of being written off.
- **A Stremio addon's `magnet:` stream URL is played as a torrent.** The protocol names a torrent
  either by `infoHash` or by a `magnet:`/`torrent:` URL in `url`, and addons use both — the magnet
  form was read as a plain direct link, so ExoPlayer was handed `magnet:…` and every row of such an
  addon failed. The `xt=urn:btih:` hash, the `index=` file and the `tr=` trackers are parsed out and
  it goes to the same torrent engine `infoHash` rows use.
- **"No playable server found" is no longer printed over a list of servers.** The verdict text was
  built purely from `playableEvery(found).isEmpty()`, so a search whose rows were all links it could
  not resolve said "no playable server found after searching 517 extensions" while the sheet showed
  those very rows. The note now says what actually happened ("Found N links in your playback addons,
  but none could be turned into a video"), a title from Hikari's own catalogue says which playback
  addons were asked instead of blaming "the extension this title came from" (there is none), and the
  player fails fast on that wording too instead of calling it a cut-short search.
- **The "asked 3 · 2 don't carry it" tail of that note can no longer describe a different search.**
  `crossSummary()` reads the newest published pass tally, and a pass with nothing to search returns
  without publishing — so the numbers under THIS title's verdict could belong to another title's
  search entirely. The tally now records the title it describes and the summary refuses to print for
  a different one.
- **Telegram: a tag post's whole batch of videos is found, not the first 20 messages / 40 videos.**
  A tag in Saved Messages is a word written once and then a batch of uploads under it — the reported
  case is 200+ videos under one word — but the walk forward from the hit asked TDLib for 20 newer
  messages at most and stopped at 40 videos, so a fifth of the batch was listed and the rest could
  not be reached from any page. The walk now pages forward properly (100 messages a page, ~5 900
  messages at most per post) until the post's own tail ends, and it does not stop dead at a sticker
  or an uncaptioned photo in the middle of a batch. Each chat-text hit is also no longer cut at the
  page size with the cursor moved on to the NEXT hit, which was what made "search further back"
  return the same first rows forever.
- **Telegram: "That is the whole chat." is only said when the chat really was walked to the end.**
  The history walk treated a page shorter than the requested 100 as the end. TDLib's own
  documentation for `GetChatHistory` (and `SearchChatMessages`) says the number of returned messages
  "is chosen by TDLib and can be smaller than the specified limit", so a short page is normal — and
  the walk declared the chat finished at 181 posts with the user's 200+ videos unfindable from there
  on. The walk now ends on a page with nothing new in it, which is what the end of a chat looks like.
- **Telegram: "Look in: Both" now means both places.** It ran only the file-name walk, so on the
  reported case — one word written once with 200 videos posted under it, two of which are also named
  after it — "Post text" found the batch and "Both" found less than it did, where "Both" should be
  the two sets added together (the count the user expected). It is now the union of Telegram's own
  post-text search (each hit expanded to the videos under it) and the file-name walk, deduped by
  message. A caption is post text, so it belongs to the text half.
- **Telegram: a chat search walks the whole chat by itself.** It loaded one page and put the rest
  behind a "Search further back" button under the list, so a tag with hundreds of matches showed a
  fraction of them until the user found and tapped that button. It now pages until the chat is really
  exhausted, showing the results as they fill in and a live "Looking through this chat… N post(s) so
  far" line, and the footer reports the real number of posts looked at and videos found. The button
  remains for the page ceiling only.

### Notes

- **"Only this extension" cannot mean "only PenguPlay" for a title from Home.** The setting scopes a
  lookup to the repo the title was OPENED FROM; a Stremio addon like PenguPlay has no catalogue of
  its own (`"catalogs": []`), so no title can ever be opened *from* it. What the switch does for a
  Home-browsed title now is ask the playback addons that can resolve it by id (see above) — if the
  intent is "play from this addon and nothing else", that is the thing to use instead: mark the
  addon under Settings → Playback & Servers → Exception extensions, which is asked for every title
  opened anywhere else.

## 0.10.34

### Fixed

- **Telegram: tapping a channel or a group now opens it.** The "which chat is open" state used `-1`
  as its "nothing open" value and tested it with `openChat > 0`, but a channel or a supergroup id is
  NEGATIVE — Saved Messages is the only chat on that screen with a positive id — so tapping a channel
  stored its own negative id and the test then read as "nothing open": the page stayed on the list and
  only Saved Messages ever worked. The sentinel is `0` now (no chat has that id) and the test is
  `!= 0`, so every row in the list opens what it names.
- **Telegram: searching a tag inside a chat finds the videos that were posted under it.** A tag is
  usually not the video: the user sends the text ("iviroses"), then posts the videos beneath it (or
  attaches them as an album whose first message carries the caption). The search asked TDLib for
  VIDEO messages whose text matched, and those videos carry no text at all — so a tag that finds its
  video in Telegram found nothing here. The search now asks for the POST (`SearchMessagesFilterEmpty`)
  and resolves each hit to the videos that belong to it: the message itself when it is a video, plus
  the run of videos that follows it, stopping at the first message that is not one of them. Each
  video found this way is labelled with the tag post's own text, the "Look in" scope reads "Post text"
  instead of "Chat text" (that is what it was always doing), and the empty-state sentence says so too.
- **Installing one extension, or twenty at once, no longer stutters and no longer ends in a crash.**
  Four things, all in the same chain. Every install asked for the provider list to be rebuilt, and a
  bulk run rebuilt it after every single extension — one instantiation per installed extension, over
  and over, on a machine already downloading and dex-loading (quadratic work: twenty or thirty
  extensions was the crash). A bulk run now owns its own single rebuild, and an install's request is
  ignored while one is running. The update check re-read EACH installed extension's whole file into
  memory and hashed it on every provider-list change; it streams the file and remembers
  `(size, modified, sha256)` per path, so an unchanged file is never hashed twice. A provider-list
  rebuild whose configs are identical to the last one is now skipped instead of re-emitting a new
  list (which re-ran the screens' effects and threw away live extension instances), and an install
  set that has not changed is no longer published as a change.
- **Stremio addons: YouTube and "external" streams play instead of reporting no playable source.**
  Stremio names a stream in three ways — a direct URL, a torrent, or a `ytId` / `externalUrl` — and
  the last two were filtered out one screen later, so an addon whose streams are all of those kinds
  answered the search and then played nothing ("no playable source found" from an addon that plays in
  Stremio itself; every trailer row is a `ytId`). Both are resolved at the end of a lookup now:
  `ytId` through NewPipeExtractor (which had never been initialised in this app — `NewPipe.init`
  needs a downloader and nothing had ever called it, so the jar's YouTube extractor could not have
  worked in any path), and `externalUrl` through the same fallback engine every other embed uses.
  Only MUXED YouTube formats are offered (progressive, then HLS for a live stream, then YouTube's own
  DASH manifest, which carries its audio inside the one document) — NewPipe's video-only renditions
  need a separate audio track this player cannot attach yet, and a silent video is a worse answer
  than an honest one. The pass is bounded: at most six rows, twelve seconds each, twenty seconds
  overall, and a row that resolves to nothing is left exactly as it was.
- **Trackers: a refused app id is explained instead of opening a username/password box.** MyAnimeList
  answers an authorize request whose client id it does not know with HTTP 401 plus an
  `WWW-Authenticate: Basic realm="OAuth"` challenge, so the browser (and the WebView) render the
  device's own "Sign in" dialog — the credential prompt that "does not even feel like the real login
  page", and that cannot work whatever is typed into it. The dialog now asks the service FIRST (the
  same request as a plain GET, which comes back as a status code and a readable error body) and says
  what was actually refused — for MyAnimeList, that the Client ID is the 32-character value and not
  the Client Secret, and that the app's redirect URL must be exactly `hikari://oauth`. The WebView
  also cancels an HTTP-auth challenge outright and reports it, as a backstop.
- **Trackers: a sign-in finished in the browser now completes in the app.** A `hikari://oauth` link
  was only ever finishable by the dialog that started it, because the `state` the service echoes back
  is generated there — and for MyAnimeList that same value IS the PKCE `code_verifier` the token
  exchange needs. So a link that arrived after the app was closed (or after Android reclaimed it)
  ended in "paste it into the code field". The pending sign-in (service, client id, state) is
  persisted when the login page opens and cleared when the sign-in finishes, and MainActivity
  completes the code exchange itself, checking the echoed state against the stored one first.

### Notes

- **AnimeOnline.Ninja (Aniyomi) — no defect found on Hikari's side.** The poster and the episode list
  were checked point by point against the extension's own code and against the live site: the site's
  inner pages still serve the DooPlay markup the extension selects (`div.sheader div.poster img` with
  the real URL in `data-src`, `div#seasons > div.se-c`, `ul.episodios > li`), `abs:` attributes
  resolve because the document is parsed with its base URI, Hikari ships a working
  `app.cash.quickjs` bridge (the class the extension's VRF interceptor links against), the
  `wsidchk` interstitial family is solved, and the `Injekt` singletons an extension reaches for
  (`Application`, `NetworkHelper`, `JavaScriptEngine`) are all registered. The extension's own repo
  rewrote this source on 2026-08-31 ("migrate to `ParsedAnimeHttpLegacySource`", plus "fix 6
  top-voted broken sources"), so an installed build older than that — or one whose requests hit the
  site's challenge — is what to check first: update the extension from its repo (or reinstall it) and
  try again, and the app's own reason line for the source will say what the extension reported.

## 0.10.33

### Fixed

- **Telegram: the video that never started downloading now plays — and starts instantly.** The
  previous release made the data source wait only for the bytes a read needs, which was the right
  shape, but the readiness question itself was being asked of the wrong number. TDLib's local file
  reports two sizes, and this code took the LARGER of them as "on disk": `downloadedPrefixSize`,
  which is measured from `downloadOffset` (so it answers 0 for a seek into the middle of a film
  however much of the file is really there), and `downloadedSize`, which TDLib's own documentation
  says is "only for calculating download progress — the actual file size may be bigger, and some
  parts of it may contain garbage". A read that was told its bytes were ready then read a file that
  did not have them yet, returned zero, and ExoPlayer treated a zero as "no data at all" and sat in
  buffering until its watchdog blamed the server — the reported "still buffering after 60s". Three
  things change. Readiness is now `GetFileDownloadedPrefixSize`, TDLib's own
  `FileNode::downloaded_prefix`, which walks the file's downloaded-parts bitmask and answers for an
  ARBITRARY offset, seeks included. The download is asked for once per read position and left alone
  for 24 MiB at a time, because a `downloadFile` with a different offset CANCELS the range TDLib is
  already fetching (`FileNode::set_download_offset` → `update_downloaded_part`) — renewing every
  2 MiB was restarting the download just ahead of the playhead, so it could never run ahead of it.
  And `read` may no longer return 0: it re-asks, and only a real failure throws.
- **Telegram: a refusal is reported instead of waited out, and an expired file link is repaired.**
  `downloadFile`'s RESULT is where TDLib reports that it will not do the download at all — an expired
  file reference (`Can't download file: have no valid file reference`), a file from a torn-down
  session — and the result handler was `null`, so the failure was invisible and the download simply
  never started. It is recorded per file now, and the player is told the reason in about a second
  instead of buffering for a minute. The URI also carries the post the video came from
  (`hikari-td://file?id=…&chat=…&msg=…`), so a download that fails is retried once after re-reading
  that post — which is exactly how TDLib is handed a fresh file reference. Finally, tapping a video
  starts the download immediately (before the player activity exists), so the cold start — resolving
  the reference and pulling the first part out of Telegram — happens while the player is still being
  built.
- **Stremio addons: a fake server can no longer end the search, and a series is walked in every id
  spelling it was asked for.** An addon that cannot serve a request answers with a ROW rather than an
  empty list — `{"name":"PenguPlay","title":"You must sign in","url":"…/signin.mp4"}` — which parses
  as a perfectly ordinary direct stream. The search stopped at the first non-empty answer, so a
  series it could not resolve ended on that fake row (reported as "no playable server found" when the
  row was then rejected downstream). Rows that are a message rather than a video are filtered out and
  remembered as the reason, and the walk continues. The id walk is also round-robin across spellings
  now (`tmdb:…:s:e` and `tt…:s:e`, each with its most likely type segment before either gets its
  second), because walking one spelling to exhaustion first is what left the other — the one some
  addons can actually resolve — never tried at all when the fan-out was capped.
- **Television: the page is no longer inset by a navigation rail that is not on screen.** The rail's
  width was subtracted from EVERY television page, but the rail itself is drawn only on the tab
  routes — a detail page (`showBar == false`) was pushed away from an edge with nothing on it,
  leaving a rail's width of bare backdrop down the side of the page. That is the band of empty screen
  beside the detail page on a television.
- **Television: every control a remote is expected to reach now works.** Three separate symptoms, one
  cause: a toggle is not a D-pad target (Compose's focus search walks past the small switch at the
  end of a full-width row, so the focus "skipped straight to the next option"), a slider has no drag
  on a remote, and a text field eats the arrow keys — the caret is already at the end, so the focus
  cannot leave the box and the remote appears to stop working. Switches now answer the D-pad
  themselves (select presses, left/right flips), sliders step with left/right through the same commit
  a drag's release runs (so the setting is really saved), and text fields hand the arrows to the
  focus system — always up/down, and left/right once the field is empty. This is applied across the
  app: settings, extension and repo URL fields, IPTV fields, dialogs, search bars, and the manga
  reader's zoom slider.
- **Television: the screen-edge gap defaults to 0.** Modern Android TV, Google TV and Fire OS boxes
  draw the whole frame; the ones that crop it can raise it in Settings → TV & Remote.

## 0.10.32

### Fixed

- **Telegram videos play on the first tap instead of "Server is not responding (still buffering
  after 20s)".** The data source that streams a video out of TDLib made ExoPlayer's first read wait
  until a FULL 2 MiB chunk was on disk — half a minute on a 76 KB/s phone connection, and on a
  slower one it never arrived — while the player's watchdog wrote the server off after 20s. It now
  waits only for the bytes the read in front of it actually needs (a faststart video's header is a
  few dozen kilobytes), asks TDLib for a 2 MiB window aligned to that read and KEEPS the window so
  the download still runs ahead of playback, and a Telegram source gets a 60s budget instead of the
  20s an HTTP server is held to (a TDLib fetch has no CDN in front of it, and resolving the file
  reference is itself a round trip). Seeking is honest too: TDLib counts only bytes actually
  written and a range download does not advance the file's prefix, so a jump into the middle now
  measures that window's own coverage instead of waiting for the prefix to reach it.
- **Stremio addons: series play, not just movies.** A series is always asked for as a video id
  (`tt…:season:episode`), and the id it is asked WITH is now one the addon declared — both spellings
  when it declares both, `tmdb:` first because that is the id the item already carries. TMDB's
  `/tv/{id}/external_ids` has no `imdb_id` at all for a large share of shows (most anime, most
  non-English series) while a movie's nearly always has one, so an IMDb-only translation left those
  series being asked with a bare numeric id that an addon like PenguPlay — which declares `tt` and
  `tmdb:` — answers with nothing. A series whose episode list could not be loaded is also played as
  season 1 episode 1 rather than with no episode at all, and the `/stream/{type}/` segments the
  manifest declares are tried before the guessed ones. Reported as "movies play, series say no
  playable source found".
- **Manga: a failed chapter list now says WHY.** The extension's own RxJava-1 HTTP path hangs an
  `OnNextValue` marker off the END of the cause chain, so walking to the deepest cause printed the
  wrapper — "x chapters failed: OnNextValue: OnError while emitting onNext value: okhttp" — and the
  line was then cut at 72 characters, before the actual reason. The wrappers are skipped now and the
  real error is reported; a linkage error (an extension built against a different version of the
  app's HTTP library) is called out in plain words, because that is a compatibility problem and not
  the site being down. The row has room for the whole sentence.

## 0.10.31

### Fixed

- **Trackers: the sign-in finished in your BROWSER now comes back to the app.** The dialog's own
  WebView catches the redirect itself, which is why signing in inside the app worked — but the
  button beside its paste field ("Open the login page in your browser") exists for the services
  whose Cloudflare check refuses a WebView, and the browser had *nobody to hand the link to*: it
  reaches the end of the sign-in with an access token in the URL and the app had never declared
  that it can receive `hikari://oauth`, so the page simply sat there. The app claims that URL now,
  the window is `singleTask` so the link lands in the window that is already running (the dialog
  that started the sign-in is still there, holding the `state` the service checks — MyAnimeList's
  `code_verifier`), and the dialog finishes it through the paste path it already had. With no
  dialog on screen the app finishes what it can on its own — AniList's token flow needs nothing but
  the stored client id — and says where to paste the link for the code-based ones instead of
  pretending nothing arrived.
- **Trackers: the login page now FITS, and whatever does not fit can be reached.** A WebView lays
  a desktop-width page out at its natural width inside a phone-width box and then does not let you
  pan sideways, so AniList's page (its sign-in form plus the Turnstile widget) ran off the right
  edge with no way to get to the other half — including the "verify you are human" box and the
  sign-in button themselves. The page is laid out at its own width and scaled to the box
  (`useWideViewPort` + `loadWithOverviewMode`), pinch-zoom is on, the box is taller, and the
  guidance now names the `https://anilist.co/api/v2/oauth/pin` redirect for the accounts that
  would rather read the token as text.
- **Extensions: the empty catalogue, for real this time.** The newer keiyoushi/Aniyomi sources
  build their own client out of the one the app hands them and, before using it, assert *by class
  name* that it contains a `CloudflareInterceptor` — Hikari's Cloudflare handling lived under a
  different name, so that assert threw on the first request of the first catalogue and the
  extension returned nothing: no titles, no verification page, nothing on screen to explain it,
  for a whole family of sources at once. The client now carries one under the name they look for.
  The same family also compiles against **OkHttp 5** (`okhttp3.CompressionInterceptor`,
  `okhttp3.brotli.Brotli`, `okhttp3.zstd.Zstd`, `com.squareup.zstd.okio`), and against a jsoup
  newer than the 4.x one this app shipped — referencing a class the host does not have is a
  `NoClassDefFoundError` in the extension, which is also an empty catalogue with nothing on
  screen. OkHttp is 5.4.0 with brotli and zstd, jsoup is 1.22.2, and the zstd packages are kept
  whole in the release build's shrinking rules (external bytecode calls them by name, so R8 cannot
  see the reference and would remove them).
- **Catalogues now say WHY they are empty.** Every provider already records a one-line outcome
  (`✗ HTTP 403`, `✗ Cloudflare challenge`, `✗ the site answered, but it came back with no titles`)
  and the empty screen threw it away, so a broken extension and an extension with nothing to show
  looked identical. The reason is drawn under the empty-state text now, and an empty page is
  recorded as "the site answered but its markup may have changed" instead of as a success with
  zero titles. The load also no longer swallows `CancellationException` — a cancelled load used to
  be recorded as a *finished* one, clearing the spinner while its replacement was still running.
- **Telegram: videos in Saved Messages played for nobody** — "Playback failed —
  `ExoPlaybackException [ERROR_CODE_IO_UNSPECIFIED]` Telegram is not available". Nothing was wrong
  with the id or the network: TDLib only tells the app about a file once a download exists, and a
  video the user has never watched has never been downloaded, so the app knew nothing about it —
  and the `getFile` fallback it used instead can never answer, because TDLib's synchronous
  `execute` only serves queries that need no database. Every Telegram video therefore failed on
  the first tap. The file is now *asked* for properly (an asynchronous `getFile`, cached), so
  playback starts on the first tap.
- **Telegram: searching inside a chat, without leaving it.** Every chat and Saved Messages now has
  a search button in its header, and the results appear in that same chat page — nothing jumps to
  the app's Search tab, which is a different question entirely ("which engine has this title").
  The search has three scopes, picked right there: **Chat text** (the caption a batch of videos was
  posted under — Telegram's own index, so it searches the WHOLE chat and is complete), **Video
  names** (the file name, which Telegram cannot index, so the chat's history is walked and the
  footer says exactly how many posts it looked at, with "Search further back" to continue), or
  **Both**. That is the "I captioned that batch abc, show me the batch" case working as a tag.
- **Telegram: real thumbnails, and three ways to read a chat.** Rows drew the tiny inline
  *minithumbnail* that plenty of posts do not carry — which is why the list was a wall of black
  boxes. The video's actual TDLib thumbnail is fetched now (with the inline preimage as the instant
  placeholder). And the chat's videos can be read as a **list**, as **tiles**, or as **posters** —
  the choice is one tap in the chat header and is remembered.
- **Player: the fit/crop button also stretches.** The fullscreen-adjacent resize control cycled
  between fit and crop; it now also cycles to **stretch** (fill the screen, ignoring the aspect
  ratio) for the sources whose bars are worse than a slightly wrong shape, and the menu entry names
  all three.
- **Television: the player's own menus can be walked with the remote.** A D-pad can only land on a
  control that is *focusable*, and `setOnClickListener` makes a view clickable while leaving it
  unfocusable — which is the state of every pill, chip, swatch, header icon and ✕ in the player's
  panels, and of the whole bottom pill row, whose scroll container additionally declared
  `descendantFocusability="blocksDescendants"` ("no descendant of mine may EVER take focus"). So on
  a television those menus could be seen and not selected. Anything clickable is now reachable, each
  one wears a focus ring drawn inside its own bounds, the pill row can hand focus to its pills, and
  a menu that opens takes the focus itself so the first arrow press walks it. A television box's
  virtual remote (which reports itself as a touch device) gets `focusableInTouchMode` too, or the
  remote would still find nothing on those boxes.

### Changed

- **OkHttp 5.4.0, jsoup 1.22.2**, with `okhttp-brotli` and `okhttp-zstd` — the versions the
  current extension ecosystem is actually compiled against. This is also what the app's own
  extension client now presents to sources that inspect it.
- **App window is `singleTask`** so an external-browser sign-in returns into the running window
  (see the tracker fix above).
- **compileSdk 36, Android Gradle Plugin 8.10.1.** OkHttp 5's Android artifact (`okhttp-android`,
  pulled in by `com.squareup.okhttp3:okhttp`) declares `minCompileSdk = 36` in its AAR metadata, so
  a project compiling against 35 cannot depend on it at all — AGP fails the build with "Dependency …
  requires … version 36 or later". 8.10 is the oldest AGP that supports API 36 and the newest that
  still runs on this project's Gradle 8.11.1, so the wrapper did not have to move. It is a
  COMPILE-time level only (`targetSdk` is untouched, so no runtime behaviour changed), and CI's SDK
  step now installs `platforms;android-36`.

## 0.10.30

### Fixed

- **Trackers: every sign-in reported "no connection (network error)" on a device that was
  online.** The tracker requests were issued from a Compose coroutine scope, and that runs on the
  main thread — where a socket throws `NetworkOnMainThreadException`, an exception with no
  message at all. That is the entire reason the card could only say "no connection": the request
  never left the device. Every request in `TrackerApi` now runs on the IO dispatcher, at the one
  point all of them pass through, so sign-in, search, sync and reporting are safe no matter which
  thread called them.
- **AniList: the login page's "verify you are human" wall now has a way around it.** AniList's
  login page sits behind Cloudflare, and a WebView is exactly what that check is suspicious of —
  it can sit on "Verification failed" forever, never reaching the redirect the app is waiting
  for. The dialog now offers **Open the login page in your browser** beside the paste field: a
  real browser passes the check, and the redirect it ends on (which carries the token) is in the
  address bar to copy into the field, which is what the line above it already asks for.
- **Telegram: Saved Messages now appears in "Your chats" — and can be searched for.** It is the
  chat with yourself, and TDLib only places it in the account's chat list once that account has
  actually saved something, so a user who opened Hikari before ever forwarding a message to
  themselves had no Saved Messages row at all — and therefore nothing for the search above the
  list to find ("No chat matches that name" for the one chat everybody has). It is created
  explicitly now — `createPrivateChat` with our own user id, which is what TDLib's own
  documentation prescribes — classified as Saved Messages from that id, and pinned to the top of
  the list.

## 0.10.29

### Fixed

- **App lock: this is the real reason every password said "wrong".** The stored secret
  interpolated a raw `ByteArray` into the string (`"$algo:$salt:${derive(...)}"`), and a bare
  `ByteArray` in Kotlin prints as its *identity* — `[B@1f2e3d` — which is a different string on
  every call and contains no hash at all. So the blob could never be reproduced, and every
  password, from every build that ever set a lock, was refused. The derivation is hex-encoded
  now. A lock written by one of those builds cannot be verified by anything (the hash is simply
  not in it), so it is recognised as unreadable and the unlock screen says so and offers the one
  honest way out — **Forgot password?** turns the lock off behind a confirmation that says
  plainly what that means. Nothing about the check itself changed otherwise: the same PBKDF2,
  the same constant-time comparison, and a wrong password still costs one derivation.
- **App lock: the screen now tells you what it is doing.** The dots match the password's
  recorded length (a 4-digit PIN was drawn as six empty dots, which read as "it wants six
  digits"), the check shows **Checking…** and freezes the input while 120k PBKDF2 rounds run off
  the main thread, a wrong answer buzzes and clears itself once the red has been seen, the error
  is shown in text mode too, and a value typed while a check was already running is verified next
  instead of being dropped on the floor.
- **Telegram: an api_id/api_hash that look fine but are refused as "not a valid pair" are now
  caught before they are sent.** Telegram compares the hash as a *string*, so one pasted in
  uppercase, or with a space, a dash or a leftover newline from the page it was copied off, is
  refused as a mismatched pair — which reads as "your credentials are wrong" and sent users
  hunting for a new pair that was never the problem. Both fields are validated as you leave
  them, the hash is reduced to its 32 hex characters (lowercased) and the id to its digits, and
  the sanitised pair is what is stored and sent.
- **Telegram: the button next to "Send code" now actually goes back to the keys.** It said
  "Start over" and appeared to do nothing: the saved pair was still in place, so the tab
  re-entered the same state with the same credentials. It is now **Change API keys**, which
  clears the stored pair, closes the client running on it, and opens the api_id/api_hash fields
  again (with the previously saved values shown back, so the mistake is visible).
- **18+ extensions that are already INSTALLED are hidden now too, and come back when the
  switch goes on.** With the adult-content switch off, Hikari filtered the store listings but
  not the installed rows — the 18+ tag lives in the repo listing, and a row that was already
  installed never carried it. Two things fix that: the listing's own tag is adopted onto the
  rows installed from it (persisted, so it survives the repo being removed), and for the
  extensions that declare adult content per *title* rather than per *extension*
  (CloudStream/Hikari/SkyStream/Nuvio plugins) the extension is asked once which it is, with the
  answer written onto the row. The provider list is built through that flag, so while the
  switch is off an installed 18+ extension is not listed, not instantiable, not searched, and
  cannot reach Home — and switching the switch back on restores it, with nothing deleted in
  between.

### Added

- **Trackers (Settings → Trackers): AniList, MyAnimeList, Kitsu, Simkl, Shikimori and Trakt.**
  Sign in to the services you already keep a list on, and what you watch in Hikari is reported
  there automatically — episode by episode, when the episode has been watched through, the same
  way CloudStream does it. Progress is reported once per episode and never removed from your
  list, and marking something watched from a detail page reports it too.
  - **Matching is careful on purpose.** A tracker list is something you have kept for years, so
    a title is only reported when its match is confident: titles are normalised (punctuation,
    articles, release tags, season numbers and years all dropped) before comparison, a film is
    only ever reported to a film entry, an episode to a series entry, and a search whose top two
    candidates cannot be told apart is reported as *unmatched* rather than guessed at. Every
    result is written down on the card — including "no confident match for …".
  - **Each service's app id is your own.** These APIs only accept a login from an app registered
    with them, and Hikari does not ship somebody else's registration. The card walks you through
    creating one (the exact page, and the exact redirect URI to paste — `hikari://oauth`), and
    keeps it on the device. Signing in is then that service's own flow: AniList's token page in
    an in-app WebView, MyAnimeList and Shikimori by authorization code, Kitsu with the account's
    own credentials, Simkl and Trakt by a short code you confirm on their site.
  - **Sync watch history** brings the last dozen watched titles over on demand, **Test
    connections** shows whether each sign-in still works, and a **Report what I watch** switch
    turns the whole thing off without signing anything out.
  - Watch history now remembers the episode and season *numbers* (not just the name), which is
    what lets progress be reported exactly — a "1" parsed out of "Season 1 E12" would have
    marked the wrong episode watched.

## 0.10.28

### Fixed

- **Telegram sign-in: "Send code" now tells you what happened.** TDLib reports every
  refusal ("PHONE_NUMBER_INVALID", "FLOOD_WAIT_34", "API_ID_INVALID", "PHONE_CODE_INVALID")
  as the *result* of the request, and those results were being thrown away — so the button
  did nothing at all and the screen stayed on the phone number forever. Every login step now
  reads its result, says what Telegram refused in plain English under the field, and shows
  a spinner while the step is in flight.
- **The phone number is sent the way Telegram wants it** — in international format, with the
  `+`. Spaces, dashes and brackets are stripped, and a leading `00` becomes `+`. This was the
  most likely reason a correct number was refused: a phone-number keyboard without a `+`
  handed Telegram a number it does not accept.
- **A corrected api_id/api_hash now actually reaches Telegram.** They are sent once, at the
  start of a session, so a client already running on a bad pair kept failing with it forever.
  Saving a different pair now rebuilds the client on the new pair, and a **Start over**
  button sits in every login step (and next to the saved credentials) to restart the login
  from scratch — the way out of a wrong number, a stuck variant, or a session Telegram has
  revoked.
- **The tab can no longer get stuck.** If a login step is ever left unanswered the button
  re-enables itself after 30 seconds, the code step says which number the code went to and
  offers "Use a different number", and a login variant this build cannot finish (e-mail,
  QR-confirmation) says so instead of showing an empty card.
- **App lock: the unlock screen shows as many dots as your password has.** A 4-digit PIN was
  drawn as six empty dots, which read as "it wants six digits". The length is now recorded
  with the password, the dots match it, and the PIN is submitted on the last digit. A lock
  set by an older build (whose length was never written down) still works, and is checked at
  every prefix of four or more digits as it is typed.
- **App lock: a wrong password is never silent again.** Verifying takes a moment (120k PBKDF2
  rounds), and the screen used to accept the tap and show nothing — no spinner, and in text
  mode not even an error, because the message only existed on the keypad. The check now shows
  "Checking…", the field shows the error too, a wrong answer buzzes and clears itself, and
  the typed digits are frozen while the check runs.
- **App lock: a password that will not verify can no longer lock you out of your own app.**
  Verification now accepts both stored formats (the current `algo:salt:hash` and the older
  `salt:hash`), and tries the password exactly as typed before the trimmed form, so a lock
  written by an older build is accepted instead of refused forever. And after a failed
  attempt the screen offers **"Forgot password?"**, which turns the lock off behind a
  confirmation that says plainly what that means.
- **Television: the remote's focus is finally visible.** The focus ring was drawn on the
  component's outer edge and scaled up 1.06×, but nearly every card in the app clips itself
  to its rounded shape before the clickable — so the clip painted the whole ring away, and
  pressing the D-pad moved a cursor nothing showed. The ring is now drawn *inside* the
  component's bounds (inset past half its stroke, never scaled), so no clip can remove it,
  and the focused item is unmistakable: the accent tint over the card, a soft wide halo and a
  crisp ring. Pressing Enter brightens it.
- **Television: the focus ring appears on a remote that reports itself as a phone.** The ring
  was installed only when the device was detected as a television; a box that says it is a
  phone drew no cursor at all. Any D-pad/Enter/media key now turns the ring on app-wide (and
  a touch turns it back off on a touch device), so the layout a box reports can no longer
  leave the remote walking invisible buttons.

### Notes

- The app lock is unchanged in what it stores: a PBKDF2 derivation and a random salt, never
  the password. What the fix adds is the length beside it (for the dots) and a way out of a
  lock that can no longer be opened.

## 0.10.27

### Added

- **A real Telegram client** (Telegram tab → "Your Telegram"). Sign in with your own
  account (an api_id and api_hash from my.telegram.org) and the tab lists your chats:
  Saved Messages, private channels and groups, chats with people, with the unread
  counts. Open any of them and its videos are listed a page at a time, all the way
  back through the history, and play in Hikari's own player — streamed out of TDLib,
  so seeking into the middle of a film starts downloading at the middle. Public
  channels still work with no account at all, as before.
- **The unlock screen has a keypad** (Settings → Privacy & Browsing → App lock): the
  padlock, the dots and 1–9 with the fingerprint in the corner, as in every messenger's
  app lock — plus "Enter password instead" for a password that is a word rather than
  a PIN.
- **Changing or removing the app lock now asks for the current password** before it
  changes anything.

### Changed

- **The quality tag on posters fills in from the extension's own data.** A site-scraping
  extension (CloudStream) states the quality of every catalog row, so those posters now
  carry the badge (4K / 1080p / HDR / Web / DVD / CAM) the moment they are drawn, without
  opening the title and without playing it. Addons that resolve streams instead of
  scraping a catalog (Nuvio, Stremio) still show nothing until a stream is picked,
  because nothing is known about them before then.
- **A catalog folder opens with its own artwork as the header** — with no hero image
  set, the folder's cover is what fills the space above the title (it is the artwork
  the user tapped to get here). An image that fails to load no longer leaves a blank
  band: the header falls back to the title text.
- **The unlock password is trimmed and entered through a real password keyboard.** The
  unlock field used the plain text keyboard, whose autocorrect/capitalisation could
  change what was typed — which is what made a correct password report "wrong password".

## 0.10.26

### Added

- **Telegram tab** (off by default — Settings → Taskbar buttons → Telegram). Add a public channel by its `@name` or a `t.me` link and the channel's videos are listed in the app and play in Hikari's own player, with seeking, subtitles and audio tracks as usual. Older posts load a page at a time. Private channels and Saved Messages are not readable without a Telegram login, which this build does not do.
- **App lock** (Settings → Privacy & Browsing → App lock). A password is asked when Hikari opens, and the fingerprint or face can be used too. The password is always required — the fingerprint is only a quicker way in — and it is stored hashed, so a forgotten one cannot be recovered.

### Changed

- **My Stuff** sections can be swiped: Library, History and Downloads are now pages you can slide between, and the pills above them follow the swipe.
- **A folder tile no longer prints the name of whatever is inside it** ("Recent +2") in the Personal Catalog creator — the tile says the folder's name, and an empty folder says it is empty.
- **Inside a folder, the folder's own name is not repeated over every row** — the header already says which folder you are in. The "All" tab still labels each row, because there the rows really do come from different folders.

## 0.10.25

Everything added and fixed since 0.9.8.

### Added

- Manga reader: paged left-to-right, paged right-to-left and webtoon, with a chapter picker, page slider, zoom, page fit, saved progress and continue reading.
- Manga tab: installed engines, browse, search, Popular/Latest lists, and a search box inside a repo's index.
- Manga extensions install the same way Aniyomi ones do (same files, same repos).
- Stats: watch time, items consumed, day heatmap and your top titles. Every row opens what it is about.
- Codec details in the player (stats for nerds): resolution, codec, bitrate, dropped frames.
- Performance booster in Settings: one switch that drops the poster blur and the animated effects and searches fewer providers at once, for a phone that stutters or heats up.
- Pin button on a source, to keep it at the top of Home's picker.
- Year filter, and a Movies / Series / Both filter, on the search screen - no second search needed.
- Advanced filters for a custom source: genres, release dates, rating, votes, language, country.
- Title logo on the detail screen and on the loading screen, each with its own size slider.
- Episode order filter: first to last, or last to first.
- Mark as watched, watching and watch later on the detail page, drawn on the poster too.
- Pair and sync with another device: one shows a code, the other types it.
- Collections and folders: pin to Home, reorder, browse as rows or tabs, folder cover art and logo, hide the name, animated covers, export and import as JSON.
- IPTV tab, with the playlist dialog right there and groups drawn as folder tiles.
- Android TV, Google TV and Fire TV support in the same APK: rail layout, remote controls, TV settings folder and a TV banner.
- Pick several sources at once in the provider picker.
- Subtitles from the internet: five sites built in, search from inside the player, and auto-find in your language.
- Franchise row, production companies, the full release date, and film/series and quality tags on posters.
- 20 languages fully translated.

### Fixed

- Player dialogs and panels: no more cut off text or buttons, correct size in portrait and landscape, every box scrolls.
- Lag and jitter while installing extensions, and while nothing is happening on screen.
- Nuvio providers not showing all their servers: engines boot faster, run together instead of queueing, are no longer cut off mid-pass, and say why when they come back empty.
- Aniyomi extensions loading again, including ones behind Cloudflare and ones that use the JavaScript engine.
- Manga pages breaking: pages are drawn with the same renderer Nekoread uses, and preloaded around the page you are on.
- Reader page slider landing on the page you tapped, and the chapter arrows going to the next chapter instead of another group's copy of the same one.
- Subtitle box opening with the original title, and results that could never download are gone.
- Home going back to "all providers" after coming out of the player.
- Stats counting real time and the right day.
- Importing a collections JSON file, and the titles it carries.
- IPTV channel logos loading however the playlist wrote them.
- R-rated titles leaving the catalogues while the adult switch is off.
- The interface language changing on the spot.
- The extension that listed itself nine times is one row now, with its sources underneath.
- No crash on a device without a WebView.

### Changed

- The Manga tab is on by default.
- Webtoon is the default reading mode.
- Poster effects and loading-screen effects default to NONE.
- The background extension search waits until the video is playing.
## 0.10.24

**The lag, the cut-off nuvio engines, and the diagnostics to prove both — plus
the Manga tab on by default and clickable Stats rows.**

### Fixed

- **Nothing gets "cut off" without being heard from.** The pass's wall-clock
  ceiling used to cancel a nuvio engine that was still working, and the teardown
  then handed it to the background sweep — a SECOND VM boot and a second round of
  fetches for the same answer (the reported "some providers are cut off, they
  show servers in the nuvio app"). The pass now waits up to 25s past its ceiling
  while a nuvio engine is still in flight; its own 60s call budget is the real
  bound.
- **A nuvio engine boots roughly twice as fast.** Each fresh engine used to
  execute ~440KB of cheerio before the provider ran a single line. It is now
  evaluated only for a provider whose own source mentions cheerio (or `$(`) —
  most engines are fetch + JSON + a deobfuscation step — and a provider that
  turns out to need it is retried once WITH the bundle rather than failing.
- **Every engine has a memory ceiling (256MB).** Nothing bounded the JS heap
  before: one runaway provider could allocate until the OS low-memory killer took
  the whole app. That is the "it almost crashes while the sources load" half of
  the report.
- **The request fan-out is sized to the device.** A four-core phone and an
  eight-core tablet were both given the same 96-way fan-out, which is why the app
  froze while the servers loaded: searches, their parsing, the QuickJS engines
  and the UI were all fighting for the same handful of cores. The wave ceiling
  and the search gate now scale with the core count (24..96) — nothing is
  skipped, the queue still drains in waves, and the tail lands sooner because the
  head is not thrashing.

### Added

- **The log now answers "why did this provider have nothing?"** Every nuvio
  provider that comes back empty gets one line: the exact TMDB id, media type and
  season/episode it was asked with, the verdict, and the HTTP trail from that
  call (`no HTTP request at all` = it gave up before touching its own site, i.e.
  wrong arguments; a 403/404 = the site refusing it; a 200 = its own parsing
  found nothing).
- **Memory is in the log.** Startup prints the device's Java heap class, its
  large-heap class, total/free RAM and what the process holds; every search pass
  ends with `java used/max MB · native …MB`; `onTrimMemory` logs the level
  Android chose. "It gets laggy" is now measurable instead of a feeling.

### Changed

- **The Manga tab is ON by default** (Settings → Taskbar buttons switches it off
  for anyone who does not read comics).
- **Poster effect and loading-screen effect default to NONE** — the gallery
  frame, aura ring, edge light, sheen and glow are all opt-in now, so a fresh
  install draws plain artwork and a plain loading card.
- **Every row on the Stats page opens the title it is about** — the "Items
  consumed" / "Time spent" rows, the day lists, and the favourite-title card
  (a video row reopens its detail page, a manga row its chapter list, and a row
  with no provider behind it opens Search on the name).

## 0.10.23

**The nuvio engines are now asked the way the app they come from asks them.**
The fetch bridge was a *synchronous* native call — JS got its answer only once a
request was over — and every provider request in the app went through a fixed
pool of **four** threads. That is why the same engines that list their servers in
a couple of seconds in the reference client took tens of seconds here, and why
one hung site could take the whole nuvio tab down with it. The bridge is now
asynchronous and cancellable (each engine's requests really run in parallel, and
a cancelled search really cancels its HTTP), background sweeps no longer compete
with a search you are waiting on, and a provider that *crashed* is no longer
filed away as "no servers".

### Fixed

- **Nuvio sources in seconds, not a minute.** The bridge is an `asyncFunction` over
  OkHttp's own dispatcher, exactly like the reference client's `__native_fetch`:
  a provider that fires ten requests at once gets all ten in flight, and every
  engine's fetches run together instead of queueing behind four threads. A single
  site that hangs no longer eats a quarter of the phone's capacity — and it can no
  longer hold a VM slot for 90 seconds after the search it belonged to is over.
- **"No servers" now means no servers.** A nuvio provider that threw inside its
  own JS, or failed to resolve a TMDB id, was recorded as the *answer* "no
  servers" — so it was never asked again and the report read as if the engine had
  looked and found nothing. Those cases are now reported as what they are
  ("provider failed: …"), and are re-asked in the background like any other
  non-answer. The per-provider line also prints the provider's own reason
  ("no sources for this title", "couldn't resolve a TMDB id") instead of a
  blanket "no servers".
- **The quality badge shows up for titles you have actually played.** The badge
  is fed by completed source searches, but the recording was skipped whenever the
  answer came from the cache, from the live feed, or from the player — which is
  most plays. The player now files the best quality of every server list it holds,
  the detail page files it for cached and fallback answers too, and the lookup
  itself no longer depends on a catalogue row carrying the same year as the one
  that filed it. (Settings → Poster styling → "Quality tag on posters".)
- **Leaving the player no longer leaves the app heavy for a few seconds.** The
  title's background sweep is stopped as before, but every other background
  search now stands down for the moment the app needs to rebuild its own screens,
  and a sweep's nuvio calls can no longer hold the engines the just-opened screen
  is waiting for.

### Changed

- **A search you are waiting on always wins.** Background sweeps (the "keep
  asking every installed extension" half of a pass) now park while a pass is
  running, and their own budget clock pauses with them, so nothing they never got
  to is lost. This is what stops the reported "Nuvio 12 · 11 still searching"
  while every engine sits queued behind work for another title.
- **Background nuvio calls take a small pool of their own slots** (3), so a sweep
  re-asking engines for a title you are no longer watching can never crowd out
  the engines for the title you are.
- **At most two background sweeps run at once** (was three), which with 8 workers
  each was 24 extensions being searched at once beside a pass.

## 0.10.22

**Episodes now run newest-first with one tap, posters say whether a title is a
film or a show, the pinned title logo finally obeys its own size setting, and
Stats answers for the day you tapped.** The two behind-the-scenes fixes are the
ones you will feel: nuvio engines were being asked for anime titles as *movies*,
so a whole category of titles came back empty, and the search budget cut the
engine pass off at 55s while nuvio's own plugins are allowed 60.

### Added

- **Episode order arrow** (Detail page → beside "Episodes (N)"). One tap flips
  the list to newest-first and the arrow flips to match; tap again and it is
  oldest-first, episode 1 at the top, the way it always was. It applies *before*
  paging, so on a 600-episode donghua "newest first" means the newest 30 make up
  the first page. The season picker and the page picker both follow the order,
  and the choice survives a rotation.
- **A film / series tag on posters** (Settings → App Layout → Poster styling →
  "Movie / series tag on posters", on by default). A small "Movie" or "Series"
  chip in the poster's top-left corner, so a grid tells a film from a show
  without opening it — the same white-on-dark pill the quality chip uses, so the
  two stack cleanly when both are on.
- **A quality tag on posters** (same card → "Quality tag on posters", **off by
  default**). Prints the best quality Hikari actually knows a title comes in —
  read from the title's own name ("… 1080p WEB-DL") or from the servers found the
  last time you opened it. A title Hikari has never seen shows nothing rather
  than a guess.
- **Every figure on Stats is now a door.** Tap **Time spent** for what was
  watched and for how long, **Items consumed** for every item that made the
  count up, **Days active** for the day-by-day list — and tap any of those days
  to open it. Whichever day the heatmap has picked scopes the first two figures,
  so tapping the 19th and then "Time spent" answers "what did I watch on the
  19th" instead of repeating the all-time total. The sheet opens in the page,
  and its own "All time" action puts the whole log back.

### Fixed

- **Anime titles now reach the nuvio engines as series.** Every nuvio provider
  we ship does `mediaType === "tv" ? "tv" : "movie"` — none of them understands
  "anime" — so an anime asked for as `anime` was looked up as a *movie* and came
  back with an empty list (or a 404). Anime items are now asked for as "tv", and
  films as "movie", which is what the providers expect.
- **The nuvio engine pass is no longer cut off at 55 seconds.** The providers get
  60 seconds each (nuvio's own number), but the pass they run inside gave up at
  55 — so the tail of a full engine set was cancelled mid-run and had to be
  re-asked from scratch in the background sweep, paying a second engine boot plus
  a second round of network work for the same answer. A pass that is asking nuvio
  engines now gets the longer ceiling, and twelve engines run at once rather than
  ten, so a twelve-engine install stops queueing its last two behind the cap.
- **The title logo obeys its size setting once it is pinned.** Raising "Title
  logo size" used to enlarge the wordmark only while the header art was on
  screen; the moment it scrolled up the title came back at the same small size,
  which read as the setting doing nothing. The setting is now a plain size
  multiplier applied at *both* ends — 130% is 1.3x the usual size over the header
  image and 1.3x the usual size as the pinned title. At the default 100% nothing
  changes for anyone who never touched the slider.
- **Stats counts the day you actually tapped.** The heatmap read out the same
  time and the same numbers whichever square was picked, because a day's bucket
  held a time and nothing else. Each day now carries its own per-title
  breakdown, so picking the 19th shows the 19th's own time, episodes and
  chapters, and the titles that made them up.
- **"Items consumed" can now list what it counted.** Opening a chapter and
  closing it again is an item consumed, but it used to leave no title row at
  all, so a count of 9 sat next to a list that could account for none of them.
  Every event now records its title.

## 0.10.21

**Nuvio engines actually finish, so the server list stops at 2-3, the reader's
page slider lands where you tap it, and Stats finally counts your time.** The
Nuvio one is the big one: with 20+ nuvio providers installed the search ran out
of budget before most of them had even booted, which is why the list looked
nothing like the same list in the Nuvio app.

### Added

- **A nuvio report in the sources sheet.** Under the server list (and in the
  "no playable sources" state) there is now a line per installed nuvio engine:
  what it answered — "3 sources", "no sources", an error, or "never ran — no
  engine slot before the search ended" — plus a count of how many engines
  answered at all. When a plugin's servers are missing, this says which plugin
  and why instead of leaving you to guess.
- **Title logo size** (Settings → App Layout → Details header). A slider that
  sizes the title artwork drawn over the detail page's header image, 50% to
  160%, moving one percent at a time.

### Fixed

- **Nuvio providers no longer lose their turn.** Three things were stopping most
  of a 20+ provider install from ever answering: every provider call re-compiled
  the ~550KB runtime (boot + cheerio + harness) from source in a brand-new
  QuickJS engine (nuvio compiles it to bytecode once and reuses it — we now do
  the same), only 6 engines ran at once (nuvio runs 10), and a provider had 45
  seconds (nuvio gives 60). All three are now nuvio's numbers, so the tail of
  the provider queue gets to run and its servers reach the player.
- **The server list stops stuttering while servers are still arriving.** Bursts
  of new servers rebuilt every open chooser (and re-created its whole chip
  strip) once per server; they are now gathered and rebuilt at most four times a
  second, and a rebuild that would produce the same chip strip is skipped.
- **The app no longer stays heavy for a few seconds after leaving the player.**
  Leaving used to hand the title's background search straight back to the
  system, so a fresh nuvio sweep (one QuickJS engine per provider) started
  exactly while the player was being torn down and the previous screen was
  rebuilding. The sweep is now stopped and held when you leave the player, and
  resumes the next time you play that title (or shortly after, on its own
  backstop).
- **The reader's page slider lands where you tap.** Tapping or dragging the
  bottom bar jumped the chapter but the knob stayed on the old page until the
  next scroll; it now follows your finger immediately and only waits for the
  reader if the reader settles somewhere else.
- **Stats counts real time.** Two separate leaks kept "time spent" at 0m while
  items piled up: the player's final flush of watched seconds was written in the
  activity's own scope, so it was cancelled the moment the player closed (losing
  the last part of every watch, and all of a watch shorter than a minute), and
  the manga reader's clock restarted on every chapter change — a reader who
  flipped chapters, or whose webtoon strip walked across them, was credited no
  time at all. Both now write through the app's own scope and one continuous
  clock, so time spent, XP, the rank ladder and the favourite title's "spent"
  figure follow what you actually watched and read.

## 0.10.20

**Two devices can now set each other up, the source list can be pinned, and the
subtitle search box is finally the size it should be.** The pairing is the big
one: a QR code and a six-character code move your whole setup to a new phone or
television over your own Wi-Fi, with no file and no account.

### Added

- **Pair & sync** (Settings → Backup & Restore → Pair & sync). The device that
  is already set up taps **Show the code** and shows a QR code plus a six-
  character code; the new device either **scans the QR** with its camera or
  **types the code**. Your settings, installed extensions, sources, repos,
  collections and history then travel straight from one device to the other over
  the local network — nothing is uploaded anywhere, and the receiving device
  asks before it replaces anything. What it sends is a **backup**, byte for
  byte: the same payload the "Back up Hikari data" button writes to a file, so
  the transfer cannot get right what a file restore gets wrong. A device with no
  camera (a television) simply types the code, which is why the code is six
  unconfusable characters shown large enough to read across a room, and why the
  typed path finds the other device by itself (a Wi-Fi broadcast, so no IP
  address ever has to be read off one screen and typed on another). The code is
  the only key: nothing is listening once you leave the screen, and every
  request without the right code is refused.
- **Pin a source to the top of Home's picker.** Every row in "Choose an
  extension" now carries a pin — tap it and that extension floats to the top of
  the list (and above everything pinned before it), tap again to let it go. With
  a hundred extensions installed, the two you actually use were otherwise as
  hard to find as any other two. The pin is remembered across restarts, and it
  is a pin on an extension's ID, so an extension update cannot drop it.

### Fixed

- **The subtitle search box ("Load from internet") now opens as a proper box**
  — 86% of the screen's width and 72% of its height, with the results scrolling
  inside it — instead of a nearly-empty panel that grew from whatever rows it
  happened to hold. Two things were wrong: the box was sized from its contents
  (and its contents arrive one subtitle site at a time, so it opened at the
  height of its empty search row and stayed there), and its width was capped on
  the window's *height* axis, which in the landscape player is about 41% of the
  screen. The 0.10.19 attempt — re-applying the window's layout whenever the
  panel grew — could not have worked: a dialog window is measured when it is
  SHOWN, and re-measuring it is a race with rows that are still landing. The box
  is now given a definite size and simply keeps it. See docs/PLAYER_PANELS.md.

## 0.10.19

**A Stats page, a pinnable codec readout, a lock icon that gets out of the way,
and the two player panels that were still misbehaving.** Four requests, plus the
subtitle search box that opened at the height of its empty search row.

### Added

- **Stats.** Settings → Stats, and an off-by-default **Stats taskbar button**
  (Settings → Taskbar buttons switches it on). The page counts what you watched
  and read: time spent, items consumed, days active, an Otaku rank with XP and
  level progress, daily averages for episodes and chapters, the current and
  longest streak, your favourite title and a twelve-week activity heatmap whose
  squares can be tapped for a day's total. The numbers come from a small
  per-day/per-title document (`WatchStats`) that the player and the reader write
  as they go — the player adds wall-clock seconds of real playback every minute
  and one "item" per session, the reader counts each chapter opened and the time
  the reader is on screen — so nothing here is guessed from the watch history
  (which holds one row per video and cannot report time spent at all).
  Everything is in the same preferences store, so a backup carries it.
- **Codec details** ("stats for nerds"), in the player's gear menu: the live
  video and audio codecs, resolution, frame rate, bitrate, HDR range, languages,
  the frame actually on screen, the decoder's rendered/dropped counters, the
  stream type and host, the buffer and the playback state. It is a **pinnable**
  overlay — pin it and it stays over the video and keeps updating once a second;
  leave it unpinned and it goes away with the controls.
- **Brightness & volume swipes can be switched off** in Settings → Player →
  Player controls (the layout editor). ON by default; off means a vertical drag
  on the video is not a gesture at all.

### Fixed

- **The subtitle search box ("Load from internet") opens at its proper size
  immediately.** A `WRAP_CONTENT` dialog window is measured when it is SHOWN and
  is not measured again when the rows inside it arrive, so the box kept the
  nearly-empty height it opened with until an unrelated relayout (the screen
  going off and on) sized it — which is exactly the report. The panel now
  re-applies its window layout whenever `fitToContent()` resizes it
  (`refitWindowOnResize`, opted into by this panel alone, so no other box
  changes). *(Superseded in 0.10.20: the re-measure did not take on the device,
  because a dialog window is measured once when it is shown — the box is now
  given a definite size instead. See the 0.10.20 section.)*
- **The search button says "Searching…" while a search is in flight**, and goes
  back to "Search" when it finishes. The panel's status line already said so, but
  the button the user tapped never changed.
- **The lock icon is no longer parked on the picture for the whole film.** It
  appears for two seconds when the controls are locked and again on any tap on
  the screen while locked (that is how it is found), then fades out.

## 0.10.18

**The player's boxes are the 0.9.8 boxes again, and the reader's chapter title is
the chapter you are actually in.** Two reports, both traced to code rather than to
another guess: the panel height caps of 0.10.6–0.10.17 are DELETED (the user
pointed at the 0.9.8 APK — the build whose panels work — and they were right, so
that geometry is back verbatim), and the reader's "it says Chapter 4 while I am
reading Chapter 6, and ▶ loads the same chapter again" turned out to be four
`derivedStateOf`s that had captured the FIRST chapter's `remember(chapter)` state
objects and therefore reported the chapter the reader was opened on for the rest
of the session.

### Fixed

- **The player's dialog panels are the 0.9.8 implementation.** `presentGlass`
  takes the height the caller expects (in dp) again, clamps it to the window,
  gives the panel that definite height and puts ONE plain `ScrollView` inside it
  with `weight = 1`; `fitToContent()` shrinks the panel onto its rows afterwards.
  A definite panel height with a weighted scroller cannot produce a box that is
  one row tall and unscrollable, which is what every build of the cap family
  shipped. `MaxHeightScrollView`, `visibleRoomPx`, `screenHeightPx`,
  `screenWidthPx` and the whole `applyHeightCap` pass are deleted, and
  `windowSize()` is back to its 0.9.8 form. All five call sites pass a height
  again: `showGlassMenu` derives it from its rows, the server chooser passes
  700dp, the subtitle sheets 620dp. Callers gained nothing else — the
  `headerActions` gear/search buttons and the sideways strips are kept. See
  [docs/PLAYER_PANELS.md](docs/PLAYER_PANELS.md).
- **The reader's chapter title, page counter, saved progress and ◀ ▶ buttons now
  follow the chapter the viewer is in.** `streamPosition`, `activeChapterUrl`,
  `currentPage` and `pageTotal` were `remember(isWebtoon)` while the states they
  read are `remember(chapter)` — a Compose closure captures the state OBJECT, so
  once a chapter change recreated those states the derived values kept reading
  the first chapter's for the rest of the session. The title froze on the chapter
  the reader was opened on, `prevChapter`/`nextChapter` kept resolving the same
  neighbour, and the progress debounce kept writing the wrong `chapterUrl`. They
  are now keyed on the chapter too, and `viewerPos` is seeded by the seed effect
  so the first frame is right even when a chapter is prepended above the reader.
- **A chapter change lands at the TOP of the chapter it goes to.**
  `openChapter` compares against the chapter the viewer is in (not the one the
  screen was seeded with, which made the button a no-op for a chapter the strip
  already held) and a chapter opened from inside the reader no longer restores its
  old saved page; only the chapter the reader was opened on does.
- **The strip is continuous in BOTH directions.** The webtoon viewer reports
  `nearStart` as well as `nearEnd`, so scrolling up to the top of the first
  streamed chapter streams the previous chapter in ABOVE the reader — the reader
  can now be scrolled from the chapter it opened on all the way back to chapter 1
  and forward to the last chapter without leaving the strip. A prepend shifts
  every adapter position, so the viewer remembers the page under the reader and
  puts the scroll back on it, and the shell asks for each previous chapter at most
  once.
- The reader's chapter sheet opens ON the chapter being read instead of always at
  chapter 1.


**Two things this project had "already fixed" were fixed by guesswork, and this
release is what happens when the guess is checked against the code.** The player's
server and subtitle panels were re-derived from the same two window figures three
times; the third look found the real shape of the bug: the cap was MONOTONIC — it
could only ever shrink from the first number it ever computed — so a single early
measurement pass with a wrong room fixed the list at that height for the dialog's
whole life, which is a box one row tall with a scrollbar beside it and no drag that
can ever reveal the rest. And "Nekoread's image loading, copied full to full" was
half true: the viewer, the page cache and the page download were a line-for-line
copy, while the fetcher layer that loads an image THROUGH an extension's own client
— Nekoread's `ExtensionPageImageFetcher` — was never ported at all. Both are now in.

### Fixed

- **The player's panels are sized from measured numbers only, and the cap can
  never be stuck.** `visibleRoomPx` is now the dialog frame's own measured height,
  bounded by the configuration's screen height in the current orientation;
  `getWindowVisibleDisplayFrame` and `windowSize()` — both of which report a
  floating window's area from the display's natural metrics, and can also answer a
  band one row tall while a window is still being placed — are no longer consulted
  at all, and the panel's WIDTH comes from the configuration too. The ceiling is
  re-derived on every pass and the correction that trims it is per-room
  (`lastAvail`), so a cap that was fixed at a wrong size recovers instead of
  staying wrong; the ceiling is also clipped by what the rows actually measure, so
  a three-row sheet is three rows tall rather than a wall of glass; the correction
  stops at its floor; and `applyHeightCap()` refuses to re-enter, because a layout
  pass it scheduled must not run it a second time inside one frame. See
  [docs/PLAYER_PANELS.md](docs/PLAYER_PANELS.md).
- **Nekoread's image-loading layer, ported whole.**
  `reader/source/ExtensionPageImageFetcher.kt` is Nekoread's own file: a Coil
  `Fetcher` + `Keyer` for `ExtensionPageImage` (a page loaded through
  `HttpSource.getImage`) and for `ExtensionCoverImage` (a cover loaded with the
  source's own headers, plus a Referer fallback, through a short-timeout clone of
  the extension's client so a cover can never queue behind a burst of page
  requests). Both are registered on the app's Coil loader. The one addition is
  `ExtensionCoverRef`, which names the provider and resolves it on Coil's
  dispatcher — this app loads extension classes on demand rather than keeping
  Nekoread's start-up registry, and that load must never happen while a grid is
  composing — with a fallback to the plain URL so a cover can never be worse than
  before. `MangaSource` carries Nekoread's members again (`userAgent`,
  `getPageImageModels`, `coverImageModel`), and manga covers now go through the
  extension's client (`PosterLoader.model(url, providerId)`, used by
  `Artwork.model`, `MangaPosterCard`, `ContinueCard` and the manga detail header).
- **The Play button looks like it did.** The mark check that sat beside it in the
  same row stole a quarter of its width, so "Resume S1 E5" wrapped and Play came
  out as a tall pill. The row is Play + Download + Library again, exactly as it was.

### Added

- **The mark state is drawn on the detail page.** Every mark a title carries —
  Watched, Watching, Watch later — is now a chip in a strip under Play, so the
  answer to "have I marked this?" is on the page instead of hidden behind a tap on
  the button that sets it. The strip also carries the way into the sheet (`Mark`
  when nothing is set, `Change` when something is), which keeps the actions that
  are not a plain on/off — mark every episode, remove from history — one tap away.
  See [docs/DETAIL_HEADER.md](docs/DETAIL_HEADER.md).

### Performance

- **An animated cover's still is decoded SAMPLED** (bounds pass, then
  `inSampleSize` to 512px in `RGB_565`) instead of at full size: it used to be a
  multi-megabyte bitmap per animated cover, held for the session, which is exactly
  the allocation that gets a low-memory device killed while a grid scrolls.
- **Installing an extension streams** instead of holding a second full copy of the
  package in memory when the temp file has to be copied rather than renamed.
- **The panel's cap pass cannot nest inside its own layout**, which removes the
  "the box shudders while I scroll it" shape of jank.
- [docs/PERFORMANCE.md](docs/PERFORMANCE.md) is new: it records the main-thread,
  decode-size, layout-pass and install-path rules the app is built to, each with
  the symptom that breaking it produced.

### Notes

- Continuous channel only. Nothing is published to the main release.

## 0.10.16

**The player's server and subtitle boxes were sized off numbers that do not
describe the room they are in.** Every height cap on the glass panels was
derived from `visibleRoomPx`, and that function took the smaller of the panel's
own frame height and `getWindowVisibleDisplayFrame().height()` — both of which a
floating dialog's WINDOW reports from the display's *natural* orientation, so in
a landscape player they answered the portrait height (the same quirk the
fullscreen helper already documents: `currentWindowMetrics` answering
`1080x2460` inside a `2460x1080` window). Every cap was therefore about 2.3x the
real room, the panel grew past the bottom of the video, and the rows below the
fold could not be scrolled to no matter what the caps said — which is why
several rounds of re-deriving the caps from those same two figures changed
nothing. The bound that actually follows rotation is the configuration's
`screenHeightDp`, so the caps are now taken against that, the scroll view clamps
its cap against the measure spec it is handed, and the "is it still too tall"
correction compares the *truthful* list height (hint + scroll + chrome +
padding) against the allowance instead of against the clamp — the old
comparison could never disagree with itself, so it never fired. Alongside it:
manga pages are fetched through the extension's own `getImage(page)` exactly as
the reference reader does, the reader defaults are the user's screenshotted
states, and a mark button sits beside Play.

### Fixed

- **The server box and the subtitle box fit the screen and scroll.** See the
  thesis above: `player/PlayerActivity.kt` gains `screenHeightPx()` (the
  configuration's `screenHeightDp` times density — the only height figure that
  rotates with the device), `visibleRoomPx` and the opening panel cap are both
  bounded by it, `MaxHeightScrollView.onMeasure` clamps the cap against the
  incoming spec so the scroll view can never be handed more room than the panel
  has, and the correction now measures `hint + scroll.height + chrome + padding`
  against the allowance (with the panel's own padding read from
  `paddingTop/paddingBottom`, the old `panel.height - scroll.height` kept only as
  a fallback). Nothing else about the panel geometry changed.
- **A manga page is downloaded through the extension, by the book.** The page
  loader now does exactly what the reference reader's HTTP source adapter does:
  build a `Page` carrying the page's own URL and its resolved image URL, call
  `ext.getImage(page)`, check `isSuccessful`, copy the body to the page cache
  and close it in a `finally`. That is the half that matters — the extension's
  `getImage` is where the per-page `Referer`, the site's headers and any
  descrambler live, and the hand-rolled request that skipped it is why chapters
  kept breaking. `MangaProvider.getStreams` now records each page's URL on the
  stream (`StreamSource.pageUrl`, new and defaulted so every existing producer
  compiles untouched) and writes the resolved image URL back onto the page, the
  reader hands its source to the loader (`MangaProvider.httpSource()`), and the
  descriptors carry `pageUrl` instead of an empty string. The old header-replay
  path survives only as the fallback for a source that is not an HTTP source.

### Changed

- **The reader's defaults are the user's screenshots.** Double-tap zoom, pinch
  zoom and tap-to-change-pages are now off; keep-screen-on, page transitions,
  smooth auto-scroll, edge tap zones, the stock (Default/none) invert, webtoon
  fit-width scale, zero side padding, the Normal hide threshold and High (sharp)
  quality were already what the screenshots showed. A stored settings blob is
  left alone, so an existing reader keeps what it has — "Reset to defaults" in
  the reader chrome applies the new set.

### Added

- **A mark button beside Play on the detail page.** It opens a sheet: mark as
  watched, mark as unwatched, mark as watching (which never clobbers a saved
  playback position), add to / remove from watch later, mark every episode of a
  series as watched, and remove the title from history. "Watch later" is an
  ordinary Library category found by name and created on first use, so it can be
  renamed or deleted like any other.

### Notes

- Continuous channel only. Nothing is published to the main release.

## 0.10.15

**The manga reader is Nekoread's reader, ported whole** — its two native viewers,
its on-device page cache, its Coil decoder and its chrome with every option on
it. That is what the user asked for ("port whole nekoread reader … full copy
means full copy"), and it is also the fix for what they were looking at: pages
drawn in pieces, a blank gap between pages, and "fit screen" options that did
nothing at all. Alongside it: every dialog in the player now really scrolls both
ways, and the adult-content switch finally reaches the movie LIST endpoints.

### Fixed

- **A manga page is drawn by Nekoread's renderer, from a file, region-decoded.**
  `ui/screens/MangaReaderScreen.kt` is now the shell and the reading is done by
  `reader/ui/YomiWebtoonReader` (the continuous strip: a `RecyclerView` of
  subsampling page frames, a chapter divider where each streamed chapter begins,
  a trailer that reports the next chapter's loading/error/end state) and
  `reader/ui/ChimahonPagerReader` (the paged modes: a `DirectionalViewPager` of
  the same frames, so it can page vertically too). Both render every page from a
  file in `reader/cache/WebtoonPageCache`, fetched **through the source's own
  client** (`reader/source/HikariPageSource`, which replays the page's request
  headers — this is the half that makes a hotlink-protected CDN answer) and
  region-decoded from disk by `SubsamplingScaleImageView`. The old path — one
  hand-decoded bitmap per page — and the sliced/chunked renderers with it are
  **deleted** (`manga/PageBitmaps.kt`, `manga/MangaPageLoader.kt`,
  `manga/MangaEnhance.kt`); that path is what drew the page as a grid of
  fragments with grey seams, which is the user's screenshot.
- **The strip is drawn at fill-width, always, and the page-fit setting is a PAGED
  setting.** The "blank screen between images" was a paged fit (`Fit height`)
  being applied to a continuous webtoon: every page was scaled to the screen's
  height, leaving a screen-sized black band between pages. A strip is now always
  fit-width (Nekoread's own rule — a strip fitted to the height leaves two thirds
  of a phone screen empty beside every page), and what changes its look is the
  Webtoon section's scale type / smart scale / side padding. In the paged modes
  the fit picker now genuinely drives the renderer
  (`SubsamplingScaleImageView.SCALE_TYPE_*`), and a change re-binds the page you
  are on instead of waiting for a page turn — the report "clicking any option
  from fit screen not doing anything" was both halves: the option was applied to
  the wrong mode and, in the mode that ignored it, nothing was listening.
- **Every reader option the reference app has is wired, and it persists.**
  `reader/ui/YomiReaderChrome.kt` is yomi/chimahon's chrome copied in: reading
  mode, page fit, orientation, crop borders (per mode), tap zones and their
  inversion, smaller tap zones, side padding, page scale + gap smart scale, zoom
  (double-tap and pinch), page transitions, auto-scroll and its speed, the
  menu-hide threshold, custom brightness, colour filter, grayscale / inverted
  colours / image enhance, image quality (50/75/100%, with RGB_565 for strips at
  50%), the backdrop, the chapter list with a per-row thumbnail, and the
  per-series mode override. All of it lives in **one JSON blob**
  (`AppStore.MANGA_READER_SETTINGS` ↔ `reader/ReaderSettings`), and the old
  per-key preferences are read once through `ReaderSettings.fromLegacy` so an
  existing install keeps the mode/fit/backdrop/page-number it had picked.
- **The player's boxes scroll, vertically and sideways — and only where there is
  something to scroll.** The outer `HorizontalScrollView` ("the reach") is gone:
  it claimed EVERY sideways drag anywhere in the panel the moment the finger
  moved, because that is what the platform class does on touch slop — it does not
  first ask whether it has anything to scroll to. Its own range was zero, so the
  gesture went nowhere: the engine chip strip (All / CloudStream / Hikari / Nuvio
  / Stremio / SkyStream) and every other sideways row never saw a drag. That is
  the "I can't scroll horizontally to select Nuvio" report. `PanelReach` and
  `fitContentToPanel` went with it, and the content is now the vertical
  scroller's `MATCH_PARENT` child — a bounded width by construction, so labels
  ellipsize and the trailing pills sit inside the glass with nothing pinned by
  hand. A row that genuinely needs to move sideways owns a `SidewaysScrollView`,
  which calls `requestDisallowInterceptTouchEvent(true)` once a motion is clearly
  horizontal so nothing above it can take the gesture away.
- **The panel's height cap is corrected against what the frame actually laid
  out.** The silhouette's padding is derived from its own size, so a fixed
  prediction of it drifts as the panel grows, and every pixel it drifts by is a
  pixel below the bottom of the video where no drag can reach it — the
  "subtitle box is unscrollable" report. `applyHeightCap` now compares the DIALOG
  ROOT's measured height (hint line plus panel) with the room the dialog was
  given and takes the excess off the list, re-running on every layout change of
  the frame, the panel, the scroll view and the root, and bounded so the passes
  can only ever shrink and so cannot chase each other.
- **R-rated films leave the catalogues while the adult-content switch is off.**
  The switch filtered items it could JUDGE, and a catalogue row carries no
  certificate to judge — but only `/discover/movie` can be told to leave R-rated
  films out, and Home's rows do not ask it: `/movie/popular`, `/movie/top_rated`,
  `/movie/now_playing`, `/movie/upcoming` and the `/trending/all|movie/*` family
  are plain LISTS that take no `certification` parameter at all (TMDB drops it).
  So those rows came back identical with the switch on and off — exactly "I turn
  off nsfw 18+ toggle, but still r rated movies showing". `NsfwGate.restrictRequest`
  now REWRITES each of them into the equivalent `/discover/movie` query with
  `certification_country=US&certification.lte=PG-13` and the same ordering (with a
  `vote_count.gte=300` floor under a vote-average sort, so a "top rated" row is
  not a wall of one-vote titles), and `rewritesToMovies` tells a rewritten MIXED
  row's parser that the answer is movies. `/trending/person/*` is deliberately
  left alone — its answer is people, not titles.

**A webtoon page is drawn as ONE image again** — which is what the reference
reader does, and the only shape that has ever come out right on these phones.
Everything else here is the list from the last round of reports: the player's
boxes are bounded and scrollable, the adult-content switch reaches the
catalogues themselves, and four features asked for by hand (a light you place on
your posters, a search box with pins over your manga engines, a search box inside
a studio's catalogue, and Popular/Latest inside a manga engine's own page).

### Fixed

- **The manga reader draws the whole page as one bitmap, and the chunked renderer
  is deleted.** 0.10.13 ported Nekoread's `WebtoonChunkedImageView`, and the
  reference app's OWN log says what that renderer does with a real webtoon strip:
  it comes out black ("page 6, path=TALL_CHUNKED … one solid black field"). That is
  why the reference app only ever hands it a strip whose single decode cannot fit
  its 48MB per-bitmap budget — for everything else it decodes the ENTIRE strip once,
  at display width, as SOFTWARE memory, and draws it with an ordinary `ImageView`.
  The platform's render thread splits such a bitmap into as many tiles as it needs,
  silently and correctly, and because the bitmap is stable for the page's whole life
  there is nothing to re-decode while scrolling. That is now exactly what
  `PageBitmaps` does (`TALL_PAGE_BYTES` 48MB, width reduced by halving only when a
  monster strip needs it, never below 256px, `inPreferredConfig = ARGB_8888` so the
  decode is software and not a hardware texture) and there is one drawing shape for
  every page. `ChunkedPageView`/`NekoPageView` are gone, the reader's tall-page
  guard is gone, and a page is one bitmap or it is a failed page with a retry.
- **The reader's "Page fit" choice now applies in the continuous (webtoon) mode**,
  which is the mode that ignored it. `Fit width` keeps the strip shape (each page at
  the full width, at its real height, in one continuous column, exactly as before);
  `Fit height` and `Fit the screen` now give every page a whole viewport of its own
  with the page scaled inside it. This is the piece that was reported as "clicking
  any option from fit screen does nothing": `WebtoonRunBody` was never passed the
  setting at all, so all three choices drew the same thing.
- **The players' server and subtitle rows fit inside the glass.** A
  `HorizontalScrollView` measures its child with an UNSPECIFIED width on its own
  axis — that is what makes a sideways drag possible at all — so the width the panel
  pinned on its content was IGNORED: every row was measured against no width, its
  weighted text column was handed the label's full intrinsic width instead of
  shrinking, the content came out wider than the panel, and each row's trailing pill
  (the HLS/DASH badge, the checkmark) landed past the curved edge. `PanelReach` is
  that missing half: a child that has been given an explicit width is measured at
  exactly that width, so the rows are bounded by the panel, the labels ellipsize and
  the pills sit inside the glass. The old hand-written walk that pinned every nested
  scroller to the panel's FULL inner width is gone with it — that is wider than the
  space a strip actually has once the row's own padding is taken off, which is why a
  chip strip ran out under the glass by exactly that padding.
- **Every player box scrolls, vertically and sideways.** The panel's height cap is
  now corrected against the panel's own MEASURED height instead of a predicted
  padding: the silhouette's padding is derived from its size, so a guess drifts as
  the panel grows and every pixel of drift is a pixel of the panel below the bottom
  of the video, where no drag can reach it ("the subtitle box is unscrollable").
  The excess comes off the list and the next pass lays out again, once per measured
  height so the passes cannot chase each other. The vertical scrollbar still appears
  exactly when the list can move.
- **R-rated films leave the catalogues while the adult-content switch is off.** The
  switch filtered items it could JUDGE, and a catalogue row carries no certificate to
  judge: a `/discover/movie` answer is a title, a poster and a year. Every row, browse
  grid, preset and Production/Network/Person catalogue in this app IS a discover
  query, so the ceiling is now asked of TMDB itself (`certification_country=US`,
  `certification.lte=PG-13`) in the one place every TMDB request passes through —
  `TmdbResolver.apiGet`. TV is deliberately not capped (TV-MA is not in the adult
  set, see `docs/CONTENT_FILTERS.md`), a query that names its own certification is
  left alone, and Home drops its cached feed and rebuilds the moment the switch
  moves, so the change is visible on the screen the user is looking at rather than on
  the next launch. The cost, documented there too: TMDB will not match a film it has
  no US certificate for, so a niche row comes back shorter than with the switch on.

### Added

- **Poster styling → Edge light: a light you place.** The new `LIT` treatment lights
  the card from a point the reader chooses and takes it down towards shadow
  everywhere else — which is what makes a flat poster read as a lit object, and what
  the reference look in the screenshots is doing. Two radial gradients, no image, no
  blur, nothing cached: a soft pool of light centred on the point, plus a shadow that
  grows with distance from the same point. Settings draws the real thing in a
  draggable preview (point at the place, or drag the dot) plus a strength slider; the
  point is stored as two fractions of the card, so it lands in the same place on a
  90dp grid cell and on a 400dp hero.
- **Manga → Browse: the engine search box is always there, and a 0.5s hold pins an
  engine to the top.** The box used to appear only past six engines — with four
  installed and a name to find, the reader had no way to filter at all. A hold on an
  engine row (the same 0.5s hold, timed by the same helper, as the Home picker's
  multi-select) reveals a pin; a pinned engine is drawn above every other one, wears
  the accent and a pin mark so the top of the list explains itself, and the choice is
  stored by engine id.
- **A manga engine's own page now offers Popular and Latest as tabs**, so switching
  between the two lists happens where the reader already is instead of by going back
  and pressing the other pill.
- **A studio's (or network's, list's, person's) catalogue has its own search box.**
  It filters what is loaded AND walks further into the catalogue (up to eight more
  pages) until the title is found — and it stays on the page: it does not hand the
  name to the app's Search tab, which is what it used to feel like. "Looking further
  into this catalog…" says so while it works.

---
## 0.10.13

The reader is the ported one FOR REAL this time, the player's server/subtitle
rows fit inside their box, and there is a single switch for adult content.

### Added

- **Settings → Content & Filters → Adult content (on by default).** On, the app is
  exactly as it has always been. Off, adult and R-rated titles leave every
  catalogue, shelf, search result, collection grid and "Continue watching" row, and
  18+ extensions disappear — installed ones stop being used at all (they are not
  even instantiated), and store listings stop offering them for install. Nothing is
  deleted or rewritten: flip it back on and everything is there again. What counts
  as adult is deliberately specific — an explicit marker (TMDB's `adult`, a
  provider's own `TvType.NSFW`), an 18+/adult genre tag, a whole-word title match,
  and the film certificates the trade calls adults-only (`R`, `NC-17`, `X`, `18`…);
  TV-MA is excluded on purpose, because hiding every TV-MA show would empty the
  shelves of someone who only meant to turn off adult material. `docs/CONTENT_FILTERS.md`
  is the whole rule.

### Fixed

- **The reader is Nekoread's real chunked renderer, ported — their `WebtoonChunkedImageView`, not a token fix.** 0.10.12's routing rule was right and the file it routed to was worthless: `WebtoonSubsamplingItem` is dead code in Nekoread (nothing in that app calls it), and an ordinary comic page (~1.8–2× its width) never matched `h > 3w` anyway, so the user saw literally no change. This build ports the file Nekoread actually draws strips with: the page's FILE is region-decoded into chunks of at most 2048px, nearest-to-the-viewport first (two workers per page, three decodes at once across all pages), decoded chunks are kept for the page's lifetime, only the far ones are recycled past a 64MB budget, and `onDraw` draws just the chunk range overlapping the viewport plus one chunk of margin. Nothing it hands the compositor is ever bigger than one chunk, however tall the page is — which is the one property the page-sized bitmaps never had. A chunk that fails is retried once when it enters the window, and a chunk the reader is looking at that fails reports the page broken, so a damaged file becomes the reader's Retry row instead of a black band. The short-page half was brought down to Nekoread's own budgets too (`1_000_000` px, `2048` on either axis — the old 11M-pixel budget permitted a 1080×10000 page, which is still not a texture), and the now-unused `SubsamplingScaleImageView` dependency is gone from the build.
- **The player's server and subtitle pills fit inside the panel, with no sideways drag to find them.** Each row is `[marker][label, weight 1][pill][chevron]`, and a weighted label only shrinks when it is measured against a BOUNDED width — but the panel's reach measures its content with an unspecified width (that is what makes a horizontal drag possible at all), so every row was measured at its label's full intrinsic width, the trailing pills landed past the panel's right edge, and a sideways drag inside a vertically scrolling list was the only way to reach them. `applyHeightCap` now pins the content to the panel's inner width (and bounds the nested chip strip from the same call), so labels ellipsize and the pills stay in the glass on every row.

### Notes

- Nothing is published to the main release: this build is on the **continuous** release, like every push to `main`.
- `docs/READER.md` section 4a is rewritten around the chunked renderer (and lists all five shapes that were tried); `docs/PLAYER_PANELS.md` gains the content-width rule; `docs/CONTENT_FILTERS.md` is new. Read them before touching those paths.

## 0.10.12

The reader is the ported one now, the player's boxes are the ones you screenshotted
as correct, and the detail page draws its title the way the reference client does.

### Fixed

- **The reader IS Nekoread's reader, ported — the two-shape rule and the subsampling view, with that app's own settings.** The honest version of what was wrong: Nekoread's source has been in this repo's workspace for days, I read it, and then I reimplemented a reader anyway — three times — instead of porting the one that works on your phones. So now it is ported, and the port is the rule that makes the difference: **a page taller than 3× its own width is a strip and is never decoded into a bitmap at all.** It goes to `NekoPageView`: `SubsamplingScaleImageView` (the Tachiyomi fork, `com.github.tachiyomiorg:subsampling-scale-image-view`, commit `66e0db195d` — the same artifact and commit Nekoread uses) inside an `AndroidView`, given the page FILE, region-decoding a base layer plus the tiles the viewport needs. Every setting is theirs, unchanged: FIT_WIDTH, pan-inside, minimum tile DPI 180, minimum DPI 1, zoom off, eager loading off, a `FileInputStream` provider, and a view that ignores touch so the reader's list keeps every gesture. Everything at or under 3× the width is an ordinary page and stays one bitmap — and 3× is not taste, it is arithmetic: at the screen's own width that is at most 3×1080 = 3240px tall, under the 4096px texture limit every phone reports. `PageBitmaps` now holds exactly that (never wider than the screen, never taller than 3200px), refuses a strip outright, and logs the size it produced (`page 800x1600 → bitmap 800x1600 (sample 1)`) so the next report can say which shape a page took. `Enhance images` applies to short pages (the filter is a Compose colour filter and the view has no equivalent).
- **The player's panels are back to the geometry you screenshotted as correct.** 0.10.6, 0.10.9 and 0.10.11 each tried to get the panel's width by MEASURING the layout instead of computing it, and each of them produced the same wrong box: ~72% of a 2460px screen, sitting left of centre, with the right of the video empty — because the window is sized FROM the panel, so the panel's width cannot be asked of the layout. The width is the smallest of three ceilings again: the window less the glow's margins, the window's HEIGHT × 0.93 (the term that binds in landscape, and what makes the box a floating pane rather than a wall), and 560dp — floored by 140dp but never over the room that exists. The panel goes straight into the dialog's root and the WINDOW is `panelW + 2*halo` wide, `MATCH_PARENT` tall and centred, with its width re-asserted on every layout pass so a rotation or a split-screen resize cannot leave it wrong. The layout-measuring host is deleted. Its height cap is unchanged: the panel itself stays wrap-content and the list inside it is capped against the room the frame really has, so a long list scrolls INSIDE a box that is always fully on screen.

### Added

- **The detail page draws the title as ART over the header, and keeps it on screen as the art scrolls away** — the reference client's header, which is what you asked for: the title is the title's own transparent wordmark from TMDB (`logo_path`, one cached request per title, the best-voted English logo with the language-neutral ones as fallback), drawn on the lower part of the header art; as you scroll, the art scrolls up and away while the wordmark does NOT move with it — it rises to the top of the page, shrinking a little, and stays there acting as the title. The title is never printed twice: the text title is drawn only for a title TMDB has no wordmark for, so this can only ever add to a page, and a back button takes over from the art's own once the art is mostly gone. `docs/DETAIL_HEADER.md` has the geometry and the rules.

### Notes

- Nothing is published to the main release: this build is on the **continuous** release, like every push to `main`.
- `docs/READER.md` section 4a is rewritten around the two-shape rule; `docs/PLAYER_PANELS.md` around the restored width arithmetic. Read them before touching either path.

## 0.10.11

Both of the things in the screenshots, fixed at the root this time: the reader no
longer draws a page in PIECES of any kind, and a player panel's width no longer
comes out of a number.

### Fixed

- **The reader draws a page as ONE whole bitmap, and every "draw it in pieces" path is gone for good.** Two builds tried to draw a page in pieces — 0.10.9 cut a decoded page into 2048px slices and stacked them, 0.10.10 handed the file to a region-decoding subsampling view (the Tachiyomi/yomi "tall strip" reader) — and on your phone both came out as the same thing: artwork scattered in displaced blocks across a white field. The lesson is not that the tile size was wrong. A page drawn in pieces depends on the PLATFORM getting tile placement, texture limits and region rectangles right, and that is not something this app can verify or promise on someone else's phone; a whole bitmap depends on none of it, because there is nothing to mis-place when the pixels arrive in one piece. So `manga/PageBitmaps` decodes the file ONCE — at most at the width the screen can show (an ~800px source is never upscaled to a 1080px screen) and at most ~44MB, which for a page bigger than that means the next power-of-two reduction down: a 1080×20000 manhwa strip becomes 540×10000, the sharpest COMPLETE copy a phone can hold — and the page is drawn by an ordinary Compose `Image`, the same thing every poster in this app is drawn with. The subsampling library, its JitPack dependency and the view that used it are deleted, so there is no second drawing path left to reach for.
- **A page starts drawing a few hundred milliseconds sooner, because the page the thumb is heading for is now DECODED ahead, not only downloaded.** The loader's preload window still warms the bytes of ten pages ahead (that is the slow half — seconds), and the reader warms the PIXELS of exactly the next page (`PageBitmaps.prefetch`): one page, because a decoded page is worth a few hundred milliseconds on an ordinary page and megabytes of heap on a big one, and warming ten of them would evict the page on screen to make room for pages nobody is looking at. The pre-warm gives up rather than queue in front of a decode that is already running, so it can never make the page under your thumb slower.
- **A player panel is as wide as the room the dialog is actually standing in.** The panel's width used to be computed from a number — first the window metrics, then the configuration, then a fraction of the screen height — and all of those can be right about the WRONG AXIS: in the landscape player they answer 1080×2460 inside a 2460×1080 window, which is why every panel came out ~915px (330dp) wide on a 2460px screen with half the picture empty beside it and the right-hand column of each row beyond the edge. A dp cap alone could not fix it either: 620dp is a width whose pixel value depends entirely on the density a device reports, and where that is small the panel is a narrow box on a wide screen. There is no number any more. The panel sits in a one-child `PanelWidthHost` that is `MATCH_PARENT` in the dialog's root, so the width it is measured with IS the width the window gave the dialog — any orientation, any insets, any device — and the panel takes that room, up to 95% of it (97% for the flat skins) and up to the ceiling the rows were designed for. Nothing re-measures it afterwards; the layout pass does it, by itself, on every layout change there is.

### Notes

- Nothing was published to the main release: this build is on the **continuous** release, like every push to `main`.
- Section 4a of `docs/READER.md` is the full story of why a page is one bitmap, including the two shapes that were tried and failed — read it before touching the reader's image path.

## 0.10.10

Two things were still wrong after 0.10.9, and both are fixed at the root this time.

### Fixed

- **The reader draws pages with the subsampling reader (the same one Nekoread uses), and that is what finally ends "the image is breaking".** 0.10.9's fix — decode the page, cut it into 2048px slices, stack the slices — was a recipe that had to guess the device's texture limit, and a page is not "smaller than the limit" just because it was cut up: a webtoon page is still 1600×8000, the phones that report a 2048 limit are exactly the ones the slices have to satisfy, and anything that still ends up over the limit goes straight back to the tiled draw that scatters the artwork. So the page is no longer drawn from a bitmap at all. A new `SubsamplingPageView` (the Tachiyomi fork of `SubsamplingScaleImageView`, the artifact Nekoread draws with) opens the page's file and **decodes it as tiles**: a downsampled base layer about as sharp as the screen can show, plus the higher-resolution tiles the viewport actually needs, and never the page at its own size. There is no page height, no page width and no device that can make it break, because nothing in the drawing path depends on a texture limit any more — the tile ceiling is pinned at 2048px for exactly that reason. The loader keeps doing what it is good at (the extension's own headers, a complete image of a known format, ten attempts, a file per fetch) and stops at the file; the decode-it-into-a-bitmap-and-cache-it half is gone with it, so a page now costs its compressed bytes instead of up to 20MB of pixels.
- **A dialog's panel is measured from the dialog's own frame, so it is the right width on every device.** The width was taken once from `windowSize()` — which on some devices answers in the display's natural orientation — and never re-checked, so a wrong answer stayed wrong for the life of the panel: the box came out a third of the screen wide (915px of 2460px) with the right-hand column of every row beyond its edge and nothing to drag. `applyPanelWidth` now re-derives it from the frame's own measured width on every layout pass, which is the width the window really got, in whatever orientation the device is in. `windowSize()` is only the opening estimate now.
- **A panel can no longer be pushed off the bottom of the player.** 0.10.9 sized the dialog window's height itself from `windowSize()`; when that number came back in the wrong orientation the window was asked for a height taller than the screen, the platform kept its top on the display, and the panel — centred inside a frame taller than the screen — was drawn from the middle downwards with its last rows below the fold. The window is `MATCH_PARENT` in both axes again and left to the window manager, which is the only thing that knows the screen's real rect; every cap comes from the frame and the display area actually visible to it.

### Notes

- Nothing was published to the main release: this build is on the **continuous** release, like every push to `main`.
- The reader's drawing path is now the same code as this app's manga sibling Nekoread (same `SubsamplingScaleImageView` artifact and commit), which is where the shape of this fix comes from.

## 0.10.9

Two reports, both fixed at the root: **a manga page that is drawn as a wreck**
(displaced bands of artwork, the same piece repeated at an offset, white seams,
the page running off both screen edges) and **player boxes whose content is cut
off and will not scroll, sideways or down**.

### Fixed

- **A page is drawn as GPU-legal SLICES, and that is what was breaking the reader's images.** The bytes were never the problem — and that is why fetching them again, validating them harder and retrying ten times changed nothing. A drawn image is uploaded to the GPU as a texture, and a texture cannot be larger than the device's `GL_MAX_TEXTURE_SIZE`; an image bigger than that is not drawn by Skia, it is drawn as a **grid of tiles**, and that fallback mis-places the tiles' source rectangles on the drivers these phones have. The result is exactly the report: bands of the page displaced sideways, the same artwork repeated at a fixed offset, a white seam at every tile boundary, and the whole thing enlarged past both screen edges. 0.10.8's own change made it worse — the loader started decoding pages up to 8K tall and handing them straight to the compositor, which is over the texture limit on most phones. A decoded page is now **cut into slices no taller than 2048px** (half the smallest maximum texture size any GLES2 device may report, so a slice is a legal texture everywhere) and the reader stacks them inside a box of the page's aspect ratio, so a strip still reads as one image — edge to edge, uninterrupted, and cheaper to upload than the single huge one was. The paged modes that scale the whole page into the viewport ask for a decode that already fits the viewport, so they keep arriving as one piece.
- **Player boxes are the size of the box again, and they scroll in both directions.** The panel was being sized from the display's *portrait* metrics inside the landscape player: `windowSize()` fell back to the window metrics before the decor had been laid out, those answer in the display's natural orientation, and the cross-check with the configuration could only ever *shrink* an axis — so the axes could not be put back the right way round. The panel came out a third of the screen wide (915px of 2460px), which is why the right-hand column of a long row was cut off with no way to reach it, and every height cap was measured against the wrong axis. Now the raw figure is turned to the orientation the configuration reports before anything is compared with it, so the width is real.
- **A long list can no longer end up with nothing to scroll.** The height cap used the dialog frame's own measured height as "the room", which is not the same as the room that is actually on screen: a window laid out taller than the display measures a room that runs off the bottom of it. The cap is now the smallest of the frame, the area of the display that is genuinely visible to this window, and the rotation-corrected window size — and it is **always applied**. The old rule lifted the cap entirely whenever the rows were judged to fit, and if that judgement was wrong (a long list measured short) the scroll view was given no ceiling at all: it grew to the full height of its content, the panel grew with it past the bottom of the video, and because the view was then exactly as tall as what it held there was nothing left to scroll — the last rows sat below the screen and no drag could bring them up. A `WRAP_CONTENT` scroll view with a ceiling shrinks onto short rows by itself, so nothing was ever gained by removing it.
- **A box's content can be dragged sideways when it does not fit.** The rows hang inside a horizontal scroll (which fills its viewport, so nothing changes when they fit) and the panel's curve-bend follows the drag instead of fighting it. Its scrollbar appears only when there is really something out there to reach, exactly as the vertical one now does — both are driven by whether the view can actually scroll, rather than by a guess about the content's size. A row that scrolls sideways **on its own** — the source chooser's engine chip strip — is sized to the panel's inner width instead of being left to answer with the width of all its pills, so it scrolls inside the panel and never stretches the ordinary rows out past the glass.
- **A retry asks the CDN for a fresh copy.** From the second attempt on, a page request carries `Cache-Control: no-cache`: a retry means the body that came back was unusable, and the likeliest reason a later attempt gets the identical bad body is an edge that has it cached (a header, not a URL change, so signed links stay valid).
- **Pages that give up now say why in the app's log.** Every failed page records its reason and URL, and a page whose decode is taller than one drawn slice records its real decoded size and slice count — the two facts a report about a broken page needs.

### Notes

- Nothing was published to the main release: this build is on the **continuous** release, like every push to `main`.

## 0.10.8

The follow-up to 0.10.7, again straight from a batch of reports: **broken pages are re-fetched instead of shown**, **the reader's settings sheet can no longer slice its own last row**, **webtoon is the default reading mode**, **every manga page has a WebView button and says when a verification is probably in the way**, **scrolling a chapter is a blit instead of a decode**, **the reader has its own Enhance**, **the player's boxes stop cutting their edges in other languages**, **the subtitle search opens with the title in English and the episode**, and **"Downloads" fits in its own button**.

### Fixed

- **A page that arrives broken is now re-fetched rather than drawn.** 0.10.7 added structural checks (promised length, magic bytes, end-of-image marker), and pages still came out as artwork cut into displaced rectangles with blank gaps through it. Those bytes pass every one of those tests: the file is a *complete, correctly terminated* JPEG whose scan data is damaged, and a decoder handed it does not fail — it returns a bitmap full of displaced blocks, so nothing ever retried. The load now ends with the only test no header can stand in for: the bytes are **decoded**, at the size the page will be drawn. A page that will not decode is a failed attempt, which is the existing retry ladder (ten attempts, short backoff), and a page that still will not decode shows the per-page **Retry** button. A page that reports itself ready but has no pixels (a file the system cleared, a decode that failed after the fact) asks for itself again exactly once, so the reader can never sit on a spinner for a page that will never appear.
- **The reader's settings sheet no longer cuts off its own last row.** The sheet's content did not scroll, so on a short screen the last group overflowed the sheet's edge — and a half-drawn `Switch` sitting under another row's switch is what read as "an extra toggle near Keep screen on". Nothing was duplicated: the row below it was sliced. The content scrolls now, so every setting (including anything added later) is reachable on any screen.
- **The player's boxes stop cutting their edges when the app is in another language.** In Arabic (and any right-to-left language) the subtitle settings rows came up with their own buttons' leading edge sliced off, and the same shape of problem hit longer translated words at the other end. Both rows that pair a label with controls were rebuilt: the **label has a line of its own** and the controls share a full-width row below it, weighted so nothing can be wider than the panel. The caption-style sheet keeps a scrolling pill row — but the row is pinned to LTR, because Android's horizontal scroller opens a mirrored layout scrolled to the far end, which is exactly how a row ends up showing its first pill cut in half.
- **"Load from internet" opens with the name the subtitle sites actually index.** The box sometimes opened empty (the title came from an item the intent did not carry) and, when it did fill, it filled with the *display* title — which a TMDB language change had localised, so the sites matched nothing. The seed is now the item's **original/English** title (the same rule the cross-extension server search follows), with the **episode** appended for a series (`"Castle TV S01E01"`), falling back through the intent's title, the history title and the top bar's own text, so the box cannot open blank. The search still sends the structured season/episode beside the clean title, and the panel's window now resizes for the keyboard — a field that exists behind the IME looks exactly like a field that is not there.
- **"Downloads" fits in its own button.** The My Stuff strip's labels were ellipsized ("Downloa…") and, on a narrow screen, sliced. Each label is now **measured and sized to fit** the room its icon leaves (the same trick the taskbar uses), and when even a readable size will not fit, the icon yields instead of the word.

### Changed

- **Webtoon is the default reading mode.** Manhwa and webtoon releases are what almost every installed manga extension carries, and a chapter of one is a single vertical strip: opening it one page at a time cut every page at the knee. Paged left-to-right and right-to-left are still one tap away in the reader's settings, and a stored choice still wins.
- **Multi-select in the provider pickers needs a 0.5s hold instead of 1.5s.** Still far longer than a tap, still cancelled by a drag, and ticking four extensions is now a gesture rather than a wait.

### Added

- **A WebView button on the manga detail page and in the reader.** The Manga tab's globe was per engine, which is the right place to fix a blocked catalog — but a site can put its Cloudflare challenge in front of the **chapter list** or the **page list**, and then the page you are looking at is the one that comes back empty. Both screens now carry the button, and both re-ask the source the moment the verification view closes (the clearance is flushed to disk before it does).
- **A nudge when a list has not arrived in ten seconds.** A spinner on its own cannot say why nothing is coming, and the usual answer is a verification. Ten seconds in, a **small chip** appears for three seconds — "Not loading? Tap to verify in the WebView" — with its own ✕, on the catalog, the chapter list and the reader. Tapping the chip opens the verification view directly; it re-arms only on the next real wait, so a slow-but-working site is never nagged.
- **The reader can Enhance the pages.** A switch in the reader's settings applies a mild colour matrix — a little more saturation, a little more contrast, a lifted black — **at draw time on the GPU**: no second decode, no extra memory, no frame cost, so it can stay on while scrolling. The scans most sites serve are washed out, and this is the honest version of fixing that.
- **Scrolling a chapter is a blit, not a decode.** The loader now decodes each page itself, **downsampled to the reader's own width** (and to 8K tall, because a 20,000px webtoon strip uploaded whole is where the dropped frames come from) and in RGB_565 — half the bytes of ARGB, and a page needs no alpha. Decoded pages live in a **byte-budgeted cache** (a sixth of the heap, 48–320MB) keyed by page, so a page that scrolls back into view draws from memory. The ten pages ahead and eight behind are decoded **eagerly** as their bytes land, the rest of the chapter is pulled to disk only, and the preload window is recomputed only when the reader has actually moved a page or two — walking two hundred page entries on every frame of a fling was its own cost.

### Notes

- Nothing was published to the main release: this build is on the **continuous** release, like every push to `main`.

## 0.10.7

A reading-and-fixing build, all of it from the same session of reports: **the reader's page bar finally goes where you tap it**, **a webtoon no longer stops at a chapter boundary**, **a page that arrives broken is re-fetched instead of drawn**, **a site you verified stays verified**, **"next chapter" steps to the next chapter instead of the next scanlation group's copy of the one you are on**, **the My Stuff switches can be switched back on**, and **the Search tab's provider list is a vertical, searchable picker instead of a scrolling row of chips**.

### Fixed

- **Tapping or dragging the reader's page bar lands on the page you aimed at.** Both gestures used to flash to the target page and then snap straight back to the page you started on. The cause was a feedback loop, not a gesture problem: the page bar moved the surface by setting the page, and the thing that carried the move out was an effect keyed on *the very page the surface reports* — so as soon as the surface reported the move it had been asked to make, that effect restarted, cancelled the scroll it was in the middle of, and the surface fell back to where it had been. Dragging was worse, because every page the finger crossed started its own scroll and the scrolls cancelled each other, which is the bar "going back to the point where I started dragging". The reader now **speaks in requests**: the bar, the arrow buttons, a D-pad press, a chapter jump and a restored position all publish a request that a single collector (one per body, started once and never re-keyed) carries out through a conflated channel — so nothing can cancel a move, and the surface's own position reports may only update the readout. A drag now crosses pages with the finger and stops where the finger stops.
- **A webtoon keeps scrolling from one chapter into the next.** In webtoon mode the reader held exactly one chapter, so the strip stopped dead at the last page of it. The strip is now a **run** of chapters: reaching the end fetches the next chapter and appends it, and scrolling above the top fetches the previous one and **prepends** it while holding the page under your eye exactly still, so reading up past chapter 3's first page shows chapter 2's last pages. A **chapter title card** is drawn where each chapter begins — "when chapter 2 starts, it shows chapter 2" — because a strip with no page breaks has no other way to tell you the chapter changed.
- **"Next chapter" loads the next chapter, not the next scanlation group's copy of the current one.** An aggregator lists one chapter once per group (the chapter list in the report had "Chapter 1" five times in a row), and the arrows walked that raw list — so pressing next on Chapter 1 opened Chapter 1 again, from the group below it. The arrows now walk **one entry per chapter number**, and they prefer the release by the group you are already reading when there is one, so stepping forward keeps the translation you started. The chapter picker still lists every release, because choosing a different group for the chapter you are on is a real thing to want.
- **A page that arrives broken is re-fetched, not drawn.** Some pages appeared as artwork cut into rectangular slabs with black gaps through it. That is what a decoder does with a **truncated** body: nothing fails, so nothing retried, and Skia filled the missing blocks with black. Page images are no longer handed straight from the network to a decoder. Each one is fetched with the extension's own headers, checked against what the CDN promised (`Content-Length`), checked to be an image at all (magic bytes — this is also what catches a hotlink refusal or a bot wall answering as a "200"), and checked to be **complete** (every image format says how it ends: a JPEG's `FF D9`, a PNG's `IEND`, a GIF's `0x3B`, WebP's own declared length). A page that fails any of those is fetched again, up to **ten attempts** with a short backoff — and if all ten fail, that page alone shows a **Retry** button with the attempt count, so one bad page never costs you the chapter. Pages also no longer go through the poster loader's User-Agent/Referer rewriting, which was inventing browser headers for images that wanted the extension's own.
- **A site you verified stops asking you to verify it.** The clearance the verify WebView earns is a `cf_clearance`, and Cloudflare binds that cookie to the User-Agent it was minted for. The app was sending it under a *different* UA — the extension client's own hard-coded Chrome string — so the site threw the clearance away and challenged every request again, which is exactly "I verified it and it still says I need to verify". The extension client, the challenge solver's WebView and the verify WebView now all present the **same** User-Agent (Hikari's effective WebView UA), a request that carries a clearance is presented under that UA whatever the extension set, and the cookies are **flushed to disk** the moment a verification completes so they survive the process. Two fingerprints fighting over one cookie is also what used to make a verified site lapse back into challenging: every background solve overwrote the user's own clearance.
- **The My Stuff section switches can be switched back on.** Library/History/Downloads each have a switch, and the moment one was switched **off** its switch became dead — the disabled rule was `on && count > 1`, which disables a section precisely when it is off, forever. It is now "disabled only while it is the last one ON", which is the case that must not be switchable.
- **Switching a section off hides it immediately.** The hidden section's pill used to stay in the strip until you tapped one of the others, so a section you had switched off was still there (and still drawing) until you navigated. Now the page moves to the first section still kept the moment the switch flips, so the pill and the page go together.

### Added

- **The Search tab's providers are a vertical picker, not a chip row.** With a few dozen extensions installed the horizontal chip row could not show them all — a provider whose chip had scrolled off screen simply could not be picked. The tab now has one **scope button** ("All providers" / a name / "N sources") that opens the same picker Home's header uses: a full-height, scrollable list with its own **search box**, **category chips** (All, plus one per installed engine kind — Hikari, CloudStream, IPTV, Aniyomi, Manga, … — with personal catalogs listed under Collections), and **multi-select** (hold a row for a second, tick as many as you want, then Done). The separate "Filter providers…" field above the old chip row is gone, because the picker carries the search itself.
- **A whole chapter is preloaded around the reader.** The page loader keeps **ten pages ahead and eight behind** the page you are on, and then pulls the **rest of the chapter forward** in the background, so turning a page shows the next one instead of a spinner. It fetches four pages at a time so the CDN does not throttle the page you are actually looking at, and the preload is free to re-run on every page turn (a page already on disk or in flight is left alone).
- **A page's real shape is known before it is drawn.** The loader reads each page's pixel size out of the file header, so the webtoon strip and the paged fit are laid out at the right height from the first frame — no more strip that shifts under your thumb as pages land.

### Notes

- Nothing was published to the main release: this build is on the **continuous** release, like every push to `main`.

## 0.10.6

The player's panels stop cutting their own options off, and the manga half of the app gains the things a reader asks for first: **your tags are buttons**, **the reader has a chapter picker and a page bar you can aim at**, **a manga extension can open its own site to pass a Cloudflare check**, and **an engine's list can be searched** — including a 1396-entry repo.

### Fixed

- **No player panel can cut off its own options any more — in every Player UI (Default, Neon, Cinema, Minimal).** The download sheet showed one option and sliced the second at the panel's edge, and a long menu clipped its own text. The cause was sizing by **estimate**: every dialog opened at a guessed height (`~34dp` a row, plus a fixed guess for the message line), which is shorter than the rows really are — so the sheet opened one row short, and the cap that was supposed to correct it only ever ran from a layout callback that a short dialog need not trigger. The panel's size is now measured, never guessed: it opens at the room the **window** has (which can be too tall, never too short), then `applyHeightCap` recomputes the cap from the height the dialog frame actually measured **and from what the content actually measures**, and — the part that matters — **when the rows fit, the cap is removed entirely**, so the panel is sized purely by its content and there is nothing left that could slice the last row off. A scrollbar is now drawn only when the list really does scroll (one beside a list that cannot scroll reads as a list with something hidden in it), the measurement runs once more in the window's own pre-draw pass so the first frame the user sees is already the right size, and the secondary line of a menu row may use **two** lines, so a long explanation ("Nothing applied — the picture exactly as the server sent it") is read rather than ellipsised.
- **The manga reading history can be pruned.** There was no way to remove a single title from "Manga — continue reading" and no way to clear the lot — My Stuff → History and the Manga tab both list it, and neither row had a control. Each card now carries a small **✕** over its cover (that title's reading position) and the section heading carries **Clear all** (every title's), on both screens. "Clear all" asks first — it cannot be undone — and it clears only the reading POSITIONS: the titles you follow and their chapter lists are kept.
- **A search that found a manga opened the wrong page.** A result from a manga engine opened the *video* detail page, which asks that engine for servers it cannot serve. Manga results now open the manga detail page — which is also what makes the new tag search work end to end.

### Added

- **A manga's tags are buttons.** Tapping one offers **Search** (inside the engine that title came from) and **Global search** (every installed engine), because both are wanted and only the user knows which: the tag belongs to that site's vocabulary, while the same story is usually carried by several engines.
- **The reader has a chapter picker and a per-page bar.** A list button in the top bar opens the chapter list — in reading order, scrolled to the chapter you are on, with a **search box** (a 300-chapter series is not navigated by pressing "next") — and each row that is not the current one can be tapped to jump straight to it. The bottom bar is no longer a slider: it is **one dot per page** with the current one highlighted, and a **tap or a drag anywhere on it** lands on the exact page rather than on an interpolation of it — with the chapter's own structure visible at a glance (short chapters read as a sparse row, long ones as a dense one).
- **A manga extension's page has a search box and a Verify-site button.** Opening an engine's Popular/Latest list gives you a search field that asks *that* engine (the one you are actually reading from) and a globe that opens the extension's own site in a browser so you can pass its Cloudflare check — the WebView closes itself as soon as the clearance is in the jar, and the list reloads with it. Sites like ManhwaRead answer every request with a challenge until a browser has passed one, and there was previously no way to do that from a manga engine's own page; the empty state says which of the two problems you have and offers the same button. The Manga tab's engine cards carry the globe too.
- **An engine row opens the engine.** Tapping an extension's name on the Manga tab did nothing — only the **Popular** / **Latest** pills were live. The whole row now opens that engine's Popular list.
- **Search inside an extension's list, and inside a repo's index.** A repo's extension list (Keiyoushi alone is **1396 entries**) now has a filter box above the list, pinned so it stays reachable however far you have scrolled, and the Manga tab's engine list gets one once enough engines are installed to make scrolling a chore. "Install all" is hidden while a filter is active: it installs the whole repo, and offering it under a narrowed list would install a thousand extensions to someone who typed a name.
- **A repo's listing says which extensions are manga and which are anime.** One index can hold both kinds — the two ecosystems publish the same file format out of the same folders — so a mixed repo is grouped under **Anime extensions** / **Manga extensions** headings with **All / Manga / Anime** filter chips. The kind comes from what the app has already installed where it can (the authoritative answer) and otherwise from the entry's own index format, which separates the two branches exactly: Mihon/keiyoushi publishes the modern shape (`resources.apkUrl`, a string `versionCode`, `extensionLib`) and Aniyomi the legacy one (`apk` file name, integer `code`). A heading sorts the list; it never hides an entry.
- **My Stuff's three sections have their own switches** (Settings → Taskbar buttons → My Stuff). Library, History and Downloads share one taskbar button and one strip; each can now be switched off, and the strip re-lays itself out so the ones that are left share the row — no gap where the hidden one was, and no second row. One section always stays: the page would otherwise be empty, and a section that is switched off is still reachable from the rest of the app (History from the player's "Continue watching", Downloads from a download button, Library from the heart), the same rule a hidden tab obeys.

### Notes

- Nothing was published to the main release: this build is on the **continuous** release, like every push to `main`.

## 0.10.5

Three reported installs that could not work, and they had three different causes. **The Keiyoushi repo showed "Outdated App" instead of its extensions** (its `index.min.json` is now a two-entry "install Mihon" placeholder — the real list moved to `index.json`), **every manga extension install ended in "Download timed out"** (a repo published as `index.json` had its `apk` file names appended to the wrong folder, so the download was a 404 — a failure that used to be reported as a timeout), and **an Aniyomi extension behind Cloudflare reported "HTTP error 403" on Home** while its neighbours worked (extensions get their HTTP client from the app, and that client never cleared a challenge — AnimeOnline.Ninja's own source says "let CloudflareInterceptor solve it").

### Fixed

- **A repo listing is no longer "whatever `index.min.json` happens to say".** Keiyoushi turned that file into a two-entry stub — literally named **"Outdated App"** and **"Update to Mihon 0.20.1+"** — that older apps are expected to *display* rather than work around, so the Keiyoushi folder read like an app-version warning with nothing installable in it. The real catalogue (1396 extensions) is in the same folder's `index.json`. A Mihon/Aniyomi repo is now read from whichever of `index.json` / `index.min.json` / `repo.json` / `plugins.json` actually lists extensions (a `.pb` link is rewritten to its JSON sibling), a placeholder index and a file that is not an index at all are skipped instead of shown, and the entry format is read in **both** shapes the ecosystem publishes — keiyoushi's modern one (`extensionList.extensions[]` with absolute `resources.apkUrl`/`iconUrl` links and a string `versionCode`) and Aniyomi's classic one (`pkg`/`apk`/`code`). Adding the same repo through its other spelling no longer makes a second folder, and **keiyoushi** / **manga** now work as short names in the Add-repo box.
- **"Download timed out — check your connection" now means what it says.** An index entry's `apk` is a bare file name, so it has to be appended to the repo root as `<root>/apk/<name>` — and the root was derived by stripping only `index.min.json` from the repo URL. A repo served as `index.json` therefore built `<root>/index.json/apk/<name>`, a URL that 404s in milliseconds while the listing itself kept working: every extension in the repo looked installable and every install "timed out". The repo root is now derived from any index file name, the download streams with a 60s per-read / 5-minute overall budget instead of a 30s read timeout, the raw-GitHub ↔ jsDelivr mirror pair is tried, and the bytes are checked for the `PK` zip signature before installing — so a failure reports **what** failed (an HTTP 404 and where, a 403, a rate limit, a DNS failure, a file that is not an APK) instead of blaming the connection.
- **An extension behind Cloudflare now clears the challenge itself.** An extension's HTTP client is the app's (`NetworkHelper`), and Hikari's deliberately left out Aniyomi's Cloudflare interceptor — so an extension whose site serves a managed challenge got its 403 back as-is ("Home failed: HTTP error 403") while unprotected extensions in the same repo loaded fine. The extension can do nothing about it on its own: AnimeOnline.Ninja's source asks for exactly this ("…serve an identical managed challenge on every path, so keep each request single-hopped on ww3. and let CloudflareInterceptor solve it"). The extension client now attaches the shared WebView cookie jar's clearance to every request, and on a challenge (Cloudflare's own signature, a `cf-mitigated` header, or the "One moment, please" / `wsidchk` interstitial those DooPlay sites serve) loads the page in an **offscreen WebView with the request's own User-Agent** — a `cf_clearance` is minted for the UA that earned it — and retries once with the cookie. Nothing appears on screen, one solve per host serves every parallel request, and images are never solved (a challenge on a cover is a dead end; they get through on the document's clearance). If even that fails, the host is recorded so Home can say the extension needs a verification and offer a **Verify site** button right there next to **Retry**.
- **Extensions that link against Aniyomi's JavaScript engine work.** Aniyomi bundles `app.cash.quickjs`, and extensions call it directly — AnimeOnline.Ninja's `VrfInterceptor` runs `QuickJs.create().use { it.evaluate("$west + $east") }` to compute that site's `wsidchk` token, and it is the standard tool for the "compute the token the page's own script would have computed" kind of wall. Hikari ships the same engine under its own package but not that name, so such an extension died with `NoClassDefFoundError: app.cash.quickjs.QuickJs` — on a page whose challenge needs that computation, showing up as a 403. `app.cash.quickjs.QuickJs` is now bridged to the engine the Nuvio and SkyStream runtimes already use: no second native library, and the extension's own solves run.
- **The extension client's User-Agent is a current browser.** Cloudflare's managed challenge treats a stale browser version as a bot signal, and a `cf_clearance` is bound to the UA it was minted for — so the extension client's default UA is now a recent Chrome-on-Android, the same one the challenge solve presents.

### Notes

- A repo whose index is only served as protobuf (`index.pb`) is read from its JSON sibling; the binary format itself is not parsed.
- Nothing was published to the main release: this build is on the **continuous** release, like every push to `main`.

## 0.10.4

The taskbar loses two buttons and gains a reader. **Library, History and Downloads are now one button — "My Stuff"** — with a three-way strip at the top of the page, and the room that frees up is spent on a **Manga tab**: Tachiyomi/Mihon extensions (Keiyoushi and its mirrors) install through the same Extensions screen as every other engine, each title opens a detail page with its chapter list and a follow button, and the chapters open in a reader with **paged left-to-right, paged right-to-left and webtoon** modes.

### Added

- **Manga, end to end.** A manga engine is installed exactly like an Aniyomi extension — the two are the same file format listed by the same `index.min.json`, so the Keiyoushi repo is added through the existing **Add Aniyomi repo** box and its extensions install through the same buttons. What is new is everything after that: a **Manga** tab (Continue reading · your library · one card per engine with its **Popular** and **Latest** lists · a search box that asks every installed engine at once), a detail page per title (cover, description, genres, **Follow**, and the full chapter list with the chapter you are on marked), and a reader.
- **The reader.** Paged **left-to-right** (comics, manhwa), paged **right-to-left** (Japanese manga — including the arrow keys and tap zones being mirrored with it), or **webtoon**, one continuous vertical strip for releases drawn with no page gaps. Three fits (**fit width** and scroll a tall page, **fit height** for a spread, **fit the screen**), three backdrops (black / dark grey / white), keep-the-screen-awake (on by default) and an optional floating page number. Tap the middle of the page for the controls — slider, `12 / 40`, previous/next chapter — tap a side to turn the page, and on a television the D-pad does the same (◀ ▶ turn, OK hides the bars). Reading position is saved as you go, so the detail page, the Manga tab and My Stuff all resume on the exact page.
- **A manga engine's own request headers are replayed for its page images.** Manga CDNs routinely answer a hotlink with a 403; the extension's User-Agent/Referer/cookies now travel with every page request, so the reader shows the same images the site does.
- **"My Stuff" — one taskbar button for Library, History and Downloads.** They are all *your* things rather than "find something to watch", and three buttons for them left the bar with no room for a reader. The three are a small equal-width pill strip at the top of the page (the Nekoread History|Updates idiom), and **each keeps its own route** — every existing link to History or Downloads, from an empty state's "Browse" to a Continue-watching "See all", still lands on the right section. Hiding one of them in Settings → Taskbar buttons hides the button, as before.
- **Manga in the places you already look.** Followed titles appear as a shelf in **My Stuff → Library**, and what you were reading appears at the top of **My Stuff → History**, both drawing nothing at all when there is no manga.

### Changed

- **The Manga tab is off by default.** Like IPTV, it has its own switch in **Settings → Taskbar buttons → Manga** ("Off by default — switch on to read comics"): an install with no manga engine has no use for it, and a button costs every install room. The switch never counts towards the "you cannot hide the last tab" rule, exactly like IPTV's.
- **Home never shows a manga engine's rows.** A manga engine has its own tab and its own detail page; a manga row on Home would open the video detail screen, which has nothing to play. Manga engines are also left out of the video cross-extension server search for the same reason.
- **A manga repo is browsed in Extensions like any other.** Manga extensions list under the same repo group as Aniyomi's (one index format, one parser), install through the same format-aware path — the APK's own manifest decides which engine loads it — and uninstall through it too, removing the extension file only once nothing references it.

### Notes

- Chapter **downloads** and a manga source's own **settings screen** are not part of this release: the reader streams pages and caches them in memory/Coil's disk cache only. A source that publishes a preferences screen (`ConfigurableSource`) is loaded but its settings page is not surfaced yet.

## 0.10.3

Returning to Home can no longer draw a *different* provider's catalogs than the one you picked: your pick is read back from the store before anything is allowed to load, and a feed load that started under an older pick can never paint its rows over a newer one.

### Fixed

- **Home showed every provider's catalogs again after coming back from the player.** Reproduced from the report "I pick 1Shows, play a movie, load a subtitle from the internet, close the player and come back, and it is showing all providers again instead of 1Shows" — with the floating pill still reading **1Shows** while the shelves on screen were MovieBox's, which is what made this look like a lost pick when the pick was never lost at all. Closing the player can leave Android to recreate the activity (it does whenever the process was trimmed in the background), and the new Home screen built a fresh view model: its coroutine that watches the installed extensions ran before the coroutine that reads the saved pick back out of the store, saw a still-empty selection, and started an **all-providers** load. That load's rows then landed on screen — over the picked provider's own feed — while the pill (which reads the pick, not the rows) kept saying 1Shows. The pick is now read *first* and every other load waits for it; a load that is superseded by a newer one discards its rows instead of painting them (both cases are logged: "load superseded before it started" / "pick=… superseded by a newer load"); and an installed-extension list that has not been built yet (the first seconds of a process) is no longer mistaken for "this extension was uninstalled", which could have forgotten a pick outright.
- **Coming back from the player paints your pick instantly again.** The last feed of every pick is now remembered for the whole app session rather than inside the Home view model, so the activity recreation above cannot empty it — Home returns to the picked provider's rows from the cache instead of a spinner and a full re-fetch of catalogs you were already looking at.

## 0.10.2

Search gets the year filter it should always have had — a scrollable, multi-select strip you pick **before** searching — an IPTV channel is now searched only inside its own playlist, the provider pill and the Home feed can no longer disagree about what is on screen, and the subtitle search box is always pre-filled with the title.

### Added

- **Pick the years to search, before the search.** The Search screen's year control is now a slim, horizontally scrolling strip of every year (this year back to 1950) with **multi-select**: tap 2001, 2002 and 2004 and the grid keeps only titles released in those three years, alongside the existing **Both / Movies / Series** chips. The old control was a dropdown that listed only the years the results so far happened to carry, so a year could not be chosen until a title from it had already been found — the filter was picked after the fact instead of before the search. It is a short, thin box on purpose: a year is four digits and needs no more height than a chip row.
- **The year filter says what it dropped.** A result whose source never said when it came out cannot be shown as a 2023 title, so it is hidden while any year is selected — and the count line says how many ("…have no year, so they are hidden") instead of the grid quietly disagreeing with its own total.

### Fixed

- **A provider pick can no longer show every provider's catalogs.** Home enforces the promise the floating pill makes: a pick of exactly ONE extension draws that extension's catalog rows and nothing else — rows that are not its own are dropped, an empty feed for a pick falls back to that pick's own cached feed (never to whatever was on screen before), and each load is logged with the pick it ran for, the rows it produced and which providers they came from (Settings → Logs). This is the reported "no matter what provider I am selecting, it loads all providers' catalogs".
- **An IPTV channel is searched only in its own playlist.** A live channel exists in exactly one place, so an IPTV item now behaves like a repo that keeps its catalogue to itself (the rule a marked exception repo already got): the origin playlist is the only source asked — no sweep of the installed extensions, no borrowed episode list, no automatic subtitle search — and the loading line says "Searching this playlist for servers…" instead of counting extensions. Reported as "any IPTV link only search its own links, not every other provider's".
- **The subtitle search box always shows the title.** The box was pre-filled from the player's own item, which only exists when the launch intent carries a media id — so a live channel, or any stream opened without one, opened the panel with an EMPTY bar (the reported "it doesn't even show the name of the series or movie"). It now falls back to the title the player was opened with, and the automatic subtitle pass runs for those titles too (built from the same intent).
- **Subtitle search results that could never download are gone.** Every subtitle site behind "Load from internet" was checked live end to end — search response, parsed rows AND a real download of the file URL a row carries. Subscene's downloads now use its own `/download/<id>` route (a URL built from the id alone on the file host answered HTTP 404), and YIFYSubtitles was dropped because its files sit behind a Cloudflare challenge while its pages do not, so its rows could only ever fail after a tap.
- **A subtitle search asks for the original title when the box is untouched.** A TMDB display title can be localised ("Vengadores: Endgame") while every subtitle site indexes the original release name, so an untouched box now searches `originalTitle` — retyping the box overrides that, and a numeric id is only handed to TMDB when the item really came from TMDB (an extension's numeric id is the SITE's id, and asking TMDB for it resolves a different film).

## 0.10.1

"Load from internet" now searches **five subtitle sites of its own** — no addon installed and no account needed — the TV layout switches performance mode on by itself, and two reported regressions are fixed: an empty Home on "All providers", and a "Show all" page that opened on "Nothing here right now" for every row of a personal catalog.

### Added

- **Five subtitle sites, built in.** The player's **Load from internet** panel could only ask the Stremio subtitle addons you had installed, so an install with none was told "No subtitle addon is installed" and had no way to get a subtitle at all — and an install WITH one got "No subtitles found" for every title whose IMDb id could not be resolved (those addons answer an empty list, with HTTP 200, to anything but a `tt…` id). Five sites are now asked directly, in parallel, each with its own timeout:

  - **OpenSubtitles** — the catalogue behind its public mirror: a track per language with its own download URL, one request.
  - **OpenSubtitles search** — opensubtitles.org's own search API, which is the one that answers by **name** as well as by id, and reports each track's download count and rating.
  - **SubDL** — 35 languages, every release as a .zip.
  - **SubtitleCat** — the aggregator, as a plain .srt per language.
  - **Subscene** — the community library, one .zip per subtitle.

  The results list reads like the players people are used to: **language · site · release · format**, sorted with your own language first and then English, so picking a subtitle is picking a language rather than guessing at a rip name. Nothing is downloaded until a row is tapped, and a site that is down (or blocked) costs nothing but its own line going missing.
- **Find subtitles automatically** (Subtitles panel, off by default). With it on, a video that starts with no subtitle of its own — and with no subtitle addon installed at all — gets a track in your language, looked up on those sites as soon as playback begins, instead of the user having to open the panel every time.
- The "no subtitles found" line now says **which sources answered and with how many**, which sources stayed silent, and which ids were asked with — the difference between "this title has none" and "the lookup could not place this title".

### Fixed

- **Home on "All providers" showed nothing.** The all-providers feed asked for the rows of *no* provider, so the whole page fell through to its empty state ("No content yet") for anyone browsing All. Home on All is back to stacking every installed extension's home page, exactly as before; a pick made only of personal catalogs still shows just those catalogs' folder tiles.
- **"Show all" on a row inside a personal catalog opened an empty page.** The button handed the extension the personal catalog's internal source key ("prov|cs3|movie|trending") instead of the catalog's own id, so the extension answered nothing and the page said "Nothing here right now — the site may be blocking or down." It now opens the real catalog, with the extension's own type, and pages through it.
- **Performance mode follows the TV layout.** A television gets the lighter visuals the moment the app starts, and switching **Settings → TV & Remote → Layout** to the TV layout turns them on immediately (a phone whose user switches the layout on gets them too). Working the switch yourself makes it your choice from then on — it is never overridden again.
- **A subtitle site is only listed if its files can actually be downloaded.** Every endpoint behind the panel was checked live — the search response *and* a real download of the file URL a row carries — and one candidate (YIFYSubtitles) was dropped because its files sit behind a Cloudflare challenge while its pages do not: its rows would have shown up and then failed on every tap. The search, the parsed rows and the downloads of the five that remain were all verified against the live sites.

## 0.10.0

The personal catalog creator now has the whole set of collection settings the reference client has (minus the account/Trakt half, which needs engines Hikari does not have), a personal catalog can be pinned onto Home so its folders are there without picking anything, and a batch of reported player, subtitle and layout defects are fixed.

### Added

- **Pin a personal catalog to Home.** A collection's editor has a **Pin to top of Home** switch. Pinned, its folder tiles are drawn on Home even while Home is on "All providers" — no need to pick it from the source list — which is what makes a personal catalog behave like a shelf you own instead of something you have to remember to select. The collections list shows a pin beside the name.
- **Collections and folders are reorderable.** Every collection row in the creator has the same up/down pair the folders inside it have, and the order is what Home draws (and what a shared JSON carries).
- **Folders as tabs.** A collection can be browsed as **Rows** (one shelf per folder, as before) or as a **Tabs** strip: one tab per folder and, if you leave it on, an extra **All** tab holding every folder's catalogs. Each tab's body is exactly the folder's own page, so "Show all", imported title lists and a failing source behave the same either way. The setting lives in the collection editor.
- **Folder hero art and a hidden name.** A folder can carry its own **wide backdrop** and a **title logo** (a transparent wordmark drawn on its page header in place of the name), and it can **hide its tile's name** — the artwork says what it is.
- **"Always animate" for animated covers, with a per-device override.** An animated (GIF) folder cover plays while its tile is focused by default; the folder's own switch keeps it animating always, and **Settings → App Layout → Animate covers** is the device-level override — off draws the first frame, which is what a TV stick wants from a file a phone is happy with.
- **Copy your collections out as JSON.** The clipboard button in the creator's header writes every collection — covers, tile shapes, hidden names, pin-to-top, the view mode and every discover filter — as the same JSON the importer reads, so a personal catalog can move to another install, go to a friend, or be kept before a reinstall.
- **The full custom-source filter set.** A **Custom** TMDB source has an **Advanced filters** section: genres (TMDB's `,`/`|` id syntax), excluded genres, a release-date window, a rating window, a minimum vote count, original language, origin country, keywords and excluded keywords, companies, networks, a streaming-provider pair and a watch region. Blank fields send no parameter at all, so an untouched section changes nothing.
- **Imported files carry all of that too.** The importer now reads pin-to-top, the view mode, the "All" tab, a collection's backdrop and a folder's hide-title / GIF / hero settings, maps **LIST** and **DIRECTOR** sources (both engines already existed — the importer used to drop them), and passes the whole `filters` block through to TMDB, so a file produces the same rows here as in the app it came from. The import preview warns when a collection's sources are all catalogs you do not have installed (or Trakt lists), before you import it.
- **IPTV's + button adds a playlist where you are.** The IPTV tab's plus opens the add-playlist dialog itself — paste an M3U/M3U8/Xtream link, or pick a file — instead of sending you to Extensions, and the empty state offers the same button.

### Fixed

- **"Load from internet" finds subtitles for every title, not none.** OpenSubtitles v3 declares `idPrefixes: ["tt"]` and answers an EMPTY list with HTTP 200 to a `tmdb:` id or to a bare name (verified against the live service), so when the title could not be resolved to an IMDb id EVERY film came back with "no subtitles found". The lookup now falls back to IMDb's own suggestion endpoint (no key needed) for the `tt` id before giving up, the resolution walk is allowed to finish (it used to be cut off at 8 seconds, mid-walk, leaving only a `tmdb:` id in hand), and the "nothing found" line now says which ids were tried and what each addon answered — so a failure is readable from the screen instead of being a dead end.
- **The subtitle box — and every other player panel — is no longer cut off.** The panel's WIDTH was being capped by the window's HEIGHT, and in the landscape player the height is a third of the width, so every panel was clamped to roughly the screen's height and the right-hand column of each row (the **Change** pill, the **A−/A+** steppers, **+0.5s**) sat past the edge with no way to reach it. The width cap is gone (the height is capped where it belongs — the list itself, against the room the dialog really has), and the subtitle settings rows now put their controls in a horizontal scroller with a weighted label, so nothing can be sliced off in any of the four player skins.
- **A production company's logo no longer blanks out when you scroll past it.** The logos row is a lazy row, so scrolling a logo off-screen disposed its cell and dropped its bitmap — coming back showed the initials for a moment while the same image was fetched again. Decoded logos are now kept for the session, so a studio's logo is instant the second time (and on the next title that lists it).
- **The TV & Remote "Layout" explanation is no longer clipped.** That paragraph was the card's last child with only a top inset, so it sat against the glass's edge and its last line ran under the rounded bottom corner. It now gets the same 16dp inset the card's rows use, plus a bottom one.
- **The player's panel rows keep their own width as well.** The subtitle settings label can wrap to two lines and the controls scroll, so a long caption-style summary can never push a button off the panel.

Hikari now runs on televisions as well as phones — the same APK, whichever device you install it on.

### Added

- **Filter the search results without searching again.** The search screen now has a second chip row under the provider chips: **Both / Movies / Series**, and a **year** chip that opens a scrollable list of the years the results in front of you actually carry. Picking one narrows the grid on the spot — the sweep is never re-run, so "search moana, choose the year, keep only the film" is a tap rather than another search. A result whose provider never said *what* it is or *when* is kept, and counted in the "Showing 12 of 240" line, because whole extensions label nothing: hiding all of them would turn a filter into an empty screen that reads as a broken search.
- **Android TV, Google TV and Fire TV support.** The APK is universal: install it on a phone and it is the phone app you already know; install the *same file* on a television and it comes up as a television app — a navigation rail down the left instead of the taskbar, bigger posters, everything padded in from the screen edges, and a focus ring so you can always see what the remote's OK button is about to press. Nothing about the phone interface changes.
- **The remote does everything it should in the player.** Up/Down/OK bring the controls up, Left/Right seek 10 seconds (hold to scrub), the remote's Rewind/Fast-forward buttons seek 30 seconds, Play/Pause works at any time, and Back closes the controls before it leaves the player. With the controls up, the arrows move between the buttons as they should.
- **A TV & Remote settings folder**: the layout (Automatic, or forced TV/phone for a box that reports itself wrongly), **screen edges** — the safe area, because televisions crop a few percent off the picture, which is how a back button ends up half off the side of a Fire TV — **performance mode** (plain posters instead of the animated treatments, for the weaker sticks), and a written guide to what each remote button does.
- **A television launcher entry** with a proper TV banner, so Hikari appears on an Android TV / Fire TV home screen like a native app. Choosing a different phone icon never affects it.
- **Import a whole personal catalog from a file.** The clipboard button on the Personal Catalog creator (and the **Import collections (JSON)** card beside "New collection") takes the JSON file another client exports — six collections of folders and catalogs is one paste — and creates it as it stands: every collection, every folder with its own cover picture, and every catalog already pointing at the right thing. A folder that names a TMDB network, studio, collection or discovery query becomes a TMDB catalog; a folder that names an extension's catalog is matched against the extensions you have installed. The file's own cover images are used as the folder art, so a collection of Netflix / Prime Video / Disney+ folders looks exactly like the file it came from.
- **The Personal Catalog creator shows the folders themselves.** Under each collection's card is now a row of its folder tiles — wide, square or poster, wearing the logo or picture the folder was given — so you can see and open a folder without stepping into the collection first.
- **The interface is now fully translated in all 20 languages.** Every remaining label, button, hint, dialog and message was missing from the dictionaries (~9,500 entries added), covering the player and its panels, the settings screens, the collections and personal catalog screens, the extensions screen and the browser — plus the genre and credit tags that come from extensions.
- **A title's production companies, and everything they made.** The detail page now shows the studios and networks behind a film or series as their logos, and tapping one opens that company's whole catalogue — its films *and* its series, in one grid (TMDB answers the two from different endpoints, so they are merged). It is the "tap a production and see all of its work" flow, with the real TMDB ids behind it, so it works for a company no extension has ever heard of.
- **A franchise row.** A film that belongs to a collection (TMDB's `belongs_to_collection`) gets that collection as its own row on the detail page — "Shrek Collection", "The Dark Knight Collection" — with the other parts in release order, directly above Related/Similar.
- **The full release date** in the details block, printed in the language the app is using, instead of only the year.
- **Subtitle addons are asked for subtitles, not for catalogs.** Adding a Stremio addon whose manifest is `resources: ["subtitles"]` with no catalogs — OpenSubtitles v3 (`opensubtitles-v3.strem.io`) and the official SubDL addon (`api3.subdl.com`) are the two everyone meets — used to look broken. It was given TMDB's rows to browse (the fallback that exists for stream-only addons) and then answered no servers for anything, so it read as a useless repo. Those addons are now recognised for what they are: nothing is added to Home, and the player asks them for the title's subtitle tracks and lists them in the subtitle menu beside the server's own. Each is asked with the id it actually accepts (OpenSubtitles v3 only answers IMDb ids; SubDL is happiest with `tmdb:`), for the right type segment, with the `:season:episode` suffix for an episode, so a film and an episode both come back with real tracks. The tracks are capped, and ordered the app's own language first and then English. Each row now also carries the name of the addon that found it, and the cap is high enough for the addons to answer in every language they have instead of only the first few.
- **An IPTV tab on the taskbar — off until you switch it on.** Your playlists have their own tab now, next to Downloads, and it stays hidden until you ask for it: **Settings → Taskbar** has the IPTV switch beside the other buttons, and it is off by default, so nothing about the taskbar changes for anyone who does not use IPTV. Switch it on and every playlist you added appears there as its own tile.
- **IPTV browses like a catalog.** Each playlist's tile opens its own screen, where its groups are laid out as folder tiles — **wide**, **square** or **poster**, cycled from the button in the header and remembered — with the channel count on each and the group's own logo where the playlist provides one. Tapping a group opens its channels as a grid. Live channels are marked **LIVE**, a channel with no logo of its own gets a tile drawn from its name and group, and playlists are read in the background a few at a time so a panel with a hundred groups does not freeze the screen.
- **Several providers at once on Home.** Press and hold a provider in the "Choose an extension" picker for a second and a half and the picker becomes a multi-select: hold or tap as many providers as you like, then press **Done** in the picker's own header to save them all. Home then shows one catalog after another from every provider you selected, and a single tap switches back to the ordinary one-provider behaviour. The picker's header keeps the count of what is selected, so a long list never leaves you guessing.
- **A personal catalog on Home shows its folders, not its contents.** Selecting a personal catalog used to pour every catalog inside every one of its folders onto Home at once — the folders (animation, anime, Netflix, Amazon…) disappeared and hundreds of rows arrived in their place. Selecting one now shows the folders themselves, exactly as the Personal Catalog creator does, and tapping a folder opens just that folder's catalogs. **Show all** opens the whole flat grid when that is what you want.
- **The subtitle appearance and timing controls have their own panel.** Caption style, text size, sync and position used to be the last four rows of the subtitle sheet — which put them below the track list, and a film whose subtitle addons answer in twenty languages puts thirty-odd tracks there, so changing the caption style meant scrolling the whole panel to the bottom. They are a sheet of their own now (**Subtitle settings**), one tap away from a **gear** in the subtitle sheet's header or from its own row near the top, and the sheet itself is just the track list.
- **Load subtitles from the internet without leaving the player.** **Load from internet**, under the subtitle sheet's track list, opens a panel pre-filled with the title you are watching: press Search and every installed subtitle addon is asked again — by the name you typed, which is what fixes the case where the release's own name is not something the database knows. The results list every language found, says which addon each came from, and one tap downloads it, checks it, and starts showing it, with the same sync offset, size and style already in force. On an episode it searches that episode.

### Fixed

- **Aniyomi extensions can load again.** The container every extension resolves its dependencies out of (`Injekt`) was being filled with the library's own *reified* type references — `fullType<T>()`, an anonymous subclass that carries the type argument — and the release build's shrinker rewrites those subclasses away, so the constructor threw `IllegalArgumentException: Internal error: TypeReference constructed without actual type information` on the very FIRST registration. That throw aborted the whole registration block, so the container stayed empty and every extension then failed with `No registered instance or factory for type class android.app.Application` — the reported "Aniyomi still has an error and does not install". Each type is now registered through a reference that states its `Class` outright (nothing an optimizer can break), and each registration is allowed to fail on its own with the reason written to the log, instead of the first failure taking the other five down with it.
- **Importing a collections JSON finishes — and imports.** An import whose file names an addon you do not have (the common case: a shared file names its author's addons) used to walk every installed extension — 200 to 400 of them — asking each to enumerate its catalogs with a 12-second cap on each, so the Import button span for minutes and the whole app went unresponsive behind the sheet. An import now asks ONLY the extensions that actually look like the addon the file names (whose name or address contains the addon's own last segment), makes at most a few cheap guesses when nothing does, and gives the whole matching phase one hard deadline; anything it cannot match inside that is dropped and *counted* in the summary, which says so instead of hanging. The file in the report — six collections, 50 folders, ~200 sources of which ten name an addon — now imports in seconds with every TMDB and trakt folder intact.
- **A title opened in another language finds all of its servers, trailers and episodes.** With the interface in Arabic (or any language), a film opened from an Arabic-named extension came back with one trailer and only the Nuvio servers, while the same film opened in English had a dozen trailers and servers from everywhere. Two causes, both fixed. *Trailers*: TMDB's video list is answered in the language you ask in and only contains videos TAGGED with it, so `language=ar` returns the one Arabic trailer and stops — the query now asks for the app's language first, then English, then untagged videos, exactly the shape TMDB's own site uses, so the localized trailer still leads the row and the English ones fill it. *Servers*: the page keeps showing the name you opened it with, but the name every provider is SEARCHED with is the title's original, English one — for a TMDB row that name comes from TMDB, and for an extension row (which has no such thing) the item's own name is translated once, cached, and used for the search. "فلم موانا" was being handed to 400 extensions that none of them index, which is exactly why only the engines that resolve by TMDB id answered. The lookup is bounded (2.5 s, and skipped entirely for a name that is already Latin) and never changes what is on screen.
- **The accent, theme, scale and font you chose are on screen from the first frame.** All of those preferences were read asynchronously (DataStore) while the interface was seeded with a stock default, so coming back from the player painted the default gold accent and dark theme for a frame or two before the real values arrived — the reported "the accent I picked resets to the default and then changes back — it lags". Each one is now mirrored into plain preferences that the activity can read synchronously while it is being created (which the player already did for its own accent), so the first frame after a return is already the chosen colour, theme, scale and font.
- **No crash when a panel opens in portrait.** Opening a subtitles, source or audio panel while the phone was upright could take the whole player down with `Cannot coerce value to an empty range: maximum 256 is less than minimum 272` — the panel's height floor was allowed to outgrow its ceiling on a phone-sized window. The floor now always yields to the ceiling.
- **The glass panel can no longer be wider than the screen.** In portrait the panel's width was measured against the window's *height*, so it came out twice as wide as the phone, with the right-hand column of every row (the **Change** button, the sync ±0.5s, the **A+**) sliced off the edge and no way to reach it — a vertical list does not scroll sideways. The width is now measured against the window's own width, and re-measured if the window changes (rotation, split screen) while the panel is open, so the panel always floats with both rounded sides on screen.
- **No crash on a device without a WebView.** Plenty of cheap television boxes ship without one, and opening any web page (a title's site, a Cloudflare check, a link) used to crash the app; it now says so and closes the page instead.
- **The last servers in the list can be reached.** The services panel sized itself from the window rather than from the room the dialog actually had, so on some screens and aspect ratios it came out taller than the space it was given: the window is centred, so the extra height went off both ends and the bottom rows — the last one or two servers — sat outside the screen where no amount of scrolling could bring them into view. The panel now caps its scroll area against the height the dialog frame really measured, so the panel's rounded bottom is always on screen and every row above it can be scrolled to.
- **Subtitles and audio tracks: every track is listed, and only the one you pick is ticked.** Two separate faults in the same place. A stream that carries three Hindi subtitles (or three audio languages) marked all of them as selected in the picker, because a selection was matched by language rather than by track; and a sheet opened while the manifest was still being parsed offered Off/Auto only, so a release with subtitles in the file looked like it had none. Tracks are now matched by track (language *and* position, falling back to language only when the list has been rebuilt), both sheets wait for the track list to exist before answering, and a pick is confirmed on screen and re-asserted, so tapping a subtitle visibly selects and applies it.
- **The background extension search waits for the video.** The "keep asking every installed extension" sweep used to start the moment the first pass ended — while the player was still on its loading cover — so every play began with a background extension search racing the buffering video. It is now held from the moment the player opens and released on the first frame that really renders (with a time limit so a video that never starts cannot strand the work). Everything it finds still lands in the Sources list while the film plays, which is where it was always meant to appear.
- **Aniyomi extensions say why they did not load, instead of blaming Cloudflare.** An Aniyomi extension whose sources failed to load showed the generic "nothing came back from this extension — check its site in the WebView" note, which sent people looking for a Cloudflare check that was never the problem. The app now reports the extension's own reason, per extension ("none of its sources could be loaded", a class-load failure, an unsupported extension library). Two real defects behind those failures were fixed as well: an extension was only accepted if its extensions-lib version matched exactly (so one built against 16.1 was refused while 16.0 was accepted — the major version is what matters), and the extension's version was read from the wrong part of its name.
- **Adding an Aniyomi repository no longer fails on a valid index.** An Aniyomi `index.min.json` is usually a bare array, but many repos — and mirrors of the official one — wrap the same entries in an object. Those were rejected with "Value {…} of type JSONObject cannot be converted to JSONArray"; both shapes are accepted now, including entries keyed by package name.
- **An imported list is no longer told it needs an extension.** Opening a title that came from an imported JSON file (or any TMDB-sourced list) where no installed extension happens to carry it used to end on "The extension this title came from is no longer installed" — which was never true: the title never came from an extension. The page now stays, with everything that comes from TMDB (poster, description, ratings, details, cast, trailers, the franchise, Related/Similar), and the source search still asks every installed extension, so a playable server is found whenever one of them has the title.
- **An imported file that carries its titles is imported as titles.** A Nuvio-style file can put actual titles in a folder instead of naming a catalog to fetch; that shape was parsed into folders that then loaded nothing. It now becomes a list you can open, and a folder with no cover picture of its own uses the first title's poster instead of arriving as a grey tile.
- **The detail page's own labels follow the app language.** The TMDB status word ("Released", "Returning Series") and the vote count are translated now, alongside the genres and credit roles that already were.
- **A production company's logo is readable whatever colour it was drawn in.** TMDB ships these as transparent PNGs in two kinds — a white wordmark drawn for a dark backdrop, and a dark or coloured one drawn for a light backdrop — so drawing them straight onto the page made one of the two wrong: the dark navy Disney script on a near-black card is the "the logo is a solid black box" report, and a white wordmark would have been invisible on a light one. Each tile now measures its own logo (the average brightness of the pixels that are actually opaque — the transparent ones have their colour zeroed, so averaging them in reports every logo as black) and puts a near-black plate behind a light logo and an off-white one behind a dark logo. A logo that cannot be fetched or decoded falls back to the company's initials, so the row never shows an empty rectangle.
- **The interface language changes the instant you pick it.** Choosing a language left the screen written in two languages at once — the composables that had re-read the configuration in the new language, and everything else still holding the old one — and only a cold start made it uniform. The activity handles `locale|layoutDirection` itself instead of being recreated, so the change is now applied to the whole tree in one frame: the language map, the configuration and the layout direction are all provided by the app, every `tr(...)` re-reads them on the same frame, and an RTL language flips the layout in the same pass. Nothing is recreated, so scroll position, the open settings card and the navigation stack all survive the switch.
- **A picker opens in the middle of the screen in every language.** Choosing a poster treatment, a featured-banner style or a details-header style opened a scrollable dialog *where the card that opened it happened to be* rather than over the screen — which looked centred in English and landed at the very bottom of the page in Arabic, where a taller right-to-left card pushed it out of the fold (reported exactly that way). The panel is drawn in its own window now, so it is centred on the screen in every language, on every screen, over the taskbar and the navigation rail instead of behind them.
- **Importing a collections JSON finishes, and the button works however the text got there.** Two separate faults. The import itself walked every catalog the file named and asked the installed extensions about it — and an extension whose host has stopped answering answers nothing, so the button span forever and the sheet sat on its spinner; each extension's catalog lookup is now bounded, the whole import is bounded, and the spinner always clears with the real reason in the log. And the only thing wired to the parser was the Paste button, so pasting with the keyboard (long-press → Paste, or a paste from a password manager) filled the field but left no plan and no working Import button; the field is now parsed as it is typed or pasted, off the main thread and debounced, so every route in enables the button.
- **The hero banner on a television stands still.** The featured carousel advanced itself on a timer on a TV as well as a phone, which on a television — where nothing is being swiped, so nothing resets it — is a banner that never stops moving under the title you are reading. It no longer auto-advances on a television, and its card is capped to about half the screen's height, so the rows below it are actually on screen.
- **A television gets the whole interface, not the top of it.** Four faults in the same place. The posters were sized from a constant, and a 2:3 poster 168dp wide is 252dp tall — three or four of them filled a 540dp-tall landscape screen and the rows underneath were never reached, so the cell is now sized from the screen's own height (about three rows to a screen, still clamped so it reads from a sofa). The navigation rail honoured the phone's "hide this tab" preference, which on a TV leaves a screen that the remote can never open — Extensions and Settings were reachable only if you had never hidden them on a phone — so the rail now always draws every tab and scrolls itself to whatever the remote lands on. The app asked for no particular orientation, so a television that reports portrait-shaped windows showed a landscape interface in a portrait box; a TV is now pinned to landscape and always immersive. And the 5%-of-the-picture crop that televisions have applied since the CRT era was not accounted for, which is what put the left edge of the rail off the screen; the safe area's default is now 48dp — exactly 5% of a 1080p television's 960dp width — so nothing is drawn where the set cannot show it.
- **A personal catalog's folder tiles take the room they need and no more.** The folder tiles on the Personal Catalog screens were sized for a poster, so on a phone only two fitted across and each one wrapped its own glass box around a cover and two lines of text — the reported "it's showing 2 posters only on screen, it's taking too much space because it wraps into a glass box". A folder is a bookmark, not a poster: the covers are smaller, the panel's padding is tighter and a phone fits three across, while a television keeps the larger size it wants.
- **Subtitles from an installed subtitle addon reach the player.** An addon was only asked with the id the title carried — and a title from a site scraper carries the site's own id, which no subtitle addon has ever heard of, so an installed OpenSubtitles answered nothing and the films it certainly has subtitles for came up with none. The title is now resolved to the id the addons actually index (IMDb first, then `tmdb:`) before they are asked, the per-language answers in every language are kept rather than the first handful, and the subtitle menu is laid out again if it is open when they arrive, so the tracks appear in the player instead of only in a log.
- **IPTV channels never pick up TMDB metadata.** A channel now shows the live tile and card art it should; its artwork and ratings are no longer fetched from TMDB.
- **IPTV channel logos load however the playlist wrote them.** Many playlists write a channel's `tvg-logo` as a bare host (`example.com/logo.png`), a root-relative path (`/logos/1.png`) or a protocol-relative one (`//cdn/2.png`) rather than an absolute URL. Those were handed to the image loader unchanged and silently failed, so channels that plainly have a logo arrived as blank placeholders. Each address is now resolved against the playlist's own address (spaces percent-encoded), and a file's own `data:` logo is used as-is.
 An extension resolves its own dependencies out of Mihon's global `Injekt` container the moment it is constructed (`Injekt.get<Application>()` for its preferences — which is why a failure came out as `InvocationTargetException: null` with nothing behind it). The container is now checked and re-primed on every extension load, not only at startup, so a scope that was replaced after startup repairs itself; the singletons include the `ProtoBuf` instance that the keiyoushi-utils library reads in a top-level property initialiser; a failure to install them is written to the log rather than swallowed (it used to be a silent `runCatching`, and it is the difference between "the container was never primed" being a mystery and being a log line); the two kotlinx.serialization modules extensions link against by name but Hikari never used — `kotlinx-serialization-json-okio`, which is where `Json.decodeFromBufferedSource` lives, and `kotlinx-serialization-protobuf` — are now on the classpath; and a load failure now carries the unwrapped cause and its frames instead of only "none of its sources could be loaded". A latent crash was fixed underneath as well: `AnimeCatalogueSource` overrode `getHosterList`/`getVideoList` with bodies that called the same signature, i.e. infinite recursion, which is a `StackOverflowError` for any extension that implements that interface directly.

### Changed

- **The interface follows the language you actually chose** — including one chosen on Android's own per-app-language screen rather than inside Hikari, which used to leave the whole interface in English. A label that is still missing from the dictionary is translated and remembered instead of staying English.
- **Every tab of the navigation rail is reachable from a TV remote.** On a short screen the rail's tabs used to run off the bottom, so the last ones (Extensions, Settings) could not be focused at all; the rail now fits itself to the height and scrolls.
- **On a television the app is always fullscreen and always landscape**, and the settings that only make sense on a phone — the taskbar layout and its buttons, "turn off full screen app mode", the home-screen icon and the app icon variants — are not offered there at all, instead of being shown and doing nothing. The orientation matters on the boxes that report a portrait-shaped window: without asking for landscape the television drew a landscape television interface inside a portrait box, which is both letterboxed and cut off.
- **First run on a television is quieter out of the box**: no animated poster treatments, no poster blur, and a slightly larger interface, so a TV stick can keep the interface smooth while it decodes video.
- **The navigation rail scrolls to whatever the remote lands on.** Each tab is brought into view as it takes focus, so Down always reaches the last one on a screen too short to show them all — the rail can no longer hide Extensions or Settings below the bottom edge.
- **A held background search is invisible where it should be, and instant where it should not.** Opening the Services list, asking for another server, or hitting a playback error releases the held extension search immediately, so the one screen that is genuinely about finding servers never waits on the hold; leaving the player releases it too.

## 0.9.8

Everything added and fixed since the last version released here (0.6.6). Pick the APK for your phone: **hikari-arm64-v8a.apk** (64-bit, every phone sold today), **hikari-armeabi-v7a.apk** (older 32-bit phones) or **hikari.apk** (universal — works on any device).

### Added

- **IPTV playlists.** Add an M3U/M3U8 link (an Xtream panel's get.php?...&type=m3u_plus link works) or pick a playlist file from storage. Channels appear on Home (an "All channels" row plus the playlist's own groups, biggest first), in search, in the player's server list under an **IPTV** heading, and can be marked as exception extensions. The playlist is read before it is saved, so you are told how many channels it holds, and Refresh re-reads one on demand.
- **Stremio addons.** Add any addon manifest URL. Catalog addons get Home rows and a name search; stream-only addons browse TMDB, so their servers appear for titles opened from any extension.
- **Aniyomi, Nuvio and SkyStream engines**, alongside CloudStream plugins and Hikari extensions — all installed and managed from the Extensions screen.
- **Poster styling** (Settings → App Layout → Poster styling): dynamic blur halo, corner rounding, titles, score badges and glass trim, with effects — Glow, 3D tilt, Sheen, Aura ring, Spotlight and Gallery frame. Several effects can be on at once, and the aura ring has its own colour.
- **Loading screen options**: four styles (poster card, cinematic, minimal, spotlight), the same effect set (sheen, aura ring, gallery frame, accent glow), and a dark backdrop when a title has no artwork.
- **Taskbar & navigation styles**: Floating animation, Floating, Always compact, Classic — plus the option to turn off full-screen app mode.
- **In-app UI scale** in 1% steps, and a choice to ignore or follow the phone's font and display size settings.
- **Other layout choices**: featured banner styles, detail-page header styles, ratings visibility, Continue Watching, and the taskbar's own options.
- **Player options**: player skins, video-enhance presets, subtitle styling (font, size, colour, background), playback speed, 10-second seek steps, and a resume prompt.
- **Server search options** (Settings → Playback & Servers): "Search all installed extensions", play as soon as the first server is found, and **exception extensions** — whole engines (CloudStream, Hikari, Aniyomi, Nuvio, SkyStream, Stremio) or single extensions that are asked for every title.
- **Themes and language**: dark/light/system, eleven accent colours, a font of your own, app icons, and an interface translated into 20+ languages.
- **Downloads, Continue Watching, history, collections and folders** (with custom covers, including animated GIFs), a personal catalog, and **backup & restore** of your extensions, repos and settings.
- **In-app updater** that downloads the build for your phone's processor, and a **Stop** button for Install all / Update all.

### Fixed

- **Episodes appear immediately** instead of after several seconds, and a series is no longer shown as "No episode list available" while its list is still on the way.
- **Ratings and the Related/Similar rows load together with the episode list** instead of after it, and titles whose names carry episode or release tags ("Episode 172", "S01E12", "English Subtitles", "1080p") find their score again.
- **The resume prompt now arrives with the first frame**, not several seconds into the video.
- **A second press of Play is no longer needed**: a source that timed out (Nuvio, Hikari or CloudStream) is asked again while the film plays, and anything it finds joins the server list.
- **No more "searching…" that never ends**: a stalled extension, a dead repo and an unreachable addon are each reported with a retry, a repo behind a Cloudflare check is skipped on purpose and no longer counted, and a search whose progress has stopped is reported as finished.
- **Only the right title and the right episode**: a repo that matches one word of a title, or names a different episode number, is rejected, so another video can no longer play instead of the one you opened.
- **The extension a title was opened from plays it** (first in the server list), and a title you open inside one of your exception extensions plays from that extension alone.
- **Uninstall removes exactly the extension you tapped**, same-named plugins from other repos no longer overwrite or uninstall each other, and installed extensions no longer disappear from the list.
- **Repo funding and donation cards are blocked**, so a page or a loading episode is never covered by a "Help keep this alive" overlay or a donation popup.
- **No extension can open a Cloudflare/Turnstile verification page by itself** — such a popup is closed the moment it appears. The app's own verify button remains the only way a challenge page opens.
- **Playback is silent the moment you leave the player** (no sound for a second or two after Back) — while PiP and background audio still work.
- **Other apps' audio pauses** when playback starts, instead of a trailer, a browser tab or music playing underneath the video.
- **Closing the app really closes it**: no notification saying it is still searching, and no splash screen that will not go away.
- **The title, description and episode names stay in the language you chose.**
- **The coloured glow around posters shows on every Android version**, and the 3D tilt looks 3D.
- **Settings always save**, and the settings screen can no longer hang the app until it is restarted.
- **No crash on launch**, no more "0 episodes" after backing out of the player, and no crash opening a personal catalog that lists the same entry twice.
- **A server name is no longer cut in half in the player.**

### Changed

- **Every setting now lives in the right folder**: Playback & Servers (controls, video enhancement, loading screen, server search), App Layout (UI scale, posters, ratings, taskbar, full screen), Appearance & Theme, Sources & Extensions (installed extensions, userscripts, extensions' verification pages), Privacy & Browsing, and Player.
- **A new look out of the box**: coloured halo and gallery frame on posters, the Showcase featured banner, the poster-beside-the-art detail header, the Neon player skin and the poster-card loading screen.
- **Smaller downloads**: emulator-only builds, the bundled yt-dlp engine removed, unused code and resources stripped from the release build, native libraries stored compressed — and one APK per processor type plus a universal one, with the updater fetching the right one for your phone.
- **Smoother scrolling, tapping and settings**: per-poster blur layers removed, poster styling read once per row, Home and Search lists built once per change, and settings reads no longer repeated per poster.
- **Faster, more predictable searches**: extensions are asked in waves instead of all at once, servers start resolving while the search is still running, a frozen extension is retried after two minutes instead of being dropped for the session, and the Sources line counts every engine, Nuvio included.
- **Repos and extensions**: duplicate repos are merged instead of stacked, extensions published as GitHub release files get their own identity, and a mistyped short name is answered with the name you meant.

## 0.9.7

Episodes show up right away, the score strip and the Related row load beside them instead of after them, and the funding cards some repos bolt onto their pages are gone.

### Fixed

- **"0 episodes" for the first seconds, then the whole list.** A detail page paints its episodes in stages: the extension (or a borrowed list) answers first, then the app adds translations and the episodes' own names — and every one of those stages used to be awaited before ANY of them reached the screen, while the page meanwhile read **Episodes (0) — No episode list available.** The reported "it says no episode / 0 episode and after 6-7 seconds it shows all episodes" was exactly that. Now every usable list is handed to the page the moment it exists: a list is painted as soon as it arrives, the count keeps a small spinner beside it until the finishing touches land, and the page only ever shows a "couldn't load" line when the lookup has genuinely finished and come back empty (with its Try again). The wait for a *borrowed* list (another extension's, when the origin has none) also dropped from a 4-second settle to about one second, and a superseded lookup can no longer paint its empty answer over a newer one's page.
- **No IMDb/TMDB score and no Related/Similar row on some titles.** Two things: the ratings, the details block and the Related/Similar shelves only STARTED once the episode list had finished — so on a series that took several seconds to list its episodes the whole score strip arrived late, and on a page opened from an episode's own search row ("… Episode 172 English Subtitles") they often did not arrive at all. The shelves now run in parallel with the episode lookup, and the title match itself is much harder to miss: episode/release decorations ("Episode 172", "S01E12", "English Subtitles", "Hindi Dubbed", "1080p", "WEB-DL", "x265"…) are stripped when building search queries, and a title that every TMDB search misses is looked up through IMDb's suggestion endpoint before the app gives up on it.
- **The resume prompt arrived several seconds into the video.** "Continue from where you left off?" fires when the first frame is on screen — but it waited for a watch-history read from disk first, so a replay from a cached server (the one that starts playing instantly) got its prompt five or six seconds in. It now uses the saved position the detail page already handed it, with the history pre-read in the background while the player is being built: the prompt is on screen with the first frame.
- **"I allowed net77.cc and the verification WebView still says Blocked redirect to net77.cc."** The Cloudflare verification view only accepted the site it started on plus Cloudflare's own infra, so a site that answers on an allowed mirror host was refused even though the user had put that host on the allow list. The allowed-redirect list is now part of that decision — and it is honoured IMMEDIATELY: the list is mirrored in memory, so a link added in Settings applies to the very next redirect instead of waiting for the settings file to be read (which a page's first redirect could easily beat).
- **Repo funding cards ("Help keep … alive", "Goal missed = delayed fixes").** The promo cleaner knew the ko-fi/Patreon shapes but not the repo-side funding card — the big overlay with a goal meter, "No ads, no subscription", "N supporters this month" and a "Maybe Later" button. Those phrases are recognised now, a funding overlay that covers the page is hidden outright (a merely hidden overlay still eats every tap), and its own dismiss control is clicked so the page underneath is usable. Extensions' own "watch an ad to support" links were already blocked and still are.

### Changed

- **In-app UI scale moved to App Layout** (it decides how much room the interface takes, which is layout, not decoration) and it now moves in **1% steps** instead of 10%: 101%, 102%, 103%… are all reachable, and the app can be sized to fit rather than in ten-percent jumps.
- **Taskbar & navigation gained "Always compact"**: the animated bar's small icon-only pill, kept small permanently, for anyone who wants the taskbar out of the way and the whole screen taller. The four looks are now Floating animation / Floating / Always compact / Classic.
- **The settings folders were tidied.** "In-app UI scale" moved to App Layout; "Continue Watching" moved out of Sources & Extensions into App Layout (it is a Home row, not a source); the extensions' own-verification-page switch moved from Privacy & Browsing to Sources & Extensions; the playback-start card is only in Playback & Servers (it used to be duplicated in the Player folder); and Sources & Extensions no longer advertises the yt-dlp fallback that was removed in 0.9.1.

## 0.9.6

Extensions that are published as GitHub release files are told apart from each other — and a long Install all can be stopped.

### Fixed

- **Installing one extension made the whole repo read as installed — and uninstalling one made the whole repo read as uninstalled.** A repo that is built by CI publishes its files as GitHub **release assets** (`github.com/o/r/releases/download/<tag>/<file>`). The URL-identity code knew raw file links and `blob`/`raw`/`resolve` links, but a release link fell through to the "this is just the repo's page" rule and collapsed onto the bare repository — so all 144 extensions of such a repo shared ONE identity. One install lit up every row ("Uninstall" on 143 extensions you do not have, and no Install button anywhere), "Install all" thought there was nothing to do, uninstalling a row could take a different extension with it, and the updates list was wrong for the same reason. A release file is now identified as `owner/repo/filename`, exactly like a raw file (`continuous`, a version tag and GitHub's `latest` spelling are all the same file), so each extension stands on its own: one install shows one Uninstall, the rest keep their Install button, Install all installs what is missing, and Uninstall removes exactly the row you tapped.

### Added

- **Stop for Install all / Update all.** A repo with a hundred-plus extensions is a long job to start by accident, and there was no way out of it. The progress line now carries a **Stop** button while a bulk install or update runs: it finishes the extension it is on right now (so nothing is left half-downloaded or half-registered) and then ends the run, reporting where it got to ("Stopped — installed 57 of 144 extensions").
- **A mistyped short name now answers with the name you meant.** Typing `hiakri` used to be answered with the generic "must start with http(s):// — or a short name (megarepo, hikari, nuvio, …)" hint, which reads like a rejection of a word you nearly got right. One swapped pair (or two letters out of place) is recognised as a typo and answered directly: `Unknown short name "hiakri" — did you mean "hikari"?` — with the check narrowed to the kind of repo the dialog can actually add.

## 0.9.5

Your repo list stops growing duplicates, Uninstall tells the truth, a whole engine can be marked and then trimmed, and an IPTV playlist is a first-class extension.

### Added

- **IPTV playlists.** Extensions → IPTV playlists → Add IPTV playlist. Paste an M3U/M3U8 link — an Xtream panel's `get.php?username=…&password=…&type=m3u_plus` link works, and so does a single m3u8 stream — or pick a playlist file from storage, which is copied into the app's own storage so it keeps working after a restart. The playlist is READ before it is saved, so you are told how many channels it holds (or that the link is dead) instead of being left with an empty extension. A channel's stream is simply its own URL, so there is nothing to extract and no server to scrape: channels appear on **Home** (one "All channels" row plus the playlist's own groups, biggest first), in **search** by channel name or group, in the **player's server list** under an **IPTV** heading, and can be marked as exception extensions in Settings like any other engine. The folder shows how many channels each playlist holds, and Refresh re-reads one on demand.

### Fixed

- **Adding the same repo twice made a second folder.** A repo's identity was its exact URL, and the same repository is published on more than one branch (`…/builds/repo.json` and `…/main/repo.json`) — so pasting a link to a repo you already had grew a second folder with the same name and the same extensions in it. Repo identity now ignores which branch a manifest was read from, so one repository is ONE entry however it is spelled: re-adding it merges into the entry already there and says "Repo already added" instead of "Added", and the app collapses duplicates an older build had already stored the next time it starts.
- **Uninstall said "Uninstalled" while the row still showed Uninstall.** The providers to remove were matched against the URL the repo lists TODAY, so a copy installed from the other branch (or the jsDelivr mirror) spelling did not match and stayed installed — gear and all — while the message claimed it was gone. An uninstall now removes every copy of that file whatever either side's spelling is, and it reports honestly: "Uninstalled X (3 providers)", or "Nothing to uninstall" when there genuinely was nothing left to remove.
- **Hikari `.hiki` extensions had the same file-name collision CloudStream plugins had.** Almost every `.hiki` file is called `extension.hiki`, so two repos' extensions shared one file on disk — the second install overwrote the first, and uninstalling either deleted the file the other was still loaded from. The file now carries a short stamp of its source URL (so two repos' same-named extensions are two extensions), and a re-install cleans up the older name-only copy instead of leaving it as a second row.
- **A whole engine could be marked as an exception, but not trimmed.** There was no way to say "all of CloudStream except these two". Tapping an engine chip now narrows the list to that engine's extensions *and* marks it, and any single row can then be switched off — the row reads "left out of its engine" — without unmarking the engine itself and without losing the extensions installed later.

## 0.9.4

Extensions stop lying about each other, Stremio addons can be browsed and found, and no search can sit on "searching…" for ever.

### Fixed

- **Uninstalling one extension took the whole repo's worth with it.** Two things were wrong at once, and they compounded.
  The first: a repo that serves its files as `github.com/o/r/blob/…` links (the web spelling of a raw file) had EVERY plugin in it collapsed to the same identity — the repo root — so "uninstall this one" matched the whole list. A `blob`/`raw`/`resolve` link is now recognised as the file it points at, which is the identity it always should have had.
  The second: the identity of an installed file on disk was its NAME. 27 of the extensions in the repos users actually add share a name with a different plugin in another repo (`AniKoto`, `DailymotionProvider`, `InternetArchiveProvider`, `Aniworld`, `Luna712`'s copies of other repos' plugins…), so installing the second one overwrote the first one's file, and uninstalling either deleted the file the other was still running from. The file a plugin is saved as now carries a short stamp of its source URL, so two repos' same-named plugins are two plugins — and installing one no longer silently replaces the other. An install also cleans up the older name-only copy of the same source, so nobody ends up with two rows for one extension.
  On top of both, an uninstall now resolves the exact providers it is about to remove BEFORE writing, and only falls back to the looser "the file moved" match when the exact spelling matches nothing at all — so a single Uninstall can never take out a sibling again.
- **Add repo / back / Cancel did nothing while something was installing.** Every "Add …" dialog in Extensions was disabled for as long as ANY background task ran, and a Mega-repo import installs hundreds of extensions one after another — so the moment the user tried to add another repo (or back out of the dialog, or change their mind) the whole dialog was dead: "the add button, back button all clicking doing nothing". The dialogs no longer wait for anything on the background work: Add is enabled as soon as the field has something in it, Cancel and the back gesture always work, and the add itself runs in the background and reports on the screen like every other install.
- **Re-adding a repo you already have made its extensions look uninstalled.** Adding a repo that is already in the list merges the two (correctly), but the plugin list is keyed by the repo's URL, and a merge can upgrade the stored spelling (a jsDelivr URL to the origin one) — which left the list the user was looking at attached to a key nothing pointed at, so the repo re-fetched and every extension it holds looked new again. The list and its load state now follow the merge, and "is this installed?" is answered by comparing SOURCE URLs (however each side spells them) rather than by a literal string test — so an extension that is installed shows Uninstall, not Install.
- **A Stremio addon with no catalog of its own was invisible.** An addon like HdHub is a stream addon: it answers `/stream` and declares no catalogs. Home skipped those on purpose, and that one line had three effects the user reported as three separate bugs: no **Stremio** chip in the provider picker, "hdHub" finding nothing when searched, and nowhere for its servers to come from.
  Such an addon now browses **TMDB**, exactly the way a Nuvio provider already does here — real Home rows (niche-matched to the addon's name: HdHub gets Hindi rows, an anime addon gets anime), a working name search, and its own IMDb id resolved from the TMDB id whenever a title is opened, because a stream addon only answers about the ids it declares (`tt…`). Its servers therefore show up for titles opened from ANY extension, and no Cinemeta-style "catalog addon" has to be installed just to have something to look at.
- **The gear on a Stremio addon said "Provider file missing".** That screen is the NUVIO provider-settings screen, which reads the provider's FILE — an addon is a remote manifest URL, so it read nothing and reported a healthy addon as broken. The gear on an addon now shows what the addon is: its manifest URL and what it provides (catalogs/streams/metadata), with the note that addons have no settings of their own.
- **"Searching…" that never ended.** Two causes, both in the Extensions screen. Repos were fetched one after another, so with a Mega-imported list the search knew about only the repos already read and sat on "Searching…" for minutes while a hundred hosts were tried in turn; they now load a few at a time. And a repo whose fetch FAILED was still counted as "still loading" (it had no plugin list, which is also what a repo in flight looks like), so one dead repo kept the search saying "Searching…" for as long as the screen was open. A failed repo is now reported as failed — its own row with Retry — and no longer pretends to be on its way. Each repo's manifest also has a hard ceiling now, so a host that never answers becomes an error with a Retry instead of a permanent spinner.
  The same was true of a dead Stremio addon, and worse: its manifest was re-fetched on EVERY search, meta, episode and stream lookup, and each attempt is two URL spellings tried twice against a 20-second connect / 30-second read timeout — minutes of waiting, again and again, for one host that is simply down. A failed manifest is now remembered for a short while and the fetch itself is bounded, so an unreachable addon is reported and then stepped over instead of stalling the search it is part of.
- **A long server name was cut in half in the player.** The server rows force every label onto one line to keep the capsules the same height, which ellipsised exactly the names worth reading ("… (Repo) · Plugi…"). The server list is now the one place that fits TWO lines, and the row's box grows with the name instead of chopping it.
- **Extension exceptions could only be picked one by one.** The exception list is 150+ rows, and ticking them one at a time went stale the moment a new repo was installed — a repo installed NEXT WEEK was not in the ticked list, so the choice quietly stopped meaning "all of CloudStream". The picker now has an engine chip row: **All engines**, or one chip per installed engine (CloudStream, Hikari, Aniyomi, Nuvio, SkyStream, Stremio). Marking an engine covers every extension of that engine, including ones installed later — and any number of engines can be marked at once, so CloudStream *and* Hikari can both be exceptions. Rows covered by a chip stay ticked and are marked as such, so what is on screen is exactly what a search will ask.

## 0.9.3

Fixes the launch crash in 0.9.2 — and, in the rules themselves, the reason it happened.

### Fixed

- **0.9.2 closed itself as soon as it opened.** It was not a slow start: the app restored your store and pre-warmed the CloudStream classes fine, then the background worker that reads your saved provider list died with `NoSuchMethodError: No direct method <init>(Ljava/lang/String;Lorg/json/JSONParserConfiguration;)V in class Lorg/json/JSONTokener` and took the process with it. A method the code asks for that the class it is asking does not have.
  The reason is worth knowing, because it is the one trap that shrinking a *host* app walks into. Android ships its own **org.json**, inside the runtime itself, and the boot class loader is asked first — so a copy of org.json inside an APK is *always* shadowed: the phone runs the platform's version, no matter which version we bundle. **NiceHttp** — the HTTP client CloudStream plugins are built against — declares a much newer `org.json:json`, so 0.9.1 and 0.9.2 were shipping a second, modern org.json that could never actually run. On its own that is harmless: our code calls `JSONObject(text)`, which is platform-compatible, and the call lands on the platform's implementation. But with shrinking on, R8 took the copy it could see as the definition of `JSONObject` and **inlined that copy's constructor body into our own classes** — producing a literal `new JSONTokener(String, JSONParserConfiguration)` call, a constructor that only the bundled copy has. At runtime the name still resolved to the platform's `JSONTokener`, so the app crashed the first time it parsed anything. Without shrinking the call stayed a call and reached the platform's implementation — which is why 0.9.0 never showed this.
- **The extra `org.json` is now excluded from the runtime classpath**, so compile time, R8's view and the phone all mean the same org.json — the platform's. Nothing else in the app changes: our org.json calls were already written against the platform API, and NiceHttp only ever uses `JSONObject`/`JSONArray` (checked against its jar — it references no modern-only API), so nothing loses a class it actually used. `app/proguard-rules.pro` carries the matching `-dontwarn`, and a copy can only come back with a dependency that declares `org.json:json` — which is the thing to check when adding one, because a bundled org.json can never be the copy the device runs.

Sizes are unchanged from 0.9.2 (the 31 MB / 23 MB APKs), and every keep rule from 0.9.1 and 0.9.2 is still in place.

## 0.9.2

A maintenance release with nothing visible in it. Two keep rules added in 0.9.1 named packages that do not exist in this app, so one of them was protecting nothing.

### Fixed

- **The keep rule for the extension API's local HTTP server was pointed at the wrong package.** An Aniyomi extension that serves its pages over a socket instead of returning a direct link starts that server itself — `createHttpServer()`, from `eu.kanade.tachiyomi.animesource.model.HttpServer` — and the server it starts is NanoHTTPD. The 0.9.1 rule named it `org.nanohttpd.**`, but the artifact that actually ships here (`org.nanohttpd:nanohttpd:2.3.1`) puts its classes in `fi.iki.elonen`, and the extension API vendored into Hikari is `fi.iki.elonen.NanoHTTPD`. It survived the 0.9.1 shrink only because it happened to be reachable from the extension API we keep whole — correct by luck, not by rule. A later change to that API could have dropped it silently, and every extension that reads its pages over a socket would then have failed with `NoClassDefFoundError` the first time it served one. Both package names are now kept, and both are in the `-dontwarn` list.
- **A keep rule for a package that is not in the app.** The same list carried `com.github.blatzar.**`, which does not exist here at all; the CloudStream HTTP client extensions use is `com.lagradost.nicehttp.**`, already kept whole by the CloudStream rule. Removed.

Nothing about the app's behaviour on your phone changes in 0.9.2, and the APK sizes are the same as 0.9.1.

## 0.9.1

A smaller download. The APK no longer carries a bundled yt-dlp/Python engine, and the release build now drops the code and resources nothing in the app can reach.

### Changed

- **A smaller APK.** Three things came off the download:
  - the bundled **yt-dlp engine** is gone (see below) — a full CPython 3.13 interpreter, roughly 15 MB of the arm64 build, plus the few seconds it spent starting up at every app launch;
  - the release build now **shrinks**: code nothing can reach and resources nothing refers to are dropped. **Nothing is renamed** — every class a plugin or an extension links against by name is kept exactly as it was, which is the whole point of `app/proguard-rules.pro` (read the comment at the top of that file before changing it);
  - the **native libraries are stored compressed** in the APK (TorrServer's Go engine, Conscrypt), which takes a few more MB off every download in exchange for a little unpacking at install time.

### Removed

- **Universal extraction (yt-dlp)**, and the setting that switched it on. It only ever ran *after* the plugin's own `loadLinks`, the CloudStream jar extractor registry, MovieBlast, FallbackResolver and the Nuvio/SkyStream JS runtimes had **all** returned nothing — exactly the case where a plain-Python yt-dlp was least likely to find anything either, and the free build of that library has no TLS impersonation, so the sites that needed it failed regardless. It was also arm64-only (on a 32-bit phone `YtDlp.init()` threw, so those users never had it at all) and it cost up to 45 seconds of waiting on every title it was tried for. Every other engine is untouched.
  If a title now reports no playable source, that is a plugin or extractor that came up empty — the same answer the fallback usually produced anyway — and the player's panel still names the reason.

## 0.9.0

The coloured glow is back around every poster, the loading screen's ring and frame wrap the whole screen with a colour of their own, and closing the app now really stops it — no more notification saying it is still running, and no more splash screen that will not go away.

### Added

- **A colour for the aura ring (Settings → App Layout).** The ring around a poster card, and the ring around the loading screen, each have their own colour now: **Accent** (the default — it follows your app colour, exactly as before) or any of the app's eleven palette colours. The row appears under the effect picker as soon as **Aura ring** is ticked, on both the Poster styling card and the Loading screen card, so a red ring on a gold app is one tap.

### Fixed

- **The coloured glow around posters not showing.** The soft halo behind each poster was left as just a slightly enlarged copy of the artwork with no blur on top, so all that showed was a ~4dp rim that read as nothing at all — the effect looked broken no matter what the Dynamic blur slider said. The blur is back (unclipped, so it spills out around the card instead of being cut back to it), and the halo is now drawn as two layers — a tight band of the artwork's colours at the card's edge and a wide faint one beyond it — so the light falls off gradually. The tiny soft decode stays underneath, so the halo still shows on phones older than Android 12, where the blur filter does not exist.
- **"It is still running in the background after I close the app."** Hikari keeps long searches alive while you are in another app (that is deliberate — Android freezes a backgrounded app and every search used to stop dead), but it never let go when the app was actually *closed*: the notification stayed on screen saying it was still searching, hundreds of requests kept running for minutes, and the next launch had to fight all of that for the phone — which is the "I closed it, opened it again, and it just sits on the Hikari logo" report. Closing the app (Back out of it, or swiping it off the recents list) now cancels every registered background task outright and stops the service, so the notification goes and nothing of ours is left running. Stepping away to another app still keeps work going, as before.
- **The trailer still playing under the video.** The player built playback without asking for audio focus, so whatever else the phone was playing carried on underneath: the trailer you opened from a title page (which hands off to the YouTube app), a browser tab, a music player. Playback now requests audio focus, which is what pauses them — and the in-app WebView also stops its own media when you leave it (and resumes it when you come back), so a video page opened in Hikari cannot keep playing in the background either.
- **Two different loading screens in a row.** The detail page puts the loading card up on the tap and the player then shows its own copy of it; the two were meant to be the same picture, but the aura ring was drawn in the app's accent on one and the player's own accent on the other, so the hand-off looked like a second, different loading screen appearing. The detail page now resolves the ring's colour and hands the exact colour over to the player.

### Changed

- **The loading screen's aura ring and gallery frame are out at the phone's edge.** Both used to sit in the middle of the screen around the title card — a small ring around some text, nowhere near the border. The aura ring is now a glowing frame around the whole loading screen (a wide soft band of light under a crisp hairline, in your chosen colour, breathing), and the gallery frame is a hairline mount just inside it, so with both ticked you get one nested pair of rings hugging the screen. The player's own cover draws the identical picture.

## 0.8.0

The aura ring is a ring around the card instead of a circle over it, every visual effect can be combined with any other, the server search has exception extensions, the 3D tilt actually looks 3D, and the app scrolls and taps more smoothly.

### Added

- **More than one effect at a time.** Poster effects and loading-screen effects are now multi-select: tick **Sheen** and **Aura ring** together, or **Gallery frame** and **3D tilt**, and every one of them is drawn on the same card. The list you pick from works like before — tap a row to add or remove it — and **None** clears the whole selection. Existing choices were kept: whatever you had picked is still what is on.
- **Exception extensions (Settings → Playback & Servers → Server search).** A middle ground between "search everything" and "only the extension I opened". Pick one or more extensions and they are asked for servers for *every* title you play anywhere else — even with "Search all installed extensions" switched off — and their servers appear in the same list as the rest. The one rule that goes with it: a title you open **inside** one of those extensions plays from that extension alone, and is never mixed with anything else. The picker lists every installed extension with a search box (match by repo name or by engine) and scrolls, so it stays usable with a couple of hundred installed. The card spells both rules out in two lines.

### Fixed

- **The loading screen's aura ring being a circle.** It was a fixed 300dp *oval* drawn over a rectangular card, so it crossed the card at four points and read as a stray outline. It is now a rounded rectangle that wraps whatever card the chosen style put up — the same ring a poster card wears — so with the poster card it rings the poster, and with Minimal it rings the title. The gallery frame also sits further out (22dp instead of 14dp), so the line reads as a frame *around* the cover instead of an edge *on* it.
- **The 3D tilt effect not looking 3D.** The tilt set the camera distance to `14 × screen density`, which on a normal phone put the camera roughly five times *further* from the card than Android's default — almost no perspective at all, which is exactly why it looked like a flat, slightly slanted picture. The camera is now close to the card and the angles are larger, and the leaning edge catches a highlight while the far edge falls into shade, so the two sides visibly sit at different depths.

### Changed

- **A new default loading screen.** A fresh install now shows the title's **poster card** with a **sheen** sweeping across it while a server is found (it used to be the plain Cinematic backdrop with no treatment). Both are ordinary settings, so anything you pick yourself still wins.
- **Smoother scrolling, tapping and settings.** Several pieces of the drawing path were doing repeated work per poster per frame:
  - the soft halo behind each poster was also running Android's blur filter, which builds a separate offscreen render layer for **every** poster on screen — the single most expensive thing a scrolling grid did. It is gone; the halo is the same tiny, already-soft decode, so it looks the same and costs a fraction.
  - every poster cell read its own copy of the poster styling out of settings — a Home feed with three rows of twelve visible posters held about thirty-six live settings subscriptions and re-mapped all of them on every settings write. Styling is now read once per row (or per grid) and handed down.
  - the Home feed rebuilt its row list — and a fresh key string for every row — on every recomposition, i.e. on every scroll step. It is built once per change now, and the shelves share a content type so Compose can reuse them as they scroll off and on.
  - the same for the Search results and a "Show all" catalog grid, which both rebuilt their deduplicated item list per cell.

## 0.7.0

Playback starts the moment there is something to play, a title can now be searched through the extension you opened it from and no other, the loading screen has real effect options, and the poster halo shows on every phone.

### Added

- **A "Playback & Servers" settings folder.** It holds the new Server search option and the playback-start choice, so "what plays, and when" is in one place.
- **Server search: "Search all installed extensions".** On by default (how Hikari has always worked): a title is searched across every extension you have installed, and the server list gathers what all of them found. Turn it off and a title is only ever searched through the extension you opened it from — the way CloudStream works — with no other extension contacted, no background search, and no episode list borrowed from another site.
- **Loading screen effects.** The Loading screen setting now has an Effect of its own, with the same kind of signature treatments the poster styling has: **Sheen** (a band of light sweeping across the card), **Aura ring** (a breathing accent ring behind the name), **Gallery frame** (a hairline mount around the card) and **Accent glow** (a pool of light swelling behind the title). They work with any of the four styles, so "Minimal + gallery frame" or "Spotlight + sheen" is one setting each.
- **A retry when an episode list will not load.** If an extension cannot answer with the episode list, the page now says so and offers **Try again**, instead of claiming the series has no episodes.

### Fixed

- **The app becoming "buggy" until it was closed and reopened.** Adding or removing a website in the extensions screen could hang the app's settings storage permanently: the write waited for a read of its own data that could never be answered, and because that write never finished, every later setting — and the episode lookup on a series page — queued behind it forever. That is the reported "I click a series and it shows no episode, and every setting I pick stays on the old one, and it is only fixed by closing the app". The read now happens before the write.
- **Settings that would not stay put.** A settings save that failed was invisible: the choice silently reverted and nothing anywhere said why. Saves are now retried, and one that keeps failing is written to the log with the reason and shown as a message, so a device with no free space says so instead of looking like the app ignores its settings.
- **A slow app open.** Two places read the settings synchronously while the app was starting, so a settings file that was slow to answer held the launch screen with no way forward. Both now have a limit, the app comes up either way, and the log records how long the start took and which step was slow.
- **The poster blur/halo not showing for some people.** The soft coloured halo behind a poster was drawn with Android's blur, which only exists from Android 12 — so identical settings gave one phone a glowing halo and the next one flat artwork. The halo is now drawn from the artwork itself, which works on every Android version, and the Dynamic blur slider is a real gradient of softness on all of them.
- **A series that showed no episodes.** An empty episode list was treated as final for the page: one cold extension (the first call to an Aniyomi extension loads its whole APK; a plugin has to start its runtime) or one dropped request left "Episodes (0) — No episode list available" over a show that has plenty, and only reopening the app cleared it. The lookup is now retried, and if it still fails the page says it could not load the list and offers a retry — it no longer presents a failure as "this show has no episodes". A cancelled lookup can also no longer be mistaken for an empty one.
- **Waiting on the loading screen while servers were already in hand.** With "play as soon as the first server is found" selected, playback still waited for the extension the title was opened from — up to 45 seconds — even with dozens of servers ready from other extensions, which is the reported "it found 70 servers and was still searching instead of playing". The extension you opened the title from now gets a three-second head start: if it answers, its server plays as before, and if it does not, playback starts on the best server available while the rest of the search keeps running and its finds keep appearing in the server list.

### Changed

- **A new look out of the box.** A fresh install now starts on the styling people ask for: the coloured halo and gallery frame on posters (blur 15, corners 28, titles and score badges on, glass trim on), the side-by-side **Showcase** featured banner, the **Art + poster** detail header, and the **Neon** player skin. Every one of them is still a normal setting — a choice you make yourself always wins.

## 0.6.9

Nothing a search starts is dropped any more — for nuvio, and for every other extension — and the Sources line now tells the truth about nuvio while its engines are still starting.

### Fixed

- **The first play showing every server except nuvio.** Nuvio sources are asked differently from the rest (they resolve from a TMDB id, with no title search), and they were the one kind with no second chance: on a cold start each nuvio source has to boot its own JavaScript engine, which makes them the slowest thing in the whole lookup — so when the search's time ran out, whatever had not answered was cancelled and forgotten. Their servers then showed up on the *next* press of Play, against engines that were warm by that point. That is the "first time no nuvio server showed, second and third time it showed" half of the report, and it is what this fixes: a nuvio source that timed out, failed, or was cut off mid-request is now asked again in the background while the film plays, and whatever it finds is added to the list you are watching. Every other extension already worked this way, so this now holds for all of them — hikari, cloudstream, skystream, aniyomi, stremio and nuvio alike.
- **"Search done" while nuvio was still starting.** The Sources line counted only the other extensions — nuvio sources appeared in none of its numbers — so it could say the search had finished while the nuvio engines were still booting and the nuvio tab was still missing. The line now counts every extension that was asked, nuvio included ("Nuvio 13 of 13"), and keeps reading "still searching" until they have answered.
- **Repos skipped without a reason being given.** Two searches of the same title, minutes apart, asked 238 extensions and then 140: the ~100 that went missing were Hikari repos sitting behind a Cloudflare check, which the app drops on purpose for ten minutes (re-asking a wall only wastes the phone's time). The drop was meant to be invisible in the server list — but it was invisible in the log too, so the only way to find out was to guess. The count and the reasons are now printed together on every search, from that search's own records, so the numbers always add up and `cf-skip=104` says exactly what happened.
- **Unfinished work that was waiting for a free slot being lost.** When three background searches were already running, a fourth hand-off was refused — and the repos it was carrying were simply dropped: never asked, never shown, recoverable only by leaving the player and pressing Play again. Unfinished work is now recorded per title and picked up the moment a background slot frees; and pressing Play yourself always retries it.
- **Installed extensions disappearing.** Installing, updating or removing an extension rewrites the whole installed list, and doing that from a copy made a moment earlier meant two of them at once could overwrite one another — so an extension could vanish from the app (which is also why one search could ask 140 extensions and the next 238). Every write to that list is now a locked read-modify-write, and every change to it is written to the log with its size, enabled count and per-engine split.

### Changed

- **One retry mechanism instead of two.** The title's own extension used to have a private "ask me again" path of its own; it is now re-asked by the same mechanism as everything else, which means it cannot be asked twice at once and no provider is left out because of the family it belongs to.
- A nuvio source that answers "I have nothing for this episode" is not re-asked (asking again only spends its time); only a source that never really answered is.

## 0.6.8

The search no longer stops working when an extension freezes, servers start resolving while the search is still going, and everything you read — title, description, episode names — stays in the language you chose.

### Fixed

- **A search that stopped and never recovered.** An extension's request is a plain blocking call, so when one froze while starting up (a cold plugin, an Aniyomi APK, a locked-up runtime) nothing could interrupt it: its slot was given back but the call stayed parked, no server ever arrived, and the search sat on the same count — "30 still searching" — for as long as you looked at it. Backing out of the player and pressing Play again was the only way out, because that asks the same extensions again with fresh calls. The app now watches for that: if nothing at all answers for 25 seconds it ends the stuck attempt, asks every extension it got no answer from again in the background, and the search carries on by itself. Frozen extensions are also no longer blacklisted for the whole session — they are left out for two minutes and then asked again, which is what makes the automatic retry work.
- **"Still searching" that never went to "done".** The background search that keeps asking the extensions a pass did not reach was allowed to run 10 minutes per round for up to 6 rounds, so the Sources panel could honestly read "still searching" for the better part of an hour while the film played. It is now two rounds of two minutes, an extension that takes more than 45 seconds no longer holds a round open, and a search whose progress has not moved for a minute is reported as finished instead of frozen.
- **Description and episode names turning English with a language chosen.** With a TMDB language selected, the page's title was translated but its description came from the extension (English), and the player's episode line showed the extension's English episode name — so the page read French and the player read "Episode 2 · First Dance". TMDB localizes the title, the description and the episode names in the same response, all three are now shown in your chosen language, and an extension's own text only fills a gap instead of replacing it.
- **A page renaming itself after it opened.** An extension's own metadata was allowed to overwrite the page's title, so a page opened in your chosen language could flip back to the site's English name once the extension answered. The page now keeps the name you opened it with; every extension search still goes out with the original name in the background, which is what finds it on sites that only index it in English.

### Changed

- **Servers start resolving while the search is still running.** A repo that matched the show used to wait for every other extension to finish searching before its links were resolved — up to 45 seconds of doing nothing on a cold start, which is why the first play of a title sat on the finding-server card for a minute and the second play (with the extensions already warm) was instant. Extraction now starts the moment a repo matches, in the order you would want it: the extension you opened the title in first, then extensions that have already given you servers, then the rest.
- **Extensions are asked in waves instead of all at once.** Every installed extension still gets asked, but no longer at the same instant: a phone cannot cold-start a hundred plugin runtimes at once without them locking up, which is what caused the freeze above. The first wave is a few repos, and the waves widen only while answers are still coming back.
- **A server from a repository is only accepted for the right show and the right episode.** A repo entry that names only a word from the title you asked for ("Renegade" for *Renegade Immortal*) is rejected now — that was how an unrelated video's server could appear — while a repo that names the show more fully, or numbers its rows its own way ("Ep 148", "148", "第148集"), still matches. An entry whose own name states a different episode number than the one you are playing is rejected too, so a repo cannot hand you another episode's video.


Smaller downloads: Hikari is now built for each phone processor type, and the emulator-only builds are gone.

### Changed

- **Emulator-only processor builds removed.** Every release used to carry native libraries for x86 and x86_64 as well. Those processors only exist in Android emulators, so no phone could ever load them — they were simply a large part of every download. Only arm builds are produced now, which makes every APK smaller.
- **Three APKs per release instead of one.** Each release publishes `hikari-arm64-v8a.apk` (64-bit phones, which is every phone sold today), `hikari-armeabi-v7a.apk` (older 32-bit phones) and `hikari.apk` (universal — contains both, and works on any device). Download the file for your phone to use less data; if you are not sure which you have, the universal one is always correct.
- **The in-app update downloads the right build by itself.** The update dialog now fetches the APK for your phone's processor instead of the universal one, so updating uses less data as well.

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
- The crash some people hit while browsing ("Key "…" was already used"). Compose refuses to draw two items in the same list that share a key — it throws, and the screen goes down with it. Every key in Hikari is built out of data an extension handed us, and extensions repeat themselves: the same title twice in a scraped page, two catalogs sharing an id, the same repo installed twice, the same source added to a folder twice. Those repeats are now handled in Home, a catalog's "Show all" grid, search results, a detail page's shelf, the Library grid, a folder's catalogs and the extension and picker lists — a repeat in the data costs the item its own slot at worst, and can no longer take a screen (or the app) down with it.
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
