package com.hazia.forgetmenot.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

private fun all() = Skin.entries.flatMap { s -> Mode.entries.map { m -> Triple(s, m, ThemeTokens.palette(s, m)) } }

private fun eachPalette(check: (String, Mode, Palette) -> Unit) {
    for ((s, m, p) in all()) check("${s.key}/${m.key}", m, p)
}

private fun assertPair(name: String, fg: Int, bg: Int, min: Double) {
    val ratio = contrastRatio(fg, bg)
    assertTrue("$name is %.2f:1, needs %.1f:1".format(ratio, min), ratio >= min)
}

private fun Palette.roles(): Map<String, Int> = mapOf(
    "bg" to bg, "surface" to surface, "text" to text, "textMuted" to textMuted, "primary" to primary,
    "onPrimary" to onPrimary, "decorative" to decorative, "onDecorative" to onDecorative, "border" to border,
    "borderStrong" to borderStrong, "press" to press, "warnBg" to warnBg, "warnText" to warnText,
)

// SPEC §7 "Token roles", as retuned on 2026-10-17: AAA for text and primary actions (phones are
// read outdoors), AA for secondary text, 3:1 for control boundaries.
class ThemeTokensTest {

    @Test fun fivePalettesInTwoModes() {
        assertEquals(10, all().map { it.third }.toSet().size)
    }

    @Test fun textAndPrimaryClear7OnBgAndSurface() = eachPalette { id, _, p ->
        for (g in listOf("bg" to p.bg, "surface" to p.surface)) {
            assertPair("$id text/${g.first}", p.text, g.second, 7.0)
            assertPair("$id primary/${g.first}", p.primary, g.second, 7.0)
        }
    }

    @Test fun mutedClears45OnBgAndSurface() = eachPalette { id, _, p ->
        assertPair("$id textMuted/bg", p.textMuted, p.bg, 4.5)
        assertPair("$id textMuted/surface", p.textMuted, p.surface, 4.5)
    }

    // A label on a fill is read like text, so the fills owe AAA too.
    @Test fun labelsOnFillsClear7() = eachPalette { id, _, p ->
        assertPair("$id onPrimary/primary", p.onPrimary, p.primary, 7.0)
        assertPair("$id onDecorative/decorative", p.onDecorative, p.decorative, 7.0)
        assertPair("$id text/press", p.text, p.press, 7.0)
        assertPair("$id warnText/warnBg", p.warnText, p.warnBg, 7.0)
    }

    // WCAG 1.4.11: the whole boundary of a control; the focus ring is primary.
    @Test fun boundariesClear3() = eachPalette { id, _, p ->
        assertPair("$id borderStrong/bg", p.borderStrong, p.bg, 3.0)
        assertPair("$id borderStrong/surface", p.borderStrong, p.surface, 3.0)
    }

    // Never pure black or white: they vibrate against each other, and #000 smears on OLED.
    @Test fun noPureBlackOrWhite() = eachPalette { id, _, p ->
        for ((role, c) in p.roles()) {
            val rgb = c and 0xFFFFFF
            assertTrue("$id $role is #000000", rgb != 0x000000)
            assertTrue("$id $role is #FFFFFF", rgb != 0xFFFFFF)
        }
    }

    // OLED: the ground sits at 3-6% HSL lightness, tinted, so most pixels are nearly off.
    @Test fun oledGroundIsNearBlack() = eachPalette { id, mode, p ->
        if (mode == Mode.DARK) {
            val l = hsl(p.bg)[2]
            assertTrue("$id bg lightness %.1f%%".format(l * 100), l in 0.03..0.06)
        }
    }

    // Pink and red are fills only: never text, never the primary action, never the warn banner.
    @Test fun noRedOrPinkInk() = eachPalette { id, _, p ->
        for ((role, c) in p.roles().filterKeys { it in setOf("text", "textMuted", "primary", "warnText", "warnBg") }) {
            val (_, chroma, hue) = oklch(c)
            assertFalse("$id $role reads red/pink (h %.0f, C %.3f)".format(hue, chroma), chroma > 0.03 && (hue < 40 || hue > 330))
        }
    }

