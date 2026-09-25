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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Extension
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
import androidx.compose.material.icons.filled.SaveAlt
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
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.hikari.app.R
import com.hikari.app.data.BackupManager
import com.hikari.app.data.ProviderType
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
import com.hikari.app.player.PlayerSkins
import com.hikari.app.ui.AppIconManager
import com.hikari.app.ui.AppIconVariants
import com.hikari.app.ui.AppFonts
import com.hikari.app.ui.AuraColors
import com.hikari.app.ui.LoadingEffects
import com.hikari.app.ui.LoadingStyles
import com.hikari.app.ui.PosterEffects
import com.hikari.app.ui.components.ChoiceDialog
import com.hikari.app.ui.components.ChoiceItem
import com.hikari.app.ui.components.ChoiceRow
import com.hikari.app.ui.components.GlassCard
import com.hikari.app.ui.components.GlassShape
import com.hikari.app.ui.components.HeroStyles
import com.hikari.app.ui.components.MultiChoiceDialog
import com.hikari.app.ui.components.SettingsIconBadge
import com.hikari.app.ui.components.SettingsPageHeader
import com.hikari.app.ui.components.TrackerLoginDialog
import com.hikari.app.ui.LanguageManager
import com.hikari.app.ui.components.UpdateDialog
import com.hikari.app.ui.navigation.BottomTab
import com.hikari.app.ui.navigation.BottomTabs
import com.hikari.app.ui.navigation.LocalTaskbarInset
import com.hikari.app.ui.navigation.NavStyles
import com.hikari.app.ui.navigation.Routes
import com.hikari.app.ui.edgeLight
import com.hikari.app.ui.openTelegram
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

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 72.dp),
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
    )
}

