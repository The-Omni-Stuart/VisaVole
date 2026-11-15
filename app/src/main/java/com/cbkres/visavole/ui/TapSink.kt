package com.cbkres.visavole.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Swallows taps landing on a floating panel's own surface — its gaps, its padding, its dead
 * corners. Without a pointer node here, the hit test continues to whatever is behind the panel,
 * and the map canvas accepts an unconsumed down and selects a country under the panel. Only the
 * release is consumed, so scrolling and the panel's own controls keep working.
 */
internal fun Modifier.tapSink(): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown()
        do {
            val event = awaitPointerEvent()
            event.changes.forEach { if (!it.pressed) it.consume() }
        } while (event.changes.any { it.pressed })
    }
}
