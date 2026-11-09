package com.hikari.app.ui.screens
import com.hikari.app.tv.TvUi
import com.hikari.app.tv.TvMode
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Tv
import com.hikari.app.i18n.tr
import com.hikari.app.lock.AppLock
import com.hikari.app.ui.Biometrics
import com.hikari.app.i18n.I18n

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Panorama
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material.icons.filled.SwitchAccount
import androidx.compose.material.icons.filled.ViewCarousel
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestorePage
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.window.Dialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.hikari.app.BuildConfig
import com.hikari.app.HikariApp
import com.hikari.app.data.BackupManager
import com.hikari.app.data.MangaMetadataRepository
import com.hikari.app.data.ProviderType
import com.hikari.app.data.Ratings
import com.hikari.app.data.TmdbLang
import com.hikari.app.data.TrackerClient
import com.hikari.app.data.TrackerKind
import com.hikari.app.data.Userscript
import com.hikari.app.tracker.TrackerSync
import com.hikari.app.download.DownloadService
import com.hikari.app.download.DownloadStatus
import com.hikari.app.download.DownloadsRepository
import com.hikari.app.net.AdBlocker
import com.hikari.app.net.DnsProviders
import com.hikari.app.net.DohDns
import com.hikari.app.net.NetTuning
import com.hikari.app.net.Updater
import com.hikari.app.player.EnhancePreset
import com.hikari.app.player.CustomEnhancePreset
import com.hikari.app.player.PlayerSkins
import com.hikari.app.player.DefaultPlayback
import com.hikari.app.ui.AppIconManager
import com.hikari.app.ui.AppIconVariants
import com.hikari.app.ui.AppFonts
import com.hikari.app.ui.AuraColors
import com.hikari.app.ui.LoadingEffects
import com.hikari.app.ui.LoadingStyles
import com.hikari.app.ui.PosterEffects
import com.hikari.app.ui.PosterTypes
import com.hikari.app.ui.components.ChoiceDialog
import com.hikari.app.ui.components.ChoiceItem
import com.hikari.app.ui.components.ChoiceRow
import com.hikari.app.ui.components.GlassCard
import com.hikari.app.ui.components.GlassShape
import com.hikari.app.ui.components.HeroStyles
import com.hikari.app.ui.components.MultiChoiceDialog
import com.hikari.app.ui.components.SettingsThemeTile
import com.hikari.app.ui.components.SettingsPageHeader
import com.hikari.app.data.Profiles
import com.hikari.app.ui.components.LocalHideHelp
import com.hikari.app.ui.components.TrackerLoginDialog
import com.hikari.app.ui.components.helpShown
import com.hikari.app.ui.LanguageManager
import com.hikari.app.ui.components.UpdateDialog
import com.hikari.app.ui.navigation.BottomTab
import com.hikari.app.ui.navigation.BottomTabs
import com.hikari.app.ui.navigation.LocalTaskbarInset
import com.hikari.app.ui.navigation.NavStyles
import com.hikari.app.ui.navigation.Routes
import com.hikari.app.ui.edgeLight
import com.hikari.app.ui.DISCORD_INVITE_URL
import com.hikari.app.ui.REDDIT_COMMUNITY_URL
import com.hikari.app.ui.openCommunity
import com.hikari.app.ui.openTelegram
import java.io.File
import com.hikari.app.ui.theme.HikariAccent
import com.hikari.app.ui.theme.HikariThemeMode
import com.hikari.app.ui.theme.inkOn
import com.hikari.app.web.UserscriptManager
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.hikari.app.tv.tvAdjust
import com.hikari.app.tv.tvToggle
import com.hikari.app.tv.tvTextFieldKeys
import com.hikari.app.tv.tvPress

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 72.dp),
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
    )
}

private enum class SettingsFolder(
    /** Stable id. Also what a sub-folder names as its [parent]. */
    val key: String,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    /** Id of the folder this one lives inside, or null for a top-level folder. */
    val parent: String? = null,
    /** A folder that only makes sense on a phone/tablet, so it is not offered at
     *  all on a television: it configures something the television layout does
     *  not have (the taskbar, the launcher icon aliases — a TV launcher shows
     *  the banner instead). See com.hikari.app.tv.TvMode. */
    val phoneOnly: Boolean = false,
) {
    APPEARANCE(
        "appearance",
        "Appearance & Theme",
        "Language, theme, accent, icon & font",
        Icons.Filled.Palette,
    ),
    APP_LAYOUT(
        "layout",
        "App Layout",
        "UI scale, posters, ratings, taskbar & full screen",
        Icons.Filled.Dashboard,
    ),
    PERFORMANCE(
        "performance",
        "Performance",
        "Fix lag, stutter & battery drain",
        Icons.Filled.Speed,
    ),
    TV(
        "tv",
        "TV & Remote",
        "Television layout, screen edges & performance",
        Icons.Filled.Tv,
    ),
    // An empty folder for now: a home for the app's personal customizations.
    CUSTOMIZE(
        "customize",
        "Customize",
        "Personalize the app",
        Icons.Filled.Tune,
    ),
    PLAYER(
        "player",
        "Player",
        "Controls, video enhancement & loading screen",
        Icons.Filled.PlayArrow,
    ),
    PLAYBACK_SERVERS(
        "servers",
        "Playback & Servers",
        "Where servers come from & when playback starts",
        Icons.Filled.SmartDisplay,
    ),
    NETWORK(
        "network",
        "Network and Internet",
        "DNS mode & slow connections",
        Icons.Filled.Public,
    ),
    SOURCES(
        "sources",
        "Sources & Extensions",
        "Installed extensions, userscripts & verification",
        Icons.Filled.Extension,
    ),
    CONTENT(
        "content",
        "Content & Filters",
        "Adult content & what the app shows you",
        Icons.Filled.Visibility,
    ),
    DOWNLOADS(
        "downloads",
        "Downloads",
        "Offline copies & parallel saves",
        Icons.Filled.Download,
    ),
    TRACKERS(
        "trackers",
        "Trackers",
        "Trakt, Simkl and watch-progress syncing",
        Icons.Filled.Sync,
    ),
    INTEGRATIONS(
        "integrations",
        "Integrations",
        "Custom metadata tweaks & more",
        Icons.Filled.ViewCarousel,
    ),
    INTEGRATIONS_TMDB(
        "integrations.tmdb",
        "TMDB Enrichment",
        "Artwork, metadata, episodes, credits & recommendations",
        Icons.Filled.Panorama,
        parent = "integrations",
    ),
    INTEGRATIONS_MDBLIST(
        "integrations.mdblist",
        "MDBList Ratings",
        "External IMDb, TMDB, Rotten Tomatoes & more",
        Icons.Filled.Star,
        parent = "integrations",
    ),
    INTEGRATIONS_ANIME(
        "integrations.anime",
        "Custom metadata tweaks",
        "AniList-first titles, episodes & next-airing",
        Icons.Filled.AutoAwesome,
        parent = "integrations",
    ),    // The user's own catalogs (Collections) live here rather than under
    CATALOG(
        "catalog",
        "Personal Catalog creator",
        "Your own collections, folders & catalogs",
        Icons.Filled.FolderOpen,
    ),
    PRIVACY(
        "privacy",
        "Privacy & Browsing",
        "Ad blocking, redirects & user agent",
        Icons.Filled.Shield,
    ),
    LOGS(
        "logs",
        "Logs & Diagnostics",
        "App logs & crash reports",
        Icons.Filled.BugReport,
    ),
    BACKUP(
        "backup",
        "Backup & Restore",
        "One file with your whole setup",
        Icons.Filled.SettingsBackupRestore,
    ),
    ABOUT(
        "about",
        "About & Updates",
        "Version, links & roadmap",
        Icons.Filled.Info,
    ),
    CLEAR_DATA(
        "clear-data",
        "Clear App data",
        "Delete everything Hikari has stored on this device",
        Icons.Filled.DeleteForever,
    ),

    // ---- Sub-folders (never listed on the index; see [parent]) ----

    APPEARANCE_COLORS(
        "appearance.colors",
        "Accent colour",
        "The app colour & the player's",
        Icons.Filled.ColorLens,
        parent = "appearance",
    ),
    APPEARANCE_FONT(
        "appearance.font",
        "App font",
        "The typeface used everywhere",
        Icons.Filled.TextFields,
        parent = "appearance",
    ),
    APPEARANCE_ICON(
        "appearance.icon",
        "App icon",
        "Your home-screen icon",
        Icons.Filled.Android,
        parent = "appearance",
    ),
    LAYOUT_POSTER(
        "layout.poster",
        "Poster styling",
        "Poster type, blur, corners, titles & score badges",
        Icons.Filled.Wallpaper,
        parent = "layout",
    ),
    LAYOUT_NAV(
        "layout.nav",
        "Taskbar & navigation",
        "Bar layout & which buttons stay",
        Icons.Filled.Tune,
        parent = "layout",
        // A television has no taskbar to configure — the app draws its
        // navigation rail there instead (see TvNavRail).
        phoneOnly = true,
    ),
    LAYOUT_EPISODES(
        "layout.episodes",
        "Episode UI & descriptions",
        "Episode layout, size & descriptions",
        Icons.Filled.Tv,
        parent = "layout",
    ),
}

/** A card on a folder page, spaced like every other card there. */
@Composable
private fun SettingsCard(
    top: Dp = 12.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = top),
        content = content,
    )
}

private val openSettingsSections = mutableStateMapOf<String, Boolean>()

@Composable
private fun SettingsSection(
    id: String,
    icon: ImageVector,
    title: String,
    summary: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val expanded = openSettingsSections[id] == true
    // The chevron turns over as the card opens rather than snapping.
    val turn by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "section-chevron",
    )
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { openSettingsSections[id] = !expanded }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingsThemeTile(icon, id)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (!summary.isNullOrBlank() && helpShown()) {
                    Text(
                        summary,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.rotate(turn),
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(Modifier.fillMaxWidth()) {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f),
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Spacer(Modifier.height(12.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    content = content,
                )
            }
        }
    }
}

/** One settings-search hit: where it lives and what opens it. */
private data class SettingsSearchEntry(
    val key: String,
    val title: String,
    val path: String,
    val folder: SettingsFolder,
    val sub: SettingsFolder? = null,
    val sectionId: String? = null,
    val keywords: String = "",
)

private fun settingsFolderByKey(key: String): SettingsFolder? =
    SettingsFolder.entries.firstOrNull { it.key == key }

/** Every setting worth finding, matched against title + path + keywords. */
private fun searchSettings(rawQuery: String): List<SettingsSearchEntry> {
    val tokens = rawQuery.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (tokens.isEmpty()) return emptyList()
    val folderKeywords = mapOf(
        "appearance" to "theme accent font language color icon",
        "layout" to "layout posters taskbar scale fullscreen ratings navigation",
        "performance" to "performance lag stutter battery speed",
        "tv" to "tv television remote overscan",
        "customize" to "customize personalize player ui buttons icon size colour color shape",
        "player" to "player video audio subtitles gestures loading enhance",
        "servers" to "playback servers play search extensions wait ask scope",
        "network" to "network dns slow internet",
        "sources" to "sources extensions repos userscripts verification",
        "content" to "content adult nsfw filter",
        "downloads" to "downloads offline save",
        "trackers" to "trackers sync library anilist mal kitsu simkl trakt",
        "integrations" to "integrations tmdb mdblist anime manga metadata ratings anilist simkl pornhwa manhwa",
        "catalog" to "catalog collections personal folders",
        "privacy" to "privacy ads block user agent webview",
        "logs" to "logs diagnostics crash report",
        "backup" to "backup restore transfer",
        "about" to "about version update roadmap",
        "clear-data" to "clear data delete reset",
        "integrations.tmdb" to "tmdb artwork metadata episodes credits",
        "integrations.mdblist" to "ratings imdb rotten tomatoes",
        "integrations.anime" to "custom tweaks anime anilist simkl episodes",
    )
    val out = ArrayList<SettingsSearchEntry>()
    for (f in SettingsFolder.entries) {
        val parent = f.parent?.let { pk -> SettingsFolder.entries.firstOrNull { it.key == pk } }
        out += SettingsSearchEntry(
            key = "folder-" + f.key,
            title = f.title,
            path = parent?.title ?: f.subtitle,
            folder = parent ?: f,
            sub = if (parent != null) f else null,
            keywords = (folderKeywords[f.key].orEmpty() + " " + f.key.replace(".", " ").replace("-", " ")),
        )
    }
    fun section(key: String, title: String, path: String, folderKey: String, subKey: String?, sectionId: String?, keywords: String) {
        val folder = settingsFolderByKey(folderKey) ?: return
        val sub = subKey?.let { settingsFolderByKey(it) }
        out += SettingsSearchEntry(key, title, path, folder, sub, sectionId, keywords)
    }
    section("sec-player-style", "Player UI style", "Customize", "customize", null, null, "player ui style buttons icons size bigger colour color shape round square circle position move opacity customize")
    section("sec-player-gestures", "Player gestures & decoder", "Customize", "customize", null, null, "player gestures decoder brightness volume swipe slide seek pinch zoom hardware software video")
    section("sec-default-playback", "Default quality & audio", "Player", "player", null, null, "default quality audio language voice dub hindi english 1080p 720p 480p resolution auto")
    val servers = "Playback & Servers"
    section("sec-play-start", "Playback start", servers, "servers", null, "player.start", "playback start play first server wait")
    section("sec-ask-where", "Ask where to play", servers, "servers", null, "player.start", "ask where play extension selected scope choice")
    section("sec-server-search", "Server search", servers, "servers", null, null, "server search all extensions nuvio stremio cloudstream hikari anymex aniyomi skystream sora vega choose")
    section("sec-tracker-servers", "Tracker servers", servers, "servers", null, null, "tracker servers search")
    section("sec-anime-meta", "Custom metadata tweaks", "Integrations", "integrations", "integrations.anime", null, "custom tweaks anime manga manhwa manhua pornhwa adult metadata anilist simkl episodes")
    section("sec-tmdb", "TMDB Enrichment", "Integrations", "integrations", "integrations.tmdb", "integrations.tmdb.main", "tmdb artwork metadata episodes credits")
    section("sec-tmdb-mod", "TMDB modules", "Integrations", "integrations", "integrations.tmdb", "integrations.tmdb.modules", "tmdb modules artwork")
    section("sec-mdb", "MDBList Ratings", "Integrations", "integrations", "integrations.mdblist", "integrations.mdblist.main", "ratings imdb rotten tomatoes")
    section("sec-mdb-prov", "MDBList providers", "Integrations", "integrations", "integrations.mdblist", "integrations.mdblist.providers", "ratings providers")
    return out.filter { e ->
        val hay = (e.title + " " + e.path + " " + e.keywords).lowercase()
        tokens.all { it in hay }
    }.sortedWith(compareBy(
        { e -> tokens.count { e.title.lowercase().contains(it) } == 0 },
        { e -> e.sectionId == null },
        { e -> e.title },
    )).take(30)
}

