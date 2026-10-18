package com.cbkres.visavole.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.sql.Connection

/**
 * Pins the PERMISSIVE parsing in [JdbcRepository] (and its Android mirror [VisaRepository], which
 * shares the queries): optional columns absent from older bundled DBs, null members arrays and
 * null numeric fields must all load instead of throwing (P2-33).
 */
class JdbcRepositoryTest {

    private fun inMemory(): Connection {
        val c = java.sql.DriverManager.getConnection("jdbc:sqlite:")
        c.createStatement().use { st ->
            st.executeUpdate("CREATE TABLE countries (iso2 TEXT PRIMARY KEY, name TEXT)")
            // Older schema on purpose: no zone_name (legacy `zone` instead), no optional stay columns.
            st.executeUpdate(
                "CREATE TABLE stay_rules (id TEXT, zone TEXT, countries TEXT, window_type TEXT, " +
                    "window_days INTEGER, window_period_days INTEGER, extension INTEGER, " +
                    "multiple_entry INTEGER, nationalities TEXT, valid_from TEXT, valid_to TEXT, source TEXT, note TEXT)",
            )
            st.executeUpdate("CREATE TABLE visa_rules (passport TEXT, destination TEXT, type TEXT, days INTEGER)")
            st.executeUpdate("CREATE TABLE mobility_regimes (id TEXT, name TEXT, level TEXT, members TEXT)")
            st.executeUpdate("CREATE TABLE visa_holdings (id TEXT, name TEXT, category TEXT, issuing_country TEXT)")
            // No residence_min column: the hasColumn() branch must drop it from the SELECT.
            st.executeUpdate(
                "CREATE TABLE visa_benefits (holding TEXT, destination TEXT, type TEXT, days INTEGER, entry_type TEXT)",
            )
        }
        c.createStatement().use { st ->
            st.executeUpdate("INSERT INTO countries VALUES ('CZ','Czechia')")
            st.executeUpdate("INSERT INTO countries VALUES ('DE','Germany')")
            st.executeUpdate("INSERT INTO visa_rules VALUES ('CZ','DE','free',NULL)")
            st.executeUpdate("INSERT INTO visa_rules VALUES ('CZ','FR','visa',90)")
            st.executeUpdate("INSERT INTO mobility_regimes VALUES ('eu','EU','level','[\"AT\", \"BE\"]')")
            st.executeUpdate("INSERT INTO mobility_regimes VALUES ('empty','Empty','level',NULL)")
            st.executeUpdate("INSERT INTO visa_holdings VALUES ('h1','Holding','residence','DE')")
            st.executeUpdate("INSERT INTO visa_benefits VALUES ('h1','AT','free',NULL,'double,multiple')")
            st.executeUpdate(
                "INSERT INTO stay_rules VALUES ('r1','Schengen','[\"AT\",\"DE\"]','rolling',90,180,NULL,1,NULL,NULL,NULL,NULL,NULL)",
            )
        }
        return c
    }

    @Test
    fun missingOptionalColumnsLoadFromLegacySchema() {
        inMemory().use { c ->
            val w = JdbcRepository.load(c)
            assertEquals(2, w.countries.size)
            // Legacy `zone` column fills zoneName when `zone_name` is absent.
            val rule = w.stayRules.single()
            assertEquals("Schengen", rule.zoneName)
            assertEquals("Schengen", rule.zoneId)
            assertEquals(setOf("AT", "DE"), rule.countries)
            assertNull(rule.extensionDays)
            assertTrue(rule.multipleEntry)
            assertTrue(rule.nationalities.isEmpty())
        }
    }

    @Test
    fun nullMembersArrayYieldsEmptyMembers() {
        inMemory().use { c ->
            val w = JdbcRepository.load(c)
            assertEquals(2, w.regimes.size)
            assertEquals(listOf("AT", "BE"), w.regimes.first { it.id == "eu" }.members)
            assertTrue(w.regimes.first { it.id == "empty" }.members.isEmpty())
        }
    }

    @Test
    fun nullDaysLoadAsNullCorridor() {
        inMemory().use { c ->
            val w = JdbcRepository.load(c)
            val cz = w.baseline["CZ"]!!
            assertNull(cz.first { it.destination == "DE" }.days)
            assertEquals(90, cz.first { it.destination == "FR" }.days)
        }
    }

    @Test
    fun benefitsWithoutResidenceMinColumnLoadWithNull() {
        inMemory().use { c ->
            val w = JdbcRepository.load(c)
            val b = w.benefits["h1"]!!.single()
            assertNull(b.days)
            assertNull(b.residenceMin)
            assertEquals(setOf("double", "multiple"), b.entryTypes)
        }
    }

    @Test
    fun parseIso2ArrayIsLenientAndShared() {
        // P3-31: the Android (org.json) and JVM (string-split) repositories used to parse members
        // differently — a malformed string crashed Android but loaded leniently on the JVM, so the
        // divergence was untestable. They now share this one lenient parser, which never throws.
        assertEquals(listOf("AT", "BE"), parseIso2Array("[\"AT\", \"BE\"]"))
        assertEquals(emptyList<String>(), parseIso2Array(null))
        assertEquals(emptyList<String>(), parseIso2Array("   "))
        assertEquals(emptyList<String>(), parseIso2Array("[]"))
        // Best-effort on malformed input: no brackets, trailing comma — degrade, don't throw.
        assertEquals(listOf("AT", "BE"), parseIso2Array("AT, BE,"))
        // Literal "null" elements (org.json's getString on a JSON null) are skipped, not kept.
        assertEquals(listOf("AT"), parseIso2Array("[\"AT\", \"null\"]"))
    }
}