    // Harmony over every token, the warn pair included: designkit's Matsuda template fit and mean
    // pairwise score over HSL hues, ported below. Celadon and Dusk are held lower by their pinned
    // identity colours (the bisaek glaze; blush against violet ink).
    @Test fun everyTokenSitsOnOneHarmonyTemplate() = eachPalette { id, _, p ->
        val hues = p.roles().values.map { hsl(it)[0] }
        val (template, fit) = Harmony.best(hues)
        val pair = Harmony.score(hues, template)
        assertTrue("$id template fit %.3f ($template)".format(fit), fit >= 0.90)
        assertTrue("$id pairwise harmony %.3f ($template)".format(pair), pair >= 0.79)
    }

    // SPEC §5: widgets draw text, muted and accent on bg.
    @Test fun widgetTokens() = eachPalette { id, _, p ->
        val w = ThemeTokens.widget(p)
        assertEquals(p.bg, w.background)
        assertPair("$id widget text", w.text, w.background, 7.0)
        assertPair("$id widget muted", w.muted, w.background, 4.5)
        assertPair("$id widget accent", w.accent, w.background, 7.0)
    }

    // P6 replaces DEFAULT_WIDGET_THEME in widget/WidgetData.kt with this.
    @Test fun defaultWidgetIsCyanotypeLight() {
        val w = ThemeTokens.widget(ThemeTokens.DEFAULT)
        assertEquals(listOf(0xFFE9F1F7, 0xFF142F44, 0xFF526A7C, 0xFF0A5188).map { it.toInt() },
            listOf(w.background, w.text, w.muted, w.accent))
    }

    // Spot checks against the solved palettes, so a typo in one hex fails loudly.
    @Test fun valuesMatchTheSolvedPalettes() {
        val celadon = ThemeTokens.palette(Skin.GREEN, Mode.LIGHT)
        assertEquals(0xFFA2CDC5.toInt(), celadon.decorative) // bisaek, pinned
        assertEquals(0xFF202726.toInt(), celadon.text)       // sanggam ink, pinned
        assertEquals(0xFFA2CDC5.toInt(), ThemeTokens.palette(Skin.GREEN, Mode.DARK).primary)
        assertEquals(0xFFF4C6D6.toInt(), ThemeTokens.palette(Skin.PINK, Mode.LIGHT).decorative)
        assertEquals(0xFF110A03.toInt(), ThemeTokens.palette(Skin.AMBER, Mode.DARK).bg)
        assertEquals(0xFF5A4388.toInt(), ThemeTokens.palette(Skin.LAVENDER, Mode.LIGHT).primary)
    }

    @Test fun swatchIsPrimaryInActiveMode() {
        assertEquals(0xFF6A3D83.toInt(), ThemeTokens.swatch(Skin.PINK, Mode.LIGHT))
        assertEquals(0xFFB897D1.toInt(), ThemeTokens.swatch(Skin.PINK, Mode.DARK))
    }

    @Test fun names() {
        assertEquals(listOf("blue", "green", "pink", "amber", "lavender"), Skin.entries.map { it.key })
        assertEquals(listOf("Cyanotype", "Celadon", "Dusk", "Apricot", "Provence"), Skin.entries.map { it.displayName })
        assertEquals(listOf("Light", "OLED"), Mode.entries.map { it.displayName })
    }
}

// SPEC §7 "The unrecognised-theme fallback": any unknown key gives the whole default palette.
class ThemeFallbackTest {
    @Test fun knownKeysResolve() {
        assertSame(ThemeTokens.palette(Skin.PINK, Mode.DARK), ThemeTokens.resolve("pink", "dark"))
        assertSame(ThemeTokens.palette(Skin.LAVENDER, Mode.LIGHT), ThemeTokens.resolve("lavender", "light"))
    }