@Composable
fun SettingsScreen(nav: NavHostController) {
    val context = LocalContext.current
    val app = context.applicationContext as HikariApp
    val scope = rememberCoroutineScope()
    val isTv = TvMode.current()

    var themeMenuOpen by remember { mutableStateOf(false) }
    var checkingUpdates by remember { mutableStateOf(false) }
    var updateStatus by remember { mutableStateOf<Updater.UpdateStatus?>(null) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var openFolder by remember { mutableStateOf<SettingsFolder?>(null) }
    var settingsQuery by remember { mutableStateOf("") }
    val searchHits = remember(settingsQuery) {
        if (settingsQuery.isBlank()) emptyList() else searchSettings(settingsQuery)
    }
    var openSub by remember { mutableStateOf<SettingsFolder?>(null) }
    // Settings -> Customize -> Player UI style: its own full page (a live
    // preview + one editor per button). Gestures & decoder is a full page too.
    var showPlayerStyle by remember { mutableStateOf(false) }
    var showPlayerGestures by remember { mutableStateOf(false) }
    var showStats by remember { mutableStateOf(false) }
    var showPair by remember { mutableStateOf(false) }
    var showLogs by remember { mutableStateOf(false) }
    // Settings → Profiles: its own page, like Logs and Pair — a list that is
    // created, renamed, deleted and switched between does not fit in a card.
    var showProfiles by remember { mutableStateOf(false) }
    var showClearDataDialog by remember { mutableStateOf(false) }

    // Accent colours: the app accent repaints this whole screen live; the
    // player accent (and the "match app & player" switch) decide what the
    // View-based player will use.
    val appAccentFlow = remember { app.store.appAccentFlow() }
    val appAccentKey by appAccentFlow.collectAsState(initial = HikariAccent.DEFAULT_APP.key)
    val themeFlow = remember { app.store.themeFlow() }
    val storedThemeKey by themeFlow.collectAsState(initial = HikariThemeMode.DEFAULT.key)
    var themeKey by remember { mutableStateOf(storedThemeKey) }
    LaunchedEffect(storedThemeKey) { themeKey = storedThemeKey }
    val playerAccentFlow = remember { app.store.playerAccentFlow() }
    val playerAccentKey by playerAccentFlow.collectAsState(
        initial = HikariAccent.DEFAULT_PLAYER.key
    )
    val themeLinkedFlow = remember { app.store.themeLinkedFlow() }
    val themeLinked by themeLinkedFlow.collectAsState(initial = false)

    val currentTheme = remember(themeKey) { HikariThemeMode.fromKey(themeKey) }
    val hideContinueFlow = remember { app.store.hideContinueFlow() }
    val hideContinue by hideContinueFlow.collectAsState(initial = false)
    val languageFlow = remember { app.store.languageFlow() }
    val appLanguage by languageFlow.collectAsState(initial = "")
    val installedProviders by app.providers.providers.collectAsState()
    // Page positions held across visits too (see ScrollKeeper): leaving
    // Settings entirely and coming back lands each page where it was.
    val indexState = com.hikari.app.ui.components.keptListState("settings:index")
    val folderState = com.hikari.app.ui.components.keptListState("settings:folder:$openFolder")
    val subState = com.hikari.app.ui.components.keptListState("settings:sub:$openFolder|$openSub")
    // Television two-pane: the left category list scrolls on its own.
    val tvLeftState = com.hikari.app.ui.components.keptListState("settings:tvLeft")
    // ONE state per PAGE — the index, the open folder, and a sub-folder of it —
    // because one state cannot remember three positions. With a single state (and
    // then with two), going back from a deeper page had to scroll the page it
    // landed on to its top, since the deeper page's position was the only one
    // the state knew; that is what threw the page you came back to up to the
    // top. Each page keeping its own state means the position is simply still
    // there — nothing has to be reset on the way back (see [openFolderPage] and
    // [openSubPage], which are where a page's top is requested now).
    val listState = when {
        openFolder == null -> indexState
        openSub != null -> subState
        else -> folderState
    }
    // Profiles: the index row says which setup is in use (see ProfilesScreen).
    val profileList by Profiles.all.collectAsState()
    val profileActive by Profiles.activeId.collectAsState()

    BackHandler(
        enabled = openSub != null || (openFolder != null && !isTv) || showStats || showLogs ||
            showPair || showProfiles || showPlayerStyle || showPlayerGestures,
    ) {
        when {
            showPlayerStyle -> showPlayerStyle = false
            showPlayerGestures -> showPlayerGestures = false
            showLogs -> showLogs = false
            showStats -> showStats = false
            showPair -> showPair = false
            showProfiles -> showProfiles = false
            openSub != null -> openSub = null
            else -> if (!isTv) openFolder = null
        }
    }

    // Gestures & decoder (Customize folder) is a full screen as well.
    if (showPlayerGestures) {
        PlayerGesturesPage(app, onBack = { showPlayerGestures = false })
        return
    }

    // The Player UI style editor (Customize folder) is a full screen as well:
    // a pinned live preview above one editor per button.
    if (showPlayerStyle) {
        PlayerStylePage(app, onBack = { showPlayerStyle = false })
        return
    }

    // The logs page is its own full screen too: three files, each with two
    // actions, plus the share-all row.
    if (showLogs) {
        LogsPage(app, onBack = { showLogs = false })
        return
    }

    if (showStats) {
        StatsScreen(
            app,
            onBack = { showStats = false },
            // Same as the Stats tab's own door: a row opens the title it is
            // about (see Routes.fromStatsKey).
            onOpenTitle = { t ->
                com.hikari.app.ui.navigation.Routes
                    .fromStatsKey(t.key, t.title, t.posterUrl)
                    ?.let { nav.navigate(it) }
            },
        )
        return
    }

    // Pair & sync is its own page for the same reason the player-control editor
    // is: two ends of a transfer, a QR code and a camera do not fit in a card.
    if (showPair) {
        PairScreen(app, onBack = { showPair = false })
        return
    }

    // Profiles is a page too: the list of setups, what each of them holds, and
    // the actions on them (see ProfilesScreen).
    if (showProfiles) {
        ProfilesScreen(app, onBack = { showProfiles = false })
        return
    }

    // A page the user OPENED arrives at its own top with its cards folded; the
    // page they come BACK to is left exactly as they left it. That is why the
    // reset lives here, in the two functions that open a page, and not in an
    // effect keyed on the open page: an effect cannot tell "opened" from "came
    // back to", so it also scrolled the page you returned to up to the top — the
    // report was Settings → App Layout → Poster styling, back, and land at the
    // top of App Layout instead of on the row that was tapped.
    //
    // `requestScrollToItem` (not `scrollToItem`) because it primes the position
    // for the page's NEXT measure instead of scrolling a list that is not
    // attached yet — so the page's very first frame is already at its top, with
    // no flash of the position it was left at.
    fun openFolderPage(target: SettingsFolder) {
        folderState.requestScrollToItem(0)
        openSettingsSections.clear()
        openSub = null
        openFolder = target
    }

    fun openSubPage(target: SettingsFolder) {
        subState.requestScrollToItem(0)
        openSettingsSections.clear()
        openSub = target
    }

    // Top-level folders offered on this device — the TV left pane, and the
    // phone index below, read the same list so the two can never disagree.
    val tvTopFolders = remember(isTv) {
        SettingsFolder.entries.filter { it.parent == null && (!isTv || !it.phoneOnly) }
    }
    val settingsListBody: LazyListScope.() -> Unit = {
        val folder = openFolder ?: if (isTv) tvTopFolders.firstOrNull() else null
        val sub = openSub
        if (folder != null) {
            item {
                FolderHeader(
                    folder = sub ?: folder,
                    parentTitle = if (sub != null) folder.title else null,
                    onBack = { if (sub != null) openSub = null else if (!isTv) openFolder = null else nav.popBackStack() },
                )
            }
            when (sub ?: folder) {
                SettingsFolder.PLAYER -> {
                    item { SettingsCard(top = 2.dp) { VideoEnhanceCard(app) } }
                    item { SettingsCard { PlayerUiCard(app) } }
                    item { SettingsCard { DefaultPlaybackCard(app) } }
                    item { SettingsCard { HoldToFastForwardCard(app) } }
                    item { SettingsCard { PlayerIconLabelsCard(app) } }

                    item { SettingsCard { ExternalPlayerCard(app) } }
                    item { SettingsCard { TvPlayerPanelsCard(app) } }
                    item { SettingsCard { VolumeBoostCard(app) } }
                    item { SettingsCard { SubtitlesDefaultCard(app) } }
                    item { SettingsCard { LoadingBannerCard(app) } }
                }
                SettingsFolder.PLAYBACK_SERVERS -> {
                    item { SettingsCard(top = 2.dp) { ServerSearchCard(app) } }
                    item { SettingsCard { TrackerServerSearchCard(app) } }
                    item { SettingsCard { ServerTimeoutCard(app) } }
                    item { SettingsCard { PlaybackStartCard(app) } }
                    item { SettingsCard { ServerLayoutCard(app) } }
                }
                SettingsFolder.NETWORK -> {
                    item {
                        SettingsCard(top = 2.dp) {
                            DnsModeCard(app)
                        }
                    }
                    item { SettingsCard { SlowConnectionCard(app) } }
                }
                SettingsFolder.SOURCES -> {
                    item {
                        SettingsCard(top = 2.dp) {
                            ExtensionsShortcutCard(installedProviders.size) {
                                Routes.navigateTab(nav, Routes.EXTENSIONS)
                            }
                        }
                    }
                    item { SettingsCard { UserscriptsCard(app) } }
                    item { SettingsCard { ExtensionVerifyCard(app) } }
                }
                SettingsFolder.CONTENT -> {
                    item { SettingsCard(top = 2.dp) { AdultContentCard(app) } }
                }
                SettingsFolder.DOWNLOADS -> {
                    item { SettingsCard(top = 2.dp) { DownloadSettingsCard(app) } }
                }
                SettingsFolder.TRACKERS -> {
                    item { SettingsCard(top = 2.dp) { TrackersCard(app) } }
                }
                SettingsFolder.INTEGRATIONS -> {
                    // TMDB Enrichment + MDBList Ratings stay installed but hidden:
                    // their switches now live in Custom metadata tweaks.
                    SettingsFolder.entries
                        .filter {
                            it.parent == SettingsFolder.INTEGRATIONS.key &&
                                it.key != SettingsFolder.INTEGRATIONS_TMDB.key &&
                                it.key != SettingsFolder.INTEGRATIONS_MDBLIST.key
                        }
                        .forEach { target ->
                            item {
                                SettingsFolderRow(
                                    folder = target,
                                    onClick = { openSubPage(target) },
                                )
                            }
                        }
                }
                SettingsFolder.INTEGRATIONS_TMDB -> {
                    item { SettingsCard(top = 2.dp) { TmdbIntegrationCard(app) } }
                    item { SettingsCard { TmdbModulesCard(app) } }
                }
                SettingsFolder.INTEGRATIONS_MDBLIST -> {
                    item { SettingsCard(top = 2.dp) { MdbListIntegrationCard(app) } }
                    item { SettingsCard { MdbListProvidersCard(app) } }
                }
                SettingsFolder.INTEGRATIONS_ANIME -> {
                    item { SettingsCard(top = 2.dp) { AnimeMetadataIntegrationCard(app) } }
                }
                SettingsFolder.APPEARANCE -> {
                    item { SettingsCard(top = 2.dp) { LanguageCard(app, appLanguage) } }
                    item { SettingsCard { TmdbLanguageCard(app, appLanguage) } }
                    item {
                        SettingsCard {
                            Box {
                                ListItem(
                                    leadingContent = {
                                        Icon(
                                            if (currentTheme == HikariThemeMode.LIGHT) Icons.Filled.LightMode
                                            else Icons.Filled.DarkMode,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    },
                                    headlineContent = { Text(tr("Theme")) },
                                    supportingContent = { Text(currentTheme.label) },
                                    trailingContent = {
                                        Icon(
                                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    },
                                    modifier = Modifier.clickable { themeMenuOpen = true }
                                )
                                if (themeMenuOpen) {
                                    ChoiceDialog(
                                        title = tr("Theme"),
                                        items = HikariThemeMode.entries.map {
                                            ChoiceItem(it.key, it.label)
                                        },
                                        selectedKey = themeKey,
                                        onPick = { pick ->
                                            themeKey = pick
                                            scope.launch { app.store.setTheme(pick) }
                                        },
                                        onDismiss = { themeMenuOpen = false },
                                    )
                                }
                            }
                        }
                    }
                    SettingsFolder.entries
                        .filter { it.parent == SettingsFolder.APPEARANCE.key }
                        .filter { !isTv || !it.phoneOnly }
                        .forEach { target ->
                            item {
                                SettingsFolderRow(
                                    folder = target,
                                    onClick = { openSubPage(target) },
                                )
                            }
                        }
                }
                SettingsFolder.APPEARANCE_ICON -> {
                    item { SettingsCard(top = 2.dp) { AppIconCard(app) } }
                }
                SettingsFolder.APPEARANCE_COLORS -> {
                    item {
                        SettingsCard(top = 2.dp) {
                            AccentCard(
                                app = app,
                                appAccentKey = appAccentKey,
                                playerAccentKey = playerAccentKey,
                                linked = themeLinked,
                            )
                        }
                    }
                    item {
                        SettingsCard {
                            MatchThemeCard(
                                app = app,
                                linked = themeLinked,
                                appAccentKey = appAccentKey,
                                playerAccentKey = playerAccentKey,
                            )
                        }
                    }
                }
                // ---- App Layout: how a page is arranged ----
                SettingsFolder.APP_LAYOUT -> {
                    item { SettingsCard(top = 2.dp) { UiScaleCard(app) } }
                    item { SettingsCard { DetailRatingCard(app) } }
                    item { SettingsCard { HeroBannerCard(app) } }
                    item { SettingsCard { DetailHeaderCard(app) } }
                    item { SettingsCard { ContinueWatchingCard(app, hideContinue, scope) } }
                    item { SettingsCard { GifAnimCard(app) } }
                    item { SettingsCard { HideHelpCard(app) } }
                    SettingsFolder.entries
                        .filter { it.parent == SettingsFolder.APP_LAYOUT.key }
                        .filter { !isTv || !it.phoneOnly }
                        .forEach { target ->
                            item {
                                SettingsFolderRow(
                                    folder = target,
                                    top = 12.dp,
                                    onClick = { openSubPage(target) },
                                )
                            }
                        }
                    if (!isTv) {
                        item { SettingsCard { FullscreenCard(app) } }
                    }
                }
                SettingsFolder.LAYOUT_POSTER -> {
                    item { SettingsCard(top = 2.dp) { PosterStyleCard(app) } }
                }
                // ---- Performance: the switch for a device that cannot keep up ----
                SettingsFolder.PERFORMANCE -> {
                    item { SettingsCard(top = 2.dp) { PerformanceBoosterCard(app) } }
                }                SettingsFolder.APPEARANCE_FONT -> {
                    item { SettingsCard(top = 2.dp) { FontCard(app) } }
                }
                SettingsFolder.LAYOUT_NAV -> {
                    item { SettingsCard(top = 2.dp) { NavBarCard(app) } }
                    item { SettingsCard { TaskbarCard(app) } }
                }
                SettingsFolder.LAYOUT_EPISODES -> {
                    item { SettingsCard(top = 2.dp) { EpisodeViewModeCard(app) } }
                    item { SettingsCard { TvEpisodeSizeCard(app) } }
                    item { SettingsCard { EpisodeDescriptionCard(app) } }
                }
                SettingsFolder.TV -> {
                    item { SettingsCard(top = 2.dp) { TvDeviceCard(app) } }
                    item { SettingsCard { TvPosterSizeCard(app) } }
                    item { SettingsCard { TvTextSizeCard(app) } }
                    item { SettingsCard { TvDetailStyleCard(app) } }
                    item { SettingsCard { TvOverscanCard(app) } }
                    item { SettingsCard { TvPerformanceCard(app) } }
                    item { SettingsCard { TvRemoteCard() } }
                }
                SettingsFolder.CUSTOMIZE -> {
                    item {
                        SettingsCard(top = 2.dp) {
                            SettingsSection(
                                id = "customize.background",
                                icon = Icons.Filled.Wallpaper,
                                title = tr("Custom background"),
                                summary = tr("Use your own picture as the app background"),
                            ) {
                                com.hikari.app.ui.CustomBackgroundContent()
                            }
                        }
                    }
                    item { SettingsCard { PlayerStyleCard(onOpen = { showPlayerStyle = true }) } }
                    item { SettingsCard { PlayerGesturesCard(onOpen = { showPlayerGestures = true }) } }
                }
                SettingsFolder.CATALOG -> {
                    item {
                        SettingsCard(top = 2.dp) {
                            CollectionsCard(onOpen = { Routes.safeNavigate(nav, Routes.COLLECTIONS) })
                        }
                    }
                }
                SettingsFolder.PRIVACY -> {
                    item { SettingsCard(top = 2.dp) { AppLockCard(app) } }
                    item { SettingsCard { MyStuffLockCard(app) } }
                    item { SettingsCard { AdBlockingCard(app) } }
                    item { SettingsCard { WebViewSafetyCard(app) } }
                    item { SettingsCard { WebViewUserAgentCard(app) } }
                }
                SettingsFolder.LOGS -> {
                    item {
                        SettingsCard(top = 2.dp) {
                            Column {
                                ListItem(
                                    leadingContent = {
                                        Icon(
                                            Icons.Filled.BugReport,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    },
                                    headlineContent = { Text(tr("App logs & crash reports")) },
                                    supportingContent = {
                                        Text(
                                            tr("Share the log files instead of a screenshot")
                                        )
                                    },
                                    trailingContent = {
                                        Icon(
                                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    },
                                    modifier = Modifier.clickable { showLogs = true }
                                )
                            }
                        }
                    }
                    item {
                        Text(
                            tr("Stays on this device until you share it."),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 10.dp, start = 4.dp),
                        )
                    }
                }
                SettingsFolder.BACKUP -> {
                    item { SettingsCard(top = 2.dp) { BackupCard(app, onPair = { showPair = true }) } }
                }
                SettingsFolder.ABOUT -> {
                    item {
                        SettingsCard(top = 2.dp) {
                            Column {
                                ListItem(
                                    leadingContent = {
                                        Icon(
                                            Icons.Filled.CheckCircle,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    },
                                    headlineContent = { Text(tr("Version")) },
                                    supportingContent = {
                                        Text(BuildConfig.VERSION_NAME + I18n.t(" (build ") + BuildConfig.VERSION_CODE + ")")
                                    }
                                )
                                SettingsDivider()
                                ListItem(
                                    leadingContent = {
                                        Icon(
                                            Icons.Filled.SystemUpdate,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    },
                                    headlineContent = { Text(tr("Check for updates")) },
                                    supportingContent = {
                                        if (checkingUpdates) {
                                            Text(tr("Checking GitHub…"))
                                        } else {
                                            Text(tr("Version ") + Updater.currentVersion())
                                        }
                                    },
                                    trailingContent = {
                                        if (checkingUpdates) {
                                            CircularProgressIndicator(
                                                Modifier.size(20.dp),
                                                strokeWidth = 2.dp
                                            )
                                        }
                                    },
                                    modifier = Modifier.clickable {
                                        if (!checkingUpdates) {
                                            checkingUpdates = true
                                            scope.launch {
                                                updateStatus = runCatching { Updater.checkForUpdate() }.getOrNull()
                                                checkingUpdates = false
                                                showUpdateDialog = true
                                            }
                                        }
                                    }
                                )
                                SettingsDivider()
                                ListItem(
                                    leadingContent = {
                                        Icon(
                                            Icons.Filled.OpenInNew,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    },
                                    headlineContent = { Text(tr("GitHub")) },
                                    supportingContent = { Text(tr("github.com/codegeasse1/hikari — releases & source")) },
                                    trailingContent = {
                                        Icon(
                                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    },
                                    modifier = Modifier.clickable {
                                        context.startActivity(
                                            Intent(
                                                Intent.ACTION_VIEW,
                                                Uri.parse("https://github.com/codegeasse1/hikari")
                                            )
                                        )
                                    }
                                )
                                SettingsDivider()
                                ListItem(
                                    leadingContent = {
                                        Icon(
                                            Icons.Filled.Send,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    },
                                    headlineContent = { Text(tr("Telegram")) },
                                    supportingContent = { Text(tr("t.me/CodegeasseHikari — help, bugs & feature requests")) },
                                    trailingContent = {
                                        Icon(
                                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    },
                                    modifier = Modifier.clickable {
                                        openTelegram(context)
                                    }
                                )
                                SettingsDivider()
                                ListItem(
                                    leadingContent = {
                                        Icon(
                                            Icons.Filled.Forum,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    },
                                    headlineContent = { Text(tr("Reddit")) },
                                    supportingContent = { Text(tr("r/HikariApp — help, discussion & release news")) },
                                    trailingContent = {
                                        Icon(
                                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    },
                                    modifier = Modifier.clickable {
                                        openCommunity(context, REDDIT_COMMUNITY_URL)
                                    }
                                )
                                SettingsDivider()
                                ListItem(
                                    leadingContent = {
                                        Icon(
                                            Icons.Filled.Chat,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    },
                                    headlineContent = { Text(tr("Discord")) },
                                    supportingContent = { Text(tr("Hikari server — help, discussion & release news")) },
                                    trailingContent = {
                                        Icon(
                                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    },
                                    modifier = Modifier.clickable {
                                        openCommunity(context, DISCORD_INVITE_URL)
                                    }
                                )
                            }
                        }
                    }
                    item { SettingsCard { RoadmapCard() } }
                    item { SettingsCard { AboutCard() } }
                }
                SettingsFolder.CLEAR_DATA -> {
                    item { SettingsCard(top = 2.dp) { ClearDataCard(onClear = { showClearDataDialog = true }) } }
                }
            }
        } else if (!isTv) {
            item {
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        tr("Settings"),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = settingsQuery,
                        onValueChange = { settingsQuery = it },
                        placeholder = { Text(tr("Search settings")) },
                        leadingIcon = {
                            Icon(
                                Icons.Filled.Tune,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(14.dp))
                }
            }
            if (settingsQuery.isBlank()) {
            SettingsFolder.entries
                .filter { it.parent == null && (!isTv || !it.phoneOnly) }
                .forEach { target ->
                item {
                    SettingsFolderRow(folder = target, onClick = { openFolderPage(target) })
                }
            }
            } else {
                val hits = searchHits
                if (hits.isEmpty()) {
                    item {
                        Text(
                            tr("No settings match that search"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                        )
                    }
                }
                hits.forEach { hit ->
                    item(key = "search-" + hit.key) {
                        SettingsCard(top = 8.dp) {
                            ListItem(
                                leadingContent = {
                                    SettingsThemeTile(
                                        hit.folder.icon,
                                        hit.folder.key,
                                        34.dp
                                    )
                                },
                                headlineContent = { Text(tr(hit.title)) },
                                supportingContent = { Text(tr(hit.path)) },
                                trailingContent = {
                                    Icon(
                                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                },
                                modifier = Modifier.clickable {
                                    if (hit.sub != null) {
                                        val parent = SettingsFolder.entries.firstOrNull { it.key == hit.sub.parent }
                                        if (parent != null) openFolderPage(parent)
                                        openSubPage(hit.sub)
                                    } else {
                                        openFolderPage(hit.folder)
                                    }
                                    if (hit.sectionId != null) openSettingsSections[hit.sectionId] = true
                                }
                            )
                        }
                    }
                }
            }
            item {
                SettingsCard(top = 12.dp) {
                    ListItem(
                        leadingContent = {
                            SettingsThemeTile(Icons.Filled.BarChart, "Stats", 40.dp)
                        },
                        headlineContent = { Text(tr("Stats")) },
                        supportingContent = {
                            if (helpShown()) Text(tr("Time spent, streaks and what you watched most"))
                        },
                        trailingContent = {
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        modifier = Modifier.clickable { showStats = true }
                    )
                }
            }
            item {
                SettingsCard(top = 12.dp) {
                    ListItem(
                        leadingContent = {
                            SettingsThemeTile(Icons.Filled.SwitchAccount, "Profiles", 40.dp)
                        },
                        headlineContent = { Text(tr("Profiles")) },
                        supportingContent = {
                            if (helpShown()) {
                                val activeName = profileList.firstOrNull { it.id == profileActive }?.name
                                Text(
                                    if (activeName == null) {
                                        tr("Separate setups on one device — extensions, Library, history & settings")
                                    } else {
                                        tr("In use") + ": " + activeName + " — " +
                                            tr("tap to switch, rename or add another")
                                    }
                                )
                            }
                        },
                        trailingContent = {
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        modifier = Modifier.clickable { showProfiles = true }
                    )
                }
            }
        }
    }
    // Television draws the categories and the folder side by side; the phone
    // keeps its single column. Both read the same body above.
    if (isTv) {
        Row(Modifier.fillMaxSize()) {
            LazyColumn(
                state = tvLeftState,
                modifier = Modifier
                    .weight(0.36f)
                    .fillMaxHeight(),
                contentPadding = PaddingValues(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 16.dp),
            ) {
                item(key = "tv-left-title") {
                    Text(
                        tr("Settings"),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = com.hikari.app.tv.TvDesktop.TextPrimary,
                        modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
                    )
                }
                // The television rail has only five tabs, so the pages that are
                // no longer on it are opened from here.
                item(key = "tv-left-extensions") {
                    TvLeftRow(
                        icon = Icons.Filled.Extension,
                        title = tr("Extensions"),
                        onClick = { Routes.navigateTab(nav, Routes.EXTENSIONS) },
                    )
                }
                item(key = "tv-left-manga") {
                    TvLeftRow(
                        icon = Icons.Filled.AutoStories,
                        title = tr("Manga"),
                        onClick = { Routes.navigateTab(nav, Routes.MANGA) },
                    )
                }
                item(key = "tv-left-iptv") {
                    TvLeftRow(
                        icon = Icons.Filled.LiveTv,
                        title = tr("IPTV"),
                        onClick = { Routes.navigateTab(nav, Routes.IPTV) },
                    )
                }
                item(key = "tv-left-telegram") {
                    TvLeftRow(
                        icon = Icons.Filled.Send,
                        title = tr("Telegram"),
                        onClick = { Routes.navigateTab(nav, Routes.TELEGRAM) },
                    )
                }
                tvTopFolders.forEach { target ->
                    item(key = "tv-left-" + target.key) {
                        SettingsFolderRow(
                            folder = target,
                            top = 8.dp,
                            selected = target == (openFolder ?: tvTopFolders.firstOrNull()),
                            onClick = { openFolderPage(target) },
                        )
                    }
                }
                item(key = "tv-left-stats") {
                    TvLeftRow(
                        icon = Icons.Filled.BarChart,
                        title = tr("Stats"),
                        onClick = { showStats = true },
                    )
                }
                item(key = "tv-left-profiles") {
                    TvLeftRow(
                        icon = Icons.Filled.SwitchAccount,
                        title = tr("Profiles"),
                        onClick = { showProfiles = true },
                    )
                }
            }
            LazyColumn(
                state = folderState,
                modifier = Modifier
                    .weight(0.64f)
                    .fillMaxHeight(),
                contentPadding = PaddingValues(start = 8.dp, end = 16.dp, top = 16.dp, bottom = 16.dp),
            ) {
                settingsListBody()
            }
        }
    } else {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 16.dp,
                // Clear of the floating taskbar (0 when there is no bar).
                bottom = LocalTaskbarInset.current + 16.dp,
            ),
        ) {
            settingsListBody()
        }
    }

    if (showUpdateDialog) {
        UpdateDialog(
            context = context,
            onDismiss = { showUpdateDialog = false },
            initialStatus = updateStatus,
        )
    }

    if (showClearDataDialog) {
        AlertDialog(
            onDismissRequest = { showClearDataDialog = false },
            title = { Text(tr("Clear all data?")) },
            text = {
                Text(
                    tr(
                        "All your data will be deleted: every setting, signed-in " +
                            "account, catalog, favorite, watch-history entry and " +
                            "download record Hikari has stored on this device. " +
                            "This cannot be undone."
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showClearDataDialog = false
                    scope.launch {
                        app.store.clearAll()
                        Toast.makeText(context, I18n.t("All data cleared"), Toast.LENGTH_SHORT).show()
                    }
                }) { Text(tr("Clear"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearDataDialog = false }) { Text(tr("Cancel")) }
            },
        )
    }
}

@Composable
private fun FolderHeader(
    folder: SettingsFolder,
    parentTitle: String? = null,
    onBack: () -> Unit,
) {
    SettingsPageHeader(
        title = tr(folder.title),
        subtitle = tr(folder.subtitle),
        icon = folder.icon,
        breadcrumb = parentTitle?.let { tr(it) + " ›" },
        onBack = onBack,
    )
}

/** One folder on the index — and one sub-folder inside a folder page: badge,
 *  name, what is inside, and its own chevron. [top] is the gap above it, so a
 *  sub-folder row can sit tighter under its parent's heading. [selected] is the
 *  television left pane's answer to "which category is open". */
@Composable
private fun SettingsFolderRow(
    folder: SettingsFolder,
    top: Dp = 12.dp,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    GlassCard(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = top)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Theme tile for every folder AND sub-folder: no picture icons, and the
            // tile follows the selected accent colour.
            SettingsThemeTile(folder.icon, folder.key)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    tr(folder.title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (selected) {
                        // Reference purple on TV, the phone's own accent setting.
                        if (com.hikari.app.tv.TvMode.current()) com.hikari.app.tv.Cs3.accent
                        else MaterialTheme.colorScheme.primary
                    }
                    else if (com.hikari.app.tv.TvMode.current()) com.hikari.app.tv.TvDesktop.TextPrimary
                    else MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                if (helpShown()) {
                    Text(
                        tr(folder.subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (com.hikari.app.tv.TvMode.current()) com.hikari.app.tv.TvDesktop.TextMuted
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = if (selected) {
                    if (com.hikari.app.tv.TvMode.current()) com.hikari.app.tv.Cs3.accent
                    else MaterialTheme.colorScheme.primary
                }
                else if (com.hikari.app.tv.TvMode.current()) com.hikari.app.tv.TvDesktop.TextMuted
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/** One compact category row in the television left pane (Stats, Profiles) —
 *  the same shape as a folder row, without a folder behind it. */
@Composable
private fun TvLeftRow(icon: ImageVector, title: String, onClick: () -> Unit) {
    GlassCard(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SettingsThemeTile(icon, title)
            Spacer(Modifier.width(16.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = com.hikari.app.tv.TvDesktop.TextPrimary,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = com.hikari.app.tv.TvDesktop.TextMuted,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun ExtensionsShortcutCard(installed: Int, onOpen: () -> Unit) {
    GlassCard(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SettingsThemeTile(Icons.Filled.Extension, "Extensions", 44.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    tr("Extensions"),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(3.dp))
                if (!LocalHideHelp.current) {
                Text(
                    if (installed > 0) {
                        "$installed installed — browse repos, install or remove extensions."
                    } else {
                        "Browse repos and install .hiki / CloudStream extensions."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                }
            }
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier
                    .size(28.dp)
                    .clip(GlassShape)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun ContinueWatchingCard(
    app: HikariApp,
    hideContinue: Boolean,
    scope: CoroutineScope,
) {
    Column(Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    tr("Continue Watching"),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    tr("Collects progress from every extension"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = !hideContinue,
                onCheckedChange = { show ->
                    scope.launch { app.store.setHideContinue(!show) }
                },
                // A remote cannot land on a bare Switch at the end of a row —
                // see [Modifier.tvToggle].
                modifier = Modifier.tvToggle(!hideContinue) { show ->
                    scope.launch { app.store.setHideContinue(!show) }
                },
            )
        }
    }
}

@Composable
private fun GifAnimCard(app: HikariApp) {
    val scope = rememberCoroutineScope()
    val flow = remember { app.store.gifAnimFlow() }
    val on by flow.collectAsState(initial = true)
    Column(Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    tr("Animate covers"),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(4.dp))
                if (!LocalHideHelp.current) {
                Text(
                    tr(
                        "Play animated folder covers (GIFs) in your personal catalogs. Off " +
                            "draws their first frame — lighter on a TV stick."
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                }
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = on,
                onCheckedChange = { v -> scope.launch { runCatching { app.store.setGifAnim(v) } } },
                // See [Modifier.tvToggle].
                modifier = Modifier.tvToggle(on) { v ->
                    scope.launch { runCatching { app.store.setGifAnim(v) } }
                },
            )
        }
    }
}

@Composable
private fun HideHelpCard(app: HikariApp) {
    val scope = rememberCoroutineScope()
    val flow = remember { app.store.hideHelpFlow() }
    val hidden by flow.collectAsState(initial = false)
    Column(Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    tr("Explanations"),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(4.dp))
                if (helpShown()) {
                    Text(
                        tr(
                            "Off hides the small grey notes that explain a setting, in " +
                                "every screen — a cleaner, shorter app. The settings " +
                                "themselves are untouched."
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = !hidden,
                onCheckedChange = { on -> scope.launch { runCatching { app.store.setHideHelp(!on) } } },
                // See [Modifier.tvToggle].
                modifier = Modifier.tvToggle(!hidden) { on ->
                    scope.launch { runCatching { app.store.setHideHelp(!on) } }
                },
            )
        }
    }
}

@Composable
private fun AdultContentCard(app: HikariApp) {
    val scope = rememberCoroutineScope()
    val flow = remember { app.store.nsfwEnabledFlow() }
    val on by flow.collectAsState(initial = true)
    Column(Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    tr("Adult content"),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(4.dp))
                if (!LocalHideHelp.current) {
                Text(
                    tr(
                        "On (the default) shows everything your extensions publish. Off " +
                            "hides adult and R-rated titles and 18+ extensions — their " +
                            "catalogues, shelves and search results stay away until you " +
                            "turn it back on."
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                }
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = on,
                onCheckedChange = { v ->
                    scope.launch { runCatching { app.store.setNsfwEnabled(v) } }
                },
                // See [Modifier.tvToggle].
                modifier = Modifier.tvToggle(on) { v ->
                    scope.launch { runCatching { app.store.setNsfwEnabled(v) } }
                },
            )
        }
    }
}

@Composable
private fun RoadmapCard() {
    Column(Modifier.padding(16.dp)) {
        Text(
            tr("Roadmap"),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(6.dp))
        if (!LocalHideHelp.current) {
        Text(
            tr("✓ Stremio addons\n" + "✓ Universal scrapers\n") +
                I18n.t("✓ HLS/DASH player with headers + subtitles\n") +
                I18n.t("✓ CloudStream .cs3 plugin loader\n") +
                I18n.t("✓ Torrent engine for infoHash streams\n") +
                I18n.t("✓ Watch history + Continue Watching (all extensions)\n") +
                I18n.t("✓ Downloads — offline copies, export to phone storage, concurrent limit\n") +
                I18n.t("✓ SkyStream .sky extensions (scriptable JS providers)\n") +
                I18n.t("✓ Trackers — AniList, MyAnimeList, Kitsu, Simkl, Shikimori & Trakt\n") +
                I18n.t("• Manga reading progress on your tracker (AniList + MyAnimeList)"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        }
    }
}

@Composable
private fun AboutCard() {
    Column(Modifier.padding(16.dp)) {
        Text(
            tr("About"),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(6.dp))
        Text(
            tr("A universal streaming app built from scratch."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ClearDataCard(onClear: () -> Unit) {
    Column(Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(GlassShape)
                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.DeleteForever,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    tr("Clear all data"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    tr("Every setting, account, catalog, favorite and history entry this app has stored"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(
            tr("This cannot be undone: the app returns to how it looked the first time you opened it."),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        TextButton(onClick = onClear) {
            Icon(
                Icons.Filled.DeleteSweep,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(tr("Clear all data"), color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun DownloadSettingsCard(app: HikariApp) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val stored by app.store.downloadConcurrencyFlow().collectAsState(initial = 3)
    var value by remember { mutableStateOf(stored.toFloat()) }

    LaunchedEffect(stored) {
        value = stored.toFloat()
    }

    Column(Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(GlassShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Download,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    tr("Simultaneous downloads"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    tr("How many videos may save at the same time"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                value.roundToInt().toString(),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(Modifier.height(4.dp))
        val commitConcurrency: () -> Unit = {
                val n = value.roundToInt().coerceIn(1, 10)
                value = n.toFloat()
                scope.launch {
                    app.store.setDownloadConcurrency(n)
                    DownloadsRepository.setMaxConcurrent(n)
                    if (DownloadsRepository.snapshot().any { it.status == DownloadStatus.QUEUED }) {
                        DownloadService.start(context)
                    }
                }
        }
        Slider(
            value = value,
            onValueChange = { value = it },
            onValueChangeFinished = commitConcurrency,
            valueRange = 1f..10f,
            steps = 8,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f),
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
            ),
            modifier = Modifier.fillMaxWidth().tvAdjust { delta ->
                value = (value.roundToInt() + delta).coerceIn(1, 10).toFloat()
                commitConcurrency()
            }
        )
        Text(
            tr("Extra videos wait in the queue."),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun CollectionsCard(onOpen: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as HikariApp
    val flow = remember { app.store.collectionsFlow() }
    val collections by flow.collectAsState(initial = emptyList())
    val folders = collections.sumOf { it.folders.size }
    val catalogs = collections.sumOf { c -> c.folders.sumOf { it.sources.size } }
    Box {
        ListItem(
            leadingContent = {
                Icon(
                    Icons.Filled.FolderOpen,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            headlineContent = { Text(tr("Collections")) },
            supportingContent = {
                Text(
                    if (collections.isEmpty()) {
                        tr("Group the catalogs you watch into folders")
                    } else {
                        collections.size.toString() + " " +
                            (if (collections.size == 1) tr("collection") else tr("collections")) +
                            " · " + catalogs + " " + (if (catalogs == 1) tr("catalog") else tr("catalogs"))
                    }
                )
            },
            trailingContent = {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            modifier = Modifier.clickable(onClick = onOpen)
        )
    }
}

@Composable
private fun UiScaleCard(app: HikariApp) {
    val scope = rememberCoroutineScope()
    val enabledFlow = remember { app.store.uiScaleEnabledFlow() }
    val enabled by enabledFlow.collectAsState(initial = false)
    val scaleFlow = remember { app.store.uiScaleFlow() }
    val scale by scaleFlow.collectAsState(initial = 1f)
    var slider by remember { mutableStateOf(scale) }

    LaunchedEffect(scale) { slider = scale }

    SettingsSection(
        // Layout, not decoration: this decides how much ROOM the interface takes,
        // which is why the card sits in App Layout (see SettingsFolder).
        id = "layout.ui-scale",
        icon = Icons.Filled.FormatSize,
        title = tr("In-app UI scale"),
        summary = if (enabled) (scale * 100).roundToInt().toString() + "%"
        else tr("Off — your phone's text size is used"),
    ) {
        SettingsToggle(
            label = tr("Force one interface size"),
            supporting = tr("Ignores your phone's text size"),
            checked = enabled,
            onCheckedChange = { on ->
                scope.launch { runCatching { app.store.setUiScaleEnabled(on) } }
            },
        )
        if (enabled) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    tr("UI scale size"),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    (slider * 100).roundToInt().toString() + "%",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            val commitScale: () -> Unit = {
                    val pct = (slider * 100).roundToInt().coerceIn(70, 130)
                    slider = pct / 100f
                    scope.launch { runCatching { app.store.setUiScale(pct) } }
            }
            Slider(
                value = slider,
                onValueChange = { v ->
                    slider = (v * 100f).roundToInt().coerceIn(70, 130) / 100f
                },
                onValueChangeFinished = commitScale,
                valueRange = 0.7f..1.3f,
                // 59 gaps between 70% and 130% = a step of exactly 1%.
                steps = 59,
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f),
                    activeTickColor = Color.Transparent,
                    inactiveTickColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth().tvAdjust { delta ->
                slider = ((slider * 100f).roundToInt() + delta).coerceIn(70, 130) / 100f
                commitScale()
            }
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    tr("Smaller"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    tr("Default"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    tr("Bigger"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun TaskbarCard(app: HikariApp) {
    val scope = rememberCoroutineScope()
    val hiddenFlow = remember { app.store.hiddenTabsFlow() }
    val hidden by hiddenFlow.collectAsState(initial = emptySet())
    // The IPTV button is the one tab that is OFF until it is switched on here
    // (see AppStore.iptvTabFlow) — every other tab is on unless switched off.
    val iptvFlow = remember { app.store.iptvTabFlow() }
    val iptvTab by iptvFlow.collectAsState(initial = false)
    val mangaFlow = remember { app.store.mangaTabFlow() }
    val mangaTab by mangaFlow.collectAsState(initial = true)
    // Stats is the third off-by-default button (see AppStore.statsTabFlow).
    val statsFlow = remember { app.store.statsTabFlow() }
    val statsTab by statsFlow.collectAsState(initial = false)
    val telegramFlow = remember { app.store.telegramTabFlow() }
    val telegramTab by telegramFlow.collectAsState(initial = true)
    fun shown(tab: BottomTab): Boolean = when (tab.route) {
        Routes.IPTV -> iptvTab
        Routes.MANGA -> mangaTab
        Routes.STATS -> statsTab
        Routes.TELEGRAM -> telegramTab
        else -> tab.route !in hidden
    }
    val extras = setOf(Routes.IPTV, Routes.MANGA, Routes.STATS, Routes.TELEGRAM)
    val coreVisible = BottomTabs.filter { it.route !in extras && it.route !in hidden }
    val visibleCount = coreVisible.size + (if (iptvTab) 1 else 0) + (if (mangaTab) 1 else 0) +
        (if (statsTab) 1 else 0) + (if (telegramTab) 1 else 0)
    val labelsFlow = remember { app.store.tabLabelsFlow() }
    val labels by labelsFlow.collectAsState(initial = true)

    SettingsSection(
        id = "nav.taskbar",
        icon = Icons.Filled.Tune,
        title = tr("Taskbar buttons"),
        summary = visibleCount.toString() + " / " + BottomTabs.size + " " + tr("buttons") + " · " +
            (if (labels) tr("labels on") else tr("labels off")),
    ) {
        SettingsToggle(
            label = tr("Show text on taskbar buttons"),
            supporting = tr("Write each tab's name under its icon"),
            checked = labels,
            onCheckedChange = { on ->
                scope.launch { runCatching { app.store.setTabLabels(on) } }
            },
        )
        Spacer(Modifier.height(4.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
        BottomTabs.forEach { tab ->
            val isOn = shown(tab)
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    tab.icon,
                    contentDescription = null,
                    tint = if (isOn) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        tr(tab.label),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (tab.route == Routes.IPTV) {
                        Text(
                            tr("Off by default — switch on to browse your playlists"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (tab.route == Routes.MANGA) {
                        Text(
                            tr("On by default — switch off to hide the Manga tab"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (tab.route == Routes.STATS) {
                        Text(
                            tr("Off by default — switch on to see your watch time"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (tab.route == Routes.TELEGRAM) {
                        Text(
                            tr("Off by default — switch on to watch your Telegram channels"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (tab.route == Routes.LIBRARY) {
                        Text(
                            tr("Its sections can be switched off individually below"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                // Hoisted so the D-pad modifier can run the same lambda (see
                // [Modifier.tvToggle]) without repeating its body.
                val tabOn: (Boolean) -> Unit = { on ->
                    scope.launch {
                        when (tab.route) {
                            Routes.IPTV -> app.store.setIptvTab(on)
                            Routes.MANGA -> app.store.setMangaTab(on)
                            Routes.STATS -> app.store.setStatsTab(on)
                            Routes.TELEGRAM -> app.store.setTelegramTab(on)
                            else -> app.store.setTabHidden(tab.route, !on)
                        }
                    }
                }
                val tabEnabled = if (tab.route in extras) true else !isOn || coreVisible.size > 1
                Switch(
                    checked = isOn,
                    // A hidden tab can always be brought back; a shown one only
                    // while at least one other tab is still on (IPTV and Manga
                    // excepted — they are not among the app's core pages).
                    enabled = tabEnabled,
                    onCheckedChange = tabOn,
                    modifier = Modifier.tvToggle(isOn, tabEnabled, tabOn),
                )
            }
            if (tab.route == Routes.LIBRARY && isOn) {
                MyStuffSectionToggles(app)
            }
        }
    }
}

/** The three switches for the sections of the My Stuff page. */
@Composable
private fun MyStuffSectionToggles(app: HikariApp) {
    val scope = rememberCoroutineScope()
    val libraryFlow = remember { app.store.myStuffSectionFlow(com.hikari.app.data.MyStuffSection.LIBRARY) }
    val historyFlow = remember { app.store.myStuffSectionFlow(com.hikari.app.data.MyStuffSection.HISTORY) }
    val downloadsFlow = remember { app.store.myStuffSectionFlow(com.hikari.app.data.MyStuffSection.DOWNLOADS) }
    val libraryOn by libraryFlow.collectAsState(initial = true)
    val historyOn by historyFlow.collectAsState(initial = true)
    val downloadsOn by downloadsFlow.collectAsState(initial = true)
    val onCount = listOf(libraryOn, historyOn, downloadsOn).count { it }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 18.dp)
    ) {
        // A hairline, and an indent: the three rows configure the button ABOVE
        // them, not the taskbar in general.
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
            modifier = Modifier.padding(bottom = 2.dp),
        )
        MyStuffSectionRow(
            label = tr("Library"),
            supporting = tr("Titles you saved with the player's heart"),
            checked = libraryOn,
            enabled = !libraryOn || onCount > 1,
            onChange = { on -> scope.launch { runCatching { app.store.setMyStuffSection(com.hikari.app.data.MyStuffSection.LIBRARY, on) } } },
        )
        MyStuffSectionRow(
            label = tr("History"),
            supporting = tr("What you watched and where you stopped reading"),
            checked = historyOn,
            enabled = !historyOn || onCount > 1,
            onChange = { on -> scope.launch { runCatching { app.store.setMyStuffSection(com.hikari.app.data.MyStuffSection.HISTORY, on) } } },
        )
        MyStuffSectionRow(
            label = tr("Downloads"),
            supporting = tr("What you saved for offline"),
            checked = downloadsOn,
            enabled = !downloadsOn || onCount > 1,
            onChange = { on -> scope.launch { runCatching { app.store.setMyStuffSection(com.hikari.app.data.MyStuffSection.DOWNLOADS, on) } } },
        )
        if (onCount == 1) {
            if (!LocalHideHelp.current) {
            Text(
                tr("One section always stays — the page would otherwise be empty."),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 4.dp),
            )
            }
        }
    }
}

@Composable
private fun MyStuffSectionRow(
    label: String,
    supporting: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled || checked) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (helpShown()) {
                Text(
                    supporting,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            // See [Modifier.tvToggle]: a remote must be able to land on and
            // flip this, and the focus search skips it otherwise.
            modifier = Modifier.tvToggle(checked, enabled, onChange),
        )
    }
}

@Composable
private fun FontCard(app: HikariApp) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyFlow = remember { app.store.appFontFlow() }
    val key by keyFlow.collectAsState(initial = AppFonts.DEFAULT)
    val fileFlow = remember { app.store.appFontFileFlow() }
    val file by fileFlow.collectAsState(initial = "")
    val labelFlow = remember { app.store.appFontLabelFlow() }
    val importedLabel by labelFlow.collectAsState(initial = "")
    var pickerOpen by remember { mutableStateOf(false) }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val picked = withContext(Dispatchers.IO) { AppFonts.import(context, uri) }
            if (picked == null) {
                Toast.makeText(
                    context,
                    I18n.t("That file isn't a font Hikari can read."),
                    Toast.LENGTH_SHORT,
                ).show()
            } else {
                app.store.setImportedFont(picked.first, picked.second)
                app.store.setAppFont(AppFonts.IMPORTED)
                Toast.makeText(context, I18n.t("Font imported"), Toast.LENGTH_SHORT).show()
            }
        }
    }

    SettingsSection(
        id = "appearance.font",
        icon = Icons.Filled.TextFields,
        title = tr("App font"),
        summary = AppFonts.labelFor(key, importedLabel),
    ) {
        ChoiceRow(
            value = AppFonts.labelFor(key, importedLabel),
            leadingIcon = Icons.Filled.TextFields,
            onClick = { pickerOpen = true },
        )
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = {
                runCatching {
                    importer.launch(
                        arrayOf("font/ttf", "font/otf", "application/octet-stream", "*/*")
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
            shape = GlassShape,
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(tr(if (file.isBlank()) "Import a font from storage" else "Replace the imported font"))
        }
        Spacer(Modifier.height(4.dp))
        Text(
            tr("Best with .ttf or .otf files"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (pickerOpen) {
        ChoiceDialog(
            title = tr("App font"),
            items = buildList {
                AppFonts.CHOICES.forEach { choice -> add(ChoiceItem(choice.key, choice.label)) }
                if (file.isNotBlank()) {
                    add(
                        ChoiceItem(
                            key = AppFonts.IMPORTED,
                            label = AppFonts.labelFor(AppFonts.IMPORTED, importedLabel),
                            supporting = tr("Imported from your storage"),
                        )
                    )
                }
            },
            selectedKey = key,
            onPick = { picked ->
                if (picked != key) scope.launch { runCatching { app.store.setAppFont(picked) } }
            },
            onDismiss = { pickerOpen = false },
        )
    }
}

@Composable
private fun PosterStyleCard(app: HikariApp) {
    val scope = rememberCoroutineScope()
    val blurFlow = remember { app.store.posterBlurFlow() }
    val blur by blurFlow.collectAsState(initial = 0)
    val cornerFlow = remember { app.store.posterCornerFlow() }
    val corner by cornerFlow.collectAsState(initial = 14)
    val titlesFlow = remember { app.store.posterShowTitlesFlow() }
    val titles by titlesFlow.collectAsState(initial = true)
    val ratingsFlow = remember { app.store.posterShowRatingsFlow() }
    val ratings by ratingsFlow.collectAsState(initial = false)
    val typeBadgeFlow = remember { app.store.posterShowTypeFlow() }
    val typeBadge by typeBadgeFlow.collectAsState(initial = true)
    val qualityBadgeFlow = remember { app.store.posterShowQualityFlow() }
    val qualityBadge by qualityBadgeFlow.collectAsState(initial = false)
    val epBadgesFlow = remember { app.store.posterShowEpBadgesFlow() }
    val epBadges by epBadgesFlow.collectAsState(initial = true)
    val glassFlow = remember { app.store.posterGlassFlow() }
    val glass by glassFlow.collectAsState(initial = true)
    val effectsFlow = remember { app.store.posterEffectsFlow() }
    val effects by effectsFlow.collectAsState(initial = emptySet())
    val auraFlow = remember { app.store.posterAuraColorFlow() }
    val auraColor by auraFlow.collectAsState(initial = AuraColors.THEME)
    var effectPicker by remember { mutableStateOf(false) }
    val posterTypeFlow = remember { app.store.posterTypeFlow() }
    val posterType by posterTypeFlow.collectAsState(initial = PosterTypes.AUTO)
    var typePicker by remember { mutableStateOf(false) }
    // The chosen treatments, translated PART BY PART (a combination read back as
    // one English sentence could not be translated).
    val effectNames = PosterEffects.ALL
        .filter { it != PosterEffects.NONE && it in effects }
        .map { tr(PosterEffects.label(it)) }

    var blurSlider by remember { mutableStateOf(blur.toFloat()) }
    var cornerSlider by remember { mutableStateOf(corner.toFloat()) }
    LaunchedEffect(blur) { blurSlider = blur.toFloat() }
    LaunchedEffect(corner) { cornerSlider = corner.toFloat() }

    SettingsSection(
        id = "layout.poster",
        icon = Icons.Filled.Wallpaper,
        title = tr("Poster & icon styling"),
        summary = tr("Blur") + " " + (if (blur < 1) tr("off") else blur.toString()) +
            " · " + tr("Corners") + " " + corner,
    ) {
        ChoiceRow(
            value = if (effectNames.isEmpty()) {
                tr(PosterEffects.label(PosterEffects.NONE))
            } else {
                effectNames.joinToString(" + ")
            },
            supporting = if (effectNames.isEmpty()) {
                tr(PosterEffects.description(PosterEffects.NONE))
            } else if (effectNames.size == 1) {
                tr(PosterEffects.description(effects.first()))
            } else {
                tr(PosterEffects.description(effects.first())) + " · +" +
                    (effectNames.size - 1).toString() + " " + tr("more")
            },
            leadingIcon = Icons.Filled.AutoAwesome,
            onClick = { effectPicker = true },
        )
        ChoiceRow(
            value = tr(PosterTypes.label(posterType)),
            supporting = tr(PosterTypes.description(posterType)),
            leadingIcon = Icons.Filled.AspectRatio,
            onClick = { typePicker = true },
        )
        if (PosterEffects.LIT in effects) {
            val pointFlow = remember { app.store.posterGlowPointFlow() }
            val point by pointFlow.collectAsState(initial = 0.5f to 0.14f)
            val strengthFlow = remember { app.store.posterGlowStrengthFlow() }
            val strength by strengthFlow.collectAsState(initial = 55)
            Spacer(Modifier.height(10.dp))
            EdgeLightControls(
                x = point.first,
                y = point.second,
                strength = strength,
                onChangePoint = { px, py ->
                    scope.launch { runCatching { app.store.setPosterGlowPoint(px, py) } }
                },
                onChangeStrength = { v ->
                    scope.launch { runCatching { app.store.setPosterGlowStrength(v) } }
                },
            )
        }
        // The aura ring's own colour, offered only while that ring is on: a
        // colour row for a ring that is not being drawn would be noise.
        if (PosterEffects.AURA in effects) {
            AuraColorRow(
                selected = auraColor,
                onPick = { key -> scope.launch { runCatching { app.store.setPosterAuraColor(key) } } },
            )
        }
        Spacer(Modifier.height(14.dp))
        SettingsSlider(
            label = tr("Dynamic blur"),
            value = blurSlider,
            valueText = if (blurSlider < 1f) tr("Off") else blurSlider.roundToInt().toString(),
            valueRange = 0f..24f,
            steps = 23,
            onValueChange = { blurSlider = it },
            onValueChangeFinished = {
                val v = blurSlider.roundToInt().coerceIn(0, 24)
                blurSlider = v.toFloat()
                scope.launch { runCatching { app.store.setPosterBlur(v) } }
            },
        )
        Text(
            tr("Soft coloured halo behind each poster"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(14.dp))
        SettingsSlider(
            label = tr("Corner rounding"),
            value = cornerSlider,
            valueText = cornerSlider.roundToInt().toString(),
            valueRange = 0f..28f,
            steps = 27,
            onValueChange = { cornerSlider = it },
            onValueChangeFinished = {
                val v = cornerSlider.roundToInt().coerceIn(0, 28)
                cornerSlider = v.toFloat()
                scope.launch { runCatching { app.store.setPosterCorner(v) } }
            },
        )
        Spacer(Modifier.height(4.dp))
        SettingsToggle(
            label = tr("Show titles"),
            checked = titles,
            onCheckedChange = { on -> scope.launch { runCatching { app.store.setPosterShowTitles(on) } } },
        )
        SettingsToggle(
            label = tr("Score badges on posters"),
            checked = ratings,
            onCheckedChange = { on -> scope.launch { runCatching { app.store.setPosterShowRatings(on) } } },
        )
        SettingsToggle(
            label = tr("Movie / series tag on posters"),
            supporting = tr("A small FILM or SERIES tag in the poster's top-left corner"),
            checked = typeBadge,
            onCheckedChange = { on -> scope.launch { runCatching { app.store.setPosterShowType(on) } } },
        )
        SettingsToggle(
            label = tr("Quality tag on posters"),
            supporting = tr(
                "The best quality Hikari knows for a title — from its name, or from the " +
                    "servers found when you last opened it. Titles it has never seen show nothing."
            ),
            checked = qualityBadge,
            onCheckedChange = { on -> scope.launch { runCatching { app.store.setPosterShowQuality(on) } } },
        )
        SettingsToggle(
            label = tr("Dub / Sub + episode badges on posters"),
            supporting = tr("Latest dub/sub episode pills, like CloudStream"),
            checked = epBadges,
            onCheckedChange = { on -> scope.launch { runCatching { app.store.setPosterShowEpBadges(on) } } },
        )
        SettingsToggle(
            label = tr("Glass trim"),
            supporting = tr("The frosted backing every card shares"),
            checked = glass,
            onCheckedChange = { on -> scope.launch { runCatching { app.store.setPosterGlass(on) } } },
        )
    }

    if (effectPicker) {
        MultiChoiceDialog(
            title = tr("Poster effects"),
            items = PosterEffects.ALL.map {
                ChoiceItem(it, tr(PosterEffects.label(it)), tr(PosterEffects.description(it)))
            },
            selectedKeys = effects,
            onToggle = { pick ->
                val next: Set<String> = if (pick == PosterEffects.NONE) {
                    emptySet()
                } else {
                    effects.toMutableSet().apply { if (!add(pick)) remove(pick) }
                }
                scope.launch { runCatching { app.store.setPosterEffects(next) } }
            },
            onDismiss = { effectPicker = false },
            footnote = "Tick as many as you like. They are drawn together on every poster, " +
                "and \"None\" clears them all.",
        )
    }

    if (typePicker) {
        ChoiceDialog(
            title = tr("Poster type"),
            items = PosterTypes.ALL.map {
                ChoiceItem(it, tr(PosterTypes.label(it)), tr(PosterTypes.description(it)))
            },
            selectedKey = posterType,
            onPick = { pick ->
                scope.launch { runCatching { app.store.setPosterType(pick) } }
            },
            onDismiss = { typePicker = false },
        )
    }
}

@Composable
private fun EdgeLightControls(
    x: Float,
    y: Float,
    strength: Int,
    onChangePoint: (Float, Float) -> Unit,
    onChangeStrength: (Int) -> Unit,
) {
    var strengthSlider by remember { mutableStateOf(strength.toFloat()) }
    LaunchedEffect(strength) { strengthSlider = strength.toFloat() }
    var dragging by remember { mutableStateOf(false) }
    var pointX by remember { mutableStateOf(x) }
    var pointY by remember { mutableStateOf(y) }
    LaunchedEffect(x, y) {
        if (!dragging) {
            pointX = x
            pointY = y
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        BoxWithConstraints(
            Modifier
                .width(78.dp)
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(10.dp))
                .background(
                    Brush.verticalGradient(listOf(Color(0xFF414B5E), Color(0xFF141922)))
                )
                .edgeLight(pointX, pointY, strengthSlider / 100f)
                .pointerInput(Unit) {
                    detectTapGestures { off ->
                        pointX = (off.x / size.width).coerceIn(0f, 1f)
                        pointY = (off.y / size.height).coerceIn(0f, 1f)
                        onChangePoint(pointX, pointY)
                    }
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { off ->
                            dragging = true
                            pointX = (off.x / size.width).coerceIn(0f, 1f)
                            pointY = (off.y / size.height).coerceIn(0f, 1f)
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            pointX = (change.position.x / size.width).coerceIn(0f, 1f)
                            pointY = (change.position.y / size.height).coerceIn(0f, 1f)
                        },
                        onDragEnd = {
                            dragging = false
                            onChangePoint(pointX, pointY)
                        },
                        onDragCancel = {
                            dragging = false
                            onChangePoint(pointX, pointY)
                        },
                    )
                }
        ) {
            Box(
                Modifier
                    .offset(
                        x = maxWidth * pointX - 5.dp,
                        y = maxHeight * pointY - 5.dp,
                    )
                    .size(10.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.92f))
            )
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            SettingsSlider(
                label = tr("Light strength"),
                value = strengthSlider,
                valueText = strengthSlider.roundToInt().toString(),
                valueRange = 0f..100f,
                steps = 99,
                onValueChange = { strengthSlider = it },
                onValueChangeFinished = {
                    val v = strengthSlider.roundToInt().coerceIn(0, 100)
                    strengthSlider = v.toFloat()
                    onChangeStrength(v)
                },
            )
            Text(
                tr("Drag the dot to place the light"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---- Television & remote (see com.hikari.app.tv.TvMode) ----
//
// This folder is offered on BOTH kinds of device. On a television it is where
// the layout, the safe area and the performance mode live; on a phone it is how
// the television layout can be seen (and checked) without a television, and how
// a box that reports itself wrongly can be corrected.

@Composable
private fun TvDeviceCard(app: HikariApp) {
    val modeFlow = remember { app.store.tvModeFlow() }
    val stored by modeFlow.collectAsState(initial = TvMode.AUTO)
    var menuOpen by remember { mutableStateOf(false) }
    val mode = TvMode.normalize(stored)
    val look = if (TvMode.deviceIsTelevision) {
        tr("this device reports itself as a TV")
    } else {
        tr("this device reports itself as a phone or tablet")
    }
    val currentLabel = when (mode) {
        TvMode.TV -> tr("Always the TV layout")
        TvMode.PHONE -> tr("Always the phone layout")
        else -> tr("Automatic") + " — " + look
    }
    Column {
        ListItem(
            leadingContent = {
                Icon(
                    Icons.Filled.Tv,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            },
            headlineContent = { Text(tr("Layout")) },
            supportingContent = { Text(currentLabel) },
            trailingContent = {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            modifier = Modifier.clickable { menuOpen = true },
        )
        if (menuOpen) {
            ChoiceDialog(
                title = tr("Layout"),
                items = listOf(
                    ChoiceItem(TvMode.AUTO, tr("Automatic")),
                    ChoiceItem(TvMode.TV, tr("TV layout")),
                    ChoiceItem(TvMode.PHONE, tr("Phone layout")),
                ),
                selectedKey = mode,
                onPick = { pick ->
                    menuOpen = false
                    app.appScope.launch {
                        val ok = runCatching { app.store.setTvMode(pick) }.isSuccess
                        if (!ok) {
                            com.hikari.app.data.Logs.log(
                                "App",
                                "layout pick '" + pick + "' failed to save",
                            )
                        }
                    }
                },
                onDismiss = { menuOpen = false },
            )
        }
        if (!LocalHideHelp.current) {
        Text(
            tr(
                "One APK, two layouts. The TV layout puts the tabs in a rail down " +
                    "the left, draws everything bigger and pads it away from the " +
                    "screen edges, and follows the remote's arrow keys. Switching " +
                    "this takes effect immediately — no restart."
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 14.dp),
        )
        }
    }
}

@Composable
private fun TvOverscanCard(app: HikariApp) {
    val scope = rememberCoroutineScope()
    val flow = remember { app.store.tvOverscanFlow() }
    val stored by flow.collectAsState(initial = TvUi.DEFAULT_OVERSCAN_DP)
    var slider by remember { mutableStateOf(stored.toFloat()) }
    LaunchedEffect(stored) { slider = stored.toFloat() }

    SettingsSection(
        id = "tv.overscan",
        icon = Icons.Filled.AspectRatio,
        title = tr("Screen edges"),
        summary = slider.roundToInt().toString() + " dp",
    ) {
        SettingsSlider(
            label = tr("Keep controls away from the edge"),
            value = slider,
            valueText = slider.roundToInt().toString() + " dp",
            valueRange = 0f..TvUi.MAX_OVERSCAN_DP.toFloat(),
            // 4dp steps: 16 of them across 0..64.
            steps = TvUi.MAX_OVERSCAN_DP / 4 - 1,
            onValueChange = { v -> slider = (v / 4f).roundToInt().toFloat() * 4f },
            onValueChangeFinished = {
                scope.launch { runCatching { app.store.setTvOverscan(slider.roundToInt()) } }
            },
        )
        if (!LocalHideHelp.current) {
        Text(
            tr(
                "Televisions crop a few percent off the picture, so content drawn " +
                    "at the very edge can be invisible. Raise this until nothing " +
                    "is cut off; lower it if your TV shows the whole frame."
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        }
    }
}

@Composable
private fun TvPosterSizeCard(app: HikariApp) {
    val scope = rememberCoroutineScope()
    val flow = remember { app.store.tvPosterScaleFlow() }
    val stored by flow.collectAsState(initial = 100)
    var slider by remember { mutableStateOf(stored.toFloat()) }
    LaunchedEffect(stored) { slider = stored.toFloat() }

    SettingsSection(
        id = "tv.posterSize",
        icon = Icons.Filled.Tune,
        title = tr("Poster size"),
        summary = slider.roundToInt().toString() + "%",
    ) {
        SettingsSlider(
            label = tr("How big posters are drawn"),
            value = slider,
            valueText = slider.roundToInt().toString() + "%",
            valueRange = 70f..150f,
            steps = 80 - 1,
            onValueChange = { v -> slider = v.roundToInt().toFloat() },
            onValueChangeFinished = {
                scope.launch { runCatching { app.store.setTvPosterScale(slider.roundToInt()) } }
            },
        )
    }
}

@Composable
private fun TvTextSizeCard(app: HikariApp) {
    val scope = rememberCoroutineScope()
    val flow = remember { app.store.tvTextScaleFlow() }
    val stored by flow.collectAsState(initial = 100)
    var slider by remember { mutableStateOf(stored.toFloat()) }
    LaunchedEffect(stored) { slider = stored.toFloat() }

    SettingsSection(
        id = "tv.textSize",
        icon = Icons.Filled.FormatSize,
        title = tr("Text size"),
        summary = slider.roundToInt().toString() + "%",
    ) {
        SettingsSlider(
            label = tr("How big text is drawn on TV"),
            value = slider,
            valueText = slider.roundToInt().toString() + "%",
            valueRange = 50f..150f,
            steps = 100,
            onValueChange = { v -> slider = v.roundToInt().toFloat() },
            onValueChangeFinished = {
                scope.launch { runCatching { app.store.setTvTextScale(slider.roundToInt()) } }
            },
        )
    }
}

@Composable
private fun TvDetailStyleCard(app: HikariApp) {
    val scope = rememberCoroutineScope()
    val flow = remember { app.store.tvDetailBigFlow() }
    val on by flow.collectAsState(initial = com.hikari.app.tv.TvMode.current())
    val blurFlow = remember { app.store.tvBlurDetailFlow() }
    val blurOn by blurFlow.collectAsState(initial = false)

    SettingsSection(
        id = "tv.detailStyle",
        icon = Icons.Filled.SmartDisplay,
        title = tr("TV details screen"),
        summary = if (on) tr("On — big title art") else tr("Off — standard page"),
    ) {
        SettingsToggle(
            label = tr("Big title art on TV"),
            supporting = tr("Draw the detail image large above the content"),
            checked = on,
            onCheckedChange = { value ->
                scope.launch {
                    runCatching {
                        app.store.setTvDetailBig(value)
                        app.store.setTvDetailBigChosen(true)
                    }
                }
            },
        )
        SettingsToggle(
            label = tr("Full bleed and blur detail"),
            supporting = tr("Header art stays sharp like the reference client; everything scrolled to sits over its blurred background"),
            checked = blurOn,
            onCheckedChange = { value ->
                scope.launch { runCatching { app.store.setTvBlurDetail(value) } }
            },
        )
    }
}

@Composable
private fun TvPerformanceCard(app: HikariApp) {
    val scope = rememberCoroutineScope()
    val flow = remember { app.store.tvPerfFlow() }
    val on by flow.collectAsState(initial = false)

    SettingsSection(
        id = "tv.perf",
        icon = Icons.Filled.Speed,
        title = tr("Performance mode"),
        summary = if (on) tr("On — plain posters, no effects")
        else tr("Off — the poster effects are drawn"),
    ) {
        SettingsToggle(
            label = tr("Lighter visuals"),
            supporting = tr("Skips the poster treatments and their blur"),
            checked = on,
            onCheckedChange = { value ->
                scope.launch {
                    runCatching {
                        app.store.setTvPerf(value)
                        app.store.setTvPerfChosen(true)
                    }
                }
            },
        )
        if (!LocalHideHelp.current) {
        Text(
            tr(
                "Television boxes are much weaker than phones, and smoother " +
                    "scrolling matters more on a big screen than a fancy poster. " +
                    "Turn this off if your box handles the effects without trouble."
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        }
        Text(
            tr(
                "It is switched on for you whenever the TV layout is active, and " +
                    "off for the phone layout. Changing the switch yourself makes " +
                    "it your choice from then on."
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun TvRemoteCard() {
    SettingsSection(
        id = "tv.remote",
        icon = Icons.Filled.SettingsRemote,
        title = tr("Remote controls"),
        summary = tr("What each button does"),
    ) {
        TvKeyRow(tr("Up / Down / OK"), tr("Bring the player's controls up"))
        TvKeyRow(tr("Left / Right"), tr("Seek 10 seconds (hold to scrub). With the controls up, they move between its buttons"))
        TvKeyRow(tr("Rewind / Fast-forward"), tr("Seek 30 seconds"))
        TvKeyRow(tr("Play / Pause"), tr("Pause and resume"))
        TvKeyRow(tr("Back"), tr("Close the controls — press it again to leave the player"))
        TvKeyRow(tr("In the app"), tr("Left/Right walks a row, Up/Down moves between rows, and the rail on the left changes tab"))
    }
}

/** One line of [TvRemoteCard]: the button, then what it does. */
@Composable
private fun TvKeyRow(keys: String, what: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            keys,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(150.dp),
        )
        Text(
            what,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * A labelled slider with its value on the right — used by [PosterStyleCard]. */
@Composable
private fun SettingsSlider(
    label: String,
    value: Float,
    valueText: String,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            valueText,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    Slider(
        value = value,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        valueRange = valueRange,
        steps = steps,
        colors = SliderDefaults.colors(
            thumbColor = MaterialTheme.colorScheme.primary,
            activeTrackColor = MaterialTheme.colorScheme.primary,
            inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f),
            activeTickColor = Color.Transparent,
            inactiveTickColor = Color.Transparent,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .tvAdjust { delta ->
                val span = valueRange.endInclusive - valueRange.start
                val step = if (steps > 0) span / (steps + 1) else span / 20f
                onValueChange((value + step * delta).coerceIn(valueRange.start, valueRange.endInclusive))
                onValueChangeFinished()
            },
    )
}

/** A switch row with a caption — the shape every toggle in this folder uses. */
@Composable
private fun SettingsToggle(
    label: String,
    supporting: String = "",
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (supporting.isNotBlank() && helpShown()) {
                Text(
                    supporting,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.tvToggle(checked, onValueChange = onCheckedChange),
        )
    }
}

/** A card's leading row: the brand tile plus the card's own heading. */
@Composable
private fun SettingsCardHeading(icon: ImageVector, title: String, brandKey: String = title) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        SettingsThemeTile(icon, brandKey, size = 30.dp)
        Spacer(Modifier.width(12.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun DetailRatingCard(app: HikariApp) {
    val scope = rememberCoroutineScope()
    val flow = remember { app.store.showDetailRatingFlow() }
    val show by flow.collectAsState(initial = true)

    Column(Modifier.padding(16.dp)) {
        SettingsCardHeading(Icons.Filled.Star, tr("Ratings"))
        SettingsToggle(
            label = tr("Show scores"),
            supporting = tr("IMDb & Rotten Tomatoes strips"),
            checked = show,
            onCheckedChange = { on ->
                scope.launch { runCatching { app.store.setShowDetailRating(on) } }
            },
        )
    }
}

@Composable
private fun HeroBannerCard(app: HikariApp) {
    val scope = rememberCoroutineScope()
    val styleFlow = remember { app.store.heroStyleFlow() }
    val style by styleFlow.collectAsState(initial = HeroStyles.CAROUSEL)
    val overviewFlow = remember { app.store.heroOverviewFlow() }
    val overview by overviewFlow.collectAsState(initial = true)
    val ratingFlow = remember { app.store.heroRatingFlow() }
    val rating by ratingFlow.collectAsState(initial = true)
    val metaFlow = remember { app.store.heroMetaFlow() }
    val meta by metaFlow.collectAsState(initial = true)
    var pickerOpen by remember { mutableStateOf(false) }
    val scaleFlow = remember { app.store.heroScaleFlow() }
    val scale by scaleFlow.collectAsState(initial = 100)
    var scaleSlider by remember { mutableStateOf(scale.toFloat()) }
    LaunchedEffect(scale) { scaleSlider = scale.toFloat() }

    Column(Modifier.padding(16.dp)) {
        SettingsCardHeading(Icons.Filled.ViewCarousel, tr("Featured banner"))
        ChoiceRow(
            value = tr(HeroStyles.label(style)),
            supporting = tr(HeroStyles.description(style)),
            leadingIcon = Icons.Filled.ViewCarousel,
            onClick = { pickerOpen = true },
        )
        Spacer(Modifier.height(14.dp))
        SettingsSlider(
            label = tr("Header size"),
            value = scaleSlider,
            valueText = scaleSlider.roundToInt().toString() + "%",
            valueRange = 50f..150f,
            steps = 99,
            onValueChange = { v -> scaleSlider = v.roundToInt().toFloat().coerceIn(50f, 150f) },
            onValueChangeFinished = {
                val pct = scaleSlider.roundToInt().coerceIn(50, 150)
                scaleSlider = pct.toFloat()
                scope.launch { runCatching { app.store.setHeroScale(pct) } }
            },
        )
        if (!LocalHideHelp.current) {
        Text(
            tr("How big the Home header image is drawn — slide left to shrink it, right to grow it"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        }
        // Height only: the card keeps its width and gets shorter or taller (the
        // slider above scales width and height together). Stored by HeaderPrefs.
        Spacer(Modifier.height(14.dp))
        var headerHeight by remember {
            mutableStateOf(com.hikari.app.ui.HeaderPrefs.heightPercent.toFloat())
        }
        SettingsSlider(
            label = tr("Header height"),
            value = headerHeight,
            valueText = headerHeight.roundToInt().toString() + "%",
            valueRange = 50f..150f,
            steps = 99,
            onValueChange = { v -> headerHeight = v.roundToInt().toFloat().coerceIn(50f, 150f) },
            onValueChangeFinished = {
                val pct = headerHeight.roundToInt().coerceIn(50, 150)
                headerHeight = pct.toFloat()
                com.hikari.app.ui.HeaderPrefs.setHeightPercent(pct)
            },
        )
        if (!LocalHideHelp.current) {
            Text(
                tr("Makes only the height of the Home header image smaller or bigger — the width stays the same, on phone and on TV"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(8.dp))
        SettingsToggle(
            label = tr("Plot line"),
            supporting = tr("A two-line summary under the title"),
            checked = overview,
            onCheckedChange = { on -> scope.launch { runCatching { app.store.setHeroOverview(on) } } },
        )
        SettingsToggle(
            label = tr("Score badge"),
            supporting = tr("The rating chip on the artwork"),
            checked = rating,
            onCheckedChange = { on -> scope.launch { runCatching { app.store.setHeroRating(on) } } },
        )
        SettingsToggle(
            label = tr("Metadata"),
            supporting = tr("Year, runtime, seasons and genres"),
            checked = meta,
            onCheckedChange = { on -> scope.launch { runCatching { app.store.setHeroMeta(on) } } },
        )
    }

    if (pickerOpen) {
        ChoiceDialog(
            title = tr("Featured banner"),
            items = HeroStyles.ALL.map {
                ChoiceItem(it, tr(HeroStyles.label(it)), tr(HeroStyles.description(it)))
            },
            selectedKey = style,
            onPick = { pick ->
                pickerOpen = false
                scope.launch { runCatching { app.store.setHeroStyle(pick) } }
            },
            onDismiss = { pickerOpen = false },
        )
    }
}

@Composable
private fun DetailHeaderCard(app: HikariApp) {
    val scope = rememberCoroutineScope()
    val flow = remember { app.store.detailHeroStyleFlow() }
    val style by flow.collectAsState(initial = DetailHeroStyles.WIDE)
    var pickerOpen by remember { mutableStateOf(false) }
    val logoFlow = remember { app.store.detailLogoSizeFlow() }
    val logoSize by logoFlow.collectAsState(initial = 100)
    var logoSlider by remember { mutableStateOf(logoSize.toFloat()) }
    LaunchedEffect(logoSize) { logoSlider = logoSize.toFloat() }

    Column(Modifier.padding(16.dp)) {
        SettingsCardHeading(Icons.Filled.Panorama, tr("Details header"))
        ChoiceRow(
            value = tr(DetailHeroStyles.label(style)),
            supporting = tr(DetailHeroStyles.description(style)),
            leadingIcon = Icons.Filled.Panorama,
            onClick = { pickerOpen = true },
        )
        Spacer(Modifier.height(14.dp))
        SettingsSlider(
            label = tr("Title logo size"),
            value = logoSlider,
            valueText = logoSlider.roundToInt().toString() + "%",
            valueRange = 50f..160f,
            // 111 single-percent positions between 50% and 160% (110 gaps): the
            // slider moves one point at a time instead of leaping in tens.
            steps = 109,
            onValueChange = { v -> logoSlider = v.roundToInt().toFloat().coerceIn(50f, 160f) },
            onValueChangeFinished = {
                val pct = logoSlider.roundToInt().coerceIn(50, 160)
                logoSlider = pct.toFloat()
                scope.launch { runCatching { app.store.setDetailLogoSize(pct) } }
            },
        )
        if (!LocalHideHelp.current) {
        Text(
            tr("How big the title artwork is drawn — over the header image, and as the pinned title once it scrolls up"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        }
    }

    if (pickerOpen) {
        ChoiceDialog(
            title = tr("Details header"),
            items = DetailHeroStyles.ALL.map {
                ChoiceItem(it, tr(DetailHeroStyles.label(it)), tr(DetailHeroStyles.description(it)))
            },
            selectedKey = style,
            onPick = { pick ->
                pickerOpen = false
                scope.launch { runCatching { app.store.setDetailHeroStyle(pick) } }
            },
            onDismiss = { pickerOpen = false },
        )
    }
}

@Composable
private fun PlayerUiCard(app: HikariApp) {
    val scope = rememberCoroutineScope()
    val flow = remember { app.store.playerSkinFlow() }
    val skin by flow.collectAsState(initial = PlayerSkins.FALLBACK)
    var pickerOpen by remember { mutableStateOf(false) }

    Column(Modifier.padding(16.dp)) {
        SettingsCardHeading(Icons.Filled.SmartDisplay, tr("Player UI"))
        ChoiceRow(
            value = tr(PlayerSkins.label(skin)),
            supporting = tr(PlayerSkins.description(skin)),
            leadingIcon = Icons.Filled.SmartDisplay,
            onClick = { pickerOpen = true },
        )
        Spacer(Modifier.height(6.dp))
        Text(
            tr("Applies to the next video you open."),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (pickerOpen) {
        ChoiceDialog(
            title = tr("Player UI"),
            items = PlayerSkins.ALL.map {
                ChoiceItem(it, tr(PlayerSkins.label(it)), tr(PlayerSkins.description(it)))
            },
            selectedKey = skin,
            onPick = { pick ->
                pickerOpen = false
                scope.launch { runCatching { app.store.setPlayerSkin(pick) } }
            },
            onDismiss = { pickerOpen = false },
        )
    }
}

/**
 * Default video quality and audio language. A video opens on these by itself, so nothing has to be
 * picked from the player's Quality / Audio sheets each time. When the quality (or the audio) is
 * not offered, the player moves to the next closest one.
 */
@Composable
private fun DefaultPlaybackCard(app: HikariApp) {
    val scope = rememberCoroutineScope()
    val qualityFlow = remember { app.store.playerDefaultQualityFlow() }
    val quality by qualityFlow.collectAsState(initial = 0)
    val audioFlow = remember { app.store.playerDefaultAudioFlow() }
    val audio by audioFlow.collectAsState(initial = "")
    val audio2Flow = remember { app.store.playerDefaultAudio2Flow() }
    val audio2 by audio2Flow.collectAsState(initial = "")
    // 0 = no picker open, 1 = quality, 2 = audio, 3 = audio if not found
    var picker by remember { mutableStateOf(0) }

    Column(Modifier.padding(16.dp)) {
        SettingsCardHeading(Icons.Filled.Tune, tr("Default quality & audio"))
        ChoiceRow(
            value = tr("Quality") + ": " + tr(DefaultPlayback.qualityLabel(quality)),
            supporting = tr("Every video starts on this quality. If it is not offered, the next closest one is used."),
            leadingIcon = Icons.Filled.Tune,
            onClick = { picker = 1 },
        )
        Spacer(Modifier.height(8.dp))
        ChoiceRow(
            value = tr("Audio") + ": " + tr(DefaultPlayback.audioLabel(audio)),
            supporting = tr("Every video starts with this voice. If the stream does not have it, a server that carries it is used."),
            leadingIcon = Icons.Filled.Language,
            onClick = { picker = 2 },
        )
        Spacer(Modifier.height(8.dp))
        ChoiceRow(
            value = tr("If not found") + ": " + tr(DefaultPlayback.audioLabel(audio2)),
            supporting = tr("The voice to try next when the audio above is not available anywhere."),
            leadingIcon = Icons.Filled.Translate,
            onClick = { picker = 3 },
        )
    }
    if (picker == 1) {
        ChoiceDialog(
            title = tr("Default quality"),
            items = DefaultPlayback.QUALITIES.map {
                ChoiceItem(
                    it.toString(),
                    tr(DefaultPlayback.qualityLabel(it)),
                    if (it == 0) tr("The player picks by itself") else null,
                )
            },
            selectedKey = quality.toString(),
            onPick = { pick ->
                picker = 0
                pick.toIntOrNull()?.let { chosen ->
                    scope.launch { runCatching { app.store.setPlayerDefaultQuality(chosen) } }
                }
            },
            onDismiss = { picker = 0 },
        )
    }
    if (picker == 2 || picker == 3) {
        val first = picker == 2
        ChoiceDialog(
            title = if (first) tr("Default audio") else tr("If not found"),
            items = listOf(ChoiceItem("", tr("Stream default"), tr("Use the voice the stream marks as default"))) +
                DefaultPlayback.AUDIO_LANGS.map { ChoiceItem(it.first, tr(it.second)) },
            selectedKey = if (first) audio else audio2,
            onPick = { pick ->
                picker = 0
                scope.launch {
                    runCatching {
                        if (first) app.store.setPlayerDefaultAudio(pick)
                        else app.store.setPlayerDefaultAudio2(pick)
                    }
                }
            },
            onDismiss = { picker = 0 },
        )
    }
}

/**
 * Speed used by the player's press-and-hold fast-play gesture.
 */
@Composable
private fun HoldToFastForwardCard(app: HikariApp) {
    val scope = rememberCoroutineScope()
    val flow = remember { app.store.playerHoldSpeedFlow() }
    val speed by flow.collectAsState(initial = 2f)
    var pickerOpen by remember { mutableStateOf(false) }
    val values = remember { (200..400 step 25).map { it / 100f } }
    val badgeFlow = remember { app.store.playerShowHoldSpeedFlow() }
    val badgeOn by badgeFlow.collectAsState(initial = true)

    Column(Modifier.padding(16.dp)) {
        SettingsCardHeading(Icons.Filled.Speed, tr("Press-and-hold speed"))
        ChoiceRow(
            value = speed.toString() + "x",
            supporting = tr("Hold your finger on the video for 1 second to play faster. Choose 2x through 4x."),
            leadingIcon = Icons.Filled.Speed,
            onClick = { pickerOpen = true },
        )
        Spacer(Modifier.height(4.dp))
        SettingsToggle(
            label = tr("Show speed on player"),
            supporting = tr("While your finger is down, show a small badge (like 2x) at the side of the video. It hides when you lift."),
            checked = badgeOn,
            onCheckedChange = { value ->
                scope.launch { runCatching { app.store.setPlayerShowHoldSpeed(value) } }
            },
        )
    }
    if (pickerOpen) {
        ChoiceDialog(
            title = tr("Press-and-hold speed"),
            items = values.map {
                ChoiceItem(it.toString(), it.toString() + "x", tr("Used while you keep your finger down"))
            },
            selectedKey = speed.toString(),
            onPick = { pick ->
                pickerOpen = false
                pick.toFloatOrNull()?.let { chosen ->
                    scope.launch { runCatching { app.store.setPlayerHoldSpeed(chosen) } }
                }
            },
            onDismiss = { pickerOpen = false },
        )
    }
}

/**
 * Whether player pills keep their text labels or show only their icons.
 */
@Composable
private fun PlayerIconLabelsCard(app: HikariApp) {
    val scope = rememberCoroutineScope()
    val flow = remember { app.store.playerIconOnlyFlow() }
    val on by flow.collectAsState(initial = false)
    val sizeFlow = remember { app.store.playerIconSizeFlow() }
    val iconSize by sizeFlow.collectAsState(initial = 24)
    var sizeDraft by remember { mutableStateOf(24f) }
    val topSizeFlow = remember { app.store.playerTopIconSizeFlow() }
    val topIconSize by topSizeFlow.collectAsState(initial = 24)
    var topSizeDraft by remember { mutableStateOf(24f) }

    LaunchedEffect(iconSize) {
        sizeDraft = iconSize.toFloat()
    }
    LaunchedEffect(topIconSize) {
        topSizeDraft = topIconSize.toFloat()
    }

    Column(Modifier.padding(16.dp)) {
        SettingsCardHeading(Icons.Filled.Tune, tr("Player button labels"))
        SettingsToggle(
            label = tr("Show icons only"),
            supporting = tr("Hide names such as Source, Audio and Subtitles while keeping their icons."),
            checked = on,
            onCheckedChange = { value ->
                scope.launch { runCatching { app.store.setPlayerIconOnly(value) } }
            },
        )
        if (on) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    tr("Bottom icon size"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "${sizeDraft.roundToInt()} dp",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Slider(
                value = sizeDraft,
                onValueChange = { sizeDraft = it },
                onValueChangeFinished = {
                    scope.launch { runCatching { app.store.setPlayerIconSize(sizeDraft.roundToInt()) } }
                },
                valueRange = 20f..44f,
                steps = 5,
                modifier = Modifier.fillMaxWidth().tvAdjust { delta ->
                    sizeDraft = (sizeDraft.roundToInt() + delta * 4).coerceIn(20, 44).toFloat()
                    scope.launch { runCatching { app.store.setPlayerIconSize(sizeDraft.roundToInt()) } }
                },
            )
            Text(
                tr("Increase this when icon-only controls look too small."),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                tr("Top icon size"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "${topSizeDraft.roundToInt()} dp",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = topSizeDraft,
            onValueChange = { topSizeDraft = it },
            onValueChangeFinished = {
                scope.launch { runCatching { app.store.setPlayerTopIconSize(topSizeDraft.roundToInt()) } }
            },
            valueRange = 20f..44f,
            steps = 5,
            modifier = Modifier.fillMaxWidth().tvAdjust { delta ->
                topSizeDraft = (topSizeDraft.roundToInt() + delta * 4).coerceIn(20, 44).toFloat()
                scope.launch { runCatching { app.store.setPlayerTopIconSize(topSizeDraft.roundToInt()) } }
            },
        )
        Text(
            tr("Back, favourite, download, PiP, options and lock above the video."),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Whether the player's options menu (the top-bar gear) offers "Play in
 * external player" — the row that hands the current stream to VLC & co.
 */
@Composable
private fun ExternalPlayerCard(app: HikariApp) {
    val scope = rememberCoroutineScope()
    val flow = remember { app.store.playerExternalButtonFlow() }
    val on by flow.collectAsState(initial = true)

    Column(Modifier.padding(16.dp)) {
        SettingsCardHeading(Icons.Filled.OpenInNew, tr("External player"))
        SettingsToggle(
            label = tr("Show the external-player row"),
            supporting = tr("Offers the playing video to VLC and other installed players from the player options menu. Turn it off to hide the row."),
            checked = on,
            onCheckedChange = { value ->
                scope.launch { runCatching { app.store.setPlayerExternalButton(value) } }
            },
        )
    }
}

@Composable
private fun EpisodeViewModeCard(app: HikariApp) {
    val scope = rememberCoroutineScope()
    val flow = remember { app.store.episodeViewModeFlow() }
    val mode by flow.collectAsState(initial = -1)
    val glassFlow = remember { app.store.episodeGlassFlow() }
    val glass by glassFlow.collectAsState(initial = false)
    val options = listOf(
        -1 to tr("Auto (grid on TV, list on phone)"),
        0 to tr("List rows"),
        1 to tr("Poster grid"),
        2 to tr("Cinematic big cards"),
    )
    Column(Modifier.padding(16.dp)) {
        SettingsCardHeading(Icons.Filled.Tv, tr("Episode UI"))
        options.forEach { (value, label) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        scope.launch { runCatching { app.store.setEpisodeViewMode(value) } }
                    }
                    .padding(vertical = 6.dp),
            ) {
                RadioButton(
                    selected = mode == value,
                    onClick = {
                        scope.launch { runCatching { app.store.setEpisodeViewMode(value) } }
                    },
                )
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        Text(
            tr("Applies to every title on phone and TV. The button above the episode list switches the same setting."),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        SettingsToggle(
            label = tr("Glass box episode UI"),
            supporting = tr("Frosted-glass cards for list, grid and big episodes"),
            checked = glass,
            onCheckedChange = { on -> scope.launch { runCatching { app.store.setEpisodeGlass(on) } } },
        )
    }
}

/**
 * Size of the episode UI in list, grid and big-card modes, on phone and TV.
 * 100% keeps the existing size.
 */
@Composable
private fun TvEpisodeSizeCard(app: HikariApp) {
    val scope = rememberCoroutineScope()
    val flow = remember { app.store.tvEpisodeSizeFlow() }
    val size by flow.collectAsState(initial = 100)
    var draft by remember { mutableStateOf(100f) }

    LaunchedEffect(size) {
        draft = size.toFloat()
    }

    Column(Modifier.padding(16.dp)) {
        SettingsCardHeading(Icons.Filled.Tv, tr("Episode size UI"))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                tr("Episode size"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "${draft.roundToInt()}%",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = draft,
            onValueChange = { draft = it },
            onValueChangeFinished = {
                scope.launch { runCatching { app.store.setTvEpisodeSize(draft.roundToInt()) } }
            },
            valueRange = 80f..160f,
            steps = 7,
            modifier = Modifier.fillMaxWidth().tvAdjust { delta ->
                draft = (draft.roundToInt() + delta * 10).coerceIn(80, 160).toFloat()
                scope.launch { runCatching { app.store.setTvEpisodeSize(draft.roundToInt()) } }
            },
        )
        Text(
            tr("Applies to the episode list, poster grid and big cards, on phone and TV."),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EpisodeDescriptionCard(app: HikariApp) {
    val scope = rememberCoroutineScope()
    val flow = remember { app.store.episodeDescriptionFlow() }
    val on by flow.collectAsState(initial = true)

    Column(Modifier.padding(16.dp)) {
        SettingsCardHeading(Icons.Filled.Description, tr("Episode descriptions"))
        SettingsToggle(
            label = tr("Show episode descriptions"),
            supporting = tr("Print the description under each episode title. Off shows titles only."),
            checked = on,
            onCheckedChange = { value ->
                scope.launch { runCatching { app.store.setEpisodeDescription(value) } }
            },
        )
    }
}

/**
 * TV menu layout for Source, Subtitles, Audio, Quality and other player sheets.
 */
@Composable
private fun TvPlayerPanelsCard(app: HikariApp) {
    val scope = rememberCoroutineScope()
    val flow = remember { app.store.playerTvPanelsFlow() }
    val on by flow.collectAsState(initial = false)

    Column(Modifier.padding(16.dp)) {
        SettingsCardHeading(Icons.Filled.Tv, tr("TV player panels"))
        SettingsToggle(
            label = tr("Use large left-side panels"),
            supporting = tr("On TV, open player menus from the left side toward the middle. D-pad navigation stays enabled."),
            checked = on,
            onCheckedChange = { value ->
                scope.launch { runCatching { app.store.setPlayerTvPanels(value) } }
            },
        )
    }
}

@Composable
private fun VolumeBoostCard(app: HikariApp) {
    val scope = rememberCoroutineScope()
    val flow = remember { app.store.volumeBoostFlow() }
    val on by flow.collectAsState(initial = false)

    Column(Modifier.padding(16.dp)) {
        SettingsCardHeading(Icons.Filled.VolumeUp, tr("Volume booster"))
        SettingsToggle(
            label = tr("Boost quiet audio"),
            supporting = tr(
                "Turns the volume up to 200% for films with quiet dialogue. " +
                    "Applies to the next video you open."
            ),
            checked = on,
            onCheckedChange = { value ->
                scope.launch { runCatching { app.store.setVolumeBoost(value) } }
            },
        )
    }
}

@Composable
private fun SubtitlesDefaultCard(app: HikariApp) {
    val scope = rememberCoroutineScope()
    val flow = remember { app.store.subsDefaultOnFlow() }
    val on by flow.collectAsState(initial = true)

    Column(Modifier.padding(16.dp)) {
        SettingsCardHeading(Icons.Filled.TextFields, tr("Subtitles"))
        SettingsToggle(
            label = tr("Subtitles on by default"),
            supporting = tr(
                "Automatically show subtitles when a video opens. " +
                    "Off means videos start with captions hidden."
            ),
            checked = on,
            onCheckedChange = { value ->
                scope.launch { runCatching { app.store.setSubsDefaultOn(value) } }
            },
        )
    }
}

@Composable
private fun FullscreenCard(app: HikariApp) {
    val scope = rememberCoroutineScope()
    val flow = remember { app.store.fullscreenOffFlow() }
    val off by flow.collectAsState(initial = false)

    Column(Modifier.padding(16.dp)) {
        SettingsCardHeading(
            if (off) Icons.Filled.Fullscreen else Icons.Filled.FullscreenExit,
            tr("Full screen app mode"),
        )
        SettingsToggle(
            label = tr("Turn off full screen app mode"),
            supporting = tr("Shows the phone's status bar on every screen"),
            checked = off,
            onCheckedChange = { value ->
                scope.launch { runCatching { app.store.setFullscreenOff(value) } }
            },
        )
    }
}

@Composable
private fun NavBarCard(app: HikariApp) {
    val scope = rememberCoroutineScope()
    val styleFlow = remember { app.store.navStyleFlow() }
    val style by styleFlow.collectAsState(initial = NavStyles.ANIMATED)
    var pickerOpen by remember { mutableStateOf(false) }
    val chosen = NavStyles.ALL.firstOrNull { it.key == style } ?: NavStyles.ALL.first()

    Column(Modifier.padding(16.dp)) {
        Text(
            tr("Navigation bar"),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(10.dp))
        ChoiceRow(
            value = tr(chosen.label),
            supporting = tr(chosen.blurb),
            leadingIcon = Icons.Filled.Dashboard,
            onClick = { pickerOpen = true },
        )
    }

    if (pickerOpen) {
        ChoiceDialog(
            title = tr("Navigation bar"),
            items = NavStyles.ALL.map { ChoiceItem(it.key, tr(it.label), tr(it.blurb)) },
            selectedKey = style,
            onPick = { pick -> scope.launch { runCatching { app.store.setNavStyle(pick) } } },
            onDismiss = { pickerOpen = false },
        )
    }
}

@Composable
private fun ApiKeyEditor(
    label: String,
    value: String,
    savedValue: String,
    onValueChange: (String) -> Unit,
    onSave: () -> Unit,
) {
    var visible by rememberSaveable(label) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            singleLine = true,
            visualTransformation = if (visible) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                IconButton(onClick = { visible = !visible }) {
                    Icon(
                        if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (visible) "Hide $label" else "Show $label",
                    )
                }
            },
            modifier = Modifier.fillMaxWidth().tvTextFieldKeys(value),
        )
        TextButton(
            onClick = onSave,
            enabled = value.trim() != savedValue.trim(),
            modifier = Modifier.tvPress(previewPass = true, onClick = onSave),
        ) {
            Text(if (value.trim() == savedValue.trim()) tr("Saved") else tr("Save"))
        }
    }
}

@Composable
private fun TmdbIntegrationCard(app: HikariApp) {
    val scope = rememberCoroutineScope()
    val enabled by remember { app.store.tmdbEnabledFlow() }.collectAsState(initial = true)
    val savedKey by remember { app.store.tmdbApiKeyFlow() }.collectAsState(initial = "")
    var draftKey by remember { mutableStateOf("") }
    LaunchedEffect(savedKey) { draftKey = savedKey }

    Column(Modifier.padding(16.dp)) {
        SettingsSection(
            id = "integrations.tmdb.main",
            icon = Icons.Filled.Panorama,
            title = tr("TMDB enrichment"),
            summary = if (enabled) tr("Enabled") else tr("Disabled"),
        ) {
            SettingsToggle(
                label = tr("Enable TMDB enrichment"),
                supporting = tr("Use TMDB as a metadata source to enhance addon data"),
                checked = enabled,
                onCheckedChange = { scope.launch { runCatching { app.store.setTmdbEnabled(it) } } },
            )
            Spacer(Modifier.height(10.dp))
            Text(
                tr("Personal API key"),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                tr("Leave blank to use the default. A personal key overrides it."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
            )
            ApiKeyEditor(
                label = "TMDB API key",
                value = draftKey,
                savedValue = savedKey,
                onValueChange = { draftKey = it },
                onSave = {
                    scope.launch { runCatching { app.store.setTmdbApiKey(draftKey.trim()) } }
                },
            )
        }
    }
}

/** Every TMDB enrichment module (shared by the old Modules card + the floating window). */
private val TmdbModuleDefs = listOf(
        Triple("trailers", "Trailers", "Trailer candidates from TMDB videos for the detail trailer section"),
        Triple("artwork", "Artwork", "Logo and backdrop images from TMDB"),
        Triple("basic", "Basic Info", "Description, genres, and rating from TMDB"),
        Triple("details", "Details", "Runtime, status, country, and language from TMDB"),
        Triple("credits", "Credits", "Cast with photos, director, and writer from TMDB"),
        Triple("productions", "Productions", "Production companies from TMDB"),
        Triple("networks", "Networks", "Networks with logos from TMDB"),
        Triple("episodes", "Episodes", "Episode titles, overviews, thumbnails, and runtime from TMDB"),
        Triple("season_posters", "Season posters", "Use TMDB season posters in the metadata season selector"),
        Triple("more_like", "More Like This", "TMDB recommendation backdrops on the detail page"),
        Triple("collections", "Collections", "TMDB movie collections in release order"),
    )

/** The module on/off rows (one shared list, two hosts). */
@Composable
private fun TmdbModuleToggleList(app: HikariApp) {
    val scope = rememberCoroutineScope()
    TmdbModuleDefs.forEachIndexed { index, module ->
        val (key, title, description) = module
        val moduleEnabled by remember(key) { app.store.tmdbModuleFlow(key) }.collectAsState(initial = true)
        SettingsToggle(
            label = tr(title),
            supporting = tr(description),
            checked = moduleEnabled,
            onCheckedChange = { value ->
                scope.launch { runCatching { app.store.setTmdbModule(key, value) } }
            },
        )
        if (index != TmdbModuleDefs.lastIndex) {
            HorizontalDivider(
                modifier = Modifier.padding(top = 10.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
            )
        }
    }
}

@Composable
private fun TmdbModulesCard(app: HikariApp) {
    Column(Modifier.padding(16.dp)) {
        SettingsSection(
            id = "integrations.tmdb.modules",
            icon = Icons.Filled.Tune,
            title = tr("Modules"),
            summary = tr("Choose which TMDB enrichments are requested"),
        ) {
            TmdbModuleToggleList(app)
        }
    }
}

/** Floating TMDB modules window (opened from Custom metadata tweaks). */
@Composable
private fun TmdbModulesDialog(app: HikariApp, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        // Opaque surface, not translucent glass: the settings list behind
        // used to show through and double every line of text.
        androidx.compose.material3.Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(
                Modifier
                    .padding(16.dp)
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    tr("Modules"),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    tr("Choose which TMDB enrichments are requested"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                TmdbModuleToggleList(app)
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = onDismiss) { Text(tr("Close")) }
            }
        }
    }
}

@Composable
private fun MdbListIntegrationCard(app: HikariApp) {
    val scope = rememberCoroutineScope()
    val enabled by remember { app.store.mdbEnabledFlow() }.collectAsState(initial = false)
    val savedKey by remember { app.store.mdbListApiKeyFlow() }.collectAsState(initial = "")
    var draftKey by remember { mutableStateOf("") }
    LaunchedEffect(savedKey) { draftKey = savedKey }

    Column(Modifier.padding(16.dp)) {
        SettingsSection(
            id = "integrations.mdblist.main",
            icon = Icons.Filled.Star,
            title = tr("MDBList ratings"),
            summary = if (enabled) tr("Enabled") else tr("Disabled"),
        ) {
            SettingsToggle(
                label = tr("Enable MDBList ratings"),
                supporting = tr("Fetch ratings from external providers in metadata detail screens"),
                checked = enabled,
                onCheckedChange = { scope.launch { runCatching { app.store.setMdbEnabled(it) } } },
            )
            Spacer(Modifier.height(10.dp))
            Text(
                tr("API key"),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                tr("Save either key independently. Clearing a key and saving removes it."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
            )
            ApiKeyEditor(
                label = "MDBList API key",
                value = draftKey,
                savedValue = savedKey,
                onValueChange = { draftKey = it },
                onSave = {
                    scope.launch {
                        runCatching {
                            app.store.setMdbListApiKey(draftKey.trim())
                            Ratings.clearCache()
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun MdbListProvidersCard(app: HikariApp) {
    val scope = rememberCoroutineScope()
    val providers = listOf(
        Triple("imdb", "IMDb", "IMDb score"),
        Triple("tmdb", "TMDB", "TMDB score"),
        Triple("tomatoes", "Rotten Tomatoes", "Tomatometer"),
        Triple("metacritic", "Metacritic", "Metascore"),
        Triple("trakt", "Trakt", "Trakt score"),
        Triple("letterboxd", "Letterboxd", "Letterboxd score"),
        Triple("audience", "Audience Score", "Rotten Tomatoes audience score"),
        Triple("mal", "MyAnimeList", "MyAnimeList score"),
    )
    Column(Modifier.padding(16.dp)) {
        SettingsSection(
            id = "integrations.mdblist.providers",
            icon = Icons.Filled.Star,
            title = tr("External ratings providers"),
            summary = tr("Choose which ratings MDBList contributes"),
        ) {
            providers.forEachIndexed { index, provider ->
                val (key, title, description) = provider
                val providerEnabled by remember(key) { app.store.mdbProviderFlow(key) }.collectAsState(initial = true)
                SettingsToggle(
                    label = tr(title),
                    supporting = tr(description),
                    checked = providerEnabled,
                    onCheckedChange = { value ->
                        scope.launch {
                            runCatching {
                                app.store.setMdbProvider(key, value)
                                Ratings.clearCache()
                            }
                        }
                    },
                )
                if (index != providers.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(top = 10.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                    )
                }
            }
        }
    }
}


@Composable
private fun TmdbLanguageCard(app: HikariApp, appLanguage: String) {
    val scope = rememberCoroutineScope()
    val flow = remember { app.store.tmdbLanguageFlow() }
    val saved by flow.collectAsState(initial = "")
    val followed = TmdbLang.forAppLanguage(appLanguage)
    var pickerOpen by remember { mutableStateOf(false) }
    val currentLabel = when {
        saved.isBlank() -> tr("Follow app language")
        saved == "none" -> tr("Off (TMDB default)")
        else -> TmdbLang.CHOICES.firstOrNull { it.first == saved }?.second ?: saved
    }
    val items = buildList {
        add(
            ChoiceItem(
                key = "",
                label = tr("Follow app language"),
                supporting = if (followed.isBlank()) {
                    tr("English is used for this language")
                } else {
                    tr("Currently") + ": " + followed
                },
            )
        )
        add(
            ChoiceItem(
                key = "none",
                label = tr("Off (TMDB default)"),
                supporting = tr("Titles as TMDB releases them"),
            )
        )
        TmdbLang.CHOICES.forEach { (code, name) -> add(ChoiceItem(code, name, code)) }
    }

    Column(Modifier.padding(16.dp)) {
        Text(
            tr("Title language (TMDB)"),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(10.dp))
        ChoiceRow(
            value = currentLabel,
            supporting = if (saved.isBlank() && followed.isNotBlank()) tr("Currently") + ": " + followed else null,
            leadingIcon = Icons.Filled.Translate,
            onClick = { pickerOpen = true },
        )
    }

    if (pickerOpen) {
        ChoiceDialog(
            title = tr("Title language (TMDB)"),
            items = items,
            selectedKey = saved,
            onPick = { pick -> scope.launch { runCatching { app.store.setTmdbLanguage(pick) } } },
            onDismiss = { pickerOpen = false },
        )
    }
}

private fun rasterizeIcon(context: Context, @DrawableRes resId: Int): ImageBitmap? =
    runCatching {
        val drawable = context.getDrawable(resId) ?: return@runCatching null
        // The tile is drawn 1:1 in the 64dp box, so 2 px per dp is already
        // sharper than any phone screen needs (and keeps the bitmaps small).
        val px = (128 * context.resources.displayMetrics.density).toInt().coerceIn(192, 768)
        val bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, px, px)
        drawable.draw(canvas)
        bitmap.asImageBitmap()
    }.getOrNull()

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppIconCard(app: HikariApp) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentFlow = remember { app.store.appIconFlow() }
    val current by currentFlow.collectAsState(initial = AppIconManager.DEFAULT_KEY)
    val tile = 64.dp
    val tileShape = RoundedCornerShape(tile * 0.26f)

    SettingsSection(
        id = "appearance.icon",
        icon = Icons.Filled.Android,
        title = tr("App icon"),
        summary = AppIconVariants.firstOrNull { it.key == current }?.let { tr(it.label) },
    ) {
        FlowRow(horizontalArrangement = Arrangement.Start) {
            AppIconVariants.forEach { v ->
                val selected = v.key == current
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .padding(end = 10.dp, bottom = 12.dp)
                        .width(tile + 6.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(tile)
                            .clip(tileShape)
                            .clickable {
                                if (!selected) {
                                    scope.launch {
                                        runCatching { app.store.setAppIcon(v.key) }
                                        withContext(Dispatchers.IO) {
                                            AppIconManager.apply(context, v.key)
                                        }
                                    }
                                    Toast.makeText(context, I18n.t("Icon updated"), Toast.LENGTH_SHORT).show()
                                }
                            }
                            .border(
                                width = if (selected) 2.dp else 1.dp,
                                color = if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                                shape = tileShape
                            )
                    ) {
                        val icon = remember(v.key) { rasterizeIcon(context, v.preview) }
                        if (icon != null) {
                            Image(
                                bitmap = icon,
                                contentDescription = v.label,
                                contentScale = ContentScale.FillBounds,
                                modifier = Modifier.size(tile)
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        tr(v.label),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        textAlign = TextAlign.Center,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            tr("Your launcher may need to refresh"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DnsModeCard(app: HikariApp) {
    val scope = rememberCoroutineScope()
    var providerKey by remember { mutableStateOf(DnsProviders.SYSTEM) }
    var typed by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var testReason by remember { mutableStateOf<String?>(null) }
    var testRan by remember { mutableStateOf(false) }
    var pickerOpen by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        providerKey = app.store.dnsProvider()
        val stored = app.store.customDns()
        typed = stored
        saved = stored
    }

    val chosen = DnsProviders.byKey(providerKey)
    val endpoint = DnsProviders.customEndpoint(typed)

    fun select(key: String) {
        providerKey = key
        testRan = false
        scope.launch { runCatching { app.store.setDnsProvider(key) } }
    }

    // Hoisted out of runTest(): tr() is @Composable, so a plain local function
    // is not allowed to call it.
    val addrWarning = tr("Write an address first")

    fun runTest() {
        val target = when (chosen.key) {
            DnsProviders.SYSTEM -> null
            DnsProviders.CUSTOM -> endpoint
            else -> chosen
        }
        if (chosen.key == DnsProviders.CUSTOM && target == null) {
            testRan = true
            testReason = addrWarning
            return
        }
        testing = true
        testRan = false
        scope.launch {
            val reason = withContext(Dispatchers.IO) { DohDns.probe(target) }
            testing = false
            testReason = reason
            testRan = true
        }
    }

    SettingsSection(
        id = "network.dns",
        icon = Icons.Filled.Public,
        title = tr("DNS mode"),
        summary = tr(chosen.label),
    ) {
        ChoiceRow(
            value = tr(chosen.label),
            supporting = when {
                chosen.key == DnsProviders.CUSTOM && endpoint != null -> endpoint.url
                chosen.key == DnsProviders.CUSTOM -> tr("Any DNS-over-HTTPS address")
                else -> tr(chosen.note)
            },
            leadingIcon = Icons.Filled.Public,
            onClick = { pickerOpen = true },
        )

        if (providerKey == DnsProviders.CUSTOM) {
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = typed,
                onValueChange = {
                    typed = it
                    testRan = false
                },
                label = { Text(tr("DNS address")) },
                placeholder = { Text("https://dns.example.com/dns-query") },
                singleLine = true,
                shape = GlassShape,
                isError = typed.isNotBlank() && endpoint == null,
                modifier = Modifier.fillMaxWidth().tvTextFieldKeys(typed)
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (typed.isNotBlank() && endpoint == null) {
                    tr("That doesn't look like a web address.")
                } else {
                    tr("Bare hosts get https:// and /dns-query")
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (typed.isNotBlank() && endpoint == null) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (typed.trim() != saved) {
                Spacer(Modifier.height(4.dp))
                TextButton(
                    enabled = endpoint != null,
                    onClick = {
                        val value = typed.trim()
                        saved = value
                        testRan = false
                        scope.launch { runCatching { app.store.setCustomDns(value) } }
                    }
                ) { Text(tr("Use this address")) }
            }
        }

        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    tr("Check this resolver"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    when {
                        testRan && testReason == null -> tr("Working — it resolved example.com.")
                        testRan -> testReason.orEmpty()
                        else -> tr("Looks up example.com to check it")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (testRan && testReason == null) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (testing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                TextButton(onClick = { runTest() }) { Text(tr("Test")) }
            }
        }

        if (pickerOpen) {
            ChoiceDialog(
                title = tr("DNS mode"),
                items = DnsProviders.ALL.map { ChoiceItem(it.key, tr(it.label), tr(it.note)) },
                selectedKey = providerKey,
                onPick = { select(it) },
                onDismiss = { pickerOpen = false },
            )
        }
    }
}

@Composable
private fun SlowConnectionCard(app: HikariApp) {
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(false) }
    var tipEnabled by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        enabled = app.store.slowConnection()
        tipEnabled = app.store.slowTipEnabled()
    }

    SettingsSection(
        id = "network.slow",
        icon = Icons.Filled.Speed,
        title = tr("Mobile data / slow internet"),
        summary = tr("Slow connection mode") + " " + (if (enabled) tr("on") else tr("off")),
    ) {
        SettingsToggle(
            label = tr("Slow connection mode"),
            supporting = tr("Longer timeouts, and retries extensions"),
            checked = enabled,
            onCheckedChange = {
                enabled = it
                NetTuning.setSlowConnection(it)
                scope.launch { runCatching { app.store.setSlowConnection(it) } }
            },
        )
        SettingsToggle(
            label = tr("Suggest it when videos start slowly"),
            supporting = tr("The player offers to switch it on"),
            checked = tipEnabled,
            onCheckedChange = {
                tipEnabled = it
                scope.launch { runCatching { app.store.setSlowTipEnabled(it) } }
            },
        )
    }
}

@Composable
private fun PlaybackStartCard(app: HikariApp) {
    val scope = rememberCoroutineScope()
    var waitServers by remember { mutableStateOf(false) }
    var minServers by remember { mutableStateOf(2f) }
    var askServer by remember { mutableStateOf(false) }
    var failoverAsk by remember { mutableStateOf(true) }
    var scopeAsk by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        waitServers = app.store.playWaitServers()
        minServers = app.store.playMinServers().toFloat()
        askServer = app.store.askServerOnPlay()
        failoverAsk = app.store.failoverAskOnFailure()
        scopeAsk = app.store.playScopeAsk()
    }

    fun persist(wait: Boolean) {
        waitServers = wait
        scope.launch { runCatching { app.store.setPlayWaitServers(wait) } }
    }

    SettingsSection(
        id = "player.start",
        icon = Icons.Filled.PlayArrow,
        title = tr("Playback start"),
        summary = when {
            askServer -> tr("Show the server list first")
            waitServers -> tr("Wait for") + " " + minServers.roundToInt() + " " + tr("servers")
            else -> tr("Play the first server found")
        },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { persist(false) }
                .tvToggle(!waitServers, onValueChange = { persist(false) }),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = !waitServers, onClick = null)
            Column(Modifier.weight(1f)) {
                Text(
                    tr("Play as soon as the first server is found"),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    tr("Instant — falls back if it's dead"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { persist(true) }
                // See the row above: the whole line is the target, so the D-pad
                // can reach this choice too.
                .tvToggle(waitServers, onValueChange = { persist(true) }),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = waitServers, onClick = null)
            Column(Modifier.weight(1f)) {
                Text(
                    tr("Wait for more servers first"),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    tr("Or when every extension has finished"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (waitServers) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    tr("Servers to wait for"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.weight(1f))
                Text(
                    minServers.roundToInt().toString(),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            val commitMinServers: () -> Unit = {
                    val n = minServers.roundToInt().coerceIn(1, 5)
                    minServers = n.toFloat()
                    scope.launch { runCatching { app.store.setPlayMinServers(n) } }
            }
            Slider(
                value = minServers,
                onValueChange = { minServers = it },
                onValueChangeFinished = commitMinServers,
                valueRange = 1f..5f,
                steps = 3,
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f),
                    activeTickColor = Color.Transparent,
                    inactiveTickColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth().tvAdjust { delta ->
                minServers = (minServers.roundToInt() + delta).coerceIn(1, 5).toFloat()
                commitMinServers()
            }
            )
            Text(
                tr("Fewer found? Playback starts anyway"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    tr("Always show the server list"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    if (askServer) {
                        tr("On — Play opens the server list")
                    } else {
                        tr("Off — Play starts the first server")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = askServer,
                onCheckedChange = {
                    askServer = it
                    scope.launch { runCatching { app.store.setAskServerOnPlay(it) } }
                },
                // See [Modifier.tvToggle].
                modifier = Modifier.tvToggle(askServer) {
                    askServer = it
                    scope.launch { runCatching { app.store.setAskServerOnPlay(it) } }
                },
            )
        }

        // Only meaningful when the player is choosing a server by itself: when a
        // server the USER picked fails, should Hikari switch on its own or ask?
        if (!askServer) {
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        tr("Ask when my server fails"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        tr("Offer the next one instead of switching"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = failoverAsk,
                    onCheckedChange = {
                        failoverAsk = it
                        scope.launch { runCatching { app.store.setFailoverAskOnFailure(it) } }
                    },
                    // See [Modifier.tvToggle].
                    modifier = Modifier.tvToggle(failoverAsk) {
                        failoverAsk = it
                        scope.launch { runCatching { app.store.setFailoverAskOnFailure(it) } }
                    },
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    tr("Ask where to play"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    if (scopeAsk) {
                        tr("On — Play asks: this extension or my selected servers")
                    } else {
                        tr("Off — Play uses your server-search choices directly")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = scopeAsk,
                onCheckedChange = {
                    scopeAsk = it
                    scope.launch { runCatching { app.store.setPlayScopeAsk(it) } }
                },
                // See [Modifier.tvToggle].
                modifier = Modifier.tvToggle(scopeAsk) {
                    scopeAsk = it
                    scope.launch { runCatching { app.store.setPlayScopeAsk(it) } }
                },
            )
        }
    }
}

@Composable
private fun ServerLayoutCard(app: HikariApp) {
    val scope = rememberCoroutineScope()
    var layout by remember { mutableStateOf(com.hikari.app.player.ServerLayouts.HORIZONTAL) }

    LaunchedEffect(Unit) {
        layout = runCatching { app.store.serverLayout() }
            .getOrDefault(com.hikari.app.player.ServerLayouts.HORIZONTAL)
    }

    fun persist(next: String) {
        layout = com.hikari.app.player.ServerLayouts.namespace(next)
        scope.launch { runCatching { app.store.setServerLayout(layout) } }
    }

    SettingsSection(
        id = "player.sources_layout",
        icon = Icons.Filled.ViewModule,
        title = tr("Server page layout"),
        summary = if (com.hikari.app.player.ServerLayouts.namespace(layout) == com.hikari.app.player.ServerLayouts.VERTICAL) {
            tr("Vertical — compact cards over poster art")
        } else {
            tr("Horizontal — rows over backdrop art")
        },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { persist(com.hikari.app.player.ServerLayouts.HORIZONTAL) }
                // A remote lands on the ROW — the radio button alone is a small
                // right-hand target the focus search skips past. See [Modifier.tvToggle].
                .tvToggle(layout != com.hikari.app.player.ServerLayouts.VERTICAL, onValueChange = {
                    if (it) persist(com.hikari.app.player.ServerLayouts.HORIZONTAL)
                }),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = layout != com.hikari.app.player.ServerLayouts.VERTICAL, onClick = null)
            Column(Modifier.weight(1f)) {
                Text(
                    tr("Horizontal"),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    tr("Wide rows over backdrop art, like now"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { persist(com.hikari.app.player.ServerLayouts.VERTICAL) }
                // See the row above: the whole line is the target, so the D-pad
                // can reach this choice too.
                .tvToggle(layout == com.hikari.app.player.ServerLayouts.VERTICAL, onValueChange = {
                    if (it) persist(com.hikari.app.player.ServerLayouts.VERTICAL)
                }),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = layout == com.hikari.app.player.ServerLayouts.VERTICAL, onClick = null)
            Column(Modifier.weight(1f)) {
                Text(
                    tr("Vertical"),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    tr("Compact cards over portrait poster art"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private val ProviderType.isFamilyEngine: Boolean
    get() = this != ProviderType.NUVIO && this != ProviderType.STREMIO &&
        this != ProviderType.IPTV && this != ProviderType.MANGA

@Composable
private fun TrackerServerSearchCard(app: HikariApp) {
    val scope = rememberCoroutineScope()
    val trackerSearchAll by remember { app.store.trackerServerSearchAllFlow() }.collectAsState(initial = true)
    val trackerNuvioAll by remember { app.store.trackerNuvioSearchAllFlow() }.collectAsState(initial = true)
    val trackerStremioAll by remember { app.store.trackerStremioSearchAllFlow() }.collectAsState(initial = true)
    val trackerFamilies by remember { app.store.trackerEngineFamiliesFlow() }.collectAsState(initial = setOf(ProviderType.NUVIO.name, ProviderType.STREMIO.name))
    val trackerProviderIds by remember { app.store.trackerProviderIdsFlow() }.collectAsState(initial = emptySet())
    val installed by app.providers.providers.collectAsState()
    val enabled = installed.filter { it.config.enabled }
    val engines = remember(enabled) { enabled.map { it.config.type }.distinct().filter { it != ProviderType.IPTV && it != ProviderType.MANGA && it != ProviderType.ANYMEX_MANGA }.sortedBy { it.groupLabel } }
    var pickerEngine by remember { mutableStateOf<ProviderType?>(null) }
    fun familyOn(t: ProviderType): Boolean = when (t) {
        ProviderType.NUVIO -> trackerNuvioAll
        ProviderType.STREMIO -> trackerStremioAll
        else -> t.name in trackerFamilies
    }
    suspend fun setFamily(t: ProviderType, on: Boolean) {
        when (t) {
            ProviderType.NUVIO -> app.store.setTrackerNuvioSearchAll(on)
            ProviderType.STREMIO -> app.store.setTrackerStremioSearchAll(on)
            else -> app.store.setTrackerEngineFamily(t, on)
        }
    }
    Column(Modifier.padding(16.dp)) {
        SettingsCardHeading(Icons.Filled.Sync, tr("Tracker servers"))
        if (!LocalHideHelp.current) Text(
            tr("These source choices apply only to titles opened from tracker libraries. The existing server-search options above are unchanged."),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        SettingsToggle(label = tr("Search tracker servers"), supporting = tr("Use the tracker-only source choices below"),
            checked = trackerSearchAll, onCheckedChange = { on -> scope.launch { runCatching { app.store.setTrackerServerSearchAll(on) } } })
        if (trackerSearchAll) {
            Spacer(Modifier.height(10.dp))
            for (t in engines) {
                val ids = enabled.filter { it.config.type == t }.map { it.config.id }.toSet()
                val on = familyOn(t)
                val selected = if (on) ids else trackerProviderIds.intersect(ids)
                SettingsToggle(
                    label = tr("Search every %s").replace("%s", t.groupLabel),
                    supporting = tr("Use every installed %s for tracker titles").replace("%s", t.groupLabel),
                    checked = on,
                    onCheckedChange = { v -> scope.launch { runCatching { setFamily(t, v) } } },
                )
                Spacer(Modifier.height(6.dp))
                ChoiceRow(
                    value = when {
                        selected.isEmpty() -> tr("Choose %s extensions").replace("%s", t.groupLabel)
                        on -> tr("All %s extensions (%s)").replace("%s", t.groupLabel).replace("%s", ids.size.toString())
                        else -> selected.size.toString() + " " + t.groupLabel
                    },
                    supporting = tr("Tap to choose individual %s extensions").replace("%s", t.groupLabel),
                    leadingIcon = Icons.Filled.Extension,
                    onClick = { pickerEngine = t },
                )
                Spacer(Modifier.height(10.dp))
            }
        }
    }
    val pe = pickerEngine
    if (pe != null) {
        val list = enabled.filter { it.config.type == pe }.sortedBy { it.config.name.ifBlank { it.config.id }.lowercase() }
        val ids = list.map { it.config.id }.toSet()
        val on = familyOn(pe)
        val selected = if (on) ids else trackerProviderIds.intersect(ids)
        MultiChoiceDialog(title = pe.groupLabel + " " + tr("tracker servers"),
            items = list.map { ChoiceItem(key = it.config.id, label = it.config.name.ifBlank { it.config.id }, supporting = it.config.type.groupLabel) },
            selectedKeys = selected,
            onToggle = { id ->
                scope.launch {
                    val next = selected.toMutableSet()
                    if (!next.add(id)) next.remove(id)
                    if (next.size == ids.size && ids.isNotEmpty()) {
                        runCatching { setFamily(pe, true); app.store.setTrackerProviderIds(trackerProviderIds - ids) }
                    } else {
                        runCatching { setFamily(pe, false); app.store.setTrackerProviderIds((trackerProviderIds - ids) + next) }
                    }
                }
            }, onDismiss = { pickerEngine = null }, searchable = true,
            searchPlaceholder = tr("Search %s extensions").replace("%s", pe.groupLabel),
            footnote = tr("Scrollable list — tap again to unselect. Select only the extensions you want tracker titles to search."))
    }
}

@Composable
private fun ServerFamilyChoiceRow(
    groupLabel: String,
    engineIds: Set<String>,
    familyOn: Boolean,
    pickedIds: Set<String>,
    onOpen: () -> Unit,
) {
    val kept = if (familyOn) engineIds else pickedIds.intersect(engineIds)
    ChoiceRow(
        value = when {
            engineIds.isEmpty() -> tr("No %s extensions installed").replace("%s", groupLabel)
            kept.isEmpty() -> tr("Choose %s extensions").replace("%s", groupLabel)
            familyOn -> tr("All %s extensions (%s)").replace("%s", groupLabel).replace("%s", engineIds.size.toString())
            else -> kept.size.toString() + " " + groupLabel
        },
        supporting = tr("Tap to choose individual %s extensions").replace("%s", groupLabel),
        leadingIcon = Icons.Filled.Extension,
        onClick = onOpen,
    )
}

@Composable
private fun ServerSearchCard(app: HikariApp) {
    val scope = rememberCoroutineScope()
    val flow = remember { app.store.searchAllExtensionsFlow() }
    val all by flow.collectAsState(initial = false)
    // Every engine whose whole installed family is searched for a title opened
    // from one of its repos — one value for all of them (see
    // AppStore.engineFamiliesFlow). Nuvio and Stremio have their own rows below;
    // the site-scraper engines are rendered as a row each, further down.
    val familiesFlow = remember { app.store.engineFamiliesFlow() }
    val families by familiesFlow.collectAsState(initial = setOf("NUVIO", "STREMIO"))
    // "Search every Nuvio provider" — the one widening that does not follow the
    // switch above. See AppStore.nuvioSearchAllFlow / SearchScope.nuvioFamily.
    val nuvioFlow = remember { app.store.nuvioSearchAllFlow() }
    val nuvioAll by nuvioFlow.collectAsState(initial = true)
    // "Search every Stremio addon" — the addon twin of the switch above. See
    // AppStore.stremioSearchAllFlow / SearchScope.stremioFamily.
    val stremioFlow = remember { app.store.stremioSearchAllFlow() }
    val stremioAll by stremioFlow.collectAsState(initial = true)
    val nuvioIds by remember { app.store.searchNuvioIdsFlow() }.collectAsState(initial = emptySet())
    val stremioIds by remember { app.store.searchStremioIdsFlow() }.collectAsState(initial = emptySet())
    val familyIds by remember { app.store.searchFamilyIdsFlow() }.collectAsState(initial = emptySet())
    var serverPickerEngine by remember { mutableStateOf<ProviderType?>(null) }
    val exceptionOnFlow = remember { app.store.searchExceptionOnFlow() }
    val exceptionOn by exceptionOnFlow.collectAsState(initial = false)
    val exceptionIdsFlow = remember { app.store.searchExceptionIdsFlow() }
    val exceptionIds by exceptionIdsFlow.collectAsState(initial = emptySet())
    val exceptionTypesFlow = remember { app.store.searchExceptionTypesFlow() }
    val exceptionTypes by exceptionTypesFlow.collectAsState(initial = emptySet())
    val exceptionExcludesFlow = remember { app.store.searchExceptionExcludesFlow() }
    val exceptionExcludes by exceptionExcludesFlow.collectAsState(initial = emptySet())
    val effectiveFlow = remember { app.store.activeSearchExceptionsFlow() }
    val effectiveIds by effectiveFlow.collectAsState(initial = emptySet())
    var pickerOpen by remember { mutableStateOf(false) }
    // Which engine the picker's list is narrowed to (null = all of them). Kept
    // in the card so it survives closing and reopening the picker.
    var engineFilter by remember { mutableStateOf<String?>(null) }

    val installed by app.providers.providers.collectAsState()
    val installedEnabled = installed.filter { it.config.enabled }
    val chosenHere = installedEnabled.filter { it.config.id in exceptionIds }
    val enginesHere = remember(installedEnabled) {
        installedEnabled.map { it.config.type }.distinct().sortedBy { it.groupLabel }
    }

    Column(Modifier.padding(16.dp)) {
        SettingsCardHeading(Icons.Filled.Extension, tr("Server search"))
        SettingsToggle(
            label = tr("Search all installed extensions"),
            supporting = if (all) {
                tr("Every installed extension is asked for servers, for every title")
            } else {
                tr("Only the extension the title was opened from")
            },
            checked = all,
            onCheckedChange = { on ->
                scope.launch { runCatching { app.store.setSearchAllExtensions(on) } }
            },
        )
        Spacer(Modifier.height(8.dp))
        if (!LocalHideHelp.current) {
        Text(
            if (all) {
                tr(
                    "A title is searched across all of your extensions at once, so whichever one " +
                        "has a working server wins and the server list gathers what every " +
                        "extension found."
                )
            } else {
                tr(
                    "A title only ever plays from the extension you opened it from — the same " +
                        "way CloudStream works. Nothing else is searched: no other extensions, " +
                        "no background search, and no episode list borrowed from another site. " +
                        "Titles from an extension that has nothing for them will show no servers."
                )
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        }
        Spacer(Modifier.height(14.dp))

        
        SettingsToggle(
            label = tr("Search every Nuvio provider"),
            supporting = if (nuvioAll) {
                tr("A Nuvio title is also searched in your other Nuvio providers")
            } else {
                tr("A Nuvio title plays from that provider alone")
            },
            checked = nuvioAll,
            onCheckedChange = { on ->
                scope.launch { runCatching { app.store.setNuvioSearchAll(on) } }
            },
        )

        Spacer(Modifier.height(10.dp))

        ServerFamilyChoiceRow(
            groupLabel = ProviderType.NUVIO.groupLabel,
            engineIds = installedEnabled.filter { it.config.type == ProviderType.NUVIO }.map { it.config.id }.toSet(),
            familyOn = nuvioAll,
            pickedIds = nuvioIds,
            onOpen = { serverPickerEngine = ProviderType.NUVIO },
        )
        Spacer(Modifier.height(10.dp))

        SettingsToggle(
            label = tr("Search every Stremio addon"),
            supporting = if (stremioAll) {
                tr("A Stremio title is also searched in your other Stremio addons")
            } else {
                tr("A Stremio title plays from that addon alone")
            },
            checked = stremioAll,
            onCheckedChange = { on ->
                scope.launch { runCatching { app.store.setStremioSearchAll(on) } }
            },
        )

        ServerFamilyChoiceRow(
            groupLabel = ProviderType.STREMIO.groupLabel,
            engineIds = installedEnabled.filter { it.config.type == ProviderType.STREMIO }.map { it.config.id }.toSet(),
            familyOn = stremioAll,
            pickedIds = stremioIds,
            onOpen = { serverPickerEngine = ProviderType.STREMIO },
        )
        Spacer(Modifier.height(10.dp))

        // ---- Every OTHER engine's family ----
        // The same switch as the two above, one row per engine the user actually
        // has installed: with "search all installed extensions" off, a title
        // opened from ONE CloudStream repo plays from that repo alone — unless
        // this engine's switch is on, in which case every installed repo of that
        // engine (and only that engine) is asked beside it. Off by default; the
        // user's own words were "same turning on cloudstream toggle will search
        // servers from all installed CloudStream extensions".
        //
        // One row per ENGINE, not per repo: an engine's repos index the same kind
        // of content and answer the same shape of id, which is what makes the
        // family a sensible unit — the same reason the nuvio and Stremio switches
        // are per engine. Nuvio and Stremio keep their own rows above so their
        // existing switches (and the stored choices behind them) are untouched.
        for (type in enginesHere.filter { it.isFamilyEngine }) {
            Spacer(Modifier.height(10.dp))
            val on = type.name in families
            SettingsToggle(
                label = tr("Search every %s").replace("%s", type.groupLabel),
                supporting = if (on) {
                    tr("A title from one of them is also searched in your other %s")
                        .replace("%s", type.groupLabel)
                } else {
                    tr("A title plays from the %s repo you opened it from")
                        .replace("%s", type.groupLabel)
                },
                checked = on,
                onCheckedChange = { value ->
                    scope.launch { runCatching { app.store.setEngineFamily(type, value) } }
                },
            )
            Spacer(Modifier.height(6.dp))
            ServerFamilyChoiceRow(
                groupLabel = type.groupLabel,
                engineIds = installedEnabled.filter { it.config.type == type }.map { it.config.id }.toSet(),
                familyOn = on,
                pickedIds = familyIds,
                onOpen = { serverPickerEngine = type },
            )
        }

        Spacer(Modifier.height(14.dp))

        SettingsToggle(
            label = tr("Exception extensions"),
            supporting = tr("These repos are always searched, whichever extension you opened"),
            checked = exceptionOn,
            onCheckedChange = { on ->
                scope.launch { runCatching { app.store.setSearchExceptionOn(on) } }
            },
        )
        if (exceptionOn) {
            Spacer(Modifier.height(10.dp))
            val markedEngines = enginesHere.filter { it.name in exceptionTypes }
            ChoiceRow(
                value = when {
                    effectiveIds.isEmpty() -> tr("Choose extensions or engines")
                    effectiveIds.size == 1 -> installedEnabled
                        .firstOrNull { it.config.id in effectiveIds }
                        ?.config?.name?.takeIf { it.isNotBlank() }
                        ?: tr("1 extension chosen")
                    else -> effectiveIds.size.toString() + " " + tr("extensions chosen")
                },
                supporting = when {
                    markedEngines.isNotEmpty() -> buildString {
                        append(tr("Whole engines: "))
                        append(markedEngines.joinToString(", ") { it.groupLabel })
                        val leftOut = exceptionExcludes.size
                        if (leftOut > 0) {
                            append(" · ")
                            append(leftOut.toString() + " " + tr("left out"))
                        }
                        if (chosenHere.isNotEmpty()) {
                            append(" · ")
                            append(chosenHere.size.toString() + " " + tr("picked by name"))
                        }
                    }
                    effectiveIds.isEmpty() ->
                        tr("Tap to pick whole engines (every CloudStream repo), or single extensions")
                    else -> chosenHere.joinToString(" · ") { it.config.name.ifBlank { it.config.id } }
                },
                leadingIcon = Icons.Filled.Extension,
                onClick = { pickerOpen = true },
            )
            Spacer(Modifier.height(8.dp))
            // The two lines the user asked for: what the setting does, in the
            // order it happens.
            if (!LocalHideHelp.current) {
            Text(
                tr(
                    "Any title you play from another extension is also searched through the repos " +
                        "you pick here — even with the switch above off — and their servers join " +
                        "the same list."
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            }
            Spacer(Modifier.height(3.dp))
            if (!LocalHideHelp.current) {
            Text(
                tr(
                    "A title you open INSIDE one of those repos plays from that repo alone: it is " +
                        "never mixed with anything else."
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            tr("Applies from the next search you start."),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (pickerOpen) {
        val listed = if (engineFilter == null) installedEnabled
        else installedEnabled.filter { it.config.type.name == engineFilter }
        MultiChoiceDialog(
            title = tr("Exception extensions"),
            items = listed
                .sortedWith(
                    compareBy(
                        { it.config.name.ifBlank { it.config.id }.lowercase() },
                        { it.config.id },
                    )
                )
                .map {
                    val byEngine = it.config.type.name in exceptionTypes
                    val optedOut = it.config.id in exceptionExcludes
                    ChoiceItem(
                        key = it.config.id,
                        label = it.config.name.ifBlank { it.config.id },
                        supporting = buildString {
                            append(it.config.type.groupLabel)
                            if (byEngine) {
                                append(" · ")
                                append(if (optedOut) tr("left out of its engine") else tr("from its engine chip"))
                            }
                        },
                    )
                },
            // What is actually in force: a tick here means "a search will ask
            // this one", whether it got there by name or through its engine.
            selectedKeys = effectiveIds,
            onToggle = { id ->
                val type = installedEnabled.firstOrNull { it.config.id == id }?.config?.type
                val byEngine = type != null && type.name in exceptionTypes
                if (id in effectiveIds) {
                    // Switching it OFF: an engine covers it, so say no to THIS
                    // one instead of unmarking the whole engine.
                    if (byEngine) {
                        val next = exceptionExcludes + id
                        scope.launch { runCatching { app.store.setSearchExceptionExcludes(next) } }
                    } else {
                        val next = exceptionIds.toMutableSet().apply { remove(id) }
                        scope.launch { runCatching { app.store.setSearchExceptionIds(next) } }
                    }
                } else {
                    // Switching it ON: if its engine is marked that is already
                    // enough (drop the opt-out), otherwise name it explicitly.
                    val nextExcludes = exceptionExcludes - id
                    scope.launch {
                        runCatching { app.store.setSearchExceptionExcludes(nextExcludes) }
                        if (!byEngine) {
                            runCatching {
                                app.store.setSearchExceptionIds(exceptionIds + id)
                            }
                        }
                    }
                }
            },
            onDismiss = { pickerOpen = false },
            searchable = true,
            searchPlaceholder = "Search extensions",
            footnote = "Tap an engine to see only its extensions and mark all of them — then " +
                "tap whichever ones you do NOT want. They are always searched for titles you " +
                "open elsewhere, and never searched sideways when you are inside them.",
            headerContent = {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ExceptionEngineChip(
                        label = tr("All engines"),
                        selected = enginesHere.isNotEmpty() &&
                            enginesHere.all { it.name in exceptionTypes },
                        listed = engineFilter == null,
                        onClick = {
                            engineFilter = null
                            val all = enginesHere.map { it.name }.toSet()
                            val next = if (exceptionTypes.containsAll(all) && all.isNotEmpty()) {
                                exceptionTypes - all
                            } else {
                                exceptionTypes + all
                            }
                            scope.launch {
                                runCatching { app.store.setSearchExceptionTypes(next) }
                                // Marking an engine means "all of it": its old
                                // opt-outs go, and unmarking it drops them too.
                                runCatching { app.store.setSearchExceptionExcludes(emptySet()) }
                            }
                        },
                    )
                    enginesHere.forEach { engine ->
                        val on = engine.name in exceptionTypes
                        val listed = engineFilter == engine.name
                        ExceptionEngineChip(
                            label = engine.groupLabel,
                            selected = on,
                            listed = listed,
                            onClick = {
                                val marking = !listed || !on
                                engineFilter = if (listed) null else engine.name
                                val next = exceptionTypes.toMutableSet().apply {
                                    if (marking) add(engine.name) else remove(engine.name)
                                }
                                scope.launch {
                                    runCatching { app.store.setSearchExceptionTypes(next) }
                                    // A fresh mark covers the whole engine; an
                                    // unmark makes its opt-outs meaningless.
                                    val ids = installedEnabled
                                        .filter { it.config.type.name == engine.name }
                                        .map { it.config.id }
                                        .toSet()
                                    runCatching {
                                        app.store.setSearchExceptionExcludes(
                                            exceptionExcludes - ids
                                        )
                                    }
                                }
                            },
                        )
                    }
                }
            },
        )
    }

    val spe = serverPickerEngine
    if (spe != null) {
        val list = installedEnabled.filter { it.config.type == spe }.sortedBy { it.config.name.ifBlank { it.config.id }.lowercase() }
        val ids = list.map { it.config.id }.toSet()
        val on = when (spe) {
            ProviderType.NUVIO -> nuvioAll
            ProviderType.STREMIO -> stremioAll
            else -> spe.name in families
        }
        val selected = if (on) ids else when (spe) {
            ProviderType.NUVIO -> nuvioIds.intersect(ids)
            ProviderType.STREMIO -> stremioIds.intersect(ids)
            else -> familyIds.intersect(ids)
        }
        suspend fun setServerFamily(t: ProviderType, v: Boolean) {
            when (t) {
                ProviderType.NUVIO -> app.store.setNuvioSearchAll(v)
                ProviderType.STREMIO -> app.store.setStremioSearchAll(v)
                else -> app.store.setEngineFamily(t, v)
            }
        }
        MultiChoiceDialog(
            title = spe.groupLabel + " " + tr("servers"),
            items = list.map { ChoiceItem(key = it.config.id, label = it.config.name.ifBlank { it.config.id }, supporting = it.config.type.groupLabel) },
            selectedKeys = selected,
            onToggle = { id ->
                scope.launch {
                    val next = selected.toMutableSet()
                    if (!next.add(id)) next.remove(id)
                    if (next.size == ids.size && ids.isNotEmpty()) {
                        runCatching { setServerFamily(spe, true) }
                        when (spe) {
                            ProviderType.NUVIO -> runCatching { app.store.setSearchNuvioIds(nuvioIds - ids) }
                            ProviderType.STREMIO -> runCatching { app.store.setSearchStremioIds(stremioIds - ids) }
                            else -> runCatching { app.store.setSearchFamilyIds(familyIds - ids) }
                        }
                    } else {
                        runCatching { setServerFamily(spe, false) }
                        when (spe) {
                            ProviderType.NUVIO -> runCatching { app.store.setSearchNuvioIds((nuvioIds - ids) + next) }
                            ProviderType.STREMIO -> runCatching { app.store.setSearchStremioIds((stremioIds - ids) + next) }
                            else -> runCatching { app.store.setSearchFamilyIds((familyIds - ids) + next) }
                        }
                    }
                }
            },
            onDismiss = { serverPickerEngine = null },
            searchable = true,
            searchPlaceholder = tr("Search %s extensions").replace("%s", spe.groupLabel),
            footnote = tr("Scrollable list — tap again to unselect. Only the extensions you keep selected are searched when the family switch above is off."),
        )
    }
}

/** One engine pill in the exception picker (see [ServerSearchCard]). */
@Composable
private fun ServerTimeoutCard(app: HikariApp) {
    val scope = rememberCoroutineScope()
    val allSecs by remember { app.store.serverTimeoutAllFlow() }.collectAsState(initial = 0)
    val overrides by remember { app.store.serverTimeoutsFlow() }.collectAsState(initial = emptyMap())
    val installed by app.providers.providers.collectAsState()
    val installedEnabled = installed.filter { it.config.enabled }
    val enginesHere = remember(installedEnabled) {
        installedEnabled.map { it.config.type }.distinct().sortedBy { it.groupLabel }
    }
    var openEngines by remember { mutableStateOf(setOf<String>()) }
    val masterOn = allSecs in 20..100

    Column(Modifier.padding(16.dp)) {
        SettingsCardHeading(Icons.Filled.Timer, tr("Server search time"))
        SettingsToggle(
            label = tr("Same search time for every extension"),
            supporting = if (masterOn) {
                tr("Every extension waits up to %s seconds for servers").replace("%s", allSecs.toString())
            } else {
                tr("Set the time per extension below")
            },
            checked = masterOn,
            onCheckedChange = { on ->
                scope.launch { runCatching { app.store.setServerTimeoutAll(if (on) 40 else 0) } }
            },
        )
        if (masterOn) {
            Spacer(Modifier.height(8.dp))
            TimeoutSecondSlider(
                seconds = allSecs,
                onCommit = { v -> scope.launch { runCatching { app.store.setServerTimeoutAll(v) } } },
            )
        }
        if (!LocalHideHelp.current) {
            Spacer(Modifier.height(8.dp))
            Text(
                tr(
                    "Slow extensions (torrents first) need more than the built-in wait. " +
                        "Raising the time only waits longer — a server that fails outright " +
                        "still reports immediately instead of burning the whole time."
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(12.dp))
        if (installedEnabled.isEmpty()) {
            Text(
                tr("No extensions installed yet."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        for (type in enginesHere) {
            val exts = installedEnabled.filter { it.config.type == type }
                .sortedBy { it.config.name.ifBlank { it.config.id }.lowercase() }
            val custom = exts.count { it.config.id in overrides }
            val open = type.name in openEngines
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        type.groupLabel,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        exts.size.toString() + " " + tr("extensions") +
                            (if (custom > 0) " · " + custom.toString() + " " + tr("custom") else ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = {
                    openEngines = if (open) openEngines - type.name else openEngines + type.name
                }) {
                    Text(if (open) tr("Hide") else tr("Show"))
                }
            }
            if (open) {
                for (holder in exts) {
                    val id = holder.config.id
                    val label = holder.config.name.ifBlank { id }
                    val current = overrides[id]
                    Column(Modifier.fillMaxWidth().padding(start = 8.dp, top = 4.dp, bottom = 4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                label,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (current != null) {
                                TextButton(onClick = {
                                    scope.launch { runCatching { app.store.setServerTimeout(id, null) } }
                                }) {
                                    Text(tr("Default"))
                                }
                            }
                        }
                        TimeoutSecondSlider(
                            seconds = current ?: 20,
                            onCommit = { v -> scope.launch { runCatching { app.store.setServerTimeout(id, v) } } },
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

/** 20–100 seconds in one-second steps; writes only when the drag settles. */
@Composable
private fun TimeoutSecondSlider(seconds: Int, onCommit: (Int) -> Unit) {
    var pos by remember(seconds) { mutableStateOf(seconds.coerceIn(20, 100).toFloat()) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Slider(
            value = pos,
            onValueChange = { pos = it },
            onValueChangeFinished = { onCommit(pos.roundToInt().coerceIn(20, 100)) },
            valueRange = 20f..100f,
            steps = 79,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            pos.roundToInt().toString() + "s",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExceptionEngineChip(
    label: String,
    selected: Boolean,
    /** True when the list is currently narrowed to this engine — shown as a
     *  bolder label, since [selected] already means "marked as an exception"
     *  and the two are different things. */
    listed: Boolean = false,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(label, fontWeight = if (listed) FontWeight.SemiBold else FontWeight.Normal)
        },
        shape = RoundedCornerShape(50),
        colors = FilterChipDefaults.filterChipColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            selectedLabelColor = MaterialTheme.colorScheme.primary,
        ),
    )
}

@Composable
private fun LoadingBannerCard(app: HikariApp) {    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(true) }
    val styleFlow = remember { app.store.loadingStyleFlow() }
    val style by styleFlow.collectAsState(initial = LoadingStyles.POSTER)
    val effectsFlow = remember { app.store.loadingEffectsFlow() }
        // No treatment until the store answers (the default is NONE — see
        // AppStore.DEFAULT_LOADING_EFFECTS), so the switch never flashes on.
        val effects by effectsFlow.collectAsState(initial = emptySet<String>())
    // The title wordmark on the cover: whether it is drawn at all, and its own
    // size (kept apart from the detail header's — see [AppStore.loadingLogoSizeFlow]).
    // The switch is held locally and seeded from the store, the same pattern as
    // [enabled] above; the size is a plain read.
    var logoOn by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { logoOn = app.store.loadingLogo() }
    val logoSizeFlow = remember { app.store.loadingLogoSizeFlow() }
    val logoSize by logoSizeFlow.collectAsState(initial = 100)
    var logoSlider by remember { mutableStateOf(logoSize.toFloat()) }
    LaunchedEffect(logoSize) { logoSlider = logoSize.toFloat() }
    val auraFlow = remember { app.store.loadingAuraColorFlow() }
    val auraColor by auraFlow.collectAsState(initial = AuraColors.THEME)
    var pickerOpen by remember { mutableStateOf(false) }
    var effectPickerOpen by remember { mutableStateOf(false) }
    // Translated one treatment at a time — a combination read back as one
    // English sentence could not be translated (see PosterStyleCard).
    val effectNames = LoadingEffects.ALL
        .filter { it != LoadingEffects.NONE && it in effects }
        .map { tr(LoadingEffects.label(it)) }

    LaunchedEffect(Unit) {
        enabled = app.store.showLoadingBanner()
    }

    Column(Modifier.padding(16.dp)) {
        SettingsCardHeading(Icons.Filled.Slideshow, tr("Loading screen"))
        SettingsToggle(
            label = tr("Title artwork while loading"),
            supporting = if (enabled) tr("Artwork and name until video starts")
            else tr("A plain loading icon instead"),
            checked = enabled,
            onCheckedChange = {
                enabled = it
                scope.launch { runCatching { app.store.setShowLoadingBanner(it) } }
            },
        )
        if (enabled) {
            Spacer(Modifier.height(10.dp))
            ChoiceRow(
                value = tr(LoadingStyles.label(style)),
                supporting = tr(LoadingStyles.description(style)),
                leadingIcon = Icons.Filled.Slideshow,
                onClick = { pickerOpen = true },
            )
            Spacer(Modifier.height(10.dp))
            ChoiceRow(
                value = if (effectNames.isEmpty()) {
                    tr(LoadingEffects.label(LoadingEffects.NONE))
                } else {
                    effectNames.joinToString(" + ")
                },
                supporting = if (effectNames.isEmpty()) {
                    tr(LoadingEffects.description(LoadingEffects.NONE))
                } else if (effectNames.size == 1) {
                    tr(LoadingEffects.description(effects.first()))
                } else {
                    tr(LoadingEffects.description(effects.first())) + " · +" +
                        (effectNames.size - 1).toString() + " " + tr("more")
                },
                leadingIcon = Icons.Filled.AutoAwesome,
                onClick = { effectPickerOpen = true },
            )
            // The ring's colour, offered only while the ring is on.
            if (LoadingEffects.AURA in effects) {
                AuraColorRow(
                    selected = auraColor,
                    onPick = { key -> scope.launch { runCatching { app.store.setLoadingAuraColor(key) } } },
                )
            }
            Spacer(Modifier.height(10.dp))
            SettingsToggle(
                label = tr("Title logo"),
                supporting = if (logoOn) {
                    tr("Draw the title's own wordmark here, like the detail header")
                } else {
                    tr("Draw the title as plain text")
                },
                checked = logoOn,
                onCheckedChange = {
                    logoOn = it
                    scope.launch { runCatching { app.store.setLoadingLogo(it) } }
                },
            )
            if (logoOn) {
                Spacer(Modifier.height(10.dp))
                SettingsSlider(
                    label = tr("Title logo size"),
                    value = logoSlider,
                    valueText = logoSlider.roundToInt().toString() + "%",
                    valueRange = 50f..160f,
                    steps = 109,
                    onValueChange = { v -> logoSlider = v.roundToInt().toFloat().coerceIn(50f, 160f) },
                    onValueChangeFinished = {
                        val pct = logoSlider.roundToInt().coerceIn(50, 160)
                        logoSlider = pct.toFloat()
                        scope.launch { runCatching { app.store.setLoadingLogoSize(pct) } }
                    },
                )
                if (!LocalHideHelp.current) {
                Text(
                    tr("How big the wordmark is drawn on the loading cover, as a percentage of its default size"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                tr("Applies to the next video you open."),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (pickerOpen) {
        ChoiceDialog(
            title = tr("Loading screen"),
            items = LoadingStyles.ALL.map {
                ChoiceItem(it, tr(LoadingStyles.label(it)), tr(LoadingStyles.description(it)))
            },
            selectedKey = style,
            onPick = { pick ->
                pickerOpen = false
                scope.launch { runCatching { app.store.setLoadingStyle(pick) } }
            },
            onDismiss = { pickerOpen = false },
        )
    }

    if (effectPickerOpen) {
        MultiChoiceDialog(
            title = tr("Loading effects"),
            items = LoadingEffects.ALL.map {
                ChoiceItem(it, tr(LoadingEffects.label(it)), tr(LoadingEffects.description(it)))
            },
            selectedKeys = effects,
            onToggle = { pick ->
                val next: Set<String> = if (pick == LoadingEffects.NONE) {
                    emptySet()
                } else {
                    effects.toMutableSet().apply { if (!add(pick)) remove(pick) }
                }
                scope.launch { runCatching { app.store.setLoadingEffects(next) } }
            },
            onDismiss = { effectPickerOpen = false },
            footnote = "Tick as many as you like — a sheen AND an aura ring is one choice, " +
                "and \"None\" clears them all.",
        )
    }
}


@Composable
private fun PerformanceBoosterCard(app: HikariApp) {
    val scope = rememberCoroutineScope()
    val flow = remember { app.store.perfModeFlow() }
    val on by flow.collectAsState(initial = false)
    val tvFlow = remember { app.store.tvPerfFlow() }
    val tvOn by tvFlow.collectAsState(initial = false)

    Column(Modifier.padding(16.dp)) {
        SettingsCardHeading(Icons.Filled.Speed, tr("Performance"))
        SettingsToggle(
            label = tr("Performance booster"),
            supporting = if (on) {
                tr("Heavy visual work is off and searches run in smaller batches")
            } else {
                tr("Turn this on if the app lags, stutters or heats up")
            },
            checked = on,
            onCheckedChange = {
                scope.launch { runCatching { app.store.setPerfMode(it) } }
            },
        )
        Spacer(Modifier.height(10.dp))
        if (!LocalHideHelp.current) {
        Text(
            tr(
                "Off, Hikari draws and searches as it always has. On, it drops the " +
                    "things that cost the most frames per second — the blurred glow " +
                    "behind every poster, the animated cards, and how many extensions " +
                    "are asked for servers at the same time. The same servers are " +
                    "still found, just a few at a time, and nothing you can watch is " +
                    "removed."
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            if (tvOn) {
                tr("Television performance mode is also on (Settings → TV & Remote).")
            } else {
                tr("On a television the same thing is switched on automatically — see Settings → TV & Remote.")
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun AppLockCard(app: HikariApp) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lockFlow = remember { app.store.appLockFlow() }
    val on by lockFlow.collectAsState(initial = false)
    val secretFlow = remember { app.store.appLockSecretFlow() }
    val secret by secretFlow.collectAsState(initial = "")
    val lenFlow = remember { app.store.appLockLenFlow() }
    val secretLen by lenFlow.collectAsState(initial = 0)
    val bioFlow = remember { app.store.appLockBioFlow() }
    val bioOn by bioFlow.collectAsState(initial = true)
    // The lock's extra rules: whether a screen-off locks, whether leaving the
    // app locks, and the grace period after either (0 = Instant).
    val screenOffFlow = remember { app.store.appLockScreenOffFlow() }
    val screenOffLocks by screenOffFlow.collectAsState(initial = true)
    val leaveFlow = remember { app.store.appLockLeaveFlow() }
    val leaveLocks by leaveFlow.collectAsState(initial = true)
    val delayFlow = remember { app.store.appLockDelayFlow() }
    val storedDelay by delayFlow.collectAsState(initial = 0)
    var delay by remember { mutableStateOf(0) }
    LaunchedEffect(storedDelay) { delay = storedDelay }
    val hasSecret = AppLock.isSet(secret)
    val biometrics = remember(context) { Biometrics.available(context) }
    var setDialog by remember { mutableStateOf(false) }
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    var current by remember { mutableStateOf("") }
    var oldWrong by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    val tooShort = first.isNotEmpty() && first.length < 4
    val mismatch = second.isNotEmpty() && first != second
    val canSave = first.length >= 4 && first == second &&
        (!hasSecret || current.isNotBlank())

    Column(Modifier.padding(16.dp)) {
        SettingsCardHeading(Icons.Filled.Lock, tr("App lock"))
        SettingsToggle(
            label = tr("Ask for a password when Hikari opens"),
            supporting = if (on) tr("On — the app opens locked") else tr("Off"),
            checked = on,
            onCheckedChange = { want ->
                if (want && !hasSecret) {
                    first = ""
                    second = ""
                    current = ""
                    oldWrong = false
                    removing = false
                    setDialog = true
                } else {
                    scope.launch { runCatching { app.store.setAppLock(want) } }
                }
            },
        )
        if (hasSecret) {
            SettingsToggle(
                label = tr("Unlock with fingerprint"),
                supporting = if (biometrics) {
                    tr("Use the fingerprint or face this device already has")
                } else {
                    tr("No fingerprint or face is set up on this device")
                },
                checked = bioOn && biometrics,
                onCheckedChange = { scope.launch { runCatching { app.store.setAppLockBio(it) } } },
            )
            SettingsToggle(
                label = tr("Lock when the screen turns off"),
                supporting = if (screenOffLocks) {
                    tr("A screen-off locks it too, like leaving the app")
                } else {
                    tr("Only leaving the app locks it")
                },
                checked = screenOffLocks,
                onCheckedChange = {
                    scope.launch { runCatching { app.store.setAppLockScreenOff(it) } }
                },
            )
            SettingsToggle(
                label = tr("Lock when I leave the app"),
                supporting = if (leaveLocks) {
                    tr("Switching to another app draws the lock again")
                } else {
                    tr("Leaving the app does not lock it — only a screen-off does")
                },
                checked = leaveLocks,
                onCheckedChange = {
                    scope.launch { runCatching { app.store.setAppLockLeave(it) } }
                },
            )
            // The grace period applies to whichever triggers are on, so it is
            // only offered while at least one of them is.
            if (leaveLocks || screenOffLocks) {
                Spacer(Modifier.height(8.dp))
                SettingsSlider(
                    label = tr("Lock after leaving"),
                    value = delay.toFloat(),
                    valueText = if (delay <= 0) tr("Instantly") else delay.toString() + " " + tr("min"),
                    valueRange = 0f..60f,
                    steps = 59,
                    onValueChange = { v -> delay = v.roundToInt().coerceIn(0, 60) },
                    onValueChangeFinished = {
                        scope.launch { runCatching { app.store.setAppLockDelay(delay) } }
                    },
                )
                if (!LocalHideHelp.current) {
                Text(
                    tr(
                        "How long the app may stay unlocked after you leave it. \"Instantly\" locks " +
                            "the moment you leave the app or the screen turns off."
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    first = ""
                    second = ""
                    current = ""
                    oldWrong = false
                    removing = false
                    setDialog = true
                }) {
                    Text(tr("Change password"))
                }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = {
                    // Removing the lock asks for the current password too — the
                    // same proof changing it needs, for the same reason.
                    current = ""
                    oldWrong = false
                    removing = true
                    setDialog = true
                }) {
                    Text(tr("Remove password"))
                }
            }
        } else if (on) {
            Spacer(Modifier.height(6.dp))
            Text(
                tr(
                    "The saved password cannot be read — it was written by an older version " +
                        "of Hikari. Set a new one; the app lock stays on but nothing can " +
                        "unlock it until you do."
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = {
                first = ""
                second = ""
                current = ""
                oldWrong = false
                removing = false
                setDialog = true
            }) {
                Text(tr("Set a new password"))
            }
        }
        Spacer(Modifier.height(8.dp))
        if (!LocalHideHelp.current) {
        Text(
            tr(
                "The password is always required — the fingerprint is only a " +
                    "quicker way in. It cannot be read back, so write it down: the " +
                    "unlock screen can turn a forgotten lock off, but nothing can " +
                    "tell you the old password."
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        }
    }

    if (setDialog) {
        AlertDialog(
            onDismissRequest = { setDialog = false },
            title = {
                Text(
                    when {
                        removing -> tr("Remove the app lock")
                        hasSecret -> tr("Change password")
                        else -> tr("Set a password")
                    }
                )
            },
            text = {
                Column {
                    if (hasSecret) {
                        OutlinedTextField(
                            value = current,
                            onValueChange = {
                                current = it
                                oldWrong = false
                            },
                            label = { Text(tr("Current password")) },
                            singleLine = true,
                            isError = oldWrong,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth().tvTextFieldKeys(current),
                        )
                        if (oldWrong) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                tr("That is not the current password"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        if (secretLen > 0 && !oldWrong) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                tr("Your current password is ") + secretLen + " " +
                                    (if (secretLen == 4) tr("digits") else tr("characters")),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (!removing) Spacer(Modifier.height(10.dp))
                    }
                    if (!removing) {
                        OutlinedTextField(
                            value = first,
                            onValueChange = { first = it },
                            label = { Text(tr("New password")) },
                            singleLine = true,
                            isError = tooShort,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth().tvTextFieldKeys(first),
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = second,
                            onValueChange = { second = it },
                            label = { Text(tr("Repeat password")) },
                            singleLine = true,
                            isError = mismatch,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth().tvTextFieldKeys(second),
                        )
                        if (tooShort) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                tr("Use at least 4 characters"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        if (mismatch) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                tr("The two passwords are not the same"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        if (!LocalHideHelp.current) {
                        Text(
                            tr(
                                "A PIN works here too — the unlock screen has a keypad for it, " +
                                    "and it will show as many dots as there are characters. It " +
                                    "cannot be read back: if it is ever forgotten, the unlock " +
                                    "screen's \"Forgot password?\" turns the lock off."
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        }
                    } else {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            tr("The password is removed and the app stops asking for it."),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = if (removing) current.isNotBlank() else canSave,
                    onClick = {
                        val wanted = first
                        scope.launch {
                            withContext(Dispatchers.Default) {
                                if (hasSecret && !AppLock.verify(current, secret)) {
                                    oldWrong = true
                                    return@withContext
                                }
                                if (removing) {
                                    app.store.setAppLock(false)
                                    app.store.setAppLockSecret("")
                                    app.store.setAppLockLen(0)
                                } else {
                                    app.store.setAppLockSecret(AppLock.encode(wanted))
                                    app.store.setAppLockLen(wanted.length)
                                    app.store.setAppLock(true)
                                    app.store.setAppLockBio(biometrics)
                                }
                                setDialog = false
                            }
                        }
                    },
                ) {
                    Text(if (removing) tr("Remove") else tr("Save"))
                }
            },
            dismissButton = {
                TextButton(onClick = { setDialog = false }) { Text(tr("Cancel")) }
            },
        )
    }
}

@Composable
private fun MyStuffLockCard(app: HikariApp) {
    val scope = rememberCoroutineScope()
    val lockFlow = remember { app.store.myStuffLockFlow() }
    val on by lockFlow.collectAsState(initial = false)
    val secretFlow = remember { app.store.myStuffLockSecretFlow() }
    val secret by secretFlow.collectAsState(initial = "")
    val hasSecret = AppLock.isSet(secret)
    var setDialog by remember { mutableStateOf(false) }
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    var current by remember { mutableStateOf("") }
    var oldWrong by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    val tooShort = first.isNotEmpty() && first.length < 4
    val mismatch = second.isNotEmpty() && first != second
    val canSave = first.length >= 4 && first == second &&
        (!hasSecret || current.isNotBlank())

    Column(Modifier.padding(16.dp)) {
        SettingsCardHeading(Icons.Filled.Lock, tr("My Stuff lock"))
        SettingsToggle(
            label = tr("Ask for a password when opening My Stuff"),
            supporting = if (on) tr("On — the tab opens locked") else tr("Off"),
            checked = on,
            onCheckedChange = { want ->
                if (want && !hasSecret) {
                    first = ""
                    second = ""
                    current = ""
                    oldWrong = false
                    removing = false
                    setDialog = true
                } else {
                    scope.launch { runCatching { app.store.setMyStuffLock(want) } }
                }
            },
        )
        if (hasSecret) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = {
                    first = ""
                    second = ""
                    current = ""
                    oldWrong = false
                    removing = false
                    setDialog = true
                }) { Text(tr("Change password")) }
                TextButton(onClick = {
                    first = ""
                    second = ""
                    current = ""
                    oldWrong = false
                    removing = true
                    setDialog = true
                }) { Text(tr("Remove")) }
            }
        }
        Spacer(Modifier.height(8.dp))
        if (!LocalHideHelp.current) {
        Text(
            tr(
                "A different password from the app lock, for the library, history " +
                    "and downloads only. It is asked once per app launch, it cannot " +
                    "be read back, and it never leaves this device in a backup or " +
                    "a pairing."
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        }
    }

    if (setDialog) {
        AlertDialog(
            onDismissRequest = { setDialog = false },
            title = {
                Text(
                    when {
                        removing -> tr("Remove the My Stuff lock")
                        hasSecret -> tr("Change password")
                        else -> tr("Set a password")
                    }
                )
            },
            text = {
                Column {
                    if (hasSecret) {
                        OutlinedTextField(
                            value = current,
                            onValueChange = {
                                current = it
                                oldWrong = false
                            },
                            label = { Text(tr("Current password")) },
                            singleLine = true,
                            isError = oldWrong,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth().tvTextFieldKeys(current),
                        )
                        if (oldWrong) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                tr("That is not the current password"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        if (!removing) Spacer(Modifier.height(10.dp))
                    }
                    if (!removing) {
                        OutlinedTextField(
                            value = first,
                            onValueChange = { first = it },
                            label = { Text(tr("New password")) },
                            singleLine = true,
                            isError = tooShort,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth().tvTextFieldKeys(first),
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = second,
                            onValueChange = { second = it },
                            label = { Text(tr("Repeat password")) },
                            singleLine = true,
                            isError = mismatch,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth().tvTextFieldKeys(second),
                        )
                        if (tooShort) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                tr("Use at least 4 characters"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        if (mismatch) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                tr("The two passwords are not the same"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    } else {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            tr("The password is removed and My Stuff stops asking for it."),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = if (removing) current.isNotBlank() else canSave,
                    onClick = {
                        val wanted = first
                        scope.launch {
                            withContext(Dispatchers.Default) {
                                if (hasSecret && !AppLock.verify(current, secret)) {
                                    oldWrong = true
                                    return@withContext
                                }
                                if (removing) {
                                    app.store.setMyStuffLock(false)
                                    app.store.setMyStuffLockSecret("")
                                } else {
                                    app.store.setMyStuffLockSecret(AppLock.encode(wanted))
                                    app.store.setMyStuffLock(true)
                                }
                                setDialog = false
                            }
                        }
                    },
                ) {
                    Text(if (removing) tr("Remove") else tr("Save"))
                }
            },
            dismissButton = {
                TextButton(onClick = { setDialog = false }) { Text(tr("Cancel")) }
            },
        )
    }
}

@Composable
private fun TrackerServiceCard(
    kind: TrackerKind,
    account: com.hikari.app.data.TrackerAccount?,
    client: TrackerClient,
    working: Boolean,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
) {
    var expanded by rememberSaveable(kind.key) { mutableStateOf(false) }
    val connected = account != null
    val gradient = when (kind) {
        TrackerKind.TRAKT -> Brush.linearGradient(listOf(Color(0xFF76279A), Color(0xFFE31E4F), Color(0xFFF32328)))
        TrackerKind.SIMKL -> Brush.linearGradient(listOf(Color(0xFF111111), Color(0xFF2A2A2A), Color(0xFF0D0D0D)))
        TrackerKind.MAL -> Brush.linearGradient(listOf(Color(0xFF173D69), Color(0xFF2D65A0)))
        else -> Brush.linearGradient(listOf(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.surface))
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp))
            .background(gradient)
            .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(26.dp))
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }
                .padding(horizontal = 18.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(kind.label, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Color.White)
                Text(
                    when {
                        connected -> tr("Connected") + " · " + account?.user.orEmpty().ifBlank { tr("your account") }
                        client.ready -> tr("Ready to connect")
                        else -> tr("Not connected")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.82f),
                )
            }
            Icon(
                Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.86f),
                modifier = Modifier.rotate(if (expanded) 180f else 0f).size(28.dp),
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(
                Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    if (connected) tr("This account can receive Hikari watch updates.") else kind.blurb,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.78f),
                )
                if (!connected) {
                    OutlinedButton(
                        onClick = onSignIn,
                        enabled = !working,
                        modifier = Modifier.fillMaxWidth(),
                        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                            contentColor = Color.White,
                            disabledContentColor = Color.White.copy(alpha = 0.45f),
                        ),
                    ) { Text(tr("Connect")) }
                } else {
                    OutlinedButton(
                        onClick = onSignOut,
                        enabled = !working,
                        modifier = Modifier.fillMaxWidth(),
                        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                            contentColor = Color.White,
                            disabledContentColor = Color.White.copy(alpha = 0.45f),
                        ),
                    ) { Text(tr("Disconnect")) }
                }
            }
        }
    }
}

@Composable
private fun AnimeMetadataIntegrationCard(app: HikariApp) {
    val scope = rememberCoroutineScope()
    val simkl by remember { app.store.animeSimklFlow() }.collectAsState(initial = true)
    val anilist by remember { app.store.animeAnilistFlow() }.collectAsState(initial = true)
    val animeTmdb by remember { app.store.animeTmdbFlow() }.collectAsState(initial = true)
    val videoTmdb by remember { app.store.videoTmdbFlow() }.collectAsState(initial = true)
    val savedSimklId by remember { app.store.animeSimklAppIdFlow() }.collectAsState(initial = "")
    var draftSimklId by remember { mutableStateOf("") }
    LaunchedEffect(savedSimklId) { draftSimklId = savedSimklId }
    val context = LocalContext.current
    val mangaAnilist by remember { app.store.mangaAnilistFlow() }.collectAsState(initial = true)
    val adultManhwa by remember { app.store.adultManhwaFlow() }.collectAsState(initial = false)
    val savedPwdbKey by remember { app.store.pornhwaApiKeyFlow() }.collectAsState(initial = "")
    var draftPwdbKey by remember { mutableStateOf("") }
    LaunchedEffect(savedPwdbKey) { draftPwdbKey = savedPwdbKey }
    var checkingPwdb by remember { mutableStateOf(false) }
    val native by remember { app.store.nativeProvidersFlow() }.collectAsState(initial = emptySet())
    val installed by app.providers.providers.collectAsState()
    val videoProviders = remember(installed) {
        installed.filter { it.config.enabled && it.config.type.isFamilyEngine }
            .sortedBy { it.config.name.ifBlank { it.config.id }.lowercase() }
    }
    var nativeQuery by remember { mutableStateOf("") }
    var modulesOpen by remember { mutableStateOf(false) }
    val shownProviders = remember(videoProviders, nativeQuery) {
        val q = nativeQuery.trim().lowercase()
        if (q.isBlank()) videoProviders
        else videoProviders.filter { p ->
            p.config.name.ifBlank { p.config.id }.lowercase().contains(q) ||
                p.config.id.lowercase().contains(q) ||
                p.config.type.groupLabel.lowercase().contains(q)
        }
    }

    Column(Modifier.padding(16.dp)) {
        SettingsCardHeading(Icons.Filled.AutoAwesome, tr("Metadata sources"))
        if (!LocalHideHelp.current) Text(
            tr("Anime metadata merges Simkl, then AniList, then TMDB — the first hit wins per field. No logins needed; everything enabled here fetches automatically."),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        Text(tr("Anime"), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        SettingsToggle(
            label = tr("Simkl"),
            supporting = tr("Anime titles, posters and episode details from Simkl"),
            checked = simkl,
            onCheckedChange = { on -> scope.launch { runCatching { app.store.setAnimeSimkl(on) } } },
        )
        SettingsToggle(
            label = tr("AniList"),
            supporting = tr("Anime titles, posters and episode details from AniList"),
            checked = anilist,
            onCheckedChange = { on -> scope.launch { runCatching { app.store.setAnimeAnilist(on) } } },
        )
        SettingsToggle(
            label = tr("TMDB"),
            supporting = tr("Anime titles, posters and episode details from TMDB"),
            checked = animeTmdb,
            onCheckedChange = { on -> scope.launch { runCatching { app.store.setAnimeTmdb(on) } } },
        )
        if (simkl) {
            Spacer(Modifier.height(10.dp))
            Text(
                tr("Simkl app ID"),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                tr("Optional. A connected Simkl tracker login already supplies one; a free app ID from the Simkl API portal works without any login."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
            )
            ApiKeyEditor(
                label = "Simkl app ID",
                value = draftSimklId,
                savedValue = savedSimklId,
                onValueChange = { draftSimklId = it },
                onSave = {
                    scope.launch { runCatching { app.store.setAnimeSimklAppId(draftSimklId.trim()) } }
                },
            )
        }
        Spacer(Modifier.height(14.dp))
        Text(tr("Movies & series"), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        SettingsToggle(
            label = tr("TMDB"),
            supporting = tr("Posters, details and episode names for movies and series"),
            checked = videoTmdb,
            onCheckedChange = { on -> scope.launch { runCatching { app.store.setVideoTmdb(on) } } },
        )
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = { modulesOpen = true }, shape = GlassShape) {
            Text(tr("TMDB modules"))
        }
        if (modulesOpen) TmdbModulesDialog(app, onDismiss = { modulesOpen = false })
        Spacer(Modifier.height(14.dp))
        Text(tr("Manga"), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        SettingsToggle(
            label = tr("AniList"),
            supporting = tr("Manhwa, manhua and manga blurbs, genres and covers from AniList"),
            checked = mangaAnilist,
            onCheckedChange = { on -> scope.launch { runCatching { app.store.setMangaAnilist(on) } } },
        )
        SettingsToggle(
            label = tr("Adult manhwa (PornHwaDB)"),
            supporting = tr("Adult titles enriched from PornHwaDB — needs your free API key below"),
            checked = adultManhwa,
            onCheckedChange = { on -> scope.launch { runCatching { app.store.setAdultManhwa(on) } } },
        )
        if (adultManhwa) {
            Text(
                tr("Get your free key from pornhwadb.com — Profile, Settings, Developer tab — then paste it below."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
            )
            TextButton(
                onClick = {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(MangaMetadataRepository.GET_KEY_URL)))
                    }
                },
            ) {
                Icon(Icons.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(tr("Get a key from PornHwaDB"))
            }
            ApiKeyEditor(
                label = "PornHwaDB API key",
                value = draftPwdbKey,
                savedValue = savedPwdbKey,
                onValueChange = { draftPwdbKey = it },
                onSave = {
                    scope.launch {
                        val key = draftPwdbKey.trim()
                        runCatching { app.store.setPornhwaApiKey(key) }
                        if (key.isEmpty()) return@launch
                        checkingPwdb = true
                        val code = runCatching { MangaMetadataRepository.checkApiKey(key) }.getOrDefault(-1)
                        checkingPwdb = false
                        val msg = when (code) {
                            200 -> "PornHwaDB key saved — adult manhwa metadata is on"
                            401 -> "That key was rejected (401) — check it on pornhwadb.com"
                            else -> "Key saved, but PornHwaDB did not answer — it will retry when a title opens"
                        }
                        Toast.makeText(context, I18n.t(msg), Toast.LENGTH_LONG).show()
                    }
                },
            )
            if (checkingPwdb) {
                Text(
                    tr("Checking key..."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(tr("Extension metadata"), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        if (!LocalHideHelp.current) Text(
            tr("Sources whose own metadata is better stay native: their titles, posters and episode names are used as-is, with no external enrichment."),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (videoProviders.isEmpty()) {
            Text(
                tr("Install a video source to choose native metadata per source."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        } else {
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = nativeQuery,
                onValueChange = { nativeQuery = it },
                placeholder = { Text(tr("Search sources…")) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().tvTextFieldKeys(nativeQuery),
            )
        }
        if (shownProviders.isEmpty() && videoProviders.isNotEmpty()) {
            Text(
                tr("No sources match that search."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        for (p in shownProviders) {
            val id = p.config.id
            SettingsToggle(
                label = p.config.name.ifBlank { id },
                supporting = p.config.type.groupLabel,
                checked = id in native,
                onCheckedChange = { on ->
                    scope.launch {
                        runCatching {
                            app.store.setNativeProviders(if (on) native + id else native - id)
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun TrackersCard(app: HikariApp) {
    val scope = rememberCoroutineScope()
    val accountsFlow = remember { app.store.trackersFlow() }
    val accounts by accountsFlow.collectAsState(initial = emptyList())
    val clientsFlow = remember { app.store.trackerClientsFlow() }
    val clients by clientsFlow.collectAsState(initial = emptyList())
    val syncFlow = remember { app.store.trackerSyncFlow() }
    val sync by syncFlow.collectAsState(initial = true)
    val lastFlow = remember { app.store.trackerLastFlow() }
    val last by lastFlow.collectAsState(initial = "")
    var loginFor by remember { mutableStateOf<TrackerKind?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    // Read here, in the composable body: the handlers below are plain lambdas and
    // `tr` is itself composable, so it cannot be called from one.
    val signedOutSuffix = tr(" signed out")
    val yourAccount = tr("your account")

    Column(Modifier.padding(16.dp)) {
        SettingsCardHeading(Icons.Filled.Sync, tr("Trackers"))
        Spacer(Modifier.height(10.dp))
        val librarySource by app.store.trackerLibrarySourceFlow().collectAsState(initial="nuvio")
        var libraryPicker by remember { mutableStateOf(false) }
        val libraryLabels=mapOf("nuvio" to tr("Hikari Library"),"anilist" to tr("AniList"),"mal" to tr("MyAnimeList"),"kitsu" to tr("Kitsu"),"simkl" to tr("Simkl"),"shikimori" to tr("Shikimori"),"trakt" to tr("Trakt"))
        Text(tr("Library source"),style=MaterialTheme.typography.titleSmall,color=MaterialTheme.colorScheme.primary)
        Text(tr("Load watchlists and personal/status lists from the connected tracker directly into Hikari."),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        ChoiceRow(value=libraryLabels[librarySource] ?: librarySource,leadingIcon=Icons.Filled.FolderOpen,onClick={libraryPicker=true})
        if(libraryPicker) ChoiceDialog(title=tr("Choose library source"),items=libraryLabels.map{(key,label)->ChoiceItem(key,label,if(key=="nuvio") tr("Use Hikari's saved library") else tr("Connect this tracker first"))},selectedKey=librarySource,onPick={scope.launch{app.store.setTrackerLibrarySource(it)}},onDismiss={libraryPicker=false})
        Spacer(Modifier.height(14.dp))

        for (kind in TrackerKind.entries) {
            val account = accounts.firstOrNull { it.kind == kind }
            val client = clients.firstOrNull { it.kind == kind } ?: TrackerClient(kind)
            TrackerServiceCard(
                kind = kind,
                account = account,
                client = client,
                working = working,
                onSignIn = { message = null; loginFor = kind },
                onSignOut = {
                    scope.launch {
                        runCatching { app.store.removeTrackerAccount(kind) }
                        message = kind.label + signedOutSuffix
                    }
                },
            )
            Spacer(Modifier.height(10.dp))
        }

        HorizontalDivider(
            modifier = Modifier.padding(top = 12.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        SettingsToggle(
            label = tr("Report what I watch"),
            supporting = tr("Only after an episode is watched through"),
            checked = sync,
            onCheckedChange = { on ->
                scope.launch { runCatching { app.store.setTrackerSync(on) } }
            },
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                enabled = !working && accounts.isNotEmpty(),
                onClick = {
                    working = true
                    message = null
                    scope.launch {
                        message = runCatching { TrackerSync.syncHistory(app.store) }
                            .getOrElse { it.message ?: "the sync failed" }
                        working = false
                    }
                },
            ) {
                if (working) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(tr("Syncing…"))
                } else {
                    Text(tr("Sync watch history"))
                }
            }
            Spacer(Modifier.width(8.dp))
            TextButton(
                enabled = !working && accounts.isNotEmpty(),
                onClick = {
                    working = true
                    message = null
                    scope.launch {
                        val lines = runCatching { TrackerSync.ping(app.store) }
                            .getOrElse { listOf(it.message ?: "the check failed") }
                        message = lines.joinToString("\n")
                        working = false
                    }
                },
            ) { Text(tr("Test connections")) }
        }
        if (!LocalHideHelp.current) {
        Text(
            tr(
                "The last 12 watched titles are checked, newest first — a title nobody has " +
                    "matched confidently is reported here instead of being guessed at."
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        }
        (message ?: last.takeIf { it.isNotBlank() })?.let { text ->
            Spacer(Modifier.height(8.dp))
            Text(
                text,
                style = MaterialTheme.typography.labelSmall,
                color = if (message == null) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
        }
    }

    loginFor?.let { kind ->
        TrackerLoginDialog(
            app = app,
            kind = kind,
            onSignedIn = { text ->
                message = text
                loginFor = null
            },
            onDismiss = { loginFor = null },
        )
    }
}

@Composable
private fun LanguageCard(app: HikariApp, current: String) {
    var pickerOpen by remember { mutableStateOf(false) }
    val selected = LanguageManager.ALL.firstOrNull { it.tag == current } ?: LanguageManager.SYSTEM
    val systemLabel = "System default" + " (" +
        (LanguageManager.nameOf(LanguageManager.platformTag()) ?: "English") + ")"
    val selectedName = if (selected.tag.isBlank()) systemLabel else selected.name

    Column(Modifier.padding(16.dp)) {
        Text(
            tr("App language"),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(6.dp))
        Text(
            tr("Changes the app's words and the player controls"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        ChoiceRow(
            value = selectedName,
            leadingIcon = Icons.Filled.Translate,
            leadingText = selected.flag,
            onClick = { pickerOpen = true },
        )
    }

    if (pickerOpen) {
        ChoiceDialog(
            title = tr("Choose a language"),
            items = LanguageManager.ALL.map {
                ChoiceItem(
                    key = it.tag,
                    label = if (it.tag.isBlank()) systemLabel else it.name,
                    leading = it.flag,
                )
            },
            selectedKey = selected.tag,
            onPick = { tag -> LanguageManager.choose(app, tag) },
            onDismiss = { pickerOpen = false },
            maxHeight = 420.dp,
        )
    }
}

@Composable
private fun WebViewSafetyCard(app: HikariApp) {
    val scope = rememberCoroutineScope()
    var redirectProtection by remember { mutableStateOf(true) }
    var popupProtection by remember { mutableStateOf(true) }
    var allowedRedirects by remember { mutableStateOf(listOf<String>()) }
    var newAllowedDomain by remember { mutableStateOf("") }
    var popupAllow by remember { mutableStateOf(listOf<String>()) }
    var popupBlock by remember { mutableStateOf(listOf<String>()) }
    var newPopupDomain by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        redirectProtection = app.store.webviewRedirect()
        popupProtection = app.store.webviewPopup()
        allowedRedirects = app.store.webviewRedirectAllow()
        popupAllow = app.store.webviewPopupAllow()
        popupBlock = app.store.webviewPopupBlock()
    }

    SettingsSection(
        id = "privacy.webview",
        icon = Icons.Filled.Shield,
        title = tr("WebView safety"),
        summary = if (redirectProtection && popupProtection) tr("Redirects & popups blocked")
        else tr("Partly blocked"),
    ) {
        SettingsToggle(
            label = tr("Block redirects to other sites"),
            supporting = tr("Subdomains of the site still load"),
            checked = redirectProtection,
            onCheckedChange = {
                redirectProtection = it
                scope.launch { runCatching { app.store.setWebviewRedirect(it) } }
            },
        )
        SettingsToggle(
            label = tr("Block popups from other sites"),
            supporting = tr("Only the site's own popups"),
            checked = popupProtection,
            onCheckedChange = {
                popupProtection = it
                scope.launch { runCatching { app.store.setWebviewPopup(it) } }
            },
        )
        Spacer(Modifier.height(10.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
        Spacer(Modifier.height(10.dp))

        Text(
            tr("Allowed redirect links"),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(4.dp))
        if (!LocalHideHelp.current) {
        Text(
            tr("A host (player.example.com), or one word — a word allows any link that contains it, whatever comes after it: add 'filester' and filester.com, filester.gg and filester.sh all work."),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = newAllowedDomain,
                onValueChange = { newAllowedDomain = it },
                placeholder = { Text(tr("player.example.com")) },
                singleLine = true,
                shape = GlassShape,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = {
                val d = AdBlocker.normalizeDomain(newAllowedDomain)
                if (d.isNotBlank()) {
                    val next = (allowedRedirects + d).distinct()
                    allowedRedirects = next
                    newAllowedDomain = ""
                    scope.launch { app.store.setWebviewRedirectAllow(next) }
                }
            }) { Text(tr("Add")) }
        }
        allowedRedirects.forEach { domain ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    domain,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = {
                    val next = allowedRedirects.filterNot { it == domain }
                    allowedRedirects = next
                    scope.launch { app.store.setWebviewRedirectAllow(next) }
                }) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "Remove $domain",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
        Spacer(Modifier.height(10.dp))
        Text(
            tr("Popup exceptions"),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(4.dp))
        if (!LocalHideHelp.current) {
        Text(
            tr("Chosen from the popup's Allow once / Always allow / Block always question, or added here. Block-always sites never ask again. Remove one to undo a mistaken tap. Ad-domain blocks live under Ad Blocking → Manual blocklist."),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            tr("Always allow popups from"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )
        popupAllow.forEach { domain ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    domain,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = {
                    val next = popupAllow.filterNot { it == domain }
                    popupAllow = next
                    scope.launch { app.store.setWebviewPopupAllow(next) }
                }) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "Remove $domain from popup allow",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
        Text(
            tr("Always block popups from"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 6.dp)
        )
        popupBlock.forEach { domain ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    domain,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = {
                    val next = popupBlock.filterNot { it == domain }
                    popupBlock = next
                    scope.launch { app.store.setWebviewPopupBlock(next) }
                }) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "Unblock popups from $domain",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = newPopupDomain,
                onValueChange = { newPopupDomain = it },
                placeholder = { Text(tr("ads.example.com")) },
                singleLine = true,
                shape = GlassShape,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = {
                val d = AdBlocker.normalizeDomain(newPopupDomain)
                if (d.isNotBlank() && d !in popupBlock) {
                    val next = (popupBlock + d).distinct()
                    popupBlock = next
                    newPopupDomain = ""
                    scope.launch { app.store.setWebviewPopupBlock(next) }
                }
            }) { Text(tr("Block")) }
        }
    }
}

@Composable
private fun BackupCard(app: HikariApp, onPair: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    // Read here, not in the coroutine below: tr() is a composable, so it cannot
    // be called from inside scope.launch.
    val savedPrefix = tr("Saved to Downloads")
    val backingUpPrefix = tr("Backing up…")

    fun report(r: BackupManager.Report) {
        busy = false
        status = if (r.detail.isBlank()) r.message else r.message + " · " + r.detail
        Toast.makeText(context, r.message, Toast.LENGTH_SHORT).show()
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        status = ""
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { BackupManager.restoreStream(app, it) }
                        ?: BackupManager.Report(false, "Could not read that file.")
                }
            }.getOrElse {
                runCatching {
                    com.hikari.app.data.Logs.log(
                        "Backup",
                        "restore failed: " + it.javaClass.simpleName + ": " + it.message.orEmpty(),
                    )
                }
                BackupManager.Report(false, "Backup failed.", it.message.orEmpty())
            }
            report(result)
        }
    }

    // CloudStream's own backup file (.txt) goes through the same picker
    // machinery: it is only ever *read*, and all it can add is repositories.
    val csPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        status = ""
        scope.launch {
            val result = runCatching {
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { BackupManager.readCapped(it, 32L * 1024L * 1024L) }
                }
                if (bytes == null) BackupManager.Report(false, "Could not read that file.")
                else BackupManager.restoreCloudStream(app, bytes)
            }.getOrElse { BackupManager.Report(false, "Import failed.", it.message.orEmpty()) }
            report(result)
        }
    }

    fun backup() {
        busy = true
        status = ""
        scope.launch {
            val result = runCatching {
                val name = BackupManager.fileName()
                val (bytes, saved) = withContext(Dispatchers.IO) {
                    var lastBeat = 0L
                    val opened = com.hikari.app.data.DownloadsSaver
                        .openDownloadStream(context, name, "application/json")
                    if (opened.isSuccess) {
                        val (uri, out) = opened.getOrThrow()
                        try {
                            val n = BackupManager.exportToStream(app, out) { done, total ->
                                val now = System.currentTimeMillis()
                                if (now - lastBeat > 400 || done >= total) {
                                    lastBeat = now
                                    scope.launch { status = backingUpPrefix + " " + done + "/" + total }
                                }
                            }
                            val shown = com.hikari.app.data.DownloadsSaver.finishDownload(context, uri)
                            n to shown.ifBlank { name }
                        } catch (t: Throwable) {
                            runCatching { com.hikari.app.data.DownloadsSaver.abortDownload(context, uri) }
                            throw t
                        }
                    } else {
                        runCatching {
                            com.hikari.app.data.Logs.log("Backup", "direct export unavailable, cache-file fallback")
                        }
                        val f = File(context.cacheDir, "outbox/" + name)
                        BackupManager.exportToFile(app, f)
                        val shown = BackupManager.saveToDownloads(context, f, name)
                        val n = f.length()
                        runCatching { f.delete() }
                        n to (shown ?: throw java.io.IOException("could not save the backup"))
                    }
                }
                val kb = (bytes + 1023) / 1024
                BackupManager.Report(
                    true,
                    savedPrefix + "/" + saved,
                    "${kb} KB · " +
                        "${app.providers.providers.value.size} sources",
                )
            }.getOrElse {
                runCatching {
                    com.hikari.app.data.Logs.log(
                        "Backup",
                        "export failed: " + it.javaClass.simpleName + ": " + it.message.orEmpty(),
                    )
                }
                BackupManager.Report(false, "Backup failed.", it.message.orEmpty())
            }
            report(result)
        }
    }

    Column(Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                tr("Backup & Restore"),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            tr("Settings and extensions in one file."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        BackupRow(
            icon = Icons.Filled.SaveAlt,
            title = tr("Back up Hikari data"),
            subtitle = tr("Save extensions and settings to a file"),
            action = tr("Back up"),
            enabled = !busy,
            onClick = { backup() },
        )
        Spacer(Modifier.height(10.dp))
        BackupRow(
            icon = Icons.Filled.RestorePage,
            title = tr("Restore from a backup"),
            subtitle = tr("Bring a Hikari backup back"),
            action = tr("Restore"),
            enabled = !busy,
            onClick = { picker.launch(arrayOf("application/json", "text/plain", "*/*")) },
        )
        Spacer(Modifier.height(10.dp))
        BackupRow(
            icon = Icons.Filled.QrCode2,
            title = tr("Pair & sync with another device"),
            subtitle = tr("Copy this setup over Wi-Fi — no file, no cable"),
            action = tr("Pair"),
            enabled = !busy,
            onClick = onPair,
        )
        Spacer(Modifier.height(10.dp))
        BackupRow(
            icon = Icons.Filled.SettingsBackupRestore,
            title = tr("Coming from CloudStream?"),
            subtitle = tr("Add the repositories its backup lists"),
            action = tr("Import"),
            enabled = !busy,
            onClick = { csPicker.launch(arrayOf("text/plain", "application/json", "*/*")) },
        )
        if (status.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(
                status,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.height(10.dp))
        if (!LocalHideHelp.current) {
        Text(
            tr("Never includes your videos or your app lock - and each device keeps its own layout."),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        }
    }
}

/** One "icon · title/subtitle · button" row inside [BackupCard]. */
@Composable
private fun BackupRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    action: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            shape = GlassShape,
        ) { Text(action) }
    }
}

@Composable
private fun ExtensionVerifyCard(app: HikariApp) {
    val scope = rememberCoroutineScope()
    var allowed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { allowed = app.store.extensionVerifyWebview() }

    Column(Modifier.padding(16.dp)) {
        SettingsCardHeading(Icons.Filled.VerifiedUser, tr("Verification pages"))
        SettingsToggle(
            label = tr("Open their verification pages"),
            supporting = tr("Off: never opens on its own"),
            checked = allowed,
            onCheckedChange = {
                allowed = it
                scope.launch { runCatching { app.store.setExtensionVerifyWebview(it) } }
            },
        )
    }
}

@Composable
private fun WebViewUserAgentCard(app: HikariApp) {
    val scope = rememberCoroutineScope()
    var useDefault by remember { mutableStateOf(true) }
    var customUa by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        useDefault = app.store.webviewUseDefaultUa()
        customUa = app.store.webviewCustomUa()
        draft = customUa
    }

    fun persist(u: Boolean, custom: String) {
        useDefault = u
        customUa = custom
        // Keep the runtime UA (used by both WebViews + stream capture) current.
        app.webViewUseDefaultUa = u
        app.webViewCustomUa = custom.ifBlank { null }
        scope.launch { runCatching { app.store.setWebViewUa(u, custom) } }
    }

    SettingsSection(
        id = "privacy.user-agent",
        icon = Icons.Filled.Language,
        title = tr("WebView user agent"),
        summary = if (useDefault) tr("Android default") else tr("Custom"),
    ) {
        SettingsToggle(
            label = tr("Use Android default user agent"),
            supporting = tr(
                "The device's own UA, with the WebView marker (`; wv`) removed — sites that " +
                    "refuse to be read inside another app answer HTTP 403 to it"
            ),
            checked = useDefault,
            onCheckedChange = { on -> persist(on, draft) },
        )
        if (!useDefault) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    placeholder = { Text(tr("Mozilla/5.0 …")) },
                    singleLine = true,
                    shape = GlassShape,
                    label = { Text(tr("Custom user agent")) },
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = { persist(false, draft) },
                    enabled = draft.trim().isNotEmpty() && draft.trim() != customUa
                ) { Text(tr("Save")) }
            }
            Text(
                I18n.t("Currently used: %s…").replace("%s", app.effectiveWebViewUa().take(70)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun UserscriptsCard(app: HikariApp) {
    val scope = rememberCoroutineScope()
    var scripts by remember { mutableStateOf<List<Userscript>>(emptyList()) }
    var editing by remember { mutableStateOf<Userscript?>(null) }
    var adding by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        scripts = runCatching { app.store.userscripts() }.getOrDefault(emptyList())
    }

    fun persist(list: List<Userscript>) {
        scripts = list
        scope.launch { runCatching { app.store.setUserscripts(list) } }
    }

    SettingsSection(
        id = "sources.userscripts",
        icon = Icons.Filled.Code,
        title = tr("Userscripts"),
        summary = if (scripts.isEmpty()) tr("None added")
        else scripts.size.toString() + " " + (if (scripts.size == 1) tr("script") else tr("scripts")),
    ) {
        if (scripts.isEmpty()) {
            Text(
                tr("Tampermonkey-style, WebView-only scripts"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
        } else {
            scripts.forEach { s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            s.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1
                        )
                        Text(
                            if (s.enabled) I18n.t("Active in WebView") else I18n.t("Paused"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = s.enabled,
                        onCheckedChange = { on ->
                            persist(scripts.map {
                                if (it.id == s.id) it.copy(enabled = on) else it
                            })
                        },
                        // See [Modifier.tvToggle].
                        modifier = Modifier.tvToggle(s.enabled) { on ->
                            persist(scripts.map {
                                if (it.id == s.id) it.copy(enabled = on) else it
                            })
                        },
                    )
                    TextButton(onClick = { draft = s.code; editing = s }) { Text(tr("Edit")) }
                    IconButton(onClick = { persist(scripts.filterNot { it.id == s.id }) }) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = tr("Delete"),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        }
        OutlinedButton(
            onClick = { draft = ""; adding = true },
            shape = GlassShape,
        ) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text(tr("Add userscript"))
        }
    }

    if (adding || editing != null) {
        AlertDialog(
            onDismissRequest = { adding = false; editing = null },
            title = { Text(if (editing != null) I18n.t("Edit userscript") else I18n.t("Add userscript")) },
            text = {
                Column {
                    Text(
                        tr("Runs only inside the WebView."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier
                            .fillMaxWidth().tvTextFieldKeys(draft)
                            .heightIn(min = 200.dp),
                        textStyle = MaterialTheme.typography.bodySmall,
                        shape = GlassShape,
                        placeholder = { Text(tr("// ==UserScript==\n// @name   My Script\n// @match  https://example.com/*\n// @run-at document-start\n// ==/UserScript==\n\nconsole.log('hello');")) }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val code = draft.trim()
                    if (code.isNotEmpty()) {
                        val name = UserscriptManager.parse(code).name
                        val editId = editing?.id
                        persist(
                            if (editId != null) {
                                scripts.map {
                                    if (it.id == editId) it.copy(name = name, code = code) else it
                                }
                            } else {
                                scripts + Userscript(
                                    id = "us" + System.currentTimeMillis(),
                                    name = name,
                                    code = code
                                )
                            }
                        )
                    }
                    adding = false
                    editing = null
                }) { Text(tr("Save")) }
            },
            dismissButton = {
                TextButton(onClick = { adding = false; editing = null }) { Text(tr("Cancel")) }
            }
        )
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun AdBlockingCard(app: HikariApp) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var enabled by remember { mutableStateOf(true) }
    var lists by remember { mutableStateOf(listOf<AdBlocker.HostList>()) }
    var blockList by remember { mutableStateOf(listOf<String>()) }
    var whiteList by remember { mutableStateOf(listOf<String>()) }
    var updating by remember { mutableStateOf(false) }
    var updateStatus by remember { mutableStateOf<String?>(null) }
    var showAddListDialog by remember { mutableStateOf(false) }
    var newBlockDomain by remember { mutableStateOf("") }
    var newWhiteDomain by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        enabled = app.store.adEnabled()
        lists = app.store.adLists()
        blockList = app.store.adBlock()
        whiteList = app.store.adWhite()
    }

    SettingsSection(
        id = "privacy.ad-blocking",
        icon = Icons.Filled.Block,
        title = tr("Ad Blocking"),
        summary = if (!enabled) tr("Off")
        else tr("On") + " · " + lists.size + " " + (if (lists.size == 1) tr("list") else tr("lists")),
    ) {
        SettingsToggle(
            label = tr("Block ads & trackers"),
            supporting = tr("In the browser tab, not the player"),
            checked = enabled,
            onCheckedChange = {
                enabled = it
                scope.launch { app.store.setAdEnabled(it) }
            },
        )

        if (enabled) {
            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            Spacer(Modifier.height(10.dp))

            Text(
                tr("Blocklists (ad hosts)"),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(6.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                AdBlocker.PRESETS.forEach { preset ->
                    val isAdded = lists.any { it.url == preset.url }
                    OutlinedButton(
                        onClick = {
                            val next = if (isAdded) {
                                lists.filterNot { it.url == preset.url }
                            } else {
                                lists.filterNot { it.url == preset.url } + preset
                            }
                            lists = next
                            scope.launch(Dispatchers.IO) {
                                runCatching { app.store.setAdLists(next) }
                                runCatching { AdBlocker.download(preset.url, context) }
                            }
                        },
                        shape = GlassShape,
                    ) {
                        Text((if (isAdded) "✓ " else "+ ") + preset.name)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = { showAddListDialog = true }) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(tr("Add custom list URL"))
            }
            lists.forEach { list ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(list.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            list.url,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = {
                        lists = lists.filterNot { it.url == list.url }
                        scope.launch { app.store.setAdLists(lists) }
                    }) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "Remove ${list.name}",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = {
                        updating = true
                        updateStatus = null
                        scope.launch(Dispatchers.IO) {
                            val total = runCatching { AdBlocker.refreshAll(lists, context) }
                                .getOrDefault(emptySet()).size
                            withContext(Dispatchers.Main) {
                                updating = false
                                updateStatus = if (total > 0) {
                                    "$total blocked domains ready"
                                } else {
                                    "Couldn't update lists — check connection"
                                }
                            }
                        }
                    },
                    enabled = !updating && lists.isNotEmpty()
                ) {
                    if (updating) {
                        CircularProgressIndicator(
                            Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(tr("Updating…"))
                    } else {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(tr("Update lists"))
                    }
                }
            }
            if (updateStatus != null) {
                Text(
                    updateStatus!!,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            Spacer(Modifier.height(10.dp))

            Text(
                tr("Manual blocklist"),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(4.dp))
            Text(
                tr("Always block this domain"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newBlockDomain,
                    onValueChange = { newBlockDomain = it },
                    placeholder = { Text(tr("ads.example.com")) },
                    singleLine = true,
                    shape = GlassShape,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = {
                    val d = AdBlocker.normalizeDomain(newBlockDomain)
                    if (d.isNotBlank()) {
                        val next = (blockList + d).distinct()
                        blockList = next
                        newBlockDomain = ""
                        scope.launch { app.store.setAdBlock(next) }
                    }
                }) { Text(tr("Add")) }
            }
            blockList.forEach { domain ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        domain,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = {
                        val next = blockList.filterNot { it == domain }
                        blockList = next
                        scope.launch { app.store.setAdBlock(next) }
                    }) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "Unblock $domain",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            Spacer(Modifier.height(10.dp))

            Text(
                tr("Whitelist"),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(4.dp))
            Text(
                tr("Whitelist a wrongly-blocked site"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newWhiteDomain,
                    onValueChange = { newWhiteDomain = it },
                    placeholder = { Text(tr("video-site.example.com")) },
                    singleLine = true,
                    shape = GlassShape,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = {
                    val d = AdBlocker.normalizeDomain(newWhiteDomain)
                    if (d.isNotBlank()) {
                        val next = (whiteList + d).distinct()
                        whiteList = next
                        newWhiteDomain = ""
                        scope.launch { app.store.setAdWhite(next) }
                    }
                }) { Text(tr("Add")) }
            }
            whiteList.forEach { domain ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        domain,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = {
                        val next = whiteList.filterNot { it == domain }
                        whiteList = next
                        scope.launch { app.store.setAdWhite(next) }
                    }) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "Remove $domain from whitelist",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }

    if (showAddListDialog) {
        var name by remember { mutableStateOf("") }
        var url by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddListDialog = false },
            title = { Text(tr("Add blocklist")) },
            text = {
                Column {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(tr("Name")) },
                        shape = GlassShape,
                        singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text(tr("Hosts file URL")) },
                        shape = GlassShape,
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val u = url.trim()
                    if (name.isNotBlank() && u.startsWith("http")) {
                        val list = AdBlocker.HostList(name.trim(), u)
                        val next = lists.filterNot { it.url == u } + list
                        lists = next
                        showAddListDialog = false
                        scope.launch(Dispatchers.IO) {
                            runCatching { app.store.setAdLists(next) }
                            runCatching { AdBlocker.download(u, context) }
                        }
                    } else {
                        showAddListDialog = false
                    }
                }) { Text(tr("Add")) }
            },
            dismissButton = {
                TextButton(onClick = { showAddListDialog = false }) { Text(tr("Cancel")) }
            }
        )
    }
}


// ---- Player controls & video enhance (Player folder) ----

/** Opens the full-screen Player UI style editor (Customize folder). */
@Composable
private fun PlayerStyleCard(onOpen: () -> Unit) {
    Column(Modifier.padding(16.dp)) {
        Text(
            tr("Player UI style"),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(6.dp))
        Text(
            tr("Make the player buttons bigger, recolour them, change their shape and move them"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onOpen, shape = GlassShape) {
            Text(tr("Customize player buttons"))
        }
    }
}

/** Opens the full-screen Player gestures & decoder page (Customize folder). */
@Composable
private fun PlayerGesturesCard(onOpen: () -> Unit) {
    Column(Modifier.padding(16.dp)) {
        Text(
            tr("Player gestures & decoder"),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(6.dp))
        Text(
            tr("Swipes, pinch-to-zoom and the video decoder"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onOpen, shape = GlassShape) {
            Text(tr("Open gestures & decoder"))
        }
    }
}

/** Video enhance preset picker — real GPU colour grading of the video itself. */
@Composable
private fun VideoEnhanceCard(app: HikariApp) {
    val scope = rememberCoroutineScope()
    val presetFlow = remember { app.store.enhancePresetFlow() }
    val presetKey by presetFlow.collectAsState(initial = EnhancePreset.DEFAULT.key)
    val customsFlow = remember { app.store.customEnhanceFlow() }
    val customs by customsFlow.collectAsState(initial = emptyList())
    var menuOpen by remember { mutableStateOf(false) }
    var editorOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<CustomEnhancePreset?>(null) }
    val activeCustom = customs.firstOrNull { it.key() == presetKey }
    val preset = EnhancePreset.fromKey(presetKey)

    SettingsSection(
        id = "player.enhance",
        icon = Icons.Filled.AutoAwesome,
        title = tr("Video enhance"),
        summary = tr(activeCustom?.displayName() ?: preset.label),
    ) {
        Box {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
                    .clickable { menuOpen = true }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(tr("Preset"), style = MaterialTheme.typography.titleSmall)
                    Text(
                        activeCustom?.summary() ?: tr(preset.desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    activeCustom?.displayName() ?: tr(preset.label),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(6.dp))
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (customs.size < 12) {
                OutlinedButton(
                    onClick = {
                        editing = null
                        editorOpen = true
                    },
                    shape = GlassShape,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(tr("New custom preset"))
                }
            }
            if (activeCustom != null) {
                OutlinedButton(
                    onClick = {
                        editing = activeCustom
                        editorOpen = true
                    },
                    shape = GlassShape,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(tr("Edit custom preset"))
                }
            }
        }
        if (customs.size < 12 || activeCustom != null) Spacer(Modifier.height(8.dp))
    }

    if (menuOpen) {
        ChoiceDialog(
            title = tr("Video enhance"),
            items = EnhancePreset.entries.map {
                ChoiceItem(it.key, tr(it.label), tr(it.desc))
            } + customs.map {
                ChoiceItem(it.key(), it.displayName(), tr("Custom preset") + " · " + it.summary())
            },
            selectedKey = activeCustom?.key() ?: preset.key,
            onPick = { pick ->
                scope.launch {
                    runCatching {
                        app.store.setEnhancePreset(pick)
                        // The user picked it themselves — the TV layout stops
                        // forcing Natural from here on (HikariApp.syncTvEnhance).
                        app.store.setEnhanceChosen(true)
                    }
                }
            },
            onDismiss = { menuOpen = false },
        )
    }

    if (editorOpen) {
        CustomEnhanceEditorDialog(
            app = app,
            existing = editing,
            customs = customs,
            activeKey = presetKey,
            onDismiss = { editorOpen = false },
        )
    }
}

@Composable
private fun CustomEnhanceEditorDialog(
    app: HikariApp,
    existing: CustomEnhancePreset?,
    customs: List<CustomEnhancePreset>,
    activeKey: String,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var brightness by remember { mutableStateOf(existing?.brightness ?: 0) }
    var saturation by remember { mutableStateOf(existing?.saturation ?: 0) }
    var contrast by remember { mutableStateOf(existing?.contrast ?: 0) }
    var gamma by remember { mutableStateOf(existing?.gamma ?: 0) }
    var hue by remember { mutableStateOf(existing?.hue ?: 0) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr(if (existing == null) "New custom preset" else "Edit custom preset")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(24) },
                    label = { Text(tr("Name")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                CustomEnhanceSliderRow(tr("Brightness"), brightness, -100, 100) { brightness = it }
                CustomEnhanceSliderRow(tr("Saturation"), saturation, -100, 100) { saturation = it }
                CustomEnhanceSliderRow(tr("Contrast"), contrast, -100, 100) { contrast = it }
                CustomEnhanceSliderRow(tr("Gamma"), gamma, -100, 100) { gamma = it }
                Text(
                    tr("Gamma is a midtone lift — media3 has no true gamma curve."),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                CustomEnhanceSliderRow(tr("Hue"), hue, -180, 180) { hue = it }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val clean = CustomEnhancePreset.cleanName(name).ifBlank { "Custom" }
                val preset = (existing ?: CustomEnhancePreset(id = CustomEnhancePreset.newId(), name = clean))
                    .copy(
                        name = clean,
                        brightness = brightness,
                        saturation = saturation,
                        contrast = contrast,
                        gamma = gamma,
                        hue = hue,
                    )
                val list = customs.toMutableList()
                val at = list.indexOfFirst { it.id == preset.id }
                if (at >= 0) list[at] = preset else list += preset
                scope.launch {
                    runCatching {
                        app.store.setCustomEnhanceList(list)
                        app.store.setEnhancePreset(preset.key())
                        app.store.setEnhanceChosen(true)
                    }
                }
                onDismiss()
            }) {
                Text(tr("Save"))
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (existing != null) {
                    TextButton(onClick = {
                        scope.launch {
                            runCatching {
                                app.store.setCustomEnhanceList(customs.filterNot { it.id == existing.id })
                                if (activeKey == existing.key()) {
                                    app.store.setEnhancePreset(EnhancePreset.NATURAL.key)
                                }
                            }
                        }
                        onDismiss()
                    }) {
                        Text(tr("Delete"))
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(tr("Cancel"))
                }
            }
        },
    )
}

@Composable
private fun CustomEnhanceSliderRow(
    title: String,
    value: Int,
    min: Int,
    max: Int,
    onChange: (Int) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Text(
            (if (value >= 0) "+" else "") + value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    Slider(
        value = value.toFloat(),
        onValueChange = { onChange(it.roundToInt().coerceIn(min, max)) },
        valueRange = min.toFloat()..max.toFloat(),
        modifier = Modifier.fillMaxWidth()
    )
}

// ---- Accent colours (Appearance & Theme folder) ----

/** The app accent picker (and the player's own accent while the two are not
 *  linked). Every swatch is drawn from the accent's real gradient, so what you
 *  tap is what the buttons will look like. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccentCard(
    app: HikariApp,
    appAccentKey: String,
    playerAccentKey: String,
    linked: Boolean,
) {
    val scope = rememberCoroutineScope()

    SettingsSection(
        id = "appearance.accent",
        icon = Icons.Filled.ColorLens,
        title = tr("Accent color"),
        summary = HikariAccent.fromKey(appAccentKey).label,
    ) {
        AccentSwatches(
            selected = HikariAccent.fromKey(appAccentKey),
            onPick = { accent ->
                scope.launch { runCatching { app.store.setAppAccent(accent.key) } }
            }
        )

        if (linked) {
            Spacer(Modifier.height(10.dp))
            Text(
                tr("The player follows this colour."),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Spacer(Modifier.height(18.dp))
            Text(
                tr("Player color"),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(8.dp))
            AccentSwatches(
                selected = HikariAccent.fromKey(playerAccentKey),
                onPick = { accent ->
                    scope.launch { runCatching { app.store.setPlayerAccent(accent.key) } }
                }
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AuraColorRow(selected: String, onPick: (String) -> Unit) {
    val chosen = AuraColors.normalize(selected)
    Column(Modifier.padding(top = 12.dp)) {
        Text(
            tr("Aura ring colour"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            if (chosen == AuraColors.THEME) tr("Follows the accent colour")
            else tr("The ring's own colour"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(8.dp))
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AuraColors.ALL.forEach { key ->
                val isSelected = key == chosen
                val swatch = if (key == AuraColors.THEME) MaterialTheme.colorScheme.primary
                else HikariAccent.fromKey(key).mid
                Column(
                    Modifier
                        .width(56.dp)
                        .clickable { onPick(key) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(50))
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(50))
                                .background(swatch),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (isSelected) {
                                Icon(
                                    Icons.Filled.CheckCircle,
                                    contentDescription = null,
                                    tint = inkOn(swatch),
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        tr(AuraColors.label(key)),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccentSwatches(selected: HikariAccent, onPick: (HikariAccent) -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        HikariAccent.entries.forEach { accent ->
            val isSelected = accent == selected
            Column(
                Modifier
                    .width(56.dp)
                    .clickable { onPick(accent) },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(50))
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(50))
                            .background(
                                Brush.linearGradient(listOf(accent.start, accent.end))
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isSelected) {
                            Icon(
                                Icons.Filled.CheckCircle,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    accent.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }
}

/** "Match app & player theme", plus the one-tap syncs in either direction. */
@Composable
private fun MatchThemeCard(
    app: HikariApp,
    linked: Boolean,
    appAccentKey: String,
    playerAccentKey: String,
) {
    val scope = rememberCoroutineScope()

    SettingsSection(
        id = "appearance.match-theme",
        icon = Icons.Filled.Palette,
        title = tr("Match app & player theme"),
        summary = if (linked) tr("The player follows the app colour")
        else tr("The two keep their own colours"),
    ) {
        SettingsToggle(
            label = tr("Use one colour everywhere"),
            checked = linked,
            onCheckedChange = { on ->
                scope.launch {
                    runCatching {
                        if (on) {
                            // Copy the app colour onto the player as the two
                            // are joined, so they are identical immediately.
                            app.store.setPlayerAccent(appAccentKey)
                            app.store.setThemeLinked(true)
                        } else {
                            app.store.setThemeLinked(false)
                        }
                    }
                }
            }
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    scope.launch { runCatching { app.store.setPlayerAccent(appAccentKey) } }
                },
                enabled = playerAccentKey != appAccentKey,
                shape = GlassShape,
            ) { Text(tr("App \u2192 player")) }
            OutlinedButton(
                onClick = {
                    scope.launch { runCatching { app.store.setAppAccent(playerAccentKey) } }
                },
                enabled = playerAccentKey != appAccentKey,
                shape = GlassShape,
            ) { Text(tr("Player \u2192 app")) }
        }
    }
}
