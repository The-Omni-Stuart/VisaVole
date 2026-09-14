package com.cbkres.visavole.data

import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * JVM mirror of [VisaRepository]: loads the same [WorldData] from a SQLite file via JDBC
 * (xerial sqlite-jdbc), running the identical queries so unit tests validate the real schema.
 * Uses plain string parsing for the members array (no org.json, which is Android-only at runtime).
 */
object JdbcRepository {

    fun load(dbFile: File): WorldData =
        DriverManager.getConnection("jdbc:sqlite:" + dbFile.absolutePath).use { load(it) }

    fun load(conn: Connection): WorldData {
        val countries = loadCountries(conn)
        val baseline = loadBaseline(conn)
        val regimes = loadRegimes(conn)
        val holdings = loadHoldings(conn)
        val benefits = loadBenefits(conn)
        val stayRules = loadStayRules(conn)
        return WorldData(countries, baseline, regimes, holdings, benefits, stayRules)
    }

    private fun loadCountries(c: Connection): Map<String, Country> {
        val out = HashMap<String, Country>()
        c.createStatement().use { st ->
            st.executeQuery("SELECT iso2, name FROM countries").use { rs ->
                while (rs.next()) out[rs.getString("iso2")] = Country(rs.getString("iso2"), rs.getString("name"))
            }
        }
        return out
    }

    private fun loadBaseline(c: Connection): Map<String, List<Corridor>> {
        val out = HashMap<String, MutableList<Corridor>>()
        c.createStatement().use { st ->
            st.executeQuery("SELECT passport, destination, type, days FROM visa_rules").use { rs ->
                while (rs.next()) {
                    val p = rs.getString("passport")
                    val d = rs.getInt("days")
                    val days = if (rs.wasNull()) null else d
                    out.getOrPut(p) { mutableListOf() }.add(Corridor(p, rs.getString("destination"), rs.getString("type"), days))
                }
            }
        }
        return out
    }

    private fun loadRegimes(c: Connection): List<Regime> {
        val out = mutableListOf<Regime>()
        c.createStatement().use { st ->
            st.executeQuery("SELECT id, name, level, members FROM mobility_regimes").use { rs ->
                while (rs.next()) {
                    out.add(Regime(rs.getString("id"), rs.getString("name"), rs.getString("level"), iso2Array(rs.getString("members"))))
                }
            }
        }
        return out
    }

    private fun loadHoldings(c: Connection): Map<String, Holding> {
        val out = HashMap<String, Holding>()
        c.createStatement().use { st ->
            st.executeQuery("SELECT id, name, category, issuing_country FROM visa_holdings").use { rs ->
                while (rs.next()) out[rs.getString("id")] = Holding(rs.getString("id"), rs.getString("name"), rs.getString("category"), rs.getString("issuing_country"))
            }
        }
        return out
    }

    private fun loadBenefits(c: Connection): Map<String, List<Benefit>> {
        val hasResidenceMin = hasColumn(c, "visa_benefits", "residence_min")
        val out = HashMap<String, MutableList<Benefit>>()
        c.createStatement().use { st ->
            val sql = "SELECT holding, destination, type, days, entry_type" +
                if (hasResidenceMin) ", residence_min FROM visa_benefits" else " FROM visa_benefits"
            st.executeQuery(sql).use { rs ->
                while (rs.next()) {
                    val h = rs.getString("holding")
                    val d = rs.getInt("days")
                    val days = if (rs.wasNull()) null else d
                    val residenceMin = if (hasResidenceMin) rs.getString("residence_min") else null
                    out.getOrPut(h) { mutableListOf() }
                        .add(Benefit(h, rs.getString("destination"), rs.getString("type"), days, parseEntryTypes(rs.getString("entry_type")), residenceMin))
                }
            }
        }
        return out
    }

    /** True when [column] exists on [table] — optional columns may be absent from older bundled DBs. */
    private fun hasColumn(c: Connection, table: String, column: String): Boolean {
        c.createStatement().use { st ->
            st.executeQuery("PRAGMA table_info($table)").use { rs ->
                while (rs.next()) if (rs.getString(1) == column) return true
            }
        }
        return false
    }

    private fun loadStayRules(c: Connection): List<StayRule> {
        val out = mutableListOf<StayRule>()
        c.createStatement().use { st ->
            st.executeQuery("SELECT * FROM stay_rules").use { rs ->
                val meta = rs.metaData
                val indices = HashMap<String, Int>()
                for (i in 1..meta.columnCount) indices[meta.getColumnLabel(i).lowercase()] = i

                fun str(col: String): String? {
                    val i = indices[col] ?: return null
                    val v = rs.getString(i)
                    return if (rs.wasNull()) null else v
                }
                fun intv(col: String): Int? {
                    val i = indices[col] ?: return null
                    val v = rs.getInt(i)
                    return if (rs.wasNull()) null else v
                }

                while (rs.next()) {
                    out.add(
                        StayRule(
                            zoneName = str("zone_name") ?: str("zone") ?: "",
                            countries = iso2Array(str("countries")).toSet(),
                            windowType = str("window_type") ?: "",
                            windowDays = intv("window_days"),
                            windowPeriodDays = intv("window_period_days"),
                            multipleEntry = (intv("multiple_entry") ?: 0) != 0,
                            nationalities = iso2Array(str("nationalities")).toSet(),
                            note = str("note"),
                            id = str("id") ?: "",
                            zoneId = str("zone"),
                            extensionDays = intv("extension"),
                            validFrom = str("valid_from"),
                            validTo = str("valid_to"),
                            source = str("source"),
                        ),
                    )
                }
            }
        }
        return out
    }

    private fun intOrNull(rs: java.sql.ResultSet, col: String): Int? {
        val v = rs.getInt(col)
        return if (rs.wasNull()) null else v
    }

    /** Parse a JSON array of ISO2 strings like `["AT", "BE"]` without a JSON library. */
    private fun iso2Array(json: String?): List<String> {
        if (json == null) return emptyList()
        val inner = json.trim().removePrefix("[").removeSuffix("]")
        if (inner.isBlank()) return emptyList()
        return inner.split(",").map { it.trim().trim('"') }.filter { it.isNotEmpty() }
    }

    /** Parse a comma list of entry types like `double,multiple`; null/blank = no restriction. */
    private fun parseEntryTypes(csv: String?): Set<String> {
        if (csv == null) return emptySet()
        return csv.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }
}
