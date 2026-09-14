package com.cbkres.visavole.domain

import com.cbkres.visavole.data.Benefit
import com.cbkres.visavole.data.Regime
import com.cbkres.visavole.data.StayRule
import com.cbkres.visavole.data.WorldData
import com.cbkres.visavole.domain.AccessLevel.COVERED
import com.cbkres.visavole.domain.AccessLevel.E_VISA
import com.cbkres.visavole.domain.AccessLevel.ETA
import com.cbkres.visavole.domain.AccessLevel.FREEDOM
import com.cbkres.visavole.domain.AccessLevel.REFUSED
import com.cbkres.visavole.domain.AccessLevel.RESIDENCE
import com.cbkres.visavole.domain.AccessLevel.UNKNOWN
import com.cbkres.visavole.domain.AccessLevel.VISA_FREE
import com.cbkres.visavole.domain.DocKind.Custom
import com.cbkres.visavole.domain.DocKind.Holding
import com.cbkres.visavole.domain.DocKind.Passport
import java.time.LocalDate
import java.time.ZoneOffset

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

    /** Pure best-of: higher [AccessLevel.rank] wins; on a tie, the one carrying a days limit, then
     *  the higher residence class, so the strongest held residence document is displayed. */
    private fun best(a: Access?, b: Access): Access {
        if (a == null) return b
        if (b.level.rank > a.level.rank) return b
        if (a.level.rank > b.level.rank) return a
        if (a.days == null && b.days != null) return b
        val aClass = a.residenceClass?.ordinal ?: -1
        val bClass = b.residenceClass?.ordinal ?: -1
        if (bClass > aClass) return b
        if (aClass > bClass) return a
        return a
    }

    private fun merge(map: MutableMap<String, Access>, dest: String, candidate: Access) {
        map[dest] = best(map[dest], candidate)
    }

    /** A benefit with no [Benefit.entryTypes] applies to any document; otherwise the document's
     *  entry type must be one of the listed types. A doc with no entry type (e.g. a bare
     *  [Holding]) never satisfies a restricted grant. */
    private fun entryTypeSatisfied(benefitTypes: Set<String>, docEntryType: String?): Boolean =
        benefitTypes.isEmpty() || (docEntryType != null && docEntryType in benefitTypes)

    /** A benefit with a null [Benefit.residenceMin] applies to any document; otherwise the
     *  document's effective residence class must rank at or above the minimum, and a
     *  non-residence document (no class) never satisfies a restricted grant. */
    private fun residenceMinSatisfied(doc: Document, world: WorldData, benefit: Benefit): Boolean {
        val min = ResidenceClass.fromId(benefit.residenceMin) ?: return true
        val effective = doc.residenceClassFor(world) ?: return false
        return effective.ordinal >= min.ordinal
    }

    /** True when [doc] carries an expiry date already in the past (relative to [today]). */
    private fun isExpired(doc: Document, today: LocalDate): Boolean {
        val iso = doc.expiry ?: return false
        return runCatching { LocalDate.parse(iso) }.getOrNull()?.let { it.isBefore(today) } ?: false
    }

    /**
     * Candidates from a passport: baseline corridors + bloc freedom/visa-free + own country.
     * An [expired] passport keeps its home country and freedom-of-movement bloc(s) but drops its
     * baseline visa corridors and its visa-free blocs.
     */
    private fun passportCandidates(home: String, world: WorldData, expired: Boolean): Map<String, Access> {
        val map = LinkedHashMap<String, Access>()
        if (!expired) {
            for (c in world.baseline[home].orEmpty()) {
                merge(map, c.destination, Access(AccessLevel.fromBaselineType(c.type), c.days, "Passport rule"))
            }
        }
        for (r in world.regimes) {
            if (home !in r.members) continue
            if (expired && r.level != "freedom-of-movement") continue
            val level = if (r.level == "freedom-of-movement") FREEDOM else VISA_FREE
            val reason = if (r.level == "freedom-of-movement") "Bloc: ${r.name}" else "Bloc visa-free: ${r.name}"
            for (m in r.members) merge(map, m, Access(level, null, reason))
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
                val isResidence = h.category in RESIDENCE_LIKE_HOLDING_CATEGORIES
                val rc = if (isResidence) doc.residenceClassFor(world) else null
                merge(
                    map, h.issuingCountry,
                    Access(if (isResidence) RESIDENCE else COVERED, null, if (isResidence) "Residence: ${h.name}" else "You hold: ${h.name}", true, rc),
                )
                val benefitLevel = if (isResidence) VISA_FREE else COVERED
                for (b in world.benefits[k.holdingId].orEmpty()) {
                    if (!AccessLevel.isEntryBenefit(b.type)) continue // skip transit-only rows
                    if (!entryTypeSatisfied(b.entryTypes, k.entryType)) continue
                    if (!residenceMinSatisfied(doc, world, b)) continue
                    merge(map, b.destination, Access(benefitLevel, b.days, "${h.name} (${b.type})", true))
                }
            }
            is Custom -> {
                val targets = LinkedHashSet<String>()
                targets += k.countries
                // Resolve the travel-area bloc. A document earns short-stay travel, not freedom of
                // movement — so if the stored bloc is a freedom-of-movement bloc but a visa-free bloc
                // covers the same countries, use the visa-free one. This fixes legacy EU/Schengen
                // residence docs auto-assigned the EU/EEA/EFTA freedom bloc (which over-granted
                // non-Schengen states such as Ireland). An explicit visa-free choice is respected.
                val storedBloc = k.blocId?.let { id -> world.regimes.firstOrNull { it.id == id } }
                val travelBloc: Regime? = when {
                    storedBloc == null -> world.travelBlocFor(k.countries)
                    storedBloc.level == "visa-free" -> storedBloc
                    else -> world.travelBlocFor(k.countries) ?: storedBloc
                }
                travelBloc?.let { targets += it.members }
                val isResidence = k.kind == "residence"
                val residenceCountry = k.countries.firstOrNull()
                val rc = if (isResidence) doc.residenceClassFor(world) else null
                for (c in targets) {
                    val level = when {
                        isResidence && c == residenceCountry -> RESIDENCE
                        isResidence -> VISA_FREE
                        else -> COVERED
                    }
                    merge(map, c, Access(level, null, "Your ${k.kind}", true, if (level == RESIDENCE) rc else null))
                }
                // A known holding tied to this document also applies its travel perks (entry benefits only).
                k.holdingId?.let { hid ->
                    val h = world.holdings[hid] ?: return@let
                    val perkLevel = if (isResidence) VISA_FREE else COVERED
                    for (b in world.benefits[hid].orEmpty()) {
                        if (!AccessLevel.isEntryBenefit(b.type)) continue
                        if (!entryTypeSatisfied(b.entryTypes, k.entryType)) continue
                        if (!residenceMinSatisfied(doc, world, b)) continue
                        merge(map, b.destination, Access(perkLevel, b.days, "${h.name} (${b.type})", true))
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
     * The "own" countries of every non-expired short-term visa the traveller holds — the visa's
     * home / issuing country ([Holding]) or the specific countries it was made for ([Custom]). On the
     * map these render the darker purple; the rest of the bloc a visa unlocks is the lighter shade.
     * Residences are excluded (they render teal, not purple).
     */
    fun ownVisaCountries(
        docs: List<Document>,
        world: WorldData,
        today: LocalDate = LocalDate.now(ZoneOffset.UTC),
    ): Set<String> =
        docs.flatMap { d ->
            if (isExpired(d, today)) return@flatMap emptyList()
            when (val k = d.kind) {
                is Passport -> emptyList()
                is Holding -> {
                    val h = world.holdings[k.holdingId] ?: return@flatMap emptyList()
                    if (h.category in RESIDENCE_LIKE_HOLDING_CATEGORIES) emptyList() else listOf(h.issuingCountry)
                }
                is Custom -> if (k.kind == "residence") emptyList() else k.countries.toList()
            }
        }.toSet()

    /**
     * Per-document access to [dest], strongest first, for the "enter with" breakdown in the detail
     * card — so the user sees exactly which passport/document unlocks entry. Expired non-passport
     * documents and documents with no documented rule are omitted.
     */
    fun breakdownFor(
        dest: String,
        docs: List<Document>,
        world: WorldData,
        today: LocalDate = LocalDate.now(ZoneOffset.UTC),
    ): List<DocAccess> =
        docs
            .filter { d -> d.kind is Passport || !isExpired(d, today) }
            .map { d ->
                val candidates = when (val k = d.kind) {
                    is Passport -> passportCandidates(k.iso2, world, isExpired(d, today))
                    else -> documentCandidates(d, world)
                }
                val label = when (val k = d.kind) {
                    is Passport -> world.countries[k.iso2]?.name ?: k.iso2
                    is Holding -> world.holdings[k.holdingId]?.name ?: d.label
                    is Custom -> d.label
                }
                val isOwn = when (val k = d.kind) {
                    is Passport -> false
                    is Holding -> {
                        val h = world.holdings[k.holdingId]
                        h != null && h.category !in RESIDENCE_LIKE_HOLDING_CATEGORIES && h.issuingCountry == dest
                    }
                    is Custom -> k.kind != "residence" && dest in k.countries
                }
                DocAccess(label, candidates[dest] ?: Access(UNKNOWN, null), isOwn)
            }
            .filter { it.access.level != UNKNOWN }
            .sortedByDescending { it.access.level.rank }

    /**
     * The id of the single held document to suggest for entry into [dest] on [today].
     *
     * Passports are preferred over weaker or redundant visas. A visa / residence / permit is
     * preferred when it gives stronger access, or when it is the destination residence document —
     * residents must present that permit at the border. A residence that only ties the best passport
     * (for example a bloc residence granting short-stay access) is also preferred. Expired
     * non-passport documents are ignored. Returns null when no held document has a documented rule.
     */
    fun bestDocumentId(
        dest: String,
        docs: List<Document>,
        world: WorldData,
        today: LocalDate = LocalDate.now(ZoneOffset.UTC),
    ): String? {
        val evaluated = docs
            .mapNotNull { doc ->
                if (doc.kind !is Passport && isExpired(doc, today)) return@mapNotNull null
                val candidates = when (val k = doc.kind) {
                    is Passport -> passportCandidates(k.iso2, world, isExpired(doc, today))
                    else -> documentCandidates(doc, world)
                }
                candidates[dest]?.takeIf { it.level != UNKNOWN }?.let { doc to it }
            }
        val bestPassport = evaluated
            .filter { it.first.kind is Passport }
            .maxWithOrNull { a, b -> compareDocumentAccess(a.second, b.second) }
        val bestOther = evaluated
            .filter { it.first.kind !is Passport }
            .maxWithOrNull { a, b -> compareDocumentAccess(a.second, b.second) }
        return when {
            bestPassport == null -> bestOther?.first?.id
            bestOther == null -> bestPassport.first.id
            else -> {
                val (otherDoc, otherAccess) = bestOther
                val passportAccess = bestPassport.second
                when {
                    otherAccess.level == RESIDENCE -> otherDoc.id
                    compareDocumentAccess(otherAccess, passportAccess) > 0 -> otherDoc.id
                    compareDocumentAccess(otherAccess, passportAccess) == 0 && isResidenceDocument(otherDoc, world) -> otherDoc.id
                    else -> bestPassport.first.id
                }
            }
        }
    }

    private fun isResidenceDocument(doc: Document, world: WorldData): Boolean = when (val k = doc.kind) {
        is Passport -> false
        is Holding -> world.holdings[k.holdingId]?.category in RESIDENCE_LIKE_HOLDING_CATEGORIES
        is Custom -> k.kind == "residence"
    }

    private fun compareDocumentAccess(a: Access, b: Access): Int {
        if (a.level.rank != b.level.rank) return a.level.rank - b.level.rank
        if (a.days != b.days) return (a.days ?: -1).compareTo(b.days ?: -1)
        val aClass = a.residenceClass?.ordinal ?: -1
        val bClass = b.residenceClass?.ordinal ?: -1
        return aClass - bClass
    }

    /**
     * Effective access for every destination, given the held [docs] (passports included). [today]
     * drives expiry: a lapsed visa/residence/permit grants nothing, and an expired passport keeps
     * only its home country + freedom-of-movement bloc(s).
     */
    fun compute(
        docs: List<Document>,
        world: WorldData,
        today: LocalDate = LocalDate.now(ZoneOffset.UTC),
    ): Map<String, Access> {
        val result = LinkedHashMap<String, Access>()
        for (doc in docs) {
            when (val k = doc.kind) {
                is Passport -> {
                    // Pure best-of across passports; an expired one still grants home + freedom blocs.
                    for ((dest, access) in passportCandidates(k.iso2, world, isExpired(doc, today))) {
                        merge(result, dest, access)
                    }
                }
                else -> {
                    // A lapsed document (visa / residence / permit) no longer unlocks anything.
                    if (isExpired(doc, today)) continue
                    for ((dest, access) in documentCandidates(doc, world)) merge(result, dest, access)
                }
            }
        }
        return result
    }

    fun accessFor(dest: String, docs: List<Document>, world: WorldData): Access =
        compute(docs, world)[dest] ?: Access(UNKNOWN, null, "No data for $dest")

    /**
     * The primary days label for the detail card. A [StayRule] is only shown when it matches the
     * effective access: either its window matches the access's explicit day limit, or the access is
     * a passport/bloc entry grant with no explicit day limit. This keeps a document-specific 30-day
     * grant from being mixed with an unrelated generic 90/180 passport rule.
     */
    fun stayLabelFor(access: Access?, stayRule: StayRule?): String? {
        val daysLabel = access?.days?.let { "$it days" }
        if (stayRule == null || access == null) return daysLabel
        val matches = when {
            access.days != null -> stayRule.windowDays == access.days
            !access.fromDocument -> access.level in setOf(FREEDOM, RESIDENCE, VISA_FREE, COVERED, ETA, E_VISA)
            else -> false
        }
        return if (matches) stayRule.windowSummary() else daysLabel
    }
}
