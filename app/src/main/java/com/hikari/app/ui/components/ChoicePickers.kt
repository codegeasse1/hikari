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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hikari.app.i18n.tr
import com.hikari.app.tv.TvMode
import com.hikari.app.ui.navigation.LocalTaskbarInset
import com.hikari.app.ui.theme.rememberGlassTokens
import com.hikari.app.tv.tvTextFieldKeys

data class ChoiceItem(
    val key: String,
    val label: String,
    val supporting: String? = null,
    val leading: String? = null,
)

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
    // The remote's landing spot on a television: the FIRST row, so the very
    // first arrow press walks the list (see [GlassDialog.initialFocus]).
    val firstRow = remember { FocusRequester() }
    // Television rows read white/muted (the desktop dialog language); the
    // phone keeps its own row colors.
    val tvChoice = TvMode.current()
    GlassDialog(onDismiss = onDismiss, title = title, initialFocus = firstRow) {
        LazyColumn(
            Modifier.heightIn(max = maxHeight),
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
                        .then(if (index == 0) Modifier.focusRequester(firstRow) else Modifier)
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
                            else if (tvChoice) com.hikari.app.tv.TvDesktop.TextPrimary
                            else MaterialTheme.colorScheme.onSurface,
                        )
                        if (!item.supporting.isNullOrBlank()) {
                            Text(
                                item.supporting,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (tvChoice) com.hikari.app.tv.TvDesktop.TextMuted
                                else MaterialTheme.colorScheme.onSurfaceVariant,
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
    val firstRow = remember { FocusRequester() }
    // Television rows read white/muted (see [ChoiceDialog]).
    val tvChoice = TvMode.current()
    val shown = if (!searchable || query.isBlank()) {
        options
    } else {
        val q = query.trim().lowercase()
        options.filter {
            it.label.lowercase().contains(q) ||
                it.supporting?.lowercase()?.contains(q) == true
        }
    }
    GlassDialog(onDismiss = onDismiss, title = title, initialFocus = firstRow) {
        if (!footnote.isNullOrBlank()) {
            Text(
                tr(footnote),
                style = MaterialTheme.typography.labelSmall,
                color = if (tvChoice) com.hikari.app.tv.TvDesktop.TextMuted
                else MaterialTheme.colorScheme.onSurfaceVariant,
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
                        tint = if (tvChoice) com.hikari.app.tv.TvDesktop.TextMuted
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                trailingIcon = {
                    if (query.isNotBlank()) {
                        IconButton(onClick = { query = "" }, modifier = Modifier.size(32.dp)) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = null,
                                tint = if (tvChoice) com.hikari.app.tv.TvDesktop.TextMuted
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                singleLine = true,
                shape = GlassShape,
                modifier = Modifier.fillMaxWidth().tvTextFieldKeys(query),
            )
            Spacer(Modifier.height(8.dp))
        }
        if (shown.isEmpty()) {
            Text(
                tr("Nothing matches that."),
                style = MaterialTheme.typography.bodyMedium,
                color = if (tvChoice) com.hikari.app.tv.TvDesktop.TextMuted
                else MaterialTheme.colorScheme.onSurfaceVariant,
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
                        .then(if (index == 0) Modifier.focusRequester(firstRow) else Modifier)
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
                            else if (tvChoice) com.hikari.app.tv.TvDesktop.TextPrimary
                            else MaterialTheme.colorScheme.onSurface,
                        )
                        if (!item.supporting.isNullOrBlank() || (locked && !disabledNote.isNullOrBlank())) {
                            Text(
                                listOfNotNull(
                                    item.supporting?.takeIf { it.isNotBlank() },
                                    if (locked) disabledNote else null,
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (tvChoice) com.hikari.app.tv.TvDesktop.TextMuted
                                else MaterialTheme.colorScheme.onSurfaceVariant,
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
                color = if (tvChoice) com.hikari.app.tv.TvDesktop.TextMuted
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

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
    // Television rows read white/muted (see [ChoiceDialog]).
    val tvChoice = TvMode.current()
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
            // Theme tile (accent wash + hairline + accent glyph): the same tile
            // the folders and extension rows wear — one icon language.
            SettingsThemeTile(
                icon = leadingIcon,
                key = value,
                size = 34.dp,
            )
        } else if (!leadingText.isNullOrBlank()) {
            Text(leadingText, style = MaterialTheme.typography.titleMedium)
        }
        if (hasLeading) Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                value,
                style = MaterialTheme.typography.bodyLarge,
                color = if (tvChoice) com.hikari.app.tv.TvDesktop.TextPrimary
                else MaterialTheme.colorScheme.onSurface,
            )
            if (!supporting.isNullOrBlank()) {
                Text(
                    supporting,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (tvChoice) com.hikari.app.tv.TvDesktop.TextMuted
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = if (tvChoice) com.hikari.app.tv.TvDesktop.TextMuted
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
