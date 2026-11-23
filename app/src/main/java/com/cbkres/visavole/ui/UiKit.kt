package com.cbkres.visavole.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.cbkres.visavole.domain.AccessLevel
import com.cbkres.visavole.domain.AllowanceKind
import com.cbkres.visavole.domain.AllowanceSnapshot
import com.cbkres.visavole.domain.AllowanceStatus
import com.cbkres.visavole.domain.DocKind
import com.cbkres.visavole.domain.Document
import com.cbkres.visavole.domain.WorldData
import com.cbkres.visavole.domain.docCategory
import com.cbkres.visavole.domain.toDateOrNull
import com.cbkres.visavole.ui.screens.map.STATUS_CHIP_ALPHA
import com.cbkres.visavole.ui.screens.map.Severity
import com.cbkres.visavole.ui.screens.map.severityColor
import com.cbkres.visavole.ui.screens.map.statusTone
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

// --- Date helpers ---

/** The app's one ISO date text; everything that shows or persists a date as text goes through it. */
internal fun LocalDate.toUtcIsoDate(): String = toString()

internal fun Long.toUtcLocalDate(): LocalDate =
    Instant.ofEpochMilli(this).atOffset(ZoneOffset.UTC).toLocalDate()

internal fun Long.toUtcIsoDate(): String = toUtcLocalDate().toUtcIsoDate()

/** Spoken form for announcements, so an ISO date is not read out as digits. */
internal fun LocalDate.spoken(): String =
    "$dayOfMonth ${month.getDisplayName(java.time.format.TextStyle.FULL, Locale.ENGLISH)}"

internal fun LocalDate.toUtcMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

internal fun String.toUtcMillis(): Long? = toDateOrNull()?.toUtcMillis()

// --- Document presentational helpers (shared by the cards and the tab-top allowance display) ---

/**
 * The card's first-row type badge: the base type (Passport / Visa / Residence) in the tone the map
 * uses for what that document unlocks — the home-country blue, the covered-visa magenta, or the
 * residence teal.
 */
internal fun docTypeBadge(doc: Document, world: WorldData, isDark: Boolean): Pair<String, Color> =
    when (doc.kind) {
        is DocKind.Passport -> "Passport" to statusTone(null, isDark, isHome = true)
        else -> if (doc.docCategory(world) == "residence") {
            "Residence" to statusTone(AccessLevel.RESIDENCE, isDark)
        } else {
            "Visa" to statusTone(AccessLevel.COVERED, isDark)
        }
    }

/**
 * The card's title: the country(ies) the document applies to — a single name, "A, B" for two, or
 * "A, B +N more" beyond. A bloc-scoped holding (e.g. Schengen/EU) has no single country, so it
 * carries its holding name instead.
 */
internal fun docTitle(doc: Document, world: WorldData): String {
    val names = when (val k = doc.kind) {
        is DocKind.Passport -> listOf(world.countries[k.iso2]?.name ?: k.iso2)
        is DocKind.Holding -> {
            val isos = world.holdingCountries(k.holdingId).sortedBy { world.countries[it]?.name ?: it }
            when {
                isos.isEmpty() -> listOf(k.holdingId)
                isos.size == 1 -> listOf(world.countries[isos.first()]?.name ?: isos.first())
                else -> listOf(world.holdings[k.holdingId]?.name ?: k.holdingId)
            }
        }
        is DocKind.Custom ->
            k.countries.sortedBy { world.countries[it]?.name ?: it }.map { world.countries[it]?.name ?: it }
    }
    return if (names.size <= 2) names.joinToString(", ")
    else "${names.take(2).joinToString(", ")} +${names.size - 2} more"
}

// --- Shared visual vocabulary: one place defines the style, screens just supply content ---

/**
 * Tonal status chip — the single source of the pill's shape, tonal fill (`color` at
 * [STATUS_CHIP_ALPHA]) and text colour. Compact card rows use the defaults; the country-card
 * header passes a larger [style]/[contentPadding]/[fontWeight]. [maxWidth] lets long labels wrap
 * instead of squeezing a weighted sibling; [bold] raises weight for terminal states (e.g. expired).
 */
/** Keeps number+unit pairs together so a narrow pill never orphans the unit. */
internal fun tidyPillText(text: String): String =
    text.replace(Regex("(?<=\\d) (?=[A-Za-z])"), "\u00a0")

@Composable
internal fun StatusPill(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    bold: Boolean = false,
    maxWidth: Dp? = null,
    style: TextStyle = MaterialTheme.typography.labelSmall,
    contentPadding: PaddingValues = PaddingValues(horizontal = 8.dp, vertical = 3.dp),
    fontWeight: FontWeight? = null,
) {
    val weight = fontWeight ?: (if (bold) FontWeight.Bold else FontWeight.SemiBold)
    Surface(
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = STATUS_CHIP_ALPHA),
        modifier = modifier.then(maxWidth?.let { Modifier.widthIn(max = it) } ?: Modifier),
    ) {
        Text(
            tidyPillText(text),
            color = color,
            fontWeight = weight,
            style = style,
            modifier = Modifier.padding(contentPadding),
        )
    }
}

