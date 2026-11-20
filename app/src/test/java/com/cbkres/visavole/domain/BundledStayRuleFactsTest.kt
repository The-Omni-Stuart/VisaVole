package com.cbkres.visavole.domain

import com.cbkres.visavole.data.JdbcRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/**
 * Stay-rule facts read from the bundled dataset. Nothing else in either repo asserts a stay-rule
 * membership, so a dataset regression reaches a traveller's screen with both suites green: the
 * Schengen pool once carried Ireland and omitted Croatia, Iceland and Norway, which over-restricted
 * Dublin while promising a fresh 90 in Split. VisaDB's own suite checks rule *shape*, not membership.
 */
class BundledStayRuleFactsTest {

    private val world = JdbcRepository.load(File("src/main/assets/visa_data.db"))
    private val today = LocalDate.of(2026, 11, 18)

    private fun ruleFor(dest: String, passport: String) = world.stayRuleFor(dest, setOf(passport), today)
    private fun poolKey(dest: String, passport: String) = ruleFor(dest, passport)?.let(world::zoneKeyFor)

    @Test
    fun schengenPartnersShareOneRollingPool() {
        for (dest in listOf("DE", "HR", "IS", "NO", "GR")) {
            val rule = ruleFor(dest, "US")
            assertEquals("$dest must sit in the shared Schengen pool", "stay:schengen", rule?.let(world::zoneKeyFor))
            assertEquals("$dest shares the pool's rolling window", "rolling", rule?.windowType)
            assertEquals("$dest is metered in the same 180-day window", 180, rule?.windowPeriodDays)
        }
    }

    @Test
    fun irelandFallsOutOfThePoolIntoItsOwnCounter() {
        // Ireland keeps the Common Travel Area, not the Schengen pool: a US traveller's Dublin week
        // must not consume any of the allowance they would spend in France.
        val rule = ruleFor("IE", "US")
        assertEquals("stay:synthetic:IE", rule?.let(world::zoneKeyFor))
        assertEquals("per-entry", rule?.windowType)
        assertEquals(90, rule?.windowDays)
        assertTrue("Ireland must not draw on the Schengen pool", poolKey("IE", "US") != "stay:schengen")
    }

    @Test
    fun commonTravelAreaStayCarriesNoStayRuleAtAll() {
        // GB -> IE is visa-free with no published limit (the corridor's days are null), so there is
        // no window to meter. A rule here would mint a fake allowance for a right that is not time-limited.
        assertNull("a GB traveller in Ireland is inside the CTA, not a stay window", ruleFor("IE", "GB"))
    }

    @Test
    fun westernBalkansStatesKeepSeparateCounters() {
        // Four countries, four allowances: the row that used to cover them shared one pool while its
        // own note said it did not, so a BA+ME+MK+RS tour reported an overstay no law creates.
        val keys = listOf("BA", "ME", "MK", "RS").map { poolKey(it, "US") }
        assertEquals("the four must land on four separate counters", keys.size, keys.toSet().size)
        assertFalse("the merged counter must be gone", keys.contains("stay:western-balkans"))
        keys.forEach { assertTrue("$it must still be metered at all", it != null) }
    }

    @Test
    fun eacVisaIsOneConsumedTicketThatNeverRefills() {
        // Neither a rolling refill nor a border refill describes a visa that grants the days once.
        val rule = ruleFor("KE", "US")
        assertEquals("lifetime", rule?.windowType)
        assertNull("a consumed single ticket has no sliding period", rule?.windowPeriodDays)
        assertEquals("the three states still share the one allowance", "stay:eac", poolKey("RW", "US"))
        assertEquals("stay:eac", poolKey("UG", "US"))
    }

    @Test
    fun rulelessDestinationGetsItsOwnCounter() {
        // Most destinations have no curated stay rule and fall back to the corridor's own grant.
        // They must never read as part of the Schengen pool, which is what would make alpha 5 print
        // a recovery date for an allowance that is simply per-entry.
        val key = poolKey("AE", "US")
        assertTrue("UAE should be metered, but on its own: $key", key != null && key != "stay:schengen")
    }

    @Test
    fun schengenDocumentCoversEveryPoolMember() {
        // The travel bloc behind a Schengen visa/residence is what unlocks its member states, so a
        // missing member silently makes a legal entry look illegal. Greece was absent for exactly
        // this path: 28 members where the pool has 29.
        val bloc = world.travelBlocFor(setOf("DE"))
        val pool = world.stayRules.filter { it.zoneId == "schengen" }
        val membership = pool.filter { it.id == "schengen" }.flatMap { it.countries }.toSet()
        assertEquals("the bloc and the pool must agree on membership", membership, bloc?.members?.toSet())
        assertTrue("Greece must be covered by a Schengen document", bloc?.members?.contains("GR") == true)
        // The design intent: short-stay travel, not freedom of movement, so the opt-outs stay out.
        assertFalse("Ireland is not part of the Schengen travel area", bloc?.members?.contains("IE") == true)
        assertFalse("Cyprus is not part of the Schengen travel area", bloc?.members?.contains("CY") == true)
    }

    @Test
    fun microstatesAndGibraltarShareThePoolWithoutClaimingMembership() {
        val pool = world.stayRules.filter { it.zoneId == "schengen" }
        val extensions = listOf("MC", "SM", "VA", "AD", "GI")
        assertEquals("Gibraltar joins the four microstates as a pool-only row",
                     extensions.toSet(), pool.filter { it.id != "schengen" }.flatMap { it.countries }.toSet())
        assertEquals("2026-07-15", pool.first { it.id == "gi" }.validFrom)
        for (dest in extensions) {
            val rule = ruleFor(dest, "US")
            assertEquals("$dest must be metered inside the Schengen pool", "stay:schengen", rule?.let(world::zoneKeyFor))
            assertEquals("$dest must not redefine the pool's window", "rolling", rule?.windowType)
            assertEquals("$dest must not resize the pool", 90, rule?.windowDays)
            assertEquals("$dest must not rescale the pool", 180, rule?.windowPeriodDays)
        }
    }
}
