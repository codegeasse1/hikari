package com.hikari.app.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.hikari.app.data.Logs
import kotlinx.coroutines.delay

object ScrollKeeper {
    data class Pos(val index: Int, val offset: Int, val anchor: String? = null)

    private val positions = java.util.concurrent.ConcurrentHashMap<String, Pos>()

    fun save(key: String, index: Int, offset: Int, anchor: String? = null) {
        if (index <= 0 && offset <= 0) positions.remove(key)
        else positions[key] = Pos(index, offset, anchor)
    }

    fun save(key: String, offset: Int) {
        if (offset <= 0) positions.remove(key)
        else positions[key] = Pos(0, offset, null)
    }

    fun load(key: String): Pos? = positions[key]

    fun drop(key: String) {
        positions.remove(key)
    }
}

private const val RESTORE_WAIT_MS = 10_000L

private const val FOLLOW_MS = 15_000L

private fun scrollLog(msg: String) {
    runCatching { Logs.log("Scroll", msg) }
}

@Composable
fun keptListState(
    key: String,
    /** Item keys in Lazy order; used to re-find the saved anchor. */
    itemKeys: List<String> = emptyList(),
    /** Called while waiting when the target row is not loaded yet (paged lists). */
    onNeedMore: (() -> Unit)? = null,
): LazyListState {
    val saved = remember(key) { ScrollKeeper.load(key) }
    val startIndex = if (saved?.anchor != null) 0 else (saved?.index ?: 0).coerceAtLeast(0)
    val startOffset = if (saved?.anchor != null) 0 else (saved?.offset ?: 0).coerceAtLeast(0)
    val state = rememberLazyListState(
        initialFirstVisibleItemIndex = startIndex,
        initialFirstVisibleItemScrollOffset = startOffset,
    )
    val keysNow = rememberUpdatedState(itemKeys)
    val needMoreNow = rememberUpdatedState(onNeedMore)
    LaunchedEffect(key) {
        val s = ScrollKeeper.load(key)
        if (s == null) {
            scrollLog("miss key=$key")
            return@LaunchedEffect
        }
        if (s.index <= 0 && s.offset <= 0) return@LaunchedEffect
        scrollLog("load key=$key idx=${s.index} off=${s.offset} anchor=${s.anchor}")
        val deadline = System.currentTimeMillis() + RESTORE_WAIT_MS
        if (s.anchor != null) {
            while (System.currentTimeMillis() < deadline) {
                if (state.isScrollInProgress) {
                    scrollLog("stand-down key=$key (user grabbed list)")
                    return@LaunchedEffect
                }
                if (keysNow.value.indexOf(s.anchor) >= 0) break
                delay(50L)
            }
        }
        var target = s.index.coerceAtLeast(0)
        s.anchor?.let { a ->
            val ai = keysNow.value.indexOf(a)
            if (ai >= 0) target = ai
        }
        var ticks = 0
        while (System.currentTimeMillis() < deadline) {
            if (state.isScrollInProgress) {
                scrollLog("stand-down key=$key (user grabbed list)")
                return@LaunchedEffect
            }
            s.anchor?.let { a ->
                val ai = keysNow.value.indexOf(a)
                if (ai >= 0) target = ai
            }
            if (state.layoutInfo.totalItemsCount > target) {
                if (state.firstVisibleItemIndex != target ||
                    state.firstVisibleItemScrollOffset != s.offset
                ) {
                    runCatching { state.scrollToItem(target, s.offset) }
                }
                scrollLog("restored key=$key target=$target total=${state.layoutInfo.totalItemsCount} anchorFound=" + (s.anchor != null && keysNow.value.indexOf(s.anchor) >= 0))
                if (s.anchor == null) return@LaunchedEffect
                var lastTarget = target
                var driftTicks = 0
                val followUntil = System.currentTimeMillis() + FOLLOW_MS
                while (System.currentTimeMillis() < followUntil) {
                    delay(250L)
                    // Dragging or flinging: the user is driving now.
                    if (state.isScrollInProgress) { scrollLog("stand-down key=$key (user took list during follow)"); return@LaunchedEffect }
                    val ai = keysNow.value.indexOf(s.anchor)
                    if (ai < 0) {
                        // Anchor gone for now (rows reloading): hold unless the
                        // user scrolls away, in which case it is theirs.
                        if (state.firstVisibleItemIndex != lastTarget) { scrollLog("stand-down key=$key (anchor gone, user moved)"); return@LaunchedEffect }
                    } else if (state.firstVisibleItemIndex == ai) {
                        lastTarget = ai
                        driftTicks = 0
                    } else if (ai == lastTarget) {
                        driftTicks++
                        if (driftTicks >= 3) { scrollLog("stand-down key=$key (viewport left anchor)"); return@LaunchedEffect }
                        runCatching { state.scrollToItem(ai, s.offset) }
                    } else {
                        // Anchor moved under a stationary viewport: feed update.
                        driftTicks = 0
                        runCatching { state.scrollToItem(ai, s.offset) }
                        lastTarget = ai
                        scrollLog("followed key=$key target=$ai")
                    }
                }
                return@LaunchedEffect
            }
            if (ticks % 5 == 0) runCatching { needMoreNow.value?.invoke() }
            ticks++
            delay(100L)
        }
        scrollLog("give-up key=$key target=$target total=${state.layoutInfo.totalItemsCount} (staying, no end jump)")
    }
    DisposableEffect(key) {
        onDispose {
            runCatching {
                val idx = state.firstVisibleItemIndex
                val off = state.firstVisibleItemScrollOffset
                val anchor = keysNow.value.getOrNull(idx)
                if (idx <= 0 && off <= 0) scrollLog("drop key=$key (left at top)")
                else {
                    ScrollKeeper.save(key, idx, off, anchor)
                    scrollLog("save key=$key idx=$idx off=$off anchor=$anchor")
                }
            }
        }
    }
    return state
}

