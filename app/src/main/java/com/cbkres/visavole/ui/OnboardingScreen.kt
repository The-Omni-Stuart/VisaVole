package com.cbkres.visavole.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cbkres.visavole.data.Country
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

private fun Long.toUtcIsoDate(): String {
    val d = Instant.ofEpochMilli(this).atOffset(ZoneOffset.UTC).toLocalDate()
    return "%04d-%02d-%02d".format(d.year, d.monthValue, d.dayOfMonth)
}

/** First-run setup: add the traveller's first passport (required to compute the map). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    countries: Map<String, Country>,
    onAddPassport: (String, String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    var pendingIso by remember { mutableStateOf<String?>(null) }
    var showExpiryDialog by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    val q = query.trim().lowercase()
    val list = countries.entries
        .filter { q.isEmpty() || it.value.name.lowercase().contains(q) || it.key.lowercase() == q }
        .sortedBy { it.value.name }

    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = modifier.fillMaxSize(),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(20.dp),
        ) {
            Text("Welcome to Visa Vole", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                "Add your first passport (you can add more later). Every passport becomes a home country (blue) on the map.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search country") },
                singleLine = true,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                val onboardingListState = rememberLazyListState()
                val (onbTop, onbEnd) = onboardingListState.hazeAlphas()
                HazeBox(onbTop, onbEnd, MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = onboardingListState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(4.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(list, key = { it.key }) { entry ->
                            val name = entry.value.name
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        pendingIso = entry.key
                                        showExpiryDialog = true
                                    }
                                    .padding(vertical = 12.dp, horizontal = 10.dp),
                            ) {
                                Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                                Text(entry.key, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showExpiryDialog) {
        val iso = pendingIso
        if (iso != null) {
            val countryName = countries[iso]?.name ?: iso
            AlertDialog(
                onDismissRequest = { showExpiryDialog = false },
                title = { Text("$countryName passport") },
                text = { Text("Add an expiry date for this passport? You can skip it and add one later from the documents screen.") },
                confirmButton = {
                    Button(onClick = {
                        showExpiryDialog = false
                        showDatePicker = true
                    }) { Text("Choose date") }
                },
                dismissButton = {
                    TextButton(onClick = { onAddPassport(iso, null) }) { Text("Skip") }
                },
            )
        }
    }

    if (showDatePicker) {
        val iso = pendingIso
        if (iso != null) {
            val todayMillis = LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            val datePickerState = rememberDatePickerState(initialSelectedDateMillis = todayMillis)
            DatePickerDialog(
                onDismissRequest = {
                    showDatePicker = false
                    showExpiryDialog = true
                },
                confirmButton = {
                    TextButton(onClick = {
                        onAddPassport(iso, datePickerState.selectedDateMillis?.toUtcIsoDate())
                        showDatePicker = false
                        showExpiryDialog = false
                    }) { Text("Save") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showDatePicker = false
                        showExpiryDialog = true
                    }) { Text("Cancel") }
                },
            ) {
                DatePicker(state = datePickerState)
            }
        }
    }
}
