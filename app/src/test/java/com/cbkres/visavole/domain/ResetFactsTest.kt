package com.cbkres.visavole.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/** F1's reset semantics and document cap, derived in the domain (§12.12 rows 3, 6, 7, 9, 10). */
class ResetFactsTest {

    private val today = LocalDate.of(2026, 11, 19)

    private fun rule(windowType: String) = StayRule(
        zoneName = "Test", countries = setOf("FR"), windowType = windowType,
        windowDays = 90, windowPeriodDays = if (windowType == "rolling") 180 else null,
        multipleEntry = true, nationalities = setOf("*"), note = null,
    )

    private fun status(total: Int?, used: Int = 0, remaining: Int? = null) = DocStatus(
        expired = false, reason = null, effectiveExpiry = null,
        entries = total?.let {
            EntryStatus(total = it, used = used, remaining = remaining, state = EntryState.IN_USE,
                effectiveExpiry = null)
        },
        notYetValid = false, expiryDate = null, replacementValidFrom = null,
    )

    @Test
    fun row1PassportVisaFreeResetsAtTheBorder() {
        val facts = resetFactsFor(rule("per-entry"), null)
        assertEquals(ResetMode.AT_BORDER, facts.resetMode)
        assertNull(facts.entryTotal)
    }

    @Test
    fun row3SingleEntryVisaCannotPromiseABorderReset() {
        val facts = resetFactsFor(rule("per-entry"), status(1, used = 1, remaining = 0))
        assertEquals(ResetMode.AT_NEW_DOCUMENT, facts.resetMode)
        assertEquals(1, facts.entryTotal)
        assertEquals(1, facts.entryUsed)
        assertEquals(0, facts.entryRemaining)
    }

    @Test
    fun row5DoubleEntryVisaCarriesItsCounts() {
        val facts = resetFactsFor(rule("per-entry"), status(2, used = 1, remaining = 1))
        assertEquals(ResetMode.AT_NEW_DOCUMENT, facts.resetMode)
        assertEquals(2, facts.entryTotal)
        assertEquals(1, facts.entryRemaining)
    }

    @Test
    fun row9LifetimeNeverRefills() {
        assertEquals(ResetMode.NEVER, resetFactsFor(rule("lifetime"), null).resetMode)
    }

    @Test
    fun row10UnknownWindowTypeStaysSilent() {
        assertEquals(ResetMode.UNKNOWN, resetFactsFor(rule("something-new"), status(2)).resetMode)
    }

    @Test
    fun row7RecoveryIsSuppressedWhenItsFirstSliceIsPastTheDocumentCap() {
        val rolling = rule("rolling")
        val segments = listOf(ZoneSegment(today.minusDays(10), today.minusDays(5)))
        assertNull("a slice that returns after the visa expires can never be spent",
            rollingRecovery(rolling, segments, today, cap = today.plusDays(3)))
    }

    @Test
    fun row7RecoveryIsCappedWhenWholeAgainFallsPastTheDocument() {
        val rolling = rule("rolling")
        val segments = listOf(ZoneSegment(today.minusDays(10), today.minusDays(5)))
        val cap = today.plusDays(172)
        val recovery = rollingRecovery(rolling, segments, today, cap = cap)!!
        assertEquals(cap, recovery.wholeAgainOn)
        assertEquals("first slice is before the cap and stays visible", today.plusDays(170), recovery.firstSliceBackOn)
    }
}
