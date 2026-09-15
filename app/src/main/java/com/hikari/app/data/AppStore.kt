package com.hikari.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.hikari.app.net.AdBlocker
import com.hikari.app.player.EnhancePreset
import com.hikari.app.ui.AccentStore
import com.hikari.app.ui.UiScale
import com.hikari.app.ui.theme.HikariAccent
import com.hikari.app.ui.theme.HikariThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.hkDataStore by preferencesDataStore(name = "hikari")

/** The stream server a video was last played with, remembered so a replay
 *  starts on the same server — instantly, with the same header variant that
 *  worked last time (0 = full headers, 1 = no Referer, 2 = none). */
data class LastSource(
    val url: String,
    val name: String,
    val headerVariant: Int = 0,
)

class AppStore(private val ctx: Context) {

    private val store get() = ctx.hkDataStore

    private object K {
        val PROVIDERS = stringPreferencesKey("providers")
        val FAVORITES = stringPreferencesKey("favorites")
        val CS3_REPOS = stringPreferencesKey("cs3Repos")
        val SITES = stringPreferencesKey("sites")
        val USERS = stringPreferencesKey("userscripts")
        val THEME = stringPreferencesKey("theme")
        val APP_ACCENT = stringPreferencesKey("appAccent")
        val PLAYER_ACCENT = stringPreferencesKey("playerAccent")
        val THEME_LINKED = booleanPreferencesKey("themeLinked")
        val PLAYER_CONTROLS = stringPreferencesKey("playerControls")
        val PLAYER_ENHANCE = stringPreferencesKey("playerEnhance")
        val UI_SCALE_ENABLED = booleanPreferencesKey("uiScaleEnabled")
        val UI_SCALE_PERCENT = intPreferencesKey("uiScalePercent")
        val HISTORY = stringPreferencesKey("history")
        val HISTORY_PAUSED = booleanPreferencesKey("historyPaused")
        val HIDE_CONTINUE = booleanPreferencesKey("hideContinue")
        val LAST_SOURCE = stringPreferencesKey("lastSource")
        val ELEMENT_BLOCKS = stringPreferencesKey("elementBlocks")
        val AD_ENABLED = booleanPreferencesKey("adEnabled")
        val AD_LISTS = stringPreferencesKey("adLists")
        val AD_BLOCK = stringPreferencesKey("adBlock")
        val AD_WHITE = stringPreferencesKey("adWhite")
        val WEBVIEW_REDIRECT = booleanPreferencesKey("webviewRedirect")
        val WEBVIEW_POPUP = booleanPreferencesKey("webviewPopup")
        val WEBVIEW_REDIRECT_ALLOW = stringPreferencesKey("webviewRedirectAllow")
        val WEBVIEW_DEFAULT_UA = booleanPreferencesKey("webviewDefaultUa")
        val WEBVIEW_CUSTOM_UA = stringPreferencesKey("webviewCustomUa")
        val YTDLP_ENABLED = booleanPreferencesKey("ytdlpEnabled")
        val HOME_PROVIDER = stringPreferencesKey("homeProvider")
        val TRANSLATE_PROVIDERS = stringPreferencesKey("translateProviders")
        val TRANSLATE_CACHE = stringPreferencesKey("translateCache")
        val SEEDED_REPOS = booleanPreferencesKey("seededRepos")
        val DOWNLOAD_CONCURRENCY = intPreferencesKey("downloadConcurrency")
        val SLOW_CONNECTION = booleanPreferencesKey("slowConnection")
        val PLAY_WAIT_SERVERS = booleanPreferencesKey("playWaitServers")
        val PLAY_MIN_SERVERS = intPreferencesKey("playMinServers")
        val ASK_SERVER = booleanPreferencesKey("askServerOnPlay")
        val SHOW_LOADING_BANNER = booleanPreferencesKey("showLoadingBanner")
        val SLOW_TIP_ENABLED = booleanPreferencesKey("slowTipEnabled")
        val SLOW_TIP_DONT_ASK = booleanPreferencesKey("slowTipDontAsk")
        val SLOW_TIP_LAST_DISMISS = longPreferencesKey("slowTipLastDismiss")
        val TELEGRAM_DONT_SHOW = booleanPreferencesKey("telegramDontShow")
    }

