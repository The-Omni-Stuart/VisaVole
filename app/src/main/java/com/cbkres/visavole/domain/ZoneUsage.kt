package com.cbkres.visavole.domain

import com.cbkres.visavole.domain.AccessLevel.FREEDOM
import com.cbkres.visavole.domain.AccessLevel.RESIDENCE
import com.cbkres.visavole.domain.AccessLevel.VISA_FREE
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** A zone allowance with no more than this many days left reads as a warning on every surface. */
internal const val WARNING_THRESHOLD_DAYS = 7

/** An inclusive (start, end) stay segment in a zone; [end] is already clamped to `asOf`. */
internal data class ZoneSegment(val start: LocalDate, val end: LocalDate)

internal data class ZoneUsage(
    val segments: MutableList<ZoneSegment> = mutableListOf(),
    val tripIds: MutableSet<String> = mutableSetOf(),
    val activeTripIds: MutableSet<String> = mutableSetOf(),
    val activeTripDays: MutableMap<String, Int> = mutableMapOf(),
    var rule: StayRule? = null,
)

// Memo for stayRuleFor: passports and as-of are fixed per warning call, so the scan is pure in dest.
internal class StayRuleMemo(
    private val world: WorldData,
    private val passports: Set<String>,
    private val asOf: LocalDate,
) {
    private val allowingSynthetic = HashMap<String, StayRule?>()
    private val explicitOnly = HashMap<String, StayRule?>()

    fun forDest(dest: String, allowSynthetic: Boolean = true): StayRule? {
        val memo = if (allowSynthetic) allowingSynthetic else explicitOnly
        if (dest in memo) return memo[dest]
        return world.stayRuleFor(dest, passports, asOf, allowSynthetic).also { memo[dest] = it }
    }
}

internal fun zoneUsages(
    trips: List<Trip>,
    docs: List<Document>,
    world: WorldData,
    asOf: LocalDate,
    access: Map<String, Access>? = null,
    statuses: Map<String, DocStatus>? = null,
): Map<String, ZoneUsage> {
    // Stay rules resolve against the passports that can actually support a stay — an expired
    // passport's nationality must not drive the allowance window.
    val passports = AccessModel.validHomeCountries(docs, world, asOf, trips = trips, statuses = statuses)
    val access = access ?: AccessModel.compute(docs, world, asOf)
    val suppressed = access.filterValues { it.level == FREEDOM || it.level == RESIDENCE }.keys
    val rules = StayRuleMemo(world, passports, asOf)
    val out = HashMap<String, ZoneUsage>()
    for (trip in trips) {
        for (stop in trip.stops) {
            // Stops not yet started as of asOf contribute no usage. This includes an
            // open-ended final stop whose arrival lies after the projection date: its
            // days are deliberately left out of the allowance usage, while its access is still
            // checked per stop — the projection is a snapshot as of asOf and a future stop has
            // not yet consumed any of the window.
            if (stop.arrival.isAfter(asOf)) continue
            val end = if (stop.departure != null && stop.departure.isBefore(asOf)) stop.departure else asOf
            if (end.isBefore(stop.arrival)) continue
            val isSuppressed = stop.countryIso2 in suppressed
            val rule = rules.forDest(stop.countryIso2, allowSynthetic = !isSuppressed)
                ?: if (!isSuppressed) {
                    access[stop.countryIso2]
                        ?.takeIf { it.level == VISA_FREE }
                        ?.days
                        ?.takeIf { it > 0 }
                        ?.let { world.syntheticStayRuleFor(stop.countryIso2, it) }
                } else {
                    null
                }
                ?: continue
            val key = world.zoneKeyFor(rule)
            val usage = out.getOrPut(key) { ZoneUsage() }
            // A zone can be entered through different rules per country (the CA-4 has one window
            // each); pool the stays under the tightest window so stop order never decides the answer.
            usage.rule = tighterRule(usage.rule, rule)
            usage.tripIds.add(trip.id)
            if (stop.departure == null || !stop.departure.isBefore(asOf)) {
                usage.activeTripIds.add(trip.id)
            }
            if (isSuppressed) continue
            // One (start, end) segment, not one LocalDate per day: a year-old ongoing stop is
            // one pair instead of ~365 dates re-materialised on every publish.
            usage.segments.add(ZoneSegment(stop.arrival, end))
            // Track each trip's zone days (clamped to asOf) so the current-trip pick is
            // the most-in-progress active trip, not the first one inserted.
            usage.activeTripDays.merge(trip.id, ChronoUnit.DAYS.between(stop.arrival, end).toInt() + 1, Int::plus)
        }
    }
    return out
}

