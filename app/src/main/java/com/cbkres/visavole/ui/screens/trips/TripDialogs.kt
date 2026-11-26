package com.cbkres.visavole.ui.screens.trips

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cbkres.visavole.domain.Trip
import com.cbkres.visavole.domain.WorldData
import com.cbkres.visavole.domain.formatHuman
import com.cbkres.visavole.ui.VoleAlertDialog
import java.time.LocalDate

internal data class OngoingConflict(val candidate: Trip, val previous: Trip, val end: LocalDate)

@Composable
internal fun GapDialog(
    details: String,
    message: String,
    error: String?,
    canUndo: Boolean,
    onUndo: () -> Unit,
    onSplit: () -> Unit,
    onDismiss: () -> Unit,
) {
    VoleAlertDialog(
        title = "Gap between stops",
        confirmLabel = "End trip and start new trip",
        onConfirm = onSplit,
        neutralLabel = if (canUndo) "Undo last change" else null,
        onNeutral = onUndo,
        dismissLabel = "Keep gap",
        onDismiss = onDismiss,
        content = {
            Text(
                details,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(8.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            error?.let { err ->
                Spacer(Modifier.height(8.dp))
                Text(
                    err,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
    )
}

@Composable
internal fun OngoingConfirmDialog(
    onAddDeparture: () -> Unit,
    onBack: () -> Unit,
    onDone: () -> Unit,
    onDismiss: () -> Unit,
) {
    VoleAlertDialog(
        title = "No final departure",
        confirmLabel = "Add departure",
        onConfirm = onAddDeparture,
        neutralLabel = "Done",
        onNeutral = onDone,
        onDismiss = onDismiss,
        content = {
            Text(
                "This trip will be marked as ongoing.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
    )
}

/** Warns that adding a stop to an ongoing trip closes the current stop. */
@Composable
internal fun AddStopWarningDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    VoleAlertDialog(
        title = "Add stop to ongoing trip",
        confirmLabel = "Add stop",
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        content = { Text("This trip is ongoing. Adding a new stop will close the current stop. A departure date will be added automatically and can be edited afterwards.") },
    )
}

/** Asks whether to close the other ongoing trip on its end date before saving. */
@Composable
internal fun OngoingConflictDialog(
    conflict: OngoingConflict,
    world: WorldData,
    onClosePrevious: () -> Unit,
    onKeepBoth: () -> Unit,
    onDismiss: () -> Unit,
) {
    VoleAlertDialog(
        title = "Another trip is ongoing",
        confirmLabel = "Close previous and add",
        onConfirm = onClosePrevious,
        neutralLabel = "Keep both",
        onNeutral = onKeepBoth,
        dismissLabel = "Cancel",
        onDismiss = onDismiss,
        content = {
            Text(
                "“${TripPresentation(conflict.previous, world)}” is ongoing. Close it on ${conflict.end.formatHuman()} and add this trip, or keep both.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}
