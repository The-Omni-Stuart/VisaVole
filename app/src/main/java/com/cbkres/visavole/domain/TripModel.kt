package com.cbkres.visavole.domain

import com.cbkres.visavole.data.StayRule
import com.cbkres.visavole.data.WorldData
import com.cbkres.visavole.domain.AccessLevel.E_VISA
import com.cbkres.visavole.domain.AccessLevel.ETA
import com.cbkres.visavole.domain.AccessLevel.FREEDOM
import com.cbkres.visavole.domain.AccessLevel.REFUSED
import com.cbkres.visavole.domain.AccessLevel.RESIDENCE
import com.cbkres.visavole.domain.AccessLevel.VISA_FREE
import com.cbkres.visavole.domain.AccessLevel.VISA_REQUIRED
import com.cbkres.visavole.domain.DocKind.Custom
import com.cbkres.visavole.domain.DocKind.Holding
import com.cbkres.visavole.domain.DocKind.Passport
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

object TripModel {

    private const val WARNING_THRESHOLD_DAYS = 7

    private fun daysLabel(count: Int): String = "$count ${if (count == 1) "day" else "days"}"

    /** An inclusive (start, end) stay segment in a zone; [end] is already clamped to `asOf`. */
    private data class ZoneSegment(val start: LocalDate, val end: LocalDate)

    private data class ZoneUsage(
        val segments: MutableList<ZoneSegment> = mutableListOf(),
        val tripIds: MutableSet<String> = mutableSetOf(),
        val activeTripIds: MutableSet<String> = mutableSetOf(),
        val activeTripDays: MutableMap<String, Int> = mutableMapOf(),
        var rule: StayRule? = null,
    )

    private data class DocEntry(val start: LocalDate, val end: LocalDate?, val tripId: String)

    private data class TrippedStop(val tripId: String, val stop: TripStop)

    fun sections(trips: List<Trip>, today: LocalDate = LocalDate.now(ZoneOffset.UTC)): TripSections {
        val upcoming = trips.filter { it.statusAt(today) == TripStatus.UPCOMING }.sortedBy { it.firstArrival }
        val current = trips.filter { it.statusAt(today) == TripStatus.ONGOING }.sortedBy { it.firstArrival }
        val previous = trips.filter { it.statusAt(today) == TripStatus.PREVIOUS }.sortedByDescending { it.firstArrival }
        return TripSections(upcoming, current, previous)
    }

