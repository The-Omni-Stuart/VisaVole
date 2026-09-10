package com.example.visa_vole.domain

/** What a document is, and what it unlocks. */
sealed class DocKind {
    /** A nationality. Contributes the passport's baseline corridors + its bloc (regime) freedom. */
    data class Passport(val iso2: String) : DocKind()

    /** A known VisaDB holding (the 13 bespoke documents, e.g. `schengen-residence`, `us-visa`). */
    data class Holding(val holdingId: String) : DocKind()

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
)
