package com.cbkres.visavole.domain

import com.cbkres.visavole.data.WorldData
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The centralised guard. Every document or trip mutation is evaluated by [GuardEngine] before it
 * is applied: the screens call it on the user's input and the ViewModel backstops it on the same
 * context, so both share one definition of what is allowed. Rules are pure functions of
 * ([GuardAction], [GuardContext]) — no UI, no persistence, fully testable.
 */

/** Severity of a [GuardFinding]. BLOCK stops the action; WARN is advisory (the action may proceed). */
enum class GuardSeverity { BLOCK, WARN }

/** A single finding from a guard rule. [code] is a stable identifier used by the UI and the tests. */
data class GuardFinding(
    val code: String,
    val severity: GuardSeverity,
    val title: String,
    val message: String,
)

/**
 * A transform the engine wants applied together with the pending action (superseding a replaced
 * document, ending an ongoing trip). Mutations never block: they ride along with the action and
 * the UI confirms them before the ViewModel applies candidate + mutations atomically.
 */
sealed class GuardMutation {
    /**
     * Mark document [oldId] as superseded by the new document [newId]. The old document keeps its
     * own dates; its effective expiry derives live from the replacement's `validFrom` (see
     * [DocStatus]), so it follows the replacement's edits and revives if the replacement is deleted.
     */
    data class SupersedeDocument(val oldId: String, val newId: String) : GuardMutation()
    /** End trip [tripId], clamping its last departure to [onDate]. */
    data class EndTrip(val tripId: String, val onDate: LocalDate) : GuardMutation()
}

/** Everything a guard rule may need to decide. Immutable and fully injectable (testable). */
data class GuardContext(
    val docs: List<Document>,
    val trips: List<Trip>,
    val world: WorldData,
    val today: LocalDate = LocalDate.now(ZoneOffset.UTC),
    val primaryId: String? = null,
) {
    /** Document ids referenced by any trip stop — a document in this set is "in use". */
    val referencedDocIds: Set<String> = referencedDocumentIds(trips)

    fun countryName(iso2: String): String = world.countries[iso2]?.name ?: iso2
}

/** The action being attempted; the engine runs the rule set that applies to it. */
sealed class GuardAction {
    data class AddDocument(val candidate: Document) : GuardAction()
    data class UpdateDocument(val candidate: Document, val original: Document) : GuardAction()
    data class RemoveDocument(val docId: String) : GuardAction()
    data class SetHome(val iso2: String, val expiry: String? = null) : GuardAction()
    data class SetPrimary(val docId: String?) : GuardAction()
    data class AddTrip(val candidate: Trip) : GuardAction()
    data class UpdateTrip(val candidate: Trip) : GuardAction()
    data class RemoveTrip(val tripId: String) : GuardAction()
    data class EndTrip(val tripId: String) : GuardAction()
}

data class GuardResult(
    val findings: List<GuardFinding>,
    val mutations: List<GuardMutation> = emptyList(),
) {
    val blocks: List<GuardFinding> get() = findings.filter { it.severity == GuardSeverity.BLOCK }
    val warnings: List<GuardFinding> get() = findings.filter { it.severity == GuardSeverity.WARN }
    val canProceed: Boolean get() = blocks.isEmpty()
}

object GuardEngine {

    fun evaluate(action: GuardAction, ctx: GuardContext): GuardResult {
        val results = when (action) {
            is GuardAction.AddDocument -> documentRules(action, ctx)
            is GuardAction.UpdateDocument -> documentRules(action, ctx)
            is GuardAction.RemoveDocument -> listOfNotNull(removeDocumentRule(action, ctx))
            is GuardAction.SetHome -> listOfNotNull(setHomeRule(action, ctx))
            is GuardAction.SetPrimary -> listOfNotNull(setPrimaryRule(action, ctx))
            is GuardAction.AddTrip -> tripRules(action, ctx)
            is GuardAction.UpdateTrip -> tripRules(action, ctx)
            is GuardAction.RemoveTrip -> emptyList()
            is GuardAction.EndTrip -> emptyList()
        }
        return GuardResult(
            findings = results.flatMap { it.findings }.distinct(),
            mutations = results.flatMap { it.mutations }.distinct(),
        )
    }

    // ------------------------------------------------------------------
    // Document rules
    // ------------------------------------------------------------------

