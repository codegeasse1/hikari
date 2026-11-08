package com.hikari.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hikari.app.i18n.tr
import com.hikari.app.ui.theme.rememberGlassTokens
import kotlinx.coroutines.withTimeoutOrNull

const val QUICK_MENU_HOLD_MS = 1000L

class QuickMenuState {
    var visible by mutableStateOf(false)
    var selected by mutableIntStateOf(1)
}

@Composable
fun rememberQuickMenuState(): QuickMenuState = remember { QuickMenuState() }

fun Modifier.quickMenuGesture(
    state: QuickMenuState,
    onPick: (Int) -> Unit,
): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown()
        val start = down.position
        val slop = viewConfiguration.touchSlop
        var outcome: String? = null
        withTimeoutOrNull(QUICK_MENU_HOLD_MS) {
            var ev = awaitPointerEvent()
            while (ev.changes.all { it.pressed }) {
                val p = ev.changes.firstOrNull()?.position
                if (p != null && (p - start).getDistance() > slop) {
                    outcome = "drag"
                    return@withTimeoutOrNull
                }
                ev = awaitPointerEvent()
            }
            outcome = "up"
        }
        // Timeout (outcome still null) is the ONLY path that shows the menu: a
        // tap or a scroll leaves everything to the card/row.
        if (outcome != null) return@awaitEachGesture
        state.selected = 1
        state.visible = true
        var sel = 1
        try {
            while (true) {
                val ev = awaitPointerEvent()
                val x = ev.changes.firstOrNull()?.position?.x
                val w = size.width.toFloat()
                if (x != null && w > 0f) {
                    sel = ((x / w) * 4).toInt().coerceIn(0, 3)
                    state.selected = sel
                }
                ev.changes.forEach { it.consume() }
                if (ev.changes.all { !it.pressed }) break
            }
        } finally {
            state.visible = false
        }
        onPick(sel)
    }
}

@Composable
fun BoxScope.QuickMenuOverlay(selected: Int) {
    val scheme = MaterialTheme.colorScheme
    val glass = rememberGlassTokens()
    val detailsLabel = tr("Details")
    val playLabel = tr("Play")
    val libraryLabel = tr("Library")
    val closeLabel = tr("Close")
    val hint = tr("Slide to explore")
    BoxWithConstraints(Modifier.matchParentSize()) {
        val spacing = (maxWidth * 0.04f).coerceAtMost(10.dp)
        val disc = ((maxWidth - spacing * 3 - 16.dp) / 4).coerceIn(20.dp, 40.dp)
        Canvas(Modifier.matchParentSize()) {
            val w = size.width
            val h = size.height
            // Dim the art so the icons read on any poster.
            drawRect(Color.Black.copy(alpha = 0.30f))
            val discPx = disc.toPx()
            val spacingPx = spacing.toPx()
            val rowW = discPx * 4f + spacingPx * 3f
            val padX = 10.dp.toPx()
            val padT = 8.dp.toPx()
            val padB = 10.dp.toPx()
            val bottomPad = 8.dp.toPx()
            val hintPx = 15.dp.toPx()
            val labelPx = 16.dp.toPx()
            val raisedPx = 7.dp.toPx()
            val cx = w / 2f
            val contentBottom = h - bottomPad
            val contentTop = contentBottom - labelPx - discPx - raisedPx - 3.dp.toPx() - hintPx
            val top = contentTop - padT
            val bottom = (contentBottom + padB).coerceAtMost(h - 2.dp.toPx())
            val left = cx - rowW / 2f - padX
            val right = cx + rowW / 2f + padX
            val stadiumW = right - left
            val stadiumH = (bottom - top).coerceAtLeast(2.dp.toPx())
            val corner = CornerRadius(stadiumH / 2f, stadiumH / 2f)
            val topLeft = Offset(left, top)
            val stadiumSize = Size(stadiumW, stadiumH)
            drawRoundRect(
                color = scheme.surface.copy(alpha = if (glass.dark) 0.92f else 0.95f),
                topLeft = topLeft,
                size = stadiumSize,
                cornerRadius = corner,
            )
            drawRoundRect(
                brush = Brush.verticalGradient(listOf(glass.fillTop, glass.fillBottom)),
                topLeft = topLeft,
                size = stadiumSize,
                cornerRadius = corner,
            )
            drawRoundRect(
                color = glass.border,
                topLeft = topLeft,
                size = stadiumSize,
                cornerRadius = corner,
                style = Stroke(width = 1.dp.toPx()),
            )
            // The glow, kept at the ring's crown.
            val glowR = 5.dp.toPx()
            drawCircle(
                color = Color(0xFFFF5252).copy(alpha = 0.35f),
                radius = glowR * 2.2f,
                center = Offset(cx, top),
            )
            drawCircle(
                color = Color(0xFFFF5252),
                radius = glowR,
                center = Offset(cx, top),
            )
        }
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                hint,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                color = Color.White.copy(alpha = 0.75f),
                maxLines = 1,
            )
            Spacer(Modifier.height(3.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(spacing),
                verticalAlignment = Alignment.Bottom,
            ) {
                QuickMenuOption(
                    label = detailsLabel,
                    icon = 0,
                    selected = selected,
                    disc = disc,
                    raised = false,
                ) {
                    Icon(Icons.Filled.Info, contentDescription = detailsLabel, tint = iconTint(0, selected))
                }
                QuickMenuOption(
                    label = playLabel,
                    icon = 1,
                    selected = selected,
                    disc = disc,
                    raised = true,
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = playLabel, tint = iconTint(1, selected))
                }
                QuickMenuOption(
                    label = libraryLabel,
                    icon = 2,
                    selected = selected,
                    disc = disc,
                    raised = false,
                ) {
                    Icon(Icons.Filled.BookmarkAdd, contentDescription = libraryLabel, tint = iconTint(2, selected))
                }
                QuickMenuOption(
                    label = closeLabel,
                    icon = 3,
                    selected = selected,
                    disc = disc,
                    raised = false,
                ) {
                    Icon(Icons.Filled.Close, contentDescription = closeLabel, tint = iconTint(3, selected))
                }
            }
        }
    }
}

@Composable
private fun iconTint(icon: Int, selected: Int): Color {
    val scheme = MaterialTheme.colorScheme
    return if (icon == selected) scheme.onPrimary else Color.White
}

/** One fan option: a labelled disc, accent-filled with a light ring when
 *  selected, dark glass otherwise. The centre (Play) option rides slightly
 *  higher on the arc. */
@Composable
private fun QuickMenuOption(
    label: String,
    icon: Int,
    selected: Int,
    disc: Dp,
    raised: Boolean,
    content: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val on = icon == selected
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.offset(y = if (raised) (-7).dp else 0.dp),
    ) {
        androidx.compose.foundation.layout.Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(disc)
                .background(
                    if (on) scheme.primary else Color.Black.copy(alpha = 0.55f),
                    CircleShape,
                )
                .then(
                    if (on) Modifier.border(
                        1.5.dp,
                        Color.White.copy(alpha = 0.75f),
                        CircleShape,
                    ) else Modifier
                ),
        ) {
            content()
        }
        Spacer(Modifier.height(2.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
            color = if (on) Color.White else Color.White.copy(alpha = 0.8f),
            fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}
