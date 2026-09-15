package com.cbkres.visavole.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class DocumentMigrationTest {

    // ---- legacy "home" -> UUID migration ----

    @Test fun legacyHomeToUuidConvertsHomeDocAndRemapsTrips() {
        val docs = listOf(
            Document("home", "Passport · RU", DocKind.Passport("RU")),
            Document("v1", "Schengen Visa", DocKind.Holding("schengen-visa")),
        )
        val trips = listOf(
            Trip(
                id = "t1",
                stops = listOf(
                    TripStop("s1", "DE", LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-07"), documentId = "home"),
                    TripStop("s2", "FR", LocalDate.parse("2026-10-07"), null, documentId = "v1"),
                ),
            ),
        )
        val (docs2, trips2, newHomeId) = DocumentMigration.legacyHomeToUuid(docs, trips, newId = { "uuid-123" })
        assertEquals("uuid-123", newHomeId)
        // The home doc now carries the new UUID; the other doc is untouched.
        assertEquals("uuid-123", docs2[0].id)
        assertEquals("v1", docs2[1].id)
        // The stop that referenced "home" is remapped; the other stop is untouched.
        assertEquals("uuid-123", trips2[0].stops[0].documentId)
        assertEquals("v1", trips2[0].stops[1].documentId)
    }

    @Test fun legacyHomeToUuidLeavesNullDocumentStopsUntouched() {
        val docs = listOf(Document("home", "Passport · RU", DocKind.Passport("RU")))
        val trips = listOf(
            Trip("t1", listOf(
                TripStop("s1", "DE", LocalDate.parse("2026-10-01"), null), // no doc
                TripStop("s2", "FR", LocalDate.parse("2026-10-07"), null, documentId = "home"),
            )),
        )
        val (docs2, trips2, _) = DocumentMigration.legacyHomeToUuid(docs, trips, newId = { "uuid-123" })
        assertEquals("uuid-123", docs2[0].id)
        assertNull(trips2[0].stops[0].documentId)
        assertEquals("uuid-123", trips2[0].stops[1].documentId)
    }

    @Test fun legacyHomeToUuidIsNoOpWhenNoHomeDoc() {
        val docs = listOf(Document("a", "Passport · RU", DocKind.Passport("RU")))
        val trips = listOf(Trip("t1", listOf(TripStop("s1", "DE", LocalDate.parse("2026-10-01"), null, documentId = "a"))))
        val (docs2, trips2, newHomeId) = DocumentMigration.legacyHomeToUuid(docs, trips)
        assertNull(newHomeId)
        assertSame(docs, docs2)
        assertSame(trips, trips2)
    }

    // ---- effective primary: the star is a tie-breaker that auto-drops when expired/missing ----

    @Test fun effectivePrimaryIdReturnsStarredPassportWhenValid() {
        val docs = listOf(
            Document("p1", "Passport · RU", DocKind.Passport("RU")),
            Document("p2", "Passport · IE", DocKind.Passport("IE")),
        )
        assertEquals("p2", DocumentMigration.effectivePrimaryId(docs, primaryId = "p2"))
        // No star: the first passport is the default.
        assertEquals("p1", DocumentMigration.effectivePrimaryId(docs, primaryId = null))
    }

    @Test fun effectivePrimaryIdFallsBackToFirstPassportWhenStarredExpired() {
        val docs = listOf(
            Document("p1", "Passport · RU", DocKind.Passport("RU")),
            Document("p2", "Passport · IE", DocKind.Passport("IE"), expiry = "2020-01-01"),
        )
        // Starred p2 is expired -> fall back to the first valid passport (p1).
        assertEquals("p1", DocumentMigration.effectivePrimaryId(docs, primaryId = "p2", today = LocalDate.parse("2026-01-01")))
    }

    @Test fun effectivePrimaryIdFallsBackWhenStarredMissing() {
        val docs = listOf(Document("p1", "Passport · RU", DocKind.Passport("RU")))
        assertEquals("p1", DocumentMigration.effectivePrimaryId(docs, primaryId = "ghost"))
    }

    @Test fun effectivePrimaryIdMovesToNextSameNationalityWhenStarredExpires() {
        val docs = listOf(
            Document("p1", "Passport · RU", DocKind.Passport("RU"), expiry = "2020-01-01"),
            Document("p2", "Passport · RU", DocKind.Passport("RU")),
            Document("p3", "Passport · IE", DocKind.Passport("IE")),
        )
        // Starred p1 (RU) lapsed -> the star moves to the next valid RU passport (p2),
        // not to the unrelated IE passport (p3).
        assertEquals("p2", DocumentMigration.effectivePrimaryId(docs, primaryId = "p1", today = LocalDate.parse("2026-01-01")))
    }

    @Test fun effectivePrimaryIdIgnoresNonPassportStar() {
        val docs = listOf(
            Document("v1", "Schengen Visa", DocKind.Holding("schengen-visa")),
            Document("p1", "Passport · RU", DocKind.Passport("RU")),
        )
        // A starred visa is not a passport, so it never counts as the primary passport.
        assertEquals("p1", DocumentMigration.effectivePrimaryId(docs, primaryId = "v1"))
    }

    @Test fun effectivePrimaryIdSkipsExpiredPassportsForFallback() {
        val docs = listOf(
            Document("p1", "Passport · RU", DocKind.Passport("RU"), expiry = "2020-01-01"),
            Document("p2", "Passport · IE", DocKind.Passport("IE")),
        )
        // No star; the first passport (p1) is expired -> fall back to the first VALID one (p2).
        assertEquals("p2", DocumentMigration.effectivePrimaryId(docs, primaryId = null, today = LocalDate.parse("2026-01-01")))
    }

    @Test fun effectivePrimaryIdIsNullOrWhenNoValidPassport() {
        val docs = listOf(Document("p1", "Passport · RU", DocKind.Passport("RU"), expiry = "2020-01-01"))
        assertNull(DocumentMigration.effectivePrimaryId(docs, primaryId = null, today = LocalDate.parse("2026-01-01")))
    }
}
