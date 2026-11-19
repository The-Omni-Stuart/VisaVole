package com.cbkres.visavole.domain

import java.time.LocalDate

/** The one lenient ISO date read: an unreadable value must read as absent, never throw. Rejections are rejected by shape, not by an exception, because user-typed text fails constantly. */
internal fun String?.toDateOrNull(): LocalDate? {
    if (this == null || !isIsoLocalDateShape()) return null
    return runCatching { LocalDate.parse(this) }.getOrNull()
}

private fun String.isIsoLocalDateShape(): Boolean {
    if (length != 10) return false
    if (this[4] != '-' || this[7] != '-') return false
    for (i in intArrayOf(0, 1, 2, 3, 5, 6, 8, 9)) if (this[i] < '0' || this[i] > '9') return false
    return true
}
