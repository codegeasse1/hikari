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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hikari.app.tv.tvPress
import com.hikari.app.i18n.tr
import com.hikari.app.ui.theme.rememberGlassTokens

// Part of this file's own package: the app-wide "hide the explanations" switch
// (see LocalHideHelp), which every caption here answers to.
import com.hikari.app.ui.components.helpShown

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
 * A self-made colourful badge in the folder-badge language (navy ring,
 * saturated disc, white glyph): for every inner settings row that has no
 * supplied artwork. The disc colour is picked deterministically from [key]
 * so a row keeps its colour across restarts, themes and locales — the same
 * row is never two colours, and neighbours rarely share one.
 */
@Composable
fun SettingsColorBadge(icon: ImageVector, key: String, size: Dp = 46.dp) {
    val disc = badgeColorFor(key)
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(disc)
            .border(1.5.dp, androidx.compose.ui.graphics.Color(0xFF0F2540), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = androidx.compose.ui.graphics.Color.White,
            modifier = Modifier.size(size * 0.46f),
        )
    }
}

/**
 * The glyph tile every settings/extension row leads with: a rounded square in
 * the reference language (dark tile, centred glyph) drawn from the ACTIVE
 * theme — accent wash fill, accent hairline, accent glyph — so it follows the
 * user's selected accent colour instead of fixed artwork. No picture icons.
 * Brand-letter keys (TMDB/MDBList) keep their drawn letter tiles; everything
 * else is the theme tile. [key] is the folder key, section id or row title.
 */
@Composable
fun SettingsThemeTile(icon: ImageVector, key: String = "", size: Dp = 44.dp) {
    val spec = if (key.isNotBlank()) brandSpecFor(key) else null
    if (spec?.letters != null) {
        SettingsBrandBadge(key, icon, size)
        return
    }
    val accent = MaterialTheme.colorScheme.primary
    val tile = RoundedCornerShape(size * 0.28f)
    Box(
        Modifier
            .size(size)
            .clip(tile)
            .background(accent.copy(alpha = 0.16f))
            .border(1.dp, accent.copy(alpha = 0.38f), tile),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(size * 0.52f),
        )
    }
}

/** Stable vibrant disc for [SettingsColorBadge], by row key. */
fun badgeColorFor(key: String): androidx.compose.ui.graphics.Color {
    val palette = listOf(
        0xFFE5484D, 0xFFF97316, 0xFFF5A623, 0xFF22C55E, 0xFF14B8A6,
        0xFF0EA5E9, 0xFF6366F1, 0xFFA855F7, 0xFFEC4899, 0xFF84CC16,
        0xFF06B6D4, 0xFFEAB308, 0xFF8B5CF6, 0xFF10B981,
    )
    val idx = (key.hashCode() and Int.MAX_VALUE) % palette.size
    return androidx.compose.ui.graphics.Color(palette[idx].toInt())
}

/**
 * One brand tile for a settings row: a rounded square with its own gradient
 * and its own mark, in the language of the reference Sources screen's engine
 * tiles (TMDB's dark-cyan block, MDBList's blue M) — drawn originally for
 * this app, never copied artwork. Every option row gets one (folders,
 * sections, card headings), so no row is left on the old flat random-disc
 * badge. [key] is the folder key, section id or heading title; [icon] is the
 * fallback glyph for rows with no bespoke letters.
 */
@Composable
fun SettingsBrandBadge(key: String, icon: ImageVector, size: Dp = 46.dp) {
    val spec = brandSpecFor(key)
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.27f))
            .background(Brush.linearGradient(listOf(spec.top, spec.bottom)))
            .border(1.dp, Color.White.copy(alpha = 0.26f), RoundedCornerShape(size * 0.27f)),
        contentAlignment = Alignment.Center,
    ) {
        if (spec.letters != null) {
            Text(
                spec.letters,
                color = Color.White,
                fontWeight = FontWeight.Black,
                fontSize = (size.value * (if (spec.letters.length > 1) 0.21f else 0.44f)).sp,
                letterSpacing = (size.value * 0.01f).sp,
                maxLines = 1,
            )
        } else {
            Icon(
                icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(size * 0.5f),
            )
        }
    }
}

data class BrandSpec(
    val top: Color,
    val bottom: Color,
    val letters: String? = null,
)

