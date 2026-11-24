package com.cbkres.visavole.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/** Which rule applies must be a property of the rules, never of the order rows arrive from the database. */
class StayRuleChoiceTest {

    private val today = LocalDate.of(2026, 11, 19)

    private fun rule(id: String, nationalities: Set<String>, days: Int) = StayRule(
        "Test Area $id",
        setOf("DE"),
        "rolling",
        days,
        180,
        true,
        nationalities,
        null,
        id = id,
        zoneId = null,
    )

    private val wildcardA = rule("a-wildcard", setOf("*"), 90)
    private val wildcardB = rule("b-wildcard", setOf("*"), 30)
    private val namedUs = rule("c-named", setOf("US"), 60)

    private fun world(vararg rules: StayRule) = WorldData(
        mapOf("DE" to Country("DE", "Germany")),
        emptyMap(),
        emptyList(),
        emptyMap(),
        emptyMap(),
        rules.toList(),
    )

    private fun chosen(vararg rules: StayRule) =
        world(*rules).stayRuleFor("DE", setOf("US"), today, allowSynthetic = false)?.id

    @Test
    fun syntheticRuleIsNotMintedFromALapsedCorridor() {
        val lapsed = Corridor("US", "DE", "visa-free", 90, validTo = "2026-11-18")
        val w = WorldData(
            mapOf("DE" to Country("DE", "Germany")),
            mapOf("US" to listOf(lapsed)),
            emptyList(), emptyMap(), emptyMap(), emptyList(),
        )
        assertNull(w.stayRuleFor("DE", setOf("US"), today))
    }

    @Test
    fun aTieBetweenTwoWildcardRowsIsNotDecidedByRowOrder() {
        assertEquals(chosen(wildcardA, wildcardB), chosen(wildcardB, wildcardA))
    }

    @Test
    fun aNamedPassportRuleStillBeatsAWildcardWhicheverOrderTheyArriveIn() {
        assertEquals(namedUs.id, chosen(wildcardA, wildcardB, namedUs))
        assertEquals(namedUs.id, chosen(namedUs, wildcardB, wildcardA))
    }

    @Test
    fun nothingIsInventedWhenNoRuleNamesTheTraveller() {
        val onlyOthers = world(rule("d-other", setOf("JP"), 15))
        assertEquals(null, onlyOthers.stayRuleFor("DE", setOf("US"), today, allowSynthetic = false))
    }
}