    fun validateTrip(trip: Trip, world: WorldData, today: LocalDate = LocalDate.now(ZoneOffset.UTC)): List<TripWarning> {
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

    fun closeTrip(trip: Trip, departure: LocalDate): Trip {
        val last = sortedStops(trip.stops).lastOrNull() ?: return trip
        val safeDeparture = departure.takeIf { !it.isBefore(last.arrival) } ?: last.arrival
        return trip.copy(
            stops = trip.stops.map { stop ->
                when {
                    // A final stop that is already closed keeps its earlier departure: extending
                    // recorded history would silently inflate the country's zone usage (P2-12).
                    // Truncating to an earlier close is allowed.
                    stop.id == last.id ->
                        if (stop.departure == null || stop.departure.isAfter(safeDeparture)) stop.copy(departure = safeDeparture) else stop
                    stop.departure == null -> stop.copy(departure = safeDeparture)
                    else -> stop
                }
            },
        )
    }

    fun calculate(
        trips: List<Trip>,
        docs: List<Document>,
        world: WorldData,
        today: LocalDate = LocalDate.now(ZoneOffset.UTC),
        statuses: Map<String, DocStatus>? = null,
        /** The publish's access map, reused for the zone suppression set instead of a second
         *  full `AccessModel.compute` (P2-28). Null (standalone/tests) computes its own. */
        access: Map<String, Access>? = null,
        /** The publish's per-document candidate maps, shared by every gate probe (P2-27). */
        candidateMaps: Map<String, Map<String, Access>>? = null,
    ): TripCalculation {
        val tripSections = sections(trips, today)
        val docStatuses = statuses ?: DocStatus.all(docs, trips, world, today)
        // P3-4: derive the entry-status map from the threaded DocStatus.entries instead of
        // re-running computeEntryStatus per doc — DocStatus.all already computed each one.
        val entryStatus = docStatuses.mapNotNull { (id, status) -> status.entries?.let { id to it } }.toMap()
        val zoneAllowances = zoneUsages(trips, docs, world, today, access).mapNotNull { (key, usage) ->
            zoneSnapshot(key, usage, world, today, docs, trips, docStatuses, candidateMaps)
        }
        // Every document gets a snapshot, passports included: the Documents tab's top display
        // surfaces the ones with an expiry (all visas, passports/residences nearing theirs); the
        // Trips tab keeps filtering the "doc:" snapshots out by key.
        val docAllowances = docs
            .mapNotNull { documentSnapshot(it, entryStatus[it.id], docStatuses[it.id], today, docs) }
        val allowances = (zoneAllowances + docAllowances)
            .distinctBy { it.key }
            .sortedByDescending { primaryScore(it) }
        return TripCalculation(tripSections, allowances, entryStatus)
    }

    fun entryStatusFor(
        docs: List<Document>,
        trips: List<Trip>,
        world: WorldData,
        today: LocalDate = LocalDate.now(ZoneOffset.UTC),
    ): Map<String, EntryStatus> =
        docs
            .filter { isVisaLike(it, world) || it.entryType() != null }
            .associate { it.id to computeEntryStatus(it, trips, today) }

    /**
     * The entry status of a single document, or null when it carries no entry count and is not
     * visa-like (e.g. a passport). The per-document form of [entryStatusFor].
     */
    fun entryStatusFor(
        doc: Document,
        trips: List<Trip>,
        world: WorldData,
        today: LocalDate = LocalDate.now(ZoneOffset.UTC),
    ): EntryStatus? =
        if (isVisaLike(doc, world) || doc.entryType() != null) computeEntryStatus(doc, trips, today) else null

    /**
     * The docs with their `expiry` clamped to their unified effective expiry (earliest of date
     * expiry, entry exhaustion, and supersession — see [DocStatus]), so date-only consumers (map,
     * projections) see the same validity. Unconstrained docs pass through unchanged.
     */
    fun effectiveDocs(
        docs: List<Document>,
        trips: List<Trip>,
        world: WorldData,
        today: LocalDate = LocalDate.now(ZoneOffset.UTC),
        statuses: Map<String, DocStatus>? = null,
    ): List<Document> {
        val all = statuses ?: DocStatus.all(docs, trips, world, today)
        return docs.map { doc ->
            all[doc.id]?.effectiveExpiry?.let { doc.copy(expiry = it.toString()) } ?: doc
        }
    }

    fun overlapWarningsFor(
        candidate: Trip,
        trips: List<Trip>,
        docs: List<Document>,
        world: WorldData,
        today: LocalDate = LocalDate.now(ZoneOffset.UTC),
    ): List<TripWarning> {
        val passports = AccessModel.validHomeCountries(docs, world, today, trips = trips)
        val out = mutableListOf<TripWarning>()
        val seen = HashSet<String>()
        for (cs in candidate.stops) {
            for (other in trips.filter { it.id != candidate.id }) {
                for (os in other.stops) {
                    val overlap = overlapDays(cs.arrival, cs.departure ?: today, os.arrival, os.departure ?: today)
                    if (overlap <= 1) continue
                    val sameCountry = cs.countryIso2 == os.countryIso2
                    val csRule = world.stayRuleFor(cs.countryIso2, passports, today)
                    val osRule = world.stayRuleFor(os.countryIso2, passports, today)
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

    /**
     * The entry warning for a single destination on [date], or null when entry is visa-free,
     * freedom, a residence, or already covered by a held document. Shared by [projectionWarningsFor]
     * (whole trip) and the stop editor (a single stop) so both warn identically.
     */
    fun accessWarningFor(access: Access?, countryName: String, date: LocalDate): TripWarning? = when {
        access == null -> TripWarning("trip.access.none", WarningSeverity.DANGER, "No valid entry grant", "No valid entry grant found for $countryName on $date.")
        access.level == VISA_REQUIRED || access.level == REFUSED -> TripWarning(
            "trip.access.blocked",
            WarningSeverity.DANGER,
            "Entry blocked",
            "$countryName is ${if (access.level == REFUSED) "entry refused" else "visa required"} on $date.",
        )
        access.level == ETA || access.level == E_VISA -> TripWarning("trip.access.extraStep", WarningSeverity.WARNING, "Extra step required", "$countryName needs ${access.level.label()} on $date.")
        else -> null
    }

    fun projectionWarningsFor(
        candidate: Trip,
        trips: List<Trip>,
        docs: List<Document>,
        world: WorldData,
        today: LocalDate = LocalDate.now(ZoneOffset.UTC),
    ): List<TripWarning> {
        val out = mutableListOf<TripWarning>()
        val projection = candidate.stops.mapNotNull { it.departure }.maxOrNull() ?: today
        val asOf = if (projection.isBefore(today)) today else projection
        val passports = AccessModel.validHomeCountries(docs, world, asOf, trips = trips)

        val zones = linkedSetOf<String>()
        for (stop in candidate.stops) {
            world.stayRuleFor(stop.countryIso2, passports, asOf)?.let { zones.add(world.zoneKeyFor(it)) }
        }
        val usageWith = zoneUsages(trips + candidate, docs, world, asOf)
        for (key in zones) {
            val usage = usageWith[key] ?: continue
            val rule = usage.rule ?: continue
            val used = if (rule.windowType == "rolling") rollingUsage(rule, usage.segments, asOf).first
            else perEntryUsage(rule, usage.segments, asOf).first
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
            // stop is a re-entry (P2-13): computeEntryStatus alone merged them and downgraded
            // "not enough entries" to "last entry used".
            val remaining = projectedEntryUsage(doc, trips, candidate, asOf) ?: continue
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

        val dates = linkedSetOf<LocalDate>()
        for (stop in candidate.stops) {
            if (!stop.arrival.isBefore(today)) dates.add(stop.arrival)
            val end = stop.departure ?: today
            if (!end.isBefore(today) && !end.isBefore(stop.arrival)) dates.add(end)
        }
        val accessSeen = HashSet<String>()
        for (date in dates) {
            val effective = effectiveDocs(docs, trips, world, date)
            val access = AccessModel.compute(effective, world, date)
            for (stop in candidate.stops) {
                val stopEnd = stop.departure ?: today
                if (stop.arrival > date || stopEnd < date) continue
                val a = access[stop.countryIso2]
                val country = world.countries[stop.countryIso2]?.name ?: stop.countryIso2
                accessWarningFor(a, country, date)?.let { if (accessSeen.add(it.message)) out.add(it) }
            }
        }
        return out
    }

    fun primaryAllowance(allowances: List<AllowanceSnapshot>, focusedKey: String? = null): AllowanceSnapshot? {
        if (focusedKey != null) {
            allowances.firstOrNull { it.key == focusedKey }?.let { return it }
        }
        return allowances.maxByOrNull { primaryScore(it) }
    }

    fun sortedStops(stops: List<TripStop>): List<TripStop> = stops.sortedBy { it.arrival }

    /**
     * The distinct stay-zone allowances a trip touches — one per zone, so several stops in the same
     * zone (e.g. two Schengen stops) yield a single face. A zone is "touched" when the trip has a
     * stop in it ([AllowanceSnapshot.relevantTripIds]). Ordered most-useful-first for the trip card's
     * multi-zone allowance pill: the current destination (the zone this trip is most-in-progress in,
     * when ongoing) first, then overstayed zones (most-over first), then the rest by fewest remaining
     * days/entries. A single-zone trip simply gets its one zone back, unchanged.
     */
    fun zoneAllowancesFor(trip: Trip, allowances: List<AllowanceSnapshot>): List<AllowanceSnapshot> {
        val zones = allowances.filter { !it.key.startsWith("doc:") && it.relevantTripIds.contains(trip.id) }
        if (zones.size <= 1) return zones
        return zones.sortedWith(Comparator { a, b ->
            val aCurrent = a.currentTripId == trip.id
            val bCurrent = b.currentTripId == trip.id
            if (aCurrent != bCurrent) return@Comparator if (aCurrent) -1 else 1
            if (a.overstayDays != b.overstayDays) return@Comparator b.overstayDays - a.overstayDays
            compareRemaining(a, b)
        })
    }

    private fun compareRemaining(a: AllowanceSnapshot, b: AllowanceSnapshot): Int {
        val ra = remainingOf(a)
        val rb = remainingOf(b)
        return when {
            ra == null && rb == null -> 0
            ra == null -> 1
            rb == null -> -1
            else -> ra.compareTo(rb)
        }
    }

    private fun remainingOf(a: AllowanceSnapshot): Int? = a.remainingDays ?: a.entryRemaining

    fun firstGapIndex(stops: List<TripStop>): Int? {
        val ordered = sortedStops(stops)
        for (i in 0 until ordered.lastIndex) {
            // P3-1: skip (not bail on) an open stop so a later gap is still found — matches
            // gapWarningsFor, which `continue`s past open stops.
            val departure = ordered[i].departure ?: continue
            if (ordered[i + 1].arrival > departure.plusDays(1)) return i + 1
        }
        return null
    }

    fun gapWarningsFor(trip: Trip, today: LocalDate = LocalDate.now(ZoneOffset.UTC)): List<TripWarning> {
        val ordered = sortedStops(trip.stops)
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
                        "There is a $gapDays-day gap between stop ${i + 1} and stop ${i + 2}, so this trip is not continuous.",
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
        val ordered = sortedStops(trip.stops)
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

    /**
     * Split [stops] at the gap before sorted position [index] (see [firstGapIndex]): the left half
     * keeps the stops before the gap, clamping a missing departure to [closeDate] so the trip stays
     * valid on its own; the right half is the remaining stops. Both halves are sorted by arrival.
     */
    fun splitStops(stops: List<TripStop>, index: Int, closeDate: LocalDate): Pair<List<TripStop>, List<TripStop>> {
        val ordered = sortedStops(stops)
        val left = ordered.take(index).map { if (it.departure == null) it.copy(departure = closeDate) else it }
        return left to ordered.drop(index)
    }

    fun isShortStaySuppressed(access: Access?): Boolean =
        access?.level == FREEDOM || access?.level == RESIDENCE

    private fun primaryScore(a: AllowanceSnapshot): Int = when {
        a.currentTripId != null -> 100_000 + statusScore(a)
        a.status == AllowanceStatus.DANGER -> 50_000 + statusScore(a)
        a.status == AllowanceStatus.EXHAUSTED -> 40_000 + statusScore(a)
        a.status == AllowanceStatus.WARNING -> 20_000 + statusScore(a)
        a.remainingDays != null && a.remainingDays <= WARNING_THRESHOLD_DAYS -> 10_000 + statusScore(a)
        else -> statusScore(a)
    }

    private fun statusScore(a: AllowanceSnapshot): Int = when (a.status) {
        AllowanceStatus.DANGER -> 4
        AllowanceStatus.EXHAUSTED -> 3
        AllowanceStatus.WARNING -> 2
        AllowanceStatus.OK -> 1
        AllowanceStatus.UNLIMITED, AllowanceStatus.UNKNOWN -> 0
    }

    private fun zoneUsages(
        trips: List<Trip>,
        docs: List<Document>,
        world: WorldData,
        asOf: LocalDate,
        access: Map<String, Access>? = null,
    ): Map<String, ZoneUsage> {
        // Stay rules resolve against the passports that can actually support a stay — an expired
        // passport's nationality must not drive the allowance window (P2-30).
        val passports = AccessModel.validHomeCountries(docs, world, asOf, trips = trips)
        val access = access ?: AccessModel.compute(docs, world, asOf)
        val suppressed = access.filterValues { it.level == FREEDOM || it.level == RESIDENCE }.keys
        val out = HashMap<String, ZoneUsage>()
        for (trip in trips) {
            for (stop in trip.stops) {
                // Stops not yet started as of asOf contribute no usage. This includes an
                // open-ended final stop whose arrival lies after the projection date (P3-3): its
                // days are deliberately left out of the allowance usage, while its access is still
                // checked per stop — the projection is a snapshot as of asOf and a future stop has
                // not yet consumed any of the window.
                if (stop.arrival.isAfter(asOf)) continue
                val end = if (stop.departure != null && stop.departure.isBefore(asOf)) stop.departure else asOf
                if (end.isBefore(stop.arrival)) continue
                val isSuppressed = stop.countryIso2 in suppressed
                val rule = world.stayRuleFor(stop.countryIso2, passports, asOf, allowSynthetic = !isSuppressed)
                    ?: if (!isSuppressed) {
                        access[stop.countryIso2]
                            ?.takeIf { it.level == VISA_FREE && it.days != null && it.days > 0 }
                            ?.let { world.syntheticStayRuleFor(stop.countryIso2, it.days!!) }
                    } else {
                        null
                    }
                    ?: continue
                val key = world.zoneKeyFor(rule)
                val usage = out.getOrPut(key) { ZoneUsage() }
                usage.rule = rule
                usage.tripIds.add(trip.id)
                if (stop.departure == null || !stop.departure.isBefore(asOf)) {
                    usage.activeTripIds.add(trip.id)
                }
                if (isSuppressed) continue
                // One (start, end) segment, not one LocalDate per day: a year-old ongoing stop is
                // one pair instead of ~365 dates re-materialised on every publish (P2-28).
                usage.segments.add(ZoneSegment(stop.arrival, end))
                // P3-2: track each trip's zone days (clamped to asOf) so the current-trip pick is
                // the most-in-progress active trip, not the first one inserted.
                usage.activeTripDays.merge(trip.id, ChronoUnit.DAYS.between(stop.arrival, end).toInt() + 1, Int::plus)
            }
        }
        return out
    }

    private fun zoneSnapshot(
        key: String,
        usage: ZoneUsage,
        world: WorldData,
        asOf: LocalDate,
        docs: List<Document>,
        trips: List<Trip>,
        statuses: Map<String, DocStatus>,
        candidateMaps: Map<String, Map<String, Access>>? = null,
    ): AllowanceSnapshot? {
        val rule = usage.rule ?: return null
        val kind = if (rule.windowType == "rolling") AllowanceKind.ROLLING else AllowanceKind.PER_ENTRY
        val (used, overstay) = if (kind == AllowanceKind.ROLLING) rollingUsage(rule, usage.segments, asOf)
        else perEntryUsage(rule, usage.segments, asOf)
        val windowDays = rule.windowDays
        val rawRemaining = windowDays?.let { it - used }
        val cap = expiryCapFor(rule, docs, world, asOf, trips, statuses, candidateMaps)
        val capped = when {
            rawRemaining == null || cap == null -> rawRemaining
            else -> minOf(rawRemaining, ChronoUnit.DAYS.between(asOf, cap).toInt())
        }
        val remaining = capped?.coerceAtLeast(0)
        // P3: an illegal stay comes from the window (used > windowDays) or the document cap
        // landing in the past; capped = min(rawRemaining, daysToCap) subsumes both, so the
        // overstayed count is -capped's magnitude (which equals the window overstay when the
        // window is the binding constraint). Every "N days over" surface — ring centre, summary
        // line, switcher card, trip chip — already shows this via the overstayDays branch, so a
        // cap-driven DANGER no longer falls through to "0 days left".
        val overstayDays = maxOf(overstay, (capped?.let { -it } ?: 0).coerceAtLeast(0))
        val status = when {
            overstayDays > 0 -> AllowanceStatus.DANGER
            remaining != null && remaining <= WARNING_THRESHOLD_DAYS -> AllowanceStatus.WARNING
            rule.windowDays == null -> AllowanceStatus.UNKNOWN
            else -> AllowanceStatus.OK
        }
        val title = when {
            rule.zoneName.isNotBlank() -> rule.zoneName
            rule.countries.size == 1 -> world.countries[rule.countries.first()]?.name ?: rule.displayName
            else -> rule.displayName
        }
        val usedDays = if (capped != null && rawRemaining != null && capped < rawRemaining)
            windowDays?.let { it - capped.coerceAtLeast(0) } ?: used
        else used
        return AllowanceSnapshot(
            key = key,
            title = title,
            shortTitle = if (rule.countries.size == 1) rule.countries.first() else null,
            subtitle = rule.windowSummary(),
            kind = kind,
            status = status,
            usedDays = usedDays,
            remainingDays = remaining,
            totalDays = windowDays,
            windowPeriodDays = rule.windowPeriodDays,
            overstayDays = overstayDays,
            currentTripId = usage.activeTripIds.maxByOrNull { usage.activeTripDays[it] ?: 0 },
            relevantTripIds = usage.tripIds.toList(),
        )
    }

    /**
     * The earliest date on which the document gating entry to any country of [rule] stops
     * authorising a stay — the day a stay in this zone stops being legal, regardless of the
     * window. A stay runs on the document its stop declares ([TripStop.documentId]): that
     * document gates the stay even after it lapses (capping the window into the past → DANGER)
     * and even where a passport could in principle cover the country — the declared document is
     * what the stay is on. Only stops that are actually in progress at [asOf] declare a gate: a
     * document used by a *past* stay must not cap today's zone (its exhaustion belongs to that
     * stay, not this one). Stops without a declared document fall back to the best document held
     * for the country, then its lapsed-gate counterpart. Null when no held document documents
     * the zone.
     */
    private fun expiryCapFor(
        rule: StayRule,
        docs: List<Document>,
        world: WorldData,
        asOf: LocalDate,
        trips: List<Trip>,
        statuses: Map<String, DocStatus>,
        candidateMaps: Map<String, Map<String, Access>>? = null,
    ): LocalDate? {
        val docById = docs.associateBy { it.id }
        return rule.countries
            .mapNotNull { country ->
                val declared = trips
                    .flatMap { it.stops }
                    .filter {
                        it.countryIso2 == country &&
                            !it.arrival.isAfter(asOf) &&
                            (it.departure == null || !it.departure.isBefore(asOf))
                    }
                    .mapNotNull { it.documentId }
                    .filter { docById.containsKey(it) }
                    .toSet()
                val gateIds = if (declared.isNotEmpty()) declared
                else listOfNotNull(
                    // Shared per-document candidate maps (built once per publish) keep the probe
                    // from rebuilding every doc's full candidate map per zone country (P2-27).
                    AccessModel.bestDocumentId(country, docs, world, asOf, trips = trips, statuses = statuses, candidateMaps = candidateMaps)
                        ?: AccessModel.gateDocumentId(country, docs, world, asOf, trips = trips, statuses = statuses, candidateMaps = candidateMaps),
                )
                gateIds
                    .mapNotNull { id ->
                        val status = statuses[id] ?: return@mapNotNull null
                        gateCapDate(docById.getValue(id), status, docs)
                    }
                    .minOrNull()
            }
            .minOrNull()
    }

    /**
     * The day [doc] stops authorising a stay. A document whose entry is in progress
     * ([EntryState.IN_USE]) keeps the current stay alive until its own date expiry — an entry
     * count limits re-entries, not the stay you are already in; every other state falls back to
     * the unified [DocStatus.effectiveExpiry] (entry exhaustion, supersession, lapsed date).
     */
    private fun gateCapDate(
        doc: Document,
        status: DocStatus,
        docs: List<Document>,
    ): LocalDate? {
        if (status.entries?.state != EntryState.IN_USE) return status.effectiveExpiry
        val own = doc.expiry?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val superseded = doc.supersededBy
            ?.let { sid -> docs.firstOrNull { it.id == sid }?.validFrom }
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        return listOfNotNull(own, superseded).minOrNull() ?: status.effectiveExpiry
    }

    /** Union of the zone's segments (overlapping stays on the same days count once). */
    private fun mergedSegments(segments: List<ZoneSegment>): List<Pair<LocalDate, LocalDate>> {
        val out = mutableListOf<Pair<LocalDate, LocalDate>>()
        for (seg in segments.sortedBy { it.start }) {
            val last = out.lastOrNull()
            if (last != null && !seg.start.isAfter(last.second)) {
                out[out.lastIndex] = last.first to (if (seg.end.isAfter(last.second)) seg.end else last.second)
            } else {
                out.add(seg.start to seg.end)
            }
        }
        return out
    }

    private fun rollingUsage(rule: StayRule, segments: List<ZoneSegment>, asOf: LocalDate): Pair<Int, Int> {
        val period = rule.windowPeriodDays
        val start = if (period != null) asOf.minusDays(period - 1L) else LocalDate.MIN
        var used = 0
        for ((s, e) in mergedSegments(segments)) {
            val lo = if (s.isAfter(start)) s else start
            val hi = if (e.isBefore(asOf)) e else asOf
            if (!lo.isAfter(hi)) used += ChronoUnit.DAYS.between(lo, hi).toInt() + 1
        }
        val overstay = rule.windowDays?.let { (used - it).coerceAtLeast(0) } ?: 0
        return used to overstay
    }

    private fun perEntryUsage(rule: StayRule, segments: List<ZoneSegment>, asOf: LocalDate): Pair<Int, Int> {
        // Contiguous entries: segments joined while the gap to the next is at most one day (the
        // day-level rule the old per-day set implemented).
        val entries = mutableListOf<Triple<LocalDate, LocalDate, List<Pair<LocalDate, LocalDate>>>>()
        for ((s, e) in mergedSegments(segments)) {
            val last = entries.lastOrNull()
            if (last == null || s > last.second.plusDays(1)) {
                entries.add(Triple(s, e, listOf(s to e)))
            } else {
                val (ls, le, segs) = last
                entries[entries.lastIndex] = Triple(ls, if (e.isAfter(le)) e else le, segs + (s to e))
            }
        }
        val active = entries.firstOrNull { it.first <= asOf && asOf <= it.second }
        var used = 0
        if (active != null) {
            for ((s, e) in active.third) {
                val hi = if (e.isBefore(asOf)) e else asOf
                if (!s.isAfter(hi)) used += ChronoUnit.DAYS.between(s, hi).toInt() + 1
            }
        }
        val overstay = rule.windowDays?.let { (used - it).coerceAtLeast(0) } ?: 0
        return used to overstay
    }

    private fun documentSnapshot(
        doc: Document,
        entry: EntryStatus?,
        status: DocStatus?,
        asOf: LocalDate,
        docs: List<Document>,
    ): AllowanceSnapshot? {
        val total = entry?.total ?: entryTotalFor(doc.entryType())
        val used = entry?.used ?: 0
        val remaining = entry?.remaining
        val expiry = status?.let { gateCapDate(doc, it, docs) } ?: entry?.effectiveExpiry ?: iso(doc.expiry)
        val daysToExpiry = expiry?.let { ChronoUnit.DAYS.between(asOf, it) }
        // Only the entry type and the expiry date here — the "N days left" / "N entries
        // remaining" halves live on the coloured summary line of the tab-top display.
        val subtitle = buildString {
            entryTotalFor(doc.entryType())?.let {
                append(when (it) {
                    1 -> "Single entry"
                    2 -> "Double entry"
                    else -> "Multiple entry"
                })
            }
            expiry?.let {
                if (isNotEmpty()) append(" · ")
                append("Expires $it")
            }
        }
        val status = when {
            expiry != null && expiry.isBefore(asOf) -> AllowanceStatus.DANGER
            total != null && remaining == 0 && entry?.state != EntryState.IN_USE -> AllowanceStatus.EXHAUSTED
            total != null && remaining == 1 -> AllowanceStatus.WARNING
            daysToExpiry != null && daysToExpiry in 0..WARNING_THRESHOLD_DAYS.toLong() -> AllowanceStatus.WARNING
            total != null && remaining == 0 && entry?.state == EntryState.IN_USE -> AllowanceStatus.WARNING
            else -> AllowanceStatus.OK
        }
        val remainingDays = daysToExpiry?.let { maxOf(0, it.toInt()) }
        return AllowanceSnapshot(
            key = "doc:${doc.id}",
            title = doc.label,
            subtitle = subtitle.takeIf { it.isNotEmpty() },
            kind = if (total != null) AllowanceKind.ENTRY_COUNT else AllowanceKind.DOCUMENT_VALIDITY,
            status = status,
            remainingDays = remainingDays,
            entryTotal = total,
            entryUsed = used,
            entryRemaining = remaining,
            effectiveExpiry = expiry,
            currentTripId = if (entry?.state == EntryState.IN_USE) entry.sourceTripId else null,
            relevantTripIds = entry?.sourceTripId?.let { listOf(it) } ?: emptyList(),
            // Set even when the entry has lapsed mid-stay (state EXPIRED), so an expired doc you're
            // still staying on is recognised as "in use" by the ring's pulse.
            inUseTripId = entry?.activeTripId,
        )
    }

    private fun computeEntryStatus(doc: Document, trips: List<Trip>, asOf: LocalDate): EntryStatus {
        val total = entryTotalFor(doc.entryType())
        val from = iso(doc.validFrom)
        val stops = trips
            .flatMap { trip ->
                trip.stops
                    .filter { it.documentId == doc.id && (from == null || !it.arrival.isBefore(from)) }
                    .map { TrippedStop(trip.id, it) }
            }
            .sortedBy { it.stop.arrival }
        val entries = mutableListOf<DocEntry>()
        for (ts in stops) {
            val last = entries.lastOrNull()
            if (last == null || (last.end != null && ts.stop.arrival > last.end.plusDays(1))) {
                entries.add(DocEntry(ts.stop.arrival, ts.stop.departure, ts.tripId))
            } else {
                entries[entries.lastIndex] = last.copy(end = mergeEnd(last.end, ts.stop.departure))
            }
        }
        val usedEntries = entries.filter { it.start <= asOf }
        val used = usedEntries.size
        val active = entries.firstOrNull { it.start <= asOf && (it.end == null || asOf <= it.end) }
        val lastCompleted = usedEntries
            .mapNotNull { e -> e.end?.let { e to it } }
            .maxByOrNull { it.second }
        val lastUsedOn = if (active != null) asOf else lastCompleted?.second
        val sourceTripId = if (active != null) active.tripId else lastCompleted?.first?.tripId
        val original = iso(doc.expiry)
        // Exhaustion by entry count is an effective expiry only once the stay is actually OVER
        // (active == null): while an entry is in progress the stay runs on the document's own date
        // expiry, so a single-entry visa mid-stay must not report "expires today" — that poisoned
        // the edit guard and capped the zone ring at 0 days.
        val exhaustion = if (active == null) lastCompleted?.second else null
        val effective = when {
            total != null && used >= total && exhaustion != null && !exhaustion.isAfter(asOf) ->
                if (original == null || exhaustion.isBefore(original)) exhaustion else original
            else -> original
        }
        val state = when {
            effective != null && effective.isBefore(asOf) -> EntryState.EXPIRED
            total != null && used >= total -> if (active != null) EntryState.IN_USE else EntryState.EXHAUSTED
            active != null -> EntryState.IN_USE
            else -> EntryState.AVAILABLE
        }
        return EntryStatus(
            total, used, total?.minus(used), state, effective, lastUsedOn, sourceTripId,
            activeTripId = active?.tripId,
        )
    }

    /**
     * The remaining entries of [doc] once [candidate] is added, for the projection warnings:
     * existing entries are counted as at [asOf] (as in [computeEntryStatus]) PLUS the candidate's
     * own stops — a candidate stop counts as a (re-)entry unless it is contiguous with the entry
     * before it, where an open-ended entry is treated as ending at [asOf] (P2-13). Null when the
     * doc carries no entry count.
     */
    private fun projectedEntryUsage(
        doc: Document,
        trips: List<Trip>,
        candidate: Trip,
        asOf: LocalDate,
    ): Int? {
        val total = entryTotalFor(doc.entryType()) ?: return null
        val from = iso(doc.validFrom)
        val all = (trips + candidate)
            .flatMap { trip ->
                trip.stops
                    .filter { it.documentId == doc.id && (from == null || !it.arrival.isBefore(from)) }
                    .map { TrippedStop(trip.id, it) }
            }
            .sortedBy { it.stop.arrival }
        val entries = mutableListOf<DocEntry>()
        for (ts in all) {
            val last = entries.lastOrNull()
            // Open entry: for the projection it ends at [asOf], so a separated future stop is a
            // re-entry instead of merging into the unknown open stay.
            val lastEnd = last?.end ?: asOf
            if (last == null || ts.stop.arrival > lastEnd.plusDays(1)) {
                entries.add(DocEntry(ts.stop.arrival, ts.stop.departure, ts.tripId))
            } else {
                entries[entries.lastIndex] = last.copy(end = mergeEnd(last.end, ts.stop.departure))
            }
        }
        val used = entries.count { it.start <= asOf || it.tripId == candidate.id }
        return total - used
    }

    private fun isVisaLike(doc: Document, world: WorldData): Boolean = when (val k = doc.kind) {
        is Passport -> false
        is Holding -> world.holdings[k.holdingId]?.category?.let { it !in RESIDENCE_LIKE_HOLDING_CATEGORIES } ?: false
        is Custom -> k.kind == "visa" || k.kind == "permit"
    }

    /** The fixed entry count an entry type carries ("single" = 1, "double" = 2, null = unlimited). */
    fun entryTotalFor(type: String?): Int? = when (type) {
        "single" -> 1
        "double" -> 2
        else -> null
    }

    private fun mergeEnd(a: LocalDate?, b: LocalDate?): LocalDate? = when {
        a == null -> b
        b == null -> null
        else -> if (a.isAfter(b)) a else b
    }

    private fun overlapDays(aStart: LocalDate, aEnd: LocalDate, bStart: LocalDate, bEnd: LocalDate): Int {
        val start = if (aStart.isAfter(bStart)) aStart else bStart
        val end = if (aEnd.isBefore(bEnd)) aEnd else bEnd
        if (end.isBefore(start)) return 0
        return ChronoUnit.DAYS.between(start, end).toInt() + 1
    }

    private fun iso(s: String?): LocalDate? =
        s?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
}
