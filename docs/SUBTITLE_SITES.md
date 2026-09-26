# Subtitle sites

The player's **Load from internet** panel (Subtitles → Load from internet) and the
automatic pass behind **Subtitles → Find subtitles automatically** both ask the
sites in `app/src/main/java/com/hikari/app/subtitles/SubtitleSites.kt`, plus every
installed Stremio-style subtitle addon.

Everything here is a plain HTTP request to a **public, key-less** endpoint. No
account, no API key, no addon install. Each endpoint below was verified live
before it was wired in (status + content + a real download), which is why the
list is short: a site that needs a key (SubSource, Wyzie, OpenSubtitles.com,
Jimaku, Assrt) or that is behind an anti-bot wall (opensubtitles.org's HTML
pages, dl.opensubtitles.org, titlovi, TVSubtitles' search, moviesubtitles.org —
and YIFYSubtitles' *files*, whose pages are reachable but whose downloads are
not) is deliberately absent.

| id | site | search | languages | download |
|----|------|--------|-----------|----------|
| `opensubtitles` | OpenSubtitles (public mirror `opensubtitles-v3.strem.io`) | id only (`tt…`, `:season:episode` for a series) | 30+ | direct `.srt` URL given by the mirror |
| `opensubtitles-org` | opensubtitles.org search API (`rest.opensubtitles.org`) | **by name** (`query-…`), by `imdbid-N`, by episode/season | 30+ | `subs5.strem.io/…/src-api/file/<IDSubtitleFile>` |
| `subdl` | SubDL (`subdl.com`) | name → title page | 35 | `dl.subdl.com/subtitle/<id>.zip` |
| `subtitlecat` | SubtitleCat | name → release pages → per-language `.srt` | ~26 | site `.srt` links |
| `subscene` | Subscene (`subscene.best`) | name → title page | 40+ | `subscene.best/download/<subtitleId>` |

## How a search runs

1. The player resolves an **IMDb id** for the title (`SubtitleIds.imdb`): the id
   the item already carries, else TMDB (through `TmdbResolver`, which resolves a
   site-scraper title by name), else IMDb's key-less suggestion endpoint.
2. Every site and every installed addon is asked **concurrently**, each with its
   own timeout (`SITE_SUBTITLE_MS` = 18s, `ADDON_SUBTITLE_MS` = 30s). A failure or
   timeout contributes nothing and never blocks the others.
3. Results are merged, deduped by URL and sorted: the app's own language first,
   then English, then everything else (within a group, the site's own order — by
   download count where the site reports one).
4. Nothing is downloaded until a row is tapped (`applyRemoteSubtitle`), which runs
   the same validation a hand-picked file gets: gzip/zip/UTF-16 decoding, and a
   "does it really carry cues?" check (`isSubtitleText`).

## Gotchas worth remembering

- **OpenSubtitles (both routes) needs a `tt…` id.** The v3 mirror answers an
  empty `subtitles` array — with HTTP 200 — to a `tmdb:` id or a bare name. That
  is what made "no subtitles found for every title" look like a broken addon.
- **`rest.opensubtitles.org` refuses `sublanguageid-all`** (HTTP 403 from its
  WAF). The criteria-only form (`/search/imdbid-3521164`) returns every language,
  which is what the list wants anyway.
- **The v3 mirror's file ids are the same numbers** opensubtitles.org reports as
  `IDSubtitleFile`, which is why the two routes can share one download host.
- **Subscene's download URL is `/download/<id>`, not the `res.subscene.best/file/…`
  route it redirects to.** That route embeds a release-name slug
  (`titanic_HI_arabic-78041.zip`) that the list page does not carry, so a URL
  built from the id alone answers HTTP 404. `/download/<id>` is one request and
  serves the `.zip` (as `application/octet-stream`, which the decoder sniffs).
- **A site must pass a DOWNLOAD check, not just a search check.** YIFYSubtitles
  was dropped for failing it: `/movie-imdb/tt…` and its title pages answer 200,
  but `/subtitle/<slug>.zip` answers **403 with Cloudflare's "Just a moment…"
  challenge**, so its rows could only ever fail after the user tapped them.
  Search + page + file for every listed site, or it does not go in `ALL`.
- **`deleted`/renamed hosts**: Subscene is `subscene.best` (the original
  `subscene.com` is gone), SubtitleCat scrapes its own `.html` rows, SubDL's title
  page announces each language block with `data-language`.
- Every request goes through `Http.getStringQuiet` (the client WITHOUT the
  Cloudflare interceptor) so a third-party subtitle site can never raise the
  app's "verification needed for your extension" banner — the same rule the
  ratings lookups follow.

## Adding a site

Implement `SubtitleSite` (`id`, `name`, `search(SubtitleQuery): List<SiteTrack>`),
add it to `SubtitleSites.ALL`, verify the endpoints live, and it appears in the
panel and in the automatic pass with no other change. Multi-request sites (search
then a title page) should stay within ~3 requests, since every search is run on
the user's connection from a phone.
