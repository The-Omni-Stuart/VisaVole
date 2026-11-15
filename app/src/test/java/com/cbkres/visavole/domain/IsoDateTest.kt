package com.cbkres.visavole.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * Documents and rules carry dates as text from user input and from the bundled database, so an
 * unreadable value must read as "no date" everywhere rather than throwing at whoever asks.
 */
class IsoDateTest {

    @Test
    fun anIsoDateReadsAsItself() {
        assertEquals(LocalDate.of(2027, 6, 30), "2027-06-30".toDateOrNull())
    }

    @Test
    fun missingAndUnreadableDatesReadAsAbsent() {
        assertNull(null.toDateOrNull())
        assertNull("".toDateOrNull())
        assertNull("not a date".toDateOrNull())
        assertNull("2027-13-01".toDateOrNull())
        // Unpadded is not ISO_LOCAL_DATE: lenient means unreadable-is-absent, not forgiving formats.
        assertNull("2027-6-3".toDateOrNull())
    }
}
