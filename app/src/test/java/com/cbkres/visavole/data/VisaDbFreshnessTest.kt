package com.cbkres.visavole.data

import com.cbkres.visavole.domain.VisaDbMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.sql.DriverManager
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The app-side tripwire for the upstream build guard: a lapsed `valid_to` must never ship in the
 * bundled DB, and the loaders must read the `meta` audit dates and expiry probes back correctly.
 */
class VisaDbFreshnessTest {

    private fun bundledDb(): File = File("src/main/assets/visa_data.db")

    // The upstream guarantee is relative to the asset's build date, not the run date: rows
    // that were future at build time may lapse while the asset sits in the repo, and the app
    // is built to display those. Probing against now() turns the suite red on that date.
    private fun buildDate(): String = JdbcRepository.load(bundledDb()).visaDbMeta.generated

    @Test
    fun noBundledStayRuleCarriesALapsedExpiry() {
        val today = buildDate()
        DriverManager.getConnection("jdbc:sqlite:" + bundledDb().absolutePath).use { c ->
            c.prepareStatement("SELECT id, valid_to FROM stay_rules WHERE valid_to IS NOT NULL AND valid_to < ?").use { ps ->
                ps.setString(1, today)
                ps.executeQuery().use { rs ->
                    val lapsed = buildString {
                        while (rs.next()) append(rs.getString("id")).append('=').append(rs.getString("valid_to")).append(' ')
                    }
                    assertTrue("stay_rules rows lapsed before $today: $lapsed", lapsed.isEmpty())
                }
            }
        }
    }

    @Test
    fun bundledDbReadsBackFreshnessMeta() {
        // The audit dates are stamped by the upstream build, so pin the shape and the ordering rather
        // than one build's calendar — literal dates fail the suite on every legitimate asset refresh.
        val meta = JdbcRepository.load(bundledDb(), today = LocalDate.parse(buildDate())).visaDbMeta
        val generated = LocalDate.parse(meta.generated)
        val verified = LocalDate.parse(meta.rulesVerifiedAt)
        val review = LocalDate.parse(meta.nextReview)
        assertTrue("next review $review must come after the verification $verified", review.isAfter(verified))
        assertTrue("rules verified $verified cannot post-date the build $generated", !verified.isAfter(generated))
        assertEquals(0, meta.lapsedRules)
        assertEquals(0, meta.lapsingSoon)
    }

    @Test
    fun expiryProbesCountLapsedAndLapsingSoonWithExactBounds() {
        inMemoryDb(validTos = listOf("2020-01-01", null, today(), plusDays(1), plusDays(30), plusDays(31))).use { c ->
            val meta = JdbcRepository.load(c).visaDbMeta
            assertEquals(1, meta.lapsedRules)
            // today, +1 and +30 sit inside the inclusive window; +31 is out; NULL is never counted.
            assertEquals(3, meta.lapsingSoon)
        }
    }

    @Test
    fun metaDefaultsWhenTheTableIsAbsent() {
        inMemoryDb(validTos = listOf("2020-01-01")).use { c ->
            assertEquals(VisaDbMeta("", "", "", lapsedRules = 1), JdbcRepository.load(c).visaDbMeta)
        }
    }

    private fun today(): String = LocalDate.now(ZoneOffset.UTC).toString()

    private fun plusDays(n: Long): String = LocalDate.now(ZoneOffset.UTC).plusDays(n).toString()

    /** Full loader schema (the six required tables, never a `meta` table) with given valid_to values. */
    private fun inMemoryDb(validTos: List<String?>, corridorValidTos: List<String?> = emptyList()): java.sql.Connection {
        val c = DriverManager.getConnection("jdbc:sqlite:")
        c.createStatement().use { st ->
            st.executeUpdate("CREATE TABLE countries (iso2 TEXT PRIMARY KEY, name TEXT)")
            st.executeUpdate("CREATE TABLE stay_rules (id TEXT, countries TEXT, window_type TEXT, valid_to TEXT)")
            st.executeUpdate("CREATE TABLE visa_rules (passport TEXT, destination TEXT, type TEXT, days INTEGER, valid_to TEXT)")
            st.executeUpdate("CREATE TABLE mobility_regimes (id TEXT, name TEXT, short_name TEXT, level TEXT, members TEXT)")
            st.executeUpdate("CREATE TABLE visa_holdings (id TEXT, name TEXT, category TEXT, issuing_country TEXT)")
            st.executeUpdate("CREATE TABLE visa_benefits (holding TEXT, destination TEXT, type TEXT, days INTEGER, entry_type TEXT)")
            validTos.forEachIndexed { i, validTo ->
                st.executeUpdate("INSERT INTO stay_rules VALUES ('r$i','[\"XX\"]','per-entry',${validTo?.let { "'$it'" } ?: "NULL"})")
            }
            corridorValidTos.forEachIndexed { i, validTo ->
                st.executeUpdate("INSERT INTO visa_rules VALUES ('P$i','DE','free',90,${validTo?.let { "'$it'" } ?: "NULL"})")
            }
        }
        return c
    }
}
