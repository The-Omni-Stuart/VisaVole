package com.cbkres.visavole.ui.screens.trips

import com.cbkres.visavole.domain.Country
import com.cbkres.visavole.domain.DocKind
import com.cbkres.visavole.domain.Document
import com.cbkres.visavole.domain.GuardAction
import com.cbkres.visavole.domain.GuardContext
import com.cbkres.visavole.domain.GuardEngine
import com.cbkres.visavole.domain.StayRule
import com.cbkres.visavole.domain.Trip
import com.cbkres.visavole.domain.TripStop
import com.cbkres.visavole.domain.WorldData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * A split saves the first half immediately, and the published trip list trails that commit by a
 * publish cycle, so the dialog must judge its later verdicts against a list that already holds
 * the saved half — otherwise the second half is judged as if the split never happened.
 */
class TripSplitTripsTest {

    private val today = LocalDate.of(2026, 9, 13)

    private fun trip(id: String, vararg stops: TripStop) = Trip(id, stops.toList())
    private fun stop(id: String, iso: String, arrival: LocalDate, departure: LocalDate?) =
        TripStop(id, iso, arrival, departure, null)

    @Test
    fun splittingAnEditedTripReplacesThatTripInTheJudgedList() {
        val before = trip("t1", stop("s1", "DE", today.minusDays(20), today.plusDays(20)))
        val leftHalf = trip("t1", stop("s1", "DE", today.minusDays(20), today.plusDays(2)))

        val judged = tripsAfterSplit(listOf(before, trip("other")), leftHalf, replacedExisting = true)

        assertEquals("the split must not leave a stale copy of the trip behind", 2, judged.size)
        assertEquals(leftHalf, judged.first { it.id == "t1" })
    }

    @Test
    fun splittingANewTripAddsTheFirstHalfToTheJudgedList() {
        val leftHalf = trip("t-new", stop("s1", "DE", today.minusDays(5), today))

        val judged = tripsAfterSplit(emptyList(), leftHalf, replacedExisting = false)

        assertEquals(listOf(leftHalf), judged)
    }

    /**
     * The just-saved first half is what makes the second half warn: judged against a list that
     * lacks it, the same pair answers quietly.
     */
    @Test
    fun theJustSavedFirstHalfIsWhatMakesTheSecondHalfWarn() {
        val world = WorldData(
            mapOf("DE" to Country("DE", "Germany")),
            emptyMap(), emptyList(), emptyMap(), emptyMap(),
            listOf(StayRule("Schengen Area", setOf("DE"), "rolling", 90, 180, true, setOf("*"), null, id = "schengen", zoneId = "schengen")),
        )
        val passport = Document("p-GB", "GB", DocKind.Passport("GB"))
        val leftHalf = trip("t-left", stop("s1", "DE", today.plusDays(1), today.plusDays(10)))
        val rightHalf = trip("t-right", stop("s2", "DE", today.plusDays(5), today.plusDays(12)))

        fun overlaps(trips: List<Trip>) = GuardEngine
            .evaluate(GuardAction.AddTrip(rightHalf), GuardContext(listOf(passport), trips, world, today))
            .warnings
            .any { it.title == "Trip overlap" }

        assertTrue("the split-off half must trigger the overlap warning", overlaps(listOf(leftHalf)))
        assertFalse("without the saved half there is nothing to overlap", overlaps(emptyList()))
    }
}
