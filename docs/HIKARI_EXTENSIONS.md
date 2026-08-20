# Hikari extensions (`.hiki`)

A Hikari extension is a small dexed JAR implementing Hikari's own provider API
(`com.hikari.ext.HikariProvider`). It plugs into Home, search, detail and the
player exactly like a Stremio addon or a CloudStream `.cs3` plugin — but it is
written against Hikari's SDK, so the fixes are made **in the SDK once** and
every extension benefits, instead of fighting CloudStream's desktop assumptions
(no-op WebView resolver, missing context, plugin-specific Cloudflare hacks).

The SDK classes live in the Hikari APK (`com.hikari.ext`), so:

- the app resolves them at runtime — extensions only need to be **dexed**, not
  bundled with the API;
- extensions can use the same platform libraries the app uses: `org.json`,
  `org.jsoup`, and all of `HikariNet`.

## The API in one screen

```kotlin
interface HikariProvider {
    val id: String                  // stable slug, e.g. "yts"
    val name: String
    val mainUrl: String
    val description: String get() = ""
    val version: Int get() = 1
    val iconUrl: String? get() = null
    val tvTypes: Set<HikariMediaType> get() = setOf(MOVIE, SERIES)

    fun catalogs(): List<HikariCatalog> = emptyList()
    suspend fun getCatalog(catalog: HikariCatalog, page: Int): List<HikariMedia> = emptyList()
    suspend fun search(query: String, page: Int): List<HikariMedia> = emptyList()
    suspend fun getMeta(media: HikariMedia): HikariMedia = media
    suspend fun getEpisodes(media: HikariMedia): List<HikariEpisode>? = null
    suspend fun getStreams(media: HikariMedia, episode: HikariEpisode?): List<HikariStream>  // required
}
```

A minimal extension implements `search` + `getStreams` (or `catalogs` +
`getCatalog` for a catalog-only source). Everything else has a safe default.

## The SDK helpers (`HikariNet`)

```kotlin
HikariNet.getString(url, headers)                       // body text (null on failure)
HikariNet.getJson(url, headers)                         // org.json JSONObject?
HikariNet.getBytes(url, headers)                        // ByteArray?
HikariNet.fetch(url, headers)                           // status + url + body
HikariNet.browserHeaders                                // desktop Chrome fingerprint

// The StreamHG/hgcloud-style helper: runs a page in a real Android WebView and
// returns every request whose URL matched the capture regex (e.g. the m3u8 the
// player JS fires), with its headers:
val hits = HikariNet.resolveWithWebView(embedUrl, Regex("(m3u8|master\\.txt)"))
hits.firstOrNull()?.let { HikariStream("Stream", it.url, headers = it.headers, isM3u8 = true) }
```

All requests go through the app's hardened stack: redirects, generous timeouts,
browser User-Agent, Conscrypt TLS.

## Stream shapes

| Playback mode        | Set on `HikariStream`                                   |
|----------------------|---------------------------------------------------------|
| HLS / direct         | `url` (+ optional `headers`, `isM3u8`/`isMpd`)          |
| Torrent              | `isTorrent = true`, `infoHash`, `trackers` (+ `fileIdx`)|
| YouTube              | `ytId`                                                  |
| External/browser     | `externalUrl = true`, `url`                             |

Torrents are played by the app's built-in TorrServer engine.

## Building an extension

1. Write your provider against `com.hikari.ext` (grab the classes from the
   `app/src/main/java/com/hikari/ext` sources in this repo).
2. Compile and **dex** the jar so Android can load it (a plain `.jar` won't
   load on device):

   ```bash
   # compile against the SDK classes on your classpath
   kotlinc -classpath com.hikari.ext.* -d classes src/... 
   # put classes + manifest.json into a jar
   jar cf MyExtension.jar -C classes . -C manifestdir manifest.json
   # dex the jar
   d8 MyExtension.jar --output .
   # d8 emits classes.dex next to MyExtension.jar — repack into the .hiki
   jar cf MyExtension.hiki MyExtension.jar classes.dex manifest.json
   ```

   (Simplest reliable shape: the `.hiki` is a jar containing `classes.dex`
   plus `manifest.json`.)

3. `manifest.json`:

   ```json
   {
     "name": "My Extension",
     "version": 1,
     "mainClass": "com.example.MyProvider"
   }
   ```

   `mainClass` may be an array if one file ships several providers — each
   becomes a separate installable provider.

4. Install in the app: Extensions → **Install .hiki from URL** or
   **Pick .hiki file**. Failed loads surface the real error on the card.

## Reference implementation

The bundled **YTS** provider (`com.hikari.ext.providers.YtsProvider`) ships in
the APK and is auto-registered on first run. It is a complete, working provider
against a public JSON API (torrents → TorrServer) and the cleanest template for
a new extension.
