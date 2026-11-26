package com.cbkres.visavole.ui.components.statuscard

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.cbkres.visavole.domain.AllowanceSnapshot
import com.cbkres.visavole.ui.HazeBox
import com.cbkres.visavole.ui.LocalReducedMotion
import com.cbkres.visavole.ui.hazeAlphas
import com.cbkres.visavole.ui.ringColor
import com.cbkres.visavole.ui.screens.EmptyState
import com.cbkres.visavole.ui.screens.map.LocalEffectiveDarkTheme
import kotlinx.coroutines.delay

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
    onFocus: (String) -> Unit,
    onRingClick: (AllowanceSnapshot) -> Unit = {},
    titleRow: (@Composable RowScope.(AllowanceSnapshot) -> Unit)? = null,
) {
    if (allowances.isEmpty()) {
        EmptyState("No allowances to track yet.")
        return
    }
    val faceMode = when {
        primary == null -> FaceMode.STATIC
        primary.recovery?.inProgress == true && allowanceAlternateLine(primary) != null -> FaceMode.HOLD
        allowanceFaceRotates(primary) || ringDaysFaceRotates(primary) -> FaceMode.ROTATE
        else -> FaceMode.STATIC
    }
    val reducedMotion = LocalReducedMotion.current
    var faceFlip by remember(primary?.key) { mutableStateOf(false) }
    val shownFlip = faceMode == FaceMode.HOLD || faceFlip
    LaunchedEffect(faceMode, reducedMotion, primary?.key) {
        if (faceMode != FaceMode.ROTATE || reducedMotion) {
            faceFlip = false
            return@LaunchedEffect
        }
        while (true) {
            delay(5_000)
            faceFlip = !faceFlip
        }
    }
    primary?.let {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .clip(CircleShape) // clip the ripple to the donut
                    .clickable(onClick = { onRingClick(it) }),
            ) {
                Deportometer(it, faceFlip = shownFlip)
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
                Crossfade(targetState = shownFlip, animationSpec = tween(300)) { flip ->
                    val line = if (flip) allowanceAlternateLine(it) ?: allowanceSummary(it) else allowanceSummary(it)
                    Text(
                        line,
                        Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.labelMedium,
                        color = ringColor(it, LocalEffectiveDarkTheme.current),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
    val switcherScroll = rememberScrollState()
    val (switcherStart, switcherEnd) = switcherScroll.hazeAlphas()
    HazeBox(switcherStart, switcherEnd, MaterialTheme.colorScheme.background, horizontal = true, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(switcherScroll), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            allowances.forEach { a ->
                AllowanceCard(a, focused = a.key == focusedKey, onClick = { onFocus(a.key) })
            }
        }
    }
}

/** One switchable card in the allowance card row (the focused one takes the selection fill). */
@Composable
private fun AllowanceCard(a: AllowanceSnapshot, focused: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (focused) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
        // Clip the ripple to the card shape; selectable carries the focused state to TalkBack.
        modifier = Modifier
            .width(168.dp)
            .clip(MaterialTheme.shapes.medium)
            .selectable(selected = focused, role = Role.Tab, onClick = onClick),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(a.title, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(allowanceSummary(a), style = MaterialTheme.typography.labelSmall, color = ringColor(a, LocalEffectiveDarkTheme.current))
        }
    }
}
/** The status ring's face decision: flip between faces, hold the recovery face, or show one face. */
private enum class FaceMode { ROTATE, HOLD, STATIC }
