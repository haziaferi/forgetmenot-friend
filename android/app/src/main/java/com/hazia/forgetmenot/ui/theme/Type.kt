package com.hazia.forgetmenot.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.hazia.forgetmenot.R

// Self-hosted (SIL OFL; licences in assets/licenses): no network, no downloadable-fonts
// provider (I7). Chosen 2026-10-17 by designkit's typography search (pairing fitness 0.81,
// palette harmony 1.0): Fredoka, a rounded display face, over Nunito Sans, a calm humanist
// body. Both are variable fonts; one file each, one Font per weight in use.
@OptIn(ExperimentalTextApi::class)
val Fredoka = FontFamily(
    Font(R.font.fredoka_variable, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
)

@OptIn(ExperimentalTextApi::class)
val NunitoSans = FontFamily(
    Font(R.font.nunito_sans_variable, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.nunito_sans_variable, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
)

// Four sizes on a 1.25 ratio from a 16sp body, the same step as the size tiers, so type and
// controls scale together. Phone-sized: the web search's 1.333 ratio reached 70px, which no
// screen here needs. In sp, so the OS font scale applies; nothing has a fixed height.
object TypeScale {
    val sm = 13.sp
    val md = 16.sp
    val lg = 20.sp
    val xl = 25.sp
    val steps: List<TextUnit> = listOf(sm, md, lg, xl)
}

// Line heights sit on the 4dp grid. Fredoka headings at 600 (it ships no 800); slight negative
// tracking on display sizes, none on body.
private fun display(size: TextUnit, lineHeight: TextUnit) = TextStyle(
    fontFamily = Fredoka, fontWeight = FontWeight.SemiBold, fontSize = size, lineHeight = lineHeight,
    letterSpacing = (-0.01).em,
)

private fun body(size: TextUnit, lineHeight: TextUnit, weight: FontWeight = FontWeight.Normal) =
    TextStyle(fontFamily = NunitoSans, fontWeight = weight, fontSize = size, lineHeight = lineHeight)

val AppTypography = Typography(
    displayLarge = display(TypeScale.xl, 32.sp),
    displayMedium = display(TypeScale.xl, 32.sp),
    displaySmall = display(TypeScale.xl, 32.sp),
    headlineLarge = display(TypeScale.xl, 32.sp),
    headlineMedium = display(TypeScale.xl, 32.sp),
    headlineSmall = display(TypeScale.lg, 28.sp),
    titleLarge = display(TypeScale.xl, 32.sp),                 // wordmark, detail name
    titleMedium = display(TypeScale.lg, 28.sp),                // screen and sheet titles
    titleSmall = body(TypeScale.md, 24.sp, FontWeight.Bold),   // a home row's name
    bodyLarge = body(TypeScale.md, 24.sp),
    bodyMedium = body(TypeScale.md, 24.sp),
    bodySmall = body(TypeScale.sm, 20.sp),                     // secondary lines, captions
    labelLarge = body(TypeScale.md, 24.sp, FontWeight.Bold),   // T1: Log, Save, picks
    labelMedium = body(TypeScale.sm, 20.sp, FontWeight.Bold),  // T2: chips, theme and mode buttons
    labelSmall = body(TypeScale.sm, 16.sp, FontWeight.Bold),   // T3: Clear, How?, Restore
)
