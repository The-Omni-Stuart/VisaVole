package com.cbkres.visavole.domain

import com.cbkres.visavole.data.Benefit
import com.cbkres.visavole.data.Corridor
import com.cbkres.visavole.data.JdbcRepository
import com.cbkres.visavole.data.Regime
import com.cbkres.visavole.data.StayRule
import com.cbkres.visavole.data.WorldData
import com.cbkres.visavole.domain.AccessLevel.COVERED
import com.cbkres.visavole.domain.AccessLevel.ETA
import com.cbkres.visavole.domain.AccessLevel.E_VISA
import com.cbkres.visavole.domain.AccessLevel.FREEDOM
import com.cbkres.visavole.domain.AccessLevel.REFUSED
import com.cbkres.visavole.domain.AccessLevel.RESIDENCE
import com.cbkres.visavole.domain.AccessLevel.VISA_FREE
import com.cbkres.visavole.domain.AccessLevel.VISA_REQUIRED
import com.cbkres.visavole.domain.DocKind.Custom
import com.cbkres.visavole.domain.DocKind.Holding
import com.cbkres.visavole.domain.DocKind.Passport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import com.cbkres.visavole.data.Holding as DataHolding

class AccessModelTest {

    private fun corridor(p: String, d: String, type: String, days: Int? = null) = Corridor(p, d, type, days)
    private fun regime(id: String, name: String, level: String, vararg m: String) = Regime(id, name, level, m.toList())
    private fun doc(id: String, kind: DocKind) = Document(id, id, kind)
    private fun world(
        baseline: Map<String, List<Corridor>> = emptyMap(),
        regimes: List<Regime> = emptyList(),
        holdings: Map<String, DataHolding> = emptyMap(),
        benefits: Map<String, List<Benefit>> = emptyMap(),
        stayRules: List<StayRule> = emptyList(),
    ) = WorldData(emptyMap(), baseline, regimes, holdings, benefits, stayRules)

    @Test fun ownCountryIsFreedom() {
        val w = world(baseline = mapOf("CZ" to listOf(corridor("CZ", "DE", "visa-free", 90))))
        assertEquals(FREEDOM, AccessModel.compute(listOf(doc("p", Passport("CZ"))), w)["CZ"]!!.level)
    }

    @Test fun blocFreedomAppliesToAllMembers() {
        val w = world(
            baseline = mapOf("CZ" to listOf(corridor("CZ", "FR", "visa-free", 90))),
            regimes = listOf(regime("eu", "EU", "freedom-of-movement", "CZ", "DE", "FR")),
        )
        val acc = AccessModel.compute(listOf(doc("p", Passport("CZ"))), w)
        assertEquals(FREEDOM, acc["DE"]!!.level)
        assertEquals(FREEDOM, acc["FR"]!!.level)
    }

    @Test fun visaFreeBaselineCarriesDays() {
        val w = world(baseline = mapOf("GB" to listOf(corridor("GB", "AD", "visa-free", 90))))
        val acc = AccessModel.compute(listOf(doc("p", Passport("GB"))), w)["AD"]!!
        assertEquals(VISA_FREE, acc.level)
        assertEquals(90, acc.days)
    }

    @Test fun freedomOfMovementBaselineIsFreedom() {
        val w = world(baseline = mapOf("RU" to listOf(corridor("RU", "OS", "freedom-of-movement"))))
        assertEquals(FREEDOM, AccessModel.compute(listOf(doc("p", Passport("RU"))), w)["OS"]!!.level)
    }

    @Test fun eVisaBaseline() {
        val w = world(baseline = mapOf("GB" to listOf(corridor("GB", "AU", "e-visa", 90))))
        assertEquals(E_VISA, AccessModel.compute(listOf(doc("p", Passport("GB"))), w)["AU"]!!.level)
    }

    @Test fun etaBaseline() {
        val w = world(baseline = mapOf("GB" to listOf(corridor("GB", "AU", "eta", 90))))
        assertEquals(ETA, AccessModel.compute(listOf(doc("p", Passport("GB"))), w)["AU"]!!.level)
    }

    @Test fun visaOnArrivalCountsAsVisaFree() {
        val w = world(baseline = mapOf("GB" to listOf(corridor("GB", "TH", "visa-on-arrival", 30))))
        val acc = AccessModel.compute(listOf(doc("p", Passport("GB"))), w)["TH"]!!
        assertEquals(VISA_FREE, acc.level)
        assertEquals(30, acc.days)
    }

    @Test fun visaRequiredBaseline() {
        val w = world(baseline = mapOf("GB" to listOf(corridor("GB", "RU", "visa-required"))))
        assertEquals(VISA_REQUIRED, AccessModel.compute(listOf(doc("p", Passport("GB"))), w)["RU"]!!.level)
    }

    @Test fun visaHoldingUpgradesVisaRequiredToCovered() {
        val w = world(
            baseline = mapOf("GB" to listOf(corridor("GB", "RU", "visa-required"))),
            holdings = mapOf("ru-visa" to DataHolding("ru-visa", "Russia Visa", "short_term_visa", "RU")),
            benefits = mapOf("ru-visa" to listOf(Benefit("ru-visa", "RU", "visa-free", 90))),
        )
        assertEquals(COVERED, AccessModel.compute(listOf(doc("p", Passport("GB")), doc("d", Holding("ru-visa"))), w)["RU"]!!.level)
    }

    @Test fun residenceGivesResidenceInIssuingCountryAndBlocShortStay() {
        val w = world(
            baseline = mapOf("GB" to listOf(corridor("GB", "CZ", "visa-free", 90), corridor("GB", "DE", "visa-free", 90))),
            holdings = mapOf("res" to DataHolding("res", "Schengen Residence", "residency", "CZ")),
            benefits = mapOf("res" to listOf(Benefit("res", "DE", "visa-free", 90), Benefit("res", "FR", "visa-free", 90))),
        )
        val acc = AccessModel.compute(listOf(doc("p", Passport("GB")), doc("d", Holding("res"))), w)
        assertEquals(RESIDENCE, acc["CZ"]!!.level) // residence country
        assertEquals(VISA_FREE, acc["DE"]!!.level) // bloc short-stay
        assertEquals(VISA_FREE, acc["FR"]!!.level)
    }

    @Test fun nationalityGatedBenefitAppliesToCoveredNationality() {
        // BIVS: a UK-visa benefit gated to Indian/Chinese nationals opens Ireland for an Indian traveller.
        val w = world(
            baseline = mapOf("IN" to listOf(corridor("IN", "IE", "visa-required"))),
            holdings = mapOf("uk-visa" to DataHolding("uk-visa", "UK Visa", "short_term_visa", "GB")),
            benefits = mapOf("uk-visa" to listOf(Benefit("uk-visa", "IE", "visa-free", 90, nationalities = setOf("IN", "CN")))),
        )
        val acc = AccessModel.compute(listOf(doc("p", Passport("IN")), doc("d", Holding("uk-visa"))), w)["IE"]!!
        assertEquals(COVERED, acc.level)
        assertEquals(90, acc.days)
    }

    @Test fun nationalityGatedBenefitAppliesToAllCoveredNationalities() {
        val w = world(
            baseline = mapOf("CN" to listOf(corridor("CN", "IE", "visa-required"))),
            holdings = mapOf("uk-visa" to DataHolding("uk-visa", "UK Visa", "short_term_visa", "GB")),
            benefits = mapOf("uk-visa" to listOf(Benefit("uk-visa", "IE", "visa-free", 90, nationalities = setOf("IN", "CN")))),
        )
        val acc = AccessModel.compute(listOf(doc("p", Passport("CN")), doc("d", Holding("uk-visa"))), w)["IE"]!!
        assertEquals(COVERED, acc.level)
        assertEquals(90, acc.days)
    }

    @Test fun nationalityGatedBenefitSkippedForUncoveredNationality() {
        // FR is not a covered BIVS nationality, so the gated UK-visa benefit is skipped; IE stays visa-required.
        val w = world(
            baseline = mapOf("FR" to listOf(corridor("FR", "IE", "visa-required"))),
            holdings = mapOf("uk-visa" to DataHolding("uk-visa", "UK Visa", "short_term_visa", "GB")),
            benefits = mapOf("uk-visa" to listOf(Benefit("uk-visa", "IE", "visa-free", 90, nationalities = setOf("IN", "CN")))),
        )
        val acc = AccessModel.compute(listOf(doc("p", Passport("FR")), doc("d", Holding("uk-visa"))), w)["IE"]!!
        assertEquals(VISA_REQUIRED, acc.level)
    }

