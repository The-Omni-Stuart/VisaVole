package com.cbkres.visavole.ui.components.statuscard

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cbkres.visavole.domain.AllowanceKind
import com.cbkres.visavole.domain.AllowanceSnapshot
import com.cbkres.visavole.domain.AllowanceStatus
import com.cbkres.visavole.ui.allowanceFraction
import com.cbkres.visavole.ui.entriesRemainingPhrase
import com.cbkres.visavole.ui.ringColor
import com.cbkres.visavole.ui.screens.map.LocalEffectiveDarkTheme
import kotlinx.coroutines.delay

/** Entry-count documents rotate to a days-to-expiry face this close to their expiry. */
private const val RING_ROTATE_MAX_DAYS = 90     // TODO: Probably shouldn't hard code this.

// TODO: Make this more generic since it's used for document expiery as well

/**
 * The allowance donut: the remaining window (or remaining entries) drawn in the status colour,
 * the centre showing the remaining count and a label. [size] scales the donut — the stroke and
 * the centre text follow it. An in-use entry shows "In use" with the entries remaining beneath;
 * a lapsed document shows "Expired" with the date. Entry-count documents nearing expiry (at most
 * [RING_ROTATE_MAX_DAYS] days) alternate every 5 s with a days-to-expiry face — the arc and the
 * colour stay put, only the centre text crossfades.
 */
@Composable
internal fun Deportometer(a: AllowanceSnapshot, size: Dp = 112.dp, modifier: Modifier = Modifier) {
    val color = ringColor(a, LocalEffectiveDarkTheme.current)
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
    // An overstayed allowance shows a negative count + "OVERSTAY!" so it reads as unmistakably bad.
    val overstay = a.overstayDays > 0
    // An expired document still driving an in-progress trip flashes too: you're staying on a lapsed
    // doc, which is an active illegal situation, not just a quiet "expired" note.
    val expiredInUse = a.status == AllowanceStatus.DANGER && a.inUseTripId != null
    // A zone whose active stop has no document can't actually be travelled on, so its (otherwise
    // healthy) stay window flashes "no valid doc" instead of showing a remaining count.
    val noValidDoc = a.noValidDoc
    // The ring pulses between bright red and the dim (empty) track, on a loop, so an illegal stay
    // (in-use expired doc, or a stop with no document) can't be mistaken for a healthy — or merely
    // empty — ring.
    val pulsing = overstay || expiredInUse || noValidDoc
    val pulse = remember { Animatable(1f) }
    LaunchedEffect(pulsing) {
        if (!pulsing) {
            pulse.snapTo(1f)
            return@LaunchedEffect
        }
        while (true) {
            pulse.animateTo(0.15f, tween(700, easing = FastOutSlowInEasing))
            pulse.animateTo(1f, tween(700, easing = FastOutSlowInEasing))
        }
    }
    val (center, centerLabel) = when {
        noValidDoc -> "NO DOC" to ""
        overstay -> "-${a.overstayDays}" to "OVERSTAY!"
        // A window-less (UNKNOWN) allowance has no remaining count — show "no data", not a bogus
        // "0 days left" (P2-20).
        a.status == AllowanceStatus.UNKNOWN -> "—" to "no data"
        docKind && a.status == AllowanceStatus.DANGER -> "Expired" to "on ${a.effectiveExpiry ?: "unknown"}"
        a.kind == AllowanceKind.ENTRY_COUNT && a.currentTripId != null -> "In use" to entriesRemainingPhrase(a.entryRemaining)
        a.kind == AllowanceKind.ENTRY_COUNT -> (a.entryRemaining ?: 0).toString() to "entries left"
        a.kind == AllowanceKind.DOCUMENT_VALIDITY -> (a.remainingDays ?: 0).toString() to "days to expiry"
        else -> (a.remainingDays ?: 0).toString() to "days left"
    }
    val daysCenter = a.remainingDays?.let { it.toString() to "days to expiry" }
    val showDaysFace = rotatable && daysFace
    // The centre box is a *minimum* height, not a fixed one: at normal text sizes both faces sit in
    // an identical 58dp box (so the crossfade's own size never changes and the text stays parked on
    // the ring's centre line), but at large accessibility font scales the box grows to fit the
    // scaled text instead of clipping it (P2-18).
    val faceHeight = 58.dp
    // P2-22: the donut is a Canvas plus two Text nodes; merge them into a single accessible node
    // so TalkBack announces the allowance's state in one stop instead of the number and label as
    // separate, meaningless fragments.
    val ringDescription = "Allowance: ${allowanceSummary(a)}"
    Box(
        modifier = modifier
            .size(size)
            .semantics(mergeDescendants = true) { contentDescription = ringDescription },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = (size / 11f).toPx()
            drawCircle(color = color.copy(alpha = 0.15f), style = Stroke(stroke))
            // Pulsing (overstay or in-use-expired): a full ring whose alpha pulses (see `pulse`)
            // between bright red and the dim track. Otherwise the normal remaining-fraction arc.
            val sweep = if (pulsing) 360f else 360f * fraction
            val arcColor = if (pulsing) color.copy(alpha = pulse.value) else color
            drawArc(
                color = arcColor,
                startAngle = -90f,
                sweepAngle = sweep,
                useCenter = false,
                style = Stroke(stroke),
            )
        }
        Crossfade(targetState = showDaysFace, animationSpec = tween(300)) { face ->
            val (c, l) = if (face) daysCenter ?: (center to centerLabel) else center to centerLabel
            val wordFace = c == "In use" || c == "Expired"
            Box(Modifier.heightIn(min = faceHeight), contentAlignment = Alignment.Center) {
                if (noValidDoc) {
                    // A single balanced face: "NO DOC" centred, with no tiny caption to throw the
                    // weight off (the full "No valid document" reads in the summary beside the ring).
                    Text(
                        center,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = color,
                    )
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            c,
                            // Word faces ("In use", "Expired") run one step smaller than a bare number.
                            style = if (wordFace) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = color,
                        )
                        Text(
                            l,
                            style = MaterialTheme.typography.labelSmall,
                            // "OVERSTAY!" is bold + the ring's red; other labels stay muted.
                            color = if (overstay) color else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (overstay) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }
}