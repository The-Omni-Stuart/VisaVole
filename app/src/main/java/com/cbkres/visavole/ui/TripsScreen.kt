package com.cbkres.visavole.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.cbkres.visavole.data.StayRule
import com.cbkres.visavole.data.WorldData
import com.cbkres.visavole.domain.AccessLevel
import com.cbkres.visavole.domain.AccessModel
import com.cbkres.visavole.domain.AllowanceKind
import com.cbkres.visavole.domain.AllowanceSnapshot
import com.cbkres.visavole.domain.AllowanceStatus
import com.cbkres.visavole.domain.DocKind
import com.cbkres.visavole.domain.Document
import com.cbkres.visavole.domain.Trip
import com.cbkres.visavole.domain.TripModel
import com.cbkres.visavole.domain.TripStop
import com.cbkres.visavole.domain.TripStatus
import com.cbkres.visavole.domain.TripWarning
import com.cbkres.visavole.domain.WarningSeverity
import com.cbkres.visavole.domain.entryType
import com.cbkres.visavole.domain.passportCountsByIso
import com.cbkres.visavole.domain.passportNumbers
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

private fun Long.toUtcIsoDate(): String {
    val d = Instant.ofEpochMilli(this).atOffset(ZoneOffset.UTC).toLocalDate()
    return "%04d-%02d-%02d".format(d.year, d.monthValue, d.dayOfMonth)
}

private fun LocalDate.toUtcMillis(): Long =
    atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

@Composable
fun TripsScreen(
    vm: AccessViewModel,
    ready: AppState.Ready,
    scrollState: LazyListState,
    modifier: Modifier = Modifier,
) {
    val today = remember { LocalDate.now(ZoneOffset.UTC) }
    var focusedKey by remember { mutableStateOf<String?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var editingTrip by remember { mutableStateOf<Trip?>(null) }
    var deletingTrip by remember { mutableStateOf<Trip?>(null) }
    var endingTrip by remember { mutableStateOf<Trip?>(null) }
    val primary = TripModel.primaryAllowance(ready.allowances, focusedKey)

    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        Text("Trips", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Record your trips and track visa allowance balances.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.weight(1f))
            Button(onClick = { showAdd = true }) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Add trip")
            }
        }
        Spacer(Modifier.height(12.dp))
        AllowanceSection(
            allowances = ready.allowances,
            primary = primary,
            focusedKey = focusedKey,
            onFocus = { focusedKey = if (focusedKey == it) null else it },
        )
        Spacer(Modifier.height(12.dp))
        val sections = ready.tripSections
        if (sections.upcoming.isEmpty() && sections.current.isEmpty() && sections.previous.isEmpty()) {
            Text(
                "No trips yet. Add your first trip to start tracking allowances.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 16.dp),
            )
        } else {
            val tripsListState = scrollState
            val (tripsTop, tripsEnd) = tripsListState.hazeAlphas()
            HazeBox(tripsTop, tripsEnd, MaterialTheme.colorScheme.background, modifier = Modifier.weight(1f)) {
                LazyColumn(state = tripsListState, modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (sections.upcoming.isNotEmpty()) {
                        item(key = "header-upcoming") { SectionLabel("Upcoming") }
                        items(sections.upcoming, key = { it.id }) { trip ->
                            TripCard(trip, ready.world, ready.docs, ready.allowances, today, onEdit = { editingTrip = trip }, onEnd = {}, onDelete = { deletingTrip = trip })
                        }
                    }
                    if (sections.current.isNotEmpty()) {
                        item(key = "header-current") { SectionLabel("Current") }
                        items(sections.current, key = { it.id }) { trip ->
                            TripCard(trip, ready.world, ready.docs, ready.allowances, today, onEdit = { editingTrip = trip }, onEnd = { endingTrip = trip }, onDelete = { deletingTrip = trip })
                        }
                    }
                    if (sections.previous.isNotEmpty()) {
                        item(key = "header-previous") { SectionLabel("Previous") }
                        items(sections.previous, key = { it.id }) { trip ->
                            TripCard(trip, ready.world, ready.docs, ready.allowances, today, onEdit = { editingTrip = trip }, onEnd = {}, onDelete = { deletingTrip = trip })
                        }
                    }
                }
            }
        }
    }

    if (showAdd) {
        AddTripDialog(vm, ready, null) { showAdd = false }
    }
    if (editingTrip != null) {
        AddTripDialog(vm, ready, editingTrip) { editingTrip = null }
    }
    deletingTrip?.let { trip ->
        AlertDialog(
            onDismissRequest = { deletingTrip = null },
            title = { Text("Delete trip") },
            text = { Text("Delete this trip and its stops? Allowance usage will be recalculated.") },
            confirmButton = {
                TextButton(onClick = { vm.removeTrip(trip.id); deletingTrip = null }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deletingTrip = null }) { Text("Cancel") }
            },
        )
    }
    endingTrip?.let { trip ->
        EndTripDialog(
            trip = trip,
            today = today,
            onConfirm = { departure ->
                vm.endTrip(trip.id, departure)
                endingTrip = null
            },
            onDismiss = { endingTrip = null },
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
    )
}

