package com.cbkres.visavole.data

/** Chunk size for the passport-filtered baseline query; SQLite's variable limit is 999. */
internal const val BASELINE_CHUNK = 500

// The dataset-load SQL shared by both loaders ([VisaRepository], [JdbcRepository]) so the two
// mirrors read exactly the same columns; the freshness probes live in [FreshnessSql.kt].

internal const val SQL_COUNTRIES = "SELECT iso2, name FROM countries"

/** Unfiltered baseline read; the JVM mirror uses it when no passports are pinned. */
internal const val SQL_BASELINE_ALL = "SELECT passport, destination, type, days, valid_to FROM visa_rules"

/** The chunked baseline query: [placeholders] is one `?` per chunked passport, comma-joined. */
internal fun sqlBaselineChunked(placeholders: String): String =
    "$SQL_BASELINE_ALL WHERE passport IN ($placeholders)"

internal const val SQL_REGIMES = "SELECT id, name, short_name, level, members FROM mobility_regimes"

internal const val SQL_HOLDINGS = "SELECT id, name, category, issuing_country FROM visa_holdings"

/** Benefits query, adapted to whichever optional columns this dataset build carries. */
internal fun sqlBenefits(hasResidenceMin: Boolean, hasNationalities: Boolean): String = buildString {
    append("SELECT holding, destination, type, days, entry_type")
    if (hasResidenceMin) append(", residence_min")
    if (hasNationalities) append(", nationalities")
    append(" FROM visa_benefits")
}

internal const val SQL_STAY_RULES = "SELECT * FROM stay_rules"
