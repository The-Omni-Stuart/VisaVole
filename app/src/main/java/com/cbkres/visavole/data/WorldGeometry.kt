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
        // P3-30: a corrupt/tampered asset must degrade to an empty map, never crash startup. Every
        // structural int is bounds-checked against the remaining buffer before it sizes an array,
        // and any BufferUnderflow (a truncated asset) falls back to the empty map.
        return try {
            val width = buf.getInt().coerceIn(0, MAX_DIM)
            val height = buf.getInt().coerceIn(0, MAX_DIM)
            val count = buf.getInt()
            if (count < 0 || count > MAX_COUNTRIES) return empty(width, height)
            val countries = LinkedHashMap<String, CountryShape>(count)
            repeat(count) {
                val iso = readString(buf) ?: return empty(width, height)
                val name = readString(buf) ?: return empty(width, height)
                val ringCount = buf.getInt()
                if (ringCount < 0 || ringCount > MAX_RINGS) return empty(width, height)
                val rings = ArrayList<FloatArray>(ringCount)
                repeat(ringCount) {
                    val pointCount = buf.getInt()
                    if (pointCount < 0 || pointCount > buf.remaining() / 4) return empty(width, height)
                    val ring = FloatArray(pointCount * 2)
                    for (i in ring.indices step 2) {
                        ring[i] = (buf.getShort().toInt() and 0xFFFF) / COORD_SCALE
                        ring[i + 1] = (buf.getShort().toInt() and 0xFFFF) / COORD_SCALE
                    }
                    rings.add(ring)
                }
                countries[iso] = CountryShape(name, rings)
            }
            WorldMapData(width, height, countries)
        } catch (e: Exception) {
            empty(0, 0)
        }
    }

    private fun empty(width: Int, height: Int) = WorldMapData(width, height, emptyMap())

    private fun readString(buf: ByteBuffer): String? {
        val len = buf.getInt()
        if (len < 0 || len > buf.remaining()) return null
        val bytes = ByteArray(len)
        buf.get(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private const val MAGIC = 0x56525743
    private const val COORD_SCALE = 64f
    private const val MAX_DIM = 1_000_000
    private const val MAX_COUNTRIES = 5_000
    private const val MAX_RINGS = 100_000
}
