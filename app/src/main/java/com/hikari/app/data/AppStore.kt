package com.hikari.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.byteArrayPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.hikari.app.net.AdBlocker
import com.hikari.app.net.DnsProviders
import com.hikari.app.net.ExtensionVerifyGuard
import com.hikari.app.player.EnhancePreset
import com.hikari.app.ui.AccentStore
import com.hikari.app.ui.UiScale
import com.hikari.app.ui.theme.HikariAccent
import com.hikari.app.ui.theme.HikariThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

/**
 * One stored preference in a backup file: its key, a one-letter type code and
 * the value itself (already flattened to something JSON can carry). See
 * [AppStore.snapshotPreferences] for why the type rides along explicitly.
 */
data class PrefRecord(
    val key: String,
    /** "s" string · "b" boolean · "i" int · "l" long · "f" float · "d" double ·
     *  "ss" set of strings · "bin" base64 bytes. */
    val type: String,
    val value: Any?,
)

class AppStore(private val ctx: Context) {

    private val store get() = ctx.hkDataStore

    private object K {
        val PROVIDERS = stringPreferencesKey("providers")
        val FAVORITES = stringPreferencesKey("favorites")
        val CS3_REPOS = stringPreferencesKey("cs3Repos")
        /** User-made collections (name + folders of catalog sources), stored as
         *  one JSON array. See [Collection]. */
        val COLLECTIONS = stringPreferencesKey("collections")
        val SITES = stringPreferencesKey("sites")
        val USERS = stringPreferencesKey("userscripts")
        val THEME = stringPreferencesKey("theme")
        val APP_ACCENT = stringPreferencesKey("appAccent")
        val PLAYER_ACCENT = stringPreferencesKey("playerAccent")
        val THEME_LINKED = booleanPreferencesKey("themeLinked")
        val PLAYER_CONTROLS = stringPreferencesKey("playerControls")
        val PLAYER_ENHANCE = stringPreferencesKey("playerEnhance")
        val PLAYER_ENHANCE_UNSUPPORTED = booleanPreferencesKey("playerEnhanceUnsupported")
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
        val CF_AUTO_SOLVE = booleanPreferencesKey("cfAutoSolve")
        val EXT_VERIFY_WEBVIEW = booleanPreferencesKey("extVerifyWebview")
        val WEBVIEW_REDIRECT_ALLOW = stringPreferencesKey("webviewRedirectAllow")
        val WEBVIEW_DEFAULT_UA = booleanPreferencesKey("webviewDefaultUa")
        val WEBVIEW_CUSTOM_UA = stringPreferencesKey("webviewCustomUa")
        val LANGUAGE = stringPreferencesKey("appLanguage")
        val HOME_PROVIDER = stringPreferencesKey("homeProvider")
        val TRANSLATE_PROVIDERS = stringPreferencesKey("translateProviders")
        val TRANSLATE_CACHE = stringPreferencesKey("translateCache")
        val SEEDED_REPOS = booleanPreferencesKey("seededRepos")
        val DOWNLOAD_CONCURRENCY = intPreferencesKey("downloadConcurrency")
        val SLOW_CONNECTION = booleanPreferencesKey("slowConnection")

        /** Resolver chosen in Settings → Network and Internet → DNS mode
         *  ([com.hikari.app.net.DnsProviders] keys), and the address a Custom
         *  choice points at. */
        val DNS_PROVIDER = stringPreferencesKey("dnsProvider")
        val CUSTOM_DNS = stringPreferencesKey("customDns")
        val PLAY_WAIT_SERVERS = booleanPreferencesKey("playWaitServers")
        val PLAY_MIN_SERVERS = intPreferencesKey("playMinServers")
        val ASK_SERVER = booleanPreferencesKey("askServerOnPlay")
        val FAILOVER_ASK = booleanPreferencesKey("failoverAskOnFailure")
        val SHOW_LOADING_BANNER = booleanPreferencesKey("showLoadingBanner")
        val SLOW_TIP_ENABLED = booleanPreferencesKey("slowTipEnabled")
        val SLOW_TIP_DONT_ASK = booleanPreferencesKey("slowTipDontAsk")
        val SLOW_TIP_LAST_DISMISS = longPreferencesKey("slowTipLastDismiss")
        val TELEGRAM_DONT_SHOW = booleanPreferencesKey("telegramDontShow")
        val HIDDEN_TABS = stringPreferencesKey("hiddenTabs")
        val APP_ICON = stringPreferencesKey("appIcon")
        /** Language TMDB titles are shown in. "" = follow the app language,
         *  "none" = leave TMDB on English, otherwise a TMDB code ("es-ES"). */
        val TMDB_LANGUAGE = stringPreferencesKey("tmdbLanguage")
        /** Key of the app-wide font (see [com.hikari.app.ui.AppFonts]). */
        val APP_FONT = stringPreferencesKey("appFont")
        /** File name (inside filesDir/fonts) of a font the user imported. */
        val APP_FONT_FILE = stringPreferencesKey("appFontFile")
        /** The imported font's own display name, for Settings. */
        val APP_FONT_LABEL = stringPreferencesKey("appFontLabel")
        /** The user's Library categories ([LibraryCategory] JSON array). */
        val LIBRARY_CATEGORIES = stringPreferencesKey("libraryCategories")
        /** uniqueId → category ids ([favoriteCategories]). */
        val FAVORITE_CATEGORIES = stringPreferencesKey("favoriteCategories")
        /** Poster & icon styling (see [com.hikari.app.ui.PosterStyle]). */
        val POSTER_BLUR = intPreferencesKey("posterBlur")
        val POSTER_CORNER = intPreferencesKey("posterCorner")
        val POSTER_SHOW_TITLES = booleanPreferencesKey("posterShowTitles")
        val POSTER_SHOW_RATINGS = booleanPreferencesKey("posterShowRatings")
        val POSTER_GLASS = booleanPreferencesKey("posterGlass")
        /** The animated/visual treatment drawn over every poster card — see
         *  [com.hikari.app.ui.PosterEffects]. A SET, so a card can wear several
         *  treatments at once. The old single-key preference ([POSTER_EFFECT])
         *  is still read as the starting set, so nobody's choice is lost. */
        val POSTER_EFFECTS = stringSetPreferencesKey("posterEffects")
        /** A single treatment stored by a build before multi-select existed. */
        val POSTER_EFFECT = stringPreferencesKey("posterEffect")
        /** Shape of Home's featured banner — see
         *  [com.hikari.app.ui.components.HeroStyles]. */
        val HERO_STYLE = stringPreferencesKey("heroStyle")
        /** Extra lines the featured banner may draw over its artwork. */
        val HERO_OVERVIEW = booleanPreferencesKey("heroOverview")
        val HERO_RATING = booleanPreferencesKey("heroRating")
        val HERO_META = booleanPreferencesKey("heroMeta")
        /** How the detail page's header art is laid out — see
         *  [com.hikari.app.ui.screens.DetailHeroStyles]. */
        val DETAIL_HERO_STYLE = stringPreferencesKey("detailHeroStyle")
        /** Which player control shell the player wears — see
         *  [com.hikari.app.player.PlayerSkins]. */
        val PLAYER_SKIN = stringPreferencesKey("playerSkin")
        /** Look of the "finding your server" card — see
         *  [com.hikari.app.ui.LoadingStyles]. */
        val LOADING_STYLE = stringPreferencesKey("loadingStyle")
        /** The treatment drawn over the loading card (a sheen, an aura ring, a
         *  gallery frame, an accent bloom) — see
         *  [com.hikari.app.ui.LoadingEffects]. A SET, so several treatments can
         *  be on at once. The old single-key preference ([LOADING_EFFECT]) is
         *  still read as the starting set. */
        val LOADING_EFFECTS = stringSetPreferencesKey("loadingEffects")
        /** A single treatment stored by a build before multi-select existed. */
        val LOADING_EFFECT = stringPreferencesKey("loadingEffect")
        /** The colour a poster card's "Aura ring" is drawn in — see
         *  [com.hikari.app.ui.AuraColors]. "theme" (the default) follows the app
         *  accent, which is what the ring always drew. */
        val POSTER_AURA_COLOR = stringPreferencesKey("posterAuraColor")
        /** The same choice for the loading screen's own aura ring. Kept apart
         *  from the poster's so the two screens can differ. */
        val LOADING_AURA_COLOR = stringPreferencesKey("loadingAuraColor")
        /** "Search every installed extension" (Settings → Playback → Server
         *  search). On by default: a title is searched across every installed
         *  extension, the way it always has been. Off, only the extension the
         *  title was opened from is asked — CloudStream's own model, where a
         *  film plays from the repo you picked and nowhere else. See
         *  [com.hikari.app.data.SearchScope]. */
        val SEARCH_ALL_EXTENSIONS = booleanPreferencesKey("searchAllExtensions")
        /** "Exception extensions" (Settings → Playback & Servers → Server
         *  search): extensions that are asked for servers for EVERY title, even
         *  when [SEARCH_ALL_EXTENSIONS] is off. See
         *  [com.hikari.app.data.SearchScope] for the one rule that goes with it
         *  — a title opened FROM an exception extension plays from that
         *  extension alone. */
        val SEARCH_EXCEPTION_ON = booleanPreferencesKey("searchExceptionOn")
        val SEARCH_EXCEPTION_IDS = stringSetPreferencesKey("searchExceptionIds")
        /** ENTIRE ENGINES marked as exceptions, by [ProviderType] name
         *  ("CS3", "HIKARI", …). See [searchExceptionTypesFlow]. */
        val SEARCH_EXCEPTION_TYPES = stringSetPreferencesKey("searchExceptionTypes")
        /** Extensions left OUT of a marked engine ("all of CloudStream, except
         *  these"). See [searchExceptionExcludesFlow]. */
        val SEARCH_EXCEPTION_EXCLUDES = stringSetPreferencesKey("searchExceptionExcludes")
        /** Bottom navigation bar layout — see [com.hikari.app.ui.navigation.NavStyles]:
         *  "classic" | "floating" | "animated" (an old stored "borderless" is
         *  upgraded to "animated" when read). */
        val NAV_STYLE = stringPreferencesKey("navBarStyle")
        /** Draw the icon labels ("Home", "Library", …) under the taskbar's
         *  buttons. On by default; off leaves icon-only buttons. */
        val TAB_LABELS = booleanPreferencesKey("tabLabels")
        /** Draw the rating strip on the detail page (IMDb, RT, …). On by
         *  default: a title's score is part of what the page is for. */
        val SHOW_DETAIL_RATING = booleanPreferencesKey("showDetailRating")
        /** "Turn off full screen app mode": keep the system status bar and the
         *  three-button navigation bar visible everywhere instead of hiding
         *  them behind an immersive, swipe-to-reveal fullscreen. Off = the app
         *  is immersive (the way it ships); on = normal windowed layout. */
        val FULLSCREEN_OFF = booleanPreferencesKey("fullscreenOff")
    }

