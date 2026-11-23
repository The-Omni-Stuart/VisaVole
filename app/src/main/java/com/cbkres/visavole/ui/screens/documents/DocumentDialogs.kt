package com.cbkres.visavole.ui.screens.documents

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.cbkres.visavole.domain.Country
import com.cbkres.visavole.domain.DocKind
import com.cbkres.visavole.domain.Document
import com.cbkres.visavole.domain.GuardFinding
import com.cbkres.visavole.ui.DateFieldButton
import com.cbkres.visavole.ui.VisaDatePickerDialog
import com.cbkres.visavole.ui.screens.map.LocalEffectiveDarkTheme
import com.cbkres.visavole.ui.screens.map.Severity
import com.cbkres.visavole.ui.screens.map.severityColor
import com.cbkres.visavole.ui.toUtcIsoDate
import com.cbkres.visavole.ui.toUtcLocalDate
import com.cbkres.visavole.ui.toUtcMillis
import com.cbkres.visavole.ui.VoleAlertDialog

/** The validity-period button and its two chained pickers (valid-from → expiry). */
@Composable
internal fun ValidityPeriodField(
    validFromDate: Long?,
    expiryDate: Long?,
    todayMillis: Long,
    reopenToken: Int = 0,
    reopenPicker: Int = 1,
    onValidFrom: (Long?) -> Unit,
    onExpiry: (Long?) -> Unit,
) {
    val validityLabel = when {
        validFromDate != null || expiryDate != null ->
            "Validity: ${validFromDate?.let { it.toUtcIsoDate() } ?: "…"} → ${expiryDate?.let { it.toUtcIsoDate() } ?: "…"}"
        else -> "Validity period (optional)"
    }
    var showValidFromPicker by remember { mutableStateOf(false) }
    var showValidToPicker by remember { mutableStateOf(false) }
    // when the parent bumps [reopenToken], open the requested picker so a half-set validity
    // can be completed without hunting for the field again.
    LaunchedEffect(reopenToken) {
        if (reopenToken != 0) {
            showValidFromPicker = reopenPicker == 1
            showValidToPicker = reopenPicker != 1
        }
    }
    // The two dates must leave at least one usable day; the expiry may be in the past.
    val validFrom = validFromDate?.toUtcLocalDate()
    val expiry = expiryDate?.toUtcLocalDate()
    DateFieldButton(validityLabel) { showValidFromPicker = true }
    if (showValidFromPicker) {
        VisaDatePickerDialog(
            initialMillis = validFromDate ?: todayMillis,
            confirmLabel = "Next",
            onConfirm = { date ->
                onValidFrom(date?.toUtcMillis())
                showValidFromPicker = false
                showValidToPicker = true
            },
            onDismiss = { showValidFromPicker = false; showValidToPicker = false },
            secondaryLabel = "Clear",
            onSecondary = {
                onValidFrom(null)
                showValidFromPicker = false
                showValidToPicker = true
            },
            maxDate = expiry?.minusDays(1),
        )
    }
    if (showValidToPicker) {
        val fallbackTo = validFrom?.plusDays(1)?.toUtcMillis() ?: todayMillis
        VisaDatePickerDialog(
            initialMillis = expiryDate ?: fallbackTo,
            confirmLabel = "Done",
            onConfirm = { date ->
                onExpiry(date?.toUtcMillis())
                showValidToPicker = false
            },
            onDismiss = { showValidToPicker = false },
            secondaryLabel = "Clear",
            onSecondary = {
                onExpiry(null)
                showValidToPicker = false
            },
            minDate = validFrom?.plusDays(1),
        )
    }
}

/** Confirms saving a visa that covers several countries at once. */
@Composable
internal fun MultiCountryConfirmDialog(
    doc: Document,
    countries: Map<String, Country>,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val names = (doc.kind as? DocKind.Custom)?.countries?.sortedBy { countries[it]?.name ?: it } ?: emptyList()
    VoleAlertDialog(
        confirmLabel = "Save anyway",
        onConfirm = onConfirm,
        dismissLabel = "Back",
        onDismiss = onDismiss,
        content = {
            val warn = severityColor(
                Severity.WARN,
                LocalEffectiveDarkTheme.current
            )
            Text(
                buildAnnotatedString {
                    append("You have multiple countries selected for this visa: ")
                    names.forEachIndexed { index, iso ->
                        withStyle(SpanStyle(color = warn, fontWeight = FontWeight.SemiBold)) {
                            append(countries[iso]?.name ?: iso)
                        }
                        if (index != names.lastIndex) append(", ")
                    }
                    append(". Are you sure you want to save?")
                },
            )
        },
    )
}

/** Confirms an accompanying mutation (e.g. expiring a superseded document) that a save implies. */
@Composable
internal fun PendingMutationDialog(
    warn: GuardFinding?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    VoleAlertDialog(
        title = warn?.title ?: "Heads up",
        confirmLabel = "Confirm",
        onConfirm = onConfirm,
        dismissLabel = "Back",
        onDismiss = onDismiss,
        content = { Text(warn?.message ?: "This action will also change another of your documents.") },
    )
}

/** Shows a blocking guard finding; when the block is a duplicate, offers to jump to the existing doc. */
@Composable
internal fun BlockedFindingDialog(
    finding: GuardFinding,
    hasDuplicate: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    onDiscard: () -> Unit,
) {
    VoleAlertDialog(
        // Back returns to the half-filled form; leaving the editor is an explicitly labelled act.
        title = finding.title,
        confirmLabel = if (hasDuplicate) "Edit existing" else "Got it",
        onConfirm = onConfirm,
        neutralLabel = if (hasDuplicate) "Discard" else null,
        onNeutral = onDiscard,
        dismissLabel = null,
        onDismiss = onDismiss,
        content = { Text(finding.message) },
    )
}