    @Test fun ungatedBenefitStillAppliesRegardlessOfNationality() {
        // A benefit with no nationality restriction applies to every nationality (control).
        val w = world(
            baseline = mapOf("FR" to listOf(corridor("FR", "IE", "visa-required"))),
            holdings = mapOf("uk-visa" to DataHolding("uk-visa", "UK Visa", "short_term_visa", "GB")),
            benefits = mapOf("uk-visa" to listOf(Benefit("uk-visa", "IE", "visa-free", 90))),
        )
        assertEquals(COVERED, AccessModel.compute(listOf(doc("p", Passport("FR")), doc("d", Holding("uk-visa"))), w)["IE"]!!.level)
    }

    @Test fun bivSLoadedFromBundledDbIsGatedToIndianAndChineseNationalities() {
        // The real bundled db (the visadb release build) carries the BIVS benefit gated to IN/CN.
        val w = JdbcRepository.load(File("src/main/assets/visa_data.db"))
        val ie = w.benefits["uk-visa"]?.firstOrNull { it.destination == "IE" }
        assertTrue("expected a uk-visa -> IE benefit in the bundled db", ie != null)
        assertEquals(setOf("IN", "CN"), ie!!.nationalities)
        assertEquals("visa-free", ie.type)
    }

    @Test fun futureDatedDocUnlocksNothingAnywhere() {
        // P1-2: a visa whose validFrom lies after today unlocks nothing — not just in compute,
        // but in the breakdown, relevant docs, own-visa countries and the suggested document.
        // The gate still sees it (a gate exists even once lapsed/not-yet).
        val w = world(baseline = mapOf("GB" to listOf(corridor("GB", "CA", "visa-required"))))
        val d = Document(
            "d",
            "Visa: CA",
            Custom(setOf("CA"), null, "visa", null, "multiple"),
            "2029-01-01",
            "2026-10-01",
        )
        val today = LocalDate.of(2026, 9, 16)
        assertNull(AccessModel.compute(listOf(d), w, today)["CA"])
        assertTrue(AccessModel.breakdownFor("CA", listOf(d), w, today).isEmpty())
        assertTrue(AccessModel.relevantDocuments("CA", listOf(d), w, today).isEmpty())
        assertTrue(AccessModel.ownVisaCountries(listOf(d), w, today).isEmpty())
        assertNull(AccessModel.bestDocumentId("CA", listOf(d), w, today))
        assertEquals("d", AccessModel.gateDocumentId("CA", listOf(d), w, today))
    }

    @Test fun mergePrefersLongerDaysOnTieRegardlessOfOrder() {
        // Two passports, same rank (visa-free), different day limits: the merged access must
        // carry the LONGER limit no matter the insertion order (P1-3).
        val w = world(
            baseline = mapOf(
                "GB" to listOf(corridor("GB", "AD", "visa-free", 90)),
                "FR" to listOf(corridor("FR", "AD", "visa-free", 183)),
            ),
        )
        val docs = listOf(doc("p1", Passport("GB")), doc("p2", Passport("FR")))
        assertEquals(183, AccessModel.compute(docs, w)["AD"]!!.days)
        assertEquals(183, AccessModel.compute(docs.reversed(), w)["AD"]!!.days)
    }

    @Test fun blocHoldingCoverageIsTravelBlocNotFreedomBloc() {
        // A bloc-issued holding (issuingCountry "EU") must cover the visa-free travel bloc
        // (Schengen), not the whole freedom-of-movement bloc — otherwise it would supersede
        // valid docs for non-Schengen members like Ireland (P1-4).
        val w = world(
            regimes = listOf(
                regime("eu-eea-efta", "EU/EEA/EFTA", "freedom-of-movement", "IE", "CY", "DE", "FR", "CZ"),
                regime("schengen", "Schengen", "visa-free", "DE", "FR", "CZ"),
            ),
            holdings = mapOf("eu-res" to DataHolding("eu-res", "EU Residence", "residency", "EU")),
        )
        assertEquals(setOf("DE", "FR", "CZ"), doc("h", Holding("eu-res")).coveredCountries(w))
        // A single-country holding is unchanged.
        val w2 = world(holdings = mapOf("cz-res" to DataHolding("cz-res", "CZ Residence", "residency", "CZ")))
        assertEquals(setOf("CZ"), doc("h", Holding("cz-res")).coveredCountries(w2))
    }

    @Test fun documentNeverWorsensBaseline() {
        // baseline visa-free 90; a visa holding that only confers a 30d e-visa must not downgrade
        val w = world(
            baseline = mapOf("GB" to listOf(corridor("GB", "AD", "visa-free", 90))),
            holdings = mapOf("x" to DataHolding("x", "X Visa", "short_term_visa", "X")),
            benefits = mapOf("x" to listOf(Benefit("x", "AD", "e-visa", 30))),
        )
        val acc = AccessModel.compute(listOf(doc("p", Passport("GB")), doc("d", Holding("x"))), w)["AD"]!!
        assertEquals(VISA_FREE, acc.level)
        assertEquals(90, acc.days)
        assertFalse(acc.fromDocument) // the stronger passport rule still wins, so the stay rule is not a document override
    }

    @Test fun documentOverridesPassportRefusal() {
        // A visa you hold lets you in even where your passport is refused.
        val w = world(
            baseline = mapOf("GB" to listOf(corridor("GB", "SY", "refused"))),
            holdings = mapOf("x" to DataHolding("x", "X Visa", "short_term_visa", "SY")),
            benefits = mapOf("x" to listOf(Benefit("x", "SY", "visa-free", 90))),
        )
        val sy = AccessModel.compute(listOf(doc("p", Passport("GB")), doc("d", Holding("x"))), w)["SY"]!!
        assertEquals(COVERED, sy.level)
        assertTrue(sy.fromDocument)
    }

    @Test fun documentDaysAreFlaggedWhenPassportHasNoDays() {
        val w = world(
            baseline = mapOf("GB" to listOf(corridor("GB", "SY", "visa-required"))),
            holdings = mapOf("x" to DataHolding("x", "X Visa", "short_term_visa", "SY")),
            benefits = mapOf("x" to listOf(Benefit("x", "SY", "visa-free", 90))),
        )
        val acc = AccessModel.compute(listOf(doc("p", Passport("GB")), doc("d", Holding("x"))), w)["SY"]!!
        assertEquals(90, acc.days)
        assertTrue(acc.fromDocument)
    }

    @Test fun passportAccessIsNotFlaggedAsDocument() {
        val w = world(baseline = mapOf("GB" to listOf(corridor("GB", "AD", "visa-free", 90))))
        val acc = AccessModel.compute(listOf(doc("p", Passport("GB"))), w)["AD"]!!
        assertEquals(90, acc.days)
        assertFalse(acc.fromDocument)
    }

    @Test fun residenceOverridesPassportRefusalInBloc() {
        // A Czech residence grants EU short-stay even where a Russian passport is refused.
        val w = world(
            baseline = mapOf("RU" to listOf(corridor("RU", "DE", "refused"))),
            regimes = listOf(regime("eu", "EU", "freedom-of-movement", "CZ", "DE", "FR")),
        )
        val acc = AccessModel.compute(listOf(doc("p", Passport("RU")), doc("d", Custom(setOf("CZ"), "eu", "residence"))), w)
        assertEquals(RESIDENCE, acc["CZ"]!!.level)
        assertEquals(VISA_FREE, acc["DE"]!!.level)
    }

    @Test fun customVisaCoversCountry() {
        val w = world(baseline = mapOf("GB" to listOf(corridor("GB", "IR", "visa-required"))))
        assertEquals(COVERED, AccessModel.compute(listOf(doc("p", Passport("GB")), doc("d", Custom(setOf("IR"), null, "visa"))), w)["IR"]!!.level)
    }

    @Test fun customResidenceGivesResidenceAndBlocVisaFree() {
        val w = world(
            baseline = mapOf("GB" to listOf(corridor("GB", "DE", "visa-free", 90))),
            regimes = listOf(regime("eu", "EU", "freedom-of-movement", "CZ", "DE")),
        )
        val acc = AccessModel.compute(listOf(doc("p", Passport("GB")), doc("d", Custom(setOf("CZ"), "eu", "residence"))), w)
        assertEquals(RESIDENCE, acc["CZ"]!!.level) // residence country
        assertEquals(VISA_FREE, acc["DE"]!!.level) // bloc short-stay
    }

    @Test fun secondPassportAddsItsBloc() {
        val w = world(
            baseline = mapOf(
                "GB" to listOf(corridor("GB", "DE", "visa-free", 90)),
                "CZ" to listOf(corridor("CZ", "DE", "visa-free", 90)),
            ),
            regimes = listOf(regime("eu", "EU", "freedom-of-movement", "CZ", "DE", "FR")),
        )
        val acc = AccessModel.compute(listOf(doc("p1", Passport("GB")), doc("p2", Passport("CZ"))), w)
        assertEquals(FREEDOM, acc["DE"]!!.level)
        assertEquals(FREEDOM, acc["FR"]!!.level)
    }

