package com.cbkres.visavole.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cbkres.visavole.data.Country
import com.cbkres.visavole.domain.GuardFinding
import java.time.LocalDate

/** First-run setup: add the traveller's first passport (required to compute the map). */
@Composable
fun OnboardingScreen(
    countries: Map<String, Country>,
    today: LocalDate,
    onAddPassport: (String, String?) -> List<GuardFinding>,
    modifier: Modifier = Modifier,
) {
    // rememberSaveable so an in-progress setup (chosen country, open dialogs, query)
    // survives process death; the transient addError is intentionally not saved.
    var query by rememberSaveable { mutableStateOf("") }
    var pendingIso by rememberSaveable { mutableStateOf<String?>(null) }
    var showExpiryDialog by rememberSaveable { mutableStateOf(false) }
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    var addError by remember { mutableStateOf<String?>(null) }
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
            addError?.let { err ->
                Spacer(Modifier.height(16.dp))
                Text(err, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
            }
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
                            CountryRow(
                                name = entry.value.name,
                                onClick = {
                                    pendingIso = entry.key
                                    addError = null
                                    showExpiryDialog = true
                                },
                                iso = entry.key,
                            )
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
            val tryAdd = { expiry: String? ->
                addError = onAddPassport(iso, expiry).firstOrNull()?.message
                showExpiryDialog = false
            }
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
                    TextButton(onClick = { tryAdd(null) }) { Text("Skip") }
                },
            )
        }
    }

    if (showDatePicker) {
        val iso = pendingIso
        if (iso != null) {
            VisaDatePickerDialog(
                initialMillis = today.toUtcMillis(),
                confirmLabel = "Save",
                onConfirm = { date ->
                    addError = onAddPassport(iso, date?.toUtcIsoDate()).firstOrNull()?.message
                    showDatePicker = false
                    showExpiryDialog = false
                },
                onDismiss = { showDatePicker = false; showExpiryDialog = true },
                secondaryLabel = "Cancel",
                onSecondary = { showDatePicker = false; showExpiryDialog = true },
            )
        }
    }
}
