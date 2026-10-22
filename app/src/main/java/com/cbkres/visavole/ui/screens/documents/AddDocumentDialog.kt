package com.cbkres.visavole.ui.screens.documents

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.cbkres.visavole.data.Country
import com.cbkres.visavole.data.WorldData
import com.cbkres.visavole.domain.DocKind
import com.cbkres.visavole.domain.Document
import com.cbkres.visavole.domain.GuardAction
import com.cbkres.visavole.domain.GuardContext
import com.cbkres.visavole.domain.GuardEngine
import com.cbkres.visavole.domain.GuardFinding
import com.cbkres.visavole.domain.ResidenceClass
import com.cbkres.visavole.domain.defaultResidenceClassFor
import com.cbkres.visavole.domain.duplicateSignature
import com.cbkres.visavole.domain.passportDocument
import com.cbkres.visavole.ui.components.forms.CountryPicker
import com.cbkres.visavole.ui.components.forms.SelectionField
import com.cbkres.visavole.ui.hazeAlphas
import com.cbkres.visavole.ui.screens.map.LocalEffectiveDarkTheme
import com.cbkres.visavole.ui.screens.map.Severity
import com.cbkres.visavole.ui.screens.map.severityColor
import com.cbkres.visavole.ui.toUtcIsoDate
import com.cbkres.visavole.ui.toUtcMillis
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

