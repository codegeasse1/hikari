package com.hikari.app.tv

import android.view.KeyEvent
import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.keyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager

/**
 * The D-pad plumbing every INTERACTIVE control needs on a television.
 *
 * Compose gives a phone user everything for free: a button is tapped where the
 * finger is, and a switch is dragged. A remote is different in three specific,
 * mechanical ways, and each one has cost this app a report:
 *
 *  1. **A toggle is not a target.** [Switch] is `toggleable`, so it *can* take
 *     focus — but it is a small target at the right-hand end of a full-width
 *     row, and Compose's two-dimensional focus search walks down the page from
 *     whatever was focused to the nearest candidate below it. Navigate down a
 *     settings page and the focus lands on (or skips straight past) the
 *     right-hand switches, so "when using the remote it never lands on the
 *     toggle option — it skips to the next one". [tvToggle] makes the switch
 *     answer the D-pad itself (centre/enter presses it, left/right flips it),
 *     so a toggle is usable from wherever the focus happens to be.
 *  2. **A slider is not a target either.** [Slider] is a wide,
 *     pointer-oriented control; with a remote there is no drag. [tvAdjust] gives
 *     it the D-pad: left/right step it by one of its own steps, through the same
 *     `onValueChange`/`onValueChangeFinished` pair a drag uses, so the setting is
 *     really saved and not merely redrawn.
 *  3. **A text field swallows the remote.** Once focus is in a text box the
 *     arrows move the CARET, and a caret that is already at the end of the line
 *     simply does not move: the focus stays in the box no matter which direction
 *     is pressed ("when the remote goes to a place where something has to be
 *     typed — a search bar, a repo URL — pressing the remote does not let go of
 *     the cursor or move anywhere else"). [tvTextFieldKeys] takes up/down out of
 *     the field's hands entirely (a single-line field has nothing to do with
 *     them) and hands them to the focus system, and does the same for left/right
 *     once the field is empty and the caret has nowhere to go.
 *
 * All three are made to work through the *preview* key pass, which runs from the
 * root down to the focused node — so a handler installed here always sees the
 * key before the component's own keyboard handling can consume it, and there is
 * no chance of a press being handled twice.
 */

/** Centre/enter (and space) count as "press"; a remote's select button sends
 *  DPAD_CENTER, an air-mouse/keyboard sends ENTER. */
private fun isPressKey(code: Int): Boolean = when (code) {
    KeyEvent.KEYCODE_DPAD_CENTER,
    KeyEvent.KEYCODE_ENTER,
    KeyEvent.KEYCODE_NUMPAD_ENTER,
    KeyEvent.KEYCODE_SPACE -> true
    else -> false
}

/**
 * A toggle that a remote can actually use — see the file comment.
 *
 * Applied to a [Switch]/[Checkbox]/[RadioButton]: the control is focusable (so a
 * D-pad can land on it at all), centre/enter presses it, and left/right flip it.
 * The press is consumed here so the component's own handler cannot apply it a
 * second time.
 */
fun Modifier.tvToggle(
    value: Boolean,
    enabled: Boolean = true,
    onValueChange: (Boolean) -> Unit,
): Modifier = this
    .focusable(enabled)
    .onPreviewKeyEvent { event ->
        if (!enabled || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when {
            isPressKey(event.keyCode) -> {
                onValueChange(!value)
                true
            }
            event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
                event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> {
                onValueChange(!value)
                true
            }
            else -> false
        }
    }

/**
 * A slider a remote can actually use — see the file comment.
 *
 * [onAdjust] is called with -1 for a left press and +1 for a right press, so the
 * caller decides how much one step is (a slider knows its own `steps`).
 */
fun Modifier.tvAdjust(
    enabled: Boolean = true,
    onAdjust: (Int) -> Unit,
): Modifier = this
    .focusable(enabled)
    .onPreviewKeyEvent { event ->
        if (!enabled || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                onAdjust(-1)
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                onAdjust(1)
                true
            }
            else -> false
        }
    }

/**
 * Lets the remote LEAVE a text field — see the file comment.
 *
 * [value] is the field's own text, which is the only thing needed to know
 * whether an arrow has anywhere to go: while there is text, left/right belong to
 * the caret; on an empty field there is nothing to move the caret over, so they
 * leave the box. Up and down never belong to a single-line field, so they are
 * taken unconditionally and handed to the focus system.
 *
 * Compose's own `moveFocus` is used rather than a hand-rolled "find the next
 * control", so the move follows exactly the same geometry the platform's own
 * D-pad handling does — including inside a scrolling list, which scrolls to
 * follow the new focus.
 */
@Composable
fun Modifier.tvTextFieldKeys(value: String): Modifier {
    val focus = LocalFocusManager.current
    return this.onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                focus.moveFocus(FocusDirection.Up)
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                focus.moveFocus(FocusDirection.Down)
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> if (value.isEmpty()) {
                focus.moveFocus(FocusDirection.Left)
                true
            } else {
                false
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (value.isEmpty()) {
                focus.moveFocus(FocusDirection.Right)
                true
            } else {
                false
            }
            else -> false
        }
    }
}
