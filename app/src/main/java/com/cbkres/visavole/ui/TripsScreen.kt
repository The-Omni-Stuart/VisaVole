package com.cbkres.visavole.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults

import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cbkres.visavole.data.StayRule
import com.cbkres.visavole.data.WorldData
import com.cbkres.visavole.domain.AccessModel
import com.cbkres.visavole.domain.AllowanceKind
import com.cbkres.visavole.domain.AllowanceSnapshot
import com.cbkres.visavole.domain.AllowanceStatus
import com.cbkres.visavole.domain.DocKind
import com.cbkres.visavole.domain.Document
import com.cbkres.visavole.domain.GuardAction
import com.cbkres.visavole.domain.GuardContext
import com.cbkres.visavole.domain.GuardEngine
import com.cbkres.visavole.domain.GuardFinding
import com.cbkres.visavole.domain.GuardMutation
import com.cbkres.visavole.domain.GuardResult
import com.cbkres.visavole.domain.GuardSeverity
import com.cbkres.visavole.domain.Trip
import com.cbkres.visavole.domain.TripModel
import com.cbkres.visavole.domain.TripStop
import com.cbkres.visavole.domain.TripStatus
import com.cbkres.visavole.domain.passportCountsByIso
import com.cbkres.visavole.domain.passportNumbers
import java.time.LocalDate
import java.util.UUID