/**
 * One category of settings. The Settings tab is a short INDEX of these folders
 * instead of one long scroll of every switch the app owns, so it stays readable
 * no matter how many options get added; opening a folder shows only what
 * belongs to it. A folder may itself contain folders (see [parent]) — the two
 * busiest ones do, because "Appearance & Theme" and "App Layout" had each grown into a
 * long page of unrelated switches.
 *
 * The split: Appearance & Theme is what the app *is* and *wears* — language,
 * theme, metadata language, accent colour, launcher icon, font. Everything about
 * how a page is laid out and how much room the interface takes — interface
 * scale, posters, ratings, the taskbar, full screen — lives under App Layout.
 */
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
    // The one switch to reach for when the app stutters. Its own folder rather
    // than a corner of App Layout: the user looking for it is not browsing how
    // the app LOOKS, they are trying to make it run — and it must be findable
    // while the app is being annoying to use.
    PERFORMANCE(
        "performance",
        "Performance",
        "Fix lag, stutter & battery drain",
        Icons.Filled.Speed,
    ),
    // The television half of "one APK, two layouts" (see com.hikari.app.tv.TvMode).
    // Offered on both kinds of device on purpose: a phone user can switch the TV
    // layout on to see it, and a television whose box reports itself as a phone
    // can switch it on from here — which is the whole reason the folder exists.
    TV(
        "tv",
        "TV & Remote",
        "Television layout, screen edges & performance",
        Icons.Filled.Tv,
    ),
    PLAYER(
        "player",
        "Player",
        "Controls, video enhancement & loading screen",
        Icons.Filled.PlayArrow,
    ),
    // Where a title's SERVERS come from — the other half of "playing a video",
    // kept apart from the Player folder (which is about the controls and the
    // look of the player itself). This is the CloudStream-shaped question: do a
    // lookup's servers come from the extension you opened the title from, or
    // from every extension you have installed? It also carries the playback
    // start choice, so "what plays and when" is one folder.
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
    // What the app is willing to SHOW, as opposed to what it can reach: the
    // adult-content switch lives here (see NsfwGate). Its own folder rather than
    // a corner of Sources & Extensions, because the switch hides both titles and
    // extensions and a user looking for it thinks of it as "content", not as an
    // extension they installed.
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
    // Your watch progress on somebody else's list — AniList, MyAnimeList,
    // Kitsu, Simkl, Shikimori, Trakt (see [TrackerKind]). Its own folder rather
    // than a corner of Playback: the user looking for it is thinking about the
    // account they keep elsewhere, not about this app's player.
    TRACKERS(
        "trackers",
        "Trackers",
        "AniList, MyAnimeList, Kitsu, Simkl, Shikimori & Trakt",
        Icons.Filled.Sync,
    ),    // The user's own catalogs (Collections) live here rather than under
    // Appearance & Theme: they are something the user CREATES and manages — like the
    // extensions they install — not a way the app looks, and a folder of their
    // own is where they go looking for it.
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
    // The one irreversible action in Settings, in a folder of its own. It used
    // to be a red text link at the very foot of the index — easy to hit by
    // accident while scrolling, impossible to explain, and it acted the instant
    // it was tapped. A folder gives it a page that says what "everything"
    // means, and the button there asks first (see ClearDataCard).
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
        // A television launcher does not show an icon: it shows the app's
        // banner, and the aliases below are the phone launcher's.
        phoneOnly = true,
    ),
    LAYOUT_POSTER(
        "layout.poster",
        "Poster styling",
        "Blur, corners, titles & score badges",
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

/**
 * Which cards are currently unfolded, keyed by the card's own id (see
 * [SettingsSection]).
 *
 * Deliberately a file-level map rather than per-card `remember`: folder pages
 * clear it on every entry and exit (see [SettingsScreen]), so a page always
 * opens folded — tap a heading to unfold it, and it is folded again the next
 * time the page is opened — while an unfolded card keeps its state while the
 * user scrolls the page it is on.
 */
private val openSettingsSections = mutableStateMapOf<String, Boolean>()

/**
 * A card that holds more than one setting.
 *
 * Folder pages used to be a wall of always-open switches. A card with two or
 * more controls now reads as a single heading — icon, name, and a one-line
 * summary of what it is set to — and unfolds in place when tapped; the summary
 * answers "what is this set to?" without opening anything. Cards with one
 * control are not sections: there is nothing to fold, so they stay plain
 * [SettingsCard]s.
 */
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
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (!summary.isNullOrBlank()) {
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

@Composable
fun SettingsScreen(nav: NavHostController) {
    val context = LocalContext.current
    val app = context.applicationContext as HikariApp
    val scope = rememberCoroutineScope()
    // Which layout this device is drawing (see com.hikari.app.tv.TvMode). It
    // decides which cards are offered at all: the taskbar, the launcher icon and
    // "full screen app mode" are phone things, and a television gets its own
    // folder instead.
    val isTv = TvMode.current()

    var themeMenuOpen by remember { mutableStateOf(false) }
    var checkingUpdates by remember { mutableStateOf(false) }
    var updateStatus by remember { mutableStateOf<Updater.UpdateStatus?>(null) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var openFolder by remember { mutableStateOf<SettingsFolder?>(null) }
    // A sub-folder inside [openFolder] (Appearance & Theme → App icon, App Layout →
    // Poster styling…). Two levels is the whole tree, so two slots is enough and
    // back always has an obvious target.
    var openSub by remember { mutableStateOf<SettingsFolder?>(null) }
    var showPlayerControls by remember { mutableStateOf(false) }
    var showStats by remember { mutableStateOf(false) }
    var showPair by remember { mutableStateOf(false) }
    var showLogs by remember { mutableStateOf(false) }
    // Settings → Clear App data: the confirmation in front of the app's only
    // irreversible action (see ClearDataCard and the dialog at the end of this
    // composable).
    var showClearDataDialog by remember { mutableStateOf(false) }

    // Accent colours: the app accent repaints this whole screen live; the
    // player accent (and the "match app & player" switch) decide what the
    // View-based player will use.
    val appAccentFlow = remember { app.store.appAccentFlow() }
    val appAccentKey by appAccentFlow.collectAsState(initial = HikariAccent.DEFAULT_APP.key)
    // The chosen theme is persisted, so it must be read back from the store:
    // starting from DARK made a saved AMOLED/Dark Glass/Light show as
    // "Hikari Dark" until the user re-picked it in the same session.
    val themeFlow = remember { app.store.themeFlow() }
    val storedThemeKey by themeFlow.collectAsState(initial = HikariThemeMode.DARK.key)
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
    val listState = rememberLazyListState()

    // System back steps out of the open settings folder (Player, Sources…) —
    // and out of a sub-folder before that — instead of popping the whole
    // Settings destination and landing on Home.
    BackHandler(enabled = openSub != null || openFolder != null) {
        if (openSub != null) openSub = null else openFolder = null
    }

    // The Player controls editor is its own full screen (fifteen controls × a
    // four-way placement each does not fit in one card).
    if (showPlayerControls) {
        PlayerControlsPage(app, onBack = { showPlayerControls = false })
        return
    }

    // The logs page is its own full screen too: three files, each with two
    // actions, plus the share-all row.
    if (showLogs) {
        LogsPage(app, onBack = { showLogs = false })
        return
    }

    // Stats is a full page as well, and the SAME composable the off-by-default
    // Stats tab draws (Settings → Taskbar buttons switches that button on):
    // one screen with two doors, so the two can never drift apart.
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

    // A folder opens at its own top: without this, opening one from partway
    // down the index would leave the new page scrolled by the old offset.
    // The same move folds every settings section again: a page always arrives
    // with its cards closed, so the user never comes back to a page they left
    // half-unfolded.
    LaunchedEffect(openFolder, openSub) {
        listState.scrollToItem(0)
        openSettingsSections.clear()
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 16.dp,
            // Clear of the floating taskbar (0 when there is no bar).
            bottom = LocalTaskbarInset.current + 16.dp,
        )
    ) {
        val folder = openFolder
        val sub = openSub
        if (folder != null) {
            item {
                FolderHeader(
                    folder = sub ?: folder,
                    parentTitle = if (sub != null) folder.title else null,
                    onBack = { if (sub != null) openSub = null else openFolder = null },
                )
            }
            when (sub ?: folder) {
                SettingsFolder.PLAYER -> {
                    item {
                        SettingsCard(top = 2.dp) {
                            PlayerControlsCard(onOpen = { showPlayerControls = true })
                        }
                    }
                    item { SettingsCard { VideoEnhanceCard(app) } }
                    item { SettingsCard { PlayerUiCard(app) } }
                    // "When playback starts" lives in Playback & Servers, once:
                    // the same card in two folders only made the user wonder
                    // which one was in charge. The player folder is the controls.
                    item { SettingsCard { LoadingBannerCard(app) } }
                }
                SettingsFolder.PLAYBACK_SERVERS -> {
                    item { SettingsCard(top = 2.dp) { ServerSearchCard(app) } }
                    // "When playback starts" (play the first server / wait for more)
                    // lives here — it is a decision about SERVERS — and only here:
                    // the same card used to show up in the Player folder too.
                    item { SettingsCard { PlaybackStartCard(app) } }
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
                    // Whether EXTENSIONS may open their own Cloudflare
                    // verification page belongs with the extensions, not with the
                    // WebView's ad/redirect rules: it is a decision about what an
                    // extension is allowed to do, and that is the folder a user
                    // opens to look for it.
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
                                // A glass page of themes rather than a Material
                                // dropdown, so picking one looks like picking a
                                // language, a font or a DNS resolver.
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
                    // The things the app wears. Each is several choices wide (a
                    // wall of accent swatches, a dozen icon aliases, a stack of
                    // fonts), so they get their own pages instead of turning
                    // Appearance into a long scroll past everything else.
                    //
                    // (The "In-app UI scale" card lives under App Layout now:
                    // it changes how much ROOM the interface takes, which is
                    // layout, not decoration — see SettingsFolder.APP_LAYOUT.)
                    SettingsFolder.entries
                        .filter { it.parent == SettingsFolder.APPEARANCE.key }
                        .filter { !isTv || !it.phoneOnly }
                        .forEach { target ->
                            item {
                                SettingsFolderRow(
                                    folder = target,
                                    onClick = { openSub = target },
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
                    // Animated covers (a personal catalog's GIF folder tiles).
                    // Per-device on purpose: the same imported file goes to a
                    // phone that renders a row of GIFs happily and to a TV stick
                    // that would rather not spend its frames on them.
                    item { SettingsCard { GifAnimCard(app) } }
                    SettingsFolder.entries
                        .filter { it.parent == SettingsFolder.APP_LAYOUT.key }
                        .filter { !isTv || !it.phoneOnly }
                        .forEach { target ->
                            item {
                                SettingsFolderRow(
                                    folder = target,
                                    top = 12.dp,
                                    onClick = { openSub = target },
                                )
                            }
                        }
                    // "Turn off full screen app mode" is a phone setting: a
                    // television is always immersive (there is no status bar to
                    // read and no navigation bar to reach), so the card is not
                    // offered there — see MainActivity.applyImmersiveMode.
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
                SettingsFolder.TV -> {
                    item { SettingsCard(top = 2.dp) { TvDeviceCard(app) } }
                    item { SettingsCard { TvOverscanCard(app) } }
                    item { SettingsCard { TvPerformanceCard(app) } }
                    item { SettingsCard { TvRemoteCard() } }
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
                            }
                        }
                    }
                    item { SettingsCard { RoadmapCard() } }
                    item { SettingsCard { AboutCard() } }
                }
                // Deleting the app's data lives here, and only here. The card
                // spells out what goes, the note under it says it cannot be
                // undone, and the button opens a confirmation — the tap itself
                // deletes nothing.
                SettingsFolder.CLEAR_DATA -> {
                    item { SettingsCard(top = 2.dp) { ClearDataCard(onClear = { showClearDataDialog = true }) } }
                }
            }
        } else {
            item {
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        tr("Settings"),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(14.dp))
                }
            }
            // Only the top-level folders: a sub-folder is reached from inside its
            // parent, not from the index. On a television the phone-only ones
            // (the taskbar, the launcher icon aliases) are left out entirely.
            SettingsFolder.entries
                .filter { it.parent == null && (!isTv || !it.phoneOnly) }
                .forEach { target ->
                item {
                    SettingsFolderRow(folder = target, onClick = { openFolder = target })
                }
            }
            // Stats: a page rather than a folder (it is one screen, like Logs),
            // and the same page the off-by-default Stats tab draws. It sits on
            // the index as well as in the taskbar settings so it is reachable
            // whether or not the user ever switches that button on.
            item {
                SettingsCard(top = 12.dp) {
                    ListItem(
                        leadingContent = {
                            Icon(
                                Icons.Filled.BarChart,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        },
                        headlineContent = { Text(tr("Stats")) },
                        supportingContent = {
                            Text(tr("Time spent, streaks and what you watched most"))
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
        }
    }

    if (showUpdateDialog) {
        UpdateDialog(
            context = context,
            onDismiss = { showUpdateDialog = false },
            initialStatus = updateStatus,
        )
    }

    // The warning the folder's button opens. Nothing about clearing data is
    // destructive until the confirm button here is tapped, so this dialog is
    // the only place the wipe is called from.
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

/**
 * The folder page's own header: the folder's badge, then its name and one-line
 * summary, on the same line as the back button — so a folder starts with the
 * same "icon, then what it is called" shape as the row that opened it.
 *
 * The layout, the one size every settings page's name is drawn at, and the
 * reason for it all live in [SettingsPageHeader]; this is only the folder-shaped
 * caller of it. [parentTitle] is set when a sub-folder is open, and is printed as
 * a breadcrumb ("Appearance ›") on the line above the badge row.
 */
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
 *  sub-folder row can sit tighter under its parent's heading. */
@Composable
private fun SettingsFolderRow(folder: SettingsFolder, top: Dp = 12.dp, onClick: () -> Unit) {
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
            SettingsIconBadge(folder.icon)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    tr(folder.title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    tr(folder.subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(10.dp))
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/**
 * The door to the Extensions tab, put in the folder where the user asks "where
 * do I add extensions?". This folder explains how sources are found; the
 * installing/browsing itself lives on the Extensions tab, so this card hands
 * the user over to it instead of describing it from a distance.
 */
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
            Box(
                Modifier
                    .size(44.dp)
                    .clip(GlassShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Extension,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    tr("Extensions"),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(3.dp))
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

/**
 * Animated covers on tiles.
 *
 * A personal catalog's folder tile can wear a GIF (see [CoverKinds.GIF]), and
 * the reference app pairs that with two switches: the catalog's own "show GIF
 * when configured", and a per-device override. This is the device half — a
 * television stick that drops frames while a wall of GIFs animates can turn them
 * still without editing what it imported, and a folder whose own "always
 * animate" is on still animates either way (see
 * [com.hikari.app.data.AppStore.gifAnimFlow]).
 */
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
                Text(
                    tr(
                        "Play animated folder covers (GIFs) in your personal catalogs. Off " +
                            "draws their first frame — lighter on a TV stick."
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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

/**
 * The adult-content switch (Settings → Content & Filters).
 *
 * ON is the default and the app as it has always been. OFF is a single rule the
 * whole app reads at draw time ([com.hikari.app.data.NsfwGate]) plus the provider
 * list ([com.hikari.app.providers.ProviderManager]): adult and
 * R-rated titles leave every catalogue, shelf, grid, search result and
 * collection, and 18+-tagged extensions stop being listed — installed ones stop
 * being used at all, and store listings stop offering them.
 *
 * The caption says what OFF does rather than what the switch is called, because
 * this is the one setting whose name ("NSFW") is understood differently by
 * different users: what matters is that nothing adult is reachable while it is
 * off, and that nothing was deleted while it was on.
 */
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
        Text(
            tr("✓ Stremio addons\n" + "✓ Universal scrapers\n") +
                I18n.t("✓ HLS/DASH player with headers + subtitles\n") +
                I18n.t("✓ CloudStream .cs3 plugin loader\n") +
                I18n.t("✓ Torrent engine for infoHash streams\n") +
                I18n.t("✓ Watch history + Continue Watching (all extensions)\n") +
                I18n.t("✓ Downloads — offline copies, export to phone storage, concurrent limit\n") +
                I18n.t("✓ SkyStream .sky extensions (scriptable JS providers)\n") +
                I18n.t("✓ Trackers — AniList, MyAnimeList, Kitsu, Simkl, Shikimori & Trakt\n") +
                I18n.t("• Manga reading progress on your tracker (planned)"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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

/**
 * Settings → Clear App data: the app's only irreversible action, on a page of
 * its own.
 *
 * It used to be a bare red text link at the foot of the settings index, which
 * gave the user no warning at all — one stray tap while scrolling and
 * everything was gone. Here the card says what "everything" means, the note
 * says it cannot be undone, and the button does not delete anything itself: it
 * asks first ([onClear] opens the confirmation dialog).
 */
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
                    // Wake the queue so raising the limit immediately starts
                    // the extra downloads, instead of waiting for the next
                    // enqueue/resume to restart the service.
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
                // One step per press of the D-pad, run through the SAME commit
                // the drag's release runs (see onValueChangeFinished above), so a
                // remote really changes the setting. See [Modifier.tvAdjust].
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
                    // 1%-steps, not 10%: the whole point of this slider is to sit
                    // between two sizes ("…101%, 102%…"), and a 10%-step slider
                    // could not land there at all.
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
                // One step per press of the D-pad, run through the SAME commit
                // the drag's release runs (see onValueChangeFinished above), so a
                // remote really changes the setting. See [Modifier.tvAdjust].
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

/**
 * The floating bottom bar, one switch per tab (the list is [BottomTabs], i.e.
 * the same list the bar itself draws).
 *
 * Hiding a tab only hides its *button* — the screen stays reachable from inside
 * the app (Home's search icon, a Continue Watching row, a download button …),
 * so nobody can lock themselves out of History or Downloads by tidying the bar.
 * The one hard rule is that the bar never becomes empty: the last visible tab
 * cannot be switched off. If that last tab is Settings, a gear appears in
 * Home's top bar so this screen stays reachable.
 */
@Composable
private fun TaskbarCard(app: HikariApp) {
    val scope = rememberCoroutineScope()
    val hiddenFlow = remember { app.store.hiddenTabsFlow() }
    val hidden by hiddenFlow.collectAsState(initial = emptySet())
    // The IPTV button is the one tab that is OFF until it is switched on here
    // (see AppStore.iptvTabFlow) — every other tab is on unless switched off.
    val iptvFlow = remember { app.store.iptvTabFlow() }
    val iptvTab by iptvFlow.collectAsState(initial = false)
    // Manga is the other way round: its button is ON by default (see
    // AppStore.mangaTabFlow), so the reading half of the app is reachable
    // without hunting through settings, and a user who does not read comics
    // switches it off here.
    val mangaFlow = remember { app.store.mangaTabFlow() }
    val mangaTab by mangaFlow.collectAsState(initial = true)
    // Stats is the third off-by-default button (see AppStore.statsTabFlow).
    val statsFlow = remember { app.store.statsTabFlow() }
    val statsTab by statsFlow.collectAsState(initial = false)
    // Telegram is the fourth off-by-default button (AppStore.telegramTabFlow):
    // the videos on the public Telegram channels the user added.
    val telegramFlow = remember { app.store.telegramTabFlow() }
    val telegramTab by telegramFlow.collectAsState(initial = false)
    fun shown(tab: BottomTab): Boolean = when (tab.route) {
        Routes.IPTV -> iptvTab
        Routes.MANGA -> mangaTab
        Routes.STATS -> statsTab
        Routes.TELEGRAM -> telegramTab
        else -> tab.route !in hidden
    }
    // The tabs that obey "the last one cannot be switched off" rule. IPTV,
    // Manga, Stats and Telegram are not among them: they are extra pages, so
    // switching any of them on or off can never leave the user without a way
    // around the app.
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
                        // My Stuff is really THREE pages behind one button, so
                        // its own sections get their own switches directly under
                        // it — see MyStuffSections below.
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
            // ---- My Stuff's own sections ----
            //
            // Drawn only while the My Stuff button itself is on: the switches
            // below it configure a page the user cannot currently reach, and a
            // row of them under a switched-off tab reads as a setting that does
            // nothing. Every one of the three can be switched off, except the
            // last one still on — the strip always keeps at least one pill
            // (see MyStuffScreen, which also draws its section on screen even
            // when it has been hidden, so a deep link can never land on a page
            // with no way out).
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
            // A switch is only disabled while it would be the LAST one on: that
            // is the case it must not be possible to turn off. It must NOT be
            // disabled when it is already off — `libraryOn && onCount > 1` did
            // exactly that, so the moment a section was switched off its switch
            // went dead and could never be switched back on.
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
            Text(
                tr("One section always stays — the page would otherwise be empty."),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 4.dp),
            )
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
            Text(
                supporting,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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

/**
 * The app-wide font.
 *
 * One choice repaints every Compose screen at once (the theme's typography is
 * rebuilt around the chosen family — see [com.hikari.app.ui.theme.typographyWith])
 * and the View-based half of the app — the player, its dialogs, the built-in
 * browser — through [AppFonts.applyToViewTree], so the whole app speaks in one
 * typeface rather than just the parts written in Compose.
 *
 * The last entry is a font the user brings from their own storage. It is copied
 * into the app's private directory on import, which is why it keeps working
 * after the document picker's permission has expired — and why the player can
 * read it during its own Activity creation.
 */
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

/**
 * Poster & icon styling — the dynamic iOS-style blur that lifts a poster off
 * the page, the corner rounding, and whether the title/score are drawn at all.
 *
 * Every grid in the app renders through [com.hikari.app.ui.PosterArt] and reads
 * these values live, so what is set here is what the next frame shows — no
 * restart, and the same look on Home, Search, Library and a collection alike.
 */
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
    val glassFlow = remember { app.store.posterGlassFlow() }
    val glass by glassFlow.collectAsState(initial = true)
    val effectsFlow = remember { app.store.posterEffectsFlow() }
    val effects by effectsFlow.collectAsState(initial = emptySet())
    // The aura ring's own colour (Settings → App Layout → Poster styling →
    // Aura ring colour): "Accent" follows the app accent, which is what the
    // ring always drew, so an existing look is unchanged on update.
    val auraFlow = remember { app.store.posterAuraColorFlow() }
    val auraColor by auraFlow.collectAsState(initial = AuraColors.THEME)
    var effectPicker by remember { mutableStateOf(false) }
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
        // The motion/decoration layer: static looks (a glow, a spotlight, a
        // framed print, a 3D lean) and the animated ones (the light sweep, the
        // breathing aura). Any combination of them can be on at once — several
        // is how the reference look is actually built (a gallery frame WITH a
        // sheen, a 3D lean WITH an aura ring).
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
        // The aura ring's own colour, offered only while that ring is on: a
        // colour row for a ring that is not being drawn would be noise.
        // The edge light's own controls, offered only while the light is one of
        // the card's treatments — two sliders for a light that is not being
        // drawn would be noise (see [PosterEffects.LIT], and [EdgeLightControls]).
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
        // The two tags in the poster's top-left corner. They stack downwards from
        // that corner, so switching both on shows one above the other and
        // switching one on shows it at the top — never "one of them down below"
        // on some posters and at the top on others.
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
}

/**
 * The poster edge light's two controls: a card the reader points AT to place the
 * light, and a strength slider (see [PosterEffects.LIT]).
 *
 * A card rather than a pair of X/Y sliders, because "where is the light" is one
 * question with an obvious gesture — pointing at the place — and a light placed
 * by reading two numbers is a light nobody places. The preview draws the real
 * thing: the same [com.hikari.app.ui.edgeLight] modifier every poster in the app
 * uses, over a stand-in card, so what is set here is what a grid shows.
 *
 * The point being dragged is held locally and written when the gesture ENDS. A
 * DataStore write per frame would fight the finger it is following, and the
 * preview has to move at the speed of the touch to be usable at all.
 */
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
        // A write that lands while a finger is on the card is this gesture's own
        // echo coming back, and snapping the dot to it mid-drag is the dot
        // fighting the hand.
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
                // A stand-in for a poster: dark, with a little vertical
                // structure so a light placed on it is obviously reaching the
                // top of the card or the bottom.
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
            // The light itself: the dot the reader drags. Offset by the same
            // fractions the effect draws with, so the dot IS where the light is
            // rather than near it.
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

/**
 * Which of the two layouts this install draws.
 *
 * "Automatic" is the shipped behaviour and the right answer for almost
 * everybody: the layout is chosen from the device itself (see
 * [com.hikari.app.tv.TvMode] — the Android TV/Leanback features, a Fire TV
 * marker, or simply the absence of a touchscreen). The two explicit choices are
 * the escape hatch, and they exist because device detection is a set of
 * heuristics: an unusual Android box can report none of the television signals
 * and would then be handed a taskbar no remote can press, and a phone with a
 * desktop-mode dock could claim to be one.
 */
@Composable
private fun TvDeviceCard(app: HikariApp) {
    val scope = rememberCoroutineScope()
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
                    scope.launch { runCatching { app.store.setTvMode(pick) } }
                },
                onDismiss = { menuOpen = false },
            )
        }
        Text(
            tr(
                "One APK, two layouts. The TV layout puts the tabs in a rail down " +
                    "the left, draws everything bigger and pads it away from the " +
                    "screen edges, and follows the remote's arrow keys. Switching " +
                    "this takes effect immediately — no restart."
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            // This paragraph is the card's LAST child, and a [GlassCard] is
            // clipped to its rounded shape with no padding of its own: with only
            // a top inset the copy was drawn hard against the glass's left edge
            // and its final line ran under the rounded bottom corner, so the
            // explanation read as cut off ("the written explanation is not
            // showing, it's cut off"). It gets the same 16dp the card's rows use,
            // plus a bottom inset of its own.
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 14.dp),
        )
    }
}

/**
 * The safe area: how far content is kept from the edge of the screen.
 *
 * Televisions have cropped a few percent off the picture since the CRT days,
 * and plenty of modern sets and HDMI switches still do. Anything drawn hard
 * against the edge is simply not there on those screens — which is how a back
 * button ends up half off the left side of a Fire TV. The default (see
 * [TvUi.DEFAULT_OVERSCAN_DP]) keeps every control well inside; the slider is
 * here because the right number belongs to the television, and a set that shows
 * the whole frame wants 0.
 */
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

/**
 * The television performance mode: draw the plain posters.
 *
 * A TV stick is decoding 1080p with a chip a phone would have called slow, and
 * the poster treatments (an animated frame, a sheen sweep, an aura) are drawn
 * per card, per frame, on top of that. On is the shipped default on a
 * television (see HikariApp's first-run seeding) and it also drops the blurred
 * halo behind each card; off restores whatever the poster settings say.
 */
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
                        // The switch is the user's answer now: the app stops
                        // following the layout and keeps whatever they chose
                        // (see HikariApp.syncTvPerformance).
                        app.store.setTvPerfChosen(true)
                    }
                }
            },
        )
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

/**
 * What the remote does, written down.
 *
 * A remote has up to a dozen buttons and no touchscreen; the player's gestures
 * (drag for brightness, double-tap to seek, hold for 2×) do not exist there, so
 * without this card the television controls are invisible knowledge.
 */
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
        // A remote has no drag, so the slider takes the D-pad: left/right step
        // it by one of its OWN steps (the same number the thumb snaps to), and
        // the change is finished on every press so the setting is really
        // saved rather than merely redrawn. Without this the whole row was
        // unreachable: it is a wide pointer control, and nothing moved it.
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
            // Only when a switch actually needs explaining: a caption under
            // every one of them made the folder pages read like a manual.
            if (supporting.isNotBlank()) {
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
        // The switch answers the D-pad itself — see [Modifier.tvToggle]: on a
        // television this small right-hand target is otherwise skipped over by
        // the focus search, and a setting that cannot be turned on or off is
        // the whole complaint about toggles and remotes.
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.tvToggle(checked, onValueChange = onCheckedChange),
        )
    }
}

/** A card's leading row: the icon token plus the card's own heading. */
@Composable
private fun SettingsCardHeading(icon: ImageVector, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * Ratings (Settings → App Layout): the IMDb / RT / Metacritic strip on a detail
 * page, and the small score badge on each poster. Off means neither is drawn and
 * no rating lookups are made. On by default.
 */
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

/**
 * The Featured banner on Home (Settings → App Layout): four ways to present the
 * big carousel at the top of the page, plus the toggles for what it says.
 *
 * The three toggles are separate keys rather than a fixed bundle because they
 * are genuinely independent: someone may want the plot line but not the score
 * chips, or the metadata badges with neither. Styles that have no room for a
 * piece simply ignore its toggle (Compact has no room for a plot line), which is
 * noted in each style's description.
 */
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

    Column(Modifier.padding(16.dp)) {
        SettingsCardHeading(Icons.Filled.ViewCarousel, tr("Featured banner"))
        ChoiceRow(
            value = tr(HeroStyles.label(style)),
            supporting = tr(HeroStyles.description(style)),
            leadingIcon = Icons.Filled.ViewCarousel,
            onClick = { pickerOpen = true },
        )
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

/**
 * The header of a title page (Settings → App Layout): five shapes for the
 * artwork a detail screen opens with — the wide cinematic band, a side-by-side
 * band with the poster on the left, portrait art, the poster over its own
 * backdrop, or a plain back button (for people who would rather have the
 * information than the picture).
 */
@Composable
private fun DetailHeaderCard(app: HikariApp) {
    val scope = rememberCoroutineScope()
    val flow = remember { app.store.detailHeroStyleFlow() }
    val style by flow.collectAsState(initial = DetailHeroStyles.WIDE)
    var pickerOpen by remember { mutableStateOf(false) }
    // The title logo's own size (Settings → App Layout → Details header): the
    // wordmark art a title page draws over its header. Held locally while the
    // finger is on it and written when the drag ends — the same pattern as the
    // poster sliders above — but in SINGLE-percent steps (50%…160%), because
    // "a bit bigger" is not a 10% jump.
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
        Text(
            tr("How big the title artwork is drawn — over the header image, and as the pinned title once it scrolls up"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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

/**
 * The Player UI skin (Settings → Player): four looks for the playback controls.
 * Default is Hikari's own curved-glass look — the one the app shipped with.
 *
 * It applies to the NEXT playback session rather than the one already running —
 * the controller is built once when the player opens, and rebuilding it
 * mid-playback would reset the position, tracks and subtitles for a cosmetic
 * change. The card says so, so the delay is expected rather than a bug.
 */
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
 * Full screen app mode (Settings → App Layout). Hikari normally hides the
 * phone's status bar and its three buttons so a page fills the screen; this
 * switch brings them back, everywhere (the player's own fullscreen video is
 * untouched — that is a separate, per-video choice).
 */
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

/**
 * The bottom bar's layout (Settings → App Layout → Taskbar & navigation): the
 * animated bar (the default — a full labelled bar that shrinks to a small pill
 * as you scroll back up), a fixed floating glass pill, or a seamless
 * edge-to-edge plate.
 *
 * The tabs themselves are chosen by [TaskbarCard] right above; this only
 * changes the chrome around them, and it is stored under its own key so the two
 * settings never fight.
 */
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

/**
 * Which language TMDB titles are fetched in.
 *
 * "Follow app language" is the default and the interesting one: change the app
 * to Spanish and every TMDB row — search results, a studio's films, the detail
 * page's similar shelf, a collection's source — comes back with Spanish titles,
 * because the chosen code rides along on every TMDB request (see
 * [com.hikari.app.nuvio.TmdbResolver.contentLanguage]). "Off" pins TMDB to its
 * own default so the titles stay as released; the explicit list is for the
 * people who want, say, Japanese titles inside an English app.
 */
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

/**
 * Rasterises a launcher icon resource into a square bitmap. Needed because
 * `painterResource` only understands vector and raster XML: the v2…v11
 * launcher icons resolve to an adaptive-icon XML inside `mipmap-anydpi-v26`,
 * which sends Compose's vector loader into an
 * IllegalArgumentException. Drawing the Drawable the way the launcher would is
 * also the more faithful preview — it covers the adaptive layer, a plain
 * vector (the pre-API-26 fallback for the official icon) and a plain bitmap
 * identically. Null on any failure, so a missing icon can never crash Settings.
 */
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

/**
 * Launcher-icon picker. Each tile is drawn from the launcher mipmap itself,
 * masked like a home-screen icon, so what the user taps is what they get.
 *
 * Switching writes the choice to the store and then flips the enabled
 * activity-alias in [AppIconManager]; the app is not restarted, and some
 * launchers only repaint their cached icon after a moment (or a restart), which
 * the note at the bottom warns about.
 */
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
                        // The preview drawable is the composed icon square
                        // (background + artwork with its margin), drawn 1:1 in
                        // the box — deliberately *not* zoomed to imitate the
                        // launcher's mask, which is what made the artwork look
                        // chopped in an earlier build.
                        // Rasterised by hand rather than with painterResource:
                        // the v2…v11 mipmaps resolve to an `<adaptive-icon>` XML
                        // on API 26+, which Compose's vector loader refuses.
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

/**
 * DNS mode: which resolver Hikari asks for names, for everything it does.
 *
 * Every HTTP client the app builds — source searches, extension and repo
 * downloads, stream probes, playback, the userscript runtime — resolves through
 * [DohDns], so a choice here changes the whole app's name lookups rather than
 * just some corner of it. The phone's own resolver can't be replaced by an app
 * (it belongs to the system), so the options are servers Hikari talks to itself:
 * the encrypted DNS-over-HTTPS ones, each carrying the addresses of its own
 * server so the choice still works on a device whose resolver is the thing
 * that's broken, plus the one plain port-53 server for the resolver that never
 * added an encrypted service.
 *
 * The test row exists because the alternative is finding out a typed address is
 * wrong from a failed source search later on.
 */
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

    LaunchedEffect(Unit) {
        waitServers = app.store.playWaitServers()
        minServers = app.store.playMinServers().toFloat()
        askServer = app.store.askServerOnPlay()
        failoverAsk = app.store.failoverAskOnFailure()
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = !waitServers, onClick = { persist(false) })
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = waitServers, onClick = { persist(true) })
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
                // One step per press of the D-pad, run through the SAME commit
                // the drag's release runs (see onValueChangeFinished above), so a
                // remote really changes the setting. See [Modifier.tvAdjust].
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
    }
}

/**
 * Settings → Playback & Servers → Server search.
 *
 * The CloudStream-shaped question: when a title is opened, whose servers may it
 * be played from? On (the default, and how Hikari has always worked) every
 * installed extension is searched and the player's "Select server" list gathers
 * what all of them found. Off, a lookup never leaves the extension the title was
 * opened from — its own repo and its own hosts, nothing else — which is what
 * CloudStream itself does, and what someone with a hundred installed extensions
 * may well prefer: a definite source, no minute-long cross-search, no servers
 * from repos they did not ask.
 *
 * "Exception extensions" sits between the two: with "search all" off, the repos
 * picked here are STILL asked for every title played anywhere else, so a couple
 * of favourite repos stay in play without turning the whole cross-search back on.
 * The one rule that goes with it — a title opened FROM an exception repo plays
 * from that repo alone — is spelled out in the two lines under the picker and
 * implemented in [com.hikari.app.data.SearchScope].
 *
 * This is not only the cross pass: the background sweep and the "borrow another
 * site's episode list" fallback are part of the same behaviour and are switched
 * off with it — except for the exception repos, which are part of both.
 */
/**
 * Engines whose repos can be installed many times over and therefore get their
 * own "search every <engine>" row in the Server search card (see
 * [ServerSearchCard]).
 *
 * Nuvio and Stremio are excluded because they have their own switches above;
 * IPTV and Manga are excluded because "every repo of this engine" says nothing
 * useful about them — an IPTV playlist's channels are already all asked, and a
 * manga source never resolves a video (see [ContentRepository]).
 */
private val ProviderType.isFamilyEngine: Boolean
    get() = this != ProviderType.NUVIO && this != ProviderType.STREMIO &&
        this != ProviderType.IPTV && this != ProviderType.MANGA

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
    val exceptionOnFlow = remember { app.store.searchExceptionOnFlow() }
    val exceptionOn by exceptionOnFlow.collectAsState(initial = false)
    val exceptionIdsFlow = remember { app.store.searchExceptionIdsFlow() }
    val exceptionIds by exceptionIdsFlow.collectAsState(initial = emptySet())
    // Whole engines marked as exceptions, and the EFFECTIVE set (the explicit
    // picks plus every extension of a marked engine) — what the picker shows as
    // ticked, so what is on screen is what a search will actually ask.
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

    // Every installed extension, so the picker can list them (and search them by
    // name or engine). Read live: an install/uninstall while Settings is open is
    // reflected without a reload.
    val installed by app.providers.providers.collectAsState()
    val installedEnabled = installed.filter { it.config.enabled }
    val chosenHere = installedEnabled.filter { it.config.id in exceptionIds }
    // The engines that have at least one installed extension — one chip each,
    // plus "All". Marking an engine keeps applying to whatever of that engine is
    // installed later, and several engines can be marked at once.
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
        Spacer(Modifier.height(14.dp))

        // ---- The nuvio family ----
        // The one widening that does not depend on the switch above: a title
        // opened FROM a nuvio provider is also asked in every other installed
        // nuvio provider, because they all resolve the same TMDB id and episode
        // — one source of servers, not a cross-search of unrelated sites. See
        // [com.hikari.app.data.SearchScope.nuvioFamily]. Off, "only this
        // extension" means exactly that for nuvio too.
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

        // ---- The Stremio family ----
        // The addon twin of the nuvio switch above: a title opened FROM a
        // Stremio addon is asked in every other installed Stremio addon, because
        // they all resolve the same imdb/tmdb id. See
        // [com.hikari.app.data.SearchScope.stremioFamily]. Off, "only this
        // extension" means exactly that for a Stremio origin.
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
        }

        Spacer(Modifier.height(14.dp))

        // ---- Exception extensions ----
        // The middle ground between the two models above: keep "only this
        // extension" on almost everywhere, but name a couple of repos that are
        // always asked. See com.hikari.app.data.SearchScope for the pass this
        // drives.
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
            Text(
                tr(
                    "Any title you play from another extension is also searched through the repos " +
                        "you pick here — even with the switch above off — and their servers join " +
                        "the same list."
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                tr(
                    "A title you open INSIDE one of those repos plays from that repo alone: it is " +
                        "never mixed with anything else."
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            tr("Applies from the next search you start."),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (pickerOpen) {
        // Which engine the list is narrowed to ("All engines" = null). Tapping an
        // engine chip both narrows the list to that engine AND marks the whole
        // engine, so "all of CloudStream, except this one" is two taps rather
        // than a hundred and fifty rows.
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
                // One chip per installed engine plus "All". Engines can be mixed
                // freely, and a marked engine covers extensions installed later
                // too — the row-by-row list alone could not express either.
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
}

/** One engine pill in the exception picker (see [ServerSearchCard]). */
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
    // The colour the cover's aura ring is drawn in (Settings → App Layout →
    // Loading screen → Aura ring colour). Handed to the player as a resolved
    // colour, so both screens showing the cover draw the same ring.
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
            // The look of the "finding your server" card — the page the user
            // stares at from the tap until the first frame of video, on BOTH
            // the detail screen and the player (one choice, two screens, so the
            // hand-off between them never changes the design under them).
            ChoiceRow(
                value = tr(LoadingStyles.label(style)),
                supporting = tr(LoadingStyles.description(style)),
                leadingIcon = Icons.Filled.Slideshow,
                onClick = { pickerOpen = true },
            )
            Spacer(Modifier.height(10.dp))
            // The signature treatments over that card: the loading screen's half
            // of Poster styling's effects (sheen, aura ring, gallery frame,
            // accent glow). Works with every style, so the quietest card can
            // wear the same kind of detail a poster card does — and any
            // combination of them can be on at once.
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
            // The title's own WORDMARK on the cover, instead of the text title —
            // the same art the detail page's header draws, so tapping Play never
            // swaps the design under the user. Its size is its OWN slider: the
            // cover is a full screen with a centred title block, while the header
            // is a wide banner, so one number for both always looked wrong on one
            // of them.
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
                    // 111 single-percent positions between 50% and 160%, like the
                    // detail header's slider — and its own setting, so the two
                    // screens are sized apart.
                    steps = 109,
                    onValueChange = { v -> logoSlider = v.roundToInt().toFloat().coerceIn(50f, 160f) },
                    onValueChangeFinished = {
                        val pct = logoSlider.roundToInt().coerceIn(50, 160)
                        logoSlider = pct.toFloat()
                        scope.launch { runCatching { app.store.setLoadingLogoSize(pct) } }
                    },
                )
                Text(
                    tr("How big the wordmark is drawn on the loading cover, as a percentage of its default size"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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

// (The "Universal extraction (yt-dlp)" settings card stood here. It was removed
// in 0.9.1 along with the bundled yt-dlp runtime it controlled — see the
// CHANGELOG. There is nothing left to switch on or off.)

/**
 * Settings → Performance: the ONE switch for a device that cannot keep up.
 *
 * It is deliberately a single toggle with no sub-options. The person who needs it
 * is using an app that is stuttering and wants it to stop — asking them to pick
 * between four renderer settings is asking them to do the diagnosis. What it
 * does is stated in the card instead (they can read it if they want to), and
 * every one of those things is dropped together:
 *
 *  * the blurred halo behind every poster (three artwork layers per card plus a
 *    gaussian blur — the most expensive thing in the UI, and the one that costs
 *    the most while scrolling a row of posters);
 *  * the animated poster and loading treatments;
 *  * the width of a cross-extension search fan-out (fewer extensions asked at
 *    once, in more waves — the same servers are still found, just staggered);
 *  * the number of nuvio engines that may run at once (twelve QuickJS VMs is a
 *    lot to ask of a phone that is also drawing the screen).
 *
 * Nothing it turns off changes what can be PLAYED. The television gets the same
 * treatment from its own switch in TV & Remote; the two are OR'd (see
 * [com.hikari.app.data.PerfMode]), so a box with both on simply stays light.
 */
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

/**
 * The in-app lock (Settings → Privacy & Browsing → App lock).
 *
 * Two halves, and the order matters: a PASSWORD always has to exist for the
 * lock to be switchable on at all, and the fingerprint/face is an additional,
 * optional way in on top of it. That is the user's own rule — "have to choose
 * at least one password type" — and it is also the only shape that cannot lock
 * someone out of their own app on a phone whose sensor was never enrolled.
 *
 * There is no recovery, and the card says so BEFORE the password is set: what
 * is stored is a PBKDF2 derivation (see [AppLock]), so nothing in the app can
 * read the password back. The way out of a forgotten password is clearing the
 * app's data, exactly like every other app lock.
 */
@Composable
private fun AppLockCard(app: HikariApp) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lockFlow = remember { app.store.appLockFlow() }
    val on by lockFlow.collectAsState(initial = false)
    val secretFlow = remember { app.store.appLockSecretFlow() }
    val secret by secretFlow.collectAsState(initial = "")
    // How long the password is, recorded with it (see AppStore.APP_LOCK_LEN) so
    // the unlock screen draws the right number of dots and submits on the last
    // one instead of guessing.
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
    // Whether this device can offer the fingerprint at all: no sensor, nothing
    // enrolled, or a biometric stack that says no all read as "not available",
    // and the toggle is then explained rather than shown as a switch that can
    // never work.
    val biometrics = remember(context) { Biometrics.available(context) }
    var setDialog by remember { mutableStateOf(false) }
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    // What the user says the CURRENT password is. Only asked for when one
    // exists, and checked (never stored) before anything is changed: a phone
    // that is already unlocked should still not let a passer-by replace the
    // lock with one of their own — "if the real user who set old password is
    // changing it" has to be the one doing the changing.
    var current by remember { mutableStateOf("") }
    var oldWrong by remember { mutableStateOf(false) }
    // True while the dialog was opened by "Remove password" rather than by
    // "Set/Change": the same dialog serves both, because both need exactly the
    // same proof (the current password) and only differ in what happens next.
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
                // Switching it on with no password yet must not leave the app in
                // a lock that cannot be opened: the password comes first, and the
                // switch is turned on when it is saved.
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
            // The leaving trigger's own switch, the twin of the screen-off one
            // above: with it off, switching away from Hikari never draws the
            // unlock card — only the screen turning off (if that is on) or a
            // fresh start of the app does.
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
                // The grace period: Instant, then every minute up to 60. A slider
                // with one step per minute rather than a list of presets, because
                // that is the shape asked for and because 61 points covers the whole
                // range with no rounding between the control and the stored value.
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
                Text(
                    tr(
                        "How long the app may stay unlocked after you leave it. \"Instantly\" locks " +
                            "the moment you leave the app or the screen turns off."
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
            // The lock is on but the stored password cannot be read: this is the
            // state an install that set its password before the encoding fix is
            // in (see [AppLock.encode]) — the unlock screen cannot accept any
            // password, so the card says so and offers the way out in one tap
            // rather than letting the user keep typing into a lock that is
            // already broken.
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
                            // Says how long the current one is, which is what the
                            // unlock keypad's dots show too — the field itself
                            // cannot be read back (see [AppLock]).
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
                                // The old password is verified BEFORE anything is
                                // written, and only ever a verification — it is
                                // never stored, and a failure changes nothing.
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
                                    // The length travels with the secret: the
                                    // unlock screen's dots and its "this is the
                                    // last digit" both come from it.
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

/**
 * Trackers (Settings → Trackers).
 *
 * The services the user already keeps a list on — AniList, MyAnimeList, Kitsu,
 * Simkl, Shikimori and Trakt — and the switch that reports what they watch to
 * them. The feature the user asked for by name ("login with their id for
 * tracking, like CloudStream"): sign in once, and the episode you just finished
 * lands on your list without opening anything.
 *
 * Two things the card is careful to be honest about, because both are the
 * difference between a tracker that works and one that *looks* like it works:
 *  * the credentials are the user's own ([TrackerKind.registerUrl] — why is
 *    explained at length on [TrackerKind]), and the card says so plainly rather
 *    than pretending a sign-in is one tap away;
 *  * what happens is always written down under the buttons (the last run's
 *    result, which failures included), so a title that could not be matched, or
 *    a service that refused the token, is visible instead of silent.
 */
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

        for (kind in TrackerKind.entries) {
            val account = accounts.firstOrNull { it.kind == kind }
            val client = clients.firstOrNull { it.kind == kind } ?: TrackerClient(kind)
            HorizontalDivider(
                modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        kind.label,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        when {
                            account != null ->
                                tr("Signed in as ") + account.user.ifBlank { yourAccount } +
                                    if (account.expired) " · " + tr("refreshing the token…") else ""
                            client.ready -> tr("App registered — press Sign in to connect")
                            else -> kind.blurb
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (account != null) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                Spacer(Modifier.width(8.dp))
                if (account == null) {
                    TextButton(
                        enabled = !working,
                        onClick = { message = null; loginFor = kind },
                    ) { Text(tr("Sign in")) }
                } else {
                    TextButton(
                        enabled = !working,
                        onClick = {
                            scope.launch {
                                runCatching { app.store.removeTrackerAccount(kind) }
                                // `tr` is composable and this is a click handler, so
                                // the suffix is hoisted like every other string that
                                // has to be read outside a composable position.
                                message = kind.label + signedOutSuffix
                            }
                        },
                    ) { Text(tr("Sign out")) }
                }
            }
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
        Text(
            tr(
                "The last 12 watched titles are checked, newest first — a title nobody has " +
                    "matched confidently is reported here instead of being guessed at."
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
    // The System-default entry says what it will actually do rather than always
    // claiming English: on a phone whose own language is Arabic, or for a user
    // who picked Arabic for Hikari in Android's app-language screen, "System
    // default" means Arabic — and it used to say "(English)" either way, which
    // is exactly why a user could be sure they had "selected Arabic in the app"
    // and still see an English interface.
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
        // The pattern every "pick one of several" setting in the app follows:
        // one glass row showing what is in use, tapping it opens a scrollable
        // glass page of choices (see ChoiceRow/ChoiceDialog).
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
            // Persist FIRST (in memory + on the app scope), then hand the locale
            // to the platform: the apply recreates the activity, and a write
            // launched on the dying composition's scope was cancelled by it —
            // which is why the same language had to be picked twice.
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

    LaunchedEffect(Unit) {
        redirectProtection = app.store.webviewRedirect()
        popupProtection = app.store.webviewPopup()
        allowedRedirects = app.store.webviewRedirectAllow()
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
        Text(
            tr("A host (player.example.com), or one word — a word allows any link that contains it, whatever comes after it: add 'filester' and filester.com, filester.gg and filester.sh all work."),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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
    }
}

/**
 * Settings → Backup & Restore: the whole setup (installed extensions, sources
 * and every pref this app owns) in one JSON file, and the way back.
 *
 * What a backup deliberately does NOT contain — offline videos, the poster and
 * adblock caches, the download queue — is documented in BackupManager; the short
 * version is that a backup stays small enough to e-mail to yourself, and a
 * restore can never point the app at a video file this phone does not have.
 */
@Composable
private fun BackupCard(app: HikariApp, onPair: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    // Read here, not in the coroutine below: tr() is a composable, so it cannot
    // be called from inside scope.launch.
    val savedPrefix = tr("Saved to Downloads")

    fun report(r: BackupManager.Report) {
        busy = false
        status = if (r.detail.isBlank()) r.message else r.message + " · " + r.detail
        Toast.makeText(context, r.message, Toast.LENGTH_SHORT).show()
    }

    // Restore goes through the system file picker (SAF) rather than a path: the
    // backup is usually in Downloads or was sent over a chat, and the picker
    // hands back a URI anyone can read — no storage permission needed.
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        status = ""
        scope.launch {
            val result = runCatching {
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }
                if (bytes == null) BackupManager.Report(false, "Could not read that file.")
                else BackupManager.restore(app, bytes)
            }.getOrElse { BackupManager.Report(false, "Backup failed.", it.message.orEmpty()) }
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
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
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
                val bytes = BackupManager.export(app)
                val name = BackupManager.fileName()
                val saved = withContext(Dispatchers.IO) {
                    BackupManager.saveToDownloads(context, bytes, name)
                }
                if (saved == null) {
                    BackupManager.Report(false, "Could not save the backup.")
                } else {
                    BackupManager.Report(
                        true,
                        savedPrefix + "/" + saved,
                        "${(bytes.size + 1023) / 1024} KB · " +
                            "${app.providers.providers.value.size} sources",
                    )
                }
            }.getOrElse { BackupManager.Report(false, "Backup failed.", it.message.orEmpty()) }
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
        Text(
            tr("Never includes your videos or your app lock - and each device keeps its own layout."),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
            // FlowRow so all three presets stay visible (the third wraps to a
            // second line instead of overflowing off the right edge), with no
            // dead space between the chips and the row below.
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
                        // Downloads are blocking okhttp + retries — must NOT run
                        // on the main thread (it froze the app / ANR-crashed).
                        // One bad list can never abort the rest or crash.
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

/** Opens the full-screen Player controls editor. */
@Composable
private fun PlayerControlsCard(onOpen: () -> Unit) {
    Column(Modifier.padding(16.dp)) {
        Text(
            tr("Player controls"),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(6.dp))
        Text(
            tr("Where each button sits, or hide it"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onOpen, shape = GlassShape) {
            Text(tr("Edit control layout"))
        }
    }
}

/** Video enhance preset picker — real GPU colour grading of the video itself. */
@Composable
private fun VideoEnhanceCard(app: HikariApp) {
    val scope = rememberCoroutineScope()
    val presetFlow = remember { app.store.enhancePresetFlow() }
    val presetKey by presetFlow.collectAsState(initial = EnhancePreset.DEFAULT.key)
    var menuOpen by remember { mutableStateOf(false) }
    val preset = EnhancePreset.fromKey(presetKey)

    SettingsSection(
        id = "player.enhance",
        icon = Icons.Filled.AutoAwesome,
        title = tr("Video enhance"),
        summary = tr(preset.label),
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
                        preset.desc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    preset.label,
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
    }

    // Eight presets with a description each: a glass page of choices beats a
    // wall of a Material dropdown menu, and it matches every other "pick one"
    // setting in the app.
    if (menuOpen) {
        ChoiceDialog(
            title = tr("Video enhance"),
            items = EnhancePreset.entries.map {
                ChoiceItem(it.key, tr(it.label), tr(it.desc))
            },
            selectedKey = preset.key,
            onPick = { pick ->
                scope.launch { runCatching { app.store.setEnhancePreset(pick) } }
            },
            onDismiss = { menuOpen = false },
        )
    }
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

/**
 * The aura ring's colour picker — Settings → App Layout → Poster styling /
 * Loading screen → Aura ring colour.
 *
 * A ring is the one poster/loading treatment whose whole character is its
 * colour, so it gets its own choice instead of being nailed to the app accent:
 * "Accent" (first swatch, and the default, so nothing changes for anyone
 * upgrading) follows MaterialTheme's primary, exactly as the ring always drew.
 * The rest are the app's own palette ([HikariAccent]), so the ring can only ever
 * be one of the colours the rest of the app is built from.
 */
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
