package com.cbkres.visavole.domain

import com.cbkres.visavole.data.Benefit
import com.cbkres.visavole.data.Corridor
import com.cbkres.visavole.data.JdbcRepository
import com.cbkres.visavole.data.Regime
import com.cbkres.visavole.data.StayRule
import com.cbkres.visavole.data.WorldData
import com.cbkres.visavole.data.Holding as DataHolding
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

    private fun docEx(id: String, kind: DocKind, expiry: String?) = Document(id, id, kind, null, expiry, null)

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

        assertEquals(183, w.stayRuleFor("PE", setOf("MX"))?.windowDays)
        assertEquals(90, w.stayRuleFor("PE", setOf("GB"))?.windowDays)
        assertEquals(60, w.stayRuleFor("PE", setOf("DO"))?.windowDays)

        assertEquals(30, w.stayRuleFor("PA", setOf("HK"))?.windowDays)
        assertEquals(180, w.stayRuleFor("PA", setOf("US"))?.windowDays)
        assertEquals(90, w.stayRuleFor("PA", setOf("GB"))?.windowDays)

        assertEquals(7, w.stayRuleFor("TH", setOf("KH"))?.windowDays)
        assertEquals(14, w.stayRuleFor("TH", setOf("MM"))?.windowDays)
        assertEquals(30, w.stayRuleFor("TH", setOf("TL"))?.windowDays)
        assertEquals(90, w.stayRuleFor("TH", setOf("AR"))?.windowDays)
        assertEquals(60, w.stayRuleFor("TH", setOf("GB"))?.windowDays)

        val tr = w.stayRuleFor("TR", setOf("GB"))!!
        assertEquals(90, tr.windowDays)
        assertEquals(180, tr.windowPeriodDays)
        assertTrue(tr.multipleEntry)
    }

    // ---- residence classes + benefit residence-minimum gating ----

    private fun residenceDoc(id: String, cls: ResidenceClass?) =
        Document(id, id, Holding("schengen-residence"), null, null, null, cls)

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

    @Test fun customResidenceGatedByResidenceMinimum() {
        val w = world(
            holdings = mapOf("schengen-residence" to DataHolding("schengen-residence", "Schengen/EU Residence", "residency", "EU")),
            benefits = mapOf("schengen-residence" to listOf(Benefit("schengen-residence", "BA", "visa-free", 30, emptySet(), "permanent"))),
        )
        val temp = AccessModel.compute(listOf(doc("d", Custom(setOf("CZ"), "eu", "residence", "schengen-residence"))), w)
        assertEquals(null, temp["BA"]) // default temporary < permanent
        val perm = AccessModel.compute(
            listOf(Document("d", "d", Custom(setOf("CZ"), "eu", "residence", "schengen-residence"), null, null, null, ResidenceClass.PERMANENT)),
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
        assertEquals(ResidenceClass.LONG_TERM, Document("c", "c", Custom(setOf("SG"), null, "residence"), null, null, null, ResidenceClass.LONG_TERM).residenceClassFor(w))
        assertNull(Document("c", "c", Custom(setOf("CZ"), null, "visa")).residenceClassFor(w))
    }
}
