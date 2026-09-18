package com.cbkres.visavole.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarData
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cbkres.visavole.domain.AccessLevel
import com.cbkres.visavole.domain.AllowanceKind
import com.cbkres.visavole.domain.AllowanceSnapshot
import com.cbkres.visavole.domain.AllowanceStatus
import com.cbkres.visavole.domain.DocKind
import com.cbkres.visavole.domain.Document
import com.cbkres.visavole.data.WorldData
import com.cbkres.visavole.domain.docCategory
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.delay

// --- Date helpers (one canonical copy; previously duplicated in Trips/Documents/Onboarding) ---

internal fun Long.toUtcIsoDate(): String {
    val d = Instant.ofEpochMilli(this).atOffset(ZoneOffset.UTC).toLocalDate()
    return "%04d-%02d-%02d".format(d.year, d.monthValue, d.dayOfMonth)
}

internal fun LocalDate.toUtcMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

internal fun LocalDate.toUtcIsoDate(): String = "%04d-%02d-%02d".format(year, monthValue, dayOfMonth)

internal fun String.toUtcMillis(): Long? = runCatching { LocalDate.parse(this).toUtcMillis() }.getOrNull()

// --- Document presentational helpers (shared by the cards and the tab-top allowance display) ---

/**
 * The card's first-row type badge: the base type (Passport / Visa / Residence) in the tone the map
 * uses for what that document unlocks — the home-country blue, the covered-visa magenta, or the
 * residence teal.
 */
internal fun docTypeBadge(doc: Document, world: WorldData): Pair<String, Color> =
    when (doc.kind) {
        is DocKind.Passport -> "Passport" to HOME
        else -> if (doc.docCategory(world) == "residence") {
            "Residence" to statusTone(AccessLevel.RESIDENCE)
        } else {
            "Visa" to statusTone(AccessLevel.COVERED)
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
@Composable
fun StatusPill(
    text: String,
    color: Color,
    bold: Boolean = false,
    maxWidth: Dp? = null,
    style: TextStyle = MaterialTheme.typography.labelSmall,
    contentPadding: PaddingValues = PaddingValues(horizontal = 8.dp, vertical = 3.dp),
    fontWeight: FontWeight? = null,
    modifier: Modifier = Modifier,
) {
    val weight = fontWeight ?: (if (bold) FontWeight.Bold else FontWeight.SemiBold)
    Surface(
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = STATUS_CHIP_ALPHA),
        modifier = modifier.then(maxWidth?.let { Modifier.widthIn(max = it) } ?: Modifier),
    ) {
        Text(
            text,
            color = color,
            fontWeight = weight,
            style = style,
            modifier = Modifier.padding(contentPadding),
        )
    }
}

/** Small overline heading above a list section (e.g. "Upcoming", "Active"). */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(top = 6.dp, bottom = 2.dp),
    )
}

/** Screen title + one-line subtitle, with the standard 12.dp gap before the first control. */
@Composable
fun ScreenHeader(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
    }
}

/** Right-aligned primary "Add …" button on its own row. [onClick] is last so call sites can use a
 *  trailing lambda: `AddButtonRow("Add trip") { showAdd = true }`. */
@Composable
fun AddButtonRow(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(modifier.fillMaxWidth()) {
        Spacer(Modifier.weight(1f))
        Button(onClick = onClick) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text(label)
        }
    }
}

/** Centred muted placeholder shown when a list has no items. */
@Composable
fun EmptyState(message: String, modifier: Modifier = Modifier) {
    Text(
        message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(vertical = 16.dp),
    )
}

