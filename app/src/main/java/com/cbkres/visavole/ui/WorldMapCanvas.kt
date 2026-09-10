package com.cbkres.visavole.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cbkres.visavole.data.WorldMapData
import com.cbkres.visavole.domain.Access
import com.cbkres.visavole.domain.AccessLevel

private const val MIN_ZOOM = 1f
private const val MAX_ZOOM = 20f

/**
 * Per-country focus margin as a fraction of the world width (default 7%). A small mainland would
 * otherwise pull a nearby-but-separate territory into the tap-to-zoom focus: the UK's Gibraltar sits
 * only ~4% of the world width south of Great Britain, while its immediate islands (Hebrides, Orkney,
 * Shetland) are all within ~2%. Tightening GB to 3% keeps those islands and drops Gibraltar, so
 * tapping the UK centres on the home islands instead of zooming out over the Mediterranean.
 */
private val FOCUS_MARGIN_OVERRIDE: Map<String, Float> = mapOf("GB" to 0.03f)

private data class IsoShape(
    val iso: String,
    val rings: List<List<Offset>>,
    val path: Path,
    val area: Float,
)

private data class BBox(
    val minX: Float,
    val minY: Float,
    val maxX: Float,
    val maxY: Float,
) {
    val cx get() = (minX + maxX) / 2f
    val cy get() = (minY + maxY) / 2f
    val w get() = (maxX - minX).coerceAtLeast(1f)
    val h get() = (maxY - minY).coerceAtLeast(1f)
}

/** The world point to centre on, plus the bbox used to choose the zoom level for it. */
private data class Focus(val point: Offset, val bbox: BBox)

/**
 * Robinson world choropleth. Each country is filled by the traveller's [Access] level for it.
 *
 * The view is described by the world point sitting at the centre of the canvas ([centerWorld]) and a
 * [zoom] factor, which is the standard way maps model a viewport. That makes "centre on a country"
 * trivial (just move the centre) and keeps pan/zoom consistent at every zoom level. One-finger drag
 * pans, two-finger pinch zooms about the fingers, the +/- buttons zoom about the centre, and the home
 * button resets. Tapping a country (or picking one from the search box) smoothly centres and zooms in.
 */
