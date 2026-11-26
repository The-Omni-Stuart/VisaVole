package com.cbkres.visavole.domain

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val HUMAN_DATE = DateTimeFormatter.ofPattern("d MMM uuuu", Locale.ENGLISH)

/** A date inside a sentence: "1 Mar 2027"; grids and date fields keep the ISO form. */
internal fun LocalDate.formatHuman(): String = format(HUMAN_DATE)
