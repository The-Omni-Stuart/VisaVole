package com.cbkres.visavole.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * The dialog → draft → document round trip. [untouchedDraft] replays `AddDocumentDialog`'s state
 * initialisation, so `buildDocument == initial` proves an edit the user touched nothing in is a
 * no-op (identity, entry type, residence class and all).
 */
class DocDraftTest {

    private val world = WorldData(
        countries = buildMap {
            put("FR", Country("FR", "France"))
            put("DE", Country("DE", "Germany"))
            put("ES", Country("ES", "Spain"))
            put("GB", Country("GB", "United Kingdom"))
            for (i in 1..12) put("C%02d".format(i), Country("C%02d".format(i), "Country%02d".format(i)))
        },
        baseline = emptyMap(),
        regimes = listOf(Regime("schengen", "Schengen Area", "visa-free", listOf("FR", "DE"))),
        holdings = mapOf(
            "fr-res" to Holding("fr-res", "France Residence", "residency", "FR"),
            "fr-visa" to Holding("fr-visa", "France Visa", "short_term_visa", "FR"),
            "es-permit" to Holding("es-permit", "Spain Permit", "special_permit", "ES"),
        ),
        benefits = emptyMap(),
    )

    /** The draft the dialog holds when opened on [initial] and nothing is changed. */
    private fun untouchedDraft(initial: Document): DocDraft {
        val initPassport = initial.kind as? DocKind.Passport
        val initCustom = initial.kind as? DocKind.Custom
        val initHolding = initial.kind as? DocKind.Holding
        val docType = when {
            initPassport != null -> DocType.PASSPORT
            initCustom?.kind == "visa" || initCustom?.kind == "permit" -> DocType.VISA
            initCustom?.kind == "passport" -> DocType.PASSPORT
            else -> DocType.RESIDENCE
        }
        val customIso = when {
            initPassport != null -> setOf(initPassport.iso2)
            initCustom != null -> initCustom.countries
            else -> emptySet()
        }
        val lastIso = when {
            initPassport != null -> initPassport.iso2
            initCustom != null -> initCustom.countries.firstOrNull()
            else -> null
        }
        val inferredBloc = initCustom?.let { world.travelBlocFor(it.countries) }
        val blocChoice = when {
            initCustom == null -> null
            inferredBloc?.id == initCustom.blocId -> null
            initCustom.blocId == null -> ""
            else -> initCustom.blocId
        }
        val inferredHolding = initCustom?.let { world.holdingFor(it.countries, it.kind) }
        val holdingChoice = when {
            initCustom == null -> initHolding?.holdingId
            inferredHolding == initCustom.holdingId -> null
            initCustom.holdingId == null -> ""
            else -> initCustom.holdingId
        }
        // Auto-holding inference must use the same tab-guarded kind buildDocument guards with:
        // a legacy "permit" sits on the Visa tab, and inferring with the tab's "visa" would
        // stamp an auto holding into holdingId on an untouched edit (identity drift → in-use block).
        val inferredKind =
            if (initCustom != null && initialTabFor(initCustom.kind) == docType) initCustom.kind else docType.kind
        return DocDraft(
            id = initial.id,
            docType = docType,
            lastIso = lastIso,
            countries = customIso,
            effectiveBloc = when (blocChoice) {
                null -> world.travelBlocFor(customIso)?.id
                "" -> null
                else -> blocChoice
            },
            effectiveHolding = when (holdingChoice) {
                null -> world.holdingFor(customIso, inferredKind)
                "" -> null
                else -> holdingChoice
            },
            entryType = initCustom?.entryType ?: "multiple",
            residenceClass = initial.residenceClass ?: ResidenceClass.TEMPORARY,
            residenceClassEdited = false,
            expiry = initial.expiry,
            validFrom = initial.validFrom,
        )
    }

    private fun newDraft(
        docType: DocType,
        id: String = "new-1",
        countries: Set<String> = setOf("FR"),
        effectiveBloc: String? = null,
        effectiveHolding: String? = null,
        entryType: String = "multiple",
        residenceClass: ResidenceClass = ResidenceClass.TEMPORARY,
        residenceClassEdited: Boolean = false,
    ) = DocDraft(
        id = id,
        docType = docType,
        lastIso = countries.firstOrNull(),
        countries = countries,
        effectiveBloc = effectiveBloc,
        effectiveHolding = effectiveHolding,
        entryType = entryType,
        residenceClass = residenceClass,
        residenceClassEdited = residenceClassEdited,
        expiry = null,
        validFrom = null,
    )

    // ---- the untouched-edit round trip: an edit changes nothing ----

