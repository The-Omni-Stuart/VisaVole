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
        "2031-05-01",
        "2021-05-01",
    )

    private val holding = Document(
        "doc-holding",
        "Schengen residence",
        DocKind.Holding("schengen-residence", "multiple"),
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
    fun duplicateDocIdsDedupeFirstWins() {
        // A hand-edited backup with two docs sharing an id would crash the keyed lists and make
        // an edit delete both twins — decode keeps the first occurrence (P1-7).
        val text = StateCodec.encode(
            PersistedState(
                "doc-passport",
                listOf(passport, passport.copy(label = "Twin passport"), custom),
                emptyList(),
            ),
        )
        val decoded = StateCodec.decode(text)
        assertEquals(2, decoded.docs.size)
        assertEquals("Passport · Czech Republic", decoded.docs.first { it.id == "doc-passport" }.label)
    }

    @Test
    fun duplicateTripIdsDedupeFirstWins() {
        val text = StateCodec.encode(
            PersistedState(
                null,
                emptyList(),
                listOf(trip, trip.copy(note = "Twin trip")),
            ),
        )
        val decoded = StateCodec.decode(text)
        assertEquals(1, decoded.trips.size)
        assertEquals("Berlin then Paris", decoded.trips.first().note)
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
    fun missingTripIdsAreGenerated() {
        val json = """
            {"schemaVersion":3,"primaryDocId":null,"docs":[],
             "trips":[{"id":"","note":null,
                       "stops":[{"id":"s1","country":"DE","arrival":"2026-10-01","departure":null,"documentId":null}]}]}
        """.trimIndent()
        val state = StateCodec.decode(json)
        val trip = state.trips.single()
        assertNotNull(trip.id)
        assertTrue(trip.id.isNotBlank())
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

    @Test
    fun corruptDocEntriesAreSkippedButValidDocsSurvive() {
        // A non-object entry AND an object missing its "kind" field must both be skipped
        // without taking the valid passport down with them.
        val json = """
            {"schemaVersion":3,"primaryDocId":"doc-passport",
             "docs":[42,
                     {"id":"doc-passport","label":"P","kind":"passport","iso2":"CZ","expiry":"2031-05-01"},
                     {"id":"doc-broken","label":"B"}],
             "trips":[]}
        """.trimIndent()
        val state = StateCodec.decode(json)
        assertEquals(1, state.docs.size)
        assertEquals("doc-passport", state.docs.single().id)
        assertEquals("doc-passport", state.primaryDocId)
    }

    @Test
    fun nullIdDocIsSkippedNotMintedAsLiteralNull() {
        // org.json's getString on a JSON null returns the string "null": without the guard, a
        // corrupt doc would decode to a document whose id is literally "null" (P2-3).
        val json = """
            {"schemaVersion":3,"primaryDocId":null,
             "docs":[
               {"id":null,"label":"Ghost","kind":"passport","iso2":"CZ"},
               {"id":"doc-passport","label":"P","kind":"passport","iso2":"CZ"}
             ],"trips":[]}
        """.trimIndent()
        val state = StateCodec.decode(json)
        assertEquals(1, state.docs.size)
        assertEquals("doc-passport", state.docs.single().id)
    }

    @Test
    fun nullIso2PassportIsSkipped() {
        val json = """
            {"schemaVersion":3,"primaryDocId":null,
             "docs":[{"id":"doc-1","label":"P","kind":"passport","iso2":null}],
             "trips":[]}
        """.trimIndent()
        assertTrue(StateCodec.decode(json).docs.isEmpty())
    }

    @Test
    fun nullHoldingIdIsSkipped() {
        val json = """
            {"schemaVersion":3,"primaryDocId":null,
              "docs":[{"id":"doc-1","label":"H","kind":"holding","holdingId":null,"entryType":null}],
              "trips":[]}
        """.trimIndent()
        assertTrue(StateCodec.decode(json).docs.isEmpty())
    }

    @Test
    fun docWithUnparseableDateIsSkipped() {
        // A non-null date that doesn't parse would otherwise decode as unconstrained — an immortal
        // doc, or one valid immediately — because every downstream consumer wraps LocalDate.parse
        // in runCatching. Skip such docs instead of minting them with garbage dates (P3-27).
        val json = """
            {"schemaVersion":3,"primaryDocId":null,
             "docs":[
               {"id":"doc-bad-exp","label":"A","kind":"passport","iso2":"CZ","expiry":"not-a-date"},
               {"id":"doc-bad-from","label":"B","kind":"passport","iso2":"CZ","expiry":"2031-05-01","validFrom":"soon"},
               {"id":"doc-good","label":"C","kind":"passport","iso2":"CZ","expiry":"2031-05-01","validFrom":"2021-05-01"}
             ],"trips":[]}
        """.trimIndent()
        val state = StateCodec.decode(json)
        assertEquals(1, state.docs.size)
        assertEquals("doc-good", state.docs.single().id)
    }

    @Test
    fun docsAndTripsFieldsPresentButNotArraysYieldEmpty() {
        // P3-35: a hand-edited backup where `docs`/`trips` exist but aren't arrays must not crash —
        // optJSONArray returns null and decode falls back to an empty array.
        val json = """
            {"schemaVersion":3,"primaryDocId":null,"docs":42,"trips":"oops"}
        """.trimIndent()
        val state = StateCodec.decode(json)
        assertTrue(state.docs.isEmpty())
        assertTrue(state.trips.isEmpty())
    }

    @Test
    fun customDocWithNonArrayCountriesIsSkipped() {
        // P3-35: a custom doc whose `countries` is present but not an array throws in getJSONArray;
        // decode catches it and skips the doc (a sibling valid doc survives).
        val json = """
            {"schemaVersion":3,"primaryDocId":null,
             "docs":[
               {"id":"doc-bad","label":"A","kind":"custom","countries":"DE"},
               {"id":"doc-good","label":"B","kind":"passport","iso2":"CZ","expiry":"2031-05-01"}
             ],"trips":[]}
        """.trimIndent()
        val state = StateCodec.decode(json)
        assertEquals(1, state.docs.size)
        assertEquals("doc-good", state.docs.single().id)
    }

    @Test
    fun danglingSupersededByPointerIsClearedOnDecode() {
        // A backup that references a replacement not in the set would leave a chip that can never
        // resolve; the decode choke point drops the dangling pointer (P2-4).
        val json = """
            {"schemaVersion":3,"primaryDocId":null,
             "docs":[
               {"id":"doc-old","label":"Old","kind":"passport","iso2":"CZ","supersededBy":"doc-missing"},
               {"id":"doc-keep","label":"Keep","kind":"passport","iso2":"CZ","supersededBy":null}
             ],"trips":[]}
        """.trimIndent()
        val state = StateCodec.decode(json)
        assertNull(state.docs.first { it.id == "doc-old" }.supersededBy)
        assertEquals(2, state.docs.size)
    }

    @Test
    fun selfSupersededByPointerIsClearedOnDecode() {
        // A doc superseding itself would expire from today and vanish from the valid lists;
        // decode drops the pointer (P2-4).
        val json = """
            {"schemaVersion":3,"primaryDocId":null,
             "docs":[{"id":"doc-1","label":"Self","kind":"passport","iso2":"CZ","supersededBy":"doc-1"}],
             "trips":[]}
        """.trimIndent()
        val state = StateCodec.decode(json)
        assertEquals(1, state.docs.size)
        assertNull(state.docs.single().supersededBy)
    }

    @Test
    fun validSupersededByPointerSurvivesDecode() {
        val json = """
            {"schemaVersion":3,"primaryDocId":null,
             "docs":[
               {"id":"doc-old","label":"Old","kind":"passport","iso2":"CZ","supersededBy":"doc-new"},
               {"id":"doc-new","label":"New","kind":"passport","iso2":"CZ","supersededBy":null}
             ],"trips":[]}
        """.trimIndent()
        assertEquals("doc-new", StateCodec.decode(json).docs.first { it.id == "doc-old" }.supersededBy)
    }

    @Test
    fun stopWithNullCountryIsSkippedButSiblingsSurvive() {
        val json = """
            {"schemaVersion":3,"primaryDocId":null,"docs":[],
             "trips":[{"id":"trip-1","note":null,
                       "stops":[
                         {"id":"bad","country":null,"arrival":"2026-10-01"},
                         {"id":"stop-ok","country":"FR","arrival":"2026-10-05","departure":null,"documentId":null}
                       ]}]}
        """.trimIndent()
        val stops = StateCodec.decode(json).trips.single().stops
        assertEquals(1, stops.size)
        assertEquals("FR", stops.single().countryIso2)
    }

    @Test
    fun primaryDocIdSurvivesDecodeIncludingDangling() {
        // The primary pointer is preserved verbatim (even when dangling) — the ViewModel
        // re-derives a valid pointer on load; decode only enforces doc-set invariants (P2-40).
        val json = """
            {"schemaVersion":3,"primaryDocId":"doc-gone","docs":[],"trips":[]}
        """.trimIndent()
        assertEquals("doc-gone", StateCodec.decode(json).primaryDocId)
    }

    @Test
    fun futureSchemaVersionIsRejected() {
        // A file written by a NEWER app must not be misread: decode throws so the caller can
        // quarantine the file (a downgraded app must never rewrite a newer format).
        val json = """
            {"schemaVersion":4,"primaryDocId":null,"docs":[],"trips":[]}
        """.trimIndent()
        try {
            StateCodec.decode(json)
            throw AssertionError("expected IllegalArgumentException for a future schemaVersion")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun missingOrLegacySchemaVersionIsAccepted() {
        // Only versions NEWER than the app are rejected; missing/older versions get a
        // best-effort parse (the format is additive and this app has only ever written v3).
        val json = """
            {"primaryDocId":"doc-passport",
             "docs":[{"id":"doc-passport","label":"P","kind":"passport","iso2":"CZ"}],
             "trips":[]}
        """.trimIndent()
        val state = StateCodec.decode(json)
        assertEquals(1, state.docs.size)
        assertEquals("doc-passport", state.primaryDocId)
    }
}
