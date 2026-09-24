package com.hikari.app.tv

import android.view.KeyEvent
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
 * WHERE THE RING IS DRAWN IS THE WHOLE TRICK, and it was wrong before: the app's
 * cards are almost all `Modifier.clip(shape).background(...).clickable(...)`, so
 * the indication node sits INSIDE a clip. A ring drawn on the component's outer
 * edge — and worse, one scaled up to 1.06× so it "lifts" out of the row — is
 * therefore outside that clip and painted away entirely, which is exactly the
 * reported "the remote moves to another button but nothing shows where it is,
 * it is invisible". The ring is now drawn INSET from the component's own bounds
 * by rather more than half its stroke, and the content is not scaled at all, so
 * no clip anywhere in the app can remove it.
 *
 * Three things say "this is the focused one", because a television is watched
 * from four metres: the focused card is tinted with the accent colour, a soft
 * wide halo sits under the ring (which is what makes it survive busy poster
 * artwork), and a crisp ring is drawn on top. Pressing Enter brightens the tint,
 * so a press gives the same "give" a tap does.
 *
 * Installed whenever the app is in its television layout OR the last thing the
 * user touched was a remote/keyboard ([TvInput]) — a hub that reports itself as a
 * phone gets the ring the moment its remote is used — and never on a touch
 * device, where the ripple is exactly as it was.
 */
internal class TvFocusIndication(
    private val ring: Color,
    private val cornerRadius: Dp = 20.dp,
    private val ringWidth: Dp = 4.dp,
) : IndicationNodeFactory {

    override fun create(interactionSource: InteractionSource): DelegatableNode =
        TvFocusNode(interactionSource, ring, cornerRadius, ringWidth)

    // IndicationNodeFactory instances are compared by value by Compose (a
    // changing instance would rebuild the node on every recomposition), so the
    // data-class contract has to be honoured by hand.
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
        drawContent()

        // Everything below is drawn INSIDE the component's own bounds, so a
        // clip anywhere up the modifier chain cannot swallow it (see the class
        // comment) — and it is drawn after the content, so it is never hidden
        // behind a poster.
        val stroke = ringWidth.toPx()
        val inset = minOf(stroke * 1.6f, size.minDimension * 0.14f)
        val inner = Size(
            (size.width - inset * 2f).coerceAtLeast(1f),
            (size.height - inset * 2f).coerceAtLeast(1f),
        )
        val spot = Offset(inset, inset)
        val radius = CornerRadius(cornerRadius.toPx().coerceAtMost(inner.minDimension / 2f))

        // 1. The card itself takes the accent colour, faintly — a whole focused
        //    poster changing shade is visible from the sofa even before the ring
        //    is.
        drawRoundRect(
            color = ring.copy(alpha = if (pressed) 0.26f else 0.13f),
            topLeft = spot,
            size = inner,
            cornerRadius = radius,
        )
        // 2. A wide, soft halo: the reason the ring survives busy artwork.
        drawRoundRect(
            color = ring.copy(alpha = 0.34f),
            topLeft = spot,
            size = inner,
            cornerRadius = radius,
            style = Stroke(width = stroke * 2.4f),
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

/**
 * Which input the user is actually using.
 *
 * A television box that reports itself as a phone is a real thing (see
 * [TvMode]), and on one of those the focus ring used to be switched off with
 * the television layout — so the remote moved a cursor nobody could see. Any
 * D-pad / Enter / media key now turns the ring on for the whole app, and a
 * touch turns it back off on a device that is not a television.
 *
 * [MainActivity] feeds this from `dispatchKeyEvent`/`dispatchTouchEvent`: the
 * earliest point in the pipeline, before any composable has seen the event, and
 * framework-level, so it needs no Compose API that may or may not exist in the
 * Compose version this app is built against.
 */
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

    /** A finger on the screen: back to the platform ripple, on a non-TV device. */
    fun noteTouch() {
        if (!remoteActive.value) return
        if (!TvMode.isTv) remoteActive.value = false
    }

    /**
     * Should the focus ring be installed right now? Television layout, or a
     * remote/keyboard in use — and it is a Compose read, so the app re-draws the
     * instant either becomes true.
     */
    @Composable
    fun ringWanted(): Boolean = TvMode.current() || remoteActive.value
}

/**
 * Installs the television focus ring for everything below it — when this is a
 * television, and also on a device that turns out to be driven by a remote (see
 * [TvInput]). On a phone with a finger, this is a no-op wrapper: the platform's
 * own ripple indication passes straight through, so nothing about the touch
 * interface changes.
 */
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
