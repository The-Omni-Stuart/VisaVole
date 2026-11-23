package com.cbkres.visavole.ui.screens.documents

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cbkres.visavole.domain.DocKind
import com.cbkres.visavole.domain.DocStatus
import com.cbkres.visavole.domain.Document
import com.cbkres.visavole.domain.GuardAction
import com.cbkres.visavole.domain.GuardContext
import com.cbkres.visavole.domain.GuardEngine
import com.cbkres.visavole.domain.GuardFinding
import com.cbkres.visavole.domain.GuardResult
import com.cbkres.visavole.domain.docCategory
import com.cbkres.visavole.domain.passportCountsByIso
import com.cbkres.visavole.domain.passportNumbers
import com.cbkres.visavole.ui.AccessViewModel
import com.cbkres.visavole.ui.HazeBox
import com.cbkres.visavole.ui.StatusPill
import com.cbkres.visavole.ui.animateItemToCenter
import com.cbkres.visavole.ui.app.AppState
import com.cbkres.visavole.ui.components.statuscard.AllowanceFocus
import com.cbkres.visavole.ui.components.statuscard.FlashTrigger
import com.cbkres.visavole.ui.components.statuscard.StatusCard
import com.cbkres.visavole.ui.docTitle
import com.cbkres.visavole.ui.docTypeBadge
import com.cbkres.visavole.ui.screens.AddFab
import com.cbkres.visavole.ui.screens.EmptyState
import com.cbkres.visavole.ui.screens.ScreenHeader
import com.cbkres.visavole.ui.screens.SectionLabel
import com.cbkres.visavole.ui.screens.map.LocalEffectiveDarkTheme
import com.cbkres.visavole.ui.VoleAlertDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.time.Duration.Companion.milliseconds

/** 24 months in days — a passport / residence enters the top display this close to its expiry. */
private const val EXPIRY_WATCH_DAYS = 730

