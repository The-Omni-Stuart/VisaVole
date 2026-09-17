package com.cbkres.visavole.domain

import java.time.LocalDate

enum class TripStatus {
    UPCOMING,
    ONGOING,
    PREVIOUS,
}

data class TripStop(
    val id: String,
    val countryIso2: String,
    val arrival: LocalDate,
    val departure: LocalDate?,
    val documentId: String? = null,
)

data class Trip(
    val id: String,
    val stops: List<TripStop>,
    val note: String? = null,
) {
    val firstArrival: LocalDate
        get() = stops.minOfOrNull { it.arrival } ?: LocalDate.MAX

    val isOpen: Boolean
        get() = stops.any { it.departure == null }

    val finalDeparture: LocalDate?
        get() = if (isOpen) null else stops.mapNotNull { it.departure }.maxOrNull()

    fun statusAt(today: LocalDate): TripStatus {
        if (stops.isEmpty()) return TripStatus.UPCOMING
        val start = stops.minOf { it.arrival }
        if (start.isAfter(today)) return TripStatus.UPCOMING
        val end = stops.mapNotNull { it.departure }.maxOrNull()
        return when {
            isOpen || end == null -> TripStatus.ONGOING
            !end.isBefore(today) -> TripStatus.ONGOING
            else -> TripStatus.PREVIOUS
        }
    }
}

data class TripSections(
    val upcoming: List<Trip>,
    val current: List<Trip>,
    val previous: List<Trip>,
)

/**
 * The set of document ids referenced by any stop of any trip (across all trip statuses).
 * A document in this set cannot be deleted until the referencing stop is re-pointed to
 * another document or the trip is removed. Ending a trip does not free its document —
 * the stop keeps its [TripStop.documentId].
 */
fun referencedDocumentIds(trips: List<Trip>): Set<String> =
    trips.flatMap { t -> t.stops.mapNotNull { s -> s.documentId } }.toSet()

enum class AllowanceKind {
    ROLLING,
    PER_ENTRY,
    DOCUMENT_VALIDITY,
    ENTRY_COUNT,
    UNLIMITED,
}

enum class AllowanceStatus {
    OK,
    WARNING,
    DANGER,
    EXHAUSTED,
    UNLIMITED,
    UNKNOWN,
}

data class AllowanceSnapshot(
    val key: String,
    val title: String,
    val subtitle: String? = null,
    val kind: AllowanceKind,
    val status: AllowanceStatus,
    val usedDays: Int? = null,
    val remainingDays: Int? = null,
    val totalDays: Int? = null,
    val windowPeriodDays: Int? = null,
    val overstayDays: Int = 0,
    val entryTotal: Int? = null,
    val entryUsed: Int? = null,
    val entryRemaining: Int? = null,
    val effectiveExpiry: LocalDate? = null,
    val currentTripId: String? = null,
    val relevantTripIds: List<String> = emptyList(),
)

enum class EntryState {
    AVAILABLE,
    IN_USE,
    EXHAUSTED,
    EXPIRED,
}

data class EntryStatus(
    val total: Int?,
    val used: Int,
    val remaining: Int?,
    val state: EntryState,
    val effectiveExpiry: LocalDate?,
    val lastUsedOn: LocalDate? = null,
    val sourceTripId: String? = null,
)

enum class WarningSeverity {
    INFO,
    WARNING,
    DANGER,
}

data class TripWarning(
    /** Stable machine-readable id (e.g. `trip.gap`); [title] is display text only. */
    val code: String,
    val severity: WarningSeverity,
    val title: String,
    val message: String,
)

data class TripCalculation(
    val sections: TripSections,
    val allowances: List<AllowanceSnapshot>,
    val entryStatus: Map<String, EntryStatus>,
)
