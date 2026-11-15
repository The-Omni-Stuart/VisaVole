package com.cbkres.visavole.data

/** A stay rule's display label: its own zone name, else the zone id it belongs to. */
internal fun zoneLabel(zoneName: String?, zone: String?): String = zoneName ?: zone ?: ""

/** Parse a comma list of entry types like `double,multiple`; null/blank = no restriction. */
internal fun parseEntryTypes(csv: String?): Set<String> {
    if (csv == null) return emptySet()
    return csv.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
}
