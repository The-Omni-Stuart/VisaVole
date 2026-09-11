package com.cbkres.visavole.data

import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pre-projected (Robinson) country geometry bundled as `assets/world_robinson.bin`.
 * Coordinates are already in a fixed pixel space (width x height); the UI just scales
 * that space to the available canvas size and hit-tests in the same space.
 *
 * Each ring is a flat `FloatArray` of alternating x/y values.
 */
data class CountryShape(val name: String, val rings: List<FloatArray>)

data class WorldMapData(
    val width: Int,
    val height: Int,
    val countries: Map<String, CountryShape>,
)

object WorldGeometry {
    fun load(context: Context): WorldMapData {
        val bytes = context.assets.open("world_robinson.bin").use { it.readBytes() }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        require(buf.getInt() == MAGIC) { "bad world_robinson.bin magic" }
        val width = buf.getInt()
        val height = buf.getInt()
        val count = buf.getInt()
        val countries = LinkedHashMap<String, CountryShape>(count)
        repeat(count) {
            val iso = readString(buf)
            val name = readString(buf)
            val ringCount = buf.getInt()
            val rings = ArrayList<FloatArray>(ringCount)
            repeat(ringCount) {
                val pointCount = buf.getInt()
                val ring = FloatArray(pointCount * 2)
                for (i in ring.indices step 2) {
                    ring[i] = (buf.getShort().toInt() and 0xFFFF) / COORD_SCALE
                    ring[i + 1] = (buf.getShort().toInt() and 0xFFFF) / COORD_SCALE
                }
                rings.add(ring)
            }
            countries[iso] = CountryShape(name, rings)
        }
        return WorldMapData(width, height, countries)
    }

    private fun readString(buf: ByteBuffer): String {
        val len = buf.getInt()
        val bytes = ByteArray(len)
        buf.get(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private const val MAGIC = 0x56525743
    private const val COORD_SCALE = 64f
}
