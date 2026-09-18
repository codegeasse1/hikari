package com.hikari.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hikari.app.ui.navigation.LocalTaskbarInset
import com.hikari.app.ui.theme.rememberGlassTokens

/**
 * One option in a [ChoiceDialog].
 *
 * [leading] is a short bit of text drawn before the name — a flag for languages,
 * "Aa" for a font — and [supporting] is the line under it (a note, a code, an
 * address). Both are optional.
 */
data class ChoiceItem(
    val key: String,
    val label: String,
    val supporting: String? = null,
    val leading: String? = null,
)

/**
 * The app's one "pick one of many" surface: a glass panel with a scrollable list
 * of options, the one in use tinted and ticked. Tapping an option picks it and
 * closes the panel; the X or anywhere outside closes it unchanged.
 *
 * Settings that offer a lot of choices (the app font, the TMDB title language,
 * the DNS resolver) open this instead of printing every option into their own
 * card: a card stays the height of one row whatever is chosen, a choice of
 * thirty is a scroll rather than a page-long wall of radio buttons, and every
 * long list in Settings looks and behaves the same way.
 */
@Composable
fun ChoiceDialog(
    title: String,
    items: List<ChoiceItem>,
    selectedKey: String?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
    maxHeight: Dp = 440.dp,
) {
    // Aliased so `items` inside the LazyColumn below is unambiguously
    // LazyListScope.items and not this parameter.
    val options = items
    GlassDialog(onDismiss = onDismiss, title = title) {
        LazyColumn(
            Modifier.heightIn(max = maxHeight),
            // The panel is drawn over the page, so it can end up sitting behind
            // the floating taskbar on a tab screen. Without this the last few
            // options could neither be tapped nor scrolled clear of the bar.
            contentPadding = PaddingValues(bottom = LocalTaskbarInset.current),
        ) {
            items(options.size) { index ->
                val item = options[index]
                val isOn = item.key == selectedKey
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            if (isOn) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                            else Color.Transparent
                        )
                        .clickable {
                            onDismiss()
                            onPick(item.key)
                        }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!item.leading.isNullOrBlank()) {
                        Text(
                            item.leading,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.width(12.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            item.label,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (isOn) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                        )
                        if (!item.supporting.isNullOrBlank()) {
                            Text(
                                item.supporting,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (isOn) {
                        Spacer(Modifier.width(10.dp))
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The row that opens a [ChoiceDialog]: an optional icon or glyph, the choice in
 * use, the line under it, and a chevron saying it leads somewhere. Every "tap to
 * choose" setting in the app is one of these, so it reads as one control rather
 * than a menu in one card and a list of radios in the next.
 *
 * Its rounding matches the settings pages' other inner boxes (14.dp — see
 * `SettingsBoxShape` in SettingsScreen.kt); this is the row the app-language
 * card leads with on Appearance & Theme.
 */
@Composable
fun ChoiceRow(
    value: String,
    onClick: () -> Unit,
    supporting: String? = null,
    leadingIcon: ImageVector? = null,
    leadingText: String? = null,
    modifier: Modifier = Modifier,
) {
    val glass = rememberGlassTokens()
    val shape = RoundedCornerShape(14.dp)
    val hasLeading = leadingIcon != null || !leadingText.isNullOrBlank()
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.verticalGradient(listOf(glass.fillTop, glass.fillBottom)))
            .border(1.dp, glass.border, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            Icon(
                leadingIcon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        } else if (!leadingText.isNullOrBlank()) {
            Text(leadingText, style = MaterialTheme.typography.titleMedium)
        }
        if (hasLeading) Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                value,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (!supporting.isNullOrBlank()) {
                Text(
                    supporting,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
