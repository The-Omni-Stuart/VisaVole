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
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Loads the bundled [VisaDb] into an in-memory [WorldData].
 *
 * Android implementation (uses the platform SQLite + org.json). A JVM mirror of the same
 * queries lives in [JdbcRepository] so the SQL is exercised by unit tests.
 */
class VisaRepository(private val db: VisaDb) {

    fun loadWorld(homePassports: Set<String>, today: LocalDate = LocalDate.now(ZoneOffset.UTC)): WorldData {
        val conn = db.database
        return WorldData(
            countries = loadCountries(conn),
            baseline = loadBaseline(conn, homePassports, today),
            regimes = loadRegimes(conn),
            holdings = loadHoldings(conn),
            benefits = loadBenefits(conn),
            stayRules = loadStayRules(conn),
            visaDbMeta = loadVisaDbMeta(conn, today),
        )
    }

    /** The `meta` audit dates plus the four shared expiry probes; an absent `meta` table degrades to defaults. */
    private fun loadVisaDbMeta(c: android.database.sqlite.SQLiteDatabase, today: LocalDate): VisaDbMeta {
        val values = HashMap<String, String>()
        runCatching {
            c.rawQuery(SQL_META_FRESHNESS, null).use { cur ->
                while (cur.moveToNext()) values[cur.getString(0)] = cur.getString(1)
            }
        }
        val (from, to) = lapsingSoonWindow(today)
        val lapsed = countQuery(c, SQL_STAY_RULES_LAPSED, arrayOf(from))
        val lapsingSoon = countQuery(c, SQL_STAY_RULES_LAPSING_SOON, arrayOf(from, to))
        val lapsedCorridors = countQuery(c, SQL_GRANTS_LAPSED, arrayOf(from))
        val corridorsLapsingSoon = countQuery(c, SQL_GRANTS_LAPSING_SOON, arrayOf(from, to))
        return visaDbMeta(values, lapsed, lapsingSoon, lapsedCorridors, corridorsLapsingSoon)
    }

    private fun countQuery(c: android.database.sqlite.SQLiteDatabase, sql: String, args: Array<String>): Int =
        runCatching {
            c.rawQuery(sql, args).use { cur -> if (cur.moveToNext()) cur.getInt(0) else 0 }
        }.getOrDefault(0)

    private fun loadCountries(c: android.database.sqlite.SQLiteDatabase): Map<String, Country> {
        val out = HashMap<String, Country>()
        c.rawQuery(SQL_COUNTRIES, null).use { cur ->
            while (cur.moveToNext()) out[cur.getString(0)] = Country(cur.getString(0), cur.getString(1))
        }
        return out
    }

