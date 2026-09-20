package com.hikari.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hikari.app.i18n.tr
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
                        .clip(GlassShape)
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
 * The multi-select twin of [ChoiceDialog]: the same glass panel and the same
 * scrollable list, but tapping a row TOGGLES it instead of closing the panel, and
 * several rows can be on at once. Used by the two "pick one or more" settings —
 * the poster treatments and the loading-screen treatments — and by the Server
 * search exception list, where [searchable] adds a filter field over what can be
 * a hundred-plus installed extensions.
 *
 * [onToggle] fires on every tap, so the caller writes each change straight
 * through to the store: the list behind the panel (and the poster grid itself)
 * updates as the user ticks rows. The panel stays open until they close it, which
 * is the whole point of a multi-select — picking two things should not be two
 * trips through the menu.
 */
@Composable
fun MultiChoiceDialog(
    title: String,
    items: List<ChoiceItem>,
    selectedKeys: Set<String>,
    onToggle: (String) -> Unit,
    onDismiss: () -> Unit,
    /** Show a filter field above the list. Names are matched case-insensitively
     *  against the label and the supporting line, so an extension can be found by
     *  its repo name or by its engine. */
    searchable: Boolean = false,
    searchPlaceholder: String = "Search",
    /** Text under the title, for the two lines of "how this works". */
    footnote: String? = null,
    /** Drawn between the footnote and the search field — a caller's own
     *  controls over the list (the exception picker's engine chips). */
    headerContent: (@Composable () -> Unit)? = null,
    /** Rows that are ON but not the user's to switch off here: they are covered
     *  by something else in [headerContent] (a whole engine). They keep their
     *  tick and their colour, and a tap on one does nothing instead of silently
     *  doing nothing to the setting it appears to control. */
    disabledKeys: Set<String> = emptySet(),
    /** Text on a [disabledKeys] row, after its supporting line. */
    disabledNote: String? = null,
    maxHeight: Dp = 440.dp,
) {
    val options = items
    var query by remember { mutableStateOf("") }
    val shown = if (!searchable || query.isBlank()) {
        options
    } else {
        val q = query.trim().lowercase()
        options.filter {
            it.label.lowercase().contains(q) ||
                it.supporting?.lowercase()?.contains(q) == true
        }
    }
    GlassDialog(onDismiss = onDismiss, title = title) {
        if (!footnote.isNullOrBlank()) {
            Text(
                tr(footnote),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
        }
        headerContent?.let {
            it()
            Spacer(Modifier.height(8.dp))
        }
        if (searchable) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(tr(searchPlaceholder)) },
                leadingIcon = {
                    Icon(
                        Icons.Filled.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                trailingIcon = {
                    if (query.isNotBlank()) {
                        IconButton(onClick = { query = "" }, modifier = Modifier.size(32.dp)) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                singleLine = true,
                shape = GlassShape,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
        }
        if (shown.isEmpty()) {
            Text(
                tr("Nothing matches that."),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        }
        LazyColumn(
            Modifier.heightIn(max = maxHeight),
            // The panel is drawn over the page, so it can end up sitting behind
            // the floating taskbar on a tab screen (see [ChoiceDialog]).
            contentPadding = PaddingValues(bottom = LocalTaskbarInset.current),
        ) {
            items(shown.size, key = { shown[it].key }) { index ->
                val item = shown[index]
                val isOn = item.key in selectedKeys
                val locked = item.key in disabledKeys
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(GlassShape)
                        .background(
                            if (isOn) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                            else Color.Transparent
                        )
                        .clickable(enabled = !locked) { onToggle(item.key) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = isOn,
                        // null = the box is not its own tap target; the whole
                        // ROW toggles (otherwise a tap on the box itself could
                        // toggle twice).
                        onCheckedChange = null,
                        enabled = !locked,
                    )
                    if (!item.leading.isNullOrBlank()) {
                        Text(
                            item.leading,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.width(10.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            item.label,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (isOn) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                        )
                        if (!item.supporting.isNullOrBlank() || (locked && !disabledNote.isNullOrBlank())) {
                            Text(
                                listOfNotNull(
                                    item.supporting?.takeIf { it.isNotBlank() },
                                    if (locked) disabledNote else null,
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (isOn) {
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
        if (searchable) {
            Spacer(Modifier.height(6.dp))
            Text(
                tr("Tap a row to include or exclude it."),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The row that opens a [ChoiceDialog]: an optional icon or glyph, the choice in
 * use, the line under it, and a chevron saying it leads somewhere. Every "tap to
 * choose" setting in the app is one of these, so it reads as one control rather
 * than a menu in one card and a list of radios in the next.
 *
 * Its rounding is the app's one box radius (`GlassShape` in Components.kt), the
 * same corner the card around it is drawn with; this is the row the
 * app-language card leads with on Appearance & Theme.
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
    val shape = GlassShape
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
