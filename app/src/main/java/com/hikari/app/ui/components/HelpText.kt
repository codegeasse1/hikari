package com.hikari.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf

val LocalHideHelp = compositionLocalOf { false }

/** True when an explanation line should be drawn — the readable form of
 *  `!LocalHideHelp.current`, used at every caption site. */
@Composable
fun helpShown(): Boolean = !LocalHideHelp.current
