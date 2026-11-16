package com.cbkres.visavole.ui.screens.settings

import com.cbkres.visavole.domain.VisaDbMeta

/** Labels for the Settings data-build pills, kept composable-free so the branching is JVM-testable. */
internal data class DataBuildPills(
    /** The generated date when the whole audit trail is present, else [unknownLabel]. */
    val buildLabel: String,
    /** Compact counts summary; null when nothing has lapsed or is lapsing. */
    val warningLabel: String?,
)

/**
 * Compact labels for the data-build pills. The generated date is only shown when every audit date
 * parses — with an incomplete trail the age dot is already neutral, so a bare date would look precise
 * but unverifiable. [warningParts] are pre-localised count labels (lapsed first, absent when zero);
 * joining them keeps this free of resources so the branching is JVM-testable.
 */
internal fun dataBuildPills(
    meta: VisaDbMeta,
    unknownLabel: String,
    warningParts: List<String>,
    separator: String,
): DataBuildPills {
    val complete = listOf(meta.generated, meta.rulesVerifiedAt, meta.nextReview).all {
        runCatching { java.time.LocalDate.parse(it) }. isSuccess
    }
    return DataBuildPills(
        buildLabel = if (complete) meta.generated else unknownLabel,
        warningLabel = warningParts.joinToString(separator).ifBlank { null },
    )
}
