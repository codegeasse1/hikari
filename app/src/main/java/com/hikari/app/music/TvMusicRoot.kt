package com.hikari.app.music

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.hikari.app.tv.TvDesktop
import com.hikari.app.tv.TvUi
import kotlinx.coroutines.delay

/**
 * The whole "HIKari music" experience on a television: Home / Search / Library pages, the song and
 * album pages, the now-playing bar and the full-screen player.
 *
 * AppRoot draws this over Hikari's own pages when the rail's Music tab is chosen (the rail stays on
 * screen at the left, exactly like every other tab, and steps aside only while the full player is
 * open - see [TvMusicUi.playerOpen]). It is the television counterpart of [MusicRoot], which keeps
 * serving phones and tablets.
 *
 * Back: closes the player, then goes up one page, then back to Home, and from Home leaves music and
 * returns to Hikari.
 */
@UnstableApi
@Composable
fun TvMusicRoot(startPad: Dp = TvUi.RAIL_WIDTH + 24.dp) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) {
        MusicStore.ensure(ctx)
        MusicPlayer.init(ctx)
    }
    val st by MusicPlayer.state.collectAsState()
    var tab by rememberSaveable { mutableStateOf(0) }
    val stack = remember { mutableStateListOf<MRoute>() }
    val playerOpen = TvMusicUi.playerOpen
    val pages = rememberSaveableStateHolder()
    val pagesFocus = remember { FocusRequester() }

    // AppRoot hides the rail while the player is open; never leave that flag behind.
    DisposableEffect(Unit) {
        onDispose { TvMusicUi.playerOpen = false }
    }

    LaunchedEffect(st.error) {
        val msg = st.error
        if (msg != null) {
            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
            MusicPlayer.clearError()
        }
    }

    // When the player closes, the pages come back and the remote needs somewhere to land.
    var playerWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(playerOpen) {
        if (!playerOpen && playerWasOpen) {
            delay(120)
            runCatching { pagesFocus.requestFocus() }
        }
        playerWasOpen = playerOpen
    }

    // Registered in this order so the last one wins: player, then page, then the Home tab.
    BackHandler(enabled = true) {
        if (tab != 0) tab = 0 else MusicMode.setActive(ctx, false)
    }
    BackHandler(enabled = stack.isNotEmpty()) { stack.removeAt(stack.lastIndex) }
    BackHandler(enabled = playerOpen) { TvMusicUi.playerOpen = false }

    val openPlayer: () -> Unit = { TvMusicUi.playerOpen = true }
    val openItem: (MItem) -> Unit = { item ->
        stack.add(MRoute.Remote(item.kind, item.id, item.title, item.thumb))
    }
    val openRoute: (MRoute) -> Unit = { route -> stack.add(route) }
    val goBack: () -> Unit = { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }
    val tabs: @Composable () -> Unit = {
        TvMusicTabs(tab) { i ->
            stack.clear()
            tab = i
        }
    }

    Box(Modifier.fillMaxSize().background(TvDesktop.Background)) {
        // The pages are taken out of composition (not just covered) while the player is open, so the
        // D-pad can never wander onto a card hidden under it. Their saved state (scroll position,
        // search text, library tab) is kept and comes back with them.
        if (!playerOpen) {
            pages.SaveableStateProvider("tv-music-pages") {
                Box(
                    Modifier
                        .fillMaxSize()
                        .focusRequester(pagesFocus)
                        .focusGroup(),
                ) {
                    val top = stack.lastOrNull()
                    when {
                        top is MRoute.Remote -> TvMusicDetail(top, startPad, openItem, openPlayer)
                        top is MRoute.Local -> TvMusicLocal(top, startPad, goBack, openPlayer)
                        tab == 0 -> TvMusicHome(startPad, tabs, openItem, openPlayer)
                        tab == 1 -> TvMusicSearch(startPad, tabs, openItem, openPlayer)
                        else -> TvMusicLibrary(startPad, tabs, openRoute, openPlayer)
                    }
                    TvMusicNowPlaying(
                        startPad = startPad,
                        onOpen = openPlayer,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }
        AnimatedVisibility(
            visible = playerOpen,
            enter = fadeIn(tween(220)),
            exit = fadeOut(tween(180)),
        ) {
            TvMusicFullPlayer(onClose = { TvMusicUi.playerOpen = false })
        }
    }
}
