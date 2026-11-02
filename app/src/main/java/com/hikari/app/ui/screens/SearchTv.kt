package com.hikari.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hikari.app.i18n.tr
import com.hikari.app.tv.TvDesktop
import com.hikari.app.tv.tvPress

/*
 * Television parts of the Search screen:
 *
 *  - TvSearchFilterRow: provider + type + year + genre as ONE line of pills
 *    (the same four pills the phone has), so the results get all the room
 *    below. Each pill opens the same picker box the phone uses.
 *  - TvSearchSuggestionList: the type-ahead list under the search box, with
 *    big remote-friendly rows.
 */

/** One TV filter pill: looks like the TV provider pill, accent-tinted while a filter is on. */
@Composable
private fun TvFilterPill(
    label: String,
    active: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
) {
    val accent = TvDesktop.accent
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (active) accent.copy(alpha = 0.22f) else TvDesktop.SurfaceCard,
        border = BorderStroke(
            1.dp,
            if (active) accent.copy(alpha = 0.75f) else TvDesktop.GlassEdge,
        ),
        modifier = Modifier.tvPress { onClick() },
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Tune,
                contentDescription = null,
                tint = if (active) accent else TvDesktop.TextMuted,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = TvDesktop.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(6.dp))
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = contentDescription,
                tint = TvDesktop.TextMuted,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** Provider, type, year and genre pills in a single line. */
@Composable
internal fun TvSearchFilterRow(
    showScope: Boolean,
    scopeLabel: String,
    scopeActive: Boolean,
    onScope: () -> Unit,
    typeLabel: String,
    typeActive: Boolean,
    onType: () -> Unit,
    yearLabel: String,
    yearActive: Boolean,
    onYear: () -> Unit,
    genreLabel: String,
    genreActive: Boolean,
    onGenre: () -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showScope) {
            item {
                TvFilterPill(scopeLabel, scopeActive, tr("Choose providers"), onScope)
            }
        }
        item {
            TvFilterPill(typeLabel, typeActive, tr("Choose type"), onType)
        }
        item {
            TvFilterPill(yearLabel, yearActive, tr("Choose years"), onYear)
        }
        item {
            TvFilterPill(genreLabel, genreActive, tr("Choose genres"), onGenre)
        }
    }
}

/**
 * The suggestion rows under the search box on TV. The first row is normally the
 * typed text itself (see [SearchSuggestion.isTypedText]), so one press of OK
 * runs exactly what was typed; the rest are title suggestions.
 */
@Composable
internal fun TvSearchSuggestionList(
    suggestions: List<SearchSuggestion>,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(
            suggestions,
            key = { (if (it.isTypedText) "typed:" else "title:") + it.title.lowercase() },
        ) { s ->
            Surface(
                onClick = { onPick(s.title) },
                shape = RoundedCornerShape(TvDesktop.CornerLarge),
                color = TvDesktop.SurfaceCard,
                border = BorderStroke(1.dp, TvDesktop.GlassEdge),
                modifier = Modifier
                    .fillMaxWidth()
                    .tvPress { onPick(s.title) },
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = when {
                            s.isTypedText -> Icons.Filled.Search
                            s.isSeries -> Icons.Filled.Tv
                            else -> Icons.Filled.Movie
                        },
                        contentDescription = null,
                        tint = TvDesktop.TextMuted,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(14.dp))
                    Text(
                        s.title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = TvDesktop.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}
