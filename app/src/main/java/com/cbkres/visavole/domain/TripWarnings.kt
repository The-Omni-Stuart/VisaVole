package com.cbkres.visavole.domain

import com.cbkres.visavole.domain.AccessLevel.ETA
import com.cbkres.visavole.domain.AccessLevel.E_VISA
import com.cbkres.visavole.domain.AccessLevel.REFUSED
import com.cbkres.visavole.domain.AccessLevel.VISA_REQUIRED
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** How far ahead an open-ended stay is probed for expiring grants and passport validity. */
internal const val OPEN_STAY_PROBE_HORIZON_DAYS = 365L

/**
 * The four trip warnings engines behind the [TripModel] facade: structural validation,
 * cross-trip overlap, forward projection of allowance/entries/access, and within-trip
 * continuity (gaps and stop overlaps). [TripModel] keeps its public facade over these.
 */
internal object TripWarnings {

    private fun daysLabel(count: Int): String = "$count ${if (count == 1) "day" else "days"}"

    /**
     * Inclusive overlap in days between two stop ranges. A missing departure means the stay is
     * open-ended: clamping it to any concrete date inverts future ranges and mutes real overlaps.
     */
    private fun stopOverlapDays(
        aArrival: LocalDate,
        aDeparture: LocalDate?,
        bArrival: LocalDate,
        bDeparture: LocalDate?,
    ): Int {
        val start = if (aArrival.isAfter(bArrival)) aArrival else bArrival
        val aEnd = aDeparture ?: bDeparture ?: start
        val bEnd = bDeparture ?: aDeparture ?: start
        val end = if (aEnd.isBefore(bEnd)) aEnd else bEnd
        if (end.isBefore(start)) return 0
        return ChronoUnit.DAYS.between(start, end).toInt() + 1
    }

    fun validateTrip(trip: Trip, world: WorldData, today: LocalDate): List<TripWarning> {
        val out = mutableListOf<TripWarning>()
        if (trip.stops.isEmpty()) {
            out.add(TripWarning("trip.noStops", WarningSeverity.DANGER, "No stops", "A trip needs at least one country stop."))
        }
        trip.stops.forEachIndexed { index, stop ->
            if (world.countries[stop.countryIso2] == null) {
                out.add(TripWarning("trip.unknownCountry", WarningSeverity.DANGER, "Unknown country", "Stop ${index + 1} has no known country."))
            }
            if (stop.departure != null && stop.departure.isBefore(stop.arrival)) {
                out.add(TripWarning("trip.departureBeforeArrival", WarningSeverity.DANGER, "Departure before arrival", "Stop ${index + 1} ends before it starts."))
            }
            if (index < trip.stops.lastIndex && stop.departure == null) {
                out.add(TripWarning("trip.missingDeparture", WarningSeverity.WARNING, "Missing departure", "Only the final stop can leave its departure date empty."))
            }
        }
        return out
    }

    fun overlapWarningsFor(
        candidate: Trip,
        trips: List<Trip>,
        docs: List<Document>,
        world: WorldData,
        today: LocalDate,
    ): List<TripWarning> {
        val passports = AccessModel.validHomeCountries(docs, world, today, trips = trips)
        val rules = StayRuleMemo(world, passports, today)
        val out = mutableListOf<TripWarning>()
        val seen = HashSet<String>()
        for (cs in candidate.stops) {
            for (other in trips.filter { it.id != candidate.id }) {
                for (os in other.stops) {
                    val overlap = stopOverlapDays(cs.arrival, cs.departure, os.arrival, os.departure)
                    // One overlapping day is a transit/day-trip within a stay, not a collision —
                    // the tolerance boundary is pinned in TripModelTest (oneDay/twoDay cases).
                    if (overlap <= 1) continue
                    val sameCountry = cs.countryIso2 == os.countryIso2
                    val csRule = rules.forDest(cs.countryIso2)
                    val osRule = rules.forDest(os.countryIso2)
                    val sameZone = !sameCountry && csRule != null && osRule != null && world.zoneKeyFor(csRule) == world.zoneKeyFor(osRule)
                    if (sameCountry || sameZone) {
                        val label = when {
                            sameCountry -> world.countries[cs.countryIso2]?.name ?: cs.countryIso2
                            else -> csRule?.displayName ?: "the same zone"
                        }
                        val key = (if (sameCountry) "c:${cs.countryIso2}" else "z:${csRule?.let { world.zoneKeyFor(it) }}") + other.id
                        if (seen.add(key)) {
                            out.add(
                                TripWarning(
                                    "trip.overlap",
                                    WarningSeverity.WARNING,
                                    "Trip overlap",
                                    "This trip overlaps $label by ${daysLabel(overlap)}.",
                                ),
                            )
                        }
                    } else if (seen.add("t:" + other.id)) {
                        out.add(
                            TripWarning(
                                "trip.timeOverlap",
                                WarningSeverity.WARNING,
                                "Overlapping trips",
                                "This trip overlaps another trip by ${daysLabel(overlap)}, but the stays are in " +
                                    "different places — you can only be in one place at a time.",
                            ),
                        )
                    }
                }
            }
        }
        return out
    }

