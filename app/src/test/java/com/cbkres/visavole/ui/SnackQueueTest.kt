package com.cbkres.visavole.ui

import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Snacks are buffered until the snackbar host exists, which means feedback from an earlier
 * phase (an onboarding rejection) can pop over a freshly built main screen. The queue is what
 * lets the arriving host throw that phase's feedback away while keeping the warnings that
 * describe the app's state rather than a rejected gesture.
 */
class SnackQueueTest {

    private suspend fun SnackQueue.next(): String? = withTimeoutOrNull(120) { flow.firstOrNull() }

    @Test
    fun feedbackBufferedBeforeTheHostArrivesIsDropped() = runBlocking {
        val queue = SnackQueue()
        queue.send("That document is a duplicate.")
        queue.send("A trip needs at least one stop.")

        queue.dropBuffered()

        assertNull("stale feedback survived the arrival of the host", queue.next())
    }

    @Test
    fun stateWarningsKeepTheirTurnThroughTheDrain() = runBlocking {
        val queue = SnackQueue()
        queue.send("A trip needs at least one stop.")
        queue.send("2 records could not be read", sticky = true)
        queue.send("Could not reload the visa database", sticky = true)

        queue.dropBuffered()

        assertEquals("2 records could not be read", queue.next())
        assertEquals("Could not reload the visa database", queue.next())
        assertNull(queue.next())
    }

    @Test
    fun feedbackSentAfterTheHostArrivedIsNeverDropped() = runBlocking {
        val queue = SnackQueue()
        queue.dropBuffered()
        queue.send("First half saved")

        assertEquals("First half saved", queue.next())
    }
}
