package com.example.visa_vole.domain

import com.example.visa_vole.data.WorldData

/** Holding categories the app treats as residence documents (the Residence tab). */
val RESIDENCE_LIKE_HOLDING_CATEGORIES = setOf("residency", "long_term_visa")

/** The class of a residence document, weakest to strongest. */
enum class ResidenceClass(val id: String, val label: String) {
    TEMPORARY("temporary", "Temporary"),
    LONG_TERM("long_term", "Long-term"),
    PERMANENT("permanent", "Permanent");

    companion object {
        fun fromId(id: String?): ResidenceClass? = entries.firstOrNull { it.id == id }
    }
}

/**
 * The effective residence class of a document: null for passports and visa/permit documents; for
 * residence documents the stored class, or the default inferred from its known holding.
 */
fun Document.residenceClassFor(world: WorldData): ResidenceClass? = when (val k = kind) {
    is DocKind.Passport -> null
    is DocKind.Holding -> {
        val h = world.holdings[k.holdingId] ?: return null
        if (h.category in RESIDENCE_LIKE_HOLDING_CATEGORIES) residenceClass ?: defaultResidenceClassFor(h.id, false)
        else null
    }
    is DocKind.Custom -> if (k.kind == "residence") residenceClass ?: ResidenceClass.TEMPORARY else null
}

/**
 * The default residence class for a known holding; permanent-residency documents default to
 * PERMANENT, the long-term Singapore permit to LONG_TERM, everything else to TEMPORARY.
 */
fun defaultResidenceClassFor(holdingId: String?, isResidenceCustom: Boolean): ResidenceClass = when {
    holdingId in setOf("us-green-card", "ca-pr", "au-pr") -> ResidenceClass.PERMANENT
    holdingId == "sg-visa" -> ResidenceClass.LONG_TERM
    else -> ResidenceClass.TEMPORARY
}