/** Most restrictive first: a never-refilling pool must not lose a zone to a looser rule of the same size. */
internal val StayRule.windowClass: Int
    get() = when (windowType) {
        "per-entry" -> 2
        "rolling" -> 1
        else -> 0
    }

// Day counts cannot settle a zone whose rules disagree on window type: at equal days the tie fell to the id string.
internal fun tighterRule(current: StayRule?, candidate: StayRule?): StayRule =
    listOfNotNull(current, candidate).minWith(compareBy({ it.windowClass }, { it.windowDays ?: Int.MAX_VALUE }, { it.id }))

internal fun zoneSnapshot(
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
    val kind = when (rule.windowType) {
        "rolling" -> AllowanceKind.ROLLING
        "per-entry" -> AllowanceKind.PER_ENTRY
        else -> AllowanceKind.LIFETIME
    }
    val (used, overstay) = usageOn(rule, usage.segments, asOf)
    val windowDays = rule.windowDays
    val rawRemaining = windowDays?.let { it - used }
    val gateStatuses = gateStatusesFor(rule, docs, world, asOf, trips, statuses, candidateMaps)
    val cap = gateStatuses.mapNotNull(::gateCapDate).minOrNull()
    // The earliest-capping document owns reset semantics; ties go to the first status.
    val gate = gateStatuses.minByOrNull { gateCapDate(it) ?: LocalDate.MAX }
    val (resetMode, entryTotal, entryUsed, entryRemaining) = resetFactsFor(rule, gate)
    val recovery = rollingRecovery(rule, usage.segments, asOf, cap)
    val capped = when {
        rawRemaining == null || cap == null -> rawRemaining
        else -> minOf(rawRemaining, ChronoUnit.DAYS.between(asOf, cap).toInt())
    }
    val remaining = capped?.coerceAtLeast(0)
    // An illegal stay comes from the window (used > windowDays) or the document cap
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
    // An active stop in this zone with no document makes the stay-window misleading — flag the
    // zone so every surface (ring, card, pill) shows "no valid doc" instead of a remaining count.
    val zoneCountries = rule.countries.toSet()
    val noValidDoc = trips
        .filter { it.id in usage.tripIds }
        .flatMap { it.stops }
        .any {
            it.countryIso2 in zoneCountries &&
                it.documentId == null &&
                !it.arrival.isAfter(asOf) &&
                (it.departure == null || !it.departure.isBefore(asOf))
        }
    // An open future stop has no departure, so its figure is evaluated at its arrival instead.
    val futureStop = trips.asSequence()
        .flatMap { it.stops.asSequence() }
        .filter { it.countryIso2 in zoneCountries && it.arrival.isAfter(asOf) }
        .minByOrNull { it.arrival }
    val projection = futureStop?.let { stop ->
        val segments = usage.segments + ZoneSegment(stop.arrival, stop.departure ?: stop.arrival)
        rule.windowDays?.let { total ->
            remainingOn(rule, segments, stop.arrival)?.let { AllowanceProjection(stop.arrival, it, total) }
        }
    }
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
        noValidDoc = noValidDoc,
        resetMode = resetMode,
        recovery = recovery,
        projection = projection,
        entryTotal = entryTotal,
        entryUsed = entryUsed,
        entryRemaining = entryRemaining,
    )
}

