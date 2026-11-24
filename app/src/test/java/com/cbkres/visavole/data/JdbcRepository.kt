package com.cbkres.visavole.data

import com.cbkres.visavole.domain.Benefit
import com.cbkres.visavole.domain.Corridor
import com.cbkres.visavole.domain.Country
import com.cbkres.visavole.domain.Holding
import com.cbkres.visavole.domain.Regime
import com.cbkres.visavole.domain.StayRule
import com.cbkres.visavole.domain.VisaDbMeta
import com.cbkres.visavole.domain.grantValidOn
import com.cbkres.visavole.domain.WorldData
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * JVM mirror of [VisaRepository]: loads the same [WorldData] from a SQLite file via JDBC
 * (xerial sqlite-jdbc), running the identical queries so unit tests validate the real schema.
 * With [homePassports] set it runs the same passport-filtered, chunked baseline query the
 * Android loader uses (empty set loads nothing, like Android); unset it reads the whole table
 * so unit tests see every corridor.
 * Uses plain string parsing for the members array (no org.json, which is Android-only at runtime).
 */
object JdbcRepository {

    fun load(dbFile: File, homePassports: Set<String>? = null, today: LocalDate = LocalDate.now(ZoneOffset.UTC)): WorldData =
        DriverManager.getConnection("jdbc:sqlite:" + dbFile.absolutePath).use { load(it, homePassports, today) }

    fun load(conn: Connection, homePassports: Set<String>? = null, today: LocalDate = LocalDate.now(ZoneOffset.UTC)): WorldData {
        val countries = loadCountries(conn)
        val baseline = loadBaseline(conn, homePassports, today)
        val regimes = loadRegimes(conn)
        val holdings = loadHoldings(conn)
        val benefits = loadBenefits(conn)
        val stayRules = loadStayRules(conn)
        return WorldData(countries, baseline, regimes, holdings, benefits, stayRules, loadVisaDbMeta(conn, today))
    }

    /** The `meta` audit dates plus the four shared expiry probes; an absent `meta` table degrades to defaults. */
    private fun loadVisaDbMeta(c: Connection, today: LocalDate): VisaDbMeta {
        val values = HashMap<String, String>()
        runCatching {
            c.createStatement().use { st ->
                st.executeQuery(SQL_META_FRESHNESS).use { rs ->
                    while (rs.next()) values[rs.getString("key")] = rs.getString("value")
                }
            }
        }
        val (from, to) = lapsingSoonWindow(today)
        val lapsed = countQuery(c, SQL_STAY_RULES_LAPSED, listOf(from))
        val lapsingSoon = countQuery(c, SQL_STAY_RULES_LAPSING_SOON, listOf(from, to))
        val lapsedCorridors = countQuery(c, SQL_GRANTS_LAPSED, listOf(from))
        val corridorsLapsingSoon = countQuery(c, SQL_GRANTS_LAPSING_SOON, listOf(from, to))
        return visaDbMeta(values, lapsed, lapsingSoon, lapsedCorridors, corridorsLapsingSoon)
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
            st.executeQuery(SQL_COUNTRIES).use { rs ->
                while (rs.next()) out[rs.getString("iso2")] = Country(rs.getString("iso2"), rs.getString("name"))
            }
        }
        return out
    }

    private fun loadBaseline(c: Connection, homePassports: Set<String>?, today: LocalDate): Map<String, List<Corridor>> {
        if (homePassports != null && homePassports.isEmpty()) return emptyMap()
        val out = HashMap<String, MutableList<Corridor>>()
        // Unfiltered reads the whole table; filtered runs the same chunked IN query as Android.
        val queries: List<Pair<String, List<String>>> = if (homePassports == null) {
            listOf(SQL_BASELINE_ALL to emptyList())
        } else {
            homePassports.sorted().chunked(BASELINE_CHUNK).map { chunk ->
                val placeholders = chunk.joinToString(",") { "?" }
                sqlBaselineChunked(placeholders) to chunk
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
                        val validTo = rs.getString("valid_to")
                        if (!grantValidOn(null, validTo, today)) continue
                        out.getOrPut(p) { mutableListOf() }
                            .add(Corridor(p, rs.getString("destination"), rs.getString("type"), days, validTo))
                    }
                }
            }
        }
        return out
    }

    private fun loadRegimes(c: Connection): List<Regime> {
        val out = mutableListOf<Regime>()
        c.createStatement().use { st ->
            st.executeQuery(SQL_REGIMES).use { rs ->
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
            st.executeQuery(SQL_HOLDINGS).use { rs ->
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
            val sql = sqlBenefits(hasResidenceMin, hasNationalities)
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
            st.executeQuery(SQL_STAY_RULES).use { rs ->
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
                            zoneName = zoneLabel(str("zone_name"), str("zone")),
                            countries = parseIso2Array(str("countries")).toSet(),
                            windowType = str("window_type") ?: "",
                            windowDays = intv("window_days"),
                            windowPeriodDays = intv("window_period_days"),
                            multipleEntry = (intv("multiple_entry") ?: 0) != 0,
                            nationalities = parseIso2Array(str("nationalities")).toSet(),
                            note = str("note"),
                            id = str("id") ?: "",
                            zoneId = str("zone"),
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
}
