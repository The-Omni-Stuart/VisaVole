package com.cbkres.visavole.domain

/** The computed result for one destination: the effective [level], an optional [days] limit, and a human [reason].
 *  [fromDocument] is true when the access comes from a visa/residence/permit rather than a passport. */
data class Access(
    val level: AccessLevel,
    val days: Int?,
    val reason: String = "",
    val fromDocument: Boolean = false,
    val residenceClass: ResidenceClass? = null, // set on RESIDENCE access, colours the teal shade
)

/** A single document's access to a destination, for the per-document "enter with" breakdown. */
data class DocAccess(
    val label: String,
    val access: Access,
    val isOwn: Boolean = false,
)
