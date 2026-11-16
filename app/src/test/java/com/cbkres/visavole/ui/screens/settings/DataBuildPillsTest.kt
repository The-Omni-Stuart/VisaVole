package com.cbkres.visavole.ui.screens.settings

import com.cbkres.visavole.domain.VisaDbMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the compact pill labels for the Settings data-build block: the build pill shows the generated
 * date only when the whole audit trail is present, and the warning pill is absent unless something
 * has lapsed or is lapsing.
 */
class DataBuildPillsTest {

    private fun pills(
        generated: String = "2026-11-01",
        rulesVerifiedAt: String = "2026-10-20",
        nextReview: String = "2027-01-15",
        warningParts: List<String> = emptyList(),
    ) = dataBuildPills(
        VisaDbMeta(generated, rulesVerifiedAt, nextReview),
        unknownLabel = "Build date unknown",
        warningParts = warningParts,
        separator = " · ",
    )

    @Test
    fun completeAuditShowsGeneratedDate() {
        assertEquals("2026-11-01", pills().buildLabel)
    }

    @Test
    fun missingOrGarbageAuditDateFallsBackToUnknown() {
        assertEquals("Build date unknown", pills(generated = "").buildLabel)
        assertEquals("Build date unknown", pills(rulesVerifiedAt = "").buildLabel)
        assertEquals("Build date unknown", pills(nextReview = "garbage").buildLabel)
    }

    @Test
    fun expiryFreeBuildHasNoWarningPill() {
        assertNull(pills().warningLabel)
    }

    @Test
    fun singleWarningPartPassesThrough() {
        assertEquals("2 lapsed", pills(warningParts = listOf("2 lapsed")).warningLabel)
    }

    @Test
    fun emptyLabelListIsNotAnEmptyPill() {
        assertNull(pills(warningParts = listOf("")).warningLabel)
    }

    @Test
    fun warningPartsJoinInGivenOrder() {
        assertEquals(
            "2 lapsed · 1 expiring",
            pills(warningParts = listOf("2 lapsed", "1 expiring")).warningLabel,
        )
    }
}
