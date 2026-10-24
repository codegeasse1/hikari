package com.hikari.app.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import com.hikari.app.data.RatingSource
import com.hikari.app.data.TitleRating
import com.hikari.app.ui.components.PosterImage

/**
 * The CloudStream desktop client's visual language, rebuilt for the
 * television layout. Every value mirrors that client's exact tokens: the
 * Navy default theme (background 0x0C0C16, card 0x161624, elevated 0x20202E,
 * divider 0x2A2A38), white primary text, hairline edges, and the reference's
 * own purple accent (0xFF6C5CE7) — hardcoded, so the TV matches the
 * reference one-to-one instead of following the phone's accent setting.
 */
object Cs3 {

    /** Page background: the reference default Navy. */
    val Background: Color = Color(0xFF0C0C16)

    /** Card surface: one step up from the page, like the reference. */
    val SurfaceCard: Color = Color(0xFF161624)

    /** Elevated surface (dialogs, thumbs, sheets). */
    val SurfaceElevated: Color = Color(0xFF20202E)

    val TextPrimary: Color = Color.White
    val TextMuted: Color = Color.White.copy(alpha = 0.7f)

    /** Hairline divider/border on the dark surfaces. */
    val Divider: Color = Color(0xFF2A2A38)

    /** Glass hairline for focused cards and floating bars. */
    val Edge: Color = Color.White.copy(alpha = 0.14f)

    /** The corner language: 8dp cards/posters, 12dp buttons, 20dp panels. */
    val CornerCard = 8.dp
    val CornerButton = 12.dp
    val CornerPanel = 20.dp
    val CornerPill = 24.dp

    /** 2:3 posters and 16:9 stills, same as the reference. */
    const val PosterAspect: Float = 2f / 3f
    const val StillAspect: Float = 16f / 9f

    /** The reference's own purple accent, hardcoded for one-to-one TV. */
    val Purple: Color = Color(0xFF6C5CE7)

    /** The accent on TV (the reference purple). */
    val accent: Color
        @Composable get() = Purple
}

/**
 * Focus treatment for CS3 cards: a gentle scale plus an accent ring, the
 * remote's version of the desktop client's hover glow. The ring is composited
 * by alpha so an unfocused card costs nothing extra to draw.
 */
@Composable
fun Modifier.cs3Focus(corner: androidx.compose.ui.unit.Dp = Cs3.CornerCard): Modifier {
    var focused by remember { mutableStateOf(false) }
    val accent = Cs3.accent
    return this
        .onFocusChanged { focused = it.isFocused }
        .border(
            width = 1.dp,
            color = if (focused) accent else Color.Transparent,
            shape = RoundedCornerShape(corner),
        )
}

/**
 * Aspect-safe backdrop art, the reference DetailsBackdrop contract:
 *
 * - Genuinely wide art (a TMDB 16:9 backdrop) paints SHARP, top-anchored, with
 *   the reference's triple gradient (bottom scrim into the page, left
 *   vignette, vertical melt).
 * - Anything else (a portrait poster, a thumb) NEVER sharp-crops: it fills
 *   blurred behind the same gradients, exactly like the reference's
 *   blurred-fallback path.
 *
 * This is what ends the stretched-poster backdrops: a poster-only title gets
 * atmosphere, never a beheaded face.
 */
@Composable
fun Cs3BackdropArt(
    model: Any?,
    wide: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.background(Cs3.Background)) {
        if (model != null) {
            if (wide) {
                PosterImage(
                    model = model,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                // Blurred fill: atmosphere without the crop.
                PosterImage(
                    model = model,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter,
                    modifier = Modifier
                        .fillMaxSize()
                        .blur(22.dp, BlurredEdgeTreatment.Unbounded),
                )
                // Readability dim over the blur.
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.35f))
                )
            }
        }
        // Bottom scrim: melts the art into the page.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.00f to Color.Transparent,
                        0.45f to Color.Transparent,
                        0.70f to Cs3.Background.copy(alpha = 0.55f),
                        0.88f to Cs3.Background.copy(alpha = 0.90f),
                        1.00f to Cs3.Background,
                    )
                )
        )
        // Left vignette: the text column's backdrop.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0.00f to Cs3.Background.copy(alpha = 0.82f),
                        0.30f to Cs3.Background.copy(alpha = 0.55f),
                        0.55f to Cs3.Background.copy(alpha = 0.18f),
                        0.75f to Color.Transparent,
                        1.00f to Color.Transparent,
                    )
                )
        )
    }
}

