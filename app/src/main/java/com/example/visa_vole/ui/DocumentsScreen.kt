package com.example.visa_vole.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.visa_vole.data.Country
import com.example.visa_vole.data.WorldData
import com.example.visa_vole.domain.Document
import com.example.visa_vole.domain.DocKind
import com.example.visa_vole.domain.ResidenceClass
import com.example.visa_vole.domain.defaultResidenceClassFor
import com.example.visa_vole.domain.residenceClassFor
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

private fun Long.toUtcIsoDate(): String {
    val d = Instant.ofEpochMilli(this).atOffset(ZoneOffset.UTC).toLocalDate()
    return "%04d-%02d-%02d".format(d.year, d.monthValue, d.dayOfMonth)
}

private val STATUS_ORANGE = Color(0xFFEF6C00)
private val STATUS_GREEN = Color(0xFF2E7D32)
private val STATUS_RED = Color(0xFFC62828)

/** Days from [today] until the ISO date in [iso]; null when unset or unparsable. */
private fun daysUntil(iso: String?, today: LocalDate): Long? =
    iso?.let { runCatching { ChronoUnit.DAYS.between(today, LocalDate.parse(it)) }.getOrNull() }

/** True when the document has an expiry and it is already past. */
private fun isExpired(doc: Document, today: LocalDate): Boolean =
    daysUntil(doc.expiry, today)?.let { it < 0 } ?: false

/**
 * The document subtitle: kind/summary and the valid-from / valid-to dates in the neutral base
 * colour; only the entry type is tinted (orange for single, green for multiple). The expiry
 * status itself is carried by a separate pill in the row.
 */
