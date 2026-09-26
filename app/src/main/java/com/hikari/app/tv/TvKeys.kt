package com.hikari.app.tv

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.focusable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
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
 *  1. **A toggle is not a target.** [androidx.compose.material3.Switch] is
 *     `toggleable`, so it *can* take focus — but it is a small target at the
 *     right-hand end of a full-width row, and Compose's two-dimensional focus
 *     search walks down the page from whatever was focused to the nearest
 *     candidate below it. Navigate down a settings page and the focus lands on
 *     (or skips straight past) the right-hand switches, so "when using the remote
 *     it never lands on the toggle option — it skips to the next one". [tvToggle]
 *     makes the switch answer the D-pad itself (centre/enter presses it,
 *     left/right flips it), so a toggle is usable from wherever the focus
 *     happens to be.
 *  2. **A slider is not a target either.** [androidx.compose.material3.Slider] is
 *     a wide, pointer-oriented control; with a remote there is no drag.
 *     [tvAdjust] gives it the D-pad: left/right step it by one of its own steps,
 *     through the same `onValueChange`/`onValueChangeFinished` pair a drag uses,
 *     so the setting is really saved and not merely redrawn.
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
 *  4. **A `pointerInput` row is not a target at all.** A row that handles its
 *     own taps with a raw pointer gesture — the Home extension picker's
 *     hold-to-multi-select rows, the manga engines — never enters the focus
 *     system, so the D-pad walks past the whole list and a centre press does
 *     nothing. [tvPress] gives such a row the focus target and the press the
 *     gesture never had, without taking the touch gesture away.
 *
 * All four are made to work through the *preview* key pass, which runs from the
 * root down to the focused node — so a handler installed here always sees the
 * key before the component's own keyboard handling can consume it, and there is
 * no chance of a press being handled twice.
 *
 * Keys are read as Compose [Key]s (`event.key`, the platform-independent
 * mapping — see com.hikari.app.ui.screens.MangaReaderScreen, which has done
 * remote navigation this way since long before this file existed) rather than as
 * raw Android key codes: `Key.DirectionCenter` is the D-pad's select button on
 * every remote, whatever its `KEYCODE_DPAD_CENTER` happens to be on the box.
 */

/** Centre/enter (and the numpad's own enter) count as "press" — a remote's
 *  select button arrives as [Key.DirectionCenter], an air-mouse or a keyboard as
 *  [Key.Enter]. */
private fun isPressKey(key: Key): Boolean = when (key) {
    Key.Enter,
    Key.NumPadEnter,
    Key.DirectionCenter -> true
    else -> false
}

/**
 * A toggle that a remote can actually use — see the file comment.
 *
 * Applied to a `Switch`/`Checkbox`/`RadioButton`: the control is focusable (so a
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
            isPressKey(event.key) -> {
                onValueChange(!value)
                true
            }
            event.key == Key.DirectionLeft || event.key == Key.DirectionRight -> {
                onValueChange(!value)
                true
            }
            else -> false
        }
    }

/**
 * A press-only row a remote can actually use — see the file comment.
 *
 * This is the missing third primitive. `clickable` already gives a row a focus
 * target and a centre-press handler, but a row whose tap handling is a raw
 * `pointerInput` ([holdOrTap]'s hold-to-multi-select rows, the manga engines)
 * has NEITHER: a `pointerInput` is invisible to the focus system, so the D-pad
 * walks straight past the row and a centre press does nothing at all. That is
 * exactly the report "unable to select provider button at home … using DPAD
 * remote" — the Home extension picker's rows are all `pointerInput` rows.
 *
 * It is applied ALONGSIDE the `pointerInput`, never instead of it: the pointer
 * still owns the touch gesture (including the hold), and this adds the focus
 * target and the press. The default [Indication] is passed through so a focused
 * row is visibly highlighted on a television, where `focusable()` alone draws
 * nothing and the user cannot see what the remote is on.
 */
@Composable
fun Modifier.tvPress(
    enabled: Boolean = true,
    /**
     * Which key pass answers the press.
     *
     * `true` (the default) is the PREVIEW pass — right for a control that has no
     * focusable children of its own: a picker row, a bare button. It sees the key
     * before anything else can.
     *
     * `false` answers it on the way back UP the normal pass, which is what a ROW
     * THAT CONTAINS ITS OWN CONTROLS needs (an extension row with its Install /
     * Uninstall buttons, a row with a switch). The preview pass runs
     * root-first, so a row-level preview handler would swallow a press aimed at
     * its own button — pressing Uninstall would run the row's action instead.
     * The normal pass runs leaf-first, so the focused child answers first and
     * only a press nothing else claimed reaches the row.
     */
    previewPass: Boolean = true,
    onClick: () -> Unit,
): Modifier {
    val interactions = remember { MutableInteractionSource() }
    val indication = LocalIndication.current
    fun handle(event: androidx.compose.ui.input.key.KeyEvent): Boolean {
        if (!enabled || event.type != KeyEventType.KeyDown) return false
        if (!isPressKey(event.key)) return false
        onClick()
        return true
    }
    // The ring, applied the way `clickable` applies it (that is where every
    // other control in the app gets its focus highlight from — see
    // [TvFocusIndication]): the indication is drawn from the SAME interaction
    // source the focus target reports to, which is why the source is passed to
    // both. Foundation has no single-call `focusable(enabled, source,
    // indication)` in this version, so it is these two modifiers in the order
    // `clickable` itself uses them.
    val press = if (previewPass) {
        Modifier.onPreviewKeyEvent { handle(it) }
    } else {
        Modifier.onKeyEvent { handle(it) }
    }
    return this
        .indication(interactions, indication)
        .focusable(enabled, interactions)
        .then(press)
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
        when (event.key) {
            Key.DirectionLeft -> {
                onAdjust(-1)
                true
            }
            Key.DirectionRight -> {
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
        when (event.key) {
            Key.DirectionUp -> {
                focus.moveFocus(FocusDirection.Up)
                true
            }
            Key.DirectionDown -> {
                focus.moveFocus(FocusDirection.Down)
                true
            }
            Key.DirectionLeft -> if (value.isEmpty()) {
                focus.moveFocus(FocusDirection.Left)
                true
            } else {
                false
            }
            Key.DirectionRight -> if (value.isEmpty()) {
                focus.moveFocus(FocusDirection.Right)
                true
            } else {
                false
            }
            else -> false
        }
    }
}
