package com.cbkres.visavole.ui.screens.trips

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cbkres.visavole.data.WorldData
import com.cbkres.visavole.domain.AccessModel
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
import com.cbkres.visavole.domain.passportCountsByIso
import com.cbkres.visavole.domain.passportNumbers
import com.cbkres.visavole.ui.AccessViewModel
import com.cbkres.visavole.ui.HazeBox
import com.cbkres.visavole.ui.VisaDialog
import com.cbkres.visavole.ui.app.AppState
import com.cbkres.visavole.ui.hazeAlphas
import com.cbkres.visavole.ui.screens.map.LocalEffectiveDarkTheme
import com.cbkres.visavole.ui.screens.map.SAVABLE_DANGER_CODES
import com.cbkres.visavole.ui.screens.map.Severity
import com.cbkres.visavole.ui.screens.map.severityColor
import java.time.LocalDate
import java.util.UUID

// TODO: Refactor This too

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddTripDialog(
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
            Text(
                if (isEditingExisting) "Edit trip" else "Add trip",
                style = MaterialTheme.typography.titleLarge
            )
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
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
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
                Text(
                    "No stops yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
                Text(
                    "Stops are kept in arrival order.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun StopRow(
    stop: TripStop,
    world: WorldData,
    docs: List<Document>,
    today: LocalDate,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    val validHomes = remember(docs, world, today) { AccessModel.validHomeCountries(docs, world, today) }
    val rule = world.stayRuleFor(stop.countryIso2, validHomes, today)
    val passportCounts = remember(docs) { passportCountsByIso(docs) }
    val passportNumbers = remember(docs) { passportNumbers(docs) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
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

private data class StopSnapshot(val stops: List<TripStop>)

private data class OngoingConflict(val candidate: Trip, val previous: Trip, val end: LocalDate)

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
                "“${TripPresentation(conflict.previous, world)}” is still open. Close it on ${conflict.end} and add this trip, or keep both.",
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
                            LocalEffectiveDarkTheme.current,
                        ),
                        modifier = Modifier.padding(vertical = 3.dp),
                    )
                }
            }
        },
    )
}