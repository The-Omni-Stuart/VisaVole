package com.example.visa_vole.domain

import com.example.visa_vole.data.Benefit
import com.example.visa_vole.data.Corridor
import com.example.visa_vole.data.JdbcRepository
import com.example.visa_vole.data.Regime
import com.example.visa_vole.data.StayRule
import com.example.visa_vole.data.WorldData
import com.example.visa_vole.data.Holding as DataHolding
import com.example.visa_vole.domain.AccessLevel.COVERED
import com.example.visa_vole.domain.AccessLevel.ETA
import com.example.visa_vole.domain.AccessLevel.E_VISA
import com.example.visa_vole.domain.AccessLevel.FREEDOM
import com.example.visa_vole.domain.AccessLevel.REFUSED
import com.example.visa_vole.domain.AccessLevel.RESIDENCE
import com.example.visa_vole.domain.AccessLevel.UNKNOWN
import com.example.visa_vole.domain.AccessLevel.VISA_FREE
import com.example.visa_vole.domain.AccessLevel.VISA_REQUIRED
import com.example.visa_vole.domain.DocKind.Custom
import com.example.visa_vole.domain.DocKind.Holding
import com.example.visa_vole.domain.DocKind.Passport
import org.junit.Assert.assertEquals
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
    }

    @Test fun documentOverridesPassportRefusal() {
        // A visa you hold lets you in even where your passport is refused.
        val w = world(
            baseline = mapOf("GB" to listOf(corridor("GB", "SY", "refused"))),
            holdings = mapOf("x" to DataHolding("x", "X Visa", "short_term_visa", "SY")),
            benefits = mapOf("x" to listOf(Benefit("x", "SY", "visa-free", 90))),
        )
        assertEquals(COVERED, AccessModel.compute(listOf(doc("p", Passport("GB")), doc("d", Holding("x"))), w)["SY"]!!.level)
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

    @Test fun breakdownShowsExpiredDocumentAsNoAccess() {
        val w = world(
            holdings = mapOf("x" to DataHolding("x", "X Visa", "short_term_visa", "SY")),
            benefits = mapOf("x" to listOf(Benefit("x", "SY", "visa-free", 90))),
        )
        val today = LocalDate.of(2026, 9, 8)
        val bd = AccessModel.breakdownFor("SY", listOf(docEx("d", Holding("x"), "2020-01-01")), w, today)
        assertEquals(1, bd.size)
        assertEquals(UNKNOWN, bd[0].access.level)
    }

    @Test fun realDatabaseGbpPassportIranIsEVisaNotVisaFree() {
        val w = JdbcRepository.load(File("src/main/assets/visa_data.db"))
        val ir = AccessModel.compute(listOf(doc("p", Passport("GB"))), w)["IR"]!!
        assertEquals(E_VISA, ir.level)
        assertEquals(30, ir.days)
    }

    @Test fun realDatabaseSchengaVisaDoesNotGrantIrelandButGrantsSchenga() {
        val w = JdbcRepository.load(File("src/main/assets/visa_data.db"))
        val acc = AccessModel.compute(listOf(doc("s", Holding("schengen-visa"))), w)
        assertTrue(acc.containsKey("DE")) // Germany is in Schengen
        assertEquals(null, acc["IE"])
        assertEquals(null, acc["CY"])
        assertEquals(null, acc["BG"])
    }

    @Test fun realDatabaseSchengaResidenceLegacyFreedomBlocDoesNotGrantIreland() {
        val w = JdbcRepository.load(File("src/main/assets/visa_data.db"))
        // A legacy CZ residence stored with the EU/EEA/EFTA freedom bloc must still only grant the
        // Schengen travel area (not Ireland/Cyprus/Bulgaria).
        val acc = AccessModel.compute(
            listOf(doc("d", Custom(setOf("CZ"), "eu-eea-efta", "residence", "schengen-residence"))), w,
        )
        assertEquals(RESIDENCE, acc["CZ"]!!.level)
        assertTrue(acc.containsKey("DE"))
        assertEquals(null, acc["IE"])
        assertEquals(null, acc["CY"])
        assertEquals(null, acc["BG"])
    }
}
