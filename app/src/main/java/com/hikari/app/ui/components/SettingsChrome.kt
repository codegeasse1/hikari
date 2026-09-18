package com.hikari.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hikari.app.i18n.tr
import com.hikari.app.ui.theme.rememberGlassTokens

/**
 * The header of EVERY page inside Settings — a folder page, the Logs page, the
 * player-control editor — draws its name at this one size, so no settings page
 * looks louder or quieter than the next one. 16.sp is also what the index's
 * folder rows use, so a folder's page never shouts louder than the row that
 * opened it.
 *
 * The size is not a taste call: the header is one line (icon, then name), and
 * 16.sp is the largest that keeps the longest name in Settings — "Personal
 * Catalog creator" — on that line on a phone. The numbers, so nobody has to
 * re-derive them: the back button and badge take 92.dp, leaving ~236.dp of a
 * 360.dp phone's row, and the longest name measures 178.dp at 16.sp in Roboto
 * Bold — room to spare even with the phone's own font size set a notch or two up.
 * Anything larger ellipsises, and a truncated name reads as a bug.
 */
val SETTINGS_TITLE_SIZE: TextUnit = 16.sp

/**
 * The corner radius of every box drawn INSIDE a settings page: the action
 * buttons, the preset chips, the row a picker leads with ([ChoiceRow]). The
 * outer setting box stays what it is ([GlassCard], 26.dp) — a Material button
 * defaults to a 50% capsule, though, and beside a 26.dp card that read as two
 * different designs on one page.
 *
 * 14.dp is the same proportion of a 40.dp button as 26.dp is of a 76.dp card
 * (~35%), so everything inside a settings page is now rounded by one rule
 * rather than by whichever default each component happened to inherit.
 */
val SettingsBoxShape = RoundedCornerShape(14.dp)

/**
 * The round accent badge a settings row leads with: a circle of accent wash with
 * a hairline ring, so the icon reads as a glass token rather than a flat square.
 * One composable for every one of them, so the index, the folder headers and the
 * shortcut cards can never drift apart.
 */
@Composable
fun SettingsIconBadge(icon: ImageVector, size: Dp = 46.dp) {
    val accent = MaterialTheme.colorScheme.primary
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(accent.copy(alpha = 0.14f))
            .border(1.dp, accent.copy(alpha = 0.22f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(size * 0.46f),
        )
    }
}

/**
 * The way out of a settings page: one circular glass button, the same size and
 * fill everywhere, so backing out of a folder, the Logs page and the
 * player-control editor are all the same gesture in the same place.
 */
@Composable
private fun SettingsBackButton(onBack: () -> Unit) {
    val glass = rememberGlassTokens()
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(glass.fillTop)
            .border(1.dp, glass.border, CircleShape)
            .clickable(onClick = onBack),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = tr("Back to settings"),
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * The header of a page inside Settings — ONE line: the back button, an optional
 * icon badge, then the page's name with its one-line summary under the name.
 * A folder page leads with the folder's own icon, so it starts with the same
 * "icon, then what it is called" shape as the row that opened it; the pages that
 * are not folders (Logs, the player-control editor) pass no icon and simply hand
 * the name the extra width.
 *
 * The name is one line by construction, not by hope: one fixed size
 * ([SETTINGS_TITLE_SIZE]) for every page, ellipsised rather than wrapped, and
 * weighted so it takes whatever the back button and badge leave. The size was
 * chosen to fit the longest name Settings has, so in practice nothing ellipsises.
 *
 * [breadcrumb] is set only for a sub-folder ("Appearance ›") and is printed on
 * the line ABOVE that row — inside it, the breadcrumb would eat the width the
 * name needs. [trailing] is for the rare page with an action up there (the
 * player-control editor's Reset).
 *
 * The strings arrive already translated (see `tr`), like [ChoiceRow]'s.
 */
@Composable
fun SettingsPageHeader(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    icon: ImageVector? = null,
    breadcrumb: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp)
    ) {
        if (breadcrumb != null) {
            Text(
                breadcrumb,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            SettingsBackButton(onBack)
            if (icon != null) {
                Spacer(Modifier.width(8.dp))
                SettingsIconBadge(icon, 40.dp)
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontSize = SETTINGS_TITLE_SIZE,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(1.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (trailing != null) {
                Spacer(Modifier.width(8.dp))
                trailing()
            }
        }
        Spacer(Modifier.height(10.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
    }
}
