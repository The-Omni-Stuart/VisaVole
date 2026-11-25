package com.cbkres.visavole.ui.screens.map

import com.cbkres.visavole.domain.Access
import com.cbkres.visavole.domain.AccessLevel
import com.cbkres.visavole.domain.AccessReason
import com.cbkres.visavole.domain.ResidenceClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * [mapPaintKey] must track map colours by content: every publish hands over new map/set
 * instances, and keying the fills on their identity would rebuild all ~204 paths' fills and
 * repaint the world even when nothing changed (e.g. starring a passport).
 */
class MapPaintKeyTest {

    private fun access(iso: String, days: Int?) = iso to Access(AccessLevel.VISA_FREE, days, AccessReason.PassportBaseline)

    @Test fun equalMapsAcrossImplementationsShareAKey() {
        val entries = listOf(access("DE", 90), access("FR", 90), access("US", null))
        val a = LinkedHashMap(entries.toMap())
        val b = buildMap { entries.reversed().forEach { (k, v) -> put(k, v) } }
        val emptyMix = mapPaintKey(a, setOf("GB"), setOf("DE"), emptySet())
        assertEquals("insertion order and Map implementation must not change the key", emptyMix, mapPaintKey(b, setOf("GB"), setOf("DE"), emptySet()))
        // Equal-content Access instances in distinct maps: Map contract does the rest.
        assertEquals(mapPaintKey(LinkedHashMap(entries.toMap()), setOf("GB"), setOf("DE"), emptySet()), mapPaintKey(a, buildSet { add("GB") }, linkedSetOf("DE"), emptySet()))
    }

    @Test fun emptyMapsShareTheEmptyKey() {
        assertEquals(mapPaintKey(emptyMap(), emptySet(), emptySet(), emptySet()), mapPaintKey(LinkedHashMap(), linkedSetOf(), setOf(), linkedSetOf()))
    }

    @Test fun everyColourInputChangesTheKey() {
        val base = mapPaintKey(mapOf(access("DE", 90)), setOf("GB"), setOf("DE"), emptySet())
        assertNotEquals(base, mapPaintKey(mapOf(access("DE", 30)), setOf("GB"), setOf("DE"), emptySet()))
        assertNotEquals(base, mapPaintKey(mapOf("DE" to Access(AccessLevel.FREEDOM, 90)), setOf("GB"), setOf("DE"), emptySet()))
        assertNotEquals(base, mapPaintKey(mapOf(access("DE", 90)), setOf("GB", "FR"), setOf("DE"), emptySet()))
        assertNotEquals(base, mapPaintKey(mapOf(access("DE", 90)), setOf("GB"), setOf(), emptySet()))
        assertNotEquals(base, mapPaintKey(mapOf(access("DE", 90)), setOf("GB"), setOf("DE"), setOf("CZ")))
    }

    @Test fun residenceClassAndReasonParticipateInEquality() {
        // The key leans on Access being a data class; pin the fields the map paints from.
        val withClass = Access(AccessLevel.RESIDENCE, null, fromDocument = true, residenceClass = ResidenceClass.LONG_TERM)
        val without = Access(AccessLevel.RESIDENCE, null, fromDocument = true)
        assertNotEquals(withClass, without)
        assertNotEquals(
            mapPaintKey(mapOf("DE" to withClass), emptySet(), emptySet(), emptySet()),
            mapPaintKey(mapOf("DE" to without), emptySet(), emptySet(), emptySet()),
        )
    }
}
