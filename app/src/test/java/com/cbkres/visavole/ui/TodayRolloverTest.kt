package com.cbkres.visavole.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** The cheap guard behind the ON_RESUME hook: only an actually-rolled UTC date republishes. */
class TodayRolloverTest {

    @Test fun resumeKeepsStateWhenTheDateIsUnchanged() {
        assertFalse(shouldRepublish(LocalDate.of(2026, 11, 13), LocalDate.of(2026, 11, 13)))
    }

    @Test fun resumeRepublishesWhenTheDateRolledOver() {
        assertTrue(shouldRepublish(LocalDate.of(2026, 11, 13), LocalDate.of(2026, 11, 14)))
    }
}
