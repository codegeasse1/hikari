# Hikari — what this workspace is for

This perchance *generator* workspace is **not** Hikari. It is the workbench used to
edit the Android app in the GitHub repo **`codegeasse1/hikari`** on the user's
behalf, on their instruction, from chat.

Nothing here ships to the user's users: the app they install is built by that
repo's GitHub Actions workflow. `main.pjs` / `index.html` are unused scaffolding.

## How the work is done

- The repo is read and written through the **GitHub REST API** (Git Data API for
  a multi-file commit) with a **PAT the user pastes into chat**. The token is
  never written into a file, a commit, or a plan — re-ask for it if a session
  does not have it. (A previous session's token is in that session's transcript
  only.)
- The working copy of the repo lives at `scratch/repo/…` (repo-relative
  paths). **`scratch/` is EPHEMERAL** — it is gone in the next session. If a
  rebuild recipe matters, put it in the repo itself (a code comment, `docs/`,
  or `CHANGELOG.md`), not in `scratch/`.
- Push recipe that works (see `docs/RELEASING.md` in the repo for the project's
  own view): `GET /repos/codegeasse1/hikari/commits/main` → its `tree.sha` →
  `POST /git/blobs` per file (base64) → `POST /git/trees` with `base_tree` →
  `POST /git/commits` with `parents: [head]` → `PATCH /git/refs/heads/main`.
  `POST /git/commits` intermittently answers **422** through the proxy: retry the
  same body and it succeeds. Trust `GET /actions/runs?head_sha=…` for the pushed
  sha — a `GET /git/refs/heads/main` right after a push can return the OLD sha.
- Verify what is about to be pushed by hashing each local file as a git blob and
  comparing against the remote tree. This caught a **corrupted local
  `app/libs/quickjs-kt-android-1.0.5-nuvio.aar`** (3.5 MB locally vs 1.9 MB in the
  repo) that must NEVER be pushed.

## Rules the user set (do not break these)

- **Push code to `main`; never create a `main` release — unless the owner asks for
  one in writing in the current conversation.** CI (`.github/workflows/build.yml`)
  builds a signed APK on every push and publishes it to the **`continuous`
  pre-release** and the **`build`** branch, and that is what the user installs.
  Publishing a real release requires a manual `workflow_dispatch` with typed
  `CONFIRM-RELEASE`; it was done exactly once, for **v0.10.42** (the owner asked for
  it in chat, the build was green, and the release body is that version's CHANGELOG
  section — so the CHANGELOG section for a version that will be released has to read
  like user-facing release notes, not like an engineering post-mortem). Never
  dispatch it without an explicit request.
- **No blind fixes.** Read the full code path, read the upstream/library source
  when a behaviour is in question (TDLib's source settled the Telegram bug; a
  vendored base class settled the manga one), and say in the CHANGELOG what the
  real cause was.
- Bump `versionCode`/`versionName` and prepend a `CHANGELOG.md` section per
  release, in the existing house style (bold lead-in, then the cause and the fix
  in plain prose).
- After a push, wait for the Actions run and check it is green. On failure, read
  the job log (`/actions/jobs/{id}/logs`) and fix the compiler errors before
  reporting anything as done.

## Where the app's own knowledge lives

The repo documents itself: `docs/` (catalogs, manga reader, subtitle sites,
search, player panels, releasing, …) and a very detailed `CHANGELOG.md`. Read
those before changing a subsystem — most of this app's behaviour is deliberate
and written down.

## Requested, not yet built

(Nothing outstanding: the two features the owner asked about in chat — profiles,
and a switch that hides the explanation lines — were both built in **0.10.44**;
see the session log below, which is where their design notes live.)

## Session log (newest first)

