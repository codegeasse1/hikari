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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.hikari.app.data.screenTypeFor
import com.hikari.app.ui.components.PosterImage
import com.hikari.app.ui.theme.rememberGlassTokens
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

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

object PosterTypes {
    const val AUTO = "auto"
    const val CINEMA = "cinema"
    const val VERTICAL = "vertical"

    val ALL = listOf(AUTO, CINEMA, VERTICAL)

    fun normalize(key: String?): String = if (key != null && key in ALL) key else AUTO

    fun label(key: String): String = when (normalize(key)) {
        CINEMA -> "Cinema 16:9"
        VERTICAL -> "Vertical 2:3"
        else -> "Auto"
    }

    fun description(key: String): String = when (normalize(key)) {
        CINEMA -> "Wide landscape cards on phones and TVs alike"
        VERTICAL -> "Tall poster cards on phones and TVs alike"
        else -> "Cinema cards on TV, posters on phone"
    }
}

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
    /** Draw the Dub/Sub + latest-episode pills in the poster's top-center
     *  (CloudStream-style). They print only for series whose episode list the
     *  app has already cached — never a guess. */
    val showEpBadges: Boolean = true,
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
    /** Which shape catalogue cards take (see [PosterTypes]). */
    val posterType: String = PosterTypes.AUTO,
) {
    /** True when [key] is one of the treatments this card wears. */
    fun has(key: String): Boolean = key in effects

    /** True when catalogue cards are drawn 16:9 landscape on this device. */
    fun isCinema(isTv: Boolean): Boolean = when (PosterTypes.normalize(posterType)) {
        PosterTypes.CINEMA -> true
        PosterTypes.VERTICAL -> false
        else -> isTv
    }
}

@Composable
fun rememberPosterStyle(): PosterStyle {
    val app = LocalContext.current.applicationContext as HikariApp
    val blurFlow = remember { app.store.posterBlurFlow() }
    val cornerFlow = remember { app.store.posterCornerFlow() }
    val titlesFlow = remember { app.store.posterShowTitlesFlow() }
    val ratingsFlow = remember { app.store.posterShowRatingsFlow() }
    val typeFlow = remember { app.store.posterShowTypeFlow() }
    val qualityFlow = remember { app.store.posterShowQualityFlow() }
    val epBadgesFlow = remember { app.store.posterShowEpBadgesFlow() }
    val glassFlow = remember { app.store.posterGlassFlow() }
    val effectsFlow = remember { app.store.posterEffectsFlow() }
    val auraFlow = remember { app.store.posterAuraColorFlow() }
    val glowPointFlow = remember { app.store.posterGlowPointFlow() }
    val glowStrengthFlow = remember { app.store.posterGlowStrengthFlow() }
    val posterTypeFlow = remember { app.store.posterTypeFlow() }    // Television performance mode (Settings → TV & Remote): a TV stick is
    val perfFlow = remember { app.store.tvPerfFlow() }
    val tvPerf by perfFlow.collectAsState(initial = false)
    val boosterFlow = remember { app.store.perfModeFlow() }
    val booster by boosterFlow.collectAsState(initial = false)
    val perf = tvPerf || booster
    val blur by blurFlow.collectAsState(initial = 0)
    val corner by cornerFlow.collectAsState(initial = 14)
    val titles by titlesFlow.collectAsState(initial = true)
    val ratings by ratingsFlow.collectAsState(initial = false)
    val showType by typeFlow.collectAsState(initial = true)
    val showQuality by qualityFlow.collectAsState(initial = false)
    val showEpBadges by epBadgesFlow.collectAsState(initial = true)
    val glass by glassFlow.collectAsState(initial = true)
    val effects by effectsFlow.collectAsState(initial = emptySet())
    val auraColor by auraFlow.collectAsState(initial = AuraColors.THEME)
    val glowPoint by glowPointFlow.collectAsState(initial = 0.5f to 0.14f)
    val glowStrength by glowStrengthFlow.collectAsState(initial = 55)
    val posterType by posterTypeFlow.collectAsState(initial = app.store.posterTypeMemory)
    return PosterStyle(
        blur = if (perf) 0 else blur.coerceIn(0, 24),
        corner = corner.coerceIn(0, 28),
        showTitles = titles,
        showRatings = ratings,
        showType = showType,
        showQuality = showQuality,
        showEpBadges = showEpBadges,
        glass = glass,
        effects = if (perf) emptySet() else PosterEffects.normalizeSet(effects),
        auraColor = AuraColors.normalize(auraColor),
        glowX = glowPoint.first.coerceIn(0f, 1f),
        glowY = glowPoint.second.coerceIn(0f, 1f),
        glowStrength = if (perf) 0 else glowStrength.coerceIn(0, 100),
        posterType = PosterTypes.normalize(posterType),
    )
}

/** The rounded shape a poster at [style] uses. */
fun PosterStyle.shape(): RoundedCornerShape = RoundedCornerShape(corner.dp)

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

