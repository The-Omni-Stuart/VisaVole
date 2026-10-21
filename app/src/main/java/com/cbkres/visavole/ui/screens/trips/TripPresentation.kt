package com.cbkres.visavole.ui.screens.trips

import com.cbkres.visavole.data.WorldData
import com.cbkres.visavole.domain.Trip
import com.cbkres.visavole.domain.TripModel

internal fun TripPresentation(trip: Trip, world: WorldData): String {
    val names = TripModel.sortedStops(trip.stops).map { world.countries[it.countryIso2]?.name ?: it.countryIso2 }
    return when {
        names.size <= 1 -> names.firstOrNull().orEmpty()
        names.first() == names.last() -> if (names.toSet().size == 1) names.first() else "${names.first()} → … → ${names.last()}"
        else -> "${names.first()} → ${names.last()}"
    }
}