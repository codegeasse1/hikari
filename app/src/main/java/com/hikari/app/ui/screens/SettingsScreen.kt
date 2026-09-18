package com.hikari.app.ui.screens
import com.hikari.app.i18n.tr
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestorePage
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.hikari.app.BuildConfig
import com.hikari.app.HikariApp
import com.hikari.app.R
import com.hikari.app.data.BackupManager
import com.hikari.app.data.TmdbLang
import com.hikari.app.data.Userscript
import com.hikari.app.download.DownloadService
import com.hikari.app.download.DownloadStatus
import com.hikari.app.download.DownloadsRepository
import com.hikari.app.net.AdBlocker
import com.hikari.app.net.NetTuning
import com.hikari.app.net.Updater
import com.hikari.app.player.EnhancePreset
import com.hikari.app.ui.AppIconManager
import com.hikari.app.ui.AppIconVariants
import com.hikari.app.ui.AppFonts
import com.hikari.app.ui.components.GlassCard
import com.hikari.app.ui.components.GlassDialog
import com.hikari.app.ui.LanguageManager
import com.hikari.app.ui.components.UpdateDialog
import com.hikari.app.ui.navigation.BottomTabs
import com.hikari.app.ui.navigation.NavStyles
import com.hikari.app.ui.navigation.Routes
import com.hikari.app.ui.openTelegram
import com.hikari.app.ui.theme.HikariAccent
import com.hikari.app.ui.theme.HikariThemeMode
import com.hikari.app.ui.theme.rememberGlassTokens
import com.hikari.app.web.UserscriptManager
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withContext

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
 * busiest ones do, because "Appearance" and "App Layout" had each grown into a
 * long page of unrelated switches.
 *
 * The split: Appearance is only what the app *is* (language, theme, interface
 * scale) plus the two things it wears (icon, colour). Everything about how a
 * page is laid out — titles, posters, fonts, the taskbar — lives under App
 * Layout.
 */