@Composable
fun rememberPosterBadges(item: MediaItem?, style: PosterStyle): List<String> {
    if (item == null || (!style.showType && !style.showQuality)) return emptyList()
    val revision by com.hikari.app.data.TitleQuality.revision.collectAsState()
    val movieLabel = com.hikari.app.i18n.tr("Movie")
    val seriesLabel = com.hikari.app.i18n.tr("Series")
    val epRevision by com.hikari.app.data.MetaCache.epRevision.collectAsState()
    var epCount by remember(item.uniqueId) { mutableStateOf<Int?>(null) }
    LaunchedEffect(item.uniqueId, epRevision) {
        epCount = withContext(Dispatchers.IO) {
            runCatching { cachedEpisodeCount(item) }.getOrNull()
        }
    }
    return remember(item, style.showType, style.showQuality, revision, movieLabel, seriesLabel, epCount) {
        buildList {
            if (style.showQuality) {
                com.hikari.app.data.TitleQuality.forItem(item)?.let { add(it) }
            }
            if (style.showType) {
                when (screenTypeFor(item, epCount)) {
                    com.hikari.app.data.MediaType.MOVIE -> add(movieLabel)
                    com.hikari.app.data.MediaType.SERIES -> add(seriesLabel)
                    else -> Unit
                }
            }
        }
    }
}

@Composable
fun rememberPosterEpBadges(item: MediaItem?): List<String> {
    if (item == null) return emptyList()
    if (item.type != com.hikari.app.data.MediaType.SERIES &&
        item.type != com.hikari.app.data.MediaType.UNKNOWN
    ) return emptyList()
    val dubLabel = com.hikari.app.i18n.tr("Dub")
    val subLabel = com.hikari.app.i18n.tr("Sub")
    var pills by remember(item.uniqueId) { mutableStateOf<List<String>?>(null) }
    val revision by com.hikari.app.data.MetaCache.epRevision.collectAsState()
    LaunchedEffect(item.uniqueId, revision) {
        pills = withContext(Dispatchers.IO) {
            runCatching { epPillsFor(item, dubLabel, subLabel) }.getOrNull()
        }
    }
    return pills ?: emptyList()
}

/** Episode count from the cached list, or null when never loaded. */
private fun cachedEpisodeCount(item: MediaItem): Int? {
    val key = com.hikari.app.data.MetaCache.episodesKey(item.uniqueId)
    val eps = com.hikari.app.data.MetaCache.cachedEpisodes(key)
        ?: com.hikari.app.data.MetaCache.cachedEpisodes(key + "|trk2")
        ?: return null
    return eps.size
}

/** Latest Dub / Sub episode numbers from the cached list, as pill texts. */
private fun epPillsFor(item: MediaItem, dubLabel: String, subLabel: String): List<String> {
    val key = com.hikari.app.data.MetaCache.episodesKey(item.uniqueId)
    val eps = com.hikari.app.data.MetaCache.cachedEpisodes(key)
        ?: com.hikari.app.data.MetaCache.cachedEpisodes(key + "|trk2")
        ?: return emptyList()
    if (screenTypeFor(item, eps.size) == com.hikari.app.data.MediaType.MOVIE) return emptyList()
    var dub = 0
    var sub = 0
    var plain = 0
    for (e in eps) {
        if (e.number <= 0) continue
        when (com.hikari.app.data.EpisodeDubSub.audioKindOf(e)?.lowercase()) {
            "dub" -> if (e.number > dub) dub = e.number
            "sub" -> if (e.number > sub) sub = e.number
            else -> if (e.number > plain) plain = e.number
        }
    }
    return buildList {
        if (dub > 0) add("$dubLabel Ep $dub")
        if (sub > 0) add("$subLabel Ep $sub")
        if (dub == 0 && sub == 0 && plain > 0) add("Ep $plain")
    }
}

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
    badgeAlignment: Alignment = Alignment.TopStart,
    /** Where the artwork itself sits inside its frame. [Alignment.TopCenter] is
     *  what the personal-catalog cover tiles ask for: a phone photo used as a
     *  poster keeps its top rather than being cut in the middle. */
    imageAlignment: Alignment = Alignment.Center,
    item: MediaItem? = null,
    /** Fires when the main artwork is definitively dead (see [PosterImage]):
     *  the detail hero uses it to fall back to looked-up art. Placed before
     *  [overlay] so the trailing content lambda keeps binding to [overlay]. */
    onImageError: (() -> Unit)? = null,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    val shape = style.shape()
    val badges = rememberPosterBadges(item, style)
    val epPills = rememberPosterEpBadges(if (style.showEpBadges) item else null)
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
            initialValue = 0.62f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1500, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "breathe",
        )
    } else {
        null
    }
    val accent = MaterialTheme.colorScheme.primary
    val accentAlt = MaterialTheme.colorScheme.tertiary
    val auraStart = AuraColors.color(style.auraColor, accent)
    val auraEnd = AuraColors.color(style.auraColor, accentAlt)
    // Glow/Spotlight ask for a halo regardless of the blur slider; the other
    // treatments leave the slider's own value alone.
    val halo = if (PosterEffects.haloed(effects)) maxOf(style.blur, 16) else style.blur
    val innerCorner = (style.corner - 3).coerceAtLeast(0)
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
                onFinalError = onImageError,
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
            if (epPills.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 5.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    epPills.forEach { label ->
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
        if (ringed) {
            Box(
                Modifier
                    .matchParentSize()
                    .border(5.dp, auraStart.copy(alpha = 0.28f), shape)
            )
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer { alpha = breathe?.value ?: 1f }
                    .border(
                        2.5.dp,
                        Brush.linearGradient(listOf(auraStart, auraEnd, auraStart)),
                        shape,
                    )
            )
        }
    }
}

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
