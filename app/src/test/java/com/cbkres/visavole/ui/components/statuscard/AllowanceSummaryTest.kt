package com.cbkres.visavole.ui.components.statuscard

import com.cbkres.visavole.domain.AllowanceKind
import com.cbkres.visavole.domain.AllowanceSnapshot
import com.cbkres.visavole.domain.AllowanceStatus
import com.cbkres.visavole.domain.Recovery
import com.cbkres.visavole.domain.ResetMode
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class AllowanceSummaryTest {

    private fun snapshot(
        kind: AllowanceKind = AllowanceKind.ROLLING,
        status: AllowanceStatus = AllowanceStatus.OK,
    ) = AllowanceSnapshot(
        key = "schengen",
        title = "Schengen Area",
        kind = kind,
        status = status,
    )

    @Test
    fun theNoValidDocumentFlagOverridesEveryOtherSummary() {
        // Loaded with every other branch's data: the flag must still win the whole line.
        val a = snapshot(status = AllowanceStatus.DANGER).copy(
            noValidDoc = true,
            overstayDays = 23,
            remainingDays = 5,
            totalDays = 90,
            effectiveExpiry = LocalDate.of(2026, 9, 1),
            subtitle = "90 in 180 (rolling)",
        )

        assertEquals("No valid document", allowanceSummary(a))
    }

    @Test
    fun overstayingDaysAreReportedAsANegativeCountdown() {
        val a = snapshot(status = AllowanceStatus.DANGER).copy(
            overstayDays = 23,
            remainingDays = 0,
            totalDays = 90,
        )

        assertEquals("-23 Days OVERSTAYING!", allowanceSummary(a))
    }

    @Test
    fun anExpiredDocumentReportsItsExpiryWhileAWindowedZoneStillCountsDays() {
        val visa = snapshot(kind = AllowanceKind.DOCUMENT_VALIDITY, status = AllowanceStatus.DANGER).copy(
            effectiveExpiry = LocalDate.of(2026, 9, 1),
            remainingDays = 0,
        )
        assertEquals("Expired on 1 Sep 2026", allowanceSummary(visa))

        // A DANGER rolling zone is "out of days", not "expired" — windows keep the day-count line.
        val zone = snapshot(kind = AllowanceKind.ROLLING, status = AllowanceStatus.DANGER).copy(
            remainingDays = 0,
            totalDays = 90,
            effectiveExpiry = LocalDate.of(2026, 9, 1),
        )
        assertEquals("0 of 90 days left", allowanceSummary(zone))
    }

    @Test
    fun anEntryCountDocumentCarriesDaysAndEntriesDotJoined() {
        val visa = snapshot(kind = AllowanceKind.ENTRY_COUNT, status = AllowanceStatus.WARNING).copy(
            remainingDays = 12,
            entryRemaining = 3,
        )
        assertEquals("12 days left · 3 entries left", allowanceSummary(visa))

        val last = visa.copy(remainingDays = 1, entryRemaining = 1)
        assertEquals("1 day left · 1 entry left", allowanceSummary(last))

        val today = visa.copy(remainingDays = 0, entryRemaining = 0)
        assertEquals("Expires today · no entries left", allowanceSummary(today))
    }

    @Test
    fun anEntryCountDocumentWithoutDaysShowsJustTheEntriesHalf() {
        val visa = snapshot(kind = AllowanceKind.ENTRY_COUNT).copy(entryRemaining = 4)
        assertEquals("4 entries left", allowanceSummary(visa))
    }

    @Test
    fun anEntryCountDocumentWithNothingLeftToShowFallsBackToSubtitleThenNoLimit() {
        val withSubtitle = snapshot(kind = AllowanceKind.ENTRY_COUNT).copy(subtitle = "Multiple entry")
        assertEquals("Multiple entry", allowanceSummary(withSubtitle))

        assertEquals("No limit", allowanceSummary(snapshot(kind = AllowanceKind.ENTRY_COUNT)))
    }

    @Test
    fun aValidityDocumentReportsItsRemainingDays() {
        val residence = snapshot(kind = AllowanceKind.DOCUMENT_VALIDITY).copy(remainingDays = 30)
        assertEquals("Valid for 30 days", allowanceSummary(residence))

        val lastDay = residence.copy(remainingDays = 1)
        assertEquals("Valid for 1 day", allowanceSummary(lastDay))
    }

    @Test
    fun anAllowanceWithNoDayDataFallsBackToSubtitleThenNoLimit() {
        val noDayData = snapshot().copy(subtitle = "90 in 180 (rolling)")
        assertEquals("90 in 180 (rolling)", allowanceSummary(noDayData))

        assertEquals("No limit", allowanceSummary(snapshot()))
    }

    private fun recovering() = snapshot().copy(
        remainingDays = 84,
        totalDays = 90,
        recovery = Recovery(
            firstSliceBackOn = LocalDate.of(2027, 4, 1),
            wholeAgainOn = LocalDate.of(2027, 4, 18),
            daysBack = 6,
            sliceDays = 2,
            inProgress = false,
            totalDays = 90,
        ),
    )

    @Test
    fun recoveryLinePrintsBothDatesAtHomeAndGoesQuietInsideTheZone() {
        val atHome = recovering()
        assertEquals("Regenerating from 1 Apr 2027 (2 days)", allowanceAlternateLine(atHome))
        assertEquals(null, allowanceAlternateLine(atHome.copy(currentTripId = "trip")))
        assertEquals(null, allowanceAlternateLine(atHome.copy(overstayDays = 1)))
    }

    @Test
    fun aRunningRecoveryLocksTheFaceToPresentTenseAndStaysSilentInsideTheZone() {
        val running = recovering().copy(recovery = recovering().recovery!!.copy(inProgress = true))
        assertEquals("Regenerating for 2 days", allowanceAlternateLine(running))
        assertEquals(false, allowanceFaceRotates(running))
        assertEquals(null, allowanceAlternateLine(running.copy(currentTripId = "trip")))
        assertEquals(
            "Allowance: 84 of 90 days left; regenerating for 2 days",
            allowanceSpokenDescription(running),
        )
    }

    @Test
    fun perEntryLineTellsABorderResetFromASpentSingleEntryVisa() {
        val border = snapshot(kind = AllowanceKind.PER_ENTRY).copy(
            resetMode = ResetMode.AT_BORDER,
            currentTripId = "trip",
        )
        assertEquals("resets on re-entry", allowanceAlternateLine(border))
        assertEquals(null, allowanceAlternateLine(border.copy(currentTripId = null)))

        val single = border.copy(resetMode = ResetMode.AT_NEW_DOCUMENT, entryTotal = 1, entryRemaining = 1)
        assertEquals("this entry only", allowanceAlternateLine(single))
        assertEquals(null, allowanceAlternateLine(single.copy(entryRemaining = 0)))

        val double = border.copy(resetMode = ResetMode.AT_NEW_DOCUMENT, entryTotal = 2, entryRemaining = 1)
        assertEquals("final entry", allowanceAlternateLine(double))
        assertEquals("resets on your next entry", allowanceAlternateLine(double.copy(entryRemaining = 2)))
    }

    @Test
    fun theSpokenDescriptionCarriesBothHalvesInOneStaticString() {
        val text = allowanceSpokenDescription(recovering())
        assertEquals("Allowance: 84 of 90 days left; regenerating from 1 April (2 days)", text)
        assertEquals(false, text.contains("2027-04-01"))
    }
}
