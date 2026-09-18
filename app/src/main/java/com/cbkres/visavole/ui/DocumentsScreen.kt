package com.cbkres.visavole.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.cbkres.visavole.data.Country
import com.cbkres.visavole.data.WorldData
import com.cbkres.visavole.domain.Document
import com.cbkres.visavole.domain.DocKind
import com.cbkres.visavole.domain.DocStatus
import com.cbkres.visavole.domain.EntryState
import com.cbkres.visavole.domain.ExpiryReason
import com.cbkres.visavole.domain.GuardAction
import com.cbkres.visavole.domain.GuardContext
import com.cbkres.visavole.domain.GuardEngine
import com.cbkres.visavole.domain.GuardFinding
import com.cbkres.visavole.domain.GuardResult
import com.cbkres.visavole.domain.ResidenceClass
import com.cbkres.visavole.domain.defaultResidenceClassFor
import com.cbkres.visavole.domain.duplicateSignature
import com.cbkres.visavole.domain.passportCountsByIso
import com.cbkres.visavole.domain.passportDocument
import com.cbkres.visavole.domain.passportNumbers
import com.cbkres.visavole.domain.residenceClassFor
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Days from [today] until [date]; null when unset. */
private fun daysUntil(date: LocalDate?, today: LocalDate): Long? =
    date?.let { ChronoUnit.DAYS.between(today, it) }

/**
 * The document subtitle: kind/summary and the valid-from / valid-to dates in the neutral base
 * colour; only the entry type is tinted (orange for single, green for multiple). The expiry
 * status itself is carried by a separate pill in the row.
 */
private fun docSubtitle(
    doc: Document,
    world: WorldData,
    base: Color,
    effectiveExpiry: LocalDate? = null,
): AnnotatedString {
    val k = doc.kind
    val regime = (k as? DocKind.Custom)?.blocId?.let { id -> world.regimes.firstOrNull { r -> r.id == id }?.name }
    val entryType = (k as? DocKind.Custom)?.entryType
    val classSuffix = doc.residenceClassFor(world)?.let { " · ${it.label}" } ?: ""
    val displayExpiry = effectiveExpiry?.toString() ?: doc.expiry

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
        val (label, severity) = when (type) {
            "single" -> "single" to Severity.BAD
            "double" -> "double" to Severity.WARN
            else -> "multiple" to Severity.OK
        }
        styled(" · $label entry", severityColor(severity))
    }
    doc.validFrom?.let { from -> styled(" · from $from", base) }
    displayExpiry?.let { exp -> styled(" · to $exp", base) }
    return b.toAnnotatedString()
}