@Composable
fun keptGridState(
    key: String,
    /** Item keys in grid order; used to re-find the saved anchor. */
    itemKeys: List<String> = emptyList(),
    /** Called while waiting when the target cell is not loaded yet (paged grids). */
    onNeedMore: (() -> Unit)? = null,
): LazyGridState {
    val saved = remember(key) { ScrollKeeper.load(key) }
    // Same no-high-seed rule as the list version (see keptListState): with an
    // anchor the grid starts at the top and the effect lands on the anchor.
    val startIndex = if (saved?.anchor != null) 0 else (saved?.index ?: 0).coerceAtLeast(0)
    val startOffset = if (saved?.anchor != null) 0 else (saved?.offset ?: 0).coerceAtLeast(0)
    val state = rememberLazyGridState(
        initialFirstVisibleItemIndex = startIndex,
        initialFirstVisibleItemScrollOffset = startOffset,
    )
    val keysNow = rememberUpdatedState(itemKeys)
    val needMoreNow = rememberUpdatedState(onNeedMore)
    LaunchedEffect(key) {
        val s = ScrollKeeper.load(key)
        if (s == null) {
            scrollLog("miss key=$key")
            return@LaunchedEffect
        }
        if (s.index <= 0 && s.offset <= 0) return@LaunchedEffect
        scrollLog("load key=$key idx=${s.index} off=${s.offset} anchor=${s.anchor}")
        val deadline = System.currentTimeMillis() + RESTORE_WAIT_MS
        if (s.anchor != null) {
            while (System.currentTimeMillis() < deadline) {
                if (state.isScrollInProgress) {
                    scrollLog("stand-down key=$key (user grabbed grid)")
                    return@LaunchedEffect
                }
                if (keysNow.value.indexOf(s.anchor) >= 0) break
                delay(50L)
            }
        }
        var target = s.index.coerceAtLeast(0)
        s.anchor?.let { a ->
            val ai = keysNow.value.indexOf(a)
            if (ai >= 0) target = ai
        }
        var ticks = 0
        while (System.currentTimeMillis() < deadline) {
            if (state.isScrollInProgress) {
                scrollLog("stand-down key=$key (user grabbed grid)")
                return@LaunchedEffect
            }
            s.anchor?.let { a ->
                val ai = keysNow.value.indexOf(a)
                if (ai >= 0) target = ai
            }
            if (state.layoutInfo.totalItemsCount > target) {
                if (state.firstVisibleItemIndex != target ||
                    state.firstVisibleItemScrollOffset != s.offset
                ) {
                    runCatching { state.scrollToItem(target, s.offset) }
                }
                scrollLog("restored key=$key target=$target total=${state.layoutInfo.totalItemsCount} anchorFound=" + (s.anchor != null && keysNow.value.indexOf(s.anchor) >= 0))
                if (s.anchor == null) return@LaunchedEffect
                var lastTarget = target
                var driftTicks = 0
                val followUntil = System.currentTimeMillis() + FOLLOW_MS
                while (System.currentTimeMillis() < followUntil) {
                    delay(250L)
                    // Dragging or flinging: the user is driving now.
                    if (state.isScrollInProgress) { scrollLog("stand-down key=$key (user took list during follow)"); return@LaunchedEffect }
                    val ai = keysNow.value.indexOf(s.anchor)
                    if (ai < 0) {
                        // Anchor gone for now (rows reloading): hold unless the
                        // user scrolls away, in which case it is theirs.
                        if (state.firstVisibleItemIndex != lastTarget) { scrollLog("stand-down key=$key (anchor gone, user moved)"); return@LaunchedEffect }
                    } else if (state.firstVisibleItemIndex == ai) {
                        lastTarget = ai
                        driftTicks = 0
                    } else if (ai == lastTarget) {
                        driftTicks++
                        if (driftTicks >= 3) { scrollLog("stand-down key=$key (viewport left anchor)"); return@LaunchedEffect }
                        runCatching { state.scrollToItem(ai, s.offset) }
                    } else {
                        // Anchor moved under a stationary viewport: feed update.
                        driftTicks = 0
                        runCatching { state.scrollToItem(ai, s.offset) }
                        lastTarget = ai
                        scrollLog("followed key=$key target=$ai")
                    }
                }
                return@LaunchedEffect
            }
            if (ticks % 5 == 0) runCatching { needMoreNow.value?.invoke() }
            ticks++
            delay(100L)
        }
        scrollLog("give-up key=$key target=$target total=${state.layoutInfo.totalItemsCount} (staying, no end jump)")
    }
    DisposableEffect(key) {
        onDispose {
            runCatching {
                val idx = state.firstVisibleItemIndex
                val off = state.firstVisibleItemScrollOffset
                val anchor = keysNow.value.getOrNull(idx)
                if (idx <= 0 && off <= 0) scrollLog("drop key=$key (left at top)")
                else {
                    ScrollKeeper.save(key, idx, off, anchor)
                    scrollLog("save key=$key idx=$idx off=$off anchor=$anchor")
                }
            }
        }
    }
    return state
}

