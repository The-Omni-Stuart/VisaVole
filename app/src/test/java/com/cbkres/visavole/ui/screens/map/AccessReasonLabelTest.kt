package com.cbkres.visavole.ui.screens.map

import com.cbkres.visavole.domain.AccessReason
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The detail card decides what to show under the level from the reason's type, never from its
 * wording: rephrasing a label must not resurrect or silence a line of subtext.
 */
class AccessReasonLabelTest {

    @Test
    fun noReasonAndThePassportBaselineShowNothing() {
        assertEquals("", accessReasonLabel(null))
        assertEquals("", accessReasonLabel(AccessReason.PassportBaseline))
    }

    @Test
    fun homeCountryReadsAsItsOwnLine() {
        assertEquals("Your country", accessReasonLabel(AccessReason.HomeCountry))
    }

    @Test
    fun aGrantShowsItsLabel() {
        assertEquals(
            "Schengen Residence (visa-free)",
            accessReasonLabel(AccessReason.Grant("Schengen Residence (visa-free)")),
        )
    }
}
