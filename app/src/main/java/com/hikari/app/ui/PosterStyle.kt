package com.hikari.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hikari.app.HikariApp
import com.hikari.app.data.MediaItem
import com.hikari.app.data.Ratings
import com.hikari.app.ui.components.PosterImage
import com.hikari.app.ui.theme.rememberGlassTokens
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/**
 * The visual treatments a poster card can wear — Settings → App Layout →
 * Poster styling → Poster effect.
 *
 * These are the "signature" card styles the reference clients are known for,
 * built from nothing but what a card already draws: the artwork it is loading,
 * the accent colour, and one animation clock. None of them costs an extra
 * network request or a second copy of the image, so switching one on is instant
 * and works offline over a cached poster.
 *
 *  - [GLOW]      the coloured halo behind the art, pushed harder than the blur
 *                slider's quiet default — this is the reference client's look.
 *  - [TILT]      the card leans back in 3D, as if laid on a table seen from the
 *                side, with a matching highlight.
 *  - [SHEEN]     a band of light sweeps across the art every couple of seconds,
 *                the way a glossy print does when you tilt it.
 *  - [AURA]      a breathing accent-gradient ring around the card.
 *  - [SPOTLIGHT] an accent spotlight behind the card plus a bottom scrim, so
 *                the artwork reads as lit from the page rather than pasted on.
 *  - [FRAME]     a gallery mat: the art inset behind a hairline frame with a
 *                soft accent tint, like a print in a mount.
 *  - [LIT]       one light over the card, standing wherever the reader put it
 *                (Settings → Poster styling → Edge light): the artwork is
 *                brightened where the light falls and taken down towards shadow
 *                everywhere else, which is what makes a flat poster read as a
 *                lit object. The only treatment here whose LOOK is the user's
 *                (see [PosterStyle.glowX]/[PosterStyle.glowY]).
 */
object PosterEffects {
    const val NONE = "none"
    const val GLOW = "glow"
    const val TILT = "tilt"
    const val SHEEN = "sheen"
    const val AURA = "aura"
    const val SPOTLIGHT = "spotlight"
    const val FRAME = "frame"
    const val LIT = "lit"

    /** Every effect, in the order Settings lists them. */
    val ALL = listOf(NONE, GLOW, TILT, SHEEN, AURA, SPOTLIGHT, FRAME, LIT)

    fun normalize(key: String?): String = if (key != null && key in ALL) key else NONE

    /** The treatments named by [keys], in [ALL] order and without [NONE].
     *
     *  A card can wear SEVERAL at once — the choice is a set in Settings, so
     *  "sheen and aura ring" or "gallery frame and 3D tilt" are each one
     *  selection, and each treatment draws its own layer (see [PosterArt]). */
    fun normalizeSet(keys: Collection<String>?): Set<String> =
        if (keys.isNullOrEmpty()) emptySet()
        else ALL.filter { it != NONE && it in keys }.toSet()

    /** A single stored key from an older build, or a comma-joined list, read back
     *  as the treatments it names. */
    fun parse(stored: String?): Set<String> {
        val s = stored?.trim().orEmpty()
        if (s.isEmpty()) return emptySet()
        return normalizeSet(s.split(',').map { it.trim() })
    }

    /** The chosen treatments as ONE preference value (see [parse]). */
    fun encode(keys: Collection<String>): String =
        ALL.filter { it in keys }.joinToString(",")

    fun label(key: String): String = when (normalize(key)) {
        GLOW -> "Glow"
        TILT -> "3D tilt"
        SHEEN -> "Sheen"
        AURA -> "Aura ring"
        SPOTLIGHT -> "Spotlight"
        FRAME -> "Gallery frame"
        LIT -> "Edge light"
        else -> "None"
    }

    /** The chosen treatments as one line: "Sheen + Aura ring". */
    fun label(keys: Collection<String>): String {
        val names = ALL.filter { it != NONE && it in keys }.map { label(it) }
        return if (names.isEmpty()) label(NONE) else names.joinToString(" + ")
    }