    private fun documentRules(action: GuardAction, ctx: GuardContext): List<GuardResult> {
        val candidate = when (action) {
            is GuardAction.AddDocument -> action.candidate
            is GuardAction.UpdateDocument -> action.candidate
            else -> return emptyList()
        }
        return listOfNotNull(
            duplicateRule(candidate, ctx),
            inUseIdentityRule(action, candidate, ctx),
            visaMultiCountryRule(candidate, ctx),
            inUseValidityRule(action, candidate, ctx),
            inUseEntriesRule(action, candidate, ctx),
            capRules(candidate, ctx),
            singlePerCountryRule(candidate, ctx),
        )
    }

    /** `doc.duplicate` — the candidate is an exact duplicate of a held document. */
    private fun duplicateRule(candidate: Document, ctx: GuardContext): GuardResult? {
        val dup = ctx.docs.firstOrNull {
            it.id != candidate.id && it.duplicateSignature() == candidate.duplicateSignature()
        } ?: return null
        return GuardResult(listOf(
            GuardFinding(
                "doc.duplicate", GuardSeverity.BLOCK, "Document already exists",
                "You already have this document. Edit the existing one to change its details, or discard this entry.",
            ),
        ))
    }

    /** `doc.inUse.identity` — an in-use document's structural identity would change. */
    private fun inUseIdentityRule(action: GuardAction, candidate: Document, ctx: GuardContext): GuardResult? {
        if (action !is GuardAction.UpdateDocument) return null
        if (candidate.id !in ctx.referencedDocIds) return null
        if (candidate.identityKey() == action.original.identityKey()) return null
        val facets = identityFacets(action.original, candidate)
        return GuardResult(listOf(
            GuardFinding(
                "doc.inUse.identity", GuardSeverity.BLOCK, "In use by a trip",
                "This document is used by a trip, so its ${facets.joinToString(" or ")} can't be changed here — doing " +
                    "so would silently change what the trip was entered with and how its stay allowance is calculated. " +
                    "To change it, re-point that trip to a different document (or delete the trip) first. You can " +
                    "still edit its dates, number, and entry type.",
            ),
        ))
    }

    /** The identity facets that actually differ between two documents (R11). */
    private fun identityFacets(original: Document, candidate: Document): List<String> {
        val o = original.kind
        val c = candidate.kind
        val facets = mutableListOf<String>()
        if (kindType(o) != kindType(c)) facets += "type"
        if ((o as? DocKind.Passport)?.iso2 != (c as? DocKind.Passport)?.iso2) facets += "country"
        if (kindHolding(o) != kindHolding(c)) facets += "holding"
        if ((o as? DocKind.Custom)?.blocId != (c as? DocKind.Custom)?.blocId) facets += "bloc"
        if ((o as? DocKind.Custom)?.countries != (c as? DocKind.Custom)?.countries) facets += "countries"
        return facets.ifEmpty { listOf("type or country") }
    }

    private fun kindType(k: DocKind): String = when (k) {
        is DocKind.Passport -> "passport"
        is DocKind.Holding -> "holding"
        is DocKind.Custom -> k.kind
    }

    private fun kindHolding(k: DocKind): String? = when (k) {
        is DocKind.Passport -> null
        is DocKind.Holding -> k.holdingId
        is DocKind.Custom -> k.holdingId
    }

    /** `doc.visa.multiCountry` — a custom visa covering more than one country. */
    private fun visaMultiCountryRule(candidate: Document, ctx: GuardContext): GuardResult? {
        val k = candidate.kind as? DocKind.Custom ?: return null
        if (k.kind != "visa" || k.countries.size <= 1) return null
        val names = k.countries
            .sortedBy { ctx.world.countries[it]?.name ?: it }
            .joinToString(", ") { ctx.world.countries[it]?.name ?: it }
        return GuardResult(listOf(
            GuardFinding(
                "doc.visa.multiCountry", GuardSeverity.WARN, "Multiple countries",
                "You have multiple countries selected for this visa: $names. Are you sure you want to save?",
            ),
        ))
    }

