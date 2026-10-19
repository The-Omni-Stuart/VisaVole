package com.cbkres.visavole.ui.screens.map

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cbkres.visavole.domain.AccessLevel
import com.cbkres.visavole.ui.HazeBox
import com.cbkres.visavole.ui.hazeAlphas

@Composable
internal fun MapLegend(counts: Map<AccessLevel?, Int>, homeCount: Int = 1) {
    val levels = listOf(
        AccessLevel.FREEDOM, AccessLevel.RESIDENCE, AccessLevel.VISA_FREE, AccessLevel.COVERED,
        AccessLevel.ETA, AccessLevel.E_VISA, AccessLevel.VISA_REQUIRED, AccessLevel.REFUSED,
    )
    val legendScroll = rememberScrollState()
    val (legendStart, legendEnd) = legendScroll.hazeAlphas()
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 0.dp) {
        HazeBox(
            legendStart,
            legendEnd,
            MaterialTheme.colorScheme.surfaceContainerHigh,
            horizontal = true,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(legendScroll)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(14.dp)
                            .background(HOME),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if (homeCount > 1) "Your countries $homeCount" else "Your country",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                levels.forEach { lvl ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(14.dp)
                                .background(colorFor(lvl)),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(lvl.label(), style = MaterialTheme.typography.labelSmall)
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "${counts[lvl] ?: 0}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                // Slate swatch: countries with no documented rule render slate on the map but had no
                // legend entry (P3-15).
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(14.dp)
                            .background(colorFor(null)),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("No documented rule", style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "${counts[null] ?: 0}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}