@Composable
fun DocumentsScreen(
    vm: AccessViewModel,
    ready: AppState.Ready,
    scrollState: LazyListState,
    modifier: Modifier = Modifier,
) {
    var showAdd by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Document?>(null) }
    var blockedRemove by remember { mutableStateOf<GuardFinding?>(null) }
    var removingDoc by remember { mutableStateOf<Document?>(null) }
    val today = ready.today
    // One shared guard context for the whole screen; every add/remove/star decision below goes
    // through the same GuardEngine the ViewModel backstops with.
    val guardCtx = remember(ready.docs, ready.trips, ready.world, ready.starredDocId, today) {
        GuardContext.of(ready.docs, ready.trips, ready.world, ready.starredDocId, today)
    }
    val removeBlockings: Map<String, GuardResult> = remember(guardCtx) {
        ready.docs.associateBy({ it.id }, { d -> GuardEngine.evaluate(GuardAction.RemoveDocument(d.id), guardCtx) })
    }
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        ScreenHeader("Documents", "Add the passport and papers you hold — the map updates to match.")
        AddButtonRow("Add document") { showAdd = true }
        Spacer(Modifier.height(8.dp))
        if (ready.docs.isEmpty()) {
            EmptyState("No documents yet. Start with a passport or a residence permit.")
        } else {
            // Unified validity for every document (date expiry, entry exhaustion, supersession).
            val statuses = remember(ready.docs, ready.trips, ready.world, today) {
                DocStatus.all(ready.docs, ready.trips, ready.world, today)
            }
            val active = ready.docs.filter { !statuses.getValue(it.id).expired }
            val archived = ready.docs.filter { statuses.getValue(it.id).expired }
            // How many VALID (non-expired) passports the user holds of each nationality. The primary
            // star only makes sense when two or more valid passports of the same country compete.
            val validPassportIsoCounts = active
                .filter { it.kind is DocKind.Passport }
                .groupingBy { (it.kind as DocKind.Passport).iso2 }
                .eachCount()
            // A stable "(N)" number for every passport: its 1-based position within its nationality,
            // in insertion order (the order documents were added). Numbering all passports — valid and
            // expired alike — keeps each one identifiable (e.g. still "(2)") even after it lapses, at
            // which point its expiry date further distinguishes it.
            val passportIsoTotal = remember(ready.docs) { passportCountsByIso(ready.docs) }
            val passportNumber = remember(ready.docs) { passportNumbers(ready.docs) }
            val docsListState = scrollState
            val (docsTop, docsEnd) = docsListState.hazeAlphas()
            HazeBox(docsTop, docsEnd, MaterialTheme.colorScheme.background, modifier = Modifier.weight(1f)) {
                LazyColumn(state = docsListState, modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (active.isNotEmpty()) {
                        item(key = "header-active") { SectionLabel("Active") }
                        items(active, key = { it.id }) { doc ->
                            val iso = (doc.kind as? DocKind.Passport)?.iso2
                            val sameNatCount = iso?.let { validPassportIsoCounts[it] ?: 0 } ?: 0
                            val star: (() -> Unit)? =
                                if (doc.kind is DocKind.Passport && sameNatCount >= 2) { { vm.setPrimary(doc.id) } } else null
                            val number = iso?.let { if ((passportIsoTotal[it] ?: 0) >= 2) passportNumber[doc.id] else null }
                            DocCard(
                                doc,
                                ready.world,
                                today,
                                statuses[doc.id],
                                supersededByLabel = doc.supersededBy?.let { id -> ready.docs.firstOrNull { d -> d.id == id }?.label },
                                canRemove = removeBlockings[doc.id]?.canProceed == true,
                                passportNumber = number,
                                isPrimary = doc.id == ready.primaryDocId,
                                onRemove = { removingDoc = doc },
                                onRemoveBlocked = { blockedRemove = removeBlockings[doc.id]?.findings?.firstOrNull() },
                                onEdit = { editing = doc },
                                onStar = star,
                            )
                        }
                    }
                    if (archived.isNotEmpty()) {
                        item(key = "header-archive") { SectionLabel("Archive") }
                        items(archived, key = { it.id }) { doc ->
                            // An expired passport can never be the primary, so it shows no star — but
                            // it keeps its stable "(N)" number so it stays identifiable after expiry.
                            val iso = (doc.kind as? DocKind.Passport)?.iso2
                            val number = iso?.let { if ((passportIsoTotal[it] ?: 0) >= 2) passportNumber[doc.id] else null }
                            DocCard(
                                doc,
                                ready.world,
                                today,
                                statuses[doc.id],
                                supersededByLabel = doc.supersededBy?.let { id -> ready.docs.firstOrNull { d -> d.id == id }?.label },
                                canRemove = removeBlockings[doc.id]?.canProceed == true,
                                passportNumber = number,
                                isPrimary = false,
                                onRemove = { removingDoc = doc },
                                onRemoveBlocked = { blockedRemove = removeBlockings[doc.id]?.findings?.firstOrNull() },
                                onEdit = { editing = doc },
                                onStar = null,
                            )
                        }
                    }
                }
            }
        }
    }
    blockedRemove?.let { finding ->
        AlertDialog(
            onDismissRequest = { blockedRemove = null },
            title = { Text(finding.title) },
            text = { Text(finding.message) },
            confirmButton = { TextButton(onClick = { blockedRemove = null }) { Text("OK") } },
        )
    }
    removingDoc?.let { doc ->
        AlertDialog(
            onDismissRequest = { removingDoc = null },
            title = { Text("Delete document") },
            text = { Text("Delete this document? The map's access will be recalculated.") },
            confirmButton = {
                Button(
                    onClick = { vm.removeDocument(doc.id); removingDoc = null },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { removingDoc = null }) { Text("Cancel") }
            },
        )
    }
    if (showAdd) {
        AddDocumentDialog(
            world = ready.world,
            guard = guardCtx,
            onAdd = { vm.addDocument(it) },
            onDismiss = { showAdd = false },
            onEditExisting = { showAdd = false; editing = it },
        )
    }
    if (editing != null) {
        AddDocumentDialog(
            world = ready.world,
            guard = guardCtx,
            initial = editing,
            onAdd = { vm.updateDocument(it) },
            onDismiss = { editing = null },
            onEditExisting = { editing = it },
        )
    }
}