    /** One line on what the effect does, shown under its name in Settings. */
    fun description(key: String): String = when (normalize(key)) {
        GLOW -> "Soft coloured halo around the artwork"
        TILT -> "Cards lean back in 3D with a highlight"
        SHEEN -> "A band of light sweeps across the art"
        AURA -> "A breathing ring around the card, in its own colour"
        SPOTLIGHT -> "Accent spotlight behind, scrim over the bottom"
        FRAME -> "Art inset behind a hairline gallery frame"
        LIT -> "Lit from a point you choose, darker away from it"
        else -> "Plain artwork, no effect"
    }

    /** The chosen treatments as one line — the description of the first, plus a
     *  count of the others, so the row stays one line tall. */
    fun description(keys: Collection<String>): String {
        val chosen = ALL.filter { it != NONE && it in keys }
        if (chosen.isEmpty()) return description(NONE)
        val first = description(chosen.first())
        return if (chosen.size == 1) first
        else first + " · +" + (chosen.size - 1).toString() + " more"
    }

    /** True when any chosen treatment animates (so the card runs an animation
     *  clock for the sheen and/or the aura ring). */
    fun animated(keys: Collection<String>): Boolean =
        keys.any { it == SHEEN || it == AURA }

    /** Kept for a stored value from an older build. */
    fun animated(key: String): Boolean = animated(normalizeSet(listOf(key)))

    /** True when any chosen treatment wants the blurred halo behind the art. */
    fun haloed(keys: Collection<String>): Boolean =
        keys.any { it == GLOW || it == SPOTLIGHT }

    /** Kept for a stored value from an older build. */
    fun haloed(key: String): Boolean = haloed(normalizeSet(listOf(key)))
}

/**
 * The user's poster styling — Settings → App Layout → Poster styling.
 *
 * These are the knobs the reference client offers on its artwork: the dynamic
 * iOS-style blur behind a poster, the corner rounding, whether the title and the
 * score are drawn over the art at all, and which of the [PosterEffects] the card
 * wears (one, several, or none). Every grid in the app reads them from
 * [rememberPosterStyle] and renders through [PosterArt], so a change in Settings
 * is visible on the very next frame everywhere at once.
 */
data class PosterStyle(
    /** Blur radius in dp for the coloured halo behind the art (0 = none). */
    val blur: Int = 0,
    /** Corner rounding in dp. */
    val corner: Int = 14,
    /** Draw the title under the poster. */
    val showTitles: Boolean = true,
    /** Draw the score badge on the poster (only where a score is known). */
    val showRatings: Boolean = false,
    /** Draw the movie/series tag on the poster. On by default: whether a tile is
     *  a film or a show is the one thing a poster cannot say by itself. */
    val showType: Boolean = true,
    /** Draw the quality tag on the poster ([com.hikari.app.data.TitleQuality] —
     *  only titles whose quality the app has actually seen get one). */
    val showQuality: Boolean = false,
    /** The glass hairline + frosted backing every card in the app shares. */
    val glass: Boolean = true,
    /** The signature looks drawn over the card — a SET of [PosterEffects], so a
     *  card can wear several at once (sheen + aura ring, gallery frame + 3D
     *  tilt…) and an empty set means "none". */
    val effects: Collection<String> = emptyList(),
    /** The colour the [PosterEffects.AURA] ring is drawn in
     *  ([com.hikari.app.ui.AuraColors.THEME] follows the app accent). */
    val auraColor: String = AuraColors.THEME,
    /** Where the [PosterEffects.LIT] light stands, as fractions of the card:
     *  (0, 0) is the top-left corner and (1, 1) the bottom-right. */
    val glowX: Float = 0.5f,
    val glowY: Float = 0.14f,
    /** How hard that light burns, 0-100. */
    val glowStrength: Int = 55,
) {
    /** True when [key] is one of the treatments this card wears. */
    fun has(key: String): Boolean = key in effects
}

