package com.cbkres.visavole.ui.screens.trips

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cbkres.visavole.data.StayRule
import com.cbkres.visavole.data.WorldData
import com.cbkres.visavole.domain.AllowanceSnapshot
import com.cbkres.visavole.domain.Document
import com.cbkres.visavole.domain.Trip
import com.cbkres.visavole.domain.TripModel
import com.cbkres.visavole.domain.TripStatus
import com.cbkres.visavole.domain.TripStop
import com.cbkres.visavole.ui.StatusPill
import com.cbkres.visavole.ui.ringColor
import com.cbkres.visavole.ui.screens.map.LocalEffectiveDarkTheme
import com.cbkres.visavole.ui.screens.map.Severity
import com.cbkres.visavole.ui.screens.map.severityColor
import kotlinx.coroutines.delay
import java.time.LocalDate

private fun zoneLabel(rule: StayRule?, fallback: String): String = when {
    rule == null -> fallback
    rule.zoneName.isNotBlank() -> rule.zoneName
    rule.countries.size == 1 -> fallback
    else -> rule.displayName
}

// The allowance display (ring kit, AllowanceSection, AllowanceCard) lives in UiKit — shared with
// the Documents tab.

/** One expanded stop's derived display values, computed once per trip row (P3-43). */
private data class StopRow(
    val countryName: String,
    val zoneLabel: String,
    val docLabel: String?,
    val zoneAllowance: AllowanceSnapshot?,
)

/** Everything a TripCard derives per trip, computed once per publish (P3-43) so a card's row
 *  doesn't re-derive on every scroll-frame recomposition (amplified by P1-14). */
private data class TripCardRow(
    val sortedStops: List<TripStop>,
    val stops: List<StopRow>,
    val title: String,
    val stopCountLabel: String?,
    val docLabels: String,
    val zones: List<String>,
    val relatedAllowance: AllowanceSnapshot?,
    val allowanceHint: String?,
    val zoneAllowances: List<AllowanceSnapshot>,
    val noDocZoneTitles: Set<String>,
)

// TODO: This whole thing and the connection components should be cleaned

