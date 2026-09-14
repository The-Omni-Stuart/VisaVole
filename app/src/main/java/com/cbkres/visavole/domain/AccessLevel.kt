package com.cbkres.visavole.domain

/**
 * The effective ability to enter a destination, best-to-worst.
 *
 * FREEDOM       — own country, or a passport freedom-of-movement bloc (bloc citizenship).
 * RESIDENCE     — a residence country: a residence permit (live/work rights) in that country.
 * VISA_FREE     — no step needed: visa-free / visa-on-arrival, or a residence grants short-stay.
 * COVERED       — baseline needs a visa but the user already holds a document for it.
 * ETA           — quick online pre-authorisation (eTA / ESTA / ETIAS), approved almost instantly.
 * E_VISA        — apply for an e-visa online (more involved than an ETA, no embassy visit).
 * VISA_REQUIRED — must apply for a visa, no document held (embassy / consulate).
 * REFUSED       — entry refused; applies only when nothing you hold opens it and every passport refuses.
 * UNKNOWN       — no documented rule for this pair.
 */
enum class AccessLevel(val rank: Int) {
    FREEDOM(8),
    RESIDENCE(7),
    VISA_FREE(6),
    COVERED(5),
    ETA(4),
    E_VISA(3),
    VISA_REQUIRED(2),
    REFUSED(1),
    UNKNOWN(0);

    fun label(): String = when (this) {
        FREEDOM -> "Freedom of movement"
        RESIDENCE -> "Residence permit"
        VISA_FREE -> "Visa-free / on arrival"
        COVERED -> "Covered by a document"
        ETA -> "ETA (pre-authorisation)"
        E_VISA -> "e-visa (apply online)"
        VISA_REQUIRED -> "Visa required"
        REFUSED -> "Entry refused"
        UNKNOWN -> "No documented rule"
    }

    companion object {
        fun fromBaselineType(type: String?): AccessLevel = when (type) {
            "freedom-of-movement" -> FREEDOM
            "visa-free", "visa-on-arrival" -> VISA_FREE
            "eta" -> ETA
            "e-visa" -> E_VISA
            "visa-required" -> VISA_REQUIRED
            "refused" -> REFUSED
            else -> UNKNOWN
        }

        /** True for benefits that grant entry (transit-free and others are ignored for the entry map). */
        fun isEntryBenefit(type: String?): Boolean =
            type in setOf("visa-free", "e-visa", "eta", "visa-on-arrival")
    }
}
