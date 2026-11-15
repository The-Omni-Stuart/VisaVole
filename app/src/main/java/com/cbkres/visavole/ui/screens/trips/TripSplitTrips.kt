package com.cbkres.visavole.ui.screens.trips

import com.cbkres.visavole.domain.Trip

/**
 * The trip list the add-trip dialog must judge by once a split has committed its first half:
 * the published list still shows the trip as it was before the split (the ViewModel publishes a
 * cycle later), so the dialog tracks its own view of it — replaced in place when the split
 * edited a saved trip, appended when it committed a new one.
 */
internal fun tripsAfterSplit(
    base: List<Trip>,
    leftTrip: Trip,
    replacedExisting: Boolean,
): List<Trip> =
    if (replacedExisting) base.map { if (it.id == leftTrip.id) leftTrip else it }
    else base + leftTrip
