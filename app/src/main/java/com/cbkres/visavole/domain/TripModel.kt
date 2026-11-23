package com.cbkres.visavole.domain

import com.cbkres.visavole.domain.AccessLevel.FREEDOM
import com.cbkres.visavole.domain.AccessLevel.RESIDENCE
import com.cbkres.visavole.domain.DocKind.Custom
import com.cbkres.visavole.domain.DocKind.Holding
import com.cbkres.visavole.domain.DocKind.Passport
import java.time.LocalDate
import java.time.temporal.ChronoUnit

object TripModel {

    fun sections(trips: List<Trip>, today: LocalDate): TripSections {
        val upcoming = trips.filter { it.statusAt(today) == TripStatus.UPCOMING }.sortedBy { it.firstArrival }
        val current = trips.filter { it.statusAt(today) == TripStatus.ONGOING }.sortedBy { it.firstArrival }
        val previous = trips.filter { it.statusAt(today) == TripStatus.PREVIOUS }.sortedByDescending { it.firstArrival }
        return TripSections(upcoming, current, previous)
    }

    fun validateTrip(trip: Trip, world: WorldData, today: LocalDate): List<TripWarning> =
        TripWarnings.validateTrip(trip, world, today)

    fun closeTrip(trip: Trip, departure: LocalDate): Trip {
        val last = sortedStops(trip.stops).lastOrNull() ?: return trip
        val safeDeparture = departure.takeIf { !it.isBefore(last.arrival) } ?: last.arrival
        return trip.copy(
            stops = trip.stops.map { stop ->
                when {
                    // A final stop that is already closed keeps its earlier departure: extending
                    // recorded history would silently inflate the country's zone usage.
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
        today: LocalDate,
        statuses: Map<String, DocStatus>? = null,
        /** The publish's access map, reused for the zone suppression set instead of a second
         *  full `AccessModel.compute`. Null (standalone/tests) computes its own. */
        access: Map<String, Access>? = null,
        /** The publish's per-document candidate maps, shared by every gate probe. */
        candidateMaps: Map<String, Map<String, Access>>? = null,
    ): TripCalculation {
        val tripSections = sections(trips, today)
        val docStatuses = statuses ?: DocStatus.all(docs, trips, world, today)
        // derive the entry-status map from the threaded DocStatus.entries instead of
        // re-running computeEntryStatus per doc — DocStatus.all already computed each one.
        val entryStatus = docStatuses.mapNotNull { (id, status) -> status.entries?.let { id to it } }.toMap()
        val zoneAllowances = zoneUsages(trips, docs, world, today, access, docStatuses).mapNotNull { (key, usage) ->
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

    /**
     * The entry status of a single document, or null when it carries no entry count and is not
     * visa-like (e.g. a passport).
     */
    fun entryStatusFor(
        doc: Document,
        trips: List<Trip>,
        world: WorldData,
        today: LocalDate,
    ): EntryStatus? =
        if (isVisaLike(doc, world) || doc.entryType() != null) computeEntryStatus(doc, trips, today, doc.validFrom.toDateOrNull(), doc.expiry.toDateOrNull()) else null

    /** [entryStatusFor] for callers that already hold [doc]'s parsed dates (see [DocStatus.of]). */
    internal fun entryStatusFor(
        doc: Document,
        trips: List<Trip>,
        world: WorldData,
        today: LocalDate,
        validFromDay: LocalDate?,
        expiryDay: LocalDate?,
    ): EntryStatus? =
        if (isVisaLike(doc, world) || doc.entryType() != null) computeEntryStatus(doc, trips, today, validFromDay, expiryDay) else null

    /**
     * The docs with their `expiry` clamped to their unified effective expiry (earliest of date
     * expiry, entry exhaustion, and supersession — see [DocStatus]), so date-only consumers (map,
     * projections) see the same validity. Unconstrained docs pass through unchanged.
     */
    fun effectiveDocs(
        docs: List<Document>,
        trips: List<Trip>,
        world: WorldData,
        today: LocalDate,
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
        today: LocalDate,
    ): List<TripWarning> = TripWarnings.overlapWarningsFor(candidate, trips, docs, world, today)

    fun accessWarningFor(access: Access?, countryName: String, date: LocalDate): TripWarning? =
        TripWarnings.accessWarningFor(access, countryName, date)

    fun projectionWarningsFor(
        candidate: Trip,
        trips: List<Trip>,
        docs: List<Document>,
        world: WorldData,
        today: LocalDate,
    ): List<TripWarning> = TripWarnings.projectionWarningsFor(candidate, trips, docs, world, today)

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

    /** A rolling zone's live balance is misleading on a trip whose days have all aged out of its window. */
    fun tripCountsToward(trip: Trip, allowance: AllowanceSnapshot, today: LocalDate): Boolean {
        if (allowance.kind != AllowanceKind.ROLLING || allowance.windowPeriodDays == null) return true
        if (allowance.currentTripId == trip.id) return true
        val start = today.minusDays(allowance.windowPeriodDays.toLong() - 1)
        return trip.stops.any { stop ->
            val from = if (stop.arrival.isAfter(start)) stop.arrival else start
            val to = if (stop.departure != null && stop.departure.isBefore(today)) stop.departure else today
            !stop.arrival.isAfter(today) && !from.isAfter(to)
        }
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
            // skip (not bail on) an open stop so a later gap is still found — matches
            // gapWarningsFor, which `continue`s past open stops.
            val departure = ordered[i].departure ?: continue
            if (ordered[i + 1].arrival > departure.plusDays(1)) return i + 1
        }
        return null
    }

    fun gapWarningsFor(trip: Trip): List<TripWarning> =
        TripWarnings.gapWarningsFor(trip)

    fun stopOverlapWarningsFor(trip: Trip): List<TripWarning> =
        TripWarnings.stopOverlapWarningsFor(trip)

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
        AllowanceStatus.UNKNOWN -> 0
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
        val expiry = status?.let { gateCapDate(it) } ?: entry?.effectiveExpiry ?: doc.expiry.toDateOrNull()
        val daysToExpiry = expiry?.let { ChronoUnit.DAYS.between(asOf, it) }
        // Only the entry type and the expiry date here — the "N days left" / "N entries
        // remaining" halves live on the coloured summary line of the tab-top display.
        val subtitle = buildString {
            entryTotalFor(doc.entryType())?.let {
                append(if (it == 1) "Single entry" else "Double entry")
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

    private fun computeEntryStatus(
        doc: Document,
        trips: List<Trip>,
        asOf: LocalDate,
        from: LocalDate?,
        original: LocalDate?,
    ): EntryStatus {
        val total = entryTotalFor(doc.entryType())
        val stops = trips
            .flatMap { trip ->
                trip.stops
                    .filter { it.documentId == doc.id && (from == null || !it.arrival.isBefore(from)) }
                    .map { TrippedStop(trip.id, it) }
            }
            .sortedBy { it.stop.arrival }
        val entries = mergeStaySegments(stops) { it.end }
        val usedEntries = entries.filter { it.start <= asOf }
        val used = usedEntries.size
        val active = entries.firstOrNull { it.start <= asOf && (it.end == null || asOf <= it.end) }
        val lastCompleted = usedEntries
            .mapNotNull { e -> e.end?.let { e to it } }
            .maxByOrNull { it.second }
        val lastUsedOn = if (active != null) asOf else lastCompleted?.second
        val sourceTripId = if (active != null) active.tripId else lastCompleted?.first?.tripId
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
     * before it, where an open-ended entry is treated as ending at [asOf]. Null when the
     * doc carries no entry count.
     */
    internal fun projectedEntryUsage(
        doc: Document,
        trips: List<Trip>,
        candidate: Trip,
        asOf: LocalDate,
    ): Int? {
        val total = entryTotalFor(doc.entryType()) ?: return null
        val from = doc.validFrom.toDateOrNull()
        val all = (trips + candidate)
            .flatMap { trip ->
                trip.stops
                    .filter { it.documentId == doc.id && (from == null || !it.arrival.isBefore(from)) }
                    .map { TrippedStop(trip.id, it) }
            }
            .sortedBy { it.stop.arrival }
        // Open stay: for the projection it ends at [asOf], so a separated future stop is a
        // re-entry instead of merging into the unknown open stay.
        val entries = mergeStaySegments(all) { it.end ?: asOf }
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

}
