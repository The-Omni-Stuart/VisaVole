package com.cbkres.visavole.data

import android.content.Context
import androidx.compose.ui.geometry.Offset
import org.json.JSONObject

/**
 * Pre-projected (Robinson) country geometry bundled as `assets/world_robinson.json`.
 * Coordinates are already in a fixed pixel space (width x height); the UI just scales
 * that space to the available canvas size and hit-tests in the same space.
 */
data class CountryShape(val name: String, val rings: List<List<Offset>>)

data class WorldMapData(
    val width: Int,
    val height: Int,
    val countries: Map<String, CountryShape>,
)

object WorldGeometry {
    fun load(context: Context): WorldMapData {
        val text = context.assets.open("world_robinson.json").bufferedReader().use { it.readText() }
        val root = JSONObject(text)
        val width = root.getInt("width")
        val height = root.getInt("height")
        val countries = LinkedHashMap<String, CountryShape>()
        val cObj = root.getJSONObject("countries")
        val names = cObj.keys()
        while (names.hasNext()) {
            val iso = names.next()
            val o = cObj.getJSONObject(iso)
            val name = o.getString("name")
            val rings = ArrayList<List<Offset>>()
            val rArr = o.getJSONArray("rings")
            for (i in 0 until rArr.length()) {
                val ringArr = rArr.getJSONArray(i)
                val ring = ArrayList<Offset>(ringArr.length())
                for (j in 0 until ringArr.length()) {
                    val p = ringArr.getJSONArray(j)
                    ring.add(Offset(p.getDouble(0).toFloat(), p.getDouble(1).toFloat()))
                }
                rings.add(ring)
            }
            countries[iso] = CountryShape(name, rings)
        }
        return WorldMapData(width, height, countries)
    }
}
