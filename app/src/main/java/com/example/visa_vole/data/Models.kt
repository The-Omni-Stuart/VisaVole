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

/** A grant from `visa_benefits`: what a [holding] document confers at [destination]. */
data class Benefit(val holding: String, val destination: String, val type: String, val days: Int?)

/** In-memory snapshot of the whole VisaDB, loaded once and read by the pure [com.example.visa_vole.domain.AccessModel]. */
data class WorldData(
    val countries: Map<String, Country>,
    val baseline: Map<String, List<Corridor>>,
    val regimes: List<Regime>,
    val holdings: Map<String, Holding>,
    val benefits: Map<String, List<Benefit>>,
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
            "visa" -> setOf("short_term_visa", "long_term_visa")
            else -> setOf("special_permit", "long_term_visa") // permit
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
}
