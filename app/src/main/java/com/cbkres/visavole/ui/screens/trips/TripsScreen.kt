package com.cbkres.visavole.ui.screens.trips

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import com.cbkres.visavole.domain.AccessModel
import com.cbkres.visavole.domain.Trip
import com.cbkres.visavole.domain.TripStatus
import com.cbkres.visavole.domain.passportCountsByIso
import com.cbkres.visavole.domain.passportNumbers
import com.cbkres.visavole.ui.AccessViewModel
import com.cbkres.visavole.ui.HazeBox
import com.cbkres.visavole.ui.VisaDatePickerDialog
import com.cbkres.visavole.ui.animateItemToCenter
import com.cbkres.visavole.ui.sectionedIndices
import com.cbkres.visavole.ui.app.AppState
import com.cbkres.visavole.ui.components.statuscard.AllowanceFocus
import com.cbkres.visavole.ui.components.statuscard.FlashTrigger
import com.cbkres.visavole.ui.components.statuscard.StatusCard
import com.cbkres.visavole.ui.screens.AddFab
import com.cbkres.visavole.ui.screens.EmptyState
import com.cbkres.visavole.ui.screens.ScreenHeader
import com.cbkres.visavole.ui.screens.SectionLabel
import com.cbkres.visavole.ui.toUtcMillis
import com.cbkres.visavole.ui.VoleAlertDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.time.Duration.Companion.milliseconds

@Composable
internal fun TripsScreen(
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
    // Shared tab-top display state: switcher focus (with the toggle + dead-key rules) and the
    // ring-tap flash counters, one holder pair per screen (the same pair the Documents tab uses).
    val focus = remember { AllowanceFocus() }
    val flashes = remember { FlashTrigger() }
    var listViewportPx by remember { mutableIntStateOf(0) }
    var showAdd by rememberSaveable { mutableStateOf(false) }
    var editingTrip by rememberSaveable { mutableStateOf<Trip?>(null) }
    var deletingTrip by remember { mutableStateOf<Trip?>(null) }
    var endingTrip by remember { mutableStateOf<Trip?>(null) }
    val zoneAllowances = remember(ready.allowances) { ready.allowances.filter { !it.key.startsWith("doc:") } }
    val passportCounts = remember(ready.docs) { passportCountsByIso(ready.docs) }
    val passportNumbers = remember(ready.docs) { passportNumbers(ready.docs) }
    val validHomes = remember(ready.docs, ready.statuses) {
        AccessModel.validHomeCountries(ready.docs, ready.world, today, statuses = ready.statuses)
    }
    // onToggleExpanded comes from the scaffold and may carry a new identity per recomposition;
    // the per-card callbacks below read it through this so they can stay pinned per trip.
    val toggleExpanded by rememberUpdatedState(onToggleExpanded)

    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        ScreenHeader(
            "Trips",
            "Record your trips and track visa allowance balances."
        )

        val sections = ready.tripSections
        val tripIndex = remember(sections.upcoming, sections.current, sections.previous) {
            sectionedIndices(
                listOf(
                    sections.upcoming.map { it.id },
                    sections.current.map { it.id },
                    sections.previous.map { it.id },
                ),
            )
        }

        val tripsListState = scrollState

        Box(Modifier.weight(1f)) {
            HazeBox(
                tripsListState,
                MaterialTheme.colorScheme.background,
                modifier = Modifier.fillMaxSize()
            ) {
                LazyColumn(
                    state = tripsListState,
                    modifier = Modifier
                        .fillMaxSize()
                        .onSizeChanged { listViewportPx = it.height },
                    contentPadding = PaddingValues(top = 12.dp, bottom = 66.dp),
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
                            primary = focus.primaryAllowance(zoneAllowances),
                            focusedKey = focus.effectiveFocusKey(zoneAllowances),
                            onFocus = focus::toggle,
                            onRingClick = { snap ->
                                val trip = ringTargetTrip(snap, sections.current, sections.previous)
                                val target = trip?.let { tripIndex[it.id] }

                                if (trip != null && target != null) {
                                    scope.launch {
                                        tripsListState.animateItemToCenter(target, listViewportPx)

                                        // Flash after centring; expire only if no newer tap took over.
                                        val n = flashes.trigger(trip.id)
                                        delay(900.milliseconds)
                                        flashes.expire(trip.id, n)
                                    }
                                }
                            },
                        )

                        Spacer(Modifier.height(4.dp))
                    }


                    if (sections.upcoming.isNotEmpty()) {
                        item(key = "header-upcoming") {
                            SectionLabel("Upcoming")
                        }

                        items(
                            items = sections.upcoming,
                            key = { it.id }
                        ) { trip ->
                            // Callback identities pinned per trip so cards whose trip did not
                            // change can skip recomposition when the screen republishes.
                            val onExpand = remember(trip) { { if (trip.stops.size > 1) toggleExpanded(trip.id) } }
                            val onEdit = remember(trip) { { editingTrip = trip } }
                            val onDelete = remember(trip) { { deletingTrip = trip } }
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
                                onExpand = onExpand,
                                onEdit = onEdit,
                                onEnd = {},
                                onDelete = onDelete,
                                flashTrigger = flashes.countFor(trip.id),
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
                            val onExpand = remember(trip) { { if (trip.stops.size > 1) toggleExpanded(trip.id) } }
                            val onEdit = remember(trip) { { editingTrip = trip } }
                            val onEnd = remember(trip) { { endingTrip = trip } }
                            val onDelete = remember(trip) { { deletingTrip = trip } }
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
                                onExpand = onExpand,
                                onEdit = onEdit,
                                onEnd = onEnd,
                                onDelete = onDelete,
                                flashTrigger = flashes.countFor(trip.id),
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
                            val onExpand = remember(trip) { { if (trip.stops.size > 1) toggleExpanded(trip.id) } }
                            val onEdit = remember(trip) { { editingTrip = trip } }
                            val onDelete = remember(trip) { { deletingTrip = trip } }
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
                                onExpand = onExpand,
                                onEdit = onEdit,
                                onEnd = {},
                                onDelete = onDelete,
                                flashTrigger = flashes.countFor(trip.id),
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
            AddFab("Add trip", Modifier.align(Alignment.BottomEnd)) { showAdd = true }
        }
    }

    if (showAdd) {
        AddTripDialog(vm, ready, null) { showAdd = false }
    }
    if (editingTrip != null) {
        AddTripDialog(vm, ready, editingTrip) { editingTrip = null }
    }
    deletingTrip?.let { trip ->
        VoleAlertDialog(
            title = "Delete trip",
            confirmLabel = "Delete",
            confirmDestructive = true,
            onConfirm = { vm.removeTrip(trip.id); onTripDeleted(trip.id); deletingTrip = null },
            onDismiss = { deletingTrip = null },
            content = {
                Text(
                    if (trip.statusAt(today) == TripStatus.ONGOING)
                        "This trip is ongoing. Delete it and its stops? Allowance usage will be recalculated."
                    else "Delete this trip and its stops? Allowance usage will be recalculated.",
                )
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
private fun EndTripDialog(today: LocalDate, onConfirm: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    VisaDatePickerDialog(
        initialMillis = today.toUtcMillis(),
        confirmLabel = "End",
        onConfirm = { date -> date?.let(onConfirm) },
        onDismiss = onDismiss,
    )
}
