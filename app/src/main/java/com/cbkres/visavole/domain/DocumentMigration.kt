package com.cbkres.visavole.domain

import java.time.LocalDate
import java.util.UUID

/**
 * Pure, framework-free transformations for persisted access state. Kept outside the
 * ViewModel so they are unit-testable without the Android runtime (no Application,
 * coroutines, or asset loading involved).
 */
object DocumentMigration {

    /**
     * Convert legacy state (schema v2, where the home passport had the magic id "home")
     * to the current shape (every document has a UUID). The home passport is given a fresh
     * UUID and every trip stop referencing the old "home" id is remapped to it.
     *
     * No-op (returns the same list references) when no "home" document exists.
     * [newId] is injectable so tests can assert the exact id used.
     */
    fun legacyHomeToUuid(
        docs: List<Document>,
        trips: List<Trip>,
        newId: () -> String = { UUID.randomUUID().toString() },
    ): Triple<List<Document>, List<Trip>, String?> {
        val home = docs.firstOrNull { it.id == "home" } ?: return Triple(docs, trips, null)
        val id = newId()
        val migratedDocs = docs.map { d -> if (d.id == "home") d.copy(id = id) else d }
        val migratedTrips = trips.map { t ->
            t.copy(stops = t.stops.map { s -> if (s.documentId == "home") s.copy(documentId = id) else s })
        }
        return Triple(migratedDocs, migratedTrips, id)
    }

    /**
     * The document that currently "counts" as the user's primary passport for tie-breaking:
     * the starred one, if it still exists, is a passport, and is not expired. When the starred
     * passport has expired or been removed the star "moves" to the first still-valid passport of
     * the SAME nationality (a tie can only ever occur between passports of one country, so that is
     * the natural successor — e.g. the user held three and the starred one lapsed). If no passport
     * of that nationality is valid any more, fall back to the first valid passport overall; null
     * when there is no valid passport at all.
     */
    fun effectivePrimaryId(
        docs: List<Document>,
        primaryId: String?,
        today: LocalDate = LocalDate.now(),
    ): String? {
        if (primaryId != null) {
            val d = docs.firstOrNull { it.id == primaryId }
            if (d != null && d.kind is DocKind.Passport && !isExpiredDoc(d, today)) return d.id
            val iso2 = (d?.kind as? DocKind.Passport)?.iso2
            if (iso2 != null) {
                docs.firstOrNull {
                    (it.kind as? DocKind.Passport)?.iso2 == iso2 && !isExpiredDoc(it, today)
                }?.let { return it.id }
            }
        }
        return docs.firstOrNull { it.kind is DocKind.Passport && !isExpiredDoc(it, today) }?.id
    }

    /**
     * True when a passport is past its expiry (non-passports are never "expired"). For passports
     * the unified [DocStatus] reduces to exactly this date check (no entry counts, never
     * superseded), so this helper needs no trip/world context.
     */
    fun isExpiredDoc(doc: Document, today: LocalDate = LocalDate.now()): Boolean {
        if (doc.kind !is DocKind.Passport) return false
        val iso = doc.expiry ?: return false
        return runCatching { LocalDate.parse(iso) }.getOrNull()?.let { it.isBefore(today) } ?: false
    }
}
