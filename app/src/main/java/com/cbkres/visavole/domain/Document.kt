package com.cbkres.visavole.domain

import com.cbkres.visavole.data.WorldData

/** What a document is, and what it unlocks. */
sealed class DocKind {
    /** A nationality. Contributes the passport's baseline corridors + its bloc (regime) freedom. */
    data class Passport(val iso2: String) : DocKind()

    /** A known VisaDB holding (the 13 bespoke documents, e.g. `schengen-residence`, `us-visa`). */
    data class Holding(
        val holdingId: String,
        val entryType: String? = null, // "single" | "double" | "multiple" (visa docs only)
    ) : DocKind()

    /**
     * A fully custom document. [countries] are ISO2 codes it unlocks; [blocId] optionally adds a
     * whole bloc's members; [holdingId] optionally ties it to a known holding so that holding's
     * travel perks also apply (e.g. a Czech residence typed as `schengen-residence`). [kind] is
     * "residence" | "visa" (legacy "permit" docs are treated as a visa). A residence gives its
     * country FREEDOM and the bloc VISA_FREE short-stay; a visa/permit gives COVERED.
     */
    data class Custom(
        val countries: Set<String>,
        val blocId: String? = null,
        val kind: String = "visa",
        val holdingId: String? = null,
        val entryType: String? = null, // "single" | "double" | "multiple" (visa docs only)
    ) : DocKind()
}

/** A user-held document (passport / residence / visa / permit), persisted by the app. */
data class Document(
    val id: String,
    val label: String,
    val kind: DocKind,
    val countryNumber: String? = null,
    val expiry: String? = null, // valid to — ISO-8601 date (YYYY-MM-DD)
    val validFrom: String? = null, // valid from — ISO-8601 date (YYYY-MM-DD)
    val residenceClass: ResidenceClass? = null, // residence docs only (temporary / long-term / permanent)
    // Id of the newer document that superseded this one (one valid visa/residence per country, §13.3).
    // The superseded doc keeps its own dates; its effective expiry derives live from the replacement's
    // `validFrom` (see DocStatus), so it follows the replacement's edits and revives when the
    // replacement is deleted (the ViewModel clears dangling pointers). Passports are never superseded.
    val supersededBy: String? = null,
)

/** The document's entry type, if it carries one; passports and residence documents return null. */
fun Document.entryType(): String? = when (val k = kind) {
    is DocKind.Passport -> null
    is DocKind.Holding -> k.entryType
    is DocKind.Custom -> if (k.kind == "visa" || k.kind == "permit") k.entryType else null
}

/**
 * Identity used to detect duplicate documents. Two documents are a true duplicate when they share
 * this signature. A passport is keyed by nationality + validity window, so the same country with
 * different valid-from/expiry dates is a distinct (allowed) passport; a holding by its holding id +
 * validity window; a custom doc by its unlocked countries/bloc/kind + validity window.
 */
fun Document.duplicateSignature(): String = when (val k = kind) {
    is DocKind.Passport ->
        "P|${k.iso2}|${countryNumber.orEmpty()}|${validFrom.orEmpty()}|${expiry.orEmpty()}"
    is DocKind.Holding ->
        "H|${k.holdingId}|${k.entryType.orEmpty()}|${validFrom.orEmpty()}|${expiry.orEmpty()}"
    is DocKind.Custom ->
        "C|${k.countries.sorted().joinToString(",")}|${k.blocId.orEmpty()}|${k.kind}|" +
            "${k.holdingId.orEmpty()}|${k.entryType.orEmpty()}|${validFrom.orEmpty()}|${expiry.orEmpty()}"
}

/**
 * The document's structural identity — what makes it "the same document in the world": its type and
 * the country / bloc / holding it belongs to. Deliberately excludes its attributes (number, dates,
 * entry type, residence class), which are harmless to change. Two documents whose keys differ are a
 * different document (e.g. a UK passport vs a French one, a visa vs a residence). Used to stop a
 * document that a trip still references from being re-identified, which would silently change what
 * the trip was entered with and how its stay allowance is calculated.
 */
fun Document.identityKey(): String = when (val k = kind) {
    is DocKind.Passport -> "P|${k.iso2}"
    is DocKind.Holding -> "H|${k.holdingId}"
    is DocKind.Custom ->
        "C|${k.countries.sorted().joinToString(",")}|${k.blocId.orEmpty()}|${k.kind}|${k.holdingId.orEmpty()}"
}

/**
 * The non-passport document's category for the "one valid visa/residence per country" rule:
 * "residence" or "visa". Passports return null — their multiplicity is allowed (they are numbered
 * instead and governed by [duplicateSignature]).
 */
fun Document.docCategory(world: WorldData): String? = when (val k = kind) {
    is DocKind.Passport -> null
    is DocKind.Holding -> world.holdings[k.holdingId]?.let { h ->
        if (h.category == "residency" || h.category == "long_term_visa") "residence" else "visa"
    }
    is DocKind.Custom -> if (k.kind == "residence") "residence" else "visa"
}

/**
 * The ISO2 countries a document applies to: a passport's nationality, a holding's coverage
 * ([WorldData.holdingCountries]), or a custom document's explicit [DocKind.Custom.countries].
 */
fun Document.coveredCountries(world: WorldData): Set<String> = when (val k = kind) {
    is DocKind.Passport -> setOf(k.iso2)
    is DocKind.Holding -> world.holdingCountries(k.holdingId)
    is DocKind.Custom -> k.countries
}

/**
 * A stable 1-based number for each passport, per nationality, in the given (insertion) order. The
 * first passport added of a country is (1), the second (2), and so on. Numbering every passport —
 * valid and expired alike — keeps each one identifiable (e.g. still "(2)") even after it lapses, at
 * which point it is further distinguished by its expiry date.
 */
fun passportNumbers(docs: List<Document>): Map<String, Int> =
    docs.filter { it.kind is DocKind.Passport }
        .groupBy { (it.kind as DocKind.Passport).iso2 }
        .flatMap { (_, list) -> list.mapIndexed { i, d -> d.id to (i + 1) } }
        .toMap()

/** The total number of passports held (valid and expired) per nationality. */
fun passportCountsByIso(docs: List<Document>): Map<String, Int> =
    docs.filter { it.kind is DocKind.Passport }
        .groupingBy { (it.kind as DocKind.Passport).iso2 }
        .eachCount()