/** The live poster style, collected from the store. Remembered flows, so the
 *  subscriptions survive recomposition.
 *
 *  Called ONCE per row/grid and passed down (see [PosterArt]); a copy per poster
 *  cell would open one DataStore collection per card on screen. */
@Composable
fun rememberPosterStyle(): PosterStyle {
    val app = LocalContext.current.applicationContext as HikariApp
    val blurFlow = remember { app.store.posterBlurFlow() }
    val cornerFlow = remember { app.store.posterCornerFlow() }
    val titlesFlow = remember { app.store.posterShowTitlesFlow() }
    val ratingsFlow = remember { app.store.posterShowRatingsFlow() }
    val typeFlow = remember { app.store.posterShowTypeFlow() }
    val qualityFlow = remember { app.store.posterShowQualityFlow() }
    val glassFlow = remember { app.store.posterGlassFlow() }
    val effectsFlow = remember { app.store.posterEffectsFlow() }
    val auraFlow = remember { app.store.posterAuraColorFlow() }
    val glowPointFlow = remember { app.store.posterGlowPointFlow() }
    val glowStrengthFlow = remember { app.store.posterGlowStrengthFlow() }
    // Television performance mode (Settings → TV & Remote): a TV stick is
    // decoding 1080p with a chip a phone would have called slow, so while it is
    // on the expensive per-poster work is dropped — the animated treatments and
    // the blurred halo behind each card — and the posters are drawn plain.
    val perfFlow = remember { app.store.tvPerfFlow() }
    val tvPerf by perfFlow.collectAsState(initial = false)
    // The PERFORMANCE BOOSTER (Settings → Performance) drops exactly the same
    // work, because it is the same complaint from the other kind of device: the
    // blur behind every poster is three artwork layers per card and a gaussian
    // blur on API 31+, which is the single most expensive thing Hikari's UI does
    // per frame. Read here, where that cost is decided.
    val boosterFlow = remember { app.store.perfModeFlow() }
    val booster by boosterFlow.collectAsState(initial = false)
    val perf = tvPerf || booster
    val blur by blurFlow.collectAsState(initial = 0)
    val corner by cornerFlow.collectAsState(initial = 14)
    val titles by titlesFlow.collectAsState(initial = true)
    val ratings by ratingsFlow.collectAsState(initial = false)
    val showType by typeFlow.collectAsState(initial = true)
    val showQuality by qualityFlow.collectAsState(initial = false)
    val glass by glassFlow.collectAsState(initial = true)
    val effects by effectsFlow.collectAsState(initial = emptySet())
    val auraColor by auraFlow.collectAsState(initial = AuraColors.THEME)
    val glowPoint by glowPointFlow.collectAsState(initial = 0.5f to 0.14f)
    val glowStrength by glowStrengthFlow.collectAsState(initial = 55)
    return PosterStyle(
        blur = if (perf) 0 else blur.coerceIn(0, 24),
        corner = corner.coerceIn(0, 28),
        showTitles = titles,
        showRatings = ratings,
        showType = showType,
        showQuality = showQuality,
        glass = glass,
        effects = if (perf) emptySet() else PosterEffects.normalizeSet(effects),
        auraColor = AuraColors.normalize(auraColor),
        glowX = glowPoint.first.coerceIn(0f, 1f),
        glowY = glowPoint.second.coerceIn(0f, 1f),
        // The light is a per-card gradient, and a television in performance mode
        // is not drawing treatments at all (the set above is empty then) — the
        // zero is belt and braces for any card that asks for it directly.
        glowStrength = if (perf) 0 else glowStrength.coerceIn(0, 100),
    )
}

/** The rounded shape a poster at [style] uses. */
fun PosterStyle.shape(): RoundedCornerShape = RoundedCornerShape(corner.dp)

