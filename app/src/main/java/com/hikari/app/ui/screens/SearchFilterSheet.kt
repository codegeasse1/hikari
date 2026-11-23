package com.hikari.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.hikari.app.i18n.tr

private val DECADE_CHIPS: List<Pair<String, IntRange>> = listOf(
    "This year" to IntRange.EMPTY,
    "2020s" to (2020..2029),
    "2010s" to (2010..2019),
    "2000s" to (2000..2009),
    "1990s" to (1990..1999),
    "Older" to (1900..1989),
)

/** The Discover-style filter sheet: type, genres, years, upcoming, rating,
 *  votes, language and the production-company grid. Every section writes
 *  straight back into the search tab's filter state; closing applies. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SearchFilterSheet(
    scopeLabel: String,
    onOpenProviders: () -> Unit,
    kindKey: String,
    onKindChange: (String) -> Unit,
    genreLabel: String,
    genreActive: Boolean,
    onOpenGenres: () -> Unit,
    years: Set<Int>,
    currentYear: Int,
    onSetYears: (Set<Int>) -> Unit,
    upcoming: Boolean,
    onUpcomingChange: (Boolean) -> Unit,
    minVote: Float,
    onMinVoteChange: (Float) -> Unit,
    minVotes: Int,
    onMinVotesChange: (Int) -> Unit,
    lang: String,
    onLangChange: (String) -> Unit,
    companyName: String?,
    onSelectCompany: (String?) -> Unit,
    resultCount: Int?,
    onClearAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
    )
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Tune, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        tr("Filters"),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onClearAll) { Text(tr("Clear all")) }
                }
            }
            item {
                FilterSectionTitle(tr("Sources"))
                Surface(
                    onClick = onOpenProviders,
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            scopeLabel,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Icon(
                            Icons.Filled.KeyboardArrowDown,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
            item {
                FilterSectionTitle(tr("Type"))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(enabled = false, onClick = {}),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val kinds = listOf("all" to tr("All"), "movies" to tr("Movies"), "series" to tr("TV Shows"), "anime" to tr("Anime"))
                    for ((key, label) in kinds) {
                        val sel = kindKey == key
                        Surface(
                            onClick = { onKindChange(key) },
                            shape = RoundedCornerShape(10.dp),
                            color = if (sel) MaterialTheme.colorScheme.surfaceVariant
                            else MaterialTheme.colorScheme.surface.copy(alpha = 0.45f),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                label,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (sel) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                modifier = Modifier.padding(vertical = 10.dp),
                            )
                        }
                    }
                }
            }
            item {
                FilterSectionTitle(tr("Genres"))
                Surface(
                    onClick = onOpenGenres,
                    shape = RoundedCornerShape(12.dp),
                    color = if (genreActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                    else MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                    border = BorderStroke(
                        1.dp,
                        if (genreActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                        else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                    ),
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            genreLabel,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = if (genreActive) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Icon(
                            Icons.Filled.KeyboardArrowDown,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
            item {
                FilterSectionTitle(tr("Release year"))
                val thisYear = currentYear
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for ((chip, range) in DECADE_CHIPS) {
                        val effective = if (chip == "This year") thisYear..thisYear else range
                        val sel = effective.all { it in years }
                        SheetChip(
                            label = tr(chip),
                            selected = sel,
                            onClick = {
                                onSetYears(
                                    if (sel) years - effective.toSet()
                                    else years + effective.toSet(),
                                )
                            },
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                YearFromToRow(
                    years = years,
                    currentYear = thisYear,
                    onSetYears = onSetYears,
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        tr("Include upcoming releases"),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = upcoming, onCheckedChange = onUpcomingChange)
                }
            }
            item {
                FilterSectionTitle(tr("Rating"))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val steps = listOf(0f to "Any score", 6f to "6+", 7f to "7+", 8f to "8+", 9f to "9+")
                    for ((v, label) in steps) {
                        SheetChip(
                            label = "★ $label",
                            selected = minVote == v,
                            onClick = { onMinVoteChange(v) },
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Slider(
                        value = minVote,
                        onValueChange = { onMinVoteChange((it * 2).toInt() / 2f) },
                        valueRange = 0f..10f,
                        steps = 19,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (minVote <= 0f) tr("Any score") else "★ ${if (minVote == minVote.toInt().toFloat()) minVote.toInt().toString() else minVote.toString()}",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(72.dp),
                    )
                }
            }
            item {
                FilterSectionTitle(tr("Number of ratings"))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val steps = listOf(0 to tr("Auto"), 100 to "100+", 1000 to "1K+", 10000 to "10K+")
                    for ((v, label) in steps) {
                        SheetChip(
                            label = label,
                            selected = minVotes == v,
                            onClick = { onMinVotesChange(v) },
                        )
                    }
                }
            }
            item {
                FilterSectionTitle(tr("Original language"))
                var langOpen by remember { mutableStateOf(false) }
                val langLabel = SearchDiscovery.LANGUAGES.firstOrNull { it.first == lang }?.second
                    ?: tr("Any language")
                Box {
                    Surface(
                        onClick = { langOpen = true },
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
                    ) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (lang.isBlank()) langLabel else langLabel,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (lang.isBlank()) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Icon(
                                Icons.Filled.KeyboardArrowDown,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    DropdownMenu(expanded = langOpen, onDismissRequest = { langOpen = false }) {
                        for ((code, label) in SearchDiscovery.LANGUAGES) {
                            DropdownMenuItem(
                                text = { Text(if (code == lang) "$label  ✓" else label) },
                                onClick = { onLangChange(code); langOpen = false },
                            )
                        }
                    }
                }
            }
            item {
                FilterSectionTitle(tr("Production"))
                CompanyGrid(
                    selected = companyName,
                    onSelect = { name -> onSelectCompany(if (name == companyName) null else name) },
                )
            }
            item {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text(
                        if (resultCount != null) tr("Show %s titles").replace("%s", resultCount.toString())
                        else tr("Show results"),
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun FilterSectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
    )
}

@Composable
private fun SheetChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = if (selected) MaterialTheme.colorScheme.surfaceVariant
        else MaterialTheme.colorScheme.surface.copy(alpha = 0.45f),
        border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
        else null,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
        )
    }
}

@Composable
private fun YearFromToRow(
    years: Set<Int>,
    currentYear: Int,
    onSetYears: (Set<Int>) -> Unit,
) {
    val options = remember(currentYear) { ((currentYear + 1) downTo 1950).toList() }
    fun bounds(): Pair<Int?, Int?> {
        if (years.isEmpty()) return null to null
        return years.min() to years.max()
    }
    val (fromYear, toYear) = bounds()
    var fromOpen by remember { mutableStateOf(false) }
    var toOpen by remember { mutableStateOf(false) }
    fun apply(from: Int?, to: Int?) {
        if (from == null && to == null) {
            onSetYears(emptySet())
            return
        }
        val lo = (from ?: 1950).coerceAtMost(to ?: (currentYear + 1))
        val hi = (to ?: (currentYear + 1)).coerceAtLeast(from ?: 1950)
        onSetYears((lo..hi).toSet())
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            YearBox(
                label = fromYear?.toString() ?: tr("From"),
                onClick = { fromOpen = true },
            )
            DropdownMenu(expanded = fromOpen, onDismissRequest = { fromOpen = false }) {
                DropdownMenuItem(
                    text = { Text(tr("Any")) },
                    onClick = { apply(null, toYear); fromOpen = false },
                )
                for (y in options) {
                    DropdownMenuItem(
                        text = { Text(y.toString()) },
                        onClick = { apply(y, toYear); fromOpen = false },
                    )
                }
            }
        }
        Text(
            tr("to"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 10.dp),
        )
        Box(Modifier.weight(1f)) {
            YearBox(
                label = toYear?.toString() ?: tr("To"),
                onClick = { toOpen = true },
            )
            DropdownMenu(expanded = toOpen, onDismissRequest = { toOpen = false }) {
                DropdownMenuItem(
                    text = { Text(tr("Any")) },
                    onClick = { apply(fromYear, null); toOpen = false },
                )
                for (y in options) {
                    DropdownMenuItem(
                        text = { Text(y.toString()) },
                        onClick = { apply(fromYear, y); toOpen = false },
                    )
                }
            }
        }
    }
}

@Composable
private fun YearBox(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** Studio/streamer logo grid. Logos resolve from TMDB at runtime; names
 *  without a logo (or while loading) show a monogram tile. */