    // ---- real bundled database ----

    @Test fun realDatabaseLoadsAndMerges() {
        val db = File("src/main/assets/visa_data.db")
        assertTrue("visa_data.db missing at ${db.absolutePath}", db.exists())
        val w = JdbcRepository.load(db)

        assertTrue(w.countries.size >= 200)
        assertTrue(!w.baseline["GB"].isNullOrEmpty())
        assertTrue(w.regimes.any { it.id == "eu-eea-efta" && "CZ" in it.members })
        assertTrue(w.holdings.containsKey("schengen-visa"))

        // GB baseline: Andorra is visa-free 90d
        val gb = AccessModel.compute(listOf(doc("p", Passport("GB"))), w)
        assertEquals(VISA_FREE, gb["AD"]!!.level)
        assertEquals(90, gb["AD"]!!.days)

        // A Czech passport => EU/EEA/EFTA freedom of movement to Germany
        val cz = AccessModel.compute(listOf(doc("p", Passport("CZ"))), w)
        assertEquals(FREEDOM, cz["DE"]!!.level)

        // GB + a real Schengen visa: must not worsen the already visa-free baseline to Germany
        val gbSchengen = AccessModel.compute(listOf(doc("p", Passport("GB")), doc("s", Holding("schengen-visa"))), w)
        assertTrue(gbSchengen["DE"]!!.level.rank >= gb["DE"]!!.level.rank)

        // stay_rules: Schengen is multiple-entry, 90 in 180 rolling, and DE is in its countries.
        assertTrue(w.stayRules.isNotEmpty())
        val de = w.stayRuleFor("DE", setOf("GB"))
        assertEquals(true, de?.multipleEntry)
        assertEquals("Multiple entry - 90 in 180 (rolling)", de?.summary())
    }

    // ---- multi-passport "stronger above stronger" (a refusal never overrides a good passport) ----

    @Test fun strongerPassportOverridesWeakerRefusal() {
        val w = world(
            baseline = mapOf(
                "RU" to listOf(corridor("RU", "SY", "refused")),
                "IE" to listOf(corridor("IE", "SY", "visa-free", 90)),
            ),
        )
        val acc = AccessModel.compute(listOf(doc("p1", Passport("RU")), doc("p2", Passport("IE"))), w)
        assertEquals(VISA_FREE, acc["SY"]!!.level)
    }

    @Test fun allPassportsRefusedStaysRefused() {
        val w = world(
            baseline = mapOf(
                "RU" to listOf(corridor("RU", "SY", "refused")),
                "SA" to listOf(corridor("SA", "SY", "refused")),
            ),
        )
        val acc = AccessModel.compute(listOf(doc("p1", Passport("RU")), doc("p2", Passport("SA"))), w)
        assertEquals(REFUSED, acc["SY"]!!.level)
    }

    @Test fun homeCountriesAreAllPassports() {
        val docs = listOf(doc("p1", Passport("GB")), doc("p2", Passport("CZ")), doc("d", Holding("ru-visa")))
        assertEquals(setOf("GB", "CZ"), AccessModel.homeCountries(docs))
    }

    @Test fun breakdownListsPerDocumentStrongestFirst() {
        val w = world(
            baseline = mapOf(
                "GB" to listOf(corridor("GB", "SY", "visa-required")),
                "IE" to listOf(corridor("IE", "SY", "visa-free", 90)),
            ),
        )
        val docs = listOf(doc("p1", Passport("GB")), doc("p2", Passport("IE")))
        val bd = AccessModel.breakdownFor("SY", docs, w)
        assertEquals(2, bd.size)
        assertEquals(VISA_FREE, bd[0].access.level)
        assertEquals(VISA_REQUIRED, bd[1].access.level)
    }

    @Test fun bestDocumentPrefersHeldVisaOverVisaRequiredPassport() {
        val w = world(
            baseline = mapOf("GB" to listOf(corridor("GB", "SY", "visa-required"))),
            holdings = mapOf("x" to DataHolding("x", "X Visa", "short_term_visa", "SY")),
            benefits = mapOf("x" to listOf(Benefit("x", "SY", "visa-free", 90))),
        )
        val docs = listOf(doc("p", Passport("GB")), doc("d", Holding("x")))
        assertEquals("d", AccessModel.bestDocumentId("SY", docs, w))
    }

    @Test fun bestDocumentPrefersStrongerPassport() {
        val w = world(
            baseline = mapOf(
                "GB" to listOf(corridor("GB", "SY", "visa-required")),
                "IE" to listOf(corridor("IE", "SY", "visa-free", 90)),
            ),
        )
        val docs = listOf(doc("p1", Passport("GB")), doc("p2", Passport("IE")))
        assertEquals("p2", AccessModel.bestDocumentId("SY", docs, w))
    }

    @Test fun bestDocumentIgnoresExpiredDocuments() {
        val w = world(
            holdings = mapOf("x" to DataHolding("x", "X Visa", "short_term_visa", "SY")),
            benefits = mapOf("x" to listOf(Benefit("x", "SY", "visa-free", 90))),
        )
        val today = LocalDate.of(2026, 9, 8)
        val docs = listOf(docEx("expired", Holding("x"), "2020-01-01"), docEx("valid", Holding("x"), "2030-01-01"))
        assertEquals("valid", AccessModel.bestDocumentId("SY", docs, w, today))
        assertNull(AccessModel.bestDocumentId("SY", docs.filter { it.id == "expired" }, w, today))
    }

    @Test fun bestDocumentIsNullWithoutDocumentedRule() {
        val w = world()
        assertNull(AccessModel.bestDocumentId("XX", listOf(doc("p", Passport("GB"))), w))
    }

    @Test fun bestDocumentPrefersDestinationResidenceOverPassportFreedom() {
        val w = world(
            baseline = mapOf("CZ" to listOf(corridor("CZ", "DE", "visa-free", 90))),
            regimes = listOf(regime("eu", "EU", "freedom-of-movement", "CZ", "DE")),
        )
        val docs = listOf(doc("p", Passport("CZ")), doc("res", Custom(setOf("CZ"), "eu", "residence")))
        assertEquals("res", AccessModel.bestDocumentId("CZ", docs, w))
    }

    @Test fun bestDocumentPrefersResidenceShortStayOverEqualPassport() {
        val w = world(
            baseline = mapOf("GB" to listOf(corridor("GB", "DE", "visa-free", 90))),
            holdings = mapOf("res" to DataHolding("res", "Schengen Residence", "residency", "CZ")),
            benefits = mapOf("res" to listOf(Benefit("res", "DE", "visa-free", 90))),
        )
        val docs = listOf(doc("p", Passport("GB")), doc("res", Holding("res")))
        assertEquals("res", AccessModel.bestDocumentId("DE", docs, w))
    }

    @Test fun bestDocumentPrefersPassportOverResidenceInAnotherCountry() {
        val w = world(
            baseline = mapOf("CZ" to listOf(corridor("CZ", "DE", "visa-free", 90))),
            regimes = listOf(regime("eu", "EU", "freedom-of-movement", "CZ", "DE")),
        )
        val docs = listOf(doc("p", Passport("CZ")), doc("res", Custom(setOf("CZ"), "eu", "residence")))
        assertEquals("p", AccessModel.bestDocumentId("DE", docs, w))
    }

    @Test fun bestDocumentPrefersPassportOverWeakerVisa() {
        val w = world(
            baseline = mapOf("GB" to listOf(corridor("GB", "AD", "visa-free", 90))),
            holdings = mapOf("x" to DataHolding("x", "X Visa", "short_term_visa", "AD")),
            benefits = mapOf("x" to listOf(Benefit("x", "AD", "e-visa", 30))),
        )
        val docs = listOf(doc("p", Passport("GB")), doc("x", Holding("x")))
        assertEquals("p", AccessModel.bestDocumentId("AD", docs, w))
    }

    // ---- auto-select for a stop: carry only what still unlocks the destination ----

    @Test fun autoSelectCarriesOnlyDocsThatUnlockDest() {
        val w = world(
            baseline = mapOf("GB" to listOf(corridor("GB", "SY", "visa-required"))),
            regimes = listOf(regime("schengen", "Schengen", "visa-free", "DE", "FR")),
        )
        // A Schengen visa: unlocks DE/FR, not SY.
        val docs = listOf(doc("p", Passport("GB")), doc("v", Custom(setOf("DE"), "schengen", "visa", "schengen-visa")))
        // Carrying it onto a SY stop: it doesn't unlock SY, so it must NOT be pre-selected —
        // fall back to the best valid document (the GB passport).
        assertEquals("p", AccessModel.autoSelectDocumentId("SY", docs, w, carriedId = "v"))
        // Carrying it onto a DE stop: it DOES unlock DE, so it is carried through.
        assertEquals("v", AccessModel.autoSelectDocumentId("DE", docs, w, carriedId = "v"))
        // No carry: the best valid document for SY is the passport.
        assertEquals("p", AccessModel.autoSelectDocumentId("SY", docs, w))
    }

