package com.cbkres.visavole.domain

/** The document editor's three tabs; [kind] is the family string persisted on `DocKind.Custom`. */
internal enum class DocType(val label: String, val kind: String) {
    PASSPORT("Passport", "passport"),
    RESIDENCE("Residence", "residence"),
    VISA("Visa", "visa"),
}