    /** Slow / mobile-data mode: raise the source-search and stream-probe
     *  timeouts and retry providers that time out, so a weak connection doesn't
     *  end in "No playable sources found". Off by default so fast connections
     *  keep their snappy timeouts. */
    fun slowConnectionFlow(): Flow<Boolean> =
        store.data.map { it[K.SLOW_CONNECTION] ?: false }

    suspend fun slowConnection(): Boolean = slowConnectionFlow().first()

    suspend fun setSlowConnection(enabled: Boolean) {
        store.edit { it[K.SLOW_CONNECTION] = enabled }
    }

    /** Playback start rule: false = start the moment the FIRST server is found
     *  (default), true = wait until [playMinServers] servers are known. The
     *  search finishing always counts as "enough", so a title with fewer
     *  servers than the requested count still plays as soon as every installed
     *  extension has answered. */
    fun playWaitServersFlow(): Flow<Boolean> =
        store.data.map { it[K.PLAY_WAIT_SERVERS] ?: false }

    suspend fun playWaitServers(): Boolean = playWaitServersFlow().first()

    suspend fun setPlayWaitServers(wait: Boolean) {
        store.edit { it[K.PLAY_WAIT_SERVERS] = wait }
    }

    /** How many servers to wait for when [playWaitServersFlow] is on (1–5). */
    fun playMinServersFlow(): Flow<Int> =
        store.data.map { (it[K.PLAY_MIN_SERVERS] ?: 2).coerceIn(1, 5) }

    suspend fun playMinServers(): Int = playMinServersFlow().first()

    suspend fun setPlayMinServers(n: Int) {
        store.edit { it[K.PLAY_MIN_SERVERS] = n.coerceIn(1, 5) }
    }

    /**
     * "Don't play directly — show all servers to choose": off (the default)
     * keeps the instant-play behaviour, where the player starts on the first
     * server it finds. On, the player opens with every server it could find,
     * divided into one section per engine (CloudStream, Hikari, Nuvio,
     * Stremio), and waits for the user to pick one instead of playing on its
     * own.
     */
    fun askServerOnPlayFlow(): Flow<Boolean> =
        store.data.map { it[K.ASK_SERVER] ?: false }

    suspend fun askServerOnPlay(): Boolean = askServerOnPlayFlow().first()

    suspend fun setAskServerOnPlay(ask: Boolean) {
        store.edit { it[K.ASK_SERVER] = ask }
    }

    /** Show the full-screen title card (backdrop + breathing name) from Play
     *  until the first frame of video. Off = the player opens straight away
     *  with just a round loading spinner. On by default. */
    fun showLoadingBannerFlow(): Flow<Boolean> =
        store.data.map { it[K.SHOW_LOADING_BANNER] ?: true }

    suspend fun showLoadingBanner(): Boolean = showLoadingBannerFlow().first()

    suspend fun setShowLoadingBanner(show: Boolean) {
        store.edit { it[K.SHOW_LOADING_BANNER] = show }
    }

    /** Whether the player may suggest turning on Slow connection mode when a
     *  play looks like it is struggling on a weak connection. On by default —
     *  a user who keeps getting a wrong "your connection looks slow" verdict
     *  turns it off here and never sees the dialog again. */
    fun slowTipEnabledFlow(): Flow<Boolean> =
        store.data.map { it[K.SLOW_TIP_ENABLED] ?: true }

    suspend fun slowTipEnabled(): Boolean = slowTipEnabledFlow().first()

    suspend fun setSlowTipEnabled(enabled: Boolean) {
        store.edit { it[K.SLOW_TIP_ENABLED] = enabled }
    }

    /** Set by the dialog's "Don't ask again" — permanent, unlike the timed
     *  cooldown of a plain dismissal. */
    fun slowTipDontAskFlow(): Flow<Boolean> =
        store.data.map { it[K.SLOW_TIP_DONT_ASK] ?: false }

    suspend fun slowTipDontAsk(): Boolean = slowTipDontAskFlow().first()

    suspend fun setSlowTipDontAsk(dontAsk: Boolean) {
        store.edit { it[K.SLOW_TIP_DONT_ASK] = dontAsk }
    }

    /** When the tip was last dismissed with "Not now" (0 = never). Keeps the
     *  dialog from reappearing on every single play. */
    fun slowTipLastDismissFlow(): Flow<Long> =
        store.data.map { it[K.SLOW_TIP_LAST_DISMISS] ?: 0L }

    suspend fun slowTipLastDismiss(): Long = slowTipLastDismissFlow().first()

