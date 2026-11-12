package com.hikari.app.music

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import com.hikari.app.R

/**
 * Which "provider" the app is showing: Hikari (movies / series / anime) or HIKari music.
 * The choice is remembered. Hikari's own screens stay alive underneath, so switching
 * back puts you exactly where you were.
 */
object MusicMode {
    private const val PREFS = "hikari_music_mode"
    private var loaded = false

    var active by mutableStateOf(false)
        private set

    var drawerOpen by mutableStateOf(false)

    fun ensureLoaded(ctx: Context) {
        if (loaded) return
        loaded = true
        active = runCatching {
            ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("active", false)
        }.getOrDefault(false)
    }

    @UnstableApi
    fun setActive(ctx: Context, value: Boolean) {
        ensureLoaded(ctx)
        active = value
        runCatching {
            ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean("active", value).apply()
        }
        // Going back to movies: stop the music so the two never play over each other.
        if (!value) MusicPlayer.pause()
    }
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

// ====================================================================== PROVIDER DRAWER

/** The "Select Provider / Content source" panel that slides in from the left. */
@UnstableApi
@Composable
fun ProviderDrawer() {
    val ctx = LocalContext.current
    MusicMode.ensureLoaded(ctx)
    val cs = MaterialTheme.colorScheme
    val open = MusicMode.drawerOpen
    BackHandler(enabled = open) { MusicMode.drawerOpen = false }

    AnimatedVisibility(visible = open, enter = fadeIn(), exit = fadeOut()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f))
                .pointerInput(Unit) { detectTapGestures { MusicMode.drawerOpen = false } },
        )
    }
    AnimatedVisibility(
        visible = open,
        enter = slideInHorizontally { -it },
        exit = slideOutHorizontally { -it },
    ) {
        Column(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(0.78f)
                .widthIn(max = 340.dp)
                .background(cs.surface)
                .windowInsetsPadding(WindowInsets.systemBars)
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 16.dp)) {
                Text("Select Provider", color = cs.onSurface, fontWeight = FontWeight.Bold, fontSize = 24.sp)
                Spacer(Modifier.height(4.dp))
                Text("Content source", color = cs.onSurfaceVariant, fontSize = 15.sp)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(cs.onSurface.copy(alpha = 0.1f)))
            Spacer(Modifier.height(12.dp))
            ProviderRow(
                name = "hikari",
                selected = !MusicMode.active,
                badge = {
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Brush.linearGradient(listOf(Color(0xFF1B3A66), Color(0xFF3F8CFF)))),
                        contentAlignment = Alignment.Center,
                    ) { Text("hikari", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                },
            ) {
                MusicMode.setActive(ctx, false)
                MusicMode.drawerOpen = false
            }
            ProviderRow(
                name = "HIKari music",
                selected = MusicMode.active,
                badge = {
                    Image(
                        painter = painterResource(id = R.drawable.music_provider_hikari),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                    )
                },
            ) {
                MusicMode.setActive(ctx, true)
                MusicMode.drawerOpen = false
            }
        }
    }
}

@Composable
private fun ProviderRow(name: String, selected: Boolean, badge: @Composable () -> Unit, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) cs.surfaceVariant else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        badge()
        Spacer(Modifier.width(16.dp))
        Text(name, color = cs.onSurface, fontSize = 18.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        if (selected) Icon(Icons.Filled.Check, contentDescription = "Selected", tint = cs.onSurface)
    }
}

// ====================================================================== MUSIC ROOT

/** The whole "HIKari music" experience: Home / Search / Library, mini player and full player. */
@UnstableApi
@Composable
fun MusicRoot() {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    LaunchedEffect(Unit) {
        MusicStore.ensure(ctx)
        MusicPlayer.init(ctx)
    }
    val st by MusicPlayer.state.collectAsState()
    var tab by rememberSaveable { mutableStateOf(0) }
    var playerOpen by rememberSaveable { mutableStateOf(false) }
    val stack = remember { mutableStateListOf<MRoute>() }
    val homeState = rememberLazyListState()
    val searchState = rememberLazyListState()
    val libraryState = rememberLazyListState()

    LaunchedEffect(st.error) {
        val msg = st.error
        if (msg != null) {
            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
            MusicPlayer.clearError()
        }
    }

    // Back: close the player, then pop a page, then return to Home, then send the app to the
    // background (music keeps playing) instead of popping Hikari's hidden screens.
    BackHandler(enabled = true) {
        if (tab != 0) tab = 0 else ctx.findActivity()?.moveTaskToBack(true)
    }
    BackHandler(enabled = stack.isNotEmpty()) { stack.removeAt(stack.lastIndex) }
    BackHandler(enabled = playerOpen) { playerOpen = false }

    val openItem: (MItem) -> Unit = { item ->
        stack.add(MRoute.Remote(item.kind, item.id, item.title, item.thumb))
    }
    val openRoute: (MRoute) -> Unit = { route -> stack.add(route) }
    val openMenu: () -> Unit = { MusicMode.drawerOpen = true }
    val goBack: () -> Unit = { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }

    Box(
        Modifier
            .fillMaxSize()
            .background(cs.background)
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val top = stack.lastOrNull()
                when {
                    top is MRoute.Remote -> MusicDetailScreen(top, goBack, openItem)
                    top is MRoute.Local -> MusicLocalScreen(top, goBack)
                    tab == 0 -> MusicHomeScreen(
                        state = homeState,
                        onOpen = openItem,
                        onHistory = { openRoute(MRoute.Local("history", "Recently played")) },
                        onMenu = openMenu,
                    )
                    tab == 1 -> MusicSearchScreen(searchState, openItem, openMenu)
                    else -> MusicLibraryScreen(libraryState, openRoute, openMenu)
                }
            }
            MusicMiniPlayer { playerOpen = true }
            NavigationBar(containerColor = cs.surface) {
                NavigationBarItem(
                    selected = tab == 0 && stack.isEmpty(),
                    onClick = { stack.clear(); tab = 0 },
                    icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                    label = { Text("Home") },
                )
                NavigationBarItem(
                    selected = tab == 1 && stack.isEmpty(),
                    onClick = { stack.clear(); tab = 1 },
                    icon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    label = { Text("Search") },
                )
                NavigationBarItem(
                    selected = tab == 2 && stack.isEmpty(),
                    onClick = { stack.clear(); tab = 2 },
                    icon = { Icon(Icons.Filled.LibraryMusic, contentDescription = null) },
                    label = { Text("Library") },
                )
            }
        }
        AnimatedVisibility(
            visible = playerOpen,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
        ) {
            MusicFullPlayer(onClose = { playerOpen = false })
        }
    }
}
