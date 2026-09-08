package com.example.visa_vole.data

import org.json.JSONArray

/**
 * Loads the bundled [VisaDb] into an in-memory [WorldData].
 *
 * Android implementation (uses the platform SQLite + org.json). A JVM mirror of the same
 * queries lives in [JdbcRepository] so the SQL is exercised by unit tests.
 */
class VisaRepository(private val db: VisaDb) {

    fun loadWorld(): WorldData {
        val conn = db.database
        return WorldData(
            countries = loadCountries(conn),
            baseline = loadBaseline(conn),
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

    private fun loadBaseline(c: android.database.sqlite.SQLiteDatabase): Map<String, List<Corridor>> {
        val out = HashMap<String, MutableList<Corridor>>()
        c.rawQuery("SELECT passport, destination, type, days FROM visa_rules", null).use { cur ->
            while (cur.moveToNext()) {
                val p = cur.getString(0)
                val days = if (cur.isNull(3)) null else cur.getInt(3)
                out.getOrPut(p) { mutableListOf() }.add(Corridor(p, cur.getString(1), cur.getString(2), days))
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
        val out = HashMap<String, MutableList<Benefit>>()
        c.rawQuery("SELECT holding, destination, type, days FROM visa_benefits", null).use { cur ->
            while (cur.moveToNext()) {
                val h = cur.getString(0)
                val days = if (cur.isNull(3)) null else cur.getInt(3)
                out.getOrPut(h) { mutableListOf() }.add(Benefit(h, cur.getString(1), cur.getString(2), days))
            }
        }
        return out
    }

    private fun loadStayRules(c: android.database.sqlite.SQLiteDatabase): List<StayRule> {
        val out = mutableListOf<StayRule>()
        c.rawQuery(
            "SELECT zone_name, countries, window_type, window_days, window_period_days, multiple_entry, nationalities, note FROM stay_rules",
            null,
        ).use { cur ->
            while (cur.moveToNext()) {
                out.add(
                    StayRule(
                        zoneName = cur.getString(0) ?: "",
                        countries = jsonSet(cur.getString(1)),
                        windowType = cur.getString(2) ?: "",
                        windowDays = if (cur.isNull(3)) null else cur.getInt(3),
                        windowPeriodDays = if (cur.isNull(4)) null else cur.getInt(4),
                        multipleEntry = cur.getInt(5) != 0,
                        nationalities = jsonSet(cur.getString(6)),
                        note = cur.getString(7),
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
}
