package com.cbkres.visavole.domain

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
 *   exhaustion date, and the superseding document's `validFrom` (null when unconstrained); a
 *   superseding document *without* a `validFrom` supersedes from [today];
 * - [reason] is the [ExpiryReason] attached to that earliest date;
 * - [expired] is true once [effectiveExpiry] is past (an expiry of *today* is still valid);
 * - [notYetValid] is true when the document's `validFrom` lies after [today] — such a document
 *   unlocks nothing at [today], a passport excepted: it evidences a nationality from any date
 *   (see [com.cbkres.visavole.domain.AccessModel.compute]).
 */
data class DocStatus(
    val expired: Boolean,
    val reason: ExpiryReason?,
    val effectiveExpiry: LocalDate?,
    val entries: EntryStatus?,
    val notYetValid: Boolean,
    /** The parsed own `expiry` string (null when unset or unparseable), carried so consumers like
     *  the gate cap don't re-parse it. */
    val expiryDate: LocalDate?,
    /** The parsed `validFrom` of the document that supersedes this one (null when nothing
     *  supersedes it or the replacement has no `validFrom`). */
    val replacementValidFrom: LocalDate?,
) {
    companion object {
        fun of(
            doc: Document,
            docs: List<Document>,
            trips: List<Trip>,
            world: WorldData,
            today: LocalDate,
        ): DocStatus {
            val original = doc.expiry.toDateOrNull()
            val validFrom = doc.validFrom.toDateOrNull()
            val candidates = mutableListOf<Pair<LocalDate, ExpiryReason>>()
            if (original != null) candidates += original to ExpiryReason.DATE
            val entry = TripModel.entryStatusFor(doc, trips, world, today, validFrom, original)
            if (entry != null && entry.effectiveExpiry != null &&
                (original == null || entry.effectiveExpiry.isBefore(original))
            ) {
                candidates += entry.effectiveExpiry to ExpiryReason.ENTRIES
            }
            var replacementValidFrom: LocalDate? = null
            doc.supersededBy?.let { id ->
                docs.firstOrNull { it.id == id }?.let { replacement ->
                    // A replacement without a `validFrom` supersedes IMMEDIATELY (user decision
                    // 2026-09-27): the old doc is archived on the spot (red "Replaced by …")
                    // instead of sitting in a shifting "replaced from today" amber state. Anchor
                    // the missing date to yesterday so the standard "before today" test trips.
                    replacementValidFrom = replacement.validFrom.toDateOrNull()
                    candidates += (replacementValidFrom ?: today.minusDays(1)) to ExpiryReason.SUPERSEDED
                }
            }
            val best = candidates.minWithOrNull(compareBy({ it.first }, { it.second.ordinal }))
            val effective = best?.first
            return DocStatus(
                expired = effective != null && effective.isBefore(today),
                reason = best?.second,
                effectiveExpiry = effective,
                entries = entry,
                notYetValid = validFrom != null && validFrom.isAfter(today),
                expiryDate = original,
                replacementValidFrom = replacementValidFrom,
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

    }
}