    @Test fun autoSelectNeverCarriesAnExpiredDoc() {
        val w = world(
            baseline = mapOf("GB" to listOf(corridor("GB", "SY", "visa-required"))),
            holdings = mapOf("x" to DataHolding("x", "X Visa", "short_term_visa", "SY")),
            benefits = mapOf("x" to listOf(Benefit("x", "SY", "visa-free", 90))),
        )
        val today = LocalDate.of(2026, 9, 8)
        val docs = listOf(doc("p", Passport("GB")), docEx("expired", Holding("x"), "2020-01-01"))
        // The expired visa is carried in, but it must not be pre-selected — fall back to the passport.
        assertEquals("p", AccessModel.autoSelectDocumentId("SY", docs, w, today, carriedId = "expired"))
    }

    @Test fun autoSelectDefersToNoneWhenNothingUnlocksDest() {
        val w = world()
        val docs = listOf(doc("p", Passport("GB")), doc("v", Custom(setOf("DE"), "schengen", "visa", "schengen-visa")))
        // A destination no held document documents: nothing is pre-selected (defer to none).
        assertNull(AccessModel.autoSelectDocumentId("XX", docs, w, carriedId = "v"))
        assertNull(AccessModel.autoSelectDocumentId("XX", docs, w))
    }

    // ---- primary passport: a pure tie-breaker (star wins only on an exact access tie) ----

    @Test fun bestDocumentPrimaryBreaksTieBetweenIdenticalPassports() {
        val w = world(baseline = mapOf("RU" to listOf(corridor("RU", "DE", "visa-free", 90))))
        val docs = listOf(doc("p1", Passport("RU")), doc("p2", Passport("RU")))
        // No primary: the first passport wins the tie (previous behaviour).
        assertEquals("p1", AccessModel.bestDocumentId("DE", docs, w))
        // Star the second passport: it now wins the tie.
        assertEquals("p2", AccessModel.bestDocumentId("DE", docs, w, primaryId = "p2"))
        // Star the first: it still wins.
        assertEquals("p1", AccessModel.bestDocumentId("DE", docs, w, primaryId = "p1"))
        // A primary that isn't among the tied docs: falls back to the first.
        assertEquals("p1", AccessModel.bestDocumentId("DE", docs, w, primaryId = "pX"))
    }

    @Test fun bestDocumentPrimaryNeverOverridesAStrongerPassport() {
        val w = world(
            baseline = mapOf(
                "GB" to listOf(corridor("GB", "SY", "visa-required")),
                "IE" to listOf(corridor("IE", "SY", "visa-free", 90)),
            ),
        )
        val docs = listOf(doc("pGB", Passport("GB")), doc("pIE", Passport("IE")))
        // Star the WEAK passport: the stronger non-starred passport still wins.
        assertEquals("pIE", AccessModel.bestDocumentId("SY", docs, w, primaryId = "pGB"))
        // Star the strong one: it wins (as before).
        assertEquals("pIE", AccessModel.bestDocumentId("SY", docs, w, primaryId = "pIE"))
    }

    @Test fun bestDocumentPrimaryBreaksTieWithinDocuments() {
        val w = world(
            holdings = mapOf("x" to DataHolding("x", "X Visa", "short_term_visa", "SY")),
            benefits = mapOf("x" to listOf(Benefit("x", "SY", "visa-free", 90))),
        )
        // Two identical visas: the starred one wins the tie.
        val docs = listOf(doc("v1", Holding("x")), doc("v2", Holding("x")))
        assertEquals("v1", AccessModel.bestDocumentId("SY", docs, w))
        assertEquals("v2", AccessModel.bestDocumentId("SY", docs, w, primaryId = "v2"))
    }

    @Test fun mobilityBlocPrefersFreedomThenLargest() {
        val w = world(
            regimes = listOf(
                regime("small", "Small", "freedom-of-movement", "CZ"),
                regime("big", "Big", "freedom-of-movement", "CZ", "DE", "FR"),
                regime("vf", "Vf", "visa-free", "CZ", "DE", "FR", "IT"),
            ),
        )
        // Single country: the largest freedom-of-movement bloc wins over a bigger visa-free one.
        assertEquals("big", w.mobilityBlocFor(setOf("CZ"))?.id)
        // Every selected country is covered by one bloc.
        assertEquals("big", w.mobilityBlocFor(setOf("CZ", "FR"))?.id)
        // No single bloc covers all: fall back to the best bloc any selected country has.
        assertEquals("big", w.mobilityBlocFor(setOf("CZ", "XX"))?.id)
        // No bloc at all.
        assertEquals(null, w.mobilityBlocFor(setOf("XX"))?.id)
    }

    @Test fun holdingForMatchesCountryAndKind() {
        val w = world(
            regimes = listOf(
                regime("eu-eea-efta", "EU", "freedom-of-movement", "CZ", "DE"),
                regime("gcc", "GCC", "freedom-of-movement", "SA", "QA", "AE"),
            ),
            holdings = mapOf(
                "schengen-residence" to DataHolding("schengen-residence", "Schengen/EU Residence", "residency", "EU"),
                "schengen-visa" to DataHolding("schengen-visa", "Schengen Visa", "short_term_visa", "EU"),
                "gcc-residence" to DataHolding("gcc-residence", "GCC Residency", "residency", "SA"),
                "us-green-card" to DataHolding("us-green-card", "US Green Card", "residency", "US"),
            ),
        )
        assertEquals("schengen-residence", w.holdingFor(setOf("CZ"), "residence"))
        assertEquals("schengen-visa", w.holdingFor(setOf("DE"), "visa"))
        assertEquals("gcc-residence", w.holdingFor(setOf("SA"), "residence"))
        assertEquals("us-green-card", w.holdingFor(setOf("US"), "residence"))
        assertEquals(null, w.holdingFor(setOf("LB"), "visa")) // no known holding covers Lebanon
    }

    @Test fun residenceLevelIsLabelledAsResidencePermit() {
        assertEquals("Residence permit", RESIDENCE.label())
    }

    @Test fun visaAndResidenceHoldingPickersAreDisjoint() {
        val w = world(
            regimes = listOf(
                regime("eu-eea-efta", "EU", "freedom-of-movement", "CZ", "DE"),
            ),
            holdings = mapOf(
                "schengen-residence" to DataHolding("schengen-residence", "Schengen/EU Residence", "residency", "EU"),
                "schengen-visa" to DataHolding("schengen-visa", "Schengen Visa", "short_term_visa", "EU"),
                "sg-visa" to DataHolding("sg-visa", "Singapore Work/Residence Permit", "long_term_visa", "SG"),
                "apec-card" to DataHolding("apec-card", "APEC Business Travel Card", "special_permit", "AU"),
            ),
        )

        assertEquals(listOf("schengen-residence", "sg-visa"), w.holdingsForKind("residence").map { it.id })
        assertEquals(listOf("apec-card", "schengen-visa"), w.holdingsForKind("visa").map { it.id })
        assertEquals("sg-visa", w.holdingFor(setOf("SG"), "residence"))
        assertNull(w.holdingFor(setOf("SG"), "visa"))
        assertEquals("schengen-visa", w.holdingFor(setOf("DE"), "visa"))
        assertEquals("apec-card", w.holdingFor(setOf("AU"), "visa"))
    }

    @Test fun customResidenceAppliesKnownHoldingPerks() {
        // A Czech residence typed as a Schengen/EU residence unlocks e.g. Mexico, beyond the bloc.
        val w = world(
            baseline = mapOf("RU" to listOf(corridor("RU", "MX", "visa-required"))),
            regimes = listOf(regime("eu-eea-efta", "EU", "freedom-of-movement", "CZ", "DE", "FR")),
            holdings = mapOf("schengen-residence" to DataHolding("schengen-residence", "Schengen/EU Residence", "residency", "EU")),
            benefits = mapOf("schengen-residence" to listOf(Benefit("schengen-residence", "MX", "visa-free", 180))),
        )
        val acc = AccessModel.compute(
            listOf(doc("p", Passport("RU")), doc("d", Custom(setOf("CZ"), "eu-eea-efta", "residence", "schengen-residence"))),
            w,
        )
        assertEquals(RESIDENCE, acc["CZ"]!!.level)
        assertEquals(VISA_FREE, acc["MX"]!!.level) // perk from the known holding, beyond the bloc
    }

