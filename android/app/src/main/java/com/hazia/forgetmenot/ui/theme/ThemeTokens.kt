package com.hazia.forgetmenot.ui.theme

import kotlin.math.pow

// The ten palettes as plain ARGB ints, with no Compose types, so the Glance widgets (P6) and
// anything outside a composition can read the same values the app draws with. Retuned on
// 2026-10-17 (SPEC §7): each skin keeps its settings key; its colours were solved
// lightness-only to the floors ThemeTokensTest checks, with every role's hue placed on one
// harmony template (designkit's Matsuda score over all 13 tokens, the warn pair included).
// Light mode never uses #FFFFFF; OLED mode sits at 3-6% lightness and never uses #000000.

// Keys are the settings values (SPEC §2); display names are what the theme picker shows.
enum class Skin(val key: String, val displayName: String) {
    BLUE("blue", "Cyanotype"),        // print-paper ground, Prussian ink
    GREEN("green", "Celadon"),        // Goryeo bisaek glaze #A2CDC5 and sanggam ink #202726, pinned
    PINK("pink", "Dusk"),             // blush fills only; dusk-violet ink
    AMBER("amber", "Apricot"),        // sunlit plaster, apricot fills, cinnamon ink
    LAVENDER("lavender", "Provence"), // lavender ink, olive fills
}

// The stored key stays "dark", so settings and backups keep working; OLED is what it now is.
enum class Mode(val key: String, val displayName: String) { LIGHT("light", "Light"), DARK("dark", "OLED") }

data class Palette(
    val bg: Int,
    val surface: Int,
    val text: Int,
    val textMuted: Int,
    val primary: Int,
    val onPrimary: Int,
    val decorative: Int,
    val onDecorative: Int,
    val border: Int,
    val borderStrong: Int,
    val press: Int,
    // The sync-conflict banner: straw to citron in every palette, never red, tuned per palette
    // so it sits on the same harmony template as the rest.
    val warnBg: Int,
    val warnText: Int,
)

// What a widget card needs: it sits on bg, where text and accent clear 7:1 and muted 4.5:1
// (SPEC §5 "Colours and backgrounds").
data class WidgetTokens(val background: Int, val text: Int, val muted: Int, val accent: Int)

object ThemeTokens {
    private fun c(rgb: Int): Int = rgb or 0xFF000000.toInt()

    private fun p(
        bg: Int, surface: Int, text: Int, textMuted: Int, primary: Int, onPrimary: Int, decorative: Int,
        onDecorative: Int, border: Int, borderStrong: Int, press: Int, warnBg: Int, warnText: Int,
    ) = Palette(c(bg), c(surface), c(text), c(textMuted), c(primary), c(onPrimary), c(decorative),
        c(onDecorative), c(border), c(borderStrong), c(press), c(warnBg), c(warnText))