/** Fixed tile per row: the same key always draws the same tile. */
fun brandSpecFor(key: String): BrandSpec {
    val k = key.lowercase()
    // Brand-inspired tiles (original lettering, brand colours).
    if ("tmdb" in k) return BrandSpec(Color(0xFF0D253F), Color(0xFF01B4E4), "TMDB")
    if ("mdb" in k) return BrandSpec(Color(0xFF1E40AF), Color(0xFF3B82F6), "M")
    if ("anime" in k) return BrandSpec(Color(0xFF6D28D9), Color(0xFFA855F7))
    // Families with a fixed colour each.
    if (k.startsWith("appearance")) return BrandSpec(Color(0xFFB45309), Color(0xFFF59E0B))
    if (k.startsWith("layout.poster") || k == "layout.poster") return BrandSpec(Color(0xFF0F766E), Color(0xFF14B8A6))
    if ("taskbar" in k || "nav" in k) return BrandSpec(Color(0xFF15803D), Color(0xFF22C55E))
    if ("episode" in k) return BrandSpec(Color(0xFFB91C1C), Color(0xFFEF4444))
    if (k.startsWith("player") || "external" in k || "volume" in k || "subtitle" in k || "loading" in k || "tv player" in k || "enhance" in k || "playback" in k || "server" in k) {
        return BrandSpec(Color(0xFFC2410C), Color(0xFFF97316))
    }
    if (k.startsWith("tv") || "remote" in k || "overscan" in k) return BrandSpec(Color(0xFF4338CA), Color(0xFF6366F1))
    if (k.startsWith("network") || "dns" in k || "slow" in k) return BrandSpec(Color(0xFF0369A1), Color(0xFF0EA5E9))
    if (k.startsWith("privacy") || "webview" in k || "user-agent" in k || "ad-block" in k) {
        return BrandSpec(Color(0xFF047857), Color(0xFF10B981))
    }
    if ("userscript" in k) return BrandSpec(Color(0xFF4D7C0F), Color(0xFF84CC16))
    if ("rating" in k || "score" in k) return BrandSpec(Color(0xFF7C2D12), Color(0xFFF59E0B))
    if ("banner" in k || "showcase" in k || "header" in k || "featured" in k) {
        return BrandSpec(Color(0xFF9A3412), Color(0xFFFB7185))
    }
    if ("poster" in k) return BrandSpec(Color(0xFF0E7490), Color(0xFF06B6D4))
    if ("ui-scale" in k || "font" in k || k == "appearance.font") return BrandSpec(Color(0xFF1D4ED8), Color(0xFF60A5FA))
    if ("icon" in k) return BrandSpec(Color(0xFF0F766E), Color(0xFF2DD4BF))
    if ("accent" in k || "colour" in k || "color" in k || "theme" in k) return BrandSpec(Color(0xFFA21CAF), Color(0xFFEC4899))
    if ("download" in k) return BrandSpec(Color(0xFF0F766E), Color(0xFF10B981))
    if ("tracker" in k || "sync" in k) return BrandSpec(Color(0xFF5B21B6), Color(0xFF8B5CF6))
    if ("catalog" in k || "collection" in k) return BrandSpec(Color(0xFF92400E), Color(0xFFF59E0B))
    if ("backup" in k || "restore" in k) return BrandSpec(Color(0xFF155E75), Color(0xFF0891B2))
    if ("log" in k || "diagnostic" in k) return BrandSpec(Color(0xFF334155), Color(0xFF64748B))
    if ("about" in k || "update" in k) return BrandSpec(Color(0xFF1E3A8A), Color(0xFF3B82F6))
    if ("clear" in k || "delete" in k) return BrandSpec(Color(0xFF7F1D1D), Color(0xFFDC2626))
    if ("content" in k || "filter" in k || "privacy" in k || "adult" in k) return BrandSpec(Color(0xFF831843), Color(0xFFEC4899))
    // Deterministic fallback: fixed gradient pairs, never random per launch.
    val pairs = listOf(
        BrandSpec(Color(0xFF9A3412), Color(0xFFF97316)),
        BrandSpec(Color(0xFF166534), Color(0xFF22C55E)),
        BrandSpec(Color(0xFF0C4A6E), Color(0xFF0EA5E9)),
        BrandSpec(Color(0xFF5B21B6), Color(0xFFA855F7)),
        BrandSpec(Color(0xFF9D174D), Color(0xFFEC4899)),
        BrandSpec(Color(0xFF713F12), Color(0xFFEAB308)),
        BrandSpec(Color(0xFF134E4A), Color(0xFF14B8A6)),
        BrandSpec(Color(0xFF7F1D1D), Color(0xFFE5484D)),
    )
    return pairs[(key.hashCode() and Int.MAX_VALUE) % pairs.size]
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
            .clickable(onClick = onBack)
            .tvPress(previewPass = true, onClick = onBack),
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
 * theme tile, then the page's name with its one-line summary under the name.
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
                SettingsThemeTile(icon, title, 40.dp)
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
                if (helpShown()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
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