    // ---- stay_rules: per-destination entry window + single/multiple entry ----

    @Test fun stayRuleForPicksMostSpecificNationality() {
        val al = StayRule("Western Balkans", setOf("AL"), "rolling", 90, 180, false, setOf("EU-EEA", "GB"), null)
        val ar = StayRule("Argentina", setOf("AR"), "per-entry", 90, null, true, setOf("US"), null)
        val w = world(
            regimes = listOf(regime("eu-eea-efta", "EU", "freedom-of-movement", "FR", "DE")),
            stayRules = listOf(al, ar),
        )
        assertEquals("Western Balkans", w.stayRuleFor("AL", setOf("GB"))?.zoneName) // named passport
        assertEquals("Western Balkans", w.stayRuleFor("AL", setOf("FR"))?.zoneName) // EU-EEA bloc code
        assertEquals(null, w.stayRuleFor("AL", setOf("IN"))) // no matching nationality
        assertEquals("Argentina", w.stayRuleFor("AR", setOf("US"))?.zoneName)
        assertEquals(null, w.stayRuleFor("AR", setOf("GB")))
        assertEquals(null, w.stayRuleFor("XX", setOf("GB"))) // no rule names this destination
    }

    @Test fun stayRuleNamedBeatsWildcard() {
        val any = StayRule("Any", setOf("DE"), "rolling", 90, 180, true, setOf("*"), null)
        val named = StayRule("Named", setOf("DE"), "per-entry", 180, null, true, setOf("IN"), null)
        val w = world(stayRules = listOf(any, named))
        assertEquals("Named", w.stayRuleFor("DE", setOf("IN"))?.zoneName) // IN beats the wildcard
        assertEquals("Any", w.stayRuleFor("DE", setOf("GB"))?.zoneName) // falls back to the wildcard
    }

    @Test fun stayRuleOutsideValidityWindowIsIgnored() {
        // P3-37: a rule whose window excludes `today` is filtered before scoring — validFrom in
        // the future and validTo in the past both make it invisible even if it's the only match.
        val future = StayRule("Future", setOf("DE"), "rolling", 90, 180, true, setOf("*"), null,
            validFrom = "2099-01-01")
        val past = StayRule("Past", setOf("DE"), "per-entry", 90, null, true, setOf("*"), null,
            validTo = "2000-01-01")
        val inWindow = StayRule("InWindow", setOf("DE"), "per-entry", 180, null, true, setOf("*"), null,
            validFrom = "2020-01-01", validTo = "2030-01-01")
        val today = LocalDate.parse("2026-09-28")
        assertEquals(
            "InWindow",
            world(stayRules = listOf(future, past, inWindow))
                .stayRuleFor("DE", setOf("GB"), today = today, allowSynthetic = false)?.zoneName,
        )
        // With only out-of-window rules present, the (non-synthetic) lookup finds nothing.
        assertNull(world(stayRules = listOf(future, past))
            .stayRuleFor("DE", setOf("GB"), today = today, allowSynthetic = false))
    }

    @Test fun zoneKeyFallsBackThroughIdToZoneName() {
        // P3-37: zoneKeyFor tiers — zoneId beats id beats zoneName; the zoneName tier fires only
        // when both id and zoneId are blank (legacy stay_rules rows without an id).
        val w = world()
        assertEquals(
            "stay:z1",
            w.zoneKeyFor(StayRule("Z", setOf("DE"), "rolling", 90, 180, true, setOf("*"), null, id = "", zoneId = "z1")),
        )
        assertEquals(
            "stay:r1",
            w.zoneKeyFor(StayRule("Z", setOf("DE"), "rolling", 90, 180, true, setOf("*"), null, id = "r1")),
        )
        assertEquals("stay:Z", w.zoneKeyFor(StayRule("Z", setOf("DE"), "rolling", 90, 180, true, setOf("*"), null)))
    }

    @Test fun precomputedStatusesMatchTheDefaultPath() {
        // P3-36: every precompute overload accepts an optional `statuses` map (a precomputed
        // DocStatus.all), but no test ever passed one, so the memoized path was unpinned — deleting
        // the parameter would keep the suite green. Pin parity: for a world mixing valid, expired and
        // superseded docs plus a trip, the explicit-map call must equal the default (recomputed) call.
        val today = LocalDate.parse("2026-09-28")
        val docs = listOf(
            Document("p1", "FR", Passport("FR"), "2031-01-01"),
            Document("p2", "DE", Passport("DE"), "2020-01-01"),
            Document("v1", "DE visa", Custom(setOf("DE"), null, "visa"), "2031-01-01"),
            Document("old", "Old CZ", Passport("CZ"), "2031-01-01", supersededBy = "new"),
            Document("new", "New CZ", Passport("CZ"), "2031-01-01"),
        )
        val trips = listOf(Trip("t1", listOf(TripStop("s1", "DE", today.plusDays(10), today.plusDays(20), null))))
        val w = world(baseline = mapOf("FR" to listOf(corridor("FR", "DE", "visa-free", 90))))
        val pre = DocStatus.all(docs, trips, w, today)

        assertEquals(AccessModel.compute(docs, w, today, trips), AccessModel.compute(docs, w, today, trips, statuses = pre))
        assertEquals(AccessModel.validHomeCountries(docs, w, today, trips), AccessModel.validHomeCountries(docs, w, today, trips, statuses = pre))
        assertEquals(AccessModel.ownVisaCountries(docs, w, today, trips), AccessModel.ownVisaCountries(docs, w, today, trips, statuses = pre))
        for (dest in listOf("DE", "FR", "CZ", "XX")) {
            assertEquals(
                "bestDocumentId($dest)",
                AccessModel.bestDocumentId(dest, docs, w, today, "p1", trips),
                AccessModel.bestDocumentId(dest, docs, w, today, "p1", trips, statuses = pre),
            )
            assertEquals(
                "gateDocumentId($dest)",
                AccessModel.gateDocumentId(dest, docs, w, today, trips),
                AccessModel.gateDocumentId(dest, docs, w, today, trips, statuses = pre),
            )
        }
    }

    @Test fun stayRuleSummaryFormats() {
        assertEquals(
            "Multiple entry - 90 in 180 (rolling)",
            StayRule("Z", setOf("DE"), "rolling", 90, 180, true, setOf("*"), null).summary(),
        )
        assertEquals(
            "Single entry - 90 per entry",
            StayRule("Z", setOf("AR"), "per-entry", 90, null, false, setOf("*"), null).summary(),
        )
        // A rolling rule with multipleEntry=false has no confirmed entry count, so don't claim "Single entry".
        assertEquals(
            "90 in 180 (rolling)",
            StayRule("Z", setOf("BA"), "rolling", 90, 180, false, setOf("*"), null).summary(),
        )
    }

    // ---- expiry + Schengen travel-area (Bug A/B/C fixes) ----

    private fun docEx(id: String, kind: DocKind, expiry: String?) = Document(id, id, kind, expiry, null)

    @Test fun travelBlocForPrefersVisaFreeOverFreedom() {
        val w = world(
            regimes = listOf(
                regime("eu", "EU", "freedom-of-movement", "CZ", "DE", "FR", "IE"),
                regime("schengen", "Schengen", "visa-free", "CZ", "DE", "FR"),
            ),
        )
        // A residence/visa travel area should be the visa-free bloc, not the freedom bloc.
        assertEquals("schengen", w.travelBlocFor(setOf("CZ"))?.id)
        assertEquals(null, w.travelBlocFor(setOf("XX"))?.id)
    }

    @Test fun legacyFreedomBlocResidenceUsesVisaFreeTravelArea() {
        // A CZ residence stored with the freedom bloc must grant the visa-free (Schengen) travel
        // area, not the freedom bloc's extra non-Schengen states (e.g. Ireland).
        val w = world(
            regimes = listOf(
                regime("eu-eea-efta", "EU", "freedom-of-movement", "CZ", "DE", "IE"),
                regime("schengen", "Schengen", "visa-free", "CZ", "DE"),
            ),
        )
        val acc = AccessModel.compute(listOf(doc("d", Custom(setOf("CZ"), "eu-eea-efta", "residence"))), w)
        assertEquals(RESIDENCE, acc["CZ"]!!.level)
        assertEquals(VISA_FREE, acc["DE"]!!.level)
        assertEquals(null, acc["IE"])
    }