/**
 * The score a poster's badge should print for [item], or null when the badges
 * are switched off or nothing is known about the title yet.
 *
 * This is the ONE place a poster grid asks for a score, so every grid warms and
 * repaints identically: the cell registers itself for a background lookup
 * ([Ratings.ensure] — cheap once the title is on file), reads the revision so
 * only the cells showing that title repaint when the answer lands
 * ([Ratings.revision]), and prints whatever the cache holds
 * ([Ratings.cachedBadge] — IMDb first, then TMDB's own average). A grid that
 * only did the cache read showed a score for the titles the user had already
 * opened and a blank corner for the rest.
 *
 * The item's own TMDB average ([MediaItem.rating], carried by every TMDB-sourced
 * row) is the last fallback, so a row that came in with scores prints them on
 * the first frame instead of waiting for a lookup.
 */
@Composable
fun rememberPosterScore(item: MediaItem, style: PosterStyle): String? {
    if (!style.showRatings) return null
    LaunchedEffect(item.uniqueId) {
        // The warm-up queue is bounded, and a Home feed composes more posters
        // than it will take at once; a refused title used to stay bare for the
        // life of its cell, because nothing asks twice. Ask again until it is
        // taken — a refused call is one memory read, and once the title is on
        // file (score or scoreless answer) the first call already returns.
        var attempt = 0
        while (attempt < 14 && !Ratings.ensure(item)) {
            attempt++
            delay(900L + attempt * 500L)
        }
    }
    Ratings.revision(item)
    return Ratings.cachedBadge(item)
        ?: item.rating?.takeIf { it > 0.0 }
            ?.let { ((it * 10f).roundToInt() / 10f).toString() }
}

/**
 * The tags a poster draws in its top-left corner, in the order they are stacked:
 * first the one that is switched on alone, then the second directly under it.
 *
 *  - the QUALITY tag ([com.hikari.app.data.TitleQuality]) — the best quality the
 *    app has actually seen for this title, never a guess;
 *  - the TYPE tag — "MOVIE" or "SERIES", so a grid tells a film from a show
 *    without opening it.
 *
 * The order is fixed rather than "quality first when it exists": with both
 * switched on the pair must not swap places between two posters, and with only
 * one switched on it must simply sit at the top (the reported "when only one is
 * on it shows above, not down below") — which is exactly what a Column anchored
 * at the top-left does, whatever it is handed.
 */
@Composable
fun rememberPosterBadges(item: MediaItem?, style: PosterStyle): List<String> {
    if (item == null || (!style.showType && !style.showQuality)) return emptyList()
    // Re-reads itself when a new quality lands, so a grid that is already on
    // screen picks up the badge the moment a search teaches the app the answer
    // (see [com.hikari.app.data.TitleQuality.revision]).
    val revision by com.hikari.app.data.TitleQuality.revision.collectAsState()
    // The labels are resolved OUT here: `tr` is a composable, so it cannot be
    // called from inside a `remember` lambda. They are also the two things that
    // change when the app's language does, so the value below is keyed on them.
    // NOT upper-cased: the reference chips read "Web" / "Blu-ray" in title case,
    // and shouting "SERIES" next to a quiet "1080p" would be two different
    // designs in one corner.
    val movieLabel = com.hikari.app.i18n.tr("Movie")
    val seriesLabel = com.hikari.app.i18n.tr("Series")
    return remember(item, style.showType, style.showQuality, revision, movieLabel, seriesLabel) {
        buildList {
            if (style.showQuality) {
                com.hikari.app.data.TitleQuality.forItem(item)?.let { add(it) }
            }
            if (style.showType) {
                when (item.type) {
                    com.hikari.app.data.MediaType.MOVIE -> add(movieLabel)
                    com.hikari.app.data.MediaType.SERIES -> add(seriesLabel)
                    else -> Unit
                }
            }
        }
    }
}

