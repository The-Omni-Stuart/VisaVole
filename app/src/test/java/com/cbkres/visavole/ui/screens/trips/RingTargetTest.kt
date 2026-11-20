package com.cbkres.visavole.ui.screens.trips

import com.cbkres.visavole.domain.AllowanceKind
import com.cbkres.visavole.domain.AllowanceSnapshot
import com.cbkres.visavole.domain.AllowanceStatus
import com.cbkres.visavole.domain.Trip
import com.cbkres.visavole.domain.TripStop
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class RingTargetTest {

    private val old = Trip("old", listOf(TripStop("s1", "DE", LocalDate.of(2026, 5, 20), LocalDate.of(2026, 5, 30))))
    private val recent = Trip("recent", listOf(TripStop("s2", "HR", LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 19))))
    private val upcoming = Trip("upcoming", listOf(TripStop("s3", "DE", LocalDate.of(2027, 4, 1), LocalDate.of(2027, 4, 10))))

    private fun zone(currentTripId: String?) = AllowanceSnapshot(
        key = "stay:schengen",
        title = "Schengen Area",
        kind = AllowanceKind.ROLLING,
        status = AllowanceStatus.OK,
        currentTripId = currentTripId,
        relevantTripIds = listOf("old", "recent", "upcoming"),
    )

    @Test
    fun ringCentresOnTheMostRecentSettledTripContributingToIt() {
        assertEquals(
            "recent",
            ringTargetTrip(zone(null), current = emptyList(), previous = listOf(old, recent))?.id,
        )
        assertEquals("old", ringTargetTrip(zone("old"), emptyList(), listOf(recent, old))?.id)
        assertEquals("recent", ringTargetTrip(zone("upcoming"), emptyList(), listOf(recent, old))?.id)
    }
}
