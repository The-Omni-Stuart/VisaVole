package com.cbkres.visavole.data

import com.cbkres.visavole.domain.DocKind
import com.cbkres.visavole.domain.Document
import com.cbkres.visavole.domain.ResidenceClass
import com.cbkres.visavole.domain.Trip
import com.cbkres.visavole.domain.TripStop
import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class StateCodecTest {

    private val passport = Document(
        "doc-passport",
        "Passport · Czech Republic",
        DocKind.Passport("CZ"),
        null,
        "2031-05-01",
        "2021-05-01",
    )

    private val holding = Document(
        "doc-holding",
        "Schengen residence",
        DocKind.Holding("schengen-residence", "multiple"),
        null,
        "2029-11-30",
        null,
        ResidenceClass.LONG_TERM,
        null,
    )

    private val custom = Document(
        "doc-custom",
        "Work permit",
        DocKind.Custom(setOf("DE", "FR"), "eu", "visa", null, "double"),
    )

    private val trip = Trip(
        "trip-1",
        listOf(
            TripStop("stop-1", "DE", LocalDate.parse("2026-10-01"), null, "doc-holding"),
            TripStop("stop-2", "FR", LocalDate.parse("2026-10-05"), LocalDate.parse("2026-10-12"), null),
        ),
        "Berlin then Paris",
    )

    private val fullState = PersistedState(
        "doc-passport",
        listOf(passport, holding, custom),
        listOf(trip),
    )

    @Test
    fun roundTripPreservesEverything() {
        assertEquals(fullState, StateCodec.decode(StateCodec.encode(fullState)))
    }

    @Test
    fun roundTripWithNullPrimaryAndEmptyCollections() {
        val state = PersistedState(null, emptyList(), emptyList())
        val decoded = StateCodec.decode(StateCodec.encode(state))
        assertNull(decoded.primaryDocId)
        assertTrue(decoded.docs.isEmpty())
        assertTrue(decoded.trips.isEmpty())
    }

    @Test
    fun encodeWritesNullFieldsExplicitly() {
        val root = JSONObject(StateCodec.encode(fullState))
        val doc = root.getJSONArray("docs").getJSONObject(1)
        assertTrue(doc.isNull("validFrom")) // null is written as JSON null, not omitted
        val stop = root.getJSONArray("trips").getJSONObject(0).getJSONArray("stops").getJSONObject(0)
        assertTrue(stop.isNull("departure"))
        assertTrue(root.has("schemaVersion"))
        assertEquals(StateCodec.SCHEMA_VERSION, root.getInt("schemaVersion"))
    }

    @Test
    fun literalNullStringDecodesAsNull() {
        val json = """
            {"schemaVersion":3,"primaryDocId":"doc-passport",
             "docs":[{"id":"doc-passport","label":"P","kind":"passport","iso2":"CZ",
                      "expiry":"null","validFrom":null,"residenceClass":null,"supersededBy":null}],
             "trips":[]}
        """.trimIndent()
        val state = StateCodec.decode(json)
        assertNull(state.docs.first().expiry)
        assertEquals("doc-passport", state.primaryDocId)
    }

    @Test
    fun missingStopIdsAreGenerated() {
        val json = """
            {"schemaVersion":3,"primaryDocId":null,"docs":[],
             "trips":[{"id":"trip-1","note":null,
                       "stops":[{"country":"DE","arrival":"2026-10-01","departure":null,"documentId":null}]}]}
        """.trimIndent()
        val state = StateCodec.decode(json)
        val stop = state.trips.single().stops.single()
        assertNotNull(stop.id)
        assertTrue(stop.id.isNotBlank())
        assertNull(stop.departure)
    }

    @Test
    fun malformedStopsAreSkippedButUsableOnesSurvive() {
        val json = """
            {"schemaVersion":3,"primaryDocId":null,"docs":[],
             "trips":[{"id":"trip-1","note":"keep me",
                       "stops":[
                         {"id":"bad","arrival":"not-a-date"},
                         {"id":"stop-ok","country":"FR","arrival":"2026-10-05","departure":"2026-10-12","documentId":null}
                       ]}]}
        """.trimIndent()
        val state = StateCodec.decode(json)
        val stops = state.trips.single().stops
        assertEquals(1, stops.size)
        assertEquals("FR", stops.single().countryIso2)
    }

    @Test
    fun tripWithNoUsableStopsIsDropped() {
        val json = """
            {"schemaVersion":3,"primaryDocId":null,"docs":[],
             "trips":[{"id":"trip-1","note":null,
                       "stops":[{"id":"bad","arrival":"nope"}]}]}
        """.trimIndent()
        assertTrue(StateCodec.decode(json).trips.isEmpty())
    }

    @Test
    fun invalidJsonThrows() {
        try {
            StateCodec.decode("{this is not json")
            throw AssertionError("expected JSONException")
        } catch (e: JSONException) {
            // expected
        }
    }
}
