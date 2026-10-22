package com.hazia.forgetmenot.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

// SPEC §7 "Size tiers". A control's drawn size follows its function, in three tiers, not one
// minimum for everything. The values are the knee of the Pareto front over density,
// legibility, glance and harmony (scored 2026-10-17): one 1.25 step between tiers, every
// label fitting its tier at 130% font scale. The hit area is a separate decision.
@Immutable
data class Sizes(
    // T1, the actions a screen exists for: the add button, Log today, the home rows' Log,
    // Save, sheet Save, the log-date picks, Message / Plan something / Share, Settings rows
    // and text fields.
    val primary: Dp = 50.dp,
    // T2, choices: theme and mode buttons, chips and filter pills, the inline date field.
    val choice: Dp = 40.dp,
    // T3, supporting controls: icon buttons, inline text buttons (Clear, How?, Restore), the
    // switch, search clear, avatar remove.
    val support: Dp = 32.dp,

    // Hit areas. Every target clears WCAG 2.5.8's 24dp; small controls with room reach 48dp;
    // inside a card row, and for inline text buttons, 40dp, a row's height, so they never
    // spill into the next row.
    val minHit: Dp = 24.dp,
    val hit: Dp = 48.dp,
    val rowHit: Dp = 40.dp,
    // Chip rows keep this gap so neighbouring hit areas do not overlap.
    val chipGap: Dp = 12.dp,
)

val LocalSizes = staticCompositionLocalOf { Sizes() }

// 12/14/16dp corners, soft and consistent; pills use CircleShape.
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(16.dp),
)

// Compose expands a pointer target smaller than ViewConfiguration.minimumTouchTargetSize to
// that size without moving or repainting anything, and only where no sibling is hit directly:
// the native form of app.css's centred ::before (app.css:506-521). The theme leaves it at 48dp.
// Inside a card row or around an inline text button, wrap the controls in this to cap the
// expansion at the row's 40dp so it never reaches the next row.
@Composable
fun RowHitArea(content: @Composable () -> Unit) {
    val base = LocalViewConfiguration.current
    val rowHit = LocalSizes.current.rowHit
    val capped = object : ViewConfiguration by base {
        override val minimumTouchTargetSize: DpSize = DpSize(base.minimumTouchTargetSize.width, rowHit)
    }
    CompositionLocalProvider(LocalViewConfiguration provides capped, content = content)
}
