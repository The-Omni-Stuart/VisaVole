package com.cbkres.visavole.data

import com.cbkres.visavole.domain.VisaDbMeta
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The freshness SQL shared by both loaders ([VisaRepository] on Android, [JdbcRepository] on the
 * JVM) so the two aggregate expiry probes stay identical queries. `valid_to` holds ISO dates, so
 * TEXT comparison orders them correctly; both probes exclude NULL rows.
 */
internal const val SQL_META_FRESHNESS =
    "SELECT key, value FROM meta WHERE key IN ('generated','rules_verified_at','next_review')"

/** Count of `stay_rules` rows whose `valid_to` is strictly before the given date. */
internal const val SQL_STAY_RULES_LAPSED =
    "SELECT COUNT(*) FROM stay_rules WHERE valid_to IS NOT NULL AND valid_to < ?"

/** Count of rows whose `valid_to` falls in the inclusive 30-day window starting at the given date. */
internal const val SQL_STAY_RULES_LAPSING_SOON =
    "SELECT COUNT(*) FROM stay_rules WHERE valid_to IS NOT NULL AND valid_to >= ? AND valid_to <= ?"

internal const val LAPSING_SOON_DAYS = 30L

/** The inclusive window [today, today + 30 d] used by the lapsing-soon probe, as ISO strings. */
internal fun lapsingSoonWindow(today: LocalDate = LocalDate.now(ZoneOffset.UTC)): Pair<String, String> =
    today.toString() to today.plusDays(LAPSING_SOON_DAYS).toString()

/** Folds the freshness `meta` rows and the two probe counts into a [VisaDbMeta]; absent keys stay "". */
internal fun visaDbMeta(values: Map<String, String>, lapsedRules: Int, lapsingSoon: Int): VisaDbMeta =
    VisaDbMeta(
        generated = values["generated"] ?: "",
        rulesVerifiedAt = values["rules_verified_at"] ?: "",
        nextReview = values["next_review"] ?: "",
        lapsedRules = lapsedRules,
        lapsingSoon = lapsingSoon,
    )