@Composable
fun TripsScreen(
    vm: AccessViewModel,
    ready: AppState.Ready,
    scrollState: LazyListState,
    expandedTrips: List<String>,
    onToggleExpanded: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val today = ready.today
    var focusedKey by remember { mutableStateOf<String?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var editingTrip by remember { mutableStateOf<Trip?>(null) }
    var deletingTrip by remember { mutableStateOf<Trip?>(null) }
    var endingTrip by remember { mutableStateOf<Trip?>(null) }
    val primary = TripModel.primaryAllowance(ready.allowances, focusedKey)

    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        ScreenHeader("Trips", "Record your trips and track visa allowance balances.")
        AddButtonRow("Add trip") { showAdd = true }
        Spacer(Modifier.height(12.dp))
        val sections = ready.tripSections
        // One scroll plane under the add button: the allowance section is the first item of the
        // same list as the trip sections (its horizontal card switcher keeps its own scroll + haze).
        val tripsListState = scrollState
        val (tripsTop, tripsEnd) = tripsListState.hazeAlphas()
        HazeBox(tripsTop, tripsEnd, MaterialTheme.colorScheme.background, modifier = Modifier.weight(1f)) {
            LazyColumn(state = tripsListState, modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item(key = "allowances") {
                    AllowanceSection(
                        allowances = ready.allowances,
                        primary = primary,
                        focusedKey = focusedKey,
                        onFocus = { focusedKey = if (focusedKey == it) null else it },
                    )
                    Spacer(Modifier.height(4.dp))
                }
                if (sections.upcoming.isEmpty() && sections.current.isEmpty() && sections.previous.isEmpty()) {
                    item(key = "empty") { EmptyState("No trips yet. Add your first trip to start tracking allowances.") }
                } else {
                    if (sections.upcoming.isNotEmpty()) {
                        item(key = "header-upcoming") { SectionLabel("Upcoming") }
                        items(sections.upcoming, key = { it.id }) { trip ->
                            TripCard(trip, ready.world, ready.docs, ready.allowances, today, expanded = trip.id in expandedTrips, onExpand = { onToggleExpanded(trip.id) }, onEdit = { editingTrip = trip }, onEnd = {}, onDelete = { deletingTrip = trip })
                        }
                    }
                    if (sections.current.isNotEmpty()) {
                        item(key = "header-current") { SectionLabel("Current") }
                        items(sections.current, key = { it.id }) { trip ->
                            TripCard(trip, ready.world, ready.docs, ready.allowances, today, expanded = trip.id in expandedTrips, onExpand = { onToggleExpanded(trip.id) }, onEdit = { editingTrip = trip }, onEnd = { endingTrip = trip }, onDelete = { deletingTrip = trip })
                        }
                    }
                    if (sections.previous.isNotEmpty()) {
                        item(key = "header-previous") { SectionLabel("Previous") }
                        items(sections.previous, key = { it.id }) { trip ->
                            TripCard(trip, ready.world, ready.docs, ready.allowances, today, expanded = trip.id in expandedTrips, onExpand = { onToggleExpanded(trip.id) }, onEdit = { editingTrip = trip }, onEnd = {}, onDelete = { deletingTrip = trip })
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
                Button(
                    onClick = { vm.removeTrip(trip.id); deletingTrip = null },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deletingTrip = null }) { Text("Cancel") }
            },
        )
    }
    endingTrip?.let { trip ->
        EndTripDialog(
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
    expanded: Boolean,
    onExpand: () -> Unit,
    onEdit: () -> Unit,
    onEnd: () -> Unit,
    onDelete: () -> Unit,
) {
    val sortedStops = TripModel.sortedStops(trip.stops)
    val title = tripTitle(trip, world)
    val stopCountLabel = if (sortedStops.size > 1) "${sortedStops.size} stops" else null
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
    VisaListCard(
        onClick = onExpand,
        actions = {
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
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatusPill(trip.statusAt(today).label, MaterialTheme.colorScheme.onSurfaceVariant)
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
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(150)) + expandVertically(tween(150)),
            exit = fadeOut(tween(150)) + shrinkVertically(tween(150)),
        ) {
            Column {
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
            }
        }
        if (!expanded) {
            if (trip.note != null) {
                Text(trip.note!!, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (docLabels.isNotEmpty()) {
                Text(docLabels, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    VisaDialog(onDismissRequest = onDismiss) {
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
                } else if (neutralLabel != null) {
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

@Composable
private fun GapDialog(
    details: String,
    message: String,
    error: String?,
    onKeepGap: () -> Unit,
    onCancel: () -> Unit,
    onSplit: () -> Unit,
    onDismiss: () -> Unit,
) {
    VisaDialog(onDismissRequest = onDismiss) {
        Column(Modifier.padding(20.dp)) {
            Text("Gap between stops", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(12.dp))
                Text(details, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))
                Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                error?.let { err ->
                    Spacer(Modifier.height(8.dp))
                    Text(err, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
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

@Composable
private fun OngoingConfirmDialog(
    onAddDeparture: () -> Unit,
    onBack: () -> Unit,
    onDone: () -> Unit,
    onDismiss: () -> Unit,
) {
    VisaDialog(onDismissRequest = onDismiss) {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddTripDialog(
    vm: AccessViewModel,
    ready: AppState.Ready,
    initial: Trip?,
    onDismiss: () -> Unit,
) {
    val today = ready.today
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
    var gapError by remember { mutableStateOf<String?>(null) }
    var lastStopSnapshot by remember { mutableStateOf<StopSnapshot?>(null) }
    var saveWarnings by remember { mutableStateOf<Pair<List<GuardFinding>, Boolean>?>(null) }
    var pendingMutations by remember { mutableStateOf<List<GuardMutation>>(emptyList()) }
    var ongoingConflict by remember { mutableStateOf<OngoingConflict?>(null) }

    fun candidate(): Trip = Trip(currentTripId, stops, note.ifBlank { null })

    fun save(trip: Trip = candidate()) {
        if (isEditingExisting) vm.updateTrip(trip, pendingMutations) else vm.addTrip(trip, pendingMutations)
        pendingMutations = emptyList()
        onDismiss()
    }

    /** The shared guard context for this dialog: the effective primary passport is the
     *  tie-breaker document the `trip.passport.*` rules evaluate against. */
    fun evaluate(trip: Trip, tripsOverride: List<Trip> = ready.trips): GuardResult {
        val action = if (isEditingExisting) GuardAction.UpdateTrip(trip) else GuardAction.AddTrip(trip)
        return GuardEngine.evaluate(action, GuardContext.of(ready.docs, tripsOverride, ready.world, ready.starredDocId, today))
    }

    /** The ongoing-cap finding/mutation from the engine, if any (§13.4). */
    fun ongoingConflictFor(trip: Trip): OngoingConflict? {
        val end = evaluate(trip).mutations.firstOrNull { it is GuardMutation.EndTrip } as? GuardMutation.EndTrip
            ?: return null
        val previous = ready.trips.firstOrNull { it.id == end.tripId } ?: return null
        return OngoingConflict(trip, previous, end.onDate)
    }

    fun continueValidation(trip: Trip) {
        val conflict = ongoingConflictFor(trip)
        if (conflict != null) {
            ongoingConflict = conflict
            return
        }
        val result = evaluate(trip)
        pendingMutations = result.mutations
        if (result.warnings.isEmpty()) save(trip) else saveWarnings = result.warnings to true
    }

    fun resolveOngoingConflict(closePrevious: Boolean) {
        val conflict = ongoingConflict ?: return
        ongoingConflict = null
        if (closePrevious) {
            // Re-evaluate against the list where the previous trip is already closed: the cap
            // rule is then quiet and its mutation is gone. The EndTrip mutation only reaches the
            // ViewModel when this trip is actually saved (atomically with the candidate).
            val closedTrips = ready.trips.map {
                if (it.id == conflict.previous.id) TripModel.closeTrip(it, conflict.end) else it
            }
            val result = evaluate(conflict.candidate, closedTrips)
            if (result.blocks.isNotEmpty()) {
                pendingMutations = emptyList()
                saveWarnings = result.blocks to false
                return
            }
            pendingMutations = listOf(GuardMutation.EndTrip(conflict.previous.id, conflict.end))
            if (result.warnings.isEmpty()) save(conflict.candidate) else saveWarnings = result.warnings to true
        } else {
            val result = evaluate(conflict.candidate)
            pendingMutations = emptyList()
            val warnings = result.warnings.filter { it.code != "trip.ongoing.cap" }
            if (warnings.isEmpty()) save(conflict.candidate) else saveWarnings = warnings to true
        }
    }

    fun trySave() {
        val trip = candidate()
        val result = evaluate(trip)
        if (result.blocks.isNotEmpty()) {
            pendingMutations = emptyList()
            saveWarnings = result.blocks to false
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
        gapError = null
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
        val (leftStops, rightStops) = TripModel.splitStops(stops, idx, closeDate)
        val leftTrip = Trip(
            currentTripId,
            leftStops,
            if (isEditingExisting) (initial?.note ?: rightNote) else rightNote,
        )
        evaluate(leftTrip).blocks.firstOrNull()?.let { blockedBy ->
            gapError = "Can't split here: ${blockedBy.message}"
            return
        }
        if (isEditingExisting) vm.updateTrip(leftTrip) else vm.addTrip(leftTrip)
        stops = rightStops
        note = rightNote ?: ""
        currentTripId = UUID.randomUUID().toString()
        isEditingExisting = false
        editingStopId = null
        lastStopSnapshot = null
        gapIndex = null
        gapError = null
    }

    VisaDialog(
        onDismissRequest = onDismiss,
        width = 400.dp,
        maxHeight = (LocalConfiguration.current.screenHeightDp - 96).dp,
        dismissable = false,
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(if (isEditingExisting) "Edit trip" else "Add trip", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(12.dp))
                TripStopList(
                    stops = stops,
                    world = ready.world,
                    docs = ready.docs,
                    today = today,
                    maxHeight = (LocalConfiguration.current.screenHeightDp * 0.45f).dp,
                    onEdit = { editingStopId = it.id },
                    onRemove = { stop ->
                        stops = stops.filter { it.id != stop.id }
                        lastStopSnapshot = null
                        refreshGap()
                    },
                )
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
                if (stops.isEmpty()) {
                    Text(
                        "Add at least one stop to save the trip",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(12.dp))
                    Button(onClick = { trySave() }, enabled = stops.isNotEmpty()) {
                        Text(if (isEditingExisting) "Save" else "Add")
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
                trips = ready.trips,
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
            trips = ready.trips,
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
        AddStopWarningDialog(
            onConfirm = {
                showAddStopWarning = false
                val defaultArrival = TripModel.sortedStops(stops).lastOrNull()?.departure ?: today
                pendingNewStop = TripStop(UUID.randomUUID().toString(), "", defaultArrival, null)
            },
            onDismiss = { showAddStopWarning = false },
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
        OngoingConflictDialog(
            conflict = conflict,
            world = ready.world,
            onClosePrevious = { resolveOngoingConflict(true) },
            onKeepBoth = { resolveOngoingConflict(false) },
            onDismiss = { ongoingConflict = null },
        )
    }
    gapIndex?.let { idx ->
        val ordered = TripModel.sortedStops(stops)
        val prev = ordered.getOrNull(idx - 1)
        val next = ordered.getOrNull(idx)
        val departure = prev?.departure
        if (prev != null && next != null && departure != null) {
            val prevName = ready.world.countries[prev.countryIso2]?.name ?: prev.countryIso2
            val nextName = ready.world.countries[next.countryIso2]?.name ?: next.countryIso2
            GapDialog(
                details = "$departure → ${next.arrival}",
                message = "There is a gap between $prevName and $nextName. You can keep both stops in this trip, cancel the last stop change, or end this trip and start a new trip after the gap.",
                error = gapError,
                onKeepGap = {
                    lastStopSnapshot = null
                    gapIndex = null
                },
                onCancel = { cancelLastStopChange() },
                onSplit = { splitGap() },
                onDismiss = {
                    lastStopSnapshot = null
                    gapIndex = null
                },
            )
        }
    }
    saveWarnings?.let { (warnings, canSave) ->
        SaveWarningsDialog(
            warnings = warnings,
            canSave = canSave,
            onConfirm = {
                saveWarnings = null
                save()
            },
            onBack = {
                saveWarnings = null
                onDismiss()
            },
            onDismiss = { saveWarnings = null },
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

/** The scrollable list of stop rows inside the trip dialog. */
@Composable
private fun TripStopList(
    stops: List<TripStop>,
    world: WorldData,
    docs: List<Document>,
    today: LocalDate,
    maxHeight: Dp,
    onEdit: (TripStop) -> Unit,
    onRemove: (TripStop) -> Unit,
) {
    val stopsScroll = rememberScrollState()
    val (stopsTop, stopsEnd) = stopsScroll.hazeAlphas()
    HazeBox(
        stopsTop,
        stopsEnd,
        MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().heightIn(max = maxHeight),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .verticalScroll(stopsScroll),
        ) {
            if (stops.isEmpty()) {
                Text("No stops yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            stops.forEach { stop ->
                StopRow(
                    stop = stop,
                    world = world,
                    docs = docs,
                    today = today,
                    onClick = { onEdit(stop) },
                    onRemove = { onRemove(stop) },
                )
                Spacer(Modifier.height(6.dp))
            }
            if (stops.size > 1) {
                Text("Stops are kept in arrival order.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

/** Warns that adding a stop to an ongoing trip closes the current stop. */
@Composable
private fun AddStopWarningDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
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
private fun OngoingConflictDialog(
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
                "“${tripTitle(conflict.previous, world)}” is still open. Close it on ${conflict.end} and add this trip, or keep both.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

/** Lists guard findings for the candidate trip; blocks forbid saving, warnings allow it. */
@Composable
private fun SaveWarningsDialog(
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
        neutralLabel = if (canSave) null else "Back",
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
                        ),
                        modifier = Modifier.padding(vertical = 3.dp),
                    )
                }
            }
        },
    )
}

/** OutlinedButton that opens a date picker: calendar icon + one-line label. */
@Composable
private fun StopDateButton(text: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(Icons.Filled.DateRange, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StopEditorDialog(
    initial: TripStop,
    world: WorldData,
    docs: List<Document>,
    trips: List<Trip> = emptyList(),
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
    LaunchedEffect(stop, docs, carryDocumentId) {
        if (docTouched) return@LaunchedEffect
        val preferred = carryDocumentId?.takeIf { id -> docs.any { it.id == id } }
            ?: AccessModel.bestDocumentId(stop.countryIso2, docs, world, stop.arrival, primaryDocId, trips)
        if (preferred != stop.documentId) {
            stop = stop.copy(documentId = preferred)
        }
    }
    val rule = remember(stop.countryIso2, stop.arrival, docs) {
        world.stayRuleFor(stop.countryIso2, AccessModel.homeCountries(docs), stop.arrival)
    }
    val stopAccess = remember(stop.countryIso2, stop.arrival, docs, trips) {
        AccessModel.compute(docs, world, stop.arrival, trips)[stop.countryIso2]
    }
    val selectedDoc = docs.firstOrNull { it.id == stop.documentId }
    val countrySelected = stop.countryIso2 in world.countries
    val passportCounts = remember(docs) { passportCountsByIso(docs) }
    val passportNumbers = remember(docs) { passportNumbers(docs) }
    // Mirror the map's country-card: only offer documents that actually unlock the destination.
    val relevantDocs = remember(stop.countryIso2, stop.arrival, docs, trips) {
        if (stop.countryIso2 in world.countries) {
            AccessModel.relevantDocuments(stop.countryIso2, docs, world, stop.arrival, trips)
        } else {
            docs
        }
    }

    VisaDialog(
        onDismissRequest = onDismiss,
        width = 400.dp,
        maxHeight = (LocalConfiguration.current.screenHeightDp - 96).dp,
        dismissable = false,
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
                StopDateButton("Arrival: ${stop.arrival}", onClick = { showArrival = true })
                Spacer(Modifier.height(8.dp))
                StopDateButton("Departure: ${stop.departure ?: "open"}", onClick = { showDeparture = true })
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
                    relevantDocs.forEach { d ->
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
                if (!countrySelected) {
                    Text(
                        if (stop.countryIso2.isBlank()) "Pick a country to continue" else "Country not found",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                }
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

    if (showArrival) {
        VisaDatePickerDialog(
            initialMillis = stop.arrival.toUtcMillis(),
            confirmLabel = "Done",
            onConfirm = { date ->
                date?.let { stop = stop.copy(arrival = it) }
                showArrival = false
            },
            onDismiss = { showArrival = false },
        )
    }
    if (showDeparture) {
        VisaDatePickerDialog(
            initialMillis = stop.departure?.toUtcMillis() ?: stop.arrival.toUtcMillis(),
            confirmLabel = "Done",
            onConfirm = { date ->
                date?.let { stop = stop.copy(departure = it) }
                showDeparture = false
            },
            onDismiss = { showDeparture = false },
            secondaryLabel = if (isFinal) "Clear" else null,
            onSecondary = if (isFinal) ({ stop = stop.copy(departure = null); showDeparture = false }) else null,
        )
    }
}

@Composable
private fun EndTripDialog(today: LocalDate, onConfirm: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    VisaDatePickerDialog(
        initialMillis = today.toUtcMillis(),
        confirmLabel = "End",
        onConfirm = { date -> date?.let(onConfirm) },
        onDismiss = onDismiss,
    )
}
