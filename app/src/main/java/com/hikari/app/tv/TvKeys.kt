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
import kotlinx.coroutines.launch

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
 *     gesture never had, without taking the touch gesture away. The same
 *     primitive is what makes a plain ROW of a list a target — an extension row
 *     with its Install button, a website row, an installed extension with its
 *     switch: without a focus node the remote's highlight could only ever appear
 *     on the small button at the far end of the line, never on the row itself.
 *     A row that CONTAINS controls must pass `previewPass = false`: the preview
 *     pass runs root-first, so a row-level preview handler would swallow a press
 *     aimed at the row's own button (pressing Uninstall would run the row's
 *     action instead) — the normal pass runs leaf-first, which is the rule a row
 *     wants.
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
 *
 * One press is one activation: held-key auto-repeat is swallowed, so keeping
 * OK held down flips the toggle once instead of machine-gunning it (the same
 * repeat class that drove [tvPress] into a focus jump — see its note).
 */
fun Modifier.tvToggle(
    value: Boolean,
    enabled: Boolean = true,
    onValueChange: (Boolean) -> Unit,
): Modifier = this
    .focusable(enabled && TvMode.isTv)
    .onPreviewKeyEvent { event ->
        if (!enabled || !TvMode.isTv || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        // A held key re-fires Down with a non-zero repeat count: the toggle
        // answers the first press only, or one hold flips it a dozen times.
        if (event.nativeKeyEvent.repeatCount != 0) return@onPreviewKeyEvent true
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
 *
 * One press is one activation, guaranteed here rather than at the 60+ call
 * sites. A held OK key re-fires Down with a non-zero repeat count, and the old
 * code answered EVERY one: the first Down opened a repo and moved the focus,
 * so the repeats landed on whatever the new screen had focused (its back
 * button, the Home rail) — one tap walked the user two screens deep, "very
 * fast". Repeats are now swallowed (consumed, never fired).
 *
 * Deliberately stateless: the repeat count comes from the key event itself,
 * so there is no claimed-flag that a navigation between Down and Up could
 * strand (a stranded flag would eat the NEXT press's Down and make the
 * control look dead until an Up wandered back). Same-node `clickable` +
 * `tvPress` pairs stay single-fire in practice: the Down is consumed here,
 * so a click handler waiting for the matching Up never completes a second
 * activation.
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
    /**
     * Fired when OK is HELD ~0.5s instead of tapped (see [HOLD_MS] for why half
     * a second). The Home extension picker's hold-to-multi-select never existed
     * for remotes: the press fired on KeyDown, so a hold was just a tap and TV
     * users could not multi-pick at all. Null (the default) keeps the old
     * tap-only behaviour everywhere else.
     */
    onHold: (() -> Unit)? = null,
    /**
     * The tap handler. Deliberately LAST: a trailing lambda (`.tvPress { … }`)
     * binds to the final function parameter, so tap-only call sites written
     * before [onHold] existed keep meaning "on click".
     */
    onClick: () -> Unit,
): Modifier {
    val tvEnabled = enabled && TvMode.isTv
    val interactions = remember { MutableInteractionSource() }
    val indication = LocalIndication.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val state = remember { HoldState() }
    fun down(): Boolean {
        // Held-key auto-repeat re-fires Down with a non-zero repeat count: one
        // physical press must fire once, or the repeats land on whatever the
        // first activation focused (see the KDoc above). Consumed, never fired.
        if (!tvEnabled) return false
        if (onHold == null) {
            onClick()
            return true
        }
        // Key repeat while held: the first Down owns the gesture.
        if (state.down) return true
        state.down = true
        state.held = false
        state.job = scope.launch {
            kotlinx.coroutines.delay(com.hikari.app.ui.screens.HOLD_MS)
            state.held = true
            onHold()
        }
        return true
    }
    fun up(): Boolean {
        if (!enabled || onHold == null || !state.down) return false
        state.down = false
        state.job?.cancel()
        state.job = null
        // A hold already fired: swallow the release so it never ALSO taps.
        if (state.held) {
            state.held = false
            return true
        }
        onClick()
        return true
    }
    fun handle(event: androidx.compose.ui.input.key.KeyEvent): Boolean {
        if (!tvEnabled) return false
        if (!isPressKey(event.key)) return false
        // Held-key auto-repeat (see down()'s note): swallow repeats here so
        // neither the tap nor the hold path ever sees them.
        if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount != 0) return true
        return when (event.type) {
            KeyEventType.KeyDown -> down()
            KeyEventType.KeyUp -> up()
            else -> false
        }
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
        .focusable(tvEnabled, interactions)
        .then(press)
}

private class HoldState {
    var down = false
    var held = false
    var job: kotlinx.coroutines.Job? = null
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
    .focusable(enabled && TvMode.isTv)
    .onPreviewKeyEvent { event ->
        if (!enabled || !TvMode.isTv || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
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