@Composable
private fun AllowanceSection(
    allowances: List<AllowanceSnapshot>,
    primary: AllowanceSnapshot?,
    focusedKey: String?,
    onFocus: (String) -> Unit,
) {
    if (allowances.isEmpty()) {
        Text("No allowances to track yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    primary?.let {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AllowanceRing(it)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(it.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                it.subtitle?.let { s -> Text(s, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Text(allowanceSummary(it), style = MaterialTheme.typography.labelMedium, color = ringColor(it))
            }
        }
        Spacer(Modifier.height(16.dp))
    }
    val switcherScroll = rememberScrollState()
    val (switcherStart, switcherEnd) = switcherScroll.hazeAlphas()
    HazeBox(switcherStart, switcherEnd, MaterialTheme.colorScheme.background, horizontal = true, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(switcherScroll), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            allowances.forEach { a ->
                AllowanceCard(a, focused = a.key == focusedKey, onClick = { onFocus(a.key) })
            }
        }
    }
}

private val STATUS_YELLOW = Color(0xFFCA8A04)

private fun zoneLabel(rule: StayRule?, fallback: String): String = when {
    rule == null -> fallback
    rule.zoneName.isNotBlank() -> rule.zoneName
    rule.countries.size == 1 -> fallback
    else -> rule.displayName
}

private fun allowanceFraction(a: AllowanceSnapshot): Float = when {
    a.totalDays != null && a.usedDays != null -> ((a.totalDays - a.usedDays).coerceAtLeast(0) / a.totalDays.toFloat()).coerceIn(0f, 1f)
    a.entryTotal != null && a.entryRemaining != null -> (a.entryRemaining.toFloat() / a.entryTotal).coerceIn(0f, 1f)
    a.remainingDays != null -> (a.remainingDays.coerceAtLeast(0) / 90f).coerceIn(0f, 1f)
    else -> 1f
}

private fun ringColor(a: AllowanceSnapshot): Color {
    if (a.status == AllowanceStatus.UNKNOWN) return Color(0xFF64748B)
    if (a.overstayDays > 0 || a.status == AllowanceStatus.DANGER || a.status == AllowanceStatus.EXHAUSTED) return STATUS_BAD
    val fraction = allowanceFraction(a)
    return when {
        fraction < 0.10f -> STATUS_BAD
        fraction < 0.25f -> STATUS_WARN
        fraction < 0.50f -> STATUS_YELLOW
        else -> STATUS_OK
    }
}

private fun allowanceSummary(a: AllowanceSnapshot): String = when {
    a.overstayDays > 0 -> if (a.overstayDays == 1) "1 day over" else "${a.overstayDays} days over"
    a.kind == AllowanceKind.ENTRY_COUNT -> if (a.entryRemaining == 0 && a.currentTripId != null) "In use"
    else "${a.entryRemaining ?: 0} of ${a.entryTotal ?: 0} entries left"
    a.kind == AllowanceKind.DOCUMENT_VALIDITY -> "Expires ${a.effectiveExpiry ?: "unknown"}"
    a.totalDays != null && a.remainingDays != null -> "${a.remainingDays} of ${a.totalDays} ${if (a.totalDays == 1) "day" else "days"} left"
    else -> a.subtitle ?: "No limit"
}