/** Reset mode and entry counts from the rule's window and gate: finite entries rule out a border reset. */
internal fun resetFactsFor(rule: StayRule, gate: DocStatus?): ResetFacts {
    val entries = gate?.entries
    val finite = entries?.total
    val mode = when {
        rule.windowType == "lifetime" -> ResetMode.NEVER
        // A rolling pool recovers with time; F1 prints its recovery date, not a reset line.
        rule.windowType != "per-entry" -> ResetMode.UNKNOWN
        finite != null -> ResetMode.AT_NEW_DOCUMENT
        else -> ResetMode.AT_BORDER
    }
    return ResetFacts(
        resetMode = mode,
        entryTotal = finite,
        entryUsed = entries?.used,
        entryRemaining = entries?.remaining,
    )
}

internal data class ResetFacts(
    val resetMode: ResetMode,
    val entryTotal: Int?,
    val entryUsed: Int?,
    val entryRemaining: Int?,
)

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
internal fun expiryCapFor(
    rule: StayRule,
    docs: List<Document>,
    world: WorldData,
    asOf: LocalDate,
    trips: List<Trip>,
    statuses: Map<String, DocStatus>,
    candidateMaps: Map<String, Map<String, Access>>? = null,
): LocalDate? =
    gateStatusesFor(rule, docs, world, asOf, trips, statuses, candidateMaps)
        .mapNotNull(::gateCapDate)
        .minOrNull()

/** The statuses gating a stay in [rule]'s zone; one source so the cap and reset mode cannot disagree. */
internal fun gateStatusesFor(
    rule: StayRule,
    docs: List<Document>,
    world: WorldData,
    asOf: LocalDate,
    trips: List<Trip>,
    statuses: Map<String, DocStatus>,
    candidateMaps: Map<String, Map<String, Access>>? = null,
): List<DocStatus> {
    val docById = docs.associateBy { it.id }
    val stopsAtAsOf = trips
        .flatMap { it.stops }
        .filter { !it.arrival.isAfter(asOf) && (it.departure == null || !it.departure.isBefore(asOf)) }
    return rule.countries
        .flatMap { country ->
            val declared = stopsAtAsOf
                .filter { it.countryIso2 == country }
                .mapNotNull { it.documentId }
                .filter { docById.containsKey(it) }
                .toSet()
            val gateIds = if (declared.isNotEmpty()) declared
            else listOfNotNull(
                // Shared per-document candidate maps (built once per publish) keep the probe
                // from rebuilding every doc's full candidate map per zone country.
                AccessModel.bestDocumentId(country, docs, world, asOf, trips = trips, statuses = statuses, candidateMaps = candidateMaps)
                    ?: AccessModel.gateDocumentId(country, docs, world, asOf, trips = trips, statuses = statuses, candidateMaps = candidateMaps),
            )
            gateIds.mapNotNull { statuses[it] }
        }
}

/**
 * The day the document behind [status] stops authorising a stay. A document whose entry is in
 * progress ([EntryState.IN_USE]) keeps the current stay alive until its own date expiry — an
 * entry count limits re-entries, not the stay you are already in; every other state falls back
 * to the unified [DocStatus.effectiveExpiry] (entry exhaustion, supersession, lapsed date).
 * The exemption covers exhaustion only: a superseded document stops authorising the stay at the
 * supersession anchor, even when the replacement carries no `validFrom` and the anchor rides on
 * [DocStatus.effectiveExpiry] alone.
 */
internal fun gateCapDate(status: DocStatus): LocalDate? {
    if (status.entries?.state != EntryState.IN_USE) return status.effectiveExpiry
    return listOfNotNull(
        status.expiryDate,
        status.replacementValidFrom,
        status.effectiveExpiry.takeIf { status.reason == ExpiryReason.SUPERSEDED },
    ).minOrNull() ?: status.effectiveExpiry
}