/**
 * The per-document status pill, driven by the unified [DocStatus]: the reason (date, entries, or
 * supersession) attached to the effective expiry decides the text — "Expired on …" / "Entries used
 * up" / "Replaced by …" — so a lapsed document always says *why* it is in the archive.
 */
@Composable
private fun ExpiryStatusPill(
    today: LocalDate,
    status: DocStatus?,
    supersededByLabel: String? = null,
) {
    val effective = status?.effectiveExpiry
    val entry = status?.entries
    val days = daysUntil(effective, today)
    val replacedBy = supersededByLabel ?: "a newer document"
    val (text, severity, bold) = when {
        entry?.total != null && entry.state == EntryState.IN_USE -> Triple("In use", Severity.OK, false)
        status?.reason == ExpiryReason.SUPERSEDED && status.expired ->
            Triple("Replaced by $replacedBy", Severity.BAD, true)
        status?.expired == true && status.reason == ExpiryReason.ENTRIES ->
            Triple("Entries used up", Severity.BAD, true)
        status?.expired == true -> Triple("Expired on $effective", Severity.BAD, true)
        status?.reason == ExpiryReason.SUPERSEDED ->
            Triple("Replaced by $replacedBy from $effective", Severity.WARN, false)
        entry?.total != null && entry.remaining != null && entry.remaining > 0 ->
            Triple("${entry.remaining} ${if (entry.remaining == 1) "entry" else "entries"} left", Severity.OK, false)
        days == null -> Triple("No expiry", Severity.OK, false)
        days <= 7 -> Triple("Expiring soon ($days days)", Severity.WARN, false)
        else -> Triple("Valid for $days days", Severity.OK, false)
    }
    // Long pills (e.g. "Replaced by <label>") must wrap instead of squeezing the card's weighted
    // text column; the cap keeps the title column readable for the longest labels.
    StatusPill(text, severityColor(severity), bold = bold, maxWidth = 120.dp)
}

