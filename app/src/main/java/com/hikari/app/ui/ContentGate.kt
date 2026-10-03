package com.hikari.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.hikari.app.HikariApp
import com.hikari.app.data.CatalogRow
import com.hikari.app.data.MediaItem
import com.hikari.app.data.NsfwGate

/**
 * The adult-content switch as Compose state, so a screen re-filters the moment it
 * is flipped in Settings.
 *
 * [NsfwGate] itself reads a plain in-memory flag (a list being drawn cannot await
 * DataStore), which means nothing would recompose just because the flag changed.
 * This is the missing half: the flow is collected here, and every list that
 * filters through the gate is remembered against the value, so turning NSFW off
 * empties the adult rows on the next frame instead of on the next navigation.
 */
@Composable
fun rememberNsfwEnabled(): Boolean {
    val app = LocalContext.current.applicationContext as HikariApp
    val flow = remember { app.store.nsfwEnabledFlow() }
    val on by flow.collectAsState(initial = NsfwGate.enabled)
    return on
}

/**
 * The items of [items] that may be shown, deduplicated, as a value that changes
 * when the switch does.
 *
 * Every grid and row in the app draws through one of these instead of filtering
 * for itself: the rule is the same everywhere (see [NsfwGate]) and the remembered
 * key is what makes the switch instant. [dedupe] is on by default because a
 * provider is free to return the same title twice — Compose throws on a duplicated
 * key, so the dedupe has to happen before the grid anyway, and doing both here
 * keeps one allocation instead of two.
 */
@Composable
fun rememberVisibleItems(items: List<MediaItem>, dedupe: Boolean = true): List<MediaItem> {
    val on = rememberNsfwEnabled()
    return remember(items, on, dedupe) {
        val base = if (dedupe) items.distinctBy { it.uniqueId } else items
        NsfwGate.filter(base)
    }
}

/**
 * The rows of a shelf that may be shown: each row's items filtered, and a row
 * whose every item was filtered away dropped with them (see [NsfwGate.filterRows]).
 */
@Composable
fun rememberVisibleRows(rows: List<CatalogRow>): List<CatalogRow> {
    val on = rememberNsfwEnabled()
    return remember(rows, on) { NsfwGate.filterRows(rows) }
}