/** A tappable country row: optional selection tick, name (weighted), optional ISO tag. */
@Composable
internal fun CountryRow(
    name: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iso: String? = null,
    selected: Boolean = false,
    selectionRole: Role? = null,
) {
    // Rows that toggle carry their role so TalkBack says "checked", not "check mark"; plain
    // navigate-and-act rows (map search, onboarding) stay clickable.
    val stateModifier = if (selectionRole != null) Modifier.selectable(selected, role = selectionRole, onClick = onClick)
    else Modifier.clickable(onClick = onClick)
    Row(
        // minHeight keeps every country/search row on a ≥48dp touch target regardless of text style.
        modifier.fillMaxWidth().heightIn(min = 48.dp).then(stateModifier).padding(vertical = 10.dp, horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
        Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        iso?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/**
 * The shared list-card frame, derived from the trip card: an [ElevatedCard] with 16.dp padding,
 * a weighted content column (rows spaced by 8.dp) and a trailing vertical column of icon actions.
 * Screens supply their own content rows and action icons; the padding/spacing/shape live here.
 *
 * [flashTrigger] replays the SAME ripple the card body shows on a real tap whenever it changes to
 * a new non-zero value — the "look here" flash for a card the list just scrolled to. It drives the
 * theme's [LocalIndication] (the exact bounded ripple the body's [clickable] uses) from a dedicated
 * `flashSource` by emitting a press at the card's centre, so the ring-tap flash and the card-tap
 * ripple look identical. There is no extra start delay: the trigger already fires only once the
 * centring scroll has settled, so the ripple begins with (near-)zero latency on the trigger.
 */
@Composable
internal fun VisaListCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
    flashTrigger: Int = 0,
    content: @Composable ColumnScope.() -> Unit,
) {
    // Clip the press ripple to the card's rounded shape so a tap doesn't flash a square.
    val shape = CardDefaults.elevatedShape
    // Dedicated source so the programmatic flash doesn't fight the body tap's own interaction.
    val flashSource = remember { MutableInteractionSource() }
    val flashScope = rememberCoroutineScope()
    val flashMutex = remember { Mutex() }
    var flashCenter by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(flashTrigger) {
        if (flashTrigger == 0) return@LaunchedEffect
        // A flash started while one is still running must not strand its Press: the unparented
        // job finishes its pair before the next one begins, and cannot be cancelled mid-pair.
        flashScope.launch(NonCancellable) {
            flashMutex.withLock {
                val press = PressInteraction.Press(flashCenter)
                flashSource.emit(press)
                delay(300.milliseconds)
                flashSource.emit(PressInteraction.Release(press))
            }
        }
    }
    val cardModifier = modifier
        .fillMaxWidth()
        .onSizeChanged { flashCenter = Offset(it.width / 2f, it.height / 2f) }
        .clip(shape)
        .then(onClick?.let { Modifier.clickable(onClick = it) } ?: Modifier)
        .indication(flashSource, LocalIndication.current)
    ElevatedCard(shape = shape, modifier = cardModifier) {
        Box {
            // Top-aligned: the action buttons stack is often taller than the text, and centring
            // made short cards float their text in the middle. Top keeps the title on the top line.
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    content()
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = 8.dp)) {
                    actions()
                }
            }
        }
    }
}

/** M3 date picker with the app's button vocabulary; [onConfirm] receives the chosen date (or
 *  null) and the caller is responsible for closing. [secondaryLabel] replaces "Cancel" (e.g. "Clear").
 *  [minDate]/[maxDate] bound the selectable range (dates outside are dimmed and cannot be chosen);
 *  leave both null for an unbounded picker. Confirm stays disabled while the selection — possibly
 *  the untouched initial date — sits outside the bounds, so no call site can confirm an illegal date. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VisaDatePickerDialog(
    initialMillis: Long,
    confirmLabel: String,
    onConfirm: (LocalDate?) -> Unit,
    onDismiss: () -> Unit,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
    minDate: LocalDate? = null,
    maxDate: LocalDate? = null,
) {
    // Material 3 bounds dates through a SelectableDates on the state (the older
    // validations/AllowedDateRange API is gone in 1.4.0). With bounds, any date outside
    // [minDate, maxDate] is dimmed and cannot be chosen; without, every date is selectable.
    val selectableDates = remember(minDate, maxDate) {
        if (minDate == null && maxDate == null) {
            DatePickerDefaults.AllDates
        } else {
            object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                    val d = utcTimeMillis.toUtcLocalDate()
                    if (minDate != null && d.isBefore(minDate)) return false
                    if (maxDate != null && d.isAfter(maxDate)) return false
                    return true
                }
            }
        }
    }
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initialMillis,
        selectableDates = selectableDates,
    )
    // The state keeps an initial date outside the bounds as the current selection until the user
    // taps another day, so gate Confirm on the selection rather than trusting it blindly.
    val selected = state.selectedDateMillis?.toUtcLocalDate()
    val inBounds = selected == null ||
        (minDate == null || !selected.isBefore(minDate)) &&
        (maxDate == null || !selected.isAfter(maxDate))
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(selected) }, enabled = inBounds) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            if (secondaryLabel != null && onSecondary != null) {
                TextButton(onClick = onSecondary) { Text(secondaryLabel) }
            } else {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    ) {
        DatePicker(state = state)
    }
}

/**
 * The app's snackbar host: the stock M3 snackbar is a flat full-width bar with square corners and
 * no margin, so this re-skins it with the card vocabulary — a tonal `surfaceContainerHigh` surface,
 * 16.dp rounded corners, horizontal margins, and a tonal elevation that reads in light and dark.
 */
