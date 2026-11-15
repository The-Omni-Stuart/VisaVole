package com.cbkres.visavole.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * The next-day-handover merge the entry status, the projection warnings and the planned-entries
 * guard all perform. They agree on closed ranges and on one-day handover; they deliberately
 * disagree about an open-ended stay, which is what [mergeStaySegments]'s `openEndOf` argument
 * captures: each caller states what the end of an open segment means for its own question.
 */
class StaySegmentsTest {

    private val jan: (Int) -> LocalDate = { LocalDate.of(2027, 1, it) }

    private fun stop(tripId: String, arrival: Int, departure: Int? = null) =
        TrippedStop(
            tripId,
            TripStop("s$arrival", "DE", jan(arrival), departure?.let(jan)),
        )

    @Test
    fun aNextDayHandoverSharesOneSegment() {
        val segments = mergeStaySegments(listOf(stop("t1", 1, 5), stop("t2", 6, 10))) { it.end }
        assertEquals(1, segments.size)
        assertEquals(jan(1), segments[0].start)
        assertEquals(jan(10), segments[0].end)
        // The segment is attributed to the trip that opened it.
        assertEquals("t1", segments[0].tripId)
    }

    @Test
    fun aGapStartsANewSegment() {
        val segments = mergeStaySegments(listOf(stop("t1", 1, 5), stop("t2", 8, 10))) { it.end }
        assertEquals(2, segments.size)
    }

    @Test
    fun anOpenSegmentAbsorbsEveryLaterStopForTheEntryStatus() {
        val segments = mergeStaySegments(listOf(stop("t1", 1), stop("t2", 20, 25))) { it.end }
        assertEquals(1, segments.size)
        assertEquals(jan(25), segments[0].end)
    }

    @Test
    fun anOpenStopReopensTheSegmentItMergesInto() {
        val segments = mergeStaySegments(
            listOf(stop("t1", 1, 5), stop("t2", 6), stop("t3", 20, 25)),
        ) { it.end }
        assertEquals(1, segments.size)
        assertEquals(jan(25), segments[0].end)
    }

    @Test
    fun aSeparatedFutureStopIsAReEntryOnceTheOpenStayEndsAtTheAsOfDate() {
        val segments = mergeStaySegments(
            listOf(stop("t1", 1), stop("t2", 10, 12)),
        ) { it.end ?: jan(2) }
        assertEquals(2, segments.size)
    }

    @Test
    fun aStopTouchingTheAsOfBoundContinuesTheOpenStay() {
        val segments = mergeStaySegments(
            listOf(stop("t1", 1), stop("t2", 3, 4)),
        ) { it.end ?: jan(2) }
        assertEquals(1, segments.size)
    }

    @Test
    fun anOpenStayDoesNotAbsorbIntoThePlannedEntryCount() {
        val segments = mergeStaySegments(listOf(stop("t1", 1), stop("t2", 5, 10))) { it.bound }
        assertEquals(2, segments.size)
    }

    @Test
    fun thePlannedBoundRunsOnTheLatestKnownEndEvenThroughAnOpenStop() {
        val segments = mergeStaySegments(
            listOf(stop("t1", 1, 5), stop("t2", 6), stop("t3", 7, 9)),
        ) { it.bound }
        assertEquals(1, segments.size)
        assertEquals(jan(9), segments[0].end)
        assertEquals(jan(9), segments[0].bound)
    }

    @Test
    fun anOpenStopEndsThePlannedBoundOnItsArrival() {
        val segments = mergeStaySegments(listOf(stop("t1", 1), stop("t2", 2))) { it.bound }
        assertEquals(1, segments.size)
        assertNull(segments[0].end)
        assertEquals(jan(2), segments[0].bound)
    }
}
