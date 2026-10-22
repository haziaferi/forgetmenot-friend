package com.hazia.forgetmenot.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// SPEC §7 "Size tiers": three drawn sizes by function, chosen from the Pareto front of density,
// legibility, glance and harmony (the knee, 50/40/32). The hit area is a separate decision.
class SizesTest {
    private val s = Sizes()

    @Test fun threeTiers() {
        assertEquals(50.dp, s.primary)
        assertEquals(40.dp, s.choice)
        assertEquals(32.dp, s.support)
    }

    // One geometric step, 1.25, both ways: the tiers read as three sizes at a glance.
    @Test fun tiersStepByOneRatio() {
        assertEquals(1.25f, s.primary / s.choice, 0.001f)
        assertEquals(1.25f, s.choice / s.support, 0.001f)
    }

    // No flat 48dp floor: choices and supporting controls are drawn smaller than the actions.
    @Test fun noFlatMinimum() {
        assertTrue(s.choice < 48.dp && s.support < 48.dp)
    }

    @Test fun hitAreas() {
        assertEquals(24.dp, s.minHit)   // WCAG 2.5.8
        assertEquals(48.dp, s.hit)      // where there is room
        assertEquals(40.dp, s.rowHit)   // inline text buttons and card-row icon buttons
        assertEquals(12.dp, s.chipGap)  // keeps neighbouring chip hit areas apart
        assertTrue(s.support >= s.minHit)
    }

    // Each tier's label fits its control at 130% font scale, with the padding the scorer used.
    @Test fun labelsFitAtLargeFontScale() {
        fun fits(style: TextStyle, tier: Float, pad: Float) =
            style.lineHeight.value * 1.3f + 2 * pad <= tier
        assertTrue(fits(AppTypography.labelLarge, s.primary.value, 8f))
        assertTrue(fits(AppTypography.labelMedium, s.choice.value, 6f))
        assertTrue(fits(AppTypography.labelSmall, s.support.value, 4f))
    }

    @Test fun typeScaleIsFourSteps() {
        assertEquals(listOf(13, 16, 20, 25).map { it.sp }, TypeScale.steps)
        val styles = AppTypography.let {
            listOf(it.displayLarge, it.displayMedium, it.displaySmall, it.headlineLarge, it.headlineMedium,
                it.headlineSmall, it.titleLarge, it.titleMedium, it.titleSmall, it.bodyLarge, it.bodyMedium,
                it.bodySmall, it.labelLarge, it.labelMedium, it.labelSmall)
        }
        for (st in styles) {
            assertTrue("${st.fontSize} is off the scale", st.fontSize in TypeScale.steps)
            // Line heights land on the 4dp grid, so rows and baselines share one rhythm.
            assertEquals("${st.fontSize} line height ${st.lineHeight}", 0f, st.lineHeight.value % 4f, 0.001f)
        }
    }
}