/** A tappable country row: optional selection tick, name (weighted), optional ISO tag. */
@Composable
fun CountryRow(
    name: String,
    onClick: () -> Unit,
    iso: String? = null,
    selected: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Row(
        // minHeight keeps every country/search row on a ≥48dp touch target regardless of text style.
        modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick).padding(vertical = 10.dp, horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected) {
            Text("✓ ", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        iso?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/**
 * A one-shot ripple in the card-tap ripple's look, fired programmatically: whenever [trigger]
 * changes to a new non-zero value, a circle expands from the centre while fading out. Clipped to
 * [shape] so it never spills past the card's rounded corners; [startDelay] lets a list scroll
 * settle first so the flash lands on the arriving card.
 */
@Composable
internal fun RippleFlash(trigger: Int, shape: Shape, startDelay: Int = 0, modifier: Modifier = Modifier) {
    if (trigger == 0) return
    val progress = remember { Animatable(0f) }
    LaunchedEffect(trigger) {
        if (startDelay > 0) delay(startDelay.toLong())
        progress.snapTo(0f)
        progress.animateTo(1f, animationSpec = tween(600, easing = FastOutSlowInEasing))
    }
    val alpha = (1f - progress.value) * 0.2f
    if (alpha > 0.004f) {
        // The theme read is composable, so it happens outside the Canvas draw lambda.
        val flashColor = MaterialTheme.colorScheme.onSurface
        // 0.75 of the longer side beats half the diagonal for any aspect ratio, so at full
        // progress the circle always covers the whole card.
        Box(modifier.fillMaxSize().clip(shape)) {
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(
                    color = flashColor.copy(alpha = alpha),
                    radius = maxOf(size.width, size.height) * 0.75f * progress.value,
                )
            }
        }
    }
}

/**
 * The shared list-card frame, derived from the trip card: an [ElevatedCard] with 16.dp padding,
 * a weighted content column (rows spaced by 8.dp) and a trailing vertical column of icon actions.
 * Screens supply their own content rows and action icons; the padding/spacing/shape live here.
 * [flashTrigger] plays a one-shot [RippleFlash] over the card whenever it changes to a new
 * non-zero value — the "look here" flash for a card the list just scrolled to.
 */
@Composable
fun VisaListCard(
    onClick: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
    trailing: (@Composable () -> Unit)? = null,
    flashTrigger: Int = 0,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    // Clip the press ripple to the card's rounded shape so a tap doesn't flash a square.
    val shape = CardDefaults.elevatedShape
    val cardModifier = Modifier
        .fillMaxWidth()
        .clip(shape)
        .then(onClick?.let { Modifier.clickable(onClick = it) } ?: Modifier)
    ElevatedCard(shape = shape, modifier = cardModifier) {
        Box {
            // Top-aligned: the action buttons stack is often taller than the text, and centring
            // made short cards float their text in the middle. Top keeps the title on the top line.
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    content()
                }
                if (trailing != null) {
                    Spacer(Modifier.width(8.dp))
                    trailing()
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = 8.dp)) {
                    actions()
                }
            }
            // Starts 150 ms after the screen has fired the trigger — which it does only once the
            // ring-tap centring scroll has settled, so the ripple plays on the landed card.
            RippleFlash(flashTrigger, shape, startDelay = 150)
        }
    }
}

/** M3 date picker with the app's button vocabulary; [onConfirm] receives the chosen date (or
 *  null) and the caller is responsible for closing. [secondaryLabel] replaces "Cancel" (e.g. "Clear"). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VisaDatePickerDialog(
    initialMillis: Long,
    confirmLabel: String,
    onConfirm: (LocalDate?) -> Unit,
    onDismiss: () -> Unit,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    val state = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(state.selectedDateMillis?.toUtcIsoDate()?.let(LocalDate::parse)) }) {
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
 * no margin, so this re-skins it with the card vocabulary — a tonal [surfaceContainerHigh] surface,
 * 16.dp rounded corners, horizontal margins, and a tonal elevation that reads in light and dark.
 */
