package com.cbkres.visavole.data

import com.cbkres.visavole.domain.Benefit
import com.cbkres.visavole.domain.Corridor
import com.cbkres.visavole.domain.Country
import com.cbkres.visavole.domain.Holding
import com.cbkres.visavole.domain.Regime
import com.cbkres.visavole.domain.StayRule
import com.cbkres.visavole.domain.VisaDbMeta
import com.cbkres.visavole.domain.WorldData
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/** Chunk size for the passport-filtered baseline query; SQLite's variable limit is 999. */
internal const val BASELINE_CHUNK = 500

/**
 * JVM mirror of [VisaRepository]: loads the same [WorldData] from a SQLite file via JDBC
 * (xerial sqlite-jdbc), running the identical queries so unit tests validate the real schema.
 * With [homePassports] set it runs the same passport-filtered, chunked baseline query the
 * Android loader uses (empty set loads nothing, like Android); unset it reads the whole table
 * so unit tests see every corridor.
 * Uses plain string parsing for the members array (no org.json, which is Android-only at runtime).
 */
object JdbcRepository {

    fun load(dbFile: File, homePassports: Set<String>? = null): WorldData =
        DriverManager.getConnection("jdbc:sqlite:" + dbFile.absolutePath).use { load(it, homePassports) }

    fun load(conn: Connection, homePassports: Set<String>? = null): WorldData {
        val countries = loadCountries(conn)
        val baseline = loadBaseline(conn, homePassports)
        val regimes = loadRegimes(conn)
        val holdings = loadHoldings(conn)
        val benefits = loadBenefits(conn)
        val stayRules = loadStayRules(conn)
        return WorldData(countries, baseline, regimes, holdings, benefits, stayRules, loadVisaDbMeta(conn))
    }

    /** The `meta` audit dates plus the two shared expiry probes; an absent `meta` table degrades to defaults. */
    private fun loadVisaDbMeta(c: Connection): VisaDbMeta {
        val values = HashMap<String, String>()
        runCatching {
            c.createStatement().use { st ->
                st.executeQuery(SQL_META_FRESHNESS).use { rs ->
                    while (rs.next()) values[rs.getString("key")] = rs.getString("value")
                }
            }
        }
        val (from, to) = lapsingSoonWindow()
        val lapsed = countQuery(c, SQL_STAY_RULES_LAPSED, listOf(from))
        val lapsingSoon = countQuery(c, SQL_STAY_RULES_LAPSING_SOON, listOf(from, to))
        return visaDbMeta(values, lapsed, lapsingSoon)
    }

    private fun countQuery(c: Connection, sql: String, args: List<String>): Int = runCatching {
        c.prepareStatement(sql).use { ps ->
            args.forEachIndexed { i, a -> ps.setString(i + 1, a) }
            ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }
    }.getOrDefault(0)

    private fun loadCountries(c: Connection): Map<String, Country> {
        val out = HashMap<String, Country>()
        c.createStatement().use { st ->
            st.executeQuery("SELECT iso2, name FROM countries").use { rs ->
                while (rs.next()) out[rs.getString("iso2")] = Country(rs.getString("iso2"), rs.getString("name"))
            }
        }
        return out
    }

    private fun loadBaseline(c: Connection, homePassports: Set<String>?): Map<String, List<Corridor>> {
        if (homePassports != null && homePassports.isEmpty()) return emptyMap()
        val out = HashMap<String, MutableList<Corridor>>()
        // Unfiltered reads the whole table; filtered runs the same chunked IN query as Android.
        val queries: List<Pair<String, List<String>>> = if (homePassports == null) {
            listOf("SELECT passport, destination, type, days FROM visa_rules" to emptyList())
        } else {
            homePassports.sorted().chunked(BASELINE_CHUNK).map { chunk ->
                val placeholders = chunk.joinToString(",") { "?" }
                "SELECT passport, destination, type, days FROM visa_rules WHERE passport IN ($placeholders)" to chunk
            }
        }
        for ((sql, args) in queries) {
            c.prepareStatement(sql).use { ps ->
                args.forEachIndexed { i, a -> ps.setString(i + 1, a) }
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val p = rs.getString("passport")
                        val d = rs.getInt("days")
                        val days = if (rs.wasNull()) null else d
                        out.getOrPut(p) { mutableListOf() }.add(Corridor(p, rs.getString("destination"), rs.getString("type"), days))
                    }
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
                    out.add(Regime(rs.getString("id"), rs.getString("name"), rs.getString("level"), parseIso2Array(rs.getString("members"))))
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
        val hasNationalities = hasColumn(c, "visa_benefits", "nationalities")
        val out = HashMap<String, MutableList<Benefit>>()
        c.createStatement().use { st ->
            val sql = buildString {
                append("SELECT holding, destination, type, days, entry_type")
                if (hasResidenceMin) append(", residence_min")
                if (hasNationalities) append(", nationalities")
                append(" FROM visa_benefits")
            }
            st.executeQuery(sql).use { rs ->
                while (rs.next()) {
                    val h = rs.getString("holding")
                    val d = rs.getInt("days")
                    val days = if (rs.wasNull()) null else d
                    val residenceMin = if (hasResidenceMin) rs.getString("residence_min") else null
                    // nationalities is a comma list (same shape as entry_type), so the same parser applies.
                    val nationalities = if (hasNationalities) parseEntryTypes(rs.getString("nationalities")) else emptySet()
                    out.getOrPut(h) { mutableListOf() }
                        .add(Benefit(h, rs.getString("destination"), rs.getString("type"), days, parseEntryTypes(rs.getString("entry_type")), residenceMin, nationalities))
                }
            }
        }
        return out
    }

    /** True when [column] exists on [table] — optional columns may be absent from older bundled DBs. */
    private fun hasColumn(c: Connection, table: String, column: String): Boolean {
        c.createStatement().use { st ->
            st.executeQuery("PRAGMA table_info($table)").use { rs ->
                while (rs.next()) if (rs.getString(2) == column) return true
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
                            countries = parseIso2Array(str("countries")).toSet(),
                            windowType = str("window_type") ?: "",
                            windowDays = intv("window_days"),
                            windowPeriodDays = intv("window_period_days"),
                            multipleEntry = (intv("multiple_entry") ?: 0) != 0,
                            nationalities = parseIso2Array(str("nationalities")).toSet(),
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

    /** Parse a comma list of entry types like `double,multiple`; null/blank = no restriction. */
    private fun parseEntryTypes(csv: String?): Set<String> {
        if (csv == null) return emptySet()
        return csv.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }
}
