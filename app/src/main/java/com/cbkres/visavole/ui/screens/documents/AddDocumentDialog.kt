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
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cbkres.visavole.domain.DocDraft
import com.cbkres.visavole.domain.DocKind
import com.cbkres.visavole.domain.DocType
import com.cbkres.visavole.domain.Document
import com.cbkres.visavole.domain.GuardAction
import com.cbkres.visavole.domain.GuardContext
import com.cbkres.visavole.domain.GuardEngine
import com.cbkres.visavole.domain.GuardFinding
import com.cbkres.visavole.domain.Regime
import com.cbkres.visavole.domain.ResidenceClass
import com.cbkres.visavole.domain.WorldData
import com.cbkres.visavole.domain.buildDocument
import com.cbkres.visavole.domain.defaultResidenceClassFor
import com.cbkres.visavole.domain.duplicateSignature
import com.cbkres.visavole.domain.initialTabFor
import com.cbkres.visavole.ui.HazeBox
import com.cbkres.visavole.ui.VisaDialog
import com.cbkres.visavole.ui.components.forms.CountryPicker
import com.cbkres.visavole.ui.components.forms.SelectionField
import com.cbkres.visavole.ui.hazeAlphas
import com.cbkres.visavole.ui.toUtcIsoDate
import com.cbkres.visavole.ui.toUtcMillis
import com.cbkres.visavole.ui.GuardFindingsDialog
import com.cbkres.visavole.ui.VoleAlertDialog
import java.util.UUID

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
            initPassport?.let { DocType.PASSPORT }
                ?: initCustom?.kind?.let(::initialTabFor)
                ?: DocType.RESIDENCE,
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
    var entryType by rememberSaveable { mutableStateOf(initCustom?.entryType ?: "multiple") } // "single" | "double" | "multiple" (visa only)
    var residenceClass by rememberSaveable { mutableStateOf(initial?.residenceClass ?: ResidenceClass.TEMPORARY) } // residence only
    var residenceClassEdited by rememberSaveable { mutableStateOf(false) } // an explicit pick, vs the TEMPORARY fallback on a doc that stored no class
    var expiryDate by remember { mutableStateOf(initial?.expiry?.toUtcMillis()) }
    var validFromDate by remember { mutableStateOf(initial?.validFrom?.toUtcMillis()) }
    // a validity period is both-or-neither. When a save is attempted with exactly one date
    // set, show a fix dialog and re-open the requested picker in the field.
    var showValidityPairDialog by remember { mutableStateOf(false) }
    var validityReopenToken by remember { mutableIntStateOf(0) }
    var validityReopenPicker by remember { mutableIntStateOf(1) } // 1=valid-from, 2=expiry
    // Guard-driven dialog state: [blocked] is the blocking finding shown to the user,
    // [blockedDuplicate] the existing document an "Edit existing" button can jump to,
    // [pendingMutationDoc]/[pendingMutationWarn] the pending action + its accompanying mutation
    // awaiting confirmation, [multiCountryDoc] the visa awaiting the multi-country confirm, and
    // [saveWarnings] a pending action + the guard warnings no other confirm covered.
    var blocked by remember { mutableStateOf<GuardFinding?>(null) }
    var blockedDuplicate by remember { mutableStateOf<Document?>(null) }
    var pendingMutationDoc by remember { mutableStateOf<Document?>(null) }
    var pendingMutationWarn by remember { mutableStateOf<GuardFinding?>(null) }
    var multiCountryDoc by remember { mutableStateOf<Document?>(null) }
    var saveWarnings by remember { mutableStateOf<Pair<Document, List<GuardFinding>>?>(null) }

    // A residence/visa earns short-stay travel, not freedom of movement — infer the visa-free
    // travel bloc (e.g. Schengen for an EU residence) rather than the freedom-of-movement bloc.
    val inferredBloc = world.travelBlocFor(customIso)
    val effectiveBloc: String? = when (customBlocChoice) {
        null -> inferredBloc?.id
        "" -> null
        else -> customBlocChoice
    }
    // Infer with the kind buildDocument guards (not the tab's): a legacy "permit" opens on the
    // Visa tab, and inferring with "visa" would stamp an auto holding into holdingId on an
    // untouched edit, drifting the identity the in-use guard protects.
    val inferredKind =
        if (initCustom != null && initialTabFor(initCustom.kind) == docType) initCustom.kind else docType.kind
    val inferredHolding = world.holdingFor(customIso, inferredKind)
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
                residenceClass = defaultResidenceClassFor(effectiveHolding)
                residenceClassEdited = true
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

    fun build(): Document = buildDocument(
        DocDraft(
            id = initial?.id ?: UUID.randomUUID().toString(),
            docType = docType,
            lastIso = lastIso,
            countries = customIso,
            effectiveBloc = effectiveBloc,
            effectiveHolding = effectiveHolding,
            entryType = entryType,
            residenceClass = residenceClass,
            residenceClassEdited = residenceClassEdited,
            expiry = expiryOrNull,
            validFrom = validFromOrNull,
        ),
        initial,
        world,
    )

    // The guard's decision chain lives in [decideDocSave] (JVM-pinned by DocSaveChainTest); the
    // dialogs below only render one step and re-enter with the matching skip flag.
    fun runSave(doc: Document, skipMultiCountry: Boolean = false, skipMutation: Boolean = false) {
        when (val step = decideDocSave(doc, guard, skipMultiCountry, skipMutation)) {
            is DocSaveDecision.Blocked -> {
                blocked = step.finding
                blockedDuplicate = step.duplicate
            }
            DocSaveDecision.ConfirmMultiCountry -> multiCountryDoc = doc
            is DocSaveDecision.ConfirmMutation -> {
                pendingMutationDoc = doc
                pendingMutationWarn = step.warning
            }
            is DocSaveDecision.ShowSaveWarnings -> saveWarnings = doc to step.warnings
            is DocSaveDecision.Save -> {
                onAdd(doc)
                onDismiss()
            }
        }
    }

    VisaDialog(
        onDismissRequest = onDismiss,
        width = 400.dp,
        dismissable = false,
    ) {
        val docDialogScroll = rememberScrollState()
        val (docTop, docEnd) = docDialogScroll.hazeAlphas()
        HazeBox(
            docTop,
            docEnd,
            MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth()
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
                    // Half-width row: the db's short name (CIS, Schengen…) fits where
                    // the full name would clip; the dropdown below still spells it out.
                    fun blocLabel(r: Regime) = r.short.ifEmpty { r.name }
                    val blocValue = when (val choice = customBlocChoice) {
                        null -> autoName?.let { "$it (auto)" } ?: "none detected"
                        "" -> "none"
                        else -> world.regimes.firstOrNull { it.id == choice }?.let { blocLabel(it) } ?: choice
                    }
                    // Bloc and class/entry are short picks; sharing a row keeps the form
                    // inside one screen instead of a scroll.
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SelectionField("Mobility bloc", blocValue, Modifier.weight(1f)) { onSelected ->
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
                            SelectionField("Residence class", residenceClass.label, Modifier.weight(1f)) { onSelected ->
                                ResidenceClass.entries.forEach { rc ->
                                    DropdownMenuItem(
                                        text = { Text(rc.label) },
                                        onClick = { residenceClass = rc; residenceClassEdited = true; onSelected() },
                                    )
                                }
                            }
                        }
                        if (docType == DocType.VISA) {
                            val entryValue = when (entryType) {
                                "single" -> "Single entry"
                                "double" -> "Double entry"
                                else -> "Multiple entry"
                            }
                            SelectionField("Entry type", entryValue, Modifier.weight(1f)) { onSelected ->
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
                }

                Spacer(Modifier.height(16.dp))
                val todayMillis = remember(guard.today) { guard.today.toUtcMillis() }
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
                            // exactly one date set — can't save a half validity period.
                            validityReopenPicker = if (validFromDate == null) 1 else 2
                            showValidityPairDialog = true
                        } else {
                            runSave(build())
                        }
                    }, enabled = valid) {
                        Text(if (initial == null) "Add" else "Save")
                    }
                }
            }
        }
    }

    if (showValidityPairDialog) {
        VoleAlertDialog(
            title = "Validity period",
            confirmLabel = "Set both dates",
            onConfirm = {
                validityReopenToken++
                showValidityPairDialog = false
            },
            // "Clear both" is an action, not a dismissal: Back returns to the form.
            neutralLabel = "Clear both",
            onNeutral = {
                validFromDate = null
                expiryDate = null
                showValidityPairDialog = false
            },
            dismissLabel = null,
            onDismiss = { showValidityPairDialog = false },
            content = { Text("A validity period needs both a start and an end date. Set the missing one, or clear both.") },
        )
    }

    multiCountryDoc?.let { doc ->
        MultiCountryConfirmDialog(
            doc = doc,
            countries = countries,
            onConfirm = {
                multiCountryDoc = null
                runSave(doc, skipMultiCountry = true)
            },
            onDismiss = { multiCountryDoc = null },
        )
    }
    pendingMutationDoc?.let { doc ->
        PendingMutationDialog(
            warn = pendingMutationWarn,
            onConfirm = {
                pendingMutationDoc = null
                runSave(doc, skipMutation = true)
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
            onDiscard = {
                blocked = null
                onDismiss()
            },
        )
    }
    saveWarnings?.let { (doc, warnings) ->
        GuardFindingsDialog(
            title = "Check document",
            findings = warnings,
            confirmLabel = "Save anyway",
            dismissLabel = "Edit",
            onConfirm = {
                saveWarnings = null
                onAdd(doc)
                onDismiss()
            },
            onDismiss = { saveWarnings = null },
        )
    }
}