    // ---- Settings writes ----
    //
    // Every write a setting makes goes through [write] below rather than
    // `store.edit` directly. Two reasons, both from the "I pick an option and it
    // stays on the old one" report:
    //
    //  - a write can FAIL. DataStore writes the whole preferences file, so a
    //    full disk, a momentarily busy file system or a wedged read leaves the
    //    user's choice unsaved, the live flow keeps emitting the old value, and
    //    the toggle in front of them flips back — silently, because every call
    //    site wrapped it in `runCatching`. Retrying and then SAYING SO is the
    //    difference between a diagnosable bug and a haunted app;
    //  - a failure needs to be visible in the shared log (and, once, on screen),
    //    or the only evidence is a setting that will not stay put.

    /** Attempts made at one settings write before giving up and telling the
     *  user. A failed DataStore write is almost always transient. */
    private val WRITE_TRIES = 3

    /** At most one "couldn't save" toast a minute, so broken storage cannot put
     *  a stream of them on screen. */
    private val WRITE_WARNING_COOLDOWN_MS = 60_000L

    @Volatile
    private var lastWriteWarningAt = 0L

    /**
     * Writes settings, retrying a failed attempt and reporting one that keeps
     * failing — see the note above. [what] names the setting for the log line
     * and the warning.
     */
    private suspend fun write(
        what: String,
        transform: suspend (androidx.datastore.preferences.core.MutablePreferences) -> Unit,
    ) {
        var last: Throwable? = null
        for (attempt in 1..WRITE_TRIES) {
            try {
                store.edit(transform)
                if (attempt > 1) Logs.log("Store", "saved $what on attempt $attempt")
                return
            } catch (c: kotlinx.coroutines.CancellationException) {
                // The caller went away (the screen was left mid-save): not a
                // failure, and retrying a cancelled write is meaningless.
                throw c
            } catch (t: Throwable) {
                last = t
                Logs.log(
                    "Store",
                    "✗ could not save $what (${t.javaClass.simpleName}: ${t.message}) — " +
                        "attempt $attempt of $WRITE_TRIES",
                )
                kotlinx.coroutines.delay(250L * attempt)
            }
        }
        Logs.log(
            "Store",
            "✗✗ GAVE UP saving $what — the choice will revert. Last error: " +
                "${last?.javaClass?.simpleName}: ${last?.message}. " +
                "If this repeats, the device is out of space or the app's data is unreadable.",
        )
        warnWriteFailure(what)
    }