private fun docSubtitle(
    doc: Document,
    world: WorldData,
    base: Color,
    orange: Color,
    green: Color,
    red: Color,
): AnnotatedString {
    val k = doc.kind
    val regime = (k as? DocKind.Custom)?.blocId?.let { id -> world.regimes.firstOrNull { r -> r.id == id }?.name }
    val entryType = (k as? DocKind.Custom)?.entryType
    val classSuffix = doc.residenceClassFor(world)?.let { " · ${it.label}" } ?: ""

    val b = AnnotatedString.Builder()
    fun styled(text: String, color: Color) {
        val s = b.length
        b.append(text)
        b.addStyle(SpanStyle(color = color), s, b.length)
    }
    styled(
        when (k) {
            is DocKind.Passport -> "Passport"
            is DocKind.Holding -> k.holdingId + classSuffix
            is DocKind.Custom -> {
                val bloc = regime?.let { " · bloc $it" } ?: ""
                "${k.kind} · ${k.countries.size} countries$bloc$classSuffix"
            }
        },
        base,
    )
    entryType?.let { type ->
        val (label, color) = when (type) {
            "single" -> "single" to red
            "double" -> "double" to orange
            else -> "multiple" to green
        }
        styled(" · $label entry", color)
    }
    doc.validFrom?.let { from -> styled(" · from $from", base) }
    doc.expiry?.let { exp -> styled(" · to $exp", base) }
    return b.toAnnotatedString()
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
        } else {
            val today = LocalDate.now(ZoneOffset.UTC)
            val active = ready.docs.filter { !isExpired(it, today) }
            val archived = ready.docs.filter { isExpired(it, today) }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (active.isNotEmpty()) {
                    item(key = "header-active") { SectionLabel("Active") }
                    items(active, key = { it.id }) { doc ->
                        DocCard(doc, ready.world, today, onRemove = { vm.removeDocument(doc.id) })
                    }
                }
                if (archived.isNotEmpty()) {
                    item(key = "header-archive") { SectionLabel("Archive") }
                    items(archived, key = { it.id }) { doc ->
                        DocCard(doc, ready.world, today, onRemove = { vm.removeDocument(doc.id) })
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
private fun ExpiryStatusPill(doc: Document, today: LocalDate) {
    val days = daysUntil(doc.expiry, today) ?: return
    val (text, color, bold) = when {
        days < 0 -> Triple("Expired", MaterialTheme.colorScheme.error, true)
        days <= 7 -> Triple("Expiring soon ($days days)", STATUS_ORANGE, false)
        else -> Triple("Valid for $days days", STATUS_GREEN, false)
    }
    Surface(shape = RoundedCornerShape(50), color = color.copy(alpha = 0.15f)) {
        Text(
            text,
            color = color,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun DocCard(
    doc: Document,
    world: WorldData,
    today: LocalDate,
    onRemove: () -> Unit,
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(doc.label, style = MaterialTheme.typography.titleSmall)
                Text(
                    docSubtitle(doc, world, MaterialTheme.colorScheme.onSurfaceVariant, STATUS_ORANGE, STATUS_GREEN, STATUS_RED),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (doc.expiry != null) {
                Spacer(Modifier.width(8.dp))
                ExpiryStatusPill(doc, today)
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Filled.Close, contentDescription = "Remove")
            }
        }
    }
}

private enum class DocType(val label: String, val kind: String) {
    PASSPORT("Passport", "passport"),
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
    var docType by remember { mutableStateOf(DocType.RESIDENCE) }
    var query by remember { mutableStateOf("") }
    var passportIso by remember { mutableStateOf<String?>(null) }
    var customIso by remember { mutableStateOf<Set<String>>(emptySet()) }
    var customBlocChoice by remember { mutableStateOf<String?>(null) } // null=auto, ""=none, else regime id
    var holdingChoice by remember { mutableStateOf<String?>(null) } // null=auto, ""=none, else holding id
    var entryType by remember { mutableStateOf("multiple") } // "single" | "double" | "multiple" (visa only)
    var residenceClass by remember { mutableStateOf(ResidenceClass.TEMPORARY) } // residence only
    var expiryDate by remember { mutableStateOf<Long?>(null) }
    var validFromDate by remember { mutableStateOf<Long?>(null) }

    // A residence/visa earns short-stay travel, not freedom of movement — infer the visa-free
    // travel bloc (e.g. Schengen for an EU residence) rather than the freedom-of-movement bloc.
    val inferredBloc = world.travelBlocFor(customIso)
    val effectiveBloc: String? = when (customBlocChoice) {
        null -> inferredBloc?.id
        "" -> null
        else -> customBlocChoice
    }
    val inferredHolding = world.holdingFor(customIso, docType.kind)
    val effectiveHolding: String? = when (holdingChoice) {
        null -> inferredHolding
        "" -> null
        else -> holdingChoice
    }

    fun resetFields() {
        query = ""; passportIso = null
        customIso = emptySet(); customBlocChoice = null; holdingChoice = null
    }

    // The residence class follows the selected known holding (e.g. a US green card defaults to
    // permanent); a custom residence with no known holding defaults to temporary.
    LaunchedEffect(docType, effectiveHolding) {
        if (docType == DocType.RESIDENCE) {
            residenceClass = defaultResidenceClassFor(effectiveHolding, effectiveHolding == null)
        }
    }

    val valid = when (docType) {
        DocType.PASSPORT -> passportIso != null
        DocType.RESIDENCE, DocType.VISA -> customIso.isNotEmpty()
    }
    val expiryOrNull = expiryDate?.let { it.toUtcIsoDate() }
    val validFromOrNull = validFromDate?.let { it.toUtcIsoDate() }

    fun build(): Document {
        val exp = expiryOrNull
        val vfrom = validFromOrNull
        return when (docType) {
            DocType.PASSPORT -> {
                val iso = passportIso!!
                Document(UUID.randomUUID().toString(), "Passport · ${countries[iso]?.name ?: iso}", DocKind.Passport(iso), null, exp, vfrom)
            }
            DocType.RESIDENCE, DocType.VISA -> {
                val names = customIso.joinToString(", ") { countries[it]?.name ?: it }.take(80)
                val typeSuffix = effectiveHolding?.let { id -> " (${world.holdings[id]?.name ?: id})" } ?: ""
                val entry = if (docType == DocType.VISA) entryType else null
                val rc = if (docType == DocType.RESIDENCE) residenceClass else null
                Document(UUID.randomUUID().toString(), "${docType.label}: $names$typeSuffix", DocKind.Custom(customIso, effectiveBloc, docType.kind, effectiveHolding, entry), null, exp, vfrom, rc)
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp,
            modifier = Modifier
                .width(400.dp)
                .heightIn(max = (LocalConfiguration.current.screenHeightDp - 96).dp),
        ) {
            Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState())) {
                Text("Add document", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DocType.entries.forEach { t ->
                        FilterChip(selected = docType == t, onClick = { docType = t; resetFields() }, label = { Text(t.label) })
                    }
                }
                Spacer(Modifier.height(16.dp))

                when (docType) {
                    DocType.PASSPORT -> CountryPicker(
                        countries, query, { query = it }, setOfNotNull(passportIso),
                        onToggle = { passportIso = it }, single = true,
                    )
                    DocType.RESIDENCE, DocType.VISA -> {
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
                            world.holdingsForKind(docType.kind).forEach { h ->
                                DropdownMenuItem(
                                    text = { Text(h.name) },
                                    onClick = { holdingChoice = h.id; typeOpen.value = false },
                                )
                            }
                        }
                        if (docType == DocType.RESIDENCE) {
                            Spacer(Modifier.height(8.dp))
                            val classOpen = remember { mutableStateOf(false) }
                            Row(Modifier.fillMaxWidth()) {
                                OutlinedButton(onClick = { classOpen.value = true }, modifier = Modifier.fillMaxWidth()) {
                                    Text("Residence class: ${residenceClass.label}")
                                }
                            }
                            DropdownMenu(expanded = classOpen.value, onDismissRequest = { classOpen.value = false }) {
                                ResidenceClass.entries.forEach { rc ->
                                    DropdownMenuItem(
                                        text = { Text(rc.label) },
                                        onClick = { residenceClass = rc; classOpen.value = false },
                                    )
                                }
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
                        if (docType == DocType.VISA) {
                            Spacer(Modifier.height(8.dp))
                            val entryOpen = remember { mutableStateOf(false) }
                            val entryLabel = when (entryType) {
                                "single" -> "Single entry"
                                "double" -> "Double entry"
                                else -> "Multiple entry"
                            }
                            Row(Modifier.fillMaxWidth()) {
                                OutlinedButton(onClick = { entryOpen.value = true }, modifier = Modifier.fillMaxWidth()) {
                                    Text("Entry type: $entryLabel")
                                }
                            }
                            DropdownMenu(expanded = entryOpen.value, onDismissRequest = { entryOpen.value = false }) {
                                DropdownMenuItem(
                                    text = { Text("Single entry") },
                                    onClick = { entryType = "single"; entryOpen.value = false },
                                )
                                DropdownMenuItem(
                                    text = { Text("Double entry") },
                                    onClick = { entryType = "double"; entryOpen.value = false },
                                )
                                DropdownMenuItem(
                                    text = { Text("Multiple entry") },
                                    onClick = { entryType = "multiple"; entryOpen.value = false },
                                )
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
