package com.cbkres.visavole.ui.components.statuscard

import org.junit.Assert.assertEquals
import org.junit.Test

class FlashTriggerTest {

    @Test
    fun tappingARingFlashesTheCardAndTappingAgainReplaysTheFlash() {
        val flashes = FlashTrigger()

        assertEquals(0, flashes.countFor("doc-1"))

        flashes.trigger("doc-1")
        assertEquals(1, flashes.countFor("doc-1"))

        // Each tap bumps the counter, so the card replays its one-shot ripple.
        val n = flashes.trigger("doc-1")
        assertEquals(2, flashes.countFor("doc-1"))
        assertEquals(2, n)
    }

    @Test
    fun theRippleEndingClearsTheFlashSoARenteringCardDoesNotReplayIt() {
        val flashes = FlashTrigger()

        val n = flashes.trigger("doc-1")
        flashes.expire("doc-1", n)
        assertEquals(0, flashes.countFor("doc-1"))
    }

    @Test
    fun aStaleRippleTimerNeverClearsANewerFlash() {
        val flashes = FlashTrigger()

        val first = flashes.trigger("doc-1")
        // The user taps the same ring again before the first ripple's timer lands.
        val second = flashes.trigger("doc-1")
        assertEquals(2, second)

        // The first tap's timer firing must not wipe the newer flash.
        flashes.expire("doc-1", first)
        assertEquals(2, flashes.countFor("doc-1"))

        // Only the matching (newest) timer clears it.
        flashes.expire("doc-1", second)
        assertEquals(0, flashes.countFor("doc-1"))
    }

    @Test
    fun flashingAnotherCardRetiresThePreviousCardsFlash() {
        val flashes = FlashTrigger()

        val n = flashes.trigger("doc-1")
        flashes.trigger("doc-2")
        assertEquals(0, flashes.countFor("doc-1"))

        // doc-1's timer, landing after doc-2 was flashed, must leave doc-2's flash alone.
        flashes.expire("doc-1", n)
        assertEquals(1, flashes.countFor("doc-2"))
    }
}