    @Test fun expiredPassportKeepsHomeAndFreedomButDropsBaseline() {
        val w = world(
            baseline = mapOf("CZ" to listOf(corridor("CZ", "JP", "visa-free", 90))),
            regimes = listOf(regime("eu", "EU", "freedom-of-movement", "CZ", "DE", "FR")),
        )
        val today = LocalDate.of(2026, 9, 8)
        val expired = AccessModel.compute(listOf(docEx("p", Passport("CZ"), "2020-01-01")), w, today)
        assertEquals(FREEDOM, expired["CZ"]!!.level) // home
        assertEquals(FREEDOM, expired["DE"]!!.level) // freedom bloc
        assertEquals(null, expired["JP"]) // baseline corridor gone
        val valid = AccessModel.compute(listOf(docEx("p", Passport("CZ"), "2030-01-01")), w, today)
        assertEquals(VISA_FREE, valid["JP"]!!.level)
    }

    @Test fun expiredDocumentGrantsNothing() {
        val w = world(
            holdings = mapOf("x" to DataHolding("x", "X Visa", "short_term_visa", "SY")),
            benefits = mapOf("x" to listOf(Benefit("x", "SY", "visa-free", 90))),
        )
        val today = LocalDate.of(2026, 9, 8)
        assertEquals(null, AccessModel.compute(listOf(docEx("d", Holding("x"), "2020-01-01")), w, today)["SY"])
        assertEquals(COVERED, AccessModel.compute(listOf(docEx("d", Holding("x"), "2030-01-01")), w, today)["SY"]!!.level)
    }

    @Test fun breakdownExcludesExpiredDocuments() {
        val w = world(
            holdings = mapOf("x" to DataHolding("x", "X Visa", "short_term_visa", "SY")),
            benefits = mapOf("x" to listOf(Benefit("x", "SY", "visa-free", 90))),
        )
        val today = LocalDate.of(2026, 9, 8)
        val expired = AccessModel.breakdownFor("SY", listOf(docEx("d", Holding("x"), "2020-01-01")), w, today)
        assertTrue(expired.isEmpty())
        val valid = AccessModel.breakdownFor("SY", listOf(docEx("d", Holding("x"), "2030-01-01")), w, today)
        assertEquals(1, valid.size)
        assertEquals(COVERED, valid[0].access.level)
    }

    @Test fun breakdownOmitsDocumentsWithNoDocumentedRule() {
        val w = world(baseline = mapOf("GB" to listOf(corridor("GB", "XX", "visa-required"))))
        val bd = AccessModel.breakdownFor("XX", listOf(doc("p", Passport("GB")), doc("d", Holding("missing"))), w)
        assertEquals(1, bd.size)
        assertEquals(VISA_REQUIRED, bd[0].access.level)
    }

    // ---- relevantDocuments: the stop-editor dropdown's relevance set (mirrors the map breakdown) ----

    @Test fun relevantDocumentsKeepOnlyDocsThatUnlockDest() {
        val w = world(
            baseline = mapOf(
                "GB" to listOf(corridor("GB", "DE", "visa-free", 90)), // GB passport unlocks DE
                "FR" to listOf(corridor("FR", "SY", "visa-free", 90)), // FR passport does not
            ),
        )
        val docs = listOf(doc("pGB", Passport("GB")), doc("pFR", Passport("FR")))
        assertEquals(listOf("pGB"), AccessModel.relevantDocuments("DE", docs, w).map { it.id })
    }

    @Test fun relevantDocumentsDropsUnrelatedVisaResidence() {
        val w = world(
            baseline = mapOf("GB" to listOf(corridor("GB", "FR", "visa-free", 90))),
            holdings = mapOf("ab-res" to DataHolding("ab-res", "Abkhazia Residence", "residency", "AB")),
        )
        val docs = listOf(doc("pGB", Passport("GB")), doc("ab", Holding("ab-res")))
        // The Abkhazia residence unlocks AB (its issuing country), not FR.
        assertEquals(listOf("pGB"), AccessModel.relevantDocuments("FR", docs, w).map { it.id })
        // But it IS relevant to its own country — where the GB passport has no rule.
        assertEquals(listOf("ab"), AccessModel.relevantDocuments("AB", docs, w).map { it.id })
    }

    @Test fun relevantDocumentsCoversWholeTravelBloc() {
        // A Schengen visa is relevant to every Schengen member, not just the one it names.
        val w = world(
            baseline = mapOf("IN" to listOf(corridor("IN", "DE", "visa-required"))),
            regimes = listOf(regime("schengen", "Schengen", "visa-free", "DE", "FR", "IT")),
        )
        val docs = listOf(doc("v", Custom(setOf("DE"), "schengen", "visa", "schengen-visa")))
        assertTrue(AccessModel.relevantDocuments("FR", docs, w).isNotEmpty())
        assertTrue(AccessModel.relevantDocuments("IT", docs, w).isNotEmpty())
    }

    @Test fun relevantDocumentsDropsExpiredNonPassport() {
        val w = world(
            holdings = mapOf("x" to DataHolding("x", "X Visa", "short_term_visa", "SY")),
            benefits = mapOf("x" to listOf(Benefit("x", "SY", "visa-free", 90))),
        )
        val today = LocalDate.of(2026, 9, 8)
        val docs = listOf(docEx("expired", Holding("x"), "2020-01-01"), docEx("valid", Holding("x"), "2030-01-01"))
        assertEquals(listOf("valid"), AccessModel.relevantDocuments("SY", docs, w, today).map { it.id })
    }

    @Test fun relevantDocumentsKeepsExpiredPassportForHomeOnly() {
        val w = world(
            baseline = mapOf("CZ" to listOf(corridor("CZ", "JP", "visa-free", 90))),
            regimes = listOf(regime("eu", "EU", "freedom-of-movement", "CZ", "DE")),
        )
        val today = LocalDate.of(2026, 9, 8)
        val docs = listOf(docEx("p", Passport("CZ"), "2020-01-01"))
        // Home (CZ) + freedom bloc (DE) stay relevant; the baseline corridor (JP) is dropped.
        assertTrue(AccessModel.relevantDocuments("CZ", docs, w, today).isNotEmpty())
        assertTrue(AccessModel.relevantDocuments("DE", docs, w, today).isNotEmpty())
        assertTrue(AccessModel.relevantDocuments("JP", docs, w, today).isEmpty())
    }

    @Test fun relevantDocumentsPreservesDocsOrder() {
        val w = world(
            baseline = mapOf(
                "GB" to listOf(corridor("GB", "DE", "visa-free", 90)),
                "IE" to listOf(corridor("IE", "DE", "visa-free", 90)),
            ),
        )
        val docs = listOf(doc("pIE", Passport("IE")), doc("pGB", Passport("GB")))
        // Both unlock DE; the original docs order is kept (not re-sorted by rank).
        assertEquals(listOf("pIE", "pGB"), AccessModel.relevantDocuments("DE", docs, w).map { it.id })
    }

    @Test fun stayLabelForUsesMatchingStayRule() {
        val rolling = StayRule("Western Balkans", setOf("BA"), "rolling", 90, 180, false, setOf("*"), null)
        val passport90 = Access(VISA_FREE, 90, "Passport rule")
        val document30 = Access(VISA_FREE, 30, "Schengen Residence (visa-free)", fromDocument = true)
        val document90 = Access(VISA_FREE, 90, "Schengen Residence (visa-free)", fromDocument = true)
        val freedomNull = Access(FREEDOM, null, "Bloc: EU")
        val visaRequired = Access(VISA_REQUIRED, null, "Passport rule")

        assertEquals("90 in 180 (rolling)", AccessModel.stayLabelFor(passport90, rolling))
        assertEquals("30 days", AccessModel.stayLabelFor(document30, rolling))
        assertEquals("90 in 180 (rolling)", AccessModel.stayLabelFor(document90, rolling))
        assertEquals("90 in 180 (rolling)", AccessModel.stayLabelFor(freedomNull, rolling))
        assertEquals(null, AccessModel.stayLabelFor(visaRequired, rolling))
        assertEquals("30 days", AccessModel.stayLabelFor(document30, null))
    }

    @Test fun realDatabaseGbpPassportIranIsVisaRequired() {
        val w = JdbcRepository.load(File("src/main/assets/visa_data.db"))
        val ir = AccessModel.compute(listOf(doc("p", Passport("GB"))), w)["IR"]!!
        // GB->IR is visa-required. The old "e-visa" was a scrape bug: a Kish Island sub-row on the
        // British-citizens page overwrote Iran's main row. Fixed at the VisaDB source (wikipedia
        // parser first-occurrence-wins) + a manual override; the app just reads the corrected DB.
        assertEquals(VISA_REQUIRED, ir.level)
        assertNull(ir.days)
    }

    @Test fun realDatabaseSchengaVisaGrantsSchengenInclBulgariaNotIrelandCyprus() {
        val w = JdbcRepository.load(File("src/main/assets/visa_data.db"))
        val acc = AccessModel.compute(listOf(doc("s", Holding("schengen-visa"))), w)
        assertTrue(acc.containsKey("DE")) // Germany is in Schengen
        // Bulgaria is in the Schengen travel area (current product definition) -> granted.
        assertEquals(COVERED, acc["BG"]!!.level)
        // Ireland and Cyprus are EU but non-Schengen -> not granted by a Schengen visa.
        assertEquals(null, acc["IE"])
        assertEquals(null, acc["CY"])
    }

