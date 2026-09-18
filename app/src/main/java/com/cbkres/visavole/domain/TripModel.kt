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

    private data class ZoneUsage(
        val days: MutableSet<LocalDate> = mutableSetOf(),
        val tripIds: MutableSet<String> = mutableSetOf(),
        val activeTripIds: MutableSet<String> = mutableSetOf(),
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
                    stop.id == last.id -> stop.copy(departure = safeDeparture)
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
    ): TripCalculation {
        val tripSections = sections(trips, today)
        val entryStatus = entryStatusFor(docs, trips, world, today)
        val docStatuses = DocStatus.all(docs, trips, world, today)
        val zoneAllowances = zoneUsages(trips, docs, world, today).mapNotNull { (key, usage) ->
            zoneSnapshot(key, usage, world, today, docs, trips, docStatuses)
        }
        val docAllowances = docs
            .filter { it.kind !is Passport }
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
    ): List<Document> {
        val statuses = DocStatus.all(docs, trips, world, today)
        return docs.map { doc ->
            statuses[doc.id]?.effectiveExpiry?.let { doc.copy(expiry = it.toString()) } ?: doc
        }
    }

    fun overlapWarningsFor(
        candidate: Trip,
        trips: List<Trip>,
        docs: List<Document>,
        world: WorldData,
        today: LocalDate = LocalDate.now(ZoneOffset.UTC),
    ): List<TripWarning> {
        val passports = AccessModel.homeCountries(docs)
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
        val passports = AccessModel.homeCountries(docs)

        val zones = linkedSetOf<String>()
        for (stop in candidate.stops) {
            world.stayRuleFor(stop.countryIso2, passports, asOf)?.let { zones.add(world.zoneKeyFor(it)) }
        }
        val usageWith = zoneUsages(trips + candidate, docs, world, asOf)
        for (key in zones) {
            val usage = usageWith[key] ?: continue
            val rule = usage.rule ?: ruleForZoneKey(world, key) ?: continue
            val used = if (rule.windowType == "rolling") rollingUsage(rule, usage.days, asOf).first
            else perEntryUsage(rule, usage.days, asOf).first
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
            val status = computeEntryStatus(doc, trips + candidate, asOf)
            val total = status.total ?: continue
            val remaining = status.remaining ?: continue
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
                val warn: TripWarning? = when {
                    a == null -> TripWarning("trip.access.none", WarningSeverity.DANGER, "No valid entry grant", "No valid entry grant found for $country on $date.")
                    a.level == VISA_REQUIRED || a.level == REFUSED -> TripWarning(
                        "trip.access.blocked",
                        WarningSeverity.DANGER,
                        "Entry blocked",
                        "$country is ${if (a.level == REFUSED) "entry refused" else "visa required"} on $date.",
                    )
                    a.level == ETA || a.level == E_VISA -> TripWarning("trip.access.extraStep", WarningSeverity.WARNING, "Extra step required", "$country needs ${a.level.label()} on $date.")
                    else -> null
                }
                warn?.let { if (accessSeen.add(it.message)) out.add(it) }
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

    fun firstGapIndex(stops: List<TripStop>): Int? {
        val ordered = sortedStops(stops)
        for (i in 0 until ordered.lastIndex) {
            val departure = ordered[i].departure ?: return null
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
    ): Map<String, ZoneUsage> {
        val passports = AccessModel.homeCountries(docs)
        val access = AccessModel.compute(docs, world, asOf)
        val suppressed = access.filterValues { it.level == FREEDOM || it.level == RESIDENCE }.keys
        val out = HashMap<String, ZoneUsage>()
        for (trip in trips) {
            for (stop in trip.stops) {
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
                var d = stop.arrival
                while (!d.isAfter(end)) {
                    usage.days.add(d)
                    d = d.plusDays(1)
                }
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
    ): AllowanceSnapshot? {
        val rule = usage.rule ?: ruleForZoneKey(world, key) ?: return null
        val kind = if (rule.windowType == "rolling") AllowanceKind.ROLLING else AllowanceKind.PER_ENTRY
        val (used, overstay) = if (kind == AllowanceKind.ROLLING) rollingUsage(rule, usage.days, asOf)
        else perEntryUsage(rule, usage.days, asOf)
        val windowDays = rule.windowDays
        val rawRemaining = windowDays?.let { it - used }
        val cap = expiryCapFor(rule, docs, world, asOf, trips, statuses)
        val capped = when {
            rawRemaining == null || cap == null -> rawRemaining
            else -> minOf(rawRemaining, ChronoUnit.DAYS.between(asOf, cap).toInt())
        }
        val remaining = capped?.coerceAtLeast(0)
        val status = when {
            overstay > 0 -> AllowanceStatus.DANGER
            capped != null && capped < 0 -> AllowanceStatus.DANGER
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
            subtitle = rule.windowSummary(),
            kind = kind,
            status = status,
            usedDays = usedDays,
            remainingDays = remaining,
            totalDays = windowDays,
            windowPeriodDays = rule.windowPeriodDays,
            overstayDays = overstay,
            currentTripId = usage.activeTripIds.firstOrNull(),
            relevantTripIds = usage.tripIds.toList(),
        )
    }

    /**
     * The earliest date on which the document gating entry to any country of [rule] stops
     * authorising a stay — the day a stay in this zone stops being legal, regardless of the
     * window. An in-use entry caps at the document's own expiry; a lapsed gate caps the window
     * into the past (DANGER). Null when no held document documents the zone.
     */
    private fun expiryCapFor(
        rule: StayRule,
        docs: List<Document>,
        world: WorldData,
        asOf: LocalDate,
        trips: List<Trip>,
        statuses: Map<String, DocStatus>,
    ): LocalDate? =
        rule.countries
            .mapNotNull { country ->
                val gate = AccessModel.bestDocumentId(country, docs, world, asOf, trips = trips)
                    ?: AccessModel.gateDocumentId(country, docs, world, asOf, trips = trips)
                gate?.let { id ->
                    val status = statuses[id] ?: return@let null
                    val doc = docs.firstOrNull { it.id == id } ?: return@let null
                    gateCapDate(doc, status, docs)
                }
            }
            .minOrNull()

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

    private fun rollingUsage(rule: StayRule, days: Set<LocalDate>, asOf: LocalDate): Pair<Int, Int> {
        val period = rule.windowPeriodDays
        val start = if (period != null) asOf.minusDays(period - 1L) else LocalDate.MIN
        val used = days.count { !it.isBefore(start) }
        val overstay = rule.windowDays?.let { (used - it).coerceAtLeast(0) } ?: 0
        return used to overstay
    }

    private fun perEntryUsage(rule: StayRule, days: Set<LocalDate>, asOf: LocalDate): Pair<Int, Int> {
        val entries = contiguousEntries(days)
        val active = entries.firstOrNull { it.first() <= asOf && asOf <= it.last() }
        val used = active?.count { !it.isAfter(asOf) } ?: 0
        val overstay = rule.windowDays?.let { (used - it).coerceAtLeast(0) } ?: 0
        return used to overstay
    }

    private fun contiguousEntries(days: Set<LocalDate>): List<List<LocalDate>> {
        val sorted = days.toSortedSet()
        val out = mutableListOf<List<LocalDate>>()
        var current = mutableListOf<LocalDate>()
        for (d in sorted) {
            if (current.isEmpty() || d <= current.last().plusDays(1)) {
                current.add(d)
            } else {
                out.add(current)
                current = mutableListOf(d)
            }
        }
        if (current.isNotEmpty()) out.add(current)
        return out
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
        val daysToExpiry = expiry?.let { ChronoUnit.DAYS.between(asOf, it) }
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
        val effective = when {
            total != null && used >= total && lastUsedOn != null && !lastUsedOn.isAfter(asOf) ->
                if (original == null || lastUsedOn.isBefore(original)) lastUsedOn else original
            else -> original
        }
        val state = when {
            effective != null && effective.isBefore(asOf) -> EntryState.EXPIRED
            total != null && used >= total -> if (active != null) EntryState.IN_USE else EntryState.EXHAUSTED
            active != null -> EntryState.IN_USE
            else -> EntryState.AVAILABLE
        }
        return EntryStatus(total, used, total?.minus(used), state, effective, lastUsedOn, sourceTripId)
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

    private fun ruleForZoneKey(world: WorldData, key: String): StayRule? =
        world.stayRules.firstOrNull { world.zoneKeyFor(it) == key }

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
