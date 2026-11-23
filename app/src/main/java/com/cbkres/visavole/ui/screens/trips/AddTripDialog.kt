package com.cbkres.visavole.ui.screens.trips

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalConfiguration
import com.cbkres.visavole.ui.DIALOG_CHROME_RESERVE
import com.cbkres.visavole.ui.LocalVisaDialogHeight
import com.cbkres.visavole.domain.GuardAction
import com.cbkres.visavole.domain.GuardContext
import com.cbkres.visavole.domain.GuardEngine
import com.cbkres.visavole.domain.GuardFinding
import com.cbkres.visavole.domain.GuardMutation
import com.cbkres.visavole.domain.GuardResult
import com.cbkres.visavole.domain.Trip
import com.cbkres.visavole.domain.TripModel
import com.cbkres.visavole.domain.TripStop
import com.cbkres.visavole.ui.AccessViewModel
import com.cbkres.visavole.ui.VisaDialog
import com.cbkres.visavole.ui.VoleAlertDialog
import com.cbkres.visavole.ui.GuardFindingsDialog
import com.cbkres.visavole.ui.app.AppState
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
/** A form dialog's usable height: measured from the window, with the screen's as the fallback. */
@Composable
private fun dialogUsableHeight(): Dp {
    val measured = LocalVisaDialogHeight.current
    if (measured != null) return measured
    return LocalConfiguration.current.screenHeightDp.dp - DIALOG_CHROME_RESERVE
}