@Composable
fun VisaSnackbarHost(hostState: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(
        hostState = hostState,
        modifier = modifier,
        snackbar = { data ->
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 2.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
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
    a.totalDays != null && a.usedDays != null -> ((a.totalDays - a.usedDays).coerceAtLeast(0) / a.totalDays.toFloat()).coerceIn(0f, 1f)
    a.entryTotal != null && a.entryRemaining != null -> (a.entryRemaining.toFloat() / a.entryTotal).coerceIn(0f, 1f)
    a.remainingDays != null -> (a.remainingDays.coerceAtLeast(0) / 90f).coerceIn(0f, 1f)
    else -> 1f
}

/**
 * One state-to-colour map for every allowance surface (ring, summary line, switcher card):
 * terminal states red, "watch it" states amber, healthy green. Zone allowances (no document
 * behind them) keep the remaining-fraction gradient instead of a document state.
 */
internal fun ringColor(a: AllowanceSnapshot): Color {
    if (a.status == AllowanceStatus.UNKNOWN) return Color(0xFF64748B) // neutral slate — allowance unknown
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
    return severityColor(severity)
}

/** "no entries left" / "1 entry remaining" / "n entries remaining" — the ring's in-use sub-line. */
internal fun entriesRemainingPhrase(remaining: Int?): String = when (remaining) {
    null -> "no entries remaining"
    0 -> "no entries left"
    1 -> "1 entry remaining"
    else -> "$remaining entries remaining"
}

/**
 * The coloured summary line: the allowance's state in one phrase (same words as the cards' pills).
 * Entry-count documents carry both halves — "N days left · N entries remaining" — dot-joined, so a
 * visa gets the same coloured treatment as a residence's "Valid for N days".
 */
internal fun allowanceSummary(a: AllowanceSnapshot): String = when {
    a.overstayDays > 0 -> if (a.overstayDays == 1) "1 day over" else "${a.overstayDays} days over"
    a.status == AllowanceStatus.DANGER && a.kind != AllowanceKind.ROLLING && a.kind != AllowanceKind.PER_ENTRY ->
        "Expired on ${a.effectiveExpiry ?: "unknown"}"
    a.kind == AllowanceKind.ENTRY_COUNT -> buildList {
        a.remainingDays?.let {
            add(when {
                it <= 0 -> "Expires today"
                it == 1 -> "1 day left"
                else -> "$it days left"
            })
        }
        a.entryRemaining?.let { add(entriesRemainingPhrase(it)) }
        if (isEmpty()) add(a.subtitle ?: "No limit")
    }.joinToString(" · ")
    a.remainingDays != null && a.totalDays == null ->
        "Valid for ${a.remainingDays} ${if (a.remainingDays == 1) "day" else "days"}"
    a.totalDays != null && a.remainingDays != null ->
        "${a.remainingDays} of ${a.totalDays} ${if (a.totalDays == 1) "day" else "days"} left"
    else -> a.subtitle ?: "No limit"
}

/** Entry-count documents rotate to a days-to-expiry face this close to their expiry. */
private const val RING_ROTATE_MAX_DAYS = 90

/**
 * The allowance donut: the remaining window (or remaining entries) drawn in the status colour,
 * the centre showing the remaining count and a label. [size] scales the donut — the stroke and
 * the centre text follow it. An in-use entry shows "In use" with the entries remaining beneath;
 * a lapsed document shows "Expired" with the date. Entry-count documents nearing expiry (at most
 * [RING_ROTATE_MAX_DAYS] days) alternate every 5 s with a days-to-expiry face — the arc and the
 * colour stay put, only the centre text crossfades.
 */
@Composable
fun AllowanceRing(a: AllowanceSnapshot, size: Dp = 112.dp, modifier: Modifier = Modifier) {
    val color = ringColor(a)
    val fraction = allowanceFraction(a)
    val rotatable = a.kind == AllowanceKind.ENTRY_COUNT &&
        a.status != AllowanceStatus.DANGER &&
        a.remainingDays != null && a.remainingDays in 1..RING_ROTATE_MAX_DAYS
    var daysFace by remember(a.key, rotatable) { mutableStateOf(false) }
    LaunchedEffect(rotatable) {
        if (!rotatable) {
            daysFace = false
            return@LaunchedEffect
        }
        while (true) {
            delay(5_000)
            daysFace = !daysFace
        }
    }
    val docKind = a.kind == AllowanceKind.ENTRY_COUNT || a.kind == AllowanceKind.DOCUMENT_VALIDITY
    val (center, centerLabel) = when {
        a.overstayDays > 0 -> a.overstayDays.toString() to "days over"
        docKind && a.status == AllowanceStatus.DANGER -> "Expired" to "on ${a.effectiveExpiry ?: "unknown"}"
        a.kind == AllowanceKind.ENTRY_COUNT && a.currentTripId != null -> "In use" to entriesRemainingPhrase(a.entryRemaining)
        a.kind == AllowanceKind.ENTRY_COUNT -> (a.entryRemaining ?: 0).toString() to "entries left"
        a.kind == AllowanceKind.DOCUMENT_VALIDITY -> (a.remainingDays ?: 0).toString() to "days to expiry"
        else -> (a.remainingDays ?: 0).toString() to "days left"
    }
    val daysCenter = a.remainingDays?.let { it.toString() to "days to expiry" }
    val showDaysFace = rotatable && daysFace
    // Both faces are laid out into an identical fixed-height box, so the crossfade's own size never
    // changes. A variable-size crossfade snaps a few pixels the instant the shorter face swaps in —
    // the constant box keeps the centre text parked on the ring's centre line through the fade.
    val faceHeight = if (size < 96.dp) 48.dp else 58.dp
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = (size / 11f).toPx()
            drawCircle(color = color.copy(alpha = 0.15f), style = Stroke(stroke))
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = 360f * fraction,
                useCenter = false,
                style = Stroke(stroke),
            )
        }
        Crossfade(targetState = showDaysFace, animationSpec = tween(300)) { face ->
            val (c, l) = if (face) daysCenter ?: (center to centerLabel) else center to centerLabel
            val wordFace = c == "In use" || c == "Expired"
            Box(Modifier.height(faceHeight), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        c,
                        // Word faces ("In use", "Expired") run one step smaller than a bare number.
                        style = if (wordFace || size < 96.dp) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = color,
                    )
                    Text(l, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/**
 * The tab-top allowance display, shared by the Trips tab (zone allowances) and the Documents tab
 * (documents with an expiry): the focused allowance's ring and summary up top, with the rest as a
 * horizontally scrollable card row beneath it. Tapping the ring fires [onRingClick] (the screens
 * scroll the matching document/trip into view); [titleRow] replaces the plain title text — the
 * Documents tab renders its type badge pill plus the country name there.
 */
@Composable
internal fun AllowanceSection(
    allowances: List<AllowanceSnapshot>,
    primary: AllowanceSnapshot?,
    focusedKey: String?,
    onFocus: (String) -> Unit,
    onRingClick: (AllowanceSnapshot) -> Unit = {},
    titleRow: (@Composable RowScope.(AllowanceSnapshot) -> Unit)? = null,
) {
    if (allowances.isEmpty()) {
        Text("No allowances to track yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    primary?.let {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .clip(CircleShape) // clip the ripple to the donut
                    .clickable(onClick = { onRingClick(it) }),
            ) {
                AllowanceRing(it)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (titleRow != null) {
                        titleRow(it)
                    } else {
                        Text(it.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                it.subtitle?.let { s -> Text(s, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Text(allowanceSummary(it), style = MaterialTheme.typography.labelMedium, color = ringColor(it))
            }
        }
        Spacer(Modifier.height(16.dp))
    }
    val switcherScroll = rememberScrollState()
    val (switcherStart, switcherEnd) = switcherScroll.hazeAlphas()
    HazeBox(switcherStart, switcherEnd, MaterialTheme.colorScheme.background, horizontal = true, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(switcherScroll), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            allowances.forEach { a ->
                AllowanceCard(a, focused = a.key == focusedKey, onClick = { onFocus(a.key) })
            }
        }
    }
}

/** One switchable card in the allowance card row (the focused one is tinted with the primary). */
@Composable
internal fun AllowanceCard(a: AllowanceSnapshot, focused: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (focused) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(16.dp),
        // Clip the ripple to the card shape — a bare clickable on the Surface flashes a square.
        modifier = Modifier
            .width(168.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(a.title, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(allowanceSummary(a), style = MaterialTheme.typography.labelSmall, color = ringColor(a))
        }
    }
}

/**
 * Scrolls [index] to the middle of the viewport: the first hop aligns the item with the list's
 * top edge (which also forces it to lay out), the second settles it to the vertical middle using
 * the item's measured height.
 */
suspend fun LazyListState.animateItemToCenter(index: Int, viewportHeightPx: Int) {
    animateScrollToItem(index)
    withFrameNanos { }
    val item = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return
    val offset = ((viewportHeightPx - item.size) / 2f).toInt().coerceAtLeast(0)
    if (offset > 0) animateScrollToItem(index, scrollOffset = offset)
}
