package com.cbkres.visavole.ui.components.statuscard

import com.cbkres.visavole.domain.AllowanceKind
import com.cbkres.visavole.domain.AllowanceSnapshot
import com.cbkres.visavole.domain.AllowanceStatus
import com.cbkres.visavole.domain.ResetMode
import com.cbkres.visavole.domain.isWindow
import com.cbkres.visavole.ui.entriesRemainingPhrase
import com.cbkres.visavole.ui.spoken

/**
 * The coloured summary line: the allowance's state in one phrase (same words as the cards' pills).
 * Entry-count documents carry both halves — "N days left · N entries remaining" — dot-joined, so a
 * visa gets the same coloured treatment as a residence's "Valid for N days".
 */
internal fun allowanceSummary(a: AllowanceSnapshot): String = when {
    a.noValidDoc -> "No valid document"
    a.overstayDays > 0 -> "-${a.overstayDays} Days OVERSTAYING!"
    a.status == AllowanceStatus.DANGER && !a.kind.isWindow ->
        "Expired on ${a.effectiveExpiry ?: "unknown"}"
    a.kind == AllowanceKind.ENTRY_COUNT -> buildList {
        a.remainingDays?.let {
            add(when {
                it <= 0 -> "Expires today"
                it == 1 -> "1 day left"
                else -> "$it days left"
            })
        }
        a.entryRemaining?.let { add(entriesRemainingPhrase(it)) }
        if (isEmpty()) add(a.subtitle ?: "No limit")
    }.joinToString(" · ")
    a.remainingDays != null && a.totalDays == null ->
        "Valid for ${a.remainingDays} ${if (a.remainingDays == 1) "day" else "days"}"
    a.totalDays != null && a.remainingDays != null ->
        "${a.remainingDays} of ${a.totalDays} ${if (a.totalDays == 1) "day" else "days"} left"
    else -> a.subtitle ?: "No limit"
}

/** The rotating half of the summary slot: recovery dates or reset wording, null to stay silent. */
internal fun allowanceAlternateLine(a: AllowanceSnapshot): String? {
    if (a.noValidDoc || a.overstayDays > 0) return null
    return when (a.kind) {
        AllowanceKind.ROLLING -> a.takeIf { it.currentTripId == null }?.recovery?.let {
            if (it.inProgress) "Regenerating for ${it.sliceDays} days"
            else "Regenerating from ${it.firstSliceBackOn} (${it.sliceDays} days)"
        }
        AllowanceKind.PER_ENTRY -> a.takeIf { it.currentTripId != null }?.let {
            when {
                it.entryRemaining == 0 -> null
                it.resetMode == ResetMode.AT_NEW_DOCUMENT && it.entryTotal == 1 -> "this entry only"
                it.resetMode == ResetMode.AT_NEW_DOCUMENT && it.entryRemaining == 1 -> "final entry"
                it.resetMode == ResetMode.AT_NEW_DOCUMENT -> "resets on your next entry"
                it.resetMode == ResetMode.AT_BORDER -> "resets on re-entry"
                else -> null
            }
        }
        else -> null
    }
}

internal fun allowanceFaceRotates(a: AllowanceSnapshot): Boolean =
    allowanceAlternateLine(a) != null && a.recovery?.inProgress != true

/** One static accessibility string carrying both halves, with recovery dates in spoken form. */
internal fun allowanceSpokenDescription(a: AllowanceSnapshot): String = buildString {
    append("Allowance: ").append(allowanceSummary(a))
    val recovery = a.recovery
    if (a.kind == AllowanceKind.ROLLING && recovery != null && allowanceAlternateLine(a) != null) {
        if (recovery.inProgress) {
            append("; regenerating for ").append(recovery.sliceDays).append(" days")
        } else {
            append("; regenerating from ").append(recovery.firstSliceBackOn.spoken())
            append(" (").append(recovery.sliceDays).append(" days)")
        }
    }
}