    /** `doc.inUse.validity` (D3a) — the edit would invalidate a logged stay using this document. */
    private fun inUseValidityRule(action: GuardAction, candidate: Document, ctx: GuardContext): GuardResult? {
        if (action !is GuardAction.UpdateDocument) return null
        if (candidate.id !in ctx.referencedDocIds) return null
        val e = TripModel.entryStatusFor(candidate, ctx.trips, ctx.world, ctx.today)?.effectiveExpiry
            ?: parseIso(candidate.expiry)
        val validFrom = parseIso(candidate.validFrom)
        val findings = mutableListOf<GuardFinding>()
        for (trip in ctx.trips) {
            for (stop in trip.stops.filter { it.documentId == candidate.id }) {
                val country = ctx.countryName(stop.countryIso2)
                if (validFrom != null && stop.arrival.isBefore(validFrom)) {
                    findings += GuardFinding(
                        "doc.inUse.validity", GuardSeverity.BLOCK, "In use by a trip",
                        "Trip to $country starts ${stop.arrival}, before this document became valid ($validFrom).",
                    )
                }
                val end = stop.departure ?: ctx.today
                if (e != null && end.isAfter(e)) {
                    findings += GuardFinding(
                        "doc.inUse.validity", GuardSeverity.BLOCK, "In use by a trip",
                        "This document would no longer cover the whole stay of trip to $country (stay ends $end; " +
                            "effective expiry $e).",
                    )
                }
            }
        }
        if (findings.isEmpty()) return null
        return GuardResult(findings)
    }

    /** `doc.inUse.entries` (D3b) — the edit would reduce/make finite the entry count of a used document. */
    private fun inUseEntriesRule(action: GuardAction, candidate: Document, ctx: GuardContext): GuardResult? {
        if (action !is GuardAction.UpdateDocument) return null
        if (candidate.id !in ctx.referencedDocIds) return null
        val original = (action as GuardAction.UpdateDocument).original
        val n = TripModel.entryTotalFor(candidate.entryType())
        val o = TripModel.entryTotalFor(original.entryType())
        // Only a reduction or a made-finite count matters: increases and unchanged counts are allowed.
        if (n == null || (o != null && n >= o)) return null
        val u = TripModel.entryStatusFor(candidate, ctx.trips, ctx.world, ctx.today)?.used ?: 0
        if (u < 1) return null
        val message = if (u > n) {
            "Trips have already consumed $u ${candidate.label} entries, but the document now has only $n."
        } else {
            "This document has been used by logged trips, so its entry count can no longer be reduced or made " +
                "finite (currently: ${entryTypeLabel(original)} → ${entryTypeLabel(candidate)})."
        }
        return GuardResult(listOf(
            GuardFinding("doc.inUse.entries", GuardSeverity.BLOCK, "In use by a trip", message),
        ))
    }

    private fun entryTypeLabel(doc: Document): String = doc.entryType() ?: "unlimited"

    /** `doc.cap.*` — caps on the number of VALID documents held (total per category, per country). */
    private fun capRules(candidate: Document, ctx: GuardContext): GuardResult? {
        if (!heldAndValid(candidate, ctx)) return null
        val others = ctx.docs.filter { it.id != candidate.id }
        return when {
            candidate.kind is DocKind.Passport -> {
                if (others.count { it.kind is DocKind.Passport && heldAndValid(it, ctx) } >= 10) {
                    GuardResult(listOf(
                        GuardFinding(
                            "doc.cap.passportTotal", GuardSeverity.BLOCK, "Too many passports",
                            "You can hold at most 10 valid passports. Delete or expire one before adding another.",
                        ),
                    ))
                } else {
                    val iso = (candidate.kind as DocKind.Passport).iso2
                    if (others.count { (it.kind as? DocKind.Passport)?.iso2 == iso && heldAndValid(it, ctx) } >= 4) {
                        GuardResult(listOf(
                            GuardFinding(
                                "doc.cap.passportPerCountry", GuardSeverity.BLOCK,
                                "Too many passports for this country",
                                "You have reached the maximum number of passports for this country. Please delete " +
                                    "or expire one (or more) of your pre-existing passports for this country before " +
                                    "adding another.",
                            ),
                        ))
                    } else {
                        null
                    }
                }
            }
            candidate.docCategory(ctx.world) == "residence" ->
                if (others.count { it.docCategory(ctx.world) == "residence" && heldAndValid(it, ctx) } >= 10) {
                    GuardResult(listOf(
                        GuardFinding(
                            "doc.cap.residenceTotal", GuardSeverity.BLOCK, "Too many residence documents",
                            "You can hold at most 10 valid residence documents. Delete or expire one before adding another.",
                        ),
                    ))
                } else {
                    null
                }
            candidate.docCategory(ctx.world) == "visa" ->
                if (others.count { it.docCategory(ctx.world) == "visa" && heldAndValid(it, ctx) } >= 10) {
                    GuardResult(listOf(
                        GuardFinding(
                            "doc.cap.visaTotal", GuardSeverity.BLOCK, "Too many visas",
                            "You can hold at most 10 valid visas. Delete or expire one before adding another.",
                        ),
                    ))
                } else {
                    null
                }
            else -> null
        }
    }

