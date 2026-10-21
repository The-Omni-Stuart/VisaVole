package com.cbkres.visavole.ui.screens.trips

import com.cbkres.visavole.domain.DocKind
import com.cbkres.visavole.domain.Document

/** Display label for [doc]; when the holder keeps several passports of one nationality, append a
 * stable "(N)" (e.g. "Passport · United Kingdom (2)") so it's clear which passport a stop refers to. */
internal fun Document.displayLabel(counts: Map<String, Int>, numbers: Map<String, Int>): String {
    val iso = (kind as? DocKind.Passport)?.iso2 ?: return label
    val n = numbers[id] ?: return label
    return if ((counts[iso] ?: 0) >= 2) "$label  ($n)" else label
}

/** Width-safe [displayLabel] for the collapsed preview field: a passport drops the "Passport · "
 * prefix so the nationality and "(N)" number both fit (e.g. "United Kingdom (1)"). */
internal fun Document.compactDisplayLabel(counts: Map<String, Int>, numbers: Map<String, Int>): String =
    displayLabel(counts, numbers).removePrefix("Passport · ")