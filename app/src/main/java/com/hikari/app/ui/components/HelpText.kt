package com.hikari.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf

/**
 * Whether the app's small grey EXPLANATION lines are hidden right now —
 * Settings → App Layout → "Explanations" (see [com.hikari.app.data.AppStore.hideHelpFlow]).
 *
 * WHAT COUNTS AS AN EXPLANATION. The caption under a settings row, the sentence
 * under a folder's name, the paragraph at the bottom of a card that says what a
 * switch does, a sheet's or a dialog's note. Everything that TELLS the user
 * something *about* the thing in front of them, as opposed to the thing itself
 * (the title, the control, its value) — which is what a user who knows the app
 * already wants gone, and what used to mean scrolling past a paragraph to reach
 * the next switch.
 *
 * WHY A COMPOSITION LOCAL. The switch has to reach every screen at once and
 * there is no single "explanation" component to change: these lines are drawn by
 * a dozen different rows, cards and dialogs. The value is provided once, at the
 * top of the app (see MainActivity), and each caption asks for it where it is
 * drawn — so a screen added later gets it for free by reading [helpShown], and
 * nothing has to be threaded through a composable's parameters.
 *
 * Default false (draw the explanations), which is also what a screen previewed
 * outside the app's own composition gets.
 */
val LocalHideHelp = compositionLocalOf { false }

/** True when an explanation line should be drawn — the readable form of
 *  `!LocalHideHelp.current`, used at every caption site. */
@Composable
fun helpShown(): Boolean = !LocalHideHelp.current
