package com.cbkres.visavole.data

import com.cbkres.visavole.domain.VisaDbMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.sql.DriverManager
import java.time.LocalDate

/** 52 corridors carry a `valid_to`; before the loaders read it the app applied an expired grant forever. */
class CorridorExpiryTest {

    private val today = LocalDate.of(2026, 11, 19)

    private fun db(corridorValidTos: List<String?>): java.sql.Connection {
        val c = java.sql.DriverManager.getConnection("jdbc:sqlite:")
        c.createStatement().use { st ->
            st.executeUpdate("CREATE TABLE countries (iso2 TEXT PRIMARY KEY, name TEXT)")
            st.executeUpdate("CREATE TABLE stay_rules (id TEXT, countries TEXT, window_type TEXT, valid_to TEXT)")
            st.executeUpdate("CREATE TABLE visa_rules (passport TEXT, destination TEXT, type TEXT, days INTEGER, valid_to TEXT)")
            st.executeUpdate("CREATE TABLE mobility_regimes (id TEXT, name TEXT, short_name TEXT, level TEXT, members TEXT)")
            st.executeUpdate("CREATE TABLE visa_holdings (id TEXT, name TEXT, category TEXT, issuing_country TEXT)")
            st.executeUpdate("CREATE TABLE visa_benefits (holding TEXT, destination TEXT, type TEXT, days INTEGER, entry_type TEXT)")
            corridorValidTos.forEachIndexed { i, validTo ->
                st.executeUpdate(
                    "INSERT INTO visa_rules VALUES ('P$i','DE','free',90,${validTo?.let { "'$it'" } ?: "NULL"})",
                )
            }
        }
        return c
    }

    private fun plusDays(n: Long): String = today.plusDays(n).toString()

    @Test
    fun aLapsedGrantIsNotOfferedAsAccess() {
        db(listOf("2026-11-18", today.toString(), "2026-11-20", null)).use { c ->
            val baseline = JdbcRepository.load(c, today = today).baseline
            assertNull("yesterday's expiry must not grant anything", baseline["P0"])
            assertEquals("an expiry of today still stands", 1, baseline["P1"]?.size)
            assertEquals("a future expiry grants normally", 1, baseline["P2"]?.size)
            assertEquals("an open-ended grant grants normally", 1, baseline["P3"]?.size)
        }
    }

    @Test
    fun theExpirySurvivesToTheCorridorSoTheDayCountCannotOutliveIt() {
        db(listOf("2026-12-31")).use { c ->
            assertEquals("2026-12-31", JdbcRepository.load(c, today = today).baseline["P0"]?.single()?.validTo)
        }
    }

    @Test
    fun corridorExpiryIsCountedWithTheSameBoundsAsRuleExpiry() {
        db(listOf("2020-01-01", null, today.toString(), plusDays(1), plusDays(30), plusDays(31))).use { c ->
            val meta = JdbcRepository.load(c, today = today).visaDbMeta
            assertEquals(1, meta.lapsedCorridors)
            assertEquals(3, meta.corridorsLapsingSoon)
        }
    }

    @Test
    fun absentProbesReadAsZeroRatherThanAnError() {
        db(emptyList()).use { c ->
            assertEquals(VisaDbMeta(), JdbcRepository.load(c).visaDbMeta)
        }
    }

    @Test
    fun noBundledCorridorCarriesALapsedExpiry() {
        val asset = File("src/main/assets/visa_data.db")
        // The upstream guarantee is relative to the asset's build date: a corridor that was
        // future at build time may lapse while the asset sits in the repo, and the app is
        // built to display those. Probing against now() turns the suite red on that date.
        val generated = LocalDate.parse(JdbcRepository.load(asset).visaDbMeta.generated)
        val meta = JdbcRepository.load(asset, today = generated).visaDbMeta
        assertEquals("the upstream build refuses lapsed rows, so none may reach the asset", 0, meta.lapsedCorridors)
        java.sql.DriverManager.getConnection("jdbc:sqlite:" + asset.absolutePath).use { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT COUNT(*) FROM visa_rules WHERE valid_to IS NOT NULL AND valid_to != ''").use { rs ->
                    rs.next()
                    assertTrue("the probe is meaningless if no bundled corridor expires: ${rs.getInt(1)}", rs.getInt(1) > 0)
                }
            }
        }
    }
}
