package com.example.visa_vole.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.visa_vole.data.Country
import com.example.visa_vole.data.Regime
import com.example.visa_vole.data.WorldData
import com.example.visa_vole.domain.Document
import com.example.visa_vole.domain.DocKind
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

private fun Long.toUtcIsoDate(): String {
    val d = Instant.ofEpochMilli(this).atOffset(ZoneOffset.UTC).toLocalDate()
    return "%04d-%02d-%02d".format(d.year, d.monthValue, d.dayOfMonth)
}

private fun Document.summary(regimes: List<Regime>): String = when (val k = kind) {
    is DocKind.Passport -> "Passport"
    is DocKind.Holding -> k.holdingId
    is DocKind.Custom -> {
        val bloc = k.blocId?.let { id -> " · bloc ${regimes.firstOrNull { it.id == id }?.name ?: id}" } ?: ""
        val entry = k.entryType?.let { " · ${if (it == "single") "single" else "multiple"} entry" } ?: ""
        "${k.kind} · ${k.countries.size} countries$bloc$entry"
    }
}

@Composable
fun DocumentsScreen(
    vm: AccessViewModel,
    ready: AppState.Ready,
    modifier: Modifier = Modifier,
) {
    var showAdd by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Documents", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Add the passport and papers you hold — the map updates to match.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.weight(1f))
            Button(onClick = { showAdd = true }) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Add document")
            }
        }
        Spacer(Modifier.height(8.dp))
        if (ready.docs.isEmpty()) {
            Text(
                "No documents yet. Start with a passport or a residence permit.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 16.dp),
            )
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(ready.docs, key = { it.id }) { doc ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(doc.label, style = MaterialTheme.typography.titleSmall)
                            Text(
                                doc.summary(ready.world.regimes) +
                                    (doc.validFrom?.let { " · from $it" } ?: "") +
                                    (doc.expiry?.let { " · to $it" } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { vm.removeDocument(doc.id) }) {
                            Icon(Icons.Filled.Close, contentDescription = "Remove")
                        }
                    }
                }
            }
        }
    }
    if (showAdd) {
        AddDocumentDialog(
            world = ready.world,
            onAdd = { vm.addDocument(it) },
            onDismiss = { showAdd = false },
        )
    }
}

