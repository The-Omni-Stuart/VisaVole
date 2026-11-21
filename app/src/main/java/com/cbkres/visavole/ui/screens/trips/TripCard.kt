package com.cbkres.visavole.ui.screens.trips

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cbkres.visavole.domain.AllowanceSnapshot
import com.cbkres.visavole.domain.Document
import com.cbkres.visavole.domain.StayRule
import com.cbkres.visavole.domain.Trip
import com.cbkres.visavole.domain.TripModel
import com.cbkres.visavole.domain.TripStatus
import com.cbkres.visavole.domain.TripStop
import com.cbkres.visavole.domain.WorldData
import com.cbkres.visavole.ui.StatusPill
import com.cbkres.visavole.ui.VisaListCard
import com.cbkres.visavole.ui.ringColor
import com.cbkres.visavole.ui.screens.map.LocalEffectiveDarkTheme
import com.cbkres.visavole.ui.screens.map.Severity
import com.cbkres.visavole.ui.screens.map.severityColor
import java.time.LocalDate

private fun zoneLabel(rule: StayRule?, fallback: String): String = when {
    rule == null -> fallback
    rule.zoneName.isNotBlank() -> rule.zoneName
    rule.countries.size == 1 -> fallback
    else -> rule.displayName
}

// The allowance display (ring kit, AllowanceSection, AllowanceCard) lives in UiKit — shared with
// the Documents tab.

/** One expanded stop's derived display values, computed once per trip row. */
private data class StopRow(
    val countryName: String,
    val zoneLabel: String,
    val docLabel: String?,
    val zoneAllowance: AllowanceSnapshot?,
)

/** Everything a TripCard derives per trip, computed once per publish so a card's row
 *  doesn't re-derive on every scroll-frame recomposition. */
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
        )
    }
    val allowanceHintColor = row.relatedAllowance?.let {
        ringColor(
            it,
            LocalEffectiveDarkTheme.current
        )
    } ?: MaterialTheme.colorScheme.onSurfaceVariant
    VisaListCard(
        onClick = if (trip.stops.size > 1) onExpand else null,
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
            if (trip.stops.size > 1) {
                Text(
                    text = if (expanded) "−" else "+",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
                row.zoneAllowances.size > 1 && !expanded -> CyclingZonePill(row.zoneAllowances)

                row.zoneAllowances.size <= 1 -> {
                    val noDoc = row.zoneAllowances.firstOrNull()?.noValidDoc == true
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
// internal: CyclingZonePill.kt (same package) shares this helper.
internal fun zoneHint(a: AllowanceSnapshot): String? = when {
    a.overstayDays > 0 -> "-${a.overstayDays} Days OVERSTAYING!"
    a.remainingDays != null -> if (a.remainingDays == 1) "1 day left" else "${a.remainingDays} days left"
    a.entryRemaining != null -> "${a.entryRemaining} ${if (a.entryRemaining == 1) "entry" else "entries"} left"
    else -> null
}

private val TripStatus.label: String
    get() = when (this) {
        TripStatus.UPCOMING -> "Upcoming"
        TripStatus.ONGOING -> "Ongoing"
        TripStatus.PREVIOUS -> "Completed"
    }