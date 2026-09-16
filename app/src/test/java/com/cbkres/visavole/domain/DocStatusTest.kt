package com.cbkres.visavole.domain

import com.cbkres.visavole.data.Country
import com.cbkres.visavole.data.WorldData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class DocStatusTest {

    private val today = LocalDate.of(2026, 9, 16)

    private fun world() = WorldData(
        mapOf(
            "FR" to Country("FR", "France"),
            "DE" to Country("DE", "Germany"),
            "CA" to Country("CA", "Canada"),
        ),
        emptyMap(),
        emptyList(),
        emptyMap(),
        emptyMap(),
        emptyList(),
    )

    private fun passport(
        id: String,
        iso: String,
        expiry: String? = null,
        validFrom: String? = null,
    ) = Document(id, "Passport · $iso", DocKind.Passport(iso), null, expiry, validFrom)

    private fun visa(
        id: String,
        countries: Set<String>,
        entry: String? = "multiple",
        expiry: String? = null,
        validFrom: String? = null,
        supersededBy: String? = null,
    ) = Document(
        id,
        "Visa: ${countries.joinToString(",")}",
        DocKind.Custom(countries, null, "visa", null, entry),
        null,
        expiry,
        validFrom,
        null,
        supersededBy,
    )

    private fun stop(
        id: String,
        iso: String,
        arrival: LocalDate,
        departure: LocalDate?,
        docId: String? = null,
    ) = TripStop(id, iso, arrival, departure, docId)

    private fun status(doc: Document, docs: List<Document> = listOf(doc), trips: List<Trip> = emptyList()): DocStatus =
        DocStatus.of(doc, docs, trips, world(), today)

    // ------------------------------------------------------------------
    // DATE
    // ------------------------------------------------------------------

    @Test fun pastDateExpiresDocument() {
        val s = status(visa("v1", setOf("CA"), expiry = "2026-01-01"))
        assertTrue(s.expired)
        assertEquals(ExpiryReason.DATE, s.reason)
        assertEquals(LocalDate.of(2026, 1, 1), s.effectiveExpiry)
    }

    @Test fun expiryOnTodayIsStillValid() {
        val s = status(visa("v1", setOf("CA"), expiry = "2026-09-16"))
        assertFalse(s.expired)
        assertEquals(ExpiryReason.DATE, s.reason)
        assertEquals(today, s.effectiveExpiry)
    }

    @Test fun futureDateIsNotExpired() {
        val s = status(visa("v1", setOf("CA"), expiry = "2027-01-01"))
        assertFalse(s.expired)
        assertEquals(ExpiryReason.DATE, s.reason)
        assertEquals(LocalDate.of(2027, 1, 1), s.effectiveExpiry)
    }

    @Test fun passportHasNoEntryStatusAndExpiresByDateOnly() {
        val s = status(passport("p1", "FR", expiry = "2026-01-01"))
        assertTrue(s.expired)
        assertEquals(ExpiryReason.DATE, s.reason)
        assertNull(s.entries)
    }

    @Test fun malformedExpiryDateIsIgnored() {
        val s = status(visa("v1", setOf("CA"), expiry = "not-a-date"))
        assertFalse(s.expired)
        assertNull(s.reason)
        assertNull(s.effectiveExpiry)
    }

    // ------------------------------------------------------------------
    // Unconstrained
    // ------------------------------------------------------------------

    @Test fun unconstrainedDocumentNeverExpires() {
        val s = status(visa("v1", setOf("CA")))
        assertFalse(s.expired)
        assertNull(s.reason)
        assertNull(s.effectiveExpiry)
    }

    // ------------------------------------------------------------------
    // ENTRIES
    // ------------------------------------------------------------------

    @Test fun exhaustedSingleEntryVisaExpiresByEntries() {
        val v1 = visa("v1", setOf("CA"), entry = "single")
        val t = Trip("t1", listOf(stop("s1", "CA", LocalDate.of(2026, 8, 5), LocalDate.of(2026, 8, 10), "v1")))
        val s = DocStatus.of(v1, listOf(v1), listOf(t), world(), today)
        assertTrue(s.expired)
        assertEquals(ExpiryReason.ENTRIES, s.reason)
        assertEquals(LocalDate.of(2026, 8, 10), s.effectiveExpiry)
        assertNotNull(s.entries)
        assertEquals(1, s.entries!!.total)
        assertEquals(1, s.entries.used)
    }

    @Test fun entryExhaustionBeatsLaterDate() {
        val v1 = visa("v1", setOf("CA"), entry = "single", expiry = "2027-01-01")
        val t = Trip("t1", listOf(stop("s1", "CA", LocalDate.of(2026, 8, 5), LocalDate.of(2026, 8, 10), "v1")))
        val s = DocStatus.of(v1, listOf(v1), listOf(t), world(), today)
        assertTrue(s.expired)
        assertEquals(ExpiryReason.ENTRIES, s.reason)
        assertEquals(LocalDate.of(2026, 8, 10), s.effectiveExpiry)
    }

    @Test fun partiallyUsedVisaExpiresByDate() {
        // One of two entries used; an entry remains, so the visa's own date governs.
        val v1 = visa("v1", setOf("CA"), entry = "double", expiry = "2026-08-15")
        val t = Trip("t1", listOf(stop("s1", "CA", LocalDate.of(2026, 8, 5), LocalDate.of(2026, 8, 10), "v1")))
        val s = DocStatus.of(v1, listOf(v1), listOf(t), world(), today)
        assertTrue(s.expired)
        assertEquals(ExpiryReason.DATE, s.reason)
        assertEquals(LocalDate.of(2026, 8, 15), s.effectiveExpiry)
    }

    @Test fun unusedSingleEntryVisaIsNotExpired() {
        val v1 = visa("v1", setOf("CA"), entry = "single")
        val s = status(v1)
        assertFalse(s.expired)
        assertNull(s.reason)
        assertNotNull(s.entries)
        assertEquals(1, s.entries!!.total)
        assertEquals(0, s.entries.used)
    }

    // ------------------------------------------------------------------
    // SUPERSEDED
    // ------------------------------------------------------------------

    @Test fun supersededByFutureValidFromIsNotYetExpired() {
        val v1 = visa("v1", setOf("CA"), expiry = "2028-01-01", supersededBy = "v2")
        val v2 = visa("v2", setOf("CA"), expiry = "2029-01-01", validFrom = "2026-10-01")
        val s = DocStatus.of(v1, listOf(v1, v2), emptyList(), world(), today)
        assertFalse(s.expired)
        assertEquals(ExpiryReason.SUPERSEDED, s.reason)
        assertEquals(LocalDate.of(2026, 10, 1), s.effectiveExpiry)
    }

    @Test fun supersededByPastValidFromIsExpired() {
        val v1 = visa("v1", setOf("CA"), expiry = "2028-01-01", supersededBy = "v2")
        val v2 = visa("v2", setOf("CA"), expiry = "2029-01-01", validFrom = "2026-01-01")
        val s = DocStatus.of(v1, listOf(v1, v2), emptyList(), world(), today)
        assertTrue(s.expired)
        assertEquals(ExpiryReason.SUPERSEDED, s.reason)
        assertEquals(LocalDate.of(2026, 1, 1), s.effectiveExpiry)
    }

    @Test fun ownDateBeatsLaterSupersession() {
        val v1 = visa("v1", setOf("CA"), expiry = "2026-05-01", supersededBy = "v2")
        val v2 = visa("v2", setOf("CA"), expiry = "2029-01-01", validFrom = "2026-10-01")
        val s = DocStatus.of(v1, listOf(v1, v2), emptyList(), world(), today)
        assertTrue(s.expired)
        assertEquals(ExpiryReason.DATE, s.reason)
        assertEquals(LocalDate.of(2026, 5, 1), s.effectiveExpiry)
    }

    @Test fun supersessionBeatsLaterDate() {
        val v1 = visa("v1", setOf("CA"), expiry = "2028-01-01", supersededBy = "v2")
        val v2 = visa("v2", setOf("CA"), expiry = "2029-01-01", validFrom = "2026-10-01")
        val s = DocStatus.of(v1, listOf(v1, v2), emptyList(), world(), today)
        assertFalse(s.expired)
        assertEquals(ExpiryReason.SUPERSEDED, s.reason)
        assertEquals(LocalDate.of(2026, 10, 1), s.effectiveExpiry)
    }

    @Test fun danglingSupersededByPointerIsIgnored() {
        val v1 = visa("v1", setOf("CA"), expiry = "2028-01-01", supersededBy = "ghost")
        val s = status(v1)
        assertFalse(s.expired)
        assertEquals(ExpiryReason.DATE, s.reason)
    }

    @Test fun replacementWithoutValidFromGivesNoSupersession() {
        val v1 = visa("v1", setOf("CA"), expiry = "2028-01-01", supersededBy = "v2")
        val v2 = visa("v2", setOf("CA"), expiry = "2029-01-01")
        val s = DocStatus.of(v1, listOf(v1, v2), emptyList(), world(), today)
        assertFalse(s.expired)
        assertEquals(ExpiryReason.DATE, s.reason)
        assertEquals(LocalDate.of(2028, 1, 1), s.effectiveExpiry)
    }

    // ------------------------------------------------------------------
    // all()
    // ------------------------------------------------------------------

    @Test fun allReturnsEveryDocument() {
        val v1 = visa("v1", setOf("CA"), expiry = "2026-01-01")
        val v2 = visa("v2", setOf("CA"), entry = "single")
        val map = DocStatus.all(listOf(v1, v2), emptyList(), world(), today)
        assertEquals(setOf("v1", "v2"), map.keys)
        assertTrue(map.getValue("v1").expired)
        assertFalse(map.getValue("v2").expired)
    }
}
