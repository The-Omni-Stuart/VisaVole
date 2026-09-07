package com.example.visa_vole.data

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
        return WorldData(countries, baseline, regimes, holdings, benefits)
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
        val out = HashMap<String, MutableList<Benefit>>()
        c.createStatement().use { st ->
            st.executeQuery("SELECT holding, destination, type, days FROM visa_benefits").use { rs ->
                while (rs.next()) {
                    val h = rs.getString("holding")
                    val d = rs.getInt("days")
                    val days = if (rs.wasNull()) null else d
                    out.getOrPut(h) { mutableListOf() }.add(Benefit(h, rs.getString("destination"), rs.getString("type"), days))
                }
            }
        }
        return out
    }

    /** Parse a JSON array of ISO2 strings like `["AT", "BE"]` without a JSON library. */
    private fun iso2Array(json: String?): List<String> {
        if (json == null) return emptyList()
        val inner = json.trim().removePrefix("[").removeSuffix("]")
        if (inner.isBlank()) return emptyList()
        return inner.split(",").map { it.trim().trim('"') }.filter { it.isNotEmpty() }
    }
}
