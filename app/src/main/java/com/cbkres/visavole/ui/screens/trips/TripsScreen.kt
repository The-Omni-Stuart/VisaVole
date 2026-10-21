package com.cbkres.visavole.ui.screens.trips

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import com.cbkres.visavole.domain.AccessModel
import com.cbkres.visavole.domain.Trip
import com.cbkres.visavole.domain.TripModel
import com.cbkres.visavole.domain.passportCountsByIso
import com.cbkres.visavole.domain.passportNumbers
import com.cbkres.visavole.ui.AccessViewModel
import com.cbkres.visavole.ui.AddButtonRow
import com.cbkres.visavole.ui.AppState
import com.cbkres.visavole.ui.EmptyState
import com.cbkres.visavole.ui.HazeBox
import com.cbkres.visavole.ui.ScreenHeader
import com.cbkres.visavole.ui.SectionLabel
import com.cbkres.visavole.ui.animateItemToCenter
import com.cbkres.visavole.ui.components.StatusCard
import com.cbkres.visavole.ui.toUtcMillis
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate

@Composable
fun TripsScreen(
    vm: AccessViewModel,
    ready: AppState.Ready,
    scrollState: LazyListState,
    expandedTrips: List<String>,
    onToggleExpanded: (String) -> Unit,
    onTripDeleted: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val today = ready.today
    var focusedKey by remember { mutableStateOf<String?>(null) }
    var listViewportPx by remember { mutableStateOf(0) }
    var flashTrip by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var editingTrip by remember { mutableStateOf<Trip?>(null) }
    var deletingTrip by remember { mutableStateOf<Trip?>(null) }
    var endingTrip by remember { mutableStateOf<Trip?>(null) }
    val zoneAllowances = remember(ready.allowances) { ready.allowances.filter { !it.key.startsWith("doc:") } }
    val effectiveFocusKey = remember(zoneAllowances, focusedKey) {
        focusedKey?.takeIf { key -> zoneAllowances.any { it.key == key } }
    }
    val primary = remember(zoneAllowances, effectiveFocusKey) {
        TripModel.primaryAllowance(zoneAllowances, effectiveFocusKey)
    }
    val passportCounts = remember(ready.docs) { passportCountsByIso(ready.docs) }
    val passportNumbers = remember(ready.docs) { passportNumbers(ready.docs) }
    val validHomes = remember(ready.docs, ready.world, today) { AccessModel.validHomeCountries(ready.docs, ready.world, today) }

    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        ScreenHeader(
            "Trips",
            "Record your trips and track visa allowance balances."
        )

        val sections = ready.tripSections
        val tripIndex = remember(sections.upcoming, sections.current, sections.previous) {
            val m = HashMap<String, Int>()
            var i = 1
            for (section in listOf(sections.upcoming, sections.current, sections.previous)) {
                if (section.isEmpty()) continue
                i += 1
                section.forEachIndexed { n, t -> m[t.id] = i + n }
                i += section.size
            }
            m
        }

        val tripsListState = scrollState

        HazeBox(
            tripsListState,
            MaterialTheme.colorScheme.background,
            modifier = Modifier.weight(1f)
        ) {
            LazyColumn(
                state = tripsListState,
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { listViewportPx = it.height },
                contentPadding = PaddingValues(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val hasTrips =
                    sections.upcoming.isNotEmpty() ||
                            sections.current.isNotEmpty() ||
                            sections.previous.isNotEmpty()

                if (hasTrips) {
                    item(key = "allowances") {
                        StatusCard(
                            allowances = zoneAllowances,
                            primary = primary,
                            focusedKey = effectiveFocusKey,
                            onFocus = {
                                focusedKey = if (focusedKey == it) null else it
                            },
                            onRingClick = { snap ->
                                val trip =
                                    (snap.currentTripId ?: snap.relevantTripIds.firstOrNull())
                                        ?.let { id ->
                                            ready.trips.firstOrNull { trip ->
                                                trip.id == id
                                            }
                                        }

                                if (trip != null) {
                                    if (trip.id !in expandedTrips) {
                                        onToggleExpanded(trip.id)
                                    }

                                    val target = tripIndex[trip.id]

                                    if (target != null) {
                                        scope.launch {
                                            tripsListState.animateItemToCenter(
                                                target,
                                                listViewportPx
                                            )

                                            val flashCount =
                                                (flashTrip
                                                    ?.takeIf { it.first == trip.id }
                                                    ?.second ?: 0) + 1

                                            flashTrip = trip.id to flashCount

                                            delay(900)

                                            if (flashTrip == trip.id to flashCount) {
                                                flashTrip = null
                                            }
                                        }
                                    }
                                }
                            },
                        )

                        Spacer(Modifier.height(4.dp))
                    }

                    // TODO: Current layout: Upcoming, Current, Previous. Maybe change to tabs?
                    // TODO: pull out to generic display comp

                    if (sections.upcoming.isNotEmpty()) {
                        item(key = "header-upcoming") {
                            SectionLabel("Upcoming")
                        }

                        items(
                            items = sections.upcoming,
                            key = { it.id }
                        ) { trip ->
                            TripCard(
                                trip = trip,
                                world = ready.world,
                                docs = ready.docs,
                                allowances = ready.allowances,
                                passportCounts = passportCounts,
                                passportNumbers = passportNumbers,
                                validHomes = validHomes,
                                today = today,
                                expanded = trip.id in expandedTrips,
                                onExpand = {
                                    onToggleExpanded(trip.id)
                                },
                                onEdit = {
                                    editingTrip = trip
                                },
                                onEnd = {},
                                onDelete = {
                                    deletingTrip = trip
                                },
                                flashTrigger =
                                    flashTrip
                                        ?.takeIf { it.first == trip.id }
                                        ?.second
                                        ?: 0,
                            )
                        }
                    }

                    if (sections.current.isNotEmpty()) {
                        item(key = "header-current") {
                            SectionLabel("Current")
                        }

                        items(
                            items = sections.current,
                            key = { it.id }
                        ) { trip ->
                            TripCard(
                                trip = trip,
                                world = ready.world,
                                docs = ready.docs,
                                allowances = ready.allowances,
                                passportCounts = passportCounts,
                                passportNumbers = passportNumbers,
                                validHomes = validHomes,
                                today = today,
                                expanded = trip.id in expandedTrips,
                                onExpand = {
                                    onToggleExpanded(trip.id)
                                },
                                onEdit = {
                                    editingTrip = trip
                                },
                                onEnd = {
                                    endingTrip = trip
                                },
                                onDelete = {
                                    deletingTrip = trip
                                },
                                flashTrigger =
                                    flashTrip
                                        ?.takeIf { it.first == trip.id }
                                        ?.second
                                        ?: 0,
                            )
                        }
                    }

                    if (sections.previous.isNotEmpty()) {
                        item(key = "header-previous") {
                            SectionLabel("Previous")
                        }

                        items(
                            items = sections.previous,
                            key = { it.id }
                        ) { trip ->
                            TripCard(
                                trip = trip,
                                world = ready.world,
                                docs = ready.docs,
                                allowances = ready.allowances,
                                passportCounts = passportCounts,
                                passportNumbers = passportNumbers,
                                validHomes = validHomes,
                                today = today,
                                expanded = trip.id in expandedTrips,
                                onExpand = {
                                    onToggleExpanded(trip.id)
                                },
                                onEdit = {
                                    editingTrip = trip
                                },
                                onEnd = {},
                                onDelete = {
                                    deletingTrip = trip
                                },
                                flashTrigger =
                                    flashTrip
                                        ?.takeIf { it.first == trip.id }
                                        ?.second
                                        ?: 0,
                            )
                        }
                    }
                } else {
                    item(key = "empty") {
                        EmptyState(
                            "No trips yet. Add your first trip to start tracking allowances."
                        )
                    }
                }
            }
        }
        AddButtonRow("Add trip") { showAdd = true }
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
                    onClick = { vm.removeTrip(trip.id); onTripDeleted(trip.id); deletingTrip = null },
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

// TODO: Investing this
@Composable
private fun EndTripDialog(today: LocalDate, onConfirm: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    _root_ide_package_.com.cbkres.visavole.ui.VisaDatePickerDialog(
        initialMillis = today.toUtcMillis(),
        confirmLabel = "End",
        onConfirm = { date -> date?.let(onConfirm) },
        onDismiss = onDismiss,
    )
}
