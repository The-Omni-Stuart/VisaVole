package com.cbkres.visavole.ui

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * The queue behind the app's transient feedback. Snacks are buffered until the snackbar host is
 * composed, which is what lets an earlier phase's rejection pop over a fresh main screen — so
 * [dropBuffered] discards the pending feedback belonging to the phase that just ended, keeping
 * only what was sent [sticky]: warnings that describe the app's state (a damaged backup) rather
 * than a gesture the user can simply repeat.
 */
internal class SnackQueue {

    private data class Snack(val message: String, val sticky: Boolean)

    private val channel = Channel<Snack>(Channel.BUFFERED)
    val flow: Flow<String> = channel.receiveAsFlow().map { it.message }

    /** False when the queue is full and the message had to be dropped. */
    fun send(message: String, sticky: Boolean = false): Boolean =
        channel.trySend(Snack(message, sticky)).isSuccess

    fun dropBuffered() {
        val surviving = mutableListOf<Snack>()
        while (true) {
            val snack = channel.tryReceive().getOrNull() ?: break
            if (snack.sticky) surviving += snack
        }
        surviving.forEach { channel.trySend(it) }
    }
}
