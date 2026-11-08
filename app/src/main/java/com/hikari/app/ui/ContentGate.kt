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

@Composable
fun rememberNsfwEnabled(): Boolean {
    val app = LocalContext.current.applicationContext as HikariApp
    val flow = remember { app.store.nsfwEnabledFlow() }
    val on by flow.collectAsState(initial = NsfwGate.enabled)
    return on
}

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
