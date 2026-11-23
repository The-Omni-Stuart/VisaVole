package com.cbkres.visavole.ui.screens.trips

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.cbkres.visavole.domain.AllowanceSnapshot
import com.cbkres.visavole.ui.PILL_MAX_WIDTH
import com.cbkres.visavole.ui.LocalReducedMotion
import com.cbkres.visavole.ui.NO_VALID_DOC_PILL
import com.cbkres.visavole.ui.StatusPill
import com.cbkres.visavole.ui.ringColor
import com.cbkres.visavole.ui.screens.map.LocalEffectiveDarkTheme
import com.cbkres.visavole.ui.screens.map.Severity
import com.cbkres.visavole.ui.screens.map.severityColor
import kotlinx.coroutines.delay

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
 * zone at once instead of re-announcing on each flip. A zone whose snapshot is flagged
 * [AllowanceSnapshot.noValidDoc] (an active stop with no document) shows "no valid doc" in place
 * of its allowance, in the status red.
 */
@Composable
internal fun CyclingZonePill(zones: List<AllowanceSnapshot>) {
    if (zones.isEmpty()) return
    val isDark = LocalEffectiveDarkTheme.current
    val error = severityColor(
        Severity.BAD,
        isDark
    )
    var face by remember(zones) { mutableIntStateOf(0) }
    val reducedMotion = LocalReducedMotion.current
    LaunchedEffect(zones, reducedMotion) {
        if (zones.size < 2 || reducedMotion) {
            face = 0
            return@LaunchedEffect
        }
        while (true) {
            delay(if (face == 0) 10_000 else 5_000)
            face = (face + 1) % zones.size
        }
    }
    fun faceText(a: AllowanceSnapshot) =
        if (a.noValidDoc) "${a.shortTitle ?: a.title}: $NO_VALID_DOC_PILL" else labeledZoneHint(a)
    fun faceColor(a: AllowanceSnapshot) =
        if (a.noValidDoc) error else ringColor(
            a,
            isDark
        )
    // Crossfade keeps rendering the previous target through the whole fade and its content lambda
    // captures the newest zones, so target the snapshot object rather than the face index: the
    // outgoing face renders the old object, which stays valid after the list shrinks.
    val currentFace = zones.getOrNull(face) ?: zones.first()
    Box(
        modifier = Modifier.semantics(mergeDescendants = true) {
            contentDescription = zones.joinToString("; ") { faceText(it) }
        },
    ) {
        zones.forEach { a ->
            StatusPill(
                faceText(a),
                faceColor(a),
                modifier = Modifier.alpha(0f),
                maxWidth = PILL_MAX_WIDTH,
            )
        }
        Crossfade(targetState = currentFace, animationSpec = tween(300)) { a ->
            StatusPill(
                faceText(a),
                faceColor(a),
                maxWidth = PILL_MAX_WIDTH,
            )
        }
    }
}