@Composable
internal fun VisaSnackbarHost(hostState: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(
        hostState = hostState,
        modifier = modifier,
        snackbar = { data ->
            // Settings hides the nav bar, so the host's Scaffold inset collapses: the safe area is its own.
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 2.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    Text(
                        data.visuals.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = 12.dp),
                    )
                    data.visuals.actionLabel?.let { label ->
                        TextButton(onClick = { hostState.currentSnackbarData?.dismiss() }) {
                            Text(label)
                        }
                    }
                }
            }
        },
    )
}

// --- Allowance ring: shared by the Trips tab (112.dp primary + switcher) and the Documents tab (72.dp per document) ---

internal fun allowanceFraction(a: AllowanceSnapshot): Float = when {
    // guard a zero-day window — dividing by 0f is NaN and would poison drawArc's sweep.
    a.totalDays != null && a.usedDays != null && a.totalDays > 0 -> ((a.totalDays - a.usedDays).coerceAtLeast(0) / a.totalDays.toFloat()).coerceIn(0f, 1f)
    a.entryTotal != null && a.entryRemaining != null -> (a.entryRemaining.toFloat() / a.entryTotal).coerceIn(0f, 1f)
    a.remainingDays != null -> (a.remainingDays.coerceAtLeast(0) / 90f).coerceIn(0f, 1f)
    else -> 1f
}

/**
 * One state-to-colour map for every allowance surface (ring, summary line, switcher card):
 * terminal states red, "watch it" states amber, healthy green. Zone allowances (no document
 * behind them) keep the remaining-fraction gradient instead of a document state.
 */
internal fun ringColor(a: AllowanceSnapshot, isDark: Boolean): Color {
    if (a.noValidDoc) return severityColor(
        Severity.BAD,
        isDark
    ) // no document assigned — flash red like an overstay
    if (a.status == AllowanceStatus.UNKNOWN) return statusTone(null, isDark) // neutral slate — allowance unknown
    val severity = when {
        a.overstayDays > 0 || a.status == AllowanceStatus.DANGER || a.status == AllowanceStatus.EXHAUSTED -> Severity.BAD
        a.kind == AllowanceKind.ENTRY_COUNT -> when {
            a.currentTripId != null && a.entryRemaining == 0 -> Severity.BAD
            a.status == AllowanceStatus.WARNING -> Severity.WARN
            else -> Severity.OK
        }
        a.kind == AllowanceKind.DOCUMENT_VALIDITY -> if (a.status == AllowanceStatus.WARNING) Severity.WARN else Severity.OK
        else -> {
            val fraction = allowanceFraction(a)
            when {
                fraction < 0.10f -> Severity.BAD
                fraction < 0.25f -> Severity.WARN
                fraction < 0.50f -> Severity.CAUTION
                else -> Severity.OK
            }
        }
    }
    return severityColor(severity, isDark)
}

/** "no entries left" / "1 entry remaining" / "n entries remaining" — the ring's in-use sub-line. */
internal fun entriesRemainingPhrase(remaining: Int?): String = when (remaining) {
    null -> "no entries remaining"
    0 -> "no entries left"
    1 -> "1 entry remaining"
    else -> "$remaining entries remaining"
}

/**
 * Scrolls [index] to roughly the middle of the viewport in a single animated hop. The average
 * visible item stands in for the target's height, so the hop aims mid-screen without a second
 * nudge once the real height is known — a second animation read as the list centring twice.
 */
internal suspend fun LazyListState.animateItemToCenter(index: Int, viewportHeightPx: Int) {
    val visible = layoutInfo.visibleItemsInfo
    val estimate = if (visible.isEmpty()) 0 else visible.sumOf { it.size } / visible.size
    animateScrollToItem(index, scrollOffset = -(viewportHeightPx - estimate) / 2)
}

/** LazyColumn row indices: item 0 is the allowances card, each non-empty section adds a header before its items. */
internal fun sectionedIndices(sections: List<List<String>>): Map<String, Int> {
    val m = LinkedHashMap<String, Int>()
    var i = 1
    for (section in sections) {
        if (section.isEmpty()) continue
        i += 1
        section.forEachIndexed { n, id -> m[id] = i + n }
        i += section.size
    }
    return m
}