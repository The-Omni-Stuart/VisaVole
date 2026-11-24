package com.cbkres.visavole.ui.screens.trips

import com.cbkres.visavole.domain.AllowanceSnapshot
import com.cbkres.visavole.domain.Trip
import com.cbkres.visavole.domain.TripModel
import com.cbkres.visavole.domain.WorldData

/** The trip a zone ring should centre on: its active trip, else the most recent past/current one. */
internal fun ringTargetTrip(
    zone: AllowanceSnapshot,
    current: List<Trip>,
    previous: List<Trip>,
): Trip? {
    val settled = current + previous
    return zone.currentTripId?.let { id -> settled.firstOrNull { it.id == id } }
        ?: settled.filter { it.id in zone.relevantTripIds }.maxByOrNull { it.firstArrival }
}

internal fun TripPresentation(trip: Trip, world: WorldData): String {
    val names = TripModel.sortedStops(trip.stops).map { world.countries[it.countryIso2]?.name ?: it.countryIso2 }
    return when {
        names.size <= 1 -> names.firstOrNull().orEmpty()
        names.first() == names.last() -> if (names.toSet().size == 1) names.first() else "${names.first()} → … → ${names.last()}"
        else -> "${names.first()} → ${names.last()}"
    }
}