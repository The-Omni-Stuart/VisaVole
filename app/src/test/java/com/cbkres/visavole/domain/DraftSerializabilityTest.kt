package com.cbkres.visavole.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable
import java.time.LocalDate

/** rememberSaveable keeps a draft only if it is Serializable; a non-serialisable one loses a half-entered trip on a config change. */
class DraftSerializabilityTest {

    private fun roundTrip(value: Serializable): Any =
        ByteArrayOutputStream().use { bytes ->
            ObjectOutputStream(bytes).use { it.writeObject(value) }
            ObjectInputStream(ByteArrayInputStream(bytes.toByteArray())).use { it.readObject() }
        }

    @Test
    fun anEditedTripDraftSurvivesTheBundle() {
        val trip = Trip(
            id = "draft-1",
            stops = listOf(
                TripStop("s1", "FR", LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 5)),
                TripStop("s2", "MC", LocalDate.of(2026, 12, 6), null, "passport-1"),
            ),
            note = "half-entered",
        )
        assertEquals(trip, roundTrip(trip))
    }

    @Test
    fun aDraftStopWithEveryFieldSetSurvivesTheBundle() {
        val stop = TripStop("s1", "DE", LocalDate.of(2026, 11, 19), null, "doc-9")
        assertEquals(stop, roundTrip(stop))
    }

    @Test
    fun everyDocumentKindSurvivesTheBundle() {
        val docs = listOf(
            Document("p", "Passport · DE", DocKind.Passport("DE")),
            Document("h", "US visa", DocKind.Holding("us-visa", "multiple")),
            Document("c", "Custom", DocKind.Custom(setOf("FR", "MC"), "schengen", "residence", "schengen-residence", "single"),
                     residenceClass = ResidenceClass.PERMANENT),
        )
        docs.forEach { assertEquals(it, roundTrip(it)) }
    }
}
