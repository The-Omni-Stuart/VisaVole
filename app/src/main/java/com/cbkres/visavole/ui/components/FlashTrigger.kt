package com.cbkres.visavole.ui.components

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The ring-tap "scroll & flash" state shared by the Trips and Documents tab-top displays: which
 * list card a [StatusCard] ring tap just centred, with a per-card counter so each tap bumps that
 * card's one-shot "look here" ripple — bumping re-arms it, so tapping the same ring twice flashes
 * twice. The screens read [countFor] per card and call [trigger] after the centring scroll.
 */
@Stable
internal class FlashTrigger {
    private var current by mutableStateOf<Pair<String, Int>?>(null)

    /** The flash count the card with this id should play against (0 = never flashed). */
    fun countFor(id: String): Int =
        current?.takeIf { it.first == id }?.second ?: 0

    /** Flashes the card with this id; returns the count to hand to [expire] when the ripple ends. */
    fun trigger(id: String): Int {
        val n = countFor(id) + 1
        current = id to n
        return n
    }

    /**
     * The ripple for ([id], [count]) has played; stand the flash down so a card re-entering the
     * composition doesn't replay it. Clears only while [current] is still exactly this flash — a
     * newer [trigger] moved the pair on, and that timer will do its own clearing.
     */
    fun expire(id: String, count: Int) {
        if (current == (id to count)) current = null
    }
}
