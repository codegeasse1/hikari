# Hikari for Windows

A native desktop media browser for existing Hikari users. It does not wrap or require the Android APK, and it does not change the Android `:app` module.

## What it does

- **Internal player**: direct-play streams (progressive + HLS) open in an in-app Hikari Player window (JavaFX media + Swing surface, subtitles, speed, quality switching). DASH and embed pages still open externally as before.
- **All portable extension ecosystems**: Stremio add-ons plus the JS families — Nuvio, Anymex/Mangayomi, Sora, SkyStream, Zangetsu, Vega — all executed locally in V8 (Javet) with the same fetch/crypto/prefs plumbing as Android, plus M3U playlists and Xtream Codes portals (IPTV).
- **Extensions screen**: TMDB key override, add-by-URL-or-file for every family, curated repository browsers (Nuvio + Anymex), enable/disable, validation, delete.
- **Full app wiring**: engine catalogs feed Home shelves, engine search merges into Search, engine titles open the same detail page (TMDB meta/episodes where the family supports it), and playable engine streams merge into the stream picker.
- Desktop keeps its own catalog browsing, Search, Library, History, and Watch-history behavior from the MVP.

## Hard boundary

CS3 `.cs3` plugins and Aniyomi `.apk` extensions are Android dex binaries loaded through `dalvik.system.PathClassLoader` — the desktop JVM cannot execute them, so they are not portable. Everything else ports. The manga/novel reader UI is not part of this port (engines expose video catalogs; no reader screen exists on desktop yet).

## Build and run

Use Gradle 8.11.1 or newer with JDK 17 or newer:

```sh
gradle :desktop:test
gradle :desktop:run
gradle :desktop:packageMsi
gradle :desktop:packageExe
```

The MSI and EXE installers must be generated on a Windows runner. CI builds both from `windows-latest` and uploads them as workflow artifacts and publishes them to the continuous release; no Android build or release is changed by that workflow.

The JS harness assets (`nuvio/*`, `anymex/*`, `sora/*`, `skystream/*`, `vega/*`) download at build time from this repo's `app/src/main/assets` into resources — no manual copying needed.

## Notes

- The app starts without touching JavaFX or V8; both initialize lazily (player opens, extension runs). Startup, unit tests, and the installer smoke test never load native libraries.
- Extension catalogs/details use TMDB. Hikari ships with built-in keys (same rotation as Android); paste a personal key on the Extensions screen if they ever run dry.
- Installed providers live under `~/.hikari-desktop/engines/` (scripts, prefs, IPTV cache).
