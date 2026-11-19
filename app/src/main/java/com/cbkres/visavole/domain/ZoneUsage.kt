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

/**
 * The tighter of two rules pooling into one zone: the smallest windowDays wins (null = no stated
 * limit, weakest), rule id breaks ties so the pick never depends on stop order either.
 */
private fun tighterRule(current: StayRule?, candidate: StayRule?): StayRule =
    listOfNotNull(current, candidate).minWith(compareBy({ it.windowDays ?: Int.MAX_VALUE }, { it.id }))

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
    val kind = if (rule.windowType == "rolling") AllowanceKind.ROLLING else AllowanceKind.PER_ENTRY
    val (used, overstay) = usageOn(rule, usage.segments, asOf)
    val windowDays = rule.windowDays
    val rawRemaining = windowDays?.let { it - used }
    val cap = expiryCapFor(rule, docs, world, asOf, trips, statuses, candidateMaps)
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
internal fun expiryCapFor(
    rule: StayRule,
    docs: List<Document>,
    world: WorldData,
    asOf: LocalDate,
    trips: List<Trip>,
    statuses: Map<String, DocStatus>,
    candidateMaps: Map<String, Map<String, Access>>? = null,
): LocalDate? {
    val docById = docs.associateBy { it.id }
    val stopsAtAsOf = trips
        .flatMap { it.stops }
        .filter { !it.arrival.isAfter(asOf) && (it.departure == null || !it.departure.isBefore(asOf)) }
    return rule.countries
        .mapNotNull { country ->
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
            gateIds
                .mapNotNull { id ->
                    val status = statuses[id] ?: return@mapNotNull null
                    gateCapDate(status)
                }
                .minOrNull()
        }
        .minOrNull()
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

/** The one place the window type picks the arithmetic; [on] makes "as of today" a caller's choice. */
internal fun usageOn(rule: StayRule, segments: List<ZoneSegment>, on: LocalDate): Pair<Int, Int> =
    if (rule.windowType == "rolling") rollingUsage(rule, segments, on) else perEntryUsage(rule, segments, on)

/** Days left against [rule] as of [on], before any document caps it. Null when the rule sets no limit. */
internal fun remainingOn(rule: StayRule, segments: List<ZoneSegment>, on: LocalDate): Int? =
    rule.windowDays?.let { it - usageOn(rule, segments, on).first }

/**
 * When a rolling window's used days come back: a day leaves `[on - period + 1, on]` once `on`
 * reaches `day + period`, so the first slice returns on `firstUsed + period` and the pool is whole
 * on `lastUsed + period`. Closed form — the publish path cannot afford a day walk; only the test walks.
 * Null when it does not refill over time: per-entry, no period (L2), or nothing used.
 */
internal fun rollingRecovery(rule: StayRule, segments: List<ZoneSegment>, today: LocalDate): Recovery? {
    if (rule.windowType != "rolling") return null
    val period = rule.windowPeriodDays ?: return null
    val total = rule.windowDays ?: return null
    val daysBack = usageOn(rule, segments, today).first
    if (daysBack <= 0) return null
    // Only days already spent can come back: a stay still ahead of today has consumed nothing.
    val used = mergedSegments(segments).mapNotNull { (start, end) ->
        val hi = if (end.isBefore(today)) end else today
        if (start.isAfter(hi)) null else start to hi
    }
    if (used.isEmpty()) return null
    return Recovery(
        firstSliceBackOn = used.minOf { it.first }.plusDays(period.toLong()),
        wholeAgainOn = used.maxOf { it.second }.plusDays(period.toLong()),
        daysBack = daysBack.coerceAtMost(total),
        totalDays = total,
    )
}
