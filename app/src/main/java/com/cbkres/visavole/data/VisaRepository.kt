package com.cbkres.visavole.data

import org.json.JSONArray

/**
 * Loads the bundled [VisaDb] into an in-memory [WorldData].
 *
 * Android implementation (uses the platform SQLite + org.json). A JVM mirror of the same
 * queries lives in [JdbcRepository] so the SQL is exercised by unit tests.
 */
class VisaRepository(private val db: VisaDb) {

    fun loadWorld(homePassports: Set<String>): WorldData {
        val conn = db.database
        return WorldData(
            countries = loadCountries(conn),
            baseline = loadBaseline(conn, homePassports),
            regimes = loadRegimes(conn),
            holdings = loadHoldings(conn),
            benefits = loadBenefits(conn),
            stayRules = loadStayRules(conn),
        )
    }

    private fun loadCountries(c: android.database.sqlite.SQLiteDatabase): Map<String, Country> {
        val out = HashMap<String, Country>()
        c.rawQuery("SELECT iso2, name FROM countries", null).use { cur ->
            while (cur.moveToNext()) out[cur.getString(0)] = Country(cur.getString(0), cur.getString(1))
        }
        return out
    }

    private fun loadBaseline(c: android.database.sqlite.SQLiteDatabase, homePassports: Set<String>): Map<String, List<Corridor>> {
        if (homePassports.isEmpty()) return emptyMap()
        val out = HashMap<String, MutableList<Corridor>>()
        homePassports.toList().chunked(500).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            c.rawQuery(
                "SELECT passport, destination, type, days FROM visa_rules WHERE passport IN ($placeholders)",
                chunk.toTypedArray(),
            ).use { cur ->
                while (cur.moveToNext()) {
                    val p = cur.getString(0)
                    val days = if (cur.isNull(3)) null else cur.getInt(3)
                    out.getOrPut(p) { mutableListOf() }.add(Corridor(p, cur.getString(1), cur.getString(2), days))
                }
            }
        }
        return out
    }

    private fun loadRegimes(c: android.database.sqlite.SQLiteDatabase): List<Regime> {
        val out = mutableListOf<Regime>()
        c.rawQuery("SELECT id, name, level, members FROM mobility_regimes", null).use { cur ->
            while (cur.moveToNext()) {
                val members = mutableListOf<String>()
                cur.getString(3)?.let { json ->
                    val arr = JSONArray(json)
                    for (i in 0 until arr.length()) members.add(arr.getString(i))
                }
                out.add(Regime(cur.getString(0), cur.getString(1), cur.getString(2), members))
            }
        }
        return out
    }

    private fun loadHoldings(c: android.database.sqlite.SQLiteDatabase): Map<String, Holding> {
        val out = HashMap<String, Holding>()
        c.rawQuery("SELECT id, name, category, issuing_country FROM visa_holdings", null).use { cur ->
            while (cur.moveToNext()) out[cur.getString(0)] = Holding(cur.getString(0), cur.getString(1), cur.getString(2), cur.getString(3))
        }
        return out
    }

    private fun loadBenefits(c: android.database.sqlite.SQLiteDatabase): Map<String, List<Benefit>> {
        val hasResidenceMin = hasColumn(c, "visa_benefits", "residence_min")
        val sql = "SELECT holding, destination, type, days, entry_type" +
            if (hasResidenceMin) ", residence_min FROM visa_benefits" else " FROM visa_benefits"
        val out = HashMap<String, MutableList<Benefit>>()
        c.rawQuery(sql, null).use { cur ->
            while (cur.moveToNext()) {
                val h = cur.getString(0)
                val days = if (cur.isNull(3)) null else cur.getInt(3)
                val entryTypes = parseEntryTypes(cur.getString(4))
                val residenceMin = if (hasResidenceMin) cur.getString(5) else null
                out.getOrPut(h) { mutableListOf() }.add(Benefit(h, cur.getString(1), cur.getString(2), days, entryTypes, residenceMin))
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
        c.rawQuery("SELECT * FROM stay_rules", null).use { cur ->
            val iZoneName = cur.getColumnIndex("zone_name")
            val iZone = cur.getColumnIndex("zone")
            val iCountries = cur.getColumnIndex("countries")
            val iWindowType = cur.getColumnIndex("window_type")
            val iWindowDays = cur.getColumnIndex("window_days")
            val iWindowPeriodDays = cur.getColumnIndex("window_period_days")
            val iExtension = cur.getColumnIndex("extension")
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
                        zoneName = when {
                            iZoneName >= 0 -> str(iZoneName) ?: ""
                            iZone >= 0 -> str(iZone) ?: ""
                            else -> ""
                        },
                        countries = jsonSet(str(iCountries)),
                        windowType = str(iWindowType) ?: "",
                        windowDays = intv(iWindowDays),
                        windowPeriodDays = intv(iWindowPeriodDays),
                        multipleEntry = (intv(iMultipleEntry) ?: 0) != 0,
                        nationalities = jsonSet(str(iNationalities)),
                        note = str(iNote),
                        id = str(iId) ?: "",
                        zoneId = str(iZone),
                        extensionDays = intv(iExtension),
                        validFrom = str(iValidFrom),
                        validTo = str(iValidTo),
                        source = str(iSource),
                    ),
                )
            }
        }
        return out
    }

    private fun jsonSet(json: String?): Set<String> {
        if (json == null) return emptySet()
        val arr = JSONArray(json)
        return (0 until arr.length()).map { arr.getString(it) }.toSet()
    }

    /** Parse a comma list of entry types like `double,multiple`; null/blank = no restriction. */
    private fun parseEntryTypes(csv: String?): Set<String> {
        if (csv == null) return emptySet()
        return csv.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }
}