    @Test fun passportEditRoundTripsUnchanged() {
        val initial = passportDocument("GB", "United Kingdom", "2031-05-01", "2021-05-01", id = "p1")
        assertEquals(initial, buildDocument(untouchedDraft(initial), initial, world))
    }

    @Test fun untouchedPermitEditDoesNotStampAutoHolding() {
        // A legacy permit opens on the Visa tab; auto-holding inference with the tab's "visa"
        // kind finds the ES permit holding (visa accepts special_permit) and would stamp it
        // into holdingId, drifting the document's identity on an edit that touched nothing.
        val initial = Document(
            "pm1", "Permit: Spain", DocKind.Custom(setOf("ES"), blocId = null, kind = "permit", holdingId = null, entryType = "multiple"),
            null, null, null, null,
        )
        assertEquals(initial, buildDocument(untouchedDraft(initial), initial, world))
    }

    @Test fun untouchedPermitEditPassesInUseIdentityGuard() {
        val permit = Document(
            "pm2", "Permit: Spain", DocKind.Custom(setOf("ES"), blocId = null, kind = "permit", holdingId = null, entryType = "multiple"),
            null, null, null, null,
        )
        val stay = Trip("tp", listOf(TripStop("ts", "ES", LocalDate.of(2026, 11, 13), null, documentId = permit.id)))
        val built = buildDocument(untouchedDraft(permit), permit, world)
        val guard = GuardEngine.evaluate(
            GuardAction.UpdateDocument(built, permit),
            GuardContext(listOf(permit), listOf(stay), world, LocalDate.of(2026, 11, 15), null),
        )
        assertEquals(emptyList<String>(), guard.findings.map { it.code })
    }

    @Test fun holdingEditRoundTripsUnchanged() {
        val initial = Document(
            "h1", "France Residence · Long-term", DocKind.Holding("fr-res", "multiple"),
            "2031-01-01", "2024-01-01", ResidenceClass.LONG_TERM,
        )
        assertEquals(initial, buildDocument(untouchedDraft(initial), initial, world))
    }

    @Test fun customVisaEditRoundTripsUnchanged() {
        val initial = Document(
            "v1", "Visa: France, Germany (France Visa)",
            DocKind.Custom(setOf("FR", "DE"), "schengen", "visa", "fr-visa", "single"),
            "2027-06-01", "2026-06-01",
        )
        assertEquals(initial, buildDocument(untouchedDraft(initial), initial, world))
    }

    @Test fun customResidenceEditKeepsStoredEntryType() {
        val initial = Document(
            "r1", "Residence: France (France Residence)",
            DocKind.Custom(setOf("FR"), "schengen", "residence", "fr-res", "multiple"),
            "2030-01-01", "2020-01-01", ResidenceClass.LONG_TERM,
        )
        assertEquals(initial, buildDocument(untouchedDraft(initial), initial, world))
    }

    @Test fun customPermitEditKeepsStoredKind() {
        // Stored holding matches the visa tab's auto-inference, so the kind re-stamp is the only
        // field an untouched edit could break.
        val initial = Document(
            "k1", "Visa: France (France Visa)",
            DocKind.Custom(setOf("FR"), null, "permit", "fr-visa", "single"),
            "2027-01-01", "2026-01-01",
        )
        val built = buildDocument(untouchedDraft(initial), initial, world)
        assertEquals(initial, built)
        assertEquals(initial.identityKey(), built.identityKey())
    }

    // ---- deliberate edits: the user's choices win ----

    @Test fun customResidenceMovedToVisaTabRestampsToVisa() {
        // Moving a residence document to the Visa tab is intent: the family re-stamps.
        val initial = Document(
            "r2", "Residence: France",
            DocKind.Custom(setOf("FR"), "schengen", "residence", null, "multiple"),
            "2030-01-01", null, ResidenceClass.LONG_TERM,
        )
        val kind = buildDocument(untouchedDraft(initial).copy(docType = DocType.VISA), initial, world)
            .kind as DocKind.Custom
        assertEquals("visa", kind.kind)
        assertEquals("multiple", kind.entryType) // visa documents take the dialog's selection
    }

    @Test fun customVisaMovedToResidenceTabRestampsToResidence() {
        val initial = Document(
            "v2", "Visa: France",
            DocKind.Custom(setOf("FR"), "schengen", "visa", null, "single"),
        )
        val kind = buildDocument(untouchedDraft(initial).copy(docType = DocType.RESIDENCE), initial, world)
            .kind as DocKind.Custom
        assertEquals("residence", kind.kind)
        assertEquals("single", kind.entryType) // the stored entry type survives the move
    }

