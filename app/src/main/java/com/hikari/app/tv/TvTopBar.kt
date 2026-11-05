package com.hikari.app.tv

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import android.content.Intent
import com.hikari.app.data.Site
import com.hikari.app.web.WebViewActivity
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import com.hikari.app.data.ProviderType
import com.hikari.app.data.Collection
import com.hikari.app.providers.ContentProvider
import com.hikari.app.data.Profiles
import com.hikari.app.i18n.tr
import com.hikari.app.HikariApp
import com.hikari.app.data.RepoProvenance
import com.hikari.app.ui.navigation.Routes
import com.hikari.app.ui.screens.holdOrTap

/**
 * Process-level catalog-name cache for the TV feed mixer (see the prefetch in
 * [TvManageFeedDialog]). The phone picker never loads these; without the
 * cache every box open re-fired one load per installed provider.
 */
private val catalogNameCache = java.util.concurrent.ConcurrentHashMap<String, List<String>>()

/** Install-set signature [catalogNameCache] was built for; a mismatch drops it. */
@Volatile
private var catalogNameCacheKey = ""

/**
 * The television top-right cluster, in the shape of the CloudStream desktop
 * reference: a provider pill (icon + name, opens the provider box) and a
 * profile pill (avatar + name, opens the profile menu). Row is right-aligned
 * so it hugs the screen's top-right corner above the feed.
 */
@Composable
fun TvHomeTopBar(
    providerName: String?,
    providerIconUrl: String?,
    profileName: String?,
    onProviderClick: () -> Unit,
    onProfileClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 10.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TvProviderPill(
            name = providerName,
            iconUrl = providerIconUrl,
            onClick = onProviderClick,
        )
        Spacer(Modifier.width(10.dp))
        TvProfilePill(
            name = profileName,
            onClick = onProfileClick,
        )
    }
}