    /**
     * `doc.single.perCountry` — at most one valid visa/residence per country/category. Every
     * existing document of the same category that overlaps the candidate's countries and is still
     * valid at the candidate's start is superseded by the candidate (MUTATE + WARN per document,
     * replaces the old hard block).
     */
    private fun singlePerCountryRule(candidate: Document, ctx: GuardContext): GuardResult? {
        val category = candidate.docCategory(ctx.world) ?: return null
        val mine = candidate.coveredCountries(ctx.world)
        if (mine.isEmpty()) return null
        val start = parseIso(candidate.validFrom) ?: ctx.today
        val oldOnes = ctx.docs.filter { o ->
            o.id != candidate.id &&
                o.docCategory(ctx.world) == category &&
                o.coveredCountries(ctx.world).any { it in mine } &&
                !DocStatus.of(o, ctx.docs, ctx.trips, ctx.world, start).expired
        }
        if (oldOnes.isEmpty()) return null
        return GuardResult(
            findings = oldOnes.map { old ->
                GuardFinding(
                    "doc.single.perCountry", GuardSeverity.WARN, "Replaces your existing document",
                    "This will supersede your ${old.label} from $start, since you can only hold one valid " +
                        "$category per country. It stays in your archive, marked as replaced.",
                )
            },
            mutations = oldOnes.map { GuardMutation.SupersedeDocument(it.id, candidate.id) },
        )
    }

    /** True when the document is not archived as of today (date, entries, or supersession). */
    private fun heldAndValid(doc: Document, ctx: GuardContext): Boolean =
        !DocStatus.of(doc, ctx.docs, ctx.trips, ctx.world, ctx.today).expired

    /** `doc.inUse.removable` — the document is referenced by a trip. */
    private fun removeDocumentRule(action: GuardAction.RemoveDocument, ctx: GuardContext): GuardResult? {
        if (action.docId !in ctx.referencedDocIds) return null
        return GuardResult(listOf(
            GuardFinding(
                "doc.inUse.removable", GuardSeverity.BLOCK, "In use",
                "This document is used by a trip, so it can't be removed. Change that trip to use a different " +
                    "document, or delete the trip.",
            ),
        ))
    }

    /** `doc.home.*` — onboarding passport creation. */
    private fun setHomeRule(action: GuardAction.SetHome, ctx: GuardContext): GuardResult? {
        val country = ctx.world.countries[action.iso2]
        return when {
            country == null -> GuardResult(listOf(
                GuardFinding(
                    "doc.home.unknown", GuardSeverity.BLOCK, "Unknown country",
                    "No known country matches ${action.iso2}.",
                ),
            ))
            ctx.docs.any { (it.kind as? DocKind.Passport)?.iso2 == action.iso2 } -> GuardResult(listOf(
                GuardFinding(
                    "doc.home.duplicate", GuardSeverity.BLOCK, "Passport already exists",
                    "You already have a passport for ${country.name}. Remove or edit it first.",
                ),
            ))
            else -> null
        }
    }

    /** `doc.primary.notPassport` — the primary (star) must be a passport. */
    private fun setPrimaryRule(action: GuardAction.SetPrimary, ctx: GuardContext): GuardResult? {
        val id = action.docId ?: return null
        val doc = ctx.docs.firstOrNull { it.id == id } ?: return null
        if (doc.kind is DocKind.Passport) return null
        return GuardResult(listOf(
            GuardFinding(
                "doc.primary.notPassport", GuardSeverity.BLOCK, "Only passports can be primary",
                "Only a passport can be the primary document; ${doc.label} is not a passport.",
            ),
        ))
    }

    // ------------------------------------------------------------------
    // Trip rules (implemented in the trip-side phase)
    // ------------------------------------------------------------------

    private fun tripRules(action: GuardAction, ctx: GuardContext): List<GuardResult> = emptyList()

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun parseIso(s: String?): LocalDate? = s?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
}
