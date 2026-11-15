package com.cbkres.visavole.ui.screens.documents

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.cbkres.visavole.domain.Country
import com.cbkres.visavole.domain.DocKind
import com.cbkres.visavole.domain.Document
import com.cbkres.visavole.domain.GuardFinding
import com.cbkres.visavole.ui.VisaDatePickerDialog
import com.cbkres.visavole.ui.screens.map.LocalEffectiveDarkTheme
import com.cbkres.visavole.ui.screens.map.Severity
import com.cbkres.visavole.ui.screens.map.severityColor
import com.cbkres.visavole.ui.toUtcIsoDate
import com.cbkres.visavole.ui.toUtcMillis
import com.cbkres.visavole.ui.VoleAlertDialog
import java.time.LocalDate

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
    // Bound the pickers to a coherent validity period: the start may not come after the end, and
    // a fresh expiry may not land in the past — but a stored expiry already in the past stays
    // selectable so it can be corrected or extended instead of only cleared.
    val today = todayMillis.toUtcIsoDate().let(LocalDate::parse)
    val validFrom = validFromDate?.toUtcIsoDate()?.let(LocalDate::parse)
    val expiry = expiryDate?.toUtcIsoDate()?.let(LocalDate::parse)
    OutlinedButton(
        onClick = { showValidFromPicker = true },
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(Icons.Filled.DateRange, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(validityLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
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
            // The validity start may not come after the (already set) end.
            maxDate = expiry,
        )
    }
    if (showValidToPicker) {
        val fallbackTo = maxOf(validFromDate ?: todayMillis, todayMillis)
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
            // The expiry may not precede the start; a stored expiry already in the past is floored
            // at itself so it can be corrected rather than only cleared.
            minDate = when {
                expiry != null && expiry.isBefore(today) -> expiry
                validFrom != null && validFrom.isAfter(today) -> validFrom
                else -> today
            },
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