    fun projectionWarningsFor(
        candidate: Trip,
        trips: List<Trip>,
        docs: List<Document>,
        world: WorldData,
        today: LocalDate,
    ): List<TripWarning> {
        val out = mutableListOf<TripWarning>()
        val projection = candidate.stops.mapNotNull { it.departure }.maxOrNull() ?: today
        val asOf = if (projection.isBefore(today)) today else projection
        val passports = AccessModel.validHomeCountries(docs, world, asOf, trips = trips)

        val zones = linkedSetOf<String>()
        val rules = StayRuleMemo(world, passports, asOf)
        for (stop in candidate.stops) {
            rules.forDest(stop.countryIso2)?.let { zones.add(world.zoneKeyFor(it)) }
        }
        val usageWith = zoneUsages(trips + candidate, docs, world, asOf)
        for (key in zones) {
            val usage = usageWith[key] ?: continue
            val rule = usage.rule ?: continue
            val used = usageOn(rule, usage.segments, asOf).first
            val total = rule.windowDays ?: continue
            val overstay = (used - total).coerceAtLeast(0)
            if (overstay > 0) {
                out.add(
                    TripWarning(
                        "trip.allowance.exceeded",
                        WarningSeverity.DANGER,
                        "Allowance exceeded",
                        "This trip would exceed the ${rule.displayName} allowance by ${daysLabel(overstay)}.",
                    ),
                )
            } else {
                val remaining = total - used
                if (remaining <= WARNING_THRESHOLD_DAYS) {
                    out.add(
                        TripWarning(
                            "trip.allowance.low",
                            WarningSeverity.WARNING,
                            "Low allowance",
                            "After this trip you will have ${if (remaining == 1) "1 ${rule.displayName} day" else "$remaining ${rule.displayName} days"} left.",
                        ),
                    )
                }
            }
        }

        for (docId in candidate.stops.mapNotNull { it.documentId }.toSet()) {
            val doc = docs.firstOrNull { it.id == docId } ?: continue
            // The candidate's own stops must count as entries even though they start after [asOf],
            // and an open in-progress entry is treated as ending at [asOf] so a separated future
            // stop is a re-entry: computeEntryStatus alone merged them and downgraded
            // "not enough entries" to "last entry used".
            val remaining = TripModel.projectedEntryUsage(doc, trips, candidate, asOf) ?: continue
            if (remaining < 0) {
                out.add(
                    TripWarning(
                        "trip.entries.over",
                        WarningSeverity.DANGER,
                        "Not enough entries",
                        "This trip would use more ${doc.label} entries than available.",
                    ),
                )
            } else if (remaining == 0) {
                out.add(
                    TripWarning(
                        "trip.entries.last",
                        WarningSeverity.WARNING,
                        "Last entry used",
                        "This trip would use the last ${doc.label} entry.",
                    ),
                )
            }
        }

        // Probe access at every date the answer can change: stop boundaries from today onward,
        // and the day each held document stops being valid — an open-ended stay has no end date
        // to catch a grant expiring mid-stay otherwise. Doc-expiry probes are capped a year ahead:
        // an open stop spans every probe, so uncapped ones warned per far-future expiry.
        val dates = linkedSetOf<LocalDate>()
        for (stop in candidate.stops) {
            if (!stop.arrival.isBefore(today)) dates.add(stop.arrival)
            val end = stop.departure
            if (end != null && !end.isBefore(today) && !end.isBefore(stop.arrival)) dates.add(end)
            // A stay already running is probed today: its arrival is past and an open-ended one
            // has no end, so otherwise no date would cover the "now" the traveller is in.
            if (!stop.arrival.isAfter(today) && (end == null || !end.isBefore(today))) dates.add(today)
        }
        val horizon = today.plusDays(OPEN_STAY_PROBE_HORIZON_DAYS)
        for (doc in docs) {
            val expiry = doc.expiry.toDateOrNull() ?: continue
            val breaksTheDayAfter = expiry.plusDays(1)
            if (!breaksTheDayAfter.isBefore(today) && !breaksTheDayAfter.isAfter(horizon)) dates.add(breaksTheDayAfter)
        }
        val accessSeen = HashSet<String>()
        for (date in dates) {
            val effective = TripModel.effectiveDocs(docs, trips, world, date)
            val access = AccessModel.compute(effective, world, date)
            for (stop in candidate.stops) {
                if (stop.arrival > date) continue
                if (stop.departure != null && stop.departure < date) continue
                val a = access[stop.countryIso2]
                val country = world.countries[stop.countryIso2]?.name ?: stop.countryIso2
                accessWarningFor(a, country, date)?.let { if (accessSeen.add(it.message)) out.add(it) }
            }
        }
        return out
    }

