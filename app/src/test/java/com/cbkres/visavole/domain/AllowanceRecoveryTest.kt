package com.cbkres.visavole.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * `remainingOn` for any date, and `rollingRecovery`'s closed-form answer checked against a walk
 * that asks `remainingOn` once per day — too slow to ship, so the walk stays here as the referee.
 */
class AllowanceRecoveryTest {

    private val today = LocalDate.of(2026, 11, 19)

    /** 30 days in any 90 — the Schengen shape at a scale a test can walk. */
    private val rolling = StayRule(
        "Test Area",
        setOf("DE"),
        "rolling",
        30,
        90,
        true,
        setOf("*"),
        null,
        id = "test-rolling",
        zoneId = "test",
    )

    private val perEntry = rolling.copy(windowType = "per-entry", windowPeriodDays = null, id = "test-entry", zoneId = null)

    private val segments = listOf(ZoneSegment(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 4))) +
        listOf(ZoneSegment(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 2)))

    // 4 used days in the first slice, 2 in the second, all inside the window ending today.
    private val usedToday = 6

    private fun world() = WorldData(
        mapOf("DE" to Country("DE", "Germany")),
        emptyMap(),
        emptyList(),
        emptyMap(),
        emptyMap(),
        listOf(rolling),
    )

    private fun usageOf(rule: StayRule, segs: List<ZoneSegment>) =
        ZoneUsage(segments = segs.toMutableList(), rule = rule)

    @Test
    fun remainingOnAnswersForAnyDateNotJustToday() {
        assertEquals(30 - usedToday, remainingOn(rolling, segments, today))
        assertEquals("nothing consumed before the first stay", 30, remainingOn(rolling, segments, LocalDate.of(2026, 9, 30)))
        assertEquals("both slices still inside the window", 30 - usedToday, remainingOn(rolling, segments, LocalDate.of(2026, 12, 18)))
        assertEquals("both slices dropped out of it", 30, remainingOn(rolling, segments, LocalDate.of(2027, 2, 15)))
    }

    @Test
    fun recoveryDatesAreTheSliceEdgesPlusOneWindow() {
        val recovery = rollingRecovery(rolling, segments, today)!!
        assertEquals(LocalDate.of(2026, 12, 30), recovery.firstSliceBackOn)
        assertEquals(LocalDate.of(2027, 1, 31), recovery.wholeAgainOn)
        assertEquals(usedToday, recovery.daysBack)
        assertEquals(30, recovery.totalDays)
    }

    @Test
    fun closedFormRecoveryAgreesWithWalkingTheDateAxis() {
        val recovery = rollingRecovery(rolling, segments, today)!!
        var recovered = 0
        var previous = remainingOn(rolling, segments, today)!!
        var on = today.plusDays(1)
        while (!on.isAfter(recovery.wholeAgainOn)) {
            val now = remainingOn(rolling, segments, on)!!
            assertTrue("the balance must never fall as days pass", now >= previous)
            recovered += now - previous
            previous = now
            on = on.plusDays(1)
        }
        assertEquals(recovery.daysBack, recovered)
        assertEquals("whole on the date it promises", 30, previous)
        assertEquals("exactly one slice back on the date it claims", 30 - usedToday + 1, remainingOn(rolling, segments, recovery.firstSliceBackOn))
    }

    @Test
    fun anAllowanceThatDoesNotRefillReportsNoDates() {
        // A date for any of these would be an invention, which is why null is the contract.
        assertNull(rollingRecovery(perEntry, segments, today))
        assertNull("L2: rolling with no period has no whole-again date", rollingRecovery(rolling.copy(windowPeriodDays = null), segments, today))
        assertNull(rollingRecovery(rolling.copy(windowDays = null), segments, today))
        assertNull(rollingRecovery(rolling, emptyList(), today))
        assertNull(rollingRecovery(rolling, listOf(ZoneSegment(today.plusDays(10), today.plusDays(20))), today))
    }

    @Test
    fun perEntryStaysFullOutsideAnEntry() {
        // The guard the refactor must not disturb: full outside any entry, that entry's days only inside.
        assertEquals(30, remainingOn(perEntry, segments, today))
        assertEquals(30, remainingOn(perEntry, segments, LocalDate.of(2026, 10, 20)))
        assertEquals(30 - 2, remainingOn(perEntry, segments, LocalDate.of(2026, 11, 2)))
    }

    @Test
    fun publishedSnapshotAgreesWithTheDateAxis() {
        // S28 written once: what the ring shows must be the evaluator's answer for the same inputs.
        val snapshot = zoneSnapshot(
            "stay:test",
            usageOf(rolling, segments),
            world(),
            today,
            docs = emptyList(),
            trips = emptyList(),
            statuses = emptyMap(),
        )!!
        assertEquals(remainingOn(rolling, segments, today), snapshot.remainingDays)
        assertEquals(usedToday, snapshot.usedDays)
        assertEquals(30, snapshot.totalDays)
        assertEquals(30 - usedToday, snapshot.remainingDays)
    }

    @Test
    fun publishedSnapshotCarriesTheRecoveryItsDatesProduce() {
        val snapshot = zoneSnapshot(
            "stay:test",
            usageOf(rolling, segments),
            world(),
            today,
            docs = emptyList(),
            trips = emptyList(),
            statuses = emptyMap(),
        )!!
        assertEquals(rollingRecovery(rolling, segments, today), snapshot.recovery)
        assertNull(zoneSnapshot(
            "stay:test", usageOf(perEntry, segments), world(), today,
            docs = emptyList(), trips = emptyList(), statuses = emptyMap(),
        )!!.recovery)
    }

    @Test
    fun firstSliceComesFromTheWindowNotFromAnAlreadyReturnedStay() {
        val alreadyReturned = ZoneSegment(today.minusDays(190), today.minusDays(180))
        val inside = ZoneSegment(today.minusDays(10), today.minusDays(1))
        val recovery = rollingRecovery(rolling, listOf(alreadyReturned, inside), today)!!
        assertEquals(today.plusDays(80), recovery.firstSliceBackOn)
        assertEquals(today.minusDays(1).plusDays(90), recovery.wholeAgainOn)
        assertEquals(10, recovery.sliceDays)
        assertEquals(false, recovery.inProgress)
        assertEquals(false, rollingRecovery(rolling, listOf(inside), today)!!.inProgress)
    }

    @Test fun anOldReturnedStayDoesNotMakeTheNewSliceInProgress() {
        val old = ZoneSegment(today.minusDays(400), today.minusDays(390))
        val recent = ZoneSegment(today.minusDays(40), today.minusDays(32))
        val recovery = rollingRecovery(rolling, listOf(old, recent), today)!!
        assertEquals(today.plusDays(50), recovery.firstSliceBackOn)
        assertEquals(false, recovery.inProgress)
    }

    @Test
    fun currentSliceCountsOnlyTheDaysReturningOnThatDate() {
        val first = ZoneSegment(today.minusDays(80), today.minusDays(70))
        val second = ZoneSegment(today.minusDays(10), today.minusDays(1))
        val recovery = rollingRecovery(rolling, listOf(first, second), today)!!
        assertEquals(today.plusDays(10), recovery.firstSliceBackOn)
        assertEquals(11, recovery.sliceDays)
        assertEquals(21, recovery.daysBack)
    }

    @Test
    fun projectionIsEvaluatedAtAFutureOpenStopsArrival() {
        // An open future stop has a known arrival but no departure: the figure is for that date.
        val arrival = LocalDate.of(2027, 4, 1)
        val trip = Trip("t", listOf(TripStop("s", "DE", arrival, null)))
        val snapshot = zoneSnapshot(
            "stay:test", usageOf(rolling, segments), world(), today,
            docs = emptyList(), trips = listOf(trip), statuses = emptyMap(),
        )!!
        assertEquals(arrival, snapshot.projection?.on)
        assertEquals("the old stays are outside the window by then, the new stop costs one day",
            29, snapshot.projection?.remainingDays)
        assertEquals(30, snapshot.projection?.totalDays)
    }
}