@Composable
internal fun DocumentsScreen(
    vm: AccessViewModel,
    ready: AppState.Ready,
    scrollState: LazyListState,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var showAdd by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf<Document?>(null) }
    var blockedRemove by remember { mutableStateOf<GuardFinding?>(null) }
    var removingDoc by remember { mutableStateOf<Document?>(null) }
    // Shared tab-top display state (the same pair the Trips tab uses): which document's allowance
    // the top display is focused on (null = the primary one), and the ring-tap flash counters —
    // each tap bumps the card's counter, which makes that card's VisaListCard play its one-shot
    // "look here" ripple.
    val focus = remember { AllowanceFocus() }
    val flashes = remember { FlashTrigger() }
    // The document list's viewport height, in px — lets a tapped ring centre its card on screen.
    var listViewportPx by remember { mutableIntStateOf(0) }
    val today = ready.today
    // One shared guard context for the whole screen; every add/remove/star decision below goes
    // through the same GuardEngine the ViewModel backstops with.
    val guardCtx = remember(ready.docs, ready.trips, ready.world, ready.starredDocId, today) {
        GuardContext.of(ready.docs, ready.trips, ready.world, ready.starredDocId, today)
    }
    val removeBlockings = ready.removeBlockings
    // Card callbacks read the blockings through this so their identities stay stable across
    // publishes (a fresh per-publish map in a captured lambda would force every card to recompose).
    val blockings by rememberUpdatedState(removeBlockings)
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        ScreenHeader(
            "Documents",
            "Manage your passports and permits, unlocking your map."
        )
        Box(Modifier.weight(1f)) {
        if (ready.docs.isEmpty()) {
            EmptyState("No documents yet. Start with a passport or a residence permit.")
        } else {
            val statuses = ready.statuses
            val active = ready.docs.filter { !statuses.getValue(it.id).expired }
            val archived = ready.docs.filter { statuses.getValue(it.id).expired }
            // Every card's position in the LazyColumn below, keyed by doc id: the allowances item
            // sits at 0, then each non-empty section adds a header before its cards. A ring tap
            // centres its card from here instead of re-deriving the header offsets by hand.
            val docIndex = remember(active, archived) {
                val m = HashMap<String, Int>()
                var i = 1
                if (active.isNotEmpty()) {
                    i += 1
                    active.forEachIndexed { n, d -> m[d.id] = i + n }
                    i += active.size
                }
                if (archived.isNotEmpty()) {
                    i += 1
                    archived.forEachIndexed { n, d -> m[d.id] = i + n }
                }
                m
            }
            // The tab-top allowance display (the same one the Trips tab uses), fed from the
            // documents that actually have an expiry to watch: every visa, plus passports /
            // residence permits with under 24 months left.
            // remember the per-publish filters — docAllowances is O(allowances × docs) and
            // these used to re-run on every recomposition, i.e. every scroll frame via.
            val docAllowances = remember(ready.allowances, ready.docs, ready.world) {
                ready.allowances.filter { a ->
                    val doc = ready.docs.firstOrNull { it.id == a.key.removePrefix("doc:") } ?: return@filter false
                    when {
                        doc.kind is DocKind.Passport -> (a.remainingDays ?: Int.MAX_VALUE) < EXPIRY_WATCH_DAYS
                        doc.docCategory(ready.world) == "visa" -> a.remainingDays != null
                        else -> (a.remainingDays ?: Int.MAX_VALUE) < EXPIRY_WATCH_DAYS
                    }
                }
            }
            // Effective focus + primary display — the dead-key fallback and the urgency pick are
            // AllowanceFocus's rules, shared with the Trips tab.
            val effectiveFocusAllowance = focus.effectiveFocusKey(docAllowances)
            val docPrimary = focus.primaryAllowance(docAllowances)
            // How many VALID (non-expired) passports the user holds of each nationality. The primary
            // star only makes sense when two or more valid passports of the same country compete.
            val validPassportIsoCounts = remember(active) {
                active
                    .filter { it.kind is DocKind.Passport }
                    .groupingBy { (it.kind as DocKind.Passport).iso2 }
                    .eachCount()
            }
            // A stable "(N)" number for every passport: its 1-based position within its nationality,
            // in insertion order (the order documents were added). Numbering all passports — valid and
            // expired alike — keeps each one identifiable (e.g. still "(2)") even after it lapses, at
            // which point its expiry date further distinguishes it.
            val passportIsoTotal = remember(ready.docs) { passportCountsByIso(ready.docs) }
            val passportNumber = remember(ready.docs) { passportNumbers(ready.docs) }
            val docsListState = scrollState
            // State-taking overload: the per-frame haze read stays inside HazeBox, so scrolling
            // recomposes only the haze edges — not the whole screen and every visible card.
            HazeBox(
                docsListState,
                MaterialTheme.colorScheme.background,
                modifier = Modifier.fillMaxSize()
            ) {
                LazyColumn(
                    state = docsListState,
                    modifier = Modifier
                        .fillMaxSize()
                        .onSizeChanged { listViewportPx = it.height },
                    contentPadding = PaddingValues(top = 12.dp, bottom = 66.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item(key = "allowances") {
                        StatusCard(
                            allowances = docAllowances,
                            primary = docPrimary,
                            focusedKey = effectiveFocusAllowance,
                            onFocus = focus::toggle,
                            titleRow = { a ->
                                val doc =
                                    ready.docs.firstOrNull { d -> d.id == a.key.removePrefix("doc:") }
                                if (doc != null) {
                                    // The same badge the card wears: type pill + country name.
                                    val (label, tone) = docTypeBadge(
                                        doc,
                                        ready.world,
                                        LocalEffectiveDarkTheme.current
                                    )
                                    StatusPill(
                                        label,
                                        tone
                                    )
                                    Text(
                                        docTitle(
                                            doc,
                                            ready.world
                                        ),
                                        style = MaterialTheme.typography.titleMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                } else {
                                    Text(
                                        a.title,
                                        style = MaterialTheme.typography.titleMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            },
                            onRingClick = { snap ->
                                // Jump the list to the tapped document, centre it, and flash the
                                // card so the landing is obvious.
                                val doc =
                                    ready.docs.firstOrNull { d -> d.id == snap.key.removePrefix("doc:") }
                                if (doc != null) {
                                    val index = docIndex[doc.id]
                                    if (index != null) {
                                        // Fire the flash only once the centring scroll has settled, so it
                                        // lands on the card wherever it sits in the list — not while the
                                        // card is still off-screen mid-scroll. Clear the trigger once
                                        // the ripple has had time to play (~600 ms: a 300 ms held press
                                        // then a 300 ms release fade) — FlashTrigger.expire only
                                        // clears if no newer tap took over, so a card re-entering the
                                        // composition never replays a spent flash.
                                        scope.launch {
                                            docsListState.animateItemToCenter(index, listViewportPx)
                                            val n = flashes.trigger(doc.id)
                                            delay(900.milliseconds)
                                            flashes.expire(doc.id, n)
                                        }
                                    }
                                }
                            },
                        )
                    }
                    if (active.isNotEmpty()) {
                        item(key = "header-active") {
                            SectionLabel(
                                "Active"
                            )
                        }
                        items(active, key = { it.id }) { doc ->
                            val sameNatCount = (doc.kind as? DocKind.Passport)?.iso2
                                ?.let { validPassportIsoCounts[it] } ?: 0
                            // Callback identities pinned per document so cards whose doc did not
                            // change can skip recomposition when the screen republishes.
                            DocumentListCard(
                                doc,
                                ready,
                                today,
                                statuses,
                                removeBlockings,
                                flashes,
                                passportIsoTotal,
                                passportNumber,
                                star = if (doc.kind is DocKind.Passport && sameNatCount >= 2) {
                                    remember(doc) { { vm.setPrimary(doc.id) } }
                                } else null,
                                isPrimary = doc.id == ready.primaryDocId,
                                onRemove = remember(doc) { { removingDoc = doc } },
                                onRemoveBlocked = remember(doc) {
                                    { blockedRemove = blockings[doc.id]?.findings?.firstOrNull() }
                                },
                                onEdit = remember(doc) { { editing = doc } },
                            )
                        }
                    }
                    if (archived.isNotEmpty()) {
                        item(key = "header-archive") {
                            SectionLabel(
                                "Archive"
                            )
                        }
                        items(archived, key = { it.id }) { doc ->
                            // An expired passport can never be the primary, so it shows no star — but
                            // it keeps its stable "(N)" number so it stays identifiable after expiry.
                            DocumentListCard(
                                doc,
                                ready,
                                today,
                                statuses,
                                removeBlockings,
                                flashes,
                                passportIsoTotal,
                                passportNumber,
                                star = null,
                                isPrimary = false,
                                onRemove = remember(doc) { { removingDoc = doc } },
                                onRemoveBlocked = remember(doc) {
                                    { blockedRemove = blockings[doc.id]?.findings?.firstOrNull() }
                                },
                                onEdit = remember(doc) { { editing = doc } },
                            )
                        }
                    }
                }
            }
        }
        AddFab("Add document", Modifier.align(Alignment.BottomEnd)) { showAdd = true }
        }
    }
    blockedRemove?.let { finding ->
        VoleAlertDialog(
            title = finding.title,
            confirmLabel = "OK",
            onConfirm = { blockedRemove = null },
            dismissLabel = null,
            onDismiss = { blockedRemove = null },
            content = { Text(finding.message) },
        )
    }
    removingDoc?.let { doc ->
        VoleAlertDialog(
            title = "Delete document",
            confirmLabel = "Delete",
            confirmDestructive = true,
            onConfirm = { vm.removeDocument(doc.id); removingDoc = null },
            onDismiss = { removingDoc = null },
            content = { Text("Delete this document? The map's access will be recalculated.") },
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
    // Keyed on the id: the duplicate remedy swaps the edited document in place ("Edit existing"),
    // and the dialog's form state must not carry from one document to the next.
    key(editing?.id) {
        if (editing != null) {
            AddDocumentDialog(
                world = ready.world,
                guard = guardCtx,
                initial = editing,
                onAdd = { vm.addDocument(it) },
                onDismiss = { editing = null },
                onEditExisting = { editing = it },
            )
        }
    }
}

/** One document row, shared by the Active and Archive sections; the sections differ only in the
 *  primary star and its callback, which the caller supplies already pinned per document. */
@Composable
private fun DocumentListCard(
    doc: Document,
    ready: AppState.Ready,
    today: LocalDate,
    statuses: Map<String, DocStatus>,
    removeBlockings: Map<String, GuardResult>,
    flashes: FlashTrigger,
    passportIsoTotal: Map<String, Int>,
    passportNumber: Map<String, Int>,
    star: (() -> Unit)?,
    isPrimary: Boolean,
    onRemove: () -> Unit,
    onRemoveBlocked: () -> Unit,
    onEdit: () -> Unit,
) {
    val iso = (doc.kind as? DocKind.Passport)?.iso2
    val number = iso?.let {
        if ((passportIsoTotal[it] ?: 0) >= 2) passportNumber[doc.id] else null
    }
    DocumentCard(
        doc,
        ready.world,
        today,
        statuses[doc.id],
        supersededByLabel = doc.supersededBy?.let { id ->
            ready.docs.firstOrNull { d -> d.id == id }?.let { docTitle(it, ready.world) }
        },
        canRemove = removeBlockings[doc.id]?.canProceed == true,
        passportNumber = number,
        isPrimary = isPrimary,
        onRemove = onRemove,
        onRemoveBlocked = onRemoveBlocked,
        onEdit = onEdit,
        onStar = star,
        flashTrigger = flashes.countFor(doc.id),
    )
}
