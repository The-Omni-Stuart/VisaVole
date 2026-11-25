package com.cbkres.visavole.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Kept clear of the window edge so a dialog stays intact on narrow phones. */
private val DIALOG_EDGE_GAP = 8.dp

/** What the dialog leaves its window for chrome, matching the reserve the screen-relative sizing used. */
internal val DIALOG_CHROME_RESERVE = 96.dp

/** The dialog's usable height, measured from its window; unset outside [VisaDialog]'s content. */
internal val LocalVisaDialogHeight = staticCompositionLocalOf<Dp?> { null }

/**
 * Central dialog chrome: every hand-rolled app dialog is wrapped in this so the surface
 * style and the pop-in/pop-out animation are defined exactly once.
 *
 * [width] is the dialog's maximum width (340.dp for confirms, 400.dp for forms): a window
 * narrower than that gets the full width minus a small edge gap, so the trailing buttons are
 * never pushed off-screen; the height is capped against the dialog window (see
 * [LocalVisaDialogHeight]); [dismissable] = false keeps filled forms open on outside tap —
 * Back still closes, as an explicit intent to leave (discard with the Cancel button, or
 * accept with the confirm one).
 */
@Composable
internal fun VisaDialog(
    onDismissRequest: () -> Unit,
    width: Dp = 340.dp,
    dismissable: Boolean = true,
    content: @Composable () -> Unit,
) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    Dialog(
        // Back always closes, even a filled form: it is an explicit intent to leave, and
        // swallowing it read as a dead button. Only the scrim tap is gated by [dismissable].
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // A wrap-height dialog window resizes as its content composes and the system
        // re-centres it each time — the dialog visibly snaps low, then centre. Pinning the
        // window full-screen keeps it fixed so the pop-in animates in place; outside taps
        // now land inside the window, so the scrim dismisses them — a raw tap gesture, as
        // a clickable here would merge the dialog subtree into one query-hijacking node.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (dismissable) Modifier.pointerInput(Unit) {
                        detectTapGestures(onTap = { onDismissRequest() })
                    } else Modifier,
                ),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedVisibility(
                visible = shown,
                // There is no exit: closing unmounts the Dialog outright, so an exit transition
                // here would never get to run.
                enter = fadeIn(tween(160)) + scaleIn(tween(160), initialScale = 0.92f),
            ) {
                BoxWithConstraints {
                    val cap = (maxHeight - DIALOG_CHROME_RESERVE).coerceAtLeast(200.dp)
                    CompositionLocalProvider(LocalVisaDialogHeight provides cap) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            shape = MaterialTheme.shapes.large,
                            tonalElevation = 0.dp,
                            modifier = Modifier
                                // The keyboard is the one inset a dialog cannot scroll its way out of.
                                .imePadding()
                                .padding(horizontal = DIALOG_EDGE_GAP)
                                .widthIn(max = width)
                                .heightIn(max = cap),
                        ) { content() }
                    }
                }
            }
        }
    }
}
