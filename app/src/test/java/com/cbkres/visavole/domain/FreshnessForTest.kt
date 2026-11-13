package com.cbkres.visavole.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Pins the [freshnessFor] colour bands (green ≤30 d, amber ≤90 d, red beyond, inclusive boundaries)
 * and the graceful degradation: unusable dates must never throw, only turn the row neutral.
 */
class FreshnessForTest {

    private val today = LocalDate.of(2026, 11, 13)

    private fun freshness(
        generated: String = today.toString(),
        rulesVerifiedAt: String = "2026-09-02",
        nextReview: String = "2026-12-01",
        lapsedRules: Int = 0,
        lapsingSoon: Int = 0,
    ) = freshnessFor(generated, rulesVerifiedAt, nextReview, lapsedRules, lapsingSoon, today)

    @Test
    fun sameDayBuildIsGreen() {
        val f = freshness()
        assertEquals(0L, f.ageDays)
        assertEquals(FreshnessLevel.GREEN, f.level)
    }

    @Test
    fun buildExactlyThirtyDaysOldIsStillGreen() {
        val f = freshness(generated = today.minusDays(30).toString())
        assertEquals(30L, f.ageDays)
        assertEquals(FreshnessLevel.GREEN, f.level)
    }

    @Test
    fun buildThirtyOneDaysOldIsAmber() {
        val f = freshness(generated = today.minusDays(31).toString())
        assertEquals(FreshnessLevel.AMBER, f.level)
    }

    @Test
    fun buildExactlyNinetyDaysOldIsStillAmber() {
        val f = freshness(generated = today.minusDays(90).toString())
        assertEquals(90L, f.ageDays)
        assertEquals(FreshnessLevel.AMBER, f.level)
    }

    @Test
    fun buildNinetyOneDaysOldIsRed() {
        val f = freshness(generated = today.minusDays(91).toString())
        assertEquals(FreshnessLevel.RED, f.level)
    }

    @Test
    fun lapsedRulesRaiseWarn() {
        assertTrue(freshness(lapsedRules = 1).warn)
    }

    @Test
    fun lapsingSoonRulesRaiseWarn() {
        assertTrue(freshness(lapsingSoon = 2).warn)
    }

    @Test
    fun expiryFreeBuildDoesNotWarn() {
        assertFalse(freshness().warn)
    }

    @Test
    fun unparsableBuildDateDegradesToNeutral() {
        val f = freshness(generated = "not-a-date")
        assertNull(f.ageDays)
        assertEquals(FreshnessLevel.NEUTRAL, f.level)
    }

    @Test
    fun missingAuditDatesDegradeToNeutral() {
        assertEquals(FreshnessLevel.NEUTRAL, freshness(rulesVerifiedAt = "").level)
        assertEquals(FreshnessLevel.NEUTRAL, freshness(nextReview = "garbage").level)
    }

    @Test
    fun warnSurvivesUnusableDates() {
        val f = freshness(generated = "???", lapsedRules = 1)
        assertEquals(FreshnessLevel.NEUTRAL, f.level)
        assertTrue(f.warn)
    }
}