private enum class DocType(val label: String, val kind: String) {
    PASSPORT("Passport", "passport"),
    RESIDENCE("Residence", "residence"),
    VISA("Visa", "visa"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddDocumentDialog(
    world: WorldData,
    guard: GuardContext,
    onAdd: (Document) -> Unit,
    onDismiss: () -> Unit,
    initial: Document? = null,
    onEditExisting: (Document) -> Unit,
) {
    val countries = world.countries
    val initPassport = initial?.kind as? DocKind.Passport
    val initCustom = initial?.kind as? DocKind.Custom
    val initHolding = initial?.kind as? DocKind.Holding
    var docType by remember {
        mutableStateOf(
            when {
                initPassport != null -> DocType.PASSPORT
                initCustom?.kind == "visa" || initCustom?.kind == "permit" -> DocType.VISA
                initCustom?.kind == "passport" -> DocType.PASSPORT
                else -> DocType.RESIDENCE
            },
        )
    }
    var customIso by remember {
        mutableStateOf(
            when {
                initPassport != null -> setOf(initPassport.iso2)
                initCustom != null -> initCustom.countries
                else -> emptySet()
            },
        )
    }
    var query by remember { mutableStateOf("") }
    var lastIso by remember {
        mutableStateOf(
            when {
                initPassport != null -> initPassport.iso2
                initCustom != null -> initCustom.countries.firstOrNull()
                else -> null
            },
        )
    }
    val initialInferredBloc = remember(initCustom) { initCustom?.let { world.travelBlocFor(it.countries) } }
    var customBlocChoice by remember {
        mutableStateOf(
            when {
                initCustom == null -> null
                initialInferredBloc?.id == initCustom.blocId -> null
                initCustom.blocId == null -> ""
                else -> initCustom.blocId
            },
        )
    } // null=auto, ""=none, else regime id
    val initialInferredHolding = remember(initCustom) { initCustom?.let { world.holdingFor(it.countries, it.kind) } }
    var holdingChoice by remember {
        mutableStateOf(
            when {
                initCustom == null -> initHolding?.holdingId
                initialInferredHolding == initCustom.holdingId -> null
                initCustom.holdingId == null -> ""
                else -> initCustom.holdingId
            },
        )
    } // null=auto, ""=none, else holding id
    var entryType by remember { mutableStateOf(initCustom?.entryType ?: "multiple") } // "single" | "double" | "multiple" (visa only)
    var residenceClass by remember { mutableStateOf(initial?.residenceClass ?: ResidenceClass.TEMPORARY) } // residence only
    var expiryDate by remember { mutableStateOf(initial?.expiry?.toUtcMillis()) }
    var validFromDate by remember { mutableStateOf(initial?.validFrom?.toUtcMillis()) }
    // P3-55: a validity period is both-or-neither. When a save is attempted with exactly one date
    // set, show a fix dialog and re-open the requested picker in the field.
    var showValidityPairDialog by remember { mutableStateOf(false) }
    var validityReopenToken by remember { mutableStateOf(0) }
    var validityReopenPicker by remember { mutableStateOf(1) } // 1=valid-from, 2=expiry
    // Guard-driven dialog state: [blocked] is the blocking finding shown to the user,
    // [blockedDuplicate] the existing document an "Edit existing" button can jump to,
    // [pendingMutationDoc]/[pendingMutationWarn] the pending action + its accompanying mutation
    // awaiting confirmation, and [multiCountryDoc] the visa awaiting the multi-country confirm.
    var blocked by remember { mutableStateOf<GuardFinding?>(null) }
    var blockedDuplicate by remember { mutableStateOf<Document?>(null) }
    var pendingMutationDoc by remember { mutableStateOf<Document?>(null) }
    var pendingMutationWarn by remember { mutableStateOf<GuardFinding?>(null) }
    var multiCountryDoc by remember { mutableStateOf<Document?>(null) }

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

    val visaMulti = docType == DocType.VISA && customIso.size > 1

    fun onSwitchTab(target: DocType) {
        if (docType == DocType.VISA && customIso.size > 1 && target != DocType.VISA) return
        customBlocChoice = null
        holdingChoice = null
    }

    // The residence class follows the selected known holding (e.g. a US green card defaults to
    // permanent); a custom residence with no known holding defaults to temporary. In edit mode the
    // initially stored class is preserved until the holding actually changes.
    var lastResidenceKey by remember { mutableStateOf(docType to effectiveHolding) }
    LaunchedEffect(docType, effectiveHolding) {
        val key = docType to effectiveHolding
        if (key != lastResidenceKey) {
            lastResidenceKey = key
            if (docType == DocType.RESIDENCE) {
                residenceClass = defaultResidenceClassFor(effectiveHolding, effectiveHolding == null)
            }
        }
    }

    // `holdingChoice` is null=auto, ""=the "No known type (custom only)" option, else an id.
    // With no countries selected, "" would build a broken DocKind.Holding("") (empty title,
    // matches nothing) — so saving a holding doc requires a real holding choice, and picking
    // "no known type" forces a country selection (which saves as a custom doc instead).
    val valid = when {
        docType == DocType.PASSPORT -> lastIso != null && customIso.isNotEmpty()
        initHolding != null && customIso.isEmpty() -> docType == DocType.RESIDENCE &&
                holdingChoice != null && holdingChoice != ""
        docType == DocType.RESIDENCE || docType == DocType.VISA -> customIso.isNotEmpty()
        else -> false
    }
    val expiryOrNull = expiryDate?.let { it.toUtcIsoDate() }
    val validFromOrNull = validFromDate?.let { it.toUtcIsoDate() }

    fun build(): Document {
        val id = initial?.id ?: UUID.randomUUID().toString()
        val exp = expiryOrNull
        val vfrom = validFromOrNull
        return when {
            docType == DocType.PASSPORT -> {
                val iso = lastIso ?: customIso.first()
                passportDocument(iso, countries[iso]?.name, exp, vfrom, id = id)
            }
            initHolding != null && customIso.isEmpty() -> {
                val h = holdingChoice!!
                val label = (world.holdings[h]?.name ?: h) + residenceClass.let { " · ${it.label}" }
                Document(id, label, DocKind.Holding(h), exp, vfrom, residenceClass)
            }
            else -> {
                val names = customIso.joinToString(", ") { countries[it]?.name ?: it }.take(80)
                val typeSuffix = effectiveHolding?.let { hid -> " (${world.holdings[hid]?.name ?: hid})" } ?: ""
                val entry = if (docType == DocType.VISA) entryType else null
                val rc = if (docType == DocType.RESIDENCE) residenceClass else null
                Document(id, "${docType.label}: $names$typeSuffix", DocKind.Custom(customIso, effectiveBloc, docType.kind, effectiveHolding, entry), exp, vfrom, rc)
            }
        }
    }

    // Run the pending document through the centralised guard. Blocks stop the save; the
    // multi-country warning asks once; accompanying mutations (e.g. expiring a superseded doc)
    // get their own confirm. [skipMultiCountry] lets "Save anyway" re-run the rest. The
    // ViewModel backstops the very same rules before persisting.
    fun saveDoc(doc: Document, skipMultiCountry: Boolean) {
        val action = if (initial != null) GuardAction.UpdateDocument(doc, initial) else GuardAction.AddDocument(doc)
        val result = GuardEngine.evaluate(action, guard)
        if (result.blocks.isNotEmpty()) {
            val block = result.blocks.first()
            blocked = block
            blockedDuplicate = if (block.code == "doc.duplicate") {
                guard.docs.firstOrNull { it.id != doc.id && it.duplicateSignature() == doc.duplicateSignature() }
            } else {
                null
            }
            return
        }
        if (!skipMultiCountry && result.warnings.any { it.code == "doc.visa.multiCountry" }) {
            multiCountryDoc = doc
            return
        }
        if (result.mutations.isNotEmpty()) {
            pendingMutationDoc = doc
            pendingMutationWarn = result.warnings.firstOrNull { it.code == "doc.single.perCountry" }
                ?: result.warnings.firstOrNull()
            return
        }
        onAdd(doc)
        onDismiss()
    }

    _root_ide_package_.com.cbkres.visavole.ui.VisaDialog(
        onDismissRequest = onDismiss,
        width = 400.dp,
        maxHeight = (LocalConfiguration.current.screenHeightDp - 96).dp,
        dismissable = false,
    ) {
        val docDialogScroll = rememberScrollState()
        val (docTop, docEnd) = docDialogScroll.hazeAlphas()
        _root_ide_package_.com.cbkres.visavole.ui.HazeBox(
            docTop,
            docEnd,
            MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth()
                .heightIn(max = (LocalConfiguration.current.screenHeightDp - 96).dp)
        ) {
            Column(Modifier.padding(20.dp).verticalScroll(docDialogScroll)) {
                Text(
                    if (initial == null) "Add document" else "Edit document",
                    style = MaterialTheme.typography.titleLarge
                )
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DocType.entries.forEach { t ->
                        FilterChip(
                            selected = docType == t,
                            enabled = !(visaMulti && t != DocType.VISA),
                            onClick = {
                                if (t != docType) {
                                    docType = t; onSwitchTab(t)
                                }
                            },
                            label = { Text(t.label) },
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))

                CountryPicker(
                    countries,
                    query,
                    { query = it },
                    selected = if (docType == DocType.PASSPORT) lastIso?.let { setOf(it) }
                        ?: emptySet() else customIso,
                    onToggle = { iso ->
                        if (docType == DocType.VISA) {
                            val next =
                                if (customIso.contains(iso)) customIso - iso else customIso + iso
                            customIso = next
                            lastIso = if (next.contains(iso)) iso else next.firstOrNull()
                        } else {
                            customIso = setOf(iso)
                            lastIso = iso
                        }
                    },
                    label = when (docType) {
                        DocType.PASSPORT -> "Passport country"
                        DocType.RESIDENCE -> "Residence country"
                        DocType.VISA -> "Add countries"
                    },
                )

                if (docType == DocType.RESIDENCE || docType == DocType.VISA) {
                    Spacer(Modifier.height(8.dp))
                    val autoTypeName = inferredHolding?.let { world.holdings[it]?.name }
                    val typeValue = when (val choice = holdingChoice) {
                        null -> autoTypeName?.let { "$it (auto)" } ?: "none (custom only)"
                        "" -> "none"
                        else -> world.holdings[choice]?.name ?: choice
                    }
                    SelectionField("Type", typeValue) { onSelected ->
                        DropdownMenuItem(
                            text = { Text("Auto — ${autoTypeName ?: "none"}") },
                            onClick = { holdingChoice = null; onSelected() },
                        )
                        DropdownMenuItem(
                            text = { Text("No known type (custom only)") },
                            onClick = { holdingChoice = ""; onSelected() },
                        )
                        world.holdingsForKind(docType.kind).forEach { h ->
                            DropdownMenuItem(
                                text = { Text(h.name) },
                                onClick = { holdingChoice = h.id; onSelected() },
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    val autoName = inferredBloc?.name
                    val blocValue = when (val choice = customBlocChoice) {
                        null -> autoName?.let { "$it (auto)" } ?: "none detected"
                        "" -> "none"
                        else -> world.regimes.firstOrNull { it.id == choice }?.name ?: choice
                    }
                    SelectionField("Mobility bloc", blocValue) { onSelected ->
                        DropdownMenuItem(
                            text = { Text("Auto — ${autoName ?: "none detected"}") },
                            onClick = { customBlocChoice = null; onSelected() },
                        )
                        DropdownMenuItem(
                            text = { Text("No mobility bloc") },
                            onClick = { customBlocChoice = ""; onSelected() },
                        )
                        world.regimes.sortedBy { it.name }.forEach { r ->
                            DropdownMenuItem(
                                text = { Text("${r.name} (${r.id})") },
                                onClick = { customBlocChoice = r.id; onSelected() },
                            )
                        }
                    }
                    if (docType == DocType.RESIDENCE) {
                        Spacer(Modifier.height(8.dp))
                        SelectionField("Residence class", residenceClass.label) { onSelected ->
                            ResidenceClass.entries.forEach { rc ->
                                DropdownMenuItem(
                                    text = { Text(rc.label) },
                                    onClick = { residenceClass = rc; onSelected() },
                                )
                            }
                        }
                    }
                    if (docType == DocType.VISA) {
                        Spacer(Modifier.height(8.dp))
                        val entryValue = when (entryType) {
                            "single" -> "Single entry"
                            "double" -> "Double entry"
                            else -> "Multiple entry"
                        }
                        SelectionField ("Entry type", entryValue) { onSelected ->
                            DropdownMenuItem(
                                text = { Text("Single entry") },
                                onClick = { entryType = "single"; onSelected() },
                            )
                            DropdownMenuItem(
                                text = { Text("Double entry") },
                                onClick = { entryType = "double"; onSelected() },
                            )
                            DropdownMenuItem(
                                text = { Text("Multiple entry") },
                                onClick = { entryType = "multiple"; onSelected() },
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                val todayMillis = remember {
                    guard.today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                }
                ValidityPeriodField(
                    validFromDate = validFromDate,
                    expiryDate = expiryDate,
                    todayMillis = todayMillis,
                    reopenToken = validityReopenToken,
                    reopenPicker = validityReopenPicker,
                    onValidFrom = { validFromDate = it },
                    onExpiry = { expiryDate = it },
                )
                Spacer(Modifier.height(20.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(12.dp))
                    Button(onClick = {
                        if ((validFromDate == null) xor (expiryDate == null)) {
                            // P3-55: exactly one date set — can't save a half validity period.
                            validityReopenPicker = if (validFromDate == null) 1 else 2
                            showValidityPairDialog = true
                        } else {
                            saveDoc(build(), false)
                        }
                    }, enabled = valid) {
                        Text(if (initial == null) "Add" else "Save")
                    }
                }
            }
        }
    }

    if (showValidityPairDialog) {
        AlertDialog(
            onDismissRequest = { showValidityPairDialog = false },
            title = { Text("Validity period") },
            text = { Text("A validity period needs both a start and an end date. Set the missing one, or clear both.") },
            confirmButton = {
                Button(onClick = {
                    validityReopenToken++
                    showValidityPairDialog = false
                }) { Text("Set both dates") }
            },
            dismissButton = {
                TextButton(onClick = {
                    validFromDate = null
                    expiryDate = null
                    showValidityPairDialog = false
                }) { Text("Clear both") }
            },
        )
    }

    multiCountryDoc?.let { doc ->
        MultiCountryConfirmDialog(
            doc = doc,
            countries = countries,
            onConfirm = {
                multiCountryDoc = null
                saveDoc(doc, true)
            },
            onDismiss = { multiCountryDoc = null },
        )
    }
    pendingMutationDoc?.let { doc ->
        PendingMutationDialog(
            warn = pendingMutationWarn,
            onConfirm = {
                pendingMutationDoc = null
                onAdd(doc)
                onDismiss()
            },
            onDismiss = { pendingMutationDoc = null },
        )
    }
    blocked?.let { finding ->
        BlockedFindingDialog(
            finding = finding,
            hasDuplicate = blockedDuplicate != null,
            onConfirm = {
                val dup = blockedDuplicate
                blocked = null
                if (dup != null) onEditExisting(dup)
            },
            onDismiss = { blocked = null },
            onCancel = {
                blocked = null
                onDismiss()
            },
        )
    }
}

/** The validity-period button and its two chained pickers (valid-from → expiry). */
@Composable
private fun ValidityPeriodField(
    validFromDate: Long?,
    expiryDate: Long?,
    todayMillis: Long,
    reopenToken: Int = 0,
    reopenPicker: Int = 1,
    onValidFrom: (Long?) -> Unit,
    onExpiry: (Long?) -> Unit,
) {
    val validityLabel = when {
        validFromDate != null || expiryDate != null ->
            "Validity: ${validFromDate?.let { it.toUtcIsoDate() } ?: "…"} → ${expiryDate?.let { it.toUtcIsoDate() } ?: "…"}"
        else -> "Validity period (optional)"
    }
    var showValidFromPicker by remember { mutableStateOf(false) }
    var showValidToPicker by remember { mutableStateOf(false) }
    // P3-55: when the parent bumps [reopenToken], open the requested picker so a half-set validity
    // can be completed without hunting for the field again.
    LaunchedEffect(reopenToken) {
        if (reopenToken != 0) {
            showValidFromPicker = reopenPicker == 1
            showValidToPicker = reopenPicker != 1
        }
    }
    // Bound the pickers to a coherent validity period: the start may not come after the end,
    // and a document being (re)dated may not already be expired.
    val today = todayMillis.toUtcIsoDate().let(LocalDate::parse)
    val validFrom = validFromDate?.toUtcIsoDate()?.let(LocalDate::parse)
    val expiry = expiryDate?.toUtcIsoDate()?.let(LocalDate::parse)
    OutlinedButton(
        onClick = { showValidFromPicker = true },
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(Icons.Filled.DateRange, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(validityLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (showValidFromPicker) {
        _root_ide_package_.com.cbkres.visavole.ui.VisaDatePickerDialog(
            initialMillis = validFromDate ?: todayMillis,
            confirmLabel = "Next",
            onConfirm = { date ->
                onValidFrom(date?.toUtcMillis())
                showValidFromPicker = false
                showValidToPicker = true
            },
            onDismiss = { showValidFromPicker = false; showValidToPicker = false },
            secondaryLabel = "Clear",
            onSecondary = {
                onValidFrom(null)
                showValidFromPicker = false
                showValidToPicker = true
            },
            // The validity start may not come after the (already set) end.
            maxDate = expiry,
        )
    }
    if (showValidToPicker) {
        val fallbackTo = maxOf(validFromDate ?: todayMillis, todayMillis)
        _root_ide_package_.com.cbkres.visavole.ui.VisaDatePickerDialog(
            initialMillis = expiryDate ?: fallbackTo,
            confirmLabel = "Done",
            onConfirm = { date ->
                onExpiry(date?.toUtcMillis())
                showValidToPicker = false
            },
            onDismiss = { showValidToPicker = false },
            secondaryLabel = "Clear",
            onSecondary = {
                onExpiry(null)
                showValidToPicker = false
            },
            // The expiry may not precede the start nor already be in the past.
            minDate = if (validFrom != null && validFrom.isAfter(today)) validFrom else today,
        )
    }
}

/** Confirms saving a visa that covers several countries at once. */
@Composable
private fun MultiCountryConfirmDialog(
    doc: Document,
    countries: Map<String, Country>,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val names = (doc.kind as? DocKind.Custom)?.countries?.sortedBy { countries[it]?.name ?: it } ?: emptyList()
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Save anyway") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Back") }
        },
        text = {
            val warn = severityColor(
                Severity.WARN,
                LocalEffectiveDarkTheme.current
            )
            Text(
                buildAnnotatedString {
                    append("You have multiple countries selected for this visa: ")
                    names.forEachIndexed { index, iso ->
                        withStyle(SpanStyle(color = warn, fontWeight = FontWeight.SemiBold)) {
                            append(countries[iso]?.name ?: iso)
                        }
                        if (index != names.lastIndex) append(", ")
                    }
                    append(". Are you sure you want to save?")
                },
            )
        },
    )
}

/** Confirms an accompanying mutation (e.g. expiring a superseded document) that a save implies. */
@Composable
private fun PendingMutationDialog(
    warn: GuardFinding?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Confirm") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Back") }
        },
        title = { Text(warn?.title ?: "Heads up") },
        text = { Text(warn?.message ?: "This action will also change another of your documents.") },
    )
}

/** Shows a blocking guard finding; when the block is a duplicate, offers to jump to the existing doc. */
@Composable
private fun BlockedFindingDialog(
    finding: GuardFinding,
    hasDuplicate: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {
            if (hasDuplicate) onCancel()
            onDismiss()
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(if (hasDuplicate) "Edit existing" else "Got it") }
        },
        dismissButton = {
            if (hasDuplicate) {
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        },
        title = { Text(finding.title) },
        text = { Text(finding.message) },
    )
}