package com.cbkres.visavole.ui.screens.map

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import com.cbkres.visavole.data.WorldMapData
import kotlin.math.sign
import kotlin.math.abs
import kotlin.math.hypot

// Geometric centre, so an exact world fit keeps equal margins on both map edges.
internal const val DEFAULT_CENTER_X_FRACTION = 0.5f

/**
 * Per-country focus margin as a fraction of the world width (default 7%). A small mainland would
 * otherwise pull a nearby-but-separate territory into the tap-to-zoom focus: the UK's Gibraltar sits
 * only ~4% of the world width south of Great Britain, while its immediate islands (Hebrides, Orkney,
 * Shetland) are all within ~2%. Tightening GB to 3% keeps those islands and drops Gibraltar, so
 * tapping the UK centres on the home islands instead of zooming out over the Mediterranean.
 */
internal val FOCUS_MARGIN_OVERRIDE: Map<String, Float> = mapOf("GB" to 0.03f)

// Public (not internal) so AccessViewModel can pre-build these on the IO thread and ship them
// via the public AppState.Ready; they are pure geometry data.
data class IsoShape(
    val iso: String,
    val rings: List<FloatArray>,
    val path: Path,
    val area: Float,
    val bbox: BBox,
    val labelAnchor: Offset? = null,
)

internal data class DrawShape(
    val iso: String,
    val path: Path,
    val bbox: BBox,
    val fill: Color,
    val labelAnchor: Offset? = null,
)

// Public (see IsoShape) — carried by IsoShape/Focus on the public AppState.Ready.
data class BBox(
    val minX: Float,
    val minY: Float,
    val maxX: Float,
    val maxY: Float,
) {
    val cx get() = (minX + maxX) / 2f
    val cy get() = (minY + maxY) / 2f
    val w get() = (maxX - minX).coerceAtLeast(1f)
    val h get() = (maxY - minY).coerceAtLeast(1f)

    fun intersectsViewport(left: Float, top: Float, right: Float, bottom: Float): Boolean =
        maxX >= left && minX <= right && maxY >= top && minY <= bottom

    fun contains(x: Float, y: Float): Boolean =
        x >= minX && x <= maxX && y >= minY && y <= maxY
}

/** The world point to centre on, plus the bbox used to choose the zoom level for it.
 *  Public (see IsoShape) — carried on the public AppState.Ready. */
data class Focus(val point: Offset, val bbox: BBox)

internal fun buildShapes(geometry: WorldMapData): List<IsoShape> =
    geometry.countries.map { (iso, shape) ->
        val path = Path()
        var area = 0f
        val reverse = ringsToReverse(shape.rings)
        for ((index, ring) in shape.rings.withIndex()) {
            val backwards = index in reverse
            var i = if (backwards) ring.size - 2 else 0
            val step = if (backwards) -2 else 2
            var first = true
            while (i >= 0 && i < ring.size) {
                val x = ring[i]
                val y = ring[i + 1]
                if (first) path.moveTo(x, y) else path.lineTo(x, y)
                first = false
                i += step
            }
            path.close()
            area += polygonArea(ring)
        }
        IsoShape(iso, shape.rings, path, area, bboxOf(shape.rings), interiorLabelPoint(shape.rings))
    }

/** True when a label rectangle clears every label already drawn. */
internal fun labelFits(placed: List<Rect>, candidate: Rect): Boolean =
    placed.none {
        candidate.left < it.right && candidate.right > it.left &&
            candidate.top < it.bottom && candidate.bottom > it.top
    }

/** An interior point of the largest landmass, furthest from its outline — a centroid can land in a
 *  neighbour. Near-ties break toward the bbox centre so a wide low strip labels mid-width. */
internal fun interiorLabelPoint(rings: List<FloatArray>, steps: Int = 21): Offset? {
    val ring = rings.maxByOrNull(::polygonArea) ?: return null
    val b = bboxOf(listOf(ring))
    val cx = (b.minX + b.maxX) / 2f
    val cy = (b.minY + b.maxY) / 2f
    var best: Offset? = null
    var bestClear = -1f
    var bestCentred = Float.MAX_VALUE
    for (i in 1 until steps) {
        for (j in 1 until steps) {
            val x = b.minX + (b.maxX - b.minX) * i / steps
            val y = b.minY + (b.maxY - b.minY) * j / steps
            if (!pointInRing(ring, x, y)) continue
            var clear = Float.MAX_VALUE
            for (k in ring.indices step 2) {
                val dx = ring[k] - x
                val dy = ring[k + 1] - y
                val d = dx * dx + dy * dy
                if (d < clear) clear = d
            }
            val centred = (x - cx) * (x - cx) + (y - cy) * (y - cy)
            when {
                best == null || clear > bestClear * 1.01f -> {
                    bestClear = clear; bestCentred = centred; best = Offset(x, y)
                }
                // Within ~1% of the best clearance: the more central point wins.
                clear >= bestClear * 0.99f && centred < bestCentred -> {
                    bestCentred = centred; best = Offset(x, y)
                }
            }
        }
    }
    return best
}

internal data class PlacedMapLabel(val iso: String, val text: String, val x: Float, val y: Float, val rect: Rect, val bbox: BBox)

internal fun rectsOverlap(a: Rect, b: Rect): Boolean =
    a.left < b.right && a.right > b.left && a.top < b.bottom && a.bottom > b.top

/** A clearly larger host keeps its full name; only near-equal neighbours may trade down. */
internal fun canDowngradeFor(host: PlacedMapLabel, x: Float, y: Float, candidateWidth: Float): Boolean =
    host.text != host.iso && !host.bbox.contains(x, y) && host.bbox.w <= candidateWidth * 1.5f