    /** Says, once in a while, that a choice could not be saved. Silence is what
     *  made this look like the app ignoring its own settings. */
    private fun warnWriteFailure(what: String) {
        val now = System.currentTimeMillis()
        if (now - lastWriteWarningAt < WRITE_WARNING_COOLDOWN_MS) return
        lastWriteWarningAt = now
        runCatching {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                runCatching {
                    android.widget.Toast.makeText(
                        ctx,
                        "Hikari couldn't save that setting ($what). Check the device's free space.",
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    // ---- The launcher icon the user picked (see AppIconManager) ----
    /** Key of the launcher-icon variant in use — one of
     *  [com.hikari.app.ui.AppIconVariants]. The choice lives in the manifest as
     *  the enabled `activity-alias`, so this mirror is what lets the app put the
     *  manifest back in sync after a backup restore drops the setting. */
    fun appIconFlow(): Flow<String> =
        store.data.map { it[K.APP_ICON] ?: com.hikari.app.ui.AppIconManager.DEFAULT_KEY }

    suspend fun appIcon(): String = appIconFlow().first()

    suspend fun setAppIcon(key: String) {
        write("APP_ICON") { it[K.APP_ICON] = key }
    }

    // ---- Title language for TMDB metadata ----
    // The app language already localises Hikari's own interface; this decides
    // whether TMDB's titles and descriptions follow it too. "" = follow the app
    // language, "none" = stay on English, otherwise an explicit TMDB code.

    fun tmdbLanguageFlow(): Flow<String> =
        store.data.map { it[K.TMDB_LANGUAGE] ?: "" }

    suspend fun tmdbLanguage(): String = tmdbLanguageFlow().first()

    suspend fun setTmdbLanguage(mode: String) {
        write("TMDB_LANGUAGE") { it[K.TMDB_LANGUAGE] = mode.trim() }
    }

    // ---- App-wide font (Settings → Appearance & Theme → App font) ----

    /** Key of the chosen font in [com.hikari.app.ui.AppFonts.CHOICES]. */
    fun appFontFlow(): Flow<String> =
        store.data.map { it[K.APP_FONT] ?: com.hikari.app.ui.AppFonts.DEFAULT }

    suspend fun appFont(): String = appFontFlow().first()

    suspend fun setAppFont(key: String) {
        write("APP_FONT") { it[K.APP_FONT] = key }
    }

    /** File name (inside `filesDir/fonts`) of a font the user imported, or "".
     *  Only meaningful while [appFontFlow] is [com.hikari.app.ui.AppFonts.IMPORTED]. */
    fun appFontFileFlow(): Flow<String> =
        store.data.map { it[K.APP_FONT_FILE] ?: "" }

    suspend fun appFontFile(): String = appFontFileFlow().first()

    /** The imported font's own name (the file's `familyName`), for Settings. */
    fun appFontLabelFlow(): Flow<String> =
        store.data.map { it[K.APP_FONT_LABEL] ?: "" }

    suspend fun appFontLabel(): String = appFontLabelFlow().first()

    suspend fun setImportedFont(fileName: String, label: String) {
        write("APP_FONT_FILE") {
            it[K.APP_FONT_FILE] = fileName
            it[K.APP_FONT_LABEL] = label
        }
    }

    // ---- Library categories ----

    /** The categories that exist right now (seeded with four on first read). */
    fun libraryCategoriesFlow(): Flow<List<LibraryCategory>> =
        store.data.map { parseCategories(it[K.LIBRARY_CATEGORIES]) }

    suspend fun libraryCategories(): List<LibraryCategory> = libraryCategoriesFlow().first()

    suspend fun saveLibraryCategories(list: List<LibraryCategory>) {
        write("LIBRARY_CATEGORIES") { it[K.LIBRARY_CATEGORIES] = encodeCategories(list) }
    }

    suspend fun addLibraryCategory(name: String): LibraryCategory {
        val c = LibraryCategory(newId("cat"), name.trim())
        saveLibraryCategories(libraryCategories() + c)
        return c
    }

    /** Renames a category in place — every title filed under it follows. */
    suspend fun renameLibraryCategory(id: String, name: String) {
        saveLibraryCategories(
            libraryCategories().map { if (it.id == id) it.copy(name = name.trim()) else it }
        )
    }

    /** Deletes a category and unfiles every title that was in it. */
    suspend fun removeLibraryCategory(id: String) {
        saveLibraryCategories(libraryCategories().filter { it.id != id })
        write("FAVORITE_CATEGORIES") { prefs ->
            val cur = parseCategoryMap(prefs[K.FAVORITE_CATEGORIES])
            prefs[K.FAVORITE_CATEGORIES] = encodeCategoryMap(
                cur.mapValues { (_, set) -> set - id }.filterValues { it.isNotEmpty() }
            )
        }
    }

    /** Title uniqueId → the category ids it is filed under. */
    fun favoriteCategoriesFlow(): Flow<Map<String, Set<String>>> =
        store.data.map { parseCategoryMap(it[K.FAVORITE_CATEGORIES]) }

    suspend fun favoriteCategories(): Map<String, Set<String>> = favoriteCategoriesFlow().first()

    /** Replaces one title's filing. An empty set simply forgets the title. */
    suspend fun setFavoriteCategories(uniqueId: String, categories: Set<String>) {
        if (uniqueId.isBlank()) return
        write("FAVORITE_CATEGORIES") { prefs ->
            val cur = parseCategoryMap(prefs[K.FAVORITE_CATEGORIES]).toMutableMap()
            if (categories.isEmpty()) cur.remove(uniqueId) else cur[uniqueId] = categories
            prefs[K.FAVORITE_CATEGORIES] = encodeCategoryMap(cur)
        }
    }

    /** Adds categories to whatever a title is already filed under (the
     *  "Add to library" prompt must never silently unfile something). */
    suspend fun addFavoriteCategories(uniqueId: String, categories: Set<String>) {
        if (uniqueId.isBlank() || categories.isEmpty()) return
        val cur = favoriteCategories()[uniqueId].orEmpty()
        setFavoriteCategories(uniqueId, cur + categories)
    }

    /**
     * The look the app ships with — what a fresh install shows before the user
     * has touched anything (Settings → App Layout).
     *
     * These are the defaults the reference clients are known for, so Hikari
     * looks its best out of the box instead of asking the user to go and find
     * the styling screen first:
     *
     *  - posters wear the coloured halo + gallery frame, rounded and badged;
     *  - Home's featured banner is the side-by-side Showcase;
     *  - the detail page opens on "Art + poster";
     *  - the player wears the Neon skin;
     *  - the loading screen is the title's poster card with a sheen across it.
     *
     * They are only DEFAULTS: every one of them is a normal setting, and a user
     * who picks something else keeps it (a stored value always wins — see each
     * getter below).
     */
    private companion object ShipDefaults {
        /** dp of halo behind each poster. */
        const val DEFAULT_POSTER_BLUR = 15

        /** dp of corner rounding on each poster. */
        const val DEFAULT_POSTER_CORNER = 28

        /** The signature card treatment ([com.hikari.app.ui.PosterEffects]). */
        val DEFAULT_POSTER_EFFECT = com.hikari.app.ui.PosterEffects.FRAME

        /** The signature card treatmentS — a first-run poster wears the same
         *  gallery frame as before. */
        val DEFAULT_POSTER_EFFECTS: Set<String> = setOf(DEFAULT_POSTER_EFFECT)

        /** Home's featured banner shape ([HeroStyles]). */
        const val DEFAULT_HERO_STYLE = com.hikari.app.ui.components.HeroStyles.SHOWCASE

        /** The detail page's header art ([DetailHeroStyles]). */
        const val DEFAULT_DETAIL_HERO_STYLE = com.hikari.app.ui.screens.DetailHeroStyles.SIDE

        /** The player's control shell ([PlayerSkins]). */
        const val DEFAULT_PLAYER_SKIN = com.hikari.app.player.PlayerSkins.NEON

        /** The look of the "finding your server" card ([LoadingStyles]). Poster
         *  card: the title's own poster on a glass card, so the first thing a
         *  new user sees while a server is found is the film's artwork. */
        const val DEFAULT_LOADING_STYLE = com.hikari.app.ui.LoadingStyles.POSTER

        /** The treatments over that card ([LoadingEffects]) — a single sweep of
         *  light, which is the least busy of them and reads well on every
         *  style. */
        val DEFAULT_LOADING_EFFECTS: Set<String> =
            setOf(com.hikari.app.ui.LoadingEffects.SHEEN)
    }

    // ---- Poster & icon styling ----

    /** Backdrop blur radius in dp behind a poster (0 = the plain art). */
    fun posterBlurFlow(): Flow<Int> =
        store.data.map { (it[K.POSTER_BLUR] ?: DEFAULT_POSTER_BLUR).coerceIn(0, 24) }

    suspend fun posterBlur(): Int = posterBlurFlow().first()

    suspend fun setPosterBlur(value: Int) {
        write("POSTER_BLUR") { it[K.POSTER_BLUR] = value.coerceIn(0, 24) }
    }

    /** Poster corner rounding in dp. */
    fun posterCornerFlow(): Flow<Int> =
        store.data.map { (it[K.POSTER_CORNER] ?: DEFAULT_POSTER_CORNER).coerceIn(0, 28) }

    suspend fun posterCorner(): Int = posterCornerFlow().first()

    suspend fun setPosterCorner(value: Int) {
        write("POSTER_CORNER") { it[K.POSTER_CORNER] = value.coerceIn(0, 28) }
    }

    fun posterShowTitlesFlow(): Flow<Boolean> =
        store.data.map { it[K.POSTER_SHOW_TITLES] ?: true }

    suspend fun posterShowTitles(): Boolean = posterShowTitlesFlow().first()

    suspend fun setPosterShowTitles(show: Boolean) {
        write("POSTER_SHOW_TITLES") { it[K.POSTER_SHOW_TITLES] = show }
    }

    fun posterShowRatingsFlow(): Flow<Boolean> =
        store.data.map { it[K.POSTER_SHOW_RATINGS] ?: true }

    suspend fun posterShowRatings(): Boolean = posterShowRatingsFlow().first()

    suspend fun setPosterShowRatings(show: Boolean) {
        write("POSTER_SHOW_RATINGS") { it[K.POSTER_SHOW_RATINGS] = show }
    }

    /** The glass hairline + soft sheen over every poster. */
    fun posterGlassFlow(): Flow<Boolean> =
        store.data.map { it[K.POSTER_GLASS] ?: true }

    suspend fun posterGlass(): Boolean = posterGlassFlow().first()

    suspend fun setPosterGlass(on: Boolean) {
        write("POSTER_GLASS") { it[K.POSTER_GLASS] = on }
    }

    /** The visual treatment(s) drawn over every poster card. */
    fun posterEffectsFlow(): Flow<Set<String>> =
        store.data.map { prefs ->
            com.hikari.app.ui.PosterEffects.normalizeSet(
                prefs[K.POSTER_EFFECTS]
                    // A choice made before multi-select: the single stored key
                    // becomes a one-member set, so nobody's setting is lost.
                    ?: com.hikari.app.ui.PosterEffects.parse(prefs[K.POSTER_EFFECT])
                        .ifEmpty { DEFAULT_POSTER_EFFECTS },
            )
        }

    suspend fun posterEffects(): Set<String> = posterEffectsFlow().first()

    suspend fun setPosterEffects(keys: kotlin.collections.Collection<String>) {
        // The legacy single key is CLEARED, not left behind: it is only ever read
        // as the first-run/upgrade value, and a stale copy would resurrect an
        // old choice if the new set were ever emptied by a restore.
        write("POSTER_EFFECTS") {
            it[K.POSTER_EFFECTS] = com.hikari.app.ui.PosterEffects.normalizeSet(keys)
            it.remove(K.POSTER_EFFECT)
        }
    }

    // ---- Home's featured banner ----

    /** How the featured banner is shaped: the carousel, a full-width spotlight,
     *  a compact strip or the side-by-side showcase. */
    fun heroStyleFlow(): Flow<String> =
        store.data.map {
            com.hikari.app.ui.components.HeroStyles.normalize(
                it[K.HERO_STYLE] ?: DEFAULT_HERO_STYLE,
            )
        }

    suspend fun heroStyle(): String = heroStyleFlow().first()

    suspend fun setHeroStyle(key: String) {
        write("HERO_STYLE") { it[K.HERO_STYLE] = com.hikari.app.ui.components.HeroStyles.normalize(key) }
    }

    fun heroOverviewFlow(): Flow<Boolean> = store.data.map { it[K.HERO_OVERVIEW] ?: true }

    suspend fun heroOverview(): Boolean = heroOverviewFlow().first()

    suspend fun setHeroOverview(on: Boolean) {
        write("HERO_OVERVIEW") { it[K.HERO_OVERVIEW] = on }
    }

    fun heroRatingFlow(): Flow<Boolean> = store.data.map { it[K.HERO_RATING] ?: true }

    suspend fun heroRating(): Boolean = heroRatingFlow().first()

    suspend fun setHeroRating(on: Boolean) {
        write("HERO_RATING") { it[K.HERO_RATING] = on }
    }

    fun heroMetaFlow(): Flow<Boolean> = store.data.map { it[K.HERO_META] ?: true }

    suspend fun heroMeta(): Boolean = heroMetaFlow().first()

    suspend fun setHeroMeta(on: Boolean) {
        write("HERO_META") { it[K.HERO_META] = on }
    }

    /** How the detail page's header art is laid out. */
    fun detailHeroStyleFlow(): Flow<String> =
        store.data.map {
            com.hikari.app.ui.screens.DetailHeroStyles.normalize(
                it[K.DETAIL_HERO_STYLE] ?: DEFAULT_DETAIL_HERO_STYLE,
            )
        }

    suspend fun detailHeroStyle(): String = detailHeroStyleFlow().first()

    suspend fun setDetailHeroStyle(key: String) {
        write("DETAIL_HERO_STYLE") { it[K.DETAIL_HERO_STYLE] = com.hikari.app.ui.screens.DetailHeroStyles.normalize(key) }
    }

    /** Which player control shell the player wears. */
    fun playerSkinFlow(): Flow<String> =
        store.data.map {
            com.hikari.app.player.PlayerSkins.normalize(
                it[K.PLAYER_SKIN] ?: DEFAULT_PLAYER_SKIN,
            )
        }

    suspend fun playerSkin(): String = playerSkinFlow().first()

    suspend fun setPlayerSkin(key: String) {
        write("PLAYER_SKIN") { it[K.PLAYER_SKIN] = com.hikari.app.player.PlayerSkins.normalize(key) }
    }

    // ---- The "finding your server" card (Settings → App Layout) ----

    /** Which look the loading card wears — see
     *  [com.hikari.app.ui.LoadingStyles]. One choice, two screens: the detail
     *  page shows the card from the tap, and the player continues with the same
     *  look until the video is on screen. */
    fun loadingStyleFlow(): Flow<String> =
        store.data.map {
            com.hikari.app.ui.LoadingStyles.normalize(it[K.LOADING_STYLE] ?: DEFAULT_LOADING_STYLE)
        }

    suspend fun loadingStyle(): String = loadingStyleFlow().first()

    suspend fun setLoadingStyle(key: String) {
        write("LOADING_STYLE") { it[K.LOADING_STYLE] = com.hikari.app.ui.LoadingStyles.normalize(key) }
    }

    /** The treatment(s) drawn over the loading card — see
     *  [com.hikari.app.ui.LoadingEffects]. Works with every style, so the
     *  quietest card can wear the same kind of signature detail a poster does,
     *  and several treatments can be on at once. */
    fun loadingEffectsFlow(): Flow<Set<String>> =
        store.data.map { prefs ->
            com.hikari.app.ui.LoadingEffects.normalizeSet(
                prefs[K.LOADING_EFFECTS]
                    ?: com.hikari.app.ui.LoadingEffects.parse(prefs[K.LOADING_EFFECT])
                        .ifEmpty { DEFAULT_LOADING_EFFECTS },
            )
        }

    suspend fun loadingEffects(): Set<String> = loadingEffectsFlow().first()

    suspend fun setLoadingEffects(keys: kotlin.collections.Collection<String>) {
        write("LOADING_EFFECTS") {
            it[K.LOADING_EFFECTS] = com.hikari.app.ui.LoadingEffects.normalizeSet(keys)
            it.remove(K.LOADING_EFFECT)
        }
    }

    /** The colour a poster card's aura ring is drawn in ([AuraColors.THEME] =
     *  follow the app accent). */
    fun posterAuraColorFlow(): Flow<String> =
        store.data.map {
            com.hikari.app.ui.AuraColors.normalize(it[K.POSTER_AURA_COLOR])
        }

    suspend fun posterAuraColor(): String = posterAuraColorFlow().first()

    suspend fun setPosterAuraColor(key: String) {
        write("POSTER_AURA_COLOR") {
            it[K.POSTER_AURA_COLOR] = com.hikari.app.ui.AuraColors.normalize(key)
        }
    }

    /** The colour the loading screen's own aura ring is drawn in. */
    fun loadingAuraColorFlow(): Flow<String> =
        store.data.map {
            com.hikari.app.ui.AuraColors.normalize(it[K.LOADING_AURA_COLOR])
        }

    suspend fun loadingAuraColor(): String = loadingAuraColorFlow().first()

    suspend fun setLoadingAuraColor(key: String) {
        write("LOADING_AURA_COLOR") {
            it[K.LOADING_AURA_COLOR] = com.hikari.app.ui.AuraColors.normalize(key)
        }
    }

    // ---- Server search (Settings → Playback) ----

    /**
     * May a lookup ask extensions OTHER than the one the title was opened from?
     *
     * On by default, which is the behaviour the app has always had: every
     * installed extension is searched and the player's server list gathers
     * whatever all of them found. Off, a title is searched ONLY through its own
     * extension — the CloudStream model, where a film plays from the repo you
     * picked and its own hosts, and the other ~250 extensions are left alone
     * entirely (no cross search, no background sweep, no episode list borrowed
     * from another site).
     */
    fun searchAllExtensionsFlow(): Flow<Boolean> =
        store.data.map { it[K.SEARCH_ALL_EXTENSIONS] ?: true }

    suspend fun searchAllExtensions(): Boolean = searchAllExtensionsFlow().first()

    suspend fun setSearchAllExtensions(all: Boolean) {
        write("SEARCH_ALL_EXTENSIONS") { it[K.SEARCH_ALL_EXTENSIONS] = all }
    }

    /**
     * Are the "exception extensions" in force?
     *
     * On, the extensions picked in Settings (see [searchExceptionIds]) are asked
     * for servers for EVERY title — even with [searchAllExtensions] off — while
     * a title opened FROM one of them plays from that extension alone. Off (the
     * default), they change nothing at all.
     */
    fun searchExceptionOnFlow(): Flow<Boolean> =
        store.data.map { it[K.SEARCH_EXCEPTION_ON] ?: false }

    suspend fun searchExceptionOn(): Boolean = searchExceptionOnFlow().first()

    suspend fun setSearchExceptionOn(on: Boolean) {
        write("SEARCH_EXCEPTION_ON") { it[K.SEARCH_EXCEPTION_ON] = on }
    }

    /** The ids of the extensions chosen as exceptions (see [searchExceptionOnFlow]). */
    fun searchExceptionIdsFlow(): Flow<Set<String>> =
        store.data.map { it[K.SEARCH_EXCEPTION_IDS] ?: emptySet() }
    suspend fun searchExceptionIds(): Set<String> = searchExceptionIdsFlow().first()

    suspend fun setSearchExceptionIds(ids: kotlin.collections.Collection<String>) {
        write("SEARCH_EXCEPTION_IDS") { it[K.SEARCH_EXCEPTION_IDS] = ids.toSet() }
    }

    /**
     * WHOLE ENGINES marked as exceptions, by [ProviderType] name ("CS3",
     * "HIKARI", "STREMIO", …).
     *
     * A user who wants "every CloudStream repo, always" should not have to tick
     * a hundred and fifty rows — and, worse, a repo they install NEXT WEEK was
     * not in the ticked list, so the choice silently went stale. An engine
     * choice keeps applying to whatever of that engine is installed later, and
     * several engines can be marked at once (CloudStream *and* Hikari), which
     * the row-by-row picker could not express either.
     */
    fun searchExceptionTypesFlow(): Flow<Set<String>> =
        store.data.map { it[K.SEARCH_EXCEPTION_TYPES] ?: emptySet() }

    suspend fun searchExceptionTypes(): Set<String> = searchExceptionTypesFlow().first()

    suspend fun setSearchExceptionTypes(types: kotlin.collections.Collection<String>) {
        write("SEARCH_EXCEPTION_TYPES") { it[K.SEARCH_EXCEPTION_TYPES] = types.toSet() }
    }

    /**
     * Extensions explicitly LEFT OUT of a marked engine: "all of CloudStream,
     * except these two".
     *
     * Marking an engine covers everything installed for it, including next
     * week's install — but it also has to be possible to say no to one of them,
     * or the only way to exclude a single CloudStream repo would be to unmark
     * the whole engine and tick 150 rows by hand.
     */
    fun searchExceptionExcludesFlow(): Flow<Set<String>> =
        store.data.map { it[K.SEARCH_EXCEPTION_EXCLUDES] ?: emptySet() }

    suspend fun searchExceptionExcludes(): Set<String> = searchExceptionExcludesFlow().first()

    suspend fun setSearchExceptionExcludes(ids: kotlin.collections.Collection<String>) {
        write("SEARCH_EXCEPTION_EXCLUDES") { it[K.SEARCH_EXCEPTION_EXCLUDES] = ids.toSet() }
    }

    /**
     * The exception extensions that are actually IN FORCE: the chosen ids, the
     * extensions of every chosen ENGINE, or nothing when the switch is off. This
     * is the single value the search mirrors into
     * [com.hikari.app.data.SearchScope.exceptions], so the switch, the engine
     * chips and the list can never disagree mid-lookup.
     *
     * Engine exceptions are resolved against the provider list HERE, on every
     * emission, so an extension installed after the engine was marked is an
     * exception from the moment it exists (no stale list of ids to maintain),
     * and uninstalling one simply drops it.
     */
    fun activeSearchExceptionsFlow(): Flow<Set<String>> =
        store.data.map { prefs ->
            if (prefs[K.SEARCH_EXCEPTION_ON] != true) return@map emptySet<String>()
            val ids = prefs[K.SEARCH_EXCEPTION_IDS] ?: emptySet()
            val types = prefs[K.SEARCH_EXCEPTION_TYPES] ?: emptySet()
            val excluded = prefs[K.SEARCH_EXCEPTION_EXCLUDES] ?: emptySet()
            if (types.isEmpty()) return@map ids - excluded
            val byEngine = parseProviders(prefs[K.PROVIDERS])
                .filter { it.type.name in types }
                .map { it.id }
            (ids + byEngine) - excluded
        }

    // ---- Bottom navigation bar layout ----

    /** "classic" (the seamless tonal plate), "floating" (a detached glass pill)
     *  or "animated" (full bar at the top of a page, floating pill once you
     *  scroll — see [com.hikari.app.ui.navigation.NavStyles]). */
    fun navStyleFlow(): Flow<String> =
        store.data.map {
            com.hikari.app.ui.navigation.NavStyles.normalize(
                it[K.NAV_STYLE] ?: com.hikari.app.ui.navigation.NavStyles.ANIMATED
            )
        }

    suspend fun navStyle(): String = navStyleFlow().first()

    suspend fun setNavStyle(style: String) {
        write("NAV_STYLE") { it[K.NAV_STYLE] = style }
    }

    // ---- The taskbar's icon labels (Settings → App Layout) ----

    /** Whether the bottom bar writes each button's name under its icon. On by
     *  default — that is how the bar ships. */
    fun tabLabelsFlow(): Flow<Boolean> =
        store.data.map { it[K.TAB_LABELS] ?: true }

    suspend fun tabLabels(): Boolean = tabLabelsFlow().first()

    suspend fun setTabLabels(show: Boolean) {
        write("TAB_LABELS") { it[K.TAB_LABELS] = show }
    }

    // ---- The detail page's rating strip (Settings → App Layout) ----

    /** Whether the detail page draws the IMDb/RT/… badges. On unless the user
     *  turned it off (see [setShowDetailRating]). */
    fun showDetailRatingFlow(): Flow<Boolean> =
        store.data.map { it[K.SHOW_DETAIL_RATING] ?: true }

    suspend fun showDetailRating(): Boolean = showDetailRatingFlow().first()

    suspend fun setShowDetailRating(show: Boolean) {
        write("SHOW_DETAIL_RATING") { it[K.SHOW_DETAIL_RATING] = show }
    }

    // ---- Full screen app mode (Settings → App Layout) ----

    /** True when the user asked for the normal, windowed layout: system status
     *  bar and the phone's own navigation bar visible on every screen. */
    fun fullscreenOffFlow(): Flow<Boolean> =
        store.data.map { it[K.FULLSCREEN_OFF] ?: false }

    suspend fun fullscreenOff(): Boolean = fullscreenOffFlow().first()

    suspend fun setFullscreenOff(off: Boolean) {
        write("FULLSCREEN_OFF") { it[K.FULLSCREEN_OFF] = off }
    }

    // ---- The floating bottom bar: which tab buttons the user keeps ----

    /** Routes of the bottom-bar tabs the user has switched off (see
     *  [com.hikari.app.ui.navigation.BottomTabs]). Hiding one only removes its
     *  button — the screen itself stays reachable from inside the app. */
    fun hiddenTabsFlow(): Flow<Set<String>> =
        store.data.map { parseStringList(it[K.HIDDEN_TABS]).toSet() }

    suspend fun hiddenTabs(): Set<String> = hiddenTabsFlow().first()

    suspend fun setTabHidden(route: String, hidden: Boolean) {
        val cur = hiddenTabs()
        val next = if (hidden) cur + route else cur - route
        write("HIDDEN_TABS") { it[K.HIDDEN_TABS] = encodeStringList(next.toList()) }
    }

    /** Slow / mobile-data mode: raise the source-search and stream-probe
     *  timeouts and retry providers that time out, so a weak connection doesn't
     *  end in "No playable sources found". Off by default so fast connections
     *  keep their snappy timeouts. */
    fun slowConnectionFlow(): Flow<Boolean> =
        store.data.map { it[K.SLOW_CONNECTION] ?: false }

    suspend fun slowConnection(): Boolean = slowConnectionFlow().first()

    suspend fun setSlowConnection(enabled: Boolean) {
        write("SLOW_CONNECTION") { it[K.SLOW_CONNECTION] = enabled }
    }

    /** The chosen resolver ([com.hikari.app.net.DnsProviders] key). Default is
     *  "system" — the phone's own DNS, with Hikari's encrypted fallback, i.e.
     *  exactly the app's behaviour before this setting existed. */
    fun dnsProviderFlow(): Flow<String> =
        store.data.map { it[K.DNS_PROVIDER] ?: DnsProviders.SYSTEM }

    suspend fun dnsProvider(): String = dnsProviderFlow().first()

    suspend fun setDnsProvider(key: String) {
        write("DNS_PROVIDER") { it[K.DNS_PROVIDER] = key }
    }

    /** What a Custom DNS choice points at, as the user typed it (normalised to
     *  an endpoint by [DnsProviders.customEndpoint] when it is used). */
    fun customDnsFlow(): Flow<String> =
        store.data.map { it[K.CUSTOM_DNS] ?: "" }

    suspend fun customDns(): String = customDnsFlow().first()

    suspend fun setCustomDns(url: String) {
        write("CUSTOM_DNS") { it[K.CUSTOM_DNS] = url }
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
        write("PLAY_WAIT_SERVERS") { it[K.PLAY_WAIT_SERVERS] = wait }
    }

    /** How many servers to wait for when [playWaitServersFlow] is on (1–5). */
    fun playMinServersFlow(): Flow<Int> =
        store.data.map { (it[K.PLAY_MIN_SERVERS] ?: 2).coerceIn(1, 5) }

    suspend fun playMinServers(): Int = playMinServersFlow().first()

    suspend fun setPlayMinServers(n: Int) {
        write("PLAY_MIN_SERVERS") { it[K.PLAY_MIN_SERVERS] = n.coerceIn(1, 5) }
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
        write("ASK_SERVER") { it[K.ASK_SERVER] = ask }
    }

    /**
     * What the player does when the server it is playing dies mid-video (a
     * signed link expired, the mirror went away): on (the default) it asks —
     * "try the next server" or "choose another server", with a countdown that
     * switches automatically if the question is ignored. Off, it walks the list
     * silently like it always did.
     *
     * Only a server the USER picked gets the question: a dead link discovered
     * while the player is still walking the list on its own (the instant-play
     * start, an automatic failover) keeps advancing on its own, so a title with
     * a few dud servers never turns into a wall of dialogs.
     */
    fun failoverAskOnFailureFlow(): Flow<Boolean> =
        store.data.map { it[K.FAILOVER_ASK] ?: true }

    suspend fun failoverAskOnFailure(): Boolean = failoverAskOnFailureFlow().first()

    suspend fun setFailoverAskOnFailure(ask: Boolean) {
        write("FAILOVER_ASK") { it[K.FAILOVER_ASK] = ask }
    }

    /** Show the full-screen title card (backdrop + breathing name) from Play
     *  until the first frame of video. Off = the player opens straight away
     *  with just a round loading spinner. On by default. */
    fun showLoadingBannerFlow(): Flow<Boolean> =
        store.data.map { it[K.SHOW_LOADING_BANNER] ?: true }

    suspend fun showLoadingBanner(): Boolean = showLoadingBannerFlow().first()

    suspend fun setShowLoadingBanner(show: Boolean) {
        write("SHOW_LOADING_BANNER") { it[K.SHOW_LOADING_BANNER] = show }
    }

    /** Whether the player may suggest turning on Slow connection mode when a
     *  play looks like it is struggling on a weak connection. On by default —
     *  a user who keeps getting a wrong "your connection looks slow" verdict
     *  turns it off here and never sees the dialog again. */
    fun slowTipEnabledFlow(): Flow<Boolean> =
        store.data.map { it[K.SLOW_TIP_ENABLED] ?: true }

    suspend fun slowTipEnabled(): Boolean = slowTipEnabledFlow().first()

    suspend fun setSlowTipEnabled(enabled: Boolean) {
        write("SLOW_TIP_ENABLED") { it[K.SLOW_TIP_ENABLED] = enabled }
    }

    /** Set by the dialog's "Don't ask again" — permanent, unlike the timed
     *  cooldown of a plain dismissal. */
    fun slowTipDontAskFlow(): Flow<Boolean> =
        store.data.map { it[K.SLOW_TIP_DONT_ASK] ?: false }

    suspend fun slowTipDontAsk(): Boolean = slowTipDontAskFlow().first()

    suspend fun setSlowTipDontAsk(dontAsk: Boolean) {
        write("SLOW_TIP_DONT_ASK") { it[K.SLOW_TIP_DONT_ASK] = dontAsk }
    }

    /** When the tip was last dismissed with "Not now" (0 = never). Keeps the
     *  dialog from reappearing on every single play. */
    fun slowTipLastDismissFlow(): Flow<Long> =
        store.data.map { it[K.SLOW_TIP_LAST_DISMISS] ?: 0L }

    suspend fun slowTipLastDismiss(): Long = slowTipLastDismissFlow().first()

    suspend fun setSlowTipLastDismiss(atMs: Long) {
        write("SLOW_TIP_LAST_DISMISS") { it[K.SLOW_TIP_LAST_DISMISS] = atMs }
    }

    /** Set by the launch Telegram invitation's "Don't show this again" checkbox,
     *  so the dialog never comes back. */
    fun telegramDontShowFlow(): Flow<Boolean> =
        store.data.map { it[K.TELEGRAM_DONT_SHOW] ?: false }

    suspend fun telegramDontShow(): Boolean = telegramDontShowFlow().first()

    suspend fun setTelegramDontShow(dontShow: Boolean) {
        write("TELEGRAM_DONT_SHOW") { it[K.TELEGRAM_DONT_SHOW] = dontShow }
    }

    /** How many downloads may run simultaneously (1–10). */
    fun downloadConcurrencyFlow(): Flow<Int> =
        store.data.map { (it[K.DOWNLOAD_CONCURRENCY] ?: 3).coerceIn(1, 10) }

    suspend fun downloadConcurrency(): Int = downloadConcurrencyFlow().first()

    suspend fun setDownloadConcurrency(n: Int) {
        write("DOWNLOAD_CONCURRENCY") { it[K.DOWNLOAD_CONCURRENCY] = n.coerceIn(1, 10) }
    }

    /** Which provider the Home screen is currently showing (empty = All). */
    fun homeProviderFlow(): Flow<String> =
        store.data.map { it[K.HOME_PROVIDER] ?: "" }

    suspend fun homeProvider(): String = homeProviderFlow().first()

    suspend fun setHomeProvider(id: String) {
        write("HOME_PROVIDER") { it[K.HOME_PROVIDER] = id }
    }

    // ---- Per-extension auto-translate (WebView pages → English) ----

    /** Provider ids whose web pages are always translated to English. */
    fun translateProvidersFlow(): Flow<Set<String>> =
        store.data.map { parseStringList(it[K.TRANSLATE_PROVIDERS]).toSet() }

    suspend fun translateProviders(): Set<String> = translateProvidersFlow().first()

    suspend fun setTranslateProvider(id: String, enabled: Boolean) {
        val cur = translateProviders()
        val next = if (enabled) cur + id else cur - id
        write("TRANSLATE_PROVIDERS") { it[K.TRANSLATE_PROVIDERS] = encodeStringList(next.toList()) }
    }

    /** Persisted original→English translation pairs (title cache). */
    suspend fun translateCache(): List<Pair<String, String>> =
        store.data.map { parsePairs(it[K.TRANSLATE_CACHE]) }.first()

    suspend fun setTranslateCache(list: List<Pair<String, String>>) {
        write("TRANSLATE_CACHE") { it[K.TRANSLATE_CACHE] = encodePairs(list) }
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
        write("THEME") { it[K.THEME] = key }
    }

    // ---- Accent colours (app + player) ----

    /** The app UI's accent colour (Settings → Appearance & Theme → Accent colour). Defaults to
     *  the amber/gold the app has always used, so an existing install looks
     *  identical until the user picks something else. */
    fun appAccentFlow(): Flow<String> =
        store.data.map { it[K.APP_ACCENT] ?: HikariAccent.DEFAULT_APP.key }

    suspend fun appAccent(): String = appAccentFlow().first()

    suspend fun setAppAccent(key: String) {
        write("APP_ACCENT") { it[K.APP_ACCENT] = key }
        syncAccents()
    }

    /** The player's own accent (used only while the app and the player are NOT
     *  linked). Defaults to the cyan→violet glow the player has always had. */
    fun playerAccentFlow(): Flow<String> =
        store.data.map { it[K.PLAYER_ACCENT] ?: HikariAccent.DEFAULT_PLAYER.key }

    suspend fun playerAccent(): String = playerAccentFlow().first()

    suspend fun setPlayerAccent(key: String) {
        write("PLAYER_ACCENT") { it[K.PLAYER_ACCENT] = key }
        syncAccents()
    }

    /** "Match app & player theme": while ON the player follows the app accent
     *  and picking a colour in either place recolours both. */
    fun themeLinkedFlow(): Flow<Boolean> =
        store.data.map { it[K.THEME_LINKED] ?: false }

    suspend fun themeLinked(): Boolean = themeLinkedFlow().first()

    suspend fun setThemeLinked(linked: Boolean) {
        write("THEME_LINKED") { it[K.THEME_LINKED] = linked }
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
        write("PLAYER_CONTROLS") { it[K.PLAYER_CONTROLS] = json }
    }

    /** Video enhance preset key (see [com.hikari.app.player.EnhancePreset]). */
    fun enhancePresetFlow(): Flow<String> =
        store.data.map { it[K.PLAYER_ENHANCE] ?: EnhancePreset.DEFAULT.key }

    suspend fun enhancePreset(): String = enhancePresetFlow().first()

    suspend fun setEnhancePreset(key: String) {
        write("PLAYER_ENHANCE") { it[K.PLAYER_ENHANCE] = key }
    }

    /**
     * True once a device has proven it cannot run media3's video-effects
     * pipeline (its GL stack refuses the frame processor). Remembered so the
     * player stops arming the pipeline — arming it on such a device would fail
     * EVERY play, not just the one where the user first picked a preset.
     */
    fun enhanceUnsupportedFlow(): Flow<Boolean> =
        store.data.map { it[K.PLAYER_ENHANCE_UNSUPPORTED] ?: false }

    suspend fun enhanceUnsupported(): Boolean = enhanceUnsupportedFlow().first()

    suspend fun setEnhanceUnsupported(value: Boolean) {
        write("PLAYER_ENHANCE_UNSUPPORTED") { it[K.PLAYER_ENHANCE_UNSUPPORTED] = value }
    }

    // ---- In-app UI scale ----

    /** When ON the app ignores the phone's Font size AND Display size settings
     *  and scales its interface with [uiScaleFlow] instead — so it looks the
     *  same on every phone. OFF (default) follows the system settings. */
    fun uiScaleEnabledFlow(): Flow<Boolean> =
        store.data.map { it[K.UI_SCALE_ENABLED] ?: false }

    suspend fun uiScaleEnabled(): Boolean = uiScaleEnabledFlow().first()

    suspend fun setUiScaleEnabled(enabled: Boolean) {
        write("UI_SCALE_ENABLED") { it[K.UI_SCALE_ENABLED] = enabled }
        // Mirror into the synchronous cache so View-based screens (player,
        // WebView) and the next cold start pick the change up immediately.
        runCatching { UiScale.sync(ctx, enabled, uiScale()) }
    }

    /** The in-app scale (0.7f–1.3f) used while [uiScaleEnabledFlow] is on. */
    fun uiScaleFlow(): Flow<Float> =
        store.data.map { (it[K.UI_SCALE_PERCENT] ?: 100).coerceIn(70, 130) / 100f }

    suspend fun uiScale(): Float = uiScaleFlow().first()

    suspend fun setUiScale(percent: Int) {
        write("UI_SCALE_PERCENT") { it[K.UI_SCALE_PERCENT] = percent.coerceIn(70, 130) }
        runCatching { UiScale.sync(ctx, uiScaleEnabled(), uiScale()) }
    }

    // ---- Ad blocking (WebView only) ----

    fun adEnabledFlow(): Flow<Boolean> =
        store.data.map { it[K.AD_ENABLED] ?: true }

    suspend fun adEnabled(): Boolean = adEnabledFlow().first()

    suspend fun setAdEnabled(enabled: Boolean) {
        write("AD_ENABLED") { it[K.AD_ENABLED] = enabled }
    }

    fun adListsFlow(): Flow<List<AdBlocker.HostList>> =
        store.data.map { parseHostLists(it[K.AD_LISTS]) }

    suspend fun adLists(): List<AdBlocker.HostList> = adListsFlow().first()

    suspend fun setAdLists(list: List<AdBlocker.HostList>) {
        write("AD_LISTS") { it[K.AD_LISTS] = encodeHostLists(list) }
    }

    fun adBlockFlow(): Flow<List<String>> =
        store.data.map { parseStringList(it[K.AD_BLOCK]) }

    suspend fun adBlock(): List<String> = adBlockFlow().first()

    suspend fun setAdBlock(list: List<String>) {
        write("AD_BLOCK") { it[K.AD_BLOCK] = encodeStringList(list) }
    }

    fun adWhiteFlow(): Flow<List<String>> =
        store.data.map { parseStringList(it[K.AD_WHITE]) }

    suspend fun adWhite(): List<String> = adWhiteFlow().first()

    suspend fun setAdWhite(list: List<String>) {
        write("AD_WHITE") { it[K.AD_WHITE] = encodeStringList(list) }
    }

    // ---- WebView safety (redirect + popup protection; default ON) ----

    fun webviewRedirectFlow(): Flow<Boolean> =
        store.data.map { it[K.WEBVIEW_REDIRECT] ?: true }

    suspend fun webviewRedirect(): Boolean = webviewRedirectFlow().first()

    suspend fun setWebviewRedirect(enabled: Boolean) {
        write("WEBVIEW_REDIRECT") { it[K.WEBVIEW_REDIRECT] = enabled }
    }

    fun webviewPopupFlow(): Flow<Boolean> =
        store.data.map { it[K.WEBVIEW_POPUP] ?: true }

    suspend fun webviewPopup(): Boolean = webviewPopupFlow().first()

    suspend fun setWebviewPopup(enabled: Boolean) {
        write("WEBVIEW_POPUP") { it[K.WEBVIEW_POPUP] = enabled }
    }

    /** Legacy preference: "Solve Cloudflare checks automatically" used to exist
     *  in Settings, and the value is left in place so an old install's stored
     *  key still reads back cleanly. NOTHING reads it any more and the switch is
     *  gone on purpose: no Cloudflare challenge is ever loaded on its own, so
     *  there is nothing to toggle (see CloudflareVerifier). */
    fun cfAutoSolveFlow(): Flow<Boolean> =
        store.data.map { it[K.CF_AUTO_SOLVE] ?: false }

    suspend fun cfAutoSolve(): Boolean = cfAutoSolveFlow().first()

    suspend fun setCfAutoSolve(enabled: Boolean) {
        write("CF_AUTO_SOLVE") { it[K.CF_AUTO_SOLVE] = enabled }
    }

    /**
     * Whether EXTENSIONS may open their own Cloudflare verification page.
     *
     * OFF (the default) is Hikari's rule: the only thing that can open a
     * verification page is the user tapping the app's own WebView (globe)
     * button. Some extensions ship their own Cloudflare WebView and open it in
     * the middle of loading sources (Cinemacity does — see
     * com.hikari.app.net.ExtensionVerifyGuard for the disassembled proof), so
     * while this is off the app forces those extensions' own switches to `false`
     * at launch, again whenever a plugin's settings sheet closes, and whenever
     * this switch changes.
     *
     * ON stops the forcing and nothing else: every read answers whatever the
     * extension stored, and the extension's own switch keeps its own value. It
     * deliberately does NOT write `true` into those switches — that would turn
     * the very page this setting exists to prevent ON, and leave it on. For the
     * rare case where an extension only works through its own bypass screen.
     */
    fun extensionVerifyWebviewFlow(): Flow<Boolean> =
        store.data.map { it[K.EXT_VERIFY_WEBVIEW] ?: false }

    suspend fun extensionVerifyWebview(): Boolean = extensionVerifyWebviewFlow().first()

    suspend fun setExtensionVerifyWebview(allowed: Boolean) {
        write("EXT_VERIFY_WEBVIEW") { it[K.EXT_VERIFY_WEBVIEW] = allowed }
        // Apply immediately (not just on the next launch) so the choice takes
        // effect for the very next source search.
        runCatching { ExtensionVerifyGuard.apply(ctx, allowed) }
    }

    /** Hosts the user allowed redirects to (blocked-elsewhere hosts allowed
     *  through). */
    fun webviewRedirectAllowFlow(): Flow<List<String>> =
        store.data.map { parseStringList(it[K.WEBVIEW_REDIRECT_ALLOW]) }

    suspend fun webviewRedirectAllow(): List<String> =
        webviewRedirectAllowFlow().first().also { RedirectAllow.set(it) }

    suspend fun setWebviewRedirectAllow(list: List<String>) {
        // Mirror FIRST (synchronously, in memory) and the DataStore write second:
        // a WebView that is being redirected right now has to see a link the
        // user just added (see RedirectAllow), and waiting for the store write
        // would leave that first redirect blocked.
        RedirectAllow.set(list)
        write("WEBVIEW_REDIRECT_ALLOW") { it[K.WEBVIEW_REDIRECT_ALLOW] = encodeStringList(list) }
    }

    // ---- WebView user agent (stock Android default vs custom) ----

    fun webviewUseDefaultUaFlow(): Flow<Boolean> =
        store.data.map { it[K.WEBVIEW_DEFAULT_UA] ?: true }

    suspend fun webviewUseDefaultUa(): Boolean = webviewUseDefaultUaFlow().first()

    fun webviewCustomUaFlow(): Flow<String> =
        store.data.map { it[K.WEBVIEW_CUSTOM_UA] ?: "" }

    suspend fun webviewCustomUa(): String = webviewCustomUaFlow().first()

    suspend fun setWebViewUa(useDefault: Boolean, customUa: String) {
        write("WEBVIEW_DEFAULT_UA") {
            it[K.WEBVIEW_DEFAULT_UA] = useDefault
            it[K.WEBVIEW_CUSTOM_UA] = customUa
        }
    }

    // ---- App language ----

    /** The app language as a BCP-47 tag ("" = follow the device). Applied to
     *  the whole app — and therefore to the player's overlay words, which are
     *  resource strings — via AppCompatDelegate.setApplicationLocales (see
     *  com.hikari.app.ui.LanguageManager). */
    fun languageFlow(): Flow<String> =
        store.data.map { it[K.LANGUAGE] ?: "" }

    suspend fun language(): String = languageFlow().first()

    suspend fun setLanguage(tag: String) {
        write("LANGUAGE") { it[K.LANGUAGE] = tag }
    }

    // ---- Universal extractor (yt-dlp fallback) ----
    //
    // REMOVED in 0.9.1, along with the whole bundled yt-dlp runtime. The
    // preference key ("ytdlpEnabled") is deliberately not written or read any
    // more: a stale value left in somebody's DataStore is simply ignored.

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

    // ---- Library categories (encode/parse) ----

    private fun encodeCategories(list: List<LibraryCategory>): String {
        val arr = JSONArray()
        for (c in list) {
            arr.put(
                JSONObject()
                    .put("id", c.id)
                    .put("name", c.name)
                    .put("built", c.builtIn)
            )
        }
        return arr.toString()
    }

    /** A missing value means "never configured" and yields the four defaults;
     *  an explicitly empty array means the user deleted them all. */
    private fun parseCategories(s: String?): List<LibraryCategory> {
        if (s == null) return LibraryCategory.DEFAULTS
        if (s.isBlank()) return LibraryCategory.DEFAULTS
        return try {
            val arr = JSONArray(s)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id")
                val name = o.optString("name")
                if (id.isBlank() || name.isBlank()) null
                else LibraryCategory(id, name, o.optBoolean("built", false))
            }
        } catch (e: Exception) {
            LibraryCategory.DEFAULTS
        }
    }

    private fun encodeCategoryMap(map: Map<String, Set<String>>): String {
        val obj = JSONObject()
        for ((k, v) in map) {
            if (v.isEmpty()) continue
            val arr = JSONArray()
            for (c in v) arr.put(c)
            obj.put(k, arr)
        }
        return obj.toString()
    }

    private fun parseCategoryMap(s: String?): Map<String, Set<String>> {
        if (s.isNullOrBlank()) return emptyMap()
        return try {
            val obj = JSONObject(s)
            val out = LinkedHashMap<String, Set<String>>()
            obj.keys().forEach { k ->
                val arr = obj.optJSONArray(k) ?: return@forEach
                val set = HashSet<String>()
                for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotBlank() }?.let { set.add(it) }
                if (set.isNotEmpty()) out[k] = set
            }
            out
        } catch (e: Exception) {
            emptyMap()
        }
    }

    // ---- Userscripts (run inside the WebView only) ----

    fun userscriptsFlow(): Flow<List<Userscript>> =
        store.data.map { parseUserscripts(it[K.USERS]) }

    suspend fun userscripts(): List<Userscript> = userscriptsFlow().first()

    suspend fun setUserscripts(list: List<Userscript>) {
        write("USERS") { it[K.USERS] = encodeUserscripts(list) }
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

    /**
     * Every write to the installed-provider list goes through here, one at a
     * time.
     *
     * The list is stored as ONE JSON array, so each change is a read (of the
     * whole list), a copy with one entry added/removed/toggled, and a write —
     * and two of those interleaving (a repo sync installing several extensions
     * while the Extensions screen toggles one) lose whichever finished first.
     * A lost update here does not just fail to add something: it writes back a
     * list built from a snapshot that never had the other change in it, so
     * INSTALLED extensions disappear from the store — which is the "sometimes it
     * searches 140 repos, sometimes 238, and my Hikari repos are not all in
     * either count" report. Serialising the read-modify-write removes the race
     * (DataStore only makes each individual write atomic; it cannot make a
     * caller's read-then-write atomic).
     */
    suspend fun saveProviders(list: List<ProviderConfig>) = providerWrites.withLock {
        write("PROVIDERS") { it[K.PROVIDERS] = encodeProviders(list) }
    }

    private val providerWrites = Mutex()

    /**
     * The ATOMIC read-modify-write of the installed-provider list: [transform]
     * sees the list as it is at the moment of the write, under the same lock
     * every other provider write takes.
     *
     * Callers used to read the list themselves and hand the result to
     * [saveProviders], which is a lost update waiting to happen (an extension
     * repo sync writing its rebuilt list while an install adds one more provider
     * drops the install — see [saveProviders]). Anything that rebuilds or prunes
     * the list belongs in here.
     */
    suspend fun updateProviders(
        transform: (List<ProviderConfig>) -> List<ProviderConfig>,
    ): List<ProviderConfig> = providerWrites.withLock {
        var result: List<ProviderConfig> = emptyList()
        write("PROVIDERS") { prefs ->
            result = transform(parseProviders(prefs[K.PROVIDERS]))
            prefs[K.PROVIDERS] = encodeProviders(result)
        }
        result
    }

    suspend fun addProvider(c: ProviderConfig) {
        providerWrites.withLock {
            write("PROVIDERS") { prefs ->
                prefs[K.PROVIDERS] =
                    encodeProviders(parseProviders(prefs[K.PROVIDERS]).filter { it.id != c.id } + c)
            }
        }
    }

    suspend fun removeProvider(id: String) {
        providerWrites.withLock {
            write("PROVIDERS") { prefs ->
                prefs[K.PROVIDERS] =
                    encodeProviders(parseProviders(prefs[K.PROVIDERS]).filter { it.id != id })
            }
        }
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        providerWrites.withLock {
            write("PROVIDERS") { prefs ->
                prefs[K.PROVIDERS] = encodeProviders(
                    parseProviders(prefs[K.PROVIDERS])
                        .map { if (it.id == id) it.copy(enabled = enabled) else it },
                )
            }
        }
    }

    fun reposFlow(): Flow<List<Cs3Repo>> =
        store.data.map { parseRepos(it[K.CS3_REPOS]) }

    suspend fun repos(): List<Cs3Repo> = reposFlow().first()

    /** The identity of a repo URL, never null: [SourceUrls.repoKey] answers null
     *  only for an input with nothing usable in it, and such an entry is better
     *  compared by its own text than folded together with every other one. */
    private fun repoId(url: String): String =
        SourceUrls.repoKey(url) ?: SourceUrls.canonical(url).ifBlank { url.trim() }

    suspend fun addCs3Repo(r: Cs3Repo) {
        val key = repoId(r.url)
        val existing = repos().firstOrNull { repoId(it.url) == key }
        // The same REPOSITORY is one entry, however it was spelled and whichever
        // branch the file was read from (see [SourceUrls.repoKey]): re-adding it
        // — the same link, the jsDelivr mirror, `refs/heads/x` vs `x`, or the
        // repo's other branch — updates the entry that is already there instead
        // of growing a second folder with the same name. The stored URL is
        // upgraded to the origin spelling when the existing entry is only a
        // mirror, but an existing entry is otherwise left where it is: the
        // extensions installed from it match THAT file's URL.
        val merged = if (existing == null || existing.url == r.url) r
        else existing.copy(
            name = existing.name.ifBlank { r.name },
            description = r.description.ifBlank { existing.description },
            url = if (SourceUrls.isMirror(existing.url)) r.url else existing.url,
        )
        saveRepos(repos().filter { repoId(it.url) != key } + merged)
    }

    suspend fun removeCs3Repo(url: String) {
        // Remove by identity, not by spelling: a repo stored twice under two
        // URL spellings (from an older build) would otherwise reappear as soon
        // as the list is read back.
        val key = repoId(url)
        saveRepos(repos().filter { repoId(it.url) != key })
    }

    /**
     * Collapses duplicate repo entries an older build left behind — the same
     * repository stored twice (two branches, two URL spellings) shows as one.
     * Runs once at startup; a no-op when there is nothing to collapse.
     */
    suspend fun dedupeStoredRepos() {
        val before = repos()
        val after = dedupeRepos(before)
        if (after != before) saveRepos(after)
    }

    private suspend fun saveRepos(list: List<Cs3Repo>) {
        write("CS3_REPOS") { it[K.CS3_REPOS] = encodeRepos(dedupeRepos(list)) }
    }

    /**
     * One entry per repo whatever the spelling of its URL — the canonical
     * form is the identity (see [SourceUrls]). The origin spelling wins over
     * a jsDelivr mirror; otherwise the first entry seen is kept, so the repo
     * list's order is stable.
     */
    private fun dedupeRepos(list: List<Cs3Repo>): List<Cs3Repo> {
        val out = ArrayList<Cs3Repo>(list.size)
        val index = HashMap<String, Int>()
        for (r in list) {
            if (r.url.isBlank()) continue
            val key = repoId(r.url)
            val at = index[key]
            if (at == null) {
                index[key] = out.size
                out.add(r)
            } else if (SourceUrls.isMirror(out[at].url) && !SourceUrls.isMirror(r.url)) {
                out[at] = r
            } else if (out[at].name.isBlank() && r.name.isNotBlank()) {
                out[at] = out[at].copy(name = r.name, description = out[at].description.ifBlank { r.description })
            }
        }
        return out
    }

    // ---- Collections (name → folders → catalog sources) ----

    /** Every user-made collection, in creation order. */
    fun collectionsFlow(): Flow<List<Collection>> =
        store.data.map { parseCollections(it[K.COLLECTIONS]) }

    suspend fun collections(): List<Collection> = collectionsFlow().first()

    suspend fun collection(id: String): Collection? = collections().firstOrNull { it.id == id }

    /** Replaces the whole list — the editor always hands back the full set, so
     *  a create/rename/reorder is one atomic write. */
    suspend fun saveCollections(list: List<Collection>) {
        write("COLLECTIONS") { it[K.COLLECTIONS] = encodeCollections(list) }
    }

    /** Adds [c] (or replaces the same-id entry) and returns the saved list. */
    suspend fun upsertCollection(c: Collection): List<Collection> {
        val next = collections().filter { it.id != c.id } + c
        saveCollections(next)
        return next
    }

    suspend fun removeCollection(id: String) {
        saveCollections(collections().filter { it.id != id })
    }

    /** A stable, URL/JSON-safe id for a new collection or folder. */
    fun newId(prefix: String): String =
        prefix + "-" + System.currentTimeMillis().toString(36) +
            "-" + (1000 + (Math.random() * 8999).toInt())

    // ---- First-run extension-repo seeding ----

    /** True once the bundled default extension repos have been added. Kept so a
     *  user who deliberately removes a default repo doesn't have it pushed back
     *  on the next launch. */
    suspend fun seededRepos(): Boolean =
        store.data.map { it[K.SEEDED_REPOS] ?: false }.first()

    suspend fun markReposSeeded() {
        write("SEEDED_REPOS") { it[K.SEEDED_REPOS] = true }
    }

    fun favoritesFlow(): Flow<List<MediaItem>> =
        store.data.map { parseMedia(it[K.FAVORITES]) }

    suspend fun favorites(): List<MediaItem> = favoritesFlow().first()

    suspend fun addFavorite(m: MediaItem) {
        val list = favorites().filter { it.uniqueId != m.uniqueId } + m
        write("FAVORITES") { it[K.FAVORITES] = encodeMedia(list) }
    }

    suspend fun removeFavorite(id: String) {
        write("FAVORITES") { prefs ->
            prefs[K.FAVORITES] = encodeMedia(parseMedia(prefs[K.FAVORITES]).filter { f -> f.uniqueId != id })
            // Unfiling the title is part of removing it: leaving the mapping
            // behind would file the NEXT title saved under the same id.
            val cats = parseCategoryMap(prefs[K.FAVORITE_CATEGORIES]).toMutableMap()
            if (cats.remove(id) != null) prefs[K.FAVORITE_CATEGORIES] = encodeCategoryMap(cats)
        }
    }

    fun sitesFlow(): Flow<List<Site>> =
        store.data.map { parseSites(it[K.SITES]) }

    suspend fun sites(): List<Site> = sitesFlow().first()

    suspend fun addSite(s: Site) {
        // The current list is read BEFORE the write. It used to be read inside
        // the `edit` block (`sites()` → `store.data.first()`), and DataStore
        // serves reads and writes through ONE actor: a read issued from inside a
        // write's transform can never be answered, so the write never completes
        // — and because that write holds the actor, EVERY later read and write
        // in the whole app queues behind it forever. That is the reported
        // "the app becomes buggy: settings stop saving, series show no episodes
        // … fixed by closing and reopening it", and it is why the fix is to
        // hoist the read out of the transaction.
        val list = sites().filter { it.url != s.url } + s
        write("SITES") { it[K.SITES] = encodeSites(list) }
    }

    suspend fun removeSite(url: String) {
        val list = sites().filter { it.url != url }
        write("SITES") { it[K.SITES] = encodeSites(list) }
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
        write("every setting") { it.clear() }
    }

    // ---- Backup & restore (Settings → Backup & Restore) ----

    /**
     * Every stored preference as plain data, for the backup file.
     *
     * Deliberately a GENERIC dump rather than a hand-written list of the keys
     * this class happens to declare today: the store is where the whole setup
     * lives (installed sources and their configs, repos, history, favorites,
     * per-provider settings, the lot), and a curated list silently drops every
     * key added after it was written. [PrefRecord.type] keeps each value's exact
     * type so a restore is byte-for-byte what was saved rather than a guess
     * (a Long that comes back as a Double, or a String set that comes back as a
     * list, is a subtly broken setting).
     */
    suspend fun snapshotPreferences(): List<PrefRecord> =
        store.data.first().asMap().mapNotNull { (key, value) -> recordOf(key.name, value) }

    /**
     * Applies records from a backup file, overwriting the values for those keys
     * and leaving every other key alone. One edit transaction, so the app can
     * never observe a half-restored store.
     */
    suspend fun restorePreferences(records: List<PrefRecord>) {
        if (records.isEmpty()) return
        write("the restored backup") { prefs ->
            for (r in records) applyRecord(prefs, r)
        }
    }

    /** [value] in the backup file's own terms, or null for a type the file
     *  format has no code for (nothing in this store uses one today). */
    private fun recordOf(name: String, value: Any): PrefRecord? = when (value) {
        is String -> PrefRecord(name, "s", value)
        is Boolean -> PrefRecord(name, "b", value)
        is Int -> PrefRecord(name, "i", value)
        is Long -> PrefRecord(name, "l", value)
        is Float -> PrefRecord(name, "f", value)
        is Double -> PrefRecord(name, "d", value)
        is Set<*> -> PrefRecord(name, "ss", value.filterIsInstance<String>())
        is ByteArray -> PrefRecord(
            name, "bin",
            android.util.Base64.encodeToString(value, android.util.Base64.NO_WRAP),
        )
        else -> null
    }

    private fun applyRecord(prefs: MutablePreferences, r: PrefRecord) {
        when (r.type) {
            "s" -> (r.value as? String)?.let { prefs[stringPreferencesKey(r.key)] = it }
            "b" -> (r.value as? Boolean)?.let { prefs[booleanPreferencesKey(r.key)] = it }
            "i" -> (r.value as? Number)?.let { prefs[intPreferencesKey(r.key)] = it.toInt() }
            "l" -> (r.value as? Number)?.let { prefs[longPreferencesKey(r.key)] = it.toLong() }
            "f" -> (r.value as? Number)?.let { prefs[floatPreferencesKey(r.key)] = it.toFloat() }
            "d" -> (r.value as? Number)?.let { prefs[doublePreferencesKey(r.key)] = it.toDouble() }
            "ss" -> (r.value as? List<*>)?.let { list ->
                prefs[stringSetPreferencesKey(r.key)] = list.filterIsInstance<String>().toSet()
            }
            "bin" -> (r.value as? String)?.let { b64 ->
                runCatching { android.util.Base64.decode(b64, android.util.Base64.NO_WRAP) }
                    .getOrNull()
                    ?.let { prefs[byteArrayPreferencesKey(r.key)] = it }
            }
        }
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
        write("HISTORY") { prefs ->
            val cur = parseHistory(prefs[K.HISTORY])
            val next = (listOf(e) + cur.filter { it.uniqueKey != e.uniqueKey }).take(200)
            prefs[K.HISTORY] = encodeHistory(next)
        }
    }

    suspend fun clearHistory() {
        write("HISTORY") { it[K.HISTORY] = "[]" }
    }

    /** Remove ONE entry — a single movie, or a single episode of a series
     *  (episodes of one title share a mediaId, so the key is per-video). */
    suspend fun removeHistory(uniqueKey: String) {
        write("HISTORY") { prefs ->
            val cur = parseHistory(prefs[K.HISTORY])
            prefs[K.HISTORY] = encodeHistory(cur.filter { it.uniqueKey != uniqueKey })
        }
    }

    fun historyPausedFlow(): Flow<Boolean> =
        store.data.map { it[K.HISTORY_PAUSED] ?: false }

    suspend fun historyPaused(): Boolean = historyPausedFlow().first()

    suspend fun setHistoryPaused(paused: Boolean) {
        write("HISTORY_PAUSED") { it[K.HISTORY_PAUSED] = paused }
    }

    /** When true, the Home screen hides its "Continue Watching" row entirely
     *  (history keeps being recorded — this only hides the shelf). */
    fun hideContinueFlow(): Flow<Boolean> =
        store.data.map { it[K.HIDE_CONTINUE] ?: false }

    suspend fun hideContinue(): Boolean = hideContinueFlow().first()

    suspend fun setHideContinue(hide: Boolean) {
        write("HIDE_CONTINUE") { it[K.HIDE_CONTINUE] = hide }
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
        write("LAST_SOURCE") { it[K.LAST_SOURCE] = encodeLastSources(cur) }
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
        write("ELEMENT_BLOCKS") { it[K.ELEMENT_BLOCKS] = encodeStringList((cur + selector).take(200)) }
    }

    /** Removes and returns the most recently blocked selector (null if none). */
    suspend fun removeLastElementBlock(): String? {
        val cur = elementBlocks()
        if (cur.isEmpty()) return null
        val last = cur.last()
        write("ELEMENT_BLOCKS") { it[K.ELEMENT_BLOCKS] = encodeStringList(cur.dropLast(1)) }
        return last
    }

    suspend fun clearElementBlocks() {
        write("ELEMENT_BLOCKS") { it[K.ELEMENT_BLOCKS] = "[]" }
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
                .let { dedupeRepos(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun encodeCollections(list: List<Collection>): String {
        val arr = JSONArray()
        for (c in list) {
            val folders = JSONArray()
            for (f in c.folders) {
                val sources = JSONArray()
                for (s in f.sources) {
                    sources.put(
                        JSONObject()
                            .put("kind", s.kind.name)
                            .put("title", s.title)
                            .put("pid", s.providerId)
                            .put("cid", s.catalogId)
                            .put("type", s.type.name)
                            .put("raw", s.rawType)
                            .put("preset", s.tmdbPreset)
                            .put("spec", s.tmdbSpec)
                            .put("items", s.itemsJson)
                            .put("uid", s.uid)
                    )
                }
                folders.put(
                    JSONObject()
                        .put("id", f.id)
                        .put("name", f.name)
                        .put("coverKind", f.coverKind)
                        .put("coverValue", f.coverValue)
                        .put("tileShape", f.tileShape)
                        .put("sources", sources)
                )
            }
            arr.put(
                JSONObject()
                    .put("id", c.id)
                    .put("name", c.name)
                    .put("coverKind", c.coverKind)
                    .put("coverValue", c.coverValue)
                    .put("tileShape", c.tileShape)
                    .put("folders", folders)
            )
        }
        return arr.toString()
    }

    private fun parseCollections(s: String?): List<Collection> {
        if (s.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(s)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id")
                val name = o.optString("name")
                if (id.isBlank() || name.isBlank()) return@mapNotNull null
                val foldersArr = o.optJSONArray("folders")
                val folders = ArrayList<CollectionFolder>()
                if (foldersArr != null) {
                    for (j in 0 until foldersArr.length()) {
                        val fo = foldersArr.optJSONObject(j) ?: continue
                        val fid = fo.optString("id")
                        val fname = fo.optString("name")
                        if (fid.isBlank() || fname.isBlank()) continue
                        val sourcesArr = fo.optJSONArray("sources")
                        val sources = ArrayList<CatalogSource>()
                        if (sourcesArr != null) {
                            for (k in 0 until sourcesArr.length()) {
                                val so = sourcesArr.optJSONObject(k) ?: continue
                                val kind = runCatching {
                                    CatalogSourceKind.valueOf(so.optString("kind", "TMDB"))
                                }.getOrDefault(CatalogSourceKind.TMDB)
                                val source = CatalogSource(
                                    kind = kind,
                                    title = so.optString("title"),
                                    providerId = so.optString("pid"),
                                    catalogId = so.optString("cid"),
                                    type = runCatching { MediaType.valueOf(so.optString("type")) }
                                        .getOrDefault(MediaType.UNKNOWN),
                                    rawType = so.optString("raw"),
                                    tmdbPreset = so.optString("preset"),
                                    tmdbSpec = so.optString("spec"),
                                    itemsJson = so.optString("items"),
                                    uid = so.optString("uid"),
                                )
                                // A source that can't resolve to anything is a
                                // row that would never load: drop it, but keep
                                // the folder itself.
                                val usable = when (kind) {
                                    CatalogSourceKind.TMDB ->
                                        TmdbPresets.byKey(source.tmdbPreset) != null ||
                                            (source.spec?.type != null)
                                    CatalogSourceKind.ITEMS -> source.itemCount > 0
                                    CatalogSourceKind.PROVIDER ->
                                        source.providerId.isNotBlank() &&
                                            source.catalogId.isNotBlank()
                                }
                                if (usable) sources.add(source)
                            }
                        }
                        folders.add(
                            CollectionFolder(
                                id = fid,
                                name = fname,
                                // Deduped on the way IN, not just on the way out:
                                // a folder that already holds the same catalog
                                // twice (written by an older build, or restored
                                // from a backup) used to reach a lazy list with
                                // two identical keys and crash the editor and the
                                // collection view with
                                //   IllegalArgumentException: Key "prov|cs3|…"
                                //   was already used.
                                // Two entries with the same key ARE the same
                                // catalog (same extension, same catalog id), so
                                // dropping the copy loses nothing.
                                sources = sources.distinctBy { it.key },
                                coverKind = CoverKinds.normalize(fo.optString("coverKind")),
                                coverValue = fo.optString("coverValue"),
                                tileShape = TileShapes.normalize(fo.optString("tileShape")),
                            )
                        )
                    }
                }
                Collection(
                    id = id,
                    name = name,
                    folders = folders,
                    coverKind = CoverKinds.normalize(o.optString("coverKind")),
                    coverValue = o.optString("coverValue"),
                    tileShape = TileShapes.normalize(o.optString("tileShape")),
                )
            }
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
                    .put("rating", m.rating ?: 0.0)
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
                    rating = o.optDouble("rating", 0.0).takeIf { it > 0.0 },
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