    @Test fun unknownSkinIsWholeDefault() {
        assertSame(ThemeTokens.DEFAULT, ThemeTokens.resolve("violet", "dark"))
    }

    @Test fun unknownModeIsWholeDefault() {
        assertSame(ThemeTokens.DEFAULT, ThemeTokens.resolve("green", "sepia"))
    }

    @Test fun missingKeysAreDefault() {
        assertSame(ThemeTokens.DEFAULT, ThemeTokens.resolve(null, null))
        assertSame(ThemeTokens.DEFAULT, ThemeTokens.resolve("", "light"))
    }

    // The stored keys stay "light" and "dark"; "OLED" is only the display name.
    @Test fun oledIsStoredAsDark() {
        assertSame(ThemeTokens.palette(Skin.BLUE, Mode.DARK), ThemeTokens.resolve("blue", "dark"))
        assertSame(ThemeTokens.DEFAULT, ThemeTokens.resolve("blue", "oled"))
    }

    @Test fun keysAreCaseSensitive() {
        assertSame(ThemeTokens.DEFAULT, ThemeTokens.resolve("Pink", "dark"))
    }

    @Test fun defaultIsBlueLight() {
        assertSame(ThemeTokens.palette(Skin.BLUE, Mode.LIGHT), ThemeTokens.DEFAULT)
    }
}

// SPEC §7 "System bars": both bars are transparent over bg, so their icons follow bg.
class SystemBarsTest {
    @Test fun lightModesGetDarkIcons() {
        for (s in Skin.entries) {
            val p = ThemeTokens.palette(s, Mode.LIGHT)
            assertTrue(s.key, ThemeTokens.statusBarDarkIcons(p))
            assertTrue(s.key, ThemeTokens.navigationBarDarkIcons(p))
        }
    }

    @Test fun oledModesGetLightIcons() {
        for (s in Skin.entries) {
            val p = ThemeTokens.palette(s, Mode.DARK)
            assertFalse(s.key, ThemeTokens.statusBarDarkIcons(p))
            assertFalse(s.key, ThemeTokens.navigationBarDarkIcons(p))
        }
    }

    // isLightColor() in www/js/lib/text.js: WCAG relative luminance, threshold 0.4.
    @Test fun lightnessThreshold() {
        assertTrue(isLightColor(0xFFFAFAFA.toInt()))
        assertFalse(isLightColor(0xFF1E2B33.toInt()))
        assertFalse(isLightColor(0xFF888888.toInt())) // luminance 0.246
        assertTrue(isLightColor(0xFFB0B0B0.toInt()))  // luminance 0.434
    }

    @Test fun contrastOfBlackOnWhiteIs21() {
        assertEquals(21.0, contrastRatio(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), 0.01)
    }
}

// P6 points Glance widgets at ThemeTokens; it must not drag Compose UI types along.
class ThemeTokensIsComposeFreeTest {
    @Test fun noComposeTypesInTheTokenApi() {
        val classes = listOf(ThemeTokens::class.java, Palette::class.java, WidgetTokens::class.java, Skin::class.java, Mode::class.java)
        for (c in classes) {
            for (f in c.declaredFields) assertFalse("${c.simpleName}.${f.name}", f.type.name.startsWith("androidx.compose"))
            for (m in c.declaredMethods) {
                assertFalse("${c.simpleName}.${m.name}", m.returnType.name.startsWith("androidx.compose"))
                for (p in m.parameterTypes) assertFalse("${c.simpleName}.${m.name}", p.name.startsWith("androidx.compose"))
            }
        }
    }
}

// --- colour helpers for the tests (HSL as designkit reads it; OKLCH for the red/pink guard) ---

private fun channels(c: Int) = Triple((c shr 16 and 0xFF) / 255.0, (c shr 8 and 0xFF) / 255.0, (c and 0xFF) / 255.0)

