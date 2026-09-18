package com.cbkres.visavole.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

// --- Date helpers (one canonical copy; previously duplicated in Trips/Documents/Onboarding) ---

internal fun Long.toUtcIsoDate(): String {
    val d = Instant.ofEpochMilli(this).atOffset(ZoneOffset.UTC).toLocalDate()
    return "%04d-%02d-%02d".format(d.year, d.monthValue, d.dayOfMonth)
}

internal fun LocalDate.toUtcMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

internal fun LocalDate.toUtcIsoDate(): String = "%04d-%02d-%02d".format(year, monthValue, dayOfMonth)

internal fun String.toUtcMillis(): Long? = runCatching { LocalDate.parse(this).toUtcMillis() }.getOrNull()

// --- Shared visual vocabulary: one place defines the style, screens just supply content ---

/**
 * Tonal status chip — the single source of the pill's shape, tonal fill (`color` at
 * [STATUS_CHIP_ALPHA]) and text colour. Compact card rows use the defaults; the country-card
 * header passes a larger [style]/[contentPadding]/[fontWeight]. [maxWidth] lets long labels wrap
 * instead of squeezing a weighted sibling; [bold] raises weight for terminal states (e.g. expired).
 */
@Composable
fun StatusPill(
    text: String,
    color: Color,
    bold: Boolean = false,
    maxWidth: Dp? = null,
    style: TextStyle = MaterialTheme.typography.labelSmall,
    contentPadding: PaddingValues = PaddingValues(horizontal = 8.dp, vertical = 3.dp),
    fontWeight: FontWeight? = null,
    modifier: Modifier = Modifier,
) {
    val weight = fontWeight ?: (if (bold) FontWeight.Bold else FontWeight.SemiBold)
    Surface(
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = STATUS_CHIP_ALPHA),
        modifier = modifier.then(maxWidth?.let { Modifier.widthIn(max = it) } ?: Modifier),
    ) {
        Text(
            text,
            color = color,
            fontWeight = weight,
            style = style,
            modifier = Modifier.padding(contentPadding),
        )
    }
}

/** Small overline heading above a list section (e.g. "Upcoming", "Active"). */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(top = 6.dp, bottom = 2.dp),
    )
}

/** Screen title + one-line subtitle, with the standard 12.dp gap before the first control. */
@Composable
fun ScreenHeader(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
    }
}

/** Right-aligned primary "Add …" button on its own row. [onClick] is last so call sites can use a
 *  trailing lambda: `AddButtonRow("Add trip") { showAdd = true }`. */
@Composable
fun AddButtonRow(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(modifier.fillMaxWidth()) {
        Spacer(Modifier.weight(1f))
        Button(onClick = onClick) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text(label)
        }
    }
}

/** Centred muted placeholder shown when a list has no items. */
@Composable
fun EmptyState(message: String, modifier: Modifier = Modifier) {
    Text(
        message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(vertical = 16.dp),
    )
}

/** A tappable country row: optional selection tick, name (weighted), optional ISO tag. */
@Composable
fun CountryRow(
    name: String,
    onClick: () -> Unit,
    iso: String? = null,
    selected: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Row(
        // minHeight keeps every country/search row on a ≥48dp touch target regardless of text style.
        modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick).padding(vertical = 10.dp, horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected) {
            Text("✓ ", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        iso?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/**
 * The shared list-card frame, derived from the trip card: an [ElevatedCard] with 16.dp padding,
 * a weighted content column (rows spaced by 8.dp) and a trailing vertical column of icon actions.
 * Screens supply their own content rows and action icons; the padding/spacing/shape live here.
 */
@Composable
fun VisaListCard(
    onClick: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val cardModifier = Modifier
        .fillMaxWidth()
        .then(onClick?.let { Modifier.clickable(onClick = it) } ?: Modifier)
    ElevatedCard(modifier = cardModifier) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                content()
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = 8.dp)) {
                actions()
            }
        }
    }
}

/** M3 date picker with the app's button vocabulary; [onConfirm] receives the chosen date (or
 *  null) and the caller is responsible for closing. [secondaryLabel] replaces "Cancel" (e.g. "Clear"). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VisaDatePickerDialog(
    initialMillis: Long,
    confirmLabel: String,
    onConfirm: (LocalDate?) -> Unit,
    onDismiss: () -> Unit,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    val state = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(state.selectedDateMillis?.toUtcIsoDate()?.let(LocalDate::parse)) }) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            if (secondaryLabel != null && onSecondary != null) {
                TextButton(onClick = onSecondary) { Text(secondaryLabel) }
            } else {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    ) {
        DatePicker(state = state)
    }
}
