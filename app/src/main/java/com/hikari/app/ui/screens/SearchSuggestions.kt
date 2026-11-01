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
import java.util.concurrent.ConcurrentHashMap

/** One "type-ahead" suggestion: just a title and whether it is a series. */
data class SearchSuggestion(
    val title: String,
    val isSeries: Boolean,
)

/**
 * Type-ahead suggestions for the Search tab.
 *
 * Uses the SAME TMDB key the rest of the app already uses (the personal key from
 * Settings when one is saved, otherwise the built-in one) through
 * [TmdbResolver.apiGet], so nothing new has to be configured. It only returns
 * titles - it never starts a search by itself.
 */
object SearchSuggestionsApi {
    private const val MAX_SUGGESTIONS = 8

    private val cache = ConcurrentHashMap<String, List<SearchSuggestion>>()

    suspend fun fetch(query: String): List<SearchSuggestion> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        val cacheKey = q.lowercase()
        cache[cacheKey]?.let { return it }

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
            if (out.size >= MAX_SUGGESTIONS) break
        }
        if (out.isNotEmpty()) {
            if (cache.size > 200) cache.clear()
            cache[cacheKey] = out
        }
        return out
    }
}

/**
 * The suggestion list shown under the search box while the user is typing.
 *
 * Tapping a row runs that title ([onPick]); the small up-left arrow only copies
 * the title into the search box so it can be edited further ([onFill]).
 */
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
                        imageVector = if (s.isSeries) Icons.Filled.Tv else Icons.Filled.Movie,
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
