package com.cbkres.visavole.ui.screens.map

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cbkres.visavole.domain.Access
import com.cbkres.visavole.domain.AccessLevel
import com.cbkres.visavole.domain.DocAccess
import com.cbkres.visavole.ui.HazeBox
import com.cbkres.visavole.ui.StatusPill
import com.cbkres.visavole.ui.hazeAlphas


@Composable
internal fun CountryDetailCard(
    countryName: String,
    access: Access?,
    breakdown: List<DocAccess>?,
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
        // P3-57: no drop shadow — the default 3dp elevation read as a shadow on the card's
        // lower edge and washed out the last breakdown row (the card is a flat bottom sheet).
        shadowElevation = 0.dp,
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(countryName, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Close")
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val chipTone =
                    statusTone(level, LocalEffectiveDarkTheme.current, isHome, isOwnCovered)
                StatusPill(
                    text = if (isHome) "Your country" else level.label(),
                    color = chipTone,
                    style = MaterialTheme.typography.labelLarge,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    fontWeight = FontWeight.Normal,
                )
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
            AnimatedContent(
                targetState = breakdown,
                transitionSpec = {
                    if (targetState == null) {
                        // Switching country: go to loading immediately.
                        fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                    } else {
                        // Data has arrived: gently reveal it.
                        fadeIn(
                            animationSpec = tween(
                                durationMillis = 250,
                                delayMillis = 150,
                            )
                        ) togetherWith fadeOut(tween(120))
                    }
                },
                label = "breakdownContent",
            ) { currentBreakdown ->
                if (currentBreakdown == null) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Spacer(Modifier.height(20.dp))
                        CircularProgressIndicator()
                        Spacer(Modifier.height(8.dp))
                    }
                } else if (currentBreakdown.isNotEmpty()) {

                    Column {
                        Spacer(Modifier.height(20.dp))

                        Text(
                            "Enter with",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        Spacer(Modifier.height(8.dp))

                        val breakdownScroll = rememberScrollState()

                        val maxVisibleBreakdownRows = 5
                        val breakdownRowHeight = 32.dp
                        val breakdownSpacing = 8.dp

                        val breakdownMaxHeight =
                            breakdownRowHeight * maxVisibleBreakdownRows +
                                    breakdownSpacing * (maxVisibleBreakdownRows - 1)

                        val (breakdownTop, _) = breakdownScroll.hazeAlphas()

                        HazeBox(
                            breakdownTop,
                            0f,
                            MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                Modifier.then(
                                    if (currentBreakdown.size > maxVisibleBreakdownRows) {
                                        Modifier
                                            .height(breakdownMaxHeight)
                                            .verticalScroll(breakdownScroll)
                                    } else {
                                        Modifier
                                    },
                                ),
                            ) {
                                currentBreakdown.forEachIndexed { i, entry ->

                                    Row(
                                        Modifier.height(breakdownRowHeight),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        val isYourCountry =
                                            entry.access.reason == "Your country"

                                        Text(
                                            if (isYourCountry) {
                                                "Your country"
                                            } else {
                                                entry.access.level.label()
                                            },
                                            color = statusTone(
                                                entry.access.level,
                                                LocalEffectiveDarkTheme.current,
                                                isYourCountry,
                                                entry.isOwn,
                                            ),
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

                                    if (i < currentBreakdown.size - 1) {
                                        Spacer(Modifier.height(breakdownSpacing))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