    suspend fun setSlowTipLastDismiss(atMs: Long) {
        store.edit { it[K.SLOW_TIP_LAST_DISMISS] = atMs }
    }

    /** Set by the launch Telegram invitation's "Don't show this again" checkbox,
     *  so the dialog never comes back. */
    fun telegramDontShowFlow(): Flow<Boolean> =
        store.data.map { it[K.TELEGRAM_DONT_SHOW] ?: false }

    suspend fun telegramDontShow(): Boolean = telegramDontShowFlow().first()

    suspend fun setTelegramDontShow(dontShow: Boolean) {
        store.edit { it[K.TELEGRAM_DONT_SHOW] = dontShow }
    }

    /** How many downloads may run simultaneously (1–10). */
    fun downloadConcurrencyFlow(): Flow<Int> =
        store.data.map { (it[K.DOWNLOAD_CONCURRENCY] ?: 3).coerceIn(1, 10) }

    suspend fun downloadConcurrency(): Int = downloadConcurrencyFlow().first()

    suspend fun setDownloadConcurrency(n: Int) {
        store.edit { it[K.DOWNLOAD_CONCURRENCY] = n.coerceIn(1, 10) }
    }

    /** Which provider the Home screen is currently showing (empty = All). */
    fun homeProviderFlow(): Flow<String> =
        store.data.map { it[K.HOME_PROVIDER] ?: "" }

    suspend fun homeProvider(): String = homeProviderFlow().first()

    suspend fun setHomeProvider(id: String) {
        store.edit { it[K.HOME_PROVIDER] = id }
    }

    // ---- Per-extension auto-translate (WebView pages → English) ----

    /** Provider ids whose web pages are always translated to English. */
    fun translateProvidersFlow(): Flow<Set<String>> =
        store.data.map { parseStringList(it[K.TRANSLATE_PROVIDERS]).toSet() }

    suspend fun translateProviders(): Set<String> = translateProvidersFlow().first()

    suspend fun setTranslateProvider(id: String, enabled: Boolean) {
        val cur = translateProviders()
        val next = if (enabled) cur + id else cur - id
        store.edit { it[K.TRANSLATE_PROVIDERS] = encodeStringList(next.toList()) }
    }

    /** Persisted original→English translation pairs (title cache). */
    suspend fun translateCache(): List<Pair<String, String>> =
        store.data.map { parsePairs(it[K.TRANSLATE_CACHE]) }.first()

    suspend fun setTranslateCache(list: List<Pair<String, String>>) {
        store.edit { it[K.TRANSLATE_CACHE] = encodePairs(list) }
    }

    private fun encodePairs(list: List<Pair<String, String>>): String {
        val arr = JSONArray()
        for ((k, v) in list) arr.put(JSONArray().put(k).put(v))
        return arr.toString()
    }