    @Test fun visaEditKeepsDialogEntrySelection() {
        val initial = Document("v1", "Visa: France", DocKind.Custom(setOf("FR"), null, "visa", null, "single"))
        val built = buildDocument(untouchedDraft(initial).copy(entryType = "double"), initial, world)
        assertEquals("double", (built.kind as DocKind.Custom).entryType)
    }

    @Test fun editedResidenceClassWinsOnCustom() {
        val initial = Document(
            "r1", "Residence: France (France Residence)",
            DocKind.Custom(setOf("FR"), "schengen", "residence", "fr-res", null),
            residenceClass = ResidenceClass.LONG_TERM,
        )
        val draft = untouchedDraft(initial).copy(residenceClass = ResidenceClass.PERMANENT, residenceClassEdited = true)
        assertEquals(ResidenceClass.PERMANENT, buildDocument(draft, initial, world).residenceClass)
    }

    @Test fun editedResidenceClassWinsOnHolding() {
        val initial = Document(
            "h1", "France Residence · Temporary", DocKind.Holding("fr-res", null),
            residenceClass = ResidenceClass.TEMPORARY,
        )
        val draft = untouchedDraft(initial).copy(residenceClass = ResidenceClass.PERMANENT, residenceClassEdited = true)
        val built = buildDocument(draft, initial, world)
        assertEquals(ResidenceClass.PERMANENT, built.residenceClass)
        assertEquals("France Residence · Permanent", built.label)
    }

    @Test fun archivedEditCarriesSupersededBy() {
        val initial = Document(
            "v1", "Visa: France", DocKind.Custom(setOf("FR"), null, "visa", null, "single"),
            supersededBy = "v2",
        )
        assertEquals("v2", buildDocument(untouchedDraft(initial), initial, world).supersededBy)
    }

    @Test fun customOnlyHoldingOnStoredHoldingBuildsCustom() {
        // Holding choice "" (custom only) while countries are picked must build a Custom, never a
        // broken DocKind.Holding("").
        val initial = Document(
            "h1", "France Residence · Temporary", DocKind.Holding("fr-res", null),
            residenceClass = ResidenceClass.TEMPORARY,
        )
        val draft = untouchedDraft(initial).copy(countries = setOf("FR"), effectiveBloc = "schengen", effectiveHolding = null)
        val built = buildDocument(draft, initial, world)
        assertEquals(Document("h1", "Residence: France", DocKind.Custom(setOf("FR"), "schengen", "residence", null, null), residenceClass = ResidenceClass.TEMPORARY), built)
    }

    // ---- new documents: labels, bloc/holding choices, deterministic id ----

    @Test fun builderUsesDraftIdForNewDocuments() {
        val built = buildDocument(newDraft(DocType.PASSPORT, id = "fixed-id", countries = setOf("GB")), null, world)
        assertEquals("fixed-id", built.id)
    }

    @Test fun newPassportUsesWorldCountryName() {
        val built = buildDocument(newDraft(DocType.PASSPORT, id = "np1", countries = setOf("GB")), null, world)
        assertEquals("Passport · United Kingdom", built.label)
        assertEquals(DocKind.Passport("GB"), built.kind)
    }

    @Test fun newVisaLabelJoinsNamesAndHolding() {
        val built = buildDocument(
            newDraft(DocType.VISA, countries = setOf("FR", "DE"), effectiveHolding = "fr-visa"), null, world,
        )
        assertEquals("Visa: France, Germany (France Visa)", built.label)
    }

    @Test fun longCountryListLabelIsTruncatedToEighty() {
        val isos = (1..12).map { "C%02d".format(it) }.toSet()
        val names = (1..12).joinToString(", ") { "Country%02d".format(it) }
        val built = buildDocument(newDraft(DocType.VISA, countries = isos), null, world)
        assertEquals("Visa: ${names.take(80)}", built.label)
    }

    @Test fun blocChoicesCarryAutoNoneAndExplicit() {
        for (bloc in listOf("schengen", null, "gcc")) {
            val built = buildDocument(newDraft(DocType.VISA, effectiveBloc = bloc), null, world)
            assertEquals(bloc, (built.kind as DocKind.Custom).blocId)
        }
    }

    @Test fun holdingChoicesCarryAutoNoneAndExplicit() {
        for (holding in listOf("fr-visa", null)) {
            val built = buildDocument(newDraft(DocType.VISA, effectiveHolding = holding), null, world)
            assertEquals(holding, (built.kind as DocKind.Custom).holdingId)
        }
        val labelled = buildDocument(newDraft(DocType.VISA, effectiveHolding = "fr-visa"), null, world)
        val bare = buildDocument(newDraft(DocType.VISA, effectiveHolding = null), null, world)
        assertEquals("Visa: France (France Visa)", labelled.label)
        assertEquals("Visa: France", bare.label)
    }
}
