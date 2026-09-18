package com.cbkres.visavole.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cbkres.visavole.domain.AllowanceKind
import com.cbkres.visavole.domain.AllowanceSnapshot
import com.cbkres.visavole.domain.AllowanceStatus
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
    trailing: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    // Clip the press ripple to the card's rounded shape so a tap doesn't flash a square.
    val shape = CardDefaults.elevatedShape
    val cardModifier = Modifier
        .fillMaxWidth()
        .clip(shape)
        .then(onClick?.let { Modifier.clickable(onClick = it) } ?: Modifier)
    ElevatedCard(shape = shape, modifier = cardModifier) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                content()
            }
            if (trailing != null) {
                Spacer(Modifier.width(8.dp))
                trailing()
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

/**
 * The app's snackbar host: the stock M3 snackbar is a flat full-width bar with square corners and
 * no margin, so this re-skins it with the card vocabulary — a tonal [surfaceContainerHigh] surface,
 * 16.dp rounded corners, horizontal margins, and a tonal elevation that reads in light and dark.
 */
@Composable
fun VisaSnackbarHost(hostState: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(
        hostState = hostState,
        modifier = modifier,
        snackbar = { data ->
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 2.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    Text(
                        data.visuals.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = 12.dp),
                    )
                    data.visuals.actionLabel?.let { label ->
                        TextButton(onClick = { hostState.currentSnackbarData?.dismiss() }) {
                            Text(label)
                        }
                    }
                }
            }
        },
    )
}

// --- Allowance ring: shared by the Trips tab (112.dp primary + switcher) and the Documents tab (72.dp per document) ---

internal fun allowanceFraction(a: AllowanceSnapshot): Float = when {
    a.totalDays != null && a.usedDays != null -> ((a.totalDays - a.usedDays).coerceAtLeast(0) / a.totalDays.toFloat()).coerceIn(0f, 1f)
    a.entryTotal != null && a.entryRemaining != null -> (a.entryRemaining.toFloat() / a.entryTotal).coerceIn(0f, 1f)
    a.remainingDays != null -> (a.remainingDays.coerceAtLeast(0) / 90f).coerceIn(0f, 1f)
    else -> 1f
}

internal fun ringColor(a: AllowanceSnapshot): Color {
    if (a.status == AllowanceStatus.UNKNOWN) return Color(0xFF64748B) // neutral slate — allowance unknown
    val fraction = allowanceFraction(a)
    val severity = when {
        a.overstayDays > 0 || a.status == AllowanceStatus.DANGER || a.status == AllowanceStatus.EXHAUSTED -> Severity.BAD
        fraction < 0.10f -> Severity.BAD
        fraction < 0.25f -> Severity.WARN
        fraction < 0.50f -> Severity.CAUTION
        else -> Severity.OK
    }
    return severityColor(severity)
}

internal fun allowanceSummary(a: AllowanceSnapshot): String = when {
    a.overstayDays > 0 -> if (a.overstayDays == 1) "1 day over" else "${a.overstayDays} days over"
    a.kind == AllowanceKind.ENTRY_COUNT -> if (a.entryRemaining == 0 && a.currentTripId != null) "In use"
    else "${a.entryRemaining ?: 0} of ${a.entryTotal ?: 0} entries left"
    a.kind == AllowanceKind.DOCUMENT_VALIDITY -> "Expires ${a.effectiveExpiry ?: "unknown"}"
    a.totalDays != null && a.remainingDays != null -> "${a.remainingDays} of ${a.totalDays} ${if (a.totalDays == 1) "day" else "days"} left"
    else -> a.subtitle ?: "No limit"
}

/**
 * The allowance donut: the remaining window (or remaining entries) drawn in the status colour,
 * the centre showing the remaining count and a label. [size] scales the donut — the stroke and
 * the centre text follow it (the 72.dp Documents variant drops to a smaller headline).
 */
@Composable
fun AllowanceRing(a: AllowanceSnapshot, size: Dp = 112.dp, modifier: Modifier = Modifier) {
    val color = ringColor(a)
    val fraction = allowanceFraction(a)
    val center = when {
        a.overstayDays > 0 -> a.overstayDays.toString()
        a.kind == AllowanceKind.ENTRY_COUNT -> if (a.entryRemaining == 0 && a.currentTripId != null) "In use" else (a.entryRemaining ?: 0).toString()
        a.kind == AllowanceKind.DOCUMENT_VALIDITY -> (a.remainingDays ?: 0).toString()
        else -> (a.remainingDays ?: 0).toString()
    }
    val centerLabel = when {
        a.overstayDays > 0 -> "days over"
        a.kind == AllowanceKind.ENTRY_COUNT -> "entries left"
        a.kind == AllowanceKind.DOCUMENT_VALIDITY -> "days to expiry"
        else -> "days left"
    }
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = (size / 11f).toPx()
            drawCircle(color = color.copy(alpha = 0.15f), style = Stroke(stroke))
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = 360f * fraction,
                useCenter = false,
                style = Stroke(stroke),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                center,
                style = if (size >= 96.dp) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = color,
            )
            Text(centerLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
