package com.cbkres.visavole.ui.screens.trips

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cbkres.visavole.domain.AccessModel
import com.cbkres.visavole.domain.DocStatus
import com.cbkres.visavole.domain.Document
import com.cbkres.visavole.domain.TripStop
import com.cbkres.visavole.domain.WorldData
import com.cbkres.visavole.domain.passportCountsByIso
import com.cbkres.visavole.domain.passportNumbers
import com.cbkres.visavole.ui.HazeBox
import com.cbkres.visavole.ui.hazeAlphas
import java.time.LocalDate

/** The scrollable list of stop rows inside the trip dialog. */
@Composable
internal fun TripStopList(
    stops: List<TripStop>,
    world: WorldData,
    docs: List<Document>,
    today: LocalDate,
    maxHeight: Dp,
    onEdit: (TripStop) -> Unit,
    onRemove: (TripStop) -> Unit,
    statuses: Map<String, DocStatus> = emptyMap(),
) {
    val validHomes = remember(docs, world, today, statuses) {
        AccessModel.validHomeCountries(docs, world, today, statuses = statuses)
    }
    val stopsScroll = rememberScrollState()
    val (stopsTop, stopsEnd) = stopsScroll.hazeAlphas()
    HazeBox(
        stopsTop,
        stopsEnd,
        MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().heightIn(max = maxHeight),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .verticalScroll(stopsScroll),
        ) {
            if (stops.isEmpty()) {
                Text(
                    "No stops yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            stops.forEach { stop ->
                StopRow(
                    stop = stop,
                    world = world,
                    docs = docs,
                    today = today,
                    validHomes = validHomes,
                    onClick = { onEdit(stop) },
                    onRemove = { onRemove(stop) },
                )
                Spacer(Modifier.height(6.dp))
            }
            if (stops.size > 1) {
                Text(
                    "Stops are kept in arrival order.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun StopRow(
    stop: TripStop,
    world: WorldData,
    docs: List<Document>,
    today: LocalDate,
    validHomes: Set<String>,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    val rule = world.stayRuleFor(stop.countryIso2, validHomes, today)
    val passportCounts = remember(docs) { passportCountsByIso(docs) }
    val passportNumbers = remember(docs) { passportNumbers(docs) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(world.countries[stop.countryIso2]?.name ?: stop.countryIso2, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "${stop.arrival} → ${stop.departure ?: "open"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Own line for the document: appended to the dates it wrapped anyway.
                docs.firstOrNull { it.id == stop.documentId }?.let {
                    Text(
                        it.displayLabel(passportCounts, passportNumbers),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    rule?.let { "Counts toward: ${it.summary()}" } ?: "No shared stay rule",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Filled.Delete, contentDescription = "Remove stop")
            }
        }
    }
}
