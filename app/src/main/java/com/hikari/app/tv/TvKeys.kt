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


/** Centre/enter (and the numpad's own enter) count as "press" — a remote's
 *  select button arrives as [Key.DirectionCenter], an air-mouse or a keyboard as
 *  [Key.Enter]. */
private fun isPressKey(key: Key): Boolean = when (key) {
    Key.Enter,
    Key.NumPadEnter,
    Key.DirectionCenter -> true
    else -> false
}

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

@Composable
fun Modifier.tvPress(
    enabled: Boolean = true,
    previewPass: Boolean = true,
    onHold: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier {
    val tvEnabled = enabled && TvMode.isTv
    val interactions = remember { MutableInteractionSource() }
    val indication = LocalIndication.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val state = remember { HoldState() }
    fun down(): Boolean {
        if (!tvEnabled) return false
        if (onHold == null) {
            val now = android.os.SystemClock.uptimeMillis()
            if (now - state.lastTap < 350L) return true
            state.lastTap = now
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
    var lastTap = 0L
}

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