    @Test fun realDatabaseSchengaResidenceLegacyFreedomBlocGrantsSchengenAndCyprusNotIreland() {
        val w = JdbcRepository.load(File("src/main/assets/visa_data.db"))
        // A legacy CZ residence stored with the EU/EEA/EFTA freedom bloc must grant the Schengen
        // travel area (which now includes Bulgaria) and Cyprus, which separately accepts a
        // Schengen residence permit, but NOT non-Schengen Ireland.
        val acc = AccessModel.compute(
            listOf(doc("d", Custom(setOf("CZ"), "eu-eea-efta", "residence", "schengen-residence"))), w,
        )
        assertEquals(RESIDENCE, acc["CZ"]!!.level)
        assertTrue(acc.containsKey("DE"))
        assertEquals(VISA_FREE, acc["BG"]!!.level)
        assertEquals(null, acc["IE"])
        assertEquals(VISA_FREE, acc["CY"]!!.level)
        assertEquals(90, acc["CY"]!!.days)
        assertTrue(acc["CY"]!!.fromDocument)
    }

    @Test fun realDatabaseSchengenResidenceGrantsCyprusNotIreland() {
        val w = JdbcRepository.load(File("src/main/assets/visa_data.db"))
        val acc = AccessModel.compute(listOf(doc("d", Holding("schengen-residence"))), w)
        assertEquals(VISA_FREE, acc["DE"]!!.level)
        assertEquals(VISA_FREE, acc["CY"]!!.level)
        assertEquals(90, acc["CY"]!!.days)
        assertTrue(acc["CY"]!!.fromDocument)
        assertEquals(null, acc["IE"])
    }

    @Test fun schengenVisaCyprusRequiresDoubleOrMultipleEntry() {
        val w = JdbcRepository.load(File("src/main/assets/visa_data.db"))
        val schengen = w.regimes.first { it.id == "schengen" }.members
        fun visa(entry: String) = Custom(schengen.toSet(), "schengen", "visa", "schengen-visa", entry)
        // IN does not itself grant Cyprus (visa-required), so Cyprus can only come from the
        // Schengen-visa perk, which is gated on the visa's entry type.
        val single = AccessModel.compute(listOf(doc("p", Passport("IN")), doc("v1", visa("single"))), w)
        assertEquals(VISA_REQUIRED, single["CY"]!!.level) // single entry -> no Cyprus perk
        val dbl = AccessModel.compute(listOf(doc("p", Passport("IN")), doc("v2", visa("double"))), w)
        assertEquals(COVERED, dbl["CY"]!!.level) // double entry -> Cyprus perk
        val multi = AccessModel.compute(listOf(doc("p", Passport("IN")), doc("v3", visa("multiple"))), w)
        assertEquals(COVERED, multi["CY"]!!.level) // multiple entry -> Cyprus perk
        // The rest of Schengen is covered regardless (the perk only gates Cyprus).
        assertEquals(COVERED, single["DE"]!!.level)
    }

    @Test fun realDatabaseBosniaSchengenResidenceFlagsDocumentDays() {
        val w = JdbcRepository.load(File("src/main/assets/visa_data.db"))
        // IN passport is visa-required for Bosnia, but a Schengen/EU residence grants 30 days.
        val acc = AccessModel.compute(
            listOf(doc("p", Passport("IN")), doc("d", Holding("schengen-residence"))),
            w,
        )
        val ba = acc["BA"]!!
        assertEquals(VISA_FREE, ba.level)
        assertEquals(30, ba.days)
        assertTrue(ba.fromDocument)
        // The generic Western-Balkans passport rule is still available for the card to label.
        assertTrue(w.stayRuleFor("BA", setOf("IN")) != null)
    }

    @Test fun realDatabaseStayRulesReflectAuditedWindows() {
        val w = JdbcRepository.load(File("src/main/assets/visa_data.db"))
        // The TH 60-day rule carries a valid_to of 2026-09-14 (the audited scheme's expiry). Pin the
        // reference date so this data check stays deterministic once real time passes that expiry.
        val today = LocalDate.of(2026, 9, 1)

        assertEquals(183, w.stayRuleFor("PE", setOf("MX"), today)?.windowDays)
        assertEquals(90, w.stayRuleFor("PE", setOf("GB"), today)?.windowDays)
        assertEquals(60, w.stayRuleFor("PE", setOf("DO"), today)?.windowDays)

        assertEquals(30, w.stayRuleFor("PA", setOf("HK"), today)?.windowDays)
        assertEquals(180, w.stayRuleFor("PA", setOf("US"), today)?.windowDays)
        assertEquals(90, w.stayRuleFor("PA", setOf("GB"), today)?.windowDays)

        assertEquals(7, w.stayRuleFor("TH", setOf("KH"), today)?.windowDays)
        assertEquals(14, w.stayRuleFor("TH", setOf("MM"), today)?.windowDays)
        assertEquals(30, w.stayRuleFor("TH", setOf("TL"), today)?.windowDays)
        assertEquals(90, w.stayRuleFor("TH", setOf("AR"), today)?.windowDays)
        assertEquals(60, w.stayRuleFor("TH", setOf("GB"), today)?.windowDays)

        val tr = w.stayRuleFor("TR", setOf("GB"), today)!!
        assertEquals(90, tr.windowDays)
        assertEquals(180, tr.windowPeriodDays)
        assertTrue(tr.multipleEntry)
    }

    // ---- residence classes + benefit residence-minimum gating ----

    private fun residenceDoc(id: String, cls: ResidenceClass?) =
        Document(id, id, Holding("schengen-residence"), null, null, cls)

    private fun schengenWorld() = world(
        holdings = mapOf("schengen-residence" to DataHolding("schengen-residence", "Schengen/EU Residence", "residency", "EU")),
        benefits = mapOf("schengen-residence" to listOf(
            Benefit("schengen-residence", "BA", "visa-free", 30, emptySet(), "permanent"),
            Benefit("schengen-residence", "AL", "visa-free", 90, emptySet(), "long_term"),
            Benefit("schengen-residence", "DE", "visa-free", 90),
        )),
    )

    @Test fun temporaryResidenceFailsHigherResidenceMinimums() {
        // No stored class: a Schengen residence defaults to TEMPORARY.
        val acc = AccessModel.compute(listOf(doc("d", Holding("schengen-residence"))), schengenWorld())
        assertEquals(null, acc["BA"]) // temporary < permanent
        assertEquals(null, acc["AL"]) // temporary < long_term
        assertEquals(VISA_FREE, acc["DE"]!!.level) // null minimum passes
    }

    @Test fun permanentResidenceSatisfiesPermanentMinimum() {
        val acc = AccessModel.compute(listOf(residenceDoc("d", ResidenceClass.PERMANENT)), schengenWorld())
        assertEquals(VISA_FREE, acc["BA"]!!.level)
        assertEquals(VISA_FREE, acc["AL"]!!.level)
        assertEquals(VISA_FREE, acc["DE"]!!.level)
    }

    @Test fun longTermResidenceSatisfiesLongTermButNotPermanent() {
        val acc = AccessModel.compute(listOf(residenceDoc("d", ResidenceClass.LONG_TERM)), schengenWorld())
        assertEquals(VISA_FREE, acc["AL"]!!.level) // long_term >= long_term
        assertEquals(null, acc["BA"]) // long_term < permanent
    }

    @Test fun nullResidenceMinPassesForAllClasses() {
        val w = schengenWorld()
        for (cls in ResidenceClass.entries) {
            val acc = AccessModel.compute(listOf(residenceDoc("d", cls)), w)
            assertEquals(VISA_FREE, acc["DE"]!!.level)
        }
        assertEquals(VISA_FREE, AccessModel.compute(listOf(doc("d", Holding("schengen-residence"))), w)["DE"]!!.level)
    }

    @Test fun nonResidenceDocumentFailsResidenceMinimum() {
        val w = world(
            holdings = mapOf("jp-visa" to DataHolding("jp-visa", "Valid Japanese Visa", "short_term_visa", "JP")),
            benefits = mapOf("jp-visa" to listOf(Benefit("jp-visa", "KR", "visa-free", 90, emptySet(), "long_term"))),
        )
        assertEquals(null, AccessModel.compute(listOf(doc("d", Holding("jp-visa"))), w)["KR"])
        // The same grant without a minimum still applies to a visa.
        val w2 = world(
            holdings = mapOf("jp-visa" to DataHolding("jp-visa", "Valid Japanese Visa", "short_term_visa", "JP")),
            benefits = mapOf("jp-visa" to listOf(Benefit("jp-visa", "KR", "visa-free", 90))),
        )
        assertEquals(COVERED, AccessModel.compute(listOf(doc("d", Holding("jp-visa"))), w2)["KR"]!!.level)
    }