    /**
     * The entry warning for a single destination on [date], or null when entry is visa-free,
     * freedom, a residence, or already covered by a held document. Shared by [projectionWarningsFor]
     * (whole trip) and the stop editor (a single stop) so both warn identically.
     */
    fun accessWarningFor(access: Access?, countryName: String, date: LocalDate): TripWarning? = when {
        access == null -> TripWarning("trip.access.none", WarningSeverity.DANGER, "No valid entry grant", "No valid entry grant found for $countryName on ${date.formatHuman()}.")
        access.level == VISA_REQUIRED || access.level == REFUSED -> TripWarning(
            "trip.access.blocked",
            WarningSeverity.DANGER,
            "Entry blocked",
            "$countryName is ${if (access.level == REFUSED) "entry refused" else "visa required"} on ${date.formatHuman()}.",
        )
        access.level == ETA || access.level == E_VISA -> TripWarning("trip.access.extraStep", WarningSeverity.WARNING, "Extra step required", "$countryName needs ${access.level.label()} on ${date.formatHuman()}.")
        else -> null
    }

    /** One sentence, every surface: the findings line and the live gap dialog say this identically. */
    fun gapSentence(leftLabel: String, rightLabel: String, gapDays: Int): String =
        "There is a $gapDays-day gap between $leftLabel and $rightLabel, so this trip is not continuous."

    fun gapWarningsFor(trip: Trip): List<TripWarning> {
        val ordered = TripModel.sortedStops(trip.stops)
        val out = mutableListOf<TripWarning>()
        for (i in 0 until ordered.lastIndex) {
            val departure = ordered[i].departure ?: continue
            val nextArrival = ordered[i + 1].arrival
            if (nextArrival > departure.plusDays(1)) {
                val gapDays = ChronoUnit.DAYS.between(departure.plusDays(1), nextArrival).toInt()
                out.add(
                    TripWarning(
                        "trip.gap",
                        WarningSeverity.WARNING,
                        "Gap between stops",
                        gapSentence("stop ${i + 1}", "stop ${i + 2}", gapDays),
                    ),
                )
            }
        }
        return out
    }

    /**
     * Consecutive stops in one trip that overlap — the next stop's arrival is strictly before the
     * previous stop's departure, so the traveller would be in two places at once. Mirrors the
     * trip-level [overlapWarningsFor] for stops *within* a single trip (which that function does not
     * cover). A back-to-back handover (next arrival == previous departure) is fine, not an overlap.
     */
    fun stopOverlapWarningsFor(trip: Trip): List<TripWarning> {
        val ordered = TripModel.sortedStops(trip.stops)
        val out = mutableListOf<TripWarning>()
        for (i in 0 until ordered.lastIndex) {
            val departure = ordered[i].departure ?: continue
            val nextArrival = ordered[i + 1].arrival
            if (nextArrival.isBefore(departure)) {
                val overlapDays = ChronoUnit.DAYS.between(nextArrival, departure).toInt()
                out.add(
                    TripWarning(
                        "trip.stopOverlap",
                        WarningSeverity.WARNING,
                        "Overlapping stops",
                        "Stop ${i + 2} starts ${daysLabel(overlapDays.coerceAtLeast(1))} before stop ${i + 1} ends — " +
                            "you can only be in one place at a time. Move stop ${i + 2} later, or end stop ${i + 1} earlier.",
                    ),
                )
            }
        }
        return out
    }

}
