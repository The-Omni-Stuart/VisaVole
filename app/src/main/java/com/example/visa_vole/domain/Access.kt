package com.example.visa_vole.domain

/** The computed result for one destination: the effective [level], an optional [days] limit, and a human [reason]. */
data class Access(
    val level: AccessLevel,
    val days: Int?,
    val reason: String = "",
)

/** A single document's access to a destination, for the per-document "enter with" breakdown. */
data class DocAccess(
    val label: String,
    val access: Access,
)
