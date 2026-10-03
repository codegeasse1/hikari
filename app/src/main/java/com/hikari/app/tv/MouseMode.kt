package com.hikari.app.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.hikari.app.i18n.tr
import kotlin.math.roundToInt

object MouseMode {
  var enabled by mutableStateOf(false)
  var xFrac by mutableStateOf(0.5f)
  var yFrac by mutableStateOf(0.5f)

  fun toggle() {
    enabled = !enabled
    if (enabled) { xFrac = 0.5f; yFrac = 0.5f }
  }

  fun move(dx: Float, dy: Float) {
    xFrac = (xFrac + dx).coerceIn(0.02f, 0.98f)
    yFrac = (yFrac + dy).coerceIn(0.05f, 0.95f)
  }
}

@Composable
fun TvMouseCursorOverlay(modifier: Modifier = Modifier) {
  if (!MouseMode.enabled) return
  val density = LocalDensity.current
  val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
  Box(
    modifier
      .fillMaxSize()
      .onPreviewKeyEvent { event ->
        if (!MouseMode.enabled) return@onPreviewKeyEvent false
        val step = 0.035f
        when (event.key) {
          Key.DirectionUp -> { MouseMode.move(0f, -step); focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Up); true }
          Key.DirectionDown -> { MouseMode.move(0f, step); focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Down); true }
          Key.DirectionLeft -> { MouseMode.move(-step, 0f); focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Left); true }
          Key.DirectionRight -> { MouseMode.move(step, 0f); focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Right); true }
          else -> false
        }
      },
  ) {
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
      val px = with(density) { (maxWidth * MouseMode.xFrac).toPx() }
      val py = with(density) { (maxHeight * MouseMode.yFrac).toPx() }
      Box(
        Modifier
          .offset { IntOffset((px - with(density) { 17.dp.toPx() }).roundToInt(), (py - with(density) { 17.dp.toPx() }).roundToInt()) }
          .size(34.dp)
          .clip(CircleShape)
          .background(Color.Transparent)
          .border(2.5.dp, MaterialTheme.colorScheme.primary, CircleShape),
        contentAlignment = Alignment.Center,
      ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
      }
      Surface(
        tonalElevation = 2.dp,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 86.dp),
      ) {
        Text(
          tr("Mouse on — arrows move the cursor, OK clicks"),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
      }
    }
  }
}