@Composable
fun WorldMapCanvas(
    geometry: WorldMapData,
    access: Map<String, Access>,
    selected: String?,
    onCountryTap: (String?) -> Unit,
    homeCountries: Set<String> = emptySet(),
    ownVisaCountries: Set<String> = emptySet(),
    controlsBottomPadding: Dp = 24.dp,
    modifier: Modifier = Modifier,
) {
    val shapes: List<IsoShape> = remember(geometry) {
        geometry.countries.map { (iso, shape) ->
            val path = Path()
            var area = 0f
            for (ring in shape.rings) {
                ring.forEachIndexed { i, p ->
                    if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                }
                path.close()
                area += polygonArea(ring)
            }
            IsoShape(iso, shape.rings, path, area)
        }
    }
    // Focus = the country's main body: its largest landmass ring plus any rings that sit close to it
    // (islands hugging the mainland), but not far-flung overseas territories. The margin is tuned so
    // a colonial power's distant holdings (e.g. the UK's Cyprus bases) don't drag the view out with
    // them; those bits stay highlighted on the map, they just don't drive the zoom.
    val focus: Map<String, Focus> = remember(geometry) {
        geometry.countries.mapValues { (iso, shape) ->
            val margin = (FOCUS_MARGIN_OVERRIDE[iso] ?: 0.07f) * geometry.width
            val core = shape.rings.maxByOrNull { ring -> polygonArea(ring) } ?: shape.rings.first()
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
            Focus(Offset(fb.cx, fb.cy), fb)
        }
    }

    val latestOnTap by rememberUpdatedState(onCountryTap)
    val latestSelected by rememberUpdatedState(selected)

    var canvasSize by remember { mutableStateOf(Size.Zero) }
    var zoom by remember { mutableStateOf(1f) }
    var centerWorld by remember { mutableStateOf(Offset(geometry.width / 2f, geometry.height / 2f)) }

    // The scale at which the whole world fits the canvas (zoom == 1).
    val baseScale = if (canvasSize.width > 0f) fitScale(geometry, canvasSize.width, canvasSize.height) else 1f

    // Smoothly centre and zoom in when a country becomes selected.
    LaunchedEffect(selected, canvasSize) {
        if (selected == null || canvasSize.width <= 0f) return@LaunchedEffect
        val f = focus[selected] ?: return@LaunchedEffect
        val w = canvasSize.width
        val h = canvasSize.height
        val bs = fitScale(geometry, w, h)
        val targetZoom = (minOf(w * 0.75f / f.bbox.w, h * 0.75f / f.bbox.h) / bs).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val targetCenter = clampCenter(f.point, bs * targetZoom, geometry, w, h)
        val fromZoom = zoom
        val fromCenter = centerWorld
        Animatable(0f).animateTo(1f, tween(350)) {
            val k = value
            zoom = fromZoom + (targetZoom - fromZoom) * k
            centerWorld = Offset(
                fromCenter.x + (targetCenter.x - fromCenter.x) * k,
                fromCenter.y + (targetCenter.y - fromCenter.y) * k,
            )
        }
    }

    Box(modifier.clipToBounds()) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { canvasSize = Size(it.width.toFloat(), it.height.toFloat()) }
                .pointerInput(geometry) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        var last = down.position
                        var lastDist = Float.NaN
                        var moved = false
                        while (true) {
                            val ev = awaitPointerEvent()
                            val act = ev.changes.filter { it.pressed }
                            val ds = if (canvasSize.width > 0f) canvasSize else Size(1f, 1f)
                            val w = ds.width
                            val h = ds.height
                            val bs = fitScale(geometry, w, h)
                            when {
                                act.size >= 2 -> {
                                    val p0 = act[0].position
                                    val p1 = act[1].position
                                    val c = Offset((p0.x + p1.x) / 2f, (p0.y + p1.y) / 2f)
                                    val dist = distBetween(p0, p1)
                                    if (!lastDist.isNaN() && lastDist > 0f) {
                                        val (nz, nc) = zoomAround(centerWorld, zoom, bs, c, dist / lastDist, geometry, w, h)
                                        zoom = nz
                                        centerWorld = nc
                                        // Also follow the centroid's movement (two-finger pan).
                                        val d = Offset(c.x - last.x, c.y - last.y)
                                        val sc = bs * zoom
                                        centerWorld = clampCenter(centerWorld - Offset(d.x / sc, d.y / sc), sc, geometry, w, h)
                                    }
                                    lastDist = dist
                                    last = c
                                    moved = true
                                }
                                act.size == 1 -> {
                                    val p = act[0].position
                                    val d = Offset(p.x - last.x, p.y - last.y)
                                    if (d.length() > viewConfiguration.touchSlop) {
                                        val sc = bs * zoom
                                        centerWorld = clampCenter(centerWorld - Offset(d.x / sc, d.y / sc), sc, geometry, w, h)
                                        moved = true
                                    }
                                    last = p
                                }
                                else -> break
                            }
                            ev.changes.forEach { it.consume() }
                        }
                        if (!moved) {
                            val ds = if (canvasSize.width > 0f) canvasSize else Size(1f, 1f)
                            val sc = fitScale(geometry, ds.width, ds.height) * zoom
                            val wx = centerWorld.x + (down.position.x - ds.width / 2f) / sc
                            val wy = centerWorld.y + (down.position.y - ds.height / 2f) / sc
                            val hit = hitTest(shapes, wx, wy)
                            latestOnTap(if (hit == null || hit == latestSelected) null else hit)
                        }
                    }
                },
        ) {
            drawRect(OCEAN)
            val ds = if (canvasSize.width > 0f) canvasSize else size
            val sc = fitScale(geometry, ds.width, ds.height) * zoom
            // Screen point for a world point: (canvasCenter) + (world - centerWorld) * sc.
            val tx = ds.width / 2f - centerWorld.x * sc
            val ty = ds.height / 2f - centerWorld.y * sc
            translate(tx, ty) {
                scale(sc, sc, pivot = Offset.Zero) {
                    val hair = 1.0f / sc
                    // Pass 1: fills. Pass 2: borders on top of every fill so shared borders are
                    // crisp (not overdrawn by a neighbour's fill). Width is constant on screen.
                    for (s in shapes) {
                        val a = access[s.iso]
                        val lvl = a?.level
                        val fill = when {
                            s.iso in homeCountries -> HOME
                            lvl == AccessLevel.COVERED && s.iso in ownVisaCountries -> COVERED_OWN
                            lvl == AccessLevel.RESIDENCE -> RESIDENCE_FILL
                            else -> colorFor(lvl)
                        }
                        drawPath(s.path, fill)
                    }
                    for (s in shapes) {
                        drawPath(s.path, LAND_STROKE, style = Stroke(width = hair))
                    }
                    selected?.let { sel ->
                        shapes.firstOrNull { it.iso == sel }?.let { s ->
                            drawPath(s.path, Color.White, style = Stroke(width = 2.2f / sc))
                        }
                    }
                }
            }
        }

        Surface(
            tonalElevation = 6.dp,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(horizontal = 12.dp)
                .padding(top = 12.dp, bottom = controlsBottomPadding),
        ) {
            val cx = canvasSize.width / 2f
            val cy = canvasSize.height / 2f
            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.padding(4.dp),
            ) {
                IconButton(
                    onClick = {
                        val (nz, nc) = zoomAround(centerWorld, zoom, baseScale, Offset(cx, cy), 1.5f, geometry, canvasSize.width, canvasSize.height)
                        zoom = nz
                        centerWorld = nc
                    },
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "Zoom in")
                }
                IconButton(
                    onClick = {
                        val (nz, nc) = zoomAround(centerWorld, zoom, baseScale, Offset(cx, cy), 1f / 1.5f, geometry, canvasSize.width, canvasSize.height)
                        zoom = nz
                        centerWorld = nc
                    },
                ) {
                    Text("-", style = MaterialTheme.typography.titleMedium)
                }
                IconButton(onClick = {
                    zoom = 1f
                    centerWorld = Offset(geometry.width / 2f, geometry.height / 2f)
                }) {
                    Icon(Icons.Filled.Home, contentDescription = "Reset view")
                }
            }
        }
    }
}

