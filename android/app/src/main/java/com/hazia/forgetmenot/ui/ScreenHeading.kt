package com.hazia.forgetmenot.ui

import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics

/**
 * Marks a screen's level-1 heading and moves focus to it when the screen comes up (SPEC §7,
 * goTo() in www/js/ui/nav.js). Without it focus stays on a control of the screen just left, and
 * a keyboard or TalkBack user has nowhere sensible to continue from. Going back to a screen
 * counts as coming up: Navigation disposes a screen's composition when it leaves the back stack
 * top, so the effect runs again on return.
 *
 * Use it on exactly one element per screen; section titles inside a screen keep a plain
 * `semantics { heading() }`. Sheets do their own focus (SheetCore.kt).
 */
@Composable
fun Modifier.screenHeading(): Modifier {
    val requester = remember { FocusRequester() }
    // runCatching: requestFocus throws if the node is not attached yet (e.g. a screen
    // replaced in the same frame); a missed focus move is not worth a crash.
    LaunchedEffect(requester) { runCatching { requester.requestFocus() } }
    return this.semantics { heading() }.focusRequester(requester).focusable()
}
