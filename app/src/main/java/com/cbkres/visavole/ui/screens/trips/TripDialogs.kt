package com.cbkres.visavole.ui.screens.trips

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cbkres.visavole.domain.Trip
import com.cbkres.visavole.domain.WorldData
import com.cbkres.visavole.ui.VoleAlertDialog
import com.cbkres.visavole.ui.VisaDialog
import java.time.LocalDate

internal data class OngoingConflict(val candidate: Trip, val previous: Trip, val end: LocalDate)

@Composable
internal fun GapDialog(
    details: String,
    message: String,
    error: String?,
    canUndo: Boolean,
    onKeepGap: () -> Unit,
    onUndo: () -> Unit,
    onSplit: () -> Unit,
    onDismiss: () -> Unit,
) {
    VisaDialog(onDismissRequest = onDismiss) {
        Column(Modifier.padding(20.dp)) {
            Text("Gap between stops", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
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
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onKeepGap, modifier = Modifier.weight(1f)) {
                    Text("Keep gap")
                }
                if (canUndo) {
                    OutlinedButton(onClick = onUndo, modifier = Modifier.weight(1f)) {
                        Text("Undo last change")
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(onClick = onSplit, modifier = Modifier.fillMaxWidth()) {
                Text("End trip and start new trip")
            }
        }
    }
}

@Composable
internal fun OngoingConfirmDialog(
    onAddDeparture: () -> Unit,
    onBack: () -> Unit,
    onDone: () -> Unit,
    onDismiss: () -> Unit,
) {
    VisaDialog(onDismissRequest = onDismiss) {
        Column(Modifier.padding(20.dp)) {
            Text("No final departure", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            Text(
                "This trip will be marked as ongoing.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(20.dp))
            OutlinedButton(onClick = onAddDeparture, modifier = Modifier.fillMaxWidth()) {
                Text("Add departure")
            }
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) {
                    Text("Back")
                }
                Button(onClick = onDone, modifier = Modifier.weight(1f)) {
                    Text("Done")
                }
            }
        }
    }
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
        confirmLabel = "Close previous & add",
        onConfirm = onClosePrevious,
        neutralLabel = "Keep both",
        onNeutral = onKeepBoth,
        dismissLabel = "Cancel",
        onDismiss = onDismiss,
        content = {
            Text(
                "“${TripPresentation(conflict.previous, world)}” is still open. Close it on ${conflict.end} and add this trip, or keep both.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}
