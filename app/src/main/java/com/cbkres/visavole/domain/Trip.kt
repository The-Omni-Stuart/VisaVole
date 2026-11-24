package com.cbkres.visavole.domain

import java.io.Serializable
import java.time.LocalDate

enum class TripStatus {
    UPCOMING,
    ONGOING,
    PREVIOUS,
}

/** The one user-facing word per status: section headers and card pills agree. */
internal val TripStatus.label: String
    get() = when (this) {
        TripStatus.UPCOMING -> "Upcoming"
        TripStatus.ONGOING -> "Ongoing"
        TripStatus.PREVIOUS -> "Completed"
    }

data class TripStop(
    val id: String,
    val countryIso2: String,
    val arrival: LocalDate,
    val departure: LocalDate?,
    val documentId: String? = null,
) : Serializable

data class Trip(
    val id: String,
    val stops: List<TripStop>,
    val note: String? = null,
) : Serializable {
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
internal fun referencedDocumentIds(trips: List<Trip>): Set<String> =
    trips.flatMap { t -> t.stops.mapNotNull { s -> s.documentId } }.toSet()

enum class AllowanceKind {
    ROLLING,
    PER_ENTRY,
    LIFETIME,
    DOCUMENT_VALIDITY,
    ENTRY_COUNT,
}

/** True when the allowance is a stay window (days in a zone), as opposed to a document's own life. */
internal val AllowanceKind.isWindow: Boolean
    get() = this == AllowanceKind.ROLLING || this == AllowanceKind.PER_ENTRY || this == AllowanceKind.LIFETIME

/** True when the DANGER status means a document lapsed, not a stay window running out of days. */
internal val AllowanceSnapshot.isExpiredDoc: Boolean
    get() = status == AllowanceStatus.DANGER && !kind.isWindow

enum class AllowanceStatus {
    OK,
    WARNING,
    DANGER,
    EXHAUSTED,
    UNKNOWN,
}

/**
 * When a rolling allowance gives used days back, or null where none come back over time — a
 * per-entry allowance resets at a border, so a date printed for it would be an invention.
 */
data class Recovery(
    /** First date a used day has left the window. */
    val firstSliceBackOn: LocalDate,
    /** From this date the allowance is whole again. Display parked for the alpha-5 screen (L13). */
    val wholeAgainOn: LocalDate,
    /** Days consumed today. Never more than [totalDays]. */
    val daysBack: Int,
    /** Days that return on [firstSliceBackOn] — the slice currently in progress. */
    val sliceDays: Int,
    /** True once the first used day has started returning and the pool is not whole yet. */
    val inProgress: Boolean,
    val totalDays: Int,
)

/** What a planned stop's arrival date would look like, evaluated with the same allowance arithmetic. */
data class AllowanceProjection(
    val on: LocalDate,
    val remainingDays: Int,
    val totalDays: Int,
)

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
    /** The trip still running on this document, even when it has lapsed mid-stay. Drives the
     *  "expired doc that is still in use" pulse on the ring (distinct from [currentTripId], which
     *  is only set while the entry is in a healthy IN_USE state). */
    val inUseTripId: String? = null,
    /** Short display form for compact surfaces: the ISO-2 code when the zone is a single country
     *  (so a pill reads "US", not "United States"), or null for a named multi-country zone, where
     *  the caller falls back to [title] (e.g. "Schengen Area"). */
    val shortTitle: String? = null,
    /** True when an active stop in this zone has no document assigned — the stay-window "days left"
     *  would mislead, so every surface flashes "no valid doc" instead of a remaining count. */
    val noValidDoc: Boolean = false,
    /** How the traveller earns this allowance back, from the gate document rather than [kind]. */
    val resetMode: ResetMode = ResetMode.UNKNOWN,
    /** When used days come back, or null where they do not — the rotating recovery line. */
    val recovery: Recovery? = null,
    /** The next planned stop in this zone, at its own arrival date. */
    val projection: AllowanceProjection? = null,
)

/** Whether an allowance comes back, and how — derived from the rule's window and its gate document. */
enum class ResetMode {
    /** Crossing a border restores the full allowance (visa-free passport, multiple-entry visa). */
    AT_BORDER,
    /** No further entry on this document; the allowance needs a new one (finite-entry visa used up). */
    AT_NEW_DOCUMENT,
    /** One grant that never comes back (a lifetime pool). */
    NEVER,
    /** This build cannot tell — stay silent rather than promise a reset that may not happen. */
    UNKNOWN,
}

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
    /** The trip whose entry is currently in progress (null when the stay is over). Unlike
     *  [sourceTripId], this is set even when the doc has lapsed mid-stay — which is exactly the
     *  "expired but still in use" case the overstay-style pulse wants to flag. */
    val activeTripId: String? = null,
)

enum class WarningSeverity {
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
