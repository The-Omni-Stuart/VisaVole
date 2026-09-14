package com.cbkres.visavole.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cbkres.visavole.domain.Access
import com.cbkres.visavole.domain.AccessLevel
import com.cbkres.visavole.domain.DocAccess

@Composable
fun CountryDetailCard(
    countryName: String,
    access: Access?,
    breakdown: List<DocAccess> = emptyList(),
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    isHome: Boolean = false,
    isOwnCovered: Boolean = false,
    homePassport: String? = null,
    daysLabel: String? = null,
) {
    val level = access?.level ?: AccessLevel.UNKNOWN
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 0.dp, bottomEnd = 0.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 0.dp,
    ) {
        Column(Modifier.padding(20.dp).padding(bottom = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(countryName, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Close")
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val chipTone = statusTone(level, isHome, isOwnCovered && level == AccessLevel.COVERED)
                Column(
                    Modifier
                        .background(chipTone.copy(alpha = STATUS_CHIP_ALPHA), CircleShape)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        if (isHome) "Your country" else level.label(),
                        color = chipTone,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                val shownDays = daysLabel ?: access?.days?.let { "$it days" }
                if (!shownDays.isNullOrBlank()) {
                    Spacer(Modifier.width(12.dp))
                    Text(shownDays, style = MaterialTheme.typography.titleMedium)
                }
            }
            val classSuffix = access?.residenceClass?.let { " (${it.label.lowercase()})" }.orEmpty()
            val subtext = when {
                isHome -> "Passport: ${homePassport.orEmpty()}"
                access == null || access.level == AccessLevel.UNKNOWN -> ""
                else -> {
                    val reason = when {
                        access.reason == "Passport rule" -> ""
                        access.reason.startsWith("No data") -> "No documented rule"
                        else -> access.reason
                    }
                    (reason + classSuffix).trim()
                }
            }
            if (subtext.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    subtext,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (breakdown.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                Text(
                    "Enter with",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                val breakdownScroll = rememberScrollState()
                val maxVisibleBreakdownRows = 4
                val breakdownRowHeight = 32.dp
                val breakdownSpacing = 8.dp
                val breakdownMaxHeight = breakdownRowHeight * maxVisibleBreakdownRows +
                    breakdownSpacing * (maxVisibleBreakdownRows - 1)
                val (breakdownTop, breakdownEnd) = breakdownScroll.hazeAlphas()
                HazeBox(breakdownTop, breakdownEnd, MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.then(
                            if (breakdown.size > maxVisibleBreakdownRows) {
                                Modifier
                                    .height(breakdownMaxHeight)
                                    .verticalScroll(breakdownScroll)
                            } else {
                                Modifier
                            },
                        ),
                    ) {
                        breakdown.forEachIndexed { i, entry ->
                            Row(
                                Modifier.height(breakdownRowHeight),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                val isYourCountry = entry.access.reason == "Your country"
                                Text(
                                    if (isYourCountry) "Your country" else entry.access.level.label(),
                                    color = statusTone(entry.access.level, isYourCountry, entry.isOwn),
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.widthIn(min = 72.dp),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    entry.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (entry.access.days != null) {
                                    Text(
                                        "${entry.access.days} days",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            if (i < breakdown.size - 1) Spacer(Modifier.height(breakdownSpacing))
                        }
                    }
                }
            }
        }
    }
}