/**
 * The poster edge light ([PosterEffects.LIT]) as a draw modifier: a card lit
 * from ([centerX], [centerY]) — expressed as fractions of the card, so the same
 * numbers mean "a third of the way across, just under the top edge" on a 90dp
 * grid cell and on a 400dp hero — and falling into shadow away from it.
 *
 * Two radial gradients, and both are needed for the look to read as LIGHT
 * rather than as a bright blob:
 *
 *  * a soft white pool centred on the point, which is the light itself;
 *  * a shadow that grows with distance from the same point, because a lit object
 *    is not only brighter where the light falls, it is darker where it does not.
 *    Without this half the card is uniformly bright and reads as pasted on.
 *
 * Nothing is decoded, blurred or cached for either: a radial gradient is a draw
 * call, so the treatment costs the same on a 4K television as on a phone, and it
 * works over an artwork that has not finished loading.
 */
fun Modifier.edgeLight(centerX: Float, centerY: Float, strength: Float): Modifier =
    if (strength <= 0f) this
    else drawBehind {
        val c = Offset(
            size.width * centerX.coerceIn(0f, 1f),
            size.height * centerY.coerceIn(0f, 1f),
        )
        // The far corner is how far the shadow has to reach to cover the whole
        // card from wherever the light stands.
        val reach = maxOf(
            hypot(c.x, c.y),
            hypot(size.width - c.x, c.y),
            hypot(c.x, size.height - c.y),
            hypot(size.width - c.x, size.height - c.y),
        ).coerceAtLeast(1f)
        drawRect(
            Brush.radialGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.34f * strength),
                    Color.White.copy(alpha = 0.10f * strength),
                    Color.Transparent,
                ),
                center = c,
                radius = reach * 0.66f,
            )
        )
        drawRect(
            Brush.radialGradient(
                colorStops = arrayOf(
                    0f to Color.Transparent,
                    0.55f to Color.Black.copy(alpha = 0.10f * strength),
                    1f to Color.Black.copy(alpha = 0.52f * strength),
                ),
                center = c,
                radius = reach,
            )
        )
    }

/**
 * One poster cell, styled.
 *
 * This is the single place poster styling is applied, so every grid — Home's
 * rows, Show All, Search, Library, a collection's TMDB source — looks the same
 * and honours the same settings.
 *
 * The "iOS-style blur" is the artwork itself, enlarged a touch and blurred,
 * sitting behind the crisp poster as a soft coloured halo: that is what gives
 * the reference client's cards their glow, and it costs one extra draw of an
 * image the card is loading anyway. The blur is a tiny decode scaled back up
 * ([PosterLoader.haloModel]), not `Modifier.blur` — that modifier does not exist
 * below Android 12, so the halo used to show for some users and not others.
 *
 * @param model   the Coil model (see [Artwork.model])
 * @param rating  TMDB score to badge, when [PosterStyle.showRatings] is on and
 *                the item actually has one
 * @param imdb    the score already on file for this title ([Ratings] — the IMDb
 *                number when one could be resolved, otherwise TMDB's average),
 *                which wins over [rating] when present — it is the number the
 *                score badge is *for*, and the one the reference client prints
 * @param overlay extra content on top of the art (a heart badge, a kebab menu…)
 */