@Composable
private fun DocCard(
    doc: Document,
    world: WorldData,
    today: LocalDate,
    status: DocStatus? = null,
    supersededByLabel: String? = null,
    canRemove: Boolean = true,
    passportNumber: Int? = null,
    isPrimary: Boolean = false,
    onRemove: () -> Unit,
    onRemoveBlocked: () -> Unit = {},
    onEdit: () -> Unit,
    onStar: (() -> Unit)? = null,
) {
    val effectiveExpiry = status?.effectiveExpiry
    val isPassport = doc.kind is DocKind.Passport
    val surface = MaterialTheme.colorScheme.onSurface
    VisaListCard(
        actions = {
            if (onStar != null) {
                IconButton(onClick = onStar) {
                    Icon(
                        Icons.Filled.Star,
                        contentDescription = if (isPrimary) "Primary passport" else "Set as primary",
                        // material-icons-core has no true Star outline (bug), so the non-primary
                        // star is the same solid star in a faded grey to stay monochrome.
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (isPrimary) 1f else DIM_ALPHA),
                    )
                }
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Filled.Edit, contentDescription = "Edit")
            }
            IconButton(
                onClick = { if (canRemove) onRemove() else onRemoveBlocked() },
                modifier = Modifier.alpha(if (canRemove) 1f else DIM_ALPHA),
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = if (canRemove) "Remove" else "In use by a trip",
                    tint = if (canRemove) surface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (status != null &&
                (effectiveExpiry != null || status.entries?.total != null || doc.supersededBy != null)
            ) {
                ExpiryStatusPill(today, status, supersededByLabel)
            }
            Text(
                buildAnnotatedString {
                    append(doc.label)
                    if (isPassport && passportNumber != null) {
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) {
                            append("  ($passportNumber)")
                        }
                    }
                },
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            docSubtitle(doc, world, MaterialTheme.colorScheme.onSurfaceVariant, effectiveExpiry),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private enum class DocType(val label: String, val kind: String) {
    PASSPORT("Passport", "passport"),
    RESIDENCE("Residence", "residence"),
    VISA("Visa", "visa"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SelectionField(
    label: String,
    value: String,
    items: @Composable ColumnScope.(onSelected: () -> Unit) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            singleLine = true,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            shape = MaterialTheme.shapes.large,
        ) {
            items({ expanded = false })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddDocumentDialog(
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

    val valid = when {
        docType == DocType.PASSPORT -> lastIso != null && customIso.isNotEmpty()
        initHolding != null && customIso.isEmpty() -> docType == DocType.RESIDENCE && holdingChoice != null
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
                Document(id, label, DocKind.Holding(h), null, exp, vfrom, residenceClass)
            }
            else -> {
                val names = customIso.joinToString(", ") { countries[it]?.name ?: it }.take(80)
                val typeSuffix = effectiveHolding?.let { hid -> " (${world.holdings[hid]?.name ?: hid})" } ?: ""
                val entry = if (docType == DocType.VISA) entryType else null
                val rc = if (docType == DocType.RESIDENCE) residenceClass else null
                Document(id, "${docType.label}: $names$typeSuffix", DocKind.Custom(customIso, effectiveBloc, docType.kind, effectiveHolding, entry), null, exp, vfrom, rc)
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

    VisaDialog(
        onDismissRequest = onDismiss,
        width = 400.dp,
        maxHeight = (LocalConfiguration.current.screenHeightDp - 96).dp,
        dismissable = false,
    ) {
            val docDialogScroll = rememberScrollState()
            val (docTop, docEnd) = docDialogScroll.hazeAlphas()
            HazeBox(docTop, docEnd, MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp - 96).dp)) {
                Column(Modifier.padding(20.dp).verticalScroll(docDialogScroll)) {
                    Text(if (initial == null) "Add document" else "Edit document", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DocType.entries.forEach { t ->
                        FilterChip(
                            selected = docType == t,
                            enabled = !(visaMulti && t != DocType.VISA),
                            onClick = { if (t != docType) { docType = t; onSwitchTab(t) } },
                            label = { Text(t.label) },
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))

                CountryPicker(
                    countries,
                    query,
                    { query = it },
                    selected = if (docType == DocType.PASSPORT) lastIso?.let { setOf(it) } ?: emptySet() else customIso,
                    onToggle = { iso ->
                        if (docType == DocType.VISA) {
                            val next = if (customIso.contains(iso)) customIso - iso else customIso + iso
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
                            SelectionField("Entry type", entryValue) { onSelected ->
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
                    onValidFrom = { validFromDate = it },
                    onExpiry = { expiryDate = it },
                )
                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    OutlinedButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(12.dp))
                    Button(onClick = { saveDoc(build(), false) }, enabled = valid) {
                        Text(if (initial == null) "Add" else "Save")
                    }
                }
            }
            }
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
        VisaDatePickerDialog(
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
        )
    }
    if (showValidToPicker) {
        val fallbackTo = maxOf(validFromDate ?: todayMillis, todayMillis)
        VisaDatePickerDialog(
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
            Text(
                buildAnnotatedString {
                    append("You have multiple countries selected for this visa: ")
                    names.forEachIndexed { index, iso ->
                        withStyle(SpanStyle(color = severityColor(Severity.WARN), fontWeight = FontWeight.SemiBold)) {
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

/** Search + list of countries; tap toggles membership in [selected]. */
@Composable
internal fun CountryPicker(
    countries: Map<String, Country>,
    query: String,
    onQuery: (String) -> Unit,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    label: String,
) {
    val listState = rememberLazyListState()
    val q = query.trim().lowercase()
    val list = countries.entries
        .filter { q.isEmpty() || it.value.name.lowercase().contains(q) || it.key.lowercase() == q }
        .sortedBy { it.value.name }
    LaunchedEffect(list, selected) {
        val selectedIndex = list.indexOfFirst { selected.contains(it.key) }
        if (selectedIndex < 0) return@LaunchedEffect
        // Scroll only when the selection is off-screen (e.g. opening in edit mode). Tapping an
        // already-visible country must not yank it to the top of the list.
        val visible = listState.layoutInfo.visibleItemsInfo
        if (visible.none { it.index == selectedIndex }) listState.scrollToItem(selectedIndex)
    }
    Column {
        OutlinedTextField(
            value = query, onValueChange = onQuery,
            label = { Text(label) },
            singleLine = true,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Surface(
            modifier = Modifier.fillMaxWidth().height(180.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            val (pickerTop, pickerEnd) = listState.hazeAlphas()
            HazeBox(pickerTop, pickerEnd, MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxSize()) {
                LazyColumn(state = listState, modifier = Modifier.padding(4.dp)) {
                    items(list, key = { it.key }) { entry ->
                    CountryRow(
                        name = entry.value.name,
                        onClick = { onToggle(entry.key) },
                        iso = entry.key,
                        selected = selected.contains(entry.key),
                    )
                }
                }
            }
        }
    }
}
