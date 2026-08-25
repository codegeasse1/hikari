package com.hikari.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.hikari.app.net.AdBlocker
import com.hikari.app.ui.theme.HikariThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.hkDataStore by preferencesDataStore(name = "hikari")

class AppStore(private val ctx: Context) {

    private val store get() = ctx.hkDataStore

    private object K {
        val PROVIDERS = stringPreferencesKey("providers")
        val FAVORITES = stringPreferencesKey("favorites")
        val CS3_REPOS = stringPreferencesKey("cs3Repos")
        val SITES = stringPreferencesKey("sites")
        val USERS = stringPreferencesKey("userscripts")
        val THEME = stringPreferencesKey("theme")
        val HISTORY = stringPreferencesKey("history")
        val HISTORY_PAUSED = booleanPreferencesKey("historyPaused")
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
        val HOME_PROVIDER = stringPreferencesKey("homeProvider")
        val TRANSLATE_PROVIDERS = stringPreferencesKey("translateProviders")
        val TRANSLATE_CACHE = stringPreferencesKey("translateCache")
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
     *  first, capped at 200 entries). */
    suspend fun addHistory(e: HistoryEntry) {
        val next = (listOf(e) + history().filter { it.uniqueKey != e.uniqueKey }).take(200)
        store.edit { it[K.HISTORY] = encodeHistory(next) }
    }

    suspend fun clearHistory() {
        store.edit { it[K.HISTORY] = "[]" }
    }

    fun historyPausedFlow(): Flow<Boolean> =
        store.data.map { it[K.HISTORY_PAUSED] ?: false }

    suspend fun historyPaused(): Boolean = historyPausedFlow().first()

    suspend fun setHistoryPaused(paused: Boolean) {
        store.edit { it[K.HISTORY_PAUSED] = paused }
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
