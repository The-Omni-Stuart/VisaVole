package com.cbkres.visavole.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Colour band for the Settings data-build row; NEUTRAL means the dates do not support a claim. */
enum class FreshnessLevel { GREEN, AMBER, RED, NEUTRAL }

/** Verdict of [freshnessFor]: how old the data build is and whether anything has expired. */
data class VisaFreshness(
    /** Days between the build date and [freshnessFor]'s today; null when the build date is unusable. */
    val ageDays: Long?,
    val level: FreshnessLevel,
    val warn: Boolean,
)

private const val GREEN_DAYS = 30L
private const val AMBER_DAYS = 90L


/**
 * Pure freshness verdict for the Settings data-build row. Age is measured from [generated]; the
 * colour is green up to 30 days, amber up to 90, red beyond. Any missing or unparsable date makes
 * the row NEUTRAL — with an incomplete audit trail an age colour would be an unfounded claim — and
 * [lapsedRules]/[lapsingSoon] drive [VisaFreshness.warn] independently of the dates.
 */
fun freshnessFor(
    generated: String,
    rulesVerifiedAt: String,
    nextReview: String,
    lapsedRules: Int,
    lapsingSoon: Int,
    today: LocalDate,
): VisaFreshness {
    val build = generated.toDateOrNull()
    val age = build?.let { ChronoUnit.DAYS.between(it, today) }
    val level = when {
        age == null || rulesVerifiedAt.toDateOrNull() == null || nextReview.toDateOrNull() == null -> FreshnessLevel.NEUTRAL
        age <= GREEN_DAYS -> FreshnessLevel.GREEN
        age <= AMBER_DAYS -> FreshnessLevel.AMBER
        else -> FreshnessLevel.RED
    }
    return VisaFreshness(age, level, lapsedRules > 0 || lapsingSoon > 0)
}
