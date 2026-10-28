package com.cbkres.visavole.ui.components.statuscard

import com.cbkres.visavole.domain.AllowanceKind
import com.cbkres.visavole.domain.AllowanceSnapshot
import com.cbkres.visavole.domain.AllowanceStatus
import com.cbkres.visavole.ui.entriesRemainingPhrase

/**
 * The coloured summary line: the allowance's state in one phrase (same words as the cards' pills).
 * Entry-count documents carry both halves — "N days left · N entries remaining" — dot-joined, so a
 * visa gets the same coloured treatment as a residence's "Valid for N days".
 */
internal fun allowanceSummary(a: AllowanceSnapshot): String = when {
    a.noValidDoc -> "No valid document"
    a.overstayDays > 0 -> "-${a.overstayDays} Days OVERSTAYING!"
    a.status == AllowanceStatus.DANGER && a.kind != AllowanceKind.ROLLING && a.kind != AllowanceKind.PER_ENTRY ->
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