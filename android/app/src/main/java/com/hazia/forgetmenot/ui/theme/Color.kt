package com.hazia.forgetmenot.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// The token roles M3's ColorScheme has no slot for, by their SPEC §7 names. Read through
// AppTheme.colors; anything that maps onto a ColorScheme role is read from MaterialTheme.
@Immutable
data class AppColors(
    val textMuted: Color,
    val decorative: Color,
    val onDecorative: Color,
    val border: Color,
    val borderStrong: Color,
    val press: Color,
    val warnBg: Color,
    val warnText: Color,
)

fun Palette.toAppColors() = AppColors(
    textMuted = Color(textMuted),
    decorative = Color(decorative),
    onDecorative = Color(onDecorative),
    border = Color(border),
    borderStrong = Color(borderStrong),
    press = Color(press),
    warnBg = Color(warnBg),
    warnText = Color(warnText),
)

// Every M3 role is pinned to a token, so a stock component never shows a baseline purple or a
// tonal-elevation tint the contrast test has not seen. The pairs M3 draws together are the
// pairs the palettes were solved for: on* over its fill, text and muted over bg and surface.
fun Palette.toColorScheme(mode: Mode): ColorScheme {
    val bg = Color(bg)
    val surface = Color(surface)
    val text = Color(text)
    val muted = Color(textMuted)
    val primary = Color(primary)
    val onPrimary = Color(onPrimary)
    val decorative = Color(decorative)
    val onDecorative = Color(onDecorative)
    val warnBg = Color(warnBg)
    val warnText = Color(warnText)
    val base = if (mode == Mode.DARK) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = decorative,
        onPrimaryContainer = onDecorative,
        inversePrimary = decorative,
        secondary = primary,
        onSecondary = onPrimary,
        secondaryContainer = decorative,
        onSecondaryContainer = onDecorative,
        tertiary = primary,
        onTertiary = onPrimary,
        tertiaryContainer = decorative,
        onTertiaryContainer = onDecorative,
        background = bg,
        onBackground = text,
        surface = surface,
        onSurface = text,
        surfaceVariant = surface,
        onSurfaceVariant = muted,
        // Equal to surface, so tonal elevation blends surface into itself: no tint.
        surfaceTint = surface,
        inverseSurface = text,
        inverseOnSurface = bg,
        // No red in the palettes; the warn pair is the one solved alert colour.
        error = warnText,
        onError = warnBg,
        errorContainer = warnBg,
        onErrorContainer = warnText,
        outline = Color(borderStrong),
        outlineVariant = Color(border),
        surfaceBright = surface,
        surfaceDim = bg,
        surfaceContainerLowest = surface,
        surfaceContainerLow = surface,
        surfaceContainer = surface,
        surfaceContainerHigh = surface,
        surfaceContainerHighest = surface,
    )
}

val LocalAppColors = staticCompositionLocalOf { ThemeTokens.DEFAULT.toAppColors() }
