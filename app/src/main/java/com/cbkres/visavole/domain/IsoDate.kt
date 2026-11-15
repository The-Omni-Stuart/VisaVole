package com.cbkres.visavole.domain

import java.time.LocalDate

/**
 * The one lenient ISO date read: user-entered and database dates are text, and an unreadable
 * value must read as absent rather than throw at whoever is computing an expiry. Format is
 * still strictly ISO_LOCAL_DATE.
 */
internal fun String?.toDateOrNull(): LocalDate? =
    this?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