    private fun parsePairs(s: String?): List<Pair<String, String>> {
        if (s.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(s)
            (0 until arr.length()).mapNotNull { i ->
                val pair = arr.optJSONArray(i) ?: return@mapNotNull null
                val k = pair.optString(0)
                val v = pair.optString(1)
                if (k.isBlank() || v.isBlank()) null else k to v
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun themeFlow(): Flow<String> =
        store.data.map { it[K.THEME] ?: HikariThemeMode.DARK.key }

    suspend fun theme(): String = themeFlow().first()

    suspend fun setTheme(key: String) {
        store.edit { it[K.THEME] = key }
    }

    // ---- Accent colours (app + player) ----

    /** The app UI's accent colour (Settings → Appearance → Accent). Defaults to
     *  the amber/gold the app has always used, so an existing install looks
     *  identical until the user picks something else. */
    fun appAccentFlow(): Flow<String> =
        store.data.map { it[K.APP_ACCENT] ?: HikariAccent.DEFAULT_APP.key }

    suspend fun appAccent(): String = appAccentFlow().first()

    suspend fun setAppAccent(key: String) {
        store.edit { it[K.APP_ACCENT] = key }
        syncAccents()
    }

    /** The player's own accent (used only while the app and the player are NOT
     *  linked). Defaults to the cyan→violet glow the player has always had. */
    fun playerAccentFlow(): Flow<String> =
        store.data.map { it[K.PLAYER_ACCENT] ?: HikariAccent.DEFAULT_PLAYER.key }

    suspend fun playerAccent(): String = playerAccentFlow().first()

    suspend fun setPlayerAccent(key: String) {
        store.edit { it[K.PLAYER_ACCENT] = key }
        syncAccents()
    }

    /** "Match app & player theme": while ON the player follows the app accent
     *  and picking a colour in either place recolours both. */
    fun themeLinkedFlow(): Flow<Boolean> =
        store.data.map { it[K.THEME_LINKED] ?: false }

    suspend fun themeLinked(): Boolean = themeLinkedFlow().first()

    suspend fun setThemeLinked(linked: Boolean) {
        store.edit { it[K.THEME_LINKED] = linked }
        syncAccents()
    }

    /** The accent the player should actually use right now (app accent while
     *  linked, otherwise its own). */
    suspend fun effectivePlayerAccent(): String =
        if (themeLinked()) appAccent() else playerAccent()

    /** Mirror the accent preferences into [AccentStore] so the View-based
     *  player can read them synchronously during Activity creation. */
    private suspend fun syncAccents() {
        runCatching {
            AccentStore.sync(ctx, appAccent(), playerAccent(), themeLinked())
        }
    }

    // ---- Player overlay layout & video enhance ----

    /** The player's control layout, as [com.hikari.app.player.PlayerControlsConfig]
     *  JSON (control key → slot key). Blank means "the default layout". */
    fun playerControlsFlow(): Flow<String> =
        store.data.map { it[K.PLAYER_CONTROLS] ?: "" }

    suspend fun playerControls(): String = playerControlsFlow().first()

    suspend fun setPlayerControls(json: String) {
        store.edit { it[K.PLAYER_CONTROLS] = json }
    }

    /** Video enhance preset key (see [com.hikari.app.player.EnhancePreset]). */
    fun enhancePresetFlow(): Flow<String> =
        store.data.map { it[K.PLAYER_ENHANCE] ?: EnhancePreset.DEFAULT.key }

    suspend fun enhancePreset(): String = enhancePresetFlow().first()

    suspend fun setEnhancePreset(key: String) {
        store.edit { it[K.PLAYER_ENHANCE] = key }
    }

    // ---- In-app UI scale ----

    /** When ON the app ignores the phone's Font size AND Display size settings
     *  and scales its interface with [uiScaleFlow] instead — so it looks the
     *  same on every phone. OFF (default) follows the system settings. */
    fun uiScaleEnabledFlow(): Flow<Boolean> =
        store.data.map { it[K.UI_SCALE_ENABLED] ?: false }

    suspend fun uiScaleEnabled(): Boolean = uiScaleEnabledFlow().first()

    suspend fun setUiScaleEnabled(enabled: Boolean) {
        store.edit { it[K.UI_SCALE_ENABLED] = enabled }
        // Mirror into the synchronous cache so View-based screens (player,
        // WebView) and the next cold start pick the change up immediately.
        runCatching { UiScale.sync(ctx, enabled, uiScale()) }
    }

    /** The in-app scale (0.7f–1.3f) used while [uiScaleEnabledFlow] is on. */
    fun uiScaleFlow(): Flow<Float> =
        store.data.map { (it[K.UI_SCALE_PERCENT] ?: 100).coerceIn(70, 130) / 100f }

    suspend fun uiScale(): Float = uiScaleFlow().first()

    suspend fun setUiScale(percent: Int) {
        store.edit { it[K.UI_SCALE_PERCENT] = percent.coerceIn(70, 130) }
        runCatching { UiScale.sync(ctx, uiScaleEnabled(), uiScale()) }
    }

    // ---- Ad blocking (WebView only) ----

    fun adEnabledFlow(): Flow<Boolean> =
        store.data.map { it[K.AD_ENABLED] ?: true }

    suspend fun adEnabled(): Boolean = adEnabledFlow().first()

    suspend fun setAdEnabled(enabled: Boolean) {
        store.edit { it[K.AD_ENABLED] = enabled }
    }

    fun adListsFlow(): Flow<List<AdBlocker.HostList>> =
        store.data.map { parseHostLists(it[K.AD_LISTS]) }

    suspend fun adLists(): List<AdBlocker.HostList> = adListsFlow().first()

    suspend fun setAdLists(list: List<AdBlocker.HostList>) {
        store.edit { it[K.AD_LISTS] = encodeHostLists(list) }
    }

    fun adBlockFlow(): Flow<List<String>> =
        store.data.map { parseStringList(it[K.AD_BLOCK]) }

    suspend fun adBlock(): List<String> = adBlockFlow().first()

    suspend fun setAdBlock(list: List<String>) {
        store.edit { it[K.AD_BLOCK] = encodeStringList(list) }
    }

    fun adWhiteFlow(): Flow<List<String>> =
        store.data.map { parseStringList(it[K.AD_WHITE]) }

    suspend fun adWhite(): List<String> = adWhiteFlow().first()

    suspend fun setAdWhite(list: List<String>) {
        store.edit { it[K.AD_WHITE] = encodeStringList(list) }
    }

    // ---- WebView safety (redirect + popup protection; default ON) ----

    fun webviewRedirectFlow(): Flow<Boolean> =
        store.data.map { it[K.WEBVIEW_REDIRECT] ?: true }

    suspend fun webviewRedirect(): Boolean = webviewRedirectFlow().first()

    suspend fun setWebviewRedirect(enabled: Boolean) {
        store.edit { it[K.WEBVIEW_REDIRECT] = enabled }
    }

    fun webviewPopupFlow(): Flow<Boolean> =
        store.data.map { it[K.WEBVIEW_POPUP] ?: true }

    suspend fun webviewPopup(): Boolean = webviewPopupFlow().first()

    suspend fun setWebviewPopup(enabled: Boolean) {
        store.edit { it[K.WEBVIEW_POPUP] = enabled }
    }

    /** Hosts the user allowed redirects to (blocked-elsewhere hosts allowed
     *  through). */
    fun webviewRedirectAllowFlow(): Flow<List<String>> =
        store.data.map { parseStringList(it[K.WEBVIEW_REDIRECT_ALLOW]) }

    suspend fun webviewRedirectAllow(): List<String> = webviewRedirectAllowFlow().first()

    suspend fun setWebviewRedirectAllow(list: List<String>) {
        store.edit { it[K.WEBVIEW_REDIRECT_ALLOW] = encodeStringList(list) }
    }

    // ---- WebView user agent (stock Android default vs custom) ----

    fun webviewUseDefaultUaFlow(): Flow<Boolean> =
        store.data.map { it[K.WEBVIEW_DEFAULT_UA] ?: true }

    suspend fun webviewUseDefaultUa(): Boolean = webviewUseDefaultUaFlow().first()

    fun webviewCustomUaFlow(): Flow<String> =
        store.data.map { it[K.WEBVIEW_CUSTOM_UA] ?: "" }

    suspend fun webviewCustomUa(): String = webviewCustomUaFlow().first()

    suspend fun setWebViewUa(useDefault: Boolean, customUa: String) {
        store.edit {
            it[K.WEBVIEW_DEFAULT_UA] = useDefault
            it[K.WEBVIEW_CUSTOM_UA] = customUa
        }
    }

    // ---- Universal extractor (yt-dlp fallback) ----

    fun ytdlpEnabledFlow(): Flow<Boolean> =
        store.data.map { it[K.YTDLP_ENABLED] ?: true }

    suspend fun ytdlpEnabled(): Boolean = ytdlpEnabledFlow().first()

    suspend fun setYtdlpEnabled(enabled: Boolean) {
        store.edit { it[K.YTDLP_ENABLED] = enabled }
    }

    private fun encodeHostLists(list: List<AdBlocker.HostList>): String {
        val arr = JSONArray()
        for (l in list) {
            arr.put(JSONObject().put("name", l.name).put("url", l.url))
        }
        return arr.toString()
    }

    private fun parseHostLists(s: String?): List<AdBlocker.HostList> {
        if (s.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(s)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val url = o.optString("url")
                if (url.isBlank()) null
                else AdBlocker.HostList(o.optString("name").ifBlank { url }, url)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun encodeStringList(list: List<String>): String {
        val arr = JSONArray()
        for (s in list) arr.put(s)
        return arr.toString()
    }

    private fun parseStringList(s: String?): List<String> {
        if (s.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(s)
            (0 until arr.length()).mapNotNull { arr.optString(it).ifBlank { null } }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ---- Userscripts (run inside the WebView only) ----

    fun userscriptsFlow(): Flow<List<Userscript>> =
        store.data.map { parseUserscripts(it[K.USERS]) }

    suspend fun userscripts(): List<Userscript> = userscriptsFlow().first()

    suspend fun setUserscripts(list: List<Userscript>) {
        store.edit { it[K.USERS] = encodeUserscripts(list) }
    }

    private fun parseUserscripts(s: String?): List<Userscript> {
        if (s.isNullOrBlank()) return emptyList()
        val out = mutableListOf<Userscript>()
        try {
            val arr = JSONArray(s)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val code = o.optString("code")
                if (code.isBlank()) continue
                out += Userscript(
                    id = o.optString("id"),
                    name = o.optString("name").ifBlank { "Userscript" },
                    enabled = o.optBoolean("enabled", true),
                    code = code,
                )
            }
        } catch (e: Exception) {
            return emptyList()
        }
        return out
    }

    private fun encodeUserscripts(list: List<Userscript>): String {
        val arr = JSONArray()
        for (u in list) {
            arr.put(
                JSONObject()
                    .put("id", u.id)
                    .put("name", u.name)
                    .put("enabled", u.enabled)
                    .put("code", u.code)
            )
        }
        return arr.toString()
    }

    fun providersFlow(): Flow<List<ProviderConfig>> =
        store.data.map { parseProviders(it[K.PROVIDERS]) }

    suspend fun providers(): List<ProviderConfig> = providersFlow().first()

    suspend fun saveProviders(list: List<ProviderConfig>) {
        store.edit { it[K.PROVIDERS] = encodeProviders(list) }
    }

    suspend fun addProvider(c: ProviderConfig) {
        saveProviders(providers().filter { it.id != c.id } + c)
    }

    suspend fun removeProvider(id: String) =
        saveProviders(providers().filter { it.id != id })

    suspend fun setEnabled(id: String, enabled: Boolean) {
        saveProviders(providers().map { if (it.id == id) it.copy(enabled = enabled) else it })
    }

    fun reposFlow(): Flow<List<Cs3Repo>> =
        store.data.map { parseRepos(it[K.CS3_REPOS]) }

    suspend fun repos(): List<Cs3Repo> = reposFlow().first()

    suspend fun addCs3Repo(r: Cs3Repo) {
        saveRepos(repos().filter { it.url != r.url } + r)
    }

    suspend fun removeCs3Repo(url: String) {
        saveRepos(repos().filter { it.url != url })
    }

    private suspend fun saveRepos(list: List<Cs3Repo>) {
        store.edit { it[K.CS3_REPOS] = encodeRepos(list) }
    }

    // ---- First-run extension-repo seeding ----

    /** True once the bundled default extension repos have been added. Kept so a
     *  user who deliberately removes a default repo doesn't have it pushed back
     *  on the next launch. */
    suspend fun seededRepos(): Boolean =
        store.data.map { it[K.SEEDED_REPOS] ?: false }.first()

    suspend fun markReposSeeded() {
        store.edit { it[K.SEEDED_REPOS] = true }
    }

    fun favoritesFlow(): Flow<List<MediaItem>> =
        store.data.map { parseMedia(it[K.FAVORITES]) }

    suspend fun favorites(): List<MediaItem> = favoritesFlow().first()

    suspend fun addFavorite(m: MediaItem) {
        val list = favorites().filter { it.uniqueId != m.uniqueId } + m
        store.edit { it[K.FAVORITES] = encodeMedia(list) }
    }

    suspend fun removeFavorite(id: String) {
        store.edit { it[K.FAVORITES] = encodeMedia(favorites().filter { f -> f.uniqueId != id }) }
    }

    fun sitesFlow(): Flow<List<Site>> =
        store.data.map { parseSites(it[K.SITES]) }

    suspend fun sites(): List<Site> = sitesFlow().first()

    suspend fun addSite(s: Site) {
        store.edit { it[K.SITES] = encodeSites(sites().filter { it.url != s.url } + s) }
    }

    suspend fun removeSite(url: String) {
        store.edit { it[K.SITES] = encodeSites(sites().filter { it.url != url }) }
    }

    private fun encodeSites(list: List<Site>): String {
        val arr = JSONArray()
        for (s in list) {
            arr.put(JSONObject().put("name", s.name).put("url", s.url))
        }
        return arr.toString()
    }

    private fun parseSites(s: String?): List<Site> {
        if (s.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(s)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val url = o.optString("url")
                if (url.isBlank()) null
                else Site(name = o.optString("name").ifBlank { url }, url = url)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun clearAll() {
        store.edit { it.clear() }
    }

    // ---- Watch history ----

    fun historyFlow(): Flow<List<HistoryEntry>> =
        store.data.map { parseHistory(it[K.HISTORY]) }

    suspend fun history(): List<HistoryEntry> = historyFlow().first()

    /** Insert/update one entry (deduped by [HistoryEntry.uniqueKey], newest
     *  first, capped at 200 entries). The read-modify-write happens INSIDE a
     *  single DataStore edit so the 5-second save tick and the onStop/onDestroy
     *  write can't race and drop one of two different entries. */
    suspend fun addHistory(e: HistoryEntry) {
        store.edit { prefs ->
            val cur = parseHistory(prefs[K.HISTORY])
            val next = (listOf(e) + cur.filter { it.uniqueKey != e.uniqueKey }).take(200)
            prefs[K.HISTORY] = encodeHistory(next)
        }
    }

    suspend fun clearHistory() {
        store.edit { it[K.HISTORY] = "[]" }
    }

    /** Remove ONE entry — a single movie, or a single episode of a series
     *  (episodes of one title share a mediaId, so the key is per-video). */
    suspend fun removeHistory(uniqueKey: String) {
        store.edit { prefs ->
            val cur = parseHistory(prefs[K.HISTORY])
            prefs[K.HISTORY] = encodeHistory(cur.filter { it.uniqueKey != uniqueKey })
        }
    }

    fun historyPausedFlow(): Flow<Boolean> =
        store.data.map { it[K.HISTORY_PAUSED] ?: false }

    suspend fun historyPaused(): Boolean = historyPausedFlow().first()

    suspend fun setHistoryPaused(paused: Boolean) {
        store.edit { it[K.HISTORY_PAUSED] = paused }
    }

    /** When true, the Home screen hides its "Continue Watching" row entirely
     *  (history keeps being recorded — this only hides the shelf). */
    fun hideContinueFlow(): Flow<Boolean> =
        store.data.map { it[K.HIDE_CONTINUE] ?: false }

    suspend fun hideContinue(): Boolean = hideContinueFlow().first()

    suspend fun setHideContinue(hide: Boolean) {
        store.edit { it[K.HIDE_CONTINUE] = hide }
    }

    // ---- Last-used server per video ----
    // Remembers which stream server a video was last played with (keyed exactly
    // like HistoryEntry.uniqueKey), so replaying it continues on the same
    // server — and, because that server's URL is already probe-resolved, starts
    // instantly instead of re-running the source search from scratch.

    fun lastSourcesFlow(): Flow<Map<String, LastSource>> =
        store.data.map { parseLastSources(it[K.LAST_SOURCE]) }

    /** The server this video was last played with (URL + name + the header
     *  variant that actually worked), or null. */
    suspend fun lastSource(key: String): LastSource? =
        lastSourcesFlow().first()[key]

    suspend fun setLastSource(key: String, url: String, name: String, headerVariant: Int = 0) {
        if (key.isBlank()) return
        val cur = lastSourcesFlow().first().toMutableMap()
        cur[key] = LastSource(url, name, headerVariant)
        store.edit { it[K.LAST_SOURCE] = encodeLastSources(cur) }
    }

    private fun encodeLastSources(map: Map<String, LastSource>): String {
        val obj = JSONObject()
        map.entries.toList().takeLast(300).forEach { (k, v) ->
            obj.put(
                k,
                JSONObject()
                    .put("u", v.url)
                    .put("n", v.name)
                    .put("h", v.headerVariant)
            )
        }
        return obj.toString()
    }

    private fun parseLastSources(s: String?): Map<String, LastSource> {
        if (s.isNullOrBlank()) return emptyMap()
        return try {
            val obj = JSONObject(s)
            val out = LinkedHashMap<String, LastSource>()
            obj.keys().forEach { k ->
                val o = obj.optJSONObject(k) ?: return@forEach
                val u = o.optString("u")
                val n = o.optString("n")
                val h = o.optInt("h", 0)
                if (u.isNotBlank() || n.isNotBlank()) out[k] = LastSource(u, n, h)
            }
            out
        } catch (e: Exception) {
            emptyMap()
        }
    }

    // ---- WebView element blocker (persistent CSS selectors) ----

    fun elementBlocksFlow(): Flow<List<String>> =
        store.data.map { parseStringList(it[K.ELEMENT_BLOCKS]) }

    suspend fun elementBlocks(): List<String> = elementBlocksFlow().first()

    suspend fun addElementBlock(selector: String) {
        val cur = elementBlocks()
        if (selector in cur) return
        store.edit { it[K.ELEMENT_BLOCKS] = encodeStringList((cur + selector).take(200)) }
    }

    /** Removes and returns the most recently blocked selector (null if none). */
    suspend fun removeLastElementBlock(): String? {
        val cur = elementBlocks()
        if (cur.isEmpty()) return null
        val last = cur.last()
        store.edit { it[K.ELEMENT_BLOCKS] = encodeStringList(cur.dropLast(1)) }
        return last
    }

    suspend fun clearElementBlocks() {
        store.edit { it[K.ELEMENT_BLOCKS] = "[]" }
    }

    private fun encodeHistory(list: List<HistoryEntry>): String {
        val arr = JSONArray()
        for (h in list) {
            arr.put(
                JSONObject()
                    .put("pid", h.providerId)
                    .put("id", h.mediaId)
                    .put("type", h.type.name)
                    .put("title", h.title)
                    .put("poster", h.posterUrl ?: "")
                    .put("eid", h.episodeId)
                    .put("ename", h.episodeName)
                    .put("pos", h.positionMs)
                    .put("dur", h.durationMs)
                    .put("at", h.watchedAt)
            )
        }
        return arr.toString()
    }

    private fun parseHistory(s: String?): List<HistoryEntry> {
        if (s.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(s)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id")
                if (id.isBlank()) null
                else HistoryEntry(
                    providerId = o.optString("pid"),
                    mediaId = id,
                    type = runCatching { MediaType.valueOf(o.optString("type")) }
                        .getOrDefault(MediaType.UNKNOWN),
                    title = o.optString("title"),
                    posterUrl = o.optString("poster").ifBlank { null },
                    episodeId = o.optString("eid"),
                    episodeName = o.optString("ename"),
                    positionMs = o.optLong("pos", 0L),
                    durationMs = o.optLong("dur", 0L),
                    watchedAt = o.optLong("at", 0L),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun encodeProviders(list: List<ProviderConfig>): String {
        val arr = JSONArray()
        for (c in list) {
            arr.put(
                JSONObject()
                    .put("id", c.id)
                    .put("name", c.name)
                    .put("type", c.type.name)
                    .put("url", c.url)
                    .put("iconUrl", c.iconUrl ?: "")
                    .put("enabled", c.enabled)
                    .put("extra", c.extra ?: "")
            )
        }
        return arr.toString()
    }

    private fun parseProviders(s: String?): List<ProviderConfig> {
        if (s.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(s)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                ProviderConfig(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    type = runCatching { ProviderType.valueOf(o.optString("type")) }
                        .getOrDefault(ProviderType.STREMIO),
                    url = o.optString("url"),
                    iconUrl = o.optString("iconUrl").ifBlank { null },
                    enabled = o.optBoolean("enabled", true),
                    extra = o.optString("extra").ifBlank { null },
                )
            }.filter { it.id.isNotBlank() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun encodeRepos(list: List<Cs3Repo>): String {
        val arr = JSONArray()
        for (r in list) {
            arr.put(
                JSONObject()
                    .put("url", r.url)
                    .put("name", r.name)
                    .put("description", r.description)
                    .put("kind", r.kind.name)
            )
        }
        return arr.toString()
    }

    private fun parseRepos(s: String?): List<Cs3Repo> {
        if (s.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(s)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Cs3Repo(
                    url = o.optString("url"),
                    name = o.optString("name").ifBlank { o.optString("url") },
                    description = o.optString("description"),
                    kind = runCatching { RepoKind.valueOf(o.optString("kind", "CS3")) }
                        .getOrDefault(RepoKind.CS3),
                )
            }.filter { it.url.isNotBlank() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun encodeMedia(list: List<MediaItem>): String {
        val arr = JSONArray()
        for (m in list) {
            arr.put(
                JSONObject()
                    .put("pid", m.providerId)
                    .put("id", m.id)
                    .put("title", m.title)
                    .put("type", m.type.name)
                    .put("poster", m.posterUrl ?: "")
                    .put("year", m.year ?: 0)
                    .put("overview", m.overview ?: "")
            )
        }
        return arr.toString()
    }

    private fun parseMedia(s: String?): List<MediaItem> {
        if (s.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(s)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                MediaItem(
                    providerId = o.optString("pid"),
                    id = o.optString("id"),
                    title = o.optString("title"),
                    type = runCatching { MediaType.valueOf(o.optString("type")) }
                        .getOrDefault(MediaType.UNKNOWN),
                    posterUrl = o.optString("poster").ifBlank { null },
                    year = o.optInt("year", 0).takeIf { it > 0 },
                    overview = o.optString("overview").ifBlank { null },
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
