package com.cbkres.visavole.domain

import java.time.LocalDate

/** A stop paired with the id of the trip it belongs to. */
internal data class TrippedStop(val tripId: String, val stop: TripStop)

/**
 * A run of stops that share one entry: [start] and [tripId] come from the stop that opened the
 * stay, [end] is null while the merged range stays open-ended, and [bound] is the latest date
 * the range could end on given every stop merged so far — an open stop contributes its arrival.
 */
internal data class StaySegment(val start: LocalDate, val end: LocalDate?, val tripId: String, val bound: LocalDate)

/**
 * Groups arrival-sorted [stops] into stay segments: a stop starting at most one day after the
 * previous segment's end is a next-day handover and shares its entry. What the end of an
 * open-ended segment means differs per question, so the caller answers through [openEndOf] —
 * null lets the open segment absorb everything that follows.
 */
internal fun mergeStaySegments(
    stops: List<TrippedStop>,
    openEndOf: (StaySegment) -> LocalDate?,
): List<StaySegment> {
    val segments = mutableListOf<StaySegment>()
    for (ts in stops) {
        val last = segments.lastOrNull()
        val bound = last?.let(openEndOf)
        if (last == null || (bound != null && ts.stop.arrival > bound.plusDays(1))) {
            segments += StaySegment(
                start = ts.stop.arrival,
                end = ts.stop.departure,
                tripId = ts.tripId,
                bound = ts.stop.departure ?: ts.stop.arrival,
            )
        } else {
            segments[segments.lastIndex] = last.copy(
                end = mergeEnd(last.end, ts.stop.departure),
                bound = maxOf(last.bound, ts.stop.departure ?: ts.stop.arrival),
            )
        }
    }
    return segments
}

private fun mergeEnd(a: LocalDate?, b: LocalDate?): LocalDate? = when {
    a == null -> b
    b == null -> null
    else -> if (a.isAfter(b)) a else b
}