    private fun loadBaseline(
        c: android.database.sqlite.SQLiteDatabase,
        homePassports: Set<String>,
        today: LocalDate,
    ): Map<String, List<Corridor>> {
        if (homePassports.isEmpty()) return emptyMap()
        val out = HashMap<String, MutableList<Corridor>>()
        homePassports.toList().chunked(BASELINE_CHUNK).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            c.rawQuery(sqlBaselineChunked(placeholders), chunk.toTypedArray()).use { cur ->
                while (cur.moveToNext()) {
                    val p = cur.getString(0)
                    val days = if (cur.isNull(3)) null else cur.getInt(3)
                    val validTo = cur.getString(4)
                    if (!grantValidOn(null, validTo, today)) continue
                    out.getOrPut(p) { mutableListOf() }.add(Corridor(p, cur.getString(1), cur.getString(2), days, validTo))
                }
            }
        }
        return out
    }

    private fun loadRegimes(c: android.database.sqlite.SQLiteDatabase): List<Regime> {
        val out = mutableListOf<Regime>()
        c.rawQuery(SQL_REGIMES, null).use { cur ->
            while (cur.moveToNext()) {
                // shared lenient parser (see parseIso2Array) — identical to the JVM mirror,
                // so a malformed members string degrades instead of crashing the startup load.
                out.add(Regime(cur.getString(0), cur.getString(1), cur.getString(2), parseIso2Array(cur.getString(3))))
            }
        }
        return out
    }

    private fun loadHoldings(c: android.database.sqlite.SQLiteDatabase): Map<String, Holding> {
        val out = HashMap<String, Holding>()
        c.rawQuery(SQL_HOLDINGS, null).use { cur ->
            while (cur.moveToNext()) out[cur.getString(0)] = Holding(cur.getString(0), cur.getString(1), cur.getString(2), cur.getString(3))
        }
        return out
    }

    private fun loadBenefits(c: android.database.sqlite.SQLiteDatabase): Map<String, List<Benefit>> {
        val hasResidenceMin = hasColumn(c, "visa_benefits", "residence_min")
        val hasNationalities = hasColumn(c, "visa_benefits", "nationalities")
        val sql = sqlBenefits(hasResidenceMin, hasNationalities)
        val out = HashMap<String, MutableList<Benefit>>()
        c.rawQuery(sql, null).use { cur ->
            val natIdx = if (hasNationalities) cur.getColumnIndex("nationalities") else -1
            while (cur.moveToNext()) {
                val h = cur.getString(0)
                val days = if (cur.isNull(3)) null else cur.getInt(3)
                val entryTypes = parseEntryTypes(cur.getString(4))
                val residenceMin = if (hasResidenceMin) cur.getString(5) else null
                // nationalities is a comma list (same shape as entry_type), so the same parser applies.
                val nationalities = if (natIdx >= 0) parseEntryTypes(cur.getString(natIdx)) else emptySet()
                out.getOrPut(h) { mutableListOf() }.add(Benefit(h, cur.getString(1), cur.getString(2), days, entryTypes, residenceMin, nationalities))
            }
        }
        return out
    }

    /** True when [column] exists on [table] — optional columns may be absent from older bundled DBs. */
    private fun hasColumn(c: android.database.sqlite.SQLiteDatabase, table: String, column: String): Boolean {
        c.rawQuery("PRAGMA table_info($table)", null).use { cur ->
            while (cur.moveToNext()) if (cur.getString(1) == column) return true
        }
        return false
    }

    private fun loadStayRules(c: android.database.sqlite.SQLiteDatabase): List<StayRule> {
        val out = mutableListOf<StayRule>()
        c.rawQuery(SQL_STAY_RULES, null).use { cur ->
            val iZoneName = cur.getColumnIndex("zone_name")
            val iZone = cur.getColumnIndex("zone")
            val iCountries = cur.getColumnIndex("countries")
            val iWindowType = cur.getColumnIndex("window_type")
            val iWindowDays = cur.getColumnIndex("window_days")
            val iWindowPeriodDays = cur.getColumnIndex("window_period_days")
            val iMultipleEntry = cur.getColumnIndex("multiple_entry")
            val iNationalities = cur.getColumnIndex("nationalities")
            val iValidFrom = cur.getColumnIndex("valid_from")
            val iValidTo = cur.getColumnIndex("valid_to")
            val iSource = cur.getColumnIndex("source")
            val iNote = cur.getColumnIndex("note")
            val iId = cur.getColumnIndex("id")

            fun str(idx: Int): String? = if (idx < 0) null else cur.getString(idx)
            fun intv(idx: Int): Int? = if (idx < 0 || cur.isNull(idx)) null else cur.getInt(idx)

            while (cur.moveToNext()) {
                out.add(
                    StayRule(
                        zoneName = zoneLabel(str(iZoneName), str(iZone)),
                        countries = jsonSet(str(iCountries)),
                        windowType = str(iWindowType) ?: "",
                        windowDays = intv(iWindowDays),
                        windowPeriodDays = intv(iWindowPeriodDays),
                        multipleEntry = (intv(iMultipleEntry) ?: 0) != 0,
                        nationalities = jsonSet(str(iNationalities)),
                        note = str(iNote),
                        id = str(iId) ?: "",
                        zoneId = str(iZone),
                        validFrom = str(iValidFrom),
                        validTo = str(iValidTo),
                        source = str(iSource),
                    ),
                )
            }
        }
        return out
    }

    private fun jsonSet(json: String?): Set<String> = parseIso2Array(json).toSet()
}