- **0.10.59** (versionCode 230) — the owner's fourteenth round, four asks in one push. The owner was emphatic that
  the extension problem is **an app bug, not an extension bug** ("i told you thats bug in app instead you wasted
  time to edit hikari extension repos … dont in any extension sources"), so this round touched **only**
  `codegeasse1/hikari` — no file under `hikari-extensions` was changed.
  - **Extensions' catalogues: the real cause was R8, and it had never worked on a release build.** The 9 screenshots
    all show the same Home subtitle: `its plugin failed to load: NoSuchFieldError: No field Companion of type
    Lcom/hikari/app/HikariApp$Companion; in class Lcom/hikari/app/HikariApp; … (declaration of 'com.hikari.app.HikariApp'
    appears in base.apk! classes2.dex)` — AllWish/Animesalt/Animexin (Phisher), AnimeSuge (CNC Verse). Proven by
    **DEX-dumping both sides**: the shipped `hikari-arm64-v8a.apk` has `Lcom/hikari/app/HikariApp;` with
    `static HikariApp instance` + `static MainActivity mainActivity`, **no `Companion` field, and no
    `Lcom/hikari/app/HikariApp$Companion;` class at all** (class-by-class walk of the dex); the published
    `phisher.hiki`'s plugin bytecode references exactly `HikariApp->Companion`, `HikariApp$Companion.getMainActivity()`
    and `getInstance()`. Cause: R8 full mode **staticises the Kotlin companion** (methods move to the host class,
    field and class deleted); the app's own call sites are rewritten so it never breaks the app, but the extension's
    already-compiled plugin names the field by string. Fix is app-side only: `app/proguard-rules.pro` section
    **3d** keeps `com.hikari.app.HikariApp`, `com.hikari.app.HikariApp$Companion` and `public static ** Companion`
    (`-keep class X` alone does NOT match the nested `$Companion` name). Documented in
    `docs/HIKARI_EXTENSIONS.md` ("The host class the bridge talks to must survive R8") with the error text and a
    warning to change the stubs and the rule together. **Verified after the build by re-DEX-dumping the new APK**
    (companion class + field + accessors must exist) — the only verification possible without a device.
  - **IPTV live channels: a 20-second timeout was being treated as a dead server, and the "fix" the owner rejected
    (raising the timeout) is not the fix.** An IPTV channel is ONE `StreamSource` (`IptvProvider.getStreams`
    returns `listOf(...)` for a normal playlist channel), so the player's normal answer to a timeout — blacklist the
    host, walk to the next server, and while `!liveSearchDone` wait up to 180s for the search to hand over a
    replacement (`awaitReplacementForStalledServer`) — can only walk off a one-item list and then wait for a server
    no playlist can produce: the reported "keep searching instead of playing". Fixed with a live-source concept:
    new `PlayerActivity.isLiveSource()` (provider id starts with `IptvMark.ID_PREFIX` = `iptv|`, or media3's own
    `Player.isCurrentMediaItemLive` for any other provider's live HLS). Both watchdogs now route a live source to
    new `retryLiveStart()` (own budget `liveStartBudgetMs` = 30s, up to `maxLiveStartRetries` = 6 full re-opens —
    `playSource(currentIndex)` rebuilds the player, so each is a fresh connection, the actual cure for a live feed
    whose first segment never arrived); the error path routes to new `scheduleLiveReconnect()` (3s delay, then the
    same bounded re-open) instead of the search dance; `promptSlowServer` has a defensive live guard; the
    `startedWhileSearching` relink branch in `onPlayerError` is skipped for live; the retry counter resets in
    `onRenderedFirstFrame`; the pending reconnect task is removed in `onDestroy`. Live hosts are never added to
    `deadHosts` and the cross-extension search is never consulted for `iptv|`. Only after the retries does the
    honest "This live channel is not responding. It may be offline right now." appear.
  - **Telegram: videos showed only in Saved Messages and bot chats, never in channels; archived and some joined
    chats were missing entirely.** Root cause of the video half: the reader listed the chat's *newest 60 messages*
    (`GetChatHistory`) and kept the ones that were videos — for a channel that mostly posts text/links the newest 60
    contain no video at all, so the page read "No videos in this chat", while Saved Messages / a bot chat (where the
    owner posts videos) looked fine. Fixed by rewriting `Td.chatVideosPage` to merge **Telegram's own media index**
    (`SearchChatMessages(chatId, null, query="", null, fromMessageId=before, 0, limit, SearchMessagesFilterVideo())`
    → `FoundChatMessages` — the server-side Shared Media → Videos list the official clients show; the vendored
    `TdApi.java` documents empty-query + filter as valid) with the history walk, deduped by message id, cursor =
    oldest id either reader saw; history is kept as the second reader because it is the only one that sees a video
    posted as a FILE (MessageDocument with a video mime) or an animation, and it still works when the index refuses.
    `ChatHistoryVideos` gained `note` (a TDLib refusal is no longer printed as "no videos"); `query()` gained an
    `onError` hook + `errorText()`; `TelegramScreen`'s state block gained `note`/`cursor`/`reachedEnd` with a
    `loadOlder()` that walks up to 6 pages while the cursor moves back (a page can legitimately hold nothing NEW),
    the empty state offers "Search further back" always, and the footer button uses the same walk. Root cause of the
    list half: `loadChats()` did a single `LoadChats(ChatListMain(), 100)` — `LoadChats` is PAGED and signals
    "fully loaded" with error **404**, not an empty batch — and `publishChats()` filtered on `mainOrder != 0`, so
    every chat TDLib had not positioned yet and every archived chat vanished. Now: paged `loadChatPage()` over
    `ChatListMain` **and** `ChatListArchive` (`CHAT_LOAD_PAGE` 100, `CHAT_LOAD_MAX` 2000); `mainOrder`/
    `archiveOrder`/`leftBehind` maps; `absorbPosition`/`absorbPositions`/`forgetInList`; positions are read from
    `UpdateNewChat`, `UpdateChatPosition` **and** `UpdateChatLastMessage` (TDLib docs: the last is sent *instead of*
    `updateChatPosition`, which is why some joined channels never appeared); membership fallback via `chat.chatLists`
    with explicit removal winning, Saved Messages always shown; `Chat.archived` + sort Saved → main → archived.
    UI: a "Chats / Archived · N" pill row in `TelegramMyChats` (archive pill only when the count > 0) and a
    " · Archived" suffix on the row's kind label.
  - **Volume booster (new feature, Settings → Player).** A switch (default OFF) that attaches
    `android.media.audiofx.LoudnessEnhancer` to the player's audio session with `setTargetGain(600)` mB = +6 dB
    (10^(6/20) ≈ 2.0× amplitude = "200%"), applied after the decoder so it stacks on top of the device's own volume
    — the only way past the keys' 100% ceiling. `AppStore.VOLUME_BOOST` + `volumeBoostFlow()/volumeBoost()/
    setVolumeBoost()`; new `VolumeBoostCard` in `SettingsScreen` (PLAYER folder, next to Player UI);
    `PlayerActivity` reads it with the other player preferences, `applyVolumeBoost()` attaches/re-attaches/releases
    (called from `onTracksChanged`, a new `onAudioSessionIdChanged` override, and after the pref lands), release in
    `onDestroy`; a device whose HAL refuses the effect fails soft (unboosted playback, logged).
  - Also in this round: `versionCode` 230 / `versionName` `0.10.59`, this CHANGELOG section, and the
    `docs/HIKARI_EXTENSIONS.md` R8 note. Pushed as ONE commit to `codegeasse1/hikari` `main`; **continuous build
    only — no release was dispatched** (the owner's standing rule, restated this round as "do continuous build and
    not to release the APK to main").

- **0.10.58** (versionCode 229) — the owner's thirteenth round: **CNC Verse and Phisher `.hiki` extensions showed no
  catalogue** on Home (screenshots: `Couldn't load Donghuasteam · Donghuasteam (Phisher)`, `MovieBoxProvider
  (Phisher)`, `BanglaPlex (Phisher)`, `AnimeDekhoProvider · Onepace (Phisher)`, each with the generic "Nothing came
  back from this extension. Retry, or open its site in the WebView …" subtitle). The owner was explicit that this
  is **not** a verification/Cloudflare problem and asked for the real cause. Two faults, one on each side of the
  repo boundary — which is why the round touched **both** `codegeasse1/hikari` and `codegeasse1/hikari-extensions`:
  - **The bridge's catalogue protocol was wrong for every plugin, and every local failure was silent.** Root cause
    read out of the bridge source and the CloudStream base classes, not guessed: a CloudStream plugin answers
    `getMainPage` against the **page and row it was asked for** (`MainPageRequest(page.name, row.data, false)`),
    but `Cs3BridgeProvider.catalogs()` flattened everything into ONE `HikariCatalog("Home", "Home")` (id `"Home"`,
    requested page literally `"Home"`), so `getMainPage` matched no real page and every plugin returned nothing.
    Every local failure the bridge could have (no bundled plugin in the archive, `plugin.load()` threw, the plugin
    registered no provider, the page parsed to nothing) was also converted to the same silent `emptyList()`. The
    bridge now emits one catalogue **per home row** (`row:<page>:<row>`, fallback `page:<index>`, id never empty),
    passes each row's own page name + `data` through, and throws an `IllegalStateException("<name>: <reason>")` for
    local failures; the load is bounded (45s budget on a shared daemon executor, 60s failure cooldown, 12s host
    activity wait, 3s registration poll, `APIHolder.allProviders` before/after diff); `extract()` names its cached
    payload `<sha256-prefix8>-<basename>` so a stale payload is impossible. **A full external-class audit of all
    112 bundled `.cs3` files against `cloudstream3.jar` + the app source came back CLEAN** (the only unresolved
    family, `com/lagradost/nicehttp/**`, is a declared dependency) — i.e. the cause was the protocol/silent-empty
    pair, NOT a `NoClassDefFoundError`. (Audit caveat for a future session: a DEX header's `type_ids_size` is at
    **0x40** and `type_ids_off` at **0x44**; reading them at 0x48 gives nonsense.) It was also confirmed that no
    plugin references `MainActivity$Companion` or the consts `ANIMATED_OUTLINE`/`API_NAME_EXTRA_KEY`/
    `FILE_DELETE_KEY`/`nextSearchQuery`/`filesToDelete`/`activityResultLauncher`, so shadowing them was
    deliberately skipped.
  - **The app had no HIKARI entry in Home's failure chain at all.** `HomeScreen.engineFailureReason()` is a `?:`
    chain over each engine's `catalogErrors` map, and `.hiki` was missing from it and from `CatalogScreen`'s
    `providerReason()` — so an empty `.hiki` catalogue fell through to that "open its site …" line. New
    `HikariProviderAdapter.catalogErrors` (companion `ConcurrentHashMap`, cleared on success) is recorded from
    `catalogs()`/`getCatalog()`, read back in `HomeScreen.engineFailureReason()` (first in the chain) and in
    `CatalogScreen.providerReason()`; `ContentRepository.noteCatalogTimeout` now routes a HIKARI/Vega timeout into
    the right map. The adapter's doc comment explains that the old generic line sent users chasing a Cloudflare
    check that a bundled-plugin extension never performs.
  - **An already-installed `.hiki` could never have received the fix.** `repo.json` carried no `fileHash`, and the
    app's update check is `val hash = plugin.fileHash ?: continue; if (!hash.startsWith("sha256-")) continue` —
    so no entry in the Hikari repository could ever be offered as an update. `hikari-extensions/build.sh` now
    emits `"fileHash": "sha256-<sha256sum>"` for every `.hiki`, `.jar` and native `.cs3` in both `repo.json` and
    `repo-desktop.json`. This is the half that makes the bridge fix reachable; without it the extensions repo
    change is invisible to devices that already had the extensions.
  - Files: **hikari-extensions** — `cncverse|phisher|anime/src/com/hikari/ext/providers/Cs3BridgeProvider.kt`
    (all three byte-identical rewrites, 42885 B), `build.sh`, the three `manifest.json` version bumps
    (`anime` 1→2, `cncverse` 2→3, `phisher` 2→3); pushed as
    **`e2189ec1b660f7533a2027e14e196bf30ce9fc2c`** (parent `677449b2…`, tree `57886340…`). **hikari** —
    `providers/HikariProviderAdapter.kt`, `ui/screens/HomeScreen.kt`, `ui/screens/CatalogScreen.kt`,
    `data/ContentRepository.kt`, `app/build.gradle.kts`, `docs/HIKARI_EXTENSIONS.md` (new "CloudStream plugins
    inside a Hikari extension (the bridge)" + "Publishing the repository (`fileHash`)" sections, which is now the
    written-down description of the row-catalogue protocol), `CHANGELOG.md`, this file.
  - **The phisher bundle could not be rebuilt at all** — which is why the first two pushes of this round came back
    red. `CloudPlay.cs3` and `MovieBlast.cs3` had been removed from
    `phisher98/cloudstream-extensions-phisher@builds`, and `build.sh` treated a failed fetch as fatal, so the whole
    extensions CI run died on a 404 and **nothing** in that repo was published — the bridge fix could not reach a
    single device. Both files are gone from `phisher/bridge-sources.txt` (every one of the 76 remaining phisher
    paths, cncverse's 34 and anime's 5 was verified to return 200), their `PhiCloudPlay`/`PhiMovieBlast` wrapper
    classes and manifest entries are removed so no provider is registered that can never load, and `build.sh` now
    SKIPS a missing upstream `.cs3` with a `::warning::` (listed again at the end of the build) instead of aborting:
    one stale upstream path must not stop every other extension in the repo from shipping. The rebuilt
    `builds/repo.json` now carries a `sha256-…` `fileHash` on all 142 entries, so the app's update check can finally
    see updates at all. Verified after the green run by downloading the three published `.hiki` files from the
    `continuous` release: sha256 identical to `repo.json`, `classes.dex` contains the new row-catalogue code (and
    none of the old), and phisher bundles 76 `.cs3` with no CloudPlay/MovieBlast.
  - The `sdk/HikariProvider.kt` interface and `HikariCatalog` were deliberately **not** changed, so an old
    installed `.hiki` still loads; only its catalogue contents change.
  - **`continuous` only — no main release.** (The owner's round-13 message initially read as "do continuous build
    and not to release the apk to main" and was confirmed to be the standing rule, with a typo in the earlier
    wording.)

- **v0.10.57 RELEASED to `main`** (2026-09-13) — the owner asked in writing for the accumulated build
  (0.10.43–0.10.57, i.e. everything since v0.10.42) to become the live release. Before dispatching, the
  `## 0.10.57` CHANGELOG section was rewritten from engineering notes into user-facing release notes: what was
  added and what was fixed **since v0.10.42**, in the style of the 0.10.42 section. Bugs that existed only in
  intermediate continuous builds are deliberately NOT listed — the owner is the only one who ever ran those
  builds, so from a user's point of view they never happened, and release notes that "fixed" them would be
  describing the owner's own test cycle. Dispatched `.github/workflows/build.yml` by hand with
  `release=true`, `confirm_release=CONFIRM-RELEASE`, `version=0.10.57`; the release carries the three APKs and
  the body is that CHANGELOG section.
- **0.10.57** (versionCode 228) — the owner's twelfth round: four reported items (the app opening in the
  near-black theme instead of AMOLED on a fresh install, the subtitle settings sheet clipped in the landscape
  player, a drag on the colour picker's square scrolling the box instead of moving the colour, and anime titles
  from a `.hiki` repo sitting on "Searching your extension for servers…" for minutes). Push once, and
  **`continuous` only — no main release**. Files touched: `app/build.gradle.kts`, `ui/theme/Theme.kt`,
  `data/AppStore.kt`, `ui/AccentStore.kt`, `ui/navigation/AppNav.kt`, `ui/screens/SettingsScreen.kt`,
  `player/PlayerActivity.kt`, `player/ColorPickerDialog.kt`, `data/ContentRepository.kt`, `CHANGELOG.md`,
  `docs/{PLAYER_PANELS,SEARCH}.md`.
  - **AMOLED Black is the default theme.** DARK was the default in three places that must agree:
    `HikariThemeMode.fromKey`'s fallback, `AppStore.themeFlow()`, and `AccentStore`'s synchronous mirror
    (`AccentStore.theme()` is `MainActivity`'s `collectAsState(initial = …)`, i.e. the first frame, before
    DataStore has emitted anything — leaving it at "dark" would paint one dark frame then switch). The default
    is now written down ONCE, as `HikariThemeMode.DEFAULT_KEY = "amoled"` with `HikariThemeMode.DEFAULT =
    entries.first { it.key == DEFAULT_KEY }`; `AppStore.DEFAULT_THEME` is a `const` alias (const so it is inlined
    at compile time and `AccentStore`'s property initialiser cannot depend on another object's init order),
    `themeFlow()`/`AccentStore`/`AppRoot`'s default parameter/`SettingsScreen`'s `collectAsState(initial)`
    all read it. `fromKey`'s fallback is `DEFAULT` now, so an unknown stored key lands on AMOLED too. An
    explicit stored choice still wins; `HikariTheme(DEFAULT)`, `AppRoot(themeKey = DEFAULT.key)` and
    `pageBackground()` (AMOLED's `background` is opaque black, so it returns the scheme colour, not `HikariBg`)
    all behave unchanged. `AppRoot`'s `when` already draws NOTHING behind an AMOLED page.
  - **`fitToContent()` was asking for 2×halo of empty glass on every panel.** `wanted = contentH +
    scroll.padding + panel.paddingTop + panel.paddingBottom` counted the panel's padding whole, but that
    padding is measured from the VIEW's edge and already includes the halo around the silhouette
    (`CurvedGlassPanel.onSizeChanged`: `padV = haloPx + rowGapPx`, or `haloPx + flatTopGapPx` flat). The `- halo`
    (with `.coerceAtLeast(0)` for a fit that runs before the first layout, when padding is still 0) is what makes
    the fit exact; before it, each panel carried 20dp (flat) / 52dp (pane) of glass under its last row, and a
    sheet that did not fit the window spent that 52dp of the room before the clamp.
  - **The subtitle SETTINGS sheet's own rows were taller than the room.** Five `controlRow`s (label line + a
    weighted control row each, `addRow` with a 7dp top margin) came to ~325dp; a 1080p phone in landscape leaves
    the pane ~324dp once `roomFor()` has paid the hint line and 2×halo. So the last row ("Find subtitles
    automatically") opened with its pill sliced by the pane's bottom edge — the fix for a fit that misses by a
    hair is to stop missing by a hair: `controlRow` padding 7→4dp, its label-to-control gap 6→4dp, `addRow`'s top
    margin 7→4dp, and `rowLabel` gets `includeFontPadding = false` (~4dp of leading per label). ~280dp in all.
    Same sheet, same five settings. Do not "restore" the old spacing without redoing this arithmetic.
  - **The colour picker's three custom surfaces now claim the drag.** `SvSquare`/`HueStrip`/`AlphaStrip` set
    saturation/value/hue/alpha from the finger's `event.x`/`event.y`, but they live inside the dialog's own
    vertical scroller (added in 0.10.56 — the picker is ~390dp and a landscape window is shorter) and each strip
    inside a horizontal one, and a scrolling ancestor takes a drag over at the touch slop. New file-level
    `View.claimDragFor(event)` calls `parent?.requestDisallowInterceptTouchEvent(true)` on ACTION_DOWN and
    `false` on UP/CANCEL (the call walks the whole ancestor chain, so one call locks every scroller above the
    surface), and each of the three `onTouchEvent`s calls it first. ACTION_CANCEL releases too — a scroller left
    locked would freeze the box.
  - **A scoped pass whose ONLY target never answered now asks its engine family.** Root cause proven from the
    owner's log: with "search all installed extensions" off (the 0.10.39 default), no exception repos and the
    Hikari family switch off, an anime opened from `Anime4i` logs `primary=1 nuvio=0 cross=0 same=0 late=0` plus
    `family(Hikari) switch is off — 180 sibling repo(s) … are not asked`, so the pass has ONE target, that target
    is the one whose call never came back (`✗ the call never came back`, `no answer within 45s`), and no other
    extension is ever asked — no server can arrive. Fix in `streamsForInner`'s teardown, beside the existing
    sweep hand-off: when `crossTargets.isEmpty() && passFound.isEmpty() && !scopeAll &&
    providerOutcome[origin] != "no servers" && !isHung(origin)`, the origin's enabled same-`ProviderType` siblings
    are added to `sweepTargets` (title search, the same machinery as any cross repo — these are separate
    catalogues, unlike the id-resolving Nuvio/Stremio families) and the pass logs a `familyFallback "<title>": …`
    line. Deliberately narrow: a real "no servers" is an answer and is left alone, and a healthy origin is
    untouched, so invariant 17's promise still holds everywhere else. Note the OTHER half of that latency is
    unchanged and is not a bug this round fixed: a `.hiki` extension serialises its calls behind
    `ProviderGate`, so an abandoned first attempt plus the retry can queue behind the first call (the log's
    `BACKGROUND call started after waiting 26.436s — this extension was still finishing an earlier call`) — the
    retry eventually answers, which is why two of the three reported titles did resolve.

- **0.10.56** (versionCode 227) — the owner's eleventh round: a batch of small
  reported bugs (the player's look on a new install, the subtitle colour picker discarding a choice, the
  subtitle panel's last rows sliced in the landscape player, the colour picker too tall for it, the Home
  search box showing the feed through it, the app-lock screen doing the same, an extension row's "Show all"
  opening a personal catalog's folder grid, and a collection's Show-all page saying "0 titles · 0 catalogs"
  while Home showed plenty) plus, verbatim, **"also fix when search by clicking search bar at home, and click
  any search result and come back it directly leading to home instead of show the search, like instead of just
  back it leading full back to home. so fix this too."** Push once, and **`continuous` only — no main release**.
  - **A new install wears the Minimal player UI and shows the Telegram tab.** Two defaults were wrong for a
    fresh install, not two bugs: `PlayerSkins.FALLBACK` is now `MINIMAL` (was `DEFAULT`) and
    `AppStore.DEFAULT_PLAYER_SKIN` is `PlayerSkins.MINIMAL` (was `NEON`). `normalize()` still maps the
    pre-picker `glass` value to `DEFAULT` and passes every `ALL` entry through, so an explicit choice always
    wins and an install that picked the curved glass in the older build keeps exactly that — only "never
    chose" changed. `AppStore.telegramTabFlow()` and the two `collectAsState(initial = …)` call sites
    (`AppNav`, `SettingsScreen`) flipped from false to true; the switch still sticks when turned off.
  - **The subtitle colour picker now applies live, and Cancel is a real undo.** Root cause: the picker held a
    local draft (`hue/sat/value/alpha`) and only handed it to the caller on the Apply button, so the ✕ — the
    obvious way to close a picker that already showed the colour in a live caption preview — discarded the
    choice and the captions came back in the old colour. `ColorPickerDialog.publish()` now calls `onPick(c)`
    the moment the colour changes (`published` de-dupes, `touched` records that the user changed anything);
    ✕ and Apply just dismiss, Cancel calls `onPick(initial)` when `touched`. The dialog also got TV support
    (`makeTvReachable` / `focusRing` / `findFirstFocusable`, mirroring `PlayerActivity.View.tvFocusableTree`):
    `setOnClickListener` makes a view clickable but NOT focusable, so a remote could not reach the chips, the
    ✕ or the two buttons, and the three custom surfaces (square/hue/alpha) got D-pad `onKeyDown` handlers.
  - **The panel geometry rule changed from a fraction of the window to the ROOM the window has, and the panel
    re-measures when its window changes.** `PlayerActivity.presentGlass`: the height cap was
    `min(fitsScreen, 0.66|0.76 of the window)` — a fixed fraction that in the landscape player leaves ~233dp
    for a ~291dp subtitle sheet, so its last row was cut with no scrollbar to say anything was missing.
    `chrome`/`fitsScreen`/`maxFraction` are deleted; the cap is `roomFor(win, w)` = the window's height minus
    the MEASURED height of the hint line (`headerRow.measure(...)` — it is a wrapped 4-line cap, not a
    constant) minus the halo, floored at 110dp. `panelWidthFor(w)`/`headerHeightFor(w)`/`roomFor(...)` are
    local functions of the window now, `fitToContent()` coerces into the same room, and a layout listener on
    the activity decor calls `refitToWindow()` so a panel that outlives the window it was measured in (the
    player rotates itself and is fullscreen, and a dialog window keeps the layout it was SHOWN with) is
    re-measured — the "sometimes, and reopening the player fixes it" report. The 0.9.8 structure is
    untouched: definite panel height + a weighted scroller; do NOT reintroduce a cap on the scroller.
  - **The colour picker itself fits a landscape window now.** It is ~390dp tall and the landscape player's
    window is ~393dp, so on a slightly shorter window its buttons sat past the screen edge, unreachable. In
    `ColorPickerDialog.show()` the panel is measured first, wrapped in a `ScrollView`, and the window is
    `panel.measuredHeight.coerceAtMost(winH - dp(24f))`.
  - **Full-screen covers paint `pageBackground()`, not `MaterialTheme.colorScheme.background`.** The Dark
    Glass theme's `background` is TRANSPARENT by design (its page colour is a gradient drawn behind the whole
    app), so the Home search overlay showed the feed's hero banner and "View Details" pill through it, and
    the app-lock screen let the locked content be read through it. New `@Composable fun pageBackground()` in
    `ui/theme/Theme.kt` returns the scheme's own background when its alpha >= 0.99f, else the solid `HikariBg`;
    `HomeSearchOverlay` and all three covers in `AppLockGate` use it.
  - **Back from a Home search result returns to the search.** `showHomeSearch` was a plain `remember`, and
    pushing a result's detail page takes `HomeScreen` out of composition, so it was destroyed and Back landed
    on the feed with the query gone. It is `rememberSaveable` now and is deliberately NOT cleared by the
    overlay's `onOpen`; the overlay's own ✕ / `BackHandler` are what close it. `HomeSearchOverlay`'s `typed`
    and `applied` were already saveable (so the query survives and the search re-runs on return). This is the
    owner's verbatim ask above.
  - **"Show all" routes by OWNERSHIP, not by the presence of a key prefix.** `HomeScreen`'s unique-rows
    `onShowAll` sent any row whose key started with `coll|` to the collection grid — but a plain EXTENSION row
    inside a picked personal catalog carries that prefix too (see `CollectionsRepository`, which keys a
    collection's rows `coll|<collectionId>|…`). It computes `collectionId` from the row's key and uses
    `Routes.collectionGrid(id)` only when it resolves, else `Routes.catalog(row.providerId, row.catalogId, …)`.
  - **`CollectionGridScreen` says what Home says before it says "nothing", and hands over to the folders when
    there is truly nothing.** It only ever ran `allRows` (every catalog of every folder), so a collection whose
    sources are structured for Home's shelf view reported "0 titles · 0 catalogs" above "this collection's
    catalogs returned no content" while Home — same collection — showed plenty. It now falls back to
    `CollectionsRepository.pickRows` (exactly "what Home shows"), then to `CollectionFoldersPage` (the folder
    tiles, which are the way in); a missing id shows only the `Collection not found` header, like
    `CollectionViewScreen`. `lastOrNull` on a Flow needed `kotlinx.coroutines.flow.lastOrNull` imported here.
  - **A collection flow always emits, even when everything answered nothing.** The three
    `CollectionsRepository` flows published only non-empty lists, so a collection whose sources all returned
    nothing never emitted at all — and the screen's "loading" state is a null list, so it sat on the spinner
    for ever. Each flow now wraps its launches in `coroutineScope { }` and ends with
    `publish(..., force = true)`; intermediate publishes stay non-forced, because an early empty list would
    read as "loaded and empty" while the first source is still working.
  - **A repo row can be copied, and a fresh install ships SubDL's Stremio addon.** `ExtensionsScreen.RepoCard`
    gained a copy button (`ContentCopy` + `LocalClipboardManager`, toast `Repo link copied`) where the
    decorative `ChevronRight` used to be — the trailing cluster is three real actions, which also stops four
    items squeezing the repo name to a few characters. `StremioAddon` gained `DEFAULT_ADDON_URL`
    (`https://api3.subdl.com/manifest.json`) and `providerIdFor(url)` (id from the BASE url, so
    `https://host`, `…/` and `…/manifest.json` are ONE provider row); `HikariApp` seeds it on first run under
    a one-way `stremioAddonSeeded` flag (new `K.STREMIO_SEEDED` key + `AppStore.stremioAddonSeeded()` /
    `markStremioAddonSeeded()`), skipping an install that already has it under any spelling, and
    `ExtensionsScreen.addStremio` uses the same `providerIdFor`.
  - **Files**: TWO code commits. The first (21 paths) is `app/build.gradle.kts` + 14 Kotlin sources
    (`HikariApp.kt`, `player/{PlayerActivity, ColorPickerDialog, PlayerSkins}.kt`, `ui/theme/Theme.kt`,
    `ui/AppLockGate.kt`, `ui/navigation/AppNav.kt`, `ui/screens/{HomeScreen, CollectionScreens,
    ExtensionsScreen, SettingsScreen}.kt`, `data/{AppStore, CollectionsRepository}.kt`,
    `providers/StremioAddon.kt`), `CHANGELOG.md`, and `docs/{PLAYER_PANELS, PERSONAL_CATALOG, APP_LOCK,
    SEARCH, SUBTITLE_SITES}.md` — then a docs-only commit carrying this `src/README.md`. The change set was
    proved by comparing every local file's git blob SHA-1 against main's recursive tree (all 578 files): only
    those paths differ.
  - **The 0.10.55 warning about stale local docs is RESOLVED.** `docs/SEARCH.md` and `docs/VEGA.md` were
    re-fetched at main's HEAD. `VEGA.md` came back byte-identical to main's (so it stayed out of the commit)
    and the new Home-search section was added on top of main's current `SEARCH.md` (not on top of the stale
    copy), so nothing main-side was reverted. A future session can edit either file freely again.
  - **Not built this round, and why (do not pretend otherwise):** automatic extension update checks
    (`ExtensionsViewModel.checkUpdates()` + the Update buttons + the "updates available" card already exist and
    run when the Extensions screen opens — a launch-time background check would need the repo listings, which
    only that screen fetches); the reported television player lag on Play (no concrete cause identified); and
    D-pad focus beyond the colour picker (the infrastructure is extensive — `tv/TvFocus.kt`,
    `MainActivity.applyTvFocus`, `View.tvFocusableTree` in the player, `Modifier.tvPress` — and no specific gap
    was found).  - **Pushed as TWO code commits**, because CI caught exactly one compile error on the first try and the fix
    could not be folded in (the commit was already public): the feature commit
    `ac66b4aac1d7509e407221e5a1f508d4ceb4c6b2` (parent `effc5cf52ff8`, 21 paths) and the one-line fix
    `85900d8ac1421c45983a3b7bcc3f64fe38b25599`. Every pushed file was verified by git blob SHA-1 against
    main's recursive tree (all 578 files), which is what proves the change set is exactly those 21 paths and
    that `docs/VEGA.md` is back in sync.
  - **The one compile error, for the record**: `ExtensionsScreen.kt:5964:41 — @Composable invocations can only
    happen from the context of a @Composable function`. `tr(...)` is `@Composable` (it reads a
    `CompositionLocal`), so the new copy-button's `Toast.makeText(context, tr("Repo link copied"), …)` INSIDE
    an `onClick` lambda was illegal; a click handler is not a composable scope. It now uses `I18n.t(...)` — the
    non-composable translate the file's other toasts already use (see the note at `CollectionScreens.kt:3558`,
    where a `tr` is hoisted into a `val` for the same reason). Rule of thumb for this repo: `tr` in composable
    content and composable arguments, `I18n.t` inside event handlers.
  - **CI**: run `36319894753` on `85900d8a` — **success**; the feature commit's run `36319538554` was the one
    that failed on the error above. `continuous` re-uploaded its 3 APKs (41,345,697 / 39,572,723 / 61,694,500 B
    at 12:58:09Z), the `build` branch went to `fb9ec66dd934fb3599f4cc2278a3b77c175bd863` ("build: update test
    APK 202609271257"), and the newest STABLE release is still **v0.10.42** — no main release, as asked.

- **0.10.55** (versionCode 226) — the owner's tenth round, one message with four screenshots and two asks:
  **make the personal catalog look like the reference client** ("can we make our imported personal catalog from
  nuvio, and own personal catalog creator to look like nuvio, see our is showing glasy corner, which cutting
  some name, so fix this or the glass box inside the glads box remove it so it look goods") and **fix the
  television freezes/crashes while a detail screen loads its data and episodes** ("most tv come with 1-1.5gb
  ram, so do this only for tv make sure this setting doesnt affect android apk … do all lag fix, freeze fix").
  Push once, and **`continuous` only — no main release**.
  - **The screenshots were identified by their nav bar, not assumed.** `…150557`/`…150600` are the reference
    client (4 tabs, "0.5.3-beta"); `…150625` is Hikari (7 tabs); `…150630` is a THIRD app (8 tabs, "v2.0.0").
    Vision on a 1080×2460 screenshot downscales it to 450×1024 and gave contradictory answers about the tiles
    ("no inner border" then "a distinct lighter rounded glass border"), so the bands were cropped and upscaled
    1.6×/2.6× with `OffscreenCanvas` and re-read: what is actually there is a thin light hairline around each
    cover, INSET from the tile panel's own hairline — the nested box — plus wordmark covers whose lettering is
    cut at the artwork's bottom corners. Both are the tile's own doing, not the assets'.
  - **The tile fix is `CollectionScreens.kt` only** (`FolderTile` + `CoverArt`), so the SAME widget fixes all
    three places a folder is drawn (the creator's grid, the horizontal row, and Home's
    `CollectionFoldersOnHome`). Two faults, two fixes: the cover drew `PosterArt`'s own `style.glass` hairline
    inside the panel's (`CoverArt(ownGlass = false)`, and the non-image fallback branch loses its border too),
    and the panel used the flat `GlassCornerRadius` (26dp) whatever the tile's width, which on a 102dp tile is
    a quarter of it. The panel's radius is now `min(GlassCornerRadius, width × 0.16f)` — read from a new
    `BoxWithConstraints`, the only new import besides `GlassCornerRadius` — and the cover's radius is
    `panelRadius - TILE_PADDING` (the concentric-corner rule), so the two arcs run parallel. The cap is reached
    at ~163dp, so a TV's larger cells are unchanged, and 0.10.54's "painted, never clipped" is still in force.
  - **The TV work is memory, and it is keyed on HARDWARE, not on a switch.** `PerfMode.tvDevice` is a NEW flag
    mirrored from `TvMode.deviceIsTelevision` by a new `HikariApp.syncTvDeviceFlag()` (called beside every
    `TvMode.detect` — `onCreate` and `MainActivity`), which is what makes all of it television-only: every
    phone/tablet branch is untouched. It is deliberately NOT `PerfMode.tvOn`, because a 1GB box has 1GB of RAM
    with every setting off — so each runtime's new TV pool is tested BEFORE the booster's. Numbers:
    `PerfMode.tvEngineMemoryLimit` = `min(64MB, maxMemory/4)` replaces 256MB in `NuvioRuntime` and
    `VegaRuntime` (read per engine creation) and is applied to `SkyStreamRuntime` ONLY on a TV (that runtime
    had no cap at all, so a phone stays exactly as it was); engine pools 4/2/3 (Nuvio/Vega/SkyStream) replace
    12/8/6; `deviceFanOut()` gains a `cores×2` (6..16) branch ahead of the booster's; and
    `CROSS_EXT_EXTRACT_CONCURRENCY` 20→10 / `CROSS_EXT_DETAIL_CONCURRENCY` 32→12 — the latter is the cap the
    detail page's episode load actually runs through, and the reported freeze is that burst. Those two are
    class-init `val`s, so the flag must be set before `ContentRepository` loads; `HikariApp.onCreate` does
    that a few lines after `TvMode.detect`.
  - **`onTrimMemory` now actually frees memory on a TV.** Its own doc comment said there was deliberately
    nothing to drop, which is true on a phone and useless on a box about to be killed: at
    `TRIM_MEMORY_RUNNING_LOW`+ on `tvDevice` it now clears Coil's memory cache (and says so in the log) — the
    largest thing the app can hand back without touching the engines a load is waiting on. Coil's cache is
    also a twelfth of the heap (16..64MB) instead of an eighth (24..96MB) on a TV, in the same builder.
  - **One bonus lag fix, same gate**: `FolderTile` draws an animated cover as its first frame when
    `tvDevice && tvOn` — the same switch, and the same reasoning, as the poster effects `rememberPosterStyle`
    already drops on a TV. A folder's own `gifAlways` still wins, so nothing the user configured is ignored.
  - **Pushed as ONE code commit, `f921de84a10d9f30f05100f68f235f53d61643c0`** (parent `395ef54fe47b`), 12 files
    (9 Kotlin/gradle + `CHANGELOG.md` + `docs/PERFORMANCE.md` + `docs/PERSONAL_CATALOG.md`). Every pushed file
    was verified by git blob SHA-1, and a full local-vs-remote sweep of all 578 files confirmed the ONLY
    changed paths are those 12 — nothing else drifted. ⚠️ `scratch/repo/docs/SEARCH.md` and `docs/VEGA.md` are
    STALE locally (missing 33 and 11 lines of 0.10.54's content); they were deliberately NOT pushed, and a
    future session must re-fetch them from the repo before editing them.
  - **CI**: run `36312027921` on `f921de84` — **success** (~14 min), first try. `continuous` re-uploaded its 3
    APKs (41,337,305 / 39,564,331 / 61,686,108 B), `build` branch → `14e7b1a48477` ("build: update test APK
    202609271031"), and the newest STABLE release is still **v0.10.42** — no main release, as asked.
  - **GitHub API gotcha worth remembering**: `GET /git/refs/heads/main` (the singular endpoint) returned the
    OLD sha for minutes after a successful `PATCH` — twice this session, once making a push look like it had
    failed and the verification look like it had failed with it. `GET /commits/main` and `GET /git/refs` (the
    list) were correct immediately; verify against those.

- **0.10.54** (versionCode 225) — the owner's ninth round, in two halves: **revert the Vega work of 0.10.53**
  ("even series is not loading any episode, just showing play button like movie … revert back to 0.10.52 code,
  just remove what you did extra in 0.10.53, don't remove or make anything worse") and **add per-engine filter
  chips to the Search tab** ("if I search black clover I can click cloudstream to see only CloudStream search,
  or hikari to see search from all Hikari extensions"). Push once, and **`continuous` only — no main release**.
  - **The revert is byte-exact, not re-derived.** The 0.10.52 contents were fetched from the repo itself (the
    tree of `80d385211a7fd63021cc80ad89539eb1fcdcffb8`, the 0.10.53 code commit's parent) by git blob sha:
    `vega/harness.js` `0e41a45e465334e47fdcc8cab450a62905852efd` (25,091 B),
    `VegaProvider.kt` `3259085817e7f2951bed402cd07e239532f6cb31` (28,626 B),
    `VegaRuntime.kt` `493867cc3a49b859d16e3c43e2a79d7d789402fd` (36,938 B),
    `CHANGELOG.md` `f8dedfe2c6b4328c4570a04fe7b6984a033c4600` (437,786 B). All four were written into the working
    copy byte for byte (byte counts verified after the write), then the version was bumped to 0.10.54/225 and the
    new CHANGELOG section added. `app/build.gradle.kts` differs from 0.10.52 ONLY in `versionCode`/`versionName`.
    The three restored code files hash to exactly those blob shas inside the new commit (verified by hashing the
    local files as git blobs and comparing against the pushed tree), so "reverted to 0.10.52" is a provable
    equality, not a reconstruction — and a local-vs-remote hash sweep of every file in the working copy confirmed
    NOTHING else changed (no stray edits, and the repository's large binary blobs under `app/libs/` hashed
    byte-identical to the repo's own, so nothing bulky was rewritten by accident).
  - **What was removed with it (recorded so nobody has to re-derive it).** Everything 0.10.53 added:
    `__vegaCallManySettled` + its comment in `vega/harness.js`, `VegaRuntime.callManySettled` and the corrected
    `callMany` KDoc, and in `VegaProvider`: `NetTuning` import, `MAX_MOVIE_LINKS`, `LOOKUP_*` budgets,
    `IMAGE_EXTENSIONS`/`IMAGE_HOSTS`, `movieLinksCache`+`VegaLink`+`movieLinks`+`isImageLink`,
    `StreamLookup`/`StreamCall`/`lookupStreams`/`settledCalls`, `mapStreams(data, qualityHint)`, and the
    position-matched episode arrays (`DetailJob.episodes: CompletableDeferred<JSONArray?>`,
    `seasonEpisodesFrom(data): JSONArray?`, `seasonEpisodeArrays`, per-index `linkIndex`/`seenLinks`/`label`).
    The **movie multi-quality half goes with it** — the movie fix was not left half-applied. It was built as a
    self-contained change (its own harness function + runtime wrapper + `lookupStreams`), so re-applying ONLY
    that half later is a clean, separable job if the owner wants it back; that is the recommended next step if
    movies on Vega providers go back to "No playable sources".
  - **Why it broke series (analysis, with the honest limit).** The whole 0.10.53 diff was re-read line by line:
    in the SERIES path the only behavioural difference between 0.10.52 and 0.10.53 is how the runtime's
    `episodes` array (one entry per `linkList` entry carrying an `episodesLink`, in list order) is matched to
    the season rows — 0.10.52 keyed it by SEASON NUMBER (`Map<Int, JSONArray>`, so two packs of one season
    overwrite each other: the duplicate-rows screenshot), 0.10.53 read it BY POSITION (`arrays?.optJSONArray(idx)`,
    with `linkIndex++` consumed only for a season whose `directLinks` is empty, and a `seenLinks` skip for a
    repeated `episodesLink`). For the Movies4u shape (series entries carry `directLinks: []` — see
    `scratch/analysis/movies4u/meta.pretty.js`) those two index walks agree, which is why a narrower fix was not
    attempted blind: reproducing the owner's exact provider needs the real site + a live QuickJS engine, which is
    not available off-device, and guessing in this path is what produced the regression. The owner's instruction
    is the tie-breaker: restore the matcher that is known to list episodes (0.10.52's), duplicates and all.
    Also noted for the record: 0.10.53 capped a single-link lookup's engine budget at 35 s/42 s (`LOOKUP_BUDGET_MS`)
    where 0.10.52 gave it `CALL_TIMEOUT_MS` = 60 s — another difference in the episode PLAYBACK path that comes
    back to 60 s with the revert.
  - **Search-tab engine chips (`SearchScreen.kt`).** New state: `engineOf` (`providerId → type.groupLabel`,
    remembered off `providers`), `engineKey` (`rememberSaveable`, `""` = every engine) and `engineCounts`
    (hits per engine over `filtered`, i.e. AFTER the kind/year/genre strips, most hits first). A `visible`
    list (`filtered`, or `filtered` restricted to `engineOf[item.providerId] == engineKey`) is what the grid
    now renders (`rememberVisibleItems(visible)`) and what the "N shown · M found" line counts, and the line's
    gate is now `(filterOn || engineKey.isNotBlank())`. The strip itself is a `LazyRow` of `EngineChip`s
    (a copy of the screen's `YearChip` style) drawn at the top of the results column, `All · <total>` first,
    then one chip per engine by hit count — rendered ONLY when `engineCounts.size >= 2`, so a single-source
    search never sees a strip that can do nothing, and the counts come from the pre-engine list so picking a
    chip can never change the row (the way back is always in the same place). Tapping the chip in force clears
    it, like the provider picker's own engine chips. The user's screenshot
    (`scratch/message-attachments/Screenshot_20260927-141553.jpg`) is the Search tab this changes.
  - **Pushed as TWO code commits** on `main`, both green, then this `src/README.md`-only commit (which
    `paths-ignore: '**.md'` does not build):
    - **`9f8e319873e3e038b9204724e5aced55402578ab`** (parent `04b67d9d09f7601e9473f489d8da7379fe48da29`, the
      0.10.53 docs commit) — the revert (harness.js, VegaProvider.kt, VegaRuntime.kt, version), the Search engine
      strip (SearchScreen.kt), CHANGELOG 0.10.54, and the `docs/SEARCH.md` + `docs/VEGA.md` notes. CI run
      **36307990488** — **success** at 09:16:37Z. This run is the compile validation of the whole revert + the
      first cut of the strip.
    - **`962fca4d30f323be97bbaf24b294c2fbd9c6fb06`** — the strip's own dead-end fix (below). CI run
      **36308870977** — **success** at 09:32:03Z.
    - `continuous` re-uploaded its 3 APKs at **2026-09-27T09:32:00Z** (arm64 41,335,869 B; armeabi-v7a
      39,562,891 B; signed 61,684,668 B); `build` branch = `a8f3045eda` "build: update test APK 202609270931";
      **NO new main release** (the newest real release is still **v0.10.42**) — the workflow's
      "Refuse unconfirmed main release" / "Publish main release (manual only)" steps are the guard, and neither
      was dispatched.
  - **A dead-end in the strip was found and fixed between the two pushes** (worth remembering: the strip is a
    filter over an ALREADY-FILTERED list, so its own chip can be narrowed off it). The strip is drawn only when
    `engineCounts.size >= 2`, but a pick could survive that becoming false — e.g. pick "CloudStream", then a year
    filter removes every CloudStream hit while Hikari hits remain: the grid filters to an engine with no chip left
    to clear it, and no way back to "All". The fix: `activeEngine = engineKey.takeIf { engineCounts.containsKey(it) }`
    is what the filter and the chips obey (a vanished pick stops filtering), a `LaunchedEffect` drops it from the
    saved state so it cannot silently re-apply when the strips change back, and the strip is drawn while a pick is
    in force at ANY count (`engineCounts.size >= 2 || activeEngine.isNotBlank()`) so the pick always has an undo.

- **0.10.53** (versionCode 224) — the owner's eighth round: **"make the Vega provider play like the Vega app
  does"** for a movie whose post carries several quality rows (the report: Movies4u in Hikari shows only a Play
  button and then "No playable sources for this title", while the Vega app shows FOUR options to play for the
  same title), **"fix all the issues in one go"**, and an explicit instruction to **NOT** touch the Lulustream /
  network-stream playback 403 this round (that analysis was done and deliberately deferred — see the last bullet).
  Built and pushed as one commit (`6939782f5fc2fab586be5e69fa85f7b514c766e0`; parent
  `80d385211a7fd63021cc80ad89539eb1fcdcffb8`, the 0.10.52 docs commit; CI run **36280879424** — **success on
  the first try**; `continuous` re-uploaded its 3 APKs at 00:08:55Z, `build` branch =
  `build: update test APK 202609270008` (`475e23e734`); **NO new main release** — the newest real release is
  still v0.10.42), followed by this `src/README.md`-only commit, which `paths-ignore: '**.md'` does not build.
  - **The diagnosis (why the Vega app had four options and we had none).** Both apps run the same provider code;
    what differs is what each does with the provider's `Info.linkList`. A Vega post's `linkList` has ONE ENTRY
    PER QUALITY ROW (`480p [650MB]`, `720p HEVC`, `1080p HEVC`, `1080p`) and each entry carries its own
    `directLinks[0]`; the Vega app's detail screen renders those entries (its `SeasonList` draws a dropdown over
    the whole `LinkList` plus a row per entry, and `providerDiagnostics.getPlayableLink` picks one `directLink`),
    so ONE entry = ONE `getStream` call and the user chooses. `VegaProvider.getStreams` took the FIRST entry
    (`movieLink`) and made ONE call — so entries 2..4 were never resolved and had no UI at all. Worse, the first
    entry is not reliably a download: these providers build `linkList` by scanning `h3/h4/p` whose text matches
    `\d+p` and pairing it with the first anchor that follows it in the page (see `scratch/analysis/movies4u/`
    `meta.pretty.js`), which also catches the synopsis paragraph ("…available in 480p & 720p & 1080p") — and the
    anchor that follows THAT is a screenshot host. That is exactly the link the old code extracted, and the
    earlier session's log line (`HubCloud extract https://postimages.org/ failed`) is the proof. Hence: 0.10.52
    removed the crash, and this round removed the WRONG LINK plus the one-link limit.
  - **`__vegaCallManySettled` (new, `vega/harness.js`).** `__vegaCallMany` flattens every failure to `null`, so a
    caller cannot tell "answered nothing" from "its extraction threw, and the provider said why". The new variant
    answers each slot as the JSON TEXT of `{"ok":true,"data":…}` / `{"ok":false,"error":…}` (per-slot
    serialization also keeps one unserializable result from taking the whole run down), and takes a third
    argument `settleAfterMs`: > 0 races `Promise.all` against `g.setTimeout(finish, ms)` and answers with what has
    LANDED, so one dead host (whose extraction walks several requests in turn) cannot outlive the caller's whole
    budget and take the servers the other entries found with it. Still-running slots are `null`, never an error.
  - **`VegaRuntime.callManySettled(…, settleAfterMs, budgetMs)`** wraps it, and the stale "sequentially" KDoc on
    `callMany` (concurrent since 0.10.52) was corrected.
  - **`VegaProvider` movie path.** `movieLinks(item)` collects EVERY candidate (`directLinks[*].link` per entry,
    then the entry's own `link`, then `webUrl`, then the item id as the last resort — deduped, capped at
    `MAX_MOVIE_LINKS = 8`), sorted so entries whose link is an image file or a screenshot host (`isImageLink`:
    extensions + a small host list, incl. `postimages.org`) go LAST — never dropped, only ordered, so the cap can
    never cut a real quality row in their favour. `lookupStreams` runs them all in ONE engine through
    `callManySettled`, merges the servers, de-duplicates by URL, and names each with the quality of the entry it
    came from (a `mapStreams(data, qualityHint)` parameter: the provider's own per-stream quality still wins),
    so four otherwise identically-named servers are distinguishable in the player's list. When nothing comes
    back, the provider's OWN error is surfaced (the card used to say the generic line). Budgets: settle
    25 s → cap 30 s, engine 35 s → cap 42 s, both scaled by `NetTuning.timeout` (×3 in Slow connection mode) and
    capped UNDER the app's own per-provider lookup budget (`CROSS_EXT_STREAMS_TIMEOUT_MS` = 45 s, 50 s in slow
    mode), because a lookup that overruns that is reported as a timeout instead of as the servers it found.
  - **Vega series: the packs no longer overwrite each other.** The runtime answers `getEpisodes` once per
    `linkList` entry with an `episodesLink`, in list order, but the host matched those answers to seasons by
    SEASON NUMBER (`Map<Int, JSONArray>`), and two rows of one season share their number (a 1080p pack and a
    720p pack of "Season 1" are both 1) — so the second answer overwrote the first and BOTH rows rendered the
    same pack's episodes, twice (the owner's screenshot: "1 NEX DRIVE", "1 NEX DRIVE", "2 Episode 1", "2
    Episode 1" — `DetailScreen` shows a season selector only when 2+ DISTINCT season numbers exist, so a
    same-numbered pair renders as one flat list, sorted by `(season, number)`). The `DetailJob.episodes`
    deferred now carries the RAW array and the host reads it BY INDEX (`seasonEpisodeArrays`), which is what the
    runtime's own `seasonRequests` order means; a season whose `episodesLink` is literally the same page as one
    already used is dropped (its index still consumed), and when several packs share a season number the episode
    rows carry the pack's quality ("1080p · NEX DRIVE") so the sets read as the different things they are.
  - **Verification done (scratch is ephemeral — the METHOD is what to keep).** (1) `esbuild-wasm` parses both
    harnesses (`execute_js` → `esm.sh/esbuild-wasm@0.21.5`, loader `js`). (2) The harness boots in a plain V8
    worker: evaluate `nuvio/boot.js`, register a stub `__nuvioCheerio`, evaluate `nuvio/harness.js`, then the
    register glue `VegaRuntime.buildRegisterScript` performs (cheerio registration, `__nuvioFetchImpl`,
    `__nuvioBridgeStub`, `__vegaLoadIsolated`, `__vegaCommonHeadersJson` from `vega/commonHeaders.js`,
    `__vegaProviderValue`, `__vegaKvJson`), then `vega/harness.js` — plus stubs for `__vegDone`/`__vegaKv*`/
    `__vegaLog`/`__vegProgress`/`__hikariFetch` and a fake `stream` module loaded exactly as
    `VegaRuntime.loadModule` wraps a provider file. That proved the payload shape the Kotlin parser expects
    (array of JSON strings + `null` holes), per-slot error propagation, the circular-result isolation, the
    deadline returning partial results, and index alignment with a slow first entry. (3) The QuickJS-only half:
    shadow `globalThis.setTimeout`/`clearTimeout` with own properties (QuickJS has NO timers — the harness then
    installs its shim), call `__vegaCallManySettled('getStream', […], 150)` and drive it the way the host's pump
    loop does (`__vegaFireTimer()` in a loop). Result: right after the call `__vegaFireTimer()` reported a
    parked timer due in **150 ms**, the pump fired it (`…9, 0` = "a timer just fired"), and the payload landed at
    **151 ms** carrying the working entry's server plus `null` for the hung one — i.e. the deadline really does
    fire on-device, and the fallback (`Promise.all` finishing first) is untouched.
  - **Deliberately NOT done, and what is known about it** (the owner said not to touch it this round): the
    Lulustream / pasted-network-stream `403` that the player reports as "Server failed". Established so far —
    the source IS resolved (the sheet lists `LuluStream`/`Luluvdo 640p`), and the CDN (`*.tnmr.org/hls2/…`)
    refuses the PLAYER's request. Hikari's extraction stack has the session handling (the jar's `app` client is
    wired with `CloudflareVerifier.intercept`, which re-uses the WebView's `cf_clearance` + WebView UA), while
    `PlayerHttp.client` is a plain OkHttp with **no cookie jar and no Cloudflare interceptor**, and the
    `OkHttpDataSource` it feeds does not handle Set-Cookie either. CloudStream's own player is built from
    `app.baseClient` (`.setHandleSetCookieRequests(true)` on its Cronet path), i.e. the SAME client that earned
    the cookies — which is the difference. `M3u8Helper` having fetched the master playlist successfully during
    extraction (that is where the 640p variant came from) is evidence the CDN wants that session. The agreed-safe
    shape for a future round: carry the extraction session as a `StreamSource` header (the player already drops
    headers one step at a time on rejection, `PlayerActivity.headerVariant 0→1→2`) and add `cf_clearance` only
    as a LATER rung of that ladder — never blanket-attaching a WebView cookie jar to the first attempt, because
    there are hosts that 403 any request carrying a cookie. One unknown remains: whether the tnmr.org 403 is
    cookie/clearance-gated or token/IP-locked (which cookies would not fix) — one device log of that failing URL
    settles it.

- **0.10.52** (versionCode 223) — the owner's seventh round; four asks (a network stream
  pasted out of a playing site must play; Movies4u from the Vega provider must work the way
  it does in the Vega app; a Vega series must SHOW that its episode list is loading, and load
  faster; a crash when tapping an extension's settings gear), built in one batch and pushed
  as one commit (`b329e79bdf4aff8eb6fb45b1a8f444c7447ba096`; parent
  `382efe26a3aeff940de958a9197545eec811ced1`; CI run **36275989456** — **success**;
  `continuous` republished 22:36:05Z, `build` branch = `build: update test APK 202609262235`;
  **NO new main release** — the newest real release is still v0.10.42), followed by this
  `src/README.md`-only commit, which `paths-ignore: '**.md'` does not build. Compiled first
  try — unlike the two rounds before it, which each needed a compile-fix commit.
  - **Vega/Movies4u: cheerio result sets were not iterable.** `nuvio/cheerio.js` is
    `cheerio-without-node-native@0.20.2`, whose result sets are array-LIKE objects with no
    `Symbol.iterator`, so `for (const el of $(...))` — ordinary provider code — throws
    `TypeError: value is not iterable` (QuickJS's wording), which is exactly what Movies4u
    reported on every title (`Movies4u stream failed: HubCloud extract <url> failed: value is
    not iterable`). The Vega app's own bundled cheerio is patched for this; its minified
    runtime contains the two lines verbatim — `ut.prototype.splice =
    Array.prototype.splice; ut.prototype[Symbol.iterator] = Array.prototype[Symbol.iterator];`.
    `nuvio/harness.js` now defines `__hikariMakeCheerioIterable(module)` (published on the
    global, so the vega harness calls the same one), which reaches the BASE prototype through
    one throwaway `load()` (`module.load(...)` → `$('a')` → proto → proto) and patches
    `Symbol.iterator` + `splice` there — so every later `load()`, and one a provider captured
    before the patch ran, inherits it. Verified against the real bundle in a V8 worker:
    iteration works, an EMPTY set iterates (`ok:0`), and `.filter(fn)`/`.first()`/`.attr()` are
    unchanged.
  - **Vega providerContext completeness.** The Vega app hands providers
    `{axios, cheerio, Crypto, commonHeaders, getBaseUrl, openWebView, kvStore}`; ours lacked
    `getBaseUrl` (`undefined` → TypeError for any provider that destructures it). Added,
    answered from the repo's own `urls.json` (one cached fetch per engine — the same table the
    providers' own inline helper reads). `Crypto` (expo-crypto's digest API) was NOT added:
    nothing in the movies4u bundle uses it, and it would mean either bundling crypto-js into
    the vega engine (boot cost, the thing we are trying to reduce) or writing digests by hand.
    **Known limitation, still open: `openWebView` is a stub.** It answers `{success:false}`
    because this engine has no interactive WebView to hand a captcha to, while the Vega app
    opens a real one (its `WafWebViewDialog`) to earn a `cf_clearance` cookie. A title whose
    page the site only serves behind a challenge therefore still cannot be extracted here —
    that is the next real fix for this class of provider (the machinery to start from is
    `com/lagradost/cloudstream3/network/WebViewResolver.kt` and `net/CloudflareSolver`).
  - **Crash: the missing `MainActivity.Companion`.** `cloudstreamJarClean` drops the jar's
    `MainActivity$*.class` along with `MainActivity.class`, so any plugin touching
    `MainActivity.Companion.x` died with `NoSuchFieldError: No field Companion of type
    Lcom/lagradost/cloudstream3/MainActivity$Companion;` on the MAIN thread (the log's
    BingeCloud `BingeCloudPlugin.load$lambda$0$0` ← its `Settings.showSettingsDialog`).
    The shadow now carries a companion mirroring the jar's `MainActivity$Companion`,
    javap-verified member for member: `activityResultLauncher: ActivityResultLauncher<Intent>?`,
    `lastError: String?` (plus the jar's odd `setLastError(Context)` overload),
    `nextSearchQuery: String?`, `filesToDelete: MutableSet<String>`, `deleteFileOnExit(File)`,
    `centerView(View)`, `handleAppIntentUrl(FragmentActivity, String, Boolean = false,
    Bundle? = null): Boolean` — defaults ON PURPOSE, because the jar emits
    `handleAppIntentUrl$default` and a Kotlin declaration without defaults emits none — and the
    seven `Event<Boolean>` observers (`afterPluginsLoadedEvent`, `mainPluginsLoadedEvent`,
    `afterRepositoryLoadedEvent`, `bookmarksUpdatedEvent`, `reloadHomeEvent`,
    `reloadLibraryEvent`, `reloadAccountEvent`; the jar's `utils/Event` is public with a no-arg
    constructor). Hikari's own MainActivity additionally registers a real
    `registerForActivityResult(StartActivityForResult())` launcher right after
    `super.onCreate` and publishes it, so a plugin's file/folder picker opens instead of
    dereferencing null one line later.
  - **Vega series: the episode-loading state, and parallel seasons.** Vega catalog rows are
    `MediaType.UNKNOWN` (VegaProvider.toItems keeps the provider's own `type` only as
    `rawType`), and the real kind is only learned from meta.js — the slow half of opening the
    page — so during those seconds `DetailScreen`'s `isSeries` was false and the page drew NO
    episode area at all, which reads as "this show has no episodes". `DetailScreen` (~3080) now
    treats an UNKNOWN kind on a `vega|` provider, while the episodes are not loaded yet, as
    "show the episode area in its loading state" — the ordinary "Loading episodes…" row and
    spinner — collapsing to the truth once meta answers. Scoped to vega ids deliberately:
    other engines' rows already carry their kind. And `__vegaDetail` / `__vegaCallMany` in
    `vega/harness.js` now issue the per-season `getEpisodes` calls CONCURRENTLY (`Promise.all`,
    a failing season contributing null to its own slot) instead of one after another, so a
    multi-season list lands when the slowest season answers rather than after all of them in
    turn.
  - **Network streams: browser headers.** A network stream is a page's link, and these hosts
    hotlink-protect what they serve: the player's 403 (an `ExoPlaybackException
    [ERROR_CODE_IO_BAD_HTTP_STATUS] 403` on an `*.tnmr.org/hls2/...` playlist resolved out of a
    Luluvdo/LuluStream embed) is the CDN rejecting a request that carries no Referer /
    User-Agent. `NetworkStream.resolve` now runs every source through
    `withStreamHeaders(headers, url)`, which keeps whatever an extractor chose (the box hosts'
    own UA/Referer/Cookie) and otherwise adds `User-Agent: Http.UA` and
    `Referer: <origin of the pasted link>/`. That origin is the page the stream came from; when
    the stream address itself was pasted it is the stream's own origin, which is what such
    hosts check for. Bounded risk by design: the player already drops headers one step at a
    time when a server rejects them (`PlayerActivity.headerVariant`), so a wrong guess costs
    one retry while a missing one costs the whole play.

- **0.10.51** (versionCode 222) — the owner's sixth "fix some things" round; five
  asks (collapse duplicate titles across engines when "all providers" is
  selected; make the Vega detail screen fast; a genre search box on Home; the
  rounded corner slicing folder-tile names; IPTV network streams), built in one
  batch and pushed as **two** code commits (`8c99a043efc726832fe334886b8d2ad2a1fde2dc`,
  then the compile-fix `0ff8850fb2a7e30fd995fb94ce5f962317054772`; parent
  `7a60d7e082f7c366db16e1b75e79633c7cefd7be`; CI run **36273138708** —
  **success**; `continuous` republished 21:43:06Z, `build` branch = `build:
  update test APK 202609262142`; **NO new main release** — the newest real
  release is still v0.10.42), followed by this `src/README.md`-only commit,
  which the workflow's `paths-ignore: '**.md'` deliberately does **not** build
  (so the `continuous` APK is still the `0ff8850…` code). The FIRST push failed
  to compile in exactly two places: `NetworkStream.boxList`/`boxListPage` declared `jar: List<String>`
  but pass it on to `fetchText(jar: MutableList<String>)` (the caller's jar is a
  `LinkedList`), and `IptvProvider` called `NetworkStream.resolve(url = …)` when
  the parameter is named `rawUrl`. Both fixed; nothing else failed.
  - **Cross-engine duplicates.** NEW `data/HomeDedupe.kt` — `apply(rows, types)`
    walks the placed rows in placement order, and only for
    `ProviderType.NUVIO`/`STREMIO` rows, dropping an item whose key was already
    claimed and dropping a row left empty; a key is claimed only by an item
    `NsfwGate.allows(item)` permits (so a hidden item never suppresses a visible
    one). Keys: TMDB id (a bare numeric id or `tmdb:<id>[:kind]`), IMDb (`tt…`),
    or `t:<normalizeTitle(searchTitle)>|<year>|<movie|tv>` — the title key only
    when a year is known, so a yearless item can never claim one.
    `ContentRepository` builds `typeIndex(providers)`, funnels the two emission
    points in `homeRowsStreamingWhere` through `feedSnapshot(...)`, and
    `homeRows` through `translateRows(...)`; with no duplicate (or no
    Nuvio/Stremio row) it returns the input list untouched.
  - **Vega detail speed (one engine boot instead of two).**
    `assets/vega/harness.js` gained `pickModuleFunction(name, fn)`,
    `seasonRequests(info)`, `vegaArgs(args)` and `g.__vegaDetail(linkJson)`:
    it runs `getMeta`, pushes the Info to the host immediately via a new
    `__vegProgress('info', …)` bridge, then runs `getEpisodes` for the seasons
    and answers `{info, episodes:[…]}`. `VegaRuntime.createEngine`/`run` take an
    `onProgress` and `extraModules` (loaded after the main module, each load in
    a try/catch that rethrows `CancellationException`, so a broken companion
    file can't cost the main answer); detail runs on a `vega/v2/` cache key.
    `VegaProvider` gained a private `DetailJob(item)` — single-flight, bounded
    to `DETAIL_JOBS_MAX=8` with an access-ordered map and its own
    `Dispatchers.IO` scope — so opening a detail page boots ONE engine and the
    Info shows as soon as `getMeta` returns; `getEpisodes` reuses that job's
    episodes (falling back to the legacy per-season `callMany` only for a caller
    that never opened the page).
  - **Genre search.** `HomeScreen.HomeGenreStrip` filters the chips on both
    `g.name` and `I18n.t(g.name)` (case-insensitive) behind a
    `GlassSearchField` in the strip header, resets the chip list's scroll on
    each query, shows a "No genre matches …" line, and clears the query when a
    chip is picked.
  - **Folder-tile names.** `CollectionScreens.FolderTile` clipped its whole
    Column to `GlassShape`; the 26dp corner then sliced the last letters off a
    long folder name (the 10dp text inset sits inside the curve). The clip is
    gone and the background takes the shape instead (the border already did);
    the ripple is square by design. This was the only tile in the app that had
    a clip wrapping text — the other `.clip(GlassShape)` call sites clip images
    or colour only.
  - **IPTV network streams.** NEW `data/NetworkStream.kt`, plus an "Add network
    stream" mode in the IPTV add dialog (`extra = "netstream"` channel marker).
    Play resolves a stored link to playable sources instead of handing a file
    page to the player: direct media → Google-Drive normalisation → pixeldrain
    `/api/file/<id>?download` → the Terabox family (`/main` for `jsToken` +
    cookies → `/api/shorturlinfo` → `/share/list`, one level into up to 3
    folders, `dlink` as the source with the `terabox;…` UA + Referer, one
    fresh-cookie retry) → CloudStream's `FallbackResolver` (`resolve`, then
    `resolveEmbedUrl`) → a Range-request Content-Type sniff → the raw URL as a
    last resort. Box host list: terabox, 1024tera, 4funbox, momerybox, tibibox,
    nepnepbox, telebox, mirrobox, shibabox (covers the asked-for m3u8, Terabox,
    Telebox, MDisk and "Diskwala"-style page links; an installed extractor
    handles the rest). The whole resolve is budgeted at 45s (20s per stage),
    never throws and never answers empty for an http link; a stream channel's
    `channels()` short-circuits, so selecting one costs no network.

- **0.10.50** (versionCode 221) — the owner's fifth "fix some things" round; three
  asks (Extensions scroll freeze, Home provider-picker delay, manga catalogue
  needing the globe button) plus "add the anime genres to Home too, and any
  genre missing from either strip", built in one batch and pushed as **two**
  commits (`6f74ff018156067dc11e59ee982f55b4d09f0d94`, then the compile-fix
  `7a60d7e082f7c366db16e1b75e79633c7cefd7be`; parent
  `6a1bc244b6691651d4773762067b5d67fd8326fb`; CI run **36266806891** —
  **success**, 10 min; `continuous` republished 19:50:34Z, `build` branch =
  `build: update test APK 202609261950`; **NO new main release** — the newest
  real release is still v0.10.42). The FIRST push failed to compile in exactly
  one place: `UPDATE_QUIET_MS` was declared `private const val` **inside** the
  ViewModel class, and Kotlin only allows `const` at top level / in an
  object/companion — the house pattern beside it is `private val
  REFRESH_QUIET_MS = 500L`. Everything else in the batch compiled first try.

  - **Extensions scroll (the "full freeze").** Three things ran per draw, and
    compounded: `RepoProvenance.nameMap` scanned the whole repo list per
    provider *and re-parsed each repo URL inside that scan* (now
    `RepoProvenance.Index` — one pass over repos, O(1) lookups; `nameMap` is
    O(P+R)); `checkUpdates()` (sha256 of every installed extension file) and
    `adoptAdultFlags()` ran on EVERY repo-listing arrival, and listings arrive
    one repo at a time (now `requestUpdateCheck()` → `updateTicks`
    `collectLatest` + 600ms quiet); every row re-probed its icon on re-entry
    (now `ExtensionIcons.missAt`, 5-min TTL, + a 320ms `ICON_SETTLE_MS` delay in
    `ProviderIcon`/`RepoPluginIcon` so a fling never probes); `rememberCs3SettingsIds`
    now probes cache-only `ContentProvider.settingsReady` (it used to call
    `settingsAvailable`, which DEX-loads the plugin) and is keyed on the id SET;
    `ProviderPacks.rows` is `remember`ed above the lazy list in both
    `InstalledExtensionsView` and `SourcesOverviewView`; `SourceUrls.canonical`/
    `matchKeys` are memoised (bounded LinkedHashMaps). Note `LazyListScope`
    functions cannot use `remember` — `extensionsSearchItems` (line ~4419) still
    groups its rows inline, deliberately.
  - **Home provider picker delay.** `ProviderPickerSheet` took a new trailing
    `repoNameByProvider: Map<String,String>? = null`; Home hoists
    `RepoProvenance.nameMap(providers.map{it.config}, repos)` while idle and
    passes it (the sheet calls `remember` unconditionally — `computedRepoNames`
    is empty when the map was provided — because a conditional `remember` would
    change the composition structure between its two callers). Search still
    passes nothing and computes locally.
  - **Genre vocabulary.** NEW `data/Genres.kt` is the ONE list: `ALL` =
    `TmdbGenres.MOVIE` ∪ `TmdbGenres.TV` ∪ 99 `ANIME_TAGS` (sorted);
    `tmdbGenreFilter(name)` = `with_genres` OR-list across BOTH namespaces
    (`TV_FOR_MOVIE`/`MOVIE_FOR_TV` cover the differently-spelled names);
    `keywordCandidates(name)` = TMDB keyword names for a tag TMDB has no genre
    id for. `SearchScreen.SEARCH_GENRES = Genres.ALL` (its private
    `ANIME_GENRES`/`SEARCH_GENRES` block is deleted); `HomeScreen.HomeGenreStrip`
    is rebuilt from it (`HomeGenre(name, genresText, keywordsText)`; ids →
    `genresText`, else keywords joined with `|` → `keywordsText`).
    `TmdbSources.discover` now RESOLVES keyword NAMES via `/search/keyword`
    (`keywordsAsIds`/`resolveKeywordId`, `keywordIds` + `keywordMisses` caches)
    and **returns emptyList() when an asked-for keyword resolves to nothing** —
    dropping the parameter would answer with the whole popular catalogue and
    read like a filter that had been applied.
  - **Verified against the LIVE TMDB API** (do this again if the tag list is
    touched): 97 of 99 tags resolve to a keyword and every one answers a
    non-empty `/discover` grid in at least one namespace. TMDB has **no** guild
    keyword → "Guilds" falls back to **adventurer** (175428; 136 films/62
    series); TMDB spells it **"cross dressing"** (12090; 187/138) → that
    candidate was added. `with_genres=28|10759` answers Action films on
    `/discover/movie` (20001) AND Action&Adventure series on `/discover/tv`
    (10199), and a bogus id (999999) answers **0**, so the combined OR-list is
    safe. Surprise worth knowing: TV series carry MOVIE-namespace genre ids —
    `with_genres=36` on `/discover/tv` answers 42 History series even though
    `/genre/tv/list` never lists 36.
  - **Manga walls.** `MangaProvider` gained `siteUrl()` (via
    `MangaExtensionManager.siteUrlOf`, cached, blocking — fine inside `gate`),
    `warmSite()` (→ `CloudflareSolver.warm`, NOT gated on
    `needsVerification`, 90s per-host cooldown, records `solvedAt` +
    `clearBlocked` + `CookieManager.flush()`) and `noteWall()` (the same
    `WALL_MESSAGE` regex `AniyomiProvider` uses → `CloudflareVerifier.markBlocked`).
    `getCatalog` warms + retries when page 1 is empty **or** threw; `search`
    warms only on a THROWN failure (an empty search is a legitimate answer);
    `getEpisodes` warms when the list is null **or** empty. The retry's own
    reason is what gets reported (the first failure is usually the wall the
    warm-up existed to clear). Root cause of the reported "no catalogue until I
    open the globe once": the site wanted a real browser session and the app
    never tried to give it one — the globe worked because that WebView earned
    the site's cookies, which `AndroidCookieJar` then fed to the extension's
    OkHttp client.

- **0.10.49** (versionCode 220) — the owner's fourth "fix some things" round;
  seven asks, built in one batch and pushed to `main` as **two** commits
  (`1e8ec82932e08095bf7bc45e0bfb2fb9d500a2f4`, then the compile-fix
  `6a1bc244b6691651d4773762067b5d67fd8326fb`; parent `ff55b179026ce7895ad8404d61b78806253a74b7`;
  CI run **36260261345** — **success**; `continuous` pre-release republished
  18:02Z, `build` branch = `build: update test APK 202609261802`; **NO new main
  release** — the newest real release is still v0.10.42). The FIRST push failed
  to compile in exactly three places, all in this batch's new code: `obj` out of
  scope at the tail of `addRepoUrl` (hoist the JSONObject — a Vega manifest has
  none), `Http.fetchBytesCancellable` called from a non-suspend
  `loadAniyomiIndex` (it is `suspend` now), and a `by remember` property that
  cannot be smart-cast in HomeScreen (`val selectedKey = selected ?: ""`). Read the
  run's **`build-log` artifact** for compiler errors — the job summary carries
  none. Download it from `/actions/artifacts/{id}/zip` (the Authorization header
  survives the 302) and unzip with `@zip.js/zip.js`; the log is `gradle-build.log`.
  26 files (7 new):
  `CHANGELOG.md`, `README.md`, `app/build.gradle.kts`,
  `app/src/main/assets/vega/{harness.js,commonHeaders.js}`,
  `app/src/main/java/com/hikari/app/data/{Models,SourceUrls,ContentRepository,TmdbSources,AppStore,RepoProvenance}.kt`,
  `app/src/main/java/com/hikari/app/HikariApp.kt`,
  `app/src/main/java/com/hikari/app/providers/ProviderManager.kt`,
  `app/src/main/java/com/hikari/app/providers/vega/{VegaRuntime,VegaProvider,VegaPluginManager}.kt`,
  `app/src/main/java/com/hikari/app/ui/{ExtensionIcons,ProviderPacks}.kt`,
  `app/src/main/java/com/hikari/app/ui/screens/{CatalogScreen,CollectionScreens,DetailScreen,ExtensionsScreen,HomeScreen}.kt`,
  `app/src/main/java/com/hikari/app/aniyomi/AniyomiExtensionManager.kt`,
  `docs/{HIKARI_EXTENSIONS,VEGA}.md`; deleted
  `app/src/main/java/com/hikari/ext/providers/YtsProvider.kt` (as `sha: null`).
  Notes worth keeping:
  1. **Vega** (ask 2) — `Zenda-Cross/vega-providers`'s `manifest.json` is a BARE
     JSON ARRAY of `{display_name, value, version, icon, type, disabled,
     hasSettings}`; provider code is `dist/<value>/{catalog,posts,meta,stream,
     episodes,settings}.js`, each a **self-contained bundle** (no cross-file
     `require` anywhere in the 259 published files) ⇒ **one module per call**.
     `VegaRuntime` boots boot.js → cheerio (optional) → nuvio/harness.js → a
     per-call register script → `assets/vega/harness.js` (providerContext: a
     faithful axios over the async OkHttp bridge, cheerio, the repo's
     `headers.js` as `commonHeaders`, kvStore persisted to
     `<providerDir>/kv.json`, an honest `openWebView` returning
     `{success:false}`), then loads ONE file wrapped in a CommonJS function and
     bytecode-caches the wrapper. Provider files destructure named keys, so
     passing a superset args object (`{filter,page}` + `providerValue` +
     `providerContext`) is safe. `.data` must be JSON-parsed when the body is
     JSON and left as a string when it is HTML (the harness's axios
     transformResponse does exactly that). Repo kind, provider type, the
     Extensions screen (Sources row, folder, add dialog, short names
     `vega`/`vegarepo`/`vegarepos`/`vegaproviders`/`zendacross`), ProviderManager, ContentRepository's
     error maps and cross-search filters, ProviderPacks, ExtensionIcons,
     RepoProvenance and HikariApp's seeding are all wired. `RepoProvenance`
     matches a Vega provider through the `githubRoot` fallback: the install
     remembers `…/main/dist/AniKoto` and the repo list `…/main/manifest.json`,
     and `SourceUrls.repoKey` does NOT collapse those.
  2. **Aniyomi `.pb`** (ask 3) — protobuf `index.pb` is gzipped (`1f 8b`), so
     `AniyomiExtensionManager.pbIndex` gunzips then hand-parses the varint tag
     stream (schema checked field-by-field against Mihon's
     `NetworkExtensionStore.kt` and the real keiyoushi file: 1377 entries,
     store "Keiyoushi", 365 NSFW, 0 empty apk URLs). `indexCandidatesFor` now
     appends `PB_INDEX_FILE_NAMES` and `isIndexUrl`/`indexDirFor` use
     `ALL_INDEX_FILE_NAMES`; a `.pb` candidate takes
     `Http.fetchBytesCancellable(..., readTimeoutSec = 60)`, which is why
     `loadAniyomiIndex` had to become `suspend`.
  3. **Nuvio-style catalogue** (ask 4) — `CatalogScreen` now mirrors Nuvio's
     `CatalogScreen.kt`: a header row (back + globe) then a title block
     (`headlineMedium` + engine as subtitle), `GridCells.Fixed(columns)` with
     `catalogColumnsFor(widthDp)` (3/4/5/6/7) passed through
     `TvUi.gridColumns(...)` so a TV still sizes by living-room cell width,
     16dp gutters / 12dp / 18dp, a tile that honours `PosterStyle.corner` and
     `showTitles`, a year+genre second line, and a pulsing skeleton grid while
     the first page loads (the Cloudflare nudge floats over it).
  4. **Home genre strip** (ask 5) — `HomeGenreStrip` under the hero, using
     `TmdbGenres.MOVIE` names and `HOME_GENRE_TV_IDS` so each chip's filter is
     `"<movieId>|<tvId>"` (TMDB keeps the two genre id spaces SEPARATE — film 28
     = series 10759 — and ignores the id from the other namespace rather than
     erroring). It opens `Routes.tmdbGridSpec(TmdbSpec(DISCOVER, media="all",
     genresText=filter, …))`; `TmdbSources.page()`'s DISCOVER branch merges
     movie+tv when `spec.isAll`. Verified against the live API with the app key.
  5. **Repo provenance** (ask 6) — `data/RepoProvenance.kt` + the
     `LocalRepoNameByProvider` CompositionLocal wrapped around ExtensionsScreen's
     whole `when {}`, plus `PluginRow(repoName=…)` from both
     `RepoPluginsView` call sites, the Home picker sheet and
     `ExtensionPickerSheet`.
  6. **In-place Home search** (ask 7) — `HomeSearchOverlay` over Home, scoped to
     the provider Home is loading its catalogue from
     (`repo.searchStreaming(q, providerIds = setOf(id))`), 400ms debounce,
     `BackHandler`, `MediaItem.shrinkPoster()`, `engineFailureReason()` detail.
  7. **YTS removal** (ask 1) — `YtsProvider.kt` deleted, `AppStore` gained
     `ytsCleaned()`/`markYtsCleaned()`, `HikariApp` sweeps `hiki|yts` once.

- **0.10.48** (versionCode 219) — the owner's third "fix some things" round; five
  asks, built in one batch and pushed to `main` (commit
  `ff55b179026ce7895ad8404d61b78806253a74b7`, parent `c5aa72b`; CI run
  **36252396826** — **success**; `continuous` pre-release republished 15:46Z and
  the `build` branch updated to `build: update test APK 202609261546`; NO new main
  release — the newest real release is still v0.10.42). 13 files pushed:
  `app/build.gradle.kts`, `CHANGELOG.md`, `docs/MANGA.md`, `MangaProvider.kt`,
  `SearchScreen.kt`, `Components.kt`, `ChoicePickers.kt`, `AppStore.kt`,
  `HikariApp.kt`, `NuvioPluginManager.kt`, `ExtensionsScreen.kt`,
  `PlayerActivity.kt`, `SettingsScreen.kt`. Notes worth keeping:
  1. **Manga chapters / HTTP 404** (`manga/MangaProvider.kt`) — the call, not the
     source. `getMeta`/`getEpisodes` used `src.getMangaDetails` /
     `src.getChapterList` (extensions-lib **1.5** API). A keiyoushi
     `KeiSource` (lib **1.6**, e.g. Comix = comix.to v1.6.40) does not implement
     those; it overrides `getMangaUpdate(manga, chapters, fetchDetails,
     fetchChapters)`, and the base class's `chapterListRequest` builds
     `baseUrl + manga.url` = `https://comix.to/12345-slug` → **404** (the real
     page is `/title/12345-slug`); Comix's chapter list is a cipher-SIGNED API
     call only producible inside its own `getMangaUpdate`. New
     `MangaProvider.updateOf(src, item, details, chapters) =
     src.getMangaUpdate(sm(item), emptyList(), details, chapters)` — `getMeta`
     uses `(true, false)`, `getEpisodes` `(false, true)`. Hikari's vendored
     `eu.kanade.tachiyomi.source.MangaSource.getMangaUpdate` has a default body
     bridging to the old pair, so legacy sources are unchanged. This is
     Nekoread's `TachiyomiHttpSourceAdapter` wiring and the same shape as the
     ANIYOMI half (`getAnimeEpisodeUpdate`). `getPageList` untouched. Written up
     in `docs/MANGA.md` §"Which extension call answers details and chapters".
  2. **Search genres** (`ui/screens/SearchScreen.kt`) — `ANIME_GENRES` declared
     BEFORE `SEARCH_GENRES` (top-level init order — a later-declared list would
     read null and NPE on class load) and unioned into it. Must-adds: Isekai,
     School Life, plus the rest of the MAL/AniList tag set. TMDB names (Romance,
     Action, …) are deliberately NOT repeated.
  3. **TV: any picker's first D-pad press ran "close"** (`GlassDialog` in
     `ui/components/Components.kt`). The scrim and the tap-swallowing Column are
     both `clickable`, and a `clickable` is a FOCUS TARGET — so on a TV the
     cursor landed on the full-screen scrim first and centre press ran
     `onDismiss()`. Exactly the "I cannot change the app language with the
     remote" report. Both containers now wear
     `.then(if (tv) Modifier.focusProperties { canFocus = false } else Modifier)`
     BEFORE their `.clickable` (touch untouched), and `GlassDialog` gained an
     `initialFocus: FocusRequester?` that a `LaunchedEffect` retries for up to
     30 frames via `withFrameNanos` (a Dialog's window is not attached on the
     frame the composable first runs, and `requestFocus()` throws until it is).
     `ChoiceDialog` + `MultiChoiceDialog` pass their first row's requester
     (`focusRequester` must PRECEDE the `.clickable`/focus target).
  4. **TV video stutter → the video-enhance preset** (`player/EnhancePreset.kt`
     is the evidence: a non-empty effect list is a GL pass on EVERY decoded
     frame; `NATURAL` is the default and applies nothing). `AppStore` gained
     `playerEnhanceChosen`, `HikariApp.syncTvEnhance(store)` mirrors
     `syncTvPerformance` (a TV that has not chosen a preset is put back on
     Natural, once) and is called at BOTH `syncTvPerformance` call sites;
     `PLAYER_ENHANCE`, `PLAYER_ENHANCE_CHOSEN` and `PLAYER_ENHANCE_UNSUPPORTED`
     joined `DeviceLocal.KEYS` (the last one is a CAPABILITY — a phone whose GL
     stack refused the pipeline must not tell a TV it cannot run one).
     `setEnhanceChosen(true)` is written wherever the USER picks a preset:
     `PlayerActivity.setEnhancePreset` (both the re-open path and the tail) and
     `SettingsScreen` → Player → Video enhance. NOTE: the player has no
     continuous animation during playback (the loading banner's four infinite
     animators are cancelled by `hideLoadingBanner`), no blur/RenderEffect, and
     the pollers are 700ms/1s/2s — so the enhance pass is the only per-frame
     cost the app adds to the picture.
  5. **Pre-installed providers removed, repos kept**
     (`nuvio/NuvioPluginManager.kt`). Deleted `SEED_PROVIDERS` + the pre-install
     block inside `seedDefaults` (the repo-adding loop stays). New
     `FORMERLY_SEEDED = {vixsrc.js, moviebox.js, showbox.js, dahmermovies.js}`
     and `removeFormerlySeededProviders(context)`, called right after
     `seedDefaults` in `HikariApp`; one-shot via the new
     `AppStore.nuvioSeedCleaned`/`markNuvioSeedCleaned`. Matched on the FILE, not
     the name — `SourceUrls.fileKey(extra).startsWith("tapframe/nuvio-providers/")`
     — because MovieBox also ships in the Hindi/All-in-One bundles the app now
     seeds, and a copy the user installed themselves must survive. (All four
     were confirmed pre-installed: the owner's Extensions screenshot lists
     exactly 8 extensions, of which the Nuvio ones are moviebox, vixsrc,
     DahmerMovies, ShowBox.)
  6. **Two more bundled Nuvio repos** — added to BOTH places:
     `NuvioPluginManager.DEFAULT_REPOS` (the first-run seed) and
     `ExtensionsScreen.REPO_ALIASES`'s `everyNuvio` (what typing `nuvio` adds),
     plus their own short names `animenuvio`/`allinoneanime` and
     `hindinuvio`/`hindi`. URLs:
     `https://raw.githubusercontent.com/D3adlyRocket/Anime-Nuvio/refs/heads/main/manifest.json`
     (name "All-in-One-Anime", 9 scrapers) and
     `.../D3adlyRocket/Hindi-Nuvio/refs/heads/main/manifest.json` (name
     "Hindi-Nuvio", 14 scrapers). Both fetched 200 OK this session.
  - TV work here is reasoning + compile-verified only — nothing in this
    environment can drive a D-pad.

- **0.10.47** (versionCode 218) — the owner's second "fix some things" round; four
  asks, built in one batch and pushed (CI green on runs 36244630235→fixed
  36245044856 and 36246012604 for the docs; `continuous` republished 13:39Z, no
  main release). Notes worth keeping:
  1. **Telegram: the three sections swipe** (`ui/screens/TelegramScreen.kt`).
     `TelegramHome`'s `when (section)` became a `HorizontalPager` over
     `listOf(TgSection.CHATS, ADDED, LINKS)`, with the My Stuff pattern
     (`ui/screens/MyStuffScreen.kt`): two `LaunchedEffect`s keep the pill and the
     pager in step in both directions (each checks before it writes), a pill tap
     also animates, and `section` stays hoisted in `TelegramScreen` (so "add
     lands on the section you added to" and the `BackHandler` still work).
  2. **Settings: back from a SUB-folder no longer jumps to the top** — the fix is
     now universal across the three page levels. Root cause of the remaining case:
     the reset lived in `LaunchedEffect(openFolder, openSub) { pageState.scrollToItem(0) }`,
     and an effect cannot tell "the page was OPENED" from "we came BACK to it", so
     the page returned to was scrolled to its top (report: App Layout → Poster
     styling → back → top of App Layout). Now: `indexState` + `folderState` +
     `subState`, the reset moved INTO the two functions that open a page
     (`openFolderPage` / `openSubPage`) as `requestScrollToItem(0)`, and there is
     no effect at all. `LazyListState.requestScrollToItem` is public in foundation
     1.7.6 (verified in `foundation-android-1.7.6-sources.jar`) and primes the
     position for the next measure — so the page's first frame is already at its
     top, no flash. (`focusRestorer` does NOT exist in 1.7.6 — checked; do not
     plan a fix around it.)
  3. **Search tab: Anime + a genre strip + recent searches**
     (`ui/screens/SearchScreen.kt`, `data/AppStore.kt`, `docs/SEARCH.md`).
     - `SearchKindFilter.ANIME` + `MediaItem.looksAnime` (the addon's `anime`
       rawType, an "Anime" tag, or TMDB's "Animation").
     - `SEARCH_GENRES` (TMDB film ∪ TV names, sorted) in a year-strip-shaped
       scrollable multi-select; `passesSearchFilter`/`unknownKindKept` gained a
       `genres` parameter and `hiddenNoGenre` reports "N have no genre, so they
       are hidden" (strict, like the year strip; the kind filter stays lenient).
     - Recent searches: `AppStore` key `SEARCH_HISTORY` + `searchHistoryFlow` /
       `addSearchHistory` (dedupe case-insensitively, newest first, cap
       `SEARCH_HISTORY_MAX` = 20, ignores < 2 chars) / `removeSearchHistory` /
       `clearSearchHistory`; recorded from the **debounced** query in the view
       model's `init`; chips with their own ✕ (a separate clickable, so forgetting
       one cannot also run it) + "Clear all", shown only while the box is empty.
     - Two compile lessons from the first push: `app` in `SearchViewModel` is a
       plain CONSTRUCTOR PARAMETER — in scope in property initializers/`init` but
       NOT in member functions (added `private val hikari = app as HikariApp`); and
       adding a parameter to `passesSearchFilter` means finding EVERY call site
       (one filter in the collection-hits row was missed). CI caught both.
  4. **TV: the Extensions tab's rows are focus targets with the ring**
     (`ui/screens/ExtensionsScreen.kt`). Root cause of "I press the button on the
     remote and nothing shows which option I'm on": those rows are plain `Row`s,
     and a plain `Row` has no focus node — the D-pad could only ever land on the
     small button at the end of the line. `PluginRow` (press = install / update /
     uninstall, matching its own trailing button), `ProviderCard` (press = the
     switch) and `SiteRow` (press = open) now wear `Modifier.tvPress`.
     - **`tvPress` gained `previewPass`** (`tv/TvKeys.kt`), and this is the
       durable trap: the preview pass runs ROOT-first, so a row-level preview
       handler swallows a press aimed at the row's own button (pressing Uninstall
       would run the row's action). A row that CONTAINS controls must use
       `previewPass = false` (the normal pass, leaf-first). Verified in the
       foundation sources that `clickable` handles Enter/DirectionCenter in
       `onKeyEvent` (the normal pass) — so leaf-first is exactly right.
       `HomeScreen`'s PickerRow and `MangaScreen`'s engine row were switched to
       `previewPass = false` too: both contain their own clickables (the pin and
       pack caret; the Popular/Latest chips).
     - As always for TV work: this is compile-verified only — nothing here can
       drive a D-pad, so the behaviour rests on the reasoning above.

- **0.10.46** (versionCode 217) — the owner's "do some fixes" round, seven asks,
  all built in one commit and pushed to `main` (CI green, run 36241494969;
  `continuous` republished). The notes worth keeping:
  1. **Telegram's "Video links" pill was off-screen** — the section strip was laid
     out with the 16dp edge inset as *layout* padding (32dp narrower than the
     screen), so with the counts on the first two pills the third sat entirely
     past the right edge. Fixed in `ui/screens/TelegramScreen.kt`: the strip's
     `Modifier` is `fillMaxWidth()` with the inset moved to `contentPadding`, and
     `TgSectionPill` is compact (11/7dp padding, 15dp icon, `labelMedium`), so
     ~57dp of the third pill peeks at rest.
  2. **Adding lands on the section you added to** — `section` was state *inside*
     `TelegramHome`; it is hoisted into `TelegramScreen` (rememberSaveable) so
     `addChannel()` can set `TgSection.ADDED` and `addLink()` `TgSection.LINKS`.
  3. **Back out of a Telegram chat/channel/section left the tab** — nothing in the
     tab consumed back, so the press fell through to the NavHost (tabs are routes:
     `ui/navigation/AppNav.kt`, `composable(Routes.TELEGRAM)`). A `BackHandler` in
     `TelegramScreen` now steps out: chat → channel → section → tab.
  4. **Back out of a Settings folder threw the index to the top** — one
     `rememberLazyListState()` served both the index and the open folder, and
     `LaunchedEffect(openFolder, openSub) { listState.scrollToItem(0) }` ran on
     the way back too. Now `indexState` + `pageState` (the folder resets to its own
     top; the index is never touched).
  5. **The Profiles explanation ignored the hide-explanations switch** — it was
     drawn outside the `LocalHideHelp` scope; the read is now inside the
     `item { }` (a `LazyListScope` lambda is NOT composable — reading a
     composition local there is a compile error).
  6. **The remote could not select a provider on Home.** Root cause (this is the
     durable lesson): `PickerRow` in `ui/screens/HomeScreen.kt` handles taps with
     `Modifier.pointerInput(label, multi) { holdOrTap(onLongClick, onClick) }`, and
     **a `pointerInput` is invisible to Compose's focus system** — no focus target
     is created at all, so the D-pad walks past the row and centre does nothing.
     New **`Modifier.tvPress`** in `tv/TvKeys.kt` (the fourth primitive there,
     documented in the file header) adds the focus target + a centre-press
     handler *alongside* the gesture (touch is untouched); applied to `PickerRow`
     and to the manga engine rows in `ui/screens/MangaScreen.kt`. Also closed the
     remaining remote gaps found by auditing every `Slider(`/`Switch(`/`Checkbox(`/
     `RadioButton(`/`pointerInput` in the app: the reader's auto-scroll-speed
     slider and page scrubber got `tvAdjust`, the two "Playback start" radio rows
     in Settings became whole-row `clickable` + `tvToggle` (the radio alone is a
     small target the focus search skips), and the search box's translate button
     (a bare `detectTapGestures` Box in `ui/screens/SearchScreen.kt`) got
     `tvPress`. `tvToggle`/`tvAdjust`/`tvPress` are now applied at every Slider
     and Switch in the app; no Slider is without one.
  7. **A subtitle-language bar in the player's "Load from internet" panel** (the
     "only selected language subs show" ask). Built in `player/PlayerActivity.kt`
     `showSubtitleSearchDialog()`: a `HorizontalScrollView` of focusable pill
     chips (All / the user's own tag / en, hi, ar, fr, es, pt, id, bn, ta, te, ml,
     ur, ru, zh, ja, ko), inserted at index 0 of the panel's content. Finding that
     decided the design: **no subtitle site narrows by `locale`** (grep:
     `SubtitleQuery.locale` is only ever *set* by the caller and read by nobody),
     so the narrowing is CLIENT-SIDE in `render()` — `keep(lang)` compares through
     `SubtitleLang.of(lang).code` (so "Arabic" and "ara" match), the per-source
     counts are recomputed for the filtered list, and picking a language re-filters
     the tracks already on screen (`lastQuery`/`searchDone`) instead of searching
     again. The pick persists in the player's `subsPrefs` ("sub_lang_filter") and
     is also sent as the query's `locale` so sites that order by language put it
     first. The bar is built *after* `render` (a local function cannot be
     referenced before its declaration) and placed with `contentView.addView(bar,
     0, …)`.
  8. The "Hold any provider for half a second…" line is now drawn regardless of
     the hide-explanations switch (it is the only place the gesture is written
     down). `HomeScreen.kt`'s `providers-hold-hint` item lost its
     `if (!LocalHideHelp.current)` guard.
  - **CI lesson this round:** the job summary in `GET /actions/jobs/{id}` does not
    contain compiler errors — download the run's **`build-log` artifact**
    (`GET /actions/artifacts/{id}/zip` with the token, unzip with
    `@zip.js/zip.js`, read `gradle-build.log`, grep `^e: `). The one error this
    round: **this Compose (BOM 2025.01.01 / foundation 1.7.6) has no
    `Modifier.focusable(enabled, source, indication)`** — verified against the real
    `foundation-android-1.7.6-sources.jar` — so the ring must be applied the way
    `Clickable.kt` does it: `Modifier.indication(source, LocalIndication.current)`
    then `.focusable(enabled, source)`.

- **0.10.45** (versionCode 216) — the owner's two messages this round. The first
  was a batch of four reports/fixes, the second added a feature on top:
  1. **"Could not save the file" in Settings → Logs & diagnostics — on every log,
     every time.** Root cause read out of the code, not guessed: the save inserted
     into `MediaStore.Downloads` with DISPLAY_NAME + MIME_TYPE and nothing else,
     and Android 11+ refuses exactly that (an insert with no
     `RELATIVE_PATH` resolves to the root of shared storage, which an app cannot
     write). The app's own download exporter (`download/DownloadEngine.kt`) had
     always done it correctly (`RELATIVE_PATH = Download/Hikari` + `IS_PENDING`),
     so the fix extracts ONE shared saver — new **`data/DownloadsSaver.kt`**
     (`save`/`saveBytes`/`pathOf`), used by both `LogsScreen.saveLog` and
     `BackupManager.saveToDownloads` — with a legacy public-dir fallback and a
     failure REASON so the toast says why instead of nothing.
  2. **A Telegram group full of videos said "This channel has no videos on its
     public page".** Proved against the real page (`scratch/tg-young2.html` is
     gone with the session; the finding is in the code): `t.me/s/izero5g` is a
     public GROUP, whose page is a 12 KB landing page with ZERO
     `tgme_widget_message` blocks — Telegram publishes a feed for public
     *channels* and for nothing else — while the old `parsePage` returned an empty
     video list and the tab claimed the chat had none. `TelegramPage` gained
     `posts` (`hasFeed`), the account path became PRIMARY when signed in
     (`Td.publicChat` → `walkChatVideos`, 4 pages × 60 up front), and the three
     empty states are now honest (no preview / files withheld / genuinely none).
  3. **Signed in, only 100 of your chats were listed.** `Td.loadChats` asked for
     one `LoadChats` page; it now pages (`loadChatPage`, cap 2000) until TDLib
     says there is nothing more.
  4. **The provider picker's multi-select was undiscoverable.** A hint under the
     "Providers" heading in `ProviderPickerSheet` (HomeScreen): "Hold any provider
     for half a second to select more than one."
  5. **The Telegram tab was restructured into sections.** `TelegramHome` is now a
     fixed header (title + an always-visible **+**) over a horizontally
     scrollable pill strip — "My chats · N", "Added channels · N" and (new, see
     below) "Video links · N" — with the account card + chat search in the
     My-chats section. `TgSection`, `TelegramSectionStrip`, `TgSectionPill`,
     `TelegramMyChats`, `TelegramAddedChannels`.
  6. **The owner's second message: "when click plus in telegram section give
     option to add telegram video link to stream … pasting video link also add in
     folder videos link, and can stream by clicking any added video link too."**
     The + now opens a chooser (new `AddOptionRow`): **Public channel** (the old
     flow) or **Video link**. Links live in a new **`telegram/TelegramLinks.kt`**
     (`Link(url, channel, messageId, title, poster)`; `normalize` accepts
     `t.me/<name>/<id>`, `?single`/`?comment`, `t.me/c/<internal>/<id>` and
     `tg://resolve`; `describe` fills the title/still; `decode`/`encode`/`add`/
     `remove`) stored under a new key `K.TELEGRAM_LINKS` / `telegramLinksFlow` /
     `setTelegramLinks`, rendered by a new `TelegramLinksSection` +
     `TelegramLinkRow`. Playing a row resolves the LINK at tap time — the account
     first via new **`Td.linkVideo(url)`** (`TdApi.GetMessageLinkInfo`, then
     `videoOf`, then a short forward walk for an album caption), the post's own
     embed page second via new **`TelegramWeb.loadPost`/`parsePost`/
     `TelegramPost`** (`t.me/<name>/<id>?embed=1`) — so a saved row outlives the
     short-lived file reference Telegram returned when it was added. Evidence
     gathered with `fetch_url` before coding: `t.me/telegram/441?embed=1` carries
     one `tgme_widget_message` with a real `<video src>`, while
     `t.me/Youngupdatesource/1444?embed=1` (a withheld 855 MB upload) carries the
     `message_media_not_supported` block and the caption but no source — which is
     why a withheld post gives the "sign in to play" toast rather than a silent
     no-op.
  - CHANGELOG section (### Fixed + ### Added) prepended; `versionName` 0.10.45.
  - **CI:** the first push (`21236949`) FAILED to compile with exactly ONE error,
    twice (debug + release): `TelegramWeb.kt:267:30 Type annotation class
    'org.jspecify.annotations.Nullable' of the inferred type is inaccessible`.
    Cause: **jsoup 1.22 annotates its API with the JSpecify `@Nullable`
    TYPE-USE annotation** (`Element.selectFirst` → `@Nullable Element`), the
    project has no `org.jspecify` on the compile classpath, and a generic
    inference (`let`/`ifBlank`) that has to name one of those inferred types is a
    hard ERROR, not a warning. Fixed in `86d3119` by giving every jsoup-derived
    local an EXPLICIT type and avoiding `let`/`ifBlank` over jsoup returns (the
    shape the pre-existing `parsePage`/`titleOf` already used). **A reusable trap
    for a future session: never write `val x = someJsoupCall()` AND then pass `x`
    through a generic function whose type parameter is inferred from it — declare
    the type first.** CI run `36237448940` is green; the `continuous` pre-release
    was re-published (three APKs, 11:14Z) and the `build` branch moved to
    `637f1da0`. Latest non-prerelease release is still **`v0.10.42`** — no main
    release from this round.

- **0.10.44** (versionCode 215) — the owner's message: *"do those 2 things and this too"* — (a)
  Profiles, (b) a switch that hides the explanation lines, (c) the Library sheet's
  ticks not responding, (d) a Telegram public channel reporting "no video
  available" although Telegram plays it, (e) a crash log. All in one push,
  continuous build only — **no main release**. Four features/fixes plus one
  data-format repair, so the details below are the reference for each.
  - **(a) Profiles: `data/Profiles.kt` + `ui/screens/ProfilesScreen.kt` + entry on
    the Settings index.** A profile is a SNAPSHOT of the whole preferences store
    (minus `DeviceLocal`) in `filesDir/profiles/<id>.json`, plus a registry
    (`profiles/registry.json`) holding the list and the active id. Switching =
    `AppStore.replacePreferences(records)`, which CLEARS every key except
    `DeviceLocal` and applies the target's — the mechanism that already existed
    for backup/restore is `restorePreferences` (which deliberately does NOT clear:
    right for a file, wrong for a profile). `BackupManager.refreshLiveState` was
    made public and is reused so the once-at-startup settings (language, ad-block
    selectors, WebView UA, DNS, extension instances) are re-read after a switch.
    The download queue (`DownloadStore.raw`/`writeRaw`, new) is snapshotted too.
    Design decisions worth keeping: the first profile is the setup the user
    already has (`adopt`, offered when the picker is first opened — otherwise
    creating a profile would look like it wiped their app); `createEmpty` writes
    the registry with the OLD active id and lets `switchTo` do the save (a bug
    caught in review: marking the new profile active first made the save a
    no-op); `AppStore.K.PROFILE_TAG` is the store-side marker that makes "the
    store and the registry agree" checkable, so a cleared store or a restored
    backup re-applies the active profile instead of showing an empty app under a
    profile's name; extension FILES and downloads stay shared, so
    `ExtensionsScreen.remove` now also asks `Profiles.otherProfilesReference`
    before deleting a plugin file (uninstalling in one profile used to take a
    file another profile listed with it). Verified by reading the engines:
    `AniyomiProviderSync`/`Cs3ProviderSync` only REPAIR existing rows
    (`if (mine.isEmpty()) return false`), so an empty providers list really does
    read as "nothing installed" — a new profile is a fresh install.
  - **(b) Hide-explanations: `ui/components/HelpText.kt` (`LocalHideHelp` +
    `helpShown()`), provided in `MainActivity` from `AppStore.hideHelpFlow()`, plus
    the switch card (`HideHelpCard`) in Settings → App Layout.** The captions are
    drawn by a dozen rows/cards/dialogs, so there is no single component to
    change: the local is read where each line is drawn. Sites guarded: the
    `supporting` parameter family (`SettingsToggle`, `MyStuffSectionRow`,
    `SettingsSection.summary`, `SettingsFolderRow.subtitle`,
    `SettingsPageHeader.subtitle`, `CollectionScreens.PickerLine`,
    `ExtensionsScreen.ProviderCard`), and 60-odd inline "explanation paragraph"
    `Text`s wrapped in `if (!LocalHideHelp.current) { … }` — found mechanically by
    the rule *labelSmall/bodySmall + onSurfaceVariant + a literal ≥ 60 chars*,
    then hand-filtered to EXCLUDE status/empty-state/error lines (e.g. "Nothing on
    TMDB matched", "Waiting for the other device…", the Clear-data warning, DNS
    check results). Unbalanced-brace check over all 281 .kt files after the sweep:
    clean (the one flagged file, `cs3/FallbackResolver.kt`, was untouched and is a
    tokenizer artifact of its regex string).
  - **(c) Library ticks — root cause found, and it was exactly what it looked
    like.** `CategoryToggleRow` (LibraryCategories.kt) applied ONLY
    `Modifier.tvToggle(...)` — which is `focusable` + `onPreviewKeyEvent`, i.e.
    D-pad only — and its `Checkbox` had `onCheckedChange = null` (decoration), so
    on a touch device NOTHING in the row had a click handler: a tap on the row or
    the box did nothing, which is why a ticked category could not be unticked.
    Fixed with `Modifier.toggleable(value, role = Role.Checkbox, onValueChange)`
    (touch + ripple + a11y) kept alongside `tvToggle` (the D-pad, consumed in the
    preview pass so it cannot double-toggle).
  - **(d) Telegram public channels — root cause proved by fetching the page.**
    `t.me/s/Youngupdatesource` (the reported channel) DOES carry the posts, but
    the video ones arrive with Telegram's `message_media_not_supported` block and
    **no `<video src>` at all** (verified: 19 messages, zero `<video>` tags; the
    per-post embed page `t.me/<ch>/1444?embed=1` too, and those posts are the
    12:58 / 855 MB ones Telegram withholds from anonymous visitors). So
    `TelegramWeb.parse` returned an empty list and the screen said "This channel
    has no videos on its public page" — a claim about the channel that was simply
    false. Fix: `TelegramWeb.parsePage`/`loadPage` return a `TelegramPage` with an
    `unpublished` count; when a channel's page has video posts but no publishable
    files, the tab now reads the channel through the user's OWN account —
    `Td.publicChatId(@name)` (new; `TdApi.SearchPublicChat`, no join needed) then
    `Td.chatVideos(...)`, rendered with the existing `ChatVideoCollection` and
    played by the existing `playTdVideo`/`TdStream`, with "Load older" paging. Not
    signed in → the empty state says what is actually happening and that signing
    in fixes it.
  - **(e) The crash: `IllegalArgumentException: Key "stremio|1617974660" was
    already used`** — a lazy list with two items sharing an id. Keys of that shape
    are provider ids, and two render sites had no dedupe
    (`ExtensionsScreen` "STREMIO ADDONS",
    `CollectionScreens` catalog-source picker). The real source is the installed
    list itself: `AniyomiExtensionManager`'s own comment documents a past bug that
    GREW duplicates ("I installed Anime World again and now it lists six of the
    same provider"), so a store written by an older build can legitimately hold
    two rows with one id — and `addProvider`/`setEnabled` cannot repair that.
    Fixed at the source (`ProviderManager.refresh` prunes `distinctBy { it.id }`
    AND writes the pruned list back, so the repair sticks) plus `distinctBy` at
    both render sites.
  - **Bonus data-format fix found while building profiles.** `PrefRecord` carries a
    string set as a JSON array, and `AppStore.applyRecord`'s "ss" branch only
    accepted a `List<*>` — a `JSONArray` (which is what a file gives back) was
    silently DROPPED, so a restored backup lost every set-valued setting (hidden
    tabs, disabled extensions, allowed sources). Both the backup and the profile
    path are fixed by accepting a `JSONArray` there (and `Profiles` converts
    explicitly).
  - **Verification done here:** brace/paren balance over all 281 .kt files after
    the scripted sweep; the Telegram root cause against the real fetched HTML;
    the profiles-no-auto-adopt assumption against the Aniyomi/CS3 sync sources.
    The compile itself is the CI run on the pushed sha.
  - **CI:** the first push (`c547ab50`) FAILED to compile — 40 errors, all in 3
    files: a glued `import` line in `SettingsScreen.kt` (a my-edit artifact);
    a nullable `<video>` element in `TelegramWeb.kt` (the `msg.selectFirst`
    result was passed where non-null was required — the loop now `continue`s on
    a null element, so it smart-casts); and seven `tr()` calls inside
    NON-composable lambdas in `ProfilesScreen.kt` (`scope.launch` bodies, the
    dialog `onConfirm`s) — hoisted into `val msg… = tr(…)` at the top of the
    composable and referenced from the lambdas. Fixed in `d922d750`; CI run
    **`36233429646` is green**, the `continuous` pre-release was re-published
    (`hikari-arm64-v8a/armeabi-v7a/signed.apk`, 09:50Z today) and the `build`
    branch moved to `build: update test APK 202609260950`. Latest non-prerelease
    release is still **`v0.10.42`** — no main release from this round. Note for a
    future session: a plain `import` edit in a Kotlin file is easy to glue into
    the neighbouring line; after any scripted edit, re-read the region.
- **0.10.43** (versionCode 214) — two requests in one message. (a) *The report:* two
  screenshots of the loading screen stuck on "Searching your extension for servers…"
  for **51s** and **53s** (燃气十年 ep 379, 遮天 ep 182) while the SAME episode plays in
  a couple of seconds in CloudStream, plus the decisive new clue — *"i tried play same
  after closing and restarting app, and same episode plays in instant like not even
  took 2 second"*. (b) *The feature:* in the WebView's ⋯ menu, an "allow redirect to
  this site" for the link that was just blocked, and an allowed-redirect list that
  accepts a single word (`filester` ⇒ any link containing it, whatever the TLD).
  Continuous build only — **no main release**.
  - **Which extension is the origin (evidence).** The Home log line in the user's own
    crash-report breadcrumbs reads `Home: pick=hiki|2033716227|0 … from=Anime4i`, and
    `hiki|<hash>|<i>` is exactly how `ExtensionsScreen` mints a `.hiki` provider id —
    so the title's own extension is a **HIKARI** provider, i.e. the one engine whose
    stream call goes through `ProviderGate` (`HikariProviderAdapter.getStreams`, lane
    BACKGROUND). Same log: **531 installed providers**, device INFINIX X6815D / Android
    13.
  - **Root cause of the ~51s: the origin was asked twice, and the two asks could not
    overlap.** `ContentRepository.fetchStreams` gave the origin a 12s **probe** and then
    a full-budget retry. A provider call cannot be interrupted (`withTimeoutOrNull` only
    abandons the WAIT), and `ProviderGate` allows ONE call into an extension at a time —
    so the abandoned probe kept holding the extension while the "retry" waited for the
    lock, i.e. probe-wait + probe's real duration + the whole second extraction. 12 +
    ~40 ≈ the 51s/53s on screen, and a fresh launch has no abandoned call inside the
    extension, which is why the restart played instantly. Fixed by giving **every
    attempt the full budget** (45s, 90s Aniyomi — the same budget CloudStream gives a
    plugin) and retrying only after a call really came back empty, which is the only
    retry that can no longer queue behind a running call. The old 12s cap bought
    nothing: nothing the player does waits on the origin any more (its auto-start waits
    for the FIRST server, `StreamsLive.settleOrigin`), and the pass deadline + the
    player's 4s failsafe bound the wait.
  - **"First server fast" was structurally impossible for the CloudStream engine.**
    `Cs3MainApiProvider.pluginSources()` returned an empty list unless
    `pluginJob.isCompleted`, so the merge loop's `!pluginDone && sawPluginSource` branch
    (its comment: "it streams them in as it goes") was **dead code** — no server could
    be handed over until the whole `loadLinks` run returned, i.e. until the plugin's own
    loadLinks budget expired. Now the partial list is read (guarded by a links/subs size
    check so the 80ms poll does not re-map it for nothing), the loop breaks 2s after the
    first link as its own comment intended, and a log line records when servers were
    handed over while the plugin was still extracting.
  - **`ProviderGate`'s BACKGROUND lane yielded app-wide.** Its "don't start while a page
    is loading" test used `interactiveWindows` — a counter for ANY provider's page —
    and parked a background call for up to `BACKGROUND_MAX_HOLD_MS` (20s) even when
    nothing was contending its own extension, which a player's stream lookup sat behind.
    Now that check buys `BACKGROUND_COURTESY_MS` (2s) and a page waiting on the SAME
    extension keeps its full 20s hold (the case that was ever reported as a page being
    held up). A `WAIT_LOG_MS` line names the reason a background call started late —
    "a page is waiting for this extension" / "a page was loading" / "this extension was
    still finishing an earlier call" — which is how the next report gets attributed.
  - **Instrumentation for the next round.** `fetchStreams` logs `N server(s) in Ns
    (attempt k/m)` for the origin and for any call ≥3s (`SLOW_CALL_LOG_MS`), plus a line
    when an attempt answers nothing and is re-asked with the full budget. If the wait
    persists after this build, the user's **Settings → Logs** will show whether the
    extension itself took the time (its own seconds in that line) or Hikari held it (a
    ProviderGate line).
  - **Feature: allow-a-blocked-redirect, from the menu and as a word.**
    `Data/RedirectAllow` is now the single matcher — `allowsIn(url, list)` / `allows(url)`:
    an entry WITH a dot is a host (itself + subdomains), an entry WITHOUT one is matched
    as a case-insensitive substring of the **whole URL** (`filester` ⇒ filester.com/.me/
    .gg/.sh, and a host carried in a query). `WebViewActivity` remembers the refused
    main-frame navigation (`lastBlockedRedirectUrl/Host`, cleared per page, set at all
    four block sites including the Cloudflare-verify view), the ⋯ menu adds **"✔ Allow
    redirect to <host>"** (id 11) only while there is one, and the action appends the
    host to the stored list via `AppStore.setWebviewRedirectAllow` (which mirrors it
    before the DataStore write) and immediately loads the refused URL; the blocked-redirect
    toast now says where the way out is. Settings' supporting line explains both forms.
  - **Costs/risks knowingly taken:** a CloudStream extraction now hands over the servers
    it has ~2s after the first one and cancels the rest (the code's own intent:
    "its later servers are a nice-to-have; playback speed is the point") — if a
    "fewer servers than before" report comes back for a CS3 extension, this is it, and
    the fix is a late-links channel from `getStreams` to the live session.
  - **Two compile-fix commits followed the batch, both in `RedirectAllow.kt`.**
    `com.hikari.app.data` has its own `data class Collection` (Models.kt), which shadows
    `kotlin.collections.Collection` in EVERY file of that package — so `list: Collection<String>`
    was `No type arguments expected for 'data class Collection'` (and `list.iterator()`
    ambiguous). Parameter is now `list: Iterable<String>`; `Iterable` is not shadowed, but it
    has no `isEmpty()`, so the guard is just `if (url.isNullOrBlank()) return false` — an empty
    list falls through the loop to `false` anyway. Final green CI run: `36229174778` on
    `22e19ac2`; `continuous` pre-release + `build` branch refreshed 08:25Z, no main release.
- **0.10.42** (versionCode 213) — the user's report: *pairing from phone to TV copied
  the phone's app lock (the TV came up on a PIN screen) and the phone's layout; the
  fix must never carry either, the layout must be detected per device, and this build
  is to be published as a MAIN RELEASE* (the first since 0.10.25 — dispatches
  `build.yml` with `release=true`, `confirm_release=CONFIRM-RELEASE`,
  `version=0.10.42`; the release body is the `## 0.10.42` CHANGELOG section, so that
  section was written as a normal user-facing changelog "Everything added and fixed
  since 0.10.25" in the same voice as `v0.10.25`'s notes — no debugging narrative).
  - **Root cause (evidence, not a guess).** Pairing's payload *is*
    `BackupManager.export(app)` (`PairHost.start`), and the guest applies it with
    `BackupManager.restore` → `AppStore.restorePreferences`, which wrote EVERY key of
    the one DataStore (`snapshotPreferences` = all 145 keys, no filter). Three separate
    transfers came out of that single hole: (1) `appLock` + `appLockSecret` (+ length,
    biometric, screen-off, delay, leave) → `AppLockGate` reads them and the TV comes up
    locked with the phone's PIN; (2) `tvMode` — the phone's *default* `"auto"` (i.e.
    "follow the device") overwrote the box's own explicit "this device is a TV"
    override, so a box that misreports itself fell back to the phone layout, and
    `uiScaleEnabled=false`/`uiScalePercent=100` plus `fullscreenOff` overwrote the
    television's own 110% scale and immersive mode → the phone layout on the TV;
    (3) `tvSeeded=true` arrived from the phone, so the TV's own first-run defaults
    ("no poster effects, 110% UI scale", `HikariApp.onCreate`) could never be applied,
    and `tvPerf`/`tvPerfChosen` replaced its lighter visuals.
  - **Fix: one rule, one list, both directions.** New `AppStore.DeviceLocal` object
    (`KEYS` built from `K.*.name` so it cannot drift, `contains(key)`): app lock (9
    keys), layout (`tvMode`, `tvOverscan`, `uiScaleEnabled/Percent`, `fullscreenOff`),
    this device's capability/first-run state (`tvPerf`, `tvPerfChosen`, `tvSeeded`,
    `perfMode`), and the launcher-icon alias (`appIcon`). `snapshotPreferences()`
    filters it (so a backup never contains them) and `restorePreferences()` filters it
    again and now returns the number of settings actually applied — which is what
    repairs a file written BEFORE the rule existed (the user's case). `BackupManager`
    counts the kept-out records and reports them (`device-local kept: N`). UI copy
    updated: Backup & Restore's footer, the pair confirm dialog ("Your app lock and
    this device's layout stay as they are"), and a line on the guest screen after a
    successful restore. Deliberately NOT device-local (documented in the code):
    taskbar/buttons, My Stuff sections, theme/font/poster styling, and the *account*
    logins (tracker tokens, Telegram api pair) — those are the user's, not the
    device's. Deliberate non-fix: a device that ALREADY received a lock keeps it (an
    update must never silently unlock an app); the lock screen's "Forgot password? →
    Turn it off" is the way out, and the user was told.
  - Bumped 212 → 213 (`0.10.41` → `0.10.42`); CHANGELOG section prepended; main
    release published (see the release note above).
- **0.10.41** (versionCode 212) — one user report, fixed from the crash log they
  attached:
  - **Tapping the settings gear on a CloudStream (Cs3) extension crashed the
    app.** The attached `hikari-crash.log` named the frame exactly:
    `java.lang.NoSuchMethodError: No static method
    getDrawable(Resources;ILResources$Theme;)Drawable; in class
    androidx.core.content.res.ResourcesCompat` at
    `com.cncverse.Settings.getDrawable(Settings.kt:47)` ← `makeTvCompatible` ←
    `onViewCreated` ← `FragmentManager.execPendingActions`. **Root cause: R8
    release shrinking (app/proguard-rules.pro had no keep rule for
    `androidx.core`), NOT a missing method in the library.** Evidence gathered:
    (a) the stack trace shows the class present in base.apk but the METHOD absent;
    (b) downloaded `androidx/core/core/1.13.1/core-1.13.1.aar` (google maven), read
    `androidx/core/content/res/ResourcesCompat.class` out of `classes.jar` with a
    hand-written class-file parser → 19 methods incl.
    `getDrawable(Landroid/content/res/Resources;ILandroid/content/res/Resources$Theme;)Landroid/graphics/drawable/Drawable;`
    → the pinned library HAS it;
    (c) `proguard.txt` inside that AAR only has
    `-keepclassmembernames,allowshrinking` rules for a few `Api*Impl` classes → the
    library does not protect it;
    (d) `grep ResourcesCompat` over the whole repo → ZERO app references, so with
    R8 full-mode member removal the class survived (referenced by the kept
    `androidx.appcompat` → `AppCompatResources`) but the method was deleted. A
    plugin's call is a string in its own bytecode, so it must be kept by name —
    the exact doctrine in that file's header (section "the two rules this whole
    file is built on") which had been applied to the plugin SDKs but not to
    androidx.
  - **Fix:** new section **5b** in `app/proguard-rules.pro`: `-keep class
    androidx.core.** { *; }` plus the rest of the androidx surface a plugin's own
    screens can link (legacy, localbroadcastmanager, mediarouter, palette,
    customview, cursoradapter, interpolator, vectordrawable, documentfile,
    dynamicanimation, transition, constraintlayout, coordinatorlayout,
    drawerlayout, slidingpanelayout, swiperefreshlayout, viewpager, viewpager2,
    emoji2, autofill, savedstate, arch.core, tracing, startup, profileinstaller,
    resourceinspection, versionedparcelable, collection, print, loader,
    asynclayoutinflater, window, exifinterface, graphics, media) +
    `-dontwarn androidx.**`. The list was derived from evidence, not guessing:
    `app/libs/cloudstream3.jar` carries the `androidx.**.R` classes of
    CloudStream's own build, which enumerates the androidx namespaces a plugin
    author had on their compile classpath (activity, annotation, appcompat, arch,
    autofill, biometric, cardview, compose, constraintlayout, coordinatorlayout,
    core, cursoradapter, customview, documentfile, drawerlayout, dynamicanimation,
    emoji2, exifinterface, fragment, graphics, interpolator, legacy, lifecycle,
    loader, localbroadcastmanager, media, media3, mediarouter, navigation,
    navigationevent, palette, preference, print, profileinstaller, recyclerview,
    room, savedstate, slidingpanelayout, sqlite, startup, tracing, transition,
    tvprovider, vectordrawable, versionedparcelable, viewbinding, viewpager,
    viewpager2, window, work, core/ktx, core/viewtree); the namespaces already
    kept by section 5 (appcompat/fragment/recyclerview/preference/cardview/
    viewbinding/annotation/activity/lifecycle/webkit/media3/material/biometric)
    and the ones only Hikari's own stack uses and which no plugin can see
    (compose, navigation, datastore, room, sqlite, work) are deliberately left
    out. Also note: **this bug class is release-only** (debug builds are not
    shrunk), so it can never be reproduced in a debug APK — the user's installed
    `continuous` build is a release build, which is why it crashed for them.
  - **Second report in the same batch: "the clear all data button in settings is
    in open — create a new setting folder Clear App data and put it in there,
    with a warning on tap".** The button was a red `TextButton` at the very foot
    of the settings index (a second copy sat at the bottom of About & Updates),
    and it wiped every stored preference on the tap itself, with no warning.
    Now: a top-level **Clear App data** folder (`SettingsFolder.CLEAR_DATA`, key
    `clear-data`, `Icons.Filled.DeleteForever`, subtitle "Delete everything
    Hikari has stored on this device") whose page is `ClearDataCard` — it says
    what "everything" means, says it cannot be undone, and its button only opens
    an `AlertDialog` ("Clear all data?" body "All your data will be deleted: …
    This cannot be undone.") with a red **Clear** confirm and **Cancel**. That
    dialog is the only remaining call site of `app.store.clearAll()`. Both old
    links are gone, and ABOUT's subtitle dropped the "& reset" it no longer
    carries ("Version, links, roadmap & reset" → "Version, links & roadmap").
    The new English literals are deliberately NOT added to `assets/i18n/*.json`:
    `tr()` falls back to the literal and `TagTranslator` live-translates the gap,
    which is the established behaviour for new copy.
  - Version bump 211 → 212 (`0.10.40` → `0.10.41`), CHANGELOG section prepended,
    pushed to `main`; **no release** (CI publishes the `continuous` pre-release +
    `build` branch).
- **0.10.40** (versionCode 211) — three user reports in one push, all "do a real
  fix, not a blind one":
  - **"Still 55/60 servers and it will not play" (4th and 5th report of the same
    complaint).** The screenshots that came with it are what settled it: the player
    cover reading "Found 53 servers — still searching…" with no picture. Three
    mechanisms were wrong at once. (1) `PlayerActivity` had NO `coverPlaybackLine`,
    so the cover kept printing the SEARCH's count (`StreamsLive.statusFlow`) after
    playback had already committed — the user's own screenshot of a committed
    player is indistinguishable from a stuck one. Added: `playSource` clears it,
    `playDirectInner` sets `Starting <server>…`, `advanceToServer` /
    `awaitReplacementForStalledServer` set what is happening to the server, and the
    status collector prefers it over the search line. (2) `probeAndPlay` awaited
    `StreamProbe.resolve(...)` BEFORE `playDirectInner` — resolving a wrapper URL
    walks dead hops and can take minutes, so the player held servers, had
    committed, and sat in a probe. Now the walk is a detached `async` awaited with
    `withTimeoutOrNull(firstProbeWaitMs = 2_500L)`; past it the RAW url goes to
    ExoPlayer and the walk's answer is re-applied if it lands while that server is
    still current and no frame has been drawn. (3) The no-first-frame watchdog was
    armed `if (mime != null || drmManager != null)` (line 7919 of the previous
    build), so a plain progressive source that reached READY with no picture was
    unrecoverable — `recoverNoPicture(index)` now exists and the task is armed for
    EVERY source, re-arming every `firstFramePollMs = 4_000L` while not READY.
    Related fixes in the same area: `nextUntriedIndex` PASS 1 prefers an untried
    `probeVerified()` server not on a dead host; `promptSlowServer` skips silently
    (`maxSilentSkips = 6`) while nothing has played and the search is live; the
    `START_FAILSAFE` poll also PULLS `StreamsLive.flow(liveId).value` and appends
    anything missing, so the cover cannot say N servers while the player holds
    none; `firstFrameRetried` is no longer reset by `playDirectInner` (it was reset
    by the very restart path it guarded → a frameless server restarted every 20 s
    forever) but when the walk moves to a different index; `noVideoPolls` bounds
    the "READY but no video track reported yet" case at 3 re-checks.
    `docs/SEARCH.md` invariant 18.
  - **App lock still asked for the password with "Lock when I leave the app" OFF**
    (screenshot: lock on, fingerprint on, screen-off switch ON, leave switch OFF,
    grace Instant). Cause: `unlocked` was composition-only, so ANY fresh process
    started locked, and removing an app from recents kills its process on nearly
    every launcher. Added `AppStore.APP_LOCK_SESSION_OPEN` + `APP_LOCK_SESSION_AT`
    (`appLockSession()` / `setAppLockSessionOpen(at)` / `setAppLockSessionClosed()`),
    written by `setAppLock(on = true)` as closed and by the unlock as open; the
    first frame seeds `unlocked` from `(open, at)` + the switches in this order:
    leave OFF + screen-off OFF → unlocked; grace > 0 → `now - at < grace`;
    leave ON + grace 0 → locked; leave OFF + screen-off ON → the stored session
    decides. With a grace period, `ON_STOP` re-stamps the session with the moment
    of leaving (that is what makes the stored deadline mean "since I left").
    `ACTION_SCREEN_OFF` now calls `lockNow()` on the event itself (an already
    backgrounded app never got another `ON_STOP`), and `lockNow()` persists the
    closed session. `docs/APP_LOCK.md` rewritten around the table of decisions.
  - **Aniyomi/SkyStream ~15 s to load the episode list and the detail page.**
    (1) `AniyomiProvider.metaLocked` asked for episodes first through a 3-shape
    walk then details, with a details-then-retry pass when a shape answered empty
    (up to ~7 serial requests). Now: `combinedSupported(src)` answers by
    REFLECTION (`Method.getDeclaringClass != AnimeSource::class.java`, cached in
    `combinedCalls`) and `metaLocked` makes ONE
    `getAnimeEpisodeUpdate(anime, emptyList(), true, true)` call for details +
    episodes; `episodesLocked(..., preDetailed)` skips the double pass;
    `fetchEpisodeList` tries only two shapes with the implemented one first;
    `storeEpisodes(raw, animeId)` is the shared funnel. (2) `ProviderGate` gained
    `enum class Lane { INTERACTIVE, BACKGROUND }`, `interactiveWaiters`,
    `interactiveWindows`, `interactiveActive()`, `suspend fun <T> interactive(block)`
    and `withProvider(id, lane = INTERACTIVE, block)`: INTERACTIVE = FIFO; BACKGROUND
    = poll/tryLock only when no interactive waiter or window exists, with
    `BACKGROUND_MAX_HOLD_MS = 20_000L` as the anti-starvation backstop.
    `ContentRepository.metaFor`/`episodesFor` are now thin `ProviderGate.interactive`
    wrappers around `metaForInner`/`episodesForInner`; `AniyomiProvider`,
    `MangaProvider` and `HikariProviderAdapter` `getStreams` use
    `Lane.BACKGROUND`; `DetailViewModel` gained `prefetchJob`/`startPrefetch()`/
    `cancelPrefetch()` and `openStreams` cancels the prefetch first.
    `docs/PERFORMANCE.md` new section.
  - Version bump 210 → 211 (`0.10.39` → `0.10.40`), `CHANGELOG.md` section
    prepended, docs above updated, pushed to `main` — **no release** (CI publishes
    the `continuous` pre-release + `build` branch, which is what the user installs).
- **0.10.39** (commit `b0bc0e63`, versionCode 210, CI green, published to
  `continuous` — no main release) — three user reports in one push:
  - **"56 servers found and it is still not playing" (third report of the same
    thing).** The cause was the ONE remaining hold: the wait for the extension the
    title was opened FROM — 45 s in "wait for more servers first" mode (`originReady`
    in PlayerActivity, alarms at `originHoldUntil`/`originHeadStartMs`). 0.10.38 had
    already zeroed it for the instant mode, which fixed nothing for a user whose
    setting was the patient one. The hold is now GONE in both modes: DetailScreen
    sends `originGraceMs = 0` / `originHeadStartMs = 0`, `tryStart` starts on the
    first server, the 10 s `PREFERRED_GRACE_MS` "wait for the server last played
    with" hold is gone too (kept as ORDER instead), and PlayerActivity gained a
    `START_FAILSAFE_MS` (4 s poll) that starts playback and logs `FAILSAFE: …` if
    servers ever sit with nothing committed. `docs/SEARCH.md` invariants 2 + 13.
  - **Screen-off app lock did not lock.** `ProcessLifecycleOwner`'s `ON_STOP` read
    `PowerManager.isInteractive`, which the platform can dispatch before the
    display state settles → read as an ordinary "left the app" → consulted the
    leave switch. `AppLockGate` now also registers a receiver for
    `ACTION_SCREEN_OFF`/`ACTION_SCREEN_ON` (via `ContextCompat.registerReceiver`,
    NOT_EXPORTED) and ORs the two signals; `enabled` is collected as `Boolean?` so
    a locked app cannot flash its content during the cold-start read. New
    `docs/APP_LOCK.md`.
  - **Per-engine family switches.** `SearchScope.engineFamilies: Set<String>` +
    `SearchScope.family(type)`; `nuvioFamily`/`stremioFamily` are computed
    getters now. `AppStore.engineFamiliesFlow()` merges the two dedicated keys
    (`NUVIO_SEARCH_ALL`/`STREMIO_SEARCH_ALL`, kept so existing choices survive)
    with the new `searchFamilyTypes` set, and `setEngineFamily(type, on)` writes
    whichever applies. `AppStore.searchAllExtensionsFlow()` now defaults to
    **false**. `sameEngine` in `streamsForInner` is gated on
    `SearchScope.family(origin.config.type)` and logs when siblings are skipped.
    SettingsScreen renders one "Search every <engine>" row per installed engine
    (`ProviderType.isFamilyEngine` filters out Nuvio/Stremio/IPTV/MANGA).
    `docs/SEARCH.md` invariant 17.
- **0.10.38** (commit `9db50401`, versionCode 209, CI green, published to
  `continuous` — no main release) — five user reports in one push:
  - **"Play instantly as soon as 1 server is found"** (reported with 12 servers
    found and the video still not starting). The player's auto-start still held
    the first server behind the ORIGIN's answer: in the default instant mode that
    hold was a 3 s head start (`ORIGIN_HEAD_START_MS`). Both it and the
    instant-mode backstop (`ORIGIN_INSTANT_GRACE_MS`) are 0 now, so
    `originGraceMs = 0` on the launch intent makes `originReady` true the instant
    anything is in hand. "Wait for more servers first" still uses
    `ORIGIN_PLAY_GRACE_MS` (45 s). `docs/SEARCH.md` invariant 2.
  - **THE BLANK chapters (`HttpException: HTTP error 403`) — two causes.**
    (1) The chapter list is an Inertia XHR to `/serie/<slug>`, which theblank.net
    guards with Cloudflare (live probe: `403 Attention Required! | Cloudflare`);
    `ExtensionCloudflareInterceptor.isChallenge` demanded an HTML interstitial in
    the BODY, but a JSON XHR body has none, so the solve never ran. It now treats
    a 403/503 served BY Cloudflare as a wall (same as `CloudflareVerifier` and
    CloudStream's `CloudflareKiller`). (2) Upstream, keiyoushi fixed The Blank's
    chapters on 2026-09-16 ("reader v2 attestation and ece pages", versionCode
    54) — an older installed extension fails in ANY reader, which is why Nekoread
    works. `docs/MANGA.md`.
  - **Catalogue / episode / detail loads are slow on the second look** (aniyomi &
    skystream called out). New `data/MetaCache.kt`: on-disk JSON per key under
    `filesDir/metacache/` (bounded, trimmable, no user data). `loadCatalogPage`
    gained an `onCached` callback (CatalogScreen paints it at once) and now falls
    back to the disk cache at any age; `episodesFor` paints a cached list via
    `onPartial` then refreshes and returns the cache instead of `null` when every
    engine is empty; `metaFor` serves a cached enriched meta outright (overview
    present) and stores partial results it used to discard. TTLs 6 h / 12 h / 3 d.
    `docs/PERFORMANCE.md`.
  - **Stremio family search.** `SearchScope.stremioFamily` (mirrored from
    `AppStore.stremioSearchAllFlow`, default on) + `stremioOrder` add every
    installed Stremio addon when the title was opened FROM one and scope is "only
    this extension"; `stremioPrimaryIds` now covers the family so the cross pass
    does not re-ask them by title. New "Search every Stremio addon" switch on the
    Server search card. `docs/SEARCH.md` invariant 16.
  - **App lock: "Lock when I leave the app"** switch (default on) + the Trackers
    card's intro paragraph removed. `AppStore.APP_LOCK_LEAVE`,
    `AppLockGate` now picks the switch by trigger (`isInteractive` at `ON_STOP`).
- **0.10.37** (versionCode 208) — three user reports in one push:
  - **THE BLANK fails in Hikari with `Exception: HTTP Error 403` but loads in
    Nekoread.** Not a Cloudflare wall: the extension's own Pam multisrc base GETs
    the *site root* first and throws that exact string on any non-200. The site
    was refusing Hikari's User-Agent — `effectiveWebViewUa()` returned the stock
    Android WebView UA, and that one value is the default UA for *every*
    Aniyomi/Mihon extension request. The stock string carries `; wv` and
    `Version/4.0`, and theblank.net refuses anything marked as an embedded
    reader (its own block page says so). Proved live with `root.superFetch`:
    stock UA → 403 / 2746 bytes (block page); same string minus the two markers
    → 200 / 112678 bytes; `; wv` alone → 403. Fix: `HikariApp.withoutWebViewMarkers()`
    strips both markers before the UA is used anywhere (extension client, verify
    WebView, offscreen solver), so a `cf_clearance` still matches what follows
    it. One function, fixes every extension. See `docs/MANGA.md`.
    *(The temporary `superFetch = {import:super-fetch-plugin}` added to this
    workspace's `main.pjs` for the live probe has been removed.)*
  - **A title opened from a Nuvio provider should search every installed Nuvio
    provider**, even with "Search all installed extensions" off — and there is now
    a dedicated switch ("Search every Nuvio provider", default on) in Settings →
    Playback & Servers. New `SearchScope.nuvioFamily` flag + `nuvioOrder()`
    comparator in `ContentRepository`; the family case outranks the
    exception-collapse rule for nuvio origins only. `docs/SEARCH.md` invariant 15.
  - **App lock: lock on screen-off, and a grace period.** "Lock when the screen
    turns off" switch (default on = today's behaviour) plus a "Lock after
    leaving" slider from Instant to 60 min (1-minute steps, default Instant).
    `AppLockGate` reads `PowerManager.isInteractive` at `ON_STOP` to tell a
    screen-off from an app switch, and stores a wall-clock timestamp (not a
    running timer) so the grace period survives sleep and a killed process.
- **0.10.36** (versionCode 207, published to `continuous`) — five user reports in
  one push:
  - **"Movies play, but every series on PenguPlay says no playable source; the
    same addon shows 79 servers in Stremio."** Three Stremio-protocol mistakes in
    `StremioAddon.getStreams`, all series-only: the `/stream/{type}/{id}` segment
    used the item's `rawType` (TMDB's `tv`) first instead of the types the addon
    declares FOR THAT ID; the walk stopped at the first non-empty parse, and a
    link row (`externalUrl`/`ytId`) parses as a stream so it ended the search
    before the spelling carrying the servers was asked; and the numeric part of a
    `tmdb:…` id was read with `takeWhile { isDigit }` on the raw id, so an
    addon declaring only `tt` was asked about an unknown namespace. Also: a
    series with no episode list is asked for `:1:1`, and `/meta` falls back to
    `TmdbBrowse.episodes` for a `tmdb:` id. See `docs/SEARCH.md` invariant 12.
  - **"79 servers found and the video still does not start."** `originReady`'s
    head start for the origin ignored servers a probe had already PROVED
    playable; `PlayerSource.probeVerified()` now ends the hold (and wins
    `healthyStartIndex`), so playback starts on the first proved server while the
    rest keep loading in the background. Invariant 13.
  - **"Installing extensions still feels laggy."** The install's 20 s timeout
    abandoned the WAIT while the blocking OkHttp read ran on to 30 s on its IO
    thread — a leaked download per cancelled/overrunning install. New
    `Http.fetchBytesCancellable` / `downloadBytesCancellable` enqueue the call and
    CANCEL it with the coroutine (`invokeOnCancellation { call.cancel() }`); every
    install path (hiki/nuvio/skystream/cs3/aniyomi + nuvio first-run seeding) uses
    them.
  - **"Show all says nothing here right now although Home's row is full."**
    `ContentRepository.loadCatalogPage` is now the single funnel for every
    engine's catalogue read: retry once when the page the user is looking at comes
    back empty, serve the last non-empty page 1 from a process-wide cache, and
    surface the addon's own `error`/`totalItems` words. A search-only catalogue is
    no longer a Home row (`StremioAddon.homeCatalogs`), and `search()` skips
    catalogues that declare extras without `search`. Invariant 14.
  - **D-pad completeness.** The TV layer existed; the remaining bare
    `Switch`/`Slider`/`Checkbox` controls (Settings, Collections, Extensions,
    History, Player controls, library-category picker, reader settings sheet) are
    wrapped in `Modifier.tvToggle`/`Modifier.tvAdjust`. The reader's LIVE chrome
    page scrubber deliberately keeps left/right for page navigation.

- **0.10.35** (commits `750d385` + `78e4da3`, both CI green, published to
  `continuous` as versionCode 206) — two user reports:
  - **"The same PenguPlay addon shows dozens of servers in Stremio and Hikari
    says no playable source found."** The real cause was that a title browsed
    from Hikari's own Home/Search/Collections carries `providerId = "tmdb"`
    (`TmdbMeta`) and `manager.byId("tmdb")` returns null, so `streamsForInner`
    built every target list from a null origin: with "Server search: only this
    extension" on, **no provider was asked at all**; Stremio addons were only
    ever asked *by title* (useless for a catalogue-less addon like PenguPlay).
    New `originless` case asks the id-resolving engines (all Stremio addons by
    id + nuvio engines) whatever the scope switch says, and excludes them from
    the by-title cross pass (`crossExtensionTargets(alsoSkip=…)`). See
    `docs/SEARCH.md` invariants 10 and 11.
  - **"It shows servers and then says no playable source."** `playableEvery`
    filters `ytId`/`externalUrl` rows out of playback while the sheet renders the
    raw list, and the resolver ran once at the end of a pass for the first 6 rows
    in a 20 s budget. `PlayableResolver.warmLinks` now resolves every link row in
    the background as it arrives and pushes the servers back through the view
    model's live feed; `magnet:`/`torrent:` URLs in a Stremio `url` are parsed
    into a torrent row; the verdict wording is honest ("Found N links … none
    could be turned into a video") and `PlayerActivity.isNoResultVerdict`
    recognises it; `CrossTally.title` stops a summary describing another title's
    search.
  - **Telegram Saved Messages search.** `videosOfPost` walked only 20 newer
    messages and capped at 40 videos, so a tag with 200+ videos under it showed a
    fifth of them; `searchChatVideos` cut a hit's tail at the page size and moved
    the cursor to the next hit; the history walk treated a short page as the end
    of the chat (TDLib documents that it may return fewer than asked); and the UI
    needed a "Search further back" tap per page. All four are fixed (the tail
    walk pages with `GetChatHistory(offset = -99, limit = 100)`, the walk ends on
    a page with nothing new, the UI auto-pages with a live progress line), and
    "Look in: Both" is now the union of the post-text search and the file-name
    walk (`chatTextSearch` + `historySearch`).
  - Telegram bugs here must be reasoned from `TdApi.java`'s own doc comments
    (the vendored copy in `scratch/TdApi.java` is gone with the session; the
    aar's `TdApi` in `app/libs/` is the source of truth) — e.g. negative
    `GetChatHistory.offset` = "additionally -offset NEWER messages", and the
    returned count "can be smaller than the specified limit".

- **0.10.34** (commit `57e4050`, CI green, published to `continuous`) — six
  user reports, all in one push:
  - Telegram: chat/channel taps never opened anything (the "no chat open"
    sentinel was `-1` while every channel id is negative); a tag written as its
    own message above the videos found nothing (the search asked TDLib for VIDEO
    messages whose text matched, and those videos have no text — it now searches
    POSTS and resolves each hit to the videos that follow it). See
    `Td.searchChatVideos` / `Td.videosOfPost` / `TelegramScreen` (`openChat`).
  - Extension install jank/crash: `requestRefresh()` is ignored during a bulk
    run (which rebuilds once itself), `checkUpdates` streams the file hash and
    caches it per `(size, mtime)`, `updateProviders` and `reloadInstalled` skip
    publishing an unchanged value, and `ProviderManager.refresh` skips a rebuild
    whose configs are identical.
  - Stremio "no playable source": `ytId` and `externalUrl` rows were filtered
    out downstream and never played. New `cs3/PlayableResolver.kt` resolves both
    (`ytId` via NewPipeExtractor, whose `NewPipe.init(Downloader)` had **never**
    been called in this app, so the jar's YouTube extractor could not work at
    all; only MUXED YouTube formats are offered — the player has no audio-track
    field). Wired in at the end of `ContentRepository.streamsForInner`.
  - Trackers: the sign-in dialog now preflights the authorize URL
    (`TrackerApi.checkAuthorize`) so a refused client id is *explained* instead
    of opening MAL's `401` + `WWW-Authenticate: Basic realm="OAuth"` — which a
    browser renders as a username/password box. The WebView cancels
    `onReceivedHttpAuthRequest`. The pending sign-in (`TrackerApi.Pending`,
    `AppStore.trackerPending`) is persisted so a browser redirect finishes the
    code exchange in `MainActivity` even if the dialog is gone.
  - **AnimeOnline.Ninja (Aniyomi)** — investigated, no Hikari-side defect found
    (site markup matches the extension's selectors; the QuickJS bridge, the
    `wsidchk` solver and the `Injekt` singletons the extension needs are all
    present). The extension's own repo rewrote that source on 2026-08-31, so an
    older installed build is the thing to check. Recorded in the 0.10.34
    `### Notes` section of the repo's CHANGELOG.
