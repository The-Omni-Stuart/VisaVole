package com.cbkres.visavole.ui.screens.trips

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cbkres.visavole.domain.GuardFinding
import com.cbkres.visavole.domain.GuardSeverity
import com.cbkres.visavole.domain.Trip
import com.cbkres.visavole.domain.WorldData
import com.cbkres.visavole.ui.VisaDialog
import com.cbkres.visavole.ui.screens.map.LocalEffectiveDarkTheme
import com.cbkres.visavole.ui.screens.map.SAVABLE_DANGER_CODES
import com.cbkres.visavole.ui.screens.map.Severity
import com.cbkres.visavole.ui.screens.map.severityColor
import java.time.LocalDate

@Composable
internal fun TripAlertDialog(
    title: String,
    confirmLabel: String?,
    onConfirm: () -> Unit,
    confirmEnabled: Boolean = true,
    neutralLabel: String? = null,
    onNeutral: (() -> Unit)? = null,
    dismissLabel: String = "Cancel",
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    VisaDialog(onDismissRequest = onDismiss) {
        Column(Modifier.padding(20.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            content()
            Spacer(Modifier.height(20.dp))
            if (neutralLabel != null && confirmLabel != null) {
                Button(
                    onClick = onConfirm,
                    enabled = confirmEnabled,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(confirmLabel) }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = { onNeutral?.invoke() ?: onDismiss() },
                        modifier = Modifier.weight(1f)
                    ) { Text(neutralLabel) }
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                        Text(
                            dismissLabel
                        )
                    }
                }
            } else if (neutralLabel != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = { onNeutral?.invoke() ?: onDismiss() },
                        modifier = Modifier.weight(1f)
                    ) { Text(neutralLabel) }
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                        Text(
                            dismissLabel
                        )
                    }
                }
            } else {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(onClick = onDismiss) { Text(dismissLabel) }
                    if (confirmLabel != null) {
                        Spacer(Modifier.width(12.dp))
                        Button(onClick = onConfirm, enabled = confirmEnabled) { Text(confirmLabel) }
                    }
                }
            }
        }
    }
}internal data class OngoingConflict(val candidate: Trip, val previous: Trip, val end: LocalDate)

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
    TripAlertDialog(
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
    TripAlertDialog(
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

/** Lists guard findings for the candidate trip; blocks forbid saving, warnings allow it. */
@Composable
internal fun SaveWarningsDialog(
    warnings: List<GuardFinding>,
    canSave: Boolean,
    onConfirm: () -> Unit,
    onBack: () -> Unit,
    onDismiss: () -> Unit,
) {
    TripAlertDialog(
        title = if (canSave) "Check trip" else "Cannot save yet",
        confirmLabel = if (canSave) "Save anyway" else null,
        confirmEnabled = canSave,
        onConfirm = onConfirm,
        neutralLabel = if (canSave) null else "Discard",
        onNeutral = onBack,
        dismissLabel = "Edit",
        onDismiss = onDismiss,
        content = {
            Column {
                warnings.forEach { f ->
                    Text(
                        "${f.title}: ${f.message}",
                        style = MaterialTheme.typography.bodySmall,
                        color = severityColor(
                            if (f.severity == GuardSeverity.BLOCK || f.code in SAVABLE_DANGER_CODES) Severity.BAD else Severity.WARN,
                            LocalEffectiveDarkTheme.current,
                        ),
                        modifier = Modifier.padding(vertical = 3.dp),
                    )
                }
            }
        },
    )
}
