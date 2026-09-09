package com.example.visa_vole.data

/** A country as stored in the VisaDB `countries` table. */
data class Country(val iso2: String, val name: String)

/**
 * One baseline rule from `visa_rules`: what a [passport] nationality needs to enter [destination].
 * [type] is one of: visa-free, e-visa, eta, visa-on-arrival, visa-required, refused.
 */
data class Corridor(val passport: String, val destination: String, val type: String, val days: Int?)

/** A mobility bloc from `mobility_regimes`; [members] are ISO2 codes, [level] is freedom-of-movement or visa-free. */
data class Regime(val id: String, val name: String, val level: String, val members: List<String>)

/** A held-document category from `visa_holdings`; [category] is residency / short_term_visa / long_term_visa / special_permit. */
data class Holding(val id: String, val name: String, val category: String, val issuingCountry: String)

/** A grant from `visa_benefits`: what a [holding] document confers at [destination].
 *  [entryTypes] restricts the grant to documents with one of these entry types
 *  ("single"/"double"/"multiple"); empty = applies to every entry type. */
data class Benefit(
    val holding: String,
    val destination: String,
    val type: String,
    val days: Int?,
    val entryTypes: Set<String> = emptySet(),
)

/** A per-destination stay/entry rule from `stay_rules`: the window and entry type. */
data class StayRule(
    val zoneName: String,
    val countries: Set<String>,
    val windowType: String, // "rolling" | "per-entry"
    val windowDays: Int?,
    val windowPeriodDays: Int?,
    val multipleEntry: Boolean,
    val nationalities: Set<String>, // "*" or an ISO2 or a bloc code (e.g. "EU-EEA")
    val note: String?,
) {
    /** Compact window text for the primary days display, e.g. "90 in 180 (rolling)" or "90 per entry". */
    fun windowSummary(): String = when (windowType) {
        "rolling" -> when {
            windowDays != null && windowPeriodDays != null -> "$windowDays in $windowPeriodDays (rolling)"
            windowDays != null -> "$windowDays (rolling)"
            else -> "rolling"
        }
        else -> if (windowDays != null) "$windowDays per entry" else "per entry"
    }

    /**
     * Full human summary, e.g. "Multiple entry - 90 in 180 (rolling)".
     *
     * `multipleEntry` is a confirmed-fact flag: for rolling windows, `false` means the source did
     * not confirm multiple entry, so the summary omits the entry count rather than claiming
     * "Single entry".
     */
    fun summary(): String {
        val window = windowSummary()
        val entry = when {
            multipleEntry -> "Multiple entry"
            windowType == "per-entry" -> "Single entry"
            else -> ""
        }
        return if (entry.isEmpty()) window else "$entry - $window"
    }
}

/** In-memory snapshot of the whole VisaDB, loaded once and read by the pure [com.example.visa_vole.domain.AccessModel]. */
data class WorldData(
    val countries: Map<String, Country>,
    val baseline: Map<String, List<Corridor>>,
    val regimes: List<Regime>,
    val holdings: Map<String, Holding>,
    val benefits: Map<String, List<Benefit>>,
    val stayRules: List<StayRule> = emptyList(),
) {
    /**
     * The best mobility bloc for the given [isos]: a bloc containing every selected country,
     * free-movement blocs preferred over visa-free ones, then the largest. Falls back to the best
     * bloc any selected country belongs to when no single bloc covers all of them.
     */
    fun mobilityBlocFor(isos: Collection<String>): Regime? {
        if (isos.isEmpty()) return null
        val score = { r: Regime -> (if (r.level == "freedom-of-movement") 1_000_000 else 0) + r.members.size }
        return regimes.filter { r -> isos.all { it in r.members } }.maxByOrNull { score(it) }
            ?: regimes.filter { r -> isos.any { it in r.members } }.maxByOrNull { score(it) }
    }

    /**
     * The mobility bloc a residence/visa should use as its short-stay travel area: the largest
     * visa-free bloc covering the selected countries. A paper you hold earns short-stay visa-free
     * travel, not freedom of movement — so an EU residence maps to the Schengen visa-free bloc, not
     * the EU/EEA/EFTA freedom bloc (which wrongly grants non-Schengen states such as Ireland).
     * Returns null when no visa-free bloc covers the selected countries.
     */
    fun travelBlocFor(isos: Collection<String>): Regime? {
        if (isos.isEmpty()) return null
        val vf = regimes.filter { it.level == "visa-free" }
        return vf.filter { r -> isos.all { it in r.members } }.maxByOrNull { it.members.size }
            ?: vf.filter { r -> isos.any { it in r.members } }.maxByOrNull { it.members.size }
    }

    /**
     * Known holdings whose mobility area spans a whole bloc — their [Holding.issuingCountry] is a
     * pseudo-code or a representative member rather than the whole area. Any other holding covers
     * exactly its issuing country.
     */
    private val blocHoldings: Map<String, String> = mapOf(
        "EU" to "eu-eea-efta", // Schengen/EU residence & visa
        "SA" to "gcc",          // GCC residency (SA/QA/OM/BH/KW)
    )

    /**
     * The known holding that best matches a custom document built from [countries] + [kind], so its
     * travel perks apply automatically (e.g. a Czech residence → `schengen-residence`). Returns null
     * when no known holding covers the selected area.
     */
    fun holdingFor(countries: Collection<String>, kind: String): String? {
        val cats = when (kind) {
            "residence" -> setOf("residency", "long_term_visa")
            else -> setOf("short_term_visa", "long_term_visa", "special_permit") // "visa" + the APEC card
        }
        val coverage = { h: Holding ->
            blocHoldings[h.issuingCountry]
                ?.let { id -> regimes.firstOrNull { it.id == id }?.members ?: emptySet() }
                ?: setOf(h.issuingCountry)
        }
        return holdings.values
            .filter { it.category in cats }
            .sortedByDescending { coverage(it).count { c -> c in countries } }
            .firstOrNull { coverage(it).any { c -> c in countries } }
            ?.id
    }

    /** Members of the EU/EEA/EFTA freedom bloc — the "EU-EEA" nationality code used in `stay_rules`. */
    val euEeaMembers: Set<String> by lazy {
        regimes.firstOrNull { it.id == "eu-eea-efta" }?.members?.toSet() ?: emptySet()
    }

    /**
     * The best-matching stay/entry rule for [dest] given the traveller's [passports].
     *
     * A rule applies to a destination when [dest] is in its [StayRule.countries]. Among those, the
     * most specific nationality match wins: a named passport (3) beats the EU-EEA bloc code (2),
     * which beats the "any" wildcard (1). Returns null when no rule names the destination or none
     * matches the traveller's nationality.
     */
    fun stayRuleFor(dest: String, passports: Set<String>): StayRule? {
        val cands = stayRules.filter { dest in it.countries }
        if (cands.isEmpty()) return null
        val hasEuEea = passports.any { it in euEeaMembers }
        fun score(nats: Set<String>): Int = when {
            passports.any { it in nats } -> 3 // a named passport
            "EU-EEA" in nats && hasEuEea -> 2 // the EU-EEA bloc code
            "*" in nats -> 1 // any nationality
            else -> 0
        }
        return cands.maxByOrNull { score(it.nationalities) }
            ?.takeIf { score(it.nationalities) > 0 }
    }
}
