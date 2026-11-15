package com.cbkres.visavole.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Dates cross the persistence, dialog and display boundaries as ISO text. Every form of that
 * rendering — from a date, from epoch millis — must produce the identical padded string, or a
 * date round-tripped through one path stops matching another.
 */
class IsoDateTextTest {

    @Test
    fun aDateRendersAsPaddedIsoText() {
        assertEquals("2027-06-30", LocalDate.of(2027, 6, 30).toUtcIsoDate())
        assertEquals("0099-01-05", LocalDate.of(99, 1, 5).toUtcIsoDate())
    }

    @Test
    fun epochMillisRenderTheirUtcDate() {
        val midnight = LocalDate.of(2027, 6, 30).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        assertEquals("2027-06-30", midnight.toUtcIsoDate())
        assertEquals("2027-06-29", (midnight - 1).toUtcIsoDate())
        assertEquals("2027-06-30", (midnight + 23L * 3_600_000).toUtcIsoDate())
    }

    @Test
    fun everyFormOfTheIsoTextAgrees() {
        val date = LocalDate.of(2027, 6, 30)
        val millis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        assertEquals(date.toString(), date.toUtcIsoDate())
        assertEquals(date.toUtcIsoDate(), millis.toUtcIsoDate())
    }
}
