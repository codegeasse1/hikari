package com.hikari.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity

/**
 * Same rubber-band scroll animation for vertical + horizontal, applied everywhere.
 * Ported from VxMusic wobble (v3): platform overscroll disabled so only ONE
 * overscroll system runs, drag tracked synchronously, spring-back on fling end.
 */
@Composable
fun Modifier.hikariWobble(): Modifier {
    var rawY by remember { mutableFloatStateOf(0f) }
    var rawX by remember { mutableFloatStateOf(0f) }
    val settleY = remember { Animatable(0f) }
    val settleX = remember { Animatable(0f) }
    val connection = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (available.y != 0f) rawY = (rawY + available.y * 0.30f).coerceIn(-36f, 36f)
                if (available.x != 0f) rawX = (rawX + available.x * 0.30f).coerceIn(-36f, 36f)
                return Offset.Zero
            }
            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (rawY != 0f) {
                    settleY.snapTo(rawY); rawY = 0f
                    settleY.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow))
                }
                if (rawX != 0f) {
                    settleX.snapTo(rawX); rawX = 0f
                    settleX.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow))
                }
                return Velocity.Zero
            }
        }
    }
    return this.nestedScroll(connection).graphicsLayer {
        val oy = if (rawY != 0f) rawY else settleY.value
        val ox = if (rawX != 0f) rawX else settleX.value
        translationY = oy
        translationX = ox
        clip = oy != 0f || ox != 0f
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WithoutPlatformOverscroll(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalOverscrollConfiguration provides null, content = content)
}
