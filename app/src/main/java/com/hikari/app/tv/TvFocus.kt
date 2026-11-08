package com.hikari.app.tv

import android.view.KeyEvent
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

internal class TvFocusIndication(
    private val ring: Color,
    private val cornerRadius: Dp = 20.dp,
    // Thin by design: a 4dp band plus halo read as a thick yellow frame that
    // sat outside the card bounds. 1.5dp hugs the edge like the reference.
    private val ringWidth: Dp = 1.5.dp,
) : IndicationNodeFactory {

    override fun create(interactionSource: InteractionSource): DelegatableNode =
        TvFocusNode(interactionSource, ring, cornerRadius, ringWidth)

    override fun equals(other: Any?): Boolean =
        other is TvFocusIndication &&
            other.ring == ring &&
            other.cornerRadius == cornerRadius &&
            other.ringWidth == ringWidth

    override fun hashCode(): Int {
        var result = ring.hashCode()
        result = 31 * result + cornerRadius.hashCode()
        result = 31 * result + ringWidth.hashCode()
        return result
    }
}

private class TvFocusNode(
    private val source: InteractionSource,
    private val ring: Color,
    private val cornerRadius: Dp,
    private val ringWidth: Dp,
) : Modifier.Node(), DrawModifierNode {

    private var focused by mutableStateOf(false)
    private var pressed by mutableStateOf(false)

    override fun onAttach() {
        coroutineScope.launch {
            source.interactions.collect { interaction ->
                when (interaction) {
                    is FocusInteraction.Focus -> focused = true
                    is FocusInteraction.Unfocus -> focused = false
                    is PressInteraction.Press -> pressed = true
                    is PressInteraction.Release -> pressed = false
                    is PressInteraction.Cancel -> pressed = false
                    is HoverInteraction.Enter -> focused = true
                    is HoverInteraction.Exit -> focused = false
                    else -> Unit
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        // Unfocused: draw the component exactly as it always drew itself (the
        // ripple used to add nothing here either).
        if (!focused) {
            drawContent()
            return
        }
        drawContent()

        val minSide = size.minDimension
        // Nothing sensible can be ringed in a few pixels (a divider, a spacer):
        // drawing anyway would paint a smear over the whole element.
        if (minSide < 6f) return
        val stroke = minOf(ringWidth.toPx(), minSide * 0.10f)
        val inset = minOf(stroke * 1.0f, minSide * 0.10f)
        val inner = Size(
            (size.width - inset * 2f).coerceAtLeast(1f),
            (size.height - inset * 2f).coerceAtLeast(1f),
        )
        val spot = Offset(inset, inset)
        val radius = CornerRadius(cornerRadius.toPx().coerceAtMost(inner.minDimension / 2f))

        drawRoundRect(
            color = ring.copy(alpha = if (pressed) 0.15f else 0.07f),
            topLeft = spot,
            size = inner,
            cornerRadius = radius,
        )
        // 2. A narrow, soft halo: the reason the ring survives busy artwork.
        drawRoundRect(
            color = ring.copy(alpha = 0.15f),
            topLeft = spot,
            size = inner,
            cornerRadius = radius,
            style = Stroke(width = stroke * 1.5f),
        )
        // 3. The crisp ring.
        drawRoundRect(
            color = ring,
            topLeft = spot,
            size = inner,
            cornerRadius = radius,
            style = Stroke(width = stroke),
        )
    }
}

@Composable
fun Modifier.tvFocusRing(
    corner: Dp = 12.dp,
    // Thin like the indication above: 2dp stacked next to a component's own
    // border read as a double frame (the Stremio-chip photo).
    width: Dp = 1.dp,
    color: Color = Cs3.accent,
): Modifier {
    if (!TvMode.isTv) return this
    var focused by remember { mutableStateOf(false) }
    return this
        .onFocusChanged { focused = it.isFocused }
        .border(
            width = width,
            color = if (focused) color else Color.Transparent,
            shape = RoundedCornerShape(corner),
        )
}

object TvInput {

    /** True once a remote/keyboard key has been seen (until a touch follows). */
    private val remoteActive = mutableStateOf(false)

    private val remoteKeys = setOf(
        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER,
        KeyEvent.KEYCODE_SPACE,
        KeyEvent.KEYCODE_TAB,
        KeyEvent.KEYCODE_MENU,
        KeyEvent.KEYCODE_MEDIA_PLAY,
        KeyEvent.KEYCODE_MEDIA_PAUSE,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_MEDIA_STOP,
        KeyEvent.KEYCODE_MEDIA_NEXT,
        KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
        KeyEvent.KEYCODE_MEDIA_REWIND,
    )

    /** Note a key from the framework. Cheap: only a real key-down does anything. */
    fun noteKey(event: KeyEvent) {
        if (event.action != KeyEvent.ACTION_DOWN) return
        if (event.keyCode in remoteKeys) remoteActive.value = true
    }

    /** Plain read of the remote-driven state for View-based screens (the
     *  player), which cannot use the composable [ringWanted]. */
    fun isRemoteActiveNow(): Boolean = remoteActive.value

    /** A finger on the screen: back to the platform ripple, on a non-TV device. */
    fun noteTouch() {
        if (!remoteActive.value) return
        if (!TvMode.isTv) remoteActive.value = false
    }

    @Composable
    fun ringWanted(): Boolean = TvMode.current() || remoteActive.value
}

@Composable
fun TvFocusProvider(ring: Color, content: @Composable () -> Unit) {
    if (!TvInput.ringWanted()) {
        content()
        return
    }
    val indication = remember(ring) { TvFocusIndication(ring) }
    CompositionLocalProvider(
        LocalIndication provides indication,
        content = content,
    )
}
