package com.cbkres.visavole.ui

import com.cbkres.visavole.data.WorldMapData
import com.cbkres.visavole.domain.Corridor
import com.cbkres.visavole.domain.Country
import com.cbkres.visavole.domain.DocKind
import com.cbkres.visavole.domain.Document
import com.cbkres.visavole.domain.StayRule
import com.cbkres.visavole.domain.Trip
import com.cbkres.visavole.domain.TripStop
import com.cbkres.visavole.domain.WorldData
import com.cbkres.visavole.ui.app.AppState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * The publish derivation and its supersession gate. [AccessViewModel] runs [deriveReady] off the
 * main thread and lets only the newest scheduled run write state, so these pin both halves of that
 * contract without an Android runtime.
 */
class PublishDerivationTest {

    private val today = LocalDate.of(2026, 11, 13)

    private val world = WorldData(
        mapOf("GB" to Country("GB", "United Kingdom"), "DE" to Country("DE", "Germany"), "FR" to Country("FR", "France")),
        mapOf(
            "GB" to listOf(
                Corridor("GB", "DE", "visa-free", 90),
                Corridor("GB", "FR", "visa-free", 90),
            ),
        ),
        emptyList(), emptyMap(), emptyMap(),
        listOf(
            StayRule(
                "Schengen", setOf("DE", "FR"), "rolling", 90, 180, true, setOf("*"), null,
                id = "schengen", zoneId = "schengen",
            ),
        ),
    )

    private val geometry = WorldMapData(360, 180, emptyMap())

    private val passport = Document("p", "Passport · GB", DocKind.Passport("GB"), "2030-01-01")

    private fun trip(id: String) = Trip(
        id,
        listOf(TripStop("$id-s", "DE", today.minusDays(5), today.plusDays(5))),
    )

    private fun inputs(trips: List<Trip>) = PublishedInputs(
        world = world,
        geometry = geometry,
        shapes = emptyList(),
        focus = emptyMap(),
        docs = listOf(passport),
        trips = trips,
        starredDocId = passport.id,
        today = today,
    )

    @Test
    fun deriveReadyCarriesEveryPublishedField() {
        val ongoing = trip("t1")
        val ready = deriveReady(inputs(listOf(ongoing)))
        assertEquals(setOf("GB"), ready.homeCountries)
        assertEquals(listOf(passport), ready.docs)
        assertEquals(setOf(passport.id), ready.statuses.keys)
        assertFalse(ready.statuses.getValue(passport.id).expired)
        assertEquals(listOf(ongoing), ready.trips)
        assertEquals(listOf(ongoing), ready.tripSections.current)
        assertNotNull(ready.access["DE"])
        assertTrue(ready.allowances.any { it.key == "stay:schengen" })
        assertTrue(ready.allowances.any { it.key == "doc:p" })
        assertEquals(passport.id, ready.primaryDocId)
        assertEquals(passport.id, ready.starredDocId)
        assertEquals(today, ready.today)
        assertEquals(geometry, ready.geometry)
        assertTrue(ready.shapes.isEmpty())
        assertTrue(ready.focus.isEmpty())
    }

    @Test
    fun snapshotPublishedInputsFreezesTheLiveLists() {
        val liveDocs = mutableListOf(passport)
        val liveTrips = mutableListOf(trip("t1"))
        val frozen = snapshotPublishedInputs(
            world, geometry, emptyList(), emptyMap(), liveDocs, liveTrips, passport.id, today,
        )
        liveDocs.add(Document("p2", "Passport · FR", DocKind.Passport("FR")))
        liveTrips.add(trip("t2"))
        // A mutation that lands while an earlier derivation is still running must not leak into it.
        assertEquals(listOf(passport), frozen.docs)
        assertEquals(listOf(trip("t1")), frozen.trips)
        assertEquals(1, deriveReady(frozen).trips.size)
    }

    @Test
    fun onlyTheNewestGenerationIsCurrent() {
        val gate = PublishGeneration()
        val first = gate.next()
        val second = gate.next()
        assertFalse(gate.isCurrent(first))
        assertTrue(gate.isCurrent(second))
        val third = gate.next()
        assertFalse(gate.isCurrent(second))
        assertTrue(gate.isCurrent(third))
    }

    /**
     * Mirrors the publish loop's scheduling: two mutations in one frame, the older derivation lands
     * last. The gate is what keeps the late arrival from overwriting the newest state.
     */
    @Test
    fun aLateSupersededDerivationNeverOverwritesTheNewest() {
        val gate = PublishGeneration()
        val published = AtomicReference<AppState.Ready>()
        val writes = AtomicInteger()
        val older = gate.next()
        val newer = gate.next()

        fun publish(generation: Int, trips: List<Trip>, delayMillis: Long) {
            val ready = deriveReady(inputs(trips))
            Thread.sleep(delayMillis)
            if (gate.isCurrent(generation)) {
                published.set(ready)
                writes.incrementAndGet()
            }
        }

        val slow = Thread { publish(older, listOf(trip("t1")), 250) }
        slow.start()
        publish(newer, listOf(trip("t1"), trip("t2")), 0)
        slow.join()

        assertEquals(1, writes.get())
        assertEquals(listOf("t1", "t2"), published.get()?.trips?.map { it.id })
    }
}
