package com.cbkres.visavole.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Kept clear of the window edge so a dialog stays intact on narrow phones. */
private val DIALOG_EDGE_GAP = 8.dp

/** What the dialog leaves its window for chrome, matching the reserve the screen-relative sizing used. */
private val DIALOG_CHROME_RESERVE = 96.dp

/** The dialog's usable height, measured from its window; unset outside [VisaDialog]'s content. */
internal val LocalVisaDialogHeight = staticCompositionLocalOf<Dp?> { null }

/**
 * Central dialog chrome: every hand-rolled app dialog is wrapped in this so the surface
 * style and the pop-in/pop-out animation are defined exactly once.
 *
 * [width] is the dialog's maximum width (340.dp for confirms, 400.dp for forms): a window
 * narrower than that gets the full width minus a small edge gap, so the trailing buttons are
 * never pushed off-screen; [maxHeight] caps form dialogs against the screen height;
 * [dismissable] = false keeps filled forms open on outside tap AND on the system Back button
 * (both are swallowed — use the explicit Cancel button to discard).
 */
@Composable
internal fun VisaDialog(
    onDismissRequest: () -> Unit,
    width: Dp = 340.dp,
    /** Caps the dialog; measured from its own window when absent, never from the screen's height. */
    maxHeightOverride: Dp? = null,
    dismissable: Boolean = true,
    content: @Composable () -> Unit,
) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    Dialog(
        // The platform still dispatches Back to onDismissRequest for non-dismissable dialogs
        // (only outside TAP is gated by dismissOnClickOutside) — swallow it there too, or Back
        // would wipe a filled form.
        onDismissRequest = { if (dismissable) onDismissRequest() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = dismissable),
    ) {
        AnimatedVisibility(
            visible = shown,
            // There is no exit: closing unmounts the Dialog outright, so an exit transition
            // here would never get to run.
            enter = fadeIn(tween(160)) + scaleIn(tween(160), initialScale = 0.92f),
        ) {
            BoxWithConstraints {
                val cap = maxHeightOverride ?: (maxHeight - DIALOG_CHROME_RESERVE).coerceAtLeast(200.dp)
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
