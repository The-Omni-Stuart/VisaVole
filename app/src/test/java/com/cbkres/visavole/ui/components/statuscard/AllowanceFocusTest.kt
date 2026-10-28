package com.cbkres.visavole.ui.components.statuscard

import com.cbkres.visavole.domain.AllowanceKind
import com.cbkres.visavole.domain.AllowanceSnapshot
import com.cbkres.visavole.domain.AllowanceStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class AllowanceFocusTest {

    private fun allowance(
        key: String,
        status: AllowanceStatus = AllowanceStatus.OK,
        currentTripId: String? = null,
    ) = AllowanceSnapshot(
        key = key,
        title = "Allowance $key",
        kind = AllowanceKind.ROLLING,
        status = status,
        currentTripId = currentTripId,
    )

    private val allowances = listOf(allowance("schengen"), allowance("ca4"))

    // A row where each allowance scores differently under TripModel.primaryAllowance's worked
    // example: the in-use one (currentTripId) outranks the DANGER one, which outranks the OK one.
    private val mixed = listOf(
        allowance("ok"),
        allowance("urgent", status = AllowanceStatus.DANGER),
        allowance("inuse", status = AllowanceStatus.OK, currentTripId = "trip-1"),
    )

    @Test
    fun tappingACardFocusesItAndReTappingItReturnsToThePrimary() {
        val focus = AllowanceFocus()

        focus.toggle("schengen")
        assertEquals("schengen", focus.effectiveFocusKey(allowances))

        focus.toggle("ca4")
        assertEquals("ca4", focus.effectiveFocusKey(allowances))

        // Re-tapping the focused card unfocuses — the user gets back to the primary.
        focus.toggle("ca4")
        assertEquals(null, focus.effectiveFocusKey(allowances))
    }

    @Test
    fun aFocusedAllowanceThatDisappearsFallsBackToThePrimary() {
        val focus = AllowanceFocus()
        focus.toggle("ca4")

        // The focused doc/trip was deleted or lapsed: its card is no longer in the row.
        assertEquals(null, focus.effectiveFocusKey(listOf(allowance("schengen"))))

        // And it isn't permanently stuck: tapping a surviving card focuses it again.
        focus.toggle("schengen")
        assertEquals("schengen", focus.effectiveFocusKey(listOf(allowance("schengen"))))
    }

    @Test
    fun thePrimaryDisplayFollowsTheFocusAndFallsBackWithoutIt() {
        val focus = AllowanceFocus()

        // No focus: the most urgent allowance is shown (in-use outranks danger outranks ok).
        assertEquals("inuse", focus.primaryAllowance(mixed)?.key)

        // A tapped card takes over the display.
        focus.toggle("ok")
        assertEquals("ok", focus.primaryAllowance(mixed)?.key)

        // A focus whose allowance has gone away falls back to the urgent pick, not to null.
        focus.toggle("ok") // unfocus, then focus a key that isn't in the row
        focus.toggle("ghost")
        assertEquals("inuse", focus.primaryAllowance(mixed)?.key)
    }
}
