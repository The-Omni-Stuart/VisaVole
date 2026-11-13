package com.cbkres.visavole.domain

import com.cbkres.visavole.data.Country
import com.cbkres.visavole.data.StayRule
import com.cbkres.visavole.data.WorldData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The trip guard's stay-rule lookups: [TripModel] memoises `stayRuleFor` per warning computation,
 * so one evaluation of a trip with stops against many trips resolves each destination once per memo
 * instead of once per stop pair. Pinning the count keeps a re-inlined lookup from creeping back in.
 */
class GuardTripMemoTest {

    private val today = LocalDate.of(2026, 11, 13)

    /** A stayRules list that counts every traversal, i.e. every raw `stayRuleFor` lookup. */
    private class CountingStayRules(private val delegate: List<StayRule>) : List<StayRule> by delegate {
        var lookups = 0
            private set
        override fun iterator(): Iterator<StayRule> {
            lookups++
            return delegate.iterator()
        }
    }

    private val rules = listOf(
        StayRule(
            "Schengen", setOf("DE", "FR"), "rolling", 90, 180, true, setOf("*"), null,
            id = "schengen", zoneId = "schengen",
        ),
        StayRule(
            "CA-4", setOf("CA"), "per-entry", 180, null, true, setOf("*"), null,
            id = "ca4", zoneId = "ca4",
        ),
    )

    private fun world(stayRules: List<StayRule>) = WorldData(
        mapOf(
            "DE" to Country("DE", "Germany"),
            "FR" to Country("FR", "France"),
            "CA" to Country("CA", "Canada"),
        ),
        emptyMap(), emptyList(), emptyMap(), emptyMap(), stayRules,
    )

    private fun stop(id: String, iso: String, from: Int, to: Int) =
        TripStop(id, iso, today.plusDays(from.toLong()), today.plusDays(to.toLong()))

    private val existing = listOf(
        Trip("t1", listOf(stop("a", "DE", -10, -5), stop("b", "FR", -9, -4))),
        Trip("t2", listOf(stop("c", "CA", -8, -3))),
    )

    private val candidate = Trip("new", listOf(stop("d", "DE", -6, -2), stop("e", "FR", -5, -1)))

    private val docs = listOf(Document("p", "Passport · GB", DocKind.Passport("GB")))

    private fun evaluate(stayRules: List<StayRule>): GuardResult = GuardEngine.evaluate(
        GuardAction.AddTrip(candidate),
        GuardContext.of(docs, existing + candidate, world(stayRules), "p", today),
    )

    @Test
    fun oneTripEvaluationLooksUpEachDestinationOncePerMemo() {
        val counted = CountingStayRules(rules)
        val result = evaluate(counted)
        assertTrue(result.warnings.isNotEmpty())
        assertTrue(result.warnings.map { it.code }.containsAll(listOf("trip.overlap", "trip.timeOverlap")))
        // Three memos run for one AddTrip (trip overlap, projection zones, projection usage) and each
        // resolves the fixture's three destinations once; the stop pairs alone used to need 12 lookups.
        assertEquals(8, counted.lookups)
    }

    @Test
    fun memoisedEvaluationMatchesThePlainOne() {
        val counted = CountingStayRules(rules)
        assertEquals(evaluate(rules), evaluate(counted))
    }

    @Test
    fun guardEvaluationIsDeterministic() {
        // The dialog hands its verdict to the ViewModel instead of re-running the engine, which is
        // only safe because a rule is a pure function of (action, context).
        assertEquals(evaluate(rules), evaluate(rules))
    }
}