    @Test fun unparseableResidenceMinimumFailsClosed() {
        // P3-28: a residenceMin that doesn't parse (a data typo) must deny the grant — it must not
        // be treated as "no minimum" and over-grant even to a permanent residence.
        val w = world(
            holdings = mapOf("schengen-residence" to DataHolding("schengen-residence", "Schengen/EU Residence", "residency", "EU")),
            benefits = mapOf("schengen-residence" to listOf(Benefit("schengen-residence", "BA", "visa-free", 30, emptySet(), "permant"))),
        )
        assertNull(AccessModel.compute(listOf(residenceDoc("d", ResidenceClass.PERMANENT)), w)["BA"])
    }

    @Test fun customResidenceGatedByResidenceMinimum() {
        val w = world(
            holdings = mapOf("schengen-residence" to DataHolding("schengen-residence", "Schengen/EU Residence", "residency", "EU")),
            benefits = mapOf("schengen-residence" to listOf(Benefit("schengen-residence", "BA", "visa-free", 30, emptySet(), "permanent"))),
        )
        val temp = AccessModel.compute(listOf(doc("d", Custom(setOf("CZ"), "eu", "residence", "schengen-residence"))), w)
        assertEquals(null, temp["BA"]) // default temporary < permanent
        val perm = AccessModel.compute(
            listOf(Document("d", "d", Custom(setOf("CZ"), "eu", "residence", "schengen-residence"), null, null, ResidenceClass.PERMANENT)),
            w,
        )
        assertEquals(VISA_FREE, perm["BA"]!!.level)
    }

    @Test fun residenceAccessCarriesEffectiveClass() {
        val w = world(
            holdings = mapOf(
                "us-green-card" to DataHolding("us-green-card", "US Green Card", "residency", "US"),
                "schengen-residence" to DataHolding("schengen-residence", "Schengen/EU Residence", "residency", "EU"),
                "sg-visa" to DataHolding("sg-visa", "Singapore Work/Residence Permit", "long_term_visa", "SG"),
            ),
        )
        // Default for a green card is permanent; a bare Schengen residence defaults to temporary.
        val gc = AccessModel.compute(listOf(doc("d", Holding("us-green-card"))), w)["US"]!!
        assertEquals(RESIDENCE, gc.level)
        assertEquals(ResidenceClass.PERMANENT, gc.residenceClass)
        val cz = AccessModel.compute(listOf(doc("d", Holding("schengen-residence"))), w)["EU"]!!
        assertEquals(RESIDENCE, cz.level)
        assertEquals(ResidenceClass.TEMPORARY, cz.residenceClass)
        val sgDoc = doc("d", Holding("sg-visa"))
        val sg = AccessModel.compute(listOf(sgDoc), w)["SG"]!!
        assertEquals(RESIDENCE, sg.level)
        assertEquals(ResidenceClass.LONG_TERM, sg.residenceClass)
        assertFalse("SG" in AccessModel.ownVisaCountries(listOf(sgDoc), w))
    }

    @Test fun strongerResidenceClassWinsOnTie() {
        val w = world(
            holdings = mapOf("schengen-residence" to DataHolding("schengen-residence", "Schengen/EU Residence", "residency", "EU")),
        )
        val tempFirst = AccessModel.compute(
            listOf(residenceDoc("temp", ResidenceClass.TEMPORARY), residenceDoc("perm", ResidenceClass.PERMANENT)),
            w,
        )["EU"]!!
        assertEquals(RESIDENCE, tempFirst.level)
        assertEquals(ResidenceClass.PERMANENT, tempFirst.residenceClass)

        val permFirst = AccessModel.compute(
            listOf(residenceDoc("perm", ResidenceClass.PERMANENT), residenceDoc("temp", ResidenceClass.TEMPORARY)),
            w,
        )["EU"]!!
        assertEquals(ResidenceClass.PERMANENT, permFirst.residenceClass)
    }

    @Test fun residenceClassFromId() {
        assertEquals(ResidenceClass.TEMPORARY, ResidenceClass.fromId("temporary"))
        assertEquals(ResidenceClass.LONG_TERM, ResidenceClass.fromId("long_term"))
        assertEquals(ResidenceClass.PERMANENT, ResidenceClass.fromId("permanent"))
        assertNull(ResidenceClass.fromId(null))
        assertNull(ResidenceClass.fromId("bogus"))
    }

    @Test fun defaultResidenceClassByHolding() {
        assertEquals(ResidenceClass.PERMANENT, defaultResidenceClassFor("us-green-card", false))
        assertEquals(ResidenceClass.PERMANENT, defaultResidenceClassFor("ca-pr", false))
        assertEquals(ResidenceClass.PERMANENT, defaultResidenceClassFor("au-pr", false))
        assertEquals(ResidenceClass.LONG_TERM, defaultResidenceClassFor("sg-visa", false))
        assertEquals(ResidenceClass.TEMPORARY, defaultResidenceClassFor("schengen-residence", false))
        assertEquals(ResidenceClass.TEMPORARY, defaultResidenceClassFor(null, true))
    }

    @Test fun residenceClassForDocuments() {
        val w = world(
            holdings = mapOf(
                "schengen-residence" to DataHolding("schengen-residence", "Schengen/EU Residence", "residency", "EU"),
                "sg-visa" to DataHolding("sg-visa", "Singapore Work/Residence Permit", "long_term_visa", "SG"),
                "jp-visa" to DataHolding("jp-visa", "Valid Japanese Visa", "short_term_visa", "JP"),
            ),
        )
        assertNull(Document("p", "p", Passport("GB")).residenceClassFor(w))
        assertNull(Document("v", "v", Holding("jp-visa")).residenceClassFor(w))
        assertEquals(ResidenceClass.TEMPORARY, Document("d", "d", Holding("schengen-residence")).residenceClassFor(w))
        assertEquals(ResidenceClass.PERMANENT, residenceDoc("d", ResidenceClass.PERMANENT).residenceClassFor(w))
        assertEquals(ResidenceClass.LONG_TERM, Document("s", "s", Holding("sg-visa")).residenceClassFor(w))
        assertEquals(ResidenceClass.TEMPORARY, Document("c", "c", Custom(setOf("CZ"), null, "residence")).residenceClassFor(w))
        assertEquals(ResidenceClass.LONG_TERM, Document("c", "c", Custom(setOf("SG"), null, "residence"), null, null, ResidenceClass.LONG_TERM).residenceClassFor(w))
        assertNull(Document("c", "c", Custom(setOf("CZ"), null, "visa")).residenceClassFor(w))
        // P2-29: a Custom residence typed as a known holding inherits that holding's default class
        // (a green card is permanent, not blanket temporary) — matching the identical Holding doc.
        assertEquals(
            ResidenceClass.PERMANENT,
            Document("g", "g", Custom(setOf("US"), null, "residence", "us-green-card")).residenceClassFor(w),
        )
    }

    @Test fun ownVisaCountriesCollectsIssuingAndCustomCountries() {
        // P2-35: pin the positive paths — a short-term visa holding contributes its issuing
        // country, a custom visa its countries — alongside the exclusions (residences, expired).
        val w = world(
            holdings = mapOf(
                "jp-visa" to DataHolding("jp-visa", "Japan Visa", "short_term_visa", "JP"),
                "cz-res" to DataHolding("cz-res", "CZ Residence", "residency", "CZ"),
            ),
        )
        val today = LocalDate.of(2026, 9, 16)
        val jp = doc("jp", Holding("jp-visa"))
        val czRes = doc("cz", Holding("cz-res"))
        val ca = Document("ca", "CA Visa", Custom(setOf("CA"), null, "visa", null, "multiple"), "2028-01-01")
        val expiredJp = Document("jp2", "Old JP Visa", Holding("jp-visa"), "2025-01-01")
        assertEquals(setOf("JP", "CA"), AccessModel.ownVisaCountries(listOf(jp, czRes, ca, expiredJp), w, today))
    }

    @Test fun validHomeCountriesDropsExpiredPassports() {
        // P2-30: only non-expired passports count as home countries for stay-rule resolution.
        val w = world()
        val today = LocalDate.of(2026, 9, 16)
        val valid = doc("p1", Passport("GB"))
        val expired = Document("p2", "FR", Passport("FR"), "2025-01-01")
        assertEquals(setOf("GB"), AccessModel.validHomeCountries(listOf(valid, expired), w, today))
        assertTrue(AccessModel.validHomeCountries(listOf(expired), w, today).isEmpty())
    }
}