// [hue degrees, saturation, lightness], achromatic hue 0, as designkit.colorspace.rgb_to_hsl.
private fun hsl(c: Int): DoubleArray {
    val (r, g, b) = channels(c)
    val mx = max(r, max(g, b)); val mn = min(r, min(g, b)); val l = (mx + mn) / 2
    if (mx == mn) return doubleArrayOf(0.0, 0.0, l)
    val d = mx - mn
    val s = if (l > 0.5) d / (2 - mx - mn) else d / (mx + mn)
    val h = when (mx) {
        r -> (g - b) / d + (if (g < b) 6 else 0)
        g -> (b - r) / d + 2
        else -> (r - g) / d + 4
    } * 60
    return doubleArrayOf(h, s, l)
}

private fun oklch(c: Int): Triple<Double, Double, Double> {
    fun lin(v: Double) = if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    val (r0, g0, b0) = channels(c); val r = lin(r0); val g = lin(g0); val b = lin(b0)
    val l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
    val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
    val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
    val bigL = 0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s
    val a = 1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s
    val bb = 0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s
    return Triple(bigL, hypot(a, bb), (Math.toDegrees(atan2(bb, a)) + 360) % 360)
}

// designkit/harmony.py, ported: Matsuda templates, 30 degree linear decay, 10 degree free zone.
private object Harmony {
    private const val DECAY = 30.0
    private val templates = linkedMapOf(
        "monochromatic" to listOf(0.0),
        "analogous" to listOf(-30.0, 0.0, 30.0),
        "complementary" to listOf(0.0, 180.0),
        "split-complementary" to listOf(0.0, 150.0, 210.0),
        "triadic" to listOf(0.0, 120.0, 240.0),
        "tetradic" to listOf(0.0, 90.0, 180.0, 270.0),
        "rectangle" to listOf(0.0, 60.0, 180.0, 240.0),
        "near-complementary" to listOf(0.0, 157.5),
    )

    private fun dist(a: Double, b: Double): Double { val d = abs(((a % 360) + 360) % 360 - ((b % 360) + 360) % 360); return min(d, 360 - d) }

    private fun pairOffsets(t: String): List<Double> {
        val offs = templates.getValue(t)
        val out = mutableListOf<Double>()
        for (i in offs.indices) for (j in offs.indices) if (i != j) {
            val d = dist(offs[i], offs[j]); if (out.none { abs(it - d) < 1e-3 }) out += d
        }
        if (out.isEmpty()) out += 0.0
        if (out.none { it == 0.0 }) out += 0.0
        return out
    }

    fun score(hues: List<Double>, t: String): Double {
        val valid = pairOffsets(t)
        val scores = mutableListOf<Double>()
        for (i in hues.indices) for (j in i + 1 until hues.size) {
            val sep = dist(hues[i], hues[j])
            scores += max(0.0, 1.0 - valid.minOf { abs(sep - it) } / DECAY)
        }
        return scores.average()
    }

    private fun fit(hues: List<Double>, t: String): Double {
        val offs = templates.getValue(t)
        var best = -1.0
        for (base in hues.map { ((it % 360) + 360) % 360 }.distinct().sorted()) {
            val positions = offs.map { ((base + it) % 360 + 360) % 360 }
            val avg = hues.sumOf { h -> max(0.0, 1.0 - max(0.0, positions.minOf { dist(h, it) } - 10.0) / DECAY) } / hues.size
            val used = positions.count { p -> hues.any { dist(it, p) <= DECAY } }
            best = max(best, avg * used / positions.size)
        }
        return best
    }

    // (template, fit) for the closest template; a longer one that fits equally well wins.
    fun best(hues: List<Double>): Pair<String, Double> =
        templates.keys.map { t -> val f = fit(hues, t); Triple(f + 0.01 * templates.getValue(t).size, t, f) }
            .maxBy { it.first }.let { it.second to it.third }
}