@Composable
private fun CompanyGrid(
    selected: String?,
    onSelect: (String) -> Unit,
) {
    val resolved = remember { mutableStateMapOf<String, SearchDiscovery.ResolvedCompany?>() }
    LaunchedEffect(Unit) {
        for (name in SearchDiscovery.PRODUCTIONS) {
            if (!resolved.containsKey(name)) {
                resolved[name] = SearchDiscovery.resolveCompany(name)
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (row in SearchDiscovery.PRODUCTIONS.chunked(4)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                for (name in row) {
                    val hit = resolved[name]
                    val sel = selected == name
                    Column(
                        Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Surface(
                            onClick = { onSelect(name) },
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
                            border = if (sel) BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                            else BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(52.dp)
                                    .padding(6.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                val logo = hit?.logoUrl
                                if (logo != null) {
                                    AsyncImage(
                                        model = logo,
                                        contentDescription = name,
                                        modifier = Modifier
                                            .size(width = 64.dp, height = 36.dp)
                                            .clip(RoundedCornerShape(6.dp)),
                                        contentScale = ContentScale.Fit,
                                    )
                                } else {
                                    Text(
                                        name.split(" ").mapNotNull { it.firstOrNull()?.uppercase() }.take(2).joinToString(""),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 18.sp,
                                    )
                                }
                            }
                        }
                        Text(
                            name,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (sel) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontSize = 10.sp,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                }
                val missing = 4 - row.size
                repeat(missing) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