@Composable
private fun AllowanceRing(a: AllowanceSnapshot) {
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
    Box(Modifier.size(112.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 10.dp.toPx()
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
            Text(center, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = color)
            Text(centerLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AllowanceCard(a: AllowanceSnapshot, focused: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (focused) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .width(168.dp)
            .clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(a.title, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(allowanceSummary(a), style = MaterialTheme.typography.labelSmall, color = ringColor(a))
        }
    }
}

@Composable
private fun StatusPill(text: String, color: Color) {
    Surface(shape = RoundedCornerShape(50), color = color.copy(alpha = STATUS_CHIP_ALPHA)) {
        Text(
            text,
            color = color,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

private fun tripTitle(trip: Trip, world: WorldData): String {
    val names = TripModel.sortedStops(trip.stops).map { world.countries[it.countryIso2]?.name ?: it.countryIso2 }
    return when {
        names.size <= 1 -> names.firstOrNull().orEmpty()
        names.first() == names.last() -> if (names.toSet().size == 1) names.first() else "${names.first()} → … → ${names.last()}"
        else -> "${names.first()} → ${names.last()}"
    }
}

@Composable
private fun TripCard(
    trip: Trip,
    world: WorldData,
    docs: List<Document>,
    allowances: List<AllowanceSnapshot>,
    today: LocalDate,
    onEdit: () -> Unit,
    onEnd: () -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val sortedStops = TripModel.sortedStops(trip.stops)
    val title = tripTitle(trip, world)
    val stopCountLabel = if (sortedStops.size > 1) "${sortedStops.size} ${if (sortedStops.size == 1) "stop" else "stops"}" else null
    val passportCounts = remember(docs) { passportCountsByIso(docs) }
    val passportNumbers = remember(docs) { passportNumbers(docs) }
    val docLabels = sortedStops.mapNotNull { it.documentId }.distinct().joinToString(", ") { id ->
        docs.firstOrNull { it.id == id }?.displayLabel(passportCounts, passportNumbers) ?: id
    }
    val zones = sortedStops
        .map { stop ->
            val countryName = world.countries[stop.countryIso2]?.name ?: stop.countryIso2
            zoneLabel(world.stayRuleFor(stop.countryIso2, AccessModel.homeCountries(docs), today), countryName)
        }
        .distinct()
    val relatedAllowance = allowances.firstOrNull { it.currentTripId == trip.id }
        ?: allowances.firstOrNull { it.relevantTripIds.contains(trip.id) }
    val allowanceHint = when {
        relatedAllowance?.overstayDays != null && relatedAllowance.overstayDays > 0 -> if (relatedAllowance.overstayDays == 1) "1 day over" else "${relatedAllowance.overstayDays} days over"
        relatedAllowance?.remainingDays != null -> if (relatedAllowance.remainingDays == 1) "1 day left" else "${relatedAllowance.remainingDays} days left"
        relatedAllowance?.entryRemaining != null -> "${relatedAllowance.entryRemaining} ${if (relatedAllowance.entryRemaining == 1) "entry" else "entries"} left"
        else -> null
    }
    val allowanceHintColor = relatedAllowance?.let { ringColor(it) } ?: MaterialTheme.colorScheme.onSurfaceVariant
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = { expanded = !expanded })
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AssistChip(onClick = {}, label = { Text(trip.statusAt(today).label) }, modifier = Modifier.height(24.dp))
                    Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(
                        text = if (expanded) "−" else "+",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    buildString {
                        append(trip.firstArrival)
                        append(" → ")
                        append(trip.finalDeparture ?: "ongoing")
                        stopCountLabel?.let {
                            append(" · ")
                            append(it)
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (zones.isNotEmpty()) {
                        Text(
                            zones.joinToString(", "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = if (expanded) Int.MAX_VALUE else 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    allowanceHint?.let {
                        StatusPill(it, allowanceHintColor)
                    }
                }
                if (expanded) {
                    Spacer(Modifier.height(12.dp))
                    sortedStops.forEachIndexed { index, stop ->
                        val countryName = world.countries[stop.countryIso2]?.name ?: stop.countryIso2
                        val docLabel = stop.documentId?.let { id -> docs.firstOrNull { it.id == id }?.displayLabel(passportCounts, passportNumbers) ?: id }
                        Column(Modifier.padding(vertical = 6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("${index + 1}.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                Text(countryName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Text(
                                    zoneLabel(world.stayRuleFor(stop.countryIso2, AccessModel.homeCountries(docs), today), countryName),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(
                                "${stop.arrival} → ${stop.departure ?: "open"}${docLabel?.let { " · $it" } ?: " · No document"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (trip.note != null) {
                        Text(trip.note!!, style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    if (trip.note != null) {
                        Text(trip.note!!, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (docLabels.isNotEmpty()) {
                        Text(docLabels, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = 8.dp)) {
                IconButton(onClick = onEdit) {
                    Icon(Icons.Filled.Edit, contentDescription = "Edit")
                }
                if (trip.statusAt(today) == TripStatus.ONGOING) {
                    IconButton(onClick = onEnd) {
                        Icon(Icons.Filled.DateRange, contentDescription = "End trip")
                    }
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Close, contentDescription = "Delete")
                }
            }
        }
    }
}

private val TripStatus.label: String
    get() = when (this) {
        TripStatus.UPCOMING -> "Upcoming"
        TripStatus.ONGOING -> "Ongoing"
        TripStatus.PREVIOUS -> "Completed"
    }

private data class GapKey(val prevId: String, val nextId: String, val departure: LocalDate, val arrival: LocalDate)

private data class StopSnapshot(val stops: List<TripStop>)

private data class OngoingConflict(val candidate: Trip, val previous: Trip, val end: LocalDate)

@Composable
private fun TripAlertDialog(
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
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = MaterialTheme.shapes.large,
            tonalElevation = 0.dp,
            modifier = Modifier.width(340.dp),
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(12.dp))
                content()
                Spacer(Modifier.height(20.dp))
                if (neutralLabel != null && confirmLabel != null) {
                    Button(onClick = onConfirm, enabled = confirmEnabled, modifier = Modifier.fillMaxWidth()) { Text(confirmLabel) }
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { onNeutral?.invoke() ?: onDismiss() }, modifier = Modifier.weight(1f)) { Text(neutralLabel) }
                        OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text(dismissLabel) }
                    }
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = onDismiss) { Text(dismissLabel) }
                        if (confirmLabel != null) {
                            Spacer(Modifier.width(12.dp))
                            Button(onClick = onConfirm, enabled = confirmEnabled) { Text(confirmLabel) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GapDialog(
    details: String,
    message: String,
    onKeepGap: () -> Unit,
    onCancel: () -> Unit,
    onSplit: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = MaterialTheme.shapes.large,
            tonalElevation = 0.dp,
            modifier = Modifier.width(340.dp),
        ) {
            Column(Modifier.padding(20.dp)) {
                Text("Gap between stops", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(12.dp))
                Text(details, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))
                Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onKeepGap, modifier = Modifier.weight(1f)) {
                        Text("Keep gap")
                    }
                    OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                        Text("Cancel")
                    }
                }
                Spacer(Modifier.height(12.dp))
                Button(onClick = onSplit, modifier = Modifier.fillMaxWidth()) {
                    Text("End trip and start new trip")
                }
            }
        }
    }
}

@Composable
private fun OngoingConfirmDialog(
    onAddDeparture: () -> Unit,
    onBack: () -> Unit,
    onDone: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = MaterialTheme.shapes.large,
            tonalElevation = 0.dp,
            modifier = Modifier.width(340.dp),
        ) {
            Column(Modifier.padding(20.dp)) {
                Text("No final departure", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(12.dp))
                Text("This trip will be marked as ongoing.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(20.dp))
                OutlinedButton(onClick = onAddDeparture, modifier = Modifier.fillMaxWidth()) {
                    Text("Add departure")
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddTripDialog(
    vm: AccessViewModel,
    ready: AppState.Ready,
    initial: Trip?,
    onDismiss: () -> Unit,
) {
    val today = remember { LocalDate.now(ZoneOffset.UTC) }
    var stops by remember { mutableStateOf(TripModel.sortedStops(initial?.stops ?: emptyList())) }
    var note by remember { mutableStateOf(initial?.note ?: "") }
    var currentTripId by remember { mutableStateOf(initial?.id ?: UUID.randomUUID().toString()) }
    var isEditingExisting by remember { mutableStateOf(initial != null) }
    var editingStopId by remember { mutableStateOf<String?>(null) }
    var departureFocusStopId by remember { mutableStateOf<String?>(null) }
    var pendingNewStop by remember { mutableStateOf<TripStop?>(null) }
    var showAddStopWarning by remember { mutableStateOf(false) }
    var showOngoingConfirm by remember { mutableStateOf(false) }
    var gapIndex by remember { mutableStateOf<Int?>(null) }
    var acknowledgedGap by remember { mutableStateOf<GapKey?>(null) }
    var lastStopSnapshot by remember { mutableStateOf<StopSnapshot?>(null) }
    var saveWarnings by remember { mutableStateOf<Pair<List<TripWarning>, Boolean>?>(null) }
    var ongoingConflict by remember { mutableStateOf<OngoingConflict?>(null) }

    fun candidate(): Trip = Trip(currentTripId, stops, note.ifBlank { null })

    fun save(trip: Trip = candidate()) {
        if (isEditingExisting) vm.updateTrip(trip) else vm.addTrip(trip)
        onDismiss()
    }

    fun warningsFor(trip: Trip, tripsOverride: List<Trip> = ready.trips): List<TripWarning> {
        val others = tripsOverride.filter { it.id != trip.id }
        return TripModel.validateTrip(trip, ready.world, today) +
            TripModel.gapWarningsFor(trip, today) +
            TripModel.overlapWarningsFor(trip, others, ready.docs, ready.world, today) +
            TripModel.projectionWarningsFor(trip, others, ready.docs, ready.world, today)
    }

    fun ongoingConflictFor(trip: Trip): OngoingConflict? {
        if (trip.statusAt(today) != TripStatus.ONGOING) return null
        val previous = ready.trips
            .filter { it.id != trip.id && it.isOpen }
            .minByOrNull { it.firstArrival }
            ?: return null
        val end = when {
            trip.firstArrival.isBefore(previous.firstArrival) -> previous.firstArrival
            trip.firstArrival.isAfter(today) -> today
            else -> trip.firstArrival
        }
        return OngoingConflict(trip, previous, end)
    }

    fun continueValidation(trip: Trip) {
        val conflict = ongoingConflictFor(trip)
        if (conflict != null) {
            ongoingConflict = conflict
            return
        }
        val warnings = warningsFor(trip)
        if (warnings.isEmpty()) save(trip) else saveWarnings = warnings to true
    }

    fun resolveOngoingConflict(closePrevious: Boolean) {
        val conflict = ongoingConflict ?: return
        ongoingConflict = null
        if (closePrevious) {
            val closed = TripModel.closeTrip(conflict.previous, conflict.end)
            vm.updateTrip(closed)
            val updatedTrips = ready.trips.map { if (it.id == closed.id) closed else it }
            val warnings = warningsFor(conflict.candidate, updatedTrips)
            if (warnings.isEmpty()) save(conflict.candidate) else saveWarnings = warnings to true
        } else {
            val warnings = warningsFor(conflict.candidate)
            if (warnings.isEmpty()) save(conflict.candidate) else saveWarnings = warnings to true
        }
    }

    fun trySave() {
        val trip = candidate()
        val hard = TripModel.validateTrip(trip, ready.world, today).filter { it.severity == WarningSeverity.DANGER }
        if (hard.isNotEmpty()) {
            saveWarnings = hard to false
            return
        }
        val conflict = ongoingConflictFor(trip)
        if (conflict != null) {
            ongoingConflict = conflict
            return
        }
        if (trip.stops.any { it.departure == null } && initial?.isOpen != true) {
            showOngoingConfirm = true
            return
        }
        continueValidation(trip)
    }

    fun confirmOngoing() {
        showOngoingConfirm = false
        continueValidation(candidate())
    }

    fun addDepartureFromOngoing() {
        showOngoingConfirm = false
        val openStop = TripModel.sortedStops(stops).lastOrNull { it.departure == null }
        if (openStop != null) {
            departureFocusStopId = openStop.id
            editingStopId = openStop.id
        }
    }

    fun requestAddStop() {
        if (stops.any { it.departure == null }) {
            showAddStopWarning = true
        } else {
            val defaultArrival = TripModel.sortedStops(stops).lastOrNull()?.departure ?: today
            pendingNewStop = TripStop(UUID.randomUUID().toString(), "", defaultArrival, null)
        }
    }

    fun refreshGap() {
        acknowledgedGap = null
        val idx = TripModel.firstGapIndex(stops) ?: run {
            gapIndex = null
            return
        }
        val ordered = TripModel.sortedStops(stops)
        val prev = ordered.getOrNull(idx - 1)
        val next = ordered.getOrNull(idx)
        if (prev != null && next != null && prev.departure != null) {
            gapIndex = idx
        }
    }

    fun applyStop(updated: TripStop) {
        lastStopSnapshot = StopSnapshot(stops)
        stops = TripModel.sortedStops(stops.map { if (it.id == updated.id) updated else it })
        refreshGap()
    }

    fun cancelLastStopChange() {
        val snapshot = lastStopSnapshot
        lastStopSnapshot = null
        gapIndex = null
        acknowledgedGap = null
        if (snapshot != null) {
            stops = TripModel.sortedStops(snapshot.stops)
            refreshGap()
        }
    }

    fun splitGap() {
        val idx = gapIndex ?: return
        val ordered = TripModel.sortedStops(stops)
        val prev = ordered.getOrNull(idx - 1) ?: return
        val next = ordered.getOrNull(idx) ?: return
        val closeDate = prev.departure ?: return
        val rightNote = note.ifBlank { null }
        val leftTrip = Trip(
            currentTripId,
            ordered.take(idx).map { if (it.departure == null) it.copy(departure = closeDate) else it },
            if (isEditingExisting) (initial?.note ?: rightNote) else rightNote,
        )
        if (TripModel.validateTrip(leftTrip, ready.world, today).any { it.severity == WarningSeverity.DANGER }) {
            gapIndex = null
            return
        }
        if (isEditingExisting) vm.updateTrip(leftTrip) else vm.addTrip(leftTrip)
        stops = TripModel.sortedStops(ordered.drop(idx))
        note = rightNote ?: ""
        currentTripId = UUID.randomUUID().toString()
        isEditingExisting = false
        editingStopId = null
        lastStopSnapshot = null
        gapIndex = null
        acknowledgedGap = null
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = MaterialTheme.shapes.large,
            tonalElevation = 0.dp,
            modifier = Modifier
                .width(400.dp)
                .heightIn(max = (LocalConfiguration.current.screenHeightDp - 96).dp),
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(if (isEditingExisting) "Edit trip" else "Add trip", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(12.dp))
                val stopsScroll = rememberScrollState()
                val (stopsTop, stopsEnd) = stopsScroll.hazeAlphas()
                HazeBox(
                    stopsTop,
                    stopsEnd,
                    MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.45f).dp),
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.45f).dp)
                            .verticalScroll(stopsScroll),
                    ) {
                        if (stops.isEmpty()) {
                            Text("No stops yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        stops.forEach { stop ->
                            StopRow(
                                stop = stop,
                                world = ready.world,
                                docs = ready.docs,
                                today = today,
                                onClick = { editingStopId = stop.id },
                                onRemove = {
                                    stops = stops.filter { it.id != stop.id }
                                    lastStopSnapshot = null
                                    refreshGap()
                                },
                            )
                            Spacer(Modifier.height(6.dp))
                        }
                        if (stops.size > 1) {
                            Text("Stops are kept in arrival order.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(6.dp))
                        }
                    }
                }
                OutlinedButton(
                    onClick = { requestAddStop() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Add stop")
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Note (optional)") },
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(12.dp))
                    Button(onClick = { trySave() }, enabled = stops.isNotEmpty()) {
                        Text(if (isEditingExisting) "Save" else "Add")
                    }
                }
            }
        }
    }

    editingStopId?.let { id ->
        val ordered = TripModel.sortedStops(stops)
        val stopIndex = ordered.indexOfFirst { it.id == id }
        val stop = ordered.getOrNull(stopIndex)
        if (stop != null) {
            StopEditorDialog(
                initial = stop,
                world = ready.world,
                docs = ready.docs,
                isFinal = ordered.last().id == stop.id,
                carryDocumentId = ordered.getOrNull(stopIndex - 1)?.documentId,
                initiallyEditingDeparture = departureFocusStopId == stop.id,
                primaryDocId = ready.primaryDocId,
                onDone = { updated ->
                    editingStopId = null
                    departureFocusStopId = null
                    applyStop(updated)
                },
                onRemove = {
                    stops = stops.filter { it.id != id }
                    editingStopId = null
                    departureFocusStopId = null
                    lastStopSnapshot = null
                    refreshGap()
                },
                onDismiss = {
                    editingStopId = null
                    departureFocusStopId = null
                },
            )
        }
    }
    pendingNewStop?.let { stop ->
        StopEditorDialog(
            initial = stop,
            world = ready.world,
            docs = ready.docs,
            isFinal = true,
            carryDocumentId = TripModel.sortedStops(stops).lastOrNull()?.documentId,
            primaryDocId = ready.primaryDocId,
            onDone = { updated ->
                pendingNewStop = null
                lastStopSnapshot = StopSnapshot(stops)
                val closed = stops.map { stop ->
                    if (stop.departure == null) stop.copy(departure = maxOf(stop.arrival, updated.arrival)) else stop
                }
                stops = TripModel.sortedStops(closed + updated)
                refreshGap()
            },
            onDismiss = { pendingNewStop = null },
        )
    }
    if (showAddStopWarning) {
        TripAlertDialog(
            title = "Add stop to ongoing trip",
            confirmLabel = "Add stop",
            onConfirm = {
                showAddStopWarning = false
                val defaultArrival = TripModel.sortedStops(stops).lastOrNull()?.departure ?: today
                pendingNewStop = TripStop(UUID.randomUUID().toString(), "", defaultArrival, null)
            },
            onDismiss = { showAddStopWarning = false },
            content = { Text("This trip is ongoing. Adding a new stop will close the current stop. A departure date will be added automatically and can be edited afterwards.") },
        )
    }
    if (showOngoingConfirm) {
        OngoingConfirmDialog(
            onAddDeparture = { addDepartureFromOngoing() },
            onBack = { showOngoingConfirm = false },
            onDone = { confirmOngoing() },
            onDismiss = { showOngoingConfirm = false },
        )
    }
    ongoingConflict?.let { conflict ->
        TripAlertDialog(
            title = "Another trip is ongoing",
            confirmLabel = "Close previous & add",
            onConfirm = { resolveOngoingConflict(true) },
            neutralLabel = "Keep both",
            onNeutral = { resolveOngoingConflict(false) },
            dismissLabel = "Cancel",
            onDismiss = { ongoingConflict = null },
            content = {
                Text(
                    "“${tripTitle(conflict.previous, ready.world)}” is still open. Close it on ${conflict.end} and add this trip, or keep both.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )
    }
    gapIndex?.let { idx ->
        val ordered = TripModel.sortedStops(stops)
        val prev = ordered.getOrNull(idx - 1)
        val next = ordered.getOrNull(idx)
        val departure = prev?.departure
        if (prev != null && next != null && departure != null) {
            val key = GapKey(prev.id, next.id, departure, next.arrival)
            val prevName = ready.world.countries[prev.countryIso2]?.name ?: prev.countryIso2
            val nextName = ready.world.countries[next.countryIso2]?.name ?: next.countryIso2
            GapDialog(
                details = "$departure → ${next.arrival}",
                message = "There is a gap between $prevName and $nextName. You can keep both stops in this trip, cancel the last stop change, or end this trip and start a new trip after the gap.",
                onKeepGap = {
                    acknowledgedGap = key
                    lastStopSnapshot = null
                    gapIndex = null
                },
                onCancel = { cancelLastStopChange() },
                onSplit = { splitGap() },
                onDismiss = {
                    acknowledgedGap = key
                    lastStopSnapshot = null
                    gapIndex = null
                },
            )
        }
    }
    saveWarnings?.let { (warnings, canSave) ->
        TripAlertDialog(
            title = if (canSave) "Check trip" else "Cannot save yet",
            confirmLabel = if (canSave) "Save anyway" else null,
            confirmEnabled = canSave,
            onConfirm = {
                saveWarnings = null
                save()
            },
            neutralLabel = if (canSave) null else "Back",
            onNeutral = {
                saveWarnings = null
                onDismiss()
            },
            dismissLabel = "Edit",
            onDismiss = { saveWarnings = null },
            content = {
                Column {
                    warnings.forEach { w ->
                        Text(
                            "${w.title}: ${w.message}",
                            style = MaterialTheme.typography.bodySmall,
                            color = when (w.severity) {
                                WarningSeverity.DANGER -> STATUS_BAD
                                WarningSeverity.WARNING -> STATUS_WARN
                                WarningSeverity.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.padding(vertical = 3.dp),
                        )
                    }
                }
            },
        )
    }
}

/** Display label for [doc]; when the holder keeps several passports of one nationality, append a
 * stable "(N)" (e.g. "Passport · United Kingdom (2)") so it's clear which passport a stop refers to. */
private fun Document.displayLabel(counts: Map<String, Int>, numbers: Map<String, Int>): String {
    val iso = (kind as? DocKind.Passport)?.iso2 ?: return label
    val n = numbers[id] ?: return label
    return if ((counts[iso] ?: 0) >= 2) "$label  ($n)" else label
}

/** Width-safe [displayLabel] for the collapsed preview field: a passport drops the "Passport · "
 * prefix so the nationality and "(N)" number both fit (e.g. "United Kingdom (1)"). */
private fun Document.compactDisplayLabel(counts: Map<String, Int>, numbers: Map<String, Int>): String =
    displayLabel(counts, numbers).removePrefix("Passport · ")

@Composable
private fun StopRow(
    stop: TripStop,
    world: WorldData,
    docs: List<Document>,
    today: LocalDate,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    val rule = world.stayRuleFor(stop.countryIso2, AccessModel.homeCountries(docs), today)
    val passportCounts = remember(docs) { passportCountsByIso(docs) }
    val passportNumbers = remember(docs) { passportNumbers(docs) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(world.countries[stop.countryIso2]?.name ?: stop.countryIso2, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "${stop.arrival} → ${stop.departure ?: "open"}${docs.firstOrNull { it.id == stop.documentId }?.let { " · ${it.displayLabel(passportCounts, passportNumbers)}" } ?: ""}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    rule?.let { "Counts toward: ${it.summary()}" } ?: "No shared stay rule",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Filled.Close, contentDescription = "Remove stop")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StopEditorDialog(
    initial: TripStop,
    world: WorldData,
    docs: List<Document>,
    isFinal: Boolean,
    onDone: (TripStop) -> Unit,
    onRemove: (() -> Unit)? = null,
    onDismiss: () -> Unit,
    carryDocumentId: String? = null,
    initiallyEditingDeparture: Boolean = false,
    primaryDocId: String? = null,
) {
    var stop by remember { mutableStateOf(initial) }
    var countryQuery by remember { mutableStateOf("") }
    var docTouched by remember { mutableStateOf(initial.documentId != null) }
    var showArrival by remember { mutableStateOf(false) }
    var showDeparture by remember { mutableStateOf(initiallyEditingDeparture) }
    val todayMillis = remember { LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }
    LaunchedEffect(stop, docs, carryDocumentId) {
        if (docTouched) return@LaunchedEffect
        val preferred = carryDocumentId?.takeIf { id -> docs.any { it.id == id } }
            ?: AccessModel.bestDocumentId(stop.countryIso2, docs, world, stop.arrival, primaryDocId)
        if (preferred != stop.documentId) {
            stop = stop.copy(documentId = preferred)
        }
    }
    val rule = remember(stop.countryIso2, stop.arrival, docs) {
        world.stayRuleFor(stop.countryIso2, AccessModel.homeCountries(docs), stop.arrival)
    }
    val stopAccess = remember(stop.countryIso2, stop.arrival, docs) {
        AccessModel.compute(docs, world, stop.arrival)[stop.countryIso2]
    }
    val selectedDoc = docs.firstOrNull { it.id == stop.documentId }
    val countrySelected = stop.countryIso2 in world.countries
    val passportCounts = remember(docs) { passportCountsByIso(docs) }
    val passportNumbers = remember(docs) { passportNumbers(docs) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = MaterialTheme.shapes.large,
            tonalElevation = 0.dp,
            modifier = Modifier
                .width(400.dp)
                .heightIn(max = (LocalConfiguration.current.screenHeightDp - 96).dp),
        ) {
            val stopDialogScroll = rememberScrollState()
            val (stopTop, stopEnd) = stopDialogScroll.hazeAlphas()
            HazeBox(stopTop, stopEnd, MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp - 96).dp)) {
                Column(Modifier.padding(20.dp).verticalScroll(stopDialogScroll)) {
                    Text(if (onRemove == null) "Add stop" else "Edit stop", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(16.dp))
                CountryPicker(
                    countries = world.countries,
                    query = countryQuery,
                    onQuery = { countryQuery = it },
                    selected = setOf(stop.countryIso2),
                    onToggle = { iso -> stop = stop.copy(countryIso2 = iso) },
                    label = "Country",
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { showArrival = true },
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.DateRange, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Arrival: ${stop.arrival}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { showDeparture = true },
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.DateRange, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Departure: ${stop.departure ?: "open"}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(8.dp))
                SelectionField(label = "Document", value = selectedDoc?.compactDisplayLabel(passportCounts, passportNumbers) ?: "None") { onSelected ->
                    DropdownMenuItem(
                        text = { Text("None") },
                        onClick = {
                            stop = stop.copy(documentId = null)
                            docTouched = true
                            onSelected()
                        },
                    )
                    docs.forEach { d ->
                        DropdownMenuItem(
                            text = { Text(d.displayLabel(passportCounts, passportNumbers)) },
                            onClick = {
                                stop = stop.copy(documentId = d.id)
                                docTouched = true
                                onSelected()
                            },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Counts toward: ${rule?.summary() ?: "No shared stay rule found"}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (TripModel.isShortStaySuppressed(stopAccess)) {
                    Text(
                        "Days here won't count against short-stay allowance.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    if (onRemove != null) {
                        TextButton(onClick = onRemove) { Text("Remove") }
                        Spacer(Modifier.width(8.dp))
                    }
                    OutlinedButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(12.dp))
                    Button(onClick = { onDone(stop); onDismiss() }, enabled = countrySelected) { Text("Done") }
                }
            }
            }
        }
    }

    if (showArrival) {
        val state = rememberDatePickerState(initialSelectedDateMillis = stop.arrival.toUtcMillis())
        DatePickerDialog(
            onDismissRequest = { showArrival = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { stop = stop.copy(arrival = it.toUtcIsoDate().let(LocalDate::parse)) }
                    showArrival = false
                }) { Text("Done") }
            },
            dismissButton = { TextButton(onClick = { showArrival = false }) { Text("Cancel") } },
        ) {
            DatePicker(state = state)
        }
    }
    if (showDeparture) {
        val state = rememberDatePickerState(initialSelectedDateMillis = stop.departure?.toUtcMillis() ?: stop.arrival.toUtcMillis())
        DatePickerDialog(
            onDismissRequest = { showDeparture = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { stop = stop.copy(departure = it.toUtcIsoDate().let(LocalDate::parse)) }
                    showDeparture = false
                }) { Text("Done") }
            },
            dismissButton = {
                if (isFinal) {
                    TextButton(onClick = {
                        stop = stop.copy(departure = null)
                        showDeparture = false
                    }) { Text("Clear") }
                }
            },
        ) {
            DatePicker(state = state)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EndTripDialog(trip: Trip, today: LocalDate, onConfirm: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val todayMillis = remember { today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }
    val state = rememberDatePickerState(initialSelectedDateMillis = todayMillis)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { onConfirm(it.toUtcIsoDate().let(LocalDate::parse)) }
            }) { Text("End") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    ) {
        DatePicker(state = state)
    }
}