private enum class Mode { PASSPORT, CUSTOM }
private enum class CustomKind(val label: String, val kind: String) {
    RESIDENCE("Residence", "residence"),
    VISA("Visa", "visa"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddDocumentDialog(
    world: WorldData,
    onAdd: (Document) -> Unit,
    onDismiss: () -> Unit,
) {
    val countries = world.countries
    var mode by remember { mutableStateOf(Mode.CUSTOM) }
    var query by remember { mutableStateOf("") }
    var passportIso by remember { mutableStateOf<String?>(null) }
    var customKind by remember { mutableStateOf(CustomKind.RESIDENCE) }
    var customIso by remember { mutableStateOf<Set<String>>(emptySet()) }
    var customBlocChoice by remember { mutableStateOf<String?>(null) } // null=auto, ""=none, else regime id
    var holdingChoice by remember { mutableStateOf<String?>(null) } // null=auto, ""=none, else holding id
    var entryType by remember { mutableStateOf("multiple") } // "single" | "multiple" (visa only)
    var expiryDate by remember { mutableStateOf<Long?>(null) }
    var validFromDate by remember { mutableStateOf<Long?>(null) }

    val inferredBloc = world.mobilityBlocFor(customIso)
    val effectiveBloc: String? = when (customBlocChoice) {
        null -> inferredBloc?.id
        "" -> null
        else -> customBlocChoice
    }
    val inferredHolding = world.holdingFor(customIso, customKind.kind)
    val effectiveHolding: String? = when (holdingChoice) {
        null -> inferredHolding
        "" -> null
        else -> holdingChoice
    }

    fun resetFields() {
        query = ""; passportIso = null
        customIso = emptySet(); customBlocChoice = null; holdingChoice = null
    }

    val valid = when (mode) {
        Mode.PASSPORT -> passportIso != null
        Mode.CUSTOM -> customIso.isNotEmpty()
    }
    val expiryOrNull = expiryDate?.let { it.toUtcIsoDate() }
    val validFromOrNull = validFromDate?.let { it.toUtcIsoDate() }

    fun build(): Document {
        val exp = expiryOrNull
        val vfrom = validFromOrNull
        return when (mode) {
            Mode.PASSPORT -> {
                val iso = passportIso!!
                Document(UUID.randomUUID().toString(), "Passport · ${countries[iso]?.name ?: iso}", DocKind.Passport(iso), null, exp, vfrom)
            }
            Mode.CUSTOM -> {
                val names = customIso.joinToString(", ") { countries[it]?.name ?: it }.take(80)
                val typeSuffix = effectiveHolding?.let { id -> " (${world.holdings[id]?.name ?: id})" } ?: ""
                val entry = if (customKind.kind == "visa") entryType else null
                Document(UUID.randomUUID().toString(), "${customKind.label}: $names$typeSuffix", DocKind.Custom(customIso, effectiveBloc, customKind.kind, effectiveHolding, entry), null, exp, vfrom)
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp,
            modifier = Modifier.width(400.dp),
        ) {
            Column(Modifier.padding(20.dp)) {
                Text("Add document", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = mode == Mode.PASSPORT, onClick = { mode = Mode.PASSPORT; resetFields() }, label = { Text("Passport") })
                    FilterChip(selected = mode == Mode.CUSTOM, onClick = { mode = Mode.CUSTOM; resetFields() }, label = { Text("Custom") })
                }
                Spacer(Modifier.height(16.dp))

                when (mode) {
                    Mode.PASSPORT -> CountryPicker(
                        countries, query, { query = it }, setOfNotNull(passportIso),
                        onToggle = { passportIso = it }, single = true,
                    )
                    Mode.CUSTOM -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CustomKind.entries.forEach { ck ->
                                FilterChip(selected = customKind == ck, onClick = { customKind = ck }, label = { Text(ck.label) })
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        CountryPicker(
                            countries, query, { query = it }, customIso,
                            onToggle = { customIso = if (customIso.contains(it)) customIso - it else customIso + it },
                            single = false,
                        )
                        Spacer(Modifier.height(8.dp))
                        val typeOpen = remember { mutableStateOf(false) }
                        val autoTypeName = inferredHolding?.let { world.holdings[it]?.name }
                        val typeLabel = when (val choice = holdingChoice) {
                            null -> if (autoTypeName != null) "Type: $autoTypeName (auto)" else "Type: none (custom only)"
                            "" -> "Type: none"
                            else -> world.holdings[choice]?.name ?: choice
                        }
                        Row(Modifier.fillMaxWidth()) {
                            OutlinedButton(onClick = { typeOpen.value = true }, modifier = Modifier.fillMaxWidth()) {
                                Text(typeLabel)
                            }
                        }
                        DropdownMenu(expanded = typeOpen.value, onDismissRequest = { typeOpen.value = false }) {
                            DropdownMenuItem(
                                text = { Text("Auto — ${autoTypeName ?: "none"}") },
                                onClick = { holdingChoice = null; typeOpen.value = false },
                            )
                            DropdownMenuItem(
                                text = { Text("No known type (custom only)") },
                                onClick = { holdingChoice = ""; typeOpen.value = false },
                            )
                            world.holdings.values.sortedBy { it.name }.forEach { h ->
                                DropdownMenuItem(
                                    text = { Text("${h.name} (${h.category})") },
                                    onClick = { holdingChoice = h.id; typeOpen.value = false },
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        val blocOpen = remember { mutableStateOf(false) }
                        val autoName = inferredBloc?.name
                        val blocLabel = when (val choice = customBlocChoice) {
                            null -> if (autoName != null) "Mobility bloc: $autoName (auto)" else "Mobility bloc: none detected"
                            "" -> "Mobility bloc: none"
                            else -> world.regimes.firstOrNull { it.id == choice }?.name ?: choice
                        }
                        Row(Modifier.fillMaxWidth()) {
                            OutlinedButton(onClick = { blocOpen.value = true }, modifier = Modifier.fillMaxWidth()) {
                                Text(blocLabel)
                            }
                        }
                        DropdownMenu(expanded = blocOpen.value, onDismissRequest = { blocOpen.value = false }) {
                            DropdownMenuItem(
                                text = { Text("Auto — ${autoName ?: "none detected"}") },
                                onClick = { customBlocChoice = null; blocOpen.value = false },
                            )
                            DropdownMenuItem(
                                text = { Text("No mobility bloc") },
                                onClick = { customBlocChoice = ""; blocOpen.value = false },
                            )
                            world.regimes.sortedBy { it.name }.forEach { r ->
                                DropdownMenuItem(
                                    text = { Text("${r.name} (${r.id})") },
                                    onClick = { customBlocChoice = r.id; blocOpen.value = false },
                                )
                            }
                        }
                        if (customKind == CustomKind.VISA) {
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(selected = entryType == "single", onClick = { entryType = "single" }, label = { Text("Single entry") })
                                FilterChip(selected = entryType == "multiple", onClick = { entryType = "multiple" }, label = { Text("Multiple entry") })
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                var showValidFromPicker by remember { mutableStateOf(false) }
                OutlinedButton(onClick = { showValidFromPicker = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.DateRange, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(validFromDate?.let { it.toUtcIsoDate() } ?: "Valid from (optional)")
                }
                if (showValidFromPicker) {
                    val datePickerState = rememberDatePickerState(initialSelectedDateMillis = validFromDate)
                    DatePickerDialog(
                        onDismissRequest = { showValidFromPicker = false },
                        confirmButton = {
                            TextButton(onClick = { validFromDate = datePickerState.selectedDateMillis; showValidFromPicker = false }) { Text("OK") }
                        },
                        dismissButton = {
                            TextButton(onClick = { validFromDate = null; showValidFromPicker = false }) { Text("Clear") }
                        },
                    ) {
                        DatePicker(state = datePickerState)
                    }
                }
                Spacer(Modifier.height(8.dp))
                var showDatePicker by remember { mutableStateOf(false) }
                OutlinedButton(onClick = { showDatePicker = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.DateRange, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(expiryDate?.let { it.toUtcIsoDate() } ?: "Valid to (optional)")
                }
                if (showDatePicker) {
                    val datePickerState = rememberDatePickerState(initialSelectedDateMillis = expiryDate)
                    DatePickerDialog(
                        onDismissRequest = { showDatePicker = false },
                        confirmButton = {
                            TextButton(onClick = { expiryDate = datePickerState.selectedDateMillis; showDatePicker = false }) { Text("OK") }
                        },
                        dismissButton = {
                            TextButton(onClick = { expiryDate = null; showDatePicker = false }) { Text("Clear") }
                        },
                    ) {
                        DatePicker(state = datePickerState)
                    }
                }
                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    OutlinedButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(12.dp))
                    Button(onClick = { onAdd(build()); onDismiss() }, enabled = valid) { Text("Add") }
                }
            }
        }
    }
}

/** Search + list of countries; tap toggles membership in [selected]. */
@Composable
private fun CountryPicker(
    countries: Map<String, Country>,
    query: String,
    onQuery: (String) -> Unit,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    single: Boolean,
) {
    val q = query.trim().lowercase()
    val list = countries.entries
        .filter { q.isEmpty() || it.value.name.lowercase().contains(q) || it.key.lowercase() == q }
        .sortedBy { it.value.name }
    Column {
        OutlinedTextField(
            value = query, onValueChange = onQuery,
            label = { Text(if (single) "Passport country" else "Add countries") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Surface(
            modifier = Modifier.fillMaxWidth().height(220.dp),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            LazyColumn(Modifier.padding(4.dp)) {
                items(list, key = { it.key }) { entry ->
                    val isSel = selected.contains(entry.key)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onToggle(entry.key) }
                            .padding(vertical = 8.dp, horizontal = 10.dp),
                    ) {
                        Text(
                            if (isSel) "✓ " else "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(entry.value.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text(entry.key, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
