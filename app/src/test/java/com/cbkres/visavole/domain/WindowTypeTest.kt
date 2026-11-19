package com.cbkres.visavole.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.LocalDate

/** An unknown window type meters as a lifetime pool, since only a per-entry reading can over-grant. */
class WindowTypeTest {

    private val today = LocalDate.of(2026, 11, 19)

    /** 90 days granted once, pooled across three states, with no period to slide over. */
    private val lifetime = StayRule(
        "East African Community",
        setOf("KE", "RW", "UG"),
        "lifetime",
        90,
        null,
        true,
        setOf("*"),
        null,
        id = "eac-test",
        zoneId = "eac",
    )

    private val unknown = lifetime.copy(windowType = "someday", id = "unknown-test", zoneId = null)
    private val perEntry = lifetime.copy(windowType = "per-entry", id = "entry-test")
    private val rolling = lifetime.copy(windowType = "rolling", windowPeriodDays = 90, id = "rolling-test", zoneId = null)

    /** Nine days spent across an open land border, which is exactly what a per-entry reading rewards. */
    private val stays = listOf(
        ZoneSegment(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 5)),
        ZoneSegment(LocalDate.of(2026, 9, 6), LocalDate.of(2026, 9, 9)),
    )

    private fun world(rules: List<StayRule>) = WorldData(
        mapOf("KE" to Country("KE", "Kenya"), "RW" to Country("RW", "Rwanda")),
        emptyMap(),
        emptyList(),
        emptyMap(),
        emptyMap(),
        rules,
    )

    private fun snapshotOf(rule: StayRule) = zoneSnapshot(
        "stay:test",
        ZoneUsage(segments = stays.toMutableList(), rule = rule),
        world(listOf(rule)),
        today,
        docs = emptyList(),
        trips = emptyList(),
        statuses = emptyMap(),
    )!!

    @Test
    fun aConsumedTicketReportsTheDaysSpentNotAFreshAllowance() {
        // A rolling window and a lifetime pool agree here; per-entry reports a full 90 spent elsewhere.
        assertEquals(81, remainingOn(lifetime, stays, today))
        assertEquals("an unknown type must not default to the border-resets-it reading", 81, remainingOn(unknown, stays, today))
        assertEquals("what per-entry answers for the same traveller — the over-grant this replaces", 0, usageOn(perEntry, stays, today).first)
    }

    @Test
    fun aLifetimePoolNeverRecoversAndNeverClaimsTo() {
        assertNull(rollingRecovery(lifetime, stays, today))
        assertNull(rollingRecovery(unknown, stays, today))
        // The days are gone wherever the evaluation date sits.
        assertEquals(81, remainingOn(lifetime, stays, today.plusYears(5)))
    }

    @Test
    fun onlyDaysAlreadySpentCountAgainstALifetimePool() {
        val ahead = listOf(ZoneSegment(today.plusDays(10), today.plusDays(20)))
        assertEquals(90, remainingOn(lifetime, ahead, today))
        assertEquals("the 10th to the 20th is 11 days, counted inclusively as everywhere else", 79, remainingOn(lifetime, ahead, today.plusDays(20)))
    }

    @Test
    fun aLifetimeRowStopsBeingLabelledPerEntry() {
        for (snapshot in listOf(snapshotOf(lifetime), snapshotOf(unknown))) {
            assertEquals(AllowanceKind.LIFETIME, snapshot.kind)
            assertEquals(81, snapshot.remainingDays)
            assertFalse("the subtitle must not promise a border reset: ${snapshot.subtitle}", snapshot.subtitle!!.contains("per entry"))
        }
        assertEquals(AllowanceKind.PER_ENTRY, snapshotOf(perEntry).kind)
        assertEquals(AllowanceKind.ROLLING, snapshotOf(rolling).kind)
    }

    @Test
    fun aZoneIsGovernedByTheTighterWindowTypeNotByItsId() {
        // Equal day counts, so only the type can stop a per-entry row running a whole zone.
        val perEntryFirst = perEntry.copy(id = "aa", zoneId = "shared")
        val rollingSecond = rolling.copy(id = "zz", zoneId = "shared")
        assertEquals(rollingSecond.id, tighterRule(perEntryFirst, rollingSecond).id)
        assertEquals(rollingSecond.id, tighterRule(rollingSecond, perEntryFirst).id)
        assertEquals(lifetime.id, tighterRule(rollingSecond, lifetime.copy(zoneId = "shared")).id)
        assertEquals(rolling.id, tighterRule(rolling, rolling.copy(windowDays = 120, id = "roomier")).id)
    }
}