@Composable
fun PosterArt(
    model: Any?,
    contentDescription: String?,
    style: PosterStyle,
    modifier: Modifier = Modifier,
    rating: Double? = null,
    imdb: String? = null,
    contentScale: ContentScale = ContentScale.Crop,
    /** Corner the score badge sits in. Overridable because a card that already
     *  puts its own buttons in the top-right (Library's favourites) needs the
     *  badge somewhere else rather than on top of them. */
    ratingAlignment: Alignment = Alignment.TopEnd,
    /** Corner the type/quality tag stack hangs from. The top-LEFT default is the
     *  free corner on every standard grid (the score badge has the top-right);
     *  a cell that already spends the top-left on its own badge — the
     *  Related/Similar shelves put the score there, because the kebab owns the
     *  top-right — passes another corner instead of stacking the two on top of
     *  each other. */
    badgeAlignment: Alignment = Alignment.TopStart,
    /** Where the artwork itself sits inside its frame. [Alignment.TopCenter] is
     *  what the personal-catalog cover tiles ask for: a phone photo used as a
     *  poster keeps its top rather than being cut in the middle. */
    imageAlignment: Alignment = Alignment.Center,
    /** The catalogue item this art belongs to, when the caller has one: the
     *  movie/series tag and the quality tag are derived from it (see
     *  [rememberPosterBadges]). Optional, so a card that draws art for something
     *  that is not a catalogue item (a cast portrait, a collection tile) simply
     *  passes nothing and gets no tags. */
    item: MediaItem? = null,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    val shape = style.shape()
    val badges = rememberPosterBadges(item, style)
    val glass = rememberGlassTokens()
    val effects = PosterEffects.normalizeSet(style.effects)
    val tilted = PosterEffects.TILT in effects
    val sheened = PosterEffects.SHEEN in effects
    val ringed = PosterEffects.AURA in effects
    val spotlighted = PosterEffects.SPOTLIGHT in effects
    val framed = PosterEffects.FRAME in effects
    val lit = PosterEffects.LIT in effects
    // 0-1, and 0 when the light is not one of this card's treatments: every layer
    // below is skipped outright at zero, so a card without it pays nothing.
    val lightStrength = if (lit) style.glowStrength.coerceIn(0, 100) / 100f else 0f
    // One animation clock per card, created only when one of the chosen
    // treatments animates. The animated values are read INSIDE graphicsLayer,
    // never in composition, so a moving sheen or a breathing ring invalidates
    // the card's drawing layer and nothing else — no recomposition per frame.
    val clock = if (sheened || ringed) {
        rememberInfiniteTransition(label = "poster-effect")
    } else {
        null
    }
    val sweep = if (clock != null && sheened) {
        clock.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing)),
            label = "sweep",
        )
    } else {
        null
    }
    val breathe = if (clock != null && ringed) {
        clock.animateFloat(
            initialValue = 0.42f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1500, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "breathe",
        )
    } else {
        null
    }
    val accent = MaterialTheme.colorScheme.primary
    val accentAlt = MaterialTheme.colorScheme.tertiary
    // The aura ring's colour: its own setting (Settings → App Layout → Poster
    // styling → Aura ring colour), or the app accent when that is left on
    // "Accent". See [AuraColors].
    val auraStart = AuraColors.color(style.auraColor, accent)
    val auraEnd = AuraColors.color(style.auraColor, accentAlt)
    // Glow/Spotlight ask for a halo regardless of the blur slider; the other
    // treatments leave the slider's own value alone.
    val halo = if (PosterEffects.haloed(effects)) maxOf(style.blur, 16) else style.blur
    val innerCorner = (style.corner - 3).coerceAtLeast(0)
    // IMDb's own yellow, not the app accent: the badge is a score from that
    // site, and the colour is what makes "9.8" read as an IMDb rating at a
    // glance instead of as one more piece of the app's chrome.
    // The cached score first (IMDb, else TMDB — see Ratings.cachedBadge), then
    // the row's own TMDB value: three chances at a number, so a poster that
    // carries any score at all prints one instead of an empty corner.
    val badge = imdb?.takeIf { it.isNotBlank() }
        ?: rating?.takeIf { it > 0.0 }?.let { ((it * 10f).roundToInt() / 10f).toString() }
    Box(
        modifier = modifier.then(
            if (tilted) {
                Modifier.graphicsLayer {
                    // A real 3D lean, not a flat skew. Two things make it read
                    // as depth:
                    //
                    //  - the camera sits CLOSE to the card. Compose's default
                    //    camera distance is 8, and SMALLER is a stronger
                    //    perspective (larger would be nearer to orthographic).
                    //    This used to be `14f * density` — ~5x FURTHER away than
                    //    the default on a 3x screen, i.e. almost no perspective
                    //    at all, which is exactly the reported "3D tilt doesn't
                    //    look 3D";
                    //  - the angles are big enough to see, and the near edge is
                    //    brightened while the far edge is shaded (below), so the
                    //    two sides of the card are visibly at different depths.
                    rotationY = -12f
                    rotationX = 6f
                    cameraDistance = 6f
                }
            } else {
                Modifier
            }
        )
    ) {
        if (tilted) {
            // The shadow the lean casts: the card's own silhouette, pushed down
            // and towards the side the card leans away from, so the tilt reads
            // as a card standing in front of the page rather than as a slanted
            // picture. Drawn inside the tilting layer (it leans with the card)
            // and before the art, so only the peeking edge is visible.
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        translationX = 5f * density
                        translationY = 11f * density
                    }
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Black.copy(alpha = 0.45f),
                                Color.Black.copy(alpha = 0.08f),
                            )
                        ),
                        shape,
                    )
            )
        }
        if (spotlighted) {
            // The accent light the card is standing in, larger than the card so
            // it spills onto the page.
            Box(
                Modifier
                    .matchParentSize()
                    .scale(1.20f)
                    .background(
                        Brush.radialGradient(listOf(accent.copy(alpha = 0.40f), Color.Transparent)),
                        shape,
                    )
            )
        }
        if (halo > 0) {
            // The halo is a TINY decode of the same artwork, scaled back up —
            // see [PosterLoader.haloModel]. That is what makes it show on every
            // Android version: `Modifier.blur` alone is a NO-OP below API 31, so
            // identical settings gave one user a coloured glow and another flat
            // artwork (the "the blur effect doesn't show for him" report).
            //
            // Two layers, because that is what a glow actually is: a tight band
            // of the artwork's own colours right at the card's edge, and a wide
            // faint one spilling further out, so the light FALLS OFF instead of
            // ending in a hard rim. On API 31+ a real gaussian blur is thrown on
            // top (with an UNBOUNDED edge treatment, so the blur is not clipped
            // back to the cell — that clipping is what made the halo read as a
            // flat 4dp outline); below 31 the upscaled 6-34px decode already
            // carries the softness.
            val haloModel = remember(model, halo) { PosterLoader.haloModel(model, halo) }
            val soft = remember(halo) {
                if (android.os.Build.VERSION.SDK_INT >= 31) {
                    Modifier.blur(halo.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded)
                } else {
                    Modifier
                }
            }
            // The wide, faint spill. Scales with the blur slider, so the slider
            // is a real gradient of softness rather than a switch.
            val spread = 1.10f + (halo.coerceIn(0, 24) / 100f)
            PosterImage(
                model = haloModel,
                contentDescription = null,
                modifier = Modifier
                    .matchParentSize()
                    .scale(spread)
                    .then(soft)
                    .alpha(0.34f),
                contentScale = ContentScale.Crop,
            )
            // The tight band at the card's edge — the bit that makes the card
            // look like it is glowing rather than sitting on a coloured smear.
            PosterImage(
                model = haloModel,
                contentDescription = null,
                modifier = Modifier
                    .matchParentSize()
                    .scale(1.04f)
                    .then(soft)
                    .alpha(0.72f),
                contentScale = ContentScale.Crop,
            )
        }
        Box(
            Modifier
                .matchParentSize()
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .then(
                    if (style.glass) Modifier.border(1.dp, glass.border, shape) else Modifier
                ),
            contentAlignment = Alignment.Center,
        ) {
            // Behind the artwork: a cell whose image 403s/404s still reads as a
            // poster slot instead of a blank rectangle.
            Icon(
                Icons.Filled.Movie,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.40f),
                modifier = Modifier.size(28.dp),
            )
            PosterImage(
                model = model,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale,
                alignment = imageAlignment,
            )
            if (spotlighted) {
                // Darkens the foot of the art, which is what makes the card look
                // lit from above instead of uniformly flat.
                Box(
                    Modifier
                        .matchParentSize()
                        .background(
                            Brush.verticalGradient(
                                0.45f to Color.Transparent,
                                1f to Color.Black.copy(alpha = 0.55f),
                            )
                        )
                )
            }
            if (lightStrength > 0f) {
                // The reader's own light (see [PosterEffects.LIT]): over the
                // artwork, under everything the other treatments draw on top of
                // it — a light is part of how the art is LIT, not a layer above
                // the art, so a card wearing both this and a sheen keeps the
                // sheen on top.
                Box(
                    Modifier
                        .matchParentSize()
                        .edgeLight(style.glowX, style.glowY, lightStrength)
                )
            }
            if (framed) {
                // A whisper of accent tint over the art, then the mount itself.
                Box(
                    Modifier
                        .matchParentSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(accent.copy(alpha = 0.14f), Color.Transparent, accentAlt.copy(alpha = 0.10f))
                            )
                        )
                )
            }
            if (tilted) {
                // The highlight the lean promises: the near (right/top) edge of
                // the card catches the light, the far edge falls into shade.
                // Drawn over the art, inside the tilting layer, so it follows
                // the card's angle.
                Box(
                    Modifier
                        .matchParentSize()
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = 0.20f),
                                    Color.Transparent,
                                    Color.Black.copy(alpha = 0.26f),
                                ),
                                start = Offset.Zero,
                                end = Offset.Infinite,
                            )
                        )
                )
            }
            if (sheened && sweep != null) {
                Box(
                    Modifier
                        .matchParentSize()
                        .graphicsLayer {
                            translationX = -size.width + sweep.value * size.width * 2f
                            rotationZ = 16f
                        }
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color.Transparent, Color.White.copy(alpha = 0.17f), Color.Transparent)
                            )
                        )
                )
            }
            if (style.showRatings && badge != null) {
                RatingBadge(
                    text = badge,
                    modifier = Modifier
                        .align(ratingAlignment)
                        .padding(5.dp),
                )
            }
            // The type/quality tags stack down from the top-LEFT corner — the
            // score badge's own corner is the top-right on every grid, so the
            // two can never land on each other. One tag sits at the top of the
            // stack, two sit one above the other with a hairline gap.
            if (badges.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .align(badgeAlignment)
                        .padding(5.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    badges.forEach { label ->
                        PosterTag(text = label)
                    }
                }
            }
            overlay()
        }
        if (framed) {
            Box(
                Modifier
                    .matchParentSize()
                    .padding(4.dp)
                    .border(
                        1.dp,
                        Color.White.copy(alpha = 0.22f),
                        RoundedCornerShape(innerCorner.dp),
                    )
            )
        }
        if (ringed && breathe != null) {
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer { alpha = breathe.value }
                    .border(
                        2.dp,
                        Brush.linearGradient(listOf(auraStart, auraEnd, auraStart)),
                        shape,
                    )
            )
        }
    }
}