/** The stop list's share of that height, so a long trip scrolls inside the dialog instead of off it. */
@Composable
internal fun dialogHeightForStops(): Dp = dialogUsableHeight() * 0.45f
@Composable
internal fun AddTripDialog(
    vm: AccessViewModel,
    ready: AppState.Ready,
    initial: Trip?,
    onDismiss: () -> Unit,
) {
    val today = ready.today
    var stops by rememberSaveable { mutableStateOf(TripModel.sortedStops(initial?.stops ?: emptyList())) }
    var note by rememberSaveable { mutableStateOf(initial?.note ?: "") }
    var currentTripId by rememberSaveable { mutableStateOf(initial?.id ?: UUID.randomUUID().toString()) }
    var isEditingExisting by rememberSaveable { mutableStateOf(initial != null) }
    var editingStopId by rememberSaveable { mutableStateOf<String?>(null) }
    var departureFocusStopId by remember { mutableStateOf<String?>(null) }
    var pendingNewStop by rememberSaveable { mutableStateOf<TripStop?>(null) }
    var showAddStopWarning by remember { mutableStateOf(false) }
    var showOngoingConfirm by remember { mutableStateOf(false) }
    var gapIndex by remember { mutableStateOf<Int?>(null) }
    var gapError by remember { mutableStateOf<String?>(null) }
    var lastStopSnapshot by remember { mutableStateOf<StopSnapshot?>(null) }
    var saveWarnings by remember { mutableStateOf<Pair<List<GuardFinding>, Boolean>?>(null) }
    var pendingMutations by remember { mutableStateOf<List<GuardMutation>>(emptyList()) }
    // The guard result behind pendingMutations: handed to the ViewModel on save so one save tap runs
    // the engine once instead of re-running it for the same candidate.
    var pendingResult by remember { mutableStateOf<GuardResult?>(null) }
    var ongoingConflict by remember { mutableStateOf<OngoingConflict?>(null) }
    var splitCommitted by remember { mutableStateOf(false) }
    var showSplitExitConfirm by remember { mutableStateOf(false) }
    // A split commits its first half to the ViewModel, whose publish trails the commit; while a
    // split is pending the dialog judges by its own list so later verdicts see the saved half.
    var splitTrips by remember { mutableStateOf<List<Trip>?>(null) }
    val judgedTrips = splitTrips ?: ready.trips

    fun candidate(): Trip = Trip(currentTripId, stops, note.ifBlank { null })

    // A split already committed its first half, so leaving is a discard, not a clean cancel.
    fun requestDismiss() {
        if (splitCommitted) showSplitExitConfirm = true else onDismiss()
    }

    fun save(trip: Trip = candidate()) {
        if (isEditingExisting) vm.updateTrip(trip, pendingMutations, pendingResult)
        else vm.addTrip(trip, pendingMutations, pendingResult)
        pendingMutations = emptyList()
        pendingResult = null
        onDismiss()
    }

    /** The shared guard context for this dialog: the effective primary passport is the
     *  tie-breaker document the `trip.passport.*` rules evaluate against. */
    fun evaluate(trip: Trip, tripsOverride: List<Trip> = judgedTrips): GuardResult {
        val action = if (isEditingExisting) GuardAction.UpdateTrip(trip) else GuardAction.AddTrip(trip)
        return GuardEngine.evaluate(action, GuardContext.of(ready.docs, tripsOverride, ready.world, ready.starredDocId, today))
    }

    /** The ongoing-cap finding/mutation from [result]; [trip] is evaluated only when no result is passed. */
    fun ongoingConflictFor(trip: Trip, result: GuardResult = evaluate(trip)): OngoingConflict? {
        val end = result.mutations.firstOrNull { it is GuardMutation.EndTrip } as? GuardMutation.EndTrip
            ?: return null
        val previous = judgedTrips.firstOrNull { it.id == end.tripId } ?: return null
        return OngoingConflict(trip, previous, end.onDate)
    }

    /** Mutations + warnings all come from [evaluated], so a save tap runs the engine exactly once. */
    fun continueValidation(trip: Trip, evaluated: GuardResult = evaluate(trip)) {
        val conflict = ongoingConflictFor(trip, evaluated)
        if (conflict != null) {
            ongoingConflict = conflict
            return
        }
        pendingResult = evaluated
        pendingMutations = evaluated.mutations
        if (evaluated.warnings.isEmpty()) save(trip) else saveWarnings = evaluated.warnings to true
    }

    fun resolveOngoingConflict(closePrevious: Boolean) {
        val conflict = ongoingConflict ?: return
        ongoingConflict = null
        if (closePrevious) {
            val closedTrips = judgedTrips.map {
                if (it.id == conflict.previous.id) TripModel.closeTrip(it, conflict.end) else it
            }
            val result = evaluate(conflict.candidate, closedTrips)
            if (result.blocks.isNotEmpty()) {
                pendingMutations = emptyList()
                pendingResult = null
                saveWarnings = result.blocks to false
                return
            }
            pendingResult = result
            // result.mutations is empty: it was evaluated with the previous trip already closed.
            pendingMutations = listOf(GuardMutation.EndTrip(conflict.previous.id, conflict.end))
            if (result.warnings.isEmpty()) save(conflict.candidate) else saveWarnings = result.warnings to true
        } else {
            val result = evaluate(conflict.candidate)
            pendingResult = result
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
            pendingResult = null
            saveWarnings = result.blocks to false
            return
        }
        val conflict = ongoingConflictFor(trip, result)
        if (conflict != null) {
            ongoingConflict = conflict
            return
        }
        if (trip.stops.any { it.departure == null } && initial?.isOpen != true) {
            // The confirm can't change the trip, so reuse this verdict instead of re-running the engine.
            pendingResult = result
            pendingMutations = result.mutations
            showOngoingConfirm = true
            return
        }
        continueValidation(trip, result)
    }

    fun confirmOngoing() {
        showOngoingConfirm = false
        continueValidation(candidate(), pendingResult ?: evaluate(candidate()))
    }

    fun addDepartureFromOngoing() {
        showOngoingConfirm = false
        val openStop = TripModel.sortedStops(stops).lastOrNull { it.departure == null }
        if (openStop != null) {
            departureFocusStopId = openStop.id
            editingStopId = openStop.id
        }
    }

    fun queueNewStop() {
        val defaultArrival = TripModel.sortedStops(stops).lastOrNull()?.departure ?: today
        pendingNewStop = TripStop(UUID.randomUUID().toString(), "", defaultArrival, null)
    }

    fun requestAddStop() {
        if (stops.any { it.departure == null }) {
            showAddStopWarning = true
        } else {
            queueNewStop()
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
        val splitResult = evaluate(leftTrip)
        splitResult.blocks.firstOrNull()?.let { blockedBy ->
            gapError = "Can't split here: ${blockedBy.message}"
            return
        }
        if (isEditingExisting) vm.updateTrip(leftTrip, evaluated = splitResult) else vm.addTrip(leftTrip, evaluated = splitResult)
        splitCommitted = true
        splitTrips = tripsAfterSplit(judgedTrips, leftTrip, replacedExisting = isEditingExisting)
        vm.snackMessage("First half saved — the rest continues as a new trip")
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
        onDismissRequest = { requestDismiss() },
        width = 400.dp,
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
                statuses = ready.statuses,
                maxHeight = dialogHeightForStops(),
                onEdit = { editingStopId = it.id },
                onRemove = { stop ->
                    lastStopSnapshot = StopSnapshot(stops)
                    stops = stops.filter { it.id != stop.id }
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
                OutlinedButton(onClick = { requestDismiss() }) { Text("Cancel") }
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
                trips = judgedTrips,
                isFinal = ordered.last().id == stop.id,
                carryDocumentId = ordered.getOrNull(stopIndex - 1)?.documentId,
                initiallyEditingDeparture = departureFocusStopId == stop.id,
                primaryDocId = ready.primaryDocId,
                statuses = ready.statuses,
                onDone = { updated ->
                    editingStopId = null
                    departureFocusStopId = null
                    applyStop(updated)
                },
                onRemove = {
                    lastStopSnapshot = StopSnapshot(stops)
                    stops = stops.filter { it.id != id }
                    editingStopId = null
                    departureFocusStopId = null
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
            trips = judgedTrips,
            isFinal = true,
            carryDocumentId = TripModel.sortedStops(stops).lastOrNull()?.documentId,
            primaryDocId = ready.primaryDocId,
            statuses = ready.statuses,
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
                queueNewStop()
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
            val canUndo = lastStopSnapshot != null
            GapDialog(
                details = "$departure → ${next.arrival}",
                message = if (canUndo)
                    "There is a gap between $prevName and $nextName. You can keep both stops in this trip, undo the last change, or end this trip and start a new trip after the gap."
                else
                    "There is a gap between $prevName and $nextName. You can keep both stops in this trip or end this trip and start a new trip after the gap.",
                error = gapError,
                canUndo = canUndo,
                onKeepGap = {
                    lastStopSnapshot = null
                    gapIndex = null
                },
                onUndo = { cancelLastStopChange() },
                onSplit = { splitGap() },
                onDismiss = {
                    lastStopSnapshot = null
                    gapIndex = null
                },
            )
        }
    }
    saveWarnings?.let { (warnings, canSave) ->
        GuardFindingsDialog(
            title = if (canSave) "Check trip" else "Cannot save yet",
            findings = warnings,
            confirmLabel = if (canSave) "Save anyway" else null,
            dismissLabel = "Edit",
            neutralLabel = if (canSave) null else "Discard",
            onConfirm = {
                saveWarnings = null
                save()
            },
            onNeutral = {
                saveWarnings = null
                onDismiss()
            },
            onDismiss = { saveWarnings = null },
        )
    }
    if (showSplitExitConfirm) {
        VoleAlertDialog(
            title = "Discard the new trip?",
            confirmLabel = "Discard",
            onConfirm = onDismiss,
            dismissLabel = "Keep editing",
            onDismiss = { showSplitExitConfirm = false },
            content = { Text("The first half was already saved. The stops still in the form will be lost.") },
        )
    }
}

private data class StopSnapshot(val stops: List<TripStop>)
