package com.hikari.app.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.hikari.app.data.MediaItem

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
