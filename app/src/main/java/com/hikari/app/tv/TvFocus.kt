package com.hikari.app.tv

import androidx.compose.foundation.IndicationNodeFactory
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * The focus ring every clickable in the app wears on a television.
 *
 * On a phone, "what is under my finger" is answered by the finger — a ripple
 * under the touch. On a television there is no finger: the remote moves a
 * *focus* around the screen, and if nothing draws that focus the app is simply
 * unusable, because the user cannot see which button Enter is about to press.
 * Compose gives every clickable/toggleable/selectable component a focus state
 * already (that is what lets the D-pad walk the UI), and it hands the visual
 * feedback out through `LocalIndication` — the same seam the ripple uses. This
 * indication is installed there, once, in
 * [com.hikari.app.MainActivity]'s content: from then on *every* clickable in the
 * app — poster cards, settings rows, glass cards, dialog buttons, the rail —
 * draws a ring around itself when the remote lands on it, without a single call
 * site having to know that televisions exist.
 *
 * It is drawn as the component's own indication node, so it follows the
 * component's real bounds (padding, shape and all) exactly like the ripple did,
 * and the focused item is drawn slightly *larger* than its neighbours, so it
 * reads as lifted out of the row — the standard 10-foot pattern, and the reason
 * a focused poster is unmistakable from the sofa.
 *
 * Installed only on a television ([TvFocusProvider]), so phone behaviour —
 * ripples, exactly as they were — is untouched.
 */
internal class TvFocusIndication(
    private val ring: Color,
    private val cornerRadius: Dp = 18.dp,
    private val ringWidth: Dp = 3.dp,
    private val zoom: Float = 1.06f,
) : IndicationNodeFactory {

    override fun create(interactionSource: InteractionSource): DelegatableNode =
        TvFocusNode(interactionSource, ring, cornerRadius, ringWidth, zoom)

    // IndicationNodeFactory instances are compared by value by Compose (a
    // changing instance would rebuild the node on every recomposition), so the
    // data-class contract has to be honoured by hand.
    override fun equals(other: Any?): Boolean =
        other is TvFocusIndication &&
            other.ring == ring &&
            other.cornerRadius == cornerRadius &&
            other.ringWidth == ringWidth &&
            other.zoom == zoom

    override fun hashCode(): Int {
        var result = ring.hashCode()
        result = 31 * result + cornerRadius.hashCode()
        result = 31 * result + ringWidth.hashCode()
        result = 31 * result + zoom.hashCode()
        return result
    }
}

private class TvFocusNode(
    private val source: InteractionSource,
    private val ring: Color,
    private val cornerRadius: Dp,
    private val ringWidth: Dp,
    private val zoom: Float,
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
                    // A television can be driven with a mouse or an air-mouse
                    // (and with the editor's own pointer), where a hover is the
                    // same thing as a focus: it is what the user is aiming at.
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
        // A press on the focused item shrinks the zoom a touch, so Enter has the
        // same "give" a tap does.
        val factor = if (pressed) zoom - 0.025f else zoom
        scale(factor, factor) {
            // `this@draw` is the ContentDrawScope: the block below runs with a
            // plain DrawScope receiver, which has no drawContent() of its own.
            this@draw.drawContent()
            val radius = CornerRadius(cornerRadius.toPx())
            val stroke = ringWidth.toPx()
            // A soft halo first, then the crisp ring on top of it. The halo is
            // what makes the ring survive busy artwork behind it — a thin line
            // alone disappears over a poster.
            drawRoundRect(
                color = ring.copy(alpha = 0.30f),
                cornerRadius = radius,
                style = Stroke(width = stroke * 2.6f),
            )
            drawRoundRect(
                color = ring,
                cornerRadius = radius,
                style = Stroke(width = stroke),
            )
            if (pressed) {
                drawRoundRect(
                    color = ring.copy(alpha = 0.16f),
                    cornerRadius = radius,
                )
            }
        }
    }
}

/**
 * Installs the television focus ring for everything below it — but only when
 * this really is a television. On a phone this is a no-op wrapper: the platform's
 * own ripple indication passes straight through, so nothing about the touch
 * interface changes.
 */
@Composable
fun TvFocusProvider(ring: Color, content: @Composable () -> Unit) {
    if (!TvMode.current()) {
        content()
        return
    }
    val indication = remember(ring) { TvFocusIndication(ring) }
    CompositionLocalProvider(
        LocalIndication provides indication,
        content = content,
    )
}
