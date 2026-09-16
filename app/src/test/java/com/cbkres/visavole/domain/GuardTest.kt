package com.cbkres.visavole.domain

import com.cbkres.visavole.data.Country
import com.cbkres.visavole.data.Holding
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
    ) = GuardContext(docs, trips, w, today)

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
        assertEquals(listOf(GuardMutation.ExpireDocument("v1", LocalDate.of(2026, 10, 1))), r.mutations)
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
        assertEquals(listOf(GuardMutation.ExpireDocument("v1", today)), r.mutations)
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
        assertEquals(listOf(GuardMutation.ExpireDocument("v1", LocalDate.of(2027, 2, 1))), r.mutations)
    }

    @Test fun passportsAreNotSubjectToSinglePerCountry() {
        val p1 = passport("p1", "FR", "123", "2028-01-01")
        val p2 = passport("p2", "FR", "456", "2028-01-01")
        val r = add(p2, listOf(p1))
        assertTrue("codes=${codes(r)}", r.canProceed)
        assertTrue(r.mutations.isEmpty())
    }

    // ------------------------------------------------------------------
    // doc.home.*
    // ------------------------------------------------------------------

    @Test fun unknownHomeCountryIsBlocked() {
        val r = GuardEngine.evaluate(GuardAction.SetHome("XX"), ctx())
        assertFalse(r.canProceed)
        assertTrue(codes(r).contains("doc.home.unknown"))
    }

    @Test fun duplicateHomePassportIsBlocked() {
        val p1 = passport("p1", "FR", "123", "2028-01-01")
        val r = GuardEngine.evaluate(GuardAction.SetHome("FR"), ctx(docs = listOf(p1)))
        assertFalse(r.canProceed)
        val f = r.findings.firstOrNull { it.code == "doc.home.duplicate" }
        assertNotNull(f)
        assertEquals("You already have a passport for France. Remove or edit it first.", f!!.message)
    }

    @Test fun freshHomeCountryIsAllowed() {
        val p1 = passport("p1", "FR", "123", "2028-01-01")
        val r = GuardEngine.evaluate(GuardAction.SetHome("DE"), ctx(docs = listOf(p1)))
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
    // Trip actions (stubs until the trip-side phase)
    // ------------------------------------------------------------------

    @Test fun tripActionsAreAcceptedForNow() {
        val t = trip("t1", stop("s1", "DE", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 15)))
        val addR = GuardEngine.evaluate(GuardAction.AddTrip(t), ctx())
        val updR = GuardEngine.evaluate(GuardAction.UpdateTrip(t), ctx())
        val rmR = GuardEngine.evaluate(GuardAction.RemoveTrip("t1"), ctx())
        val endR = GuardEngine.evaluate(GuardAction.EndTrip("t1"), ctx())
        for (r in listOf(addR, updR, rmR, endR)) {
            assertTrue(r.canProceed)
            assertTrue(r.findings.isEmpty())
            assertTrue(r.mutations.isEmpty())
        }
    }
}
