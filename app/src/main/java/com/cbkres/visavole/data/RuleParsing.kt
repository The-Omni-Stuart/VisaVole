package com.cbkres.visavole.data

/** A stay rule's display label: its own zone name, else the zone id it belongs to. */
internal fun zoneLabel(zoneName: String?, zone: String?): String = zoneName ?: zone ?: ""

/** Parse a list of entry types, CSV or JSON: a token that fails to match would silently void a grant. */
internal fun parseEntryTypes(value: String?): Set<String> {
    if (value.isNullOrBlank()) return emptySet()
    return value.trim().removeSurrounding("[", "]").split(",")
        .map { it.trim().removeSurrounding("\"").trim() }
        .filter { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
        .toSet()
}