private enum class SettingsFolder(
    /** Stable id. Also what a sub-folder names as its [parent]. */
    val key: String,
    val title: String,
    val subtitle: String,
    val blurb: String,
    val icon: ImageVector,
    /** Id of the folder this one lives inside, or null for a top-level folder. */
    val parent: String? = null,
) {
    APPEARANCE(
        "appearance",
        "Appearance",
        "Language, theme, icon & interface size",
        "How Hikari looks and speaks on this phone.",
        Icons.Filled.Palette,
    ),
    APP_LAYOUT(
        "layout",
        "App Layout",
        "Titles, posters, fonts & navigation",
        "How the app's pages are arranged, and what they look like in the hand.",
        Icons.Filled.Dashboard,
    ),
    PLAYER(
        "player",
        "Player",
        "Playback start, loading screen, slow internet",
        "How the player behaves when you start a video.",
        Icons.Filled.PlayArrow,
    ),
    SOURCES(
        "sources",
        "Sources & Extensions",
        "yt-dlp fallback, userscripts, Continue Watching",
        "How Hikari finds, plays and remembers videos.",
        Icons.Filled.Extension,
    ),
    DOWNLOADS(
        "downloads",
        "Downloads",
        "Offline copies & parallel saves",
        "Saving videos to this device.",
        Icons.Filled.Download,
    ),
    // The user's own catalogs (Collections) live here rather than under
    // Appearance: they are something the user CREATES and manages — like the
    // extensions they install — not a way the app looks, and a folder of their
    // own is where they go looking for it.
    CATALOG(
        "catalog",
        "Personal Catalog creator",
        "Your own collections, folders & catalogs",
        "Build collections out of the catalogs you actually watch.",
        Icons.Filled.FolderOpen,
    ),
    PRIVACY(
        "privacy",
        "Privacy & Browsing",
        "Ad blocking, redirects & user agent",
        "What the built-in browser is allowed to do.",
        Icons.Filled.Shield,
    ),
    LOGS(
        "logs",
        "Logs & Diagnostics",
        "App logs & crash reports",
        "Share what the app recorded, so a bug needs no screenshot.",
        Icons.Filled.BugReport,
    ),
    BACKUP(
        "backup",
        "Backup & Restore",
        "One file with your whole setup",
        "Carry your extensions, sources and settings to another phone.",
        Icons.Filled.SettingsBackupRestore,
    ),
    ABOUT(
        "about",
        "About & Updates",
        "Version, links, roadmap & reset",
        "What this build is, and where it comes from.",
        Icons.Filled.Info,
    ),

    // ---- Sub-folders (never listed on the index; see [parent]) ----

    APPEARANCE_ICON(
        "appearance.icon",
        "App icon",
        "The icon Hikari wears on your home screen",
        "Pick which launcher icon the app uses. The change takes a moment and " +
            "your home screen may need a refresh before the new icon shows.",
        Icons.Filled.Android,
        parent = "appearance",
    ),
    APPEARANCE_COLORS(
        "appearance.colors",
        "App color theme",
        "Accent colours & player matching",
        "The accent colour every screen is painted in, and whether the player " +
            "follows the same one.",
        Icons.Filled.ColorLens,
        parent = "appearance",
    ),
    LAYOUT_TMDB(
        "layout.tmdb",
        "TMDB language titles",
        "Show titles & descriptions in your language",
        "What language TMDB metadata — titles, overviews, artwork text — is " +
            "fetched in. Follows your app language unless you pick one here.",
        Icons.Filled.Translate,
        parent = "layout",
    ),
    LAYOUT_POSTER(
        "layout.poster",
        "Poster styling",
        "Blur, corners, titles & score badges",
        "How the artwork cells in every grid are drawn: the iOS-style blur " +
            "halo, the corner rounding, and whether titles and scores show.",
        Icons.Filled.Wallpaper,
        parent = "layout",
    ),
    LAYOUT_FONT(
        "layout.font",
        "App font",
        "The typeface used everywhere, including the player",
        "Pick a bundled font, or import one from storage and use it across the " +
            "whole app.",
        Icons.Filled.TextFields,
        parent = "layout",
    ),
    LAYOUT_NAV(
        "layout.nav",
        "Taskbar & navigation",
        "Bar layout & which buttons stay",
        "Which tabs the bottom bar carries, and the three layouts it can wear.",
        Icons.Filled.Tune,
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

@Composable
fun SettingsScreen(nav: NavHostController) {
    val context = LocalContext.current
    val app = context.applicationContext as HikariApp
    val scope = rememberCoroutineScope()

    var themeMenuOpen by remember { mutableStateOf(false) }
    var checkingUpdates by remember { mutableStateOf(false) }
    var updateStatus by remember { mutableStateOf<Updater.UpdateStatus?>(null) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var openFolder by remember { mutableStateOf<SettingsFolder?>(null) }
    // A sub-folder inside [openFolder] (Appearance → App icon, App Layout →
    // Poster styling…). Two levels is the whole tree, so two slots is enough and
    // back always has an obvious target.
    var openSub by remember { mutableStateOf<SettingsFolder?>(null) }
    var showPlayerControls by remember { mutableStateOf(false) }
    var showLogs by remember { mutableStateOf(false) }

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

    // A folder opens at its own top: without this, opening one from partway
    // down the index would leave the new page scrolled by the old offset.
    LaunchedEffect(openFolder, openSub) { listState.scrollToItem(0) }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp)
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
                    item { SettingsCard { PlaybackStartCard(app) } }
                    item { SettingsCard { LoadingBannerCard(app) } }
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
                    item { SettingsCard { UniversalExtractionCard(app) } }
                    item { SettingsCard { ContinueWatchingCard(app, hideContinue, scope) } }
                    item { SettingsCard { UserscriptsCard(app) } }
                }
                SettingsFolder.DOWNLOADS -> {
                    item { SettingsCard(top = 2.dp) { DownloadSettingsCard(app) } }
                }
                SettingsFolder.APPEARANCE -> {
                    item { SettingsCard(top = 2.dp) { LanguageCard(app, appLanguage) } }
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
                                DropdownMenu(
                                    expanded = themeMenuOpen,
                                    onDismissRequest = { themeMenuOpen = false }
                                ) {
                                    HikariThemeMode.entries.forEach { mode ->
                                        DropdownMenuItem(
                                            text = { Text(mode.label) },
                                            onClick = {
                                                themeKey = mode.key
                                                themeMenuOpen = false
                                                scope.launch { app.store.setTheme(mode.key) }
                                            },
                                            leadingIcon = {
                                                if (themeKey == mode.key) {
                                                    Icon(
                                                        Icons.Filled.CheckCircle,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.primary
                                                    )
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                    item { SettingsCard { UiScaleCard(app) } }
                    item { SettingsCard { DetailRatingCard(app) } }
                    // The two things the app wears. Each is several choices
                    // wide (a dozen icon aliases, a wall of accent swatches),
                    // so they get their own pages instead of turning Appearance
                    // into a long scroll past everything else.
                    SettingsFolder.entries
                        .filter { it.parent == SettingsFolder.APPEARANCE.key }
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
                    SettingsFolder.entries
                        .filter { it.parent == SettingsFolder.APP_LAYOUT.key }
                        .forEach { target ->
                            item {
                                SettingsFolderRow(
                                    folder = target,
                                    top = 12.dp,
                                    onClick = { openSub = target },
                                )
                            }
                        }
                    item { SettingsCard { FullscreenCard(app) } }
                }
                SettingsFolder.LAYOUT_TMDB -> {
                    item { SettingsCard(top = 2.dp) { TmdbLanguageCard(app, appLanguage) } }
                }
                SettingsFolder.LAYOUT_POSTER -> {
                    item { SettingsCard(top = 2.dp) { PosterStyleCard(app) } }
                }
                SettingsFolder.LAYOUT_FONT -> {
                    item { SettingsCard(top = 2.dp) { FontCard(app) } }
                }
                SettingsFolder.LAYOUT_NAV -> {
                    item { SettingsCard(top = 2.dp) { NavBarCard(app) } }
                    item { SettingsCard { TaskbarCard(app) } }
                }
                SettingsFolder.CATALOG -> {
                    item {
                        SettingsCard(top = 2.dp) {
                            CollectionsCard(onOpen = { Routes.safeNavigate(nav, Routes.COLLECTIONS) })
                        }
                    }
                }
                SettingsFolder.PRIVACY -> {
                    item { SettingsCard(top = 2.dp) { AdBlockingCard(app) } }
                    item { SettingsCard { WebViewSafetyCard(app) } }
                    item { SettingsCard { ExtensionVerifyCard(app) } }
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
                                            tr("Two rolling app logs and the last crash " + "log. Share them directly instead of ") +
                                                I18n.t("sending screenshots.")
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
                            tr("Logs stay on this device and are only sent when you " + "tap Share or Save on the logs page."),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 10.dp, start = 4.dp),
                        )
                    }
                }
                SettingsFolder.BACKUP -> {
                    item { SettingsCard(top = 2.dp) { BackupCard(app) } }
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
                    item {
                        TextButton(
                            onClick = { scope.launch { app.store.clearAll() } },
                            modifier = Modifier.padding(top = 14.dp)
                        ) {
                            Text(tr("Clear all data"), color = MaterialTheme.colorScheme.error)
                        }
                    }
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
            // parent, not from the index.
            SettingsFolder.entries.filter { it.parent == null }.forEach { target ->
                item {
                    SettingsFolderRow(folder = target, onClick = { openFolder = target })
                }
            }
            item {
                TextButton(
                    onClick = { scope.launch { app.store.clearAll() } },
                    modifier = Modifier.padding(top = 16.dp)
                ) {
                    Text(tr("Clear all data"), color = MaterialTheme.colorScheme.error)
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
}

/**
 * The round accent badge a settings row leads with: a circle of accent wash with
 * a hairline ring, so the icon reads as a glass token rather than a flat square.
 * One composable for every one of them, so the index, the folder headers and the
 * shortcut cards can never drift apart.
 */
@Composable
private fun SettingsIconBadge(icon: ImageVector, size: Dp = 46.dp) {
    val accent = MaterialTheme.colorScheme.primary
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(accent.copy(alpha = 0.14f))
            .border(1.dp, accent.copy(alpha = 0.22f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(size * 0.46f),
        )
    }
}

/**
 * The folder page's own header: a back button, the folder's badge and name, and
 * a one-line explanation of what is inside, so a page always says where you are
 * without repeating the settings tab's title.
 *
 * [parentTitle] is set only when a sub-folder is open, and is printed above the
 * title as a breadcrumb — "Appearance › App icon" — so the way back out is
 * obvious without reading the back button's glyph.
 */
@Composable
private fun FolderHeader(
    folder: SettingsFolder,
    parentTitle: String? = null,
    onBack: () -> Unit,
) {
    val glass = rememberGlassTokens()
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(glass.fillTop)
                    .border(1.dp, glass.border, CircleShape)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = tr("Back to settings"),
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            SettingsIconBadge(folder.icon, 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                if (parentTitle != null) {
                    Text(
                        tr(parentTitle) + " ›",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(
                    tr(folder.title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    tr(folder.subtitle),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            tr(folder.blurb),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
    }
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
                    .clip(RoundedCornerShape(14.dp))
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
                    .clip(RoundedCornerShape(10.dp))
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
                    tr("Show the Continue Watching shelf on Home. It collects " + "progress from every extension you've watched, so an ") +
                        I18n.t("episode started on one extension still shows up after ") +
                        I18n.t("you switch to another."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = !hideContinue,
                onCheckedChange = { show ->
                    scope.launch { app.store.setHideContinue(!show) }
                }
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
                I18n.t("• Trakt integration (planned)"),
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
            tr("Hikari (光) — a universal streaming app built from scratch. " + "One player, every extension ecosystem."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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
                    .clip(RoundedCornerShape(12.dp))
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
        Slider(
            value = value,
            onValueChange = { value = it },
            onValueChangeFinished = {
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
            },
            valueRange = 1f..10f,
            steps = 8,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f),
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
            ),
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            tr("Slide to 3 to run three downloads at once, 4 for four, and so on (max 10). " + "Videos beyond the limit stay queued and start automatically as slots free up."),
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
                        tr("Group the catalogs you actually watch into folders, then pick the " +
                            "collection on Home to browse only those.")
                    } else {
                        collections.size.toString() + " " +
                            (if (collections.size == 1) tr("collection") else tr("collections")) +
                            " · " + folders + " " + (if (folders == 1) tr("folder") else tr("folders")) +
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

    Column(Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    tr("In-app UI scale"),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    tr("Force one interface size on every phone"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = { on ->
                    scope.launch { runCatching { app.store.setUiScaleEnabled(on) } }
                }
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            tr("Turning OFF applies your phone's Font size and Display size settings " + "to the app. Turning ON ignores those two phone settings and follows ") +
                I18n.t("the in-app UI scale size below instead, so the app looks the same on ") +
                I18n.t("every device."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
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
            Slider(
                value = slider,
                onValueChange = { slider = it },
                onValueChangeFinished = {
                    val pct = (slider * 100).roundToInt().coerceIn(70, 130)
                    slider = pct / 100f
                    scope.launch { runCatching { app.store.setUiScale(pct) } }
                },
                valueRange = 0.7f..1.3f,
                steps = 5,
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f),
                    activeTickColor = Color.Transparent,
                    inactiveTickColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth()
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
    val visible = BottomTabs.filter { it.route !in hidden }.ifEmpty { BottomTabs }
    val labelsFlow = remember { app.store.tabLabelsFlow() }
    val labels by labelsFlow.collectAsState(initial = true)

    Column(Modifier.padding(16.dp)) {
        Text(
            tr("Taskbar buttons"),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(4.dp))
        Text(
            tr("Choose which tabs the bottom bar shows. Turn one off and its " + "button disappears — the others share the space. ") +
                I18n.t("The screen itself still opens from inside the app, and the last remaining tab always stays."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        SettingsToggle(
            label = tr("Show text on taskbar buttons"),
            supporting = tr("Write each tab's name under its icon. Turn this off to keep the bar icons-only."),
            checked = labels,
            onCheckedChange = { on ->
                scope.launch { runCatching { app.store.setTabLabels(on) } }
            },
        )
        Spacer(Modifier.height(4.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
        BottomTabs.forEach { tab ->
            val shown = tab.route !in hidden
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    tab.icon,
                    contentDescription = null,
                    tint = if (shown) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    tr(tab.label),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = shown,
                    // A hidden tab can always be brought back; a shown one only
                    // while at least one other tab is still on.
                    enabled = !shown || visible.size > 1,
                    onCheckedChange = { on -> scope.launch { app.store.setTabHidden(tab.route, !on) } }
                )
            }
        }
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

    Column(Modifier.padding(16.dp)) {
        Text(
            tr("App font"),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(6.dp))
        Text(
            tr("Applies to the whole app — every screen, the player and the built-in browser."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        AppFonts.CHOICES.forEach { choice ->
            FontRow(label = choice.label, selected = key == choice.key) {
                if (key != choice.key) scope.launch { runCatching { app.store.setAppFont(choice.key) } }
            }
        }
        if (file.isNotBlank()) {
            FontRow(
                label = AppFonts.labelFor(AppFonts.IMPORTED, importedLabel),
                supporting = tr("Imported from your storage"),
                selected = key == AppFonts.IMPORTED,
            ) {
                if (key != AppFonts.IMPORTED) {
                    scope.launch { runCatching { app.store.setAppFont(AppFonts.IMPORTED) } }
                }
            }
        }
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
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(tr(if (file.isBlank()) "Import a font from storage" else "Replace the imported font"))
        }
        Spacer(Modifier.height(4.dp))
        Text(
            tr("Tip: .ttf and .otf files from your Downloads folder work best."),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** One radio row in the font picker. */
@Composable
private fun FontRow(
    label: String,
    selected: Boolean,
    supporting: String? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (!supporting.isNullOrBlank()) {
                Text(
                    supporting,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
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
    val glassFlow = remember { app.store.posterGlassFlow() }
    val glass by glassFlow.collectAsState(initial = true)

    var blurSlider by remember { mutableStateOf(blur.toFloat()) }
    var cornerSlider by remember { mutableStateOf(corner.toFloat()) }
    LaunchedEffect(blur) { blurSlider = blur.toFloat() }
    LaunchedEffect(corner) { cornerSlider = corner.toFloat() }

    Column(Modifier.padding(16.dp)) {
        Text(
            tr("Poster & icon styling"),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(6.dp))
        Text(
            tr("How artwork is drawn in every grid — Home, Search, Library and your collections."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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
            tr("A soft coloured halo behind each poster, the way the reference client does it."),
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
            supporting = tr("The name under each poster."),
            checked = titles,
            onCheckedChange = { on -> scope.launch { runCatching { app.store.setPosterShowTitles(on) } } },
        )
        SettingsToggle(
            label = tr("Show ratings"),
            supporting = tr("A score badge on posters that have one (TMDB titles)."),
            checked = ratings,
            onCheckedChange = { on -> scope.launch { runCatching { app.store.setPosterShowRatings(on) } } },
        )
        SettingsToggle(
            label = tr("Glass trim"),
            supporting = tr("The hairline and frosted backing every card in the app shares."),
            checked = glass,
            onCheckedChange = { on -> scope.launch { runCatching { app.store.setPosterGlass(on) } } },
        )
    }
}

/** A labelled slider with its value on the right — used by [PosterStyleCard]. */
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
        modifier = Modifier.fillMaxWidth(),
    )
}

/** A switch row with a caption — the shape every toggle in this folder uses. */
@Composable
private fun SettingsToggle(
    label: String,
    supporting: String,
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
            Text(
                supporting,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
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
 * Ratings (Settings → Appearance): the IMDb / RT / Metacritic strip on a
 * detail page, and the small score badge on each poster. Off means neither is
 * drawn and no rating lookups are made. On by default.
 */
@Composable
private fun DetailRatingCard(app: HikariApp) {
    val scope = rememberCoroutineScope()
    val flow = remember { app.store.showDetailRatingFlow() }
    val show by flow.collectAsState(initial = true)

    Column(Modifier.padding(16.dp)) {
        SettingsCardHeading(Icons.Filled.Star, tr("Ratings"))
        SettingsToggle(
            label = tr("Show ratings"),
            supporting = tr(
                "The IMDb / Rotten Tomatoes scores on a detail page, and the " +
                    "small score badge on each poster. Turning it off skips the " +
                    "rating lookups too."
            ),
            checked = show,
            onCheckedChange = { on ->
                scope.launch { runCatching { app.store.setShowDetailRating(on) } }
            },
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
            supporting = tr(
                "Keep the phone's status bar and its three buttons visible on " +
                    "every screen. Off keeps the app edge-to-edge."
            ),
            checked = off,
            onCheckedChange = { value ->
                scope.launch { runCatching { app.store.setFullscreenOff(value) } }
            },
        )
    }
}

/**
 * The bottom bar's layout (Settings → Appearance → Navigation bar): the
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

    Column(Modifier.padding(16.dp)) {
        Text(
            tr("Navigation bar"),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(6.dp))
        Text(
            tr("Choose how the bottom bar is drawn. Which buttons it shows is set above."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        NavStyles.ALL.forEach { option ->
            val on = style == option.key
            val pick = { scope.launch { runCatching { app.store.setNavStyle(option.key) } } }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { pick() },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = on, onClick = { pick() })
                Column(Modifier.weight(1f)) {
                    Text(
                        tr(option.label),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        tr(option.blurb),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
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

    Column(Modifier.padding(16.dp)) {
        Text(
            tr("Title language (TMDB)"),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(6.dp))
        Text(
            tr("Titles and overviews from TMDB are fetched in this language, so the same " +
                "movie reads correctly after you change the app language."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        NavOptionRow(
            label = tr("Follow app language"),
            supporting = if (followed.isBlank()) {
                tr("No TMDB translation for the current app language — English is used.")
            } else {
                tr("Currently") + ": " + followed
            },
            selected = saved.isBlank(),
            onClick = { scope.launch { runCatching { app.store.setTmdbLanguage("") } } },
        )
        NavOptionRow(
            label = tr("Off (TMDB default)"),
            supporting = tr("Keep the titles exactly as TMDB releases them."),
            selected = saved == "none",
            onClick = { scope.launch { runCatching { app.store.setTmdbLanguage("none") } } },
        )
        Spacer(Modifier.height(6.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
        Spacer(Modifier.height(6.dp))
        TmdbLang.CHOICES.forEach { (code, name) ->
            NavOptionRow(
                label = name,
                supporting = code,
                selected = saved == code,
                onClick = { scope.launch { runCatching { app.store.setTmdbLanguage(code) } } },
            )
        }
    }
}

/** A radio row used by the navigation-bar and title-language pickers. */
@Composable
private fun NavOptionRow(
    label: String,
    supporting: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                supporting,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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

    Column(Modifier.padding(16.dp)) {
        Text(
            tr("App icon"),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(4.dp))
        Text(
            tr("Pick the icon your launcher shows for Hikari."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(14.dp))
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
            tr("Some launchers take a moment to refresh the icon. If yours keeps the old one, restart it or remove and re-add the shortcut."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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

    Column(Modifier.padding(16.dp)) {
        Text(
            tr("Mobile data / slow internet"),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(6.dp))
        Text(
            tr("Gives every source search much more time and retries extensions " + "that time out, so a weak connection doesn't end in ") +
                I18n.t("\"No playable sources found\". Only turn it on if you need it — ") +
                I18n.t("fast connections stay quick with it off."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    tr("Slow connection mode"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    tr("Enable this if your internet is slow."),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = {
                    enabled = it
                    NetTuning.setSlowConnection(it)
                    scope.launch { runCatching { app.store.setSlowConnection(it) } }
                }
            )
        }
        Spacer(Modifier.height(14.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    tr("Slow internet suggestion"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    tr("When a video looks slow to start, the player offers to switch " + "Slow connection mode on. Turn this off if it keeps guessing ") +
                        I18n.t("wrong on a connection that is actually fine."),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = tipEnabled,
                onCheckedChange = {
                    tipEnabled = it
                    scope.launch { runCatching { app.store.setSlowTipEnabled(it) } }
                }
            )
        }
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

    Column(Modifier.padding(16.dp)) {
        Text(
            tr("Playback start"),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(6.dp))
        Text(
            tr("Choose when the player starts after you tap Play."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = !waitServers, onClick = { persist(false) })
            Column(Modifier.weight(1f)) {
                Text(
                    tr("Play as soon as the first server is found"),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    tr("Instant playback — the fastest option. If that server turns out " + "to be dead, the player moves to the next one automatically."),
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
                    tr("Playback starts once the number chosen below has been found — or " + "when every installed extension has finished searching, ") +
                        I18n.t("whichever happens first."),
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
            Slider(
                value = minServers,
                onValueChange = { minServers = it },
                onValueChangeFinished = {
                    val n = minServers.roundToInt().coerceIn(1, 5)
                    minServers = n.toFloat()
                    scope.launch { runCatching { app.store.setPlayMinServers(n) } }
                },
                valueRange = 1f..5f,
                steps = 3,
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f),
                    activeTickColor = Color.Transparent,
                    inactiveTickColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                tr("Slide to 3 to start once three servers are ready. If the whole " + "search finds fewer than that (say only 2), playback starts with ") +
                    I18n.t("everything that was found the moment every extension has ") +
                    I18n.t("finished — it never waits forever for a server that doesn't exist."),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    tr("Don't play directly — show all servers to choose"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    if (askServer) {
                        "On — tapping Play stops at the server list instead of " +
                            "starting a server by itself. Every server found is " +
                            "divided into sections by the engine it came from " +
                            "(CloudStream, Hikari, Nuvio, Stremio) so you can pick " +
                            "one deliberately."
                    } else {
                        "Off — the player starts on the first server it finds and " +
                            "only moves to another one if that server turns out to " +
                            "be dead."
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
                }
            )
        }

        // Only meaningful when the player is choosing a server by itself: when a
        // server the USER picked fails, should Hikari switch on its own or ask?
        if (!askServer) {
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        tr("Ask me when a chosen server fails"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        tr(
                            "When a server you picked yourself fails, Hikari offers to try " +
                                "the next one or lets you choose another — instead of " +
                                "switching silently."
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = failoverAsk,
                    onCheckedChange = {
                        failoverAsk = it
                        scope.launch { runCatching { app.store.setFailoverAskOnFailure(it) } }
                    }
                )
            }
        }
    }
}

@Composable
private fun LoadingBannerCard(app: HikariApp) {
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        enabled = app.store.showLoadingBanner()
    }

    Column(Modifier.padding(16.dp)) {
        Text(
            tr("Loading screen"),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(6.dp))
        Text(
            tr("What covers the player while it finds a server and buffers the first " + "frame of video."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    tr("Show banner until servers load"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    if (enabled) {
                        "On — the title's artwork and name (breathing in and out) stay " +
                            "on screen until the first frame of video is ready. Tapping " +
                            "the banner does nothing."
                    } else {
                        "Off — the player opens straight away with just a round loading " +
                            "icon, no artwork or name."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = {
                    enabled = it
                    scope.launch { runCatching { app.store.setShowLoadingBanner(it) } }
                }
            )
        }
    }
}

@Composable
private fun UniversalExtractionCard(app: HikariApp) {
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        enabled = app.store.ytdlpEnabled()
    }

    Column(Modifier.padding(16.dp)) {
        Text(
            tr("Universal extraction (yt-dlp)"),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(6.dp))
        Text(
            tr("When a provider's own extractors find no playable source, the " + "built-in yt-dlp engine takes over and tries to pull a direct ") +
                I18n.t("stream from the page. Adds ~60 MB to the APK."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    tr("Fall back to yt-dlp when no sources found"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    tr("Only kicks in on pages the built-in extractors can't resolve."),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = {
                    enabled = it
                    scope.launch { runCatching { app.store.setYtdlpEnabled(it) } }
                }
            )
        }
    }
}

@Composable
private fun LanguageCard(app: HikariApp, current: String) {
    var pickerOpen by remember { mutableStateOf(false) }
    val selected = LanguageManager.ALL.firstOrNull { it.tag == current } ?: LanguageManager.SYSTEM
    // The System-default entry is deliberately NOT translated: its job is to say
    // "unless you pick otherwise, this app speaks English", and that reads best
    // as the plain English words followed by the language actually in effect —
    // ("System default (English)"). Translating it left the brackets empty (or
    // nonsense) in every language but English.
    val systemLabel = "System default (English)"
    val selectedName = if (selected.tag.isBlank()) systemLabel else selected.name
    val glass = rememberGlassTokens()
    val pillShape = RoundedCornerShape(16.dp)

    Column(Modifier.padding(16.dp)) {
        Text(
            stringResource(R.string.settings_language_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.settings_language_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(pillShape)
                .background(Brush.verticalGradient(listOf(glass.fillTop, glass.fillBottom)))
                .border(1.dp, glass.border, pillShape)
                .clickable { pickerOpen = true }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Translate,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                selected.flag,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                selectedName,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    // The picker is a glass panel like every other surface in the app (it used
    // to be a bare Material dropdown, which looked nothing like Hikari), and a
    // whole-panel tap-to-dismiss target so cancelling never needs a precise aim.
    if (pickerOpen) {
        GlassDialog(
            onDismiss = { pickerOpen = false },
            title = tr("Choose a language"),
        ) {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(LanguageManager.ALL.size) { index ->
                    val lang = LanguageManager.ALL[index]
                    val isOn = lang.tag == selected.tag
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                if (isOn) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                                else Color.Transparent
                            )
                            .clickable {
                                pickerOpen = false
                                // Persist FIRST (in memory + on the app scope), then
                                // hand the locale to the platform: the apply recreates
                                // the activity, and a write launched on the dying
                                // composition's scope was cancelled by it — which is
                                // why the same language had to be picked twice.
                                LanguageManager.choose(app, lang.tag)
                            }
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(lang.flag, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            if (lang.tag.isBlank()) systemLabel else lang.name,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (isOn) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        if (isOn) {
                            Icon(
                                Icons.Filled.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
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

    Column(Modifier.padding(16.dp)) {
        Text(
            tr("WebView safety"),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(6.dp))
        Text(
            tr("Stops sites from redirecting or popping you out to ad pages. " + "Only pages/popups that belong to the site itself are allowed. ") +
                I18n.t("Turn off if a site's player opens in another tab on a different domain."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    tr("Block redirects to other sites"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    tr("Same-site pages & subdomains still load normally."),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = redirectProtection,
                onCheckedChange = {
                    redirectProtection = it
                    scope.launch { runCatching { app.store.setWebviewRedirect(it) } }
                }
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    tr("Block popups from other sites"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    tr("Only popups opened by the site itself can appear."),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = popupProtection,
                onCheckedChange = {
                    popupProtection = it
                    scope.launch { runCatching { app.store.setWebviewPopup(it) } }
                }
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            tr("Nothing opens in a web view on its own — not ads, not pop-ups, and not a site's human-verification page. An extension whose site wants a browser check is skipped instead, and you can open that site yourself with the globe button."),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
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
            tr("Redirects to these hosts are never blocked, even though they're a " + "different site (e.g. a player or CDN a site must send you to)."),
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
private fun BackupCard(app: HikariApp) {
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
            tr("Your extensions, sources and settings — not your videos — in one file."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        BackupRow(
            icon = Icons.Filled.SaveAlt,
            title = tr("Back up Hikari data"),
            subtitle = tr("Save your extensions, sources and settings to one file you can keep."),
            action = tr("Back up"),
            enabled = !busy,
            onClick = { backup() },
        )
        Spacer(Modifier.height(10.dp))
        BackupRow(
            icon = Icons.Filled.RestorePage,
            title = tr("Restore from a backup"),
            subtitle = tr("Pick a Hikari backup file and put it back on this device."),
            action = tr("Restore"),
            enabled = !busy,
            onClick = { picker.launch(arrayOf("application/json", "text/plain", "*/*")) },
        )
        Spacer(Modifier.height(10.dp))
        BackupRow(
            icon = Icons.Filled.SettingsBackupRestore,
            title = tr("Coming from CloudStream?"),
            subtitle = tr("Pick a CloudStream backup file to add the repositories it contains."),
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
            tr("A backup holds your settings and extension files — never your videos and never your passwords. Restoring replaces what is on this device now."),
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
        OutlinedButton(onClick = onClick, enabled = enabled) { Text(action) }
    }
}

@Composable
private fun ExtensionVerifyCard(app: HikariApp) {
    val scope = rememberCoroutineScope()
    var allowed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { allowed = app.store.extensionVerifyWebview() }

    Column(Modifier.padding(16.dp)) {
        Text(
            tr("Extension verification pages"),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(6.dp))
        Text(
            tr("Hikari only opens a site's verification page when you tap the WebView button yourself. Extensions whose site wants that check are skipped while loading sources, so no page opens on its own."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    tr("Let extensions open their own verification page"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    tr("Off: an extension's own page can never open uninvited. On: Hikari stops forcing the extension's switch — it never turns that page on for you."),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = allowed,
                onCheckedChange = {
                    allowed = it
                    scope.launch { runCatching { app.store.setExtensionVerifyWebview(it) } }
                }
            )
        }
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

    Column(Modifier.padding(16.dp)) {
        Text(
            tr("WebView user agent"),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(6.dp))
        Text(
            tr("Some sites block the WebView when it advertises a " + "desktop browser it doesn't match. The stock Android user agent ") +
                I18n.t("works on most sites; a custom one is for sites ") +
                I18n.t("that need a specific desktop/mobile UA."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    tr("Use Android default user agent"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    tr("Stock Android WebView UA — works on most sites."),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = useDefault,
                onCheckedChange = { on -> persist(on, draft) }
            )
        }
        if (!useDefault) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    placeholder = { Text(tr("Mozilla/5.0 …")) },
                    singleLine = true,
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

    Column(Modifier.padding(16.dp)) {
        Text(
            tr("Userscripts"),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(6.dp))
        Text(
            tr("Tampermonkey-style scripts that run ONLY inside the app's WebView " + "(@match/@include/@run-at + GM_getValue/setValue). Add as many as you like."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        if (scripts.isEmpty()) {
            Text(
                tr("No userscripts yet."),
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
                        }
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
        OutlinedButton(onClick = { draft = ""; adding = true }) {
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
                        tr("Paste a userscript with a // ==UserScript== header " + "(name, @match, @run-at…). It runs only in the WebView."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 200.dp),
                        textStyle = MaterialTheme.typography.bodySmall,
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

    Column(Modifier.padding(16.dp)) {
        Text(
            tr("Ad Blocking"),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                tr("Block ads & trackers in websites opened in the browser tab"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = enabled,
                onCheckedChange = {
                    enabled = it
                    scope.launch { app.store.setAdEnabled(it) }
                }
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            tr("Applies only to WebView sites — the video player is never affected."),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
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
                        }
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
                tr("Add a domain to always block in the browser tab"),
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
                tr("If a site or video is wrongly blocked, whitelist its domain"),
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
                        singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text(tr("Hosts file URL")) },
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
            tr("Choose where each button sits in the player — top bar, the left or " + "right end of the bottom row — or hide the ones you never use."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onOpen) { Text(tr("Edit control layout")) }
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

    Column(Modifier.padding(16.dp)) {
        Text(
            tr("Video enhance"),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(6.dp))
        Text(
            tr("Realtime colour grading applied to the video itself (not an overlay), " + "here and from the Enhance button in the player."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
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
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                EnhancePreset.entries.forEach { p ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(p.label)
                                Text(
                                    p.desc,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        onClick = {
                            menuOpen = false
                            scope.launch {
                                runCatching { app.store.setEnhancePreset(p.key) }
                            }
                        },
                        leadingIcon = {
                            if (p == preset) {
                                Icon(
                                    Icons.Filled.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            tr("Natural applies nothing at all — enhancement only runs while a preset " + "is picked. HDR videos ignore the tint part of a preset, and effects ") +
                I18n.t("are applied with no quality loss to the source."),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ---- Accent colours (Appearance folder) ----

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

    Column(Modifier.padding(16.dp)) {
        Text(
            tr("Accent color"),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(6.dp))
        Text(
            tr("The colour of buttons, selected tabs, sliders and highlights " + "throughout the app."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        AccentSwatches(
            selected = HikariAccent.fromKey(appAccentKey),
            onPick = { accent ->
                scope.launch { runCatching { app.store.setAppAccent(accent.key) } }
            }
        )

        if (linked) {
            Spacer(Modifier.height(12.dp))
            Text(
                tr("The player is following this colour — see \"Match app & player " + "theme\" below to give it its own."),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Spacer(Modifier.height(20.dp))
            Text(
                tr("Player color"),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(6.dp))
            Text(
                tr("The glow behind the player's pills, badges, play ring and " + "progress bar."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
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

    Column(Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(tr("Match app & player theme"), style = MaterialTheme.typography.titleSmall)
                Text(
                    tr("Use one colour everywhere"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
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
        }
        Spacer(Modifier.height(6.dp))
        Text(
            if (linked) {
                "On: the player always uses the app's accent colour, so the two " +
                    "can never drift apart."
            } else {
                "Off: the app and the player each keep their own colour. Use the " +
                    "buttons below to copy one onto the other."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    scope.launch { runCatching { app.store.setPlayerAccent(appAccentKey) } }
                },
                enabled = playerAccentKey != appAccentKey
            ) { Text(tr("App \u2192 player")) }
            OutlinedButton(
                onClick = {
                    scope.launch { runCatching { app.store.setAppAccent(playerAccentKey) } }
                },
                enabled = playerAccentKey != appAccentKey
            ) { Text(tr("Player \u2192 app")) }
        }
    }
}
