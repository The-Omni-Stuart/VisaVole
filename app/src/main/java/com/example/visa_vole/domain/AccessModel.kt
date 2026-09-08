package com.example.visa_vole.domain

import com.example.visa_vole.data.WorldData
import com.example.visa_vole.domain.AccessLevel.COVERED
import com.example.visa_vole.domain.AccessLevel.FREEDOM
import com.example.visa_vole.domain.AccessLevel.REFUSED
import com.example.visa_vole.domain.AccessLevel.RESIDENCE
import com.example.visa_vole.domain.AccessLevel.UNKNOWN
import com.example.visa_vole.domain.AccessLevel.VISA_FREE
import com.example.visa_vole.domain.DocKind.Custom
import com.example.visa_vole.domain.DocKind.Holding
import com.example.visa_vole.domain.DocKind.Passport

/**
 * Pure access model: given the user's held documents (each passport counts as a nationality) and
 * the [WorldData] snapshot, computes the effective [Access] for every destination.
 *
 * Merge rules:
 *  - every passport is merged with a pure best-of, so a refusal from one passport never overrides
 *    another passport's good access ("stronger above stronger");
 *  - documents (visas, residences, customs) are then merged best-of and can override a passport
 *    refusal — a paper you hold unlocks its destinations (e.g. a CZ residence grants EU short-stay
 *    even if your passport is refused); a destination is REFUSED only when nothing you hold opens it;
 *  - a document never worsens a destination (best-of guarantees relax-only).
 */
object AccessModel {

    /** Pure best-of: higher [AccessLevel.rank] wins; on a tie, the one carrying a days limit. */
    private fun best(a: Access?, b: Access): Access {
        if (a == null) return b
        if (b.level.rank > a.level.rank) return b
        if (a.level.rank > b.level.rank) return a
        if (a.days == null && b.days != null) return b
        return a
    }

    private fun merge(map: MutableMap<String, Access>, dest: String, candidate: Access) {
        map[dest] = best(map[dest], candidate)
    }

    /** Candidates from a passport: baseline corridors + bloc freedom/visa-free + own country. */
    private fun passportCandidates(home: String, world: WorldData): Map<String, Access> {
        val map = LinkedHashMap<String, Access>()
        for (c in world.baseline[home].orEmpty()) {
            merge(map, c.destination, Access(AccessLevel.fromBaselineType(c.type), c.days, "Passport rule"))
        }
        for (r in world.regimes) {
            if (home in r.members) {
                val level = if (r.level == "freedom-of-movement") FREEDOM else VISA_FREE
                val reason = if (r.level == "freedom-of-movement") "Bloc: ${r.name}" else "Bloc visa-free: ${r.name}"
                for (m in r.members) merge(map, m, Access(level, null, reason))
            }
        }
        // Your own country always beats any bloc's freedom of movement.
        map[home] = Access(FREEDOM, null, "Your country")
        return map
    }

    /** Candidates from a held document (residence / visa / permit / custom). */
    private fun documentCandidates(doc: Document, world: WorldData): Map<String, Access> {
        val map = LinkedHashMap<String, Access>()
        when (val k = doc.kind) {
            is Passport -> { /* contributed via passportCandidates */ }
            is Holding -> {
                val h = world.holdings[k.holdingId] ?: return emptyMap()
                val isResidence = h.category == "residency"
                merge(
                    map, h.issuingCountry,
                    Access(if (isResidence) RESIDENCE else COVERED, null, if (isResidence) "Residence: ${h.name}" else "You hold: ${h.name}"),
                )
                val benefitLevel = if (isResidence) VISA_FREE else COVERED
                for (b in world.benefits[k.holdingId].orEmpty()) {
                    if (!AccessLevel.isEntryBenefit(b.type)) continue // skip transit-only rows
                    merge(map, b.destination, Access(benefitLevel, b.days, "${h.name} (${b.type})"))
                }
            }
            is Custom -> {
                val targets = LinkedHashSet<String>()
                targets += k.countries
                if (k.blocId != null) world.regimes.firstOrNull { it.id == k.blocId }?.let { targets += it.members }
                val isResidence = k.kind == "residence"
                val residenceCountry = k.countries.firstOrNull()
                for (c in targets) {
                    val level = when {
                        isResidence && c == residenceCountry -> RESIDENCE
                        isResidence -> VISA_FREE
                        else -> COVERED
                    }
                    merge(map, c, Access(level, null, "Your ${k.kind}"))
                }
                // A known holding tied to this document also applies its travel perks (entry benefits only).
                k.holdingId?.let { hid ->
                    val h = world.holdings[hid] ?: return@let
                    val perkLevel = if (isResidence) VISA_FREE else COVERED
                    for (b in world.benefits[hid].orEmpty()) {
                        if (!AccessLevel.isEntryBenefit(b.type)) continue
                        merge(map, b.destination, Access(perkLevel, b.days, "${h.name} (${b.type})"))
                    }
                }
            }
        }
        return map
    }

    /** Every passport iso2 in [docs] — each of these is a "home" country (blue on the map). */
    fun homeCountries(docs: List<Document>): Set<String> =
        docs.mapNotNull { (it.kind as? Passport)?.iso2 }.toSet()

    /**
     * Per-document access to [dest], strongest first, for the "enter with" breakdown in the detail
     * card — so the user sees exactly which passport/document unlocks entry.
     */
    fun breakdownFor(dest: String, docs: List<Document>, world: WorldData): List<DocAccess> =
        docs.map { d ->
            val candidates = when (val k = d.kind) {
                is Passport -> passportCandidates(k.iso2, world)
                else -> documentCandidates(d, world)
            }
            val label = when (val k = d.kind) {
                is Passport -> world.countries[k.iso2]?.name ?: k.iso2
                is Holding -> world.holdings[k.holdingId]?.name ?: d.label
                is Custom -> d.label
            }
            DocAccess(label, candidates[dest] ?: Access(UNKNOWN, null))
        }.sortedByDescending { it.access.level.rank }

    /** Effective access for every destination, given the held [docs] (passports included). */
    fun compute(docs: List<Document>, world: WorldData): Map<String, Access> {
        val result = LinkedHashMap<String, Access>()
        // Tier 1: every passport, pure best-of (a single refusal never overrides a good passport).
        for (p in homeCountries(docs)) {
            for ((dest, access) in passportCandidates(p, world)) merge(result, dest, access)
        }
        // Tier 2: documents, best-of — a paper you hold unlocks its destinations even if your
        // passport is refused there (e.g. a CZ residence grants EU short-stay despite a refusal).
        for (doc in docs) {
            if (doc.kind is Passport) continue
            for ((dest, access) in documentCandidates(doc, world)) merge(result, dest, access)
        }
        return result
    }

    fun accessFor(dest: String, docs: List<Document>, world: WorldData): Access =
        compute(docs, world)[dest] ?: Access(UNKNOWN, null, "No data for $dest")
}
