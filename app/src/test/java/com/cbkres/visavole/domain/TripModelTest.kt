package com.cbkres.visavole.domain

import com.cbkres.visavole.data.Benefit
import com.cbkres.visavole.data.Corridor
import com.cbkres.visavole.data.Country
import com.cbkres.visavole.data.Regime
import com.cbkres.visavole.data.StayRule
import com.cbkres.visavole.data.WorldData
import com.cbkres.visavole.data.Holding as DataHolding
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

    private fun schengenDoc() = Document("v", "Visa", Holding("x", "single"), null, "2030-01-01")

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
        val doc = Document("v", "Visa", Holding("x", "double"), null, "2030-01-01")
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
        val doc = Document("v", "Visa", Holding("x", "single"), null, "2030-01-01")
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
    fun splitBeforeStopClosesTheLeftTripAndStartsARightTrip() {
        val t = trip(
            "t",
            stop("s1", "DE", today.minusDays(10), null),
            stop("s2", "FR", today.plusDays(7), null),
        )
        val (left, right) = TripModel.splitBeforeStop(t, 1, today.plusDays(4), "new")!!
        assertEquals("t", left.id)
        assertEquals(listOf(today.plusDays(4)), left.stops.map { it.departure })
        assertEquals("new", right.id)
        assertEquals(listOf("s2"), right.stops.map { it.id })
        assertNull(right.note)
    }

    @Test
    fun splitBeforeStopRejectsInvalidIndexes() {
        val t = trip("t", stop("s1", "DE", today, null))
        assertNull(TripModel.splitBeforeStop(t, 0, today, "new"))
        assertNull(TripModel.splitBeforeStop(t, 1, today, "new"))
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
}
