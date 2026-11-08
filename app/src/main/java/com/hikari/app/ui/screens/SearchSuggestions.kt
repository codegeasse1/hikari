package com.hikari.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hikari.app.nuvio.TmdbResolver
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.ConcurrentHashMap

/** One "type-ahead" suggestion: just a title and whether it is a series. */
data class SearchSuggestion(
    val title: String,
    val isSeries: Boolean,
    /** True for the fallback row that is just the typed text (no TMDB match). */
    val isTypedText: Boolean = false,
)

object SearchSuggestionsApi {
    private const val MAX_SUGGESTIONS = 8
    private const val MIN_DIRECT = 3
    private const val MIN_SCORE = 0.67

    private val STOP_WORDS = setOf("the", "a", "an", "of", "and", "in", "to", "for", "on")

    private val cache = ConcurrentHashMap<String, List<SearchSuggestion>>()

    suspend fun fetch(query: String): List<SearchSuggestion> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        val cacheKey = q.lowercase()
        cache[cacheKey]?.let { return it }

        val direct = lookup(q)
        var result: List<SearchSuggestion> = direct
        if (direct.size < MIN_DIRECT) {
            val extra = fuzzyExtras(q, direct)
            if (extra.isNotEmpty()) result = direct + extra
        }
        result = result.take(MAX_SUGGESTIONS)

        if (result.isNotEmpty()) {
            if (cache.size > 200) cache.clear()
            cache[cacheKey] = result
        }
        return result
    }

    /** One TMDB multi-search; movies and series only, deduplicated by title. */
    private suspend fun lookup(q: String): List<SearchSuggestion> {
        val json = try {
            TmdbResolver.apiGet(
                "/search/multi",
                mapOf(
                    "query" to q,
                    "include_adult" to "false",
                    "page" to "1",
                ),
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

        val arr = json?.optJSONArray("results") ?: return emptyList()
        val out = ArrayList<SearchSuggestion>()
        val seen = HashSet<String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val type = o.optString("media_type")
            val raw = when (type) {
                "movie" -> o.optString("title")
                "tv" -> o.optString("name")
                else -> continue
            }
            val title = raw.trim()
            if (title.isEmpty()) continue
            if (!seen.add(title.lowercase())) continue
            out.add(SearchSuggestion(title = title, isSeries = type == "tv"))
        }
        return out
    }

    /**
     * Titles found through the first letters of the typed words, kept only when
     * they match (allowing small typos / unfinished words) most of what was typed.
     */
    private suspend fun fuzzyExtras(
        q: String,
        have: List<SearchSuggestion>,
    ): List<SearchSuggestion> {
        val typed = wordsOf(q)
        if (typed.isEmpty()) return emptyList()

        val probes = typed
            .filter { it.length >= 3 && it !in STOP_WORDS }
            .sortedByDescending { it.length }
            .map { it.take(4) }
            .distinct()
            .take(3)
        if (probes.isEmpty()) return emptyList()

        val pool: List<SearchSuggestion> = coroutineScope {
            probes.map { probe -> async { lookup(probe) } }.awaitAll()
        }.flatten()

        val seen = HashSet<String>()
        for (h in have) seen.add(h.title.lowercase())

        val scored = ArrayList<Pair<SearchSuggestion, Double>>()
        for (s in pool) {
            if (!seen.add(s.title.lowercase())) continue
            val score = similarity(typed, wordsOf(s.title))
            if (score >= MIN_SCORE) scored.add(Pair(s, score))
        }
        return scored.sortedByDescending { it.second }.map { it.first }
    }

    private fun wordsOf(text: String): List<String> =
        text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

    /** Share of the typed words that some title word matches. */
    private fun similarity(typed: List<String>, title: List<String>): Double {
        if (typed.isEmpty()) return 0.0
        var hit = 0
        for (qw in typed) {
            if (title.any { tw -> wordMatches(qw, tw) }) hit++
        }
        return hit.toDouble() / typed.size
    }

    private fun wordMatches(qw: String, tw: String): Boolean {
        if (tw.startsWith(qw)) return true
        val allowed = when {
            qw.length >= 7 -> 2
            qw.length >= 4 -> 1
            else -> 0
        }
        if (allowed == 0) return false
        if (editDistance(qw, tw) <= allowed) return true
        return tw.length > qw.length &&
            editDistance(qw, tw.substring(0, qw.length)) <= allowed
    }

    private fun editDistance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            val swap = prev
            prev = cur
            cur = swap
        }
        return prev[b.length]
    }
}

@Composable
fun SearchSuggestionList(
    suggestions: List<SearchSuggestion>,
    onPick: (String) -> Unit,
    onFill: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(suggestions, key = { it.title.lowercase() }) { s ->
            Surface(
                onClick = { onPick(s.title) },
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = when {
                            s.isTypedText -> Icons.Filled.Search
                            s.isSeries -> Icons.Filled.Tv
                            else -> Icons.Filled.Movie
                        },
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(14.dp))
                    Text(
                        s.title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .clickable { onFill(s.title) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ArrowUpward,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .size(20.dp)
                                .rotate(-45f),
                        )
                    }
                }
            }
        }
    }
}
