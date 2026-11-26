package com.cbkres.visavole.ui.screens.trips

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cbkres.visavole.domain.Access
import com.cbkres.visavole.domain.AccessModel
import com.cbkres.visavole.domain.DocStatus
import com.cbkres.visavole.domain.Document
import com.cbkres.visavole.domain.Trip
import com.cbkres.visavole.domain.TripModel
import com.cbkres.visavole.domain.TripStop
import com.cbkres.visavole.domain.TripWarning
import com.cbkres.visavole.domain.WarningSeverity
import com.cbkres.visavole.domain.WorldData
import com.cbkres.visavole.domain.passportCountsByIso
import com.cbkres.visavole.domain.passportNumbers
import com.cbkres.visavole.ui.DateFieldButton
import com.cbkres.visavole.ui.HazeBox
import com.cbkres.visavole.ui.VisaDatePickerDialog
import com.cbkres.visavole.ui.VisaDialog
import com.cbkres.visavole.ui.VoleAlertDialog
import com.cbkres.visavole.ui.components.forms.CountryPicker
import com.cbkres.visavole.ui.components.forms.SelectionField
import com.cbkres.visavole.ui.components.forms.VoleMenuItemPadding
import com.cbkres.visavole.ui.hazeAlphas
import com.cbkres.visavole.ui.screens.map.LocalEffectiveDarkTheme
import com.cbkres.visavole.ui.screens.map.Severity
import com.cbkres.visavole.ui.screens.map.severityColor
import com.cbkres.visavole.ui.toUtcMillis

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StopEditorDialog(
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
    statuses: Map<String, DocStatus> = emptyMap(),
) {
    var stop by rememberSaveable { mutableStateOf(initial) }
    var countryQuery by rememberSaveable { mutableStateOf("") }
    var docTouched by rememberSaveable { mutableStateOf(initial.documentId != null) }
    var droppedDocFor by rememberSaveable { mutableStateOf<String?>(null) }
    var showArrival by remember { mutableStateOf(false) }
    var showDeparture by remember { mutableStateOf(initiallyEditingDeparture) }
    var pendingAccessWarning by remember { mutableStateOf<TripWarning?>(null) }
    val relevantDocs = remember(stop.countryIso2, stop.arrival, docs, trips) {
        if (stop.countryIso2 in world.countries) {
            AccessModel.relevantDocuments(stop.countryIso2, docs, world, stop.arrival, trips)
        } else {
            docs
        }
    }
    LaunchedEffect(stop, docs, carryDocumentId) {
        val held = stop.documentId
        // A touched document sticks only while it still covers the country; a country change
        // re-points it (or drops it, with a word), so the gate can never be one that can't gate.
        if (docTouched && (held == null || relevantDocs.any { it.id == held })) return@LaunchedEffect
        val preferred = AccessModel.autoSelectDocumentId(
            stop.countryIso2, docs, world, stop.arrival,
            carriedId = carryDocumentId, primaryId = primaryDocId, trips,
        )
        if (preferred != stop.documentId) {
            stop = stop.copy(documentId = preferred)
        }
        if (docTouched && held != null && preferred == null) droppedDocFor = stop.countryIso2
    }
    val validHomes = remember(docs, world, stop.arrival, statuses) {
        AccessModel.validHomeCountries(docs, world, stop.arrival, statuses = statuses)
    }
    val rule = world.stayRuleFor(stop.countryIso2, validHomes, stop.arrival)
    // One whole-world compute per date, off the UI thread: picking another country indexes this
    // map instead of re-running the access model. Null briefly while a new date's compute runs.
    val accessByCountry by produceState(
        initialValue = emptyMap<String, Access>(),
        docs, world, stop.arrival, trips, statuses,
    ) {
        value = withContext(Dispatchers.Default) {
            AccessModel.compute(docs, world, stop.arrival, trips, statuses)
        }
    }
    val stopAccess = accessByCountry[stop.countryIso2]
    val accessWarning = TripModel.accessWarningFor(
        stopAccess,
        world.countries[stop.countryIso2]?.name ?: stop.countryIso2,
        stop.arrival,
    )
    val selectedDoc = docs.firstOrNull { it.id == stop.documentId }
    val countrySelected = stop.countryIso2 in world.countries
    val passportCounts = remember(docs) { passportCountsByIso(docs) }
    val passportNumbers = remember(docs) { passportNumbers(docs) }
    VisaDialog(
        onDismissRequest = onDismiss,
        width = 400.dp,
        dismissable = false,
    ) {
        val stopDialogScroll = rememberScrollState()
        val (stopTop, stopEnd) = stopDialogScroll.hazeAlphas()
        HazeBox(
            stopTop,
            stopEnd,
            MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(20.dp).verticalScroll(stopDialogScroll)) {
                Text(
                    if (onRemove == null) "Add stop" else "Edit stop",
                    style = MaterialTheme.typography.titleLarge
                )
                Spacer(Modifier.height(16.dp))
                CountryPicker(
                    countries = world.countries,
                    query = countryQuery,
                    onQuery = { countryQuery = it },
                    selected = setOf(stop.countryIso2),
                    onToggle = { iso -> stop = stop.copy(countryIso2 = iso) },
                    label = "Country",
                    error = if (countrySelected) null else
                        if (stop.countryIso2.isBlank()) "Pick a country to continue" else "Country not found",
                )
                Spacer(Modifier.height(8.dp))
                DateFieldButton("Arrival: ${stop.arrival}", onClick = { showArrival = true })
                Spacer(Modifier.height(8.dp))
                DateFieldButton(
                    "Departure: ${stop.departure ?: "open"}",
                    onClick = { showDeparture = true })
                Spacer(Modifier.height(8.dp))
                SelectionField(
                    label = "Document",
                    value = selectedDoc?.compactDisplayLabel(passportCounts, passportNumbers)
                        ?: "None"
                ) { onSelected ->
                    DropdownMenuItem(
                        contentPadding = VoleMenuItemPadding,
                        text = { Text("None") },
                        onClick = {
                            stop = stop.copy(documentId = null)
                            docTouched = true
                            onSelected()
                        },
                    )
                    relevantDocs.forEach { d ->
                        DropdownMenuItem(
                            contentPadding = VoleMenuItemPadding,
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
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (TripModel.isShortStaySuppressed(stopAccess)) {
                    Text(
                        "Days here won't count against short-stay allowance.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(20.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (onRemove != null) {
                        TextButton(onClick = onRemove) { Text("Remove") }
                        Spacer(Modifier.width(8.dp))
                    }
                    OutlinedButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(12.dp))
                    Button(
                        onClick = {
                            val w = accessWarning
                            if (w != null) pendingAccessWarning = w else {
                                onDone(stop); onDismiss()
                            }
                        },
                        enabled = countrySelected,
                    ) { Text("Done") }
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
            maxDate = stop.departure,
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
            onSecondary = if (isFinal) ({
                stop = stop.copy(departure = null); showDeparture = false
            }) else null,
            minDate = stop.arrival,
        )
    }
    droppedDocFor?.let { iso ->
        VoleAlertDialog(
            title = "No covering document",
            confirmLabel = "OK",
            onConfirm = { droppedDocFor = null },
            dismissLabel = null,
            onDismiss = { droppedDocFor = null },
            content = {
                Text("None of your documents cover ${world.countries[iso]?.name ?: iso}, so this stop keeps no document — its stay will show as \"no valid document\".")
            },
        )
    }
    pendingAccessWarning?.let { w ->
        StopAccessWarningDialog(
            warning = w,
            onConfirm = { onDone(stop); pendingAccessWarning = null; onDismiss() },
            onDismiss = { pendingAccessWarning = null },
        )
    }
}

/** Warns before saving a stop whose destination needs a visa, e-visa, or has no entry grant. */
@Composable
private fun StopAccessWarningDialog(
    warning: TripWarning,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    VoleAlertDialog(
        title = warning.title,
        confirmLabel = "Save anyway",
        onConfirm = onConfirm,
        dismissLabel = "Keep editing",
        onDismiss = onDismiss,
        content = {
            Text(
                warning.message,
                style = MaterialTheme.typography.bodySmall,
                color = severityColor(
                    if (warning.severity == WarningSeverity.DANGER) Severity.BAD else Severity.WARN,
                    LocalEffectiveDarkTheme.current,
                ),
            )
        },
    )
}