/** Site colour for a rating badge, the reference's branded-rating colours. */
private fun ratingSiteColor(source: RatingSource): Color = when (source) {
    RatingSource.IMDB -> Color(0xFFF5C518)
    RatingSource.TMDB -> Color(0xFF01B4E4)
    RatingSource.ANILIST -> Color(0xFF02A9FF)
    RatingSource.TOMATOMETER -> Color(0xFFFA320A)
    RatingSource.POPCORN -> Color(0xFFFFB300)
    RatingSource.METACRITIC -> Color(0xFF66CC33)
    RatingSource.LETTERBOXD -> Color(0xFF00C030)
}

private fun ratingSiteLabel(source: RatingSource): String = when (source) {
    RatingSource.IMDB -> "IMDb"
    RatingSource.TMDB -> "TMDB"
    RatingSource.ANILIST -> "AL"
    RatingSource.TOMATOMETER -> "RT"
    RatingSource.POPCORN -> "RT"
    RatingSource.METACRITIC -> "MC"
    RatingSource.LETTERBOXD -> "LB"
}

/**
 * The reference's ratings row: small dark pills, site name in the site
 * colour, value in white — IMDb 7.4, TMDB 7.2, MAL 74% — followed by the
 * content-rating chip. Tapping a badge opens the same source dialog as the
 * phone badges.
 */
@Composable
fun Cs3RatingBadges(
    ratings: List<TitleRating>,
    cert: String?,
    onRatingClick: (TitleRating) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (ratings.isEmpty() && cert.isNullOrBlank()) return
    val ts = TvUi.scale()
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ratings.forEach { r ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .border(0.5.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(6.dp))
                    .clickable { onRatingClick(r) }
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            ) {
                Text(
                    text = ratingSiteLabel(r.source),
                    fontSize = 11.sp * ts,
                    fontWeight = FontWeight.ExtraBold,
                    color = ratingSiteColor(r.source),
                )
                Text(
                    text = r.value,
                    fontSize = 12.sp * ts,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
            }
        }
        if (!cert.isNullOrBlank()) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .border(0.5.dp, Color.White.copy(alpha = 0.30f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            ) {
                Text(
                    text = cert,
                    fontSize = 11.sp * ts,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
            }
        }
    }
}

/**
 * The reference's primary action: a solid white pill with a dark label —
 * "Watch Now", "Play S2 E1", "Resume S2 E2 • 23m left". Reads across the room.
 */
@Composable
fun Cs3PlayButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Filled.PlayArrow,
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.White,
            contentColor = Color.Black,
        ),
        shape = RoundedCornerShape(Cs3.CornerButton),
        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp),
        modifier = modifier.height(48.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, maxLines = 1)
    }
}

/**
 * The reference's secondary actions: dark translucent squares with a
 * hairline — library, download, trailer. 52dp, 12dp corners.
 */
@Composable
fun Cs3SquareButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Color.White,
    highlighted: Boolean = false,
) {
    val accent = Cs3.accent
    Surface(
        onClick = onClick,
        modifier = modifier.size(48.dp),
        shape = RoundedCornerShape(Cs3.CornerButton),
        color = if (highlighted) accent.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.12f),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (highlighted) accent.copy(alpha = 0.65f) else Color.White.copy(alpha = 0.28f),
        ),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (highlighted) accent else tint,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/**
 * A section header in the reference language: bold white title on the left
 * ("Season 2 • 3 Episodes", "Cast & Crew", "Screenshots", "Show Details"),
 * actions pinned right.
 */
@Composable
fun Cs3SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    actions: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = Cs3.TextPrimary,
            modifier = Modifier.weight(1f),
        )
        actions?.invoke()
    }
}

/** Small header action: muted text button, like the reference's right-side links. */
@Composable
fun Cs3HeaderLink(
    label: String,
    onClick: () -> Unit,
    highlighted: Boolean = false,
) {
    val accent = Cs3.accent
    Text(
        text = label,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = if (highlighted) accent else Cs3.TextMuted,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    )
}

/**
 * The reference's hero dots: a dark pill with dashes, the active one long
 * and white. D-pad safe: each dash is clickable.
 */
@Composable
fun Cs3HeroDots(
    count: Int,
    active: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (count < 2) return
    Row(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(Cs3.CornerButton))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { i ->
            val selected = i == active
            Box(
                Modifier
                    .width(if (selected) 28.dp else 10.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        if (selected) Color.White
                        else Color.White.copy(alpha = 0.30f)
                    )
                    .clickable { onSelect(i) }
            )
        }
    }
}

