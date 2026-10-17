package com.hikari.app.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.hikari.app.data.MediaItem

/**
 * The Manga tab's search state, kept across detail-back navigation.
 *
 * The tab is a plain composable: opening a title leaves the composition, so
 * `remember` rows would vanish and coming back would re-ask every engine for
 * a question whose answers were just on screen. This view-model is scoped to
 * the Manga entry instead, so the rows survive the round trip and the search
 * effect (see MangaScreen) skips re-searching when its key is unchanged.
 *
 * It also carries the per-engine page cursor behind the endless scroll: each
 * engine's next page to ask ([nextPage]) and which engines answered a page
 * empty and have nothing more ([pagesDone]). A failed page (timeout, thrown
 * exception — a Cloudflare wall reads exactly like this) is NOT an empty
 * page: it counts a strike in [pageFails] and the engine is only retired
 * after 3 consecutive failures, so one walled moment can never end the list.
 */
class MangaSearchViewModel : ViewModel() {
    var results by mutableStateOf<List<MediaItem>>(emptyList())
    var searching by mutableStateOf(false)
    var enginesLeft by mutableIntStateOf(0)
    var loadingMore by mutableStateOf(false)
    var lastKey: String? = null
    val nextPage = mutableStateMapOf<String, Int>()
    val pagesDone = mutableStateMapOf<String, Boolean>()
    val pageFails = mutableStateMapOf<String, Int>()
}
