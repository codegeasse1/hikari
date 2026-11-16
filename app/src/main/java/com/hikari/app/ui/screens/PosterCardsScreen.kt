package com.hikari.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hikari.app.HikariApp
import com.hikari.app.i18n.tr
import com.hikari.app.tv.TvMode
import com.hikari.app.tv.TvUi
import com.hikari.app.tv.tvFocusRing
import com.hikari.app.ui.PosterSizes
import com.hikari.app.ui.PosterSizing
import com.hikari.app.ui.PosterTypes
import com.hikari.app.ui.components.GlassCard
import com.hikari.app.ui.components.SettingsPageHeader
import com.hikari.app.ui.navigation.LocalTaskbarInset
import kotlinx.coroutines.launch

/**
 * Settings -> Customize -> Poster cards.
 *
 * One page to choose how posters look everywhere in the app (phone and TV):
 *  - Shape: Portrait (tall 2:3 posters) or Landscape (wide 16:9 cards that use
 *    the title's backdrop when it has one). This is the app's existing poster
 *    type, so Settings -> Layout -> Poster & icon styling stays in step.
 *  - Size: Small / Default / Large, chosen separately for portrait and for
 *    landscape cards. It applies to the poster rows and the grids.
 *
 * A live preview stays pinned at the top and follows every tap.
 */
@Composable
fun PosterCardsPage(app: HikariApp, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val landscape = PosterSizing.landscape()
    val portraitKey = PosterSizing.portraitKey()
    val landscapeKey = PosterSizing.landscapeKey()
    // Page position held across visits (see ScrollKeeper).
    val listState = com.hikari.app.ui.components.keptListState("posterCards")

    BackHandler { onBack() }

    Column(Modifier.fillMaxWidth().fillMaxHeight()) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
            SettingsPageHeader(
                title = tr("Poster cards"),
                subtitle = tr("Choose how posters look and how big they are"),
                onBack = onBack,
            )
            GlassCard(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Column(Modifier.padding(14.dp)) {
                    PosterCardsPreview(landscape = landscape)
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 8.dp,
                bottom = LocalTaskbarInset.current + 16.dp,
            ),
        ) {
            item(key = "shape") {
                Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    PosterCardsSectionLabel(tr("Shape"))
                    GlassCard(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            PosterCardsSegmented(
                                options = listOf(
                                    PosterTypes.VERTICAL to tr("Portrait"),
                                    PosterTypes.CINEMA to tr("Landscape"),
                                ),
                                selected = if (landscape) PosterTypes.CINEMA else PosterTypes.VERTICAL,
                                onPick = { pick ->
                                    scope.launch { runCatching { app.store.setPosterType(pick) } }
                                },
                            )
                        }
                    }
                    Text(
                        tr("Landscape uses the wide backdrop when the title has one. Otherwise the whole poster is shown on a blurred background."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp),
                    )
                }
            }

            item(key = "size") {
                Column(Modifier.fillMaxWidth().padding(top = 20.dp)) {
                    PosterCardsSectionLabel(tr("Size"))
                    GlassCard(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                tr("Portrait card size"),
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(start = 2.dp, bottom = 8.dp),
                            )
                            PosterCardsSegmented(
                                options = PosterSizes.ALL.map { it to tr(PosterSizes.label(it)) },
                                selected = portraitKey,
                                onPick = { pick ->
                                    scope.launch { runCatching { app.store.setPosterPortraitSize(pick) } }
                                },
                            )
                            Spacer(Modifier.height(16.dp))
                            Text(
                                tr("Landscape card size"),
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(start = 2.dp, bottom = 8.dp),
                            )
                            PosterCardsSegmented(
                                options = PosterSizes.ALL.map { it to tr(PosterSizes.label(it)) },
                                selected = landscapeKey,
                                onPick = { pick ->
                                    scope.launch { runCatching { app.store.setPosterLandscapeSize(pick) } }
                                },
                            )
                        }
                    }
                    Text(
                        tr("Card size applies to poster rows and grids throughout the app, on phone and on TV."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun PosterCardsSectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(start = 4.dp),
    )
}

/** A pill-shaped choice bar: one tap (or one OK press on a remote) picks. */
@Composable
private fun PosterCardsSegmented(
    options: List<Pair<String, String>>,
    selected: String,
    onPick: (String) -> Unit,
) {
    val outline = MaterialTheme.colorScheme.outline
    val barShape = RoundedCornerShape(50)
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(barShape)
            .border(1.dp, outline, barShape),
    ) {
        for ((key, label) in options) {
            val on = key == selected
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .tvFocusRing(24.dp)
                    .background(
                        if (on) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                    )
                    .clickable { onPick(key) },
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (on) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                        color = if (on) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * Three stand-in cards at the size the app would draw them right now (the same
 * widths the rows use), so the effect of every tap is visible at once without
 * needing any picture to load.
 */
@Composable
private fun PosterCardsPreview(landscape: Boolean) {
    val tv = TvMode.current()
    val screenWidth = LocalConfiguration.current.screenWidthDp
    val landscapeFactor = PosterSizing.landscapeFactor()
    val portraitWidth = TvUi.posterWidth()
    val landscapeWidth = if (tv) {
        ((TvUi.posterWidthBase().value * 1.8f).coerceIn(96f, 230f) * landscapeFactor * 16f / 9f).dp
    } else {
        minOf((280f * landscapeFactor).dp, (screenWidth - 64).coerceAtLeast(120).dp)
    }
    val cardWidth = if (landscape) landscapeWidth else portraitWidth
    val ratio = if (landscape) 16f / 9f else 2f / 3f
    val tints = listOf(Color(0xFF3949AB), Color(0xFF00897B), Color(0xFFD81B60))

    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        tints.forEachIndexed { index, tint ->
            Column(Modifier.width(cardWidth)) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(ratio)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            Brush.verticalGradient(listOf(tint, tint.copy(alpha = 0.40f)))
                        ),
                )
                Text(
                    tr("Sample title") + " " + (index + 1).toString(),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp),
                )
            }
        }
    }
}
