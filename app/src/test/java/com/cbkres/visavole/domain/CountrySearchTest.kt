package com.cbkres.visavole.domain

import com.cbkres.visavole.data.Country
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests the country-search matching rules ([searchCountries]) at the seam every country
 * search UI is meant to share: map search bar, onboarding picker, and the document/stop
 * [com.cbkres.visavole.ui.components.forms.CountryPicker].
 *
 * Fixture names mirror the shipped visa_data.db (Türkiye and Côte d'Ivoire are the only
 * diacritic names among the 203), so expectations are real user scenarios, not tautologies.
 */
class CountrySearchTest {

    private val world = mapOf(
        "TR" to Country("TR", "Türkiye"),
        "GB" to Country("GB", "United Kingdom"),
        "US" to Country("US", "United States"),
        "AU" to Country("AU", "Australia"),
        "CI" to Country("CI", "Côte d'Ivoire"),
        "FR" to Country("FR", "France"),
        "TN" to Country("TN", "Tunisia"),
    )

    @Test
    fun `plain ascii query finds diacritic name`() {
        assertEquals(listOf(Country("CI", "Côte d'Ivoire")), searchCountries(world, "cote"))
    }

    @Test
    fun `query typed with a stray diacritic still finds plain name`() {
        assertEquals(listOf(Country("GB", "United Kingdom")), searchCountries(world, "ünited kingdom"))
    }

    @Test
    fun `exact iso code query finds country`() {
        assertEquals(listOf(Country("CI", "Côte d'Ivoire")), searchCountries(world, "ci"))
    }

    @Test
    fun `word-initial abbreviation finds country`() {
        assertEquals(listOf(Country("GB", "United Kingdom")), searchCountries(world, "uk"))
    }

    @Test
    fun `in-word subsequence finds country`() {
        // Letters in order with gaps allowed; no other fixture name contains this letter run.
        assertEquals(listOf(Country("US", "United States")), searchCountries(world, "unisttes"))
    }

    @Test
    fun `english exonym finds the endonym`() {
        // The reported bug: "turkey" matches no substring/subsequence of Türkiye
        // (the e/y swap breaks letter order); only edit distance bridges exonym->endonym.
        assertEquals(listOf(Country("TR", "Türkiye")), searchCountries(world, "turkey"))
    }

    @Test
    fun `stronger match kinds rank above weaker ones`() {
        // Crafted so each query hits two distinct tiers, with alphabetical order pointing
        // the WRONG way: exact ISO beats substring, and substring beats subsequence.
        val ranked = mapOf(
            "QY" to Country("QY", "Qystonia"),   // exact ISO code for "qy"
            "AQ" to Country("AQ", "Aqyria"),    // substring "qy"
            "BW" to Country("BW", "Botswana"),   // subsequence-only for "tsa" (no substring)
            "WT" to Country("WT", "Wotsamaria"), // substring "tsa"
        )
        assertEquals(
            listOf(Country("QY", "Qystonia"), Country("AQ", "Aqyria")),
            searchCountries(ranked, "qy"),
        )
        assertEquals(
            listOf(Country("WT", "Wotsamaria"), Country("BW", "Botswana")),
            searchCountries(ranked, "tsa"),
        )
    }

    @Test
    fun `blank query matches nothing`() {
        assertEquals(emptyList<Country>(), searchCountries(world, "   "))
    }

    @Test
    fun `results are capped at twenty`() {
        val many = (1..25).associate { i ->
            val iso = "Z%02d".format(i)
            iso to Country(iso, "Testland %02d".format(i))
        }
        assertEquals(20, searchCountries(many, "testland").size)
    }
}