    // Columns: bg, surface, text, textMuted, primary, onPrimary, decorative, onDecorative,
    // border, borderStrong, press, warnBg, warnText.
    private val palettes: Map<Pair<Skin, Mode>, Palette> = mapOf(
        (Skin.BLUE to Mode.LIGHT) to p(0xE9F1F7, 0xF4F9FD, 0x142F44, 0x526A7C, 0x0A5188, 0xF4F9FD, 0xB0D7F1, 0x142F44, 0xAFC2D1, 0x778997, 0xC3DAEC, 0xEEE5C0, 0x544816),
        (Skin.BLUE to Mode.DARK) to p(0x000D14, 0x051925, 0xCFDEE6, 0x758A95, 0x66ACD2, 0x000D14, 0xA4D2EB, 0x000D14, 0x2C3E48, 0x566A75, 0x1C323E, 0x2A2005, 0xBDAC7F),
        (Skin.GREEN to Mode.LIGHT) to p(0xEFF4EE, 0xF7FBF8, 0x202726, 0x5E6C61, 0x165A4D, 0xF7FBF8, 0xA2CDC5, 0x202726, 0xB8C5BC, 0x7F8C83, 0xCCDDD1, 0xE2EEC2, 0x455014),
        (Skin.GREEN to Mode.DARK) to p(0x060D08, 0x0F1A12, 0xD7DED5, 0x7C8B7E, 0xA2CDC5, 0x060D08, 0xDCEAE7, 0x060D08, 0x363E33, 0x616A5E, 0x253329, 0x1F250A, 0xA8B487),
        (Skin.PINK to Mode.LIGHT) to p(0xF9EFF4, 0xFEF8FB, 0x3A283A, 0x746274, 0x6A3D83, 0xFEF8FB, 0xF4C6D6, 0x3A283A, 0xD2BBC6, 0x98828D, 0xEAD0DD, 0xFFE2CB, 0x6D3F15),
        (Skin.PINK to Mode.DARK) to p(0x0E0813, 0x0F1725, 0xDFDAE3, 0x8D8395, 0xB897D1, 0x0E0813, 0xE9AFC4, 0x0E0813, 0x413848, 0x6D6374, 0x352B3D, 0x22230B, 0xAEB188),
        (Skin.AMBER to Mode.LIGHT) to p(0xFBF0EA, 0xFEF8F5, 0x3E2A20, 0x78645A, 0x74442C, 0xFEF8F5, 0xF8C4AA, 0x3E2A20, 0xD3BDB2, 0x998479, 0xDED9BF, 0xF3E9B7, 0x574A04),
        (Skin.AMBER to Mode.DARK) to p(0x110A03, 0x1F150B, 0xE6DACF, 0x948474, 0xC89B6E, 0x110A03, 0xEFC9A4, 0x110A03, 0x47392B, 0x746455, 0x3C2C1C, 0x242306, 0xB1B182),
        (Skin.LAVENDER to Mode.LIGHT) to p(0xF3F0F9, 0xFAF8FE, 0x322A41, 0x6B647A, 0x5A4388, 0xFAF8FE, 0xD3D1A0, 0x322A41, 0xC4BDD3, 0x8B8599, 0xDBD3EC, 0xEDEBB9, 0x514C00),
        (Skin.LAVENDER to Mode.DARK) to p(0x0D0914, 0x191423, 0xDEDAE4, 0x8A8397, 0xAF99D5, 0x0D0914, 0xCFCE99, 0x0D0914, 0x3E3849, 0x6A6476, 0x322B3E, 0x242306, 0xB1B182),
    )

    fun palette(skin: Skin, mode: Mode): Palette = palettes.getValue(skin to mode)

    // Cyanotype light: the defaults (SPEC §7 "The theme matrix") and the fallback.
    val DEFAULT: Palette = palette(Skin.BLUE, Mode.LIGHT)

    // SPEC §7 "The unrecognised-theme fallback": a hand-edited settings file or a backup from a
    // newer build can name a skin or mode this build lacks. Either unknown gives the whole of
    // blue/light, never a mix. Matched exactly, as the CSS attribute selectors were.
    fun resolveKeys(skin: String?, mode: String?): Pair<Skin, Mode> {
        val s = Skin.entries.firstOrNull { it.key == skin }
        val m = Mode.entries.firstOrNull { it.key == mode }
        return if (s == null || m == null) Skin.BLUE to Mode.LIGHT else s to m
    }

    fun resolve(skin: String?, mode: String?): Palette =
        resolveKeys(skin, mode).let { (s, m) -> palette(s, m) }

    fun widget(p: Palette): WidgetTokens = WidgetTokens(p.bg, p.text, p.textMuted, p.primary)

    // The theme picker's dot: each skin's primary in the active mode, which clears 7:1 on that
    // mode's backgrounds.
    fun swatch(skin: Skin, mode: Mode): Int = palette(skin, mode).primary

    // SPEC §7 "System bars": both bars are transparent and show bg through them, so their icons
    // follow bg. Light modes get dark icons, OLED modes light ones.
    fun statusBarDarkIcons(p: Palette): Boolean = isLightColor(p.bg)

    fun navigationBarDarkIcons(p: Palette): Boolean = isLightColor(p.bg)
}

// WCAG 2.x relative luminance of an ARGB colour (alpha ignored).
fun relativeLuminance(argb: Int): Double {
    fun chan(shift: Int): Double {
        val v = ((argb shr shift) and 0xFF) / 255.0
        return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * chan(16) + 0.7152 * chan(8) + 0.0722 * chan(0)
}

fun contrastRatio(a: Int, b: Int): Double {
    val la = relativeLuminance(a)
    val lb = relativeLuminance(b)
    return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
}

// isLightColor() in www/js/lib/text.js: WCAG relative luminance above 0.4.
fun isLightColor(argb: Int): Boolean = relativeLuminance(argb) > 0.4
