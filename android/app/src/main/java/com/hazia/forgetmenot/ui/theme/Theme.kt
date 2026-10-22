package com.hazia.forgetmenot.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.core.view.WindowCompat

// True when the user turned animations off (Settings > Accessibility > Remove animations, which
// sets the animator duration scale to 0): the native prefers-reduced-motion. SPEC §7: collapse
// every animation, keep end states (the pull-to-refresh spinner still shows, unrotated).
val LocalReducedMotion = staticCompositionLocalOf { false }

fun reducedMotion(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

object AppTheme {
    val colors: AppColors @Composable @ReadOnlyComposable get() = LocalAppColors.current
    val sizes: Sizes @Composable @ReadOnlyComposable get() = LocalSizes.current
    val reducedMotion: Boolean @Composable @ReadOnlyComposable get() = LocalReducedMotion.current
}

/**
 * The app's theme. [skin] and [mode] are the settings values as stored; the app's own mode,
 * not the system's, picks light or dark, and anything unrecognised is the whole of blue/light
 * (SPEC §7). Defaults are the first-paint pair.
 */
@Composable
fun ForgetmenotTheme(
    skin: String? = Skin.BLUE.key,
    mode: String? = Mode.LIGHT.key,
    content: @Composable () -> Unit,
) {
    val (resolvedSkin, resolvedMode) = ThemeTokens.resolveKeys(skin, mode)
    val palette = ThemeTokens.palette(resolvedSkin, resolvedMode)
    val scheme = remember(palette) { palette.toColorScheme(resolvedMode) }
    val appColors = remember(palette) { palette.toAppColors() }
    val context = LocalContext.current
    val inPreview = LocalInspectionMode.current
    val reduced = remember(context) { !inPreview && reducedMotion(context) }

    SystemBars(palette)

    CompositionLocalProvider(
        LocalAppColors provides appColors,
        LocalSizes provides Sizes(),
        LocalReducedMotion provides reduced,
        // No flat 48dp: M3 pads small components' layout up to this, growing what is drawn.
        // Drawn sizes come from LocalSizes; the 48dp hit area stays, via ViewConfiguration.
        LocalMinimumInteractiveComponentSize provides Dp.Unspecified,
    ) {
        MaterialTheme(colorScheme = scheme, typography = AppTypography, shapes = AppShapes, content = content)
    }
}

// SPEC §7 "System bars": both bars are transparent over bg (enableEdgeToEdge in MainActivity),
// so their icons follow bg, and the system's contrast scrims are off so nothing tints them.
// This is the only place native chrome follows the in-app theme; re-applied on every change.
@Composable
private fun SystemBars(palette: Palette) {
    val view = LocalView.current
    if (view.isInEditMode) return
    SideEffect {
        val window = view.context.findActivity()?.window ?: return@SideEffect
        window.isStatusBarContrastEnforced = false
        window.isNavigationBarContrastEnforced = false
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = ThemeTokens.statusBarDarkIcons(palette)
            isAppearanceLightNavigationBars = ThemeTokens.navigationBarDarkIcons(palette)
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * The window under edge-to-edge: bg everywhere, behind both bars. Content starts below the
 * status bar and any cutout and clears the side insets (cutouts, the 3-button bar in
 * landscape); those insets are consumed here, so a Scaffold inside does not pad them twice.
 * The bottom is left to each screen, so lists scroll under the gesture bar: pad the end with
 * [bottomInsetPadding], or a lazy list's contentPadding with [bottomInsets].
 */
@Composable
fun AppFrame(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
    ) { content() }
}

// The navigation bar and, when it is up, the keyboard: what a screen's last element must clear.
val bottomInsets: WindowInsets
    @Composable get() = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)

fun Modifier.bottomInsetPadding(): Modifier = composed { windowInsetsPadding(bottomInsets) }
