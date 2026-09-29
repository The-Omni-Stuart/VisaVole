package com.cbkres.visavole.domain

import com.cbkres.visavole.data.Country
import com.cbkres.visavole.data.Holding
import com.cbkres.visavole.data.Regime
import com.cbkres.visavole.data.StayRule
import com.cbkres.visavole.data.WorldData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class GuardTest {

    private val today = LocalDate.of(2026, 9, 16)

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private fun world(
        countries: Map<String, Country> = mapOf(
            "FR" to Country("FR", "France"),
            "DE" to Country("DE", "Germany"),
            "CA" to Country("CA", "Canada"),
        ),
        holdings: Map<String, Holding> = mapOf(
            "ca-pr" to Holding("ca-pr", "Canadian PR", "residency", "CA"),
        ),
    ) = WorldData(countries, emptyMap(), emptyList(), holdings, emptyMap(), emptyList())

    private fun ctx(
        docs: List<Document> = emptyList(),
        trips: List<Trip> = emptyList(),
        w: WorldData = world(),
        primaryId: String? = null,
    ) = GuardContext(docs, trips, w, today, primaryId)

    private fun passport(
        id: String,
        iso: String,
        expiry: String? = null,
        validFrom: String? = null,
    ) = Document(id, "Passport · $iso", DocKind.Passport(iso), expiry, validFrom)

    private fun visa(
        id: String,
        countries: Set<String>,
        entry: String? = "multiple",
        expiry: String? = null,
        validFrom: String? = null,
    ) = Document(
        id,
        "Visa: ${countries.joinToString(",")}",
        DocKind.Custom(countries, null, "visa", null, entry),
        expiry,
        validFrom,
    )

    private fun residence(
        id: String,
        countries: Set<String>,
        expiry: String? = null,
        validFrom: String? = null,
    ) = Document(
        id,
        "Residence: ${countries.joinToString(",")}",
        DocKind.Custom(countries, null, "residence", null, null),
        expiry,
        validFrom,
    )

    private fun stop(
        id: String,
        iso: String,
        arrival: LocalDate,
        departure: LocalDate?,
        docId: String? = null,
    ) = TripStop(id, iso, arrival, departure, docId)

    private fun trip(id: String, vararg stops: TripStop) = Trip(id, stops.toList())

    private fun add(c: Document, existing: List<Document> = emptyList(), trips: List<Trip> = emptyList()): GuardResult =
        GuardEngine.evaluate(GuardAction.AddDocument(c), ctx(docs = existing, trips = trips))

    private fun update(candidate: Document, original: Document, trips: List<Trip> = emptyList()): GuardResult =
        GuardEngine.evaluate(GuardAction.UpdateDocument(candidate, original), ctx(docs = listOf(original), trips = trips))

    private fun codes(r: GuardResult) = r.findings.map { it.code }

    // ------------------------------------------------------------------
    // doc.duplicate
    // ------------------------------------------------------------------

    @Test fun addExactDuplicateIsBlocked() {
        val p1 = passport("p1", "FR", "2028-01-01", "2018-01-01")
        val dup = passport("p2", "FR", "2028-01-01", "2018-01-01")
        val r = add(dup, listOf(p1))
        assertFalse(r.canProceed)
        assertTrue("codes=${codes(r)}", codes(r).contains("doc.duplicate"))
        assertEquals(GuardSeverity.BLOCK, r.findings.first { it.code == "doc.duplicate" }.severity)
        assertEquals("Document already exists", r.findings.first { it.code == "doc.duplicate" }.title)
    }

    @Test fun sameCountryDifferentDatesIsNotDuplicate() {
        val p1 = passport("p1", "FR", "123", "2028-01-01")
        val p2 = passport("p2", "FR", "123", "2029-01-01")
        val r = add(p2, listOf(p1))
        assertTrue(r.canProceed)
        assertFalse(codes(r).contains("doc.duplicate"))
    }

    @Test fun invertedValidityWindowIsBlocked() {
        val v = visa("v1", setOf("CA"), "multiple", expiry = "2026-10-01", validFrom = "2026-11-01")
        val r = add(v)
        assertFalse(r.canProceed)
        assertTrue(r.blocks.any { it.code == "doc.validity.inverted" })
    }

    @Test fun validFromBeforeExpiryIsAllowed() {
        val v = visa("v1", setOf("CA"), "multiple", expiry = "2026-11-01", validFrom = "2026-10-01")
        val r = add(v)
        assertTrue(r.canProceed)
        assertFalse(codes(r).contains("doc.validity.inverted"))
    }

    @Test fun updatingToOwnSignatureIsNotDuplicate() {
        val p1 = passport("p1", "FR", "123", "2028-01-01")
        val r = update(p1.copy(label = "Renamed"), p1)
        assertFalse(codes(r).contains("doc.duplicate"))
    }

    // ------------------------------------------------------------------
    // doc.inUse.identity
    // ------------------------------------------------------------------

    @Test fun changingInUsePassportCountryIsBlocked() {
        val p1 = passport("p1", "FR", "123", "2028-01-01")
        val t = trip("t1", stop("s1", "DE", LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 10), "p1"))
        val candidate = passport("p1", "DE", "123", "2028-01-01")
        val r = update(candidate, p1, listOf(t))
        assertFalse(r.canProceed)
        val f = r.findings.firstOrNull { it.code == "doc.inUse.identity" }
        assertNotNull(f)
        assertEquals("In use by a trip", f!!.title)
        assertTrue("msg=${f.message}", f.message.contains("its country can't be changed"))
    }

    @Test fun changingInUseDatesOnlyIsAllowed() {
        val p1 = passport("p1", "FR", "2028-01-01")
        val t = trip("t1", stop("s1", "DE", LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 10), "p1"))
        val candidate = passport("p1", "FR", "2029-01-01", "2019-01-01")
        val r = update(candidate, p1, listOf(t))
        assertTrue("codes=${codes(r)}", r.canProceed)
        assertFalse(codes(r).contains("doc.inUse.identity"))
    }

    @Test fun unreferencedDocumentCanChangeIdentity() {
        val p1 = passport("p1", "FR", "123", "2028-01-01")
        val candidate = passport("p1", "DE", "123", "2028-01-01")
        val r = update(candidate, p1)
        assertFalse(codes(r).contains("doc.inUse.identity"))
    }

    @Test fun changingInUseCustomCountriesIsBlocked() {
        val v1 = visa("v1", setOf("DE"), "multiple", "2028-01-01")
        val t = trip("t1", stop("s1", "DE", LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 10), "v1"))
        val candidate = visa("v1", setOf("DE", "FR"), "multiple", "2028-01-01")
        val r = update(candidate, v1, listOf(t))
        val f = r.findings.firstOrNull { it.code == "doc.inUse.identity" }
        assertNotNull(f)
        assertTrue("msg=${f!!.message}", f.message.contains("countries"))
    }

    // ------------------------------------------------------------------
    // doc.inUse.removable
    // ------------------------------------------------------------------

    @Test fun removingInUseDocumentIsBlocked() {
        val t = trip("t1", stop("s1", "DE", LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 10), "p1"))
        val r = GuardEngine.evaluate(GuardAction.RemoveDocument("p1"), ctx(trips = listOf(t)))
        assertFalse(r.canProceed)
        val f = r.findings.firstOrNull { it.code == "doc.inUse.removable" }
        assertNotNull(f)
        assertEquals("In use", f!!.title)
        assertEquals(
            "This document is used by a trip, so it can't be removed. Change that trip to use a different document, or delete the trip.",
            f.message,
        )
    }

    @Test fun removingUnusedDocumentIsAllowed() {
        val r = GuardEngine.evaluate(GuardAction.RemoveDocument("p1"), ctx())
        assertTrue(r.canProceed)
        assertTrue(r.findings.isEmpty())
    }

    // ------------------------------------------------------------------
    // doc.visa.multiCountry
    // ------------------------------------------------------------------

    @Test fun multiCountryVisaWarnsAndIsSavable() {
        val c = visa("v1", setOf("DE", "FR"), "multiple", "2028-01-01")
        val r = add(c)
        assertTrue(r.canProceed)
        assertTrue(r.blocks.isEmpty())
        val f = r.warnings.firstOrNull { it.code == "doc.visa.multiCountry" }
        assertNotNull(f)
        assertEquals(
            "You have multiple countries selected for this visa: France, Germany. Are you sure you want to save?",
            f!!.message,
        )
    }

    @Test fun singleCountryVisaDoesNotWarn() {
        val c = visa("v1", setOf("DE"), "multiple", "2028-01-01")
        val r = add(c)
        assertFalse(codes(r).contains("doc.visa.multiCountry"))
    }

    @Test fun multiCountryResidenceDoesNotWarn() {
        val c = residence("r1", setOf("DE", "FR"), "2028-01-01")
        val r = add(c)
        assertFalse(codes(r).contains("doc.visa.multiCountry"))
    }

    // ------------------------------------------------------------------
    // doc.inUse.validity (D3a)
    // ------------------------------------------------------------------

    @Test fun shorteningExpiryBelowStayEndIsBlocked() {
        val v1 = visa("v1", setOf("DE"), "multiple", "2026-12-31")
        val t = trip("t1", stop("s1", "DE", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 15), "v1"))
        val candidate = v1.copy(expiry = "2026-10-10")
        val r = update(candidate, v1, listOf(t))
        assertFalse(r.canProceed)
        val f = r.findings.firstOrNull { it.code == "doc.inUse.validity" }
        assertNotNull(f)
        assertTrue("msg=${f!!.message}", f.message.contains("trip to Germany"))
        assertTrue(f.message.contains("2026-10-15"))
    }

    @Test fun validFromAfterStayStartIsBlocked() {
        val v1 = visa("v1", setOf("DE"), "multiple", "2026-12-31")
        val t = trip("t1", stop("s1", "DE", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 15), "v1"))
        val candidate = v1.copy(validFrom = "2026-10-05")
        val r = update(candidate, v1, listOf(t))
        assertFalse(r.canProceed)
        val f = r.findings.firstOrNull { it.code == "doc.inUse.validity" }
        assertNotNull(f)
        assertTrue("msg=${f!!.message}", f.message.contains("before this document became valid"))
    }

    @Test fun datesStillCoveringStayAreAllowed() {
        val v1 = visa("v1", setOf("DE"), "multiple", "2026-12-31")
        val t = trip("t1", stop("s1", "DE", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 15), "v1"))
        val candidate = v1.copy(expiry = "2027-01-01", validFrom = "2026-09-01")
        val r = update(candidate, v1, listOf(t))
        assertTrue("codes=${codes(r)}", r.canProceed)
    }

    @Test fun futureStayWithoutEntriesUsesRawExpiry() {
        val v1 = visa("v1", setOf("DE"), "single", "2026-12-31")
        val t = trip("t1", stop("s1", "DE", LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 20), "v1"))
        val candidate = v1.copy(expiry = "2026-11-10")
        val r = update(candidate, v1, listOf(t))
        assertFalse(r.canProceed)
        assertTrue(codes(r).contains("doc.inUse.validity"))
    }

    @Test fun exhaustedDocumentCoversNoFutureStay() {
        val v1 = visa("v1", setOf("DE"), "single", "2026-12-31")
        val past = trip("t1", stop("s1", "DE", LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 5), "v1"))
        val next = trip("t2", stop("s2", "DE", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 5), "v1"))
        val r = update(v1.copy(label = "Visa: DE (checked)"), v1, listOf(past, next))
        assertFalse(r.canProceed)
        assertTrue(codes(r).contains("doc.inUse.validity"))
    }

    @Test fun passportFallbackToDateExpiry() {
        val p1 = passport("p1", "FR", "123", "2028-01-01")
        val t = trip("t1", stop("s1", "DE", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 15), "p1"))
        val candidate = p1.copy(expiry = "2026-10-10")
        val r = update(candidate, p1, listOf(t))
        assertFalse(r.canProceed)
        assertTrue(codes(r).contains("doc.inUse.validity"))
    }

    @Test fun openEndedStayUsesToday() {
        val p1 = passport("p1", "FR", "123", "2026-09-20")
        val t = trip("t1", stop("s1", "DE", LocalDate.of(2026, 9, 1), null, "p1"))
        val candidate = p1.copy(expiry = "2026-09-10")
        val r = update(candidate, p1, listOf(t))
        assertFalse(r.canProceed)
        assertTrue(codes(r).contains("doc.inUse.validity"))
    }

    // ------------------------------------------------------------------
    // doc.inUse.entries (D3b)
    // ------------------------------------------------------------------

    @Test fun reducingEntryCountOfUsedDocumentIsBlocked() {
        val v1 = visa("v1", setOf("CA"), "double", "2028-01-01")
        val past = trip("t1", stop("s1", "CA", LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 10), "v1"))
        val candidate = v1.copy(kind = DocKind.Custom(setOf("CA"), null, "visa", null, "single"))
        val r = update(candidate, v1, listOf(past))
        assertFalse(r.canProceed)
        val f = r.findings.firstOrNull { it.code == "doc.inUse.entries" }
        assertNotNull(f)
        assertTrue("msg=${f!!.message}", f.message.contains("double"))
    }

    @Test fun overConsumeAfterReductionIsBlockedWithSpecificMessage() {
        val v1 = visa("v1", setOf("CA"), "double", "2028-01-01")
        val past1 = trip("t1", stop("s1", "CA", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 5), "v1"))
        val past2 = trip("t2", stop("s2", "CA", LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 5), "v1"))
        val candidate = v1.copy(kind = DocKind.Custom(setOf("CA"), null, "visa", null, "single"))
        val r = update(candidate, v1, listOf(past1, past2))
        assertFalse(r.canProceed)
        val f = r.findings.firstOrNull { it.code == "doc.inUse.entries" }
        assertNotNull(f)
        assertTrue("msg=${f!!.message}", f.message.contains("already consumed 2"))
    }

    @Test fun makingUsedDocumentFiniteIsBlocked() {
        val v1 = visa("v1", setOf("CA"), "multiple", "2028-01-01")
        val past = trip("t1", stop("s1", "CA", LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 10), "v1"))
        val candidate = v1.copy(kind = DocKind.Custom(setOf("CA"), null, "visa", null, "single"))
        val r = update(candidate, v1, listOf(past))
        assertFalse(r.canProceed)
        assertTrue(codes(r).contains("doc.inUse.entries"))
    }

    @Test fun increasingEntryCountOfUsedDocumentIsAllowed() {
        val v1 = visa("v1", setOf("CA"), "single", "2028-01-01")
        val past = trip("t1", stop("s1", "CA", LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 10), "v1"))
        val candidate = v1.copy(kind = DocKind.Custom(setOf("CA"), null, "visa", null, "double"))
        val r = update(candidate, v1, listOf(past))
        assertTrue("codes=${codes(r)}", r.canProceed)
        assertFalse(codes(r).contains("doc.inUse.entries"))
    }

    @Test fun unusedDocumentEntryChangeIsAllowed() {
        val v1 = visa("v1", setOf("CA"), "multiple", "2028-01-01")
        val candidate = v1.copy(kind = DocKind.Custom(setOf("CA"), null, "visa", null, "single"))
        val r = update(candidate, v1)
        assertTrue("codes=${codes(r)}", r.canProceed)
    }

    @Test fun unchangedCountWithPreexistingOveruseIsNotBlocked() {
        val v1 = visa("v1", setOf("CA"), "single", "2028-01-01")
        val past1 = trip("t1", stop("s1", "CA", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 5), "v1"))
        val past2 = trip("t2", stop("s2", "CA", LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 5), "v1"))
        val candidate = v1.copy(label = "Visa: CA (renamed)")
        val r = update(candidate, v1, listOf(past1, past2))
        assertTrue("codes=${codes(r)}", r.canProceed)
    }

    // ------------------------------------------------------------------
    // doc.cap.*
    // ------------------------------------------------------------------

    /** [n] valid passports spread over the fixture countries so no per-country count reaches the cap. */
    private fun validPassports(n: Int): List<Document> {
        val isos = listOf("FR", "DE", "CA")
        return (1..n).map { i -> passport("p$i", isos[(i - 1) % 3], "2028-%02d-01".format(i)) }
    }

    @Test fun eleventhValidPassportIsBlocked() {
        // 10 valid passports (FR 3, DE 3, CA 4) so only the total cap, not the per-country one, fires.
        val existing = validPassports(9) + passport("pCA4", "CA", "2028-12-01")
        val candidate = passport("pFR4", "FR", "2028-10-01")
        val r = add(candidate, existing)
        assertFalse(r.canProceed)
        assertTrue("codes=${codes(r)}", codes(r).contains("doc.cap.passportTotal"))
        assertFalse("codes=${codes(r)}", codes(r).contains("doc.cap.passportPerCountry"))
    }

    @Test fun tenthValidPassportIsAllowed() {
        val existing = validPassports(9)
        val candidate = passport("pFR4", "FR", "2028-10-01")
        val r = add(candidate, existing)
        assertTrue("codes=${codes(r)}", r.canProceed)
        assertFalse(codes(r).contains("doc.cap.passportTotal"))
    }

    @Test fun expiredPassportDoesNotCountTowardsCap() {
        // 9 valid + 1 expired FR: counting the expired one would reach the per-country cap (4).
        val existing = validPassports(9) + passport("pX", "FR", "2025-01-01")
        val candidate = passport("pFR4", "FR", "2028-10-01")
        val r = add(candidate, existing)
        assertTrue("codes=${codes(r)}", r.canProceed)
    }

    @Test fun fifthPassportForCountryIsBlocked() {
        val existing = (1..4).map { passport("p$it", "FR", "2028-%02d-01".format(it)) } +
            (1..3).map { passport("d$it", "DE", "2028-%02d-01".format(it)) }
        val candidate = passport("p5", "FR", "2028-05-01")
        val r = add(candidate, existing)
        assertFalse(r.canProceed)
        assertTrue("codes=${codes(r)}", codes(r).contains("doc.cap.passportPerCountry"))
    }

    @Test fun eleventhValidVisaIsBlocked() {
        val existing = (1..10).map {
            visa("v$it", setOf("CA"), "multiple", "2027-0${if (it < 10) it + 1 else 1}-01")
        }
        // A fresh country (FR) frees no slot via supersession, so the total cap still blocks (P3-5).
        val candidate = visa("v11", setOf("FR"), "multiple", "2027-12-01")
        val r = add(candidate, existing)
        assertFalse(r.canProceed)
        assertTrue("codes=${codes(r)}", codes(r).contains("doc.cap.visaTotal"))
    }

    @Test fun eleventhValidResidenceIsBlocked() {
        val existing = (1..10).map {
            residence("r$it", setOf("CA"), "2027-0${if (it < 10) it + 1 else 1}-01")
        }
        // A fresh country (FR) frees no slot via supersession, so the total cap still blocks (P3-5).
        val candidate = residence("r11", setOf("FR"), "2027-12-01")
        val r = add(candidate, existing)
        assertFalse(r.canProceed)
        assertTrue("codes=${codes(r)}", codes(r).contains("doc.cap.residenceTotal"))
    }

    @Test fun expiredCandidateIsNotSubjectToCaps() {
        val existing = validPassports(10)
        val candidate = passport("pX", "FR", "2025-01-01")
        val r = add(candidate, existing)
        assertFalse(codes(r).contains("doc.cap.passportTotal"))
    }

    @Test fun exhaustedVisaDoesNotCountTowardsCap() {
        val visas = (1..9).map { visa("v$it", setOf("CA"), "multiple", "2028-0${if (it < 10) it + 1 else 1}-01") }
        val exhausted = visa("vX", setOf("CA"), "single", "2028-01-01")
        val past = trip("t1", stop("s1", "CA", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 5), "vX"))
        val candidate = visa("v11", setOf("CA"), "multiple", "2028-12-01")
        val r = add(candidate, visas + exhausted, listOf(past))
        assertTrue("codes=${codes(r)}", r.canProceed)
    }

    // ------------------------------------------------------------------
    // doc.single.perCountry
    // ------------------------------------------------------------------

    @Test fun newVisaSupersedesOldWithMutation() {
        val v1 = visa("v1", setOf("CA"), "multiple", "2027-06-30")
        val v2 = visa("v2", setOf("CA"), "multiple", "2028-01-01", "2026-10-01")
        val r = add(v2, listOf(v1))
        assertTrue(r.canProceed)
        assertEquals(listOf(GuardMutation.SupersedeDocument("v1", "v2")), r.mutations)
        val f = r.warnings.firstOrNull { it.code == "doc.single.perCountry" }
        assertNotNull(f)
        assertTrue("msg=${f!!.message}", f.message.contains("Visa: CA"))
        assertTrue(f.message.contains("2026-10-01"))
        assertTrue(f.message.contains("one valid visa per country"))
        assertFalse("msg=${f.message}", f.message.contains("still used by"))
    }

    @Test fun supersessionWarningMentionsInUseTrips() {
        val v1 = visa("v1", setOf("CA"), "multiple", "2027-06-30")
        val t = trip("t1", stop("s1", "CA", today.plusDays(20), today.plusDays(30), docId = "v1"))
        val v2 = visa("v2", setOf("CA"), "multiple", "2028-01-01", "2026-10-01")
        val r = add(v2, listOf(v1), trips = listOf(t))
        val f = r.warnings.firstOrNull { it.code == "doc.single.perCountry" }
        assertNotNull(f)
        assertTrue("msg=${f!!.message}", f.message.contains("still used by 1 trip"))
    }

    @Test fun supersessionWithoutValidFromUsesToday() {
        val v1 = visa("v1", setOf("CA"), "multiple", "2027-06-30")
        val v2 = visa("v2", setOf("CA"), "multiple", "2028-01-01")
        val r = add(v2, listOf(v1))
        assertTrue(r.canProceed)
        assertEquals(listOf(GuardMutation.SupersedeDocument("v1", "v2")), r.mutations)
    }

    @Test fun differentCountryDoesNotSupersede() {
        val v1 = visa("v1", setOf("CA"), "multiple", "2027-06-30")
        val v2 = visa("v2", setOf("DE"), "multiple", "2028-01-01", "2026-10-01")
        val r = add(v2, listOf(v1))
        assertTrue(r.canProceed)
        assertTrue(r.mutations.isEmpty())
        assertFalse(codes(r).contains("doc.single.perCountry"))
    }

    @Test fun alreadyExpiredOldDocumentIsNotSuperseded() {
        val v1 = visa("v1", setOf("CA"), "multiple", "2026-09-01")
        val v2 = visa("v2", setOf("CA"), "multiple", "2028-01-01", "2026-10-01")
        val r = add(v2, listOf(v1))
        assertTrue(r.canProceed)
        assertTrue(r.mutations.isEmpty())
    }

    @Test fun eachOverlappingDocumentGetsItsOwnSupersession() {
        val v1 = visa("v1", setOf("CA"), "multiple", "2027-06-30")
        val v2 = visa("v2", setOf("CA", "DE"), "multiple", "2028-01-01")
        val v3 = visa("v3", setOf("CA"), "multiple", "2028-06-01", "2026-10-01")
        val r = add(v3, listOf(v1, v2))
        assertTrue(r.canProceed)
        assertEquals(
            listOf(GuardMutation.SupersedeDocument("v1", "v3"), GuardMutation.SupersedeDocument("v2", "v3")),
            r.mutations,
        )
        assertEquals(2, r.warnings.count { it.code == "doc.single.perCountry" })
    }

    @Test fun alreadySupersededDocumentIsNotSupersededAgain() {
        // v1 was superseded by v2 in January, so as of the new candidate's start it is already lapsed.
        val v1 = visa("v1", setOf("CA"), "multiple", "2027-06-30").copy(supersededBy = "v2")
        val v2 = visa("v2", setOf("CA"), "multiple", "2028-01-01", "2026-01-01")
        val v3 = visa("v3", setOf("CA"), "multiple", "2028-06-01", "2026-10-01")
        val r = add(v3, listOf(v1, v2))
        assertTrue(r.canProceed)
        assertEquals(listOf(GuardMutation.SupersedeDocument("v2", "v3")), r.mutations)
    }

    @Test fun supersededDocumentsDoNotCountTowardVisaCap() {
        val valid = (1..9).map { i ->
            visa("v$i", setOf("CA"), "multiple", "2027-%02d-01".format(i), if (i == 1) "2026-01-01" else null)
        }
        val superseded = visa("vold", setOf("CA"), "multiple", "2027-06-30").copy(supersededBy = "v1")
        val candidate = visa("vnew", setOf("CA"), "multiple", "2028-01-01")
        val r = add(candidate, valid + superseded)
        assertTrue("codes=${codes(r)}", r.canProceed)
        assertFalse(codes(r).contains("doc.cap.visaTotal"))
    }

    @Test fun differentCategoryDoesNotSupersede() {
        val r1 = residence("r1", setOf("CA"), "2027-06-30")
        val v2 = visa("v2", setOf("CA"), "multiple", "2028-01-01", "2026-10-01")
        val r = add(v2, listOf(r1))
        assertTrue(r.canProceed)
        assertTrue(r.mutations.isEmpty())
    }

    @Test fun updateMovingWindowOntoOldDocumentSupersedesIt() {
        val v1 = visa("v1", setOf("CA"), "multiple", "2028-01-01", "2027-01-01")
        val v2 = visa("v2", setOf("CA"), "multiple", "2026-12-31", "2026-09-01")
        val candidate = v2.copy(validFrom = "2027-02-01", expiry = "2027-06-30")
        val r = GuardEngine.evaluate(
            GuardAction.UpdateDocument(candidate, v2),
            ctx(docs = listOf(v1, v2)),
        )
        assertTrue(r.canProceed)
        assertEquals(listOf(GuardMutation.SupersedeDocument("v1", "v2")), r.mutations)
    }

    @Test fun blocHoldingDoesNotSupersedeNonTravelBlocMemberDoc() {
        // P1-4: a bloc-issued (issuingCountry "EU") residence spans the visa-free travel bloc
        // (Schengen) for supersession purposes — NOT the freedom-of-movement bloc — so a valid
        // Irish or Cypriot document is untouched, while a German one is superseded.
        val w = WorldData(
            mapOf(
                "IE" to Country("IE", "Ireland"),
                "CY" to Country("CY", "Cyprus"),
                "DE" to Country("DE", "Germany"),
            ),
            emptyMap(),
            listOf(
                Regime("eu-eea-efta", "EU/EEA/EFTA", "freedom-of-movement", listOf("IE", "CY", "DE", "FR", "CZ")),
                Regime("schengen", "Schengen", "visa-free", listOf("DE", "FR", "CZ")),
            ),
            mapOf("eu-res" to Holding("eu-res", "EU Residence", "residency", "EU")),
            emptyMap(),
            emptyList(),
        )
        val candidate = Document("h-new", "EU residence", DocKind.Holding("eu-res"), "2030-01-01")
        val ieRes = residence("r-ie", setOf("IE"), "2028-06-30")
        val r = GuardEngine.evaluate(GuardAction.AddDocument(candidate), ctx(docs = listOf(ieRes), w = w))
        assertTrue("codes=${codes(r)}", r.canProceed)
        assertTrue(r.mutations.isEmpty())
        val deRes = residence("r-de", setOf("DE"), "2028-06-30")
        val r2 = GuardEngine.evaluate(GuardAction.AddDocument(candidate), ctx(docs = listOf(deRes), w = w))
        assertEquals(listOf(GuardMutation.SupersedeDocument("r-de", "h-new")), r2.mutations)
    }

    @Test fun passportsAreNotSubjectToSinglePerCountry() {
        val p1 = passport("p1", "FR", "123", "2028-01-01")
        val p2 = passport("p2", "FR", "456", "2028-01-01")
        val r = add(p2, listOf(p1))
        assertTrue("codes=${codes(r)}", r.canProceed)
        assertTrue(r.mutations.isEmpty())
    }

    // ------------------------------------------------------------------
    // doc.supersession.stale (P2-5) + post-apply state (P2-11)
    // ------------------------------------------------------------------

    @Test fun supersededDocIsExpiredAfterMutationApplied() {
        // P2-11: assert the DocStatus AFTER the mutation is applied, not just the emitted mutation
        // (the level at which P1-1 slipped through).
        val v1 = visa("v1", setOf("CA"), "multiple", "2027-06-30")
        val v2 = visa("v2", setOf("CA"), "multiple", "2028-01-01", "2026-09-01")
        val r = add(v2, listOf(v1))
        assertEquals(listOf(GuardMutation.SupersedeDocument("v1", "v2")), r.mutations)
        val superseded = v1.copy(supersededBy = "v2")
        val applied = listOf(superseded, v2)
        val s1 = DocStatus.of(superseded, applied, emptyList(), world(), today)
        assertTrue(s1.expired)
        assertEquals(ExpiryReason.SUPERSEDED, s1.reason)
        assertFalse(DocStatus.of(v2, applied, emptyList(), world(), today).expired)
    }

    @Test fun supersessionWithoutValidFromArchivesOldDocImmediately() {
        // Pinned at the DocStatus level, post-apply (P2-11); user decision 2026-09-27: a
        // replacement without a validFrom archives the old doc IMMEDIATELY (red "Replaced by …"),
        // on the evaluation day itself and every day after — no shifting amber limbo.
        val v1 = visa("v1", setOf("CA"), "multiple", "2027-06-30")
        val v2 = visa("v2", setOf("CA"), "multiple", "2028-01-01")
        val superseded = v1.copy(supersededBy = "v2")
        val applied = listOf(superseded, v2)
        for (day in listOf(today, today.plusDays(1), today.plusDays(30))) {
            val s = DocStatus.of(superseded, applied, emptyList(), world(), day)
            assertTrue("day=$day", s.expired)
            assertEquals(ExpiryReason.SUPERSEDED, s.reason)
        }
    }

    @Test fun updatingReplacementAwayFromOverlapRevivesOldDoc() {
        // P2-5: v2 superseded v1 (both CA). Editing v2's countries so they no longer overlap
        // emits an Unsupersede mutation; once applied, v1 is valid again.
        val v1 = visa("v1", setOf("CA"), "multiple", "2027-06-30").copy(supersededBy = "v2")
        val v2 = visa("v2", setOf("CA"), "multiple", "2028-01-01", "2026-09-01")
        val candidate = v2.copy(kind = DocKind.Custom(setOf("FR"), null, "visa", null, "multiple"))
        val r = GuardEngine.evaluate(GuardAction.UpdateDocument(candidate, v2), ctx(docs = listOf(v1, v2)))
        assertTrue("codes=${codes(r)}", r.canProceed)
        assertEquals(listOf(GuardMutation.UnsupersedeDocument("v1")), r.mutations)
        val revived = v1.copy(supersededBy = null)
        val applied = listOf(revived, v2)
        val s = DocStatus.of(revived, applied, emptyList(), world(), today)
        assertFalse(s.expired)
    }

    @Test fun updatingReplacementStillOverlappingDoesNotUnsupersede() {
        val v1 = visa("v1", setOf("CA"), "multiple", "2027-06-30").copy(supersededBy = "v2")
        val v2 = visa("v2", setOf("CA"), "multiple", "2028-01-01", "2026-09-01")
        val candidate = v2.copy(label = "Renamed")
        val r = GuardEngine.evaluate(GuardAction.UpdateDocument(candidate, v2), ctx(docs = listOf(v1, v2)))
        assertTrue("codes=${codes(r)}", r.canProceed)
        assertTrue(r.mutations.none { it is GuardMutation.UnsupersedeDocument })
    }

    // ------------------------------------------------------------------
    // doc.duplicate with residenceClass (P2-7)
    // ------------------------------------------------------------------

    @Test fun sameHoldingDifferentResidenceClassIsNotDuplicate() {
        // P2-7: the class is part of the signature — an in-place class upgrade with unchanged
        // dates is an edit, not a "Document already exists" block.
        val h1 = Document("h1", "CA residence", DocKind.Holding("ca-pr"), "2030-01-01", null, ResidenceClass.TEMPORARY)
        val h2 = Document("h2", "CA residence", DocKind.Holding("ca-pr"), "2030-01-01", null, ResidenceClass.PERMANENT)
        val r = add(h2, listOf(h1))
        assertTrue("codes=${codes(r)}", r.canProceed)
        assertFalse(codes(r).contains("doc.duplicate"))
    }

    @Test fun sameHoldingSameResidenceClassStillDuplicate() {
        val h1 = Document("h1", "CA residence", DocKind.Holding("ca-pr"), "2030-01-01", null, ResidenceClass.TEMPORARY)
        val h2 = Document("h2", "CA residence", DocKind.Holding("ca-pr"), "2030-01-01", null, ResidenceClass.TEMPORARY)
        val r = add(h2, listOf(h1))
        assertFalse(r.canProceed)
        assertTrue(codes(r).contains("doc.duplicate"))
    }

    // ------------------------------------------------------------------
    // doc.inUse.entries planned usage (P2-8)
    // ------------------------------------------------------------------

    @Test fun reducingEntriesBelowPlannedFutureUsageWarns() {
        // P2-8: no entries consumed yet, but planned future stays reference the document —
        // reducing below the planned usage is a WARN (symmetric to D3a's future-stay protection).
        // Two separate future stays (a gap between them) consume two entries.
        val v1 = visa("v1", setOf("CA"), "multiple", "2028-01-01")
        val t = trip(
            "t1",
            stop("s1", "CA", today.plusDays(30), today.plusDays(40), docId = "v1"),
            stop("s2", "CA", today.plusDays(50), today.plusDays(60), docId = "v1"),
        )
        val candidate = v1.copy(kind = DocKind.Custom(setOf("CA"), null, "visa", null, "single"))
        val r = update(candidate, v1, trips = listOf(t))
        assertTrue("codes=${codes(r)}", r.canProceed)
        val f = r.findings.firstOrNull { it.code == "doc.inUse.entriesPlanned" }
        assertNotNull(f)
        assertEquals(GuardSeverity.WARN, f!!.severity)
    }

    @Test fun reducingEntriesToExactlyPlannedFutureUsageIsQuiet() {
        // One planned stay + single entry: planned usage (1) does not exceed the new total (1).
        val v1 = visa("v1", setOf("CA"), "multiple", "2028-01-01")
        val t = trip("t1", stop("s1", "CA", today.plusDays(30), today.plusDays(40), docId = "v1"))
        val candidate = v1.copy(kind = DocKind.Custom(setOf("CA"), null, "visa", null, "single"))
        val r = update(candidate, v1, trips = listOf(t))
        assertTrue("codes=${codes(r)}", r.canProceed)
        assertFalse(codes(r).contains("doc.inUse.entriesPlanned"))
    }

    @Test fun reducingEntriesWithEnoughTotalForPlannedTripsIsQuiet() {
        val v1 = visa("v1", setOf("CA"), "multiple", "2028-01-01")
        val t = trip("t1", stop("s1", "CA", today.plusDays(30), today.plusDays(40), docId = "v1"))
        val candidate = v1.copy(kind = DocKind.Custom(setOf("CA"), null, "visa", null, "double"))
        val r = update(candidate, v1, trips = listOf(t))
        assertTrue("codes=${codes(r)}", r.canProceed)
        assertFalse(codes(r).contains("doc.inUse.entriesPlanned"))
    }

    // ------------------------------------------------------------------
    // doc.inUse.validity expiry before a future open-ended stay (P2-9)
    // ------------------------------------------------------------------

    @Test fun expiryBeforeFutureOpenEndedStayIsBlocked() {
        // P2-9: open-ended future stop (no departure) — an expiry landing between today and the
        // arrival must be blocked (previously the check ended at `today` and slipped through).
        val v1 = visa("v1", setOf("CA"), "multiple", "2028-01-01")
        val t = trip("t1", stop("s1", "CA", today.plusDays(30), null, docId = "v1"))
        val candidate = v1.copy(expiry = today.plusDays(10).toString())
        val r = update(candidate, v1, trips = listOf(t))
        assertFalse(r.canProceed)
        assertTrue(codes(r).contains("doc.inUse.validity"))
    }

    @Test fun expiryCoveringFutureOpenEndedStayIsAllowed() {
        val v1 = visa("v1", setOf("CA"), "multiple", "2028-01-01")
        val t = trip("t1", stop("s1", "CA", today.plusDays(30), null, docId = "v1"))
        val r = update(v1.copy(label = "Renamed"), v1, trips = listOf(t))
        assertTrue("codes=${codes(r)}", r.canProceed)
        assertFalse(codes(r).contains("doc.inUse.validity"))
    }

    // ------------------------------------------------------------------
    // doc.inUse.identity residence class (P2-10)
    // ------------------------------------------------------------------

    @Test fun changingInUseResidenceClassIsBlocked() {
        // P2-10: the class is not part of identityKey, but it gates residence-minimum benefits,
        // so changing it under a trip is blocked like an identity change.
        val r1 = residence("r1", setOf("CA"), "2028-01-01").copy(residenceClass = ResidenceClass.TEMPORARY)
        val t = trip("t1", stop("s1", "CA", today.plusDays(30), today.plusDays(40), docId = "r1"))
        val candidate = r1.copy(residenceClass = ResidenceClass.PERMANENT)
        val r = update(candidate, r1, trips = listOf(t))
        assertFalse(r.canProceed)
        val f = r.findings.firstOrNull { it.code == "doc.inUse.identity" }
        assertNotNull(f)
        assertTrue("msg=${f!!.message}", f.message.contains("residence class"))
    }

    @Test fun changingUnusedResidenceClassIsAllowed() {
        val r1 = residence("r1", setOf("CA"), "2028-01-01").copy(residenceClass = ResidenceClass.TEMPORARY)
        val candidate = r1.copy(residenceClass = ResidenceClass.PERMANENT)
        val r = update(candidate, r1)
        assertTrue("codes=${codes(r)}", r.canProceed)
    }

    // ------------------------------------------------------------------
    // doc.passport.unknownCountry
    // ------------------------------------------------------------------

    @Test fun unknownPassportCountryIsBlockedOnAdd() {
        val r = add(passport("p1", "XX", "123", "2028-01-01"))
        assertFalse(r.canProceed)
        assertTrue(codes(r).contains("doc.passport.unknownCountry"))
    }

    @Test fun unknownPassportCountryIsBlockedOnUpdate() {
        val p1 = passport("p1", "FR", "123", "2028-01-01")
        val r = GuardEngine.evaluate(GuardAction.UpdateDocument(p1.copy(kind = DocKind.Passport("XX")), p1), ctx(docs = listOf(p1)))
        assertFalse(r.canProceed)
        assertTrue(codes(r).contains("doc.passport.unknownCountry"))
    }

    @Test fun secondPassportOfSameCountryIsAllowed() {
        val p1 = passport("p1", "FR", "123", "2028-01-01")
        val p2 = passport("p2", "FR", "456", "2030-01-01")
        val r = add(p2, listOf(p1))
        assertTrue("codes=${codes(r)}", r.canProceed)
    }

    @Test fun freshPassportCountryIsAllowed() {
        val p1 = passport("p1", "FR", "123", "2028-01-01")
        val r = add(passport("p2", "DE", "789", "2028-01-01"), listOf(p1))
        assertTrue(r.canProceed)
        assertTrue(r.findings.isEmpty())
    }

    // ------------------------------------------------------------------
    // doc.primary.notPassport
    // ------------------------------------------------------------------

    @Test fun settingVisaAsPrimaryIsBlocked() {
        val v1 = visa("v1", setOf("CA"), "multiple", "2028-01-01")
        val r = GuardEngine.evaluate(GuardAction.SetPrimary("v1"), ctx(docs = listOf(v1)))
        assertFalse(r.canProceed)
        assertTrue(codes(r).contains("doc.primary.notPassport"))
    }

    @Test fun settingPassportAsPrimaryIsAllowed() {
        val p1 = passport("p1", "FR", "123", "2028-01-01")
        val r = GuardEngine.evaluate(GuardAction.SetPrimary("p1"), ctx(docs = listOf(p1)))
        assertTrue(r.canProceed)
    }

    @Test fun clearingPrimaryIsAllowed() {
        val v1 = visa("v1", setOf("CA"), "multiple", "2028-01-01")
        val r = GuardEngine.evaluate(GuardAction.SetPrimary(null), ctx(docs = listOf(v1)))
        assertTrue(r.canProceed)
    }

    // ------------------------------------------------------------------
    // Trip rules
    // ------------------------------------------------------------------

    private fun addTrip(
        t: Trip,
        docs: List<Document> = emptyList(),
        trips: List<Trip> = emptyList(),
        primaryId: String? = null,
    ): GuardResult =
        GuardEngine.evaluate(GuardAction.AddTrip(t), ctx(docs = docs, trips = trips, primaryId = primaryId))

    @Test fun tripWithNoStopsIsBlocked() {
        val r = addTrip(Trip("t1", emptyList()))
        assertTrue(r.blocks.any { it.code == "trip.noStops" })
        assertFalse(r.canProceed)
    }

    @Test fun tripToUnknownCountryIsBlocked() {
        val r = addTrip(trip("t1", stop("s1", "XX", today.plusDays(10), today.plusDays(20))))
        assertTrue(r.blocks.any { it.code == "trip.unknownCountry" })
        assertFalse(r.canProceed)
    }

    @Test fun departureBeforeArrivalIsBlocked() {
        val r = addTrip(trip("t1", stop("s1", "DE", today.plusDays(10), today.plusDays(5))))
        assertTrue(r.blocks.any { it.code == "trip.departureBeforeArrival" })
        assertFalse(r.canProceed)
    }

    @Test fun nonFinalStopWithoutDepartureWarnsButIsSavable() {
        val r = addTrip(
            trip("t1", stop("s1", "DE", today.plusDays(10), null),
                stop("s2", "CA", today.plusDays(20), today.plusDays(30))),
        )
        assertTrue(r.warnings.any { it.code == "trip.missingDeparture" })
        assertTrue(r.canProceed)
    }

    @Test fun gapBetweenStopsWarns() {
        val r = addTrip(
            trip("t1", stop("s1", "DE", today.plusDays(10), today.plusDays(12)),
                stop("s2", "CA", today.plusDays(20), today.plusDays(30))),
        )
        assertTrue(r.warnings.any { it.code == "trip.gap" })
    }

    @Test fun continuousStopsProduceNoGapFinding() {
        val r = addTrip(
            trip("t1", stop("s1", "DE", today.plusDays(10), today.plusDays(12)),
                stop("s2", "CA", today.plusDays(13), today.plusDays(30))),
        )
        assertFalse(r.findings.any { it.code == "trip.gap" })
    }

    @Test fun overlappingTripInSameCountryWarns() {
        val other = trip("t0", stop("s0", "DE", today.plusDays(12), today.plusDays(20)))
        val r = addTrip(trip("t1", stop("s1", "DE", today.plusDays(10), today.plusDays(15))), trips = listOf(other))
        assertTrue(r.warnings.any { it.code == "trip.overlap" })
    }

    @Test fun crossCountryTimeOverlapWarns() {
        val other = trip("t0", stop("s0", "DE", today.plusDays(12), today.plusDays(20)))
        val r = addTrip(trip("t1", stop("s1", "CA", today.plusDays(10), today.plusDays(15))), trips = listOf(other))
        val f = r.findings.firstOrNull { it.code == "trip.timeOverlap" }
        assertNotNull(f)
        assertEquals(GuardSeverity.WARN, f!!.severity)
        assertFalse(r.findings.any { it.code == "trip.overlap" })
        assertTrue(r.canProceed)
    }

    @Test fun nonOverlappingDifferentCountriesDoNotWarn() {
        val other = trip("t0", stop("s0", "DE", today.plusDays(12), today.plusDays(20)))
        val r = addTrip(trip("t1", stop("s1", "CA", today.plusDays(22), today.plusDays(27))), trips = listOf(other))
        assertFalse(r.findings.any { it.code == "trip.timeOverlap" || it.code == "trip.overlap" })
    }

    @Test fun updatingTripDoesNotWarnAgainstItself() {
        // P2-39: UpdateTrip's self-exclusion (others = trips.filter { it.id != candidate.id }) —
        // the trip being updated still sits in ctx.trips, so without the filter it would warn
        // about overlapping itself.
        val t = trip("t1", stop("s1", "DE", today.plusDays(10), today.plusDays(20)))
        val r = GuardEngine.evaluate(GuardAction.UpdateTrip(t), ctx(trips = listOf(t)))
        assertFalse(r.findings.any { it.code == "trip.overlap" })
        assertFalse(r.findings.any { it.code == "trip.timeOverlap" })
        assertTrue(r.canProceed)
    }

    @Test fun sameTimeOverlapAcrossZoneStillUsesZoneFinding() {
        val rule = StayRule("Schengen", setOf("FR", "DE"), "rolling", 90, 180, true, setOf("*"), null, "schengen", "schengen")
        val w = WorldData(
            mapOf("FR" to Country("FR", "France"), "DE" to Country("DE", "Germany"), "CA" to Country("CA", "Canada")),
            emptyMap(),
            emptyList(),
            mapOf("ca-pr" to Holding("ca-pr", "Canadian PR", "residency", "CA")),
            emptyMap(),
            listOf(rule),
        )
        val other = trip("t0", stop("s0", "FR", today.plusDays(12), today.plusDays(20)))
        val r = GuardEngine.evaluate(
            GuardAction.AddTrip(trip("t1", stop("s1", "DE", today.plusDays(10), today.plusDays(15)))),
            ctx(trips = listOf(other), w = w),
        )
        assertTrue(r.findings.any { it.code == "trip.overlap" })
        assertFalse(r.findings.any { it.code == "trip.timeOverlap" })
    }

    @Test fun projectionDangerIsAWarningNotABlock() {
        // FR passport holder, bare world (no rules) → DE stop gets "No valid entry grant",
        // a DANGER before the unified guard, a savable WARN now (Q1).
        val r = addTrip(
            trip("t1", stop("s1", "DE", today.plusDays(10), today.plusDays(20))),
            docs = listOf(passport("p1", "FR", expiry = "2027-12-31")),
        )
        val f = r.findings.firstOrNull { it.code == "trip.access.none" }
        assertNotNull(f)
        assertEquals(GuardSeverity.WARN, f!!.severity)
        assertTrue(r.canProceed)
    }

    @Test fun futureValidFromDoesNotGrantAccessBeforeItStarts() {
        // CA visa that only becomes valid in 30 days must not cover an imminent CA stop.
        val docs = listOf(
            passport("p1", "FR", expiry = "2027-12-31"),
            visa("v1", setOf("CA"), "multiple", "2027-12-31", validFrom = "2026-10-16"),
        )
        val r = addTrip(trip("t1", stop("s1", "CA", today.plusDays(5), today.plusDays(10))), docs = docs)
        assertTrue("codes=${codes(r)}", r.findings.any { it.code == "trip.access.none" })
        assertFalse(r.findings.any { it.code == "trip.access.blocked" })
    }

    @Test fun pastValidFromStillGrantsAccess() {
        // Same setup as the future-validFrom case, but the visa is already valid → CA is covered.
        val docs = listOf(
            passport("p1", "FR", expiry = "2027-12-31"),
            visa("v1", setOf("CA"), "multiple", "2027-12-31", validFrom = "2026-01-01"),
        )
        val r = addTrip(trip("t1", stop("s1", "CA", today.plusDays(5), today.plusDays(10))), docs = docs)
        assertFalse("codes=${codes(r)}", r.findings.any { it.code == "trip.access.none" || it.code == "trip.access.blocked" })
    }

    @Test fun stopsBeforeValidFromDoNotConsumeEntries() {
        val v = visa("v1", setOf("CA"), "single", "2027-12-31", validFrom = "2026-10-16")
        val t = trip("t1", stop("s1", "CA", today.plusDays(5), today.plusDays(10), docId = "v1"))
        val status = TripModel.entryStatusFor(v, listOf(t), world(), today)
        assertNotNull(status)
        assertEquals(1, status!!.total)
        assertEquals(0, status.used)
    }

    @Test fun adapterParityWithLegacyWarnings() {
        val docs = listOf(
            passport("p1", "FR", expiry = "2027-12-31"),
            visa("v1", setOf("DE"), expiry = "2027-12-31"),
        )
        val w = world()
        val corpus = listOf(
            trip("t1", stop("s1", "DE", today.plusDays(10), today.plusDays(20))),
            trip("t2", stop("s1", "DE", today.plusDays(10), null)),
            trip("t3", stop("s1", "DE", today.plusDays(10), today.plusDays(12)),
                stop("s2", "CA", today.plusDays(20), today.plusDays(30))),
        )
        // The engine also emits its own additions (`trip.passport.*`, `trip.ongoing.cap`); parity
        // here covers exactly the codes the legacy TripModel warnings carry.
        val adapterCodes = setOf(
            "trip.noStops", "trip.unknownCountry", "trip.departureBeforeArrival", "trip.missingDeparture",
            "trip.gap", "trip.overlap", "trip.timeOverlap",
            "trip.allowance.exceeded", "trip.allowance.low", "trip.entries.over", "trip.entries.last",
            "trip.access.none", "trip.access.blocked", "trip.access.extraStep",
        )
        for (t in corpus) {
            val others = corpus.filter { it.id != t.id }
            val legacy = TripModel.validateTrip(t, w, today) +
                TripModel.gapWarningsFor(t, today) +
                TripModel.overlapWarningsFor(t, others, docs, w, today) +
                TripModel.projectionWarningsFor(t, others, docs, w, today)
            val r = GuardEngine.evaluate(GuardAction.AddTrip(t), ctx(docs = docs, trips = corpus))
            val engineCodes = r.findings.map { it.code }.filter { it in adapterCodes }.toSortedSet()
            val legacyCodes = legacy.map { it.code }.toSortedSet()
            assertEquals("codes for ${t.id}", legacyCodes, engineCodes)
        }
    }

    @Test fun savingNewOngoingTripEmitsEndTripForOpenTrip() {
        val open = trip("t0", stop("s0", "DE", today.minusDays(10), null))
        val candidate = trip("t1", stop("s1", "CA", today.minusDays(5), today.plusDays(5)))
        val r = addTrip(candidate, trips = listOf(open))
        assertEquals(listOf(GuardMutation.EndTrip("t0", today.minusDays(5))), r.mutations)
        assertTrue(r.findings.any { it.code == "trip.ongoing.cap" })
        assertTrue(r.canProceed)
    }

    @Test fun upcomingCandidateDoesNotTriggerOngoingCap() {
        val open = trip("t0", stop("s0", "DE", today.minusDays(10), null))
        val candidate = trip("t1", stop("s1", "CA", today.plusDays(10), today.plusDays(20)))
        val r = addTrip(candidate, trips = listOf(open))
        assertFalse(r.mutations.any { it is GuardMutation.EndTrip })
        assertFalse(r.findings.any { it.code == "trip.ongoing.cap" })
    }

    @Test fun closedOtherTripsDoNotTriggerOngoingCap() {
        val closed = trip("t0", stop("s0", "DE", today.minusDays(10), today.minusDays(5)))
        val candidate = trip("t1", stop("s1", "CA", today, today.plusDays(5)))
        val r = addTrip(candidate, trips = listOf(closed))
        assertFalse(r.findings.any { it.code == "trip.ongoing.cap" })
    }

    @Test fun passportExpiringWithinThreeMonthsWarnsRedTier() {
        val p = passport("p1", "FR", expiry = today.plusDays(61).toString())
        val r = addTrip(
            trip("t1", stop("s1", "DE", today.plusDays(10), today.plusDays(20))),
            docs = listOf(p), primaryId = "p1",
        )
        assertTrue(codes(r).contains("trip.passport.expiringSoon3"))
        assertFalse(codes(r).contains("trip.passport.expiringSoon6"))
        assertTrue(r.canProceed)
    }

    @Test fun passportExpiringWithinSixMonthsWarnsOrangeTier() {
        val p = passport("p1", "FR", expiry = today.plusDays(122).toString())
        val r = addTrip(
            trip("t1", stop("s1", "DE", today.plusDays(10), today.plusDays(20))),
            docs = listOf(p), primaryId = "p1",
        )
        assertTrue(codes(r).contains("trip.passport.expiringSoon6"))
        assertFalse(codes(r).contains("trip.passport.expiringSoon3"))
    }

    @Test fun passportExpiringBeyondSixMonthsIsQuiet() {
        val p = passport("p1", "FR", expiry = today.plusDays(200).toString())
        val r = addTrip(
            trip("t1", stop("s1", "DE", today.plusDays(10), today.plusDays(20))),
            docs = listOf(p), primaryId = "p1",
        )
        assertFalse(r.findings.any { it.code.startsWith("trip.passport.") })
    }

    @Test fun passportExpiringMidTripWarns() {
        val p = passport("p1", "FR", expiry = today.plusDays(250).toString())
        val r = addTrip(
            trip("t1", stop("s1", "DE", today.plusDays(10), today.plusDays(300))),
            docs = listOf(p), primaryId = "p1",
        )
        assertTrue(codes(r).contains("trip.passport.expiresDuringTrip"))
        assertTrue(r.canProceed)
    }

    @Test fun passportExpiryAtTierBoundaries() {
        // P3-39: the 3/6-month tiers are magic ranges 1..92 / 93..183 — pin the exact boundaries so
        // an off-by-one can't silently move a passport between tiers (existing tests used 61/122/200).
        val t = trip("t1", stop("s1", "DE", today.plusDays(10), today.plusDays(20)))
        val at92 = addTrip(t, docs = listOf(passport("p1", "FR", expiry = today.plusDays(92).toString())), primaryId = "p1")
        assertTrue("92 days is still the 3-month tier", codes(at92).contains("trip.passport.expiringSoon3"))
        assertFalse("92 days is not the 6-month tier", codes(at92).contains("trip.passport.expiringSoon6"))
        val at93 = addTrip(t, docs = listOf(passport("p1", "FR", expiry = today.plusDays(93).toString())), primaryId = "p1")
        assertTrue("93 days steps up to the 6-month tier", codes(at93).contains("trip.passport.expiringSoon6"))
        assertFalse("93 days is not the 3-month tier", codes(at93).contains("trip.passport.expiringSoon3"))
        val at183 = addTrip(t, docs = listOf(passport("p1", "FR", expiry = today.plusDays(183).toString())), primaryId = "p1")
        assertTrue("183 days is still the 6-month tier", codes(at183).contains("trip.passport.expiringSoon6"))
        val at184 = addTrip(t, docs = listOf(passport("p1", "FR", expiry = today.plusDays(184).toString())), primaryId = "p1")
        assertFalse("184 days is quiet", at184.findings.any { it.code.startsWith("trip.passport.") })
    }

    @Test fun openEndedTripExpiresDuringTrip() {
        // P3-39: an open-ended trip (no final departure) whose passport expires after the trip
        // starts and after today must warn "expires during trip" — the end == null branch the
        // closed-trip mid-trip test doesn't reach.
        val p = passport("p1", "FR", expiry = today.plusDays(200).toString()) // quiet on the tiers
        val r = addTrip(
            trip("t1", stop("s1", "DE", today.plusDays(10), null)),
            docs = listOf(p), primaryId = "p1",
        )
        assertTrue(codes(r).contains("trip.passport.expiresDuringTrip"))
    }

    @Test fun passportExpiredBeforeForeignTripIsBlocked() {
        val p = passport("p1", "FR", expiry = today.minusDays(10).toString())
        val r = addTrip(
            trip("t1", stop("s1", "DE", today.plusDays(10), today.plusDays(20))),
            docs = listOf(p), primaryId = "p1",
        )
        assertTrue(r.blocks.any { it.code == "trip.passport.expiredBeforeTrip" })
        assertFalse(r.warnings.any { it.code == "trip.passport.expiredBeforeTrip" })
        assertFalse(r.canProceed)
    }

    @Test fun passportExpiredBeforeOwnCountryTripIsAllowedWithWarning() {
        val p = passport("p1", "FR", expiry = today.minusDays(10).toString())
        val r = addTrip(
            trip("t1", stop("s1", "FR", today.plusDays(10), today.plusDays(20))),
            docs = listOf(p), primaryId = "p1",
        )
        assertTrue(r.warnings.any { it.code == "trip.passport.expiredBeforeTrip" })
        assertTrue(r.canProceed)
    }

    @Test fun passportExpiredBeforeFreedomBlocTripIsAllowedWithWarning() {
        val w = WorldData(
            mapOf("FR" to Country("FR", "France"), "DE" to Country("DE", "Germany")),
            emptyMap(),
            listOf(Regime("eu", "EU", "freedom-of-movement", listOf("FR", "DE"))),
            emptyMap(),
            emptyMap(),
        )
        val p = passport("p1", "FR", expiry = today.minusDays(10).toString())
        val r = GuardEngine.evaluate(
            GuardAction.AddTrip(trip("t1", stop("s1", "DE", today.plusDays(10), today.plusDays(20)))),
            ctx(docs = listOf(p), w = w, primaryId = "p1"),
        )
        assertTrue(r.warnings.any { it.code == "trip.passport.expiredBeforeTrip" })
        assertTrue(r.canProceed)
    }

    @Test fun expiredOnlyPassportStillBlocksForeignTripViaFactory() {
        // The effective primary is null (no valid passport); the factory keeps the raw starred id
        // so the rule still evaluates the expired passport instead of silently doing nothing.
        val p = passport("p1", "FR", expiry = today.minusDays(10).toString())
        val r = GuardEngine.evaluate(
            GuardAction.AddTrip(trip("t1", stop("s1", "DE", today.plusDays(10), today.plusDays(20)))),
            GuardContext.of(listOf(p), emptyList(), world(), "p1", today),
        )
        assertTrue(r.blocks.any { it.code == "trip.passport.expiredBeforeTrip" })
        assertFalse(r.canProceed)
    }

    @Test fun expiredOnlyPassportStaysSavableForOwnCountryViaFactory() {
        val p = passport("p1", "FR", expiry = today.minusDays(10).toString())
        val r = GuardEngine.evaluate(
            GuardAction.AddTrip(trip("t1", stop("s1", "FR", today.plusDays(10), today.plusDays(20)))),
            GuardContext.of(listOf(p), emptyList(), world(), "p1", today),
        )
        assertTrue(r.warnings.any { it.code == "trip.passport.expiredBeforeTrip" })
        assertTrue(r.canProceed)
    }

    @Test fun factoryFallsBackToFirstPassportWhenNothingStarred() {
        val p = passport("p1", "FR", expiry = today.minusDays(10).toString())
        val r = GuardEngine.evaluate(
            GuardAction.AddTrip(trip("t1", stop("s1", "DE", today.plusDays(10), today.plusDays(20)))),
            GuardContext.of(listOf(p), emptyList(), world(), null, today),
        )
        assertTrue(r.blocks.any { it.code == "trip.passport.expiredBeforeTrip" })
        assertFalse(r.canProceed)
    }

    @Test fun expiredPassportOfSecondNationalityUnblocksOwnCountry() {
        // Two expired passports: the starred (evaluated) one is French, but the traveller's
        // German passport still covers Germany, so the trip stays savable.
        val pDe = passport("pDe", "DE", expiry = today.minusDays(30).toString())
        val pFr = passport("pFr", "FR", expiry = today.minusDays(10).toString())
        val r = GuardEngine.evaluate(
            GuardAction.AddTrip(trip("t1", stop("s1", "DE", today.plusDays(10), today.plusDays(20)))),
            GuardContext.of(listOf(pDe, pFr), emptyList(), world(), "pFr", today),
        )
        assertFalse(r.blocks.any { it.code == "trip.passport.expiredBeforeTrip" })
        assertTrue(r.warnings.any { it.code == "trip.passport.expiredBeforeTrip" })
        assertTrue(r.canProceed)
    }

    @Test fun removeTripAndEndTripAreAlwaysAccepted() {
        val open = trip("t0", stop("s0", "DE", today.minusDays(10), null))
        val rm = GuardEngine.evaluate(GuardAction.RemoveTrip("t0"), ctx(trips = listOf(open)))
        val end = GuardEngine.evaluate(GuardAction.EndTrip("t0"), ctx(trips = listOf(open)))
        assertTrue(rm.canProceed && rm.findings.isEmpty())
        assertTrue(end.canProceed && end.findings.isEmpty())
    }

    // ------------------------------------------------------------------
    // Batch G (P3-5..P3-11)
    // ------------------------------------------------------------------

    private val tenVisas = listOf(
        visa("v1", setOf("FR")), visa("v2", setOf("FR")), visa("v3", setOf("DE")),
        visa("v4", setOf("DE")), visa("v5", setOf("CA")), visa("v6", setOf("FR")),
        visa("v7", setOf("DE")), visa("v8", setOf("CA")), visa("v9", setOf("FR")),
        visa("v10", setOf("DE")),
    )

    @Test fun capAllowsEleventhOverlappingVisaWhenItSupersedesOne() {
        // P3-5: at the 10-valid-visa cap, an 11th visa that supersedes an existing one is allowed
        // (net stays 10), so the total cap must not block it.
        val r = add(visa("v11", setOf("FR")), existing = tenVisas)
        assertFalse(r.blocks.any { it.code == "doc.cap.visaTotal" })
        assertTrue(r.warnings.any { it.code == "doc.single.perCountry" })
    }

    @Test fun capStillBlocksEleventhNonOverlappingVisa() {
        // P3-5 (contrast): an 11th visa in a country with no existing visa frees no slot → blocked.
        val w = world(countries = mapOf(
            "FR" to Country("FR", "France"), "DE" to Country("DE", "Germany"),
            "CA" to Country("CA", "Canada"), "IT" to Country("IT", "Italy"),
        ))
        val r = GuardEngine.evaluate(GuardAction.AddDocument(visa("v11", setOf("IT"))), ctx(docs = tenVisas, w = w))
        assertTrue(r.blocks.any { it.code == "doc.cap.visaTotal" })
    }

    @Test fun multiCountryWarningNotReaskedWhenCountriesUnchanged() {
        // P3-6: editing the expiry of an existing multi-country visa (countries unchanged) must not
        // re-trigger the "are you sure" confirm; changing the countries still does.
        val original = visa("v", setOf("FR", "DE"))
        assertFalse(codes(update(visa("v", setOf("FR", "DE"), expiry = "2030-01-01"), original)).contains("doc.visa.multiCountry"))
        assertTrue(codes(update(visa("v", setOf("FR", "DE", "CA")), original)).contains("doc.visa.multiCountry"))
    }

    @Test fun passportExpiringOnTripStartDayIsFlagged() {
        // P3-8: a passport whose expiry equals the trip start day is flagged (during-trip), not silent.
        val p = passport("p", "FR", expiry = today.plusDays(30).toString())
        val t = trip("t", stop("s1", "CA", today.plusDays(30), today.plusDays(40), "p"))
        val r = GuardEngine.evaluate(GuardAction.AddTrip(t), ctx(docs = listOf(p), primaryId = "p"))
        assertTrue(codes(r).contains("trip.passport.expiresDuringTrip"))
    }

    @Test fun setPrimaryUnknownIdIsBlocked() {
        // P3-10: a ghost id is blocked rather than silently stored.
        val r = GuardEngine.evaluate(GuardAction.SetPrimary("ghost"), ctx(docs = listOf(passport("p", "FR"))))
        assertTrue(codes(r).contains("doc.primary.unknown"))
        assertFalse(r.canProceed)
    }

    @Test fun backdatedSupersessionDoesNotResurrectAnExpiredDoc() {
        // P3-11: a backdated validFrom must not turn an already-expired doc into a supersession.
        val old = visa("old", setOf("FR"), expiry = today.minusDays(30).toString())
        val candidate = visa("new", setOf("FR"), validFrom = today.minusDays(60).toString())
        val r = add(candidate, existing = listOf(old))
        assertFalse(r.mutations.any { it is GuardMutation.SupersedeDocument && it.oldId == "old" })
    }

    @Test fun supersessionStillAppliesToAValidOverlappingDoc() {
        // P3-11 (control): the clamp must not stop a still-valid overlapping doc from being superseded.
        val old = visa("old", setOf("FR"), expiry = "2030-01-01")
        val candidate = visa("new", setOf("FR"), validFrom = today.minusDays(60).toString())
        val r = add(candidate, existing = listOf(old))
        assertTrue(r.mutations.any { it is GuardMutation.SupersedeDocument && it.oldId == "old" })
    }

    // ------------------------------------------------------------------
    // P2-4: restore invariants (states the UI can never create)
    // ------------------------------------------------------------------

    @Test fun restoreFlagsTwoValidVisasForOneCountry() {
        val a = visa("a", setOf("FR"), expiry = "2028-01-01")
        val b = visa("b", setOf("FR"), expiry = "2029-01-01")
        val violations = GuardEngine.restoreViolations(ctx(docs = listOf(a, b)))
        assertEquals(1, violations.size)
        assertTrue("got=${violations.first()}", violations.first().contains("France"))
    }

    @Test fun restoreIsQuietWhenTheOlderIsSuperseded() {
        val a = visa("a", setOf("FR"), expiry = "2028-01-01")
        val b = visa("b", setOf("FR"), expiry = "2029-01-01").copy(supersededBy = "a")
        assertTrue(GuardEngine.restoreViolations(ctx(docs = listOf(a, b))).isEmpty())
    }

    @Test fun restoreIsQuietForDifferentCountries() {
        val a = visa("a", setOf("FR"), expiry = "2028-01-01")
        val b = visa("b", setOf("DE"), expiry = "2029-01-01")
        assertTrue(GuardEngine.restoreViolations(ctx(docs = listOf(a, b))).isEmpty())
    }

    @Test fun restoreIsQuietForTwoPassports() {
        // Passports are exempt from the one-document-per-country rule.
        val p1 = passport("p1", "FR", expiry = "2028-01-01")
        val p2 = passport("p2", "FR", expiry = "2029-01-01")
        assertTrue(GuardEngine.restoreViolations(ctx(docs = listOf(p1, p2))).isEmpty())
    }
}
