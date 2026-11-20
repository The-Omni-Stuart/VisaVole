package com.cbkres.visavole.ui.components.statuscard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cbkres.visavole.R
import com.cbkres.visavole.domain.AllowanceSnapshot
import com.cbkres.visavole.ui.HazeBox
import com.cbkres.visavole.ui.LocalReducedMotion
import com.cbkres.visavole.ui.hazeAlphas
import com.cbkres.visavole.ui.ringColor
import com.cbkres.visavole.ui.screens.map.LocalEffectiveDarkTheme
import com.cbkres.visavole.ui.toUtcIsoDate
import kotlinx.coroutines.delay
import java.time.LocalDate

/**
 * The tab-top allowance display, shared by the Trips tab (zone allowances) and the Documents tab
 * (documents with an expiry): the focused allowance's ring and summary up top, with the rest as a
 * horizontally scrollable card row beneath it. Tapping the ring fires [onRingClick] (the screens
 * scroll the matching document/trip into view); [titleRow] replaces the plain title text — the
 * Documents tab renders its type badge pill plus the country name there.
 *
 * Pure display: the focus and flash behaviour both tabs share lives in [AllowanceFocus] and
 * [FlashTrigger], which the screens hold and read from.
 */
@Composable
internal fun StatusCard(
    allowances: List<AllowanceSnapshot>,
    primary: AllowanceSnapshot?,
    focusedKey: String?,
    today: LocalDate,
    onFocus: (String) -> Unit,
    onRingClick: (AllowanceSnapshot) -> Unit = {},
    titleRow: (@Composable RowScope.(AllowanceSnapshot) -> Unit)? = null,
) {
    if (allowances.isEmpty()) {
        Text("No allowances to track yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val rotates = primary?.let { allowanceFaceRotates(it) || ringDaysFaceRotates(it) } ?: false
    val reducedMotion = LocalReducedMotion.current
    var faceFlip by remember(primary?.key) { mutableStateOf(false) }
    LaunchedEffect(rotates, reducedMotion, primary?.key) {
        if (!rotates || reducedMotion) {
            faceFlip = false
            return@LaunchedEffect
        }
        while (true) {
            delay(5_000)
            faceFlip = !faceFlip
        }
    }
    primary?.let {
        val alternate = if (faceFlip) allowanceAlternateLine(it) else null
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .clip(CircleShape) // clip the ripple to the donut
                    .clickable(onClick = { onRingClick(it) }),
            ) {
                Deportometer(it, faceFlip = faceFlip)
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
                it.projection?.let { p ->
                    Text(
                        "On ${p.on.toUtcIsoDate()} · ${p.remainingDays} of ${p.totalDays} days",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        alternate ?: allowanceSummary(it),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.labelMedium,
                        color = ringColor(it, LocalEffectiveDarkTheme.current),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // Honest date for the day-dependent numbers the summary just printed.
                    Text(
                        stringResource(R.string.status_today, today.toUtcIsoDate()),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
private fun AllowanceCard(a: AllowanceSnapshot, focused: Boolean, onClick: () -> Unit) {
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
            Text(allowanceSummary(a), style = MaterialTheme.typography.labelSmall, color = ringColor(a, LocalEffectiveDarkTheme.current))
        }
    }
}