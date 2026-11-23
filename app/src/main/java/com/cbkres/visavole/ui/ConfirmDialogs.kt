package com.cbkres.visavole.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import com.cbkres.visavole.ui.screens.map.LocalEffectiveDarkTheme
import com.cbkres.visavole.ui.screens.map.Severity
import com.cbkres.visavole.ui.screens.map.severityColor

/** Codes that were DANGERs before the unified guard (savable): kept WARN, rendered in the danger colour. */
private val savableDangerCodes = setOf(
    "trip.allowance.exceeded",
    "trip.entries.over",
    "trip.access.none",
    "trip.access.blocked",
    "trip.passport.expiringSoon3",
)

/**
 * The one confirm/alert dialog shape over [VisaDialog] chrome: an optional title, content, and a
 * confirm/neutral/dismiss button row. Every confirmation in the app uses this so the buttons sit
 * identically everywhere; a null label removes that button.
 */
@Composable
internal fun VoleAlertDialog(
    confirmLabel: String? = null,
    onConfirm: () -> Unit = {},
    confirmEnabled: Boolean = true,
    confirmDestructive: Boolean = false,
    neutralLabel: String? = null,
    onNeutral: (() -> Unit)? = null,
    title: String? = null,
    dismissLabel: String? = "Cancel",
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    VisaDialog(onDismissRequest = onDismiss) {
        Column(Modifier.padding(20.dp)) {
            if (title != null) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(12.dp))
            }
            content()
            Spacer(Modifier.height(20.dp))
            if (neutralLabel != null && confirmLabel != null) {
                Button(
                    onClick = onConfirm,
                    enabled = confirmEnabled,
                    colors = confirmButtonColors(confirmDestructive),
                    modifier = Modifier.fillMaxWidth()
                ) { Text(confirmLabel) }
                Spacer(Modifier.height(12.dp))
                NeutralDismissRow(neutralLabel, onNeutral, dismissLabel, onDismiss)
            } else if (neutralLabel != null) {
                NeutralDismissRow(neutralLabel, onNeutral, dismissLabel, onDismiss)
            } else {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (dismissLabel != null) {
                        OutlinedButton(onClick = onDismiss) { Text(dismissLabel) }
                    }
                    if (confirmLabel != null) {
                        Spacer(Modifier.width(12.dp))
                        Button(
                            onClick = onConfirm,
                            enabled = confirmEnabled,
                            colors = confirmButtonColors(confirmDestructive),
                        ) { Text(confirmLabel) }
                    }
                }
            }
        }
    }
}

@Composable
private fun confirmButtonColors(destructive: Boolean) =
    if (destructive) {
        ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
    } else {
        ButtonDefaults.buttonColors()
    }

/** The neutral + dismiss button row shared by the confirm/neutral variants above. */
@Composable
private fun NeutralDismissRow(
    neutralLabel: String,
    onNeutral: (() -> Unit)?,
    dismissLabel: String?,
    onDismiss: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(
            onClick = { onNeutral?.invoke() ?: onDismiss() },
            modifier = Modifier.weight(1f)
        ) { Text(neutralLabel) }
        if (dismissLabel != null) {
            OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text(dismissLabel) }
        }
    }
}

/**
 * Lists guard findings with their severity colour: a block, or a danger-coded warning that only
 * the trip-save flow treats as saveable, paints red. A null [confirmLabel] refuses the save.
 */
@Composable
internal fun GuardFindingsDialog(
    title: String,
    findings: List<GuardFinding>,
    confirmLabel: String?,
    dismissLabel: String,
    neutralLabel: String? = null,
    onConfirm: () -> Unit,
    onNeutral: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    VoleAlertDialog(
        title = title,
        confirmLabel = confirmLabel,
        onConfirm = onConfirm,
        neutralLabel = neutralLabel,
        onNeutral = onNeutral,
        dismissLabel = dismissLabel,
        onDismiss = onDismiss,
        content = {
            Column {
                findings.forEach { f ->
                    Text(
                        "${f.title}: ${f.message}",
                        style = MaterialTheme.typography.bodySmall,
                        color = severityColor(
                            if (f.severity == GuardSeverity.BLOCK || f.code in savableDangerCodes) Severity.BAD else Severity.WARN,
                            LocalEffectiveDarkTheme.current,
                        ),
                        modifier = Modifier.padding(vertical = 3.dp),
                    )
                }
            }
        },
    )
}