/** Union of the zone's segments (overlapping stays on the same days count once). */
internal fun mergedSegments(segments: List<ZoneSegment>): List<Pair<LocalDate, LocalDate>> {
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

internal fun rollingUsage(rule: StayRule, segments: List<ZoneSegment>, asOf: LocalDate): Pair<Int, Int> {
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

internal fun perEntryUsage(rule: StayRule, segments: List<ZoneSegment>, asOf: LocalDate): Pair<Int, Int> {
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

// Nothing slides, so only days already spent have left the pool.
internal fun lifetimeUsage(rule: StayRule, segments: List<ZoneSegment>, asOf: LocalDate): Pair<Int, Int> {
    var used = 0
    for ((s, e) in mergedSegments(segments)) {
        val hi = if (e.isBefore(asOf)) e else asOf
        if (!s.isAfter(hi)) used += ChronoUnit.DAYS.between(s, hi).toInt() + 1
    }
    val overstay = rule.windowDays?.let { (used - it).coerceAtLeast(0) } ?: 0
    return used to overstay
}

/** Metered as a lifetime pool when the type is unrecognised: per-entry is the only reading that can over-grant. */
internal fun usageOn(rule: StayRule, segments: List<ZoneSegment>, on: LocalDate): Pair<Int, Int> =
    when (rule.windowType) {
        "rolling" -> rollingUsage(rule, segments, on)
        "per-entry" -> perEntryUsage(rule, segments, on)
        else -> lifetimeUsage(rule, segments, on)
    }

/** Days left against [rule] as of [on], before any document caps it. Null when the rule sets no limit. */
internal fun remainingOn(rule: StayRule, segments: List<ZoneSegment>, on: LocalDate): Int? =
    rule.windowDays?.let { it - usageOn(rule, segments, on).first }

/** Days return as they leave the window; [cap] suppresses a slice the traveller can no longer spend. */
internal fun rollingRecovery(
    rule: StayRule,
    segments: List<ZoneSegment>,
    today: LocalDate,
    cap: LocalDate? = null,
): Recovery? {
    if (rule.windowType != "rolling") return null
    val period = rule.windowPeriodDays ?: return null
    val total = rule.windowDays ?: return null
    val daysBack = usageOn(rule, segments, today).first
    if (daysBack <= 0) return null
    // Only days still inside the window can come back — an older stay has already been returned.
    val windowStart = today.minusDays(period - 1L)
    val used = mergedSegments(segments).mapNotNull { (start, end) ->
        val lo = if (start.isBefore(windowStart)) windowStart else start
        val hi = if (end.isBefore(today)) end else today
        if (lo.isAfter(hi)) null else lo to hi
    }
    if (used.isEmpty()) return null
    val firstSlice = used.minOf { it.first }.plusDays(period.toLong())
    val sliceDays = used.sumOf { (start, end) ->
        if (start.plusDays(period.toLong()) == firstSlice) ChronoUnit.DAYS.between(start, end).toInt() + 1 else 0
    }
    val wholeAgain = used.maxOf { it.second }.plusDays(period.toLong())
    val rawFirst = mergedSegments(segments).filter { !it.first.isAfter(today) }.minOfOrNull { it.first }
    val startedOn = rawFirst?.plusDays(period.toLong()) ?: firstSlice
    val inProgress = !today.isBefore(startedOn) && !wholeAgain.isBefore(today)
    if (cap != null && firstSlice.isAfter(cap)) return null
    return Recovery(
        firstSliceBackOn = firstSlice,
        wholeAgainOn = if (cap != null && wholeAgain.isAfter(cap)) cap else wholeAgain,
        daysBack = daysBack.coerceAtMost(total),
        sliceDays = sliceDays,
        inProgress = inProgress,
        totalDays = total,
    )
}