/** Nothing before 1.5x, ISO codes after, full names only once zoomed further and they fit. */
internal fun mapLabel(iso: String, name: String, widthPx: Float, zoom: Float): String? = when {
    zoom < 1.5f -> null
    zoom >= 2.5f && widthPx >= 160f -> name
    widthPx >= 48f -> iso
    else -> null
}

/** Indices of enclave rings whose winding matches the surrounding ring, so they must be reversed. */
internal fun ringsToReverse(rings: List<FloatArray>): Set<Int> {
    val signed = rings.map(::signedArea)
    return rings.indices.filterTo(HashSet()) { i ->
        rings.indices.any { j -> j != i && ringInsideRing(rings[i], rings[j]) && signed[i] * signed[j] > 0f }
    }
}

private fun ringInsideRing(inner: FloatArray, outer: FloatArray): Boolean {
    val a = bboxOf(listOf(inner))
    val b = bboxOf(listOf(outer))
    return a.minX >= b.minX && a.maxX <= b.maxX && a.minY >= b.minY && a.maxY <= b.maxY &&
        pointInRing(outer, inner[0], inner[1])
}

private fun signedArea(ring: FloatArray): Float {
    var a = 0f
    var j = ring.size - 2
    for (i in ring.indices step 2) {
        a += (ring[j] + ring[i]) * (ring[j + 1] - ring[i + 1])
        j = i
    }
    return sign(a)
}

/**
 * Focus = the country's main body: its largest landmass ring plus any rings that sit close to it
 * (islands hugging the mainland), but not far-flung overseas territories. The margin is tuned so
 * a colonial power's distant holdings (e.g. the UK's Cyprus bases) don't drag the view out with
 * them; those bits stay highlighted on the map, they just don't drive the zoom.
 */
internal fun buildFocus(geometry: WorldMapData): Map<String, Focus> =
    geometry.countries.mapNotNull { (iso, shape) ->
        // A 0-ring feature cannot be focused; dropping it beats throwing on .first() at load time.
        val core = shape.rings.maxByOrNull { ring -> polygonArea(ring) } ?: return@mapNotNull null
        val margin = (FOCUS_MARGIN_OVERRIDE[iso] ?: 0.07f) * geometry.width
        val cb = bboxOf(listOf(core))
        val exMinX = cb.minX - margin
        val exMinY = cb.minY - margin
        val exMaxX = cb.maxX + margin
        val exMaxY = cb.maxY + margin
        val included = shape.rings.filter { ring ->
            val rb = bboxOf(listOf(ring))
            rb.minX <= exMaxX && rb.maxX >= exMinX && rb.minY <= exMaxY && rb.maxY >= exMinY
        }
        val fb = bboxOf(included)
        iso to Focus(Offset(fb.cx, fb.cy), fb)
    }.toMap()

internal fun defaultCenter(geometry: WorldMapData): Offset =
    Offset(geometry.width * DEFAULT_CENTER_X_FRACTION, geometry.height / 2f)

internal fun bboxOf(rings: List<FloatArray>): BBox {
    var minX = Float.MAX_VALUE
    var minY = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE
    var maxY = -Float.MAX_VALUE
    for (r in rings) for (i in r.indices step 2) {
        val x = r[i]
        val y = r[i + 1]
        if (x < minX) minX = x
        if (x > maxX) maxX = x
        if (y < minY) minY = y
        if (y > maxY) maxY = y
    }
    return if (minX > maxX) BBox(0f, 0f, 1f, 1f) else BBox(minX, minY, maxX, maxY)
}

internal fun distBetween(a: Offset, b: Offset): Float =
    hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()).toFloat()

internal fun Offset.length(): Float = distBetween(this, Offset.Zero)

/** Smallest-area country whose any outer ring contains the point (resolves enclaves). */
internal fun hitTest(shapes: List<IsoShape>, px: Float, py: Float): String? {
    var best: String? = null
    var bestArea = Float.MAX_VALUE
    for (s in shapes) {
        if (s.area >= bestArea) continue
        // bbox pre-check — skip ray-casting any ring of a country whose bbox misses the tap.
        if (!s.bbox.contains(px, py)) continue
        if (pointInAnyRing(s.rings, px, py)) {
            best = s.iso
            bestArea = s.area
        }
    }
    return best
}

internal fun pointInAnyRing(rings: List<FloatArray>, x: Float, y: Float): Boolean {
    for (ring in rings) if (pointInRing(ring, x, y)) return true
    return false
}

internal fun pointInRing(ring: FloatArray, x: Float, y: Float): Boolean {
    var inside = false
    var j = ring.size - 2
    for (i in ring.indices step 2) {
        val piX = ring[i]
        val piY = ring[i + 1]
        val pjX = ring[j]
        val pjY = ring[j + 1]
        if ((piY > y) != (pjY > y)) {
            val xInt = (pjX - piX) * (y - piY) / (pjY - piY) + piX
            if (x < xInt) inside = !inside
        }
        j = i
    }
    return inside
}

internal fun polygonArea(ring: FloatArray): Float {
    var a = 0f
    var j = ring.size - 2
    for (i in ring.indices step 2) {
        val piX = ring[i]
        val piY = ring[i + 1]
        val pjX = ring[j]
        val pjY = ring[j + 1]
        a += (pjX + piX) * (pjY - piY)
        j = i
    }
    return abs(a) / 2f
}