@Composable
internal fun TripCard(
    trip: Trip,
    world: WorldData,
    docs: List<Document>,
    allowances: List<AllowanceSnapshot>,
    passportCounts: Map<String, Int>,
    passportNumbers: Map<String, Int>,
    validHomes: Set<String>,
    today: LocalDate,
    expanded: Boolean,
    onExpand: () -> Unit,
    onEdit: () -> Unit,
    onEnd: () -> Unit,
    onDelete: () -> Unit,
    flashTrigger: Int = 0,
) {
    val row = remember(trip, world, docs, allowances, today) {
        val sortedStops = TripModel.sortedStops(trip.stops)
        val zoneAllowances = TripModel.zoneAllowancesFor(trip, allowances)
        val zoneByTitle = zoneAllowances.associateBy { it.title }
        val stops = sortedStops.map { stop ->
            val countryName = world.countries[stop.countryIso2]?.name ?: stop.countryIso2
            val zoneLabel = zoneLabel(world.stayRuleFor(stop.countryIso2, validHomes, today), countryName)
            StopRow(
                countryName = countryName,
                zoneLabel = zoneLabel,
                docLabel = stop.documentId?.let { id -> docs.firstOrNull { it.id == id }?.displayLabel(passportCounts, passportNumbers) ?: id },
                zoneAllowance = zoneByTitle[zoneLabel],
            )
        }
        val relatedAllowance = allowances.firstOrNull { it.currentTripId == trip.id && !it.key.startsWith("doc:") }
            ?: allowances.firstOrNull { it.currentTripId == trip.id }
            ?: allowances.firstOrNull { it.relevantTripIds.contains(trip.id) && !it.key.startsWith("doc:") }
            ?: allowances.firstOrNull { it.relevantTripIds.contains(trip.id) }
        TripCardRow(
            sortedStops = sortedStops,
            stops = stops,
            title = TripPresentation(trip, world),
            stopCountLabel = if (sortedStops.size > 1) "${sortedStops.size} stops" else null,
            docLabels = stops.mapNotNull { it.docLabel }.distinct().joinToString(", "),
            zones = stops.map { it.zoneLabel }.distinct(),
            relatedAllowance = relatedAllowance,
            allowanceHint = when {
                relatedAllowance?.overstayDays != null && relatedAllowance.overstayDays > 0 -> "-${relatedAllowance.overstayDays} Days OVERSTAYING!"
                relatedAllowance?.remainingDays != null -> if (relatedAllowance.remainingDays == 1) "1 day left" else "${relatedAllowance.remainingDays} days left"
                relatedAllowance?.entryRemaining != null -> "${relatedAllowance.entryRemaining} ${if (relatedAllowance.entryRemaining == 1) "entry" else "entries"} left"
                else -> null
            },
            zoneAllowances = zoneAllowances,
            noDocZoneTitles = stops.filter { it.docLabel == null }.map { it.zoneLabel }.toSet(),
        )
    }
    val allowanceHintColor = row.relatedAllowance?.let {
        ringColor(
            it,
            LocalEffectiveDarkTheme.current
        )
    } ?: MaterialTheme.colorScheme.onSurfaceVariant
    _root_ide_package_.com.cbkres.visavole.ui.VisaListCard(
        onClick = onExpand,
        flashTrigger = flashTrigger,
        actions = {
            IconButton(onClick = onEdit) {
                Icon(Icons.Filled.Edit, contentDescription = "Edit")
            }
            if (trip.statusAt(today) == TripStatus.ONGOING) {
                IconButton(onClick = onEnd) {
                    Icon(Icons.Filled.DateRange, contentDescription = "End trip")
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Close, contentDescription = "Delete")
            }
        },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            StatusPill(
                trip.statusAt(today).label,
                MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                row.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = if (expanded) "−" else "+",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            buildString {
                append(trip.firstArrival)
                append(" → ")
                append(trip.finalDeparture ?: "ongoing")
                row.stopCountLabel?.let {
                    append(" · ")
                    append(it)
                }
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (row.zones.isNotEmpty()) {
                Text(
                    row.zones.joinToString(", "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (expanded) Int.MAX_VALUE else 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            when {
                row.zoneAllowances.size > 1 && !expanded -> CyclingZonePill(
                    row.zoneAllowances,
                    row.noDocZoneTitles
                )

                row.zoneAllowances.size <= 1 -> {
                    val noDoc = row.zoneAllowances.firstOrNull()?.title in row.noDocZoneTitles
                    if (noDoc) StatusPill(
                        "no valid doc",
                        severityColor(
                            Severity.BAD,
                            LocalEffectiveDarkTheme.current
                        )
                    )
                    else row.allowanceHint?.let {
                        StatusPill(
                            it,
                            allowanceHintColor
                        )
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(150)) + expandVertically(tween(150)),
            exit = fadeOut(tween(150)) + shrinkVertically(tween(150)),
        ) {
            Column {
                Spacer(Modifier.height(12.dp))
                row.sortedStops.forEachIndexed { index, stop ->
                    val sr = row.stops[index]
                    Column(Modifier.padding(vertical = 6.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                "${index + 1}.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                sr.countryName,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                sr.zoneLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (row.zoneAllowances.size > 1) {
                                sr.zoneAllowance?.let { a ->
                                    if (sr.docLabel == null) {
                                        StatusPill(
                                            "no valid doc",
                                            severityColor(
                                                Severity.BAD,
                                                LocalEffectiveDarkTheme.current
                                            )
                                        )
                                    } else {
                                        zoneHint(a)?.let { h ->
                                            StatusPill(
                                                h,
                                                ringColor(
                                                    a,
                                                    LocalEffectiveDarkTheme.current
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        Text(
                            "${stop.arrival} → ${stop.departure ?: "open"}${sr.docLabel?.let { " · $it" } ?: " · No document"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (trip.note != null) {
                    Text(trip.note!!, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (!expanded) {
            if (trip.note != null) {
                Text(
                    trip.note!!,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (row.docLabels.isNotEmpty()) {
                Text(
                    row.docLabels,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * The compact allowance wording a zone shows (no destination prefix — the destination is named by
 * [labeledZoneHint], or, in the expanded stop list, by the stop's own row): "76 days left",
 * "-3 Days OVERSTAYING!", "5 entries left".
 */
private fun zoneHint(a: AllowanceSnapshot): String? = when {
    a.overstayDays > 0 -> "-${a.overstayDays} Days OVERSTAYING!"
    a.remainingDays != null -> if (a.remainingDays == 1) "1 day left" else "${a.remainingDays} days left"
    a.entryRemaining != null -> "${a.entryRemaining} ${if (a.entryRemaining == 1) "entry" else "entries"} left"
    else -> null
}

/**
 * The cycling-pill face text: the zone's short name (ISO-2 code for a single-country zone, else the
 * zone name) naming the destination, then [zoneHint] — e.g. "US: 76 days left",
 * "Schengen Area: 76 days left", "GB: -3 Days OVERSTAYING!".
 */
private fun labeledZoneHint(a: AllowanceSnapshot): String {
    val name = a.shortTitle ?: a.title
    val hint = zoneHint(a) ?: return name
    return "$name: $hint"
}

/**
 * A single allowance pill that cycles through a multi-zone trip's zones in turn, mirroring the
 * allowance ring's face-rotator (5 s cadence + crossfade). The invisible "ghost" faces pin the box
 * to the widest face so swapping never shifts the row, and the merged TalkBack label reads every
 * zone at once instead of re-announcing on each flip. A zone in [noDocZoneTitles] (a stop with no
 * document) shows "no valid doc" in place of its allowance, in the status red.
 */
@Composable
private fun CyclingZonePill(zones: List<AllowanceSnapshot>, noDocZoneTitles: Set<String>) {
    val isDark = LocalEffectiveDarkTheme.current
    val error = severityColor(
        Severity.BAD,
        isDark
    )
    var face by remember(zones) { mutableStateOf(0) }
    LaunchedEffect(zones) {
        if (zones.size < 2) {
            face = 0
            return@LaunchedEffect
        }
        while (true) {
            delay(if (face == 0) 10_000 else 5_000)
            face = (face + 1) % zones.size
        }
    }
    fun faceText(a: AllowanceSnapshot) =
        if (a.title in noDocZoneTitles) "${a.shortTitle ?: a.title}: no valid doc" else labeledZoneHint(a)
    fun faceColor(a: AllowanceSnapshot) =
        if (a.title in noDocZoneTitles) error else _root_ide_package_.com.cbkres.visavole.ui.ringColor(
            a,
            isDark
        )
    Box(
        modifier = Modifier.semantics(mergeDescendants = true) {
            contentDescription = zones.joinToString("; ") { faceText(it) }
        },
    ) {
        zones.forEach { a ->
            StatusPill(
                faceText(a),
                faceColor(a),
                modifier = Modifier.alpha(0f)
            )
        }
        Crossfade(targetState = face, animationSpec = tween(300)) { i ->
            StatusPill(
                faceText(zones[i]),
                faceColor(zones[i])
            )
        }
    }
}

private val TripStatus.label: String
    get() = when (this) {
        TripStatus.UPCOMING -> "Upcoming"
        TripStatus.ONGOING -> "Ongoing"
        TripStatus.PREVIOUS -> "Completed"
    }