@Composable
fun TvProviderPill(
    name: String?,
    iconUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = TvDesktop.SurfaceCard,
        modifier = modifier
            .tvPress(previewPass = true, onClick = onClick)
            .tvFocusRing(50.dp)
            .border(1.dp, TvDesktop.GlassEdge, RoundedCornerShape(50)),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TvProviderIcon(iconUrl = iconUrl, name = name, size = 22.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                name ?: tr("All providers"),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = TvDesktop.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = TvDesktop.TextMuted,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
fun TvProfilePill(
    name: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = TvDesktop.SurfaceCard,
        modifier = modifier
            .tvPress(previewPass = true, onClick = onClick)
            .tvFocusRing(50.dp)
            .border(1.dp, TvDesktop.GlassEdge, RoundedCornerShape(50)),
    ) {
        Row(
            Modifier.padding(start = 6.dp, end = 12.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val initial = name?.trim()?.firstOrNull()?.uppercase() ?: "M"
            Box(
                Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(Cs3.accent),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    initial,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                name ?: tr("Main"),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = TvDesktop.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = TvDesktop.TextMuted,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/**
 * The television home header as ONE line, CloudStream-desktop-style: the
 * title and tagline on the left, then the mouse, search, translate and
 * site buttons, then the provider pill and the profile pill — everything in
 * a single row, so the pills row and the button row can never stack into two.
 *
 * [selected] is the header's extension pick (null on All/collections): it
 * gates the translate and site buttons exactly like the phone header, and
 * [onSettings] is non-null only while the Settings tab is hidden from the
 * taskbar.
 */
@Composable
fun TvHomeHeaderBar(
    providerName: String?,
    providerIconUrl: String?,
    profileName: String?,
    onProviderClick: () -> Unit,
    onProfileClick: () -> Unit,
    selected: String?,
    onSearch: () -> Unit,
    onTranslate: () -> Unit,
    onVerify: () -> Unit,
    onMouse: () -> Unit,
    mouseOn: Boolean,
    onSettings: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val accent = Cs3.accent
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                tr("Hikari"),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                tr("Every stream, one place."),
                style = MaterialTheme.typography.bodyMedium,
                color = TvDesktop.TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onMouse, modifier = Modifier.tvFocusRing(12.dp)) {
            Icon(
                Icons.Filled.Mouse,
                contentDescription = tr("Mouse cursor"),
                tint = if (mouseOn) accent else accent.copy(alpha = 0.7f),
                modifier = Modifier.size(if (mouseOn) 26.dp else 24.dp),
            )
        }
        IconButton(onClick = onSearch, modifier = Modifier.tvFocusRing(12.dp)) {
            Icon(Icons.Filled.Search, contentDescription = tr("Search"), tint = accent)
        }
        selected?.let { pid ->
            val translateOn = com.hikari.app.data.Translator.isOn(pid)
            IconButton(onClick = onTranslate, modifier = Modifier.tvFocusRing(12.dp)) {
                Text(
                    tr("A\u3042"),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (translateOn) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
        if (selected != null) {
            IconButton(onClick = onVerify, modifier = Modifier.tvFocusRing(12.dp)) {
                Icon(
                    Icons.Filled.Public,
                    contentDescription = tr("Open this extension's site in a web view"),
                    tint = accent,
                )
            }
        }
        onSettings?.let { open ->
            IconButton(onClick = open, modifier = Modifier.tvFocusRing(12.dp)) {
                Icon(
                    Icons.Filled.Settings,
                    contentDescription = tr("Settings"),
                    tint = accent,
                )
            }
        }
        TvProviderPill(
            name = providerName,
            iconUrl = providerIconUrl,
            onClick = onProviderClick,
        )
        Spacer(Modifier.width(10.dp))
        TvProfilePill(
            name = profileName,
            onClick = onProfileClick,
        )
    }
}

/** A provider's icon, or its initial letter when it declares none. */
@Composable
fun TvProviderIcon(
    iconUrl: String?,
    name: String?,
    size: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    // White-on-white reads as an empty tile (a white logo on the old white
    // disc, or a failed load that never replaces it), so the tile is dark and
    // a failed load falls back to the letter tile below — the row never shows
    // a blank disc. Fit (never Crop) with a little padding, so a wide or tall
    // logo draws whole instead of bleeding off the edge.
    var failed by remember(iconUrl) { mutableStateOf(false) }
    if (!iconUrl.isNullOrBlank() && !failed) {
        AsyncImage(
            model = iconUrl,
            contentDescription = name,
            contentScale = ContentScale.Fit,
            onError = { failed = true },
            modifier = modifier
                .size(size)
                .clip(CircleShape)
                .background(Color(0xFF232833))
                .padding(4.dp),
        )
    } else {
        Box(
            modifier
                .size(size)
                .clip(CircleShape)
                .background(Cs3.accent.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                name?.trim()?.firstOrNull()?.uppercase() ?: "•",
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                color = Cs3.accent,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Provider box + feed mixer, one-to-one with the CloudStream desktop
// reference (ProviderSelectionDialog + HomeManagementDialog, same layout,
// same strings, same ratios). TV-only: the phone picker is
// HomeScreen.ProviderPickerSheet and is untouched.
// ---------------------------------------------------------------------------

/** Reference card subtitle: the first two content types, then the host. */
private fun refProviderSubtitle(p: ContentProvider): String {
    val types = when (p.config.type) {
        ProviderType.ANIYOMI, ProviderType.FORK_VIDEO, ProviderType.ANYMEX, ProviderType.SORA ->
            listOf("Anime")
        ProviderType.IPTV -> listOf("Live")
        else -> listOf("Movie", "TvSeries")
    }.take(2).joinToString(", ")
    val host = runCatching {
        val u = p.config.url.trim()
        if (u.isBlank()) "" else java.net.URI(u).host?.removePrefix("www.").orEmpty()
    }.getOrDefault("")
    return if (host.isBlank()) types else "$types • $host"
}

/** Reference feed chip mapping for a provider kind. */
/** Engine-family chip label for a provider (Nuvio, Hikari, CloudStream, ...). */
private fun providerFamilyLabel(type: ProviderType): String = type.groupLabel

/**
 * The reference dialog frame (CloudstreamCustomDialog): 28dp surface with a
 * fade+scale enter, no platform default width.
 */
@Composable
private fun RefDialogFrame(
    onDismiss: () -> Unit,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    val transition = remember { MutableTransitionState(false) }
    transition.targetState = true
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        AnimatedVisibility(
            visibleState = transition,
            enter = fadeIn(tween(140)) + scaleIn(tween(140), initialScale = 0.96f),
            exit = fadeOut(tween(100)) + scaleOut(tween(100), targetScale = 0.96f),
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = modifier,
            ) {
                content()
            }
        }
    }
}

/**
 * "Select Provider", one-to-one with the reference ProviderSelectionDialog:
 * title + subtitle on the left, the "All Plugins (Global)" pill top-right, a
 * full-width filter below, then the providers side by side in an adaptive grid
 * of icon+name+type cards, the pick tinted purple. A tap picks and closes; the
 * pill picks global. One box shared by Home and Search on TV.
 */
@Composable
fun TvSelectProviderDialog(
    providers: List<ContentProvider>,
    selection: List<String>,
    onPickAll: () -> Unit,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit,
    /** The user's own collections, drawn full-width above the provider grid. */
    collections: List<Collection> = emptyList(),
) {
    // Portrait phones run this same TV UI: 65% of a narrow portrait screen is a
    // vertical strip, while the reference desktop is landscape-only. Same
    // reference fraction on wide screens, near-full width on narrow ones.
    val selectCfg = LocalConfiguration.current
    val selectW = if (selectCfg.screenWidthDp < 600) (selectCfg.screenWidthDp * 0.92f).dp else minOf((selectCfg.screenWidthDp * 0.65f).dp, 760.dp)
    var providerModalSearch by remember { mutableStateOf("") }
    val narrow = selectCfg.screenWidthDp < 600
    var isAdvancedMode by remember { mutableStateOf(selection.size > 1) }
    val dlgFocus = remember { FocusRequester() }
    // Explicit D-pad chain (pill → filter → websites → toggle → grid): the
    // toggle sits at its row's far end and the 2D search kept walking past
    // it, so every stop names its neighbours instead of relying on geometry.
    val filterReq = remember { FocusRequester() }
    val webReq = remember { FocusRequester() }
    val toggleReq = remember { FocusRequester() }
    val siteReq = remember { FocusRequester() }
    val firstCardReq = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { dlgFocus.requestFocus() } }
    val working = remember(selection) { mutableStateOf(ArrayList(selection)) }
    // Repo display names for the "(repo)" duplicate tags — the reference tells
    // same-named installs apart by the parent folder of the plugin path, which
    // is exactly what the repo name is here.
    val app = LocalContext.current.applicationContext as HikariApp
    val repos by remember { app.store.reposFlow() }.collectAsState(initial = emptyList())
    val repoLabels = remember(providers, repos) {
        RepoProvenance.nameMap(providers.map { it.config }, repos)
    }
    val matchingProviders = remember(providers, providerModalSearch) {
        val list = if (providerModalSearch.isBlank()) providers
        else providers.filter { it.config.name.contains(providerModalSearch, ignoreCase = true) }
        list.distinctBy { it.config.id }
    }
    val duplicateNames = remember(matchingProviders) {
        matchingProviders.groupBy { it.config.name }.filterValues { it.size > 1 }.keys
    }
    val shownCollections = remember(collections, providerModalSearch) {
        if (providerModalSearch.isBlank()) collections
        else collections.filter { it.name.contains(providerModalSearch, ignoreCase = true) }
    }

    RefDialogFrame(
        onDismiss = onDismiss,
        modifier = Modifier.width(selectW).wrapContentHeight().heightIn(min = 220.dp, max = (selectCfg.screenHeightDp * 0.85f).dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().wrapContentHeight().padding(24.dp).focusGroup(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (!narrow) {
                // Header (reference: title left, pill right)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = tr("Select Provider"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(text = tr("Choose a dedicated provider or search across all installed plugins"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    FilledTonalButton(onClick = onPickAll, shape = RoundedCornerShape(10.dp), modifier = Modifier.focusRequester(dlgFocus).focusProperties { down = filterReq }.tvFocusRing(10.dp)) {
                        Text(tr("All Plugins (Global)"), fontWeight = FontWeight.SemiBold, fontSize = 12.sp, maxLines = 1)
                    }
                }
            } else {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(text = tr("Select Provider"), fontWeight = FontWeight.Bold, fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        FilledTonalButton(onClick = onPickAll, shape = RoundedCornerShape(10.dp), modifier = Modifier.focusRequester(dlgFocus).focusProperties { down = filterReq }.tvFocusRing(10.dp)) {
                            Text(tr("All Plugins (Global)"), fontWeight = FontWeight.SemiBold, fontSize = 12.sp, maxLines = 1)
                        }
                    }
                    Text(text = tr("Choose a dedicated provider or search across all installed plugins"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            // Search input within modal
            OutlinedTextField(
                value = providerModalSearch,
                onValueChange = { providerModalSearch = it },
                placeholder = { Text(tr("Filter providers..."), fontSize = 13.sp) },
                singleLine = true,
                leadingIcon = {
                    Icon(
                        Icons.Filled.Search,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
                // The remote must be able to LEAVE the field (arrows move the
                // caret otherwise) and land on the Websites row below it.
                modifier = Modifier.fillMaxWidth().height(48.dp).focusRequester(filterReq).focusProperties { up = dlgFocus; down = webReq }.tvTextFieldKeys(providerModalSearch),
                shape = RoundedCornerShape(10.dp),
            )

            // The user's saved websites (Extensions → Webview sites): one
            // "Websites" pill under the filter, opening the full saved list
            // under it — a tap on a site loads it at once in the ad-free
            // WebView, straight from this picker.
            val sites by remember { app.store.sitesFlow() }.collectAsState(initial = emptyList<Site>())
            val shownSites = remember(sites, providerModalSearch) {
                if (providerModalSearch.isBlank()) sites
                else sites.filter {
                    it.name.contains(providerModalSearch, ignoreCase = true) ||
                        it.url.contains(providerModalSearch, ignoreCase = true)
                }
            }
            var sitesOpen by remember { mutableStateOf(false) }
            val tvCtx = LocalContext.current
            fun openSite(site: Site) {
                onDismiss()
                runCatching {
                    tvCtx.startActivity(
                        Intent(tvCtx, WebViewActivity::class.java).apply {
                            putExtra("url", site.url)
                            putExtra("title", site.name)
                        }
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = sitesOpen,
                    onClick = { sitesOpen = !sitesOpen },
                    label = { Text(tr("Websites"), fontSize = 12.sp, maxLines = 1) },
                    leadingIcon = {
                        Icon(
                            Icons.Filled.Public,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                        )
                    },
                    modifier = Modifier.focusRequester(webReq).focusProperties { up = filterReq; down = if (sitesOpen) siteReq else toggleReq }.tvFocusRing(50.dp),
                )
            }
            // The saved list, under the pill: every added site as its own
            // pill, opening straight in the WebView on tap.
            if (sitesOpen) {
                if (shownSites.isEmpty()) {
                    Text(
                        tr("No websites saved yet — add them in Extensions → Webview sites."),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        shownSites.forEachIndexed { siteIdx, site ->
                            FilterChip(
                                selected = false,
                                onClick = { openSite(site) },
                                label = { Text(site.name, fontSize = 12.sp, maxLines = 1) },
                                leadingIcon = {
                                    Icon(
                                        Icons.Filled.Public,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                    )
                                },
                                // Stacked full-width rows walk fine on their
                                // own; only the ends are pinned: the first
                                // hands Up back to the Websites pill, the last
                                // hands Down to the multi toggle.
                                modifier = (if (siteIdx == 0) Modifier.focusRequester(siteReq) else Modifier)
                                    .focusProperties {
                                        if (siteIdx == 0) up = webReq
                                        if (siteIdx == shownSites.lastIndex) down = toggleReq
                                    }
                                    .tvFocusRing(50.dp),
                            )
                        }
                    }
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(text = tr("Enable Multi-Provider Feed"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Spacer(modifier = Modifier.width(8.dp))
                // A bare Switch at the row's far end is what the 2D search
                // kept skipping (see [Modifier.tvToggle]): the switch answers
                // the D-pad itself now, and the row above/below hand to it.
                Switch(checked = isAdvancedMode, modifier = Modifier.focusRequester(toggleReq).focusProperties { up = webReq }.tvToggle(isAdvancedMode) { enabled ->
                    isAdvancedMode = enabled
                    if (enabled && working.value.isEmpty()) { selection.firstOrNull()?.let { working.value = ArrayList(listOf(it)) } }
                }.tvFocusRing(50.dp), onCheckedChange = { enabled ->
                    isAdvancedMode = enabled
                    if (enabled && working.value.isEmpty()) { selection.firstOrNull()?.let { working.value = ArrayList(listOf(it)) } }
                })
            }

            if (isAdvancedMode) {
                val activeSel = working.value.mapNotNull { id -> matchingProviders.firstOrNull { it.config.id == id } }
                val inactiveSel = matchingProviders.filter { it.config.id !in working.value }
                if (narrow) {
                    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SelectActivePane(activeSel, working, Modifier.fillMaxWidth())
                        SelectAvailPane(inactiveSel, working, Modifier.fillMaxWidth())
                    }
                } else {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SelectActivePane(activeSel, working, Modifier.weight(1f))
                        SelectAvailPane(inactiveSel, working, Modifier.weight(1f))
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Button(onClick = { onSave(working.value.toList()) }, modifier = Modifier.tvFocusRing(12.dp)) {
                        Text(tr("Save & Close"), maxLines = 1)
                    }
                }
            } else {
                LazyVerticalGrid(columns = GridCells.Fixed(4), modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp, max = if (narrow) 280.dp else 440.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(shownCollections, key = { _, col -> "tv-col-" + col.id }, span = { _, _ -> GridItemSpan(maxLineSpan) }) { colIdx, col ->
                        val key = Routes.COLLECTION_PROVIDER_PREFIX + col.id
                        RefProviderCard(name = col.name, repoTag = null, subtitle = tr("Collection"), selected = key in selection, iconUrl = null, iconName = col.name, onClick = { onPick(key) }, modifier = if (colIdx == 0) Modifier.focusRequester(firstCardReq).focusProperties { up = toggleReq } else Modifier)
                    }
                    items(matchingProviders.size, key = { idx ->
                        val p = matchingProviders[idx]
                        p.config.name + "_" + p.config.id
                    }) { idx ->
                        val provider = matchingProviders[idx]
                        val id = provider.config.id
                        val repoTag = if (provider.config.name in duplicateNames) { repoLabels[id]?.takeIf { it.isNotBlank() } } else null
                        RefProviderCard(name = provider.config.name, repoTag = repoTag, subtitle = refProviderSubtitle(provider), selected = id in selection, iconUrl = provider.config.iconUrl, iconName = provider.config.name, onClick = { onPick(id) }, compact = true, modifier = if (shownCollections.isEmpty() && idx == 0) Modifier.focusRequester(firstCardReq).focusProperties { up = toggleReq } else Modifier)
                    }
                }
            }
        }
    }
}

/** Active side of the Select box multi mixer: number + icon + name, up/down arrows to reorder, X to remove. */
@Composable
private fun SelectActivePane(active: List<ContentProvider>, working: androidx.compose.runtime.MutableState<ArrayList<String>>, mod: Modifier) {
    Column(modifier = mod) {
        Text(tr("Active Providers Feed"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(modifier = Modifier.height(8.dp))
        if (active.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp, max = 160.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                Text(tr("Pick from the other side to build the mix"), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp, max = 220.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)).padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(active.size, key = { i -> "sel-active-" + active[i].config.id }) { index ->
                    val p = active[index]
                    Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surface).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text((index + 1).toString() + ".", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.width(6.dp))
                        if (!p.config.iconUrl.isNullOrBlank()) {
                            AsyncImage(model = p.config.iconUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)))
                        } else {
                            Box(modifier = Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.Extension, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
                            }
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(p.config.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(refProviderSubtitle(p), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = { if (index > 0) { val n = ArrayList(working.value); val t = n[index - 1]; n[index - 1] = n[index]; n[index] = t; working.value = n } }, enabled = index > 0, modifier = Modifier.size(28.dp).tvFocusRing(50.dp)) {
                            Icon(Icons.Filled.ArrowUpward, contentDescription = tr("Move Up"), modifier = Modifier.size(16.dp))
                        }
                        IconButton(onClick = { if (index < working.value.lastIndex) { val n = ArrayList(working.value); val t = n[index + 1]; n[index + 1] = n[index]; n[index] = t; working.value = n } }, enabled = index < working.value.lastIndex, modifier = Modifier.size(28.dp).tvFocusRing(50.dp)) {
                            Icon(Icons.Filled.ArrowDownward, contentDescription = tr("Move Down"), modifier = Modifier.size(16.dp))
                        }
                        IconButton(onClick = { working.value = ArrayList(working.value - p.config.id) }, modifier = Modifier.size(28.dp).tvFocusRing(50.dp)) {
                            Icon(Icons.Filled.Close, contentDescription = tr("Remove"), tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
    }
}

/** Available side of the Select box multi mixer: + adds to the mix. */
@Composable
private fun SelectAvailPane(inactive: List<ContentProvider>, working: androidx.compose.runtime.MutableState<ArrayList<String>>, mod: Modifier) {
    Column(modifier = mod) {
        Text(tr("Available Plugins"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(modifier = Modifier.height(8.dp))
        if (inactive.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp, max = 160.dp).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                Text(tr("All plugins are active"), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp, max = 220.dp).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(12.dp)).padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(inactive.size, key = { i -> "sel-avail-" + inactive[i].config.id }) { index ->
                    val p = inactive[index]
                    Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surface).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (!p.config.iconUrl.isNullOrBlank()) {
                            AsyncImage(model = p.config.iconUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)))
                        } else {
                            Box(modifier = Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.Extension, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
                            }
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(p.config.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(refProviderSubtitle(p), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        FilledTonalIconButton(onClick = { working.value = ArrayList((working.value + p.config.id).distinct()) }, modifier = Modifier.tvFocusRing(50.dp)) {
                            Icon(Icons.Filled.Add, contentDescription = tr("Add"))
                        }
                    }
                }
            }
        }
    }
}

/** One reference Select-Provider card: 28dp round icon + name/tag + subtitle. */
/**
 * One-line text that shrinks to fit instead of truncating: starts at
 * [baseSize] and steps down 1sp per overflow layout pass to [minScale], so a
 * long provider name in a narrow 4-per-row card still reads in full. The loop
 * terminates (bounded below, only shrinks on real visual overflow).
 */
@Composable
private fun ShrinkText(
    text: String,
    baseSize: TextUnit,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    minScale: Float = 0.7f,
) {
    var size by remember(text, baseSize) { mutableStateOf(baseSize) }
    Text(
        text = text,
        modifier = modifier,
        style = style,
        color = color,
        fontWeight = fontWeight,
        fontSize = size,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        softWrap = false,
        onTextLayout = { layout ->
            if (layout.hasVisualOverflow && size.value > baseSize.value * minScale) {
                size = (size.value - 1f).sp
            }
        },
    )
}

@Composable
private fun RefProviderCard(
    name: String,
    repoTag: String?,
    subtitle: String,
    selected: Boolean,
    iconUrl: String?,
    iconName: String,
    onClick: () -> Unit,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
        ),
        modifier = modifier.tvPress(previewPass = true, onClick = onClick).tvFocusRing(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = if (compact) 8.dp else 12.dp, vertical = if (compact) 6.dp else 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp),
        ) {
            TvProviderIcon(
                iconUrl = iconUrl,
                name = iconName,
                size = if (compact) 22.dp else 28.dp,
            )
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ShrinkText(
                        text = name,
                        baseSize = if (compact) 11.sp else 13.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (!repoTag.isNullOrBlank()) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "($repoTag)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                ShrinkText(
                    text = subtitle,
                    baseSize = if (compact) 9.sp else 11.sp,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * "Manage Home Screen Feed", one-to-one with the reference
 * HomeManagementDialog: an "Enable Multi-Provider Feed" switch, type chips,
 * and either a single-provider grid (switch off — a tap picks and closes) or
 * the Active/Available two-pane feed mixer (switch on — reorder with arrows or
 * drag, expand rows to toggle catalogs, add/remove, Save & Close applies the
 * mix). Home-only on TV.
 */
@Composable
fun TvManageFeedDialog(
    providers: List<ContentProvider>,
    selection: List<String>,
    onSave: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as HikariApp
    val mcfg = LocalConfiguration.current
    val manageW = if (mcfg.screenWidthDp < 600) (mcfg.screenWidthDp * 0.95f).dp else (mcfg.screenWidthDp * 0.85f).dp
    val manageNarrow = mcfg.screenWidthDp < 600
    val advNarrow = manageNarrow
    val scope = rememberCoroutineScope()
    val dlgFocus = remember { FocusRequester() }
    // Same explicit chain as the Select box: the toggle answers the D-pad
    // itself and the search field hands Down to the chips below it.
    val manageFilterReq = remember { FocusRequester() }
    val manageToggleReq = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { dlgFocus.requestFocus() } }
    val working = remember(selection) { mutableStateOf(ArrayList(selection)) }
    var isAdvancedMode by remember { mutableStateOf(selection.size > 1) }
    var catalogProvider by remember { mutableStateOf<ContentProvider?>(null) }
    var providerTypeFilter by remember { mutableStateOf(emptySet<String>()) }
    var manageQuery by remember { mutableStateOf("") }
    val disabledCatalogs by remember { app.store.disabledCatalogsFlow() }
        .collectAsState(initial = emptyMap())
    val orderedCatalogs by remember { app.store.catalogOrderFlow() }
        .collectAsState(initial = emptyMap())
    val allRepos by remember { app.store.reposFlow() }.collectAsState(initial = emptyList())
    val repoNameById = remember(providers, allRepos) {
        RepoProvenance.nameMap(providers.map { it.config }, allRepos)
    }
    // Per-provider catalog names for the counts, the expander toggles and the
    // Tune sub-screen — loaded off the tap path like the reference keeps them
    // in memory, a provider that does not answer in time keeps its plain row.
    //
    // Process-level cache + a 4-wide gate: the phone picker never does this
    // work at all, and firing one load per installed provider (649 on a big
    // install, JS-engine boots among them) the moment the box opens saturated
    // the device so the tapped provider's own feed had nothing left to run on.
    // Reopens reuse the cache; a changed install set drops it.
    val catalogNames = remember { mutableStateMapOf<String, List<String>>() }
    LaunchedEffect(providers) {
        val sig = providers.size.toString() + "|" + providers.sumOf { it.config.id.hashCode() }.toString()
        if (sig != catalogNameCacheKey) {
            catalogNameCache.clear()
            catalogNameCacheKey = sig
        }
        providers.forEach { p ->
            catalogNameCache[p.config.id]?.let { catalogNames[p.config.id] = it }
        }
        val gate = Semaphore(4)
        providers.filter { it.config.id !in catalogNames }.forEach { p ->
            launch {
                gate.withPermit {
                    val names = withTimeoutOrNull(8_000L) {
                        runCatching { p.homeCatalogs().map { it.name }.distinct() }.getOrDefault(null)
                    }
                    if (names != null) {
                        catalogNames[p.config.id] = names
                        catalogNameCache[p.config.id] = names
                    }
                }
            }
        }
    }
    fun matches(p: ContentProvider): Boolean {
        if (providerTypeFilter.isNotEmpty() && p.config.type.groupLabel !in providerTypeFilter) return false
        val q = manageQuery.trim()
        if (q.isBlank()) return true
        return p.config.name.contains(q, ignoreCase = true) || p.config.id.contains(q, ignoreCase = true)
    }
    fun sortedNames(p: ContentProvider): List<String>? {
        val names = catalogNames[p.config.id] ?: return null
        val order = orderedCatalogs[p.config.id].orEmpty()
        return if (order.isEmpty()) names
        else names.sortedBy { order.indexOf(it).takeIf { i -> i >= 0 } ?: Int.MAX_VALUE }
    }

    RefDialogFrame(
        onDismiss = {
            if (!isAdvancedMode && working.value.size > 1) {
                val top = working.value.firstOrNull() ?: providers.firstOrNull()?.config?.id
                if (top != null) onSave(listOf(top)) else onDismiss()
            } else {
                catalogProvider = null
                onDismiss()
            }
        },
        modifier = Modifier.width(manageW).fillMaxHeight(0.85f),
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            if (!manageNarrow) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = if (catalogProvider != null) { tr("Customize %s").replace("%s", catalogProvider?.config?.name.orEmpty()) } else { tr("Manage Home Screen Feed") }, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(text = if (catalogProvider != null) { tr("Enable, disable, and drag to reorder catalogs.") } else { tr("Mix, reorder, and customize multiple providers.") }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    val cp = catalogProvider
                    if (cp != null) {
                        val hasCustomOrder = orderedCatalogs[cp.config.id]?.isNotEmpty() == true
                        if (hasCustomOrder) {
                            OutlinedButton(onClick = { scope.launch { app.store.resetCatalogOrder(cp.config.id) } }, shape = RoundedCornerShape(8.dp), modifier = Modifier.tvFocusRing(8.dp)) {
                                Text(tr("Reset Order"), style = MaterialTheme.typography.labelMedium, maxLines = 1)
                            }
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(tr("Enable Multi-Provider Feed"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(modifier = Modifier.width(8.dp))
                            Switch(checked = isAdvancedMode, modifier = Modifier.focusRequester(manageToggleReq).focusProperties { down = manageFilterReq }.tvToggle(isAdvancedMode) { enabled ->
                                isAdvancedMode = enabled
                                if (!enabled) {
                                    val top = working.value.firstOrNull() ?: providers.firstOrNull()?.config?.id
                                    working.value = if (top != null) ArrayList(listOf(top)) else ArrayList()
                                }
                            }.tvFocusRing(50.dp), onCheckedChange = { enabled ->
                                isAdvancedMode = enabled
                                if (!enabled) {
                                    val top = working.value.firstOrNull() ?: providers.firstOrNull()?.config?.id
                                    working.value = if (top != null) ArrayList(listOf(top)) else ArrayList()
                                }
                            })
                        }
                    }
                }
            } else {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(text = if (catalogProvider != null) { tr("Customize %s").replace("%s", catalogProvider?.config?.name.orEmpty()) } else { tr("Manage Home Screen Feed") }, fontWeight = FontWeight.Bold, fontSize = 19.sp, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(text = if (catalogProvider != null) { tr("Enable, disable, and drag to reorder catalogs.") } else { tr("Mix, reorder, and customize multiple providers.") }, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(modifier = Modifier.height(6.dp))
                    val cp = catalogProvider
                    if (cp != null) {
                        val hasCustomOrder = orderedCatalogs[cp.config.id]?.isNotEmpty() == true
                        if (hasCustomOrder) {
                            OutlinedButton(onClick = { scope.launch { app.store.resetCatalogOrder(cp.config.id) } }, shape = RoundedCornerShape(8.dp), modifier = Modifier.tvFocusRing(8.dp)) {
                                Text(tr("Reset Order"), style = MaterialTheme.typography.labelMedium, maxLines = 1)
                            }
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(tr("Enable Multi-Provider Feed"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Spacer(modifier = Modifier.width(8.dp))
                            Switch(checked = isAdvancedMode, modifier = Modifier.focusRequester(manageToggleReq).focusProperties { down = manageFilterReq }.tvToggle(isAdvancedMode) { enabled ->
                                isAdvancedMode = enabled
                                if (!enabled) {
                                    val top = working.value.firstOrNull() ?: providers.firstOrNull()?.config?.id
                                    working.value = if (top != null) ArrayList(listOf(top)) else ArrayList()
                                }
                            }.tvFocusRing(50.dp), onCheckedChange = { enabled ->
                                isAdvancedMode = enabled
                                if (!enabled) {
                                    val top = working.value.firstOrNull() ?: providers.firstOrNull()?.config?.id
                                    working.value = if (top != null) ArrayList(listOf(top)) else ArrayList()
                                }
                            })
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
            if (catalogProvider == null) {
                // Family chips (All + every installed engine family) with a
                // fast name search above them: tapping a family shows only
                // that family's extensions; All clears the filter.
                val feedCats = remember(providers) {
                    listOf("All") + providers.map { providerFamilyLabel(it.config.type) }.distinct().sorted()
                }
                OutlinedTextField(
                    value = manageQuery,
                    onValueChange = { manageQuery = it },
                    placeholder = { Text(tr("Search extensions..."), fontSize = 13.sp) },
                    singleLine = true,
                    leadingIcon = {
                        Icon(
                            Icons.Filled.Search,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp).focusRequester(manageFilterReq).focusProperties { up = manageToggleReq }.tvTextFieldKeys(manageQuery),
                    shape = RoundedCornerShape(10.dp),
                )
                Spacer(modifier = Modifier.height(8.dp))
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    items(feedCats) { category ->
                        val catSelected = if (category == "All") providerTypeFilter.isEmpty() else category in providerTypeFilter
                        FilterChip(
                            modifier = Modifier.focusRequester(dlgFocus).focusProperties { up = manageFilterReq }.tvFocusRing(50.dp),
                            selected = catSelected,
                            onClick = {
                                providerTypeFilter = if (category == "All") {
                                    emptySet()
                                } else if (category in providerTypeFilter) {
                                    providerTypeFilter - category
                                } else {
                                    providerTypeFilter + category
                                }
                            },
                            label = { Text(category) },
                        )
                    }
                }
            }

            @Composable
            fun ActivePane(mod: Modifier) {
                        // Left column: active providers feed
                        Column(modifier = mod) {
                            Text(
                                tr("Active Providers Feed"),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            if (working.value.isEmpty()) {
                                Box(
                                    modifier = Modifier.fillMaxSize()
                                        .background(
                                            MaterialTheme.colorScheme.surfaceVariant,
                                            RoundedCornerShape(12.dp),
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        tr("No active providers"),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            } else {
                                var draggingProviderName by remember { mutableStateOf<String?>(null) }
                                var providerDragAccumulatedY by remember { mutableStateOf(0f) }
                                var providerDragInitialIndex by remember { mutableStateOf(0) }
                                var providerSlotHeightPx by remember { mutableStateOf(0f) }
                                val fallbackProviderHeight = with(LocalDensity.current) { 68.dp.toPx() }
                                val effectiveSlotHeight =
                                    if (providerSlotHeightPx > 0f) providerSlotHeightPx
                                    else fallbackProviderHeight
                                val currentTargetIndex = if (draggingProviderName != null && effectiveSlotHeight > 0f) {
                                    (providerDragInitialIndex + kotlin.math.round(providerDragAccumulatedY / effectiveSlotHeight).toInt())
                                        .coerceIn(0, working.value.lastIndex)
                                } else providerDragInitialIndex
                                val currentActive by rememberUpdatedState(working.value.toList())
                                val currentEffectiveSlotHeight by rememberUpdatedState(effectiveSlotHeight)
                                val currentDragAccumulatedY by rememberUpdatedState(providerDragAccumulatedY)
                                val currentDragInitialIndex by rememberUpdatedState(providerDragInitialIndex)
                                val onDropProvider by rememberUpdatedState {
                                    val fromIdx = currentDragInitialIndex
                                    val slotH = currentEffectiveSlotHeight
                                    val accY = currentDragAccumulatedY
                                    val toIdx = if (slotH > 0f) {
                                        (fromIdx + kotlin.math.round(accY / slotH).toInt())
                                            .coerceIn(0, currentActive.lastIndex)
                                    } else fromIdx
                                    draggingProviderName = null
                                    providerDragAccumulatedY = 0f
                                    if (fromIdx != toIdx && fromIdx in currentActive.indices && toIdx in currentActive.indices) {
                                        val next = ArrayList(currentActive)
                                        val moved = next.removeAt(fromIdx)
                                        next.add(toIdx, moved)
                                        working.value = next
                                    }
                                }
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize()
                                        .background(
                                            MaterialTheme.colorScheme.surfaceVariant,
                                            RoundedCornerShape(12.dp),
                                        )
                                        .padding(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    itemsIndexed(
                                        working.value.toList(),
                                        key = { index, id -> "tv-feed-active-$id-$index" },
                                    ) { index, id ->
                                        val provider = providers.firstOrNull { it.config.id == id }
                                            ?: return@itemsIndexed
                                        RefActiveProviderItem(
                                            index = index,
                                            totalActive = working.value.size,
                                            provider = provider,
                                            catalogCount = catalogNames[id]?.size,
                                            catalogNames = sortedNames(provider),
                                            disabled = disabledCatalogs[id].orEmpty(),
                                            isDraggingThis = draggingProviderName == id,
                                            dragOffsetY = providerDragAccumulatedY,
                                            targetShiftY = when {
                                                draggingProviderName == id -> providerDragAccumulatedY
                                                draggingProviderName != null && providerDragInitialIndex < currentTargetIndex &&
                                                    index in (providerDragInitialIndex + 1)..currentTargetIndex -> -effectiveSlotHeight
                                                draggingProviderName != null && providerDragInitialIndex > currentTargetIndex &&
                                                    index in currentTargetIndex until providerDragInitialIndex -> effectiveSlotHeight
                                                else -> 0f
                                            },
                                            onHeightMeasured = { h ->
                                                if (providerSlotHeightPx == 0f && h > 0f) {
                                                    providerSlotHeightPx = h + 8f
                                                }
                                            },
                                            onDragStart = {
                                                draggingProviderName = id
                                                providerDragInitialIndex = currentActive.indexOf(id)
                                                providerDragAccumulatedY = 0f
                                            },
                                            onDragEnd = { onDropProvider() },
                                            onDragCancel = {
                                                draggingProviderName = null
                                                providerDragAccumulatedY = 0f
                                            },
                                            onDragDelta = { dy -> providerDragAccumulatedY += dy },
                                            onMoveUp = {
                                                if (index > 0) {
                                                    val next = ArrayList(working.value)
                                                    val tmp = next[index - 1]
                                                    next[index - 1] = next[index]
                                                    next[index] = tmp
                                                    working.value = next
                                                }
                                            },
                                            onMoveDown = {
                                                if (index < working.value.lastIndex) {
                                                    val next = ArrayList(working.value)
                                                    val tmp = next[index + 1]
                                                    next[index + 1] = next[index]
                                                    next[index] = tmp
                                                    working.value = next
                                                }
                                            },
                                            onRemove = { working.value = ArrayList(working.value - id) },
                                            onToggleCatalog = { catalog, enabled ->
                                                scope.launch {
                                                    app.store.setCatalogEnabled(id, catalog, enabled)
                                                }
                                            },
                                            onMoveCatalog = { from, to ->
                                                sortedNames(provider)?.let { current ->
                                                    scope.launch {
                                                        app.store.moveCatalog(id, from, to, current)
                                                    }
                                                }
                                            },
                                        )
                                    }
                                }
                            }
                        }

            }
            @Composable
            fun AvailPane(mod: Modifier) {
                        // Right column: available plugins
                        Column(modifier = mod) {
                            Text(
                                tr("Available Plugins"),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            val filteredInactive = providers
                                .filter { it.config.id !in working.value && matches(it) }
                                .sortedBy { it.config.name }
                            val duplicateInactiveNames = remember(filteredInactive) {
                                filteredInactive.groupBy { it.config.name }
                                    .filterValues { it.size > 1 }.keys
                            }
                            if (filteredInactive.isEmpty()) {
                                Box(
                                    modifier = Modifier.fillMaxSize()
                                        .background(
                                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                            RoundedCornerShape(12.dp),
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        tr("All plugins are active"),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            } else {
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize()
                                        .background(
                                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                            RoundedCornerShape(12.dp),
                                        )
                                        .padding(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    items(filteredInactive.size, key = { index ->
                                        "tv-feed-avail-${filteredInactive[index].config.id}-$index"
                                    }) { index ->
                                        val provider = filteredInactive[index]
                                        val repoTag = if (provider.config.name in duplicateInactiveNames) {
                                            repoNameById[provider.config.id]?.takeIf { it.isNotBlank() }
                                        } else null
                                        Row(
                                            modifier = Modifier.fillMaxWidth()
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(MaterialTheme.colorScheme.surface)
                                                .padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            if (!provider.config.iconUrl.isNullOrBlank()) {
                                                AsyncImage(
                                                    model = provider.config.iconUrl,
                                                    contentDescription = null,
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier.size(32.dp)
                                                        .clip(RoundedCornerShape(8.dp)),
                                                )
                                                Spacer(modifier = Modifier.width(12.dp))
                                            } else {
                                                Box(
                                                    modifier = Modifier.size(32.dp)
                                                        .clip(RoundedCornerShape(8.dp))
                                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                                    contentAlignment = Alignment.Center,
                                                ) {
                                                    Icon(
                                                        Icons.Filled.Extension,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        modifier = Modifier.size(16.dp),
                                                    )
                                                }
                                                Spacer(modifier = Modifier.width(12.dp))
                                            }
                                            Column(modifier = Modifier.weight(1f)) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                ) {
                                                    Text(
                                                        provider.config.name,
                                                        style = MaterialTheme.typography.bodyLarge,
                                                        fontWeight = FontWeight.Medium,
                                                        color = MaterialTheme.colorScheme.onSurface,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                        modifier = Modifier.weight(1f, fill = false),
                                                    )
                                                    if (!repoTag.isNullOrBlank()) {
                                                        Spacer(modifier = Modifier.width(4.dp))
                                                        Text(
                                                            "($repoTag)",
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = MaterialTheme.colorScheme.primary,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis,
                                                        )
                                                    }
                                                }
                                                val availCount = catalogNames[provider.config.id]?.size
                                                Text(
                                                    if (availCount != null) "$availCount catalogs available"
                                                    else refProviderSubtitle(provider),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                            FilledTonalIconButton(onClick = {
                                                working.value =
                                                    ArrayList((working.value + provider.config.id).distinct())
                                            }, modifier = Modifier.tvFocusRing(50.dp)) {
                                                Icon(Icons.Filled.Add, contentDescription = tr("Add"))
                                            }
                                        }
                                    }
                                }
                            }
                        }            }
            // MAIN CONTENT (takes remaining space)
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                val cp = catalogProvider
                if (cp != null) {
                    // CATALOG TWEAKING SUB-SCREEN
                    RefCatalogTuneScreen(
                        provider = cp,
                        names = sortedNames(cp),
                        disabled = disabledCatalogs[cp.config.id].orEmpty(),
                        onToggle = { catalog, enabled ->
                            scope.launch { app.store.setCatalogEnabled(cp.config.id, catalog, enabled) }
                        },
                        onMove = { from, to ->
                            sortedNames(cp)?.let { current ->
                                scope.launch { app.store.moveCatalog(cp.config.id, from, to, current) }
                            }
                        },
                    )
                } else if (!isAdvancedMode) {
                    // SIMPLE MODE: 4 providers per row; a tap picks and closes.
                    val filteredProviders = providers.filter { matches(it) }
                    val sortedProviders = filteredProviders.sortedBy { it.config.name }
                    LazyVerticalGrid(columns = GridCells.Fixed(4), modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), RoundedCornerShape(12.dp)).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(sortedProviders.size, key = { index -> sortedProviders[index].config.name + "_" + sortedProviders[index].config.id + "_$index" }) { index ->
                            val provider = sortedProviders[index]
                            val isActive = provider.config.id in working.value
                            RefProviderCard(name = provider.config.name, repoTag = null, subtitle = catalogNames[provider.config.id]?.size?.let { it.toString() + " catalogs" } ?: refProviderSubtitle(provider), selected = isActive, iconUrl = provider.config.iconUrl, iconName = provider.config.name, onClick = { working.value = ArrayList(listOf(provider.config.id)); onSave(listOf(provider.config.id)) }, compact = true)
                        }
                    }
                } else if (advNarrow) {
                    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        ActivePane(Modifier.weight(1f).fillMaxWidth())
                        AvailPane(Modifier.weight(1f).fillMaxWidth())
                    }
                } else {
                    Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        ActivePane(Modifier.weight(1f).fillMaxHeight())
                        AvailPane(Modifier.weight(1f).fillMaxHeight())

                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // BOTTOM ACTION BAR
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (catalogProvider != null) {
                    OutlinedButton(onClick = { catalogProvider = null }, modifier = Modifier.tvFocusRing(12.dp)) {
                        Text(tr("Back"))
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                }
                Button(onClick = {
                    if (!isAdvancedMode && working.value.size > 1) {
                        val top = working.value.firstOrNull()
                            ?: providers.firstOrNull()?.config?.id
                        if (top != null) onSave(listOf(top)) else onDismiss()
                    } else {
                        catalogProvider = null
                        onSave(working.value.toList())
                    }
                }) {
                    Text(tr("Save & Close"))
                }
            }
        }
    }

}

/** Catalog enable/disable + reorder sub-screen ("Customize X"). */
@Composable
private fun RefCatalogTuneScreen(
    provider: ContentProvider,
    names: List<String>?,
    disabled: Set<String>,
    onToggle: (String, Boolean) -> Unit,
    onMove: (Int, Int) -> Unit,
) {
    if (names == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    if (names.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                tr("No catalogs available for this provider."),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    var draggingKey by remember { mutableStateOf<String?>(null) }
    var dragAccumulatedY by remember { mutableStateOf(0f) }
    var dragInitialIndex by remember { mutableStateOf(0) }
    var slotHeightPx by remember { mutableStateOf(0f) }
    val fallbackHeight = with(LocalDensity.current) { 56.dp.toPx() }
    val effectiveSlotHeight = if (slotHeightPx > 0f) slotHeightPx else fallbackHeight
    val currentTargetIndex = if (draggingKey != null && effectiveSlotHeight > 0f) {
        (dragInitialIndex + kotlin.math.round(dragAccumulatedY / effectiveSlotHeight).toInt())
            .coerceIn(0, names.lastIndex)
    } else dragInitialIndex
    val currentNames by rememberUpdatedState(names)
    val currentEffectiveSlotHeight by rememberUpdatedState(effectiveSlotHeight)
    val currentDragAccumulatedY by rememberUpdatedState(dragAccumulatedY)
    val currentDragInitialIndex by rememberUpdatedState(dragInitialIndex)
    val onDrop by rememberUpdatedState {
        val fromIdx = currentDragInitialIndex
        val slotH = currentEffectiveSlotHeight
        val accY = currentDragAccumulatedY
        val toIdx = if (slotH > 0f) {
            (fromIdx + kotlin.math.round(accY / slotH).toInt())
                .coerceIn(0, currentNames.lastIndex)
        } else fromIdx
        draggingKey = null
        dragAccumulatedY = 0f
        if (fromIdx != toIdx && fromIdx in currentNames.indices && toIdx in currentNames.indices) {
            onMove(fromIdx, toIdx)
        }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize()
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                RoundedCornerShape(12.dp),
            )
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(names, key = { index, cat -> "${provider.config.id}_$cat-$index" }) { index, catalog ->
            val catalogKey = "${provider.config.id}_$catalog-$index"
            val isEnabled = catalog !in disabled
            val isDraggingThis = draggingKey == catalogKey
            val targetShiftY = when {
                isDraggingThis -> dragAccumulatedY
                draggingKey != null && dragInitialIndex < currentTargetIndex &&
                    index in (dragInitialIndex + 1)..currentTargetIndex -> -effectiveSlotHeight
                draggingKey != null && dragInitialIndex > currentTargetIndex &&
                    index in currentTargetIndex until dragInitialIndex -> effectiveSlotHeight
                else -> 0f
            }
            val animatedShiftY by animateFloatAsState(
                targetValue = targetShiftY,
                animationSpec = androidx.compose.animation.core.spring(
                    stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow,
                ),
            )
            val elevation by animateDpAsState(if (isDraggingThis) 12.dp else 0.dp)
            val scale by animateFloatAsState(if (isDraggingThis) 1.02f else 1.0f)
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (isDraggingThis) {
                    MaterialTheme.colorScheme.surfaceVariant
                } else if (isEnabled) {
                    MaterialTheme.colorScheme.surface
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                },
                border = BorderStroke(
                    if (isDraggingThis) 1.5.dp else 0.5.dp,
                    if (isDraggingThis) MaterialTheme.colorScheme.primary
                    else Color.White.copy(alpha = 0.08f),
                ),
                shadowElevation = elevation,
                modifier = Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { coordinates ->
                        if (coordinates.size.height > 0 && slotHeightPx == 0f) {
                            slotHeightPx = coordinates.size.height.toFloat() + 8f
                        }
                    }
                    .zIndex(if (isDraggingThis) 100f else 1f)
                    .scale(scale)
                    .graphicsLayer {
                        translationY = if (isDraggingThis) dragAccumulatedY else animatedShiftY
                    },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier.size(32.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (isDraggingThis) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                                else Color.Transparent,
                            )
                            .pointerInput(catalogKey) {
                                detectDragGestures(
                                    onDragStart = {
                                        draggingKey = catalogKey
                                        dragInitialIndex = index
                                        dragAccumulatedY = 0f
                                    },
                                    onDragEnd = { onDrop() },
                                    onDragCancel = {
                                        draggingKey = null
                                        dragAccumulatedY = 0f
                                    },
                                ) { change, dragAmount ->
                                    change.consume()
                                    dragAccumulatedY += dragAmount.y
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.DragIndicator,
                            contentDescription = tr("Hold and drag to reorder"),
                            tint = if (isDraggingThis) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${index + 1}.",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (isEnabled) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.width(32.dp),
                    )
                    Text(
                        text = catalog,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = if (isEnabled) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                RoundedCornerShape(50),
                            )
                            .padding(horizontal = 4.dp),
                    ) {
                        IconButton(
                            onClick = { onMove(index, index - 1) },
                            enabled = index > 0,
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                Icons.Filled.ArrowUpward,
                                contentDescription = tr("Move Up"),
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        IconButton(
                            onClick = { onMove(index, index + 1) },
                            enabled = index < names.lastIndex,
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                Icons.Filled.ArrowDownward,
                                contentDescription = tr("Move Down"),
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Switch(
                        checked = isEnabled,
                        onCheckedChange = { onToggle(catalog, it) },
                    )
                }
            }
        }
    }
}

/** One Active-pane row: grip, number, icon, name/counts, arrows, caret, remove. */
@Composable
private fun RefActiveProviderItem(
    index: Int,
    totalActive: Int,
    provider: ContentProvider,
    catalogCount: Int?,
    catalogNames: List<String>?,
    disabled: Set<String>,
    isDraggingThis: Boolean,
    dragOffsetY: Float,
    targetShiftY: Float,
    onHeightMeasured: (Float) -> Unit,
    onDragStart: () -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    onDragDelta: (Float) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
    onToggleCatalog: (String, Boolean) -> Unit,
    onMoveCatalog: (Int, Int) -> Unit,
) {
    var isExpanded by remember { mutableStateOf(false) }
    val elevation by animateDpAsState(if (isDraggingThis) 24.dp else 0.dp)
    val scale by animateFloatAsState(if (isDraggingThis) 1.03f else 1.0f)
    val animatedShiftY by animateFloatAsState(
        targetValue = targetShiftY,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
    )
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
    val currentOnDragCancel by rememberUpdatedState(onDragCancel)
    val currentOnDragDelta by rememberUpdatedState(onDragDelta)
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (isDraggingThis) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.98f)
        else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            if (isDraggingThis) 2.dp else 0.5.dp,
            if (isDraggingThis) MaterialTheme.colorScheme.primary
            else Color.White.copy(alpha = 0.08f),
        ),
        shadowElevation = elevation,
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { coordinates ->
                if (coordinates.size.height > 0) {
                    onHeightMeasured(coordinates.size.height.toFloat())
                }
            }
            .zIndex(if (isDraggingThis) 100f else 1f)
            .scale(scale)
            .graphicsLayer {
                translationY = if (isDraggingThis) dragOffsetY else animatedShiftY
            },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
                .clickable(enabled = !isDraggingThis) { isExpanded = !isExpanded },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Drag grip handle
                Box(
                    modifier = Modifier.size(32.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            if (isDraggingThis) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                            else Color.Transparent,
                        )
                        .pointerInput(provider.config.id) {
                            detectDragGestures(
                                onDragStart = { currentOnDragStart() },
                                onDragEnd = { currentOnDragEnd() },
                                onDragCancel = { currentOnDragCancel() },
                            ) { change, dragAmount ->
                                change.consume()
                                currentOnDragDelta(dragAmount.y)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.DragIndicator,
                        contentDescription = tr("Hold and drag to reorder"),
                        tint = if (isDraggingThis) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "${index + 1}.",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 10.dp, start = 4.dp),
                )
                if (!provider.config.iconUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = provider.config.iconUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)),
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                } else {
                    Box(
                        modifier = Modifier.size(40.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.Extension,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        provider.config.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val total = catalogCount
                    Text(
                        if (total != null) "Catalogs: ${total - disabled.size}/$total active"
                        else refProviderSubtitle(provider),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            RoundedCornerShape(50),
                        )
                        .padding(horizontal = 4.dp),
                ) {
                    IconButton(onClick = onMoveUp, enabled = index > 0, modifier = Modifier.size(28.dp).tvFocusRing(50.dp)) {
                        Icon(
                            Icons.Filled.ArrowUpward,
                            contentDescription = tr("Move Up"),
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    IconButton(
                        onClick = onMoveDown,
                        enabled = index < totalActive - 1,
                        modifier = Modifier.size(28.dp).tvFocusRing(50.dp),
                    ) {
                        Icon(
                            Icons.Filled.ArrowDownward,
                            contentDescription = tr("Move Down"),
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Icon(
                    imageVector = if (isExpanded) Icons.Filled.KeyboardArrowUp
                    else Icons.Filled.KeyboardArrowDown,
                    contentDescription = tr("Expand"),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp),
                )
                IconButton(
                    onClick = onRemove,
                    modifier = Modifier.background(
                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
                        RoundedCornerShape(50),
                    ).tvFocusRing(50.dp),
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = tr("Remove"),
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
            // Expandable catalog section
            if (isExpanded) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(
                        tr("Toggle Catalogs"),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    val sorted = catalogNames
                    if (sorted == null) {
                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        }
                    } else if (sorted.isEmpty()) {
                        Text(
                            tr("No catalogs available."),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        sorted.forEachIndexed { catIdx, catalog ->
                            val isEnabled = catalog !in disabled
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onToggleCatalog(catalog, !isEnabled) }
                                    .tvFocusRing(8.dp)
                                    .padding(vertical = 8.dp, horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = "${catIdx + 1}. $catalog",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (isEnabled) MaterialTheme.colorScheme.onSurface
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .background(
                                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                            RoundedCornerShape(50),
                                        )
                                        .padding(horizontal = 4.dp),
                                ) {
                                    IconButton(
                                        onClick = { onMoveCatalog(catIdx, catIdx - 1) },
                                        enabled = catIdx > 0,
                                        modifier = Modifier.size(24.dp),
                                    ) {
                                        Icon(
                                            Icons.Filled.ArrowUpward,
                                            contentDescription = tr("Move Up"),
                                            modifier = Modifier.size(14.dp),
                                        )
                                    }
                                    IconButton(
                                        onClick = { onMoveCatalog(catIdx, catIdx + 1) },
                                        enabled = catIdx < sorted.lastIndex,
                                        modifier = Modifier.size(24.dp),
                                    ) {
                                        Icon(
                                            Icons.Filled.ArrowDownward,
                                            contentDescription = tr("Move Down"),
                                            modifier = Modifier.size(14.dp),
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Switch(
                                    checked = isEnabled,
                                    onCheckedChange = { onToggleCatalog(catalog, it) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * One provider card for the PHONE picker sheet (HomeScreen.ProviderPickerSheet —
 * untouched): icon + name + repo tag + supporting line, purple when picked. A
 * tap picks; on touch a half-second hold ticks the multi-pick (tvPress only
 * answers remote keys, so the press is timed here and clickable stays OFF on
 * that path, or the tap would fire twice).
 */
@Composable
fun TvProviderCard(
    name: String,
    supporting: String?,
    selected: Boolean,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    onHold: (() -> Unit)? = null,
    /** Duplicate-name tag ("(repo)"). */
    tag: String? = null,
    /** Country flag ("🇮🇳") drawn before the name, null when the row's
     *  language is not known (see ProviderFlags — never guessed). */
    flag: String? = null,
    /** Phone picker actions (pin, pack caret) — null on TV, which has none. */
    trailing: (@Composable () -> Unit)? = null,
) {
    val shape = RoundedCornerShape(20.dp)
    // Fresh lambdas for the gesture below (see PickerRow's note): the touch
    // hold's pointerInput key never changes, so without these every tap after
    // the first recomposition would compute from a dead snapshot.
    val latestClick by rememberUpdatedState(onClick)
    val latestHold by rememberUpdatedState(onHold)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                if (selected) Cs3.accent.copy(alpha = 0.28f)
                else TvDesktop.SurfaceCard.copy(alpha = 0.45f)
            )
            .border(
                1.dp,
                if (selected) Cs3.accent
                else TvDesktop.GlassEdge,
                shape,
            )
            .then(
                when {
                    // No hold: a plain tap card, TV and touch alike.
                    onHold == null -> Modifier.clickable(onClick = onClick)
                    // Television keeps the remote key hold it already had.
                    TvMode.isTv -> Modifier.clickable(onClick = onClick)
                        .tvPress(previewPass = true, onClick = onClick, onHold = onHold)
                    // Touch never reached onHold (tvPress only answers remote
                    // keys), so a held card did nothing on phones: the press
                    // is timed here — a tap picks, half a second ticks the
                    // multi-pick — and clickable stays OFF, or the tap would
                    // fire twice (once from each handler).
                    else -> Modifier
                        .tvPress(previewPass = true, onClick = { latestClick() }, onHold = { latestHold?.invoke() })
                        .pointerInput(Unit) { holdOrTap({ latestHold?.invoke() }, { latestClick() }) }
                }
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon()
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!flag.isNullOrBlank()) {
                    Text(
                        flag,
                        fontSize = 13.sp,
                        maxLines = 1,
                    )
                    Spacer(Modifier.width(5.dp))
                }
                Text(
                    name,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                    fontWeight = FontWeight.SemiBold,
                    color = TvDesktop.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (!tag.isNullOrBlank()) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        tag,
                        style = MaterialTheme.typography.labelSmall,
                        color = Cs3.accent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (!supporting.isNullOrBlank()) {
                Text(
                    supporting,
                    style = MaterialTheme.typography.labelSmall,
                    color = TvDesktop.TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(2.dp))
            trailing()
        }
    }
}

/**
 * The profile menu: the active profile first, every other profile to switch
 * into, an inline Add row, and Manage (which opens Settings, where the full
 * profile management lives). A locked target refuses the switch — the caller
 * reports it (see [onLockedTarget]).
 */
@Composable
fun TvProfileMenuDialog(
    profiles: List<Profiles.Profile>,
    activeId: String?,
    onSwitch: (String) -> Unit,
    onAdd: (String) -> Unit,
    onManage: () -> Unit,
    onDismiss: () -> Unit,
) {
    var adding by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(TvDesktop.CornerLarge),
        containerColor = TvDesktop.SurfaceElevated,
        title = { Text(tr("Profiles"), fontWeight = FontWeight.Bold, color = TvDesktop.TextPrimary) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    tr("Active Profile"),
                    style = MaterialTheme.typography.labelMedium,
                    color = Cs3.accent,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(6.dp))
                profiles.forEach { p ->
                    val active = p.id == activeId
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(TvDesktop.CornerCard))
                            .background(
                                if (active) Cs3.accent.copy(alpha = 0.16f)
                                else Color.Transparent
                            )
                            .clickable(enabled = !active) { onSwitch(p.id) }
                            .tvPress(previewPass = true, onClick = { if (!active) onSwitch(p.id) })
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val initial = p.name.trim().firstOrNull()?.uppercase() ?: "•"
                        Box(
                            Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(
                                    if (active) Cs3.accent
                                    else TvDesktop.SurfaceElevated
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                initial,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (active) MaterialTheme.colorScheme.onPrimary
                                else TvDesktop.TextMuted,
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            p.name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (active) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                tint = Cs3.accent,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (adding) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium,
                        placeholder = { Text(tr("Profile name…")) },
                        shape = RoundedCornerShape(TvDesktop.CornerCard),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = {
                            adding = false
                            newName = ""
                        }) { Text(tr("Cancel")) }
                        TextButton(
                            onClick = {
                                val name = newName.trim()
                                if (name.isNotEmpty()) {
                                    adding = false
                                    newName = ""
                                    onAdd(name)
                                }
                            }
                        ) { Text(tr("Create")) }
                    }
                } else {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(TvDesktop.CornerCard))
                            .clickable { adding = true }
                            .tvPress(previewPass = true, onClick = { adding = true })
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = null,
                            tint = Cs3.accent,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            tr("Add Profile"),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(TvDesktop.CornerCard))
                        .clickable(onClick = onManage)
                        .tvPress(previewPass = true, onClick = onManage)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.Settings,
                        contentDescription = null,
                        tint = TvDesktop.TextMuted,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        tr("Manage Profiles"),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(tr("Close")) }
        },
    )
}