/**
 * A poster's corner tag: the quality ("4K", "1080p", "Blu-ray") and the type
 * ("Movie", "Series").
 *
 * Deliberately NOT [RatingBadge]: that one is IMDb-yellow because a score IS an
 * IMDb number, and a yellow "Movie" would read as one more rating. The tags are
 * plain white on the same dark translucent pill, which is the shape the
 * reference clients draw their own quality chips in (their "4K" / "Web" /
 * "Blu-ray" labels are white on a dark rounded chip in the poster's top-left
 * corner), so the two kinds of badge stay distinguishable at a glance on a cell
 * that carries both.
 */
@Composable
fun PosterTag(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(5.dp))
            .background(Color.Black.copy(alpha = 0.66f))
            .padding(horizontal = 4.dp, vertical = 1.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            maxLines = 1,
        )
    }
}

/**
 * The score chip: IMDb's yellow number on a dark translucent pill so it stays
 * readable over any artwork. Deliberately SMALL and tucked into the poster's
 * top-right corner — a grid is for the art, and a score the size of the title
 * would be the loudest thing on the page.
 */
@Composable
fun RatingBadge(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(5.dp))
            .background(Color.Black.copy(alpha = 0.66f))
            .padding(horizontal = 4.dp, vertical = 1.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFF5C518),
            maxLines = 1,
        )
    }
}