/**
 * The reference's episode card: a 16:9 still with the rating badge top-left
 * and the watched check top-right, then the episode code ("S2 • EPISODE 1"),
 * the bold title, a two-line synopsis and duration — over a dark card with a
 * hairline edge. Anti-spoiler blurs the still and masks the text.
 *
 * [art] is the episode's TMDB still first, then its own thumbnail (provider),
 * both landscape; [fallbackBlur] is the show art that fills sharp when there
 * is no still at all (desktop parity) — a card never wears a blank box.
 */
/**
 * The reference's episode card, one-to-one: a single 16:9 art box with
 * 16dp corners and a hairline edge, art full-bleed, the site rating badge
 * top-start, the watched check top-end, and the code / title / two-line
 * synopsis / duration-plus-date block overlaid on the art's bottom safe
 * zone, with the continue progress floating above the bottom edge.
 */
@Composable
fun Cs3EpisodeCard(
    art: Any?,
    fallbackBlur: Any?,
    code: String,
    title: String,
    desc: String,
    duration: String?,
    dateText: String?,
    siteLabel: String?,
    rating10: Double?,
    progress: Float,
    watched: Boolean,
    hideSpoilers: Boolean,
    onPlay: () -> Unit,
    onDownload: (() -> Unit)?,
    modifier: Modifier = Modifier,
    /** Detail side-panel cards are narrow (0.37 of the hero): their text
     *  rides at 0.85 so the 19.5sp title doesn't crowd the still. */
    textScale: Float = 1f,
) {
    val accent = Cs3.accent
    // Adaptive TV type: the card's text rides the viewport scale (see
    // TvUi.scale) so a dense stick screen doesn't set 19sp titles into a
    // card half the height, and a 4K panel doesn't whisper.
    val ts = TvUi.scale() * textScale.coerceIn(0.5f, 1.5f)
    Box(
        modifier = modifier
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF141518))
            .border(0.5.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(16.dp))
            .clickable(onClick = onPlay)
            .cs3Focus(16.dp),
    ) {
        // Card background image, full-bleed like the reference.
        if (art != null) {
            PosterImage(
                model = art,
                contentDescription = title,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (hideSpoilers) Modifier.blur(14.dp) else Modifier),
            )
        } else if (fallbackBlur != null) {
            // The show's own art as a last resort, painted SHARP like the
            // desktop client — a blurred copy of the detail banner behind
            // every card is what read as the same image repeating itself.
            PosterImage(
                model = fallbackBlur,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        // Anti-spoiler veil + pill.
        if (hideSpoilers) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.Black.copy(alpha = 0.6f))
                        .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(16.dp))
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Text(
                        "Hidden by Anti-spoiler",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
        // Rating badge, top-start (site color + value, like the reference).
        if (rating10 != null && rating10 > 0.0 && !hideSpoilers) {
            val siteColor = when (siteLabel) {
                "IMDb" -> Color(0xFFF5C518)
                "TMDB" -> Color(0xFF01B4E4)
                "MAL" -> Color(0xFF02A9FF)
                else -> Color.White
            }
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.75f))
                    .border(0.5.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.5.dp, vertical = 3.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.5.dp),
                ) {
                    if (!siteLabel.isNullOrBlank()) {
                        Text(
                            text = siteLabel,
                            fontSize = 11.5.sp * ts,
                            fontWeight = FontWeight.Bold,
                            color = siteColor,
                        )
                    }
                    Text(
                        text = String.format(java.util.Locale.US, "%.1f", rating10),
                        fontSize = 11.5.sp * ts,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                }
            }
        }
        // Watched completion check, top-end.
        if (watched) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(accent)
                    .border(1.dp, Color.White.copy(alpha = 0.30f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        // Bottom overlay: code, title, synopsis, duration + date.
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(
                    start = 18.dp,
                    end = 18.dp,
                    bottom = if (progress > 0f) 20.dp else 14.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = code,
                fontSize = 12.5.sp * ts,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp * ts,
                color = Color(0xFFE2E8F0).copy(alpha = 0.80f),
                maxLines = 1,
            )
            Text(
                text = if (hideSpoilers) "Episode title hidden" else title,
                fontSize = 19.5.sp * ts,
                lineHeight = 24.sp * ts,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = Color.White,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (hideSpoilers) Modifier.blur(2.dp) else Modifier),
            )
            Text(
                text = when {
                    hideSpoilers -> "Description hidden."
                    desc.isNotBlank() -> desc
                    else -> "No description available."
                },
                fontSize = 14.5.sp * ts,
                lineHeight = 20.sp * ts,
                color = Color(0xFFD1D5DB).copy(alpha = if (desc.isNotBlank() && !hideSpoilers) 0.88f else 0.45f),
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.then(if (hideSpoilers && desc.isNotBlank()) Modifier.blur(5.dp) else Modifier),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (duration != null) {
                    Text(
                        text = duration,
                        fontSize = 12.5.sp * ts,
                        fontWeight = FontWeight.Medium,
                        color = Color.White.copy(alpha = 0.65f),
                    )
                } else {
                    Spacer(modifier = Modifier.width(1.dp))
                }
                if (dateText != null) {
                    Text(
                        text = dateText,
                        fontSize = 12.5.sp * ts,
                        fontWeight = FontWeight.Medium,
                        color = Color.White.copy(alpha = 0.55f),
                    )
                }
            }
        }
        // Continue-watching progress, floating above the bottom edge.
        if (progress > 0f) {
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 14.dp, bottom = 6.dp)
                    .height(3.5.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.22f)),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(2.5.dp))
                        .background(accent),
                )
            }
        }
    }
}

