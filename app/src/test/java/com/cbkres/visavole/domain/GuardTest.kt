package com.cbkres.visavole.domain

import com.cbkres.visavole.data.Country
import com.cbkres.visavole.data.Holding
import com.cbkres.visavole.data.Regime
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
        number: String? = null,
        expiry: String? = null,
        validFrom: String? = null,
    ) = Document(id, "Passport · $iso", DocKind.Passport(iso), number, expiry, validFrom)

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
        null,
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
        null,
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
        val p1 = passport("p1", "FR", "123", "2028-01-01", "2018-01-01")
        val dup = passport("p2", "FR", "123", "2028-01-01", "2018-01-01")
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
        val p1 = passport("p1", "FR", "123", "2028-01-01")
        val t = trip("t1", stop("s1", "DE", LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 10), "p1"))
        val candidate = passport("p1", "FR", "456", "2029-01-01", "2019-01-01")
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
        return (1..n).map { i -> passport("p$i", isos[(i - 1) % 3], "N$i", "2028-01-01") }
    }

    @Test fun eleventhValidPassportIsBlocked() {
        // 10 valid passports (FR 3, DE 3, CA 4) so only the total cap, not the per-country one, fires.
        val existing = validPassports(9) + passport("pCA4", "CA", "NCA4", "2028-01-01")
        val candidate = passport("pFR4", "FR", "NFR4", "2028-01-01")
        val r = add(candidate, existing)
        assertFalse(r.canProceed)
        assertTrue("codes=${codes(r)}", codes(r).contains("doc.cap.passportTotal"))
        assertFalse("codes=${codes(r)}", codes(r).contains("doc.cap.passportPerCountry"))
    }

    @Test fun tenthValidPassportIsAllowed() {
        val existing = validPassports(9)
        val candidate = passport("pFR4", "FR", "NFR4", "2028-01-01")
        val r = add(candidate, existing)
        assertTrue("codes=${codes(r)}", r.canProceed)
        assertFalse(codes(r).contains("doc.cap.passportTotal"))
    }

    @Test fun expiredPassportDoesNotCountTowardsCap() {
        // 9 valid + 1 expired FR: counting the expired one would reach the per-country cap (4).
        val existing = validPassports(9) + passport("pX", "FR", "NX", "2025-01-01")
        val candidate = passport("pFR4", "FR", "NFR4", "2028-01-01")
        val r = add(candidate, existing)
        assertTrue("codes=${codes(r)}", r.canProceed)
    }

    @Test fun fifthPassportForCountryIsBlocked() {
        val existing = (1..4).map { passport("p$it", "FR", "N$it", "2028-01-01") } +
            (1..3).map { passport("d$it", "DE", "N$it", "2028-01-01") }
        val candidate = passport("p5", "FR", "N5", "2028-01-01")
        val r = add(candidate, existing)
        assertFalse(r.canProceed)
        assertTrue("codes=${codes(r)}", codes(r).contains("doc.cap.passportPerCountry"))
    }

    @Test fun eleventhValidVisaIsBlocked() {
        val existing = (1..10).map {
            visa("v$it", setOf("CA"), "multiple", "2027-0${if (it < 10) it + 1 else 1}-01")
        }
        val candidate = visa("v11", setOf("CA"), "multiple", "2027-12-01")
        val r = add(candidate, existing)
        assertFalse(r.canProceed)
        assertTrue("codes=${codes(r)}", codes(r).contains("doc.cap.visaTotal"))
    }

    @Test fun eleventhValidResidenceIsBlocked() {
        val existing = (1..10).map {
            residence("r$it", setOf("CA"), "2027-0${if (it < 10) it + 1 else 1}-01")
        }
        val candidate = residence("r11", setOf("CA"), "2027-12-01")
        val r = add(candidate, existing)
        assertFalse(r.canProceed)
        assertTrue("codes=${codes(r)}", codes(r).contains("doc.cap.residenceTotal"))
    }

    @Test fun expiredCandidateIsNotSubjectToCaps() {
        val existing = validPassports(10)
        val candidate = passport("pX", "FR", "NX", "2025-01-01")
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

    @Test fun passportsAreNotSubjectToSinglePerCountry() {
        val p1 = passport("p1", "FR", "123", "2028-01-01")
        val p2 = passport("p2", "FR", "456", "2028-01-01")
        val r = add(p2, listOf(p1))
        assertTrue("codes=${codes(r)}", r.canProceed)
        assertTrue(r.mutations.isEmpty())
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
        val map = mapOf(
            "No stops" to "trip.noStops",
            "Unknown country" to "trip.unknownCountry",
            "Departure before arrival" to "trip.departureBeforeArrival",
            "Missing departure" to "trip.missingDeparture",
            "Gap between stops" to "trip.gap",
            "Trip overlap" to "trip.overlap",
            "Allowance exceeded" to "trip.allowance.exceeded",
            "Low allowance" to "trip.allowance.low",
            "Not enough entries" to "trip.entries.over",
            "Last entry used" to "trip.entries.last",
            "No valid entry grant" to "trip.access.none",
            "Entry blocked" to "trip.access.blocked",
            "Extra step required" to "trip.access.extraStep",
        )
        for (t in corpus) {
            val others = corpus.filter { it.id != t.id }
            val legacy = TripModel.validateTrip(t, w, today) +
                TripModel.gapWarningsFor(t, today) +
                TripModel.overlapWarningsFor(t, others, docs, w, today) +
                TripModel.projectionWarningsFor(t, others, docs, w, today)
            val r = GuardEngine.evaluate(GuardAction.AddTrip(t), ctx(docs = docs, trips = corpus))
            val engineCodes = r.findings
                .map { it.code }
                .filter { it in map.values }
                .toSortedSet()
            val legacyCodes = legacy.map { map.getValue(it.title) }.toSortedSet()
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

    @Test fun passportExpiredBeforeForeignTripIsBlocked() {
        val p = passport("p1", "FR", expiry = today.minusDays(10).toString())
        val r = addTrip(
            trip("t1", stop("s1", "DE", today.plusDays(10), today.plusDays(20))),
            docs = listOf(p), primaryId = "p1",
        )
        assertTrue(r.blocks.any { it.code == "trip.passport.expiredBeforeTrip" })
        assertTrue(r.warnings.any { it.code == "trip.passport.expiredBeforeTrip" })
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

    @Test fun removeTripAndEndTripAreAlwaysAccepted() {
        val open = trip("t0", stop("s0", "DE", today.minusDays(10), null))
        val rm = GuardEngine.evaluate(GuardAction.RemoveTrip("t0"), ctx(trips = listOf(open)))
        val end = GuardEngine.evaluate(GuardAction.EndTrip("t0"), ctx(trips = listOf(open)))
        assertTrue(rm.canProceed && rm.findings.isEmpty())
        assertTrue(end.canProceed && end.findings.isEmpty())
    }
}
