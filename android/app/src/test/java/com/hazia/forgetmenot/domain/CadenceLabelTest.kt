package com.hazia.forgetmenot.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/** cadenceLabel(), shared by add and detail: presets by name, then rounded days, months, years. */
class CadenceLabelTest {
    @Test fun presetsKeepTheirNames() {
        assertEquals("1 week", cadenceLabel(7))
        assertEquals("3 months", cadenceLabel(90))
        assertEquals("1 year", cadenceLabel(365))
    }

    @Test fun theRestRoundsLikeMathRound() {
        assertEquals("1 day", cadenceLabel(1))
        assertEquals("10 days", cadenceLabel(10))
        assertEquals("2 months", cadenceLabel(45)) // Math.round(1.5) goes up
        assertEquals("2 months", cadenceLabel(60))
        assertEquals("4 months", cadenceLabel(120))
        assertEquals("12 months", cadenceLabel(364))
        assertEquals("2 years", cadenceLabel(700))
        assertEquals("2 years", cadenceLabel(730))
    }

    @Test fun oneIsSingular() {
        assertEquals("1 month", cadenceLabel(31))
        assertEquals("1 year", cadenceLabel(400)) // the JS said "1 years"
    }
}
