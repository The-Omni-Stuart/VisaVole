package com.cbkres.visavole.data

/**
 * Parse a JSON array of ISO2 strings like `["AT", "BE"]` without a JSON library. Shared by the
 * Android ([VisaRepository]) and JVM ([JdbcRepository]) repositories so both behave IDENTICALLY on
 * malformed input: a bad string yields a best-effort list, never a crash. Empty and literal-`"null"`
 * elements are skipped.
 */
internal fun parseIso2Array(json: String?): List<String> {
    if (json == null) return emptyList()
    val inner = json.trim().removePrefix("[").removeSuffix("]")
    if (inner.isBlank()) return emptyList()
    return inner.split(",").map { it.trim().trim('"') }.filter { it.isNotEmpty() && it != "null" }
}
