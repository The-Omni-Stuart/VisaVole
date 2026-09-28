package com.cbkres.visavole.domain

import com.cbkres.visavole.data.Benefit
import com.cbkres.visavole.data.Corridor
import com.cbkres.visavole.data.Country
import com.cbkres.visavole.data.Regime
import com.cbkres.visavole.data.StayRule
import com.cbkres.visavole.data.WorldData
import com.cbkres.visavole.data.Holding as DataHolding
import com.cbkres.visavole.domain.DocKind.Custom
import com.cbkres.visavole.domain.DocKind.Holding
import com.cbkres.visavole.domain.DocKind.Passport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class TripModelTest {

    private val today = LocalDate.of(2026, 9, 13)

    private fun world(
        countries: Map<String, Country> = mapOf(
            "DE" to Country("DE", "Germany"),
            "FR" to Country("FR", "France"),
            "CA" to Country("CA", "Canada"),
            "US" to Country("US", "United States"),
        ),
        baseline: Map<String, List<Corridor>> = emptyMap(),
        regimes: List<Regime> = emptyList(),
        holdings: Map<String, DataHolding> = emptyMap(),
        benefits: Map<String, List<Benefit>> = emptyMap(),
        stayRules: List<StayRule> = emptyList(),
    ) = WorldData(countries, baseline, regimes, holdings, benefits, stayRules)

    private val schengen = StayRule(
        "Schengen Area",
        setOf("DE", "FR"),
        "rolling",
        90,
        180,
        true,
        setOf("*"),
        null,
        id = "schengen",
        zoneId = "schengen",
    )

    private val ca4 = StayRule(
        "CA-4",
        setOf("CA"),
        "per-entry",
        180,
        null,
        true,
        setOf("*"),
        null,
        id = "ca4",
        zoneId = "ca4",
    )

    private fun passport(iso2: String) = Document("p-$iso2", iso2, Passport(iso2))

    private fun stop(id: String, country: String, arrival: LocalDate, departure: LocalDate?, documentId: String? = null) =
        TripStop(id, country, arrival, departure, documentId)

    private fun trip(id: String, vararg stops: TripStop, note: String? = null) = Trip(id, stops.toList(), note)

    private fun schengenDoc() = Document("v", "Visa", Holding("x", "single"), "2030-01-01")

    private fun schengenWorld(holdings: Map<String, DataHolding> = mapOf("x" to DataHolding("x", "X Visa", "short_term_visa", "US"))) =
        world(holdings = holdings, stayRules = listOf(schengen, ca4))

    private fun allowance(calc: TripCalculation, key: String) = calc.allowances.first { it.key == key }

    @Test
    fun rollingWindowCountsUniqueDays() {
        val trips = listOf(
            trip("t1", stop("s1", "DE", today.minusDays(12), today.minusDays(3))), // 10 days
            trip("t2", stop("s2", "FR", today.minusDays(2), null)), // 3 days through today
        )
        val calc = TripModel.calculate(trips, listOf(passport("GB")), world(stayRules = listOf(schengen)), today)
        val a = allowance(calc, "stay:schengen")
        assertEquals(13, a.usedDays)
        assertEquals(77, a.remainingDays)
        assertEquals("t2", a.currentTripId)
    }

    @Test
    fun freedomAndResidenceDaysAreNotCounted() {
        val trips = listOf(trip("t1", stop("s1", "DE", today.minusDays(4), today)))
        val calc = TripModel.calculate(trips, listOf(passport("DE")), world(stayRules = listOf(schengen)), today)
        val a = allowance(calc, "stay:schengen")
        assertEquals(0, a.usedDays)
    }

    @Test
    fun overlappingTripsCountUniqueCalendarDaysOnly() {
        val trips = listOf(
            trip("t1", stop("s1", "DE", today.minusDays(12), today.minusDays(3))),
            trip("t2", stop("s2", "DE", today.minusDays(6), today.plusDays(7))),
        )
        val calc = TripModel.calculate(trips, listOf(passport("GB")), world(stayRules = listOf(schengen)), today.plusDays(7))
        val a = allowance(calc, "stay:schengen")
        assertEquals(20, a.usedDays)
    }

    @Test
    fun openTripCountsThroughToday() {
        val trips = listOf(trip("t1", stop("s1", "DE", today, null)))
        val calc = TripModel.calculate(trips, listOf(passport("GB")), world(stayRules = listOf(schengen)), today)
        assertEquals(1, allowance(calc, "stay:schengen").usedDays)
    }

    @Test
    fun perEntryUsageCountsOnlyTheActiveEntry() {
        val trips = listOf(
            trip("t1", stop("s1", "CA", today.minusDays(30), today.minusDays(26))),
            trip("t2", stop("s2", "CA", today.minusDays(3), null)),
        )
        val calc = TripModel.calculate(trips, listOf(passport("GB")), world(stayRules = listOf(ca4)), today)
        val a = allowance(calc, "stay:ca4")
        assertEquals(4, a.usedDays)
        assertEquals(176, a.remainingDays)
    }

    @Test
    fun perEntryUsageResetsAfterAGap() {
        val trips = listOf(trip("t1", stop("s1", "CA", today.minusDays(30), today.minusDays(26))))
        val calc = TripModel.calculate(trips, listOf(passport("GB")), world(stayRules = listOf(ca4)), today)
        assertEquals(0, allowance(calc, "stay:ca4").usedDays)
    }

    @Test
    fun overstayIsReported() {
        val trips = listOf(trip("t1", stop("s1", "DE", today.minusDays(90), today)))
        val calc = TripModel.calculate(trips, listOf(passport("GB")), world(stayRules = listOf(schengen)), today)
        val a = allowance(calc, "stay:schengen")
        assertEquals(91, a.usedDays)
        assertEquals(1, a.overstayDays)
        assertEquals(AllowanceStatus.DANGER, a.status)
    }

    private fun gateVisa(expiry: String) =
        Document("v", "Visa", Custom(setOf("DE", "FR"), kind = "visa"), expiry = expiry)

    @Test
    fun zoneRemainingIsCappedByGateDocumentExpiry() {
        val visa = gateVisa("2026-09-20") // 7 days out
        val trips = listOf(trip("t1", stop("s1", "DE", today.minusDays(12), today.minusDays(3)))) // 10 days used
        val calc = TripModel.calculate(trips, listOf(passport("GB"), visa), world(stayRules = listOf(schengen)), today)
        val a = allowance(calc, "stay:schengen")
        assertEquals(90, a.totalDays)
        assertEquals(7, a.remainingDays)
        assertEquals(83, a.usedDays)
        assertEquals(AllowanceStatus.WARNING, a.status)
    }

    @Test
    fun inUseSingleEntryCapsZoneAtDocumentDateExpiry() {
        val visa = Document("v", "Visa", Custom(setOf("DE", "FR"), kind = "visa", entryType = "single"), expiry = "2026-09-30")
        val trips = listOf(trip("t1", stop("s1", "DE", today.minusDays(2), null, "v"))) // in use: 3 days
        val calc = TripModel.calculate(trips, listOf(passport("GB"), visa), world(stayRules = listOf(schengen)), today)
        val a = allowance(calc, "stay:schengen")
        assertEquals(17, a.remainingDays)
        assertEquals(73, a.usedDays)
        assertEquals(AllowanceStatus.OK, a.status)
    }

    @Test
    fun inUseSingleEntryDocumentShowsPrintedExpiry() {
        val visa = Document("v", "Visa", Custom(setOf("DE", "FR"), kind = "visa", entryType = "single"), expiry = "2026-09-30")
        val trips = listOf(trip("t1", stop("s1", "DE", today.minusDays(2), null, "v")))
        val calc = TripModel.calculate(trips, listOf(passport("GB"), visa), world(stayRules = listOf(schengen)), today)
        val a = calc.allowances.first { it.key == "doc:v" }
        assertEquals(LocalDate.of(2026, 9, 30), a.effectiveExpiry)
        assertEquals(17, a.remainingDays)
        assertEquals(AllowanceStatus.WARNING, a.status)
    }

    @Test
    fun zoneNotCappedWhenGateDocumentExpiryIsFar() {
        val visa = gateVisa("2030-01-01")
        val trips = listOf(
            trip("t1", stop("s1", "DE", today.minusDays(12), today.minusDays(3))), // 10 days
            trip("t2", stop("s2", "FR", today.minusDays(2), null)), // 3 days
        )
        val calc = TripModel.calculate(trips, listOf(passport("GB"), visa), world(stayRules = listOf(schengen)), today)
        val a = allowance(calc, "stay:schengen")
        assertEquals(77, a.remainingDays)
        assertEquals(13, a.usedDays)
    }

    @Test
    fun lapsedGateDocumentCapsZoneIntoDanger() {
        val visa = gateVisa("2026-09-10") // 3 days past
        val trips = listOf(trip("t1", stop("s1", "DE", today.minusDays(12), today.minusDays(3)))) // 10 days used
        val calc = TripModel.calculate(trips, listOf(passport("GB"), visa), world(stayRules = listOf(schengen)), today)
        val a = allowance(calc, "stay:schengen")
        assertEquals(AllowanceStatus.DANGER, a.status)
        assertEquals(0, a.remainingDays)
        assertEquals(90, a.usedDays)
    }

    @Test
    fun lapsedDeclaredGateCapsZoneEvenWhenPassportCoversCountry() {
        // Regression: a stay runs on the document its stop declares. The passport covers both
        // zone countries visa-free, so the best-document fallback shadows the lapsed visa and
        // the zone would show OK — but the declared (lapsed) visa is what the stay is on.
        val visa = gateVisa("2026-09-10") // 3 days past
        val w = world(
            baseline = mapOf(
                "GB" to listOf(
                    Corridor("GB", "DE", "visa-free", 90),
                    Corridor("GB", "FR", "visa-free", 90),
                ),
            ),
            stayRules = listOf(schengen),
        )
        val trips = listOf(trip("t1", stop("s1", "DE", today.minusDays(5), null, "v"))) // in use, on the visa
        val calc = TripModel.calculate(trips, listOf(passport("GB"), visa), w, today)
        val a = allowance(calc, "stay:schengen")
        assertEquals(AllowanceStatus.DANGER, a.status)
        assertEquals(0, a.remainingDays)
    }

    @Test
    fun pastTripDeclaredDocDoesNotCapCurrentZone() {
        // Regression (P1-5): a document declared on a PAST stay must not cap today's zone. The
        // past trip exhausted single-entry visa V; the current open stay is on the passport,
        // which covers the zone visa-free — the ring must show the full window, not 0/DANGER.
        val visa = Document("v", "Visa", Custom(setOf("DE", "FR"), kind = "visa", entryType = "single"))
        val w = world(
            baseline = mapOf(
                "GB" to listOf(
                    Corridor("GB", "DE", "visa-free", 90),
                    Corridor("GB", "FR", "visa-free", 90),
                ),
            ),
            stayRules = listOf(schengen),
        )
        val trips = listOf(
            trip("t1", stop("s1", "DE", today.minusDays(30), today.minusDays(20), "v")), // past stay, exhausted
            trip("t2", stop("s2", "DE", today.minusDays(2), null)),                       // open stay, on passport
        )
        val calc = TripModel.calculate(trips, listOf(passport("GB"), visa), w, today)
        val a = allowance(calc, "stay:schengen")
        assertEquals(AllowanceStatus.OK, a.status)
        assertEquals(76, a.remainingDays) // 90 - 11 past days - 3 current days
    }

    @Test
    fun inProgressEntryWithoutDateExpiryKeepsFullWindow() {
        // Regression (P1-6): a single-entry visa with NO date expiry, mid-entry, must not cap
        // the zone at 0 — exhaustion has not happened, the entry count limits re-entries only.
        val visa = Document("v", "Visa", Custom(setOf("DE", "FR"), kind = "visa", entryType = "single"))
        val w = world(stayRules = listOf(schengen))
        val trips = listOf(trip("t1", stop("s1", "DE", today.minusDays(2), null, "v")))
        val calc = TripModel.calculate(trips, listOf(passport("GB"), visa), w, today)
        val a = allowance(calc, "stay:schengen")
        assertEquals(AllowanceStatus.OK, a.status)
        assertEquals(87, a.remainingDays) // 90 - 3 days used
    }

    @Test
    fun residenceDocumentGetsItsOwnAllowanceSnapshot() {
        val residence = Document("r", "Residence", Custom(setOf("FR"), kind = "residence"), expiry = "2026-12-31")
        val calc = TripModel.calculate(emptyList(), listOf(passport("GB"), residence), world(stayRules = listOf(schengen)), today)
        val a = calc.allowances.first { it.key == "doc:r" }
        assertEquals(AllowanceKind.DOCUMENT_VALIDITY, a.kind)
        assertEquals(LocalDate.of(2026, 12, 31), a.effectiveExpiry)
        assertEquals(109, a.remainingDays)
        assertEquals(AllowanceStatus.OK, a.status)
    }

    @Test
    fun singleEntryExhaustionDerivesEffectiveExpiry() {
        val trips = listOf(trip("t1", stop("s1", "US", today.minusDays(12), today.minusDays(8), "v")))
        val calc = TripModel.calculate(trips, listOf(schengenDoc()), schengenWorld(), today)
        val e = calc.entryStatus["v"]!!
        assertEquals(1, e.total)
        assertEquals(1, e.used)
        assertEquals(0, e.remaining)
        assertEquals(EntryState.EXPIRED, e.state)
        assertEquals(today.minusDays(8), e.effectiveExpiry)
    }

    @Test
    fun doubleEntryUsageCountsSeparateEntries() {
        val doc = Document("v", "Visa", Holding("x", "double"), "2030-01-01")
        val trips = listOf(
            trip("t1", stop("s1", "US", today.minusDays(40), today.minusDays(36), "v")),
            trip("t2", stop("s2", "US", today.minusDays(12), today.minusDays(8), "v")),
        )
        val calc = TripModel.calculate(trips, listOf(doc), schengenWorld(), today)
        val e = calc.entryStatus["v"]!!
        assertEquals(2, e.used)
        assertEquals(0, e.remaining)
        assertEquals(EntryState.EXPIRED, e.state)
    }

    @Test
    fun contiguousStopsUseTheSameEntry() {
        val doc = Document("v", "Visa", Holding("x", "single"), "2030-01-01")
        val trips = listOf(
            trip("t1", stop("s1", "US", today.minusDays(40), today.minusDays(36), "v")),
            trip("t2", stop("s2", "US", today.minusDays(35), today.minusDays(31), "v")),
        )
        val calc = TripModel.calculate(trips, listOf(doc), schengenWorld(), today)
        assertEquals(1, calc.entryStatus["v"]!!.used)
    }

    @Test
    fun futureTripDoesNotConsumeEntries() {
        val trips = listOf(trip("t1", stop("s1", "US", today.plusDays(18), today.plusDays(22), "v")))
        val calc = TripModel.calculate(trips, listOf(schengenDoc()), schengenWorld(), today)
        assertEquals(0, calc.entryStatus["v"]!!.used)
        assertEquals(EntryState.AVAILABLE, calc.entryStatus["v"]!!.state)
    }

    @Test
    fun effectiveDocsShortenExhaustedVisas() {
        val trips = listOf(trip("t1", stop("s1", "US", today.minusDays(12), today.minusDays(8), "v")))
        val docs = listOf(schengenDoc())
        val effective = TripModel.effectiveDocs(docs, trips, schengenWorld(), today)
        assertEquals(today.minusDays(8).toString(), effective.first { it.id == "v" }.expiry)
    }

    @Test
    fun sectionsClassifyTrips() {
        val trips = listOf(
            trip("past", stop("s1", "DE", today.minusDays(30), today.minusDays(20))),
            trip("ongoing", stop("s1", "DE", today.minusDays(3), null)),
            trip("upcoming", stop("s1", "DE", today.plusDays(3), today.plusDays(10))),
        )
        val sections = TripModel.sections(trips, today)
        assertEquals(listOf("past"), sections.previous.map { it.id })
        assertEquals(listOf("ongoing"), sections.current.map { it.id })
        assertEquals(listOf("upcoming"), sections.upcoming.map { it.id })
    }

    @Test
    fun overlappingSameCountryTripsWarn() {
        val candidate = trip("c", stop("s1", "DE", today.plusDays(10), today.plusDays(20)))
        val existing = listOf(trip("e", stop("s1", "DE", today.plusDays(15), today.plusDays(30))))
        val warnings = TripModel.overlapWarningsFor(candidate, existing, listOf(passport("GB")), world(stayRules = listOf(schengen)), today)
        assertTrue(warnings.any { it.title == "Trip overlap" })
    }

    @Test
    fun projectionWarnsWhenAllowanceWouldBeExceeded() {
        val candidate = trip("c", stop("s1", "DE", today.minusDays(5), today.plusDays(90)))
        val warnings = TripModel.projectionWarningsFor(candidate, emptyList(), listOf(passport("GB")), world(stayRules = listOf(schengen)), today)
        assertTrue(warnings.any { it.severity == WarningSeverity.DANGER && it.title == "Allowance exceeded" })
    }

    @Test
    fun projectionWarnsWhenNotEnoughEntries() {
        val candidate = trip(
            "c",
            stop("s1", "US", today.plusDays(10), today.plusDays(14), "v"),
            stop("s2", "US", today.plusDays(30), today.plusDays(34), "v"),
        )
        val warnings = TripModel.projectionWarningsFor(candidate, emptyList(), listOf(schengenDoc()), schengenWorld(), today)
        assertTrue(warnings.any { it.severity == WarningSeverity.DANGER && it.title == "Not enough entries" })
    }

    @Test
    fun primaryAllowancePrefersCurrentTrip() {
        val current = AllowanceSnapshot("stay:schengen", "Schengen", null, AllowanceKind.ROLLING, AllowanceStatus.WARNING, 83, 7, 90, 180, 0, null, null, null, null, "t1", listOf("t1"))
        val other = AllowanceSnapshot("doc:v", "Visa", null, AllowanceKind.ENTRY_COUNT, AllowanceStatus.WARNING, null, null, null, null, 0, 1, 1, 0, null, null, emptyList())
        val primary = TripModel.primaryAllowance(listOf(other, current))
        assertEquals("stay:schengen", primary?.key)
    }

    @Test
    fun sortedStopsOrdersByArrival() {
        val stops = listOf(
            stop("s2", "FR", today.plusDays(5), null),
            stop("s1", "DE", today, today.plusDays(4)),
        )
        assertEquals(listOf("s1", "s2"), TripModel.sortedStops(stops).map { it.id })
    }

    @Test
    fun firstGapIndexIsNullForContinuousStops() {
        val stops = listOf(
            stop("s1", "DE", today, today.plusDays(4)),
            stop("s2", "FR", today.plusDays(5), null),
        )
        assertNull(TripModel.firstGapIndex(stops))
    }

    @Test
    fun firstGapIndexFindsTheStopAfterTheGap() {
        val stops = listOf(
            stop("s1", "DE", today, today.plusDays(4)),
            stop("s2", "FR", today.plusDays(7), null),
        )
        assertEquals(1, TripModel.firstGapIndex(stops))
    }

    @Test
    fun gapWarningsDescribeTheNumberOfGapDays() {
        val t = trip(
            "t",
            stop("s1", "DE", today, today),
            stop("s2", "FR", today.plusDays(3), null),
        )
        val warnings = TripModel.gapWarningsFor(t, today)
        assertEquals(1, warnings.size)
        assertEquals(WarningSeverity.WARNING, warnings.first().severity)
        assertEquals("Gap between stops", warnings.first().title)
        assertTrue(warnings.first().message.contains("2-day gap"))
    }

    @Test
    fun shortStaySuppressionAppliesOnlyToFreedomAndResidence() {
        assertTrue(TripModel.isShortStaySuppressed(Access(AccessLevel.FREEDOM, null, "freedom")))
        assertTrue(TripModel.isShortStaySuppressed(Access(AccessLevel.RESIDENCE, null, "residence")))
        assertTrue(!TripModel.isShortStaySuppressed(Access(AccessLevel.VISA_FREE, 90, "visa free")))
        assertTrue(!TripModel.isShortStaySuppressed(null))
    }

    @Test
    fun stayRuleDisplayNameFallsBackWhenZoneNameIsBlank() {
        val named = StayRule("Schengen Area", setOf("FR"), "rolling", 90, 180, true, setOf("*"), null, zoneId = "schengen")
        val unnamed = StayRule("", setOf("US"), "per-entry", 90, null, true, setOf("*"), null, zoneId = "us")

        assertEquals("Schengen Area", named.displayName)
        assertEquals("us", unnamed.displayName)
    }

    @Test
    fun allowanceWarningUsesSingularDayForOneDayOverstay() {
        val candidate = trip("c", stop("s1", "DE", today.minusDays(5), today.plusDays(85)))
        val warnings = TripModel.projectionWarningsFor(candidate, emptyList(), listOf(passport("GB")), world(stayRules = listOf(schengen)), today)

        val warning = warnings.first { it.title == "Allowance exceeded" }
        assertTrue(warning.message.contains("by 1 day"))
    }

    @Test
    fun projectionWarnsWhenNoValidEntryGrantExists() {
        val candidate = trip("c", stop("s1", "CA", today.plusDays(10), today.plusDays(14)))
        val world = world(
            baseline = mapOf(
                "GB" to listOf(
                    Corridor("GB", "CA", "visa-required", null)
                )
            ),
            stayRules = listOf(schengen),
        )
        val warnings = TripModel.projectionWarningsFor(candidate, emptyList(), listOf(passport("GB")), world, today)

        val blocked = warnings.first { it.title == "Entry blocked" }
        assertTrue(blocked.message.contains("visa required"))
    }

    @Test
    fun closeTripAppendsDepartureToFinalOpenStop() {
        val original = trip("t", stop("s1", "DE", today.minusDays(3), today), stop("s2", "FR", today.plusDays(1), null))

        val closed = TripModel.closeTrip(original, today.plusDays(4))

        assertEquals(today.plusDays(4), closed.stops.first { it.id == "s2" }.departure)
        assertEquals(today, closed.stops.first { it.id == "s1" }.departure)
        assertFalse(closed.isOpen)
    }

    @Test
    fun closeTripClampsDepartureBeforeFinalArrival() {
        val original = trip("t", stop("s1", "DE", today.plusDays(2), null))

        val closed = TripModel.closeTrip(original, today.minusDays(1))

        assertEquals(today.plusDays(2), closed.stops.first().departure)
    }

    @Test
    fun closeTripDoesNotExtendAlreadyClosedFinalStop() {
        // P2-12: a final stop already closed with an EARLIER departure is kept — extending
        // recorded history would silently inflate the country's zone usage. Truncating to an
        // earlier close date is allowed.
        val original = trip(
            "t",
            stop("s1", "DE", today.minusDays(10), today.minusDays(8)),
            stop("s2", "FR", today.minusDays(6), today.minusDays(4)),
        )
        val extended = TripModel.closeTrip(original, today)
        assertEquals(today.minusDays(4), extended.stops.first { it.id == "s2" }.departure)
        val truncated = TripModel.closeTrip(original, today.minusDays(5))
        assertEquals(today.minusDays(5), truncated.stops.first { it.id == "s2" }.departure)
    }

    @Test
    fun splitStopsKeepsLeftHalfSortedAndRightHalfRemaining() {
        val stops = listOf(
            stop("s1", "DE", today.plusDays(10), today.plusDays(12)),
            stop("s2", "CA", today.plusDays(20), today.plusDays(30)),
            stop("s3", "FR", today.plusDays(31), today.plusDays(40)),
        )

        val (left, right) = TripModel.splitStops(stops, 1, today.plusDays(12))

        assertEquals(listOf("s1"), left.map { it.id })
        assertEquals(listOf("s2", "s3"), right.map { it.id })
        assertEquals(today.plusDays(12), left.first().departure)
    }

    @Test
    fun splitStopsClampsMissingDepartureInLeftHalf() {
        val stops = listOf(
            stop("s1", "DE", today.plusDays(10), null),
            stop("s2", "CA", today.plusDays(20), today.plusDays(30)),
        )

        val (left, right) = TripModel.splitStops(stops, 1, today.plusDays(12))

        assertEquals(today.plusDays(12), left.first().departure)
        assertEquals(listOf("s2"), right.map { it.id })
    }

    @Test
    fun rollingWindowDropsStaysOutsideLookback() {
        // P2-36: pin the 180-day lookback boundary — a stay that ended well outside the window
        // counts nothing, and one day at exactly the window start (today-179) still counts.
        // A lookback off-by-one flips one of these two.
        val outside = trip("t1", stop("s1", "DE", today.minusDays(200), today.minusDays(190))) // 11 days, outside
        val boundary = trip("t2", stop("s2", "FR", today.minusDays(179), today.minusDays(179))) // 1 day, inside
        val calc = TripModel.calculate(listOf(outside, boundary), listOf(passport("GB")), world(stayRules = listOf(schengen)), today)
        val a = allowance(calc, "stay:schengen")
        assertEquals(1, a.usedDays)
        assertEquals(89, a.remainingDays)
    }

    @Test
    fun projectionWarnsWhenAllowanceWouldGetLow() {
        // P2-37: trip.allowance.low — the candidate does not exceed the window, but only
        // ≤7 days remain after it.
        val candidate = trip("c", stop("s1", "DE", today.plusDays(5), today.plusDays(91))) // 87 of the 90 days
        val warnings = TripModel.projectionWarningsFor(candidate, emptyList(), listOf(passport("GB")), world(stayRules = listOf(schengen)), today)
        val w = warnings.first { it.code == "trip.allowance.low" }
        assertEquals(WarningSeverity.WARNING, w.severity)
        assertTrue(w.message.contains("3 Schengen Area days left"))
    }

    @Test
    fun projectionWarnsWhenCandidateUsesLastEntry() {
        // P2-37: trip.entries.last — the candidate's own future stop consumes the document's
        // final entry (remaining hits 0, not negative).
        val v = Document("v", "Visa", Custom(setOf("US"), null, "visa", null, "double"), "2030-01-01")
        val past = trip("t1", stop("s1", "US", today.minusDays(30), today.minusDays(25), "v"))
        val candidate = trip("c", stop("s2", "US", today.plusDays(10), today.plusDays(14), "v"))
        val warnings = TripModel.projectionWarningsFor(candidate, listOf(past), listOf(v), world(), today)
        val w = warnings.first { it.code == "trip.entries.last" }
        assertEquals(WarningSeverity.WARNING, w.severity)
        assertTrue(w.message.contains("last Visa entry"))
    }

    @Test
    fun projectionCountsSeparatedFutureStopOnOpenStayAsReEntry() {
        // P2-13: a single-entry visa with an open in-progress stay plus the candidate's separated
        // future stop on the same doc is a re-entry — 2 entries against 1 → DANGER
        // "Not enough entries" (the old merge-into-the-open-entry logic reported
        // WARNING "Last entry used" instead).
        val v = Document("v", "Visa", Custom(setOf("AT"), null, "visa", null, "single"), "2030-01-01")
        val inProgress = trip("t1", stop("s1", "AT", today.minusDays(5), null, "v"))
        val candidate = trip("t2", stop("s2", "AT", today.plusDays(10), null, "v"))
        val warnings = TripModel.projectionWarningsFor(candidate, listOf(inProgress), listOf(v), world(), today)
        assertTrue(warnings.any { it.code == "trip.entries.over" && it.severity == WarningSeverity.DANGER })
    }

    @Test
    fun projectionWarnsWhenCountryNeedsExtraStep() {
        // P2-37: trip.access.extraStep — a destination needing an e-visa/ETA on the trip dates
        // (a warning, not a block).
        val w = world(
            countries = mapOf("AU" to Country("AU", "Australia")),
            baseline = mapOf("GB" to listOf(Corridor("GB", "AU", "e-visa", 90))),
        )
        val candidate = trip("c", stop("s1", "AU", today.plusDays(10), today.plusDays(14)))
        val warnings = TripModel.projectionWarningsFor(candidate, emptyList(), listOf(passport("GB")), w, today)
        val extra = warnings.first { it.code == "trip.access.extraStep" }
        assertEquals(WarningSeverity.WARNING, extra.severity)
        assertTrue(extra.message.contains("e-visa"))
    }

    @Test
    fun expiredPassportNationalityDoesNotDriveStayRule() {
        // P2-30: the traveller holds a valid GB passport and an expired FR passport, staying in a
        // neutral country (DE) with no document declared. The stay rule must resolve on the valid
        // GB nationality's 60-day rule — the expired FR nationality must not pull in the FR-keyed
        // 90-day rule. (DE, not FR: an FR stay is suppressed as a home-country stay regardless.)
        val gbRule = StayRule("GB-60", setOf("DE"), "per-entry", 60, null, true, setOf("GB"), null, id = "gb60", zoneId = "gb60")
        val frRule = StayRule("FR-90", setOf("DE"), "per-entry", 90, null, true, setOf("FR"), null, id = "fr90", zoneId = "fr90")
        val w = world(stayRules = listOf(gbRule, frRule))
        val trips = listOf(trip("t1", stop("s1", "DE", today.minusDays(5), null)))
        val calc = TripModel.calculate(trips, listOf(passport("GB"), Document("p2", "FR", Passport("FR"), "2025-01-01")), w, today)
        val a = calc.allowances.first { it.key == "stay:gb60" }
        assertEquals(60, a.totalDays)
        assertEquals(6, a.usedDays)
        assertTrue(calc.allowances.none { it.key == "stay:fr90" })
    }

    @Test
    fun exhaustedDocSnapshotShowsDangerAtExhaustionDate() {
        // P2-38: a single-entry visa whose one stay is over is spent: zero entries left, pinned
        // to the exhaustion date (the stay's end). (The AllowanceStatus.EXHAUSTED branch is
        // shadowed here — an exhausted doc's effective expiry is by definition in the past, so
        // the expiry check reports DANGER first; that is what the UI shows.)
        val v = Document("v", "Visa", Custom(setOf("US"), null, "visa", null, "single"), "2030-01-01")
        val trips = listOf(trip("t1", stop("s1", "US", today.minusDays(12), today.minusDays(8), "v")))
        val calc = TripModel.calculate(trips, listOf(v), world(), today)
        val a = calc.allowances.first { it.key == "doc:v" }
        assertEquals(AllowanceKind.ENTRY_COUNT, a.kind)
        assertEquals(1, a.entryTotal)
        assertEquals(1, a.entryUsed)
        assertEquals(0, a.entryRemaining)
        assertEquals(today.minusDays(8), a.effectiveExpiry)
        assertEquals(0, a.remainingDays)
        assertEquals(AllowanceStatus.DANGER, a.status)
    }

    @Test
    fun lastEntryDocSnapshotWarns() {
        // P2-38: WARNING at remaining == 1 — a double-entry visa with one entry used.
        val v = Document("v", "Visa", Custom(setOf("US"), null, "visa", null, "double"), "2030-01-01")
        val trips = listOf(trip("t1", stop("s1", "US", today.minusDays(12), today.minusDays(8), "v")))
        val calc = TripModel.calculate(trips, listOf(v), world(), today)
        val a = calc.allowances.first { it.key == "doc:v" }
        assertEquals(2, a.entryTotal)
        assertEquals(1, a.entryUsed)
        assertEquals(1, a.entryRemaining)
        assertEquals(AllowanceStatus.WARNING, a.status)
    }

    @Test
    fun docSnapshotSubtitleFormat() {
        // P2-38: the subtitle is "<entry type> · Expires <date>"; the entry-type half is omitted
        // for unlimited-entry docs and the date half when there is no expiry.
        val double = Document("v", "Visa", Custom(setOf("US"), null, "visa", null, "double"), "2030-01-01")
        val unlimited = Document("u", "Visa", Custom(setOf("US"), null, "visa", null, "multiple"), "2030-01-01")
        val noExpiry = Document("n", "Visa", Custom(setOf("US"), null, "visa", null, "single"))
        val calc = TripModel.calculate(emptyList(), listOf(double, unlimited, noExpiry), world(), today)
        assertEquals("Double entry · Expires 2030-01-01", calc.allowances.first { it.key == "doc:v" }.subtitle)
        assertEquals("Expires 2030-01-01", calc.allowances.first { it.key == "doc:u" }.subtitle)
        assertEquals("Single entry", calc.allowances.first { it.key == "doc:n" }.subtitle)
    }

    @Test
    fun passportSnapshotShowsValidityNotEntries() {
        // P2-38: a passport gets a DOCUMENT_VALIDITY snapshot — no entry count, just "Expires".
        val p = Document("p", "Passport · GB", Passport("GB"), expiry = "2026-10-12")
        val calc = TripModel.calculate(emptyList(), listOf(p), world(), today)
        val a = calc.allowances.first { it.key == "doc:p" }
        assertEquals(AllowanceKind.DOCUMENT_VALIDITY, a.kind)
        assertEquals("Expires 2026-10-12", a.subtitle)
        assertEquals(29, a.remainingDays)
        assertEquals(AllowanceStatus.OK, a.status)
    }
}