/** Scale at which the whole world (width x height) fits inside a w x h canvas. */
private fun fitScale(geometry: WorldMapData, w: Float, h: Float): Float =
    minOf(w / geometry.width, h / geometry.height)

/**
 * Keep the world's bounding box overlapping the canvas (a sliver always visible) so the user can pan
 * freely — including to the very top and bottom — but can never lose the map entirely.
 */
private fun clampCenter(center: Offset, scale: Float, geometry: WorldMapData, w: Float, h: Float): Offset {
    val hx = w / 2f / scale
    val hy = h / 2f / scale
    val x = center.x.coerceIn(-hx, geometry.width + hx)
    val y = center.y.coerceIn(-hy, geometry.height + hy)
    return Offset(x, y)
}

/** Zoom by [factor] keeping the world point under [pivot] fixed; returns (newZoom, newCenter). */
private fun zoomAround(
    center: Offset,
    zoom: Float,
    baseScale: Float,
    pivot: Offset,
    factor: Float,
    geometry: WorldMapData,
    w: Float,
    h: Float,
): Pair<Float, Offset> {
    val nz = (zoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
    if (nz == zoom) return zoom to center
    val os = baseScale * zoom
    val ns = baseScale * nz
    val rx = pivot.x - w / 2f
    val ry = pivot.y - h / 2f
    val wx = center.x + rx / os
    val wy = center.y + ry / os
    val ncx = wx - rx / ns
    val ncy = wy - ry / ns
    return nz to clampCenter(Offset(ncx, ncy), ns, geometry, w, h)
}

private fun bboxOf(rings: List<List<Offset>>): BBox {
    var minX = Float.MAX_VALUE
    var minY = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE
    var maxY = -Float.MAX_VALUE
    for (r in rings) for (p in r) {
        if (p.x < minX) minX = p.x
        if (p.x > maxX) maxX = p.x
        if (p.y < minY) minY = p.y
        if (p.y > maxY) maxY = p.y
    }
    return if (minX > maxX) BBox(0f, 0f, 1f, 1f) else BBox(minX, minY, maxX, maxY)
}

private fun distBetween(a: Offset, b: Offset): Float =
    kotlin.math.hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()).toFloat()

private fun Offset.length(): Float = distBetween(this, Offset.Zero)

/** Smallest-area country whose any outer ring contains the point (resolves enclaves). */
private fun hitTest(shapes: List<IsoShape>, px: Float, py: Float): String? {
    var best: String? = null
    var bestArea = Float.MAX_VALUE
    for (s in shapes) {
        if (s.area >= bestArea) continue
        if (pointInAnyRing(s.rings, px, py)) {
            best = s.iso
            bestArea = s.area
        }
    }
    return best
}

private fun pointInAnyRing(rings: List<List<Offset>>, x: Float, y: Float): Boolean {
    for (ring in rings) if (pointInRing(ring, x, y)) return true
    return false
}

private fun pointInRing(ring: List<Offset>, x: Float, y: Float): Boolean {
    var inside = false
    var j = ring.size - 1
    for (i in ring.indices) {
        val pi = ring[i]
        val pj = ring[j]
        if ((pi.y > y) != (pj.y > y)) {
            val xInt = (pj.x - pi.x) * (y - pi.y) / (pj.y - pi.y) + pi.x
            if (x < xInt) inside = !inside
        }
        j = i
    }
    return inside
}

private fun polygonArea(ring: List<Offset>): Float {
    var a = 0f
    var j = ring.size - 1
    for (i in ring.indices) {
        a += (ring[j].x + ring[i].x) * (ring[j].y - ring[i].y)
        j = i
    }
    return kotlin.math.abs(a) / 2f
}
