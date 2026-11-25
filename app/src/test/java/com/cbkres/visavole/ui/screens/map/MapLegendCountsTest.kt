package com.cbkres.visavole.ui.screens.map

import com.cbkres.visavole.domain.Access
import com.cbkres.visavole.domain.AccessLevel
import com.cbkres.visavole.domain.AccessLevel.ETA
import com.cbkres.visavole.domain.AccessLevel.FREEDOM
import com.cbkres.visavole.domain.AccessLevel.UNKNOWN
import com.cbkres.visavole.domain.AccessLevel.VISA_FREE
import com.cbkres.visavole.domain.AccessLevel.VISA_REQUIRED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The legend's slate row read `counts[null]`, but the count builder grouped by the non-nullable
 * `Access.level` — the row was structurally always 0 while the canvas painted thousands of
 * countries slate. [legendCounts] must count what the canvas can actually paint slate: drawable
 * (geometry) countries that are not the traveller's homes and have no access entry — or an
 * UNKNOWN one, which has no palette and fills slate too.
 */
class MapLegendCountsTest {

    // Geometry carries AQ (drawn, but absent from the 203-country DB) — always slate-eligible.
    private val drawable = setOf("US", "GB", "DE", "FR", "ES", "AQ")
    private val homes = setOf("DE")
    private val access = mapOf(
        "US" to Access(VISA_FREE, 90),
        "GB" to Access(ETA, null),
        "DE" to Access(FREEDOM, null), // home: its own swatch, never slate, never a level bucket
        "FR" to Access(VISA_REQUIRED, null),
    )

    @Test
    fun slateCountsGeometryMinusAccessMinusHome() {
        // ES (no access) and AQ (drawn, absent from the DB) are slate; DE is a home.
        val counts = legendCounts(drawable, access, homes)
        assertEquals(2, counts[null])
        assertEquals(1, counts[VISA_FREE])
        assertEquals(1, counts[ETA])
        assertEquals(1, counts[VISA_REQUIRED])
        assertFalse("home must not enter a level bucket", counts.containsKey(FREEDOM))
    }

    @Test
    fun levelCountsUnchangedForNormalCases() {
        val counts = legendCounts(drawable, access, homes)
        // The previous builder, kept verbatim: level buckets over non-home access entries.
        val oldLevels = access.entries
            .filter { it.key !in homes }
            .groupBy { it.value.level }
            .mapValues { it.value.size }
        assertEquals(oldLevels, counts.filterKeys { it != null })
    }

    @Test
    fun emptyAccessLeavesEverythingSlateExceptHomes() {
        val counts = legendCounts(setOf("US", "DE", "FR"), emptyMap(), homes)
        assertEquals(mapOf<AccessLevel?, Int>(null to 2), counts)
    }

    @Test
    fun unknownLevelEntriesPaintSlateToo() {
        // MapColors has no UNKNOWN palette, so the canvas fills those countries slate as well.
        val counts = legendCounts(setOf("US", "GB"), mapOf("US" to Access(UNKNOWN, null)), emptySet())
        assertEquals(2, counts[null])
    }

    @Test
    fun residencePermitUnderBlocFreedomCountsAsResidence() {
        // The canvas paints BE teal (the permit outranks freedom visually), so the legend must
        // too: BE leaves the FREEDOM bucket and joins RESIDENCE, while CZ stays in FREEDOM.
        val acc = mapOf("BE" to Access(FREEDOM, null), "CZ" to Access(FREEDOM, null))
        val counts = legendCounts(setOf("BE", "CZ"), acc, homeCountries = setOf("DE"), residenceCountries = setOf("BE"))
        assertEquals(1, counts[AccessLevel.RESIDENCE])
        assertEquals(1, counts[FREEDOM])
    }

    @Test
    fun oldBuilderShapeHadNoSlateKeyForThisFixture() {
        // Regression pin for the S23/M7 bug: the old builder's exact bucketing (groupBy on the
        // non-nullable level) leaves the legend's slate read `counts[null]` at 0 for the same
        // slate-heavy fixture that now counts 2.
        val old: Map<AccessLevel?, Int> = access.entries
            .filter { it.key !in homes }
            .groupBy { it.value.level as AccessLevel? }
            .mapValues { it.value.size }
        assertFalse("the old builder could never produce a null (slate) bucket", old.containsKey(null))
        assertEquals(2, legendCounts(drawable, access, homes)[null])
    }
}