/**
 * The reference's poster card: a 2:3 poster with 8dp corners, the rating
 * badge top-left, the title below in white. Posters stay posters — never
 * stretched into landscape frames.
 */
@Composable
fun Cs3PosterCard(
    model: Any?,
    title: String,
    rating10: Double?,
    progress: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = Cs3.accent
    Column(
        modifier
            .clickable(onClick = onClick)
            .cs3Focus(Cs3.CornerCard),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(Cs3.PosterAspect)
                .clip(RoundedCornerShape(Cs3.CornerCard))
                .background(Cs3.SurfaceElevated)
                .border(0.5.dp, Cs3.Edge, RoundedCornerShape(Cs3.CornerCard)),
        ) {
            if (model != null) {
                PosterImage(
                    model = model,
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (rating10 != null && rating10 > 0.0) {
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.72f))
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = "★ " + String.format(java.util.Locale.US, "%.1f", rating10),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFFFD700),
                    )
                }
            }
            if (progress > 0f) {
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(Color.White.copy(alpha = 0.22f)),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(progress.coerceIn(0f, 1f))
                            .fillMaxHeight()
                            .background(accent),
                    )
                }
            }
        }
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = Cs3.TextPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 16.sp,
            modifier = Modifier.padding(start = 2.dp, top = 6.dp, end = 2.dp),
        )
    }
}

/**
 * A cast/character tile: profile art with 8dp corners, name in white and the
 * character (or voice) in muted below — the reference's Cast & Crew row.
 */
@Composable
fun Cs3PersonCard(
    model: Any?,
    name: String,
    sub: String?,
    modifier: Modifier = Modifier,
) {
    Column(modifier.width(104.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.78f)
                .clip(RoundedCornerShape(Cs3.CornerCard))
                .background(Cs3.SurfaceElevated)
                .border(0.5.dp, Cs3.Edge, RoundedCornerShape(Cs3.CornerCard)),
        ) {
            if (model != null) {
                PosterImage(
                    model = model,
                    contentDescription = name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Text(
            text = name,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = Cs3.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 2.dp, top = 6.dp, end = 2.dp),
        )
        if (!sub.isNullOrBlank()) {
            Text(
                text = sub,
                style = MaterialTheme.typography.labelSmall,
                color = Cs3.TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 2.dp, end = 2.dp),
            )
        }
    }
}

/**
 * The reference's dialog panel: an elevated dark box with 20dp corners and a
 * hairline edge — Select Provider, Manage Feed, confirmations. Content goes
 * inside; the panel only owns the container.
 */
@Composable
fun Cs3DialogPanel(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(Cs3.CornerPanel),
        color = Cs3.SurfaceElevated,
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
        shadowElevation = 24.dp,
    ) {
        content()
    }
}

/**
 * The reference's shelf header, one-to-one: bold title, the "View All" pill
 * in the reference purple, and the two scroll chevrons. [scroll] drives the
 * chevrons (4 items per tap); [leadingExtra] slots content like
 * "Clear History" before the pill.
 */
