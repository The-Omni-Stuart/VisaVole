package com.cbkres.visavole.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class DocumentMigrationTest {

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

    // ---- deletion guard: a doc referenced by any stop of any trip cannot be removed ----

    @Test fun referencedDocumentIdsCollectsAllStopDocsAcrossTrips() {
        val t1 = Trip("t1", listOf(
            TripStop("s1", "DE", LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-07"), documentId = "p1"),
            TripStop("s2", "FR", LocalDate.parse("2026-10-07"), null, documentId = "p2"),
        ))
        val t2 = Trip("t2", listOf(
            TripStop("s3", "IT", LocalDate.parse("2025-01-01"), LocalDate.parse("2025-01-05"), documentId = "p1"), // duplicate + past trip
            TripStop("s4", "ES", LocalDate.parse("2025-01-05"), null), // no doc
        ))
        assertEquals(setOf("p1", "p2"), referencedDocumentIds(listOf(t1, t2)))
    }

    @Test fun referencedDocumentIdsIsEmptyWhenNoStopHasADoc() {
        val trips = listOf(Trip("t1", listOf(TripStop("s1", "DE", LocalDate.parse("2026-10-01"), null))))
        assertTrue(referencedDocumentIds(trips).isEmpty())
    }
}
