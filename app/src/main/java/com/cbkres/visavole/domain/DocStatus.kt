package com.cbkres.visavole.domain

import com.cbkres.visavole.data.WorldData
import java.time.LocalDate

/**
 * Why a document is (or will be) no longer usable. A document can lapse for several independent
 * reasons; the one with the earliest effective expiry date wins ([DocStatus.of]).
 */
enum class ExpiryReason {
    /** Its own expiry date has passed (or will pass). */
    DATE,

    /** Its fixed entry count was consumed by logged trips. */
    ENTRIES,

    /** A newer document of the same country/category superseded it ([Document.supersededBy]). */
    SUPERSEDED,

    /** Its allowed time frame was used up. Design slot: needs the not-yet-built `maxDays` field. */
    TIMEFRAME,
}

/**
 * The unified validity of a document as of [today] — the single source of truth for "is this
 * document still usable, and if not, why". Every consumer (document list, map, trip projections,
 * guard rules) derives expiry from this instead of its own date/entry heuristics.
 *
 * - [effectiveExpiry] is the earliest of the document's own expiry date, its entry-driven
 *   exhaustion date, and the superseding document's `validFrom` (null when unconstrained);
 * - [reason] is the [ExpiryReason] attached to that earliest date;
 * - [expired] is true once [effectiveExpiry] is past (an expiry of *today* is still valid).
 */
data class DocStatus(
    val expired: Boolean,
    val reason: ExpiryReason?,
    val effectiveExpiry: LocalDate?,
    val entries: EntryStatus?,
) {
    companion object {
        fun of(
            doc: Document,
            docs: List<Document>,
            trips: List<Trip>,
            world: WorldData,
            today: LocalDate,
        ): DocStatus {
            val original = parse(doc.expiry)
            val candidates = mutableListOf<Pair<LocalDate, ExpiryReason>>()
            if (original != null) candidates += original to ExpiryReason.DATE
            val entry = TripModel.entryStatusFor(doc, trips, world, today)
            if (entry != null && entry.effectiveExpiry != null &&
                (original == null || entry.effectiveExpiry.isBefore(original))
            ) {
                candidates += entry.effectiveExpiry to ExpiryReason.ENTRIES
            }
            doc.supersededBy?.let { id ->
                docs.firstOrNull { it.id == id }
                    ?.validFrom
                    ?.let { parse(it) }
                    ?.let { candidates += it to ExpiryReason.SUPERSEDED }
            }
            val best = candidates.minWithOrNull(compareBy({ it.first }, { it.second.ordinal }))
            val effective = best?.first
            return DocStatus(
                expired = effective != null && effective.isBefore(today),
                reason = best?.second,
                effectiveExpiry = effective,
                entries = entry,
            )
        }

        /** The status of every document in [docs] (ids to status), computed once per render. */
        fun all(
            docs: List<Document>,
            trips: List<Trip>,
            world: WorldData,
            today: LocalDate,
        ): Map<String, DocStatus> =
            docs.associate { it.id to of(it, docs, trips, world, today) }

        private fun parse(s: String?): LocalDate? = s?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    }
}