@Composable
fun Cs3ShelfHeader(
    title: String,
    itemCount: Int,
    scroll: LazyListState? = null,
    onViewAll: (() -> Unit)? = null,
    leadingExtra: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = Cs3.TextPrimary,
            modifier = Modifier.weight(1f, fill = false),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.width(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (leadingExtra != null) {
                leadingExtra()
                Spacer(modifier = Modifier.width(8.dp))
            }
            if (onViewAll != null) {
                Surface(
                    onClick = onViewAll,
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                ) {
                    Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(
                            "View All",
                            color = Cs3.accent,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
            if (scroll != null) {
                Spacer(modifier = Modifier.width(8.dp))
                Cs3Chevron(
                    enabled = scroll.canScrollBackward,
                    onClick = {
                        scope.launch {
                            val target = (scroll.firstVisibleItemIndex - 4).coerceAtLeast(0)
                            scroll.animateScrollToItem(target)
                        }
                    },
                    icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Cs3Chevron(
                    enabled = scroll.canScrollForward,
                    onClick = {
                        scope.launch {
                            val last = (scroll.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) + 4
                            scroll.animateScrollToItem(last.coerceAtMost((itemCount - 1).coerceAtLeast(0)))
                        }
                    },
                    icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                )
            }
        }
    }
}

@Composable
private fun Cs3Chevron(
    enabled: Boolean,
    onClick: () -> Unit,
    icon: ImageVector,
) {
    val alpha = if (enabled) 0.6f else 0.25f
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .padding(horizontal = 2.dp)
            .size(38.dp),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (enabled) Color.White.copy(alpha = 0.1f) else Color.White.copy(alpha = 0.04f),
        ),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) Cs3.TextPrimary else Cs3.TextPrimary.copy(alpha = 0.3f),
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/**
 * The reference's plugin/extension box, one-to-one: 46dp icon, name + flag,
 * version • size • repo line, two-line description, up to 3 type chips, and
 * the Install / Installed / Uninstall action. Built for the 2-per-row grid.
 */
@Composable
fun Cs3PluginCard(
    name: String,
    version: String,
    sizeLabel: String?,
    repoName: String,
    onRepoClick: (() -> Unit)?,
    flagCode: String?,
    tvTypes: List<String>,
    iconUrl: String?,
    description: String?,
    isInstalled: Boolean,
    installStatus: String,
    isInstalling: Boolean,
    onInstallClick: () -> Unit,
    onUninstallClick: (() -> Unit)?,
    showSettings: Boolean,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Explicit TV focus chain: the card hands Down to its first action, the
    // gear and the action chain Left/Right, and both hand Up back to the
    // card — the D-pad can reach everything on the 2-per-row grid.
    val cardFocus = remember { FocusRequester() }
    val settingsFocus = remember { FocusRequester() }
    val actionFocus = remember { FocusRequester() }
    // What a press of the WHOLE card does: the same thing the card's own
    // action button does — install, update, or uninstall.
    val cardAction: () -> Unit = if (!isInstalled || installStatus == "Update available") onInstallClick else (onUninstallClick ?: {})
    val firstCardAction = if (showSettings) settingsFocus else actionFocus
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 172.dp)
            .focusGroup()
            .focusRequester(cardFocus)
            .focusProperties {
                down = firstCardAction
            }
            .tvPress(previewPass = false, onClick = cardAction),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Cs3PluginIcon(iconUrl = iconUrl, name = name)
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = name,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                            color = Cs3.TextPrimary,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (!flagCode.isNullOrBlank()) {
                            com.hikari.app.ui.FlagIcon(code = flagCode, size = 20.dp)
                        }
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "v$version" + if (!sizeLabel.isNullOrBlank()) " • $sizeLabel" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = Cs3.accent,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                        )
                        if (onRepoClick != null) {
                            Surface(
                                onClick = onRepoClick,
                                shape = RoundedCornerShape(6.dp),
                                color = Cs3.accent.copy(alpha = 0.25f),
                                modifier = Modifier.weight(1f, fill = false),
                            ) {
                                Text(
                                    text = "$repoName ↗",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Cs3.TextPrimary,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                        } else {
                            Text(
                                text = "• $repoName",
                                style = MaterialTheme.typography.bodySmall,
                                color = Cs3.TextMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                        }
                    }
                }
            }
            if (!description.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = Cs3.TextMuted,
                    maxLines = 2,
                    lineHeight = 16.sp,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    tvTypes.take(3).forEach { type ->
                        val isTorrent = type.contains("Torrent", ignoreCase = true)
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (isTorrent) Color(0xFFF59E0B).copy(alpha = 0.18f)
                            else MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                            border = if (isTorrent) androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.4f)) else null,
                        ) {
                            Text(
                                text = if (isTorrent) "⚡ $type" else type,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isTorrent) Color(0xFFF59E0B) else Cs3.TextMuted,
                                fontWeight = if (isTorrent) FontWeight.SemiBold else FontWeight.Normal,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                if (showSettings) {
                    IconButton(
                        onClick = onSettingsClick,
                        modifier = Modifier
                            .size(32.dp)
                            .focusRequester(settingsFocus)
                            .focusProperties {
                                left = cardFocus
                                up = cardFocus
                                right = actionFocus
                            }
                            .tvPress(previewPass = true, onClick = onSettingsClick),
                    ) {
                        Icon(
                            Icons.Filled.Settings,
                            contentDescription = "Plugin settings",
                            tint = Cs3.TextMuted,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                if (onUninstallClick != null && isInstalled) {
                    androidx.compose.material3.OutlinedButton(
                        onClick = onUninstallClick,
                        modifier = Modifier
                            .height(32.dp)
                            .focusRequester(actionFocus)
                            .focusProperties {
                                left = if (showSettings) settingsFocus else cardFocus
                                up = cardFocus
                            }
                            .tvPress(previewPass = true, onClick = onUninstallClick),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.6f)),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text("Uninstall", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    }
                } else if (isInstalled || installStatus == "Installed") {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF1B4D2E).copy(alpha = 0.6f),
                    ) {
                        Text(
                            text = "Installed ✓",
                            color = Color(0xFF81C784),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                } else {
                    androidx.compose.material3.FilledTonalButton(
                        onClick = onInstallClick,
                        enabled = !isInstalling,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                        modifier = Modifier
                            .height(32.dp)
                            .focusRequester(actionFocus)
                            .focusProperties {
                                left = if (showSettings) settingsFocus else cardFocus
                                up = cardFocus
                            }
                            .tvPress(previewPass = true, enabled = !isInstalling, onClick = onInstallClick),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        if (isInstalling) {
                            androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Text("+ Install", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Cs3PluginIcon(iconUrl: String?, name: String) {
    // A repo plugin whose icon URL is missing OR dead (hotlink-guarded CDN,
    // expired file) used to paint a blank dark square. The letter fallback
    // now covers both: blank shows it at once, dead swaps to it when the
    // retries give up (see PosterImage.onFinalError).
    var iconDead by remember(iconUrl) { mutableStateOf(false) }
    if (iconUrl.isNullOrBlank() || iconDead) {
        Box(
            modifier = Modifier.size(46.dp).clip(RoundedCornerShape(12.dp)).background(Cs3.SurfaceElevated),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = name.trim().firstOrNull()?.uppercase() ?: "?",
                color = Cs3.TextPrimary,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }
    } else {
        PosterImage(
            model = iconUrl,
            contentDescription = name,
            contentScale = ContentScale.Crop,
            onFinalError = { iconDead = true },
            modifier = Modifier.size(46.dp).clip(RoundedCornerShape(12.dp)).background(Cs3.SurfaceElevated),
        )
    }
}

/**
 * The reference's provider pick box: icon + flag + name + supporting line,
 * the purple ring + check when selected — the Select Provider grid cell.
 */
@Composable
fun Cs3ProviderBox(
    name: String,
    supporting: String?,
    selected: Boolean,
    iconUrl: String?,
    flagCode: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) Cs3.accent.copy(alpha = 0.22f) else Cs3.SurfaceCard)
            .border(1.5.dp, if (selected) Cs3.accent else Cs3.Edge, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cs3PluginIcon(iconUrl = iconUrl, name = name)
        if (!flagCode.isNullOrBlank()) {
            Spacer(modifier = Modifier.width(8.dp))
            com.hikari.app.ui.FlagIcon(code = flagCode, size = 20.dp)
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                color = Cs3.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!supporting.isNullOrBlank()) {
                Text(
                    supporting,
                    style = MaterialTheme.typography.labelSmall,
                    color = Cs3.TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (selected) {
            Spacer(modifier = Modifier.width(6.dp))
            Icon(Icons.Filled.Check, contentDescription = null, tint = Cs3.accent, modifier = Modifier.size(18.dp))
        }
    }
}

/**
 * Section header with the reference's count pill ("Screenshots 6",
 * "Videos & Trailers 1") — title in titleLarge bold plus the purple pill.
 */
@Composable
fun Cs3CountHeader(
    title: String,
    count: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = Cs3.TextPrimary,
        )
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Cs3.accent.copy(alpha = 0.18f),
        ) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = Cs3.accent,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
            )
        }
    }
}

/**
 * The reference's 16:9 screenshots mosaic, one-to-one: the featured shot at
 * half width, the 2x2 grid beside it, and the dark "+N more" cell when shots
 * remain. 6dp gaps, 14dp corners.
 */
@Composable
fun Cs3ScreenshotsMosaic(
    screenshots: List<String>,
    onShotClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (screenshots.isEmpty()) return
    val gap = 6.dp
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val halfWidth = (maxWidth - gap) / 2
        val mosaicHeight = halfWidth * (9f / 16f)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(mosaicHeight)
                .clip(RoundedCornerShape(14.dp)),
            horizontalArrangement = Arrangement.spacedBy(gap),
        ) {
            Cs3MosaicCell(
                url = screenshots[0],
                onClick = { onShotClick(0) },
                modifier = Modifier.width(halfWidth).fillMaxHeight(),
            )
            if (screenshots.size > 1) {
                val rightItems = screenshots.drop(1).take(3)
                val remainder = screenshots.size - 1 - rightItems.size
                Column(
                    modifier = Modifier.width(halfWidth).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(gap),
                ) {
                    Row(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(gap),
                    ) {
                        Cs3MosaicCell(
                            url = rightItems.getOrNull(0).orEmpty(),
                            onClick = { onShotClick(1) },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                        if (rightItems.size >= 2) {
                            Cs3MosaicCell(
                                url = rightItems[1],
                                onClick = { onShotClick(2) },
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                            )
                        }
                    }
                    if (rightItems.size >= 3) {
                        Row(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(gap),
                        ) {
                            Cs3MosaicCell(
                                url = rightItems[2],
                                onClick = { onShotClick(3) },
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                            )
                            if (remainder > 0) {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .clickable { onShotClick(4) },
                                ) {
                                    if (screenshots.size > 4) {
                                        PosterImage(
                                            model = screenshots[4],
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    }
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(Color.Black.copy(alpha = 0.68f)),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(2.dp),
                                        ) {
                                            Text(
                                                text = "+${remainder + 1}",
                                                style = MaterialTheme.typography.titleLarge,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White,
                                                fontSize = 22.sp,
                                            )
                                            Text(
                                                text = "more",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Medium,
                                                color = Color.White.copy(alpha = 0.75f),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Cs3MosaicCell(
    url: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (url.isBlank()) return
    Box(modifier = modifier.clickable(onClick = onClick)) {
        PosterImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * The reference's trailer card: 320dp 16:9, OFFICIAL + TYPE badges, center
 * play button, title below — over the card's dark scrim.
 */
@Composable
fun Cs3TrailerCard(
    name: String,
    type: String,
    isOfficial: Boolean,
    thumbnailUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .width(320.dp)
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = Cs3.SurfaceElevated,
        border = androidx.compose.foundation.BorderStroke(1.dp, Cs3.accent.copy(alpha = 0.15f)),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (!thumbnailUrl.isNullOrBlank()) {
                PosterImage(
                    model = thumbnailUrl,
                    contentDescription = name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(modifier = Modifier.fillMaxSize().background(Color(0xFF1E1E28)))
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Black.copy(alpha = 0.4f),
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.88f),
                            )
                        )
                    )
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isOfficial) {
                    Surface(shape = RoundedCornerShape(6.dp), color = Cs3.accent) {
                        Text(
                            text = "OFFICIAL",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 0.8.sp,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.size(1.dp))
                }
                Surface(shape = RoundedCornerShape(6.dp), color = Color.Black.copy(alpha = 0.7f)) {
                    Text(
                        text = type.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.6.sp,
                        color = Color.White.copy(alpha = 0.9f),
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                    )
                }
            }
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.65f))
                    .border(1.5.dp, Color.White.copy(alpha = 0.7f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
            }
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}

/**
 * The reference's cast header: "Cast & Crew" + the purple "View All →"
 * text button on the right.
 */
@Composable
fun Cs3CastHeader(
    title: String,
    showViewAll: Boolean,
    onViewAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = Cs3.TextPrimary,
        )
        if (showViewAll) {
            Row(
                modifier = Modifier.clickable(onClick = onViewAll).padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "View All",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Cs3.accent,
                )
                Spacer(modifier = Modifier.width(4.dp))
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = Cs3.accent, modifier = Modifier.size(14.dp))
            }
        }
    }
}

/**
 * The reference's actor card: 2:3 art with 14dp corners, the voice-actor
 * mini avatar bottom-end, name + "🎙 Voice:" line below, and the "View All
 * Cast & Crew" tile that closes the row.
 */
@Composable
fun Cs3ActorCard(
    imageUrl: String?,
    cornerUrl: String?,
    name: String,
    sub: String?,
    roleBadge: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(14.dp)
    Column(modifier = modifier.clickable(onClick = onClick)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(shape)
                .background(Cs3.SurfaceElevated),
        ) {
            if (!imageUrl.isNullOrBlank()) {
                PosterImage(
                    model = imageUrl,
                    contentDescription = name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .align(Alignment.BottomCenter)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.65f)))),
            )
            if (!roleBadge.isNullOrBlank()) {
                Box(
                    modifier = Modifier
                        .padding(8.dp)
                        .align(Alignment.TopStart)
                        .background(Color.Black.copy(alpha = 0.68f), RoundedCornerShape(6.dp))
                        .border(0.5.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                ) {
                    Text(
                        text = roleBadge,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp,
                        color = Cs3.accent,
                    )
                }
            }
            if (!cornerUrl.isNullOrBlank()) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .clip(CircleShape)
                        .background(Cs3.SurfaceCard)
                        .padding(2.5.dp)
                        .clip(CircleShape),
                ) {
                    PosterImage(
                        model = cornerUrl,
                        contentDescription = sub,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().clip(CircleShape),
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = name,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = Cs3.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
        if (!sub.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = sub,
                fontSize = 12.sp,
                color = Cs3.TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
fun Cs3ViewAllCastCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(14.dp))
                .background(Cs3.SurfaceElevated.copy(alpha = 0.5f))
                .border(1.dp, Cs3.Edge, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(12.dp)) {
                Surface(shape = CircleShape, color = Cs3.accent.copy(alpha = 0.2f), modifier = Modifier.size(44.dp)) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = Cs3.accent, modifier = Modifier.size(20.dp))
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                Text("View All", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = Cs3.TextPrimary)
                Text("Cast & Crew", style = MaterialTheme.typography.labelSmall, color = Cs3.TextMuted)
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Full Roster",
            fontSize = 11.5.sp,
            color = Cs3.TextMuted.copy(alpha = 0.65f),
            maxLines = 1,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * The reference's Show Details block: two columns of label/value rows with
 * hairline dividers, then the studio and network logo rows (white logo
 * chips, like the reference).
 */
@Composable
fun Cs3ShowDetails(
    rows: List<Pair<String, String>>,
    studioLogos: List<String>,
    networkLogos: List<String>,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "Show Details",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = Cs3.TextPrimary,
        )
        Spacer(modifier = Modifier.height(8.dp))
        if (rows.isNotEmpty()) {
            val half = (rows.size + 1) / 2
            val left = rows.take(half)
            val right = rows.drop(half)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(48.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    left.forEachIndexed { i, (label, value) ->
                        Cs3DetailRow(label, value)
                        if (i < left.lastIndex) {
                            androidx.compose.material3.HorizontalDivider(color = Color.White.copy(alpha = 0.08f), thickness = 0.5.dp)
                        }
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    right.forEachIndexed { i, (label, value) ->
                        Cs3DetailRow(label, value)
                        if (i < right.lastIndex) {
                            androidx.compose.material3.HorizontalDivider(color = Color.White.copy(alpha = 0.08f), thickness = 0.5.dp)
                        }
                    }
                }
            }
        }
        if (studioLogos.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "PRODUCTION STUDIOS",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.6.sp,
                color = Cs3.TextMuted,
            )
            Spacer(modifier = Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(studioLogos, key = { "st-$it" }) { url -> Cs3LogoChip(url) }
            }
        }
        if (networkLogos.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "BROADCAST NETWORKS",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.6.sp,
                color = Cs3.TextMuted,
            )
            Spacer(modifier = Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(networkLogos, key = { "nw-$it" }) { url -> Cs3LogoChip(url) }
            }
        }
    }
}

@Composable
private fun Cs3DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, fontSize = 14.sp, color = Color.White.copy(alpha = 0.65f))
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = value,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Cs3LogoChip(url: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = Color.White,
        modifier = Modifier.height(44.dp),
    ) {
        PosterImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/**
 * The reference's anti-spoiler pill: label + switch in one 40dp surface
 * row, purple when on.
 */
@Composable
fun Cs3AntiSpoilerPill(
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)),
        modifier = modifier.height(40.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable { onChange(!checked) }.padding(horizontal = 12.dp),
        ) {
            Text(
                text = "Anti-spoiler",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (checked) FontWeight.Bold else FontWeight.Medium,
                color = if (checked) Cs3.accent else Cs3.TextPrimary.copy(alpha = 0.85f),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Switch(
                checked = checked,
                onCheckedChange = onChange,
                modifier = Modifier.scale(0.8f),
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Cs3.accent,
                    checkedTrackColor = Cs3.accent.copy(alpha = 0.4f),
                ),
            )
        }
    }
}

/**
 * The reference's "Sort ▼" chip.
 */
@Composable
fun Cs3SortChip(
    ascending: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
        modifier = modifier,
    ) {
        Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                text = if (ascending) "Sort ▼" else "Sort ▲",
                color = Cs3.TextPrimary,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
