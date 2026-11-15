package com.cbkres.visavole.ui.screens.documents

import com.cbkres.visavole.domain.Country
import com.cbkres.visavole.domain.DocKind
import com.cbkres.visavole.domain.Document
import com.cbkres.visavole.domain.GuardContext
import com.cbkres.visavole.domain.GuardMutation
import com.cbkres.visavole.domain.Holding
import com.cbkres.visavole.domain.Trip
import com.cbkres.visavole.domain.TripStop
import com.cbkres.visavole.domain.WorldData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The save chain is a pure decision over the guard's result: exactly one step at a time
 * (block → multi-country confirm → mutation confirm → remaining-warning confirm → save),
 * and no step may skip past a warning that still needs the user's eye. These pins replay the
 * dialog's decision chain through [decideDocSave] without Compose.
 */
class DocSaveChainTest {

    private val today = LocalDate.of(2026, 9, 16)

    private fun world() = WorldData(
        mapOf(
            "FR" to Country("FR", "France"),
            "DE" to Country("DE", "Germany"),
            "CA" to Country("CA", "Canada"),
        ),
        emptyMap(), emptyList(),
        mapOf("ca-pr" to Holding("ca-pr", "Canadian PR", "residency", "CA")),
        emptyMap(), emptyList(),
    )

    private fun ctx(docs: List<Document>, trips: List<Trip> = emptyList()) =
        GuardContext(docs, trips, world(), today)

    private fun visa(
        id: String,
        countries: Set<String>,
        entry: String? = "multiple",
        expiry: String? = null,
        validFrom: String? = null,
    ) = Document(
        id,
        "Visa: ${countries.joinToString(",")}",
        DocKind.Custom(countries, null, "visa", null, entry),
        expiry,
        validFrom,
    )

    private fun stop(id: String, iso: String, docId: String? = null, offset: Long = 30) =
        TripStop(id, iso, today.plusDays(offset), today.plusDays(offset + 10), docId)

    private fun trip(id: String, vararg stops: TripStop) = Trip(id, stops.toList())

    @Test
    fun aCleanSaveGoesStraightThrough() {
        val v = visa("v1", setOf("CA"), "multiple", "2028-01-01")
        val step = decideDocSave(v, ctx(emptyList()))
        assertTrue("expected Save, got $step", step is DocSaveDecision.Save)
    }

    @Test
    fun aBlockStopsTheChain() {
        val live = visa("v1", setOf("CA"), "multiple", "2028-01-01")
        val dup = visa("v2", setOf("CA"), "multiple", "2028-01-01")
        val step = decideDocSave(dup, ctx(listOf(live)))
        assertTrue("expected Blocked, got $step", step is DocSaveDecision.Blocked)
        val blocked = step as DocSaveDecision.Blocked
        assertEquals("doc.duplicate", blocked.finding.code)
        assertEquals(live.id, blocked.duplicate?.id)
    }

    @Test
    fun aWarningWithoutItsOwnConfirmEndsInSaveWarnings() {
        val v1 = visa("v1", setOf("CA"), "multiple", "2028-01-01")
        val t = trip(
            "t1",
            stop("s1", "CA", docId = "v1"),
            stop("s2", "CA", docId = "v1", offset = 50),
        )
        val candidate = v1.copy(kind = DocKind.Custom(setOf("CA"), null, "visa", null, "single"))
        val step = decideDocSave(candidate, ctx(listOf(v1), listOf(t)))
        assertTrue("expected ShowSaveWarnings, got $step", step is DocSaveDecision.ShowSaveWarnings)
        val shown = (step as DocSaveDecision.ShowSaveWarnings).warnings
        assertEquals(listOf("doc.inUse.entriesPlanned"), shown.map { it.code })
    }

    @Test
    fun aMutationWithoutOtherWarningsConfirmsThenSaves() {
        val v1 = visa("v1", setOf("CA"), "multiple", "2027-06-30")
        val v2 = visa("v2", setOf("CA"), "multiple", "2028-01-01", "2026-10-01")
        val step = decideDocSave(v2, ctx(listOf(v1)))
        assertTrue("expected ConfirmMutation, got $step", step is DocSaveDecision.ConfirmMutation)
        val confirm = step as DocSaveDecision.ConfirmMutation
        assertEquals(listOf(GuardMutation.SupersedeDocument("v1", "v2")), confirm.mutations)

        val next = decideDocSave(v2, ctx(listOf(v1)), skipMutation = true)
        assertTrue("expected Save, got $next", next is DocSaveDecision.Save)
    }

    /**
     * Confirming the accompanying mutation must not swallow the warnings the mutation dialog
     * never showed: the save still ends in the warnings dialog with exactly the findings the
     * user has not seen.
     */
    /**
     * An in-use visa reduced from multiple to single entries while future trips plan it, and the
     * same edit supersedes another visa: confirming the supersession must not swallow the
     * planned-entries warning the mutation dialog never showed.
     */
    @Test
    fun mutationConfirmStillShowsRemainingWarnings() {
        val other = visa("v-old", setOf("CA"), "multiple", "2027-06-30")
        val v1 = visa("v1", setOf("CA"), "multiple", "2028-12-01")
        val planned = trip(
            "t1",
            stop("s1", "CA", docId = "v1"),
            stop("s2", "CA", docId = "v1", offset = 50),
        )
        val candidate = v1.copy(
            kind = DocKind.Custom(setOf("CA"), null, "visa", null, "single"),
            validFrom = "2026-10-01",
        )

        val step = decideDocSave(candidate, ctx(listOf(other, v1), listOf(planned)))
        assertTrue("expected ConfirmMutation, got $step", step is DocSaveDecision.ConfirmMutation)
        val confirm = step as DocSaveDecision.ConfirmMutation
        assertEquals(
            "the mutation dialog must show the single-per-country warning it explains",
            "doc.single.perCountry", confirm.warning?.code,
        )

        val next = decideDocSave(candidate, ctx(listOf(other, v1), listOf(planned)), skipMutation = true)
        assertTrue("expected ShowSaveWarnings after mutation-confirm, got $next", next is DocSaveDecision.ShowSaveWarnings)
        val shown = (next as DocSaveDecision.ShowSaveWarnings).warnings.map { it.code }
        assertTrue("planned-entries warning was swallowed: $shown", shown.contains("doc.inUse.entriesPlanned"))
        assertTrue("the mutation dialog's warning must not be asked twice: $shown", !shown.contains("doc.single.perCountry"))
    }

    /** Each confirm step runs in turn, and each skip flag silences only its own step. */
    @Test
    fun theConfirmStepsRunInOrder() {
        val live = visa("v1", setOf("FR"), "multiple", "2028-06-30")
        val candidate = visa("v2", setOf("FR", "DE"), "multiple", "2029-01-01", "2026-10-01")

        val first = decideDocSave(candidate, ctx(listOf(live)))
        assertTrue("multi-country ask must come first, got $first", first is DocSaveDecision.ConfirmMultiCountry)

        val second = decideDocSave(candidate, ctx(listOf(live)), skipMultiCountry = true)
        assertTrue("expected ConfirmMutation, got $second", second is DocSaveDecision.ConfirmMutation)

        val third = decideDocSave(candidate, ctx(listOf(live)), skipMultiCountry = true, skipMutation = true)
        assertTrue("expected Save, got $third", third is DocSaveDecision.Save)
    }
}
