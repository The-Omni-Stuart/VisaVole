package com.cbkres.visavole.domain

/** The computed result for one destination: the effective [level], an optional [days] limit, and
 *  why. [fromDocument] is true when the access comes from a visa/residence/permit rather than a
 *  passport. */
data class Access(
    val level: AccessLevel,
    val days: Int?,
    val reason: AccessReason? = null,
    val fromDocument: Boolean = false,
    val residenceClass: ResidenceClass? = null, // set on RESIDENCE access, colours the teal shade
)

/**
 * Why a destination has its access level. Surfaces decide from the [type][AccessReason], never
 * from [label] wording: rephrasing a label must not change what any component shows.
 */
sealed interface AccessReason {

    /** The text shown under the level; blank for reasons the surfaces do not repeat. */
    val label: String

    /** The traveller's own country by citizenship. */
    data object HomeCountry : AccessReason {
        override val label = "Your country"
    }

    /** A passport's baseline entry for the destination — the level already says it. */
    data object PassportBaseline : AccessReason {
        override val label = ""
    }

    /** A named grant: a bloc rule, a held document, or a perk it unlocks. */
    data class Grant(override val label: String) : AccessReason
}

/** A single document's access to a destination, for the per-document "enter with" breakdown. */
data class DocAccess(
    val label: String,
    val access: Access,
    val isOwn: Boolean = false,
)