@Composable
fun keptScrollState(key: String): ScrollState {
    val saved = remember(key) { ScrollKeeper.load(key) }
    val state = rememberScrollState(initial = (saved?.offset ?: 0).coerceAtLeast(0))
    LaunchedEffect(key) {
        val s = ScrollKeeper.load(key)
        if (s == null) {
            scrollLog("miss key=$key")
            return@LaunchedEffect
        }
        if (s.offset <= 0) return@LaunchedEffect
        scrollLog("load key=$key off=${s.offset}")
        val deadline = System.currentTimeMillis() + RESTORE_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            if (state.isScrollInProgress) {
                scrollLog("stand-down key=$key (user grabbed column)")
                return@LaunchedEffect
            }
            if (state.maxValue >= s.offset) {
                if (state.value != s.offset) runCatching { state.scrollTo(s.offset) }
                scrollLog("restored key=$key off=${s.offset}")
                return@LaunchedEffect
            }
            delay(100L)
        }
        scrollLog("give-up key=$key off=${s.offset} max=${state.maxValue} (staying, no end jump)")
    }
    DisposableEffect(key) {
        onDispose {
            runCatching {
                val off = state.value
                if (off <= 0) scrollLog("drop key=$key (left at top)")
                else {
                    ScrollKeeper.save(key, off)
                    scrollLog("save key=$key off=$off")
                }
            }
        }
    }
    return state
}
